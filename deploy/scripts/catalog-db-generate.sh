#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# File is generated locally, contains no shell code, and is never committed.
set -a
source deploy/.env.catalog.local
set +a
export DB_URL=jdbc:postgresql://host.docker.internal:5433/starcon_catalog
export DB_MIGRATION_USER=starcon_migrator
export DB_MIGRATION_PASSWORD="$PG_PASSWORD"
docker run --rm -e DB_URL -e DB_MIGRATION_USER -e DB_MIGRATION_PASSWORD \
  -v "$PWD/backend:/workspace" -v starcon-maven-cache:/root/.m2 -w /workspace \
  maven:3.9.9-eclipse-temurin-21 mvn -B -ntp -pl services/catalog -am -Pjooq-codegen compile
