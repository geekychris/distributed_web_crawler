package com.webcrawler.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlerPropertiesTest {

    private CrawlerProperties props(List<String> allowed, List<String> excludes, List<String> seeds) {
        return new CrawlerProperties(
                5, Duration.ofSeconds(1), 10,
                allowed, excludes, seeds,
                true, "test/1.0", 3, true, 10,
                Duration.ofSeconds(5), Duration.ofMinutes(5),
                2_097_152, true, true, -1, -1, -1);
    }

    @Test
    void patternCompilationHandlesEmptyList() {
        CrawlerProperties p = props(List.of(), List.of(), List.of());
        assertTrue(p.getAllowedDomainPatterns().isEmpty());
        assertTrue(p.getExcludePatternList().isEmpty());
        assertTrue(p.getSeedUrlSet().isEmpty());
    }

    @Test
    void patternCompilationHonoursRegexes() {
        CrawlerProperties p = props(
                List.of("example\\.com$", "wikipedia\\.org$"),
                List.of("/private/.*"),
                List.of());
        var compiled = p.getAllowedDomainPatterns();
        assertEquals(2, compiled.size());
        assertTrue(compiled.stream().anyMatch(pat ->
                pat.matcher("www.example.com").find()));
        assertTrue(p.getExcludePatternList().stream().anyMatch(pat ->
                pat.matcher("/private/foo").find()));
    }

    @Test
    void seedUrlsDeduplicate() {
        CrawlerProperties p = props(List.of(), List.of(),
                List.of("https://a/", "https://b/", "https://a/"));
        assertEquals(2, p.getSeedUrlSet().size());
    }

    @Test
    void nullAllowedListSafe() {
        CrawlerProperties p = props(null, null, null);
        assertTrue(p.getAllowedDomainPatterns().isEmpty());
        assertTrue(p.getExcludePatternList().isEmpty());
        assertTrue(p.getSeedUrlSet().isEmpty());
    }
}
