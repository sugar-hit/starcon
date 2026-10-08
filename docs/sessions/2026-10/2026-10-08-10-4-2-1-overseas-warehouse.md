# 2026-10-08 · 10.4.2.1 海外仓连接（阶段 D7）

## 结论摘要

- **模块落在 `channel` 服务**，不是 warehouse。依据：蓝图 10 卷卷首「后端归属：channel 拥有物流商连接与轨迹」＋ L2 表「10.4 头程管理 → trade + warehouse + channel」＋ `AGENTS.md`「外部适配器（Shopify / Amazon / …形态的第三方系统对接）放在 channel，每个 vendor 独立包」。
- 🔴 蓝图加粗约束「外部仓库存是**观测值不是自有账本**，必须与自营库存**分开存储与对账，不能覆盖**」在 DB 层钉死，且由测试证明绕过服务层直接写 SQL 同样被拒。
- 迁移：channel `V93__overseas_warehouse_connection.sql`。**未触碰 identity / warehouse / trade 任何文件**，跨车道回归面 = 0。

## 关键 trade-off

### 1. 外部调用的边界怎么放（本仓库无真实海外仓账号与网络出口）

选项：(a) 写假 HTTP 客户端打真实域名 —— 会制造「看起来能连、实际永远失败」的假能力，**否决**；(b) 硬编码假响应当数据源 —— 违反禁 mock，**否决**；(c) 租户可维护的报送表承接外部边界 —— **采纳**。

做法是新增 `warehouse_inbound_stock_line`（服务商报送行 = 适配器的**输入**）与 `OverseasWarehouseAdapter` 统一接口，`ReportedFeedWarehouseAdapter` 从报送表读数。链路是真跑通的：录入报送 → 触发同步 → 读报送 → 落观测快照 + 落同步记录，全链路真落 PostgreSQL 16。将来接真实服务商只需新增一个接口实现，上层服务与数据模型一行不改。

**输入表可改（UPDATE）、观测表不可改**，这个区别是分表存在的理由，不是冗余：服务商报错要能修正输入，已观测到的事实不能篡改。

### 2. 不变量钉在哪一层

不是「记得别写」，是三层各自独立成立，任何一层被绕过都仍成立：

| 层 | 手段 | 挡住什么 |
|---|---|---|
| 物理 | 观测表落 **channel 库**，自营库存账本在 **warehouse 库**（V51），`AGENTS.md` 禁跨服务 DB 访问 | 同步路径在物理上够不到自营库存表 |
| 结构 | 观测表**没有 on_hand 这类余额字段**，每次观测插一行带 `observed_at` 的新快照；「当前值」用 `distinct on (external_sku) … order by observed_at desc` 查出来 | 没有可被「覆盖」的语义位置 |
| DB | `guard_external_stock_append_only` BEFORE UPDATE/DELETE 触发器（23514）+ `GRANT SELECT,INSERT`（刻意不给 UPDATE/DELETE） | 绕过服务层直连数据库也改不动 |

注意第 2 层是关键：如果只加触发器而保留 `on_hand` 字段，界面和后续代码仍会长出编辑框；反之只做快照语义而没有触发器，换个脚本就能破坏。**三层缺一不可**。

## 踩到的两个真坑

### 1. `on conflict do nothing` 静默丢数据（本车道自己发现并修）

适配器早期版本返回该账户**全部历史报送行**，且所有行盖同一个观测时点，于是同一 SKU 的多条行在观测表唯一约束 `(account_id, external_sku, observed_at)` 上互相吞掉 —— 服务商**改正过**的那个值被 `on conflict do nothing` 静默丢弃，观测值看起来正常、实际是旧的。

修法：适配器改为 `select distinct on (external_sku) … order by external_sku, reported_at desc`，一次拉取返回**每个 SKU 的当前值**而不是它的历史。已用 `correctedInboundValueWins` 回归钉死。

这类「静默丢数」比报错危险得多：报错会被发现，静默丢数只会在对账时变成一个说不清的缺口。

### 2. 幂等键写成按账户 → 一个账户一辈子只能同步一次

