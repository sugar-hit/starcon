# 2026-10-23 —— 1.5.1.1 同步与 API 健康（卷 01 · L3 1.5.1 集成健康）交付记录

> 车道 `feat/1-5-1-1`，基线 `integration/module-dev @ de6dbb98`。
> 本车道属**阶段 D7**，四车道同批（1.2.1.2 / 1.2.2.2 / 1.4.1.2 / 1.5.1.1）互不依赖。

---

## 一、决策摘要

**1.5.1.1「同步与 API 健康」交付为 channel 服务的**只读投影层**，零迁移、零写端点。

蓝图职责：「一屏回答"现在能不能正常同步"：各平台连接状态、最近同步时间、失败计数、
限流命中、队列积压，按店铺/连接展示红黄绿。输出：连接健康卡片 + 需处理项跳转
（重授权、看失败队列、查积压）。」

### 迁移：**无**（V338 预分配但**不占用**）

四条输入逐条落在 channel 自己的既有事实表上，**没有一条需要新表**：

| 蓝图输入 | 真实来源 |
| --- | --- |
| 各平台连接状态 | `channel_connection.status` / `sync_paused` |
| 最近同步时间 | `channel_connection.last_sync_at`（连接级）+ `sync_run.finished_at`（运行级） |
| 失败计数 | `sync_failure`（`reason` 六值） |
| 限流命中 | **同一个 `sync_failure` 表** `reason='RATE_LIMITED'` 的计数 |
| 队列积压 | `outbox_event.published_at IS NULL` + 三张真实队列表 |

**为什么不落派生快照表**：卷 01 的定位是「只读投影」。派生值落库只会比它描述的事实
更早过期 —— 快照表是「上一刻的健康」，而运维要问的是「现在能不能同步」。多一张表
就多一个会与真实状态撒谎的地方。

### 红黄绿：一张显式的「枚举 → 严重度」映射表

服务端算（`SyncHealthRules`），前端**不重算**。规则表在 `GET /channel/sync-health/capabilities`
里逐条公布（命中条件 / 严重度 / 人话理由 / 事实来源表列），页面上每个颜色都能追到一条规则。

| 规则 | 严重度 | 人话 |
| --- | --- | --- |
| `status ∈ {EXPIRED, REVOKED}` | 红 | 连接已失效，不重授权就一直漏单 |
| 生效的最近同步时刻为空 | 黄 | 从来没有同步过（≠「一切正常」） |
| 距最近同步 > 72h | 红 | 同步已停滞 |
| 距最近同步 > 24h | 黄 | 同步偏旧 |
| 最近一次 `sync_run.status='FAILED'` | 红 | 这一轮没跑通 |
| 近 24h `AUTHORIZATION_EXPIRED` 失败 > 0 | 红 | 持续发生，不重授权就一直漏 |
| `status='EXPIRING'` / `='PAUSED'` / `sync_paused=true` | 黄 | 即将过期 / 被暂停 |
| 最近一次运行 ∈ {PARTIAL, CANCELLED} | 黄 | 这一轮只处理了一部分 |
| 近 24h `RATE_LIMITED` 失败 > 0 | 黄 | 平台在限速，**变慢但没断** |
| 其余失败 > 0 | 黄 | 解析失败 / 映射缺失 / 网络 / 平台侧错误 |

**阈值（24h / 72h / 24h 窗口）写进代码常量并在能力声明公布**，并写明
「这是**本模块的默认政策**，不是任何平台下发的口径」。

---

## 二、关键 trade-off

### 1. 「限流」判黄不判红

`RATE_LIMITED` 与 `AUTHORIZATION_EXPIRED` 都是 `sync_failure`，但严重度相反：
限流 = 平台在限速，同步**变慢但没断**；授权失效 = 这条连接**再也拉不到数据**，
会持续漏单。把两者都判成红，运营会对着满屏限流去重授权 —— 那是错的方向。

### 2. 「读不到」与「没有」分三处处理

