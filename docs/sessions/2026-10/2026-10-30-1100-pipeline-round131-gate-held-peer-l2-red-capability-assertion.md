# 2026-10-30 11:00 · pipeline round 131 · 调度闸被 08:00 tick 活体持锁（其 L2 已判红）⇒ 零派发 + 只读 + 精确定位 L2 红因

> 本轮**零派发、零回收、零 L2、未 push**。与 round 130 同型的「零动作轮次」，但**背景已变**：
> round 130 时对端 L2 还在飞；**本轮对端 L2 已于 11:05 跑完并判红**，红点被本轮精确定位到
> **一个测试文件的 6 处过期断言**（纯测试过期，生产代码正确）。
>
> 与 round 121 / 126 / 130（撞活闸）、round 128（节流拦截）同型。
> **本轮与它们的区别**：节流闸**已放行**（202 分钟 ≥ 100 分钟），容量闸**四项全过** ——
> 拦下本轮的**只有调度闸**（对端活体持锁）。

## 决策摘要

| 项         | 值                                                                                                   |
| ---------- | ---------------------------------------------------------------------------------------------------- |
| 轮次       | **round 131**（2026-10-30 11:00 tick）                                                                |
| 档位       | **reduced（降档）** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过（当前 2026-10-30 11:00）           |
| 车道数     | `LANES` 4 → **2**（减半）；节流闸 `THROTTLE_MIN` 50 → **100** 分钟                                    |
| 台账       | **435 / 465 已交付（93.5%）**，进行中 0，未开发 **30**；`sync.mjs --check` **绿**（✓ 台账与蓝图一致）    |
| 节流闸     | 距上批（08:01:48）**202 分钟** ≥ 阈值 **100** ⇒ ✅ **放行**（round 130 时是 58 分钟被拦，本轮已过）     |
| 调度闸     | `gate status` = `held` / `ageSeconds` **9** / `ttlSeconds` 4200 / **`stale=false`** ⇒ **撞闸，零派发** |
| 温度闸     | `pmset -g therm`：No thermal warning / performance warning / CPU power status ⇒ ✅ 无降频               |
| 配额闸     | running **0** / 配额 **6**（245 条车道**全部** `merged`）⇒ ✅                                        |
| PG 闸      | 11:02 实测带标签 **25**（对端 L2 在飞）；**11:05 L2 结束后复测 = 0**；无标签孤儿 **0** ⇒ ✅ 现已放行   |
| load 闸    | **2.20**（1min）/ 3.19（5min）/ 3.44（15min），阈值 12、核数 10 ⇒ ✅                                  |
| 派发       | **0 条** —— 调度闸拦截（唯一拦截项）                                                                  |
| 回收       | **未执行** —— 闸被占，协议禁止 `merge-ready` / `gc` / `plan` / `heartbeat`；245 条车道全 `merged`，0 `ready` |
| 静态预检   | 本轮**零派发 ⇒ 无新迁移**；全量存量 ① **113** / ② **42** / ③ **30**，与 round 130 **逐项一致** ⇒ 对端本批零新增危险形状 |
| L2         | **本轮未跑**（对端 L2 已跑完且**判红**；协议禁止并发第二个 L2，裁决权归持闸者）                        |
| 说明文件   | 本文件 + `docs/backlog/2026-10-30-l2-red-stale-capability-assertions-after-13-6-2-1.md`               |
| commit     | ✅ 本轮做（说明文件 + backlog + 台账，**同一个 commit**）                                              |
| push       | ❌ **本轮不做** —— **L2 批次闸未绿**（对端 L2 判红）+ 闸被对端持有 ⇒ 按「L2 没绿绝对不 push」不推        |

## 进度（`sync.mjs` 实时输出，非手工计数）

