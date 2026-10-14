package kh.mentor.trading.telegram;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import kh.mentor.trading.alerts.AlertRepository;
import kh.mentor.trading.alerts.AlertScanService;
import kh.mentor.trading.alerts.OutboxEventStatus;
import kh.mentor.trading.backtest.BacktestResult;
import kh.mentor.trading.backtest.BacktestService;
import kh.mentor.trading.backtest.ChronologicalResearchResult;
import kh.mentor.trading.backtest.ChronologicalResearchService;
import kh.mentor.trading.backtest.PaperTrade;
import kh.mentor.trading.market.MarketDataException;
import kh.mentor.trading.paper.ClosePaperRequest;
import kh.mentor.trading.paper.OpenPaperRequest;
import kh.mentor.trading.paper.PaperPortfolioService;
import kh.mentor.trading.paper.PaperPosition;
import kh.mentor.trading.paper.PaperSummary;
import kh.mentor.trading.risk.RiskPlan;
import kh.mentor.trading.risk.RiskPlanner;
import kh.mentor.trading.strategy.MarketAnalysis;
import kh.mentor.trading.strategy.ScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Turns one authorized Telegram command into a plain-text reply.
 * Same services as the loopback HTTP API; paper ledger writes need an explicit /confirm.
 * Never places real orders.
 */
