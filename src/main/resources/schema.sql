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
    CONSTRAINT chk_alert_status CHECK (status='PENDING' OR status='SENDING' OR status='SENT' OR status='FAILED'),
    CONSTRAINT chk_attempts CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS idx_alert_ready ON alert_outbox(status, next_attempt_ms);
