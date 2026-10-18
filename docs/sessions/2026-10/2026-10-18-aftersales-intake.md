# 11.1.1.3 售后受理（车道 11.1.1.3，基线 f760ba37）

> 蓝图 `docs/product/capability-blueprint/11-aftersales-and-returns.md` 第 86-90 行。
> 分支 `feat/11-1-1-3`，worktree `.worktrees/11.1.1.3`，迁移版本号 V164（预分配）。

## 决策摘要

1. **四项判定各读一张真实存在的表，没有一处硬编码常量。**
   售后期锚点读 `sales_order`（V99，同库只读）；期限天数与必退词表读 17.5.1.1 业务参数（identity，跨服务只读）；
   责任人从租户可维护的名册按加权最少在办单轮转；回复时限 = 平台给的时限 > 租户 SLA。

2. **17.5.1.1「业务参数」实际已交付 —— 任务书里的「未交付」结论已过时。**
   实测集成头：`backend/services/identity/.../V48__business_parameter.sql` 有该模块四张表 +
   `BusinessParameterController` 的 7 个端点，台账 `module-progress.md:526` 已标「已交付」。
   8 条出厂默认值里 AFTERSALE 类恰好两条，正是本模块所需：
   `AFTERSALE_RETURN_WINDOW_DAYS`（INTEGER，出厂 30 天）与
   `AFTERSALE_MUST_REFUND_REASONS`（MULTI_ENUM，出厂 `DAMAGED_ON_ARRIVAL,MISSING_ITEM,WRONG_ITEM`，
   合法取值 6 个）。**⇒ 「必退情形自动判定」有真实可配置数据来源，不需要降级方案。**

3. **政策不落影子副本。** 它在 identity 独立库，AGENTS.md 禁止跨服务 SQL ⇒ 经
   `/identity/business-parameters/effective` 授权只读端点读，透传调用方自己的 Authorization。
   两条参数**一次请求同时取回**：分两次读会出现「旧期限 + 新词表」这种现实中不存在的组合。

4. **`aftersales_case` 的写入所有者仍是 11.1.1.1，本模块只委托不自写。**
   11.1.1.1 的 `create()` 注释已写明「11.1.1.3 交付后应当接管本方法」，本车道据此接管**受理判定**，
   建单仍走 `AftersalesCaseService.create()`（不自写 INSERT，避免第二写入者）。

5. **加粗句「人工不能为了业绩拖着不退导致平台判责」钉了三层**（不是服务层自觉）：
   - DB 触发器：被顶替的判定若为 `PLATFORM_DECLARED` 必退，新判定**不许降级**（绕过服务层写 SQL 也被拒）；
   - 权限：判定凭据**列级**只授 `UPDATE(superseded_at)`，判定内容不可改写；
   - 入参：**没有**「我要不要必退」这个开关，且平台强制必退只接受平台消息声明（内部录入勾选 400）。

## 关键 trade-off

| 决策 | 取舍 |
|---|---|
| 回复时限落在本服务 `aftersales_reply_sla`，**不**假装是业务参数 | 17.5.1.1 里确实没有这条参数（实测 8 条默认值确认）。宁可少一个「统一配置入口」，也不让运营去业务参数页改一个不存在的东西。代价：多一张租户可维护的表。 |
| 两档时限都取不到时**受理失败**，不给「默认 48 小时」兜底 | 兜底会让 `capabilities.replyDeadlineNote` 变成假话（界面说「按租户策略推算」，实际是代码常量）。代价：首次使用必须先配一档 SLA，页面上把这件事显式做成可见配置来补偿。 |
| 必退情形映射表**显式写成 `Map`**，只映射 3/7 类 | `SIZE_MISMATCH`/`BUYER_REASON`/`MISTAKE_BUY`/`FRAUD_RETURN` 不映射。少一条映射 = 那一类永远拿不到必退判定，而这种遗漏在报表上看不出来。`FRAUD_RETURN` 不映射是刻意的：欺诈恰恰是**不能**自动同意退款的一类。 |
| 重新判定**沿用首判的回复时限** | 重新判定的是「符不符合条件」，不是重开一次计时。换新时限等于给一张已被平台强制必退、且已超时的单续命 —— 正是蓝图要防的事。 |
| 判定凭据冻结**整份**生效词表而非只记命中项 | 只存命中项就答不出「三个月前还有哪些情形算必退」，而那正是改参数后要复查的东西。 |
| 余量小时数：正数=余量、负数=欠账 | 一个正数永远表示「还有余量」，读的人不必先判断符号方向才知道是好消息还是坏消息。 |

