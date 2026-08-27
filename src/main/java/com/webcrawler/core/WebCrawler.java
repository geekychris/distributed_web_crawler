package com.webcrawler.core;

import com.webcrawler.config.CrawlerProperties;
import com.webcrawler.model.CrawlRequest;
import com.webcrawler.model.PageContent;
import com.webcrawler.queue.BatchConsumer;
import com.webcrawler.queue.UrlQueue;
import com.webcrawler.service.ActivityFeed;
import com.webcrawler.service.CrawlJobService;
import com.webcrawler.service.ScopeService;
import com.webcrawler.storage.StorageService;
import crawlercommons.filters.basic.BasicURLNormalizer;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class WebCrawler {
    private static final Logger logger = LoggerFactory.getLogger(WebCrawler.class);

    private final CrawlerProperties config;
    private final UrlQueue urlQueue;
    private final StorageService storageService;
    private final CrawlJobService jobs;
    private final ScopeService scope;
    private final ActivityFeed activity;

    private final ExecutorService fetchExecutor;
    private final ScheduledExecutorService retryScheduler;
    private final ExecutorService workerExecutor;

    /** Next allowed fetch time per host — atomically reserved before sleeping. */
    private final Map<String, Instant> nextFetchAt = new ConcurrentHashMap<>();
    /** Per-host lock so politeness reservation is serialized within one process. */
    private final Map<String, Object> hostLocks = new ConcurrentHashMap<>();
    private final Map<String, SimpleRobotRules> robotsCache = new ConcurrentHashMap<>();
    private final Set<String> sitemapsSeen = ConcurrentHashMap.newKeySet();

    private final BasicURLNormalizer urlNormalizer = new BasicURLNormalizer();
    private final SimpleRobotRulesParser robotsParser = new SimpleRobotRulesParser();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile boolean isRunning;

    @Autowired
    public WebCrawler(CrawlerProperties config,
                      UrlQueue urlQueue,
                      StorageService storageService,
                      CrawlJobService jobs,
                      ScopeService scope,
                      ActivityFeed activity) {
        this.config = config;
        this.urlQueue = urlQueue;
        this.storageService = storageService;
        this.jobs = jobs;
        this.scope = scope;
        this.activity = activity;
        // Shared virtual-thread executor for per-URL work — one instance,
        // never recreated per batch. Fixes the exit-137 OOM caused by
        // creating a fresh executor per URL that never got shut down.
        this.fetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.retryScheduler = Executors.newScheduledThreadPool(2);
        this.workerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PostConstruct
    private void initialize() {
        logger.info("WebCrawler initialised (maxDepth={}, maxConcurrent={}, robots={}, jobs={})",
                config.maxDepth(), config.maxConcurrentRequests(),
                config.respectRobotsTxt(), config.getSeedUrlSet().size());
    }

    public synchronized void start() {
        if (isRunning) {
            logger.info("WebCrawler already running");
            return;
        }
        isRunning = true;
        logger.info("Starting WebCrawler");
        try {
            seedUrlQueue();
            int workerCount = Math.max(1, config.maxConcurrentRequests() / 5);
            logger.info("Starting {} crawl worker(s)", workerCount);
            for (int i = 0; i < workerCount; i++) {
                workerExecutor.execute(this::crawlLoop);
            }
        } catch (RuntimeException e) {
            // Roll back the running flag so a subsequent start() attempt can
            // retry initialisation.
            isRunning = false;
            throw e;
        }
    }

    public synchronized void stop() {
        if (!isRunning) return;
        logger.info("Stopping WebCrawler");
        isRunning = false;
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void seedUrlQueue() {
        Set<String> seeds = config.getSeedUrlSet();
        logger.info("Seeding URL queue with {} URL(s)", seeds.size());
        for (String url : seeds) {
            String normalized = normalize(url);
            if (normalized == null) continue;
            CrawlRequest request = new CrawlRequest(
                    normalized, 0, null, Instant.now(), 1, 0, null, null);
            try {
                urlQueue.enqueue(request).get(10, TimeUnit.SECONDS);
                logger.info("Seeded: {}", normalized);
            } catch (Exception e) {
                logger.warn("Failed to seed URL {}: {}", normalized, e.getMessage());
            }
        }
    }

    /**
     * Per-worker crawl loop: each worker owns its own BatchConsumer. Kafka
     * consumers are single-thread-affinity, so poll+commit must happen on the
     * same thread — this is that thread.
     */
    private void crawlLoop() {
        Thread current = Thread.currentThread();
        logger.info("Crawl loop starting: {}", current.getName());
        try (BatchConsumer consumer = urlQueue.openBatchConsumer()) {
            while (isRunning) {
                try {
                    BatchConsumer.Batch batch = consumer.poll(config.pollTimeout());
                    // No records → no offsets to commit; loop again.
                    if (!batch.hasRecords()) continue;
                    if (batch.isEmpty()) {
                        // All records failed to parse — commit anyway so we
                        // don't re-poll broken messages forever.
                        batch.commit().run();
                        continue;
                    }

                    logger.info("Processing batch of {} URL(s)", batch.size());
                    processBatch(batch.requests());

                    // Commit only after processing — losses on crash are
                    // bounded to at most one batch.
                    batch.commit().run();
                } catch (org.apache.kafka.common.errors.WakeupException we) {
                    // Woken up by shutdown — exit cleanly.
                    break;
                } catch (Exception e) {
                    logger.error("Crawl loop iteration failed", e);
                    sleepMillis(1_000);
                }
            }
        } catch (Exception e) {
            logger.error("Crawl loop terminated abnormally", e);
        }
        logger.info("Crawl loop exiting: {}", current.getName());
    }

    private void processBatch(List<CrawlRequest> requests) {
        CompletableFuture<?>[] tasks = requests.stream()
                .map(req -> CompletableFuture.runAsync(() -> {
                    try {
                        processSingleRequest(req);
                    } catch (Exception e) {
                        logger.error("URL failed: {}", req.url(), e);
                    }
                }, fetchExecutor))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(tasks)
                    .orTimeout(config.batchTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException | CancellationException e) {
            logger.warn("Batch exceeded timeout {} — committing partial progress",
                    config.batchTimeout());
        }
    }

    private void processSingleRequest(CrawlRequest request) {
        if (!request.isReadyToProcess()) {
            urlQueue.enqueue(request);
            return;
        }
        CrawlDecision decision = shouldCrawl(request);
        switch (decision.action()) {
            case CRAWL -> crawlUrlAux(request);
            case RETRY_LATER -> scheduleRetry(request, decision.retryAt());
            case REJECT -> {
                logger.info("Rejected {} — {}", request.url(), decision.reason());
                activity.rejected(request.url(), decision.reason());
            }
        }
    }

    private CrawlDecision shouldCrawl(CrawlRequest request) {
        try {
            URL url = new URL(request.url());
            String domain = url.getHost();

            if (request.depth() > config.maxDepth()) {
                return CrawlDecision.reject("depth " + request.depth() + " > maxDepth " + config.maxDepth());
            }
            if (request.retryCount() > config.maxRetryAttempts()) {
                return CrawlDecision.reject("retry limit " + config.maxRetryAttempts());
            }

            if (!scope.allows(request.url())) {
                return CrawlDecision.reject(
                        "domain '" + domain + "' not in scope (add via /api/crawler/url or the Add URLs tab to trust it)");
            }

            if (config.respectRobotsTxt()) {
                SimpleRobotRules rules = getRobotsRules(url, request.jobId());
                if (!rules.isAllowed(request.url())) {
                    return CrawlDecision.reject("robots.txt disallows");
                }
            }

            // Politeness: atomically reserve the next allowed fetch time for
            // this host BEFORE sleeping. Two concurrent tasks targeting the
            // same host used to both read the same lastVisit, sleep the same
            // duration, and fire simultaneously — this serialises them via
            // per-host reservations spaced by crawlDelay.
            try {
                reserveAndAwaitPoliteness(domain);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return CrawlDecision.reject("interrupted during politeness delay");
            }

            return CrawlDecision.crawl();
        } catch (Exception e) {
            logger.warn("Validation failed for {}: {}", request.url(), e.getMessage());
            return CrawlDecision.reject("validation error: " + e.getMessage());
        }
    }

    /**
     * Atomically reserve the next-allowed fetch time for the host, then sleep
     * until it. Guarantees that no two concurrent tasks for the same host
     * fire less than crawlDelay apart within one process.
     */
    private void reserveAndAwaitPoliteness(String domain) throws InterruptedException {
        Object lock = hostLocks.computeIfAbsent(domain, k -> new Object());
        Instant fireAt;
        synchronized (lock) {
            Instant now = Instant.now();
            Instant scheduled = nextFetchAt.getOrDefault(domain, now);
            fireAt = scheduled.isAfter(now) ? scheduled : now;
            nextFetchAt.put(domain, fireAt.plus(config.crawlDelay()));
        }
        long waitMs = Duration.between(Instant.now(), fireAt).toMillis();
        if (waitMs > 0) Thread.sleep(waitMs);
    }

    private void scheduleRetry(CrawlRequest originalRequest, Instant retryAt) {
        CrawlRequest retryRequest = originalRequest.withRetry(
                originalRequest.retryCount() + 1, retryAt);
        Duration delay = Duration.between(Instant.now(), retryAt);
        if (delay.isNegative() || delay.isZero()) {
            urlQueue.enqueue(retryRequest);
            return;
        }
        retryScheduler.schedule(() -> {
            try {
                urlQueue.enqueue(retryRequest).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Retry enqueue failed for {}: {}", retryRequest.url(), e.getMessage());
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void crawlUrlAux(CrawlRequest request) {
        try {
            URL url = new URL(request.url());
            String domain = url.getHost();

            // Job budget: check WITHOUT touching counters. Non-2xx pages,
            // duplicate content, and IOException paths must not consume
            // budget — we record the crawl only after a successful store.
            if (!jobs.canAdmit(request.jobId(), domain)) {
                logger.info("Job {} rejected {} (budget or status)", request.jobId(), request.url());
                return;
            }

            Connection.Response response = Jsoup.connect(request.url())
                    .userAgent(config.userAgent())
                    .timeout(30_000)
                    .maxBodySize(config.maxContentBytes())
                    .ignoreHttpErrors(true)
                    .ignoreContentType(false)
                    .followRedirects(true)
                    .execute();

            int status = response.statusCode();
            String contentType = response.contentType() == null ? "" : response.contentType();
            if (status < 200 || status >= 300 || !contentType.toLowerCase().contains("html")) {
                logger.info("Skipping non-HTML/non-2xx {} (status={}, type={})",
                        request.url(), status, contentType);
                return;
            }

            Document doc = response.parse();
            String rawHtml = doc.html();
            String contentHash = sha256Hex(doc.text().getBytes(StandardCharsets.UTF_8));

            if (storageService.exists(contentHash).join()) {
                logger.info("Duplicate content — skipping {} (hash matches earlier page)", request.url());
                activity.rejected(request.url(), "duplicate content (visible text hash)");
                return;
            }

            // Extract ALL absolute links (unfiltered) — the stored page
            // reflects reality. Then compute the in-scope subset for
            // enqueueing. Users see both counts.
            Set<String> discoveredLinks = extractAllLinks(doc);
            Set<String> linksToFollow = discoveredLinks.stream()
                    .filter(scope::allows)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("depth", String.valueOf(request.depth()));
            metadata.put("links_discovered", String.valueOf(discoveredLinks.size()));
            metadata.put("links_followed", String.valueOf(linksToFollow.size()));
            if (request.parentUrl() != null) metadata.put("parent_url", request.parentUrl());

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", contentType);
            response.headers().forEach(headers::putIfAbsent);

            PageContent pageContent = new PageContent(
                    request.url(),
                    contentHash,
                    rawHtml,
                    Instant.now(),
                    status,
                    headers,
                    discoveredLinks,
                    metadata,
                    request.jobId());

            storageService.store(pageContent)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .join();

            // Only now do we consume budget — the page really landed.
            jobs.recordCrawl(request.jobId(), domain);

            activity.crawled(request.url(), status, discoveredLinks.size(), linksToFollow.size());

            // Enqueue in-scope links — depth-checked here to avoid
            // 2-4x wasted Kafka/Cassandra/log traffic.
            enqueueDiscoveredLinks(request, linksToFollow);

        } catch (IOException e) {
            logger.warn("Crawl failed for {}: {}", request.url(), e.getMessage());
            activity.error(request.url(), e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error crawling {}", request.url(), e);
            activity.error(request.url(), e.getMessage());
        }
    }

    private Set<String> extractAllLinks(Document doc) {
        return doc.select("a[href]").stream()
                .map(el -> el.attr("abs:href"))
                .map(this::normalize)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void enqueueDiscoveredLinks(CrawlRequest parent, Set<String> links) {
        int childDepth = parent.depth() + 1;
        if (childDepth > config.maxDepth()) {
            logger.debug("Not enqueueing {} children — at maxDepth", links.size());
            return;
        }
        List<CompletableFuture<Void>> enqueues = new ArrayList<>(links.size());
        for (String link : links) {
            CrawlRequest child = new CrawlRequest(
                    link, childDepth, parent.url(), Instant.now(), 1, 0, null, parent.jobId());
            enqueues.add(urlQueue.enqueue(child));
        }
        try {
            CompletableFuture.allOf(enqueues.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Bulk enqueue partially failed for parent {}: {}", parent.url(), e.getMessage());
        }
    }

    private String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        try {
            String normalized = urlNormalizer.filter(rawUrl);
            if (normalized == null) return null;
            // Sanity check — throws if the URL is not parseable.
            new URL(normalized);
            return normalized;
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Cached per host — the robots rules themselves don't depend on the
     * calling job. jobId is only propagated so sitemap-discovered URLs
     * carry the correct job attribution.
     */
    private SimpleRobotRules getRobotsRules(URL url, java.util.UUID jobId) {
        String key = url.getProtocol() + "://" + url.getHost();
        return robotsCache.computeIfAbsent(key, k -> fetchRobots(url, jobId));
    }

    private SimpleRobotRules fetchRobots(URL url, java.util.UUID jobId) {
        String robotsUrl = url.getProtocol() + "://" + url.getHost() + "/robots.txt";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(robotsUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", config.userAgent())
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            SimpleRobotRules rules = robotsParser.parseContent(
                    robotsUrl, resp.body(), "text/plain", List.of(config.userAgent()));
            if (config.discoverSitemaps()) {
                rules.getSitemaps().forEach(sm -> enqueueSitemap(sm, url, jobId));
            }
            return rules;
        } catch (Exception e) {
            logger.debug("robots.txt fetch failed for {}: {}", url.getHost(), e.getMessage());
            return robotsParser.failedFetch(500);
        }
    }

    private void enqueueSitemap(String sitemapUrl, URL originatingPage, java.util.UUID jobId) {
        String normalized = normalize(sitemapUrl);
        if (normalized == null || !sitemapsSeen.add(normalized)) return;
        // Fire-and-forget on the fetch executor — sitemap parsing is
        // best-effort, we never block a crawl on it.
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(normalized))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", config.userAgent())
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                var siteMap = new crawlercommons.sitemaps.SiteMapParser()
                        .parseSiteMap(resp.body(), new URL(normalized));
                if (siteMap instanceof crawlercommons.sitemaps.SiteMap sm) {
                    for (var url : sm.getSiteMapUrls()) {
                        String loc = normalize(url.getUrl().toString());
                        if (loc == null) continue;
                        urlQueue.enqueue(new CrawlRequest(
                                loc, 0, originatingPage.toString(), Instant.now(), 1, 0, null, jobId));
                    }
                    logger.info("Enqueued {} URLs from sitemap {}", sm.getSiteMapUrls().size(), normalized);
                }
            } catch (Exception e) {
                logger.debug("Sitemap parse failed for {}: {}", normalized, e.getMessage());
            }
        }, fetchExecutor);
    }

    private void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void destroy() {
        logger.info("Destroying WebCrawler");
        stop();
        // Workers first so no new fetches are submitted while we tear down
        // the fetch executor. Retry scheduler next (nothing else feeds it).
        // Fetch executor last so in-flight fetches get their timeout window.
        shutdown(workerExecutor, "workerExecutor", 30);
        shutdown(retryScheduler, "retryScheduler", 10);
        shutdown(fetchExecutor, "fetchExecutor", 30);
    }

    private static void shutdown(ExecutorService es, String name, long timeoutSec) {
        es.shutdown();
        try {
            if (!es.awaitTermination(timeoutSec, TimeUnit.SECONDS)) {
                logger.warn("{} did not terminate — forcing", name);
                es.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            es.shutdownNow();
        }
    }
}
