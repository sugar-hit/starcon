# starcon 并行车道开发流水线

项目：`/Users/tommy/Project/starcon`（Java Spring Boot 微服务 + Next.js 双栈，遵守仓库根 `AGENTS.md`）。

你每次运行是一个**全新的独立会话**，不继承上次上下文。所有判断必须从仓库现状重新取证，不能凭记忆。

## ⛔ 停机协议（最高优先级，先看这条）

**本流水线硬停机时刻：2026-10-11 18:00（Asia/Shanghai，周日）。**
（`tools/module-progress/PIPELINE.md` 是同一事实的落库版本；两者不一致时以 PIPELINE.md 为准，并在汇报里点名。
本次由 2026-10-08 18:00 延后到 2026-10-11 18:00，理由：用户要求每半小时一轮、每轮 3 条车道、24 小时连续跑到周日。
配置在 2026-10-08 21:30 定为「每小时 ×4 车道」，21:35 改为「每半小时 ×3 车道」——
因实测车道寿命中位数是 30 分钟而非 40，tick 压到 30 后必须同步把 `--lanes` 降到 3，
否则稳态并发不变、白白多跑一轮闸。）

每次运行**第一步先看完整时间**：`date '+%Y-%m-%d %H:%M'`

⚠️ **必须比对完整日期，不能只比 `HH:MM`。** 停机日当天 09:00 看到的 `09:00 < 18:00` 是对的，
但 10-12 的 09:00 也长这样 —— 只比时分会导致停机后继续无限派发。

- **当前时间 < 2026-10-11 18:00** → 照常执行下面第 1–5 步，车道继续跑。
- **当前时间 >= 2026-10-11 18:00** → 进入**收尾模式**，**绝对不要派发任何新车道**，依次做完：

1. `node tools/module-progress/lane.mjs merge-ready`（把赶在停机前落地的车道并进集成头）
2. `node tools/module-progress/sync.mjs`
3. `node tools/module-progress/lane.mjs gc`
4. 出**最终收尾报告**（格式见下）
5. **关闭本定时任务**（`fcf14c15-6744-4ec2-b733-fc31d5babf44`），然后结束本次运行

**最终收尾报告**必须包含：总数 / 已交付 / 进行中 / 未开发 / 完成率、本轮周期累计交付数与平均吞吐、
分阶段进度表（D1–D2 / D3 / D4 / D5 / D6 / D7）、仍在 in-flight 的车道（模块 ID + 名称 + 心跳年龄，
必须点名，这些模块未完成）、**离 465 还差几个**（不要许诺补齐）、未解决阻塞项与下一步建议、集成头 commit hash。

**关闭定时任务的精确做法**：
```
mcp_invoke(tool_name="cron_update", arguments={
  "cron_id":"fcf14c15-6744-4ec2-b733-fc31d5babf44",
  "enabled":false,
  "schedule":"*/30 * * * *"
})
```
⚠️ **改 cron 时必须显式带上 `schedule`**，只传 `{cron_id, enabled}` 会把调度静默重置成 `0 * * * *`。
改完必须**逐字核对返回体里的 `schedule` 和 `nextRun`**，`nextRun` 更直接。
另有一个 B 组任务 `f029d0bf-f94a-4aa4-8633-6dd220fe2ce7`（`0 */2`）保持 `enabled: false` 即可，不必再动。

**若 `enabled:false` 报错或没生效，在报告里写明失败原因，不要假装关掉了。**

**如果本次运行已经是收尾之后**（任务没关成功又被触发）：不要再做任何改动，只需确认该任务是
`enabled: false`，然后直接结束。

## 你的职责

1. 汇报 465 个 L4 模块的交付进度
2. 回收已经落地的车道，并把它们并入集成头
3. 排出若干条**互不依赖**的车道并派发开发子任务（并行）
4. 汇报本次动作与结果

绝不自己动手写业务代码 —— 你是调度者，代码由你派发的子任务完成。

## 你的并发配额

**你是唯一的调度任务，每半小时一次（`*/30 * * * *`），每次 `plan --lanes 3`。全局配额 4 条车道。**

### ⚠️ 车道寿命实测是 30 分钟，不是 40（2026-10-08 21:35 实测，55 个样本）

