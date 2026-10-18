# 批次 20 — 售后受理 / 退货授权 / 换货授权 / 审批记录

> 组批模式：4 条车道并行开发，L0+L1 由 worker 在各自 worktree 跑，
> **L2 全量验证由调度器在集成头上只跑一次**（`mvnw verify` + typecheck/lint/test + Next build + sync --check）。
>
> 集成头：`f760ba37` → `06beab27`，4 条车道顺序并入，**零冲突**。

本批一个值得记的编排事实：`.dev-agent/PIPELINE.local.md` 写 `LANES=6`，但运行时
`child_task_limit=4` —— 派第 5 条起不来。实际派 4 条，另外 2 条
（2.4.2.3 映射缺失队列 / 2.5.2.1 常用回写操作）**开工前即 abandon**，释放配额与 Flyway 版本号，
下批可干净重排。调度器不改配置。

---

## 模块 11.2.1.1 退货授权单

**这个功能做什么**：运营在售后单上审批买家的退货申请 —— 确认这一行还能退多少、退货地址、
买家回寄的快递单号、货回来之后怎么处理，可以整单授权、部分授权或拒绝并写明理由。

**技术落地**：

- 迁移：`V161__trade_rma_authorization.sql` — 建 `rma_authorization` / `rma_authorization_line` /
  `rma_decision_event` 三张表，`tenant_id NOT NULL` + ENABLE/FORCE RLS + GRANT + 序列。
  **零 ALTER 既有表、零外键** ⇒ 跨车道 DDL 回归面 = 0
- 端点：`/trade/rma-authorizations` × 10 — capabilities / returnable-quantities / 列表 / 详情 /
  建单 / 改单 / decide / return-logistics / cancel / expire-overdue
- 页面：`/aftersales/returns` — 可退量校验、草稿改单、审批弹窗、回寄物流登记、超时关闭，三态齐全
- 测试：`RmaAuthorizationIntegrationTest` 39 例 + `RmaAuthorizationTenancyIntegrationTest` 4 例

**关键设计决策**：

1. **「已退数量」这一列当前根本不存在** —— 写入所有者是未交付的 11.2.2.2。
   不建影子表，`receivedQty` 恒 null + 标注归属，**不填 0 冒充「没退过」**。
   已退量为 0 与「还没退」是两件事，填 0 会让可退量算错并造成实质资损。
2. 可退量**钉三层**：表级 CHECK + DB 触发器真查 + 服务层审批时重算。没有履约投影时已发按 0 计。
3. **刻意没有 `RECEIVED` 状态** —— 登记实收属 11.2.2.1 未交付；编一个「已收到」会让下游
   误做入库分录，而退回商品**默认进待检**，当已收货处理会把残次品当新品卖出去。
4. 超时关闭做成显式端点而非定时器（定时器归 worker 进程），扫描单次上限 500 张，
   判据只看时限 ⇒ 无遗漏风险，只是延迟。

**测试逼出来的三个真 bug**：`? in (...)` 与 PG 的 JSONB `?` 运算符冲突，报错与真实原因无关；
审批留痕 `occurred_at` 取错列（超时关闭被记成审批时刻）导致时间线时好时坏；
在途累计按 `case_line_id` 排除，但同一售后单的多张授权单共享该列 ⇒ 「上限已被占满」形同虚设。

---

## 模块 11.3.2.1 换货授权

**这个功能做什么**：批准换货 —— 确认能换多少、替代商品有没有货，**真的把替换货预占下来**，
生成新出库安排，同时挂住「旧货要收回」，防止只发不收。

**技术落地**：

- 迁移：`V162__trade_exchange_authorization.sql` — 5 张新表，`tenant_id NOT NULL` + ENABLE/FORCE
  RLS，**零 ALTER 既有表**
- 端点：`/trade/aftersales/exchanges` × 6 — capabilities / filters / list / detail / authorize / cancel
- 页面：`/aftersales/exchanges`
- 测试：`ExchangeAuthorizationIntegrationTest` 25 例 + `ExchangeAuthorizationTenancyIntegrationTest` 6 例，
  前端模型 25 例 + allowlist 4 例

**关键设计决策**：

1. **预占是真的远程写，不是「读一下 + 一个标志位」**。warehouse 拥有 `inventory_reservation`，
   trade 调它的授权写端点并把返回的 `reservation_id` 存为 NOT NULL 无默认 ⇒
   「只读 + 标志位」在这个 schema 上**不可表达**，从结构上排除了假预占。
