# 2026-10-23 · 15.4.1.2 工单处理（crm）

> 车道 `15.4.1.2`，分支 `feat/15-4-1-2`，工作树
> `starcon-worktrees/integration/.worktrees/15.4.1.2`。
> 蓝图为 `docs/product/capability-blueprint/15-crm-and-service.md` 的 15.4.1.2 节；
> 上游工单列表 = 15.4.1.1（crm 服务，V306），工单事实所有者 = 11.4.2.1（trade 服务，V270）。

## 决策摘要

**蓝图原文**：职责「处理工单：回复、备注、状态流转、关联订单与售后单、解决确认、
满意度回收、SLA 暂停/恢复」；输入「处理动作；关联单据；满意度评分」；
输出「工单状态 + 处理记录 + 关联单据更新」；
关系「上游 = 15.4.1.1；下游 = 11 售后（升级）、9 订单」；
**加粗句「工单关闭不等于问题解决，需确认实际结果（退款到账、换货发出）才计为解决」**。

| 蓝图要求 | 落地 |
| --- | --- |
| 回复 | 转发上游 `POST /{id}/first-reply`（**回复时限的完成时刻只在上游记一次**）+ 本服务 `crm_service_ticket_note`（note_type=REPLY，渠道必填） |
| 备注 | 本服务 `crm_service_ticket_note`（note_type=NOTE，渠道必须为空），**append-only** |
| 状态流转 | 转发上游 `POST /{id}/status` |
| 关联订单与售后单 | 本服务 `crm_service_ticket_link` 只存**关系**；**订单 / 售后单的当前事实**读取时经上游授权 API 现取（**不复制一列**） |
| 解决确认 | 本服务 `crm_service_ticket_resolution`（**另一张表、另一个动作**） |
| 满意度回收 | 转发上游 `POST /{id}/satisfaction`（**不另存一份**） |
| SLA 暂停 / 恢复 | 本服务 `crm_service_ticket_sla_pause`（区间）+ `ServiceTicketProcessSlaAxis`（**沿 15.4.1.1 的营业时间轴**） |
| 处理记录 | 本服务 `crm_service_ticket_action`（append-only 动作时间线） |
| 下游 11 售后（升级） | 转发上游 `POST /{id}/escalate` —— 升级物**真的**落在 trade 库里，本模块只记关系 |

**加粗句的物理落点（四层）**：

1. **close 与「解决确认」是两个动作、两张表、两个响应字段**。
   close 走上游（落终态 RESOLVED / CANCELLED）；解决确认落本模块自己的
   `crm_service_ticket_resolution`。合并成一个 status 之后，
   「关掉了但退款没到」与「退款已到」在库里会长得一模一样 —— 那正是加粗句要避免的误判。
2. **响应里 `closed` 与 `resolutionConfirmed` 是两个字段**，前端把两者做成并排的两张卡。
3. **解决确认要求工单已关闭**（先关后确认）：未关闭就确认 ⇒ 400，理由原文写明「关闭 ≠ 解决」。
   顺序本身就是纪律的落点。
4. **能力声明的 `boundaries` 里写死这条**，页面把它渲染成一条醒目提示（不是脚注）。

**SLA 的物理落点**：

- 计时轴 = 15.4.1.1 建的 `crm_service_business_hours` / `_holiday`（**同库同服务**，不是跨库），
  到期时刻在轴上累加，**不是** `createdAt + N 小时`。
- **暂停扣的是营业时间**：暂停区间里的营业时间不计入 SLA 消耗 ⇒ 到期时刻推后
  **恰好等于那段暂停里的营业时间**（测试里 1 个营业小时 ⇒ 到期推后 1 个营业小时）。
- **没配营业时间 ⇒ 一律 NO_CALENDAR，且不给暂停**：没有轴就没有「暂停掉的那段营业时间」，
  记一条暂停只会让人以为 SLA 被冻结了。刻意**不退回自然时间**。
- **「暂停中」是第四种 SLA 状态**（既不是 PENDING，也不是 BREACHED）。
- **暂停不能抹掉暂停之前就已经发生的超期**：判「暂停中 vs 已超期」时拿
  「假如没有这次暂停」的到期时刻来比，否则「先拖到超期、再点一下暂停」就成了洗白超时的手法。

## 关键 trade-off

- **事实不复制，处理侧事实自己建表**。工单的**写**动作全部转发 trade（11.4.2.1 拥有），
  但 trade **没有**备注 / 处理记录 / 关联单据 / 解决确认 / SLA 暂停这五样 ⇒ 本模块自建五张表。
  代价：五张表要在本服务里维护；收益：这五样在 trade 侧**根本不存在**，
  硬塞进上游会污染它的领域模型。
