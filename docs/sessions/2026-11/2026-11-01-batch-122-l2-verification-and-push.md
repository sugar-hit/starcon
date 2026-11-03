# batch-122 · 并行车道流水线第 122 轮：L2 全量验证 + 累积批次首次推送

> 轮次：2026-11-01 04:00 tick（降档模式，`LANES=4 → 2`，节流闸 50 → 100 分钟）
> 集成头：`integration/module-dev`，本轮起点 `1f342b8d`
> 推送目标：`origin/integration/module-dev`（**不推 main**）

## 本轮结论速览

| 项                     | 结果                                                              |
| ---------------------- | ----------------------------------------------------------------- |
| 进度                   | 465 个 L4 模块 · **已交付 445** · 进行中 0 · **未开发 20** · 95.7% |
| 容量闸                 | **GO**（温度闸正常 / running 1<6 / PG 容器 0<6 / load 1.85<12）    |
| 本批回收               | 无（`merge-ready` 返回 `merged: []`，没有 ready 车道）             |
| 本批派发               | **2 条**：18.3.1.2 规则编辑器（V421）、18.4.1.1 对话助手（V422）    |
| 静态预检               | **三项全清**（ADD COLUMN 无 DEFAULT / SET NOT NULL / ADD CONSTRAINT CHECK 均无命中） |
| L2 批量验证           | **绿**（详见「L2 批量验证结果」一节）                            |
| 收尾三件套             | 说明 ✅ / commit ✅ / push ✅                                      |
| 推送内容               | **74 个 commit**（含 13 个 merge commit）首次落到 origin           |

> ⚠️ **本轮的主要价值不是「新交付了模块」，而是「把此前因 L2 红而滞留在本地的 74 个提交第一次推上远端」。**
> 上轮（batch-121）L2 判红在存量 warehouse 跨钟域缺陷，本轮该缺陷已由 `723cee8f` 修复并并入，
> 于是整条累积批次第一次拿到全绿验证并得以推送。

---

## 容量闸判定（GO）

| 闸           | 阈值             | 实测                   | 判定 |
| ------------ | ---------------- | ---------------------- | ---- |
| 温度闸       | 有降频项即 STOP  | `No thermal warning level has been recorded` | ✅ 通过 |
| 配额闸       | running ≥ 6 STOP | 1（18.3.1.2，且为待回收的僵尸车道） | ✅ 通过 |
| PG 闸        | 带标签容器 ≥ 6 STOP | **0**（无 testcontainers 容器） | ✅ 通过 |
| load 闸      | 1min > 12 STOP  | 1.85 / 1.76 / 1.70，90 秒内 6 次采样在 1.58–1.87 间平稳 | ✅ 通过 |

静默窗口 `SILENT_WINDOW=0` ⇒ 不设时段闸；温度闸 / 容量闸 / 节流闸照旧生效（本轮全部通过）。
节流闸：距上批 **466 分钟** ≥ 降档后阈值 **100 分钟** ⇒ 放行。

## 本批回收

`node tools/module-progress/lane.mjs merge-ready` 返回 `{"merged": []}` —— **没有 ready 车道**。
上一轮（batch-121）的车道早已并入并提交，只是因 L2 红未推送。`lane.mjs gc` 返回 `{"removed": []}`。

### 僵尸车道取证与回收

台账里唯一一条 `running` 车道 **18.3.1.2 规则编辑器**心跳停在 `2026-10-31T16:12:10Z`（距本轮 3h48m，
远超 90 分钟 TTL）。本轮亲自取证，结论是**空跑而非慢跑**：

- 分支 `feat/18-3-1-2` 的尖端是 `18b0d933 merge(18.3.1.1): 规则列表`，即**创建时的基线**，
  `git merge-base --is-ancestor` 确认它是集成头的祖先 ⇒ 该车道**一个 commit 都没产出**；
- 工作区 `git status --porcelain` 为 **0 行** ⇒ 没有未提交的半成品；
- `lane.mjs plan` 的孤儿回收逻辑（`laneIsStale` ⇒ orphaned ⇒ 重派）自动接管了它，
  `redispatched: 1`，版本号重新预分配为 V421。

## 本批派发（2 条，降档后 LANES=2）

| 模块 ID   | 名称     | 阶段 | L3     | 分支            | 迁移版本 | worktree                                     |
| --------- | -------- | ---- | ------ | --------------- | -------- | -------------------------------------------- |
| 18.3.1.2  | 规则编辑器 | D7  | 18.3.1 | `feat/18-3-1-2` | **V421** | `.worktrees/18.3.1.2`                         |
| 18.4.1.1  | 对话助手 | D7  | 18.4.1 | `feat/18-4-1-1` | **V422** | `.worktrees/18.4.1.1`                         |

两条车道 L3 锚点互不相同（18.3.1 / 18.4.1），互不依赖；上游已交付；
落地微服务 `platform` 已存在于集成头 ⇒ 通过微服务闸（`serviceBlocked` 为空）。
派发前预检：V421/V422 未被占用（磁盘最大 V420），两条 worktree 均干净、`node_modules` 就位。

### ⚠️ 两条车道的派发都被基础设施错误打断（含额度耗尽）

首次派发后约 15 分钟，两条子任务**在同一瞬间**（20:20:37Z / 20:20:48Z）以**同一个**
`net::ERR_HTTP2_PING_FAILED` 崩溃。取证结论：

- 这是**运行时/网络层的传输故障**，不是 worker 逻辑问题 —— 崩溃前两条车道都在正常产出
  （18.3.1.2 已完成依赖自检并在读 api-proxy 与 codegen 约定；18.4.1.1 已定位到 reporting 服务的利润端点）；
- 机器资源健康（load 2.65、内存 53% 空闲、磁盘 19%），**排除资源耗尽**；
- 两条 worktree 均 0 改动、无提交 ⇒ **无半成品可捞，也无双写风险**。

