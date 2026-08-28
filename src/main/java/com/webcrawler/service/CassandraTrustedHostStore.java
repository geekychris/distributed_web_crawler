package com.webcrawler.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Cassandra-backed trust store fronted by a small Caffeine LRU. All lookups
 * hit Caffeine first — a miss triggers a single-key SELECT and populates the
 * cache. Writes go to Cassandra first, then to Caffeine.
 *
 * <p>Alternatives for the RAM/latency tier (future work):
 * <ul>
 *   <li>Redis / Valkey — shared low-latency L2 across crawler replicas.
 *       Would slot in as a peer implementation of TrustedHostStore.</li>
 *   <li>FP64 fingerprint + Trove {@code TLongHashSet} — ~5× smaller
 *       per-entry when we only need membership.</li>
 *   <li>Bloom filter — probabilistic negative cache, useful if hit ratio
 *       is very low.</li>
 * </ul>
 */
@Component
public class CassandraTrustedHostStore implements TrustedHostStore {
    private static final Logger logger = LoggerFactory.getLogger(CassandraTrustedHostStore.class);
    private static final Boolean PRESENT = Boolean.TRUE;

    private final CqlSession session;
    private final Cache<String, Boolean> hot = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    private PreparedStatement upsert;
    private PreparedStatement selectOne;
    private PreparedStatement selectAll;

    @Autowired
    public CassandraTrustedHostStore(CqlSession session) {
        this.session = session;
    }

    @PostConstruct
    void init() {
        this.upsert = session.prepare(
                "INSERT INTO trusted_hosts (host, mode, added_at) VALUES (?, ?, ?)");
        this.selectOne = session.prepare(
                "SELECT host FROM trusted_hosts WHERE host = ?");
        this.selectAll = session.prepare(
                "SELECT host FROM trusted_hosts");
    }

    @Override
    public boolean persist(String key, ScopeService.Mode mode) {
        if (hot.getIfPresent(key) != null) return true;
        try {
            session.execute(upsert.bind(key, mode.name(), Instant.now()));
            hot.put(key, PRESENT);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to persist trusted host {}: {}", key, e.getMessage());
            hot.put(key, PRESENT); // still available in-process, degraded write
            return false;
        }
    }

    @Override
    public boolean contains(String key) {
        Boolean cached = hot.getIfPresent(key);
        if (cached != null) return cached;
        try {
            Row r = session.execute(selectOne.bind(key)).one();
            boolean present = r != null;
            hot.put(key, present);
            return present;
        } catch (Exception e) {
            logger.debug("trusted_hosts lookup failed for {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public Iterable<String> allTrustedKeys() {
        List<String> out = new ArrayList<>();
        try {
            for (Row r : session.execute(selectAll.bind())) out.add(r.getString("host"));
        } catch (Exception e) {
            logger.debug("trusted_hosts scan failed: {}", e.getMessage());
        }
        return out;
    }
}
