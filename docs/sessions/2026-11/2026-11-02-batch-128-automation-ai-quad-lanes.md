# batch-128 · 18.3.2.1 / 18.4.2.2 / 18.4.3.1 / 18.5.1.1 —— 集成与自动化四条车道并入

- 轮次：r154
- 集成头：`/Users/guan/Projects/starcon-worktrees/integration`（分支 `integration/module-dev`）
- 并入前基线：`609ef8f7` → 并入后：`b4a6deef`
- L2 批次闸：r154 **结论无效** → r155 全量补跑（详见下节）

## L2 批次闸结论

### r154 无效 —— 它从未跑过本批交付的任何一行新代码

`./mvnw verify` 在 `warehouse` 失败后**默认跳过**后续模块：

```
[INFO] warehouse ...... FAILURE [13:42 min]
[INFO] trade ........ SKIPPED
[INFO] crm .......... SKIPPED
[INFO] finance ...... SKIPPED
[INFO] reporting .... SKIPPED
[INFO] platform ..... SKIPPED
```

而本批四条车道**全部落在 `platform` 服务** ⇒ r154 从头到尾没跑过 `platform`。
⇒ r154 的 `mvn-verify exit=1`（`.dev-agent/l2/l2-r154-summary.txt`）**不是本批回归的证据**，
它是「warehouse 先炸 ⇒ 后面根本没跑」。拿它当「本批红了」属误判 —— 真正的全量结论从未产生过。

### r155 全量补跑 —— 命令改为 `-fae`，一次拿到完整失败集

`.dev-agent/l2/l2-r155-summary.txt`（HEAD=`b4a6deef`，BACKEND_TREE=`d39643d3662a25a1d6c9fad426bb1fdae8cd0741`）
命令从 `./mvnw verify` 改为 **`./mvnw verify -fae`**（fail-at-end）⇒ 单个模块失败**不再跳过后续模块**。

| 步骤 | 结论 |
| --- | --- |
| mvn-verify | **RED**（exit=1，dur=4208s） |
| typecheck / lint / test / nextbuild / sync-check / format-gate | **全绿**（exit=0） |

失败集两组：

| 组 | 失败 | 归因 |
| --- | --- | --- |
| A | `platform` `AutomationRuleEditorIntegrationTest#保存后规则在18_3_1_1的档案端点里读得到`（1 failure） | **本批引入的假红**：18.3.2.1 新增 `platform_automation_rule_run` / `platform_automation_rule_run_step` 两张表，而 18.3.1.2 的断言是「同族表**全量精确集合**」⇒ 兄弟车道并入的那一刻它必红。**性质本身没错，错在断言层**（与同批前端 `ai-forecast` 白名单假红同构）。修复见 batch-130 |
| B | `reporting` `InventoryAnalysisIntegrationTest`（17 errors） | **环境抖动，非本批引入**：单一根因 —— Testcontainers PG 容器刚 `Container is started` 即连库超时（`PSQLException: 尝试连线已失败` → `SocketTimeoutException: Read timed out`，发生在 `enableSSL` 阶段）。`reporting` 服务本批一行未改 |

### 推送状态

按协议「L2 红 ⇒ 不 push」⇒ **本批交付在 r154/r155 之后并未推上远端**
（`origin/integration/module-dev` 仍停在 batch-127 的 `609ef8f7`）。
实际推送在 **batch-130**：A 组假红修掉后重跑全量 L2，绿了才推。

---

## 模块 18.3.2.1 执行日志

**这个功能做什么**：每次自动化规则跑起来都留下可查的台账 —— 触发了什么、条件命中没命中、执行了哪些动作、每步耗时多少、失败卡在哪一步；执行者可以看上下文，也可以手动重跑（重跑只登记、不擅自执行副作用）。

**技术落地**：

- 迁移：`V422__automation_rule_run.sql`（platform）—— **只建两张新表**：`platform_automation_rule_run`（一次执行的明细台账）+ `platform_automation_rule_run_step`（逐步明细）；**绝不 ALTER** 18.3.1.1（V419）的五张表。每张表 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + 租户触发器兜底 + 只追加触发器（钉死 UPDATE / DELETE）+ `GRANT SELECT, INSERT`。
  - 幂等落点三条物理事实：部分唯一索引 `UNIQUE (tenant_id, rule_id, event_id) WHERE rerun_of_run_id IS NULL`（同一事件至多一条执行记录）；幂等键唯一索引 `UNIQUE (tenant_id, rule_id, idempotency_key)`（重复提交拿不到第二条）；重跑是新的一次执行（`rerun_of_run_id` + `rerun_seq`）且**原 run 一个字都不改**。
  - 逐步明细记 `step_no` / `step_kind` / `matched` / `condition_id` + `condition_seq` / `action_type` + `target_ref` / `result` / `duration_ms`（**整数毫秒**，禁浮点）/ `error_code` + `error_message`（失败必写原因，DB 层 CHECK 钉死）⇒ **失败原因能从 run 直接下钻到 step**。
  - `condition_id` **刻意不建 FK**（18.3.1.1 改规则会整体替换条件行 ⇒ 建 FK 会把历史日志锁死），只存引用 + 快照。
