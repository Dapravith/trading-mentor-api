package kh.mentor.trading.market;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Read-only public market data; intentionally contains no order or account APIs. */
@Component
public class BinancePublicClient implements MarketDataProvider, HistoricalMarketDataProvider {
    private static final Set<String> ALLOWED_SYMBOLS = Set.of("BTCUSDT", "ETHUSDT");
    private static final Set<String> ALLOWED_INTERVALS = Set.of("4h", "1d");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper mapper;
    private final String baseUrl;
    private final long maxClockSkewMs;

    public BinancePublicClient(JsonMapper mapper,
        @Value("${trading.market-data-url}") String baseUrl,
        @Value("${trading.max-clock-skew-seconds:120}") long maxClockSkewSeconds) {
        this.mapper = mapper;
        // This is not an arbitrary URL fetcher. Prevent accidental local/internal address access.
        if (!baseUrl.equals("https://data-api.binance.vision")) {
            throw new IllegalArgumentException("Only Binance public market-data host is allowed");
        }
        this.baseUrl = baseUrl;
        this.maxClockSkewMs = maxClockSkewSeconds * 1000;
    }

    @Override
    public List<Candle> fetchClosedCandles(String symbol, String interval, int limit) {
        if (!ALLOWED_SYMBOLS.contains(symbol) || !ALLOWED_INTERVALS.contains(interval) ||
            limit < 65 || limit > 500) {
            throw new IllegalArgumentException("Unsupported symbol, interval, or candle limit");
        }
        long exchangeNow;
        try { exchangeNow = fetchJson("/api/v3/time").path("serverTime").asLong(-1); }
        catch (RuntimeException e) { throw new MarketDataException("Unable to confirm Binance server time", e); }
        if (exchangeNow <= 0 || Math.abs(exchangeNow - Instant.now().toEpochMilli()) > maxClockSkewMs) {
            throw new MarketDataException("Binance time is missing or local clock differs by > 2 minutes");
        }
        String path = "/api/v3/klines?symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
            + "&interval=" + URLEncoder.encode(interval, StandardCharsets.UTF_8) + "&limit=" + limit;
        JsonNode rows = fetchJson(path);
        if (!rows.isArray()) throw new MarketDataException("Unexpected klines response");
        List<Candle> closed = new ArrayList<>();
        long lastOpen = -1;
        long durationMs = interval.equals("4h") ? 14_400_000L : 86_400_000L;
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 7) throw new MarketDataException("Malformed candle row");
            try {
                Candle candle = new Candle(row.get(0).asLong(), row.get(6).asLong(),
                    parseNumber(row.get(1)), parseNumber(row.get(2)), parseNumber(row.get(3)),
                    parseNumber(row.get(4)), parseNumber(row.get(5)));
                if (lastOpen >= 0 && candle.openTimeMs() - lastOpen != durationMs) {
                    throw new MarketDataException("Missing, duplicate or out-of-sequence candles");
                }
                lastOpen = candle.openTimeMs();
                if (candle.closeTimeMs() < exchangeNow) closed.add(candle);
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new MarketDataException("Invalid market candle contents", e);
            }
        }
        if (closed.size() < 60) throw new MarketDataException("Insufficient completed candles");
        long age = exchangeNow - closed.getLast().closeTimeMs();
        if (age < 0 || age > 2 * durationMs) throw new MarketDataException("Stale candle data");
        return List.copyOf(closed);
    }

    /**
     * Paginated, completed UTC DAILY candles for bounded multi-year research.
     * Unlike live scans, an intentionally old history window is not "stale".
     */
    @Override
    public List<Candle> fetchHistoricalDaily(String symbol, long startMs, long endExclusiveMs) {
        long day = 86_400_000L;
        long span = endExclusiveMs - startMs;
        if (!ALLOWED_SYMBOLS.contains(symbol) || startMs <= 0 || endExclusiveMs <= startMs
            || startMs % day != 0 || endExclusiveMs % day != 0
            || span < 180L * day || span > 2_000L * day) {
            throw new IllegalArgumentException("Use supported pair and 180-2000 UTC calendar days");
        }
        long now = fetchJson("/api/v3/time").path("serverTime").asLong(-1);
        if (now <= 0 || Math.abs(now - Instant.now().toEpochMilli()) > maxClockSkewMs
            || endExclusiveMs > now) {
            throw new MarketDataException("Historical window incomplete or Binance clock invalid");
        }
        List<Candle> history = new ArrayList<>();
        long cursor = startMs;
        while (cursor < endExclusiveMs) {
            long batchSize = Math.min(1000L, (endExclusiveMs - cursor) / day);
            String path = "/api/v3/klines?symbol=" + symbol
                + "&interval=1d&startTime=" + cursor
                + "&endTime=" + (endExclusiveMs - 1) + "&limit=" + batchSize;
            JsonNode rows = fetchJson(path);
            if (!rows.isArray() || rows.isEmpty()) {
                throw new MarketDataException("Missing historical Binance candles");
            }
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 7) {
                    throw new MarketDataException("Malformed history candle");
                }
                Candle candle;
                try {
                    candle = new Candle(row.get(0).asLong(-1), row.get(6).asLong(-1),
                        parseNumber(row.get(1)), parseNumber(row.get(2)),
                        parseNumber(row.get(3)), parseNumber(row.get(4)), parseNumber(row.get(5)));
                } catch (IllegalArgumentException e) {
                    throw new MarketDataException("Invalid historical candle", e);
                }
                if (candle.openTimeMs() != cursor || candle.closeTimeMs() != cursor + day - 1
                    || candle.closeTimeMs() >= now) {
                    throw new MarketDataException("Incomplete, missing or out-of-order historical candles");
                }
                history.add(candle);
                cursor += day;
                if (cursor >= endExclusiveMs) break;
            }
            if (history.size() > 2_000) throw new MarketDataException("Historical window too large");
        }
        return List.copyOf(history);
    }

    private static double parseNumber(JsonNode node) {
        if (node == null || node.isNull()) throw new IllegalArgumentException("Missing numeric value");
        // Require quoted decimal values, as specified by Binance, not free-form conversions.
        if (!node.isTextual()) throw new IllegalArgumentException("Expected decimal string");
        return Double.parseDouble(node.asText());
    }

    private JsonNode fetchJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .GET().timeout(REQUEST_TIMEOUT).header("Accept", "application/json")
                .header("User-Agent", "ai-trading-mentor/0.1").build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new MarketDataException("Binance returned HTTP " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new MarketDataException("Binance request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("Binance request interrupted", e);
        }
    }
}