处置与后续：原车道重派（同一 worktree、同一分支、同一预分配版本号），并把上一轮已确认的探索结论
写进任务书以省掉重复排查。**但重派仍被基础设施错误打断**，最终查明根因是
**Token Plan 额度耗尽**（详见「在途车道」一节的四次失败记录与硬阻塞说明）。
18.3.1.2 在额度耗尽前已写出 1596 行半成品（保留在 worktree 供下轮续做），
18.4.1.1 零产出。**本轮无法再派发子任务。**

---

## 静态预检（互相破坏形状扫描）

对本批 15 个新迁移（platform V413–V420 共 8 个 + identity V413–V420 权限码共 7 个）逐条扫描：

| 形状 | 命令 | 结果 |
| ---- | ---- | ---- |
| ① 加了列但没给默认值 | `grep 'ADD COLUMN' … \| grep -v DEFAULT` | **✅ 无命中** |
| ② 改成必填但无触发器兜底 | `grep 'SET NOT NULL' …` | **✅ 无命中** |
| ③ 新增 CHECK 约束 | `grep 'ADD CONSTRAINT.*CHECK' …` | **✅ 无命中** |

补充取证：本批迁移**全部是纯新建表**（platform 侧 8 个文件共 29 张 CREATE TABLE，identity 侧 7 个为权限码），
`ALTER TABLE` 的命中项**全部**是 `ENABLE ROW LEVEL SECURITY` / `FORCE ROW LEVEL SECURITY`，
作用对象都是本批自己新建的表 ⇒ 不存在「破坏早先车道 INSERT」的结构性风险。

---

## L2 批量验证结果

**绿**（前端四项全绿 + 后端全量验证在隔离复跑与续跑下全绿）。逐项结果与一次**机器休眠导致的假红**处置记录如下。

### 前端四项（一次跑完，全部绿）

| 项 | 命令 | 结果 |
| -- | ---- | ---- |
| 类型检查 | `pnpm typecheck` | ✅ 3/3 任务成功（turbo 缓存命中） |
| Lint | `pnpm lint` | ✅ 3/3 任务成功（仅 react-hooks/exhaustive-deps warning，无 error） |
| 单测 | `pnpm test` | ✅ **707 个测试文件 / 9962 例全过**，20.35s |
| 生产构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ 成功，完整路由表输出 |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | ✅ 「台账与蓝图一致」 |
| 增量格式闸 | `node tools/module-progress/format-gate.mjs` | ✅ `violations: []`（本批 81 个变更文件、34 个受 prettier 管辖） |

### 后端全量 `mvnw verify`：一次假红 + 隔离复跑证伪

**第一次运行**：11 模块 reactor 跑到 **warehouse（第 6 个模块）**时中断，报 3 处失败：

```
[ERROR] Tests run: 1674, Failures: 2, Errors: 1, Skipped: 0
[ERROR] AllocationPolicyIntegrationTest.断言三_连接池归还后不串租户
[ERROR] AllocationPolicyIntegrationTest.缓冲按比例与按件数都真的生效:810 » ArrayIndexOutOfBounds Index 0 out of bounds for length 0
[ERROR] SafetyStockIntegrationTest.断言二_伪造租户头无效:1400 JSON path "$.total" expected:<1> but was:<0>
```

**处置过程**（按协议「先隔离复跑最大那一堆，不要直接归因成回归」）：

1. **隔离复跑**两个失败类：
   ```bash
   ./mvnw -pl services/warehouse -am test \
     -Dtest='AllocationPolicyIntegrationTest,SafetyStockIntegrationTest' \
     -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
   ```
   ⇒ **Tests run: 76, Failures: 0, Errors: 0，BUILD SUCCESS**；
   两个类的耗时从 **316.1s / 317.2s 降到 16.08s / 14.10s**（约 20 倍）。
2. **根因取证**：
   - 失败窗口内大量 HikariPool 同时报 `Thread starvation or clock leap detected (housekeeper delta=5m28s)`
     ⇒ **JVM 整体被冻结约 5 分 28 秒**；
   - `pmset -g log` 显示 **07:30:06 `Entering Sleep state due to 'Maintenance Sleep'`**、
     **07:42:02 唤醒** ⇒ **macOS 在测试期间进入维护性休眠**；
   - `docker ps` 无孤儿容器、无带标签容器堆积 ⇒ 排除容器资源耗尽；
   - 本批 warehouse 唯一改动 `723cee8f` 只碰了 `ConsignmentService.java`，
     **未触及 safety-stock / allocation-policy** ⇒ 排除代码回归。
3. **判定**：3 处失败是**机器休眠造成的假红**。租户隔离断言在「种子行还没落库」的时刻取值 ⇒ 读出 0 行。
   这正是协议描述的形状：*「成片失败 ⇒ 先隔离复跑，不要直接归因成回归」*。
4. **处置**：续跑改为 `caffeinate -dimsu` 包裹（阻止 macOS 维护性休眠），
   重跑 warehouse 及其后**尚未执行**的 5 个模块
   （trade / platform / reporting / finance / crm），补齐 L2 覆盖。

⚠️ **给用户的提示**：L2 长跑期间 macOS 会因「Maintenance Sleep」把机器挂起数分钟，
足以让正在跑的集成测试读出假失败。**跑长批次验证时应当用 `caffeinate` 兜住**，
否则每一轮 L2 都可能随机红在几个类上，浪费大量二分时间。

### 工具侧偏差

- 本轮第一次 `mvnw verify` **被后台任务 3600 秒运行上限中途杀掉**（不是测试失败），
  改用 `nohup` 脱离运行 + 日志落盘后才跑完。**后续轮次跑全量验证必须用脱离方式**，
  否则长批次会被工具超时截断，结论只能是「未知」而不是「绿/红」。
