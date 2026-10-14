# 2026-10-07 3.6.2.1 供应商报价（阶段 D1–D2）

车道：`feat/3-6-2-1`，L3 锚点 3.6.2，页面路由 `/partners/offers`，Flyway `V32__supplier_quote.sql`。

## 决策摘要

1. **报价是独立实体，不是 sku 上一个可覆盖的价格列。**
   蓝图那句「支持同一供应商同一 SKU 多个报价版本」直接否掉了 `sku_supplier_price(sku_id, supplier_id, price)`
   那种一行一价的做法：单行表下「上周的报价是多少」**没有答案** —— 不是查不到，是根本没存过。
   而 06 采购冻结的是下单那刻的价格、13 利润要看成本波动，两者都要回看"当时那版报价是什么"。
   落地：报价一版一行，新建一版就是 INSERT，旧版本靠 `valid_to` 划区间原样留着。

2. **歧义用读出口兑现，不用 UNIQUE 约束。**（蓝图点名的那条规则，V32 第四节）
   最容易想到的实现是 `UNIQUE(tenant_id, supplier_role_id, sku_id, priority)`。**没有这么做**，理由有两条：
   - 它会禁掉"两个币种并存"这类合法行（1688 上同一卖家美元与人民币报价并存是常态）；
   - 更要紧的是它把歧义从一个**必须由人解决的状态**变成一个**根本写不进来的状态** ——
     运营会以为"系统不允许重复优先级"，于是改填别的数字绕过，而真实的价格歧义还在。
     落地：`GET /catalog/supplier-quotes/effective` 返回全部有效报价并逐条标 `selectionState`，
     歧义时 `selected` 是 **null 而不是随便一条**；`/effective/resolve`（06 采购带出价格走它）
     直接 409 并在 ProblemDetail 附 `candidates`。集成测试断言了 409 的 body 里真的带得出候选 ——
     "报错让用户选"的后半句是**让用户选得了**，只报一句话等于没让用户选。

3. **优先级只在同一供应商内部可比。**
   星尘的优先级 1 与宏远的优先级 1 之间没有可比性 —— 那是两家供应商在竞价。
   所以选择状态与歧义计数都按 **(SKU, 供应商)** 分组计算，歧义 SQL 的分组必须带 `supplier_role_id`，
   少掉这一维就会把两家供应商的优先级 1 误判成并列。

4. **阶梯单独一张表，且是开口的。**
   两个数组塞一行（数量数组 + 单价数组）在"买 137 件属第几档"上需要内存对齐，
   而数组顺序在 DB 层没有任何保证，且"两档数量相同"能写进去、只在取价时才炸。
   一行一档之后 `PRIMARY KEY(tenant_id, quote_id, min_quantity)` 让重复档位在 DB 层不可能。
   **开口**（最后一档无上界）是因为带上下界就必须回答"买 137 超过最后一档怎么办"，
   而超量价格是与供应商另议的，不是一条能查出来的数据。低于最低档时返回"无适用价 + 提示"，
   **不**退回高档价（那会让采购单带出对方没报过的价）。

5. **币种只在报价行上，阶梯没有 currency 列。**
   逐档设币种会造出「100 件档 USD、500 件档 EUR」这种行 —— 06 采购单无法判定用哪个币种结算，
   界面只能让用户挑，而挑错了没有任何东西会报错。

6. **报价的 MOQ 与 3.6.1.2 档案的 MOQ 口径不同，两者都保留。**
   报价是 SKU 级门槛（必填 >0），档案是供应商级门槛（可空）。**刻意不建外键** ——
   档案是 3.6.1.2 的写入所有权，本模块不得因为别人没建档案就写不进报价；档案缺失时给提示。

7. **供应商用主体 ID 传入，角色由服务端解析。**
   前端手上只有主体（页面上点的是某个供应商），角色由服务端从该主体的 SUPPLIER 角色解析。
   让客户端直接传 `role_id` 等于把"这条报价属于供应商身份"这层判断交给前端，
   而前端完全可能传来一个 CUSTOMER 角色的 id。

## 关键 trade-off

- **读出口是否该"自动挑一条"**：不挑。代价是下游（06 采购）要多一次 409 处理与人工介入，
  换来的是"系统不会静默发出一个对方没确认过的价格"。对采购成本这种数据，宁可让人多看一眼。
- **有效报价判定在 SQL 里做了三遍**（概览歧义数 / 列表 `effectiveOnly` / 有效报价出口）。
  重复的代价换来"同一处口径"的确定性：不同源的症状是"台账说 2 条有效、列表筛出 3 条、歧义数是 1"。
