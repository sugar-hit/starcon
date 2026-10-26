# 2026-10-23 batch-79 — 应收账龄 / 应付单管理 / 收款单 三格落地 + 质检指令契约修复

> 本批由集成头 `integration/module-dev` 回收上一轮派发的 **4 条车道**并入，
> 一条修复车道 + 三条 finance 业务车道。并入 commit：
> `ee992cb2`（11.2.3.2 修复）→ `d75bbc04`（12.1.1.2）→ `653a8e48`（12.1.2.1）→ `6a6c94f8`（12.2.1.1）。
>
> L2 批量验证：**第 1 次前端红**（后端全绿）→ 归因到 12.1.2.1 → 修复车道 → **第 2 次 L2 全绿**
> ⇒ 说明 + 台账已 commit，**已 push** 到 `origin/integration/module-dev`。
> 第 1 次的失败、归因过程与修复车道完整记在「L2 批量验证」一节 —— **红的记录不删**。

## 本批概览

| 模块 | 名称 | 阶段 | 主责服务 | 迁移版本 |
| --- | --- | --- | --- | --- |
| 11.2.3.2 | 转可售/残次/报废（**契约修复车道**） | D4 | trade | V337（**不改迁移**） |
| 12.1.1.2 | 应收账龄 | D6 | finance | `V339` |
| 12.1.2.1 | 应付单管理 | D6 | finance | `V340` |
| 12.2.1.1 | 收款单 | D6 | finance | `V341` |

**本批的意义**：卷 12「财务核算」的 32 格从 **1/32 变成 4/32**。
batch-78 只交付了 12.1.1.1 一格（它是 finance 服务的引导车道），
本批一次性把「应收（账龄）— 应付 — 收款」三条主干补齐，
并且**解开了前两批留下的三个显式缺口**：12.1.1.2 的核销写路径（属 12.2.1.2）、
12.2.1.1 的核销入口（属 12.2.1.2）、12.1.1.1 的平台结算来源（属 12.3.1）——
这三格的**归属模块在本批里已经排上车道**（见「本轮派发」）。

---

## 模块 11.2.3.2 转可售/残次/报废（修复车道 · 纯测试）

**这个功能做什么**：本车道**不改任何功能**，只把 11.2.3.1 的一个测试断言对齐到 11.2.3.2
交付后的新权限契约 —— 并把同一个不变式**证得更强**。

**技术落地**：

- **迁移**：**无**。刻意不改 V337 —— 它已进过集成头并被应用过，改内容会让 Flyway checksum 校验炸掉。
- **端点**：**无**。只改
  `backend/services/trade/src/test/java/com/starcon/trade/ReturnQualityIntegrationTest.java`（一个文件）
- **页面**：无
- **测试**：`ReturnQualityIntegrationTest` **19 例**（本车道重排其中一个用例为五条分组断言，
  并新增 `seedExecutedDisposition(...)` SQL 夹具）

**关键设计决策**：

1. **红不是回归缺陷，是 11.2.3.1 的断言钉住了一个已经过时的权限模型**。
   V179 交付时把指令表的表级 UPDATE/DELETE **整个收回**，注释原文写明
   「消费侧：11.2.3.2 未交付 ⇒ 恒为 PENDING」。11.2.3.2 交付后 V337 做了**纯增量**改动：
   放宽 CHECK（`PENDING` → `IN ('PENDING','CONSUMED')`）+ 只授**一列** UPDATE 权限
   + 挂消费守卫触发器。⇒ **不变式其实更强了**（从「根本改不动」变成
   「没有已执行处置单就改不动、且改不回」），**但拒绝信号从 `permission denied`
   变成了守卫触发器的 `check_violation`** ⇒ 旧断言失效。
