package com.hazelcast.simulator.worker.tasks;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.simulator.common.TestCase;
import com.hazelcast.simulator.common.TestPhase;
import com.hazelcast.simulator.probes.Probe;
import com.hazelcast.simulator.probes.impl.HdrProbe;
import com.hazelcast.simulator.protocol.connector.WorkerConnector;
import com.hazelcast.simulator.test.TestContext;
import com.hazelcast.simulator.test.TestException;
import com.hazelcast.simulator.test.annotations.RunWithWorker;
import com.hazelcast.simulator.test.annotations.Setup;
import com.hazelcast.simulator.utils.ExceptionReporter;
import com.hazelcast.simulator.worker.selector.OperationSelectorBuilder;
import com.hazelcast.simulator.worker.testcontainer.TestContainer;
import com.hazelcast.simulator.worker.testcontainer.TestContextImpl;
import org.HdrHistogram.Histogram;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static com.hazelcast.simulator.TestEnvironmentUtils.setupFakeEnvironment;
import static com.hazelcast.simulator.TestEnvironmentUtils.tearDownFakeEnvironment;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class AbstractWorkerWithMultipleProbesTest {

    private static final int THREAD_COUNT = 3;
    private static final int ITERATION_COUNT = 10;
    private static final int DEFAULT_TEST_TIMEOUT = 30000;
    private File userDir;

    private enum Operation {
        EXCEPTION,
        STOP_WORKER,
        STOP_TEST_CONTEXT,
        RANDOM,
        ITERATION
    }

    private WorkerTest test;
    private TestContextImpl testContext;
    private TestContainer testContainer;

    @Before
    public void before() {
        userDir = setupFakeEnvironment();
        test = new WorkerTest();
        testContext = new TestContextImpl(mock(HazelcastInstance.class), "Test", "localhost", mock(WorkerConnector.class));
        TestCase testCase = new TestCase(testContext.getTestId())
                .setProperty("threadCount", THREAD_COUNT);
        testContainer = new TestContainer(testContext, test, testCase);

        ExceptionReporter.reset();
    }

    @After
    public void after() {
        tearDownFakeEnvironment();
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testInvokeSetup() throws Exception {
        testContainer.invoke(TestPhase.SETUP);

        assertEquals(testContext, test.testContext);
        assertEquals(0, test.workerCreated);
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testRun_withException() throws Exception {
        test.operationSelectorBuilder.addDefaultOperation(Operation.EXCEPTION);

        testContainer.invoke(TestPhase.SETUP);
        testContainer.invoke(TestPhase.RUN);

        for (int i = 1; i <= THREAD_COUNT; i++) {
            assertTrue(new File(userDir, i + ".exception").exists());
        }
        assertEquals(THREAD_COUNT, test.workerCreated);
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testStopWorker() throws Exception {
        test.operationSelectorBuilder.addDefaultOperation(Operation.STOP_WORKER);

        testContainer.invoke(TestPhase.SETUP);
        testContainer.invoke(TestPhase.RUN);

        assertFalse(test.testContext.isStopped());
        assertEquals(THREAD_COUNT, test.workerCreated);
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testStopTestContext() throws Exception {
        test.operationSelectorBuilder.addDefaultOperation(Operation.STOP_TEST_CONTEXT);

        testContainer.invoke(TestPhase.SETUP);
        testContainer.invoke(TestPhase.RUN);

        assertTrue(test.testContext.isStopped());
        assertEquals(THREAD_COUNT, test.workerCreated);
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testRandomMethods() throws Exception {
        test.operationSelectorBuilder.addDefaultOperation(Operation.RANDOM);

        testContainer.invoke(TestPhase.SETUP);
        testContainer.invoke(TestPhase.RUN);

        assertNotNull(test.randomInt);
        assertNotNull(test.randomIntWithBond);
        assertNotNull(test.randomLong);
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testGetIteration() throws Exception {
        test.operationSelectorBuilder.addDefaultOperation(Operation.ITERATION);

        testContainer.invoke(TestPhase.SETUP);
        testContainer.invoke(TestPhase.RUN);

        assertEquals(ITERATION_COUNT, test.testIteration);
        assertNotNull(test.probe);
        Histogram intervalHistogram = ((HdrProbe) test.probe).getIntervalHistogram();
        assertEquals(THREAD_COUNT * ITERATION_COUNT, intervalHistogram.getTotalCount());
        assertEquals(THREAD_COUNT, test.workerCreated);
        assertEquals(1, testContainer.getProbeMap().size());
    }

    public static class WorkerTest {

        private final OperationSelectorBuilder<Operation> operationSelectorBuilder = new OperationSelectorBuilder<Operation>();
        private TestContext testContext;

        private volatile int workerCreated;
        private volatile Integer randomInt;
        private volatile Integer randomIntWithBond;
        private volatile Long randomLong;
        private volatile long testIteration;
        private volatile Probe probe;

        @Setup
        public void setup(TestContext testContext) {
            this.testContext = testContext;
        }

        @RunWithWorker
        public Worker createWorker() {
            workerCreated++;
            return new Worker(this);
        }

        private class Worker extends AbstractWorkerWithMultipleProbes<Operation> {

            private final AbstractWorkerWithMultipleProbesTest.WorkerTest test;

            Worker(AbstractWorkerWithMultipleProbesTest.WorkerTest test) {
                super(operationSelectorBuilder);
                this.test = test;
            }

            @Override
            protected void timeStep(Operation operation, Probe probe) throws Exception {
                switch (operation) {
                    case EXCEPTION:
                        throw new TestException("expected exception");
                    case STOP_WORKER:
                        stopWorker();
                        break;
                    case STOP_TEST_CONTEXT:
                        stopTestContext();
                        break;
                    case RANDOM:
                        randomInt = randomInt();
                        randomIntWithBond = randomInt(1000);
                        randomLong = getRandom().nextLong();
                        stopTestContext();
                        break;
                    case ITERATION:
                        long started = System.nanoTime();
                        if (getIteration() == ITERATION_COUNT) {
                            test.probe = probe;
                            testIteration = getIteration();
                            stopWorker();
                            break;
                        }
                        probe.recordValue(System.nanoTime() - started);
                        break;
                    default:
                        throw new UnsupportedOperationException("Unsupported operation: " + operation);
                }
            }
        }
    }
}
