package kh.mentor.trading.alerts;

/** Boundary for a provider-acknowledged Telegram send; mockable in tests. */
public interface TelegramTransport {
    DeliveryReceipt send(String payload);
    record DeliveryReceipt(long messageId) {}
}
