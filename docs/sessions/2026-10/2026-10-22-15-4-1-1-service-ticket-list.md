# 2026-10-22 · 15.4.1.1 工单列表（crm）

> 车道 `15.4.1.1`，分支 `feat/15-4-1-1`，工作树
> `starcon-worktrees/integration/.worktrees/15.4.1.1`。
> 蓝图为 `docs/product/capability-blueprint/15-crm-and-service.md` 的 15.4.1.1 节；
> 上游工单管理 = 11.4.2.1（trade 服务，V270 `aftersales_service_ticket`）。

## 决策摘要

**蓝图原文**：职责「客服工单列表：类型、优先级、状态、SLA 剩余、关联买家与订单、分配客服、
转派与升级」；输入「工单创建（自动/人工）；筛选条件」；输出「工单列表 + SLA 预警」；
关系「上游 = 15.1.1.3、11.4.2.1；下游 = 15.4.1.2 处理」；
**加粗句「SLA 计时需按营业时间而非自然时间，否则夜间积压会造成误判」**。

**关键结论：工单的事实来源在 trade 服务，本模块是它的「客服视图」，不是它的副本。**

| 蓝图要求 | 落地 |
| --- | --- |
| 类型 / 优先级 / 状态 / 分配客服 | 上游 `aftersales_service_ticket` 的既有字段，经授权 HTTP API 只读 |
| **SLA 剩余（营业时间口径）** | 本服务自己的营业时间轴（`crm_service_business_hours` / `crm_service_business_holiday`）+ `BusinessHoursCalendar` 现算 |
| 关联买家与订单 | 订单 = 上游 trade（工单 → 售后单 → 订单）；买家 = 本服务自己的 `crm_message` / `crm_customer_contact`（按订单号关联） |
| 转派与升级 | 上游工单详情里的派单留痕（append-only）+ `escalated_case_no` / `escalated_dispute_no` |
| SLA 预警 | 列表汇总里的 `overdue`（营业时间口径），与行内剩余量**同源**（同一条轴、同一参照时刻） |
| 下游 15.4.1.2 工单处理 | **未交付** ⇒ 能力声明 `available=false` + `ownerModule=15.4.1.2`，**不指向任何不存在的入口** |

**加粗句的物理落点**（本模块的判据所在）：

1. **到期时刻 = 在营业时间轴上累加**，不是 `createdAt + N 小时`。
   `BusinessHoursCalendar` 沿每日营业区间逐段推进：跨夜、跨周末、跨节假日**都不计时**，
   午休也不计时；时区按 scope 的 IANA 名折算。
2. **口径可配置、可审阅**：营业时段（工作日区间 + 时区）与节假日例外落在**本服务自己的配置表**，
   每次改写留一条 **append-only** 的审计（谁、何时、改成了什么）——
   营业时间直接决定客服绩效口径，「当时的口径是什么」必须查得到。
3. **没配营业时间 ⇒ 状态是 `NO_CALENDAR`（算不出来），刻意不退回自然时间**。
   悄悄退回等于把加粗句要避免的误判请回来，而且现场**看不出来**（数字照常有，只是全错了）。
   所以「未配营业时间」与「无时限」是两个不同的状态，不能混成一个。
4. **库里不存会自己过期的「剩余小时数」**：剩余秒数是**读取时刻**的派生态
   （integration test 用 `information_schema` 实查 CRM 库里没有任何 remaining 列）；
   响应里同时给营业时间口径与自然时间口径两个剩余量作对照。
5. **SLA 状态筛选与逾期筛选按营业时间口径在本服务判定**：
   上游的同名筛选是**自然时间**口径，直接下推会漏掉「自然时间还没到、营业时间已经超了」的单
   —— 夜间积压正是这一类。

## 关键 trade-off

- **只读，不建工单表**。工单归 11.4.2.1（trade 服务）所有。AGENTS.md 禁止跨服务 SQL / 跨库 FK /
  共享视图 ⇒ 本服务经 `GET /trade/service-tickets` 等授权 HTTP API 只读，
  **转发调用方自己的 bearer**，租户由上游按已认证成员关系解析并强制 RLS ⇒ 租户隔离是**两道**。
  代价：列表要经上游；收益：工单事实**只有一个**写入所有者，不存在第二套口径。
- **本服务建的是「营业时间配置表」，不是「工单表」**。营业时间是**租户自己的经营口径**
  （这家店几点在上班），既不属于 trade 的工单事实，也不属于 channel 的店铺目录，
  它的真身必须在拥有 SLA 判定的这一侧 —— 写死成常量等于每个租户都迁就同一张时刻表。
- **两条执行路径**（口径都写在响应里）：
  - *快路径* —— 没有营业时间口径的筛选、排序也不是「按 SLA 排」时，分页/筛选/排序全部下推上游
    （真 `LIMIT/OFFSET` + 独立 `COUNT`），总数即上游总数；
  - *本地扫描路径* —— 需要按营业时间口径筛选或排序时，把上游结果整批取回后自己判、自己排、自己分页，
    并报出扫描量与**是否触到扫描上限**（触到时总数是**下界**，不是全量）。
  代价：SLA 筛选比普通筛选慢（一次扫描）；收益：**不把两种口径混在一处**。
