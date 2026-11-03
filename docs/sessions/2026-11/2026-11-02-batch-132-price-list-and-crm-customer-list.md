# batch-132 —— 5.1.1.1 价目表列表 / 15.3.1.1 客户列表 两条车道首次并入交付

> 批次：batch-132（接 batch-131 往后排）
> 集成头：`integration/module-dev`（HEAD=41391b61，含本批两个 merge commit）
> 本批并入：`5.1.1.1`（catalog，V431）+ `15.3.1.1`（crm，V432 + identity V432 权限迁移）
> 车道：`feat/5-1-1-1` / `feat/15-3-1-1`
>
> 每个模块自己的完整设计说明（决策 / trade-off / 文件清单）：
> - `docs/sessions/2026-11/2026-11-02-price-list-list-module.md`（5.1.1.1）
> - `docs/sessions/2026-11/2026-11-02-crm-customer-list.md`（15.3.1.1）

本批两个模块分别是两条独立上游链的地基：
5.1.1.1 是 **5.1.1.2 价目项批量维护 / 5.1.2.1 优先级与冲突检测** 的上游；
15.3.1.1 是 **15.3.1.2 客户详情 / 15.3.1.3 跨店合并 / 15.6.2.1 生命周期与流失预警** 的上游。
⇒ 它们落地后，本轮随即派出 4 条下游车道（见文末「本批派发」）。

---

## 模块 5.1.1.1 价目表列表

**这个功能做什么**：运营在 `/pricing/lists` 管理价目表（如「北美自发货零售价」）。每张价目表绑定**一个币种**、
适用**法人 / 店铺 / 站点**、有**生效区间**，列表展示表内价目项数量与最近更新时间；可新建 / 修改 / 删除，
并给出覆盖范围摘要（币种分布、范围类型分布、每张表的摘要）。

**技术落地**：

- **迁移**：`backend/services/catalog/src/main/resources/db/migration/V431__price_list.sql`
  —— 两张新表：
  - `price_list`（租户级价目表行：`tenant_id NOT NULL` + `currency` 单列 NOT NULL +
    命名约束 `price_list_one_currency` + `valid_from` / `valid_to` 半开区间 +
    `item_count` 计数器 + `customized` 等列 + `UNIQUE(tenant_id, ...)`）
  - `price_list_scope`（适用范围行：法人 / 店铺 / 站点，**用编码不用外键** —— 跨服务 FK 被 AGENTS.md 禁止）
  - 两表均：`tenant_id NOT NULL` + **ENABLE 且 FORCE** RLS `isolation` 策略（FORCE 是必须的：owner 身份一连就会绕过隔离）
    + DB 触发器兜底（直连 SQL 也写不出跨租户的行）+ `GRANT` + 索引
  - **零 ALTER 任何既有表**（本批新增迁移对既有事实表的 DDL 回归面 = 0）
- **端点**：`/catalog/price-lists` 共 **8 个** ——
  `GET /capabilities`、`GET /scope-directory`、`GET /lists`、`GET /coverage`、
  `GET /lists/{priceListId}`、`POST /lists`、`PUT /lists/{priceListId}`、`DELETE /lists/{priceListId}`
- **页面**：`/pricing/lists`（`apps/web/src/app/pricing/lists/page.tsx` +
  `apps/web/src/components/pricing/price-list-model.ts` + `price-list-workspace.tsx`）
  —— 真实工作区（能力与边界声明 / 价目表列表 / 适用范围配置 / 生效区间 / 覆盖范围摘要），
  **加载态 / 空态 / 错误态齐全**
- **白名单**：`apps/web/src/lib/api-proxy.ts` 新增**一条独立 `services[]` 元素**
  （`price-lists` 独立前缀，既有条目一字未改、也不重排）
- **测试**：
  - 后端 **37 例全绿** —— `PriceListIntegrationTest` 19 + `PriceListRulesTest` 13 + `PriceListTenancyIntegrationTest` 5
    （含**租户隔离三断言**：两租户互不可见 / 伪造 `X-Tenant-Id` 无效 / 连接池归还后不串租户）
  - 前端 **29 例全绿** —— `price-list-model.test.ts` 24 + `price-list-allowlist.test.ts` 5

