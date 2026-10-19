#!/usr/bin/env bash
# starcon 交付进度看板 · 每日自动刷新（确定性部分）
#
# 只做「同步 → 构建 → 自检」三件事，不负责发布。
# 发布由调用方（cron 会话）用 website_deploy 工具完成，见 REFRESH.md。
#
# 退出码：
#   0 = 产物就绪，可以发布
#   1 = 构建或自检失败，**必须放弃发布**（旧站点保持不动）
#   2 = 环境不满足（缺 node / 目录不存在）

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SITE="$ROOT/tools/progress-site"
cd "$ROOT" || exit 2

command -v node >/dev/null 2>&1 || { echo "环境缺少 node，无法构建"; exit 2; }
[ -d "$SITE" ] || { echo "站点目录不存在：$SITE"; exit 2; }

# git 必须是非交互的。凭证助手在无人值守时会把 git pull 挂死在提示符上，
# 那会让每天 08:00 的刷新整条卡住。GIT_TERMINAL_PROMPT=0 让它在需要凭证时
# 立刻失败而不是等待；下面的超时闸是第二道保险。
export GIT_TERMINAL_PROMPT=0
export GIT_ASKPASS=""
export GCM_INTERACTIVE=never

# 跨平台的硬超时（macOS 自带没有 timeout 命令）
run_bounded() {
  local secs="$1"
  shift
  "$@" &
  local pid=$!
  local waited=0
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$secs" ]; then
      kill -9 "$pid" 2>/dev/null
      wait "$pid" 2>/dev/null
      return 124
    fi
    sleep 1
    waited=$((waited + 1))
  done
  wait "$pid"
}

echo "== 1/3 同步远端 =="
PULL="skipped"
if git rev-parse --git-dir >/dev/null 2>&1; then
  if run_bounded 30 git pull --ff-only >/tmp/progress-site-pull.log 2>&1; then
    PULL="ok"
    echo "  pull OK — $(git rev-parse --short HEAD) $(git log -1 --pretty=%s)"
  else
    # 拉取失败不阻断：本地工作树仍是一份可用的数据源，但必须在报告里点名
    PULL="failed"
    echo "  pull FAILED（超时 / 代理不可达 / 本地有未提交改动）—— 继续用本地工作树构建"
    sed -n '1,6p' /tmp/progress-site-pull.log | sed 's/^/    | /'
  fi
else
  echo "  非 git 仓库，跳过同步"
fi

echo "== 2/3 构建 =="
if ! node "$SITE/build.mjs"; then
  echo "BUILD_FAILED —— 构建报错，放弃发布"
  exit 1
fi

echo "== 3/3 数据自检 =="
if ! node "$SITE/check.mjs"; then
  echo "CHECK_FAILED —— 数据与台账对不上，放弃发布"
  exit 1
fi

COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
echo "READY —— 产物就绪：$SITE/dist"
echo "PULL_STATUS=$PULL"
echo "SOURCE_COMMIT=$COMMIT"
