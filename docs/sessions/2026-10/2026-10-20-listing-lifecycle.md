# 4.1.1.3 生命周期与下架（D5 阶段，L3 锚点 4.1.1）

> 车道 `feat/4-1-1-3`，worktree `.worktrees/4.1.1.3`，基线 `integration/module-dev` 的 `11f19237`。

## 决策摘要

蓝图 §4.1.1.3 的职责句里，真正吃重的只有两句加粗：

> **「平台下架与内部下架是两个事实」，分别记录并展示差异** —— 平台把商品下架了而内部不知情，是最常见的漏单原因。

所以本模块的第一件事不是写状态机，而是**先把「两个事实」变成结构**，让它们不可能被混掉：

1. **事件来源是 DB 事实，不是应用层约定**。`listing_status_event.source`
   （MANUAL / PLATFORM）在 V171 建表时就预留好了 'PLATFORM'，V199 又把它扩展成一整组
   平台语义列。**事件记录始终由 V171 的 AFTER UPDATE 触发器落** ——
   服务层只 `UPDATE listing.status`，**从不直接 INSERT 事件表**（直接插会与触发器重复记，
   也会编出一条事实）。
2. **人工动作的意图另落一张 `listing_lifecycle_action`**（谁 / 为什么 / 是否回写平台 /
   关联到哪个平台任务 / 响应哪条平台事件）。事件表回答「状态变成什么了」，
   这张表回答「谁为什么这么干、平台知不知道」—— 那是**两回事**。
3. **差异区独立成一块并置顶**：四类差异逐条列出并写明两边状态，判定在 SQL 侧做，
   所以差异区能**真分页**。

第二个吃重的地方是**状态的写入所有权**。上一批的 4.3.2.2（平台状态回流）明确把
「内部生命周期联动」声明成 `available=false` + `ownerModule=4.1.1.3`，
并在能力表里写「本模块**不抢** `listing.status` 的写入所有权」。本模块接上了这一段。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 平台联动的事件来源怎么记 | **改 V171 的触发器函数**（`CREATE OR REPLACE`，不是 ALTER TABLE） | V171 把 `source` 硬写成 'MANUAL'。不改的话「平台说已下架、内部状态随之联动」会被记成**人工下架** —— 那正是蓝图要消灭的谎。改成读事务级 GUC `app.lifecycle_context`，**GUC 没设时落库的值与原 INSERT 逐字段相同** ⇒ 4.1.1.1 / 4.1.1.2 零回归（集成测试专门钉了这一条） |
| 状态机要不要新建表 | **不建** | 七态 CHECK 早在 V171 的 `listing.status` 上；事件由 V171 的触发器落。再建一套就是「一个事实两个来源」 |
| 人工动作的意图放哪 | **新建 `listing_lifecycle_action`** | 事件表没有位置放「是否回写平台」这个**选择**。没有它，「这次下架为什么没告诉平台」答不出来 |
| 状态机的人工路径与平台路径 | **人工严格、平台宽松** | 平台说的是**事实**，内部状态要做的是跟上而不是拒绝。把「平台已下架」判成非法迁移，后果就是蓝图点名的漏单。人工路径严格是因为错误点击有资损风险 |
| 非法迁移怎么表达 | **422 + 可读理由**（写明当前状态与合法后继） | 500 对运营毫无指导意义；「默默不动」比报错更糟（以为下架成功了） |
| 差异判定放哪 | **SQL 侧**（Java 侧另有一份等价实现，测试逐行比对） | 放 SQL 才能真分页。Java 侧**直接调用 4.3.2.2 的 `internalSellability` / `platformSellability`**，不复制口径 —— 两处各写一份必然漂移 |
| 「内部下架但没回写平台」算不算差异 | **算**，且分成两类 | 明确没回写（`writeback_requested=false`）与找不到动作留痕（多半走 4.1.1.1 的批量下架，那条路径不回写平台）—— 两者的处置不同，混成一句就没人管 |
| 主动下架回写平台走哪条路 | **复用 02 回写队列**（`writeback_batch` kind='DELIST' + 批次条目 + `writeback_target` + `platform_operation`） | 不新建平行下架任务表；`kind` 的 CHECK **不能动**（既有行的值域） |
| 重新上架给不给「回写平台」选项 | **不给** | `writeback_batch.kind` 值域里没有它，既有 CHECK 又不能动 ⇒ 平台侧上架动作归 4.3.1.2 / 4.3.2.2。宁可少给一个必然 500 的选项 |
| 回写平台默认勾不勾 | **默认不勾** | 回写会把平台上正在卖的商品下架（**有资损风险**），漏发的后果只是内部与平台暂时不一致 —— 保守默认永远是对的那一侧 |
| 时间怎么绑 | **ISO-8601 带偏移量的文本 + `?::timestamptz`** | 存进去的就是那个瞬间，与 JVM 时区无关。`Timestamp.valueOf(LocalDateTime)` 会静默偏 −28800s |
| 前端要不要自己判断「这条事件算不算内部状态变更」 | **不要**，服务端算好 `internalStateChange` 下发 | 判据（七态词表）会随版本变；两处各写一份必然漂移，而这一列直接决定时间线上那条事件被标成「我们改了状态」还是「平台这么说」 |
| 路径前缀 | **`/channel/listing-lifecycles`**（独立前缀） | 4.1.1.1 把 `listings/{uuid}/lifecycle` 显式登记成「不放行」，在同一棵路径树上开子资源要同时改别人已交付的白名单与拒绝清单 |
| 页面位置 | **`/listings/lifecycle`**（新建目录） | 与 4.1.1.1 列表、4.1.1.2 详情分开：读列表 / 读全景 / 改状态是三种通道，混在一页里会出现「这个下架按钮改的是列表还是平台」 |

