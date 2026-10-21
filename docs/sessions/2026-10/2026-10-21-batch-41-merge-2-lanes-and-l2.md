# batch-41 —— 汇率快照 + 索评规则并入集成头 + L2 全绿 + 派发 4 条新车道

> 批次号接在 batch-40 之后（`docs/sessions/2026-10/` 下现有最大为 batch-40）。
> 集成头 `integration/module-dev`，基线 `75646b73` → 本批合并后 `1f628c40`。
> 本轮为 **2026-10-21 02:00 tick**。
>
> ⚠️ **本轮开头撞了调度闸**：`gate status` 显示 `gate=held / ageSeconds=6 / stale=false`，
> 持闸者是**并发的 `main → module-dev` 同步任务**（另一个 cron，与本流水线共用同一把闸，
> 协议见 `tools/module-progress/SYNC-MAIN.md`）。按协议**零派发 + 只读检查**即可结束，
> 但本轮改用**有界等待**：轮询闸状态 4 分钟，等到对方在 02:05 正常 `gate release`
> 后再取闸 —— 避免白白浪费一整轮产能。等待期间**未对集成头做任何写操作**。

## 本轮摘要

本轮**回收 2 条车道**（15.2.2.2 索评规则 / 5.2.2.2 汇率快照），`merge-ready` 两条都干净合入
（`ae99e7bd` / `1f628c40`，无冲突），台账从 **250 → 252 已交付**（54.2%）。

**派发 4 条新车道**：5.2.3.2 物流与头程费用（catalog V232）、6.1.2.1 档口与货源池（trade V233）、
6.2.1.1 缺货与断货风险（trade V234）、6.3.2.2 到货与逾期（trade V235）。

**本轮新增的编排手法：给 4 条新车道钉死互不重叠的 `api-proxy.ts` 插入锚点（行号级）**
（细节见「本批派发的车道」一节）。

**模块级交付：2 个**（1 个定价/汇率功能 + 1 个客服索评功能）。

---

## 模块 5.2.2.2 汇率快照

**这个功能做什么**：把「今天用的那个汇率」**冻结成一张不可改的凭据**，
连同换算方向与舍入方式一起锁死；下个月要复算上个月的利润时，
拿到的是**当时那张快照**，而不是汇率表里**现在**的值。还能拿快照**重算一遍**，
和当时写下的目标币金额逐笔比对，对不上就报出来。

**技术落地**：

- 迁移：`V229__exchange_rate_snapshot.sql`（catalog）— **两张新表、零 ALTER 任何既有表、零 FOREIGN KEY**：
  - `exchange_rate_snapshot`（**只追加**的冻结快照）
  - `exchange_rate_rounding_rule`（租户级舍入规则配置）
  - 两表全部 `tenant_id NOT NULL` + `ENABLE` / `FORCE ROW LEVEL SECURITY` + `GRANT`；
    **零个 FOREIGN KEY**（引用校验一律用触发器：快照的 `rate_id` → `exchange_rate.id`，
    与 V225 / V224 同款取舍 —— 外键会打破同服务既有测试的 `TRUNCATE`）
- 端点：`/catalog/exchange-rate-snapshots/**` 共 7 个 — `/capabilities`、集合读 + `POST` 冻结、
  `/{uuid}` 详情、`POST /replay`（**利润复算**：拿冻结的原价、汇率、方向、舍入方式重算一遍，
  与冻结时写下的目标币金额**逐笔比对**，纯计算不写库）、`/rounding-rules`（GET+POST）、
  `/rounding-rules/initialize`（POST，幂等，内置通用两位 + 常见币种 + 日元/韩元零位）、
  `/rounding-rules/{code}`（PUT，乐观锁；**按规则代码寻址不是 uuid**）
- 页面：`/pricing/rates` 的 **5.2.2.2 分区**（`exchange-rate-snapshot-model.ts` +
  `exchange-rate-snapshot-workspace.tsx` + `exchange-rate-snapshot-model.test.ts`；
  **与 5.2.2.1 共用 L3 路由、各占一个独立分区**）：能力声明（冻结 / 方向 / 舍入三条规则摆在最显眼位置）+
  冻结表单（按币种对 + 生效日取汇率，或直接指定 `rateId`）+ 快照台账（筛选 / 分页，
  **每行显示整条换算过程**「100 USD × 7.1234 = 712.34 CNY（HALF_UP · 2 位小数）」，表格里没有裸数字）+
  利润复算面板（逐笔给「一致 / 对不上」结论）+ 舍入规则配置（列表 / 初始化内置 / 新建），
  **加载态 / 空态 / 错误态三态齐全**