**关键设计决策**（详见模块自己的说明文档）：

1. **「一价目一币种」钉在表头单列，不是集合约束** —— `price_list.currency` 是单列 NOT NULL，
   表结构上就不存在第二个币种位；列宽刻意留 `varchar(16)` 而不是 `varchar(3)`，
   因为 PG **先判列宽再判 CHECK**，列宽收窄会把「是哪条规矩挡的」藏掉。
   并给下游写死结构性约定：**价目项表不得自带币种列**（币种只能来自 `price_list.currency`）。
2. **「表内价目项数量」用计数器 + 给下游的触发器函数，不建价目项表** ——
   `price_list_item` 是 5.1.1.2 的写入所有权（两边都建 ⇒ 同一张事实表两个写入所有者）。
   落法：`price_list.item_count` 计数器 + 本迁移提供触发器函数 `price_list_sync_item_count()`，
   5.1.1.2 交付时只需 `CREATE TRIGGER ... EXECUTE FUNCTION`。价目项模块未交付前 `item_count` 恒为 0 ——
   那是**真实的 0**，不是「取不到」，能力声明与页面都把这句写出来。
3. **5.1.2.1 是下游（守门员）不是前置** —— 蓝图把它同时写成上游与下游（互为上游 ⇒ 死锁）。
   本模块因此**不做任何冲突判定**，边界在能力声明、覆盖范围摘要与台账里都写明。
4. **适用范围用编码 + 上游授权只读端点，跨服务不建副本** —— 新建 `PriceListScopePort`（法人 + 店铺），
   站点复用既有 `ChannelPlatformPort`；**法人不走 `FunctionalCurrencyPort`**（那个端口的契约是「本位币」，
   会主动过滤掉没配本位币的法人 —— 对汇率是对的，对「适用范围目录」是错的）。
   上游读不到 ⇒ **503**（不用空列表冒充「没有法人 / 没有店铺」）。
5. **生效区间是半开区间 `[valid_from, valid_to)`** —— 首尾相接的两张价目表不会在交界那一刻同时生效
   （这是 5.1.2.1 冲突检测能成立的前提）；「此刻是否生效」用 DB 的 `now()` 判定，不混 JVM 墙钟。

---

## 模块 15.3.1.1 客户列表

**这个功能做什么**：运营在 `/crm/customers` 看客户列表：多维筛选（来源店铺 / 客户标识 / 价值分层 / 风险标记 /
联系方式有无）、真分页、分层统计（不受分页影响），并能刷新上游订单与售后事实、编辑联系方式。
联系方式默认脱敏，明文需要额外权限码且调用方显式要求。

**技术落地**：

- **迁移**：
  - `backend/services/crm/src/main/resources/db/migration/V432__crm_customer_list.sql`
    —— 客户主档 / 分层统计相关表，`tenant_id NOT NULL` + **ENABLE 且 FORCE** RLS `isolation`
    + DB 触发器兜底 + `GRANT` + 索引；金额一律 `NUMERIC`（禁浮点），**按币种分行**（跨币种金额筛选必须给币种，否则 400）
  - `backend/services/identity/src/main/resources/db/migration/V432__crm_customer_permission.sql`
    —— 权限码 `crm.customers.read` / `crm.customers.update` / `crm.customers.pii.read`
    + page_resource `crm.customers` + 存量 OWNER 补权
- **端点**：`/crm/customers` 共 **5 个** ——
  `GET /capabilities`、`GET`（列表，真分页）、`POST /sync`（刷新上游事实）、`GET /runs`（刷新运行记录）、`PUT /contact`（改联系方式）
- **页面**：`/crm/customers`（`apps/web/src/app/crm/customers/page.tsx` +
  `apps/web/src/components/crm/customer-list-model.ts` + `customer-list-workspace.tsx`）
