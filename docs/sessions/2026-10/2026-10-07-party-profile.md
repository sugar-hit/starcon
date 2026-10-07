# 2026-10-07 3.6.1.1 往来单位档案（party + 角色指派）

> 车道 3.6.1.1，基线 `integration/module-dev@fa8dee8`，迁移版本号 V27（预分配）。
> 归属 catalog 服务 —— 蓝图卷 03 是本实体目录的所有者，`backend/services/trade` 目前还不存在。

## 决策摘要

**同一主体一份记录，身份靠角色区分 —— 这条推翻实体目录旧稿的三表形状。**

`db/schemas/02-master-data.md` 的「往来单位」一节写的是 `supplier` / `customer` / `carrier`
三张表。本模块**不照抄**，因为蓝图在同一卷里明确否决了那个形状。旧形状里，一个既是供应商
又是客户的对手方必须建两份记录，后果不是「数据多了一点」，而是三件做不到的事：

1. 06 采购单引用 `supplier.id`、12 财务应付引用同一个供应商，而 07 售后要联系的那个客户
   在另一张表里 —— 同一个对手方有两个 id，跨表做返点结算没有稳定锚点；
2. 对方公司改名要改两行，改漏一行的症状是采购单名称与收款方对不上，要到对账日才暴露；
3. 「这两个手机号不一样」这件事在数据上根本无法表达。

所以落成 `party`（主体：企业或个人）+ `party_role`（身份：供应商/客户/承运商），
`UNIQUE(tenant_id,party_id,role_type)` 挡住同身份两条。**界面上每行直接渲染角色徽章** ——
如果角色只藏在详情里，运营看到一个既是供应商又是客户的主体会以为数据错了，
于是去建第二条记录，那正是本模块要消灭的形态。

**角色特有字段一概不放本表。** 账期、MOQ、客户分级、信用额度、接口账号一律不加。
理由不是省事：加上去就是蓝图点名否决的「一个巨大可空字段表」——
「客户没有账期」与「供应商有账期」在那种表里是**同一种存储形态**，
12 财务取默认账期时无法区分「这个客户没设账期」与「这列不属于客户」。
3.6.1.2 落地时按 `role_type` 建三张 1:1 档案表、主键 `(tenant_id, party_id)` 复合外键
指向 `party_role`，**本表不需要改一行**。

**软删除，且没有物理删除端点。** 06 采购单、12 应付、11 售后单都会引用这个 id，
删掉行之后那些单据只能显示一个 uuid，而「对手方是谁」是财务凭证上必须显示的东西。
软删除后**详情仍可读**并带 `deleted=true`（删完回头看一眼时看到「已删除」而不是凭空消失），
但列表默认不显示、**引用候选在两种模式下都不返回它**（`includeBlocked` 只放宽「状态」
不放松「已删除」）。

## 关键 trade-off

**P5 与 P6 曾经互相矛盾，是测试逼出来的。** 最初 P6 写成「停用或拉黑的主体不允许新增角色」，
结果它把 P5 的正常流程堵死了：P5 要求「先建主体 → 挂角色 → 再启用」，而主体建的时候
还没角色，只能建成停用，于是停用状态下加角色被拒 —— 流程走不通。
最后收敛成 **P6 只拦拉黑**：拉黑是「不与这个对手方做生意」，给它新增商业身份正是要拦的；
停用是「我们自己暂时不用」，而 P5 恰恰要求在停用状态下先把角色挂好。
解指派与改备注在任何状态下都允许，因为清理拉黑主体的角色正是之后要做的动作。

**`noRoleCount` 的两个数字曾经对不上。** 集成测试断言「概览与列表逐项相等」时转红：
`list` 侧写的是 `not USABLE`（把**已软删除**的主体也算进去），`overview` 侧是
`activeCount - usable`（不算）。两边各写一遍表达式的症状就是界面上两个数字对不上，
而运营没法判断该信哪个。抽成 `NO_ACTIVE_ROLE` 一处表达式后两处都引它。

**保存与流转必须分开判。** `PUT /{id}` 一开始无条件跑 P4 流转判定，结果「改个名字」
报「状态没有变化」—— 前端把当前状态原样带回来是每次编辑的常态。
现在只在状态**真的变了**时才判 P4，「状态没有变化」这个自查只属于 `/{id}/status`
（那是一次明确的状态动作）。这也是为什么状态流转要独立端点：混在保存里，
「改个名字」也会被迫填拉黑理由。

**电话的空格是规范化掉而不是拒绝。** 与 `BrandRules.logoProblem`（URI 含空格就拒）
的结论正好相反：`+86 (138) 0000-0000` 是 B2B 最常见的写法之一，为它弹一条
「不能包含空格」只会让人绕开校验；空格对拨打没有任何影响。

## 文件清单

后端（catalog）

- `backend/services/catalog/src/main/resources/db/migration/V27__party_profile.sql` ——
  `party` + `party_role`、8 条索引（含 4 条 pg_trgm GIN）、RLS + GRANT；
  文件头第六节是 P1~P7 七条语义的权威文字
