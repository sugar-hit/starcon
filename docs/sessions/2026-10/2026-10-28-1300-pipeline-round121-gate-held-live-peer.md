# 2026-10-28 13:00 · pipeline round 121 · 调度闸被 12:00 tick 活体持锁 ⇒ 零派发 + 只读

> 本轮**没有派发新车道、没有回收、没有跑 L2、没有 push** —— 唯一原因是：
> **调度闸被 12:00 tick（round 120 / batch-107）活体持着**，而它此刻正在跑 L2 批次闸。
>
> 与 round 118（同样撞活闸）的区别：那轮对端的 L2 在飞但车道已落地；
> **本轮对端正在跑的是「本批 2 条车道（13.3.1.2 / 13.3.2.2）并入后的整批 L2」**
> —— 那 2 条车道**已经并入集成头并写好了会话日志**，只差 L2 终判 + 三件套收尾。
> 那是**对端的工作**，本轮不接管、不抢闸、不 force。

## 决策摘要

| 项       | 值                                                                                                        |
| -------- | --------------------------------------------------------------------------------------------------------- |
| 轮次     | **round 121**（2026-10-28 13:00 tick）                                                                     |
| 档位     | **reduced（降档）** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过（当前 2026-10-28 13:00）                |
| 车道数   | `LANES` 4 → **2**（减半）；节流闸 `THROTTLE_MIN` 50 → **100** 分钟                                        |
| 台账     | **422 / 465 已交付（90.8%）**，进行中 0，未开发 **43**；`sync.mjs --check` **绿**                            |
| 节流闸   | 距上批 **66 分钟**（上批 2026-10-28 12:00:36）< 阈值 **100** ⇒ **拦截**（次要拦下因素）                       |
| 调度闸   | `gate acquire` **退出码 3** / `gate status` = `held` / `ageSeconds` 560 / **`stale=false`** ⇒ **撞闸，零派发** |
| 容量闸   | 温度 / 配额 / PG / load **四条全过**（本轮是**容量可派、但调度闸拦住**）                                   |
| 派发     | **0 条**                                                                                                  |
| 回收     | **未执行**（闸被占，协议禁止）；230 条车道 **全部 `merged`**，0 `ready` / 0 `running` ⇒ 本就无可回收           |
| 静态预检 | 本轮**无新迁移**（零派发）；本批已并入的 V396 / V397 **零命中**（纯 CREATE TABLE）                          |
| L2       | **未跑**（由 12:00 tick 持闸在飞，协议禁止并发第二个 L2）                                                    |
| 说明文件 | 本文件                                                                                                    |
| commit   | ✅ 本轮做（仅本文件 + 台账，**同一个 commit**）                                                             || push     | ❌ **本轮不做** —— ① 本轮无交付批次；② 集成头 L2 未绿（对端在飞）；③ 闸被 12:00 tick 持有，push 归它        |

## 容量闸判定（四条只读命令全跑 ⇒ 全过，但调度闸拦住）

