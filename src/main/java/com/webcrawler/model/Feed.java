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
        int consecutiveEmpty,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status { ACTIVE, PAUSED, ERROR }

    /** Returns a new Feed with the same identity but a fresh nextPollAt. */
    public Feed withNextPoll(Instant lastPolled, Instant nextPoll,
                             String etag, String lastModified,
                             int newErrorCount, int newEmptyCount) {
        Status newStatus = newErrorCount >= 5 ? Status.ERROR : this.status;
        return new Feed(feedId, url, title, pack, pollIntervalSeconds, adaptive,
                followArticles, storeFullContent, newStatus,
                etag, lastModified, lastPolled, nextPoll,
                newErrorCount, newEmptyCount,
                createdAt, Instant.now());
    }

    public Feed withStatus(Status newStatus) {
        return new Feed(feedId, url, title, pack, pollIntervalSeconds, adaptive,
                followArticles, storeFullContent, newStatus,
                etag, lastModified, lastPolledAt, nextPollAt,
                consecutiveErrors, consecutiveEmpty,
                createdAt, Instant.now());
    }

    /**
     * Compute the next poll interval in seconds given this feed's current
     * state. Grows multiplicatively when consecutive polls yield nothing
     * new (adaptive backoff for quiet feeds) or when consecutive errors
     * pile up (be gentle after a publisher outage). Non-adaptive feeds
     * always poll at their base cadence.
     */
    public long effectiveIntervalSeconds() {
        long base = pollIntervalSeconds;
        if (!adaptive) return base;

        // Quiet-feed backoff: 1.5^n capped at 12x → e.g. 15m base → 3h max.
        double quietMult = consecutiveEmpty > 0
                ? Math.min(Math.pow(1.5, consecutiveEmpty), 12.0)
                : 1.0;
        // Error backoff: 2^n capped at 16x → e.g. 15m base → 4h max.
        double errorMult = consecutiveErrors > 0
                ? Math.min(Math.pow(2.0, consecutiveErrors), 16.0)
                : 1.0;

        long effective = Math.round(base * Math.max(quietMult, errorMult));
        // Absolute ceiling at 6 hours so we don't drift into abandonment.
        return Math.min(effective, 6L * 3600L);
    }
}
