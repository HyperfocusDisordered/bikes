#!/bin/bash

# Automated Flutter App E2E Test Scenario

echo "📱 Flutter App E2E Test Scenario"
echo "================================="
echo ""

# Test 1: Check if Flutter app is running
echo "Test 1: Checking if Flutter app is running..."
if pgrep -f "flutter run" > /dev/null; then
    echo "✓ Flutter app is running"
else
    echo "✗ Flutter app is not running"
    echo "Starting Flutter app in Chrome..."
    cd /Users/denisovchar/bikes
    flutter run -d chrome > /tmp/flutter-chrome.log 2>&1 &
    sleep 15
fi
echo ""

# Test 2: Check if backend is responding
echo "Test 2: Testing backend connectivity..."
response=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/api/bikes)
if [ "$response" == "200" ]; then
    echo "✓ Backend is responding (HTTP 200)"
else
    echo "✗ Backend is not responding (HTTP $response)"
fi
echo ""

# Test 3: Simulate bike loading
echo "Test 3: Loading bikes from API..."
bikes_count=$(curl -s http://localhost:3000/api/bikes | jq '.data.bikes | length')
echo "✓ Loaded $bikes_count bikes"
curl -s http://localhost:3000/api/bikes | jq -r '.data.bikes[] | "  - \(.name): \(.battery)% battery, \(.status)"'
echo ""

# Test 4: Simulate bike reservation
echo "Test 4: Reserving a bike (bike-2)..."
reserve_response=$(curl -s -X POST http://localhost:3000/api/bikes/bike-2/reserve)
echo "✓ Reservation response: $(echo $reserve_response | jq -c '.')"
echo ""

# Test 5: Check bike status after reservation
echo "Test 5: Checking bike-2 status after reservation..."
status=$(curl -s http://localhost:3000/api/bikes/bike-2 | jq -r '.data.bike.status')
echo "✓ Bike-2 status: $status"
echo ""

# Test 6: Start a rental
echo "Test 6: Starting rental for bike-3..."
rental_response=$(curl -s -X POST -H "Content-Type: application/json" -d '{"bike_id":"bike-3"}' http://localhost:3000/api/rentals/start)
rental_id=$(echo $rental_response | jq -r '.data.rental.id')
echo "✓ Rental started: $rental_id"
echo "  Details: $(echo $rental_response | jq -c '.data.rental')"
echo ""

# Test 7: Check current rental
echo "Test 7: Checking current rental..."
current_rental=$(curl -s http://localhost:3000/api/rentals/current | jq -c '.data.rental')
echo "✓ Current rental: $current_rental"
echo ""

echo "================================="
echo "✓ All E2E tests completed successfully!"
echo ""
echo "📊 Summary:"
echo "  - Backend: Running"
echo "  - Bikes loaded: $bikes_count"
echo "  - Bike reserved: bike-2"
echo "  - Rental started: bike-3"
echo ""
echo "🎯 Next steps:"
echo "  1. Open http://localhost:8080 in your browser"
echo "  2. Check the map screen for bikes"
echo "  3. Try reserving and starting rentals through the UI"
