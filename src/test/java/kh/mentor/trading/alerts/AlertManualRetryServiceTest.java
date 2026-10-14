package kh.mentor.trading.alerts;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class AlertManualRetryServiceTest {
    private AlertRepository repository;
    private AlertManualRetryService service;
    private JdbcTemplate jdbc;

    @BeforeEach void setup() throws Exception {
        var datasource=new DriverManagerDataSource(
            "jdbc:h2:mem:manual-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try(Connection connection=datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("schema.sql"));
        }
        jdbc=new JdbcTemplate(datasource);
        repository=new AlertRepository(jdbc);
        service=new AlertManualRetryService(jdbc,new DataSourceTransactionManager(datasource));
    }

    private String failedEvent() {
        String id=repository.enqueueConnectivityTest();
        var claim=repository.claim(System.currentTimeMillis()+1000,90_000L,5);
        assertEquals(id,claim.id());
        assertTrue(repository.markFailed(claim,System.currentTimeMillis(),5,
            true,"TELEGRAM_REJECTED"));
        return id;
    }

    @Test void inspectFailedWithoutExposingPayload() {
        String id=failedEvent();
        var failed=service.failed(20);
        assertEquals(1,failed.size());
        assertEquals(id,failed.getFirst().eventId());
        assertEquals("TELEGRAM_REJECTED",failed.getFirst().errorCode());
        assertEquals("connectivity-v1",failed.getFirst().strategyVersion());
        assertEquals(1,failed.getFirst().attempts());
        assertFalse(failed.getFirst().toString().contains("bot token"));
    }

    @Test void explicitRetryRestoresPendingAndWritesImmutableAudit() {
        String id=failedEvent();
        var result=service.retry(id,"Channel administrator permission has been fixed");
        assertEquals("QUEUED",result.status());
        assertEquals("PENDING",repository.eventStatus(id).status());
        assertEquals(0,repository.eventStatus(id).attempts());
        assertEquals(1,service.failed(20).size()-1+1==1?0:0); // no FAILED events remain
        assertTrue(service.failed(20).isEmpty());
        assertEquals(1,jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_retry_audit WHERE alert_id=?",Integer.class,id));
        assertEquals("TELEGRAM_REJECTED",jdbc.queryForObject(
            "SELECT prior_error_code FROM alert_retry_audit WHERE alert_id=?",
            String.class,id));
        var claim=repository.claim(System.currentTimeMillis()+1000,90_000L,5);
        assertNotNull(claim);
        assertTrue(repository.markSent(claim,System.currentTimeMillis(),555));
        assertEquals("SENT",repository.eventStatus(id).status());
        assertEquals(555L,repository.eventStatus(id).telegramMessageId());
        assertEquals("NOT_RETRYABLE",service.retry(id,
            "Cannot requeue successfully acknowledged messages").status());
    }

    @Test void retryDoesNotReplayAlreadySentOrMissingItems() {
        String id=repository.enqueueConnectivityTest();
        var claim=repository.claim(System.currentTimeMillis()+1000,90_000L,5);
        repository.markSent(claim,System.currentTimeMillis(),42);
        assertEquals("NOT_RETRYABLE",service.retry(id,
            "Attempt to replay already acknowledged event").status());
        assertEquals("NOT_FOUND",service.retry("connectivity-v1:absent",
            "Operator reviewed failed delivery history").status());
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_retry_audit",Integer.class));
    }

    @Test void aSecondRetryWithoutAnotherFailureIsRejected() {
        String id=failedEvent();
        assertEquals("QUEUED",service.retry(id,"Fixed invalid Telegram group permissions").status());
        assertEquals("NOT_RETRYABLE",service.retry(id,
            "Trying again without a new delivery failure").status());
        assertEquals(1,jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_retry_audit",Integer.class));
    }

    @Test void disallowBulkRangesInvalidReasonAndBadLimits() {
        assertThrows(IllegalArgumentException.class,()->service.failed(101));
        assertThrows(IllegalArgumentException.class,()->service.failed(0));
        assertThrows(IllegalArgumentException.class,()->service.retry(
            "invalid?event","Operator verified connection"));
        assertThrows(IllegalArgumentException.class,()->service.retry(
            "connectivity-v1:ok","short"));
        assertThrows(IllegalArgumentException.class,()->service.retry(
            "connectivity-v1:ok","bad\nnewline text"));
    }
}