2. **蓝图那句加粗由 DB 强制**，不是服务层自律：两个 `DEFERRABLE INITIALLY DEFERRED` 约束触发器
   在 COMMIT 时复核每行恰好一个预占、恰好一个旧货退回要求、`reserved == authorized`。
   测试**绕开服务层**直接写 SQL，断言 PostgreSQL 拒绝。
3. 并发：warehouse 的 `inventory_reservation` **没有** `(source_system, source_ref_id)` 唯一约束，
   幂等在 DB 层无兜底 ⇒ 远程调用**前**先锁 aftersales_case 头是承重的，不是风格选择。
   测试断言并发双授权时 warehouse 端点只被调一次。
   ⚠️ 这是 warehouse 的既有设计缺口，会影响后续所有跨服务预占类模块。

**测试逼出来的真 bug**：jOOQ **没有** `fetchOne(String, Class, Object...)` 重载，
`Object.class` 被当成**参数**绑定（因为到处写 `?::uuid` 所以表面看着对）；
以及 `aftersales_case_line` 是 append-only（UPDATE 已 REVOKE），
在它上面 `SELECT ... FOR UPDATE` 既失败也没意义 —— 加锁只在 case 头上。

---

## 模块 11.1.1.3 售后受理

**这个功能做什么**：受理平台或内部发起的售后请求 —— 自动判定是否在售后期内、是否符合条件，
指定责任人、设置回复时限；**平台强制必退的情形自动判出来**，不让运营为了业绩拖着不退被平台判责。

**技术落地**：

- 迁移：`V164__trade_aftersales_intake.sql`
- 端点：`/trade/aftersales/intake` × 10 + `AftersalesPolicyQueryPort`（跨服务读 identity 政策）
- 页面：`/aftersales/intake`
- 测试：`AftersalesIntakeIntegrationTest` 28 例 + `AftersalesIntakeTenancyIntegrationTest` 4 例，
  兄弟定向回归 `AftersalesCase*` 53 例，前端模型 19 例 + allowlist 4 例

**关键设计决策**：

1. **纠正了任务书的一个前提**：任务书写「17.5.1.1 业务参数尚未交付」。实测集成头 —— **它已交付**。
   8 条出厂默认值里 AFTERSALE 类恰好两条正是所需：`AFTERSALE_RETURN_WINDOW_DAYS`（30 天）
   与 `AFTERSALE_MUST_REFUND_REASONS`。经 identity 授权只读端点读，**透传调用方自己的
   Authorization**，不落影子副本；两条参数**一次请求同时取回**（分两次读会拼出
   「旧期限 + 新词表」这种不存在的组合）；不可达时 503 并说清唯一真身，**绝不返回编造默认值**。
2. **必退判定优先级固定** `平台声明 > 原因分类映射 > 不命中`。七类原因映射六种必退情形显式成表；
   `FRAUD_RETURN` **刻意不映射** —— 欺诈恰恰是不能自动同意退款的一类。
3. **「不能拖着不退」钉三层**：DB 触发器禁止把 `PLATFORM_DECLARED` 必退降级为非必退
   （测试绕开服务层直接写 SQL 实跑被拒）；判定凭据**列级**只授 `UPDATE(superseded_at)`；
   **入参根本没有「我要不要必退」这个开关**，平台强制必退只接受平台消息声明。
4. 回复时限在 17.5.1.1 里确实不存在。它没有伪装成政策参数，而是落在本服务租户可维护的
   `aftersales_reply_sla`；平台给 + 租户 SLA **两档都取不到时受理直接失败**并指向配置入口，
   而不是替租户编一个默认小时数。

**测试逼出来的真 bug**：`handling_plan_source` / `policy_source_scope` 声明 `varchar(16)`
装不下 18 字符的 `MUST_REFUND_FORCED` / `DEFINITION_DEFAULT`，必退单必 409；
判定凭据整表 `REVOKE UPDATE` 与 rejudge 的 superseded 标记**直接冲突**，改为 V156 同款列级白名单。

---

## 模块 17.4.2.2 审批记录

**这个功能做什么**：把一张审批单的全链路摊开 —— 谁发起、走到哪个节点、每个节点谁批的、
意见是什么、什么时候批的，中间有没有转交、驳回、撤回，最后结果如何，并可导出用于审计。

**技术落地**：