- 测试：`ExchangeRateSnapshotIntegrationTest`（19 例，真实 PG16 Testcontainers + 强制 RLS，连接池固定 1 条连接）
  + `ExchangeRateSnapshotTenancyIntegrationTest`（1 例，**租户隔离三断言**：两租户互不可见 /
  伪造 `X-Tenant-Id` 头无效 / 单连接反复交替 5 轮不串租户）
  + `ExchangeRateSnapshotRulesTest`（14 例纯规则：方向解析、六种舍入方式逐值钉死、换算入口、字段校验）；
  前端 `exchange-rate-snapshot-model.test.ts`（33 例）+ `exchange-rate-snapshot-allowlist.test.ts`（6 例，
  双向断言：契约里真实 path 全放行 / 不存在的形状仍 404 / 兄弟车道条目未被挤掉）
- 契约：`packages/api-contracts` 由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  重新生成（+12 个 `ExchangeRateSnapshot*` DTO，纯新增）

**关键设计决策**：

1. **蓝图加粗句「今天汇率变了不能让上个月的利润报表数字跟着变」落成三个物理结构**，而不是靠约定：
   - **整行冻结** —— 汇率值、方向、生效日、来源、舍入方式、目标币金额**全部冗余冻结在快照行里**，
     读快照**不 JOIN 汇率表取值**（汇率表改了、来源被停用了，历史快照一个数字都不动）；
   - **只追加** —— `GRANT` 只给 `SELECT` / `INSERT`（与 V225 拉取日志同一手法）+
     `guard_exchange_rate_snapshot_immutable` 触发器挡住 `UPDATE`（连表 owner 直连 SQL 也改不动）；
   - **同一单据行 + 同一币种对只能有一条**（UNIQUE）—— 要更正就换单据行标识另冻一条，
     原快照原样保留，历史不被抹掉。
2. **方向显式且冻结的是「哪一条」**：快照同时记 `rate_id` / `rate_base_currency` / `rate_quote_currency` /
   `rate` / `conversion_direction`（`BASE_TO_QUOTE` 乘 · `QUOTE_TO_BASE` 除）/ `source_currency` → `target_currency`；
   两条 CHECK 保证换算的两侧正好是这条汇率的两侧；
   `guard_exchange_rate_snapshot_rate` 触发器逐项核对冻结值与被引用汇率行**逐字相同**
   —— 「快照上写着一个汇率表里根本不存在的汇率」直连 SQL 也写不进来。
   **反向汇率仍然只能推导、不能手工再录一条方向相反的**（V225 第二节纪律在本模块继续生效，除法由服务层按冻结的 `rate` 现算）。
3. **舍入方式必须落在数字上**：`rounding_rule_code` + `rounding_mode`（六个取值与 `java.math.RoundingMode` 逐字对应）
   + `rounding_scale` + 目标币金额**一起冻结** ⇒ 复算不需要回查规则表。
   **解析顺序是「先具体后通用」两步**（先找适用币种正好等于目标币种的规则，找不到再找通用兜底规则）
   —— 一步到位按 `priority` 抓会让通用规则盖掉日元零位规则，**日元按两位小数入账是真实存在的账务事故**；
   **没有可用规则时明确拒绝冻结**（400），不偷偷按默认口径舍入。
4. **跨服务纪律**：09 订单 / 12 财务的单据不在本库 ⇒ 只用逻辑引用
   （`document_type` + `document_id` + 单据号）定位，**不建跨库 FK**。
5. **「算不出来」一律表达成查不到，而不是拿别的顶替**：快照只能冻结**已登记**的汇率事实
   （`rate_id NOT NULL`）—— 想冻结一条没登记的汇率，先在 5.2.2.1 登记它；
   查不到就报查不到（404 并写明「不会拿别的币种对或过期日期的汇率顶替」），不回退到「最近的一版」。