- `PIPELINE.md` 记录的「后端 2829 例 / 前端 2163 例 / 151 个测试类」已过期 ——
  实测**后端 770 个测试类、前端 707 个测试文件 9962 例**；
  单轮后端全量实测 **3 小时以上**（每个测试类都新建 Testcontainers PG 并重放全部 Flyway 迁移），
  协议里「L2 约 25–40 分钟」「发热峰值 = 1 次全量」的估算需要按新规模重算。

---

## 交付模块逐节说明

> 本节覆盖**本轮推送出去的这一批**交付。每个模块的完整原始证据见台账
> `docs/product/capability-blueprint/module-progress.md` 对应行，以及各自的专题会话日志
> （末尾「文件清单」列出）。此处按流水线要求给出「功能 / 迁移 / 端点 / 页面 / 测试 / 关键设计决策」六要素。

### 模块 18.1.2.2 投递记录

**这个功能做什么**：运维能看到「这个事件到底投出去了没有、投了几次、对方回了什么、失败卡在哪」，
并能对失败的投递**手动重投**、对耗尽重试的死信**重放**。

**技术落地**：

- **迁移**：`V413__webhook_delivery_record.sql` — 四表 `platform_delivery_record`（投递记录）/
  `platform_delivery_attempt`（逐次投递日志）/ `platform_delivery_dead_letter`（死信）/
  `platform_delivery_replay_task`（重放任务）；`tenant_id NOT NULL` + `UNIQUE(tenant_id,…)` 复合键 +
  跨表 `(tenant_id, …)` 复合外键 + **ENABLE 与 FORCE** RLS + `GRANT SELECT,INSERT,UPDATE`（**不给 DELETE**）。
  权限码另见 `V413__webhook_delivery_record_permission.sql`（`platform.webhook.delivery.read/.publish/.replay`）。
- **端点**：`/platform/deliveries` **11 条** — GET `/capabilities` `/overview` `/{id}` `/{id}/attempts`
  `/dead-letters` `/replay-tasks`；POST `/publish` `/{id}/replay` `/dead-letters/{id}/replay` `/retry-due`。
- **页面**：`/integrations/deliveries` — `apps/web/src/app/integrations/deliveries/page.tsx` +
  `components/integrations/deliveries/delivery-record-workspace.tsx` + `delivery-record-model.ts`。
- **测试**：`DeliveryRecordIntegrationTest`、`DeliveryRecordTenancyIntegrationTest`、`DeliveryRecordReceiver`。

**关键设计决策**：

- 🔴 **记录先落库、包后发**。反过来（先发包再落库）崩在中途 ⇒ 对方收到了、我们没有记录，
  「投没投出去」就变成一个答不出来的问题。
- 🔴 **手动重投重发同一份报文字节**（原始字节 Base64 落库，重放时**不重新序列化**）——
  否则字节序/空格变了，接收方的验签就对不上；**事件 ID 不变**，接收方按 `event_id` 幂等去重即可。
- 🔴 **响应码 0 是合法值**，含义是「根本没拿到响应」（连接被拒/超时/没发出去）——
  把它翻成 200 或 -1 都是编造事实。
- **不跟随重定向**（跟随了「我们投到了哪个地址」就不可审阅，3xx 如实记成 3xx）；响应体只读前 1 KB。
- 「至少一次」落在唯一键上：`UNIQUE(tenant_id, event_id, subscription_id)` ⇒ 同一事件对同一订阅只有一行，
  而「投递日志」是**每一次尝试**一行 —— 两个粒度刻意不同。

### 模块 18.1.1.3 调用监控

**这个功能做什么**：对接方说「你们接口有问题」时，能立刻定位到是哪个应用、哪个接口、
哪个时间窗、什么错误码、是否命中限流。

**技术落地**：

- **迁移**：`V414__api_call_monitor.sql` — 两表 `platform_api_call_log`（调用埋点，只追加：
  GRANT 只给 SELECT+INSERT，另用**触发器**在数据层拒绝 UPDATE/DELETE）+
  `platform_api_call_alert`（异常告警，部分唯一索引保证未闭环期间同一 应用×类型×接口 只一行）。
  权限码见 `V414__platform_call_monitor_permission.sql`（`platform.call-monitor.read/.update`）。
- **端点**：`/platform/call-monitor` **12 个**（GET capabilities/options/overview/trend/apps/endpoints/
  slow-calls/calls/alerts；POST alerts/scan、alerts/{alertId}/acknowledge、alerts/{alertId}/resolve）。
- **页面**：`/integrations/call-monitor` — `apps/web/src/app/integrations/call-monitor/page.tsx` +
  `components/integrations/call-monitor-workspace.tsx` + `call-monitor-model.ts`。
- **测试**：`ApiCallMonitorIntegrationTest`、`ApiCallMonitorTenancyIntegrationTest`。

**关键设计决策**：

- 🔴 **本车道把「上游 = API 网关」补上了**：全仓原本**没有网关**，也没有任何服务产出对外调用埋点 ⇒
  不补网关就没有埋点，没有埋点四维指标就只能是编出来的。故在 platform 内新增**真实**网关入口
  `/open/v1/**`：真实校验凭证（API Key 走 SHA-256 定位 + BCrypt 比 Secret）、真实判定授权范围/
  IP 白名单/配额、真实落库埋点。
- 🔴 **网关不是开放代理**：放行清单封闭（7 条逐条可数）+ 清单外一律 404，**不做路径转发** ——
  放行到后端不存在的映射比 404 更糟。
- 🔴 **网关租户来源是 API Key 反查**，与 `/platform/**` 的会话租户彻底分开 —— 一个端点两种租户口径
  是本仓最不愿意出现的形状。跨租户凭证定位只走 `platform_api_credential_lookup(text)` SECURITY DEFINER 函数，
  且**不做 LIMIT 1**（同一 Key 值可在两个租户下各登记一次，必须把候选行全部拿去比 Secret，
  比中多行判歧义 409 并拒绝）。
