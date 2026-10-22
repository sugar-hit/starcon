# 2026-10-23: 能力声明校正（batch-68 交付后）

## 摘要

batch-68 把三个下游交付了（15.4.1.2 工单处理 / 16.1.2.2 申诉管理 / 15.4.1.1、15.5.1.1、16.1.2.1 的对侧），
于是**上游模块的能力声明变成了假话** —— 声明里还写着「下游未交付 ⇒ `available=false`」。
本车道只干一件事：把这三处**声明层**（常量字符串、能力声明 payload 的 `available`、javadoc）
改成与事实一致，**不碰任何业务逻辑、数据流、表结构、迁移**。

顺带（调度者补充的第 ④ 项）：修掉 `PlatformViolationService.java` 里 6 个 `U+FFFD` 替换字符
（某次按字节截断的写入把「几」字的 3 个字节打坏），其中 1 处在**运行时字符串**里，
会真的出现在 `/channel/platform-violations/capabilities` 的响应中。

## 决策 / 结论

1. **16.1.2.1 违规与处罚（channel）**：`16.1.2.2` 那一维 `available` 由 `false` 翻成 **`true`**，
   `ownerModule` 保留 `16.1.2.2`（归属说明）。两个下游都在 **channel 服务同库**（V308 违规 / V310 申诉），
   所以口径写成「本模块只把违规的处理状态记到「申诉中」；申诉单、举证材料、提交与结果由 16.1.2.2 记录，
   **本模块不复制申诉数据**」。`12 财务`那一维**保持 `available=false`** —— 财务服务在本仓仍未落地。

2. **15.4.1.1 工单列表（crm）**：`ticket_processing` 维 `available` 由 `false` 翻成 **`true`**，
   `ownerModule` 保留 `15.4.1.2`，并指向**真实存在**的入口 **`/crm/service-ticket-process`**
   （15.4.1.2 本批新建的独立前缀）。**api-proxy 一字未改** —— 15.4.1.1 的白名单元素
   **仍然不放行 `{uuid}/process`**（那是 15.4.1.1 自己刻意不放行的形状，与 15.4.1.2 无关）。

3. **15.5.1.1 自动回复（crm）**：`human_intervention_signal` 维**保持 `available=false`** ——
   只把「15.4.1.2 未交付」改成如实说法：**15.4.1.2 已交付**（同服务同库，
   `crm_service_ticket_action` 是工单侧介入动作的事实表），但**本模块当前仍未把工单侧介入动作
   纳入豁免判定** ⇒ 豁免判定**仍只依据 15.1.1.2 的 SENT 发送记录**。

4. **为什么不把 15.5.1.1 的 `available` 也翻成 `true`** —— 翻了就**又是一个假话**：
   自动回复的豁免判定逻辑**真的没读** `crm_service_ticket_action`（本车道**刻意不改判定逻辑**，
   那是业务行为变更，不在声明层车道内）。`available` 描述的是**本模块**的能力，
   本模块确实还没拿到工单侧介入信号 ⇒ 保持 `false` 才是如实的。
   把「为什么还读不到」的理由从「下游没交付」换成「下游已交付但本模块未接入」，
   这样**真话不假、人也不被误导**，且**口径统一留给另一条车道**。

5. **契约零变化（实测，不是推断）**：改动全部落在**运行时值**与 **javadoc** 上，
   没有碰任何 record 的结构与注解。仍做了取证：分别导出**改动前 / 改动后**的
   `channel.openapi.json` 与 `crm.openapi.json` 做**逐字节 diff** ⇒ **两者 IDENTICAL**。
   因此**不需要**跑 `tools/codegen/catalog-contracts.py`，`packages/api-contracts` / `packages/sdk`
   **零改动**（也就没有「codegen 把别的服务 `.ts` 按字母序重排」的 churn 问题）。

6. **`related[].href` 保持 `null`** —— 该字段在本服务里**从未被赋值**（5 条 related 全是 `null`），
   前端也**根本不消费** `related` 与 `href`（`service-ticket-list-workspace.tsx` 只按
   `dimensions[].available` 动态分组渲染）。擅自给一条填 `href` 等于**新造一个语义**
   （API 路径还是页面路由？），收益为零、风险未知 ⇒ 不填，入口路径写进 `source` / `impact` 文案。

7. **前端不需要改**：前端**按后端返回的 `available` 动态渲染**「可用能力 / 未交付」两组，
   没有硬编码的可用性清单 ⇒ 后端翻标志后前端**自动**把 `ticket_processing` 移到可用组，
   `16.1.2.2` 申诉维同理。前端那几处**注释**（`service-ticket-list-model.ts:34`、
   `service-ticket-list-workspace.tsx:41`、`platform-violations-model.ts:21` 等）仍写着「未交付」，
   属**前端车道自己的文件**，本车道**不碰**（见「后续待办」）。

## 涉及文件

**主源码（声明层）**

- `backend/services/channel/src/main/java/com/starcon/channel/PlatformViolationService.java`
  —— `STATE_MACHINE_SEMANTICS` / `DOWNSTREAM_NOTE` / `UPSTREAM_NOTE` / `capabilities()` javadoc /
  `16.1.2.2` 那一维 payload（`available` + `impact`）/ 类 javadoc；**另含 ④ 的 6 处 U+FFFD 修复**
