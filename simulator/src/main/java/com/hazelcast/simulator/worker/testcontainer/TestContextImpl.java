/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.simulator.worker.testcontainer;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.simulator.protocol.connector.WorkerConnector;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.operation.LogOperation;
import com.hazelcast.simulator.test.TestContext;

import static java.lang.String.format;

public class TestContextImpl implements TestContext {

    private final HazelcastInstance hazelcastInstance;
    private final String testId;
    private final String publicIpAddress;
    private final WorkerConnector workerConnector;
    private volatile boolean stopped;

    public TestContextImpl(HazelcastInstance hazelcastInstance, String testId, String publicIpAddress,
                           WorkerConnector workerConnector) {
        this.hazelcastInstance = hazelcastInstance;
        this.testId = testId;
        this.publicIpAddress = publicIpAddress;
        this.workerConnector = workerConnector;
    }

    public HazelcastInstance getTargetInstance() {
        return hazelcastInstance;
    }

    @Override
    public String getTestId() {
        return testId;
    }

    @Override
    public String getPublicIpAddress() {
        return publicIpAddress;
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void stop() {
        stopped = true;
    }

    public void afterLocalWarmup() {
        stopped = false;
    }

    @Override
    public void echoCoordinator(String msg, Object... args) {
        String message = format(msg, args);
        workerConnector.invokeAsync(SimulatorAddress.COORDINATOR, new LogOperation(message));
    }
}