- **白名单**：`apps/web/src/lib/api-proxy.ts` 新增**一条独立 `services[]` 元素**（`customers` 前缀，既有条目一字未改）
- **测试**：
  - 后端 **18 例全绿** —— `CustomerListIntegrationTest` 14 + `CustomerListTenancyIntegrationTest` 4
  - 前端 **19 例全绿** —— `customer-list-model.test.ts` 14 + `customers-allowlist.test.ts` 5

**关键设计决策**（详见模块自己的说明文档）：

1. **客户是「按来源店铺」的，不是「逻辑客户」** —— 身份列是 `(source_shop, customer_ref)`，
   同一个客户标识在两个来源下是两行。**不建逻辑客户主表、不建合并映射** ——
   那是 15.3.1.3 的事实表，两边都建会撞「每张事实表只有一个写入所有者」。
2. **「来源店铺」当前是订单来源口径，不假装是店铺级** —— 上游订单读投影没有 `shop` 列，只有 `order_source`；
   从 `order_source` 反推 shop 是**猜** ⇒ 列名与文档都写明这一点。
3. **上游只读走 trade 的授权 HTTP API**（复用既有 `CustomerFactsPort`），联系方式另走 catalog 的 `CustomerPartyPort`；
   转发调用方自己的 `Authorization` bearer ⇒ 租户隔离是**两道**（crm 侧 RLS + 上游 RLS）。
   售后单不带买家维度 ⇒ 「售后单 → orderId → 订单 → customer_party_id → 客户」的归因链路
   在**一次拉取内**建索引完成，本库**不建任何指向 trade 的外键**。
4. **退款率口径照抄 15.3.2.1**（同服务、同一个客户口径）；**样本不足 5 单 ⇒ `INSUFFICIENT_SAMPLE`（不可判定）**，
   不是「没风险」；「显著高于」判据 = ≥ 租户基线 × 2（基线由同窗真实事实算出，不写死数字）。
5. **联系方式是「两把锁」** —— 脱敏形态在**写库之前**算好落库（读时现算会让脱敏规则一改就显示出另一种形态）；
   明文需要额外权限码 `crm.customers.pii.read` **且**调用方显式要求；
   没权限还硬要 ⇒ **403**，而不是悄悄退回脱敏（那会让调用方以为权限已生效）。
6. **价值分层的基线必须在金额落库之后算** —— 先算基线再落库 ⇒ 第一次刷新时基线是空的 ⇒ 所有人都是「常规」，
   而分层统计看上去又「有值」，那是最难发现的一种错。

---

## 本批 L2 结论（r158）

**L2_RESULT = GREEN**（存量红 0 条 / 本批新增失败 0 条 ⇒ 允许 push）

### 静态预检（互相破坏形状扫描）—— 零命中

对本批三条新迁移（`V431__price_list.sql`、`V432__crm_customer_list.sql`、`V432__crm_customer_permission.sql`）跑：

| 检查 | 结果 |
| --- | --- |
| `ADD COLUMN` 无 DEFAULT | **0 命中** |
| `SET NOT NULL` | **0 命中** |
| `ADD CONSTRAINT ... CHECK` | **0 命中** |
| ALTER 既有事实表 | **0**（唯二 `ALTER TABLE` 是 RLS `ENABLE/FORCE` 且只作用于本迁移新建的表） |

### 后端 verify（分块）

```
cd backend && ./mvnw verify -fae -pl libraries/tenancy,services/identity,services/catalog,services/crm,services/platform
```

| 模块 | 结果 | 耗时 |
| --- | --- | --- |
| tenancy | SUCCESS | 0.4 s |
| identity | SUCCESS | 03:03 min |
| catalog | SUCCESS | 06:15 min |
| crm | SUCCESS | 01:33 min |
| platform | SUCCESS | 02:13 min |
| **合计** | **BUILD SUCCESS（Failures: 0, Errors: 0）** | **13:07 min** |

**验证范围为什么是这样划的**（不是随手挑的）：

- 本批变更的模块（必须重验）：`identity`（V432 权限迁移）、`catalog`（V431 + 价目实体）、
  `crm`（V432 + 客户实体）
