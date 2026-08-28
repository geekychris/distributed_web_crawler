package com.webcrawler.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.webcrawler.config.TrustedHostsProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed {@link TrustedHostStore}. All trusted keys live in a single
 * Redis SET (default key {@code trusted:hosts}) — SADD / SISMEMBER give
 * O(1) writes and lookups regardless of set size.
 *
 * <p>Fronted by a Caffeine LRU (10k entries, 1h expiry) so the hot path
 * stays in-process. On a miss we hit Redis and populate the cache.
 *
 * <p>Activated by {@code trusted-hosts.backend=redis} (env
 * {@code TRUSTED_HOSTS_BACKEND=redis}); default backend remains Cassandra.
 * Both implementations are @Component-scanned; the @ConditionalOnProperty
 * picks exactly one at boot.
 */
@Component
@ConditionalOnProperty(name = "trusted-hosts.backend", havingValue = "redis")
public class RedisTrustedHostStore implements TrustedHostStore {
    private static final Logger logger = LoggerFactory.getLogger(RedisTrustedHostStore.class);
    private static final Boolean PRESENT = Boolean.TRUE;

    private final StringRedisTemplate redis;
    private final String setKey;
    private final Cache<String, Boolean> hot = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();
    private SetOperations<String, String> ops;

    @Autowired
    public RedisTrustedHostStore(StringRedisTemplate redis, TrustedHostsProperties props) {
        this.redis = redis;
        this.setKey = props.redisSetKey();
    }

    @PostConstruct
    void init() {
        this.ops = redis.opsForSet();
        logger.info("RedisTrustedHostStore active (setKey='{}')", setKey);
    }

    @Override
    public boolean persist(String key, ScopeService.Mode mode) {
        if (hot.getIfPresent(key) != null) return true;
        try {
            ops.add(setKey, key);
            hot.put(key, PRESENT);
            return true;
        } catch (Exception e) {
            logger.warn("Redis SADD failed for {}: {}", key, e.getMessage());
            hot.put(key, PRESENT); // degrade to in-process only
            return false;
        }
    }

    @Override
    public boolean contains(String key) {
        Boolean cached = hot.getIfPresent(key);
        if (cached != null) return cached;
        try {
            Boolean present = ops.isMember(setKey, key);
            boolean result = Boolean.TRUE.equals(present);
            hot.put(key, result);
            return result;
        } catch (Exception e) {
            logger.debug("Redis SISMEMBER failed for {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public Iterable<String> allTrustedKeys() {
        List<String> out = new ArrayList<>();
        try {
            Set<String> members = ops.members(setKey);
            if (members != null) out.addAll(members);
        } catch (Exception e) {
            logger.debug("Redis SMEMBERS failed: {}", e.getMessage());
        }
        return out;
    }
}