- 权限：`V422__platform_automation_rule_run_permission.sql`（identity）登记 `platform.automation_rule_run.read` / `.record` / `.rerun` 三个码 + page_resource `platform.automation_rule_run`，**只授 OWNER**（能看台账的人不该因此就能让系统替他改价建单）。
- 端点：6 个，挂 `/platform/automation/runs`（`AutomationRuleRunController.java`，jOOQ 显式 SQL）—— GET `/capabilities`（逐条点名执行器未交付）/ GET `/overview`（概览，**含「未执行」这一档**）/ GET `/`（真分页 + 真筛选，`created_at DESC, id DESC` 全序）/ GET `/{id}`（详情，**含逐步明细** + 重跑次数）/ POST `/`（登记一次执行，幂等：同规则 + 同事件 ⇒ 409 并点名既有 run id）/ POST `/{id}/rerun`（手动重跑，落成**新的一条**）。
  - 🔴 **手动重跑只做记录与编排，不擅自执行副作用**：本仓不存在规则执行引擎 ⇒ 重跑落成 `outcome=NOT_ATTEMPTED` + `failure_class=EXECUTOR_UNAVAILABLE` + `error_code=EXECUTOR_NOT_CONNECTED` + `side_effects_applied=false` + `executed_actions=0`，**绝不伪造「已重跑成功」**。
  - 试运行标记**由规则档案推导**（服务端读规则，客户端自称不算数 ⇒ 请求体不接受 `dryRun`，Jackson fail-on-unknown-properties 直接 400）；请求体**不接受** tenantId / userId / membershipId。
- 页面：`/automations/runs`（`apps/web/src/app/automations/runs/page.tsx` + `apps/web/src/components/automations/automation-run-model.ts` / `automation-run-workspace.tsx`）—— 真实工作区：运行列表（真分页 + 真筛选）/ 运行详情（逐步明细分「条件判定」「动作执行」两段，**第一处失败单独拎出来**）/ 触发上下文摘要 / 重跑入口（带幂等键，双击不会跑两次）；加载 · 空 · 错误三态齐全，空态明说「18.3.2 执行尚未交付，这里空是**预期状态**不是故障」；结论徽章分 `成功 / 失败 / 跳过 / 超时 / 未执行` 五档，**「未执行」单独一档**。
- 测试：后端 `PlatformAutomationRuleRunIntegrationTest` 19 例 + `PlatformAutomationRuleRunTenancyIntegrationTest` 9 例 = **28 例全过**（真 Testcontainers PostgreSQL 16、FORCE RLS、连接池压到 1 条）；前端 `automation-run-model.test.ts` + `automation-run-allowlist.test.ts` = 24 例 + 全量 allowlist 回归 1602 例全过。多租户三条断言齐全（两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户），另断言 FORCE RLS、只追加台账不可改不可删、跨租户规则挂不上执行日志。

**关键设计决策**：

- **另建自己的表，不复用 18.3.1.1 的 `_execution` 表** —— 那张只是聚合计数台账，不含逐步明细 ⇒「失败卡在第几步」在那边查不到；每张事实表只有一个写入所有者 ⇒ 一个字都不写进 18.3.1.1 的表。
- **幂等用数据库唯一索引实现，不靠服务层记得检查** —— 重复投递撞索引，**不可能凭空多出一条 run 并宣称已执行**。
- **失败原因必须落在 step 上**（错误码 + 错误信息 + 分类配套，CHECK 钉死），否则台账只能告诉你「失败了」却不能告诉你「为什么」。

---

## 模块 18.4.2.2 选品与需求预测

**这个功能做什么**：把历史销量、库存、在途、价格、促销日历喂进去，产出销量预测、补货量建议、价格弹性与调价建议、爆品识别与机会品发现 —— 每个预测都带**置信区间和影响因素说明**，建议只作参考输入，不自动执行。

**技术落地**：

