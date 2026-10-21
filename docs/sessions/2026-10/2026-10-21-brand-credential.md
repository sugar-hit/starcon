# 16.4.2.2 授权凭证（阶段 D7，L3 锚点 16.4.2）

- **日期**：2026-10-21
- **车道**：`feat/16-4-2-2`（worktree `integration/.worktrees/16.4.2.2`）
- **蓝图原文**（`docs/product/capability-blueprint/16-compliance-and-risk.md` 196-201 行）：
  > 职责：集中存储与核验授权凭证文件：真伪验证记录、文件版本、签署方信息，支持一键核验。
  > 输入：凭证上传；核验操作。
  > 输出：凭证档案 + 核验记录。
  > 关系：上游 = 16.4.2.1；下游 = 16.4.1.2。**凭证需保留原始文件与元数据**，争议时是唯一证据。

## 决策摘要

### 1. 🔴 与上游 16.4.2.1 的交接面：本模块最核心的决策

16.4.2.1 交付时明确留下三样东西（原话见
`docs/sessions/2026-10/2026-10-21-brand-authorization.md`）：

1. `brand_authz_document` **只存引用与元数据**（文件名 / 内容类型 / 字节数 / 存储句柄 /
   上传时刻 / 上传人），**文件本体不入库** —— 它当时的理由是「把 PDF / 扫描件塞进业务库
   会让这张表变成垃圾桶」。
2. 「真伪验证记录、文件版本、签署方信息、一键核验」四样它**明确没做**。
3. 「`brand_authz_document` 的写入所有权**可以**移交过来」。

本模块的决策是 **不移交**、两边**各自独立**。理由三条，缺一不可：

- **交接会打挂已交付模块**：移交意味着要 ALTER 16.4.2.1 的既有表（加 `version_no` /
  `sha256` / 签署方列），而**加 NOT NULL 列会直接打挂 16.4.2.1 已交付的 INSERT 路径**
  （老 INSERT 不写新列 ⇒ 必然违反 not-null 约束）。本仓纪律是「加列必须带 DEFAULT 或可空」。
- **概念上也不对**：16.4.2.1 的 `brand_authz_document` 是「授权档案下的一个文件**引用**」，
  本模块的 `brand_cred_document` 是「凭证文件的**本体 + 版本 + 摘要**」。合并成一张表会让
  「凭证版本」这种概念挂在授权下面。
- **凭证必须活得比它引用的东西更久**：授权被撤销 / 归档时，凭证作为争议时的唯一证据必须
  留下来 ⇒ 凭证侧**不建**指向 `brand_authz_*` 的外键，引用一律用**逻辑 id**。

⇒ 两边**不互相写**，也没有任何一处同时往同一张表写。

### 2. 🔴 文件本体的存储是本模块的责任（不是「什么都不做」）

蓝图加粗句「**凭证需保留原始文件与元数据**，争议时是唯一证据」把「存原件」落在本模块头上
（16.4.2.1 只登记引用，原件的归宿当时是空的）。

- 文件本体真写进**受管存储**（`LocalCredentialDocumentStorage`：本地文件系统 +
  路径穿越防护 + 键里带租户段，与 16.2.2.2 的 `LocalCertificationDocumentStorage` 同一手法）。
- 库里只存**句柄 + 文件名 + MIME + 字节数 + sha256 摘要 + 上传时刻**。
- **沿用** 16.4.2.1「二进制不进业务库」的判断（备份、迁移、RLS 审计都要为二进制付代价），
  但**「谁负责把文件放进去」由本模块承担**。

### 3. 🔴「真伪」的判据是文件摘要，不是文件名

「真伪验证」在企业场景里能做到的是：**把受管存储里的文件本体读回来，重算 sha256，
与登记时记下的摘要逐字比对**。

- 摘要不符 ⇒ 文件在登记之后被改过 ⇒ `DIGEST_MISMATCH`。
- 文件不在受管存储 ⇒ `FILE_MISSING`，**不是**「通过」。
- 这**不是**「连品牌方服务器验签」—— 那需要品牌方提供接口，不在本模块范围内；
  但它**本地可复算、可审计、争议时拿得出来**，判定过程与结论都落成不可改写的凭据。

### 4. 🔴「没核验过」≠「核验过但不通过」

`UNVERIFIED`（还没查）与 `FAILED`（查了没过）是**两个状态**，在概览、列表、筛选里都分开。
把「还没查」显示成「假的」是误伤，把「假的」显示成「还没查」是放过。

