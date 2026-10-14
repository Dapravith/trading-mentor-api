package kh.mentor.trading.alerts;

import kh.mentor.trading.strategy.MarketAnalysis;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {
    private final JdbcTemplate jdbc;
    public AlertRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Unique DB key ensures a closed-candle setup is delivered at most once by a single worker. */
    public boolean recordNew(MarketAnalysis analysis) {
        if (!"PAPER_SETUP_WATCH".equals(analysis.status())) return false;
        try {
            return jdbc.update("INSERT INTO signal_alerts (symbol, candle_interval, candle_close_ms) VALUES (?, ?, ?)",
                analysis.symbol(), analysis.interval(), analysis.candleCloseMs()) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
