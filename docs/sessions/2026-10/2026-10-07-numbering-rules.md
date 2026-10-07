# 17.5.1.2 编号规则（阶段 D1–D2）

车道：`feat/17-5-1-2`，基线 `integration/module-dev`（560a822）。
上游 17.5.1.1「业务参数」在集成头已交付（V48 + BusinessParameterController + 集成测试），本模块照抄其写法与命名。

## 决策摘要

蓝图对本模块点名三件事，逐一落到具体机制：

**1. 前缀 + 日期 + 序号 + 后缀，且支持多级嵌套。**
规则不由一个模板字符串描述，而是由**有序的段**（`document_number_rule_segment`）拼成：
`PREFIX / DATE / SEQUENCE / SUFFIX / LITERAL / NESTED`。
`NESTED` 段引用另一条 `COMPONENT` 子规则，渲染时递归展开。

- 为什么用段表而不是 pattern 串：段表能把「序号」这一段单独拎出来查、
  单独做重置分桶，字符串模板做不到；而序号重置恰恰是本模块的核心语义。
- 库里有 `document_number_rule_segment_shape` CHECK 钉死「每种段只认属于它自己的列」，
  写错形状在插入时就被挡下，而不是渲染时才发现。

**2. 序号按实体/日期重置。**
计数器键是 `(tenant_id, entity_code, scope_key, reset_key)` 四列：

- `entity_code` 写**根规则**的实体（不是子规则自己的）。同一个子规则嵌到订单与采购
  两条规则下时，两个实体各有各的计数线，互不推进对方的序号。
- `reset_key` 按 `NONE/DAILY/MONTHLY/YEARLY` 取 `ALL` / 业务日期 / 年月 / 年。
  **分桶用业务日期而不是 `now()`**——补录上周的订单要落上周的桶接着编号，
  否则会插进今天的序号中间把当天的号全顶开。
- `scope_key` 取 `ENTITY`（换规则接着数）或 `RULE:<规则 id>`（新规则从头数）。

取号走 `insert … on conflict … do update set current_value = current_value + 1 returning`，
递增与读回在同一条语句里完成，并发请求不会撞号；不做应用层读-改-写。

**3. 规则冲突要在配置时校验；编号不是外键。**
- 同一 `entity_code` 上两条**启用主规则**（`rule_kind='PRIMARY'`）的生效区间不允许交叠，
  `create` / `update` 写入前查一次 `overlaps`，交叠返回 409。
  半开区间语义：`a.to == b.from` 算相邻不算冲突（前一条到点失效、后一条接上生效是正常配置）。
  `COMPONENT` 与 `DISABLED` 都不参与判定。
- `document_number_issued.document_type / document_no` 是**可读标识**，不建跨库外键
  （单据本体在各服务自己的库里）。唯一性由 `UNIQUE(tenant_id, entity_code, number_text)` 兜：
  两条规则碰巧渲染出同一个编号时，第二次发放失败而不是产生两个"看起来一样"的单据号。

## 关键 trade-off

- **预览不占号，取号才占号。** 两个出口共用同一段 `render()`，区别只在 `consume` 布尔：
  预览读当前计数器 +1（桶不存在则从 `sequence_start` 起算），取号走原子 upsert。
  预览与实际不一致是编号系统最难排查的故障，所以宁可多一次查询也不能让两者分叉。
- **预览的 `parts` 是「摊平轨迹」而不是「逐段树」。** NESTED 段**不额外补一行**，
  子规则的段直接摊进来并各自带 `depth`；否则同一段内容会被数两遍。
  但规则详情里 NESTED 段自身仍要有 `rendered`，所以 `Outcome` 另带一张
  `bySegment`（本规则自有段 → 渲染文本，含 NESTED）。这是两个不同用途的视图，不能共用一张表。
- **嵌套环在配置时拒 + 渲染时兜深度上限（8 层）。** 写入时用 `descendsFrom` 查可达图；
  渲染侧再兜一层是因为数据可以被直接改库，渲染不该因此变成死循环或爆栈。
- **日期段按根规则的时区渲染，整棵嵌套树共用一个「今天」。** 否则父子规则可能渲染出两个日期。
  测试里 `dateSegmentRendersInTheRuleTimezoneNotUtc` 专门钉住这条：同一时刻在
  Asia/Shanghai 是 10-08、在 UTC 是 10-07。
- **`document_entity` 是全局码表不带 tenant_id**，按 V3/V48 的先例（page_resource /
  business_parameter_definition 同类），不加 RLS。租户没有「新增单据类型」的需求——
  那意味着新增一整条业务链路，不是配置项能决定的。

## 文件清单

新增：

- `backend/services/identity/src/main/resources/db/migration/V52__document_number_rule.sql`
- `backend/services/identity/src/main/java/com/starcon/identity/NumberRuleController.java`
- `backend/services/identity/src/test/java/com/starcon/identity/NumberRuleIntegrationTest.java`（21 项）
- `apps/web/src/components/iam/numbering-model.ts`
- `apps/web/src/components/iam/numbering-model.test.ts`（37 项）
- `apps/web/src/components/iam/numbering-workspace.tsx`
- `apps/web/src/app/org/numbering-rules/page.tsx`

改动：

- `apps/web/src/components/iam/identity-workspace.tsx`（导航目的地 + `visible()` 的页面权限码）
- `apps/web/src/lib/api-proxy.ts`（放行 `numbering` 前缀下已实现的 8 条路径）
- `packages/api-contracts/src/catalog.ts` + `identity.openapi.json`（codegen 产物，禁止手改）
- `docs/product/capability-blueprint/module-progress.md`（仅 17.5.1.2 那一行 + sync.mjs 派生的计数）

## 踩过的坑（值得记住）

- `record.get("sequence_start", BigDecimal.class)` 对 `bigint` 列会抛 `IllegalArgumentException`，
  被 `ApiErrorHandler` 报成 400「字段格式不正确」，与真实原因完全无关。
  必须先读成 `Long` 再转 `BigDecimal`。
- `Map.of(...)` 不接受 null 值，而复制一条含非文本段的规则时 `literal_text` 就是 null →
  `NullPointerException`。构造快照一律用 `LinkedHashMap`。
- 前端 `detectsNestingCycle` 最初只用一个 `visited` 集合，把**指向祖先的合法分支**
  （A→B、A→C、C→B）和真正的环 A→B→A 一起剪掉了，间接成环检测不出来。
  必须区分 `visiting` / `done` 两种状态。
- `?::timestamptz` 放在 values 末位会让 jOOQ 整体绑定错位一格，本模块所有写入都把 cast 挪开。

## 后续待办

- 台账「L3 锚点路由」列仍是 `—`：L3 锚点行本身没写路由，按纪律不改蓝图，
  页面地址已写在证据列（`apps/web/src/app/org/numbering-rules/page.tsx`）。
- 下游接号方（9 审单 / 6 采购 / 8 仓储作业 / 11 售后）尚未接入：
  它们建单时调 `POST /identity/numbering/rules/{id}/next` 即可，本模块已提供该端点。
- 若将来要支持「按店铺/法人分档的编号」，现在的实体维度是单层 `entity_code`；
  蓝图 17.5.1.2 未要求，暂不做。