# round-146 · 并行车道流水线第 146 轮（2026-11-01 05:00 tick）：调度闸被**活着的**上一轮持闸 ⇒ 零派发 + 只读取证

> 轮次：**round-146**（2026-11-01 05:00 tick）。上一轮 round-145（04:00 tick）仍在跑。
> 集成头：`integration/module-dev`，本轮起点 `1f342b8d`
> 模式：**降档模式**（`TOKEN_HORIZON=2026-10-25` 已过期 ⇒ `LANES=4→2`、节流闸 `50→100` 分钟）
> 调度闸：`gate acquire` 返回**退出码 3 / `held-by-other`** ⇒ 本轮**零派发**

---

## 本轮结论速览

| 项                   | 结果                                                                                  |
| -------------------- | ------------------------------------------------------------------------------------- |
| 进度                 | 465 个 L4 模块 · **已交付 445** · 进行中 0 · **未开发 20** · **95.7%**                |
| 容量闸               | **GO**（温度闸正常 / running 2<6 / PG 容器 2–3<6 / load 3.31<12）                     |
| 节流闸               | 通过（距上批 **526 分钟** ≥ 阈值 100 分钟）                                            |
| **调度闸**           | 🔴 **撞闸**（`acquire` 退出码 3）—— 持闸者**活着**且正在跑 L2 ⇒ **零派发**             |
| 本批回收             | 无（`merge-ready` 未执行 —— 闸被占时协议禁止跑并入）                                  |
| 本批派发             | **零条**                                                                               |
| 静态预检             | 对**待推送的 74 个 commit / 15 个新迁移**逐条扫描：**三项全清**（0 命中）              |
| L2 批量验证          | ⏳ **在途**（round-145 正在跑 `mvnw verify`，05:20 时 232/770 报告、**0 失败**）      |
| 收尾三件套           | 说明 ✅ / commit ✅ / **push ❌** —— L2 未绿，本轮**不 push**                            |
| 待推送存量           | **74 个 commit**（含 13 个 merge commit）仍滞留本地，远端仍在 `7a994ec0`              |

> ⚠️ **本轮不是「无事可做」，而是「有大量待办但全部被一道闸挡住」**：
> 74 个已并入集���头的提交等 L2 绿才能推；2 条在途车道因额度熔断死了 worker；
> 而这一切的**唯一阻塞点是一个活着的持闸 tick**，不是代码、不是机器资源。

---

## 调度闸判定：撞闸，且持闸者**活着** ⇒ 零派发

`node tools/module-progress/lane.mjs gate acquire` 的实测返回：

```json
{ "ok": false, "gate": "held-by-other" }
```

退出码 **3**。按协议（PIPELINE.md 第 2 步 / PIPELINE_CRON_PROMPT.md §3.3），
**撞闸的正确动作是「零派发 + 只读检查 + 简短汇报」，不是清闸、不是 `force`**。本轮严格照此执行。

### 持闸者确实活着 —— 四重独立证据

这是本轮**最关键的一次取证**，因为历史上同一形状被误判过两次
（round-130/131/132：连续三轮只取证不处置）。

| # | 证据                                                        | 实测值                                                                                                              |
| - | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| 1 | 持闸会话状态                                               | `mvs_1c73c7520d9041d28d1978de8cf1f979` = **started**（round-145 / 04:00 tick）                                       |
| 2 | 持闸者的**自己的** L2 日志在长                            | `.tmp/l2-verify.log` 持续增长，05:20 时 **232/770** 报告、**0 失败**行、`Building channel [5/11]`                     |
| 3 | 构建进程活���                                               | `mvnw verify`（PID 73297，56 分钟）+ surefire fork（77026/77027）**仍在写 surefire 报告**（05:01 有新文件）           |
| 4 | 持闸者近 5 分钟仍在活动                                   | 05:07 起持续改 `batch-122` 说明文件（实测 3 次 `edit` 调用），并在 05:07 写 `.dev-agent/last-batch-at`               |

`gate status` 的字段全部自洽：`gate=held / ageSeconds=153 / stale=false / reclaimable=false / holderAlive=true / inFlightLanes=2`。

