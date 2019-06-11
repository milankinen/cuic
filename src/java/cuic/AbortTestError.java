package cuic;

public class AbortTestError extends Error {
  public AbortTestError() {
    super("Test aborted due to failed assertion");
  }
}
