# Mac B 配置清单（执行机）

> Mac B 是**执行机**：只领票、干活、交付。**不调度、不合并、不跑 L2、不改台账。**
> 设计依据见 `PIPELINE-MULTI.md`。
>
> 本清单按顺序做。每步都有**验收判据**，不通过就别往下走 ——
> 后面每一步都建立在前一步的正确性上。

---

## 第 0 步：前置依赖

| 依赖 | 版本 | 检查命令 |
| --- | --- | --- |
| Node | **≥ 22.12**（仓库 `.nvmrc` = 22） | `node -v` |
| pnpm | **9.0.0**（仓库 `packageManager` 锁定） | `pnpm -v` |
| Java | **25**（后端是 Spring Boot + jOOQ） | `java -version` |
| Docker | 能跑 Testcontainers | `docker info` |

```bash
node -v && pnpm -v && java -version 2>&1 | head -1 && docker info --format '{{.ServerVersion}}'
```

**Docker Desktop 资源**：内存 ≥ 6 GB、CPU ≥ 4 核。
Testcontainers 每次集成测试起一个 PostgreSQL 16 容器，内存不够会 OOM，
表现为「测试莫名其妙全红」，很容易被误判成代码问题。

> ⚠️ **Mac B 的 load 基线大概率和 Mac A 不同**（B 上跑什么常驻应用不一样）。
> 所以 B 的 `LOAD_MAX` **必须自己实测**，不能照抄 A 的 12。
> 阈值低于本机空载基线 = 把流水线整个锁死（A 踩过：设成 6，而空载就有 7.04）。

---

## 第 1 步：拿代码

```bash
mkdir -p ~/Project && cd ~/Project
git clone https://github.com/sugar-hit/starcon.git
cd starcon
git checkout integration/module-dev
```

**必须切到 `integration/module-dev`**（流水线的工作分支），不是 `main`。
Mac A 的集成头在 `origin/integration/module-dev` 上，Mac B 要跟它对齐。

**验收**：

```bash
git rev-parse --abbrev-ref HEAD      # → integration/module-dev
git log -1 --oneline
```

---

## 第 2 步：装依赖

```bash
cd ~/Project/starcon
pnpm install
```

**验收**：`node -e "require('fs').existsSync('node_modules')"` → `true`

---

## 第 3 步：注册合并驱动器（**不要跳过**）

```bash
cd ~/Project/starcon
node tools/module-progress/lane.mjs init
```

这一步做两件**每台机器各自一份**的事：

1. 把 `merge.contract-union.driver` 注册到**本地 `.git/config`**
   —— 这是「契约 / 台账多写者自动求并」的驱动器。
   **它不在版本库里**（git config 是本机状态），所以**新机器必须重跑一次**。
   不跑的后果：Mac A 合并时找不到 `contract-union` 驱动 → 契约与台账变成普通文本冲突。
2. 确认 `.worktrees/` 已在 `.gitignore` 里（并行车道的 worktree 目录）。

**验收**：

```bash
git config --get merge.contract-union.driver
# → node tools/module-progress/merge-union.mjs %O %A %B
```

---

## 第 4 步：环境变量

```bash
cd ~/Project/starcon
cp .env.example .env
```

`.env` 按实际值填写。**Mac B 不需要真的起服务**，但 Testcontainers 跑集成测试需要
`DB_URL` / `DB_USER` / `DB_PASSWORD` / `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD`
能被测试配置读到（`.env.example` 里已有 `DATABASE_URL`，按 `AGENTS.md` 的约定对齐）。

**验收**：`grep -c . .env` → 非 0，且没有 `change-me-in-prod` 残留。

---

## 第 5 步：本机运行参数（gitignored，**不进版本库**）

Mac B 有**自己的** `.dev-agent/PIPELINE.local.md`。
两机各调各的 —— 配额、load 阈值都按本机硬件定，不共享。

