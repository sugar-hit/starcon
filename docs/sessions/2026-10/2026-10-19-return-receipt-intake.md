# 11.2.2.1 退货入库（L3 11.2.2 退货实收）

- **日期**：2026-10-19
- **车道**：`feat/11-2-2-1`（worktree `.worktrees/11.2.2.1`，基线 `integration/module-dev` @ `8300d842`）
- **状态**：已交付，验证全绿

## 决策摘要

按蓝图 `docs/product/capability-blueprint/11-aftersales-and-returns.md` 的 11.2.2.1 原文实现：
登记退回实物（扫码核对 SKU 与序列号、清点数量、拍照留证、判定外包装与商品状态），
输出实收记录 + 关联授权行分配 + **待检入库分录（不是可售）**。

命名空间 `/trade/return-receipts`，独立于 `/trade/rma-authorizations`（11.2.1.1）
与 `/trade/rma-instructions`（11.2.1.2）：本模块的产物是**另一张单据**（一次收货事件 = 一个包裹）。

## 关键 trade-off

### 1. 硬规则 1 钉在**三处**，而不是只在服务层

蓝图加粗句「退回商品默认进待检状态」是本模块唯一的事故防线，单靠服务层校验等于没有 ——
直连 SQL、改一次代码都能绕过。最终形态：

| 位置 | 形态 | 挡住的场景 |
| --- | --- | --- |
| `inventory_state` 表级 CHECK | **只允许一个取值** `PENDING_INSPECTION` | 想改成可售必须先改 CHECK（一次需要评审的迁移） |
| `sellable_qty_delta` 表级 CHECK | **恒为 0** | 「可售增加了多少」成为必须被显式填 0 的字段，而不是根本不存在的一列 |
| 分录由 **DB 触发器**生成 | 插入语句里入库列全是常量 | 绕过服务层也拿不到一张「已进可售」的分录 |

**代价**：想支持「批量直转可售」这个真实诉求 someday，必须改迁移。这是**有意的代价** ——
那次改动会强制把 11.2.3.1 的质检结论带进同一张分录，而不是让任何人随手勾一个下拉框。

配套：传输层**刻意不提供**能改入库状态的字段（`postReceipt` 的请求体里没有「目标状态」）。
传一个可选目标状态，接口就等于在问「这次要不要直接当新品卖」。

### 2. 硬规则 3 的额度占用**含草稿**

只算 POSTED 的话，两张草稿各自校验时都合法，并发入库时总量已经超了。
取「草稿也占」换来的是：录错了要先撤销草稿才能重录。
错误消息直接写明被哪几张单占了（`t_taken_by` 用 `text` 不用 `varchar(32)` ——
两单同占时拼出来的说明串远超 32 字符，varchar 会把业务错误报成类型溢出）。

服务层与 DB 触发器**共用同一口径**，且入库时刻对整单**再算一次**。
界面显示「还能收 3 件」而点提交被拒，是最消耗信任的一种不一致。

### 3. 两个判定维度分两列，并让 `NOT_APPLICABLE` ≠ `NOT_INSPECTED`

蓝图 11.2.3.1 的加粗句「包装破损但商品完好可判可再销售，缺少配件则应判残次」
在任何单值列里都无法表达。`MISSING_PARTS`（配件缺失）刻意单列而不并进 `DEFECTIVE`：
配件缺常是仓库漏装，商品损坏常是运输或买家，索赔方向不同。

`NOT_APPLICABLE` 与 `NOT_INSPECTED` 合成一个值，会让入库闸门把
「这件货本来就没有这个维度」（散装 / 无包装无配件）当成「还没判」一直拦住。

### 4. 实收行只存授权行 ID，但**分录自带 SKU 快照**

两者用途不同：实收行是**证据**（要跟着订单行核对，SKU 实时读）；
分录是**跨服务交接凭据**（7 库存 / 8 仓库未交付，由同步器消费），
必须在订单行被改名之后仍能独立读出 SKU。

### 5. 分录的 UPDATE/DELETE 权限也收回

照片、留痕是纠纷证据，已按 V161/V165 的先例收回。但入库分录同样收回 ——
它是待检库存的**账**：改一条而不改实收行账实不符，删一条等于凭空少了待检库存。
**刻意不做反向冲销分录**：撤销走显式状态，而报废是不可逆的、需审批与原因。

### 6. 跨车道 codegen 带出了 channel 的存量漂移

