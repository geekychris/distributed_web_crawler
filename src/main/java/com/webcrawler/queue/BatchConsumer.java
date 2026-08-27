package com.webcrawler.queue;

import com.webcrawler.model.CrawlRequest;

import java.time.Duration;
import java.util.List;

/**
 * A single-threaded batch consumer of {@link CrawlRequest}s. All methods must
 * be invoked from the same thread — the underlying KafkaConsumer is not
 * multi-thread safe.
 */
public interface BatchConsumer extends AutoCloseable {
    /**
     * Poll for a batch of requests. Returns an empty batch (never null) if
     * nothing was available within the timeout.
     */
    Batch poll(Duration timeout);

    @Override
    void close();

    /**
     * Records returned by one poll, plus a commit hook tied to those records'
     * offsets. {@link #hasRecords()} distinguishes "poll returned no records"
     * (nothing to commit) from "poll returned records that all failed to
     * parse" (still commit, otherwise we'd re-poll the broken records
     * forever).
     */
    record Batch(List<CrawlRequest> requests, Runnable commit, boolean hasRecords) {
        public boolean isEmpty() { return requests.isEmpty(); }
        public int size() { return requests.size(); }
    }
}
