# 2026-10-24 12.2.2.2 付款执行 —— L3 12.2.2 的第二格

> 车道：`feat/12-2-2-2`（worktree `.worktrees/12.2.2.2`），基线 `integration/module-dev` @ `2874248d`。
> 阶段 D6（台账 `module-progress.md` 的阶段列由 `sync.mjs` 从蓝图生成，**未手改**；任务书里写的
> 「阶段 D4」与台账的 D6 不一致 —— 以台账 / 蓝图为准），L3 锚点 **12.2.2 付款**
> （该 L3 下两格：12.2.2.1 付款单与审批**已交付**，本车道是第二格）。
> 迁移版本号由调度者预分配为 **V350**（同批另三条车道占 V347 / V348 / V349）。
> 上游依赖在开工前已核对：V346 的 `finance_payment_order` 及其状态 / 审批字段、
> `ExchangeRatePort` / `CatalogExchangeRatePort`、12.2.2.1 的 exchange-rate 端点**全部存在** ⇒ 未 BLOCKED。

## 这个功能做什么

把一张**已批**的付款单**真的付出去**：对接支付渠道 / 银企直连，发送付款指令，跟踪结果，
失败重试与异常处理；处理汇率锁汇。产出是**付款结果 + 渠道流水号 + 失败原因**。

蓝图原文的关系行是：「上游 = 12.2.2.1；下游 = 12.7.1 资金账户、12.5.2 凭证。
**外部支付超时不等于失败**，必须先查证再重发——重复付款是严重资损事故。」

## 决策摘要

### 1. 「超时不等于失败」落成**独立的状态**，而不是一句注释

执行单的状态是 `PENDING / SENDING / SUCCEEDED / FAILED / **UNKNOWN**`。
UNKNOWN = 结果未定（渠道超时 / 5xx / 网络不可达 / 回单金额对不上）。

三层都钉死了它：

- **执行单 CHECK**：`FAILED` 必须写明失败原因与分类；
- **付款单状态守卫**：写 `FAILED` 必须有一条 `status='FAILED'` 的执行记录 ⇒
  **UNKNOWN 的执行单写不成「失败」**（连直连 SQL 也一样）；
- **前端色调**：UNKNOWN 是**独立的第四种语气**（warning），**不复用** FAILED 的 danger ——
  标红等于在催人点重试，而重复付款是严重资损事故。

### 2. 幂等键**先落库再发送**，且是**确定性**派生

`finance_payment_execution.idempotency_key` 带 `UNIQUE(tenant_id, idempotency_key)`，
键由「付款单 + 分批」派生 ⇒ **同一笔逻辑付款的每次重发都用同一个键**。

两条收益：

- 本地重复发起被唯一约束挡住（幂等键命中就 409）；
- **即使本地事务回滚把记录丢了**，重发用的还是同一个键 ⇒ 渠道侧去重 ⇒ **不会重复付款**。
  这才是幂等键的真正兜底价值。

### 3. 渠道调用**绝不放进数据库事务**

三个阶段、三次事务：`prepare`（落执行单 + 推进付款单到执行中 + 锁汇）→
`dispatch`（调渠道，**无事务**）→ `settle`（落尝试记录 + 写结论 + 回写行级已付金额）。

理由：渠道已经付了钱而本地事务回滚 ⇒ 本地**没有任何记录**说这笔钱付过。
分段之后最坏情况是「执行单停在已发送、没有结论」⇒ 下一步明确是**查证**而不是重发。

⚠️ **trade-off**：渠道调用与落库之间有窗口，所以**幂等键必须是确定性的**（见第 2 条）——
这是本模块唯一真正的兜底，不能省。

### 4. 重发前**必须先查证**，判据在服务端

`POST /{id}/retry` 的第一步**永远是**状态查证：

- 查证发现渠道侧**已成功** ⇒ **不重发**，直接收敛成「已付」（**重复付款是严重资损事故**）；
- 查证确认渠道侧**确实没有**这笔 ⇒ 才允许重发（**同一个幂等键**）；
- 查证**仍查不出来** ⇒ 拒绝重发（查不出来 ≠ 没付出去）。

DB 层也有一道：`guard_finance_payment_execution_resend_guard` 要求
「从 UNKNOWN / FAILED 回到 SENDING 之前必须已有一条 QUERY 尝试记录」。

