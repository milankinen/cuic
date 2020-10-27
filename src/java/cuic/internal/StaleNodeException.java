package cuic.internal;

public class StaleNodeException extends RuntimeException {
    public StaleNodeException() {
        super("Stale node", null, false, false);
    }
}
