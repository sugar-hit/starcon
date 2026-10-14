# 7.2.1.2 预占分配明细

- 日期：2026-10-13
- 阶段：D3 · L3 锚点 7.2.1 · 路由 `/inventory/reservation`
- 车道：`feat/7-2-1-2`（worktree `.worktrees/7.2.1.2`）

## 决策摘要

蓝图职责：「展示一个预占需求实际分配到了哪些 bucket（哪个仓、哪个货位、哪个批次），以及每个分配的
held→终态迁移记录。」输出「分配明细行 + 状态迁移时间线」。

**① 本迁移建两张表（V102 明确把两者都留给本车道）。**
开工自检确认：`reservation_allocation` 与 `reservation_entry` 在**全库任何服务里都不存在**，
V102 §一 明写「7.2.1.2 与 7.2.1.3 都不建空表占位」。只建主表不建流水表，
时间线就永远是空的 —— 而「空时间线」会被读成「这笔预占没发生过任何迁移」，
与「功能尚未交付」混淆，正是 V1/V102 反复拒绝的那种假交付。7.2.1.3 会**写**同一张流水表
（释放也是一次迁移），但同服务多写入方共享事实表是正常分工，跨服务才是 AGENTS.md 禁止的那件事。

**② 分配行引用「仓 + 货位 + 批次」三维，不引用 `stock_bucket.id`。**
蓝图举例就是这三个维度。引 stock_bucket 看着更「准」，刻意不这么做：
(a) stock_bucket 要外键指向 `inventory_balance`，而「预占」常常**先于**该 bucket 的实物入库发生
（先圈住这批货，到货才建 bucket），引它会让「先预占后建账」写不进去；
(b) 预占分配长于 bucket 存续时间，bucket 会被盘点/效期/合桶重建，
而「当初圈在了哪个货位」是审计事实，不该因为下游事实表换了一行 id 就查不到。
兜底是维度自洽：货位用**复合外键** `(tenant_id,warehouse_id,location_id)` →
`warehouse_location(tenant_id,warehouse_id,id)`（V2 专门为此建了那个唯一键），
所以「仓 A + 仓 B 的货位」在 DB 层就被拒；批次与预占的 SKU 同商品由触发器比对。

**③ 父表 `allocated_quantity` 由 DB 触发器回写，不在 Java 里回写。**
V102 §一 已把「allocated_quantity 由 7.2.1.2 回写」写进契约，而 7.2.1.3 的释放路径也会改分配状态 ——
两条写入路径各回写一次，迟早漏一次，父表就与明细**永久对不上账**。触发器是唯一能让它对不上的方式
就是绕过本服务裸写 SQL，而裸写也照样被重算。父行已终结时跳过：终态行是历史快照，
它的 allocated_quantity 就该停在终结那刻的值。

**④ 分配状态变，预占状态跟着变（不是洁癖，是防线）。**
分配全部承诺 → 预占进 COMMITTED；分配全部消耗且覆盖需求量 → 预占进 CONSUMED。
不同步的话预占一直停在 HELD，而蓝图 7.2.1.3 的超时清理会把**已承诺发货的货误释放** ——
蓝图自己点名的超卖场景。部分承诺时预占**仍留 HELD**：它确实还有没承诺的部分，继续参与超时是对的。

**⑤ 释放与过期不是本模块的按钮。**
属 7.2.1.3（手动释放入口 + 批量过期清理 + 可用量恢复）。本模块既不提供入口，也不在能力声明里
假装已接入（`ALLOCATION_RELEASE` 报 `available=false` + `ownerModule=7.2.1.3`）。
「给一个点下去什么也不发生的按钮」比不给更糟。

**⑥ 路径用 `/timeline` 而不是 `/entries`。**
对外说的是「时间线」这个概念（蓝图输出原话），`reservation_entry` 是表的内部名字。
顺带让 `reservations/{id}/entries` 继续留在反向拒绝清单里，不与任何模块抢形状。

## 关键 trade-off

| 取舍                  | 选择                                           | 代价 / 理由                                                                                     |
| --------------------- | ---------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| 分配定位到哪一级      | 仓 + 货位 + 批次三维（弱引用 + 维度自洽）      | 放弃「引用 stock_bucket」带来的存量兜底；换来「先预占后建账」可写、审计痕迹不被 bucket 重建冲掉 |
| 父表 allocated 谁维护 | DB 触发器（非终态分配之和）                    | Java 侧不再有回写代码；7.2.1.3 释放后自动对账，无需记得改                                       |
| 预占状态联动          | 服务层在 commit / consume 里推进               | 多一次父表 UPDATE；换来超时清理不会误释放已承诺的货                                             |
| 已释放量的口径        | 本模块**不碰**父表 `released_quantity`         | 归 7.2.1.3。碰了就会在对方写完释放量后被下一次分配变更覆盖回去                                  |
| 下拉候选值            | 筛选候选只含本预占出现过的；登记目标给全量在用 | 两组候选值含义不同。登记目标必须全量 —— 新建预占零分配，拿候选集填表是死结                      |
| 白名单改动面          | 只改 `reservations(...)` 一支，不重排整条正则  | 字面量子路径排在 `[0-9a-f-]+` 之前（正则 alternation 首个命中即返回，不是最长匹配）             |

## 踩到并修掉的三个真问题

