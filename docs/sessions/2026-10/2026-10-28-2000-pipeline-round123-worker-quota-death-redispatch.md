# 2026-10-28: 并行车道第 123 轮 —— worker 额度耗尽致双车道死亡，重派 2 条 + identity 红点修复继续

## 摘要

本轮是**降档模式**（已过 `TOKEN_HORIZON=2026-10-25T00:00:00+0800`）下的组批派发轮：
起点台账 **423/465** 已交付。本轮**派发 2 条车道**（13.4.2.1 成本结构 / identity L2 红点修复），
**两条都在本轮内落地并入**，台账 **423 → 424**（未开发 42 → 41）。

本轮最重要的发现不是进度，而是三件事：

1. **上一轮派发的两条车道都死于同一原因**：两个 worker 会话在 **19:03 前后被 Token Plan 用量上限强杀**
   （`已达到 Token Plan 用量上限 (2056)`），留下「一条写了一半代码、一条什么都没写」的半成品状态。
   ⇒ 本轮把两条车道**按 orphan 机制正常重派**，并把「接手半成品」写进任务书的第一节 ——
   上一轮 13.4.2.1 已经写完 2400 行后端 + 1032 行测试 + 1100 行前端，**只差验证和 commit**，
   这些成果**全部还在 worktree 里**，没有丢。
2. 🔴 **`identity` 那个长期红点的根因被推翻了**：第 122 轮判定的「跨测试状态污染」**是错的**。
   真实根因是**跨钟域判定**：`role_assignment.effective_from` 写入侧用**应用服务器墙钟**，
   而授权判定用**数据库 `now()`** ⇒ 刚写好的授权被判「尚未生效」⇒ **403**。
   已修（详见 batch-109），**这是真生产缺陷，不是测试噪音**。
3. **本轮是第一次让全量 L2 真正跑完 identity 之后的 8 个模块** ——
   历史记录显示上一次全量 verify 在 **identity [3/11] 就 fail-fast 中止**了，
   catalog / channel / warehouse / trade / crm / finance / reporting / platform **从未在一次完整全量跑里被验证过**。

本轮交付 **2 个模块**：13.4.2.1（batch-108）+ identity L2 红点修复（batch-109）。

## 档位与闸门判定

| 项                   | 取值                                       | 判定                       |
| -------------------- | ------------------------------------------ | -------------------------- |
| 强度档位             | `TOKEN_HORIZON` 已过 → **reduced**         | `LANES=4/2=2`、`节流闸=100` |
| 静默窗口             | `SILENT_WINDOW=0`（全天运行）              | 未命中                     |
| 批次节流闸           | 距上批 **104 分钟** ≥ 阈值 **100**          | ✅ 放行（通过后才写时间戳） |
| 温度闸 `pmset -g therm` | `No thermal warning level has been recorded` | ✅ 通过（无降频项）   |
| 配额闸               | running **2** / 配额 **6**                  | ✅ 通过                     |
| PG 闸                | 带 `org.testcontainers.sessionId` 标签 **0** / 上限 6（无孤儿容器） | ✅ 通过 |
| load 闸              | 1min **3.55** / 5min 2.52 / 15min 2.79，阈值 12 | ✅ 通过             |
| 调度闸               | `gate=free` → `acquire` 成功（TTL 4200s）    | ✅ 独占整轮                 |

## 进度（`sync.mjs` 实时输出，非手工计数）

- 总数 **465** · 已交付 **423**（91.0%）· 进行中 **0** · 未开发 **42**
- 分阶段：D7 已交付 116 / 未开发 **39**；D6 已交付 75 / 未开发 **3**；D1–D2 80/0、D5 52/0、D3 40/0、D4 60/0
- D6 的 3 个未开发模块：`5.1.1.1` 价目表列表 / `5.1.1.2` 价目项批量维护 / `5.1.2.1` 优先级与冲突检测
- 距 465 还差 **42** 个

