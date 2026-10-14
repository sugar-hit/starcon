# 2026-10-07 17.4.1.1 审批流定义（阶段 D4）

> 车道 `feat/17-4-1-1`，基线 `dd8e43d`。交付蓝图 17.4.1.1「审批流定义」，
> 严格限定在**流程定义 + 节点配置 + 版本化 + 校验**，不做审批运行时。

## 决策摘要

**「审批流按单据类型注册」是一个必须落到数据库的硬约束，不只是界面上的分组。**

蓝图原文：「审批流按单据类型注册，未注册的变更类操作默认可直接执行」。
下游 17.4.2.1 拿到一张单据要回答「该走哪条流程」——如果同一租户同一单据类型同时挂着
两条生效流程，这个答案就没有唯一解。所以它落在**部分唯一索引**上：

```sql
CREATE UNIQUE INDEX approval_flow_active_document
  ON approval_flow(tenant_id, document_type_code) WHERE status='ACTIVE';
```

Java 侧先查一次给出可读的 409（带冲突流程名），索引是最后一道兜底 —— 并发下两条同时
提交也只会成功一条。配套的 `approval_flow_no_registration` 视图把蓝图的**默认放行语义**
变成可查询事实：`registered=false` 的单据类型当前真的没有管控，而不是「还没做完」。

其余关键决策：

- **阶段之间串行，阶段内可并行/条件分支**：`stage_index` 定序，`approval_mode` 描述阶段内
  协作方式（SERIAL 独占本阶段 / PARALLEL 同阶段并行 / CONDITIONAL 条件命中才参与）。
  一个阶段里两个串行节点被拒 —— 阶段之间本来就串行，阶段内再串行是自我矛盾。
- **金额阈值币种 NOT NULL**：区间是否交叠只有在**同一币种内**才有确定答案。留一个
  「不分币种」的空档会让交叠判定本身没有唯一解。区间允许留缺口（缺口走默认串行路径），
  但不允许交叠（交叠时「该走哪个节点」没有答案）。上限留空 = 覆盖该币种全部金额，
  因此与其他分支互斥 —— 那是默认路径，不该是分支。
- **不建 `effective_from` / `effective_to`**：蓝图没要求，本模块也不实现按日期自动生效/失效。
  摆着两列却没有任何代码在生效期做判定，会让读表的人以为存在一套按日期切换的机制，那是假的。
- **不建版本历史留档表**：变更前后快照由 17.5.3.1 的 `identity_audit` 承担（`version` 列每次
  保存 +1）。再建一张历史表就是同一份事实的第二个写入所有者，违反 AGENTS.md。
- **「岗位」由本模块最小可用地补齐**：蓝图点名的三种审批人里有「岗位」，而上游 17.3.1.1
  只交付了 role。按职责内最小可用建 `approval_position` 租户级字典 —— 它是**岗位**，
  不是第二套角色体系：**不携带任何权限**，权限仍只来自 `role_permission`。
- **审批人引用真实 role 表**：`approval_flow_node.role_id` 复合外键指向 `role(tenant_id,id)`，
  写入前再查一次把「角色不存在」变成可读的 400。角色被流程引用时拒删（RESTRICT → ApiErrorHandler
  统一转 409），不静默产生悬空节点。

## 踩到的坑（值得留在仓库里的那种）

**`app_user` 不能直连 —— 42501。** 节点要显示「指定人」的用户名，最自然的写法是子查询
`(select username from app_user where id=m.user_id)`。这会 403，且错误信息是
`"没有目标租户的授权"`（ApiErrorHandler 把 SQLState 42501 一律翻译成这句话，很有迷惑性）。
V1 明确写了「Service account cannot directly enumerate users」。正确做法是走
`member_names(hash)` 这个 SECURITY DEFINER 函数（以会话成员关系为条件）。

连带一个更隐蔽的坑：**`member_names` 需要 `identity.member.read` 权限**，配审批流的人未必有。
所以「审批人已不存在」的判定**绝不能拿显示名是否为空来判** —— 那是权限不足，不是人没了。
因此视图里把 `approverExists`（join 出行）与 `approverActive`（行是否 ACTIVE）拆成两个字段，
结构体检也据此分成 `APPROVER_MISSING` 与 `APPROVER_INACTIVE` 两条结论。

**JS `Set.add()` 返回 Set 而不是 boolean。** 前端阶段串行校验写成
`!serialStages.add(n.stageIndex)` —— 永远为 false，校验静默失效。被自己写的模型测试抓到的。

**契约重新生成带出了别的模块的历史漂移。** `catalog-contracts.py` 重跑后 `warehouse.openapi.json`
出现 17k 行 diff —— 那是 8.2.1.2 合并时没重新生成契约留下的**既有漂移**，不是本次改动。
用「类型集合求差」验证过：`catalog.ts` **没有丢任何类型**，只是新增 15 个 `Approval*`

- 重排。codegen 产物是只读生成物，不允许手改，因此保留工具的完整输出。

## 影响面

