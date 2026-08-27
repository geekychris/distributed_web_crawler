package com.webcrawler.service;

import com.webcrawler.config.CrawlerProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeServiceTest {

    private static ScopeService svc(List<String> allowed, List<String> excludes) {
        CrawlerProperties props = new CrawlerProperties(
                5, Duration.ofSeconds(1), 10,
                allowed, excludes, List.of(),
                true, "test/1.0", 3, true, 10,
                Duration.ofSeconds(5), Duration.ofMinutes(5),
                2_097_152, true, true, -1, -1, -1);
        return new ScopeService(props);
    }

    @Test
    void emptyScopeAllowsAnyUrl() {
        ScopeService s = svc(List.of(), List.of());
        assertTrue(s.allows("https://random.example/"));
    }

    @Test
    void configuredRegexIsHonoured() {
        ScopeService s = svc(List.of("wikipedia\\.org$"), List.of());
        assertTrue(s.allows("https://en.wikipedia.org/wiki/Amiga"));
        assertFalse(s.allows("https://cnn.com/"));
    }

    @Test
    void excludesTakePrecedence() {
        ScopeService s = svc(List.of("example\\.com$"), List.of("/private/"));
        assertTrue(s.allows("https://example.com/public/"));
        assertFalse(s.allows("https://example.com/private/secret"));
    }

    @Test
    void hostModeMatchesExactHostOnly() {
        ScopeService s = svc(List.of(), List.of());
        s.trustSubmission("https://news.example.com/", ScopeService.Mode.HOST);
        assertTrue(s.allows("https://news.example.com/a"));
        assertFalse(s.allows("https://www.example.com/"));
        assertFalse(s.allows("https://example.com/"));
    }

    @Test
    void domainModeMatchesRegistrableDomainAndSubs() {
        ScopeService s = svc(List.of(), List.of());
        s.trustSubmission("https://news.example.com/", ScopeService.Mode.DOMAIN);
        assertTrue(s.allows("https://news.example.com/a"));
        assertTrue(s.allows("https://blog.example.com/"));
        assertTrue(s.allows("https://example.com/"));
        assertFalse(s.allows("https://example.org/"));
    }

    @Test
    void anyModeUnrestricted() {
        ScopeService s = svc(List.of("only\\.this$"), List.of());
        s.trustSubmission("https://whatever/", ScopeService.Mode.ANY);
        assertTrue(s.allows("https://cnn.com/"));
        assertTrue(s.allows("https://obscure.co.jp/"));
        assertTrue(s.isUnrestricted());
    }

    @Test
    void anyModeStillRespectsExcludes() {
        ScopeService s = svc(List.of(), List.of("\\.pdf$"));
        s.trustSubmission("https://x/", ScopeService.Mode.ANY);
        assertTrue(s.allows("https://cnn.com/story"));
        assertFalse(s.allows("https://cnn.com/report.pdf"));
    }

    @Test
    void hostMatchingIsCaseInsensitive() {
        ScopeService s = svc(List.of(), List.of());
        s.trustSubmission("https://Example.COM/", ScopeService.Mode.HOST);
        assertTrue(s.allows("https://EXAMPLE.com/foo"));
    }

    @Test
    void invalidUrlRejected() {
        ScopeService s = svc(List.of(), List.of());
        s.trustSubmission("https://good.example/", ScopeService.Mode.HOST);
        assertFalse(s.allows("::garbage::"));
    }
}