- **上游读不到** ⇒ 整个端点 **503**，绝不折成空集合冒充「一切正常」。
- **数据范围看不见同步事实** ⇒ `sync_cursor` / `sync_failure` 挂着 RESTRICTIVE 的
  `cursor_scope` / `failure_scope`，SELF / DEPARTMENT 范围下**一行都读不到**。
  此时卡片标 `SYNC_DATA_NOT_VISIBLE`「判不了」，并**跳过全部依赖同步事实的规则** ——
  看不见的时候判「从来没有同步过」是一次彻头彻尾的撒谎。
  （为此专门加了 `SyncHealthRules.evaluate(facts, syncFactsVisible)` 的第二个参数。）
- **平台没有连接** ⇒ 单列 `platformsWithoutConnection`，并区分
  `NO_CONNECTION` / `NO_CONNECTION_BETA` / **`NOT_INTEGRATED`**（尚未接入的平台没连接是
  预期内的，**不是缺口**）。它**不是**一张绿卡片。

### 3. 🔴 `channel_connection.last_sync_at` 本仓**没有任何写入方**

开工取证发现：`last_sync_at` 只被 SELECT，从不被 UPDATE。
把它直接当「最近同步时刻」，等于把「这一列没人写」说成「这条连接从没同步过」。

因此卡片**两个来源都报**（`lastSyncAt` 连接级 / `effectiveLastSyncAt` 运行级），
用 `lastSyncAtSource`（`CONNECTION_DECLARED` / `RUN_FINISHED` / `NONE`）标出生效的是哪一个，
能力声明里写明「本仓当前没有任何代码写 `channel_connection.last_sync_at`，它恒为 null」。

### 4. 🔴 下游跳转落点按**真实路由**校正

任务书给的 `/channels/connections`、`/channels/sync` 是**台账里 L3 锚点的概念名**，
实测本仓 Next.js 路由**没有这两个路径**（`/channels` 是单页 + 页内 Tab，
`Section` 用 `useState` 初始化，**没有 URL 参数**；没有 rewrite、没有 middleware）。
照抄会落到兜底页「这里没有页面」。

⇒ 待处理项改指**真实存在的** `/channels`（并写明落点是哪个 Tab）与本页 `/system-health`。
失败队列（2.4.2.1）**本仓无独立前端页面**（台账路由为 —）⇒ 指向「渠道与店铺 → 同步运行」Tab
的失败原因分布，并写明这件事，**不**指向不存在的路由。

**遗留**：渠道工作台若要做 Tab 深链（`/channels?section=sync`）需要改
`components/channel/channel-workspace.tsx` —— 那是 02.x 车道 owned 的文件，本车道不碰。
⇒ 已记入下方「后续待办」。

### 5. 积压**逐张表**报，合计 = 逐行相加

四张表（`outbox_event` / `listing_content_writeback_task` /
`listing_status_action_queue` / `publish_task`）性质不同，各报一行并带
「算作积压」的判定条件。**合计行不进列表** —— 早先在列表里塞了一行「合计」，
`backlogTotal` 又把列表求和，结果合计被算了两遍（3 + 3 = 6）。已修掉：
合计是**单独一个字段**，列表只含真实表。

### 6. api-proxy 白名单：路径级，方法不级

本文件的白名单只按**路径**匹配（不分方法），这是全文件的既有形状。
所以「本模块只读」的保证在**后端**（只有 `@GetMapping`，写方法落上去拿 **405**），
`SyncHealthIntegrationTest.moduleIsReadOnly` 实跑断言；
代理侧的义务是**不凭空发明写路径**（`/retry`、`/reauthorize` 一律 404）。

---

## 三、文件清单

### 后端（channel 服务，`backend/services/channel/src/main/java/com/starcon/channel/`）

| 文件 | 作用 |
| --- | --- |
| `SyncHealthModels.java` | 响应形状（16 个 record） |
| `SyncHealthRules.java` | 红黄绿规则层：规则表 + `evaluate` + 阈值（**纯函数，可单测**） |
| `SyncHealthService.java` | 只读投影：jOOQ 显式 SQL + 聚合 + 能力声明 |
| `SyncHealthController.java` | 6 个 GET 端点（`/channel/sync-health`） |

