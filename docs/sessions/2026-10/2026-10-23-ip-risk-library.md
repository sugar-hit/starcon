# 2026-10-23: 16.4.1.1 侵权库（阶段 D7）

## 摘要

本轮实现能力蓝图模块 **16.4.1.1「侵权库」**（卷 16 合规与风控，L3 锚点 16.4.1 知识产权），
落在 **channel 服务**（端口 8083，DB 角色 `channel_app`）。

蓝图原文：「维护知识产权风险库：品牌商标、专利、版权、肖像权，登记风险等级与适用平台。
输入：侵权条目登记；批量导入。输出：侵权库记录。关系：上游 = 3.3.2 品牌；下游 = 16.4.1.2 检测。
**侵权检测是刊登前拦截而非事后申诉**，成本差一个数量级。」

产出：迁移 `V315__channel_ip_risk_library.sql`（5 张新表、零 ALTER）、
`IpRiskLibrary{Models,Rules,Service,Controller,SourcePort}.java`、契约重新生成
（+9 路径 / +23 DTO，零删除零改字段）、前端路由 `/compliance/ip-risk` 真实工作区、
api-proxy 新增一条独立白名单元素（首 token `ip-risk-library`）。

## 决策 / 结论

1. **🔴🔴 权利类型与风险等级都是受控值域，且由 DB 层 CHECK 钉死**：
   权利类型四种（`TRADEMARK` / `PATENT` / `COPYRIGHT` / `IMAGE_RIGHT`），
   风险等级三级（`HIGH` / `MEDIUM` / `LOW`）。
   **不**留自由文本权利类型 —— 「商标」/「TRADEMARK」/「商标类」会被检测侧当成三种权利。
   **不用 0..100 的裸数字风险等级** —— 裸数字没有口径，下游拿到的「72 分」没法解释成
   高风险还是中风险，排序与筛选也无从统一。

2. **🔴 适用平台是多对多，不是单列** —— 一条侵权条目常常在多个平台上都要拦。
   落在独立表 `ip_risk_entry_platform`；平台编码**直接读本服务自己的 `platform` 目录**
   （全局码表，AMAZON / SHOPIFY / EBAY / TIKTOK_SHOP / TEMU / WALMART …），
   **不复制目录表、不造平行平台表、不写死平台常量**；存在性由**触发器**校验（**零外键**，
   与 V300 / V304 / V308 / V311 同一纪律：FK 会打破同服务既有测试的 TRUNCATE 纪律）。
   ⇒「这个平台上有哪些高风险条目」可反查（列表筛选 + `GET /platform-summary` 汇总）。

3. **🔴 匹配特征是本模块的关键交付**（16.4.1.2 检测要拿它比对商品标题/描述/图片/品牌字段）：
   条目必须带**可被匹配的字段** —— `brand_name`（**可空**：有些专利/版权没有品牌名）、
   `ip_risk_match_term` **多值**关键词/短语集合、`match_pattern`（可选的标识符/外观特征描述，
   如商标注册号、版权作品名）。**本模块只登记这些特征，不执行任何比对**。

4. **🔴 批量导入是真入库 + 行级结果 + 幂等 + 部分失败不回滚**：
   一次导入返回**成功行数 / 跳过行数 / 失败行数 + 逐行错误**（哪一行、哪个字段、为什么），
   结果**落库**（`ip_risk_import_batch` / `ip_risk_import_row`，append-only，触发器钉死不可改写）——
   「整个文件成功 / 失败」二选一不够用。
   **幂等**靠 `UNIQUE(tenant_id, entry_code)` 唯一约束兜底（**不靠「先查后插」的竞态写法**），
   重复导入命中冲突 ⇒ 记「跳过」且**不改写既有内容**。
   **部分失败不回滚已成功的行**：服务层**逐行 savepoint** 提交，一行 DB 报错只回滚那一行。

5. **🔴 生命周期只有启用 / 停用**：停用**必须写理由**（DB 层 CHECK 钉死），
   停用 = 不再参与未来的检测但**历史记录保留**；停用后条目内容**冻结**
   （服务层 422 + DB 触发器双拒，要改内容先重新启用）；**不做物理删除**
   （`ip_risk_entry` 上 DELETE 被触发器挡 —— 引用它的其他模块可能已经引用过这一行）。

6. **🔴 跨服务只经授权 HTTP API 读**：3.3.2 品牌（catalog 服务 8082，`GET /catalog/brands`），
   **转发调用方自己的 Authorization bearer**、**绝不传租户号头**（租户由上游按已认证成员关系解析，
   两道 RLS）。**读不到就显式说读不到**（`available=false` + 人话原因），
   **绝不返回空集合冒充「上游一个数据都没有」**。落库只存品牌**逻辑 id**（`brand_id`），
   **零跨库 FK、零影子表、零跨服务 SQL**。

7. **🔴 下游 16.4.1.2 检测与预警未交付** ⇒ 本模块**只做「库 + 登记 + 批量导入 + 查询」**：
   **不实现检测逻辑、不产出任何检测结论、不建检测结果表**；页面与 API 里**没有**
   「立即检测 / 放行」这类入口（放行一个不存在的入口比 404 更糟）；
   能力声明里逐条列 `available=false` + `ownerModule` + 影响说明。
   16.4.2.1 / 16.4.2.2 已交付但**与本模块是两件事**（管「授权」vs 管「风险条目」）⇒ **零 FK**；
   4 刊登已交付，但刊登前检测归 16.4.1.2，本模块**不越界**做拦截。