```bash
mkdir -p ~/Project/starcon/.dev-agent
cat > ~/Project/starcon/.dev-agent/PIPELINE.local.md <<'EOF'
# starcon 流水线 · Mac B 本地运行参数（不进版本库）

## 机器可读档位（供脚本 eval，勿改格式）

RUNNING_QUOTA=6
LOAD_MAX=0
ACTIVE_HOURS="8-20"
QUIET_FROM="20:30"
QUIET_TO="08:00"

> ⚠️ LOAD_MAX=0 是**占位**。测出 B 的空载基线后填成 基线×1.2，
> 例如基线 5 → 填 6。**填 0 会让容量闸永远拒绝派发**（这是故意的：
>   没实测就不许开工）。
>   实测方法：空闲时连采 3 次 `uptime`，取 1min 的最大值。
EOF
```

> **为什么 B 不需要 `LANES` / `CRON` / `THROTTLE_MIN`**：
> B 不排车道（没有 `plan`），所以没有 `--lanes`；
> B 的节流不是时间闸而是「有没有票」，所以没有 `THROTTLE_MIN`；
> B 的触发频率写在它自己的 cron 表达式里，所以没有 `CRON`。

**验收**：

```bash
cd ~/Project/starcon
grep -E '^(RUNNING_QUOTA|LOAD_MAX|ACTIVE_HOURS)=' .dev-agent/PIPELINE.local.md
```

---

## 第 6 步：Git 凭据（能 push `feat/*` 分支）

Mac B 要把车道分支 push 回远端，**Mac A 才能收割**。

```bash
git remote -v          # 确认远端名是 upstream（脚本默认按这个名字找）
```

如果远端叫 `origin`（自己 clone 的默认名），**改个名**，否则脚本找不到远端：

```bash
git remote rename origin upstream
```

或者在 Mac B 上给脚本一个环境变量：`export LANE_REMOTE=origin`。

**验收**：

```bash
git push --dry-run upstream HEAD:refs/heads/_probe_mac_b && git push upstream :refs/heads/_probe_mac_b
```

能 push 说明凭据可用。

> 用**同一账号** clone 和 push 最简单。若 Mac B 用的是另一个账号，
> 它推的分支 Mac A 未必有权限收割 —— **两机必须能互相读写 `feat/*` 分支**。

---

## 第 7 步：建 Mac B 的定时任务

**先确认 Mac A 还没有开始签发票据**（`PIPELINE-MULTI.md` 的 P2 阶段才开始签）。
在 P2 之前，Mac B 的定时任务只做「空轮询」，用来验证环境。

### 7.1 先手动跑一次空轮询（确认脚本能跑）

```bash
cd ~/Project/starcon
node tools/module-progress/remote-worker.mjs poll
```

**期望输出**：`{"ok": true, "unclaimed": [], "inFlight": []}`
（Mac A 还没签发票据时，空数组是**正确**的。）

如果报 `fetch upstream 失败` → 回第 6 步检查远端名与凭据。

### 7.2 创建 cron

在 Mac B 的 Mavis 里执行：

```
tool_search: cron_create
mcp_invoke(
  tool_name="cron_create",
  arguments={
    "cron_name": "starcon 跨机执行（Mac B）",
    "schedule": "*/5 8-20 * * *",
    "timezone": "Asia/Shanghai",
    "agent_name": "mavis",
    "session": { "mode": "new" },
    "project": "/Users/tommy/Project/starcon",
    "prompt": "<见下方 7.3 全文>"
  }
)
```

| 参数 | 值 | 为什么 |
| --- | --- | --- |
| `schedule` | `*/5 8-20 * * *` | 每 5 分钟轮询；`8-20` 让夜间**结构上**不触发（静默窗口第一道闸） |
| `session.mode` | `new` | 每轮是全新会话，不继承上次上下文（与 A 组一致） |
| `project` | `/Users/tommy/Project/starcon` | **必须是 Mac B 上的绝对路径** |

> ⚠️ **`*/5` 与 `0` 的错峰**：Mac A 在整点签发票据，B 在整点后最多 5 分钟领走。
> 平均延迟 2.5 分钟，相对 A 的 60 分钟批次节奏约 4% 损耗 —— 可以接受。
>
> ⚠️ **不要给 B 的 cron 加 `THROTTLE_MIN` 那种时间闸**：B 空转只是
> `git fetch` + 扫分支（< 2 秒），**零发热**。加了时间闸反而让 B 闲着等。

