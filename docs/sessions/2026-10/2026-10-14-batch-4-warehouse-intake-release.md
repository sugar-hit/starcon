# Batch 4 · 仓库入库/释放主线（2026-10-14 08:00–09:15）

> 一句话：**本批交付 7 个模块**——其中 3 个是把昨天卡在同一个文件上、滞留了整整一天的
> 车道复活并入，4 个是本批新开发。全量 L2 一次通过（后端 3187 例 + 前端 2445 例 + Next build）。

## 本批为什么值得单独记

不是「又交付了 4 个模块」，而是**流水线的两个结构性缺陷在本批同时暴露**，
其中一个造成了 24 小时的吞吐归零，另一个差点造成静默路由丢失。

| 缺陷                                                         | 后果                                               | 本批处置                              |
| ------------------------------------------------------------ | -------------------------------------------------- | ------------------------------------- |
| `apps/web/src/lib/api-proxy.ts` 不在 merge driver 覆盖范围内 | 每批 3–4 条 warehouse 车道必然撞同一个单行巨型正则 | 手工求并恢复 + 给出治法建议（见文末） |
| 车道从**上一次合并之前**的基线切出                           | 「取 theirs」会静默删掉已并入的路由                | 求并保留 + 探针自检证明删掉会转红     |

---

## 模块 7.2.1.2 预占分配明细（**滞留一天后复活**）

**这个功能做什么**：运营在预占需求列表里点开一条预占单，能看到「这一单的钱分给了哪些库位、
每条分了多少、各自什么状态」，并能对单条分配做承诺、消耗出库，看整单的状态迁移时间线。

**技术落地**：

- 迁移：`V107__warehouse_reservation_allocation.sql`（520 行）— `reservation_allocation` +
  `reservation_entry`，全部 `tenant_id NOT NULL` + FORCE RLS + `GRANT warehouse_app`
- 端点：`ReservationAllocation{Controller,Models,Service}.java`，挂在 `/inventory/reservations/{id}` 下
- 页面：`/inventory/reservation/[reservationId]` — 真实工作区，与预占需求列表联动
- 测试：`ReservationAllocationIntegrationTest` 34 例（含租户隔离三断言）+ 白名单守卫

**关键设计决策**：

- **白名单里字面量子路径必须排在 `[0-9a-f-]+` 通配支之前**。alternation 是**首个命中即返回**，
  不是最长匹配——把 `reservations/options` 放到通配支后面，它会先被当成「id=options 的明细」而放行，
  等于凭空开出一个后端不存在的端点入口。**白名单宁可少放，不可放错。**
- 刻意**不**放行 `reservations/{id}/release` 与 `/entries`：前者属 7.2.1.3（当时未交付），
  后者不是本模块的对外形状（时间线叫 `/timeline`）。

---

## 模块 8.4.2.1 拆解单（**滞留一天后复活**）

**这个功能做什么**：把已组装好的成品**拆回组件**并登记退回的库存——比如把退回的成品衣服拆回面料和扣子。

**技术落地**：

- 迁移：`V108__warehouse_disassembly_order.sql`（407 行）— **零 ALTER 任何既有表**，跨车道 DDL 回归面 = 0
- 端点：`DisassemblyOrder{Controller,Models,Service}.java`，6 个（capabilities / 建单 / 列表 /
  详情 / execute / cancel），**刻意无 PUT/DELETE**：组件明细是建单时按 BOM 反推冻结的快照 + 逐行确定的去向，
  改它等于改账的输入
- 页面：`/warehouse/disassembly-orders`
- 测试：`DisassemblyOrderIntegrationTest` 27 例 + model 21 例 + 白名单守卫 7 例

**关键设计决策**：

- 蓝图加粗句「拆解结果的组件质量状态由接收方决定，**不能默认全部回可售**」落成三层强制：
  ① `destination_quality_status_id NOT NULL`（DB 层）；② 服务层**不提供默认值**，不传就是 400；
  ③ **去向逐行**，不给「整单默认去向」——同一张单里「面料回可售、螺丝回残次」是常态。
  刻意与 8.4.1.1 组装单的「默认回 SELLABLE」相反：**退回的货凭什么能直接卖。**
- 命名空间与组装单完全隔离（`disassembly-*` 不是 `assembly-orders` 的子路径）——
  「组装」与「拆解」必须能分别统计，挂在同一支下会让「哪些单据动过库存」有两个答案。
  白名单里有专门一条守卫钉死这点：`'disassembly-orders'` 字符串里本就含 `'assembly-orders'`，
  改写成子路径时 typecheck / lint / vitest **全绿**，只有这条守卫能挡住。
- **不静默取整**：拆解数量与应回量必须能被库存刻度（4 位小数）精确表示，否则服务层拒绝并指出哪一行——
  向下取整等于凭空吞掉组件，向上取整等于凭空多产组件，两种都恰好能让守恒关系「看起来成立」而物料对不上。

