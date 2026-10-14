package kh.mentor.trading.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import kh.mentor.trading.alerts.AlertManualRetryService;
import kh.mentor.trading.alerts.FailedAlert;
import org.junit.jupiter.api.Test;

class AlertManualRetryControllerTest {
    private static final String KEY="generated-test-operator-secret-abcdefghijklmnopqrstuvwxyz";
    private final AlertManualRetryService service=mock(AlertManualRetryService.class);
    private final AlertManualRetryController controller=new AlertManualRetryController(service,KEY);

    @Test void wrongOrMissingSecretIsDeniedBeforeQueryOrReplay() {
        assertEquals(401,controller.failed(null,20).getStatusCode().value());
        assertEquals(401,controller.retry("wrong","connectivity-v1:any",
            new AlertManualRetryController.RetryRequest("Fixed error")).getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void operatorCanInspectAndRequeueOnlyFailed() {
        when(service.failed(20)).thenReturn(List.of(new FailedAlert(
            "connectivity-v1:abc","connectivity-v1",2,"NETWORK_ERROR",123L)));
        assertEquals(200,controller.failed(KEY,20).getStatusCode().value());
        when(service.retry("connectivity-v1:abc","Verified network fixed"))
            .thenReturn(new AlertManualRetryService.RetryResult(
                "connectivity-v1:abc","QUEUED","FAILED"));
        assertEquals(202,controller.retry(KEY,"connectivity-v1:abc",
            new AlertManualRetryController.RetryRequest("Verified network fixed"))
            .getStatusCode().value());
    }

    @Test void previouslySentCannotBeRequeued() {
        when(service.retry("test:s123","Inspected provider ACK and not retryable"))
            .thenReturn(new AlertManualRetryService.RetryResult(
                "test:s123","NOT_RETRYABLE","SENT"));
        assertEquals(409,controller.retry(KEY,"test:s123",
            new AlertManualRetryController.RetryRequest(
                "Inspected provider ACK and not retryable")).getStatusCode().value());
    }

    @Test void refusesPlaceholderCredential() {
        assertThrows(IllegalStateException.class,()->
            new AlertManualRetryController(service,
                "REPLACE_WITH_RANDOM_SECRET_012345678901234567890"));
    }
}
