# 2026-10-24 12.7.1.1 账户管理（卷 12 L3 12.7 资金的第一格）

> 车道：`feat/12-7-1-1`（worktree `integration/.worktrees/12.7.1.1`），基线 `98e7a607`。
> 迁移版本号由调度者预分配为 **V367**。
> L0 编译闸 + L1 模块闸全绿（见文末「验证」）。

## 这个模块做什么

蓝图 12-finance.md 12.7.1.1 原文：「管理资金账户：银行账户、支付平台账户、平台备付金账户，
记录币种、账号、余额、开户主体（法人）、账户类型。」输入：「账户定义；余额同步或手工更新；
关联法人。」输出：「账户档案 + 余额历史。」关系：上游 = 17 法人；下游 = 12.2 收付款、12.7.1.2 对账。
加粗句：「**账户必须绑定法人**，跨法人混用资金账户会导致收入归属错误。」

本车道交付**账户档案 + 余额历史**两张真表（jOOQ 显式 SQL + 真实 PostgreSQL 16，无常量、无 mock、无占位页）。

## 关键决策

### 1. 法人：**软引用 + 落库前实时校验**（不是 FK，也不是「先信着」）

identity 在另一个库 ⇒ 跨库 FK / 跨库 SQL / 共享视图一律禁（AGENTS.md 模块边界纪律）。
本表只存 `legal_entity_id` + 编码/名称快照，**落库前**经
`FundAccountLegalEntityPort` 走 identity 的**授权只读** API `GET /identity/legal-entities` 校验。

关键取舍：**「法人清单读不到」与「这个租户没有法人」是两件事**。
本端口把「读不到」表达成一个状态（照 `CostValuationScopePort` / channel `LegalEntitySourcePort` 的手法），
服务层据此**拒绝**建户 / 换绑并回 503 —— 绝不把「读不到」当成「校验通过」，
那等于把「账户必须绑定法人」这条纪律架空虚化。已登记但没这个法人 ⇒ 400。

### 2. 余额历史 **append-only**，当前余额**单一写入口**

- 每次同步 / 手工更新**追加**一行 `finance_fund_account_balance`；历史行**不许 UPDATE / DELETE**：
  - `guard_finance_fund_account_balance_append_only` 触发器（BEFORE UPDATE OR DELETE 一律拒绝）
    —— **表 owner 也过不去**（测试里用 owner 连接实测过，不是只看权限）；
  - 运行角色 **REVOKE UPDATE,DELETE**（第二层）。
- 账户上的 `current_balance` 由 `sync_finance_fund_account_balance` **AFTER INSERT 触发器**从
  **最新一条历史**推导 ⇒ 不存在「两处余额对不上」。
- 补录一条**更早**的历史余额**不**把当前余额倒回去（当前余额 = 最新时刻的那个值）。
- `guard_finance_fund_account_balance_insert` 再钉三道闸：账户存在、**币种与账户一致**、
  改前余额必须与上一条**首尾相接**。

### 3. 手工更新必须留痕；同步必须幂等

- 手工（MANUAL）：谁（已认证会话的 `user_id`，不是客户端自称）/ 何时（`recorded_at` 事实时刻，
  `timestamptz` + `OffsetDateTime` 绑定 + `?::timestamptz`）/ 为什么（`reason`，DB 层 CHECK 拒绝缺理由的 MANUAL）/
  改前改后（`previous_amount` → `balance_amount`）。
- 同步（SYNC）：`source_system` + `source_reference` 组成**幂等键**（部分唯一索引）⇒
  同一笔来源流水重复同步 ⇒ 409，**不重复入账**。

### 4. 单账户单币种；跨币种绝不相加

- `currency` 是账户的固有属性，**不可改**（触发器钉死，改币种等于换一个账户）；账户编号同样不可改。
- 汇总**按币种分组**（币种内再按**法人**分组 —— 跨法人混用资金账户会导致收入归属错误，
  所以法人维度必须看得见）；`crossCurrencyTotal` **恒为 null** 并写明「本模块不做本币折算，
  折算归 12.7.2.1」—— **不**按 1:1 假装折算。

### 5. 账号脱敏：原始账号**只在请求作用域内存活**

落库只留 `account_number_masked`（如 `6222****5678`）+ `account_number_tail`（后四位）；
DB 层 CHECK 钉死「有掩码 ⇒ 后四位恰好 4 位」。服务层要求原始账号**至少 8 位**
（少于 8 位时后四位几乎等于完整账号，掩码没有意义 ⇒ 400）。
接口返回同样脱敏；**本模块没有任何账号日志**。

掩码规则**只有服务端一处实现** —— 前端不自己按「前四位 + **** + 后四位」再算一遍
（两处实现迟早分叉，显示出来的掩码会跟库里存的不一样）。

### 6. 账户类型是**受控词表**；**没有物理删除端点**

- 类型 CHECK 钉死三值：`BANK` / `PAYMENT_PLATFORM` / `PLATFORM_RESERVE`（不是自由文本）；
  银行账户必须有开户行、其它类型不许填（DB CHECK + 服务层双判）。
- **不提供删除端点**：资金账户是资金凭据，删掉等于让「钱曾在这个账户里」从未发生过 ⇒
  停用走 `POST /{id}/deactivate`（停用后不再接受余额登记、不可改档案；可再 `activate` 回来）。

### 7. 权限码沿用同 L3 兄弟 12.7.2.1 的 `finance.receivable.read` / `.update`

