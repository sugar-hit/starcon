# 2026-10-23 batch-78 — 退货处置落地 + finance 服务引导与应收单管理

> 本批由集成头 `integration/module-dev` 的第 78 批车道并入，两条车道：**11.2.3.2 转可售/残次/报废**、
> **12.1.1.1 应收单管理**（含 `backend/services/finance/` 服务引导）。
> 并入 commit：`c13044af`（11.2.3.2）→ `8a5b91be`（12.1.1.1）。
>
> ⚠️ **本批 L2 批量验证是红的，本轮未 push。** 详见文末「L2 结果与二分定位」。
> 说明与台账已 commit 到集成头本地，修复落地后随下一批一起 push。

## 本批概览

| 模块 | 名称 | 阶段 | 主责服务 | 迁移版本 |
| --- | --- | --- | --- | --- |
| 11.2.3.2 | 转可售/残次/报废 | D4 | trade + warehouse | `V337`（双服务各一份） |
| 12.1.1.1 | 应收单管理 | D6 | finance | `V338` |

**本批的额外价值**：12.1.1.1 车道同时交付了 **`backend/services/finance/` 服务引导**。
卷 12「财务核算」32 个 L4 模块此前全部卡在「`backend/services/finance/` 不存在」这一堵墙上 ——
编排闸按蓝图 README 的「后端归属」列检查服务是否存在，服务不在，一格都排不出来。
本批之后卷 12 从 0/32 变成 1/32，且后续 31 格具备可落地的前提。

---

## 模块 11.2.3.2 转可售/残次/报废

**这个功能做什么**：售后质检出结论后，把这批退货落到确定的库存状态上 ——
可再销售就转成可售库存、残次就进残次库存、报废就核销并计入损耗。
报废这条路径被刻意做成**不可逆**的：客服不能随手报废，必须走独立权限的审批、留下必填意见、
提交人和审批人不能是同一个人，批完之后整单冻结且全链路没有反执行端点。

**技术落地**：

- **迁移**：双服务两份 `V337`（trade 与 warehouse 是两个独立库，版本号可同）
  - `backend/services/trade/src/main/resources/db/migration/V337__trade_return_inventory_disposition.sql`
    — 三张新表 `return_inventory_disposition` / `return_inventory_disposition_event` /
    `return_inventory_disposition_loss`，**零 ALTER 零 DROP 既有表**
  - `backend/services/warehouse/src/main/resources/db/migration/V337__warehouse_return_disposition_execution.sql`
    — 一张新表 `inventory_return_disposition_execution`，**零 ALTER 零 DROP 既有表**
  - **RLS**：两个库各走一遍「tenant_id NOT NULL + ENABLE/FORCE RLS + 租户谓词」；
    trade 侧对 11.2.3.1 的指令表只做**纯增量**改动（放宽 `instruction_status` CHECK 为
    `IN ('PENDING','CONSUMED')`、**仅授 column 级 `GRANT UPDATE(instruction_status)`**，
    其余列与表级 UPDATE/DELETE 一律不动）
- **端点**：
  - `trade` `/trade/return-dispositions` **12 个** — `GET /capabilities`、`GET /instructions`、
    `GET /options`、集合读、`GET /{id}`、`POST`（建单）、`PUT /{id}`、`POST /{id}/submit`、
    `POST /{id}/approve`、`POST /{id}/reject`、`POST /{id}/cancel`、`POST /{id}/execute`。
    权限码 `trade.aftersales.read` / `.write` / `.approve`（读写与审批分离）
  - `warehouse` `/warehouse/inventory/return-dispositions` **5 个** — `GET /capabilities`、
    `GET /options`、`POST /execute`、`GET /executions`、`GET /executions/{id}`。
    权限码 `warehouse.inventory.read` / `.update`
- **页面**：`/aftersales/return-dispositions`
  （`apps/web/src/components/aftersales/return-disposition-model.ts` +
  `return-disposition-workspace.tsx` + `apps/web/src/app/aftersales/return-disposition-model.ts` 路由页）
  — 统计卡 / 待处置指令队列 / 起草面板 / 处置单列表 / 详情（留痕 + 损耗 + 动作区）/ 能力边界；
  **加载态 / 空态 / 错误态三态齐全**，无占位数据