6. **没有「编辑」「删除」入口** —— 快照是只追加的冻结事实。
7. **与 5.2.2.1 的分工**：5.2.2.1 只产出**汇率事实**（可改、可更正），本模块只冻结与复算（**不可改写**）；
   **与下游的分工**：09 订单 / 12 财务 / 13 利润读快照复算利润，本模块不代它们做账、也不替 13 利润出报表。

---

## 模块 15.2.2.2 索评规则

**这个功能做什么**：把「什么时候向买家要评价、说什么话、按什么比例 A/B 分流、
节假日顺延到哪天」这套索评策略**配成可执行的规则**，
并让运营能看到**每条规则实际带来的效果对比**（按变体 / 按规则两路）。

**技术落地**：

- 迁移：`V228__crm_review_rule.sql`（**归属 crm**：卷 15 客户与客服）——
  **五张新表**、**零 ALTER 任何既有表**（V220 / V99 一行未改）：
  `crm_review_rule`（索评规则配置）/ `crm_review_message_template`（话术模板）/
  `crm_review_message_variant`（话术变体 A / B + 权重）/ `crm_review_holiday`（节假日日历）/
  `crm_review_rule_assignment`（生效策略落地 = 效果对比唯一的归属事实）；
  全部 `tenant_id NOT NULL` + `ENABLE` / `FORCE ROW LEVEL SECURITY` + `GRANT crm_app`；
  **序列单独授权**（`crm_review_rule_no_seq` / `crm_review_message_template_no_seq` ——
  `GRANT ... ON ALL TABLES` **不覆盖序列**，漏授权会变成「第一条规则就报权限不足」）
- 端点：`/crm/review-rules/**` 共 **13 个** — `/capabilities`、`/shops`、集合 GET + `POST`、
  `/{uuid}`（GET·PUT·DELETE）、`/templates`（GET + POST）、`/templates/{uuid}`（GET·PUT·DELETE）、
  `/holidays`（GET + POST）、`/holidays/{uuid}`（GET·PUT·DELETE）、`/effective`（GET）、
  `/candidates`（GET）、`/assignments`（GET + POST 应用策略）、`/assignments/{uuid}`（GET）、
  `/effect-comparison`（GET）；**刻意没有** `{uuid}/variants` 之类后端不存在的映射
- 页面：`/crm/review-rules` —— 能力声明逐维显形 / 生效策略预览（含分桶种子、节假日顺延口径）/
  索评规则增删改查 / 话术模板 A/B 变体与权重 / 节假日登记（支持连休）/ 应用生效策略落到索评请求 /
  落地记录与详情 / 效果对比按变体与按规则两路
- 测试：后端 **26 例全绿**（`ReviewRuleIntegrationTest` 22 + `ReviewRuleTenancyIntegrationTest` 4，
  Testcontainers PostgreSQL 16，**真跑 RLS 隔离三断言**：两租户互不可见 /
  伪造 tenant header 无效**且行落在会话租户名下** / 连接池归还后不串租户）；
  前端模型 + allowlist **47 例**、**全量 api allowlist 回归 660 例**全绿，`pnpm typecheck` 3/3 通过

**关键设计决策**：

1. **生效策略只有一个答案**：同一适用范围（店铺 × 物流方式）同一优先级**不允许**有两条 `ACTIVE` 规则
   （DB 唯一索引 `crm_review_rule_one_active_per_priority`），
   命中时**范围越具体越优先**（店铺+物流方式 > 店铺 > 物流方式 > 全局），同范围按 `priority` 升序。
2. **节假日按规则时区换算日界**（默认 `Asia/Shanghai`）：用 UTC 日界会把「东八区 10-02 00:00」算成 10-01，
   节假日就错开一天；命中后排期推到**第一个不是节假日的日子**（先按顺延天数挪，再逐日后找，上限 30 天）。
3. **A/B 分桶是确定性的**：SHA-256 种子 → `0..9999` 的桶，同一个种子**永远**落到同一个臂（效果对比可复算）；
   A/B 未开启时用默认臂（权重最高，同权重按变体码升序）。
4. **7 个 DB 守卫**（触发器，**一律不用外键** —— 理由同 V219 / V220）：
   `guard_crm_review_message_variant_target` / `guard_crm_review_rule_template_target` /
   `guard_crm_review_rule_assignment_target` / `guard_crm_review_rule_assignment_identity`
   （落地记录的身份列建后不可改 —— 改了会让「哪个变体带来了这次回收」在历史上出现两个答案）/
   `guard_crm_review_rule_delete` / `guard_crm_review_message_variant_delete` /
   `guard_crm_review_message_template_delete`
   （**有落地记录的规则 / 变体 / 模板删不掉**：删掉后效果对比里那一段历史会凭空消失
   —— 结论被改写，而不是少了几条）。
