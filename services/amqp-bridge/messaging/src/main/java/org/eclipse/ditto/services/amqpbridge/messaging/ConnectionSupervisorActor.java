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

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkArgument;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.jms.JMSException;
import javax.jms.JMSRuntimeException;
import javax.naming.NamingException;

import org.eclipse.ditto.model.base.headers.WithDittoHeaders;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.signals.commands.amqpbridge.exceptions.ConnectionUnavailableException;

import akka.actor.AbstractActor;
import akka.actor.ActorKilledException;
import akka.actor.ActorRef;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.Terminated;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.DeciderBuilder;
import akka.japi.pf.ReceiveBuilder;
import scala.concurrent.duration.FiniteDuration;

/**
 * Supervisor for {@link ConnectionActor} which means it will create, start and watch it as child actor.
 * <p>
 * If the child terminates, it will wait for the calculated exponential backoff time and restart it afterwards. The
 * child has to send {@link ManualReset} after it started successfully. Between the termination of the child and the
 * restart, this actor answers to all requests with a
 * {@link ConnectionUnavailableException} as fail fast
 * strategy.
 * </p>
 */
public final class ConnectionSupervisorActor extends AbstractActor {

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

    private final String connectionId;
    private final Duration minBackoff;
    private final Duration maxBackoff;
    private final double randomFactor;
    private final SupervisorStrategy supervisorStrategy;

    private Props persistenceActorProps;
    private ActorRef child;
    private long restartCount;

    private ConnectionSupervisorActor(final SupervisorStrategy supervisorStrategy,
            final Duration minBackoff,
            final Duration maxBackoff,
            final double randomFactor,
            final ConnectionActorPropsFactory connectionActorPropsFactory) {
        try {
            this.connectionId = checkArgument(
                    URLDecoder.decode(getSelf().path().name(), StandardCharsets.UTF_8.name()),
                    arg -> arg.indexOf(':') > 0,
                    () -> "ConnectionId must contain a type prefix.");
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalStateException("Unsupported encoding", e);
        }
        this.supervisorStrategy = supervisorStrategy;
        this.minBackoff = minBackoff;
        this.maxBackoff = maxBackoff;
        this.randomFactor = randomFactor;
        this.persistenceActorProps = connectionActorPropsFactory.getActorPropsForType(connectionId);
    }

    /**
     * Props for creating a {@link ConnectionSupervisorActor}.
     * <p>
     * Exceptions in the child are handled with a supervision strategy that restarts the child on {@link
     * NullPointerException}'s, stops it for {@link ActorKilledException}'s and escalates all others.
     * </p>
     *
     * @param minBackoff minimum (initial) duration until the child actor will started again, if it is terminated.
     * @param maxBackoff the exponential back-off is capped to this duration.
     * @param randomFactor after calculation of the exponential back-off an additional random delay based on this factor
     * is added, e.g. `0.2` adds up to `20%` delay. In order to skip this additional delay pass in `0`.
     * for accessing the connection cache in cluster.
     * @param connectionActorPropsFactory factory to create actor props for given connection type
     * @return the {@link Props} to create this actor.
     */
    public static Props props(final Duration minBackoff,
            final Duration maxBackoff,
            final double randomFactor,
            final ConnectionActorPropsFactory connectionActorPropsFactory) {

        return Props.create(ConnectionSupervisorActor.class, new Creator<ConnectionSupervisorActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public ConnectionSupervisorActor create() {
                return new ConnectionSupervisorActor(new OneForOneStrategy(true, DeciderBuilder
                        .match(JMSRuntimeException.class, e -> SupervisorStrategy.resume())
                        .match(NullPointerException.class, e -> SupervisorStrategy.restart())
                        .match(JMSException.class, e -> SupervisorStrategy.stop())
                        .match(NamingException.class, e -> SupervisorStrategy.stop())
                        .match(ActorKilledException.class, e -> SupervisorStrategy.stop())
                        .matchAny(e -> SupervisorStrategy.escalate())
                        .build()), minBackoff, maxBackoff, randomFactor, connectionActorPropsFactory);
            }
        });
    }

    @Override
    public SupervisorStrategy supervisorStrategy() {
        return supervisorStrategy;
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        startChild();
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(StartChild.class, startChild -> startChild())
                .match(ManualReset.class, manualReset -> restartCount = 0)
                .match(Terminated.class, terminated -> {
                    log.info("Persistence actor for Connection with ID '{}' terminated abnormally", connectionId);
                    child = null;
                    final Duration restartDelay = calculateRestartDelay();
                    getContext().system().scheduler()
                            .scheduleOnce(new FiniteDuration(restartDelay.toNanos(), TimeUnit.NANOSECONDS), getSelf(),
                                    StartChild.getInstance(),
                                    getContext().dispatcher(), null);
                    restartCount += 1;
                })
                .matchAny(message -> {
                    if (getChild().isPresent()) {
                        if (child.equals(getSender())) {
                            log.warning("Received unhandled message from child actor '{}': {}", connectionId, message);
                            unhandled(message);
                        } else {
                            child.forward(message, getContext());
                        }
                    } else {
                        log.warning("Received message '{}' during downtime of child actor for Connection with ID '{}'",
                                message.getClass().getSimpleName(), connectionId);
                        final ConnectionUnavailableException.Builder builder =
                                ConnectionUnavailableException.newBuilder(connectionId);
                        if (message instanceof WithDittoHeaders) {
                            builder.dittoHeaders(((WithDittoHeaders) message).getDittoHeaders());
                        }
                        getSender().tell(builder.build(), getSelf());
                    }
                })
                .build();
    }

    private Optional<ActorRef> getChild() {
        return Optional.ofNullable(child);
    }

    private void startChild() {
        if (!getChild().isPresent()) {
            log.debug("Starting persistence actor for Connection with ID '{}'", connectionId);
            final ActorRef childRef = getContext().actorOf(persistenceActorProps, "pa");
            child = getContext().watch(childRef);
        }
    }

    private Duration calculateRestartDelay() {
        final double rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor;
        if (restartCount >= 30) // Duration overflow protection (> 100 years)
        {
            return maxBackoff;
        } else {
            final double backoff = minBackoff.toNanos() * Math.pow(2, restartCount) * rnd;
            return Duration.ofNanos(Math.min(maxBackoff.toNanos(), (long) backoff));
        }
    }

    /**
     * Message that should be sent to this actor to indicate a working child and reset the exponential backoff
     * mechanism.
     */
    static final class ManualReset {

        private ManualReset() {
            // no-op
        }

        static ManualReset getInstance() {
            return new ManualReset();
        }

    }

    /**
     * Message that is sent to the actor by itself to restart the child.
     */
    private static final class StartChild {

        private StartChild() {
            // no-op
        }

        static StartChild getInstance() {
            return new StartChild();
        }

    }

}
