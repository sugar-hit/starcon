# 17.4.2.2 审批记录（阶段 D4，L3 锚点 17.4.2）

- 日期：2026-10-18
- 车道：`feat/17-4-2-2`（worktree `.worktrees/17.4.2.2`）
- 基线：集成头 `integration/module-dev` @ `f760ba37`

## 决策摘要

**1. 不建新表。** 本模块是审批事实的读取 / 统计 / 导出层。蓝图点名的每一项都能从上游 17.4.2.1
（`V160__my_approval_inbox.sql`）的 `approval_instance` + `approval_instance_action` **只读推导**：

| 蓝图要求 | 来源 |
| --- | --- |
| 发起 | `approval_instance.submitted_at` / `applicant_membership_id` / `source_*` |
| 流转节点 | `approval_instance_action.node_key`（每次动作**快照当时节点**）按 `decided_at` 串 |
| 各节点意见与时间 | `approval_instance_action.comment` / `decided_at` |
| 转交 | `action='TRANSFER'` + `to_membership_id` |
| 驳回 | `action='REJECT'`（库上 CHECK 已强制必带理由） |
| 最终结果 | `approval_instance.status` / `finished_at` |
| 重新发起关联原实例 | **`original_instance_id`（上游已留列）** |

再造一张「审批记录表」等于把同一份事实复制一份、必然与上游漂移——正是 AGENTS.md
「每张事实表只有一个写入所有者」要避免的。统计同理走**实时 SQL 聚合**而非快照表。

**2. V163 用掉了，但只含索引 + 页面权限。** 上游只建了
`approval_instance_action_timeline(tenant_id,instance_id,decided_at,id)`（只服务取单条时间线），
本模块的按人 / 按节点 / 按时间范围 / 按申请人 / 重发链 / 跨服务来源定位六类查询**一条索引都没有**。
没有这些索引，「全链路记录查询」就是把整表拉回来在内存里筛——那是假装有索引。

**3. 按节点统计走动作快照，不走 `current_node_key`。** 问「哪个节点是瓶颈」时答案必须包含
已经被流转走掉的节点；只看当前节点的话，一条三步流程的三步会被全算到同一个节点头上。
这条同时被测试钉住（三步流程时间线必须给出 N1/N2/N3 三个**不同**的节点）。

**4. read 与 export 拆成两个权限码。** 「能查」与「能把全量审批链路导成 CSV 落到自己机器上」
是两种不同的风险。刻意不复用 `approval_inbox.*` / `approval_flow.*`：审计岗要看得见所有人批了什么，
但绝不该因此拿到审批权或配流程的权。

**5. 撤回如实报未交付。** V160 明确 `status` 的 CHECK 刻意不含 `WITHDRAWN`（撤回是申请人的动作，
不在待我审批的职责内）。本模块**没有**预先往 CHECK 里塞一个没有任何代码路径能到达的状态，
也**没有**在时间线上伪造撤回动作；`/approval-records/capabilities` 如实报 `delivered=false`
并指明归属「提交侧」。页面上也没有撤回按钮。

## 关键 trade-off

- **列表按实例聚合 vs 按动作分行。** 一条被 3 个人审过的单子按动作分行会让同一个实例号出现 3 次，
  而「这条单现在什么结果」只有按实例看才成立。所以列表按实例，按动作的视角走独立的
  `by-actor/{actorId}` 与单条详情的时间线。
- **按节点 / 按动作用 `exists` 子查询而不是 join。** join 会把一条多动作的实例复制成多行，
  按实例聚合就废了、`total` 也会数重。这是本模块最容易写错的一处。
- **CSV 逐 (实例, 动作) 展开而不是只导实例摘要。** 只有实例摘要的导出会丢掉全部审批意见，
  而意见正是审计要的东西。代价是同一个实例号在 CSV 里出现多行——对审计导出这是正确的形状。
