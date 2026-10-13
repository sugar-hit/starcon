# 7.2.1.1 预占需求列表（阶段 D3，L3 锚点 7.2.1）

主责服务：**warehouse**。车道 `feat/7-2-1-1`，迁移版本 **V102**（预分配）。

## 决策摘要

交付蓝图 7.2.1.1 的全部职责：展示所有预占需求（订单履约 / 加工领料 / 其他占用），
含需求来源、数量、状态（held/committed/consumed/released/expired）、剩余量、超时时长，
输出列表 + 状态汇总。筛选覆盖蓝图点名的状态、来源类型、时间三项。

## 关键 trade-off

### 一、预占表落 warehouse，**偏离**蓝图关系句「预占需求由 trade 持久化协调」

这是本车道最需要评审的一条，理由是对集成头全库实查的硬事实，不是偏好：

1. `inventory_reservation` 表**在任何服务里都不存在**。全库 grep 只命中
   `V1__warehouse_inventory_core.sql` 的**文件头注释** —— 那段注释写明 warehouse 是
   `inventory_transaction / inventory_reservation / inventory_lot / inventory_serial` 的
   **唯一**所有者，并逐字注明「reservation / lot / serial 分别属 7.2.1.x 与 7.1.1.2，
   本期**不建空表占位**」。也就是说 V1 早已把位置预留给 warehouse，7.2.1.x 一直没来建。
   这是**补上预留的位置**，不是抢 trade 的活。
2. trade 服务（V1 baseline + V92~V101）**没有任何预占相关表**。所以「去 trade 查」在物理上
   不存在 —— 不是不该跨库读，是那张表根本没有。
3. AGENTS.md 模块边界纪律禁止跨服务读写对方表。即使 trade 将来建了同名表，本库也不该 join 它。

蓝图那句描述的是**履约流程中的协调分工**（trade 决定何时发起取消 / 回传实发，
warehouse 执行本地事务与分配），不是「这张表归 trade 建」。蓝图 07 卷开头「后端归属」
一行本身就写着 warehouse 拥有 `inventory_reservation`，两句本身有张力；
本迁移取「后端归属」与 V1 文件头一致的那一句。

**后续 7.2.1.2 分配明细与 7.2.1.3 释放与过期同样落在 warehouse**，三张表同库同 RLS 策略，
两条兄弟车道可直接建外键，不需要跨服务协调。

### 二、只建本模块那张表，不给兄弟模块建空表占位

7.2.1.2 的 `reservation_allocation` 与 7.2.1.3 的 `reservation_entry` **一律不建**（理由同 V1
「不建空表占位」：建一张永远只有 HELD 行的分配表等于宣称「分配明细已交付」而实际是空的）。
已分配量先记成 `allocated_quantity` 标量列，待 7.2.1.2 在同一张 reservation 上建子表后回写，
**本模块不需要迁移**。

### 三、剩余量口径：`requested − consumed`，**不含**已分配未出库

蓝图点了三个量：数量、剩余量、超时时长，三个都必须可回答。`remaining` 做成生成列，
**没有第三个来源**。为什么不含 allocated：allocated 是「货已圈到某个 bucket」，
货还在仓里没动；consumed 才是「货真的出库了」。蓝图要回答的是「这笔预占还欠多少货没出」——
已分配但未出库的那部分**仍然欠**，算进 consumed 会让列表在 7.2.1.2 交付后显示成「已结清」，
而实际货还在仓里占着可用量。口径宁可保守也不美化。

### 四、状态机钉在 DB 层，不只靠 Java 校验

7.2.1.2 / 7.2.1.3 两条兄弟车道会各自写这张表，它们各自的 service 层校验加在一起
仍然能被一条裸 SQL 绕开。而绕开的后果是「已出库的货被复活成预占」——不可逆的超卖事故。
所以「终态不可复活」与「状态与时间戳自洽」都写成触发器，集成测试里有绕过服务层的裸 SQL 用例。

### 五、COMMITTED 不参与自动过期

蓝图 7.2.1.3 红线：「承诺分配（committed）后不再自动 TTL 释放，否则已承诺发货的货会被误释放
导致超卖」。落到本模块就是：超时筛选与超时计数**只认 HELD**，由过期候选的部分索引
`WHERE status='HELD' AND expires_at IS NOT NULL` 兑现。本模块**刻意不实现自动过期动作** ——
高风险不可逆动作必须由 7.2.1.3 按显式规则交付，不能夹在列表模块里顺带实现。

### 六、前端四条「写成 0 就完了」的口径