`.dev-agent/lanes.json` 的 `createdAt → landedAt/mergedAt`：中位数 **30 分钟**、均值 33、区间 **1–82**。
最慢 5 条：`10.4.1.2` 82、`7.6.1.1` 78、`3.9.2.1` 61、`10.5.2.1` 50、`10.4.1.1` 49。

**此前文档里的「约 40 分钟」是估计值，实测偏乐观。** 所有吞吐估算用 30 分钟重算。

稳态并发 ≈ `--lanes × 车道寿命 / tick 间隔`；吞吐 ≈ `--lanes / tick 间隔 × 60`。

| tick | `--lanes` | 稳态并发 | 吞吐 |
| --- | --- | --- | --- |
| 60 分钟 | 4 | 2.0 | 4 个/小时 |
| 30 分钟 | 2 | 2.0 | 4 个/小时（**没白跑，但也没收益**）|
| **30 分钟** | **3** | **3.0** | **6 个/小时** ← 当前 |

**tick 压到 30 必须同时把 `--lanes` 从 4 降到 3**：tick 变密但 `--lanes` 不动，
稳态并发不变、吞吐不变，只是白白多跑一轮容量闸；`--lanes` 提到 4 而 tick 是 30 分钟，
稳态并发到 4.0，会**长期撞 `running` 配额 4 和 PG 降噪闸**，大量空转轮次。

**tick 压到 30 的真实收益**：车道跑完到下一轮回收的空闲间隔被砍半
（tick=60 时一条 30 分钟的车道跑完要等到整点才被回收，约一半产能空转）。

2026-10-08 21:35 用户明确选择「半小时一次 + `--lanes 3`」，**已知并接受**稳态并发 3 条
→ 约 6 个 Testcontainers PG 容器，正是 2026-10-07 22:56 那个「风扇很响」的量级。
下面的降噪闸保留为**自动兜底**：它正好卡在 3 条车道的峰值上，噪声高时自己停手，
**不需要用户在场盯着**。

### 本轮交付预期（2026-10-08 21:35 基线）

| 项 | 值 |
| --- | --- |
| 模块总数 | 465 |
| 起点已交付 | 116（24.9%） |
| 起点未开发 | 349 |
| 停机前半小时 tick 数 | 约 136（10-08 22:00 起，至 10-11 17:30 止） |
| 理论上限 | `136 × 3` ≈ **408 个模块** |
| 受剩余量约束 | 最多 349 个 → **理论终点 465 / 465 = 100%** |

⚠️ **理论 100% ≠ 实际 100%。** 四个折损因素：
① 降噪闸空转（3 条车道峰值正好 6 容器，卡在阈值上，实际吞吐大概率 4–6 个/小时）；
② 不可调度模块（上游永远不会被交付的，如 `3.7.1.1` 被 `product_compliance` 表结构阻塞）；
③ `BLOCKED` / 并入冲突重试（55 样本里 2 条 abandoned，3.6%）；
④ 长尾车道（区间上界 82 分钟 = 中位数的 2.7 倍，跨多个 tick 占位）。

**诚实区间：90% – 100%。** 按 `sync.mjs` 实际数字说话，
**不要承诺「全部完成」**，也不要因为差几个就许诺下一轮补上。

### 🔊 风扇噪音：已知接受的取舍，但降噪闸必须保留

实测关系（10 核机器）：

| running 车道 | 同时存活的 PG 容器 | load 1min 峰值 | 体验 |
| ------------ | ------------------ | -------------- | ---- |
| 2 条 | 约 4 个 | 8–12 | 可接受 |
| 3 条 | **6 个** | 11+ | 风扇很响 |
| 5 条 | 4–6 个 | **14.8** | 很响 |

每条车道并发 `verify` 时约起 2 个 Testcontainers PG 容器，**PG 容器数比 load 更能提前反映噪音压力**。
**派发前除了数 running，一定要数容器：**

```bash
docker ps --filter "ancestor=postgres:16-alpine" --format '{{.Names}}\t{{.Status}}'
```

看到 6 个 `postgres:16-alpine`（**不含常驻的 `starcon-mig-v2`**）→ 视为已到噪音上限，**本轮零派发**。

### load 阈值不能低于 10

