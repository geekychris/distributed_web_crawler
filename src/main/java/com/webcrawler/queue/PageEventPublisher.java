package com.webcrawler.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.webcrawler.config.KafkaProperties;
import com.webcrawler.config.S3Properties;
import com.webcrawler.model.PageContent;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes a CloudEvents-shaped record to {@code crawler.pages.v1} every time
 * a page is fully stored (S3 + Cassandra). Downstream consumers (indexers,
 * summarisers, webhook relays) can subscribe without touching the crawler.
 *
 * See CloudEvents 1.0 spec — https://github.com/cloudevents/spec.
 */
@Component
public class PageEventPublisher {
    private static final Logger logger = LoggerFactory.getLogger(PageEventPublisher.class);
    private static final String EVENT_TYPE = "com.webcrawler.page.crawled.v1";
    private static final String SPEC_VERSION = "1.0";
    private static final String SOURCE = "com.webcrawler/crawler";

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String s3Endpoint;
    private final String s3Bucket;

    @Autowired
    public PageEventPublisher(KafkaProperties kafkaProperties, S3Properties s3Properties) {
        this.topic = kafkaProperties.pageEventsTopic();
        this.s3Endpoint = s3Properties.endpoint();
        this.s3Bucket = s3Properties.bucket();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        this.producer = new KafkaProducer<>(props);
    }

    public CompletableFuture<Void> publish(PageContent page, String s3Key) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", page.url());
            data.put("content_hash", page.contentHash());
            data.put("s3_key", s3Key);
            data.put("s3_bucket", s3Bucket);
            data.put("s3_endpoint", s3Endpoint);
            data.put("http_status", page.httpStatus());
            data.put("content_type",
                    com.webcrawler.storage.HybridStorageService.lookupHeaderIgnoreCase(
                            page.headers(), "Content-Type", null));
            data.put("content_length", page.content() == null ? 0 : page.content().length());
            data.put("fetched_at", page.fetchTime());
            data.put("discovered_links_count", page.links() == null ? 0 : page.links().size());
            data.put("depth", page.metadata() == null ? null : page.metadata().get("depth"));
            data.put("parent_url", page.metadata() == null ? null : page.metadata().get("parent_url"));
            data.put("job_id", page.jobId());
            data.put("source_feed_item_id",
                    page.metadata() == null ? null : page.metadata().get("source_feed_item_id"));

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("specversion", SPEC_VERSION);
            envelope.put("type", EVENT_TYPE);
            envelope.put("source", SOURCE);
            envelope.put("id", UUID.randomUUID().toString());
            envelope.put("time", Instant.now());
            envelope.put("datacontenttype", "application/json");
            envelope.put("subject", page.url());
            envelope.put("data", data);

            String json = objectMapper.writeValueAsString(envelope);
            CompletableFuture<Void> f = new CompletableFuture<>();
            producer.send(new ProducerRecord<>(topic, page.url(), json), (md, ex) -> {
                if (ex != null) f.completeExceptionally(ex); else f.complete(null);
            });
            return f;
        } catch (Exception e) {
            logger.warn("Failed to publish page event for {}: {}", page.url(), e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    @PreDestroy
    public void close() {
        try { producer.close(Duration.ofSeconds(10)); } catch (Exception e) { logger.warn("Publisher close failed", e); }
    }
}
