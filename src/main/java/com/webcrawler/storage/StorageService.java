package com.webcrawler.storage;

import com.webcrawler.model.PageContent;
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
}
