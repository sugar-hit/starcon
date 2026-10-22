# 2026-10-23: 16.2.1.1 规则库（阶段 D7）

## 摘要

本轮实现能力蓝图模块 **16.2.1.1「规则管理」**（蓝图标题「规则库」；卷 16 合规与风控，L3 锚点 16.2.1 合规规则），
落在 **channel 服务**（与同卷 16.1.1.1 / 16.1.1.2 / 16.1.2.1 / 16.2.2.1 / 16.2.2.2 / 16.4.2.1 同服务同库）。

蓝图原文：「管理合规规则库：规则类型（违禁词、极限词、侵权词、认证要求、禁限售、标签要求、材质要求）、
严重度（阻断/警告/提示）、适用平台与站点、生效时间与版本。输入：规则定义与词库上传；版本更新；适用范围配置。
输出：规则集 + 生效版本。关系：上游 = 平台政策同步 + 人工维护；下游 = 16.2.1.2 执行、4.2.3.2 预检。
**规则版本化是关键**：政策变化时历史商品的判定结果要可解释，不能因为规则库更新就说过去的商品当时违规。」

产出：迁移 `V311__channel_compliance_rule.sql`（4 张新表、零 ALTER）、
`ComplianceRule{Models,Rules,Service,Controller}.java`、契约重新生成（+11 路径 / +28 DTO，零删除零改字段）、
前端路由 `/compliance/rules` 真实工作区、api-proxy 新增一条独立白名单元素。

## 决策 / 结论

1. **🔴🔴 蓝图加粗句「规则版本化是关键」落成六条物理事实**（不是文档里一句话）：
   ① **改规则 = 新增一个版本** —— 本模块**没有**「改词」「改严重度」的入口；
   ② 版本内容（severity / title / description / effective_from / version_no）**不可改写**
   （触发器 `guard_compliance_rule_version_immutable`），唯一例外是 `effective_to`
   **只允许向前关闭**（NULL → 某时刻，且不得早于当前时刻 —— 回溯关闭 = 改写历史）；
   ③ **生效时刻不许回填**（触发器 `guard_compliance_rule_version_window` 拒绝早于 `now()` 的
   effective_from）—— 回填会让「当时生效的那一版」变成今天这一版，历史判定就再也解释不清；
   ④ **同一时刻只能有一个版本生效** —— 新版本落库时触发器把上一版的窗口自动关闭到新版的生效时刻，
   并拒绝任何**重叠**窗口；
   ⑤ **版本号必须连续**（同一规则内从 1 递增，唯一键 + 触发器双重钉住）；
   ⑥ **词条与适用范围属于版本** —— 版本生效后锁死，改一个词 = 新增一个版本，词条与适用范围**不可删除**。

2. **🔴 严重度落在版本上、不落在规则上** —— 这是本模块最要紧的一条归属判断。
   规则身份只有：编码（租户内唯一，**幂等锚点**）+ 类型（类型是**规则的身份**，改类型 = 另一条规则）
   + 生命周期（在用 / 已停用）。严重度、标题、说明、词条、适用范围、生效时间**全在版本上**。
   理由：严重度会被政策调整，若它挂在规则上，旧版本绑定的判定就会读到**今天**的严重度 ——
   而蓝图加粗句要的正是「当时那一版有多严重」答得出来。

3. **🔴 值域一律由 DB 层 CHECK 钉死**（值域要可审阅，不只靠应用层 enum）：
   规则类型 = 蓝图点名的**七种**、严重度 = **三种**、生命周期两态、词条分类四档。

4. **🔴 词库是多词条，且词条属于版本** —— 违禁词 / 极限词 / 侵权词三类规则带词条集合；
   另外四类（认证要求 / 禁限售 / 标签要求 / 材质要求）**不带词条**，
   触发器 `guard_compliance_rule_term_ref` 钉死这一点（它们约束的是「有没有这份认证 /
   这个品类能不能卖 / 标签上要写什么 / 材质是什么」，不是一个词表）。
   「词库上传」是真批量：每行一个词，空行与 `#` 注释行忽略，与已有词重复的跳过。

5. **🔴 适用平台与站点从本服务自己的目录读** —— `platform` / `marketplace` / `shop` 都是
   channel 自己库里的表（V1），**不复制目录表、不造平行平台表、不写死店铺常量**。
   适用范围一行 = 适用平台（必填）+ 适用站点（NULL = 该平台全部站点）+ 可选收窄到具体店铺
   （NULL = 该平台/站点下全部店铺）；平台/站点/店铺的自洽性由触发器校验（站点必须属于所选平台、
   店铺必须属于所选平台且在本租户店铺目录里）。**零外键**（与 V300 / V304 / V308 同一纪律）。

