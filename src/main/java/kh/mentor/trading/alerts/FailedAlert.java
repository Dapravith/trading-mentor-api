package kh.mentor.trading.alerts;

/** Metadata only: never expose the Telegram message body or bot credentials. */
public record FailedAlert(String eventId, String strategyVersion, int attempts,
    String errorCode, long createdAtMs) {}
