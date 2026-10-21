# 2026-10-19: 7.8.2.1 分摊结果查看

## 摘要

在 `feat/7-8-2-1` 车道实现蓝图模块 7.8.2.1「分摊结果查看」（L3 锚点 7.8，落地服务 warehouse）。
新增迁移 `V189__warehouse_allocation_result_view.sql`（一张新表 `allocation_writeback_snapshot`）、
6 个端点 `/warehouse/inventory/allocation-results/**`、前端工作区 `/inventory/allocation-results`
（外加 L3 路由下的第二入口 `/inventory/allocation/results`）、集成测试 25 例。
核心是把蓝图那句加粗的「**平台回写有延迟**，分摊需保留缓冲以覆盖同步窗口，缓冲不足会超卖」
落成**结构**（敞口 = 平台挂着的 − 我方剩余；敞口 > 缓冲 ⇒ 缓冲不足）而不是一句提示文案，
并把「本地没有回写状态」与「回写数 = 0」在数据、接口、界面三层彻底分开。

## 决策 / 结论

| 决策                                                                                                                                                           | 理由                                                                                                                                                                                                                                                                      |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **平台回写状态建模成本地投影事实**（`allocation_writeback_snapshot`，事件 / 授权 API / 显式录入三种来源，每条带 `source_kind` + `source_reference` + `as_of`） | 4.3.2.1 在 channel 服务，AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图。本地投影是唯一不越界的做法，也让「什么时候、由谁告诉我们的」有据可查                                                                                                                              |
| **观测 append-only，不做成「每 (run, 店铺) 一行原地更新」**                                                                                                    | 回写失败与延迟恰恰是本模块要暴露的东西；压成一行就看不到「失败过几次、最后一次成功是什么时候」。时间线本身就是证据                                                                                                                                                        |
| **「不知道」是状态不是 0**：`written_quantity` / `consumed_quantity` 可空，状态含 `UNKNOWN`，界面渲染成「尚未回填」+ 一堆「—」                                 | 0 意味着「平台上一件都卖不出去」，UNKNOWN 意味着「我们不知道，该去查同步」，两者处置完全相反。渲染成 0 会把人引去补货                                                                                                                                                     |
| **平台当前值取「最近一次带确认值的观测」而不是「最近一次观测」**                                                                                               | FAILED / PENDING 的观测**不改变**平台挂着的数；已用同理取最后一次真的观测到的数（不倒退成 0，也不把「这次没测到」当成 0）                                                                                                                                                 |
| **缓冲取 7.2.2.1 的 `sellable_policy.buffer_ratio`**（平台可售数保留缓冲比例），与 7.8.1.1 的分摊缓冲、7.8.1.2 的保底池是三个不同的东西                        | 蓝图说的「缓冲不足会超卖」正是 7.2.2.1 的口径（「平台库存回写缓冲是防超卖的最后一道防线」）。该表在本服务，可直接读，不必复制口径                                                                                                                                         |
| **缓冲 = 分摊量 − trunc(分摊量 × (1 − 缓冲比例) + 超卖容忍)**，与 7.2.2.1 的 `committed` 口径同形                                                              | 同一个数在两处必须能对上；口径不同形会让人以为有两套算法                                                                                                                                                                                                                  |
| **仓维度取「回写观测所记录的仓」**；按仓筛选时未回填的行会被排除，被排除的行数由 `warehouseUnattributedCount` 报出                                             | 分摊是 SKU 级的，仓归属只能由观测给出。排除而不报数 = 那些行无声消失                                                                                                                                                                                                      |
| **口径解析只做 SHOP > DEFAULT**（与 7.2.2.1 的 `effective()` 同口径）                                                                                          | CHANNEL 档需要「店铺所属渠道 id」，而店铺在 channel 服务，本服务没有这个映射。解析不到时如实报「未配置」，不猜                                                                                                                                                            |
| **不另建分摊头**：分摊结果读 7.8.1.1 的 `allocation_run` / `allocation_run_line` 与 7.8.1.2 的 `allocation_priority_resolution`                                | 两个 run id 拼起来的记录迟早对不上，而「分摊结果查看」要的正是那一张表                                                                                                                                                                                                    |
| **路由取 `/inventory/allocation-results`**（同族兄弟），另在 `/inventory/allocation/results` 留同工作区的第二入口                                              | L3 路由 `/inventory/allocation` 已被 7.8.1.1 占用；按 7.8.1.2 的先例取兄弟路由，同时满足「L3 路由下可直达」                                                                                                                                                               |
| **顺带修掉一处真缺陷：本模块 DTO 一度与 7.8.1.1 的 `AllocationRunPage` 重名**                                                                                  | SpringDoc 的 `components/schemas` 以 schema 名为键，同名后者覆盖前者 → 7.8.1.1 的契约被悄悄换掉，其前端 typecheck 报 `starvedLineCount 不存在`。codegen 的重名检测**只跨服务、不跨同服务模块**，检查不出来。已改名为 `AllocationResultRunRow` / `AllocationResultRunPage` |

## 涉及文件

**后端（warehouse）**