6. **🔴 店铺维度显式 + data_scope 收敛** —— 规则身份 / 版本 / 词库是**租户级**资产（isolation 即可）；
   **适用范围是店铺维度的资产** ⇒ 带 RESTRICTIVE 的 `data_scope` 策略：
   显式点名店铺时按那家店收敛（`channel_scope(operation, owner, dept)`）；
   平台 / 站点级范围只有**租户级（ALL）角色**持有（`channel_scope(operation, NULL, NULL)`，
   与 V1 `credential_scope` 同一手法）—— 「适用于整个 Amazon 平台」是一条**宽口径**设置，
   不该由一个只管自己店的人来定。

7. **🔴 下游未交付一律不编造** ——
   **16.2.1.2 拦截执行未交付** ⇒ 不建拦截表、不造指向不存在入口的放行形状，
   API 与页面里**没有**「立即校验 / 放行 / 豁免」这类入口；
   **02 平台政策适配器未交付** ⇒ 输入只有「人工维护」这一条真实路径；
   **4.2.3.2 合规预检已交付**（同服务 `listing_compliance_*`）但用的是**它自己的租户级词表**，
   **不是**本模块的受版本治理规则库 —— 两边**互不改动对方的表**（边界写在两侧的迁移文件头）。
   三条缺口在能力声明里是**可查的事实**（available=false + ownerModule + 影响说明）。

8. **🔴 表命名空间隔离** —— `compliance_rule_*` 与 `platform_violation_*` / `platform_penalty_*`（V308）、
   `account_health_*`（V300）、`account_health_performance_*`（V304）、`compliance_check_*`（V197）、
   `listing_compliance_*`（V198）、`product_certification_*`（V247）、`brand_authz_*`（V248）
   七个命名空间彼此隔离。

## 关键 trade-off

- **「版本内容不可改写」与「运营想改一个错别字」冲突** —— 选前者。
  代价是：已生效版本的词永远改不掉，只能发新版本；未生效（将来生效）的版本在**同一笔事务内**
  仍可补齐词条与适用范围（触发器 `compliance_rule_version_editable` 放行「created_at = now()」
  这一种情况）。这是刻意的：**版本一旦生效就冻结**，否则「当时那一版」会被事后编辑，历史判定失去意义。
- **「平台级适用范围」的数据范围口径** —— 选「只有 ALL 角色能持有」。
  另一种做法是「平台上只要有一家可见的店铺就可见」，但那样一个 SELF 范围的角色会**看不到**
  自己负责店铺之外的平台级范围，界面上会出现「这条规则看起来没有适用范围」的错觉；
  再一种做法是「租户内一家店都没覆盖到的范围对全租户可见」，但那需要绕过 `shop` 表的 RLS
  才能区分「租户没有店」与「我没权限看」—— 代价远大于收益。
- **平台级范围与店铺级范围并存** —— 保留两者。
  平台级 = 一次覆盖整个平台；店铺级 = 精确到某一家店。代价是适用范围表有两种行，
  页面与查询都要按「NULL = 全部」解释（已写进 `scopeText` 与能力声明）。

## 文件清单

### 后端（新增）

- `backend/services/channel/src/main/resources/db/migration/V311__channel_compliance_rule.sql`
  —— 4 张新表 + 9 个触发器 + RLS（isolation ×4 + data_scope ×1）+ GRANT/REVOKE，**零 ALTER 既有表**。
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceRuleModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceRuleRules.java`
  —— 值域唯一真相源（与 DB CHECK 逐字一致）。
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceRuleService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceRuleController.java`
  —— 16 个端点。

### 后端测试（新增）

- `backend/services/channel/src/test/java/com/starcon/channel/ComplianceRuleRulesTest.java`（5 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ComplianceRuleIntegrationTest.java`（21 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ComplianceRuleTenancyIntegrationTest.java`（3 例）

### 前端（新增）

- `apps/web/src/components/compliance-rule/compliance-rule-model.ts`
- `apps/web/src/components/compliance-rule/compliance-rule-model.test.ts`（7 例）
- `apps/web/src/components/compliance-rule/compliance-rule-workspace.tsx`
- `apps/web/src/app/compliance/rules/page.tsx`
- `apps/web/src/app/api/channel/compliance-rules-allowlist.test.ts`（5 例）

