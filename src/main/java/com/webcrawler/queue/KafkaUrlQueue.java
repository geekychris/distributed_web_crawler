package com.webcrawler.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.webcrawler.config.KafkaProperties;
import com.webcrawler.model.CrawlRequest;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaUrlQueue implements UrlQueue {
    private static final Logger logger = LoggerFactory.getLogger(KafkaUrlQueue.class);

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;
    private final String topicName;
    private final String groupId;
    private final String bootstrapServers;
    private final List<KafkaBatchConsumer> openConsumers = new ArrayList<>();

    @Autowired
    public KafkaUrlQueue(KafkaProperties kafkaProperties) {
        this.groupId = kafkaProperties.groupId();
        this.topicName = kafkaProperties.topic();
        this.bootstrapServers = kafkaProperties.bootstrapServers();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.producer = createProducer(bootstrapServers);
    }

    @Override
    public CompletableFuture<Void> enqueue(CrawlRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            CompletableFuture<Void> f = new CompletableFuture<>();
            producer.send(new ProducerRecord<>(topicName, request.url(), json), (md, ex) -> {
                if (ex != null) f.completeExceptionally(ex); else f.complete(null);
            });
            return f;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public synchronized BatchConsumer openBatchConsumer() {
        KafkaConsumer<String, String> consumer = createConsumer(bootstrapServers);
        consumer.subscribe(Set.of(topicName));
        KafkaBatchConsumer bc = new KafkaBatchConsumer(consumer, objectMapper, this::forget);
        openConsumers.add(bc);
        return bc;
    }

    private synchronized void forget(KafkaBatchConsumer bc) {
        openConsumers.remove(bc);
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        for (KafkaBatchConsumer bc : new ArrayList<>(openConsumers)) {
            try { bc.close(); } catch (Exception e) { logger.warn("Consumer close failed", e); }
        }
        openConsumers.clear();
        try { producer.close(Duration.ofSeconds(10)); } catch (Exception e) { logger.warn("Producer close failed", e); }
    }

    private KafkaProducer<String, String> createProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaProducer<>(props);
    }

    private KafkaConsumer<String, String> createConsumer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
        return new KafkaConsumer<>(props);
    }

    /**
     * A single-threaded batch consumer. Owned by exactly one worker thread —
     * poll() and commit() must be called from that same thread because
     * KafkaConsumer tracks the caller's thread id and throws
     * ConcurrentModificationException on cross-thread access.
     */
    private static final class KafkaBatchConsumer implements BatchConsumer {
        private final KafkaConsumer<String, String> consumer;
        private final ObjectMapper objectMapper;
        private final java.util.function.Consumer<KafkaBatchConsumer> onClose;

        KafkaBatchConsumer(KafkaConsumer<String, String> consumer,
                           ObjectMapper objectMapper,
                           java.util.function.Consumer<KafkaBatchConsumer> onClose) {
            this.consumer = consumer;
            this.objectMapper = objectMapper;
            this.onClose = onClose;
        }

        @Override
        public Batch poll(Duration timeout) {
            ConsumerRecords<String, String> records = consumer.poll(timeout);
            if (records.isEmpty()) {
                return new Batch(List.of(), () -> {});
            }
            List<CrawlRequest> requests = new ArrayList<>(records.count());
            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            for (ConsumerRecord<String, String> record : records) {
                try {
                    requests.add(objectMapper.readValue(record.value(), CrawlRequest.class));
                } catch (Exception e) {
                    logger.warn("Skipping unparseable CrawlRequest at {}:{} offset {}: {}",
                            record.topic(), record.partition(), record.offset(), e.getMessage());
                }
                TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                offsets.merge(tp,
                        new OffsetAndMetadata(record.offset() + 1),
                        (a, b) -> a.offset() >= b.offset() ? a : b);
            }
            return new Batch(requests, () -> consumer.commitSync(offsets));
        }

        @Override
        public void close() {
            try {
                consumer.close(Duration.ofSeconds(10));
            } finally {
                onClose.accept(this);
            }
        }
    }
}
