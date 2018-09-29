package cuic;

import java.util.Map;

public class ExecutionException extends RuntimeException {
  public final boolean retryable;
  public final Map<Object, Object> data;

  public ExecutionException(String message, boolean retryable, Map<Object, Object> data) {
    super(message);
    this.data = data;
    this.retryable = retryable;
  }
}
