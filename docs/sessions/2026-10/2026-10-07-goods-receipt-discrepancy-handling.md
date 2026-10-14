# 8.2.1.2 收货差异（处理）— 车道交付日志

- 日期：2026-10-07
- 车道：8.2.1.2（分支 `feat/8-2-1-2`，worktree `.worktrees/8.2.1.2`，基线 `integration/module-dev` @ `8dbf182`）
- L3 锚点：8.2.1 · 阶段 D3 · 预分配迁移版本 **V78**

## 决策摘要

### 1. 蓝图输出写「索赔单（6 采购）」，但**不建假索赔单**

蓝图输出是「差异处理记录 + 索赔单（6 采购）+ 库存拒收不入账记录」。但 6.3.2.2 采购单域
**未交付**，而 AGENTS.md 明令「每张事实表只有一个写入所有者」「禁止跨服务 SQL / FK / 共享视图」。

照抄 8.2.1.1 对「回写采购单」的**同一判断**：warehouse 侧在
`goods_receipt_discrepancy_claim` 如实记 `source_ref_id` + 索赔意图与状态，
上游 6.3.2.2 交付后由**上游订阅事件**建自己的索赔单并回填 `claim_ref_id`。

- **不建**一张假的 `purchase_claim` 表冒充索赔单 —— 那等于凭空造出第二个索赔事实所有者。
- `claim_ref_id` 在上游交付前恒为空，并由 DB 触发器 `guard_discrepancy_claim_state`
  **禁止自行填写**（错误信息点名 6.5.1 拥有该字段）—— 不是「没来得及填」，是边界。
- 索赔状态只到 `SUBMITTED` 为止：受理与结算是 6.5.1 的写入所有者，warehouse 不代写。
- `WITHDRAWN` 是 warehouse 自己的终态（提交前撤回）。**已提交不可撤回**：撤回只允许在
  `INITIATED`，DB 触发器钉死，否则对账那边会出现一笔凭空消失的索赔。

### 2. 硬规则「破损与短少必须拒收或待检入库」钉**两处**

蓝图原文的「直接入可售会把问题货卖出去」，最危险的路径不是服务层写错代码，而是有人
**绕过服务层直连 SQL**，或者某个下游作业拿一条差异去生成入库分录。所以：

| 层     | 触发器                          | 拦什么                                                                  |
| ------ | ------------------------------- | ----------------------------------------------------------------------- |
| 服务层 | `decide()`                      | 风险差异类型 + `disposition='ACCEPT'`                                   |
| DB     | `guard_discrepancy_disposition` | 同上（改决定绕不过服务层，仍要拦 DB）                                   |
| DB     | `guard_discrepancy_stock_move`  | 风险差异的 `INBOUND_TO_SELLABLE`，以及任何指向 `role='SELLABLE'` 的入库 |

两条 DB 触发器各管一半：改决定绕不过第一条，绕过决定直接写流转绕不过第二条。

**把 `WRONG_ITEM`（型号不符）与 `EXPIRED`（过期）一并纳入风险类型**：蓝图只点名破损与短少，
但「型号不符」意味着这箱货不是买家要的那个型号，「过期」意味着它根本不该再卖 ——
四类里只有超收与错发是「货本身没问题、只是单据对不上」，那两类才允许正常入库。

**部分接收是最容易被当成绕过口子的路径**，所以测试里专门有一条：
`PARTIAL_ACCEPT` 的接受部分也不得入可售。只拦 `ACCEPT` 是不够的。

### 3. 接受部分是**质量状态流转**，不是凭空加货（本模块最容易写错的一处）

处理决定里的「接受 N 件」有两种完全不同的库存后果，混起来会凭空造出可售货：

| 情形                   | 触发条件                            | 库存动作                                  |
| ---------------------- | ----------------------------------- | ----------------------------------------- |
| 货**已在库里**，要挪走 | `fromQualityStatusId` 给了且 ≠ 去向 | 从原质量状态减 + 往去向加（**总量守恒**） |
| 货**第一次进库**       | `fromQualityStatusId` 为空          | 纯入库（+）                               |
| 货**已在目标状态**     | 两者相同                            | **完全不动库存**（记 `NO_MOVE`）          |

