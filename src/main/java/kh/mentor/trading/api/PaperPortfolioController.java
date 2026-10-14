package kh.mentor.trading.api;

import java.util.List;
import kh.mentor.trading.paper.ClosePaperRequest;
import kh.mentor.trading.paper.OpenPaperRequest;
import kh.mentor.trading.paper.PaperPortfolioService;
import kh.mentor.trading.paper.PaperPosition;
import kh.mentor.trading.paper.PaperSummary;
import org.springframework.web.bind.annotation.*;

/** Manual learning journal only. Never talks to Binance account/order APIs. */
@RestController
@RequestMapping("/api/v1/paper")
public class PaperPortfolioController {
    private final PaperPortfolioService service;
    public PaperPortfolioController(PaperPortfolioService service) { this.service=service; }

    @PostMapping("/positions")
    public PaperPosition open(@RequestBody OpenPaperRequest request) {
        return service.open(request);
    }

    @PostMapping("/positions/{id}/close")
    public PaperPosition close(@PathVariable String id,@RequestBody ClosePaperRequest request) {
        return service.close(id,request);
    }

    @GetMapping("/positions")
    public List<PaperPosition> positions() { return service.recent(); }

    @GetMapping("/summary")
    public PaperSummary summary() { return service.summary(); }
}
