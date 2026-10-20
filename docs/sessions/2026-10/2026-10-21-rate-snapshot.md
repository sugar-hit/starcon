# 2026-10-21: 5.2.2.2 汇率快照 —— 在单据/定价生效时冻结所用汇率

> 车道 `feat/5-2-2-2`，worktree `.worktrees/5.2.2.2`，基线 `integration/module-dev`（`5ec9181a`）。
> 能力蓝图卷 05「定价与价目」L3 锚点 5.2.2，阶段 D6。

## 摘要

本车道交付能力蓝图 5.2.2.2「汇率快照」：在单据/定价生效时**冻结**所用汇率，
保留换算过程（原价、汇率、舍入方式、目标币金额），并给出**利润复算**能力。

蓝图加粗句是本模块唯一不可让步的一条：

> **汇率快照是利润可追溯的前提**：今天汇率变了不能让上个月的利润报表数字跟着变。

这句话在代码里有**三个**物理落点（缺一个都不算数）：

1. **整行冻结** —— 汇率值、方向、生效日、来源、舍入方式、目标币金额全部冗余冻结在快照行里，
   读快照**不 JOIN 汇率表取值**。汇率表改了、来源被停用了，历史快照一个数字都不动。
2. **只追加** —— GRANT 只给 `SELECT/INSERT`（与 V225 的拉取日志同一手法），
   外加 `guard_exchange_rate_snapshot_immutable` 触发器挡住 UPDATE —— **连表 owner 直连 SQL 也改不动**。
3. **同一 单据行 + 同一 币种对 只能有一条**（UNIQUE）—— 要更正就换单据行标识另冻一条，
   原快照原样保留，历史不被抹掉。

上游是 5.2.2.1（汇率查询，`V225__exchange_rate.sql`，本车道已在集成头里确认存在）；
下游是 09 订单、12 财务、13 利润 —— 它们**读快照复算**利润，而不是自己再取一次汇率。

## 决策摘要

### 快照只冻结「已登记」的汇率事实（`rate_id` NOT NULL）

备选方案是允许「单据自带汇率」（`rate_id` 可空）。**没有采用**：
那样每条快照的汇率可能没有出处，复算就成了「拿一个说不清来源的数去对账」。

采用的口径：**想冻结一条还没登记的汇率，先在 5.2.2.1 登记它**。
好处是「这条快照的汇率是哪来的」永远答得上来，而且可以用触发器在 DB 层钉死。

### 冻结的是「哪一条」汇率，而不只是「一个汇率」

快照行同时记 `rate_id` / `rate_base_currency` / `rate_quote_currency` / `rate` /
`conversion_direction`（`BASE_TO_QUOTE` 乘 · `QUOTE_TO_BASE` 除）/
`source_currency` → `target_currency`，两条 CHECK 保证换算的两侧正好是这条汇率的两侧。

`guard_exchange_rate_snapshot_rate` 触发器逐项核对冻结值与被引用的汇率行**逐字相同** ——
「快照上写着一个汇率表里根本不存在的汇率」**直连 SQL 也写不进来**。

反向汇率仍然**只能推导、不能手工再录一条方向相反的**（V225 第二节的纪律在本模块继续生效）：
`QUOTE_TO_BASE` 时用的是 1/rate，除法由服务层按冻结的 rate 现算，算出来的目标币金额与 rate 一起冻结。

### 舍入方式必须落在数字上

`rounding_rule_code` + `rounding_mode` + `rounding_scale` + 目标币金额**一起冻结** ⇒
复算不需要回查规则表，改规则不动任何历史快照（这正是 5.2.1.3「分摊规则变更会导致历史利润变化」
那条教训在汇率侧的对应物：**配置可改，历史不可改**）。

规则解析顺序是**先具体后通用两步**（先找适用币种正好等于目标币种的规则，找不到再找通用兜底规则），
不是一把抓 priority —— 否则通用规则会盖掉日元零位规则，**日元按两位小数入账是真实存在的账务事故**。
没有可用规则时**明确拒绝冻结**（400），不偷偷按默认口径舍入。

### 舍入规则是配置、快照是事实：授权刻意不同

`exchange_rate_rounding_rule` 给 `SELECT/INSERT/UPDATE/DELETE`（配置本来就要能改），
`exchange_rate_snapshot` 只给 `SELECT/INSERT`（事实不能改写）。

### 跨服务纪律

09 订单 / 12 财务的单据不在本库 ⇒ 快照只用逻辑引用
（`document_type` + `document_id` + 单据号）定位，**不建跨库 FK / 不跨服务读表**。
本迁移**纯新建、零 ALTER 任何既有表**，也不碰 V225 的 `exchange_rate` / `exchange_rate_source`。

### 前端：分区结构 + 数据驱动断言

- `RATE_SECTIONS` 里 5.2.2.2 翻成**已交付**，`description` 去掉交付状态字面量。
- 共享测试 `exchange-rate-model.test.ts` 的分区断言改成**数据驱动**：
  断言「该分区 `delivered` 为 true」+「该分区写出了自己的职责（标题 / 描述含『冻结』）」，
  **不**断言「未交付」三个字 —— 兄弟模块一旦交付那个字面量就会消失，写死会让别的车道一交付就红。
- 快照模块自己的模型 / 工作区 / 测试放在**新文件**里
  （`exchange-rate-snapshot-model.ts` / `-workspace.tsx` / `-model.test.ts`），
  对共享文件只做**追加式**改动（一个 import + 一个 `<ExchangeRateSnapshotWorkspace />` + 一处分区描述）。