- **写入路径做了"先判后写"**（SKU 可见性、采购单位登记、供应商身份）。不这么做的话，
  未登记的单位与跨租户的 SKU 会一路走到 INSERT 才被外键挡下，而外键违例被 ApiErrorHandler
  渲染成 409「编码或规格组合重复」—— 那句文案与真实原因毫无关系，运营会照着去查重复编码。
  这一条是实测踩出来的：集成测试最初报的就是 `DataIntegrityViolationException` 而不是可读的 400/404。

## 预检发现的两个环境事实

1. **worktree 基线陈旧**：车道分支是从 `020f5b6` 切的，而调度器声明的基线是集成头 `5d9004a`
   （V27~V31 都在那里）。首次 grep 上游表时发现 `party` / `party_role` / `party_supplier_profile`
   全部不存在 —— 差点误判成"上游未交付"。确认 `020f5b6` 是 `5d9004a` 的祖先且本车道无独有提交后，
   `git merge --ff-only` 快进到声明基线（零提交丢失），预检随即通过。
   **教训：车道分支的创建时间可能早于调度器声明的基线，开工前应先核对 HEAD 与集成头。**

2. **读报价要 `catalog.sku.read`**：集成测试最初让"只有 `catalog.spu.read`"的身份读报价，得到 404。
   根因是已交付的 V3 `sku_read_scope`（RESTRICTIVE）：`operation='catalog.spu.read'` 时
   `sku` 行额外要求 `catalog_scope('catalog.sku.read')`；报价要 join sku，于是读报价也要 sku 读权限。
   **那是 V3 的数据范围设计在起作用，不是本模块的缺陷** —— 采购成本本就不该给连 SKU 都看不见的角色。
   测试改为给只读身份同时授予 `spu.read` + `sku.read`。

## 文件清单

新增：

- `backend/services/catalog/src/main/resources/db/migration/V32__supplier_quote.sql`
- `backend/services/catalog/src/main/java/com/starcon/catalog/SupplierQuoteRules.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/SupplierQuoteService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/SupplierQuoteController.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/SupplierQuoteRulesTest.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/SupplierQuoteIntegrationTest.java`
- `apps/web/src/components/catalog/supplier-quote-model.ts`
- `apps/web/src/components/catalog/supplier-quote-model.test.ts`
- `apps/web/src/components/catalog/supplier-quote-workspace.tsx`
- `apps/web/src/app/partners/offers/page.tsx`

改动：

- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（追加 DTO + `java.time.LocalDate` import）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ApiErrorHandler.java`（歧义 409 的 candidates）
- `apps/web/src/lib/api-proxy.ts`（白名单放行 + 注释）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（放行/拒绝断言）
- `docs/product/capability-blueprint/module-progress.md`（**仅本模块那一行**的状态与证据两格）

codegen 产物（禁止手改）：`packages/api-contracts/src/catalog.ts`（纯新增 8 个类型，删除行 0）、
`packages/api-contracts/src/catalog.openapi.json`。

## 验证

- `backend`：`./mvnw -pl services/catalog -am test -Dtest='SupplierQuoteIntegrationTest,SupplierQuoteRulesTest'`
  → Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
- `pnpm typecheck` → Tasks: 3 successful, 3 total
- `pnpm lint` → Tasks: 3 successful, 3 total
- `npx vitest run` → Test Files 43 passed / Tests 739 passed
- api-proxy 正则：`mine: 1 existing addresses: 1 existing roles: 1`，另跑 11 条允许/拒绝穷举全对
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` → Generated 491 DTOs

## 后续待办

- **06 采购**带出价格接 `/catalog/supplier-quotes/effective/resolve`，并把 409 的 candidates
  渲染成"选哪一条"的交互（当前 409 的处置路径是回台账改优先级，没有做选择器）。
- **05 定价**取成本参考、**13 利润**看成本波动：都读 `/effective`（不指定 supplierId），
  注意此时 `selected` 恒为 null、优先级只在供应商内部可比。
- 报价的**导入**路径不在本模块：3.8.1.1 导入向导的 `target_entity` CHECK 有意不含 QUOTE
  （那是 3.8.1.1 交付时钉死的边界）。若要支持批量导入报价，需要新开一个决策而不是改那条 CHECK。
- `/partners` 未进导航（既有状态，3.6.1.1 起就如此），本模块未动导航，属别的车道。
