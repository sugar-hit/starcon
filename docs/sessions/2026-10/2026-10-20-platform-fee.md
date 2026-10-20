# 5.2.3.1 平台费用（D6 阶段，L3 锚点 5.2.3）

> 车道 `feat/5-2-3-1`，worktree `.worktrees/5.2.3.1`，基线 `integration/module-dev`（4983a9cc）。
> 迁移 `V226__platform_fee.sql`（catalog 服务，版本号由调度者预分配）。
> 页面路由 `/pricing/fees`（与 5.2.3.2 物流与头程费用共用）。
> 兄弟模块 5.2.3.2 **未交付**，本车道只产出**平台侧费用规则**，不代它开形状。

## 决策摘要

蓝图 §5.2.3.1 的成立与否全在一句加粗上：

> **「规则参数用于事前测算，实际费用来自 12 的结算对账」——两者差额要在利润报表中显性展示为"费用差异"，否则测算永远不准却不知道为什么。**

这句话里有**两个口径**，本模块把它们拆成**两张互不相干的表** + **一条现算的对照**：

| 事实 | 表 | 回答的问题 | 口径 |
| --- | --- | --- | --- |
| 费用规则 | `platform_fee_rule` | 这笔单按规则应该扣多少 | **事前测算** |
| 阶梯档位 | `platform_fee_rule_tier` | 阶梯比例的每一档 | 规则的从属事实 |
| 实际费用 | `platform_fee_actual` | 平台实际扣了多少 | **事后对账** |
| 费用差异 | **不落库**，服务层现算 | 实际 − 按规则测算 | 对照 |

三个决定：

1. **规则与实际费用永不合并成一个字段**。合并之后差异恒等于 0，
   页面看起来「测算永远准」，实际是差异根本无处可藏 —— 而那正是蓝图要消灭的现象。
2. **差异不落库**。落库就会多出第三份可能与前两份不一致的副本；
   `GET /variance` 现算，算不出来就说算不出来。
3. **「算不出来」一律是 `null` + 一句原因，绝不是 0**。三处都要这样：
   整份差异报告（没有实际费用 ⇒ `available=false` + 原因）、
   差异分组（某笔实际费用找不到生效规则 ⇒ `varianceAmount=null`）、
   测算费用项（规则没配 / 阶梯落不进档 ⇒ `applicable=false` + `amount=null`）。

## 关键 trade-off

### 1. 「实际费用来自 12」当前无来源 ⇒ 显式声明缺口，不编假数据

`backend/services/` 下**没有 finance 服务**。蓝图关系句里「实际费用来自 12 的结算对账」
当前**没有来源**。

按仓库既有先例（10.7.1.3 弃置与销毁 V221）：

- 能力声明里 `actual_fee_reconciliation` 维度显式 `available=false` + `ownerModule=12`
  + 一句 impact（缺了它，测算与实际的差额无法解释）；
- **不编一个假的「历史实际费用」数据源去冒充 12 的对账结果** ——
  `platform_fee_actual.source` 的 CHECK 里**根本没有第二个取值**，
  实际费用当前唯一来源是 `MANUAL_OBSERVATION`（人工登记的平台账单观察值）。

这个取值不是妥协，是蓝图**输入句**的直接落地：「平台费用规则配置（按平台/站点/类目）；
**实际费用的历史值用于校准**」。校准本来就要历史观察值。

⇒ 推论：**每笔实际费用必须带销售额基数与数量基数**（`sale_amount` / `quantity`，NOT NULL）。
没有基数就按规则重算不出预期值 ⇒ 差异算不出来 ⇒ 这笔登记毫无意义。

### 2. 零外键，引用校验全用触发器

给既有表（`product_category`）建外键会**打断同服务既有测试的 `TRUNCATE`**（23503
「cannot truncate a table referenced in a foreign key constraint」）——
这正是上一批 batch-37 踩过的坑。照 V80 / V84 / V221 的既有纪律：

- **零个外键**（V226 里一个都没有）；
- 四个触发器顶替：类目归属守卫（内部分类必须来自 INTERNAL 树、平台类目必须来自 PLATFORM 树
  且树归属与规则声明的平台一致）、阶梯所属规则守卫、删规则级联删阶梯、实际费用只追加守卫。

