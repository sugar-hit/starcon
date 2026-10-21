# 10.3.1.2 异常件（channel 服务）

> 会话：2026-10-20 · 车道 `feat/10-3-1-2`（worktree `.worktrees/10.3.1.2`）
> ⚠️ 本车道前两个 worker 都在**收尾阶段**被上游网络错误打断（`net::ERR_HTTP2_PING_FAILED`），
> 本会话是**第三轮续写**：实现、迁移、测试、契约、台账在前两轮已落地并验证过，
> 本会话只做**复验 + 会话日志 + commit + land**，未重写任何实现。

## 决策摘要

按蓝图卷 10 的 `### 10.3.1.2 异常件` 交付「识别与处理异常件」，归属 **channel** 服务，
迁移 `V216__logistics_exception_case.sql`（**纯新建、零 ALTER 任何既有表**、零触发器挂载到别模块的表上，
跨车道 DDL 回归面 = 0）。

蓝图四问与真值位置：

| 蓝图问题 | 蓝图写的 | 真值实际在哪 | 本模块怎么接 |
| --- | --- | --- | --- |
| 职责 | 识别与处理异常件：轨迹停滞、妥投失败、地址问题、清关滞留、破损丢件、买家投诉；支持联系承运商、发起理赔、改址、补发 | — | 六类 `exception_type` 全在枚举里显式列出，**不猜承运商的自由文本异常码** |
| 输入 | 轨迹异常规则 | **全库没有这类表** | 本模块**自持** `logistics_exception_rule`（租户可维护的表） |
| 输入 | 被动异常标记 | 10.3.1.1（V212）`logistics_tracking_event.exception_code` / `logistics_tracking_subject.exception_flag` | **同库同服务**，复合外键 `(tenant_id, subject_id)` / `(tenant_id, source_event_id)` 挂上去 |
| 输入 | 客服反馈 | 15 客服**未交付** | 本模块自持 `customer_feedback` 列，作为**兜底登记口**（capabilities 里写明「不是 15 客服的替代品」） |
| 输出 | 异常件清单 + 处置记录 + 理赔/补发单 | — | `logistics_exception_case` / `_disposition` / `_claim` 三张表 |
| 关系 | 下游 = 11 售后、15 客服、7 库存 | 三者**当前都不存在** | 只产出本服务内单据 + `downstream_task`（状态恒 `PENDING_NOTIFY`） |
| 关系 | 上游 = 10.3.1.1 | 同库同服务（V212） | 零跨服务 SQL、零跨库 FK、零共享视图（AGENTS.md 模块边界纪律） |

### 加粗句「异常件必须有人负责到底」是**结构**不是文案

这句话是本模块唯一不可让步的约束，落地为三件同时成立的事，缺一件就退化成「异常池」假活：

1. **`owner_membership_id` NOT NULL** —— 建件即有责任人（默认建件人）。
   **无人认领的异常件在库里不存在**，不用「待分配」这种状态糊过去。
2. **`due_at` NOT NULL** —— 每件都有责任时限（来自规则的 `resolve_sla_minutes`）。
   逾期是**可统计的事实**（`overdue` 只在未结案时成立），不是界面上的一句话。
3. **DB CHECK 钉死结案凭据**：
   `CHECK (status <> 'RESOLVED' OR (closed_at IS NOT NULL AND owner_membership_id IS NOT NULL))`
   —— 结案时刻必须落，责任人必须还在。另有 `CHECK (status <> 'VOID' OR closed_at IS NOT NULL)`、
   `CHECK (closed_at IS NULL OR closed_at >= detected_at)`、`CHECK (due_at >= detected_at)`。

配套的状态机触发器 `logistics_exception_case_transition_trg` 让终态**不可回退**。

### 来源是服务端派生的事实，调用方填的一律不认

请求模型 `CreateExceptionCaseRequest` 里**没有 source 字段**。来源由服务端按事实派生：

| 来源 | 派生依据 |
| --- | --- |
| `RULE_SCAN` | 规则扫描命中那条路（`scan` 端点） |
| `TRACKING_FLAG` | 有异常节点（`source_event_id` 指向 10.3.1.1 的真实事件） |
| `CUSTOMER_FEEDBACK` | 带了客服反馈原文 |
| `MANUAL` | 其余（人工登记），且异常类型必须显式给出 |