---

## 模块 8.5.2.1 库位利用率（**滞留一天后复活**，刻意无迁移）

**这个功能做什么**：仓储主管能看到每个库位的容量利用率、空置率、出库频次，
并直接拿到「爆满 / 空置 / 低效 / 容量未量 / 高频货在不可拣库位 / SKU 散在多库位」六条可执行建议。

**技术落地**：

- 迁移：**V109 刻意留空**。三样输入全部已在既有事实表里（容量在 V105 `warehouse_location.capacity`、
  占用在 `stock_bucket`、出库频次在 V1 `inventory_transaction`）；建议是**读时派生**的，
  存成一张「建议表」只会让它比它描述的库存更先过期。
  也没有为了「有自己的表」而新建表——`inventory_entry` 的写入所有者是 7.1.2.1，
  代写一条库位级流水会直接违反「每张事实表只有一个写入所有者」。
- 端点：`LocationUtilization{Controller,Models,Service}.java`，`/warehouse/location-utilization`，
  **只复用 `warehouse.facilities.read` 且无任何写端点**（分析是只读的；
  用一个不存在的权限码会让连 OWNER 都 403）
- 页面：`/warehouse/facilities/[id]/utilization` — 9 张统计卡 + 真数据热力图（色阶由后端 heat 驱动）
- 测试：`LocationUtilizationIntegrationTest` 20 例 + model 19 例

**关键设计决策**：

- **出库频次必须摊分**，因为 `inventory_entry` 是 balance 粒度、表上无 `location_id`——
  「这一件从哪个库位拣走」系统里没有任何一张表在记。口径是
  `库位出库量 = Σ_SKU(SKU 本仓窗口出库量 × 该库位占此 SKU 全仓在手量的比例)`，
  可加性保证 `Σ库位出库量 == 仓库真实出库量`（有专门用例断言）。
- **三处分母边界一律 `null`**，不冒充 0：容量未量 / 容量为 0 / 在手为 0。
  容量为 0 时给 0 会被读成「一个都没放」，给 100% 是在替物理事实编数。
- 10 个 `LocationUtilization*` record 开工前逐个 grep 确认全局无重名。
  ⚠️ **springdoc 用简单类名做 `components.schemas` 键，重名会被静默覆盖、单测全绿而契约形状被改。**
  刻意不复用仓库已有的裸名 `Occupancy` / `LocationRow` / `LocationTotals` / `Turnover`。

---

## 模块 7.1.1.3 可用量计算（**本批新开发**，刻意无迁移）

**这个功能做什么**：回答「这个 SKU 现在到底还能卖多少」——一个数字，
而不是让人自己拿在手量减预占再减安全库存。

**技术落地**：

- 迁移：**无**（V111 刻意不用），理由同 8.5.2.1：可售量是**读时派生**的数，
  存表要靠每次库存变动同步，漏一次就产生一个比库存更笃定的错数，而错的恰好是**防超卖用的**。
- 端点：`AvailableQuantity{Controller,Models,Service}.java` —
  `GET /warehouse/inventory/sellable`、`/skus`、`/capabilities`
  （刻意不用 `/inventory/availability`，已被 3.5.2.2 的 BOM 批量可用量查询占用）
- 页面：`/inventory/sellable`
- 测试：集成测试 24 例 + model 33 例 + 白名单守卫 30 例（含 10 条反向断言）

**关键设计决策**：

- 算式：`① bucket 可用 = on_hand − reserved`（生成列，无第三来源）→
  `② 可售基数 = Σ available`，限 `role='SELLABLE' ∧ location.pickable ∧ location.status='ACTIVE' ∧ (lot 为空 ∨ lot.status='ACTIVE')` →
  `③ 可售量 = max(0, ② − ④)` → `④ 安全缓冲 = Σ_SKU inventory_policy.safety_stock`（SKU>WAREHOUSE>DEFAULT）。
- **「不重复扣减」实现为互斥且穷尽的五片**（合格可售+待检+残次+未上架+批次冻结 == Σ available），
  **不是连减五刀**。
- **预占只从 `reserved` 进算式**，不 join `reservation_allocation` 重算——
  那是 7.2.1.2 的溯源输出，不是本模块的输入；join 会重复扣减。

---

## 模块 7.2.1.3 释放与过期（**本批新开发**）

**这个功能做什么**：预占到期或手工放弃时，把占住的货**真正放回可售池**，
并能一次性把所有已过期的预占批量释放。

**技术落地**：

- 迁移：`V112__warehouse_reservation_release_expiry.sql` — `reservation_expiry_run` +
  `reservation_release_record`，**零 ALTER** V102/V107，FORCE RLS + GRANT
