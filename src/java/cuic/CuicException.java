package cuic;

public abstract class CuicException extends  RuntimeException {
  CuicException(String message, Throwable cause) {
    super(message, cause);
  }
}
