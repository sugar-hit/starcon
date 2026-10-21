# batch-44 —— 节流闸拦截：零派发只读轮（距上批 17 分钟 < 50 分钟阈值）

> 批次号接在 batch-43 之后（`docs/sessions/2026-10/` 下现有最大为 batch-43，已确认无重号）。
> 集成头 `integration/module-dev`，本轮基线 `4fded68f`（与 `origin/integration/module-dev` **完全一致**，0/0）。
> 本轮为 **2026-10-21 05:00 tick**，`SILENT_WINDOW=0`，`MODE=full`（`LANES=4` / `THROTTLE_MIN=50`；
> 当前 05:0x < `TOKEN_HORIZON=2026-10-25T00:00:00+0800`，**未进入降档**）。
>
> ⚠️ **本轮模块级交付 = 0。** 批次节流闸未达阈值，按协议**零派发 + 零并入 + 零 L2**结束本轮。
> 本文件记录的是**本轮的结论与交接清单**，不是功能交付说明。
> 写法沿用 batch-42（同样是零派发只读轮）的先例：**说明照写、commit 照做、push 照做**。

---

## 本轮摘要

本轮在**同步台账之后立刻**撞上**批次节流闸**：

```
LAST=$(cat .dev-agent/last-batch-at)   # 1792528960 → 2026-10-21 04:42:40 +0800
NOW=$(date +%s)                        # 2026-10-21 05:00:30 +0800
ELAPSED=$(( (NOW - LAST) / 60 ))      # 17 分钟
阈值 THROTTLE_MIN=50                   # 17 < 50 ⇒ 未达阈值
```

按 `PIPELINE.md` 第 1.5 步「**未达阈值 → 本轮直接结束。不取闸、不 plan、不派发、不跑 L2。
汇报写一行即可。这是零成本早退 —— 不碰 git、不起容器、不派子任务**」：

- **未取调度闸**（`gate acquire` 未执行；`gate status` 显示 `gate=free` / `stale=false`，闸空闲可用）
- **未 plan、未派发 worker**（0 条新车道）
- **未跑静态预检与 L2**（本轮没有新迁移可扫，也没有新代码可验）
- **未并入 2 条 `ready` 车道**（并入属于「取闸之后」的步骤，见下文「为什么不并入」）
- **未写 `.dev-agent/last-batch-at`**（未过节流闸就写时间戳会把节奏无限往后推 —— 该闸唯一会出的严重故障）

本轮实际动作 = `sync.mjs` ×2 + 5 组只读取证 + 1 次只读文档 commit。

---

## 进度快照（`node tools/module-progress/sync.mjs` 实测输出）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **258**（55.5%） |
| 进行中 | 0 |
| 未开发 | **207** |
| 距 465 全交付 | **还差 207 个** |

分阶段（已交付 / 进行中 / 未开发）：

| 阶段 | 已交付 | 进行中 | 未开发 |
| --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 |
| D3 | 36 | 0 | 4 |
| D4 | 58 | 0 | 2 |
| D5 | 52 | 0 | 0 |
| D6 | 19 | 0 | 59 |
| D7 | 13 | 0 | 142 |

> 剩下的大头是 **D7（142）** 与 **D6（59）**；D3/D4 各只剩个位数尾单。
> 台账完整性核对通过：`module-progress.md` 的 L4 行数 **465**（须恒为 465），
> 模块 ID **无重复**（`sort | uniq -d` 输出为空）。

---

## 容量闸判定：**GO**（四闸全过，但被节流闸拦下）

| 闸 | 实测 | 阈值 | 判定 |
| --- | --- | --- | --- |
| **温度**（最高优先级） | `pmset -g therm` → `No thermal warning level has been recorded` / `No performance warning level has been recorded` | 出现降频项且 < 100 | ✅ 过（无降频项） |
| **配额**（running 车道数） | **2** | `RUNNING_QUOTA=6` | ✅ 过 |
| **PG**（Testcontainers 容器） | **0** | `PG_CONTAINER_LIMIT=6` | ✅ 过 |
| **load** | 1min **1.71** / 5min 2.38 / 15min 2.96 | `LOAD_LIMIT=12`（核数 10） | ✅ 过 |

- **load 趋势**：`1min(1.71) < 5min(2.38) < 15min(2.96)` ⇒ **在下降**，不是爬升态。
- **PG 容器**：`docker ps --filter ancestor=postgres:16-alpine` 输出**空** ——
  既没有带 `org.testcontainers.sessionId` 标签的在用容器，**也没有无标签的历史遗留孤儿容器**
  （本轮无需清理，2026-10-19 那批吃掉 5/6 额度的孤儿已消失）。
- **调度闸**：`gate status` → `gate=free`、`ageSeconds=null`、`ttlSeconds=4200`、`stale=false` ⇒ 闸空闲、无泄漏。

> ⚠️ **容量闸全过 ≠ 可以派发**：节流闸（时间维度）在容量闸之后独立生效，本轮命中它。

---

## ⛔ 节流闸判定：**BLOCKED**（本轮的唯一拦截项）

