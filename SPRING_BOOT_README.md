# Distributed Crawler - Spring Boot Version

## Overview
The distributed web crawler has been successfully converted to a Spring Boot application, providing better configuration management, REST API endpoints, health monitoring, and production-ready features.

## What Changed

### 1. Spring Boot Integration
- **Main Application**: `DistributedCrawlerApplication.java` with `@SpringBootApplication`
- **Dependencies**: Updated `pom.xml` with Spring Boot parent and starters
- **Configuration**: Externalized configuration via `application.yml`

### 2. Configuration Management
- **Properties**: `CrawlerProperties.java` with `@ConfigurationProperties`
- **Infrastructure Config**: Separate properties for Kafka, Cassandra, and S3
- **Auto-configuration**: Spring beans automatically configured via dependency injection

### 3. Service Architecture
- **WebCrawler**: Now a `@Service` with Spring lifecycle management
- **Storage Service**: `@Component` with dependency injection
- **URL Queue**: `@Component` with property-based configuration

### 4. REST API Endpoints
**Crawler Management** (`/api/crawler`):
- `POST /start` - Start the crawler
- `POST /stop` - Stop the crawler  
- `GET /status` - Get crawler status and configuration
- `GET /config` - Get full crawler configuration

**System Information** (`/api/system`):
- `GET /info` - Get system and JVM information
- `GET /health-simple` - Simple health check

### 5. Interactive API Documentation
- **Swagger UI**: Complete interactive API documentation at `/swagger-ui.html`
- **OpenAPI 3.0**: Machine-readable API specification
- **Try It Out**: Test endpoints directly from the browser
- **Request/Response Examples**: See sample payloads and schemas

### 6. Monitoring & Health
- **Spring Boot Actuator**: Health checks, metrics, and monitoring endpoints
- **Endpoints**: `/actuator/health`, `/actuator/info`, `/actuator/metrics`

## Running the Application

### 1. Start Infrastructure Services
```bash
# Start services
docker-compose up -d

# Initialize Cassandra schema
docker-compose exec cassandra cqlsh -f /init/schema.cql
```

### 2. Run the Spring Boot Application
```bash
# Run directly with Maven
mvn spring-boot:run

# Or build and run the JAR
mvn clean package
java -jar target/distributed-crawler-1.0-SNAPSHOT.jar
```

### 3. Access the Application
- **Swagger UI**: http://localhost:8080/swagger-ui.html 🎯 **Interactive API Documentation**
- **Web API**: http://localhost:8080/api/crawler
- **Health Check**: http://localhost:8080/actuator/health
- **Metrics**: http://localhost:8080/actuator/metrics
- **OpenAPI Spec**: http://localhost:8080/api-docs

## Configuration

### Environment-Specific Configuration
Create `application-{profile}.yml` files for different environments:

```yaml
# application-docker.yml
kafka:
  bootstrap-servers: localhost:9092
cassandra:
  contact-points: localhost:9042
s3:
  endpoint: http://localhost:9000

# application-k8s.yml  
kafka:
  bootstrap-servers: localhost:30092
cassandra:
  contact-points: localhost:30042
s3:
  endpoint: http://localhost:30000
```

Run with specific profile:
```bash
java -jar target/distributed-crawler-1.0-SNAPSHOT.jar --spring.profiles.active=docker
```

### Key Configuration Properties
```yaml
crawler:
  max-depth: 5
  crawl-delay: PT1S
  max-concurrent-requests: 10
  seed-urls:
    - "https://example.com/"
  respect-robots-txt: true
  user-agent: "DistributedCrawler/1.0"
```

## API Usage Examples

### Start Crawling
```bash
curl -X POST http://localhost:8080/api/crawler/start
```

### Check Status
```bash
curl http://localhost:8080/api/crawler/status
```

### Stop Crawling
```bash
curl -X POST http://localhost:8080/api/crawler/stop
```

## Benefits of Spring Boot Version

1. **Production Ready**: Built-in monitoring, health checks, and metrics
2. **Interactive API Docs**: Swagger UI for API exploration and testing
3. **Configuration**: Externalized, environment-specific configuration
4. **API Management**: RESTful endpoints for crawler control
5. **Dependency Injection**: Clean separation of concerns and easier testing
6. **Auto-configuration**: Reduced boilerplate code
7. **Actuator**: Comprehensive application monitoring out of the box

## Migration from Legacy Version

The core crawling logic remains unchanged. Key differences:
- Configuration moved from code to `application.yml`
- Manual resource management replaced with Spring lifecycle
- REST API added for remote control
- Health monitoring enabled by default

All original Docker and Kubernetes deployments still work with the new Spring Boot JAR.