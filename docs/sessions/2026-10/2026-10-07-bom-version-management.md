# 3.5.2.1 BOM 版本管理（阶段 D1–D2）

- **分支**：`feat/3-5-2-1-bom-version`（基线 `feat/3-5-1-2-component-maintenance` @ `d7860a4`）
- **模块**：能力蓝图 3.5.2.1「BOM 版本管理」，L3 锚点 `/products/bom`
- **状态**：已交付（台账 `module-progress.md` 已更新）

## 决策摘要

BOM 版本管理按 3.5.1.1 套装版本管理的**同一套语义**实现：分「档案 / 版本 / 版本组件」三张表，
生效区间 R1–R5 逐字沿用，发布后三层强制（服务层 + CHECK + 触发器）同构。
但**不合并、不复用**套装的表 —— 两者是不同业务对象。下面记的是本模块自己钉死的东西。

### 一、BOM 与套装的三条实质差异（V25 文件头第零节）

蓝图的边界只有一句「BOM 用于生产/加工（产生实物成品），套装用于销售（虚拟扣组件）」，
落到数据上是三处形状差别，缺一处就会退化成「同一张表加个类型列」：

1. **BOM 有成品，套装没有。** 套装的 `bundle` 只挂编码，**不指向任何 SKU**（虚拟组合不占 SKU 行）。
   BOM 的存在意义就是「这个**成品**由哪些组件组成」，所以 `bom.output_sku_id` NOT NULL。
   这不是多加的字段，而是数据形状的根本差别：套装行里全是输入，BOM 里「成品」与「组件」是两类角色。
2. **组件行带损耗率。** 生产会损耗（裁切边角、烧制破损、来料不良），卖套装不损耗 ——
   卖一套就是把组件原样扣出去。给套装加一个永远为 0 的 `scrap_rate` 列，
   只会让「这个数字为什么是 0」变成一个永远没人回答的问题。
3. **BOM 冻结的是实物领料依据。** 套装冻结的是 09 订单展开出的子行；BOM 冻结的是
   「08 加工单当时按哪一版、什么损耗率领的料」。**后果是触发器比 V23 更严**：
   `guard_bom_component_published` 连「改 unit_quantity / 改 scrap_rate」都挡，不只是增删改。

### 二、生效区间 R1–R5：逐字沿用，不另立一套

BOM 与套装的时间语义完全一致（都是「某一时刻该用哪一版」），所以 R1–R5 沿用 V23 文件头第二节，
`BomRules` **转发** `BundleRules` 而不复制判定 —— 同一个「区间重叠」写成两份，迟早有一份被改松，
于是出现「3.5.1.1 拦得住、3.5.2.1 拦不住」这种只在跨模块操作里暴露的漏洞。
`BomRulesTest` 里留了跨模块口径一致性断言防止转发被悄悄改掉。

BOM 比套装**更不能**「自动裁决」谁生效：套装用错一版是订单展开错了，
BOM 用错一版是**领错料、算错成本，而错在仓库里撤不回来**。所以 R2 重叠一律拒绝，
报错点名与哪一版（version_no + 区间）撞了。

### 三、损耗率语义（R6，本模块新钉死）

**蓝图写了「损耗率」但没规定单位与算法**，本模块钉死：

> **`scrap_rate` 是百分数，不是比率。`5` 表示损耗 5%，`0` 表示无损耗。上界 100。**

- 为什么存百分数（5）不存比率（0.05）：运营在页面上填的是「5%」这个心理数，转成比率要悄悄除以 100。
  更要命的是**填错不报错** —— 填 5 若按比率读就是 500% 损耗，采购量翻 6 倍且系统毫无异常。
  存百分数让「填 5」只有一个自然解释，也让数据库上界自解释。
- 上界为什么是 100 而不是无穷：100% 损耗 = 这个组件一丁点都用不上、只需要补损耗，
  **那不是一条有效的 BOM 行**（真要表达废料，请在成品上记，不该让组装单出现「领料 0 件」）。
- 默认 0 而不是必填：委外加工里「这个组件不损耗」是绝大多数情况，必填只会让人填一堆 0。
- 精度 `NUMERIC(7,4)`，全程 `BigDecimal`，禁浮点。

### 四、需求量（R7，本模块新钉死）

> **`required = unit_quantity × outputQuantity × (1 + scrap_rate/100)`，保留 6 位小数 HALF_UP。**

- **只算一层**：多级递归展开是 3.5.2.2（BOM 展开）的独立职责，它要在展开树上把子 BOM 的成品 SKU
  当作父 BOM 的组件继续往下走。本方法只把本版本**直接列出**的组件乘一遍，
  返回值里 `singleLevel=true` 让接口自证这一点，页面据此把「这里不含子 BOM 里的料」写在结果上方。
- **不取整**：取整、补安全库存、判缺口是 3.5.2.2 与 06 的决定
  （蓝图 3.5.2.2 原话「展开结果是建议不是事实」）。提前取整就是在下游拿到单据之前先替它做了决定。
- 产 0 个要拒绝：所有组件需求量都是 0 的清单会被读成「这个 BOM 什么料都不用」，
  而真状态是「你没说要生产几个」。

### 五、嵌套 BOM 的表达：刻意不做

`bom_component` **只引用 SKU，不引用 BOM**（与 V23 组件只引用 SKU 同理）。
嵌套 BOM 在数据上靠「子 BOM 的成品 SKU 出现在父 BOM 的组件行里」表达，
**那一层的判定是 3.5.2.2 的职责，本模块不预先摊平它**。摊平了（加一个可空 bom_id 多态列）
会让 99% 的行带着空值，而空值与「指向 BOM 0」分不开。

