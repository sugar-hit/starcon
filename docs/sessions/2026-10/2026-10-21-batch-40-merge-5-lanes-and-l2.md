# batch-40 —— 三条定价车道 + 两条跨时钟域修复并入集成头 + L2 全绿 + 派发 4 条新车道

> 批次号接在 batch-39 之后（`docs/sessions/2026-10/` 下现有最大为 batch-39）。
> 集成头 `integration/module-dev`，基线 `f69db19e` → 本批合并后 `bc29196c`。
> 本轮为 **2026-10-21 01:00 tick**（实际收口于 23:00 tick 的延续会话，见「并发调度事件」一节）。

## 本轮摘要

batch-38 派发的 4 条车道里有 3 条栽在**同一个共享白名单累积文件** `apps/web/src/lib/api-proxy.ts`
的并入冲突上，成果无法落地。上一轮派了 3 条**纯修复车道**，把三条分支的该文件解成
**逐字相同的 108 元素版本**（既有 105 + `cost-allocations` + `platform-fees` + `profit-calculator`），
本轮 01:01:56 三条**干净合并**（`ort` 策略，无冲突）。

本轮同时把上一轮 L2 暴露的**两个跨时钟域缺陷**（trade 的 DB CHECK、channel 的测试断言）修掉并并入，
然后对整组做一次 L2 全量验证，全绿后写说明、commit、push，并派发 4 条新车道。

**模块级交付：5 个**（3 个定价功能 + 2 个跨时钟域修复）；**台账：已交付 250 / 465（53.8%）**。

---

## 模块 5.2.1.3 成本分摊

**这个功能做什么**：把一次采购多个 SKU 共用的头程运费，按重量 / 体积 / 金额 / 数量四种规则
分摊到每个 SKU 上并留下版本历史；换规则或人工微调不会抹掉历史利润的算法。

**技术落地**：

- 迁移：`V224__cost_allocation.sql`（catalog）— 三张新表、**零 ALTER 任何既有表**：
  - `cost_allocation`（分摊单：来源 / 当前版本指针）
  - `cost_allocation_revision`（**只追加**的版本快照：规则 / 货件成本总额 / 生效时间 / 变更原因）
  - `cost_allocation_revision_line`（**只追加**的逐 SKU 明细：数量 / 毛重 / 体积 / 货值基数
    + 计算分摊额 + 人工微调额与原因）
  - 三表全部 `tenant_id NOT NULL` + `ENABLE` / `FORCE ROW LEVEL SECURITY` + RESTRICTIVE `data_scope`
    （跟随父对象可见）；**零 FOREIGN KEY**，引用校验一律走触发器（版本→分摊单、明细→版本、
    当前指针→版本、明细的 SKU 必须在本租户存在），另加一道**延迟约束触发器**在事务提交时校验。
- 端点：`/catalog/cost-allocations/**` 共 10 个 — `/capabilities`、`/sources`、`GET/POST`（集合读 + 新建）、
  `/{allocationId}`、`/{allocationId}/revisions`、`/{allocationId}/revisions/{revisionNo}`、
  `/{allocationId}/diffs`、`POST /{allocationId}/recalculate`、`POST /{allocationId}/revisions`
- 页面：`/pricing/cost` —— 与 5.2.1.1 成本构成、5.2.1.2 到岸成本**共用同一 L3 路由**，
  各占一个独立分区（`CostAllocationSection`），三态齐全
- 测试：`cost-allocation-allowlist.test.ts`（API 白名单）+ `cost-allocation-model.test.ts`（前端模型）

**关键设计决策**：蓝图加粗句「**分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，
不能就地覆盖」落成**三张表 + 只追加**的结构，而不是靠一条 `updated_at` 兜底 ——
因为利润复算要的是「当时那套规则 + 当时那批货件成本」的完整快照。

---

## 模块 5.2.3.1 平台费用

**这个功能做什么**：维护平台侧费用的计算规则（佣金比例或阶梯、订阅费分摊、Referral Fee、
交易费、罚款类型），并把**规则算出来的数**和**结算回来的实际数**分开摆，差额显性展示成「费用差异」。