- 总数 **465** · 已交付 **435**（93.5%）· 进行中 **0** · 未开发 **30** ⇒ 距 465 还差 **30** 个
- 分阶段：**D7 已交付 128 / 未开发 27**；**D6 已交付 75 / 未开发 3**；D1–D2 80/0、D5 52/0、D3 40/0、D4 60/0
- 台账完整性复核：`grep -c` = **465**，模块 ID **无重复**，`sync.mjs --check` **退出码 0（绿）**
- 与 round 130 相比**零新增交付**（本轮零派发）—— 435/30 的数字与 round 130 一致。

## 为什么是零派发：调度闸被 08:00 tick 活体持锁

```
gate status  → { "gate": "held", "ageSeconds": 9, "ttlSeconds": 4200, "stale": false }
.dev-agent/dispatch.gate/owner → 73961 / 2026-10-30T00:01:48.869Z（= 08:01:48 +0800）
最新 keepalive → keepalive-ka-112339-43（11:23:39，本轮观测时点前 9 秒）
```

### 五条独立证据证明持闸者活着（不是崩掉后留残骸）

| #  | 证据                                                                                                                     |
| -  | ------------------------------------------------------------------------------------------------------------------------ |
| 1  | **keepalive 持续新建**：`ka-090527-1` → `ka-112339-43`（**43 次**），每 ~100 秒一个，闸目录 mtime 一直新鲜 ⇒ `stale` 恒为 false |
| 2  | **持闸会话仍活着**：peer 会话 `mvs_13c8b63145f84608a3d889c369ffdf94`（08:00 创建）`status: started`；本轮 `session list` 实测 |
| 3  | **它这一轮的 L2 已跑完**：`.tmp/l2-backend.log` 收尾于 **11:05:22**，`BUILD FAILURE`，总耗时 **02:00 h**；进程 PID 78954 已退出 |
| 4  | **PG 容器已随它收摊**：11:02 实测 25 个带 testcontainers 标签（finance 模块在狂起容器）；11:05 后复测 **0 个**，无标签孤儿 **0** ⇒ 不是历史遗留孤儿 |
| 5  | **keepalive 在 L2 结束后仍在续**（11:23:39 还在写）⇒ 它没崩，正处在「L2 判红 → 处理红点」的阶段                                        |

> `owner` 里那个 PID 73961 早已退出 —— 协议早就写明 `gate acquire` 写完 owner 就退出，**owner PID 不可用于判活**。

⇒ 本轮**不取闸、不 force、不清闸、不清容器**。协议对撞闸的处置是硬规定。

## 容量闸四项全过（拦下本轮的**不是机器**，是调度闸）

| 闸         | 阈值      | 实测                                                                                                     | 判定 |
| ---------- | --------- | -------------------------------------------------------------------------------------------------------- | ---- |
| 温度闸     | —         | `pmset -g therm`：No thermal warning / performance warning / CPU power status has been recorded            | ✅    |
| 配额闸     | 6         | running **0**（245 条车道全 `merged`）                                                                    | ✅    |
| PG 闸      | 6         | 11:02 带标签 **25**（对端 L2 在飞）；**11:05 后 = 0**；无标签孤儿 **0**                                  | ✅ 现放行 |
| load 闸    | 12        | **2.20**（1min）/ 3.19（5min）/ 3.44（15min），核数 10                                                    | ✅    |

- **孤儿容器 0** ⇒ 无需清理；11:02 那 25 个**不是孤儿**，是对端 L2 自己起的，**不能清**。
- 节流闸**已从 round 130 的「58 分钟被拦」变为「202 分钟放行」⇒ 本轮若拿到闸，节流闸不再是障碍。

## 🔴 对端 L2 终判：**红** —— 本轮已精确定位根因与修法

### 终判读数（只读观测，裁决权归持闸者）

- `BUILD FAILURE`，完成于 **2026-10-30T11:05:22**，总耗时 **02:00 h**。
- reactor：**backend / tenancy / identity / catalog / channel / warehouse / trade / crm / finance 全 SUCCESS**，
  **reporting FAILURE [02:16 min]**，platform SKIPPED。
- reporting 模块 **585 例，2 failures，0 errors**；**全仓只有一个失败测试类**：
  `com.starcon.reporting.CustomReportIntegrationTest`（34 例中 2 红）。

