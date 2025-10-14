package com.webcrawler.config;

import com.datastax.oss.driver.api.core.CqlSession;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.InetSocketAddress;
import java.net.URI;

@Configuration
@EnableConfigurationProperties({
    CrawlerProperties.class,
    KafkaProperties.class,
    CassandraProperties.class,
    S3Properties.class
})
public class CrawlerConfiguration {

    @Bean
    public CqlSession cassandraSession(CassandraProperties cassandraProperties) {
        String[] hostPort = cassandraProperties.contactPoints().split(":");
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 9042;
        
        return CqlSession.builder()
            .addContactPoint(InetSocketAddress.createUnresolved(host, port))
            .withLocalDatacenter(cassandraProperties.localDatacenter())
            .withKeyspace(cassandraProperties.keyspace())
            .build();
    }

    @Bean
    public S3AsyncClient s3AsyncClient(S3Properties s3Properties) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
            s3Properties.accessKeyId(),
            s3Properties.secretAccessKey()
        );
        
        return S3AsyncClient.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .endpointOverride(URI.create(s3Properties.endpoint()))
            .forcePathStyle(true)
            .build();
    }
}