## 过程中修掉的三个真 bug（都写成了注释钉住）

1. `handling_plan_source` / `policy_source_scope` 声明为 `varchar(16)`，装不下
   `MUST_REFUND_FORCED`(18) 与 `DEFINITION_DEFAULT`(18) ⇒ 必退单受理必 409。已放宽到 `varchar(32)`。
2. 判定凭据整表 `REVOKE UPDATE` 与「重新判定要标记 superseded」**直接冲突** ⇒ rejudge 走不通。
   改为照 V156 对 `aftersales_case` 的同一套写法：`REVOKE` 表级 UPDATE 后**只授回 `superseded_at` 一列**。
3. `supersedes_id` / `superseded_at` 的表级 CHECK 两个方向都写错过，各砍掉一种合法状态。
   最终**去掉该 CHECK**：四种组合都合法，真正的不变式各有归属（部分唯一索引保证单一生效判定、
   列级 GRANT 保证内容不可改、supersede_once 触发器保证顶替时刻单向）。

## 文件清单

新增（后端）
- `backend/services/trade/src/main/resources/db/migration/V164__trade_aftersales_intake.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesIntakeModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesIntakeService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesIntakeController.java`
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesPolicyQueryPort.java`
- `backend/services/trade/src/test/java/com/starcon/trade/AftersalesIntakeIntegrationTest.java`（28 例）
- `backend/services/trade/src/test/java/com/starcon/trade/AftersalesIntakeTenancyIntegrationTest.java`（4 例）

新增（前端）
- `apps/web/src/app/aftersales/intake/page.tsx`
- `apps/web/src/components/aftersales/aftersales-intake-workspace.tsx`
- `apps/web/src/components/aftersales/aftersales-intake-model.ts`
- `apps/web/src/components/aftersales/aftersales-intake-model.test.ts`（19 例）
- `apps/web/src/app/api/trade/aftersales-intake-allowlist.test.ts`（4 例）

改动
- `apps/web/src/lib/api-proxy.ts`：新增一条 `services[]` 元素（11.1.1.1 之后、11.1.2.1 之前），零改既有正则
- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`：**仅本模块那一行**的状态与证据两格

## 验证

- L0 编译闸：`./mvnw -q -pl services/trade -am compile` 通过；`pnpm typecheck` 通过（3/3，web 非缓存）。
- L1 模块闸：`-Dtest='AftersalesIntake*'` → **32 例全过**（28 + 4）。
- 兄弟定向回归：`-Dtest='AftersalesCase*'` → **53 例全过**（11.1.1.1 的 29+5、11.1.1.2 的 15+4），
  确认没有弄坏共用售后单事实表的既有模块。
- 前端：`aftersales-intake-model.test.ts` 19 例、`aftersales-intake-allowlist.test.ts` 4 例；
  因改了共享 api-proxy，跑了全量 allowlist 回归 `pnpm vitest run apps/web/src/app/api/trade`
  → **33 文件 182 例全过**。`pnpm lint` 通过。

## 后续待办

- **11.2 / 11.3 未交付**：本模块输出的「必退」与「处理方案」目前只推进到 `aftersales_case` 的
  `RECEIVED`（待处理）状态，没有真正建退货授权单或退款申请。11.2.1.1 / 11.3.1.1 交付后，
  受理的判定结论应由它们消费（当前 capabilities 里 downstream 仍如实报未交付）。
- **卷 2 平台消息未交付**：`intake_source=PLATFORM_MESSAGE` 目前只能由人工录入触发。
  同步器到位后按 V157 的 `message_source` 同一形状写入即可，表结构不用改。
- **卷 15 客服未交付**：责任人名册是租户可维护登记（`owner_source` 二选一）。15 客服交付后
  由同步器改写 `MODULE_15_SERVICE_STAFF`，表结构与查询都不用改。
- **17.5.1.1 若将来新增售后回复时限参数**：应把 `aftersales_reply_sla` 的读取切到业务参数端口，
  本表的租户可维护形态可保留为「未配置时的兜底」或整体退役 —— 届时需复核 `reply_due_basis`
  的两档优先级文案。
- 端点级错误响应文案受共享 `ApiErrorHandler` 影响（`IllegalArgumentException` 的 detail 被压成通用语），
  本模块未改该共享文件；如需向用户透出具体拒绝原因，应由该 handler 统一决策，属另一件事。