- **测试**（真实 PostgreSQL 16 + Testcontainers，**上游夹具走 11.2.3.1 真实链路**：
  订单 → 售后单 → 退货授权 → 实收入库 → 质检单 → 判定 → DB 触发器生成指令）
  - `ReturnDispositionIntegrationTest` **13 例**
  - `ReturnDispositionTenancyIntegrationTest` **3 例**
  - `ReturnDispositionExecutionIntegrationTest` **9 例**
  - `ReturnDispositionExecutionTenancyIntegrationTest` **3 例**
  - 前端：`return-disposition-model.test.ts` **14 例** +
    `apps/web/src/app/api/trade/return-disposition-allowlist.test.ts` **10 例**
  - **多租户三断言全绿**：两租户互不可见 / 伪造 `X-Tenant-Id` 无效 / 连接池压到 1 后 A→B→A 三轮不串

**关键设计决策**：

1. **双服务切分（核心）**。trade = 处置决定 + 审批 + 报废损耗记录 + 消费质检指令；
   warehouse = 库存状态变更的**执行**（只写执行回执，**不建第二张流水表** ——
   分录本体仍是 warehouse 自己的 `inventory_entry`）。
   **理由**：库存事实的写入所有权在 warehouse（AGENTS.md「每张事实表只有一个写入所有者」+
   「禁止跨服务 SQL / FK / 共享视图」），trade 不能直连 warehouse 表改库存；
   而 11.2.3.1 的 `return_quality_disposition_instruction` 在 trade 库、消费状态必须回写，
   所以「消费指令」留在 trade。**一条处置跨两个库，走授权 HTTP**（trade→warehouse 写端口 +
   候选库位读端口，调用方 bearer 转发），**两边各一道 RLS**。
   **被否决的三个切法**：① 全落 trade 直连 warehouse 表（跨库写，违模块边界纪律）；
   ② 全落 warehouse（则 `instruction_status` 无处回写，11.2.3.1 页面永远显示「待消费」，
   指令链断在半路）；③ 复用 7.4.1.1 通用调整单（调整单是通用库存动作，
   报废是带审批的**不可逆**业务决定，复用会把报废语义稀释成一行备注）。
2. **报废不新建「报废 bucket」**。`inventory_quality_status.role` 只有
   SELLABLE / PENDING_INSPECTION / DEFECTIVE 三个值 —— 报废 = 从待检 bucket 核销减库存。
   新建第四个 role 要改别的模块的公共词表，还会让「可售量」口径变浑。
3. **六道守卫钉在 DB 层**（服务层与 DB 各判一次，只判一处的话绕过服务层直连 SQL 就能造假）：
   处置必须挂在真的、还是 PENDING 的指令上；状态机只认合法流转（DB 触发器枚举穷举）；
   终态整行冻结；只有草稿能改业务要素；审批职责分离 + 审批意见必填；
   损耗口径由 DB 重算比对（`proposed_loss_amount = original_value − salvage_amount`）。
4. **损耗的财务侧字段恒为 null 并标注归属模块**（`finance_owner_module='12 财务'` +
   `loss_status='PENDING_FINANCE'`）。**绝不填 0** —— 填 0 会被读成「已核销且无损耗」。
   （本批 12.1.1.1 交付 finance 服务后，该缺口的后半段已在同批被接上。）
5. **执行失败不抛 5xx**：落 `EXECUTION_FAILED` 后返回 200。抛错会让事务回滚把失败记录一起回滚掉，
   库里连失败的痕迹都不留。前端按 `disposition.status` 展示，失败单可在仓库侧重试。
6. **契约类型改名避免撞名**：channel 服务已有 `ReturnDisposition*` 命名空间 ⇒
   trade 契约类型统一改名 `ReturnInventoryDisposition*`，warehouse 改名 `ReturnDispositionExecution*`。
