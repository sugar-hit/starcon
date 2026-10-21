# 16.4.2.1 品牌授权（阶段 D7，L3 锚点 16.4.2）

- **日期**：2026-10-21
- **车道**：`feat/16-4-2-1`（worktree `integration/.worktrees/16.4.2.1`）
- **蓝图原文**（`docs/product/capability-blueprint/16-compliance-and-risk.md` 189-195 行）：
  > 职责：管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，校验刊登时的品牌使用是否在授权范围内。
  > 输入：授权录入；文件上传；有效期。
  > 输出：授权档案 + 校验结果。
  > 关系：上游 = 6.1.2.3 供应商资质；下游 = 3.3.2 品牌状态、4.2.3.2 预检。**授权过期是「平时没事、上新时出事」的典型**，需到期提醒与刊登阻断。

## 决策摘要

### 1. 🔴 与 catalog 3.2.1.2「品牌授权」的边界（本模块第一件要说清的事）

catalog 侧已经有一套品牌授权（`V16__brand_management.sql` 的 `brand_authorization` 表，
端点 `GET/POST /catalog/brands/{id}/authorizations`、`PUT .../authorizations/{authorizationId}`）。
它回答的是**「这个品牌有没有被授权过」**这**一个问题**。

本模块做的是**合规口径的授权档案**：授权方 + 授权范围（店铺 / 国家 / 类目 / **期限**）+
授权文件 + 被授权主体，并且**校验刊登时的品牌使用是否落在授权范围内**。
回答的是**「这一段范围、这一段时间、谁可以用这个品牌」**。

落地纪律：

- catalog 侧**只经授权只读端口读**（`BrandAuthorizationSourcePort` → `GET /catalog/brands`），
  **不建 catalog 的影子表**、不跨库 SQL、不跨库 FK；
- 本模块的授权档案是**本租户自己的合规台账**（写入所有权在本模块），
  catalog 那套的写入所有权仍在 catalog 手里 —— 两边不互相写；
- catalog 那套**不算作本模块的交付物**，也不因为它存在就把本模块说成「已交付」。

> ⚠️ **任务书与实测的一处偏差（如实记录）**：任务书把上游 6.1.2.3 供应商资质写成 catalog，
> 实测它在 **trade** 服务（`backend/services/trade/.../V239__supplier_qualification.sql`，
> 端点 `/trade/supplier-qualifications/**`）。上游存在性成立，但跨服务读取路径按实测走，
> 并在迁移文件头、capabilities 与台账里写明。

### 2. 🔴 期限是**区间**，到期判定用**同源 DB 时钟**

