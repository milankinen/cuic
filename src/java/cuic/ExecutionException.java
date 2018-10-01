package cuic;

import clojure.lang.ExceptionInfo;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

public class ExecutionException extends ExceptionInfo {
  private final boolean retryable;
  private final Keyword type;

  public ExecutionException(String message, boolean retryable, Keyword type, IPersistentMap data, Throwable cause) {
    super(message, data, cause);
    this.retryable = retryable;
    this.type = type;
  }

  public boolean isRetryable() {
    return this.retryable;
  }

  public Keyword getType() {
    return this.type;
  }
}
