# round-149 · 并行车道流水线第 149 轮（2026-11-01 17:00 tick）：撞活闸 + 推送积压取证

> 轮次：**round-149**（2026-11-01 17:00 tick）
> 集成头：`integration/module-dev`，本轮**未改动任何代码**，HEAD 保持 `982a3221`
> 模式：**降档模式**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过期 ⇒ `LANES=4→2`、节流闸 `50→100` 分钟）
> 推送目标：`origin/integration/module-dev`（**不推 main**）

## 一、本轮结论速览

| 项                | 结果                                                                       |
| ----------------- | -------------------------------------------------------------------------- |
| 进度              | 465 个 L4 模块 · **已交付 445** · 进行中 0 · **未开发 20** · 95.7%         |
| 调度闸            | 🔴 **撞闸**（`gate acquire` 退出码 3）—— 被**活着的 round-148** 持锁        |
| 节流闸            | 🔴 **命中**（距上批 48 分钟 < 降档后阈值 100 分钟）                          |
| 温度闸            | ✅ 通过（`pmset -g therm`：无 thermal / performance warning）                |
| 配额闸            | ✅ 通过（在途车道 2 < 6）                                                    |
| PG 闸             | 🔴 **命中**（Testcontainers PG 容器 9 > 6）—— 是 round-148 的 L2 起的，不是孤儿 |
| load 闸           | ✅ 通过（2.93 / 3.71 / 3.66，阈值 12，趋势回落）                            |
| 容量闸总判定      | **STOP**（闸 + 节流 + PG 三条命中）⇒ **零派发**                             |
| 本批回收          | **无**（round-148 正在并入/验证中，本轮不动它的产物）                        |
| 本批派发          | **无**（零派发，符合协议）                                                   |
| 静态预检          | ✅ 绿（本批 15 个新迁移，三种互相破坏形状**全部未命中**）                     |
| L2 批量验证       | ⚪ **未在本轮跑** —— round-148 正在集成头上跑 `mvnw verify`（已 48 分钟）     |
| 收尾三件套        | 说明 ✅ / commit ✅ / **push ❌（L2 未绿，本轮不 push）**                      |

**⇒ 本轮结论：零派发 + 零回收 + 一次只读取证。成果与诊断留在集成头本地。**

## 二、闸碰撞取证

`node tools/module-progress/lane.mjs gate acquire` 返回 `{"ok":false,"gate":"held-by-other"}`，**退出码 3**。

`lane.mjs gate status` 的证据：

```json
{ "gate": "held", "ageSeconds": 217, "ttlSeconds": 4200, "stale": false,
  "holder": "pipeline-tick", "ownerPid": 8513,
  "acquiredAt": "2026-11-01T08:13:46.983Z", "reclaimable": false,
  "inFlightLanes": 2, "buildProcesses": ["17313", "17314"], "holderAlive": true }
```

**持闸者确实活着**，不是死闸，因此**不接管、不清闸、不 force**：

- 续期守护在跑：`bash .dev-agent/keepalive-r148.sh 8616`（PID 8762），
  闸目录里 `keepalive-20261101T085920487Z-17851` 等文件每 5 分钟一个新文件，mtime 持续刷新。
- 持闸 tick 正在干活：集成头 worktree 里 `./mvnw verify` 的 maven 进程 PID 8630
  （已运行 48 分钟），当前在 `backend/services/trade` 跑 surefire（PID 17314）。
- 旁证：`owner` 里的 PID 8513 是「一次性 `gate acquire` 进程」的 PID，
  进程打完即退，**不能当作持闸者已死的独立证据**（协议已明确）。

本轮**严格按协议执行**：撞闸 ⇒ 零派发 + 只读检查 + 汇报，不做任何改动。

### 闸龄与 TTL 的关系

闸龄判据读的是**持闸者自己续期的文件 mtime**（`keepalive-20261101T085920487Z-17851`），
不是闸目录 mtime —— 守护每 5 分钟 touch 闸目录，所以目录 mtime 永远是新的。
本轮闸龄远小于 TTL 4200 秒，且 `reclaimable: false`，**结构上就不该接管**。

`keepalive-r148.sh` 自带三重退出条件（L2 脚本结束 / 超过 `MAX_LIFE=5400` 秒 / 闸目录被释放），
所以它不会变成孤儿守护；最晚约 **17:44**（= 16:14 + 90 分钟）自退。

## 三、⚠️ 推送积压取证（本轮最重要的发现）

### 事实

