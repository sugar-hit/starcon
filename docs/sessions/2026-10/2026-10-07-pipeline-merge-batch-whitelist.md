# 2026-10-07 流水线：回收 9 条车道 + 修掉 api-proxy 白名单的系统性缺陷

调度者会话（无业务代码产出，除白名单与台账外全部由 worker 车道交付）。

## 结论

台账从 **92 已交付 → 100 已交付**（完成率 19.8% → 21.5%）。回收并入 9 条车道，
清掉 8 条 conflict 残留，worktree 全清。后端 `verify` BUILD SUCCESS（warehouse 633 例），
前端 77 文件 1353 例全绿，typecheck 零错误，lint 无新增。

## 本轮并入的车道

| 模块    | 名称           | 迁移 | 状态说明                                        |
| ------- | -------------- | ---- | ----------------------------------------------- |
| 7.6.2.1 | 库存健康       | V66  | 正常并入                                        |
| 7.7.1.1 | 货主与所有权   | V67  | 并入后 typecheck 不通过，修复后定案             |
| 7.8.1.1 | 分配策略配置   | V68  | 并入后 typecheck 不通过，修复后定案             |
| 7.7.1.2 | 寄售与代销     | V71  | 正常并入                                        |
| 8.3.1.1 | 出库单列表     | V72  | 正常并入                                        |
| 8.3.4.2 | 面单与标签打印 | V73  | 正常并入                                        |
| 8.5.1.1 | 作业效率       | V74  | 正常并入                                        |
| 8.2.1.1 | 收货单         | V70  | 正常并入                                        |
| 7.6.1.1 | 库龄与呆滞     | V65  | worker 断线，调度器代为提交并入                 |
| 8.1.1.1 | 仓库列表       | V69  | 上轮已并入但标记残留，本轮由 reconcile 自动补记 |

## 关键 trade-off

### 1. `api-proxy` 白名单是今天唯一的反复冲突点，本轮换了打法

前七次都是「人工解冲突 → 眼验正则」，本轮改为**括号深度感知的程序化求并集**：

- 以 ours（集成头）为骨架，只把 theirs 缺失的**内层 token** 插进对应的组，不整体拼接；
- 顶部 alternation 在 depth===1 处切分（index 1 的 `(` 是外层组，depth 初值必须是 1，否则切出来的是内层 token）；
- 每次求解后跑固定语料三方对比：ours 接受而并集拒绝 = 回归、theirs 接受而并集拒绝 = 漏、
  并集接受而两侧都不接受 = 过度放行，任一非空即拒写；
- 冲突块**不止一个**：本轮实测同一次 merge 产生 2 个块（一个注释块 + 一个 pattern 块），
  resolver 必须能分别处理 —— 注释块取两侧并集去重，pattern 块求并集。

**踩过的坑（都写进了护栏）**：

- 解算器第一版从 index 2 起算 depth=0，漏掉外层 `(`，把 18 条内层 token 当成顶层分支，
  结果括号不平衡。**括号平衡自检在写入前拦住了它**，否则就写坏文件了。
- 断言脚本把正则字面量切了两刀，第二次把 `^`/`$` 锚点也切掉，于是
  `inventory/health/bogus` 因为「包含 `inventory/health` 子串」被判为放行。
  **验证 harness 本身之前，先拿未改动的原件跑一条已知必放行的用例。**
- 某次 resolve 真的把一段注释文字拼成了假正则写进文件（`/^(ownership 是 7.7.1.1 …)$/`）。
  当时没被拦下，是因为后续断言从 `prefix: 'warehouse/'` 之后开始找 pattern，**跳过了**
  上一行那个损坏的字面量。**断言脚本必须锁定「全文件只有一条 warehouse pattern 行」。**

### 2. 契约在并集时丢失了彼此的类型 —— 这是本轮最大的隐性缺陷

7 条车道各自在自己的分支上跑 codegen，各自生成 `packages/api-contracts/src/catalog.ts`。
并集时 OpenAPI JSON 只取到一侧的版本，于是前端大面积报
`has no exported member 'OwnershipXxx'` / `'ConsignmentAgreement'` …… **typecheck 65 处错误**。

正确修法不是手改（codegen 产物禁止手改），而是**从合并后的 Spring spec 重新生成**：

```bash
cd backend && ./mvnw verify          # springdoc 产出 backend/target/openapi/*.openapi.json
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
```

`--spec-dir` 是关键：不带它脚本会尝试启动 Spring Boot 抓 `/v3/api-docs`，
本地没起服务就 `Connection refused`。重新生成后 998 个 DTO，typecheck 降到 29 处。

### 3. codegen 把 Java 枚举内联成字面量联合，不生成具名类型

