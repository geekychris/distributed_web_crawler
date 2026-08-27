CREATE KEYSPACE IF NOT EXISTS crawler
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS crawler.pages (
    url text PRIMARY KEY,
    content_hash text,
    fetch_time timestamp,
    http_status int,
    headers map<text, text>,
    links set<text>,
    metadata map<text, text>,
    s3_key text,
    job_id uuid
);

-- Idempotent migration for installs that predate job_id / max_depth.
-- Cassandra 4.x supports "IF NOT EXISTS" on ALTER TABLE ADD.
ALTER TABLE crawler.pages ADD IF NOT EXISTS job_id uuid;

CREATE INDEX IF NOT EXISTS idx_content_hash ON crawler.pages(content_hash);
CREATE INDEX IF NOT EXISTS idx_job_id ON crawler.pages(job_id);

CREATE TABLE IF NOT EXISTS crawler.content_hashes (
    content_hash text PRIMARY KEY,
    url text,
    first_seen timestamp
);

CREATE TABLE IF NOT EXISTS crawler.crawl_state (
    domain text PRIMARY KEY,
    last_crawl_time timestamp,
    robots_txt text,
    crawl_delay int
);

CREATE TABLE IF NOT EXISTS crawler.crawl_jobs (
    job_id uuid PRIMARY KEY,
    name text,
    status text,
    seed_urls set<text>,
    allowed_domains set<text>,
    exclude_patterns set<text>,
    max_depth int,
    max_pages int,
    max_pages_per_domain int,
    max_domains int,
    created_at timestamp,
    updated_at timestamp
);

-- Idempotent migration for installs that predate max_depth on crawl_jobs.
ALTER TABLE crawler.crawl_jobs ADD IF NOT EXISTS max_depth int;

CREATE TABLE IF NOT EXISTS crawler.crawl_job_progress (
    job_id uuid,
    domain text,
    pages_crawled counter,
    PRIMARY KEY (job_id, domain)
);

CREATE TABLE IF NOT EXISTS crawler.crawl_job_domains (
    job_id uuid,
    domain text,
    PRIMARY KEY (job_id, domain)
);
