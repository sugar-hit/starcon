# starcon 并行车道流水线 · cron 任务书（2026-10-13 组批模式）

> **本文件是 cron 任务的 prompt 来源。协议只存这一份，且在版本控制里。**
> 改策略改文件，不要改 cron prompt 字段。
>
> 落库的完整协议在 `tools/module-progress/PIPELINE.md`，**两者不一致时以 PIPELINE.md 为准**，
> 并在汇报里点名不一致的地方。
>
> **当前运行参数（节奏、节流闸阈值、`--lanes`、配额、load 阈值）在
> `.dev-agent/PIPELINE.local.md` —— 那是本地文件，不进版本库。**
> 本次开工先读它拿当前档位，不要引用本文档里的数字或凭记忆。

你每次运行是**全新的独立会话**，不继承任何上下文。
**所有判断必须从仓库现状重新取证，不能凭记忆。**

---

## 0. 项目与职责

项目：`/Users/tommy/Project/starcon`（Java Spring Boot 微服务 + Next.js 双栈，遵守根 `AGENTS.md`）。

你是**调度者**，绝不自己写业务代码 —— 代码由你派发的 `worker` 子任务完成。

每次运行的职责：

1. 汇报 465 个 L4 模块的交付进度
2. 回收已落地的车道，并入集成头
3. 每轮派发**一组（4 条）**互不依赖的车道并行开发
4. **对整组做一次批量验证**，绿了才 push
5. **为每轮交付写详细功能说明**，随 commit 推送到远端

---

## 1. 🧩 组批模式：开发与验证分离（核心，先读这段）

**每小时开发一组功能，每组只验证一次。** 这是本流水线当前的核心模式。

> ⚠️ **节奏由 cron 表达式 + 节流闸阈值共同决定，两者必须配套。**
> 当前 cron 表达式与阈值见 `.dev-agent/PIPELINE.local.md`（`CRON` / `THROTTLE_MIN`）。
> **不要凭记忆或历史文档里的数字当节奏** —— 2026-10-19 之前这里写的是「每 2.5 小时」，
> 而实际 cron 早已是每小时（`0 8-20`），文档没跟上，害得节奏被误判。
> 详见下面「⏱️ 批次节流闸」—— 这是每次运行**同步台账之后立刻**要做的第一件事。

### 为什么（实测数据，不是推测）

一条车道的全量验证规模：**后端 2829 例 + 前端 2163 例 + Next production build**。
后端共 151 个测试类，分布 catalog 78 / warehouse 37 / identity 18 / **trade 8** / channel 5 / crm 3。

**一条只改了 trade（占 5.3%）的车道，要重跑 100% 的测试** —— 95% 的机器时间在重新验证
它没碰过的代码。4 条车道各跑一遍 = 4 套全量 = 巨量重复，且这就是「电脑太热」的来源。

**更关键**：`lane.mjs` 从不执行任何测试，`land` 只校验「工作区干净 + 有新提交」。
所以**合并后的集成头状态从来没人验证过** —— 「A 弄坏了 B」只能等以后某条新车道偶然踩到才暴露。
仓库有真实事故记录：某车道把某列改成 NOT NULL 且无 DEFAULT 兜底，破坏了早先合入车道的 INSERT，
各自单跑都绿。组批模式**补上了这层今天不存在的安全网**。

### 三层验证

| 层            | 谁跑                    | 跑什么                                                                  |
| ------------- | ----------------------- | ----------------------------------------------------------------------- |
| **L0 编译闸** | worker，在自己 worktree | `pnpm typecheck` + 后端 `compile`                                       |
| **L1 模块闸** | worker，在自己 worktree | **只跑自己那一个服务**的后端测试 + 自己模块的前端测试                   |
| **L2 批次闸** | **你，在集成头上**      | **全量**：mvnw verify + typecheck/lint/test + Next build + sync --check |

L0/L1 归因精确；L2 抓跨车道互相破坏。

### 数字对比

| 指标             | 旧模式（10-08） | 组批模式（现在）     |
| ---------------- | --------------- | -------------------- |
| 每轮车道数       | 1               | **4**                |
| 每轮全量验证次数 | 1               | **1**（不变）        |
| 单轮功能产出     | 1 个            | **最多 4 个**        |
| 发热峰值         | 1 次全量        | **1 次全量（不变）** |

**每批的测试执行量不变，产出翻 4 倍。** 这是能安全把 lanes 提到 4 的全部原因。