## 未交付的上游（如实标注，不假装有）

| 段落 | 责任模块 | 本模块怎么表达 |
| --- | --- | --- |
| 平台操作任务的**实际派发** | 2.5.1.1 平台回写队列 | 本模块只**生成**任务（`platform_operation` 入队），不派发、不重试、不核验未知结果 |
| 重新上架的**平台回写** | 4.3.1.2 / 4.3.2.2 | `writeback_batch.kind` 值域里没有它，既有 CHECK 不能动 ⇒ 能力表 `available=false` + ownerModule，界面上不提供该选项 |
| 平台状态的**轮询调度器** | 02 同步运行时 | 本模块提供写入入口（单条 + 批量同步），但没有那个定时调度器 |
| **批量**生命周期动作 | 4.1.1.1 批量操作 | 4.1.1.1 的批量下架**不经过本模块的状态机与回写通道** —— 这正是差异区要显式提示的一种情况（`INTERNAL_DELISTED_NO_WRITEBACK_RECORD`） |
| 被封禁商品的**申诉工单** | 4.3.2.2 / 16.2.1.2 | 只把「被封禁」如实记成内部状态并留痕，不擅自把一个状态变成一个申诉工单 |

## 顺带修掉的真缺陷（记一笔）

- **集成测试原本可能用超级用户查「读不到对方数据」** —— Testcontainers 默认的
  `pg.getUsername()` 是超级用户，而超级用户**绕过 RLS**，用它查永远得到「读得到」：
  那不是隔离被破坏，而是**断言本身失效**（4.1.1.2 / 4.3.2.1 实测踩过）。本车道的
  直查全部改连 `channel_app`，并额外断言「本租户自己的数据必须看得见行」，
  证明那些零行是隔离、不是查询一直返回 0。
- **状态机拒绝理由一开始只写了中文标签**（「当前已经是「被封禁」」），运营/排障时
  看不到原始状态码。改成标签与**状态码同时出现**（「当前已经是「BLOCKED」（被封禁）」），
  单测里专门钉住「拒绝理由必含当前状态」。
