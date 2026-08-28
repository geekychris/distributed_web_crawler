package com.webcrawler.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A single item (article / episode / entry) parsed from an RSS or Atom feed.
 * The parent {@link Feed} owns polling cadence and dedup identity; each
 * item is emitted at most once, keyed by {@link #itemId()}.
 */
public record FeedItem(
        UUID feedId,
        String itemId,
        String url,
        String title,
        String summary,
        String contentSnippet,
        String author,
        Set<String> categories,
        List<Map<String, String>> enclosures,
        Instant publishedAt,
        Instant updatedAt,
        Instant firstSeen,
        String followedPageUrl
) {}