> 🔴 **特别记录一个反直觉点**：owner 文件里的 `ownerPid=72695` **已经死了**（`ps` 查无此进程）。
> 但 `holderAlive` 判的是**在途车道 + 构建进程**，不是 owner PID ——
> 这正是 PIPELINE.md 里写明的判据。**只看 owner PID 会得出「持闸者已死、可以接管」的错误结论**，
> 而实际持闸者正在跑 L2。协议对此有明文规定，本轮据此**没有**尝试 `reclaim`。

---

## 额度熔断：6 个 worker 集体死亡，留下 2 条僵尸车道

本轮另一项重要发现，**与 2026-10-28 那次是同一个根因**（见
`docs/backlog/2026-10/2026-10-28-agent-token-quota-meltdown-kills-inflight-lanes.md`）。

round-145 在 04:02 派发了 2 条车道，但**它的 6 个 worker 子会话在 04:52–04:53 集体死亡**：

| 会话                            | 角色                              | 错误                                              |
| ------------------------------- | --------------------------------- | ------------------------------------------------- |
| `mvs_42970891e8a648ad91c576dfe91d4422` | worker · Lane 18.3.1.2 rule editor (retry) | `已达到 Token Plan 用量上限 (2056)` / 42212 |
| `mvs_e6c35d6b601e4197bad6858fccd9ee44` | worker · Lane 18.4.1.1 copilot 4th try     | `已达到 Token Plan 用量上限 (2056)` / 42212 |
| `mvs_55195055df504cd5bf55f559ec9a5567` | worker · Lane 18.4.1.1 (3rd attempt)        | `Internal server error (500)` / 50113   |
| `mvs_3649674bde054ce98d44e011c3613112` | worker · Lane 18.4.1.1 (retry)               | `Internal server error (500)` / 50113   |
| `mvs_b07086003de14325bdfffba05d26b618` | worker · Lane 18.3.1.2 rule editor           | `net::ERR_HTTP2_PING_FAILED` / 50001   |
| `mvs_1b37dbd3d2df46bea5f9591fee8c1eed` | worker · Lane 18.4.1.1 copilot assistant     | `net::ERR_HTTP2_PING_FAILED` / 50001   |

⇒ 同一时刻、同一根因（**Token Plan 额度熔断**，账号侧，不是代码问题），
与 2026-10-28 那次形状一致。**持闸的调度者会话活了下来，它的 worker 全部死了** —— 这是本轮与上次的差异。

### 两条僵尸车道的可回收成果（**下一轮必须先看这里，否则会白丢 1596 行**）

| 车道         | 名称     | 迁移版本 | 状态         | 存活成果                                                                  |
| ------------ | -------- | -------- | ------------ | ------------------------------------------------------------------------- |
| **18.3.1.2** | 规则编辑器 | `V421`  | `running`（心跳 04:50，**06:20 判 stale**） | ✅ **1596 行未提交成果**：`V421__automation_rule_editor.sql`（236 行）+ `AutomationRuleEditorService.java`（1138 行）+ `AutomationRuleEditorModels.java`（222 行），**尚未 commit** |
| **18.4.1.1** | 对话助手   | `V422`  | `running`（心跳 04:32，**06:02 判 stale**） | ❌ **零成果**：`git status` 全空，**`V422` 迁移文件根本不存在**（worker 死在读码阶段） |

> ⚠️ **18.3.1.2 的 1596 行是「未提交」状态**，只靠 worktree 幸存。
> `plan` 的派发前冲突预检明确规定：**worktree 已存在且含未跟踪文件 ⇒ 立刻停止派这条，零改动退出**。
> 下一轮若直接 `plan` 而不先处理，会撞上这条预检 —— **必须先决定是接管这些文件还是弃掉重做**。

---

## 静态预检：对**待推送的 74 个 commit**逐条扫描（三项全清）

本轮没有新并入的车道，但**待推送存量本身就是一批需要过闸的迁移**，
所以预检对象是「将要被 push 的那一批」，而不是本轮新增。

