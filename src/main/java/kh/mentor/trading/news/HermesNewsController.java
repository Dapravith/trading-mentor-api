package kh.mentor.trading.news;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Local-only, shared-secret-gated AI news candidate intake.
 * Never forwards a raw Hermes prompt or unvalidated URL.
 */
@RestController
@RequestMapping("/api/v1/news")
@ConditionalOnProperty(prefix="trading.news",name="intake-enabled",havingValue="true")
public class HermesNewsController {
    private final NewsIntakeService service;
    private final byte[] key;
    public HermesNewsController(NewsIntakeService service,
        @Value("${trading.news.ingest-key:}") String configuredKey) {
        if(configuredKey.length()<32 || configuredKey.startsWith("REPLACE") || configuredKey.startsWith("your-") || configuredKey.isBlank()) throw new IllegalStateException(
            "HERMES_NEWS_INGEST_KEY must contain at least 32 characters when intake is enabled");
        this.service=service;
        this.key=configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/candidates")
    public ResponseEntity<Map<String,Object>> candidate(
        @RequestHeader(value="X-News-Intake-Key",required=false) String receivedKey,
        @RequestBody NewsCandidate input) {
        if(receivedKey==null || !MessageDigest.isEqual(
            key,receivedKey.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of(
                "error","unauthorized","message","Invalid news intake credential"));
        }
        NewsIntakeService.Result result=service.ingest(input);
        return ResponseEntity.status(result.queued()?202:200).body(
            Map.of("eventId",result.eventId(),"status",result.status(),
                "mode","AI_CURATED_RESEARCH_ONLY"));
    }
}
