# 2026-11-02: 5.1.2.1 优先级与冲突检测（价目表冲突守门员）

## 摘要

蓝图 05「定价与价目」→ 5.1「价目表」→ 5.1.2「适用范围」→ 5.1.2.1 落地。

车道分支 `feat/5-1-2-1`，迁移版本号由调度者预分配为 **V434**
（`backend/services/catalog/src/main/resources/db/migration/V434__price_list_priority.sql`）。

蓝图原文：

> **职责**：定义多张价目表的适用优先级，保存时检测冲突（同 SKU + 同店铺 + 同站点 + 同时段生效的多个价目），冲突即拒绝并列出冲突明细。
> **输入**：适用范围配置（法人/店铺/站点）；现有生效价目集合。
> **输出**：冲突检测结果；最终的生效价目解析规则。
> **关系**：上游 = 5.1.1.1；下游 = 5.3.1.1、5.4 调价。**这是价格正确性的守门员**：允许多价目但必须能唯一解析出"这个 SKU 在这个店铺此刻该卖多少"。

交付物：一张新表、7 个端点（`/catalog/price-lists/**`）、一个真实工作区页面
（`/pricing/lists/priority`）、32 个后端测试 + 19 个前端测试 + 1 个 api-proxy allowlist 回归测试。

## 决策 / 结论

### 1. 🔴 「冲突」的判定口径：同优先级才是阻断级冲突

蓝图说「允许多价目但必须能唯一解析出」⇒ **多张价目表本身不是错误**，不可唯一解析才是。
所以冲突分两级：

- **阻断级**（拒绝保存）：两张表覆盖同一（店铺, 站点, 法人）上下文、生效区间重叠、
  且**两边在该上下文的有效优先级相同** ⇒ 这一刻无法确定该用哪张表。
- **不阻断**（仍然报出来）：区间重叠但优先级不同（可解析）；或有一方未配置优先级
  （它压根不参与解析）；或有一方已停用（当前不参与定价，但重新启用前必须先排开）。

这样「冲突即拒绝」拦住的正是**会让价格变得不确定**的那一类，而不是把「多张价目表」本身
当成错误 —— 后者会让 5.1.1.1 根本无法建表。

### 2. 🔴 优先级规则的形状：`(价目表, 上下文)` + 一条全局默认

- 一行 = 「这张价目表在某个上下文里排第几」；上下文沿用 5.1.1.1 的词表（法人 / 店铺 / 站点）
  外加一个 `GLOBAL`（该表的默认优先级，`scope_ref` 固定写 `*`，DB 层 CHECK 绑定）。
- **数字越小优先级越高**（1 最高）。
- **specificity 顺序：站点 > 店铺 > 法人 > 全局** —— 租户可以给某张表在某个具体上下文里
  单独排位，但**绝不能**出现两条同样具体的规则同时命中（那会让「哪条生效」变成猜谜）
  ⇒ `UNIQUE(tenant_id, price_list_id, scope_type, scope_ref)`。
- 每张参与解析的表**必须**有一条 GLOBAL：服务层强制（首次保存必须是 GLOBAL，之后只能改不能删）。
  没有它，那张表在某些上下文里就没有优先级 ⇒ 不参与解析。

### 3. 🔴 冲突检测走**真实 SQL**，区间重叠是数据库谓词

生效区间是半开区间 `[valid_from, valid_to)`，重叠判据写成 SQL：

```sql
a.valid_from < coalesce(b.valid_to,'infinity')
and b.valid_from < coalesce(a.valid_to,'infinity')
```

⇒ **首尾相接的两张表不算重叠**（边界值有测试钉死），而且**不**把数据捞到内存里逐对比较。

适用范围交叠靠「（店铺, 站点）上下文全集」判定。这个全集来自 channel 的店铺目录
（店铺 → 它名下的站点）与 identity 的法人档案（店铺 → 法人），
**跨服务只走授权只读端点**（catalog 库里没有它们的副本，AGENTS.md 禁止跨库 JOIN / FK）。
有了它，「按店铺的表」与「按站点的表」撞上时能被发现 —— 只比编码相等会漏掉这一类。

### 4. 🔴 守门员挂在 5.1.1.1 的保存事务**之内**

