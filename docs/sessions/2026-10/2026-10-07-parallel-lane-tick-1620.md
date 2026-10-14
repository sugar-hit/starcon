# 并行车道调度 16:20 tick

## 决策摘要

- **台账**：465 个 L4 模块 · 已交付 64 · 进行中 0 · 未开发 401
- **回收**：`merge-ready` 退出码 2 —— 3.8.2.1 在 `apps/web/src/lib/api-proxy.ts` 冲突，未并入。按调度纪律**不自解冲突**，已 abort 半合并状态并回报
- **派发**：2 条新车道（3.9.2.2、17.1.1.1），**故意不派发 plan 给出的 3.8.2.1**
- **资源**：10 核 / load 1.89 / Docker 11.7GB 限、当前容器实测约 1.3GB / 内存空闲 51%

## 关键发现与处置

### 1. 3.8.2.1 是**误重派**，成果早已完成

`plan` 把 3.8.2.1「导出」列入 toDispatch 并分配了新版本号 V38。但核实结果：

- 车道分支 `feat/3-8-2-1` tip `c5f7421` **成果完整**：`V35__product_export.sql`、
  `ExportController.java`、`SpuExportDataSource`/`SkuExportDataSource` 等 9 个后端类、
  `apps/web/src/app/products/export/page.tsx`、739 测试全绿
- 台账第 178 行仍为「未开发」，**原因不是没做，而是合并从未落地** → `plan` 读台账判定未交付 → 重派

若照plan 派发，会在已有 `V35__product_export.sql` 之外再建一套导出模块并占用 V38，
即「重复建端点 + Flyway 撞号」。**已跳过该车道**，并把 state 从 `running` 改回 `conflict`
（`running` 会伪装成有 worker 在心跳，实际没有），note 里写明「切勿重新派发」。

**这是自强化死循环**：合并不了 → 台账不更新 → plan 重派 → 重复建设 → 冲突更多。
打破点是解api-proxy 冲突，不是继续派发。

### 2. 冲突根因（供下一轮直接动手）

`apps/web/src/lib/api-proxy.ts` 第 191 行是一条数千字符的单行正则字面量，
双方都在 `export` 段尾部追加，git 无法逐块合并：

- 集成头 `integration/module-dev`：`export` `exports`
- 车道 `c5f7421`：`export-sources`、`export-jobs(?:\/<uuid>(?:\/(?:file|cancel))?)?`

**必须语义求并，两段都要**，不能二选一（少一段 = 该组端点前端 404）。
注意：车道文件里 `export-sources` 出现 2 次是因为**一行注释 + 一行真实正则**，
不是重复声明缺陷，别误判。

解完必须跑 `npx vitest run`，并确认 `app/api/catalog/[...path]/route.test.ts`
的通过数**从 739 上升**（新增 export 断言），而不是持平。
解完把 state 改回 `ready` 再 `merge-ready`。验证 UUID 必须用真 UUID 形状（8-4-4-4-12），
用模块 ID 会全部误报失败。

### 3. 17.1.1.1 worktree 陈旧检出，已修正

`plan` 复用 abandoned 残留 worktree，记录了正确 `baseSha=12d71b3`，
但实际检出点是 `7ef6372` —— **落后 64 个提交**（历史教训：落后 8 个就撞过 api-proxy）。
其 `api-proxy.ts` 只有裸 `export`，缺 `import-*` / `export-sources` / `export-jobs` 等。

满足无损修正条件（分支自身提交数 = 0、worktree 干净），已 `reset --hard 12d71b3`，现落后 0。

### 4. 迁移版本空间已核实

| 服务     | 现有最大 | 本轮分配        | 状态 |
| -------- | -------- | --------------- | ---- |
| catalog  | V34      | V39（3.9.2.2）  | 空闲 |
| identity | V8       | V40（17.1.1.1） | 空闲 |

### 5. `merge-ready` 失败后必须先 abort

退出码 2 时 `MERGE_HEAD` 仍在、`UU` 文件还在，卡住主工作区与下一 tick 全部操作。
**顺序是先 abort 清干净，再写日志并提交** —— `merge --abort` 会把当时已 staged 未提交的改动一起回退。

## 任务书里加进去的防线

针对本轮观察到的反复踩坑，在两条任务书里显式写了：

- api-proxy.ts 追加端点必须是**完整顶层分支、自带前导 `|`**，且改完必须跑路由测试
- codegen 前**必须先刷新 openapi 快照**（`target/` 是构建产物，陈旧快照会把新字段退回旧字段 → 前端 TS2339）
- vitest 不做类型检查，`pnpm test` 与 `pnpm typecheck` 两条都要跑
- 台账证据单元格**禁止裸 `|`**（sync.mjs 会在竖线处截断证据）
- 每完成一个增量就 commit，别攒到最后（gc 会回收 worktree）
- `land` 之后禁止再改文件

另外针对两个模块各自的架构红线：

- **3.9.2.2**「从未被订单引用」**不允许跨库 join trade 表** —— 改为 catalog 本地引用统计快照表，
  由 trade/warehouse 经event 或授权 API 推送。缺事件源的维度明确说明或报 BLOCKED，不许假数据糊
- **17.1.1.1** 租户集合**只能由已认证用户的 membership 推导**，不信任 `X-Tenant-Id`；
  额外要求断言「无 membership 用户查不到任何租户」「伪造 header 不能扩大可见范围」

## 车道状态

运行中：3.9.1.1（心跳 4min前）、3.9.2.1（心跳 15min前）、3.9.2.2、17.1.1.1
待处理：3.8.2.1（conflict，成果在 `c5f7421`，等解 api-proxy 冲突）

## 后续待办

1. 下一轮**优先解 3.8.2.1 的 api-proxy 冲突**，解完改回 `ready` 再 merge-ready，
   合并成功后跑 sync 让台账第 178 行落「已交付」，从根上断掉误重派循环
2. api-proxy.ts 的根治改造（仍未做）：把白名单从单行正则重构成多行数组或独立常量，
   一次性解决，并行车道不再在此冲突
3. 每轮 plan 后核对每个 newlyPlanned 项在集成头是否已有代码，防陈旧台账 → 重排已交付模块
4. 资源评估需在 `./mvnw verify` 峰值时采样，当前 1.3GB 是派发时刻快照，不能据此判断能否加到 8

## 文件清单

- 本文件：`docs/sessions/2026-10/2026-10-07-parallel-lane-tick-1620.md`
- 未改动任何业务代码；工作区除本文件外保持干净