## 本批回收

**第一次 `merge-ready` 返回空**（派发时无车道落地）：`{"ok": true, "merged": [], "note": "没有待并入的车道"}`。

**13.4.2.1 在本轮内落地后再次 `merge-ready`** ⇒ 并入成功、无冲突：

```
lane.mjs merge-ready → {"lane": "13.4.2.1", "from": "9613075a", "to": "c6bfc32f"}
sync.mjs            → 已交付 423 → 424，未开发 42 → 41
```

台账完整性复核：`grep -c` = **465**，模块 ID **无重复**。工作区干净，无 `UU` 冲突文件。

## 🔴 本轮主要发现：两条在途车道都是被**额度耗尽**杀死的，不是代码问题

`mavis session list` 实测（20:03）：

| 会话 | 角色 | 状态 |
| ---- | ---- | ---- |
| `mvs_7cb9160e7252420aabb24aa88693c0c8` | worker · Lane 13.4.2.1 成本结构 | **error** `已达到 Token Plan 用量上限 (2056)` @19:03 |
| `mvs_2314269938d3405d8daba17d97e3cc2a` | worker · Fix identity L2 red 403 | **error** `已达到 Token Plan 用量上限 (2056)` @19:03 |
| `mvs_08b68ecb1fa74510b19a5c2ba2a3fdf5` | mavis · 第 122 轮调度者 | **error** 同上 @19:03 |
| `mvs_9dfcf35307fd4b18b87a112937bbd42b` | mavis · main→集成头同步 | **error** 同上 @19:03 |

⇒ **同一时刻、同一错误** ⇒ 是**额度侧的统一熔断**，不是 worker 自身崩溃。
第 122 轮调度者本身也死于同一原因 —— 这解释了为什么第 122 轮的「追加动作」只做了一半。

**车道状态里的 `sessionId` 恒为 `null`**（`plan` 不带 `--session`），所以 `lane.mjs` 的孤儿判据
在 60 分钟宽限后就判它们为 orphan（`laneIsUnclaimed`）⇒ 本轮 `plan` 把两条车道列入
`toDispatch` 并 `reDispatched: 1`。**这次判定是对的** —— worker 确实死了。

### 13.4.2.1 的半成品（**没丢，已写进接手任务书**）

| 类别 | 文件 | 规模 |
| ---- | ---- | ---- |
| 迁移 | `V399__cost_structure.sql` | 232 行 |
| 后端 | `ReportingCostStructure{Controller,Models,Port,RefreshService,Service}.java` | 303 / 247 / 144 / 607 / 1112 行 |
| 测试 | `CostStructureIntegrationTest` / `CostStructureTenancyIntegrationTest` | 699 / 333 行 |
| 前端 | `cost-structure/{page.tsx}` + `cost-structure-{model.ts,model.test.ts,workspace.tsx}` | 1836B / 321 / 268 / 513 行 |

⇒ **任务书第一节就是「摸清现状 + 跑 L0 编译闸看哪里断了」**，并写死三条：
不要 `git reset --hard`、不要删掉重来、建议分两个 commit（先 `feat(reporting):` 收拢主体，
再 `fix(reporting):` 补齐接手后修的部分）。

## 本轮派发（2 条，reduced 档）

`lane.mjs plan --lanes 2` → `toDispatch` 2 条、`newlyPlanned` **空**、`serviceBlocked` **空**。
`newlyPlanned` 为空是因为 **2 个名额全被 orphan 重派占满**（`pickModules(count - orphaned.length)` = 0），
**不是**服务缺口或上游未就绪 —— 这是脚本的正确行为，本轮不额外排新车道。

