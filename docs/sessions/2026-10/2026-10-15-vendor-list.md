# 2026-10-15 · 6.1.1.1 供应商列表

## 决策摘要

交付 **6.1.1.1 供应商列表**（蓝图 06 采购与供应，L3 锚点 6.1.1，阶段 D6），归属 **catalog** 服务，
Flyway 版本 **V137**，车道 `feat/6-1-1-1`。

本模块最大的架构约束是蓝图那句加粗：**「供应商数据主数据在 03，本模块呈现采购视角的视图与运营动作，
避免同一实体两处可编辑」**。落地方式是**物理上不建 supplier 主档表**：

- `party`（V27）/ `party_role`（V27）/ `party_supplier_profile`（V30）三张表**一条 UPDATE 都不发**，
  编码 / 名称 / 联系方式 / 主体状态读 party，合作等级 / 账期 / 结算方式读 party_supplier_profile；
- 采购侧独有的三样落在 `vendor_supply_profile`：合作状态、主供类目、采购侧备注；
- 集成测试 `本模块不回写party主数据` 把本模块的四个写端点全跑一遍，再逐列比对 party 未变，
  把这条纪律钉成数据事实而不是注释。

### 关键取舍

**1. 合作状态必须落在自己的表上（与 party.status 分开）**

`party.status` 说的是「这个往来单位本身还认不认」（ACTIVE / INACTIVE / BLACKLISTED，3.6.1.1 写）；
采购侧要说的是「**采购这条线上**还跟不跟他做」。一个战略供应商可能主体照常 ACTIVE
（11 售后单还要引用他），但采购侧已经 SUSPENDED（资质过期 / 报价全失效 / 账期谈崩）。
把两件事折叠成一个字段的后果很具体：为了表达「暂停采购」去停用主体，于是售后单找不到这个供应商。
所以 `supply_status` 独立成列，两边**互不同步**。

**2. 四个指标一个都不落库，全部是列表查询里的 SQL 聚合**

供货 SKU 数（M1，派生自 3.6.2.1 的 supplier_quote）、采购金额累计（M2）、准时交付率（M3）、
质量合格率（M4）没有一个是存下来的字段。存比率的那张表会在第一次改口径时变成假数据：
没有任何东西会提醒它「上个月的 92% 是按另一个分母算的」。

- M3 **不存 on_time 布尔列**：准时与否由 `received_date <= due_date` 两个事实推出，
  存一份推导结果就多一个可能与事实不一致的副本。
- M3 / M4 分母为 0 返回 **null 而不是 0**：「还没交货过」与「交货全部延期」是**相反**的两件事，
  折叠成 0 会让一个刚建档、正在试单的供应商在列表上看起来是最差的那一家。
- M4 按**数量**比而不是批次数比：一批 10000 合格 9999 与一批 3 合格 1，按批次算都是 50%，
  按数量算是 99.97% 与 33.33% —— 后者才是采购真正承担的风险大小。
- M2 **跨币种不相加**：本库没有汇率表，100 USD + 90 EUR = 190 是一个看起来对、实际错、
  而且错得极难被发现的数字。返回 `[{currency, amount}]` 明细，排序取金额最大的那一笔并写明取了哪笔。

**3. M3 / M4 是有时间窗的统计，窗口必须回显**

默认最近 365 天（终身累计的准时率在供应商评审里没有意义 —— 三年前只接过一单的那家会拿 100%），
上限 1095 天（挡住「设成 1970 年、看不出与终身累计有什么区别」的请求）。
响应里带 `metricWindow`，界面上必须显示它：一个不说清是哪一段的「准时率 96%」是没有意义的数字。
质检侧列是 `timestamptz`，values 位置写 `?::timestamptz`，右端取 `to + 1 天零点`。

**4. 指标聚合用 LATERAL 而不是 join 进主表**

join 出去会让一个供应商被复制成 N 行（三条到货记录 = 三行），于是「总数」与「翻页翻到底」立刻对不上，
而 COUNT 查询并不会跟着一起膨胀 —— 那是最难查的一类不一致。

