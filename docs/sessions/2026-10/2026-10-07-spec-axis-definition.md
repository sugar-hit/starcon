# 3.1.3.1 规格轴定义

## 决策摘要

交付卷 03 商品主数据系统 3.1.3.1「规格轴定义」，分支 `feat/3-1-3-1-spec-axis-definition`，
基线 `feat/3-1-2-4-unit-conversion`（tip `a6dd0fd`）。

### 数据模型张力怎么解的

**张力**：`V1__catalog_and_flexible_attributes.sql` 里的 `attribute_definition` 挂在单个
`spu_id` 下（`UNIQUE(tenant_id,spu_id,code)`），而「规格轴」按蓝图必须是**可复用的租户级
字典**（颜色/尺码/容量要被多个 SPU 复用）。若只给现有表加字段，就等于把「可复用规格轴」
偷偷退化成「每个 SPU 自带一套」，SPU 之间无法共享值源，直接违反蓝图硬约束。

**解法**：新增四张表，**不改 `attribute_definition` 的列语义、不迁移既有行**：

| 表 | 作用域 | 作用 |
| --- | --- | --- |
| `spec_axis` | 租户级 | 规格轴字典（code/name/enabled），**无 spu_id**——这是"可复用"的落点 |
| `spu_spec_axis` | SPU | 绑定一行 = 「这个 SPU 用这条轴，由这个 variant_axis 属性承载它的选项」，含 `attribute_definition_id` 复合外键 |
| `spec_axis_option` | 租户级（按轴） | 轴的选项，值域与 `attribute_option.value` 同为 varchar(100) |
| `category_spec_axis` | 租户级 | 类目允许的规格轴规则（`spu.category` 是自由文本，3.3.1.1 未开发） |

**值源硬约束用两个触发器双向兜住**（不是应用层自觉）：

- `guard_spec_axis_option_source`（正向，BEFORE INSERT/UPDATE on `spec_axis_option`）：
  每个选项值必须能在**某个已绑定到本轴的 SPU** 的 variant_axis 属性的 `attribute_option`
  里找到同一行，否则 23514。蓝图原话「规格选项不得独立于属性选项存在」的技术兑现点。
- `guard_attribute_option_in_use`（反向，BEFORE DELETE/UPDATE on `attribute_option`）：
  仍被规格轴引用的值不允许删除或改名，防止值源被抽走后留下悬空引用。

**为什么选"新增表 + 触发器"而不是"给 attribute_definition 加租户级字典表并迁移既有行"**：
后者要动 V1 的 SPU 作用域语义并迁移既有行，会同时影响 3.1.1/3.1.2/3.2.2 已交付模块的读写路径；
前者把张力关在新表内部，既有表一行不动，既有测试与历史数据不受影响。绑定表上的
`attribute_definition_id` 已经把轴与属性显式连起来——不需要改属性表本身也能建立关联。

**不做什么（刻意取舍）**：没有加"`variant_axis=true` 的属性必须挂到轴字典"的触发器。
V1 允许这种属性独立存在，3.1.1/3.1.2 的既有测试就是这么写的；加这个触发器会把已交付模块的
写入口打碎，属于重开旧账。改为在概览里暴露 **未挂字典的 variant_axis 属性数** 作为治理缺口
指标（与 V8 的 `unregisteredSkus` 同一口径：0 与"没查过"含义不同）。

### 其他四条核心决策

2. **绑定时可自动创建承载属性**（`createAttribute=true`）。蓝图上游 3.2.1.1 属性定义列表未开发，
   管理页面不存在；若绑定必须先有属性，规格轴这条链路就断了。因此允许在绑定时按同 code 建一条
   variant_axis 属性（`OPTION` / `SKU_ONLY` / 单值 / 必填——V1 的 CHECK 唯一允许的规格轴形状）。
   不勾选则显式拒绝并说明缺什么，不静默降级。
