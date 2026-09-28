#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
./deploy/scripts/catalog-dev.sh postgres
compose=(docker compose --env-file deploy/.env.catalog.local -f deploy/docker/docker-compose.catalog.yml)
"${compose[@]}" build identity catalog
"${compose[@]}" run --rm migrate-identity
"${compose[@]}" run --rm migrate-catalog