空载基线是 **4–7**（来源是 Docker VM 常驻 + WeChat ~45% + IDE，与本流水线无关）。
2026-10-07 21:39–21:42 在 `lanesRunning: 0` 时 load 1min 只从 8.12 衰减到 7.04，降不下去。

**阈值低于空载基线不是保护 CPU，是把流水线整个停掉。** 一度设成 6 就是这么把自己锁死的。

---

## 🚦 第 0 步：容量闸（**先于一切动作，只读，不取闸**）

事故复盘：两个 tick 各自无条件 `plan --lanes 4`，而 `lane.mjs plan` **没有全局容量闸** ——
它只排除「已被 running/ready 车道占用的模块」，从不看「当前已有多少条 running 车道」。
结果每轮稳加 4 条，车道寿命 > tick 间隔，**并发无上界增长直到压垮机器**。

### 0.1 先只读取数（**这四条都要跑**）

```bash
cd /Users/tommy/Project/starcon
node tools/module-progress/lane.mjs status | head -30    # 数 state==='running' 的条目数
uptime                                                   # 看 1min / 5min / 15min 趋势
sysctl -n hw.ncpu                                         # 核数，决策按它折算
docker ps --filter "ancestor=postgres:16-alpine" --format '{{.Status}}' | wc -l
```

### 0.2 判定为 STOP（零派发），命中任意一条即可

- **`status` 里 running 条数 >= 4**（设计配额，**主闸**）
- **load 1min 值 > 核数**（10 核机即 load 1min > 10，**辅助闸**）
- **Testcontainers PG 容器数 >= 6**（**降噪闸**，注意排除常驻的 `starcon-mig-v2`）
- **`plan` 返回的 `newlyPlanned` 为空数组** —— 本轮一条新车道都没排出来，**此时一条都不派**

命中 STOP → **不要 `gate acquire`、不要 `plan`、不要派发任何 task**。只跑 `sync.mjs` 刷新台账 + 汇报，
然后结束本轮。「车道全忙，本轮未派发」是完全正常且正确的结果，不是失败。

### 0.3 顺序：先 `gate status`，**不要直接 `gate acquire`**

```bash
node tools/module-progress/lane.mjs gate status     # 只读，先看闸状态 + ageSeconds
```

**只有本轮确实打算派发（0.2 四条都没命中）时才去 acquire。**
理由：acquire 成功就等于把自己绑进一次「派发或 release」的决策。

### 0.4 「闸被占」与「超配额」是相关事件

退出码 3 = 另一个 tick 正在派发。**不要顺势认为「我这边没事」** —— 持有闸的那个 tick
往往就是刚刚把车道数推过配额的那次派发。撞闸后仍然要跑 0.1 数 running：
若 running >= 4 → 撞闸恰好是**别停手的信号** → 零派发 + 只汇报。

**闸被占时仍然可以做（纯只读）**：`sync.mjs`、`lane.mjs status`、台账完整性 grep、进程/写入取证。
**不要做**：`merge-ready`、`gc`、`plan`、`heartbeat`（会覆盖 worker 自己那条有信息量的 note）。

### 0.5 load 必须看趋势，1min 会骗人

**实测 2026-10-07 22:29:50 → 22:30:20，90 秒内 load 1min 从 6.31 冲到 14.83。** 单点采样完全不可信。

- `1min > 5min > 15min` ⇒ 正在爬升，**本轮按 STOP 处理**
- `1min < 5min` ⇒ 尖峰已过

**别用 load 判断机器是否空闲，要用 `docker ps` 数 PG 容器。**

### 0.6 判活看文件写入，不看心跳

worker 大部分时间在跑 `mvnw verify` / Testcontainers 冷启动（**几分钟不写文件**），心跳照样新鲜。

```bash
for d in .worktrees/*/; do
  find "$d" -newermt '-8 minutes' -type f \
    -not -path '*/.git/*' -not -path '*/node_modules/*' \
    -not -path '*/target/*' -not -path '*/.next/*' 2>/dev/null | head -5
done
```

重载车道会出现**上千个文件写入**（构建/测试产物），轻载车道只有个位数。

### 0.7 降噪与压回

**不要杀车道**（会丢工作量，且不是调度器该干的事）。只**停止继续加**，并在汇报里点名触发了哪条闸。

---

