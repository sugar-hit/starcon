# 2026-10-21 11.4.2.1 工单管理：跨模块外键导致的跨车道破坏（修复车道）

## 决策摘要

修 11.4.2.1「工单管理」引入的一次**跨车道破坏**：本模块的迁移给**别的模块的表**加了
外键，导致**更早批次已交付**的 11.4.1.1 集成测试全红。

- 根因：**三条跨模块外键**
  - `V270:375` `aftersales_service_ticket.escalated_case_id → aftersales_case(id)`
    （11.4.2.1 → 11.1.1.1）
  - `V270:381` `aftersales_service_ticket.escalated_dispute_id → platform_dispute(id)`
    （11.4.2.1 → 11.4.1.1）
  - `V269:136` `platform_dispute_appeal.dispute_id → platform_dispute(id) ON DELETE CASCADE`
    （11.4.1.2 → 11.4.1.1）
- 传导机制：PostgreSQL 规定 —— 只要**任何**子表带外键指向某张表，`TRUNCATE` 那张表就被拒绝
  （要连带 CASCADE 或把子表列进同一批）。而 11.4.1.1 / 11.4.1.2 的四个测试类都在
  `@BeforeEach` 里用 `truncate ... platform_dispute` 做测试隔离 ⇒ 11.4.2.1 交付后，
  `PlatformDisputeIntegrationTest`（27 例）与 `PlatformDisputeTenancyIntegrationTest`（4 例）
  **100% error**（不是 failure）。
- 修法：用**「同租户存在性守卫触发器」**替换这三条外键（新建迁移 `V276`）。
  触发器**不阻止 TRUNCATE**（外键才会），但**照样在 BEFORE INSERT OR UPDATE 拦住**
  「引用了但对方根本不存在」—— V270 第 368–370 行的保护意图**一字不丢**。

## 关键 trade-off

1. **换守卫触发器，而不是改那四个测试类的 `truncate`。**
   - 改 truncate 是**把跨车道破坏的锅甩给别的批次**：受影响的两个类属 11.4.1.2、
     另两个属 11.4.1.1，它们交付时这些外键还不存在，「它们本来是绿的」是事实。
   - 更糟的是**留下复发陷阱**：只要允许「用 truncate 绕开约束」，下一条指向
     `platform_dispute` 的外键会**再炸一次**，而这次炸的仍然是别人的测试
     —— 同一个坑埋第三遍（本轮实际就是：V270 埋一遍、V269 又埋一遍）。
2. **守卫按 `tenant_id + id` 联合判，比原来的裸 `id` 外键更严。**
   原外键是 `REFERENCES platform_dispute(id)`（不含 tenant）⇒ **A 租户的工单可以引用
   B 租户的纠纷**，只要那个 uuid 存在就放行 —— 一个实打实的跨租户洞。
   新守卫额外拦下「对方存在但属于别的租户」，并由测试**在超管连接（RLS 完全不参与）
   下复验**，证明拦它的确实是守卫本身而不是 RLS。
3. **不删保护。** 直接把外键删光会让「升级了但对方根本不存在」的查无实据工单重新变得可能。
   ⇒ 守卫补的是**写入侧的存在性 + 租户归属**，外键原来的 `ON DELETE RESTRICT` 语义
   见第 4 条。
4. **语义收窄一处（如实记录）：`V269:136` 的 `ON DELETE CASCADE` 不再由 DB 兜底。**
   换成守卫后，**删纠纷不再自动连带删申诉**。取证结论：全仓**没有任何一条 DELETE 路径**
   会去删 `platform_dispute`（服务层与测试层 grep 均无命中；11.4.1.2 的 `withdrawAppeal`
   是**状态流转** `appeal_status='WITRAWN'` 不是删行；11.4.1.2 里唯一的 delete 是
   「删除草稿态下的一项材料」`delete from platform_dispute_appeal_material`）
   ⇒ 这条 CASCADE 此前是**空转的**，去掉它不改变任何现存行为。
   ⇒ 将来 11.4.1.1 若要支持「删纠纷」，应由**那条线的拥有者**决定「连带删还是拦住」——
   静默级联删掉别的模块的申诉单，正是跨模块外键最典型的破坏方式。
5. **不用新迁移改旧迁移。** 迁移一旦发布就是历史，V269/V270 一个字没动，
   全部修正走 `V276`（版本号由调度者预分配，V271–V275 被在途车道占用）。