3. **轴编码建后不可改**。承载属性与轴的 code 必须一致（触发器强制），改码会让全部绑定在写入时
   对不齐。界面在编辑态直接锁只读并说明原因。
4. **类目规则只校验"这条轴在不在允许名单"，不校验"必绑轴绑齐了没有"**。后者是面板上的
   `missingRequiredAxisCount` 缺口提示。
5. **停用与删除分开**。轴被 SPU 绑定、选项被 SKU 取用时一律拒绝删除，提示改为停用：
   停用挡住的是"新建"，历史规格照常可解析。

## 关键 trade-off

- **选项值的长度上限跟着值源走**（100），不由规格轴侧另定。两处存同一个值，
  长度数不一致会出现"值源能存、规格轴存不下"。前端 `valueProblem` 与后端同口径。
- **轴编码用 ASCII `[A-Z0-9_-]`，选项值允许中文与空格**。轴码是机器标识且要与属性 code 一致；
  选项值是人话（深空灰 / XL / 500ml）。前端两套校验分开写，注释点明为何不同。
- **矩阵行数用 `long` + 溢出钳位**。四条各 50 个选项已 6.25M 行，`int` 会溢出成负数，
  界面上画出「-1 个组合」。任一轴无可用选项时整体为 0，不把该轴按 1 假装有值。
- **`spu.category` 是自由文本，类目规则按文本绑定而非外键**。3.3.1.1 分类树未开发，没有可引用的
  类目主键。迁移头注释写明：3.3.1.1 落地后应改挂 `product_category` 主键。
- **概览缺口的断言钉在计数上而非文案上**。文案随真实数据在两个分支间切换，断言文案等于把用例
  钉死在某个数据状态上（实现期踩过）。

## 实现期发现并修掉的三个真实缺陷

1. **`guard_attribute_option_in_use` 在 BEFORE DELETE 时返回 `NEW`**：DELETE 时 `NEW` 是 NULL，
   PostgreSQL 会**静默跳过这一行的删除**——症状是"删除成功但行还在"，且不报错。
   改为 `IF TG_OP='DELETE' THEN RETURN OLD`。
2. **类目规则查询里 `array_agg(...) FILTER(WHERE required)` 与列名 `required` 撞名**，
   PostgreSQL 报 `column reference "required" is ambiguous`，导致所有绑定写入失败。
   拆成两条 `array_agg` 查询后消歧。
3. **初版在绑定触发器里校验"必绑轴绑齐"**，结果绑第一条必绑轴时第二条还没绑，检查必然失败——
   规则**永远无法满足**（绑第二条时第一条已绑，但第一条那次已经失败了）。
   该检查从触发器移到面板缺口提示。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V9__spec_axes.sql`

**后端**
- `backend/services/catalog/src/main/java/com/starcon/catalog/SpecAxisRules.java`（新增，纯规则）
- `backend/services/catalog/src/main/java/com/starcon/catalog/SpecAxisService.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/SpecAxisController.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（改：追加 3.1.3.1 DTO）

**测试**
- `backend/services/catalog/src/test/java/com/starcon/catalog/SpecAxisRulesTest.java`（新增，9 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/SpecAxisIntegrationTest.java`（新增，27 例）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/catalog.ts`、`catalog.openapi.json`

**前端**
- `apps/web/src/components/catalog/spec-axis-model.ts`（新增）
- `apps/web/src/components/catalog/spec-axis-model.test.ts`（新增，25 例）
- `apps/web/src/components/catalog/spec-axis-workspace.tsx`（新增）
- `apps/web/src/app/products/sku/spec-axis/page.tsx`（新增）
- `apps/web/src/lib/api-proxy.ts`（改：catalog allowlist 放行 `spec-axis`）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（改：新增 2 例放行测试）
- `apps/web/src/components/catalog/sku-list-workspace.tsx`（改：列表页加入口链接，最小改动）