代价：阶梯的引用完整性靠触发器而不是 DB 约束（性能无差别，语义一样）。

### 3. 「同一作用域不得有生效区间重叠」用服务层检查 + 普通唯一索引

用 `EXCLUDE USING gist` + `tstzrange` 才是在 DB 层真正排除重叠，
但它要 `CREATE EXTENSION btree_gist`（一条持续的数据库级变更），
而**本仓没有任何迁移用过它** —— 不为一个约束引入新的扩展依赖。

⇒ 折中：DB 层用**普通唯一索引**（同一作用域 + 同一生效起点）挡掉「完全重复」；
**区间重叠由服务层在写库前查并 409 拒绝**，并说清与哪一条撞了。

代价：绕过服务层直连写入仍可能写出重叠的两条（服务层不是安全边界）。
这是可接受的 —— 与仓库既有做法一致（V32 / V215 的同类约束也有服务层成分）。

### 4. 费率是「小数口径」不是「百分数口径」

`rate numeric(19,8)`，存 `0.15` 表示 15%，并用 CHECK 限 `[0, 1]`。

- 存百分数（15 = 15%）会让「0.15%」和「15%」在同一个列里差 100 倍，而页面上看不出任何异常；
- `[0, 1]` 的 CHECK 让「把 15 当成百分数填进来」当场被拒，而不是变成 1500% 的扣项。

### 5. 上游 2 平台目录：走 HTTP，且**读不到就 503**

照抄 5.2.1.1 的 `CostBasisPort` 三条纪律：`Authorization` 头**原样透传**（租户由 channel
从成员关系解析，**不造 `X-Tenant-Id` 旁路**）；上游取不到抛异常 ⇒ 服务层翻成 503，
**绝不返回空清单冒充「这个租户没有平台」**；端口在测试里由替身满足，
但**生产代码路径永远是真实 HTTP 调用**。

本仓 `catalog` 库**零个外键指向 channel**，也没有平台 / 站点的副本表。

### 6. 类目维度命中祖先链

规则的类目（`internal_category_id` / `platform_category_id`）命中**目标类目的整条祖先链**
（含自己）：「这个类目及其子类目都按这个费率」是平台费率的常态（某类目佣金更高），
只做精确匹配会让子类目漏掉费率。实现走 `product_category.path_ids @> ARRAY[rule_category_id]`。

### 7. 订阅费分摊：基数必须显式登记

分摊额 = `period_total_amount` ÷ `allocation_divisor`。
**分摊基数必须显式登记**，不许服务层取「当前订单数」当分摊基数 ——
那是**当下**的订单数而不是**期间**的，会让同一个月的分摊额随新订单进来而变，
历史利润就没有解释力了。

### 8. 特异度定序是显式的、确定性的

命中多条规则时：**站点 +8 / 内部分类 +4 / 平台类目 +2**，特异度高的赢；
同特异度按生效起点更晚的赢；再同按 id 定序 —— **确定性的，不靠数据库返回顺序**。

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V226__platform_fee.sql`

**后端（catalog）**

- `backend/services/catalog/src/main/java/com/starcon/catalog/PlatformFeeModels.java` — 词表 / 能力声明 / 规则 / 实际 / 测算 / 差异模型
- `.../PlatformFeeRules.java` — **纯算术与校验**（不碰 DB / HTTP）：四种计价形态的算式、订阅费分摊、阶梯选档、特异度
- `.../PlatformFeePort.java` — 上游 2 平台目录的跨服务端口（HTTP 实现 + 不可达异常）
- `.../PlatformFeeConfiguration.java` — 端口装配（没配 `CHANNEL_URL` ⇒ Unwired ⇒ 显式抛错）
- `.../PlatformFeeService.java` — jOOQ 显式 SQL + 测算 + 差异
- `.../PlatformFeeController.java` — 12 个端点

**测试**

- `backend/services/catalog/src/test/java/com/starcon/catalog/PlatformFeeRulesTest.java` — 13 例（纯算术）
- `backend/services/catalog/src/test/java/com/starcon/catalog/PlatformFeeIntegrationTest.java` — 15 例（含租户隔离三断言）

**前端**

- `apps/web/src/components/pricing/platform-fee-model.ts` — 类型 / 文案 / 校验 / 端点
- `apps/web/src/components/pricing/platform-fee-model.test.ts` — 16 例
- `apps/web/src/components/pricing/platform-fee-workspace.tsx` — 真实工作区
- `apps/web/src/app/pricing/fees/page.tsx` — 页面
- `apps/web/src/lib/api-proxy.ts` — **新增一条 `services[]` 元素**（既有条目一字未动，锚点在数组末尾）

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/catalog.openapi.json`
- `packages/api-contracts/src/contracts/catalog.ts`

