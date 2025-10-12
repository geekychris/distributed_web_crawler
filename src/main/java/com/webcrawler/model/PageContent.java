package com.webcrawler.model;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record PageContent(
    String url,
    String contentHash,
    String content,
    Instant fetchTime,
    int httpStatus,
    Map<String, String> headers,
    Set<String> links,
    Map<String, String> metadata
) {}
