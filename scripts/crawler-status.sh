#!/bin/bash

# Script to check crawler status
# Usage: ./crawler-status.sh [crawler-host]

set -e

# Configuration
DEFAULT_HOST="http://localhost:8080"
CRAWLER_HOST="${1:-$DEFAULT_HOST}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_status() {
    echo -e "${BLUE}[STATUS]${NC} $1"
}

show_help() {
    echo "Usage: $0 [crawler-host]"
    echo
    echo "Check the status of the distributed web crawler"
    echo
    echo "Arguments:"
    echo "  crawler-host  Base URL of the crawler API (default: $DEFAULT_HOST)"
    echo
    echo "Examples:"
    echo "  $0"
    echo "  $0 http://localhost:8080"
    echo
    echo "Environment Variables:"
    echo "  CRAWLER_HOST  Override default crawler host"
}

# Parse arguments
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    show_help
    exit 0
fi

# Use environment variable if set
if [ -n "$CRAWLER_HOST_ENV" ]; then
    CRAWLER_HOST="$CRAWLER_HOST_ENV"
fi

log_info "Checking crawler status..."
log_info "Crawler API: $CRAWLER_HOST"

# Make API request
RESPONSE=$(curl -s -w "\n%{http_code}" \
    -H "Accept: application/json" \
    "$CRAWLER_HOST/api/crawler/status")

# Parse response
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

# Handle response
if [ "$HTTP_CODE" -eq 200 ]; then
    log_info "✅ Crawler status retrieved successfully!"
    echo
    
    # Try to parse and display formatted status
    if command -v jq >/dev/null 2>&1; then
        # Parse JSON fields
        IS_RUNNING=$(echo "$BODY" | jq -r '.isRunning // false')
        UPTIME=$(echo "$BODY" | jq -r '.uptime // "N/A"')
        MAX_DEPTH=$(echo "$BODY" | jq -r '.maxDepth // "N/A"')
        MAX_CONCURRENT=$(echo "$BODY" | jq -r '.maxConcurrentRequests // "N/A"')
        CRAWL_DELAY=$(echo "$BODY" | jq -r '.crawlDelay // "N/A"')
        ROBOTS_TXT=$(echo "$BODY" | jq -r '.respectRobotsTxt // false')
        USER_AGENT=$(echo "$BODY" | jq -r '.userAgent // "N/A"')
        SEED_COUNT=$(echo "$BODY" | jq -r '.configuredSeedUrls // 0')
        
        # Display formatted status
        echo -e "${CYAN}=== Crawler Status ===${NC}"
        
        # Running status with color
        if [ "$IS_RUNNING" = "true" ]; then
            echo -e "Status: ${GREEN}●${NC} Running"
        else
            echo -e "Status: ${RED}●${NC} Stopped"
        fi
        
        echo -e "Uptime: ${UPTIME}"
        echo
        echo -e "${CYAN}=== Configuration ===${NC}"
        echo -e "Max Depth: ${MAX_DEPTH}"
        echo -e "Max Concurrent Requests: ${MAX_CONCURRENT}"
        echo -e "Crawl Delay: ${CRAWL_DELAY}"
        echo -e "Respect Robots.txt: ${ROBOTS_TXT}"
        echo -e "User Agent: ${USER_AGENT}"
        echo -e "Configured Seed URLs: ${SEED_COUNT}"
        
        # Show seed URLs if available
        SEED_URLS=$(echo "$BODY" | jq -r '.seedUrls[]? // empty' 2>/dev/null)
        if [ -n "$SEED_URLS" ]; then
            echo
            echo -e "${CYAN}=== Seed URLs ===${NC}"
            echo "$SEED_URLS" | while IFS= read -r url; do
                echo -e "${BLUE}•${NC} $url"
            done
        fi
        
        echo
        echo -e "${CYAN}=== Raw Response ===${NC}"
        echo "$BODY" | jq '.'
    else
        echo "Raw Response:"
        echo "$BODY"
        echo
        log_info "Install 'jq' for better JSON formatting"
    fi
    
else
    log_error "❌ Failed to get crawler status (HTTP $HTTP_CODE)"
    echo
    echo "Response: $BODY"
    
    # Suggest common troubleshooting
    echo
    log_info "Troubleshooting:"
    echo "  1. Is the crawler service running?"
    echo "     Check: ./check-dev-ports.sh"
    echo "  2. Is the correct host configured?"
    echo "     Current: $CRAWLER_HOST"
    echo "  3. Try starting the service:"
    echo "     mvn spring-boot:run"
    
    exit 1
fi