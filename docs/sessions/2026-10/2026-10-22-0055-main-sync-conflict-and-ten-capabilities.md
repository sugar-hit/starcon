# 2026-10-22: 集成分支并入 main（10 项能力交付同步）

## 摘要

把 `integration/module-dev` 的最新交付经 Pull Request 合入 `main`。本轮同步了 10 项新功能，
覆盖采购与供应、库存核算、售后与退货、广告与增长四个业务系统。合并过程有 5 个文件真冲突，
逐个按证据判定后解决；验证走「定向 + 冲突面专项 + 后端全量」三级。

## 决策摘要

- **合并形态 = 真合并且有冲突。** `main` 是 squash 合入的，提交数口径虚报 398 个提交，
  内容口径实为 139 个文件变更。合并结果树与 `main` 树不同 ⇒ 本轮确有内容可交付，不是空操作。
- **5 个冲突文件里 4 个取集成分支侧。** 判定依据是「`main` 侧那份 blob 在集成分支自己的历史里
  逐字节出现过」——即线性演进而非真分叉，取演进终点不丢内容。这 4 个是：库存调整页面、采购申请
  模型与服务、库存调整服务。
- **`api-proxy.ts` 人工求并后仍取集成分支侧。** 该文件是累积型白名单，逐条抽取后逐层证明：
  - 120 条（`main`）→ 129 条（集成分支）；
  - 119 条的 prefix、pattern、env、fallback **四元组逐字节相同**；
  - 唯一改写的那条（仓储库存主正则）内层 alternation 分支 167 → 179，**旧分支丢失 0 条**；
  - 有序子序列成立（`resolve()` 是 first-match-wins，顺序不能被打乱）；
  - `env` / `fallback` 零变化（路由目标没有被改写）。
    ⇒ 集成分支侧是严格保序超集，取它等同完整求并。
- **台账用生成器重新拉齐。** 合并驱动的行级兜底求并保留了正确的 465 条模块行，但把派生汇总表
  留在了旧值（已交付 287 / 完成率 61.7%）。跑一次生成器后，汇总表变为 297 / 63.9%，
  **与集成分支的台账逐字节相同**，且 465 条模块行前后零差异 ⇒ 重新生成是无损的。
- **格式债零新增。** 相对 `main` 基线新增的 29 个格式失败文件**全部继承自源分支**，
  合并本身没有制造任何一笔新债；这部分债属于集成分支，不在同步任务里顺手修。

## 关键 trade-off

1. **台账重新生成 vs 保留合并驱动产物** ⇒ 选了重新生成。驱动产出保留了正确的模块行，
   但派生汇总表陈旧；`sync.mjs --check` 是逐字节比对，汇总表陈旧就必然红。重新生成前后
   465 条模块行零差异，可以证明这一步只动派生表与行内空白，不动交付状态。
2. **白名单取一边 vs 人工求并** ⇒ 取集成分支侧。前提是「严格保序超集」被证到分支级：
   只比集合包含与零删除不够——既有正则被改写时整行文本就变了，零删除会假红，
   必须退到 alternation 分支粒度去比，并补上 env/fallback 与有序性两条独立证据。
3. **同步轮里修格式债 vs 不修** ⇒ 不修。源分支的债在源分支修；同步轮里修会让 PR 分支偏离
   源分支，下一轮三方合并还要再冲一次冲突。

## 涉及文件

- 冲突解法：`apps/web/src/lib/api-proxy.ts`、
  `apps/web/src/app/inventory/adjustments/page.tsx`、
  `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestModels.java`、
  `backend/services/trade/src/main/java/com/starcon/trade/PurchaseRequestService.java`、
  `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentService.java`
- 台账：`docs/product/capability-blueprint/module-progress.md`
- 契约产物：`packages/api-contracts/src/{catalog,channel,trade,warehouse}.openapi.json`
  与 `packages/api-contracts/src/contracts/*.ts`
- 新增迁移：`backend/services/{trade,channel,warehouse}/src/main/resources/db/migration/`
  的 V266–V276 共 11 个文件

## 验证结果

- **后端全量**：本地 `mvnw verify` BUILD SUCCESS，8 个模块全部成功，445 个测试类，
  Failures 0 / Errors 0，耗时 36 分 47 秒。
- **前端单元测试**：424 个测试文件、6379 个用例全部通过；类型检查通过；ESLint 通过（仅既有告警）。
- **代码生成测试**：17 例全部通过。
- **台账**：465 个模块条目、编号零重复，与蓝图一致。
- **白名单行为探针**：旧侧的 37 条分支各采样一条具体路径，改写后全部仍然命中，反向用例仍然拒绝。
- **远端流水线**：本轮的失败集合与主分支基线**完全一致**，未出现新增失败作业，也未出现新增失败用例。
  代码规范检查与后端集成的失败项均与基线持平（分别是仓库级格式债与 identity 服务的一例既有缺陷），
  端到端作业的失败来自 channel 服务启动异常，与本轮改动无关。

> ⚠️ 上一轮合入主分支的提交正文把「本地跑过」写成了「通过」，与流水线的真实结论不符。
> 本节是准确的验证结论，**以本节为准**。

## 后续待办

- 集成分支侧的 Prettier 格式债（29 个文件）由源分支自行清理，本仓不做跨分支代劳。
- `main` 基线自身的三项存量失败（仓库级格式债、identity 一例集成失败、端到端用例失败）
  仍然存在，需要单独立项，不在同步轮范围内。

## 关联

- 同步协议：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`
- 功能进度台账：`docs/product/capability-blueprint/module-progress.md`
- 平台对接与多租户边界：`docs/architecture/multi-tenancy.md`、
  `docs/decisions/0002-multi-tenancy-strategy.md`