### 7.3 Mac B 的 cron 任务书正文

```markdown
你负责 `/Users/tommy/Project/starcon` 的**跨机执行**。你是 Mac B（执行机）。

每次运行先完整阅读：
1. 仓库根 `AGENTS.md`
2. `tools/module-progress/PIPELINE.md`（单机协议：硬性要求、验证层级、任务书模板）
3. `tools/module-progress/PIPELINE-MULTI.md`（双机协议：你的角色与边界）
4. `.dev-agent/PIPELINE.local.md`（本机运行参数：配额、load 阈值、静默窗口）

仓库文件是唯一执行协议。本字段不重复检查命令、任务模板或进度数字。
本机参数从 `.dev-agent/PIPELINE.local.md` 读取；缺失或冲突时停止新增任务并报告差异，
不要自行猜值。不得执行配置中的 shell/eval。

## 你的角色边界（越界即视为未完成）

**你只做四件事**：领票 → 干活 → 交付 → 心跳。

**你绝不做**：
- 不跑 `lane.mjs plan`（不排车道、不分配 Flyway 版本号）
- 不跑 `lane.mjs merge-ready`、不 merge、不 push 集成分支
- 不跑 L2 全量验证（那是 Mac A 在集成头上跑的唯一一次）
- 不改台账 `docs/product/capability-blueprint/module-progress.md` 的任何一行
- 不取 Mac A 的调度闸

**为什么**：共享状态只允许一个写者。集成头、Flyway 版本号、在途车道表、台账
这四样都只有 Mac A 写。两机都写 ⇒ 跨机冲突无法根除（版本号撞号、同 L3 兄弟模块
并发、集成头 push 竞态、台账行级冲突）。协议见 `PIPELINE-MULTI.md` 第 1 节。

## 第 0 步：闸门（只读，不领票）

按顺序检查，任一命中就**本轮零领票**并结束：

1. **静默窗口**：`date '+%H:%M'`，若 `>= 20:30` 或 `< 08:00` → 结束。
   （cron 的 `8-20` 已经挡住大部分，这道是兜底）
2. **本机容量**：读 `.dev-agent/PIPELINE.local.md` 的 `RUNNING_QUOTA` / `LOAD_MAX`，
   跑 `uptime` 与 `docker ps --filter "ancestor=postgres:16-alpine" --format '{{.Status}}' | wc -l`。
   - 在途车道数 >= `RUNNING_QUOTA` → 零领票
   - load 1min > `LOAD_MAX` → 零领票（**看趋势**：1min > 5min 说明正在爬升）
   - PG 容器数 >= 6 → 零领票（降噪闸，**排除常驻的 starcon-mig-v2**）
3. **模型额度可读**：读不到就写「未知」，不要假装有。

命中闸门时**只做只读检查 + 简短汇报**，不要领票、不要建 worktree、不要起容器。

## 第 1 步：取调度闸

```bash
cd /Users/tommy/Project/starcon
node tools/module-progress/lane.mjs gate status     # 先读
node tools/module-progress/lane.mjs gate acquire    # 确认要领票时才取
```

**撞闸（退出码 3）= 上一轮还没跑完** → 本轮直接结束，不要抢。
**收尾时必须 `gate release`**，否则下一轮一直撞闸。

## 第 2 步：轮询票据

```bash
cd /Users/tommy/Project/starcon
node tools/module-progress/remote-worker.mjs poll
```

输出 `unclaimed`（待领）与 `inFlight`（本机在跑）。
**空数组是完全正常的结果**（Mac A 还没签发票据，或票都被领走了）→ 释放闸并结束。

## 第 3 步：领票并派工

对 `unclaimed` 里**最多** `RUNNING_QUOTA - 在途数` 条：

```bash
node tools/module-progress/remote-worker.mjs claim --lane <模块ID>
node tools/module-progress/remote-worker.mjs read  --lane <模块ID>
```

`read` 打印的就是**完整任务书**（由 Mac A 生成，含蓝图原文、预分配迁移版本号、
硬性要求、验证命令）。把它作为 `task` 任务书派给一个 `worker` 子任务
（`agent_name: "worker"`, `run_in_background: true`）。

**任务书里必须补充给 worker 的三条**：
1. 所有命令都在 `claim` 返回的 worktree 目录里执行，**不要动主工作区**
2. **不要 push，不要跑 `lane.mjs land`**（并入由 Mac A 负责）——
   交付走下面的 `finish`
3. 每 25 分钟打一次心跳：
   `node tools/module-progress/remote-worker.mjs heartbeat --lane <模块ID>`

worker 跑完后，把它的交付报告写进一个临时文件（在 worktree 的 `.tmp/` 下），
然后交付：

```bash
node tools/module-progress/remote-worker.mjs finish --lane <模块ID> --report <报告文件>
```

`finish` 会删掉票据（= 完成信号）、把报告写进 commit message、push 车道分支。

## 第 4 步：汇报

简短汇报（12 行以内）：
- 容量闸判定：在途数 / 配额、load 三点值及趋势、PG 容器数 → GO / STOP（命中哪条）
- 领到几条票、分别是什么模块
- 每条票的状态：跑完 / BLOCKED / RESOURCE_BLOCKED / 失败
- 交付成功的车道（模块 ID + commit SHA）
- 失败车道的**明确原因**（不要笼统写「失败」）
- 距离静默窗口还有多久

**只汇报有变化的结果。** 没有票且一切正常 → 简短结束。
不要谎报「已交付」—— 交付以 `finish` 成功返回为准。
```