- **限流命中与错误分列**（`outcome` 与 `rate_limited` 两列）—— 429 既是错误也是限流命中，但那是两个问题。
- CHECK 把结论码与状态码逐字钉死 ⇒ 「错误率」与「429 次数」两套口径不会互相打架。

### 模块 18.1.3.1 导入导出任务

**这个功能做什么**：用户能在平台里发起一次导出/导入，异步跑完能看进度、能下载结果文件、
能拿到失败行的逐行报告，不用找研发导 CSV。

**技术落地**：

- **迁移**：`V415__import_export_task.sql` — 四表 `platform_io_task`（统一任务）/ `platform_io_file`（文件本体）/
  `platform_io_row_error`（失败行逐行报告）/ `platform_io_template`（导入模板）；
  `tenant_id NOT NULL` + ENABLE 与 FORCE RLS + GRANT platform_app。
  权限码见 `V415__platform_io_permission.sql`（read/create/execute/download 四码 + 存量 OWNER 补权）。
- **端点**：`/platform/io` **14 条** — capabilities/overview/domains/tasks(GET+POST)/tasks/import/
  tasks/{uuid}/tasks/{uuid}/file（结果下载，**带有效期**，过期 410）/tasks/{uuid}/error-report/
  tasks/{uuid}/errors/tasks/{uuid}/cancel/templates/templates/{uuid}/templates/{uuid}/file/expire。
- **页面**：`/integrations/io-tasks` — `apps/web/src/app/integrations/io-tasks/page.tsx` +
  `components/integrations/io-task-workspace.tsx` + `io-task-model.ts`。
- **测试**：`ImportExportIntegrationTest`、`ImportExportTenancyIntegrationTest`、`ImportExportCsvTest`。

**关键设计决策**：

- 🔴 **「上游 = 全部业务域」按三条通道落地，不是「一条 SQL join 七个服务的表」**：
  ① platform 自有表；② 属主服务授权只读端点（只转发调用方自己的 bearer、不传租户号、不抄副本）；
  ③ 属主服务文件型导入作业（导入要写业务域，必须由属主写）。
- 🔴 **没接通的格子如实标 `available=false` 并写明缺什么、归谁**，绝不写成假的 true：
  订单/库存/平台商品/凭证/客户**只能导出不能导入**；导出对客户域未接通（crm 无客户档案列表授权端点），
  如实标 false，而不是导出一份空文件冒充「没有客户」。
- **导出与导入共用一张任务表**（两条链完全同构，拆两张表会让状态机写两遍而必然漂移）。
- **文件落磁盘不落库**（几十 MB 落进 TOAST 会撑大库），库里只留 `storage_key` + `sha256` + 字节数；
  存储实现做**路径穿越防护**。
- **凭据一个都不存**：导出要经属主服务读数据，上游按已认证成员关系解析租户 ⇒ 调用方的 bearer
  只在内存里活到这次执行结束。

### 模块 18.2.2.1 支付与资金

**这个功能做什么**：管理支付服务商连接、发起付款与收单、查证单笔支付、观测余额与流水、
同步手续费与汇率。

**技术落地**：

- **迁移**：`V416__payment_and_funds.sql` — 七表 `platform_payment_provider`（服务商连接档案）/
  `platform_payment_operation`（资金操作台账，含**幂等键** + 请求摘要 + 状态机）/
  `platform_payment_attempt`（每次尝试留痕，SEND/QUERY，**查证兜底**的证据）/`platform_payment_balance`/
  `platform_fund_flow`/`platform_payment_fee`/`platform_payment_rate`；
  金额/余额/手续费/流水额一律 `numeric(20,6)`、汇率 `numeric(24,12)`（禁浮点），四张只追加台账用触发器钉死 UPDATE/DELETE。
  权限码见 `V416__platform_payment_permission.sql`（read/create/update/execute/sync）。
- **端点**：`/platform/payment-funds` **22 条** — GET capabilities（13 维能力声明）/overview/providers/
  providers/availability/operations/operations/{id}/operations/{id}/attempts/balances/fees/rates/flows/
  upstream/fund-accounts/upstream/payment-orders；POST providers/providers/{id}/disable/payments/receipts/
  operations/{id}/retry/balances/query/balances/import/fees/sync/fees/import/rates/query。
- **页面**：`/integrations/connectors/payment-funds` — `apps/web/src/app/integrations/connectors/payment-funds/page.tsx` +
  `components/integrations/payment-funds-workspace.tsx` + `payment-funds-model.ts`。
- **测试**：`PaymentFundsIntegrationTest`、`PaymentFundsTenancyIntegrationTest`、
  `PaymentProviderAdapterTest`、`StubPaymentProviderPort`。

**关键设计决策**：

- 🔴 **幂等键的物理保证**：`UNIQUE(tenant_id, idempotency_key)` ⇒ 同键重复提交只可能命中同一行，
  服务层原样返回既有结果（`replayed=true`）、**绝不第二次调用服务商**；
  `request_digest`（SHA-256）钉住「同键必须同请求」，同键不同请求 ⇒ 409。
- 🔴 **UNAVAILABLE / UNKNOWN / FAILED 三个值刻意分开**：超时**不是**失败
  （混在一起会让重试跳过查证直接二次付款 ⇒ 资损）。
- 🔴 **超时先查证再重试**：发起超时 ⇒ 操作进 UNKNOWN ⇒ **自动向服务商查证这一笔** ⇒
  查证说成功 ⇒ **不重发**；查证说「服务商侧确实没有」⇒ 仍记 UNKNOWN 但允许重试；
  **查不出来 ⇒ 不允许重试**（查不到 ≠ 没付出去）。