理由：来源是「这件事是怎么被发现的」这一**事实**，不是调用方的自述；
让调用方填 source 等于让「来源」退化成一句可以随手写错的备注。

### 时长一律整数分钟

DB 侧 `stalled_duration_minutes bigint NOT NULL DEFAULT 0 CHECK (stalled_duration_minutes >= 0)`，
**没有 hours 浮点列**。锚点在将来时**截断到 0**（负值不写进库），界面上看到的
`stalledDurationHours` 是服务端由整数分钟换算出的 `BigDecimal`（`minutes / 60`，2 位 HALF_UP），
纯展示用，不是另一份事实。

### 金额与数量：`NUMERIC(19,6)` + BigDecimal，跨币种绝不相加

- `logistics_exception_disposition.amount` / `logistics_exception_claim.amount` / `.qty`
  一律 `numeric(19,6) NOT NULL DEFAULT 0 CHECK (... >= 0)`，Java 侧一律 `BigDecimal`。
- 币种 `varchar(3)` + `CHECK (currency = '' OR currency ~ '^[A-Z]{3}$')`；空串 = 尚未确定币种，
  **不是「0 元」**。处置金额大于 0 时必须给币种。
- **跨币种分行不相加**（汇总按币种分组给），把两种币种加成一个数是没有意义的数字。
- 🔴 **异常件主表刻意不存金额也不存数量** —— 异常件本身没有「值」这个事实，
  金额只属于处置与单据；主表一旦挂上金额字段，早晚会出现「异常件金额」与
  「单据金额合计」两套对不上的数。

### 跨租户挂载靠**复合外键**，不是「服务层记得过滤」

`logistics_exception_case` 的 `subject_id` / `source_event_id` 都指向 10.3.1.1 的表，
外键写成 **`(tenant_id, subject_id)` → `logistics_tracking_subject(tenant_id, id)`**
（V212 里该表带 `UNIQUE (tenant_id, id)`，所以复合外键成立）。

⇒ 结构上**不可能**把 A 租户的异常件挂到 B 租户的运单上：
这不是「服务层记得过滤」，是数据库层的约束。

### 下游全部未交付 ⇒ 只落本服务内的事实 + 待通知任务

蓝图下游 11 售后 / 15 客服 / 7 库存**均未交付**，承运商出网通道（10.1.1.1）也未交付：

- 处置结果落本服务自己的 `logistics_exception_disposition`（**append-only**）与
  `logistics_exception_claim`（理赔 / 补发单）。
- 需要下游接手的事逐条落 `logistics_exception_downstream_task`，**状态恒 `PENDING_NOTIFY`**，
  不写「已通知」「已受理」这种不存在的凭据。
- **改址（10.2.1.3）虽已交付，但在另一个服务里**：改址单的写入所有者永远是 10.2.1.3，
  本模块只提一条 `downstream_module='10.2.1.3'` 的待通知任务，
  **不跨服务去改别人的运单文档**。
- capabilities 逐段声明：**7 段 `available=true`**（异常件清单 / 异常规则 / 被动标记受理 /
  客服反馈受理 / 处置记录 / 理赔补发单 / 责任到底），
  **6 段 `available=false` 且点名 ownerModule**
  （联系承运商→10.1.1.1、11 售后→11、7 库存→7、15 客服→15、改址→10.2.1.3、财务→12）。
  **不用空列表或 0 冒充「这一项没有数据」。**

## 关键 trade-off

- **规则是租户可维护的表，不是代码常量。**
  各承运商的停滞口径本来就不一样，写死等于宣称「所有承运商一个阈值」。
  代价是规则本身要做租户内唯一（`UNIQUE (tenant_id, rule_code)`）与命中判定，
  收益是口径差异落在数据里而不是散在代码里。
- **异常类型必须显式给出，不做模糊匹配。**
  规则按承运商异常码**精确匹配**建件，不猜「这个自由文本大概算什么」。
- **同一票同一类型同时最多一件未结异常件。**
  部分唯一索引 `logistics_exception_case_open_guard`
  `ON (tenant_id, subject_id, exception_type) WHERE status IN ('OPEN','INVESTIGATING','ACTIONED')`
  ⇒ 规则扫描天然幂等（第二遍扫不出第二张），已结案 / 已作废不占位。