---

## 第 8 步：Mac B 自检清单

在 Mac B 上逐条跑，全绿才算配好：

```bash
cd ~/Project/starcon

# 1. 分支对
git rev-parse --abbrev-ref HEAD                        # integration/module-dev

# 2. 合并驱动器已注册
git config --get merge.contract-union.driver            # 有输出

# 3. 台账解析与 Mac A 一致
node tools/module-progress/sync.mjs | tail -5           # 计数应与 A 相同

# 4. 编排器状态可读
node tools/module-progress/lane.mjs status | head -10    # 不报错即可（B 上没有车道是正常的）

# 5. 远端可读写
git fetch upstream --prune && echo fetch-ok
node tools/module-progress/remote-worker.mjs poll        # ok:true

# 6. 蓝图卷→服务映射能解析（B 上没车道时 serviceGate 也必须能跑）
node -e "
const {execSync}=require('child_process');
" ; node tools/module-progress/lane.mjs plan --dry-run --lanes 1 | head -20
# ⚠️ **只加 --dry-run**。没有 --dry-run 的 plan 会真的建分支和 worktree。
```

> ⚠️ **第 6 条的 `--dry-run` 一定要加**。`lane.mjs plan` 有副作用：
> 不加 `--dry-run` 会建分支、建 worktree、装依赖、**占掉一个 Flyway 版本号**。
> 这不是 Mac B 该做的事（版本号只能 Mac A 分配）。
>
> ⚠️ **更隐蔽的坑：不要用 `--help` 去问 plan 支持什么参数。**
> `lane.mjs` 没有 `--help` 分支，`--help` 会被参数过滤器丢掉，
> 于是命令落到默认分支、`--lanes` 取不到值退回 1 ——
> **真的执行了一次 `plan --lanes 1`**：建了车道、占了版本号、建了 worktree。
> 它还会打印 `ok: true` 和 `newlyPlanned`，看起来完全像一次成功的 dry-run。
> 要查参数就 `grep -nE "flag\(" tools/module-progress/lane.mjs` 读源码。

---

## 第 9 步：Mac A 侧的配套改动（P2 阶段才做）

Mac B 配好后，**Mac A 才开始签发票据**。两处改动：

### 9.1 Mac A 的 cron 任务书加三条

在现有 cron（`fcf14c15-6744-4ec2-b733-fc31d5babf44`）的任务书里：

1. 阅读清单加 `tools/module-progress/PIPELINE-MULTI.md`
2. 第 4 步「排车道并派发」之后，加**签发票据**：

```bash
# 本轮 plan 出来的车道，按序号奇偶分给两台机器：
#   奇数 → 本机派 task；偶数 → 签发票据交给 Mac B
node tools/module-progress/remote-lane.mjs issue --lane <模块ID> --brief <任务书文件>
```

