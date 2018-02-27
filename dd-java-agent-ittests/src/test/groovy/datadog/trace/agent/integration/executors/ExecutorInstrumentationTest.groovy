package datadog.trace.agent.integration.executors

import datadog.opentracing.DDSpan
import datadog.opentracing.DDTracer
import datadog.trace.agent.test.IntegrationTestUtils
import datadog.trace.api.Trace
import datadog.trace.common.writer.ListWriter
import spock.lang.Specification

import java.util.concurrent.ForkJoinPool;

class ExecutorInstrumentationTest extends Specification {
  static final ListWriter TEST_WRITER = new ListWriter()
  static final DDTracer tracer = new DDTracer(TEST_WRITER)

  def setupSpec() {
    IntegrationTestUtils.registerOrReplaceGlobalTracer(tracer)
    TEST_WRITER.start()
  }

  def setup() {
    getTEST_WRITER().close()
  }

  def "test common executor services"() {
    setup:
    final ForkJoinPool pool = new ForkJoinPool()
    // String methodName = "submit"
    // Method m = pool.getClass().getMethod(methodName);

    new Runnable(){
      @Override
      @Trace(operationName = "parent")
      void run() {
        pool.submit((Runnable) new AsyncChild())
        // FIXME remove vv
        Thread.sleep(100)
      }
    }.run()

    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace.get(0).operationName == "parent"
    trace.get(1).operationName == "asyncChild"

    cleanup:
    pool.shutdown()

    // TODO : test matrix for different thread pool impls and methods
  }

  def "test cancelled jobs" () {
    // submit a lot of jobs
    // cancel all jobs
    // make sure trace finishes

    // repeat for different thread pools.
  }

  def "scala futures and callbacks"() {
  }

  def "scala either promise completion"() {
  }
}
