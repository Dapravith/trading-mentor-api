package kh.mentor.trading.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import kh.mentor.trading.alerts.TelegramDeliveryException;
import kh.mentor.trading.telegram.TelegramCommandGateway.IncomingMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TelegramCommandPollerTest {
    static final long NOW = 1_700_000_000L;
    final List<String> replies = new CopyOnWriteArrayList<>();
    final List<String> lanes = new CopyOnWriteArrayList<>();
    final TelegramCommandGateway gateway = new TelegramCommandGateway() {
        @Override public List<IncomingMessage> poll(long offset, int timeoutSeconds) { return List.of(); }
        @Override public void reply(long chatId, String text) { replies.add(chatId + ":" + text); }
        @Override public void sendTyping(long chatId) {}
        @Override public void registerCommands(Map<String, String> commands) {}
    };
    final TelegramCommandHandler handler = mock(TelegramCommandHandler.class);
    final ScheduledExecutorService typing = Executors.newSingleThreadScheduledExecutor();
    // Run inline so assertions see the reply; record which lane each command used.
    final Executor commands = task -> { lanes.add("commands"); task.run(); };
    final Executor ledger = task -> { lanes.add("ledger"); task.run(); };
    final TelegramCommandPoller poller = poller("token", "111, 222", 25);

    TelegramCommandPoller poller(String token, String ids, int timeout) {
        return new TelegramCommandPoller(gateway, handler, token, ids, timeout,
            commands, ledger, typing, ms -> {});
    }

    @AfterEach void stopTyping() { typing.shutdownNow(); }

    static IncomingMessage message(long updateId, String chatType, Long from, String text, long sentAt) {
        return new IncomingMessage(updateId, from == null ? -100 : from, chatType, from, text, sentAt);
    }

    @Test void allowedPrivateCommandIsAnswered() {
        when(handler.handle(111, "/status")).thenReturn("ok");
        poller.processBatch(List.of(message(5, "private", 111L, "/status", NOW)), NOW);
        assertEquals(List.of("111:ok"), replies);
        assertEquals(List.of("commands"), lanes);
        assertEquals(6, poller.offset());
    }

    @Test void paperLedgerCommandsUseTheSerialLane() {
        when(handler.handle(anyLong(), anyString())).thenReturn("ok");
        poller.processBatch(List.of(
            message(1, "private", 111L, "/open BTC 100 98 110 CALM x", NOW),
            message(2, "private", 111L, "/help", NOW),
            message(3, "private", 111L, "/confirm", NOW)), NOW);
        assertEquals(List.of("ledger", "commands", "ledger"), lanes);
    }

    @Test void unknownUserIsRejectedWithTheirId() {
        poller.processBatch(List.of(message(9, "private", 333L, "/paper", NOW)), NOW);
        verify(handler, never()).handle(anyLong(), anyString());
        assertEquals(List.of("333:⛔ Not authorized. Your Telegram user ID is 333."), replies);
    }

    @Test void groupChannelAndEmptyMessagesAreIgnored() {
        poller.processBatch(List.of(
            message(1, "group", 111L, "/paper", NOW),
            message(2, "channel", null, "/paper", NOW),
            message(3, "private", 111L, "", NOW)), NOW);
        verify(handler, never()).handle(anyLong(), anyString());
        assertTrue(replies.isEmpty());
        assertEquals(4, poller.offset());
    }

    @Test void staleCommandIsNotReplayed() {
        long sentAt = NOW - TelegramCommandPoller.MAX_COMMAND_AGE_SECONDS - 1;
        poller.processBatch(List.of(message(3, "private", 222L, "/confirm", sentAt)), NOW);
        verify(handler, never()).handle(anyLong(), anyString());
        assertTrue(replies.getFirst().contains("Ignored a command"));
    }

    @Test void failingCommandStillGetsAReplyAndDoesNotBlockTheBatch() {
        when(handler.handle(111, "/a")).thenThrow(new IllegalStateException("boom"));
        when(handler.handle(111, "/b")).thenReturn("b");
        poller.processBatch(List.of(message(10, "private", 111L, "/a", NOW),
            message(11, "private", 111L, "/b", NOW)), NOW);
        assertEquals(List.of("111:" + TelegramCommandPoller.COMMAND_FAILED, "111:b"), replies);
        assertEquals(12, poller.offset());
    }

    @Test void pollRetriesFastAtFirstThenBackOff() {
        var network = new TelegramDeliveryException("NETWORK_ERROR", false);
        long first = TelegramCommandPoller.pollRetryDelayMs(network, 1);
        assertTrue(first >= 250 && first <= 500, "first=" + first);
        long later = TelegramCommandPoller.pollRetryDelayMs(network, 10);
        assertTrue(later >= 7_500 && later <= 15_000, "later=" + later);
        assertEquals(7_000, TelegramCommandPoller.pollRetryDelayMs(
            new TelegramDeliveryException("RATE_LIMITED", false, 7), 1));
        assertEquals(30_000, TelegramCommandPoller.pollRetryDelayMs(
            new TelegramDeliveryException("POLLING_CONFLICT", false), 1));
    }

    @Test void refusesToStartWithoutCredentialsOrAllowList() {
        assertThrows(IllegalStateException.class, () -> poller("", "111", 25));
        assertThrows(IllegalStateException.class, () -> poller("token", " ", 25));
        assertThrows(IllegalStateException.class, () -> poller("token", "@me", 25));
        assertThrows(IllegalArgumentException.class, () -> poller("token", "111", 0));
    }
}
