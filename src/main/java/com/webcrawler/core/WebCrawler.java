package com.webcrawler.core;

import com.webcrawler.config.CrawlerProperties;
import com.webcrawler.model.CrawlRequest;
import com.webcrawler.model.PageContent;
import com.webcrawler.queue.BatchConsumer;
import com.webcrawler.queue.UrlQueue;
import com.webcrawler.service.CrawlJobService;
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

    private final ExecutorService fetchExecutor;
    private final ScheduledExecutorService retryScheduler;
    private final ExecutorService workerExecutor;

    private final Map<String, Instant> lastCrawled = new ConcurrentHashMap<>();
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
                      CrawlJobService jobs) {
        this.config = config;
        this.urlQueue = urlQueue;
        this.storageService = storageService;
        this.jobs = jobs;
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

        seedUrlQueue();

        int workerCount = Math.max(1, config.maxConcurrentRequests() / 5);
        logger.info("Starting {} crawl worker(s)", workerCount);
        for (int i = 0; i < workerCount; i++) {
            workerExecutor.execute(this::crawlLoop);
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
                    if (batch.isEmpty()) continue;

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
            case REJECT -> logger.debug("Rejected {}: {}", request.url(), decision.reason());
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

            var allowedDomains = config.getAllowedDomainPatterns();
            if (!allowedDomains.isEmpty()
                    && allowedDomains.stream().noneMatch(p -> p.matcher(domain).find())) {
                return CrawlDecision.reject("domain not allowed");
            }
            var excludePatterns = config.getExcludePatternList();
            if (excludePatterns.stream().anyMatch(p -> p.matcher(request.url()).find())) {
                return CrawlDecision.reject("matches exclude pattern");
            }

            // Politeness: sleep for the remaining delay rather than requeue.
            // Requeue-with-retry-increment eats legitimate URLs when a batch
            // is dense on one domain.
            Instant lastVisit = lastCrawled.get(domain);
            if (lastVisit != null) {
                Duration since = Duration.between(lastVisit, Instant.now());
                if (since.compareTo(config.crawlDelay()) < 0) {
                    Duration remaining = config.crawlDelay().minus(since);
                    try {
                        Thread.sleep(remaining.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return CrawlDecision.reject("interrupted during politeness delay");
                    }
                }
            }

            if (config.respectRobotsTxt()) {
                SimpleRobotRules rules = getRobotsRules(url);
                if (!rules.isAllowed(request.url())) {
                    return CrawlDecision.reject("robots.txt disallows");
                }
            }

            return CrawlDecision.crawl();
        } catch (Exception e) {
            logger.warn("Validation failed for {}: {}", request.url(), e.getMessage());
            return CrawlDecision.reject("validation error: " + e.getMessage());
        }
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

            // Job budget: reject if the job has hit its cap. Passes for
            // no-job (legacy) requests.
            if (!jobs.admit(request.jobId(), domain)) {
                logger.info("Job {} rejected {} (budget or status)", request.jobId(), request.url());
                return;
            }

            lastCrawled.put(domain, Instant.now());

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
            // Hash extracted visible text, not raw HTML: strips CSRF tokens,
            // ad slots, timestamps, and other per-fetch noise that would
            // defeat dedup. Explicit UTF-8 so hashes match across machines.
            String contentHash = sha256Hex(doc.text().getBytes(StandardCharsets.UTF_8));

            if (storageService.exists(contentHash).join()) {
                logger.debug("Duplicate content, skipping: {}", request.url());
                return;
            }

            Set<String> discoveredLinks = extractAndFilterLinks(doc, request);

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("depth", String.valueOf(request.depth()));
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

            // Enqueue discovered links — depth-checked here to avoid
            // 2-4x wasted Kafka/Cassandra/log traffic.
            enqueueDiscoveredLinks(request, discoveredLinks);

        } catch (IOException e) {
            logger.warn("Crawl failed for {}: {}", request.url(), e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error crawling {}", request.url(), e);
        }
    }

    private Set<String> extractAndFilterLinks(Document doc, CrawlRequest request) {
        var allowedDomains = config.getAllowedDomainPatterns();
        var excludePatterns = config.getExcludePatternList();

        return doc.select("a[href]").stream()
                .map(el -> el.attr("abs:href"))
                .map(this::normalize)
                .filter(Objects::nonNull)
                .filter(link -> passesScope(link, allowedDomains, excludePatterns))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean passesScope(String link,
                                Set<java.util.regex.Pattern> allowedDomains,
                                Set<java.util.regex.Pattern> excludePatterns) {
        try {
            String host = new URL(link).getHost();
            if (!allowedDomains.isEmpty()
                    && allowedDomains.stream().noneMatch(p -> p.matcher(host).find())) {
                return false;
            }
            return excludePatterns.stream().noneMatch(p -> p.matcher(link).find());
        } catch (Exception e) {
            return false;
        }
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

    private SimpleRobotRules getRobotsRules(URL url) {
        String key = url.getProtocol() + "://" + url.getHost();
        return robotsCache.computeIfAbsent(key, k -> fetchRobots(url));
    }

    private SimpleRobotRules fetchRobots(URL url) {
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
                rules.getSitemaps().forEach(sm -> enqueueSitemap(sm, url));
            }
            return rules;
        } catch (Exception e) {
            logger.debug("robots.txt fetch failed for {}: {}", url.getHost(), e.getMessage());
            return robotsParser.failedFetch(500);
        }
    }

    private void enqueueSitemap(String sitemapUrl, URL originatingPage) {
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
                                loc, 0, originatingPage.toString(), Instant.now(), 1, 0, null, null));
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
        shutdown(fetchExecutor, "fetchExecutor", 30);
        shutdown(retryScheduler, "retryScheduler", 10);
        shutdown(workerExecutor, "workerExecutor", 30);
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