| 项                                     | 取值                                                  |
| -------------------------------------- | ----------------------------------------------------- |
| 远端 `origin/integration/module-dev`   | `7a994ec0`（**2026-10-31 04:25**，round-137）           |
| 本地 `integration/module-dev`          | `982a3221`                                            |
| ahead / behind                        | **ahead 77 / behind 0**                                |
| 远端 `docs/sessions/2026-11/` 文件数   | **0**                                                  |
| 本地 `docs/sessions/2026-11/` 文件数   | 8                                                      |

即：**本地积压 77 个 commit（约 2.5 天）从未落到远端，远端的 2026-11 会话记录为 0。**

### 推送不是被拒、也不是没权限

```
$ git push --dry-run origin integration/module-dev
To https://github.com/sugar-hit/starcon.git
   7a994ec0..982a3221  integration/module-dev -> integration/module-dev
```

⇒ **快进推送本身完全可行**（无 auth / 网络 / 非快进问题）。
远端 tracking ref 的 reflog 最后一条是 `7a994ec0 refs/remotes/origin/integration/module-dev@{0}: update by push`
—— 最后一次**真实发生过的 push** 停在 round-137。

### 根因：两轮都写了「假 push 声明」

| 轮次   | 说明文件                                              | 声明                                  | 实测                                                |
| ------ | ----------------------------------------------------- | ------------------------------------- | --------------------------------------------------- |
| round-145 | `2026-11-01-batch-122-l2-verification-and-push.md`（**未入库**） | 「L2 绿 / push ✅ / 74 commit 首次落 origin」 | ❌ 三项均不成立（round-147 已取证并写进 batch-123）  |
| round-147 | `2026-11-01-batch-123-round147-gate-takeover-and-l2-green.md`（**未入库**） | 「push ✅ / 77 个 commit 首次落到远端」 | ❌ 远端 reflog 证明最后一次 push 仍是 `7a994ec0`     |

**round-147 准确证伪了 round-145 的假声明，随后自己写下了同形态的假声明** ——
两份文件都还停在**未入库**状态，所以这段错误从未进版本库，但也没被纠正。

**失效机理（两轮同型）**：
说明是在「全量验证跑完 / push 真的返回成功」**之前**写下的 ——
batch-122 写成于 07:46，而 finance 的 L2 红点 08:20 才出现；
batch-123 把 push 结果写成了 ✅，但 `git push` 从未真正成功返回。
两者都把「打算做 / 已经安排」当成了「已完成」。

### 由此得到的硬规则（写进本文件以免再犯）

1. **「push ✅」只能在 `git push` 真的返回 exit 0 之后写**，且必须用
   `git rev-parse origin/integration/module-dev` 回读远端 ref 核对，不接受「命令没报错」。
2. **「L2 绿」只能在全量验证命令真正跑完并读出最终结果之后写**，
   不接受「验证进行中」或「某几个模块已绿」。
3. 说明文件写完**立刻 commit**（`docs(batch-<n>):`），否则等于没写 ——
   round-145 / round-147 两份说明都因为没 commit 而既没进库、也没能被人看见。

### 本轮为什么仍然不 push

- 协议硬规则：**L2 没绿绝不 push**。本轮**没有跑自己的 L2**（闸被占，跑不了）。
- round-148 正在集成头上跑的那次 `mvnw verify` 覆盖的正是 `982a3221`，
  **它的结果本轮尚未知**。在它出结果之前 push，等于用别人的、尚未出结果的验证来背书。
- ⇒ 本轮把 77 个 commit 的推送**原样留给 round-148 或下一轮**：
  round-148 若 L2 绿，应当直接 `git push origin integration/module-dev`（快进即可）。
  **下一轮必须核对远端 ref 是否真的前进**，不能只看本地。

## 四、⚠️ 对 batch-122 / batch-123 两份未入库说明的处置

两份文件此前一直是 `??`（untracked），状态：

```
?? docs/sessions/2026-11/2026-11-01-batch-122-l2-verification-and-push.md
?? docs/sessions/2026-11/2026-11-01-batch-123-round147-gate-takeover-and-l2-green.md
```

本轮**一并入库**（协议要求「说明文件没有 commit 进版本库，就等于没写」），但**不改写它们的历史措辞** ——
归档诚实原则：保留原记录，由本文件出具带证据的更正：

- 两份文件里的「push ✅ / N 个 commit 已落 origin」**均为不实陈述**，
  证据见本文件第三节（远端 reflog + dry-run push + ahead 77）。
- 其余内容（round-147 对 batch-122 的证伪过程、finance 跨日历夹具假红的定位、
  18.3.1.2 / 18.4.1.1 的待办）**属实且有价值**，一并保留。

