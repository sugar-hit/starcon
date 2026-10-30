# 2026-10-29 23:21 · 集成头同步 main 第 104 轮 —— 冲突全解、干净并入（10 个提交 / 5 个文件）

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：已同步**。零冲突并入 `origin/main` 的 **10 个提交**，
> 这是第 79 轮以来**第一次没有冲突**的同步轮次。

## 摘要

| 项 | 值 |
| --- | --- |
| 结果 | **已同步**（零冲突） |
| 并入的 main 提交数 | **10** |
| 差异规模 | **5 个文件 / +449 / −30**（自 merge-base `647bbcf0` 起算） |
| 冲突 | **0**（merge 直接走 `ort` 策略成功） |
| 预检 | **绿**（`./mvnw -q compile` rc=0；`pnpm typecheck` 3/3） |
| 台账 | **432 已交付 / 0 进行中 / 33 未开发**（465 总数，92.9%）—— **本轮零变化** |
| merge commit | `4d3556d5d025ff7633451bd59698aff012589c46` |
| push | 已 push 到 `origin/integration/module-dev` |
| 等闸时长 | **约 65 分钟**（22:16 → 23:21:17，TTL 接管） |

## 一、根因已从发布侧根治：27 个结构性冲突消失

第 79–103 轮连续 25 轮都撞同一堵墙，根因是 **main 上的提交是 squash（单父）**，
`merge-base` 永远停在 `6522c497`，集成头每前进一步就多一个 add/add 冲突点。

本轮发布侧改用了**双父真提交**：

```
git log -1 --format='parents=[%P]' origin/main
⇒ b813701c  parents=[4daffb1f0ac55ceef477bcaca94fbb7e3e05a61c 95cc5dace3d47560e6a5603683418b4530bb6f24]
```

⇒ `merge-base` 从 `6522c497` 前移到 **`647bbcf0`**（= 第 103 轮那次同步留下的集成头提交）
⇒ **main 侧已经包含集成头的完整历史** ⇒ 27 个结构性冲突一次性消失，
冲突面从 **1032 个文件**收敛到 **5 个文件**。

⇒ 这验证了 backlog `2026-10-21-main-squash-merge-divergence.md` 的判断：
**根治路径在发布侧改用真提交，不在同步侧解冲突。**

## 二、merge 前先查重叠，确认零文件交叉

```
git diff --name-only 647bbcf0 integration/module-dev   ⇒ 19 个文件（13.6.1.1 自定义报表车道）
git diff --name-only 647bbcf0 origin/main               ⇒ 5 个文件
comm -12 <两者>                                          ⇒ 空 ⇒ 无重叠
git status --porcelain                                  ⇒ 空 ⇒ 工作区干净
```

⇒ 两侧改动的文件集合完全不相交 ⇒ 冲突面为空是**可预判**的，不是运气。
（协议要求 `git status --porcelain` 必须为空才允许合并 —— 本轮满足。）

## 三、闸：等约 65 分钟，TTL 接管（未抢、未清、未 force）

| 时刻 | 事实 |
| --- | --- |
| 22:16 首读 `gate status` | `held` / `ageSeconds=462` / `ttl=4200` / **`stale=false`** ⇒ 按协议判「持闸者活着」⇒ 等 |
| 等闸期间取证 | `owner` PID `10103` **查无此进程**（协议已提醒 owner PID 不是持闸证据）；闸目录内 `keepalive-*` 最后 mtime **22:07:30** 后再无刷新；`ps` 无 `mvnw`/`surefire`/`java` 构建进程 ⇒ **持闸者已崩** |
| 23:21:17 | `gate acquire` 在 `age > 4200`（约 23:17:30）后成功 ⇒ **TTL 接管** |

全程用**单实例密集竞态**（`mkdirSync` 原子互斥，1 秒密度），未使用 `rmSync`、未 force、未清闸。
等闸期间只做只读取证，**未跑 `git fetch`、未跑任何构建**。

⚠️ 与第 103 轮一致：`stale` 在 **L2 运行期间恒为 false**（L2 守护每 60 秒 `touch` 新建文件进闸目录 ⇒ 刷新目录 mtime），
本轮之所以能在 TTL 边界接管，是因为**此刻没有 L2 在跑**（keepalive 自 22:07:30 起停更）。
若接管前 L2 启动，TTL 路径会重新变得机制性不可达。

## 四、🔴 本轮新增的运维教训：**`git fetch` 失败会被静默读成「无新提交」**

首次 `git fetch origin --prune` 直接失败：

```
fatal: unable to access 'https://github.com/sugar-hit/starcon.git/':
LibreSSL SSL_connect: SSL_ERROR_SYSCALL in connection to github.com:443
```

此时本地 `origin/main` 仍是旧 ref `823ebca5`（= 第 79–103 轮那颗 squash）。
若就此执行 `merge-base --is-ancestor`，会**得到与「真的没有新提交」完全一样的 `HAS_UPDATE` 判断方向**，
但依据是一颗**陈旧 ref** ⇒ **本轮本该并入 10 个提交，实际会漏掉全部**。

