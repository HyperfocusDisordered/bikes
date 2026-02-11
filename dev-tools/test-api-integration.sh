#!/bin/bash

# Automated API Integration Testing Script

echo "🧪 API Integration Testing"
echo "=========================="
echo ""

API_URL="http://localhost:3000"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

test_count=0
pass_count=0
fail_count=0

run_test() {
    local test_name=$1
    local method=$2
    local endpoint=$3
    local data=$4
    local expected_status=$5

    test_count=$((test_count + 1))
    echo -n "Test $test_count: $test_name... "

    if [ -z "$data" ]; then
        response=$(curl -s -w "\n%{http_code}" -X $method "$API_URL$endpoint")
    else
        response=$(curl -s -w "\n%{http_code}" -X $method -H "Content-Type: application/json" -d "$data" "$API_URL$endpoint")
    fi

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" == "$expected_status" ]; then
        echo -e "${GREEN}✓ PASS${NC} (HTTP $http_code)"
        pass_count=$((pass_count + 1))
        echo "  Response: $(echo $body | jq -c '.' 2>/dev/null || echo $body)"
    else
        echo -e "${RED}✗ FAIL${NC} (Expected: $expected_status, Got: $http_code)"
        fail_count=$((fail_count + 1))
        echo "  Response: $(echo $body | jq -c '.' 2>/dev/null || echo $body)"
    fi
    echo ""
}

# Check if backend is running
echo "Checking if Clojure backend is running..."
if ! curl -s -f "$API_URL/api/bikes" > /dev/null; then
    echo -e "${RED}✗ Backend is not running!${NC}"
    echo "Please start the backend with: cd backend && clj -M -m bikes-api.simple"
    exit 1
fi
echo -e "${GREEN}✓ Backend is running${NC}"
echo ""

# Run tests
echo "Running API tests..."
echo ""

run_test "Get all bikes" "GET" "/api/bikes" "" "200"
run_test "Get bike by ID (bike-1)" "GET" "/api/bikes/bike-1" "" "200"
run_test "Get bike by invalid ID" "GET" "/api/bikes/invalid-id" "" "404"
run_test "Reserve bike (bike-1)" "POST" "/api/bikes/bike-1/reserve" "" "200"
run_test "Start rental" "POST" "/api/rentals/start" '{"bike_id":"bike-2"}' "200"
run_test "Get current rental" "GET" "/api/rentals/current" "" "200"

# Summary
echo "=================================="
echo "Test Summary:"
echo -e "  Total: $test_count"
echo -e "  ${GREEN}Passed: $pass_count${NC}"
echo -e "  ${RED}Failed: $fail_count${NC}"
echo ""

if [ $fail_count -eq 0 ]; then
    echo -e "${GREEN}✓ All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}✗ Some tests failed${NC}"
    exit 1
fi
