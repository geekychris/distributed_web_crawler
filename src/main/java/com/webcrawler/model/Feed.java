package com.webcrawler.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A subscribed RSS/Atom feed. Distinct from a {@link CrawlJob} — the same
 * URL is polled on a recurring cadence and its items are stored as
 * {@link FeedItem}s, not as pages.
 */
public record Feed(
        UUID feedId,
        String url,
        String title,
        String pack,
        int pollIntervalSeconds,
        boolean adaptive,
        boolean followArticles,
        boolean storeFullContent,
        Status status,
        String etag,
        String lastModified,
        Instant lastPolledAt,
        Instant nextPollAt,
        int consecutiveErrors,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status { ACTIVE, PAUSED, ERROR }

    /** Returns a new Feed with the same identity but a fresh nextPollAt. */
    public Feed withNextPoll(Instant lastPolled, Instant nextPoll,
                             String etag, String lastModified, int newErrorCount) {
        Status newStatus = newErrorCount >= 5 ? Status.ERROR : this.status;
        return new Feed(feedId, url, title, pack, pollIntervalSeconds, adaptive,
                followArticles, storeFullContent, newStatus,
                etag, lastModified, lastPolled, nextPoll, newErrorCount,
                createdAt, Instant.now());
    }

    public Feed withStatus(Status newStatus) {
        return new Feed(feedId, url, title, pack, pollIntervalSeconds, adaptive,
                followArticles, storeFullContent, newStatus,
                etag, lastModified, lastPolledAt, nextPollAt, consecutiveErrors,
                createdAt, Instant.now());
    }
}