```
[ERROR] Tests run: 34, Failures: 2, Errors: 0, Skipped: 0 -- in com.starcon.reporting.CustomReportIntegrationTest
[ERROR] CustomReportIntegrationTest.能力声明端点可用
        → JSON path "$.exportAvailable" expected:<false> but was:<true>
[ERROR] CustomReportIntegrationTest.能力声明显式标出未交付的导出与投递
        → [导出属 13.6.2.1，未交付] Expecting value to be false but was true
```

### 根因：**旧模块的断言过期**，不是生产代码缺陷

13.6.2.1（对端本批「导出与订阅」，commit `4f153960`）把能力声明的两个开关翻成了真：

```java
// backend/services/reporting/.../ReportingCustomReportService.java:145
new CustomReportCapabilities(
    true,   // upstreamConnected
    true,   // reportAvailable
    true,   // subscriptionAvailable
    true,   // exportAvailable        ← 13.6.2.1 交付前是 false
    "13.6.2.1 导出与订阅",             // exportOwnerModule
    "",                              // exportUnavailableReason  ← 已清空
    true,   // deliveryAvailable      ← 13.6.2.1 交付前是 false
    "13.6.2.1 导出与订阅",             // deliveryOwnerModule
    "",                              // deliveryUnavailableReason ← 已清空
    ...);
```

对端**自己的** commit 正文写得很清楚：
*「ReportingCustomReportService.capabilities 的 exportAvailable / deliveryAvailable 改 true」*。

而 **13.6.1.1（自定义报表，早先已交付）** 的测试仍断言「未交付」状态 ⇒ **两个测试类互相矛盾**：

| 文件                                              | 行   | 断言                                            | 与新行为 |
| ------------------------------------------------- | ---- | ----------------------------------------------- | -------- |
| `ExportSubscriptionIntegrationTest`（13.6.2.1 新） | 680  | `exportAvailable()).isTrue()`                   | ✅ 绿     |
| `ExportSubscriptionIntegrationTest`（13.6.2.1 新） | 681  | `exportUnavailableReason()).isEmpty()`          | ✅ 绿     |
| `ExportSubscriptionIntegrationTest`（13.6.2.1 新） | 682  | `deliveryAvailable()).isTrue()`                 | ✅ 绿     |
| `ExportSubscriptionIntegrationTest`（13.6.2.1 新） | 683  | `deliveryUnavailableReason()).isEmpty()`        | ✅ 绿     |
| `CustomReportIntegrationTest`（13.6.1.1 旧）      | 285  | `exportAvailable()).isFalse()`                 | ❌ **红** |
| `CustomReportIntegrationTest`（13.6.1.1 旧）      | 865  | `jsonPath("$.exportAvailable").value(false)`    | ❌ **红** |

⇒ **生产行为是正确的**（能力声明如实反映「导出与投递已交付」），**要改的是旧测试的过期预期**。

### ⚠️ 报告只显示 2 failures，实际要改 **6 处**（AssertJ 首个失败即中止）

同一个测试方法里，断言**在第一处失败就抛异常中止**，后面的断言**根本没执行** ——
所以 surefire 只报 2 failures，实际过期面是 **6 处**（若只改这 2 处，重跑会再红）：

| #  | 位置                     | 现值                                   | 应改为                                     |
| - | ------------------------ | -------------------------------------- | ------------------------------------------ |
| 1  | `CustomReportIntegrationTest.java:285` | `exportAvailable()).isFalse()` | `isTrue()`                                 |
| 2  | `CustomReportIntegrationTest.java:287` | `exportUnavailableReason()).isNotBlank()` | `isEmpty()`（与新测试 L681 对齐） |
| 3  | `CustomReportIntegrationTest.java:288` | `deliveryAvailable()).isFalse()` | `isTrue()`                                 |
| 4  | `CustomReportIntegrationTest.java:290` | `deliveryUnavailableReason()).isNotBlank()` | `isEmpty()`（与新测试 L683 对齐） |
| 5  | `CustomReportIntegrationTest.java:865` | `jsonPath("$.exportAvailable").value(false)` | `value(true)`                      |
| 6  | `CustomReportIntegrationTest.java:866` | `jsonPath("$.deliveryAvailable").value(false)` | `value(true)`                     |

