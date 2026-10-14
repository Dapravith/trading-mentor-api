package kh.mentor.trading.api;

import kh.mentor.trading.backtest.BacktestResult;
import kh.mentor.trading.backtest.BacktestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only educational replay; no exchange orders or user credentials. */
@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestController {
    private final BacktestService service;

    public BacktestController(BacktestService service) {
        this.service = service;
    }

    @GetMapping("/run")
    public BacktestResult run(
        @RequestParam(defaultValue = "BTCUSDT") String symbol,
        @RequestParam(defaultValue = "4h") String interval,
        @RequestParam(defaultValue = "1000") double startingBalance) {
        return service.run(symbol, interval, startingBalance);
    }
}
