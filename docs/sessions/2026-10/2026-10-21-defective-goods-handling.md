# 2026-10-21 6.4.2.2 不良品处理（卷 06 采购与供应 / L3 锚点 6.4.2 来料质检）

## 决策摘要

- **归属 trade 服务**，后端命名空间 `/trade/defective-dispositions`，前端路由
  `/purchase/quality/defectives`（蓝图 06 卷 L2 表「6.4 加工与质检 → trade + warehouse」列表第一项是
  trade；同卷的 6.4.1.1 加工单、6.4.2.1 质检单、6.2.1.3 采购计划都在 trade）。
  前端路由挂在 6.4.2.1 的 `/purchase/quality` 之下，与 6.2.1.1/6.2.1.2/6.2.1.3 挤在
  `/purchase/demand/**` 同一套目录纪律。
- **命名撞车**：trade 服务已有 `QualityInspection*`（6.4.2.1 来料质检）与 `ReturnQuality*`
  （11.x 退货质检）两套，11.2.3.3 供应商索赔（V188）又是**退货侧**的索赔。本模块的 record / enum
  一律以 `Defective` 开头，表名前缀 `defective_*` —— 三条链各管各的，不复用对方的表。
- **质检结论只有一个来源**：处置单上的合格量 / 不合格量 / 让步接收量 / 扣量合计 / 可售量上限
  **全部从 6.4.2.1 质检单回读**，入参里**没有处置总量**这一栏；DB 触发器在写入时刻再核对一遍。
- **处置量由明细行重算**（AFTER 触发器）：本模块**没有**「直接写处置量」的入口。
- **让步接收要权限 + 原因**（蓝图加粗句）：DB 层 CHECK 缺一拒绝；服务层核对记录人**真的持有**
  `trade.defective-disposition.concession` —— 这一档**比「质检结论录入」高**，质检员不能随手放行。
- **库存只发指令、不建账**：warehouse 没有可复用的处置写入口 ⇒ 指令状态如实报出，
  绝不出现「已登记」却其实没动库存。
- **12 财务（finance 服务）在本仓不存在** ⇒ 索赔落 trade 本库，已生效冲减金额恒为 0。

## 关键 trade-off

1. **「可售量上限（合格量 + 让步接收量）」读作「处置不得侵占的那一部分」， operative 的上界是
   质检单的**不合格量**。** 任务书写的是「处置量还要受质检结论里的可售量上限约束（合格量 + 让步接收量）」，
   但把处置量卡在「合格量 + 让步接收量」上在业务上讲不通 —— 那等于说只能处置**判为合格**的货。
   本模块的落地口径是两条同时成立：
   - **处置量 ≤ 质检单的不合格量**（单张与**累计**都受约束，作废的单不占额度）—— 这才是「能处置什么」；
   - **可售量上限 = 合格量 + 让步接收量**被**冻结**在处置单上作为对照，处置**只作用在不合格量上**，
     不得侵占它。
   服务层与 DB 守卫各挡一道。若调度者认为原意是别的口径，改的是守卫②的一条上界，不动表结构。
2. **放行量由处置类型决定，不由调用方自报**：让步接收与转为促销品**整行**回可售池（放行量 = 该行数量），
   报废 / 退换货 / 返修的货离仓或不可售（放行量恒为 0）。「50 件里接受 30 件」写成
   `CONCESSION 30` + `SCRAP 20` 两行 —— 一行只表达一个去向。同一个数字在两个服务各有一份迟早对不上。
3. **库存指令的派发是「真发 + 如实报」两段**：端口 `DefectiveInventoryPort` 对着 `DEFECTIVE_INVENTORY_URL`
   真发一次 HTTP POST；配了对端就 2xx 落 DISPATCHED、对端拒绝落 FAILED、**没配对端落 UNAVAILABLE**
   （本仓的默认状态，因为 warehouse 侧确实没有那个端点）。集成测试对着真的 HTTP stub 验证「指令真的
   发出去了」，租户测试验证「没配对端时如实落 UNAVAILABLE」—— 两条分支都有真断言，没有假分派。
4. **索赔金额默认按质检单的单价快照 × 处置量推导**，调用方登记了就以登记的为准。单价快照来自
   6.4.2.1，不认客户端另报的价；登记口径是「我们主张的金额」，供应商认可以外的部分要另开一笔。
5. **明细行加了 `sort_order`**：同一笔事务里插入的多行创建时间完全相同，再按随机 UUID 排就得到一个
   **每次都不同**的次序，界面上的去向顺序会乱跳、测试也不稳定。
6. **测试用「每个用例一个租户」**：同一个容器里所有用例共用两个固定租户时，「列表里应该只有 1 行」
   会被别的用例的残留行打穿 —— 那不是隔离被破坏，是夹具互相污染。

## 过程中被测试逼出来的真实缺陷（已修）

