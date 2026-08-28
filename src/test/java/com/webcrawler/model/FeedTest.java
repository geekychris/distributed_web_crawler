package com.webcrawler.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedTest {

    private static Feed sample(boolean adaptive, int errors, int empty) {
        Instant now = Instant.now();
        return new Feed(
                UUID.randomUUID(), "https://x/rss", "X", "tech",
                900, adaptive, false, false,
                Feed.Status.ACTIVE, null, null, null, now, errors, empty, now, now);
    }

    @Test
    void withNextPollAdvancesTimestampsAndKeepsIdentity() {
        Feed f = sample(false, 0, 0);
        Instant now = Instant.now();
        Instant next = now.plusSeconds(900);
        Feed after = f.withNextPoll(now, next, "\"abc\"", "Wed, 27 Aug 2026 GMT", 0, 0);
        assertEquals(f.feedId(), after.feedId());
        assertEquals(f.url(), after.url());
        assertEquals(next, after.nextPollAt());
        assertEquals(now, after.lastPolledAt());
        assertEquals("\"abc\"", after.etag());
    }

    @Test
    void fiveConsecutiveErrorsFlipStatusToError() {
        Feed after = sample(true, 0, 0)
                .withNextPoll(Instant.now(), Instant.now(), null, null, 5, 0);
        assertEquals(Feed.Status.ERROR, after.status());
    }

    @Test
    void underThresholdKeepsExistingStatus() {
        Feed after = sample(true, 0, 0)
                .withNextPoll(Instant.now(), Instant.now(), null, null, 4, 0);
        assertEquals(Feed.Status.ACTIVE, after.status());
    }

    @Test
    void withStatusChangesJustStatus() {
        Feed before = sample(true, 0, 0);
        Feed after = before.withStatus(Feed.Status.PAUSED);
        assertNotEquals(before, after);
        assertEquals(Feed.Status.PAUSED, after.status());
        assertEquals(before.feedId(), after.feedId());
    }

    // ---- Adaptive interval math ----

    @Test
    void nonAdaptiveIgnoresBackoffState() {
        assertEquals(900L, sample(false, 3, 5).effectiveIntervalSeconds());
    }

    @Test
    void adaptiveBaselineWithNoErrorsOrEmpties() {
        assertEquals(900L, sample(true, 0, 0).effectiveIntervalSeconds());
    }

    @Test
    void adaptiveQuietBackoffGrowsAt1_5x() {
        // 1.5^1 = 1.5 → 1350s
        assertEquals(1350L, sample(true, 0, 1).effectiveIntervalSeconds());
        // 1.5^2 = 2.25 → 2025s
        assertEquals(2025L, sample(true, 0, 2).effectiveIntervalSeconds());
    }

    @Test
    void adaptiveErrorBackoffGrowsAt2x() {
        // 2^1 = 2 → 1800s
        assertEquals(1800L, sample(true, 1, 0).effectiveIntervalSeconds());
        // 2^3 = 8 → 7200s
        assertEquals(7200L, sample(true, 3, 0).effectiveIntervalSeconds());
    }

    @Test
    void errorBackoffDominatesQuietBackoffWhenLarger() {
        // error mult 4 (2^2) vs empty mult ~3.375 (1.5^3) — errors win
        long got = sample(true, 2, 3).effectiveIntervalSeconds();
        assertEquals(900L * 4, got);
    }

    @Test
    void adaptiveErrorMultiplierCappedAt16x() {
        // 10 consecutive errors would be 2^10 = 1024x baseline unbounded, but
        // the multiplier is capped at 16 (so 900 * 16 = 14400s = 4h).
        long got = sample(true, 10, 0).effectiveIntervalSeconds();
        assertEquals(900L * 16, got);
        assertTrue(got < 900L * 1024);
    }

    @Test
    void adaptiveIntervalCappedAtSixHoursHardCeiling() {
        // Simulate a slow feed at 30-minute baseline with a huge error
        // streak — 1800s * 16 = 8h uncapped, gets pinned at 6h.
        Feed f = new Feed(
                UUID.randomUUID(), "u", "t", null,
                1800, true, false, false,
                Feed.Status.ACTIVE, null, null, null, Instant.now(),
                10, 0, Instant.now(), Instant.now());
        assertEquals(6L * 3600L, f.effectiveIntervalSeconds());
    }
}
