# 7.1.2.2 账实核对（阶段 D3，L3 锚点 7.1.2）

日期：2026-10-07
车道：`feat/7-1-2-2`（worktree `.worktrees/7.1.2.2`）
状态：已交付

## 决策摘要

**核对两侧都现算，不建「累计值」表。** 蓝图的输入是「余额快照、流水累计值、时间区间」，
最容易顺手做的一张表就是「累计余额表」，但那是第二个真相来源 —— 一旦它和 `inventory_balance`
对不上，核对反而没法判断该信谁。累计值就是 `Σ inventory_entry`，每条余额行按需聚合即可
（与 V51 台账拒绝汇总表是同一条理由）。V54 只落**核对这件事的结论**：run 批次头 + finding 发现表，
它们是审计凭据，不是账。

**口径是全模块唯一一套：`期初 + 区间内 + 区间后 = 应当等于快照 − 快照 = 差异`。**
期初这一档不能省：快照只有「现在」这一个值，拿区间内的流水去比它会凭空差出期初那一段。
`区间后` 单独成档而不是并进期初 —— 流水落在核对区间**之后**本身就是异常信号（错期录入、事后补记），
藏进别的桶里就没人看得见了。两个边界都有值时三个桶恰好把全部历史分完，`expected == total` 恒成立。

**差异判定覆盖全部历史，不受核对区间影响。** 否则「换个区间重核一次结论就变了」，
那不是核对，是筛流水。区间只决定两件事：本期动了多少、可疑流水去哪个时间段找。

**可疑定位是本模块真正的产出。** 返回一个差额数字等于什么都没说。四类各有可计算信号，
且都落到具体的余额行或业务单上：

| 类别 | 信号 | 严重度 |
| --- | --- | --- |
| 漏记 MISSING | 快照非零，但核对区间内一条分录都没有（`period_entry_count = 0`） | WARN |
| 迟到补记 BACKFILL | 入库时刻比声称的发生时刻晚超过 24h（漏记的晚期形态） | WARN |
| 重复记 DUPLICATE | 同余额行、同类型、同数量、同操作人，相隔 ≤60s 的两笔不同业务单 | HIGH |
| 并发丢失 LOST_UPDATE | 同余额行在**写入时刻**上撞在一起（≤60s 内多次写入） | HIGH |

`DUPLICATE` 的分区键是 `(balance_id, kind, on_hand_delta, reserved_delta, actor_id)` —— 
V1 的 `UNIQUE(tenant_id, ref_type, ref_id)` 挡得住同一来源单据重投，挡不住「同一批货被两张不同的单据各记一次」，
所以签名里**不含 ref_type**。怀疑一律成对给出（`relatedTransactionId` + `gapSeconds`），
只说「重复了」而不告诉你是跟谁重复，人还是得回去翻流水。

**后三类只在有差异的余额行上扫描。** 不一致的账才需要解释；对本来平了的行做同样的扫描只会产出噪音，
把真正要看的信号淹掉。代价是「账平但潜在重复」不会被报出来 —— 这是有意的取舍，见后续待办。

**差异为零也落库。** `CONSISTENT` 与 `DISCREPANT` 同等重要，`verdict_note` 里写清
「核了哪个区间、多少笔分录、什么结论」。没有差异不等于没核对，而「某个区间被核过、结论是一致」
这件事本身必须可回查。

**结论不可改。** `REVOKE UPDATE,DELETE` + 复用 V1 的 `guard_inventory_append_only` 触发器（双层）。
查出来的差异要作废只能重跑一次，新旧两批并存 —— 这正是「可追溯」的意思。集成测试两层都断言了
（应用角色撞权限，再用超级用户连接证明触发器本身也真拦）。

**权限码拆成读写。** 发起核对用 `warehouse.inventory.update`，回查用 `inventory.read`。
核对不改动任何库存数量，但它会写下不可变的审计记录 —— 把「写审计记录」挂在 read 上，
等于声明 read 权限包含写操作，那道闸门等于没有。

**SQL 实现上的四个坑**（都踩过，记下来）：

1. **CTE 片段之间必须用逗号连接。** 少一个逗号的报错是 `syntax error at or near per_balance`，
   指向后面那个 CTE 而不是缺逗号的地方。为此加了 `ctes(...)` 帮手，不让调用方手写逗号。
2. **CTE 名必须出现在 `FROM` 里才能被引用。** `win.from_at` 写在聚合的 `filter` 里，
   而 `win` 不在 `per_balance` 的 FROM 中 → `missing FROM-clause entry for table "win"`。
   解法是 `cross join win`（`win` 只有一行，不改基数）。
