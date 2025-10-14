package com.webcrawler.storage;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webcrawler.config.S3Properties;
import com.webcrawler.model.PageContent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
public class HybridStorageService implements StorageService {
    private final CqlSession cassandraSession;
    private final S3AsyncClient s3Client;
    private final String bucketName;
    private final ObjectMapper objectMapper;

    @Autowired
    public HybridStorageService(CqlSession cassandraSession, S3AsyncClient s3Client, S3Properties s3Properties) {
        this.cassandraSession = cassandraSession;
        this.s3Client = s3Client;
        this.bucketName = s3Properties.bucket();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public CompletableFuture<Void> store(PageContent content) {
        // Store raw content in S3
        String s3Key = String.format("pages/%s/%s.html", 
            content.fetchTime().toString().substring(0, 10),
            content.contentHash());

        CompletableFuture<Void> s3Future = s3Client.putObject(
            req -> req.bucket(bucketName).key(s3Key),
            AsyncRequestBody.fromString(content.content())
        ).thenAccept(response -> {});

        // Store metadata in Cassandra
        PreparedStatement stmt = cassandraSession.prepare(
            "INSERT INTO crawler.pages (url, content_hash, fetch_time, http_status, headers, links, metadata, s3_key) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");

        CompletableFuture<AsyncResultSet> cassandraFuture = cassandraSession
            .executeAsync(stmt.bind(
                content.url(),
                content.contentHash(),
                content.fetchTime(),
                content.httpStatus(),
                content.headers(),
                content.links(),
                content.metadata(),
                s3Key
            ))
            .toCompletableFuture();

        return CompletableFuture.allOf(s3Future, cassandraFuture);
    }

    @Override
    public CompletableFuture<Optional<PageContent>> retrieve(String url) {
        PreparedStatement stmt = cassandraSession.prepare(
            "SELECT * FROM crawler.pages WHERE url = ?");

        return cassandraSession.executeAsync(stmt.bind(url))
            .toCompletableFuture()
            .thenCompose(rs -> {
                Row row = rs.one();
                if (row == null) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }

                String s3Key = row.getString("s3_key");
                return s3Client.getObject(
                    req -> req.bucket(bucketName).key(s3Key),
                    AsyncResponseTransformer.toBytes()
                ).thenApply(response -> {
                    String content = new String(response.asByteArray(), StandardCharsets.UTF_8);
                    return Optional.of(new PageContent(
                        row.getString("url"),
                        row.getString("content_hash"),
                        content,
                        row.getInstant("fetch_time"),
                        row.getInt("http_status"),
                        row.getMap("headers", String.class, String.class),
                        row.getSet("links", String.class),
                        row.getMap("metadata", String.class, String.class)
                    ));
                });
            });
    }

    @Override
    public CompletableFuture<Boolean> exists(String contentHash) {
        PreparedStatement stmt = cassandraSession.prepare(
            "SELECT content_hash FROM crawler.pages WHERE content_hash = ?");
        
        return cassandraSession.executeAsync(stmt.bind(contentHash))
            .toCompletableFuture()
            .thenApply(rs -> rs.one() != null);
    }

    @Override
    public CompletableFuture<List<StorageService.PageMetadata>> getAllPages(int limit, int offset) {
        // Note: Cassandra doesn't support OFFSET, so we'll fetch and skip in memory
        // In production, you'd use token-based pagination
        PreparedStatement stmt = cassandraSession.prepare(
            "SELECT url, content_hash, fetch_time, http_status, headers, links, metadata " +
            "FROM crawler.pages LIMIT ?");
        
        return cassandraSession.executeAsync(stmt.bind(limit + offset))
            .toCompletableFuture()
            .thenApply(asyncRs -> {
                List<StorageService.PageMetadata> pages = new ArrayList<>();
                int skipped = 0;
                for (Row row : asyncRs.currentPage()) {
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    pages.add(new StorageService.PageMetadata(
                        row.getString("url"),
                        row.getString("content_hash"),
                        row.getInstant("fetch_time"),
                        row.getInt("http_status"),
                        row.getMap("headers", String.class, String.class),
                        row.getSet("links", String.class),
                        row.getMap("metadata", String.class, String.class)
                    ));
                }
                return pages;
            });
    }

    @Override
    public CompletableFuture<List<StorageService.PageMetadata>> searchPages(String searchTerm, int limit) {
        // For Cassandra, we'll need to scan all pages and filter in memory
        // In production, you'd use a search index like Elasticsearch
        PreparedStatement stmt = cassandraSession.prepare(
            "SELECT url, content_hash, fetch_time, http_status, headers, links, metadata " +
            "FROM crawler.pages LIMIT 1000"); // Limit scan to reasonable size
        
        return cassandraSession.executeAsync(stmt.bind())
            .toCompletableFuture()
            .thenApply(asyncRs -> {
                List<StorageService.PageMetadata> matchingPages = new ArrayList<>();
                String lowerSearchTerm = searchTerm.toLowerCase();
                
                for (Row row : asyncRs.currentPage()) {
                    String url = row.getString("url");
                    if (url.toLowerCase().contains(lowerSearchTerm)) {
                        matchingPages.add(new StorageService.PageMetadata(
                            url,
                            row.getString("content_hash"),
                            row.getInstant("fetch_time"),
                            row.getInt("http_status"),
                            row.getMap("headers", String.class, String.class),
                            row.getSet("links", String.class),
                            row.getMap("metadata", String.class, String.class)
                        ));
                        if (matchingPages.size() >= limit) {
                            break;
                        }
                    }
                }
                return matchingPages;
            });
    }

    @Override
    public CompletableFuture<Long> getPageCount() {
        PreparedStatement stmt = cassandraSession.prepare(
            "SELECT COUNT(*) FROM crawler.pages");
        
        return cassandraSession.executeAsync(stmt.bind())
            .toCompletableFuture()
            .thenApply(rs -> {
                Row row = rs.one();
                return row != null ? row.getLong(0) : 0L;
            });
    }
}
