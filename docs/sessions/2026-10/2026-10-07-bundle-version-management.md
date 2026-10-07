# 3.5.1.1 套装版本管理（D1–D2）

## 决策摘要

蓝图 3.5.1.1 要求「管理组合套装（多个 SKU 按数量捆绑销售）的版本：创建版本、设置生效区间、
发布后不可变」，输入是「套装名称、组件清单、版本号、生效区间；发布操作」，
输出供 04 刊登为独立商品、供 09 订单展开组件。蓝图标了生效区间却**没有规定重叠怎么办**，
那正是本模块唯一需要自己拍板的地方。

六个关键判断：

1. **套装档案与套装版本分表，不合一。** 编码是租户内唯一且建后不可改，版本号只要求同套装内唯一；
   合表会让 `UNIQUE(tenant_id,code)` 与 `UNIQUE(tenant_id,code,version_no)` 互相打架
   （改一个版本号就会撞编码唯一键）。更要命的是「同一套装同时刻至多一个生效版本」是
   **同套装内跨行**的约束 —— 两行合一就没有「同一个 bundle_id」这个分组键了，
   重叠判定会退化成全表两两比较，又慢又说不清冲突的是哪两个套装。
2. **生效区间是半开区间 `[from, to)`，`to` 为 NULL 表示 +∞。** 用闭区间的话
   `[10:00,12:00]` 与 `[12:00,14:00]` 会在 12:00 那一瞬间「同时生效」，
   而「当前生效版本」这个查询就必须返回两行或随便挑一行 —— 两种都是编造。
   半开区间让边界日明确归后一版，钉在 `BundleRulesTest` 与
   `BundleVersionIntegrationTest#effectiveIntervalRulesArePinnedDown` 里。
3. **重叠一律拒绝，不做裁决。** 备选方案「后来者覆盖」看起来更友好，但它要先定义「谁是后来者」，
   而**版本号的先后与区间的先后互不相关**（v3 可能从今年 6 月开始生效而 v2 从去年开始）。
   一旦要靠「规则」决定谁生效，运营就再也答不出「3 月 15 日卖的是哪一版」——
   而那正是 09 订单冻结展开结果时必须能回答的问题。拒绝重叠把这个答案变成唯一的。
   报错里点名与哪一版、哪个区间撞了，让人自己决定调本版还是调接替版。
4. **已发布版本「只能向前关闭」是唯一的例外，也是模块能运转的前提。**
   若连收紧都禁掉，第一个开放区间（`to` 为 NULL）的版本发布后就永远无法被接替，
   整个版本机制第二天就死了。所以规则 R5 允许把 `to` 从 NULL 收到一个
   **仍在未来**的时刻，但**不许重开、不许往后延、不许落进过去** ——
   收紧只能往前，保证「历史某时刻生效的是哪一版」永不被追溯改写。
5. **「发布后不可变」在三层各钉一遍**：服务层（先给可执行的中文原因）、CHECK、
   以及**触发器**。CHECK 在 PostgreSQL 里可以被 `NOT VALID` 或迁移脚本绕过，触发器不行。
   `publishedVersionIsImmutableEvenThroughDirectSql` 这个用例直连 SQL 验证：
   改名 / 改版本号 / 撤回发布 / 删版本 / 给已发布版本加组件 / 改组件数量 / 删组件，全部被拦。
   组件守卫 (`guard_bundle_component_published`) 落在版本这一层，
   因为蓝图标在 3.5.1.2 的「已发布版本的组件不可改」本身就是版本表的纪律。
6. **组件清单行挂在版本上而不是套装上。** 「已发布版本的组件不可改，只能新建版本」要求每条组件记录
   都能回答「我属于哪一版」。数量用 `NUMERIC(19,6)` 而不是 int：套装里出现
   「0.5 米挂绳 + 2 个挂钩」是真实场景（3.5 挂在「组合与物料」下，BOM 侧本来就有单位用量与损耗率），
   int 会逼着运营把 0.5 写成「1 件」，而 09 订单展开与 07 库存扣减都照着这个假数走。
   组件保存用**整体替换**而不是增删式：清单有「同版本一 SKU 一行」的约束，
   增删式提交时前端要自己算最终集合，漏对账的表现是页面上剩一条「幽灵组件」。

## 关键 trade-off

- **组件做多深？** 蓝图把「组件清单」写在 3.5.1.1 的**输入**里，但把「组件增删改工作台 +
  循环引用检测」写在 3.5.1.2。本模块交付「随草稿版本编辑组件清单 + 发布时校验组件齐备与数量 > 0」，
  循环引用检测与独立组件工作台留给 3.5.1.2。理由：发布时组件齐备是可判定的硬条件，
  不能等到 3.5.1.2 才补；而组件**只引用 SKU 不直接引用套装**，嵌套套装走
  3.1.2.2「套装作为可售 SKU」，所以 `bundle_component` 保持一条干净的 SKU 外键，
  本文件不需要含糊的多态列。
- **删除套装/版本的口子有多紧？** 含任何版本都不许删（草稿是待办但仍记录了规划，
  已发布版本更是历史凭据），要停止使用只能归档 `bundle`。
  「归档」与「删除」分开是为了让 09 订单在套装归档后仍能读到当时的组件展开结果。
