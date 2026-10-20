# 2026-10-21: 5.2.3.2 物流与头程费用 —— 渠道计费规则 + 附加费 + 头程费用结构

> 车道 `feat/5-2-3-2`，worktree `.worktrees/5.2.3.2`，基线 `integration/module-dev`（`1f628c40`）。
> 能力蓝图卷 05「定价与价目」L3 锚点 5.2.3，阶段 D6。

## 摘要

本车道交付能力蓝图 5.2.3.2「物流与头程费用」：维护物流渠道的计费规则
（按重量 / 体积 / 件数、燃油附加、旺季附加、偏远附加）与头程费用结构，用于测算运费占成本比。

上游 = 10 物流与头程（**卷级引用**）；下游 = 5.3.1.1 利润测算器。

落地为 catalog 服务里的 **6 张新表 + 19 个端点 + `/pricing/fees` 的物流与头程费用分区**：

- `logistics_freight_rule` —— 渠道 / 承运商 / 运输方式 / 计费口径**四维度**的计费规则
  （含体积重换算系数、最低收费、版本链 `version_no / supersedes_id / superseded_by_id`）
- `logistics_freight_rule_tier` —— 阶梯（`FLAT_PER_TIER` 整档一口价 / `FIRST_PLUS_ADDITIONAL` 首段加续段）
- `logistics_freight_surcharge` —— 附加费（燃油 / 旺季 / 偏远；`PERCENT_OF_BASE_FREIGHT` /
  `FIXED_PER_ORDER` / `FIXED_PER_UNIT`）
- `logistics_head_freight_structure` + `logistics_head_freight_component` —— 头程费用结构
  （结构头 + 成本项；成本项各有自己的币种与计价基数）
- `logistics_freight_actual` —— 实际运费（只追加 + 作废留理由）

## 决策摘要

### 规则与实际是两套独立数据，差异现算不落库

渠道计费规则（**事前测算**）与实际运费（**事后核算**）在页面上是两块**独立区域**，
各自带自己的来源标注。运费差异由**服务层现算**，**不落库** ——
落库就会多出第三份可能与前两份不一致的副本，反而制造第三个真相。

算不出来时（没有实际运费 / 没有生效规则 / 规则不适用 / 币种不一致）返回
**`null` + 原因**，**绝不返回 0** 冒充「没有差异」。前端模型层有对应断言：
「差异算不出来时明说算不出来，不显示 0」。

### 不可变优先：改规则 = 新增版本 + 旧版本让位

已生效的规则与档位**不允许被改写**。触发器
`to_jsonb(NEW) - 'status' - 'effective_to' - 'superseded_by_id' - 'disable_reason'`
整体比较后放行 —— 只允许状态流转，**任何业务字段改写都直接被 DB 拒绝**。
用 `to_jsonb` 整体比较而不是逐字段列举，是为了让守卫**天然覆盖以后新增的列**。

这一条**刻意偏离** 5.2.3.1 的乐观锁范式：乐观锁是「改行」，本模块要的是「不许改行」。

### 「用哪一条」由生效区间决定，不由 status 决定

被取代的版本**保留自己那段闭区间**（`effective_to` 关在新版本起点），
历史测算仍读到**当时那版费率**。若按 status 过滤，被取代的规则会从历史区间里消失，
「上个月按多少钱算的运费」就再也答不出来了。

代价是 CHECK 从 `effective_to > effective_from` **放宽为 `>=`**
（空区间 = 从未生效，停用一条还没生效的规则就是这个状态）。

### 附加费「同一时刻同一类型只能命中一条」由 DB 保证

燃油 / 旺季 / 偏远三种类型**可以同时命中**，但**同类型**在同一时刻只能有一条生效
（区间重叠判定，用触发器而不是 EXCLUDE 约束 —— 避免额外依赖 `btree_gist`）。
附加费一律加在**基础运费**上，**不含其它附加费**（防止燃油按含旺季的基数算出的连环重复）。

### 头程与渠道派送是两笔不同的钱

头程费用结构**独立成表**（结构头 + 成本项），不塞进渠道计费规则。
成本项可**不同币种**、各有计价基数（`PER_SHIPMENT` / `PER_CBM` / `PER_CONTAINER` / `PER_KG`）。
**混币时合计是 `null` + 原因** —— 跨币种绝不相加。

