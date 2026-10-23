# batch-72 · 关联风险 / 数据出境与留存 / 套餐与权益 / 平台参数与开关（并入 + 派发轮）

- **日期**：2026-10-23（Asia/Shanghai）
- **集成头**：`integration/module-dev`
- **批次号**：72（接 batch-71 往后排）
- **本轮模式**：高强度（`MODE=full`，`LANES=4`，`THROTTLE_MIN=50`）—— 当前时刻 2026-10-23 08:00 < `TOKEN_HORIZON=2026-10-25T00:00:00+0800`
- **本轮并入**：5 条 ready 车道（4 个新交付模块 + 1 条纯前端修复车道），无冲突
- **本轮派发**：2 条车道（17.7.1.2 用量统计 / 17.7.2.2 平台运维视图）—— `plan --lanes 4` 只能排出 2 条，其余被服务缺口挡住（见下）

---

## 批次总览

| 模块 ID | 名称 | 阶段 | 服务 | 迁移 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 16.5.2.2 | 关联风险 | D7 | channel | `V321__linkage_risk.sql` | 已交付 |
| 16.6.2.1 | 数据出境与留存 | D7 | channel | `V322__data_governance_retention.sql` | 已交付 |
| 17.7.1.1 | 套餐与权益 | D7 | identity | `V323__saas_plan_entitlement.sql` | 已交付 |
| 17.7.2.1 | 平台参数与开关 | D7 | identity | `V324__platform_feature_flags.sql` | 已交付 |
| 17.5.2.1 | 数据字典（api-proxy 白名单修复车道） | D7 | identity | 无（纯前端） | 已交付（修复） |

台账变化：**340 已交付 → 344 已交付**，未开发 125 → 121，完成率 73.0% → **73.98%**（344/465）。
台账完整性核对：`465` 行、重复模块 ID `0`。

---

## 模块 16.5.2.2 关联风险

**这个功能做什么**：租户把自己名下多家店铺的「共享因子」（法人、设备、IP、地址、支付方式、收款账户）登记进来，
系统据此把存在关联关系的店铺聚成「关联组」，算出一个**可解释的**严重度，并给出处置提示与整改建议——
用于识别「同一主体开了多家店」这类平台封号风险。

**技术落地**：

- 迁移：`V321__linkage_risk.sql` — **纯新建 9 张表、零 ALTER 任何既有表、零跨服务外键**，命名空间 `linkage_*`：
  `linkage_policy`（一租户一条策略，阈值**不给默认值**）/ `linkage_policy_dimension`（逐维度口径 + 平台政策依据）/
  `linkage_identifier` / `linkage_shop_identifier` / `linkage_cluster`（物化当前态，`cluster_key` 由成员集合**确定性 sha256** 算出）/
  `linkage_cluster_shop` / `linkage_alert`（严重度 CHECK 钉死 + `dedup_key` 去重）/ `linkage_alert_action`（**append-only**）/
  `linkage_remediation`。
  九张表全部 `tenant_id uuid NOT NULL` + **ENABLE/FORCE RLS isolation**。
- 端点：`/channel/linkage-risk/**` **19 个映射端点**（`LinkageRiskController`）—— 能力声明 / 策略 GET+PUT / 店铺列表（真分页真搜索）/
  关联标识 CRUD / 标识↔店铺增删 / 图谱 / 扫描 / 提示列表与处置 / 整改建议。
- 页面：`apps/web/src/app/compliance/linkage-risk/page.tsx` + `apps/web/src/components/linkage-risk/` 三件套
  （model / workspace / model.test）。
- 测试：`LinkageRiskIntegrationTest`（20 例）+ `LinkageRiskTenancyIntegrationTest`（3 例，三断言实跑 PG16）+
  `LinkageRiskRulesTest`（10 例）= **33 例全绿**；前端 model 17 例 + allowlist 7 例 = **24 例全绿**。

**关键设计决策**：

1. **法人维度不另建登记面** —— 事实来自 channel 真实表 `shop.legal_entity_id`（自动发现）。
   另建一份法人登记 = 造出第二份法人真相，必然与 identity 打架。
