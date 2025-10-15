package com.webcrawler.core;

import java.time.Instant;

/**
 * Represents a decision about whether and how to crawl a URL.
 */
public record CrawlDecision(
    CrawlAction action,
    String reason,
    Instant retryAt
) {
    // Factory methods for different decisions
    public static CrawlDecision crawl() {
        return new CrawlDecision(CrawlAction.CRAWL, null, null);
    }
    
    public static CrawlDecision reject(String reason) {
        return new CrawlDecision(CrawlAction.REJECT, reason, null);
    }
    
    public static CrawlDecision retryLater(String reason, Instant retryAt) {
        return new CrawlDecision(CrawlAction.RETRY_LATER, reason, retryAt);
    }
    
    public enum CrawlAction {
        CRAWL,
        RETRY_LATER,
        REJECT
    }
}