- 🔴 **五个服务商（PayPal/Stripe/PingPong/连连/万里汇）全部如实答 `available=false`** ——
  本仓不存在这些适配器，也没有真实凭据与网络通道 ⇒ 端点如实答 409 且不写成功流水。
  适配器**不猜**各服务商的接口形状。
- 🔴 **密钥只存引用**：`credential_ref` 存密钥管理器的**引用 ID**，`credential_ref_lookup` 存**引用**的
  SHA-256 摘要，`credential_hint` 存末四位；**库里没有任何密钥明文**。
- 「**付款执行**」与「**直连同步**」是两个独立的权限码 —— 能配服务商档案的人不该顺手就能发起付款。

### 模块 18.1.3.2 数据迁移

**这个功能做什么**：从其他 ERP 导入历史数据：配字段映射、先试运行看结果、再正式迁移，
出问题能按行回滚，并拿到核对报告（成功/失败/跳过数量与原因）。

**技术落地**：

- **迁移**：`V417__data_migration.sql` — 四表 `platform_migration_mapping`（映射配置）/
  `platform_migration_run`（迁移任务）/ `platform_migration_file`（源文件与核对报告）/
  `platform_migration_row`（逐行结论）；`tenant_id NOT NULL` + ENABLE 与 FORCE RLS + GRANT platform_app。
- **端点**：`/platform/data-migration` **12 条** — capabilities/overview/targets/mappings(GET+POST)/
  mappings/{uuid}(GET/PUT/DELETE)/runs(GET+POST)/runs/{uuid}/runs/{uuid}/rollback/runs/{uuid}/rows/
  runs/{uuid}/report/runs/{uuid}/rollback-report/expire。
- **页面**：`/integrations/data-migrations` — `apps/web/src/app/integrations/data-migrations/page.tsx` +
  `components/integrations/data-migration-workspace.tsx` + `data-migration-model.ts`。
- **测试**：`DataMigrationIntegrationTest`、`DataMigrationTenancyIntegrationTest`。

**关键设计决策**：

- 🔴 **「下游 = 各业务域」按「有没有 create/delete 对」落地，不是「七个域都能迁」**：
  只有 **catalog 商品域**接通（`POST/DELETE /catalog/products`、SKU 的增删）⇒ **回滚可以是真的**
  （按逐行记下的 upstream_id 去目标库真删）；订单/库存/凭证三格如实标 `available=false` 并写明缺什么归谁 ——
  没有 create/delete 对的域做迁移，「失败回滚」只能是假的（页面写着「已回滚」而数据还在）。
- **历史数据迁移必须支持试运行**（蓝图加粗句）：试运行只算不写。
- **不另造区间语义**：时间窗口与口径沿用既有约定，避免同义不同义。

### 模块 18.2.3.1 其他对接

**这个功能做什么**：统一管理 1688/淘宝、海外仓服务商、物流商、聚合认证、自建 BI 等外部系统的
连接配置与同步健康，**且一个外部服务故障不影响其他集成**。

**技术落地**：

- **迁移**：`V418__external_integration.sql` — 三表 `platform_external_connection`（连接档案）/
  `platform_external_sync_task`（同步任务配置）/ `platform_external_sync_record`（同步记录台账，只追加，触发器钉死 UPDATE/DELETE）。
  权限码见 `V418__platform_external_permission.sql`（read/create/update/sync）。
- **端点**：**19 条**，挂 `/platform/external-integrations`（`ExternalIntegrationController.java`，jOOQ 显式 SQL）。
- **页面**：`/integrations/connectors/external-integrations` —
  `apps/web/src/app/integrations/connectors/external-integrations/page.tsx` +
  `components/integrations/external-integration-workspace.tsx` + `external-integration-model.ts`。
- **测试**：`ExternalIntegrationIntegrationTest`、`ExternalIntegrationTenancyIntegrationTest`、
  `ExternalIntegrationAdapterTest`、`ExternalIntegrationHealthRulesTest`。

**关键设计决策**：

- 🔴 **「每个对接都有独立连接与独立健康状态」在数据层是物理事实**：`health_status` 是**连接自己那一行**的列
  （写 A 的健康在物理上改不到 B 的行），健康重算的 SQL **必带 `connection_id` 过滤**；
  **刻意没有「全局集成健康」这一列** —— 一旦有它，「一个对接挂了」就会显示成「全部都不健康」。
- 🔴 **「本地台账成功」≠「外部侧已登记」**：`provider_notified` 与 `status` 是**两个分开的字段**，
  DB 层 CHECK 钉死「DIRECT + SUCCEEDED ⇒ `provider_notified=true`」；
  「外部侧已完成」的人工登记行永远是 `source=EXTERNAL` + `provider_notified=false`。
- 🔴 **健康判定「记录优先」**：外部侧的事实（哪怕是人工登记的成功）**不被**「本系统直连通道没接通」压掉 ——
  否则本模块唯一「今天就能用」的那条路径会永远显示成「未接入」，能力声明就成了空话。

### 模块 18.3.1.1 规则列表

**这个功能做什么**：管理自动化规则：规则名称、触发事件、条件、动作、优先级、启用范围、试运行开关与执行统计。

**技术落地**：

- **迁移**：`V419__automation_rule.sql` — 五表 `platform_automation_rule`（规则档案）/
  `platform_automation_condition`/`platform_automation_action`/`platform_automation_scope`/`platform_automation_execution`（执行统计台账）；
  `tenant_id NOT NULL` + ENABLE 与 FORCE RLS + GRANT platform_app。
  权限码见 `V419__platform_automation_rule_permission.sql`（read/create/update/record 四码）。
- **端点**：`/platform/automation-rules` **15 条** — GET `/capabilities` `/overview` `/rules` `/rules/{uuid}`
  `/rules/{uuid}/executions` `/applicable` `/event-types` `/upstream/shops` `/upstream/warehouses`；
  POST `/rules` `/rules/reorder` `/rules/{uuid}/enable` `/rules/{uuid}/disable` `/rules/{uuid}/dry-run` 等。
