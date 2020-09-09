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

package com.rabbitmq.stream;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public interface BackOffDelayPolicy {

    Duration TIMEOUT = Duration.ofMillis(Long.MAX_VALUE);

    static BackOffDelayPolicy fixed(Duration delay) {
        return new FixedWithInitialDelayBackOffPolicy(delay, delay);
    }

    static BackOffDelayPolicy fixedWithInitialDelay(Duration initialDelay, Duration delay) {
        return new FixedWithInitialDelayBackOffPolicy(initialDelay, delay);
    }

    static BackOffDelayPolicy fixedWithInitialDelay(Duration initialDelay, Duration delay, Duration timeout) {
        return new FixedWithInitialDelayAndTimeoutBackOffPolicy(initialDelay, delay, timeout);
    }

    Duration delay(int recoveryAttempt);

    class FixedWithInitialDelayBackOffPolicy implements BackOffDelayPolicy {

        private final Duration initialDelay;
        private final Duration delay;
        private final AtomicBoolean first = new AtomicBoolean(true);

        private FixedWithInitialDelayBackOffPolicy(Duration initialDelay, Duration delay) {
            this.initialDelay = initialDelay;
            this.delay = delay;
        }

        @Override
        public Duration delay(int recoveryAttempt) {
            if (first.compareAndSet(true, false)) {
                return initialDelay;
            } else {
                return delay;
            }
        }
    }

    class FixedWithInitialDelayAndTimeoutBackOffPolicy implements BackOffDelayPolicy {

        private final Duration initialDelay;
        private final Duration delay;
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final int attemptLimitBeforeTimeout;

        private FixedWithInitialDelayAndTimeoutBackOffPolicy(Duration initialDelay, Duration delay, Duration timeout) {
            if (timeout.toMillis() < initialDelay.toMillis()) {
                throw new IllegalArgumentException("Timeout must be longer than initial delay");
            }
            this.initialDelay = initialDelay;
            this.delay = delay;
            long timeoutWithInitialDelay = timeout.toMillis() - initialDelay.toMillis();
            this.attemptLimitBeforeTimeout = (int) (timeoutWithInitialDelay / delay.toMillis()) + 1;
        }

        @Override
        public Duration delay(int recoveryAttempt) {
            if (first.compareAndSet(true, false)) {
                return initialDelay;
            } else {
                if (recoveryAttempt >= attemptLimitBeforeTimeout) {
                    return TIMEOUT;
                } else {
                    return delay;
                }
            }
        }
    }

}
