package com.webcrawler.service;

import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.webcrawler.model.Feed;
import com.webcrawler.model.FeedItem;
import com.webcrawler.queue.FeedItemPublisher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-cadence poller. Every {@link #POLL_TICK_SECONDS} it lists all feeds,
 * picks the ones whose {@code next_poll_at} is due, and processes them on a
 * shared virtual-thread executor. Per-feed work: conditional GET (ETag /
 * Last-Modified) → ROME parse → dedup via {@code feed_items_by_id} → persist
 * new items → publish CloudEvent.
 *
 * <p>Adaptive backoff is not implemented in Phase 1 — the reader honours the
 * feed's configured pollIntervalSeconds only.
 */
@Component
public class FeedPoller {
    private static final Logger logger = LoggerFactory.getLogger(FeedPoller.class);
    private static final int POLL_TICK_SECONDS = 10;
    private static final int CONTENT_SNIPPET_CAP = 64 * 1024;

    private final FeedRepository repo;
    private final FeedItemPublisher publisher;
    private final ExecutorService fetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    public FeedPoller(FeedRepository repo, FeedItemPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "PT" + POLL_TICK_SECONDS + "S")
    public void tick() {
        List<Feed> due;
        try {
            Instant now = Instant.now();
            due = repo.listAll().stream()
                    .filter(f -> f.status() == Feed.Status.ACTIVE)
                    .filter(f -> f.nextPollAt() == null || !f.nextPollAt().isAfter(now))
                    .toList();
        } catch (Exception e) {
            logger.warn("Feed tick failed to list feeds: {}", e.getMessage());
            return;
        }
        if (due.isEmpty()) return;
        logger.info("Feed poll tick — {} due", due.size());
        for (Feed feed : due) {
            fetchExecutor.execute(() -> poll(feed));
        }
    }

    public void poll(Feed feed) {
        Instant pollAt = Instant.now();
        int errors = feed.consecutiveErrors();
        String etag = feed.etag();
        String lastModified = feed.lastModified();
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(feed.url()))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "DistributedCrawler-Feeds/1.0")
                    .header("Accept",
                            "application/atom+xml,application/rss+xml,application/xml;q=0.9,text/xml;q=0.8,*/*;q=0.5")
                    .GET();
            if (etag != null && !etag.isBlank()) req.header("If-None-Match", etag);
            if (lastModified != null && !lastModified.isBlank())
                req.header("If-Modified-Since", lastModified);

            HttpResponse<byte[]> resp = httpClient.send(
                    req.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            String newEtag = resp.headers().firstValue("ETag").orElse(etag);
            String newLastModified = resp.headers().firstValue("Last-Modified").orElse(lastModified);

            if (status == 304) {
                logger.debug("Feed {} not modified (304)", feed.url());
                errors = 0;
            } else if (status >= 200 && status < 300) {
                int added = parseAndStore(feed, resp.body(), pollAt);
                errors = 0;
                logger.info("Feed {} → {} new item(s) (status {})", feed.url(), added, status);
            } else {
                errors++;
                logger.warn("Feed {} returned HTTP {} (errors={})", feed.url(), status, errors);
            }

            Feed updated = feed.withNextPoll(
                    pollAt,
                    pollAt.plusSeconds(feed.pollIntervalSeconds()),
                    newEtag, newLastModified, errors);
            repo.updatePollResult(updated);
        } catch (Exception e) {
            errors++;
            logger.warn("Feed poll failed for {} ({}): {}", feed.url(), errors, e.getMessage());
            Feed updated = feed.withNextPoll(
                    pollAt, pollAt.plusSeconds(feed.pollIntervalSeconds()),
                    etag, lastModified, errors);
            repo.updatePollResult(updated);
        }
    }

    private int parseAndStore(Feed feed, byte[] body, Instant pollAt) {
        SyndFeed rss;
        try {
            rss = new SyndFeedInput().build(new InputSource(
                    new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            logger.warn("Feed {} parse failed: {}", feed.url(), e.getMessage());
            return 0;
        }
        int newCount = 0;
        for (SyndEntry entry : rss.getEntries()) {
            FeedItem item = toItem(feed, entry, pollAt);
            if (item == null) continue;
            try {
                if (repo.recordItemIfNew(item)) {
                    publisher.publish(feed, item, pollAt);
                    newCount++;
                }
            } catch (Exception e) {
                logger.warn("Persist item {} for feed {}: {}", item.itemId(), feed.url(), e.getMessage());
            }
        }
        return newCount;
    }

    private FeedItem toItem(Feed feed, SyndEntry entry, Instant pollAt) {
        String url = entry.getLink();
        String title = entry.getTitle();
        Instant published = entry.getPublishedDate() != null
                ? entry.getPublishedDate().toInstant() : null;
        Instant updated = entry.getUpdatedDate() != null
                ? entry.getUpdatedDate().toInstant() : null;
        String itemId = entry.getUri() != null && !entry.getUri().isBlank()
                ? entry.getUri()
                : sha256(url + "|" + (published != null ? published.toString() : ""));

        String summary = entry.getDescription() != null ? entry.getDescription().getValue() : null;

        StringBuilder contentBuf = new StringBuilder();
        for (SyndContent c : entry.getContents()) {
            if (c.getValue() != null) contentBuf.append(c.getValue()).append('\n');
        }
        String contentSnippet = contentBuf.length() == 0 ? null
                : contentBuf.length() > CONTENT_SNIPPET_CAP
                        ? contentBuf.substring(0, CONTENT_SNIPPET_CAP) + "\n<truncated>"
                        : contentBuf.toString();

        String author = entry.getAuthor();

        var categories = new HashSet<String>();
        for (SyndCategory c : entry.getCategories()) {
            if (c.getName() != null && !c.getName().isBlank()) categories.add(c.getName());
        }

        List<java.util.Map<String, String>> enclosures = new ArrayList<>();
        for (SyndEnclosure e : entry.getEnclosures()) {
            enclosures.add(FeedRepository.enclosure(e.getUrl(), e.getType(), e.getLength()));
        }

        return new FeedItem(
                feed.feedId(), itemId, url, title,
                truncate(summary),
                contentSnippet,
                author,
                new LinkedHashSet<>(categories),
                enclosures,
                published, updated, pollAt,
                /*followedPageUrl*/ null);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= CONTENT_SNIPPET_CAP) return s;
        return s.substring(0, CONTENT_SNIPPET_CAP) + "\n<truncated>";
    }

    static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return "sha256:" + sb;
        } catch (Exception e) {
            throw new IllegalStateException("sha256 unavailable", e);
        }
    }

    @PreDestroy
    void shutdown() {
        fetchExecutor.shutdown();
        try {
            if (!fetchExecutor.awaitTermination(15, TimeUnit.SECONDS)) fetchExecutor.shutdownNow();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fetchExecutor.shutdownNow();
        }
    }
}
