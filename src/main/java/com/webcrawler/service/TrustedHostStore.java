package com.webcrawler.service;

import com.webcrawler.service.ScopeService.Mode;

/**
 * Persistent trust set for {@link ScopeService}. The default production
 * implementation writes to the {@code crawler.trusted_hosts} Cassandra table
 * (see {@link CassandraTrustedHostStore}); tests inject an in-memory
 * implementation. Introducing this seam makes swapping in Redis (or any
 * other KV) trivial.
 */
public interface TrustedHostStore {
    /** @return true if {@code key} was recorded before or is recorded now. */
    boolean persist(String key, Mode mode);

    /** @return true if {@code key} is currently trusted. */
    boolean contains(String key);

    /** @return an iterable snapshot for the /api/crawler/scope endpoint. */
    Iterable<String> allTrustedKeys();
}
