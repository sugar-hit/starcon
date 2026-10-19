# 4.2.3.2 合规预检（违禁词/侵权）（D5 阶段，L3 锚点 4.2.3）

> 车道 `feat/4-2-3-2`，worktree `.worktrees/4.2.3.2`，基线 `integration/module-dev` 的 `fbad4286`。
> 落地服务：**channel**；迁移：`V198__listing_compliance_precheck.sql`；页面：`/listings/compliance`。

## 决策摘要

蓝图 §4.2.3.2 的职责句列了六个维度，输出句要求风险清单带四要素，关系句只加粗了一句。
真正决定这个模块形态的是那一句加粗与那四要素，所以本模块的第一件事不是写扫描逻辑，
而是**先把「拦截在服务端」与「四要素缺一不可」变成结构**：

1. **阻断项在服务端强制校验做成两道**：

   - 服务层 `ListingCompliancePrecheckService.publishGate(draftId)` 真算一遍，
     有未修复的阻断项就返回 `allowed=false` + 逐条阻断项清单。
   - DB 层给 `listing_editor_submission` 加一条 BEFORE INSERT 触发器
     `guard_listing_editor_submission_compliance` —— 草稿的最近一次扫描里只要还有
     `status='OPEN'` 的 BLOCKING 风险，**入队这一步就被数据库直接拒**（SQLSTATE 23514）。
     于是「绕过前端直接调 4.2.1.1 的提交端点」这条路上也有拒绝证据。

   页面把阻断项和提醒项分区显示，但**分区 ≠ 拦截** —— 这一点在能力声明、页面横幅与
   代码注释里都写死了，避免半年后有人把前端的红色当成防线。

2. **风险清单四要素在 DB 层双重钉死**：`severity` / `risk_level` / `status` 走 CHECK；
   `field_key`（命中位置）/ `rule_source`（规则来源）/ `suggestion`（建议替换）
   走 CHECK + 触发器 `guard_listing_compliance_risk_completeness`（空串也拒）。
   「命中位置要能定位到具体字段」在数据上落成三件套：
   `field_key` + `field_label` + `matched_text` + `occurrence_index`（列表字段里第几项）。

3. **「不知道」是一个状态，不是一个 0**：

   - 认证缺失（16.2.2.2 未交付）恒为 `UNKNOWN` + `ownerModule=16.2.2.2`，
     **不计入已判定维度的分母、也不算通过**。
   - 结论因此是三态：`PASS`（无阻断项且全部维度已判定）/ `BLOCKED`（有阻断项）/
     `INCOMPLETE`（当前没有已知阻断项，但有维度没量到 ⇒ **不能说「合规通过」**）。
   - `state`（覆盖度）与 `conclusion`（判定）在 CHECK 里互锁，写错口径 DB 层就拒。

4. **可验证的违规才判阻断项**：词真的出现在这一栏、宽度真的小于平台规格、文件真的不在了、
   授权真的没有 ⇒ BLOCKING；无法验证的疑虑（白底未处理、授权快到期、缺替代文本）⇒ WARNING。
   拿不到证据就不该拦下别人的货。

5. **跨服务只经授权 HTTP API 读**：`ListingComplianceSourcePort` 照抄 4.2.1.1 的
   `EditorSourceQueryPort` 写法（但不改动它），转发调用方自己的 bearer，
   租户仍由上游按已认证成员关系解析 ⇒ **两道 RLS**。
   每个方法返回 `Upstream(available, reason, value)`：**读不到就显式说读不到**，
   绝不返回空集合冒充「上游一个数据都没有」 —— 在合规预检里把「查不到」当成「没违规」
   等于把侵权商品放行，是本模块最贵的错误。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 扫描的内容来源 | **编辑器草稿 `listing_editor_draft`**（4.2.1.1） | 蓝图说的是「发布前扫描内容」，要检的是「即将发出去的那一份」，不是线上已经发出去的。草稿按 平台+站点+语言 分行保存，扫描口径与编辑口径一致 |
