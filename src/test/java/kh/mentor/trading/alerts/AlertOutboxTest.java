package kh.mentor.trading.alerts;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kh.mentor.trading.risk.RiskPlan;
import kh.mentor.trading.risk.RiskPlanner;
import kh.mentor.trading.strategy.MarketAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class AlertOutboxTest {
    AlertRepository repository;
    JdbcTemplate jdbc;
    MarketAnalysis signal;

    @BeforeEach void setUp() throws Exception {
        var ds = new DriverManagerDataSource(
            "jdbc:h2:mem:alerts-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(ds);
        repository = new AlertRepository(jdbc);
        RiskPlan plan = new RiskPlanner(.005, .20, .001, .001)
            .calculate(1000, 100, 98, 110);
        signal = new MarketAnalysis("BTCUSDT", "4h", 1_700_000_000_000L,
            "2023-11-14T22:13:20Z", 100, 99, 98, 60, 1, 1.5,
            "PAPER_SETUP_WATCH", List.of("Educational"), plan,
            "fixture", "NO REAL ORDERS");
    }

    @Test void testNotificationUsesRealOutboxAcknowledgementPath() {
        String id=repository.enqueueConnectivityTest();
        assertTrue(id.startsWith("connectivity-v1:"));
        assertEquals(1,repository.statusCounts().get("PENDING"));
        ClaimedAlert claim=repository.claim(System.currentTimeMillis()+1000,90_000,5);
        assertNotNull(claim);
        assertEquals(id,claim.id());
        assertTrue(claim.payload().contains("CONNECTION TEST"));
        assertTrue(claim.payload().contains("NOT a BUY/SELL signal"));
        assertTrue(repository.markSent(claim,System.currentTimeMillis(),54321));
        assertEquals(1,repository.statusCounts().get("SENT"));
    }

    @Test void newsEventUsesSameDurableDeliveryOutbox() {
        String id="hermes-news-v1:2026-10-14-global-macro-update";
        assertTrue(repository.enqueueNews(id,System.currentTimeMillis(),
            "AI curated research message (not a trading signal)"));
        assertFalse(repository.enqueueNews(id,System.currentTimeMillis(),
            "AI curated research message (not a trading signal)"));
        var pending=repository.eventStatus(id);
        assertEquals("PENDING",pending.status());
        assertEquals(0,pending.attempts());
        ClaimedAlert claim=repository.claim(System.currentTimeMillis()+1000,90_000,5);
        assertTrue(repository.markSent(claim,System.currentTimeMillis(),789L));
        var sent=repository.eventStatus(id);
        assertEquals("SENT",sent.status());
        assertEquals(789L,sent.telegramMessageId());
    }

    @Test void enqueuesOnceAndPersistsUntilAck() {
        assertTrue(repository.enqueue(signal));
        assertFalse(repository.enqueue(signal));
        assertEquals(1, repository.statusCounts().get("PENDING"));
        ClaimedAlert claim = repository.claim(System.currentTimeMillis()+1000, 90_000, 5);
        assertNotNull(claim);
        assertEquals(1, repository.statusCounts().get("SENDING"));
        assertTrue(repository.markSent(claim, System.currentTimeMillis(), 12345));
        assertEquals(1, repository.statusCounts().get("SENT"));
        assertNull(repository.claim(System.currentTimeMillis()+1000, 90_000, 5));
        assertEquals(12345L, jdbc.queryForObject(
            "SELECT provider_message_id FROM alert_outbox WHERE id=?",
            Long.class, claim.id()));
    }

    @Test void transientFailureRemainsPendingAndEventuallySends() {
        repository.enqueue(signal);
        long now = System.currentTimeMillis()+1000;
        ClaimedAlert first = repository.claim(now, 90_000, 5);
        assertTrue(repository.markFailed(first, now, 5, false, "NETWORK_ERROR"));
        assertNull(repository.claim(now+1000, 90_000, 5));
        ClaimedAlert second = repository.claim(now+31_000, 90_000, 5);
        assertEquals(2, second.attempts());
        assertTrue(repository.markSent(second, now+32_000, 99));
        assertEquals(1, repository.statusCounts().get("SENT"));
    }

    @Test void crashLeaseExpiresAndCanBeReclaimed() {
        repository.enqueue(signal);
        long now=System.currentTimeMillis()+1000;
        ClaimedAlert first=repository.claim(now, 100, 5);
        repository.recoverExpired(now+200, 5);
        ClaimedAlert second=repository.claim(now+201, 100, 5);
        assertNotNull(second);
        assertEquals(2, second.attempts());
        assertFalse(repository.markSent(first, now+205, 10));
        assertTrue(repository.markSent(second, now+205, 11));
    }

    @Test void lastAttemptExhaustionIsTerminal() {
        repository.enqueue(signal);
        long now=System.currentTimeMillis()+1000;
        ClaimedAlert claim=repository.claim(now, 100, 1);
        repository.recoverExpired(now+200, 1);
        assertNull(repository.claim(now+100_000, 100, 1));
        assertEquals(1, repository.statusCounts().get("FAILED"));
        assertFalse(repository.markSent(claim, now+205, 10));
    }

    @Test void terminalTelegramRejectionDoesNotRetry() {
        repository.enqueue(signal);
        long now=System.currentTimeMillis()+1000;
        ClaimedAlert first=repository.claim(now, 90_000, 5);
        assertTrue(repository.markFailed(first, now, 5, true, "TELEGRAM_REJECTED"));
        assertEquals(1, repository.statusCounts().get("FAILED"));
        assertNull(repository.claim(now+100_000, 90_000, 5));
    }

    @Test void dispatcherHandlesFailureThenAcknowledgedSuccess() {
        repository.enqueue(signal);
        AtomicInteger calls = new AtomicInteger();
        TelegramTransport fake = payload -> {
            if (calls.getAndIncrement() == 0) {
                throw new TelegramDeliveryException("NETWORK_ERROR", false);
            }
            return new TelegramTransport.DeliveryReceipt(123L);
        };
        AlertDispatcher dispatcher = new AlertDispatcher(repository, fake, 5);
        assertEquals(1, dispatcher.dispatch(1));
        assertEquals(1, repository.statusCounts().get("PENDING"));
        jdbc.update("UPDATE alert_outbox SET next_attempt_ms = 0 WHERE status='PENDING'");
        assertEquals(1, dispatcher.dispatch(1));
        assertEquals(1, repository.statusCounts().get("SENT"));
        assertEquals(2, calls.get());
    }
}
