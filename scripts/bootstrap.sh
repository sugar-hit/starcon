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
  echo "已创建 .env，请编辑填实际值后再继续"
fi
docker compose -f deploy/docker/docker-compose.dev.yml up -d

echo ">>> 4/5 跑数据库迁移"
pnpm db:migrate || echo "迁移失败 (基础设施可能没起来)，稍后手动重试"

echo ">>> 5/5 启动 dev 服务"
echo ">>> 下一步: pnpm dev  (或 pnpm --filter @starcon/api dev 单启)"