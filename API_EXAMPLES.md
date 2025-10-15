# Distributed Web Crawler API Examples

This document provides comprehensive examples for interacting with the distributed web crawler API using curl commands and convenient scripts.

## 🚀 Quick Start

### Prerequisites
- Crawler service running on `http://localhost:8080`
- `curl` installed for API calls
- `jq` installed for JSON formatting (optional but recommended)

### Install jq (recommended)
```bash
# macOS
brew install jq

# Ubuntu/Debian
sudo apt install jq

# CentOS/RHEL
sudo yum install jq
```

## 📡 API Endpoints

### Base URL
- **Local Development**: `http://localhost:8080`
- **API Base Path**: `/api`

### Authentication
Currently, no authentication is required for API endpoints.

## 🕷️ Crawler Management APIs

### 1. Start Crawler

**Endpoint**: `POST /api/crawler/start`

**Description**: Starts the distributed web crawler service.

#### curl Example:
```bash
curl -X POST http://localhost:8080/api/crawler/start \
  -H "Content-Type: application/json" \
  -H "Accept: application/json"
```

#### Response:
```json
{
  "status": "success",
  "message": "Crawler started successfully"
}
```

### 2. Stop Crawler

**Endpoint**: `POST /api/crawler/stop`

**Description**: Stops the distributed web crawler service.

#### curl Example:
```bash
curl -X POST http://localhost:8080/api/crawler/stop \
  -H "Content-Type: application/json" \
  -H "Accept: application/json"
```

#### Response:
```json
{
  "status": "success",
  "message": "Crawler stopped successfully"
}
```

### 3. Get Crawler Status

**Endpoint**: `GET /api/crawler/status`

**Description**: Returns the current status and configuration of the web crawler.

#### curl Example:
```bash
curl -X GET http://localhost:8080/api/crawler/status \
  -H "Accept: application/json"
```

#### Response:
```json
{
  "isRunning": true,
  "uptime": "PT2H30M15S",
  "maxDepth": 5,
  "maxConcurrentRequests": 10,
  "crawlDelay": "PT1S",
  "respectRobotsTxt": true,
  "userAgent": "DistributedCrawler/1.0",
  "configuredSeedUrls": 3,
  "seedUrls": [
    "https://example.com",
    "https://httpbin.org/html"
  ]
}
```

### 4. Add Single URL

**Endpoint**: `POST /api/crawler/url`

**Description**: Adds a single URL to the crawling queue.

#### curl Example:
```bash
curl -X POST http://localhost:8080/api/crawler/url \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{
    "url": "https://example.com"
  }'
```

#### Response:
```json
{
  "status": "success",
  "message": "URL added to crawling queue",
  "url": "https://example.com"
}
```

### 5. Add Multiple URLs

**Endpoint**: `POST /api/crawler/urls`

**Description**: Adds multiple URLs to the crawling queue in a single request.

#### curl Example:
```bash
curl -X POST http://localhost:8080/api/crawler/urls \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{
    "urls": [
      "https://example.com",
      "https://httpbin.org/html",
      "https://httpbin.org/json"
    ]
  }'
```

#### Response:
```json
{
  "status": "success",
  "message": "Added 3 URLs to crawling queue",
  "urls": [
    "https://example.com",
    "https://httpbin.org/html",
    "https://httpbin.org/json"
  ]
}
```

## 🗄️ Data Query APIs

### 1. Get Total Page Count

**Endpoint**: `GET /api/data/pages/count`

**Description**: Returns the total number of pages crawled and stored.

#### curl Example:
```bash
curl -X GET http://localhost:8080/api/data/pages/count \
  -H "Accept: application/json"
```

#### Response:
```json
{
  "status": "success",
  "totalPages": 1250
}
```

### 2. Get All Pages (Paginated)

**Endpoint**: `GET /api/data/pages?limit={limit}&offset={offset}`

**Description**: Retrieves a paginated list of all crawled pages.

#### Parameters:
- `limit` (optional): Number of pages to return (default: 50)
- `offset` (optional): Number of pages to skip (default: 0)

#### curl Example:
```bash
# Get first 50 pages
curl -X GET "http://localhost:8080/api/data/pages?limit=50&offset=0" \
  -H "Accept: application/json"

# Get next 20 pages starting from offset 100
curl -X GET "http://localhost:8080/api/data/pages?limit=20&offset=100" \
  -H "Accept: application/json"
```

#### Response:
```json
{
  "status": "success",
  "pages": [
    {
      "url": "https://example.com",
      "contentHash": "abc123...",
      "fetchTime": "2024-01-15T10:30:00Z",
      "httpStatus": 200
    },
    {
      "url": "https://httpbin.org/html",
      "contentHash": "def456...",
      "fetchTime": "2024-01-15T10:31:00Z",
      "httpStatus": 200
    }
  ],
  "count": 2,
  "limit": 50,
  "offset": 0
}
```

### 3. Search Pages

**Endpoint**: `GET /api/data/pages/search?query={term}&limit={limit}`

**Description**: Searches for pages containing the specified term.

#### Parameters:
- `query` (required): Search term
- `limit` (optional): Maximum number of results (default: 50)

