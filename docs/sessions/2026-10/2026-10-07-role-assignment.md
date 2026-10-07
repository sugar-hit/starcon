# 17.3.2.1 角色分配（阶段 D1–D2）

日期：2026-10-07 · 车道：feat/17-3-2-1 · 集成头：f02d5e8

## 决策摘要

蓝图对本模块的硬约束只有一句，但很重：**分配时必须同时设定范围**——没有范围的角色等于全量权限，
实践中极易误配。这条落在 DDL 层，而不是应用层 `if`。

- `role_assignment` 的 `scope_mode` **删掉了 V1 留下的 `DEFAULT 'ALL'`**。默认值本身就是「忘了填范围」的
  来源：任何省略范围列的 INSERT 都静默拿到租户内全量权限。删掉之后漏填直接失败。
- 新增 `role_assignment_scope`，承载蓝图点名的四类对象：法人、店铺、仓库、部门。
- 新增**延迟约束触发器**（`DEFERRABLE INITIALLY DEFERRED`）保证非 ALL/SELF 的分配至少有一条范围行；
  同时挡住反向矛盾：ALL / SELF 不允许再挂对象行。

## 关键 trade-off

**1. 为什么用延迟约束触发器，而不是 CHECK**
约束要跨两张表（分配行 + 它的范围行），`CHECK` 只能看同一行。延迟到提交时才判定，是因为写入路径是
「先删旧范围、再写新范围」的多步操作；语句末尾立刻判定会把中间态误判成违规。

**2. 范围模式沿用 V3 既有词表，不新造枚举**
`ALL / SELF / DEPARTMENT` 是 catalog 17.3.2.2 的 RLS 引擎在消费的值。新造枚举会让 catalog 侧
认不出新模式，直接导致下游要么全拒、要么按旧模式放行。对象范围作为**正交的一维**挂在分配上，
由本模块负责携带与预览，行级过滤的落地仍是 17.3.2.2 与各业务服务的职责（任务书明确划界）。

**3. 法人与部门库内校验，店铺与仓库不校验**
前两者本服务持有，必须校验存在且属于本租户，顺带把 `scope_code` **覆写**成真实编码——范围名不该由
调用方自称。后两者的表在 channel / warehouse 服务，AGENTS.md 禁止跨服务 FK（V5 法人档案同一处置），
因此是不透明引用，名称由调用方提供。页面取不到这两类候选时只显示本服务能给的两类，
**不拿假数据把四类凑齐**。

**4. `DEPARTMENT` 分配自动落一条范围行**
17.2.2.1 的成员管理入口只收 `scopeMode`，由 `AdministrationController.assignments()` 把
「成员自己的部门」显式写成一条 `DEPARTMENT` 范围行。成员换部门时该入口整体重写这些行，
所以不会出现「范围还指着旧部门」的陈旧数据。这样 17.2.2.1 的接口形状与行为完全不变。

## 实现中真实踩到的坑

- **jOOQ 对裸 `?` 按 varchar 推断类型**：`update … set revoked_at=?` 传 `OffsetDateTime` 被 PG 直接拒绝
  （`column "revoked_at" is of type timestamp with time zone but expression is of type character varying`）。
  所有 timestamptz 绑定一律显式 `?::timestamptz`，且**不放在 VALUES 列表末位**（末位会让整体绑定错位一格）。
  测试用 `+08:00` 构造时间并断言**瞬间**（`toInstant()` 相等），偏 8 小时的错误写法会被直接抓住。
- **位置式 INSERT 在加列后失效**：V3 的 `create_child` 用 `INSERT INTO role_assignment VALUES(...)`，
  V47 加列后直接报 `INSERT has more target columns than expressions`，连带所有新建子租户失败。
  已在同一个版本里 `CREATE OR REPLACE` 成显式列名——迁移必须一次成功。
- **审计快照不能漏范围**：只记「改过分配」等于没法追责，范围对象与生效时间一并入快照。

## 文件清单

新增：
- `backend/services/identity/src/main/resources/db/migration/V47__member_role_assignment.sql`
- `backend/services/identity/src/main/java/com/starcon/identity/RoleAssignmentController.java`
- `backend/services/identity/src/test/java/com/starcon/identity/RoleAssignmentIntegrationTest.java`（15 条）
- `apps/web/src/components/iam/role-assignment-workspace.tsx`
- `apps/web/src/components/iam/role-assignment-model.ts` + `.test.ts`（21 条）
- `apps/web/src/app/org/role-assignments/page.tsx`

修改：
- `AdministrationController.assignments()`：显式列名 + timestamptz cast + DEPARTMENT 范围行落库
- `AdministrationController.transfer()`：owner 转移的 upsert 改显式列名并复位撤销列
- `LocalBootstrap`：本地租户初始 OWNER 分配改显式列名
- `identity-workspace.tsx`：新增「角色分配」入口与权限过滤
- `packages/api-contracts/src/*`（codegen 产物，只新增 `RoleAssignment*` 8 个类型，零移除）

## 验证

- `cd backend && ./mvnw verify` — 全绿
- `pnpm typecheck && pnpm lint && pnpm test` — 51 文件 / 890 测试通过
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`
- `node tools/module-progress/sync.mjs --check` — 通过

多租户三断言（实跑 Testcontainers PostgreSQL 16，连接池压到 1）：两租户互不可见、
伪造 `X-Tenant-Id` 无效、连接池归还后不串租户。分配表与范围表都逐条断言。

## 后续待办

- **范围对象到行级过滤的落地**属 17.3.2.2 与各业务服务：catalog 的 `catalog_scope()` 目前只认
  `ALL / SELF / DEPARTMENT`，店铺/仓库级收窄要等那一侧扩展。当前行为是**失败关闭**
  （认不出的模式一律拒绝），不会误放行。
- 17.3.3.1 越权检测可以直接用 `role_assignment_scope(tenant_id, scope_type, scope_id)` 索引做反向查询，
  这个索引已建好。
