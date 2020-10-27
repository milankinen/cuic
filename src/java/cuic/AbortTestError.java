package cuic;

public class AbortTestError extends Error {
  private final Object details;

  public AbortTestError(Object details) {
    super("Test aborted");
    this.details = details;
  }

  public Object getDetails() {
    return details;
  }
}
