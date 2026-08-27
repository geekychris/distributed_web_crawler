package com.webcrawler.controller;

import com.webcrawler.model.Feed;
import com.webcrawler.model.FeedItem;
import com.webcrawler.service.FeedPoller;
import com.webcrawler.service.FeedRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/feeds")
@Tag(name = "Feeds", description = "RSS/Atom feed subscriptions")
public class FeedController {

    private final FeedRepository feeds;
    private final FeedPoller poller;

    @Autowired
    public FeedController(FeedRepository feeds, FeedPoller poller) {
        this.feeds = feeds;
        this.poller = poller;
    }

    public record CreateFeedRequest(
            String url,
            String title,
            String pack,
            Integer pollIntervalSeconds,
            Boolean adaptive,
            Boolean followArticles,
            Boolean storeFullContent) {}

    @PostMapping
    @Operation(summary = "Subscribe to a new feed")
    public ResponseEntity<Feed> create(@RequestBody CreateFeedRequest req) {
        if (req.url() == null || req.url().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Instant now = Instant.now();
        Feed feed = new Feed(
                UUID.randomUUID(), req.url().trim(), req.title(), req.pack(),
                req.pollIntervalSeconds() == null || req.pollIntervalSeconds() < 30
                        ? 900 : req.pollIntervalSeconds(),
                Boolean.TRUE.equals(req.adaptive()),
                Boolean.TRUE.equals(req.followArticles()),
                Boolean.TRUE.equals(req.storeFullContent()),
                Feed.Status.ACTIVE, null, null, null,
                // Poll immediately on next tick.
                now,
                /*consecutiveErrors*/ 0, /*consecutiveEmpty*/ 0,
                now, now);
        feeds.create(feed);
        return ResponseEntity.ok(feed);
    }

    @GetMapping
    @Operation(summary = "List all subscribed feeds")
    public ResponseEntity<List<Feed>> list() {
        return ResponseEntity.ok(feeds.listAll());
    }

    @GetMapping("/{feedId}")
    @Operation(summary = "Get a single feed")
    public ResponseEntity<Feed> get(@PathVariable UUID feedId) {
        Optional<Feed> f = feeds.get(feedId);
        return f.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{feedId}/pause")
    @Operation(summary = "Pause a feed — poller will skip it")
    public ResponseEntity<Void> pause(@PathVariable UUID feedId) {
        if (feeds.get(feedId).isEmpty()) return ResponseEntity.notFound().build();
        feeds.updateStatus(feedId, Feed.Status.PAUSED);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{feedId}/resume")
    @Operation(summary = "Resume a paused feed")
    public ResponseEntity<Void> resume(@PathVariable UUID feedId) {
        if (feeds.get(feedId).isEmpty()) return ResponseEntity.notFound().build();
        feeds.updateStatus(feedId, Feed.Status.ACTIVE);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{feedId}/poll")
    @Operation(summary = "Force an immediate poll of this feed (bypasses cadence)")
    public ResponseEntity<Void> forcePoll(@PathVariable UUID feedId) {
        Optional<Feed> feed = feeds.get(feedId);
        if (feed.isEmpty()) return ResponseEntity.notFound().build();
        poller.poll(feed.get());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{feedId}/items")
    @Operation(summary = "Recent feed items")
    public ResponseEntity<List<FeedItem>> items(
            @PathVariable UUID feedId,
            @RequestParam(defaultValue = "50") int limit) {
        if (feeds.get(feedId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(feeds.recentItems(feedId, Math.max(1, Math.min(500, limit))));
    }

    @DeleteMapping("/{feedId}")
    @Operation(summary = "Unsubscribe from a feed")
    public ResponseEntity<Void> delete(@PathVariable UUID feedId) {
        feeds.delete(feedId);
        return ResponseEntity.noContent().build();
    }
}
