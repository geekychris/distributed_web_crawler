#!/bin/bash

# Script to add multiple URLs to the crawler queue
# Usage: ./add-urls.sh <url1> <url2> ... [crawler-host]
# Or:    ./add-urls.sh -f <file> [crawler-host]

set -e

# Configuration
DEFAULT_HOST="http://localhost:8080"
CRAWLER_HOST=""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_url() {
    echo -e "${BLUE}[URL]${NC} $1"
}

show_help() {
    echo "Usage: $0 <url1> <url2> ... [crawler-host]"
    echo "   or: $0 -f <file> [crawler-host]"
    echo
    echo "Add multiple URLs to the distributed crawler queue"
    echo
    echo "Arguments:"
    echo "  url1, url2... URLs to add to the crawling queue"
    echo "  -f <file>     Read URLs from a file (one URL per line)"
    echo "  crawler-host  Base URL of the crawler API (default: $DEFAULT_HOST)"
    echo
    echo "Examples:"
    echo "  $0 https://example.com https://github.com"
    echo "  $0 -f urls.txt"
    echo "  $0 -f urls.txt http://localhost:8080"
    echo
    echo "File format (urls.txt):"
    echo "  https://example.com"
    echo "  https://github.com/trending"
    echo "  https://news.ycombinator.com"
    echo
    echo "Environment Variables:"
    echo "  CRAWLER_HOST  Override default crawler host"
}

# Validate URL format
validate_url() {
    local url="$1"
    if [[ ! "$url" =~ ^https?:// ]]; then
        log_error "Invalid URL format: $url"
        return 1
    fi
    return 0
}

# Parse arguments
URLS=()
USE_FILE=false
FILE_PATH=""

while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            exit 0
            ;;
        -f|--file)
            USE_FILE=true
            FILE_PATH="$2"
            shift 2
            ;;
        *)
            # Check if it looks like a host URL (contains http and no path)
            if [[ "$1" =~ ^https?://[^/]+/?$ ]]; then
                CRAWLER_HOST="$1"
            else
                URLS+=("$1")
            fi
            shift
            ;;
    esac
done

# Set default crawler host
if [ -z "$CRAWLER_HOST" ]; then
    CRAWLER_HOST="${CRAWLER_HOST:-$DEFAULT_HOST}"
fi

# Read URLs from file if specified
if [ "$USE_FILE" = true ]; then
    if [ ! -f "$FILE_PATH" ]; then
        log_error "File not found: $FILE_PATH"
        exit 1
    fi
    
    log_info "Reading URLs from file: $FILE_PATH"
    
    while IFS= read -r line || [ -n "$line" ]; do
        # Skip empty lines and comments
        if [[ -n "$line" && ! "$line" =~ ^[[:space:]]*# ]]; then
            # Trim whitespace
            url=$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            if [ -n "$url" ]; then
                URLS+=("$url")
            fi
        fi
    done < "$FILE_PATH"
fi

# Validate we have URLs to process
if [ ${#URLS[@]} -eq 0 ]; then
    log_error "No URLs provided"
    show_help
    exit 1
fi

# Validate all URLs
log_info "Validating ${#URLS[@]} URLs..."
for url in "${URLS[@]}"; do
    if ! validate_url "$url"; then
        log_error "Stopping due to invalid URL"
        exit 1
    fi
done

log_info "✓ All URLs validated successfully"

# Display URLs to be added
echo
log_info "URLs to be added to crawler queue:"
for url in "${URLS[@]}"; do
    log_url "$url"
done

echo
log_info "Crawler API: $CRAWLER_HOST"

# Create JSON payload
JSON_PAYLOAD=$(printf '{"urls":[%s]}' "$(printf '"%s",' "${URLS[@]}" | sed 's/,$//')")

echo
log_info "Sending ${#URLS[@]} URLs to crawler API..."

# Make API request
RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "$JSON_PAYLOAD" \
    "$CRAWLER_HOST/api/crawler/urls")

# Parse response
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

# Handle response
if [ "$HTTP_CODE" -eq 200 ]; then
    log_info "✅ All URLs added successfully!"
    
    # Try to parse and display response details
    if command -v jq >/dev/null 2>&1; then
        echo
        echo "Response:"
        echo "$BODY" | jq '.'
    else
        echo
        echo "Response: $BODY"
        log_warn "Install 'jq' for better JSON formatting"
    fi
    
else
    log_error "❌ Failed to add URLs (HTTP $HTTP_CODE)"
    echo
    echo "Response: $BODY"
    exit 1
fi

echo
log_info "You can check crawler status with:"
echo "  curl $CRAWLER_HOST/api/crawler/status | jq '.'"
echo
log_info "Or use the status script:"
echo "  ./scripts/crawler-status.sh"