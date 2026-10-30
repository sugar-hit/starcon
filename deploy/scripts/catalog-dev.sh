#!/usr/bin/env bash
# Flyway applies only new, versioned migrations; never resets an existing volume.
set -euo pipefail
cd "$(dirname "$0")/../.."
# 环境文件里的键必须齐全，缺一个会让 compose 或容器启动失败。
#
# CHANNEL_WEBHOOK_SIGNING_KEY 是**32 字节主密钥的 base64**（AES-256）。
# 不能用 token_hex —— 那是十六进制文本，base64 解码后不是 32 字节，
# WebhookSignature.keyOf 会直接拒绝。
#
# 已存在的环境文件也要补齐缺失的键：老文件是 2025-09 生成的，
# 里面没有这个键，只在「文件不存在」时生成会让老环境永远起不来。
umask 077
python3 - <<'PY'
import base64
import os
import secrets
from pathlib import Path

p = Path('deploy/.env.catalog.local')
existing = {}
if p.exists():
    for line in p.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        k, v = line.split('=', 1)
        existing[k.strip()] = v.strip()

missing = []
for key in ['PG_PASSWORD', 'APP_DB_PASSWORD', 'INTERNAL_KEY', 'LOCAL_ADMIN_PASSWORD']:
    if not existing.get(key):
        existing[key] = secrets.token_hex(24)
        missing.append(key)
if not existing.get('CHANNEL_WEBHOOK_SIGNING_KEY'):
    existing['CHANNEL_WEBHOOK_SIGNING_KEY'] = base64.b64encode(secrets.token_bytes(32)).decode()
    missing.append('CHANNEL_WEBHOOK_SIGNING_KEY')

if missing:
    p.write_text(''.join(f'{k}={v}\n' for k, v in existing.items()))
    os.chmod(p, 0o600)
    print(f'已补齐环境文件缺失的键：{", ".join(missing)}')
PY

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
