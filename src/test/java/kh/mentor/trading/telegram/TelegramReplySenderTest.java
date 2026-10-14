package kh.mentor.trading.telegram;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kh.mentor.trading.alerts.TelegramDeliveryException;
import org.junit.jupiter.api.Test;

class TelegramReplySenderTest {
    final List<Long> sleeps = new ArrayList<>();
    final List<RuntimeException> failures = new ArrayList<>();
    int attempts;
    boolean typingFails;
    final TelegramCommandGateway gateway = new TelegramCommandGateway() {
        @Override public List<IncomingMessage> poll(long offset, int timeoutSeconds) { return List.of(); }
        @Override public void reply(long chatId, String text) {
            attempts++;
            if (!failures.isEmpty()) throw failures.removeFirst();
        }
        @Override public void sendTyping(long chatId) {
            if (typingFails) throw new TelegramDeliveryException("NETWORK_ERROR", false);
        }
        @Override public void registerCommands(Map<String, String> commands) {}
    };
    final TelegramReplySender sender = new TelegramReplySender(gateway, sleeps::add);

    @Test void transientFailuresAreRetriedWithGrowingBackoff() {
        failures.add(new TelegramDeliveryException("NETWORK_ERROR", false));
        failures.add(new TelegramDeliveryException("TELEGRAM_UNAVAILABLE", false));
        assertTrue(sender.send(1, "hi"));
        assertEquals(3, attempts);
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(0) >= 250 && sleeps.get(0) <= 500, sleeps.toString());
        assertTrue(sleeps.get(1) >= 500 && sleeps.get(1) <= 1_000, sleeps.toString());
    }

    @Test void rateLimitHonoursRetryAfter() {
        failures.add(new TelegramDeliveryException("RATE_LIMITED", false, 3));
        assertTrue(sender.send(1, "hi"));
        assertEquals(List.of(3_000L), sleeps);
    }

    @Test void permanentRejectionIsNotRetried() {
        failures.add(new TelegramDeliveryException("TELEGRAM_REJECTED", true));
        assertFalse(sender.send(1, "hi"));
        assertEquals(1, attempts);
        assertTrue(sleeps.isEmpty());
    }

    @Test void givesUpAfterMaxAttempts() {
        for (int i = 0; i < 10; i++) failures.add(new TelegramDeliveryException("NETWORK_ERROR", false));
        assertFalse(sender.send(1, "hi"));
        assertEquals(TelegramReplySender.MAX_ATTEMPTS, attempts);
        assertEquals(TelegramReplySender.MAX_ATTEMPTS - 1, sleeps.size());
    }

    @Test void interruptedSleepStopsRetrying() {
        failures.add(new TelegramDeliveryException("NETWORK_ERROR", false));
        TelegramReplySender interrupted = new TelegramReplySender(gateway, ms -> {
            throw new InterruptedException();
        });
        assertFalse(interrupted.send(1, "hi"));
        assertTrue(Thread.interrupted()); // also clears the flag for other tests
    }

    @Test void typingFailureIsIgnored() {
        typingFails = true;
        assertDoesNotThrow(() -> sender.typing(1));
    }
}
