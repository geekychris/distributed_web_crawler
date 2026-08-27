package com.webcrawler.controller;

import com.webcrawler.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@Tag(name = "Stats", description = "Aggregate counters and health snapshots")
public class StatsController {

    private final StatsService stats;

    @Autowired
    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/summary")
    @Operation(summary = "Aggregate snapshot",
            description = """
                    Returns:
                      pages_total, feed_items_total (both cached; may lag by a few seconds)
                      feeds: {total, by_status, adaptive, follow_articles}
                      jobs:  {total, by_status}
                      activity: {crawled, rejected, error, pages_per_minute} for the last 5 minutes
                      unhealthy_feeds: top 10 sorted by error streak (helps operator triage)
                    """)
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(stats.summary());
    }
}