- **测试夹具踩过的坑（都记下来，免得下条车道再踩）**：
  - 「时间窗放到过去一条都不命中」是**反的**——刚提交的单子当然落在过去一天里。
    要命中零行必须把窗口**起点**放到未来。
  - 审批动作必须由**节点审批人**执行；租户所有者虽然有 `approval_inbox.*` 权限码，
    但不在那个 ROLE 节点上，用它去批会正确地 404。
  - `GET /identity/members` 返回**裸数组**不是分页信封。
  - 两张审批表都是 FORCE RLS，测试里任何直查都必须**在租户事务内**，否则读到零行。
  - `select distinct x ... order by max(y)` 在 PostgreSQL 里非法（ORDER BY 表达式必须出现在 select 列表），
    正确写法是 `group by instance_id order by max(decided_at)`。

## 文件清单

**迁移**
- `backend/services/identity/src/main/resources/db/migration/V163__approval_record_queries.sql`（无新表；9 条索引 + 页面授权）

**后端**
- `backend/services/identity/src/main/java/com/starcon/identity/ApprovalRecordController.java`（6 个端点，一个写端点都没有）
- `backend/services/identity/src/test/java/com/starcon/identity/ApprovalRecordIntegrationTest.java`（9 例）
- `backend/services/identity/src/test/java/com/starcon/identity/ApprovalRecordTenancyIntegrationTest.java`（4 例，多租户三断言）

**契约（codegen 产物，只由本模块触发生成）**
- `packages/api-contracts/src/contracts/identity.ts`
- `packages/api-contracts/src/identity.openapi.json`

> 注：`catalog-contracts.py` 会顺带重生成全部 6 个服务的契约，其中 `trade.*` 的漂移来自集成头上
> trade 车道已合入但契约未再生的部分，**不属于本模块**，已 `git checkout` 还原，未带入本车道提交。

**前端**
- `apps/web/src/components/iam/approval-record-model.ts` + `.test.ts`（纯函数，30 例）
- `apps/web/src/components/iam/approval-record-workspace.tsx`
- `apps/web/src/app/org/approval-records/page.tsx`
- `apps/web/src/components/iam/identity-workspace.tsx`（导航项 + `visible()` + 渲染分支，仅追加）
- `apps/web/src/lib/api-proxy.ts`（白名单**纯增量**新增 `approval-records` 一个分支）
- `apps/web/src/app/api/iam/approval-record-allowlist.test.ts`（放行 + 反向 + 不误伤）

**台账**
- `docs/product/capability-blueprint/module-progress.md` 第 525 行（只改本模块那一行的「状态」「证据」）

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` → 退出 0
- L1 模块闸：`-Dtest='ApprovalRecord*'` → **13 例全绿**（9 + 4），`BUILD SUCCESS`
- 上游定向回归：`-Dtest='ApprovalInbox*'` → **21 例全绿**（16 + 5），`BUILD SUCCESS`
- 前端：`tsc --noEmit -p apps/web/tsconfig.json` → 退出 0（**直取 tsc，避开 turbo 缓存**）
- 前端测试：模型 30 例 + iam allowlist 15 例 → 全绿
- `pnpm lint` → 通过（仅存量文件的历史 warning，本模块无）

## 后续待办

1. **撤回**：由提交侧车道落地 `WITHDRAWN` 状态与撤回动作。落地后本模块的链路查询
   **无需改动**即会自动呈现——状态与动作是从表里读的，不在代码里写死。
2. **`CONDITIONAL` 条件节点**：与上游一致，条件表达式的路由仍不执行（上游返回 409）。
   条件节点一旦被路由，其动作会按本模块既有的 `node_key` 快照规则进入时间线，无额外改造。
3. **提交侧来源对接**：`/approval-records/capabilities` 的 `observed` 目前如实反映库里的真实计数，
   各业务系统接上提交入口后会自然出现。
4. 若将来审批记录量大到需要分页游标（现在是最多 200 条/页的 offset 分页），
   应在 `approval_instance_by_submitted_at` 之上换成 keyset 分页。
