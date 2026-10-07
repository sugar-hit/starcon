# 7.3.1.2 效期与先进先出（阶段 D3，L3 锚点 7.3.1）

- 日期：2026-10-07
- 车道：`feat/7-3-1-2`（worktree `.worktrees/7.3.1.2`）
- 基线：`integration/module-dev` @ `fc27179`

## 决策摘要

### 1. 分配逻辑建立在 `stock_bucket` 上，分到的量是**批次精确**的

开工自检确认了 7.3.1.1 留下的口径：`inventory_lot` 与 `stock_bucket` 都有批次维度，
但 `inventory_entry` / `inventory_balance` **都没有 `lot_id`** —— 批次只在库位这一层是事实。
所以「按批次的出入库流水」只能到 balance 口径（响应带 `attribution=BALANCE_GRANULAR`）。

本模块因此把全部分配逻辑建在 `stock_bucket` 上：它是批次粒度的数量事实层，
推荐的每一行都对应真实的 bucket。**这是本模块与 7.3.1.1 的关键差别**：
7.3.1.1 的流水无法按批次拆分，而 FEFO/FIFO 的取用量可以。

### 2. FIFO 有真实的排序依据，且刻意不用 `last_movement_at`

任务书要求先 grep 确认 `stock_bucket` 上有没有能当 FIFO 依据的时间列。结论是**有**：

| 列 | 语义 | 用作 FIFO 依据？ |
| --- | --- | --- |
| `inventory_lot.received_on` | 收货业务日期 | ✅ 主依据 |
| `stock_bucket.created_at` | 该库位 bucket 的建行时间 | ✅ `received_on` 缺失时兜底 |
| `stock_bucket.last_movement_at` | **最近一次变动的业务时间** | ❌ **刻意不用** |

`last_movement_at` 每动一次就前移，拿它排序等于按「最近被碰过的先后」出库，
那不是先进先出。这一点写进了 SQL 注释与集成测试断言（缺收货日期时确实退回 `created_at`，
而不是退回 `last_movement_at`）。

FIFO 排序：`coalesce(l.received_on, b.created_at::date) asc nulls last, b.created_at, l.lot_code, b.id`。

### 3. FEFO 与 FIFO 的顺序由一条**反向**夹具钉住

如果按随机数据造夹具，两种策略有可能碰巧排出同一个顺序，断言就变成了同义反复。
所以夹具刻意让「失效日期先后」与「收货日期先后」完全相反：

| 批次 | 失效 | 收货 | FEFO 位次 | FIFO 位次 |
| --- | --- | --- | --- | --- |
| LOT-A | +3 天 | −5 天 | 1 | 3 |
| LOT-B | +20 天 | −30 天 | 3 | 1 |
| LOT-C | +10 天 | −15 天 | 2 | 2 |

于是 `containsExactly(A, C)` 与 `containsExactly(B, C)` 两条断言才真的钉住了「两种排序」。

### 4. 过期库存在任何策略下都不出库，缺口逐项报出

「临期」还能卖，「过期」不能再发。若只按 FEFO 顺序取货而不排除过期批次，
最紧急的那批会被优先发出去 —— 这正是最不能出错的地方。所以过期批次在任何策略下都排除，
且它的量单列进 `ineligible.expiredAvailable`。

同理 `ineligible` 拆成五个分项（过期 / 非效期 / 非可售 / 不可拣 / 无批次），
因为**处置动作各不相同**：过期走 6.4.2 临期品处理、待检走质检、不可拣走移库。
只报一句「缺 20 件」等于没法派工。

### 5. 提前天数是**每 SKU 的配置**，不是全局常量

食品与日化的临期窗口差一个数量级，写死 30 天对两边都是错的。
所以 `lead_days` 落在策略表上，预警清单逐批次取 `coalesce(本次覆盖, 该 SKU 策略, 默认 30)`，
并在 `leadDaysSource` 里如实标明这一行用的是 POLICY 还是 DEFAULT。

界面必须显示这个来源：同样是 3 天到期，配 7 天窗口的报「临期」、配 1 天的报「正常」，
不标来源用户会以为系统算错了。

### 6. 策略开关默认关闭 —— 蓝图「第一期是否启用可延后，但维度已保留」

蓝图原文说效期/FIFO 是食品化妆品类目的硬需求，但第一期是否启用可延后。
落到 schema 上就是 `enabled boolean not null default false`。

**默认打开是错的**：系统并不知道哪个 SKU 是效期品（品类属性属 catalog，不在本服务），
默认打开等于凭空替所有租户做了「这个 SKU 受效期管理」的判断。
默认关闭时出库退回按库位顺序取可用量，响应里 `policyEnabled=false` 如实报出，
不假装已经排过 FEFO。

策略行**不可删除**（DB `REVOKE DELETE` + 控制器无 DELETE 形状）：停用走 `enabled=false`，
策略行是「这个 SKU 曾经按 FEFO 出过货」的审计凭据。

### 7. 策略主体是 SKU，不是批次

同一箱奶的每一批都用同一条策略。塞进 `inventory_lot` 会让「改一次策略」变成「逐批改」，
而且新建批次时必须重新抄一遍 —— 漏抄一批，那批货就静默退回先进先出。
因此 `UNIQUE(tenant_id, sku_id)`，一条策略管这个 SKU 的所有批次。

### 8. 指定批次的 SKU 归属由 DB 触发器兜底

复合外键 `(tenant_id, specified_lot_id) REFERENCES inventory_lot(tenant_id, id)`
只保证「批次在本租户存在」，**保证不了「批次是这个 SKU 的」**，
而后者错配会让拣货单指向一个根本不是这个 SKU 的批次。
`guard_expiry_policy_lot_sku` 补上这一条。集成测试用**同租户但另一个 SKU** 的批次验证
（用别的租户的批次测不出这一条，它会先被外键拦下，走不到触发器）。