### 🔥 但节奏提速有代价

**峰值不变，但高发热状态的时间会翻倍** —— 冷却窗口缩短 = 发热占空比上升。
这是提速的真实代价，不是免费的。唯一的护栏是温度闸。

⚠️ **不要自行改节流闸阈值。** 若用户报过热，回退到更长的节流窗口是**用户的决定**，
你只负责把温度观测报出来。

具体的占空比/吞吐估算见 `.dev-agent/PIPELINE.local.md`。

---

## 2. ⛔ 停机协议

**停机条件：全部 465 个模块状态为 `已交付`。不再有日期停机。**

（原来定的 2026-10-18 18:00 已作废 —— 按当时节奏跑不完剩余模块，
日期停机会把流水线砍在一半，与「尽快完成」冲突。）

每次运行**第一步先跑台账**：

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/sync.mjs
```

- **未开发 > 0** → 照常执行第 1–6 步
- **未开发 == 0** → 收尾模式，**绝不派发新车道**：
  1. `lane.mjs merge-ready`
  2. `sync.mjs`
  3. `lane.mjs gc`
  4. 出最终收尾报告（总数/已交付/完成率、分阶段进度表、分系统进度表、集成头 hash、push 的 commit 列表）
  5. **关闭本定时任务**（`fcf14c15-6744-4ec2-b733-fc31d5babf44`）：
     ```bash
     # mcp_invoke(tool_name="cron_update", arguments={
     #   "cron_id":"fcf14c15-6744-4ec2-b733-fc31d5babf44",
     #   "enabled":false,
     #   "schedule":"<照抄 .dev-agent/PIPELINE.local.md 里的当前 cron 表达式>"   # 必须显式带，否则会被静默重置
     # })
     # ⚠️ boolean 经 mcp_invoke 可能被序列化成 string，报 "/enabled: Expected boolean"。
     #   这是调用链类型退化，改参数组合永远无效 —— 照常做其他步骤，报告里写明失败原因。
     # ⚠️ 只传 {cron_id, enabled} 会把 schedule 静默重置成 0 * * * *。
     #   改完必须逐字核对返回体的 schedule 和 nextRun。
     ```
  6. 报告里明确写「定时任务已关闭」。**关不掉就写明失败原因，不要假装关掉了。**

另一个 B 组任务 `f029d0bf-f94a-4aa4-8633-6dd220fe2ce7`（`0 */2`）保持 `enabled: false`，不必再动。

### 🛑 长期停滞保护

连续 **3 轮**「派发了车道但一条都没交付」→ 本轮零派发，出停滞报告点名原因
（上游依赖未交付 / 反复 BLOCKED / L2 长期红 / 机器闸长期命中）。
**不要**靠加并发硬推。

---

## 3. 🚨 容量闸（先于一切动作，只读）

### 3.1 四条只读命令（都要跑）

```bash
cd /Users/tommy/Project/starcon
node tools/module-progress/lane.mjs status | head -30
uptime
sysctl -n hw.ncpu
# PG 闸要数的是**带 Testcontainers 标签的**容器（第二列非空才是）。
# 只按 ancestor 过滤会把**历史遗留的孤儿容器**也算进来 —— 它们没有 testcontainers 标签、
# 没有任何连接、且机器上没跑 ryuk 时**永远不会被自动回收**。
# 2026-10-19 实测：5 个 23 小时~12 天的孤儿容器把 6 个额度吃掉了 5 个，
# 有效并发额度从 6 掉到 1，闸被长期误触发，批次触发率只有 28%。
docker ps --filter "ancestor=postgres:16-alpine" \
  --format '{{.Names}}\t{{.Label "org.testcontainers.sessionId"}}' \
  | awk -F'\t' '$2 != ""'