- 迁移：`V424__ai_forecast.sql`（platform）—— **六张表**：`platform_forecast_promotion`（促销）/ `platform_forecast_demand_history`（大促日历与需求历史）/ `platform_forecast_run` / `platform_forecast_result` / `platform_forecast_factor`（关键影响因素）/ `platform_forecast_suggestion`（补货与调价建议）。全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + 租户触发器兜底 + GRANT + 索引。
- 权限：`V424__platform_ai_forecast_permission.sql`（identity）登记 `platform.forecast.*` 权限码与 page_resource，沿用 **platform + identity 同号**惯例。
- 端点：20 个，挂 `/platform/ai/forecast`（`AiForecastController.java`，jOOQ 显式 SQL）—— `/capabilities`（算法口径 / 阈值 / 来源 / 边界逐条报出）、`/overview`、促销日历 CRUD、`/history` 需求历史 CRUD + `/history/sync`、预测运行 `/runs`（GET/POST）、预测结果 `/forecasts`（列表 + 详情）、`/replenish-suggestions`（补货建议）、`/suggestions/{id}/adopt` 与 `/reject`（建议采纳与否，**建议不自动执行**）、`/price-elasticity`（价格弹性）、`/hot-items`（爆品）、`/opportunities`（机会品）。
- 页面：`/ai/forecast`（`apps/web/src/app/ai/forecast/page.tsx` + `apps/web/src/components/ai/ai-forecast-model.ts` 等）—— 真实工作区：预测结果（带置信区间 + 影响因素）、补货建议（采纳 / 拒绝）、价格弹性、爆品与机会品、促销与需求历史维护；加载 · 空 · 错误三态齐全。
- 测试：后端 `AiForecastIntegrationTest` 19 例 + `AiForecastTenancyIntegrationTest` 7 例 = **26 例全过**（真 Testcontainers PostgreSQL 16、FORCE RLS、连接池压到 1 条）；前端 `ai-forecast-model.test.ts` + `ai-forecast-allowlist.test.ts` 全绿 + 全量 allowlist 回归全绿。多租户三条断言齐全。

**关键设计决策**：

- **预测必须给置信区间和影响因素** —— 单点数字会让用户过度信任；因此 `platform_forecast_factor` 单独成表，把「为什么是这个数」变成可查询的事实，而不是塞进一段说明文字。
- **建议只作参考输入，不自动执行** —— 采纳与否由用户显式 `/adopt` / `/reject` 决定，蓝图明确「建议只作为参考输入，不自动执行」。
- **促销日历与需求历史独立成表** —— 预测结果要能复算「当时的输入是什么」，促销与需求历史是输入事实，不该埋在预测结果的 JSON 里。

---

## 模块 18.4.3.1 使用与配额

**这个功能做什么**：把 AI 的调用量与成本按租户和用户管起来 —— 记录每次调用、配额限制、模型选择、敏感数据处理策略开关，并产出用量报表。**AI 是有边际成本的能力，必须可计量可限额，否则成本不可控。**

**技术落地**：

- 迁移：`V425__ai_usage_quota.sql`（platform）—— **五张表**：`platform_ai_model`（模型清单）/ `platform_ai_usage_record`（调用记录）/ `platform_ai_quota`（配额）/ `platform_ai_quota_usage`（配额用量）/ `platform_ai_sensitive_policy`（敏感数据处理策略开关）。全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + 租户触发器兜底 + GRANT + 索引；金额与成本用 `NUMERIC`，禁浮点。
- 权限：`V425__platform_ai_usage_permission.sql`（identity）登记 `platform.ai_usage.*` 权限码与 page_resource。
- 端点：13 个，挂 `/platform/ai/usage`（`AiUsageQuotaController.java`，jOOQ 显式 SQL）—— `/capabilities`、模型 `/models`（GET/POST）、调用记录 `/records`（GET/POST）、用量报表 `/summary`、配额 `/quotas`（GET/POST）、成员维度配额 `/members/{memberId}/quotas`、配额校验 `/quotas/check`、敏感策略 `/policies`（GET/POST）与 `/policies/effective`。
- 页面：`/ai/usage`（`apps/web/src/app/ai/usage/page.tsx` + `apps/web/src/components/ai/ai-usage-model.ts` 等）—— 真实工作区：用量报表（按租户 / 用户 / 模型 / 期间）、配额配置与校验、模型选择、敏感数据策略开关；加载 · 空 · 错误三态齐全。
- 测试：后端 `AiUsageQuotaIntegrationTest` 16 例 + `AiUsageQuotaTenancyIntegrationTest` 8 例 = **24 例全过**（真 Testcontainers PostgreSQL 16、FORCE RLS、连接池压到 1 条）；前端 `ai-usage-model.test.ts` + `ai-usage-allowlist.test.ts` 全绿 + 全量 allowlist 回归全绿。多租户三条断言齐全。