| 阻断项拦在哪 | **服务层闸门 + DB 触发器（双保险）** | 只做服务层 = 有人绕过前端调端点就过去了；只做 DB 触发器 = 上层拿不到「为什么被拦」的清单。两道都要 |
| 没扫描过要不要拦 | **不拦**，但闸门显式返回 `state=NOT_SCANNED` | 把「还没扫过」当成「不合规」会让整个刊登链一步都走不动，那不是蓝图的意思。真要拦住是 4.3.1.1 派发时的策略选择，不是预检模块的 |
| 租户违禁词库 vs 合规规则库 | **本模块自己建 `listing_compliance_word`（租户级词表）**，不建规则库 | 16.2.1.1 的规则库是**受版本治理**的平台规则（哪一版、何时生效、出处），与运营自己维护的「我们卖这个品类不能出现哪些词」是两回事。边界写在迁移文件头注释里 |
| 白底 / 水印怎么判 | **提醒项，不是阻断项** | 素材本身可能就是白图，只是没有处理记录 —— 拿不到证据就不该拦。白底/水印的**规格**来自租户登记的该平台图片处理配方（`GET /catalog/media/processing/recipes`） |
| 图片尺寸怎么判 | **阻断项** | 宽高是素材自己的可验证事实，与目标平台配方的目标尺寸一比即得 |
| 平台类目必填的「没绑内部属性」 | **阻断项 + ownerModule=4.2.3.1** | 4.2.3.1 的台账写明「`requirement='REQUIRED' AND internal_attribute_id IS NULL` 的 partial index **就是为 4.2.3.2 预检的阻断项清单而建**」。运营配不出这条规则、只能等刊登被驳回才知道自己缺一个属性 |
| 词命中怎么匹配 | **大小写不敏感 + ASCII 词要求词边界** | `best` 不该命中 `bestseller`；中文没有词边界，按子串匹配 |
| 扫描的计数与结论 | **由 DB 触发器从风险行算出来** | 应用层把 `blocking_count` 填错 ⇒「界面说 3 条阻断项、库里其实 5 条」，而发布闸门读的就是这组数 |
| 表命名空间 | **`listing_compliance_*`** | 同批 16.2.2.1 占 `compliance_check_*`、未来 16.2.1.1 占 `compliance_rule*`。用裸 `compliance_*` 必撞 |
| 词表唯一约束 | **表达式唯一索引**（`coalesce(platform_code,'')`） | PG 的 UNIQUE 把 NULL 视作互不相等，那样「全平台 + 全语言」能插进去无数条 |
| 权限码 | **复用 `channel.operation.*`** | 新增要动 identity 的权限真相源，是别的车道的事 |
| 契约 schema 名 | 全部 `ListingCompliance*` 前缀 | springdoc 用 record 的**简单类名**做 `components.schemas` 的键，重名会静默覆盖别人的契约 |
| api-proxy 白名单 | **新增一条独立 `services[]` 元素**，前缀 `listing-compliance-precheck` | 既有 channel 巨型正则那行被多车道共用，同时改必然撞车。`compliance-precheck` 是同批 16.2.2.1 车道的前缀，**不能碰** |
| 前端要不要重算风险 | **不重算，原样显示** | 两份算子必然漂移，漂移之后「界面说没事、服务器说有事」，运营就不知道该信谁。前端只做分区显示与跳转 |

## 未交付的上游（如实标注，不假装有）

| 上游 | 状态 | 本模块怎么表达 |
| --- | --- | --- |
| 16.2.1.1 合规规则库 | 未交付 | 能力声明 `platform_rule_library` available=false + ownerModule=16.2.1.1；**不建规则库表**，本模块只做扫描与出清单；租户违禁词库是本模块自己的东西 |
| 16.2.2.2 认证与资质 | 未交付 | 能力声明 `certification` available=false + ownerModule=16.2.2.2；认证维度恒 UNKNOWN，**不计入分母、也不算通过** |
| 16.2.2.1 合规预检（体检报告） | **同批并行车道，此刻尚不存在** | 能力声明 `compliance_health_report` available=false + ownerModule=16.2.2.1；两边不共表、不共端点、不共页面，本模块不读它的数据也不调它的端点 |
| 4.3.1.1 一键刊登 | **同批并行车道，此刻尚不存在** | 能力声明 `publish_dispatch` available=false + ownerModule=4.3.1.1；本模块不读它的表也不调它的端点，**但阻断项的服务端强制校验已经做实**，它接上来时直接读发布闸门即可 |
| 4.2.3.1 平台类目映射 | **已交付，但在 catalog 服务侧**（不是 channel） | 本模块经 `GET /catalog/category-mappings` + `/resolve` 只读引用，不跨库 |

## 边界提醒：本模块与 16.2.2.1 的名字几乎一样

调度任务书把 4.2.3.1 写成「在 channel」，实际它在 **catalog**（`V145__platform_category_mapping.sql`）。
本模块是**跨服务只读**它，不跨库、不建 FK、不建视图。

同批 16.2.2.1 叫「合规预检」（合规域 D7，做体检报告 + 分维度得分），本模块也叫「合规预检」
（刊登域 D5，做发布前风险清单 + 阻断项）。两边：