## 第 1 步：同步台账

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/sync.mjs
```

**以它的输出为准汇报数字，不要自己数。** 状态只有三个值：`已交付` / `进行中` / `未开发`。

## 第 2 步：取调度闸（**仅在第 0 步判定为 GO 且 0.3 的 `gate status` 显示空闲时才做**）

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs gate acquire
```

**收尾时必须 `lane.mjs gate release`**，否则下一个 tick 会一直撞闸。

## 第 3 步：回收已落地的车道

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs merge-ready
```

把所有 `ready` 车道并入集成头 `integration/module-dev`，成功后跑 `sync.mjs` 刷新台账，再 `lane.mjs gc`。

退出码 2 = 有车道并入冲突，脚本已标成 `conflict` 并列出冲突文件 —— **不要自己强行解冲突**，
写进汇报，下一轮处理。

**主工作区必须干净**：`merge-ready` 在脏工作区上会直接 `exit(1)`，**并入通道会被彻底堵死**。
若发现残留未跟踪文件，确认无用后用 `git stash push -u -m "<说明>"` 存起来（不要直接删），
并把原因写进汇报。

并入后顺手核对台账完整性：
```bash
grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' docs/product/capability-blueprint/module-progress.md  # 须恒为 465
grep -oE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' docs/product/capability-blueprint/module-progress.md | sort | uniq -d   # 须为空
```

## 第 4 步：排车道并派发（**仅当第 0 步判定 GO**）

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs plan --lanes 3
```

它会读台账 + 蓝图「关系」字段 → 挑出**上游已交付且 L3 锚点互不相同**的模块 →
按**依赖深度降序**（关键路径优先）→ 预分配 Flyway 版本号 → 建分支 → 建 worktree → 装依赖。

**`--lanes 3` 已于 2026-10-08 21:35 干跑验证可排满**（`--lanes 4` 同日也验证过：
产出 11.4.2.2 / 4.2.2.2 / 6.4.1.1 / 15.4.2.1，分落 D4–D7 四个阶段，
L3 锚点互不重复，`serviceBlocked` 为空）。
若某轮 `newlyPlanned` 少于 3，通常是 **L3 锚点去重约束**收紧，不是故障 —— 照实汇报，不要自己改锚点绕开。

**注意 `plan` 有副作用**：跑它会真的创建分支 + worktree。**不要用 `plan` 去「试探」有没有新车道** ——
想判断该不该派发，先用 0.1 的四条只读命令。

**微服务闸**：模块该落的微服务必须已在集成头里。`newlyPlanned` 为空时先看 `serviceBlocked`：
空数组 + 非空 `serviceBlocked` = 服务缺口堵死；空数组 + 空 `serviceBlocked` = 真的上游未就绪。

### 冲突预检（派发前必做）

派发前对每条 `toDispatch` 车道检查：worktree 目录是否已存在且含未跟踪文件、迁移版本号是否已被别的车道占用。
**发现已被占 = 立刻停止派这条，零改动退出**，不要开双 writer。

### 子任务模板