**关键设计决策**：

- **调用记录与配额用量分表** —— 「调用了多少」是事实流水，「配额用了多少」是随窗口变化的聚合；两者生命周期不同，混在一张表会让配额计算随时间漂移。
- **配额校验独立成端点（`/quotas/check`）** —— 限额必须在**执行前**被强制执行，而不是事后统计；独立端点让调用方能在发起调用前拿到结论。
- **敏感数据策略是策略表而非布尔字段** —— 开关要能带生效范围与版本，否则「某次调用到底按哪条策略执行」无法追溯。

---

## 模块 18.5.1.1 模板管理

**这个功能做什么**：按**场景 × 语言**管理通知与消息模板 —— 模板内容、变量定义、启用状态一应俱全；改模板是**新增一个版本**，已发出的通知保留当时那版内容，不会被新模板「篡改」。

**技术落地**：

- 迁移：`V427__notification_template.sql`（platform）—— **三张表**：
  - `platform_notify_template`（主记录：`tenant_id NOT NULL` + `template_key`（租户内小写机器名，CHECK `^[a-z0-9][a-z0-9._-]{0,63}$`）+ `scene`（受控词表 CHECK：ORDER_STATUS / STOCK / PRICE / LOGISTICS / CUSTOMER_SERVICE / MARKETING / SYSTEM）+ `language`（IANA 形状 CHECK）+ UNIQUE(tenant_id, template_key, scene, language) + `archived_at`（归档 = 软删除）；
  - `platform_notify_template_version`（**不可变版本行**：version_no + subject + body + variables(jsonb) + change_note + created_by，UNIQUE(tenant_id, template_id, version_no)，FK ON DELETE RESTRICT）；
  - `platform_notify_template_activation`（**启用事件日志**：template_id + version_id + activated_at + deactivated_at，部分唯一索引 `platform_notify_template_activation_one_open`（WHERE deactivated_at IS NULL）⇒ **同一模板同时只允许一个启用版本，这是数据库层写不进去的，不是服务层记得检查**）。
  - 三表均 ENABLE **与 FORCE** RLS isolation + 三道租户触发器兜底 + GRANT + 索引。
  - 🔴 **版本行只给 SELECT / INSERT，不给 UPDATE / DELETE** ⇒ 版本不可篡改由**权限层**兜底，再加 BEFORE UPDATE OR DELETE 触发器（报错写明「编辑请新增一个版本」）双保险；启用日志的指向不可改（只允许填 deactivated_at）。
- 权限：`V427__platform_template_permission.sql`（identity）登记 page_resource `platform.template` + 4 个码 `platform.template.read` / `.create` / `.update` / `.delete`（**delete 是「停用 + 归档」，不是物理删**，但仍独立成码）+ 存量 OWNER 幂等补权。
- 端点：15 个，挂 `/platform/ai/templates`（`NotificationTemplateController.java`，jOOQ 显式 SQL）—— `/capabilities`（场景词表 / 语言写法 / 变量约定 / 渲染报错口径）、`/overview`、`/`（GET 真分页真筛选 / POST 新建并落下第 1 版）、`/{templateId}`（详情：主记录 + 版本列表 + 当前生效版本）、`/{templateId}/metadata`（**只改名称与说明，内容改不了**）、`DELETE /{templateId}`（归档 = 停用 + 归档）、`/{templateId}/versions`（GET/POST —— **编辑 = 新增版本**）、`/{templateId}/versions/{versionId}`（GET）、`.../enable` / `.../disable`、`/render-preview`（**不落库**）、`/resolve?templateKey&scene&language&at`（**按时刻解析当时生效的那一版**，返回 version id + 内容快照）、`/upstream/content-assets`（3.4.2 文案资产只读）。
- 页面：`/ai/templates`（`apps/web/src/app/ai/templates/page.tsx` + `apps/web/src/components/ai/notification-template-{model,workspace}.tsx`）—— 真实工作区：能力与边界、概览、模板清单（真分页真筛选）、模板详情与**版本历史（带生效区间）**、**新增版本 = 编辑**（页面**没有**「覆盖保存」这条路）、变量定义编辑器（当场提示「正文里用了还没声明的变量」）、**渲染预览**（失败时逐条显示缺哪个变量 / 未声明变量 / 多余取值 / 语法错误）、按时刻解析、上游文案资产（读不到就显示「读不到」）；加载 · 空 · 错误三态齐全，空态区分「筛选无结果」与「还没建过模板」。
- 测试：后端 `NotificationTemplateIntegrationTest` 22 例 + `NotificationTemplateTenancyIntegrationTest` 6 例 + `NotificationTemplateRendererTest` 12 例 = **40 例全绿**（真 Testcontainers PostgreSQL 16、连接池压到 1 条）；前端 `notification-template-model.test.ts` 12 例 + `notification-template-allowlist.test.ts` 4 例 + 全量 allowlist 回归 1600 例全绿。多租户三条断言齐全，另断言：改版后按旧时刻解析仍是旧内容（旧版本 id + 旧正文）、版本行直连 SQL **改不动**（permission denied）、第二个「生效中」版本**数据库层写不进去**、时间写回值按 instant 断言（不偏 8 小时）、渲染四类错误各自点名、渲染预览**不落库**、场景枚举与 DB CHECK 逐字同表。
- 渲染引擎 `NotificationTemplateRenderer`（纯规则，不依赖外部系统）报错语义：`MISSING_VARIABLE`（点名缺哪个变量）/ `UNKNOWN_VARIABLE`（点名未声明变量）/ `EXTRA_VARIABLE`（点名多余取值）/ `SYNTAX_ERROR`（占位符没闭合）/ `INVALID_VALUE`（类型不符）/ `INVALID_DECLARATION`（变量定义有误）；渲染失败时 `renderedBody` 一律为 **null**（**绝不**渲染成空串，**绝不**留下 `{{xxx}}` 残渣）。