- 端点：`ReservationRelease{Controller,Models,Service}.java`，9 个形状挂在 `/warehouse/inventory/reservations` 下
- 页面：`/inventory/reservation-release`
- 测试：`ReservationReleaseIntegrationTest` 41 例 + 前端 18 例 + 白名单 6 例

**关键设计决策**：

- **过期用显式端点 `POST /expire-overdue`，无常驻定时器**。判据 =
  `status='HELD' AND expires_at ≤ 判定时刻`；`evaluatedAt` 可显式传入并在响应里逐字返回。
- 幂等三层：同键同参回放 / 同键异参 409 / 无键时终态再释放按重放返回。
  批量是**一事务全成功或全回滚**，候选行 `for update skip locked`，更新带状态谓词并检查影响行数。
- **与 7.2.1.2 加锁顺序一致（先分配后预占）**，否则死锁表现为随机 500。
- 修掉一个跨模块状态机违规：部分释放路径曾写入非法的 `HELD→HELD` 流水，
  V107 的转移矩阵不允许，已修并加了对应用例。
- **它把 7.2.1.2 的 `capabilities` 声明从 `available=false` 翻成 `available=true`**
  ——7.2.1.2 当时对外如实声明「释放与过期未接入」，本模块落地后必须翻旗，
  并连带修正钉住旧真相的那条测试。

---

## 模块 8.2.2.1 上架任务（**本批新开发**）

**这个功能做什么**：收货后把货从收货区**扫到指定库位**，逐行登记实扫数量、缺件、破损，最后整单完成。

**技术落地**：

- 迁移：`V109__warehouse_putaway_task.sql`（49183 字节）— `putaway_task` / `putaway_task_line` /
  `putaway_task_scan`，**只建表不 ALTER 既有表**
- 端点：`PutawayTask{Controller,Models,Service}.java`，`/warehouse/putaway-tasks` × 9，无 PUT/DELETE
- 页面：`/warehouse/putaway-tasks` — 路径计划段、实扫登记、作业时间线；加载/空/错误三态
- 测试：`PutawayTaskIntegrationTest` 33 例 + model 32 例 + 白名单守卫 8 例

**关键设计决策**：

- **执行中的任务账上净额恒为 0**——一次扫码写的就是**一对反向分录**，成对两条必然抵消。
  这一点是被测试纠正的（实现者原以为净额非 0）。所以「账上没变化」**不代表任务做完了**：
  P1 抓的是「不成对的分录」，完成与否看 P2/P3。
- 服务层文案、capabilities 与前端提示已全部按正确口径改写，避免运营误判。

---

## 模块 8.3.2.1 拣货任务（**本批新开发**）

**这个功能做什么**：按订单生成拣货单，给拣货员规划一条**少走路的拣货路径**，登记实拣、上报缺货异常、完成或重开。

**技术落地**：

- 迁移：`V110__warehouse_pick_task.sql`（39472 字节）— 六张表，**零 ALTER 他表**
- 端点：`PickTask{Controller,Models,Service}.java`，新起顶层 `/warehouse/pick-tasks`，10 个形状
- 页面：`/warehouse/pick-tasks` — 路径计划段、实拣登记、异常上报、作业时间线
- 测试：`PickTaskIntegrationTest` 33 例 + model 15 例 + 白名单守卫 8 例

**关键设计决策**：

- **分配路径时不能用 `available`（= on_hand − reserved）**——那会让「已经为本单预占的货」
  变得不可拣。必须用 on_hand 口径。
- 白名单分支插在 `disassembly-orders` 之后，`\/capabilities` 排在 `\/[0-9a-f-]+` 之前，
  并有反向断言（`waves`、多一层后缀、拼错仍 404 且不发请求）。
- 它是 8.5.2.1 建议体系里「高频货在不可拣库位」「SKU 散在多库位」两条建议的落地归属。

---

## 三路 `api-proxy.ts` 求并（本批最关键的技术动作）

`merge-ready` 先后两次尝试并入，**3 条车道全部只冲突这一个文件**，
且 8.2.2.1 与 8.3.2.1 **插在同一个位置**（`disassembly-orders` 之后），必须排成两条而不是二选一。

**求并结果**：warehouse 顶层分支 **25 → 27**，**0 条删除**，
`disassembly-orders → putaway-tasks → pick-tasks`。

**最要紧的一条发现**：三条车道都是**从 7.1.1.3 合入之前的基线切出来的**，
所以任何「取 theirs」的解法都会**静默删掉** `sellable(?:\/(?:capabilities|skus))?` 这条路由——
而症状要等到预占可售量页面白屏才暴露。求并保住了它。

**验证**（不是静态断言，是真跑代理断言 HTTP 状态码）：

- 65 条正向 + 32 条反向 + 167 条超集交叉核对，**0 失败**；
  形状清单取自各车道自己的测试文件，不是猜的。
