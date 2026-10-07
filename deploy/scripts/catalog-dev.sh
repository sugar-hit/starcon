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

# BuildKit 自动继承宿主机 shell 的 HTTP_PROXY，但构建发生在 Docker Desktop 的 Linux VM 里，
# 那里的 127.0.0.1 是 VM 自己而非宿主机，代理连不上会让 Maven 报
# "SSL peer shut down incorrectly"。这里把回环地址改写成容器可达的 host.docker.internal。
rewrite_proxy() {
  printf '%s' "$1" | sed -e 's#://127\.0\.0\.1:#://host.docker.internal:#g' \
                         -e 's#://localhost:#://host.docker.internal:#g' \
                         -e 's#://\[::1\]:#://host.docker.internal:#g'
}
export BUILD_HTTP_PROXY="$(rewrite_proxy "${HTTP_PROXY:-}")"
export BUILD_HTTPS_PROXY="$(rewrite_proxy "${HTTPS_PROXY:-${HTTP_PROXY:-}}")"
if [ -n "$BUILD_HTTPS_PROXY" ]; then
  echo "build proxy -> ${BUILD_HTTPS_PROXY} (回环地址已改写为 host.docker.internal)"
fi

docker compose --env-file deploy/.env.catalog.local -f deploy/docker/docker-compose.catalog.yml up -d --build "$@"
