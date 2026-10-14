package kh.mentor.trading.telegram;

import java.util.List;
import java.util.Map;

/** Boundary for inbound bot commands and direct replies; mockable in tests. */
public interface TelegramCommandGateway {
    /** Long-polls Telegram; returns updates with id >= offset. */
    List<IncomingMessage> poll(long offset, int timeoutSeconds);

    /** Interactive reply to the chat that issued a command; not routed through the outbox. */
    void reply(long chatId, String text);

    /** Shows "typing…" in the chat for about 5 seconds, or until the next reply. */
    void sendTyping(long chatId);

    /** Publishes the "/" command menu shown by Telegram clients. */
    void registerCommands(Map<String, String> commands);

    record IncomingMessage(long updateId, long chatId, String chatType,
        Long fromUserId, String text, long sentAtEpochSeconds) {}
}