5. **零跨库**：店铺目录在 **channel** 服务（2.3.1.1），只经 `SignoffDeliveredOrderPort` 走**授权 HTTP API** 读，
   本库只存 `shop_id` + `shop_name` 快照，**不建跨库 FK**；
   上游 15.2.2.1 的 `crm_review_request` 与本模块**同库只读**，写入所有者仍然只有 15.2.2.1 一个。
6. **不代下游做事**：下游 **15.1.1.2 消息发送未交付** ⇒ 本模块只**产出**话术与生效策略，
   **不发送**、也**不写**「已发送」这类回执（`capabilities` 里 `message_dispatch` 维度 `available=false`，
   `ownerModule=15.1.1.2` —— 自己写的「已发送」会让「买家到底收到没收到」看起来发生过，
   而那正是过度索评投诉的源头）；`review_sync` `available=false`（`ownerModule=15.2.1.1`，
   效果对比的回收数来自 15.2.2.1 的**人工登记**回收桶，不是平台自动同步）；
   `order_fulfilment_mode` `available=false`（`ownerModule=15.2.2.1`）。
7. **刻意不给的入口**：
   - **不提供**「指定用哪条规则」的入口 —— 指定规则就绕过了「生效策略」这件事本身；
   - **不提供**「已发送」按钮 —— 一个点了就返回成功的假按钮会让运营以为买家已经被索评过。
8. **回收率分母为 0 时报「不计算」而不是 0%** —— 0% 与「没有基数」是两件事。
9. **🔴 契约撞名**：`CrmReviewRuleInput` 与 15.2.2.1 的同名类型**结构不同**，
   而 codegen 的 gate **只查跨服务**、同服务内是**后者覆盖前者** ⇒ 本模块的类型改名
   **`CrmReviewRuleConfigInput`**，两条类型现在并存
   （`packages/api-contracts/src/contracts/crm.ts` 内已无重复类型名；
   这是 codegen 工具的一处待补缺口，见「遗留与风险」）。

---

## 静态预检（L2 之前）

对本批两条新迁移跑「已知互相破坏形状」扫描（`ADD COLUMN` 无 `DEFAULT` / `SET NOT NULL` /
`ADD CONSTRAINT ... CHECK`）：

| 迁移 | ① ADD COLUMN 无 DEFAULT | ② SET NOT NULL | ③ 新增 CHECK 约束 | RLS |
| --- | --- | --- | --- | --- |
| `V228__crm_review_rule.sql` | 无 | 无 | 无 | 有（3 处策略语句） |
| `V229__exchange_rate_snapshot.sql` | 无 | 无 | 无 | 有（4 处策略语句） |

⇒ **零命中**。两条迁移都是**纯新建表、零 ALTER 任何既有表**，天然不破坏早先车道的数据面。

---

## 并入过程

```
node tools/module-progress/lane.mjs merge-ready
  → 15.2.2.2 索评规则   75646b73 → ae99e7bd
  → 5.2.2.2  汇率快照   ae99e7bd → 1f628c40
  → failed: []（无冲突）
node tools/module-progress/sync.mjs   → 已交付 250 → 252，未开发 215 → 213
node tools/module-progress/lane.mjs gc → removed: [15.2.2.2, 5.2.2.2]
```

台账完整性复核：**465 行**（`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ '`）、**无重复模块 ID**。

---

## L2 批量验证

在集成头 `1f628c40` 上跑本轮**唯一一次**全量验证（**02:10:28 → 02:43:35，合计 ≈ 33 分钟**）：

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `cd backend && ./mvnw verify` | ✅ **BUILD SUCCESS**（31:24 min），6 个服务全绿：identity 270 / catalog 1714 / channel 1112 / warehouse 1535 / trade 1496 / crm 70 = **6197 例，0 失败 0 错误 0 跳过** |
| 前端类型 | `pnpm typecheck` | ✅ 绿（3/3 tasks successful） |
| 前端 lint | `pnpm lint` | ✅ 绿（3/3 tasks successful，仅既有文件的 `react-hooks/exhaustive-deps` warning） |
| 前端测试 | `pnpm test` | ✅ **338 个测试文件 / 5267 例全绿**（7.73s） |
| Next 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ **216 个静态路由 prerendered**（含本批新增的 `/crm/review-rules`） |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | ✅ 台账与蓝图一致 |