- `backend/services/warehouse/src/main/resources/db/migration/V189__warehouse_allocation_result_view.sql` —— 一张新表 + 2 个触发器 + RLS + GRANT，零 ALTER 既有表
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AllocationResultModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AllocationResultService.java` —— CTE 链（win / runs / lines / obs / plat / cons / floor / base / calc / judged）+ 显式 jOOQ SQL
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AllocationResultController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/AllocationResultIntegrationTest.java` —— 25 例

**前端（web）**

- `apps/web/src/components/inventory/allocation-result-model.ts` / `-model.test.ts` / `-workspace.tsx`
- `apps/web/src/app/inventory/allocation-results/page.tsx`、`apps/web/src/app/inventory/allocation/results/page.tsx`
- `apps/web/src/app/api/warehouse/allocation-result-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **共享文件**，白名单加 `allocation-results` 五个形状（只加，不改既有）

**契约（codegen 产物，勿手改）**

- `packages/api-contracts/src/contracts/warehouse.ts` —— +12 类型 / 0 删除

**文档**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 7.8.2.1 那一行的「状态」「证据」两格

## 关键 trade-off

- **仓维度用观测的仓而不是「政策挂的仓」**：后者能覆盖未回填的行，但要多写一套 7.2.2.1 口径解析（含 CHANNEL 档的缺口）。本轮选了前者 + 如实报出被排除的行数，代价是按仓筛选时看不到未回填的店。
- **平台值/已用取「最近一次带确认值」**：多写了两个 `distinct on` CTE（`plat` / `cons`），换来「失败的回写不会让平台当前值凭空变小」这条正确语义。
- **敞口与一致性两个判定并存**：一个比「平台挂着的 vs 应挂的」（配置层），一个比「平台挂着的 vs 我方剩余」（超卖风险层）。两者形状不同，都留着。

## 踩到的坑（值得记住）

1. **DTO 重名在同一个服务内不会被 codegen 拦住** —— 它会静默覆盖别人的契约，让别的模块的 typecheck 炸。改名后必须确认契约 diff 是**纯增量**。
2. **`FROM a, b LEFT JOIN c ON ...` 的解析陷阱**：逗号优先级低于 JOIN，`LEFT JOIN` 会绑到 `b` 而不是 `a`，于是 `ON` 里引用 `a` 会报 `invalid reference to FROM-clause entry`。本模块的 SQL 改用 `FROM a LEFT JOIN c ON ..., win`。
3. **jOOQ 把 `OffsetDateTime` 绑成 varchar**：写 `timestamptz` 列必须显式 `?::timestamptz`，且 cast 不能落在绑定序列末位。
4. **api-proxy 白名单的插入锚点必须确认在分组内部**：`allocation-priority` 之后那一处在 warehouse 的正则里是**组外**的顶层分支，插在那里 `inventory/allocation-results/...` 整组 404，而 typecheck / lint / vitest 全绿。锚点选 `allocation-policies` 之后（确定在 `inventory\/` 组内）。
5. **PG 的 `GREATEST(NULL, 0)` 返回 0**（不传播 NULL）。算敞口时必须显式 `CASE WHEN ... IS NULL THEN NULL`，否则「算不出来」会被悄悄变成「0 件敞口」。

## 验证

- `./mvnw -pl services/warehouse -am test -Dtest='AllocationResult*'` → **25 例，0 失败 0 错误**（含多租户三断言：两租户互不可见 / 伪造 `X-Tenant-Id` 无效 / 池大小 1 归还后不串租户 20 轮）
- `./mvnw -pl services/warehouse -am test -Dtest='Allocation*'` → **99 例全绿**（含 7.8.1.1 / 7.8.1.2 的既有 74 例，证明 V189 没打掉它们的直写夹具）
- `./mvnw -q compile`（全量）→ 通过
- `pnpm typecheck` → 3/3；`pnpm lint` → 3/3 无新增 warning
- 前端：`pnpm vitest run apps/web/src/components/inventory/allocation-result-model.test.ts apps/web/src/app/api/warehouse` → 15 文件 144 例全绿
- **改了 `api-proxy.ts`，因此跑了全量 allowlist 回归**：`pnpm vitest run apps/web/src/lib/api-proxy.test.ts apps/web/src/lib/api-proxy-union.test.ts apps/web/src/app/api` → 77 文件 739 例全绿
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`（先跑各服务 `OpenApiContractTest` 导出 spec），重生成后 warehouse.ts diff **纯增量**

## 后续待办

- 7.8.2.2 缺货与饥饿预警可直接在本模块的 `allocation_priority_resolution` 与本模块的敞口判定上做聚合，不必重算分摊。
- 本模块的两个页面入口**未登记进导航**（与 7.8.1.1 / 7.8.1.2 一致，三者都不在 nav 里）—— 是否补导航由 7.8 整组的收口人决定。
- 仓维度的另一种口径（按 7.2.2.1 的 `sellable_policy_warehouse` 解析政策挂的仓）留待「按仓筛选要覆盖未回填的行」这个需求真的出现时再做。
