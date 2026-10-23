# 16.6.2.1 数据出境与留存（阶段 D7，L3 锚点 16.6.2）

> 会话日期：2026-10-23 · 车道：16.6.2.1 · 分支：`feat/16-6-2-1` · 基线：`integration/module-dev`（f8733478）

## 决策摘要

实现蓝图 **16.6.2.1 数据出境与留存**：跨境数据传输登记与合法性基础、数据留存期配置、
到期清理与删除请求（被遗忘权）、**保留期与财务留存期的区分**。

**落点**：channel 服务，迁移 `V322__data_governance_retention.sql`（七张新表），
端点 `/channel/data-governance/**`（17 条路径 / 22 个操作），前端 `/compliance/data-retention`。

### 五条结构上的不变量

1. **「全部数据域」在本仓没有事实表 ⇒ 本模块自建。**
   `data_governance_data_domain` 是本模块的核心事实表（登记侧），`retention_*` 是留存侧。
   这不是「造一份副本」—— 本模块就是这张表的写入所有者。
2. **两类留存期在数据模型上就是两类东西**：`retention_class ∈ {STANDARD, FINANCIAL}`，
   不是一个期限加一个 flag。同一（数据域 × 类别）同时只能有一条 ACTIVE
   （DB 层 partial unique index 钉死）。
3. **「财务留存优先」是可判定的规则，不是一句说明**（见下）。
4. **判不了 ≠ 默认放行**：数据域一条留存策略都没登记 ⇒ 审批删除请求 **422 拒绝**。
5. **本模块不跨服务删业务数据**：执行删除请求产出的是**清理工单 + 执行记录**，
   没有「直接删除」端点。

## 关键 trade-off

### 1. 🔴 「财务留存优先」落成三件可判定的东西，而不是一句说明

蓝图那句话要能被执行，至少要有三样：**能算的期限、能判的裁决、能落的状态**。

- **能算**：`DataGovernanceRules.adjudicate`（纯函数）——
  生效留存期 = `max(普通期, 财务期)`（法定留存期是**下限**，更短的普通期压不下去）。
- **能判**：冲突时 `resolution = FINANCIAL_PREVAILS`；普通期更长时 `STANDARD_APPLIES`，
  但**财务期仍是下限** ⇒ `deletionBlocked` 照常为 true。
- **能落**：法定留存期没走完 ⇒ 删除请求判成 **`DEFERRED`（推迟）**，不是 `REJECTED`（驳回），
  并带 `defer_until`（财务策略生效日 + 财务留存天数）与 `defer_reason`。

**备选方案与放弃原因**：把「推迟」折进「驳回」⇒ 合规台账上「被拒」与「被推迟到某天」
完全混为一谈，这两件事在 GDPR 与法定留存期的口径下是不同性质的。
所以 `DEFERRED` 是**独立状态**，DB 层 CHECK 钉住（推迟 ⇒ 必须有 `defer_until` 与理由）。

**财务期走完的那一天怎么算**：财务策略生效日 + 财务留存天数。这是**保守**口径 ——
在那天之前，本模块**无法证明**某条记录已过法定期限（记录创建时刻在别的服务库里），
所以保守地不许删。真实记录级年龄由下游数据清理任务按业务数据判定。

### 2. 🔴 冲突清单**实时算**，台账**只记历史** —— 两者不是同一份事实的两个副本

`GET /retention-conflicts` 的数据源是**当前生效的策略**（唯一事实源），实时算；
`retention_conflict` 表是**裁决台账**，每次真做过一次裁决就写一行（append-only）。

**为什么不做成一张表**：策略一改，存下来的裁决就过期了；界面显示旧裁决比不显示更糟。
反过来只实时算不落台账，则「什么时候、按什么依据、裁过谁」无从追溯。

### 3. 🔴 12 财务没落地 ⇒ 「财务留存优先」按**规则**实现，不按查询实现

finance 服务在本仓尚未落地（调度者已确认，卷 12 全部未开发）。

选择：把「这条是法定财务留存期」表达成**本地的策略声明**
（`retention_class = FINANCIAL`），并把**规则**（取长 + 优先 + 推迟）完整实现；
**不建**财务凭证表、**不编造**财务明细、能力声明里 `available=false` + `ownerModule=「12 财务」`。

**备选方案与放弃原因**：等 finance 落地再做 ⇒ 本模块整个交付不了，
而「冲突时按财务留存优先」这条边界是**策略口径**、不是跨服务查询，完全可以先立住。

### 4. 🔴 与 16.6.1.1 的删除/导出审批**语义对齐**，不造第二套

「申请 → 审批 → 执行」三段式、**审批人 ≠ 申请人**、**理由必填** —— 全部沿用 16.6.1.1 的口径，
差别只有本模块自己的边界（审批时多一道财务留存裁决）。

**为什么不单造一套**：两套并行的审批语义，运营会问「这两张单有什么区别」，
而答案会随实现漂移。**理由必填**尤其重要 —— 没有理由的删除请求，审批人无从判断。