剩下的 29 处里有一类根因值得单独记：`AllocationOutcome`、`StockOwnerKind`、`OwnerRefType`
在后端 Java 里都是**真实枚举**，但 codegen 只把它们内联成行类型上的字符串联合，
**不会生成具名 type alias**。所以前端 `import type { AllocationOutcome }` 必然编译不过。

同一问题出现两轮，两次都出现了**两套解法并存**：

- 集成头上另一写者选了「前端本地声明字面量联合」；
- worker 选了「从契约行类型反推」`NonNullable<StockOwnerRow>['ownerKind']`。

**采纳 worker 的**：本地抄一份会随后端枚举增删**静默漂移** —— 后端加了枚举值，
前端类型不变、编译照过、运行时才炸。反推版本契约仍是唯一来源。
`scopePriority` 同理改 `as const`，从根上消掉 `noUncheckedIndexedAccess` 带给测试的 `| undefined`。

### 4. 白名单里 serials / lots 的明细子路径一个都没登记

并入 7 条车道后做全量复核（从前端源码反向抽取真实调用点逐条断言），
发现**已交付**的序列号与批次两个模块只登记了集合读，而页面实际在调
`serials/stats`、`serials/{id}`、`serials/{id}/movements`、`lots/{id}`、
`lots/{id}/distribution`、`lots/{id}/movements` —— 后端 `InventorySerialController` /
`InventoryLotController` 这些端点**都真实存在**（13 个 / 7 个 mapping）。

**这类漏登记 `pnpm test` 天然测不出来**：白名单只在浏览器端生效，单测直连服务层。
已补登记，并把 11 条路径加进 `route.test.ts` 的放行清单 + 注释说明成因，
让下次再漏时 CI 能红给人看。

### 5. 7.6.1.1 的 worker 断线，15 个文件悬空未提交

worker 在排查 catalog 的 503 时 `Connection error` 中断，worktree 里 15 个文件全部未提交、
0 提交。处置：**先取证再决定救不救** —— 实跑
`./mvnw -pl services/warehouse -Dtest='InventoryAging*'` 得 23 例全绿、BUILD SUCCESS，
确认成果是真的，才由调度器代为提交 + `land` + 并入。
（第一次跑加了 `-am`，把 `tenancy` 也拉进来，它没有匹配测试直接失败 ——
**`-Dtest` 与 `-am` 同用必须补 `-Dsurefire.failIfNoSpecifiedTests=false`**。）

### 6. 编排器自身：reconcile 的补记会在早退路径上丢失

`lane.mjs merge-ready` 的 conflict 补记（车道分支已是集成分支祖先 ⇒ 补记 merged）
在**「没有待并入的车道」早退路径**上要显式 `writeState()`，否则修正整个丢掉。
本轮又暴露**第二条丢失路径**：脏工作区检查 `process.exit(1)` 发生在 `writeState()` 之前。
症状是「8.1.1.1 明明已并入，状态却还是 conflict」—— 清掉脏文件后重跑即自动补记。
**所有在 `writeState()` 之前 `exit` 的分支都是同一个坑。**

### 7. 调度闸：`gate status` 不判 TTL，只有 `acquire` 判

撞闸时 `ageSeconds` 已经 621s 却仍报 `held` —— 因为 `status` 只看目录存在性。
`acquire` 才做 600s 过期接管。**判据是 `acquire` 的退出码，不是 `status` 的输出。**

### 8. worker 会改错工作目录

两个 worker 起初都在**主工作区**（集成分支的目录）写代码，而不是分给自己的 worktree。
主工作区同时是并入通道，直接提交会绕过并入与冲突解算。
已用 `task_append` 纠正并要求把改动搬回 worktree。两人都照做了，
且都**拒绝**顺手还原别人的在途改动（其中一人还发现导出 patch 时主工作区仍在被写入，
实际导出 5 个 hunk 而非看到的 3 个，并剔除了混进来的 2 个）—— 这点做得对。

## 文件清单

