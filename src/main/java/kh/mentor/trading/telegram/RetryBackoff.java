package kh.mentor.trading.telegram;

import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with jitter, so retries don't all hit Telegram/Binance at once. */
final class RetryBackoff {
    /** Blocking pause; replaced by a recorder in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private RetryBackoff() {}

    /** attempt 1 → [base/2, base], doubling each attempt, never above maxMs. */
    static long delayMs(int attempt, long baseMs, long maxMs) {
        long ceiling = Math.min(maxMs, baseMs << Math.min(Math.max(attempt - 1, 0), 20));
        long half = ceiling / 2;
        return half + ThreadLocalRandom.current().nextLong(ceiling - half + 1);
    }
}
