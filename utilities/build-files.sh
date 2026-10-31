#!/usr/bin/env bash
# Builds server/build/libs/server-app.jar with the web app inside. Gradle runs npm ci and the web build.
# Usage: ./utilities/build-files.sh <version>
set -euo pipefail

IMAGE_VERSION="${1:-}"

if [[ -z "$IMAGE_VERSION" ]]; then
  echo "Error: You must provide an image version as the first argument"
  echo "Usage: ./utilities/build-files.sh <version>"
  exit 1
fi

BASE_PATH="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
BACKEND_FOLDER="$BASE_PATH/server"
BACKEND_JAR_FILE="$BACKEND_FOLDER/build/libs/server-app.jar"

echo "=== Deployment Build Script (Linux) ==="
echo "Base Path: $BASE_PATH"
echo "Building version: $IMAGE_VERSION"

cd "$BACKEND_FOLDER"
./gradlew build --no-daemon
if [[ ! -f "$BACKEND_JAR_FILE" ]]; then
  echo "Error: Backend JAR not found"
  exit 1
fi