| 项 | 值 |
| --- | --- |
| 上批时间戳 `.dev-agent/last-batch-at` | `1792528960` → **2026-10-21 04:42:40 +0800** |
| 本轮采样时刻 | **2026-10-21 05:00:30 +0800** |
| 实际间隔 | **17 分钟** |
| 阈值 `THROTTLE_MIN` | **50 分钟** |
| 结论 | **17 < 50 ⇒ 未达阈值 ⇒ 零派发** |
| 下次放行时刻 | **05:32:40**（05:32 之后任意时刻过闸；下一个 cron 触发点 **06:00**，届时间隔 77 分钟 ⇒ 放行） |

---

## 在途车道交接清单（交给 06:00 tick）

本轮未回收任何车道，以下 4 条原样留在集成头的 `lanes.json` 里：

### 待并入（`state=ready`，已 land，成果在分支上等合并）

| 模块 | 名称 | L3 | 迁移 | 分支 head | 落地时刻 | 已完成 |
| --- | --- | --- | --- | --- | --- | --- |
| **6.2.1.2** | 补货建议 | 6.2.1 | `V238` | `7b41077d` | 04:40:52 | 后端迁移 + Models/Rules/CatalogPort/Service/Controller 落地；`ReplenishmentSuggestionCalculationTest` 13 例 + `IntegrationTest` 28 例 + `TenancyIntegrationTest` 4 例全绿 |
| **6.1.2.3** | 供应商资质 | 6.1.2 | `V239` | `be4a7c9c` | 05:03 前后 | 契约重新生成（+17 路径）+ 前端 `/supply/qualifications` 落地；api-proxy 全量 allowlist 回归 48 文件 255 例绿；L1 后端 34 例绿；已提交 3 个 commit 并 land 成功 |

### 在途（`state=running`，心跳正常，**一律不杀**）

| 模块 | 名称 | L3 | 迁移 | age | 心跳备注 |
| --- | --- | --- | --- | --- | --- |
| **5.3.2.2** | 价格规则 | 5.3.2 | `V236` | 46 min | 依赖自检通过（`pricing_template(V231)`、`sku_cost_revision(V215)` 均已在集成头） |
| **5.4.1.2** | 批量调价 | 5.4.1 | `V237` | 25 min | 后端主体完成：迁移 + Models/Rules/Service/Controller；`BatchPriceAdjustmentIntegrationTest` 15 例全绿 |

> `stale=false`（均未超 90 分钟失联线），无需干预。

---

## 为什么不并入那 2 条 `ready` 车道

三条理由，都指向同一个动作：**本轮不碰 git**。

1. **协议顺序**：并入属于「取闸之后」的第 3 步，而节流闸在第 1.5 步就结束了本轮；
   `merge-ready` 会 `git checkout` 集成分支并做 `git merge`，属于典型的 git 状态变更。
2. **闸互斥**：`main → module-dev` 同步任务（`SYNC_CRON_ID=01450d5d…`，`15 * * * *`）
   **05:15 就会触发**，与本流水线共用同一把调度闸。不取闸就 merge，
   撞上同步任务会留下 `MERGE_HEAD` 残骸（2026-10-07 一晚被这个缺陷撂过两次）。
3. **不制造未验证代码**：协议规定并入后必须跟一次 L2 才允许 push。本轮被节流闸禁止跑 L2，
   若此时并入，集成头就会带着**未验证代码**停留近一小时，反而违背「集成头只接受绿批次」。

**代价**：2 条 `ready` 车道会空闲到 06:00 tick（6.2.1.2 已空闲 ~36 分钟）。
这是节流闸换来的确定性与温度控制，属预期成本，不是故障。

---

## 🔍 本轮一个实测发现：`last-batch-at` 的落盘时机把节奏腰斩了

这不影响本轮的判定，但影响后续吞吐，建议在下一轮过闸时顺手纠正。

**证据链**（全部可复核）：

| 事实 | 取值 | 来源 |
| --- | --- | --- |
| batch-43 的 6 条 merge commit | `04:02:36` – `04:02:38` | `git log --date` |
| batch-43 的 docs commit | `04:43:01`（`39edfe12`） | `git log --date` |
| `.dev-agent/last-batch-at` | `04:42:40` | 文件内容 + mtime |

⇒ 时间戳写在**并入之后、L2 结束之后、commit 前 21 秒**，也就是**轮末**；
而 `PIPELINE.md` 第 1.5 步要求的是「**通过闸门之后立刻**写」（即轮首、plan 之前）。

**后果（按当前 cron `0 * * * *` + 阈值 50 分钟推演）**：

```
轮末写 04:42:40 → 05:00 tick 间隔 17 分钟 < 50  ⇒ 被拦
                 → 06:00 tick 间隔 77 分钟 ≥ 50  ⇒ 放行
轮末写 06:42:40 → 07:00 tick 间隔 17 分钟 < 50  ⇒ 被拦
                 → 08:00 tick 放行 ……
⇒ 稳态：1 批 / 2 小时（而不是 1 批 / 1 小时）
```

