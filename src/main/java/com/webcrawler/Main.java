package com.webcrawler;

import com.datastax.oss.driver.api.core.CqlSession;
import com.webcrawler.config.CrawlerConfig;
import com.webcrawler.core.WebCrawler;
import com.webcrawler.queue.KafkaUrlQueue;
import com.webcrawler.storage.HybridStorageService;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

public class Main {
    public static void main(String[] args) {
        // Configure storage services
        CqlSession cassandraSession = CqlSession.builder()
            .addContactPoint(InetSocketAddress.createUnresolved("localhost", 9042))
            .withLocalDatacenter("datacenter1")
            .withKeyspace("crawler")
            .build();

        S3AsyncClient s3Client = S3AsyncClient.builder().build();
        
        // Create storage service
        HybridStorageService storageService = new HybridStorageService(
            cassandraSession,
            s3Client,
            "crawler-bucket"
        );

        // Create URL queue
        KafkaUrlQueue urlQueue = new KafkaUrlQueue(
            "localhost:9092",
            "crawler-group-1"
        );

        // Configure crawler
        CrawlerConfig config = CrawlerConfig.builder()
            .maxDepth(5)
            .crawlDelay(Duration.ofSeconds(1))
            .maxConcurrentRequests(10)
            .allowedDomains(Set.of(
                Pattern.compile("example\\.com$"),
                Pattern.compile("blog\\.example\\.com$")
            ))
            .excludePatterns(Set.of(
                Pattern.compile("/private/.*"),
                Pattern.compile("/admin/.*")
            ))
            .seedUrls(Set.of(
                "https://example.com/",
                "https://blog.example.com/"
            ))
            .respectRobotsTxt(true)
            .userAgent("DistributedCrawler/1.0")
            .build();

        // Create and start crawler
        try (WebCrawler crawler = new WebCrawler(config, urlQueue, storageService)) {
            crawler.start();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            cassandraSession.close();
            s3Client.close();
        }
    }
}
