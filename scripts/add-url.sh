#!/bin/bash

# Script to add a single URL to the crawler queue
# Usage: ./add-url.sh <URL> [crawler-host]

set -e

# Configuration
DEFAULT_HOST="http://localhost:8080"
CRAWLER_HOST="${2:-$DEFAULT_HOST}"

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
    echo "Usage: $0 <URL> [crawler-host]"
    echo
    echo "Add a single URL to the distributed crawler queue"
    echo
    echo "Arguments:"
    echo "  URL           The URL to add to the crawling queue"
    echo "  crawler-host  Base URL of the crawler API (default: $DEFAULT_HOST)"
    echo
    echo "Examples:"
    echo "  $0 https://example.com"
    echo "  $0 https://news.ycombinator.com http://localhost:8080"
    echo "  $0 https://github.com/trending"
    echo
    echo "Environment Variables:"
    echo "  CRAWLER_HOST  Override default crawler host"
}

# Validate arguments
if [ $# -eq 0 ] || [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    show_help
    exit 0
fi

URL="$1"

# Use environment variable if set
if [ -n "$CRAWLER_HOST" ]; then
    CRAWLER_HOST="$CRAWLER_HOST"
fi

# Validate URL format (basic check)
if [[ ! "$URL" =~ ^https?:// ]]; then
    log_error "Invalid URL format. URL must start with http:// or https://"
    echo "Provided: $URL"
    exit 1
fi

log_info "Adding URL to crawler queue..."
log_url "$URL"
log_info "Crawler API: $CRAWLER_HOST"

# Create JSON payload
JSON_PAYLOAD=$(cat <<EOF
{
  "url": "$URL"
}
EOF
)

# Make API request
log_info "Sending request to crawler API..."
RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "$JSON_PAYLOAD" \
    "$CRAWLER_HOST/api/crawler/url")

# Parse response
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

# Handle response
if [ "$HTTP_CODE" -eq 200 ]; then
    log_info "✅ URL added successfully!"
    
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
    log_error "❌ Failed to add URL (HTTP $HTTP_CODE)"
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