7. **api-proxy 新增独立 `services[]` 元素**（首 token `return-dispositions`，锚在 `return-quality` 之后），
   **不并入既有超长 alternation**（多车道并入 git 必冲突）；
   刻意不放行 `DELETE` 与 `/resell` `/defective` `/scrap` `/salvage-posting` `/claim` 子路径。

**已知缺口（如实记录）**：① 7.4.1.1 调整单管理仍未开发 —— 本模块自建专用写端点而非复用；
② 5.4 调价不在本模块范围（转可售只改库存质量态、不动价）。

---

## 模块 12.1.1.1 应收单管理

**这个功能做什么**：把「谁欠我们钱、欠多少、什么时候到期」变成一套能查能算能追的东西。
运营可以手工建应收单，也可以从 B2B 发货确认一键导入；能按来源 / 状态 / 币种 / 应收对象 / 店铺筛选，
能看到五档账龄分布、逾期待收清单、争议金额单列；能生成并关闭催收任务、标注争议、作废整单。
每一笔应收的产生都必须先有**已启用的应收确认政策** —— 系统不替用户定会计政策。

**技术落地**：

- **迁移**：`backend/services/finance/src/main/resources/db/migration/V338__finance_receivable.sql`
  — **五张新表、零 ALTER 零 DROP 既有表**：
  `finance_receivable_policy`（按来源一行的应收确认政策） / `finance_receivable`（单头） /
  `finance_receivable_line`（明细行） / `finance_collection_task`（催收任务） /
  `finance_receivable_event`（追加式留痕，运行角色 UPDATE/DELETE 已收回）
  - **RLS**：五表 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation，
    谓词 `tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid`，
    GRANT `finance_app`（LOGIN、无 BYPASSRLS）
  - **金额**：数量 `NUMERIC(19,6)`、金额 `NUMERIC(19,4)` + `BigDecimal`，**禁浮点**。
    明细金额是生成列 `round(quantity*unit_price,4)`；单头 amount 由触发器从明细求和
- **端点**：**15 个**挂 `/finance/receivables`，
  权限 `finance.receivable.read` / `.create` / `.update`：
  `GET /capabilities`、`GET /options`、集合读、建单、`GET /{id}`、`POST /import-b2b`、
  `POST /{id}/void`、`POST /{id}/dispute`、`GET /aging`、`GET /collection-tasks`、
  `POST /{id}/collection-tasks`、`POST /collection-tasks/generate`、
  `POST /collection-tasks/{taskId}/close`、`GET /policies`、`PUT /policies/{source}`
- **页面**：`/finance`（`apps/web/src/components/finance/receivable-model.ts` +
  `receivable-workspace.tsx` + 路由页替换占位，**真实工作区**）
  — 筛选维度 = 来源 / 状态 / 币种 / 应收对象 / 店铺 / 关键字；
  可执行操作 = 建单 / 从发货确认导入 / 作废 / 标注与取消争议 / 生成与关闭催收任务 /
  配置来源确认政策；**加载态 / 空态 / 错误态三态齐全**，无占位数据
- **测试**：**37 例**实跑 Testcontainers PostgreSQL 16（连接池压到 1，迁移实跑到 V338）
  - `ReceivableIntegrationTest` **31 例**
  - `FinanceTenancyIntegrationTest` **5 例**
  - `OpenApiContractTest` **1 例**
  - 前端：`receivable-model.test.ts` **13 例** +
    `apps/web/src/app/api/finance/receivables-allowlist.test.ts` **11 例**
  - **多租户三断言实跑本模块五张表**：两租户互不可见（列表 / 详情 / 按 id 直达 / 写入口）；
    伪造租户上下文无效（无可信上下文时写入被 RLS 拒）**且**伪造 `X-Tenant-Id` 头无效
    （经 HTTP 真实写入口双向各验一次，行仍落在会话租户名下）；
    连接池压到 1 交替借还 3 轮不串租户

**关键设计决策**：

