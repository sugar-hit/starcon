# 组批 #1 · 7.2.1.1 预占需求列表（含 3 条车道并入冲突记录）

- 日期：2026-10-13
- 集成分支：`integration/module-dev`，基线 `6828689`，合并后 `9b6e491`，修复 `c3767c8`
- 本批派发 4 条车道，**交付并入集成头 1 条**，另 3 条因 `api-proxy.ts` 并入冲突挂起（见末节）

---

## 模块 7.2.1.1 预占需求列表

**这个功能做什么**：库存预占是「这批货已经被某个订单／加工单占住了，先别卖给别人」这条事实。
本模块给出**一张可筛选的预占需求列表**：谁占了（来源）、占了多少、现在处于哪个状态
（held/committed/consumed/released/expired）、还剩多少可用、已经超时多久，
外加一张按状态汇总的统计条。运营用它回答「哪些货正被占着、哪些占太久了」。

**技术落地**：

- **迁移**：`backend/services/warehouse/src/main/resources/db/migration/V102__warehouse_inventory_reservation.sql`
  - 新建 `inventory_reservation` 单表（本模块**不建兄弟模块的占位表**：`reservation_allocation`
    属 7.2.1.2、`reservation_entry` 属 7.2.1.3，一律留空待其交付时在同一张表上补子表，
    **本模块因此无需二次迁移**）
  - `tenant_id uuid NOT NULL` + `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`，
    租户来自已认证成员关系
  - 数量一律 `NUMERIC(19,6)`，禁浮点；已分配量暂记 `allocated_quantity` 标量列
  - 时间列 `timestamptz`，values 位置写 `?::timestamptz`
- **端点**（`ReservationController.java`，4 个）
  - `GET /warehouse/inventory/reservations` —— 列表：状态／来源／时间范围／关键字筛选、
    真分页、双白名单排序（非法排序键 400 而非静默忽略）、状态汇总卡
  - `POST /warehouse/inventory/reservations` —— 登记一条预占需求
  - `GET /warehouse/inventory/reservations/options` —— 状态下拉与来源类型下拉
  - `GET /warehouse/inventory/reservations/capabilities` —— 对 7.2.1.2 / 7.2.1.3 显式报
    `available=false` + `ownerModule` + 原因
- **页面**：`/inventory/reservation`（`components/inventory/inventory-reservation.tsx` +
  纯函数 `inventory-reservation-model.ts`），真实工作区，加载态／空态／错误态三态分开渲染
- **测试**：`ReservationIntegrationTest` 35 例（Testcontainers 真实 PG16，连接池压到 1）
  - `inventory-reservation-model.test.ts` 12 例 + `route.test.ts` 放行／反向拒绝两组用例
  * 租户隔离三断言实跑：两租户互不可见、伪造 `X-Tenant-Id` 无效、连接池归还后不串租户
  * `timestamptz` 断言**写回的值**（instant 与 offset 均比对），不是「没抛异常」

**关键设计决策**：

1. **预占表落 warehouse 而非 trade —— 这是本模块最需要评审的一条。**
   蓝图 07 卷「关系」句写「预占需求由 trade 持久化协调」，**本实现未采纳**，理由是全库实查：
   - `inventory_reservation` 在**任何服务里都不存在**。全库 grep 只命中 warehouse
     `V1__warehouse_inventory_core.sql` 的**文件头注释**，而那段注释明确写明
     inventory_reservation 由 warehouse 独占所有，并注明「reservation 属 7.2.1.x，本期不建空表占位」
     —— **warehouse 的 V1 早已把位置预留在这里**
   - trade（`V1__baseline` + V92～V101）**没有任何预占表**，「去 trade 查」物理上不存在
   - AGENTS.md 明令禁止跨服务读写对方表、禁止跨库 FK
   - 蓝图 07 卷开头的「后端归属」本身就写着 warehouse 拥有该表，两句有张力，取后者

   **后续 7.2.1.2 / 7.2.1.3 同样落在 warehouse。** 理由已写进迁移文件头第零节。

2. **刻意不实现释放／过期动作。** 蓝图 7.2.1.3 明写「承诺分配后不再自动 TTL 释放」，
   那是高风险不可逆动作，不属于本模块职责。页面与 `/capabilities` 显式报 `available=false`
   - ownerModule，**页面上没有点下去必然 404 的释放按钮**。

3. **摘要聚合用标量子查询而非 join 展开**，避免库位／批次维度 join 成笛卡尔积互相放大。

4. **移出了既有 route.test.ts 拒绝清单里的 `warehouse/inventory/reservations`**
   ——该清单是 7.2.1 未交付时的状态，与 transfer／ledger 当初的处理方式一致。

---

## L2 批次闸结果

