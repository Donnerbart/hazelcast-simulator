package com.hazelcast.simulator.worker.testcontainer;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.simulator.common.TestCase;
import com.hazelcast.simulator.common.TestPhase;
import com.hazelcast.simulator.protocol.connector.WorkerConnector;
import com.hazelcast.simulator.test.AbstractTest;
import com.hazelcast.simulator.test.annotations.StartNanos;
import com.hazelcast.simulator.test.annotations.TimeStep;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class TestContainer_TimeStep_StartNanosTest extends TestContainer_AbstractTest {

    @Test
    public void testWithMetronome() throws Exception {
        long intervalUs = 50;

        StartNanosTest testInstance = new StartNanosTest();
        TestCase testCase = new TestCase("test")
                .setProperty("iterations", 10)
                .setProperty("interval", intervalUs + "us")
                .setProperty("threadCount", 1)
                .setProperty("class", testInstance.getClass());

        TestContextImpl testContext = new TestContextImpl(
                mock(HazelcastInstance.class), testCase.getId(), "localhost", mock(WorkerConnector.class));
        TestContainer container = new TestContainer(testContext, testInstance, testCase);

        for (TestPhase phase : TestPhase.values()) {
            if (phase.equals(TestPhase.WARMUP)) {
                continue;
            }
            container.invoke(phase);
        }

        List<Long> startNanosList = testInstance.startNanosList;
        assertEquals(10, startNanosList.size());
        long firstNanos = startNanosList.get(0);
        for (int k = 1; k < startNanosList.size(); k++) {
            long startNanos = startNanosList.get(k);
            assertEquals(firstNanos + k * TimeUnit.MICROSECONDS.toNanos(intervalUs), startNanos);
        }
    }

    @Test
    public void testWithoutMetronome() throws Exception {
        StartNanosTest testInstance = new StartNanosTest();
        TestCase testCase = new TestCase("test")
                .setProperty("iterations", 10)
                .setProperty("threadCount", 1)
                .setProperty("class", testInstance.getClass());

        TestContextImpl testContext = new TestContextImpl(
                mock(HazelcastInstance.class), testCase.getId(), "localhost", mock(WorkerConnector.class));
        TestContainer container = new TestContainer(testContext, testInstance, testCase);

        for (TestPhase phase : TestPhase.values()) {
            if (phase.equals(TestPhase.WARMUP)) {
                continue;
            }
            container.invoke(phase);
        }

        List<Long> startNanosList = testInstance.startNanosList;
        assertEquals(10, startNanosList.size());
    }

    public static class StartNanosTest extends AbstractTest {
        private List<Long> startNanosList = new LinkedList<Long>();

        @TimeStep
        public void timeStep(@StartNanos long startNanos) {
            startNanosList.add(startNanos);
        }
    }
}