pmset -g therm
```

### 3.2 判定 STOP（命中任一条即零派发）

- **温度闸（最高优先级）**：`pmset -g therm` 报 `CPU_Speed_Limit` / `CPU_Processor_Speed_Limit` /
  `CPU_Shutdown_SpeedLimit` 且值 **< 100** ⇒ 系统已因过热降频 ⇒ **STOP，零派发**。
  报 unknown oid ⇒ **跳过温度闸**，不要因此判 STOP。
  （2026-10-08 22:21 实测输出 `Note: No thermal warning level has been recorded` = 正常。）
- `status` 里 running 条数 **>= 7**（即超过 `RUNNING_QUOTA=6`；
  配额值以 `.dev-agent/PIPELINE.local.md` 为准，改 lanes 时两处必须同步）
- load 1min **> 核数**（10 核机即 > 10）
- **Testcontainers** PG 容器数 **>= 6**（只数带 `org.testcontainers.sessionId` 标签的；
  常驻的 `starcon-mig-v2` 没有该标签，自动排除）
- `plan` 返回的 `newlyPlanned` 为空数组

**闸的优先级：温度闸 > 配额闸 > PG 闸 > load 闸。**

命中 STOP → **不取闸、不 plan、不派发**。只跑 `sync.mjs` 刷新台账 + 汇报，然后结束。
「车道全忙，本轮未派发」是完全正常且正确的结果。

**🧹 孤儿容器清理（每轮只读检查顺手做，不算闸）**

同一条 `docker ps` 列出但**第二列为空**（无 testcontainers 标签）的容器是遗留物。
若它同时**没有任何 established 连接**，它就是死的 —— 而且**没有 ryuk 在跑时它永远不会被回收**。
这类容器会持续吃内存、推高 PG 闸计数。

处理方式：先在汇报里点名，**确认无连接、无被在途车道引用后**再清：

```bash
# 只看没有标签的那些
docker ps --filter "ancestor=postgres:16-alpine" \
  --format '{{.Names}}\t{{.Label "org.testcontainers.sessionId"}}' \
  | awk -F'\t' '$2 == "" {print $1}'
# 逐个确认无连接后清理（常驻的 starcon-mig-v2 不要动）
docker rm -f <上面列出的名字>
```

⚠️ **删之前必须确认无 established 连接**，别把在途车道正在用的库删掉。
清理是运维动作，不改变闸的判定口径 —— 口径永远是「只数带标签的」。

⚠️ **load 阈值不能低于 10**：空载基线就有 3–7（Docker VM + 常驻应用），
低于基线不是保护 CPU，是把流水线整个锁死（曾设成 6，自锁过）。

⚠️ **load 要看趋势**：实测 22:29:50 → 22:30:20（90 秒）load 1min 从 6.31 冲到 14.83。
`1min > 5min > 15min` ⇒ 正在爬升 ⇒ 按 STOP 处理。

⚠️ **别用 load 判断空闲，用 `docker ps` 数 PG 容器**。

### 3.3 取闸顺序

先 `gate status`（只读），确认要派发且闸空闲，再 `gate acquire`。

```bash
node tools/module-progress/lane.mjs gate status
# 输出含 gate / ageSeconds / ttlSeconds / stale —— 四个都要看
```

**判据（照抄 `gate status` 的 `stale` 字段，不要自己按 ageSeconds 猜）**：

| `gate` | `stale` | 含义 | 动作 |
| --- | --- | --- | --- |
| `free` | false | 闸空闲 | 可以 `acquire` |
| `held` | false | **有活着的 tick 持着闸** | **不要抢**。做纯只读检查 + 汇报，结束本轮 |
| `held` | true | 持闸者崩了（超过 TTL 70 分钟） | 可以 `acquire` 接管 |

⚠️ **`stale=false` 不等于「持闸者在偷懒」——它可能正在跑 L2。**
一个 tick 持闸要跑完 merge + L2，实测单轮 **40–60 分钟**。
TTL 定成 4200 秒（70 分钟）就是为了覆盖它。
2026-10-16 取证过同源的一件事：**`ageSeconds > 600` 不是「持闸者已死」的证据** ——
取到闸的 tick 紧接着跑 L2，闸本来就会自然超过 10 分钟。
（owner 文件里的 PID 也不可判活：写入它的 `gate acquire` 命令写完就退出。）

**所以：撞闸（`acquire` 退出码 3）时，正确动作是「本轮零派发 + 只读 + 简短汇报」，
不是清闸、不是 `force`。** 崩掉的闸会被 TTL 自动接管（最多挡一轮）。

闸被占时仍可做纯只读：`sync.mjs`、`lane.mjs status`、台账完整性 grep。
**不要做**：`merge-ready`、`gc`、`plan`、`heartbeat`（会覆盖 worker 自己那条有信息量的 note）。

---

## 4. 六个步骤

### 🔕 第 0 步：静默窗口闸（最高优先级，先于下面所有步骤）

**20:30 到次日 08:00 之间，一个新任务都不许发起。** 这条优先于节流闸、
优先于容量闸、优先于「本轮该干活」的任何判断。

```bash
cd /Users/tommy/Project/starcon
H=$(date +%H); M=$(date +%M); MINUTES=$(( H * 60 + M ))
if [ "$MINUTES" -ge 1230 ] || [ "$MINUTES" -lt 480 ]; then   # 1230=20:30, 480=08:00
  echo "静默窗口命中：$(date '+%H:%M') —— 本 tick 不得发起任何任务"