### 前端（改动）

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立的 `services[]` 元素**
  （首 token `compliance-rules`，插入锚点固定在 `performance-metrics` 那条**之后**；
  既有条目**一字未动、也不重排**；首 token 零命中核对：既有全文出现次数 = 0，
  既有全部 **160 条**正则 × 本模块 11 个 shape ⇒ 命中 **0** 处）。

### 契约与台账

- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts`
  —— 由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成，
  **非手改**；逐项比对确认**只多出本模块的 11 条路径与 28 个 DTO、零删除、零改字段**；
  identity / catalog / warehouse / trade / crm **零漂移**。
- `docs/product/capability-blueprint/module-progress.md` —— **只改本模块那一行**的「状态」「证据」两格
  （未开发 → 已交付）；总览计数由调度者在批次并入时统一刷新（与前批各车道的做法一致）。
- `docs/sessions/2026-10/2026-10-23-compliance-rule-library.md` —— 本文件。

## 踩坑记录

- **🔴 plpgsql 变量名不能叫 `overlaps`** —— 那是 PostgreSQL 的区间重叠运算符关键字，
  `IF overlaps > 0 THEN` 会被解析成「OVERLAPS 运算符后面跟一个 >」⇒ 建函数时报
  `syntax error at or near ">"`（报错位置指向函数体内部，容易误判成 SQL 写错）。
  改名 `overlap_count` 后通过。
- **🔴 同一段 WHERE 不能只在一处查询里带上 FROM** —— 规则清单的「计数 / 分组统计」与「分页明细」
  共用同一份 where，但计数那条最初**漏了** `left join lateral`（当前生效版本），
  于是 where 里的 `v.title` / `v.id` 引用了一张不存在的表 ⇒ BadSqlGrammarException
  ⇒ 被 channel 的通用错误处理器翻成 **409**（不是 500），看起来像「数据冲突」而不是「SQL 写错」。
  教训：带 lateral join 的查询，**计数与明细必须共用同一个 FROM 片段**。
- **🔴 前端模板串里再嵌模板串会让白名单回归脚本抽不到路径** ——
  `fetchEffectiveVersion` 原来写成 `` `${BASE}/.../effective${suffix ? `?${suffix}` : ''}` ``，
  白名单测试用 `` /`([^`]*)`/g `` 反向抽真实调用点时会被内层反引号截断 ⇒ 抽不到这条路径。
  改成「路径与查询串分开拼」后通过。**这条对所有模块都适用**：模板串里别再嵌模板串。

## 验证

- `./mvnw -q compile`（backend）—— exit 0。
- `./mvnw -pl services/channel -am test -Dtest='ComplianceRule*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  —— **29 例 0 失败 0 错误，BUILD SUCCESS**（21 集成 + 5 值域单测 + 3 租户隔离）。
- `pnpm typecheck` —— 3/3 通过。
- `pnpm vitest run apps/web/src/app/api` —— **185 文件 1067 例全绿**（改了 api-proxy ⇒ 全量 allowlist 回归）。
- `pnpm vitest run apps/web/src/components/compliance-rule apps/web/src/app/api/channel/compliance-rules-allowlist.test.ts`
  —— 13 例全绿。
- `pnpm exec eslint`（本模块文件 + api-proxy.ts）—— 零告警。

## 后续待办

- **16.2.1.2 拦截执行**（下游，未交付）：接上之后由那一侧读本模块的**生效规则集**
  （`GET /channel/compliance-rules/effective?at=...`），并把判定结果**绑定到那一个版本 id**
  —— 本模块的版本不可改写保证了这层绑定可解释。
- **02 平台政策适配器**（上游，未交付）：接上之后平台政策的变化仍然是**新增一个版本**，不是原地改词；
  本模块的 API 已按「版本化」设计，接入时不需要改数据模型。
- **4.2.3.2 合规预检**（已交付但用自有词表）：两边目前**各管各的**。
  若将来要让预检用本模块的受版本治理规则库，那是一次**跨模块的口径统一**，
  需要先决定「预检的判定结果绑定到哪个版本」，再动 4.2.3.2 那一侧的表 —— 本车道**刻意没做**。
- 台账总览计数（已交付 328→329、未开发 137→136）由调度者在批次并入时统一刷新。
