package com.webcrawler.model;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record PageContent(
    String url,
    String contentHash,
    String content,
    Instant fetchTime,
    int httpStatus,
    Map<String, String> headers,
    Set<String> links,
    Map<String, String> metadata,
    UUID jobId
) {
    /** Legacy constructor — jobId defaults to null (unassigned pages, backward compatible). */
    public PageContent(String url, String contentHash, String content, Instant fetchTime,
                       int httpStatus, Map<String, String> headers, Set<String> links,
                       Map<String, String> metadata) {
        this(url, contentHash, content, fetchTime, httpStatus, headers, links, metadata, null);
    }
}
