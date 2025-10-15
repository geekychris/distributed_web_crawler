# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Development Commands

### Build and Test
```bash
# Build the project
mvn clean package

# Run tests
mvn test

# Run tests for a specific class
mvn test -Dtest=AppTest

# Clean build
mvn clean
```

### Docker Development
```bash
# Build Docker image
docker build -t distributed-crawler:latest .

# Start infrastructure services
docker-compose up -d

# Initialize Cassandra schema (run after services are up)
docker-compose exec cassandra cqlsh -f /init/schema.cql

# Run crawler
docker-compose up crawler

# View logs
docker-compose logs -f crawler
```

### Kubernetes Deployment

**Recommended Method (New):**
```bash
# Deploy infrastructure with proper ordering and error handling
./deploy-infrastructure.sh

# Deploy crawler (optional)
kubectl apply -f k8s/crawler/

# Clean everything if needed
./deploy-infrastructure.sh clean
```

**Manual Method (Legacy):**
```bash
# Deploy infrastructure manually
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/storage-class.yaml
kubectl apply -f k8s/kafka/
kubectl apply -f k8s/cassandra/
kubectl apply -f k8s/minio/

# Deploy crawler (optional)
kubectl apply -f k8s/crawler/
```

### Development Workflow

**For local debugging/development (Recommended):**

1. Deploy infrastructure services:
   ```bash
   ./deploy-infrastructure.sh
   ```

2. Start port forwarding for development:
   ```bash
   ./start-dev-ports.sh
   ```

3. Run the crawler locally:
   ```bash
   mvn spring-boot:run
   # OR use your IDE debugger
   ```

4. Check port forwarding status:
   ```bash
   ./check-dev-ports.sh
   ```

5. Stop port forwarding when done:
   ```bash
   ./stop-dev-ports.sh
   ```

**Alternative (Manual Method):**

1. Deploy infrastructure manually:
   ```bash
   kubectl apply -f k8s/namespace.yaml
   kubectl apply -f k8s/storage-class.yaml
   kubectl apply -f k8s/kafka/
   kubectl apply -f k8s/cassandra/
   kubectl apply -f k8s/minio/
   ```

2. Follow steps 2-5 above for port forwarding and running the crawler

### Development Access
For local development, use port forwarding to access services:
- **Kafka**: `localhost:9092`
- **Cassandra**: `localhost:9042`
- **MinIO API**: `localhost:9000`
- **MinIO Console**: `localhost:9001`
- **Zookeeper**: `localhost:2181`

The services are also exposed via NodePort for direct access:
- **Kafka**: `localhost:30092` (NodePort)
- **Zookeeper**: `localhost:32181` (NodePort)
- **Cassandra**: `localhost:30042` (NodePort)
- **MinIO API**: `localhost:30000` (NodePort)
- **MinIO Console**: `localhost:30001` (NodePort)

## Architecture Overview

This is a distributed web crawler built with Java 21 that uses a microservices-style architecture with the following key components:

### Core Architecture Pattern
- **WebCrawler**: Main crawling engine that processes URLs from a distributed queue
- **URL Queue (Kafka)**: Distributed message queue for URL distribution across crawler instances
- **Dual Storage**: Cassandra for metadata/deduplication + S3/MinIO for content storage
- **Configuration-Driven**: All crawling behavior controlled via CrawlerConfig builder pattern

### Key Data Flow
1. URLs are consumed from Kafka topic (`crawler-urls`)
2. Content is fetched, deduplicated via SHA-256 hash
3. Links are extracted and filtered based on domain/pattern rules
4. Content stored in S3, metadata in Cassandra
5. Discovered URLs published back to Kafka queue

### Service Dependencies
- **Kafka + Zookeeper**: URL queue and coordination
- **Cassandra**: Metadata storage and deduplication tracking
- **MinIO/S3**: Content blob storage
- **JSoup**: HTML parsing and link extraction

## Key Implementation Patterns

### Configuration Pattern
The crawler uses a builder pattern for configuration in `CrawlerConfig.java`. When adding new configuration options:
- Add field to record
- Add to Builder class with default value
- Add builder method following existing pattern

### Storage Abstraction
All storage operations go through the `StorageService` interface. Current implementation is `HybridStorageService` which combines Cassandra + S3. To add new storage backends, implement this interface.

### Queue Abstraction  
URL management uses the `UrlQueue` interface with Kafka implementation in `KafkaUrlQueue`. The queue handles `CrawlRequest` objects that include URL, depth, parent URL, and priority.

