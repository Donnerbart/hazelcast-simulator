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
package com.hazelcast.simulator.worker.performance;

import com.hazelcast.simulator.protocol.connector.ServerConnector;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.operation.PerformanceStatsOperation;
import com.hazelcast.simulator.worker.testcontainer.TestContainer;
import org.apache.log4j.Logger;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hazelcast.simulator.utils.CommonUtils.joinThread;
import static com.hazelcast.simulator.utils.CommonUtils.sleepNanos;
import static com.hazelcast.simulator.utils.FileUtils.getUserDir;
import static java.lang.System.currentTimeMillis;
import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Monitors the performance of all running Simulator Tests.
 */
public class PerformanceMonitor {

    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;
    private static final long WAIT_FOR_TEST_CONTAINERS_DELAY_NANOS = MILLISECONDS.toNanos(100);
    private static final Logger LOGGER = Logger.getLogger(PerformanceMonitor.class);

    private final PerformanceMonitorThread thread;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public PerformanceMonitor(ServerConnector serverConnector,
                              Collection<TestContainer> testContainers,
                              int updateIntervalSeconds) {
        this.thread = new PerformanceMonitorThread(serverConnector, testContainers, updateIntervalSeconds);
        thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                LOGGER.fatal(e.getMessage(), e);
            }
        });
    }

    public void start() {
        thread.start();
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        joinThread(thread, MINUTES.toMillis(SHUTDOWN_TIMEOUT_SECONDS));
    }

    /**
     * Thread to monitor the performance of Simulator Tests.
     */
    private final class PerformanceMonitorThread extends Thread {

        private final long scanIntervalNanos = SECONDS.toNanos(1);
        private final PerformanceLogWriter globalPerformanceLogWriter;
        private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        private final ServerConnector serverConnector;
        private final Collection<TestContainer> testContainers;
        private final long updateIntervalMillis;
        private final List<TestContainer> dirtyContainers = new ArrayList<TestContainer>();

        private PerformanceMonitorThread(ServerConnector serverConnector,
                                         Collection<TestContainer> testContainers,
                                         long updateIntervalSeconds) {
            super("WorkerPerformanceMonitor");
            setDaemon(true);
            this.updateIntervalMillis = SECONDS.toMillis(updateIntervalSeconds);
            this.serverConnector = serverConnector;
            this.testContainers = testContainers;
            this.globalPerformanceLogWriter = new PerformanceLogWriter(new File(getUserDir(), "performance.csv"));
        }

        @Override
        public void run() {
            while (!shutdown.get()) {
                long startNanos = nanoTime();
                long currentTimeMillis = currentTimeMillis();

                updateTrackers(currentTimeMillis);

                if (!dirtyContainers.isEmpty()) {
                    coordinatorUpdate();
                    persist(currentTimeMillis);
                }

                long elapsedNanos = nanoTime() - startNanos;

                if (scanIntervalNanos > elapsedNanos) {
                    if (dirtyContainers.isEmpty()) {
                        sleepNanos(WAIT_FOR_TEST_CONTAINERS_DELAY_NANOS - elapsedNanos);
                    } else {
                        sleepNanos(scanIntervalNanos - elapsedNanos);
                    }
                } else {
                    LOGGER.warn(getName() + ".run() took " + NANOSECONDS.toMillis(elapsedNanos) + " ms");
                }
            }
        }

        private void updateTrackers(long currentTimeMillis) {
            dirtyContainers.clear();

            for (TestContainer container : testContainers) {
                TestPerformanceTracker tracker = container.getTestPerformanceTracker();
                if (tracker.update(updateIntervalMillis, currentTimeMillis)) {
                    dirtyContainers.add(container);
                }
            }
        }

        private void coordinatorUpdate() {
            PerformanceStatsOperation operation = new PerformanceStatsOperation();

            for (TestContainer container : dirtyContainers) {
                TestPerformanceTracker tracker = container.getTestPerformanceTracker();
                operation.addPerformanceStats(container.getTestCase().getId(), tracker.createPerformanceStats());
            }

            if (operation.getPerformanceStats().size() > 0) {
                serverConnector.submit(SimulatorAddress.COORDINATOR, operation);
            }
        }

        private void persist(long currentTimestamp) {
            String dateString = simpleDateFormat.format(new Date(currentTimestamp));
            long globalIntervalOperationCount = 0;
            long globalOperationsCount = 0;
            double globalIntervalThroughput = 0;

            for (TestContainer container : dirtyContainers) {
                TestPerformanceTracker tracker = container.getTestPerformanceTracker();
                tracker.persist(currentTimestamp, dateString);

                globalIntervalOperationCount += tracker.intervalOperationCount();
                globalOperationsCount += tracker.totalOperationCount();
                globalIntervalThroughput += tracker.intervalThroughput();
            }

            // global performance stats
            globalPerformanceLogWriter.write(
                    currentTimestamp,
                    dateString,
                    globalOperationsCount,
                    globalIntervalOperationCount,
                    globalIntervalThroughput,
                    testContainers.size(),
                    testContainers.size());
        }
    }
}
