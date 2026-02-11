#!/bin/bash
# Install HyperFocus Bridge as macOS LaunchAgent (auto-start on login)

PLIST_NAME="com.hyperfocus.bridge"
PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_NAME}.plist"
DAEMON_PATH="$(cd "$(dirname "$0")" && pwd)/bridge-daemon.js"
LOG_DIR="$HOME/Library/Logs"

echo "Installing HyperFocus Bridge LaunchAgent..."

cat > "$PLIST_PATH" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${PLIST_NAME}</string>
    <key>ProgramArguments</key>
    <array>
        <string>/opt/homebrew/bin/node</string>
        <string>${DAEMON_PATH}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${LOG_DIR}/hyperfocus-bridge.log</string>
    <key>StandardErrorPath</key>
    <string>${LOG_DIR}/hyperfocus-bridge-error.log</string>
    <key>WorkingDirectory</key>
    <string>$(dirname "$DAEMON_PATH")</string>
</dict>
</plist>
EOF

echo "Plist created: $PLIST_PATH"

# Unload if already loaded
launchctl unload "$PLIST_PATH" 2>/dev/null

# Load
launchctl load "$PLIST_PATH"

echo "LaunchAgent loaded!"
echo ""
echo "Commands:"
echo "  Start:   launchctl start ${PLIST_NAME}"
echo "  Stop:    launchctl stop ${PLIST_NAME}"
echo "  Unload:  launchctl unload ${PLIST_PATH}"
echo "  Logs:    tail -f ${LOG_DIR}/hyperfocus-bridge.log"
echo "  Send:    ./bridge-send.sh \"message\""