- **页面**：`/automations/rules` — `apps/web/src/app/automations/rules/page.tsx` +
  `components/automations/automation-rule-workspace.tsx` + `automation-rule-model.ts`。
- **测试**：`AutomationRuleIntegrationTest`、`AutomationRuleTenancyIntegrationTest`；
  另配 `apps/web/src/app/api/platform/automation-rule-allowlist.test.ts` 做 api-proxy 白名单双向回归。

**关键设计决策**：

- 🔴 **蓝图加粗句「规则要能看出最后一次执行结果，否则坏规则静默失败无人察觉」在数据层被钉成物理事实**：
  执行统计落在**规则档案自己那一行**上（`last_run_at`/`last_run_status`/`last_run_failure_reason`/
  `last_run_execution_id` + `run_count`/`success_count`/`failure_count`）—— **不是一个「有没有执行过」的布尔位**。
- 🔴 **坏规则有反面判据**：`consecutive_failure_count` + `consecutive_failure_threshold` + `failing` 三件套，
  DB 层 CHECK 把 `failing` 与「连续失败数 ≥ 阈值」钉成**永远一致**；
  登记执行结果的 SQL **只更新统计列**、**不碰** `enabled`/`status` ⇒ 坏规则不会被悄悄改回正常。
- **`dry_run` 是独立列**（与 enabled/status 互不影响），CHECK 钉死 `dry_run ⇒ executed_actions = 0`，
  另有**触发器**钉死「执行行的 dry_run 必须与规则一致」。
- **启用范围参与过滤**：`GET /rules/applicable` 的 SQL 真的 JOIN 范围表 ——
  **没有范围行的规则不对任何店铺/仓库生效**；范围行有 CHECK（店铺/仓库范围必须带引用，租户范围不得带引用）。
- **排序是全序** `priority, created_at, id`（`id` 是全局唯一主键 ⇒ 相同优先级的规则次序**确定**，
  不靠 DB 返回顺序）。

### 模块 18.3.3.1 自动审批

**这个功能做什么**：低风险小额单据自动通过、确定性违规单据自动驳回、金额分层自动路由审批人、
超时自动升级 —— 但只处理「明确安全」的场景，其余仍走人工。

**技术落地**：

- **迁移**：`V420__auto_approval.sql` — 四表 `platform_auto_approval_policy`（规则配置）/
  `platform_auto_approval_signal`（风险信号码表）/ `platform_auto_approval_decision`（决策台账，只追加）/
  `platform_auto_approval_escalation`（超时升级台账）；`tenant_id NOT NULL` + ENABLE 与 FORCE RLS。
  权限码见 `V420__platform_auto_approval_permission.sql`（read/create/update/decide）。
- **端点**：`/platform/auto-approvals` **17 条** — GET capabilities/overview/policies/policies/{uuid}/signals/
  decisions/decisions/{uuid}/escalations/escalations/{uuid}；POST policies/policies/{uuid}/disable/signals/
  signals/{uuid}/disable/decisions/escalations/scan/escalations/{uuid}/execute；PUT policies/{uuid}。
- **页面**：`/integrations/auto-approvals` — `apps/web/src/app/integrations/auto-approvals/page.tsx` +
  `components/integrations/auto-approval-workspace.tsx` + `auto-approval-model.ts`。
- **测试**：`AutoApprovalIntegrationTest`、`AutoApprovalTenancyIntegrationTest`。

**关键设计决策**：

- 🔴 **不在 platform 侧另起一套审批状态机**（那会与 identity 17.4 形成两套真相）：
  本模块形态是「**判定 + 记录 + 经授权端口转交**」；自动通过/自动驳回**不建上游实例**
  （没有待办就没有审批人，与上游「免审 EXEMPT」同义）。
- 🔴 **五条红线各自落成可执行判定 + 测试断言**：① 自动通过 = 小额 **且** 供应商已验证 **且** 无风险信号，
  三条判定逐条落库，**缺一条走人工**；② 自动驳回只对**确定性违规**，主观风险绝不驳回，
  且**未登记的码按 RISK 从严** ⇒ 调用方编一个码就能驳单不可能发生；
  ③ 金额分层路由**读上游 `approval_flow_amount_branch`**，区间左闭右开、金额比较一律 `BigDecimal.compareTo`；
  ④ 超时自动升级用**上游 DB 钟**的停留秒数与 `timeout_hours` 比较（同一钟域、到点才触发），
  落库时刻取**注入的 Clock**，无 sleep；⑤ 决策依据记**快照**（policy/riskSignal/routing/checks 四份 jsonb）——
  规则改了以后回看历史决策，看到的仍是**当初**那条规则。

### 模块 14.3.1.3 商品维度报表

**这个功能做什么**：按商品维度看广告效果：ACOS / ROAS / TACOS / 广告销售占比 / 广告后净利 / 广告占比利润，
并能导出 CSV。

**技术落地**：

- **迁移**：`backend/services/channel/src/main/resources/db/migration/V402__product_dimension_ad_report.sql` —
  **纯新建一张表、零 ALTER 任何既有表**：`ad_product_report_export`（导出留痕：请求人/商品维度/平台/店铺/
  归一后类型/状态/起止日/行数/数据截至/口径说明/归因规则原文/发生时刻）；
  `tenant_id NOT NULL` + ENABLE/FORCE RLS + `data_scope`（RESTRICTIVE，按店铺收敛）+ GRANT（`starcon_app` 零权限）。
  **事实表不另建**：直接读已交付上游的 V273 `ad_campaign_daily_metric`、V277 `ad_campaign_group_product`、
  V171 `listing`/`listing_variant`。