2. **设备 / IP / 地址三个维度自持登记面，但能力声明如实标注「可登记但不自动发现」** ——
   本仓 channel 侧没有对应上游表。落 PostgreSQL、走 jOOQ 显式 SQL、登记必须过店铺存在性校验，
   **不是硬编码数组当数据源**。
3. **支付方式 / 收款账户两个维度直接不建** —— 12.7.1 资金账户未开发且卷 12 主责服务 finance 不在集成头，
   于是 `available=false` + `ownerModule=12.7.1.1`，**值域里根本没有这两个值**，
   DB 守卫触发器 `guard_linkage_dimension_not_finance_owned` 连绕过服务层的写入都拒。
   两份资金账户真相必然打架。
4. **关联组 = 连通分量**（A↔B 共享设备、B↔C 共享地址 ⇒ A/B/C 是一组），`cluster_key` 确定性 ⇒ 重扫**更新**既有提示而不是刷出重复提示。
5. **fail-closed 守卫**：维度必须先在策略里启用并写明政策依据才允许登记，否则会出现「事实永远不参与判定、运营却以为已经在管」。
6. **值域里没有「浏览器指纹 / 模拟登录」**，模块不生成、不采集任何设备指纹；整改建议措辞固定为「用官方 API 授权把店铺分开」。
7. **读不到 ≠ 没有**：没有策略 ⇒ 图谱 `known=false` + 扫描直接 **422**（系统不给默认阈值）；
   法人名称读不到 ⇒ `legalEntityNameAvailable=false` + `null`，**不返回空字符串冒充「这个法人没有名字」**。

---

## 模块 16.6.2.1 数据出境与留存

**这个功能做什么**：平台侧管理「数据出境」（数据发往哪个国家/地区、依据是什么）与「数据留存」（各类数据留多久、到期怎么清），
产出可审计的出境登记与留存策略，并把到期清理拆成**工单**交给下游执行。

**技术落地**：

- 迁移：`V322__data_governance_retention.sql` — **7 张表** + 引用完整性触发器 + append-only 触发器 + ENABLE/FORCE RLS + GRANT/REVOKE。
- 端点：`/channel/data-governance` **22 个映射端点 / 17 条路径**（`DataGovernanceController`）——
  出境登记 / 留存策略 / 分类法定期限 / 清理工单（`POST /cleanup-orders/{id}/execute` 回报执行结果）。
- 页面：`apps/web/src/app/compliance/data-retention/page.tsx` + `apps/web/src/components/data-retention/` 三件套。
- 测试：`DataGovernanceIntegrationTest`（23 例）+ `DataGovernanceTenancyIntegrationTest`（4 例，三断言）+
  `DataGovernanceRulesTest`（11 例）= **38 例 0 失败 0 错误**；前端 model 17 例。

**关键设计决策**：

1. **裁决逻辑抽成纯函数**（`DataGovernanceRules`）—— 留存/出境的判定规则与存储解耦，
   规则能被独立单测覆盖（11 例纯函数测试），不靠跑数据库才能验证分支。
2. **引用完整性靠触发器，不靠应用层** —— 删一个还被引用的留存分类必须被数据库挡下，
   否则「清干净了」只是应用层的乐观假设。
3. **清理动作拆成工单（`retention_cleanup_work_order`）而不是直接物理删除** ——
   本仓没有执行清理的 worker；工单把「谁来删、删了什么、结果如何」变成可追责的事实，
   下游服务消费工单后经 `POST /cleanup-orders/{id}/execute` 回报。
4. **追加不可改的历史表** —— 合规审计轨迹不允许 UPDATE/DELETE，触发器 + REVOKE 双重。
5. **财务留存期目前只是本地策略声明** —— `retention_class = FINANCIAL` 的逐类法定期限需要 12 财务（finance 服务）落地后才能接进来，
   不假装已经接了。
6. **api-proxy 只新增一条独立 `services[]` 元素**，既有条目一字未动；改共享文件后跑了
   `pnpm vitest run apps/web/src/app/api` → **197 文件 / 1147 例全绿**（含 api-proxy 全量 allowlist 回归）。

---

## 模块 17.7.1.1 套餐与权益

**这个功能做什么**：starcon **自身**的 SaaS 商业化底座 —— 平台侧定义套餐（档位、试用天数）与六个维度的权益上限
（店铺数 / SKU 数 / 订单量 / 用户数 / API 调用量 / AI 用量），租户侧订阅、试用、到期、超限判定。
**仅平台管理员可见，租户不可访问**。

