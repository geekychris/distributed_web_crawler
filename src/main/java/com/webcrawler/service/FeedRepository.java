package com.webcrawler.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.webcrawler.model.Feed;
import com.webcrawler.model.FeedItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cassandra persistence for {@link Feed}s and {@link FeedItem}s. Prepared
 * statements cached at bean-init; dedup via {@code feed_items_by_id}.
 */
@Service
public class FeedRepository {

    private final CqlSession session;
    private final PreparedStatement insertFeed;
    private final PreparedStatement updateFeedPoll;
    private final PreparedStatement updateFeedStatus;
    private final PreparedStatement selectFeed;
    private final PreparedStatement selectAllFeeds;
    private final PreparedStatement deleteFeed;
    private final PreparedStatement insertItem;
    private final PreparedStatement insertItemById;
    private final PreparedStatement itemExistsById;
    private final PreparedStatement selectRecentItems;

    @Autowired
    public FeedRepository(CqlSession session) {
        this.session = session;
        this.insertFeed = session.prepare(
                "INSERT INTO feeds (feed_id, url, title, pack, poll_interval_seconds, adaptive, "
              + "follow_articles, store_full_content, status, etag, last_modified, "
              + "last_polled_at, next_poll_at, consecutive_errors, consecutive_empty, "
              + "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        this.updateFeedPoll = session.prepare(
                "UPDATE feeds SET last_polled_at = ?, next_poll_at = ?, etag = ?, "
              + "last_modified = ?, consecutive_errors = ?, consecutive_empty = ?, "
              + "status = ?, updated_at = ? WHERE feed_id = ?");
        this.updateFeedStatus = session.prepare(
                "UPDATE feeds SET status = ?, updated_at = ? WHERE feed_id = ?");
        this.selectFeed = session.prepare("SELECT * FROM feeds WHERE feed_id = ?");
        this.selectAllFeeds = session.prepare("SELECT * FROM feeds");
        this.deleteFeed = session.prepare("DELETE FROM feeds WHERE feed_id = ?");
        this.insertItem = session.prepare(
                "INSERT INTO feed_items (feed_id, first_seen, item_id, url, title, summary, "
              + "content_snippet, author, categories, enclosures, published_at, updated_at, "
              + "followed_page_url) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)");
        this.insertItemById = session.prepare(
                "INSERT INTO feed_items_by_id (item_id, feed_id, url, first_seen) "
              + "VALUES (?,?,?,?) IF NOT EXISTS");
        this.itemExistsById = session.prepare(
                "SELECT item_id FROM feed_items_by_id WHERE item_id = ? LIMIT 1");
        this.selectRecentItems = session.prepare(
                "SELECT * FROM feed_items WHERE feed_id = ? LIMIT ?");
    }

    public Feed create(Feed feed) {
        session.execute(insertFeed.bind(
                feed.feedId(), feed.url(), feed.title(), feed.pack(),
                feed.pollIntervalSeconds(), feed.adaptive(), feed.followArticles(),
                feed.storeFullContent(), feed.status().name(),
                feed.etag(), feed.lastModified(),
                feed.lastPolledAt(), feed.nextPollAt(),
                feed.consecutiveErrors(), feed.consecutiveEmpty(),
                feed.createdAt(), feed.updatedAt()));
        return feed;
    }

    public Optional<Feed> get(UUID feedId) {
        Row r = session.execute(selectFeed.bind(feedId)).one();
        return r == null ? Optional.empty() : Optional.of(rowToFeed(r));
    }

    public List<Feed> listAll() {
        List<Feed> out = new ArrayList<>();
        session.execute(selectAllFeeds.bind()).forEach(r -> out.add(rowToFeed(r)));
        return out;
    }

