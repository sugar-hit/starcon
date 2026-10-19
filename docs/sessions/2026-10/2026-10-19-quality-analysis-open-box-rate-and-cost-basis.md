# 11.5.2.1 质量分析 —— 交付记录

- **日期**：2026-10-19
- **车道**：`feat/11-5-2-1`（worktree `.worktrees/11.5.2.1`）
- **归属服务**：`trade`（D7 质量分析）

## 决策摘要

**1. 落 trade 而不是分析服务，因为输入句的两项上游都在同一个库里。**
蓝图关系句写死「上游 = 11.1.2.2、11.2.3.1」，而 11.2.3.1 质检结论是 V179、11.1.2.2 归因是 V180 ——
两张都在 **trade 服务的独立库**。所以本模块是**同库只读聚合**，全程不经任何跨服务调用。
这直接决定了迁移里 `return_quality_*` / `product_issue_*` 全部只读、零外键。

**2. 开箱率需要一张新表，因为上游没有任何一张表记「缺陷是什么时候被发现的」。**
V179 记的是「质检员在仓库判了什么」，V180 记的是「这事该谁负责」，
V172 的 `goods_condition` 记的是「货到我们手上时什么状态」—— 三者都发生在**实物回到卖家之后**，
而开箱率问的是**缺陷在买家手上就已经存在**。
反过来「拿入库状态反推开箱率」是把两个时点当同一个：买家开箱发现 3 件、入库时其中 1 件已被人修好，
内含推法会得出完全不同的数。

**3. 缺陷观察做成 1:1 挂一条 FAIL 检项，而不是自由记录。**
缺陷类型分布的事实来源是 V179 的 **FAIL 检项**（判 FAIL 才是缺陷，PASS 不是）。
若允许一条 FAIL 检项对应多条观察，分布与开箱率两个口径立刻分叉 ——
而 11.1.2.1 的蓝图早就写过「原因口径不统一会让退货率分析失真」。
1:1 之后分布的分母恒等于 FAIL 检项数。

**4. 活行唯一用部分唯一索引，不用表级 UNIQUE。**
作废是留痕、不是删除。表级 `UNIQUE(tenant_id, check_result_id)` 会让「记错了改一下」变成不可能，
于是运营只会去**就地改原来的行** —— 而那正是我们不想要的（不留痕）。
`WHERE observation_status = 'RECORDED'` 的部分唯一索引让「作废 → 重记走新行」成立，同时活行仍然只有一条。

**5. 质量成本的三项依据等级不同，逐项标注、绝不合成一个来源的总数。**
这条是蓝图加粗句「质量成本应量化」的真正含义。实测下来只有**损耗**是真的
（原值 − 残值两个真列）；**退货物流**在任何表里都没有金额
（`rma_authorization` 只有 carrier / tracking_no / shipped_at，`rma_label` 只有重量长宽高）；
**差评影响**更根本 —— 全库没有商品评价表。
编一个「行业平均差评损失率」乘退货金额，就是把凭空假设报成测算结果。
所以参数做成**租户可维护的真实数据**，报表逐项标 MEASURED / ESTIMATED / NOT_REGISTERED。

**6. 参数未登记时金额是 `null` 而不是 0，且被排除在合计之外。**
按 0 算出来的总成本会被读成「质量没花钱」，而**偏小的质量成本正好是「改进动力不足」的那个方向**。
响应里 `excludedItemCount` 一并报出，让「这个合计少算了什么」在界面上说得清。
契约里 `CostItem.amount` 因此必须标成 `number | null` —— 写成非空 number，
前端就只会被迫把「算不出来」渲染成 0。

**7. 开箱率必须与覆盖率并排。**
分母只取**已登记观察**，而「已登记观察数 ÷ 期间内 FAIL 检项数」才是「这个数有多完整」。
覆盖率 100% 时的开箱率才是完整的开箱率；40% 时只代表「已登记的那 40%」，
剩下的沉默不能被读成「没开箱问题」。
**签收时发现刻意不计入分子**：货到买家手上时有没有被拆开验过没有任何证据。

**8. 照片聚类按内容指纹，不做图像相似度。**
沿用 11.1.2.2 在 `product_issue_photo_comparison` 上已经钉过一次的同一决定。
本仓没有图像库、没有模型、没有第三方视觉服务接入点。
同一件商品在不同光线角度下拍的两张照片指纹必然不同，编一个 0.87 就是冒充图像识别能力。
所以这个报表的诚实读法是「**哪些照片被反复上传**」，不是「外观相似的缺陷有多少类」。