2. **新断言证明的是「改得动，但只在处置单真正执行之后才改得动」**：
   ① 无已执行处置单 ⇒ 被**消费守卫**拒；② 最小权限仍成立 ⇒ 改 `consumer_module`
   （**同值** `= '11.2.3.2'`，同值改动在有权限时本该成功 ⇒ 被拒就只可能是权限）
   与 `DELETE` 仍被 `permission denied` 拒；③ 正对照保留 ⇒ 同一运行角色仍能改
   `return_quality_category`；④ **正向路径必须被证明** ⇒ 造一张 `status='EXECUTED'`
   的处置单后翻 `CONSUMED` **必须成功**（证明 V337 授的那一列可用，不是被守卫全堵死）；
   ⑤ 消费**不可逆**（标错了改不回 `PENDING`）。
3. **正向路径走 SQL 夹具而非 HTTP**：该测试类**刻意不开额外监听端口**
   （类注释写明 JDK HttpServer 靠端口 0 现分配，全量并发下偶发端口被抢占 → 503，本仓已知 flake）。
   夹具直连 SQL 走**草稿 → 待审 → 已批 → 已执行**四步，**每一步都过 V337 的守卫触发器**
   —— 这是**测试夹具，不是生产逻辑**。
4. **顺手把 V337 的三张新表加进本类 `clean()` 的 truncate 清单**：不加的话处置单夹具会跨用例残留。
5. **刻意不修**文件里另一处 U+FFFD 乱码（与本车道无关，保持 diff 严格收敛）。

---

## 模块 12.1.1.2 应收账龄

**这个功能做什么**：把「欠了多久、逾期集中在谁身上」变成能查能盯的东西。
运营能看到五档账龄分布、按**店铺 / 客户 / 币种**三个维度下钻、逐单列出的逾期清单，
并能把**争议中的金额单独拎出来看** —— 跨境应收最大的风险是平台争议冻结，
它混在余额里看不见就等于没管。

**技术落地**：

- **迁移**：`backend/services/finance/src/main/resources/db/migration/V339__finance_receivable_aging.sql`
  — **一张新表、零 ALTER 零 DROP 既有表**：`finance_aging_bucket`（账龄区间配置，租户自己维护）
  - **RLS**：tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation，
    谓词 `tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid`，GRANT `finance_app`
  - **索引**：三个（其中两个是**部分唯一索引**，兜住「两个未到期档 / 两个无上界档」）
- **端点**：`/finance/receivable-aging` **6 个** — `GET /capabilities`、`GET /options`、
  `GET /distribution`（账龄分布报表）、`GET /overdue`（逾期清单，真分页 + 真筛选 + 真排序）、
  `GET /buckets`（区间配置读）、`PUT /buckets`（区间配置整体替换）
- **页面**：`/finance/aging`
  （`apps/web/src/components/finance/receivable-aging-model.ts` +
  `receivable-aging-workspace.tsx` + `apps/web/src/app/finance/aging/page.tsx` 路由页）
  — 筛选维度 = 店铺 / 客户 / 币种 / 档位 / 争议与否；操作 = 分布下钻 / 逾期清单逐单 /
  账龄区间配置整份提交；**加载态 / 空态 / 错误态三态齐全**，无占位数据
- **测试**（Testcontainers PostgreSQL 16，迁移实跑到 V339）：
  - `ReceivableAgingIntegrationTest` **20 例**
  - `ReceivableAgingTenancyIntegrationTest` **6 例**（租户三断言）
  - 前端：`receivable-aging-model.test.ts` **8 例** +
    `apps/web/src/app/api/finance/receivable-aging-allowlist.test.ts` **5 例**（白名单双向断言）

**关键设计决策**：

1. **命名空间不复用 `/finance/receivables`**：12.1.1.1 的端点/契约/页面是它的交付物，
   同服务的不同模块各走各的前缀。两条路由两两不相交，不会互相覆盖。
2. **账龄区间 = 配置表 + 蓝图五档回落**：写死 = 替用户定会计口径（违反卷 12 原则）；
   完全不提供 = 蓝图的「输入：账龄区间配置」是空的。
   ⇒ 未配置时回落蓝图给定的五档，响应里如实标 `source=BLUEPRINT_DEFAULT` / `TENANT_CONFIG`。
