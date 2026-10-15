package kh.mentor.trading.cloud;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import kh.mentor.trading.strategy.MarketAnalysis;
import kh.mentor.trading.strategy.ScanService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * One-shot read-only scanner used by GitHub Actions. Uses the SAME strategy
 * and risk planner as the local Spring Boot application; no exchange keys,
 * orders, Telegram credentials, or direct notifications are involved here.
 *
 * Atomic output ensures a failed BTC/ETH fetch cannot emit a partial signal.
 */
@Component
@ConditionalOnProperty(prefix="trading.cloud-monitor",name="enabled",havingValue="true")
public class CloudMarketSnapshotRunner implements ApplicationRunner {
    private final ScanService scanner;
    private final JsonMapper mapper;
    private final Path destination;
    private final double balance;

    public CloudMarketSnapshotRunner(ScanService scanner, JsonMapper mapper,
        @Value("${trading.cloud-monitor.output:build/cloud-monitor/snapshot.json}") String output,
        @Value("${trading.paper-balance-usdt:1000}") double balance) {
        this.scanner=scanner;
        this.mapper=mapper;
        this.destination=Path.of(output).toAbsolutePath().normalize();
        this.balance=balance;
    }

    @Override
    public void run(ApplicationArguments ignored) throws Exception {
        // Fail closed. No file is written unless BOTH scans validate.
        MarketAnalysis bitcoin=scanner.scan("BTCUSDT","4h",balance);
        MarketAnalysis ether=scanner.scan("ETHUSDT","4h",balance);
        Snapshot snapshot=new Snapshot(1, Instant.now().toString(),
            "breakout-v1",List.of(bitcoin,ether));
        Path parent=destination.getParent();
        Files.createDirectories(parent);
        Path temp=Files.createTempFile(parent, "scan-", ".json.tmp");
        try {
            Files.writeString(temp,mapper.writeValueAsString(snapshot),StandardCharsets.UTF_8);
            Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public record Snapshot(int schema, String generatedAtUtc, String strategyVersion,
        List<MarketAnalysis> analyses) {}
}
