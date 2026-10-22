# 2026-10-22 22:00 tick · 零派发轮 + batch-67 契约漂移根因定位

- **日期**：2026-10-22 22:00（Asia/Shanghai）
- **分支**：`integration/module-dev`（集成分支，集成头 worktree `/Users/guan/Projects/starcon-worktrees/integration`）
- **模式**：full（22:00 < `TOKEN_HORIZON` = 2026-10-25T00:00:00+0800）→ `LANES=4`、`THROTTLE_MIN=50`、`SILENT_WINDOW=0`
- **本轮产出**：**零派发**。本轮没有并入、没有交付任何模块；产出是一次只读根因定位。

## 摘要

本轮 **两条闸同时命中**，按协议零派发：

| 闸 | 判定 | 依据 |
| --- | --- | --- |
| 批次节流闸 | 命中 | 距上批 46 分钟 < 阈值 50 分钟 |
| 调度闸 | 命中 | `gate status` = `held`，`ageSeconds=2765` < TTL 4200，`stale=false` ⇒ **有活着的 tick 持闸**，不可抢 |
| 温度闸 | 通过 | `pmset -g therm` = `Note: No thermal warning level has been recorded` |
| 配额闸 | 通过 | `lanesRunning=1` < `RUNNING_QUOTA=6` |
| PG 闸 | 通过 | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` 容器 **0** 个（无标签孤儿容器也是 0） |
| load 闸 | 通过 | load 5.96 / 4.66 / 4.44（10 核，空载基线区间内，无爬升） |

持闸者经取证为 **21:00 那一轮活 tick**（会话 `mvs_299e2dada0a040be8c031edf8e22228c`，状态 `started`）：
它在 21:14 续期闸、21:21 写下 batch-67 说明、22:00:39 跑完 L2 并**拿到红灯**，
22:04–22:05 又并入了 4 条车道（1198d8ae / f4c55803 / fa810233 / 4f9015f8），
正在准备复跑 L2。⇒ **本轮不能碰集成头的任何写操作**，更不能抢闸或并发跑 L2。

## 决策 / 结论

### 1. 契约闸红灯（batch-67）的根因：**batch-66 遗留的旧账，不是本批造成的**

逐项排除嫌疑人：

- batch-67 的并入 commit `c92aeb12` **只改了 2 个文档文件**（台账 + 会话记录），没碰 channel 后端 ⇒ 排除。
- 两次 main 同步（`05b7d5c4` / `a89a44da`）**只改会话记录** ⇒ 排除（PIPELINE.md §5.6 的首要嫌疑，本次不成立）。

漂移的真实内容 —— 已提交的 `packages/api-contracts/src/channel.openapi.json` 与
`contracts/channel.ts` 比 codegen 输出**少 7 个字段**，且全部落在两个 schema 上：

| schema | 缺失字段 |
| --- | --- |
| `PerformanceMetricAlertPage` | `criticalCount` / `unknownCount` / `warningCount` |
| `PerformanceMetricForecastPage` | `alreadyBreachedCount` / `cannotPredictCount` / `noBreachCount` / `willBreachCount` |

后端源码 `PerformanceMetricModels.java` 自 `7b6b861e`（16.1.1.2）起就有这 7 个字段
⇒ **契约产物落后于后端**，不是后端缺字段。

### 2. 不是「合并损坏」，是 worker 当初的产物就不全

直接比对 worker 分支：`feat/16-1-1-2:packages/api-contracts/src/channel.openapi.json`
**同样缺这 7 个字段**。⇒ 并入驱动没有破坏任何东西，是 16.1.1.2 那条车道提交契约产物时
用的 spec 快照早于它自己最后那版后端 DTO。

### 3. 为什么 batch-66 没拦住：**那批的 L2 里没有契约闸**

batch-66 的 L2 结论原文只列了
「后端 8317 例 0 失败 0 错误 / 前端 478 文件 6988 例全绿 / typecheck+lint+Next build+sync --check 全部 exit 0」
—— **没有契约闸**。而当前 `.dev-agent/l2-run.sh` 的第 6 步（`[6/6] 契约闸`）是**后加的**。
⇒ 契约闸进脚本之前放行的批次，契约漂移没有任何自动化拦截。

### 4. 4 条车道并入后，漂移已被顺带修好（**不要为此单独派修复任务**）

HEAD(`4f9015f8`) 相对并入前 `c92aeb12` 的契约产物，schema / type 级比对：

| 产物 | 变化 |
| --- | --- |
| `channel.openapi.json` | 1385 → 1417 schemas；**+32 新增、0 删除**；仅 2 个旧 schema 被改，且**都只是加字段**（正是上面那 7 个，由 16.1.2.1 车道的契约产物带入） |
| `crm.openapi.json` | 293 → 368 schemas；**+75 新增、0 删除、0 个旧 schema 改动** |
| `contracts/crm.ts` | 293 → 368 types；+75，0 删除，**0 个旧 type 被改**；4 条车道分支的 type 名 100% 都在并入结果里（缺失 0） |

⇒ 契约产物与并入后的后端在**内容层面已经自洽**，剩下只有字节层面（排序/格式）的风险。

## 关键 trade-off

- **本轮没有 commit / push 是刻意的**，不是漏做：
  集成头里有在途 tick 的未提交成果（batch-67 说明 + 台账改动），
  此时 `git add/commit` 会**抢 git index**，并把 L2 未绿的成果固化进 commit；
  `git push` 还会把未验证内容推到远端，违反「L2 没绿绝对不 push」。
  ⇒ 收益（抢 1 分钟）远小于风险（破坏在途轮的收尾三件套 / 推红），放弃。
- **没有复用「L2 红了就二分」这条常规路径**：本轮 L2 不是本轮跑的，
  在途 tick 正在处理；本轮只做只读取证，把根因直接交给它，省掉它一次 25–40 分钟的试错。
- 顺带把批次闸的成本压到最低：**零派发 + 零容器 + 零 git 写**，本轮机器负载 ≈ 只读脚本。

## 后续待办

1. 在途 tick 复跑 L2 前，**必须让后端重新构建导出新的 spec 快照**。
   它此前那次契约比对用的是 21:19 的**并入前**快照，所以 crm 也报差异 —— 那是假阳性。
2. 若用新快照复跑后契约闸仍红：正解是
   `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
   重新生成产物并提交（契约是 codegen 产物，**禁止手改 JSON / 手合并**）。
3. **建议把「契约闸」写进每一批的 L2 结论里**（batch-66 漏了它，代价是旧账漂移到今天才爆）。
4. 本文件与 batch-67 说明、台账改动一起，在下一轮由在途 tick 的
   `git add docs/sessions/$(date +%Y-%m)/` 收进版本库（**本轮刻意不 commit，避免抢 index**）。

## 文件清单

- **本轮新增**：`docs/sessions/2026-10/2026-10-22-pipeline-zero-dispatch-contract-drift-rootcause.md`（本文件，未 commit）
- **本轮只读**：未改动集成头的任何代码 / 契约 / 台账模块行
  （`sync.mjs` 只刷新了台账汇总区的空行格式，模块行零改动 —— 已用 `git diff -U0` 核过）
- **临时产物**：`.tmp/contract-drift-probe/`（契约漂移复现用）已清理，未留痕
- **交付给在途 tick**：根因 + 「漂移已被并入顺带修好」+ 「用新快照复跑」的结论，
  已通过 session 消息投递到 `mvs_299e2dada0a040be8c031edf8e22228c`
