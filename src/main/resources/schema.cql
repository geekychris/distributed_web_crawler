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
    max_pages int,
    max_pages_per_domain int,
    max_domains int,
    created_at timestamp,
    updated_at timestamp
);

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