### 5. 渠道侧**没有内置的假成功**

支付渠道走 `PaymentChannelPort` 抽象（HTTP 实现 `HttpPaymentChannelAdapter`）：

- 没有凭据 / 主机不在白名单 / 渠道未启用 ⇒ **明确报「渠道不可用 / 未配置」**，
  并**如实记成失败**（预检阶段就拦下，付款单停在「已批」，**不建执行单、不发任何请求**）；
- 渠道超时 / 5xx / 不可达 ⇒ **UNKNOWN**，不是 FAILED；
- 渠道回单金额 / 币种与指令不一致 ⇒ **金额不符**，停在「执行中」等人工核对
  （**不**悄悄按渠道金额记账，也**不**判失败 —— 判失败会释放明细 ⇒ 可能重复付款）。

凭据只存**密钥管理器引用**（`secret://` / `kv://` / `vault://` / `external-secrets://`，
表级 CHECK 钉死前缀），本系统**不保存任何明文密钥**（代码、迁移、日志里都没有）。

另加一道 **主机白名单**（`PAYMENT_CHANNEL_ALLOWED_HOSTS`，fail-closed）：
租户可以登记渠道地址，而渠道地址会变成一次**由本系统发起的外呼** ⇒ 不加白名单就等于把内网变成攻击面。

### 6. 锁汇锁的是**单据冻结的汇率**，不是发送那一刻的市场价

12.2.2.1 建单时冻结了汇率（触发器挡着事后改）⇒ 付款用的是**那个**汇率。
今天汇率变了不能让今天的付款悄悄按新汇率付出去。

- 锁汇记录落 `finance_rate_lock`（rate / base_amount / 有效期 72 小时 / 来源 `ORDER_SNAPSHOT`）；
- 付款指令带上锁定的 rate / baseAmount；
- 单币种付款**不锁汇**（没有换算就没有锁 —— 如实行为，不是缺功能）。

### 7. 行级已付金额是**增量回写**，分摊必须**留痕**

`finance_payable_line.paid_amount` 在**付款成功那一刻**才加上去
（V340 的触发器本来就放行这一列，**不需要**改 V340 的任何东西）。

🔴 **分批安排下**，「这一批的钱落在哪几行应付明细上」必须**当场记下来**
（`finance_payment_execution_allocation`）：否则第二次付款时无法判断
「这一行已经收了多少」⇒ 就会重复付款。
分摊算法：按付款单明细行顺序分配，每行不超过「本行应付余额」与「本行还没分摊出去的额度」。

### 8. 接管 V346 的守卫时，顺手修好一条**只在执行态才会暴露**的死路

V346 把 `execution_available` 用表级 CHECK 钉死为 false，12.2.2.1 因此**写不出「已付」**
（这是它写死的交接面）。本车道接管时做了四件事：

1. 放开该 CHECK 并反过来钉成**必须为 true**；
   ⚠️ **默认值也必须一并改** —— V346 的列默认值是 false，而 12.2.2.1 建单时不写这一列
   （走默认值）⇒ 默认值不改的话，**12.2.2.1 的建单会被新 CHECK 直接拒掉**；
2. 换掉两个状态守卫触发器：路径一字不改，把「本模块写不出执行态」换成**更强**的一条 ——
   **写「执行中 / 已付 / 失败」必须有对应的付款执行记录**；
3. 修好 V346 的 `guard_finance_payment_order_release_lines` 死路：
   付款单转终态时它要改明细行的 `active`，而明细行的 BEFORE UPDATE 触发器在父单非草稿时
   **直接报错** ⇒ 「付款单转终态」在 V346 里**走不通**（12.2.2.1 写不出那些状态，所以一直没暴露）。
   本迁移放行「只动 `active` 一列」的更新；
4. 分批的 UPDATE 放行**执行态**推进，但**钱的那些事实**（金额 / 币种 / 计划付款日）仍然冻结。

## 文件清单

### 迁移

- `backend/services/finance/src/main/resources/db/migration/V350__finance_payment_execution.sql`
  —— 六张新表 + 接管 V346 的守卫 + 索引 + RLS + GRANT。

### 后端（finance 服务）

