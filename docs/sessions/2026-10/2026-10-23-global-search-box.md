# 1.2.1.1 全局搜索框（⌘K）—— 卷 01 工作台与门户

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`1.2.1.1`（worktree `integration/.worktrees/1.2.1.1`，分支 `feat/1-2-1-1`）
- **基线**：`integration/module-dev`
- **蓝图**：卷 01「工作台与门户系统」L3 1.2.1 统一搜索 → L4 1.2.1.1 全局搜索框（锚点 ⌘K）

---

## 这个功能做什么

一个输入框，跨系统检索 SKU、SPU、订单号、采购单号、店名、供应商、物流单号、客户，
返回**分组结果**并直接跳到对应详情页 / 列表页；⌘K 随时唤起，最近搜过的东西在框里可回。

---

## 关键决策摘要

### 1. 检索端点落在**五个**服务里，合并在**前端**做

蓝图的「后端归属 = 前端聚合层 + 各服务的只读投影（不新增业务服务）」与「共享数据 = 跨服务投影索引」，
在本仓的现实里是：**没有统一的检索索引表，也没有 unified search 服务**。

所以做法是：每个域的检索端点落在**该表的归属服务**里（商品/客户/供应商 → catalog，
订单/采购单 → trade，店铺 → channel，仓库/物流 → warehouse，最近搜索 → identity），
前端并发打四个服务后合并。

**为什么不是一个聚合后端**：聚合服务要么跨库查别人的表（AGENTS.md 明令禁止），
要么新起一个业务服务（蓝图写死不新增）。四个只读端点 + 前端并发，是唯一不越界的形状。

**一个服务挂掉不影响其余域**：前端用 `Promise.allSettled` 语义，失败的服务进 `failedServices`，
其余域照常返回。界面必须把「没权限的域」（`unavailableDomains`）与「失败的域」分开说 ——
混为一谈，运营会把服务故障读成「这个租户没有订单」。

### 2. 客户与供应商落在 **catalog**，不是 crm

任务书里写的是「客户在 crm」，实查后不成立：`crm_customer_contact.customer_ref` 的迁移注释写死
「本仓**没有买家档案**（15.3.1.1 客户列表未交付）⇒ customer_ref 只是逻辑引用，不是主数据」。

客户与供应商的**权威主数据**是 3.6.1.1 的 `party` + `party_role`（角色 CUSTOMER / SUPPLIER），
在 catalog 的库里；trade 侧的采购单只存 `supplier_ref_id` 逻辑引用与 `supplier_name` 快照。
拿快照当主数据搜，会搜到一份可能已改名的副本 —— 那是语义造假。

⇒ 客户 / 供应商检索端点落在 catalog；crm 侧只有接触记录，不进检索口径。

### 3. 匹配方式：只做**前缀**（`ilike 'kw%'`）

蓝图写死「只做前缀/精确匹配，不做全文语义检索；模糊搜索在各自系统列表内完成」。
所以不上 trigram / 全文索引 / ES。测试里有一条专门的断言守着它：
搜 `SKU-AX` 命中 `SKU-AX-001`，搜 `X-001` **命中不到**。

> ⚠️ 遗留：前缀查询在 `code` / `order_no` 这些列上**没有可走的索引**
> （既有唯一索引是默认操作符的 btree，只服务等值）。本仓是多租户，扫描被 RLS 收敛在本租户内，
> 当前数据量下可接受。数据量上来后应加 `text_pattern_ops` 或 `lower(col)` 函数索引 ——
> 那需要各服务自己的迁移，本车道没有在那些服务里建表，故未做。

### 4. 「最近搜索记录」落 **identity**，用 **V330** 建表

它是**纯个人偏好**（不是业务事实），按模块归属属于 1.4 个人设置同域 → identity。
不塞进任何业务服务，也不只写在前端 localStorage（localStorage 只能当缓存层，
权威副本必须在后端，否则换台设备最近搜索就空了）。

`global_search_recent`：`tenant_id` + `user_id` + `keyword`（**一人一词一行**，重复搜只刷新时刻）
+ `domain` + `primary_ref` + `searched_at`；ENABLE/FORCE RLS isolation + GRANT identity_app。

**登记时机是「用户选中一条」而不是「每次键入」** —— 否则最近搜索会退化成击键历史，
而不是「最近找过的东西」。

