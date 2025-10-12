package com.webcrawler.model;

import java.time.Instant;

public record CrawlRequest(
    String url,
    int depth,
    String parentUrl,
    Instant discoveredAt,
    int priority
) {}
