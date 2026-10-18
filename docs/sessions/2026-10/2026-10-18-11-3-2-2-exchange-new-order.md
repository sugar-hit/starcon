# 11.3.2.2 换货新单（阶段 D4，L3 锚点 11.3.2）

- **日期**：2026-10-18
- **车道**：`.worktrees/11.3.2.2`，分支 `feat/11-3-2-2`
- **基线**：`integration/module-dev` @ `55bf6888`

## 一句话

在 11.3.2.1 换货授权（已交付）之上建立换货关联（原售后 ↔ **真**新销售单），并按**真实发货记录**跟踪换货闭环状态。

## 蓝图四问（原文，docs/product/capability-blueprint/11-aftersales-and-returns.md:185-190）

- 职责：建立换货关联（原售后 ↔ 新销售单/发货单），跟踪换货闭环状态，防止重复换货。
- 输入：换货授权；新单创建请求。
- 输出：换货关联记录（`exchange_order_link`）+ 新单状态跟踪。
- 关系：上游 = 11.3.2.1；下游 = 9 订单、8 出库。**换货新单不修改原出库事实，原单保持完整可追溯。**

## 落地四件

| 类别 | 位置 |
| --- | --- |
| 迁移 | `backend/services/trade/src/main/resources/db/migration/V167__trade_exchange_order_link.sql` |
| 端点 | `ExchangeOrderLinkController` → `/trade/aftersales/exchange-orders`（8 个） |
| 页面 | `apps/web/src/app/aftersales/exchange-orders/page.tsx` + `exchange-order-link-workspace.tsx` |
| 测试 | `ExchangeOrderLinkIntegrationTest`（24）+ `ExchangeOrderLinkTenancyIntegrationTest`（6）+ 前端 model（32）+ allowlist（5） |

## 三个关键决策

### 1. 新单必须是**真**销售单，不是自己的一串编号

最容易做成假的写法：记一行 `new_order_no` + `status='SHIPPED'`，界面上「换货已发货」，而 `sales_order` 里根本没有这张单。所以每次建单都真的在 `sales_order` 建单并写齐三张子表（V99 的延迟约束触发器在提交时逐项验收）。收货地址与客户快照**照抄原单**；原单没有地址快照时拒掉而不是编一个地址。

**代价（唯一一处 ALTER 既有表）**：`sales_order.manual_kind` 的 CHECK 里没有能表示「换货单」的值（只有 `OFFLINE_STORE/B2B/DROPSHIP/SAMPLE`，且强制 `order_source='MANUAL'` ⟺ `manual_kind` 非空）。塞 `SAMPLE` 会让订单列表把换货单显示成「样机单」—— 往事实表写一个假的业务分类，比不建单更糟。按 V99 文件头自己写的「R6 加值」纪律补 `'EXCHANGE'`，写法幂等（`DROP IF EXISTS` → `ADD`）。

**遗留缺口（如实报出）**：9.3.2.2 的 `SalesOrderManualKind` 枚举里还没有 `EXCHANGE`，所以手工单列表**按子类筛选**时这张换货单不会被筛出来（不筛选时正常出现）。补枚举属 9.3.2.2 车道，本车道不越界改它的 Java。

### 2. 闭环只认真实表，「已发出」的手工入口根本不存在

- `DISPATCHED` 的判据是**读** `shipment_record`（9.4.1.1，V127，`sales_order_id` 指向这张新单）。发货事实归 8 出库，本服务只读不写；读不到就一直停在 `CREATED`。
- `sync` **不接受任何状态入参**，api-proxy 对 `/{id}/dispatch`、`/{id}/close` 刻意不放行（测试反向断言 404）——放行它等于给假交付开门。
- DB 层另有 `trg_exchange_order_link_dispatch` / `trg_exchange_order_link_closure` 兜住绕过服务层的直写，另有表级 CHECK 把「已发出 ⇔ `shipment_count>0`」「已发出 ⇒ 新单已 SUBMITTED」写成不可能绕过的不变量。

