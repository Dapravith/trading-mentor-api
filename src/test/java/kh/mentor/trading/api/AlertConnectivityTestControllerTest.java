package kh.mentor.trading.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import kh.mentor.trading.alerts.AlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;

class AlertConnectivityTestControllerTest {
    private final AlertRepository repository = mock(AlertRepository.class);
    private final AlertConnectivityTestController controller =
        new AlertConnectivityTestController(repository);

    @Test void queuedTestReturnsAcceptedAndStableEventId() {
        when(repository.enqueueConnectivityTest()).thenReturn(
            "connectivity-v1:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        var response = controller.queueTest();
        assertEquals(202, response.getStatusCode().value());
        assertEquals("QUEUED", response.getBody().get("status"));
        assertEquals("connectivity-v1:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            response.getBody().get("eventId"));
    }

    @Test void sqlFailureReturnsSafeServiceUnavailableWithoutSqlOrSecrets() {
        var failure = new BadSqlGrammarException("INSERT", "INSERT INTO alert_outbox",
            new SQLException("Column missing", "42S22", 42122));
        var response = controller.handleOutboxFailure(failure);
        assertEquals(503, response.getStatusCode().value());
        assertEquals("OUTBOX_DATABASE_UNAVAILABLE", response.getBody().get("errorCode"));
        assertTrue(response.getBody().get("message").contains("42S22"));
        assertFalse(response.getBody().toString().contains("INSERT"));
        assertFalse(response.getBody().toString().contains("Column missing"));
    }

    @Test void unknownDatabaseCauseStillReturnsSafeStatus() {
        var failure = new DataAccessResourceFailureException("No access");
        var response = controller.handleOutboxFailure(failure);
        assertEquals(503, response.getStatusCode().value());
        assertTrue(response.getBody().get("message").contains("UNKNOWN"));
        assertFalse(response.getBody().toString().contains("No access"));
    }
}
