# 17.5.1.1 业务参数

## 决策摘要

蓝图对 17.5.1.1 只写了四行，但两条加粗约束决定了整体的数据模型：

1. **「参数必须有默认值与生效范围」** → 拆成「出厂默认」与「分档覆盖」两件事，各一张表。
2. **「改参数不改历史单据已冻结的行为」** → 光有变更历史不够。历史只回答"谁在什么时候改了"，
   回答不了"这张单据当时按什么参数建的"。所以必须有**冻结快照**：单据创建时把当时解析出的
   生效值整份拷进 `business_parameter_freeze_value`，之后改 override 读到的永远是快照。
   一个单据只冻结一次，重复冻结 409 —— 覆盖会让「当时按什么参数建的」重新失去唯一答案。

最终落地 5 张表（4 张租户业务表 + 1 张全局码表）、9 个端点、1 个页面、39 项测试。

## 关键 trade-off

**参数定义做成全局码表，不带 `tenant_id`。**
`business_parameter_definition` 回答的是"平台定义了哪些参数、出厂默认是什么"，与租户无关。
租户要改默认值走 GLOBAL 档 override，不去改码表——否则一个租户改默认值会污染所有租户。
先例：channel 的 `platform` / `marketplace`、identity 的 `page_resource` / `permission`
都是同类全局码表，都不加 RLS。

**校验规则放进码表，不写死在 Java。**
`allowed_values` / `validation_pattern` 两列让"这个参数长什么样才算合法"随数据进版本库，
新增一类参数时 Java 侧不需要新增分支。后端 `validated()` 与前端 `validateValue()`
读同一份码表、同一顺序，两侧口径一致；后端仍是最终判定。

**店铺维度只存逻辑 ID + 写入时快照的标签，不建跨库外键。**
`shop` 属于 channel 服务的独立库，AGENTS.md 禁止跨服务数据库访问。
沿用 channel 侧 `shop.legal_entity_id` 存逻辑 ID 的既有做法。
代价是 identity 查不到店铺可读名，所以店铺标签由调用方带入并在写入时落库快照 ——
代价换来的是变更历史与冻结快照在店铺改名后仍然自解释。

**生效值解析只写一段 SQL，`/effective` 与冻结共用。**
`RESOLVE_SQL` 用 `left join lateral` + `order by case scope_type ... limit 1` 让优先级
就是 SQL 本身。冻结快照复用同一段解析，两处逻辑不可能漂移。

**数值列 `value_numeric numeric(19,6)` 与文本列并存。**
文本列存规范化后的规范形式，数值列给下游按值读。两者必须能互相还原出同一个数，
所以集成测试对 `45` 做 `isEqualByComparingTo` 而不是 `isEqualTo`（读回是 `45.000000`）。

## 文件清单

**后端**
- `backend/services/identity/src/main/resources/db/migration/V48__business_parameter.sql`（新增）
- `backend/services/identity/src/main/java/com/starcon/identity/BusinessParameterController.java`（新增）
- `backend/services/identity/src/test/java/com/starcon/identity/BusinessParameterIntegrationTest.java`（新增，15 项）

**前端**
- `apps/web/src/app/org/business-parameters/page.tsx`（新增）
- `apps/web/src/components/iam/business-parameter-workspace.tsx`（新增）
- `apps/web/src/components/iam/business-parameter-model.ts` + `.test.ts`（新增，24 项）
- `apps/web/src/components/iam/identity-workspace.tsx`（加导航入口与 `visible()` 分支）
- `apps/web/src/lib/api-proxy.ts`（allowlist 放行 `business-parameters` 四种形状）
- `packages/ui/src/lib/task-pages.ts`（`/org/business-parameters` 标记 implemented 并入账户菜单顺序）

**契约与台账（codegen 产物，未手改）**
- `packages/api-contracts/src/catalog.ts`（+8 个 DTO）
- `packages/api-contracts/src/identity.openapi.json`（+7 路径 +8 schema）
- `docs/product/capability-blueprint/module-progress.md`（17.5.1.1 一行）

契约 diff 已逐字核对：类型集合、路径集合、schema 集合**只增不减**，
`catalog.openapi.json` 零语义变化（纯键序重排）。

## 踩过的坑

**`?::timestamptz` 放 values 末位会让 jOOQ 整体绑定错位一格。**
新建、更新、冻结、变更历史四处的 cast 都刻意从末位移开，并在注释里标注了位置
（例如「第 9 位，共 10 位」）。测试按值断言 `effectiveFrom` / `changedAt` / `frozenAt`
落在请求前后两个墙钟之间，而不是只看没抛异常。

**测试辅助方法拼出 `??shopId=...`，参数根本没被 Spring 解析到。**
表现为"法人档生效值对、店铺档不对"，一度像 SQL 优先级写错。改成
`ArrayList<String>` 拼查询串后消失。

**测试类共享容器与 `local-admin` 租户，全局档覆盖值会互相污染。**
每个用例前用超级用户连接 `TRUNCATE` 四张表（`TRUNCATE` 不触发行级触发器，
所以绕不开变更历史的不可篡改触发器）。

**`ResponseStatusException` 的响应体是空的。**
Spring 未开启 problemdetails 时 reason 不会进 body，所以错误断言只认状态码，
这与仓库里其余集成测试的写法一致。

## 验证

```
./mvnw verify                       BUILD SUCCESS（identity 117 项、catalog/channel/warehouse 全绿）
./mvnw test -Dtest=OpenApiContractTest   四服务快照刷新成功
pnpm typecheck                      3 successful, 3 total
pnpm lint                           3 successful（2 条 warning 在 catalog 侧他人文件，非本车道）
pnpm test                           Test Files 54 passed / Tests 921 passed
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build   ✓ Compiled successfully，/org/business-parameters 7.38 kB
node tools/module-progress/sync.mjs --check    ✓ 台账与蓝图一致
```

基线测试数在第一个提交前实测为 **53 文件 / 897 项**；最终为 **54 文件 / 921 项**，
新增 24 项前端单测，无一项原有测试减少。

## 后续待办

- 下游系统接入：`9 审单`、`11 售后`、`7 库存` 目前都还没有真正调用
  `/business-parameters/effective`。它们建单时应当调 `/business-parameters/freezes`
  冻结一次，之后读 `/business-parameters/freezes/{type}/{no}` 而不是实时解析 ——
  这是「改参数不改历史单据」的完整闭环，目前闭环在 identity 侧已验证，等各单据模块接入。
- `17.5.1.2 编号规则` 的上游输入（`DOCUMENT_NUMBER_PREFIX` 默认前缀）已就位，
  编号规则模块可以只读这一个参数，不必重复定义前缀概念。
- 冻结快照目前按需保留，没有清理策略。若历史单据量增长到需要归档，
  应与 `17.5.3.1 审计日志` 的留存期一起定一个统一口径。