## 关键 trade-off

| 取舍                           | 选择                 | 理由                                                                                               |
| ------------------------------ | -------------------- | -------------------------------------------------------------------------------------------------- |
| BOM vs 套装：分表还是合表      | **分表**，触发器更严 | 两者生命周期与冻结对象都不同（订单子行 vs 实物领料）；合成一张表要靠类型列，冻结规则无法按类型分叉 |
| 损耗率：百分数 vs 比率         | **百分数**           | 比率下「填 5」= 500% 损耗且不报错，采购量翻 6 倍                                                   |
| 需求量：取整 vs 精确           | **精确 6 位小数**    | 取整是 3.5.2.2 与 06 的决定；提前取整等于替下游做决定                                              |
| 展开深度：单层 vs 递归         | **单层**             | 多级展开是 3.5.2.2 的独立职责，边界写在 V25 文件头末尾                                             |
| 组件清单：整体替换 vs 增删改   | **整体替换**         | 清单有「同版本一 SKU 一行」约束，增删式漏对账的表现是页面剩一条幽灵组件                            |
| 版本复制：沿用源区间 vs 从此刻 | **从此刻**           | 沿用已发布版本的起点会直接撞 R2；运营要的是「改完从现在起接替」                                    |
| 倒排跨租户：返回空集 vs 拒绝   | **拒绝**             | 返回空集的话「这个料没人用」与「这个料不属于你」分不开                                             |
| 区间重叠：自动裁决 vs 拒绝     | **拒绝**（沿用 R2）  | 用错一版 BOM 是领错料，错在仓库里撤不回来                                                          |

## 过程中修掉的两个真实缺陷

1. **`GET /catalog/boms/{id}/effective` 不带 `at` 时 NPE** ——「不传参数 = 查此刻」是最常用写法，
   却是 500。已改为默认取 `Instant.now()`。（3.5.1.1 的同名端点有同样的潜在缺陷，
   本次未改动其代码，因为组件维护模块归其它 writer。）
2. **`BomInput.outputSkuId` 为空时报「SKU 不存在」** —— 运营会以为是自己填错了编码，
   真因是「BOM 必须有一个成品」。已加显式空值检查，给出可执行的中文原因。

## 文件清单

**后端（新增）**

- `backend/services/catalog/src/main/resources/db/migration/V25__bom_version.sql` —— 三张表 + 四触发器 + 八索引 + RLS/GRANT
- `backend/services/catalog/src/main/java/com/starcon/catalog/BomRules.java` —— 纯规则层（R6/R7 自有，R1–R5 转发）
- `backend/services/catalog/src/main/java/com/starcon/catalog/BomVersionService.java` —— jOOQ 显式 SQL 服务层
- `backend/services/catalog/src/main/java/com/starcon/catalog/BomVersionController.java` —— 端点
- `backend/services/catalog/src/test/java/com/starcon/catalog/BomRulesTest.java` —— 23 例
- `backend/services/catalog/src/test/java/com/starcon/catalog/BomVersionIntegrationTest.java` —— 24 例（PG16 + 强制 RLS）

**后端（修改）**

- `CatalogModels.java` —— 追加 18 个 `Bom*` DTO

**契约（codegen 产物，只由脚本改）**

- `packages/api-contracts/src/catalog.ts`、`catalog.openapi.json` —— +18 个 DTO，纯新增

**前端（新增）**

- `apps/web/src/app/products/bom/page.tsx`
- `apps/web/src/components/catalog/bom-model.ts` / `bom-model.test.ts`（38 例）
- `apps/web/src/components/catalog/bom-workspace.tsx`

**前端（修改）**

- `apps/web/src/lib/api-proxy.ts` —— 放行 `boms` / `bom-versions` / `bom-components`
- `apps/web/src/app/api/catalog/[...path]/route.test.ts` —— +1 例路由放行/拒绝

**台账**

- `docs/product/capability-blueprint/module-progress.md` —— 仅 3.5.2.1 一行（其余为 `sync.mjs` 自动重算的计数）

## 验证

```bash
cd backend && ./mvnw verify                                    # BUILD SUCCESS（catalog 48.6s）
pnpm typecheck                                                 # 3 successful
pnpm lint                                                      # 3 successful
pnpm test                                                      # 37 files / 583 tests passed
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build     # ✓ Compiled successfully，/products/bom 13.3 kB
node tools/module-progress/sync.mjs --check                     # ✓ 台账与蓝图一致
```

测试要点：租户隔离三断言实跑（两租户互不可见 / 伪造 `X-Tenant-Id` 头无效 / 单连接池交替 5 轮不串租户，
连接池固定 `maximum-pool-size=1`）；`timestamptz` **断言写回的值**（`effective_from` 与 `published_at` 都断言，
且验证 `+08:00` 写法归一到同一时刻），不只断言「没抛异常」。

## 后续待办

- **3.5.2.2 BOM 展开**（本模块的直接下游）：多级递归展开、子 BOM 当作组件、按仓库过滤、
  缺口计算、需求量的取整策略。V25 文件头末尾已把口子留清楚，`bom_component` 的形状可直接用。
- **3.5.1.1 的潜在缺陷**：`GET /catalog/bundles/{id}/effective` 不带 `at` 时同样会 NPE
  （`BundleVersionService.effectiveVersion`）。本次未改，因为组件维护模块归其它 writer；
  建议下一轮顺手修掉，或统一抽一个「at 缺省取此刻」的公共入口。
- **委外加工的成本口径**：本模块只给需求量，不给成本。成品与组件的成本口径在 12 财务，
  跨服务引用要走授权 API/event。
