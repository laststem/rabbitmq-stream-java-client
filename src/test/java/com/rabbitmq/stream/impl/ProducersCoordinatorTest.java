// Copyright (c) 2020 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Stream Java client library, is dual-licensed under the
// Mozilla Public License 2.0 ("MPL"), and the Apache License version 2 ("ASL").
// For the MPL, please see LICENSE-MPL-RabbitMQ. For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyByte;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.stream.BackOffDelayPolicy;
import com.rabbitmq.stream.Constants;
import com.rabbitmq.stream.StreamDoesNotExistException;
import com.rabbitmq.stream.impl.Client.Broker;
import com.rabbitmq.stream.impl.Client.StreamMetadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

public class ProducersCoordinatorTest {

  @Mock StreamEnvironment environment;
  @Mock Client locator;
  @Mock StreamProducer producer;
  @Mock StreamConsumer committingConsumer;
  @Mock Function<Client.ClientParameters, Client> clientFactory;
  @Mock Client client;
  AutoCloseable mocks;
  ProducersCoordinator coordinator;
  ScheduledExecutorService scheduledExecutorService;

  volatile Client.ShutdownListener shutdownListener;
  volatile Client.MetadataListener metadataListener;

  static Duration ms(long ms) {
    return Duration.ofMillis(ms);
  }

  @BeforeEach
  void init() {
    Client.ClientParameters clientParameters =
        new Client.ClientParameters() {
          @Override
          public Client.ClientParameters shutdownListener(
              Client.ShutdownListener shutdownListener) {
            ProducersCoordinatorTest.this.shutdownListener = shutdownListener;
            return super.shutdownListener(shutdownListener);
          }

          @Override
          public Client.ClientParameters metadataListener(
              Client.MetadataListener metadataListener) {
            ProducersCoordinatorTest.this.metadataListener = metadataListener;
            return super.metadataListener(metadataListener);
          }
        };
    mocks = MockitoAnnotations.openMocks(this);
    when(environment.locator()).thenReturn(locator);
    when(environment.clientParametersCopy()).thenReturn(clientParameters);
    coordinator = new ProducersCoordinator(environment, clientFactory);
  }

  @AfterEach
  void tearDown() throws Exception {
    // just taking the opportunity to check toString() generates valid JSON
    MonitoringTestUtils.extract(coordinator);
    if (scheduledExecutorService != null) {
      scheduledExecutorService.shutdownNow();
    }
    mocks.close();
  }

  @Test
  void registerShouldThrowExceptionWhenNoMetadataForTheStream() {
    assertThatThrownBy(() -> coordinator.registerProducer(producer, "stream"))
        .isInstanceOf(StreamDoesNotExistException.class);
  }

  @Test
  void registerShouldThrowExceptionWhenStreamDoesNotExist() {
    when(locator.metadata("stream"))
        .thenReturn(
            Collections.singletonMap(
                "stream",
                new Client.StreamMetadata(
                    "stream", Constants.RESPONSE_CODE_STREAM_DOES_NOT_EXIST, null, null)));
    assertThatThrownBy(() -> coordinator.registerProducer(producer, "stream"))
        .isInstanceOf(StreamDoesNotExistException.class);
  }