| 步骤                                                         | 结果                                                                 |
| ------------------------------------------------------------ | -------------------------------------------------------------------- |
| `./mvnw verify`                                              | ✅ BUILD SUCCESS，7/7 模块，6m42s                                    |
| `pnpm typecheck`                                             | ✅ 3/3                                                               |
| `pnpm lint`                                                  | ✅ 3/3（1 条 warning 经核为 `goods-receipt.tsx` 既有告警，非本车道） |
| `pnpm test`                                                  | ✅ 2177/2177，114 文件                                               |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ 路由表出现 `/inventory/reservation`                               |
| `sync.mjs --check`                                           | ✅ 台账与蓝图一致                                                    |

### L2 抓到并已修复的一例（跨车道违约）

首次跑 `pnpm test` 时 **2176/2177**，1 例失败：
`cross-entity-transfer-allowlist.test.ts` 断言 `warehouse/inventory/reservations` 必须 404，
实得 503。

根因：7.2.1.1 交付后该路径进入代理放行名单，这条「尚未交付因而必须 404」的断言随之失效。
7.2.1.1 已同步移除 `[...path]/route.test.ts` 里的**同一份断言**，
但漏掉了本文件这**第二份拷贝**。

修复 `c3767c8`：移除过期条目并把「为什么失效」写进注释（而非静默删除）。
`cross-entity-transfer` 的 `not-a-real-action`／`settlement` 与仍未交付的
`7.4.1.1 adjustments` 拒绝断言全部保留；全库 grep 确认无第三份遗漏拷贝。

> 这正是组批协议要拦的那一类问题：单跑 L1 全绿、四条车道各自都没问题，
> 只有在集成头上跑全量才会暴露「A 的交付让 B 的断言过期」。

---

## ⚠️ 本批未并入的 3 条车道（挂起，非失败）

| 模块    | 名称     | commit    | L1                              |
| ------- | -------- | --------- | ------------------------------- |
| 8.4.1.1 | 组装单   | `13b1309` | 945 例 0 失败                   |
| 8.1.1.3 | 仓库配置 | `79a2280` | warehouse 960 / identity 236 绿 |
| 8.1.2.1 | 库位树   | `a908634` | 949 例 0 失败                   |

三条都已 `land`、工作区干净、commit 完整，**唯一障碍是 `apps/web/src/lib/api-proxy.ts`
并入冲突**。按 `PIPELINE.md` 第 3 步「不要自己强行解冲突」，本轮未做人工求并。

### 冲突是结构性的，每批都会撞

`.gitattributes` 的 `merge=contract-union` 只登记了
`packages/api-contracts/src/*.ts`、`*.openapi.json`、`docs/**/module-progress.md` ——
**`api-proxy.ts` 不在其中**，走 git 默认行级三方合并。
而它的白名单是**一行数千字符的单行正则**，行级合并的最小单位就是行，
所以任何两条车道各自往这行追加分支，必然撞同一行。

### 已完成的取证（求并方案已验证，等决策）

以基线 `6828689` 为起点，对 4 条车道的该文件做字符级 diff：

- 4 条车道的改动**逐字节可复现**（`reproduces_exactly=True`）
- 4 条车道的编辑位置**两两不相交**（`overlapping ops: NONE`）
- 每个插入片段在求并结果中**逐字存活**
- 每个正则字面量**括号平衡**

**结论**：求并在结构上是安全的。但按协议未执行，需人工决策后再并。
求并脚本与中间产物保留在 `.tmp/merge-union/`（未入库）。

### 建议的根治方向（二选一）

1. 把 `apps/web/src/lib/api-proxy.ts` 纳入 union 合并驱动器（键 = top-level alternation 分支）
2. 改成**每条路由一行 + 一个由键派生的数组**，从根上让行级合并可用

> 不修的代价：每轮 4 条车道只能并入 1 条，吞吐被钉死在 1 个/5 小时。

---

## 遗留（不属本车道，已记录待派）

- `api-proxy.ts` 既有缺口：`outbound-orders`／`printing`／`consignments`／`stocktake-variances`
  四支在 HEAD 基线就**从未登记**，对应页面在浏览器里应 404。由 8.4.1.1 基线 diff 确认与本批无关。
- 8.1.1.2 的 `WarehouseDetailService.labor()` 仍报 `rosterAvailable=false`，
  在 8.1.1.3 交付 `warehouse_shift` 后已过期，属他人端点文件，待单独派修。
- `StockOwnershipIntegrationTest` 货权档优先级存在**基线既有 flake**
  （时刻精度：规则存 `now()` 全精度、断言比对毫秒截断的 `now()`）。
  8.4.1.1 与 8.1.2.1 各撞过一次，均以 stash 基线复现证明与本车道无关；本轮 L2 未复现。
