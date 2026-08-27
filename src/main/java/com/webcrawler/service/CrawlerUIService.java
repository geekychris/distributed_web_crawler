package com.webcrawler.service;

import com.webcrawler.config.CrawlerProperties;
import com.webcrawler.core.WebCrawler;
import com.webcrawler.model.CrawlRequest;
import com.webcrawler.queue.UrlQueue;
import com.webcrawler.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class CrawlerUIService {

    private final WebCrawler webCrawler;
    private final CrawlerProperties crawlerProperties;
    private final UrlQueue urlQueue;
    private final StorageService storageService;
    private final ScopeService scope;
    private final Instant startTime = Instant.now();

    @Autowired
    public CrawlerUIService(WebCrawler webCrawler,
                           CrawlerProperties crawlerProperties,
                           UrlQueue urlQueue,
                           StorageService storageService,
                           ScopeService scope) {
        this.webCrawler = webCrawler;
        this.crawlerProperties = crawlerProperties;
        this.urlQueue = urlQueue;
        this.storageService = storageService;
        this.scope = scope;
    }
    
    public CrawlerStats getCrawlerStats() {
        return new CrawlerStats(
            webCrawler.isRunning(),
            Duration.between(startTime, Instant.now()),
            crawlerProperties.maxDepth(),
            crawlerProperties.maxConcurrentRequests(),
            crawlerProperties.crawlDelay(),
            crawlerProperties.respectRobotsTxt(),
            crawlerProperties.userAgent(),
            crawlerProperties.getSeedUrlSet().size(),
            crawlerProperties.getSeedUrlSet()
        );
    }
    
    public void startCrawler() {
        webCrawler.start();
    }
    
    public void stopCrawler() {
        webCrawler.stop();
    }
    
    public CompletableFuture<Void> addSeedUrl(String url) {
        return addSeedUrl(url, ScopeService.Mode.HOST);
    }

    public CompletableFuture<Void> addSeedUrl(String url, ScopeService.Mode mode) {
        scope.trustSubmission(url, mode);
        CrawlRequest request = new CrawlRequest(url, 0, null, Instant.now(), 1);
        return urlQueue.enqueue(request);
    }

    /** Register scope trust without enqueueing — used when the enqueue is done separately (jobs). */
    public void trustSubmissionFor(String url, ScopeService.Mode mode) {
        scope.trustSubmission(url, mode);
    }
    
    public CompletableFuture<Void> addSeedUrls(List<String> urls) {
        CompletableFuture<?>[] futures = urls.stream()
            .map(this::addSeedUrl)
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }
    
    // Page query methods
    public CompletableFuture<List<StorageService.PageMetadata>> getAllPages(int limit, int offset) {
        return storageService.getAllPages(limit, offset);
    }
    
    public CompletableFuture<List<StorageService.PageMetadata>> searchPages(String searchTerm, int limit) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return getAllPages(limit, 0);
        }
        return storageService.searchPages(searchTerm.trim(), limit);
    }
    
    public CompletableFuture<Long> getPageCount() {
        return storageService.getPageCount();
    }
    
    public record CrawlerStats(
        boolean isRunning,
        Duration uptime,
        int maxDepth,
        int maxConcurrentRequests,
        Duration crawlDelay,
        boolean respectRobotsTxt,
        String userAgent,
        int configuredSeedUrls,
        java.util.Set<String> seedUrls
    ) {}
}