批次边界 = 待推送范围内**最早**的 merge commit 的第一父 `bf9933a4`，范围共 **15 个新迁移**
（platform `V413`–`V420` 8 个 + identity `V413`–`V420` 权限码 7 个；
channel `V402` 已随 main 同步到达 origin，不在本批 diff 内）。

| 形状                                       | 命令                                     | 结果            |
| ------------------------------------------ | ---------------------------------------- | --------------- |
| ① 加了列但没给默认值（破坏别人的老 INSERT） | `ADD COLUMN` 且无 `DEFAULT`              | ✅ **0 命中**   |
| ② 改成必填但无 BEFORE INSERT 触发器兜底     | `SET NOT NULL`                            | ✅ **0 命中**   |
| ③ 新增 CHECK 约束未放宽旧值域               | `ADD CONSTRAINT ... CHECK`                | ✅ **0 命中**   |

> 附带取证：全仓 `backend/services/*/db/migration/` 里 `ADD COLUMN` 无 `DEFAULT` 有 113 处、
> `SET NOT NULL` 42 处、`ADD CONSTRAINT ... CHECK` 30 处 —— **全部是存量债**，
> 不在本批范围内。按协议「存量债不得让闸打红」，这些不阻塞本批。

---

## L2 批量验证：在途，本轮不重复跑

| 项                | 结果                                                                                   |
| ----------------- | -------------------------------------------------------------------------------------- |
| 谁在跑            | round-145（04:00 tick），`.tmp/l2-verify.log`                                            |
| 后端 verify 进度  | 05:20 时 **232/770** 报告、`Building channel [5/11]`、**失败行数 0**                    |
| 上一轮已完成 L2   | `.dev-agent/l2-r144.out`：**`mvnw verify=1` ⇒ RED**（typecheck/lint/test/next-build/sync/格式闸均 0） |
| RED 的唯一根因    | 存量 warehouse 跨钟域缺陷 → 已由 `723cee8f` 修复并并入（`cd1deb5b`）                    |
| 本轮是否重复跑    | **否**。协议明确「L2 不得与在途车道的 L1 并发跑」，且本轮无新并入内容可验               |

> ℹ️ **上一轮的 RED 恰恰是本轮不能 push 的直接原因**：待推送的 74 个 commit 里包含
> `merge(18.3.3.1)` 等 13 个 merge，其验证结论取决于这一轮 verify 的最终结果。
> **结果未出 ⇒ 不 push。**

---

## 收尾三件套：说明 ✅ / commit ✅ / push ❌

### ① 写说明 —— ✅ 完成

本文件：`docs/sessions/2026-11/2026-11-01-0500-pipeline-round146-gate-collision-live-holder.md`

**为什么不用 `batch-<n>` 命名**：本轮**零交付**，不产出任何模块，没有「每个模块一节」可写。
协议要求批次号接着已有往后排，而 **`batch-122` 已被在途的 round-145 占用** ——
若本轮也取 `batch-123`，下一轮真正交付的车道批次就会**重号**（协议明令禁止）。
故沿用 round-137 撞闸轮的既有先例，用 `round-N` 命名空间把「诊断轮」与「交付批次」分开。

### ② commit —— ✅ 完成

```bash
cd "$PROJECT_ROOT"
node tools/module-progress/sync.mjs          # 台账已是最���状态，无待提交改动（实测 diff 为空）
git add docs/sessions/2026-11/2026-11-01-0500-pipeline-round146-gate-collision-live-holder.md \
        docs/backlog/2026-10/2026-10-28-agent-token-quota-meltdown-kills-inflight-lanes.md \
        docs/product/capability-blueprint/module-progress.md
git -c user.name="Mavis" -c user.email="mavis@starcon.local" commit \
  -m "docs(round-146): 调度闸被活着的 round-145 持锁（6 个 worker 因额度熔断集体死亡）⇒ 零派发 + 只读"
```

> ⚠️ **刻意没有 `git add docs/sessions/$(date +%Y-%m)/` 整个目录**：
> 该目录下有 round-145 **正在编辑中**的未跟踪文件
> `2026-11-01-batch-122-l2-verification-and-push.md`。
> 整目录 add 会把**别人的在途产物**卷进本轮 commit（并可能与其后续提交撞车），
> 因此本轮**只 add 自己那两个文件**。
> 台账文件实测无待提交改动（`git diff` 为空），`sync.mjs` 刚跑完即为最新，附在同一个 commit 里。

