# 9.1.2.1 平台订单同步（D5 阶段，L3 锚点 9.1.2）

> 车道 `feat/9-1-2-1`，worktree `.worktrees/9.1.2.1`，基线 `9f0fd1d7`。
> **本车道经历了一次 504 中断 + 接管续做**：详见「中断与接管」一节。

## 摘要

把平台订单接进统一订单模型。webhook 实时接入与轮询补拉**共用同一条 `ingest()` 落库路径**，
逐条事件做：幂等去重 → 平台状态归一 → 金额与税费还原 → 建单 / 推进 / 拒收，
落地到 V99 的 `sales_order` 簇（不新建平台订单主表），并把「组件展开（按映射版本）」
按映射版本一起快照下来。

**不新建平台订单主表**是本模块最要紧的一条边界：蓝图要的是「形成统一订单模型」，
所以平台单在 V99 的 `sales_order` 簇里就是一张普通销售单（`order_source` 取平台来源、
`manual_kind` 留空），9.1.1.1 / 9.1.1.2 不改一行查询就能读到平台单。

## 决策摘要

1. **幂等双闸都落在 DB 层**，不靠服务层的先查后写（那有竞态）：
   - 闸 1（事件级）`platform_order_sync_event.UNIQUE(tenant_id, event_key)`，
     事件键 = `sha256(平台 | 外部单号 | 事件类型 | 内容哈希)`。
   - 闸 2（订单级）`platform_order.UNIQUE(tenant_id, platform_code, external_order_no)`
     - `UNIQUE(tenant_id, idempotency_key)`。
       两者都用 `ON CONFLICT DO NOTHING` 挡住，**并发重复由 PostgreSQL 判，不由应用层猜**。
       服务层把「重复」（业务上正常）与「拒收」（业务上异常）分成两个 outcome，不混。

2. **状态归一不猜**：平台状态没有登记映射 ⇒ **整单拒收**（`UNMAPPED_PLATFORM_STATUS`），
   绝不猜一个内部状态码落库。只有组件类型允许兜底 `OTHER`，且额外存 `external_type`，
   界面才分得出「平台真报的 OTHER」与「我们兜底的 OTHER」。

3. **订单内容写一次**：V99 收回了运行角色对行 / 地址 / 费用 / 客户快照的 DELETE 权，
   「整体替换订单内容」**没有合法路径**。所以平台侧改了内容时：
   - 单据还是草稿 ⇒ `ORDER_CONTENT_DIVERGED`
   - 单据已提交进审单队列 ⇒ `ORDER_SNAPSHOT_FROZEN`
     两种都**交 9.1.1.3 订单变更与取消**处理，本模块只把事实记成异常明细。

4. **金额口径与 V99 同式**：行小计 = `round(单价 × 数量, 4)`、行折扣恒为负、头折扣取正、
   合计 = `小计 − 折扣 + 税 + 运 + 其他`。平台报的应付总额与本侧还原结果差超过 0.01
   即拒收（`AMOUNT_MISMATCH`）—— 超过一分钱就不是舍入而是口径不一致，不静默吸收。

5. **映射只能经 channel 授权 API 取**：`GET /channel/platforms/{code}/enums?kind={kind}`，
   转发调用方自己的 Authorization bearer，**租户不由端口传递**。
   本车道一次跨服务 SQL / FK / 共享视图都没碰（AGENTS.md 的硬边界）。

## 关键 trade-off

| 决策                         | 选择                                                                           | 理由                                                                                                                                                          |
| ---------------------------- | ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 要不要新建平台订单主表       | **不建**，写进 V99 的 `sales_order` 簇                                         | 蓝图要的是「形成统一订单模型」。另建一张平台单表就得双写，两张表的订单数迟早对不上；而且 9.1.1.1 / 9.1.1.2 都要改查询才能看到平台单 —— 那是对已交付车道的回归 |
| 来源范围含不含店铺           | **只到平台层**                                                                 | V99 `sales_order` 的外部单号唯一口径是 `(tenant, order_source, external_no)`，`order_source` 词表没有店铺维度；本车道禁 ALTER 既有表 ⇒ 加不出店铺范围         |
| 同号来自另一店铺怎么办       | **拒收 + `EXTERNAL_ORDER_NO_COLLISION`**，不判成「重复」                       | 默默判重复会让运营以为那家店铺的单同步过了，真相是它**从没进来过**。店铺只作追溯维度留在 `platform_order.shop_code`                                           |
| 组件唯一键用什么             | `(tenant, order_id, line_no, component_source, component_seq)` —— **序号入键** | 同款件在一个套装里出现两次是合法的；码入键会让整单事务回滚。序号保证「平台报的第几个」永远有唯一答案                                                          |
| 映射副本怎么留痕             | 唯一键加 `mapping_version` + 部分唯一索引 `... WHERE superseded_at IS NULL`    | 「停用旧行 + 写新行」与原唯一键冲突，留痕设计落不下去；且历史订单指向的版本行不能被删                                                                         |
| 平台改了内容怎么办           | 记异常（`ORDER_CONTENT_DIVERGED` / `ORDER_SNAPSHOT_FROZEN`），交 9.1.1.3       | 订单内容是**下单当时**的快照。V99 已用触发器收回改写权，本模块硬改就是在破坏快照语义                                                                          |
| webhook 与轮询要不要两套逻辑 | **共用一条 `ingest()`**                                                        | 两套逻辑各写一遍迟早分叉；轮询补拉与实时接入的差异只有「事件类型默认不同」和「时间窗」                                                                        |
| 平台应付总额对不上怎么办     | **拒收**，不静默吸收                                                           | 金额是资损面。差 0.01 以内当舍入，超过就是口径不一致，必须让人看见                                                                                            |
| 组件类型没映射怎么办         | 兜底 `OTHER` + 存 `external_type`                                              | 组件类型只影响展示归类，猜错不污染订单状态机；但原始词必须留着，否则界面分不出两种 OTHER                                                                      |
| 时间怎么绑                   | ISO-8601 带偏移量文本 + `?::timestamptz` + `OffsetDateTime`                    | `Timestamp.valueOf(LocalDateTime)` 按 JVM 本地时区静默偏 −28800s。测试要**断言写回值**，不是断言"没报错"                                                      |
| 前端的写入入口               | 「**可编辑 JSON 模板重放**」                                                   | 平台侧报文形状千差万别，表单化等于替平台猜字段；可编辑模板让运营用真实报文重放，且走的是真后端端点（不是 mock）                                               |
| 「重复」怎么显示             | **单列一格**，不混进「失败」                                                   | 把重复算成失败会让运营以为平台侧出了问题。归一状态与单据状态也分两列，不合成一列                                                                              |

