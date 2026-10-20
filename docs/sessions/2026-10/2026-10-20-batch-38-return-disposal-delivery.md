# batch-38 —— 回收 1 条车道并入集成头 + L2 批量验证

**日期**：2026-10-20 20:00 tick
**模式**：full（`TOKEN_HORIZON=2026-10-25`，未超期；`LANES=4` / `THROTTLE_MIN=50` / `RUNNING_QUOTA=6` / `LOAD_LIMIT=12`）
**本批交付**：1 个模块并入集成头（`10.7.1.3`），
另派发 4 条新车道（`5.2.1.3` / `5.2.2.1` / `5.2.3.1` / `5.3.1.1`）。

> 本轮台账：465 个 L4 模块，**已交付 243 / 进行中 0 / 未开发 222**（52.3%）。

---

## 模块 10.7.1.3 弃置与销毁

**这个功能做什么**：把「这批货没有销售价值，按品牌方或法规要求必须销毁掉」变成
**有凭证、不可篡改、追责时说得清**的一件事 —— 待销毁清单、销毁单、
逐件凭证（照片 / 重量 / 处置证明编号 / 执行方签核）、销毁数量与重量记录。
销毁数量同时是品牌方索赔的依据（→ 11.2.3.3 供应商索赔）。

**技术落地**：

- 迁移：`V221__return_disposal.sql`（channel）—— **六张表 + 序列 + 9 个触发器 + RLS + GRANT，
  零 ALTER 任何既有表**：
  `return_disposal_evidence_policy`（凭证要求策略 GENERAL / BRAND / PLATFORM）、
  `return_disposal_order`（销毁单主表）、`return_disposal_evidence`（逐件凭证）、
  `return_disposal_cost`（销毁费用）、`return_disposal_claim_basis`（索赔依据事实）、
  `return_disposal_event`（事件流）。
  RLS 用 DO 块循环对全部业务表开 `ENABLE` + `FORCE ROW LEVEL SECURITY`，
  `isolation` 策略按 `app.current_tenant` 隔离，
  其中 5 张表另加 `RESTRICTIVE` 的 `data_scope` 策略。
- 端点：`/channel/return-disposals` × **18** —— `capabilities`（能力声明）、
  `overview`（概览）、`candidates`（待销毁清单）、`policies`（GET/POST）、
  `policies/{id}/revoke`（撤销策略）、`orders`（GET 真分页 / POST 建单）、
  `orders/{id}`（详情）、`orders/{id}/schedule`（排期）、`orders/{id}/evidence`（追加/改写凭证）、
  `orders/{id}/costs`（费用）、`orders/{id}/claim-basis`（产出索赔依据）、
  `orders/{id}/dispose`（完成销毁）、`orders/{id}/cancel`（取消）、
  `claim-bases/{id}/submit`、`claim-bases/{id}/reject`、`events`。
- 页面：`/logistics/return-disposal` ——
  `apps/web/src/app/logistics/return-disposal/page.tsx` +
  `return-disposal-model.ts` / `return-disposal-workspace.tsx`；
  加载态 / 空态 / 错误态三态齐全，凭证缺口在服务端给 422 并**逐项列出差哪几样**。
- 测试：`ReturnDisposalIntegrationTest` **22 例** +
  `ReturnDisposalTenancyIntegrationTest` **3 例**（租户隔离三断言）；
  前端 `return-disposal-model.test.ts` **19 例** +
  `apps/web/src/app/api/channel/return-disposal-allowlist.test.ts` **6 例**。

**关键设计决策**：

1. **销毁凭证缺失 ⇒ 不许置为完成态**，落成四道结构闸而不是一句提醒：
   ① `disposal_method` 是白名单枚举（SHRED / INCINERATE / PROFESSIONAL）且**不写 DEFAULT** ——
   少给一列直接 23502，而不是悄悄落成某个方式；
   ② 凭证要求（`require_photo` / `require_weight` / `require_certificate` / `require_executor`）
   由 **DB 触发器**按「方式基线 ∪ 品牌方·平台政策」**重算并写回**，客户端自报一律作废；
   ③ 凭证一行一件、每类**专属字段列级 CHECK**、一类一张 `UNIQUE`、
   凭证不可删（REVOKE DELETE）；
   ④ 完成态的门在 DB 上：触发器 `guard_return_disposal_order_state`
   在 `status → 'DISPOSED'` 时逐项核对四面旗子 + 时刻 + 执行方，**绕过服务层直接写 SQL 也进不去**。
