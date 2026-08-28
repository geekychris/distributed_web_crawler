package com.webcrawler.model;

/** A single feed inside a {@link FeedPack}. */
public record FeedPackMember(
        String url,
        String title,
        int defaultPollIntervalSeconds
) {}
