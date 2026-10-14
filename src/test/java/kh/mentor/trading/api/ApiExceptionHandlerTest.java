package kh.mentor.trading.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler=new ApiExceptionHandler();

    @Test void missingRouteReturns404NotMisleading500() {
        var result=handler.notFound(mock(NoResourceFoundException.class));
        assertEquals(HttpStatus.NOT_FOUND,result.getStatusCode());
        assertEquals("not_found",result.getBody().get("error"));
    }

    @Test void databaseFailureReturnsSafe503WithSqlState() {
        var failure=new DataAccessResourceFailureException(
            "Sensitive SQL and URL must not be returned",
            new SQLException("No column", "42S22", 42122));
        var result=handler.databaseFailure(failure);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,result.getStatusCode());
        assertEquals("database_unavailable",result.getBody().get("error"));
        assertEquals("42S22",result.getBody().get("sqlState"));
        assertFalse(result.getBody().toString().contains("Sensitive SQL"));
        assertFalse(result.getBody().toString().contains("No column"));
    }

    @Test void unexpectedFailuresRemain500() {
        var result=handler.unexpected(new IllegalStateException("test error"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,result.getStatusCode());
        assertEquals("internal_error",result.getBody().get("error"));
        assertFalse(result.getBody().toString().contains("test error"));
    }
}
