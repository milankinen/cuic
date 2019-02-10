package cuic;

public class WaitTimeoutException extends RuntimeException {
  private final Object actual;
  public WaitTimeoutException(Object actual, Throwable cause) {
    super("Wait timeout exceeded", cause);
    this.actual = actual;
  }

  public Object getActual() {
    return this.actual;
  }
}
