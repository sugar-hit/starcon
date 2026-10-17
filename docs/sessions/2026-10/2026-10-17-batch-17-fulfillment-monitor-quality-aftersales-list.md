# 批次 17 —— 履约流程监控 / 履约质量 / 售后单列表

> **批次时间**：2026-10-17 18:00
> **集成头**：`85d2eb48`（`integration/module-dev`）
> **来源**：11:00 批次因 Token Plan 配额（`errorCode 42212`）中断后滞留在 worktree 的三条抢救车道，
> 本批次完成并入 + L2 全量验收 + push。
> **L2 结果**：绿。后端 `mvnw verify` BUILD SUCCESS（8 模块全 SUCCESS）；`TURBO_FORCE=true pnpm typecheck`
> 3/3 且 **0 cached**；lint 3/3；vitest **206 文件 / 3680 例全过**；Next production build exit 0；
> `sync --check` ✓ 台账与蓝图一致。

---

## 模块 9.3.3.2 履约流程监控

**这个功能做什么**：运营能看到「哪个履约单卡在哪一步、超时多久、依据什么规则判的」，
并且在流程卡住时**先查证再决定重试还是释放预占**——不会因为盲目重试造成重复发货。

**技术落地**：

- **迁移**：`V154__trade_fulfillment_monitor.sql`（937 行）—— **零 ALTER 既有表、零跨车道 DDL 面**。
  流程状态直接复用 9.3.3.1 已交付的 `V152`（`fulfillment_request` 头行的
  `status + current_step + process_version` 三件套），本模块只读它、对
  `(tenant_id, request_id)` 建复合外键把案例挂在真实流程上，并把判定证据
  （`rule_evidence` / `threshold_seconds` / `overdue_seconds`）**快照**进案例行。
  RLS ENABLE **与 FORCE** + GRANT。
- **端点**：`FulfillmentMonitorController` **15 个**映射（异常检出、查证、重试/释放预占、能力与缺口、筛选）。
- **页面**：`/fulfillment-monitor` 工作区（异常清单 + 查证结论 + 重试/释放入口 + 多维筛选；加载·空·错误三态齐全）。
- **测试**：`FulfillmentMonitorIntegrationTest` **26 例**。

**关键设计决策**：
1. **加粗句落成 DB 层三道闸门，而不是提示语**。蓝图要求「流程卡住时先查证再决定重试或释放预占」，
   于是：① `guard_fulfillment_monitor_retry_verified`（BEFORE INSERT 触发器）挡住「RETRY 引用非 CONFIRMED 的查证」
   **以及「引用别的案例的查证」**（拿 A 的查证结论重试 B 等于没查证）；② 部分唯一索引
   `fulfillment_monitor_retry_once_uq(tenant_id, request_id, attempt) WHERE action_kind='RETRY' AND status<>'CANCELLED'`
   挡住两个并发重试都成立。提示语会被绕过，约束不会。
2. **快照而不是 JOIN**。JOIN 出的是**当前**状态而非决策时状态；上游事后改一列会静默改掉本模块的判定。
   一单多仓多批次下每个履约单元都要留一份「当时按哪个仓判的」凭据。

---

## 模块 9.5.2.1 履约质量

**这个功能做什么**：运营在一张看板上看到发货及时率、完整发货率、错发漏发率、取消率、纠纷率、退货率
六个履约质量指标，口径统一、可随时对着事实重算。

**技术落地**：

- **迁移**：`V155__trade_fulfillment_quality.sql`（476 行）—— 只建口径定义，**零 ALTER 既有表**。
- **端点**：`FulfillmentQualityController` **14 个**映射（六指标查询、维度下钻、筛选、能力与缺口）。
- **页面**：履约质量看板（时间窗 × 店铺/平台维度筛选 + 六指标卡 + 下钻明细；加载·空·错误三态齐全）。
- **测试**：`FulfillmentQualityIntegrationTest` **38 例**。

**关键设计决策**：
1. **是读模型，不是状态机**。不推进任何状态机、不回传平台、不改一张既有表；
   六个指标全部由 jOOQ 显式 SQL **现算不落表**——落了表就会与事实漂移，而指标看板的价值恰在于随时能重算。
2. **分母为 0 返回 `null`，不是 0 也不是 1**。报 0 会被读成「及时率 0%」，那是结论不是缺口。
   比率一律 `numeric(19,6)`（0~100 百分数量纲）。
3. **发货及时率复用 9.5.1.1 的 `order_timeliness_fact`，不另起一套时限口径**。同一件事两个算法必然对不上；
   `UNSET`（未登记时限）独立报数、**不进分母**。
4. 六个指标的口径**只在两处定义**：V155 第二节 + `QualityMetric` 枚举，避免 Java 与 SQL 两侧漂移。

---

## 模块 11.1.1.1 售后单列表

**这个功能做什么**：客服/运营看到租户的售后单清单，**平台时效倒计时在列表上直接显眼**（超时整行染色，
超时数是顶部第一张卡），支持多维筛选与三项批量操作。

**技术落地**：