### 口径歧义要显式指定，不安静挑一个

同一渠道在最具体层上有多个口径的生效规则时，测算**返回「算不出来」+ 原因**，
不自己挑一条当默认。安静挑一个会让页面上看不出任何异常。

### 实际运费只有一种来源，且不冒充上游

能力声明把「上游 10 的实际运费对账」标成 `available=false` + `ownerModule=10`。
依据：channel 的 `freight_quote.cost_basis` 恒为 `RULE_ESTIMATE` ——
那是按规则估的**报价**，不是物流商的**结算单**。

因此本仓实际运费**只允许 `MANUAL_OBSERVATION`（人工登记的物流商账单观察值）**一种来源，
页面上原样显示来源，不写成「结算对账结果」。

### 零跨服务 HTTP 端口

渠道 / 承运商 / 运输方式只存**逻辑引用码 + 名称快照**，
**不建跨库 FK、不建共享视图、不留渠道目录副本**（AGENTS.md 禁止跨服务数据库访问）。
这与 5.2.3.1 的 `PlatformFeePort` **刻意不同** —— 上游 10 是卷级引用，
不是已经落地可调的端口；集成测试因此只需 identity 替身，无 channel 替身。

## 关键 trade-off

- **为什么用 `IF FOUND` 而不是 `clash IS NOT NULL` 判断作用域重叠**：
  实测踩坑 —— `SELECT ... INTO clash` 明明命中了行（同样 WHERE 条件的 count = 1），
  但 `clash IS NOT NULL` 在未定型 `record` 变量上**判不出**，守卫形同虚设。
  改用 `IF FOUND` 后才真正生效。**这条教训已写进迁移注释**，避免下次有人「优化」回去。
- **为什么阶梯表 / 成本项表给 `DELETE` 权限之外还要触发器**：
  GRANT 给 `SELECT/INSERT/UPDATE`（不给 DELETE），「不可改写」由**触发器明确执行并被测试证明**，
  而不是 privilege 缺失的副作用 —— 后者换个连接就绕过了。
- **为什么差异不落库而现算**：见上。代价是差异每次读都要重算一次基础运费，
  但运费规则量级小，这个代价换「只有两个真相」很划算。
- **为什么 DTO 改名而不是写碰撞白名单**：catalog 的 `FreightCapabilityDimension`
  与 channel 的同名 DTO **结构不同**，codegen 会在落盘前非零退出。
  codegen 明确推荐改名（白名单是给「结构完全相同」的情形兜底的），
  故改名 `LogisticsFreightCapabilityDimension`，**不写** `contract-collision-allowlist.json`。
- **为什么 api-proxy 用独立顶级段 `logistics-freight/...`**：
  实测与既有 **83 条**正则**零命中**（含跳过注释行的多行正则）。
  自己正则的外层分组**不带 `?`** —— 裸路径必须 404，放行它们比 404 更糟。

## 涉及文件

### 后端（catalog 服务）

- `backend/services/catalog/src/main/resources/db/migration/V232__logistics_freight_rule.sql`（新增：6 张表 + 触发器 + RLS + GRANT）
- `backend/services/catalog/src/main/java/com/starcon/catalog/LogisticsFreightModels.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/LogisticsFreightRules.java`（新增：纯算术与校验）
- `backend/services/catalog/src/main/java/com/starcon/catalog/LogisticsFreightService.java`（新增：jOOQ 显式 SQL）
- `backend/services/catalog/src/main/java/com/starcon/catalog/LogisticsFreightController.java`（新增：19 个端点）
- `backend/services/catalog/src/test/java/com/starcon/catalog/LogisticsFreightRulesTest.java`（新增，24 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/LogisticsFreightRuleIntegrationTest.java`（新增，16 例）

### 前端

- `apps/web/src/components/pricing/logistics-freight-model.ts`（新增：类型 + 文案 + 校验 + API 调用）
- `apps/web/src/components/pricing/logistics-freight-workspace.tsx`（新增：九个工作区，三态齐全）
- `apps/web/src/components/pricing/logistics-freight-model.test.ts`（新增，17 例）
- `apps/web/src/app/api/catalog/logistics-freight-allowlist.test.ts`（新增，5 例）
- `apps/web/src/components/pricing/platform-fee-model.ts`（改：`logistics-fee` 分区 `delivered: false → true`）
- `apps/web/src/components/pricing/platform-fee-workspace.tsx`（改：+1 import + 1 处 `<LogisticsFreightWorkspace />`）
- `apps/web/src/components/pricing/platform-fee-model.test.ts`（改：分区断言改**数据驱动**，
  断言 `delivered === true` + 断言写出自己的职责，**不写死「未交付」字面量**）
- `apps/web/src/lib/api-proxy.ts`（改：**新增一条** `logistics-freight` 元素，110 → 111，
  追加在 5.2.1.3 之后、5.2.3.1 之前，既有条目一字未动）

### 契约与台账

- `packages/api-contracts/src/catalog.openapi.json` / `packages/api-contracts/src/contracts/catalog.ts`
  （codegen 产物：catalog 纯新增 **15 条路径 + 32 个 DTO**，其余 5 个服务的契约逐字节不变）
- `docs/product/capability-blueprint/module-progress.md`（只改 5.2.3.2 一行的「状态」「证据」两格）

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` ⇒ **退出 0**
- L1 后端：`./mvnw -pl services/catalog -am test -Dtest='LogisticsFreight*'` ⇒ **40 例全绿**
  （Integration 16 + Rules 24），`Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`