- **处置记录 append-only，双保险。**
  `REVOKE UPDATE,DELETE ON logistics_exception_disposition FROM channel_app` +
  `logistics_exception_disposition_append_only_trg`（`BEFORE UPDATE OR DELETE`）——
  事后改写处置史等于伪造凭据，要更正就追加新的一条。测试用**应用账号与超级账号各试一次**。
- **理赔单与补发单是一张表两个 `claim_type`，不是两张表。**
  二者的状态机、凭据字段完全同构，拆两张表只会得到两套重复的 CHECK。
  DB CHECK 钉死各自的关键字段：`CARRIER_CLAIM` 必须 `amount > 0` 且币种非空；
  `RESHIP` 必须 `qty > 0`。
- **处置记录是「联系承运商」的凭据，不是消息送达的凭据。**
  本模块只落「谁、什么时候、说了什么」，**不假装消息已送达承运商** ——
  承运商出网通道未交付。
- **处置记录只能追加、不能追认到人之后又反悔地改。**
  结案后追加处置被拒（409），终态 DB 层不可回退：处置史是「当时做了什么」，
  不是「现在应该是什么」。

## 文件清单

**后端（channel 服务）**

- `backend/services/channel/src/main/resources/db/migration/V216__logistics_exception_case.sql`
  （五张表 + 3 个触发器 + ENABLE/FORCE RLS isolation + GRANT/REVOKE + 序列授权）
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsExceptionModels.java`（582 行）
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsExceptionService.java`（1675 行）
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsExceptionController.java`（298 行）
- `backend/services/channel/src/test/java/com/starcon/channel/LogisticsExceptionIntegrationTest.java`（19 例）
- `backend/services/channel/src/test/java/com/starcon/channel/LogisticsExceptionTenancyIntegrationTest.java`（4 例）

**前端**

- `apps/web/src/app/logistics/exceptions/page.tsx`
- `apps/web/src/app/logistics/exceptions/exceptions-model.ts`（935 行）
- `apps/web/src/app/logistics/exceptions/exceptions-model.test.ts`（30 例）
- `apps/web/src/app/logistics/exceptions/exceptions-workspace.tsx`（1346 行）
- `apps/web/src/app/api/channel/logistics-exception-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`（**新增独立一条 `services[]` 元素**，
  锚点固定在 10.3.1.1 `logistics-tracking` 那条**之后**、10.1.1.3 `logistics-transit-limits` 那条**之前**；
  既有条目一字未动）
- `apps/web/src/app/api/channel/tracking-allowlist.test.ts`（10.3.1.1 兄弟车道的文件，
  改的是共享前缀 `channel/logistics-tracking` 的**累积并集**断言，不是回退兄弟车道）

**契约与台账**

- `packages/api-contracts/src/channel.openapi.json`（**+12 路径 / +24 schema / 0 删除**）
- `packages/api-contracts/src/contracts/channel.ts`
- `docs/product/capability-blueprint/module-progress.md`（只改 10.3.1.2 一行的状态与证据两格）
- `tools/codegen/contract-collision-allowlist.json` **保持 `{"allowed": []}`** —— 撞了跨服务同名 DTO 要改名，
  不接受覆盖。

## api-proxy 条目的形状与不撞车论证

本车道**新增一条独立的 `services[]` 元素**（`prefix: 'channel/'`），
**不往既有 channel 长 alternation 里并分支** —— 同批多车道共享 `api-proxy.ts` 时，
每个服务在 `services[]` 里是一段**超长单行正则**，两条车道都往同一 alternation 并分支，
git 必然判冲突（结构性的，retry 永远不会自愈）。锚点与其他三条车道互不重叠，git 能干净求并。

放行的形状只有本模块真实实现的 12 条。**刻意不放行**（放行后端不存在的映射比 404 更糟 ——
界面会以为功能已交付）：

- `exceptions/{uuid}/rules` —— 规则是**租户级**集合，不挂在某一件上
- `exceptions/{uuid}/scan` —— 扫描是集合级动作
- `exceptions/{uuid}/events` —— 轨迹事件归 10.3.1.1
- `dispositions/{uuid}/claims` —— 单据挂在**异常件**上，不挂在处置记录上
- `exceptions/{uuid}/downstream-tasks` —— 下游任务在异常件详情里，没有第二个形状
- 裸路径 `logistics-tracking` —— 后端没有裸集合读

两处易错点已在 allowlist 测试里钉住：id 段用**完整 UUID 形状**
（`[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}`），不是 `[0-9a-f-]+`（后者把单字符 `c` 当合法 id）；
字面量子路径（`capabilities` / `claims`，首字母 `c` 落在 `[0-9a-f]` 里）**排在 uuid 段之前**判别。

## 验证（本会话实跑）

- **L0 编译闸**：`cd backend && ./mvnw -q compile` → **exit 0**（只有 Maven/Jansi 的既有告警）。
- **L1 模块闸**：`./mvnw -pl services/channel -am test -Dtest='LogisticsException*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **23 tests / 0 failures / 0 errors / 0 skipped**
  （`LogisticsExceptionIntegrationTest` 19 + `LogisticsExceptionTenancyIntegrationTest` 4），
  `BUILD SUCCESS`，Flyway 跑到 **v216**。