### ③ push —— ❌ **本轮明确不 push**

**原因：L2 未绿。** 协议最高优先级禁令：「**L2 没绿绝对不 push**」。

具体到本轮：

- 待推送的 **74 个 commit** 的验证结论**还在跑**（232/770，0 失败但**未出结论**）；
- 唯一一次**已完成**的 L2（`l2-r144`）是 **RED**；
- 持闸的 round-145 会在 verify 出结果后**自己**完成 `commit` + `push`（它已经写好了 batch-122 说明），
  本轮**抢跑 push 会把一个尚未定性的批次推到远端**，且与它随后要做的 push 撞车。

⇒ **成果与诊断留在集成头本地，随 round-145 / 下一轮一起 push。**
这与协议「L2 没绿不 push，但说明照写、commit 照做」完全一致。

---

## 本轮关键取舍

| 取舍                                       | 决定                    | 否决了什么 / 为什么                                       |
| ------------------------------------------ | ----------------------- | --------------------------------------------------------- |
| 撞闸后要不要接管调度闸                     | **不接管**              | `reclaimable=false` + 持闸者活着在跑 L2；`--force` 只会造成双向持闸 ⇒ 版本号撞号 + L2 成片假红 |
| 撞闸后要不要自己跑 L2                     | **不跑**                | 协议：L2 不得与在途车道的 L1 并发跑；且本轮无新内容可验 ⇒ 纯发热无收益 |
| 撞闸后要不要 `merge-ready` / `gc` / `plan` | **全不做**              | 协议在「闸被占」时明确禁止这三者（`plan` 有副作用，会凭空造车道） |
| 要不要顺手救两条僵尸车道                   | **不救，只取证**         | 心跳由 worker 写、调度者代写会**覆盖那条有信息量的 note**；且 `abandon`/`plan` 都属于持闸者的动作 |
| 本轮说明用 `batch-123` 还是 `round-146`   | **round-146**           | `batch-122` 已被在途 round-145 占用，取 123 会与下一轮真实交付批次**重号** |
| 本轮 commit 要不要整目录 `git add`         | **只 add 自己的 2 个文件** | 目录里有 round-145 **正在编辑**的未跟踪文件，整目录 add 会把它卷进本轮 commit |

---

## 文件清单（本轮 commit 范围）

- **新增**：`docs/sessions/2026-11/2026-11-01-0500-pipeline-round146-gate-collision-live-holder.md`（本文件）
- **追加**：`docs/backlog/2026-10/2026-10-28-agent-token-quota-meltdown-kills-inflight-lanes.md`
  —— 按 backlog README「同一主题的后续追加写到同一文件」，
  记录 2026-11-01 04:52 的第 2 次复发（6 个 worker 集体死亡 / 2 条僵尸车道 / 1596 行待救成果）
- **台账**：`docs/product/capability-blueprint/module-progress.md` —— `sync.mjs` 后无待提交改动（465 行完整、无重复 ID）

## 后续待办（交下一轮 / 交用户）

- [ ] **下一轮 tick（06:00）第一件事**：看 `gate status`。若 round-145 已完成 L2 并 release 闸 → 正常回收；
      若仍 held 但 `stale=true` 且 `holderAlive=false` ⇒ 走受管接管 `gate reclaim --wait 600`。
- [ ] **接管或重做 18.3.1.2 的 1596 行**（`V421` + 2 个 Java 文件，**未提交**）——
      派发前冲突预检会因「worktree 已存在且含未跟踪文件」拦住，**必须先决定去留**。
- [ ] **18.4.1.1 无成果**（`V422` 迁移不存在）⇒ 06:02 判 stale 后可直接重派。
- [ ] **额度水位是账号侧决定，流水线无权调整** —— 熔断已连续两轮复现，
      需要用户决定是否补充 Token Plan 额度（否则 worker 会持续在交付前被杀）。
- [ ] 待推送的 **74 个 commit** 在 L2 绿后由 round-145（或下一轮）统一 push，**不推 main**。
