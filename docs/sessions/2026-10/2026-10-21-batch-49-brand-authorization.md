# Batch 49 —— 16.4.2.1 品牌授权

> 并行车道流水线 · 批次 49 · 2026-10-21（12:00 tick）
> 集成头 `integration/module-dev` · 本批 1 条车道回收并入
> 批次号接在 batch-48 之后（`docs/sessions/2026-10/` 下现有最大为 batch-48，已确认无重号）

## 本批概览

| 模块 ID | 名称 | 阶段 | 归属服务 | 迁移版本 | 车道分支 | 车道 head | 并入 commit |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 16.4.2.1 | 品牌授权 | D7 | channel | `V248__brand_authorization.sql` | `feat/16-4-2-1` | `02123ff1` | `5a685e01` |

本批车道在 **batch-48 末尾落地**（state=ready），当时刻意**不并入**，
理由是「并进去就要再跑一次全量 L2，而本 tick 已因两处潜伏缺陷各跑过一次；
下一轮并入可与后续车道同批，摊薄 L2 成本」。本轮（12:00 tick）执行该并入。

---

## 模块 16.4.2.1 品牌授权

**这个功能做什么**：登记并管理品牌授权台账 —— 谁授权的、授权范围覆盖哪些
**店铺 / 国家 / 类目 / 期限**、授权文件是哪一份、被授权主体是谁；
并能回答「这一次用这个品牌开店/刊登，**在不在授权范围内**」，
把结论做成**可追溯的校验凭据**与**到期提醒**（授权过期是「平时没事、上新时出事」的典型）。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V248__brand_authorization.sql`
  — **4 张新表**：
  `brand_authz_authorization`（授权主档：`grantor` / `licensee_type` / `effective_from` / `effective_to` /
  `rule_version` / `enabled`）、`brand_authz_scope`（范围行：店铺 / 国家 / 类目三个维度）、
  `brand_authz_document`（**文件引用与元数据**：文件名 / 内容类型 / 字节数 / 存储句柄 / 上传时刻 / 上传人）、
  `brand_authz_check`（**判定凭据**：结论 / 严重度 / 原因码数组 / 命中授权 / 评估时刻）；
  4 组索引，`tenant_id NOT NULL` + **ENABLE 与 FORCE** RLS（`isolation` 策略，
  `current_setting('app.current_tenant')`），GRANT / REVOKE；
  **2 个守卫触发器**：`guard_brand_authz_check_immutable`（判定凭据 append-only）、
  `guard_brand_authz_authorization_version`（`rule_version` 只能前进）；
  **零 ALTER 任何既有表** ⇒ 对并行车道的写入路径零影响。
- 端点：**14 个** `/channel/brand-authorizations`
  （`GET capabilities` / `GET+POST authorizations` / `GET+PUT authorizations/{id}` /
  `PUT authorizations/{id}/enabled` / `GET expiry-reminders` / `POST checks` / `POST publish-gate` /
  `GET checks` / `GET candidates/brands` / `candidates/suppliers` / `candidates/qualifications` / `GET shops`）。
- 页面：`/compliance/brand-authorization`（真实工作区 + 纯函数模型，
  `apps/web/src/components/brand-authorization/brand-authorization-workspace.tsx`）。
- 测试：**40 例**后端
  （`BrandAuthorizationRulesTest` 18 + `BrandAuthorizationIntegrationTest` 19 +
  `BrandAuthorizationTenancyIntegrationTest` 3，含租户隔离三断言）
  + 前端 `brand-authorization-model.test.ts` **21 例**
  + `brand-authorization-allowlist.test.ts` **5 例**（白名单**双向**断言）。

**关键设计决策**：

1. 🔴 **与 catalog 3.2.1.2「品牌授权」的边界**：catalog 那套回答「这个品牌有没有被授权过」**一个问题**；
   本模块做的是**合规口径的授权档案** —— 授权方 + 范围 + 期限 + 文件 + 被授权主体，
   回答「**这一段范围、这一段时间、谁可以用这个品牌**」。
   落地纪律：catalog 侧**只经授权只读端口读**（`BrandAuthorizationSourcePort` → `GET /catalog/brands`），
   **不建影子表、不跨库 SQL、不跨库 FK**；两边写入所有权各自独立，互不写。
2. 🔴 **期限是区间，到期判定用同源 DB 时钟**：`effective_from` / `effective_to` 两个 `timestamptz`
   + `CHECK(effective_to > effective_from)`；到期判定**一律取 DB 的 `now()`**（服务层在事务里取一次，
   Java 侧与 SQL 侧共用同一时刻），剩余天数由 DB 按
   `extract(epoch from (effective_to - now()))/86400` 算。
   **禁用 `LocalDate.now()`** —— 应用机与数据库机差半天，「授权还剩 0 天」会变成差半天，
   而「还剩 0 天」正是运营最需要看见的那个数。测试直接**断言写回的值**（`extract(epoch…)` 对
   `OffsetDateTime.parse(dto).toInstant()` 逐字比）。
3. 🔴 **「判不了」是第三种状态**，不是「合格」也不是「不合格」。五态：
   `IN_SCOPE`（放行）/ `OUT_OF_SCOPE`（阻断，逐条列出哪条不覆盖哪个维度）/ `EXPIRED`（阻断）/
   `NO_AUTHORIZATION`（阻断）/ `UNKNOWN`（**判不了** —— 上下文缺维度，**不放行**）。
   `UNKNOWN` 不放行，是因为放行等于把「没查」写成「合规」；
   它也**不是**「不合格」：严重度是 `REVIEW` 而非 `BLOCKING`，原因码
   `SHOP_UNRESOLVED` / `COUNTRY_UNRESOLVED` / `CATEGORY_UNRESOLVED`，
   界面显示成「判不了（上下文不全）」而不是「越权」。
4. 🔴 **多国店铺的口径**：授权必须覆盖店铺的**全部**目标国家，缺一个就是不覆盖 ——
   授权只覆盖 DE 而店铺同时卖 DE + FR 时，在 FR 上是越界的。
   目标国家优先取调用方显式给的国家，没给就取店铺的启用站点还原出来的国家集合。
5. 🔴 **阻断在服务端强制**（三件套缺一不可）：
   ①服务层 `POST /channel/brand-authorizations/publish-gate` 真算一遍覆盖，回 `allowed=false` + 逐条原因 ——
   下游 4.2.3.2 预检与 4.3.1.1 一键刊登将来消费的是**这个服务端结论**，不是页面上的一个红字；
   ②DB 层判定凭据 **append-only**（触发器钉住 `in_scope` / `blocking` / `severity` / `conclusion` /
   `rule_version` / `evaluated_at` / `matched_authorization_id` 事后改写不了）⇒「昨天那次为什么拦住」永远答得出来；
   ③规则有版本且**只能前进**（`rule_version` 由触发器钉住）—— 回退版本号不是修订，是篡改。
   > ⚠️ **DB 层那道闸为什么没挂在刊登表上**：4.2.3.2 那条车道把闸门做成了
   > `listing_editor_submission` 的 BEFORE INSERT 触发器；本模块做不到 ——
   > `listing_editor_draft` 里**没有品牌列**（只有 `source_sku_id` 这个跨服务逻辑 id），
   > DB 层无法解析出「这次刊登用了哪个品牌」，而跨库读 catalog 被 AGENTS.md 明令禁止。
   > 故强制点放在**服务层闸门端点 + 判定凭据不可篡改**上，并如实写明，不假装有一道 DB 闸。
6. 🔴 **授权文件只存引用与元数据，文件本体不入库**：把 PDF / 扫描件塞进业务库会让这张表变成垃圾桶
   （备份、迁移、RLS 都要为二进制付出代价，租户隔离漏洞面更大）。
   真正的**凭证核验**（真伪验证记录、文件版本、签署方信息、一键核验）归 **16.4.2.2 授权凭证**，本模块**未交付**它。
7. **表命名空间与「四张表只有 isolation 策略」的取舍**：表前缀 `brand_authz_*`，与同批 4.2.3.2 的
   `listing_compliance_*`、16.2.2.1 的 `compliance_check_*`、以及 **catalog 自己库里的 `brand_authorization`**
   三个命名空间彼此隔离。
   四张表**只有 isolation、没有 data_scope（RESTRICTIVE）策略** ——
   授权档案是**租户级合规资产**（同 V198 的词表、V195 的模板），不属于某个店铺或部门；
   硬套「按店铺负责人 / 部门收敛」的 data_scope 只会造出两种坏结果 ——
   要么授权档案在部分人眼里凭空消失（合规台账不可信），要么 NULL 店铺的行被策略吞掉。
   部门级收敛归 17 的权限体系，不在本模块硬造。
8. 🔴 **契约 DTO 撞名 → 改名**（codegen 拦下来的真问题）：入参 record 原名 `BrandAuthorizationInput`
   与 **catalog 3.2.1.2 的同名 record 撞名** ⇒ 跨服务覆盖检测会直接中止（桶文件按服务顺序绑定第一份，
   channel 独有的 `brandId` / `licenseeType` / `scopes` / `documents` 会凭空消失，
   catalog 的 `certificateNo` / `evidenceUri` / `version` 会凭空多出）。
   已改名 **`BrandAuthorizationRegistrationInput`**，理由写在 record 的 javadoc 上。
9. **没有 DELETE 端点**：合规台账不做物理删除，撤销走 `PUT /authorizations/{id}/enabled`（停用）。
   历史判定凭据是「当时为什么拦」的证据，更不能删。代价是录错了只能改不能删；收益是台账本身可作凭据。
10. **判定凭据里的 `shop_id` / `matched_authorization_id` 故意不建 FK**：
    校验凭据必须活得比它引用的店铺 / 授权更久 —— 店铺被归档、授权被撤销时，判定记录不能跟着消失。
11. **`brand_authz_check` 的 CHECK 把口径钉死**：`(severity='BLOCKING') = blocking`、
    `conclusion='IN_SCOPE' OR NOT in_scope`、`conclusion='IN_SCOPE' OR jsonb_array_length(reasons)>0`
    —— 应用层算错也在 DB 层被拒（测试里直接 INSERT 一条「没有原因的不合格判定」被拒）。
12. **跨服务读不到 ⇒ `available=false` + 原因，不抛异常、不返回空集合**：
    把「读不到」折成空集合，界面会显示「这个品牌没有被授权过」，而真相是「我们没查到」。
13. **同一维度上没有登记任何范围 = 该维度不限制**（授权只登记了国家而没登记店铺，
    意思是「这家店在哪个国家卖都行」，不是「这家店不许卖」）。
14. **api-proxy 纯增量**：新增**一条独立 `services[]` 元素**（`prefix: 'channel/'` + 自己的 pattern，
    **52 行插入、零删除**），**不并进既有 channel 巨型正则**（并进去会让本车道与并行车道的插入点被隔开，git 判冲突）。
    动手前用脚本逐条核对**既有全部 100 条 elements 的正则**，对本模块 12 条路径**命中数为 0**。
    裸路径 `brand-authorizations` **刻意不放行**（后端没有「一条汇总列表接口」）
    —— 放行后端不存在的映射比 404 更糟。

---

## L2 批量验证（集成头，全量）

被验证的树 = 本批合并后的集成头 `5a685e01`（含 16.4.2.1 品牌授权），
**12:03:09 → 12:41:17**（38 分钟），本轮**唯一**一次发热高峰。

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `cd backend && ./mvnw verify` | ✅ **EXIT=0 · BUILD SUCCESS** —— 8 模块 reactor，**36:25 min**；6 个有测试的服务模块合计 **6859 例全绿**（identity 270 / catalog 1981 / **channel 1200** / warehouse 1569 / trade 1769 / crm 70） |
| 类型 | `pnpm typecheck` | ✅ EXIT=0 |
| Lint | `pnpm lint` | ✅ EXIT=0 |
| 单测 | `pnpm test` | ✅ EXIT=0 —— **379 个文件 / 5784 例全绿** |
| 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ EXIT=0 |
| 台账 | `node tools/module-progress/sync.mjs --check` | ✅ EXIT=0（台账与蓝图一致，465 行无重复 ID） |

**本车道的 40 例在 L2 里实跑全绿**（不是只跑 L1）：
`BrandAuthorizationRulesTest` 18 例（0.015 s，纯函数）+
`BrandAuthorizationIntegrationTest` 19 例（3.248 s，Testcontainers 真实 PG 16）+
`BrandAuthorizationTenancyIntegrationTest` 3 例（4.729 s，租户隔离三断言）= **40 / 40 绿**。

**两处交叉验证（说明计数可信）**：

1. **前端增量与本车道精确吻合**：batch-48 的 L2 是 **377 文件 / 5758 例**，
   本轮是 **379 文件 / 5784 例** ⇒ **+2 文件 / +26 例**，
   正好是本车道新增的 `brand-authorization-model.test.ts`（21 例）+
   `brand-authorization-allowlist.test.ts`（5 例）。**没有多、也没有少。**
2. **后端计数不含跨轮次残留**：394 个 surefire 报告逐个反查源文件，
   **孤儿报告 0 个**（identity 22 / catalog 122 / channel 88 / warehouse 61 / trade 94 / crm 7）
   ⇒ 6859 这个数不是「把历史残留的 .txt 一起累加」得来的。
   > 顺带更正一处历史数字：batch-48 文档记的「channel 1182」比本轮同口径实测**高出 22**。
   > `git diff 8bb3fcdd HEAD -- backend/services/channel/src/test/` 显示两次 L2 之间
   > channel 测试树**只增不减**（85 → 88 个测试类，仅本车道 4 个新文件），
   > 所以 1182 是那次统计的口径误差（大概率是累加了上一轮 L2 的残留 .txt），
   > **本轮起一律以「反查源文件、孤儿 0」的口径报数**。

**静态预检（本批新迁移）**：`V248__brand_authorization.sql` —
**① `ADD COLUMN` 无 `DEFAULT`：0 命中；② `SET NOT NULL`：0 命中；③ `ADD CONSTRAINT ... CHECK`：0 命中**。
该迁移是**纯 CREATE**（4 张新表 + 索引 + RLS(FORCE) + 2 个守卫触发器 + GRANT/REVOKE），
**零 ALTER 任何既有表** ⇒ 对并行车道的既有写入路径零影响。

**共享白名单文件复核**：`apps/web/src/lib/api-proxy.ts` 本批改动为
**52 行插入 / 0 行删除**（新增一条独立 `services[]` 元素，未并进既有 channel 巨型正则），
**纯增量、无删除** ⇒ 不存在「取错一侧静默删掉别人已交付路由」的风险；
且本批新增的 12 条路径对既有全部 100 条 `services[]` 元素正则**命中数为 0**（车道自检结论）。

---

## 涉及文件

- `backend/services/channel/src/main/resources/db/migration/V248__brand_authorization.sql` —— 迁移
- `backend/services/channel/src/main/java/com/starcon/channel/BrandAuthorization{Models,Rules,Service,Controller,SourcePort}.java` —— 后端
- `backend/services/channel/src/test/java/com/starcon/channel/BrandAuthorization{RulesTest,IntegrationTest,TenancyIntegrationTest,UpstreamStub}.java` —— 测试
- `apps/web/src/components/brand-authorization/` —— 前端工作区 + 纯函数模型 + 单测
- `apps/web/src/app/compliance/brand-authorization/page.tsx` —— 页面路由
- `apps/web/src/app/api/channel/brand-authorization-allowlist.test.ts` —— 白名单双向断言
- `apps/web/src/lib/api-proxy.ts` —— **纯增量**（+52 / -0）
- `packages/api-contracts/src/channel.openapi.json` + `src/contracts/channel.ts` —— codegen 产物（纯增量）
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）
- `docs/sessions/2026-10/2026-10-21-brand-authorization.md` —— 车道自己的会话日志
- `docs/sessions/2026-10/2026-10-21-batch-49-brand-authorization.md` —— 本文件

## 关联

- 上一批：`docs/sessions/2026-10/2026-10-21-batch-48-quality-reconciliation-analytics.md`
  （该批末尾记录了 16.4.2.1「已落地但本轮不并入」的决定与合并预检结果）
- 车道自述：`docs/sessions/2026-10/2026-10-21-brand-authorization.md`