2. **凭证要求只能加严、不能放松**：策略解析取**并集**，
   品牌化客户的「销毁并回传证明」合约义务是**下限**。
   `return_disposal_evidence_policy` 只挂租户隔离、不挂数据范围 ——
   一条对全租户生效的限制若只有建它的人看得见，
   另一个运营就会在**看不见要求**的情况下把货销毁掉。
3. **待销毁清单只来自 10.7.1.1，同服务直接读**：`return_disposition_order` 里
   `disposition_method = 'DISCARD'` 且未撤销的才进得来（服务层 422 + DB 触发器各拒一次）；
   一条处置单最多进一个销毁单（`UNIQUE(tenant_id, disposition_order_id)`）。
   SKU / 批次 / 收货单号由服务端从处置单抄，**不接受客户端自报**。
4. **重量以 WEIGHT 凭证的称重读数为唯一来源**，不是建单时先填一个 ——
   否则会出现「单上 12.5kg、凭证 13kg」两个互相矛盾的数字；
   触发器在 `DISPOSED` 时要求 `NEW.weight_kg` 逐字等于那张 WEIGHT 凭证。
5. **下游缺口按仓库先例显式声明、不读写不存在的服务**：12 财务（finance 服务不存在）⇒
   销毁费用以本服务自持成本行落地（合计不落表、查询时 SUM 现算、不做任何汇率换算）；
   11.2.3.3 供应商索赔在 trade（跨服务）⇒ **只产出索赔依据事实**，
   以 `source_reference` 记逻辑引用，**绝不写 trade 的任何表**。
6. **零外键**：引用校验一律用触发器做（同批 `V219` 的同款纪律），
   跨车道 DDL 回归面 = 0，**没有打断任何既有测试的 `TRUNCATE`**。

---

## 静态预检（L2 之前）

对本批新迁移 `V221__return_disposal.sql` 跑「已知互相破坏形状」扫描：

| 检查 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **未命中**（V221 零 `ALTER TABLE`） |
| ② `SET NOT NULL` | **未命中** |
| ③ `ADD CONSTRAINT ... CHECK` | **未命中**（列级 CHECK 写在 `CREATE TABLE` 内，不 ALTER 既有表） |

⇒ **零命中**。全仓扫描命中的都是历史迁移（V57 / V67 / V73 / V77 / V105 等），不在本批范围内。

---

## 并入过程

| 模块 | 分支 | merge commit | 冲突 |
| --- | --- | --- | --- |
| 10.7.1.3 | `feat/10-7-1-3` | `4983a9cc` | 无 |

契约按合并驱动器求并（`packages/api-contracts/src/**` + 台账 `merge=contract-union`）：
channel 纯增量 **+16 path / +27 schema / 0 改动 / 0 删除**。
`lane.mjs gc` 已回收该车道的 worktree。

## 台账完整性

```
grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md   → 465
grep -oE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' ... | sort | uniq -d  → 空（无重号）
```

---

## L2 批量验证

### 第一轮：后端红（1 例 / 1133），其余阶段未跑到

| 阶段 | 退出码 |
| --- | --- |
| `backend && ./mvnw verify` | **1** ❌ |
| `pnpm typecheck` / `lint` / `test` / Next build / `sync --check` | 未执行（前一阶段红即停） |

后端 reactor 跑到 `channel`（5/8）时红，`warehouse` / `trade` / `crm` 被 SKIPPED：

```
[ERROR] Tests run: 18, Failures: 1, Errors: 0 -- in com.starcon.channel.ListingPublishJobIntegrationTest
[ERROR] Tests run: 1133, Failures: 1, Errors: 0
```

**唯一失败用例**：`ListingPublishJobIntegrationTest.dispatchedAtRoundTrips`（第 982 行）

```
Expecting actual: 2026-10-20T20:20:49.933357+08:00
to be strictly after: 2026-10-20T20:21:37.802934+08:00
```

### 归因：跨时钟域断言，**不是本批回归**

- **V221 不可能造成这个红**：`V221__return_disposal.sql` 是**纯 CREATE TABLE**（六张新表，
  零 `ALTER TABLE`、零外键），根本没碰 `listing_publish_job_target`；
  本批唯一的既有文件改动是 `api-proxy.ts`（新增一条独立 `services[]` 元素）。