**技术落地**：

- 迁移：`V226__platform_fee.sql`（catalog）— **三张新表、零 ALTER 任何既有表、零外键**：
  `platform_fee_rule`（费用规则 · 事前测算口径）· `platform_fee_rule_tier`（阶梯档位）·
  `platform_fee_actual`（实际费用 · 事后对账口径）
- 端点：`/catalog/platform-fees/**` 共 14 个 — `/capabilities`、`/directory`、`/rules`（GET/POST）、
  `/rules/{ruleId}`（GET/PUT）、`/rules/{ruleId}/tiers`（PUT）、`/estimate`、`/actuals`（GET/POST）、
  `/actuals/{actualId}/void`、`/variance`
- 页面：`/pricing/fees` —— 规则目录 + 阶梯编辑 + 实际费用录入 + 费用差异四块
- 测试：`platform-fee-allowlist.test.ts`（API 白名单）+ `platform-fee-model.test.ts`（前端模型）

**关键设计决策**：蓝图加粗句「**规则参数用于事前测算，实际费用来自 12 的结算对账** —— 两者差额
要在利润报表中显性展示为『费用差异』，否则测算永远不准却不知道为什么」落成**结构**而不是文案：
规则与实际费用是**两张互不相干的表**，各自独立落库、独立追溯；**差异由服务层现算、不落库**
（落库就会多出第三份可能与前两份不一致的副本）；**「算不出来」一律表达成 `null` + 一句原因，
绝不返回 0 冒充「没有差异」**。

---

## 模块 5.3.1.1 利润测算器

**这个功能做什么**：输入 SKU + 目标店铺 + 售价，输出完整的利润拆解：售价 → 平台费用 → 物流成本
→ 采购成本 → 头程 → 其他 → 毛利额与毛利率；每一项扣减都能展开看来源，缺项就不给毛利率数字。

**技术落地**：

- 迁移：`V227__profit_calculator.sql`（catalog）— `profit_calculator_scenario` /
  `profit_calculator_logistics_tier`（`tenant_id NOT NULL` + FORCE RLS + **零外键、用触发器做引用校验**，
  避开 batch-37 的 TRUNCATE 坑）
- 端点：`/catalog/profit-calculator/**` 共 8 个 — `/capabilities`、`/sites`、`POST /calculate`、
  `POST /scenarios`、`GET /scenarios`、`/scenarios/{id}`（GET/DELETE）
- 页面：`/pricing/calculator` —— 能力声明 + 测算输入 + **利润瀑布图** + 关键数字 + 缺口与口径 + 测算方案台账
- 测试：`profit-calculator-allowlist.test.ts`（API 白名单）+ `profit-calculator-model.test.ts`（前端模型）
- 拆解引擎：`ProfitCalculatorRules`（纯 BigDecimal：金额 4 位、费率/毛利率 6 位，HALF_UP）

**关键设计决策**：蓝图加粗句「**必须显式列出每一项扣减并允许展开查看来源，不能只给一个毛利率数字**」
落成三件事同时成立：① 缺的那几项**也在行里**（标成「未交付 / 该扣减未计入」并写明归谁管）；
② **null 不渲染成 0**（上游规则模块未交付 ≠ 这笔费用是 0；渲染成 0.00 会让单位成本偏低、
毛利偏高而页面上看不出异常）；③ 每一行都能展开看来源（哪张表 / 哪个上游模块 / 什么口径 / 什么生效时间）。

---

## 模块 9.3.3.1 履约请求（跨时钟域修复 · P0）

**这个功能做什么**：把「履约步骤的开始时刻」与「完成时刻」改成**同一个时钟源**，
让这一步不再因为 JVM 时钟比数据库快几十微秒就被表级 CHECK 拒掉。

**技术落地**：

- 迁移：`V211__trade_fulfillment_step_clock.sql`（trade）— `DROP` + `CREATE` 本模块那一个窄函数
  `complete_fulfillment_step`（4 参，函数自算 `elapsed_seconds`）+ 重新 `GRANT`；
  **零 ALTER 既有表、零新建表**
