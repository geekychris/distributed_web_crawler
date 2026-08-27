package com.webcrawler.model;

import java.util.List;

/**
 * A themed bundle of feeds ("tech", "news", etc.) that a user can subscribe
 * to in one call. Definitions are seeded from feed-packs.yaml at boot; the
 * Cassandra {@code feed_packs} + {@code feed_pack_members} tables are the
 * runtime source of truth.
 */
public record FeedPack(
        String id,
        String name,
        String description,
        List<FeedPackMember> feeds
) {}
