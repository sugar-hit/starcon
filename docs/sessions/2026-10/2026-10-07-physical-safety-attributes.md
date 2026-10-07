# 3.7.2.1 物理与安全属性（D1–D2）

## 做了什么

交付能力蓝图 3.7.2.1「物理与安全属性」：裸品物理参数（长宽高、净重、毛重、包装方式）
与安全属性（带电 / 液体 / 危险品 / 磁性）的完整读写、体积重与体积的派生计算、
按安全属性的筛选查询、体积核算能力，以及「决定可走哪些物流渠道与是否需要特殊申报」
对应的可查询判定接口。

基线 `integration/module-dev`（`fa8dee8`），车道分支 `feat/3-7-2-1`，迁移版本 **V28**（编排器预分配）。

蓝图职责逐条对应：

| 蓝图要求                       | 落地                                                       | 归属                    |
| ------------------------------ | ---------------------------------------------------------- | ----------------------- |
| 物理参数（长宽高/净重/毛重/包装方式） | 读写既有 `sku_package`（V6 建，3.1.2.2 详情页在用）      | catalog，**不另建表**   |
| 安全属性（带电/液体/危险品/磁性） | 新表 `sku_safety_attribute`                                | catalog（V28）          |
| 体积、体积重、计费重           | `PhysicalSafetyRules` 纯函数现算                            | 派生值**一律不落表**    |
| 供 10 物流渠道筛选             | `GET /catalog/physical-safety/profiles`                     | catalog（渠道规则属 10） |
| 供 8 拣货装箱体积核算          | `POST /catalog/physical-safety/carton-calc`                 | catalog（箱规由调用方给） |
| 决定渠道可用性与特殊申报        | `POST /catalog/physical-safety/skus/{id}/channel-verdict`    | catalog（渠道表不建）   |

## 关键 trade-off

### 1. 物理参数复用 `sku_package`，不另建表（最重要的一条）

蓝图把「长宽高、净重、毛重、包装方式」写进本模块职责，但 **V6 早已把这些列建在
`sku_package` 上**，且 3.1.2.2 的详情页已经在读写它们。**没有另建一张表抄一遍**。

理由是 V5/V6 自己在文件头立下的纪律：catalog 一旦把同一个数字存成第二个本地列，
它就成了第二个事实来源。同一张 SKU 出现两个不同答案的毛重时，「10 物流按哪个算体积重」
这个问题没人答得上来，而算错的那一次会变成一张**真实的面单**。

V6 的文件头还直接把分工写死了：「**3.7.2.1 物理与安全属性在 sku_package 之上补
是否带电/液体/危险品/磁性**」。本模块照这句话执行：物理参数读也读 `sku_package`、
写也只写 `sku_package`（同一个 `ON CONFLICT (tenant_id,sku_id)` 目标）。

代价是本模块的写端点要同时写两张表，测试里专门加了一条
「`sku_package` 的 NUMERIC(19,6) 精度未被本模块改坏」的回归断言 ——
复用而不复制，靠这条断言钉住「别哪天顺手把列类型改了」。

### 2. 「未采集」与「都不带」是两件事

`sku_safety_attribute` **没有这一行** = 还没人采集过；**有这一行且四项全 false** =
已采集、确认什么都不带。二者在业务上不是一回事：前者下一步是「去 SKU 建档补采集」，
后者是「确认无需申报」。

所以四个标记是 `boolean NOT NULL DEFAULT false`（有行即可表达「都不带」），
而不是可空。渠道判定在未采集时**一律拒发**（规则 R2）—— 默认当成普通货放行，
等于给了一条「不填安全属性就能绕过申报」的路。前端也把这两态做成不同的文案与语气
（`未采集` 警示色 + 单独横幅说明后果 vs `已采集 · 无特殊属性` 中性态）。

### 3. 派生值一律不落表，除数是入参

体积重 = 体积 ÷ 除数，而**除数是渠道自己的口径**（空运 6000 / 快递 5000…）。
落一列「体积重」等于偷偷替 10 物流选定了除数，而它随渠道变化 —— 改一次渠道就要全表重算一次。

因此 `GET /skus/{id}?divisorCm3PerKg=` 的除数是**入参**，不给就不返回体积重，
而不是套一个默认值（套了默认值，两边口径不一致时错的是运费）。
页面上给了除数选择器与「空运 6000 / 快递 5000」快捷键，但不给隐式默认。

同样地，**不建渠道表**：渠道与限制是 10 物流的事实。`channel-verdict` 接收
「四个 allowed 开关 + requirePhysicalParameters + 除数」，回答「按你给的限制这个 SKU 能不能走」。

### 4. 四个独立布尔，不合并成一个枚举

蓝图逐字列了四个标记，它们在物流上互不蕴含（带电→电池申报 / 液体→防漏包装 /
危险品→危险品申报 / 磁性→磁性物申报）。用「特殊属性」枚举会把「带电且危险」这种
真实存在的组合压成一行，而组合恰恰是渠道判定要用的东西。

