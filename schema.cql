CREATE KEYSPACE IF NOT EXISTS crawler
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE crawler;

CREATE TABLE IF NOT EXISTS pages (
    url text PRIMARY KEY,
    content_hash text,
    fetch_time timestamp,
    http_status int,
    headers map<text, text>,
    links set<text>,
    metadata map<text, text>,
    s3_key text
);

CREATE INDEX IF NOT EXISTS idx_content_hash ON pages(content_hash);

CREATE TABLE IF NOT EXISTS crawl_state (
    domain text PRIMARY KEY,
    last_crawl_time timestamp,
    robots_txt text,
    crawl_delay int
);