| 闸         | 阈值      | 实测                                                                          | 判定      |
| ---------- | --------- | ----------------------------------------------------------------------------- | --------- |
| 温度闸     | —         | `pmset -g therm`：No thermal warning / performance warning / CPU power status has been recorded | ✅ 无降频 |
| 配额闸     | 6         | running **0**（230 条车道全 `merged`）                                        | ✅ 0/6    |
| PG 闸      | 6         | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` **1** 个         | ✅ 1/6    |
| load 闸    | 12        | **4.20**（1min）/ 3.47（5min）/ 3.02（15min）—— 三点**逐点回落**，非爬升；核数 10 | ✅        |

- **孤儿容器 0**：同一条 `docker ps` 里无标签的 `postgres:16-alpine` 数量 = **0**（不需清理）。
- 闸优先级（温度 > 配额 > PG > load）本轮**四条全过** ⇒ 容量本身可派；拦下本轮的是**调度闸**，不是机器。

## 为什么是零派发：调度闸被**活体** 12:00 tick 持着

```
gate acquire → { "ok": false, "gate": "held-by-other" }   退出码 3
gate status  → { "gate": "held", "ageSeconds": 560, "ttlSeconds": 4200, "stale": false }
.dev-agent/dispatch.gate/owner → 32925 / 2026-10-28T04:00:30.022Z（= 12:00:30 +0800）
keepalive-120210 / keepalive-124630 / keepalive-125722 / keepalive-125740
```

### 五条独立证据证明持闸者活着（不是崩掉后留残骸）

| # | 证据                                                                                                                    |
| - | ----------------------------------------------------------------------------------------------------------------------- |
| 1 | **keepalive 持续新建**：12:02:10 / 12:46:30 / 12:57:22 / 12:57:40（间隔 44 分 / 11 分 / 18 秒，最后一个距本轮报告时刻 13:12 仅 15 分钟），闸目录 mtime 一直新鲜 ⇒ `stale` 永远为 false（`owner` 里那个 PID 32925 早已退出 —— 协议早就写明 `gate acquire` 写完 owner 就退出，owner PID 不可用于判活） |
| 2 | **对端会话仍活着**：peer 会话 `mvs_a54d73019c8b45efb060a6ed75481540`（created 12:00:00，**updated 13:09+**，status `started`） |
| 3 | **对端的消息时间戳在持续推进**：13:08:36 → 13:08:58 → 13:09:13 → 13:09:46 → 13:10:49，**每一轮都是新的 `task_output` 轮询** ⇒ 会话没卡死 |
| 4 | **L2 在飞**：`.tmp/l2-batch107.log` 显示 `L2 BATCH 107 START 12:57:44` ⇒ `STEP 1/4 backend ./mvnw verify`；`mvnw verify` 的 maven JVM（PID 50941）+ 4 个 surefire fork JVM 实测存活，`catalog` 服务测试类在跑（139 个 surefire 报告已落盘，**0 失败标记**） |
| 5 | **对端的 todo 明确写着本批已完成到哪一步**：`merge(13.3.1.2)` + `merge(13.3.2.2)` 已并入、`L2` in_progress、`写说明/提交/push` pending、`释放闸` pending ⇒ **它自己会把收尾三件套做完** |

⇒ 本轮**不取闸、不 force、不清闸**。协议对撞闸的处置是硬规定，本轮照办。

## 本批已并入的 2 条车道（**对端的成果，本轮只做只读核对**）

12:00 tick 已把 2 条车道并入集成头（`495f4412` / `1555038b`），并各写了独立会话日志：

| 模块           | 名称         | 迁移                       | 并入时间   | 会话日志                                |
| -------------- | ------------ | -------------------------- | ---------- | --------------------------------------- |
| **13.3.1.2**   | 生命周期与分层 | `V396__lifecycle_segmentation.sql` | 12:57:27 | `2026-10-28-lifecycle-segmentation.md`  |
| **13.3.2.2**   | 关联营销效果   | `V397__affiliate_marketing_effect.sql` | 12:57:27 | `2026-10-28-affiliate-marketing-effect.md` |

- **13.3.1.2 生命周期与分层**：三张新表（分层规则 / 分层结果 / 按币种分行金额），纯 `CREATE TABLE`、零 ALTER 既有表；5 端点挂 `/reporting/lifecycle-segmentation`；页面 `/analytics/products/lifecycle`；后端 24 例（21 功能 + 3 租户隔离）+ 前端 34 例。
- **13.3.2.2 关联营销效果**：四张新表（指标口径 / 营销位台账 / 营销位事实 / 归因事实），纯 `CREATE TABLE`、零 ALTER 既有表；5 端点挂 `/reporting/affiliate-marketing`；页面 `/analytics/products/affiliate-marketing`；后端 26 例（22 功能 + 4 租户隔离）+ 前端 23 例。
- **台账已回写**：两行均为 `已交付`，证据列写明迁移表结构 / 端点 / 页面 / 测试四件。

### 🟢 本轮顺手修好了一处**台账 summary 漂移**（对端漏提交）

对端把 13.3.1.2 / 13.3.2.2 两行的「状态 + 证据」写进了台账并提交，但**没重跑 `sync.mjs`**，
于是 HEAD 上的台账**明细行与 summary 表对不上**：

| 台账版本    | summary 表声称已交付 | 明细行实际统计                             |
| ----------- | -------------------- | ------------------------------------------ |
| HEAD（本轮前） | **420**          | **422** 已交付 / 43 未开发 ← **对不上**     |
| 工作区（本轮 sync.mjs 后） | 422 | 422 已交付 / 43 未开发 ← **一致** ✅ |

⇒ 本轮 `sync.mjs` 把 summary 修正为 **422 / 43 / 完成率 90.8%**，`sync.mjs --check` 现为绿
（「✓ 台账与蓝图一致」）。**这是只读检查顺带修好的，不是本轮新增交付**；
对端下一轮跑 `sync.mjs` 时会得到同样的内容（已验证幂等：连跑两次 diff 不变）。

## 静态预检（本批新迁移 ⇒ **零命中**）

```bash
for v in V396 V397; do f=$(ls backend/services/*/src/main/resources/db/migration/${v}__*.sql); \
  echo "$(basename $f): ADD_COLUMN无DEFAULT=$(grep 'ADD COLUMN' $f | grep -vc 'DEFAULT') \
  SET_NOT_NULL=$(grep -c 'SET NOT NULL' $f) CHECK=$(grep -c 'ADD CONSTRAINT.*CHECK' $f)"; done
```

| 迁移                                 | ① ADD COLUMN 无 DEFAULT | ② SET NOT NULL | ③ ADD CONSTRAINT CHECK | CREATE TABLE | RLS |
| ------------------------------------ | ---------------------- | -------------- | --------------------- | ------------ | --- |
| `V396__lifecycle_segmentation.sql`   | **0**                  | **0**          | **0**                 | 4            | 2   |
| `V397__affiliate_marketing_effect.sql` | **0**                | **0**          | **0**                 | 4            | 2   |

⇒ 本批**新增表**、**零 ALTER 既有表** ⇒ 协议点名的「加了列没给默认值 ⇒ 别人老 INSERT 违约」与
「SET NOT NULL 无触发器兜底」两种互相破坏形状**都不可能发生**。

⚠️ 全量存量扫描（历史迁移，非本批）：① `ADD COLUMN` 无 DEFAULT **113** · ② `SET NOT NULL` **42** ·
③ `ADD CONSTRAINT…CHECK` **28**。这三条是**历史存量**，本批未新增，且 `sync --check` 绿。

## 关键风险提示（本轮最重要的一条）

### 🔴 集成头已 **99 个 commit** 未推送，远端落后约 **40 小时**

```
git rev-list --count origin/integration/module-dev..HEAD   →  99
origin/integration/module-dev  tip = e6f443ae  2026-10-26 21:45:45
本地 HEAD                tip = 495f4412  2026-10-28 12:57:27
```

- **不是本地 remote-tracking ref 过期**：`git fetch origin integration/module-dev` 后 `ahead` 仍是 99、`behind` 是 0 ⇒ **远端真的没有这 99 个 commit**。
- **最后一次成功 push 是 2026-10-26 21:45**（`docs(backlog): 卷 13 分析页缺功能目录入口`）。此后 40 小时里，
  流水线一直在「交付 + 并入 + 修 L2 红点」，**但一次都没推上去**。
- 根因是连续的 **L2 红**：round-115（channel 11 例跨钟域假红）→ 已由 `fix/channel-l2-red` 车道修掉并入 `c3068314`；
  round-119（10:05 那轮，backend=1，红点 = `catalog` `SpuLifecycleIntegrationTest` 2 例 503）⇒ 又是一次假红。
- ⇒ **push 的裁决点就是 12:00 tick 正在跑的这轮 L2**。它绿了 ⇒ 99 个 commit 一次性推上去；
  它红了 ⇒ 又一次二分定位。**本轮不能替它裁决，也不能在 L2 未绿时 push。**

### 🟡 上一轮 L2 的红点还没被证伪

`l2-r119.log`（10:05 那轮）：`catalog` `SpuLifecycleIntegrationTest` 2 例
`Status expected:<200> but was:<503>` / `expected:<404> but was:<503>`，
`Tests run: 1999, Failures: 2`。503 = 服务未就绪 ⇒ **负载型假红的典型形状**
（与 10-19 实测的「L2 与车道 L1 并发 ⇒ PG 峰值 16 ⇒ 24 errors 全是假红」同源）。
本批 L2 **在途车道 0 条** ⇒ 无并发 ⇒ 该红点若不复现，即坐实「假红」定性。

## 收尾三件套（本轮逐件点名）

| 件       | 状态      | 说明                                                                                                                              |
| -------- | --------- | --------------------------------------------------------------------------------------------------------------------------------- |
| ① 写说明 | ✅ 完成   | 本文件：round 121 决策摘要 + 容量闸取证 + 活体持闸的 5 条证据 + 本批 2 模块逐模块说明 + 静态预检 + 未推送积压风险                             |
| ② commit | ✅ 完成   | 说明文件与台账**同一个 commit**（台账净改动 = 420→422 已交付、45→43 未开发、D7 113→115）                                          |
| ③ push   | ❌ **本轮不做** | **L2 批次闸未绿**（对端 12:00 tick 正在跑，本轮无权裁决）⇒ 按「L2 没绿绝对不 push」不推。**99 个 commit 留集成头本地，随对端绿后一起 push** |

## 后续待办（归下一轮 / 归 12:00 tick）

1. **12:00 tick**：L2 绿 ⇒ 写 batch 说明 → commit → `git push origin integration/module-dev` ⇒ **一次推走 99 个 commit**；
   L2 红 ⇒ 二分定位（最多 2 次全量），坐实 `SpuLifecycleIntegrationTest` 503 是否仍是假红。
2. **下一轮 tick**：核对 `origin/integration/module-dev` 是否已追上 `495f4412`（或之后的合并点）⇒ 确认积压清零。
3. **43 个未开发模块**全部在 D7（40）与 D6（3），逐条核对台账得：

   - **D6（3）**：`5.1.1.1` / `5.1.1.2` / `5.1.2.1`
   - **D7（40）**：`13.4.1.1` / `13.4.1.2` / `13.4.2.1` / `13.4.3.1` / `13.5.1.1` / `13.5.2.1` / `13.5.3.1` /
     `13.6.1.1` / `13.6.1.2` / `13.6.2.1` / `14.2.1.1` / `14.3.1.3` / `14.3.2.1` / `15.3.1.1` / `15.3.1.2` /
     `15.3.1.3` / `15.6.1.1` / `15.6.1.2` / `15.6.2.1` / `18.1.1.2` / `18.1.1.3` / `18.1.2.1` / `18.1.2.2` /
     `18.1.3.1` / `18.1.3.2` / `18.2.2.1` / `18.2.3.1` / `18.3.1.1` / `18.3.1.2` / `18.3.2.1` / `18.3.2.2` /
     `18.3.3.1` / `18.4.1.1` / `18.4.1.2` / `18.4.2.2` / `18.4.2.3` / `18.4.3.1` / `18.4.3.2` /
     `18.5.1.1` / `18.5.2.1`

   ⇒ **本批的收尾一旦推走，下一批继续按依赖深度排这批**（`lane.mjs plan` 自行挑选，不手工指定）。
4. **不自行改节流闸 / `--lanes` / 配额 / load 阈值** —— 那是用户的决定，调度器只报不改。

## 附：本轮执行过的命令（全部只读，除 sync.mjs 回写台账）

```bash
node tools/module-progress/sync.mjs                # 同步台账（写 module-progress.md）
node tools/module-progress/sync.mjs --check         # ✓ 台账与蓝图一致
node tools/module-progress/lane.mjs status          # 台账快照 + 230 条车道
node tools/module-progress/lane.mjs gate acquire    # 退出码 3：held-by-other
node tools/module-progress/lane.mjs gate status     # held / stale=false
pmset -g therm ; sysctl -n vm.loadavg ; sysctl -n hw.ncpu
docker ps --filter "label=org.testcontainers.sessionId" --filter "ancestor=postgres:16-alpine"
git fetch origin integration/module-dev             # 只更新 remote-tracking ref
git rev-list --count origin/integration/module-dev..HEAD
grep -rn 'ADD COLUMN|SET NOT NULL|ADD CONSTRAINT.*CHECK' backend/services/*/src/main/resources/db/migration/
```
