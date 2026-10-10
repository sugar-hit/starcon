# 6.4.1.1 加工单 —— 对齐集成头、真跑验证、收口

日期：2026-10-10
分支：`feat/6-4-1-1`
车道：6.4.1.1（阶段 D6，L3 锚点 6.4.1）

## 一句话

接手一条挂起两天、**5 个 commit 从未验证过**的车道（末个 commit 标题自己写着「未验证」），
对齐三个模块的新集成头、真跑全量门禁（后端 2720 例 + 前端 2057 例全绿）、
修掉 merge 驱动器造成的台账损坏后收口。**模块本身不需要改代码**——
它的形状本来就是对的，坏的只有集成。

## 决策摘要

### 1. 模块边界：库存动作落在 warehouse 一个本地事务里，trade 不碰库存

蓝图硬要求是「组件出库与成品入库**必须同事务**，一个失败要整体回滚」。
核对实现，形状正确且不需要改动：

* trade 侧 `ProcessingOrderController`（`/trade/processing/**`）只建单据与登记领用进度，
  全文**没有任何库存表 UPDATE**，只把 warehouse 回填的 `inventory_transaction_ref` 记进
  `processing_inbound_request`（`ProcessingOrderService.java:445,555,604`）。
* 真正扣料入库的端点是 `warehouse` 侧
  `POST /warehouse/processing-orders/issues`（`ProcessingOrderController.java:75`），
  实现 `ProcessingOrderService.issue(...)` 带 `@Transactional`（`ProcessingOrderService.java:102`），
  组件出库与成品入库写在**同一个方法体**里。
* 事务内顺序刻意是「先出料、后入货」：组件不足就抛异常回滚，此时成品**还没入账**，
  账上不会出现凭空多出来的成品（源码注释与实现一致）。
* 幂等锚点：同一张加工单重复执行被 UNIQUE 挡住并返回 409，
  不会「重复扣料重复入货」。

跨服务纪律也守住了：无跨库 FK、无跨服务视图、trade 不直连 warehouse 的表。

### 2. 合并冲突：求并而不是二选一，四条正则逐条核对，零丢失

用 `--no-commit` 合并（不用 `git commit -F`，避免把 `MERGE_HEAD` 连同单 parent 一起吞掉）。
`api-proxy.ts` 走的是普通三方合并（merge 驱动器只覆盖契约与台账），未产生冲突标记。

写脚本 `.tmp/verify-proxy.mjs` 解析**每一层** alternation，做**与下标无关**的分支集合差，
再跑一遍行为匹配：

| | mine | integration | merged |
| --- | --- | --- | --- |
| 顶层 alternation 分支数 | 519 | 533 | **545** |

* 丢失分支：**mine 0 条、integration 0 条**。
* 四条正则（identity / catalog / channel / warehouse）加上 trade 全部逐条比对通过。
* 两层 alternation 都并到了：外层仓库级段与内层 `inventory/` 组都做了集合比对，
  只并内层会静默丢掉外层分支这种坑没有发生。

脚本第一版把 `|` 只在根层切分，得到「所有分组分支数都是 1」的假绿；
改成在**当前栈帧层**切分后才暴露出真实的合并差异。这是本次唯一一次自证工具出错，
记录下来给下一条车道：**比对前先确认解析器能看见分支**。

### 3. 集成头的既有缺陷：故意没动

`translation-coveragetranslations` 少一个 `|`，使 3.4.2.2 已交付的 translations 浏览器端 404。
核对合并结果确认该缺陷**原样保留**（`grep` 在 merged 与 integration 两版上均命中同一串），
按 backlog B-01 不属本车道，未顺手改。

### 4. merge 驱动器把台账写坏了，用 sync.mjs 重新归一

`merge.contract-union` 驱动器对台账产生了可见损坏：删掉表格间空行、
汇总数字停留在旧值、并在文件**末尾追加了 11 行游离汇总行**（重复的
`分系统进度` 表碎片与阶段统计残行）。

对策不是手改，而是回到权威来源重算：`node tools/module-progress/sync.mjs`
按蓝图树重新生成整张台账。结果 559 行、483 个模块行，
`sync.mjs --check` 通过，且按模块 ID 求差集确认**相对两侧都零丢失、无重复行**。

本车道自己的 6.4.1.1 行**一个字符都没改**（`git diff` 中该行变更数为 0）——
它的状态与证据在抢救提交里已经写全，收口只需要确认它站得住。

## 关键 trade-off

* **不重跑前端门禁来「证明」合并无害**：白名单回归由 106 例 `api-proxy-union.test.ts` +
  38 例真 proxy HTTP 断言 + 我自己的分支集合差三方交叉保证，typecheck/lint 全绿
  本身证明不了白名单没丢分支（这正是简报里点名的失败模式）。
* **契约重新生成但类型集合没变**：`catalog.ts` 重新生成后 diff 是 65 增 65 删，
  逐条比对确认是**纯字母序重排**，1398 个导出类型前后完全一致，无类型丢失。
  没有手改任何契约产物。

## 交付物位置

| 类别 | 位置 |
| --- | --- |
| 迁移 | `backend/services/trade/src/main/resources/db/migration/V97__trade_processing_order.sql`（五张表，全部 tenant_id NOT NULL + ENABLE/FORCE RLS） |
| 端点 | trade `ProcessingOrderController`（12 个，0 个 DELETE）+ warehouse `ProcessingOrderController`（4 个） |
| 页面 | `apps/web/src/app/purchase/processing/page.tsx` + `components/purchase/processing-order-{model,workspace}.ts(x)` |
| 测试 | trade `ProcessingOrderIntegrationTest` 27 例 + warehouse 16 例 |
| 会话日志 | 本文件 |

## 验证输出（真实数字）

* `./mvnw verify` → **BUILD SUCCESS**，7 个模块全 SUCCESS，06:47。
  测试合计 **2720 例，failures=0 errors=0 skipped=0**
  （identity 236 / catalog 1421 / channel 75 / warehouse 924 / trade 64）。
* `pnpm typecheck` → 3/3 successful；另跑 `npx tsc --noEmit` 强制绕开 turbo 缓存，退出码 0。
* `pnpm lint` → 3/3 successful（仅 catalog / warehouse 既有文件的 useMemo warning）。
* `pnpm test` → **104 文件 / 2057 例全绿**。
* `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 成功，
  `/purchase/processing` 出现在路由表里（9.49 kB，Static 预渲染）。
* `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致。

`/purchase/processing` 路由与白名单逐条对得上：前端实际发出的 9 个形状
（capabilities / requirements / orders / orders/{id} / status / progress /
components/issue / factories / factories/{id}）全部命中白名单，
且 `components/issue` 这条三段形状没有被单段 `{id}` 分支吃掉半截。
`DELETE` 与更深子路径确认被挡住。

## 后续待办

* 8.4.1.1 组装单未交付：capabilities 已显式 `available=false` 并写明 ownerModule。
* 6.4.1.2 BOM 关联未交付：效果上已被「下达时冻结组件清单」覆盖，仍欠一个显式模块。
* backlog B-01（`translation-coveragetranslations` 缺 `|`）仍挂在集成头，本车道未动。
