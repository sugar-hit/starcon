# 3.9.2.1 完整度评分（车道 feat/3-9-2-1）

- **日期**：2026-10-07
- **分支**：`feat/3-9-2-1`（worktree `.worktrees/3.9.2.1`，基线 `integration/module-dev` @ `d9bcd6e`）
- **交付**：能力蓝图 3.9.2.1「完整度评分」

## 决策摘要

按蓝图给每个 SPU/SKU 打主数据完整度分，输出评分卡 + 缺失项清单 + 批量补全入口。

**六个维度**（权重合计精确 1.0000，在 `CompletenessScoreRules` 的类初始化块里断言）：
必填属性 0.25 / 图片数量与尺寸 0.20 / 类目必填 0.15 / 报关属性 0.15 / 包装尺寸重量 0.15 / 认证 0.10。

**最要紧的一个取舍：两个就绪结论分开判（R3）。** 蓝图原话是「完整度直接决定能否刊登与能否清关」，
所以评分同时判两条链：刊登链（必填属性 / 图片 / 类目必填 / 认证，总分 ≥ 0.7500 且该链四维无缺失）
与清关链（报关属性 / 包装尺寸重量，总分 ≥ 0.6000 且该链两维无缺失）。
合并成一个「完整度 78%」之后，运营会看到「补到 80% 了」却仍被拦住，而且**看不出自己该去补报关还是去传图**。

## 关键 trade-off

1. **报关属性与认证建在 catalog 侧（本轮最大的边界取舍）**。
   3.7.1.1 / 3.7.1.2 与 `16 合规` 在集成头里都未交付（已逐条核对台账与 V28 文件头），
   而 AGENTS.md 禁止跨服务读表 / 跨库 FK / 共享视图。缺来源时这两个维度只能恒为缺失 ——
   **假分数比没分数危险得多，它看起来是有依据的**。所以本模块在 catalog 拥有这两类声明数据，
   并在 V37 第九节写死了 3.7.1.1 落地后的**唯一一条**接管路径（复用本表，或迁移后删本表），
   两条路都不允许「两边各存一份、评分读其中一份」。

2. **评分一律现算，快照只回答「过去某一刻」**（R1）。
   完整度的输入每时每刻都在变，缓存的分数一旦过期就是错的分数。而 4.2.3.2 预检复盘要回答
   「上周三拦住这批的那个分数是几分」—— 现算答不了过去，所以另落 `completeness_evaluation`，
   但它**不参与任何读出口**，删掉全部快照不影响评分功能（集成测试里专门钉了这一条）。

3. **认证四态的判定只有一份实现，落在 SQL 的 `sku_cert` 聚合里**。
   Java 侧刻意不判第二遍 —— 两处各算一遍的症状是「评分卡说已齐全、补全清单说待续期」，
   而两边读的是同一张表。

4. **租户从连接上下文读而不是 `?` 绑定**。
   十个 CTE 各带一个租户占位符，少绑一个就在运行时报「未设定参数值 N」而报错指不到原因；
   而且从入参再传一遍租户等于给了一条能传错租户的旁路。`AccessService.run` 已经把租户
   `set_config` 在连接上，RLS 读的也正是它。

5. **类目必填只计入无条件规则（R9，不做半成品的说明）**。
   `CONDITIONAL_REQUIRED` 是**录入期**的表单规则（3.3.1.2 用它生成录入表单 schema 并在保存时校验），
   求值必须复用 `CategoryAttributeRequirementRules.conditionHolds` 那一份算子实现；
   抄进评分 SQL 就多出第二份算子语义，两处对 GT / 不可比类型的处理迟早不一致。
   被条件性要求的属性若在属性定义上勾了 `required`，仍会被 ATTRIBUTE 维度正常计入。

6. **SPU 侧图片把变体的图并进来**。
   刊登取的是变体的图。SPU 判定「没图」而每个变体都说「图齐全」，会让同一件事在两个层级
   给出矛盾答案 —— 这是最难查的一类错。

## 踩过并修掉的三个真实缺陷（都不是风格问题，记录下来给后面的人省时间）

- **jOOQ 把数组切片 `arr[1:5]` 的冒号读成绑定标记**，渲染出 `)[1?]`。症状是 `binds` 与问号个数
  明明一样，运行时却报「未设定参数值 N」，而 N 指向的参数恰好是另一个合法值时，报错完全指不到原因。
  改成「先 `limit 5` 再 `array_agg`」。
- **PG 内联 CTE 时内层 `spu_category sc` 与外层 `sku_cat sc` 同名**，引用被解析到内层，
  报 `missing FROM-clause entry for table "sc"`。这个错误**只在运行期出现**，编译与单测都看不出来。
  别名全部改成全局唯一（`pcat` / `scat` / `cat` / `k`）。
- **同一 SELECT 列表里引用同级别名不被 PG 允许**。比率先在 `owner_ratios` 算好，
  分数与分档在 `owner_scored` 引用。

另修：jsonb 列被驱动交回 `PGobject` 时，「只判 `instanceof String`」的分支会静默跳过 ——
症状是「快照里没有缺失项」而库里明明存了。

## 文件清单

**新增**

- `backend/services/catalog/src/main/resources/db/migration/V37__completeness_score.sql`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CompletenessScoreModels.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CompletenessScoreRules.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CompletenessScoreService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CompletenessScoreController.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/CompletenessScoreIntegrationTest.java`
- `apps/web/src/components/catalog/completeness-score-model.ts`
- `apps/web/src/components/catalog/completeness-score-model.test.ts`
- `apps/web/src/components/catalog/completeness-score-workspace.tsx`
- `apps/web/src/app/products/completeness/page.tsx`

**修改**（codegen 产物与共享文件，只碰自己那部分）

- `packages/api-contracts/src/catalog.ts` / `catalog.openapi.json`（+19 DTO / 13 路径，纯新增）
- `apps/web/src/lib/api-proxy.ts`（加一条完整顶层分支）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（+1 例）
- `docs/product/capability-blueprint/module-progress.md`（只改 3.9.2.1 那一行）

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS，catalog 1078 例 0 失败 0 错误
- `pnpm typecheck` → 3 successful
- `pnpm lint` → 3 successful
- `pnpm test` → 45 files / 776 tests passed
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → Compiled successfully，
  `/products/completeness` 4.8 kB
- `npx vitest run "apps/web/src/app/api/catalog"` → 29 passed
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致
- 租户隔离三断言实跑：两租户互不可见、伪造 `X-Tenant-Id` 头无效、单连接反复交替 5 轮不串租户

## 后续待办

- **3.9.2.2 僵尸主数据清理**（本轮明确不做）：识别从未被订单引用、无库存、无 listing、
  成本为 0、长期未维护的 SKU/供应商/客户。**停用而非删除**（实体目录 §5）。
  本模块不预留它的字段 —— 它的判据需要订单 / 库存 / listing 引用统计，全在别的服务里，
  按模块边界只能走 event 或投影端口；强行留一列只会造出第二个真相源。
- **3.7.1.1 落地时**按 V37 第九节接管报关与认证表，并给 `sku_customs_declaration` 加目的地版本引用列。
- **3.7.1.2 目的地报关版本**落地后，本表仍是目的地无关的基线申报要素，版本化在它自己的表里。
- **4.2.3.2 刊登预检**调用 `POST /catalog/completeness/precheck/listing`（已提供出口，未改下游）。
