CREATE TABLE search_queries (
    id                BIGSERIAL PRIMARY KEY,
    source            VARCHAR(32) NOT NULL,
    keyword           VARCHAR(128) NOT NULL,
    max_pages         INT NOT NULL DEFAULT 10,
    interval_minutes  INT NOT NULL DEFAULT 120,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, keyword)
);

CREATE TABLE scrape_cursors (
    id                 BIGSERIAL PRIMARY KEY,
    search_query_id    BIGINT NOT NULL REFERENCES search_queries (id),
    last_scanned_at    TIMESTAMPTZ,
    last_page_scanned  INT,
    UNIQUE (search_query_id)
);

CREATE TABLE scrape_runs (
    id             BIGSERIAL PRIMARY KEY,
    source         VARCHAR(32) NOT NULL,
    query_keyword  VARCHAR(128) NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    pages_scanned  INT NOT NULL DEFAULT 0,
    jobs_seen      INT NOT NULL DEFAULT 0,
    jobs_new       INT,
    status         VARCHAR(16) NOT NULL,
    error_message  TEXT
);

CREATE INDEX idx_scrape_runs_source_started_at ON scrape_runs (source, started_at DESC);

CREATE TABLE jobs (
    id               BIGSERIAL PRIMARY KEY,
    source           VARCHAR(32) NOT NULL,
    source_job_id    VARCHAR(64) NOT NULL,
    title            TEXT NOT NULL,
    company          TEXT,
    salary_min       BIGINT,
    salary_max       BIGINT,
    salary_currency  VARCHAR(8),
    url              TEXT NOT NULL,
    content_hash     VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'open',
    attrs            JSONB NOT NULL DEFAULT '{}'::jsonb,
    first_seen_at    TIMESTAMPTZ NOT NULL,
    last_seen_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (source, source_job_id)
);

CREATE INDEX idx_jobs_last_seen_at ON jobs (last_seen_at);
CREATE INDEX idx_jobs_status ON jobs (status);

CREATE TABLE job_snapshots (
    id             BIGSERIAL PRIMARY KEY,
    source         VARCHAR(32) NOT NULL,
    source_job_id  VARCHAR(64) NOT NULL,
    scraped_at     TIMESTAMPTZ NOT NULL,
    title          TEXT,
    company        TEXT,
    salary_min     BIGINT,
    salary_max     BIGINT,
    content_hash   VARCHAR(64),
    UNIQUE (source, source_job_id, scraped_at)
);

CREATE TABLE raw_documents (
    id             BIGSERIAL PRIMARY KEY,
    source         VARCHAR(32) NOT NULL,
    source_job_id  VARCHAR(64) NOT NULL,
    fetched_at     TIMESTAMPTZ NOT NULL,
    payload        JSONB NOT NULL,
    UNIQUE (source, source_job_id, fetched_at)
);