**9. 供应商走 trade 侧镜像表，不 join catalog。**
供应商主数据在 catalog 独立库，AGENTS.md 禁止跨服务 SQL / FK / 共享视图。
沿用 11.1.2.2 处理 `platform_enum_mapping` 的**同一条既有先例**建同形状的镜像表。
批次上只有 `lot_supplier_ref_id`（uuid），**未登记名称时 `supplierName` 返回 `null`**，
由界面显示「未登记供应商」—— 把 uuid 当名字渲染，运营会照着它去联系一个根本不存在的供应商。
DELETE 已从运行角色收回：同步器写回来会复活被删的行，于是「明明停用了却又生效」。

## 关键 trade-off

| 选择 | 代价 | 为什么值得 |
| --- | --- | --- |
| 开箱率新建一张表 | 多一个写入点 | 上游确实没有这个事实；用推法算出来的数不可复现 |
| 观察 1:1 挂 FAIL 检项 | 每个失败检项都要登记 | 两个口径不分叉，比「允许自由记录再想办法对齐」便宜 |
| 成本三项分别标注 | 报表看起来没有「一个总数」 | 把估算混进实测的总数会被当财务确认数去谈索赔 |
| 供应商镜像表 | 要人工/同步器维护名称 | 比跨库 join 便宜；未登记时显示 null 而不是 uuid |
| 批次趋势用 UTC 分桶 | 与租户本地时区差一截 | 会话时区分桶会让同一份数据换个连接池连接就多出空桶 |

## 文件清单

**迁移**
- `backend/services/trade/src/main/resources/db/migration/V185__trade_quality_analysis.sql`

**后端**
- `backend/services/trade/src/main/java/com/starcon/trade/QualityAnalysisModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/QualityAnalysisService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/QualityAnalysisController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/QualityAnalysisIntegrationTest.java`（22 例）
- `backend/services/trade/src/test/java/com/starcon/trade/QualityAnalysisTenancyIntegrationTest.java`（3 例）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/contracts/trade.ts`
- `packages/api-contracts/src/trade.openapi.json`

**前端**
- `apps/web/src/app/aftersales/quality-analysis/page.tsx`
- `apps/web/src/components/aftersales/quality-analysis-model.ts`
- `apps/web/src/components/aftersales/quality-analysis-model.test.ts`（14 例）
- `apps/web/src/components/aftersales/quality-analysis-workspace.tsx`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（只改 11.5.2.1 那一行的状态与证据）

## 验证方式

- L0：`./mvnw -q compile`（backend）+ `pnpm typecheck`（前端）
- L1：`./mvnw -pl services/trade -am test -Dtest='QualityAnalysis*'` → **25 例全绿**
  （22 功能 + 3 条租户隔离断言：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户）
- 前端：`pnpm vitest run apps/web/src/components/aftersales/quality-analysis-model.test.ts` → **14 例全绿**
- ⚠️ 改了 `apps/web/src/lib/api-proxy.ts`，因此**跑了全量 allowlist 回归**
  `pnpm vitest run apps/web/src/app/api/trade` → **41 文件 / 218 例全绿**

## 后续待办

1. **6 采购同步器**（不在本车道）：按 `quality_supplier_ref` 的同形状把供应商主数据同步过来，
   否则供应商对比长期显示「未登记供应商」。
2. **6.1.1.3 供应商评级**（未交付）：本模块已备好对比数据（生产责任占比、被标记批次、改进条数），
   评级打分与升降档不在这里。
3. **6.4.2.2 供应商索赔**（未交付）：举证材料（归因结论 + 批次标记）在 11.1.2.2 那边。
4. **缺陷观察的批量登记**：当前一次只能登记一条（1:1 约束）。
   一批退货通常有十几条 FAIL 检项，逐条登记会拖慢开箱率的时效。
5. 契约漂移（**非本车道**）：本次 codegen 发现 `packages/api-contracts/src/contracts/channel.ts`
   相对集成头**缺少一批 channel 服务已产出的类型**。本车道已 `git checkout` 还原，
   避免把别的车道的产物算进本车道提交；应由 channel 侧车道（或调度器 L2）处理。
