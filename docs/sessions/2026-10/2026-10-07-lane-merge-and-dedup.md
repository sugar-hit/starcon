# 17:20 tick —— 并入 17.1.1.3、手解 api-proxy 冲突、台账重复行去重

## 决策摘要

本轮调度回收了 1 条车道（17.1.1.3 层级授权），派发 2 条新车道（17.2.1.2 部门负责人、17.2.2.2 成员开通与入职）。
两处需要手工介入的地方，都按既有机制处理，没有改动 `lane.mjs` 的编排逻辑。

## 关键 trade-off

### 1. api-proxy 冲突取 HEAD 侧，而非机械求并

`merge-ready` 对 `apps/web/src/lib/api-proxy.ts` 报冲突。先查清两侧各自改了什么，再决定取舍：

- **车道的真实增量在 `iam` 块**：新增 `hierarchy-authorizations`（17.1.1.3 的授权记录与判定）。
  git 已自动合入并保留。
- **冲突块是 `catalog` 白名单**，车道落后 12 个提交，该块对车道而言**只有缩进变化、无语义增量**；
  HEAD 侧则新增了 `zombie-candidates`（3.9.2.2）与 `completeness`（3.9.2.1）两条完整分支，
  是车道的**严格超集**。因此取 HEAD 侧。

判定依据是「查两侧相对共同祖先各自改了什么」，不是数括号，也不是无脑 `--ours`。
若无脑 `--ours` 会连带丢掉车道在 iam 块的增量 —— 这就是为什么必须先 diff 再动手。

### 2. 台账重复行必须当合并的验收项，不能只看退出码

合并后台账出现 3 处重复行：`17.1.1.3`（本次）、`3.9.2.1`、`3.9.2.2`（前两轮遗留）。
成因是 merge-union 的行级兜底并集把车道那份陈旧台账的**占位行也带了进来**，
而 `sync.mjs` 的 `readExisting()` 用 `map.set(id, ...)` **后写覆盖**。

危害不在格式噪音：占位行（`未开发 | —`）排在已交付行**之后**，一旦 sync 跑一遍，
3.9.2.1 与 3.9.2.2 会被打回未开发 —— 这两个模块的成果其实早在 `a9b9987` 就并入集成头了。
这正是「合并不了 → 台账不更新 → plan 重派 → 重复建设 → 合并更难」死循环的**触发器**。

修法：按「证据非 `—` 优先，再 已交付 > 进行中 > 未开发」保留，删占位行，再 sync。

**教训落到纪律上**：`merge-ready` 返回 ok ≠ 台账正确。台账是编排的唯一输入，
它的正确性必须当成合并的验收项。每轮合并后必跑两个判据：

```bash
grep -oE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' \
  docs/product/capability-blueprint/module-progress.md | sort | uniq -d   # 有输出 = 重复行
```

外加**算术校验**：合并后已交付数必须 = 合并前 + 本次新增模块数（本轮 69 + 1 = 70 ✓）。

### 3. `plan` 的 `toDispatch` 混入了活跃车道，必须逐条核对心跳

本轮 `plan --lanes 3` 返回的 `toDispatch` 里，`3.9.1.2` 带 `redispatched: 2`，
但其心跳是 09:20:12Z（**几秒前**）、worktree 有 15 个未提交文件 —— 那是活着的 worker，
不是上一轮漏派的僵尸。照单全派会在同一分支开第二个 writer。

派发前逐条核对（心跳 10 分钟内 + worktree 非空 = 已有 worker，只派真正的新车道）：

```bash
node -e "const d=JSON.parse(require('fs').readFileSync('.dev-agent/lanes.json','utf8'));
for(const id of ['<id>']) console.log(id, d.lanes[id].state, d.lanes[id].heartbeatAt);"
git -C .worktrees/<id> status --porcelain | head
```

本轮只派了 `newlyPlanned` 的两条。另外 `plan` 会把新车道的 state 直接置 `running`
（心跳时间就是 plan 自己那一刻），**这不是 worker 的心跳**，别误判成「已有人在跑」。

### 4. 陈旧检出点核对：新车道 worktree 与 baseSha 一致

按既有教训核对了每条车道 worktree 的 HEAD 与 `lanes.json` 里 `baseSha` 是否一致：

| 车道 | worktree HEAD | baseSha | 落后集成头 |
|------|---------------|---------|-----------|
| 3.9.1.2 | 6bf8912 | 6bf8912 | 20（正常，开发中） |
| 17.1.2.2 | a9b9987 | a9b9987 | 1 |
| 17.2.1.2 | 20711fd | 20711fd | 0 |
| 17.2.2.2 | 20711fd | 20711fd | 0 |

两条新车道检出点与记录一致，无需 `reset --hard`。

## 文件清单

- `apps/web/src/lib/api-proxy.ts` —— 手工解冲突（catalog 白名单取 HEAD 侧，iam 块保留车道增量）
- `docs/product/capability-blueprint/module-progress.md` —— 删 3 处占位重复行 + sync 重算
- `.dev-agent/lanes.json` —— 17.1.1.3 置 `merged`（merge-ready 因冲突未走完，由调度器补记）

## 验证

- `npx vitest run` → **48 files / 841 tests 全绿**
- `pnpm typecheck` → 3/3 successful
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致
- 白名单正则逐条走括号深度（final depth == 0 且 min depth == 0），4 条全部通过
- 台账审计：仅 17.1.1.3 由未开发转已交付，行数 465 不变，无重复行

## 后续待办

- 17.1.1.3 的 worktree 已由 `gc` 清理；分支 `feat/17-1-1-3` 保留
- 观察 `3.9.1.2` 与 `17.1.2.2` 落地后是否再触发 api-proxy 冲突
  （根治方案是把白名单从单行正则重构成多行数组或独立常量，一次性解决）
- 资源：10 核 / Docker 上限 11.7GB，常驻 4 容器合计约 1.3GB，尚有富余；
  但 Testcontainers 验证期的 PG 与 `next build` 不计入 Docker 账面，暂不提并发到 8