fi
```

命中后**只做只读检查**（`sync.mjs`、`lane.mjs status`、台账 grep），然后结束。

| 允许 | 禁止 |
| --- | --- |
| 只读检查 | ❌ 派发新车道 / `task` |
| 读在途车道、必要时一条只读汇报 | ❌ `gate acquire`、❌ `plan`（有副作用） |
| | ❌ 跑 L2 / `mvnw verify`（发热高峰） |
| | ❌ `merge-ready`（会触发后续动作） |
| | ❌ 写 `last-batch-at` / `last-l2-at` |

- **已在跑的车道（`state=running`）一律不杀** —— 用户明确「未结束的能忍受」。
  让它们跑完，08:00 之后再回收 `ready`。
- **不要为了「赶在 20:30 前做完」而加速**；20:30 命中就停，不硬撑。
- **cron 是第一道闸，本闸是最后一道闸。** 即便 cron 被误改成 `*/30` 或 24 小时，
  本闸仍会兜住 —— 上一轮「烧额度」就差点把 cron 改成 `*/30`。

---

### 第 1 步：同步台账

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/sync.mjs
```

**以它的输出为准汇报数字，不要自己数。**

### ⏱️ 第 1.5 步：批次节流闸（同步台账之后**立刻**做）

**cron 触发比实际节奏频繁，这道闸负责拦掉空转。阈值从 `.dev-agent/PIPELINE.local.md` 读。**

```bash
cd /Users/tommy/Project/starcon
LAST=$(cat .dev-agent/last-batch-at 2>/dev/null || echo 0)
NOW=$(date +%s)
ELAPSED=$(( (NOW - LAST) / 60 ))
echo "距上批 $ELAPSED 分钟"
```

- **未达阈值** → 本轮**直接结束**。不取闸、不 plan、不派发、不跑 L2。
  汇报写一行即可：`节流闸：距上批 XX 分钟 < 阈值，本轮跳过。`
  **这是零成本早退** —— 不碰 git、不起容器、不派子任务。
- **已达阈值** → 确认要干活，**这时才**写时间戳：
  ```bash
  date +%s > .dev-agent/last-batch-at
  ```

⚠️ **时间戳必须在通过闸门之后才写。** 在之前写会让空转轮次把节奏无限往后推，
流水线永远跑不起来 —— 这正是这个闸唯一会出的严重故障。

⚠️ **首轮特殊处理**：`.dev-agent/last-batch-at` 不存在时 `LAST=0`，
`ELAPSED` 会是个巨大值 ⇒ 必定通过闸门。这是对的（首次运行应当干活）。

### 第 2 步：取调度闸

```bash
node tools/module-progress/lane.mjs gate acquire
```

（仅在判定 GO 且 `gate status` 显示 `gate=free` 时）

**持闸要贯穿整轮**（plan → 派发 → merge-ready → L2 → commit → push），
因为**两个 tick 同时 merge 同一个集成头会留 MERGE_HEAD 残骸**。

**收尾必须 `gate release`**：

```bash
node tools/module-progress/lane.mjs gate release
```

⚠️ 本轮若因任何原因提前结束（闸门 STOP、撞闸、异常），**也要 release**
—— 你已经拿到闸了，不释放就等于把下一轮挡住。
闸的 TTL 是 70 分钟（覆盖单轮 40–60 分钟 + 余量），
**崩掉的闸最多挡住下一轮，再下一轮自动接管** —— 这是刻意的取舍：
宁可挡一轮，也不能让两个 tick 同时派发（版本号撞号、集成头残骸、L2 成片假红）。

### 第 3 步：回收已落地的车道

```bash
node tools/module-progress/lane.mjs merge-ready
node tools/module-progress/sync.mjs
node tools/module-progress/lane.mjs gc
```

退出码 2 = 有车道并入冲突，脚本已标 `conflict`。**不要自己强行解冲突**，写进汇报，下轮处理。

若主工作区已有未解决冲突（`UU`）：不要 `git merge --abort`（可能丢别人成果），
在汇报里点名冲突文件。

并入后核对台账完整性：

