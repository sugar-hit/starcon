# 7.2.1.3 释放与过期

> 车道 `feat/7-2-1-3` · 阶段 D3 · L3 锚点 7.2.1（`/inventory/reservation`）

## 决策摘要

预占的释放与过期落在 warehouse（与 7.2.1.1 / 7.2.1.2 同库同 RLS），**零 ALTER** 既有表：
V102 已经预留 `inventory_reservation.released_quantity`（文件头逐字注明「归 7.2.1.3」），
V107 已经预留 `reservation_allocation.released_quantity` 与 RELEASED / EXPIRED 两个终态、
`reservation_entry` 的 RELEASED / EXPIRED 分录、以及两条点名「7.2.1.3 会用」的部分索引。
本迁移只补两张本模块自有的表：`reservation_expiry_run`（批量清理运行凭据）与
`reservation_release_record`（单次释放凭据），两者 append-only。

蓝图原文：「职责：处理预占的释放（订单取消、支付超时、拆单减少）与过期回收，提供手动释放入口与
批量过期清理，释放时同步恢复可用量。输入：释放请求（业务事件或人工）；过期时间规则；批量清理任务。
输出：预占终态记录 + 余额恢复分录。关系：上游 = 09 订单取消事件、7.2.1.1；下游 = 7.1.1.3 可用量。
**承诺分配（committed）后不再自动 TTL 释放**。」

## 关键 trade-off

1. **恢复量与释放量分成两个字段**（`restoredQuantity` / `releasedDemandQuantity`）。
   需求 12、只圈到 10 的货整笔释放时是 10 与 12。合并会让可用量凭空多 2，而本卷硬约束是
   「可用量只准有一个来源」。预占级分录记需求侧放弃的量、分配级分录记库存侧还回的量，
   两者相等只在这笔需求被全额圈到 bucket 时成立。

2. **部分释放不写 `reservation_entry`**。部分释放时分配仍是 HELD —— 它**没有**发生状态迁移，
   而 V107 的分录守卫把 HELD → HELD 判成非法迁移（第一版实现踩了这个坑，由 DB 挡下）。
   恢复量改由 `reservation_release_record` 承担：同样是 append-only、同样带量与发生时刻，
   只是不冒充状态迁移。「时间线是状态迁移的凭据，把没发生的迁移写进去它就成了编的」。

3. **候选谓词只含 HELD，且名下有 COMMITTED 分配就整笔跳过**（不只跳过那条分配）。
   跳一半会造出「预占已进终态、那条 COMMITTED 的货永远占着可用量且再也放不掉」的死状态：
   预占进终态后不能再挂新分配。被跳过的条数在汇总里**单独报出来**（`mixedCommitmentSkippedCount`），
   因为它们在列表上与普通候选长得一模一样，不报就没人知道「这批货为什么一直占着」。

4. **加锁顺序与 7.2.1.2 一致：先分配、后预占**。第一版写成「先预占后分配」，
   会与 `ReservationAllocationService`（commit/consume 先锁分配再锁预占）构成死锁环，
   对调用方表现为「随机 500」。所以本模块的预占**先无锁读一遍**做快速判定，行锁在分配锁之后补上，
   并在拿到锁后重判一次终态。

5. **过期规则是消费而不是定义**。判据 = `status='HELD' AND expires_at 非空 AND expires_at <= 判定时刻`，
   已被 V102 的部分索引谓词与 V112 的 CHECK 一起兜住。**刻意不建默认 TTL 配置表**：
   库存策略的配置归 7.2.2 库存策略，本模块是执行者不是配置者，两个地方各算一次正是本卷反复拒绝的事。
   「过期时间规则」这一输入的第二半（补写时刻）由 `POST /{id}/expiry` 承担 —— 7.2.1.1 的入参注释
   逐字写着「不传则该预占不参与超时筛选（可稍后由 7.2.1.3 补）」。

6. **不写 `inventory_entry` 账本、不碰 `inventory_balance` / `stock_bucket` 的 `reserved`**。
   7.2.1.2 登记分配时同样不写（那才是 reserved 变大的时刻）；本模块单方面写账本会与余额侧对不上。
   `/release-capabilities` 里「可用量恢复口径」如实报 `available=false` + `ownerModule=7.1.1.3`。

