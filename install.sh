#!/usr/bin/env bash
set -e

echo "📱 Installing FCM Notifier to connected device..."

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected!"
    echo "Run 'adb devices' to check connection"
    exit 1
fi

# Enter nix-shell and install
nix develop --command bash -c "./gradlew installDebug"

echo "✅ App installed successfully!"
