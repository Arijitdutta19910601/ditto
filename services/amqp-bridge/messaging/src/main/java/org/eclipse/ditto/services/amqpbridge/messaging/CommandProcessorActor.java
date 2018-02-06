/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.amqpbridge.messaging;

import static java.util.stream.Collectors.toMap;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;

import org.apache.qpid.jms.message.JmsMessage;
import org.apache.qpid.jms.provider.amqp.message.AmqpJmsMessageFacade;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.amqpbridge.MappingContext;
import org.eclipse.ditto.model.base.auth.AuthorizationSubject;
import org.eclipse.ditto.model.base.common.DittoConstants;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.headers.DittoHeadersBuilder;
import org.eclipse.ditto.protocoladapter.Adaptable;
import org.eclipse.ditto.protocoladapter.DittoProtocolAdapter;
import org.eclipse.ditto.protocoladapter.JsonifiableAdaptable;
import org.eclipse.ditto.protocoladapter.ProtocolFactory;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.PayloadMapper;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.PayloadMapperMessage;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.PayloadMapperOptions;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.PayloadMappers;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.signals.commands.base.Command;
import org.eclipse.ditto.signals.commands.base.CommandResponse;
import org.eclipse.ditto.signals.commands.things.ThingErrorResponse;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.ExtendedActorSystem;
import akka.actor.Props;
import akka.actor.Status;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import kamon.Kamon;
import kamon.trace.Segment;
import kamon.trace.TraceContext;
import scala.Option;
import scala.Tuple2;
import scala.collection.JavaConversions;
import scala.reflect.ClassTag;
import scala.util.Try;

/**
 * This Actor processes incoming {@link Command}s and dispatches them via {@link DistributedPubSubMediator} to a
 * consumer actor.
 */
public final class CommandProcessorActor extends AbstractActor {

    /**
     * The name of this Actor in the ActorSystem.
     */
    static final String ACTOR_NAME_PREFIX = "amqpCommandProcessor-";

    private static final DittoProtocolAdapter PROTOCOL_ADAPTER = DittoProtocolAdapter.newInstance();

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

    private final ActorRef pubSubMediator;
    private final String pubSubTargetActorPath;
    private final AuthorizationSubject authorizationSubject;
    private final Cache<String, TraceContext> traces;

    private final Map<String, PayloadMapper> payloadMappers;

    private CommandProcessorActor(final ActorRef pubSubMediator, final String pubSubTargetActorPath,
            final AuthorizationSubject authorizationSubject, final List<MappingContext> mappingContexts) {
        this.pubSubMediator = pubSubMediator;
        this.pubSubTargetActorPath = pubSubTargetActorPath;
        this.authorizationSubject = authorizationSubject;
        traces = CacheBuilder.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .removalListener((RemovalListener<String, TraceContext>) notification
                        -> log.info("Trace for {} expired.", notification.getKey()))
                .build();

        final ActorSystem actorSystem = getContext().getSystem();

        payloadMappers = mappingContexts.stream()
                .collect(Collectors.toMap(MappingContext::getContentType, mappingContext -> {

                    final Map<String, String> optionsMap = mappingContext.getOptions();
                    final PayloadMapperOptions.Builder optionsBuilder =
                            PayloadMappers.createMapperOptionsBuilder(optionsMap);
                    final PayloadMapperOptions options = optionsBuilder.build();

                    // dynamically lookup static method for instantiating the payloadMapper:
                    return Arrays.stream(PayloadMappers.class.getDeclaredMethods())
                            .filter(m -> m.getReturnType().equals(PayloadMapper.class))
                            .filter(m -> m.getName()
                                    .toLowerCase()
                                    .contains(mappingContext.getMappingEngine().toLowerCase()))
                            .findFirst()
                            .map(m -> {
                                try {
                                    return (PayloadMapper) m.invoke(null, options);
                                } catch (final ClassCastException | IllegalAccessException |
                                        InvocationTargetException e) {
                                    log.error(e, "Could not initialize PayloadMapper: <{}>", e.getMessage());
                                    return null;
                                }
                            })
                            // as fallback, try loading the configured "mappingEngine" as implementation of PayloadMapper class:
                            .orElseGet(() -> {
                                final String mappingEngineClass = mappingContext.getMappingEngine();
                                final ClassTag<PayloadMapper> tag =
                                        scala.reflect.ClassTag$.MODULE$.apply(PayloadMapper.class);
                                final List<Tuple2<Class<?>, Object>> constructorArgs = new ArrayList<>();
                                constructorArgs.add(Tuple2.apply(PayloadMapperOptions.class, options));
                                final Try<PayloadMapper> payloadMapperImpl = ((ExtendedActorSystem) actorSystem)
                                        .dynamicAccess()
                                        .createInstanceFor(mappingEngineClass,
                                                JavaConversions.asScalaBuffer(constructorArgs).toList(), tag);

                                if (payloadMapperImpl.isSuccess()) {
                                    return payloadMapperImpl.get();
                                } else {
                                    log.error("Could not initialize mappingEngine <{}>",
                                            mappingContext.getMappingEngine());
                                    return null;
                                }
                            });
                }));
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param pubSubMediator the akka pubsub mediator actor.
     * @param pubSubTargetActorPath the path of the command consuming actor (via pubsub).
     * @param authorizationSubject the authorized subject that are set in command headers.
     * @return the Akka configuration Props object
     */
    static Props props(final ActorRef pubSubMediator, final String pubSubTargetActorPath,
            final AuthorizationSubject authorizationSubject, final List<MappingContext> mappingContexts) {

        return Props.create(CommandProcessorActor.class, new Creator<CommandProcessorActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public CommandProcessorActor create() {
                return new CommandProcessorActor(pubSubMediator, pubSubTargetActorPath, authorizationSubject,
                        mappingContexts);
            }
        });
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(InternalMessage.class, this::handle)
                .match(CommandResponse.class, this::handleCommandResponse)
                .match(DittoRuntimeException.class, this::handleDittoRuntimeException)
                .match(Status.Failure.class, f -> log.error(f.cause(), "Got an unexpected failure."))
                .matchAny(m -> {
                    log.debug("Unknown message: {}", m);
                    unhandled(m);
                }).build();
    }

