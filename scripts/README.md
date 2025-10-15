# Crawler API Scripts

This directory contains convenient command-line scripts for interacting with the distributed web crawler API.

## Available Scripts

### 🕷️ Crawler Management

#### `add-url.sh`
Add a single URL to the crawling queue.

```bash
# Basic usage
./add-url.sh https://example.com

# With custom host
./add-url.sh https://github.com http://localhost:8080

# Help
./add-url.sh --help
```

#### `add-urls.sh`
Add multiple URLs to the crawling queue.

```bash
# Add multiple URLs directly
./add-urls.sh https://example.com https://httpbin.org/html

# Add from file
./add-urls.sh -f ../sample-urls.txt

# Add from file with custom host
./add-urls.sh -f urls.txt http://localhost:8080

# Help
./add-urls.sh --help
```

#### `crawler-status.sh`
Check the current status of the crawler.

```bash
# Get status
./crawler-status.sh

# From custom host
./crawler-status.sh http://localhost:8080

# Help
./crawler-status.sh --help
```

### 🗄️ Data Queries

#### `query-data.sh`
Query crawled data from the database.

```bash
# Get total page count
./query-data.sh count

# List pages (default: 50 pages, offset 0)
./query-data.sh pages

# List specific number of pages
./query-data.sh pages 20

# List pages with offset
./query-data.sh pages 10 50

# Search for pages containing term
./query-data.sh search "python"
./query-data.sh search "javascript" 25

# Get statistics
./query-data.sh stats

# Help
./query-data.sh --help
```

## Requirements

### Required
- `curl` - for making HTTP requests
- `bash` - shell environment

### Recommended
- `jq` - for JSON formatting and parsing (much better output)

### Install jq

```bash
# macOS
brew install jq

# Ubuntu/Debian
sudo apt install jq

# CentOS/RHEL
sudo yum install jq
```

## Configuration

### Environment Variables

Set these for global configuration:

```bash
# Default crawler host
export CRAWLER_HOST="http://localhost:8080"

# Scripts will use these automatically
./add-url.sh https://example.com
./crawler-status.sh
./query-data.sh count
```

### File Format for URLs

When using `add-urls.sh -f <file>`, the file format is:

```
# Comments start with #
https://example.com
https://httpbin.org/html
https://httpbin.org/json

# Empty lines are ignored
https://github.com/trending
```

## Examples

### Complete Workflow

```bash
# 1. Check crawler status
./crawler-status.sh

# 2. Add some URLs
./add-urls.sh -f ../sample-urls.txt

# 3. Monitor progress
watch -n 5 ./crawler-status.sh

# 4. Check results
./query-data.sh count
./query-data.sh pages 10

# 5. Search for content
./query-data.sh search "html"
```

### Batch Operations

```bash
# Add many URLs from different sources
./add-url.sh https://example.com
./add-urls.sh https://httpbin.org/html https://httpbin.org/json
./add-urls.sh -f websites.txt

# Query different data sets
./query-data.sh count
./query-data.sh pages 50 0      # First 50 pages
./query-data.sh pages 50 50     # Next 50 pages
./query-data.sh search "python" 100
```

### Error Handling

The scripts include comprehensive error handling:

- **Validation**: URL format checking
- **HTTP Errors**: Clear error messages for API failures
- **Network Issues**: Connection troubleshooting tips
- **File Errors**: Missing files, permission issues

```bash
# Example error output
$ ./add-url.sh invalid-url
[ERROR] Invalid URL format. URL must start with http:// or https://
Provided: invalid-url

$ ./add-url.sh https://example.com
[INFO] Adding URL to crawler queue...
[URL] https://example.com
[INFO] Crawler API: http://localhost:8080
[ERROR] ❌ Failed to add URL (HTTP 502)

Response: {"error": "Service temporarily unavailable"}
```

## Troubleshooting

### Common Issues

1. **Connection Refused**
   ```bash
   # Check if service is running
   curl -I http://localhost:8080/api/crawler/status
   
   # Check port forwarding
   ../check-dev-ports.sh
   
   # Start port forwarding
   ../start-dev-ports.sh
   ```

2. **Service Not Responding**
   ```bash
   # Start the crawler service
   mvn spring-boot:run
   ```

3. **Invalid JSON Response**
   ```bash
   # Check if jq is installed
   which jq
   
   # Install jq for better formatting
   brew install jq
   ```

### Debug Mode

Add verbose curl output:

```bash
# Modify any script to add -v flag to curl commands
curl -v -X GET http://localhost:8080/api/crawler/status
```

## Script Features

### ✨ User-Friendly Output
- Color-coded messages (info, warning, error)
- Progress indicators
- Formatted JSON output (with jq)
- Clear success/failure status

### 🛡️ Error Handling
- Input validation
- HTTP status code checking
- Network error detection
- Helpful troubleshooting tips

### 🔧 Flexible Configuration
- Environment variable support
- Command-line host override
- File input support
- Multiple URL formats

### 📊 Rich Information Display
- Formatted status output
- Pagination support
- Search result highlighting
- Statistics formatting

These scripts make it easy to interact with the crawler API without remembering complex curl commands or JSON formatting.