- **失败用例属于已交付的 4.3.1.1 发布任务**，与本批模块无依赖关系。
- **机制**：`ListingPublishJobService` 写的是
  `dispatched_at = now()` —— PostgreSQL 的 `now()` 是**事务开始时刻**；
  测试却拿 JVM 的 `OffsetDateTime.now().minusSeconds(2)` 去比，**只留 2 秒余量**。
  全量跑时事务已开了 ~50 秒 ⇒ DB 侧时刻比 JVM 侧早 50 秒 ⇒ 断言必假红。
- **隔离复跑 18/18 全绿**：
  ```
  ./mvnw -pl services/channel -am test -Dtest='ListingPublishJobIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
  → Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
  ```
- **容器与宿主时钟实测只差 ~1 秒**（临时起一个 `postgres:16-alpine` 对拍），
  ⇒ 不是 Docker Desktop VM 的持续漂移，而是**事务开始时刻 vs 墙钟**的语义差。

> ⚠️ 这条结论有复用价值：**凡断言「DB `now()` vs JVM `now()`」的测试都有复发风险**。
> 正确写法是拿 DB 自己的 `now()` 做基准，或把余量放宽到分钟级。
> 该缺陷属已交付模块 4.3.1.1 的**存量测试脆弱性**，不在本批交付范围内 ——
> 本批不夹带修它（改了就是「重写别人的模块」），已记入下轮待办。

### 第二轮：重跑全量 L2 —— **六个阶段全绿** ✅（22:43:24 收口）

失败用例**没有复现** ⇒ 第一轮的红确认是**时序相关的存量脆弱性**，不是本批回归。

| 阶段 | 退出码 |
| --- | --- |
| `backend && ./mvnw verify` | **0** ✅ |
| `pnpm typecheck` | **0** ✅ |
| `pnpm lint` | **0** ✅ |
| `pnpm test` | **0** ✅ |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **0** ✅ |
| `node tools/module-progress/sync.mjs --check` | **0** ✅ |

后端 reactor **8/8 模块全过**（第一轮被 SKIPPED 的 warehouse / trade / crm 这次全部跑到）：

```
backend ....... SUCCESS [  0.001 s]
tenancy ....... SUCCESS [  0.443 s]
identity ...... SUCCESS [01:25 min]
catalog ....... SUCCESS [03:41 min]
channel ....... SUCCESS [06:17 min]
warehouse ..... SUCCESS [11:15 min]
trade ......... SUCCESS [08:47 min]
crm ........... SUCCESS [  7.145 s]
BUILD SUCCESS
```

| 项 | 数量 | 失败 | 错误 |
| --- | --- | --- | --- |
| 后端 surefire | **5955** | **0** | **0** |
| 前端 vitest | **5056**（320 个测试文件） | **0** | **0** |
| Next production build | **215/215** 静态页生成 | — | — |

⇒ 本批 L2 **两轮跑下来：真红 0 次**。第一轮那一例在隔离复跑与第二轮全量里都没再出现，
按上面的归因（跨时钟域 + 2 秒余量）判定为**存量脆弱性**，
**不归因到本批交付**，也不在批次里夹带修已交付模块的测试。

---

---

## 本批派发的 4 条新车道

L2 绿之后派发（`plan --lanes 4`，迁移版本号由编排脚本预分配）：

| 模块 ID | 名称 | 阶段 | L3 | 服务 | 迁移版本 | 分支 |
| --- | --- | --- | --- | --- | --- | --- |
| 5.2.1.3 | 成本分摊 | D6 | 5.2.1 | catalog | **V224** | `feat/5-2-1-3` |
| 5.2.2.1 | 汇率查询 | D6 | 5.2.2 | catalog | **V225** | `feat/5-2-2-1` |
| 5.2.3.1 | 平台费用 | D6 | 5.2.3 | catalog | **V226** | `feat/5-2-3-1` |
| 5.3.1.1 | 利润测算器 | D6 | 5.3.1 | catalog | **V227** | `feat/5-3-1-1` |

四条车道的 **L3 锚点互不相同**，全部落在 **catalog** 服务（卷 05 的后端主责服务）。

### ⚠️ 一条必须写进任务书的事实：编排脚本看不到「卷级上游」

