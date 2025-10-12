# Distributed Web Crawler

A scalable, distributed web crawler built with Java that supports multiple crawler processes and horizontal scaling.

## Features

- Distributed crawling using Kafka for URL queue management
- Scalable storage using Cassandra and S3/MinIO
- Content deduplication using SHA-256 hashing
- Configurable crawling rules and filters
- robots.txt support
- Concurrent crawling with configurable thread pools
- Domain filtering and pattern-based URL exclusion
- Comprehensive metadata storage

## Architecture Overview

The distributed web crawler is designed for horizontal scalability and reliability. Here's a detailed breakdown of its components:

### Core Components

1. **WebCrawler (Core Engine)**
   - Manages the crawling lifecycle
   - Implements politeness rules and delays
   - Handles concurrent crawling with thread pools
   - Processes robots.txt files
   - Computes content hashes for deduplication
   - Extracts and filters links from pages

2. **URL Queue System (Kafka)**
   - Distributes URLs across multiple crawler instances
   - Ensures each URL is processed exactly once
   - Provides persistent storage of pending URLs
   - Supports priority-based crawling
   - Topics:
     * `crawler-urls`: Main URL queue
     * `crawler-metrics`: Operational metrics

3. **Metadata Storage (Cassandra)**
   - Stores crawl metadata and state
   - Enables efficient URL deduplication
   - Tracks crawl history and timing
   - Schema:
     * `pages`: Stores page metadata and content references
     * `crawl_state`: Maintains crawler state and progress

4. **Content Storage (S3/MinIO)**
   - Stores raw page content
   - Provides scalable object storage
   - Enables content archival and retrieval
   - Organized by date and content hash

### Data Flow

1. **URL Discovery**
   - Seeds URLs added to Kafka queue
   - New URLs discovered during crawling
   - URLs filtered based on configuration rules

   After fetching content from a URL, the crawler extracts all the links from the page using JSoup. The extracted links are filtered according to the allowed domains and exclusion patterns specified in the configuration. Valid links are then converted into `CrawlRequest` objects and enqueued back into the Kafka URL topic for further processing. This allows continuous discovery and crawling of links as the process iterates over the web.

2. **Crawling Process**
   - URLs consumed from Kafka queue
   - robots.txt checked and respected
   - Content fetched and processed
   - Links extracted and filtered

   The URL processing workflow follows these steps:

   1. **URL Consumption**:
      - Crawler worker dequeues a URL from Kafka
      - URL is validated against depth limits and filters
      - robots.txt rules are checked

   2. **Content Fetching**:
      - Page is fetched using JSoup
      - Response headers and status code are captured
      - Content hash is computed for deduplication

   3. **Link Extraction**:
      - JSoup parses the HTML content
      - All `<a href>` tags are extracted
      - URLs are normalized to absolute form
      - Links are filtered based on:
        * Allowed domains
        * Exclusion patterns
        * Maximum depth
        * URL syntax validation

   4. **New URL Processing**:
      - Each valid link is converted to a CrawlRequest
      - CrawlRequest includes:
        * URL
        * Incremented depth
        * Parent URL reference
        * Discovery timestamp
        * Priority level
      - New CrawlRequests are published to Kafka
      - URLs are automatically distributed to available crawlers

   This process ensures:
   - Systematic exploration of web content
   - Respect for crawling policies
   - Even distribution of work across crawler instances
   - Efficient deduplication of content
   - Tracking of crawl paths and relationships

3. **Storage**
   - Content stored in S3/MinIO
   - Metadata stored in Cassandra
   - New URLs published to Kafka

### Scalability

- Each component can scale independently
- Multiple crawler instances can run in parallel
- Storage systems support horizontal scaling
- Kafka partitioning enables parallel processing

### Components

1. **URL Queue (Kafka)**
   - Maintains distributed queue of URLs to crawl
   - Supports multiple consumers (crawler instances)
   - Ensures each URL is processed once

2. **Storage (Cassandra + S3)**
   - Cassandra: Stores metadata, URL tracking, and crawl state
   - S3/MinIO: Stores raw page content
   - Enables efficient content deduplication and retrieval