`catalog-contracts.py` 需要全部六个服务的 spec，本次从 worktree 重新生成六个 spec 时，
发现基线 `351f618c`（失联车道抢救）加了 channel 端点但没重生成契约
（`channel.ts` 缺 `Listing*` 系列）。这是**别的模块的存量漂移**，已 `git checkout` revert，
不纳入本车道提交 —— 由该模块的车道自己重生成。

## 已知边界（本模块**刻意不做**）

| 不做 | 归属 | 原因 |
| --- | --- | --- |
| 直接进可售 / 质检判定 / 分配到订单行 | 11.2.3.2 / 11.2.3.1 / 11.2.2.2 | 均未交付。渲染一个注定被拒的按钮等于告诉运营系统坏了 |
| 处理超收（按实际退回） | 11.2.2.2 | 当前**整笔拒绝**并报错。放行超收会让「授权 3 件、实收 5 件」成为库里有、界面答不出解释的状态 |
| 写订单行已退数量 | 11.2.2.2 | 写入所有者是 11.2.2.2，抢别人写入所有权违反 AGENTS.md |
| 碰库存表 / 回冲分录 | 7 库存 / 8 仓库 | 独立数据库，跨服务禁止 SQL |
| 已入库后撤销 | 11.2.3.2 | 分录已生成，回冲属处置决定 |
| 存照片二进制本体 | 18 集成 | 列表查询会把整库图片拖进内存，而列表不需要看图 |

**超收这条要特别注意**：它是 11.2.2.2 的职责，在本模块未交付期间，
实收多于授权量会**整笔被拒**而不是被截断。这是有意的（截断会让「买家寄了几件」
与「我们记了几件」从此对不上），但 11.2.2.2 交付后需要回到同一处放宽。

## 文件清单

**后端（新增 6）**
- `backend/services/trade/src/main/resources/db/migration/V172__trade_return_receipt_intake.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnReceiptModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnReceiptService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ReturnReceiptController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/ReturnReceiptIntegrationTest.java`
- `backend/services/trade/src/test/java/com/starcon/trade/ReturnReceiptTenancyIntegrationTest.java`

**前端（新增 5）**
- `apps/web/src/app/aftersales/return-receipts/page.tsx`
- `apps/web/src/components/aftersales/return-receipt-model.ts`
- `apps/web/src/components/aftersales/return-receipt-model.test.ts`
- `apps/web/src/components/aftersales/return-receipt-workspace.tsx`
- `apps/web/src/app/api/trade/return-receipt-allowlist.test.ts`

**改动（3）**
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，未动既有条目）
- `packages/api-contracts/src/contracts/trade.ts`（codegen 产物，23 个 `ReturnReceipt*`）
- `packages/api-contracts/src/trade.openapi.json`
- `docs/product/capability-blueprint/module-progress.md`（**只改 11.2.2.1 那一行**）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `./mvnw -q compile` | 通过 |
| L0 类型 | `pnpm typecheck` | 3/3 成功 |
| L1 模块 | `./mvnw -pl services/trade -am test -Dtest='ReturnReceipt*'` | **29/29**（25 主 + 4 租户三断言） |
| 前端 | `pnpm vitest run apps/web/src/app/api/trade`（改了 api-proxy 必跑全量 allowlist） | **39 文件 / 208 例通过** |

迁移另在独立 PG 16 容器里按版本顺序跑通全链 **42 个迁移**（含 V172），
逐张断言 5 张表的 `relforcerowsecurity = true`。

## 后续待办

1. **11.2.2.2 实收分配交付时**：回 `ReturnReceiptService.insertLines` 与
   `trg_guard_return_receipt_line` 同一处放宽超收（当前整笔拒绝），
   并把订单行已退数量补上。
2. **11.2.3.1 / 11.2.3.2 交付时**：若要支持「转可售」，
   必须改 `return_inventory_posting` 的两条 CHECK 并把质检结论带进分录 ——
   这是设计上的显式摩擦点，不是遗漏。
3. **18 集成对象存储交付后**：`return_receipt_photo.photo_source` 的
   `MODULE_18_OBJECT_STORAGE_SYNC` 分支按同一形状接入，表结构与查询都不用改。
4. **页面导航**：本车道未改 `apps/web` 的导航配置（多车道共用），
   `/aftersales/return-receipts` 的入口由并入时的集成批次统一挂。
