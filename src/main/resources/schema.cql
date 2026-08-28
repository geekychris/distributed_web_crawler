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

-- Runtime allow-list of trusted hosts / registrable domains for the
-- ScopeService. Replaces the process-wide HashSet so scope survives
-- restarts AND stops eating RAM on wide crawls.
CREATE TABLE IF NOT EXISTS crawler.trusted_hosts (
    host      text PRIMARY KEY,   -- for HOST mode: exact hostname
                                  -- for DOMAIN mode: registrable domain (e.g. "amiga.com")
    mode      text,               -- "HOST" | "DOMAIN"
    added_at  timestamp
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

-- ----------------------------------------------------------------------
-- Feed crawler (RSS / Atom) — distinct content type from web pages.
-- Same infra, separate tables + Kafka topic (crawler.feed_items.v1).
-- ----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS crawler.feeds (
    feed_id                uuid PRIMARY KEY,
    url                    text,
    title                  text,
    pack                   text,
    poll_interval_seconds  int,
    adaptive               boolean,
    follow_articles        boolean,
    store_full_content     boolean,
    status                 text,
    etag                   text,
    last_modified          text,
    last_polled_at         timestamp,
    next_poll_at           timestamp,
    consecutive_errors     int,
    consecutive_empty      int,
    created_at             timestamp,
    updated_at             timestamp
);

ALTER TABLE crawler.feeds ADD IF NOT EXISTS consecutive_empty int;
ALTER TABLE crawler.feeds ADD IF NOT EXISTS last_error_message text;
ALTER TABLE crawler.feeds ADD IF NOT EXISTS last_error_at timestamp;

CREATE TABLE IF NOT EXISTS crawler.feed_items (
    feed_id            uuid,
    first_seen         timestamp,
    item_id            text,
    url                text,
    title              text,
    summary            text,
    content_snippet    text,
    author             text,
    categories         set<text>,
    enclosures         list<frozen<map<text, text>>>,
    published_at       timestamp,
    updated_at         timestamp,
    followed_page_url  text,
    PRIMARY KEY ((feed_id), first_seen, item_id)
) WITH CLUSTERING ORDER BY (first_seen DESC, item_id ASC);

CREATE TABLE IF NOT EXISTS crawler.feed_items_by_id (
    item_id     text PRIMARY KEY,
    feed_id     uuid,
    url         text,
    first_seen  timestamp
);

CREATE TABLE IF NOT EXISTS crawler.feed_packs (
    pack_id      text PRIMARY KEY,
    name         text,
    description  text
);

CREATE TABLE IF NOT EXISTS crawler.feed_pack_members (
    pack_id                       text,
    url                           text,
    title                         text,
    default_poll_interval_seconds int,
    PRIMARY KEY (pack_id, url)
);

-- Small table (usually <100k rows) — cheap to full-scan on the poller
-- tick, so no secondary index on next_poll_at.