- **前端是否注册进 TASK_PAGES？** 没有。当前仓库里已交付的 3.4.x 模块页面
  （`/products/media`、`/products/translations` 等）都**不在** `packages/ui/src/lib/task-pages.ts` 里，
  只能靠直达 URL 到达。沿用现状而不动共享导航，避免与其他模块的导航测试计数冲突；
  这是一个**仓库级缺口**，不是本模块引入的。

## 踩到的坑（已实测，务必照做）

- **`?<` 在 jOOQ 里不是绑定标记。** `effective_from<=? and (... or ?<effective_to)` 里
  `?<` 被 jOOQ 的解析器当成 jsonb 操作符，于是绑定值数量对不上，报
  「未设定参数值 4 的内容」。必须写成 `? <`（中间留空格）。
- **jOOQ `fetch(String, Object...)` 会把 UUID 与时间按 varchar 送过去。**
  - `effective_from<=?` 报 `operator does not exist: timestamp with time zone <= character varying`
    → 必须 `?::timestamptz`（AGENTS.md 已经点过这个坑，这里是同一坑的另一个入口）。
  - `id in (?)` 报 `operator does not exist: uuid = character varying`（`=` 没事是因为 PG 能从等号另一侧反推，
    `IN` 的单元素列表不行）→ 占位符统一发成 `?::uuid`。
  - 顺带一个**绑值顺序**坑：`... and id in (...) and effective_from<=?` 的绑定顺序是
    `[tenantId, ids..., now, now]`，写成 `[tenantId, now, now, ids...]` 会报
    「cannot cast type uuid to timestamp with time zone」。
- **迁移自检脚本的假阳性**：`.tmp/v23-pgcheck.sh` 第一版用
  `docker exec -i … psql …` 读脚本文件，但 psql 没收到文件内容（stdin 没接上），
  于是「V1..V23 全部通过」其实是**执行了 23 段空 SQL**。Flyway 只报字节偏移，
  必须用一次性 PG16 容器逐条 `psql -f` 跑一遍才拿得到真实语法错误与行号。
  第二版 `.tmp/v23-behavior-check.sh` 还要先手工建 `flyway_schema_history`
  （V1 里有 `REVOKE ALL ON flyway_schema_history`，而真实运行时这张表由 Flyway 自己先建）。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V23__bundle_version.sql`

**后端**
- `backend/services/catalog/src/main/java/com/starcon/catalog/BundleRules.java`（新增，纯规则层）
- `backend/services/catalog/src/main/java/com/starcon/catalog/BundleVersionService.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/BundleVersionController.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（追加 14 个 `Bundle*` DTO）

**测试**
- `backend/services/catalog/src/test/java/com/starcon/catalog/BundleRulesTest.java`（13 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/BundleVersionIntegrationTest.java`（17 例，含租户隔离三断言）
- `apps/web/src/components/catalog/bundle-model.test.ts`（19 例）

**前端**
- `apps/web/src/components/catalog/bundle-model.ts`（新增）
- `apps/web/src/components/catalog/bundle-workspace.tsx`（新增）
- `apps/web/src/app/products/bundles/page.tsx`（新增）
- `apps/web/src/lib/api-proxy.ts`（`bundles` / `versions` 白名单放行）

**契约（生成物，只读）**
- `packages/api-contracts/src/catalog.ts`（+14 个 `Bundle*` DTO，纯新增）
- `packages/api-contracts/src/catalog.openapi.json`

**台账与会话**
- `docs/product/capability-blueprint/module-progress.md`（3.5.1.1 → 已交付）
- `docs/sessions/2026-10/2026-10-07-bundle-version-management.md`（本文件）

## 验证

- `cd backend && ./mvnw verify` — BUILD SUCCESS（identity / catalog / channel / warehouse 全绿）
- `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` — 386 DTO，纯新增
- `pnpm typecheck` / `pnpm lint` — 通过（lint 只剩仓库既有 warning）
- `pnpm test` — 35 个文件 / 522 例通过
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` — Compiled successfully，`/products/bundles` 9.68 kB
- `node tools/module-progress/sync.mjs --check` — 台账与蓝图一致
- 额外：`.tmp/v23-behavior-check.sh` 在一次性 PG16 容器里跑完 V1..V23 后，
  用纯 SQL 逐条断言 R1 半开区间 / R2 重叠拒绝 / R3 草稿可重叠 / R4 当前生效唯一 /
  R5 向前关闭 / 发布后不可变 / 组件冻结 / RLS 隔离，全部按设计通过

## 后续待办

- **3.5.1.2 组件维护**：循环引用检测（套装 A 含套装 B 且 B 含 A）与独立组件工作台。
  本模块已把 `bundle_component` 的形状建对（行挂版本、数量 `NUMERIC(19,6) > 0`、
  SKU 用复合外键），3.5.1.2 在这之上做即可。
- **3.1.2.2 套装作为可售 SKU**：套装本身不占 SKU 行；刊登需要一个可售 SKU 时由那一侧建 SKU
  并引用 bundle + bundle_version。本模块不写 SKU。
- **07 库存 / 09 订单**：虚拟套装不持有独立库存，扣的是组件库存。
  07 按本表的组件清单展开预占，09 下单时读当时的生效版本并把展开结果冻结进订单行组件。
- **仓库级缺口**：catalog 的模块页面没有进 `packages/ui/src/lib/task-pages.ts`，
  侧栏与搜索里找不到，只能直达 URL。已交付的 3.4.x 全都如此，建议单开一个任务统一补。