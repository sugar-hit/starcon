# 2026-10-07 · 7.4.2.2 盘点差异处理

## 结论摘要

7.4.2.2 已交付：迁移 `V62__stocktake_variance_handling.sql`、13 个端点、工作区 `/inventory/variance`、
集成测试 20 例 + 前端纯函数 14 例。Flyway 版本按调度器预分配取 **V62**（未自行找最大版本号）。

蓝图对这个模块只点了一条红线，其余都是围绕它展开：

> 「**差异不直接改余额**，必须经调整单与审批，这是实体目录明确的红线。」

这条决定了整个模块的形态：**差异改账必须有唯一一条合法通道，而且这条通道要能被数据库自己证明
没被绕过**。

上一轮 7.4.2.1 刻意不替差异下结论、录入实盘也不动库存余额，红线就落在这里。

## 关键 trade-off

### 1. 红线做成 DB 不变量，不靠应用层自觉

最直接的实现是「服务层保证只有 post() 会改余额」。问题是这句话需要信任全部调用方：拿到连接直接
`insert into inventory_entry`，或在别的服务里写一遍，防线就漏了。

落成数据库不变量，共 9 组保护：

| 保护                                                                                     | 挡住的绕过路径                   |
| ---------------------------------------------------------------------------------------- | -------------------------------- |
| `inventory_entry` 入账闸门（ref_type='STOCKTAKE_VARIANCE' 时调整单必须 APPROVED/POSTED） | 未审批就入账                     |
| 延迟约束触发器校验 Σ分录 = Σ调整单行                                                     | 入账金额与单据金额对不上         |
| 状态机白名单（POSTED 只能从 APPROVED 转到）                                              | 草稿直接跳到已入账               |
| 调整单禁删触发器                                                                         | 删掉证据                         |
| 已过账调整单行不可改 + 已过账行部分唯一索引                                              | 事后篡改已入账金额               |
| IGNORE 终态不可翻案                                                                      | 事后把「忽略」改成「调整」重入账 |
| 归因结案后内容不可改                                                                     | 事后改归因理由                   |
| 审计 append-only                                                                         | 事后删审计痕迹                   |

**为什么值得为这个模块单独写一个 controller 和这么多触发器**：盘点差异最终要追责到人，而「这笔差异
到底有没有走审批」正是事后争议里最难自证清白的一类问题。做成数据库不变量之后，它可以被一句
`select * from pg_trigger` 确认，而不是一句需要信任应用代码的承诺。

### 2. 刻意**不**在 `inventory_balance` 上加全局触发器

最彻底的做法是给 `inventory_balance` 加一条「任何无审批来源的更新都拒绝」的全局触发器。**没做**，
因为它会连带打掉既有模块和既有集成测试的直写夹具 —— 那是跨车道回归，代价远大于收益。

改为只封堵「差异改账的唯一合法通道」：差异的每一笔入账都必须携带
`ref_type='STOCKTAKE_VARIANCE' + ref_id=<调整单 id>`，闸门触发器只校验这一条路径。这条通道本来就是
本模块自建的（见下），封它不碰任何人。

### 3. 差异只读，一份真相

不重算差异，直接读 7.4.2.1 的 `stocktake_line.variance` 生成列（`counted_qty − book_on_hand`）。

如果自己再算一份（比如按明细重新聚合），库里就有两个「差异」，从此无法判断该信谁。本模块存的是
**处置**（谁、为什么、怎么处理、批没批、入没入账），一行差异都不自己造。

### 4. 调整单自落地，并声明它不是 7.4.1.1

蓝图把 7.4.1.1 调整单列为下游，而它尚未交付。没有下游就不能把差异处理停摆 —— 账面与实物只会越差
越远。所以本模块自落地一个调整单实体（`stocktake_variance_adjustment` + `_line`）走完闭环，并在
`GET /capabilities` 与报告里**明确声明「不是 7.4.1.1 的实现」**。

两者交接靠 V1 已有的定位键 `inventory_transaction(ref_type='STOCKTAKE_VARIANCE', ref_id=<单 id>)`，
跨模块不建 FK、不查对方的表（AGENTS.md 的同一纪律）。7.4.1.1 落地后应改挂它的调整单，本模块逻辑不动。

### 5. 审批没有接入 17.4，如实说替代机制

审批中心尚未交付。本模块用的是可审计的替代方案：

