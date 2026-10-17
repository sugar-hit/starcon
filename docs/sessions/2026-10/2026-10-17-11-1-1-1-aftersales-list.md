# 11.1.1.1 售后单列表（阶段 D4，L3 锚点 11.1.1，trade 服务）

> 车道：`feat/11-1-1-1`（worktree `.worktrees/11.1.1.1`），基线 `integration/module-dev` @ `a052a025`。
> 迁移 `V156__trade_aftersales_case_list.sql`（版本号由调度器预分配，同批另三条车道占 V153/V154/V155）。

## 决策摘要

1. **「平台时效倒计时必须显眼」按数据要求落地，不只按 UI 要求。**
   `reply_due_at` / `resolve_due_at` 是两个真列；`effective_due_at`（取更紧的那个）
   由 **BEFORE 触发器**维护并建索引；时效状态由 `IMMUTABLE` 函数
   `aftersales_timeliness_state(due_at, done_at, at)` 现算，**能进 WHERE 与 ORDER BY**。
   筛选与排序全在 DB 层，前端不重算一遍判定。
   状态五值：`OVERDUE / DUE_SOON（≤24h）/ ON_TRACK / RESOLVED / NO_DEADLINE`。

2. **「参考时刻整页共用一个」。** `at` 在 `list()` 开头取一次，WHERE、行、汇总三处复用。
   否则会出现「我筛了临期、第一条不临期」的自相矛盾（前后取 `now()` 差几微秒就能触发）。

3. **店铺维度只做到平台级，且不建占位列。**
   实查 `sales_order`（V99）无 `shop_code`；卷 2 店铺在 `channel` 独立库，禁止跨服务 SQL。
   ⇒ 平台筛选用 `o.order_source`；`platformShopCode` 恒为 `null`，`capabilities` 报
   `available=false`。**刻意不建恒为空的 shop 列** —— 空列会被读成「这家店没有售后单」。

4. **责任人是租户可维护的登记表**（照抄 V150 `order_risk_signal` 既有解法）：
   `aftersales_owner_assignment` 每行强制 `owner_source` 枚举（`TENANT_MAINTAINED` /
   `MODULE_15_SERVICE_STAFF`）与 `captured_at` **采集时刻**；不伪造 L4 模块号。
   平台消息无落表方案（是对话流不是可维护信号），如实报 `crossServiceReadAvailable=false`。

5. **登记入口 `POST /trade/aftersales/cases` 是 11.1.1.3 未交付期间的登记入口**，
   只做「单据落库 + 平台时限登记」，**不含**受理判定（售后期/条件/责任人/回复时限）。
   `capabilities.intakeDelivered=false` 如实报出。没有它列表永远为空，是死链。

6. **批量三件（授权/备注/转工单）全部落在本服务内的真实表**，
   不假装已对接 11.1.1.3 / 11.4.2.1。转工单表带 `owner_module='11.4.2.1'` 显式标注归属。

7. **批量逐条失败来自写库前的真实预校验**，不是「catch 到什么都标 failed」。
   写阶段的意外异常整批回滚并返回 5xx，不伪装成部分成功。
   结果**按请求下标**返回（不是按 caseId 归并）—— 重复 ID 时按 caseId 归并会让
   「第 1 条成功」顶到第 2 个位置上，运营完全对不上自己勾选的那几行。

8. **金额区间是闭区间**（`>= min and <= max`），口径由响应字段 `amountRangeSemantics`
   原样报出，测试用 `min == max == 某张单的金额` 逐位断言。

## 关键 trade-off

- **`effective_due_at` 用触发器而不是 `GENERATED ALWAYS AS (least(...))`**：
  生成列少一个触发器，但若 PG 对 `LEAST` 的不可变性判定与预期不同，迁移会在**别的车道**
  跑测试时炸掉。触发器显式、可审阅（AGENTS.md：关键 SQL 必须可审阅）。
- **不加外键指向 `sales_order`**：加 FK 会让本迁移对别的车道的表产生 DDL 依赖，而同批
  还有三条车道在往 trade 落迁移。订单存在性由服务层在写入口真查一次校验。
- **左连 `sales_order` 而不是 inner join**：订单若缺失，售后单仍要显示（订单号显示「—」）。
  inner join 会让一张真实售后单凭空消失，而「列表里没有这张单」在售后场景里严重得多。

## 过程中被测试逼出来的三个真实缺陷（都不是测试写错）