⇒ 重试后 fetch 成功，`origin/main` 从 `823ebca5` 前进到 `b813701c`（另有 `origin/chore/sync-module-dev` 被 force-update）。

⇒ **教训**：`git fetch` 的**退出码**必须单独判定，不能只看 `merge-base` 的结果。
本轮 fetch 是**先失败、第二次成功**的 ⇒ 若只看 `merge-base --is-ancestor` 的布尔值就会漏同步。
建议把「fetch rc 必须为 0」写进协议第 2 步。

## 五、预检（快速预检，不是 L2）

```
cd backend && ./mvnw -q compile        ⇒ MVN_COMPILE_EXIT=0
pnpm typecheck                         ⇒ Tasks: 3 successful, 3 total（TYPECHECK_EXIT=0）
```

⚠️ `pnpm typecheck` 走的是 turbo **FULL TURBO 缓存命中**（3/3 cached）——
本轮 merge 只动 5 个文件、**零 TS 源码**（`GrantPermissions.java` 是 Java，其余是 `tools/module-progress/*.mjs` 与 docs），
缓存命中是**有效**的，不是「跳过检查」。

⚠️ **本轮 merge commit `4d3556d5` 的代码没经过本流水线 L1/L2** ——
下一轮流水线的 L2 必须重点看这个 merge commit。

## 六、台账：零变化（如实记录）

```
同步前（ec720b03）  已交付 432 · 进行中 0 · 未开发 33
同步后（sync.mjs）  已交付 432 · 进行中 0 · 未开发 33   ← 零变化
```

`node tools/module-progress/sync.mjs` 输出 `✓ 同步 18 卷 / 465 个 L4 模块`，
且**工作区零文件改动**（`git status --porcelain` 为空）。

**为什么 main 带进来的 25 项交付没有抬高台账**：
`4daffb1f` 交付的 25 个模块（核心指标卡 / 店铺概览 / 利润分析 / 广告归因 等）
**集成头早就有了而且更新**（第 103 轮已量化：集成头领先 25 项交付 = 工作台 5 + 利润分析 17 + 广告 3）。
本轮 main 改用真提交后，这 25 项在两侧**收敛为同一份**，台账基线因此不动 ——
**这不是「同步没生效」，而是「要同步的内容本来就在集成头」。**

## 七、并入的 10 个 main 提交

- `823ebca5` 11.2.3.2/12.1.1.1/12.2.1.1 退货处置·应收单·收款单 —— 第 79–103 轮同一颗 squash，本轮随真提交一并并入
- `24c9082d` 25 个模块 工作台·利润·广告分析能力交付
- `aaec8c26` ci-gate 合并闸门识别未出结果的作业
- `1cf4512f` ci-gate 合并闸工具须用 PR 头版本
- `2b1cb0a8` docs 第 69 轮并入 main 记录
- `28b5ca04` tenancy 租户授权判定类注释排版
- `268ba86c` ci-gate 第 69 轮 CI 判定补记
- `4daffb1f` 25 个模块 工作台·利润·广告分析能力交付（落地版本）
- `95cc5dac` docs 第 69 轮合并结果与根治验证
- `b813701c` docs 第 69 轮并入 main 根治验证（main tip，双父真提交）

⚠️ 其中 `aaec8c26` / `1cf4512f` / `268ba86c` / `b813701c` 动的是 **`tools/module-progress/ci-gate.mjs`**
与 **`MAIN_MERGE_CRON_PROMPT.md`**（合并闸工具 + main 合并 cron 的 prompt）——
它们是 main 侧的内容，随合并进入集成头，**本任务未改动任何 cron 任务的调度或配置**。

## 八、文件清单

- 新增：`docs/sessions/2026-10/2026-10-29-2321-main-sync-round104-synced-clean.md`（本文件）
- 新增（随 main 并入）：`docs/sessions/2026-10/2026-10-29-2100-main-merge-round69-conflicts-resolved-pr65.md`
- 随 main 并入：`backend/libraries/tenancy/.../GrantPermissions.java`、`tools/module-progress/ci-gate.mjs`、
  `tools/module-progress/ci-gate.itest.mjs`、`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`
- merge commit：`4d3556d5d025ff7633451bd59698aff012589c46`

## 九、后续待办

1. **协议第 2 步应加「fetch 退出码必须为 0」判据**（见 §四）—— 本轮实测 fetch 会瞬时失败。
2. backlog `2026-10-21-main-squash-merge-divergence.md` 里「授权逐文件取 ours」的待办
   **仍为未勾选**，且**发布侧改用真提交后已不再需要** ⇒ 建议该待办按「已由发布侧根治」关闭，
   避免日后有人拿它当依据自行解冲突。
3. backlog 同文件里「TTL 接管非原子」「L2 脚本必须走调度闸」两条**仍未落地** ——
   本轮 TTL 接管是单实例，侥幸没撞上并发接管；
   而 `watch-lanes.sh` 仍在把闸目录当「可随便写文件的目录」，机制性隐患还在。
4. 下一轮流水线的 **L2 必须重点看 merge commit `4d3556d5`**（它的代码未过本流水线 L1/L2）。
