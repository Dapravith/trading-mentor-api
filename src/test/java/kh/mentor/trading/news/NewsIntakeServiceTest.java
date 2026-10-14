package kh.mentor.trading.news;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import kh.mentor.trading.alerts.AlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NewsIntakeServiceTest {
    private AlertRepository outbox;
    private NewsIntakeService service;
    private static final Instant NOW=Instant.parse("2026-10-14T12:00:00Z");

    @BeforeEach void setup() {
        outbox=mock(AlertRepository.class);
        service=new NewsIntakeService(outbox,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    private NewsCandidate candidate(String first,String second) {
        return new NewsCandidate(
            "2026-10-14-global-macro-risk-announcement",
            "Important central bank or macroeconomic update",
            "A consequential economic update was published. The learner should read the original source before relying on this summary.",
            "2026-10-14T10:00:00Z",
            "2026-10-14T11:00:00Z",
            "UNCERTAIN",List.of("BTC","ETH"),
            "Price volatility may increase; no trade is justified without a separate strategy check.",
            first,second);
    }

    @Test void deduplicatesSameEventKeyAcrossResubmissions() {
        NewsCandidate input=candidate("https://www.reuters.com/world/test-report",
            "https://www.imf.org/en/News/Articles/test");
        when(outbox.enqueueNews(anyString(),anyLong(),anyString())).thenReturn(true,false);
        var first=service.ingest(input);
        var second=service.ingest(input);
        assertEquals("QUEUED",first.status());
        assertEquals("DUPLICATE",second.status());
        assertEquals(first.eventId(),second.eventId());
        assertEquals("hermes-news-v1:2026-10-14-global-macro-risk-announcement",first.eventId());
        verify(outbox,times(2)).enqueueNews(eq(first.eventId()),anyLong(),
            argThat(message->message.startsWith("📰 ")
                && message.contains("⚪ Uncertain · BTC, ETH")
                && message.contains(" ICT (published ")
                && message.contains("🔗 https://www.reuters.com/world/test-report\n🔗 https://www.imf.org/")
                && message.endsWith("AI-curated, not fact-checked — not trading advice.")));
    }

    @Test void authoritativePrimaryPublicationNeedsNoSecondLink() {
        when(outbox.enqueueNews(anyString(),anyLong(),anyString())).thenReturn(true);
        var result=service.ingest(candidate(
            "https://www.bls.gov/news.release/cpi.nr0.htm",null));
        assertTrue(result.queued());
    }

    @Test void aMediaReportAloneIsNotSufficient() {
        assertThrows(IllegalArgumentException.class,()->
            service.ingest(candidate("https://www.reuters.com/world/report",null)));
        verifyNoInteractions(outbox);
    }

    @Test void rejectsUntrustedOrSamePublisherOrHttpSources() {
        assertThrows(IllegalArgumentException.class,()->
            service.ingest(candidate("https://fake-finance.example/story",
                "https://www.reuters.com/world/report")));
        assertThrows(IllegalArgumentException.class,()->
            service.ingest(candidate("https://www.reuters.com/world/a",
                "https://graphics.reuters.com/world/b")));
        assertThrows(IllegalArgumentException.class,()->
            service.ingest(candidate("http://www.reuters.com/world/a",
                "https://www.imf.org/en/news/a")));
        verifyNoInteractions(outbox);
    }

    @Test void rejectsFutureAndOldEvents() {
        NewsCandidate fresh=candidate("https://www.bls.gov/news.release/cpi.nr0.htm",null);
        var future=new NewsCandidate(fresh.eventKey(),fresh.headline(),fresh.summary(),
            "2026-10-15T10:00:00Z",fresh.publishedAtUtc(),
            fresh.impact(),fresh.assets(),fresh.risk(),fresh.primarySourceUrl(),null);
        var stale=new NewsCandidate(fresh.eventKey(),fresh.headline(),fresh.summary(),
            "2026-10-01T10:00:00Z","2026-10-01T11:00:00Z",
            fresh.impact(),fresh.assets(),fresh.risk(),fresh.primarySourceUrl(),null);
        assertThrows(IllegalArgumentException.class,()->service.ingest(future));
        assertThrows(IllegalArgumentException.class,()->service.ingest(stale));
        verifyNoInteractions(outbox);
    }

    @Test void rejectsUnapprovedAssetsAndInvalidImpact() {
        NewsCandidate fresh=candidate("https://www.bls.gov/news.release/cpi.nr0.htm",null);
        var invalid=new NewsCandidate(fresh.eventKey(),fresh.headline(),fresh.summary(),
            fresh.eventAtUtc(),fresh.publishedAtUtc(),
            "BUY_NOW",List.of("BTC","MEMECOIN"),fresh.risk(),fresh.primarySourceUrl(),null);
        assertThrows(IllegalArgumentException.class,()->service.ingest(invalid));
        verifyNoInteractions(outbox);
    }
}
