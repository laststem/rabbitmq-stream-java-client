// Copyright (c) 2020 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is dual-licensed under the
// Mozilla Public License 1.1 ("MPL"), and the Apache License version 2 ("ASL").
// For the MPL, please see LICENSE-MPL-RabbitMQ. For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.rabbitmq.stream.TestUtils.waitAtMost;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class FrameTest {

    static EventLoopGroup eventLoopGroup;

    @BeforeAll
    static void initSuite() {
        eventLoopGroup = new NioEventLoopGroup();
    }

    @AfterAll
    static void tearDownSuite() throws Exception {
        eventLoopGroup.shutdownGracefully(1, 10, SECONDS).get(10, SECONDS);
    }

    Client client(Client.ClientParameters parameters) {
        Client client = new Client(parameters.eventLoopGroup(eventLoopGroup));
        return client;
    }

    @Test
    void messageTooBigToFitInOneFrameShouldThrowException() {
        try (Client client = client(new Client.ClientParameters().requestedMaxFrameSize(1024))) {
            byte[] binary = new byte[1000];
            Message message = new Message() {
                @Override
                public byte[] getBodyAsBinary() {
                    return binary;
                }

                @Override
                public Object getBody() {
                    return null;
                }

                @Override
                public Properties getProperties() {
                    return null;
                }

                @Override
                public Map<String, Object> getApplicationProperties() {
                    return null;
                }
            };
            List<ThrowableAssert.ThrowingCallable> publishCalls = Arrays.asList(
                    () -> client.publish("", binary),
                    () -> client.publishBinary("", Arrays.asList(binary)),
                    () -> client.publish("", message),
                    () -> client.publish("", Arrays.asList(message))
            );
            publishCalls.forEach(callable -> assertThatThrownBy(callable).isInstanceOf(IllegalArgumentException.class));
        }
    }

    @Test
    void frameTooLargeShouldTriggerCloseFromServer() throws Exception {
        int maxFrameSize = 1024;
        try (Client client = client(new Client.ClientParameters().requestedMaxFrameSize(maxFrameSize))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream dataOutputStream = new DataOutputStream(out);
            int frameSize = 1021;
            dataOutputStream.writeInt(frameSize);
            dataOutputStream.write(new byte[frameSize]);
            client.send(out.toByteArray());
            waitAtMost(10, () -> client.isOpen() == false);
        }
    }

    @Test
    void splitPublishedMessagesToFitMaxFrameSize() {
        int maxFrameSize = 1024;
        class TestDesc {
            String description;
            List<Integer> sizes;
            int expectedCalls;

            public TestDesc(String description, List<Integer> sizes, int expectedCalls) {
                this.description = description;
                this.sizes = sizes;
                this.expectedCalls = expectedCalls;
            }
        }
        List<TestDesc> tests = Arrays.asList(
                new TestDesc("1 message that fits in frame", Arrays.asList(512), 1),
                new TestDesc("x messages that fits in their respective frame", Arrays.asList(768, 768, 768), 3),
                new TestDesc("4 messages per frame", IntStream.range(0, 10).map(i -> 200).boxed().collect(Collectors.toList()), 3),
                new TestDesc("8 messages, 4 messages per frame", IntStream.range(0, 8).map(i -> 200).boxed().collect(Collectors.toList()), 2),
                new TestDesc("9 messages, 4 messages per frame", IntStream.range(0, 9).map(i -> 200).boxed().collect(Collectors.toList()), 3)
        );
        try (Client client = client(new Client.ClientParameters().requestedMaxFrameSize(maxFrameSize))) {
            tests.forEach(test -> {
                Channel channel = Mockito.mock(Channel.class);
                Mockito.when(channel.alloc()).thenReturn(ByteBufAllocator.DEFAULT);

                client.publishInternal(channel, "target", test.sizes.stream()
                        .map(size -> new Codec.EncodedMessage(size, new byte[size])).collect(Collectors.toList()));

                ArgumentCaptor<ByteBuf> bbCaptor = ArgumentCaptor.forClass(ByteBuf.class);
                verify(channel, times(test.expectedCalls)).writeAndFlush(bbCaptor.capture());
                bbCaptor.getAllValues().forEach(bb -> {
                    assertThat(bb.capacity()).isLessThanOrEqualTo(maxFrameSize);
                    bb.release();
                });
            });
        }
    }

}