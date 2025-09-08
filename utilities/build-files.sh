#!/usr/bin/env bash
set -euo pipefail

IMAGE_VERSION="${1:-}"
NO_PUSH="${NO_PUSH:-false}"

if [[ -z "$IMAGE_VERSION" ]]; then
  echo "Error: You must provide an image version as the first argument"
  echo "Usage: ./utilities/build.sh <version> [--no-push]"
  exit 1
fi

if [[ "${2:-}" == "--no-push" ]]; then
  NO_PUSH="true"
fi

BASE_PATH="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
FRONTEND_FOLDER="$BASE_PATH/web"
BACKEND_FOLDER="$BASE_PATH/server"
FRONTEND_BUILD_FOLDER="$FRONTEND_FOLDER/build"
BACKEND_STATIC_FOLDER="$BACKEND_FOLDER/src/main/resources/static"
DOCKERFILE_PATH="$BACKEND_FOLDER/Dockerfile"
BACKEND_JAR_FILE="$BACKEND_FOLDER/build/libs/server-app.jar"
BUILD_OUTPUT_FOLDER="$BASE_PATH/build"

DOCKER_REPO="bingud/filemat"

echo "=== Deployment Build Script (Linux) ==="
echo "Base Path: $BASE_PATH"
echo "Building version: $IMAGE_VERSION"

# 1. Build frontend
cd "$FRONTEND_FOLDER"
npm install
rm -rf "$FRONTEND_BUILD_FOLDER"
npm run build

# 2. Copy frontend to backend
rm -rf "$BACKEND_STATIC_FOLDER"
mkdir -p "$BACKEND_STATIC_FOLDER"
cp -r "$FRONTEND_BUILD_FOLDER"/* "$BACKEND_STATIC_FOLDER"/

# 3. Build backend
cd "$BACKEND_FOLDER"
./gradlew build --no-daemon
if [[ ! -f "$BACKEND_JAR_FILE" ]]; then
  echo "Error: Backend JAR not found"
  exit 1
fi