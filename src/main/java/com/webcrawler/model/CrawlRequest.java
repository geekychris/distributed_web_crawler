package com.webcrawler.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

public record CrawlRequest(
    String url,
    int depth,
    String parentUrl,
    Instant discoveredAt,
    int priority,
    int retryCount,
    Instant scheduledFor
) {
    // Constructor for backward compatibility (no retry info)
    public CrawlRequest(String url, int depth, String parentUrl, Instant discoveredAt, int priority) {
        this(url, depth, parentUrl, discoveredAt, priority, 0, null);
    }
    
    // Constructor for retry scheduling
    public CrawlRequest withRetry(int newRetryCount, Instant newScheduledFor) {
        return new CrawlRequest(url, depth, parentUrl, discoveredAt, priority, newRetryCount, newScheduledFor);
    }
    
    // Check if this request is ready to be processed
    @JsonIgnore
    public boolean isReadyToProcess() {
        return scheduledFor == null || !Instant.now().isBefore(scheduledFor);
    }
}
