package com.webcrawler.service;

import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.webcrawler.model.CrawlRequest;
import com.webcrawler.model.Feed;
import com.webcrawler.model.FeedItem;
import com.webcrawler.queue.FeedItemPublisher;
import com.webcrawler.queue.UrlQueue;
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
 * shared virtual-thread executor.
 *
 * <p>Adaptive behaviour (feeds with {@code adaptive=true}):
 * <ul>
 *   <li>New items returned → reset backoff to baseline cadence.</li>
 *   <li>304 Not Modified OR all items already dedup'd → increment
 *       {@code consecutiveEmpty}; effective interval grows 1.5^n.</li>
 *   <li>HTTP error or exception → increment {@code consecutiveErrors};
 *       effective interval grows 2^n. Five in a row flips the feed to
 *       {@link Feed.Status#ERROR} so the poller skips it.</li>
 *   <li>Absolute ceiling at 6 hours — see
 *       {@link Feed#effectiveIntervalSeconds()}.</li>
 * </ul>
 *
 * <p>If a feed has {@code follow_articles=true}, each NEW item's URL is
 * enqueued through the normal crawl pipeline, its host is trusted for the
 * session in {@link ScopeService}, and the crawl request carries
 * {@code sourceFeedItemId} so the downstream page event can be joined
 * back to the feed item.
 */
@Component
public class FeedPoller {
    private static final Logger logger = LoggerFactory.getLogger(FeedPoller.class);
    private static final int POLL_TICK_SECONDS = 10;
    private static final int CONTENT_SNIPPET_CAP = 64 * 1024;

    private final FeedRepository repo;
    private final FeedItemPublisher publisher;
    private final UrlQueue urlQueue;
    private final ScopeService scope;
    private final ExecutorService fetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    public FeedPoller(FeedRepository repo,
                      FeedItemPublisher publisher,
                      UrlQueue urlQueue,
                      ScopeService scope) {
        this.repo = repo;
        this.publisher = publisher;
        this.urlQueue = urlQueue;
        this.scope = scope;
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
        int emptyPolls = feed.consecutiveEmpty();
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

            Feed updated;
            if (status == 304) {
                logger.debug("Feed {} not modified (304)", feed.url());
                errors = 0;
                emptyPolls = emptyPolls + 1;
                updated = feed.withNextPoll(pollAt,
                        nextPollAt(pollAt, feed, errors, emptyPolls),
                        newEtag, newLastModified, errors, emptyPolls);
            } else if (status >= 200 && status < 300) {
                int added = parseAndStore(feed, resp.body(), pollAt);
                errors = 0;
                emptyPolls = added > 0 ? 0 : emptyPolls + 1;
                logger.info("Feed {} → {} new item(s) (status {}, empty streak {}, error streak {})",
                        feed.url(), added, status, emptyPolls, errors);
                updated = feed.withNextPoll(pollAt,
                        nextPollAt(pollAt, feed, errors, emptyPolls),
                        newEtag, newLastModified, errors, emptyPolls);
            } else {
                errors++;
                String errBody = new String(resp.body(), StandardCharsets.UTF_8);
                if (errBody.length() > 500) errBody = errBody.substring(0, 500) + "…";
                String errMsg = "HTTP " + status
                        + (errBody.isBlank() ? "" : " — " + errBody.replaceAll("\\s+", " ").trim());
                logger.warn("Feed {} returned HTTP {} (errors={}): {}",
                        feed.url(), status, errors, errMsg);
                updated = feed.withPollError(pollAt,
                        nextPollAt(pollAt, feed, errors, emptyPolls),
                        newEtag, newLastModified, errors, emptyPolls, errMsg);
            }
            repo.updatePollResult(updated);
        } catch (Exception e) {
            errors++;
            String errMsg = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "(no message)" : e.getMessage());
            logger.warn("Feed poll failed for {} ({}): {}", feed.url(), errors, errMsg);
            Feed updated = feed.withPollError(pollAt,
                    nextPollAt(pollAt, feed, errors, emptyPolls),
                    etag, lastModified, errors, emptyPolls, errMsg);
            repo.updatePollResult(updated);
        }
    }

    /** Compute the reservation timestamp using the current empty/error state. */
    static Instant nextPollAt(Instant pollAt, Feed baseFeed, int errors, int emptyPolls) {
        Feed simulated = new Feed(
                baseFeed.feedId(), baseFeed.url(), baseFeed.title(), baseFeed.pack(),
                baseFeed.pollIntervalSeconds(), baseFeed.adaptive(),
                baseFeed.followArticles(), baseFeed.storeFullContent(),
                baseFeed.status(), baseFeed.etag(), baseFeed.lastModified(),
                baseFeed.lastPolledAt(), baseFeed.nextPollAt(),
                errors, emptyPolls, baseFeed.createdAt(), baseFeed.updatedAt());
        return pollAt.plusSeconds(simulated.effectiveIntervalSeconds());
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
                    if (feed.followArticles() && item.url() != null && !item.url().isBlank()) {
                        followItemUrl(feed, item);
                    }
                }
            } catch (Exception e) {
                logger.warn("Persist item {} for feed {}: {}", item.itemId(), feed.url(), e.getMessage());
            }
        }
        return newCount;
    }

    /**
     * Enqueue the item's link URL through the normal crawl pipeline. The
     * item's host joins the runtime scope (the user opted into the feed so
     * downstream URLs are pre-trusted), and the CrawlRequest carries
     * {@code sourceFeedItemId} so the page event can be joined back.
     */
    private void followItemUrl(Feed feed, FeedItem item) {
        try {
            scope.trustSubmission(item.url(), ScopeService.Mode.HOST);
            CrawlRequest crawl = new CrawlRequest(
                    item.url(), 0, feed.url(), Instant.now(), 1, 0, null, null, item.itemId());
            urlQueue.enqueue(crawl);
            logger.debug("Enqueued follow-article {} (feed={}, item={})",
                    item.url(), feed.feedId(), item.itemId());
        } catch (Exception e) {
            logger.warn("Failed to enqueue follow-article for item {}: {}",
                    item.itemId(), e.getMessage());
        }
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
                : stripControlChars(contentBuf.length() > CONTENT_SNIPPET_CAP
                        ? contentBuf.substring(0, CONTENT_SNIPPET_CAP) + "\n<truncated>"
                        : contentBuf.toString());

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
                truncate(summary), contentSnippet, author,
                new LinkedHashSet<>(categories),
                enclosures,
                published, updated, pollAt, null);
    }

    private static String truncate(String s) {
        s = stripControlChars(s);
        if (s == null) return null;
        if (s.length() <= CONTENT_SNIPPET_CAP) return s;
        return s.substring(0, CONTENT_SNIPPET_CAP) + "\n<truncated>";
    }

    /**
     * Strip ASCII control characters (except \t / \n / \r) — some feeds ship
     * raw form feeds and vertical tabs that break strict JSON parsers when we
     * later serve the item via /api/feeds/{id}/items. Cheap defensive scrub.
     */
    static String stripControlChars(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            if (c == 0x7f) continue;
            sb.append(c);
        }
        return sb.toString();
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
