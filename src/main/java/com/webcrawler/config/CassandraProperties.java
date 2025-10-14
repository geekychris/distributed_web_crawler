package com.webcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "cassandra")
public record CassandraProperties(
    @DefaultValue("localhost:9042") String contactPoints,
    @DefaultValue("datacenter1") String localDatacenter,
    @DefaultValue("crawler") String keyspace
) {
}