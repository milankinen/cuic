package cuic;

public class WaitTimeoutException extends CuicException {
  public WaitTimeoutException(String form, Throwable cause) {
    super("Timed out waiting for form: " + form, cause);
  }
}