配套的一条：**「通过但有提醒」不是「不通过」**。签署方身份未人工核验
（`SIGNATORY_UNVERIFIED`）是 **WARN** 级 —— 姓名职务都登记了只是没核过身份，是常态，
算成不通过会让「一键核验」天天红。而**一个签署方都没登记**
（`SIGNATORY_INCOMPLETE`）才是 FAIL。

### 5. 🔴 结论八态 + 取最严重的那一条

`VERIFIED` / `DIGEST_MISMATCH` / `FILE_MISSING` / `EXPIRED` / `NOT_YET_EFFECTIVE` /
`SIGNATORY_INCOMPLETE` / `NO_DOCUMENT` / `REVOKED`。

一次核验可能同时发现好几条问题（文件不在 + 已过期 + 没签署方）。`conclusion` 取**最严重**的
那一条，其余作为逐条原因一并给出 —— 只报最严重的会让运营以为「修好这一条就没事了」。

### 6. 🔴 追加式凭据 + 规则有版本

- 核验记录 **append-only**：触发器 `guard_brand_cred_verification_immutable` 钉住
  conclusion / severity / passed / rule_version / evaluated_at / document_id 事后改写不了；
  另有 `guard_brand_cred_verification_no_delete` 不许删。
- `rule_version` 记在核验记录上：规则改了就是**新版本 + 新行**，旧行原样留着。
- 凭证文件版本**只追加**（`guard_brand_cred_document_append_only` 拒绝 UPDATE / DELETE）。
- **不通过必有逐条原因**：DB 层**具名** CHECK `brand_cred_verification_reasons_required` 兜底。
  （具名是有原因的：匿名 CHECK 的报错只会说 `violates check constraint ..._check2`，
  读报错的人根本不知道是哪条口径被违反 —— 这个是在测试失败时真踩到的。）

### 7. 🔴 当前版本与最近核验状态**不落库**，由读出口现算

存一列就要一个每日任务去维护它，而维护出来的那天与读出口算出的那天差一天时，
「这张凭证到底核验过没有」会答错 —— 而这个答案直接决定能不能用。列表页用 lateral 子查询
从 `brand_cred_document` / `brand_cred_verification` 现算。

### 8. 🔴 上游 16.4.2.1 是**同库只读**

16.4.2.1 与本模块**同属 channel 服务、同一个库**，所以 `brand_authz_authorization` 是**同库读**
（不是跨服务 SQL）。本模块**只读**它（拿「授权档案候选」），**不写**它。
候选清单里的 `note` 会写明来源与边界。

### 9. 表命名空间

本模块一律 `brand_cred_*`。**不复用** 16.4.2.1 的 `brand_authz_*`、16.2.2.2 的
`product_certification_*`、16.2.2.1 的 `compliance_check_*`、4.2.3.2 的 `listing_compliance_*`，
也不用裸名 `brand_authorization`（那是 catalog 3.2.1.2 的，在**另一个库**里）。

## 关键 trade-off

- **凭证不提供物理删除**（端点里没有 DELETE 凭证）：它是「争议时是唯一证据」的那份东西。
  撤销走 `POST /credentials/{id}/revoke`（**必须写明理由**），撤销是**终态**，不提供「恢复」。
- **文件本体不进业务库，但「存原件」是本模块的责任**：这两句不矛盾 ——
  前者是**存哪**，后者是**谁存**。16.4.2.1 当时把第二句留空了。
- **「真伪」只做到本地重算摘要**：不连品牌方系统（那要对方接口）。
  代价是「对方确实签过这件事」仍需线下确认；收益是这条链路**现在就能跑、就能审计**。
- **api-proxy 纯增量**：新增**一条独立 `services[]` 元素**（`prefix: 'channel/'` + 自己的
  pattern），**不并进既有 channel 巨型正则**（并进去会让本车道与并行车道的插入点被隔开，
  git 判冲突；取错一侧还会静默删掉别人已交付的路由）。
  动手前逐条核对了**既有全部 services[] 元素的正则**，对本模块 14 条路径命中数为 0。
- **`documents/{id}` 与 `documents/{id}/file` 后端实现了、白名单放行了，但页面不调它们** ——
  浏览器同源代理**不透传二进制**（只透传 text/csv、text/vnd.zpl、text/html），
  页面上的文件清单显示**元数据 + sha256 摘要**。这两条是给直连的归档任务 / 争议取证用的。