6. **约束名不靠猜。** V269:136 是**列级内联** `REFERENCES`，约束名由 PostgreSQL 自动生成
   （实测为 `platform_dispute_appeal_dispute_id_fkey`，但那是实现细节）。
   ⇒ 迁移里按「子表 + 父表」反查 `pg_constraint` 拿真名再删，
   照抄 V270 第 80–89 行处理内联 CHECK 时的既有写法。
7. **只动自己模块的表。** 指向 `platform_dispute` / `aftersales_case` 的外键逐条定性：
   V265:247 / V265:299（11.4.1.1 **自己模块**的子表）与 V269:232 / V269:293
   （11.4.1.2 **自己模块**的子表）**一条都不动** —— 它们不挡 `truncate platform_dispute`
   （对应子表都在 11.4.1.1 的 truncate 名单里），且是本仓一贯的正确写法。

## 涉及文件

- `backend/services/trade/src/main/resources/db/migration/V276__service_ticket_cross_module_guard.sql`
  （**新建**：撤三条跨模块外键 + 建两条同租户存在性守卫触发器
  `guard_aftersales_service_ticket_escalation` / `guard_platform_dispute_appeal_dispute`）
- `backend/services/trade/src/test/java/com/starcon/trade/ServiceTicketIntegrationTest.java`
  （**+6 例**守卫自证：工单升级去向指向「不存在 / 别的租户 / 同租户」的三种走向；
  跨租户那例额外在**超管连接**下复验）
- `backend/services/trade/src/test/java/com/starcon/trade/PlatformDisputeAppealIntegrationTest.java`
  （**+3 例**守卫自证：申诉单挂的纠纷「不存在 / 别的租户 / 同租户」）
- 服务层、端点、页面、`packages/api-contracts`、`apps/web/src/lib/api-proxy.ts`、
  `docs/product/capability-blueprint/module-progress.md`：**全部未动**（本次是修复，不是新交付）

## 验证

- L0：`cd backend && ./mvnw -q compile` → 绿；`pnpm typecheck` → 3/3 turbo tasks 绿。
- L1：`./mvnw -pl services/trade -am test -Dtest='PlatformDispute*,ServiceTicket*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **105/105 绿（Failures: 0, Errors: 0）**，**连续两次跑均绿**：
  - `PlatformDisputeIntegrationTest` 27（原 27 error → 现 27 绿）
  - `PlatformDisputeTenancyIntegrationTest` 4（原 4 error → 现 4 绿）
  - `PlatformDisputeAppealIntegrationTest` 31（原 28 error → 现 28 绿 + 新 3）
  - `PlatformDisputeAppealTenancyIntegrationTest` 5（原 5 error → 现 5 绿）
  - `ServiceTicketIntegrationTest` 35（原 29 绿 + 新 6）
  - `ServiceTicketTenancyIntegrationTest` 3（绿）
  - 四个测试类的 `truncate` 语句**一行未改**。
- **独立复核**（一次性 postgres:16-alpine，按序跑完全部 76 个迁移后用 psql 直接验）：
  - `pg_constraint` 反查：指向 `platform_dispute` / `aftersales_case` 的外键**只剩 2 条**
    （`platform_dispute_evidence_item_dispute_id_fkey` /
    `platform_dispute_settlement_dispute_id_fkey`，都是 11.4.1.1 自己模块的子表，按纪律保留）。
  - 两个测试类里**原样**的 `truncate` 语句均执行成功（**无 CASCADE**）。
  - 守卫行为：跨租户引用申诉单 → `ERROR: 申诉单挂的纠纷不存在或不属于当前租户`（23514）；
    同租户引用 → 成功。
- 容器：L1 期间同时存活的 `postgres:16-alpine` 容器**最多 1 个**
  （各测试类各自起容器、各自 `@AfterAll` 停掉，surefire 串行跑）；跑完后
  `docker ps -a` 为**空**（已被 ryuk 回收）。

## 后续待办

- **跨模块外键这个形状在本仓还有没有别的实例，本轮只查了 trade 服务指向
  `platform_dispute` / `aftersales_case` 的那 7 条。** 建议下一轮单独派一条车道
  全仓扫一遍「子表带外键 → 父表不可 TRUNCATE」的形状（线索：任何服务里
  `truncate <父表>` 的测试隔离写法）。
- **`ON DELETE RESTRICT` / `CASCADE` 的删除侧语义现在一律靠服务层自觉。**
  11.4.1.1 / 11.1.1.1 若将来支持删纠纷 / 删售后单，需要由**那些线的拥有者**
  各自决定「连带删还是拦住」，不要让后来的模块用一条外键替它们决定。
- **V271–V275 到位后，本迁移的版本号需要在集成头复核一次**
  （V276 是调度者预分配的；若那几条最终改号，本车道要跟着改）。