## 踩到并修掉的四个真问题

1. **`coalesce(?, p.lead_days, ?)` 里覆盖值永远不为 null**，于是恒取第一个非空参数，
   `p.lead_days` 成了永远走不到的死代码。表现只是「配了 7 天却按 30 天算」，
   不看返回值根本发现不了。改为 `coalesce(nullif(?,?), p.lead_days, ?)` + `-1` 哨兵。
   这是本模块最隐蔽的一个 bug，也是「逐 SKU 可配提前天数」这个需求能不能真正成立的关键。
2. **占位符错位一格**：`nullif` 与 `coalesce` 让绑定从 1 个变成 3 个，
   驱动报的是「未设定参数值 N」，看起来像 SQL 语法问题，根因却在绑定。
   现在三个值全部从同一个数组派生，不手数。
3. **`WITH` 子句必须拼在语句最前，且外层要有 `FROM scoped`**：两处顺序都错过一次。
4. **批次数原为 `count(*)`**（数的是 bucket 行），同批次跨库位会被重复计数，
   「有多少个临期批次」变成「有多少个库位有临期货」。改为 `count(distinct l.id)`。

另外 `daysToExpiry` 沿用 7.3.1.1 的坑：纯文本 SQL 里 jOOQ 拿不到列类型，
直接 `r.get(field, Integer.class)` 会静默返回 null，于是「过期批次」会显示成「没有效期」。
测试断言了真实读回的负数（`-5`）。

## 关键 trade-off

- **只读不写**：分配推荐不扣减库存，扣减留给 8 拣货的业务单据。
  避免在没有真实出库单的情况下凭空减少库存。代价是推荐与实际出库之间存在时间差，
  由 8 侧的预占（`stock_bucket.reserved`）在业务上兜住。
- **汇总不受档位筛选影响**：预警清单默认只看「过期 + 临期」两档，
  但汇总统计的是筛选范围内的**全部档位**。否则 `healthyLotCount` 会恒为 0，
  一个恒为 0 的字段等于没有这个字段。
- **零库存批次默认不进清单**（`inStock` 默认 true）：发不出货的批次占着预警位是噪声；
  需要时显式传 `inStock=false` 可看到全部批次。

## 文件清单

| 文件 | 说明 |
| --- | --- |
| `backend/services/warehouse/src/main/resources/db/migration/V60__warehouse_inventory_expiry_policy.sql` | 策略表 + RLS + GRANT + 两个触发器 + 索引 |
| `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryExpiryModels.java` | 传输模型（全部 `Expiry*` / `Fefo*` 前缀防契约重名覆盖） |
| `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryExpiryService.java` | jOOQ 显式 SQL：分配推荐 / 预警清单 / 策略 CRUD |
| `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryExpiryController.java` | 7 个端点，租户来自已认证成员关系 |
| `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryExpiryIntegrationTest.java` | 24 例，Testcontainers PG16，池压到 1 |
| `apps/web/src/components/inventory/inventory-expiry-model.ts` | 前端模型与纯函数 |
| `apps/web/src/components/inventory/inventory-expiry-model.test.ts` | 14 例 |
| `apps/web/src/components/inventory/inventory-expiry.tsx` | 工作区（预警清单 / 分配试算 / 策略） |
| `apps/web/src/app/inventory/expiry/page.tsx` | 页面 `/inventory/expiry` |
| `apps/web/src/lib/api-proxy.ts` | 白名单放行 `inventory/expiry` 的七个形状 |
| `apps/web/src/app/api/warehouse/[...path]/route.test.ts` | 白名单覆盖 |

## 验证

- `./mvnw verify`（全服务）通过
- `pnpm typecheck` / `pnpm lint` / `pnpm test` 全绿：
  基线 **1056** 例（59 文件）→ 本车道 **1070** 例（60 文件），新增 14 例，**无回归**
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` 通过，`/inventory/expiry` 已进路由表
- 契约语义比对：新增 18 个类型、**0 个消失**、新增 7 条路径、**0 条消失**，无静默覆盖

## 后续待办

1. **`inventory_entry` / `inventory_balance` 仍无 `lot_id`**：流水只能到 balance 口径
   （7.3.1.1 已记录）。本模块的分配不受影响（它建在 `stock_bucket` 上），
   但「这批货什么时候进的货」仍然答不出来。要解决需改台账表结构，不属本模块。
2. **8 拣货侧的落地**：本模块出推荐，8 侧负责按推荐建拣货任务并扣减。
   届时 `FefoAllocationPlan` 的库位级取用量可直接作为任务明细。
3. **策略的仓级覆盖**：当前策略是 SKU 级。若出现「同一 SKU 在不同仓用不同策略」的真实需求，
   需要引入 `(tenant_id, sku_id, warehouse_id)` 的覆盖行与优先级解析 —— 现在做属于凭空推测。
4. **`warehouse/inventory/lots`（7.3.1.1）不在 api-proxy 白名单里**：该模块前端调
   `warehouse/inventory/lots`，但代理白名单只放行了 `inventory/lots`（明细页的批次下拉）。
   因此 7.3.1.1 的页面在浏览器里会拿到代理 404。**本车道未改动**（属他人模块端点），
   需在并入后由协调方决定由谁补白名单条目。
5. **品类维度的批量开启**：蓝图说效期是食品化妆品类目的硬需求。
   现在只能逐 SKU 开。若 catalog 交付品类属性，可做「按品类批量开策略」的动作 ——
   但那需要跨服务读取品类，本服务不建跨服务 FK，届时仍用 catalog 的只读接口。