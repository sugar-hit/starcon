# 2026-10-15 10.1.1.3 时效与限制

## 决策摘要

- **归属 channel 服务**，迁移 `V139__logistics_transit_and_limits.sql`（版本号由调度器预分配）。
- 蓝图职责句逐项落点：
  - 渠道时效（分国家分重量段）→ `logistics_transit_rule`（分国家 × 分重量段 × 服务等级）
  - 尺寸上限 / 重量上限 → `logistics_size_limit`（渠道级 `country_code IS NULL` + 国家级，判定**取最严**）
  - 电池 / 液体 / 危险品标记要求 → `logistics_marking_requirement`
  - 禁限品类 / 电池 / 液体 / 磁力 → **沿用 V136 的 `logistics_channel_restriction`，不重建**
  - 时效估算 / 限制命中提示 → `POST /estimate`、`POST /precheck`
- 端点挂在**新的** `/channel/logistics-transit-limits/**` 命名空间下，不往 `logistics-channels`
  的 alternation 里并分支（并进去会与同批 channel 车道结构性冲突）。
- 前端页面 `/logistics/transit-limits`（五个面板：时效规则 / 尺寸上限 / 标记要求 / 预检 / 估算）。

## 关键 trade-off

1. **重量段：闭区间 → 半开区间 [下限, 上限)**（本次唯一一次设计返工）
   - 初版按「两端都含」设计 `[0.5, 30]`，结果 trigger b 把**相邻段** `[30, 45]` 也判成重叠 ——
     闭区间下 30kg 同时命中两段，估算结果取决于查询顺序。
   - 改成半开区间、上限可空（NULL = 最后一段）后：既无歧义，也没有「为了避缝而留
     [0,0.4] / [0.5,30] 这种取整假缺口」的副作用。代价是**每个重量值只会命中一段**这条
     约束要写进测试（已钉住 0.5 / 12.345678 / 29.999999 命中，30 落到下一段）。
2. **禁限品类不在本模块重建表**：V136 已经是它的落点且判定端点已交付，两处各存一份必然分歧，
   分歧的表现正是蓝图加粗句要避免的「被承运商拒收」。本模块**新增**的是 V136 确实没有的两类：
   危险品（`DANGEROUS_GOODS`）与「必须贴标识」这一层（`LABEL_REQUIRED`）。
   一致性由 trigger e **只读**对照上游表保证（上游判 BLOCK 的 flag 只允许 BLOCK）。
3. **「必须贴标识」判 REVIEW 而不是 PASS**：不拦，但不允许自动放行 —— 漏打标识会在清关出问题，
   且 10.2.2.2 面单模板要靠这一层绑定打印规格。
4. **命中不到重量段就返回 0 条并显式说明**，不拿相邻段凑一个数（凑出来的时效会被刊登出去）。
5. **预计到达不做时区换算**：承运商口径是当地日历，本模块没有承运商时区数据源，凭空换算只
   会引入一个看起来更精确、实际是编出来的偏移。
6. **上游 3.7.2 SKU 安全属性以入参接收**：channel 服务一条 SQL 都不碰 catalog 的库，
   capabilities 里该维度显式 `available=false` + ownerModule + 影响。

## 文件清单

**迁移**
- `backend/services/channel/src/main/resources/db/migration/V139__logistics_transit_and_limits.sql`

**后端**
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsTransitModels.java`（新增）
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsTransitService.java`（新增）
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsTransitController.java`（新增）
- `backend/services/channel/src/test/java/com/starcon/channel/LogisticsTransitIntegrationTest.java`（新增，27 例）
- `backend/services/channel/src/test/java/com/starcon/channel/LogisticsChannelIntegrationTest.java`
  （**改了 6 行**：truncate 夹具必须先清掉本模块三张表，否则 `truncate logistics_channel_config`
  被外键引用拒；这是 V139 的复合 FK 带来的必然后果，不是对 10.1.1.2 行为的改动）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/channel.openapi.json`
- `packages/api-contracts/src/contracts/channel.ts`（+21 个类型，纯增量）
- `packages/api-contracts/src/trade.*` 曾被 codegen 顺带动到（header 注释位置差异，非本模块），
  已 `git checkout` 还原，保证本车道提交只含自己的契约变更。

**前端**
- `apps/web/src/app/logistics/transit-limits/page.tsx`（新增）
- `apps/web/src/components/logistics/logistics-transit-model.ts`（新增）
- `apps/web/src/components/logistics/logistics-transit-model.test.ts`（新增，28 例）
- `apps/web/src/components/logistics/logistics-transit-workspace.tsx`（新增）
- `apps/web/src/app/api/channel/logistics-transit-allowlist.test.ts`（新增，13 例）
- `apps/web/src/lib/api-proxy.ts`（**纯增量**一条 services[] 元素，锚在 `prefix: 'trade/'` 之前）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（只改 10.1.1.3 一行的「状态」「证据」两格）

## 验证

- L0：`./mvnw -q compile` 绿；`pnpm typecheck` 三包绿；`pnpm lint` 无新增告警
- L1：`./mvnw -pl services/channel -am test` → **151 例零回归**（本模块 27）
- 前端：`pnpm vitest run apps/web/src/app/api/channel/ apps/web/src/components/logistics/logistics-transit-model.test.ts` → **41 例绿**
  （含 10.1.1.2 既有白名单回归，证明本车道的新 api-proxy 条目没误伤别人）

## 后续待办

1. **合并到集成头后重跑 channel 全量**：本车道改过 `LogisticsChannelIntegrationTest.truncateAll()`，
   同批其它 channel 车道若也动了这个文件，按「并入驱动器取并」需人工确认这三张表名还在列表里。
2. `apps/web/src/app/api/channel/logistics-channel-allowlist.test.ts` 里有一行注释写着
   「同 L3 但未开发的 10.1.1.3 时效与限制」，现在已交付（但形状不同：本模块走顶层
   `logistics-transit-limits/*`，不是 `{uuid}/transit-times`），断言仍然成立（那些形状确实 404），
   本车道**刻意没改**别人的测试文件以免并入冲突，合并后可顺手更新该注释。
3. 下游可接：10.1.2.1 运费试算、10.1.2.2 渠道比价、4.2.3.2 合规预检、5.3.1.1 测算、10.6.2.1 时效分析
   —— 它们需要的「时效承诺 / 限制命中」两个输入现在都有服务端端点了。
