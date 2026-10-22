# batch-67 · 卷 15 客户与客服 ×3 + 卷 16 合规风控 ×1（4 条车道并入 + 契约漂移修复 + L2 全绿）

- **日期**：2026-10-22
- **分支**：`integration/module-dev`（集成分支）
- **批次号**：batch-67（接 batch-66 往后排）
- **模式**：full（当前 < `TOKEN_HORIZON` = 2026-10-25T00:00:00+0800；`LANES=4`，节流闸 50 分钟，`SILENT_WINDOW=0` 全天运行）
- **覆盖模块**：15.1.2.2 邮件规则 · 15.4.1.1 工单列表 · 15.5.1.1 自动回复 · 16.1.2.1 违规与处罚
- **附带修复**：batch-66 遗留的 **channel 契约漂移**（`PerformanceMetricAlertPage` / `PerformanceMetricForecastPage` 缺状态计数字段）—— 由 L2 契约闸抓到，本批随 16.1.2.1 的契约重生成一并修正

## 批次构成

| 模块 | 名称 | 归属服务 | 迁移 | 分支 → 并入 commit | 状态 |
| --- | --- | --- | --- | --- | --- |
| 15.1.2.2 | 邮件规则 | crm（8086 / `crm_app`） | `V305__crm_email_rule.sql` | `feat/15-1-2-2` `f8fe38ca` → `1198d8ae` | 已交付 |
| 15.4.1.1 | 工单列表 | crm | `V306__crm_service_ticket_list.sql` | `feat/15-4-1-1` `980c24fb` → `f4c55803` | 已交付 |
| 15.5.1.1 | 自动回复 | crm | `V307__crm_auto_reply.sql` | `feat/15-5-1-1` `1b38802` → `fa810233` | 已交付 |
| 16.1.2.1 | 违规与处罚 | channel（8083 / `channel_app`） | `V308__channel_platform_violation.sql` | `feat/16-1-2-1` `162268f3` → `4f9015f8` | 已交付 |

四条迁移**全部是纯新建表、零 ALTER 任何既有表**，把跨车道 DDL 回归面压到 0。

---

## 模块 15.1.2.2 邮件规则