若按协议在**轮首**落盘（04:00:xx），则 05:00 tick 间隔 60 分钟 ≥ 50 ⇒ 放行，节奏回到 1 批 / 小时。

**这正是 `PIPELINE.md` 里写的那句「阈值必须比 cron 栅格间隔小，留出落盘余量」的反面教材** ——
不是阈值取错，而是落盘位置把「余量」吃掉了：**轮末落盘 ⇒ 落盘耗时从 13–40 秒变成 ~43 分钟**，
比栅格间隔的 3/4 还多，直接把每一格都吃掉。

**下一轮的处置建议**：过闸后**立刻**写 `date +%s > .dev-agent/last-batch-at`（轮首），
不要拖到轮末。本轮因为**没过闸**，按协议一个字都没写（`last-batch-at` 仍是 `1792528960`）。

---

## 收尾三件套状态

| 件 | 结果 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅ | 本文件 `docs/sessions/2026-10/2026-10-21-batch-44-throttle-gate-readonly.md` |
| ② commit | ✅ | `docs(batch-44):` 独立提交（沿用 batch-42 先例 `eea8b60a`） |
| ③ push | ✅ | `git push origin integration/module-dev`（仅文档，不含任何代码） |

> ⚠️ **为什么零派发轮也要 commit + push**（两点，都是硬约束不是惯例）：
>
> 1. **技术硬约束**：`lane.mjs` 的 `merge-ready` 在 `git status --porcelain` 非空时
>    **直接 `exit 1` 拒绝并入**（`tools/module-progress/lane.mjs:948`）。
>    文档若留在工作区不提交，**06:00 tick 会因为「主工作区不干净」而无法并入那 2 条 ready 车道** ——
>    通道直接堵死。batch-42 的文档（03:12 写、03:12 提交 `eea8b60a`）正是踩着这条线过去的。
> 2. **纪律**：`AGENTS.md` 要求「每次有结论的会话都要落到 `docs/sessions/`」，
>    零派发轮的结论（节流闸判定 + 交接清单）同样是有价值的结论。
>
> 本轮 commit **不含任何业务代码**，集成头代码状态仍是 batch-43 L2 全绿后的状态（`4fded68f`），
> 因此不构成「L2 未绿就 push」。模块级交付说明在 batch-43 的文档里，本文件不重复。

---

## 吞吐预期（按当前档位现算，诚实区间）

| 项 | 取值 | 来源 |
| --- | --- | --- |
| `LANES` | 4 | `.dev-agent/PIPELINE.local.md`（`MODE=full`） |
| cron | `0 * * * *`（24 触发点/天），`SILENT_WINDOW=0` | 同上 |
| 实际批次间隔 | **2 小时**（现状，轮末落盘）→ 24 批/天；若按协议轮首落盘 → **1 小时** → 48 批/天 | 上文实测推演 |
| 名义吞吐 | 4 × 12 = **48/天**（现状）～ 4 × 24 = **96/天**（纠正后） | LANES × 每日批次数 |
| 综合折损 | × 0.70（`PIPELINE.md` 各折损项连乘经验值） | 协议「组批模式的折损估算」 |
| 有效吞吐 | **34 – 67 个/天** | — |
| 剩余 | 207（其中 4 条已在途/已落地） | `sync.mjs` |

⇒ **剩余 207 个，按当前档位约 3 – 6 天**（区间，随批次间隔与折损浮动）。
**不承诺精确完成日期**；下一轮把 `last-batch-at` 改回轮首落盘后，区间会收敛到 3 – 4 天一侧。

---

## 后续待办（交给 06:00 tick）

1. **过闸后立刻写 `date +%s > .dev-agent/last-batch-at`**（轮首落盘，纠正节奏腰斩）。
2. `lane.mjs merge-ready` 并入 **6.2.1.2**（`V238`）与 **6.1.2.3**（`V239`），随后 `sync.mjs` + `gc`。
3. 对新迁移跑「已知互相破坏形状」静态预检（`ADD COLUMN` 无 `DEFAULT` / `SET NOT NULL` / `ADD CONSTRAINT CHECK`）。
4. `plan --lanes 4` 派发新车道（5.3.2.2 / 5.4.1.2 仍在途，勿重复派发同模块）。
5. 跑**一次** L2 批量验证（`mvnw verify` + `typecheck/lint/test` + Next build + `sync --check`）。
6. L2 绿 ⇒ 写 batch-45 说明（**接 batch-44 往后排**）→ 与台账**同一个** `docs(batch-45):` commit → push。
7. **不要 merge `origin/main`**（那是同步任务的职责，协议见 `tools/module-progress/SYNC-MAIN.md`）。

## 关联

- 上一轮：`docs/sessions/2026-10/2026-10-21-batch-43-six-lane-merge.md`（6 车道并入 + L2 六阶段全绿）
- 同型先例：`docs/sessions/2026-10/2026-10-21-batch-42-gate-collision-readonly.md`（撞闸零派发只读轮）
- 协议：`tools/module-progress/PIPELINE.md`、`tools/module-progress/PIPELINE_CRON_PROMPT.md`
- main 同步协议：`tools/module-progress/SYNC-MAIN.md`