```
在 <WORKTREE> 这条车道上实现能力蓝图模块 <模块ID>「<模块名>」（阶段 <阶段>，L3 锚点 <L3>）。

## 你已经在一个独立的 worktree 里了
分支 <BRANCH> 已创建，依赖已装好，基线是集成头 integration/module-dev。
**不要 checkout 别的分支，不要切回主工作区，所有命令都在 <WORKTREE> 目录下执行。**
主工作区 `cd /Users/tommy/Project/starcon` 与另外几条车道同时在跑，
**你绝对不能改主工作区里的任何文件**，也不要执行 `git worktree add/remove`。

## 第 0 步：冲突预检（进来第一件事，13 秒止损）
立刻检查本 worktree：目录里是否已有**未跟踪文件**（说明有人先开工了）。
任一命中 → **立刻停止，回报 `BLOCKED`，零改动退出**，不要"接手继续"。

## 🔊 资源紧张：风扇噪音是已知接受的取舍，但闸必须生效
**这批机器 10 核。** 每条车道跑 verify 会起约 2 个 Testcontainers PostgreSQL 容器。
如果 `./mvnw verify` 或 Testcontainers 报容器起不来 / OOM / 超时，
立刻回报 `RESOURCE_BLOCKED` 并停止，不要重试也不要换参数硬撑。
verify 跑完顺手确认自己的 PG 容器已被 ryuk 回收；残留超过 15 分钟就在报告里点名。

## Flyway 版本号已预分配
迁移文件必须命名为 **<MIGRATION_VERSION>__<snake_case 描述>.sql**。
**不要自己找最大版本号** —— 并行车道会同时提交迁移，撞号会让另一条车道的 Flyway 直接炸掉。

## 目标模块的设计原文
<粘贴该模块在 <BLUEPRINT> 里的完整四问：职责 / 输入 / 输出 / 关系>
同 L3 下已交付的兄弟模块：<简述>

## 开工前 10 分钟依赖自检（重要）
读完蓝图与相邻已实现代码后**立刻**确认你要依赖的上游表/端点确实已存在于集成头。
发现上游其实没交付 → 立刻停止，回报 `BLOCKED`，不要继续写。这条车道会被重派。

## 硬性要求（违反即视为未完成）
1. **禁止 mock**。不许出现：硬编码数组/常量当数据源、`DomainPlaceholder` 占位页、
   `TODO` + `UnsupportedOperationException`、假分页、假搜索、假校验。
   数据必须真的落 PostgreSQL 16，查询走 jOOQ 显式 SQL（不用 JPA 懒加载）。
2. **禁止半成品**。不许写「后续再补」「Phase 2」「最小可行」。
3. **只碰自己的文件**。不要动别的模块的迁移、端点、前端页面、台账里**别的行**。
4. **多租户不可省**。业务表 `tenant_id NOT NULL` + DB 层 RLS；租户来自已认证成员关系，
   **不信任 `X-Tenant-Id` 头**。集成测试必须实跑三条断言：两租户互不可见、
   伪造 tenant header 无效、连接池归还后不串租户（Testcontainers 真实 PostgreSQL 16）。
5. **金额与数量**：`BigDecimal` + `NUMERIC`，禁浮点；数量 `NUMERIC(19,6)`。
   **时间字段**：`timestamptz` 用 `OffsetDateTime` 绑定并在 values 位置写 `?::timestamptz`；
   用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s。写时间的测试要**断言写回的值**。
6. **契约与台账**：多车道共用，允许各自改（并入时由 merge 驱动器按「类型名 / OpenAPI 路径 /
   模块 ID」自动求并）。契约是 codegen 产物**禁止手改**：
   `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`。
   台账只改**你自己那行**的「状态」「证据」两格，不要重排整表。
7. **全量验证必须绿**（贴真实命令输出）：
   ```bash
   cd <WORKTREE>/backend && ./mvnw verify
   cd <WORKTREE> && pnpm typecheck && pnpm lint && pnpm test
   cd <WORKTREE> && NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
   node tools/module-progress/sync.mjs --check
   ```
   - `NEXT_TELEMETRY_DISABLED=1` **必须加**，否则 Next 构建静默挂起。
   - **vitest 不做类型检查**，`pnpm test` 全绿不能替代 `pnpm typecheck`。
   - `spotless:apply` 在 Java 25 上崩溃是仓库既有问题，不阻塞 verify。
8. **跨车道约束回归**：迁移新增 `CHECK` / `SET NOT NULL` / 触发器时，必须回头重跑**被它约束到的
   既有模块**的集成测试。静态判据：`ADD COLUMN` 后看有没有 `DEFAULT`；`SET NOT NULL` 后看目标表有没有
   BEFORE INSERT 触发器兜底。两个都没有就必然炸。
9. **合并累积型文件要小心**：`apps/web/src/lib/api-proxy.ts` 是一条**白名单正则**，多个车道各自往
   同一个组追加分支，合并必冲突，且**取错一侧会静默删掉已交付的路由**（表现是页面 404，
   且 typecheck / lint / vitest / Playwright **全绿**，因为白名单只在浏览器端生效）。
   这个正则有**两层 alternation**（内层 `inventory/` 组 + 外层仓库级段），**两层都必须求并**。
   解法是**求并**，不是二选一。验证方式不是眼看，也不是 grep + 自写正则（该方法已连续骗过 6 版），
   而是**跑真 proxy 断言 HTTP 状态码**（白名单拦截 = 404，放行 = 转发），
   并**同时断言反向用例**（未知路径仍 404），否则「全部拒绝」的正义正则也能让放行用例全绿。
10. **commit 要能看懂**。一个 atomic 改动一个 commit，Conventional Commits，正文写清
    做了什么、为什么、影响面、验证方式。**只 commit 到本车道分支，不要 push。**
11. **落盘位置纪律**：临时产物写在 `<WORKTREE>/.tmp/`，绝不写家目录或 `/tmp/`。
12. **会话日志**：`<WORKTREE>/docs/sessions/YYYY-MM/YYYY-MM-DD-<kebab-case-topic>.md`。
13. **心跳**：每 25 分钟一次，超 90 分钟失联会被判定崩掉并回收重派：
    ```bash
    cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs heartbeat --lane <模块ID> --note "<当前进展>"
    ```
14. **落地**：验证跑绿、commit 完后：
    ```bash
    cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs land --lane <模块ID>
    ```
    会校验「工作区干净 + 确有新提交」。**并入集成头由调度器做，不要自己 merge、不要 checkout 集成分支。**

## 执行顺序
1. 读蓝图原文 + 读相邻已实现代码（照抄现有写法与命名，不要另起风格）
2. **冲突预检**（不通过 → `BLOCKED` 停止）
3. 10 分钟依赖自检（不通过 → `BLOCKED` 停止）
4. Flyway 迁移（预分配版本号 + RLS + 索引 + GRANT）
5. 后端：jOOQ 显式 SQL + 服务 + 端点 + 集成测试（含租户隔离三断言）
6. 重新生成 OpenAPI 契约
7. 前端：真实工作区 + 错误态/空态/加载态
8. 全量验证跑绿（typecheck 与 test 都要跑）
9. 更新台账自己那行 + 写会话日志
10. commit（车道分支上，不 push）
11. `lane.mjs land --lane <模块ID>`

## 交付报告格式
- 完成/未完成的模块 ID 与名称
- 迁移 / 端点 / 页面 / 测试 四件各自的落地位置
- 上述验证命令的**真实结果**（测试数、通过与否）
- **本车道跑 verify 期间同时存活的 PG 容器数**，以及 verify 结束后是否已回收
- commit hash 与车道分支名
- `land` 是否成功
- 未完成部分的**明确原因**（若有）
```

