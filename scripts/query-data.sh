#!/bin/bash

# Script to query crawled data from the database
# Usage: ./query-data.sh [command] [options]

set -e

# Configuration
DEFAULT_HOST="http://localhost:8080"
CRAWLER_HOST="${CRAWLER_HOST:-$DEFAULT_HOST}"

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

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

show_help() {
    echo "Usage: $0 [command] [options]"
    echo
    echo "Query crawled data from the distributed web crawler database"
    echo
    echo "Commands:"
    echo "  count                          Get total number of crawled pages"
    echo "  pages [limit] [offset]         List crawled pages (default: limit=50, offset=0)"
    echo "  search <term> [limit]          Search for pages containing term (default: limit=50)"
    echo "  stats                          Get data statistics"
    echo
    echo "Global Options:"
    echo "  -h, --help                     Show this help message"
    echo "  --host <url>                   Crawler API host (default: $DEFAULT_HOST)"
    echo
    echo "Examples:"
    echo "  $0 count                       # Get total page count"
    echo "  $0 pages                       # List first 50 pages"
    echo "  $0 pages 20                    # List first 20 pages"
    echo "  $0 pages 10 50                 # List 10 pages starting from offset 50"
    echo "  $0 search \"python\"             # Search for pages containing 'python'"
    echo "  $0 search \"javascript\" 25      # Search for 25 pages containing 'javascript'"
    echo "  $0 stats                       # Get data statistics"
    echo
    echo "Environment Variables:"
    echo "  CRAWLER_HOST                   Override default crawler host"
}

# Parse global options
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            exit 0
            ;;
        --host)
            CRAWLER_HOST="$2"
            shift 2
            ;;
        *)
            break
            ;;
    esac
done

# Parse command
COMMAND="${1:-pages}"
shift || true

# Helper function to make API requests
make_api_request() {
    local endpoint="$1"
    
    log_info "API: $CRAWLER_HOST$endpoint"
    
    RESPONSE=$(curl -s -w "\n%{http_code}" \
        -H "Accept: application/json" \
        "$CRAWLER_HOST$endpoint")
    
    HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
    BODY=$(echo "$RESPONSE" | sed '$d')
    
    if [ "$HTTP_CODE" -ne 200 ]; then
        log_error "API request failed (HTTP $HTTP_CODE)"
        echo "Response: $BODY"
        exit 1
    fi
    
    echo "$BODY"
}

# Helper function to format page data
format_pages() {
    local json="$1"
    
    if command -v jq >/dev/null 2>&1; then
        echo "$json" | jq -r '.pages[]? | "URL: \(.url)\nFetched: \(.fetchTime)\nStatus: \(.httpStatus)\nContent Hash: \(.contentHash)\n---"'
    else
        echo "$json"
        log_warn "Install 'jq' for better formatting"
    fi
}

# Command implementations
cmd_count() {
    log_info "Getting total page count..."
    
    result=$(make_api_request "/api/data/pages/count")
    
    if command -v jq >/dev/null 2>&1; then
        count=$(echo "$result" | jq -r '.totalPages // 0')
        echo -e "${CYAN}=== Page Count ===${NC}"
        echo -e "Total Pages: ${GREEN}$count${NC}"
        echo
        echo "Raw response:"
        echo "$result" | jq '.'
    else
        echo "Response: $result"
    fi
}

cmd_pages() {
    local limit="${1:-50}"
    local offset="${2:-0}"
    
    log_info "Getting pages (limit=$limit, offset=$offset)..."
    
    result=$(make_api_request "/api/data/pages?limit=$limit&offset=$offset")
    
    if command -v jq >/dev/null 2>&1; then
        count=$(echo "$result" | jq -r '.count // 0')
        echo -e "${CYAN}=== Pages (showing $count) ===${NC}"
        echo
        format_pages "$result"
        echo
        echo "Raw response:"
        echo "$result" | jq '.'
    else
        echo "Response: $result"
    fi
}

cmd_search() {
    local term="$1"
    local limit="${2:-50}"
    
    if [ -z "$term" ]; then
        log_error "Search term is required"
        echo "Usage: $0 search <term> [limit]"
        exit 1
    fi
    
    log_info "Searching for '$term' (limit=$limit)..."
    
    # URL encode the search term
    encoded_term=$(printf '%s' "$term" | sed 's/ /%20/g')
    
    result=$(make_api_request "/api/data/pages/search?query=$encoded_term&limit=$limit")
    
    if command -v jq >/dev/null 2>&1; then
        count=$(echo "$result" | jq -r '.count // 0')
        query=$(echo "$result" | jq -r '.query // "N/A"')
        
        echo -e "${CYAN}=== Search Results ===${NC}"
        echo -e "Query: ${BLUE}$query${NC}"
        echo -e "Results: ${GREEN}$count${NC}"
        echo
        
        if [ "$count" -gt 0 ]; then
            format_pages "$result"
        else
            echo "No pages found matching the search term."
        fi
        
        echo
        echo "Raw response:"
        echo "$result" | jq '.'
    else
        echo "Response: $result"
    fi
}

cmd_stats() {
    log_info "Getting data statistics..."
    
    result=$(make_api_request "/api/data/stats")
    
    if command -v jq >/dev/null 2>&1; then
        total_pages=$(echo "$result" | jq -r '.statistics.totalPages // 0')
        last_updated=$(echo "$result" | jq -r '.statistics.lastUpdated // 0')
        
        # Convert timestamp to readable date
        if command -v date >/dev/null 2>&1 && [ "$last_updated" != "0" ]; then
            readable_date=$(date -r $((last_updated / 1000)) 2>/dev/null || echo "Unknown")
        else
            readable_date="Unknown"
        fi
        
        echo -e "${CYAN}=== Data Statistics ===${NC}"
        echo -e "Total Pages: ${GREEN}$total_pages${NC}"
        echo -e "Last Updated: ${BLUE}$readable_date${NC}"
        echo
        echo "Raw response:"
        echo "$result" | jq '.'
    else
        echo "Response: $result"
    fi
}

# Execute command
case "$COMMAND" in
    count)
        cmd_count "$@"
        ;;
    pages)
        cmd_pages "$@"
        ;;
    search)
        cmd_search "$@"
        ;;
    stats)
        cmd_stats "$@"
        ;;
    *)
        log_error "Unknown command: $COMMAND"
        echo
        show_help
        exit 1
        ;;
esac