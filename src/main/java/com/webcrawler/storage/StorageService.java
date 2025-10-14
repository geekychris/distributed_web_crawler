package com.webcrawler.storage;

import com.webcrawler.model.PageContent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface StorageService {
    /**
     * Stores a crawled page and its metadata.
     *
     * @param content The page content and metadata to store
     * @return A future that completes when the content is stored
     */
    CompletableFuture<Void> store(PageContent content);

    /**
     * Retrieves a page by its URL.
     *
     * @param url The URL of the page to retrieve
     * @return A future that completes with the page content if found
     */
    CompletableFuture<Optional<PageContent>> retrieve(String url);

    /**
     * Checks if a page with the given content hash exists.
     *
     * @param contentHash The hash to check
     * @return A future that completes with true if the content exists
     */
    CompletableFuture<Boolean> exists(String contentHash);

    /**
     * Retrieves all crawled pages with pagination.
     *
     * @param limit Maximum number of pages to return
     * @param offset Number of pages to skip
     * @return A future that completes with a list of page metadata (without content)
     */
    CompletableFuture<List<PageMetadata>> getAllPages(int limit, int offset);

    /**
     * Searches for pages containing a specific term in the URL.
     *
     * @param searchTerm The term to search for in URLs
     * @param limit Maximum number of pages to return
     * @return A future that completes with a list of matching page metadata
     */
    CompletableFuture<List<PageMetadata>> searchPages(String searchTerm, int limit);

    /**
     * Gets the total count of crawled pages.
     *
     * @return A future that completes with the total page count
     */
    CompletableFuture<Long> getPageCount();

    /**
     * Represents page metadata without the full content for efficient querying.
     */
    record PageMetadata(
        String url,
        String contentHash,
        java.time.Instant fetchTime,
        int httpStatus,
        java.util.Map<String, String> headers,
        java.util.Set<String> links,
        java.util.Map<String, String> metadata
    ) {}
}