- **端点**：`/channel/ad-product-reports/**` — 5 个 path / 5 个端点（capabilities/options/rows/export/exports）。
  **没有 trend 端点**（蓝图输出句没有趋势图），**没有粒度参数**（行 = 一个商品 × 一个币种，整窗口汇总）；
  时间范围**必填**，跨度上限 400 天（超了直接 400，**不悄悄截断**），排序白名单映射（**排序串绝不拼进 SQL**）。
- **页面**：`/ads/product-reports` — `apps/web/src/app/ads/product-reports/page.tsx` +
  `components/ads/ad-product-reports.tsx` + `ad-product-report-model.ts`（+ `.test.ts`）。
- **测试**：`AdProductReportIntegrationTest`、`AdProductReportTenancyIntegrationTest`（channel 服务）。

**关键设计决策**：

- 🔴 **归因规则固定并公示**：商品维度的广告数字是**从活动级事实分摊出来的**（活动 → Listing 按当前登记的
  投放商品数等分；Listing → 内部 SKU 按当前登记的 SKU 变体数等分；SPU = 名下**在报表内**的 SKU 相加）。
  规则原文随报表/CSV 表头/导出留痕一起返回，每行标 `attributionMode` ∈ DIRECT/ALLOCATED_EVENLY/MIXED。
  **没登记投放商品的活动 ⇒ 归不到任何商品**，汇总里单列 `unattributedSpend`/`unattributedSales`，
  **不摊给任何商品也不摊成 0**。
- 🔴 **利润归属纪律**（照抄 13.2.2.2）：利润事实只到 SKU 粒度 ⇒ SKU/SPU 维度精确；
  **LISTING 维度只有「挂 1 个 SKU 且该 SKU 只挂 1 个 listing」才有利润**，否则该行利润列为空并带 `profitNote`，
  **不摊给任何一个 listing**。
- 🔴 **口径全部先汇总再相除**（Σ分子/Σ分母，**不是**各行比率的平均）；**分母为 0 一律给 null（不可比），不是 0**。
- 🔴 **TACOS / 广告销售占比的分子用「与利润侧同一范围」的花费与销售额**，不拿全量花费除部分收入。
  **不同币种不合并**（本仓没有汇率源）⇒ 每行带自己的币种、汇总按币种拆开。
- **跨服务读不到就降级 + 如实说原因**（不是 0）：利润上游读不到 ⇒ 利润侧六列全空 + `profitAvailable=false` + 原因。

### 修复：warehouse 寄售结算跨钟域

**这个功能做什么**：不是新模块，是一处**存量缺陷修复** —— 寄售结算区间与销售事实必须落在**同一个 UTC 日历**上，
否则本地时间 00:00–08:00 区间算出来的结算数必红。

**技术落地**：

- **提交**：`723cee8f fix(18.3.3.1): 寄售结算区间与销售事实统一到 UTC 日历 —— 修掉本地 00:00–08:00 必红的跨钟域缺陷`，
  并入提交 `cd1deb5b merge(fix-warehouse-consignment-clock-domain): 寄售结算跨钟域修复`。
- **测试**：`ConsignmentIntegrationTest`（覆盖跨钟域边界）。
- **专题记录**：`docs/sessions/2026-11/2026-11-01-warehouse-consignment-clock-domain-fix.md`。

**关键设计决策**：

- 🔴 **时间口径统一到 UTC 日历**，而不是各自按本地时区折算 —— 两个口径混用会造出「同一天不同数」的结果，
  而这类偏差在结算场景里是直接的钱。
- 这条修复是**上一轮 L2 判红的唯一根因**：batch-121 的 L2 红在这里，本轮修复后整批重验通过。

---

## 在途车道（本轮派发，尚未交付）

### 车道 18.3.1.2 规则编辑器（V421）

- **目标**：可视化编辑规则 —— 选择触发事件 → 配置条件（字段/操作符/值）→ 编排动作
  （调接口/改状态/建任务/发通知/生成单据）→ **用样本测试** → 保存。
- **上游**：18.3.1.1 已交付的 `V419` 五表 + `AutomationRuleController/Service/Models`。
- **要点**：**规则必须支持用样本测试**，否则只能在生产上试错 ⇒ 测试运行必须真的执行条件判定并产出真实求值明细，
  不许返回写死的 JSON。
- **状态**：**在途，且有可续做的半成品**。本轮派发后 worker 完成了依赖自检并写出三个文件
  （未提交、未跑 L0/L1，故**不允许 `land`**）：

  | 文件                                                                 | 行数 |
  | -------------------------------------------------------------------- | ---- |
  | `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleEditorModels.java` | 222 |
  | `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleEditorService.java` | 1138 |
  | `backend/services/platform/src/main/resources/db/migration/V421__automation_rule_editor.sql` | 236 |

  这些文件**保留在 worktree 里不提交**（未验证的代码不该进版本库），
  下一轮 `plan` 复用同一 worktree 时 worker 可直接续做，不用从零重来。

### 车道 18.4.1.1 对话助手（V422）

- **目标**：中文提问业务问题（「上周哪个店利润最差」「哪些 SKU 该补货了」）→ 返回答案、
  **附数据来源链接**、给出建议的下一步操作；支持追问与上下文延续。
- **上游**：各域只读查询接口（platform 侧 `TradeOrderPort`/`WarehouseInventoryPort`/`CatalogRatePort` 等）+
  reporting 服务的利润报表端点 + trade 的补货端点。
- **要点**：① 自然语言只映射到**受控语义层指标**，**不生成任意 SQL**；
  ② **答案必须附明细链接** —— 不给依据的 AI 数字不能用于决策；
  ③ 本仓**没有真实 AI 供应商适配器** ⇒ 按 `AiTextGenerationPort` 的既有范式「端口立起来、实现如实答未接入」，
  **绝不放假 vendor**；确定性计算部分做扎实。
