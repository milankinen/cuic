package cuic;

import clojure.lang.ExceptionInfo;
import clojure.lang.IPersistentMap;

public class WaitTimeoutException extends ExceptionInfo {
  public WaitTimeoutException(String message, IPersistentMap data, Throwable cause) {
    super(message, data, cause);
  }
}