**CLOSED 当前只能由「已发出 + 旧货经批准免回」达成** —— 这是如实不是漏做：实查 V161 的 `rma_authorization` 词表**没有 `RECEIVED`**，旧货实收与质检状态机未交付。自造 `RETURNED` 会与将来的实收状态机打架（与 11.3.2.1 当初不落 `RETURNED` 同一条理由），`capabilities.oldGoodsReturnReceivedDelivered=false` 如实报出。

### 3. 防重复换货的顺序是 load-bearing 的

`UNIQUE(tenant_id, authorization_id)` + `UNIQUE(tenant_id, authorization_line_id)` + `UNIQUE(tenant_id, new_order_id)` 三条都在 DB 层。服务层**先锁 `exchange_authorization` 再建单**——两次并发请求都会各建出一张**真** `sales_order`（各拿一个真单号），所以唯一约束必须落在 `exchange_order_link` 上才能在发出副作用**之前**判掉第二次。测试断言：重复请求后 `sales_order` 里 `EXCHANGE` 单**仍只有一张**。

## 踩到的真实坑

- `char` 不带长度在 PostgreSQL 里是 `char(1)`：绑币种时 `?::char` 把 `'USD'` 截成 `'U'`，撞 CHECK。改成 `?::char(3)`。
- 聚合查询**永远返回一行**：没有匹配发货记录时 `array_agg` 给的是 NULL 而不是「没有行」。直接把 NULL 当 `tracking_status` 写进去会撞 CHECK，正确语义是 `UNSET`。
- **EXCHANGE 类售后商品行不带金额**（11.1 的既有领域规则，DB 触发器钉死）。种子数据一开始按普通退款单写了 `requested_amount`，被当场拒掉。
- 乐观锁检查要**先于**状态检查：拿着旧版本号的人看到的是过期视图，报「版本冲突」比报「已经不是草稿态」准确。
- jOOQ 把 PostgreSQL 的 42501 权限错误包装成 `bad SQL grammar`，断言权限拒绝必须看**根因消息**（`hasStackTraceContaining`）。

## 边界

- 撤销只允许**仍是草稿且从未发货**：已提交走 9.2 审单驳回、已发货走真实退货，两种都 409，不假装能撤。
- 撤销时同步把 11.3.2.1 的 `exchange_outbound_demand` 置 `CANCELLED`（新单都不发了，需求不能还挂 `PENDING`，否则 9.3.3.1 会为一件不该发的货建出库单）。
- 撤销**刻意不释放替换货预占**：预占的写入所有者是 warehouse，释放走 11.3.2.1 的撤销授权。界面与事件流都写明这一点。
- api-proxy 采用**新增一条 `services[]` 元素**（未碰既有 trade 分支的超长单行 alternation），与 11.3.1.2 / 11.3.2.1 同一做法，多车道并入零冲突。

## 验证

- L0：`./mvnw -q compile` 绿；`pnpm typecheck` 绿。
- L1：`ExchangeOrderLinkIntegrationTest` 24 条 + `ExchangeOrderLinkTenancyIntegrationTest` 6 条 = **30 条全绿**（Testcontainers 真实 PG16，租户隔离三条断言实跑在本模块真实表上，连接池压到 1）。
- 迁移在真实 PG16 上单独跑过全量迁移链确认落地：三张表 `rls=true force=true`、4 个触发器齐、`manual_kind` 词表已含 `EXCHANGE`。
- 前端：`pnpm vitest run apps/web/src/app/api/trade` **36 文件 195 条全绿**（共享累积文件的全量回归；同时反向断言同 L3 的 11.3.2.1 `/exchanges` 全部路由未被本车道弄坏）。

## 后续待办（不在本车道范围）

1. 9.3.2.2 手工单模块把 `EXCHANGE` 补进 `SalesOrderManualKind` 枚举。
2. 9.3.3.1 新出库接手后，`exchange_outbound_demand` 的 `PENDING` 才会有真实下游。
3. 11.2 退回旧货交付 `RECEIVED` 实收状态机后，`CLOSED` 才能表达「旧货已入库验收」而不只是「免回」。
