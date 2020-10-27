package cuic;

public class CuicException extends  RuntimeException {
  public CuicException(String message, Throwable cause, boolean stackTrace) {
    super(message, cause, true, stackTrace);
  }
  public CuicException(String message, Throwable cause) {
    super(message, cause);
  }
  public CuicException(String message) {
    super(message);
  }
}
