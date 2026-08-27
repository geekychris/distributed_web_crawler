package com.webcrawler.controller;

import com.webcrawler.service.RecordsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/records")
@Tag(name = "Records", description = "Unified iterator over crawled records (pages, feed items)")
public class RecordsController {

    private final RecordsService records;

    @Autowired
    public RecordsController(RecordsService records) {
        this.records = records;
    }

    @GetMapping
    @Operation(summary = "Read a batch of records with a resumable cursor",
            description = """
                    stream:
                      - 'kafka'      → tail the topic (bounded by retention). Cursor is 'kafka:<partition>:<offset>'.
                      - 'cassandra'  → scan the storage table (all history). Cursor is 'cassandra:<base64-paging-state>'.
                    type:
                      - 'page'       → crawler.pages.v1 / crawler.pages
                      - 'feed_item'  → crawler.feed_items.v1 / crawler.feed_items
                    Loop until next_cursor equals the previous cursor (Kafka: end-of-topic)
                    or is null (Cassandra: end-of-scan).
                    """)
    public ResponseEntity<Map<String, Object>> read(
            @RequestParam(defaultValue = "page") @Parameter(description = "page | feed_item")
                    String type,
            @RequestParam(defaultValue = "kafka") @Parameter(description = "kafka | cassandra")
                    String stream,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int limit) {

        RecordsService.Type t;
        try { t = RecordsService.Type.valueOf(type); }
        catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown type: " + type,
                    "supported", "page, feed_item"));
        }

        RecordsService.Batch batch;
        switch (stream.toLowerCase()) {
            case "kafka" -> batch = records.fetchKafka(t, cursor, limit);
            case "cassandra" -> batch = records.fetchCassandra(t, cursor, limit);
            default -> {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "unknown stream: " + stream,
                        "supported", "kafka, cassandra"));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stream", batch.stream());
        out.put("count", batch.count());
        out.put("next_cursor", batch.nextCursor());
        out.put("records", batch.records());
        return ResponseEntity.ok(out);
    }
}
