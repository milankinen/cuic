package cuic.internal;

public class AbortTestError extends Error {
  public AbortTestError() {
    super("cuic test aborted", null, false, false);
  }
}