- `apps/web/src/lib/api-proxy.ts` —— 白名单并集 8 次 + serials/lots 补登记 + 注释
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts` —— 新增 11 条放行断言
- `packages/api-contracts/src/catalog.ts` —— 两次重新生成，998 → 1011 个 DTO
- `docs/product/capability-blueprint/module-progress.md` —— 台账（恒 465 行、无重复）
- `docs/sessions/2026-10/2026-10-07-fix-7-7-1-1-typecheck.md`（worker）
- `docs/sessions/2026-10/2026-10-07-fix-7-8-1-1-typecheck.md`（worker）

## 后续待办

1. **并发仍无全局容量闸**：`lane.mjs plan` 没有上限，两个 cron 任务
   （`0,15,30,45` 与 `7,22,37,52`）各自 `plan --lanes 4`，各自假设对方不加。
   本轮实测曾到 12 条车道 / load 50（10 核）。孤儿判据已修
   （`laneIsStale(l) || laneIsUnclaimed(l)`），但容量闸仍需在**两个** prompt 里同时加，
   只改一边等于没改。
2. **`allocation-policy-model.test.ts` 有 1 处既有失败**（`notNull` 是类型谓词，
   测试却按「归一化函数」断言返回 null）。worker 已 stash 对比确认是基线就有的，
   未擅改 —— 需该车道 owner 定夺是改断言还是改实现。
3. **codegen 建议统一为具名枚举别名**，否则每条涉及枚举的车道都要各自反推一遍。
4. **`api-proxy.ts` 仍无自动化防漏机制**：本轮是手工写了扫描脚本（从前端源码反向抽取调用点）
   才找到 serials/lots 的缺口。**建议把这个扫描器落成仓库内的测试**，
   否则下一批车道仍要靠人工在合并现场发现。
5. 7.5.1.2 在途库存仍是「进行中」：只交付了后端，前端页面与契约未做。

## 追加（22:00 后）：两次接管半路撂下的合并 + 编排器根因修复

### 8. `merge-ready` 冲突后不 abort —— 一晚被撂了两次

22:00 后另一 cron 实例连续两次**释放了调度闸，却把合并撂在冲突上**：
`MERGE_HEAD` 残留、`api-proxy.ts` 冲突未解、闸已空、主工作区脏。

后果是双重堵死：下一个 tick 的 `merge-ready` 命中「主工作区不干净，拒绝切分支」
直接 `exit(1)`，并入通道整个卡住；而同一个循环里紧接着的那条车道会在**同一棵冲突树**
上再 merge，报出与它自身毫无关系的级联冲突 —— 看起来像两条车道撞了同一个文件，
实际只有第一条真冲突。今晚我一度以为 8.1.1.2 和 7.8.1.2 都撞了 `api-proxy.ts`，
真相是只有前者真冲突。

根因在 `tools/module-progress/lane.mjs:776-787`：`git merge` 返回非零时只把车道标成
`conflict` 就 `continue`，**没有 `git merge --abort`**。

修复：冲突分支补 `git merge --abort`。新增回归测试
`tools/module-progress/lane-merge-abort.itest.mjs` 断言两条不变式：
① 合并失败后没有 `MERGE_HEAD`、没有冲突标记、工作区干净；
② 真冲突的车道标 `conflict`，而**与之无关的车道仍能正常并入 merged**。

已验证该测试能抓住这个 bug：把 abort 那行去掉后两条断言全红。
为让整条流水线可在一次性仓库里端到端测试，`ROOT` 增加 `LANE_ROOT` 环境变量覆盖
（不设时行为完全不变）。

### 9. 并集解算器第三次证明了「先验 harness」的价值

并入 7.8.1.2 后我写了两条断言，结果**两条都红了**：
`inventory/allocation-priority/{id}/versions` 与 `inventory/labor/ranking`。
查证后发现**两条都是我的探针写错了**，不是合并缺陷：

- 真实形状是 `allocation-priority/floors/{id}/versions`（已放行），我探针少写了一段；
- `labor` 在白名单里位于**顶层**而非 `inventory/` 组内，因为前端调的是
  `warehouse/labor/ranking`（不带 `inventory/` 前缀）—— 与 `goods-receipts`/`facilities` 同构。

这是今晚第三次「红的是探针不是代码」。如果当时直接改白名单，就会把两个正确放置的
分支挪错位置 —— 而 `pnpm test` 未必抓得住。

### 10. 全量 `./mvnw verify` 在并发负载下不可靠（不是缺陷，是 flake）

本轮两次全量 verify 各红一次，但**失败集合完全落在没被本轮改动碰过的 catalog 上**：

- `GoodsReceiptIntegrationTest` 29 例 → `PSQLException: 进行 SSL 连线时发生错误`
  （Testcontainers 的 PG 在并发下握手失败），隔离重跑 29 例全绿；
- `BundleComponentIntegrationTest` / `PhysicalSafetyIntegrationTest` 7 例 → 全部 `503`
  （期望 200/401/403/404，实得 503），`ZombieCandidateIntegrationTest` 1 例
  → timestamptz 往返 `.334` vs `.335` 差 1 毫秒。隔离重跑 78 例全绿。

**结论如实记录**：集成头功能上是绿的，但「有 4 条车道在跑时跑全量 verify」会稳定地
产生假红。CI 若在并发下跑同一套测试，需要么降低并发、么把这两类隔离/重跑。
那个毫秒级往返尤其值得注意 —— 它是**真会抖**的，不是纯粹的环境问题。
