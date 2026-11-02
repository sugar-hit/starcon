# batch-133 —— 5.1.1.2 价目项批量维护（同 tick 内的第二次并入）

> 批次：batch-133（接 batch-132 往后排）
> 集成头：`integration/module-dev`（HEAD=92d5c0fd）
> 本批并入：`5.1.1.2`（catalog，V433）
> 车道：`feat/5-1-1-2`（HEAD `e2669794`，4 个 atomic commit）
> 模块自己的完整设计说明：`docs/sessions/2026-11/2026-11-02-price-list-item-batch.md`

**为什么同一 tick 里排两个批次号**：batch-132 是本 tick 开始时回收的两条车道（5.1.1.1 / 15.3.1.1）；
5.1.1.2 是本 tick 跑到一半时自己 land 的车道。batch-132 的说明**已经 commit 并 push**，
按「批次号不能重号」的规矩，5.1.1.2 另起 batch-133，而不是回头改已推送的 batch-132。

**为什么不等下一轮再收**：本轮写 `last-batch-at` 的时刻是 15:16，节流闸阈值 50 分钟
⇒ 16:00 那一格只有 44 分钟，**会被节流闸挡掉**；若按「车道跑在后台，下一轮回收」的常规节奏，
这条车道要一直躺到 17:00 才有人捡。手上已有绿灯、闸已释放、直接并入并重跑 L2 的成本
（一次 catalog 分块 ≈ 6 分钟 + 前端 6 步 ≈ 4 分钟）低于空等一个整点的机会成本。

---

## 模块 5.1.1.2 价目项批量维护

**这个功能做什么**：运营在 `/pricing/lists` 的「价目项」页签里维护 SKU 价格：
按分类 / 品牌 / SKU 范围批量设价（固定值 / 按百分比 / 按类目统一）、阶梯价（按数量门槛）、
Excel 导入导出，**保存前可预览影响范围**；同一张价目表内若出现多条同优先级有效价目，
**报错让用户选，不随机取**（蓝图 05 卷关系句的加粗要求）。

**技术落地**：

- **迁移**：`backend/services/catalog/src/main/resources/db/migration/V433__price_list_item.sql`
  —— 三张新表，**零 ALTER 既有表、零外键**（跨表引用用 DB 触发器 guard）：
  - `price_list_item`（价目项：SKU、单价 `NUMERIC(19,6)`、数量门槛 `NUMERIC(19,6)`、
    生效区间、优先级）
  - `price_list_import_batch`（一次导入批次）
  - `price_list_import_row`（导入批次里的逐行明细：Excel 行号 + 原样保留的原始取值 +
    解析结果；`unit_price` 允许 NULL ——「解析不出来」与「解析出来是 0」是两件事）
  - 三表均 `tenant_id uuid NOT NULL` + **ENABLE 且 FORCE** RLS `isolation` 策略
    （照抄 V431 第八节的 `DO $$ FOREACH` 写法）+ 触发器兜底 + `GRANT` + 索引
- **端点**：`/catalog/price-lists` 共 **11 个** ——
  `GET /item-capabilities`、`GET /lists/{priceListId}/items`、
  `GET /lists/{priceListId}/items/{itemId}`、`POST /lists/{priceListId}/items`、
  `PUT /lists/{priceListId}/items/{itemId}`、`DELETE /lists/{priceListId}/items/{itemId}`、
  `GET /lists/{priceListId}/items/conflicts`、`GET /lists/{priceListId}/items/resolve`、
  `POST /lists/{priceListId}/items/preview`、`POST /lists/{priceListId}/items/apply`、
  `GET /lists/{priceListId}/items/export`、`POST /lists/{priceListId}/items/import`
- **页面**：`apps/web/src/components/pricing/price-list-item-model.ts` +
  `price-list-item-workspace.tsx` + `pricing-lists-page.tsx`（页签），
  `apps/web/src/app/pricing/lists/page.tsx` 只改默认导出一行
- **白名单**：`apps/web/src/lib/api-proxy.ts` 新增**一条独立 `services[]` 元素**，
  锚点 = **数组末尾**（本轮四条车道分别占用末尾 / catalog 中段 / crm 中段 / identity 中段四个互不相邻锚点，
  刻意规避上轮那种「同锚点撞车、整批卡在冲突」的形状）