3. **`?::timestamptz` 集中在最前面的 `win` CTE**，后面所有 `?` 都是普通绑定 ——
   cast 落在绑定序列末位会让 jOOQ 整体错位一格。
4. **写入时 `?::timestamptz` 一个都不能省。** 纯文本语句里 jOOQ 无从推断 `OffsetDateTime`，
   不 cast 会被驱动当 varchar 送过去，报 `column window_from is of type timestamp with time zone
   but expression is of type character varying`。run 表刻意把 `created_at` 排在列清单倒数第三位，
   末位留给普通列，避免 cast 落到绑定序列末尾。

占位符用 `String.join(",", Collections.nCopies(n, "?"))` 按列位置生成，不手数 ——
手写问号个数一旦与列数对不上，PostgreSQL 报的是「bind message supplies N parameters」，排查成本高得多。

## 关键 trade-off

- **单次核对把范围内全部余额行拉进内存**（差异明细与可疑发现都要落库），没有分页。
  一次大范围核对可能产出成百上千条发现，内存占用与单次运行时间随余额行数线性增长。
  换来的是「一次事务内算完并落库」，不会出现算到一半的半截结论。
- **发现表没有保留策略。** 结论只增不减，长期运行会让表持续变大。
- **日桶由数据库会话时区决定**，前端按浏览器本地时区标注。这两者在不同时区访问时可能对不上同一天。
- **上面的两处都记进后续待办**，不在本模块里做取舍性简化。

## 文件清单

后端：
- `backend/services/warehouse/src/main/resources/db/migration/V54__warehouse_inventory_reconciliation.sql`（新增）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryReconciliationModels.java`（新增）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryReconciliationService.java`（新增）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryController.java`（改：+4 个端点、+1 个依赖）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryReconciliationIntegrationTest.java`（新增，30 例）

前端：
- `apps/web/src/components/inventory/inventory-reconciliation.tsx`（新增）
- `apps/web/src/components/inventory/inventory-model.ts`（改：核对接口 + 标签 + 纯函数）
- `apps/web/src/components/inventory/inventory-model.test.ts`（改：+10 例，含 parseLedgerHref 新增 transactionId 的断言更新）
- `apps/web/src/components/inventory/inventory-ledger.tsx`（改：接 `transactionId` 深链，自动展开那笔分录）
- `apps/web/src/components/inventory/inventory-workspace.tsx`（改：第四个页签）
- `packages/api-contracts/src/catalog.ts`、`warehouse.openapi.json`、`identity.openapi.json`（codegen 产物）

台账 / 文档：
- `docs/product/capability-blueprint/module-progress.md`（7.1.2.2 那一行的状态与证据）
- 本文件

## 验证

- `cd backend && ./mvnw verify` —— 全绿
- `pnpm typecheck`、`pnpm lint`、`pnpm test` —— 979 例 / 56 文件全绿（基线 969，+10 且零下降）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` —— 通过
- `node tools/module-progress/sync.mjs --check` —— 台账与蓝图一致

## 后续待办

1. **常驻调度未接入**（蓝图：「应作为定时任务常驻运行，而不是出事才查」）。
   蓝图与任务书都要求常驻，当前只交付了**可重复调用的端点**
   （`POST /warehouse/inventory/reconciliation`，无幂等：同区间核两次得两条记录，
   因为「昨天核过什么」和「刚才核过什么」本就是两条不同的时点事实）。
   接入调度器时需要一并处理：调度身份（服务账号如何获得 `warehouse.inventory.update`，
   `triggered_by` 列已经为它留好了位）、失败重试、以及默认区间是否应固定为「上一个自然日」
   而非缺省的「近 30 天」。**目前仓库里没有调度基础设施，这是跨模块工作，不在本车道范围内。**
2. **发现表保留策略**：结论只增不减，需要定归档/清理规则（V54 已 REVOKE DELETE，
   清理要走分区或专门的维护命令）。
3. **日桶时区统一**：后端按数据库会话时区分桶、前端按浏览器本地时区标注，不同时区访问可能对不上同一天。
4. **「账平但潜在重复」目前不报**：可疑扫描只覆盖有差异的余额行。
   要覆盖全量需要给「签名相同但账平」的组合单独定级，否则噪音会淹没真信号。
5. **下游对接**（蓝图关系的另一半）：1 工作台预警按 `severity` 决定是否阻断、
   4 外部仓对账按 `windowStart/windowEnd` 取时间桶 —— 本模块只把这两样准备好了，尚未接线。