  @Test
  void registerShouldThrowExceptionWhenMetadataResponseIsNotOk() {
    when(locator.metadata("stream"))
        .thenReturn(
            Collections.singletonMap(
                "stream",
                new Client.StreamMetadata(
                    "stream", Constants.RESPONSE_CODE_ACCESS_REFUSED, null, null)));
    assertThatThrownBy(() -> coordinator.registerProducer(producer, "stream"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void registerShouldThrowExceptionWhenNoLeader() {
    when(locator.metadata("stream"))
        .thenReturn(
            Collections.singletonMap(
                "stream",
                new Client.StreamMetadata("stream", Constants.RESPONSE_CODE_OK, null, replicas())));
    assertThatThrownBy(() -> coordinator.registerProducer(producer, "stream"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void registerShouldAllowPublishing() {
    when(locator.metadata("stream"))
        .thenReturn(
            Collections.singletonMap(
                "stream",
                new Client.StreamMetadata(
                    "stream", Constants.RESPONSE_CODE_OK, leader(), replicas())));
    when(clientFactory.apply(any(Client.ClientParameters.class))).thenReturn(client);

    Runnable cleanTask = coordinator.registerProducer(producer, "stream");

    verify(producer, times(1)).setClient(client);

    cleanTask.run();
  }

  Map<String, StreamMetadata> metadata(String stream, Broker leader, List<Broker> replicas) {
    return Collections.singletonMap(
        stream, new Client.StreamMetadata(stream, Constants.RESPONSE_CODE_OK, leader, replicas));
  }

  Map<String, StreamMetadata> metadata(Broker leader, List<Broker> replicas) {
    return metadata("stream", leader, replicas);
  }

  @Test
  void shouldRedistributeProducerAndCommittingConsumerIfConnectionIsLost() throws Exception {
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    when(environment.scheduledExecutorService()).thenReturn(scheduledExecutorService);
    Duration retryDelay = Duration.ofMillis(50);
    when(environment.recoveryBackOffDelayPolicy()).thenReturn(BackOffDelayPolicy.fixed(retryDelay));
    when(locator.metadata("stream"))
        .thenReturn(metadata(leader(), replicas()))
        .thenReturn(metadata(leader(), replicas()))
        .thenReturn(metadata(null, replicas()))
        .thenReturn(metadata(null, replicas()))
        .thenReturn(metadata(leader(), replicas()));

    when(clientFactory.apply(any(Client.ClientParameters.class))).thenReturn(client);

    CountDownLatch setClientLatch = new CountDownLatch(2 + 2);

    doAnswer(answer(() -> setClientLatch.countDown())).when(producer).setClient(client);

    doAnswer(answer(() -> setClientLatch.countDown())).when(committingConsumer).setClient(client);

    coordinator.registerProducer(producer, "stream");
    coordinator.registerCommittingConsumer(committingConsumer, "stream");

    verify(producer, times(1)).setClient(client);
    verify(committingConsumer, times(1)).setClient(client);
    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount()).isEqualTo(1);

    shutdownListener.handle(
        new Client.ShutdownContext(Client.ShutdownContext.ShutdownReason.UNKNOWN));

    assertThat(setClientLatch.await(5, TimeUnit.SECONDS)).isTrue();
    verify(producer, times(1)).unavailable();
    verify(producer, times(2)).setClient(client);
    verify(producer, times(1)).running();
    verify(committingConsumer, times(1)).unavailable();
    verify(committingConsumer, times(2)).setClient(client);
    verify(committingConsumer, times(1)).running();
    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount()).isEqualTo(1);
  }

  private static Answer<Void> answer(Runnable task) {
    return invocationOnMock -> {
      task.run();
      return null;
    };
  }

  @Test
  void shouldDisposeProducerAndNotCommittingConsumerIfRecoveryTimesOut() throws Exception {
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    when(environment.scheduledExecutorService()).thenReturn(scheduledExecutorService);
    when(environment.recoveryBackOffDelayPolicy())
        .thenReturn(BackOffDelayPolicy.fixedWithInitialDelay(ms(10), ms(10), ms(100)));
    when(locator.metadata("stream"))
        .thenReturn(metadata(leader(), replicas()))
        .thenReturn(metadata(leader(), replicas())) // for the 2 registrations
        .thenReturn(metadata(null, replicas()));

    when(clientFactory.apply(any(Client.ClientParameters.class))).thenReturn(client);

    CountDownLatch closeClientLatch = new CountDownLatch(1);
    doAnswer(answer(() -> closeClientLatch.countDown())).when(producer).closeAfterStreamDeletion();

    coordinator.registerProducer(producer, "stream");
    coordinator.registerCommittingConsumer(committingConsumer, "stream");

    verify(producer, times(1)).setClient(client);
    verify(committingConsumer, times(1)).setClient(client);
    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount()).isEqualTo(1);

    shutdownListener.handle(
        new Client.ShutdownContext(Client.ShutdownContext.ShutdownReason.UNKNOWN));

    assertThat(closeClientLatch.await(5, TimeUnit.SECONDS)).isTrue();
    verify(producer, times(1)).unavailable();
    verify(producer, times(1)).setClient(client);
    verify(producer, never()).running();
    verify(committingConsumer, times(1)).unavailable();
    verify(committingConsumer, times(1)).setClient(client);
    verify(committingConsumer, never()).running();
    verify(committingConsumer, never()).closeAfterStreamDeletion();
    assertThat(coordinator.poolSize()).isEqualTo(0);
    assertThat(coordinator.clientCount()).isEqualTo(0);
  }

  @Test
  void shouldRedistributeProducersAndCommittingConsumersOnMetadataUpdate() throws Exception {
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    when(environment.scheduledExecutorService()).thenReturn(scheduledExecutorService);
    Duration retryDelay = Duration.ofMillis(50);
    when(environment.topologyUpdateBackOffDelayPolicy())
        .thenReturn(BackOffDelayPolicy.fixed(retryDelay));
    String movingStream = "moving-stream";
    when(locator.metadata(movingStream))
        .thenReturn(metadata(movingStream, leader1(), replicas()))
        .thenReturn(metadata(movingStream, leader1(), replicas())) // for the first 2 registrations
        .thenReturn(metadata(movingStream, null, replicas()))
        .thenReturn(metadata(movingStream, leader2(), replicas()));

    String fixedStream = "fixed-stream";
    when(locator.metadata(fixedStream)).thenReturn(metadata(fixedStream, leader1(), replicas()));

    when(clientFactory.apply(any(Client.ClientParameters.class))).thenReturn(client);

    StreamProducer movingProducer = mock(StreamProducer.class);
    StreamProducer fixedProducer = mock(StreamProducer.class);
    StreamConsumer movingCommittingConsumer = mock(StreamConsumer.class);
    StreamConsumer fixedCommittingConsumer = mock(StreamConsumer.class);

    CountDownLatch setClientLatch = new CountDownLatch(2 + 2);

    doAnswer(answer(() -> setClientLatch.countDown())).when(movingProducer).setClient(client);

    doAnswer(answer(() -> setClientLatch.countDown()))
        .when(movingCommittingConsumer)
        .setClient(client);

    coordinator.registerProducer(movingProducer, movingStream);
    coordinator.registerProducer(fixedProducer, fixedStream);
    coordinator.registerCommittingConsumer(movingCommittingConsumer, movingStream);
    coordinator.registerCommittingConsumer(fixedCommittingConsumer, fixedStream);

    verify(movingProducer, times(1)).setClient(client);
    verify(fixedProducer, times(1)).setClient(client);
    verify(movingCommittingConsumer, times(1)).setClient(client);
    verify(fixedCommittingConsumer, times(1)).setClient(client);
    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount()).isEqualTo(1);

    metadataListener.handle(movingStream, Constants.RESPONSE_CODE_STREAM_NOT_AVAILABLE);

    assertThat(setClientLatch.await(5, TimeUnit.SECONDS)).isTrue();
    verify(movingProducer, times(1)).unavailable();
    verify(movingProducer, times(2)).setClient(client);
    verify(movingProducer, times(1)).running();
    verify(movingCommittingConsumer, times(1)).unavailable();
    verify(movingCommittingConsumer, times(2)).setClient(client);
    verify(movingCommittingConsumer, times(1)).running();

    verify(fixedProducer, never()).unavailable();
    verify(fixedProducer, times(1)).setClient(client);
    verify(fixedProducer, never()).running();
    verify(fixedCommittingConsumer, never()).unavailable();
    verify(fixedCommittingConsumer, times(1)).setClient(client);
    verify(fixedCommittingConsumer, never()).running();
    assertThat(coordinator.poolSize()).isEqualTo(2);
    assertThat(coordinator.clientCount()).isEqualTo(2);
  }

  @Test
  void shouldDisposeProducerIfStreamIsDeleted() throws Exception {
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    when(environment.scheduledExecutorService()).thenReturn(scheduledExecutorService);
    when(environment.topologyUpdateBackOffDelayPolicy())
        .thenReturn(BackOffDelayPolicy.fixedWithInitialDelay(ms(10), ms(10), ms(100)));
    when(locator.metadata("stream"))
        .thenReturn(
            Collections.singletonMap(
                "stream",
                new Client.StreamMetadata(
                    "stream", Constants.RESPONSE_CODE_OK, leader(), replicas())))
        .thenReturn(
            Collections.singletonMap(
                "stream",
                new Client.StreamMetadata(
                    "stream", Constants.RESPONSE_CODE_STREAM_DOES_NOT_EXIST, null, replicas())));

    when(clientFactory.apply(any(Client.ClientParameters.class))).thenReturn(client);

    CountDownLatch closeClientLatch = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              closeClientLatch.countDown();
              return null;
            })
        .when(producer)
        .closeAfterStreamDeletion();

    coordinator.registerProducer(producer, "stream");

    verify(producer, times(1)).setClient(client);
    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount()).isEqualTo(1);

