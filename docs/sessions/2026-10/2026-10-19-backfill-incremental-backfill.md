# 2.6.2.1 增量补拉（阶段 D5 · 车道 2.6.2.1）

日期：2026-10-19 · 分支 `feat/2-6-2-1` · 基线 `integration/module-dev`（8300d842）

## 决策摘要

在 channel 服务新增**增量补拉**：webhook 漏推 / 平台故障恢复 / 时间窗缺口时，按「连接 + 站点 +
资源类型 + 时间窗」主动补拉订单、商品、库存、结算资源，填补缺失区间。

蓝图四条硬要求逐条落成机制，不是落成注释：

| 蓝图要求 | 落地 |
| --- | --- |
| 补拉必须幂等 | `backfill_record` 唯一锚点 `(tenant_id, connection_id, resource_type, external_key)`，抢到 = CREATED，抢不到 = 比指纹 → DEDUPLICATED / UPDATED。**两条路径都不产生第二行** |
| 只给「最近 N 条」时分段轮询 + 去重兜底 + 页面标明「补拉可能延迟发现」 | `pagination_mode=RECENT_N_SCAN` **由 CHECK 强制** `delayed_discovery=true`；`backfill_segment` 逐段留痕（水位单调递减 = 往回够的证据）；页面顶部横幅 |
| 拉取 / 新增 / 更新 / 去重四个数字分开记 | 四个独立列 + 触发器 `guard_backfill_counts` 保证 `拉取 = 新增+更新+去重+失败` |
| `idempotency_key` 是输入契约 | `(tenant_id, idempotency_key)` 唯一索引；重复发起返回既有任务 ID |

## 关键 trade-off

**1. UPDATED 与 DEDUPLICATED 必须分开 —— 这是本模块最容易写错的一处**
蓝图只说「靠去重兜底」，但兜底若一味判重复，「平台改了这条订单」就会被当成重复推送丢掉。
因此判定链是三段：唯一索引抢不到 → 比 `payload_digest` → 相同判去重、不同判更新。
测试 `refetchingTheSameRecordWithChangedContentIsAnUpdateNotASecondRow` 钉住这一条。

**2. 时间窗用左闭右开 `(start, end]` 而不是两端闭合**
相邻两段补拉在边界那条记录上会重复归属，「这段到底漏没漏」就永远说不清。

**3. 「补拉可能延迟发现」做成 DB CHECK 而不是前端提示**
蓝图要的是「要在页面上标明」。但如果只有前端横幅、后端允许写 `delayed_discovery=false`，
那这份记录就允许被当成一次完整补拉 —— 而它不是。把约束落在 DB 层，「假装完整」写不进去。

**4. 跨服务边界：本模块不碰下游**
下游是 09 订单 / 04 刊登 / 12 结算，属别的服务。因此本模块**不存金额、不存订单行**，
也不读它们的表、不建跨库 FK。职责收敛成「把缺失区间可靠幂等地重新捞一遍并交出去」。
`capabilities.downstream_dispatch` 显式 `available=false` + `ownerModule=9.1.1.1`。
同理**没有造假 HTTP 适配器**（照抄 `CarrierHandoverAdapter` 的诚实做法）：
执行端按批提交真实平台返回，记录由服务层判幂等并落库。

**5. 补拉跨 webhook 的去重锚点**
蓝图上游点名 2.6.1.1 接收记录。本模块用它的两处：① `webhook_receipt` 作为**漏推检测**的
判据来源（窗口内零推送 = 缺口候选）；② 幂等锚点是 `(连接, 资源类型, 外部记录 ID)`，
与平台是否用过 webhook 无关 —— 因为外部记录 ID 才是「不得产生第二张订单」的锚点，
而 webhook 的 `external_event_id` 是投递事件 ID，两者不是同一个键。

**6. 平台能力声明不入参、限频不假装实现了**
`platform.capabilities` 里没有任何「增量接口形态 / 分页上限 / 限频」字段，
而这三个恰恰是本模块唯一需要外部知识的判断。因此它们是**显式入参**（`pagination_mode`、
`page_size`、`rate_limit_per_minute`）而不是由代码推断：猜错的后果是「补拉看起来成功了，
其实只捞到最近几条」。分页上限在提交端强制（超限 400），限频作为**留痕**（`backfill_segment.rate_limited`
+ 段级 `duplicate_count`）—— 本仓库没有任何真实平台出口，假装实现了退避重试是自欺。

## 文件清单

后端
- `backend/services/channel/src/main/resources/db/migration/V173__incremental_backfill.sql`（新增，版本号由调度器预分配）
- `backend/services/channel/src/main/java/com/starcon/channel/BackfillModels.java`（新增）
- `backend/services/channel/src/main/java/com/starcon/channel/BackfillService.java`（新增）
- `backend/services/channel/src/main/java/com/starcon/channel/BackfillController.java`（新增）
- `backend/services/channel/src/test/java/com/starcon/channel/BackfillIntegrationTest.java`（新增，19 例）

契约（codegen 产物，未手改）
- `packages/api-contracts/src/channel.openapi.json`（重新生成）
- `packages/api-contracts/src/contracts/channel.ts`（重新生成，12 个 Backfill 类型）

前端
- `apps/web/src/components/channel/backfill-model.ts`（新增）
- `apps/web/src/components/channel/backfill-model.test.ts`（新增，13 例）
- `apps/web/src/components/channel/backfill-workspace.tsx`（新增）
- `apps/web/src/app/channels/backfill/page.tsx`（新增）
- `apps/web/src/lib/api-proxy.ts`（**仅新增一条独立 `channel/` 元素**，既有条目一字未动、不重排）

台账
- `docs/product/capability-blueprint/module-progress.md`（仅改本模块那一行的「状态」「证据」两格）

## 后续待办

1. **下游同步处理器未接入**：补拉记录目前停在 channel 自己的台账里，
   要真正落到 09 订单 / 04 刊登 / 12 结算需由各服务消费（走 outbox 事件）。本模块
   `capabilities.downstream_dispatch` 已显式标注 ownerModule=9.1.1.1。
2. **平台真实出口接通后**，补齐退避重试（当前只留痕 `rate_limited`，不假装实现了重试）。
3. **契约里的 trade 快照在本车道为陈旧状态**：重新生成时发现 `trade.openapi.json` 落后于
   trade 服务已提交的代码（缺 9.x 换货订单族的端点）。本车道**刻意不带上这个改动** ——
   它属于 trade 车道的契约再生成，混进来会让并入时在 `trade.openapi.json` 上产生巨大冲突。
   需要 trade 车道自己跑一次 codegen。