- 表前缀 `listing_compliance_*` vs `compliance_check_*`
- API 前缀 `listing-compliance-precheck` vs `compliance-precheck`
- 页面 `/listings/compliance` vs `/compliance/precheck`
- 控制器 `ListingCompliancePrecheckController` vs `CompliancePrecheckController`

**互不依赖、互不引用。** api-proxy 的 allowlist 测试里有一条专门的断言：
`channel/compliance-precheck/*` 必须仍然 404。

## 文件清单

后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V198__listing_compliance_precheck.sql`
  （新，三张新表 + 5 道 DB 约束/触发器 + RLS + 授权，**零 ALTER 既有表的列**；
  另有 1 条加在 `listing_editor_submission` 上的 BEFORE INSERT 触发器 = 服务端强制校验的 DB 那道）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingComplianceModels.java`（新，传输模型）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingComplianceRules.java`（新，纯规则：词命中 / 品牌 / 类目 / 图片 / 结论）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingComplianceSourcePort.java`（新，03 / 04 上游只读端口）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingCompliancePrecheckService.java`（新，服务层）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingCompliancePrecheckController.java`（新，端点）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingComplianceErrorHandler.java`（新，把合规拒绝翻成 409 + 说人话的文案）

后端测试

- `backend/services/channel/src/test/java/com/starcon/channel/ListingComplianceRulesTest.java`（新，10 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingCompliancePrecheckIntegrationTest.java`（新，18 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingCompliancePrecheckTenancyIntegrationTest.java`（新，3 例，租户三断言）
- `backend/services/channel/src/test/java/com/starcon/channel/StubComplianceCatalogServer.java`（新，本地真 HTTP catalog 服务）

前端

- `apps/web/src/components/listing-compliance/listing-compliance-model.ts`（新）
- `apps/web/src/components/listing-compliance/listing-compliance-model.test.ts`（新，16 例）
- `apps/web/src/components/listing-compliance/listing-compliance-workspace.tsx`（新）
- `apps/web/src/app/listings/compliance/page.tsx`（新）
- `apps/web/src/app/api/channel/listing-compliance-precheck-allowlist.test.ts`（新，5 例）
- `apps/web/src/lib/api-proxy.ts`（**只新增一条** `services[]` 元素，插入锚点 = `listing-editor` 之后 / `listing-claims` 之前）

契约（codegen 产物，未手改）

- `packages/api-contracts/src/contracts/channel.ts`（+18 个 `ListingCompliance*` DTO）
- `packages/api-contracts/src/channel.openapi.json`（+10 条路径）

台账与会话

- `docs/product/capability-blueprint/module-progress.md`（**只改 4.2.3.2 那一行的状态与证据两格**）
- `docs/sessions/2026-10/2026-10-20-listing-compliance-precheck.md`（本文件）

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` → exit 0；`pnpm typecheck` → 3/3 通过。
- L1 模块闸：`./mvnw -pl services/channel -am test -Dtest='ListingCompliance*'
  -Dsurefire.failIfNoSpecifiedTests=false` → **31 例 0 失败 0 错误 BUILD SUCCESS**
  （其中集成 18 例 + 租户 3 例 + 纯规则 10 例 —— 两个 Testcontainers 真实 PostgreSQL 16 容器，
  本车道 verify 期间同时存活 2 个 PG 容器，测试结束后被 ryuk 回收）。
- api-proxy 全量 allowlist 回归（改了共享白名单必跑）：
  `pnpm vitest run apps/web/src/app/api/trade apps/web/src/app/api/channel apps/web/src/lib`
  → **74 文件 702 例全绿**；本模块两个测试文件 21 例全绿。
- `pnpm exec eslint` 本模块文件 → 零告警；`pnpm exec prettier --check` → 干净。

## 后续待办

- **4.3.1.1 一键刊登**接上来时，直接读 `GET /channel/listing-compliance-precheck/drafts/{id}/publish-gate`
  作为派发前的闸门；DB 触发器已经在 `listing_editor_submission` 上做实了第二道。
- **16.2.1.1 合规规则库**落地后，「极限词 / 平台规则」那一段应改为**读规则库**，
  本模块的 `listing_compliance_word` 降级为「租户自维护的补充词表」，不删。
- **16.2.2.2 认证与资质**落地后，认证维度从 UNKNOWN 变成 CHECKED，
  结论的第三态 `INCOMPLETE` 在认证维度接上之后才可能整体消失。
- **16.2.2.1 合规预检（体检报告）**落地后，两边仍不共表：一个是「能不能发」，一个是「做得好不好」。
- 平台图片规格（白底 / 水印 / 目标尺寸）目前靠租户在 catalog 侧登记图片处理配方；
  若平台侧规格另有来源，图片维度的「量不到」范围会随之变化。
