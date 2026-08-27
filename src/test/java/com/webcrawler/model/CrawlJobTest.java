package com.webcrawler.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlJobTest {

    private static CrawlJob sample() {
        Instant now = Instant.now();
        return new CrawlJob(
                UUID.randomUUID(), "job", CrawlJob.Status.PENDING,
                Set.of("https://a/"), Set.of("a\\.com"), Set.of(),
                3, 10, 5, 2, now, now);
    }

    @Test
    void withStatusPreservesEverythingExceptStatusAndUpdatedAt() {
        CrawlJob before = sample();
        CrawlJob after = before.withStatus(CrawlJob.Status.RUNNING);
        assertEquals(before.jobId(), after.jobId());
        assertEquals(before.name(), after.name());
        assertEquals(before.seedUrls(), after.seedUrls());
        assertEquals(before.allowedDomains(), after.allowedDomains());
        assertEquals(before.maxDepth(), after.maxDepth());
        assertEquals(before.maxPages(), after.maxPages());
        assertEquals(before.maxPagesPerDomain(), after.maxPagesPerDomain());
        assertEquals(before.maxDomains(), after.maxDomains());
        assertEquals(before.createdAt(), after.createdAt());
        assertEquals(CrawlJob.Status.RUNNING, after.status());
        assertTrue(!after.updatedAt().isBefore(before.updatedAt()));
    }

    @Test
    void statusChangeReturnsFreshInstance() {
        CrawlJob before = sample();
        CrawlJob after = before.withStatus(CrawlJob.Status.CANCELLED);
        assertNotEquals(before, after);
        assertEquals(CrawlJob.Status.CANCELLED, after.status());
    }
}
