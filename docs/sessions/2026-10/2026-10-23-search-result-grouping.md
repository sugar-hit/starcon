# 1.2.1.2 搜索结果分组 —— 卷 01 工作台与门户

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`1.2.1.2`（worktree `integration/.worktrees/1.2.1.2`，分支 `feat/1-2-1-2`）
- **基线**：`integration/module-dev` @ `de6dbb98`（含 1.2.1.1 全局搜索框）
- **蓝图**：卷 01「工作台与门户系统」L3 1.2.1 统一搜索 → L4 1.2.1.2 搜索结果分组

---

## 这个功能做什么

把全局搜索的跨系统命中集按**业务域**分组展示，每组显示**条数**与**数据范围**标注，
支持**只看某一组**，并如实报出「因权限没返回的业务域」。

---

## 关键决策摘要

### 1. 为什么不落库、不新增端点

本模块是**聚合 / 视图层**：分组、计数、筛选、提示全在前端算。
**零新增表**（V331 预分配但**不占用**）、零新增端点、零新增服务。
唯一的落点是**只读契约演进**（见第 2 条）。

### 2. 「无权限结果计数提示」只报**域数**，不报命中条数 —— 报条数就越权了

蓝图要「无权限结果计数提示」，而上游契约只有 `unavailableDomains`（域码数组），没有命中条数。

- **不许编一个条数**（那是凭空造数据）。
- **报条数在技术上做不到且不安全**：后端要数「被抑制的命中」，就得在**用户没有的域权限**下
  跑一次查询 —— RLS 会把行全部滤掉（0 条），要真数出来只能绕过数据范围，那等于把
  「本租户里有多少条你看不到的订单」泄露给没有权限的人。
- ⇒ 走任务书给的两条正当出路里的**第二条**：报「N 个业务域因权限未返回：商品、订单」，
  并在页面上写明「**其中命中的具体条数未返回**」。

### 3. 被抑制的**子域**要带归属**业务域**（新增只读字段 `suppressedDomains`）

catalog 的域码是 `PRODUCT_SKU` / `PRODUCT_SPU` 这种**带域内实体前缀**的码，
和分组用的 `PRODUCT` 不是一回事。 光有一串码，前端算不出「几个业务域没返回」，
也挂不到对应的组上。

⇒ 四个检索服务（catalog / trade / channel / warehouse）**逐字同形**地加一个字段：

```java
record GlobalSearchSuppressed(String domain, String label, String group, String groupLabel)
record GlobalSearchGroup(..., String dataScope, String dataScopeNote)
record GlobalSearchResponse(..., List<GlobalSearchSuppressed> suppressedDomains)
```

> 四个服务**必须一起改**：codegen 的同名类型冲突闸门（`tools/codegen/catalog-contracts.py`）
> 在四个定义不一致时会**非零退出**并点名。改完重生成契约是**纯增量**（0 删除 / 0 既有内容改变）。
>
> `unavailableDomains` 的语义**一字未动**；两组数据来自同一次权限判定，
> 集成测试断言二者的子域集合相等（不变式）。

### 4. 「标注结果是否在当前数据范围内」只能由后端如实报

蓝图要求标注结果是否在当前数据范围内。范围是**后端的事**：
租户上下文、角色授权、数据范围都在后端与 RLS 里，前端拿到的会话只能重算一遍
（等于把授权判定复制到前端，纪律上不允许，而且会漂）。

⇒ 每个服务按**自己那张表的归属维度**报出该域**实际生效**的范围：

| 域 | 服务 | 报出的范围 | 依据 |
| --- | --- | --- | --- |
| 商品（SKU + SPU） | catalog | ALL / DEPARTMENT / SELF | 两条授权取**更严**的一侧（SKU 继承 SPU 的归属） |
| 客户 / 供应商 | catalog | TENANT | `party` 是租户级主数据，表上没有归属列 |
| 销售订单 | trade | ALL / SELF | `sales_order` 没有部门列，DEPARTMENT 退化为 SELF |
| 采购单 | trade | ALL / DEPARTMENT / SELF | `owner_user_id` / `owner_department_id` |
| 店铺 | channel | ALL / DEPARTMENT / SELF | `shop` 有 owner / department（`channel_scope`） |
| 仓库 / 物流 | warehouse | TENANT | 这四张表只有 `tenant_id` |

判定本体抽到共享库 `GrantPermissions.scopeMode(...)`，与各服务 RLS 的
`catalog_scope` / `channel_scope` **同语义**：「多条授权里有一条 ALL ⇒ 返回的行全租户可见」
（报成 SELF 会与实际可见范围不符）。

### 5. 顺手修掉了上游一处口径不一致（trade 订单域）

1.2.1.1 的全局搜索在订单域上**只判 SELF**：DEPARTMENT 授权时不加任何收窄条件，
于是部门范围的角色能搜到**全租户**订单 —— 与 9.1.1.1 订单列表
（「只要有一条 ALL 就按 ALL，否则按 SELF 收窄到本人经手过的订单」）不一致。