- **满意度与回复时限事实只转发，不另存**。同一件事两个事实源迟早打架
  （trade 侧已是唯一写入所有者，本模块再存一份就是第二个）。
- **「推给上游关联单据」如实标不可用**。上游 trade **没有**改写已有售后单 / 订单的端点
  （只有读与新建）⇒ 能力声明 `linked_document_push` = `available=false` + ownerModule=11.1.1.1，
  关联单据的更新只落在本模块自己的 `last_sync_note` 上，**不假装推给了上游**。
- **处理记录只记成功的动作**。失败的动作随事务回滚一起消失 —— 那才是真相；
  「写了但没生效」留在时间线里比不写更糟。
- **解决确认支持撤销**（只标 `revoked_at`，行不删）。假的「退款已到账」比没有更糟 ——
  它会被当成「可以结案」的依据。
- **命名空间与 15.4.1.1 刻意分开**（`/crm/service-ticket-process` vs `/crm/service-tickets`）：
  那边回答「有哪些工单」，这边回答「这一张怎么处理」。api-proxy 的首 token
  `service-ticket-process` 与 `service-tickets` **不是前缀关系**（第 13 个字符处分叉），
  两个方向都实测不互吃。
- **本车道未改 15.4.1.1 的端点**（按任务书「只碰自己的文件」）。代价：15.4.1.1 的能力声明
  仍写「15.4.1.2 未交付」，且它的列表 SLA 剩余不含暂停感知 ⇒ 两处口径在暂停中的单上
  可能短暂不一致。这是**跨模块的一致性欠账**，已如实写进台账的「已知缺口」，
  需调度者决定是否开修复车道。

## 文件清单

**迁移（crm 服务）**

- `backend/services/crm/src/main/resources/db/migration/V309__crm_service_ticket_process.sql`

**后端（crm 服务）**

- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketProcessModels.java`（29 个传输类型）
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketProcessPort.java`（写上游 trade 的端口）
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketProcessSlaAxis.java`（暂停感知 SLA 轴，纯计算）
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketProcessService.java`
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketProcessController.java`（15 个 path）

**后端测试**

- `backend/services/crm/src/test/java/com/starcon/crm/ServiceTicketProcessIntegrationTest.java`（23 例）
- `backend/services/crm/src/test/java/com/starcon/crm/ServiceTicketProcessTenancyIntegrationTest.java`（4 例）
- `backend/services/crm/src/test/java/com/starcon/crm/ServiceTicketProcessSlaAxisTest.java`（9 例）

**前端**

- `apps/web/src/app/crm/service-ticket-process/page.tsx`
- `apps/web/src/components/crm/service-ticket-process-model.ts`
- `apps/web/src/components/crm/service-ticket-process-workspace.tsx`
- `apps/web/src/components/crm/service-ticket-process-model.test.ts`（15 例）
- `apps/web/src/app/api/crm/service-ticket-process-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，既有条目一字未动）

**契约 / 台账 / 会话**

- `packages/api-contracts/src/contracts/crm.ts`、`packages/api-contracts/src/crm.openapi.json`（codegen 纯增量）
- `docs/product/capability-blueprint/module-progress.md`（**只改 15.4.1.2 这一行的「状态」「证据」两格**）

## 验证

- L0：`./mvnw compile` 绿；`pnpm typecheck` 三包绿；`pnpm lint` 绿（本车道无新增告警）。
- L1：后端 `-Dtest='ServiceTicketProcess*'` **36 例绿**（23 + 4 + 9，真实 PostgreSQL 16 + Testcontainers）；
  前端本模块 **20 例绿**；`pnpm vitest run apps/web/src/app/api` 全量 allowlist 回归 **1067 例绿（185 文件）**
  （改了多车道共用的累积白名单必须全量回归）。

## 后续待办

1. **15.4.1.1 的能力声明仍写「15.4.1.2 未交付」** —— 它的端点归它自己，本车道按边界未改。
   需要一条修复车道把那一维改成已交付并指向本模块。
2. **15.4.1.1 列表的 SLA 剩余不含暂停感知** —— 暂停只在本模块生效，两处口径在暂停中的单上可能短暂不一致。
3. **把处理结果推给上游售后单 / 订单** —— 等 trade 补上「改写已有售后单 / 订单」的端点。
4. **实际把回复投递到买家通道**由 15.1.2.1 消息发送负责；本模块只管工单侧的回复记录与回复时限事实。
5. **工单事实里没有店铺 id** ⇒ SLA 口径一律取租户默认作用域（DEFAULT）。
6. 若上游补上「读一张工单的全部附属事实」的批量端点，本模块的 `loadStates` 可以从 N+1 收敛成一次往返。