**台账 / 日志**
- `docs/product/capability-blueprint/module-progress.md`（只改 3.1.3.1 一行；其余 5 行是
  sync.mjs 自动重算的计数器 37→38）

## 端点

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/catalog/spec-axis/axes` | `catalog.attribute.read` |
| POST | `/catalog/spec-axis/axes` | `catalog.attribute.update` |
| PUT | `/catalog/spec-axis/axes/{id}` | `catalog.attribute.update` |
| DELETE | `/catalog/spec-axis/axes/{id}?version=` | `catalog.attribute.update` |
| GET | `/catalog/spec-axis/overview` | `catalog.attribute.read` |
| GET | `/catalog/spec-axis/axes/{id}/options` | `catalog.attribute.read` |
| POST | `/catalog/spec-axis/axes/{id}/options` | `catalog.attribute.update` |
| DELETE | `/catalog/spec-axis/axes/{id}/options?value=` | `catalog.attribute.update` |
| GET | `/catalog/spec-axis/spus/{id}` | `catalog.attribute.read` |
| POST | `/catalog/spec-axis/spus/{id}/axes` | `catalog.attribute.update` |
| DELETE | `/catalog/spec-axis/spus/{id}/axes/{axisId}` | `catalog.attribute.update` |
| GET | `/catalog/spec-axis/category-rules?category=` | `catalog.attribute.read` |
| POST | `/catalog/spec-axis/category-rules?specAxisId=` | `catalog.attribute.update` |
| DELETE | `/catalog/spec-axis/category-rules?category=&specAxisId=` | `catalog.attribute.update` |

权限用属性域（`catalog.attribute.*`）而非 SKU：规格轴落在属性域内（值源就是属性选项），
但它不是 SKU 自身的档案字段，按 SKU 授权会让"没有 SKU 权限就配不了颜色"。

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS（catalog 模块 **184 例**，0 失败 0 错误；
  identity / channel 全绿）
- `pnpm typecheck` → 3 packages successful
- `pnpm lint` → ✔ No ESLint warnings or errors
- `pnpm test` → **19 files / 185 tests passed**（原 160，+25）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `/products/sku/spec-axis` 8.31 kB
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致
- 代理放行实测（临时探针跑真实 route handler 后已删除，14 条路径全 200）：
  `catalog/spec-axis/{axes,overview,axes/{id}/options,spus/{id},spus/{id}/axes,category-rules}`
  的 GET/POST/PUT/DELETE 全通；未声明的子路径（`spec-axis/unknown`）仍 404

## 后续待办

1. **3.1.3.2 组合唯一性校验**（本模块的下游，依赖已就绪）：`sku.variant_signature` 上已有
   `UNIQUE(tenant_id,spu_id,variant_signature)`，DB 层唯一性约束已存在；该模块要做的是
   矩阵展开前的冲突清单与 DB 约束的友好提示。本模块的 `matrixRowCount` 已是它要用的输入。

2. **3.2.1.1 属性定义列表未开发**：本模块的绑定端点会在 `createAttribute=true` 时自动建承载属性，
   但**属性字典本身（类目属性要求、属性用途筛选）仍没有管理页面**。3.2.1.1 落地后，
   `guard_spu_spec_axis` 的 code 一致性校验应当扩展为"承载属性必须是用户选定的属性"，
   而不是自动创建。

3. **`apps/web/src/lib/api-proxy.ts` 的 catalog allowlist 仍缺 `sku-code`**（3.1.2.3 的账，本次未修）。
   3.1.3.1 的 `spec-axis` 分支已按 backlog 建议的形式加进同一处正则，实测通过。
   `sku-code` 属于已交付模块，按调度要求未擅动。

4. **3.3.1.1 分类树落地后**，`category_spec_axis.category` 应改挂 `product_category` 主键；
   本迁移按 V1 的自由文本列实现，迁移头注释已写明这条债。