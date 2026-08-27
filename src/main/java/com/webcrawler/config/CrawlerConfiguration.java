package com.webcrawler.config;

import com.datastax.oss.driver.api.core.CqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties({
    CrawlerProperties.class,
    KafkaProperties.class,
    CassandraProperties.class,
    S3Properties.class
})
public class CrawlerConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(CrawlerConfiguration.class);

    /**
     * CqlSession bean. On first boot the keyspace may not exist, so we open an
     * unkeyed session, apply schema.cql, then hand out a session bound to the
     * configured keyspace. Uses a bounded retry loop so a slow-to-start Cassandra
     * (common in docker-compose / k8s cold boot) does not fail Spring startup.
     */
    @Bean
    public CqlSession cassandraSession(CassandraProperties cassandraProperties) {
        String[] hostPort = cassandraProperties.contactPoints().split(":");
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 9042;
        InetSocketAddress contact = new InetSocketAddress(host, port);

        withRetry("cassandra:" + host + ":" + port, () -> {
            try (CqlSession bootstrap = CqlSession.builder()
                    .addContactPoint(contact)
                    .withLocalDatacenter(cassandraProperties.localDatacenter())
                    .build()) {
                applySchema(bootstrap);
            }
            return null;
        });

        return CqlSession.builder()
                .addContactPoint(contact)
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

    private void applySchema(CqlSession session) {
        String schema = readClasspathResource("schema.cql");
        for (String raw : schema.split(";")) {
            String stmt = stripCommentsAndTrim(raw);
            if (stmt.isEmpty() || stmt.regionMatches(true, 0, "USE ", 0, 4)) continue;
            try {
                session.execute(stmt);
            } catch (Exception e) {
                // Idempotent-migration tolerance: some Cassandra versions
                // reject "ALTER TABLE ADD" of an already-existing column with
                // the exact "IF NOT EXISTS" grammar, and CREATE INDEX / TABLE
                // already have IF NOT EXISTS. Log and continue if the error
                // reads like an already-exists condition.
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (msg.contains("already exists") || msg.contains("conflicts with")) {
                    logger.debug("Schema statement skipped ({}): {}", e.getMessage(),
                            stmt.length() > 80 ? stmt.substring(0, 80) + "..." : stmt);
                } else {
                    throw e;
                }
            }
        }
        logger.info("Cassandra schema applied");
    }

    /** Strip CQL-style `-- ...` line comments and surrounding whitespace. */
    private static String stripCommentsAndTrim(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (String line : raw.split("\\r?\\n")) {
            int commentAt = line.indexOf("--");
            if (commentAt >= 0) line = line.substring(0, commentAt);
            if (!line.isBlank()) sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }

    private String readClasspathResource(String name) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " not on classpath");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + name, e);
        }
    }

    /** Bounded exponential backoff — total ~2 minutes. */
    static <T> T withRetry(String what, ThrowingSupplier<T> action) {
        long[] backoffMs = {1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 60_000};
        Throwable last = null;
        for (int attempt = 0; attempt <= backoffMs.length; attempt++) {
            try {
                return action.get();
            } catch (Throwable t) {
                last = t;
                if (attempt == backoffMs.length) break;
                long delay = backoffMs[attempt];
                logger.warn("Waiting for {}: {} — retrying in {}ms",
                        what, t.getClass().getSimpleName() + ": " + t.getMessage(), delay);
                try { Thread.sleep(delay); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for " + what, ie);
                }
            }
        }
        throw new IllegalStateException("Gave up waiting for " + what, last);
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