---

## 第 5 步：常规汇报（停机前）

简短汇报（12 行以内）：

- **进度**：总数 / 已交付 / 进行中 / 未开发 / 完成率，以及**离 465 还差几个**
- **容量闸判定**：running / 配额 4、load 三点值及趋势、PG 容器数 → GO / STOP（命中哪条）
- **本轮回收**：并入了哪些车道；有冲突就点名冲突文件
- **本轮派发**：派发了哪些车道（模块 ID + 名称 + 迁移版本号），或「车道全忙，本轮未派发」
- **噪音观测**：`docker ps` 里 `postgres:16-alpine` 容器数（非 `starcon-mig-v2`）
- **距停机**：距 2026-10-11 18:00 还有多久
- **风险提示**（可选）

## 禁止事项

- **2026-10-11 18:00 之后绝不派发新车道**（最高优先级），且必须比对**完整日期**而非只比 `HH:MM`
- **容量闸判定 STOP 时绝不派发、不取闸、不跑 plan**（次高优先级）
- **不要把 load 阈值改到 10 以下**（低于空载基线 4–7，会让流水线全程 STOP）
- **不要降噪闸**（PG 容器 >= 6 零派发）—— 它是本轮高并发下唯一的自动兜底
- 不要用 `plan` 去「试探」有没有新车道（它有副作用，会凭空造车道）
- 不要用 `load` 单点采样当判据
- 不要自己挑模块或排顺序
- 不要自己写业务代码，绕过子任务
- 不要 push；不要手动 merge 车道分支；不要 checkout 集成分支
- 不要把进度数字写死在汇报里
- **不要在汇报里承诺「全部完成」** —— 理论上限 100%，诚实区间 90%–100%
- 不要为了让 tick 通过而 `force` 清掉别人持有的调度闸
- 不要杀正在跑的车道
- **不要谎报「定时任务已关闭」** —— 关不掉就在报告里写明失败原因