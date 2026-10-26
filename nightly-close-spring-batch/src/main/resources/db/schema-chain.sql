-- Tables of the interface contract and of this implementation. Idempotent: run on every boot.

CREATE TABLE IF NOT EXISTS chain_runs (
    run_id           TEXT PRIMARY KEY,
    business_date    DATE NOT NULL,
    run_number       INT  NOT NULL,
    input_hash       TEXT,
    trigger_source   TEXT NOT NULL,
    owner_node       TEXT,
    job_execution_id BIGINT,
    handover         BOOLEAN NOT NULL DEFAULT FALSE,
    partitions_total INT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (business_date, run_number)
);

CREATE TABLE IF NOT EXISTS chain_nodes (
    node_id        TEXT PRIMARY KEY,
    node_name      TEXT NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    last_heartbeat TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS step_executions (
    run_id           TEXT NOT NULL,
    step             TEXT NOT NULL,
    attempt          INT  NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL,
    finished_at      TIMESTAMPTZ,
    outcome          TEXT NOT NULL,
    node             TEXT,
    job_execution_id BIGINT,
    PRIMARY KEY (run_id, step, attempt)
);

CREATE TABLE IF NOT EXISTS positions_staging (
    run_id    TEXT   NOT NULL,
    line_no   BIGINT NOT NULL,
    portfolio TEXT   NOT NULL,
    raw       TEXT   NOT NULL,
    PRIMARY KEY (run_id, line_no)
);
CREATE INDEX IF NOT EXISTS positions_staging_by_portfolio ON positions_staging (run_id, portfolio, line_no);

CREATE TABLE IF NOT EXISTS risk_scores (
    run_id    TEXT   NOT NULL,
    line_no   BIGINT NOT NULL,
    portfolio TEXT   NOT NULL,
    score     NUMERIC(28, 4) NOT NULL,
    PRIMARY KEY (run_id, line_no)
);

CREATE TABLE IF NOT EXISTS portfolio_aggregates (
    run_id      TEXT NOT NULL,
    portfolio   TEXT NOT NULL,
    positions   BIGINT NOT NULL,
    total_score NUMERIC(38, 4) NOT NULL,
    max_score   NUMERIC(28, 4) NOT NULL,
    avg_score   NUMERIC(28, 4) NOT NULL,
    PRIMARY KEY (run_id, portfolio)
);

-- Regulator side (the stub): one row per accepted delivery, one row per rejected duplicate.
CREATE TABLE IF NOT EXISTS deliveries (
    idempotency_key TEXT NOT NULL,
    business_date   DATE NOT NULL,
    run_number      INT  NOT NULL,
    delivered_at    TIMESTAMPTZ NOT NULL,
    report_sha256   TEXT,
    node            TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS deliveries_idempotency_key ON deliveries (idempotency_key);

CREATE TABLE IF NOT EXISTS delivery_rejections (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key TEXT NOT NULL,
    attempted_at    TIMESTAMPTZ NOT NULL,
    report_sha256   TEXT,
    node            TEXT
);

-- Chain side: "we know the regulator has it".
CREATE TABLE IF NOT EXISTS delivery_receipts (
    idempotency_key  TEXT PRIMARY KEY,
    run_id           TEXT NOT NULL,
    regulator_status INT  NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL
);
