# 1.4.1.2 列表与表格偏好（阶段 D7 · L3 锚点 1.4.1）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/1-4-1-2`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/1.4.1.2`）
- **基线**：`integration/module-dev` @ `de6dbb98`
- **迁移版本号**：`V336__identity_user_preference.sql`（预分配）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/01-dashboard-and-portal.md` 第 194-199 行）：

> ### 1.4.1.2 列表与表格偏好
>
> - **职责**：记住各列表页的列显示、排序、分页大小、筛选条件，降低重复操作成本。
> - **输入**：用户在列表页的列显隐/排序/分页操作；`user_preference`。
> - **输出**：下次进入该页时恢复上次的视图状态。
> - **关系**：上游 = 各系统列表页；下游 = 各列表页初始状态。**偏好按页面 key 存储**，新增字段不影响旧偏好。

本车道落地要点：

1. **表设计由「新增字段不影响旧偏好」这句硬约束反推出来。**
   「每种偏好一列」的大宽表直接被这句否决：每加一个偏好字段就要 ALTER 表，
   而老行的默认值语义会被迫重写 —— 老用户下次进页面时被新字段的默认值**静默覆盖**。
   ⇒ 取「一张表 + `page_key` + JSONB 载荷」：一页一行（UNIQUE 三元组 ⇒ 同 page_key 二次写入是覆盖不是新增），
   载荷缺键是**正常**的（老偏好没有新字段），由前端对缺失键套默认值 ⇒ 新增字段零 ALTER、零数据迁移。
2. **`user_preference` 落 identity 服务**（与 V330「最近搜索记录」同一套论证：identity 拥有个人维度数据），
   **本仓第一张个人偏好表**（`grep user_preference` 在本仓迁移目录里零命中）。
3. **租户内的「这个人」是 `membership.id`，不是 `app_user.id`** —— `app_user` 是跨租户全局账号。
   本表用复合外键 `(tenant_id, membership_id) → membership(tenant_id, id)` 把归属钉在**数据层**。
4. **RLS 到租户级 + owner 谓词到人**：`tenant_id NOT NULL` + **ENABLE + FORCE** RLS（FORCE 是必须的，
   否则表 owner 身份能绕过隔离）；「本租户里的这个人」由服务层的 `membership_id` 谓词保证
   （identity 侧没有 `app.actor` 这个 GUC）。
5. **刻意不新登记权限码。** 每一行都是「我自己的」偏好，边界是**数据归属**而不是功能开关。
   另立 `identity.user_preference.*` 只会带来一个坏处：管理员随时会新建角色，
   新角色默认没有这个码 ⇒ 普通成员的列表页偏好静默失效，而界面上看不出任何异常。
   凡是会暴露**别人**数据的入口（全局搜索、操作日志、审批记录…）才需要独立权限码。
6. **敏感值不得进 preference JSON 这条规则落在服务端**（`UserPreferencePayloadRules`，纯函数）：
   敏感**键名**（password / token / idCard / apiKey / secret / credential / bankCardNo …，归一化后按词干匹配，
   `accessToken` 与 `access_token` 一样被拦）与**凭据形状的值**（PEM 头 / JWT / `Bearer …` / `password=…`）一律 400。
   理由：前端会守（自由文本筛选根本不持久化），但**前端是可以被绕过的** ——
   任何人拿自己的 token 就能直接打这个接口。
7. **「读不到偏好 ⇒ 用默认值继续渲染」是硬要求**：读失败、服务不可达、未登录都没有记录，
   都只该让列表页回到默认视图，**绝不能**报错或白屏。
8. **恢复时不写库。** 刚进页面就把默认值存回去，会让「用户从没调过的页面」也留下一行，
   而那一行会**冻结**当时的默认列集 —— 以后这一页加了新列，老用户就再也看不到它了。
   ⇒ 只有**用户真的改了**什么才落库。
9. **「用户隐藏了某列」与「这一列当时还不存在」必须分得开** ⇒ 载荷里除了 `columns`（可见列）
   还存 `columnSet`（保存那一刻这一页已知的全部列）：差集才是用户**显式隐藏**的，新加的列默认可见。
10. **排序键是页面自己定义的不透明 token**（如 `PACKED_AT_DESC`），方向编在 token 里 ——
    因为后端的排序白名单本来就是这种形状（有的维度只有降序，没有升序），
    拆成「字段 + 方向」两个下拉反而会拼出后端不认的组合。
11. **自由文本筛选一律不落库**（关键字 / 姓名 / 备注可能含 PII）：只有页面**显式声明**的枚举与编号才进白名单，
    且值还必须是安全 token。

## 关键 trade-off

| 决策点                                   | 取舍                            | 理由                                                                                                                                                                                                                     |
| ---------------------------------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 载荷：JSONB vs 定长 record vs 大宽表    | **JSONB**                       | 定长 record 只能描述本模块这一种形状，会把 1.4.1.1（工作台布局）逼到另建一张表；大宽表则直接违反「新增字段不影响旧偏好」。JSONB 两个问题都不沾。                                                          |
| 页面 key 放路径段 vs 放 query           | **路径段**                      | 偏好的自然键就是「哪一页」；用 id 寻址要先查列表才知道 id，多绕一层。api-proxy 正则因此用严格小写 slug 词表（本模块**没有** id 段，「id 段用完整 UUID 形状」这条约定在此不适用）         |
| 是否新登记权限码                        | **不登记**                      | 见上文要点 5。边界是数据归属；另立权限码只把「数据边界」错换成「功能开关」，还拿不到任何额外安全性                                                                                                            |
| 「列显示」存可见列 vs 存隐藏列          | **存可见列 + columnSet**        | 只存可见列的话，「用户隐藏了 X」与「X 当��还不存在」分不开，新加的列会对老用户**默认隐藏**。加一个 columnSet 就能精确区分                                                                                             |
| 排序：字段 + 方向 vs 单 token           | **单 token**                    | 后端排序白名单是 `PACKED_AT_DESC` 这种形状，且**有的维度只有降序**；拆成两个下拉会拼出后端不认的组合                                                                                                                  |
| 每页条数：哪些页面可配                  | **只给真正分页的页面**          | 库存风险是一次给全量投影（改 size 会截断数据）、出库时效的每页条数由后端固定 20 —— 这两页**不显示**「每页条数」，而不是给一个改了没用的控件                                                 |

## 文件清单

**迁移（1）**

- `backend/services/identity/src/main/resources/db/migration/V336__identity_user_preference.sql` — 新建 `user_preference` + RLS + GRANT

**后端（4）**

- `backend/services/identity/src/main/java/com/starcon/identity/UserPreferenceModels.java` — View / Input / List
- `backend/services/identity/src/main/java/com/starcon/identity/UserPreferencePayloadRules.java` — 载荷纯函数校验（敏感键 / 凭据值 / 深度 / 体积）
- `backend/services/identity/src/main/java/com/starcon/identity/UserPreferenceService.java` — jOOQ 显式 SQL（upsert / 读 / 删 / 列举）
- `backend/services/identity/src/main/java/com/starcon/identity/UserPreferenceController.java` — 5 个端点，租户与 membership_id 只从会话解析

**后端测试（2）**

- `backend/services/identity/src/test/java/com/starcon/identity/UserPreferenceIntegrationTest.java`（19 例）
- `backend/services/identity/src/test/java/com/starcon/identity/UserPreferenceTenancyIntegrationTest.java`（4 例，三断言实跑）

**前端（5 新 + 1 改）**

- `apps/web/src/lib/list-preferences-model.ts` — 纯模型 + 接口调用
- `apps/web/src/lib/use-list-preferences.ts` — 可复用 hook
- `apps/web/src/lib/list-preferences-model.test.ts` — 25 例
- `apps/web/src/lib/list-preferences-api-proxy.test.ts` — 4 例（白名单对撞）
- `apps/web/src/components/common/list-preference-bar.tsx` — 共用工具条
- `apps/web/src/components/iam/list-preferences-workspace.tsx` + `apps/web/src/app/settings/profile/page.tsx` — 设置入口

**改动的既有文件（6）**

- `apps/web/src/lib/api-proxy.ts` — **纯新增一条 `services[]` 元素**（`prefix: 'identity/'`，锚在 `prefix: 'warehouse/'` 那条元素之前）
- `apps/web/src/components/catalog/catalog-workspace.tsx` — 接入 `catalog.products`
- `apps/web/src/components/orders/pending-shipment-workspace.tsx` — 接入 `orders.pending-shipment`
- `apps/web/src/components/orders/shipment-records-workspace.tsx` — 接入 `orders.shipment-records`
- `apps/web/src/components/dashboard/inventory-risk-workspace.tsx` — 接入 `inventory.risk`
- `apps/web/src/components/warehouse/warehouse-outbound-timeliness-workspace.tsx` — 接入 `warehouse.outbound-timeliness`
- `apps/web/src/components/iam/identity-workspace.tsx` — 设置入口卡片

**契约与台账（2）**

- `packages/api-contracts/src/contracts/identity.ts` + `packages/api-contracts/src/identity.openapi.json` — codegen 产物（禁手改）
- `docs/product/capability-blueprint/module-progress.md` — 只改 1.4.1.2 那一行的「状态」「证据」两格

## 接入的列表工作区

| 页面 key                              | 工作区组件                                                                | 列显示 | 排序 | 每页条数 | 筛选条件                                            |
| ------------------------------------- | ------------------------------------------------------------------------- | :----: | :--: | :------: | --------------------------------------------------- |
| `catalog.products`                    | `components/catalog/catalog-workspace.tsx`                                 |   ✅   |  —   |    —     | —（搜索是自由文本，不落库）                          |
| `orders.pending-shipment`             | `components/orders/pending-shipment-workspace.tsx`                        |   ✅   |  ✅  |    ✅    | groupBy / carrierCode                                 |
| `orders.shipment-records`             | `components/orders/shipment-records-workspace.tsx`                        |   ✅   |  ✅  |    ✅    | groupBy / carrierCode / trackingStatus / platformCallbackStatus |
| `inventory.risk`                      | `components/dashboard/inventory-risk-workspace.tsx`                       |   ✅   |  —   |    —     | warehouseKind / warehouseId / riskFilter              |
| `warehouse.outbound-timeliness`       | `components/warehouse/warehouse-outbound-timeliness-workspace.tsx`        |   ✅   |  —   |    —     | from / to / warehouseId                               |

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q compile                                    # 通过
cd <worktree> && pnpm typecheck                                     # 3 packages successful

# L1 模块闸（只跑本模块）
cd backend && ./mvnw -pl services/identity -am test \
    -Dtest='UserPreference*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# Tests run: 4  (UserPreferenceTenancyIntegrationTest)   Failures: 0
# Tests run: 19 (UserPreferenceIntegrationTest)          Failures: 0
# Tests run: 23                                          BUILD SUCCESS

# 前端（只跑本模块新增的测试文件）
pnpm vitest run apps/web/src/lib/list-preferences-model.test.ts \
                apps/web/src/lib/list-preferences-api-proxy.test.ts
# Test Files 2 passed · Tests 29 passed

# 前端全量 allowlist 回归（因为改了 api-proxy.ts —— 唯一允许扩大范围的情况）
pnpm vitest run apps/web/src/app/api
# Test Files 206 passed · Tests 1195 passed

pnpm lint                                                        # 3 successful（只剩既有 warning）
```

**契约重新生成**：`./mvnw -pl services/{identity,catalog,channel,warehouse,trade,crm} -am test -Dtest='OpenApiContractTest'`
（6 个服务各导出 1 份 snapshot）→ `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
（Generated 6060 DTOs in 6 per-service files）。

## 后续待办

1. **1.4.1.1 工作台布局**（同 L3，本轮未交付）复用**同一张 `user_preference` 表**，
   用它自己的 page_key（例如 `dashboard.layout`）存自己的载荷形状 —— **不需要** ALTER 本表，也不需要数据迁移。
2. **1.4.2.1 语言与时区 / 1.4.2.2 默认店铺与仓库** 将来若也要走个人偏好存储，同样复用这张表。
3. 偏好载荷目前**没有**「列顺序」的独立表达（存的是可见列的顺序），
   如果将来出现「只调顺序不调显隐」的需求，需要在载荷里补一个字段而不是改表结构。
4. `page_key` 词表目前到 160 字符；真的出现需要更长的 key 时，应该改**词表与 CHECK**（一次 ALTER），
   而不是放宽到任意字符 —— 放宽会让它不能再安全地当 URL 路径段用。