- `backend/services/channel/src/main/java/com/starcon/channel/PlatformViolationModels.java` —— 类 javadoc
- `backend/services/channel/src/main/java/com/starcon/channel/PlatformViolationController.java` —— 类 javadoc
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketListService.java`
  —— `capabilities()` javadoc / `ticket_processing` 维 payload / `related` 里 15.4.1.2 条目 /
  `boundaries` 里对应边界 / `downstreamNote`
- `backend/services/crm/src/main/java/com/starcon/crm/ServiceTicketListController.java` —— 两处 javadoc
- `backend/services/crm/src/main/java/com/starcon/crm/AutoReplyService.java`
  —— 类 javadoc（两条纪律 + 人工介入判定口径）/ `human_intervention_signal` 维 payload / 口径说明串
- `backend/services/crm/src/main/java/com/starcon/crm/AutoReplyModels.java` —— `CrmAutoReplyTriggerType` javadoc

**测试（同步改断言）**

- `backend/services/channel/src/test/java/com/starcon/channel/PlatformViolationIntegrationTest.java`
  —— `16.1.2.2` 那一维断言由 `isFalse()` 翻成 `isTrue()`（并补 `ownerModule` / `impact` 断言）
- `backend/services/crm/src/test/java/com/starcon/crm/ServiceTicketListIntegrationTest.java`
  —— `ticket_processing` 维断言由 `isFalse()` 翻成 `isTrue()`（并补 `source` 含
  `/crm/service-ticket-process` 的断言）+ 类 javadoc

**台账 / 文档**

- `docs/product/capability-blueprint/module-progress.md`
  —— **只动 15.4.1.1 / 15.5.1.1 / 16.1.2.1 三行的证据格**（各补一句「batch-68 交付校正」）；
  状态格、总览计数**未动**（总览由调度者统一刷新）
- `docs/sessions/2026-10/2026-10-23-capability-declaration-refresh.md` —— 本文件

**未动**：`packages/api-contracts`、`packages/sdk`、`apps/web/src/lib/api-proxy.ts`、
任何迁移文件、任何 15.4.1.2 / 16.1.2.2 本批新模块的文件。

## 关键 trade-off / 讨论

- **「`available` 是关于下游的，还是关于本模块的？」** —— 这是本车道唯一真正的判断题。
  16.1.2.1 的 `16.1.2.2` 维与 15.4.1.1 的 `ticket_processing` 维都指向**别的模块**，
  所以翻成 `true` 是「那个模块的能力**现在有了**」；而 15.5.1.1 的 `human_intervention_signal`
  维描述的是**本模块的豁免判定**用不用得到那个信号 —— 本模块真的没用 ⇒ 保持 `false`。
  同一个 `available` 字段，两种语义，必须**逐维**判，不能一刀切。
- **为什么 16.1.2.1 的 `16.1.2.2` 维不能也保持 `false`** —— 它和 15.5.1.1 不同：
  16.1.2.1 声称的能力就是「把违规的处理状态记到「申诉中」」，这个能力**现在真的具备**
  （申诉管理已交付且在同服务同库），且「申诉单归 16.1.2.2」是**归属声明**而非缺口 ⇒ 翻 `true` 才是真的。
- **字符修复只修这一个文件** —— 全仓还有 159 文件 / 450 处同类 `U+FFFD` 损坏，
  那是**独立的系统性缺陷**，调度者已单独记进
  `docs/backlog/2026-10-23-replacement-char-encoding-corruption.md`，要**先取证成因再批量修**。
  本车道只修自己本来就要改的那一个文件。

## 后续待办

1. **自动回复的豁免判定口径统一**（**不在本车道**）：15.5.1.1 至今**只**依据 15.1.1.2 的
   SENT 发送记录判「人工介入」，**没有**读同库同服务的 `crm_service_ticket_action`
   （15.4.1.2 写的事实表）⇒ 「无人工介入超时」的口径**偏长**。
   接线属于**业务行为变更**，需单独一条车道。
2. **前端注释同步**（前端车道）：`service-ticket-list-model.ts:34`、
   `service-ticket-list-workspace.tsx:41`、`platform-violations-model.ts:21`、
   `platform-violations-workspace.tsx:84`、`apps/web/src/app/crm/service-tickets/page.tsx:19`、
   `apps/web/src/app/compliance/account/page.tsx:19` 等处仍写「15.4.1.2 / 16.1.2.2 未交付」。
   **行为**没问题（前端按后端 `available` 动态分组），只是注释过期。
3. **api-proxy 允许清单注释**（api-proxy 车道）：`service-tickets-allowlist.test.ts:149`、
   `platform-violations-allowlist.test.ts:163`、`account-health-allowlist.test.ts:161`
   的注释前提已过期；**放行结论本身仍正确**（申诉走 `/channel/platform-appeals`、
   工单处理走 `/crm/service-ticket-process`，都是**独立前缀**）。
4. **其它模块的同类过期声明**（不在本车道）：`AccountHealthService.java:97`（16.1.2.1/16.1.1.1 的
   `16.1.2.2` 维仍标「未交付」）、`ServiceTicketListModels.java` 之外的
   `AutoReplyService` 类 javadoc 之外的 `crm` / `channel` 其它声明点 —— 需要**逐个取证**后
   决定是否同批校正。

## 关联

- 蓝图：`docs/product/capability-blueprint/module-progress.md`（15.4.1.1 / 15.5.1.1 / 16.1.2.1 三行）
- 本批交付：16.1.2.2 申诉管理（`V310__channel_platform_appeal.sql`）、
  15.4.1.2 工单处理（`V309__crm_service_ticket_process.sql`）
- 相关会话：`docs/sessions/2026-10/2026-10-23-platform-appeal-management.md`、
  `docs/sessions/2026-10/2026-10-23-service-ticket-process.md`
- 系统性缺陷：`docs/backlog/2026-10-23-replacement-char-encoding-corruption.md`
- 纪律依据：`AGENTS.md`（模块边界纪律 / 多租户 / 契约唯一共享面）