蓝图把 5.1.2.1 写成本表的**下游守门员**（两者互为上游会死锁）。
「保存时校验冲突」要真的生效，唯一正确的落点是：**适用范围写进库、事务还没提交的那一刻**跑检测。
⇒ 在 `PriceListService.save` 的两个分支（新建 / 修改）末尾各加一行
`priorityGate.assertNoBlockingConflict(id, bearer)`。

- 调用点在事务内 ⇒ 检测读到的就是**即将生效**的状态；
- 抛出 ⇒ 整个保存事务回滚 ⇒ 用户看到的是「**什么都没保存**」，不是「保存了但带冲突标记」。
- **本车道对 5.1.1.1 的改动只有这两行调用 + 构造器多一个依赖**，没有动它的表 / 列 / 端点形状 / 页面。

### 5. 🔴 未配置优先级 ⇒ 不参与解析（而不是「价格是 0」）

没排进这场赛跑的价目表不参与解析，但它**仍然出现在冲突报告里**并注明原因 ——
静默吞掉它的话，运营会以为自己的表在生效。
本租户一张优先级规则都没有时，守门员**直接返回**（连上游目录都不读）
⇒ 5.1.1.1 的建表流程完全不受影响（它的 37 个测试原样绿）。

### 6. 🔴 同优先级 ⇒ 不可解析，绝不随机取一条

`POST /catalog/price-lists/resolve` 返回**一条**确定的生效价目 + **解析依据**
（命中了哪条优先级规则：数字 + 规则上下文 + 规则取值 + 一句解释）。
最高优先级被多条并列 ⇒ **409 + 并列候选清单**，前端把候选**全部**列出来让用户选
（蓝图 5.1.1.2 关系句：「同优先级多条有效价目必须报错让用户选，不随机取」的同款落法）。

### 7. 🔴 409 出口单独一个 advice，不往共用的 `ApiErrorHandler` 里塞

`ApiErrorHandler` 是全服务共用的文件，并行车道都在改它 ⇒ 本车道另起
`PriceListPriorityExceptionHandler`（只处理本模块的两个异常类型）。
冲突明细整份挂在 ProblemDetail 的 `conflicts` 属性上退回前端
（与 3.1.3.2 的 `conflicts`、3.6.2.1 的 `candidates` 同一手法）。

## 🔴 已知边界（如实记录，不假装覆盖）

**「同 SKU」这一维度未覆盖。** 判据「同 SKU」要知道哪些 SKU 落在哪张价目表里 ——
那是价目项（`price_list_item`，**5.1.1.2 的写入所有权**）的事实，而 **5.1.1.2 尚未交付**
（迁移 V434 落地时该表不存在，grep 只在 V431 的注释与 5.1.1.1 测试的临时表里出现）。

⇒ 本模块实现的是**价目级**冲突检测（两张价目表覆盖同一店铺 / 站点 / 法人且生效区间重叠）。
「同 SKU」这一维度在**三处**如实标注「未覆盖」及原因：
能力声明（`dimensionCoverage` 里 `SKU.covered=false` + 原因）、冲突报告（`skuDimensionNote`）、
解析结果（`skuCoverage`）—— **不是「没有冲突」，是「判定依据还没交付」**。
「这个 SKU 此刻卖多少」里的**金额**同理未覆盖（价目金额属价目项，本模块不落任何金额列
⇒ 本仓这条线上没有浮点记账面）。
5.1.1.2 交付后由它自己的写入路径补齐 SKU 维度，本模块**不改**它的表结构与写入逻辑。

## 关键 trade-off

