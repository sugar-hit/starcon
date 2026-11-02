# 2026-11-02: 5.1.1.1 价目表列表（价目表 / 适用范围 / 生效区间 / 覆盖范围摘要）

## 摘要

蓝图 05「定价与价目」→ 5.1「价目管理」→ 5.1.1「价目表」→ **5.1.1.1 价目表列表**落地。

车道分支 `feat/5-1-1-1`，迁移版本号由调度者预分配为 **V431**
（`backend/services/catalog/.../V431__price_list.sql`）。
本模块是 **5.1.1.2 价目项批量维护** 与 **5.1.2.1 优先级与冲突检测** 的**地基** ——
这两条车道的上游都是本模块，交付后它们才能开工。

蓝图原文：「**职责**：管理价目表（如"北美自发货零售价"），每张表绑定一个币种、适用法人/店铺/站点、
生效区间，展示表内价目项数量与最近更新时间。 **输入**：价目表增删改；币种与适用范围选择；
生效区间设置。 **输出**：价目表记录 + 覆盖范围摘要。 **关系**：上游 = —；下游 = 5.1.1.2 价目项、
5.3.2.1 定价模板。**一价目一币种**是硬约束——混币种的价目表会让利润计算失效。」

交付物：两张新表 + 7 个端点（`/catalog/price-lists`）+ 一个真实工作区页面（`/pricing/lists`）+
37 个后端测试 + 24 个前端测试。

## 决策 / 结论

### 1. 🔴 一价目一币种：钉在**表头单列**，不是「集合约束」

蓝图说的是硬约束，本仓没有「一张表挂多个币种」的合法形状，于是只有一条路：
`price_list.currency` 是**单列 NOT NULL**，表结构上就不存在第二个币种位。

- 列级**命名**约束 `price_list_one_currency CHECK (currency ~ '^[A-Z]{3}$')` 兜住「写进来但不是币种」。
- 🔴 **列宽刻意留 `varchar(16)` 而不是 `varchar(3)`**：PG **先判列宽再判 CHECK**。
  列宽收成 3 的话，`'USD,CNY'` 报出来的是一句 `value too long for type character varying(3)` ——
  挡得住，但**看不出是哪条规矩挡的**。列宽放宽后第一道闸就是命名约束，
  报错里能一眼看到 `price_list_one_currency`。（这是本轮实测踩出来的：初版用 `varchar(3)`，
  集成测试断言约束名时红了才发现。）
- 给下游的**结构性约定**：价目项表（5.1.1.2）**不得自带币种列** —— 价目项的币种只能来自
  `price_list.currency`。这条写进了迁移注释与能力声明，否则下游很容易顺手加一个 `currency` 列
  把这条硬约束破掉。

### 2. 🔴 表内价目项数量：计数器 + **给下游的触发器函数**，不建价目项表

「展示表内价目项数量」是本模块的职责，但 `price_list_item` 是 **5.1.1.2 的写入所有权**
（两边都建 ⇒ 同一张事实表两个写入所有者）。

落法：`price_list.item_count` 计数器 + 本迁移提供触发器函数 `price_list_sync_item_count()`
（运行时才解析 `price_list_item`，所以它现在可以安全地存在库里）。5.1.1.2 交付时**只需**
`CREATE TRIGGER ... EXECUTE FUNCTION price_list_sync_item_count();`。

- 价目项模块未交付前 `item_count` 恒为 0 —— 那是**真实的 0**（表里确实还没有价目项），
  **不是**「取不到」。能力声明与页面都把这句写出来。
- 集成测试用一张**临时表** `price_list_item` + 本迁移的触发器函数，验证计数真的被写回
  （临时表随会话结束消失 ⇒ 不会与 5.1.1.2 的真表撞名）。同时验证「表里有价目项时删除被拒（409）」。

### 3. 🔴 5.1.2.1 是**下游**（守门员），不是前置 —— 本模块不做任何冲突判定

蓝图把 5.1.2.1 写成本表的守门员，同时又列为下游（两者互为上游 ⇒ 死锁）。
本模块因此**不实现**优先级与冲突检测：本表连价目项都还没有，
而「同 SKU + 同店铺 + 同站点 + 同时段生效」这个判据的另一半在 5.1.1.2 手里。
这条边界在能力声明、覆盖范围摘要与台账里都写明。

### 4. 适用范围：编码 + 上游授权只读端点，跨服务**不建副本**

法人 = identity 17.1.2.2（`legal_entity`）、店铺 = channel 2.3.1.1（`shop`）、
站点 = channel 2.1.1.1（`marketplace`）。全部用**编码**而不是外键（跨服务 FK 被 AGENTS.md 禁止）。

