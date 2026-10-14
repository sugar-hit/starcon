# 2026-10-07 交付 17.2.1.1 部门树（identity）

> 蓝图原文：「部门是组织维度，与法人（法律主体维度）分离——不能用一个组织节点混装两者」。
> 这一轮把 `department` 从「只有 id/tenant_id/name 的平铺表」收口成真正的层级结构：
> 部门编码、上下级、所属法人、负责人、拖拽调整、防环校验，并输出**部门树 + 成员分布**。

## 决策摘要

**层级放 `department.parent_id` 自引用，不新建 adjacency / closure 表。**

租户与法人之间已经有 `tenant_closure`（V3），部门树一开始也想照抄。但部门是**单租户内百级
节点**的小结构，一次递归 CTE 就能取回整棵树，省掉 closure 表的写入维护与一致性风险；
`tenant` 是需要跨层级授权判定的对象，量级与生命周期都不同，不该用同一套机制。
复合外键 `(tenant_id, parent_id) REFERENCES department(tenant_id, id)` 顺带保证跨租户挂靠
在 DB 层就不可写入，RLS 再兜一层可见性。

**防环分两层：DB 只挡最浅的自环，深层交给应用层递归判定。**

`CHECK (parent_id IS DISTINCT FROM id)` 挡住「自己是自己的父」。更深的环（把父挂到自己的
子孙下）需要沿祖先链判断，写成 CHECK 或触发器既不��读也不可维护，所以在
`PUT /identity/departments/{id}/parent` 里用一条递归查询做：

```sql
WITH RECURSIVE ancestors AS (
  SELECT id, parent_id FROM department WHERE id = :newParent
  UNION ALL
  SELECT d.id, d.parent_id FROM department d JOIN ancestors a ON d.id = a.parent_id
)
SELECT EXISTS (SELECT 1 FROM ancestors WHERE id = :moved) AS cycle
```

新父节点的祖先链里出现自己即成环。**实现时踩过一次反向坑**：最初写的是「新父节点的
*子树*里是否含自己」，结果把「把子节点移到祖孙节点之下」这种合法移动也判成了环——
成环的条件是新父节点在自己子树内，等价于**自己在新父节点的祖先链上**，方向反了。
`call()` 已经持有 `pg_advisory_xact_lock`，同租户的组织变更串行执行，不存在 TOCTOU。

**删除语义：有子部门或仍有成员挂靠就拒绝，并回报具体数量。**

不做静默级联。组织的层级被级联删掉之后，成员的部门归属与 17.3.2.2 的数据范围都失去依据，
而这类损失事后无法从审计里还原。返回 409 并写清「还有 N 个子部门」/「还挂靠 M 名成员」，
管理员据此先调层级或改成员归属。子部门计数与成员计数由应用层给出可读原因，
`parent_id` 外键保持 NO ACTION 作为 DB 兜底，不静默级联。

**引用类外键用 `ON DELETE SET NULL (col)`，而不是一律 NO ACTION。**

`legal_entity_id` 与 `leader_membership_id` 是**软归属**：法人是货权与记账主体、部门是组织
单元，两者互不拥有。硬删一个不该被部门挡住；法人没了就把部门这一列置空。用 PG 15+ 的列清单
形式只清被引用的列，`tenant_id` 不能动（NOT NULL）。而 `parent_id` 相反——它是层级结构
的一部分，删父节点必须先处理子节点，所以是 NO ACTION。
`leader_membership_id` 可空是蓝图 17.2.1.2 的明确要求：没有负责人的部门要在审批流里指定兜底人。

**树查询写成 `SECURITY DEFINER` 函数，不在控制器内联 SQL。**

`app_user` 没有直接授予 `identity_app`（V1 `REVOKE ALL ON ALL TABLES`，用户名只能经
`SECURITY DEFINER` 函数取）。控制器里直接 `JOIN app_user` 解析负责人用户名会以 SQLState
42501 被拒，被 `ApiErrorHandler` 映射成 403「没有目标租户的授权」——报错与真实原因完全对不上，
排查绕了一圈。故 `department_tree(hash)` 与 V3 的 `member_names` / `child_tenants` 同一套约定：
`SECURITY DEFINER` + `SET search_path` + `REVOKE FROM PUBLIC` + `GRANT TO identity_app`，
并且**显式 join 到 `session_access(hash)` 的 tenant_id**（与 `member_names` 同款），
不依赖「函数属主一定会被 RLS 拦」这一隐含假设。
整棵树（深度、法人名、负责人用户名、成员数）在一条 SQL 里取回，Java 侧只做父子装配，
没有 N+1。成员数只统计**直接挂靠**本部门的人，父部门不重复累计子部门的人数。

**`code` 租户内唯一，但迁移不能炸在存量行上。**