**技术落地**：

- 迁移：`V323__saas_plan_entitlement.sql` — 6 张表，**平台级 / 租户级两层物理隔离**：
  平台级 `saas_plan` / `saas_plan_entitlement`（**不带 `tenant_id`、不参与 RLS、只 `GRANT SELECT TO identity_app`、零外键指向租户业务表**）；
  租户级 `saas_subscription` / `saas_entitlement_usage` / `saas_subscription_event`（`tenant_id NOT NULL` + `RLS FORCE isolation`）；
  `saas_platform_role` 是平台运营角色的**只读登记**表。
  写入路径一律走 **SECURITY DEFINER** 专用函数。
  出厂 4 个套餐 TRIAL / STARTER / GROWTH / ENTERPRISE，各带 6 个权益项。
- 端点：`SaasPlanController`（`@RequestMapping("/identity")`）**20 个映射端点** —— 19 个 `/identity/saas-plans/**` + 1 个 `/identity/saas-platform/roles`。
- 页面：`apps/web/src/app/org/plans/page.tsx` + `apps/web/src/components/iam/saas-plan-{model,workspace}.tsx`。
- 测试：`SaasPlanIntegrationTest`（12 例）+ `saas-plan-model.test.ts`（28 例）+ `saas-plan-allowlist.test.ts`（5 例）。

**关键设计决策**：

1. **套餐定义是平台级事实，订阅是租户级事实 —— 两层物理隔离**（不同表、不同 RLS 策略、不同授权面）。
   做成租户级会让每个租户各存一份套餐定义，改一次定价要改 N 行。
2. **平台级表对应用角色只给 SELECT**，写入只走 SECURITY DEFINER ⇒ **租户结构上无法自升级**，
   不依赖「权限码配得对不对」这种易错的手工配置。
3. **「仅平台管理员可见」用三道闸门同时把守（Java + DB 双重）**：① 本租户 `saas_platform_role` 有登记；
   ② 登记的 roleId 正是会话持有的角色且 `scope_mode='ALL'`；③ 该角色带有所需权限码。
   权限码**刻意不授 OWNER**（与 V53 数据字典先例相反）—— OWNER 是「本租户内什么都能干」，绑到平台运营等于让任何租户 OWNER 拿到跨租户读套餐的能力。
4. **没给租户开任何读端点** —— 蓝图明写「租户不可访问」，普通租户即使经 `create_child` 拿到 `identity.plan.*` 权限码也照样 403（集成测试实跑断言）。
5. **没另造全局超级角色** —— 平台运营角色本身是**普通租户级角色**（默认码 `PLATFORM_OPS`），
   经 `POST /identity/saas-platform/roles`（需 `X-Internal-Key`，**刻意不进浏览器 allowlist**）在会话所在租户内建角色 + 授权 + 登记 + 自分配。
   造第二套身份体系会与 identity 已有的租户/角色/成员模型打架。
6. **不落任何金额** —— 没有发票/应收/付款/结算/收入确认；权益上限与用量都是**数量**（`NUMERIC(19,6)`）。
   测试用 `information_schema` 断言 `saas_*` 表无金额语义列 —— 数字对但语义错（把「月订单量」写成金额）是最难发现的错。
7. **计量未接通 ⇒ `available=false` ⇒ `usage_value` 必须为 NULL**（DB CHECK 钉死）。
   填 0 会被误读成「量过了正好是 0」；判定结果是 `UNDETERMINED`（「判定不了」≠「没超限」），并强制带 `owner_module` + `impact`。
8. **试用与到期是真推导，不是状态字段** —— 试用起止按值往返零漂移；到期由「当前时刻 vs `expires_at`」推导，可批量处理。

---

## 模块 17.7.2.1 平台参数与开关

**这个功能做什么**：平台级功能开关与参数（灰度放量、按租户禁用某模块、限流阈值、公告），**用于平台侧运营而非租户配置**；
任何业务服务都可以查「这个租户现在能不能用这个功能」的最终结论。

**技术落地**：