**无迁移**（`db/migration/` 未新增文件）。

### 后端测试（`backend/services/channel/src/test/java/com/starcon/channel/`）

| 文件 | 例数 |
| --- | --- |
| `SyncHealthRulesTest.java` | 23（每条规则各一条断言 + 规则表与实现一致） |
| `SyncHealthIntegrationTest.java` | 26（红黄绿逐枚举值、限流按 reason、积压=未发布 outbox、时间写回值、只读 405、真分页真筛选） |
| `SyncHealthTenancyIntegrationTest.java` | 3（多租户三断言，Testcontainers PG16） |

### 前端

| 文件 | 作用 |
| --- | --- |
| `apps/web/src/app/system-health/page.tsx` | `/system-health` 独立页（**新建**） |
| `apps/web/src/components/system-health/sync-health-model.ts` | 前端模型（只渲染、只过滤，**不重算红黄绿**） |
| `apps/web/src/components/system-health/sync-health-workspace.tsx` | 工作区：总览 / 卡片 / 积压 / 无连接平台 / 待处理项 / 能力声明 |
| `apps/web/src/components/system-health/sync-health-model.test.ts` | 23 例 |
| `apps/web/src/app/api/channel/sync-health-allowlist.test.ts` | 6 例（白名单双向断言） |
| `apps/web/src/lib/api-proxy.ts` | **新增一条独立 `channel/` 元素**（数组末尾，既有条目一字未动） |

### 契约 / 台账 / 日志

- `packages/api-contracts/src/channel.openapi.json` + `src/contracts/channel.ts`
  （codegen 产物：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`；
  diff **只含本模块** 16 个类型 + 6 条路径）
- `docs/product/capability-blueprint/module-progress.md` —— **只改 1.5.1.1 那一行**的状态与证据两格
- 本文件

---

## 四、验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | ✅ |
| L0 类型 | `pnpm typecheck` | ✅ 3/3 |
| L1 后端 | `./mvnw -pl services/channel -am test -Dtest='SyncHealth*' -Dsurefire.failIfNoSpecifiedTests=false` | ✅ **52 例**（23 + 26 + 3），failures 0 / errors 0 |
| L1 前端 | `pnpm vitest run apps/web/src/components/system-health` | ✅ 23 例 |
| 白名单全量回归（改 api-proxy.ts 必跑） | `pnpm vitest run apps/web/src/app/api` | ✅ **206 文件 / 1191 例** |
| Lint | `pnpm lint` | ✅ 3/3 |

---

## 五、后续待办

1. **渠道工作台的 Tab 深链**（`/channels?section=sync`）：本车道的待处理项只能指到 `/channels` 整页
   + 文字提示落点是哪个 Tab。要做成真深链需要改 `components/channel/channel-workspace.tsx`
   —— **02.x 车道 owned，本车道未碰**，建议由 2.2.1.1 / 2.4.1.1 的车道顺带做。
2. **失败队列的独立页面**（2.4.2.1 台账路由仍为 `—`）：端点齐了（`GET /channel/sync-failures` +
   `/sync-failures/summary`），缺一个前端页。当前待处理项指向「同步运行」Tab 的失败原因分布。
3. **`channel_connection.last_sync_at` 仍然没有写入方**：同步运行收尾（`completeRun`）只写
   `sync_cursor.last_success_at` / `last_failure_at`。若要这一列名副其实，
   需在 02.4 同步运行的收尾路径里补一次回写 —— 那是 **2.4.1.2 车道 owned**，
   本模块已用 `lastSyncAtSource` 显式标注当前生效来源，不依赖这一列。
4. **1.5.1.2 数据一致性体检**与 **1.5.2.1 服务与任务状态**：未交付，
   已在能力声明的 `gaps` 里逐条列出（available=false + ownerModule + 影响说明）。
