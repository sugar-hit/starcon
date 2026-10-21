# 2026-10-20: 4.3.2.2 平台状态回流（阶段 D5，L3 锚点 4.3.2）

## 摘要

交付蓝图模块 **4.3.2.2 平台状态回流**（卷 04 · 4.3 发布与同步 → 4.3.2 回写与回流 → 4.3.2.2），
落在 channel 服务：接收平台侧的商品状态变化（审核通过 / 驳回 / 下架 / 限流 / 被抑制 / 类目审核 /
价格异常通知），落**状态变更记录** + **需处理队列**，判定内部状态与平台状态的**冲突**并以平台为准告警，
支持**按驳回原因一键批量修正重发**（经 2.5.1.1 的 `platform_operation` 既有队列）。
前端独立成页 `/listings/status-sync`（与 4.3.2.1 的 `/listings/content-writeback` 并列，
但**不是同一个页面** —— 一个把内部变更推出去，一个把平台状态收回来）。

## 决策 / 结论

### 1. 🔴 `listing_status_event` 已存在 —— 本模块**扩展**它，不另建

调度给本车道的表名命名空间是 `listing_status_event` + `listing_status_action_queue`。
开工前实测集成头发现：**`listing_status_event` 已由 V171（4.1.1.1 Listing 列表）建好**，
且 V171 文件头把设计意图写死了：

> 「「近 7 日状态变化」是职责句里的展示项，而状态变更是 4.1.1.3 的输出。本模块只需要**读到**它，
> 所以事件表 `listing_status_event` 随本迁移建立，4.1.1.3 在这张表上补生命周期状态机与平台回写即可，
> **无需第二次建表**。」

`source` 列的 CHECK 早已为 4.3.2.2 **预留了 `'PLATFORM'` 取值**：

```sql
source text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('MANUAL','PLATFORM'))
```

**决策**：扩展该表（`ADD COLUMN` 一律带 DEFAULT），不另建平行事件表。
理由：另建一张会让「这个商品最近状态变了什么」出现**两个答案**，而 4.1.1.1 的「近 7 日状态变化」
与 4.1.1.2 的状态轨迹读的都是 V171 那张 —— 写另一张等于让列表与本模块记录分叉。
同时这也是与 4.3.2.1 一致的纪律：不抢别的模块的事实所有权。

**迁移纪律**（本批约定）：只 ADD COLUMN 且带 DEFAULT ⇒ V171 触发器的 INSERT 路径不违约；
不 SET NOT NULL 任何既有列；不改既有列取值域；新增 CHECK 全部只约束新列、对既有 MANUAL 行恒成立
（既不放宽也不收紧旧值域）。唯一的例外是**新增触发器**（append-only），
且只对 `source='PLATFORM'` 的行生效 ⇒ 对 4.1.1.1 / 4.1.1.2 零影响。

### 2. 🔴 冲突判定口径：双方对「当前是否在售」的主张**相反**才算冲突

服务层纯函数把两侧各自映射成可售性口径再比对：

| 内部 `listing.status` | 口径 | | 平台事件类型 | 口径 |
| --- | --- | --- | --- | --- |
| `ON_SALE` | SELLABLE | | `APPROVED` | SELLABLE |
| `UNDER_REVIEW` / `REJECTED` | **PENDING** | | `REJECTED` / `DELISTED` / `SUPPRESSED` | NOT_SELLABLE |
| `DRAFT` / `DELISTED_BY_*` / `BLOCKED` | NOT_SELLABLE | | `THROTTLED` / `CATEGORY_REVIEW` / `PRICE_ALERT` | **RESTRICTED** |

**冲突 = (内部 SELLABLE 且平台 NOT_SELLABLE) 或 (内部 NOT_SELLABLE 且平台 SELLABLE)。**

刻意排除的两种：

- 内部 **PENDING**（UNDER_REVIEW / REJECTED）而平台给出裁决 ⇒ **时序差**，不是事实矛盾
  （平台结论到了、内部生命周期还没跟上，那归 4.1.1.3）。不排除的话**每一次审核通过都会误报**。
- 平台 **RESTRICTED**（限流 / 类目审核 / 价格异常）⇒ 商品**仍在售**，与内部「在售」不矛盾。
  这三类进需处理队列，但**不进冲突告警**。

冲突结论落四个列：`conflict_detected` / `internal_status` / `platform_status` / `resolution`。
**前端不重算**（判定口径是业务规则，前端再实现一遍必然随版本漂移，而「哪一边说了算」错一次就是运营误操作）。

### 3. 🔴「有冲突必有告警」是**结构事实**，不是服务层的自觉

`listing_status_event` 的 **AFTER INSERT 触发器**（`guard_listing_status_event_conflict_alert`）：
见「`source='PLATFORM'` 且 `conflict_detected`」就**就地插一条 CONFLICT_ALERT 队列行**。
服务层不写这段代码也照样有告警 ⇒ 漏报在结构上不可能。
（与 V171 用触发器落人工状态事件同一手法：**漏记比多记更难发现**。）

集成测试里**手工插一条冲突事件**（绕开服务层）来证明这一点。

### 4. 🔴「以平台为准」但**不抢 4.1.1.3 的写入所有权**

