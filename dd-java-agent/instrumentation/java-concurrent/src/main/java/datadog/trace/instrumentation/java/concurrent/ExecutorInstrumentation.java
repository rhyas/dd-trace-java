package datadog.trace.instrumentation.java.concurrent;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;

import datadog.trace.agent.tooling.DDAdvice;
import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.ContextPropagator;

import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.util.GlobalTracer;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class ExecutorInstrumentation extends Instrumenter.Configurable {
  private static final HelperInjector EXEC_HELPER_INJECTOR =
      new HelperInjector(
          ExecutorInstrumentation.class.getName() + "$DatadogWrapper",
          ExecutorInstrumentation.class.getName() + "$CallableWrapper",
          ExecutorInstrumentation.class.getName() + "$RunnableWrapper");

  public ExecutorInstrumentation() {
    super("java_concurrent");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
      // instrumentation for Executor
        .type(not(isInterface()).and(hasSuperType(named(Executor.class.getName()))))
        .transform(EXEC_HELPER_INJECTOR)
        .transform(
            DDAdvice.create()
                .advice(
                    named("execute").and(takesArgument(0, Runnable.class)),
                    WrapRunnableAdvice.class.getName()))
        .asDecorator()
        .type(not(isInterface()).and(hasSuperType(named(ExecutorService.class.getName()))))
        .transform(EXEC_HELPER_INJECTOR)
        .transform(
            DDAdvice.create()
                .advice(
                    named("submit").and(takesArgument(0, Runnable.class)),
                    WrapRunnableAdvice.class.getName()))
        .transform(
            DDAdvice.create()
                .advice(
                    named("submit").and(takesArgument(0, Callable.class)),
                    WrapCallableAdvice.class.getName()))
        .transform(
            DDAdvice.create()
                .advice(
                    nameMatches("invoke(Any|All)$").and(takesArgument(0, Callable.class)),
                    WrapCallableCollectionAdvice.class.getName()))
        .asDecorator();
  }

  // FIXME: Handle canceled jobs
  // FIXME: Investigate scala promise either/timeout
  // FIXME: Don't suspend trace for null tasks.
  // FIXME: Don't wrap when api throws an exception

  public static class WrapRunnableAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapJob(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      final Scope scope = GlobalTracer.get().scopeManager().active();
      if (scope instanceof ContextPropagator && task != null && !(task instanceof DatadogWrapper)) {
        task = new RunnableWrapper(task, (ContextPropagator) scope);
      }
    }
  }

  public static class WrapCallableAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapJob(@Advice.Argument(value = 0, readOnly = false) Callable task) {
      final Scope scope = GlobalTracer.get().scopeManager().active();
      if (scope instanceof ContextPropagator && task != null && !(task instanceof DatadogWrapper)) {
        task = new CallableWrapper(task, (ContextPropagator) scope);
      }
    }
  }

  public static class WrapCallableCollectionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapJob(
        @Advice.Argument(value = 0) Collection<? extends Callable<?>> tasks) {
      final Scope scope = GlobalTracer.get().scopeManager().active();
      if (scope instanceof ContextPropagator) {
        for (Callable task : tasks) {
          if (!(task instanceof DatadogWrapper)) {
            task = new CallableWrapper(task, (ContextPropagator) scope);
          }
        }
      }
    }
  }

  /** Marker interface for tasks which are wrapped to propagate the trace context. */
  public interface DatadogWrapper {
    // TODO: make abstract class?
  }

  public static class RunnableWrapper implements Runnable, DatadogWrapper {
    private final Runnable delegatee;
    private final ContextPropagator.Continuation continuation;

    public RunnableWrapper(Runnable toWrap, ContextPropagator scope) {
      delegatee = toWrap;
      continuation = scope.capture();
    }

    @Override
    public void run() {
      final Scope scope = continuation.activate();
      try {
        delegatee.run();
      } finally {
        scope.close();
      }
    }
  }

  public static class CallableWrapper<T> implements Callable<T>, DatadogWrapper {
    private final Callable<T> delegatee;
    private final ContextPropagator.Continuation continuation;

    public CallableWrapper(Callable<T> toWrap, ContextPropagator scope) {
      delegatee = toWrap;
      continuation = scope.capture();
    }

    @Override
    public T call() throws Exception {
      final Scope scope = continuation.activate();
      try {
        return delegatee.call();
      } finally {
        scope.close();
      }
    }
  }
}
