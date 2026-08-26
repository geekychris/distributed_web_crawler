package com.webcrawler.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

public record CrawlRequest(
    String url,
    int depth,
    String parentUrl,
    Instant discoveredAt,
    int priority,
    int retryCount,
    Instant scheduledFor,
    UUID jobId
) {
    /** Legacy constructor — leaves jobId null. Kept for JSON payloads written before jobs existed. */
    public CrawlRequest(String url, int depth, String parentUrl, Instant discoveredAt, int priority) {
        this(url, depth, parentUrl, discoveredAt, priority, 0, null, null);
    }

    /** Legacy constructor with retry state but no job. */
    public CrawlRequest(String url, int depth, String parentUrl, Instant discoveredAt,
                        int priority, int retryCount, Instant scheduledFor) {
        this(url, depth, parentUrl, discoveredAt, priority, retryCount, scheduledFor, null);
    }

    public CrawlRequest withRetry(int newRetryCount, Instant newScheduledFor) {
        return new CrawlRequest(url, depth, parentUrl, discoveredAt, priority,
                newRetryCount, newScheduledFor, jobId);
    }

    public CrawlRequest withJob(UUID newJobId) {
        return new CrawlRequest(url, depth, parentUrl, discoveredAt, priority,
                retryCount, scheduledFor, newJobId);
    }

    @JsonIgnore
    public boolean isReadyToProcess() {
        return scheduledFor == null || !Instant.now().isBefore(scheduledFor);
    }
}