8. **表命名空间隔离**：本模块表前缀 `ip_risk_*`，与 `brand_authz_*`（16.4.2.1）、
   `brand_cred_*`（16.4.2.2）、`compliance_rule_*`（16.2.1.1）、
   `product_certification_*`（16.2.2.2）、`epr_*`（16.3.2.2）、
   `listing_compliance_*`（4.2.3.2）**彼此隔离**；**零 ALTER 任何既有表**。

## 文件清单

### 后端（新增）

- `backend/services/channel/src/main/resources/db/migration/V315__channel_ip_risk_library.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/IpRiskLibraryModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/IpRiskLibraryRules.java`
- `backend/services/channel/src/main/java/com/starcon/channel/IpRiskLibraryService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/IpRiskLibraryController.java`
- `backend/services/channel/src/main/java/com/starcon/channel/IpRiskLibrarySourcePort.java`
- `backend/services/channel/src/test/java/com/starcon/channel/IpRiskLibraryRulesTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/IpRiskLibraryIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/IpRiskLibraryTenancyIntegrationTest.java`
- `backend/services/channel/src/test/java/com/starcon/channel/IpRiskLibraryUpstreamStub.java`

### 前端（新增 / 改动）

- `apps/web/src/components/ip-risk-library/ip-risk-library-model.ts`
- `apps/web/src/components/ip-risk-library/ip-risk-library-model.test.ts`
- `apps/web/src/components/ip-risk-library/ip-risk-library-workspace.tsx`
- `apps/web/src/app/compliance/ip-risk/page.tsx`
- `apps/web/src/app/api/channel/ip-risk-library-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立的 `services[]` 元素**
  （首 token `ip-risk-library`，插入锚点固定在 `cart-recovery`（15.6.2.2）元素**之后**；
  既有条目**一字未动、也不重排**；首 token 在既有全文出现次数 = **0**）。

### 契约与台账

- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts`
  —— 由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成，**非手改**；
  channel **纯新增 9 条路径与 23 个 DTO、零删除、零改字段**；
  identity / catalog / warehouse / trade / crm **零漂移**。
- `docs/product/capability-blueprint/module-progress.md` —— **只改本模块那一行**的「状态」「证据」两格
  （未开发 → 已交付）；总览计数由调度者在批次并入时统一刷新（与前批各车道的做法一致）。
- `docs/sessions/2026-10/2026-10-23-ip-risk-library.md` —— 本文件。

## 验证

- `./mvnw -q compile` —— 退出 0。
- `./mvnw -pl services/channel -am test -Dtest='IpRiskLibrary*' -Dsurefire.failIfNoSpecifiedTests=false
-DfailIfNoSpecifiedTests=false` —— **22 例 0 失败 0 错误**
  （`IpRiskLibraryIntegrationTest` 13 / `IpRiskLibraryTenancyIntegrationTest` 3 /
  `IpRiskLibraryRulesTest` 6）。
- `pnpm typecheck` —— 3/3 通过。
- `pnpm lint` —— 通过（本车道零告警）。
- `pnpm vitest run apps/web/src/components/ip-risk-library/ip-risk-library-model.test.ts
apps/web/src/app/api/channel/ip-risk-library-allowlist.test.ts` —— **16 例全绿**。
- **全量 allowlist 回归** `pnpm vitest run apps/web/src/app/api` —— **189 文件 1089 例全绿**
  （确认白名单没挤掉兄弟车道路径）。

## 踩坑记录

- **🔴 契约 JSON 的默认 git diff 看着像「整文件重写」，其实只是 Myers diff 的噪声** ——
  `git diff --stat packages/api-contracts/src/channel.openapi.json` 显示 48541 行变化，
  换 `--patience` / `--histogram` 后真实差异是 **1203 行纯新增、零删除**。
  判据应当是**语义比对**（`json.load` 后比 `paths` / `components.schemas` 的键集合与值），
  不是看 git 的行数。**这条对所有车道都适用**：codegen 产物先做语义比对再进 diff。
- **🔴 逐行 savepoint 路径要有测试兜底** —— 只测「本地校验失败」的导入用例**测不到**savepoint 回滚分支（那一行根本没碰 DB）。
  补了一条**超长匹配特征（> varchar(200)）**的用例：那一行在 DB 层报错 ⇒ 回滚到该行 savepoint ⇒ 只那一行 FAILED，已成功的两行**留在库里**。
- **🔴 PostgreSQL 一行出错会把整笔事务打成 aborted** —— 批量导入里某一行触发约束冲突后，
  后续所有语句都会失败（直到 ROLLBACK TO SAVEPOINT）。
  ⇒ 逐行 savepoint 不是「性能优化」，是**部分失败不回滚**能不能真正成立的前提。
- **🔴 `noUncheckedIndexedAccess` 会打穿数组下标** —— 前端解析导入文本时
  `const [entryCode, rightType, ...] = cells` 出来的四项全是 `string | undefined`，
  直接塞进 `rows.push({...})` 会报 TS2322；改成带默认值解构
  （`const [entryCode = '', ...] = cells`）才过。测试里同理，数组下标要 `?.` 或先解构。

## 后续待办

- **16.4.1.2 检测与预警**（未交付）：交付后由那一侧来读本模块的**条目与匹配特征**
  （品牌名 / 关键词短语集合 / 标识符特征），产出检测报告与预警。本模块**不预建**检测结果表。
- **外部商标 / 专利查询**：蓝图把它放在 16.4.1.2 的职责里（接入外部商标/专利查询），
  本模块**不编造**一个假的外部查询入口；真要接外部源时，走授权 API + 只读端口的同一手法。
- **4 刊登的刊登前拦截**：归 16.4.1.2 / 16.2.1.2，本模块**不越界**做拦截。
