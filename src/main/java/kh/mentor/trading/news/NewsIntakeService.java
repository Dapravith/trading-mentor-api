package kh.mentor.trading.news;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kh.mentor.trading.alerts.AlertFormatter;
import kh.mentor.trading.alerts.AlertRepository;
import org.springframework.stereotype.Service;

/**
 * Validates a Hermes-researched news candidate before deduplicated Telegram dispatch.
 * Host/metadata validation is NOT equivalent to checking the article actually
 * supports the headline: model-produced research remains explicitly labeled.
 */
@Service
public class NewsIntakeService {
    private static final Set<String> ASSETS = Set.of("BTC","ETH","SOL","BNB","XRP");
    private static final Set<String> IMPACTS = Set.of("BULLISH","BEARISH","UNCERTAIN");
    private static final Set<String> DOMAINS = Set.of(
        "reuters.com","apnews.com","bloomberg.com","coindesk.com",
        "imf.org","federalreserve.gov","bls.gov","sec.gov","cftc.gov",
        "ecb.europa.eu","esma.europa.eu","binance.com","bis.org");
    private static final Set<String> PRIMARY_DOMAINS = Set.of(
        "imf.org","federalreserve.gov","bls.gov","sec.gov","cftc.gov",
        "ecb.europa.eu","esma.europa.eu","binance.com","bis.org");
    private static final Map<String,String> IMPACT_LABELS = Map.of(
        "BULLISH","🟢 Bullish","BEARISH","🔴 Bearish","UNCERTAIN","⚪ Uncertain");

    private final AlertRepository outbox;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public NewsIntakeService(AlertRepository outbox) {
        this(outbox, Clock.systemUTC());
    }
    NewsIntakeService(AlertRepository outbox, Clock clock) {
        this.outbox=outbox;
        this.clock=clock;
    }

    public Result ingest(NewsCandidate candidate) {
        if(candidate==null || candidate.eventKey()==null
            || !candidate.eventKey().matches("[a-z0-9][a-z0-9-]{8,119}")
            || !validText(candidate.headline(),12,180)
            || !validText(candidate.summary(),30,650)
            || !validText(candidate.risk(),15,300)
            || !IMPACTS.contains(candidate.impact())
            || candidate.assets()==null || candidate.assets().isEmpty()
            || candidate.assets().size()>5
            || !ASSETS.containsAll(candidate.assets())
            || candidate.assets().stream().distinct().count()!=candidate.assets().size()) {
            throw new IllegalArgumentException("Invalid news fields, assets or risk statement");
        }
        Instant event, published;
        try {
            event=Instant.parse(candidate.eventAtUtc());
            published=Instant.parse(candidate.publishedAtUtc());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Both news timestamps must be ISO-8601 UTC Instants",e);
        }
        Instant now=clock.instant();
        if (event.isAfter(now) || published.isAfter(now)
            || event.isBefore(now.minus(Duration.ofDays(3)))
            || published.isBefore(now.minus(Duration.ofDays(3)))
            || published.isBefore(event.minus(Duration.ofHours(24)))) {
            throw new IllegalArgumentException("News event/publication time is future, stale or inconsistent");
        }
        String first=host(candidate.primarySourceUrl());
        String second=null;
        if(candidate.corroboratingSourceUrl()!=null
            && !candidate.corroboratingSourceUrl().isBlank()) {
            second=host(candidate.corroboratingSourceUrl());
            if(first.equals(second) || parentDomain(first).equals(parentDomain(second))) {
                throw new IllegalArgumentException("Corroboration must be from an independent publisher");
            }
        }
        if(second==null && PRIMARY_DOMAINS.stream().noneMatch(d -> matchesHost(first,d))) {
            throw new IllegalArgumentException("Non-primary reports need a second independent source");
        }
        String eventId="hermes-news-v1:"+candidate.eventKey();
        String message="""
            📰 %s

            %s

            %s · %s
            Risk: %s
            🕒 %s (published %s)
            🔗 %s%s
            ⚠️ AI-curated, not fact-checked — not trading advice."""
            .formatted(candidate.headline(),candidate.summary(),
                IMPACT_LABELS.get(candidate.impact()),String.join(", ",candidate.assets()),
                candidate.risk(),AlertFormatter.time(event.toEpochMilli()),
                AlertFormatter.time(published.toEpochMilli()),candidate.primarySourceUrl(),
                second==null ? "" : "\n🔗 "+candidate.corroboratingSourceUrl());
        boolean created=outbox.enqueueNews(eventId,event.toEpochMilli(),message);
        return new Result(eventId,created?"QUEUED":"DUPLICATE",created);
    }

    public record Result(String eventId,String status,boolean queued) {}

    private static boolean validText(String s,int min,int max) {
        return s!=null && s.length()>=min && s.length()<=max
            && s.chars().noneMatch(ch -> ch < 32 || ch == 127);
    }

    private static String host(String source) {
        if(source==null || source.length()>512) {
            throw new IllegalArgumentException("Source URL missing or too long");
        }
        try {
            URI uri=URI.create(source);
            if(!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost()==null || uri.getRawUserInfo()!=null
                || uri.getFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)
                || uri.getRawPath()==null || uri.getRawPath().length()<2) {
                throw new IllegalArgumentException("Use HTTPS article URL with no credentials");
            }
            String host=uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if(DOMAINS.stream().noneMatch(d -> matchesHost(host,d))) {
                throw new IllegalArgumentException("News source host not in approved publisher list");
            }
            return host;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("News source must be a reputable HTTPS article URL",e);
        }
    }

    private static boolean matchesHost(String host,String domain) {
        return host.equals(domain) || host.endsWith("."+domain);
    }
    private static String parentDomain(String host) {
        return DOMAINS.stream().filter(d -> matchesHost(host,d))
            .findFirst().orElse(host);
    }
}