- **新增依赖**：`backend/services/catalog/pom.xml` 加 `poi` / `poi-ooxml` 5.2.3
  （Excel 导入导出用**真实 POI 解析 / 写出**，不是假导入）
- **测试**：
  - 后端 **40 例全绿** —— `PriceListItemRulesTest` 15 + `PriceListItemIntegrationTest` 20 +
    `PriceListItemTenancyIntegrationTest` 5
    （实跑 Testcontainers PostgreSQL 16、连接池压到 1；含**租户隔离三断言**）
  - **5.1.1.1 回归 37 例全绿**（本车道加了一个触发器，必须确认没打断上游）
  - 前端 **27 例全绿** —— `price-list-item-model.test.ts` 21 + `price-list-item-allowlist.test.ts` 6
- **契约**：`packages/api-contracts/src/catalog.openapi.json` + `contracts/catalog.ts`
  由 codegen 重新生成 —— **纯新增 15 schema / 9 path，删除 0 条、既有 schema 改动 0 处**（逐条比对确认）。
  codegen 的碰撞闸还拦下了一处与 channel 的 `ImportRequest` 同名，改名后通过。

**关键设计决策**：

1. **「同优先级多条有效价目必须报错让用户选，不随机取」** —— 落地成两个**刻意独立**的维度：
   `/items/conflicts` 把冲突**摆出来**（哪几条、为什么撞），`/items/resolve` 给出**唯一解析结果**。
   蓝图同族模块 3.6.2.1（供应商报价）已有同一套「同优先级 ⇒ 不可判定」的写法，本模块照抄其语义。
2. **保存前先预览** —— `/items/preview`（纯读、不落库，可反复问）与 `/items/apply`（真正写入）分开，
   批量操作先看影响范围再决定。
3. **导入是「先报后改」** —— `/items/import` 产出**校验报告**（哪一行错、为什么），
   有校验错误就不落库；逐行明细表保留原始取值，运营能看见自己写的是什么。
4. **数值口径** —— 单价与数量门槛一律 `NUMERIC(19,6)`（禁浮点）；解析失败时 `unit_price` 留 NULL，
   **不用 0 顶替**（「解析不出来」≠「解析出来是 0」）。
5. **越界如实标注两处**（worker 主动声明，不是问题）：
   - 改了 5.1.1.1 的 `price-list-allowlist.test.ts` —— 它原先把 `price-lists/lists/{id}/items` 断言为 404，
     而该路由现由本模块真实实现 ⇒ 前提不成立，照 11.1.1.2 售后单详情那次既有先例移除该条负向样本，
     其余负向样本不变。
   - `packages/ui/src/lib/task-pages.ts` **刻意未登记**（多车道并发改同一文件必然冲突），待调度者统一处理。

**已知缺口（如实标注）**：5.1.2.1 优先级与冲突检测**本轮未交付** ——
本模块只管**同一张价目表内部**的歧义，不拦截**跨价目表**的写入冲突（那归 5.1.2.1）；
分类 / 品牌取自 `spu.category` / `spu.brand` 自由文本，批量命中是字符串精确匹配。

---

## 本批 L2 结论（r159）

**L2_RESULT = GREEN**（存量红 24 条全在 `.dev-agent/l2-baseline.txt` 内 / **本批新增失败 0** ⇒ 允许 push）

### 静态预检 —— 零命中

对 `V433__price_list_item.sql` 跑「已知互相破坏形状」扫描：
`ADD COLUMN` 无 DEFAULT **0 命中** · `SET NOT NULL` **0 命中** · `ADD CONSTRAINT ... CHECK` **0 命中** ·
**零 ALTER 既有事实表**（唯二 `ALTER TABLE` 是 RLS `ENABLE/FORCE`，只作用于本迁移新建的三张表）。

### 后端 verify（分块）

```
cd backend && ./mvnw verify -fae -pl libraries/tenancy,services/catalog
```

**验证范围**：`catalog` 是本批**唯一**变更的后端模块（`git rev-parse HEAD:backend/<module>` 逐模块比对确认）⇒ 必须重验；
`tenancy` / `identity` / `crm` / `platform` 源树与 r158（`41391b61`）**逐字节相同** ⇒ **复用 r158 的 GREEN 结论**；
`channel` / `warehouse` / `trade` / `finance` / `reporting` ⇒ **复用 r156 的 GREEN 结论**。

### 前端 6 步

见下表。
