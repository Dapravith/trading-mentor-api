package kh.mentor.trading.api;

import java.util.Map;
import kh.mentor.trading.market.MarketDataException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "message", e.getMessage()));
    }

    @ExceptionHandler(MarketDataException.class)
    public ResponseEntity<Map<String, String>> marketUnavailable(MarketDataException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "error", "market_data_unavailable", "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        // Do not echo details such as API configuration in responses.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "error", "internal_error", "message", "Request failed; no trading action was taken"));
    }
}