3. **配置写入是整体替换（`PUT /buckets`），不是逐档增删改**：档位必须**连续覆盖 1 天起**，
   这条跨行约束只有对着整份配置才判得出来。逐档写会出现「改到一半的中间态」，
   落在缝里的应收不属于任何一档 ⇒ 报表**凭空少钱**。整体替换 ⇒ 配置要么合法要么不落库。
4. **不新建「已核销」判断口径**：读 V338 的派生规则 —— 金额取 `amount - settled_amount`（未核销余额），
   核销金额与核销状态**只有明细行一个出处**。DRAFT / VOID / SETTLED 都不进报表。
   **核销写路径属 12.2.1.2（本批已排上车道）。**
5. **「90+」档从第 91 天起算**：蓝图写「61-90 / 90+」在 90 天上重叠，
   12.1.1.1 的 `/receivables/aging` 已是 `<= 90 → 61-90 档`，
   本模块**沿用同一条边界** —— 否则同一租户在两个模块会看到两套账龄。
6. **争议金额每行、每个币种合计里单列**（`dispute_amount` / `dispute_overdue_amount`），
   逾期清单逐单带 `dispute_flag`，`?disputeOnly=true` 只看争议。
   争议事实来自 11.4.1.1 纠纷，**当前只支持人工标注、未自动联动** —— 如实写进能力声明。
7. **汇总范围是整个筛选结果集，不是当前页**：分页一变汇总就变的话，
   「翻到第二页看到总逾期变小了」会被读成「数据丢了」。
8. **不登记进 `packages/ui/src/lib/task-pages.ts`**：该文件**不在** merge-union 驱动器覆盖范围内
   ⇒ 走普通 git merge，同批多条 finance 车道都改它必然冲突。
   仓库现状也是「有页面不一定登记」（`apps/web/src/app/warehouse/` 下 22 个页面只有 7 个登记）。
9. **没有动 `apps/web/src/app/finance/page.tsx`**（12.1.1.1 的页面）。

---

## 模块 12.1.2.1 应付单管理

**这个功能做什么**：把「我们欠供应商 / 物流 / 平台 / 广告多少钱、什么时候到期、还欠多少」
变成一套能建能追能核的东西。运营能从采购到货确认导入、从物流费用导入、从广告花费导入，
也能手工录入；能按来源 / 应付对象 / 状态 / 币种筛选，能看未付清单，
并用**预付款**去抵扣 —— 但抵扣**不产生「已付款」回执**（那是付款模块的职责）。

**技术落地**：

- **迁移**：`backend/services/finance/src/main/resources/db/migration/V340__finance_payable.sql`
  — **六张新表、零 ALTER 零 DROP 既有表**：
  `finance_payable_policy`（应付确认政策，按「来源 × 义务类型」一行，**不预置任何一行**）·
  `finance_payable` / `finance_payable_line` / `finance_payable_event` ·
  `finance_prepayment` / `finance_prepayment_allocation`
  - **RLS**：六表 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT `finance_app`
  - **金额**：数量 `NUMERIC(19,6)`、金额 `NUMERIC(19,4)` + `BigDecimal`，**禁浮点**
- **端点**：`/finance/payables` **12 个** + `/finance/prepayments` **5 个** = **17 个**
  - payables：`GET /capabilities`、`GET /options`、集合读、`GET /unpaid`（未付清单）、
    `GET /policies`、`PUT /policies/{source}`、`GET /{id}`、`POST`（建单）、
    `POST /import-purchase-arrivals`、`POST /import-first-mile`、`POST /import-ad-spend`、
    `POST /{id}/void`
  - prepayments：`GET`（集合）、`GET /{id}`、`POST`（登记预付）、
    `POST /{id}/allocations`（抵扣）、`POST /{id}/void`