1. **服务引导与业务模块拆成两个 atomic commit**。卷 12 的 32 格全部卡在「服务不存在」，
   所以先 `feat(finance): 服务引导`（骨架 + 共享接线点 + V338 迁移 + 空契约），
   再 `feat(12.1.1.1): 应收单管理`。表结构先落 ——「服务连一个库都没有」时编排闸放行了也没地方落数据。
   **引导车道逐字照抄 crm 的安全边界**（`AccessService` 只换 `finance.` 前缀、`ApiErrorHandler` 保持
   错误响应形状、`TenantTransactions` 声明为 Bean，租户由 identity 按**已认证成员关系**解析），
   **刻意没抄** crm 的 60+ 张业务表、`*Port.java` 一族空壳、`*RulesTest` 抽象层、
   `Unavailable*Port` 空实现、迁移版本起点（finance 从 V338 起，跟着全局唯一分配器走，撞号会让对方 Flyway 直接炸）。
2. **六条 DB 守卫钉在 DB 层**（绕过服务层直连 SQL 也造不出假数据）：
   新增单据强制从 DRAFT 起；单头金额由触发器从明细求和；明细金额是生成列；
   确认时校验「至少一条明细行」；确认后单头与明细业务列冻结（只能作废红冲、不能改行）；
   明细数量与单价必须为正。
3. **汇率快照留在单据上**：`exchange_rate` / `base_currency` 是普通列，
   `base_amount` 是生成列 `round(amount*exchange_rate,4)`，
   **无汇率时 base_amount 为 null 而不是 0** —— 按 0 折算会被读成「这笔折成 0」。
   确认后快照列冻结。
4. **金额与状态全部派生**：单头 `settled_amount` / `status` 由明细 `settled_amount` 派生，
   行 `settlement_status` 是生成列 —— 库里没有可手改的「已核销」标记。
   **本模块不开放核销写端点**（核销属 12.2.1.2）。
5. **跨服务读取发货确认（9.7.1.1 → 12.1.1.1）的形状 = Port + 租户可维护的来源枚举列，两者都要**：
   `TradeReceivablePort` 经 trade 的**授权 HTTP API** 读 `GET /trade/b2b-orders/receivables`
   （**不跨库 SQL / FK / 视图**）；落库带 `source='B2B_CREDIT_TERM'` / `source_system='trade'` /
   `source_reference=上游单号` 三列溯源，幂等键 `UNIQUE(tenant_id, source, source_reference)`。
   **理由**：只留可维护列 ⇒ 金额靠人抄；只做实时读 ⇒ 上游不可用时历史应收消失。
   上游对不上时**跳过并如实报出来**，上游读不到时**整体失败而不是报零条**（HTTP 503）。
6. **应收确认政策是闸门**：每个来源必须有**已启用**的 `finance_receivable_policy` 行，
   否则该来源一张应收都产生不了（服务层 400，消息含「应收确认政策」）。
   **政策表不预置任何行** —— 预置 enabled=true 等于替用户把会计政策定了。
7. **12.3.1 结算未交付 ⇒ 平台结算 / 平台代扣来源当前不可产生**：DB 词表保留这两个来源枚举，
   但服务层**拒绝建单**并写明归属模块 `12.3.1.1`，对应字段恒为 null（**绝不填 0、绝不造结算**）；
   能力声明 `available=false`。**人工录入路径可用**；**B2B 账期来源只允许从发货确认导入**。
   **未发货订单不默认等于收入**：只有经发货确认导入或人工建单才产生应收。