3. **Crawler Workers**
   - Multiple concurrent crawling threads per instance
   - Configurable crawl delays and politeness rules
   - robots.txt compliance
   - Link extraction and filtering

## Building the Project

### Prerequisites

- Java 21 or later
- Maven 3.6 or later
- Docker and Docker Compose (for local development)
- Kubernetes cluster (for production deployment)

### Build Commands

```bash
# Build the project
mvn clean package

# Run tests
mvn test

# Build Docker image
docker build -t distributed-crawler:latest .
```

## Configuration

### Crawler Configuration

The crawler is configured using the `CrawlerConfig` class. Here are the key configuration options:

```java
CrawlerConfig config = CrawlerConfig.builder()
    .maxDepth(5)                           // Maximum crawl depth
    .crawlDelay(Duration.ofSeconds(1))     // Delay between requests to same domain
    .maxConcurrentRequests(10)             // Number of concurrent crawl threads
    .allowedDomains(Set.of(                // Allowed domains (regex patterns)
        Pattern.compile("example\\.com$")
    ))
    .excludePatterns(Set.of(               // Excluded URL patterns
        Pattern.compile("/private/.*")
    ))
    .seedUrls(Set.of(                      // Initial URLs to crawl
        "https://example.com/"
    ))
    .respectRobotsTxt(true)                // Whether to respect robots.txt
    .userAgent("DistributedCrawler/1.0")   // User agent string
    .build();
```

### Environment Variables

The following environment variables can be used to configure the services:

```properties
# Kafka Configuration
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KAFKA_GROUP_ID=crawler-group-1

# Cassandra Configuration
CASSANDRA_CONTACT_POINTS=localhost:9042
CASSANDRA_LOCAL_DATACENTER=datacenter1
CASSANDRA_KEYSPACE=crawler

# S3/MinIO Configuration
S3_ENDPOINT=http://localhost:9000
S3_BUCKET=crawler-bucket
AWS_ACCESS_KEY_ID=minioadmin
AWS_SECRET_ACCESS_KEY=minioadmin
```

## Running Locally with Docker Compose

1. Start the infrastructure services:
```bash
docker-compose up -d
```

2. Initialize the Cassandra schema:
```bash
docker-compose exec cassandra cqlsh -f /init/schema.cql
```

3. Run the crawler:
```bash
docker-compose up crawler
```

## Kubernetes Deployment

The crawler can be deployed to Kubernetes using the provided manifests in the `k8s` directory:

```bash
# Deploy infrastructure
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/storage-class.yaml
kubectl apply -f k8s/kafka/
kubectl apply -f k8s/cassandra/
kubectl apply -f k8s/minio/

# Deploy crawler
kubectl apply -f k8s/crawler/
```

## Monitoring and Management

### Kafka Topics

- `crawler-urls`: Contains URLs to be crawled
- `crawler-metrics`: Contains crawler metrics and statistics

### Cassandra Tables

- `crawler.pages`: Stores page metadata and S3 references
- `crawler.crawl_state`: Stores crawler state and progress

### Metrics

The crawler exposes the following metrics:

- Pages crawled
- Crawl rate
- Error rate
- Storage usage
- Queue depth

## Extending the Crawler

### Adding New Storage Backends

1. Implement the `StorageService` interface
2. Add configuration for the new backend
3. Update the storage factory in the main application

### Custom Filtering Rules

1. Extend the `CrawlerConfig` class with new rules
2. Implement the rules in the `shouldCrawl` method
3. Update the configuration builder

### Custom Content Processing

1. Modify the `crawlUrl` method in `WebCrawler`
2. Add new fields to `PageContent` if needed
3. Update the storage schema accordingly

## Troubleshooting

### Common Issues

1. **Kafka Connection Issues**
   - Verify Kafka broker addresses
   - Check network connectivity
   - Verify topic existence and permissions

2. **Cassandra Issues**
   - Verify contact points
   - Check keyspace existence
   - Verify schema compatibility

3. **S3/MinIO Issues**
   - Verify endpoint URL
   - Check credentials
   - Verify bucket existence and permissions

### Logging

The crawler uses SLF4J with Logback for logging. Configure logging levels in `logback.xml`:

```xml
<logger name="com.webcrawler" level="INFO"/>
```

## License

MIT License - See LICENSE file for details