- **页面**：`/finance/payables`
  （`apps/web/src/components/finance/payable-model.ts` + `payable-workspace.tsx` +
  `apps/web/src/app/finance/payables/page.tsx`）
  — 筛选维度 = 来源 / 义务类型 / 状态 / 币种 / 应付对象；操作 = 建单 / 三路导入 /
  作废 / 预付登记与抵扣 / 确认政策配置；**加载态 / 空态 / 错误态三态齐全**
- **测试**（Testcontainers PostgreSQL 16）：
  - `PayableIntegrationTest` **38 例**
  - `PayableTenancyIntegrationTest` **5 例**（租户三断言）
  - 前端：`payable-model.test.ts` **18 例** + `payables-allowlist.test.ts` **5 例**
  - **跨服务读取走授权 HTTP**：`PurchaseArrivalPort`（trade 6.3.2.2 到货跟踪）·
    `FirstMilePort`（物流）· `AdSpendPort`（广告）—— **不跨库 SQL / FK / 视图**
- **上游自检的一处硬结论**：`SUPPLIER_STATEMENT`（供应商对账）来源**当前不可产生**，
  归属模块写 **12.1.2.3** —— 理由是蓝图明写「**财务是最终确认方，采购不能自行确认应付**」，
  而那一格未交付。6.5.1.1 采购对账**已交付**（台账事实与任务书给的旧事实不一致，已如实记录）。

**关键设计决策**：

1. **应付、预付款、采购承诺分开建模 = 两张表 + 一个词表 + 一个不建模**：
   - 应付 → `finance_payable`（+ `_line` / `_event`）
   - 预付款 → `finance_prepayment`（+ `_allocation`）：**独立聚合**，有自己的 URL 前缀
     `/finance/prepayments`、自己的权限码 `finance.prepayment.*`、自己的状态机
   - 采购承诺 → **本服务不建模**。它的事实所有者是 6 采购（trade 的采购单）；
     另建一张 finance 表等于把同一份事实写两遍（AGENTS.md：每张事实表只有一个写入所有者）。
     词表三类分开，能力声明里写明 COMMITMENT 归属 6.3.1.1 且**不可产生**，前端**不给它开关**
2. **预付款刻意没有「已付金额」字段**：付款是 12.2.2.1 的职责，本模块只登记预付事实与抵扣，
   **不产生任何「已付款」回执**；抵扣也**不改**应付单的已付金额 —— 它只是预付款的账，
   给付款模块当输入用。
3. **不预置任何一行 `finance_payable_policy`**：预置 enabled=true 等于替用户把会计政策定了。
4. **`ADVERTISING_FEE`（广告费）来源**：蓝图来源词表五项、输入句却写了「服务费用（物流、平台、**广告**）」；
   卷 14 广告已交付 18 格且有花费事实（`ad_campaign_daily_metric.spend`）⇒ 直接落地蓝图输入句，
   不是自造词表。
5. **8.2.1.1 收货单不作为应付来源**：warehouse 的 `goods_receipt` 只有 SKU × 数量 × 批次 × 库位，
   **没有供应商、没有单价、没有币种** ⇒ 拿它推应付就是蓝图禁止的「仅凭收货统一推导」。
   真正的接缝在 trade 6.3.2.2 到货跟踪（有单价快照 × 到货数量算出的应付金额）。
6. **撤回跨模块页面改动**：`apps/web/src/app/finance/page.tsx` 的入口行**已撤回**（`revert` commit），
   新页面只靠 URL 直接可达。

---

## 模块 12.2.1.1 收款单

**这个功能做什么**：把「钱到账了、到的是哪几笔欠款、还剩多少没收」登记成可查可追的东西。
运营能手工登记收款，也能**导入到账流水**（CSV / JSON），
能一笔收款预留核销多笔应收（合并回款），能看**未核销余额**。

**技术落地**：

