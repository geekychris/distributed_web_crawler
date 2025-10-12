package com.webcrawler.config;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

public record CrawlerConfig(
    int maxDepth,
    Duration crawlDelay,
    int maxConcurrentRequests,
    Set<Pattern> allowedDomains,
    Set<Pattern> excludePatterns,
    Set<String> seedUrls,
    boolean respectRobotsTxt,
    String userAgent
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxDepth = 10;
        private Duration crawlDelay = Duration.ofSeconds(1);
        private int maxConcurrentRequests = 100;
        private Set<Pattern> allowedDomains = Set.of();
        private Set<Pattern> excludePatterns = Set.of();
        private Set<String> seedUrls = Set.of();
        private boolean respectRobotsTxt = true;
        private String userAgent = "DistributedCrawler/1.0";

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public Builder crawlDelay(Duration crawlDelay) {
            this.crawlDelay = crawlDelay;
            return this;
        }

        public Builder maxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
            return this;
        }

        public Builder allowedDomains(Set<Pattern> allowedDomains) {
            this.allowedDomains = allowedDomains;
            return this;
        }

        public Builder excludePatterns(Set<Pattern> excludePatterns) {
            this.excludePatterns = excludePatterns;
            return this;
        }

        public Builder seedUrls(Set<String> seedUrls) {
            this.seedUrls = seedUrls;
            return this;
        }

        public Builder respectRobotsTxt(boolean respectRobotsTxt) {
            this.respectRobotsTxt = respectRobotsTxt;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public CrawlerConfig build() {
            return new CrawlerConfig(
                maxDepth,
                crawlDelay,
                maxConcurrentRequests,
                allowedDomains,
                excludePatterns,
                seedUrls,
                respectRobotsTxt,
                userAgent
            );
        }
    }
}
