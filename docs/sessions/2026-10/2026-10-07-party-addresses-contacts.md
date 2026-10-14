# 3.6.3.1 地址与联系人（阶段 D1–D2）

日期：2026-10-07 ｜ 车道：3.6.3.1（分支 `feat/3-6-3-1`）｜ 迁移号：V31（预分配）

## 决策摘要

本模块兑现蓝图 3.6.3.1 的全部职责：往来单位的收发货地址、带姓名的联系人、地址变更历史版本，
外加蓝图点名要的两个默认能力（默认收/发货地址、默认联系人）。

**落成四张表**：`party_address`（地址内容，当前态）、`party_address_usage`（用途 + 默认标记）、
`party_address_version`（append-only 版本链）、`party_contact`（联系人 + 默认标记）。

三条关键取舍：

1. **用途与默认不并进 `address_type` 枚举，拆成 `party_address_usage` 行。**
   同一个供应商的仓库往往**既是发货地址又是提货地址**；枚举写法下同一物理地点要录两行、
   改一次改两处，而更要命的是「默认提货地址」在枚举列上没有落点 —— 默认是**按用途**的
   （每人每用途一个），只有拆行才能用部分唯一索引在 DB 层表达。
   这也让「同一条地址可以既是发货仓又是提货点」成为数据上的一行两列。

2. **冻结快照 = 可变当前态 + append-only 版本链 + 单据侧自存快照（三层）。**
   蓝图那句「单据上的地址是冻结快照，本模块改地址不影响已发生单据」在本模块这一侧写成：
   ① 地址行可变（默认标记与用途约束都在唯一可查的当前态行上）；
   ② 每次**内容**真的变更追加一条 `party_address_version`（只追加，不改不删）；
   ③ 06/10 单据在自己表里存快照列（权威）+ `address_id`/`version_no` 两个**不加外键**的指路牌列。
   读「当时内容」的优先级不能反：单据快照列 → 缺失时才回查版本链（只读兜底）。
   **不选「地址行不可变」**：那样每次改地址都在单据里换一个 id，且当前态退化成
   「没有后继的那一行」，「挑当前地址」要写成挑没有后继的行，删一行就把末环判错。

3. **地址软删除、联系人允许物理删除。**
   两者看似不一致但理由不同：下游单据引用的是 `(address_id, version_no)` 指路牌，
   删掉地址会让指路牌指向不存在的行；而联系人只是「当前正在对接的那个人」的**当前事实**，
   没有回溯需求（已发生单据里的姓名与电话是单据自己的冻结快照）——
   这与 V27 对 `party_role` 的同一取舍同源。

## 关键 trade-off / 踩过的坑

- **测试抓出两个真 bug（都已修）**
  - `candidates(includeBlocked=true)` 原本返回了已软删除的地址。`includeBlocked` 只应放宽
    「主体状态」，**不放松「已删除」** —— 新单据永远不该选中一个已删的地址
    （与 3.6.1.1 的 candidates 同一口径）。
  - `PartyRules.normalizePhone(null)` 返回的是**空串**而不是 null，直接落库会撞
    V31 的 `CHECK(phone IS NULL OR btrim(phone)<>'')`。归一化链上少一步 `emptyToNull`。
- **规则单测还抓出两个纯规则 bug**：`oneLine()` 在地址其余列全空时会**丢掉国家代码**
  （面单与报关都要它）；`reachProblem()` 把「填了但只有空格」报成「邮箱格式不正确」，
  让运营以为自己格式写错了 —— 现在「空格」先于「至少给一项」判定。
- **契约注解**：`selectionBlockCode` 可选时为 null，Java record 上必须标
  `@Schema(nullable = true)`，否则 codegen 生成的是 `string` 而实际可能是 null，
  typecheck 会逼所有人写断言绕过。
- **OpenAPI 路径变量**：控制器最初用 `{partyId}`，Spring 会生成一条与 `PartyController`
  的 `/catalog/parties/{id}` **平行**的路径条目 —— 同一 URL 出现两份 operation。
  已改成 `{id}`（与其余嵌套路由一致），契约变成**纯新增零删除**。
- **工作区基线陈旧**：本车道 worktree 实际建在 `020f5b6`，比 `integration/module-dev`
  落后 22 个提交，**不含 V27**（`party` 表）。`lane.mjs` 记录的 `baseSha` 是 `36a871e`，
  说明调度侧本意就是集成头，是 worktree provisioned 错了。
  已 `git merge --ff-only integration/module-dev` 对齐（HEAD 当时 0 ahead，
  快进不可能冲突也不会丢工作）。**这属于调度侧缺陷，值得复查 worktree 派发逻辑。**

## 文件清单

迁移

- `backend/services/catalog/src/main/resources/db/migration/V31__party_addresses_contacts.sql`

后端（catalog 服务）

- `backend/services/catalog/src/main/java/com/starcon/catalog/PartyAddressRules.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PartyContactRules.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PartyAddressService.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PartyContactService.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PartyAddressController.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PartyContactController.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（**追加** 3.6.3.1 的
  10 个 record，未改动其它模块的 record）
- 测试：`PartyAddressContactIntegrationTest.java`（23 例）、`PartyAddressContactRulesTest.java`（18 例）

前端

- `apps/web/src/components/catalog/party-contact-model.ts`（新增，含与后端逐字同口径的校验）
- `apps/web/src/components/catalog/party-contact-model.test.ts`（新增，14 例）
- `apps/web/src/components/catalog/party-contacts-workspace.tsx`（新增，真实工作区）
- `apps/web/src/app/partners/contacts/page.tsx`（新增）
- `apps/web/src/lib/api-proxy.ts`（**仅**扩展 catalog 的 `parties` 段，未改其它前缀）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（+1 例全路由放行）

共用产物（多车道并入时按类型名/路径/模块 ID 求并）

- `packages/api-contracts/src/catalog.ts` + `catalog.openapi.json`（codegen 生成：+13 路径 / +12 DTO）
- `docs/product/capability-blueprint/module-progress.md`（**仅** 3.6.3.1 一行 + sync 自动算出的 03 卷已交付计数）

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS
- `pnpm typecheck` → exit 0
- `pnpm lint` → exit 0
- `pnpm test` → 40 文件 / 667 例全过
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 成功，`/partners/contacts` 已编译
- `node tools/module-progress/sync.mjs --check` → 台账与蓝图一致

## 后续待办

- **06 采购 / 10 物流**：单据表里建**地址快照列**（权威）+ `address_id`/`version_no`
  两个指路牌列（**不建跨服务外键**），默认地址走
  `GET /catalog/parties/{id}/addresses/defaults?usage=...`，下拉走 `.../addresses/candidates`。
  写法见 V31 第四节。
- **主体被软删除后其下地址仍在**：本模块**刻意没有**去改 3.6.1.1 的删除规则（那是别的模块的
  文件）。后果是这些地址在界面上带 `PARTY_DELETED`、不可被新单据选中，而详情仍可读 ——
  与 3.6.1.1 对角色行的处理同源。若后续认为该联动，应在 3.6.1.1 侧提。
- `3.9.2.2` 僵尸主数据清理可把「未标用途的地址」「三项联系方式全空的联系人」列为候选来源
  （本模块已在建档侧拦住 A1/C1，但直连 SQL 造出来的行仍需那一层兜底）。