| 模块 ID            | 名称       | 阶段    | L3 锚点 | 分支                              | 迁移版本 | 归属服务    | 状态       |
| ------------------ | ---------- | ------- | ------- | --------------------------------- | -------- | ----------- | ---------- |
| `13.4.2.1`         | 成本结构   | D7      | 13.4.2  | `feat/13-4-2-1`                   | **V399** | `reporting` | 接手半成品 |
| `fix-identity-l2-red` | identity L2 红点修复（全套 639 例 38 个 403 —— 跨测试状态污染） | L2-FIX | — | `fix/identity-l2-red-403-pollution` | — | `identity` | 从取证接手 |

任务书落盘：
`.dev-agent/taskbooks/r123-13.4.2.1.md`（12.4KB） ·
`.dev-agent/taskbooks/r123-fix-identity-l2-red.md`（11.3KB）

✅ **派发前逐字检查过：任务书里没有残留 `<WORKTREE>` / `<BRANCH>` / `<INTEGRATION_ROOT>` /
`<模块ID>` / `<MIGRATION_VERSION>` / `<BLUEPRINT>` 等占位符**（全角尖括号仅剩 `<200>/<403>` 这类
HTTP 状态码字面量、`<嫌疑类>/<失败类>` 这类**留给 worker 填的**指令模板、`<当前进展>` 心跳占位）。
所有路径均写成**绝对路径**，心跳/落地命令均指明「**必须在集成头目录跑**」。

**两个 worker 会话均已确认存活**（`mvs_20459080f7b344dd8a92c212f1c74cf5` /
`mvs_098ce5362df745ea8ab7c2a6797ca56c`，status = `started`）⇒ 20:00 这一轮额度窗口可用。

## 静态预检（互相破坏形状扫描）

全仓扫描结果与上一轮**完全一致**（本轮无新迁移并入）：

- ① `ADD COLUMN` 无 `DEFAULT`：**113**（历史存量）
- ② `SET NOT NULL`：**42**（历史存量）
- ③ `ADD CONSTRAINT…CHECK`：**28**（历史存量）

⇒ 协议点名的两种「必然违约」形状（①无 DEFAULT / ②SET NOT NULL 无触发器兜底）
**本轮新增命中数为 0**。

复核上一轮并入的 `V398__profit_report.sql`（13.4.1.1）：**纯 `CREATE TABLE`，三种形状零命中**
⇒ 符合 `reporting` 服务「零 ALTER 既有表」的既有纪律。

复核本轮并入的 `V399__cost_structure.sql`（13.4.2.1）：**2 张 `CREATE TABLE`
（`reporting_cost_structure_fact` / `reporting_cost_structure_rule`），
① `ADD COLUMN` 无 `DEFAULT` **0** · ② `SET NOT NULL` **0** · ③ `ADD CONSTRAINT…CHECK` **0****
⇒ 同样纯新增表，零 ALTER 既有表。

**迁移版本号无冲突**：集成头 `reporting` 已用到 V398，V399 为空位；
全仓 `grep` 无同名迁移文件；V399 只存在于 13.4.2.1 的 worktree 内。

identity 修复车道**零迁移**（只改写入侧的时间取值口径，不动表结构）。

## L2 批量验证：**本轮未跑**（协议明令：在途车道 L1 并发时禁止跑 L2）

### 两条独立理由

1. **在途车道 L1 与 L2 并发 = 协议明令禁止** ——
   `PIPELINE_CRON_PROMPT.md`「最高优先级禁令」原文：
   *「L2 不得与在途车道的 L1 并发跑 —— 多套 Testcontainers PG 互相抢资源，
   2026-10-19 20:00 那轮实测：L2 与 4 条车道的 L1 并发时 PG 峰值 16，
   后端 1364 例里 3 failures + 24 errors **全是假红**（`seed()` 503 / 连接拒绝），
   机器空闲后隔离复跑 61 例全绿。**先等在途车道落地，再跑 L2**。」*
   本轮有 **2 条在途车道**，且 `fix-identity-l2-red` 正在跑 **identity 全套 639 例**
   （实测已有 1 个 Testcontainers PG 容器在跑）。
   ⚠️ **这一轮尤其不能跑**：identity 修复车道的全部价值在于「拿到干净的 403 复现」，
   如果我的 L2 抢资源把它的失败形状变成 503/连接拒绝，它就会得出**错误结论**，
   可能白做几小时。**干扰的代价是不对称的** —— 宁可这轮不跑。