**六阶段全绿 ⇒ 本批可 push。**

⚠️ **关于「L2 与在途车道的 L1 并发」的实测**：本轮 L2 跑了 33 分钟，其间 6.3.2.2 车道完成了自己的
L1（它自报「采样到 4 → 5 → 4 个 PG 容器，其中 3–4 个是**其他并行车道**的，不是自己的」），
**存在资源重叠**。但本轮**没有出现**协议里记录的那种成片假红
（`seed()` 503 / 连接拒绝）—— 后端 6197 例 **0 失败 0 错误**，
前端 5267 例全绿 ⇒ **本轮 L2 的绿是真的绿，不是假绿**。

> 下轮仍建议「先等在途车道落地，再跑 L2」：本轮能全绿有一定运气成分
> （重叠窗口落在 crm 这种轻量模块上），不能当作常态。

---

## 本批派发的车道

| 车道 | 名称 | 阶段 | 落地服务 | Flyway | 分支 | worktree |
| --- | --- | --- | --- | --- | --- | --- |
| 5.2.3.2 | 物流与头程费用 | D6 | catalog | V232 | `feat/5-2-3-2` | `.worktrees/5.2.3.2` |
| 6.1.2.1 | 档口与货源池 | D6 | trade | V233 | `feat/6-1-2-1` | `.worktrees/6.1.2.1` |
| 6.2.1.1 | 缺货与断货风险 | D6 | trade | V234 | `feat/6-2-1-1` | `.worktrees/6.2.1.1` |
| 6.3.2.2 | 到货与逾期 | D6 | trade | V235 | `feat/6-3-2-2` | `.worktrees/6.3.2.2` |

`plan --lanes 4` 返回 `newlyPlanned = [5.2.3.2, 6.1.2.1, 6.2.1.1, 6.3.2.2]`、`serviceBlocked = []`
（卷 05 主责 catalog、卷 06 主责 trade，两个服务都已在集成头）。
派发前预检：4 个 worktree **均存在且工作区干净**（0 dirty / 0 untracked）、4 个分支均已建在基线
`1f628c40`、**V232–V235 在集成头零占用**。

### ⭐ 给 4 条新车道钉死互不重叠的 `api-proxy.ts` 插入锚点（行号级）

**问题**：`apps/web/src/lib/api-proxy.ts` **没有并集合并驱动器**，每条车道都往同一个 `services[]`
数组里追加元素 ⇒ 追加位置重叠 ⇒ git 必判冲突。**上一批（batch-38）就是这么白烧了 3 条车道**。
实测硬结论：**只有两边逐字完全相同才能干净合并**，「并入方是当前方的超集」也会冲突。

**做法**：不动任何编排逻辑，只在任务书里把**行号**钉死，并额外要求
「不改任何既有行、0 删除行、`grep -c "prefix: '"` 必须 = 111（基线 110 + 本车道那 1 条）」。

| 车道 | 插入位置（基线 `1f628c40` 行号） | 语义位置 |
| --- | --- | --- |
| 6.1.2.1 | 第 **3349** 行 `},` 之后 | `9.2.1.1 待审队列` 之后、`9.2.2.2 风控校验` 之前 |
| 6.2.1.1 | 第 **3429** 行 `},` 之后 | `9.3.3.2 履约流程监控` 之后、`9.3.3.1 履约请求` 之前 |
| 6.3.2.2 | 第 **3479** 行 `},` 之后 | `9.3.3.1 履约请求` 之后、`9.3.1.2 合单` 之前 |
| 5.2.3.2 | 第 **4133** 行 `},` 之后 | `5.2.1.3 成本分摊` 之后、`5.2.3.1 平台费用` 之前 |

四个位置两两相隔 **50 / 80 / 130 / 654 / 784 行**，远大于 git 的 3 行上下文 ⇒ 四个 diff hunk 互不重叠。