1. **列级 `REVOKE` 在表级 `GRANT` 之后是空操作。**
   PG 语义：表级 UPDATE 蕴含每一列的 UPDATE，`REVOKE UPDATE(effective_due_at)`
   之后 `has_column_privilege` 仍返回 true —— 等于「以为收紧了其实没收紧」。
   必须 `REVOKE UPDATE ON 表` 再 `GRANT UPDATE(列清单)`。已在迁移里改掉并留注释。
   ⚠️ V150 的合计列用的是同一套列级 REVOKE 写法，**很可能同样没收紧**（不在本车道范围，未改）。

2. **序列必须单独授权**：漏 `GRANT USAGE ON SEQUENCE` 时登记端点以
   `BadSqlGrammarException → 409` 挂掉，错误文案说的是「数据与既有记录冲突」，
   与「没给 nextval 权限」毫无关系。

3. **`Timestamp.valueOf` 的静默时区偏移**：夹具用 `+08:00` 非 UTC 偏移写入，
   断言到**绝对时刻**而不是「没抛异常」。

## 文件清单

**迁移（新增）**
- `backend/services/trade/src/main/resources/db/migration/V156__trade_aftersales_case_list.sql`
  —— 5 张表 + 时效函数 + 2 个序列 + 3 个护栏/维护触发器 + 索引 + RLS(FORCE) + GRANT/REVOKE。
  零 ALTER 既有表。

**后端（新增）**
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesCaseModels.java`（枚举 + 12 个 record）
- `.../AftersalesCaseService.java`（jOOQ 显式 SQL；能力声明 / 列表 / 登记 / 批量）
- `.../AftersalesCaseController.java`（`/trade/aftersales` 下 4 个只读 + 2 个写端点）

**后端测试（新增）**
- `.../src/test/java/com/starcon/trade/AftersalesCaseIntegrationTest.java`（29 条）
- `.../AftersalesCaseTenancyIntegrationTest.java`（5 条，三断言 + RLS 强制 + 无上下文零行）

**前端（新增）**
- `apps/web/src/components/aftersales/aftersales-case-model.ts`
- `apps/web/src/components/aftersales/aftersales-case-workspace.tsx`
- `apps/web/src/components/aftersales/aftersales-case-model.test.ts`（22 条）
- `apps/web/src/app/api/trade/aftersales-allowlist.test.ts`（3 条，双向断言）

**前端（修改）**
- `apps/web/src/app/aftersales/page.tsx` —— **替换 `DomainPlaceholder` 占位页**为真实工作区
- `apps/web/src/lib/api-proxy.ts` —— **新增一条 `services[]` 元素**（未碰既有 trade 超长正则）

**契约（codegen 产物，禁止手改）**
- `packages/api-contracts/src/contracts/trade.ts`（+16 DTO）
- `packages/api-contracts/src/trade.openapi.json`（+4 路径 / +16 schema，**零丢失**）

## 上游实查结论（与调度口径一致）

- 卷 15 客服：24 个 L4 里**只交付 1 个**（15.4.1.1，在 `backend/services/crm` 独立库 `starcon_crm`）
- 卷 2 平台消息：`channel` 服务的 `create table` 清单里**无任何平台消息表**
- `sales_order`（V99）：**无 `shop_code` 列**；`order_source` 的 CHECK 已含
  `AMAZON/SHOPIFY/EBAY/TIKTOK_SHOP/ALIBABA_1688` ⇒ 平台级可用

## 后续待办（本模块不做，留给对应车道）

- **11.1.1.2 详情**：接管本模块的登记入口，补受理判定四件事（售后期校验 / 条件判定 /
  责任人自动分配 / 回复时限设置）。
- **11.1.1.3 受理**：同上；并把 `capabilities.intakeDelivered` 翻成 true。
- **D5 平台单同步**：给 `sales_order` 补 `shop_code`；同步器给售后单补店铺维度并写
  `aftersales_case` 的店铺列（本表结构与查询当时**不需要改**，只需加列 + 放开筛选）。
- **15 客服 / 卷 2 到位后**：同步器按同一形状写 `owner_source=MODULE_15_SERVICE_STAFF`，
  表结构与列表查询都不用改。
- **11.2 / 11.3**：退货实收与退款状态是**另一个口径**，刻意没混进 `aftersales_case.status`；
  它们落地后读本表的授权记录即可。
- **V150 列级 REVOKE 疑似同样没收紧**（见上「真实缺陷 1」），建议单独开一条。