### Concurrency Model
- Fixed thread pool (`config.maxConcurrentRequests()`) for parallel crawling
- Each worker runs `crawlLoop()` continuously
- Domain-based politeness delays tracked in `lastCrawled` map
- CompletableFuture-based async operations throughout

### Content Deduplication
- SHA-256 hashing of page content for deduplication
- Hash stored in Cassandra with content reference to S3
- Duplicate content detection happens before storage

## Environment Configuration

Key environment variables for runtime configuration:

```bash
# Kafka (Docker Compose)
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KAFKA_GROUP_ID=crawler-group-1

# Kafka (Kubernetes NodePort)
KAFKA_BOOTSTRAP_SERVERS=localhost:30092

# Cassandra (Docker Compose)
CASSANDRA_CONTACT_POINTS=localhost:9042
CASSANDRA_LOCAL_DATACENTER=datacenter1
CASSANDRA_KEYSPACE=crawler

# Cassandra (Kubernetes NodePort)
CASSANDRA_CONTACT_POINTS=localhost:30042

# S3/MinIO (Docker Compose)
S3_ENDPOINT=http://localhost:9000
S3_BUCKET=crawler-bucket
AWS_ACCESS_KEY_ID=minioadmin
AWS_SECRET_ACCESS_KEY=minioadmin

# S3/MinIO (Kubernetes NodePort)
S3_ENDPOINT=http://localhost:30000
S3_BUCKET=crawler-bucket
AWS_ACCESS_KEY_ID=minioadmin
AWS_SECRET_ACCESS_KEY=minioadmin
```

## Testing Strategy

The project uses JUnit Jupiter for testing. Current test coverage is minimal (only a placeholder `AppTest`). When adding tests:
- Use JUnit 5 (Jupiter) annotations 
- Mock external services (Kafka, Cassandra, S3) using Mockito
- Test individual components in isolation
- Consider integration tests for the full crawling pipeline

## Data Models

### CrawlRequest
Represents a URL to be crawled with context:
- `url`: Target URL
- `depth`: Current crawl depth from seed
- `parentUrl`: URL that discovered this one
- `discoveredAt`: Discovery timestamp
- `priority`: Queue priority

### PageContent  
Represents crawled page data:
- `url`, `contentHash`: Identification
- `content`: Raw HTML content
- `fetchTime`, `httpStatus`, `headers`: HTTP metadata
- `links`: Extracted links set
- `metadata`: Additional key-value data

## REST API Usage

The crawler provides a comprehensive REST API for management and data querying:

### API Endpoints
- **Base URL**: `http://localhost:8080/api`
- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **OpenAPI Docs**: `http://localhost:8080/api-docs`

### Quick API Examples

#### Start/Stop Crawler
```bash
# Start crawler
curl -X POST http://localhost:8080/api/crawler/start

# Stop crawler
curl -X POST http://localhost:8080/api/crawler/stop

# Check status
curl http://localhost:8080/api/crawler/status | jq '.'
```

#### Add URLs to Crawl
```bash
# Add single URL
curl -X POST http://localhost:8080/api/crawler/url \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com"}'

# Add multiple URLs
curl -X POST http://localhost:8080/api/crawler/urls \
  -H "Content-Type: application/json" \
  -d '{"urls": ["https://example.com", "https://httpbin.org/html"]}'
```

#### Query Crawled Data
```bash
# Get total page count
curl http://localhost:8080/api/data/pages/count | jq '.'

# List pages (paginated)
curl "http://localhost:8080/api/data/pages?limit=10&offset=0" | jq '.'

# Search pages
curl "http://localhost:8080/api/data/pages/search?query=python&limit=25" | jq '.'
```

### Command-Line Scripts

For convenience, use the provided scripts:

```bash
# Add URLs
./scripts/add-url.sh https://example.com
./scripts/add-urls.sh -f sample-urls.txt

# Check status
./scripts/crawler-status.sh

# Query data
./scripts/query-data.sh count
./scripts/query-data.sh pages 10
./scripts/query-data.sh search "python"
```

📚 **See `API_EXAMPLES.md` for comprehensive API documentation with examples.**

## Database Schema

Cassandra keyspace `crawler` with:
- `pages`: Main content metadata (URL as primary key)
- `crawl_state`: Domain-level crawl state and robots.txt cache
- Index on `content_hash` for deduplication lookups
