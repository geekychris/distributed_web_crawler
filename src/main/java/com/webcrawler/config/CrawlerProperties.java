package com.webcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "crawler")
public record CrawlerProperties(
    @DefaultValue("10") int maxDepth,
    @DefaultValue("PT1S") Duration crawlDelay,
    @DefaultValue("100") int maxConcurrentRequests,
    @DefaultValue List<String> allowedDomains,
    @DefaultValue List<String> excludePatterns,
    @DefaultValue List<String> seedUrls,
    @DefaultValue("true") boolean respectRobotsTxt,
    @DefaultValue("DistributedCrawler/1.0") String userAgent
) {
    public Set<Pattern> getAllowedDomainPatterns() {
        return allowedDomains != null ? 
            allowedDomains.stream()
                .map(Pattern::compile)
                .collect(Collectors.toSet()) : 
            Set.of();
    }
    
    public Set<Pattern> getExcludePatternList() {
        return excludePatterns != null ? 
            excludePatterns.stream()
                .map(Pattern::compile)
                .collect(Collectors.toSet()) : 
            Set.of();
    }
    
    public Set<String> getSeedUrlSet() {
        return seedUrls != null ? Set.copyOf(seedUrls) : Set.of();
    }
}
