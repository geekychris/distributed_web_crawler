package com.webcrawler.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlRequestTest {

    @Test
    void legacyFiveArgConstructorDefaultsRestToZero() {
        CrawlRequest r = new CrawlRequest("https://a/", 2, "https://parent/", Instant.now(), 1);
        assertEquals(0, r.retryCount());
        assertNull(r.scheduledFor());
        assertNull(r.jobId());
    }

    @Test
    void withRetryPreservesJobId() {
        UUID job = UUID.randomUUID();
        CrawlRequest r = new CrawlRequest("https://a/", 0, null, Instant.now(), 1, 0, null, job);
        CrawlRequest retry = r.withRetry(1, Instant.now().plusSeconds(30));
        assertEquals(job, retry.jobId());
        assertEquals(1, retry.retryCount());
    }

    @Test
    void withJobAssignsAndPreservesRest() {
        CrawlRequest r = new CrawlRequest("https://a/", 3, "p", Instant.EPOCH, 2, 1,
                Instant.EPOCH.plusSeconds(10), null);
        UUID job = UUID.randomUUID();
        CrawlRequest tagged = r.withJob(job);
        assertEquals(job, tagged.jobId());
        assertEquals(3, tagged.depth());
        assertEquals(1, tagged.retryCount());
        assertEquals(Instant.EPOCH.plusSeconds(10), tagged.scheduledFor());
    }

    @Test
    void isReadyToProcessTrueWhenNoSchedule() {
        CrawlRequest r = new CrawlRequest("https://a/", 0, null, Instant.now(), 1);
        assertTrue(r.isReadyToProcess());
    }

    @Test
    void isReadyToProcessFalseUntilScheduledTime() {
        CrawlRequest r = new CrawlRequest("https://a/", 0, null, Instant.now(), 1, 0,
                Instant.now().plusSeconds(60));
        assertFalse(r.isReadyToProcess());
    }
}