## 关键 trade-off

- **为什么不新增一张「换算明细表」而把换算过程摊到多张表**：蓝图要求「保留换算过程（原价、汇率、舍入方式、目标币金额）」，
  复算要能**一次读到全部四项**。摊到多张表就多一次 JOIN，复算也就多一处可能对不上的地方。
  整行冻结 + 冗余复制（而不是 JOIN 汇率表取值）是本模块的核心取舍。
- **为什么 `rate_id` 用触发器校验而不用外键**：引用校验的失败原因要可解释（「冻结的汇率与汇率记录不一致」…），
  外键只会给一条语焉不详的数据库报错；且 batch-37 有车道给既有表加外键打破了同服务既有测试的 TRUNCATE。
- **为什么快照的 UNIQUE 键含 `line_key`**：同一张单据里同一个币种对可能有多行（订单的多个 SKU 行），
  没有行标识就分不清是哪一行的换算。
- **为什么不给快照开 DELETE**：append-only 是「历史不被抹掉」的结构保证，不是应用层的君子协定。
  代价是测试之间不能删数据 —— 用例改用唯一 `document_id` 隔开。

## 涉及文件

### 后端（catalog 服务）

- `backend/services/catalog/src/main/resources/db/migration/V229__exchange_rate_snapshot.sql`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ExchangeRateSnapshotModels.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ExchangeRateSnapshotRules.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ExchangeRateSnapshotService.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ExchangeRateSnapshotController.java`（新增）
- `backend/services/catalog/src/test/java/com/starcon/catalog/ExchangeRateSnapshotIntegrationTest.java`（新增，19 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/ExchangeRateSnapshotTenancyIntegrationTest.java`（新增，1 例 · 三断言）
- `backend/services/catalog/src/test/java/com/starcon/catalog/ExchangeRateSnapshotRulesTest.java`（新增，14 例）

### 前端

- `apps/web/src/components/pricing/exchange-rate-snapshot-model.ts`（新增）
- `apps/web/src/components/pricing/exchange-rate-snapshot-model.test.ts`（新增）
- `apps/web/src/components/pricing/exchange-rate-snapshot-workspace.tsx`（新增）
- `apps/web/src/components/pricing/exchange-rate-model.ts`（改：5.2.2.2 分区翻成已交付）
- `apps/web/src/components/pricing/exchange-rate-workspace.tsx`（改：挂载快照分区）
- `apps/web/src/components/pricing/exchange-rate-model.test.ts`（改：分区断言数据驱动）
- `apps/web/src/app/api/catalog/exchange-rate-snapshot-allowlist.test.ts`（新增）
- `apps/web/src/lib/api-proxy.ts`（改：**新增一条** `services[]` 元素，108 → 109，既有条目一字未动）

### 契约与台账

- `packages/api-contracts/src/catalog.openapi.json` / `packages/api-contracts/src/contracts/catalog.ts`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（只改 5.2.2.2 一行的「状态」「证据」两格）

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile`（绿）
- L1 后端：`./mvnw -pl services/catalog -am test -Dtest='ExchangeRateSnapshot*'` ⇒ **34 例全绿**
  （Integration 19 + Tenancy 1 + Rules 14）
- L0 前端：`pnpm typecheck`（绿）
- L1 前端：`pnpm vitest run apps/web/src/components/pricing/exchange-rate-model.test.ts
  apps/web/src/components/pricing/exchange-rate-snapshot-model.test.ts apps/web/src/app/api/catalog` ⇒ **114 例全绿**
- 全量 allowlist 回归（改了 api-proxy.ts 必跑）：`pnpm vitest run apps/web/src/app/api` ⇒ **661 例全绿**
- `pnpm lint`（绿；只有既有模块的 react-hooks 警告，本车道文件无告警）
- api-proxy 自检：`grep -c "prefix: '"` = **109**；`git diff -- apps/web/src/lib/api-proxy.ts` = **只增 30 行、0 删除行**

## 后续待办

1. **⚠️ 跨车道遗留（不属本车道文件）**：5.2.2.1 的 `ExchangeRateRules.DOWNSTREAM_NOTE` 仍写着
   「下游 5.2.2.2 汇率快照**尚未交付**」，而 5.2.2.1 的 `ExchangeRateIntegrationTest` 断言了这个字面量。
   本车道**没有**改它（那是 5.2.2.1 车道与它的既有断言语义），
   下次 5.2.2.1 触及时应把那句文案与对应断言一起改成「已交付」。
   前端侧已无此问题：5.2.2.1 的 `downstreamNote` 是后端下发的字符串，本车道未改后端常量。
2. **契约文件存在既有漂移**：`packages/api-contracts/src/contracts/catalog.ts` 在集成头里
   **不是**当前 codegen 的排序输出（历史车道是按「追加」并进去的，且没有 codegen 头注释）。
   本车道按任务书要求跑了 `tools/codegen/catalog-contracts.py`，因此该文件出现了一次**纯重排 + 新增 12 个 DTO** 的 diff。
   这不是本车道引入的问题（用集成头的 spec 重跑 codegen 同样会重排，已实测），
   并入时由仓库的「按类型名求并」驱动器处理。
3. 12 财务（finance 服务）在本仓仍不存在：快照只冻结与复算，**不代 12 做账**。
   finance 落地后经授权 API / event 衔接（见 V229 迁移第一节的归属说明）。
