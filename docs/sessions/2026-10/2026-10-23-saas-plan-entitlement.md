# 17.7.1.1 套餐与权益（阶段 D7 · L3 锚点 17.7.1）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/17-7-1-1`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/17.7.1.1`）
- **基线**：`integration` @ `f8733478`
- **迁移版本号**：`V323__saas_plan_entitlement.sql`（预分配，跳号正常）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/17-org-permission-and-settings.md` 第 271-276 行）：

> ### 17.7.1.1 套餐与权益
>
> - **职责**：starcon **自身**的 SaaS 商业化：套餐定义与权益项（店铺数、SKU 数、订单量、用户数、API 调用量、AI 用量）、订阅状态、试用与到期处理、超限策略。**仅平台管理员可见，租户不可访问**——避免与租户自身的采购/应付业务混淆。
> - **输入**：套餐配置；订阅与续费事件；权益校验结果。
> - **输出**：订阅状态；权益用量视图；超限降级/阻断策略。
> - **关系**：上游 = 平台运营；下游 = 各系统功能开关（超限降级）。**与 ERP 客户的销售收入完全无关**（实体目录 §8 已说明"独立于 ERP 客户的销售收入，暂不实施"），实现时需与业务库物理隔离。

本车道落地要点：

1. **套餐定义是平台级事实，订阅是租户级事实——两层物理隔离。**
   套餐与权益定义（`saas_plan` / `saas_plan_entitlement`）**不带 `tenant_id`、不参与 RLS、只 `GRANT SELECT TO identity_app`、零外键指向租户业务表**；
   订阅、用量、变更留痕（`saas_subscription` / `saas_entitlement_usage` / `saas_subscription_event`）**`tenant_id NOT NULL` + `RLS FORCE isolation`**。
   蓝图要求的「与业务库物理隔离」由此落成：两层不在同一张表、不在同一套授权面、不在同一套 RLS 策略里。
2. **平台级表的写入只走 SECURITY DEFINER 专用函数。**
   应用角色对 `saas_plan` / `saas_plan_entitlement` **只有 SELECT** ⇒ **租户结构上无法自升级**（不靠权限码把关，靠授权面把关）。
3. **「仅平台管理员可见」用三道闸门同时把守（Java + DB 双重）。**
   ① 本租户 `saas_platform_role` 有登记；② 登记的 `role_id` 正是会话持有的角色且 `scope_mode='ALL'`；③ 该角色带有所需权限码
   （`identity.plan.{read,create,update}` / `identity.subscription.{read,create,update}`）。
   权限码**刻意不授 OWNER**（与 V53 数据字典先例相反）。
4. **平台运营角色本身是普通租户级角色**（默认码 `PLATFORM_OPS`），经 `POST /identity/saas-platform/roles`（需 `X-Internal-Key`，**刻意不进浏览器 allowlist**）在会话所在租户内建角色 + 授权 + 登记 + 自分配。
   **没有另造全局超级角色** —— 那等于造出第二套身份体系，且与 identity 服务的租户模型打架。
5. **没给租户开任何读端点。** 蓝图明写「租户不可访问」，所以本模块**一个租户侧读口都不给**：
   普通租户即使经 `create_child` 拿到了 `identity.plan.*` 权限码，也照样 403（集成测试实跑断言）。
6. **不落任何金额。** 没有发票 / 应收 / 付款 / 结算 / 收入确认；权益上限与用量都是**数量**（`NUMERIC(19,6)`）。
   测试用 `information_schema` 断言 `saas_*` 表无金额语义列 —— 数字对但语义错（把「月订单量」写成金额）是最难发现的错。
7. **计量未接通就如实说判不了。** `available=false` ⇒ `usage_value` 必须为 NULL（DB CHECK 钉死，填 0 会被误读成「量过了正好是 0」），
   且强制带 `owner_module` + `impact`；判定结果是 `UNDETERMINED`（「判定不了」≠「没超限」），能力声明如实报 3 条 `available=false`。
8. **试用与到期是真推导，不是状态字段。** 试用起止按值往返零漂移；到期由「当前时刻 vs `expires_at`」推导，可批量处理（`expire-due`），状态流转走白名单。

## 关键 trade-off

| 决策点                                                                | 取舍                                                                               | 理由                                                                                                                                                                                                                                         |
| --------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 套餐定义：租户级 vs **平台级**                                        | **平台级**（不带 `tenant_id`、不参与 RLS）                                         | 套餐是「一个平台一套」的事实。做成租户级会让每个租户各存一份套餐定义，改一次定价要改 N 行，且跨租户看板无从判断「这俩租户的套餐是不是同一套」                                                                                                |
| 平台级表授权：读写都给 vs **只给 SELECT**                             | **只给 SELECT**（写入走 SECURITY DEFINER）                                         | 只给 SELECT ⇒ 租户**结构上**无法改套餐，不依赖「权限码配得对不对」这种易错的手工配置                                                                                                                                                         |
| 平台管理员身份：**全局超级角色** vs **普通租户级角色 + 登记**         | **后者**                                                                           | 全局超级角色会与 identity 已有的租户 / 角色 / 成员模型形成第二套身份体系。普通租户级角色 + `saas_platform_role` 登记表，既复用既有角色授权能力，又能用 `scope_mode='ALL'` 把跨租户读写卡死                                                   |
| 权限码是否授 OWNER                                                    | **不授**（与 V53 先例相反）                                                        | OWNER 是「本租户内什么都能干」的语义。把它绑到平台运营，等于任何租户 OWNER 都可能拿到跨租户读套餐的权限 —— 与「租户不可访问」直接冲突                                                                                                        |
| 租户侧是否开只读端点                                                  | **一个都不开**                                                                     | 蓝图明写「租户不可访问」。开只读口看着温和，实际会让「租户能看见自己的套餐」变成默认预期，后续加写口只是顺理成章 —— 边界一破就守不住                                                                                                         |
| 用量计量没接通：`available=false` 时 usage_value 填 **0** vs **NULL** | **NULL**（DB CHECK 钉死）                                                          | 0 会被读成「用量正好是 0」。「没接通计量」与「用量为零」是两件事，只有 NULL 表达得了                                                                                                                                                         |
| 未接通计量的判定结论：**当作没超限** vs **UNDETERMINED**              | **UNDETERMINED**                                                                   | 下游功能开关据此决定是否降级/阻断。判错方向就是线上误拦或误放 —— 宁可判不了也不能猜                                                                                                                                                          |
| 权益上限：存**数量** vs 存金额                                        | **数量**                                                                           | 与 ERP 销售收入完全无关是蓝图的硬边界。落金额会把这块和 12 财务域搅在一起，正是蓝图要避免的混淆                                                                                                                                              |
| 订阅 / 用量是否给 DELETE                                              | **不给**                                                                           | 订阅与用量是事实；撤销走状态流转（`CANCELLED` / `EXPIRED`），留痕不可改                                                                                                                                                                      |
| 跨租户读订阅看板的路径                                                | **只有一条**（`saas_platform_subscription_list`，SECURITY DEFINER 且自己再判闸门） | 多条路就多一处能漏判闸门的地方。单条订阅读写先由 `saas_platform_subscription_tenant` 定出目标租户，再在该租户 RLS 上下文里操作（ADR-0005「每次业务事务选定一个明确目标租户」）                                                               |
| 前端文件位置：`components/plans/` vs **`components/iam/`**            | **`components/iam/`**                                                              | 仓内既有惯例（`ledger-book-*` / `approval-record-*` / `dictionary-*` 等）都把同族工作区放在 `components/iam/`。另起目录会与既有导航、命名、测试摆放全部错位                                                                                  |
| 权益周期的展示口径：内联在组件 vs **收敛到 model 层**                 | **收敛到 model 层**（`usageSummary(usage: UsageView)`）                            | 「把哪个字段接成周期」是最容易被写坏的一步 —— 内联映射时很容易顺手写成 `period: null`，于是「每月 3000 单」被渲染成没有周期的「3000 / 3000」：数字对了，语义丢了。收敛到 model 层后用契约类型锁死字段来源，单测能直接拿真实 `UsageView` 断言 |

## 文件清单

### 后端

- `backend/services/identity/src/main/resources/db/migration/V323__saas_plan_entitlement.sql` — 迁移（平台级表 / 租户级表 / SECURITY DEFINER 函数组 / 出厂套餐与权益项种子）
- `backend/services/identity/src/main/java/com/starcon/identity/SaasPlanController.java` — 端点（20 个：`@RequestMapping("/identity")` 下 19 个 `/saas-plans/**` + 1 个 `/saas-platform/roles`）
- `backend/services/identity/src/test/java/com/starcon/identity/SaasPlanIntegrationTest.java` — 12 例集成测试

### 前端

- `apps/web/src/app/org/plans/page.tsx` — 页面入口
- `apps/web/src/components/iam/saas-plan-model.ts` — 纯函数模型（展示口径 + 前端预校验）
- `apps/web/src/components/iam/saas-plan-workspace.tsx` — 工作区组件（加载态 / 读不到态 / 出错态 / 空态四态文案各不相同）
- `apps/web/src/components/iam/saas-plan-model.test.ts` — 28 例单测
- `apps/web/src/app/api/iam/[...path]/saas-plan-allowlist.test.ts` — 5 例放行双向断言
- `apps/web/src/lib/api-proxy.ts` — **纯增量**新增一条独立 `services[]` 元素（`prefix: 'identity/'`），锚点固定在 `// 2.4.2.3 映射缺失队列（channel 服务）` 之前，既有条目一字未改

### 契约（codegen 产物，只读）

- `packages/api-contracts/src/contracts/identity.ts`
- `packages/api-contracts/src/identity.openapi.json`

## 验证结果

| 层级 | 命令                                                                           | 结果                                       |
| ---- | ------------------------------------------------------------------------------ | ------------------------------------------ |
| L0   | `backend/./mvnw -q -o compile`                                                 | 通过                                       |
| L0   | `pnpm typecheck`                                                               | 3/3 通过                                   |
| L0   | `pnpm lint`                                                                    | 3/3 通过（warning 全在他车道既有文件）     |
| L0   | `npx prettier --check`（本车道文件）                                           | 全部符合                                   |
| L1   | `./mvnw -o -pl services/identity -am test -Dtest='SaasPlan*'`                  | **Tests run: 12, Failures: 0, Errors: 0**  |
| L1   | `pnpm vitest run`（本车道两个前端测试文件）                                    | **2 files / 33 tests 全绿**                |
| L1   | `pnpm vitest run apps/web/src/app/api/trade`（api-proxy 全量 allowlist 回归）  | **68 files / 379 tests 全绿**              |
| 契约 | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` | Generated 5913 DTOs in 6 per-service files |
| 台账 | `node tools/module-progress/sync.mjs --check`                                  | ✓ 台账与蓝图一致                           |

集成测试覆盖的关键断言：

- 普通租户即使带 `identity.plan.*` 权限码也 403
- 平台级表不带 `tenant_id` / 不参与 RLS / 无金额语义列 / 数量列为 `NUMERIC(19,6)`
- 试用起止时间**按值往返**零漂移（`OffsetDateTime` + `?::timestamptz`）
- 到期由时刻推导并可批量处理
- `UNDETERMINED` 口径：判定不了 ≠ 没超限，`usageValue` 必须为 null
- 超限判定四态（BLOCK / DEGRADE / ALLOW / 不限量）
- **多租户三断言**（两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户）
- 变更留痕不可篡改
- 能力声明如实标注 3 条 `available=false`

## 排障教训（勿重犯）

1. `identity_app` **没有** `tenant` 表的直读权限 ⇒ 应用侧直查会得 `42501`（表现为 403），必须走 `saas_platform_tenant_info`。
2. jOOQ 解析裸函数调用 `select func(...)` 不知道返回列 ⇒ 必须写 `select * from func(...)`。
3. PL/pgSQL `RETURN QUERY` 会把结果列名换成查询列名 ⇒ 必须显式 `AS` 别名。
4. PL/pgSQL `RETURNS TABLE` 与查询结果类型必须**逐字一致**（`varchar(64)` ≠ `text`）。
5. `saas_subscription` 的试用 CHECK 不能写成双向等值（否则试用转正式被 CHECK 拒）⇒ 改为 `status <> 'TRIAL' OR (trial_start/trial_end 均非空)`。
6. `AuditLog` 用无 JavaTimeModule 的 ObjectMapper ⇒ 审计快照里的时间必须转 ISO 字符串，否则「审计内容序列化失败」。
7. `ApiErrorHandler` 把任何非 42501 的 DataAccessException 映射成 409、42501 映射成 403 ⇒ 排障时不要只看状态码。
8. **台账证据格里不能出现竖线 `|`。** `sync.mjs` 用 `[^|\n]*` 切单元格，证据里的竖线会把这一格**静默截断**
   （写 `saas_platform_create|update_plan` 这种「或」的连写就会中招），而脚本照样报「同步成功」——
   看起来一切正常，证据却只剩前半截。写完证据后 `sync.mjs --check` 通过**不等于**证据完整，要另看一次行尾。

## 后续待办

1. **迁移到 `apps/admin`** —— 本模块是平台运营域，页面最终应落在内部运营后台而不是租户客户端 `apps/web`。本车道按仓内既有惯例先放 `apps/web/src/app/org/plans/page.tsx` + `components/iam/`，迁到 `apps/admin` 是后续独立工作项。
2. **用量计量未接通**（17.7.1.2）—— 本模块的 `UNDETERMINED` 会随 17.7.1.2 落地而收敛为真实判定。
3. **平台参数与开关未接通**（17.7.2.1）—— 超限降级/阻断策略的**执行方**在那边；本模块只产出判定结论，不执行拦截。
4. **实体目录 §8 计费与收入确认** —— 仍不实施（与 ERP 销售收入完全无关的边界继续保持）。

## 顺带发现（不属于本车道，未修）

现有 `identity/` allowlist 的巨型 alternation **未包含 `dict-items` / `dict-catalog`** ⇒ 17.5.2.1 数据字典页面在浏览器里会 404。
修它必须改那条共享 alternation（调度者明令禁止本车道触碰），故仅记录在此，留给对应车道处理。
