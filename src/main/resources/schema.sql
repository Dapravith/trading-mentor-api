CREATE TABLE IF NOT EXISTS signal_alerts (
    symbol VARCHAR(20) NOT NULL,
    candle_interval VARCHAR(10) NOT NULL,
    candle_close_ms BIGINT NOT NULL,
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (symbol, candle_interval, candle_close_ms)
);

-- Paper money only. Double is acceptable for educational simulation, NOT live settlement.
CREATE TABLE IF NOT EXISTS paper_account (
    id INT PRIMARY KEY,
    starting_balance_usdt DOUBLE PRECISION NOT NULL,
    cash_usdt DOUBLE PRECISION NOT NULL
);
INSERT INTO paper_account (id, starting_balance_usdt, cash_usdt)
SELECT 1, 1000, 1000 WHERE NOT EXISTS (SELECT 1 FROM paper_account WHERE id=1);

CREATE TABLE IF NOT EXISTS paper_positions (
    id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL UNIQUE,
    symbol VARCHAR(20) NOT NULL,
    entry_price DOUBLE PRECISION NOT NULL,
    stop_price DOUBLE PRECISION NOT NULL,
    target_price DOUBLE PRECISION NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    entry_cash_usdt DOUBLE PRECISION NOT NULL,
    opened_at_ms BIGINT NOT NULL,
    status VARCHAR(10) NOT NULL,
    close_price DOUBLE PRECISION,
    close_cash_usdt DOUBLE PRECISION,
    net_pnl_usdt DOUBLE PRECISION,
    closed_at_ms BIGINT,
    thesis VARCHAR(500) NOT NULL,
    emotion VARCHAR(32) NOT NULL,
    exit_reason VARCHAR(16),
    reflection VARCHAR(2000)
);
CREATE INDEX IF NOT EXISTS idx_paper_positions_status ON paper_positions(status);
CREATE INDEX IF NOT EXISTS idx_paper_positions_closed ON paper_positions(closed_at_ms);