V3 建表时没有编码列。`ADD COLUMN ... DEFAULT ''` 之后若直接建 `UNIQUE(tenant_id, code)`，
多行 `code=''` 会让迁移失败。顺序是：加列（带默认）→ `UPDATE ... SET code='DEPT-'||replace(id::text,'-','')`
回填（32 位十六进制，确定且不撞车）→ 加唯一索引 → `DROP DEFAULT`。
`UNIQUE(tenant_id, name)` 沿用 V3 不动。V6 在有存量数据的库上已实测干净应用。

**层级调整是独立端点，编辑端点不动 `parent_id`。**

`PUT /departments/{id}` 只改 code/name/legalEntityId/leaderMembershipId；
移动只走 `PUT /departments/{id}/parent`，审计动作因此也分开（`DepartmentUpdated` vs
`DepartmentMoved`）。好处是防环校验只存在于一处，不会出现「两条路径一条漏校验」。
代价是前端编辑弹窗里的「上级部门」选择器需要额外发一次移动请求——这一点在面板里显式处理，
否则那个下拉框就是个摆设。

**负责人只是字段引用，管理范围与审批链不归本模块。**

17.2.1.2（指定负责人 / 管理范围 / 审批权限 / 代管与临时授权）单独立项。本轮只提供
`leader_membership_id` 这个指向 membership 的字段，**不要求负责人属于本部门**——
「谁能审批、管多大范围」是 17.2.1.2 与 17.4 的规则，这里不擅自发明。

## 文件清单

| 类型 | 文件                                                                                                        |
| ---- | ----------------------------------------------------------------------------------------------------------- |
| 迁移 | `backend/services/identity/src/main/resources/db/migration/V6__department_tree.sql`                         |
| 后端 | `backend/services/identity/src/main/java/com/starcon/identity/AdministrationController.java`                |
| 测试 | `backend/services/identity/src/test/java/com/starcon/identity/IdentityIntegrationTest.java`                 |
| 前端 | `apps/web/src/components/iam/department-tree.tsx`（新增，树 + HTML5 拖拽）                                  |
| 前端 | `apps/web/src/components/iam/administration-panel.tsx`（平铺表格 → 树，表单扩字段）                         |
| 前端 | `apps/web/src/components/iam/administration-model.ts`（拍平 / 防环判定 / 移动端点）                         |
| 前端 | `apps/web/src/components/iam/administration-model.test.ts`                                                  |
| 契约 | `packages/api-contracts/src/catalog.ts`、`packages/api-contracts/src/identity.openapi.json`（codegen 产物） |
| 台账 | `docs/product/capability-blueprint/module-progress.md`（17.2.1.1 → 已交付）                                 |

## 端点

| 方法   | 路径                                | 权限码                       | 说明                       |
| ------ | ----------------------------------- | ---------------------------- | -------------------------- |
| GET    | `/identity/departments`             | `identity.department.read`   | 树形结构 + 成员分布        |
| POST   | `/identity/departments`             | `identity.department.create` | parentId 可空（根节点）    |
| PUT    | `/identity/departments/{id}`        | `identity.department.update` | 只改字段，不动层级         |
| PUT    | `/identity/departments/{id}/parent` | `identity.department.update` | 拖拽移动，防环             |
| DELETE | `/identity/departments/{id}`        | `identity.department.delete` | 有子部门/成员则 409 + 数量 |

审计沿用既有 `identity_audit` + `identity_outbox`，动作分别为
`DepartmentCreated` / `DepartmentUpdated` / `DepartmentMoved` / `DepartmentDeleted`。

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS；identity 18、catalog 22、channel 43，零失败
- `pnpm typecheck` / `pnpm lint` → 通过
- `pnpm test` → 13 个文件 78 个用例通过（新增 3 个部门树用例）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 构建成功
- `node tools/module-progress/sync.mjs --check` → 台账与蓝图一致
- V1–V6 迁移在临时 `postgres:16-alpine` 上按序实跑，并在**有存量行的库**上验证过回填

## 后续待办

- **17.2.1.2 部门负责人**：管理范围、审批权限、代管与临时授权。本轮只落了字段引用。
- **成员按部门筛选**：节点上目前只展示人数计数。`identity/members` 不支持按部门过滤，
  展开列具体成员需要先补后端查询，不做假数据。
- **审计查询端点**属 17.5.3.1，本轮只写审计不查审计。
- 基线分支说明：本轮从 `feat/3-1-1-3-spu-lifecycle` 切出，而非直接切 `codex/workspace-account`。
  原因是 `tools/module-progress/`（lock / sync）与含 17.2.1.1 台账行的 `module-progress.md`
  只存在于前者；后者是前者的祖先（`git merge-base --is-ancestor` 已验证），
  切更早的基线会丢台账与流水线工具，硬性要求里的台账更新与 `sync.mjs --check` 都无法执行。
