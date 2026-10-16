package kh.mentor.trading.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class BinanceDailyAccountMonitorTest {
    private JdbcTemplate jdbc;
    private BinanceSpotAccountReader reader;

    static final class MutableClock extends Clock {
        private long epochMillis;
        MutableClock(String timestamp) { epochMillis=Instant.parse(timestamp).toEpochMilli(); }
        void advanceMinutes(long minutes) { epochMillis+=minutes*60_000; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            return Clock.fixed(Instant.ofEpochMilli(epochMillis), zone);
        }
        @Override public Instant instant() { return Instant.ofEpochMilli(epochMillis); }
        @Override public long millis() { return epochMillis; }
    }

    @BeforeEach void setup() throws Exception {
        var db=new DriverManagerDataSource(
            "jdbc:h2:mem:account-digest-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try (Connection conn=db.getConnection()) {
            ScriptUtils.executeSqlScript(conn,new ClassPathResource("schema.sql"));
        }
        jdbc=new JdbcTemplate(db);
        reader=mock(BinanceSpotAccountReader.class);
        when(reader.check()).thenReturn(new BinanceSpotAccountReader.AccountStatus(
            "2026-10-16T02:00:00Z",true,2,true,false,true,
            "SPOT_READ_ONLY","No orders"));
    }

    private String state() {
        return jdbc.queryForObject("SELECT status FROM binance_account_digest",
            String.class);
    }
    private int attempts() {
        return jdbc.queryForObject("SELECT attempts FROM binance_account_digest",
            Integer.class);
    }

    @Test void missed9amOrLateBootCatchesUpOnceSameDay() {
        var clock=new MutableClock("2026-10-16T11:00:00Z"); // 18:00 ICT
        AtomicInteger count=new AtomicInteger();
        var monitor=new BinanceDailyAccountMonitor(reader,jdbc,text->{
            assertTrue(text.contains("Read-only API: VERIFIED"));
            assertTrue(text.contains("No real order"));
            assertFalse(text.contains("0.002"));
            return 100L+count.incrementAndGet();
        },clock);
        monitor.checkDaily();
        monitor.checkDaily();
        assertEquals(1,count.get());
        assertEquals("SENT",state());
        assertEquals(1,attempts());
        assertEquals(101L,jdbc.queryForObject(
            "SELECT provider_message_id FROM binance_account_digest",Long.class));
        verify(reader,times(1)).check();
    }

    @Test void before9amDoesNotSendAndLaterPollDoes() {
        var clock=new MutableClock("2026-10-16T01:59:00Z"); // 08:59 ICT
        AtomicInteger count=new AtomicInteger();
        var monitor=new BinanceDailyAccountMonitor(reader,jdbc,text->{
            count.incrementAndGet();
            return 122;
        },clock);
        monitor.checkDaily();
        assertEquals(0,count.get());
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(*) FROM binance_account_digest",Integer.class));
        clock.advanceMinutes(1);
        monitor.checkDaily();
        assertEquals(1,count.get());
        assertEquals("SENT",state());
    }

    @Test void providerFailureGetsBoundedRetriesWithoutFalseSentState() {
        var clock=new MutableClock("2026-10-16T02:00:00Z");
        AtomicInteger calls=new AtomicInteger();
        var monitor=new BinanceDailyAccountMonitor(reader,jdbc,text->{
            calls.incrementAndGet();
            throw new IllegalStateException("Private channel unavailable");
        },clock);
        monitor.checkDaily();
        assertEquals("PENDING",state());
        assertEquals(1,attempts());
        clock.advanceMinutes(16);
        monitor.checkDaily();
        assertEquals("PENDING",state());
        assertEquals(2,attempts());
        clock.advanceMinutes(16);
        monitor.checkDaily();
        assertEquals("FAILED",state());
        assertEquals(3,attempts());
        clock.advanceMinutes(20);
        monitor.checkDaily();
        assertEquals(3,calls.get());
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(*) FROM binance_account_digest WHERE status='SENT'",
            Integer.class));
    }

    @Test void expiredSendingLeaseIsRecoveredWithoutDeletingHistory() {
        var clock=new MutableClock("2026-10-16T03:00:00Z");
        jdbc.update("""
            INSERT INTO binance_account_digest
              (report_date,status,attempts,next_attempt_ms,lease_until_ms,created_at_ms)
            VALUES (?,'SENDING',1,0,?,?)
            ""","2026-10-16",clock.millis()-1,clock.millis()-1000);
        AtomicInteger count=new AtomicInteger();
        var monitor=new BinanceDailyAccountMonitor(reader,jdbc,text->{
            count.incrementAndGet();
            return 433;
        },clock);
        monitor.checkDaily();
        assertEquals(1,count.get());
        assertEquals("SENT",state());
        assertEquals(2,attempts());
    }

    @Test void currentUnexpiredLeaseBlocksDoubleSend() {
        var clock=new MutableClock("2026-10-16T03:00:00Z");
        jdbc.update("""
            INSERT INTO binance_account_digest
              (report_date,status,attempts,next_attempt_ms,lease_until_ms,created_at_ms)
            VALUES (?,'SENDING',1,0,?,?)
            ""","2026-10-16",clock.millis()+120_000,clock.millis());
        var monitor=new BinanceDailyAccountMonitor(reader,jdbc,text->{
            fail("Must not send while another sender holds the lease");
            return 1;
        },clock);
        monitor.checkDaily();
        verifyNoInteractions(reader);
        assertEquals("SENDING",state());
    }
}