### 5. 🔴 执行删除 = 派发清理工单，不是物理删除

物理删除跨服务做不了（业务数据在 trade / crm / finance 各自的库里，
AGENTS.md 明令禁止跨服务 SQL 与跨库 FK）。
本模块的产出是**清理工单 + 状态**（下游数据清理任务消费）+ **append-only 执行记录**。

**代价与诚实**：在下游落地之前，「已执行」= **已派发清理工单**，**不等于数据已删**。
这一句写在能力声明与页面上，不含糊过去。

## 实现中踩到的三个真问题

1. **UUIDv7 的开头那 48 bit 是毫秒时间戳** ⇒ 同一毫秒内生成的两个 id 前缀完全相同。
   单号若取 UUID 头部，同一秒连着登记两条就撞唯一键（测试第一次跑就撞出来了）。
   ⇒ 单号改取 **UUID 尾部随机位**。
2. **jOOQ 纯文本语句里 `OffsetDateTime` 不加 `?::timestamptz` 会被按 varchar 处理** ⇒
   `deletion_blocked_until` 插入时报「timestamp with time zone 但表达式是 character varying」。
3. **同一个触发器函数不能挂在列名不同的表上** —— `data_governance_transfer` 那一列叫
   `source_domain_code`，挂过去会在插入那一刻抛
   `record "new" has no field "domain_code"`，把整条登记路径炸掉。
   ⇒ 传输单独用一个按自己列名校验的触发器函数。

## 文件清单

**后端（channel 服务）**

- `backend/services/channel/src/main/resources/db/migration/V322__data_governance_retention.sql`
  —— 七张表 + 引用完整性触发器 + append-only 触发器 + ENABLE/FORCE RLS + GRANT/REVOKE
- `backend/services/channel/src/main/java/com/starcon/channel/DataGovernanceModels.java` —— 传输模型
- `backend/services/channel/src/main/java/com/starcon/channel/DataGovernanceRules.java` —— **裁决规则（纯函数）**
- `backend/services/channel/src/main/java/com/starcon/channel/DataGovernanceService.java` —— 服务层（jOOQ 显式 SQL）
- `backend/services/channel/src/main/java/com/starcon/channel/DataGovernanceController.java` —— 17 条路径 / 22 个操作
- `backend/services/channel/src/test/java/com/starcon/channel/DataGovernanceIntegrationTest.java` —— 23 例
- `backend/services/channel/src/test/java/com/starcon/channel/DataGovernanceTenancyIntegrationTest.java` —— 4 例（租户隔离三断言）
- `backend/services/channel/src/test/java/com/starcon/channel/DataGovernanceRulesTest.java` —— 11 例（裁决纯函数）

**前端**

- `apps/web/src/app/compliance/data-retention/page.tsx`
- `apps/web/src/components/data-retention/data-retention-model.ts` —— 类型 + 取数 + 展示映射
- `apps/web/src/components/data-retention/data-retention-workspace.tsx` —— 工作区
- `apps/web/src/components/data-retention/data-retention-model.test.ts` —— 17 例（含 **api-proxy 白名单回归**）
- `apps/web/src/lib/api-proxy.ts` —— **纯新增一条独立的 `services[]` 元素**（既有条目一字未动）

**契约与台账**

- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts`
  —— codegen 重生成（**其余 5 个服务的契约逐字节不变**）
- `docs/product/capability-blueprint/module-progress.md` —— 16.6.2.1 那一行

## 验证

- `./mvnw -q compile` 退出 0
- `./mvnw -pl services/channel -am test -Dtest='DataGovernance*'` —— **38 例 0 失败 0 错误**
  （Testcontainers 真实 PostgreSQL 16，连接池压到 1 实跑租户隔离三断言）
- `pnpm typecheck` —— 3/3 通过
- `pnpm vitest run apps/web/src/app/api` —— **197 文件 1147 例全绿**（含 api-proxy 全量 allowlist 回归）
- `pnpm lint` —— 3/3 通过
- `node tools/module-progress/sync.mjs --check` —— 台账与蓝图一致

## 后续待办

1. **下游数据清理任务**（本仓未交付）：消费 `retention_cleanup_work_order` 的 PENDING 工单，
   跨服务执行物理删除后回报结果（`POST /cleanup-orders/{id}/execute`）。
2. **12 财务（finance 服务）落地后**：把财务留存期的**明细**（逐类法定期限、凭证）接进来 ——
   现在的 `retention_class = FINANCIAL` 只是本地策略声明。
3. **数据域清单仍是租户自维护的登记**：等有服务能提供「全量数据域」枚举接口后，
   可考虑自动发现（届时 `data_governance_data_domain` 仍是权威表，只是不用再手登记）。
4. **下游工单的到期调度**（谁在什么时候把到期项变成工单）尚无归属 ——
   当前工单由人在页面上登记。