- **迁移**：`backend/services/finance/src/main/resources/db/migration/V341__finance_receipt.sql`
  — **三张新表、零 ALTER 零 DROP 既有表**：
  `finance_receipt`（单头）/ `finance_receipt_allocation`（**预留核销关系**，收款 ↔ 应收多对多）/
  `finance_receipt_event`（留痕追加式，运行角色 UPDATE/DELETE 已收回）
  - **RLS**：三表 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT `finance_app`
  - **4 条 DB 守卫**（绕过服务层直连 SQL 也造不出假数据）
- **端点**：`/finance/receipts` **10 个** — `GET /capabilities`、`GET /options`、`GET /{id}`、
  `POST /import`、`POST /{id}/allocations`、
  `POST /{id}/allocations/{allocationId}/release`、`POST /{id}/void`、`GET /balance`
- **页面**：`/finance/receipts`
  （`apps/web/src/components/finance/receipt-model.ts` + `receipt-workspace.tsx` +
  `apps/web/src/app/finance/receipts/page.tsx`）
  — 筛选维度 = 来源 / 币种 / 状态 / 预收与否；操作 = 手工登记 / CSV·JSON 导入 /
  预留核销与释放 / 作废 / 余额查询；**加载态 / 空态 / 错误态三态齐全**
- **测试**（Testcontainers PostgreSQL 16）：
  - `ReceiptIntegrationTest` **28 例**
  - `ReceiptTenancyIntegrationTest` **5 例**（连接池压到 1）
  - 前端：`receipt-model.test.ts` **14 例** + `receipts-allowlist.test.ts` **5 例**

**关键设计决策**：

1. **没有明细行**（与 12.1.1.1 的应收单不同）：一次到账流水**本身就是一笔资金事实**，
   金额是原子的、可直接记账的。为它编一条「明细行」只是为了凑结构，那是**假明细**。
   「一笔收款对应哪几笔应收」改由 `finance_receipt_allocation` 表达。
2. **未核销余额是生成列**（`unsettled_amount` = `amount − settled_amount`），没有第二个写入口。
   核销金额的唯一写入口是 `settled_amount`，属 **12.2.1.2（本批已排上车道）** ——
   本模块**不写它、也不开放写端点**。
3. **「预留」≠「核销」**：预留**不减少**未核销余额。预收（还没有对应应收的资金）由 `advance_flag` 单列，
   预收与预留**互斥**（DB 层守卫）。页面**不提供**任何「标记已核销」的入口。
4. **资金账户 = 受管引用恒为 NULL + 自由文本标识**：12.7.1.1 未交付 ⇒ 没有受管账户可引用
   （填 0 / 假 id 会被读成「进了某个已登记的账户」）。页面明确写它**不是**受管资金账户。
5. **结算单号恒为 NULL（DB CHECK 钉死）**：12.3.1.1 未交付 ⇒ 词表保留 `PLATFORM_SETTLEMENT`
   但服务层拒绝建单并写明归属模块；上游交付后 DROP 那条 CHECK 即可接上，不改结构。
6. **到账流水导入是真的**：CSV 按 RFC 4180 处理引号，JSON 都能解析 → 真实入库 + 幂等
   （幂等键 = 来源系统 + 来源流水号）。
   **文件级错误整体失败（一条不导入），行级错误只跳过那一行并逐条 warning** ——
   半个文件入库比完全失败危险；行级错误吞掉的话「导入 0 条」会被读成「文件是空的」。
7. **到账时间无偏移则拒收该行**：`Timestamp.valueOf(LocalDateTime)` 在东八区静默偏 −28800s
   ⇒ 到账时间错 8 小时就是错账。
8. **页面路由 `/finance/receipts` 与 12.1.1.1 的 `/finance` 并列**：两者各有各的单据号前缀与未核销口径。

---

## 静态预检

对本批三份新迁移（V339 / V340 / V341）跑「已知互相破坏形状」扫描：

| 检查 | 本批结果 |
| --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | **未命中** |
| `SET NOT NULL` | **未命中** |
| `ADD CONSTRAINT ... CHECK` | **未命中** |
| ALTER 既有表 | **未命中**（三份迁移全是纯 `CREATE TABLE`） |
| 跨模块 GRANT（权限模型面） | **未命中**（V340/V341 的 GRANT 全部只落在自己新建的表上，**未碰** V338 的五张表） |

