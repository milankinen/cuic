package cuic;

public class DevtoolsProtocolException extends CuicException {
    private final long code;

    public DevtoolsProtocolException(String message, long code) {
        super(message);
        this.code = code;
    }

    public String toString() {
        return DevtoolsProtocolException.class.getName() + ": " + this.getMessage() + " (code = " + this.code + ")";
    }

    public long getCode() {
        return code;
    }
}
