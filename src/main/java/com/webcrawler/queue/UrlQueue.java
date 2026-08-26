package com.webcrawler.queue;

import com.webcrawler.model.CrawlRequest;

import java.util.concurrent.CompletableFuture;

/**
 * URL queue producer interface. Consumers are created per worker thread via
 * {@link #openBatchConsumer()} — a Kafka consumer is not thread-safe, and
 * sharing one across workers breaks per-batch offset commits.
 */
public interface UrlQueue extends AutoCloseable {
    CompletableFuture<Void> enqueue(CrawlRequest request);

    /**
     * Open a new batch consumer bound to this queue. The returned consumer is
     * owned by (and safe for use from) exactly one thread — typically the
     * caller's crawl-loop thread.
     */
    BatchConsumer openBatchConsumer();

    @Override
    void close();
}
