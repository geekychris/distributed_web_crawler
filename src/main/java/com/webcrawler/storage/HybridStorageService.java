package com.webcrawler.storage;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.webcrawler.config.S3Properties;
import com.webcrawler.model.PageContent;
import com.webcrawler.queue.PageEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
public class HybridStorageService implements StorageService {
    private static final Logger logger = LoggerFactory.getLogger(HybridStorageService.class);

    private final CqlSession cassandraSession;
    private final S3AsyncClient s3Client;
    private final String bucketName;
    private final PageEventPublisher eventPublisher;

    // Prepared once, reused everywhere — driver warns on repeated prepare()s.
    private final PreparedStatement insertPage;
    private final PreparedStatement selectPageByUrl;
    private final PreparedStatement selectContentHash;
    private final PreparedStatement insertContentHash;
    private final PreparedStatement selectAllPagesLimited;
    private final PreparedStatement countPages;

    @Autowired
    public HybridStorageService(CqlSession cassandraSession,
                                S3AsyncClient s3Client,
                                S3Properties s3Properties,
                                PageEventPublisher eventPublisher) {
        this.cassandraSession = cassandraSession;
        this.s3Client = s3Client;
        this.bucketName = s3Properties.bucket();
        this.eventPublisher = eventPublisher;

        this.insertPage = cassandraSession.prepare(
            "INSERT INTO pages (url, content_hash, fetch_time, http_status, headers, links, "
          + "metadata, s3_key, job_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        this.selectPageByUrl = cassandraSession.prepare("SELECT * FROM pages WHERE url = ?");
        this.selectContentHash = cassandraSession.prepare(
            "SELECT content_hash FROM content_hashes WHERE content_hash = ? LIMIT 1");
        this.insertContentHash = cassandraSession.prepare(
            "INSERT INTO content_hashes (content_hash, url, first_seen) VALUES (?, ?, ?) IF NOT EXISTS");
        this.selectAllPagesLimited = cassandraSession.prepare(
            "SELECT url, content_hash, fetch_time, http_status, headers, links, metadata "
          + "FROM pages LIMIT ?");
        this.countPages = cassandraSession.prepare("SELECT COUNT(*) FROM pages");
    }

    @Override
    public CompletableFuture<Void> store(PageContent content) {
        String s3Key = String.format("pages/%s/%s/%s.html",
                content.fetchTime().toString().substring(0, 10),
                content.contentHash().substring(0, 2),
                content.contentHash());

        String contentType = lookupHeaderIgnoreCase(content.headers(), "Content-Type",
                "text/html; charset=utf-8");

        // Sequence: S3 first, THEN Cassandra. On Cassandra failure delete the
        // blob to avoid orphans. On S3 failure Cassandra never runs.
        return s3Client.putObject(
                    req -> req.bucket(bucketName).key(s3Key).contentType(contentType),
                    AsyncRequestBody.fromString(content.content(), StandardCharsets.UTF_8))
                .thenCompose(v -> cassandraSession.executeAsync(insertPage.bind(
                        content.url(),
                        content.contentHash(),
                        content.fetchTime(),
                        content.httpStatus(),
                        content.headers(),
                        content.links(),
                        content.metadata(),
                        s3Key,
                        content.jobId()
                )).toCompletableFuture())
                .thenCompose(rs -> {
                    // Best-effort dedup registration. LWT protects against races
                    // and is idempotent — first writer wins.
                    return cassandraSession.executeAsync(insertContentHash.bind(
                            content.contentHash(), content.url(), Instant.now()))
                            .toCompletableFuture()
                            .exceptionally(t -> {
                                logger.debug("content_hashes upsert failed for {}: {}",
                                        content.contentHash(), t.getMessage());
                                return null;
                            });
                })
                .thenCompose(v -> eventPublisher.publish(content, s3Key).exceptionally(t -> {
                    logger.warn("page event publish failed for {}: {}", content.url(), t.getMessage());
                    return null;
                }))
                .exceptionally(t -> {
                    logger.error("store() failed for {} — attempting S3 cleanup", content.url(), t);
                    s3Client.deleteObject(req -> req.bucket(bucketName).key(s3Key))
                            .whenComplete((r, ex) -> {
                                if (ex != null) logger.warn("Cleanup of {} failed: {}", s3Key, ex.getMessage());
                            });
                    throw new java.util.concurrent.CompletionException(t);
                });
    }

    @Override
    public CompletableFuture<Optional<PageContent>> retrieve(String url) {
        return cassandraSession.executeAsync(selectPageByUrl.bind(url))
                .toCompletableFuture()
                .thenCompose(rs -> {
                    Row row = rs.one();
                    if (row == null) return CompletableFuture.completedFuture(Optional.empty());
                    String s3Key = row.getString("s3_key");
                    return s3Client.getObject(
                                    req -> req.bucket(bucketName).key(s3Key),
                                    AsyncResponseTransformer.toBytes())
                            .thenApply(response -> {
                                String content = new String(response.asByteArray(), StandardCharsets.UTF_8);
                                return Optional.of(new PageContent(
                                        row.getString("url"),
                                        row.getString("content_hash"),
                                        content,
                                        row.getInstant("fetch_time"),
                                        row.getInt("http_status"),
                                        row.getMap("headers", String.class, String.class),
                                        row.getSet("links", String.class),
                                        row.getMap("metadata", String.class, String.class),
                                        row.isNull("job_id") ? null : row.getUuid("job_id")
                                ));
                            });
                });
    }

    @Override
    public CompletableFuture<Boolean> exists(String contentHash) {
        return cassandraSession.executeAsync(selectContentHash.bind(contentHash))
                .toCompletableFuture()
                .thenApply(rs -> rs.one() != null);
    }

    @Override
    public CompletableFuture<List<PageMetadata>> getAllPages(int limit, int offset) {
        // Cassandra has no OFFSET. We fetch (limit+offset) rows and skip in
        // memory. Small offsets: fine. Deep offsets: use pageState-based
        // pagination instead — callers should switch to that once the UI
        // supports opaque page tokens.
        int fetch = Math.min(limit + Math.max(0, offset), 5000);
        return cassandraSession.executeAsync(selectAllPagesLimited.bind(fetch))
                .toCompletableFuture()
                .thenApply(asyncRs -> {
                    List<PageMetadata> pages = new ArrayList<>();
                    int skipped = 0;
                    for (Row row : asyncRs.currentPage()) {
                        if (skipped < offset) { skipped++; continue; }
                        pages.add(rowToMetadata(row));
                        if (pages.size() >= limit) break;
                    }
                    return pages;
                });
    }

    @Override
    public CompletableFuture<List<PageMetadata>> searchPages(String searchTerm, int limit) {
        // WARNING: This scans up to 5000 pages and filters URLs in memory —
        // undefined ordering across the token ring, and matches beyond that
        // window are silently missed. Kept for parity with the old API; wire
        // in a real search index (Elasticsearch / SAI) if you need this at
        // any scale.
        int scanLimit = 5000;
        return cassandraSession.executeAsync(selectAllPagesLimited.bind(scanLimit))
                .toCompletableFuture()
                .thenApply(asyncRs -> {
                    List<PageMetadata> matches = new ArrayList<>();
                    String needle = searchTerm.toLowerCase();
                    for (Row row : asyncRs.currentPage()) {
                        String url = row.getString("url");
                        if (url != null && url.toLowerCase().contains(needle)) {
                            matches.add(rowToMetadata(row));
                            if (matches.size() >= limit) break;
                        }
                    }
                    return matches;
                });
    }

    @Override
    public CompletableFuture<Long> getPageCount() {
        return cassandraSession.executeAsync(countPages.bind())
                .toCompletableFuture()
                .thenApply(rs -> {
                    Row row = rs.one();
                    return row != null ? row.getLong(0) : 0L;
                });
    }

    /**
     * HTTP headers are case-insensitive per RFC 7230. Case can drift depending
     * on the client (Jsoup normalises differently from HttpClient).
     */
    public static String lookupHeaderIgnoreCase(java.util.Map<String, String> headers,
                                                String name, String defaultValue) {
        if (headers == null || headers.isEmpty()) return defaultValue;
        String direct = headers.get(name);
        if (direct != null) return direct;
        for (var e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return defaultValue;
    }

    private PageMetadata rowToMetadata(Row row) {
        return new PageMetadata(
                row.getString("url"),
                row.getString("content_hash"),
                row.getInstant("fetch_time"),
                row.getInt("http_status"),
                row.getMap("headers", String.class, String.class),
                row.getSet("links", String.class),
                row.getMap("metadata", String.class, String.class));
    }
}