- 迁移：`V324__platform_feature_flags.sql` — 5 张表，**两层分层**：
  平台全局层 `platform_flag_definition`（出厂码表）/ `platform_flag_setting`（全局当前值）/ `platform_flag_history`（变更历史）
  —— **不带 tenant_id、不启用租户 RLS、只 GRANT SELECT**（与 V53 `dict_catalog` 同一先例）；
  租户例外层 `platform_flag_tenant_exception` / `platform_flag_exception_history` —— `tenant_id NOT NULL` + ENABLE/FORCE RLS isolation。
  灰度比例 `numeric(5,2)` 禁浮点、限流阈值整数计数、`timestamptz` 一律 `OffsetDateTime` + `?::timestamptz`。
- 端点：`/identity/platform-flags` **11 个映射端点** —— `GET capabilities` / `GET definitions` / `GET settings` / `GET settings/{code}` /
  `PUT settings/{code}` / `GET exceptions` / `POST exceptions` / `DELETE exceptions/{id}` / `GET evaluate` / `GET evaluate-batch` / `GET history`。
- 页面：`apps/web/src/app/org/platform-flags/page.tsx` + `apps/web/src/components/iam/platform-flag-{model,workspace}.tsx`。
- 测试：`PlatformFlagIntegrationTest`（19 例，实跑 Testcontainers PostgreSQL 16，连接池压到 1）+ `platform-flag-model.test.ts`（25 例）。

**关键设计决策**：

1. **「平台开关不可被租户覆盖」做成四道独立防线**：
   ① 全局层**没有租户副本**（库里不存在「按租户的那一份平台开关」这个对象）；
   ② **应用角色对 `platform_flag_setting` 没有任何写入授权**（只 SELECT），唯一写入口是 SECURITY DEFINER 函数 —— 直接 SQL 写被数据库 `42501` 挡下（测试实跑）；
   ③ 例外表 `effect` 的取值域被 CHECK **钉死在 `'DISABLED'`** —— 租户例外只能收紧，永远打不开平台禁用的功能；
   ④ 限流阈值与公告**不参与**按租户禁用（`platform_flag_exception_kind_guard` 触发器拒绝 —— 禁用一个限流阈值等于放宽限制，不是收紧）。
2. **裁决过程可见**：`evaluate` 返回平台全局值 / 租户例外 / 最终生效值**三层并列** + 可读裁决码
   （`PLATFORM_DISABLED` / `TENANT_DISABLED` / `ROLLOUT_INCLUDED` / `ROLLOUT_EXCLUDED` / `NOT_CONFIGURED` / `PLATFORM_THRESHOLD` / `ANNOUNCEMENT_*`），
   不给孤零零的布尔值。
3. **灰度放量必须确定性** —— 分桶 = `sha256(salt|tenant_id)` 前 4 字节**大端无符号** `mod 10000`。
   **用 long 不用 int**：Java 的 int 有符号，首字节 ≥128 会算出负桶。与数据库函数 `platform_flag_rollout_bucket`（bigint）逐位同口径，测试逐个随机租户断言一致。
4. **限流阈值不给默认值**（照 16.5.2.1 先例）—— 出厂**不预置** `platform_flag_setting` 行，判定如实返回 `NOT_CONFIGURED`，不替平台选一个数字。
5. **平台全局层只允许根租户写** —— 本仓 `create_child` 会把 `permission` 表里的全部权限码授给子租户 OWNER，
   只看权限码挡不住子租户改全平台配置（测试实跑：子租户 OWNER 有写权限码但 403）。
6. **刻意不放行 `platform-flags/tenant-switch`** —— 「租户覆盖平台开关」是蓝图**明令禁止**的能力，
   放行它等于把一条不存在的后门登记成公开接口。

---

## 模块 17.5.2.1 数据字典（api-proxy 白名单修复车道 · 纯前端）

**这个功能做什么**：这一条不是新功能，而是把**已经交付但浏览器里 404** 的两个页面白名单补登记 ——
数据字典页面与 catalog 商品生命周期页面，后端端点全部存在且正确，但浏览器侧白名单没登记。

**技术落地**：