- **迁移**：`V156__trade_aftersales_case_list.sql`（477 行）—— **零 ALTER 既有表、零外键**。
  五张表全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS + GRANT：
  `aftersales_case`（五种类型 RETURN_REFUND/REFUND_ONLY/EXCHANGE/REISSUE/REPAIR 七态；
  金额 `numeric(19,4)`、数量 `numeric(19,6)`；`reply_due_at`/`resolve_due_at` 两个真列 +
  **`effective_due_at` 由 BEFORE 触发器维护**并建索引；CHECK 钉死「终态 ⇔ resolved_at」与「两个时限至少给一个」）、
  `aftersales_owner_assignment`、`aftersales_case_note`、`aftersales_authorization`、
  `aftersales_service_ticket`（UNIQUE(tenant_id,case_id)，一单只转一次）。
  四张留痕表 REVOKE UPDATE/DELETE（append-only）；`aftersales_case` 的 UPDATE 做成**列级白名单**。
- **端点**：`/trade/aftersales` **6 个** —— capabilities、filters、cases(GET 列表)、cases(POST 登记)、cases/bulk(POST 批量)。
  **刻意不提供 `cases/{uuid}`**（详情属未交付的 11.1.1.2，列表行不带任何指向它的链接）。
- **页面**：`/aftersales` —— **替换掉既有的 `DomainPlaceholder` 占位页**为真实工作区
  （能力与缺口 / 多维筛选 / 时效四档视觉徽标 + 倒计时人话 / 批量三件 + 逐条结果面板 / 列表 / 分页，
  加载·空·错误三态齐全）。未知时效状态回落「无平台时限」而**不回落「正常」**——后者会掩盖一次超时。
- **测试**：`AftersalesCaseIntegrationTest` **29 例** + `AftersalesCaseTenancyIntegrationTest` **5 例**
  （Testcontainers 真实 PG16、连接池压到 1），前端 25 例（model 22 + allowlist 双向断言 3）。

**关键设计决策**：
1. **「平台时效倒计时必须显眼」按数据要求落地，不只按 UI 要求**：判定真身**只有 DB 里一个 IMMUTABLE 函数**
   `aftersales_timeliness_state(due_at, done_at, at)`，Java 与前端都刻意不重算——两处实现必然漂移，
   而超时直接关系账号绩效与资金冻结。五态：`OVERDUE / DUE_SOON(≤24h) / ON_TRACK / RESOLVED / NO_DEADLINE`。
2. **参考时刻整页共用一个**：`at` 在 `list()` 开头取一次，WHERE/行/汇总三处复用，
   否则会出现「筛了临期、第一条不临期」的自相矛盾。
3. **店铺维度只做到平台级，且刻意不建恒为空的占位列**。实查 `sales_order`（V99）无 `shop_code`，
   卷 2 店铺在 channel 独立库（禁止跨服务 SQL）⇒ `platformShopCode` 恒为 `null` 并报 `available=false`。
   空列会被读成「这家店没有售后单」。
4. **责任人是租户可维护的登记表**（照抄 V150 既有解法）：强制 `owner_source` 枚举
   （`TENANT_MAINTAINED` / `MODULE_15_SERVICE_STAFF`）与 `captured_at` 采集时刻，**不伪造 L4 模块号**。
   平台消息无落表方案（是对话流不是可维护信号），如实报 `crossServiceReadAvailable=false`。
5. **批量三件全部可用**（授权 / 备注 / 转工单），逐条结果**按请求下标**返回而非按 caseId 归并
   （重复 ID 时归并会让第 1 条的成功顶到第 2 个位置）；逐条失败来自**写库前的真实预校验**，
   不是 catch 到什么都标 failed。
6. 过程中被测试逼出来的三个真实缺陷（已修）：**列级 REVOKE 在表级 GRANT 之后是空操作**（必须先收回表级 UPDATE 再逐列授回）；
   序列必须单独 `GRANT USAGE`，漏了会 BadSqlGrammarException→409 而文案说「数据冲突」；
   `Timestamp.valueOf` 的静默 −28800s 偏移必须靠 +08:00 夹具断言写回时刻才测得出来。

---

## 本批次的编排事实（供下轮参考）

- **静态预检**：V154/V155/V156 三份迁移对 ①`ADD COLUMN` 无 DEFAULT、②`SET NOT NULL`、③`ADD CONSTRAINT…CHECK`
  **全部零命中**，且都带 BEFORE INSERT 触发器兜底 ⇒ 跨车道 DDL 回归面为 0。
- **台账完整性**：465 行、零重复 ID（`merge-union` 对台账走了「按模块 ID 求并集」的行级兜底，已核对）。
- **`api-proxy.ts` 并入**：11.1.1.1 采用「新增一条独立 `services[]` 元素、插在既有 `prefix: 'trade/'` 之前」的做法，
  未碰既有 trade 分支的超长单行 alternation ⇒ 本批三条车道并入零冲突。
- **本批实际派发 4 条车道**（11.1.1.2 / 11.1.2.1 / 11.3.1.2 / 17.4.2.1），非计划中的 6 条 ——
  原因见 `2026-10-17` 批次报告：运行时子 agent 并发上限为 4。
