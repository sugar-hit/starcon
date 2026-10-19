# 2026-10-19 · 4.3.1.2 批量发布（接手收尾）

> 本次为**接手半成品**：上一位 worker 13:00–13:27 工作，因 Token Plan 用量上限
> （错误码 42212）被运行时强杀，留下已完成但未 commit 的工作。本次接手时后端、
> 迁移、契约、前端工作区、api-proxy 白名单**均已存在于工作区**，实际缺口是
> **前端 3 处 typecheck 红 + 未跑验证 + 未提交**。

## 决策摘要

### 1. 限流是**数据**，缺配置就 fail-closed 不派发

蓝图加粗句「**批量任务必须限流**：按平台各自的 API 配额排队，否则会触发平台限流
并可能影响店铺安全」是本模块的立身之本。关键决策是**限流参数落表
（`publish_rate_limit`，可配置可查），服务层没有写死的全局默认值兜底**：

- 某平台未配置限流行 ⇒ 本轮**一条都不派发**，并在 `DispatchLaneReport.note`
  写明「该平台未配置限流参数」。
- **反面做法**（已避免）：服务层兜一个「默认并发 5」。多平台场景下那就是对所有平台
  施加同一份臆测配额，正是加粗句要防的事。
- 口径两条同时生效取更紧者：`max_in_flight`（在途并发）+ `requests_per_window /
  window_seconds`（滑动窗口派发次数）。

### 2. SUCCEEDED 是终态，DB 触发器钉死

「批量重试只重试失败项」若只写在服务层，将来一次手滑的
`update publish_task set state='PENDING'` 就会重跑全店已发布商品。落法是
`guard_publish_task_terminal` 触发器：SUCCEEDED → 非 SUCCEEDED 直接抛 23514；
DISPATCHED → PENDING 也拒（等于悄悄取消已发出的平台请求）。
集成测试 `terminalSuccessCannotBeRequeued` 实跑断言这条 SQLSTATE。

### 3. 重排提交**完整次序**，不是新 position 数值

让前端传 position 数值可以传出一个与真实执行次序不符的队列。`ReorderRequest`
只接受 taskIds 的完整次序，服务端据此重写 position，派发按 position 升序取 ——
次序真的变。测试 `reorderChangesExecutionOrder` 断言的是**取到的第一条随之改变**，
不是字段回显。

### 4. 部分失败逐条如实回报

`BatchActionResult` 分 `changedTaskIds`（真被改了）与 `rejected`（被拒 + 原因），
前端原样回显，不静默吞掉。派发同理：`DispatchResponse.lanes` **逐限流行**给账，
Amazon 与 TikTok 各走各的账，不并成一个「共派发 N 条」的假账。

### 5. api-proxy 白名单新增**独立元素**，不并入既有 alternation

`api-proxy.ts` 的 channel / trade 分支是超长单行正则，多车道同改一行必然冲突
（结构性，retry 不自愈）。本次在 4.1.1.2 `listing-details` 条目**之后**、既有
trade 元素**之前**插入一条独立 `services[]` 元素。刻意不放行：裸 `listing-publish`、
`batches/{uuid}/retry-all`（重复发布）、`tasks/{uuid}`、`rate-limits/{uuid}`。
判别顺序：`capabilities` / `rate-limits` 必须排在 `{uuid}` 之前，否则被吃掉半截。

## 关键 trade-off

- **独立路径前缀 `listing-publish`** 而非 `listings/{id}/publish`：4.1.1.1 /
  4.1.1.2 已把 `listings/{uuid}/publish` 显式登记为「不放行」（归 4.3.1.1 /
  4.3.1.3）。批量发布是**队列**（批次 + 限流 + 顺序 + 重试），与「列表行」不是同一种
  资源，强行开在同一棵路径树上要同时改别人的白名单与拒绝清单。
- **`data_scope` 读批次自带的 `listing_ids` 快照而非任务行**：批次行先于任务行写入，
  若范围判定依赖「已存在的任务」，insert 批次那一步永远过不了 RLS。与 V177
  `listing_bulk_operation` 同一手法。
- **`publish_task_pending_pos` 用部分唯一索引而非整表 UNIQUE**：整表
  UNIQUE(tenant,batch,position) 会让「先派发掉位置 0，再重排剩下的」撞号 ——
  终态任务冻结了它当年的 position，而重排只改 PENDING 那些，两边必然重叠。
- **跨车道 DDL 回归面 = 0**：V183 纯新建三张表，只被 V1 `platform` / `shop` 与
  V171 `listing` / V177 `listing_content_draft` **只读**引用。

## 文件清单

**迁移**
- `backend/services/channel/src/main/resources/db/migration/V183__listing_publish_batch.sql`

**端点**（14 个，全部 `/channel/listing-publish/**`）
- `backend/services/channel/src/main/java/com/starcon/channel/PublishController.java`
- `backend/services/channel/src/main/java/com/starcon/channel/PublishModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/PublishService.java`

**页面**
- `apps/web/src/app/listings/publish/page.tsx`
- `apps/web/src/components/listing-publish/publish-model.ts`
- `apps/web/src/components/listing-publish/publish-workspace.tsx`

**白名单 / 契约**
- `apps/web/src/lib/api-proxy.ts`（新增一条独立 `services[]` 元素）
- `packages/api-contracts/src/channel.openapi.json` / `contracts/channel.ts`（codegen 产物）

**测试**
- `backend/services/channel/src/test/java/com/starcon/channel/PublishIntegrationTest.java`

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `./mvnw -q compile` | EXIT=0 |
| L0 类型 | `pnpm typecheck` | 3/3 成功 |
| L1 后端 | `./mvnw -pl services/channel -am test -Dtest='Publish*'` | Tests run: 14, Failures: 0, Errors: 0 |
| 白名单回归 | `pnpm vitest run apps/web/src/app/api/trade` | 40 files / 213 tests passed |

上游依赖自检：`listing`(V171)、`listing_content_draft`(V177)、
`channel_scope`/`channel_operation`(V1)、`platform`/`shop`(V1) 在本分支基线中
grep 均存在，未 BLOCKED。

## 本次接手实际修的东西

前端 3 处 typecheck 红（上一位 worker 被杀在跑 typecheck 之前，从未验证过）：

- `Button variant="primary"` → `"brand"`（本仓 Button 无 `primary` 取值）
- `Button size="icon-sm"` → `"icon"` ×2（同上）
- PageHeader 描述里「排队排队」的重复字

## 后续待办

- **4.3.1.1 一键刊登**、**4.3.1.3 发布任务** 未开发（同一 L3 锚点 `/listings/publish`）。
  本页失败明细只展示后端记录的原因，跳转 4.3.1.3 的目标缺失是预期状态。
- `capabilities` 里 **SPU 状态筛选** 与 **实际调用平台 API** 两个维度
  `available=false` + `ownerModule`：SPU 状态在 3 商品主数据侧、平台实际调用归
  4.3.1.3。页面显式提示未接入，不假装可用。
