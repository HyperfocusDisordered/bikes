#!/bin/bash

# Monitor Flutter app logs in real-time with filtering and highlighting

echo "🚀 Starting Flutter App Log Monitor"
echo "=================================="
echo ""

# Colors for highlighting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Start monitoring
tail -f /tmp/flutter-chrome.log 2>/dev/null | while IFS= read -r line; do
    # Highlight API calls
    if [[ "$line" =~ \[API\] ]]; then
        echo -e "${CYAN}${line}${NC}"
    # Highlight errors
    elif [[ "$line" =~ ERROR|Error|error|Exception|Failed|failed ]]; then
        echo -e "${RED}${line}${NC}"
    # Highlight success messages
    elif [[ "$line" =~ success|Success|SUCCESS|completed|Completed ]]; then
        echo -e "${GREEN}${line}${NC}"
    # Highlight warnings
    elif [[ "$line" =~ Warning|WARNING|warning ]]; then
        echo -e "${YELLOW}${line}${NC}"
    # Highlight bike operations
    elif [[ "$line" =~ bike|Bike|rental|Rental ]]; then
        echo -e "${MAGENTA}${line}${NC}"
    # Default color for other lines
    else
        echo "$line"
    fi
done