    private void handle(final InternalMessage m) {
        try {

            final TraceContext traceContext = Kamon.tracer().newContext("commandProcessor",
                    Option.apply(m.getDittoHeaders().getCorrelationId().orElse("no-correlation-id")));
            final Command<?> command = buildCommandFromPublicProtocol(m, traceContext);
            traceContext.finish();

            if (command != null) {
                traceCommand(command);
                log.info("Publishing '{}' to '{}'", command.getType(), pubSubTargetActorPath);
                pubSubMediator.tell(new DistributedPubSubMediator.Send(pubSubTargetActorPath, command, true),
                        getSelf());
            }
        } finally {
            getSender().tell(m.getAckMessage(), self());
        }
    }

    private void handleDittoRuntimeException(final DittoRuntimeException exception) {
        final ThingErrorResponse errorResponse = ThingErrorResponse.of(exception);

        logDittoRuntimeException(exception);
        handleCommandResponse(errorResponse);
    }

    private void logDittoRuntimeException(final DittoRuntimeException exception) {
        LogUtil.enhanceLogWithCorrelationId(log, exception);

        final String msgTemplate = "Got DittoRuntimeException '{}' when command via AMQP was processed: {}";
        log.info(msgTemplate, exception.getErrorCode(), exception.getMessage());
    }

    private void handleCommandResponse(final CommandResponse response) {
        LogUtil.enhanceLogWithCorrelationId(log, response);

        // TODO map back

        if (response.getStatusCodeValue() < HttpStatusCode.BAD_REQUEST.toInt()) {
            log.debug("Received response: {}", response);
        } else {
            log.info("Received error response: {}", response);
        }

        response.getDittoHeaders().getCorrelationId().ifPresent(cid -> {
            final TraceContext traceContext = traces.getIfPresent(cid);
            traces.invalidate(cid);
            if (traceContext != null) {
                traceContext.finish();
            } else {
                log.info("Trace missing for response: '{}'", response);
            }
        });
    }

    private void traceCommand(final Command<?> command) {
        command.getDittoHeaders().getCorrelationId().ifPresent(correlationId -> {
            final Option<String> token = Option.apply(correlationId);
            final TraceContext traceContext = Kamon.tracer().newContext("roundtrip.amqp_" + command.getType(), token);
            traceContext.addMetadata("command", command.getType());
            traces.put(correlationId, traceContext);
        });
    }