**不建 UN 编号 / 危险品类别 / 电池类型列** —— 那是 3.7.1.1「HS 编码与申报要素」的职责，
该模块当前未开发且台账已记录它被 `product_compliance` 的表结构缺陷阻塞。
本模块只回答「是否需要特殊申报」，**不回答「申报什么」**。

### 5. 装箱件数取两条约束里更紧的那个

体积与限重都算，逐条都比。同一 SKU 在一次核算里出现多行时**先合并再算** ——
分两行各算一次会得到两个各自成立的箱数，而真实的拣货是把这几行装进同一批箱子。
箱数向上取整（5 件、每箱 2 件 → 3 箱，不是 2 箱）。

**单测抓到一个真 bug**：两条约束都算不出来时原本返回 `Integer.MAX_VALUE`，
它会一路传到「需要几箱」而算出一个看似正常的 1 箱，把「算不出来」变成一个错的小数。
改成 0 并在 `note` 里写明「算不出来」。

### 6. 权限用 `catalog.compliance.*` 而不是 `catalog.sku.*`

3.7 在蓝图里是「商品合规属性」一级（identity 已有 `catalog.compliance` 页面资源
「合规与资质」），而本页回答的是「能不能发、要不要申报」—— 这是合规判定而不是商品资料编辑。
且物理参数与安全属性在**同一个端点**里写，用两套权限会出现「能改尺寸却改不了安全属性」的半截状态。

### 7. records 单独成文件，避���并行车道的行级合并冲突

`CatalogModels.java` 是所有车道都会追加的公共文件，且**不在** `merge-union` 驱动器的
按键求并范围内（驱动器只覆盖 `packages/api-contracts` 与台账）。
本模块的记录放进公共文件会让两条车道在同一处各追加一段，行级三方合并必然冲突。
因此新建 `PhysicalSafetyModels.java`（与 `CategoryAttributeRequirementForms` 的做法一致）。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V28__physical_safety_attributes.sql`

**后端**（`backend/services/catalog/src/main/java/com/starcon/catalog/`）
- `PhysicalSafetyModels.java` — 请求/响应 record
- `PhysicalSafetyRules.java` — 纯规则（体积/体积重/计费重/申报/渠道判定/装箱/筛选归一化）
- `PhysicalSafetyService.java` — jOOQ 显式 SQL 读写、筛选、判定、核算
- `PhysicalSafetyController.java` — 5 个端点，权限 `catalog.compliance.read/update`

**测试**（`backend/services/catalog/src/test/java/com/starcon/catalog/`）
- `PhysicalSafetyRulesTest.java` — 31 例纯规则
- `PhysicalSafetyIntegrationTest.java` — 28 例，真实 PG16 Testcontainers + 强制 RLS

**前端**
- `apps/web/src/app/products/sku/[id]/physical/page.tsx`
- `apps/web/src/components/catalog/physical-safety-model.ts`
- `apps/web/src/components/catalog/physical-safety-model.test.ts` — 27 例
- `apps/web/src/components/catalog/physical-safety-workspace.tsx`
- `apps/web/src/components/catalog/sku-detail-workspace.tsx` — 包装卡加「去维护物理与安全属性」入口（仅加链接）

**生成物 / 台账**
- `packages/api-contracts/src/catalog.ts`、`catalog.openapi.json`（codegen 产物，+11 DTO）
- `docs/product/capability-blueprint/module-progress.md` — 仅 3.7.2.1 那一行 + 自动计数

## 验证

```bash
cd backend && ./mvnw verify                      # BUILD SUCCESS，6 模块全绿
pnpm typecheck && pnpm lint && pnpm test          # 628 例全绿（含新增 27）
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build   # 含 /products/sku/[id]/physical
node tools/module-progress/sync.mjs --check       # ✓ 台账与蓝图一致
```

后端新增 59 例（28 集成 + 31 单测）。集成测试**实跑租户隔离三断言**：
两租户互不可见（含读/写/装箱/渠道判定四种跨租户调用）、伪造 `X-Tenant-Id` 头无效、
单连接反复交替 5 轮不串租户。

## 后续待办

- **3.7.1.1 / 3.7.1.2 未开发**：本模块只回答「是否需要特殊申报」，不回答「申报什么」。
  两者的 UN 编号 / 危险品类别 / 认证要素需要先拆 `product_compliance` 表（台账已记录该阻塞）。
- **下游未开发**：10 物流（渠道表、渠道规则、面单）与 16 合规（危险品与认证）都不在本模块范围。
  本模块提供的是档案读取、按属性筛选、渠道判定与装箱核算四个口子。
- **页面落点**：蓝图 L3 锚点路由是 `—`，按职责句「采集时点应尽量前置到 SKU 建档」放在
  `/products/sku/[id]/physical`。若后续 3.7.1.x 交付后需要一个统一的合规入口，
  可以再在 `/products/compliance` 下做聚合页，届时本页作为其数据来源保持不变。