`resolution='PLATFORM_WINS'` 表示**平台状态被当作事实来源**，但本模块**不改** `listing.status`：
内部生命周期联动归 4.1.1.3（未交付 ⇒ 能力表 `lifecycle_sync.available=false` + ownerModule + impact）。
**状态变更记录本身照常落库并展示** —— 不因为联动没做就不记录。
集成测试断言：冲突之后内部 `listing.status` **一字未改**。

### 5. 🔴 一键处理走 2.5.1.1 的既有队列，幂等键由服务端派生

- 动作写成 V2 `platform_operation`（`operation='PUBLISH'` + `payload.statusRepublish=true`）——
  **复用既有取值域，不改 V2 的 CHECK**（那是 2.5.1.1 的对外契约）。
- 幂等键 = `LSR-<队列项 id>`，由**服务端**派生，**不接受调用方自拟**（自拟就能两次「不同键、同一个目标」，
  恰好绕开幂等）。插入走 `on conflict (tenant_id, idempotency_key) do nothing` ⇒ 并发也不会插出两条。
- **重复点「一键处理」不产生第二个平台任务**：已派发过的**逐条写明原因**跳过。
  🔴 这里踩过一个真坑：最初把「已派发」写成了 SQL 的 `state` 过滤条件 ⇒ 那批条目被**静默滤掉**，
  回执说"命中 0 条"而真相是"早就发过了"。改成**两条查询**（可发起的 / 已处理过的）分别统计后才正确。
- **限流 / 类目审核 / 价格异常不参与重发**（盲目重发解决不了限流，那三类进「需人工处理」）。

### 6. 需处理队列三类分开：REPUBLISH / REVIEW / CONFLICT_ALERT

一条「平台说已下架、内部还说在售」的事件**同时**产生一条 REPUBLISH（要重新上去）与一条
CONFLICT_ALERT（事实矛盾告警）—— 两者是**不同的待办**，不该塞进一行（`UNIQUE(tenant_id,event_id,kind)`）。
驳回原因在队列表上**冗余一份**并由触发器钉住与事件**逐字相同**（「按驳回原因筛选」走这一列的索引，
不必回表 join 事件表）。

## 涉及文件

**后端（channel）**

- `backend/services/channel/src/main/resources/db/migration/V199__listing_status_sync.sql`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingStatusSyncModels.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingStatusSyncService.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingStatusSyncController.java`（新建）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingStatusEventIntegrationTest.java`（新建，27 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingStatusEventTenancyIntegrationTest.java`（新建，4 例）

**前端（web）**

- `apps/web/src/app/listings/status-sync/page.tsx`（新建）
- `apps/web/src/components/listing-status-sync/listing-status-sync-model.ts`（新建）
- `apps/web/src/components/listing-status-sync/listing-status-sync-workspace.tsx`（新建）
- `apps/web/src/components/listing-status-sync/listing-status-sync-model.test.ts`（新建，23 例）
- `apps/web/src/app/api/channel/listing-status-sync-allowlist.test.ts`（新建，4 例）
- `apps/web/src/lib/api-proxy.ts`（**纯增量**新增一条 `listing-status-events` 元素，
  锚点固定在 `listing-claims` 之后、4.4.2.2 批量搬迁之前）

**契约与台账**

- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts`
  （codegen 产物，**未手改**；其余五个服务的契约逐字节不变）
- `docs/product/capability-blueprint/module-progress.md`（只改 4.3.2.2 那一行的「状态」「证据」两格）

## 验证

- L0 编译闸：`./mvnw -q compile` ✅ · `pnpm typecheck` 3/3 ✅ · `pnpm lint` 3/3（无新增 warning）✅
- L1 模块闸：`./mvnw -pl services/channel -am test -Dtest='ListingStatusEvent*'` → **31 例全绿**（0 失败 0 错误）✅
  （含多租户三断言：两租户互不可见 —— 按主键直查**用 `channel_app` 连**，超级用户绕过 RLS 会让断言永远「通过」；
  伪造 `X-Tenant-Id` 头无效；连接池压到 1 后 A→B→A 三轮不串租户）
- 回归：`-Dtest='Listing*,OpenApiContractTest'` → **209 例全绿**（含 4.1.1.1 / 4.1.1.2 / 4.2.1.1 /
  4.2.1.2 / 4.3.2.1 / 4.4.2.1 / 4.4.2.2 与本模块）⇒ 确认对 `listing_status_event` 的 ALTER
  **没有打破任何已交付行为**
- 全量 allowlist 回归（因改了 `api-proxy.ts`）：
  `apps/web/src/app/api/trade` 42 文件 224 例 ✅ · `apps/web/src/app/api/channel` 22 文件 102 例 ✅

## 后续待办

1. **4.1.1.3 生命周期与下架**交付后接上内部状态联动（当前只记录 + 告警，**不改** `listing.status`）。
2. **02 平台适配器**接入真实平台 webhook 推送、**02 同步**接入轮询调度器后，
   由它们调用 `POST /channel/listing-status-events/events`（`ingestChannel` 记 WEBHOOK / POLL）。
3. **4.3.1.3 发布任务**交付后，修正重发的下游任务视图接上（当前只展示 02 队列任务 id 与其状态）。
4. **16.2.1.2 拦截执行**交付后，由它判定「价格异常 / 被抑制」是否升级成合规拦截
   （本模块只如实记录与展示这两类平台通知，**不擅自**把一条通知变成一个拦截动作）。
