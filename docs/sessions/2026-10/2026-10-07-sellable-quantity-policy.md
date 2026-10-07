# 2026-10-07 7.2.2.1 可售量口径（D3，L3 锚点 7.2.2）

> 车道 `7.2.2.1`，分支 `feat/7-2-2-1`，基线 `integration/module-dev`。
> 上游依赖已交付：7.1.1.1 库存总览、7.1.1.2 库存 bucket（V2 `stock_bucket`）、7.1.1.3 可用量计算、
> 7.1.2.1 流水台账、7.1.2.2 账实核对。

## 决策摘要

**这个模块的产出不是那几行配置，而是「回写之前先体检一遍」。**

蓝图把它定义为「平台库存回写缓冲是防超卖的最后一道防线：多店铺共享池时，缓冲不足会在同步延迟期间超卖」。
所以服务真正的返回值是 `PoolSafetyVerdict`，而不是一个 buffer_ratio 数字。
判定口径只有一套，写在迁移文件头与模型类注释里：

```
poolCapacity = Σ 合格可用（quality_status.role='SELLABLE'）+（口径含在途时）发往该仓的在途
memberListing_i = poolCapacity × (1 − bufferRatio_i) + oversellTolerance_i   （向下取整）
allocationRatio  = 成员数 − Σ bufferRatio_i        ← 完全由配置决定，与有多少货无关
headroom         = poolCapacity − Σ memberListing_i
```

`allocationRatio ≥ 1` 就是没有余量：两个店铺各留 50% 缓冲时恰好等于 1，任何同步延迟都会击穿。
这让「缓冲够不够」变成**配置问题**——名册 `/pools` 不需要任何库存数据就能给结论，
逐 SKU 的真实余量由 `/calculation` 给，两者同一套公式。

**「没有配置口径」是一个要显式处理的空状态，不是 0。**
`effective` 在没有命中任何档时返回 `policy=null` 而不是兜底成 0：
回写 0 = 立刻断货，回写满 = 超卖，两条路都比拒绝更糟。
`usedFallback` 也如实报出「用的是全租户兜底档」，因为兜底口径和店铺专属口径的风险面不同。

**口径分档即历史：一张表，不建变更历史表。**
与 17.5.1.1 业务参数 `business_parameter_override.effective_from` 同一套分档生效约定。
改口径 = 收口旧档的 `effective_to` + 开新档，已关区间的那些行**就是**历史，
因此不存在两张表各自漂移的可能。配套两个触发器把这件事钉在 DB 层：
`guard_sellable_policy_immutable`（内容列写定后不可改，只允许把 `effective_to` 从 NULL 收到一个值）
与 `guard_sellable_policy_interval`（同槽位生效区间不得重叠，并报出冲突档 id —— 重叠的区间在解析时没有唯一答案）。

**跨服务边界：店铺/渠道只用 `source_system + reference_id`，不建外键也不查 channel 的表。**
蓝图上游写的是「2.3 店铺」，店铺表在 channel 服务。照 identity V48 的先例只存逻辑 ID + 写入时快照的可读名。
代价是本服务无法校验店铺是否真实存在（由前端选店铺时从 `/channel/shops` 取来保证），换来两个服务部署不被绑死在同一个 PG 实例上。

**FBA 分类标注在口径侧，不去改 `warehouse` 表。**
「平台可售量按自有仓还是含 FBA」要可计算就需要仓的类型，但 `warehouse` **没有 type 列**，
而全局仓库分类归 7.1.3.1 那条车道（本轮 V55）。所以 `sellable_policy_warehouse.warehouse_kind`
逐条标注 OWN / PLATFORM_FBA：口径为 `OWN_WAREHOUSE` 时 FBA 不计入池容量，且**被排除的量如实报出**，
否则用户会以为 FBA 已被计入。边界更清楚，也没有去动别的车道拥有的语义。

## 关键 trade-off