    metadataListener.handle("stream", Constants.RESPONSE_CODE_STREAM_NOT_AVAILABLE);

    assertThat(closeClientLatch.await(5, TimeUnit.SECONDS)).isTrue();
    verify(producer, times(1)).unavailable();
    verify(producer, times(1)).setClient(client);
    verify(producer, never()).running();

    assertThat(coordinator.poolSize()).isEqualTo(0);
    assertThat(coordinator.clientCount()).isEqualTo(0);
  }

  @Test
  void shouldDisposeProducerAndNotCommittingConsumerIfMetadataUpdateTimesOut() throws Exception {
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    when(environment.scheduledExecutorService()).thenReturn(scheduledExecutorService);
    when(environment.topologyUpdateBackOffDelayPolicy())
        .thenReturn(BackOffDelayPolicy.fixedWithInitialDelay(ms(10), ms(10), ms(100)));
    when(locator.metadata("stream"))
        .thenReturn(metadata(leader(), replicas()))
        .thenReturn(metadata(leader(), replicas())) // for the 2 registrations
        .thenReturn(metadata(null, replicas()));

    when(clientFactory.apply(any(Client.ClientParameters.class))).thenReturn(client);

    CountDownLatch closeClientLatch = new CountDownLatch(1);
    doAnswer(answer(() -> closeClientLatch.countDown())).when(producer).closeAfterStreamDeletion();

    coordinator.registerProducer(producer, "stream");
    coordinator.registerCommittingConsumer(committingConsumer, "stream");

    verify(producer, times(1)).setClient(client);
    verify(committingConsumer, times(1)).setClient(client);
    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount()).isEqualTo(1);

    metadataListener.handle("stream", Constants.RESPONSE_CODE_STREAM_NOT_AVAILABLE);

    assertThat(closeClientLatch.await(5, TimeUnit.SECONDS)).isTrue();
    verify(producer, times(1)).unavailable();
    verify(producer, times(1)).setClient(client);
    verify(producer, never()).running();
    verify(committingConsumer, times(1)).unavailable();
    verify(committingConsumer, times(1)).setClient(client);
    verify(committingConsumer, never()).running();
    verify(committingConsumer, never()).closeAfterStreamDeletion();
    assertThat(coordinator.poolSize()).isEqualTo(0);
    assertThat(coordinator.clientCount()).isEqualTo(0);
  }

  @Test
  void growShrinkResourcesBasedOnProducersAndCommittingConsumersCount() {
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    when(environment.scheduledExecutorService()).thenReturn(scheduledExecutorService);
    when(locator.metadata("stream"))
        .thenReturn(
            Collections.singletonMap(
                "stream",
                new Client.StreamMetadata(
                    "stream", Constants.RESPONSE_CODE_OK, leader(), replicas())));

    when(clientFactory.apply(any(Client.ClientParameters.class))).thenReturn(client);

    int extraProducerCount = ProducersCoordinator.MAX_PRODUCERS_PER_CLIENT / 5;
    int producerCount = ProducersCoordinator.MAX_PRODUCERS_PER_CLIENT + extraProducerCount;

    class ProducerInfo {
      StreamProducer producer;
      byte publishingId;
      Runnable cleaningCallback;
    }
    List<ProducerInfo> producerInfos = new ArrayList<>(producerCount);
    IntStream.range(0, producerCount)
        .forEach(
            i -> {
              StreamProducer p = mock(StreamProducer.class);
              ProducerInfo info = new ProducerInfo();
              info.producer = p;
              doAnswer(
                      invocation -> {
                        info.publishingId = invocation.getArgument(0);
                        return null;
                      })
                  .when(p)
                  .setPublisherId(anyByte());
              Runnable cleaningCallback = coordinator.registerProducer(p, "stream");
              info.cleaningCallback = cleaningCallback;
              producerInfos.add(info);
            });

    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount()).isEqualTo(2);

    // let's add some committing consumers
    int extraCommittingConsumerCount = ProducersCoordinator.MAX_COMMITTING_CONSUMERS_PER_CLIENT / 5;
    int committingConsumerCount =
        ProducersCoordinator.MAX_COMMITTING_CONSUMERS_PER_CLIENT * 2 + extraCommittingConsumerCount;

    class CommittingConsumerInfo {
      StreamConsumer consumer;
      Runnable cleaningCallback;
    }
    List<CommittingConsumerInfo> committingConsumerInfos = new ArrayList<>(committingConsumerCount);
    IntStream.range(0, committingConsumerCount)
        .forEach(
            i -> {
              StreamConsumer c = mock(StreamConsumer.class);
              CommittingConsumerInfo info = new CommittingConsumerInfo();
              info.consumer = c;
              Runnable cleaningCallback = coordinator.registerCommittingConsumer(c, "stream");
              info.cleaningCallback = cleaningCallback;
              committingConsumerInfos.add(info);
            });

    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount())
        .as("new committing consumers needs yet another client")
        .isEqualTo(3);

    Collections.reverse(committingConsumerInfos);
    // let's remove some committing consumers to free 1 client
    IntStream.range(0, extraCommittingConsumerCount)
        .forEach(
            i -> {
              committingConsumerInfos.get(0).cleaningCallback.run();
              committingConsumerInfos.remove(0);
            });

    assertThat(coordinator.clientCount()).isEqualTo(2);

    // let's free the rest of committing consumers
    committingConsumerInfos.forEach(info -> info.cleaningCallback.run());

    assertThat(coordinator.clientCount()).isEqualTo(2);

    ProducerInfo info = producerInfos.get(10);
    info.cleaningCallback.run();

    StreamProducer p = mock(StreamProducer.class);
    AtomicReference<Byte> publishingIdForNewProducer = new AtomicReference<>();
    doAnswer(
            invocation -> {
              publishingIdForNewProducer.set(invocation.getArgument(0));
              return null;
            })
        .when(p)
        .setPublisherId(anyByte());
    coordinator.registerProducer(p, "stream");

    verify(p, times(1)).setClient(client);
    assertThat(publishingIdForNewProducer.get()).isEqualTo(info.publishingId);

    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount()).isEqualTo(2);

    // close some of the last producers, this should free a whole producer manager and a bit of the
    // next one
    for (int i = producerInfos.size() - 1; i > (producerCount - (extraProducerCount + 20)); i--) {
      ProducerInfo producerInfo = producerInfos.get(i);
      producerInfo.cleaningCallback.run();
    }

    assertThat(coordinator.poolSize()).isEqualTo(1);
    assertThat(coordinator.clientCount()).isEqualTo(1);
  }

  Client.Broker leader() {
    return new Client.Broker("leader", 5555);
  }

  Client.Broker leader1() {
    return new Client.Broker("leader-1", 5555);
  }

  Client.Broker leader2() {
    return new Client.Broker("leader-2", 5555);
  }

  List<Client.Broker> replicas() {
    return Arrays.asList(new Client.Broker("replica1", 5555), new Client.Broker("replica2", 5555));
  }
}
