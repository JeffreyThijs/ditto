/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.connectivity.messaging.kafka;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionId;
import org.eclipse.ditto.services.connectivity.config.ConnectionConfig;
import org.eclipse.ditto.services.connectivity.config.KafkaConfig;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientActor;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientData;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientConnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientDisconnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ConnectionFailure;
import org.eclipse.ditto.services.connectivity.messaging.internal.ImmutableConnectionFailure;
import org.eclipse.ditto.services.connectivity.util.ConnectivityMdcEntryKey;
import org.eclipse.ditto.services.models.connectivity.BaseClientState;
import org.eclipse.ditto.signals.commands.connectivity.modify.TestConnection;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Status;
import akka.japi.pf.FSMStateFunctionBuilder;

/**
 * Actor which handles connection to Kafka server.
 */
public final class KafkaClientActor extends BaseClientActor {

    private final KafkaPublisherActorFactory publisherActorFactory;
    private final Set<ActorRef> pendingStatusReportsFromStreams;
    private final KafkaConnectionFactory connectionFactory;

    private CompletableFuture<Status.Status> testConnectionFuture = null;
    private ActorRef kafkaPublisherActor;

    @SuppressWarnings("unused") // used by `props` via reflection
    private KafkaClientActor(final Connection connection,
            @Nullable final ActorRef proxyActor,
            final ActorRef connectionActor,
            final KafkaPublisherActorFactory factory) {

        super(connection, proxyActor, connectionActor);
        final ConnectionConfig connectionConfig = connectivityConfig.getConnectionConfig();
        final KafkaConfig kafkaConfig = connectionConfig.getKafkaConfig();
        connectionFactory =
                DefaultKafkaConnectionFactory.getInstance(connection, kafkaConfig, getClientId(connection.getId()));
        publisherActorFactory = factory;
        pendingStatusReportsFromStreams = new HashSet<>();
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param connection the connection.
     * @param proxyActor the actor used to send signals into the ditto cluster.
     * @param connectionActor the connectionPersistenceActor which created this client.
     * @param factory factory for creating a kafka publisher actor.
     * @return the Akka configuration Props object.
     */
    public static Props props(final Connection connection,
            @Nullable final ActorRef proxyActor,
            final ActorRef connectionActor,
            final KafkaPublisherActorFactory factory) {

        return Props.create(KafkaClientActor.class, validateConnection(connection), proxyActor, connectionActor,
                factory);
    }

    private static Connection validateConnection(final Connection connection) {
        // nothing to do so far
        return connection;
    }

    @Override
    protected FSMStateFunctionBuilder<BaseClientState, BaseClientData> inTestingState() {
        return super.inTestingState()
                .event(Status.Status.class, (e, d) -> !Objects.equals(getSender(), getSelf()),
                        (status, data) -> handleStatusReportFromChildren(status))
                .event(ClientConnected.class, BaseClientData.class, (event, data) -> {
                    final String url = data.getConnection().getUri();
                    final String message = "Kafka connection to " + url + " established successfully";
                    completeTestConnectionFuture(new Status.Success(message));
                    return stay();
                })
                .event(ConnectionFailure.class, BaseClientData.class, (event, data) -> {
                    completeTestConnectionFuture(new Status.Failure(event.getFailure().cause()));
                    return stay();
                });
    }

    @Override
    protected FSMStateFunctionBuilder<BaseClientState, BaseClientData> inConnectingState() {
        return super.inConnectingState()
                .event(Status.Status.class, (status, data) -> handleStatusReportFromChildren(status));
    }

    @Override
    protected CompletionStage<Status.Status> doTestConnection(final TestConnection testConnectionCommand) {
        if (testConnectionFuture != null) {
            final Exception error =
                    new IllegalStateException("Can't test new connection since a test is already running.");
            return CompletableFuture.completedFuture(new Status.Failure(error));
        }
        testConnectionFuture = new CompletableFuture<>();
        final DittoHeaders dittoHeaders = testConnectionCommand.getDittoHeaders();
        final String correlationId = dittoHeaders.getCorrelationId().orElse(null);
        connectClient(true, testConnectionCommand.getEntityId(), correlationId);
        return testConnectionFuture;
    }

    @Override
    protected void doConnectClient(final Connection connection, @Nullable final ActorRef origin) {
        connectClient(false, connection.getId(), null);
    }

    @Override
    protected void doDisconnectClient(final Connection connection, @Nullable final ActorRef origin) {
        self().tell((ClientDisconnected) () -> null, origin);
    }

    @Override
    protected ActorRef getPublisherActor() {
        return kafkaPublisherActor;
    }

    /**
     * Start Kafka publishers, expect "Status.Success" from each of them, then send "ClientConnected" to self.
     *
     * @param dryRun if set to true, exchange no message between the broker and the Ditto cluster.
     * @param connectionId the ID of the connection to connect the client for.
     * @param correlationId the correlation ID for logging or {@code null} if no correlation ID is known.
     */
    private void connectClient(final boolean dryRun, final ConnectionId connectionId,
            @Nullable final CharSequence correlationId) {

        // start publisher
        startKafkaPublisher(dryRun, connectionId, correlationId);
        // no command consumers as we don't support consuming from sources yet
    }

    private void startKafkaPublisher(final boolean dryRun, final ConnectionId connectionId,
            @Nullable final CharSequence correlationid) {

        logger.withCorrelationId(correlationid).withMdcEntry(ConnectivityMdcEntryKey.CONNECTION_ID, connectionId)
                .info("Starting Kafka publisher actor.");
        // ensure no previous publisher stays in memory
        stopPublisherActor();
        final Props publisherActorProps =
                publisherActorFactory.props(connection(), connectionFactory, dryRun, getDefaultClientId());
        kafkaPublisherActor = startChildActorConflictFree(publisherActorFactory.getActorName(), publisherActorProps);
        pendingStatusReportsFromStreams.add(kafkaPublisherActor);
    }

    @Override
    protected void cleanupResourcesForConnection() {
        pendingStatusReportsFromStreams.clear();
        stopPublisherActor();
    }

    @Override
    protected CompletionStage<Status.Status> startPublisherActor() {
        return CompletableFuture.completedFuture(DONE);
    }

    private void stopPublisherActor() {
        if (kafkaPublisherActor != null) {
            logger.debug("Stopping child actor <{}>.", kafkaPublisherActor.path());
            // shutdown using a message, so the actor can clean up first
            kafkaPublisherActor.tell(KafkaPublisherActor.GracefulStop.INSTANCE, getSelf());
        }
    }

    private State<BaseClientState, BaseClientData> handleStatusReportFromChildren(final Status.Status status) {
        if (pendingStatusReportsFromStreams.contains(getSender())) {
            pendingStatusReportsFromStreams.remove(getSender());
            if (status instanceof Status.Failure) {
                final Status.Failure failure = (Status.Failure) status;
                final ConnectionFailure connectionFailure =
                        new ImmutableConnectionFailure(null, failure.cause(), "child failed");
                getSelf().tell(connectionFailure, ActorRef.noSender());
            } else if (pendingStatusReportsFromStreams.isEmpty()) {
                // all children are ready; this client actor is connected.
                getSelf().tell((ClientConnected) () -> null, ActorRef.noSender());
            }
        }
        return stay();
    }

    private void completeTestConnectionFuture(final Status.Status testResult) {
        if (testConnectionFuture != null) {
            testConnectionFuture.complete(testResult);
        } else {
            // no future; test failed.
            final Exception exception = new IllegalStateException(
                    "Could not complete testing connection since the test was already completed or wasn't started.");
            getSelf().tell(new Status.Failure(exception), getSelf());
        }
    }
}
