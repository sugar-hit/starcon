# 4.4.2.2 批量搬迁（D5 阶段，L3 锚点 4.4.2）

> 车道 `feat/4-4-2-2`，worktree `.worktrees/4.4.2.2`，基线 `integration/module-dev` 的 1bb270a0。
> 迁移版本号 **V193**（调度者预分配，不自行找最大版本号）。

## 决策摘要

蓝图 §4.4.2.2 的输入是「筛选规则；目标店铺集合；重复度控制参数」，输出是「批量搬迁任务 + 结果明细；
重复度风险提示」，加粗句只有一句：**搬迁后必须做标题差异化，否则平台可能判定重复铺货并抑制 listing**。
这一句如果只落在提示文案里，它随时会丢 —— 一次赶工的实现就会把源标题原样抄进目标草稿，
然后**什么都没发生**，直到平台把 listing 抑制掉、运营还不知道是哪一步丢的。

所以本模块把三件事做成**结构**而不是文案：

1. **差异化是流程的一部分**。服务层按规则算出差异化后的标题（+ 卖点 / 描述片段），
   逐条留痕（`listing_migration_content_diff`：原值 → 每一步之后的值 + 规则 + 规则参数 + 谁改的），
   并且 V193 用 **CHECK + BEFORE INSERT 触发器**在 DB 层拒掉"没做差异化"的搬迁结果 ——
   比较用 `lower(btrim(...))` **归一化**，只改大小写或加一个空格**不算**差异化（平台看到的是同一个标题）。
2. **重复度分值是算出来的**。标题（字符二元组 Dice）/ 类目（平台类目 ID 相等 + 类目段落 Dice）/
   属性（共有属性键的取值一致率）三轴加权（0.60 / 0.25 / 0.15），**不可知的分量不计入分母**；
   三个分量都不可知时等级 `UNKNOWN`、**分值为 null（不是 0）**。
3. **「重复铺货风险」与「已搬迁」是两种事实**，落在两张表、两个查询端点，不合并成一个结论；
   「已搬迁」另有唯一索引（同一源商品 × 目标店铺搬第二次在 DB 层不成立）。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 归属服务 | **channel**（不是 catalog） | 源商品、目标店铺、目标草稿、待发布任务全部是 channel 自己的事实；`listing` 按 `shop` 收敛可见性，放 catalog 就得为店铺维度数据范围去读 channel 的表 = 跨服务 SQL（AGENTS.md 禁止） |
| 价格维度的数据源 | V169 `writeback_target.current_price`（平台侧在售价快照） | 全库**唯一**真实的价格数字（V175 已认定）。代价：它不是 05 定价的权威售价 ⇒ 界面上必须写明。未登记为可回写目标 ⇒ **UNKNOWN，不是 0** |
| 销量维度的数据源 | **不建列**，显式声明未接入 | 平台侧销量归 4.5.1.1（未交付），内部订单口径归 09 且跨服务禁止跨库 SQL。建一个影子列 = 制造第二个销量事实来源，比"没有"危险得多。三个数值维度因此统一走**三值谓词**（EXCLUDE / INCLUDE / ONLY），命中数按未知值策略如实报 |
| 标题相似度算法 | **字符二元组 Dice**（不用空格分词） | 中文标题没有空格，按词切会把两个不同的中文标题算成 1.0。字符二元组对中英文都成立且完全确定 |
| 差异化规则 | 模板 → 店铺标识 → 站点标识 → 超长截断 → 同名消歧（+ 卖点 / 描述各一条） | 规则可解释、可复现、可留痕；**默认规则一定产出与原标题不同的标题**，不达标 ⇒ 那一项直接失败而不是"照搬过去" |
| 风险算在谁身上 | 算**源 listing** 与目标店铺既有 listing 的相似度 | 蓝图原文如此（「基于源 listing 与目标店铺既有 listing 的标题/类目/属性相似度」）。差异化另有其职责与留痕，两者不混 |
| 幂等键 | 调用方给；不给就**按请求内容派生**（规范化摘要） | 同一请求重复提交天然幂等，不必让前端背"生成一个 key"的负担 |
| 与 4.4.2.1 的关系 | `listing_claim` **只读**（判断是否已被认领过去） | 「每张事实表只有一个写入所有者」：claim 的写入所有者仍是 4.4.2.1，本模块不抢 |
| 待发布任务 | **复用** V183 的 `publish_batch` / `publish_task`（一个批次 N 条任务） | 另建一张 = 平台上两套待发布队列，4.3.1.2 的限流只管一套 |
| 目标 listing 的内容 | 写 V175 `listing_content_snapshot`（差异化后的标题/卖点/描述） | 复用既有事实表，不另造内容表；待发布任务的 `draft_id` 留空（4.2.1.1 / 4.2.1.2 未交付），**不伪造** `listing_bulk_operation` |
| 契约 | 走 `tools/codegen/catalog-contracts.py` 重新生成，**不手改** | 顺带把 2.4.2.3 漏登记进 `channel.openapi.json` 快照的端点补齐（快照此前是陈的） |

