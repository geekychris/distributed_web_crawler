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
    UUID jobId,
    String sourceFeedItemId
) {
    /** Legacy 5-arg constructor — leaves jobId + sourceFeedItemId null. */
    public CrawlRequest(String url, int depth, String parentUrl, Instant discoveredAt, int priority) {
        this(url, depth, parentUrl, discoveredAt, priority, 0, null, null, null);
    }

    /** Legacy 7-arg constructor with retry state, no job. */
    public CrawlRequest(String url, int depth, String parentUrl, Instant discoveredAt,
                        int priority, int retryCount, Instant scheduledFor) {
        this(url, depth, parentUrl, discoveredAt, priority, retryCount, scheduledFor, null, null);
    }

    /** Legacy 8-arg constructor (with job, no feed attribution). */
    public CrawlRequest(String url, int depth, String parentUrl, Instant discoveredAt,
                        int priority, int retryCount, Instant scheduledFor, UUID jobId) {
        this(url, depth, parentUrl, discoveredAt, priority, retryCount, scheduledFor, jobId, null);
    }

    public CrawlRequest withRetry(int newRetryCount, Instant newScheduledFor) {
        return new CrawlRequest(url, depth, parentUrl, discoveredAt, priority,
                newRetryCount, newScheduledFor, jobId, sourceFeedItemId);
    }

    public CrawlRequest withJob(UUID newJobId) {
        return new CrawlRequest(url, depth, parentUrl, discoveredAt, priority,
                retryCount, scheduledFor, newJobId, sourceFeedItemId);
    }

    @JsonIgnore
    public boolean isReadyToProcess() {
        return scheduledFor == null || !Instant.now().isBefore(scheduledFor);
    }
}