- **探针自检**：逐个删掉 `disassembly-orders` / `customs-declarations` / `putaway-tasks` /
  `pick-tasks` / `sellable`，**5 条全部转红**，文件逐字节还原。
  ⚠️ 第一次尝试误伤了注释里的同名文本，什么也没证明——**限定到正则字面量内重做才有效。**

**契约复核**：`warehouse.openapi.json` **730 → 789 个 schema，既有形状改动 0、删除 0**，
新增 59；`contracts/warehouse.ts` 无重复标识符。8.3.2.1 的 `operationId` 重编号是
新增 controller 导致 springdoc 序号顺延，**无 schema 丢失**。

---

## 顺带修掉的既有缺陷

**7.1.1.3 遗留的 TS18048 卡死了整批 L2。**
`apps/web/src/lib/inventory-sellable-api-proxy.test.ts:70,73` 在 `noUncheckedIndexedAccess` 下报错。
真正的 `undefined` 来源不是 `m[1]`（第 65 行的类型谓词其实生效了），
而是第 69 行 `p.split('${')[0]` 的下标取值把窄化又推翻了。
修法是在同一层用类型谓词重新窄化，**没有**引入 `any` / `as` / `@ts-expect-error`，也没改 tsconfig。

关键是这个文件的设计注释：「**挖到 0 条正是这套守卫最危险的假绿**」——
`expect(...).toEqual([三个形状])` 要求**恰好挖到 3 条**。
修法保证新旧两条链抽出的数组**逐字节相同**，断言一字未改，挖到 0 条仍照红。

## 另一个被推翻的「事故结论」

我在跑 merge 前排查「`merge-ready` 的 `merge --abort` 会连带回滚上一条车道」的隐患——
这是记忆里记着的事。**最小 repro 实测推翻了它**：

```
干净车道 merge 成功 → HEAD=2ec5464 → 冲突车道 merge 冲突 → git merge --abort
⇒ HEAD 仍是 2ec5464，干净车道的成果完好
```

`ORIG_HEAD` 是**当前这次失败的 merge 尝试**写的，正是前序合并后的 HEAD。
当年那次事故的 reflog 文案是 `reset: moving to HEAD~1`，而 `merge --abort` 的文案是
`reset: moving to ORIG_HEAD`——元凶是另一次人工 `reset`，我把两个事故并成了一条。
仓库自带的 `lane-merge-abort.itest.mjs` 两例当时全绿也不能当证据：
它的 fixture 是冲突车道在前，恰好绕开了危险顺序。

**结论：代码没问题，不要去"修"它。** 改成 `--hard` 反而会引入新问题。

## L2 结果（绿）

| 闸                                                           | 结果                                                                                                                        |
| ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------- |
| `cd backend && ./mvnw verify`                                | 162 个测试类 / **3187 例**，0 失败 0 错误（catalog 1421 / warehouse 1281 / identity 236 / trade 155 / channel 76 / crm 18） |
| `pnpm typecheck`                                             | 绿                                                                                                                          |
| `pnpm lint`                                                  | exit 0（仅 `react-hooks/exhaustive-deps` warning）                                                                          |
| `pnpm test`                                                  | 130 文件 / **2445 例**，0 失败                                                                                              |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | exit 0                                                                                                                      |
| `node tools/module-progress/sync.mjs --check`                | 台账与蓝图一致                                                                                                              |

## 后续待办（交给下一轮）

1. **治本建议**：让 `lane.mjs` **拒绝**从「merge-base 早于集成头上一次 merge commit」的地方切车道——
   **陈旧分叉这个状态是可检测的**，把一次静默丢路由变成构建期报错。这比给 `api-proxy.ts`
   加 merge driver 更值得做（driver 已经有「行级兜底」模式会掩盖损坏，还得再配一遍探针自检）。
2. **7.2.1.1 的 `RELEASE` 能力位仍报 `available=false`**。7.2.1.3 无权改该文件（属 7.2.1.1），
   导致预占列表页仍显示「释放与过期未交付」——**用户可见的错误提示**。需由该车道或调度者翻旗。
3. **identity 未注册 `warehouse.picking` 权限码**。本批两条车道复用了 `warehouse.inventory.*`，
   若要给拣货单独授权需在 identity 单独注册（属 identity 服务迁移，本批未预留版本号）。
4. **`api-proxy.ts` 里 `reservations(...)` 之后那个既有的空 alternation `||`** 仍在
   （上一批车道留下的）。空分支匹配空串、不报错也不放行任何非空路径，属无害冗余，可择机清理。
5. **7.2.1.3 把 `reservations/{id}` 之后的子段变成了可选**，于是裸 `reservations/{id}` 从 404 变成放行。
   这是**超集**（没丢路由），但 7.2.1.3 自己的测试对该形状**两个方向都没断言**。已留作观察项，未擅改。