8. **api-proxy 新增独立 `services[]` 元素**（prefix `finance/`、env `FINANCE_URL`、
   fallback `http://127.0.0.1:8087`），**不并进既有 trade 长正则**（多车道并入 git 必冲突）；
   插入锚点固定在 services[] **最末尾**（`default-context` 之后），**既有条目一字未改**（diff = +49/-0）。
   id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`
   （不是 `[0-9a-f-]+`，后者会把 `capabilities` 也当 id 放行），字面量子路径排在 {uuid} 段之前判别。
   刻意不放行：`{id}/settle`（核销 12.2.1.2 未交付）、`{id}/write-off`（坏账 12.1.1.3 未交付）、
   `{id}/invoice`（发票 12.6.2.1 未交付）、`payments`（12.2.x 未交付）、`settlements`（12.3.1 未交付）、非 UUID 段。
9. **契约**：codegen 重生成（**未手改**）⇒ `packages/api-contracts/src/contracts/finance.ts`
   新增 **26 个 `Finance*` 类型**，既有六服务契约**零变化**（按类型名集合比对 +0/-0）。

**已知缺口（如实记录）**：① 12.3.1 结算未交付 ⇒ 平台结算 / 平台代扣两个来源不可产生；
② 核销（12.2.1.2）、坏账核销（12.1.1.3）、发票（12.6.2.1）未交付，对应端点刻意不放行；
③ 争议标注与 11.4.1.1 纠纷列表**未自动联动**（如实写进能力声明）。

---

## 静态预检

对本批三份新迁移跑「已知互相破坏形状」扫描：

| 检查 | 本批结果 |
| --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | **未命中** |
| `SET NOT NULL` | **未命中** |
| `ADD CONSTRAINT ... CHECK` | **未命中** |

⇒ 本批三份迁移全部是「纯新建表、零 ALTER 零 DROP 既有表」，
不存在「无 DEFAULT 加列 / 改必填 / 收紧旧值域」这类跨车道互相破坏形状。
**⇒ 本批的红不是静态形状造成的 —— 真实原因见下。**

---

## L2 结果与二分定位

### L2 全量验证（集成头上跑一次）

| 层 | 结果 |
| --- | --- |
| `backend/./mvnw verify` | ❌ **红** —— `Tests run: 2458, Failures: 1, Errors: 0`（trade） |
| `pnpm typecheck` | ✅ 3/3 successful |
| `pnpm lint` | ✅ 3/3 successful |
| `pnpm test` | ✅ **550 文件 / 7821 例全绿** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ 成功 |
| `node tools/module-progress/sync.mjs --check` | ✅ 台账与蓝图一致 |

**后端各服务**（reactor 在 trade 处 FAILURE，crm 与 finance 被 **SKIPPED**，调度者补跑）：

| 服务 | 结果 |
| --- | --- |
| identity / catalog / channel / warehouse | ✅ SUCCESS |
| trade | ❌ **FAILURE** —— 2458 例里 1 个失败 |
| crm（reactor SKIPPED，调度者补跑 `-pl services/crm -am test`） | ✅ 491 例全绿 |
| finance（reactor SKIPPED，调度者补跑 `-pl services/finance -am test`） | ✅ 37 例全绿 |

### 唯一的失败

```
com.starcon.trade.ReturnQualityIntegrationTest.质量状态指令由数据库生成且恒为待消费
  Tests run: 19, Failures: 1, Errors: 0
  java.lang.AssertionError:
  Expecting actual:
    "org.springframework.dao.DataIntegrityViolationException: jOOQ; SQL [update return_quality_disposition_instruction
     set instruction_status='CONSUMED' where tenant_id=?::uuid and id=?::uuid];
     ERROR: 质量状态指令只能在处置单真正执行（库存已改）之后才标为已消费：instruction_id=...
  to contain:
    "permission denied"
