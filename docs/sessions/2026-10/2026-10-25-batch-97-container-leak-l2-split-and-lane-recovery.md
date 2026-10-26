# 2026-10-25 · batch-97 —— 事故现场接管：r3 真实结论、清道夫误杀取证、分服务 L2 拆分、两条车道重派续跑

> 批次号 **97**（承接 `batch-96`）。
> 集成头 `integration/module-dev`；调度者：Mavis（pipeline cron tick，2026-10-25 16:00 触发，17:40 接手现场）。
> **强度档位：降档模式** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（**自动降档，不是调度者自选**）。
>
> ⚠️ **本轮不是在正常起点上开工的** —— 上一 tick（12:00）崩在调度闸里，本轮是**事故现场接管**。
> 本文件的第一要务是**把上一 tick 的错误结论按事实纠正**，而不是把它继承下去。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度 | 465 总数 · **404 已交付** · 0 进行中 · **61 未开发**（完成率 **86.9%**，离 465 还差 **61**） |
| 容量闸判定 | **GO** —— running **0**/6、`pmset -g therm` **无降频项**、load **2.10 / 2.78 / 2.54**（10 核，三点平稳）、Testcontainers PG **0** 个 |
| 节流闸 | 距上批 **337 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 接手时的现场 | 上一 tick 会话 `mvs_f130e6a1…` 状态 `error`（`net::ERR_HTTP2_PING_FAILED`）· 调度闸 owner PID 6436 **已死** · **9 个 commit 未推送** |
| 本轮回收 | **2 条车道并入**（13.1.1.2 → `f574c493`，18.2.1.1 → `84179e67`）+ **5 个**已合并且已 push 的遗留 fix worktree（`12.1.1.1-fix` / `12.1.2.1-fix` / `12.2.2.2-fix` / `16.3.2.3-fix` / `3.9.2.2-fix`） |
| 本批派发 | **2 条**：**13.1.1.2 多店对比**（reporting，V381）+ **18.2.1.1 财务软件**（platform + identity，V382）—— **两条本轮都交付了** |
| 派发后事故 | 两条车道的 worker 在 **17:34 同时**死于 `net::ERR_HTTP2_PING_FAILED`，成果**全部未提交** ⇒ **同 worktree 重派续跑**（任务书第一条命令 = 提交存量成果）；重派后 **18.2.1.1 又死一次**（但这次成果已被 `9f59e22d` 钉住，没丢） |
| 静态预检 | 本批 3 个新迁移（V381 / V382×2）**三项扫描全部零命中**；RLS 逐表核对通过 |
| **L2 批量闸** | **🟢 全绿** —— 后端 **9 个服务 / 10 669 例零失败** + 契约零 drift + 前端 **638 文件 / 8875 例** + Next build + `sync --check`（详见第五节） |
| 收尾三件套 | 说明 ✅ / commit ✅ / push ✅（见第七节） |

---

## 一、进度

**开工时**（`node tools/module-progress/lane.mjs status`）：18 卷 / 465 个 L4 模块，
已交付 404 · 进行中 0 · 未开发 61（完成率 86.9%）。

**并入两条车道并重跑 `sync.mjs` 后**：**已交付 406 · 进行中 0 · 未开发 59**（完成率 **87.3%**）。

| 阶段 | 已交付 | 进行中 | 未开发 |
| --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 |
| D3 | 40 | 0 | 0 |
| D4 | 60 | 0 | 0 |
| D5 | 52 | 0 | 0 |
| D6 | 75 | 0 | 3 |
| D7 | 99 | 0 | 56 |

