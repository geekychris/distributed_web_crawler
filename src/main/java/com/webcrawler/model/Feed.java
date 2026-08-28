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
        String lastErrorMessage,
        Instant lastErrorAt,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status { ACTIVE, PAUSED, ERROR }

    /** Legacy constructor that omits the error fields — used by callers that
     *  don't need them (e.g. tests, initial subscribe from REST). */
    public Feed(UUID feedId, String url, String title, String pack,
                int pollIntervalSeconds, boolean adaptive, boolean followArticles,
                boolean storeFullContent, Status status, String etag, String lastModified,
                Instant lastPolledAt, Instant nextPollAt, int consecutiveErrors,
                int consecutiveEmpty, Instant createdAt, Instant updatedAt) {
        this(feedId, url, title, pack, pollIntervalSeconds, adaptive, followArticles,
             storeFullContent, status, etag, lastModified, lastPolledAt, nextPollAt,
             consecutiveErrors, consecutiveEmpty, null, null, createdAt, updatedAt);
    }

    /** Copy the feed with fresh poll state. Keeps last-error info intact —
     *  callers that see an error should invoke {@link #withPollError}. */
    public Feed withNextPoll(Instant lastPolled, Instant nextPoll,
                             String etag, String lastModified,
                             int newErrorCount, int newEmptyCount) {
        Status newStatus = newErrorCount >= 5 ? Status.ERROR : this.status;
        return new Feed(feedId, url, title, pack, pollIntervalSeconds, adaptive,
                followArticles, storeFullContent, newStatus,
                etag, lastModified, lastPolled, nextPoll,
                newErrorCount, newEmptyCount, lastErrorMessage, lastErrorAt,
                createdAt, Instant.now());
    }

    /** Copy the feed with fresh poll state AND updated last-error info. */
    public Feed withPollError(Instant lastPolled, Instant nextPoll,
                              String etag, String lastModified,
                              int newErrorCount, int newEmptyCount,
                              String errorMessage) {
        Status newStatus = newErrorCount >= 5 ? Status.ERROR : this.status;
        return new Feed(feedId, url, title, pack, pollIntervalSeconds, adaptive,
                followArticles, storeFullContent, newStatus,
                etag, lastModified, lastPolled, nextPoll,
                newErrorCount, newEmptyCount, errorMessage, Instant.now(),
                createdAt, Instant.now());
    }

    public Feed withStatus(Status newStatus) {
        return new Feed(feedId, url, title, pack, pollIntervalSeconds, adaptive,
                followArticles, storeFullContent, newStatus,
                etag, lastModified, lastPolledAt, nextPollAt,
                consecutiveErrors, consecutiveEmpty, lastErrorMessage, lastErrorAt,
                createdAt, Instant.now());
    }

    /**
     * Compute the next poll interval in seconds given this feed's current
     * state.
     */
    public long effectiveIntervalSeconds() {
        long base = pollIntervalSeconds;
        if (!adaptive) return base;

        double quietMult = consecutiveEmpty > 0
                ? Math.min(Math.pow(1.5, consecutiveEmpty), 12.0)
                : 1.0;
        double errorMult = consecutiveErrors > 0
                ? Math.min(Math.pow(2.0, consecutiveErrors), 16.0)
                : 1.0;

        long effective = Math.round(base * Math.max(quietMult, errorMult));
        return Math.min(effective, 6L * 3600L);
    }
}
