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
    /**
     * Entries are either exact hosts ("news.example.com") or a bare
     * registrable domain ("example.com"). A URL matches if host equals or
     * ends with ".<entry>".
     */
    private final Set<String> dynamicallyAllowedDomains = new HashSet<>();
    private volatile boolean unrestricted = false;

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
        if (configuredAllowed.isEmpty() && dynamicallyAllowedDomains.isEmpty()) {
            return true;
        }
        if (configuredAllowed.stream().anyMatch(p -> p.matcher(hostLower).find())) return true;
        for (String entry : dynamicallyAllowedDomains) {
            if (hostLower.equals(entry) || hostLower.endsWith("." + entry)) return true;
        }
        return false;
    }

    /**
     * Register a URL that a user explicitly asked us to crawl — its host
     * joins the runtime allow-list. Uses HOST mode (only exact host allowed).
     */
    public void trustSubmission(String url) {
        trustSubmission(url, Mode.HOST);
    }

    /** Scope-expansion modes for a user submission. */
    public enum Mode {
        /** Only exact host (news.example.com). */
        HOST,
        /**
         * Registrable domain (news.example.com → *.example.com — enqueued
         * as a regex fragment).
         */
        DOMAIN,
        /** Any domain — effectively unrestricted for this session. */
        ANY
    }

    public synchronized void trustSubmission(String url, Mode mode) {
        if (mode == Mode.ANY) {
            unrestricted = true;
            logger.info("Runtime scope set to ANY (unrestricted)");
            return;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) return;
            String toAdd = mode == Mode.DOMAIN ? registrableDomain(host) : host.toLowerCase();
            if (dynamicallyAllowedDomains.add(toAdd)) {
                logger.info("Runtime-allow '{}' ({}) via user-submitted URL", toAdd, mode);
            }
        } catch (Exception e) {
            // ignore — malformed, will be rejected later
        }
    }

    /**
     * Best-effort registrable-domain extraction: last two labels of the host.
     * Not TLD-aware (won't handle co.uk correctly) — good enough for the
     * common case; a proper implementation would use publicsuffix data.
     */
    private String registrableDomain(String host) {
        String h = host.toLowerCase();
        String[] parts = h.split("\\.");
        if (parts.length < 2) return h;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    public synchronized Set<String> allowedDomainsSnapshot() {
        Set<String> config = configuredAllowed.stream().map(Pattern::pattern).collect(Collectors.toSet());
        Set<String> all = Stream.concat(config.stream(), dynamicallyAllowedDomains.stream())
                .collect(Collectors.toCollection(HashSet::new));
        if (unrestricted) all.add("<ANY>");
        return all;
    }

    public boolean isUnrestricted() {
        return unrestricted;
    }
}