| 面   | 落点                                                                                                                                          |
| ---- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| 迁移 | `backend/services/identity/src/main/resources/db/migration/V83__approval_flow_definition.sql`（版本号由调度器预分配，避让在途的 V80/V81/V82） |
| 端点 | `backend/services/identity/src/main/java/com/starcon/identity/ApprovalFlowController.java`，13 个端点挂在 `/identity/approval-flows` 下       |
| 页面 | `apps/web/src/app/org/approval-flows/page.tsx` + `components/iam/approval-flow-{model,workspace}.tsx`                                         |
| 契约 | `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`（带 `--spec-dir`，避免启动 Spring Boot 抓 /v3/api-docs 被拒）  |
| 代理 | `apps/web/src/lib/api-proxy.ts` —— **纯追加**，见下                                                                                           |

### api-proxy 是「求并」而不是「二选一」

白名单是累积型文件。这次只在 **identity** 那一条 pattern 的 alternation 里追加了一个分支：

```
|approval-flows(?:\/(?:document-types|registrations|active|positions(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/validation)?))?|
```

提交前做了四项自检（脚本见会话过程，结论）：

1. 括号平衡 + `new RegExp()` 可编译：5 条 pattern 全过；
2. 从前端源码**反向抽取真实调用点**逐条断言放行：8/8 ALLOW；
3. 既有分支没被吃掉：identity 45/45 条 HEAD 上就存在的路径仍然全部放行；
4. 未实现的形状仍然 404：`/instances`、`/{id}/approve`、`/todos` 等一律拒绝。

并且程序化证明**只有 identity 一条 pattern 变化**（auth/catalog/channel/warehouse 逐字节相同），
把我插入的那段文本删掉后能**逐字节还原 HEAD** —— 纯追加，没有覆盖任何既有分支。

### 刻意不纳入本模块

- **审批运行时**：发起审批、处理审批实例、待办列表 —— 17.4.1.2 / 17.4.2.1。
  `condition_expression` 与金额分支在这里**只校验形状、不解析不执行**；
  `AUTO_APPROVE` 也只是一条被定义出来的策略，**没有任何定时器**会去读它并真的放行或转办。
- **免审策略**：蓝图里「免审要设上限金额」属于 17.4.1.2。本模块只负责金额阈值分支的
  表达式与校验，不实现免审本身。
- **finance 域**：17.6.1.1 账簿与本位币在并行车道开发，所以金额阈值里的币种是三个大写字母的
  **可读标识**，不建到 `accounting_book` / `legal_entity` 的外键（AGENTS.md 禁止跨模块外键）。

## 验证

```
cd backend && ./mvnw verify                    # 见交付报告；identity 210 测试全绿
pnpm typecheck                                 # 通过
pnpm lint                                      # 通过（仅既有 warning）
npx vitest run                                 # 84 文件 / 1528 用例通过（基线 83 / 1500）
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build   # 成功，/org/approval-flows 在产物里
node tools/module-progress/sync.mjs --check    # ✓ 台账与蓝图一致
```

基线是**自己量的**：动手前在干净树上先跑了一次全量，把 83/1500 与 identity 183 记下来。
第一轮 `./mvnw verify` 在 warehouse 报了 4 失败 1 错误（`StockOwnershipIntegrationTest` /
`AllocationPolicyIntegrationTest`），**隔离重跑 78/78 全过**，确认是四条车道并发下的 flake，
不是本次改动 —— 没有为迎合 flake 去改任何代码。

集成测试 27 个用例，除了功能面，**多租户三断言按 AGENTS.md 实跑**：两租户互不可见
（流程 / 详情 / 校验 / 岗位字典 / 生效流程全隔离，拿对方 id 读改删一律 404）、
伪造 tenant header 无效（随机头 + 伪造成子租户真实 id）、连接池归还后不串租户
（池压到 1，交替租户请求三轮，且断言「父租户查 PURCHASE_ORDER 始终拿到自己那条」）。

`BigDecimal` 与 `timestamptz` 都按**写回值**断言：`minAmount` 写 `1000.50` 读回
`1000.500000`（numeric(19,6) 不截断），`createdAt` 必须落在请求前后两个墙钟之间
（用 `LocalDateTime` 绑 timestamptz 会静默偏 −28800 秒）。

## 后续待办

- 17.4.1.2 审批规则（触发条件 + 免审上限）接上游：本模块的 `condition_expression` 与
  `approval_flow_amount_branch` 就是给它用的输入面；免审上限金额由它定义。
- 17.4.2.1 待我审批用 `/approval-flows/active?documentTypeCode=` 这个唯一出口取流程。
- 若将来真要「按日期自动生效」，需要新增 `effective_*` 两列**并同时**实现生效期判定 ——
  只加列不加判定就是本模块特意避免的那种假能力。

## 台账

只改了自己那一行（17.4.1.1 的状态与证据两格）。`sync.mjs` 另外修掉了一处**既有损坏**：
HEAD 上 `module-progress.md` 尾部被一次坏合并追加了 11 行重复的汇总行（`已交付 103`、
`D3 21 0 19 40` 之类，且文件无换行结尾），导致 `--check` 在**任何人**动手前就是红的。
sync 把它们删掉并重算派生计数。已验证 465 个模块行里**只有 17.4.1.1 一行内容变化**。
