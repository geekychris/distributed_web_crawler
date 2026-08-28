package com.webcrawler.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.webcrawler.model.CrawlJob;
import com.webcrawler.model.Feed;
import com.webcrawler.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Aggregated counters and health snapshots for the Stats/Insights UI and
 * external monitors. Expensive queries (COUNT(*) on feed_items) are cached
 * short-term; small lists (feeds, jobs) are counted in-memory on every
 * request.
 */
@Service
public class StatsService {
    private static final Logger logger = LoggerFactory.getLogger(StatsService.class);

    private final CqlSession session;
    private final StorageService storage;
    private final FeedRepository feeds;
    private final CrawlJobService jobs;
    private final ActivityFeed activity;
    private final PreparedStatement countFeedItems;

    private volatile long cachedFeedItemCount = 0L;
    private volatile long cachedFeedItemCountAt = 0L;

    @Autowired
    public StatsService(CqlSession session, StorageService storage,
                        FeedRepository feeds, CrawlJobService jobs,
                        ActivityFeed activity) {
        this.session = session;
        this.storage = storage;
        this.feeds = feeds;
        this.jobs = jobs;
        this.activity = activity;
        this.countFeedItems = session.prepare("SELECT COUNT(*) FROM feed_items");
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", Instant.now());

        // Storage counts
        try { out.put("pages_total", storage.getPageCount().get(15, TimeUnit.SECONDS)); }
        catch (Exception e) { out.put("pages_total", -1L); }
        out.put("feed_items_total", feedItemCount());

        // Feeds by status (small table, cheap)
        Map<String, Integer> feedsByStatus = new TreeMap<>();
        int totalFeeds = 0;
        int adaptiveFeeds = 0;
        int followingFeeds = 0;
        List<Feed> allFeeds = List.of();
        try {
            allFeeds = feeds.listAll();
            totalFeeds = allFeeds.size();
            for (Feed f : allFeeds) {
                feedsByStatus.merge(f.status().name(), 1, Integer::sum);
                if (f.adaptive()) adaptiveFeeds++;
                if (f.followArticles()) followingFeeds++;
            }
        } catch (Exception e) {
            logger.warn("Feeds list failed: {}", e.getMessage());
        }
        Map<String, Object> feedStats = new LinkedHashMap<>();
        feedStats.put("total", totalFeeds);
        feedStats.put("by_status", feedsByStatus);
        feedStats.put("adaptive", adaptiveFeeds);
        feedStats.put("follow_articles", followingFeeds);
        out.put("feeds", feedStats);

        // Jobs by status
        Map<String, Integer> jobsByStatus = new TreeMap<>();
        int totalJobs = 0;
        try {
            for (CrawlJob j : jobs.listAll()) {
                jobsByStatus.merge(j.status().name(), 1, Integer::sum);
                totalJobs++;
            }
        } catch (Exception e) {
            logger.warn("Jobs list failed: {}", e.getMessage());
        }
        Map<String, Object> jobStats = new LinkedHashMap<>();
        jobStats.put("total", totalJobs);
        jobStats.put("by_status", jobsByStatus);
        out.put("jobs", jobStats);

        // Activity rate over the last 5 minutes (from the ring buffer).
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        int crawled = 0, rejected = 0, error = 0;
        for (ActivityFeed.Event e : activity.recent(200)) {
            if (e.at().isBefore(cutoff)) continue;
            switch (e.kind()) {
                case CRAWLED -> crawled++;
                case REJECTED -> rejected++;
                case ERROR -> error++;
            }
        }
        Map<String, Object> act = new LinkedHashMap<>();
        act.put("window_minutes", 5);
        act.put("crawled", crawled);
        act.put("rejected", rejected);
        act.put("error", error);
        act.put("pages_per_minute", Math.round(crawled / 5.0 * 10.0) / 10.0);
        out.put("activity", act);

        // Top 10 problem feeds by error+empty streak (help the operator).
        List<Map<String, Object>> unhealthy = allFeeds.stream()
                .filter(f -> f.consecutiveErrors() > 0 || f.consecutiveEmpty() > 3)
                .sorted((a, b) -> Integer.compare(
                        b.consecutiveErrors() * 10 + b.consecutiveEmpty(),
                        a.consecutiveErrors() * 10 + a.consecutiveEmpty()))
                .limit(10)
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("feed_id", f.feedId());
                    m.put("title", f.title() == null ? f.url() : f.title());
                    m.put("url", f.url());
                    m.put("status", f.status().name());
                    m.put("consecutive_errors", f.consecutiveErrors());
                    m.put("consecutive_empty", f.consecutiveEmpty());
                    m.put("last_polled_at", f.lastPolledAt());
                    m.put("next_poll_at", f.nextPollAt());
                    return m;
                }).toList();
        out.put("unhealthy_feeds", unhealthy);

        return out;
    }

    private long feedItemCount() {
        long now = System.currentTimeMillis();
        if (now - cachedFeedItemCountAt < 30_000L) return cachedFeedItemCount;
        try {
            var stmt = countFeedItems.bind().setTimeout(java.time.Duration.ofSeconds(15));
            Row r = session.execute(stmt).one();
            long count = r == null ? 0L : r.getLong(0);
            cachedFeedItemCount = count;
            cachedFeedItemCountAt = now;
            return count;
        } catch (Exception e) {
            logger.debug("feed_items count failed ({}); returning cached {}",
                    e.getMessage(), cachedFeedItemCount);
            return cachedFeedItemCount;
        }
    }
}
