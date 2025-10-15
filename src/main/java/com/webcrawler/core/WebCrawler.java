package com.webcrawler.core;

import com.webcrawler.config.CrawlerProperties;
import com.webcrawler.model.CrawlRequest;
import com.webcrawler.model.PageContent;
import com.webcrawler.queue.UrlQueue;
import com.webcrawler.storage.StorageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URL;
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
    private final ExecutorService executorService;
    private final ScheduledExecutorService retryScheduler;
    private final Map<String, Instant> lastCrawled;
    private final Map<String, RobotsTxtRules> robotsCache;
    private volatile boolean isRunning;

    @Autowired
    public WebCrawler(CrawlerProperties config, UrlQueue urlQueue, StorageService storageService) {
        this.config = config;
        this.urlQueue = urlQueue;
        this.storageService = storageService;
        this.executorService = Executors.newFixedThreadPool(config.maxConcurrentRequests());
        this.retryScheduler = Executors.newScheduledThreadPool(2);  // Small pool for scheduling retries
        this.lastCrawled = new ConcurrentHashMap<>();
        this.robotsCache = new ConcurrentHashMap<>();
        this.isRunning = false;
    }

    @PostConstruct
    private void initialize() {
        logger.info("Initializing WebCrawler service...");
    }

    public synchronized void start() {
        if (isRunning) {
            logger.info("WebCrawler is already running");
            return;
        }
        
        isRunning = true;
        logger.info("Starting WebCrawler...");
        
        // Seed the queue with initial URLs
        seedUrlQueue();

        // Start batch crawler workers (fewer workers since each handles batches)
        int workerCount = Math.max(1, config.maxConcurrentRequests() / 5); // Use fewer workers for batch processing
        logger.info("Starting {} batch crawler workers", workerCount);
        for (int i = 0; i < workerCount; i++) {
            CompletableFuture.runAsync(this::crawlLoop, executorService);
        }
    }
    
    public synchronized void stop() {
        if (!isRunning) {
            logger.info("WebCrawler is already stopped");
            return;
        }
        
        logger.info("Stopping WebCrawler...");
        isRunning = false;
    }
    
    public boolean isRunning() {
        return isRunning;
    }

    private void seedUrlQueue() {
        logger.info("Seeding URL queue with {} URLs", config.getSeedUrlSet().size());
        config.getSeedUrlSet().forEach(url -> {
            logger.info("Adding seed URL to queue: {}", url);
            CrawlRequest request = new CrawlRequest(url, 0, null, Instant.now(), 1);
            urlQueue.enqueue(request).join();
            logger.info("Successfully enqueued seed URL: {}", url);
        });
        logger.info("Finished seeding URL queue");
    }

    private void crawlLoop() {
        logger.info("Starting batch crawl loop thread: {}", Thread.currentThread().getName());
        while (isRunning) {
            try {
                // Poll for a batch of URLs
                logger.debug("Polling for batch of URLs (timeout: {}ms)...", config.pollTimeout().toMillis());
                List<CrawlRequest> requests = urlQueue.pollBatch(config.pollTimeout().toMillis()).join();
                
                if (requests.isEmpty()) {
                    logger.debug("No URLs found in batch poll, continuing...");
                    continue;
                }
                
                logger.info("📦 Received batch of {} URLs to process", requests.size());
                
                // Process batch in parallel using Virtual Threads
                processBatchInParallel(requests);
                
                // Commit the batch only after all URLs are processed
                logger.info("✅ All URLs in batch processed successfully, committing offsets");
                urlQueue.commitBatch().join();
                
            } catch (Exception e) {
                logger.error("Error in batch crawl loop", e);
                // Sleep briefly before retrying
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        logger.info("Batch crawl loop thread exiting: {}", Thread.currentThread().getName());
    }
    
    private void processBatchInParallel(List<CrawlRequest> requests) {
        logger.info("🚀 Starting parallel processing of {} URLs using Virtual Threads", requests.size());
        
        // Create virtual thread tasks for each URL
        List<CompletableFuture<Void>> crawlTasks = requests.stream()
            .map(this::createCrawlTask)
            .toList();
        
        // Wait for all crawl tasks to complete
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(
            crawlTasks.toArray(new CompletableFuture[0])
        );
        
        try {
            allTasks.join(); // Wait for all tasks to complete
            logger.info("✅ Completed parallel processing of {} URLs", requests.size());
        } catch (Exception e) {
            logger.error("❌ Error during parallel processing of batch", e);
            // Continue processing - individual errors are logged in each task
        }
    }
    
    private CompletableFuture<Void> createCrawlTask(CrawlRequest request) {
        return CompletableFuture.runAsync(() -> {
            try {
                processSingleRequest(request);
            } catch (Exception e) {
                logger.error("Error processing URL: {}", request.url(), e);
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }
    
    private void processSingleRequest(CrawlRequest request) {
        logger.info("🔍 Processing URL: {} (depth: {}, retry: {}) [Thread: {}]", 
            request.url(), request.depth(), request.retryCount(), Thread.currentThread().getName());
        
        // Check if request is scheduled for the future
        if (!request.isReadyToProcess()) {
            logger.debug("Request not ready yet, re-queuing: {} (scheduled for: {})", 
                request.url(), request.scheduledFor());
            urlQueue.enqueue(request);
            return;
        }
        
        CrawlDecision decision = shouldCrawl(request);
        switch (decision.action()) {
            case CRAWL -> {
                logger.info("✅ URL passed validation, starting crawl: {} [{}]", request.url(), Thread.currentThread().getName());
                crawlUrlAux(request);
                logger.info("🎯 Successfully crawled URL: {} [{}]", request.url(), Thread.currentThread().getName());
            }
            case RETRY_LATER -> {
                logger.info("⏰ Scheduling URL for retry: {} (reason: {}, retry #{}, scheduled for: {})", 
                    request.url(), decision.reason(), request.retryCount() + 1, decision.retryAt());
                scheduleRetry(request, decision.retryAt());
            }
            case REJECT -> {
                logger.info("❌ URL rejected: {} (reason: {})", request.url(), decision.reason());
            }
        }
    }

    private CrawlDecision shouldCrawl(CrawlRequest request) {
        try {
            URL url = new URL(request.url());
            String domain = url.getHost();
            
            logger.info("Validating URL: {} (domain: {})", request.url(), domain);

            // Check depth
            if (request.depth() > config.maxDepth()) {
                logger.info("URL rejected - depth {} exceeds max depth {}: {}", request.depth(), config.maxDepth(), request.url());
                return CrawlDecision.reject("depth " + request.depth() + " exceeds max depth " + config.maxDepth());
            }
            logger.info("✓ Depth check passed ({}/{}): {}", request.depth(), config.maxDepth(), request.url());

            // Check retry limits
            if (request.retryCount() > config.maxRetryAttempts()) {
                logger.info("URL rejected - exceeded max retry attempts {}: {}", config.maxRetryAttempts(), request.url());
                return CrawlDecision.reject("exceeded max retry attempts " + config.maxRetryAttempts());
            }

            // Check allowed domains
            var allowedDomains = config.getAllowedDomainPatterns();
            if (!allowedDomains.isEmpty()) {
                boolean domainMatches = allowedDomains.stream().anyMatch(p -> p.matcher(domain).matches());
                if (!domainMatches) {
                    logger.info("URL rejected - domain '{}' doesn't match allowed patterns: {}", domain, 
                        allowedDomains.stream().map(p -> p.pattern()).collect(java.util.stream.Collectors.toList()));
                    return CrawlDecision.reject("domain doesn't match allowed patterns");
                }
                logger.info("✓ Domain check passed - '{}' matches allowed patterns: {}", domain, request.url());
            } else {
                logger.info("✓ No domain restrictions configured: {}", request.url());
            }

            // Check excluded patterns
            var excludePatterns = config.getExcludePatternList();
            boolean isExcluded = excludePatterns.stream().anyMatch(p -> p.matcher(request.url()).matches());
            if (isExcluded) {
                logger.info("URL rejected - matches exclude pattern: {}", request.url());
                return CrawlDecision.reject("matches exclude pattern");
            }
            logger.info("✓ Exclude pattern check passed: {}", request.url());

            // Check crawl delay - this is where we implement retry logic
            Instant lastVisit = lastCrawled.get(domain);
            if (lastVisit != null) {
                Duration timeSinceLastVisit = Duration.between(lastVisit, Instant.now());
                if (timeSinceLastVisit.compareTo(config.crawlDelay()) < 0) {
                    if (config.enableDelayRetry()) {
                        Duration remainingDelay = config.crawlDelay().minus(timeSinceLastVisit);
                        Instant retryAt = Instant.now().plus(remainingDelay);
                        logger.info("URL scheduled for retry due to crawl delay - domain '{}': {} (retry in {}ms)", 
                            domain, request.url(), remainingDelay.toMillis());
                        return CrawlDecision.retryLater("crawl delay not satisfied for domain " + domain, retryAt);
                    } else {
                        logger.info("URL rejected - crawl delay not satisfied for domain '{}': {}", domain, request.url());
                        return CrawlDecision.reject("crawl delay not satisfied for domain " + domain);
                    }
                }
            }
            logger.info("✓ Crawl delay check passed: {}", request.url());

            // Check robots.txt
            if (config.respectRobotsTxt()) {
                logger.info("Checking robots.txt for: {}", request.url());
                RobotsTxtRules rules = getRobotsRules(url);
                if (!rules.isAllowed(request.url())) {
                    logger.info("URL rejected - robots.txt disallows: {}", request.url());
                    return CrawlDecision.reject("robots.txt disallows");
                }
                logger.info("✓ Robots.txt check passed: {}", request.url());
            } else {
                logger.info("✓ Robots.txt checking disabled: {}", request.url());
            }

            logger.info("✅ All validation checks passed for: {}", request.url());
            return CrawlDecision.crawl();
        } catch (Exception e) {
            logger.error("Error checking if URL should be crawled: {}", request.url(), e);
            return CrawlDecision.reject("validation error: " + e.getMessage());
        }
    }
    
    /**
     * Schedule a URL for retry after a specified delay.
     */
    private void scheduleRetry(CrawlRequest originalRequest, Instant retryAt) {
        CrawlRequest retryRequest = originalRequest.withRetry(
            originalRequest.retryCount() + 1,
            retryAt
        );
        
        Duration delay = Duration.between(Instant.now(), retryAt);
        if (delay.isNegative()) {
            // If the retry time is in the past, enqueue immediately
            logger.debug("Retry time is in the past, enqueueing immediately: {}", originalRequest.url());
            urlQueue.enqueue(retryRequest);
        } else {
            // Schedule for future execution
            retryScheduler.schedule(
                () -> {
                    try {
                        logger.info("🔄 Retry time reached, re-queueing URL: {} (attempt #{})", 
                            retryRequest.url(), retryRequest.retryCount());
                        urlQueue.enqueue(retryRequest).join();
                    } catch (Exception e) {
                        logger.error("Failed to re-queue URL for retry: {}", retryRequest.url(), e);
                    }
                },
                delay.toMillis(),
                TimeUnit.MILLISECONDS
            );
        }
    }

    private CompletableFuture<Void> crawlUrl(CrawlRequest request) {
        return CompletableFuture.runAsync(() -> {
            crawlUrlAux(request);
        }, executorService);
    }

    private void crawlUrlAux(CrawlRequest request) {
        try {
            logger.error("about to crawl: {}", request.url());
            URL url = new URL(request.url());
            lastCrawled.put(url.getHost(), Instant.now());

            Document doc = Jsoup.connect(request.url())
                .userAgent(config.userAgent())
                .timeout(30000)
                .get();

            String content = doc.html();
            String contentHash = computeHash(content);

            // Check if we've seen this content before
            if (storageService.exists(contentHash).join()) {
                logger.debug("Skipping duplicate content: {}", request.url());
                return;
            }

            // Extract links
            logger.info("🔍 Extracting links from page: {}", request.url());
            Set<String> rawLinks = doc.select("a[href]").stream()
                .map(element -> element.attr("abs:href"))
                .filter(link -> !link.isEmpty())
                .collect(Collectors.toSet());

            logger.info("🔗 Found {} raw links on page: {}", rawLinks.size(), request.url());

            // Filter links and log the filtering process
            Set<String> links = new HashSet<>();
            int validLinks = 0;
            int filteredLinks = 0;

            for (String link : rawLinks) {
                try {
                    URL linkUrl = new URL(link);
                    String linkDomain = linkUrl.getHost();

                    // Apply basic filtering (similar to shouldCrawl but for discovered links)
                    boolean isValid = true;
                    String filterReason = "";

                    // Check against allowed domains if configured
                    var allowedDomains = config.getAllowedDomainPatterns();
                    if (!allowedDomains.isEmpty()) {
                        boolean domainMatches = allowedDomains.stream().anyMatch(p -> p.matcher(linkDomain).matches());
                        if (!domainMatches) {
                            isValid = false;
                            filterReason = "domain '" + linkDomain + "' doesn't match allowed patterns";
                        }
                    }

                    // Check against exclude patterns
                    if (isValid) {
                        var excludePatterns = config.getExcludePatternList();
                        boolean isExcluded = excludePatterns.stream().anyMatch(p -> p.matcher(link).matches());
                        if (isExcluded) {
                            isValid = false;
                            filterReason = "matches exclude pattern";
                        }
                    }

                    if (isValid) {
                        links.add(link);
                        validLinks++;
                        logger.debug("✓ Valid link found: {} -> {}", linkDomain, link);
                    } else {
                        filteredLinks++;
                        logger.debug("✗ Filtered link: {} -> {} (reason: {})", linkDomain, link, filterReason);
                    }
                } catch (Exception e) {
                    filteredLinks++;
                    logger.debug("✗ Invalid link format: {} (error: {})", link, e.getMessage());
                }
            }

            logger.info("📊 Link processing summary for {}: {} valid, {} filtered, {} total",
                request.url(), validLinks, filteredLinks, rawLinks.size());
            Set<String> finalLinks = links;

            // Store the page
            PageContent pageContent = new PageContent(
                request.url(),
                contentHash,
                content,
                Instant.now(),
                200,
                Map.of("Content-Type", "text/html"),
                links,
                Map.of("depth", String.valueOf(request.depth()))
            );

            logger.info("Storing page content for URL: {} (hash: {})", request.url(), contentHash);
            storageService.store(pageContent).join();
            logger.info("Successfully stored page: {}", request.url());

            // Enqueue discovered links
            logger.info("📤 Enqueueing {} valid links discovered from: {}", finalLinks.size(), request.url());
            int enqueuedCount = 0;
            for (String link : finalLinks) {
                try {
                    CrawlRequest newRequest = new CrawlRequest(
                        link,
                        request.depth() + 1,
                        request.url(),
                        Instant.now(),
                        1
                    );
                    urlQueue.enqueue(newRequest).join();
                    enqueuedCount++;
                    logger.info("✓ Enqueued link [depth {}]: {}", newRequest.depth(), link);
                } catch (Exception e) {
                    logger.error("✗ Failed to enqueue link: {} (error: {})", link, e.getMessage());
                }
            }
            logger.info("🎯 Successfully enqueued {} out of {} discovered links from: {}",
                enqueuedCount, finalLinks.size(), request.url());

        } catch (Exception e) {
            logger.error("Error crawling URL: {}", request.url(), e);
        }
    }

    private String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute content hash", e);
        }
    }

    private RobotsTxtRules getRobotsRules(URL url) {
        String domain = url.getHost();
        return robotsCache.computeIfAbsent(domain, k -> {
            try {
                String robotsUrl = String.format("%s://%s/robots.txt", url.getProtocol(), domain);
                Document robotsTxt = Jsoup.connect(robotsUrl)
                    .userAgent(config.userAgent())
                    .timeout(10000)
                    .get();
                return new RobotsTxtRules(robotsTxt.body().text());
            } catch (Exception e) {
                logger.warn("Failed to fetch robots.txt for {}", domain, e);
                return new RobotsTxtRules("");  // Allow all if robots.txt is unavailable
            }
        });
    }

    @PreDestroy
    public void destroy() {
        logger.info("Destroying WebCrawler service...");
        stop();
        
        // Shutdown main executor
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        
        // Shutdown retry scheduler
        retryScheduler.shutdown();
        try {
            if (!retryScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                retryScheduler.shutdownNow();
                logger.warn("Retry scheduler did not shut down gracefully, forcing shutdown");
            }
        } catch (InterruptedException e) {
            retryScheduler.shutdownNow();
        }
    }

    private static class RobotsTxtRules {
        private final List<String> disallowedPaths;

        public RobotsTxtRules(String robotsTxt) {
            this.disallowedPaths = parseRobotsTxt(robotsTxt);
        }

        private List<String> parseRobotsTxt(String robotsTxt) {
            List<String> disallowed = new ArrayList<>();
            boolean relevantUserAgent = false;

            for (String line : robotsTxt.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.toLowerCase().startsWith("user-agent:")) {
                    String agent = line.substring(11).trim();
                    relevantUserAgent = agent.equals("*");
                } else if (relevantUserAgent && line.toLowerCase().startsWith("disallow:")) {
                    String path = line.substring(9).trim();
                    if (!path.isEmpty()) {
                        disallowed.add(path);
                    }
                }
            }
            return disallowed;
        }

        public boolean isAllowed(String url) {
            return disallowedPaths.stream().noneMatch(url::contains);
        }
    }
}
