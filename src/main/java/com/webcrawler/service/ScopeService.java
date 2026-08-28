package com.webcrawler.service;

import com.webcrawler.config.CrawlerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runtime-mutable scope check for URLs. Anything a user explicitly submits
 * via UI / REST joins the trust store — the previous version held that set
 * in a process-wide {@code HashSet<String>} that grew unbounded on wide
 * crawls.
 *
 * <p>Delegates persistence + hot cache to a {@link TrustedHostStore}
 * (default {@link CassandraTrustedHostStore}). Swap in Redis / any KV
 * later by adding another store implementation.
 */
@Service
public class ScopeService {
    private static final Logger logger = LoggerFactory.getLogger(ScopeService.class);

    private final Set<Pattern> configuredAllowed;
    private final Set<Pattern> configuredExcludes;
    private final TrustedHostStore trustStore;
    private volatile boolean unrestricted = false;

    @Autowired
    public ScopeService(CrawlerProperties properties, TrustedHostStore trustStore) {
        this.configuredAllowed = properties.getAllowedDomainPatterns();
        this.configuredExcludes = properties.getExcludePatternList();
        this.trustStore = trustStore;
    }

    public synchronized boolean allows(String url) {
        if (unrestricted) {
            return configuredExcludes.stream().noneMatch(p -> p.matcher(url).find());
        }
        String host;
        try {
            host = URI.create(url).getHost();
            if (host == null) return false;
        } catch (Exception e) {
            return false;
        }
        String hostLower = host.toLowerCase();
        if (configuredExcludes.stream().anyMatch(p -> p.matcher(url).find())) {
            return false;
        }
        if (configuredAllowed.stream().anyMatch(p -> p.matcher(hostLower).find())) return true;

        // HOST-mode entries are keyed by exact host; DOMAIN-mode entries are
        // keyed by registrable domain. Check both.
        if (trustStore.contains(hostLower)) return true;
        String registrable = registrableDomain(hostLower);
        return !registrable.equals(hostLower) && trustStore.contains(registrable);
    }

    /**
     * Register a URL that a user explicitly asked us to crawl — its host
     * joins the trust store. Uses HOST mode (only exact host allowed).
     */
    public void trustSubmission(String url) {
        trustSubmission(url, Mode.HOST);
    }

    public enum Mode { HOST, DOMAIN, ANY }

    public synchronized void trustSubmission(String url, Mode mode) {
        if (mode == Mode.ANY) {
            unrestricted = true;
            logger.info("Runtime scope set to ANY (unrestricted)");
            return;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) return;
            String key = mode == Mode.DOMAIN ? registrableDomain(host) : host.toLowerCase();
            trustStore.persist(key, mode);
            logger.info("Runtime-allow '{}' ({}) via user-submitted URL", key, mode);
        } catch (Exception e) {
            // ignore — malformed, will be rejected later
        }
    }

    private String registrableDomain(String host) {
        String h = host.toLowerCase();
        String[] parts = h.split("\\.");
        if (parts.length < 2) return h;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    public Set<String> allowedDomainsSnapshot() {
        Set<String> config = configuredAllowed.stream()
                .map(Pattern::pattern).collect(Collectors.toSet());
        Set<String> dynamic = new HashSet<>();
        trustStore.allTrustedKeys().forEach(dynamic::add);
        Set<String> all = Stream.concat(config.stream(), dynamic.stream())
                .collect(Collectors.toCollection(HashSet::new));
        if (unrestricted) all.add("<ANY>");
        return all;
    }

    public boolean isUnrestricted() {
        return unrestricted;
    }
}
