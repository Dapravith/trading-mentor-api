package kh.mentor.trading.account;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loopback-only status API. Exposes non-sensitive delivery metadata; never
 * API keys, account IDs, balances, Telegram destination or message bodies.
 */
@RestController
@RequestMapping("/api/v1/binance/account")
@ConditionalOnProperty(prefix="trading.account",name="enabled",havingValue="true")
public class BinanceAccountStatusController {
    private static final ZoneId ICT=ZoneId.of("Asia/Phnom_Penh");
    private final BinanceSpotAccountReader reader;
    private final JdbcTemplate jdbc;

    public BinanceAccountStatusController(BinanceSpotAccountReader reader,JdbcTemplate jdbc) {
        this.reader=reader;
        this.jdbc=jdbc;
    }

    @GetMapping("/status")
    public BinanceSpotAccountReader.AccountStatus status() { return reader.check(); }

    /** Query the daily sender's persisted state WITHOUT sending a message. */
    @GetMapping("/daily-status")
    public ResponseEntity<DailyStatus> dailyStatus() {
        String today=LocalDate.now(ICT).toString();
        List<DailyStatus> result=jdbc.query("""
            SELECT report_date,status,attempts,provider_message_id,sent_at_ms,error_code
            FROM binance_account_digest WHERE report_date=?
            """,(rs,n)->new DailyStatus(rs.getString("report_date"),
                rs.getString("status"),rs.getInt("attempts"),
                rs.getObject("provider_message_id",Long.class),
                rs.getObject("sent_at_ms",Long.class),
                rs.getString("error_code")),today);
        return result.isEmpty()?ResponseEntity.noContent().build():
            ResponseEntity.ok(result.getFirst());
    }

    public record DailyStatus(String reportDate,String status,int attempts,
        Long telegramMessageId,Long sentAtMs,String errorCode) {}
}
