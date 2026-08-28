package com.webcrawler.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CrawlDecisionTest {

    @Test
    void crawlHasNoReasonOrRetry() {
        CrawlDecision d = CrawlDecision.crawl();
        assertEquals(CrawlDecision.CrawlAction.CRAWL, d.action());
        assertNull(d.reason());
        assertNull(d.retryAt());
    }

    @Test
    void rejectCarriesReason() {
        CrawlDecision d = CrawlDecision.reject("robots.txt disallows");
        assertEquals(CrawlDecision.CrawlAction.REJECT, d.action());
        assertEquals("robots.txt disallows", d.reason());
        assertNull(d.retryAt());
    }

    @Test
    void retryLaterCarriesReasonAndTime() {
        Instant at = Instant.now().plusSeconds(60);
        CrawlDecision d = CrawlDecision.retryLater("crawl delay", at);
        assertEquals(CrawlDecision.CrawlAction.RETRY_LATER, d.action());
        assertEquals("crawl delay", d.reason());
        assertNotNull(d.retryAt());
        assertEquals(at, d.retryAt());
    }
}
