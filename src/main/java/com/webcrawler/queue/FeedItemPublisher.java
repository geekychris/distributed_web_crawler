package com.webcrawler.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.webcrawler.config.KafkaProperties;
import com.webcrawler.model.Feed;
import com.webcrawler.model.FeedItem;
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
 * Publishes CloudEvents-shaped records to {@code crawler.feed_items.v1}
 * whenever a NEW feed item is persisted. Envelope is deliberately separate
 * from {@code crawler.pages.v1} so downstream consumers can subscribe to
 * either or both.
 */
@Component
public class FeedItemPublisher {
    private static final Logger logger = LoggerFactory.getLogger(FeedItemPublisher.class);
    private static final String EVENT_TYPE = "com.webcrawler.feed_item.discovered.v1";
    private static final String SPEC_VERSION = "1.0";
    private static final String SOURCE = "com.webcrawler/feeds";

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;
    private final String topic;

    @Autowired
    public FeedItemPublisher(KafkaProperties kafkaProperties) {
        this.topic = kafkaProperties.feedEventsTopic();
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

    public CompletableFuture<Void> publish(Feed feed, FeedItem item, Instant pollAt) {
        try {
            Map<String, Object> feedData = new LinkedHashMap<>();
            feedData.put("feed_id", feed.feedId());
            feedData.put("url", feed.url());
            feedData.put("title", feed.title());
            feedData.put("pack", feed.pack());

            Map<String, Object> itemData = new LinkedHashMap<>();
            itemData.put("item_id", item.itemId());
            itemData.put("url", item.url());
            itemData.put("title", item.title());
            itemData.put("summary", item.summary());
            itemData.put("content_snippet", item.contentSnippet());
            itemData.put("author", item.author());
            itemData.put("categories", item.categories());
            itemData.put("enclosures", item.enclosures());
            itemData.put("published_at", item.publishedAt());
            itemData.put("updated_at", item.updatedAt());
            itemData.put("first_seen", item.firstSeen());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("feed", feedData);
            data.put("item", itemData);
            data.put("poll_at", pollAt);
            if (item.followedPageUrl() != null) {
                Map<String, Object> followed = new LinkedHashMap<>();
                followed.put("url", item.followedPageUrl());
                data.put("followed_page", followed);
            }

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("specversion", SPEC_VERSION);
            envelope.put("type", EVENT_TYPE);
            envelope.put("source", SOURCE);
            envelope.put("id", UUID.randomUUID().toString());
            envelope.put("time", Instant.now());
            envelope.put("datacontenttype", "application/json");
            envelope.put("subject", item.url() != null ? item.url() : item.itemId());
            envelope.put("data", data);

            String json = objectMapper.writeValueAsString(envelope);
            CompletableFuture<Void> f = new CompletableFuture<>();
            producer.send(new ProducerRecord<>(topic, item.itemId(), json), (md, ex) -> {
                if (ex != null) f.completeExceptionally(ex); else f.complete(null);
            });
            return f;
        } catch (Exception e) {
            logger.warn("Failed to publish feed item event for {}: {}", item.itemId(), e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    @PreDestroy
    public void close() {
        try { producer.close(Duration.ofSeconds(10)); } catch (Exception e) { logger.warn("Publisher close failed", e); }
    }
}