1. **迁移里多了一个尾随逗号**：`CONSTRAINT ... CHECK(...)` 后面留了 `,` 再 `);` ⇒
   `syntax error at or near ")"`，Flyway 直接炸。已删。
2. **时间列漏了 `?::timestamptz`**：`decided_at` / `dispatched_at` / `completed_at` / `cancelled_at` /
   `submitted_at` / `settled_at` 六处都漏了 ⇒ PG 报
   `column "decided_at" is of type timestamp with time zone but expression is of type character varying`。
   六处全部补上（这正是 AGENTS.md 点名的那个静默偏 −28800s 的坑）。
3. **DELETE 触发器里访问 `NEW`**：plpgsql 在 DELETE 触发器里 `NEW` 是**未赋值**的，访问会报
   `record "new" is not assigned yet`。明细行重算触发器改成按 `TG_OP` 分支取键。
4. **状态守卫写死过严**：原来写成「状态只能由服务层推进」，结果明细行重算触发的 UPDATE（DRAFT → DRAFT）
   也被拒。改成显式的状态机（`OLD.status = NEW.status` 也算合法流转）。
5. **INSERT 的占位符与参数错位**：处置单的 32 列 / 32 占位符 / 32 参数三份平行数组 + 长度断言，
   少一个当场炸而不是落库落歪（照抄 6.4.2.1 的做法）。
6. **N+1 查询**：去向摘要原来在 Java 里逐单查两遍明细。改成一条查询里的子查询（`string_agg`），
   列表页 50 行不再多出 100 次往返。

## 文件清单

### 后端（trade）

- `backend/services/trade/src/main/resources/db/migration/V256__defective_goods_handling.sql`
  —— 五张新表 + RLS（ENABLE **FORCE**）+ 索引 + GRANT/REVOKE + 8 个护栏触发器。零 ALTER 既有表。
- `backend/services/trade/src/main/java/com/starcon/trade/DefectiveGoodsModels.java` —— 传输模型（`Defective*` 前缀）
- `backend/services/trade/src/main/java/com/starcon/trade/DefectiveGoodsRules.java` —— 纯计算规则
- `backend/services/trade/src/main/java/com/starcon/trade/DefectiveGoodsService.java` —— 服务层（jOOQ 显式 SQL）
- `backend/services/trade/src/main/java/com/starcon/trade/DefectiveGoodsController.java` —— 16 个端点
- `backend/services/trade/src/main/java/com/starcon/trade/DefectiveInventoryPort.java` —— 库存指令派发（授权 HTTP）

### 测试

- `backend/services/trade/src/test/java/com/starcon/trade/DefectiveGoodsRulesTest.java`（6 例）
- `backend/services/trade/src/test/java/com/starcon/trade/DefectiveGoodsIntegrationTest.java`（19 例）
- `backend/services/trade/src/test/java/com/starcon/trade/DefectiveGoodsTenancyIntegrationTest.java`（6 例）

### 前端

- `apps/web/src/app/purchase/quality/defectives/page.tsx`
- `apps/web/src/components/purchase/defective-goods-model.ts` + `.test.ts`
- `apps/web/src/components/purchase/defective-goods-workspace.tsx`
- `apps/web/src/app/api/trade/defective-disposition-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（既有条目一字未动）

### 契约 / 台账

- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`
  —— codegen 产物（+17 DTO，纯新增；其它服务的既有格式漂移**已回退**）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.4.2.2 那一行的「状态」「证据」两格
  （`node tools/module-progress/sync.mjs` 复核过：手动格被保留，只更新了总览计数）

## 后续待办

- **库存写端点接线**：warehouse 需要提供「按处置单变更库存状态」的授权写端点；有了之后本模块的
  `inventory_status` 才能从 `UNAVAILABLE` 变成 `DISPATCHED`，7.1.1.3 的可售量口径才会扣掉本模块的
  报废 / 返修 / 退换货量。（ownerModule：7.1.1.2 库存明细 / 8.2.2.2 待检处理。）
- **12 财务接管**：finance 服务在本仓不存在 ⇒ 索赔的入账与退款执行没有发生。12 交付后需要它按
  `defective_supplier_claim` 接管，并把 `effective_amount` / `finance_status` 的 CHECK 放宽。
- **6.1.1.3 供应商评级**：本模块产出的索赔率是评级的输入之一，评级模块未交付 ⇒ 本模块不产出评级结论。
- **6.5.1 采购对账**：处置扣量与索赔都会影响应付，但**对账由采购发起、12 财务执行付款**；
  6.5.1 的索赔汇总口径以本模块的 `defective_supplier_claim` 为准。
- **identity 的 page_resource**：trade 的页面权限码（`trade.defective-disposition.*`）在本仓没有像
  channel/warehouse 那样注册 page_resource —— trade 全服务的既有缺口，本车道沿用兄弟模块做法
  **不改 identity**（避免与其他车道撞迁移号）。