蓝图写的是「授权范围（店铺/国家/类目/**期限**）」，而**区间**才能表达「这段时间内授权有效」。
落成 `effective_from` / `effective_to` 两个 `timestamptz` + `CHECK(effective_to > effective_from)`。

到期判定**一律取 DB 的 `now()`**（服务层 `select now()` 取一次，同一事务里 Java 侧比较与
SQL 侧比较共用这一个时刻），剩余天数由 DB 按 `extract(epoch from (effective_to - now()))/86400` 算。
**禁用 `LocalDate.now()`** —— 应用机与数据库机差半天，「授权还剩 0 天」就变成了差半天，
而「还剩 0 天」正是运营最需要看见的那个数。

配套的测试直接**断言写回的值**：用 `extract(epoch from ...)::bigint` 与
`OffsetDateTime.parse(dto).toInstant()` 逐字比，不受会话时区影响，也不受格式化影响。

### 3. 🔴 「判不了」是第三种状态，不是「合格」也不是「不合格」

结论五态：

| 结论 | 含义 | 闸门 |
| --- | --- | --- |
| `IN_SCOPE` | 至少一条授权在期限与范围上都覆盖 | **放行** |
| `OUT_OF_SCOPE` | 有授权但都不覆盖（逐条列出哪条不覆盖哪个维度） | 阻断 |
| `EXPIRED` | 有授权但全部已过期 | 阻断 |
| `NO_AUTHORIZATION` | 本租户下该品牌没有任何授权档案 | 阻断 |
| `UNKNOWN` | 授权存在但上下文缺维度，**判不了** | **不放行** |

`UNKNOWN` 之所以**不放行**：把它放行等于把「没查」写成「合规」。
它也**不是**「不合格」—— 严重度是 `REVIEW` 而不是 `BLOCKING`，原因码是
`SHOP_UNRESOLVED` / `COUNTRY_UNRESOLVED` / `CATEGORY_UNRESOLVED`，
界面显示成「判不了（上下文不全）」而不是「越权」。

**多国店铺的口径**：一个店铺往往同时在多个站点销售，授权只覆盖 DE 而店铺同时卖 DE + FR 时，
这家店的品牌使用**在 FR 上是越界的** —— 所以授权必须覆盖店铺的**全部**目标国家，缺一个就是不覆盖。
目标国家优先取调用方显式给的国家，没给就取店铺的启用站点还原出来的国家集合。

### 4. 🔴 阻断在服务端强制（卷首约束第一条）

三件事，缺一不可：

1. **服务层**：`POST /channel/brand-authorizations/publish-gate` 在服务端真算一遍授权范围覆盖，
   回 `allowed=false` + 逐条原因。4.2.3.2 预检与 4.3.1.1 一键刊登将来消费的是**这个服务端结论**，
   不是页面上的一个红字。
2. **DB 层 · 判定凭据 append-only**：触发器 `guard_brand_authz_check_immutable` 钉住
   `in_scope` / `blocking` / `severity` / `conclusion` / `rule_version` / `evaluated_at` /
   `matched_authorization_id` 事后改写不了 ⇒ 「昨天那次为什么拦住」永远答得出来。
3. **规则有版本且只能前进**：授权的 `rule_version` 由触发器
   `guard_brand_authz_authorization_version` 钉住只能前进 —— 回退版本号不是修订，是篡改。
   规则改了就是重跑一次产生新行（追加式），**旧行原样留着**。

> ⚠️ **DB 层那道闸为什么不是挂在刊登表上**：4.2.3.2 那条车道把闸门做成了
> `listing_editor_submission` 的 BEFORE INSERT 触发器。本模块做不到 ——
> `listing_editor_draft` 里**没有品牌列**（只有 `source_sku_id` 这个跨服务逻辑 id），
> DB 层无法解析出「这次刊登用了哪个品牌」，而跨库读 catalog 被 AGENTS.md 明令禁止。
> 所以本模块把强制点放在**服务层的闸门端点 + 判定凭据的不可篡改**上，并在迁移文件头与
> capabilities 里如实写明这一点，不假装有一道 DB 闸。

### 5. 🔴 授权文件只存引用与元数据，文件本体不入库

蓝图输入写的是「文件上传」，但把 PDF / 扫描件塞进业务库会让这张表变成垃圾桶：
备份、迁移、RLS 策略全部要为二进制付出代价，**租户隔离的漏洞面更大**。
所以 `brand_authz_document` 只存：文件名 / 内容类型 / 字节数 / 存储句柄 / 上传时刻 / 上传人。
真正的**凭证核验**（真伪验证记录、文件版本、签署方信息、一键核验）归 **16.4.2.2 授权凭证**，
本模块**未交付**它。

### 6. 表命名空间与「四张表只有 isolation 策略」的取舍

表前缀 `brand_authz_*`：与同批 4.2.3.2 的 `listing_compliance_*`、16.2.2.1 的
`compliance_check_*`、以及 **catalog 服务自己库里的 `brand_authorization`** 三个命名空间彼此隔离
（catalog 那套在别的库、别的 Flyway 历史里，但语义上必须分得开）。

四张表**只有 isolation 策略、没有 data_scope（RESTRICTIVE）策略**，理由写在迁移文件头：
授权档案是**租户级合规资产**（同 V198 的 `listing_compliance_word` 词表、同 V195 的
`listing_template` 模板）—— 它不属于某一个店铺，也不属于某一个部门。
硬套「按店铺负责人 / 部门收敛」的 data_scope 策略只会造出两种坏结果 ——
要么授权档案在部分人眼里凭空消失（合规台账不可信），要么 NULL 店铺的行被策略吞掉。
部门级收敛归 17 的权限体系，不在本模块硬造。

### 7. 🔴 契约 DTO 撞名 → 改名（codegen 拦下来的真问题）

入参 record 原名 `BrandAuthorizationInput` 与 **catalog 3.2.1.2 的同名 record 撞名**。
`tools/codegen/catalog-contracts.py` 的跨服务覆盖检测会**直接中止**（桶文件按服务顺序绑定第一份
⇒ catalog 那份会把本模块的入参整份遮掉：channel 独有的 `brandId` / `licenseeType` / `scopes` /
`documents` 会凭空消失，catalog 的 `certificateNo` / `evidenceUri` / `version` 会凭空多出）。
已改名 **`BrandAuthorizationRegistrationInput`**，理由写在 record 的 javadoc 上，
免得半年后有人「顺手改回去」再撞一次。

## 关键 trade-off

- **没有 DELETE 端点**：合规台账不做物理删除，撤销走 `PUT /authorizations/{id}/enabled`（停用）。
  历史判定凭据（`brand_authz_check`）是「当时为什么拦」的证据，更不能删。
  代价是录错了只能改不能删；收益是台账本身可作凭据。
- **判定凭据里的 `shop_id` / `matched_authorization_id` 故意不建 FK**：校验凭据必须活得比它引用的
  店铺 / 授权更久 —— 店铺被归档、授权被撤销时，判定记录不能跟着消失。
- **`brand_authz_check` 的 CHECK 把口径钉死**：`(severity='BLOCKING') = blocking`、
  `conclusion='IN_SCOPE' OR NOT in_scope`、`conclusion='IN_SCOPE' OR jsonb_array_length(reasons)>0`
  —— 应用层算错也在 DB 层被拒（测试里直接 INSERT 一条「没有原因的不合格判定」被拒）。
- **跨服务读不到 ⇒ `available=false` + 原因，不抛异常、不返回空集合**：把「读不到」折成空集合，
  界面会显示「这个品牌没有被授权过」，而真相是「我们没查到」。
- **同一维度上没有登记任何范围 = 该维度不限制**（授权只登记了国家而没登记店铺，
  意思是「这家店在哪个国家卖都行」，不是「这家店不许卖」）。
- **api-proxy 纯增量**：新增**一条独立 `services[]` 元素**（`prefix: 'channel/'` + 自己的 pattern），
  **不并进既有 channel 巨型正则**（并进去会让本车道与并行车道的插入点被隔开，git 判冲突）。
  动手前用脚本逐条核对**既有全部 100 条 elements 的正则**，对本模块 12 条路径**命中数为 0**。
  裸路径 `brand-authorizations` **刻意不放行**（后端没有「一条汇总列表接口」）——
  放行后端不存在的映射比 404 更糟。

## 文件清单

### 迁移（channel 服务，V248）

- `backend/services/channel/src/main/resources/db/migration/V248__brand_authorization.sql` —
  4 张新表 + 2 个守卫触发器 + 索引 + RLS(FORCE) + GRANT/REVOKE。**零 ALTER 任何既有表**。

### 后端（channel 服务）

- `BrandAuthorizationModels.java` — 传输模型（含 `BrandAuthorizationRegistrationInput` 与撞名说明）。
- `BrandAuthorizationRules.java` — **判定规则（纯函数）**：范围维度语义、期限三态、五种结论、
  逐条原因码、`expiryState` 五态、目标国家归一化。
- `BrandAuthorizationService.java` — 服务层：授权档案 CRUD（**无 DELETE**）、范围与文件整体替换、
  到期提醒（真筛选 + 真分页）、范围校验与刊登闸门、校验历史、候选、店铺。
- `BrandAuthorizationController.java` — 端点 `/channel/brand-authorizations/**`。
- `BrandAuthorizationSourcePort.java` — 跨服务只读端口（catalog + trade）。

### 测试（channel 服务）

- `BrandAuthorizationRulesTest.java` — **18 例**（纯函数）。
- `BrandAuthorizationIntegrationTest.java` — **19 例**（Testcontainers 真实 PostgreSQL 16 + 本地真 HTTP 上游）。
- `BrandAuthorizationTenancyIntegrationTest.java` — **3 例**（多租户三断言）。
- `BrandAuthorizationUpstreamStub.java` — 本地真 HTTP 上游（catalog + trade 同一台服务器）。

### 前端

- `apps/web/src/components/brand-authorization/brand-authorization-model.ts` — 契约类型 + API 出口 + 展示口径。
- `apps/web/src/components/brand-authorization/brand-authorization-model.test.ts` — **21 例**。
- `apps/web/src/components/brand-authorization/brand-authorization-workspace.tsx` — 工作区。
- `apps/web/src/app/compliance/brand-authorization/page.tsx` — 页面路由。
- `apps/web/src/app/api/channel/brand-authorization-allowlist.test.ts` — 白名单**双向**断言（5 例）。
- `apps/web/src/lib/api-proxy.ts` — **改动**：新增一条独立 `services[]` 元素（52 行插入、零删除）。

### 契约与台账

- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts` —
  codegen 产物，**纯增量**（+21 DTO / +11 路径）。catalog / trade 的漂移是别的车道的未再生物，**已还原**。
- `docs/product/capability-blueprint/module-progress.md` — **只改 16.4.2.1 那一行**的「状态」「证据」两格。

## 验证

- `./mvnw -q compile` ⇒ exit 0。
- `./mvnw -pl services/channel -am test -Dtest='BrandAuthorization*'` ⇒
  **Tests run: 40, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS**（18 + 19 + 3）。
- `pnpm typecheck` ⇒ **3/3 通过**。
- `pnpm vitest run apps/web/src/components/brand-authorization apps/web/src/app/api` ⇒
  **127 个文件 750 例全绿**（含 api-proxy 白名单全量回归）。

## 后续待办

1. **16.4.2.2 授权凭证**：本模块只登记文件引用与元数据。真伪验证记录 / 文件版本 / 签署方信息 /
   一键核验等它交付后接入，届时 `brand_authz_document` 的写入所有权可以移交过去。
2. **4.3.1.1 一键刊登接入**：拿到真实刊登入口后，直接消费本模块 `publish-gate` 的服务端结论即可，
   本模块一行不用改。
3. **16.4.1.2 检测与预警**：本模块的校验结论目前只落在校验历史里，不会自动推成预警工单。
   16.4.1.2 交付后接上。
4. **规则版本化治理（16.2.1.1）**：当前判定规则的口径是硬编码的（`CURRENT_RULE_VERSION = 1`），
   没有「哪一版规则、从何时生效」的来源。16.2.1.1 交付后把规则搬进规则库并让版本号从库里取。