- **列表行原本不携带合法动作**，页面要先点「时间线」才知道能做什么。改成服务端把
  `allowedActions` 直接算好下发，界面据��渲染按钮 —— 非法动作即使被构造出来，
  服务端也会显式拒绝并给出可读理由。

## 文件清单

后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V201__listing_lifecycle.sql`（新，一张新表 `listing_lifecycle_action` + 触发器函数扩展 + 3 条索引 + RLS + 授权，**零 ALTER 任何既有表**）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingLifecycleModels.java`（新，传输模型 + 七态 / 来源 / 动作取值域）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingLifecycleStateMachine.java`（新，纯函数状态机：人工路径严格、平台路径宽松）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingLifecycleService.java`（新，jOOQ 显式 SQL + 差异判定 + 平台联动 + 回写平台）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingLifecycleController.java`（新，`/channel/listing-lifecycles` 九个形状）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingLifecycleStateMachineTest.java`（新，12 例纯函数单测）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingLifecycleIntegrationTest.java`（新，19 例 Testcontainers 集成测试）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingLifecycleTenancyIntegrationTest.java`（新，3 例多租户三断言）

契约（codegen 产物，禁手改）

- `packages/api-contracts/src/channel.openapi.json`（重新生成：路径集 +9、schema +15、**删除 0**）
- `packages/api-contracts/src/contracts/channel.ts`（同上；其余 5 个服务**逐字节不变**）

前端

- `apps/web/src/components/listing-lifecycle/listing-lifecycle-model.ts`（新，纯函数模型 + 取数）
- `apps/web/src/components/listing-lifecycle/listing-lifecycle-model.test.ts`（新，21 例单测）
- `apps/web/src/components/listing-lifecycle/listing-lifecycle-workspace.tsx`（新，真实工作区）
- `apps/web/src/app/listings/lifecycle/page.tsx`（新，页面路由）
- `apps/web/src/app/api/channel/listing-lifecycle-allowlist.test.ts`（新，白名单双向断言）
- `apps/web/src/lib/api-proxy.ts`（**纯增量**：新增一条 `listing-lifecycles` 元素，插在 4.1.1.1 `listings` 那条之后，既有分支一字未动）

台账与文档

- `docs/product/capability-blueprint/module-progress.md`（只改 4.1.1.3 那一行的「状态」「证据」两格）
- `docs/sessions/2026-10/2026-10-20-listing-lifecycle.md`（本文件）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 通过 |
| L0 类型 | `pnpm typecheck` | 通过（3/3 task） |
| L1 模块测试 | `./mvnw -pl services/channel -am test -Dtest='ListingLifecycle*'` | **34 例全绿**（12 单测 + 19 集成 + 3 租户） |
| 前端模块测试 | `pnpm vitest run apps/web/src/app/api/channel/listing-lifecycle-allowlist.test.ts apps/web/src/components/listing-lifecycle` | **25 例全绿** |
| 白名单全量回归 | `pnpm vitest run apps/web/src/app/api/channel apps/web/src/app/api/trade` | **369 例全绿**（69 个文件） |
| Lint | `pnpm lint` | 通过（仅既有模块的既有 warning，本车道 0 条） |

## 后续待办

- **平台任务派发**（2.5.1.1）接上之后，本模块生成的 `writeback_batch` 才会真的发往平台；
  在那之前，界面上「已回写平台」指的是**已入队**，不是**平台已执行**。
- **重新上架的平台回写**：等 02 回写队列的 `kind` 值域能容纳它（或由 4.3.1.2 /
  4.3.2.2 承接）之后，本模块可以把「重新上架 + 回写」补成一个真正的动作。
- **4.1.1.1 的批量下架**若要也回写平台，需要 4.1.1.1 那一侧改走本模块的通道
  （本车道**刻意未改**别的车道已交付的文件）；在那之前，差异区会把这一类显式提示出来。