## 中断与接管（504 中断 + 接管续做）

**本车道发生过一次 504 中断**：上一轮（接管前）跑到一半被网关 504 打断，
工作区里留下 **3 个半成品文件**：

- `backend/services/trade/src/main/java/com/starcon/trade/PlatformEnumMappingClient.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformOrderSyncModels.java`
- `backend/services/trade/src/main/resources/db/migration/V207__platform_order_sync.sql`

**接管续做时的处理原则：读懂后局部修补，不推倒重写**（这三个文件承载的取舍是对的，
推倒重来只会把上一轮想清楚的东西再想一遍）。续做时**改了什么**：

1. `PlatformEnumMappingClient.java` —— 补上缺失的
   `import org.springframework.http.client.JdkClientHttpRequestFactory`。
   **原文件根本编译不过**（上一轮没跑到编译就断了），这是接管后第一个编译错误。
2. `V207__platform_order_sync.sql` —— 局部改，未推倒：
   - **删掉三张表上的 `source_scope` 列**：V99 的 `sales_order` 外部单号唯一口径是
     `(tenant, order_source, external_no)`，没有店铺维度 ⇒ 唯一范围取平台层。
   - 组件唯一键由 `(..., component_source, component_code)` 改为
     `(..., component_source, component_seq)`，并新增 `component_seq int NOT NULL CHECK > 0`。
   - `platform_order` 新增 `content_fingerprint varchar(64)`（判「平台又改了内容」）。
   - `platform_order_component` 新增 `external_type varchar(128)`。
   - 映射表唯一键加 `mapping_version`，并新增部分唯一索引
     `platform_order_enum_mapping_current_unique ... WHERE superseded_at IS NULL`。
   - 异常表改为 `platform_code` / `shop_code`；索引改名。
3. `PlatformOrderSyncModels.java` —— 局部改：新增错误码 `ORDER_CONTENT_DIVERGED`、
   `EXTERNAL_ORDER_NO_COLLISION`；新增 4 个查询 record 与 `PlatformOrderSyncMappingSyncResult`；
   删掉各出参里的 `sourceScope` 字段（改用 `platformCode` / `shopCode`）；
   组件行加 `componentSeq` / `externalType`；`buyerId` 长度 128 → 64（对齐 DB `customer_code varchar(64)`）。

**接管后新写**：`PlatformOrderSyncService` / `PlatformOrderSyncController`、
两个集成测试、前端模型 / 工作区 / 页面 / allowlist 测试、`api-proxy.ts` 的一条元素。

**接管后修掉的 bug**（都是实跑出来的，不是推演）：

