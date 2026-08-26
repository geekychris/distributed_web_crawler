package com.webcrawler.storage;

import com.webcrawler.config.S3Properties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.util.concurrent.CompletionException;

/**
 * Ensures the configured S3/MinIO bucket exists at startup. MinIO does not
 * auto-create buckets on first putObject — without this the first crawl would
 * fail with NoSuchBucket.
 */
@Component
public class BucketBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(BucketBootstrap.class);

    private final S3AsyncClient s3Client;
    private final String bucketName;

    @Autowired
    public BucketBootstrap(S3AsyncClient s3Client, S3Properties s3Properties) {
        this.s3Client = s3Client;
        this.bucketName = s3Properties.bucket();
    }

    @PostConstruct
    public void ensureBucketExists() {
        try {
            s3Client.headBucket(req -> req.bucket(bucketName)).join();
            logger.info("Bucket '{}' exists", bucketName);
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            if (cause instanceof NoSuchBucketException
                    || (cause.getMessage() != null && cause.getMessage().contains("404"))) {
                logger.info("Bucket '{}' missing — creating", bucketName);
                s3Client.createBucket(req -> req.bucket(bucketName)).join();
                logger.info("Bucket '{}' created", bucketName);
            } else {
                throw ce;
            }
        }
    }
}
