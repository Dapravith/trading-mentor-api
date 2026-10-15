package kh.mentor.trading.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kh.mentor.trading.strategy.MarketAnalysis;
import kh.mentor.trading.strategy.ScanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.json.JsonMapper;

class CloudMarketSnapshotRunnerTest {
    @TempDir Path dir;

    private MarketAnalysis analysis(String symbol) {
        return new MarketAnalysis(symbol,"4h",1792051199999L,
            "2026-10-15T15:59:59Z",100,95,90,55,3,1.2,"NO_TRADE",
            List.of("No break above prior high"),null,"public candles","Paper research");
    }

    @Test void emitsExactlyBothMarketsToSnapshot() throws Exception {
        var scanner=mock(ScanService.class);
        when(scanner.scan("BTCUSDT","4h",1000)).thenReturn(analysis("BTCUSDT"));
        when(scanner.scan("ETHUSDT","4h",1000)).thenReturn(analysis("ETHUSDT"));
        Path output=dir.resolve("a/snapshot.json");
        var runner=new CloudMarketSnapshotRunner(scanner,new JsonMapper(),output.toString(),1000);
        runner.run(new DefaultApplicationArguments());
        var json=new JsonMapper().readTree(Files.readString(output));
        assertEquals(1,json.path("schema").asInt());
        assertEquals("breakout-v1",json.path("strategyVersion").asText());
        assertEquals(2,json.path("analyses").size());
        assertEquals("BTCUSDT",json.path("analyses").get(0).path("symbol").asText());
        assertEquals("ETHUSDT",json.path("analyses").get(1).path("symbol").asText());
        verify(scanner,times(2)).scan(anyString(),eq("4h"),eq(1000.0));
    }

    @Test void neverLeavesPartialSnapshotIfOneSymbolFails() {
        var scanner=mock(ScanService.class);
        when(scanner.scan("BTCUSDT","4h",1000)).thenReturn(analysis("BTCUSDT"));
        when(scanner.scan("ETHUSDT","4h",1000)).thenThrow(new RuntimeException("Market unavailable"));
        Path output=dir.resolve("snapshot.json");
        var runner=new CloudMarketSnapshotRunner(scanner,new JsonMapper(),output.toString(),1000);
        assertThrows(RuntimeException.class,()->runner.run(new DefaultApplicationArguments()));
        assertFalse(Files.exists(output));
    }
}
