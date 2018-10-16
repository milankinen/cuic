package cuic;

public class AbortTestError extends Error {
  public AbortTestError() {
    super("Test aborted because assertion did not pass");
    StackTraceElement topFrame = this.getStackTrace()[0];
    for (StackTraceElement ste : this.getStackTrace()) {
      if (ste != topFrame && ste.getFileName() != null && ste.getFileName().equals(topFrame.getFileName()) && ste.getLineNumber() == topFrame.getLineNumber()) {
        this.setStackTrace(new StackTraceElement[] { ste });
        return;
      }
    }
    this.setStackTrace(new StackTraceElement[] { });
  }
}
