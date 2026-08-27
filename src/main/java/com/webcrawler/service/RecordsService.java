package com.webcrawler.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webcrawler.config.KafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Unified iterator over crawled records. Two backends behind one API:
 *
 * <ul>
 *   <li><b>Kafka</b> — real-time-ish, bounded by topic retention. Cursor is
 *       {@code kafka:<partition>:<offset>}. Each call opens a short-lived
 *       consumer, seeks to the position, polls a batch, closes.</li>
 *   <li><b>Cassandra</b> — full history, ordered by internal token — good
 *       for backfill and downstream reprocessing. Cursor is
 *       {@code cassandra:<base64-paging-state>} or empty to start.</li>
 * </ul>
 *
 * <p>Both paths return {@code {records, next_cursor, count, stream}}. Downstream
 * consumers loop until {@code next_cursor} equals the previous cursor (Kafka:
 * end-of-topic) or is null (Cassandra: end-of-scan).
 */
@Service
public class RecordsService {
    private static final Logger logger = LoggerFactory.getLogger(RecordsService.class);
    private static final int MAX_LIMIT = 500;

    private final CqlSession session;
    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public RecordsService(CqlSession session, KafkaProperties kafkaProperties) {
        this.session = session;
        this.kafkaProperties = kafkaProperties;
    }

    public Batch fetchKafka(Type type, String cursor, int limit) {
        String topic = topicFor(type);
        int effectiveLimit = clampLimit(limit);

        String bootstrap = kafkaProperties.bootstrapServers();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        // Unique group id so we don't share offsets with the crawler.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "records-iter-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(effectiveLimit));

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic);
            if (partitions == null || partitions.isEmpty()) {
                return new Batch(List.of(), null, 0, "kafka");
            }
            // MVP: single-partition topics (crawler.pages.v1 and
            // crawler.feed_items.v1 are P=1 today). If we ever scale, this
            // path needs to iterate across partitions.
            TopicPartition tp = new TopicPartition(topic, partitions.get(0).partition());
            consumer.assign(List.of(tp));
            long offset = parseKafkaCursor(cursor);
            if (offset < 0) {
                consumer.seekToBeginning(List.of(tp));
                offset = consumer.position(tp);
            } else {
                consumer.seek(tp, offset);
            }

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            List<Map<String, Object>> out = new ArrayList<>(records.count());
            long lastOffset = offset - 1;
            for (ConsumerRecord<String, String> rec : records) {
                Map<String, Object> parsed;
                try {
                    parsed = objectMapper.readValue(rec.value(), Map.class);
                } catch (Exception e) {
                    parsed = Map.of("_raw", rec.value(), "_parse_error", e.getMessage());
                }
                out.add(parsed);
                lastOffset = rec.offset();
                if (out.size() >= effectiveLimit) break;
            }
            String nextCursor = "kafka:" + tp.partition() + ":" + (lastOffset + 1);
            return new Batch(out, nextCursor, out.size(), "kafka");
        }
    }

    public Batch fetchCassandra(Type type, String cursor, int limit) {
        int effectiveLimit = clampLimit(limit);
        String cql = switch (type) {
            case page -> "SELECT url, content_hash, fetch_time, http_status, headers, links, "
                       + "metadata, s3_key, job_id FROM pages";
            case feed_item -> "SELECT feed_id, item_id, url, title, summary, content_snippet, "
                            + "author, categories, enclosures, published_at, updated_at, "
                            + "first_seen FROM feed_items";
        };
        SimpleStatement stmt = SimpleStatement.builder(cql)
                .setPageSize(effectiveLimit)
                .build();
        ByteBuffer pagingState = parseCassandraCursor(cursor);
        if (pagingState != null) {
            stmt = stmt.setPagingState(pagingState);
        }
        ResultSet rs = session.execute(stmt);

        List<Map<String, Object>> out = new ArrayList<>();
        int available = rs.getAvailableWithoutFetching();
        for (int i = 0; i < available && out.size() < effectiveLimit; i++) {
            Row row = rs.one();
            if (row == null) break;
            out.add(type == Type.page ? pageRow(row) : feedItemRow(row));
        }
        ByteBuffer nextState = rs.getExecutionInfo().getPagingState();
        String nextCursor = nextState == null ? null
                : "cassandra:" + Base64.getEncoder().encodeToString(bufferBytes(nextState));
        return new Batch(out, nextCursor, out.size(), "cassandra");
    }

    private Map<String, Object> pageRow(Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", row.getString("url"));
        m.put("content_hash", row.getString("content_hash"));
        m.put("fetch_time", row.getInstant("fetch_time"));
        m.put("http_status", row.getInt("http_status"));
        m.put("headers", row.getMap("headers", String.class, String.class));
        m.put("links", row.getSet("links", String.class));
        m.put("metadata", row.getMap("metadata", String.class, String.class));
        m.put("s3_key", row.getString("s3_key"));
        m.put("job_id", row.isNull("job_id") ? null : row.getUuid("job_id"));
        return m;
    }

    private Map<String, Object> feedItemRow(Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("feed_id", row.getUuid("feed_id"));
        m.put("item_id", row.getString("item_id"));
        m.put("url", row.getString("url"));
        m.put("title", row.getString("title"));
        m.put("summary", row.getString("summary"));
        m.put("content_snippet", row.getString("content_snippet"));
        m.put("author", row.getString("author"));
        m.put("categories", row.getSet("categories", String.class));
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> enclosures = row.getList("enclosures",
                    (Class<Map<String, String>>) (Class<?>) Map.class);
            m.put("enclosures", enclosures);
        } catch (Exception e) {
            m.put("enclosures", List.of());
        }
        m.put("published_at", row.getInstant("published_at"));
        m.put("updated_at", row.getInstant("updated_at"));
        m.put("first_seen", row.getInstant("first_seen"));
        return m;
    }

    private String topicFor(Type type) {
        return switch (type) {
            case page -> kafkaProperties.pageEventsTopic();
            case feed_item -> kafkaProperties.feedEventsTopic();
        };
    }

    /** Parse "kafka:P:O" or "kafka:O" — returns offset, or -1 for "start". */
    static long parseKafkaCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return -1L;
        String[] parts = cursor.split(":");
        if (parts.length < 2 || !"kafka".equalsIgnoreCase(parts[0])) return -1L;
        try {
            return Long.parseLong(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** Parse "cassandra:<base64>" — returns null (start) or the ByteBuffer state. */
    static ByteBuffer parseCassandraCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        String[] parts = cursor.split(":", 2);
        if (parts.length < 2 || !"cassandra".equalsIgnoreCase(parts[0])) return null;
        try {
            return ByteBuffer.wrap(Base64.getDecoder().decode(parts[1]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] bufferBytes(ByteBuffer bb) {
        ByteBuffer dup = bb.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) return 100;
        return Math.min(limit, MAX_LIMIT);
    }

    public enum Type { page, feed_item }

    public record Batch(List<Map<String, Object>> records, String nextCursor, int count, String stream) {}
}
