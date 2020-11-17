package cuic;

public class TimeoutException extends CuicException {
  private final Object latestValue;
  public TimeoutException(String msg, Object latestValue) {
    super(msg);
    this.latestValue = latestValue;
  }

  public Object getLatestValue() {
    return latestValue;
  }
}