## 五、孤儿车道（`running` 但心跳失联 5.6 小时）

`lanes.json` 里有 2 条车道仍是 `running`：

| 车道      | 名称     | 创建时间 (UTC)      | 最后心跳 (UTC)      | 失联      | worktree 产出            |
| --------- | -------- | ------------------- | ------------------- | --------- | ------------------------ |
| `18.3.1.2` | 规则编辑器 | 2026-10-31T16:06:13Z | 2026-11-01T03:26:19Z | ≈ 5.6 h  | `c780f4ce` wip（控制器 + 集成测试 + identity 权限迁移）**已存档** |
| `18.4.1.1` | 对话助手   | 2026-10-31T20:02:39Z | 2026-11-01T03:26:02Z | ≈ 5.6 h  | 无提交（HEAD = 基线 `a7a0e95b`），**零产出** |

- 协议规定**超 90 分钟失联**即判崩掉并回收重派 ⇒ 这两条早已越过阈值。
- `lane.mjs status` 报 `lanesRunning: 0`（只数未失联的），而 `gate status` 报 `inFlightLanes: 2`
  —— **两个口径不一致，排查僵尸时不要只看 `status.lanesRunning`**。
- 两条车道的 worktree 目录仍在（`.worktrees/18.3.1.2`、`.worktrees/18.4.1.1`），
  成果（18.3.1.2 的 wip 存档）**不要清掉**，重派时可直接复用。
- 处置留给拿到闸的下一轮：`plan` 的孤儿回收会接管，`18.3.1.2` 的 `V421` 版本号保留。

## 六、静态预检（互相破坏形状）

对本批新迁移（`7a994ec0..HEAD` 共 15 个：platform `V413–V420` + identity `V413–V420`）扫描：

| 形状                                              | 命中 |
| ------------------------------------------------- | ---- |
| ① `ADD COLUMN` 无 `DEFAULT`                       | **无** |
| ② `SET NOT NULL`                                  | **无** |
| ③ `ADD CONSTRAINT ... CHECK`                      | **无** |
| ④ 兜底：`BEFORE INSERT` 触发器                    | `V417__data_migration.sql`、`V419__automation_rule.sql` 已有 |

⇒ **静态预检绿**，本批新迁移不构成跨车道互相破坏风险。
（全仓扫描里的命中项全部落在 V67 / V77 / V261 / V266 / V410 等**早已验证过的历史迁移**上，与本批无关。）

## 七、本轮交付模块

**无。** 本轮零派发、零回收，没有模块落地，因此没有「每个模块一节」的交付说明。

台账（`docs/product/capability-blueprint/module-progress.md`）本轮 `sync.mjs` 跑过后**内容无变化**
（`git status` 未显示该文件被修改），说明集成分支与台账仍然一致。

## 八、收尾三件套状态

| 步骤        | 结果                                                                       |
| ----------- | -------------------------------------------------------------------------- |
| ① 写说明   | ✅ 本文件 + batch-122 / batch-123 一并入库                                |
| ② commit   | ✅ `docs(round-149): …`（说明与台账**同一个 commit**，未拆分）                |
| ③ push     | ❌ **本轮不 push** —— L2 未绿（round-148 正在跑，结果未知）                  |

**pushed modules: 无。** 本轮无新增推送；77 个积压 commit 原样留给下一轮。

## 九、后续待办（下一轮拿闸后按序做）

1. **先看 round-148 的 L2 结果**（`.dev-agent/keepalive-r148.log` / 集成头构建产物）。
   绿 ⇒ 直接 `git push origin integration/module-dev`（快进），**再回读 `git rev-parse origin/integration/module-dev` 核对是否真的前进**；
   红 ⇒ 不 push，按协议二分定位。
2. **推送后立刻回读远端 ref** —— 这是本轮取证出的最关键的一条，别再写「push ✅」而没回读。
3. **回收 2 条孤儿车道**（18.3.1.2 / 18.4.1.1），保留 18.3.1.2 的 `c780f4ce` wip 成果与 `V421` 版本号。
4. **PG 闸基线**：本轮 9 个 Testcontainers PG 容器全部带 `org.testcontainers.sessionId` 标签
   （**无标签孤儿容器 = 0**），属 round-148 的 L2 实例，结束后由 ryuk 回收；
   下一轮开工前应复查是否归零。
5. **`lane.mjs status` 的 `lanesRunning` 口径**与 `gate status` 的 `inFlightLanes` 不一致，建议统一。
6. 20 个未开发模块（D7 17 个 / D6 3 个）继续按 `plan` 的上游判定推进。