**刻意不新造权限词表**：权限码在 identity 的 `permission(code)` 目录里，
新造一档要改**另一个服务**的迁移（那是它的写入所有权，且迁移版本号由调度者统一分配）。
同 L3 的 12.7.2.1 资金分析已经这么定（账龄 / 资金分析都是 `finance.receivable.*`），
本模块照抄同一套，不另起风格。

### 8. 下游边界

- **12.7.1.2 银行对账未交付** ⇒ 本模块**不做对账**、不产出差异与未达账项；
  只保证「账户档案 + 余额历史」可供将来的对账使用。
- **12.2 收付款已交付但只读账户信息** ⇒ `finance_receipt.fund_account_ref` 仍由 V341 的
  CHECK 钉死恒为 NULL；反向引用由 **12.2 自己**改那条 CHECK（那是它的写入所有权），
  本车道**不 ALTER 别人的表**。
- **12.7.2.1 资金分析**的账户级资金分布当前只到币种级（它的 DB 层 CHECK 钉死恒为 NULL），
  由它自己接线；本模块**零 ALTER** 它的两张表。

## 文件清单

**迁移**

- `backend/services/finance/src/main/resources/db/migration/V367__finance_fund_account.sql`
  —— 两张新表 + 4 个触发器 + RLS + GRANT + REVOKE。

**后端**

- `backend/services/finance/src/main/java/com/starcon/finance/FundAccountModels.java`（传输模型 + 三个枚举）
- `backend/services/finance/src/main/java/com/starcon/finance/FundAccountLegalEntityPort.java`（identity 只读端口）
- `backend/services/finance/src/main/java/com/starcon/finance/FundAccountService.java`（服务层，jOOQ 显式 SQL）
- `backend/services/finance/src/main/java/com/starcon/finance/FundAccountController.java`（11 个端点）

**测试**

- `backend/services/finance/src/test/java/com/starcon/finance/FundAccountIntegrationTest.java`（20 例）
- `backend/services/finance/src/test/java/com/starcon/finance/FundAccountTenancyIntegrationTest.java`（9 例）

**前端**

- `apps/web/src/components/finance/fund-account-model.ts` + `fund-account-model.test.ts`
- `apps/web/src/components/finance/fund-account-workspace.tsx`
- `apps/web/src/app/finance/fund-accounts/page.tsx`
- `apps/web/src/app/api/finance/fund-accounts-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，既有条目一字未改）

**契约 / 台账 / 日志**

- `packages/api-contracts/src/contracts/finance.ts`（codegen 产物，+17 个类型）
- `docs/product/capability-blueprint/module-progress.md`（**只改本模块那一行**）
- `docs/sessions/2026-10/2026-10-24-fund-account-management.md`（本文件）

## 踩过的坑（留给下一个人）

1. **Flyway 报 `too many parameters specified for RAISE`** —— plpgsql 的 `RAISE EXCEPTION '…%', x`
   里**占位符个数必须与参数个数一致**；有一句漏了 `%`，整个迁移直接回滚（Spring 上下文随之起不来）。
2. **MockMvc 的错误响应体是空的** —— 断言「错误原因说得清」不能靠响应体，
   要**直接断言服务层抛出的 `ResponseStatusException.getReason()`**。
3. **中文关键字搜索**：`MockMvc` 的 `get(...).param(k, v)` 会自己编码，
   再手工 `URLEncoder.encode` 就是**双重编码**，症状是「搜索永远空」——
   看起来像后端没实现搜索。
4. **append-only 要测 owner，不能只测运行角色** —— 运行角色被 REVOKE 之后报的是
   `permission denied`，测不到触发器；用 owner 连接才测得到「有权限也过不去触发器」这一层。
5. **api-proxy 的 allowlist 正则必须真跑一遍**：本车道第一版把 UUID 分支漏了前导 `/`
   （`fund-accounts/{uuid}` 全部 404，而 typecheck / lint / vitest 全绿）。
   逐条 `new RegExp(pat).test(shape)` 实测 + 对既有全部正则**零命中**核对才敢说没误伤。
6. **余额历史行的 `previous_amount` 必须等于账户当时的 `current_balance`**（DB 触发器钉死首尾相接）——
   测试里随手写一个数会被守卫挡掉。

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` ✅（3/3 包）。
- **L1 模块闸**：`./mvnw -pl services/finance -am test -Dtest='FundAccount*' …` ✅
  **29 例全绿**（FundAccountIntegrationTest 20 + FundAccountTenancyIntegrationTest 9），
  实跑 Testcontainers PostgreSQL 16，连接池压到 1，三条隔离断言跑在**本模块自己的表**上。
- **前端**：`pnpm vitest run apps/web/src/components/finance/fund-account-model.test.ts` ✅ 9 例；
  `pnpm vitest run apps/web/src/app/api/finance` ✅ **24 文件 / 122 例**（改了 api-proxy 的全量 allowlist 回归）。
- **lint**：`pnpm --filter @starcon/web lint` ✅（只有别的模块的既有 warning）。

## 后续待办

- **12.2 收付款引用受管账户**：`finance_receipt.fund_account_ref` 的 CHECK 仍钉死恒为 NULL，
  要等 12.2 自己接线（那是它的写入所有权 + 迁移版本号由调度者统一分配）。
- **12.7.2.1 资金分析**的账户级资金分布接线（同上，由它自己改 CHECK）。
- **12.7.1.2 银行对账**：拿到本模块的「账户档案 + 余额历史」后做对账。
- **导航登记**：本页**刻意没有**在 `packages/ui/src/lib/task-pages.ts` 登记导航 ——
  那个文件是多车道并发修改的共享文件，并入时必然冲突，留给调度者统一处理。
