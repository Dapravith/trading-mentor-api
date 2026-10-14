package kh.mentor.trading.telegram;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import kh.mentor.trading.alerts.TelegramDeliveryException;
import kh.mentor.trading.telegram.TelegramCommandGateway.IncomingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Disabled by default. Long-polls on its own thread so a 25-second getUpdates
 * call never blocks the single-threaded @Scheduled scan and dispatch jobs.
 * Only private chats from allow-listed Telegram user IDs are executed.
 *
 * Responsiveness: polling never waits for a command to finish. Read-only commands
 * run concurrently on virtual threads, so a slow /research does not delay /help.
 * Paper-ledger commands (/open, /close, /confirm, /cancel) run one at a time, in order.
 * "typing…" is shown while a command runs; replies are retried on transient errors.
 */
@Component
@ConditionalOnProperty(prefix="trading.telegram-commands", name="enabled", havingValue="true")
public class TelegramCommandPoller implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(TelegramCommandPoller.class);
    /** Commands queued while the app was down are not replayed blindly. */
    static final long MAX_COMMAND_AGE_SECONDS = 300;
    static final String COMMAND_FAILED = "⚠️ Command failed; no trading action was taken.";
    /** Telegram shows "typing…" for about 5 seconds per sendChatAction. */
    private static final long TYPING_REFRESH_SECONDS = 4;
    private static final long POLL_BASE_DELAY_MS = 500;
    private static final long POLL_MAX_DELAY_MS = 15_000;
    private static final long POLL_CONFLICT_DELAY_MS = 30_000;
    private final TelegramCommandGateway gateway;
    private final TelegramCommandHandler handler;
    private final TelegramReplySender replies;
    private final Set<Long> allowedUserIds;
    private final int pollTimeoutSeconds;
    private final Executor commands;
    private final Executor ledger;
    private final ScheduledExecutorService typing;
    private final RetryBackoff.Sleeper sleeper;
    private volatile boolean running;
    private Thread worker;
    private long offset;

    @Autowired
    public TelegramCommandPoller(TelegramCommandGateway gateway, TelegramCommandHandler handler,
        @Value("${trading.alert.telegram-token:}") String token,
        @Value("${trading.telegram-commands.allowed-user-ids:}") String allowedUserIds,
        @Value("${trading.telegram-commands.poll-timeout-seconds:25}") int pollTimeoutSeconds) {
        this(gateway, handler, token, allowedUserIds, pollTimeoutSeconds,
            Executors.newVirtualThreadPerTaskExecutor(),
            Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("telegram-ledger").daemon().factory()),
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("telegram-typing").daemon().factory()),
            Thread::sleep);
    }

    TelegramCommandPoller(TelegramCommandGateway gateway, TelegramCommandHandler handler,
        String token, String allowedUserIds, int pollTimeoutSeconds,
        Executor commands, Executor ledger, ScheduledExecutorService typing,
        RetryBackoff.Sleeper sleeper) {
        if (token.isBlank()) {
            throw new IllegalStateException("Telegram commands enabled without a bot token");
        }
        this.allowedUserIds = parseIds(allowedUserIds);
        if (this.allowedUserIds.isEmpty()) {
            throw new IllegalStateException(
                "Telegram commands enabled without TELEGRAM_ALLOWED_USER_IDS");
        }
        if (pollTimeoutSeconds < 1 || pollTimeoutSeconds > 50) {
            throw new IllegalArgumentException("Invalid Telegram poll timeout");
        }
        this.gateway = gateway;
        this.handler = handler;
        this.replies = new TelegramReplySender(gateway, sleeper);
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.commands = commands;
        this.ledger = ledger;
        this.typing = typing;
        this.sleeper = sleeper;
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofPlatform().name("telegram-commands").daemon().start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        for (Object executor : List.of(commands, ledger, typing)) {
            if (executor instanceof ExecutorService service) service.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() { return running; }

    private void loop() {
        try {
            gateway.registerCommands(TelegramCommandHandler.COMMANDS);
        } catch (RuntimeException e) {
            log.warn("telegram_commands_menu_failed code={}", code(e)); // menu is optional
        }
        int failures = 0;
        while (running) {
            try {
                processBatch(gateway.poll(offset, pollTimeoutSeconds),
                    Instant.now().getEpochSecond());
                if (failures > 0) log.info("telegram_commands_poll_recovered after={}", failures);
                failures = 0;
            } catch (RuntimeException e) {
                failures++;
                long delay = pollRetryDelayMs(e, failures);
                log.warn("telegram_commands_poll_failed code={} attempt={} retryInMs={}",
                    code(e), failures, delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Fast first retry for blips; Telegram's retry_after wins; conflicts retry slowly. */
    static long pollRetryDelayMs(RuntimeException e, int failures) {
        if (e instanceof TelegramDeliveryException t) {
            if (t.retryAfterSeconds() > 0) return Math.min(60_000, t.retryAfterSeconds() * 1000);
            // Another poller/webhook owns the bot, or the token was rejected.
            if (t.errorCode().equals("POLLING_CONFLICT") || t.permanent()) {
                return POLL_CONFLICT_DELAY_MS;
            }
        }
        return RetryBackoff.delayMs(failures, POLL_BASE_DELAY_MS, POLL_MAX_DELAY_MS);
    }

    /** One getUpdates batch; offset advances first so a bad update cannot loop forever. */
    void processBatch(List<IncomingMessage> messages, long nowEpochSeconds) {
        for (IncomingMessage message : messages) {
            offset = Math.max(offset, message.updateId() + 1);
            try {
                dispatch(message, nowEpochSeconds);
            } catch (RuntimeException e) {
                log.warn("telegram_command_dispatch_failed code={}", code(e));
            }
        }
    }

    long offset() { return offset; }

    private void dispatch(IncomingMessage message, long nowEpochSeconds) {
        // Group and channel commands would expose replies to other members.
        if (message.text().isBlank() || !"private".equals(message.chatType())) return;
        long chatId = message.chatId();
        if (message.fromUserId() == null || !allowedUserIds.contains(message.fromUserId())) {
            log.info("telegram_command_rejected reason=unauthorized_user");
            commands.execute(() -> replies.send(chatId,
                "⛔ Not authorized. Your Telegram user ID is " + message.fromUserId() + "."));
            return;
        }
        if (nowEpochSeconds - message.sentAtEpochSeconds() > MAX_COMMAND_AGE_SECONDS) {
            commands.execute(() -> replies.send(chatId,
                "⏱ Ignored a command sent while the bot was offline. "
                + "Send it again if you still need it."));
            return;
        }
        Executor lane = TelegramCommandHandler.mutatesLedger(message.text()) ? ledger : commands;
        lane.execute(() -> run(chatId, message.text()));
    }

    private void run(long chatId, String text) {
        ScheduledFuture<?> typingIndicator = typing.scheduleAtFixedRate(
            () -> replies.typing(chatId), 0, TYPING_REFRESH_SECONDS, TimeUnit.SECONDS);
        String reply;
        try {
            reply = handler.handle(chatId, text);
        } catch (RuntimeException e) {
            log.warn("telegram_command_failed code={}", code(e));
            reply = COMMAND_FAILED;
        } finally {
            typingIndicator.cancel(false);
        }
        replies.send(chatId, reply);
    }

    private static Set<Long> parseIds(String raw) {
        try {
            return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("TELEGRAM_ALLOWED_USER_IDS must be numeric user IDs");
        }
    }

    private static String code(RuntimeException e) {
        return e instanceof TelegramDeliveryException t ? t.errorCode() : e.getClass().getSimpleName();
    }
}
