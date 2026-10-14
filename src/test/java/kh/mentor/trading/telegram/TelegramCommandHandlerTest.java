package kh.mentor.trading.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import kh.mentor.trading.alerts.AlertRepository;
import kh.mentor.trading.alerts.AlertScanService;
import kh.mentor.trading.backtest.BacktestService;
import kh.mentor.trading.backtest.ChronologicalResearchService;
import kh.mentor.trading.market.MarketDataException;
import kh.mentor.trading.paper.PaperPortfolioService;
import kh.mentor.trading.paper.PaperPosition;
import kh.mentor.trading.risk.RiskPlanner;
import kh.mentor.trading.strategy.MarketAnalysis;
import kh.mentor.trading.strategy.ScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class TelegramCommandHandlerTest {
    static final long CHAT = 42L;
    final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    final List<Long> sleeps = new java.util.ArrayList<>();
    ScanService scans = mock(ScanService.class);
    AlertScanService alertScans = mock(AlertScanService.class);
    AlertRepository alerts;
    PaperPortfolioService paper;
    TelegramCommandHandler handler;

    @BeforeEach void setUp() throws Exception {
        var ds = new DriverManagerDataSource(
            "jdbc:h2:mem:telegram-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        RiskPlanner risk = new RiskPlanner(.005, .20, .001, .001);
        alerts = new AlertRepository(jdbc);
        paper = new PaperPortfolioService(jdbc, risk, .001, .001);
        handler = new TelegramCommandHandler(scans, alertScans, alerts,
            mock(BacktestService.class), mock(ChronologicalResearchService.class),
            paper, risk, "4h", 1000, true, false, now::get, sleeps::add);
    }

    @Test void helpListsEveryMenuCommand() {
        String help = handler.handle(CHAT, "/help");
        for (String command : TelegramCommandHandler.COMMANDS.keySet()) {
            assertTrue(help.contains("/" + command), command);
        }
        assertEquals(help, handler.handle(CHAT, "/start"));
    }

    @Test void openNeedsConfirmationAndConfirmRunsOnce() {
        String preview = handler.handle(CHAT, "/open btc 100 98 110 calm Breakout retest");
        assertTrue(preview.contains("PAPER ENTRY PREVIEW"), preview);
        assertEquals(0, paper.summary().openPositions());

        String confirmed = handler.handle(CHAT, "/confirm");
        assertTrue(confirmed.contains("Paper position recorded"), confirmed);
        PaperPosition position = paper.recent().getFirst();
        assertEquals("BTCUSDT", position.symbol());
        assertEquals("CALM", position.emotion());
        assertEquals("Breakout retest", position.thesis());
        assertEquals(1, paper.summary().openPositions());

        assertTrue(handler.handle(CHAT, "/confirm").startsWith("Nothing to confirm"));
        assertEquals(1, paper.summary().openPositions());
    }

    @Test void invalidOpenDoesNotLeaveAPendingAction() {
        assertTrue(handler.handle(CHAT, "/open BTC 100 98 110 HAPPY x").startsWith("⚠️ EMOTION"));
        assertTrue(handler.handle(CHAT, "/open BTC 100 98").startsWith("⚠️ Usage: /open"));
        assertTrue(handler.handle(CHAT, "/open BTC abc 98 110 CALM x").contains("ENTRY must be a number"));
        String rejected = handler.handle(CHAT, "/open BTC 100 98 101 CALM Weak target");
        assertTrue(rejected.startsWith("❌ Paper entry rejected"), rejected);
        assertTrue(handler.handle(CHAT, "/confirm").startsWith("Nothing to confirm"));
    }

    @Test void expiredAndCancelledPreviewsDoNothing() {
        handler.handle(CHAT, "/open BTC 100 98 110 CALM Thesis");
        now.addAndGet(TelegramCommandHandler.CONFIRM_TTL_MS + 1);
        assertTrue(handler.handle(CHAT, "/confirm").startsWith("⌛"));

        handler.handle(CHAT, "/open BTC 100 98 110 CALM Thesis");
        assertTrue(handler.handle(CHAT, "/cancel").contains("discarded"));
        assertTrue(handler.handle(CHAT, "/confirm").startsWith("Nothing to confirm"));
        assertEquals(0, paper.summary().openPositions());
    }

    @Test void pendingActionsAreIsolatedPerChat() {
        handler.handle(CHAT, "/open BTC 100 98 110 CALM Thesis");
        assertTrue(handler.handle(7L, "/confirm").startsWith("Nothing to confirm"));
        assertEquals(0, paper.summary().openPositions());
    }

    @Test void closeByShortIdAfterConfirmation() {
        handler.handle(CHAT, "/open ETH 100 98 110 FOMO Momentum");
        handler.handle(CHAT, "/confirm");
        String shortId = paper.recent().getFirst().id().substring(0, 8);
        assertTrue(handler.handle(CHAT, "/positions open").contains(shortId));

        String preview = handler.handle(CHAT, "/close " + shortId + " 110 target Followed plan");
        assertTrue(preview.contains("PAPER EXIT PREVIEW"), preview);
        assertEquals(1, paper.summary().openPositions());

        String closed = handler.handle(CHAT, "/confirm");
        assertTrue(closed.contains("Paper position closed"), closed);
        PaperPosition position = paper.recent().getFirst();
        assertEquals("CLOSED", position.status());
        assertEquals("TARGET", position.exitReason());
        assertTrue(position.netPnlUsdt() > 0);
        assertTrue(handler.handle(CHAT, "/close " + shortId + " 110 TARGET again")
            .contains("already closed"));
        assertTrue(handler.handle(CHAT, "/position zzzz").contains("No paper position matches"));
    }

    @Test void scanNormalizesSymbolAndStripsBotSuffix() {
        when(scans.scan(anyString(), anyString(), anyDouble())).thenReturn(new MarketAnalysis(
            "BTCUSDT", "1d", 1_700_000_000_000L, "2023-11-14T22:13:20Z", 100, 99, 98, 55, 1,
            1.2, "NO_TRADE", List.of("Trend filter not met"), null, "fixture", "NO REAL ORDERS"));
        String reply = handler.handle(CHAT, "/scan@MentorBot btc 1D 500");
        verify(scans).scan("BTCUSDT", "1d", 500);
        assertTrue(reply.contains("BTCUSDT 1d — NO_TRADE"), reply);
        assertTrue(reply.contains("Trend filter not met"), reply);
    }

    @Test void serviceValidationErrorsBecomeWarnings() {
        when(scans.scan(anyString(), anyString(), anyDouble()))
            .thenThrow(new IllegalArgumentException("Unsupported symbols, interval, or paper balance"));
        assertEquals("⚠️ Unsupported symbols, interval, or paper balance",
            handler.handle(CHAT, "/scan DOGE"));
        when(alertScans.scanAndQueue()).thenThrow(new IllegalStateException("boom"));
        assertEquals("⚠️ Command failed; no trading action was taken.",
            handler.handle(CHAT, "/queue_scan"));
    }

    @Test void alertCommandsReadTheOutbox() {
        assertTrue(handler.handle(CHAT, "/last_test").startsWith("No connection test"));
        assertTrue(handler.handle(CHAT, "/test_alert").contains("Queued connection test"));
        assertTrue(handler.handle(CHAT, "/alerts").contains("PENDING 1"));
        assertTrue(handler.handle(CHAT, "/last_test").contains("Status: PENDING"));
        assertTrue(handler.handle(CHAT, "/event").startsWith("⚠️ Usage: /event"));
    }

    static MarketAnalysis analysis() {
        return new MarketAnalysis("BTCUSDT", "4h", 1_700_000_000_000L, "2023-11-14T22:13:20Z",
            100, 99, 98, 55, 1, 1.2, "NO_TRADE", List.of(), null, "fixture", "NO REAL ORDERS");
    }

    @Test void transientMarketFailureIsRetriedThenAnswered() {
        when(scans.scan(anyString(), anyString(), anyDouble()))
            .thenThrow(new MarketDataException("Binance request failed"))
            .thenThrow(new MarketDataException("Binance request failed"))
            .thenReturn(analysis());
        String reply = handler.handle(CHAT, "/scan");
        assertTrue(reply.contains("BTCUSDT 4h — NO_TRADE"), reply);
        verify(scans, times(3)).scan("BTCUSDT", "4h", 1000);
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(0) >= 500 && sleeps.get(0) <= 1_000, sleeps.toString());
        assertTrue(sleeps.get(1) >= 1_000 && sleeps.get(1) <= 2_000, sleeps.toString());
    }

    @Test void marketRetriesAreBoundedAndReported() {
        when(scans.scan(anyString(), anyString(), anyDouble()))
            .thenThrow(new MarketDataException("Binance returned HTTP 503"));
        assertEquals("⚠️ Market data unavailable: Binance returned HTTP 503",
            handler.handle(CHAT, "/scan eth"));
        verify(scans, times(TelegramCommandHandler.MARKET_ATTEMPTS)).scan("ETHUSDT", "4h", 1000);
    }

    @Test void validationErrorsAreNotRetried() {
        when(scans.scan(anyString(), anyString(), anyDouble()))
            .thenThrow(new IllegalArgumentException("Unsupported symbols, interval, or paper balance"));
        handler.handle(CHAT, "/scan DOGE");
        verify(scans, times(1)).scan(anyString(), anyString(), anyDouble());
        assertTrue(sleeps.isEmpty());
    }

    @Test void onlyPaperLedgerCommandsAreSerialized() {
        for (String text : List.of("/open BTC 1 2 3", "/CLOSE x", "/confirm", "/cancel@MentorBot")) {
            assertTrue(TelegramCommandHandler.mutatesLedger(text), text);
        }
        for (String text : List.of("/scan", "/research", "/paper", "/positions", "", "open")) {
            assertFalse(TelegramCommandHandler.mutatesLedger(text), text);
        }
    }

    @Test void unknownInputPointsToHelp() {
        assertTrue(handler.handle(CHAT, "/nope").contains("/help"));
        assertTrue(handler.handle(CHAT, "hello").contains("/help"));
        assertTrue(handler.handle(CHAT, "").contains("/help"));
    }
}