- 服务层：`FulfillmentRequestService.java` — 落库时间戳全部改 `clock_timestamp()`（DB 时钟），
  注入 `Clock` 便于测试；overdue 判定从 JVM 侧挪到 SQL 侧
- 端点 / DTO：**零变化** ⇒ 契约未动
- 测试：`FulfillmentRequestIntegrationTest` **35 例**（原 33 + 新增 2）

**关键设计决策**：
- **选 (A) 两个时间戳都取 DB 时钟**，而不是把 `started_at` 也换成 JVM 墙钟：这一步的「开始」与
  「完成」是**两次独立 HTTP 请求**（发起预占 → 收到 warehouse 回执），多副本部署会落在**不同实例**上；
  只把「JVM vs DB」换成「主机 A vs 主机 B」并没有消灭缺陷类别（毫秒级 NTP 差就足以让完成早于开始）。
  DB 时钟是所有实例共享的那一个。同模块的 `fulfillment_request_work_order` 本来就是这么写的。
- **不放宽 CHECK**：`finished_at >= started_at` 防的是真错误（写早、写未来），把它删掉等于关掉警报。
  回归用例专门断言 `pg_get_constraintdef` 仍是 `finished_at >= started_at`。
- **范围扩到整个模块**（实测取证，不是推断）：跑回归时 JVM 快 5 秒先炸的是
  `fulfillment_request_reservation_check5` —— 同一根因在模块内还有 6 处（`_reservation` 的
  `acknowledged_at` / `released_at`、`_outbound_order` 的 `acknowledged_at`、头行的 `completed_at` /
  `cancelled_at` / `blocked_at`）。只修 step 表就是半成品：预占回执仍被随机拒掉，流程照样卡在 RESERVING。

---

## 模块 4.3.1.1 一键刊登（跨时钟域断言修复）

**这个功能做什么**：把「派发时刻」断言的基准从**跨时钟域比较**（JVM 墙钟 + 2 秒余量）改成
**同源 DB 时钟**，根治全量跑时随机假红。

**技术落地**：

- 测试：`backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java`
  —— `dispatchedAtRoundTrips` 改为「派发时刻 == 写入它的那次事务的 DB 时刻（同一条 UPDATE 里
  `updated_at` 与 `dispatched_at` 是同一个 `now()` 的两次落笔）」且「不晚于 DB 当前时刻」；
  新增 `dispatchedAtBaselineIsDbSourced` 确定性证明用例 + `dbTimestamp(String)` 辅助
- **零改动**：`ListingPublishJobService`、任何 Flyway 迁移、`packages/api-contracts`、任何前端文件
- 测试数：**19 例**（原 18 + 新 1）

**关键设计决策**：否决了「把 2 秒余量放宽到分钟级」（那是跨时钟域比较的补丁，batch-38 实测两台机器
差 ~48 秒，放宽只是把复发概率往后推）、「读一次派发前的 `clock_timestamp()` 当下界」（对事务年龄不免疫：
写入所在事务若早于该参照，新断言反而假红）、「删掉断言」（真回归会漏过去）。
下界取**同行 `updated_at`** 而非作业行的 `dispatched_at`：前者同表同行无跨表耦合，后者的
`coalesce(dispatched_at, now())` 语义在重试路径下会漂。

---

## 静态预检（L2 之前）

对本批 4 条新迁移（V224 / V226 / V227 / V211）跑「已知互相破坏形状」扫描：

| 检查 | 结果 |
| --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | **无命中** |
| `SET NOT NULL` | **无命中** |
| `ADD CONSTRAINT ... CHECK` | **无命中** |
| `ALTER TABLE` | 仅 `V224`/`V226`/`V227` 的 DO 块里对**新表**做 `ENABLE/FORCE ROW LEVEL SECURITY`（动态作用于新表清单），**零 ALTER 既有表** |
| `FOREIGN KEY` / `REFERENCES` | **零命中**（只有注释在解释为什么不用外键） |

