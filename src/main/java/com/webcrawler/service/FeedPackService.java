package com.webcrawler.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.webcrawler.model.Feed;
import com.webcrawler.model.FeedPack;
import com.webcrawler.model.FeedPackMember;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Curated feed packs. Seeded from {@code feed-packs.yaml} on startup — after
 * that, the Cassandra {@code feed_packs} + {@code feed_pack_members} tables
 * are authoritative. Operators can add their own packs by INSERTing into
 * those tables at runtime; the seeding is idempotent so redeploying a
 * corrected YAML overwrites the pack in place.
 */
@Service
public class FeedPackService {
    private static final Logger logger = LoggerFactory.getLogger(FeedPackService.class);
    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 900;

    private final CqlSession session;
    private final FeedRepository feeds;

    private final PreparedStatement upsertPack;
    private final PreparedStatement upsertMember;
    private final PreparedStatement selectAllPacks;
    private final PreparedStatement selectPack;
    private final PreparedStatement selectMembers;

    @Autowired
    public FeedPackService(CqlSession session, FeedRepository feeds) {
        this.session = session;
        this.feeds = feeds;
        this.upsertPack = session.prepare(
                "INSERT INTO feed_packs (pack_id, name, description) VALUES (?, ?, ?)");
        this.upsertMember = session.prepare(
                "INSERT INTO feed_pack_members (pack_id, url, title, default_poll_interval_seconds) "
              + "VALUES (?, ?, ?, ?)");
        this.selectAllPacks = session.prepare("SELECT * FROM feed_packs");
        this.selectPack = session.prepare("SELECT * FROM feed_packs WHERE pack_id = ?");
        this.selectMembers = session.prepare(
                "SELECT * FROM feed_pack_members WHERE pack_id = ?");
    }

    @PostConstruct
    public void seedFromYaml() {
        List<FeedPack> parsed = readClasspathYaml("feed-packs.yaml");
        for (FeedPack pack : parsed) {
            session.execute(upsertPack.bind(pack.id(), pack.name(), pack.description()));
            for (FeedPackMember m : pack.feeds()) {
                session.execute(upsertMember.bind(pack.id(), m.url(), m.title(),
                        m.defaultPollIntervalSeconds()));
            }
            logger.info("Seeded feed pack '{}' with {} member(s)", pack.id(), pack.feeds().size());
        }
    }

    public List<FeedPack> listAll() {
        List<FeedPack> out = new ArrayList<>();
        for (Row r : session.execute(selectAllPacks.bind())) {
            out.add(new FeedPack(r.getString("pack_id"), r.getString("name"),
                    r.getString("description"), loadMembers(r.getString("pack_id"))));
        }
        return out;
    }

    public Optional<FeedPack> get(String packId) {
        Row r = session.execute(selectPack.bind(packId)).one();
        if (r == null) return Optional.empty();
        return Optional.of(new FeedPack(
                r.getString("pack_id"), r.getString("name"),
                r.getString("description"), loadMembers(packId)));
    }

    /**
     * Subscribe every member of the pack. Dedup against already-subscribed
     * feeds by URL. Returns the newly-created feeds (existing subscriptions
     * are untouched).
     */
    public List<Feed> subscribeAll(String packId) {
        Optional<FeedPack> pack = get(packId);
        if (pack.isEmpty()) return List.of();
        var existingUrls = new HashMap<String, Feed>();
        for (Feed f : feeds.listAll()) existingUrls.put(f.url(), f);

        List<Feed> created = new ArrayList<>();
        Instant now = Instant.now();
        for (FeedPackMember m : pack.get().feeds()) {
            if (existingUrls.containsKey(m.url())) continue;
            Feed feed = new Feed(
                    UUID.randomUUID(), m.url(), m.title(), packId,
                    m.defaultPollIntervalSeconds() > 0
                            ? m.defaultPollIntervalSeconds() : DEFAULT_POLL_INTERVAL_SECONDS,
                    /*adaptive*/ true, /*followArticles*/ false, /*storeFullContent*/ false,
                    Feed.Status.ACTIVE, null, null, null,
                    now, 0, 0, now, now);
            feeds.create(feed);
            created.add(feed);
        }
        logger.info("Subscribed pack '{}' — added {} new feed(s) ({} already present)",
                packId, created.size(), pack.get().feeds().size() - created.size());
        return created;
    }

    private List<FeedPackMember> loadMembers(String packId) {
        List<FeedPackMember> out = new ArrayList<>();
        for (Row r : session.execute(selectMembers.bind(packId))) {
            int interval = r.isNull("default_poll_interval_seconds")
                    ? DEFAULT_POLL_INTERVAL_SECONDS
                    : r.getInt("default_poll_interval_seconds");
            out.add(new FeedPackMember(r.getString("url"), r.getString("title"), interval));
        }
        return out;
    }

    // ---- YAML parsing ----

    @SuppressWarnings("unchecked")
    static List<FeedPack> readClasspathYaml(String resourceName) {
        try (InputStream in = FeedPackService.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                logger.warn("{} not on classpath — no feed packs seeded", resourceName);
                return List.of();
            }
            Object root = new Yaml().load(in);
            if (!(root instanceof Map<?, ?> rootMap)) return List.of();
            Object packs = rootMap.get("packs");
            if (!(packs instanceof List<?> packList)) return List.of();
            List<FeedPack> out = new ArrayList<>();
            for (Object p : packList) {
                if (!(p instanceof Map<?, ?> pm)) continue;
                String id = String.valueOf(pm.get("id"));
                String name = pm.get("name") == null ? id : String.valueOf(pm.get("name"));
                String description = pm.get("description") == null
                        ? null : String.valueOf(pm.get("description"));
                int defaultInterval = pm.get("default_poll_interval_seconds") instanceof Number n
                        ? n.intValue() : DEFAULT_POLL_INTERVAL_SECONDS;
                List<FeedPackMember> members = new ArrayList<>();
                Object feedsList = pm.get("feeds");
                if (feedsList instanceof List<?> fl) {
                    for (Object f : fl) {
                        if (!(f instanceof Map<?, ?> fm)) continue;
                        String url = String.valueOf(fm.get("url"));
                        String title = fm.get("title") == null ? url : String.valueOf(fm.get("title"));
                        int perFeedInterval = fm.get("poll_interval_seconds") instanceof Number pn
                                ? pn.intValue() : defaultInterval;
                        members.add(new FeedPackMember(url, title, perFeedInterval));
                    }
                }
                out.add(new FeedPack(id, name, description, members));
            }
            return out;
        } catch (Exception e) {
            logger.warn("Failed to load {}: {}", resourceName, e.getMessage());
            return List.of();
        }
    }

}