第三种就是**超收确认**：那 5 件在收货那一步已经进了可售 bucket，处理时若再加一次，
库存里就凭空多出 5 件可售货，而这 5 件本来就在库里、会被直接卖掉。测试
`超收可以正常入库因为货本身没问题` 断言的正是「仍是 105 而不是 110」。

「这批货现在在不在库里」由**调用方如实声明**（是否给 `fromQualityStatusId`），服务层不猜 ——
猜错的后果是凭空多出或少掉一批货。

### 4. 拒收不入账有**两条**真实路径，都留痕

蓝图输出那句「库存拒收不入账记录」在本模块落成五种方向：

| 方向                  | 含义                           | 余额               |
| --------------------- | ------------------------------ | ------------------ |
| `NOT_POSTED`          | 货**从没进过库**（典型是短少） | 不动               |
| `WITHDRAW`            | 货**已入库又改判拒收**         | 真扣（负增量分录） |
| `INBOUND_TO_HOLD`     | 转入待检 / 残次                | 正增量             |
| `INBOUND_TO_SELLABLE` | 正常入库                       | 正增量             |
| `NO_MOVE`             | 货已在去向质量状态里           | 不动               |

区别就是「这批货到底进过库没有」。`NOT_POSTED` 那一行是短少这类差异**唯一的库存痕迹** ——
「为什么不入库」答不出来才是真问题。

本模块**不回写** 8.2.1.1 的 `rejected_quantity`：那是收货现场就发生的事实，
事实表只有一个写入所有者。

### 5. 凭证本体真落库，而不是存「将来会有的」外链

拍照留证是蓝图点名的硬职责。只存 `storage_key` 等于交付一个**取不出照片**的功能 ——
索赔时拿不出证据，那比不做更糟。

- catalog 的 `MediaStorage` 端口在 catalog 服务内，跨服务 import 会越过模块边界纪律，
  本仓库也没有共享的对象存储库 → 凭证本体落 PostgreSQL（`bytea`）。
- 单条上限 1.5 MB，服务层先挡一次给可读报错，DB `CHECK byte_size<=1500000` 再兜一次防绕过。
- 前端在浏览器侧把照片压到长边 ≤1280px 的 JPEG 再上传（`fileToEvidencePayload`）。
  手机随手拍的货损照片压到这个尺寸通常在 100 KB 量级，且能落在代理的 200 KB 上传上限内。
- `sha256` 让「两次上传是不是同一张照片」有答案，也让事后改动可被发现。
- 读回时带 `dataUrl`，前端直接 `<img src>` 渲染 —— 不需要给代理再加一条二进制透传分支
  （`api-proxy.ts` 是多车道共享文件，**只追加不改动**）。

### 6. 刻意**没有**「处理中」状态

四态状态机：`PENDING 待处理 → DECIDED 已决定 → CLAIMED 已索赔 → CLOSED 已关闭`。

「处理中」这一态唯一的存在理由是「等 6.4.2.1 质检结论」，而质检单**未交付** ——
一个没有人能推进它的状态只会让待办列表永远挂着假待办。现场拍照留证在待处理状态下
随时可做，不必先切一个中间态。

`DECIDED` **不能改判**（`DECIDED → DECIDED` 不在转换表内）：改判会让已经发生的库存分录
没法用「一次改判」讲清楚。要改判请另登记一条差异 —— 改写历史不如追加事实，
这与 8.2.1.1「预期与实收分开记录」是同一条纪律。

处理记录与差异事实是 1:1（`UNIQUE(tenant_id, discrepancy_id)`）。

### 7. 一条差异一个处理过程

`intake()` 重复接手返回 409 并提示「要改判请另登记一条差异」，而不是默默返回既有的那条 ——
后者会让现场以为新接手成功而其实什么都没发生。

## 关键 trade-off

