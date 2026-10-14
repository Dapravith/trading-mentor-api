CREATE TABLE IF NOT EXISTS signal_alerts (
    symbol VARCHAR(20) NOT NULL,
    candle_interval VARCHAR(10) NOT NULL,
    candle_close_ms BIGINT NOT NULL,
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (symbol, candle_interval, candle_close_ms)
);