7. **过期无常驻定时器**：显式端点 `POST /expire-overdue` + 服务层幂等，页面只给「干跑 / 立即清理」。
   一批一个事务（全成功或全回滚），候选行 `for update skip locked`，更新一律带状态谓词并检查影响行数。
   干跑只写一条运行凭据（CHECK 钉死 processed_count 与恢复量恒为 0）。

8. **运行凭据用 DEFERRABLE INITIALLY DEFERRED 外键**挂到逐条释放上：运行凭据在事务末尾才写，
   非延迟外键会在循环中途报「引用的行还不存在」。

## 文件清单

**迁移**
- `backend/services/warehouse/src/main/resources/db/migration/V112__warehouse_reservation_release_expiry.sql`

**后端**（record 简单类名全部 `ReservationRelease*` 前缀，避开静默覆盖高危裸名）
- `.../ReservationReleaseModels.java`（17 个契约类型）
- `.../ReservationReleaseService.java`（jOOQ 显式 SQL；读 6 端点 / 写 3 动作）
- `.../ReservationReleaseController.java`（9 个形状挂在 `/warehouse/inventory/reservations` 之下）
- `.../ReservationReleaseIntegrationTest.java`（41 例，PG16 Testcontainers，池容量 1）
- `.../ReservationAllocationService.java` —— **唯一被点名允许改的既有文件**：
  `ALLOCATION_RELEASE` 能力位由 `available=false` 翻成 `true` 并写明入口

**前端**
- `apps/web/src/components/inventory/inventory-reservation-release-model.ts` + `.test.ts`（18 例）
- `apps/web/src/components/inventory/inventory-reservation-release.tsx`（真实工作区）
- `apps/web/src/app/inventory/reservation-release/page.tsx`
- `apps/web/src/app/api/warehouse/reservation-release-allowlist.test.ts`（6 例双向断言）
- `apps/web/src/lib/api-proxy.ts` —— **只在 `reservations(...)` 一支就地扩展**：
  新增 7 条字面量子路径（release-candidates / expiry-runs / expiry-runs/{id} / release-records /
  release-options / release-capabilities / expire-overdue）**全部排在 `[0-9a-f-]+` 通配支之前**，
  位置在既有 `options` / `capabilities` 之后；`release` / `expiry` 两个子动作挂在最内层，
  与 7.2.1.2 的 `allocations` / `timeline` 同级；既有分支相对顺序一条没动
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts` —— 共享白名单测试里两处
  「`{id}/release` 仍须 404」的旧断言改为「再多一层后缀仍 404」（旧断言编码的是 7.2.1.3 未交付时的真相）

**契约**（codegen 产物，未手改）
- `packages/api-contracts/src/contracts/warehouse.ts`、`.../src/warehouse.openapi.json`

## 验证

- `cd backend && ./mvnw -q compile` 绿
- `./mvnw -pl services/warehouse -am test` 全绿
- `pnpm typecheck` 绿
- `pnpm vitest run apps/web/src/app/api/warehouse apps/web/src/components/inventory` 绿

## 后续待办

- **7.1.1.3 可用量计算**（并行车道）：它把预占算进可用量口径后，本模块的
  `restoredQuantity` 就是它重算 `available` 的输入之一。若它那边改成读
  `inventory_balance.reserved`，需要回头评估本模块是否要补写余额侧（当前**刻意不写**，
  理由：7.2.1.2 登记分配时也不写，单方面写会与余额侧对不上）。
- **7.2.1.1 的能力声明仍报 `RELEASE` available=false**：`ReservationService.capabilities()`
  属于 7.2.1.1 的文件，本车道不在授权范围内改动，故**未翻旗**；它的
  `ReservationIntegrationTest` 也仍在断言 false。集成后应由那条车道（或调度者裁决）
  一并翻成 true，否则预占列表页会继续显示「释放与过期未交付」。
- **调度器待办**：把 7.2.1.1 的 `RELEASE` 能力位与对应测试断言翻成 `available=true`。
- **发现但未修**：`api-proxy.ts` 白名单里 `reservations(...)` 之后有一个**既有的** `||`
  （上一批车道插入时留下的空 alternation），使整条正则多接受一个空匹配分支。
  不在本车道改动范围（会动到别人的分支），与 7.2.1.2 的会话日志记的是同一条，请调度器裁决。
- **09 订单取消事件**（上游 trade）：`ORDER_CANCELLED` / `PAYMENT_TIMEOUT` 两个触发来源
  已建模并可用，但真正的上游回传尚未接线；接线时**必须带 `idempotencyKey`**。