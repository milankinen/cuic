package cuic.internal;

public class StaleObjectException extends RuntimeException {
  public StaleObjectException() {
    super("Tried to access stale js object handle", null, false, false);
  }
}
