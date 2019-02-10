package cuic;

import clojure.lang.Keyword;

public class ExecutionException extends RuntimeException {
  private final boolean retryable;
  private final Keyword type;

  public ExecutionException(String message, boolean retryable, Keyword type, Throwable cause) {
    super(message, cause);
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