- `PaymentExecutionModels.java` —— 传输模型（枚举 / 行 / 详情 / 汇总 / 入参 / 结果）。
- `PaymentExecutionService.java` —— 业务逻辑（prepare / dispatch / settle 三阶段 + 锁汇 + 分摊回写）。
- `PaymentExecutionController.java` —— 端点（12 个，挂 `/finance/payment-executions`）。
- `PaymentChannelPort.java` —— 支付渠道适配器（port）+ 渠道协议文档。
- `HttpPaymentChannelAdapter.java` —— HTTP 实现（回单 → 结论的映射表 + 主机白名单）。
- `PaymentSecretPort.java` —— 密钥管理器（port，只读、只返回解析结果）。
- `HttpPaymentSecretPort.java` —— HTTP 实现（未接入 ⇒ 解析不到 ⇒ 渠道判「未配置」）。

### 测试

- `PaymentExecutionIntegrationTest.java` —— 15 条业务断言（真实 PG16）。
- `PaymentExecutionTenancyIntegrationTest.java` —— 5 条隔离断言（连接池压到 1）。

### 前端

- `apps/web/src/components/finance/payment-execution-model.ts` —— API 客户端 + 可读形态 + 动作可用性。
- `apps/web/src/components/finance/payment-execution-model.test.ts` —— 15 条。
- `apps/web/src/components/finance/payment-execution-workspace.tsx` —— 真实工作区。
- `apps/web/src/app/finance/payment-executions/page.tsx` —— 路由页。
- `apps/web/src/app/api/finance/payment-executions-allowlist.test.ts` —— 5 条白名单双向断言。
- `apps/web/src/lib/api-proxy.ts` —— **纯追加**一条 services[] 元素（首 token `payment-executions`）。

### 契约与台账

- `packages/api-contracts/src/contracts/finance.ts` + `packages/api-contracts/src/finance.openapi.json`
  —— **codegen 产物**（`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`），未手改。
- `docs/product/capability-blueprint/module-progress.md` —— **只改 12.2.2.2 那一行的「状态」「证据」两格**。

## 验证

- L0：`cd backend && ./mvnw -q compile` → EXIT=0；
  `pnpm typecheck` → `Tasks: 3 successful, 3 total`。
- L1：`./mvnw -pl services/finance -am test -Dtest='PaymentExecution*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → `Tests run: 20, Failures: 0, Errors: 0` BUILD SUCCESS。
- 前端本车道：`pnpm vitest run apps/web/src/components/finance/payment-execution-model.test.ts apps/web/src/app/api/finance/payment-executions-allowlist.test.ts`
  → 2 文件 / 20 例全绿。
- **全量 allowlist 回归**（本车道改了共享白名单 `api-proxy.ts`，必须跑）：
  `pnpm vitest run apps/web/src/app/api` → **222 文件 / 1259 例全绿**。
- `pnpm lint` → `Tasks: 3 successful, 3 total`（只有别的文件的既有 warning）。

## 后续待办

1. **12.7.1.1 资金账户**（未交付）：付款账户仍只是 12.2.2.1 登记的「引用 + 名称 + 币种快照」，
   本模块**不建账户表、不做账户余额**（也不校验余额 —— 没有余额真相可校验）。
   它交付后由它接管账户与余额，本模块结构不用改。
2. **12.5.2.1 凭证**（未交付）：付款成功**不生成凭证**（那会造出第二套账），
   只留下「付款事实 + 渠道流水号」；凭证由它取数生成。
3. **部分已付 + 部分失败**的付款单会停在「执行中」等人工处理
   （判失败会释放应付明细 ⇒ 可能重复付款，所以本模块**不放**这条路）。后续要么由 12.2.1.2
   的核销/坏账处理，要么由运营先把失败的那批重发成功。
4. **渠道协议**目前是一份**通用 HTTP 协议**（`POST {baseUrl}/payments` + `GET {baseUrl}/payments/{key}`）。
   PayPal / Stripe / PingPong 各自的原生 API 形状不同 ⇒ 要么为它们各写一个适配器，
   要么在渠道侧加一层协议网关。**在拿到真实凭据与真实渠道 API 之前不猜**。
5. **主机白名单与密钥管理器是运维配置项**（`PAYMENT_CHANNEL_ALLOWED_HOSTS` /
   `PAYMENT_SECRET_MANAGER_URL` / `PAYMENT_SECRET_MANAGER_KEY`）：**没有它们，渠道一律判「未配置」**
   （fail-closed，宁可不付也不能在凭据不明的情况下把钱打出去）。