2. **本轮无新合并 ⇒ 无新风险面** ——
   `merge-ready` 返回空，集成头的**业务代码**自 `a3a2e829`（13.4.1.1 并入）以来**零变更**
   （`git log a3a2e829..HEAD -- backend/ apps/ packages/` 为空，只有 docs 提交）。
   L2 的职责是「抓跨车道互相破坏」，没有新合并就没有新风险面。

### 本轮改跑：**容器无关的编译校验**（不是 L2，只是不让未验证的合并裸奔）

⚠️ **13.4.1.1 的并入从未经过任何一次全量验证** ——
`merge(13.4.1.1) a3a2e829` 提交时间 **18:59:38**，
而最后一次 L2（batch108）结束于 **16:35:10** ⇒ **晚 2 小时 24 分**。
（round-122 记的「L2 未跑」指的是「13.4.1.1 落地**前**的集成头没跑」，
落地**后**的合并内容同样没跑过 —— 这是一个上一轮没讲清的缺口。）

⇒ 本轮跑**不启容器、不发热高峰**的两项，把风险压到最低：

```bash
cd backend && ./mvnw -q -T 1C compile
  → rc=0，BUILD SUCCESS（仅 JVM `sun.misc.Unsafe` / guava `UnsafeAtomicHelper` 的 deprecation warning，无编译错误）

pnpm typecheck --force
  → Tasks: 3 successful, 3 total · Cached: 0 cached, 3 total · Time: 39.148s
  → 实际执行路径 = 集成头自己的 apps/web、packages/ui、packages/config-tailwind
```

⚠️ 第一次跑 `pnpm typecheck` 命中了 turbo 缓存（`FULL TURBO`，653ms，replay 的日志路径还指向
已回收的 `.worktrees/13.4.1.1/`）⇒ 为避免「缓存命中掩盖问题」，用 `--force` 重跑了一次，
拿到**真实的 tsc --noEmit 结果**（0 cached）。

**⇒ 13.4.1.1 的并入（V398 + Controller/Service/Port + 前端契约 + api-proxy 白名单）在编译期是自洽的。**
⚠️ **这不等于 L2 绿** —— 它不跑任何测试用例，不覆盖运行时行为（尤其是多租户 RLS 与跨服务回归）。
**它只是把「编译期就炸」这一类最廉价的失败提前排除掉。**

### 下一轮 L2 的前置条件

两条车道落地后（`merge-ready` → `sync` → 静态预检）跑一次全量 L2，
覆盖内容 = 当前集成头 + `13.4.2.1`（V399）+ `identity` 修复。
**若 identity 修复车道真的修好了 403，这轮 L2 就是绿的 ⇒ 106 个提交可以一次 push 出去。**

## 🔴 收尾三件套（本轮逐件点名）

| 件       | 状态      | 说明                                                                                                                                                    |
| -------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ① 写说明 | ✅ 完成   | 本文件（round 123）+ 更新 identity 红点 backlog（补记「worker 被额度耗尽杀死 → 重派」）+ 两份任务书落盘。本轮**无模块交付**，故不写 `batch-<n>` 交付说明 |
| ② commit | ✅ 完成   | 说明文件 + backlog + 台账**同一个 commit**（台账本轮净改动 = 0，423/42 维持不变）                                                                        |
| ③ push   | ❌ **不做** | **只剩一个理由**：**集成头 L2 是红的**（identity 639 例 / 38 failures，修复车道在途，本轮无修复落地）⇒ 按「L2 没绿绝对不 push」不推 |

### 为什么本轮仍然不推

