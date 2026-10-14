# 8.3.3.1 复核扫描（阶段 D4 · L3 锚点 8.3.3 · 主责 warehouse · 迁移 V117）

车道 `feat/8-3-3-1`，worktree `.worktrees/8.3.3.1`。本文件只写决策摘要、关键 trade-off、文件清单与后续待办。

## 决策摘要

按蓝图 08 卷 8.3.3.1 原文落地「打包前复核：逐件扫描商品与订单核对，检出漏件/错件/多件，支持开箱复核」。
四个输入（扫描记录 / 订单明细 / 复核规则 / 上游拣货事实）与两个输出（通过·异常判定 / 异常转人工）全部走真实 PostgreSQL，
查询一律 jOOQ 显式 SQL，无任何内存替身、占位页或假分页。

**建六张表**：`recount_rule` / `recount_session` / `recount_line` / `recount_scan` / `recount_exception` / `recount_event`，
六张全部 `tenant_id NOT NULL` + `ENABLE/FORCE ROW LEVEL SECURITY` + GRANT（仅 `warehouse_app`，`starcon_app` 拿不到）。

**十四个端点**（`/warehouse/recount-scans`，顶层命名空间）：capabilities、rules CRUD+retire、开单、列表、详情、
open-box、scans、settle、complete、escalate、exceptions/{id}/resolve、cancel。刻意无 PUT / DELETE
（扫描记录与流水 append-only，异常只能处置一次）。

## 关键 trade-off

### 1. 「复核是错发拦截的最后机会」做成 DB 守卫，不只是服务层校验

`guard_recount_session_status_fn` 在 `PASSED` 上同时要求四件事：出库单复核前确实在 `PENDING_RECHECK`、
每一行 `line_status='MATCHED'`、没有任何 `resolution='PENDING'` 的异常、有复核人与完成时刻。
集成测试用**超级用户直连 SQL** 强行改成 `PASSED`，被拒（23514）。
「先放行再补异常」这条路绕开服务层也走不通。

同理 `recount_line.line_status` 由 BEFORE UPDATE 触发器**派生**：直连 SQL 写 `MATCHED` 语句会成功，
但值当场被改回 `MISSING` —— 行状态不是可写列。

### 2. 序列号逐件核对用「集合相等」而不是「件数相等」

`serial_match` 由两个差集查询算出（实拣集合 − 实扫集合、反向再减一次），不是 `count` 相等。
理由：应发 3 件、实拣 3 件、实扫 3 件，扫的可能是 S1/S2/S9 —— 件数一样内容不一样，那是最典型的错件。
两层保险：计数由触发器从 `recount_scan` 去重派生，`serial_match` 由服务层按真实 SQL 算，
`line_status` 再由触发器从两者派生，且 CHECK 保证 `requires_serial` 的行拿不到 `MATCHED` 除非 `serial_match`。

### 3. 「高价值订单」在本服务只能取**件数**——如实命名，不假装是金额

出库单上**根本没有金额列**：V72 §1 明写「与 transfer_order_line 一样不存类目/售价/成本」（COPY 语义），
金额的 owner 是 9 订单履约侧，跨服务读表被模块边界纪律禁止。
所以阈值列命名为 `high_value_quantity_threshold`（件数），并把这条口径原文放进
`capabilities.highValueBasis` 与每张单的 `verifyBasis` 随响应返回，界面上写「应发件数达到阈值」而不编金额。
**没有造一个 warehouse 侧算不出来的金额。**

### 4. 复核**不动库存**

货在打包区，`on_hand` / `reserved` 一动不动，不写 `inventory_entry`，预占不释放。
检出拣错/漏件时的出口是**退回拣货**（出库单 `PENDING_RECHECK → PICKING`，由 8.3.2.1 的 `reopen` 接住），
且该动作**刻意不在本模块服务层**：它是跨模块的状态回退，由调用方显式发起，不由复核单自动触发。
集成测试断言通过与失败两种结局下 balance / stock_bucket / inventory_entry 全部不变。

### 5. 基线冻结在 `recount_line`，不每次去 join `outbound_order_line`