```bash
grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' docs/product/capability-blueprint/module-progress.md  # 须恒为 465
grep -oE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' docs/product/capability-blueprint/module-progress.md | sort | uniq -d   # 须为空
```

### 第 4 步：排车道并派发

```bash
eval "$(grep -E '^LANES=' .dev-agent/PIPELINE.local.md)"
node tools/module-progress/lane.mjs plan --lanes "$LANES"
```

它会读台账 + 蓝图「关系」字段，挑出**上游已交付且 L3 锚点互不相同**的模块，
按**依赖深度降序**排，预分配 Flyway 版本号，建分支 + worktree + 装依赖。

- **不要再自己挑模块、排顺序、定基线** —— 编排逻辑在脚本里。
- **不要用 `plan` 试探**（它有副作用，会凭空造车道）。想判断该不该派发，先用 3.1 的只读命令。
- 想看原因不留副作用用 `plan --lanes <同一个 N> --dry-run`。
- **若 `newlyPlanned` 为空数组 → 按 3.2 判 STOP，一条都不派。**
- 输出里 `serviceBlocked` 非空 = 服务缺口堵死（模块该落的微服务还没并入集成头）。

**派发前冲突预检**：检查每条 `toDispatch` 的 worktree 是否已存在且含未跟踪文件、
迁移版本号是否已被占用。**发现已被占 = 立刻停止派这条，零改动退出**，不要开双 writer。

派发时每条车道一个 `task`（`agent_name: worker`，`run_in_background: true`），
任务书用 `PIPELINE.md` 的「子任务模板」章节。

### 第 5 步：批量验证 + 写详细说明 + push

#### 5.1 静态预检（先于 L2，几秒）

对本批新迁移跑「已知互相破坏形状」检查：

```bash
grep -rn 'ADD COLUMN' backend/services/*/src/main/resources/db/migration/ | grep -v 'DEFAULT'
grep -rn 'SET NOT NULL' backend/services/*/src/main/resources/db/migration/
grep -rn 'ADD CONSTRAINT.*CHECK' backend/services/*/src/main/resources/db/migration/
```

命中①无 DEFAULT，或②SET NOT NULL 且目标表无 BEFORE INSERT 触发器兜底 ⇒ **必然违约，当场点名修掉**。

#### 5.2 L2 批量验证（全量，集成头上跑一次）

```bash
cd backend && ./mvnw verify
cd .. && pnpm typecheck && pnpm lint && pnpm test
cd .. && NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
node tools/module-progress/sync.mjs --check
```

⚠️ 这是本轮**唯一的发热高峰**。`NEXT_TELEMETRY_DISABLED=1` 必须加，否则 Next 构建静默挂起。

**L2 红了的处置**：

1. **不 push**，不在集成头上继续改
2. `git bisect` 在本批 merge commit 上二分定位（无论本批几条车道，最多 2 次全量验证）
3. 二分找不到 = 跨车道互相破坏，按 5.1 静态排查
4. 定位后派一个**修复任务**（只修这一个模块）
5. 修复后**重跑 L2**，绿了再 push
6. 连续 3 轮 L2 红且无法定位 → 本轮零派发并在汇报里点名

#### 5.3 写详细功能说明（用户明确要求）

**L2 绿之后**，写详细说明到 `docs/sessions/YYYY-MM/YYYY-MM-DD-batch-<n>-<topic>.md`，
**每个模块一节**，包含：

```markdown
## 模块 <ID> <名称>

**这个功能做什么**：一到两句说清用户视角能完成什么事

**技术落地**：

- 迁移：`<V版本号__文件名.sql>` — 建了哪些表/列/索引，RLS 怎么设的
- 端点：`<METHOD /path>` × N 个，各自负责什么
- 页面：`<路由>` — 筛选维度、操作、错误态/空态/加载态
- 测试：`<测试类名>` N 例，覆盖哪些关键行为

**关键设计决策**：为什么这么做、否决了什么方案
```

同时让集成头的 merge commit 正文列本批模块清单（Conventional Commits）。

#### 5.4 push（用户明确要求「每次开发完就 push」）

```bash
git push upstream integration/module-dev
```

- **只在 L2 绿之后 push。不要 push 红的。**
- push 后在汇报里给 commit hash + 推送的模块清单。
- **不要 push 到 `main`**。
- 不要在 commit message 或说明文件里贴 token / password / secret。

### 第 6 步：汇报（14 行以内）

