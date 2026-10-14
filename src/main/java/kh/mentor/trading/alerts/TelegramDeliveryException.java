package kh.mentor.trading.alerts;

/** Sanitized error type. Never include provider URL, token, or raw response. */
public final class TelegramDeliveryException extends RuntimeException {
    private final String errorCode;
    private final boolean permanent;
    public TelegramDeliveryException(String errorCode, boolean permanent) {
        super(errorCode);
        this.errorCode = errorCode;
        this.permanent = permanent;
    }
    public String errorCode() { return errorCode; }
    public boolean permanent() { return permanent; }
}
