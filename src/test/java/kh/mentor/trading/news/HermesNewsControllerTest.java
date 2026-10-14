package kh.mentor.trading.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class HermesNewsControllerTest {
    private final NewsIntakeService service=mock(NewsIntakeService.class);
    private final HermesNewsController controller=new HermesNewsController(service,
        "secret-for-news-intake-abcdefghijklmnopqrstuvwxyz-12345");

    @Test void unauthorizedSubmissionsNeverReachService() {
        assertEquals(401,controller.candidate(null,null).getStatusCode().value());
        assertEquals(401,controller.candidate("incorrect",null).getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void successfulAndDuplicateResponsesAreDistinguished() {
        NewsCandidate candidate=new NewsCandidate("some-long-event-key","headline",
            "summary","2026-10-14T10:00:00Z","2026-10-14T11:00:00Z",
            "UNCERTAIN",List.of("BTC"),"risk","https://www.bls.gov/foo",null);
        when(service.ingest(candidate))
            .thenReturn(new NewsIntakeService.Result("event1","QUEUED",true))
            .thenReturn(new NewsIntakeService.Result("event1","DUPLICATE",false));
        assertEquals(202,controller.candidate(
            "secret-for-news-intake-abcdefghijklmnopqrstuvwxyz-12345",candidate)
            .getStatusCode().value());
        assertEquals(200,controller.candidate(
            "secret-for-news-intake-abcdefghijklmnopqrstuvwxyz-12345",candidate)
            .getStatusCode().value());
    }
}
