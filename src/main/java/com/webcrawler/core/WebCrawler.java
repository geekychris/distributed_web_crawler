package com.webcrawler.core;

import com.webcrawler.config.CrawlerConfig;
import com.webcrawler.model.CrawlRequest;
import com.webcrawler.model.PageContent;
import com.webcrawler.queue.UrlQueue;
import com.webcrawler.storage.StorageService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class WebCrawler implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(WebCrawler.class);
    private final CrawlerConfig config;
    private final UrlQueue urlQueue;
    private final StorageService storageService;
    private final ExecutorService executorService;
    private final Map<String, Instant> lastCrawled;
    private final Map<String, RobotsTxtRules> robotsCache;
    private volatile boolean isRunning;

    public WebCrawler(CrawlerConfig config, UrlQueue urlQueue, StorageService storageService) {
        this.config = config;
        this.urlQueue = urlQueue;
        this.storageService = storageService;
        this.executorService = Executors.newFixedThreadPool(config.maxConcurrentRequests());
        this.lastCrawled = new ConcurrentHashMap<>();
        this.robotsCache = new ConcurrentHashMap<>();
        this.isRunning = true;
    }

    public void start() {
        // Seed the queue with initial URLs
        seedUrlQueue();

        // Start crawler workers
        List<CompletableFuture<Void>> workers = new ArrayList<>();
        for (int i = 0; i < config.maxConcurrentRequests(); i++) {
            workers.add(CompletableFuture.runAsync(this::crawlLoop, executorService));
        }

        // Wait for all workers to complete
        CompletableFuture.allOf(workers.toArray(new CompletableFuture[0])).join();
    }

    private void seedUrlQueue() {
        config.seedUrls().forEach(url -> {
            CrawlRequest request = new CrawlRequest(url, 0, null, Instant.now(), 1);
            urlQueue.enqueue(request).join();
        });
    }

    private void crawlLoop() {
        while (isRunning) {
            try {
                CrawlRequest request = urlQueue.dequeue().join();
                if (request == null) {
                    Thread.sleep(1000);
                    continue;
                }

                if (shouldCrawl(request)) {
                    crawlUrl(request).join();
                }
            } catch (Exception e) {
                logger.error("Error in crawl loop", e);
            }
        }
    }

    private boolean shouldCrawl(CrawlRequest request) {
        try {
            URL url = new URL(request.url());
            String domain = url.getHost();

            // Check depth
            if (request.depth() > config.maxDepth()) {
                return false;
            }

            // Check allowed domains
            if (!config.allowedDomains().isEmpty() && 
                config.allowedDomains().stream().noneMatch(p -> p.matcher(domain).matches())) {
                return false;
            }

            // Check excluded patterns
            if (config.excludePatterns().stream().anyMatch(p -> p.matcher(request.url()).matches())) {
                return false;
            }

            // Check crawl delay
            Instant lastVisit = lastCrawled.get(domain);
            if (lastVisit != null && 
                Duration.between(lastVisit, Instant.now()).compareTo(config.crawlDelay()) < 0) {
                return false;
            }

            // Check robots.txt
            if (config.respectRobotsTxt()) {
                RobotsTxtRules rules = getRobotsRules(url);
                if (!rules.isAllowed(request.url())) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            logger.error("Error checking if URL should be crawled: {}", request.url(), e);
            return false;
        }
    }

    private CompletableFuture<Void> crawlUrl(CrawlRequest request) {
        return CompletableFuture.runAsync(() -> {
            try {
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
                Set<String> links = doc.select("a[href]").stream()
                    .map(element -> element.attr("abs:href"))
                    .filter(link -> !link.isEmpty())
                    .collect(Collectors.toSet());

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

                storageService.store(pageContent).join();

                // Enqueue discovered links
                for (String link : links) {
                    CrawlRequest newRequest = new CrawlRequest(
                        link,
                        request.depth() + 1,
                        request.url(),
                        Instant.now(),
                        1
                    );
                    urlQueue.enqueue(newRequest);
                }

            } catch (Exception e) {
                logger.error("Error crawling URL: {}", request.url(), e);
            }
        }, executorService);
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

    @Override
    public void close() {
        isRunning = false;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
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
