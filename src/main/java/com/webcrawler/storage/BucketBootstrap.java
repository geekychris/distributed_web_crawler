package com.webcrawler.storage;

import com.webcrawler.config.S3Properties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

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
        long[] backoffMs = {500, 1_000, 2_000, 4_000, 8_000, 15_000};
        for (int attempt = 0; attempt <= backoffMs.length; attempt++) {
            try {
                headOrCreate();
                return;
            } catch (RuntimeException e) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null
                        ? e.getCause() : e;
                if (isMissingBucket(cause)) {
                    // Structural miss — caught inside headOrCreate; a
                    // RuntimeException here means the createBucket itself
                    // threw. Retry.
                }
                if (attempt == backoffMs.length) {
                    throw e;
                }
                logger.warn("Bucket bootstrap failed ({}: {}) — retrying in {}ms",
                        cause.getClass().getSimpleName(), cause.getMessage(), backoffMs[attempt]);
                try { Thread.sleep(backoffMs[attempt]); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for MinIO", ie);
                }
            }
        }
    }

    private void headOrCreate() {
        try {
            s3Client.headBucket(req -> req.bucket(bucketName)).join();
            logger.info("Bucket '{}' exists", bucketName);
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            if (isMissingBucket(cause)) {
                logger.info("Bucket '{}' missing — creating", bucketName);
                s3Client.createBucket(req -> req.bucket(bucketName)).join();
                logger.info("Bucket '{}' created", bucketName);
            } else {
                throw ce;
            }
        }
    }

    private static boolean isMissingBucket(Throwable t) {
        if (t instanceof NoSuchBucketException) return true;
        // MinIO can throw a generic S3Exception with status 404 rather than
        // the more specific NoSuchBucketException, depending on version.
        if (t instanceof S3Exception s3e && s3e.statusCode() == 404) return true;
        return t.getMessage() != null && t.getMessage().contains("404");
    }
}
