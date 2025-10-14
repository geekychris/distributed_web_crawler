package com.webcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "s3")
public record S3Properties(
    @DefaultValue("http://localhost:9000") String endpoint,
    @DefaultValue("crawler-bucket") String bucket,
    @DefaultValue("minioadmin") String accessKeyId,
    @DefaultValue("minioadmin") String secretAccessKey
) {
}