`lane.mjs` 的上游解析只认 **4 级模块 ID**（正则 `\b(\d+\.\d+\.\d+\.\d+)\b`），
而蓝图的「关系」字段里大量写的是**卷级引用**（`上游 = 5.2 全部` / `10 头程费用` /
`12 landed_cost_allocation` / `6 采购成本`）。
卷级引用**匹配不上** ⇒ 该模块的上游数组为空 ⇒ `upstreamReady` 恒真 ⇒
**依赖检查是假绿的**。

本批的具体后果：`5.3.1.1 利润测算器` 的上游写的是「上游 = 5.2 全部」，
而 5.2 卷里 7 个模块只有 1 个（`5.2.1.1 成本构成`）已交付，
另外 6 个里 3 个是**本批并行的车道**（未并入）、3 个**未开发**。
⇒ 这条车道若按「上游已交付」的字面假设开工，缺 4 项扣减的毛利率会被当成定价依据。

任务书里因此写明了逐条真实依赖状态，并要求 worker 在 10 分钟自检里
**二选一**（显式标注缺项 / 回报 BLOCKED），**不允许既开工又假装上游齐全**。

### 本批任务书里带进去的两条纪律

1. **外键纪律**（上一批 batch-37 刚踩的坑）：
   上一批有一条车道给既有表加了 FOREIGN KEY，打破了同服务既有测试的 `TRUNCATE`
   ⇒ 39 例 Errors ⇒ L2 直接红。⇒ **优先用触发器做引用校验，不用数据库外键**；
   确实要建外键则**必须同车**把引用你新表的既有测试 `truncate` 补 `cascade`，
   且只许改 truncate 语句、不许改断言。
   本批 V221 用的就是**零外键 + 触发器**，跨车道 DDL 回归面 = 0。
2. **api-proxy 白名单纪律**：`apps/web/src/lib/api-proxy.ts` 是多车道共用的累积正则文件 ——
   只新增一条独立 `services[]` 元素（既有条目一字未动）、前缀在既有全部正则里零命中、
   改了这个文件**必须跑全量 allowlist 回归**。

---

## 收尾三件套

| # | 动作 | 结果 |
| --- | --- | --- |
| ① | 写说明（comment） | ✅ 本文件 `docs/sessions/2026-10/2026-10-20-batch-38-return-disposal-delivery.md` |
| ② | commit（说明 + 台账**同一个 commit**） | ✅ `docs(batch-38): 10.7.1.3 ……` —— 说明与台账行同 commit |
| ③ | push `origin integration/module-dev` | ✅ **L2 绿后执行** |

### 本批 push 的 commit

| commit | 内容 |
| --- | --- |
| `19da4f16` | `feat(10.7.1.3): 弃置与销毁 —— 销毁凭证缺失不许置为完成态`（worker 车道提交） |
| `4983a9cc` | `merge(10.7.1.3): 弃置与销毁`（并入集成头） |
| `docs(batch-38)` | 本批说明 + 台账计数器（243 / 222 / 52.3%） |

### 本轮调度上的两个发现（留给下一轮）

1. **L2 第一轮那一红是跨时钟域假红，不是回归** ——
   `ListingPublishJobService` 写 `dispatched_at = now()`（PG 的**事务开始**时刻），
   测试却拿 JVM `OffsetDateTime.now()` 比且只留 2 秒余量 ⇒ 全量跑时事务开了 ~50 秒就必假红。
   **凡是断言「DB `now()` vs JVM `now()`」的测试都有复发风险**，
   正确写法是拿 DB 自己的 `now()` 做基准。该缺陷属已交付模块 4.3.1.1 的存量问题，
   **下轮单独派一个修复车道**（只改这一个测试类的断言基准，不重写模块）。
2. **闸与 cron 的栅格不匹配，导致连续两轮零派发** ——
   cron 是 `0 * * * *`（整点栅格，间隔 60 分钟），单轮实测 40–100 分钟，
   而闸 TTL 只有 70 分钟 ⇒ **持闸 tick 还没收工，下一格 tick 就到了**；
   下一格看到「闸 held、stale=false」按协议不敢抢 ⇒ **零派发**（21:02、22:00 两轮都如此）。
   本轮是持闸 tick 本身，最终把 4 条车道派了出去；
   但只要单轮时长稳定超过 70 分钟，**每小时都会有一轮空转**。
   ⇒ 这是**用户该决定的事**（加长闸 TTL / 改 cron 栅格 / 缩短单轮时长），
   调度器**不得自行改节流闸与 cron**（PIPELINE.md 明令）。已点名待决。