⇒ 三份迁移全部是「纯新建表、零 ALTER 零 DROP 既有表、零跨模块授权」，
**不存在**「无 DEFAULT 加列 / 改必填 / 收紧旧值域 / 改别人表的权限模型」这类跨车道互相破坏形状。

> ⚠️ 上一批（batch-78）红在**静态预检的盲区**里：破坏形状是「权限模型」而非「表结构」，
> 静态预检的三种 grep 抓不到「授 GRANT / 放宽 CHECK / 加守卫触发器」。
> 本批据此把**「跨模块 GRANT」也纳入扫描口径** —— 三份迁移对 V338 的表**零 GRANT**。

---

## L2 批量验证（集成头上跑一次）

### 第 1 次 L2：**前端红**（后端全绿）

| 层 | 结果 |
| --- | --- |
| `backend/./mvnw verify` | ✅ **绿** —— 7/7 服务 BUILD SUCCESS，`Total time: 51:12 min` |
| `pnpm typecheck` | ✅ 3/3 successful |
| `pnpm lint` | ✅ 3/3 successful |
| `pnpm test` | ❌ **红** —— `Test Files 1 failed | 555 passed (556)`、`Tests 2 failed | 7874 passed (7876)` |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ 成功 |
| `node tools/module-progress/sync.mjs --check` | ✅ 台账与蓝图一致 |

后端 reactor 全绿：backend / tenancy / identity(2:15) / catalog(5:26) / channel(13:38) /
warehouse(12:03) / trade(16:11) / crm(1:17) / finance(19.6s)，**606 个测试类、0 failure、0 error**。

### 失败的 2 条断言（同一个根因）

```
FAIL packages/ui/src/lib/nav-routes.test.ts > 每个任务页的 implemented 标记都符合真实路由
AssertionError: expected [ Array(1) ] to deeply equal []
+ "/finance/payables 标记=false 实际=true"

FAIL packages/ui/src/lib/nav-routes.test.ts > 有真实页面的任务页不能被标成未实现（防止漏做入口）
AssertionError: expected [ '/finance/payables' ] to deeply equal []
```

被打破的不变式只有一条：

> `packages/ui/src/lib/task-pages.ts` 里每个条目：**`implemented` === 真实文件系统里该路由页面是否存在**

### 归因（无需全量二分即可定案）

调度者没有跑 2 次全量 `mvnw verify` 做二分，而是先把那条不变式**在整份注册表上复现**，
再用 `git diff` 缩小到唯一的新输入：

| 检查 | 结果 |
| --- | --- |
| `packages/ui/src/lib/nav-routes.test.ts` 本批是否被改 | **未改**（diff 为空） |
| `packages/ui/src/lib/task-pages.ts` 本批是否被改 | **未改**（diff 为空） |
| 本批新增的 `page.tsx` | `finance/aging` / `finance/payables` / `finance/receipts` 三个 |
| 这三个 href 在注册表里的状态 | aging **未登记**、receipts **未登记**、**payables 预登记为 `implemented: false`** |
| 在整份 75 条注册表上复现该不变式 | **只有 1 处不符**：`/finance/payables` |

⇒ 两个测试输入都没变，**唯一的新输入就是页面文件**，
而 `/finance/payables/page.tsx` 是本批 `41b5dc64 feat(12.1.2.1): 应付单管理前端` 新增的。
**归因到车道 12.1.2.1。**

### 根因：不是「改坏了」，是「刻意不登记」撞上了「早就预登记」

12.1.2.1 车道交付时**刻意没有**改 `packages/ui/src/lib/task-pages.ts` ——
理由写在该车道的会话日志里：该文件**不在** merge-union 驱动器的覆盖范围内，
同批多条 finance 车道都改它必然 git 冲突。
这个判断本身是对的（batch-78 就是这么互相冲突的）。