| 选择                                          | 代价                                 | 收益                                                  |
| --------------------------------------------- | ------------------------------------ | ----------------------------------------------------- |
| 不建 `purchase_claim` 表                      | 索赔单号在 6.3.2.2 交付前为空        | 不越「唯一写入所有者」边界                            |
| 凭证落 `bytea` 而非对象存储                   | 单条 ≤1.5 MB，且前端要压图           | 索赔时**真的拿得出照片**                              |
| 接受量用「质量状态流转」而非纯入库            | 调用方必须声明 `fromQualityStatusId` | 不会凭空造出可售货                                    |
| 四态而非五态                                  | 「等质检」没有专属状态               | 没有无人能推进的死状态                                |
| 决定不可改判                                  | 改判要走新差异记录                   | 已发生的库存分录讲得清                                |
| `stock()` 限 4 位小数（严于 8.2.1.1 的 6 位） | 6 位小数的差异量无法部分接收         | 余额列是 `numeric(19,4)`，给 6 位会被**静默四舍五入** |

## 文件清单

### 新增

- `backend/services/warehouse/src/main/resources/db/migration/V78__warehouse_goods_receipt_discrepancy_handling.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/GoodsReceiptDiscrepancyModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/GoodsReceiptDiscrepancyService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/GoodsReceiptDiscrepancyController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/GoodsReceiptDiscrepancyIntegrationTest.java`
- `apps/web/src/components/warehouse/goods-receipt-discrepancy.tsx`
- `apps/web/src/components/warehouse/goods-receipt-discrepancy-model.ts`
- `apps/web/src/components/warehouse/goods-receipt-discrepancy-model.test.ts`
- `apps/web/src/app/warehouse/discrepancies/page.tsx`
- `docs/sessions/2026-10/2026-10-07-goods-receipt-discrepancy-handling.md`（本文件）

### 改动（只动自己那一处）

- `apps/web/src/lib/api-proxy.ts` — **只追加** `goods-receipt-discrepancies` 分支。
  8.2.1.1 已放行的 `goods-receipts` 读形状 / `{id}` / `{id}/movements` / `POST` /
  `{id}/receive` / `{id}/close` **逐条保持放行**，`git diff` 只有 1 行变化。
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts` — 追加一条用例，
  **同时钉住 8.2.1.1 与 8.2.1.2 两组路径**（防「取错一侧静默删掉已交付路由」）。
- `docs/product/capability-blueprint/module-progress.md` — 只改 8.2.1.2 那一行的
  「状态」「证据」两格（外加脚本自己拥有的合计计数器）。

### 刻意没动

- V70 `goods_receipt*` 六张表的任何列与约束（8.2.1.1 已交付的事实表）。
- `inventory_balance` / `stock_bucket` / `inventory_entry` / `inventory_transaction`
  的任何列、约束或触发器 —— 与 V70 同口径「只插不改」。

## 验证方式与结果

- `cd backend && ./mvnw verify` —— 见交付报告。
- `GoodsReceiptDiscrepancyIntegrationTest` 53 例实跑 Testcontainers PostgreSQL 16，
  连接池压到 1 条。
- **跨车道回归**：同一次全量 verify 中重跑 8.2.1.1 `GoodsReceiptIntegrationTest` 29 例全绿。
- **基线对照**：本车道改动前后各跑一次全量 verify，用 stash 对照确认失败归属
  （见交付报告的「基线对照」一节）。
- 静态判据复核：本迁移**没有** `ADD COLUMN`、**没有** `SET NOT NULL`、
  **没有**在既有表上建触发器，只有从新表指向既有表的 FK —— 因此对并行车道零 DDL 回归面。

## 后续待办

1. **6.3.2.2 采购单交付后**：由上游订阅差异处理事件建自己的索赔单，并回填 `claim_ref_id`
   （届时需放宽 `guard_discrepancy_claim_state` 里「上游交付前禁止自行填写」那条触发器）。
2. **6.4.2.1 质检单交付后**：「待检入库」的处理记录应能一键发起质检单，
   并在详情页显示质检结论。
3. **6.4.2.2 不良品处理交付后**：`REJECT` / `HOLD_FOR_INSPECTION` 之后接报废 / 退货 / 让步接收。
4. **6.5.1 对账索赔交付后**：索赔状态机补 `ACKNOWLEDGED` / `SETTLED`，
   受理与结算由该模块推进（warehouse 不代写）。
5. **接入收货单详情页**：当前 8.2.1.1 的详情弹窗只展示差异**事实**，
   等本模块页面稳定后，可在收货单详情里加一个「去处理这条差异」的跳转
   （本次未改 8.2.1.1 的页面，避免动别人车道已交付的文件）。