- **池容量聚合 `inventory_balance` 而不是 `stock_bucket`。** 7.1.1.3 的措辞是 bucket 口径，
  但 V1 把 balance 定为「权威可用量」，V2 保证「存在 bucket 时 Σbucket == balance」，
  而「还没拆到库位」的 balance 行（7.1.1.2 的未分解）是合法状态。只聚合 bucket 会把它们漏成 0 ——
  那是少卖不是超卖，但仍然是错的数。按 balance 聚合是全集，两者在有 bucket 处数值完全相同。
- **超卖容忍给件数不给比例。** 超卖是绝对量（超卖 3 件就是多接 3 单），
  给比例会在池子变大时自动放大容忍量，那不是运营想要的。
- **写入权限用 `warehouse.inventory.update` 而非 read。** 缓冲比例与超卖容忍是经营决策，配错直接超卖；
  把「改防超卖的最后一道防线」挂在读权限上，那道闸门等于没有。
- **端口只暴露 `calculate`（试算），不接平台回写。** 04 回写（4.3.2.1）尚未交付；
  本模块交付的是它要消费的那份口径与判定，回写由那条线接。
- **`IN` 占位符与 cast 的绑定纪律。** 踩了两次同一个坑的变体：
  一次是 `with win as (...) members as (...)` 漏逗号（语法错误），
  一次是 insert 的 values 占位符与列顺序错开一位。后者 PG 报的是
  「invalid input syntax for type numeric」「cannot cast type boolean to numeric」或「未设定参数值 N」，
  **没有一条指向真正的原因（列顺序）**。修法不是数占位符，而是让 `POLICY_COLUMNS` 与 `POLICY_CASTS`
  两个数组生成同一句 SQL，长度不一致在拼 SQL 时就炸 —— 这个结构留在了代码里。

## 文件清单

| 类别 | 路径 |
| --- | --- |
| 迁移 | `backend/services/warehouse/src/main/resources/db/migration/V56__warehouse_sellable_policy.sql` |
| 模型 | `backend/services/warehouse/src/main/java/com/starcon/warehouse/SellablePolicyModels.java` |
| 服务 | `backend/services/warehouse/src/main/java/com/starcon/warehouse/SellablePolicyService.java` |
| 端点 | `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryController.java`（9 个新端点） |
| 测试 | `backend/services/warehouse/src/test/java/com/starcon/warehouse/SellablePolicyIntegrationTest.java`（17 例） |
| 页面 | `apps/web/src/app/inventory/sellable-policies/page.tsx` |
| 组件 | `apps/web/src/components/inventory/sellable-policy-workspace.tsx` |
| 前端模型 | `apps/web/src/components/inventory/sellable-policy-model.ts` |
| 代理放行 | `apps/web/src/lib/api-proxy.ts` |
| 契约 | `packages/api-contracts/src/catalog.ts`（+14 类型，−0）、`warehouse.openapi.json` |
| 台账 | `docs/product/capability-blueprint/module-progress.md` 第 7.2.2.1 行 |

## 过程中发现的一个既有现象（非本模块引入）

`packages/api-contracts/src/identity.openapi.json` 在本轮 codegen 后 operationId 发生位移
（如 `/identity/business-parameters/overrides` 的 `create_5` → `create_7`）。
用 stash 回到干净基线重跑 identity 的 `OpenApiContractTest`，结果**同样是 `create_7`**，
说明提交在版本库里的那份 identity 快照本来就是旧的，与本模块无关。
类型名集合增删为 0，本模块的 14 个新类型互不重名。

## 后续待办

- 04 回写（4.3.2.1）接本模块的 `/calculation`：回写前必须先看 `verdict`，
  `OVERSOLD` / `NO_HEADROOM` 应当拒绝而不是照写。
- 7.1.1.3 可用量计算接本模块的策略半边：现在它按「扣一次安全缓冲」表述，
  具体比例来自这里的口径，两边应对齐同一套 `bufferRatio` 语义。
- 7.2.2.2 安全库存会用到 V1 `inventory_policy` 上预留的 `safety_stock` / `reorder_point` 列，
  与本模块的缓冲比例**不是同一个东西**（一个防断货、一个防超卖），不要合并。
- 若将来 channel 侧提供店铺读取适配器，本模块可改为校验 `reference_id` 真实存在；
  在那之前只能靠调用方保证，这一点已写进迁移文件头。
