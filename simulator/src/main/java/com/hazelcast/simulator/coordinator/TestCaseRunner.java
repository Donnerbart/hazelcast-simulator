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
package com.hazelcast.simulator.coordinator;

import com.hazelcast.simulator.common.TestCase;
import com.hazelcast.simulator.common.TestPhase;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.operation.CreateTestOperation;
import com.hazelcast.simulator.protocol.operation.StartTestOperation;
import com.hazelcast.simulator.protocol.operation.StartTestPhaseOperation;
import com.hazelcast.simulator.protocol.operation.StopTestOperation;
import com.hazelcast.simulator.protocol.registry.ComponentRegistry;
import com.hazelcast.simulator.protocol.registry.TargetType;
import com.hazelcast.simulator.protocol.registry.TestData;
import com.hazelcast.simulator.protocol.registry.WorkerData;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import static com.hazelcast.simulator.common.TestPhase.GLOBAL_AFTER_WARMUP;
import static com.hazelcast.simulator.common.TestPhase.GLOBAL_PREPARE;
import static com.hazelcast.simulator.common.TestPhase.GLOBAL_TEARDOWN;
import static com.hazelcast.simulator.common.TestPhase.GLOBAL_VERIFY;
import static com.hazelcast.simulator.common.TestPhase.LOCAL_AFTER_WARMUP;
import static com.hazelcast.simulator.common.TestPhase.LOCAL_PREPARE;
import static com.hazelcast.simulator.common.TestPhase.LOCAL_TEARDOWN;
import static com.hazelcast.simulator.common.TestPhase.LOCAL_VERIFY;
import static com.hazelcast.simulator.common.TestPhase.RUN;
import static com.hazelcast.simulator.common.TestPhase.SETUP;
import static com.hazelcast.simulator.common.TestPhase.WARMUP;
import static com.hazelcast.simulator.utils.CommonUtils.await;
import static com.hazelcast.simulator.utils.CommonUtils.getElapsedSeconds;
import static com.hazelcast.simulator.utils.CommonUtils.joinThread;
import static com.hazelcast.simulator.utils.CommonUtils.rethrow;
import static com.hazelcast.simulator.utils.CommonUtils.sleepSeconds;
import static com.hazelcast.simulator.utils.FormatUtils.formatPercentage;
import static com.hazelcast.simulator.utils.FormatUtils.padRight;
import static com.hazelcast.simulator.utils.FormatUtils.secondsToHuman;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Collections.synchronizedList;

/**
 * Responsible for running a single {@link TestCase}.
 * <p>
 * Multiple TestCases can be run in parallel, by having multiple TestCaseRunners in parallel.
 */
final class TestCaseRunner implements TestPhaseListener {

    private static final int RUN_PHASE_LOG_INTERVAL_SECONDS = 30;
    private static final int WAIT_FOR_PHASE_COMPLETION_LOG_INTERVAL_SECONDS = 30;
    private static final int WAIT_FOR_PHASE_COMPLETION_LOG_VERBOSE_DELAY_SECONDS = 300;

    private static final Logger LOGGER = Logger.getLogger(TestCaseRunner.class);
    private static final ConcurrentMap<TestPhase, Object> LOG_TEST_PHASE_COMPLETION = new ConcurrentHashMap<TestPhase, Object>();

    private final ConcurrentMap<TestPhase, List<SimulatorAddress>> phaseCompletedMap
            = new ConcurrentHashMap<TestPhase, List<SimulatorAddress>>();

    private final TestData testData;
    private final TestCase testCase;
    private final TestSuite testSuite;

    private final RemoteClient remoteClient;
    private final FailureCollector failureCollector;
    private final PerformanceStatsCollector performanceStatsCollector;
    private final ComponentRegistry componentRegistry;

    private final String prefix;
    private final Map<TestPhase, CountDownLatch> testPhaseSyncMap;

    private final boolean isVerifyEnabled;
    private final TargetType targetType;
    private final int targetCount;

    private final int performanceMonitorIntervalSeconds;
    private final int logRunPhaseIntervalSeconds;