本模块改成**非 ALL 一律按「仅本人经手过」收窄**（`sales_order_status_history.actor_id`），
并加了一条集成测试：部门范围的角色搜不到别人经手过的订单。

> 这处改动是「如实标注数据范围」的**前提**：范围标注要成立，范围得先真的生效。

### 6. 前端：分组视图 + 只看某一组

- 新模型 `global-search-group-model.ts`（纯函数，可单测）：
  `buildGroupedView` / `filterGroups` / `groupOptions` / `permissionNotice` /
  `suppressedPartsNotice` / `describeGroupedOutcome`。
- **组计数与清单同源**：`count = 该组实际渲染的命中条数`，不另算一套。
- **只看某一组**是**纯前端过滤**：不重新发请求、不改上游契约；
  选中一个不存在的组时**不回落成全部**（回落会让人以为筛选没生效）。
- **无权限的组整组不出现**（蓝图原话）：被抑制的域只在提示条里点名，
  视图里不画空壳、不置灰。
- 状态行把三件事分开说：**命中** / **N 个业务域无权限** / **X 服务未响应**
  （服务挂了 ≠ 没权限 ≠ 没有数据）。

---

## 文件清单

### 后端（改动既有文件）

- `backend/libraries/tenancy/src/main/java/com/starcon/tenancy/GrantPermissions.java`
  （+ `scopeMode(...)` 与四个范围常量）
- `backend/services/{catalog,trade,channel,warehouse}/src/main/java/com/starcon/*/GlobalSearchModels.java`
- `backend/services/{catalog,trade,channel,warehouse}/src/main/java/com/starcon/*/GlobalSearchService.java`

### 后端（新增测试）

- `backend/services/catalog/src/test/java/com/starcon/catalog/GlobalSearchGroupIntegrationTest.java`
- `backend/services/trade/src/test/java/com/starcon/trade/GlobalSearchGroupIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/GlobalSearchGroupIntegrationTest.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/GlobalSearchGroupIntegrationTest.java`

### 前端

- 新增 `apps/web/src/components/dashboard/global-search-group-model.ts`
- 新增 `apps/web/src/components/dashboard/global-search-group-model.test.ts`
- 改动 `apps/web/src/components/dashboard/global-search-model.ts`（合并层带范围 + 抑制子域）
- 改动 `apps/web/src/components/dashboard/global-search-model.test.ts`
- 改动 `apps/web/src/components/dashboard/global-search-workspace.tsx`（分组视图 + 筛选条 + 提示）

### 其它

- `packages/api-contracts/**`（codegen 产物，纯增量）
- `docs/product/capability-blueprint/module-progress.md`（**只改本模块那一行**）

---

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 后端编译 | `cd backend && ./mvnw -q compile` | 通过 |
| L0 前端类型 | `pnpm typecheck` | 通过（3 包全绿） |
| L1 catalog | `./mvnw -pl services/catalog -am test -Dtest='GlobalSearchGroup*' …` | **8 例全绿** |
| L1 trade | 同上 `-pl services/trade` | **8 例全绿** |
| L1 channel | 同上 `-pl services/channel` | **6 例全绿** |
| L1 warehouse | 同上 `-pl services/warehouse` | **6 例全绿** |
| L1 回归（1.2.1.1 既有） | 同上 `-Dtest='GlobalSearch*'`（四个服务） | **64 例全绿** |
| 前端单测 | `pnpm vitest run apps/web/src/components/dashboard/` | **95 例全绿**（本模块 33 例） |
| lint | `pnpm --filter @starcon/web lint` | 通过（本模块无告警） |

集成测试实跑 Testcontainers PostgreSQL 16，连接池压到 1：
**多租户三断言**在四个服务里各跑一遍（两租户互不可见 / 伪造 `X-Tenant-Id` 无效 /
连接池归还后不串租户）。

> **未跑**：`./mvnw verify` 全量、整个服务的测试、前端生产构建 —— 按车道纪律由调度器在集成头上跑。
> **未改 `api-proxy.ts`**（1.2.1.1 已放行全部检索端点），所以不需要全量 allowlist 回归。

---

## 后续待办

1. **被抑制的命中条数**：若将来要报「其中有 N 条被权限挡住」，需要一条**授权内**的计数口径
   （例如在域权限缺失时用**同一租户**的只读计数投影，且不暴露条数存在性）——
   本模块明确不做，也不编数字。
2. **前缀检索的索引**：沿用 1.2.1.1 的遗留项 —— `code` / `order_no` 列上没有可走前缀的索引，
   数据量上来后各服务需要自己的 `text_pattern_ops` / `lower(col)` 函数索引迁移。
3. **未接通的检索域**（平台刊登 / 平台买家 / 售后单 / 发货与物流单 / 条码）仍按 1.2.1.1 的
   capabilities 逐条标注归属模块 —— 不在本模块职责内。
4. **`trade` 其余端点的写权限码**仍未在 identity 注册（沿用 1.2.1.1 的遗留项）——
   那是 17.1 授权登记面的事。