    private Command<?> buildCommandFromPublicProtocol(final InternalMessage message,
            final TraceContext traceContext) {
        try {
            final DittoHeaders dittoHeaders = message.getDittoHeaders();
            final String contentType = message.getDittoHeaders().get("content-type");

            JsonifiableAdaptable jsonifiableAdaptable = null;
            if (!DittoConstants.DITTO_PROTOCOL_CONTENT_TYPE.equalsIgnoreCase(contentType)) {

                final Segment mappingSegment = traceContext
                        .startSegment("mapping", "payload-mapping", "commandProcessor");

                final Optional<PayloadMapper> payloadMapperOptional = Optional.ofNullable(payloadMappers.get(contentType));
                if (payloadMapperOptional.isPresent()) {
                    final PayloadMapper payloadMapper = payloadMapperOptional.get();

                    final ByteBuffer rawMessage;
                    final String stringMessage;
                    if (message instanceof TextMessage) {
                        rawMessage = null;
                        stringMessage = ((TextMessage) message).getText();
                    } else if (message instanceof BytesMessage) {
                        final BytesMessage bytesMessage = (BytesMessage) message;
                        final long bodyLength = bytesMessage.getBodyLength();
                        if (bodyLength >= Integer.MIN_VALUE && bodyLength <= Integer.MAX_VALUE) {
                            final int length = (int) bodyLength;
                            final ByteBuffer byteBuffer = ByteBuffer.allocate(length);
                            bytesMessage.readBytes(byteBuffer.array());
                            rawMessage = byteBuffer;
                            stringMessage = null;
                        } else {
                            throw new IllegalArgumentException("Message too large...");
                        }
                    } else {
                        throw new IllegalArgumentException("Only messages of type TEXT or BYTE are supported.");
                    }
                    final PayloadMapperMessage payloadMapperMessage = PayloadMappers.createPayloadMapperMessage(
                            contentType, rawMessage, stringMessage, dittoHeaders);

                    final Adaptable adaptable = payloadMapper.mapIncoming(payloadMapperMessage);

                    mappingSegment.finish();

                    jsonifiableAdaptable = ProtocolFactory.wrapAsJsonifiableAdaptable(adaptable);

                    log.debug("Successfully mapped message with content-type <{}> and PayloadMapper <{}> to: <{}>",
                            contentType, payloadMapper.getClass().getSimpleName(), jsonifiableAdaptable);
                } else {
                    log.warning("No PayloadMapper found for content-type <{}>, trying to interpret as DittoProtocol " +
                            "message..", contentType);
                    mappingSegment.finishWithError(new IllegalStateException("No PayloadMapper found"));
                }
            } else if (DittoConstants.DITTO_PROTOCOL_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
                log.info("Received message had DittoProtocol content-type <{}>", contentType);
            } else {
                log.info("Received message had unknown content-type <{}>, trying to interpret as DittoProtocol message..",
                        contentType);
            }

            if (jsonifiableAdaptable == null) {
                // fall back trying to interpret as DittoProtocol
                final JsonObject publicCommandJsonObject = JsonFactory.newObject(message.getCommandJsonString());


                // use correlationId from json payload if present
                // TODO DG rly required??
//                jsonifiableAdaptable.getHeaders()
//                        .flatMap(DittoHeaders::getCorrelationId)
//                        .ifPresent(dittoHeadersBuilder::correlationId);
                LogUtil.enhanceLogWithCorrelationId(log, dittoHeaders.getCorrelationId());
                jsonifiableAdaptable = ProtocolFactory.jsonifiableAdaptableFromJson(publicCommandJsonObject);
            }

            log.debug("received public command: {}", jsonifiableAdaptable.toJsonString());

            final Segment protocolAdapterSegment = traceContext
                    .startSegment("protocoladapter", "payload-mapping", "commandProcessor");
            // convert to internal command with DittoProtocolAdapter
            final Command<?> command = (Command<?>) PROTOCOL_ADAPTER.fromAdaptable(jsonifiableAdaptable);
            protocolAdapterSegment.finish();
            return command.setDittoHeaders(dittoHeaders);
        } catch (final DittoRuntimeException e) {
            log.info("Got DittoRuntimeException '{}' when command was parsed: {}", e.getErrorCode(), e.getMessage());
            return null;
        } catch (final Exception e) {
            log.info("Unexpected Exception: {}", e.getMessage(), e);
            return null;
        }
    }
}