@Component
public class TelegramCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(TelegramCommandHandler.class);
    static final long CONFIRM_TTL_MS = 5 * 60_000L;
    /** Read-only market commands retry transient Binance failures before replying. */
    static final int MARKET_ATTEMPTS = 3;
    private static final Set<String> LEDGER_COMMANDS =
        Set.of("/open", "/close", "/confirm", "/cancel");
    private static final ZoneId CAMBODIA = ZoneId.of("Asia/Phnom_Penh");
    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT);

    /** Telegram "/" menu in display order; names may only use a-z, 0-9 and underscore. */
    public static final Map<String, String> COMMANDS = commandMenu();

    private final ScanService scans;
    private final AlertScanService alertScans;
    private final AlertRepository alerts;
    private final BacktestService backtests;
    private final ChronologicalResearchService research;
    private final PaperPortfolioService paper;
    private final RiskPlanner risk;
    private final String defaultInterval;
    private final double defaultBalance;
    private final boolean deliveryEnabled;
    private final boolean scanScheduled;
    private final LongSupplier clock;
    private final RetryBackoff.Sleeper sleeper;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(long expiresAtMs, Supplier<String> action) {}

    @Autowired
    public TelegramCommandHandler(ScanService scans, AlertScanService alertScans,
        AlertRepository alerts, BacktestService backtests,
        ChronologicalResearchService research, PaperPortfolioService paper, RiskPlanner risk,
        @Value("${trading.interval:4h}") String defaultInterval,
        @Value("${trading.paper-balance-usdt:1000}") double defaultBalance,
        @Value("${trading.alert.delivery-enabled:false}") boolean deliveryEnabled,
        @Value("${trading.scan.scheduling-enabled:false}") boolean scanScheduled) {
        this(scans, alertScans, alerts, backtests, research, paper, risk, defaultInterval,
            defaultBalance, deliveryEnabled, scanScheduled, System::currentTimeMillis,
            Thread::sleep);
    }

    TelegramCommandHandler(ScanService scans, AlertScanService alertScans,
        AlertRepository alerts, BacktestService backtests,
        ChronologicalResearchService research, PaperPortfolioService paper, RiskPlanner risk,
        String defaultInterval, double defaultBalance, boolean deliveryEnabled,
        boolean scanScheduled, LongSupplier clock, RetryBackoff.Sleeper sleeper) {
        this.scans = scans;
        this.alertScans = alertScans;
        this.alerts = alerts;
        this.backtests = backtests;
        this.research = research;
        this.paper = paper;
        this.risk = risk;
        this.defaultInterval = defaultInterval;
        this.defaultBalance = defaultBalance;
        this.deliveryEnabled = deliveryEnabled;
        this.scanScheduled = scanScheduled;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Paper-ledger commands must run one at a time, in the order they were sent. */
    public static boolean mutatesLedger(String text) {
        return LEDGER_COMMANDS.contains(commandOf(text));
    }

    private static String commandOf(String text) {
        String first = text == null ? "" : text.trim().split("\\s+", 2)[0];
        String command = first.toLowerCase(Locale.ROOT);
        int at = command.indexOf('@'); // "/scan@MyBot" form used in group menus
        return at > 0 ? command.substring(0, at) : command;
    }

    public String handle(long chatId, String text) {
        String[] parts = text == null ? new String[] {""} : text.trim().split("\\s+");
        String command = commandOf(text);
        List<String> args = List.of(parts).subList(1, parts.length);
        try {
            return switch (command) {
                case "/start", "/help" -> help();
                case "/status" -> status();
                case "/scan" -> scan(args);
                case "/scan_all" -> scanAll(args);
                case "/queue_scan" -> queueScan();
                case "/alerts" -> alertCounts();
                case "/event" -> event(args);
                case "/test_alert" -> testAlert();
                case "/last_test" -> lastTest();
                case "/backtest" -> backtest(args);
                case "/research" -> research(args);
                case "/paper" -> paperSummary();
                case "/positions" -> positions(args);
                case "/position" -> position(args);
                case "/open" -> previewOpen(chatId, args);
                case "/close" -> previewClose(chatId, args);
                case "/confirm" -> confirm(chatId);
                case "/cancel" -> pending.remove(chatId) == null
                    ? "Nothing to cancel." : "❎ Pending paper action discarded.";
                default -> command.startsWith("/")
                    ? "Unknown command. Send /help for the full list."
                    : "Send a command that starts with /. Try /help.";
            };
        } catch (IllegalArgumentException e) {
            return "⚠️ " + e.getMessage();
        } catch (MarketDataException e) {
            return "⚠️ Market data unavailable: " + e.getMessage();
        } catch (DataAccessException e) {
            log.error("telegram_command_database_failed type={}", e.getClass().getSimpleName());
            return "⚠️ Database unavailable. Check sanitized server logs. No trading action was taken.";
        } catch (RuntimeException e) {
            log.error("telegram_command_failed command={} type={}",
                command.startsWith("/") && COMMANDS.containsKey(command.substring(1))
                    ? command : "unknown",
                e.getClass().getSimpleName());
            return "⚠️ Command failed; no trading action was taken.";
        }
    }

    private String help() {
        return """
            🤖 AI Trading Mentor — commands
            Paper research only. No real orders are ever placed.

            📈 Market
            /scan [SYMBOL] [4h|1d] [BALANCE] — analyse the last completed candle
            /scan_all [4h|1d] — quick view of BTC and ETH
            /queue_scan — scan and queue qualifying setups for the alert channel

            🔔 Alerts
            /alerts — outbox delivery counts
            /event ID — delivery status of one alert
            /test_alert — queue a connection-test message
            /last_test — result of the latest connection test

            🧪 Research
            /backtest [SYMBOL] [4h|1d] [BALANCE]
            /research [SYMBOL] [START] [END] [BALANCE] — dates as YYYY-MM-DD

            📒 Paper portfolio
            /paper — balance and realized P&L
            /positions [open|closed|all]
            /position ID
            /open SYMBOL ENTRY STOP TARGET EMOTION THESIS…
            /close ID EXIT_PRICE MANUAL|STOP|TARGET REFLECTION…
            /confirm — record the pending /open or /close
            /cancel — discard it

            /status — modes, outbox and paper overview
            /help — this list

            Symbols: BTC, ETH (or BTCUSDT, ETHUSDT). IDs: first 8 characters are enough.
            Emotions: CALM, FOMO, FEAR, IMPULSIVE, UNCERTAIN.
            Example: /open BTC 60000 58500 64000 CALM Retest of breakout level""";
    }

    private String status() {
        PaperSummary s = paper.summary();
        return """
            🤖 AI Trading Mentor — status
            Mode: READ_ONLY_PAPER_RESEARCH (no real orders)
            Telegram alert delivery: %s
            Scheduled market scan: %s (%s)
            Alert outbox: %s
            Paper cash: %s USDT | open positions: %d | realized P&L: %s USDT"""
            .formatted(onOff(deliveryEnabled), onOff(scanScheduled), defaultInterval,
                counts(), money(s.availableCashUsdt()), s.openPositions(),
                signed(s.realizedPnlUsdt()));
    }

    private String scan(List<String> args) {
        String symbol = symbol(arg(args, 0, "BTCUSDT"));
        String interval = arg(args, 1, defaultInterval).toLowerCase(Locale.ROOT);
        double balance = number(arg(args, 2, null), defaultBalance, "BALANCE");
        MarketAnalysis a = withMarketRetry(() -> scans.scan(symbol, interval, balance));
        StringBuilder out = new StringBuilder();
        out.append("📈 ").append(a.symbol()).append(' ').append(a.interval())
            .append(" — ").append(a.status()).append('\n')
            .append("Completed candle: ").append(time(a.candleCloseMs())).append('\n')
            .append("Close: ").append(price(a.lastCompletedClose()))
            .append(" USDT (historical, NOT a live quote)\n")
            .append("RSI14 %.2f | EMA20 %s | EMA50 %s\n".formatted(
                a.rsi14(), price(a.ema20()), price(a.ema50())))
            .append("ATR14 %s | Volume vs 20-avg %.2fx\n".formatted(
                price(a.atr14()), a.volumeRatio()));
        if (!a.rationale().isEmpty()) {
            out.append("\nWhy:\n");
            a.rationale().forEach(r -> out.append("• ").append(r).append('\n'));
        }
        RiskPlan plan = a.paperRisk();
        if (plan != null) out.append('\n').append(riskLines(plan));
        return out.append("\n⚠️ ").append(a.disclaimer()).toString();
    }

    private String scanAll(List<String> args) {
        String interval = arg(args, 0, defaultInterval).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("📈 Market overview (" + interval + ")\n");
        for (String symbol : List.of("BTCUSDT", "ETHUSDT")) {
            MarketAnalysis a = withMarketRetry(() -> scans.scan(symbol, interval, defaultBalance));
            out.append("%s: %s | close %s | RSI %.1f\n".formatted(
                symbol, a.status(), price(a.lastCompletedClose()), a.rsi14()));
        }
        return out.append("Use /scan SYMBOL for details. Not trading advice.").toString();
    }

    private String queueScan() {
        // Safe to retry: the outbox de-duplicates by completed candle.
        int queued = withMarketRetry(alertScans::scanAndQueue);
        return "🗂 Queued " + queued + " new paper setup alert(s).\n" + (queued == 0
            ? "No qualifying completed-candle setup, or it was already queued."
            : deliveryEnabled ? "The outbox worker will post them to the alert channel."
            : "Telegram delivery is OFF, so they stay PENDING.");
    }

    private String alertCounts() {
        return "🔔 Alert outbox\n" + counts()
            + "\nFAILED alerts need operator review; they are never retried automatically.";
    }

    private String event(List<String> args) {
        return "🔔 " + eventLines(alerts.eventStatus(required(args, 0, "/event ID")));
    }

    private String testAlert() {
        if (!deliveryEnabled) {
            return "Telegram delivery is OFF (TELEGRAM_DELIVERY_ENABLED=false); "
                + "a test message would stay PENDING.";
        }
        String id = alerts.enqueueConnectivityTest();
        return "🧪 Queued connection test.\nEvent: " + id
            + "\nThe worker sends it within about a minute. Check with /last_test.";
    }

    private String lastTest() {
        OutboxEventStatus latest = alerts.latestConnectivityTest();
        return latest == null ? "No connection test recorded yet. Send /test_alert."
            : "🧪 Latest connection test\n" + eventLines(latest);
    }

    private String backtest(List<String> args) {
        String symbol = symbol(arg(args, 0, "BTCUSDT"));
        String interval = arg(args, 1, defaultInterval).toLowerCase(Locale.ROOT);
        double balance = number(arg(args, 2, null), defaultBalance, "BALANCE");
        BacktestResult r = withMarketRetry(() -> backtests.run(symbol, interval, balance));
        StringBuilder out = new StringBuilder("🧪 Backtest — ")
            .append(r.symbol()).append(' ').append(r.interval()).append('\n')
            .append(backtestLines(r));
        List<PaperTrade> trades = r.trades();
        if (!trades.isEmpty()) {
            out.append("\nLast trades:\n");
            for (PaperTrade t : trades.subList(Math.max(0, trades.size() - 3), trades.size())) {
                out.append("• %s %s → %s %s USDT (%s)\n".formatted(time(t.entryCandleOpenMs()),
                    price(t.entry()), price(t.exit()), signed(t.netPnlUsdt()), t.exitReason()));
            }
        }
        r.limitations().stream().limit(3).forEach(l -> out.append("\nℹ️ ").append(l));
        return out.toString();
    }

    private String research(List<String> args) {
        String symbol = symbol(arg(args, 0, "BTCUSDT"));
        String start = arg(args, 1, "2022-01-01");
        String end = arg(args, 2, "2026-01-01");
        double balance = number(arg(args, 3, null), defaultBalance, "BALANCE");
        ChronologicalResearchResult r =
            withMarketRetry(() -> research.run(symbol, start, end, balance));
        StringBuilder out = new StringBuilder("🔬 Chronological research — ")
            .append(r.symbol()).append(' ').append(r.interval()).append('\n')
            .append(r.startInclusiveUtc()).append(" → ").append(r.endExclusiveUtc())
            .append(" | ").append(r.datasetCandles()).append(" candles | ")
            .append(r.strategyVersion()).append('\n');
        out.append("\nTraining\n").append(backtestLines(r.training()));
        out.append("\nValidation\n").append(backtestLines(r.validation()));
        out.append("\nTesting\n").append(backtestLines(r.testing()));
        r.warnings().forEach(w -> out.append("\n⚠️ ").append(w));
        return out.toString();
    }

    private String paperSummary() {
        PaperSummary s = paper.summary();
        return """
            📒 Paper portfolio (virtual USDT)
            Starting balance: %s
            Available cash: %s
            Reserved in open positions: %s
            Open: %d | Closed: %d
            Realized P&L: %s
            ℹ️ %s""".formatted(money(s.startingBalanceUsdt()), money(s.availableCashUsdt()),
            money(s.cashReservedInOpenPositionsUsdt()), s.openPositions(),
            s.closedPositions(), signed(s.realizedPnlUsdt()), s.limitation());
    }

    private String positions(List<String> args) {
        String filter = arg(args, 0, "all").toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "OPEN", "CLOSED").contains(filter)) {
            throw new IllegalArgumentException("Usage: /positions [open|closed|all]");
        }
        List<PaperPosition> rows = paper.recent().stream()
            .filter(p -> filter.equals("ALL") || p.status().equals(filter)).limit(15).toList();
        if (rows.isEmpty()) return "No " + filter.toLowerCase(Locale.ROOT) + " paper positions.";
        StringBuilder out = new StringBuilder("📒 Paper positions (" +
            filter.toLowerCase(Locale.ROOT) + ", newest first)\n");
        for (PaperPosition p : rows) {
            out.append("\n").append(p.id(), 0, 8).append(' ').append(p.symbol()).append(' ')
                .append(p.status()).append(" @ ").append(price(p.entryPrice()));
            if (p.netPnlUsdt() != null) out.append(" → ").append(signed(p.netPnlUsdt())).append(" USDT");
            else out.append(" | stop ").append(price(p.stopPrice()))
                .append(" target ").append(price(p.targetPrice()));
        }
        return out.append("\n\nDetails: /position ID").toString();
    }

    private String position(List<String> args) {
        return positionLines(resolve(required(args, 0, "/position ID")));
    }

    private String previewOpen(long chatId, List<String> args) {
        if (args.size() < 6) {
            throw new IllegalArgumentException(
                "Usage: /open SYMBOL ENTRY STOP TARGET EMOTION THESIS…\n"
                + "Example: /open BTC 60000 58500 64000 CALM Retest of breakout level");
        }
        String symbol = symbol(args.get(0));
        double entry = number(args.get(1), 0, "ENTRY");
        double stop = number(args.get(2), 0, "STOP");
        double target = number(args.get(3), 0, "TARGET");
        String emotion = args.get(4).toUpperCase(Locale.ROOT);
        if (!PaperPortfolioService.EMOTIONS.contains(emotion)) {
            throw new IllegalArgumentException("EMOTION must be one of CALM, FOMO, FEAR, IMPULSIVE, UNCERTAIN");
        }
        String thesis = String.join(" ", args.subList(5, args.size()));
        RiskPlan plan = risk.calculate(paper.summary().availableCashUsdt(), entry, stop, target);
        if (!plan.valid()) {
            return "❌ Paper entry rejected by risk checks:\n• "
                + String.join("\n• ", plan.reasons());
        }
        String requestId = UUID.randomUUID().toString(); // reused if /confirm is repeated
        pending.put(chatId, new Pending(clock.getAsLong() + CONFIRM_TTL_MS, () -> {
            PaperPosition opened = paper.open(new OpenPaperRequest(requestId, symbol,
                entry, stop, target, true, thesis, emotion));
            return "✅ Paper position recorded (virtual money only)\n\n" + positionLines(opened);
        }));
        return "📝 PAPER ENTRY PREVIEW — virtual money only\n"
            + symbol + "\n" + riskLines(plan)
            + "\nEmotion: " + emotion + "\nThesis: " + thesis
            + "\n\nSend /confirm within 5 minutes to record it, or /cancel.";
    }

    private String previewClose(long chatId, List<String> args) {
        if (args.size() < 4) {
            throw new IllegalArgumentException(
                "Usage: /close ID EXIT_PRICE MANUAL|STOP|TARGET REFLECTION…\n"
                + "Example: /close 1a2b3c4d 63900 TARGET Followed the plan");
        }
        PaperPosition position = resolve(args.get(0));
        if (!position.status().equals("OPEN")) {
            throw new IllegalArgumentException("Paper position " + position.id().substring(0, 8)
                + " is already closed");
        }
        double exit = number(args.get(1), 0, "EXIT_PRICE");
        String reason = args.get(2).toUpperCase(Locale.ROOT);
        if (!PaperPortfolioService.EXIT_REASONS.contains(reason)) {
            throw new IllegalArgumentException("Exit reason must be MANUAL, STOP or TARGET");
        }
        String reflection = String.join(" ", args.subList(3, args.size()));
        String id = position.id();
        pending.put(chatId, new Pending(clock.getAsLong() + CONFIRM_TTL_MS, () ->
            "✅ Paper position closed\n\n" + positionLines(paper.close(id,
                new ClosePaperRequest(exit, reason, reflection, true)))));
        return "📝 PAPER EXIT PREVIEW — virtual money only\n"
            + "%s %s qty %s\nEntry %s → exit %s (%s)\nGross move before costs: %s USDT\nReflection: %s"
                .formatted(id.substring(0, 8), position.symbol(), qty(position.quantity()),
                    price(position.entryPrice()), price(exit), reason,
                    signed(position.quantity() * (exit - position.entryPrice())), reflection)
            + "\n\nSend /confirm within 5 minutes to record it, or /cancel.";
    }

    private String confirm(long chatId) {
        Pending action = pending.remove(chatId);
        if (action == null) return "Nothing to confirm. Start with /open or /close.";
        if (clock.getAsLong() > action.expiresAtMs()) {
            return "⌛ That preview expired. Send the /open or /close command again.";
        }
        return action.action().get();
    }

    /** Validation errors fail at once; only MarketDataException (Binance) is retried. */
    private <T> T withMarketRetry(Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (MarketDataException e) {
                if (attempt >= MARKET_ATTEMPTS) throw e;
                long delay = RetryBackoff.delayMs(attempt, 1_000, 4_000);
                log.info("telegram_command_market_retry attempt={} retryInMs={}", attempt, delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private PaperPosition resolve(String idOrPrefix) {
        String key = idOrPrefix.toLowerCase(Locale.ROOT);
        if (key.length() == 36) return paper.get(key);
        if (key.length() < 4) {
            throw new IllegalArgumentException("Give at least 4 characters of the position ID");
        }
        List<PaperPosition> matches = paper.recent().stream()
            .filter(p -> p.id().startsWith(key)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("No paper position matches " + key);
        if (matches.size() > 1) {
            throw new IllegalArgumentException("ID " + key + " is ambiguous; type more characters");
        }
        return matches.getFirst();
    }

    private String positionLines(PaperPosition p) {
        StringBuilder out = new StringBuilder()
            .append("📒 ").append(p.symbol()).append(" — ").append(p.status())
            .append("\nID: ").append(p.id())
            .append("\nOpened: ").append(time(p.openedAtMs()))
            .append("\nEntry ").append(price(p.entryPrice()))
            .append(" | stop ").append(price(p.stopPrice()))
            .append(" | target ").append(price(p.targetPrice()))
            .append("\nQuantity: ").append(qty(p.quantity()))
            .append(" | cost incl. fees: ").append(money(p.entryCashUsdt())).append(" USDT")
            .append("\nEmotion: ").append(p.emotion())
            .append("\nThesis: ").append(p.thesis());
        if (p.closedAtMs() != null) {
            out.append("\nClosed: ").append(time(p.closedAtMs()))
                .append(" @ ").append(price(p.closePrice())).append(" (").append(p.exitReason()).append(')')
                .append("\nNet P&L: ").append(signed(p.netPnlUsdt())).append(" USDT")
                .append("\nReflection: ").append(p.reflection());
        }
        return out.toString();
    }

    private String riskLines(RiskPlan plan) {
        StringBuilder out = new StringBuilder("Paper risk plan (balance %s USDT)\n".formatted(
                money(plan.paperBalanceUsdt())))
            .append("Entry %s | stop %s | target %s\n".formatted(
                price(plan.entry()), price(plan.stop()), price(plan.target())))
            .append("Quantity %s | position %s USDT\n".formatted(
                qty(plan.quantity()), money(plan.positionUsdt())))
            .append("Est. loss %s | est. reward %s | net R:R %.2f".formatted(
                money(plan.estimatedLossUsdt()), money(plan.estimatedRewardUsdt()),
                plan.netRewardRisk()));
        plan.reasons().forEach(r -> out.append("\n• ").append(r));
        return out.toString();
    }

    private String backtestLines(BacktestResult r) {
        return """
            Balance: %s → %s USDT
            Net return: %s%% | buy & hold: %s%%
            Max closed-trade drawdown: %.2f%%
            Trades: %d | wins: %d | win rate: %.1f%%
            """.formatted(money(r.startingBalanceUsdt()), money(r.endingBalanceUsdt()),
            signed(r.netReturnPct()), signed(r.buyAndHoldReturnPct()),
            r.maxClosedTradeDrawdownPct(), r.completedTrades(), r.winningTrades(),
            r.winRatePct());
    }

    private String eventLines(OutboxEventStatus s) {
        return "Event: %s\nStatus: %s | attempts: %d%s%s%s".formatted(s.eventId(), s.status(),
            s.attempts(),
            s.deliveredAtMs() == null ? "" : "\nDelivered: " + time(s.deliveredAtMs()),
            s.telegramMessageId() == null ? "" : "\nTelegram message ID: " + s.telegramMessageId(),
            s.errorCode() == null ? "" : "\nLast error: " + s.errorCode());
    }

    private String counts() {
        Map<String, Integer> counts = alerts.statusCounts();
        List<String> parts = new ArrayList<>();
        for (String status : List.of("PENDING", "SENDING", "SENT", "FAILED")) {
            parts.add(status + " " + counts.getOrDefault(status, 0));
        }
        return String.join(" · ", parts);
    }

    private static Map<String, String> commandMenu() {
        Map<String, String> menu = new LinkedHashMap<>();
        menu.put("help", "All commands and examples");
        menu.put("status", "Modes, alert outbox and paper overview");
        menu.put("scan", "Analyse a pair: [SYMBOL] [4h|1d] [BALANCE]");
        menu.put("scan_all", "Quick BTC and ETH overview");
        menu.put("queue_scan", "Queue qualifying setups for the alert channel");
        menu.put("alerts", "Alert outbox delivery counts");
        menu.put("event", "Delivery status of one alert: ID");
        menu.put("test_alert", "Queue a connection-test message");
        menu.put("last_test", "Latest connection test result");
        menu.put("backtest", "Replay strategy: [SYMBOL] [4h|1d] [BALANCE]");
        menu.put("research", "Train/validate/test: [SYMBOL] [START] [END] [BALANCE]");
        menu.put("paper", "Paper balance and realized P&L");
        menu.put("positions", "List paper positions: [open|closed|all]");
        menu.put("position", "Paper position details: ID");
        menu.put("open", "Preview a paper entry: SYMBOL ENTRY STOP TARGET EMOTION THESIS");
        menu.put("close", "Preview a paper exit: ID PRICE REASON REFLECTION");
        menu.put("confirm", "Record the pending paper entry or exit");
        menu.put("cancel", "Discard the pending paper entry or exit");
        return Collections.unmodifiableMap(menu);
    }

    private static String arg(List<String> args, int index, String fallback) {
        return args.size() > index ? args.get(index) : fallback;
    }

    private static String required(List<String> args, int index, String usage) {
        if (args.size() <= index) throw new IllegalArgumentException("Usage: " + usage);
        return args.get(index);
    }

    private static String symbol(String raw) {
        String s = raw.toUpperCase(Locale.ROOT);
        return s.endsWith("USDT") ? s : s + "USDT";
    }

    private static double number(String raw, double fallback, String name) {
        if (raw == null) return fallback;
        try {
            double value = Double.parseDouble(raw.replace(",", ""));
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException ignored) {
            // fall through to the user-facing message
        }
        throw new IllegalArgumentException(name + " must be a number, got: " + raw);
    }

    private static String time(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(CAMBODIA).format(TIME);
    }

    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }
    private static String money(double v) { return String.format(Locale.ROOT, "%,.2f", v); }
    private static String price(double v) { return String.format(Locale.ROOT, "%,.2f", v); }
    private static String qty(double v) { return String.format(Locale.ROOT, "%.6f", v); }
    private static String signed(double v) { return String.format(Locale.ROOT, "%+,.2f", v); }
}