1. **L2 红**：`identity` 639 例 / 38 failures。`PIPELINE.md`「L2 没绿绝对不 push」是最高优先级禁令。
2. **non-fast-forward 阻塞已解除**（第 122 轮 `c61c8637` 合回远端提交，dry-run 证明可 clean fast-forward）
   ⇒ **本轮不推的唯一理由就是 L2 红**，不是推送侧问题。
3. **禁止 force-push**（会丢远端提交）。

⇒ **106 个提交（含 13.3.1.1 / 13.3.1.2 / 13.3.2.1 / 13.3.2.2 / 13.4.1.1 等已交付模块的实现）继续留在集成头本地。**
⚠️ 风险仍成立：这些交付**只存在于本机**，worktree 丢失即不可恢复。

## 吞吐预期（按当前档位现算，不引用任何历史文档的写死数字）

- 档位：`MODE=reduced` ⇒ `LANES=2`、`THROTTLE_MIN=100`
- cron：`0 * * * *`（整点，24 触发点/天）+ `SILENT_WINDOW=0` ⇒ 全天可放行
- 节流闸 100 分钟 > 60 分钟栅格 ⇒ **每 2 个整点放行一批 ≈ 12 批/天**
- 名义吞吐 = 2 × 12 = **24 个/天**；× 综合折损 **0.70**
  ⇒ **实际约 16–17 个/天**
- 剩余 **42** 个 ⇒ 42 ÷ 16.8 ≈ **2.5 天** ⇒ **诚实区间 3–5 天**
  （上沿假设 identity 红点修不好，或额度熔断再次打断在途车道）

## ⚠️ 风险提示（本轮新增）

1. **额度熔断会打断在途车道** —— 本轮实测两个 worker + 一个调度者 + 一个同步任务在 19:03 同时死于
   `Token Plan 用量上限 (2056)`。**这是当前对吞吐威胁最大的单一因素**，比代码问题严重得多：
   一条车道在写完代码后被杀，成果只能靠 worktree 幸存（本轮侥幸）。
   建议关注额度水位，并在额度恢复窗口优先回收**已有半成品**的车道（本轮已按此处理）。
2. **调度者本身也会被熔断** —— 第 122 轮调度者死于同一原因，导致它的「追加动作」只做了一半。
   ⇒ 每轮的收尾三件套要**尽早做**（L2 一绿就先 push，别攒着）。
3. **13.4.1.1 的并入至今未经任何全量验证** —— 本轮只做了编译级校验。
   下一轮 L2 必须覆盖它。

## 后续待办（归下一轮）

1. **回收本轮 2 条车道**：`merge-ready` → `sync` → 静态预检 → **L2 全量**。
2. **L2 绿之后立即**：`git push origin integration/module-dev` ⇒ **一次发布 106+ 个提交**
   （non-fast-forward 阻塞已解除，dry-run 已验证可 clean fast-forward）。
3. **identity 修复车道若报 BLOCKED**：不要靠加并发硬推，按 backlog 的「已排除方向」继续缩小范围。
4. **D6 的 3 个定价模块**（`5.1.1.1` / `5.1.1.2` / `5.1.2.1`）单独排一批 —— 它们是卷 5 定价的入口，
   与卷 13/15/18 的 D7 依赖链独立。

---

## 涉及文件

- `docs/sessions/2026-10/2026-10-28-2000-pipeline-round123-worker-quota-death-redispatch.md`（本文件）
- `docs/backlog/2026-10/2026-10-28-identity-l2-red-403-cross-test-pollution.md`（更新：补记重派）
- `docs/product/capability-blueprint/module-progress.md`（台账，本轮净改动 0）
- `.dev-agent/taskbooks/r123-13.4.2.1.md`（新增，13.4.2.1 接手任务书）
- `.dev-agent/taskbooks/r123-fix-identity-l2-red.md`（新增，identity 修复任务书）
- `.tmp/l2-batch108.log` / `.tmp/l2-batch108.status`（red point 原始证据，沿用第 122 轮）