复核是一份**当时当地**的核对凭据。且 `outbound_order_line` 被 V72 的不可改守卫钉死，
实拣在任务 COMPLETED 后同样改不动（8.3.2.1 的守卫）—— 基线冻结有前提，测试里两条守卫都验了。

### 6. 波次**不挂外键**

8.3.2.2 的波次是排班且成员会被 detach/rebind，在复核单上钉一列 `wave_id` 会静默过期；
改为经 `pick_task → pick_wave_task` 读出用于展示。

### 7. 一张出库单同时只有一个 OPEN 复核单（部分唯一索引）

复核失败 → 退回拣货 → 重拣 → 再复核，第二轮是**新会话**，不是把第一轮改成通过 —— 证据链不能被追认。

## 测试过程中发现并修掉的三个真 bug（都是「错了会发错货」那一类）

1. **条码重扫被误当重复件**：原先按 `scanned_value` 去重计件，对条码是错的 ——
   同一个条码扫三遍是**三件**（三台同型号机器），去重会把「扫齐了」说成「还差两件」。
   反过来序列号必须去重。现已按 `scan_type` 分开计数，`DUPLICATE` 判定也只对序列号成立。
2. **只扫序列号的行件数恒为 0**：序列号分支只贡献序列号个数、不贡献件数，
   于是每一行都被判成漏件 —— 恰恰是逐件核对品最常见的形态。现在每个不同的号同时贡献件数与序列号个数。
3. **`rule(String)` 漏转 UUID**：直接绑字符串让 PG 报「uuid = character varying」，
   那是类型错而不是「规则不存在」，两者对调用方是完全不同的信号。

另有一处基础设施差异值得记下：jOOQ 对纯文本 SQL 的 `limit ? offset ?` 靠实参类型推断，
传 `long` 会绑成 `int8` 而被 PG 拒 —— 必须与 `PickTaskService` / `PickWaveService` 一样用 `int`。

## 文件清单

**迁移**
- `backend/services/warehouse/src/main/resources/db/migration/V117__warehouse_recount_scan.sql`

**后端**
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/RecountModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/RecountScanService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/RecountScanController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/RecountScanIntegrationTest.java`

**前端**
- `apps/web/src/app/warehouse/recount-scans/page.tsx`
- `apps/web/src/components/warehouse/recount-scan-model.ts`
- `apps/web/src/components/warehouse/recount-scan-model.test.ts`
- `apps/web/src/components/warehouse/recount-scan-workspace.tsx`
- `apps/web/src/lib/api-proxy.ts`（**外科式追加** `recount-scans` 一个顶层分支，既有分支一条没动）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（追加 5 例）

**共用生成物 / 台账**
- `packages/api-contracts`（由 codegen 重新生成，禁手改）
- `docs/product/capability-blueprint/module-progress.md`（只改 8.3.3.1 那一行的「状态」「证据」两格）

## 边界

- 不建打包与称重（8.3.3.2，未交付）：`capabilities.packingHandoffLive=false`，通过的单停在「待打包」等人接手。
- 不碰别的模块的迁移 / 端点 / 页面 / 台账行；不改 8.3.2.1、8.3.2.2 任何代码。
- 不新增 `warehouse.recount.*` 权限码：identity 只注册了 `warehouse.inventory` 与 `warehouse.facilities`，
  用一个没被注册的码会让连 OWNER 都 403（8.1.2.1 车道踩过这个坑）。

## 后续待办

1. **8.3.3.2 打包与称重**接本模块的出口：读处于 `PENDING_PACKING` 的出库单与已通过的复核单。
2. **退回拣货的动作**目前要调用方显式发起（8.3.2.1 `reopen`）。若现场希望「判异常即自动退回」，
   需要先决定它是否该由 8.3.2.1 拥有 —— 本车道刻意没做，避免一个复核单悄悄改掉出库单状态。
3. **高价值口径换列**：等 9 订单履约侧提供授权的金额口径后，把 `high_value_quantity_threshold`
   换成金额列并同步改列名与 `highValueBasis` 文案。
4. 复核工作台目前按出库单号/复核单号检索；若现场要按拣货任务号反查全链，需要一个跨 8.3.2.1 的检索形状。