⇒ 但 `/finance/payables` 这个 href **在本批之前就已预登记**、且标记是 `implemented: false`
（一个「规划中、尚未实现」的占位条目）。车道把真实页面建出来、却没把占位标记翻正 ⇒ 不变式破了。

⚠️ **教训（跨车道的破坏形状又多了一种）**：
> 前两批学到的是「破坏形状不止**表结构**，还有**权限模型**」。
> 本批补上第三种：**「注册表与文件系统的同步」**。
>
> **「因为是共享文件所以不碰」这个决策本身是有代价的** ——
> 当被跳过的那个条目**恰好已经预登记为未实现**时，不碰就等于破坏。
> 静态预检的三种 grep 完全抓不到这一类（它既不改表也不改权限）。

### 修复车道（只修这一个模块，不重写）

| 车道 | 分支 | 改动 | 验证 |
| --- | --- | --- | --- |
| 12.1.2.1 | `fix/12-1-2-1-nav-route-registration` | `packages/ui/src/lib/task-pages.ts` 里 `/finance/payables` 的 `implemented: false` → `true`，**只这一个布尔值**，其余 26 个占位 href 一律不动 | `pnpm vitest run packages/ui/src/lib/nav-routes.test.ts` 从 2 failed 变全绿 + `packages/ui` 全包绿 + `pnpm typecheck` 绿 |

⚠️ 刻意**不改**那 26 个其它占位 href（`/listings/drafts`、`/purchase/plans`、`/crm/inbox`、
`/finance/reconciliation`、`/finance/exchange` 等）—— 那些模块的页面还没建，
翻了标记会让同一个测试以另一种方式红。

### 第 2 次 L2：**全绿**（修复车道并入后）

修复车道 `fix/12-1-2-1-nav-route-registration`（`eaed9965`）并入集成头（`3928928f`）后重跑：

| 层 | 结果 |
| --- | --- |
| `backend/./mvnw verify` | ✅ **绿** —— 7/7 服务 BUILD SUCCESS，`Total time: 52:36 min`，606 个测试类 0 failure 0 error |
| `pnpm typecheck` | ✅ 3/3 successful |
| `pnpm lint` | ✅ 3/3 successful |
| `pnpm test` | ✅ **绿** —— `Test Files 556 passed (556)`、`Tests 7876 passed (7876)`（第 1 次是 1 failed / 2 failed） |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ 成功 |
| `node tools/module-progress/sync.mjs --check` | ✅ 台账与蓝图一致 |

后端 reactor：backend / tenancy / identity(2:15) / catalog(4:57) / channel(13:46) /
warehouse(12:29) / trade(15:52) / crm(1:19) / finance(18.9s) —— **全部 SUCCESS**。

⇒ **L2 绿 ⇒ 说明 + 台账已 commit，并已 push 到 `origin/integration/module-dev`。**

### 修复车道自己的会话日志

`docs/sessions/2026-10/2026-10-23-fix-12-1-2-1-nav-route-registration.md`
（车道自己写的；文件名**不带** `batch-` 前缀，避免与批次号编排冲突）

---

## 本轮派发（后台运行，下一轮 tick 回收）

| 模块 | 名称 | 迁移版本 | 分支 | api-proxy 锚点 |
| --- | --- | --- | --- | --- |
| 12.1.1.3 | 坏账与核销 | `V342` | `feat/12-1-1-3` | 数组最开头 |
| 12.1.2.2 | 付款计划 | `V343` | `feat/12-1-2-2` | 第一个 `prefix: 'identity/'` 之前 |
| 12.2.1.2 | 核销 | `V344` | `feat/12-2-1-2` | 第一个 `prefix: 'warehouse/'` 之前 |
| 12.3.1.1 | 结算单管理 | `V345` | `feat/12-3-1-1` | 数组末尾 |