- L0 前端：`pnpm typecheck` ⇒ **3/3 通过**
- L1 前端：`pnpm vitest run apps/web/src/components/pricing/logistics-freight-model.test.ts
apps/web/src/components/pricing/platform-fee-model.test.ts` ⇒ **33 例全绿**
  （本车道 17 + 共享分区 11 + …… 见下）
- 本车道测试文件单独跑：`logistics-freight-model.test.ts` 17 例 +
  `logistics-freight-allowlist.test.ts` 5 例 ⇒ **22 例全绿**
- **全量 allowlist 回归（改了 api-proxy.ts 必跑）**：`pnpm vitest run apps/web/src/app/api/catalog`
  ⇒ **9 文件 79 例全绿**
- `pnpm lint` ⇒ 退出 0（6 条 react-hooks 警告**全在既有文件**，本车道文件零告警）
- api-proxy 自检：`grep -c "prefix: '"` = **111**；
  `git diff --numstat` = **56 增 0 删**（既有行一字未动）

### 多租户三断言（集成测试实跑，Testcontainers 真实 PostgreSQL 16）

`spring.datasource.hikari.maximum-pool-size=1`（单连接池）：

1. **两租户互不可见** —— 列表 / 明细 / 直查都隔离
2. **伪造 `X-Tenant-Id` 头无效** —— 租户只来自已认证成员关系；
   伪造头下的**写入仍落在调用者自己名下**（不只断言对方看不见）
3. **连接池归还后不串租户** —— 单连接 A→B 交替多轮

### verify 期间的 PG 容器

L1 运行期间 `postgres:16-alpine` 容器**峰值 1 个**（t≈50s 出现，测试结束即回落为 0），
**ryuk 已回收**。基线环境另有 1 个非本车道的 postgres 容器。

## 后续待办

1. **契约：trade 快照的浮点写法漂移已按仓库先例回退**。
   `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 会把
   `packages/api-contracts/src/trade.openapi.json` 一并重生成，差异只有
   `1` ↔ `1.0` 这种**浮点写法**（24 处数值约束），无语义变化。
   按集成头 `33667346`（「其余五个服务的快照沿用集成头已提交的版本，因为它们的源码本次没有变化」）
   的既定先例，本车道**回退了 trade 快照**，只提交 catalog 的两份。
   下次 trade 源码变动时由该车道一并重生成。
2. **5.3.1.1 利润测算器已交付但尚未消费本模块输出** —— 它当前读的是**手工假设**。
   下游要接真实费率，需要 5.3.1.1 侧主动接入本模块的测算接口（本车道不代改）。
3. **实际运费对账依赖 10 物流与头程交付真实结算事实** —— 在那之前，
   本模块的实际运费只有 `MANUAL_OBSERVATION` 一种来源，能力声明里
   `actual_freight_reconciliation` 维度保持 `available=false` + `ownerModule=10`。
4. **渠道 / 承运商 / 运输方式的名称快照会过期** —— 本模块不建渠道目录副本（禁止跨库 FK），
   所以改名后历史规则里存的是**当时的名称快照**。10 落地后应改为按引用码实时解析名称。
