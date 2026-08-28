package com.webcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Selects the backend for {@link com.webcrawler.service.TrustedHostStore}.
 * Cassandra is the default so existing deployments don't need to run Redis;
 * set {@code trusted-hosts.backend=redis} (or {@code TRUSTED_HOSTS_BACKEND=redis})
 * to switch to the lower-latency KV path.
 */
@ConfigurationProperties(prefix = "trusted-hosts")
public record TrustedHostsProperties(
        @DefaultValue("cassandra") String backend,
        @DefaultValue("trusted:hosts") String redisSetKey
) {}
