package cuic;

import clojure.lang.ExceptionInfo;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

public class ExecutionException extends ExceptionInfo {
  public final boolean retryable;
  public final Keyword type;

  public ExecutionException(String message, boolean retryable, Keyword type, IPersistentMap data, Throwable cause) {
    super(message, data, cause);
    this.retryable = retryable;
    this.type = type;
  }
}