#### curl Example:
```bash
# Search for pages containing "python"
curl -X GET "http://localhost:8080/api/data/pages/search?query=python&limit=25" \
  -H "Accept: application/json"

# URL-encoded search for "web development"
curl -X GET "http://localhost:8080/api/data/pages/search?query=web%20development" \
  -H "Accept: application/json"
```

#### Response:
```json
{
  "status": "success",
  "query": "python",
  "pages": [
    {
      "url": "https://docs.python.org",
      "contentHash": "xyz789...",
      "fetchTime": "2024-01-15T10:32:00Z",
      "httpStatus": 200
    }
  ],
  "count": 1,
  "limit": 25
}
```

### 4. Get Data Statistics

**Endpoint**: `GET /api/data/stats`

**Description**: Returns comprehensive statistics about the crawled data.

#### curl Example:
```bash
curl -X GET http://localhost:8080/api/data/stats \
  -H "Accept: application/json"
```

#### Response:
```json
{
  "status": "success",
  "statistics": {
    "totalPages": 1250,
    "lastUpdated": 1705320000000
  }
}
```

## 🛠️ Command-Line Scripts

For convenience, use the provided scripts instead of raw curl commands:

### 1. Add Single URL

```bash
# Add a single URL
./scripts/add-url.sh https://example.com

# Add URL with custom API host
./scripts/add-url.sh https://github.com http://localhost:8080
```

### 2. Add Multiple URLs

```bash
# Add multiple URLs directly
./scripts/add-urls.sh https://example.com https://httpbin.org/html

# Add URLs from a file
./scripts/add-urls.sh -f sample-urls.txt

# Add URLs from file with custom host
./scripts/add-urls.sh -f sample-urls.txt http://localhost:8080
```

### 3. Check Crawler Status

```bash
# Get crawler status
./scripts/crawler-status.sh

# Get status from custom host
./scripts/crawler-status.sh http://localhost:8080
```

### 4. Query Data

```bash
# Get total page count
./scripts/query-data.sh count

# List first 50 pages
./scripts/query-data.sh pages

# List 20 pages with offset
./scripts/query-data.sh pages 20 100

# Search for pages containing "python"
./scripts/query-data.sh search "python"

# Search with custom limit
./scripts/query-data.sh search "javascript" 25

# Get data statistics
./scripts/query-data.sh stats
```

## 📝 Sample Workflow

Here's a complete workflow example:

```bash
# 1. Check if crawler is running
./scripts/crawler-status.sh

# 2. Start crawler if not running
curl -X POST http://localhost:8080/api/crawler/start

# 3. Add some URLs to crawl
./scripts/add-urls.sh -f sample-urls.txt

# 4. Wait for crawling to complete (monitor status)
./scripts/crawler-status.sh

# 5. Check how many pages were crawled
./scripts/query-data.sh count

# 6. List some crawled pages
./scripts/query-data.sh pages 10

# 7. Search for specific content
./scripts/query-data.sh search "html"

# 8. Stop the crawler when done
curl -X POST http://localhost:8080/api/crawler/stop
```

## 🔧 Advanced Usage

### Using Environment Variables

```bash
# Set crawler host globally
export CRAWLER_HOST="http://localhost:8080"

# Scripts will use this automatically
./scripts/add-url.sh https://example.com
./scripts/crawler-status.sh
./scripts/query-data.sh count
```

### Batch Processing with JSON

```bash
# Create a JSON file with URLs
cat > urls.json << 'EOF'
{
  "urls": [
    "https://httpbin.org/html",
    "https://httpbin.org/json",
    "https://httpbin.org/xml"
  ]
}
EOF

# Add URLs using the JSON file
curl -X POST http://localhost:8080/api/crawler/urls \
  -H "Content-Type: application/json" \
  -d @urls.json
```

### Monitoring and Automation

```bash
#!/bin/bash
# Simple monitoring script

while true; do
  echo "=== $(date) ==="
  ./scripts/crawler-status.sh
  ./scripts/query-data.sh count
  echo
  sleep 30
done
```

## 🚨 Error Handling

### Common HTTP Status Codes

- **200**: Success
- **400**: Bad Request (invalid parameters)
- **404**: Endpoint not found
- **500**: Internal Server Error

### Example Error Response

```json
{
  "status": "error",
  "message": "Failed to add URL: Invalid URL format"
}
```

### Troubleshooting

1. **Service Not Running**:
   ```bash
   # Check if service is up
   curl -I http://localhost:8080/api/crawler/status
   
   # Start the service
   mvn spring-boot:run
   ```

2. **Connection Refused**:
   ```bash
   # Check port forwarding
   ./check-dev-ports.sh
   
   # Restart port forwarding
   ./start-dev-ports.sh
   ```

3. **Invalid JSON**:
   ```bash
   # Validate JSON before sending
   echo '{"url": "https://example.com"}' | jq '.'
   ```

## 📚 Additional Resources

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/api-docs
- **Health Check**: http://localhost:8080/actuator/health

This API provides a complete interface for managing the distributed web crawler and querying crawled data. Use the provided scripts for convenience or integrate the API directly into your applications.