    @SuppressWarnings("checkstyle:parameternumber")
    TestCaseRunner(TestData testData,
                   RemoteClient remoteClient,
                   Map<TestPhase, CountDownLatch> testPhaseSyncMap,
                   FailureCollector failureCollector,
                   ComponentRegistry componentRegistry,
                   PerformanceStatsCollector performanceStatsCollector,
                   int performanceMonitorIntervalSeconds) {
        this.testData = testData;
        this.testCase = testData.getTestCase();
        this.testSuite = testData.getTestSuite();

        this.remoteClient = remoteClient;
        this.failureCollector = failureCollector;
        this.performanceStatsCollector = performanceStatsCollector;
        this.componentRegistry = componentRegistry;

        this.prefix = padRight(testCase.getId(), testSuite.getMaxTestCaseIdLength() + 1);
        this.testPhaseSyncMap = testPhaseSyncMap;

        this.isVerifyEnabled = testSuite.isVerifyEnabled();
        this.targetType = testSuite.getTargetType().resolvePreferClient(componentRegistry.hasClientWorkers());
        this.targetCount = testSuite.getTargetCount();

        this.performanceMonitorIntervalSeconds = performanceMonitorIntervalSeconds;
        if (performanceMonitorIntervalSeconds > 0) {
            this.logRunPhaseIntervalSeconds = min(performanceMonitorIntervalSeconds, RUN_PHASE_LOG_INTERVAL_SECONDS);
        } else {
            this.logRunPhaseIntervalSeconds = RUN_PHASE_LOG_INTERVAL_SECONDS;
        }

        for (TestPhase testPhase : TestPhase.values()) {
            phaseCompletedMap.put(testPhase, synchronizedList(new ArrayList<SimulatorAddress>()));
        }
    }

    @Override
    public void onCompletion(TestPhase testPhase, SimulatorAddress workerAddress) {
        phaseCompletedMap.get(testPhase).add(workerAddress);
    }