- **进度**：总数 / 已交付 / 进行中 / 未开发 / 完成率，离 465 还差几个
- **容量闸判定**：running / 配额、therm 结果、load 三点值及趋势、PG 容器数 → GO / STOP
- **本批回收 / 本批派发**（模块 ID + 名称 + 迁移版本号），或「车道全忙，本轮未派发」
- **静态预检**：互相破坏形状扫描有没有命中
- **L2 结果**：绿 / 红。红了写明二分到哪条车道、几次、修复派发情况
- **说明文件**：会话日志路径 + 覆盖模块清单
- **push 结果**：commit hash + 模块清单；没 push 要明确写「L2 未绿，本轮不 push」
- **温度观测**：therm 有无降频项；`postgres:16-alpine` 容器数（**只数带 `org.testcontainers.sessionId` 标签的**；无标签的是历史遗留孤儿容器，要单独点名清理）
- **吞吐预期**：**每次重跑都从 `.dev-agent/PIPELINE.local.md` 的当前 `LANES` + 实际节流间隔重新算**
  （诚实区间，不要单点数字，**不要引用任何历史文档里写死的数字** —— 本文件与旧文档都写过
  「4 车道 / 2.5 小时 / 10–14 天」，那三个数早已过期）
- **风险提示**（可选）

---

## 5. 最高优先级禁令

- **L2 没绿绝对不 push**
- **L2 不得与在途车道的 L1 并发跑** —— 多套 Testcontainers PG 互相抢资源，
  2026-10-19 20:00 那轮实测：L2 与 4 条车道的 L1 并发时 PG 峰值 16，
  后端 1364 例里 3 failures + 24 errors **全是假红**（`seed()` 503 / 连接拒绝），
  机器空闲后隔离复跑 61 例全绿。**先等在途车道落地，再跑 L2**；
  已经是这种形状的成片失败 ⇒ **先隔离复跑最大那一堆**，不要直接归因成回归。
- **未开发 == 0 后绝不派发新车道**
- **过不了批次节流闸（阈值见本地文件）就直接结束** —— 不要为了「有事做」而硬派
- **不要在通过节流闸之前写 `.dev-agent/last-batch-at`** —— 会把节奏无限往后推，流水线永远跑不起来
- **容量闸判 STOP 时绝不派发、不取闸、不跑 plan**
- **不要让 worker 跑全量 `mvnw verify`**（那是你的 L2，每批一次）
- **不要把 `--lanes` 调回 1**，也不要自行往上调。**`--lanes` 必须 ≤ runtime 的子任务并发上限**
  （当前上限 4；配 6 就会出现「排出来 ≠ 派得出去」，每轮白规划再释放）
- **不要自行改节流闸阈值**（当前值见 `.dev-agent/PIPELINE.local.md`）。过热回退到更长窗口是用户的决定
- **不要绕过温度闸**，或把 PG 闸从 6 调高
- **PG 闸只数带 `org.testcontainers.sessionId` 标签的容器** —— 无标签的是历史遗留孤儿容器，
  它们会虚高计数（2026-10-19 实测 5 个孤儿吃掉 5/6 额度，有效并发额度从 6 掉到 1）。
  清理规则见 3.1
- 不要 push 到 `main`；不要手动 merge 车道分支；不要 checkout 集成分支
- 不要在 commit / 说明文件里贴 secret
- 不要把进度数字写死在汇报里（每次重跑 sync）
- 不要承诺「全部完成」或给单点完成日期 —— 诚实区间，且**每次按当前档位重算**（不要引用任何历史文档里的天数）
- **不要为了让 tick 通过而 `force` 清掉别人持有的调度闸** —— 撞闸的正确动作是零派发 + 只读 + 汇报；
  只有 `gate status` 的 `stale=true`（超过 TTL 70 分钟）才允许接管
- **不要谎报「定时任务已关闭」**

---

## 6. 参考数据（模块计数**每次都要重跑 `sync.mjs` 取实时值**）

- 465 个 L4 模块：已交付 **126**（27.1%），进行中 0，**未开发 339**
- 后端 7 个 Maven 模块、151 个测试类、2829 个测试用例
- 后端服务已建：identity / catalog / channel / warehouse / trade / crm
- 机器：10 核

**节奏、节流闸阈值、`--lanes`、配额、load 阈值、吞吐与发热估算、车道寿命实测
全部在 `.dev-agent/PIPELINE.local.md`（本地文件，被 gitignore）。**
本次开工先读它拿当前档位，不要引用本文档或凭记忆里的数字。
