package com.webcrawler.controller;

import com.webcrawler.model.Feed;
import com.webcrawler.model.FeedPack;
import com.webcrawler.service.FeedPackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/feed-packs")
@Tag(name = "Feed Packs", description = "Curated bundles of feeds")
public class FeedPackController {

    private final FeedPackService packs;

    @Autowired
    public FeedPackController(FeedPackService packs) {
        this.packs = packs;
    }

    @GetMapping
    @Operation(summary = "List all available feed packs")
    public ResponseEntity<List<FeedPack>> list() {
        return ResponseEntity.ok(packs.listAll());
    }

    @GetMapping("/{packId}")
    @Operation(summary = "Get a single pack with all its member feeds")
    public ResponseEntity<FeedPack> get(@PathVariable String packId) {
        Optional<FeedPack> pack = packs.get(packId);
        return pack.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{packId}/subscribe")
    @Operation(summary = "Subscribe to every feed in the pack (dedup on URL)")
    public ResponseEntity<Map<String, Object>> subscribe(@PathVariable String packId) {
        Optional<FeedPack> pack = packs.get(packId);
        if (pack.isEmpty()) return ResponseEntity.notFound().build();
        List<Feed> created = packs.subscribeAll(packId);
        return ResponseEntity.ok(Map.of(
                "packId", packId,
                "packName", pack.get().name(),
                "totalInPack", pack.get().feeds().size(),
                "created", created.size(),
                "alreadySubscribed", pack.get().feeds().size() - created.size(),
                "feeds", created));
    }
}
