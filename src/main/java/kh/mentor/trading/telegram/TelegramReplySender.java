package kh.mentor.trading.telegram;

import kh.mentor.trading.alerts.TelegramDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries a reply on transient Telegram failures (network, 5xx, 429), honouring
 * retry_after. Only the reply is resent; the command is never executed twice.
 * A network error after Telegram accepted the message can produce a duplicate reply.
 */
final class TelegramReplySender {
    private static final Logger log = LoggerFactory.getLogger(TelegramReplySender.class);
    static final int MAX_ATTEMPTS = 4;
    private static final long BASE_DELAY_MS = 500;
    private static final long MAX_DELAY_MS = 8_000;
    private static final long MAX_RETRY_AFTER_MS = 30_000;
    private final TelegramCommandGateway gateway;
    private final RetryBackoff.Sleeper sleeper;

    TelegramReplySender(TelegramCommandGateway gateway, RetryBackoff.Sleeper sleeper) {
        this.gateway = gateway;
        this.sleeper = sleeper;
    }

    /** Returns true once Telegram acknowledges the reply. */
    boolean send(long chatId, String text) {
        for (int attempt = 1; ; attempt++) {
            try {
                gateway.reply(chatId, text);
                if (attempt > 1) log.info("telegram_reply_recovered attempts={}", attempt);
                return true;
            } catch (TelegramDeliveryException e) {
                if (e.permanent() || attempt >= MAX_ATTEMPTS || e.errorCode().equals("INTERRUPTED")) {
                    log.warn("telegram_reply_failed code={} attempts={}", e.errorCode(), attempt);
                    return false;
                }
                long delay = e.retryAfterSeconds() > 0
                    ? Math.min(MAX_RETRY_AFTER_MS, e.retryAfterSeconds() * 1000)
                    : RetryBackoff.delayMs(attempt, BASE_DELAY_MS, MAX_DELAY_MS);
                log.info("telegram_reply_retry code={} attempt={} retryInMs={}",
                    e.errorCode(), attempt, delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.warn("telegram_reply_failed code=INTERRUPTED attempts={}", attempt);
                    return false;
                }
            } catch (RuntimeException e) {
                log.warn("telegram_reply_failed code={} attempts={}",
                    e.getClass().getSimpleName(), attempt);
                return false;
            }
        }
    }

    /** Best effort: a missed "typing…" indicator is not worth a retry. */
    void typing(long chatId) {
        try {
            gateway.sendTyping(chatId);
        } catch (RuntimeException ignored) {
            // The reply itself still goes through send() with retries.
        }
    }
}
