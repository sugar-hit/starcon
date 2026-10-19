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
LEDGER="docs/product/capability-blueprint/module-progress.md"
cd "$ROOT" || exit 2

command -v node >/dev/null 2>&1 || { echo "环境缺少 node，无法构建"; exit 2; }
[ -d "$SITE" ] || { echo "站点目录不存在：$SITE"; exit 2; }

# git 必须是非交互的。凭证助手在无人值守时会把 git 挂死在提示符上，
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
PULL="failed"
FETCHED=0
if git rev-parse --git-dir >/dev/null 2>&1; then
  # 只 fetch，不 pull。fetch 只更新远端引用，**不碰本地工作树** ——
  # 本地有没有未推送的提交、有没有 rebase 中间态，都不影响这一步。
  if run_bounded 30 git fetch --quiet origin main >/tmp/progress-site-pull.log 2>&1; then
    FETCHED=1
    PULL="ok"
    echo "  fetch OK — origin/main = $(git rev-parse --short origin/main)"
  else
    echo "  fetch FAILED（超时 / 代理不可达）—— 回退到本地工作树"
    sed -n '1,6p' /tmp/progress-site-pull.log | sed 's/^/    | /'
  fi
else
  echo "  非 git 仓库，跳过同步"
fi

# 选数据源：优先 origin/main（共享真相），只在远端不可用或远端明显更旧时用工作树。
#
# 为什么不用 `git pull`：`git pull` 要动本地分支。本仓库的站点提交只在本地 main 上
# （未 push），远端一旦前进就分叉 —— 实测 `git pull --ff-only` 直接报
# "Diverging branches can't be fast-forwarded"，拉取永远失败 → 站点静默停止更新；
# 而 rebase 会在 .gitignore 这类共享文件上冲突，失败后每天都会卡在同一处。
# 现在改成「fetch + 从 ref 读文档」，本地 git 状态与刷新彻底解耦。
SRC="working-tree"
if [ "$FETCHED" = "1" ]; then
  if git merge-base --is-ancestor origin/main HEAD 2>/dev/null; then
    # origin/main 是 HEAD 的祖先 → 本地有更新的未推送提交，本地才是最新的
    SRC="working-tree"
    echo "  数据源：本地工作树（origin/main 落后于本地 HEAD）"
  else
    # 远端已前进或已分叉 → 远端才是最新的台账
    SRC="origin/main"
    echo "  数据源：origin/main（远端已前进 / 与本地分叉）"
  fi
else
  echo "  数据源：本地工作树（未能 fetch）"
fi

echo "== 2/3 构建 =="
if [ "$SRC" = "working-tree" ]; then
  node "$SITE/build.mjs" || { echo "BUILD_FAILED —— 构建报错，放弃发布"; exit 1; }
else
  node "$SITE/build.mjs" --docs-ref "$SRC" || { echo "BUILD_FAILED —— 构建报错，放弃发布"; exit 1; }
fi

echo "== 3/3 数据自检 =="
node "$SITE/check.mjs" || { echo "CHECK_FAILED —— 数据与台账对不上，放弃发布"; exit 1; }

COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
if [ "$SRC" = "origin/main" ]; then
  COMMIT="$(git rev-parse --short origin/main 2>/dev/null || echo unknown)"
fi

# 本地台账与所用数据源不一致时要点名 —— 说明本地有未推送的台账改动
LEDGER_NOTE="none"
if [ "$SRC" = "origin/main" ] && git diff --quiet origin/main -- "$LEDGER" 2>/dev/null; then
  :
elif [ "$SRC" = "origin/main" ]; then
  LEDGER_NOTE="local-ledger-differs"
fi

echo "READY —— 产物就绪：$SITE/dist"
echo "PULL_STATUS=$PULL"
echo "SOURCE_COMMIT=$COMMIT"
echo "SOURCE=$SRC"
echo "LEDGER_NOTE=$LEDGER_NOTE"