- **关联订单要走「工单 → 售后单 → 订单」两跳**（上游的工单行不带订单号）。
  两跳都做**完全相等**匹配（上游的 `caseNo` / `keyword` 都是模糊匹配）——
  拿一条「相近」的订单当上下文比显示「不可用」更糟：客服会照着错的单子去处理。
- **转派历史逐行取**（上游没有批量取转派历史的接口，只有单张详情里带着它）。
  取不到时标 `historyAvailable=false` 并写明原因，**不编一个 0** ——
  「没有转派过」与「没查到转派记录」是两件事。
- **营业时段的写入是「整段替换」**（改一次作用域 = 该作用域的新口径），
  所以同 scope 的时区一致性由构造保证（一次请求只有一个时区），
  DB 层的触发器是第二道闸（绕开服务层直连 SQL 时仍然拦得住）。
- **口径作用域按「请求级」解析**（`shopId` 参数优先，没配则回落租户默认），
  而不是逐行解析 —— 逐行解析会让「按 A 店口径筛出来的单，行里却按 B 店口径显示剩余量」。
  店铺维度预留了 `SHOP:<uuid>` 作用域（配置表支持），但工单侧目前**没有**可靠的店铺归属事实
  （上游工单行不带店铺），所以列表按请求口径走，并在响应里写明用的是哪一条。

## 涉及文件

**后端（crm 服务，端口 8086）**

- `backend/services/crm/src/main/resources/db/migration/V306__crm_service_ticket_list.sql`
  （三张新表 + RLS + GRANT + 索引 + 触发器）
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketListModels.java`
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketFactsPort.java`
  （跨服务只读端口：工单列表 / 售后单 / 订单 / 工单详情）
- `backend/services/crm/src/main/java/com/starcon/crm/BusinessHoursCalendar.java`
  （营业时间轴：纯计算，无 Spring / 无 DB / 无时钟）
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketListService.java`
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketListController.java`

**后端测试**

- `backend/services/crm/src/test/java/com/starcon/crm/ServiceTicketListIntegrationTest.java`（20 条）
- `backend/services/crm/src/test/java/com/starcon/crm/ServiceTicketListTenancyIntegrationTest.java`（4 条）
- `backend/services/crm/src/test/java/com/starcon/crm/BusinessHoursCalendarTest.java`（9 条）

**前端**

- `apps/web/src/app/crm/service-tickets/page.tsx`
- `apps/web/src/components/crm/service-ticket-list-model.ts` + `.test.ts`
- `apps/web/src/components/crm/service-ticket-list-workspace.tsx`
- `apps/web/src/app/api/crm/service-tickets-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**只新增**一条独立的 `crm/` 元素，未碰既有任何一条）

**契约 / 台账**

- `packages/api-contracts/src/contracts/crm.ts` + `packages/api-contracts/src/crm.openapi.json`
  （codegen 产物，`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`）
- `docs/product/capability-blueprint/module-progress.md`（只改 `| 15.4.1.1 |` 那一行的「状态」「证据」两格）

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` ✅；`pnpm typecheck` ✅
- L1 模块闸：`./mvnw -pl services/crm -am test -Dtest='ServiceTicketList*,BusinessHoursCalendarTest'`
  ⇒ **33 条全绿**（20 + 4 + 9）
- 前端：`pnpm vitest run apps/web/src/components/crm/service-ticket-list-model.test.ts
  apps/web/src/app/api/crm/service-tickets-allowlist.test.ts` ⇒ 19 条全绿；
  因为改了共享白名单 `apps/web/src/lib/api-proxy.ts`，**另跑了全量 allowlist 回归**
  `pnpm vitest run apps/web/src/app/api` ⇒ 181 文件 / 1046 条全绿
- `pnpm lint` ✅（无新增告警）

## 后续待办

- **15.4.1.2 工单处理**（下游，未交付）：回复 / 备注 / 状态流转 / 解决确认 / 满意度回收 /
  SLA 暂停恢复。本模块已为它留好入口形态（能力声明里 `ownerModule=15.4.1.2`），
  但**刻意不提供**任何处理动作。
- **工单侧的店铺归属事实**：上游工单行不带店铺 id ⇒ 营业时间目前只能按请求级作用域解析。
  若 trade 侧后续把店铺维度暴露出来，可改为逐行解析店铺口径（配置表已支持 `SHOP:<uuid>` 作用域）。
- **本地扫描路径的规模**：扫描上限为 25 页 × 200 = 5000 张工单，触到时总数是下界。
  工单量级显著上升后需要评估（要么上游加营业时间口径的下推筛选，要么本服务改投影）。
- **`channel` 契约漂移**（**不是本车道造成**）：`packages/api-contracts/src/contracts/channel.ts`
  与已并入的 16.1.1.2 绩效指标监控不同步 —— 本车道跑 codegen 时它被一并重新生成，
  为守住「只碰自己的文件」已 `git checkout` 还原，留在集成头由调度者统一处理。