    void run() {
        testData.initStartTime();
        try {
            run0();
        } catch (TestCaseAbortedException e) {
            echo(e.getMessage());
            // unblock other TestCaseRunner threads, if fail fast is not set and they have no failures on their own
            TestPhase testPhase = e.testPhase;
            while (testPhase != null) {
                decrementAndGetCountDownLatch(testPhase);
                testPhase = testPhase.getNextTestPhaseOrNull();
            }
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    private void run0() {
        createTest();
        executePhase(SETUP);

        executePhase(LOCAL_PREPARE);
        executePhase(GLOBAL_PREPARE);

        if (testSuite.getWarmupSeconds() > 0) {
            executeWarmup();

            executePhase(LOCAL_AFTER_WARMUP);
            executePhase(GLOBAL_AFTER_WARMUP);
        } else {
            echo("Skipping Test warmup");
        }

        executeRun();

        if (isVerifyEnabled) {
            executePhase(GLOBAL_VERIFY);
            executePhase(LOCAL_VERIFY);
        } else {
            echo("Skipping Test verification");
        }

        executePhase(GLOBAL_TEARDOWN);
        executePhase(LOCAL_TEARDOWN);
    }

    private void createTest() {
        echo("Starting Test initialization");
        remoteClient.invokeOnAllWorkers(new CreateTestOperation(testData.getTestIndex(), testCase));
        echo("Completed Test initialization");
    }

    private void executePhase(TestPhase testPhase) {
        if (hasFailure()) {
            throw new TestCaseAbortedException("Skipping Test " + testPhase.desc() + " (critical failure)", testPhase);
        }

        echo("Starting Test " + testPhase.desc());
        if (testPhase.isGlobal()) {
            remoteClient.invokeOnTestOnFirstWorker(testCase.getId(), new StartTestPhaseOperation(testPhase));
        } else {
            remoteClient.invokeOnTestOnAllWorkers(testCase.getId(), new StartTestPhaseOperation(testPhase));
        }
        waitForPhaseCompletion(testPhase);
        echo("Completed Test " + testPhase.desc());
        waitForGlobalTestPhaseCompletion(testPhase);
    }

    private void executeWarmup() {
        echo(format("Starting Test warmup start on %s", targetType.toString(targetCount)));
        List<String> targetWorkers = componentRegistry.getWorkerAddresses(targetType, targetCount);
        remoteClient.invokeOnTestOnAllWorkers(testCase.getId(), new StartTestOperation(targetType, targetWorkers, true));
        echo("Completed Test warmup start");

        StopThread stopThread = null;
        if (testSuite.getWarmupSeconds() > 0) {
            stopThread = new StopThread(true);
            stopThread.start();
        }

        if (testSuite.isWaitForTestCase()) {
            echo("Test will run warmup until it stops");
            waitForPhaseCompletion(WARMUP);
            echo("Test finished running warmup");

            if (stopThread != null) {
                stopThread.shutdown();
            }
        }

        joinThread(stopThread);

        waitForGlobalTestPhaseCompletion(WARMUP);
    }

    private void executeRun() {
        echo(format("Starting Test start on %s", targetType.toString(targetCount)));
        List<String> targetWorkers = componentRegistry.getWorkerAddresses(targetType, targetCount);
        remoteClient.invokeOnTestOnAllWorkers(testCase.getId(), new StartTestOperation(targetType, targetWorkers, false));
        echo("Completed Test start");

        StopThread stopThread = null;
        if (testSuite.getDurationSeconds() > 0) {
            stopThread = new StopThread(false);
            stopThread.start();
        }

        if (testSuite.isWaitForTestCase()) {
            // it will be the test deciding to determine how long to run
            echo("Test will run until it stops");
            waitForPhaseCompletion(RUN);
            echo("Test finished running");

            if (stopThread != null) {
                stopThread.shutdown();
            }
        }
        joinThread(stopThread);

        waitForGlobalTestPhaseCompletion(RUN);
    }

    private void waitForPhaseCompletion(TestPhase testPhase) {
        int completedWorkers = phaseCompletedMap.get(testPhase).size();
        int expectedWorkers = getExpectedWorkerCount(testPhase);

        long started = System.nanoTime();
        while (completedWorkers < expectedWorkers) {
            sleepSeconds(1);

            if (hasFailure()) {
                throw new TestCaseAbortedException(
                        format("Waiting for %s completion aborted (critical failure)", testPhase.desc()), testPhase);
            }

            completedWorkers = phaseCompletedMap.get(testPhase).size();
            expectedWorkers = getExpectedWorkerCount(testPhase);

            logMissingWorkers(testPhase, completedWorkers, expectedWorkers, started);
        }
    }

    private void logMissingWorkers(TestPhase testPhase, int completedWorkers, int expectedWorkers, long started) {
        long elapsed = getElapsedSeconds(started);
        if (elapsed % WAIT_FOR_PHASE_COMPLETION_LOG_INTERVAL_SECONDS != 0) {
            return;
        }
        if (elapsed < WAIT_FOR_PHASE_COMPLETION_LOG_VERBOSE_DELAY_SECONDS || completedWorkers == expectedWorkers) {
            echo(format("Waiting %s for %s completion (%d/%d workers)", secondsToHuman(elapsed), testPhase.desc(),
                    completedWorkers, expectedWorkers));
            return;
        }
        // verbose logging of missing workers
        List<SimulatorAddress> missingWorkers = new ArrayList<SimulatorAddress>(expectedWorkers - completedWorkers);
        if (expectedWorkers == 1) {
            missingWorkers.add(componentRegistry.getFirstWorker().getAddress());
        } else {
            for (WorkerData worker : componentRegistry.getWorkers()) {
                if (!phaseCompletedMap.get(testPhase).contains(worker.getAddress())) {
                    missingWorkers.add(worker.getAddress());
                }
            }
        }
        echo(format("Waiting %s for %s completion (%d/%d workers) (missing workers: %s)", secondsToHuman(elapsed),
                testPhase.desc(), completedWorkers, expectedWorkers, missingWorkers));
    }

    private void waitForGlobalTestPhaseCompletion(TestPhase testPhase) {
        if (testPhaseSyncMap == null) {
            return;
        }
        CountDownLatch latch = decrementAndGetCountDownLatch(testPhase);
        if (!hasFailure()) {
            await(latch);
        }
        if (LOG_TEST_PHASE_COMPLETION.putIfAbsent(testPhase, true) == null) {
            LOGGER.info("Completed TestPhase " + testPhase.desc());
        }
    }

    private int getExpectedWorkerCount(TestPhase testPhase) {
        return testPhase.isGlobal() ? 1 : componentRegistry.workerCount();
    }

    private CountDownLatch decrementAndGetCountDownLatch(TestPhase testPhase) {
        if (testPhaseSyncMap == null) {
            return new CountDownLatch(0);
        }
        CountDownLatch latch = testPhaseSyncMap.get(testPhase);
        latch.countDown();
        return latch;
    }

    private void echo(String msg) {
        remoteClient.logOnAllAgents(prefix + msg);
        LOGGER.info(prefix + msg);
    }

    private boolean hasFailure() {
        return failureCollector.hasCriticalFailure(testCase.getId())
                || failureCollector.hasCriticalFailure() && testSuite.isFailFast();
    }

    private final class StopThread extends Thread {

        private final boolean warmup;
        private final int durationSeconds;
        private volatile boolean isRunning = true;

        StopThread(boolean warmup) {
            this.warmup = warmup;
            this.durationSeconds = warmup ? testSuite.getWarmupSeconds() : testSuite.getDurationSeconds();
        }

        public void shutdown() {
            isRunning = false;
            interrupt();
        }

        @Override
        public void run() {
            echo(format("Test will %s for %s", warmup ? "warmup" : "run", secondsToHuman(durationSeconds)));
            sleepUntilFailure(durationSeconds);
            echo(format("Test finished %s", warmup ? "warmup" : "running"));

            echo(warmup ? "Executing Test warmup stop" : "Executing Test stop");
            remoteClient.invokeOnTestOnAllWorkers(testCase.getId(), new StopTestOperation());
            try {
                waitForPhaseCompletion(warmup ? WARMUP : RUN);
                echo(warmup ? "Completed Test warmup stop" : "Completed Test stop");
            } catch (TestCaseAbortedException e) {
                echo(e.getMessage());
            }
        }

        private void sleepUntilFailure(int sleepSeconds) {
            int sleepLoops = sleepSeconds / logRunPhaseIntervalSeconds;
            for (int i = 1; i <= sleepLoops && isRunning; i++) {
                if (hasFailure()) {
                    echo(format("Critical failure detected, aborting %s phase", warmup ? "warmup" : "run"));
                    return;
                }
                sleepSeconds(logRunPhaseIntervalSeconds);
                logProgress(logRunPhaseIntervalSeconds * i, sleepSeconds);
            }

            if (isRunning) {
                int sleepTime = sleepSeconds % logRunPhaseIntervalSeconds;
                if (sleepTime > 0) {
                    sleepSeconds(sleepSeconds % logRunPhaseIntervalSeconds);
                    logProgress(sleepSeconds, sleepSeconds);
                }
            }
        }

        private void logProgress(int elapsed, int sleepSeconds) {
            String msg = format("%s %s (%s%%)",
                    warmup ? "Warming up " : "Running",
                    secondsToHuman(elapsed),
                    formatPercentage(elapsed, sleepSeconds));
            if (performanceMonitorIntervalSeconds > 0 && elapsed % performanceMonitorIntervalSeconds == 0) {
                msg += performanceStatsCollector.formatPerformanceNumbers(testCase.getId());
            }

            LOGGER.info(prefix + msg);
        }
    }

    private static final class TestCaseAbortedException extends RuntimeException {

        private TestPhase testPhase;

        TestCaseAbortedException(String message, TestPhase testPhase) {
            super(message);
            this.testPhase = testPhase;
        }
    }
}