- DB 级状态机（DRAFT → PENDING_APPROVAL → APPROVED → POSTED，含 REJECTED / CANCELLED）
- 提交人 ≠ 审批人（自提自批被服务层拒绝）
- append-only 审计时间线（谁、何时、为什么、批没批、入没入账）

**没有**声称已接入审批中心。17.4 落地后，`approve`/`reject` 的实现换成调用审批中心即可。

### 6. 入账用带符号增量，不覆盖 bucket

「盘盈盘亏入账」最直觉的实现是把 bucket 的 on_hand 覆盖成实盘数。**没做**：

盘点冻结之后仓库照常收发（这正是 TIMING 时点差这类原因存在的原因）。把 bucket 覆盖成 counted
会把冻结期间发生的真实交易**抹掉**，账面反而比盘点前更错。

实际做法是逐行写 `inventory_entry(kind='ADJUSTMENT', on_hand_delta = variance)`，再由增量同时更新
`inventory_balance` 与 `stock_bucket` —— 冻结值不动，当前余额逐步收敛到实盘。

### 7. 报告率值在 Java 端算，不在纯文本 SQL 里算

纯文本 SQL 拼出来的 `round(count(*) filter (...) / count(*), 4)` 会有类型推断陷阱：分母为 0 时 PG 报
division by zero（而不是返回 null），表达式类型在不同上下文里也不稳定。

改为 SQL 侧只出真算聚合并显式 `::numeric(19,4)`，率值在 Java 端用 `BigDecimal` 除（4 位小数 HALF_UP）。
**分母为 0 返回 `null` 而不是 0** —— 「没有盘盈」与「盘盈率 0%」是完全不同的两件事，写成 0 会让用户
以为盘过了且没差异。

### 8. 金额口径如实报 `amountBasisAvailable=false`

蓝图只要求「含盘盈率、差异率指标」，没规定是金额还是数量。查过全库（warehouse 与 catalog 两侧所有
迁移 + `db/schemas`）：**不存在任何库存成本列**。catalog 侧只有 `supplier_quote_price_tier`，那是报价
且跨服务，不能拿来给库存计价。

所以只出数量口径的率（盘盈率、盘亏率、数量差异率、行差异率、归因完成度），并在报告里显式声明金额口径
不可用及原因。宁可少给一个指标并说清为什么，也不给一个用采购价冒充库存成本的假指标。

### 9. 原因枚举里「未入账」与「时点差」必须分开

蓝图原文列了五类原因。合成四类的话，最后一类通常会变成「其他差异」，而这一类恰好把最需要回答的
问题提前绕过去了：**这条差异要不要动账？**

- `UNRECORDED` 未入账 —— 漏记流水，**账错了**，该补一笔流水
- `TIMING` 时点差 —— 冻结时点与实际不符，**账没错**，错的是拿冻结值当今天的余额看

两者的数量口径可能完全一样，处置却相反。合成一类就等于把判断藏进了一个筐里。

### 10. 归因生命周期：一行至多一条未结归因

- **RECOUNT**（复盘）：结案归因并清空 `counted_qty` / `counted_at` / `counted_barcode`，
  单据状态退回 `COUNTING`，可重新归因 —— 复盘完的货应该能重新算差异
- **IGNORE**（忽略）：终态，DB 拒绝再次归因
- **POSTED**：终态
- **REJECTED / CANCELLED**：释放归因，可重来（单被驳回不等于货的差异消失了）

靠部分唯一索引（一行至多一条未结归因）而非服务层检查 —— 并发下服务层检查会漏。

## 踩到并修掉的问题

1. **归因表的 CHECK 写错了** —— 最初写 `(decision='ADJUST') = (adjustment_id IS NOT NULL)`，
   但归因发生在建单**之前**，「已归因待建单」是合法中间态。改为
   `CHECK(adjustment_id IS NULL OR decision='ADJUST')`。
2. **归因不可变触发器把「挂 adjustment_id」也拦了** —— 建单时正要给归因行回填单号。改为允许
   `OLD.adjustment_id IS NULL AND NEW.adjustment_id IS NOT NULL` 且其余字段全等的唯一一次挂单。
3. **纯文本 SQL 里的 `resolved_at` 漏 cast** —— jOOQ 把 timestamptz 参数按 varchar 送，PG 报
   `column resolved_at is of type timestamp with time zone but expression is of type character varying`。
   只在传非 null 时才炸（null 不走类型检查）。两个 timestamptz 槽位都补了 `?::timestamptz`。