### 5. 同迁移补登记 `trade.orders.read` / `trade.purchase.read`（既有缺口的修补）

`trade` 自己的端点（9.1.1.1 订单列表、6.3.2.1 采购单列表）早就在
`access.require(bearer, "trade.orders.read")`，但 identity 的 `permission` 表里
**从来没有过 trade.* 任何一行**，而 `role_permission.permission_code` 有指向 `permission(code)` 的外键
⇒ 没有任何角色可能持有这两个码 ⇒ 那两个端点在生产上恒 403。

不补登记，本模块的订单与采购两个域就**永远是空的**（域权限用各域自己的码，这是防越权的必要条件）。
所以 V330 一并登记这两个**读**码（刻意只登记 read：没有写端点的权限码不登记，与 identity V104 同纪律），
顺带把那两个既有端点修回可达。副作用已写进台账证据与本记录。

### 6. 数据范围照抄各服务既有口径，**不另立一套**

| 服务 | 范围机制 |
| --- | --- |
| catalog | 既有 RLS `data_scope` 策略（`catalog_scope(permission, owner, dept)`）—— 每个域用**各自的权限码**开事务（`app.permission` 是事务级 GUC，一个事务里只能有一个值） |
| channel | 同构（`channel_scope`） |
| trade | 照 9.1.1.1 / 6.3.2.1：ALL / DEPARTMENT / SELF。`sales_order` 无部门列 ⇒ DEPARTMENT 退化为 SELF（落在 `sales_order_status_history.actor_id`）；`purchase_order` 落在 `owner_user_id` / `owner_department_id` |
| warehouse | 租户级（表上没有 owner / department 列，本服务不造范围算法） |

判定逻辑抽到 `backend/libraries/tenancy` 的 `GrantPermissions`（新增类，不是改既有文件），
与各服务 `AccessService.require` **逐字同构**（同一 grant 里必须同时有动作权限与对应 `.read`）。

### 7. 前端：⌘K 面板 + 独立页，同一套逻辑

- `packages/ui` 的 `CommandPalette` 与 `AppShell` 各加**可选**属性
  （`onQueryChange` / `statusText` / `commandPalette` 绑定）。**不注入时行为与从前逐字一致** ——
  这是纯增量接线，并行车道不会因为它撞车。
- `AppShell` 里那个**占位**的「业务对象」组（`disabled: true` + 「业务数据接入后启用」）
  在注入后由真实检索结果替换 —— 占位组留着就是两个真相。
- 注入内容要进 `AppShell`（父），状态在 `GlobalSearchProvider`（子），
  所以用 **render prop** 而不是 Context（Context 只向下传）。
- 独立页 `/dashboard/search`：同一个输入框、同一套分组、同一批三态。
  ⌘K 是主入口，这一页是「可分享 / 可开标签页」的落点（最近搜索点进来时带 `?q=`）。

### 8. 命中回填

订单跳详情页 `/orders/{id}`（9.1.1.2 订单控制塔）；
其余域跳对应列表页并把主标识**回填**到关键字筛选器
（`apps/web/src/lib/global-search-handoff.ts` 的 `usePrefillKeyword`，
已在采购单 / 往来单位 / 店铺 / 仓库档案 / SKU 列表五个既有筛选器上接上）。

> 刻意**不用 `useSearchParams()`**：那会让整条路由放弃静态预渲染，
> 给 Next 的 production build 引入新的 `Suspense` 边界要求（本仓现有页面一个都没用它）。

### 9. api-proxy：五条**独立**的 `services[]` 元素，锚在数组末尾

`services[]` 每条元素只带一个 prefix，而本模块跨五个服务 ⇒ 五条。
既有 179+ 条**一字未改、也不重排**。因为动了这个文件，
按纪律跑了**全量 allowlist 回归**（200 个测试文件 / 1151 例全绿），另加本模块自己的
`global-search-allowlist.test.ts`（4 例：放行 / 转发目标端口 / 刻意不放行的形状仍 404 / 不误伤相邻车道）。

---

## 文件清单

### 后端（新增）