| 选择 | 代价 | 为什么这么选 |
| --- | --- | --- |
| 冲突分级（阻断 / 不阻断） | 「多张价目表重叠」本身不再报错 | 蓝图明说「允许多价目但必须能唯一解析」⇒ 拦「不可解析」才对得上蓝图 |
| 上下文全集走跨服务 HTTP | 冲突检测需要 channel / identity 可达（读不到 ⇒ 503，不返回空结果冒充「没有冲突」） | catalog 库里不能有 channel / identity 的副本（模块边界纪律） |
| specificity 顺序硬编码在代码里 | 租户不能改顺序 | 顺序是「语义」不是「配置」；租户可配的是**每张表排第几**（那才是数据） |
| 守门员调用点放在 5.1.1.1 的服务里 | 动了 5.1.1.1 的文件（两行调用 + 构造器） | 「保存时校验冲突」只有这一个落点能真的生效；表 / 列 / 端点形状 / 页面一字未动 |
| 前端新开 `/pricing/lists/priority` 而不是改 5.1.1.1 的页面 | 入口不在 `/pricing/lists` 根上 | 5.1.1.2 是并行车道且拥有同一个价目工作区；改同一个文件必然撞车 |
| 409 用独立 advice | 多一个文件 | `ApiErrorHandler` 是多车道共用文件 |

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V434__price_list_priority.sql`

**后端（catalog 服务）**

- `PriceListPriorityModels.java` — 传输模型（规则 / 冲突明细 / 维度覆盖 / 解析结果）
- `PriceListPriorityRules.java` — 纯规则（归一化 / specificity / 并列判定 / 维度覆盖说明）
- `PriceListPriorityService.java` — jOOQ 显式 SQL（冲突检测 / 解析）+ 守门员
- `PriceListPriorityController.java` — 7 个端点
- `PriceListPriorityConflictException.java` / `PriceListPriorityAmbiguityException.java`
- `PriceListPriorityExceptionHandler.java` — 独立 409 advice
- `PriceListService.java` — **改动**：构造器多一个依赖 + 两个保存分支各加一行守门员调用

**后端测试**

- `PriceListPriorityRulesTest.java`（9 例）
- `PriceListPriorityIntegrationTest.java`（18 例）
- `PriceListPriorityTenancyIntegrationTest.java`（5 例，多租户三断言）

**前端**

- `apps/web/src/components/pricing/price-list-priority-model.ts` + `.test.ts`（19 例）
- `apps/web/src/components/pricing/price-list-priority-workspace.tsx`
- `apps/web/src/app/pricing/lists/priority/page.tsx`
- `apps/web/src/app/api/trade/price-list-priority-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` — **改动**：新增一条独立 `services[]` 元素（锚点 = 5.1.1.1 的
  `price-lists` 那条之后 = catalog 中段）

**契约 / 台账**

- `packages/api-contracts/src/catalog.openapi.json` + `packages/api-contracts/src/contracts/catalog.ts`
  （`catalog-contracts.py` 重新生成，catalog 纯新增 6 path）
- `docs/product/capability-blueprint/module-progress.md` — **只改 5.1.2.1 那一行的「状态」「证据」两格**

## 验证

- L0 编译闸：`./mvnw -q compile` 绿；`pnpm typecheck` 三包绿；
  `prettier --check`（本车道改动过的前端文件）绿。
- L1 模块闸：`-Dtest='PriceListPriority*'` → **32 例绿**。
  另跑 `-Dtest='PriceList*'` → **69 例绿**（含 5.1.1.1 的 37 例，**未受影响**）。
- 前端：`pnpm vitest run` 本模块两个文件 → 19 例绿。
- api-proxy 全量 allowlist 回归（改了共用文件必须全量）：`apps/web/src/app/api/trade` →
  **72 文件 / 398 例绿**。
- 契约：9 个服务的 `OpenApiContractTest` 各 1 例绿 → `catalog-contracts.py --spec-dir` 重新生成。

## 后续待办

1. **5.1.1.2 交付后**：由它自己的写入路径补齐「同 SKU」维度；本模块不改它的表与逻辑，
   但**可以**在它交付后把 `skuCoverage.covered` 翻成 true（判定依据一到位就能做）。
2. **5.3.1.1 测算 / 5.4 调价**接入时：它们应该调用 `POST /catalog/price-lists/resolve`
   取生效价目表，而不是自己再实现一套「哪张表生效」的逻辑（那正是本模块要消灭的重复）。
3. **价格金额**：价目金额随 5.1.1.2 的 `price_list_item` 一起来；本模块不落金额列，
   也不引入任何浮点。
4. 台账的**统计计数**在本车道之前就与蓝图不一致（`sync.mjs --check` 在 HEAD 上已红），
   本车道按任务书只改自己那一行，没有跑全量 `sync.mjs`（避免与同批另三条车道抢同一个文件）。