**不用改的**（已实测通过）：

- L286 / L289：`exportOwnerModule()` / `deliveryOwnerModule()` `contains("13.6.2.1")` —— 归属模块没变，仍通过。
- L815：`result.note()).contains("未交付")` —— 那是 `run()` 的 note（不是能力声明），仍通过。

**建议同步改的文案**（不改也能过，但会自相矛盾）：

- L283 方法名 `能力声明显式标出未交付的导出与投递` → 应改为反映「已交付」的表述。
- L285 / L288 的 `.as("…属 13.6.2.1，未交付")` 说明文案。

### 修复性质与修法

- **纯测试过期**：改动面 = **1 个测试文件**、**0 生产代码**、**0 迁移**、**0 端点**、**0 页面**。
- 这是**跨车道交付顺序**造成的必然现象：13.6.1.1 交付时「导出与投递未交付」是真的，
  13.6.2.1 交付后它变成假 —— **后交付的模块必须回头更新先交付模块里「显式标出未交付」的断言**。
- 修复车道命名建议：`fix-13-6-1-1-capability-assertion`（分支 `fix/13-6-1-1-capability-assertion`），
  与 `lanes.json` 里已有的 8 条同类 `fix-*` 车道同构（例：`fix-catalog-timestamp-assertion-clock`、
  `fix-identity-saasusage-flaky`）。
- **顺序**：按 round 107 的先例 —— **先派修复车道并入，再重跑 L2**，不要在集成头上直接改。

## 静态预检：零新增危险形状

```bash
grep -rn 'ADD COLUMN' backend/services/*/src/main/resources/db/migration/ | grep -v 'DEFAULT'   # 113
grep -rn 'SET NOT NULL' backend/services/*/src/main/resources/db/migration/                        # 42
grep -rn 'ADD CONSTRAINT.*CHECK' backend/services/*/src/main/resources/db/migration/                # 30
```

| 迁移                            | ① ADD COLUMN 无 DEFAULT | ② SET NOT NULL | ③ ADD CONSTRAINT CHECK | CREATE TABLE | ALTER TABLE |
| ------------------------------- | ----------------------- | -------------- | --------------------- | ------------ | ----------- |
| `V409__board_builder.sql`       | **0**                   | **0**          | **0**                 | 6            | 2（仅 RLS 开关） |
| `V410__export_subscription.sql` | **0**                   | **0**          | **2**                 | 2            | 4（2 条 CHECK + 2 条 RLS 开关） |

- 三个存量计数（113 / 42 / 30）与 **round 130 逐项一致** ⇒ **对端本批零新增危险形状**。
- `V410` 的 2 条 CHECK 都加在 **`V410` 自己 `CREATE TABLE` 新建的表**上 ⇒ 不是给别人的老表加约束，
  不存在「老 INSERT 被新约束挡住」的跨车道违约形状。
- ⇒ **本批 L2 的红点与迁移形状无关**，与静态预检的结论一致（**红因是测试过期，不是互相破坏**）——
  这也意味着**不需要 `git bisect`**：红点已由 surefire 报告直接定位到单个测试类与单行断言。

## L2：本轮未跑（两条独立理由）

1. **协议禁止并发第二个 L2** —— `PIPELINE_CRON_PROMPT.md`「最高优先级禁令」：
   *「L2 不得与在途车道的 L1 并发跑 …… 先等在途车道落地，再跑 L2」*。
   本轮对端的整批 L2 从 09:04:57 跑到 11:05:22，**在 11:05 之前随时可能撞上** ⇒ 再起一个 `./mvnw verify`
   ⇒ PG 峰值翻倍 ⇒ **成片假红**（503 / 连接拒绝）。**干扰的代价不对称** —— 宁可这轮不跑。
