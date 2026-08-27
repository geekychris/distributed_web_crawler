package com.webcrawler.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityFeedTest {

    @Test
    void recentReturnsMostRecentFirst() {
        ActivityFeed feed = new ActivityFeed();
        feed.crawled("https://a/", 200, 10, 8);
        feed.rejected("https://b/", "out of scope");
        feed.error("https://c/", "timeout");

        List<ActivityFeed.Event> events = feed.recent(10);
        assertEquals(3, events.size());
        assertEquals("https://c/", events.get(0).url());
        assertEquals(ActivityFeed.Kind.ERROR, events.get(0).kind());
        assertEquals("https://a/", events.get(2).url());
    }

    @Test
    void limitHonoured() {
        ActivityFeed feed = new ActivityFeed();
        for (int i = 0; i < 20; i++) feed.crawled("https://u/" + i, 200, 0, 0);
        assertEquals(5, feed.recent(5).size());
        assertEquals(20, feed.recent(100).size());
    }

    @Test
    void ringBufferCapsAt200() {
        ActivityFeed feed = new ActivityFeed();
        for (int i = 0; i < 250; i++) feed.crawled("https://u/" + i, 200, 0, 0);
        List<ActivityFeed.Event> events = feed.recent(1_000);
        assertEquals(200, events.size());
        // Oldest retained event is index 50 (250-200).
        assertEquals("https://u/50", events.get(events.size() - 1).url());
    }

    @Test
    void crawledDetailShowsFoundAndFollowed() {
        ActivityFeed feed = new ActivityFeed();
        feed.crawled("https://u/", 200, 10, 3);
        String detail = feed.recent(1).get(0).detail();
        assertTrue(detail.contains("http 200"));
        assertTrue(detail.contains("10 found"));
        assertTrue(detail.contains("3 to crawl"));
    }
}