⇒ 四条迁移都是「新建表 + 零 ALTER 既有表 + 零外键」，不构成对早先并入车道的破坏形状。

## 并入过程

- 上一轮派发 3 条**纯修复车道**（`fix(merge)` 提交，无新功能、无新迁移）：
  把三条分支的 `api-proxy.ts` 解成**逐字相同的 108 元素版本**。
- 01:01:56–01:01:57 顺序 `merge-ready`：`5.2.1.3` → `5.2.3.1` → `5.3.1.1`，**三条全部干净合并**。
- 本轮 01:29 再次 `merge-ready`：`4.3.1.1` → `9.3.3.1`，**两条全部干净合并**（head `5ec9181a` → `bc29196c`）。
- 并入后校验：HEAD 的 `api-proxy.ts` = **108 元素**，四个新 token 全在；
  契约 `packages/api-contracts/src/contracts/catalog.ts` = 729 个导出类型，
  **逐条比对三条分支的类型名，缺失 0 个**（并集驱动器没有静默吃掉任何一条）。
- 台账完整性：465 行、无重复模块 ID，`sync --check` 通过。

## L2 批量验证

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `cd backend && ./mvnw verify` | ✅ **BUILD SUCCESS**，6 个服务全绿：identity 270 / catalog 1680 / channel 1134 / warehouse 1535 / trade 1496 / crm 44 = **6159 例，0 失败 0 错误 0 跳过** |
| 前端类型 | `pnpm typecheck` | ✅ 绿 |
| 前端 lint | `pnpm lint` | ✅ 绿（仅既有文件的 warning） |
| 前端测试 | `pnpm test` | ✅ **334 个测试文件 / 5216 例全绿** |
| Next 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ **220/220 静态页生成** |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | ✅ 台账与蓝图一致 |

- 本轮**第一次**把后端全量 `verify` 纳入本批（上一批后端零改动，才只跑前端五阶段）。后端 32 分钟（01:23:30 → 01:55:59），前端五阶段 1.5 分钟，**单轮 L2 ≈ 34 分钟**。
- 上一批 L2 的两处红（trade 的 `fulfillment_request_step_check3`、channel 的 `dispatchedAtRoundTrips` 断言）**本轮均已消失** —— 两处根因都在跨时钟域，现已改成同源 DB 时钟。

## 本批派发的车道

| 模块 | 名称 | 服务 | Flyway | 分支 | worktree |
| --- | --- | --- | --- | --- | --- |
| 15.2.2.2 | 索评规则 | crm | V228 | `feat/15-2-2-2` | `.worktrees/15.2.2.2` |
| 5.2.2.2 | 汇率快照 | catalog | V229 | `feat/5-2-2-2` | `.worktrees/5.2.2.2` |
| 5.3.1.2 | 批量测算 | catalog | V230 | `feat/5-3-1-2` | `.worktrees/5.3.1.2` |
| 5.3.2.1 | 定价模板 | catalog | V231 | `feat/5-3-2-1` | `.worktrees/5.3.2.1` |

### ⭐ 本轮新增的编排手法：给 4 条车道**钉死互不重叠的 api-proxy 插入锚点**

**问题**：`apps/web/src/lib/api-proxy.ts` 没有并集合并驱动器（`.gitattributes` 只覆盖
`packages/api-contracts/src/**` 与台账），每条车道都往同一个 `services[]` 数组里追加元素 ⇒
追加位置重叠 ⇒ git 必判冲突。上一轮实测的硬结论：**只有两边逐字完全相同才能干净合并**，
「并入方是当前方的超集」也会冲突。

**本轮做法**（**不动任何编排逻辑**，只在任务书里钉锚点）：4 条车道的任务书各自写明
「你的新元素插在**哪一个既有元素之后 / 之前**」，四个锚点两两相隔 ≥ 15 行
（远大于 git 的 3 行上下文），因此四条分支的 diff hunk 互不重叠 ⇒ 并入时逐条求并不冲突：