- **类型**：`pnpm typecheck` → **3 successful / 3 total**。
- **api-proxy 回归**（改了共享的 `api-proxy.ts`）：
  `pnpm vitest run apps/web/src/lib/api-proxy.test.ts apps/web/src/lib/api-proxy-union.test.ts`
  → **2 files / 262 tests 全绿**。
- **channel allowlist 全量回归**：`pnpm vitest run apps/web/src/app/api/channel`
  → **37 files / 172 tests 全绿**。
- **前端模型**：`pnpm vitest run apps/web/src/app/logistics/exceptions` → **1 file / 30 tests 全绿**。

### 23 例覆盖的要点

- **租户三断言**（`LogisticsExceptionTenancyIntegrationTest`）：
  1. **两租户互不可见** —— 服务层读 + **以应用账号直查**绕过「超级用户绕过 RLS」这个盲区；
     并逐表断言 `tenant_id` NOT NULL 与 `FORCE RLS`。
  2. **伪造 `X-Tenant-Id` 无效**，无会话 401；跨租户与不存在返回**逐字相同的 404**。
  3. **连接池压到 1 后 A→B→A 三轮不串租户**（连接池归还后不串）。
- 业务侧 19 例覆盖：来源派生（被动标记 / 客服反馈 / 人工登记）、规则命中建件与幂等重扫、
  按承运商异常码精确匹配、规则编码租户内唯一、结案必须写理由、结案后不能再追加处置（409）、
  终态 DB 层不可回退、转派责任人、停滞时长整数分钟且锚点在将来截断 0、
  下游待通知任务产出、补发单产出 7 库存 + 11 售后两条任务、
  理赔单缺金额被拒 / BigDecimal 精确往返 / 跨币种分行不相加、处置金额大于 0 必须给币种、
  真分页、同票同类型唯一未结、处置 append-only（应用账号与超级账号各拒一次）、
  capabilities 未交付维度 `available=false` 且点名 ownerModule、端点 HTTP 打通。

### 契约

`packages/api-contracts/src/channel.openapi.json` 由 codegen 重新生成：**+12 路径 / +24 schema / 0 删除**。
已用 `--out-dir` 临时目录与当前 spec 快照做过**逐字节比对**（结果相同），确认生成结果与 spec 一致 ——
本会话**未重跑 codegen**（重跑只会带来「路径重排」噪音 diff，且 codegen 遇到跨服务同名 DTO 会直接中止）。

## 后续待办（不属于本车道）

- **10.1.1.1 承运商通道交付后**：`carrier_contact_dispatch` 维度从 `available=false` 翻成 `true`，
  本模块的「联系承运商」处置记录届时才有真正的送达回执可挂。
- **11 售后 / 15 客服 / 7 库存交付后**：消费 `logistics_exception_downstream_task`
  （本模块已备好 `downstream_module` / `note` / 关联的处置与单据 id），
  状态从 `PENDING_NOTIFY` 推进到 `NOTIFIED` / `ACKNOWLEDGED`。
- **12 财务服务落地后**：理赔金额与赔付入账做最终对账；本模块已把金额作为
  **带币种的独立事实**落地，形状不用改（跨币种绝不相加的纪律要一起带过去）。
- **10.2.1.3 改址单消费侧**：本模块的改址待通知任务需要 10.2.1.3 侧开一个消费入口
  （改址单的写入所有权不变，仍在 trade）。
- **10.3.2.1 交运批次 / 10.3.2.2 揽收与签收确认** 交付后，
  「妥投失败 / 揽收超时」类异常的**自动建件来源**可以补上（当前只有被动标记、规则扫描、
  客服反馈、人工登记四条来源）。