- 超时长 `null` 显示「—」而不是 0：null 是「没超时 / 已终态 / 无过期时间」，0 是「超时 0 秒」。
- 已分配量恒 0 时标注「分配明细未交付」，而不是冒充「还没分配到任何货位」。
- 合计不随分页与筛选变化，旁边写明，否则用户会以为筛选没生效。
- 剩余量口径常驻说明，否则有人会把「已分配 12 / 剩余 20」当成算错。

## 文件清单

- 迁移 `backend/services/warehouse/src/main/resources/db/migration/V102__warehouse_inventory_reservation.sql`
- `ReservationModels.java` / `ReservationService.java` / `ReservationController.java`
- `ReservationIntegrationTest.java`（35 例）
- 契约 `packages/api-contracts/src/contracts/warehouse.ts` + `warehouse.openapi.json`（+7 个 `Reservation*` DTO，纯新增）
- 前端 `apps/web/src/components/inventory/inventory-reservation.tsx` + `inventory-reservation-model.ts` + 同名 `.test.ts`
- 页面 `apps/web/src/app/inventory/reservation/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（**追加** `reservations(?:\/(?:options|capabilities))?` 一支，位置在 `aging` 之后、`serials` 组外）
- `packages/ui/src/lib/task-pages.ts`（**追加** `/inventory/reservation` 导航项）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（放行 + 反向拒绝用例；并把
  `warehouse/inventory/reservations` 从既有拒绝清单移出 —— 那份清单是 7.2.1 未交付时的状态）
- 台账 `docs/product/capability-blueprint/module-progress.md` 的 7.2.1.1 一行

## 踩过的坑（留给后来车道）

1. **白名单正则的插入位置有层级**：`api-proxy.ts` 的 warehouse 分支锚在
   `^(inventory\/(?:…))`，往里加分支必须放在 `inventory/(?:` 那一层**内部**。
   我第一次插到了 `serials` 的嵌套组里，正则仍合法但永不匹配 —— typecheck 与 lint 全绿，
   只有真跑代理测试才暴露。验证方式：直接从文件里 eval 出正则字面量再 `test()`。
2. **计数查询必须与行查询共用 FROM/JOIN**：关键字筛选要匹配 `sk.code / sk.name`，
   只写 `from inventory_reservation r` 的 count 会报 bad SQL grammar，且只在用户真的敲了
   关键字时才炸。抽成 `FROM_CLAUSE` 常量后两条查询共用。
3. **RLS 段必须排在建表之后**：`ALTER TABLE … ENABLE ROW LEVEL SECURITY` 要求表已存在，
   写在前面 Flyway 直接以 `relation … does not exist` 失败。
4. **超时谓词必须带表别名**：`legal_entity_ref` 也有 `status` 列，join 之后裸写 `status=`
   会报 ambiguous；而汇总查询没有 join，不带别名又是对的 —— 只按汇总调试会以为谓词没问题。
5. `Map<String, Long>` 被 codegen 序列化成 `{}`，TS 侧无索引签名，`noUncheckedIndexedAccess`
   下直接索引报 TS7053，需要显式收窄的 `countOf()`。

## 验证

- `cd backend && ./mvnw -q compile` 绿
- `./mvnw -pl services/warehouse -am test` —— **959 例全绿**（含本模块 35 例）
- `pnpm typecheck` 绿
- 改动的前端测试文件 —— 29 例绿（模型 12 + 代理路由 17）
- `node tools/module-progress/sync.mjs --check` 通过

## 后续待办

- **7.2.1.2 预占分配明细**：建 `reservation_allocation`（含 `lot_id` / `serial_id` 可空、
  复合外键指向 `inventory_reservation` 与 `stock_bucket`），并回写本表的 `allocated_quantity`。
  注意 `stock_bucket` 上有 `guard_bucket_reservable` 触发器：非 SELLABLE 质量状态的 bucket
  不可预占，分配写入时会被 DB 拒。
- **7.2.1.3 释放与过期**：建 `reservation_entry`（append-only），提供手动释放入口与批量过期清理，
  释放时同步恢复 `stock_bucket.reserved`。**承诺分配后不再自动 TTL 释放**这条红线要落到服务端规则。
- 两条车道交付后把本模块 `/capabilities` 的 `ALLOCATION_DETAIL` / `RELEASE` 翻成 `available=true`，
  并把 `ReservationPage.detailUnavailable` 从 `true` 翻成 `false`（前端据此禁用/开放入口）。
- 预占单号目前只在本服务内唯一。跨服务去重应使用 `source_system + reference_id`，
  若将来 trade 侧重复推送同一笔需求，需要在 7.2.1.3 或上游补幂等键。
