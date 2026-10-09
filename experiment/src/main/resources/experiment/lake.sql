-- MIP-0083 §5.12: the experiment's lake tables. Hand-written (nothing generates SQL from a
-- kyo-schema `Schema`); SchemaSpec checks each table's columns against its record's wire fields.
-- experiment_point holds the registry (SamplingPoint) as it was when a cycle ran.

CREATE TABLE experiment_point (
    id VARCHAR NOT NULL,
    lat DOUBLE NOT NULL,
    lon DOUBLE NOT NULL,
    kind VARCHAR NOT NULL,
    instrument VARCHAR,
    added_on DATE NOT NULL,
    retired_on DATE
);

CREATE TABLE forecast_sample (
    provider VARCHAR NOT NULL,
    run_init TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    point VARCHAR NOT NULL,
    valid_time TIMESTAMPTZ NOT NULL,
    lead_h INTEGER NOT NULL,
    variable VARCHAR NOT NULL,
    member INTEGER,
    value DOUBLE NOT NULL,
    cell_lat DOUBLE NOT NULL,
    cell_lon DOUBLE NOT NULL,
    source_url VARCHAR NOT NULL
);

CREATE TABLE observation_sample (
    instrument VARCHAR NOT NULL,
    valid_time TIMESTAMPTZ NOT NULL,
    variable VARCHAR NOT NULL,
    value DOUBLE NOT NULL,
    averaging VARCHAR NOT NULL,
    qc_passed BOOLEAN NOT NULL
);

CREATE TABLE run_index (
    provider VARCHAR NOT NULL,
    run_init TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ,
    content_sha VARCHAR,
    state VARCHAR NOT NULL,
    reason VARCHAR,
    fetched_at TIMESTAMPTZ
);

CREATE TABLE score_cell (
    provider VARCHAR NOT NULL,
    point VARCHAR NOT NULL,
    variable VARCHAR NOT NULL,
    lead_h INTEGER NOT NULL,
    bin VARCHAR NOT NULL,
    day DATE NOT NULL,
    n BIGINT NOT NULL,
    sum_e DOUBLE NOT NULL,
    sum_sq_e DOUBLE NOT NULL,
    sum_abs_e DOUBLE NOT NULL,
    sum_crps DOUBLE NOT NULL,
    sum_sq_spread DOUBLE NOT NULL
);

CREATE TABLE pair_cell (
    provider_a VARCHAR NOT NULL,
    provider_b VARCHAR NOT NULL,
    point VARCHAR NOT NULL,
    variable VARCHAR NOT NULL,
    lead_h INTEGER NOT NULL,
    day DATE NOT NULL,
    n BIGINT NOT NULL,
    sum_d DOUBLE NOT NULL,
    sum_sq_d DOUBLE NOT NULL,
    sum_ea_eb DOUBLE NOT NULL,
    sum_sq_ea DOUBLE NOT NULL,
    sum_sq_eb DOUBLE NOT NULL
);

CREATE TABLE navy_warning (
    number INTEGER NOT NULL,
    area VARCHAR NOT NULL,
    force INTEGER NOT NULL,
    gust INTEGER,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    raw_text_sha VARCHAR NOT NULL
);
