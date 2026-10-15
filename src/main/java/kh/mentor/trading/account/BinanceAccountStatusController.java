package kh.mentor.trading.account;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local-only, quantity-free summary. Server binds to loopback by default. */
@RestController
@RequestMapping("/api/v1/binance/account")
@ConditionalOnProperty(prefix="trading.account",name="enabled",havingValue="true")
public class BinanceAccountStatusController {
    private final BinanceSpotAccountReader reader;
    public BinanceAccountStatusController(BinanceSpotAccountReader reader) {this.reader=reader;}

    @GetMapping("/status")
    public BinanceSpotAccountReader.AccountStatus status() {return reader.check();}
}