**5. 到货 / 质检记录在本服务而不是 trade，且只追加**

履约与质检结论是**供应商档案的一部分**，不是单据的一部分：「准时交付率」跨单据聚合，
它在 06 采购单里算一次、在本列表算一次，两次必然会在某次口径变更后不一致，而这种不一致**不会报错**。
指标的定义与它的原料应该同属一个写入所有者。两张表**没有 UPDATE 端点**，
录错了用 `is_void` 作废并**强制留理由**（没有理由的作废会让「准时率突然变好」无法解释）。

**6. 导出挂 3.8.2.1 的统一通道，不另开端点**

本仓库里导出是**唯一一条**通道，自带字段白名单、排序还原、异步执行与审计，
且 EMAIL / PHONE 是 `pii=true` 敏感字段（勾选须显式授权并填用途）。
另开一个 `GET /export` 等于造出第二条**无 PII 授权、无审计**的导出通道，而联系方式正是需要被审计的那类字段。
导出文件是快照，不提供任何写回路径 —— 它绝不可能成为「同一实体两处可编辑」的第二个编辑入口。

**7. 路由与下钻刻意与兄弟模块分开**

路由取 `/catalog/supply/vendors/*` 而不挂到 `/catalog/parties` 之下：让「谁能改什么」在 URL 上就看得见。
下钻端点叫**采购视角**详情而不是「供应商详情」—— 6.1.1.2 才是那个模块（账期 + 报价 + 采购历史 + 资质全景），
本端点只回答采购侧的一件事。

## 对遗留半成品的处置

上一个会话在写代码途中被中断，**一行都没 commit**，留下 8 个未跟踪文件。逐个读完后的处置：

| 文件 | 处置 | 说明 |
| --- | --- | --- |
| `V137__vendor_supply_perspective.sql` | **原样沿用** | 逐条复核了它引用的每一个上游：`party` / `party_role` / `party_supplier_profile` / `supplier_quote` / `product_category` / `unit_of_measure` / `sku` 的表名与列名全部实查确认无误。零 ALTER，跨车道 DDL 回归面 = 0。 |
| `VendorListModels.java` | **原样沿用** | DTO 划分与「三段字段不同所有者」的注释到位，类型名统一带 `Vendor` 前缀（codegen 把六个服务拍平进同一命名空间，重名会被后者覆盖）。 |
| `VendorListRules.java` | **原样沿用** | 纯规则层，与 V137 第九节 R1~R6 逐条对应，可离线穷举。 |
| `VendorListService.java` | **改一处 bug** | `options()` 用了 `product_category_tree.status='ACTIVE'`，而 V14 的真实列名是 **`enabled`** —— 该查询会直接 500。改为 `t.enabled and pc.enabled`。其余（bind 顺序、LATERAL 用法、只追加语义）复核无误。 |
| `VendorListController.java` | **改绑定方式** | 原本把 `VendorListQuery` 当**模型属性**绑定，导致 `GET /catalog/supply/vendors` 带 `metricFrom`/`metricTo` 时返回 400 且报文与筛选条件毫无关系。按 `PartyController` / `SkuListController` 的既有口径改为逐个 `@RequestParam`，顺带让每个筛选参数都进 OpenAPI 契约。 |
| `VendorListExportDataSource.java` | **原样沿用** | 绑定顺序（5 个窗口位必须排在筛选条件之前）与 `from()` 里 LATERAL 的 `?` 出现顺序复核一致；`selectList` 在导出侧重算比率（CSV 里只有比率没有分子分母），分母为 0 落空单元格。 |
| `VendorListRulesTest.java` | **改一处注释** | 15 例全部有效。注释写「按数量算是 99.92%」，实为 99.97%（10000/10003）。 |
| `VendorListIntegrationTest.java` | **改一处配置 + 注释** | 835 行**实际是完整的**（不是半成品），三断言齐全。改：测试自建的裸 `ObjectMapper` 没带 JavaTimeModule，序列化 `orderedAt` 抛 `InvalidDefinitionException`，加 `findAndRegisterModules()`；同样修正 99.92% 注释。 |

