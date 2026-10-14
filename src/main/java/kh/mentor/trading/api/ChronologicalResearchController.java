package kh.mentor.trading.api;

import kh.mentor.trading.backtest.ChronologicalResearchResult;
import kh.mentor.trading.backtest.ChronologicalResearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only research: only completed historical UTC daily candles. */
@RestController
@RequestMapping("/api/v1/backtests")
public class ChronologicalResearchController {
    private final ChronologicalResearchService service;
    public ChronologicalResearchController(ChronologicalResearchService service) {
        this.service = service;
    }

    @GetMapping("/chronological")
    public ChronologicalResearchResult evaluate(
        @RequestParam(defaultValue = "BTCUSDT") String symbol,
        @RequestParam(defaultValue = "2022-01-01") String start,
        @RequestParam(defaultValue = "2026-01-01") String end,
        @RequestParam(defaultValue = "1000") double startingBalance) {
        return service.run(symbol, start, end, startingBalance);
    }
}
