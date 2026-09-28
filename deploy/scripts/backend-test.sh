#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
docker run --rm \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD/backend:/workspace" -v starcon-maven-cache:/root/.m2 \
  -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -B -ntp verify