| 现象                                 | 根因                                                                                                        | 修法                                                                      |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| 全部 webhook 受理 409 回滚           | `sales_order` 的 INSERT 21 列只给了 20 个表达式（`?::timestamptz` 顶到了 `status` 上）                      | 补回一个 `?`，`?::timestamptz` 落到 `submitted_at`                        |
| 同上                                 | `platform_order` 的 INSERT 25 列只给了 24 个表达式（`synced_at` 的 cast 顶到了 `last_event_id`）            | 补一个 `?`，`?::timestamptz` 落到 `synced_at`                             |
| 折后价用例报「折后价高于原价」       | 折后价路径的符号反了（算出 **+2.0000**，应恒为负）                                                          | 改成 `discounted.subtract(original)`，拒收检查 `signum() > 0` 随之成立    |
| 重复事件用例 `duplicateCount` 为 0   | 测试的 `event()` 每次调用都生成**新的随机 skuId** ⇒ 两次事件内容不同 ⇒ 指纹变了，被判「内容被改」而不是重复 | 测试改成**同一份 body 投两次**                                            |
| 状态推进用例被判 REJECTED            | 同上（两条事件内容不同）                                                                                    | 测试改成复制首投事件、只改 `platformStatus` 与 `platformEventVersion`     |
| 组件用例取到 `COMPONENT` 而非 `LINE` | 显式组件挂在了第一行，而断言按 `line_no` 排序后期待第一行是「没有显式组件的那行」                           | 显式组件改挂第二行：第一行落它自己（LINE），第二行展开 2 个 COMPONENT     |
| 组件用例查不到行                     | 测试 join 写成 `p.order_id = c.order_id`                                                                    | 改成 `p.id = c.order_id`（组件表外键指向 `platform_order(tenant_id,id)`） |

## 涉及文件

**后端**

- `backend/services/trade/src/main/resources/db/migration/V207__platform_order_sync.sql`（6 张新表，零 ALTER 既有表）
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformOrderSyncController.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformOrderSyncService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformOrderSyncModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformEnumMappingClient.java`
- `backend/services/trade/src/test/java/com/starcon/trade/PlatformOrderSyncIntegrationTest.java`（20 项）
- `backend/services/trade/src/test/java/com/starcon/trade/PlatformOrderSyncTenancyIntegrationTest.java`（3 项 = 租户三断言）
- `backend/services/trade/src/test/java/com/starcon/trade/PlatformOrderSyncMappingSyncIntegrationTest.java`
  （3 项 = 跨服务边界：JDK `HttpServer` 起的 channel 桩 + 真 HTTP，钉住
  「走的是 channel 授权 API」「转发调用方自己的 bearer」「租户不由本端口传递」
  「channel 拉不到时显式失败」）

**前端**

- `apps/web/src/components/platform-order-sync/platform-order-sync-model.ts`
- `apps/web/src/components/platform-order-sync/platform-order-sync-model.test.ts`（20 项）
- `apps/web/src/components/platform-order-sync/platform-order-sync-workspace.tsx`
- `apps/web/src/app/orders/platform-sync/page.tsx`
- `apps/web/src/app/api/trade/platform-order-sync-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（追加一条 `trade/` 元素，紧跟既有 `order-review` 之后）

**契约 / 台账 / 会话**

- `packages/api-contracts/src/trade.openapi.json`、`packages/api-contracts/src/contracts/trade.ts`
- `docs/product/capability-blueprint/module-progress.md`（9.1.2.1 那一行）
- 本文件

## 验证

- L0：`cd backend && ./mvnw -q compile` 通过；`pnpm typecheck`（3/3 包）通过。
- L1：`./mvnw -pl services/trade -am test -Dtest='PlatformOrderSync*'` → **26 项全绿**
  （20 项功能 + 3 项租户三断言：两租户互不可见、伪造 tenant header 无效、连接池归还不串租户
  - 3 项跨服务边界）。
- 前端：`pnpm vitest run apps/web/src/components/platform-order-sync` → 20 项通过；
  `pnpm vitest run apps/web/src/app/api/trade` → **43 文件 229 项全通过**（既有 trade 入口未被误伤）。
- 契约：在本 worktree 跑 6 个服务的 `OpenApiContractTest` 导出 spec，再
  `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重生成。

## 后续待办

1. **9.1.1.3 订单变更与取消**接手 `ORDER_CONTENT_DIVERGED` / `ORDER_SNAPSHOT_FROZEN`
   两类异常 —— 本模块只记事实，不改订单快照。
2. **平台单与手工单的 `order_source` 词表**要在 9.2 审单队列侧确认
   「PLATFORM」来源的订单进队列后的单据编号与筛选口径。
3. **channel 契约漂移**（非本车道）：本车道重生成契约时发现
   `packages/api-contracts/src/channel.openapi.json` 与 channel 服务的真实端点已经对不上
   （channel 代码已合、契约未重生成）。**本车道只提交 trade 侧的契约变更**，
   channel 的重生成归 channel 车道。
4. **平台适配器**：本模块只吃「已经归一过的平台报文」；真正的 Amazon / Shopify 适配器
   属 02 渠道与店铺系统，本车道经 channel 授权 API 取枚举映射，不直接对接平台。

## 关联

- 能力蓝图：`docs/product/capability-blueprint/README.md`（9.1.2.1 平台订单同步）
- 统一订单模型：`backend/services/trade/src/main/resources/db/migration/V99__trade_manual_sales_order.sql`
  （`guard_sales_order_totals` / `sales_order_line_discount_is_non_positive` /
  「已提交即冻结」的触发器，本模块的口径全部对着它写）
- 枚举映射来源：2.1.2.1（channel `GET /channel/platforms/{code}/enums`）
- 多租户：`docs/decisions/0002-multi-tenancy-strategy.md`、
  `docs/architecture/multi-tenancy.md`
- 平台适配器边界：`docs/decisions/0004-java-spring-boot-microservices.md`