- 迁移：**无**（`migrationVersion = null`）—— 零后端改动、零迁移、零契约改动。
- 端点：**无**（后端端点早已存在）。本车道只动浏览器侧 `apps/web/src/lib/api-proxy.ts`，
  **新增两条独立的 `services[]` 元素**：`prefix: 'identity/'`（放行 `DictionaryController` 的 11 个端点）+
  `prefix: 'catalog/'`（放行 `LifecycleController` 的 9 个端点）。既有条目**一字未动、不重排、不格式化整文件**。
- 页面：无（沿用已交付页面）。
- 测试：新增 `apps/web/src/lib/data-dictionary-lifecycle-api-proxy.test.ts`（6 项双向断言）。

**关键设计决策**：

1. **白名单是累积型正则表 —— 静态扫描 + 自研 resolve 不可靠**。本车道逐条用 `resolve()` 实测脚本核对，
   **证伪了调度者任务书里的 2 组误判**：
   ③ catalog 商品档案（`records/(research|compliance|assets)` 既有条目已放行）、
   ④ trade 采集接入（`collector-endpoint/[A-Z0-9_]+` 既有 6.1.2.1 已放行）。
   ⇒ 这两组**不新增元素** —— 新增只会是**永远走不到的死分支**（既有条目更靠前，`resolve()` 首个命中即返回），属于「假交付」。
2. **顺带补上任务书漏掉的 `/products/{id}/lifecycle`** —— 同一个缺陷、同一个模块、同一个前端页面
   （`spu-lifecycle.tsx` 的 GET/POST），基线同样是 404，不补就是留一个 404 在那儿。
3. **id 段用完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）；
   `{dictCode}` 段用受控值域 `[A-Z][A-Z0-9_]{0,63}`，**绝不用 `[^/]+`**。
4. **刻意不放行** `dict-items/{uuid}/delete`（后端没有 DELETE 端点）等 8 个形状 ——
   放行后端不存在的映射比 404 更糟（界面会以为功能已交付）。

> 📌 **教训（留给调度者）**：本车道是靠「把前端全部 `api()` 调用点反向抽取、逐条跑 `resolve()`」这个扫描
> 才发现 ② 的漏项与 ③ ④ 的误判 —— 只按「模块已交付」清单逐条对，靠人眼查不出来。
> 建议把「api-proxy 白名单覆盖度」做成常规闸。

---

## 本轮静态预检（迁移互相破坏形状扫描）

对本批 4 个新迁移逐个扫描：

| 检查项 | V321 | V322 | V323 | V324 |
| --- | --- | --- | --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | 无 | 无 | 无 | 无 |
| ② `SET NOT NULL` | 无 | 无 | 无 | 无 |
| ③ `ADD CONSTRAINT ... CHECK` | 无 | 无 | 无 | 1 处（`platform_flag_setting_shape`） |
| ④ `BEFORE INSERT` 触发器 | 12 | 8 | 0 | 2 |

③ 那一处 CHECK 是加在 **V324 自己在同一文件里新建的** `platform_flag_setting` 表上（第 132 行建表、第 170 行加约束），
不是对既有表的 ALTER ⇒ 不会破坏早先合入车道的 INSERT。**静态预检通过。**

---

## 本轮派发（2 条车道）

`plan --lanes 4` 实际只排出 **2** 条 —— 其余 4 个卷被**服务缺口**挡住（`serviceBlocked`）：

| 卷 | 阻塞原因 |
| --- | --- |
| 18 | 主责服务 `platform` 不在集成头 |
| 12 | 主责服务 `finance` 不在集成头 |
| 1 | 主责服务「前端聚合」不在集成头 |
| 13 | 主责服务 `reporting` 不在集成头 |

⇒ **121 个未开发模块里，可调度的只剩 2 个。** 继续提速的瓶颈已经不是并发数，而是**服务缺口**。

| 模块 ID | 名称 | 分支 | 迁移 | worktree |
| --- | --- | --- | --- | --- |
| 17.7.1.2 | 用量统计 | `feat/17-7-1-2` | `V325` | `.worktrees/17.7.1.2` |
| 17.7.2.2 | 平台运维视图 | `feat/17-7-2-2` | `V326` | `.worktrees/17.7.2.2` |

两条车道都落在 **identity** 服务（卷 17 主责服务），且上游契约已经由本批的 17.7.1.1 / 17.7.2.1 点名交接：

