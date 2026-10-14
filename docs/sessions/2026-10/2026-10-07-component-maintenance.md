# 3.5.1.2 组件维护（D1–D2）

## 决策摘要

蓝图 3.5.1.2 要求「维护套装组件 SKU 与数量，校验不出现循环引用（套装 A 含套装 B 且 B 含 A）
与组件数量必须大于 0」，输出是「组件清单；校验结果（循环、数量非法、组件不存在均拒绝保存）」。
3.5.1.1 已交付「随草稿版本编辑组件清单 + 发布时校验齐备」，并在 V23 文件头第四节
**逐字把两件事留给了本模块**：循环引用检测、独立组件工作台。

七个关键判断：

1. **先发现「循环在数据上无处发生」，再补一条指向。** V23 的 `bundle_component` 只引用 `sku`
   不引用 `bundle`（刻意保持一条干净的 SKU 外键），所以「套装 A 含套装 B」在只有
   `bundle_component.sku_id` 的前提下**根本无法表达**——循环也就无从发生。
   蓝图自己给了表达方式：3.5.1.1 关系栏「下游 = 3.1.2.2（套装作为可售 SKU）」，
   即**套装自己先成为一个可售 SKU 行，再被别的套装当组件引用**。V24 因此建 `bundle_sku`
   承载这条「SKU ↔ 套装」指向。没有它，本模块的核心职责（循环检测）就是空的。
2. **不给 `bundle_component` 加 `bundle_id` 多态列。** 三个理由：组件**绝大多数**不是套装，
   多一列可空外键会让 99% 的行带着空值，而空值与「指向套装 0」分不开；
   已发布版本冻结的是「当时那份 SKU 清单」，而套装指向是**间接且可变的**
   （SKU 可以重新代表别的套装），摊平进组件行会让历史订单冻结的展开结果随重新指向一起变；
   V23 文件头已把这个选择写死，本模块沿用，不推翻上游已交付的形状。
3. **成环不是「套装直接引用自己」，而是要沿包含链走一圈。** 规则 R6 把套装看成图上的点、
   「X 的某版本里有一个代表 Y 的可售 SKU」看成边 X→Y；写入后若从新指向的套装能走回
   写入方所属的套装就是环。于是 **A 含 B 永远合法**（B 自己没被 A 包含过），
   只有闭合才拒。这决定了「可达性」是唯一判定入口，而不是「查有没有直接反向边」——
   后者会漏掉 A→B→C→A。
4. **遍历范围含草稿（规则 R7）。** 草稿的组件行是真实存在的一行数据、马上就要被发布。
   只看已发布的话，「A 的草稿里塞进 S_B（B 已发布且含 S_A）」在保存时不报，
   等 A 的草稿一发布就成环，而那一刻已经晚了。宁可现在拦。
5. **自引用就是长度为 1 的环（规则 R8），在写入侧拒绝而不是在展开侧截断。**
   在展开侧加深度上限等于给一个错误答案编造「到这里就够了」的解释。
6. **拒绝而不是自动拆环（规则 R9）。** 自动拆需要替运营决定「断哪一条」，
   而运营比我们清楚哪条是笔误。给出**整条环**（套装编码链 `A → B → C → A`）让他自己改，
   是唯一不编造的做法。所以 `find_bundle_cycle_path` 返回 `uuid[]` 路径而不是布尔。
7. **改指向必须单独查环。** 「改一个 SKU 代表哪个套装」会**一次性改掉所有把该 SKU 当组件的
   套装的出边**。不查这一侧的话，「先建好 A→B，再把 B 的可售 SKU 改指回 A」就是一条
   绕过组件侧检测的成环路径。V24 因此有第二个触发器 `guard_bundle_sku_cycle`。
   测试 `relinkingASkuToCloseACycleIsRefused` 专门钉这一条：它演示的是
   「不新增任何组件行、只改一条指向就闭合成环」。

## 关键 trade-off

1. **校验结果独立成接口（`GET /catalog/versions/{id}/component-check`）而不是只靠保存报错。**
   保存被拒只给一条原因，运营的下一个动作却是「一次改完」。不这么做就得反复提交试错。
   代价是多一个端点与一份 DTO，收益是循环/数量/不存在一次列全。
2. **成环判定在 Java 与 PL/pgSQL 各实现一次，服务层给人话、触发器兜底。**
   与 3.5.1.1 的「发布后不可变」同一套纵深防线：CHECK 在 PostgreSQL 里可被 `NOT VALID`
   或迁移脚本绕过，触发器不行。代价是两份实现必须保持同一口径，
   所以规则层做成纯函数并被单测钉死，环路文案的形状（首尾相接、起点是谁）在两侧都写死。
3. **整体替换（3.5.1.1）与逐行增删（3.5.1.2）并存而不是二选一。**
   `PUT /versions/{id}/components` 是「把清单改成提交的那份」，适合已经在别处整理好一份清单；
   `POST /versions/{id}/components` 是「加一行」，适合工作台上一行一行地加。
   两条语义不同，因此路径必须能区分开（同一路径不同方法容易在网关层被合并）。
   逐行改需要行级乐观锁，所以 V24 给 `bundle_component` 加了 `version` 列 ——
   没有它，两个人同时打开同一份清单时后写的会静默覆盖先写的，
   而组件清单是要冻结进订单的东西。
