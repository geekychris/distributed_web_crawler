package com.webcrawler.controller;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Streaming NDJSON (also called JSONL) export. Each line is one JSON object,
 * newline-delimited, so a downstream consumer can decode as they read.
 * Streamed via {@link StreamingResponseBody} — the entire dataset never sits
 * in server memory. Content-Type is {@code application/x-ndjson}.
 */
@RestController
@RequestMapping("/api/export")
@Tag(name = "Export", description = "NDJSON (JSONL) export of crawled pages and feed items")
public class ExportController {
    private static final Logger logger = LoggerFactory.getLogger(ExportController.class);
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final int DEFAULT_LIMIT = 10_000;
    private static final int MAX_LIMIT = 500_000;

    private final CqlSession session;
    private final ObjectMapper mapper;

    @Autowired
    public ExportController(CqlSession session) {
        this.session = session;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @GetMapping(value = "/pages.ndjson", produces = "application/x-ndjson")
    @Operation(summary = "Stream crawled pages as NDJSON",
            description = """
                    Each line is a JSON object with:
                      url, content_hash, fetch_time, http_status, headers, links,
                      metadata, s3_key, job_id
                    Streamed via chunked-transfer; safe for very large exports.
                    Suggested consumer: curl … | jq -c 'select(.job_id == "...")'
                    """)
    public ResponseEntity<StreamingResponseBody> exportPages(
            @RequestParam(defaultValue = "10000")
            @Parameter(description = "Max rows to emit (cap 500k)") int limit) {
        int cap = clamp(limit);
        String cql = "SELECT url, content_hash, fetch_time, http_status, headers, "
                   + "links, metadata, s3_key, job_id FROM pages LIMIT " + cap;
        return streaming("crawler-pages", cql, this::pageRow);
    }

    @GetMapping(value = "/feed_items.ndjson", produces = "application/x-ndjson")
    @Operation(summary = "Stream feed items as NDJSON",
            description = """
                    Each line is a JSON object with:
                      feed_id, item_id, url, title, summary, content_snippet,
                      author, categories, enclosures, published_at, updated_at,
                      first_seen, followed_page_url
                    Optionally scoped to a single feed via the feedId query param.
                    """)
    public ResponseEntity<StreamingResponseBody> exportFeedItems(
            @RequestParam(defaultValue = "10000")
            @Parameter(description = "Max rows to emit (cap 500k)") int limit,
            @RequestParam(required = false)
            @Parameter(description = "Optional: single feed uuid") UUID feedId) {
        int cap = clamp(limit);
        String cql;
        if (feedId != null) {
            cql = "SELECT * FROM feed_items WHERE feed_id = " + feedId + " LIMIT " + cap;
        } else {
            cql = "SELECT * FROM feed_items LIMIT " + cap;
        }
        return streaming("crawler-feed-items", cql, this::feedItemRow);
    }

    private ResponseEntity<StreamingResponseBody> streaming(
            String filenamePrefix, String cql, java.util.function.Function<Row, Map<String, Object>> mapRow) {
        String filename = filenamePrefix + "-" + Instant.now().toString().replace(':', '-') + ".ndjson";
        StreamingResponseBody body = (OutputStream out) -> {
            try {
                for (Row r : session.execute(SimpleStatement.builder(cql).setPageSize(1000).build())) {
                    Map<String, Object> m = mapRow.apply(r);
                    out.write(mapper.writeValueAsBytes(m));
                    out.write('\n');
                }
                out.flush();
            } catch (Exception e) {
                logger.warn("Export stream failed: {}", e.getMessage());
                // At this point the response body may already be partial —
                // best we can do is close the stream; the client sees an
                // incomplete NDJSON file (usually parseable up to the last
                // complete line).
            }
        };
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    private Map<String, Object> pageRow(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", r.getString("url"));
        m.put("content_hash", r.getString("content_hash"));
        m.put("fetch_time", r.getInstant("fetch_time"));
        m.put("http_status", r.getInt("http_status"));
        m.put("headers", r.getMap("headers", String.class, String.class));
        m.put("links", r.getSet("links", String.class));
        m.put("metadata", r.getMap("metadata", String.class, String.class));
        m.put("s3_key", r.getString("s3_key"));
        m.put("job_id", r.isNull("job_id") ? null : r.getUuid("job_id"));
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> feedItemRow(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("feed_id", r.getUuid("feed_id"));
        m.put("item_id", r.getString("item_id"));
        m.put("url", r.getString("url"));
        m.put("title", r.getString("title"));
        m.put("summary", r.getString("summary"));
        m.put("content_snippet", r.getString("content_snippet"));
        m.put("author", r.getString("author"));
        m.put("categories", r.getSet("categories", String.class));
        try {
            List<Map<String, String>> enclosures = r.getList("enclosures",
                    (Class<Map<String, String>>) (Class<?>) Map.class);
            m.put("enclosures", enclosures);
        } catch (Exception e) {
            m.put("enclosures", List.of());
        }
        m.put("published_at", r.getInstant("published_at"));
        m.put("updated_at", r.getInstant("updated_at"));
        m.put("first_seen", r.getInstant("first_seen"));
        m.put("followed_page_url", r.getString("followed_page_url"));
        return m;
    }

    private static int clamp(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }
}
