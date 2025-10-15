package com.webcrawler.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.webcrawler.config.KafkaProperties;
import com.webcrawler.model.CrawlRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaUrlQueue implements UrlQueue {
    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> consumer;
    private final ObjectMapper objectMapper;
    private final String topicName;
    private final String groupId;

    @Autowired
    public KafkaUrlQueue(KafkaProperties kafkaProperties) {
        this.groupId = kafkaProperties.groupId();
        this.topicName = kafkaProperties.topic();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.producer = createProducer(kafkaProperties.bootstrapServers());
        this.consumer = createConsumer(kafkaProperties.bootstrapServers());
        this.consumer.subscribe(Set.of(topicName));
    }

    @Override
    public CompletableFuture<Void> enqueue(CrawlRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            return CompletableFuture.supplyAsync(() -> 
                producer.send(new ProducerRecord<>(topicName, request.url(), json)))
                .thenAccept(result -> {});
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<List<CrawlRequest>> dequeue() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (consumer) {
                return getCrawlRequest();
            }
        });
    }

    private List<CrawlRequest> getCrawlRequest() {
        List<CrawlRequest> requests = new ArrayList<>();
        var records = consumer.poll(Duration.ofSeconds(1));
        if (records.isEmpty()) {
            return null;
        }
        var record = records.iterator().next();
        try {
            requests.add(objectMapper.readValue(record.value(), CrawlRequest.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize CrawlRequest", e);
        }
        return requests;
    }

    private KafkaProducer<String, String> createProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    private KafkaConsumer<String, String> createConsumer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        return new KafkaConsumer<>(props);
    }

    @Override
    public void close() {
        producer.close();
        synchronized (consumer) {
            consumer.close();
        }
    }
}