4. **单行修改不允许改 SKU。** 组件行的身份是「这个版本里的这个 SKU」，
   换 SKU 走「删一行 + 加一行」，历史更清楚（改了什么就是改了什么）。

## 实测踩到的坑

- **`fetchOne(多列 select ...)` 在 0 行时返回 null，不是空记录集。**
  清单页的 `count(*)` 最初写成 `select count(*) from <多列 select 的 FROM>` 之外的形式，
  实际踩到的是：把整段多列 select 传给 `fetchOne` 去数行，命中 0 行时 `fetchOne` 返回 null，
  于是 `.get(0, Long.class)` NPE，而**有数据时一切正常**——所以只在「筛不到东西」时炸。
  修法是让计数与清单**复用同一段 FROM 常量**：`select count(*) ` + FROM + where。
  这条也顺带保证了「清单按版本过滤」与「总数按版本过滤」永远是同一个集合（否则翻页页数对不上）。
- **成环检测里 `holder = 新指向的套装` 那一行要不要跳过，取决于「组件行此刻是否存在」，
  不取决于套装 id 是否相同。** 中间我按 id 相等跳过过一次，结果 `guard_bundle_sku_cycle`
  漏判了「改指向把 SKU 指回自己」的成环。最终口径是**不跳过**：
  先建指向再加组件时 holder 集合本来就是空的（由组件触发器在写组件那一刻拦），
  先加组件再改指向时 holder 集合里有它自己（当场拦）。两个顺序都拦得住，只是拦在不同那一步。
- **「一个 SKU 至多代表一个套装」的 owner 检查必须排除正在改的那一行。**
  不排除的话「把这条指向改到另一个套装」永远会被自己拦下，改指向功能等于不存在。
- **测试夹具里最容易自己骗自己的一类错：把 SKU 指向「正在用它的那个套装」。**
  那样组件触发器报的是自引用，测试看着像「成环判定生效了」，其实验的是别的东西。
  正确夹具是：指向挂在套装 B，A 用它当组件。断言里的套装编码必须取**夹具真实值**
  （`unique()` 随机生成）而不是写死字面量 —— 写死等于断言一个与真实编码无关的字符串。
- **成环报错的环路起点是「持有者」而不是「被写入方」。** 组件侧从 parent 读起，
  指向侧从 holder 读起（先说「谁的组件出了问题」）。这不是 bug，是两侧各自更自然的一侧，
  但断言必须跟着实际口径写，否则会以为算错了。

## 文件清单

- 迁移：`backend/services/catalog/src/main/resources/db/migration/V24__bundle_component_maintenance.sql`
  （`bundle_sku` 表 + `bundle_component.version` + `find_bundle_cycle_path` / `bundle_cycle_codes`
  - 两个成环触发器 + 三条索引 + RLS/GRANT）
- 后端：`BundleComponentRules.java`（纯规则，R6~R9）、`BundleComponentService.java`（jOOQ 显式 SQL）、
  `BundleComponentController.java`（端点）、`CatalogModels.java`（+9 个 DTO）
- 测试：`BundleComponentRulesTest.java`（16 例）、`BundleComponentIntegrationTest.java`（15 例）
- 前端：`apps/web/src/components/catalog/component-model.ts`、`component-workspace.tsx`、
  `component-model.test.ts`（21 例）、`apps/web/src/app/products/bundles/page.tsx`（加「组件维护」标签）、
  `apps/web/src/lib/api-proxy.ts`（放行 components / bundle-skus / component-check）
- 契约：`packages/api-contracts`（codegen 产物，+9 个 DTO）
- 台账：`docs/product/capability-blueprint/module-progress.md`（3.5.1.2 一行）

## 验证方式

- `cd backend && ./mvnw verify` → BUILD SUCCESS，catalog 648 例 Tests run / 0 Failures
- `pnpm typecheck && pnpm lint && pnpm test` → typecheck 3/3、lint 3/3、test 36 文件 544 例全绿
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 构建成功
- `node tools/module-progress/sync.mjs --check` → 台账与蓝图一致
- 迁移在临时 `postgres:16-alpine` 容器里按 V1..V24 顺序逐条跑通，并用 10 个手写场景
  验证成环判定（合法嵌套放行 / 自引用 / 二元环 / 三元环 / 改指向成环 / 数量非法 / 重复 SKU / RLS）

## 后续待办

- **07 库存预占组件**、**09 订单 `order_line_component` 冻结展开结果**：两者都读
  `GET /catalog/versions/{id}/components`（3.5.1.1）与本模块的 `GET /catalog/components`（反查方向）。
  嵌套套装在 09 侧展开时需要一个**深度上限**作为兜底（本模块保证不成环，但脏数据或跨服务写入
  理论上仍可能构造出环）。本模块不做展开，那是 09 的职责。
- **`bundle_sku` 的写入所有权在本模块**：它存在的唯一理由是让循环可判定。
  3.1.2.2（SKU 详情）只读它来展示「这个 SKU 代表哪个套装」，不写。
- **3.5.2.x BOM**（3.5.2.1 / 3.5.2.2）本模块**未触碰**：BOM 用于生产/加工、产生实物成品并
  持有自己的库存，套装用于销售、虚拟扣组件（实体目录 §3.4），两者分表。