⚠️ **四条车道全部在卷 12、同一个蓝图文件** ⇒ 共享文件冲突风险最高，
所以 api-proxy.ts 的插入锚点**按车道错开分配**（本仓既有约定，见
`docs/backlog/2026-10/2026-10-23-concurrent-lane-shared-file-merge-conflict.md`），
且**四条车道都不得改 `apps/web/src/app/finance/page.tsx`**（那是 12.1.1.1 的页面）、
**四条车道都不得碰 `packages/ui/src/lib/task-pages.ts`**（不在 merge-union 驱动器覆盖范围内，必然冲突）。

⚠️ **本批新增的硬约定（本轮 L2 红换来的）**：
> **四条车道一律不许碰 `packages/ui/src/lib/task-pages.ts`**，且**必须用没有被预登记的子路由**。
>
> 理由：`/finance/payables` 这个坑就是「预登记为 `implemented: false` 的 href」造成的 ——
> 页面建出来了，占位标记没翻正，集成头的全量前端测试直接红。
> 反过来，**没被预登记**的 href（比如本批的 `/finance/aging`、`/finance/receipts`）
> **不构成任何问题** —— `nav-routes.test.ts` 只检查注册表里**已存在**的条目。
> ⇒ **用新子路由 = 安全；碰注册表 = 冲突 + 可能破坏不变式。**
>
> 给本批 4 条车道的指定路由（互不相同，且都不在注册表里）：
> **12.1.1.3 → `/finance/bad-debt`；12.1.2.2 → `/finance/payment-plans`；
> 12.2.1.2 → `/finance/write-off`；12.3.1.1 → `/finance/settlement`。**
>
> ⚠️ 如果你发现自己的页面路由**必须**用 `/finance/reconciliation` 之类已被预登记的 href
> （本批 26 个占位里就有 `/finance/reconciliation`、`/finance/exchange`），
> **不要自己去改注册表** —— 停下，在会话日志里写明，并在你的交付报告里点名，交给调度者统一处理。

⚠️ **12.2.1.2 与 12.3.1.1 是前两批显式「归属但未交付」的模块** ——
12.1.1.2 的核销写路径、12.2.1.1 的核销入口、12.1.1.1 的平台结算来源都指向这两格。
它们落地后，上述模块里**恒为 null / 恒等于收款金额**的字段与**刻意不放行**的端点才具备接上条件
（按各模块会话日志的记录，**接上时只需 DROP 一条 CHECK 或放开一条端点，不改表结构**）。

---

## 后续待办

1. **卷 13（主责 `reporting`）与卷 18（主责 `platform`）仍被微服务闸挡住** ——
   这两个服务不在集成头里，`plan` 已把它们列进 `serviceBlocked`。
   要么补服务引导，要么在蓝图 README 里改主责服务归属。
2. **12.1.1.1 的 `/receivables/aging` 是否也读租户配置的账龄区间** ——
   它恒用蓝图五档（不读租户配置），而 12.1.1.2 读租户配置 ⇒ 租户一旦自定义区间，两处分档会不同。
   属 12.1.1.1 的改动范围，本批未越界。
3. **12.1.2.1 的 `SUPPLIER_STATEMENT` 来源待 12.1.2.3 交付后接上**。
4. **11.4.1.1 纠纷 → 争议标记自动联动**未落地，当前只有人工标注。
5. **`packages/ui/src/lib/task-pages.ts` 的登记缺口**（有页面不一定登记）——
   需一条不触发 merge 冲突的登记方案。
   ⚠️ 这条现在有了**新的约束**：登记不只是「要不要登记」，
   还有一个**必须登记**的情形 —— 「href 已预登记为 `implemented: false`」时，
   页面一旦建成就**必须**把标记翻正，否则集成头全量前端测试直接红。
   ⇒ **派发侧应把「这个 href 在注册表里是什么状态」作为任务书的必填项写给 worker。**
6. **12.2.1.2 / 12.3.1.1 落地后**，回接 12.1.1.1 / 12.1.1.2 / 12.1.2.1 / 12.2.1.1 里
   刻意留空的核销与结算路径（各模块会话日志已写明接法）。
