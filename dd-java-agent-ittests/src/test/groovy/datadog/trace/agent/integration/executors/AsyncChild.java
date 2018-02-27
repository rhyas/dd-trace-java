package datadog.trace.agent.integration.executors;

import datadog.trace.api.Trace;

import java.util.concurrent.Callable;

public class AsyncChild implements Runnable, Callable {
    @Override
    @Trace(operationName = "asyncChild")
    public void run() { }
    @Override
    @Trace(operationName = "asyncChild")
    public Object call() throws Exception {
      return null;
    }
}