- `backend/services/catalog/src/main/java/com/starcon/catalog/PartyRules.java` ——
  纯规则：P1~P7 + `usableBlock` 单一实现 + 四条不可用原因码
- `backend/services/catalog/src/main/java/com/starcon/catalog/PartyService.java` ——
  jOOQ 显式 SQL、列表/详情/保存/流转/角色增删/软删除/候选/概览
- `backend/services/catalog/src/main/java/com/starcon/catalog/PartyController.java` ——
  `/catalog/parties` 之下 13 个端点
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java` ——
  追加 10 个 `Party*` record（纯新增，未改动既有 record）
- `backend/services/catalog/src/test/java/com/starcon/catalog/PartyRulesTest.java`（21 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/PartyIntegrationTest.java`（24 例）

前端

- `apps/web/src/app/partners/page.tsx` —— 锚点路由页面
- `apps/web/src/components/catalog/party-workspace.tsx` —— 工作区
- `apps/web/src/components/catalog/party-model.ts` —— 前端模型与校验
- `apps/web/src/components/catalog/party-model.test.ts`（23 例）
- `apps/web/src/lib/api-proxy.ts` —— 放行 `parties`（含解释为什么**不**放行
  `{id}/supplier-profile`）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts` —— +1 例放行断言

共用

- `packages/api-contracts/src/catalog.ts` / `catalog.openapi.json` —— codegen 产物，
  +10 个 `Party*` DTO（纯新增）
- `docs/product/capability-blueprint/module-progress.md` —— **只改 3.6.1.1 那一行**的
  状态与证据两格（其余是 `sync.mjs` 自己重算的计数器）

## 验证

- `cd backend && ./mvnw verify` —— 6 个模块全绿（catalog SUCCESS 59.8s）
- `PartyRulesTest` 21 例 + `PartyIntegrationTest` 24 例 = 45 例全绿
- `pnpm typecheck` —— 3 个包全绿
- `pnpm lint` —— 全绿（唯一 warning 在 `variant-combination-workspace.tsx`，既有）
- `pnpm test` —— 38 个文件 / 621 例全绿
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` —— 编译成功，
  `/partners` 10.7 kB 预渲染
- `node tools/module-progress/sync.mjs --check` —— ✓ 台账与蓝图一致

租户隔离三断言在 `PartyIntegrationTest.租户隔离三断言覆盖角色指派行`：
两租户互不可见（**含角色指派行**）/ 伪造 `X-Tenant-Id` 头无效（并断言行确实落在 B 名下）/
单连接反复交替 5 轮不串租户、不串角色、候选不相交。真实 PG16 Testcontainers + 强制 RLS。
两侧都先断言「两边真的各有 2 条角色行」，否则「角色不相交」会退化成两个空集合的恒真断言。

### 反向验证（绿测不等于有效断言）

全绿之后临时改坏两处，确认断言确实咬住实现而不是恒真，随后逐字还原：

1. **把 `party` / `party_role` 的 RLS policy 改成 `USING (true) WITH CHECK (true)`**
   → `PartyIntegrationTest` 转红（`Tests run: 24, Failures: 2, Errors: 1`）：
   `租户隔离三断言覆盖角色指派行`、`编码在租户内唯一且跨租户可用同一编码`、
   `删除前必须先解掉全部角色`。恢复 policy 后转绿。
   这一条证明租户隔离靠的是 DB 层 RLS 而不是应用层恰好带了 tenant 条件 ——
   把 policy 放宽等于抽掉唯一那道防线，测试立刻咬住。
2. **前端把 `requiresReason` 改成只对「进入 BLACKLISTED」要求理由**
   → `party-model.test.ts` 的「进出拉黑都要理由，提示文案与判定一致」转红
   （`Tests 1 failed | 620 passed`）。恢复后转绿。
   这一条钉住的是「**进出**」而不是「进入」：解除拉黑同样要留档。

两次反向验证的改动都已用备份逐字比对确认还原（`diff -q` 完全一致）。

## 后续待办

- **3.6.1.2 角色档案**：按 `role_type` 建 `party_supplier_profile` /
  `party_customer_profile` / `party_carrier_profile` 三张 1:1 表，主键 `(tenant_id, party_id)`
  复合外键指向 `party_role`。本表无需改动。`/partners` 页面已为它留好位置。
- **3.6.3.1 地址与联系人**：地址与「带姓名的联系人」归它；`party` 上的
  `email/phone/website` 是主体自己的对外通道，两者不重复，不要合并。
- **3.6.2.1 供应商报价**：引用 `role_type='SUPPLIER'` 的主体，走授权 API，
  不在本服务跨库 JOIN。
- **06 采购 / 11 售后 / 12 财务**：单据冻结当时的名称与联系方式快照（各自服务所有），
  不靠 `party` 回溯 —— 这也是角色行允许物理删除、主体不允许的原因。
- **4.1.x 平台客户映射**：平台侧客户 ID 按蓝图属于 04 渠道，不要往 `party` 上加列。