4. **`ADJUSTMENT_FROM` 漏 join `_line` 表** —— 聚合引用 `al.id` 报 bad SQL grammar。
5. **三处测试自身写错**：状态断言漏 `.name()`；`LOSS` 筛的是已录入行（未录入时 variance 为 null，
   查不到）；`bucketOnHand` 需按库位编码 join `warehouse_location`。
6. **跨租户读差异明细返回 404 而非空集** —— `requireSheet` 先执行。这**比返回空集更强**：
   空集会被读成「这张单没差异」，404 才是「这张单不是你的」。
7. **`Notice` 组件签名**是 `{tone, title, children?, action?}`，没有 `icon` prop；`tone` 用
   `danger`/`warning` 而非 `error`。`exactOptionalPropertyTypes: true` 要求 `hint` 用条件展开。

## 文件清单

**后端**

- `backend/services/warehouse/src/main/resources/db/migration/V62__stocktake_variance_handling.sql`（新增）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/StocktakeVarianceModels.java`（新增）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/StocktakeVarianceService.java`（新增）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/StocktakeVarianceController.java`（新增，13 端点）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/StocktakeVarianceIntegrationTest.java`（新增，20 例）

**契约**（codegen 产物，按流程生成不手改）

- `packages/api-contracts/src/warehouse.openapi.json`、`packages/api-contracts/src/catalog.ts`
  （新增 17 类型、消失 0、既有类型零变化）

**前端**

- `apps/web/src/app/inventory/variance/page.tsx`（新增）
- `apps/web/src/components/inventory/stocktake-variance-workspace.tsx`（新增）
- `apps/web/src/components/inventory/stocktake-variance-model.ts`（新增）
- `apps/web/src/components/inventory/stocktake-variance-model.test.ts`（新增，14 例）
- `apps/web/src/lib/api-proxy.ts`（放行差异处理全部形状，无 DELETE）

**台账与会话**

- `docs/product/capability-blueprint/module-progress.md`（仅 7.4.2.2 那一行的状态与证据；
  统计段由 `sync.mjs` 重生成：已交付 86 → 87，完成率 18.5% → 18.7%）
- 本文件

## 验证

- `./mvnw -o -pl services/warehouse -am test -Dtest=StocktakeVarianceIntegrationTest`
  → **Tests run: 20, Failures: 0, Errors: 0**（真实 Testcontainers PostgreSQL 16、强制 RLS、连接池压到 1）
- `./mvnw -o verify`（全量四服务）→ **BUILD SUCCESS**，identity 42.176s / catalog 1:49 /
  channel 10.087s / warehouse 30.561s，总 3:12 min
- 租户隔离三断言全跑：两租户互不可见、伪造 `X-Tenant-Id` 无效、A→B→A→B 交替复用同一条物理连接
  不串租户，并逐张表确认 `relforcerowsecurity`
- codegen 语义比对：727 DTOs（基线 710）→ 新增 17 类型、消失 0、既有类型内容零变化
- `pnpm test` → `Test Files 61 passed (61)`、`Tests 1091 passed (1091)`
  （基线 60 files / 1077 tests，**+14 测试，无回归**）
- `pnpm typecheck` 3/3、`pnpm lint` 3/3（仅 `variant-combination-workspace.tsx` 既有 warning）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` 成功，产出 `inventory/variance` 路由
- `node tools/module-progress/sync.mjs --check` → `✓ 台账与蓝图一致`

## 后续待办（下游，不是本模块欠账）

1. **7.4.1.1 调整单** 落地后，本模块的调整单实体应改挂到它上面，交接键
   `inventory_transaction(ref_type='STOCKTAKE_VARIANCE', ref_id=...)` 已经留好，本模块逻辑不动。
2. **17.4 审批** 落地后，`approve` / `reject` 改为调用审批中心；当前的 DB 级状态机 + 职责分离 +
   append-only 审计可作为过渡期方案，也可保留为审批中心的兜底校验。
3. **13 分析（盘点差异统计）**：蓝图把本模块列为其上游。本模块的
   `GET /sheets/{sheetId}/report` 已给出单张盘点单的完成度指标，跨单/跨周期的统计留给 13。
4. **库存成本列**：报告的金额口径需要它。落进数据模型后（大概率在 catalog 或 finance 侧），
   `amountBasisAvailable` 改为 true 并补金额口径率，届时才谈得上「差异金额」。
