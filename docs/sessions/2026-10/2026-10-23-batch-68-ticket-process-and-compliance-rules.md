# batch-68 · 卷 16 合规风控 ×3 + 卷 15 客户与客服 ×1（4 条车道并入 + L2 全量验证）

> 本轮编队：2026-10-23 00:00 tick。4 条车道并行开发，`LANES=4`（MODE=full，`TOKEN_HORIZON=2026-10-25` 未到）。
> 调度闸在本轮开场被**两个活 tick** 先后持有（batch-67 收尾 + main 同步任务），按协议等其释放后于 00:08 取闸。

## 批次构成

| 车道     | 模块               | 服务    | 迁移 | 分支            | commit    |
| -------- | ------------------ | ------- | ---- | --------------- | --------- |
| 15.4.1.2 | 工单处理           | crm     | V309 | `feat/15-4-1-2` | `8ce7487` |
| 16.1.2.2 | 申诉管理           | channel | V310 | `feat/16-1-2-2` | `a2fed45` |
| 16.2.1.1 | 规则管理（规则库） | channel | V311 | `feat/16-2-1-1` | `4ec1cfe` |
| 16.3.1.1 | 税号与辖区         | channel | V312 | `feat/16-3-1-1` | `7ff4b99` |

**api-proxy 锚点互不重叠**（本批 4 条车道都改 `apps/web/src/lib/api-proxy.ts`）：
15.4.1.2 → `cart-recovery` 之后（数组末尾）／16.1.2.2 → `platform-violations` 之后／
16.2.1.1 → `performance-metrics` 之后／16.3.1.1 → `account-health` 之后。
四条车道各自**纯新增一条独立 `services[]` 元素**，既有条目一字未动、也不重排 ⇒ git 能干净求并。

---

## 模块 16.1.2.2 申诉管理