初版 `idempotency_key = "wst:" + accountId`，第二次同步永远 409。这把「防重复运行」错做成了「账户只能同步一次」。

改为按**批次**：`wst:{accountId}:{max(reported_at)}`。同批不重复跑，新批可再跑。由 `sameBatchIsIdempotent` 钉住。

### 3. api-proxy 白名单：sync-runs 被写成了 connections 的子路径

往 channel 既有 alternation 追加时，`sync-runs` 一度落进 `connections(...)` 的组里（成了 `connections/sync-runs`），`warehouse-providers/sync-runs` 被**静默拦成 404** —— 而 typecheck / lint / vitest 全绿。

这正是 `AGENTS.md` 警告的陷阱。已按要求用**真跑 proxy 断言 HTTP 状态码**（不是 grep + 自写正则）验证，并在 `api-proxy-union.test.ts` 中同时断言 24 条放行 + 5 条反向拦截，并把既有 channel 路由列为回归基线。

## 上游实查结论

| 上游 | 状态 | 处理 |
|---|---|---|
| 9.3.2.2 FBA 与自发货选择 | 已交付（trade V92） | **未复用**。`AGENTS.md` 禁跨服务读库，只能走授权 API 或 event；本车道时间窗内无可用授权 API。trade 里的 `fulfillment_platform_stock_snapshot` 与本模块的 `external_stock_observation` 语义不同（前者是履约决策的渠道快照，后者是服务商回报的观测事实），**刻意不合并**，在两条路径各自留来源字段 |
| 7.1.3.1 多仓分布 | 已交付（warehouse V55） | 下游依赖，本模块不写入其库。warehouse 已有 `platform_stock_observation`（平台仓观测），本模块的第三方仓观测与之并存不合并 |
| 2 渠道机制 | 已交付（channel） | 同服务内直接复用 `credential_ref`、`sync_*` 权限码与既有访问/租户机制 |

上游未交付的部分**没有建假上游、没有 mock 数据**。

## 权限决策

**复用** identity 已有的 `channel.connection.*` 与 `channel.sync.*`，**不新增权限码、不改 identity 服务**。理由：本模块只是 channel 既有权限面的新一组资源，新增权限码要多写一个 identity 迁移（跨车道回归面），而复用后回归面 = 0。若后续需要独立授权粒度，再补 identity 迁移。

## 未完成部分

- **订单预报（10.4.2.2）与账单对接**未实现。二者是独立 L3 模块，各有自己的输入与失败语义；硬塞进本模块会让「观测」被「推送」淹没。蓝图也把它们列为兄弟节点而非本模块子项。
- **真实服务商适配器**未实现（无账号与网络出口）。`OverseasWarehouseAdapter` 是清晰的可扩展点，接入时新增一个实现即可。
- **同步未接调度器**，当前只有手动触发端点。

## 验证

- `backend/./mvnw verify`：channel SUCCESS（9.886s），908 项中 1 项失败 —— `StockOwnershipIntegrationTest.货权档优先级是sku大于法人大于仓库大于兜底`，**非本车道问题**，详见下节。
- `pnpm typecheck`：3/3 通过
- `pnpm lint`：通过（警告均为既有文件，非本车道）
- `pnpm test`：98 文件 / **1810 项全绿**
- `pnpm --filter @starcon/web build`：`✓ Compiled successfully`，`/logistics/overseas-warehouse` 6.12 kB
- `node tools/module-progress/sync.mjs --check`：`✓ 台账与蓝图一致`

## 顺带发现（不属于本车道，未修）

`backend/services/warehouse/src/main/java/com/starcon/warehouse/StockOwnershipService.java:601`

```sql
order by case r.scope_type when 'SKU' then 0 ... end, r.effective_from desc limit 1
```

排序键只有「档位优先级 + 生效时间」，**没有最终 tiebreaker**。当多条规则同档位且 `effective_from` 相同时，选中哪一条由执行计划决定 → 结果不确定。这是 `StockOwnershipIntegrationTest` 偶发失败的根因（本次 verify 失败、单测重跑即绿）。

warehouse 属其他车道 ownership，按纪律**未修改**，在此报告。建议修法：排序末尾补 `, r.id`。