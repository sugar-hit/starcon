# 9.1.1.3 订单变更与取消

- **日期**：2026-10-14
- **车道**：`feat/9-1-1-3`（worktree `.worktrees/9.1.1.3`）
- **L3 锚点**：9.1.1 订单查询 · **阶段** D4 · **主责服务** trade · **迁移** V119

## 决策摘要

1. **变更不改写订单快照，改成独立单据。**
   V99 第 8.3 节已把 `sales_order_line` / `order_address_snapshot` 在 SUBMITTED 后用触发器冻住，
   第 8.5 节冻结单号与来源，第 8.6 节禁止退回 DRAFT。所以「地址修改」「数量调整」在已提交订单上
   **在数据层就改不动** —— 这不是本模块的选择，是上游交付的既成约束。
   因此把变更记成 `order_change_request` + 行明细 + 地址字段级前后值，订单侧一行不动。
   **刻意没建** `order_address_current` 之类的「当前值」覆盖表：多存一份可覆盖的当前值，
   等于把 V99 刚钉死的「当时那笔交易不能有两个答案」重新请回来。

2. **已发货数量取 trade 侧投影，不跨库读 warehouse。**
   实查结论：`sales_order` 簇**完全没有履约列**（status 的 CHECK 只有 DRAFT/SUBMITTED，语义是
   「有没有进审单队列」）；V116 记的是**选仓决策与 shortfall**，不是发货事实；真正的发货事实在
   warehouse 库里，而 9.3.3.1 履约请求未开发。所以建 `order_fulfillment_line_progress` 作为
   **本服务自己的只读投影**，由本模块的授权入口写入，`progress_source` 如实标注来源。
   9.3.3.1 落地后同步器按同一形状写入，**表结构与判定逻辑都不用改**。

3. **「先释放再取消」与「确认后才落终态」钉在 DB 层，不只靠服务层。**
   四条 BEFORE 触发器守卫全部回表读当前行（照 V85 / V90 / V92 同一解法），
   绕过服务层直连 SQL 同样被挡，集成测试逐条复验。

4. **刻意不建的两张表**：11 售后一条模块都未开发 → **不建售后表**，已发货被拒时逐行给出
   `ownerModule=11` 与原因；12 财务未开发 → 只记 `refund_amount` 这个**金额**，不落退款单。
   照 9.1.1.2 / 8.3.1.2 已立的规矩：**不建占位表、不返回空集冒充成功**。

5. **平台可变更规则是数据不是常量。** 各平台的可改窗口与是否允许改量本来就不一样，
   写成代码里的 if 就等于把商务事实硬编码。**查不到规则 ≠ 允许** —— 四种结果
   （无规则 / 不允许 / 超窗口 / 超减量上限）分别报出，因为它们的运营动作完全不同。

## 关键 trade-off

- **投影缺行时按「未发货」放行，还是挡住整个模块？**
  初版把「本单没有履约投影」放进 `blockers` 当确认闸门，测试立刻暴露这会让模块在 9.3.3.1
  交付前对**所有订单**完全不可用。而真正要紧的闸门（已发货不可取消）只要投影有数据就自动生效。
  **最终拆成两个概念**：`blockers`（仓库回执 + 平台回写，真正的闸门，空了才允许确认）
  与 `warnings`（必须显示、明确写「未接入」而不是 0、**不阻止确认**）。
  取舍理由：宁可让人看见「未接入，请人工核对」，也不要一个假装严谨的闸门把功能整体锁死。

- **SECURITY DEFINER 窄函数的租户安全。**
  `confirm_order_cancellation` 以定义者（迁移用户）权限执行会**绕过 RLS**。V116 的
  `advance_warehouse_allocation_status` 没有这层核对（它按 id 查，不带租户）。
  本函数**先核对调用方事务当前绑定的租户**与参数一致，不一致直接 42501 拒绝 —— 不沿用那个缺口。

- **端点前缀 `/change` 而不是裸 `/{id}`。**
  `GET /trade/sales-orders/{id}` 已被 9.1.2.2 占用，同一 `@RequestMapping` 再加一个
  `@GetMapping("/{id}")` 会让 Spring **启动时**抛 ambiguous mapping，整个 trade 服务起不来。
  与 9.1.1.1 的 `/list`、9.1.1.2 的 `/detail` 同一纪律。

- **api-proxy 白名单只做外科式追加。**
  本批 4 条车道并行，3 条（9.6.1.1 / 9.1.1.3 / 9.7.1.1）都改同一个 trade alternation，
  所以只追加 `sales-orders/change/…` 一支，不重排、不格式化已有分支；
  allowlist 测试除验新增形状放行外，**还反验 9.1.1.1 / 9.1.1.2 / 9.1.2.2 / 9.3.2.1 / 9.3.2.2 / 6.4.1.1
  已交付的形状一个都没被挤掉**（取错一侧会静默删掉已交付路由，表现为页面 404 而全绿）。

## 文件清单

**后端（trade）**
- `backend/services/trade/src/main/resources/db/migration/V119__trade_order_change_cancel.sql` — 十张新表 + 四条守卫 + RLS/GRANT
- `backend/services/trade/src/main/java/com/starcon/trade/OrderChangeCancelModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderChangeCancelService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderChangeCancelController.java` — 15 个端点
- `backend/services/trade/src/test/java/com/starcon/trade/OrderChangeCancelIntegrationTest.java` — 40 例

**前端**
- `apps/web/src/components/orders/order-change-cancel-model.ts` + `.test.ts`（17 例）
- `apps/web/src/components/orders/order-change-cancel-workspace.tsx`
- `apps/web/src/app/orders/change/cancellations/[id]/page.tsx`
- `apps/web/src/app/api/trade/order-change-cancel-allowlist.test.ts`（6 例）
- `apps/web/src/lib/api-proxy.ts` — 仅追加一支 alternation

**契约与台账**
- `packages/api-contracts/src/contracts/trade.ts` + `trade.openapi.json`（codegen 产物）
- `packages/api-contracts/src/contracts/warehouse.ts` + `warehouse.openapi.json`（**顺带修好的既有漂移**，见下）
- `docs/product/capability-blueprint/module-progress.md` — 只改 9.1.1.3 那一行

## 验证

- `cd backend && ./mvnw -q compile` → 通过
- `cd backend && ./mvnw -pl services/trade -am test` → **235 例全绿**（本模块 40 + 既有八个模块 195 未回归）
- `pnpm typecheck` → 全绿
- `pnpm lint` → 无 error（仅其他车道既有 warning）
- `pnpm vitest run <本模块两个测试文件>` → **23 例全绿**

## 后续待办

1. **9.3.3.1 履约请求交付后**：由同步器写 `order_fulfillment_line_progress`（改 `progress_source` 枚举即可），
   `capabilities` 的 `FULFILLMENT_PROGRESS_NOT_CONNECTED` 随之消失，界面的「未接入」自动变数字。
   表结构与判定逻辑都不需要改。
2. **11 售后交付后**：已发货数量的转售后入口应落在 11，本模块继续只报 `ownerModule=11`。
3. **9.1.2.1 平台订单同步交付后**：`platformCode` / `shopCode` 改由订单自己带，
   入参可弃用（表结构不需要改）。
4. **codegen 顺带修好的既有漂移**：7.2.2.2 安全库存落地时没跑 codegen，warehouse 契约少了
   SafetyStock* 系列 DTO，本次跑 codegen 顺带补上（纯新增，按类型名求并不冲突）。
   **不是本车道的文件，若并入时有争议请以「重新跑一次 codegen」为准**。