3. 第 3 步「回收已落地的车道」**之前**，加**收割远端车道**：

```bash
node tools/module-progress/remote-lane.mjs harvest
```

它会把 Mac B 交付完成的车道置为 `ready`，随后由既有的 `merge-ready` 统一并入。

### 9.2 Mac A 的 cron 任务书正文（替换用）

```markdown
你负责 `/Users/tommy/Project/starcon` 的并行车道开发调度。
**你是 Mac A（调度机）—— 唯一写集成头、唯一分配 Flyway 版本号、唯一改台账的机器。**

每次运行先完整阅读：
1. 仓库根 `AGENTS.md`
2. `tools/module-progress/PIPELINE_CRON_PROMPT.md`
3. `tools/module-progress/PIPELINE.md`
4. `tools/module-progress/PIPELINE-MULTI.md`（双机协议：你在其中的角色）

仓库文件是唯一执行协议。本字段不重复检查命令、任务模板、进度数字或吞吐估算。
运行参数从 `.dev-agent/PIPELINE.local.md` 读取；配置缺失或不一致时停止新增派发并报告差异。

先做低成本只读检查：节流与静默窗口、在途任务、资源与温度、失败检查、模型额度。
没有通过闸门就结束，不启动测试或容器，不刷新批次时间戳。

优先修复失败和完成在途任务，再派发新任务。
**本轮 plan 出的车道按序号奇偶分配执行机：奇数本机执行，偶数签发票据交给 Mac B。**
签发票据时：
  node tools/module-progress/remote-lane.mjs issue --lane <模块ID> --brief <任务书文件>
**不要给 Mac B 的车道派本机 task**（那会双写）。

回收阶段先收割远端车道：
  node tools/module-progress/remote-lane.mjs harvest
```

> **Mac A 只用 `remote-lane.mjs`**（签发 / 收割 / 收编）。
> `remote-worker.mjs` 是 **Mac B** 的脚本，Mac A 不要碰。

---

## 附录 A：Mac B 上**不需要**的东西

| 不需要 | 原因 |
| --- | --- |
| `lane.mjs init` 之外的编排器配置 | B 不排车道、不合并 |
| Flyway 版本号分配权 | 只能 Mac A 分配，否则撞号 |
| 调度闸的持有权 | 闸只防 A 自己的并发；B 用它防**自己**的 tick 重叠 |
| 台账的写权限 | 台账是版本化文件，只能一个写者 |
| L2 全量验证 | 在 Mac A 的集成头上跑唯一一次 |
| `lock.mjs` 开发锁 | 那是老调度器的遗留机制 |

## 附录 B：故障速查

| 现象 | 原因 | 处置 |
| --- | --- | --- |
| `poll` 报 `fetch upstream 失败` | 远端名不是 `upstream`，或没凭据 | `git remote -v`；改名或设 `LANE_REMOTE` |
| `poll` 永远返回空 | Mac A 还没签发票据（正常） | 等 A 的 P2 阶段；或查 A 的 `remote-lane.mjs status` |
| `claim` 报「worktree 已存在」 | 上次没收干净 | `git worktree list` 看残留，手动 `git worktree remove --force` |
| `claim` 报「与远端分叉」 | 本机有分叉提交 | 人工核对；**不要强推** |
| `finish` 报「有未预期的改动」 | worker 留了未提交改动 | 让 worker 先 commit；票据文件会被自动删 |
| A 侧 `harvest` 一直跳过某条车道 | 票据还在（worker 没交付）或心跳超 90 分钟 | `remote-lane.mjs status` 看 `ticketAlive` 与 `heartbeat` |
| 页面 404 且 typecheck/lint/vitest 全绿 | **`api-proxy.ts` 白名单被取错一侧** | 协议第 9 条：必须求并，跑真 proxy 断言 HTTP 状态码 |
| 合并时 Flyway 报「Found more than one migration with version N」 | 版本号撞了 | **不该发生**（I2 保证）。若发生说明有第二个分配者，立刻查是不是有人手工 `plan` 过 |
