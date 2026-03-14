#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUNDLE_DIR="$ROOT_DIR/bundle"
TARGET_DIR="${HOME}/.openclaw-dev/extensions/message-bridge"

require_file() {
  if [ ! -f "$1" ]; then
    echo "Missing bundle artifact: $1" >&2
    echo "Run: npm run build:bundle" >&2
    exit 1
  fi
}

require_file "$BUNDLE_DIR/index.js"
require_file "$BUNDLE_DIR/package.json"
require_file "$BUNDLE_DIR/openclaw.plugin.json"

if [ -d "$TARGET_DIR/dist" ] || [ -d "$TARGET_DIR/node_modules" ]; then
  echo "Warning: detected legacy install layout in $TARGET_DIR" >&2
  echo "Continuing with minimal bundle install." >&2
fi

mkdir -p "$TARGET_DIR"
rm -f "$TARGET_DIR/index.js" \
  "$TARGET_DIR/package.json" \
  "$TARGET_DIR/openclaw.plugin.json" \
  "$TARGET_DIR/README.md" \
  "$TARGET_DIR/package-lock.json" \
  "$TARGET_DIR/tsconfig.json"
rm -rf "$TARGET_DIR/dist" \
  "$TARGET_DIR/src" \
  "$TARGET_DIR/tests" \
  "$TARGET_DIR/node_modules"

cp "$BUNDLE_DIR/index.js" "$TARGET_DIR/index.js"
cp "$BUNDLE_DIR/package.json" "$TARGET_DIR/package.json"
cp "$BUNDLE_DIR/openclaw.plugin.json" "$TARGET_DIR/openclaw.plugin.json"

if [ -f "$BUNDLE_DIR/README.md" ]; then
  cp "$BUNDLE_DIR/README.md" "$TARGET_DIR/README.md"
fi

echo "Installed message-bridge bundle to:"
echo "  $TARGET_DIR"
echo "Installed files:"
ls -1 "$TARGET_DIR" | sed 's/^/  - /'
echo "Next:"
echo "  openclaw --dev gateway run --allow-unconfigured --verbose"
