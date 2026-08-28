package com.webcrawler.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A crawl job — a named unit of work that owns its seed URLs, scope, and
 * budget. Every {@link CrawlRequest} produced by a job carries its jobId so
 * budget accounting and cancellation apply only to that job's traffic.
 */
public record CrawlJob(
    UUID jobId,
    String name,
    Status status,
    Set<String> seedUrls,
    Set<String> allowedDomains,
    Set<String> excludePatterns,
    int maxDepth,
    int maxPages,
    int maxPagesPerDomain,
    int maxDomains,
    Instant createdAt,
    Instant updatedAt
) {
    public enum Status { PENDING, RUNNING, PAUSED, DONE, CANCELLED }

    public CrawlJob withStatus(Status newStatus) {
        return new CrawlJob(jobId, name, newStatus, seedUrls, allowedDomains, excludePatterns,
                maxDepth, maxPages, maxPagesPerDomain, maxDomains, createdAt, Instant.now());
    }
}