## 文件清单

### 迁移（channel 服务，V255）

- `backend/services/channel/src/main/resources/db/migration/V255__brand_authorization_credential.sql` —
  4 张新表 + 4 个守卫触发器 + 索引 + RLS(FORCE) + GRANT/REVOKE。**零 ALTER 任何既有表**。

### 后端（channel 服务）

- `BrandCredentialModels.java` — 传输模型（入参 `BrandCredentialRegistrationInput` 带撞名说明）。
- `BrandCredentialRules.java` — **纯函数规则层**：摘要、结论严重度排序、到期状态、
  存储键与文件名净化、全部文案口径。
- `BrandCredentialService.java` — 服务层：档案 CRUD（无 DELETE）、签署方、文件版本（只追加）、
  **一键核验**（重算 sha256）、批量核验、核验记录、上游候选、概览。
- `BrandCredentialController.java` — 端点 `/channel/brand-credentials/**`。
- `CredentialDocumentStorage.java` + `LocalCredentialDocumentStorage.java` — 存储端口与本地实现。

### 测试（channel 服务）

- `BrandCredentialRulesTest.java` — **19 例**（纯函数）。
- `BrandCredentialIntegrationTest.java` — **22 例**（Testcontainers 真实 PostgreSQL 16）。
- `BrandCredentialTenancyIntegrationTest.java` — **3 例**（多租户三断言）。

### 前端

- `apps/web/src/components/brand-credential/brand-credential-model.ts` — 契约类型 + API 出口 + 展示口径。
- `apps/web/src/components/brand-credential/brand-credential-model.test.ts` — **24 例**。
- `apps/web/src/components/brand-credential/brand-credential-workspace.tsx` — 工作区。
- `apps/web/src/app/compliance/brand-credential/page.tsx` — 页面路由。
- `apps/web/src/app/api/channel/brand-credential-allowlist.test.ts` — 白名单**双向**断言（5 例）。
- `apps/web/src/lib/api-proxy.ts` — **改动**：新增一条独立 `services[]` 元素（零删除）。

### 契约与台账

- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts` —
  codegen 产物，**纯增量**（+14 路径 / +20 DTO，零丢失）。catalog / trade 的漂移是别的车道的
  未再生物，**已还原**。
- `docs/product/capability-blueprint/module-progress.md` — **只改 16.4.2.2 那一行**的「状态」「证据」
  两格（+ `sync.mjs` 自动维护的四个汇总计数）。

## 验证

- `./mvnw compile` ⇒ **BUILD SUCCESS**。
- `./mvnw -pl services/channel -am test -Dtest='BrandCredential*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ **Tests run: 44, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS**（19 + 22 + 3）。
- `pnpm typecheck` ⇒ **3/3 通过**。
- `pnpm vitest run apps/web/src/components/brand-credential apps/web/src/app/api/channel`
  ⇒ **45 个文件 233 例全绿**（**含 api-proxy 白名单全量回归** —— 本车道改了共享的
  `api-proxy.ts`，按纪律必须跑全量 allowlist 回归）。
- `pnpm --filter @starcon/web lint` ⇒ 无新增告警；`prettier --check` ⇒ 全部符合风格。
- `node tools/module-progress/sync.mjs --check` ⇒ ✓ 台账与蓝图一致。

## 后续待办

1. **16.4.1.2 检测与预警**：本模块的核验结论目前只落在核验记录里，不会自动推成预警工单。
   16.4.1.2 交付后接上。
2. **16.2.1.1 合规规则库**：当前核验规则的口径是硬编码的（`CURRENT_RULE_VERSION = 1`），
   没有「哪一版规则、从何时生效」的来源。16.2.1.1 交付后把规则搬进规则库并让版本号从库里取。
3. **品牌方侧验签接口**：「真伪」目前是本地重算 sha256。品牌方若提供验签接口，
   那是**能力增强**，不是修 bug —— 现在这条链路已经能用、已能审计。
4. **与 16.4.2.1 的进一步联动**：16.4.2.1 的刊登闸门（`publish-gate`）目前只看**授权范围**，
   不看**凭证是否核验通过**。等两边都在生产跑一段时间、确认「凭证未核验 ⇒ 是否该阻断刊登」
   这个口径之后，再决定是否让 `publish-gate` 读本模块的最近核验结论 ——
   本车道**刻意没有**去改 16.4.2.1 的代码（那是别的模块的交付物）。