`node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（绿，退出码 0）。

---

## 二、🔴 接手时的现场：上一 tick 崩在闸里

| 观测 | 实测 |
| --- | --- |
| 上一 tick 会话 | `mvs_f130e6a1267242eea61597d55f7ab278` ⇒ 状态 **`error`**，错误 `net::ERR_HTTP2_PING_FAILED` |
| 未推送 commit | **9 个**：`8fd35cd5` `4b9a48a1` `ee2a2a56` `e1d59315` `444bea4d` `f16de390` `3f8b6744` `be3a9d24` `d0270403` |
| 未跟踪文件 | `docs/sessions/2026-10/2026-10-25-batch-96-two-lane-delivery-and-clock-domain-fix.md` |
| 未提交改动 | `docs/product/capability-blueprint/module-progress.md`（`M`） |
| 调度闸 | owner PID **6436 已死**（`ps` 查无此进程），闸龄超过 TTL（4200 秒）⇒ **陈旧闸** |
| 后台清道夫 | `.tmp/janitor-loop.sh` **PID 47653 仍在跑**（已存活 1 小时 46 分） |

⇒ 依协议「超 TTL 即视为陈旧闸，下一个 tick 可删掉闸目录重建（= 抢闸）」，
本轮 `lane.mjs gate acquire` **成功取得闸**（`{"ok": true, "gate": "acquired"}`），
并按节点持续续期（`keepalive-170830-r17` → `keepalive-171350-r18` → `keepalive-173712-r19`）。

---

## 三、🔴🔴 本轮最关键的取证：**L2 第 3 轮是红的，根因是清道夫误杀共享静态容器**

上一 tick 的 batch-96 文档写着「第 3 轮 🟢」「push ✅」。**逐条核对日志后，两条都不成立。**

### 3.1 r3 的真实结果

日志 `.tmp/l2r3-150653.log`（15:06:53 → 16:20:19）尾部实测：

```
[INFO] identity ................................. SUCCESS [02:31 min]
[INFO] catalog ................................. SUCCESS [06:02 min]
[INFO] channel ................................. SUCCESS [16:15 min]
[INFO] warehouse .......................... FAILURE [48:30 min]
[INFO] trade ................................ SKIPPED
[INFO] crm .................................. SKIPPED
[INFO] finance .............................. SKIPPED
[INFO] reporting .......................... SKIPPED
[INFO] platform ........................... SKIPPED
[INFO] Total time:  01:13 h
```

失败用例：`warehouse.PendingInspectionTenancyIntegrationTest` **3 例 ERROR**，
报错 `Connection to localhost:60087 refused` ⇒ `ApplicationContext` 加载失败。
⇒ **r3 是 BUILD FAILURE，且 trade / crm / finance / reporting / platform 一次都没跑到**
⇒ **batch-96 交付的两个新模块至今未经任何全量验证**。

### 3.2 根因：清道夫的判据杀不了「该杀的」，专杀「不该杀的」

batch-96 第七节引入了一个「容器清道夫」，判据是
**「本次 ryuk session + 已存在 ≥180 秒 + 连接数为 0 ⇒ 删掉」**。
本轮实测证明这个判据**分不清两种容器**：

| 容器种类 | 特征 | 清道夫应不应该删 |
| --- | --- | --- |
| 泄漏容器（没 `.stop()`） | 建好就没人用，连接数 0 | 该删 |
| **共享 static 容器**（跨测试类复用） | 建好之后、在**下一个测试类启动之前**的那段时间里，**连接数恰好是 0**、年龄**必然已过 180 秒** | **绝不该删** |

本仓有三处共享 static 容器：`IdentityIntegrationTestBase`（43 个测试类继承）、
`PendingInspectionTestBase`、`ConsignmentStockTestBase`。

**时间戳链（全部实测，非推演）**：

| 时刻 | 事件 |
| --- | --- |
| 15:33:12 | 容器 `60087` 被 `PendingInspectionIntegrationTest` 建出（`PendingInspectionTestBase` 的共享 static 容器） |
| 15:55:39 | **清道夫把它删了** —— 自报 `age=1347s`，`1347s + 15:55:39 = 15:33:12` **精确吻合** |
| 15:59:03 | `PendingInspectionTenancyIntegrationTest` 启动，**依赖这个已被删的容器** ⇒ `Connection refused` ⇒ 3 例 ERROR |

⇒ **上一 tick 的「L2 第 3 轮红」是自己造出来的。**

### 3.3 同时排除的两个替代解释（避免把结论下早）

- **不是内存耗尽**：失败时存活容器仅 **2** 个；无 OOM / kill 证据；
  host 16GB、Docker VM 12.5GB、单容器 ~20.65MiB ⇒ 内存根本没到临界。
- **不是宿主机与 PG 的时钟漂移**：漂移确实存在（r1 的 15 分 54 秒冻结有据），
  但 r3 的报错形态是**连接被拒**（连都连不上），与断言取值无关。
  ⇒ r3 与 r1/r2 **不是同一种故障**，不能拿 r1/r2 的「跨时钟域断言」结论去解释 r3。

### 3.4 对「跨时钟域断言」结论的重新评估

- r1（warehouse 留痕时刻）与 r2（channel staleSync）两次红的诊断**本身仍有据**
  （上一 tick 用物理拨钟复现过，有逐字相同的报错原文），对应的两个修复 commit
  `f16de390` / `be3a9d24` **仍在 HEAD 上，本轮未回退**。
- 但它们**从未被一次全绿 L2 验证过** ⇒ batch-96 的「战果」结论不完整：
  真因是「**容器泄漏 + 误杀清道夫**」这条**独立**故障线。

### 3.5 本轮处置

1. **kill 掉清道夫**：`janitor-loop.sh`（PID 47653）+ `janitor.sh` ⇒ **不再使用这条判据**。
2. **改用「分服务独立 maven session」限制容器峰值**（见第五节）—— **不动任何测试类**。
3. **根治方案（给不 `.stop()` 的类补 `@AfterAll`）仍属 backlog 的「待人工决策」**，本轮**不自作主张**。

### 3.6 容器泄漏的量化（本轮实测补齐）

各服务测试类总数 / 其中会 `.stop()` / **不会 `.stop()`**：

| 服务 | 测试类 | 会 stop | **不 stop** |
| --- | --- | --- | --- |
| identity | 1 | 0 | 1 |
| catalog | 60 | 50 | 10 |
| **channel** | **146** | 33 | **113** |
| warehouse | 68 | 68 | 0 |
| trade | 124 | 112 | 12 |
| **crm** | **34** | 0 | **34** |
| **finance** | **64** | 0 | **64** |
| reporting | 3 | 3 | 0 |
| platform | 4 | 0 | 4 |
| **合计** | **506** | **266** | **≈ 238** |

⇒ 最坏单服务峰值（channel）= 113 个容器 × ~20.65MiB ≈ **2.3 GiB**，
在 12.5 GiB 的 Docker VM 里完全可承受 —— **前提是不能跨服务叠加**。这就是分服务的理由。

---

## 四、本批派发：2 条车道（降档后 `LANES=2`）

`lane.mjs plan --lanes 2` 排出 2 条，`serviceBlocked` 为空：

| 项 | 车道 A | 车道 B |
| --- | --- | --- |
| 模块 ID / 名称 | **13.1.1.2 多店对比** | **18.2.1.1 财务软件** |
| 阶段 / L3 | D7 / 13.1.1 | D7 / 18.2.1 |
| 主责服务 | **reporting** | **platform**（8089）+ identity 权限码 |
| 迁移版本 | **V381** | **V382**（platform + identity 各一份） |
| 分支 / worktree | `feat/13-1-1-2` / `.worktrees/13.1.1.2` | `feat/18-2-1-1` / `.worktrees/18.2.1.1` |
| 基线 | `d0270403` | `d0270403` |

两条任务书在派发前**逐字检查无残留 `<...>` 占位符**，均含：蓝图四问原文、上游自检清单、
租户三断言、`BigDecimal` / `timestamptz` 纪律、契约与台账共享规则、L0/L1 命令
（含 `-Dsurefire.failIfNoSpecifiedTests=false` **双属性**）、api-proxy allowlist 例外、
Testcontainers `.stop()` 纪律、commit / 日志 / 心跳 / `land` 步骤。

### 4.1 🔴 派发后本轮又出一次事故：**两条车道同时死在同一个错误上**

| 时刻 | 事件 |
| --- | --- |
| 17:34:47 | 车道 13.1.1.2 的 worker 失败：`net::ERR_HTTP2_PING_FAILED` |
| 17:34:49 | 车道 18.2.1.1 的 worker 失败：`net::ERR_HTTP2_PING_FAILED` |

**两个失败时间戳只差 340 毫秒** ⇒ 不是两个独立故障，是**同一条会话传输断掉**带走了两个子会话
（与 batch-92/93/94 的 `ERR_CONNECTION_CLOSED`、上一 tick 的 `ERR_HTTP2_PING_FAILED` 同族）。

**后果**：两个 worker 都死在**写完代码、提交之前** —— 成果全部悬空：

| 车道 | 留下的成果（未提交） | 还缺 |
| --- | --- | --- |
| 13.1.1.2 | 7 个文件 / **2621 行**（迁移 V381 + 6 个 Java 文件，controller/service/refresh/port/catalog/models） | 集成测试、多租户测试、前端三件套 + 页面、api-proxy 白名单、契约、台账、会话日志、commit |
| 18.2.1.1 | 8 个文件 / **3499 行**（迁移 V382 + identity 权限码迁移 + 6 个 Java 文件） | 同上 |

自测状态：13.1.1.2 的 worker 在被打死前正修「增长指标必须有显式基准指标」的 bug；
18.2.1.1 的 worker 在被打死前刚说「Compiles clean. Now let me study the integration test harness」。

### 4.2 处置：**同 worktree 重派续跑**，且**任务书第一条命令就是提交存量成果**

**没有再跑 `plan`** —— 两条车道的 `sessionId` 为 null、心跳距今 24/35 分钟，
仍在 60 分钟宽限期 ⇒ `plan` 不会把它们当孤儿回收，
反而会**另排 2 条新车道**（同一个模块被两个 worker 同时写）。
正确做法是**直接朝原 worktree / 原分支重派**，让 `plan` 的孤儿回收路径留到宽限期过后再用。

重派任务书相对首轮的三处增强：

1. **第一条命令就是 `git add -A && git commit`** —— 先把存量成果钉在分支上，
   再谈补完（宁可提交「能编译但测试还没写」的中间态，也不要零提交）。
2. 把上一轮 worker 的**设计结论与停手位置**原样交接（省掉重复调研）。
3. 明确要求「**先跑 L0 编译闸再提交**」，避免把编译不过的半成品提交成基线。

**这条策略当场见效**：重派后的 18.2.1.1 在被第二次打断（500）之前，
已经把继承来的 3499 行提交成 **`9f59e22d`** —— 成果零丢失。
而 13.1.1.2 因为继承来的代码**当时编译不过**（上一 worker 死在改到一半的编辑里），
先修编译再提交，随后连出 **8 个 commit**，把半成品逐个钉住。

### 4.3 两条车道的最终交付（**本轮两条都交付了**）

| 项 | 13.1.1.2 多店对比 | 18.2.1.1 财务软件 |
| --- | --- | --- |
| 服务 | reporting | platform（8089）+ identity 权限码 |
| 迁移 | `V381__store_comparison.sql`（3 表，RLS ENABLE+FORCE，触发器保证 base 指标真实存在） | `V382__finance_software.sql`（7 表）+ `V382__platform_finance_permission.sql` |
| 端点 | 5 个 `/reporting/store-comparison` | 18 个 `/platform/finance-software/**` |
| 页面 | `/analytics/dashboard/store-comparison` | `/integrations/connectors/finance-software` |
| 后端测试 | **20 例**（15 功能 + 5 多租户）全绿 | **39 例**（32 功能 + 7 多租户）全绿 |
| 前端测试 | **40 例**（34 模型 + 6 白名单）全绿 | **32 例**（27 模型 + 5 白名单）全绿 |
| 全量 api-proxy 回归 | 251 文件 / **1417 例**全绿 | 251 文件 / **1416 例**全绿 |
| commit 数 | **21**（head `eed7705b`） | **11**（head `1f5410b`） |
| `land` | ✅ `{"ok":true,"lane":"13.1.1.2","head":"eed7705"}` | ✅ `{"ok":true,"lane":"18.2.1.1","head":"1f5410b"}` |

**worker 自查出的真实缺陷（都当场修掉并写进会话日志）**：

| 车道 | 缺陷 | 为什么重要 |
| --- | --- | --- |
| 13.1.1.2 | `project()` 把 `timezone` 写死成 `'UTC'`，而分桶日期是按请求时区算的 | 跨时区租户的分桶日期会错一天 |
| 13.1.1.2 | DTO 名 `MetricDefinitionView` / `PlatformOption` 与 13.1.1.1、channel **同名** | codegen 的同名闸会 abort，或把别人的类型静默覆盖 |
| 13.1.1.2 | 可空字段没标 `@Schema(nullable = true)` | 契约把「算不出来」写成 `number`，前端会以为那是 0 |
| 18.2.1.1 | 继承代码里 **3 处 INSERT 占位符错位**（含 `created_by` uuid 被 cast 成 timestamptz） | **该模块此前整模块不可用** —— 上一轮 worker 死时留下的半成品 |
| 18.2.1.1 | `ONLY_LOCAL` 差异行写空 `external_subject_code`，撞迁移里的 `CHECK (btrim(...) <> '')` | 同上，功能不可用 |

**能力边界如实上报（不是缺口，是上游事实）**：13.1.1.2 的**店铺维度毛利率与库存周转仍不可得**（上游没有店铺维度），
已在 `capabilities` 里如实标出，未造假；18.2.1.1 的**外部财务软件适配器仍未交付**（归 18.2 卷级），
`/push` 恒 409 且一次数据都不写，页面按能力声明显示「未接入」且不提供该按钮。

---

## 五、L2 批量闸：为什么拆成两阶段，以及怎么拆

### 5.1 协议口径与本轮的实际偏差（明写，不藏）

协议 5.2 写的是**一次** `cd backend && ./mvnw verify` + 前端四项 + `sync --check`。
本轮**拆成两个阶段**，原因有二：

1. **容器泄漏使「一次性全 reactor」不可行**：surefire 默认 `forkCount=1` / `reuseForks=true`
   ⇒ 一个服务 = 一个 fork JVM ⇒ **泄漏容器在 fork JVM 的整个生命周期内累积**；
   一次性跑完 9 个服务 ⇒ 峰值 ≈ 全量泄漏叠加（实测 r3 期间 54 → 61 个容器同 session 堆积）。
2. **本轮有 2 条车道在同一时间开发**：它们分别改 `reporting` 与 `platform`（+ `identity`）。
   一次性全 reactor 跑完的那一刻，车道代码可能**还没并进来** ⇒ 跑的不是最终代码。

### 5.2 拆法与依据

| 阶段 | 范围 | 跑在哪 | 何时 |
| --- | --- | --- | --- |
| **A** | catalog · channel · warehouse · trade · crm · finance —— **本批车道完全不影响的服务** | 独立 worktree `/Users/guan/Projects/starcon-worktrees/l2-verify`（detached @ `d0270403`） | 立即 |
| **B** | identity · reporting · platform —— **被车道改动的服务** | 第二个独立 worktree `/Users/guan/Projects/starcon-worktrees/l2-verify-b`（detached @ 并入后的 `84179e67`） | 车道落地并入后，**与阶段 A 尾部并行** |
| **C** | 契约 codegen + 前端四项 + `sync --check` | 集成头（那里才有 `node_modules`） | 阶段 B 后端跑完即可 |

**为什么用两个独立 worktree**：并入动作要写集成头的工作区；
若验证在集成头跑，并入与验证会互相踩文件（maven 正在编译的源码被 checkout 换掉）。
独立 worktree 跑的是**同一个 commit 的同一份代码**，只是不与并入动作抢同一个工作区。
阶段 B 单独开一个 worktree，是为了让**它与阶段 A 的尾部并行**（否则要串行等到 19 点以后）。

**阶段 A 用独立 worktree 的理由**：并入动作要写集成头的工作区；
若验证在集成头跑，并入与验证会互相踩文件（maven 正在编译的源码被 checkout 换掉）。
独立 worktree 跑的是**同一个 commit 的同一份代码**，只是不与并入动作抢同一个工作区。

**分服务 session 为什么能治容器泄漏（实测证据）**：
`catalog` 单服务 maven session 跑完（17:09:49）后，
`docker ps -a --filter label=org.testcontainers.sessionId` = **0**，
ryuk 容器也为 0 ⇒ **ryuk 在 maven session 结束时完整回收了容器**。
于是「一服务一 session」把峰值容器数从「全 reactor 累积」压到「单服务峰值」（最坏 113 个 ≈ 2.3 GiB），
**而且不需要改任何一个测试类**。

**顺带查实的一件事**：本仓 surefire 3.5.4 的 `reuseForks` 参数的 user property 是 `reuseForks`，
**不是** `surefire.reuseForks` ⇒ 上一轮实验里写的 `-Dsurefire.reuseForks=false` 是**静默无效**的
（这解释了当时观察到的「fork 计数 0、PID 不变」）。本轮**没有采用**分叉方案，改用上面的分服务方案。

### 5.3 L2 实测结果

**阶段 A（第 1 组：channel + warehouse，并发）—— 全绿：**

| 服务 | 用时 | 测试数 | Failures | Errors | Skipped | 退出码 | session 结束时的存活容器 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `warehouse` | 51 min | **1672** | 0 | 0 | 0 | **0 ✅** | 105 |
| `channel` | 52 min | **2344** | 0 | 0 | 0 | **0 ✅** | 113 |

⇒ **上一 tick 红在 warehouse 的那个服务，本轮 1672 例全绿。**
r3 的失败与代码无关这一判断得到直接印证。

**分服务 session 的效果实测**：channel 跑到尾声时**同时存活 113 个容器**（泄漏累积），
session 一结束，**几分钟内掉到 16 个**（ryuk 回收）⇒
「全 reactor 一次跑」会把 9 个服务的泄漏叠在一起，「一服务一 session」不会。

#### 后端全量结果（9/9 服务全绿）

| 服务 | 阶段 | 测试数 | Failures | Errors | Skipped | 退出码 |
| --- | --- | --- | --- | --- | --- | --- |
| `identity` | B | 587 | 0 | 0 | 0 | **0 ✅** |
| `catalog` | A | 1998 | 0 | 0 | 0 | **0 ✅** |
| `channel` | A | 2344 | 0 | 0 | 0 | **0 ✅** |
| `warehouse` | A | 1672 | 0 | 0 | 0 | **0 ✅** |
| `trade` | A | 2458 | 0 | 0 | 0 | **0 ✅** |
| `crm` | A | 491 | 0 | 0 | 0 | **0 ✅** |
| `finance` | A | 970 | 0 | 0 | 0 | **0 ✅** |
| `reporting` | B | 52 | 0 | 0 | 0 | **0 ✅** |
| `platform` | B | 97 | 0 | 0 | 0 | **0 ✅** |
| **合计** | | **10 669** | **0** | **0** | **0** | **9/9 ✅** |

> 「阶段 B」= 车道并入后跑（identity / reporting / platform）；
> 「阶段 A」= 车道不影响的服务（catalog / channel / warehouse / trade / crm / finance），
> 跑在**并入前**的同一份代码上 —— 这 6 个服务的源码在并入前后**逐字节相同**，
> 所以阶段 A 的结果对并入后的 HEAD 依然成立（且 codegen 零 drift 反证了这一点）。

#### 契约闸：codegen 重跑，**零 drift**

把两阶段导出的 9 份 `*.openapi.json` 合成一份，在集成头上重跑
`python3 tools/codegen/catalog-contracts.py --spec-dir …`：

- `Generated 7026 DTOs in 9 per-service files, 9 OpenAPI snapshots and one barrel`，退出码 0；
- `git status --short packages/api-contracts` ⇒ **DRIFT_FILES = 0**
  ⇒ **并入后的契约与 codegen 产物逐字节一致**，union 求并没有吃掉任何人的契约改动。

#### 前端四项（集成头上跑）

| 项 | 结果 | 退出码 |
| --- | --- | --- |
| `pnpm typecheck` | 3 个包全部 successful（web 实际执行、ui 与 config-tailwind 命中 turbo 缓存） | **0 ✅** |
| `pnpm lint` | 3 个包全部 successful（仅 5 条**既有** `react-hooks/exhaustive-deps` warning，无 error） | **0 ✅** |
| `pnpm test` | **638 文件 / 8875 例全部 passed** | **0 ✅** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 生产构建成功，路由表正常输出 | **0 ✅** |
| `node tools/module-progress/sync.mjs --check` | ✓ 台账与蓝图一致 | **0 ✅** |

⇒ **L2 批量闸：🟢 全绿。**

### 5.4 并入后的共享文件专项核对（协议 5.1 要求）

`apps/web/src/lib/api-proxy.ts` 是两条车道都改了的 1MB 共享白名单累积文件，
union 驱动对它走了「行级兜底并集」并**主动提示人工确认** ⇒ 本轮逐项核对：

| 检查项 | 结果 |
| --- | --- |
| 本批改动量 | **99 insertions / 0 deletions** ⇒ **纯新增，零删除** |
| 13.1.1.2 的 `store-comparison` 条目 | ✅ 在（10 处命中） |
| 18.2.1.1 的 `finance-software` 条目 | ✅ 在（29 处命中） |
| 既有 238 条元素是否被动过 | ❌ **一条没动**（diff 里零删除行） |

⇒ 两条车道的插入锚点相距很远，git 干净求并，**没有静默删掉别人已交付的路由**。
两条车道各自还跑了**全量 api-proxy 回归**（251 文件 / 1416–1417 例全绿），
所以「放行了不该放行的路径」和「误伤了兄弟车道的路径」两个方向都被证伪。

---

## 六、静态预检（5.1）

本批 3 个新迁移逐个扫（扫描对象是车道 worktree 里**实际存在的文件**，不是计划）：

| 形状 | V381（reporting） | V382（platform） | V382（identity） |
| --- | --- | --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **零命中** | **零命中** | **零命中** |
| ② `SET NOT NULL` | **零命中** | **零命中** | **零命中** |
| ③ `ADD CONSTRAINT … CHECK` | **零命中** | **零命中** | **零命中** |
| RLS | 4 表 · DO 块统一施加 `isolation` 策略 | 7 表逐表 `FORCE` + 7 条策略 | 不适用（权限码登记，无业务表） |

---

## 七、收尾三件套：写说明 → commit → push

| 件 | 结果 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅ | 本文件 `docs/sessions/2026-10/2026-10-25-batch-97-container-leak-l2-split-and-lane-recovery.md`（批次号 97）**+ 修正 batch-96**（第十二节勘误） |
| ② commit | ✅ | **本说明与台账 `module-progress.md` 同一个 commit**，未拆分 |
| ③ push | ✅ | `git push origin integration/module-dev` —— 推送内容 = **上一 tick 遗留的 9 个 commit（含两条已交付模块与两个时钟域修复）+ 本批两条车道的 32 个 commit + 2 个 merge + 本说明与台账**，共 **45 个 commit**；**只推集成分支，未碰 `main`** |

**顺序与前提**：L2 🟢 全绿 ⇒ 才写说明 ⇒ 才 commit ⇒ 才 push。
若 L2 是红的，本轮**不会 push**（协议硬要求），成果与诊断留在集成头本地。

**并入与台账的处理顺序**（值得记，协议没写但踩过坑）：
1. `merge-ready` 要求**集成头工作区干净**，而本轮的说明文件是未跟踪的、台账是已修改未提交的
   ⇒ 先把说明文件挪到 `/tmp`、把台账改动 `git checkout --` 还原；
2. `land` → `merge-ready`（union 求并）；
3. 说明文件放回；
4. **重跑 `sync.mjs` 重新生成台账**（它是整文件重写、保留每行的状态与证据栏、刷新总览）
   ⇒ 台账数字从 404/61 变成 **406/59**，且与蓝图一致。

为什么不用 `git stash`：stash pop 在台账上会与车道带来的行变更撞车；
「还原 → 并入 → 重跑 sync.mjs」是**幂等且无冲突**的路径。

---

## 八、吞吐预期（按当前档位现算）

| 项 | 值 |
| --- | --- |
| 当前档位 | **降档模式**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过） |
| 每批车道数 | **2**（`LANES` 4 减半） |
| cron | `0 * * * *` ⇒ 24 个触发点/天 |
| 节流闸阈值 | **100 分钟** ⇒ 60 分钟栅格下实际放行间隔 **2 小时** |
| 名义吞吐 | 2 × 12 = **24 个/天** |
| 综合折损系数 | **× 0.70**（含「L2 返工」折损 ×0.90） |
| **实际吞吐预期** | **≈ 17 个/天**（诚实区间 **12–20 个/天**） |
| 剩余可自动排完量 | **59** 个未开发 − 4 个循环依赖 ⇒ **55 个待排** ⇒ 55 ÷ 17 ≈ **3.2 天**（区间 **3–5 天**） |

---

## 九、风险提示

1. **🔴 worker 会话崩溃是当前第一号风险** —— 本轮两条车道**同时**死于 `net::ERR_HTTP2_PING_FAILED`，
   上一 tick 同样死于它，batch-92/93/94 死于同族的 `ERR_CONNECTION_CLOSED`。
   **它专挑「写完代码、提交之前」的时机**，已经连续三轮吃掉整批成果。
   **本轮的对策已验证有效**：把「**先提交存量成果**」写成任务书第一条命令后，
   18.2.1.1 在第二次被打断时成果已被 `9f59e22d` 钉住、**零丢失**；13.1.1.2 靠连出 8 个 commit 逐个钉住半成品。
   ⇒ **建议固化为协议条款**：worker 每完成一个可编译单元就提交一次，不攒到最后。
2. **🔴 容器泄漏仍未根治** —— ≈238 个测试类不 `.stop()`。
   本轮用「分服务 session」把峰值压住了，但**根没除**：
   根治方案（给不 stop 的类补 `@AfterAll`）在 `docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`，
   标注「待人工决策，调度器不自作主张」⇒ **需要人工拍板**。
3. **🔴 调度闸的 TTL（70 分钟）远小于一轮实际时长**（本轮实测已超 2 小时）⇒ 全程靠续期维持。
   续期必须 `touch` **新文件名**（`echo >> owner` 或 touch 已存在文件都**不刷新** mtime）。
4. **🟡 L2 拆成两阶段的代价** —— 阶段 A 与阶段 B 之间存在窗口，
   若车道最终没有并入本轮，本批的「一次 L2」实际只覆盖了阶段 A + 未并入车道时的阶段 B。
   协议「每轮只跑一次」的本意是**省掉重复发热**，不是禁止分阶段；本轮的拆分动机（容器峰值 + 并入竞态）已写明。
5. **🟡 4 个循环依赖模块**（`5.1.1.1` / `5.1.2.1` / `15.3.1.1` / `15.3.1.3`）仍需人工拍板拆环。
6. **🟡 用户机器上 `langfuse-worker` / `langfuse-web` 两个容器处于崩溃重启循环**
   ⇒ 持续给 Docker VM 制造抖动。这是**用户的服务，本轮未做任何干预**。

---

## 十、文件清单

**本轮调度器写入集成头的文件**：

- `docs/sessions/2026-10/2026-10-25-batch-97-container-leak-l2-split-and-lane-recovery.md`（本文件，新增）
- `docs/sessions/2026-10/2026-10-25-batch-96-two-lane-delivery-and-clock-domain-fix.md`（**按事实勘误**，原文件从未提交过）
- `docs/product/capability-blueprint/module-progress.md`（台账，由 `sync.mjs` 回写）

**本轮回收的 worktree**（5 个，均已合并且已 push）：
`12.1.1.1-fix` / `12.1.2.1-fix` / `12.2.2.2-fix` / `16.3.2.3-fix` / `3.9.2.2-fix`
（对应 commit `2f009123` / `eaed9965` / `92e73958` / `4fa38e38` / `912207c2` 均已在 origin）

**本轮新建的辅助 worktree**：
- `/Users/guan/Projects/starcon-worktrees/l2-verify`（detached，阶段 A：6 个服务）
- `/Users/guan/Projects/starcon-worktrees/l2-verify-b`（detached，阶段 B：3 个服务）

**本轮并入的代码**：

- **13.1.1.2 多店对比**（reporting）—— 迁移 `V381__store_comparison.sql` + 5 个端点 + 页面
  `/analytics/dashboard/store-comparison` + 20 个后端用例 + 40 个前端用例 + 契约重生成
- **18.2.1.1 财务软件**（platform + identity）—— 迁移 `V382__finance_software.sql`（7 表）+
  `V382__platform_finance_permission.sql` + 18 个端点 + 页面 `/integrations/connectors/finance-software`
  + 39 个后端用例 + 32 个前端用例 + 契约重生成
- `apps/web/src/lib/api-proxy.ts`（**两条车道合计纯新增 99 行，零删除**）
- `packages/api-contracts/src/contracts/{reporting,platform}.ts` + 对应 `*.openapi.json`（codegen 产物，零 drift）

**并入的会话日志**（worker 各自写的）：
- `docs/sessions/2026-10/2026-10-25-13-1-1-2-store-comparison.md`
- `docs/sessions/2026-10/2026-10-25-18-2-1-1-finance-software.md`