**这个功能做什么**：客服给邮箱配一套规则 —— 收到买家邮件时，按**发件人 / 关键词 / 工单标签**三个维度自动判定该怎么处理：自动分派、自动回复，或者**静默**（重要沟通一律不许静默，否则会踩平台违规的红线）。每条规则命中了什么、当时规则是什么版本，都留痕可查，改规则不会篡改历史命中记录。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V305__crm_email_rule.sql` —— **五张新表** `crm_email_rule`（规则本体）/ `crm_email_ticket_tag`（工单标签）/ `crm_email_ticket_tag_assignment`（标签分派）/ `crm_email_rule_hit`（命中记录，**只追加**）/ `crm_email_rule_assignment`（自动分配结果）；`tenant_id NOT NULL` + ENABLE **与 FORCE** RLS + POLICY + GRANT `crm_app` / REVOKE `starcon_app`；**零外键**，跨表引用一律用触发器校验；两条序列单独授权；**零 ALTER 既有表**。
- 端点：`/crm/email-rules/**` —— 16 个方法映射 / 13 条路径（规则 CRUD 与启停 / 命中评估 `POST /crm/email-rules/emails/{uuid}/evaluate` / 命中记录 / 分配结果 / 标签 / 能力声明）。
- 页面：`/crm/email-rules` —— `apps/web/src/app/crm/email-rules/page.tsx` + `apps/web/src/components/crm/email-rule-{model.ts,workspace.tsx}`；`api-proxy` 新增**一条独立** `crm/` 白名单元素（首 token `email-rules`，锚在 `^buyer-emails...$` 之后，既有元素一字未动）。
- 测试：`EmailRuleIntegrationTest` 14 例 + `EmailRuleRulesTest` 11 例 + `EmailRuleTenancyIntegrationTest` 4 例 = **后端 29 例**（真实 PostgreSQL 16 Testcontainers，含租户隔离三断言）；前端 `email-rule-model.test.ts` 21 例 + `email-rules-allowlist.test.ts` 4 例 = **25 例**。

**关键设计决策**：

1. **静默降级在服务端 + DB CHECK 双重强制**：判定为「重要沟通」时**不允许静默**，动作强制降级为 `ESCALATE_MANUAL`，并把 `silence_refused` 与拒绝理由写进命中记录。理由：静默重要沟通会造成平台违规，这是不可逆的信任损失，必须在服务端拦住而不是靠前端约定。
2. **命中记录只追加，且冻结当时快照**（`rule_version` + `rule_snapshot` + `match_basis`）⇒ 改规则**不追溯改变历史命中**，与仓库既有纪律一致。
3. **三个条件维度都是真列**（发件人 / 关键词 / 工单标签），不是塞进一个 JSON 糊弄 —— 规则要能被审阅和检索。
4. **下游未交付一律不编**：15.4.1.1 工单分配未交付 ⇒ 只产出自动分配结果（分给谁、依据哪条规则、命中哪些条件），**不创建工单、不写工单 id**，状态恒 `PENDING_DISPATCH`；15.5.1 自动回复未交付 ⇒ 只留痕意图（模板码 + 标题/正文快照），`delivery_status` 恒 `PENDING_DOWNSTREAM`，**不谎称已发出**；15.1.2.1 的同步流程未接线 ⇒ 评估走显式入口，能力声明 `auto_evaluation_on_receive` 标 `available=false`（ownerModule 15.1.2.1）。

**验证**：L0 `compile` exit 0 + `typecheck` 3/3；L1 `-Dtest='EmailRule*'` **29 例 0 失败 0 错误 BUILD SUCCESS**；前端 25 例 + 因改了 `api-proxy` 跑了**全量 allowlist 回归 181 文件 / 1045 例全绿**；契约只改 crm 两份（**+36 行类型 / 0 删除**）。

---

## 模块 15.4.1.1 工单列表

**这个功能做什么**：客服打开工单工作台，看到所有待处理工单的类型、优先级、状态、**SLA 还剩多少**、关联的买家与订单、当前分给谁，并能转派与升级。关键是 SLA **按营业时间算**：凌晨积压的工单不会因为「自然时间过了 8 小时」就全部显示逾期。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V306__crm_service_ticket_list.sql` —— **三张新表** `crm_service_business_hours`（营业时段）/ `crm_service_business_holiday`（节假日例外）/ `crm_service_business_hours_audit`（营业时间变更审计，**append-only**）；FORCE RLS + GRANT；零 ALTER 既有表。
- 端点：`/crm/service-tickets` —— `capabilities` / 列表（真分页真筛选）/ `{id}` / `business-hours`（GET·PUT）/ `business-hours/holidays`（PUT）。
- 页面：`/crm/service-tickets` —— `apps/web/src/app/crm/service-tickets/page.tsx` + `apps/web/src/components/crm/service-ticket-list-{model.ts,workspace.tsx}`。
- 测试：`ServiceTicketListIntegrationTest` 20 例 + `ServiceTicketListTenancyIntegrationTest` 4 例 + `BusinessHoursCalendarTest` 9 例 = **后端 33 例**；前端 19 例（model 10 + allowlist 双向断言 9）。

**跨服务依赖怎么落的**（本模块最关键的边界）：

- 工单的事实来源在 **trade 服务**（`aftersales_service_ticket`，`V270__service_ticket_management.sql`，端点 `/trade/service-tickets`）。
- 本模块通过**只读端口 `ServiceTicketFactsPort`** 经**授权 HTTP API** 读取：`GET /trade/service-tickets`、`GET /trade/service-tickets/{id}`、`GET /trade/aftersales/cases`、`GET /trade/sales-orders/list`。
- **透传调用方自己的 `Authorization` bearer，不传租户号** ⇒ 租户由 trade 侧按已认证成员关系解析并强制 RLS，租户隔离是**两道**（crm 侧 RLS + trade 侧 RLS）。
- **trade 服务零改动**；不建工单表、不建跨库 FK、不建共享视图、不直连对方库（AGENTS.md 模块边界纪律）。

**关键设计决策**：

1. **SLA 按营业时间轴累加**（蓝图加粗句）：跨夜、跨周末、跨节假日、午休时段都不计时；到期时刻 = 在营业时间轴上推进，而不是 `createdAt + N 小时`。否则夜间积压会造成大面积误判逾期。
2. **没配营业时间 ⇒ `NO_CALENDAR`，刻意不退回自然时间**。理由：自然时间兜底会让「没配置」看起来像「一切正常」，掩盖配置缺失。
3. **库里不存会自己过期的「剩余」列**（`information_schema` 实查确认没有 remaining 列）；SLA 剩余是**读取时刻的派生态**，与 trade 侧既有口径一致。
4. **SLA 状态 / 逾期筛选在本服务按营业时间口径自行判定**，不直接下推上游同名筛选 —— 上游是自然时间口径，直接下推会漏掉「自然时间还没到、营业时间已经超了」的单。
5. **营业时间配置每次修改留 append-only 审计**（谁在什么时候把哪段时段改成什么）。
6. **本地扫描上限 5000 张工单**（25 页 × 200）：触到上限时响应里如实报 `totalExact=false`，总数是下界，不假装是精确值。

**已知缺口**：营业时间作用域按**请求级**解析（`shopId` 优先、未指定回落租户默认）—— 上游工单行**不带店铺 id**，逐行解析不可靠，配置表已预留 `SHOP:<uuid>` 作用域，待 trade 暴露店铺维度后改为逐行；上游 `caseNo` / `keyword` 是模糊匹配，端口**只认完全相等**的命中（拿「相近」的订单当上下文比显示不可用更糟）。

**验证**：L0 `compile` exit 0 + `typecheck` 3/3 + `lint` 3/3；L1 **33 例 0 失败 0 错误 BUILD SUCCESS**；前端 19 例 + 因改了 `api-proxy` 跑了**全量 allowlist 回归 181 文件 / 1046 例全绿**。

---

## 模块 15.5.1.1 自动回复

**这个功能做什么**：给消息配「自动回复」：命中分类 / 关键词 / 无人接管超时，就自动回一封 —— 可以分几级（先自动引导、还没人管再升级、最后转人工），节假日无人值守也能回。**关键是随时能一键关掉，并留出人工接管的出口**，因为很多平台对纯自动回复有明确限制。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V307__crm_auto_reply.sql` —— **六张新表** `crm_auto_reply_setting`（全局开关）/ `crm_auto_reply_rule`（规则 = 阶梯一级）/ `crm_auto_reply_duty_calendar`（值班日历）/ `crm_auto_reply_record`（回复记录，**只追加**）/ `crm_auto_reply_escalation`（转人工标记，**只追加**）/ `crm_auto_reply_takeover`（人工接管，**只追加**）；`tenant_id NOT NULL` + ENABLE **与 FORCE** RLS + GRANT `crm_app` / `starcon_app` + 序列单独授权；跨表引用一律用触发器；**零 ALTER 既有表**。
- 端点：`/crm/auto-replies/**` —— 14 条路径 / 22 个 schema（`settings` / `settings/toggle` / `rules` / `calendar` / `records` / `records/{id}` / `records/{id}/escalate` / `escalations` / `takeover` / `stats` / `capabilities`）。
- 页面：`/crm/auto-replies` —— `apps/web/src/app/crm/auto-replies/page.tsx` + `apps/web/src/components/crm/auto-reply-{model.ts,workspace.tsx}`；`api-proxy` 新增**一条独立** `crm/` 元素（锚在 `^care-reminders` 之后）。
- 测试：`AutoReplyIntegrationTest` 16 例 + `AutoReplyTenancyIntegrationTest` 4 例 + `AutoReplyRulesTest` 7 例 = **后端 27 例**；前端 `auto-reply-model.test.ts` 18 例 + `auto-replies-allowlist.test.ts` 5 例 = **23 例**。

**关键设计决策**：

1. **触发条件三选一互斥**（收到消息分类 / 关键词 / 无人工介入超时），复用 15.1.1.3 的 `crm_message_classification`，**不造平行分类表**。
2. **分级阶梯显式落库**（`ladder_code` / `level` / `escalate_to_human` / `level_wait_minutes`），每一级都留痕；**转人工标记**是本模块的输出之一，真的落库可查。
3. **🔴 一键关闭即时生效**：关闭时**不评估、不发送、不写记录**；已经排队待发的逐条追加 `CANCELLED` 撤销行。**撤销行不写 15.1.1.2 的发送表** —— 一张事实表只有一个写入所有者，代价是 15.1.1.2 侧仍显 `QUEUED`，人工交接以本模块的 `CANCELLED` 行为准（已写进已知缺口）。
4. **人工接管按会话硬闸**：接管后同一会话不再自动回复。
5. **节假日 / 无人值守用租户可配的值班日历**，不是写死常量；挡下时转人工而不是硬发。
6. **平台侧限制不编白名单**：本仓拿不到平台对自动回复的具体限制 ⇒ 能力声明 `available=false` + ownerModule=2，页面只提示「先小范围试 + 随时能一键关」，**不假装知道平台的规则**。

**已知缺口**：平台投递未接入（channel 无买家站内信适配器）⇒ `transport` 恒 `MANUAL_HANDOFF`，记录停在「已登记待发」；15.4.1.2 工单未交付 ⇒ 人工介入只能依据 15.1.1.2 的 `SENT` 发送记录判定（**超时口径会偏长**，已如实说明）；15.1.1.1 平台同步未接入 ⇒ 触发入口由调用方显式调用；15.1.1.2 无取消入口 ⇒ 撤销信号落在本模块的追加行。

**验证**：L0 `compile` exit 0 + `typecheck` 3/3 + `lint` 绿；L1 **27 例 0 失败 0 错误 BUILD SUCCESS**；前端 23 例 + 因改了 `api-proxy` 跑了**全量 allowlist 回归 181 文件 / 1045 例全绿**；另跑**整个 crm 服务套件 393 例全绿**，确认 V307 迁移没破坏既有模块。

---

## 模块 16.1.2.1 违规与处罚

**这个功能做什么**：平台判了店铺违规（警告 / 下架 / 限制销售 / 扣款 / 停号），这里把它记成一条可追踪的违规档案：违规类型、涉及哪些商品和订单、处罚措施、扣了多少钱、整改要求是什么、现在整改进度如何。一张违规常常牵连一批商品，需要**批量定位**受影响的 listing 与库存。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V308__channel_platform_violation.sql` —— **六张新表、零 ALTER 任何既有表**：`platform_violation`（违规本体）/ `platform_violation_subject`（涉及商品与订单，**多对多**）/ `platform_penalty`（处罚措施 + 扣款金额）/ `platform_violation_remediation`（整改跟踪）/ `platform_violation_evidence`（凭据，**append-only**）/ `platform_violation_sync_run`（同步运行记录）；FORCE RLS + GRANT，跨表引用用触发器。
- 端点：`/channel/platform-violations/**` —— 18 个（`capabilities` / `shops` / 违规列表 + 登记 / 详情 / 状态流转 / `penalties` / `subjects` / `affected` / `delist` / `remediations` / 整改流转 / `evidence` / 处罚清单 / `shops/{uuid}/sync` / `sync-runs`）。
- 页面：`/compliance/violations` —— `apps/web/src/components/platform-violations/`（真实工作区 + 三态空态）。
- 测试：`PlatformViolationRulesTest` 11 例 + `PlatformViolationIntegrationTest` 20 例 + `PlatformViolationTenancyIntegrationTest` 3 例 = **后端 34 例**；前端 `platform-violations-model.test.ts` + `platform-violations-allowlist.test.ts` = **24 例**。

**未交付下游的如实声明**（能力声明 = 可查事实）：

| 下游 | 结论 | 处理 |
| --- | --- | --- |
| 12 财务（罚扣款） | **未交付** | `available=false`，ownerModule=12 财务系统；扣款金额只落在 `platform_penalty`，**不建财务表、不造指向不存在入口的放行形状** |
| 16.1.2.2 申诉管理 | **未交付** | `available=false`；处理状态只到「申诉中」，**不编造**申诉单/材料/结果；`api-proxy` 也**刻意不放行** `/appeals` |
| 4 刊登（问题商品下架） | **已交付**（4.1.1.1 批量下架就在 channel 服务内） | `available=true` —— 本模块**真的改 listing 状态**（`DELISTED_BY_US` + DB 触发器落状态历史），不是写一条「已下架」的记录 |

**关键设计决策**：

1. **处罚措施与违规类型是受控值域**（DB 层 CHECK 钉死，不只靠应用层 enum），值域要可审阅。
2. **扣款金额是 `Money` 值对象**（金额 + 币种，DB `NUMERIC(19,6)` + `currency`），**不同币种不得直接相加**；禁浮点。
3. **违规 ↔ 商品是多对多**（`platform_violation_subject`），支持批量定位受影响 listing 与库存；库存事实来源在别处 ⇒ **只经授权只读 API 取**，不跨服务 SQL。
4. **同步走「接收通知」形态**（平台违规通知是推送），幂等锚点落在平台侧不透明 id `external_notice_id`，唯一键（租户, 店铺, 通知 id）。**没有立一个永远打不开的拉取门** —— 那会让同步运行表成为死代码。
5. **整改凭据 append-only**（触发器拒绝 UPDATE / DELETE），处理状态走显式状态机，非法流转被拒。

**验证**：L0 `compile` exit 0 + `typecheck` 3/3；L1 **34 例 0 失败 0 错误 BUILD SUCCESS**；另跑 `AccountHealth*,PerformanceMetric*` **75 例全绿**（新迁移未破坏 16.1.1.1 / 16.1.1.2）；前端 24 例 + 因改了 `api-proxy` 跑了**全量 allowlist 回归 181 文件 / 1046 例全绿**。

---

## 🐛 本批抓到的两个缺陷（都是真问题，不是流程噪声）

### 缺陷 1：batch-66 遗留的 channel 契约漂移（L2 契约闸抓到）

**现象**：本轮第一次 L2 在**第 6 步「契约闸」红**，前 5 步全绿（后端 8317 例 0 失败、前端 6988 例 0 失败、Next production build 绿、sync --check 绿、merge-union.itest + codegen 单测绿）。

```
Files .tmp/l2-contract-gate/channel.openapi.json and packages/api-contracts/src/channel.openapi.json differ
Files .tmp/l2-contract-gate/contracts/channel.ts and packages/api-contracts/src/contracts/channel.ts differ
CONTRACT_GATE_EXIT=1
===== L2 FAIL at contract drift（契约产物与生成器输出不一致） (exit 1) =====
```

**定位**（逐字取证）：漂移**只有两处**，且是**纯增量** —— 后端早就返回、契约里却没有的计数字段：

| 类型 | 契约缺 | 后端实际返回 |
| --- | --- | --- |
| `PerformanceMetricAlertPage` | `criticalCount` / `warningCount` / `unknownCount` | `PerformanceMetricModels.java:335 long criticalCount` |
| `PerformanceMetricForecastPage` | `willBreachCount` / `alreadyBreachedCount` / `noBreachCount` / `cannotPredictCount` | `PerformanceMetricModels.java:450 long willBreachCount` |

**引入点**：`git log -S "PerformanceMetricAlertPage" -- packages/api-contracts/src/contracts/channel.ts` ⇒ 唯一引入者就是 **batch-66 的 `7b6b861e feat(channel): 16.1.1.2 绩效指标监控`**。对照 batch-65 基线（`5636f458`）：那时契约里**没有**这个类型、后端也**没有** `criticalCount` ⇒ **16.1.1.2 车道自己的契约重生成比它自己的后端 DTO 旧了一拍**，把漂移一起带了进来。
⇒ **不是本批引入的**，也**不是 main 同步带进来的**（`e4a358fd..HEAD` 对 `packages/api-contracts/` 与 `backend/services/channel/` 的 diff 均为空）—— 是 batch-66 遗留、到本批才被契约闸逮住。

**修复**：契约是 codegen 产物，**禁止手改**（AGENTS.md）。16.1.2.1 车道跑 codegen 时独立发现了同一处漂移并顺手补齐（**由 codegen 从真实 spec 生成，非手改**），本批并入它时由 merge-union 驱动器按「导出类型名」求并修正 ⇒ 并入后 `channel.ts` 已带 `criticalCount` 等字段（已复核）。

**教训**：车道跑完 codegen 之后**必须再跑一次 codegen 并比对**（契约闸存在的意义就是抓这种「后端改了、契约没跟上」）；车道报的「channel 既有漂移」不该自己改（15.4.1.1 车道的处理是对的：还原、留给调度者统一处理）。

### 缺陷 2：channel 模块偶发假红 —— stub 端口遮蔽让 404 变成 503（本轮 L2 第 1 步抓到）

**现象**：本批合并后重跑 L2，第 1 步（`./mvnw verify`）在 **channel** 模块红：

```
[ERROR]   StockForecastAvailabilityPortTest.inTransitSumsOutstandingLegsAndKeepsMissingAsNull:140
          » ResponseStatus 503 SERVICE_UNAVAILABLE "warehouse 服务返回 404：在途数据读不到：…"
[ERROR] Tests run: 1733, Failures: 0, Errors: 1, Skipped: 0
```

同一测试类 6 例只红这 1 例。

**取证与定位**：

1. **本批 4 条车道一行都没改**这个测试与它测的端口
   （`git diff c92aeb12 HEAD -- …/ForecastInTransitQueryPort.java …/StockForecastAvailabilityPortTest.java` ⇒ **空**）。
2. **单跑绿**（`-Dtest='StockForecastAvailabilityPortTest'` ⇒ 6/6）；**整模块重跑也绿**（同树 1733 例 0 失败）
   ⇒ 属「整模块连跑才会偶发」的顺序/时序型假红。
3. **根因由独立 Java 程序证明**（macOS + JDK HttpServer + SO_REUSEADDR）：

   ```java
   HttpServer specific = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); // 别的测试的 stub，端口 P
   specific.createContext("/only-catalog", …); specific.start();
   HttpServer wildcard = HttpServer.create(new InetSocketAddress(P), 0);               // 本测试的 stub，同一端口
   wildcard.createContext("/", …); wildcard.start();
   // => macOS 上 bind 成功（JDK 默认开 SO_REUSEADDR）；更具体的 127.0.0.1 绑定优先
   // => 客户端打到【别的测试的 stub】⇒ 404 No context found ⇒ 端口翻成 503「读不到」
   ```

   实测输出：`wildcard bind on SAME port 56563 => ALLOWED` / `client status=404 body=No context found for request`。

   ⇒ `StockForecastAvailabilityPortTest` 的 stub 用**通配地址** `new InetSocketAddress(0)`（绑 0.0.0.0），
   而另有 13 个 stub 类用**显式回环** `new InetSocketAddress("127.0.0.1", 0)`。
   两种写法在同一个 channel 模块里并存（通配写法是**多数**：全仓 351 个测试文件用通配），
   只要两者在同一端口上相遇，**更具体的回环绑定会遮蔽通配绑定** ⇒ 客户端拿到**别的测试的 stub** 的 404
   ⇒ 端口按纪律把它翻成 503（「读不到就报读不到，绝不用 0 冒充」——**这一步是对的，不该改**）。

4. **本批把它推到了可触发区间**：16.1.2.1 新增的 `PlatformViolationIntegrationTest` / `PlatformViolationTenancyIntegrationTest`
   各起 2 台**通配地址**的**静态** stub 且**没有停机**（端口被占住整轮 JVM 生命周期）⇒ channel 模块的 stub 数量与端口压力上升。

**缺陷本身是 batch-46 就有的**（那个车道写的测试用了通配地址），**本批只是提高了触发概率**。

**修复**：派修复车道 `fix/10-4-2-2-stub-port-shadowing`（模块 10.4.2.2 备货预报），commit `950caec9` → 并入 `fc8880c0`：

| 文件 | 改了什么 | 为什么 |
| --- | --- | --- |
| `StockForecastAvailabilityPortTest.java` | stub 绑定 `new InetSocketAddress(0)` ⇒ `new InetSocketAddress("127.0.0.1", 0)`；`base` `http://localhost:<port>` ⇒ `http://127.0.0.1:<port>`；`@AfterEach` 加空值保护 | 主修：显式回环绑定在同端口竞争中**胜出**；若真撞端口，第二个 stub 会**响亮地 `BindException`**，而不是静默「回答错误的那台」——**把「静默给出错误答案」换成「明确报错」** |
| `PlatformViolationIntegrationTest.java` | 补 `@AfterAll stopStubs()` | 那两台**静态** stub（identity / warehouse）原先**从不停机**，端口被占住整轮 JVM 生命周期 |
| `PlatformViolationTenancyIntegrationTest.java` | 补 `@AfterAll stopStubs()` | 同上（identity 一台） |

**修复车道自验证**：目标类单跑 6/6；**整模块连跑 ×3 次，每次 1733 例 0 失败 0 错误**（与调度者失败那次的总数一致）；
`compile` exit 0；`typecheck` 3/3。**生产代码一行未改**，台账未动。

**遗留（已记进修复车道的会话记录，下一轮可开专门车道收敛）**：全仓 `backend/services/*/src/test/java` 里
**350 个文件 / 459 处** `HttpServer.create(new InetSocketAddress(0), 0)` 通配绑定
（channel 105 / trade 93 / warehouse 63 / catalog 57 / crm 32）—— 同一个遮蔽机制在别的模块里也可能偶发假红；
另有两个 `PlatformViolation*` 类的 `PostgreSQLContainer pg` 也是静态且从不停止。

### 缺陷 3：车道被并入后 amend commit ⇒ 车道永久卡 conflict（本轮开场处理）

**现象**：本轮开场的 `merge-ready` 报 `16.1.1.2 并入冲突：docs/sessions/2026-10/2026-10-22-16-1-1-2-performance-metrics.md`（add/add）。

**根因**（reflog 逐字取证）：

```
97b838f5 feat/16-1-1-2@{0}: commit (amend): docs: 16.1.1.2 绩效指标监控 —— 台账状态与会话记录
138b7ee6 feat/16-1-1-2@{1}: commit:         docs: 16.1.1.2 绩效指标监控 —— 台账状态与会话记录
```

batch-66 在 19:36:56 已经把该车道并入（`3cbbf834`），**worker 却在并入之后 amend 了自己的最后一个 commit**（把文档里的例数/路径数改对）⇒ 分支顶变成 `97b838f5`；随后 worker 又调了一次 `land`，把车道状态从 `merged` 打回 `ready`，`headSha` 记成 `97b838f5`。

⚠️ **更糟的是自动补记救不回来**：`lane.mjs merge-ready` 里「conflict 且 `headSha` 已是集成分支祖先 ⇒ 补记 merged」这条判据对 `97b838f5` **不成立** —— 并入时 merge-union 驱动器按类型名求并**重建**了那个 docs commit（`138b7ee6`），原 `97b838f5` 永远不是集成分支的祖先 ⇒ **该车道会永久卡在 conflict**，`gc` 不清它的工作树，每轮 `merge-ready` 重复报同一条冲突。

**本轮的处理**（可审计）：两个代码树只差 2 个文件（台账 + 会话纪要），**代码逐字节相同** ⇒ 纯文档并入。逐项核对后确认 **amend 版本才是准确的那个**：

| 数字 | 并入版本 `138b7ee6` | amend 版本 `97b838f5` | 代码实测 |
| --- | --- | --- | --- |
| openapi 新增路径 | 11 | **12** | `channel.openapi.json` 含 `performance-metrics` 的路径 = **12** ✅ |
| 新增 DTO | 18 | **20** | `channel.ts` 里 `PerformanceMetric*` 导出 = **20** ✅ |
| `performance-metrics-model.test.ts` | 22 例 | **23 例** | `it()` 计数 = **23** ✅ |
| `performance-metrics-allowlist.test.ts` | 7 例 | **6 例** | `it()` 计数 = **6** ✅ |

⇒ 冲突**取 amend 版本**，台账 `16.1.1.2` 那一行一并换成 amend 版本（台账其余 464 行保持并入驱动器的求并结果，行数校验 = 465 行）；合并 commit `c92aeb12`；下一轮 `merge-ready` 走脚本自带的「conflict 且已是祖先 ⇒ 补记 merged」路径补记为 `merged`（本次实测该路径生效）。

**给后续的结论**：worker **被并入之后不要再 amend commit**；确需修订文档就**新起一个 commit**。否则会把已并入的车道重新变成 `ready`，并制造一次本可避免的并入冲突。

---

### 缺陷 4：台账证据格里的裸竖线会让 `sync.mjs` 静默截断交付记录（本轮发现并修好）

**现象**：15.4.1.1 车道把工单写动作写成 `transfer|claim|release|close|reply|escalate`，
**证据格里带了裸 `|`** ⇒ 这一行的 markdown 表格被切成 **14 个格子**（应为 8 个），
表格渲染错位；更要命的是 `sync.mjs` 的行正则 `([^|\n]*)` **遇第一个 `|` 就停**
⇒ 每次同步都把该模块的交付证据**从第一个竖线处截断**（实测 3149 字 → 2370 字，**丢掉约 780 字的交付记录**）。

**取证**：HEAD 里该行 `split('|')` 出 **14** 格（其余 464 行都是 8 格）；
用 `\|` 转义**也不管用**（正则不认转义）。

**修法**：证据格**不允许出现竖线**，把 `transfer|claim|release|close|reply|escalate` 改成
`transfer、claim、release、close、reply、escalate` ⇒ 恢复 8 格 / 3149 字，`sync.mjs` 复跑后证据**完整保留**。

**给车道的纪律**：**台账的证据格里不要写 `|`**（`|` 是表格分隔符）；要并列就用 `、` 或 `/`。
（这属于 `sync.mjs` 的已知格式约束，脚本本身没有转义支持。）

---

## 本轮 L2 批量验证

**最终结果：🟢 L2 GREEN**（`===== L2 GREEN =====` / `L2 END 2026-10-23 00:00:51`，日志 `.dev-agent/l2-run-batch-67-final.log`）

| 步骤 | 闸 | 结果 |
| --- | --- | --- |
| 1/6 | 后端 `./mvnw verify` | ✅ `MVN_EXIT=0` —— **8440 例，0 失败 0 错误**（batch-66 是 8317 ⇒ 本批 +123） |
| 2/6 | `pnpm typecheck` / `lint` / `test` | ✅ 三个都是 0 —— 前端 **486 文件 / 7079 例全绿**（batch-66 是 478 / 6988 ⇒ 本批 +8 文件 / +91 例） |
| 3/6 | Next production build | ✅ `BUILD_EXIT=0` |
| 4/6 | `sync --check` | ✅ `SYNCCHECK_EXIT=0` |
| 5/6 | `merge-union.itest` + codegen python 单测 | ✅ `ITEST_EXIT=0` / `CODEGEN_EXIT=0` |
| 6/6 | **契约闸**（CI catalog-e2e 同款：重新生成 + 逐字节比对） | ✅ `CONTRACT_GENERATE_EXIT=0` / **`CONTRACT_GATE_EXIT=0`** / `CONTRACT_TREE_DRIFT_EXIT=0` |

⇒ 缺陷 1（契约漂移）与缺陷 2（端口遮蔽假红）**都被这一次 L2 证明已修复**：
契约闸从上一轮的 `CONTRACT_GATE_EXIT=1` 变成 **0**（契约产物与 codegen 输出逐字节一致），
channel 模块在整模块连跑下**不再**出现 `StockForecastAvailabilityPortTest` 的 404⇒503。

**本轮 L2 一共跑了 3 次**（每次都是「集成头上的一次全量」，没有跑全量的车道）：

| 次 | 时间 | 结果 | 红的在哪 | 处置 |
| --- | --- | --- | --- | --- |
| ① | 21:14:25 – 22:00:39 | 🔴 | 第 6 步**契约闸**：`channel.openapi.json` / `contracts/channel.ts` 漂移（batch-66 遗留） | 定位到引入点 `7b6b861e`；16.1.2.1 车道的契约重生成已顺手补齐 ⇒ 随本批并入修复 |
| ② | 22:06:43 – 22:23:21 | 🔴 | 第 1 步 **channel 模块**：`StockForecastAvailabilityPortTest` 1 例 404⇒503 | 证明是**端口遮蔽型假红**（独立 Java 程序复现机制）⇒ 派修复车道 `fix/10-4-2-2-stub-port-shadowing`（`950caec9`）⇒ 并入 `fc8880c0` |
| ③ | 23:15:12 – 00:00:51 | 🟢 | —— | —— |

**静态预检**（本批 4 条新迁移）：`ADD COLUMN` 无 `DEFAULT` **0 命中**、`SET NOT NULL` **0 命中**、
新增 `ADD CONSTRAINT ... CHECK` **0 命中** ⇒ 跨车道 DDL 回归面为 0（四条迁移全是纯新建表）。

## 本轮收尾三件套

| 件 | 结果 |
| --- | --- |
| ① **写说明** | ✅ 本文件 `docs/sessions/2026-10/2026-10-22-batch-67-crm-ticket-auto-reply-and-violation.md` —— 4 个模块各一节（做什么 / 迁移 / 端点 / 页面 / 测试 / 关键设计决策）+ 3 个缺陷的取证与处置 + L2 结果。附带并入同一批的 `2026-10-22-pipeline-zero-dispatch-contract-drift-rootcause.md`（**22:00 那一轮零派发 tick 写的契约漂移根因定位**，它独立复现了同一个契约漂移结论，本轮把它一并提交，不让它变成孤儿文件） |
| ② **commit** | ✅ 与台账**同一个 commit**：`docs(batch-67): …` |
| ③ **push** | ✅ `git push origin integration/module-dev`（**只推集成头，绝不推 main**） |

## 本轮编队与容量闸（21:00 那一轮）

- 容量闸：温度 `pmset -g therm` = `No thermal warning level has been recorded`（**无降频**）；load 1.25 / 1.59 / 1.79（阈值 12）；带 `org.testcontainers.sessionId` 标签的 PG 容器 **0**（阈值 6）；在途车道 0（配额 6）；节流闸距上批 180 分钟 ≥ 50 ⇒ **GO**。
- 档位：当前 21:00 < `TOKEN_HORIZON` 2026-10-25 ⇒ **高强度模式**，`LANES=4` 满档。
- 派发 4 条（互不依赖、L3 锚点各不相同、落点服务 crm ×3 / channel ×1 均已存在于集成头）：
  15.1.2.2 邮件规则（crm，V305）· 15.4.1.1 工单列表（crm，V306）· 15.5.1.1 自动回复（crm，V307）· 16.1.2.1 违规与处罚（channel，V308）。
- 并入 commit：`1198d8ae` / `f4c55803` / `fa810233` / `4f9015f8` + 修复车道 `fc8880c0`。

## 本轮进度与遗留

- 台账：**328 / 465 已交付**（69.9% 之前的 324 ⇒ **+4**），未开发 137，进行中 0。**离全部交付还差 137 个模块。**
- 遗留（下一轮可开专门车道）：① 全仓 350 个测试文件 / 459 处 `HttpServer.create(new InetSocketAddress(0), 0)` 通配绑定，同一遮蔽机制在别的模块可能偶发假红；② 两个 `PlatformViolation*` 类的 `PostgreSQLContainer pg` 也是静态且从不停止。
- 下一轮 tick 需要回收的：**无**（本批 4 条车道已全部并入并验证）。