**这个功能做什么**：平台判了你违规之后，运营能在这条违规下面**发起申诉**——写申诉文本、挂举证材料、
提交给平台、盯住配额用了几次、记下平台最后是通过还是驳回；被驳回可以退回草稿再提一次。
配额有限，所以系统会按违规的影响档位（停号 > 下架 > 限制销售/扣款 > 警告）排出优先申诉队列。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V310__channel_platform_appeal.sql`
  —— **纯新建 5 张表，零 ALTER 既有表、零外键**：申诉单 / 举证材料 / 提交记录 / 结果记录 / 配额。
  命名空间 `platform_appeal_*`，与上游 `platform_violation_*` / `platform_penalty_*` 及 16.1.1.1 的
  `account_health_*` 彼此隔离；跨表引用（申诉 → 违规）全用**触发器**校验。
  全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** ROW LEVEL SECURITY + `data_scope`(RESTRICTIVE) + GRANT `channel_app`。
- 端点：`/channel/platform-appeals/**` —— **15 个方法 / 13 条路径**
  （capabilities · shops · appeals(GET/POST) · appeals/{id}(GET) · /text · /evidence · /submit ·
  /decision · /withdraw · /reopen · quotas(GET/PUT) · stats · priority-queue）。
  Java：`ComplianceAppealController` / `ComplianceAppealService` / `ComplianceAppealRules` / `ComplianceAppealModels`。
- 页面：`/compliance/appeals` —— `apps/web/src/app/compliance/appeals/page.tsx` +
  `apps/web/src/components/compliance-appeals/`（model + workspace + model.test）。
  **独立于** `/compliance/violations` / `/compliance/account` / `/compliance/metrics`。
- 测试：`PlatformAppealIntegrationTest` 18 例 · `PlatformAppealRulesTest` 6 例 ·
  `PlatformAppealTenancyIntegrationTest` 3 例 = **27 例全绿**；前端 model + allowlist 共 16 例。

**关键设计决策**：

1. **配额 = 店铺 + 周期**（周期用 `timestamptz`，避开 `::date` 的时区漂移），
   `used_count <= quota_limit` 由 DB 层 CHECK 钉死；**已用次数只认事务内的提交标记**，
   手工 `+1` 也被拒 —— 否则配额统计就是个可以随手改的数。
2. **驳回可回草稿再提**：否则提交次数恒等于 1，「申诉次数统计」与「配额」都只是口号。
   申诉单 / 提交记录 / 结果记录三样 **append-only**，驳回再提不抹掉上次结果。
3. **影响档位派生不落库**（用 DB 函数，与上游 `PlatformViolationRules.impactLevel` 逐档位对拍），
   避免与上游的派生口径分叉。
4. **上游 16.1.2.1 只读不写**；下游「平台申诉接口」本仓不存在 ⇒ 能力声明标 `available=false` + `ownerModule`，
   **不造指向不存在入口的放行形状**。
5. **契约类型前缀改名**：原拟 `PlatformAppeal*` 已被 trade 的 11.4.1.1 占用，扁平命名空间撞名会让 codegen
   直接失败 ⇒ 生产类型改 `ComplianceAppeal*`（对齐同服务 `CompliancePrecheck*` 约定），
   **DB 表前缀仍是 `platform_appeal_*`**；测试类名保留 `PlatformAppeal*`，
   否则任务书指定的 `-Dtest='PlatformAppeal*'` 会抽到 0 例并**假绿**。

---

## 模块 16.2.1.1 规则管理（蓝图标题「规则库」）

**这个功能做什么**：运营能维护一份**带版本的**合规规则库——违禁词 / 极限词 / 侵权词 / 认证要求 /
禁限售 / 标签要求 / 材质要求 七类规则，严重度分 阻断 / 警告 / 提示，按适用平台与站点收敛；
改规则是**新增一个版本**而不是就地改词，所以「当时那条商品到底违不违规」永远可回溯。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V311__channel_compliance_rule.sql`
  —— **4 张新表，9 个触发器，零 ALTER 既有表、零外键**：
  `compliance_rule` / `compliance_rule_version` / `compliance_rule_term` / `compliance_rule_scope`。
  RLS：isolation ×4 + `data_scope` ×1；GRANT `channel_app` / REVOKE `starcon_app`。
- 端点：`/channel/compliance-rules/**` —— **16 条路径**
  （capabilities · shops · rules(GET/POST) · rules/{id}(GET/PUT) · versions · versions/effective ·
  versions/{vid} · terms · terms/upload · scopes · effective）。
  Java：`ComplianceRuleController` / `ComplianceRuleService` / `ComplianceRuleRules` / `ComplianceRuleModels`。
- 页面：`/compliance/rules` —— `apps/web/src/app/compliance/rules/page.tsx` +
  `apps/web/src/components/compliance-rule/`（真实工作区：统计卡 / 能力声明 / 规则集 / 生效规则集 /
  店铺范围 / 版本详情 / 历史可解释弹窗 / 四态空态）。
- 测试：`ComplianceRuleIntegrationTest` 21 例 · `ComplianceRuleRulesTest` 5 例 ·
  `ComplianceRuleTenancyIntegrationTest` 3 例 = **29 例全绿**；前端 model 7 + allowlist 5 = 12 例。

**关键设计决策**：

1. **「规则版本化是关键」落成六条物理事实**：改规则 = 新增版本；版本内容不可改写
   （唯一例外 `effective_to` 只允许向前关闭）；生效时刻不许回填；同一时刻只允许一个版本生效
   （触发器自动关闭上一版）；版本号必须连续；词条与适用范围属于版本、生效后锁死且不可删除。
2. **严重度落在版本上、不落在规则上** —— 否则旧版本会读到「今天的严重度」，
   历史判定结果就没法解释了。
3. **值域由 DB 层 CHECK 钉死**：规则类型 7 种、严重度 3 种，值域可审阅，不只靠应用层 enum。
4. **适用平台/站点读本服务自己的 `platform` / `marketplace` / `shop`**，不复制目录表；店铺维度用 `data_scope` 收敛。
5. **下游未交付一律不编造**：16.2.1.2 拦截执行未交付 ⇒ 不建拦截表、不造「立即校验/放行/豁免」入口；
   02 政策适配器未交付 ⇒ 只有人工维护这一条真实输入。

---

## 模块 16.3.1.1 税号与辖区

**这个功能做什么**：把「每个店铺/法人在每个销售辖区有没有报税身份」变成一张可查的档案——
税号、VAT 状态、是否注册、EPR 注册号、注册有效期、申报周期；并且**真的算出缺什么**：
没登记的辖区、缺 VAT 号、缺 EPR 号、已过期的登记，逐条带严重度、人话说明和整改建议。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V312__channel_tax_registration.sql`
  —— **2 张表，零外键**：`tax_registration_jurisdiction`（全局受控辖区码表，89 行辖区种子，
  带「税制 / 是否要登记号 / 是否要 EPR / derivation_scope」四列）+ `tax_registration_record`
  （`tenant_id NOT NULL` + ENABLE/FORCE RLS + GRANT，跨表引用走触发器）。
- 端点：`/channel/tax-registration/**` —— **10 个**
  （capabilities · jurisdictions · shops · registrations(GET+POST) · registrations/{id}(GET+PUT) ·
  gaps · gap-summary · coverage）。
  Java：`TaxRegistrationController` / `TaxRegistrationService` / `TaxRegistrationRules` /
  `TaxRegistrationModels` + `LegalEntitySourcePort`（17 法人的跨服务只读端口）。
- 页面：`/compliance/tax` —— `apps/web/src/app/compliance/tax/page.tsx` +
  `apps/web/src/components/tax-registration/`（model + workspace + model.test）。
- 测试：`TaxRegistrationRulesTest` 16 · `TaxRegistrationIntegrationTest` 16 ·
  `TaxRegistrationTenancyIntegrationTest` 3 = **35 例全绿**；前端 model 10 + allowlist 6 = 16 例。

**关键设计决策**：

1. **缺失项是真算出来的**，不是一句提醒：推导链
   `shop_marketplace → marketplace.country_code + vat_required → 受控辖区码表 → 该店/其法人在册登记`，
   六类缺失项逐条带严重度 + 人话说明 + 整改建议，列表与汇总均真分页真筛选。
2. **辖区值域由 DB 层钉死**：`jurisdiction_code` 必须落在受控码表内，自由文本在 DB 层被拒。
3. **「推不出」不等于「都齐了」**：美国各州 `derivation_scope='SUBNATIONAL'`
   （本仓无销售事实表，推不出州一级 nexus）—— 这一点写进能力声明与前端空态，
   避免把「推不出」显示成「合规」。
4. **法人只存逻辑 ID**：不复制 identity 任何一列、不建跨库 FK；读不到时 `available=false` + 原因，
   **不显示成空白、也不显示成「未注册」**；法人登记覆盖其店铺。
5. **不含任何金额**：税额归 12.6（finance 服务本仓未落地）⇒ 不建财务表、不做浮点记账。

---

## 模块 15.4.1.2 工单处理

**这个功能做什么**：客服能真正**处理**一条工单——回客户、加内部备注、流转状态、挂上订单与售后单、
收满意度评分；并且把「关单」和「问题真的解决了」**分成两个动作**：
只有确认了实际结果（退款到账、换货发出）才计为解决。另外 SLA 计时可以在处理过程中**暂停/恢复**，
暂停的时间不计入到期时刻——而且这条轴走的是**营业时间**而不是自然时间。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V309__crm_service_ticket_process.sql`
  —— **crm 自建 5 张表**（工单事实本身不在这里）：
  `crm_service_ticket_action`（处理动作）/ `crm_service_ticket_note`（备注）/ `crm_service_ticket_link`（关联单据）/
  `crm_service_ticket_resolution`（解决确认）/ `crm_service_ticket_sla_pause`（SLA 暂停）。
  全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** ROW LEVEL SECURITY + GRANT。
- 端点：`/crm/service-ticket-process` —— **15 条 path**
  （`ServiceTicketProcessController.java`）。
  Java：`ServiceTicketProcessService` / `Models` / trade 写端口 / SLA 轴计算。
- 页面：`/crm/service-ticket-process` —— `apps/web/src/app/crm/service-ticket-process/page.tsx` +
  `apps/web/src/components/crm/service-ticket-process-{model,workspace}.tsx`（真实工作区，非占位页）。
- 测试：`ServiceTicketProcessIntegrationTest` 23 例 · `ServiceTicketProcessTenancyIntegrationTest` 4 例 ·
  `ServiceTicketProcessSlaAxisTest` 9 例 = **36 例全绿**；前端 model + allowlist 共 20 例。

**关键设计决策**：

1. **「工单关闭 ≠ 问题解决」落成两个动作、两张表、两个响应字段**：
   `close`（关闭）与 `resolution`（解决确认）分开，只有后者才计为解决 —— 这正是蓝图加粗句要防的误判。
2. **回复 / 状态流转 / 关闭 / 满意度 / 升级一律**转发 trade 11.4.2.1 的授权 HTTP API
   （转发**调用方自己的 Authorization bearer**，租户由上游按已认证成员关系解析），
   **不另存一份** —— 上游是这些事实的唯一写入所有者，另存就会出现两个事实源。
3. **trade 没有对应端点的那些**（备注 / 处理记录 / 关联单据 / 解决确认 / SLA 暂停）才由 crm 自建表，
   因为上游根本没有这些形状。
4. **SLA 沿 15.4.1.1 的营业时间轴**（`BusinessHoursCalendar` + `crm_service_business_hours` / `_holiday`），
   **不是** `createdAt + N 小时`；没配营业时间一律 NO_CALENDAR，**刻意不退回自然时间**。
   暂停期间剩余量**仍然计算**（不是直接归零），并新增独立的 `PAUSED` 状态 ——
   暂停中的计时器不能读成「进行中」或「已逾期」。
5. **处理结果推不回上游售后单/订单**：trade 只有读与新建端点，没有「改写」端点 ⇒
   能力声明标 `linked_document_push available=false + ownerModule=11.1.1.1`，界面不假装能推。

---

## 🐛 本批抓到的五个真问题（不是流程噪声）

### 问题 1：4 条车道改同一个 `api-proxy.ts` —— 锚点不重叠才换来干净求并

`apps/web/src/lib/api-proxy.ts` 是**多车道共用的累积型白名单**，每条服务规则是一段超长单行正则。
往既有那条巨型 alternation 里并分支，会让多车道同时并入时 **git 必然判冲突**（结构性，retry 永不自愈）。

本轮 4 条车道**全部**要改这个文件（4 个新前端模块 ⇒ 4 条新路由）。调度者事先给每条车道指派了
**互不重叠**的插入锚点：

| 车道     | 插入锚点                         |
| -------- | -------------------------------- |
| 16.3.1.1 | `account-health` 之后            |
| 16.2.1.1 | `performance-metrics` 之后       |
| 16.1.2.2 | `platform-violations` 之后       |
| 15.4.1.2 | `cart-recovery` 之后（数组末尾） |

⇒ **merge-ready 4/4 一次并入、零冲突、零人工解冲突**。
并入后调度者又独立核验了一遍：4 条新规则**首 token 全不重复**、且
**新模块的 4 条样本路径被既有全部 165 条正则命中 0 处**（互不遮蔽），
`platform-appeals` 与既有 `platform-dispute-appeals`（trade 11.4.1.1）也不同首 token，互不吃掉。

### 问题 2：codegen 的 `.ts` 从**活 spec** 生成，而提交的 `.openapi.json` 快照是 **key 排序**的

把**已提交的快照**喂回 codegen，会让其它服务的 `.ts` 全量重排 —— 一次纯排序 churn 伪装成几千行 diff。
本批 3 条车道先后踩到（15.4.1.2、16.2.1.1 都在过程中发现并还原）。

调度者独立核验了每条车道的契约变更**语义上纯增量**：

| 车道     | paths | schemas | 删除 | 既有 schema 内容变化 |
| -------- | ----- | ------- | ---- | -------------------- |
| 15.4.1.2 | +15   | +29     | 0    | 0                    |
| 16.1.2.2 | +13   | +25     | 0    | 0                    |
| 16.2.1.1 | +11   | +28     | 0    | 0                    |
| 16.3.1.1 | +13   | +17     | 0    | 0                    |

⇒ 且每个 `.openapi.json` 都**逐字节等于 codegen 的 `sort_keys=True` 规范形式**
（调度者用 codegen 自身的写法重算并比对过）⇒ L2 的契约闸能过。

### 问题 3：上游能力声明在本批并入后变成**假话**（本轮新开校正车道）

本仓的规矩是「**下游未交付一律不编造**」—— 上游能力声明把未交付的下游标成
`available=false` + `ownerModule`。batch-68 交付了三个下游，于是**三处上游声明当场过期**：

| 上游模块            | 服务    | 过期声明                                |
| ------------------- | ------- | --------------------------------------- |
| 15.4.1.1 工单列表   | crm     | `ticket_processing` ⇒ `available=false` |
| 15.5.1.1 自动回复   | crm     | 「15.4.1.2 工单处理**未交付**」         |
| 16.1.2.1 违规与处罚 | channel | 「16.1.2.2 申诉管理**未交付**」         |

两条车道都**主动指出**了这一点并按「只碰自己的文件」的纪律**没有越界去改**（这是对的纪律，不是疏漏）。

⇒ 调度者开了一条**校正车道** `cap-refresh-68`（分支 `fix/capability-declaration-refresh-68`），
只改**声明层**（常量 / `available` 标志 / javadoc / 对应测试断言），**不动业务逻辑、不加迁移**。
它已在本批 push 之后派发，下一批回收。

⚠️ 顺带留下一条**刻意不做**的跨模块口径统一（留给后续车道）：
15.4.1.1 列表里的 SLA 剩余**不含暂停感知**，而 15.4.1.2 的 SLA 轴是暂停感知的 ——
暂停中的单子在两处口径可能短暂不一致。15.4.1.1 的列表要读暂停表就得改它的查询，
超出「声明校正」的范围，**不在本轮范围**。

### 问题 4：全仓 **159 个文件 / 450 处** UTF-8 字符损坏（既有系统性缺陷）

调度者并入后做了一次全仓编码体检（`git ls-files` 全量按严格 UTF-8 解码后统计 `U+FFFD`），
发现 **159 个文件**里存着 **450 个替换字符** —— 覆盖后端 Java（107）、前端 TS/TSX（29）、
文档（21）、工具脚本（2）。

**这不是 batch-68 引入的**：batch-68 自己只新增了 **8 处**（全部在 lane 16.3.1.1 的 3 个文件里，
且**全部是装饰性的** —— javadoc、SQL `--` 注释、测试断言的失败消息，零功能影响），
已在 push 前修掉并重跑了受影响模块的 L1。

成因看样本像是**按字节截断**：每处损坏正好吃掉 1 个汉字或 1 个标点的 2–3 个字节
（而不是编码转错表该有的成片 `æ–‡`）。⇒ **先取证成因，再谈批量修复** ——
不知道成因就修，修完还会再坏；而且 159 个文件一次性改完的 diff 会与每一条在途车道大面积冲突。

已单独记进 `docs/backlog/2026-10-23-replacement-char-encoding-corruption.md`，
建议后续按「每批 ≤ 20 个文件」分批修 + 加一道 CI 全仓扫描。

⚠️ 顺带发现一处**运行时字符串**上的同类损坏（来自 batch-67 的 16.1.2.1）：
`PlatformViolationService.java` 的 `UPSTREAM_NOTE` 里「写了**���**条」—— 这段会真的出现在
`/channel/platform-violations/capabilities` 响应里。已并进校正车道一并修掉。

### 问题 5：L2 契约闸红 —— **根因在合并驱动器，不在车道代码**

本轮第一次 L2 在**第 6 步「契约闸」**红，前 5 步全绿：

| 步骤                                             | 结果                                               |
| ------------------------------------------------ | -------------------------------------------------- |
| [1/6] backend `./mvnw verify`                    | `MVN_EXIT=0`                                       |
| [2/6] `pnpm typecheck && pnpm lint && pnpm test` | `TYPECHECK_EXIT=0` / `LINT_EXIT=0` / `TEST_EXIT=0` |
| [3/6] Next production build                      | `BUILD_EXIT=0`                                     |
| [4/6] `sync --check`                             | `SYNCCHECK_EXIT=0`                                 |
| [5/6] merge-union.itest + codegen python 单测    | `ITEST_EXIT=0` / `CODEGEN_EXIT=0`                  |
| [6/6] 契约闸（重新生成 + 逐字节比对）            | **`CONTRACT_GATE_EXIT=1`** ❌                      |

```
Files .tmp/l2-contract-gate/channel.openapi.json and packages/api-contracts/src/channel.openapi.json differ
===== L2 FAIL at contract drift（契约产物与生成器输出不一致） (exit 1) =====
```

**漂移长什么样**：paths 781=781、schemas 1487=1487、零删除零新增 —— **只有 6 个 schema 的
`minimum` 是 `0` 而不是 `0.0`**（`AdSearchTermInput.sales/spend`、`AdKeywordInput.bid`、
`AdKeywordMetricInput`、`BatchCandidateRequest`、`LogisticsCostParameterRequest`、
`CreateListingPublishJobRequest.priceAmount`）。**没有一条与本批的四个模块有关。**

**引入点 = 合并，不是任何一条车道**：

```
a38cbfd1 0.0 ｜ 7b6b861e 0.0 ｜ b946e965 0.0 ｜ f36ac759 0.0（batch-67 的头）
a2fed453 0.0（16.1.2.2 车道自己的 commit）｜ 4ec1cfe4 0.0（16.2.1.1 车道自己的 commit）
a383f077 0   ← merge(16.3.1.1) 合并提交，这里塌成了 0
```

⇒ **车道自己的 commit 全是 `0.0`（正确）；只有经过 `merge-union` 驱动器之后才变成 `0`。**
本批 3 条 channel 车道同时改 `channel.openapi.json` ⇒ 这是**第一次真的触发**该驱动器的 JSON 重序列化
（此前 channel 每批只有一条车道改该文件 ⇒ 走单边快进，驱动器根本没被调用）。

**根因**（调度者实测对照）：

```
JS     : JSON.parse → JSON.stringify  →  {"m":0,"n":0.000001,"i":0}
Python : json.loads → json.dumps      →  {"i": 0, "m": 0.0, "n": 1e-06}
```

`merge-union.mjs` 的求并流程是 `JSON.parse → 合并 → JSON.stringify → 交给 python3 的 json-format.py 写盘`。
**JavaScript 的数字只有一种类型**，`JSON.stringify(0.0)` 写成 `0`；Python 端 `json.load` 读到的是
**int `0`** ⇒ 写出来就是 `0`。而 codegen **不经 JS**（Python 直读 spec）⇒ `0.0` 被原样保留。

⚠️ 顺带发现：`json-format.py` 的 docstring 声称已经处理掉的 `1e-6` / `1e-06` 那一例**其实也是坏的** ——
JS 先把它写成了 `0.000001`，Python 拿到的就已经是 `0.000001` 了，轮不到 Python 决定怎么写。
**docstring 与实际行为不符**：真相在 JS 序列化那一步就已经丢了。

⇒ 已派**工具修复车道** `merge-union-float-68`（分支 `fix/merge-union-float-preservation-68`）：
把 `.openapi.json` 的求并搬到 Python 侧（天生保真，且与 codegen 同一套 `json.dumps` 调用），
并加一条**带 float 字面量的回归测试**锁住这个缺陷。
⚠️ 本车道**只修工具，不动 `packages/api-contracts/**` 产物** —— 产物由调度者在驱动修复合入后单独重新生成。

**修法与验收**（车道交付实测）：

- `.openapi.json` 的求并**整体搬到 Python**（`json-format.py --merge`：解析 + 三方按键求并 + canonical 序列化），
  JS 侧只留 `contracts/*.ts` 与台账的求并。
- 否决的方案：① 在 JS 里用占位符保留字面量（求并语义要拆两套，比较语义另立规则，复杂度高于直接搬）；
  ② 在 JS 里手搓 Python 的 float repr（JS 本就分不出 int/float —— 这正是根因，指数边界规则还要抄一遍）。
- **新回归用例会红在修复前的驱动器上**（恰好 4 条断言：逐字节相同 / `0.0` 没塌 / `3.0` 没塌 / 类型仍是 float）
  ⇒ 这条用例**真的把缺陷钉住了**，不是摆设。
- 判据 A（真实 codegen 输出比对）：驱动器输出与 codegen **逐字节相同**；
  同一组输入跑**修复前**的驱动 ⇒ 不相同（`AdKeywordInput.bid = 0`，codegen = `0.0`）—— 正是本批的漂移。
- 判据 B：`merge-union.itest.mjs` **47 条断言全通过**；`tools/codegen` python 单测 **17 tests OK**。
- 真实产物回归：6 份仓库真契约过驱动器，输出与 codegen 同款序列化**逐字节相同**，
  6 个受影响 schema 的 `minimum` 全部保持 `0.0`。
- 车道 commit `68761c7f`；**零契约产物**、`backend/**`、`apps/**` 未动。

**并入后调度者独立复核**：拿合并后的集成头跑一遍驱动器的真实求并路径
（base 的 `minimum` 写成 int `0`、theirs 写 float `0.0`）⇒ 输出仍是 **`0.0`** ⇒ 修复在集成头上确实生效。

**契约产物重新生成**（由 codegen 生成，**非手改**）：`packages/api-contracts/src/channel.openapi.json`
—— 与 codegen 输出**逐字节相同**，6 个 schema 的 8 个 `minimum` 全部回到 `0.0`，
paths 781=781 / schemas 1487=1487 **零增删**，其余 5 个服务与全部 `.ts` **一字未动**。
commit `7a7b7d06`。

---

## 本轮编队与容量闸（00:00 那一轮）

- **档位**：`MODE=full`（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 未到）⇒ `LANES=4`、`THROTTLE_MIN=50`、
  `SILENT_WINDOW=0`（全天运行）。
- **节流闸**：距上批 **166 分钟 ≥ 50 分钟** ⇒ 放行（00:08 通过闸门后才写 `last-batch-at`）。
- **容量闸（温度 > 配额 > PG > load）**：
  - 温度：`pmset -g therm` ⇒ `No thermal warning level has been recorded` ⇒ **无降频项**。
  - 配额：`lanesRunning=0` < `RUNNING_QUOTA=6` ⇒ GO。
  - PG：带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` = **0** 个 < 6 ⇒ GO；
    **无标签的孤儿容器 0 个**（不需要清理）。
  - load：1min **8.05** → 5min 4.66 → 15min 3.37，起初 1min > 5min > 15min 像是爬升，
    90 秒内连采 4 次确认是**瞬时尖峰后单调回落**（8.05 → 7.89 → 5.49 → 4.11）⇒ **不是爬升**，
    且全程 < `LOAD_LIMIT=12` ⇒ GO。
- **调度闸**：本轮开场被**两个活 tick** 先后持有 —— batch-67 收尾（21:01 取闸，00:02 commit+push）
  与 main 同步任务（00:03 取闸）。按协议**不抢闸**，等其释放后于 **00:08:28** 取闸；
  整轮用 `touch .dev-agent/dispatch.gate/keepalive-<新文件名>` 持续续期
  （⚠️ `touch` 已存在文件**不**续期，只有**新建文件**才会刷新目录 mtime）。
- **本轮回收**：无待并入车道（batch-67 已在上一轮并入并 push）。

---

## 本轮 L2 批量验证

**跑了两次**：第一次红在第 6 步（契约闸），定位到根因在**合并驱动器**（见「问题 5」），
修复 + 重新生成契约产物后**完整重跑一遍**（不是只补跑那一步）。

```
===== L2 START 2026-10-23 01:43:42 =====
[1/6] backend ./mvnw verify .................. MVN_EXIT=0
[2/6] pnpm typecheck && pnpm lint && pnpm test  TYPECHECK_EXIT=0  LINT_EXIT=0  TEST_EXIT=0
[3/6] Next production build .................. BUILD_EXIT=0
[4/6] sync --check ........................... SYNCCHECK_EXIT=0
[5/6] merge-union.itest + codegen python 单测 .. ITEST_EXIT=0  CODEGEN_EXIT=0
[6/6] 契约闸（重新生成 + 逐字节比对）........... CONTRACT_GENERATE_EXIT=0
                                              CONTRACT_GATE_EXIT=0   ← 本轮之前红的那一步
                                              CONTRACT_TREE_DRIFT_EXIT=0
===== L2 GREEN =====
===== L2 END 2026-10-23 02:30:28 =====
```

**规模**：后端 **522 个测试类 / 8567 例 / 0 失败 0 错误 0 跳过**；
前端 **494 个测试文件 / 7144 例全绿**；Next production build 绿。

**静态预检**（本批 4 个新迁移，合并前跑）：`ADD COLUMN` 无 DEFAULT **0**、
`SET NOT NULL` **0**、`ADD CONSTRAINT … CHECK` **0**、
`ALTER` 既有表 **0**（4 个迁移全是纯 `CREATE TABLE` 新命名空间）⇒ **互相破坏形状零命中**。
另独立核验：4 条新 api-proxy 规则的**首 token 全不重复**、
新模块的样本路径被既有全部 165 条正则**命中 0 处** ⇒ 互不遮蔽。

## 本轮收尾三件套

| #   | 事项       | 结果                                                                                                                                                |
| --- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| ①   | **写说明** | 本文件 `docs/sessions/2026-10/2026-10-23-batch-68-ticket-process-and-compliance-rules.md`（4 个模块各一节 + 5 个真问题 + 编队/容量闸 + 进度与遗留） |
| ②   | **commit** | 说明文件与台账**同一个 commit**（`docs(batch-68): …`），契约产物与工具修复各自独立 atomic commit                                                    |
| ③   | **push**   | `git push origin integration/module-dev`（**只推集成头，绝不推 `main`**）                                                                           |

本轮 push 的 commit 序列（从集成头基线 `f36ac759` 起）：

| commit                  | 内容                                                                   |
| ----------------------- | ---------------------------------------------------------------------- |
| `a4d0e9ce` … `a383f077` | 4 条车道的 merge commit（15.4.1.2 / 16.1.2.2 / 16.2.1.1 / 16.3.1.1）   |
| `ecac9ad8`              | `docs(backlog)`: 全仓 159 文件 / 450 处 UTF-8 字符损坏                 |
| `03552315`              | merge(cap-refresh-68): 上游能力声明校正 + 14 处字符修复                |
| `cf4cd09c`              | merge(merge-union-float-68): 合并驱动器 float 保真修复 + 回归测试      |
| `7a7b7d06`              | `fix(contracts)`: 重新生成 channel 契约快照（由 codegen 生成，非手改） |
| `docs(batch-68)`        | **本说明 + 台账**（同一个 commit）                                     |

---

## 本轮进度与遗留

**进度**：465 个 L4 模块 · 已交付 **332**（**71.4%**）· 进行中 0 · 未开发 **133** · 距全部交付还差 **133** 个。

### 下一轮可以接着做的（本轮刻意未做，都有明确理由）

| 项                                                                                                                                                       | 为什么没做                                                                                                       |
| -------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| 16.1.1.1 **真正接入**三个已交付的下游（读 `platform_appeal*` 出申诉状态、读 `platform_violation*` 出违规计数、读 `account_health_performance_*` 出趋势） | 那是**业务行为变更**（新数据源 + 新「待处理项」维度），不在「声明校正」车道范围内                                |
| 15.5.1.1 把工单侧介入动作接进自动回复豁免判定                                                                                                            | 同上；且翻 `available=true` 会制造**新的假话**（本模块确实没读 `crm_service_ticket_action`）                     |
| 15.4.1.1 列表的 SLA 剩余改成**暂停感知**（对齐 15.4.1.2 的口径）                                                                                         | 要改它的查询（暂停表与它同库同服务，可直接读），超出「声明校正」范围                                             |
| 全仓 159 文件 / 450 处 `U+FFFD` 损坏                                                                                                                     | 已记 `docs/backlog/2026-10-23-replacement-char-encoding-corruption.md`；**先取证成因**再按每批 ≤ 20 个文件分批修 |
| 后端 `@DecimalMin("0")` / `"0.00"` 在 springdoc 侧产出 float `0.0`                                                                                       | 想让契约里 `minimum` 稳定为 int，得在**注解层**统一 —— 那是**契约语义决策**，不是工具问题                        |

### 容量与节奏

- **服务缺口**（`plan` 报的 `serviceBlocked`）：卷 18（platform）、卷 12（finance）、卷 13（reporting）、
  卷 1（前端聚合）的**主责服务尚未在集成头里** ⇒ 这些卷的模块排不出车道，属**服务缺口**不是上游未就绪。
- **吞吐**：本轮实际耗时约 **1 小时 45 分钟**（00:00 取闸 → 01:43 重跑 L2），
  交付 **4 个模块 + 1 条校正车道 + 1 条工具修复车道**。
  按当前档位（`LANES=4`、cron `0 * * * *`、`THROTTLE_MIN=50`），
  **名义吞吐 ≈ 4 个/小时**，按协议的经验折损系数 ≈ 0.70 估算 **实际 ≈ 2–3 个/小时**；
  剩余 **133** 个 ⇒ **约 45–65 小时**（2–3 天）。
  ⚠️ 这是在**本轮零派发等待**（闸被活 tick 持有 + L2 跑了两次）之后的**保守**估计，
  实际取决于 main 同步任务与闸的争用频率 —— **每次重跑都按当时的 `LANES` 与节流间隔重新算**，
  不要引用这个数字当承诺。