没有丢弃任何一个文件。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V137__vendor_supply_perspective.sql`

**后端**
- `.../catalog/VendorListModels.java` — DTO（含只读 / 可写字段的段落划分）
- `.../catalog/VendorListRules.java` — 词表、排序白名单、指标窗口、比率与 null 语义
- `.../catalog/VendorListService.java` — jOOQ 显式 SQL（LATERAL 聚合）、筛选、写入、审计
- `.../catalog/VendorListController.java` — `/catalog/supply/vendors/**` 九个端点
- `.../catalog/VendorListExportDataSource.java` — 挂 3.8.2.1 导出注册表（key `VENDOR_LIST`）

**测试**
- `.../catalog/VendorListRulesTest.java`（15 例）
- `.../catalog/VendorListIntegrationTest.java`（21 例，含多租户三断言）

**前端**
- `apps/web/src/components/supply/vendors-model.ts` — 类型、筛选、文案、校验、端点
- `apps/web/src/components/supply/vendors.tsx` — 工作区组件（概览 / 筛选 / 列表 / 档案编辑 / 下钻追加与作废 / 字段归属页签）
- `apps/web/src/components/supply/vendors-model.test.ts`（28 例）
- `apps/web/src/app/supply/vendors/page.tsx`

**契约（codegen 产物，不手改）**
- `packages/api-contracts/src/contracts/catalog.ts`（+16 个 `Vendor*` 类型）
- `packages/api-contracts/src/catalog.openapi.json`
- `packages/api-contracts/src/contracts/trade.ts` / `trade.openapi.json`（**既有漂移的补正**，见下）

**台账**
- `docs/product/capability-blueprint/module-progress.md` 第 228 行（只改 6.1.1.1 那一行的「状态」「证据」）

## 验证

- L0：`cd backend && ./mvnw -q compile` 绿；`pnpm typecheck` 三包绿；`pnpm eslint`（新增前端文件）绿
- L1：`./mvnw -pl services/catalog -am test` → **Tests run: 1457, Failures: 0, Errors: 0**（本模块 36）
- 前端：`pnpm vitest run apps/web/src/components/supply/vendors-model.test.ts` → **28 passed**
- Flyway：43 个迁移应用到 `v137`，`vendor_supply_perspective` 正常
- Testcontainers 结束后由 ryuk 回收，未留残留容器

## 顺带发现并修正的既有漂移

重跑 codegen 时发现 `packages/api-contracts/src/contracts/trade.ts` 与 `trade.openapi.json`
相对基线 `9a24fb1c` 处于**陈旧**状态：该基线合入了 9.5.1.1 时效分析（trade 服务端点已交付），
但契约没有跟着重生成。本次 codegen 一并补上了 `OrderTimeliness*` 类型与对应路径。
**这不是本模块引入的改动**，因此单独成一个 commit，便于并入时区分。

## 后续待办

- **6.1.1.2 供应商详情**：全景详情（账期 / 报价 / 采购历史 / 附件资质）。本模块的「采购视角」下钻
  刻意不复制它，两处各做一份的后果与 V137 第一节同源。
- **6.1.1.3 供应商评级**：需要跨周期历史。本模块只给**窗口内**聚合，不冒充评级；
  评级真正落地时应读本模块的事实表，而不是另建一份打分存档。
- **trade 侧的写入路径**：本服务目前没有到货 / 质检的写入端点来自 trade 收货流；
  蓝图关系里写的是「trade 收货时经授权 API（`POST /catalog/vendors/{roleId}/deliveries`）把事实推进来」，
  当前路由是 `/catalog/supply/vendors/{roleId}/deliveries`，trade 侧接入时按实际路由对齐。
- **6.3 采购单 / 6.5 对账**：未交付。本模块的「主供类目」与「按币种分组的金额明细」
  是它们按类目分组与对账时要读的输入，字段口径已在本页 note 里写明。