- **状态**：**在途，零产出**。四次派发全部在探索阶段被基础设施错误打断：

  | 次序 | 错误 | 崩溃前已确认的结论 |
  | ---- | ---- | ------------------ |
  | 1 | `net::ERR_HTTP2_PING_FAILED` | reporting 服务有只读报表端点；store-comparison 无店铺维度利润 |
  | 2 | `500 Internal server error` | profit-report SHOP 维度确实算毛利；trade 有补货端点 |
  | 3 | `500 Internal server error` | platform 端口范式、V380 迁移模板、api-proxy 白名单结构 |
  | 4 | —（见下方额度说明） | 已读完 BRIEF、探索完成，准备直接进实现 |

  第 4 次尝试改为「**短提示词 + 任务书落盘**」（完整任务书写进
  `.worktrees/18.4.1.1/.tmp/BRIEF.md`，提示词只负责指路），以降低单次请求体积；
  至此仍未开始写代码。

### 🔴 硬阻塞：Token Plan 额度耗尽

18.3.1.2 的重派在写完 Service 层后死于
「**已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量** (2056)」。
回看前三次 500 错误，**同一根因**（provider 侧的额度/计费错误在运行时表现为 500 与 HTTP2 ping 失败）。

⇒ **本轮无法再派发任何子任务**，两条车道只能停在原地，等待下一轮 tick 的孤儿回收路径
（心跳超 90 分钟 ⇒ `plan` 自动回收重派）。这属于**用户侧的额度决定**，调度器不得自行绕过。

**给用户的提示**：18.3.1.2 的半成品（三个文件，1596 行）已保留在 worktree，
额度恢复后下一轮可直接续做，不必从零开始。

---

## 文件清单（本轮推送范围）

- **合并提交（13 个）**：`merge(18.1.2.2)` … `merge(14.3.1.3)`、`merge(fix-warehouse-consignment-clock-domain)`、
  `merge(fix-18.3.1.1-format)`
- **新迁移（15 个）**：platform `V413`–`V420`（8 个）+ identity `V413`–`V420` 权限码（7 个）
  （channel 的 `V402__product_dimension_ad_report.sql` **已随 main 同步到达 origin**，不在本批 diff 内）
- **后端测试类（33 个）**：`DeliveryRecord*`、`ApiCallMonitor*`、`ImportExport*`、`PaymentFunds*`、
  `PaymentProviderAdapterTest`、`StubPaymentProviderPort`、`DataMigration*`、`ExternalIntegration*`、
  `AutomationRule*`、`AutoApproval*`、`AdProductReport*`、`ConsignmentIntegrationTest` 等
- **前端页面（8 个）**：`/integrations/deliveries`、`/integrations/call-monitor`、`/integrations/io-tasks`、
  `/integrations/connectors/payment-funds`、`/integrations/data-migrations`、
  `/integrations/connectors/external-integrations`、`/integrations/auto-approvals`、`/automations/rules`
- **共享文件**：`apps/web/src/lib/api-proxy.ts`（白名单累积，多车道共改 ⇒ 增量格式闸 + allowlist 双向回归）
- **本轮说明**：`docs/sessions/2026-11/2026-11-01-batch-122-l2-verification-and-push.md`

## 后续待办

1. **🔴 优先：恢复 Token Plan 额度**。本轮两条车道均因额度耗尽被打断，无法派发子任务。
   额度恢复后，下一轮 tick 的孤儿回收路径（心跳超 90 分钟 ⇒ `plan` 自动重派）会接管两条车道；
   18.3.1.2 的 1596 行半成品留在 worktree 里可**直接续做**，不必从零开始。
2. 下一轮 tick 回收 18.3.1.2 与 18.4.1.1 ⇒ 并入集成头 ⇒ 跑 L2 ⇒ 推送。
3. 未开发模块还剩 **20** 个（D7 阶段 17 个、D6 阶段 3 个）：5.1.1.1 / 5.1.1.2 / 5.1.2.1 /
   18.3.2.1 / 18.3.2.2 / 18.4.1.2 / 18.4.2.2 / 18.4.2.3 / 18.4.3.1 / 18.4.3.2 / 18.5.1.1 / 18.5.2.1 /
   15.3.1.1 / 15.3.1.2 / 15.3.1.3 / 15.6.1.1 / 15.6.1.2 / 15.6.2.1 / 18.3.1.2 / 18.4.1.1（本轮派发中）。
4. **降档模式持续生效**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过）⇒ 每批 2 条车道、节流闸 100 分钟。
   期限**不改变**停机条件 —— 停机只看「全部 465 个模块已交付」。
5. **工具侧观察项（两条，都要改）**：
   - 本轮后端全量验证跑了 60+ 分钟仍未结束，**超过了后台任务 3600 秒的运行上限**，
     第一次 L2 因此被中途杀掉（不是测试失败）。后续轮次跑 `mvnw verify` 建议**用 nohup 脱离运行 + 日志落盘**，
     否则长批次会被工具超时截断，结论只能是「未知」而不是「绿/红」。
   - **L2 长跑必须用 `caffeinate -dimsu` 兜住**：本轮 macOS 在测试期间进入
     `Maintenance Sleep`（07:30:06 睡 / 07:42:02 醒），JVM 被冻结 5 分 28 秒，
     直接导致 3 处租户隔离断言读出假失败。**每轮 L2 都要加 caffeinate**，
     否则会随机红在几个类上，浪费大量二分时间。
6. **协议与工具的偏差（非阻塞）**：`PIPELINE.md` 记的「后端 2829 例 / 前端 2163 例 / 151 个测试类」已过期 ——
   实测**后端 770 个测试类、前端 707 个测试文件 9962 例**，单轮后端全量 3 小时以上。
   吞吐与发热估算若引用旧数字会失真。
