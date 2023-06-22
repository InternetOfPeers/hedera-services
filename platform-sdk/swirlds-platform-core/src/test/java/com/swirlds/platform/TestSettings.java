/*
 * Copyright (C) 2018-2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform;

import java.util.concurrent.atomic.AtomicInteger;

public class TestSettings implements SettingsProvider {
    public final AtomicInteger transactionMaxBytes = new AtomicInteger(0);
    public final AtomicInteger ipTos = new AtomicInteger(-1);
    public final AtomicInteger throttleTransactionQueueSize = new AtomicInteger(100_000);

    @Override
    public int getTransactionMaxBytes() {
        return transactionMaxBytes.get();
    }

    @Override
    public int getSocketIpTos() {
        return ipTos.get();
    }

    @Override
    public int getTimeoutSyncClientSocket() {
        return 5_000;
    }

    @Override
    public int getTimeoutSyncClientConnect() {
        return 5_000;
    }

    @Override
    public int getTimeoutServerAcceptConnect() {
        return 0;
    }

    @Override
    public boolean isTcpNoDelay() {
        return true;
    }

    @Override
    public int getThrottleTransactionQueueSize() {
        return throttleTransactionQueueSize.get();
    }

    @Override
    public int getMaxTransactionBytesPerEvent() {
        return 245_760;
    }

    @Override
    public boolean useLoopbackIp() {
        return false;
    }

    @Override
    public int connectionStreamBufferSize() {
        return 0;
    }
}
