#!/usr/bin/env bash
set -euo pipefail

# starcon bootstrap — 仓库首次拉下来跑这个
# 用途：装依赖 + 建本地基础设施 (Postgres/Redis/Kafka) + 跑迁移 + 启 dev

echo ">>> 1/5 检查前置工具"
command -v node >/dev/null || { echo "需要 Node.js >= 20.11"; exit 1; }
command -v pnpm >/dev/null || { echo "需要 pnpm >= 9 (corepack enable)"; exit 1; }
command -v docker >/dev/null || { echo "需要 Docker Desktop / OrbStack"; exit 1; }

echo ">>> 2/5 装依赖"
pnpm install

echo ">>> 3/5 启动基础设施"
if [ ! -f .env ]; then
  cp .env.example .env
  # 🔴 本地开发基础设施的口令**不许**用 .env.example 里的占位值：
  #    占位口令人人相同，而这个 compose 的端口是对外开的。
  #    这里换成随机口令（.env 已被 gitignore，不会进仓库）。
  if command -v openssl >/dev/null; then
    gen() { openssl rand -hex 16; }
  else
    gen() { head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
  fi
  pg_pw="$(gen)"
  minio_pw="$(gen)"
  # BSD sed 与 GNU sed 的 -i 语义不同，用临时文件避免分支。
  sed -e "s|^DEV_POSTGRES_PASSWORD=.*|DEV_POSTGRES_PASSWORD=$pg_pw|" \
      -e "s|^DEV_MINIO_ROOT_PASSWORD=.*|DEV_MINIO_ROOT_PASSWORD=$minio_pw|" \
      -e "s|postgresql://starcon:[^@]*@|postgresql://starcon:$pg_pw@|" \
      .env > .env.tmp && mv .env.tmp .env
  echo "已创建 .env（本地开发口令已随机生成），请编辑填实际值后再继续"
fi
docker compose -f deploy/docker/docker-compose.dev.yml up -d

echo ">>> 4/5 跑数据库迁移"
pnpm db:migrate || echo "迁移失败 (基础设施可能没起来)，稍后手动重试"

echo ">>> 5/5 启动 dev 服务"
echo ">>> 下一步: pnpm dev  (或 pnpm --filter @starcon/api dev 单启)"