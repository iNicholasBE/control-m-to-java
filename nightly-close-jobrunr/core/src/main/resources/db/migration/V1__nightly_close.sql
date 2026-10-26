-- Application tables for the nightly close chain. JobRunr creates its own jobrunr_* tables.

-- One row per (business date, run number): the enqueue contract's source of truth.
CREATE TABLE chain_runs (
    run_id           uuid PRIMARY KEY,
    business_date    date        NOT NULL,
    run_number       int         NOT NULL,
    input_sha256     text,
    state            text        NOT NULL,
    partitions_total int,
    submitted_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    started_at       timestamptz,
    finished_at      timestamptz,
    report_file      text,
    report_sha256    text,
    delivery_receipt text,
    UNIQUE (business_date, run_number)
);

-- Every step execution writes a row on entry and completes it on exit.
-- A row with outcome NULL after the run finished means the JVM died inside that step.
CREATE TABLE step_executions (
    id          bigserial PRIMARY KEY,
    run_id      uuid        NOT NULL,
    step        text        NOT NULL,
    attempt     int         NOT NULL,
    started_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    outcome     text,
    UNIQUE (run_id, step, attempt)
);

CREATE TABLE positions_staging (
    run_id              uuid NOT NULL,
    position_id         text NOT NULL,
    business_date       date,
    portfolio           text NOT NULL,
    book                text,
    desk                text,
    trader_id           text,
    instrument_id       text,
    instrument_type     text,
    currency            text,
    counterparty_id     text,
    counterparty_rating text,
    country             text,
    sector              text,
    quantity            bigint,
    price               numeric(18, 4),
    notional            numeric(20, 2),
    market_value        numeric(20, 2),
    accrued_interest    numeric(18, 2),
    fx_rate             numeric(12, 6),
    notional_eur        numeric(20, 2),
    duration            numeric(10, 4),
    convexity           numeric(10, 4),
    delta               numeric(10, 4),
    gamma               numeric(10, 6),
    vega                numeric(14, 2),
    theta               numeric(14, 2),
    volatility          numeric(8, 6),
    pd                  numeric(8, 6),
    lgd                 numeric(6, 4),
    maturity_date       date,
    trade_date          date,
    settlement_date     date,
    coupon_rate         numeric(8, 4),
    spread_bps          numeric(10, 2),
    haircut             numeric(6, 4),
    collateral_eur      numeric(20, 2),
    limit_eur           numeric(20, 2),
    status              text,
    source_system       text,
    comments            text
);
CREATE INDEX positions_staging_run_portfolio ON positions_staging (run_id, portfolio);

CREATE TABLE position_risk (
    run_id           uuid           NOT NULL,
    portfolio        text           NOT NULL,
    position_id      text           NOT NULL,
    notional_eur     numeric(20, 2) NOT NULL,
    market_value_eur numeric(20, 2) NOT NULL,
    var_1d           numeric(20, 2) NOT NULL,
    expected_loss    numeric(20, 2) NOT NULL,
    stressed_loss    numeric(20, 2) NOT NULL
);
CREATE INDEX position_risk_run_portfolio ON position_risk (run_id, portfolio);

-- Written by every partition, in the same transaction as its scores.
-- The OSS module joins on this table; both modules report partitions done/total from it.
CREATE TABLE partition_progress (
    run_id      uuid        NOT NULL,
    partition   int         NOT NULL,
    portfolio   text        NOT NULL,
    positions   int         NOT NULL,
    finished_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, partition)
);

CREATE TABLE portfolio_aggregates (
    run_id               uuid           NOT NULL,
    portfolio            text           NOT NULL,
    positions            bigint         NOT NULL,
    gross_notional_eur   numeric(24, 2) NOT NULL,
    net_market_value_eur numeric(24, 2) NOT NULL,
    var_1d_eur           numeric(24, 2) NOT NULL,
    expected_loss_eur    numeric(24, 2) NOT NULL,
    stressed_loss_eur    numeric(24, 2) NOT NULL,
    PRIMARY KEY (run_id, portfolio)
);

-- The simulated regulator's side. The UNIQUE key turns a second delivery into a rejected insert.
CREATE TABLE deliveries (
    id              bigserial PRIMARY KEY,
    idempotency_key text        NOT NULL UNIQUE,
    business_date   date        NOT NULL,
    run_number      int         NOT NULL,
    report_sha256   text        NOT NULL,
    delivered_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE delivery_rejections (
    id              bigserial PRIMARY KEY,
    idempotency_key text        NOT NULL,
    business_date   date        NOT NULL,
    run_number      int         NOT NULL,
    attempted_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);
