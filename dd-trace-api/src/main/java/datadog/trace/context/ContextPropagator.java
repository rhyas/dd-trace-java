package datadog.trace.context;

import io.opentracing.Scope;

public interface ContextPropagator {
  /**
   * TODO: doc
   */
  public Continuation capture();

  /**
   * TODO: doc
   */
  public static interface Continuation {
    /**
     * TODO: doc
     */
    public Scope activate();
  }
}