1. **容量守卫漏挂在 INSERT 上。** 第一版把跨行守恒只放在 `BEFORE UPDATE` 触发器，
   于是超卖在**插入**时被漏过去：插入成功、随后的父表回写撞上
   `inventory_reservation` 的 `allocated_quantity<=requested_quantity` 检查而失败 ——
   结果仍是拒绝（父表 CHECK 兜住了），但错误文案只剩 `violates check constraint`，
   完全指不出是**哪一笔分配**顶破了总量。已拆成 `guard_reservation_allocation_capacity`
   （`BEFORE INSERT OR UPDATE`）+ `guard_reservation_allocation_update`（只管终态/改指/时间戳），
   触发器按名字字母序 capacity 先跑。
2. **`timeline()` 少了一次存在性校验。** 拿不属于自己的预占 id 查时间线，
   返回的是**空时间线**而不是 404 —— 那是拿「查不到」冒充「不存在」。
   已在读分录前先确认预占在当前租户下可见。
3. **测试断言的异常类型错了。** jOOQ 在 Spring 栈里把 PG 异常翻成 Spring 的
   `DataAccessException` 子类（`DataIntegrityViolationException` / `BadSqlGrammarException`），
   不是 `org.jooq.exception.DataAccessException`。断言要认父类。
   附带一条已知限制：`reservation_entry` 的 append-only 触发器用 SQLSTATE `42501`，
   Spring 会把未知 SQLSTATE 一律翻成 `BadSqlGrammarException` —— 语义正确但类型名难读，
   消息（「append-only 审计凭据，不允许 UPDATE」）才是可靠信号。改这个映射属于改
   `ApiErrorHandler`（全仓共享），不在本车道范围。

## 跨车道改动（均为 7.2.1.1 原注释显式约定）

7.2.1.1 在 `ReservationService#list` 与 `capabilities()` 里写了
「`detailUnavailable` 从 true 翻成 false：7.2.1.2 分配明细交付后**由那条车道来翻**」，
本车道按该约定执行，共动 4 个文件（均已在本车道提交内，非另开 PR）：

- `ReservationService.java`：`detailUnavailable` → false；`ALLOCATION_DETAIL` → `available=true`
- `ReservationIntegrationTest.java`：两处断言从「未交付」翻成「已交付」
- `inventory-reservation-model.ts`：`allocatedText(0)` 从 `'0（分配明细未交付）'` 改成 `'0'` ——
  该值现在是回写的真值，0 就是「确实还没圈到任何 bucket」，恒挂「未交付」后缀反而误导
- `inventory-reservation-model.test.ts`：对应断言同步

**没有**碰 `reservation_entry` 的 RELEASED / EXPIRED 写入（归 7.2.1.3），
**没有**放行 `reservations/{id}/release`（7.2.1.3 未交付）。

## 文件清单

**迁移**

- `backend/services/warehouse/src/main/resources/db/migration/V107__warehouse_reservation_allocation.sql`
  （新建 `reservation_allocation` + `reservation_entry`；4 个守卫触发器 + 1 个回写触发器；
  4 条索引；tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT + starcon_app REVOKE）

**后端**

- `.../ReservationAllocationModels.java`（16 个类型，全部 `ReservationAllocation*` 前缀）
- `.../ReservationAllocationService.java`（jOOQ 显式 SQL；读 4 端点 / 写 3 动作）
- `.../ReservationAllocationController.java`（7 个形状挂在
  `/warehouse/inventory/reservations/{reservationId}` 之下）
- `.../ReservationAllocationIntegrationTest.java`（34 例，PG16 Testcontainers，池容量 1）

**前端**

- `apps/web/src/components/inventory/inventory-reservation-allocation-model.ts` + `.test.ts`（14 例）
- `apps/web/src/components/inventory/inventory-reservation-allocation.tsx`（真实工作区）
- `apps/web/src/app/inventory/reservation/[reservationId]/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（只加 `reservations(...)` 一支 + 排布说明注释）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（新增放行用例；
  把 `${id}/allocations` 从 404 清单移出，`release` / `entries` 保持 404）

**契约**（codegen 产物，未手改）

- `packages/api-contracts/src/contracts/warehouse.ts`、`.../src/warehouse.openapi.json`

## 后续待办

- **7.2.1.3 释放与过期**：写 `reservation_allocation` 的 RELEASED / EXPIRED 与对应流水分录；
  `ALLOCATION_RELEASE` 能力位从 false 翻 true；白名单加 `reservations/{id}/release`。
  触发器已经为它留好了路（容量判据遇这两个终态跳过、回写触发器把 RELEASED/EXPIRED 排除在占额外）。
  **注意**：它写的流水分录必须满足 `to_status` 等于该分配**当前**状态这一守卫。
- **批次过期与成本分摊**：明细行已带 `lotExpiresOn` 与 batch/location 快照，
  但「按批次 FEFO 扣减」与「成本精确分摊」属 7.3.1.2 / 财务卷，本模块只提供追溯数据。
- **预占与作业单联动**：出库/领料驱动的消耗目前是本模块的动作，
  若 7.3.x 出库单要求自动消耗，需要在那里调 `consume` 而不是另写一条写路径。
- **发现但未修**：`api-proxy.ts` 白名单里 `reservations(...)` 之后有一个**既有的** `||`
  （上一批车道插入时留下的空 alternation），使整条正则多接受一个空匹配分支。
  不在本车道改动范围（会动到别人的分支），报给调度器裁决。