**台账 / 日志**

- `docs/product/capability-blueprint/module-progress.md`（只改 5.2.3.1 那一行的「状态」「证据」）
- `docs/sessions/2026-10/2026-10-20-platform-fee.md`（本文件）

## 端点清单

| 端点 | 用途 |
| --- | --- |
| `GET /catalog/platform-fees/capabilities` | 逐维度能力声明 + 三张词表 |
| `GET /catalog/platform-fees/directory` | 平台 / 站点目录（上游 2） |
| `GET /catalog/platform-fees/rules` | 规则台账（真分页 + 筛选） |
| `GET /catalog/platform-fees/rules/{uuid}` | 规则明细（+ 阶梯） |
| `POST /catalog/platform-fees/rules` | 新建规则 |
| `PUT /catalog/platform-fees/rules/{uuid}` | 修改规则（乐观锁） |
| `PUT /catalog/platform-fees/rules/{uuid}/tiers` | 整体替换阶梯 |
| `GET /catalog/platform-fees/estimate` | **测算时的费用项**（蓝图输出句） |
| `GET /catalog/platform-fees/actuals` | 实际费用台账（真分页） |
| `POST /catalog/platform-fees/actuals` | 登记实际费用 |
| `POST /catalog/platform-fees/actuals/{uuid}/void` | 作废（理由必填） |
| `GET /catalog/platform-fees/variance` | **费用差异**（实际 vs 按规则测算） |

## 验证

```
cd backend && ./mvnw -pl services/catalog -am test -Dtest='PlatformFee*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
  → Tests run: 28, Failures: 0, Errors: 0   (集成 15 + 单元 13)

cd backend && ./mvnw -q compile          → BUILD SUCCESS
pnpm typecheck                            → 3 successful, 3 total
pnpm lint                                 → 3 successful, 3 total
pnpm vitest run apps/web/src/app/api      → 106 文件 / 625 例全绿（api-proxy 全量 allowlist 回归）
pnpm vitest run apps/web/src/components/pricing/platform-fee-model.test.ts → 16 例全绿
```

api-proxy 白名单自检脚本 `.tmp/check-proxy-allowlist.mjs`：抽到 **78** 条正则
（既有 77 + 本模块 1），本模块的 9 个形状在**全部 77 条既有正则里零命中**。

## 后续待办

1. **12 财务（finance 服务）落地后**：把结算对账结果接进 `platform_fee_actual` ——
   那时 `source` 要加第二个取值并由 **12 的写入端点**写入（写入所有权在 12，本模块不抢）。
   能力声明的 `actual_fee_reconciliation` 维度随之翻成 `available=true`。
2. **5.3.1.1 利润测算器**（另一条车道）消费本模块的 `GET /estimate` 费用项。
   契约里 `FeeEstimate` / `FeeEstimateItem` 已是生成物，直接引。
3. **13 利润**消费 `GET /variance` 的费用差异。注意：**13 落地前差额只能在本模块页面里看**。
4. **5.2.3.2 物流与头程费用**（未交付）：与本模块**不共用表、不共用端点、不共用口径**，
   在 `PLATFORM_FEE_SECTIONS` 里新增一个分区即可，不重写本模块的页面骨架。
5. **14 广告**消费平台费用规则（广告费与平台费的叠加口径在 14 侧）。
6. 9.6.1.2 代发差价与对账里那条「平台费未登记 = 无法判定」的判据，
   在 5.2.3.1 交付后可以把 `TENANT_MAINTAINED` 换成 `MODULE_5_2_3_1`（见
   `docs/sessions/2026-10/2026-10-15-dropship-margin-reconciliation.md` 的后续待办）。
7. 可选（未做，不阻塞）：「费率历史版本」—— 本模块的规则是**可改**的（乐观锁），
   改一次费率不追加历史版本。若将来 13 利润要复算历史，需要像 5.2.1.1 那样加快照表。