```

### 二分定位（**2 次定向测试运行，不是 2 次全量验证**）

调度者用**单个测试类**（`ReturnQualityIntegrationTest`）在独立 worktree 上做的二分：

| 二分点 | 结果 |
| --- | --- |
| `dbe136cf`（本批并入**之前**的集成头） | **BUILD SUCCESS** —— 19 例全绿 |
| `c13044af`（**11.2.3.2 的 merge commit**） | **BUILD FAILURE** —— 同一个用例红 |

⇒ **破坏由 11.2.3.2 的迁移 `V337__trade_return_inventory_disposition.sql` 引入。**

### 根因（不是「改坏了业务」，是「上游测试钉了一个已经过时的契约」）

11.2.3.1 交付时，`return_quality_disposition_instruction` 表的运行角色 `trade_app`
**没有任何 UPDATE/DELETE 权限**，指令只由 DB 触发器生成、恒为 `PENDING`。
11.2.3.1 的测试就钉住了这个契约，断言运行角色翻 `instruction_status` 必须报 `permission denied`。

11.2.3.2 是这条指令的**指定消费方**，所以 V337（第 590–646 行）做了三件事：

1. 放宽 V179 的 `CHECK(instruction_status = 'PENDING')` → `CHECK(instruction_status IN ('PENDING','CONSUMED'))`
2. `GRANT UPDATE (instruction_status) ON return_quality_disposition_instruction TO trade_app;`
   （**只授这一列**，表级 UPDATE/DELETE 仍被 V179 收回）
3. 加消费守卫触发器 `guard_return_quality_disposition_instruction_consumed()`：
   只允许 `PENDING → CONSUMED`，且**必须已存在一张 `status = 'EXECUTED'` 的 `return_inventory_disposition`**，
   否则 `RAISE EXCEPTION '质量状态指令只能在处置单真正执行（库存已改）之后才标为已消费：…'`

⇒ **业务不变式其实更强了**（从「根本改不动」变成「没有已执行处置单就改不动、且改不回」），
**但拒绝信号从「permission denied」变成了守卫触发器的 `check_violation`** ⇒ 11.2.3.1 的断言失效。

⚠️ **为什么静态预检没抓到它**：三张 grep 形状（无 DEFAULT 加列 / SET NOT NULL / CHECK 约束）抓的是
「ALTER 既有表」这一类破坏。V337 对 11.2.3.1 指令表的改动**不是 ALTER**，
而是**放宽 CHECK + 授 column 级 GRANT + 加触发器** —— 这是**权限模型**的变更，
落在静态预检的盲区里。**教训记在这里**：
> **跨车道的破坏形状不止「表结构」，还有「权限模型」**。
> 下一个车道若要给别的模块的表授任何 GRANT、或放宽任何 CHECK，
> 都要顺手去 grep 那个模块的测试里有没有把旧权限钉成断言。

### 已派发的修复车道

- 车道 `11.2.3.2`（分支 `fix/11-2-3-2-instruction-contract`，
  worktree `.worktrees/11.2.3.2`），**只改测试、不改迁移、不改生产代码**：
  把 11.2.3.1 的断言从「`permission denied`」改成**新契约**，
  并**比原来更强**地证明同一个不变式：
  1. 无已执行处置单时翻 `CONSUMED` 仍被拒（错误来自消费守卫）；
  2. **最小权限仍成立** —— 运行角色改不了指令表**其他列**、**仍然没有 DELETE**；
  3. 正对照保留（同一角色仍能改 `return_quality_category`）；
  4. **正向路径必须被证明** —— 存在 `status='EXECUTED'` 的处置单指向该指令时，翻 `CONSUMED` **必须成功**；
  5. 该用例原有的其它断言一条不许删。
- ⚠️ **刻意不改 V337**：V337 已进过集成头并被应用过，改它的内容会让 Flyway checksum 校验炸掉。

## 本轮派发（后台运行，下一轮 tick 回收）

| 模块 | 名称 | 迁移版本 | 分支 |
| --- | --- | --- | --- |
| 11.2.3.2 | 修复车道（指令契约对齐） | V337（不改迁移） | `fix/11-2-3-2-instruction-contract` |
| 12.1.1.2 | 应收账龄 | `V339` | `feat/12-1-1-2` |
| 12.1.2.1 | 应付单管理 | `V340` | `feat/12-1-2-1` |
| 12.2.1.1 | 收款单 | `V341` | `feat/12-2-1-1` |

⚠️ **本轮 `--lanes` 用的是 3 而不是配置里的 4** —— 因为本批 L2 红了，必须占用一个并发槽位给修复车道，
**总并发仍是配置的 4**。下一轮起恢复 `--lanes 4`。
⚠️ 卷 13（主责服务 `reporting`）与卷 18（主责服务 `platform`）仍被**微服务闸**挡住 ——
这两个服务不在集成头里，排出来也注定 BLOCK。

## 后续待办

1. 修复车道落地后，重跑 L2；绿了才 push 本批（说明与台账已 commit，随下一批一起推）。
2. 卷 13 / 卷 18 要么补 `reporting` / `platform` 服务引导，要么在蓝图 README 里改主责服务归属。
3. 11.2.3.1 的台账「证据」格可补一句「指令消费权限模型已由 11.2.3.2 演进」，避免后来人误读。
