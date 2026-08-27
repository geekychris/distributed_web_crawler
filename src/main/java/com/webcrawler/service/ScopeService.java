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
 * Runtime-mutable version of {@link CrawlerProperties#allowedDomains()}. The
 * configured allow-list is loaded at startup; anything a user explicitly
 * submits via UI / REST is auto-added ("trust the human — they asked for it").
 * The immutable properties list stays as the seed-list allowlist for
 * autostart; ScopeService supersedes it for all runtime admission checks.
 */
@Service
public class ScopeService {
    private static final Logger logger = LoggerFactory.getLogger(ScopeService.class);

    private final Set<Pattern> configuredAllowed;
    private final Set<Pattern> configuredExcludes;
    private final Set<String> dynamicallyAllowedDomains = new HashSet<>();

    @Autowired
    public ScopeService(CrawlerProperties properties) {
        this.configuredAllowed = properties.getAllowedDomainPatterns();
        this.configuredExcludes = properties.getExcludePatternList();
    }

    /**
     * True if the URL's host is in-scope. If no allow-list is configured AND
     * no dynamic hosts have been added, everything passes (opt-in scope).
     */
    public synchronized boolean allows(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
            if (host == null) return false;
        } catch (Exception e) {
            return false;
        }
        if (configuredExcludes.stream().anyMatch(p -> p.matcher(url).find())) {
            return false;
        }
        if (configuredAllowed.isEmpty() && dynamicallyAllowedDomains.isEmpty()) {
            return true;
        }
        if (configuredAllowed.stream().anyMatch(p -> p.matcher(host).find())) return true;
        return dynamicallyAllowedDomains.contains(host.toLowerCase());
    }

    /**
     * Register a URL that a user explicitly asked us to crawl — its host joins
     * the runtime allow-list. Idempotent; safe to call on every submit.
     */
    public synchronized void trustSubmission(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return;
            if (dynamicallyAllowedDomains.add(host.toLowerCase())) {
                logger.info("Runtime-allow domain '{}' (user-submitted URL)", host);
            }
        } catch (Exception e) {
            // ignore — the URL is malformed, will be rejected later
        }
    }

    public synchronized Set<String> allowedDomainsSnapshot() {
        Set<String> config = configuredAllowed.stream().map(Pattern::pattern).collect(Collectors.toSet());
        return Stream.concat(config.stream(), dynamicallyAllowedDomains.stream())
                .collect(Collectors.toCollection(HashSet::new));
    }
}
