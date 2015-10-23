/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
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
package com.hazelcast.simulator.protocol.exception;

import com.hazelcast.simulator.protocol.connector.ServerConnector;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.operation.ExceptionOperation;

/**
 * Sends exceptions to a {@link ServerConnector}, which will send it to the connected parent Simulator component.
 */
public class RemoteExceptionLogger extends AbstractExceptionLogger {

    private ServerConnector serverConnector;

    public RemoteExceptionLogger(SimulatorAddress localAddress, ExceptionType exceptionType) {
        super(localAddress, exceptionType);
    }

    public void setServerConnector(ServerConnector serverConnector) {
        this.serverConnector = serverConnector;
    }

    @Override
    protected void handleOperation(long exceptionId, ExceptionOperation operation) {
        serverConnector.submit(SimulatorAddress.COORDINATOR, operation);
    }
}
