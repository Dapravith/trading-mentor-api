-- Legacy alert dedupe table retained for migration compatibility.
-- New durable outbox does not treat this pre-delivery record as "sent".
CREATE TABLE IF NOT EXISTS signal_alerts (
    symbol VARCHAR(20) NOT NULL,
    candle_interval VARCHAR(10) NOT NULL,
    candle_close_ms BIGINT NOT NULL,
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (symbol, candle_interval, candle_close_ms)
);

CREATE TABLE IF NOT EXISTS alert_outbox (
    id VARCHAR(160) PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    candle_interval VARCHAR(10) NOT NULL,
    candle_close_ms BIGINT NOT NULL,
    strategy_version VARCHAR(32) NOT NULL,
    payload CLOB NOT NULL,
    status VARCHAR(12) NOT NULL,
    attempts INT DEFAULT 0 NOT NULL,
    next_attempt_ms BIGINT NOT NULL,
    lease_until_ms BIGINT,
    delivered_at_ms BIGINT,
    provider_message_id BIGINT,
    error_code VARCHAR(64),
    created_at_ms BIGINT NOT NULL,
    CONSTRAINT chk_attempts CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS idx_alert_ready ON alert_outbox(status, next_attempt_ms);

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


-- Explicit manual replay history. Records why a FAILED event was requeued.
-- Existing H2 databases retain all previous outbox records.
CREATE TABLE IF NOT EXISTS alert_retry_audit (
    id VARCHAR(36) PRIMARY KEY,
    alert_id VARCHAR(160) NOT NULL,
    prior_attempts INT NOT NULL,
    prior_error_code VARCHAR(64),
    operator_reason VARCHAR(200) NOT NULL,
    requested_at_ms BIGINT NOT NULL,
    CONSTRAINT fk_alert_retry_outbox FOREIGN KEY (alert_id) REFERENCES alert_outbox(id)
);
CREATE INDEX IF NOT EXISTS idx_retry_audit_event ON alert_retry_audit(alert_id, requested_at_ms);