- `backend/libraries/tenancy/src/main/java/com/starcon/tenancy/GrantPermissions.java`
- `backend/services/identity/src/main/resources/db/migration/V330__global_search_recent.sql`
- `backend/services/identity/src/main/java/com/starcon/identity/GlobalSearchRecent{Controller,Models,Service}.java`
- `backend/services/identity/src/test/java/com/starcon/identity/GlobalSearchRecentIntegrationTest.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/GlobalSearch{Controller,Models,Service}.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/GlobalSearchIntegrationTest.java`
- `backend/services/trade/src/main/java/com/starcon/trade/GlobalSearch{Controller,Models,Service}.java`
- `backend/services/trade/src/test/java/com/starcon/trade/GlobalSearchIntegrationTest.java`
- `backend/services/channel/src/main/java/com/starcon/channel/GlobalSearch{Controller,Models,Service}.java`
- `backend/services/channel/src/test/java/com/starcon/channel/GlobalSearchIntegrationTest.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/GlobalSearch{Controller,Models,Service}.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/GlobalSearchIntegrationTest.java`

### 前端（新增）

- `apps/web/src/components/dashboard/global-search-model.ts` + `global-search-model.test.ts`
- `apps/web/src/components/dashboard/global-search-client.ts`
- `apps/web/src/components/dashboard/global-search-workspace.tsx`
- `apps/web/src/app/dashboard/search/page.tsx`
- `apps/web/src/lib/global-search-handoff.ts`
- `apps/web/src/app/api/global-search-allowlist.test.ts`

### 改动（既有文件）

- `packages/ui/src/components/common/command-palette.tsx`（+2 个可选属性）
- `packages/ui/src/components/layout/app-shell.tsx`（+1 个可选属性）
- `apps/web/src/components/iam/identity-shell.tsx`（接线 ⌘K 注入）
- `apps/web/src/lib/api-proxy.ts`（+5 条 `services[]` 元素，锚在数组末尾）
- 五个目标列表页工作区（各 +1 行 `usePrefillKeyword()`）：采购单 / 往来单位 / 店铺 / 仓库档案 / SKU 列表
- `packages/api-contracts/**`（codegen 产物，五个服务各 +5 个 `GlobalSearch*` 类型）
- `docs/product/capability-blueprint/module-progress.md`（**只改本模块那一行**）

---

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 后端编译 | `./mvnw -q compile` | 通过 |
| L0 前端类型 | `pnpm typecheck` | 通过（3 包全绿） |
| L1 catalog | `./mvnw -pl services/catalog -am test -Dtest='GlobalSearch*' …` | **9 例全绿** |
| L1 trade | 同上 `-pl services/trade` | **11 例全绿** |
| L1 channel | 同上 `-pl services/channel` | **7 例全绿** |
| L1 warehouse | 同上 `-pl services/warehouse` | **9 例全绿** |
| L1 identity | 同上 `-pl services/identity` | **11 例全绿** |
| 前端单测 | `pnpm vitest run apps/web/src/components/dashboard/global-search-model.test.ts` | **14 例全绿** |
| api-proxy 全量回归 | `pnpm vitest run apps/web/src/app/api` | **200 文件 / 1151 例全绿** |
| lint | `pnpm lint` | 通过（本模块无告警） |

集成测试实跑 Testcontainers PostgreSQL 16，连接池压到 1：
**多租户三断言**在五个服务里各跑一遍（两租户互不可见 / 伪造 `X-Tenant-Id` 无效且写入仍落会话租户名下 /
连接池归还后不串租户）。

---

## 后续待办

1. **1.2.1.2 搜索结果分组**：上游是本模块的命中集；本模块已经把「域」这一层准备好
   （`domain` / `kind` / 主标识 / 副信息 / 目标路由），分组规则与排序策略留给它。
2. **未接通的检索域**（已在 capabilities 里逐条给出归属模块）：
   平台刊登（04）、平台买家（02 买家域）、售后单（11）、发货与物流单（09.3 履约请求未交付）、
   条码（03.2.x）。
3. **前缀查询的索引**：见上文「匹配方式」小节 —— 数据量上来后各服务需要自己的
   `text_pattern_ops` / `lower(col)` 函数索引迁移。
4. **`identity` 的 trade 权限码**：V330 只补了 `trade.orders.read` / `trade.purchase.read` 两个**读**码。
   trade 其余端点要的写码（`trade.orders.create` / `trade.purchase.update` 等）仍未登记 ——
   那是 identity 授权登记面的另一件事（与 17.1 角色配置同域），不在本模块职责内。
