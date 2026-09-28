#!/usr/bin/env bash
# Flyway applies only new, versioned migrations; never resets an existing volume.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [ ! -f deploy/.env.catalog.local ]; then
  umask 077
  python3 - <<'PY'
import secrets
from pathlib import Path
p=Path('deploy/.env.catalog.local')
p.write_text(''.join(f'{key}={secrets.token_hex(24)}\n' for key in ['PG_PASSWORD','APP_DB_PASSWORD','INTERNAL_KEY','LOCAL_ADMIN_PASSWORD']))
PY
fi
docker compose --env-file deploy/.env.catalog.local -f deploy/docker/docker-compose.catalog.yml up -d --build "$@"