2. **本轮无新合并 ⇒ 无新风险面** —— `merge-ready` 未执行（闸被占），集成头的**业务代码**自对端本批并入后**零变更**；
   L2 的职责是「抓跨车道互相破坏」，对端已经抓到了，且本轮已把红点定位清楚。

## 收尾三件套（本轮逐件点名）

| 件       | 状态      | 说明                                                                                                                                 |
| -------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| ① 写说明 | ✅ 完成   | 本文件（round 131 决策摘要 + 闸/容量取证 + **L2 红点精确根因与 6 处修法** + 静态预检 + 后续待办）+ backlog 红因条目 |
| ② commit | ✅ 完成   | 说明文件 + backlog 条目 + 台账**同一个 commit**（台账净改动 = 无，`sync.mjs --check` 绿）                                            |
| ③ push   | ❌ **本轮不做** | **L2 批次闸未绿**（对端 11:05 判红）⇒ 按「L2 没绿绝对不 push」不推。**15 个 commit + 本轮说明留在集成头本地，随修复后的绿批次一起 push** |

## 后续待办

1. **对端（持闸的 08:00 tick）**：
   派 1 条修复车道（**只改** `backend/services/reporting/src/test/java/com/starcon/reporting/CustomReportIntegrationTest.java`
   的 6 处断言 + 2 处文案，**不碰生产代码**）→ 并入 → **重跑 L2** → 绿了才写 **batch-115** 说明
   （覆盖 `13.6.1.2` 看板搭建 + `13.6.2.1` 导出与订阅）→ commit → `git push origin integration/module-dev`
   ⇒ **一次推走 16 个 commit**。
   ⚠️ **batch 编号**：本轮**未占用** `batch-115`（本轮零交付），该号仍留给对端本批。
   ⚠️ **本轮已把红点定位到行**，无需 `git bisect`（协议允许最多 2 次全量，二分可省）。
2. **下一轮 tick**：核对 `origin/integration/module-dev` 是否已追上本地 HEAD ⇒ 确认积压清零。
3. **30 个未开发模块**继续排；`lane.mjs plan` 会自行按依赖深度挑选，**不要手工指定模块**。
4. **不自行改节流闸 / `--lanes` / 配额 / load 阈值** —— 那是用户的决定，调度者只报不改。
5. **观察项**：对端 L2 跑了 **02:00 h**（远超协议估的 25–40 分钟）。若修复后的重跑仍然这么久，
   说明 `mvnw verify` 的耗时结构需要单独排查（channel 1:14 h、trade 17 min 是大头），
   但**改构建编排属于代码改动，不在本轮调度者职责内** —— 建议下一轮视情况记入 `docs/backlog/`。

## 附：本轮执行过的命令（除 `sync.mjs` 回写台账与本轮文档提交外全部只读）

```bash
node tools/module-progress/sync.mjs                 # 同步台账（写 module-progress.md，净改动 0）
node tools/module-progress/sync.mjs --check          # ✓ 台账与蓝图一致（退出码 0）
node tools/module-progress/lane.mjs status           # 台账快照 + 245 条车道（全 merged）
node tools/module-progress/lane.mjs gate status      # held / stale=false
pmset -g therm ; uptime ; sysctl -n hw.ncpu
docker ps --filter "ancestor=postgres:16-alpine" --format '{{.Names}}\t{{.Label "org.testcontainers.sessionId"}}'
ps -o pid,ppid,lstart,command -p 78954              # 对端 L2 的 maven 进程与其父 bash
find backend -path '*surefire-reports/*.txt' -newermt '2026-10-30 09:04' -exec grep -lE '<<< (FAILURE|ERROR)!' {} \;
grep -nE 'BUILD (SUCCESS|FAILURE)|Reactor Summary' .tmp/l2-backend.log
git rev-list --count origin/integration/module-dev..HEAD   # 15
grep -rn 'ADD COLUMN|SET NOT NULL|ADD CONSTRAINT.*CHECK' backend/services/*/src/main/resources/db/migration/
```