## 文件清单

后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V193__listing_bulk_migration.sql`（新，四张表 + RLS + 触发器 + 索引）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingMigrationModels.java`（新，传输模型）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingMigrationService.java`（新，jOOQ 显式 SQL）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingMigrationController.java`（新，9 个端点）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingMigrationIntegrationTest.java`（新，19 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingMigrationTenancyIntegrationTest.java`（新，3 例，多租户三断言）

前端（web）

- `apps/web/src/app/listings/migrations/page.tsx`（新）
- `apps/web/src/components/listing-migration/listing-migration-workspace.tsx`（新）
- `apps/web/src/components/listing-migration/listing-migration-model.ts`（新，纯函数 + 取数）
- `apps/web/src/components/listing-migration/listing-migration-model.test.ts`（新，23 例）
- `apps/web/src/app/api/channel/listing-migration-allowlist.test.ts`（新，api-proxy 双向断言）
- `apps/web/src/lib/api-proxy.ts`（改：**纯增量**新增一条 `listing-migrations` 元素，锚点在既有 `prefix: 'trade/'` 之前）
- `apps/web/src/app/listings/claim/page.tsx`（改：文件头注释指向新页面）
- `packages/api-contracts/src/contracts/channel.ts` / `channel.openapi.json`（改：codegen 产物）

文档

- `docs/product/capability-blueprint/module-progress.md`（改：`| 4.4.2.2 |` 那一行的「状态」「证据」两格 + 统计数字重算）
- `docs/sessions/2026-10/2026-10-20-listing-bulk-migration.md`（本文件）

## 验证

- L0：`./mvnw -q -pl services/channel -am compile` 通过；`pnpm typecheck` 通过（3 个包全绿）
- L1：`./mvnw -pl services/channel -am test -Dtest='ListingMigration*' -Dsurefire.failIfNoSpecifiedTests=false`
  → **22 例全绿**（功能 19 + 多租户 3），实跑 Testcontainers PostgreSQL 16，连接池压到 1
- 前端：`pnpm vitest run apps/web/src/components/listing-migration` → 23 例绿；
  `pnpm vitest run apps/web/src/app/api` → **79 个文件 / 496 例**全绿（含 api-proxy allowlist 全量回归）

## 后续待办

- 销量维度要真能筛，需要 4.5.1.1 平台商品监控交付平台侧销量事实（或 09 订单的可查询投影）；
  届时把 `SALES_EXPR` 换成真实来源并把 capabilities 里 `sales_filter` 改成 `available=true`。
- 4.2.1.1 / 4.2.1.2 内部内容草稿交付后，待发布任务的 `draft_id` 应指向真实草稿（当前留空，
  内容以本次搬迁登记的内容快照为准）。
- 4.3.1.2 批量发布派发后，搬迁结果会随目标 listing 离开草稿态而变化 —— 本模块目前不派生
  「已发布」状态（那是 4.3.1.2 / 4.3.2.2 的事实）。