- 迁移：`V163__approval_record_queries.sql` — **不建任何新表**，只加 9 条索引 + 页面权限。
  上游只有 1 条时间线索引，按人 / 按节点 / 按时间范围 / 重发链 / 来源定位六类查询一条都没有
- 端点：6 个**纯读**端点（检索 / 单条链路 / 按人视角 / 统计 / 导出 CSV / 能力声明），
  **一个写端点都没有**
- 页面：`/org/approval-records`，模型 30 例单测
- 测试：`ApprovalRecordIntegrationTest` 9 例 + `ApprovalRecordTenancyIntegrationTest` 4 例，
  上游回归 `ApprovalInbox*` 21 例（与台账记录的 16/5 完全一致）

**关键设计决策**：

1. **不建新表**。蓝图每一项都能从上游 `approval_instance` + `approval_instance_action` 只读推导；
   「驳回后重新发起关联原实例」上游**已留列** `original_instance_id`。
   统计走**实时 SQL 聚合**而非快照表 —— 快照表会成为第二副本，刷新属职责外。
2. 继承上游「**审批记录不可修改**」的硬边界：上游给 `approval_instance_action`
   只授 `SELECT, INSERT`，从不授 `UPDATE/DELETE`。本模块**不写任何业务数据**，
   也不去改这个授权。写入所有权始终在 17.4.2.1。
3. **如实上报一处冲突**：任务书说「不要往那两张表写业务数据」，但 V160 迁移注释写着
   「17.4.2.2 与提交侧车道落地撤回时用各自的迁移扩这条 CHECK」。按任务书硬边界执行 ——
   **没有**往 CHECK 塞一个没有代码路径能到达的状态，`capabilities` 如实报 `delivered=false`
   并指明归属「提交侧」。**撤回落地后本模块无需改动即会自动呈现**。

---

## 本批的跨车道取证

**四条迁移的静态预检全清**（无一条命中「已知互相破坏形状」）：

| 迁移 | 建表 | `ADD COLUMN` 无 DEFAULT | `SET NOT NULL` | `ADD CONSTRAINT CHECK` |
|---|---|---|---|---|
| V161 退货授权单 | 3 | 0 | 0 | 0 |
| V162 换货授权 | 5 | 0 | 0 | 0 |
| V163 审批记录 | 0 | 0 | 0 | 0 |
| V164 售后受理 | 3 | 0 | 0 | 0 |

四条迁移**都没有 ALTER 既有表**，跨车道 DDL 回归面为零。

**`api-proxy.ts` 白名单**：三条车道都碰了这个共享累积正则。

- 11.2.1.1 **+35 / −0**、11.3.2.1 **+35 / −0**，均为纯增量（各新增一个 `services[]` 元素，
  插入锚点互相错开，git 能干净求并）
- 17.4.2.2 是在既有单行正则**中段插入**。合并前实测新旧正则的匹配行为：
  **原放行路由 0 条被拦**，新增放行 5 条，反向用例（未知路径）仍被拦 ✓
  但编辑留下了 `?||approval-records` 一个空 alternation（只匹配空串，功能无害但属编辑瑕疵）

**契约并集**：两条 trade 车道各自重新生成过同一个 `trade` 产物。合并结果 786 个导出类型，
**严格大于任一单车道**（774 / 763），新增 35 个（23 `Rma*` + 12 `Exchange*`），**丢失 0 个**。

**缓存假绿**：两条车道都遇到 turbo `FULL TURBO`，均按任务书要求直跑 `tsc --noEmit` 取真实结果。

---

## 遗留与下一步

- **11.2 / 11.3 未交付**：受理判定只推进到 `RECEIVED`（待处理），尚未真正建退货授权单或退款申请
- **卷 2 平台消息 / 卷 15 客服未交付**：`PLATFORM_MESSAGE` 目前只能人工录入触发；
  服务名册为租户可维护登记。两者到位后由同步器写入，**表结构不用改**
- **共享 `ApiErrorHandler` 把 `IllegalArgumentException` 的 detail 压成通用语** ——
  端点拒绝原因无法向用户透出。本批**未改该共享文件**，是否统一由该 handler 决策属另一件事
- **warehouse `inventory_reservation` 缺 `(source_system, source_ref_id)` 唯一约束** ——
  跨服务预占类模块的共性风险，建议单独立项
- 下批重排：2.4.2.3 映射缺失队列、2.5.2.1 常用回写操作