    public void updatePollResult(Feed feed) {
        session.execute(updateFeedPoll.bind(
                feed.lastPolledAt(), feed.nextPollAt(), feed.etag(), feed.lastModified(),
                feed.consecutiveErrors(), feed.consecutiveEmpty(),
                feed.status().name(), Instant.now(),
                feed.feedId()));
    }

    public void updateStatus(UUID feedId, Feed.Status status) {
        session.execute(updateFeedStatus.bind(status.name(), Instant.now(), feedId));
    }

    public void delete(UUID feedId) {
        session.execute(deleteFeed.bind(feedId));
    }

    /**
     * @return true if this item was newly recorded; false if the itemId was
     *         already known (dedup hit).
     */
    public boolean recordItemIfNew(FeedItem item) {
        var applied = session.execute(insertItemById.bind(
                item.itemId(), item.feedId(), item.url(), item.firstSeen()));
        Row row = applied.one();
        boolean wasApplied = row != null && row.getBoolean("[applied]");
        if (!wasApplied) return false;
        session.execute(insertItem.bind(
                item.feedId(), item.firstSeen(), item.itemId(),
                item.url(), item.title(), item.summary(), item.contentSnippet(),
                item.author(), item.categories(), item.enclosures(),
                item.publishedAt(), item.updatedAt(), item.followedPageUrl()));
        return true;
    }

    public boolean itemExists(String itemId) {
        return session.execute(itemExistsById.bind(itemId)).one() != null;
    }

    public List<FeedItem> recentItems(UUID feedId, int limit) {
        List<FeedItem> out = new ArrayList<>();
        session.execute(selectRecentItems.bind(feedId, limit))
                .forEach(r -> out.add(rowToItem(r)));
        return out;
    }

    private Feed rowToFeed(Row r) {
        return new Feed(
                r.getUuid("feed_id"),
                r.getString("url"),
                r.getString("title"),
                r.getString("pack"),
                r.isNull("poll_interval_seconds") ? 900 : r.getInt("poll_interval_seconds"),
                !r.isNull("adaptive") && r.getBoolean("adaptive"),
                !r.isNull("follow_articles") && r.getBoolean("follow_articles"),
                !r.isNull("store_full_content") && r.getBoolean("store_full_content"),
                r.getString("status") == null ? Feed.Status.ACTIVE
                        : Feed.Status.valueOf(r.getString("status")),
                r.getString("etag"),
                r.getString("last_modified"),
                r.getInstant("last_polled_at"),
                r.getInstant("next_poll_at"),
                r.isNull("consecutive_errors") ? 0 : r.getInt("consecutive_errors"),
                r.isNull("consecutive_empty") ? 0 : r.getInt("consecutive_empty"),
                r.getInstant("created_at"),
                r.getInstant("updated_at"));
    }

    private FeedItem rowToItem(Row r) {
        List<Map<String, String>> enclosures = new ArrayList<>();
        try {
            List<Map<String, String>> raw = r.getList("enclosures",
                    (Class<Map<String, String>>) (Class<?>) Map.class);
            if (raw != null) enclosures = raw;
        } catch (Exception ignored) {}
        return new FeedItem(
                r.getUuid("feed_id"),
                r.getString("item_id"),
                r.getString("url"),
                r.getString("title"),
                r.getString("summary"),
                r.getString("content_snippet"),
                r.getString("author"),
                r.getSet("categories", String.class) == null
                        ? new HashSet<>() : new HashSet<>(r.getSet("categories", String.class)),
                new ArrayList<>(enclosures),
                r.getInstant("published_at"),
                r.getInstant("updated_at"),
                r.getInstant("first_seen"),
                r.getString("followed_page_url"));
    }

    /** Convenience — build the enclosures map shape stored in Cassandra. */
    public static Map<String, String> enclosure(String url, String type, long length) {
        Map<String, String> m = new LinkedHashMap<>();
        if (url != null) m.put("url", url);
        if (type != null) m.put("type", type);
        m.put("length", String.valueOf(length));
        return m;
    }
}