**关键设计决策**：

- **编辑 = 追加版本行，老版本一个字节都不动**（GRANT + 触发器双保险）；「历史通知保留当时内容」由**启用事件日志**支撑 —— resolve 取的是「那一刻仍在有效期内」的版本并返回 **version id + 内容快照**，不是「当前版本」，下游 18.5.2.1 引用这个版本 id，后来改模板篡改不了已经发出的通知。
- **启用版本唯一性用部分唯一索引在数据库层保证**，不靠服务层记得检查。
- **与 18.4.2.1 的 `platform_text_template` 刻意分开**：18.4.2.1 是「客服回复草稿」（翻译链路产物，变量是封闭词表），本模块是「通知与消息模板」（按场景 × 语言组织、版本化、可按时刻解析）—— 对象、生命周期与下游都不同 ⇒ **各建各的表，一个字也不去 ALTER 对方的表**；契约命名空间也分开（全部 `NotifyTemplate*` 前缀，否则 codegen 会判「字段级覆盖」并中止）。
- **上游 3.4.2 文案资产走只读端口，不抄一份**：取不到一律 503（消息写明「是读不到，不是没有数据」）。

---

## 本轮顺带修掉的台账问题

- **两行证据格含字面竖线**（18.4.2.2 的 `number | null`、18.5.1.1 的 `| null`）⇒ `sync.mjs --check` 判红（台账第一号杀手：竖线切列、历史上还因此静默截断过证据）。已把字面 `|` 换成 `／` 或改写措辞，`sync.mjs` 重跑后 `✓ 台账与蓝图一致`。**并已作为硬要求写进本轮派发的 4 条任务书**：证据格里禁止出现字面竖线。
- **两条废弃车道的分支基线陈旧**（`feat/18-4-2-3` / `feat/18-4-3-2` 停在旧基线 `6b8ae806`，**零提交** ⇒ 按「先判有没有半成品」规则判定可回收）⇒ 已 `reset --hard b4a6deef` 后重新派发。

## 本轮新派发的车道（本轮不并入，下一轮回收）

| 模块 | 名称 | 分支 | 迁移 |
| --- | --- | --- | --- |
| 18.3.2.2 | 规则效果 | `feat/18-3-2-2` | V428 |
| 18.4.2.3 | 异常诊断 | `feat/18-4-2-3` | V429 |
| 18.4.3.2 | 效果评估 | `feat/18-4-3-2` | V430 |
| 18.5.2.1 | 投递记录 | `feat/18-5-2-1` | V431 |

派发时澄清了一处易混：**18.5.2.1「投递记录」（通知投递）≠ 18.1.2.2「投递记录」（webhook 事件投递，已在 platform 落地为 `DeliveryRecord*` + `platform_delivery_*` + 路由 `/platform/deliveries`）** ⇒ 18.5.2.1 用 `NotificationDelivery*` 类名与 `platform_notify_*` 表名，路由挂 `/platform/ai/deliveries`。
