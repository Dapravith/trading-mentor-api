package kh.mentor.trading.alerts;

/** Sanitized error type. Never include provider URL, token, or raw response. */
public final class TelegramDeliveryException extends RuntimeException {
    private final String errorCode;
    private final boolean permanent;
    private final long retryAfterSeconds;
    public TelegramDeliveryException(String errorCode, boolean permanent) {
        this(errorCode, permanent, 0);
    }
    /** retryAfterSeconds: Telegram's HTTP 429 "parameters.retry_after", or 0 when absent. */
    public TelegramDeliveryException(String errorCode, boolean permanent, long retryAfterSeconds) {
        super(errorCode);
        this.errorCode = errorCode;
        this.permanent = permanent;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }
    public String errorCode() { return errorCode; }
    public boolean permanent() { return permanent; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