| 车道 | 插入锚点 |
| --- | --- |
| 5.2.2.2 汇率快照 | 5.2.2.1「汇率查询」那条元素之后（catalog 组中段） |
| 5.3.2.1 定价模板 | 5.2.3.1「平台费用」之后、5.3.1.1「利润测算器」之前 |
| 5.3.1.2 批量测算 | 5.3.1.1「利润测算器」之后（数组最末尾） |
| 15.2.2.2 索评规则 | 15.2.2.1「Request a Review」那条 crm 元素之后（文件中段） |

任务书里还钉了两条**提交前自检**（结果要贴进交付报告）：
① `grep -c "prefix: '" apps/web/src/lib/api-proxy.ts` 必须 = **109**（基线 108 + 本车道那 1 条）；
② `git diff -- apps/web/src/lib/api-proxy.ts` 只能有新增行、**0 删除行**。

⚠️ **这仍然只是缓解，不是根治**：只要两条车道插到同一个位置就还会撞。
根治要靠给 `api-proxy.ts` 配「按元素求并」的合并驱动器（求并键 = `pattern` 正则源文本，
解析不了必须大声失败），那是**待用户拍板**的 P0 事项，已记在
`docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`。

## 收尾三件套

① **写说明** → 本文件 `docs/sessions/2026-10/2026-10-21-batch-40-merge-5-lanes-and-l2.md`
（每个模块一节：功能是什么 / 迁移·端点·页面·测试四件 / 关键设计决策与被否决的方案）
② **commit** → `docs(batch-40): 5.2.1.3 / 5.2.3.1 / 5.3.1.1 / 9.3.3.1 / 4.3.1.1 —— 五车道并入集成头 + L2 六阶段全绿（后端 6159 例 / 前端 5216 例）`
（本说明与台账行**同一个 commit**，不拆开）
③ **push** → `git push origin integration/module-dev`（只推集成分支，**不推 main**）

## 遗留与风险

1. **api-proxy 并集驱动器仍未落地（P0，待用户拍板）** —— 本轮用「钉锚点」把冲突概率压下去了，
   但只要两条车道插同一个位置就会撞。根治方案见
   `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md` 的解法 A（求并键 =
   `pattern` 正则源文本；解析不了必须非零退出，绝不能落「行级去重并集」兜底）。
2. **本轮出现两个调度会话重叠** —— 23:00 tick 未收工，01:00 cron 又按时起了同一个流水线会话。
   01:00 那次**按协议应当「闸被持有且 stale=false ⇒ 零改动退出」**，实际它先跑了 `plan --dry-run`
   并在 `.tmp/` 写了任务书草稿（**无副作用**：没建车道、没占 Flyway 版本号、没派 worker），
   经协调已确认停止（零派发、未 merge、未 L2、未 commit/push）。
   ⇒ 建议把「闸被持有且未过期 ⇒ 立即退出并只报一行」写进 cron prompt 的**第一行**，
   现在这条纪律只写在协议正文里，cron 触发时不一定被读到。
3. **worker 越界操作** —— 上一轮的修复车道 worker 误跑了 `lane.mjs merge-ready`（把三条车道直接
   并进了集成分支）并写了 `.dev-agent/last-batch-at`。本轮起，修复车道的任务书已加禁令：
   只允许 `heartbeat` 与 `land`，**严禁** `merge-ready` / `plan` / `gate` / `gc` / 写时间戳。
4. **闸 TTL 70 分钟 vs 单轮实测 45–60 分钟** —— 本轮靠 `keepalive` 续期覆盖（合并前一次、L2 前一次）。
   TTL 与「整点栅格 + 阈值 50 分钟」三者的错配仍在，属用户决定项。
5. **跨时钟域同类隐患尚未全仓扫** —— 9.3.3.1 与 4.3.1.1 两条车道都只修了各自模块的范围
   （「JVM 墙钟落请求侧时刻 + DB 落转移侧时刻 + CHECK 比大小」这个形状在别的模块还有）。
   需要单独派一条全仓扫描车道。
6. **一个 worker 撞网关 502** —— 5.3.1.2 的第一次 worker 在代码探索阶段被基础设施故障打断
   （worktree 干净、无半成品），已原地重派。