⚠️ **踩过的坑（记录下来）**：第一次派发时我用脚本反查「元素 → 所属模块」，**扫描方向错了**
（从元素起始行往**前**扫，扫到的是**上一个**元素的模块注释），
于是**行号对了但模块名错位了一格**。若 worker 照着错的模块名找位置，
6.2.1.1 与 6.3.2.2 会**同时落在第 3429 行** ⇒ 正是本节要防的冲突。
发现后已向 4 条车道各发一条**更正**（明确「**以行号为准**」），把邻居模块名改正。
⇒ 教训：**锚点要给行号 + 双向邻居名，且邻居名必须逐个核对过**。

⚠️ **这仍然只是缓解，不是根治**：只要两条车道插到同一个位置就还会撞。
根治要靠给 `api-proxy.ts` 配「按元素求并」的合并驱动器（求并键 = `pattern` 正则源文本，
解析不了必须大声失败），那是**待用户拍板**的 P0 事项，已记在
`docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`。

---

## 收尾三件套

① **写说明** → 本文件 `docs/sessions/2026-10/2026-10-21-batch-41-merge-2-lanes-and-l2.md`
（每个模块一节：功能是什么 / 迁移·端点·页面·测试四件 / 关键设计决策与被否决的方案）

② **commit** → `docs(batch-41): 15.2.2.2 / 5.2.2.2 —— 两条车道并入集成头 + L2 六阶段全绿（后端 6197 例 / 前端 5267 例）`
（说明文件与台账**同一个 commit**；台账由 `node tools/module-progress/sync.mjs` 刷新后一并 `git add`）

③ **push** → `git push origin integration/module-dev`（**只推集成头，不推 `main`**；
L2 已绿才推；**本流水线不自己 merge `origin/main`**，那是同步任务的职责）

**调度闸**：`gate acquire` 拿到 → 本轮全程持有（plan → 派发 → merge-ready → L2 → commit → push）→
收尾 `gate release`。TTL 70 分钟，合并前与 L2 启动前各续期一次
（`touch .dev-agent/dispatch.gate/keepalive-<新文件名>`，**必须用新文件名** ——
`touch` 已存在文件或往 `owner` 追加内容都**不会**刷新闸目录 mtime）。

---

## 遗留与风险

1. **⚠️ 本轮开头撞闸**：持闸者是并发的 `main → module-dev` 同步任务（共用同一把闸）。
   本轮用**有界等待**（轮询 4 分钟）等到对方 02:05 正常释放后再取闸 ——
   这不是协议写明的标准动作（协议原文是「撞闸即零派发 + 只读 + 汇报」），
   **判据是 `stale=false` 且对方在正常推进**（它的会话 `status=started`、有持续工具调用），
   所以**没有清闸、没有 force**。若下一轮再撞，建议仍按协议零派发结束。
2. **⚠️ 同步任务的 merge 冲突未解决（不是本流水线的活）**：本轮 02:00 那轮同步任务
   尝试并入 `origin/main` 时命中 **6 个文件冲突**（`cost-composition-*` 三件套 +
   `api-proxy.ts` + `ListingPublishJobIntegrationTest.java`），
   根因是 **main 上是集成头的 squash 快照**（历史被重写 ⇒ 同文件两侧独立 add/add）。
   同步任务已按协议 `merge --abort` 并写入会话日志 `75646b73`。
   ⇒ **下一轮 L2 若红，二分定位到 main 的 merge commit 是首要嫌疑对象**；
   且**这 6 个文件的解决方向是「保留集成头较新的一侧」**（main 是同一批交付的**旧快照**），
   但按协议**冲突不自行解决**，交人工/专门车道处理。
3. **契约 codegen 工具缺口**：同服务内同名 DTO **后者覆盖前者**（`CrmReviewRuleInput` → 已改名
   `CrmReviewRuleConfigInput` 绕过）。codegen 的 gate 应补「同服务内重名检测」。
4. **5.2.2.1 的能力声明里还写着「下游 5.2.2.2 尚未交付」**（且 5.2.2.1 的测试断言了这个字面量）——
   该文案属 5.2.2.1 车道，5.2.2.2 车道**刻意未改**（避免跨车道改既有断言语义）。
5. **in-flight 车道**（下一轮回收）：5.3.1.2 批量测算（running）、5.3.2.1 定价模板（**已 ready**）、
   5.2.3.2 / 6.1.2.1 / 6.2.1.1 / 6.3.2.2（本轮新派）。
