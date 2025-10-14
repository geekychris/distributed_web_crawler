package com.webcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kafka")
public record KafkaProperties(
    @DefaultValue("localhost:9092") String bootstrapServers,
    @DefaultValue("crawler-group-1") String groupId,
    @DefaultValue("crawler-urls") String topic
) {
}