- **17.7.1.2** 接手 17.7.1.1 的 `capabilities` 里「权益用量自动计量」缺口（`ownerModule=17.7.1.2`），
  六个维度码与四个周期码**必须沿用** 17.7.1.1 的定义（`SHOP_COUNT` / `SKU_COUNT` / `ORDER_VOLUME` / `USER_COUNT` / `API_CALL` / `AI_USAGE`；
  `DAY` / `MONTH` / `YEAR` / `TOTAL`）—— 口径不一致会直接引发计费争议。
- **17.7.2.2** 接手 17.7.2.1 的 `capabilities` 里 `PLATFORM_OPS_VIEW` 缺口（`ownerModule=17.7.2.2`），
  且有一条**红线**：跨租户视图只看技术指标不看业务数据。

两条车道在 `apps/web/src/lib/api-proxy.ts` 的插入锚点被**刻意拉开 6000 行以上**
（17.7.1.2 锚在 `// 2.4.2.3 映射缺失队列` 之前；17.7.2.2 锚在数组末尾），避免 git 结构性冲突。

---

## L2 批量验证结果

### 🔴 第一轮：**红**（已精确定位到车道）

```
===== [1/6] backend ./mvnw verify =====
[ERROR] com.starcon.identity.IdentityIntegrationTest
        .ownerIsProtectedAndTransferIsExplicitAndCannotBeUsedToEscalate
        :376->IdentityIntegrationTestBase.request:91
        Response status expected:<403> but was:<200>
[ERROR] Tests run: 301, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

⇒ **L2 没绿 ⇒ 本轮不 push。** 但说明照写、commit 照做（成果与诊断留在集成头本地，修复后随下一批一起 push）。

### 二分定位（用 2 次定向跑代替 2 次全量验证，比协议允许的上限更省）

| # | 实验 | 命令 | 结果 | 结论 |
| --- | --- | --- | --- | --- |
| 1 | 单独跑该测试类 | `-Dtest='IdentityIntegrationTest'` | **绿**（17/17） | 测试本身没问题 ⇒ 是**状态污染** |
| 2 | 平台角色测试 + 该测试类 | `-Dtest='SaasPlanIntegrationTest,IdentityIntegrationTest'` | **绿**（29/29） | 单靠 17.7.1.1 **不足以**触发 ⇒ 依赖**全量运行**时的数据规模/查询计划 |
| 3 | 全量 identity 套件 | `-Dtest=`（无过滤） | **红**（301 例 1 失败） | 复现 ✓ |
| 4 | **全量 identity 套件，排除 17.7.1.1 的测试类** | `-Dtest='!SaasPlanIntegrationTest'` | **绿：289 例 0 失败，BUILD SUCCESS** | **肇事者确定 = 17.7.1.1** |

289 = 301 − 12（`SaasPlanIntegrationTest` 正好 12 例）⇒ 排除它就绿、加回来就红，**因果链闭合**。

### 根因（三段，全部在代码里核对过）

1. **grants 数组是无序的。**
   `V47__member_role_assignment.sql:167-181` 的 `session_access(hash)` 用
   `jsonb_agg(jsonb_build_object('roleId',…, 'roleCode',…, 'scopeMode',…, 'permissions',…))` 聚合角色，
   **没有 `ORDER BY`** ⇒ PostgreSQL 不保证返回顺序，`me.get("grants").get(0)` 取到谁取决于查询计划。
2. **17.7.1.1 的测试给「共享的 root owner」多挂了一个角色。**
   `SaasPlanIntegrationTest.platformToken()`（第 77-85 行）以 `login()`（= 共享 `local-admin`）登录后调
   `POST /identity/saas-platform/roles`，打到 V323 的 `saas_platform_register_role`（V323:351-376），
   那里有一句 `INSERT INTO role_assignment(…, a.membership_id, rid, 'ALL', a.user_id)` ——
   **把 PLATFORM_OPS 角色永久分配给调用者本人**。
   ⇒ `local-admin` 从 1 个角色（OWNER）变成 2 个（OWNER + PLATFORM_OPS）。
   **在 17.7.1.1 交付之前它只有一个角色，`grants.get(0]` 是确定性的** —— 这就是本批把它引爆的原因。
3. **两个角色时 `grants.get(0)` 不再确定。**
   一旦落到 PLATFORM_OPS，`DELETE /identity/roles/{PLATFORM_OPS}` 就**合法**（它不是 OWNER）⇒ 角色被删 ⇒ **200** 而非 403。

> 另核实：同批 **17.7.2.1（`PlatformFlagIntegrationTest`）不碰角色分配，不是共犯**；
> `RoleAssignmentIntegrationTest` 等既有测试类是**受害者**不是肇事者（在 17.7.1.1 之前就存在且当时全绿）。
> 泄漏之所以能跨测试类，是因为 **identity 模块 17 个测试类在同一次 maven 运行里共用同一个 Testcontainers PG16 实例**。

### 已派发的修复车道

车道 `17.7.1.1-fix`（分支 `fix/17-7-1-1-test-isolation`），**只修 17.7.1.1 自己的测试**，不重写：

- **修复 A（主责）**：`SaasPlanIntegrationTest` 不得再污染共享的 `local-admin`。
  关键前提：`saas_platform_gate` 是**按租户**判的（V323:323-339，只要求 `saas_platform_role.tenant_id = a.tenant_id`，
  **没有根租户要求**）⇒ 可以给平台运营身份一个专属租户/成员，共享 owner 从头到尾只有一个角色。
- **修复 B（次责，调度者明确授权）**：`IdentityIntegrationTest` 那一处断言改为**按 `roleCode == "OWNER"` 显式选角色**，
  不再依赖无序聚合 —— 只改这一处，不许重构该测试方法、不许放宽任何期望状态码。
- 明确禁止改 `session_access`（V47）加 `ORDER BY`：那是全仓安全相关函数，爆炸半径远大于本缺陷。

---

<!-- L2_ROUND2 -->

---

## 后续待办

1. **服务缺口是当前最大瓶颈** —— 卷 18（platform）/ 12（finance）/ 1（前端聚合）/ 13（reporting）四个卷的主责服务不在集成头，
   导致 `plan` 每次只能排出 2 条而不是 4 条。**补齐这四个服务比提高 `--lanes` 划算得多。**
2. **17.5.2.1 白名单车道留下的两条收紧项** —— 既有 catalog alternation 里 `records/...` 的 id 段是 `[0-9a-f-]+`、
   既有 6.1.2.1 里 `collector-endpoint` 的 platform 段是 `[A-Z0-9_]+`，都比后端真实值域宽。收紧要改既有条目 ⇒ 留给单独车道。
3. **建议把「api-proxy 白名单覆盖度」做成常规闸**（反向抽取前端全部 `api()` 调用点 → 逐条跑 `resolve()` 断言真实 HTTP 状态码）。
4. **12 财务落地后**，16.5.2.2 的「支付方式 / 收款账户」两个维度与 16.6.2.1 的财务留存明细可从 `available=false` 转 `true`。
5. **数据字典与平台运维两个页面迁到 `apps/admin`** —— `apps/admin/` 目前仍只有空脚手架（没有 app router），
   本轮页面暂放 `apps/web` + `components/iam/`（仓内既有惯例），迁移是后续独立工作项。
6. **`lane.mjs merge-ready` 生成的合并 commit 只有标题、没有正文** —— 协议 5.3 ①(b) 要求「集成头的合并 commit 正文列本批模块清单」，
   但脚本写死的是 `merge --no-ff -m "merge(<lane.id>): <lane.name>"`，**正文恒为空**。
   本轮的处理办法：把完整的本批模块清单放进 `docs(batch-72)` 这个 commit 的正文（协议 ② 本来就要求「模块ID 清单」）。
   要不要让 `merge-ready` 自己生成正文，属于编排脚本的改动，**留给单独一轮**，不要在批次窗口里动它。
7. **`session_access` 的 `grants` 聚合无 `ORDER BY`**（V47:167-181）—— 这是本轮 L2 红的放大器。
   本轮只在测试层做了修复（不碰生产函数）。**「grants 顺序依赖」这个隐患仍在**：
   将来任何给共享 owner 增加角色的模块，都可能再次引爆同类问题。
   若要根治（给 `session_access` 加 `ORDER BY`，需新迁移 `CREATE OR REPLACE FUNCTION`），
   必须单独评估它对全仓 identity 消费者的影响，**不能塞进一个测试修复车道里顺手做**。
