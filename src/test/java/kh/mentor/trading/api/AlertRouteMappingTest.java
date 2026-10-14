package kh.mentor.trading.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import kh.mentor.trading.alerts.AlertRepository;
import kh.mentor.trading.alerts.AlertScanService;
import kh.mentor.trading.alerts.OutboxEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AlertRouteMappingTest {
    @Test void latestTestRouteIsRegisteredAndReturnsSavedAck() throws Exception {
        var repo=mock(AlertRepository.class);
        var scan=mock(AlertScanService.class);
        when(repo.latestConnectivityTest()).thenReturn(new OutboxEventStatus(
            "connectivity-v1:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "SENT",1,1791968637468L,12345L,null));
        var mvc=MockMvcBuilders.standaloneSetup(new AlertController(scan,repo))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/alerts/test/latest"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SENT"))
            .andExpect(jsonPath("$.telegramMessageId").value(12345));
        verify(repo).latestConnectivityTest();
    }

    @Test void latestTestRouteReturns204WhenNoPriorTest() throws Exception {
        var repo=mock(AlertRepository.class);
        var mvc=MockMvcBuilders.standaloneSetup(
            new AlertController(mock(AlertScanService.class),repo))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/alerts/test/latest"))
            .andExpect(status().isNoContent());
    }
}