- `platform` —— r157 只覆盖了 tenancy/identity/catalog，**platform 在 V429/V430 变更后从未被验证过**
  ⇒ 本轮补验（`AiDiagnosisIntegrationTest` / `AiEffectEvaluationIntegrationTest` / `DeliveryRecordIntegrationTest`
  等全绿，含 batch-131 的两个模块首次被验证）
- `channel` / `warehouse` / `trade` / `finance` / `reporting` —— 源树与 r156（`3d5b4669`）验证时**逐字节相同**
  （`git rev-parse HEAD:backend/<module>` 对比确认）⇒ **复用 r156 的 GREEN 结论**，不重复烧机器时间。
  ⚠️ 其中 `trade` 含 `.dev-agent/l2-baseline.txt` 记录的 **24 条存量红**
  （`PendingShipmentIntegrationTest` 18 errors + `FulfillmentMonitorIntegrationTest` 5 failures
  + `FulfillmentRoutingIntegrationTest` 1 failure，根因是 trade 调 warehouse 的待发货清单只读接口返回 404）。
  这 24 条**不在本批变更范围内**，且本批新增失败为 0。

### 前端 6 步

| 步骤 | 结果 |
| --- | --- |
| `pnpm typecheck` | **exit=0**（3/3 successful） |
| `pnpm lint` | **exit=0**（3/3 successful） |
| `pnpm test` | **exit=0** —— Test Files 727 passed / Tests **10198 passed** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **exit=0** |
| `node tools/module-progress/sync.mjs --check` | **exit=0** |
| `node tools/module-progress/format-gate.mjs` | **exit=0** —— 本批文件零违规 |

### 台账完整性

- 模块行数 465，模块 ID 无重复
- `sync.mjs` 输出：**已交付 455 · 进行中 0 · 未开发 10**（完成率 97.8%）

---

## 本批派发（下一轮回收）

`lane.mjs plan --lanes 4` 排出 4 条车道，`serviceBlocked` 为空：

| 模块 ID | 名称 | 阶段 | L3 | 后端服务 | 迁移版本 | 分支 |
| --- | --- | --- | --- | --- | --- | --- |
| 5.1.1.2 | 价目项批量维护 | D6 | 5.1.1 | catalog | V433 | `feat/5-1-1-2` |
| 5.1.2.1 | 优先级与冲突检测 | D6 | 5.1.2 | catalog | V434 | `feat/5-1-2-1` |
| 15.3.1.2 | 客户详情 | D7 | 15.3.1 | crm | V435 | `feat/15-3-1-2` |
| 15.6.2.1 | 生命周期与流失预警 | D7 | 15.6.2 | crm | V436 | `feat/15-6-2-1` |

**四条车道各占一个互不相邻的白名单锚点**（末尾 / catalog 中段 / crm 中段 / identity 中段）——
这是刻意设计的：`apps/web/src/lib/api-proxy.ts` 是共享白名单累积文件，四条车道若落在同一锚点，
下轮并入会再次撞车、成果整批卡在冲突里（上一轮 5.1.1.1 与 18.4.2.3 就是这样撞的）。

---

## 本轮其它动作

- **闸**：上一轮的持闸者（PID 2443）已死，闸龄超 TTL（4200s ≥ TTL 4200s）且
  `holderAlive=false`（无在途车道、无构建进程）⇒ `gate reclaim` **原子接管成功**，
  不是「抢别人的活闸」。
- **补交 batch-131 遗留**：上一轮在 L2 中途被打断，说明文件与台账没来得及 commit
  ⇒ 本轮以 `e4a3ef3b` 补交（`docs(batch-131): 18.4.2.3 / 18.4.3.2`），**未 push**，
  与本批说明一起推到远端。
- **5.1.1.1 的僵尸车道处置**：上轮并入冲突后状态停在 `conflict`，但 worker 已在自己分支里
  解决冲突并提交（`8c73d6be`，把白名单锚点搬到 catalog 中段避开 18.4.2.3 的同锚点）⇒
  判定为**半成品而非死车道**，按「续做优先于重派」用 `land` 重新标记 ready 后并入，
  **没有熔断、没有清掉成果**。
