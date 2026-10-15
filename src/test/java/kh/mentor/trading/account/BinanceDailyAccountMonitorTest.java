package kh.mentor.trading.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class BinanceDailyAccountMonitorTest {
    @Test void dailyDigestSendsOnlyOnceAndContainsNoBalancesOrOrderInstructions() throws Exception {
        var db=new DriverManagerDataSource(
            "jdbc:h2:mem:account-digest-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try(Connection conn=db.getConnection()) {
            ScriptUtils.executeSqlScript(conn,new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc=new JdbcTemplate(db);
        BinanceSpotAccountReader reader=mock(BinanceSpotAccountReader.class);
        when(reader.check()).thenReturn(new BinanceSpotAccountReader.AccountStatus(
            "2026-10-15T02:00:00Z",true,2,true,false,true,
            "SPOT_READ_ONLY","No orders"));
        AtomicInteger sent=new AtomicInteger();
        var digest=new BinanceDailyAccountMonitor(reader,jdbc,text->{
            assertTrue(text.contains("Read-only API: VERIFIED"));
            assertTrue(text.contains("No real order"));
            assertFalse(text.contains("0.002"));
            return sent.incrementAndGet();
        });
        digest.checkDaily();
        digest.checkDaily();
        assertEquals(1,sent.get());
        verify(reader,times(1)).check();
        assertEquals(1,jdbc.queryForObject(
            "SELECT COUNT(*) FROM binance_account_digest WHERE status='SENT'",
            Integer.class));
    }

    @Test void deliveryFailureNeverRecordsFalseAcknowledgement() throws Exception {
        var db=new DriverManagerDataSource(
            "jdbc:h2:mem:account-failed-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try(Connection conn=db.getConnection()){
            ScriptUtils.executeSqlScript(conn,new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc=new JdbcTemplate(db);
        var reader=mock(BinanceSpotAccountReader.class);
        when(reader.check()).thenReturn(new BinanceSpotAccountReader.AccountStatus(
            "2026-10-15T02:00:00Z",true,0,false,false,false,
            "SPOT_READ_ONLY","No orders"));
        var digest=new BinanceDailyAccountMonitor(reader,jdbc,text->{
            throw new IllegalStateException("Network down");
        });
        digest.checkDaily();
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(*) FROM binance_account_digest",Integer.class));
    }
}
