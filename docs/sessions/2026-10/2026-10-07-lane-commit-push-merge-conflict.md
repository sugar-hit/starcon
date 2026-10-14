# 会话：并行车道提交、推送与并入冲突收口

日期：2026-10-07 21:04–21:45 · 集成分支 `integration/module-dev`

## 做了什么

1. **提交**：7 条车道（7.7.1.1 / 7.7.1.2 / 7.8.1.1 / 8.1.1.1 / 8.3.1.1 / 8.3.4.2 / 8.5.1.1）各有一份成型但未提交的交付物，逐车道一个 atomic commit 落盘；worker 期间又追加的零散改动分两次补提交。
2. **推送**：远端此前只有 `main`，`integration/module-dev` 从未推过。首次推送失败于 `http.postBuffer`（`unable to rewind rpc post data`），调大到 500MB 后成功。最终远端 78 个分支，本地除 `main`（按规范不直推）全部同步。
3. **并入**：并发 tick 在本会话期间自行完成了 8 条车道的 merge-ready；本会话负责验收并修掉并入结果里的缺陷。

## 关键 trade-off

- **未等验证就提交在途代码**：这些车道是 worker 写到一半的，从未跑过 typecheck。好处是不丢工作量，代价是并入后集成分支一度编译不过（17 处类型错误）。判断依据是「无占位/mock 残留 + 有迁移/服务/端点/测试四件齐全」，事后证明这份交付是完整的、只是没验证。
- **丢弃被掏空的 openapi 产物**：主工作区里三个 `*.openapi.json` 变成 1210/1582/2422 行（原本 39669/8004/3010），是有人用只构建了部分服务的 `backend/target/openapi` 重跑 codegen 的结果。已 `git checkout` 还原 —— 提交它等于删掉几百条契约。
- **`AllocationOutcome` 采纳对方解法**：我先把它当悬空再导出删掉了；fix 车道的解法是 `AllocationLine['outcome']` 反推（codegen 把 Java 枚举内联成字符串联合），更稳且不与后端漂移，合并时以 theirs 为准。

## 并入后发现并修掉的真实缺陷（都不是 git 报出来的）

| 缺陷                                                                                | 后果                                                                         |
| ----------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `inventory/allocation-policies` 分支的 uuid 支自带前导 `\/`，外层组又消费了那个斜杠 | `/{id}`、`/{id}/retire` 永远匹配不上                                         |
| 同分支外层组缺 `?`                                                                  | 裸列表与新增（前端 POST 裸路径）被拒 → 新增策略浏览器端 404                  |
| `inventory/serial-movements` / `serial-operations` 从未登记                         | 后端早已实现（`InventorySerialController`），前端在调 → 页面 404（既有缺陷） |
| `RuleEditor` prop 类型声明 `superseding` 但解构漏了                                 | 换版预填 6 处引用取不到值                                                    |
| 作废入口引用不存在的 `onRetire`                                                     | 只有类型错误，没有运行时表现                                                 |
| `variant="primary"` 不是 Button 支持的取值（全仓唯一一处）                          | typecheck 红                                                                 |

**白名单只在浏览器端生效，`pnpm test` 直连服务层永远测不出来漏登记。** 判断依据必须是「从前端源码反向抽取调用点」，逐条断言应放行的放行。

## 求并方法（`apps/web/src/lib/api-proxy.ts`，2069 字符单行正则）

三类文件里只有它需要人工求并（契约与台账有 `merge.contract-union` 驱动自动按键求并）。

- **必须深度感知**：`inventory\/(?:…)` 大组内部还嵌套 `(?:…)`，`split('|')` 会切出 `retire))?)?` 这类碎片。
- **禁止字符串切片**：第一版按「组闭合括号位置」切片，忘了外层 `^(...)` 的闭括号也在切片尾巴上，多拼一个 `)`、留下一个空分支 —— 括号仍配平（`assertBalanced` 放过）但正则放宽到连裸 uuid 都放行。**配平 ≠ 语义正确**。改为「解析成结构 → 整体重建」。
- **禁止 theirs 优先**：7.6.2.1 从 7.4.2.2 之前分叉，它那份白名单没有 `stocktake-variances` / `cross-entity-transfers` / `facilities`，取 theirs 等于静默删掉三个已交付模块的路由。
- **校验器自己也会骗人**：把 `^(A|B|C)$` 拆成 `^A|B|C$` 再测，交替会让 `$` 只绑到最后一支，`^facilities` 变成前缀匹配，制造出一批**假的「越界放行」**。判定口径必须是 `^(?:BODY)$`。
- **放置位置看前端真实调用**：同一批车道里 6 条走 `warehouse/inventory/…`（放组内）、2 条走 `warehouse/…`（放组外），放错就是页面 404。

## 验证结果（最终态）

| 项                                                           | 结果                                          |
| ------------------------------------------------------------ | --------------------------------------------- |
| `pnpm typecheck`                                             | 0 error                                       |
| `pnpm lint`                                                  | 3/3（仅 react-hooks 警告）                    |
| `pnpm test`                                                  | 77 文件 / 1353 用例全过                       |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 通过                                          |
| `cd backend && ./mvnw verify`                                | 通过（23 个迁移到 V74，Testcontainers PG）    |
| `node tools/module-progress/sync.mjs --check`                | 台账与蓝图一致                                |
| 白名单反向校验                                               | 48 条前端调用点全覆盖、9 条拒绝清单路径仍 404 |

## 后续待办

1. **并发写主工作区**：本会话有 3 次未提交的编辑被并发 tick 冲掉（契约重生成、merge-checkout）。共享工作区上累积多个 commit 的编辑风险很高 —— 要么单次编辑后立即提交，要么把修复放到独立分支的 worktree 里。
2. **`mvnw verify` 的完整 spec 要及时用于 codegen**：本次契约是在 7.6.1.1 合并前生成的，靠另一个流程补齐。若只构建部分服务就重生成，会把契约掏空。
3. `inventory/health` 的 `trend${qs}` 这类「路径 + 查询串」形状，反向抽取调用点的脚本要能识别，否则会一直误报。
4. 7.6.1.1（库龄与呆滞）、7.7.1.1、7.8.1.1 的 fix 分支已并入并 gc；两个 cron tick 仍在运行，00:00 停机协议未变。
