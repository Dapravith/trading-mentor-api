package kh.mentor.trading.api;

import java.sql.SQLException;
import java.util.Map;
import kh.mentor.trading.market.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Consistent, secret-safe API errors. Never turn an unknown route (404)
 * into a server failure (500): doing so obscures stale deployments.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String,String>> notFound(Exception ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "error","not_found",
            "message","API route not found. Confirm your Git branch and restart the running backend."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
            "error","invalid_request","message",e.getMessage()));
    }

    @ExceptionHandler(MarketDataException.class)
    public ResponseEntity<Map<String,String>> marketUnavailable(MarketDataException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "error","market_data_unavailable","message",e.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String,String>> databaseFailure(DataAccessException e) {
        SQLException sql=null;
        for(Throwable cursor=e;cursor!=null;cursor=cursor.getCause()) {
            if(cursor instanceof SQLException found) {
                sql=found;
                break;
            }
        }
        String state=sql==null || sql.getSQLState()==null ? "UNKNOWN" : sql.getSQLState();
        int code=sql==null ? 0 : sql.getErrorCode();
        // Never log raw SQL, exception message, auth headers or credentials.
        log.error("api_database_failed category={} sqlState={} vendorCode={}",
            e.getClass().getSimpleName(),state,code);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "error","database_unavailable",
            "message","Unable to complete database operation. Check sanitized server logs.",
            "sqlState",state));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> unexpected(Exception e) {
        // The former handler silently mapped 404s and all other failures to 500.
        log.error("api_request_unexpected_failure type={}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "error","internal_error","message","Request failed; no trading action was taken"));
    }
}
