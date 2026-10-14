package kh.mentor.trading.alerts;

/** Provider ACK can be verified for one event without exposing message content. */
public record OutboxEventStatus(String eventId, String status, int attempts,
    Long deliveredAtMs, Long telegramMessageId, String errorCode) {}
