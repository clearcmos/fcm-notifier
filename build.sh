#!/usr/bin/env bash
set -e

echo "🔨 Building FCM Notifier..."

# Enter nix-shell and build
nix develop --command bash -c "./gradlew assembleDebug"

echo "✅ Build complete!"
echo "📦 APK location: app/build/outputs/apk/debug/app-debug.apk"