- 新建端口 `PriceListScopePort`（法人 + 店铺）；站点**复用**既有的 `ChannelPlatformPort`，
  不重复实现。
- 🔴 **法人不走 `FunctionalCurrencyPort`**：那个端口的契约是「本位币」，
  它会主动过滤掉没配本位币的法人 —— 对汇率是对的，对「适用范围目录」是错的。
- 上游读不到 ⇒ **503**（不用空列表冒充「没有法人 / 没有店铺」）；编码不在目录里 ⇒ 400。
- 保存时**只读本次用得到的那几个上游**（只有法人范围就不必连 channel）。
- 「适用范围为空 = 适用于全租户」是**显式语义**，摘要里写出来。

### 5. 生效区间：半开区间 [valid_from, valid_to)

起点含、终点不含 ⇒ 两张首尾相接的价目表不会在交界那一刻同时生效
（这是 5.1.2.1 冲突检测能成立的前提）。「此刻是否生效」用 **DB 的 `now()`** 判定，
不混 JVM 墙钟（跨时钟域比较是本仓已知的假红来源）。

### 6. 权限：复用 `catalog.spu.read` / `catalog.spu.update`，identity **零改动**

catalog 服务的既有定价模块（5.3.2.1 / 5.3.2.2 / 5.4.1.x / 5.5.x）全部复用商品主数据域的权限码，
新造词表要改 identity 的迁移（另一个服务的写入所有权）。本模块照此办理 ⇒ **不需要 V431 的
identity 权限迁移**，也就不会与并行车道撞版本号。

### 7. 迁移写法：零外键 + 触发器引用校验

沿用 V227 / V231 的手法（batch-37 的坑：给既有表加 FK 会打破既有测试的 TRUNCATE ⇒ 整批红）。
`price_list_scope → price_list` 用触发器 `guard_price_list_scope` 校验，报 23514。

### 8. 前端：真实工作区 + api-proxy 白名单**追加**在数组末尾

`/pricing/lists`（蓝图 05 卷「5.1 价目表 → /pricing/lists」，台账路由列也是它）。
页面：能力声明（七条口径说明固定在顶部）+ 覆盖范围摘要 + 台账（真分页 + 五个筛选）+ 表单 +
详情 + 适用范围目录 + 加载 / 错误 / 空三态。

api-proxy：**新增一条独立的 `price-lists/` 元素，追加在数组末尾**，既有条目一字未改、不重排。
刻意**不放行** `price-lists/lists/{uuid}/items`（价目项是 5.1.1.2 的写入所有权，后端没这个端点）
与 `price-lists/lists/{uuid}/conflicts`（冲突检测是 5.1.2.1）。

## 涉及文件

**后端（新增）**

- `backend/services/catalog/src/main/resources/db/migration/V431__price_list.sql`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceListModels.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceListRules.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceListScopePort.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceListScopeConfiguration.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceListService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/PriceListController.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceListRulesTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceListIntegrationTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/PriceListTenancyIntegrationTest.java`

**前端（新增）**

- `apps/web/src/app/pricing/lists/page.tsx`
- `apps/web/src/components/pricing/price-list-model.ts`
- `apps/web/src/components/pricing/price-list-model.test.ts`
- `apps/web/src/components/pricing/price-list-workspace.tsx`

**前端 / 台账（改）**

- `apps/web/src/lib/api-proxy.ts`（**追加**一条 `price-lists/` 元素，既有条目一字未改）
- `docs/product/capability-blueprint/module-progress.md`（**只改 5.1.1.1 那一行**的状态与证据两格）

## 后续待办

1. **5.1.1.2 价目项批量维护**（下游，现在可开工）：建 `price_list_item` 表 +
   `CREATE TRIGGER ... EXECUTE FUNCTION price_list_sync_item_count();`；
   🔴 **价目项不得自带币种列**（币种来自 `price_list.currency`）。
2. **5.1.2.1 优先级与冲突检测**（下游，现在可开工）：冲突判据的另一半在 5.1.1.2 手里，
   两边都落地后「同 SKU + 同店铺 + 同站点 + 同时段」才有唯一答案。
3. **5.3.2.1 定价模板**（下游）：模板的「怎么算」与价目表的「算完是多少」的关系，
   本模块在能力声明里写清了，但两边都没落地前不要互相调用。
4. **本仓没有 ISO 4217 币种字典**：币种只校验三位大写字母的形状；页面上的币种提示
   来自上游主数据里的本位币与站点默认币种，**不是权威字典**。真要做权威字典需要一张新表 + 一个写入所有者。
5. **已知缺口（如实记录）**：价目项未交付 ⇒ `item_count` 恒为 0；冲突检测未交付 ⇒ 保存价目表不做冲突校验。
