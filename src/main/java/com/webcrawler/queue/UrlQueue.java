package com.webcrawler.queue;

import com.webcrawler.model.CrawlRequest;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface UrlQueue extends AutoCloseable {
    CompletableFuture<Void> enqueue(CrawlRequest request);
    CompletableFuture<List<CrawlRequest>> dequeue();
    
    // Batch processing methods
    CompletableFuture<List<CrawlRequest>> pollBatch(long timeoutMs);
    CompletableFuture<Void> commitBatch();
}
