package com.webcrawler.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FeedTest {

    private static Feed sample() {
        Instant now = Instant.now();
        return new Feed(
                UUID.randomUUID(), "https://x/rss", "X", "tech",
                900, false, false, false,
                Feed.Status.ACTIVE, null, null, null, now, 0, now, now);
    }

    @Test
    void withNextPollAdvancesTimestampsAndKeepsIdentity() {
        Feed f = sample();
        Instant now = Instant.now();
        Instant next = now.plusSeconds(900);
        Feed after = f.withNextPoll(now, next, "\"abc\"", "Wed, 27 Aug 2026 GMT", 0);
        assertEquals(f.feedId(), after.feedId());
        assertEquals(f.url(), after.url());
        assertEquals(next, after.nextPollAt());
        assertEquals(now, after.lastPolledAt());
        assertEquals("\"abc\"", after.etag());
    }

    @Test
    void fiveConsecutiveErrorsFlipStatusToError() {
        Feed after = sample().withNextPoll(Instant.now(), Instant.now(), null, null, 5);
        assertEquals(Feed.Status.ERROR, after.status());
    }

    @Test
    void underThresholdKeepsExistingStatus() {
        Feed after = sample().withNextPoll(Instant.now(), Instant.now(), null, null, 4);
        assertEquals(Feed.Status.ACTIVE, after.status());
    }

    @Test
    void withStatusChangesJustStatus() {
        Feed before = sample();
        Feed after = before.withStatus(Feed.Status.PAUSED);
        assertNotEquals(before, after);
        assertEquals(Feed.Status.PAUSED, after.status());
        assertEquals(before.feedId(), after.feedId());
    }
}
