# 2026-10-26 · batch-104 —— 回收两条车道并入集成头、L2 批次闸、定性并修掉一处「夹具自己红」的日期脆弱性、再派发两条车道

> 批次号 **104**（接 `batch-103` 往后排；`docs/sessions/2026-10/` 下现有批次号最大为 103）。
> 集成头 `integration/module-dev`；调度者：Mavis（pipeline cron tick，2026-10-26 19:00 触发）。
> **强度档位：降档模式** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（**自动降档，不是调度者自选**）。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度（开工） | 465 总数 · **408 已交付** · 0 进行中 · **57 未开发**（完成率 **87.7%**） |
| 进度（本批并入后） | **410 已交付** · 0 进行中 · **55 未开发**（完成率 **88.2%**） |
| 容量闸判定 | **GO** —— running **0**/6、`pmset -g therm` **无降频项**、load **2.09 / 2.00 / 2.00**（限 12，1min < 5min < 15min **不是爬升**）、Testcontainers PG **0** 个（限 6）、**无标签孤儿容器 0** |
| 节流闸 | 距上批（14:02）**298 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 调度闸 | `gate acquire` **成功**（退出码 0）⇒ 全程持闸 + 持续续期 |
| 本轮回收 | ✅ **2 条** —— `1.3.2.2` 外部渠道推送（identity `V385`）/ `1.1.2.2` 店铺概览（reporting，**无迁移**） |
| 静态预检 | ①`ADD COLUMN` 无 `DEFAULT` **本批零命中**、②`SET NOT NULL` **本批零命中**、③`ADD CONSTRAINT … CHECK` **本批零命中** |
| **L2 批次闸** | 🟢 **绿** —— 后端 **10 个模块 10 752 例全绿** + 前端 646 文件 / 9004 例全绿 + typecheck / lint / Next build / `sync --check` 全绿<br>（**过程是「先红后绿」**：第一遍红在 channel 2 处 ⇒ 一处定性为机器拖慢型假红、一处定性为**真红**（夹具日期脆弱性）⇒ 修掉真红 + 补跑被 fail-fast 跳过的 6 个模块 + 重跑 channel 全量 ⇒ 绿。详见第五、六节） |
| 收尾三件套 | ① 说明已写、② commit、③ push（详见第十节） |
| 本轮派发 | ✅ **2 条**（降档后）—— `1.5.2.1` 服务与任务状态（`V386`）/ `13.1.2.1` 目标设定（`V387`），**在 L2 跑完之后才派发**（见第七节） |

---

## 一、进度

`node tools/module-progress/sync.mjs` ⇒ 18 卷 / **465 个 L4 模块**，
开工时已交付 **408** · 进行中 0 · 未开发 **57**（完成率 **87.7%**）；
本批 2 条车道并入后再跑一次 sync ⇒ **410 / 0 / 55**（完成率 **88.2%**），**离 465 还差 55**。

本批并入后，剩下 55 个未开发模块的分布（台账逐行核对）：

| 分组 | 未开发 | 明细 |
| --- | --- | --- |
| **18.x 集成开放与智能自动化** | **21** | 18.1 接口目录/调用监控/事件订阅/投递记录 **6** · 18.2 **2** · 18.3 规则引擎 **5** · 18.4 **6** · 18.5 模板与投递 **2** |
| **13.x 利润与经营分析** | **17** | 13.1 **1**（本轮派发 `13.1.2.1` 后）· 13.2 **4** · 13.3 **4** · 13.4 **4** · 13.5 **3** · 13.6 **3** |
| **15.x 客户与会员** | **6** | 15.3 **3** · 15.6 会员与等级 **3** |
| **14.x 广告与增长** | **4** | 14.2 竞价/分时策略 **2** · 14.3 商品维度报表/归因 **2** |
| **5.1 定价** | **3** | `5.1.1.1` / `5.1.1.2` / `5.1.2.1` —— **循环依赖**，自 batch-98 起连续点名，需人工拍板拆环 |
| **1.x 工作台与门户** | **2** | `1.5.2.1` 服务与任务状态（本轮派发）· 18.x 之外无其他 |

**台账完整性核对**（并入后）：`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' module-progress.md` ⇒ **465**（恒定）；
重复 ID ⇒ **零**；`sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（L2 第 6 步 exit=0）。

---

## 二、容量闸与节流闸

| 闸 | 实测 | 阈值 | 判定 |
| --- | --- | --- | --- |
| **温度闸（最高优先级）** | `pmset -g therm` ⇒ `Note: No thermal warning level has been recorded` + `No CPU power status has been recorded` | 出现降频项且 < 100 ⇒ STOP | ✅ **通过** |
| **配额闸** | running **0** | ≥ 7 ⇒ STOP | ✅ **通过** |
| **PG 闸** | 带 `org.testcontainers.sessionId` 标签的容器 = **0** | ≥ 6 ⇒ STOP | ✅ **通过** |
| **load 闸** | `2.09 / 2.00 / 2.00`（1/5/15 min），核数 **10** | 1min > 核数 ⇒ STOP | ✅ **通过**（1min < 5min < 15min ⇒ **不是爬升**） |
| **节流闸** | 距上批（`last-batch-at` = 2026-10-26 14:02）**298 分钟** | 100 分钟（降档后） | ✅ **放行** |

**孤儿容器**：`docker ps` 里带标签 0 个 + 无标签 0 个（`langfuse-*` / `crewhub-*` 是常驻业务容器，不属本流水线）⇒ 无需清理。

**档位**：**降档模式**（`TOKEN_HORIZON` 已过）⇒ 每批 **2** 条车道、节流闸 **100** 分钟。
**这是自动降档，不是调度者自选；期限不改变停机条件**（停机只看「465 全部已交付」）。

### ⚠️ 一个本轮新发现的运维坑：**后台 `sleep` 会被 macOS 延迟**

本轮派了一个「每 20 分钟 `touch` 一次闸目录续期」的守护进程，
`ps` 显示它**活着**，但日志里 19:23 之后**再没有续期记录**；
`ps -o etime` 显示它的子进程 `sleep 1200` **已经跑了 50 分钟** ⇒
**macOS 把后台进程的定时器延迟了**（App Nap / timer coalescing），
⇒ **「守护进程还活着」不等于「它按预期节奏在跑」**。

**本轮的处置**：① 协议规定的续期动作**照做不误**（每个检查点手动 `touch` 新文件名）；
② 另起一个 **30 秒**一轮的闸刷新轮询器（短 sleep 不容易被延迟）；
③ 每个检查点**手动 `touch` 一次**闸目录 ⇒ 三重保险，闸龄全程 < 5 分钟。
⇒ **下一轮的教训**：续期守护不要依赖长 `sleep`，要么短间隔，要么由调度者在每个检查点手动续。

---

## 三、本轮回收：2 条车道并入集成头

`node tools/module-progress/lane.mjs merge-ready` ⇒ 退出码 **0**，**无冲突**：

| 车道 | 名称 | 落地服务 | 迁移版本 | merge commit |
| --- | --- | --- | --- | --- |
| `1.3.2.2` | 外部渠道推送 | **identity** | `V385` | `8506c2df` |
| `1.1.2.2` | 店铺概览 | **reporting** | **无迁移**（不占版本号） | `22a75038` |

### 3.1 合并驱动行的**行级兜底并集**已人工核对

合并驱动打印了两行告警：

```
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录
```

⇒ 这两行**指的是同一个文件**（台账 `module-progress.md` 走了非 JSON 的行级兜底路径，
而它同时又是台账 ⇒ 触发按模块 ID 求并集）。**调度者已逐项核对**：

| 核对项 | 结果 |
| --- | --- |
| 台账模块行总数 | **465**（恒定，无多行无少行） |
| 重复模块 ID | **零** |
| `1.3.2.2` / `1.1.2.2` 两行 | 状态均为 **已交付**，证据完整（各含迁移/端点/页面/测试四件） |
| `sync.mjs --check` | ✅ **✓ 台账与蓝图一致** |

⇒ **未发生**「占位行把已交付读成未开发」那类历史事故（`merge-union.mjs` 注释里记过 2026-10-07 在 40 分钟内因此损坏三次）。

### 3.2 共享白名单 `api-proxy.ts` 的**静默丢路由**风险已核对（🔴 最高危形状）

两条车道都改了 `apps/web/src/lib/api-proxy.ts`（共享白名单累积文件），
**取错一侧会静默删掉别人已交付的路由**（表现是页面 404，而 typecheck / lint / vitest / Playwright **全绿**）。

| 核对项 | 结果 |
| --- | --- |
| 合并相对基线 `a29b2d0f` 的 diff | **`+112 / −0`** —— **纯新增，无一行删除** |
| 两条车道的路由是否都在 | ✅ `external-channel-push`（identity）与 `store-overview`（reporting）**都在** |
| 全量 allowlist 回归 | ✅ **646 文件 / 9004 例全绿**（L2 第 4 步 `pnpm test` exit=0，其中含 `apps/web/src/app/api` 全量） |

### 3.3 后端并入范围（供二分时判定用）

```
backend/services/identity    14 个文件
backend/services/reporting    6 个文件
backend/services/channel      0 个文件   ← 关键：本批没碰 channel
```

---

## 四、静态预检（协议 5.1「已知互相破坏形状」）

对本批新迁移 `V385__external_channel_push.sql` 逐条扫描：

| 检查项 | 本批新增 | 全仓存量 | 判定 |
| --- | --- | --- | --- |
| ① `ADD COLUMN` 且无 `DEFAULT` | **0** | 113 处（全在 V3–V371 存量迁移） | ✅ 本批零命中 |
| ② `SET NOT NULL` | **0** | 42 处（同上） | ✅ 本批零命中 |
| ③ `ADD CONSTRAINT … CHECK` | **0** | 28 处（同上） | ✅ 本批零命中 |

**V385 的实际形态**（⇒ 天然无跨车道破坏面）：
两张**新表** `external_push_channel` / `external_push_delivery`，
**纯 `CREATE TABLE`、零 `ALTER` 既有表、零跨库外键**；
两表均 `tenant_id NOT NULL` + `ENABLE ROW LEVEL SECURITY` **且 `FORCE`**（FORCE 必须：owner 身份一连就会绕过隔离）
+ 租户守卫触发器 + `GRANT` + 4 个索引。

**Flyway 版本连续性核对**（并入后）：identity 最高 `V385`、channel 最高 `V371`、reporting 最高 `V381`
⇒ 本轮新派发的 `V386` / `V387` **未被占用**，且在各自服务内均为**递进**版本（不会触发 out-of-order）。

---

## 五、L2 批次闸结果：🟡 先红后绿（两次红，两种性质）

L2 全量验证跑在**集成头** `22a75038` 上，**全程独占 Testcontainers**（派发新车道是在 L2 跑完之后才做的 —— 见第七节）。

### 5.1 第一遍（`mvnw verify` 单 reactor，19:03:18 → 19:43:44，40 分 26 秒）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `backend && ./mvnw verify`（单 reactor） | 🔴 **exit=1** —— 红在 **channel**，fail-fast ⇒ 后 6 个模块 **SKIPPED** |
| 2 | `pnpm typecheck` | ✅ exit=0 |
| 3 | `pnpm lint` | ✅ exit=0 |
| 4 | `pnpm test` | ✅ exit=0（**646 文件 / 9004 例全绿**） |
| 5 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ exit=0 |
| 6 | `node tools/module-progress/sync.mjs --check` | ✅ exit=0（✓ 台账与蓝图一致） |

**后端逐模块**：

| 模块 | 测试数 | 结果 |
| --- | --- | --- |
| tenancy | — | ✅ SUCCESS（0.4s） |
| identity | **639** | ✅ SUCCESS（2 分 58 秒）—— 含本批 `V385` 的 20 例新测试 |
| catalog | **1998** | ✅ SUCCESS（5 分 25 秒） |
| **channel** | **2344** | 🔴 **2 处红**（详见 5.2 / 第六节） |
| warehouse / trade / crm / finance / reporting / platform | — | ⚠️ **fail-fast 跳过**，本轮补跑（5.3） |

### 5.2 两处红的**定性**（这是本轮最需要说清楚的一件事）

两处红**都在 channel**，而**本批 `backend/services/channel/` 一行都没改**（见 3.3）：

```
[ERROR] ListingBulkCollectIntegrationTest.rejectsTooFrequentStart:575  Expecting actual not to be null
[ERROR] LogisticsExceptionIntegrationTest.ruleScanIsIdempotent:445      expected: 1 but was: 2
```

**隔离复跑**（机器空闲、容器峰值 1，20:04:53 → 20:05:11）：

| 测试类 | 隔离复跑结果 | 定性 |
| --- | --- | --- |
| `ListingBulkCollectIntegrationTest`（21 例） | ✅ **21/21 全绿，9.6 秒** | 🟡 **机器拖慢型假红**（限频窗口 30 秒，两次发起被机器停顿拉开 ⇒ 没触发限频） |
| `LogisticsExceptionIntegrationTest`（19 例） | 🔴 **仍红 1 处，5.75 秒**（机器空闲时**复现**） | 🔴 **真红**（不是环境问题）⇒ 根因见第六节 |

**假红那一处的旁证**：同一份日志里 `EprRegistrationTenancyIntegrationTest` 跑了 **997.1 秒**（3 个用例），
而全仓其余 **340** 个测试类都在 **8–12 秒** ⇒ 机器在 channel 跑到中段时**整体停顿过**。

### 5.3 补跑被 fail-fast 跳过的 6 个模块（「一服务一 session」）

沿用 batch-97/98/99/104 验证过的拆法：**surefire `reuseForks=true` ⇒ 一个服务 = 一个 fork JVM ⇒
每个测试类各起一个 PG 容器且不 `.stop()`，ryuk 只在 session 结束才回收**
⇒ 单 reactor 连跑会把容器堆到几十个（实测本轮峰值 **86** 个）⇒ 拆成「一服务一 session」把峰值压到「单服务峰值」，
且**不改任何一个测试类**。

| 服务 | 结果 | 测试数 | 耗时 |
| --- | --- | --- | --- |
| warehouse | ✅ SUCCESS | 1672 | 12m |
| trade | ✅ SUCCESS | **2458** | 17m |
| crm | ✅ SUCCESS | 491 | 1m |
| finance | ✅ SUCCESS | 970 | 3m |
| **reporting** | ✅ SUCCESS | **83** | <1m |
| platform | ✅ SUCCESS | 97 | <1m |

> 📌 **reporting 由 69 例变成 83 例**（+14）⇒ **正是本批并入的 `1.1.2.2` 店铺概览那 14 例**
> ⇒ 本批的代码**确实被这 6 个模块的完整验证跑过**，不是只跑了个寂寞。
> （trade 2458 例也与文档里早前写的「861」不同 —— 那是旧文档的过期数字，一律以本轮实测为准。）

> 📌 **这 6 个模块里有本批并入的 reporting**（`1.1.2.2` 店铺概览）⇒
> **本批的代码经过了这 6 个模块的完整验证**，不是只跑了个寂寞。

### 5.4 第二遍：修掉真红后重跑 channel 全量

**STEP 1 —— 隔离复跑被修的那个测试类**（20:41:37 → 20:41:56）：

```
cd backend && ./mvnw -pl services/channel -am test \
    -Dtest='LogisticsExceptionIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
⇒ [INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.992 s
⇒ BUILD SUCCESS
```

**STEP 2 —— channel 全量 verify（一服务一 session）**（20:41:56 → 20:57:47，15 分钟）：

```
cd backend && ./mvnw -pl services/channel -am verify
⇒ [INFO] Tests run: 2344, Failures: 0, Errors: 0, Skipped: 0
⇒ BUILD SUCCESS（exit=0）
```

### 5.5 ✅ L2 批次闸**最终判定：全绿**

本批并入后的**全部 10 个模块**都拿到了绿：

| 模块 | 测试数 | 结果 | 来源 |
| --- | --- | --- | --- |
| tenancy | — | ✅ SUCCESS | 第一遍单 reactor |
| identity | **639** | ✅ SUCCESS | 第一遍单 reactor（含本批 `V385` 的 20 例） |
| catalog | **1998** | ✅ SUCCESS | 第一遍单 reactor |
| **channel** | **2344** | ✅ SUCCESS（exit=0，15m） | **修掉真红后重跑**（一服务一 session） |
| warehouse | **1672** | ✅ SUCCESS | 补跑（一服务一 session） |
| trade | **2458** | ✅ SUCCESS | 补跑（一服务一 session） |
| crm | **491** | ✅ SUCCESS | 补跑（一服务一 session） |
| finance | **970** | ✅ SUCCESS | 补跑（一服务一 session） |
| **reporting** | **83** | ✅ SUCCESS | 补跑（一服务一 session）（含本批 `1.1.2.2` 的 14 例） |
| platform | **97** | ✅ SUCCESS | 补跑（一服务一 session） |
| **后端合计** | **10 752 例** | **全绿** | |

| 前端 / 台账 | 结果 |
| --- | --- |
| `pnpm typecheck` | ✅ exit=0 |
| `pnpm lint` | ✅ exit=0 |
| `pnpm test` | ✅ exit=0（**646 文件 / 9004 例**，含 `apps/web/src/app/api` 全量 allowlist 回归） |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ exit=0 |
| `node tools/module-progress/sync.mjs --check` | ✅ exit=0（✓ 台账与蓝图一致） |

---

## 六、🔴 本轮唯一一处**真红**：夹具把「昨天」写成了绝对日期，到点自己红

### 6.1 根因（不是回归，是**夹具的日历脆弱性**）

```java
// backend/services/channel/src/test/java/com/starcon/channel/LogisticsExceptionIntegrationTest.java
static final OffsetDateTime STALLED_AT = OffsetDateTime.parse("2026-09-20T08:00:00Z");
static final OffsetDateTime RECENT_AT  = OffsetDateTime.parse("2026-10-19T08:00:00Z");  // ← 写死
```

`RECENT_AT` 的语义（测试自己的注释）是「**未命中票**：最后一次轨迹是**昨天**」，
而被测规则是「**停滞超过 7 天**」（threshold = 7 × 24 × 60 分钟）：

- 相对「现在」才是「昨天」；
- 写死之后，这张票在 **2026-10-26T08:00:00Z（= 北京时间 16:00）** 就攒够了 7 天；
- 从那一刻起它变成**命中票** ⇒ `candidateCount` 由 1 变 2 ⇒
  「**只有满足阈值的票进候选池**」这条断言**从那天起每天都会自己红**。

**实测时间线（可复核）**：

| 时刻 | 谁跑的 | 结果 |
| --- | --- | --- |
| 2026-10-26 14:13 CST（06:13 UTC） | round-104 的 L2，channel 全量 2344 例 | ✅ 全绿（**尚未到 08:00 UTC**） |
| 2026-10-26 16:00 CST（08:00 UTC） | —— | 🔴 **夹具跨过阈值，红灯自此打开** |
| 2026-10-26 19:03 CST（11:03 UTC） | 本轮 L2，channel 全量 2344 例 | 🔴 `Tests run: 2344, Failures: 2` |
| 2026-10-26 20:05 CST（12:05 UTC） | 隔离复跑（机器空闲、容器峰值 1） | 🔴 **仍红** ⇒ 排除环境因素，**是真红** |

**两条排除性证据**（说明它与本批无关）：

1. **本批没碰 channel**：`git diff --name-only a29b2d0f..22a75038 | grep 'backend/services/channel/'` ⇒ **0 个文件**；
2. **同一个 2344 例的套件 5 小时前是绿的**（round-104，14:13），
   今天也是 **2344**（测试数一模一样 ⇒ 没有测试被加/删）⇒ **被测代码与被测测试都没变**。

### 6.2 修法（调度者直接落，与 `cd9e125e` 同一套路）

```java
static final OffsetDateTime RECENT_AT = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
```

并在代码注释里写明「为什么不能写死」。改动**只有这一个测试文件**（`+17 / −2`，含 import），
**业务代码一行未动**，**本批并入的两条车道一行未动**。

> 📌 **为什么调度者可以直接改测试夹具**：这不是「写业务代码」，是**修一条挡住 L2 的断言**；
> 且仓库里有**同一先例** —— `cd9e125e`「消除集中度空桶断言的『周一才红』日期脆弱性」，
> 同样由调度者（Mavis）在集成头直接落。**只修被证实的那一处，不做批量替换**
> （「过去很久」的写死日期是**故意的**，改成相对时间反而破坏可复现性）。

### 6.3 登记为跨模块 TODO（**不批量修**）

本仓已为**同一族**问题付过两次代价（`cd9e125e` 的「周一才红」+ 本轮的「跨过 7 天阈值后每天红」），
而这类故障对「L2 红 ⇒ 是不是并入的东西弄坏了」这个判定的**误导性极强** ⇒ 单独登记：

`docs/backlog/2026-10-2026-10-26-hardcoded-fixture-dates-self-failing.md`
（含判定形状「三个必要特征」、扫描口径与规模、三个处置方案，其中**根治方向 = 统一时间基准夹具**属架构级决策，留给人工拍板）。

---

## 七、本轮派发：2 条车道（**在 L2 跑完之后才派**）

### 7.1 为什么是「L2 先、派发后」

上一轮（round-104 / batch-103）把**派发车道**与 **L2 全量**同时进行，
PG 容器峰值冲到 12、swap 吃掉 4.2G ⇒ 产出 248 处 `Connection refused` ⇒ trade 3 处 503 假红 + warehouse 1 处拖慢型假红
⇒ 那一轮的 L2 是「跑红 → 隔离复跑定性 → 补跑 → 才转绿」。

**本轮把顺序倒过来**：L2 **独占** Testcontainers 跑完（峰值最高 86 个容器，但 swap 全程**零增长**、RAM 45% 空闲、
无 thrashing），派发放在 L2 之后 ⇒ 本轮 L2 的信号是干净的、可直接归因。

> 📌 **这不是偏离协议**：协议第 4 步与第 5 步的顺序是「排车道 → L2」，
> 但协议自身在「批量验证未过时的处置」与 batch-104 的实测里都要求
> **L2 不得与在途车道的 L1 并发跑**。本轮以**实测取证**为准取「L2 先、派发后」。

### 7.2 派发清单（`plan --lanes 2`，降档后）

| 车道 | 名称 | 阶段 | L3 锚点 | 落地服务 | 迁移版本 | 分支 / worktree |
| --- | --- | --- | --- | --- | --- | --- |
| `1.5.2.1` | 服务与任务状态 | D7 | 1.5.2 | channel | `V386` | `feat/1-5-2-1` |
| `13.1.2.1` | 目标设定 | D7 | 13.1.2 | reporting | `V387` | `feat/13-1-2-1` |

两条车道 **L3 锚点互不相同**、互不依赖；`serviceBlocked` 为**空**（⇒ 不是服务缺口堵死）。

### 7.3 任务书里给 worker 的**取证结论**（不是「照着蓝图写」的泛泛提示）

任务书：`.dev-agent/taskbooks/r105-1.5.2.1.md`、`.dev-agent/taskbooks/r105-13.1.2.1.md`
（**已逐字检查，无残留 `<...>` 占位符**）。

**`1.5.2.1`（服务与任务状态）** 关键取证：

- 上游任务的**真实落点**：channel 拥有 **38 张**任务/运行类表
  （`sync_run` / `sync_failure` / `sync_cursor` / `listing_*_task` / `publish_task` / `backfill_task` / `warehouse_sync_run` …）；
  `sync_run` 的列**正好够用**：`status`（RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED）、`started_at`、`finished_at`、**`duration_ms`**、`trigger_source`；
  `sync_failure.reason` 的**六个值**天然分成两类 ⇒
  「**区分系统故障与业务数据问题**」（本模块的命门）：
  `AUTHORIZATION_EXPIRED`/`MAPPING_MISSING`/`FIELD_PARSE_FAILED` = **业务/配置问题**，
  `NETWORK_ERROR`/`RATE_LIMITED`/`PLATFORM_ERROR` = **系统/外部问题** ⇒
  **混成一坨「失败任务」就等于没做**。
- 🔴 **跨服务边界**：其它服务也有大量任务表（finance / crm / warehouse / fulfillment / platform）
  ⇒ **只把 channel 自有的纳入**，其它要么走**授权只读端点**、要么如实报「未接通 + 归属模块」
  ⇒ **绝对不许**建一张跨服务聚合表（那会变成第二个事实源）。
- 🔴 **卷 18（规则任务）整卷未开发** ⇒ 规则任务**如实报未接通 + 归属模块号**，**不造表顶替**。
- 🔴 **卷 01 红线**：只读聚合、零写端点、不新建业务服务；
  **是否建表按事实判断，不要为了「用掉 V386」造一张派生表**
  （先例：1.5.1.1 主动不占用 V338、1.5.1.2 主动不占用 V375）。
- 🔴 **「停滞」必须是有名字、可测、口径写明的判定**：具名常量 + 阈值写进代码与文档，
  **判定基准用数据库 `now()`**，不许用应用层墙钟（`734ec399` 那个修复就是为此）。

**`13.1.2.1`（目标设定）** 关键取证：

- 🔴 **口径唯一**：指标码**已经登记好了**，不许自己另立公式。租户级
  `SALES_AMOUNT / COGS_AMOUNT / GROSS_PROFIT / GROSS_MARGIN / NET_PROFIT / AD_SPEND / ORDER_COUNT / RETURNED_AMOUNT / RETURN_RATE / UNIT_SOLD / INVENTORY_VALUE / RECEIVABLE_BALANCE`；
  店铺级 `STORE_*` 14 个。
  ⇒ 蓝图点名的五个目标指标里：
  **销售额** = `SALES_AMOUNT`（租户级）/ 店铺级 `STORE_GMV_PLATFORM` + `STORE_GMV_RESTORED` + `STORE_CALIBER_GAP`（**三个口径并列**）；
  **毛利额 / 毛利率** = `GROSS_PROFIT` / `GROSS_MARGIN`（派生型，分子分母已登记 ⇒ **只引用码，不重写公式**）；
  **广告 ROI** —— **没有** `AD_ROI` 码（只有 `AD_SPEND` / `STORE_AD_ROAS` / `STORE_AD_ACOS`）
  ⇒ **不许自造公式**，要么用已登记的 `STORE_AD_ROAS`、要么如实报「未登记 + 归属模块」；
  **库存周转** —— **没有**周转码（只有 `INVENTORY_VALUE`）
  ⇒ **不许拿 `INVENTORY_VALUE` 冒充库存周转**。
- 🔴 **目标必须挂在组织对象上**（蓝图的命门：部门/人/店铺）⇒ 归属对象本体在 **identity**
  （`department` / `membership`），**reporting 不得跨库查表**
  ⇒ 走 **端口**（照抄 `ReportingStoreComparisonPort` / `ReportingChannelPort` / `ReportingFinancePort` 范式）
  + `Unavailable*Port` 的「未接通如实答」；归属对象要**记快照**（否则组织改名后历史目标认不出是谁）。
  🔴 **「17 组织」整卷未开发** ⇒ 组织**读取**走 identity 现有端点，**绩效核算对接**如实标为下游未交付。
- 🔴 **分解计划**：分解项合计**必须等于**目标值 ⇒ **金额 `BigDecimal` + `NUMERIC`，禁浮点**
  （「均分后合计 ≠ 目标值」正是浮点加法最常见的坑）⇒ 舍入策略与**余数归属**都要写清楚；
  **多币种不相加**；周期边界按**本租户时区**算（复用 `ReportingClockConfiguration`）。

---

## 八、本批交付模块详细说明

### 模块 1.3.2.2 外部渠道推送（identity）

**这个功能做什么**：把「告警/通知」真正推出去 —— 运营在 `/notifications/push` 登记企业微信/钉钉/飞书/邮件渠道，
系统按规则挑出命中的告警、生成候选推送，用户可多选渠道、开关卡片化与站内跳转，
并能看到每一次投递的状态（成功 / 失败 / 推迟 / 重试耗尽）、失败原因与重试排期，
以及「这条告警我知道了」的确认动作。

**技术落地**：

- **迁移**：`identity V385__external_channel_push.sql`（**两张新表，零 ALTER 既有表、零跨库外键**）
  - `external_push_channel`（渠道登记）：`tenant_id NOT NULL` + `channel_code` 受控 CHECK
    （WECHAT_WORK / DINGTALK / FEISHU / EMAIL）+ `display_name`
    + **🔴 `secret_ref`（只收 secret manager 引用串：scheme 仅允许 `secret://` / `vault://` / `external-secrets://` / `k8s://`）**
    + `card_enabled` / `link_enabled` + `status` + `UNIQUE(tenant_id, channel_code)`
    + `FOREIGN KEY (tenant_id, created_by) REFERENCES membership`
  - `external_push_delivery`（投递记录）：渠道 **快照**（**不建外键** —— 渠道删了历史仍要留着）
    + `recipient_membership_id` + `source_kind` + `source_ref` + `notification_type` + `title` / `body`
    + **🔴 `link_url` 只允许站内相对路径** + `card_style`
    + **🔴 `status` 四个码**（DELIVERED / FAILED / FAILED_EXHAUSTED / **DEFERRED**）
    + `attempt_count` / `max_attempts` + `failure_code` / `failure_message`
    + **🔴 `next_attempt_at`（失败重试与免打扰到期共用这一个字段与这一条到期扫描）**
    + `alert_state` / `alerted_at` / `alert_message`（**告警落在本表自己三列上，不写别的模块的表**）
    + **append-only**（GRANT 只给 SELECT/INSERT/UPDATE，**不给 DELETE**）
  - 两表：`tenant_id NOT NULL` + RLS **ENABLE 且 FORCE** + 租户守卫触发器 + GRANT + 索引
- **端点**：**10 个形状**挂 `/identity/external-channel-push`
  （`GET /capabilities`、`GET|POST /channels`、`PUT|DELETE /channels/{id}`、`GET /candidates`、
  `GET|POST /deliveries`、`POST /deliveries/retry-due`、`POST /deliveries/{id}/retry`、
  `POST /deliveries/{id}/acknowledge-alert`）
  ⇒ **租户与 membership_id 只从已认证会话解析**，请求体不接受 `tenantId/userId/membershipId`
  （Jackson fail-on-unknown-properties 直接 400）
- **页面**：`/notifications/push`（`apps/web/src/app/notifications/push/page.tsx`
  + `components/notifications/external-channel-push-{model,workspace}.tsx`）——
  真实工作区，**加载 / 错误 / 空三态齐全**；🔴 登记表单**只收 secret manager 引用**，
  地址本体当场被拒并说清为什么
- **测试**：`ExternalChannelPushIntegrationTest` **14 例** + `ExternalChannelPushTenancyIntegrationTest` **6 例**
  （租户隔离三断言跑在**本模块自己的两张表**上：两租户互不可见（含按主键直查对方行读到零行）/
  伪造 `X-Tenant-Id` 无效 / 连接池归还后不串租户（5 轮交替））
  + 前端 model 25 例 + allowlist 5 例

**关键设计决策**：

- 🔴 **「凭据不落库」做成数据层 CHECK，不只是注释**：服务层 `ExternalPushRules.normalizeSecretRef` 先拒一次
  （错误消息直说「凭据不落库：请填引用，不要填 Webhook 地址本身」），V385 的 CHECK 再拒一道
  ⇒ **绕过服务层直连 SQL 也写不进一个真实的 Webhook 地址**。
- **两个端口 + 如实答「未接通」**：`ExternalChannelDeliveryPort`（企业微信/钉钉/飞书/邮件投递）
  + `SecretReferencePort`（凭据引用解析），实现 `UnavailableExternalChannelDeliveryPort` /
  `UnavailableSecretReferencePort` **逐能力如实答「未接通 + 缺什么 + 归谁」** ⇒ **绝不返回假成功、也不产出假凭据**。
- **四个投递状态而不是两个**：`DEFERRED`（免打扰时段）**不等于** `FAILED` ——
  混在一起会同时丢掉「什么时候会重试」并误报告警。
- **免打扰按收件人当地墙上时间换算**（不按 UTC）；**退避按尝试次数算、不看日历** ⇒ 与星期无关。
- **本服务不装 `@Scheduled`**：多副本下自带定时器要做主节点选举与分布式锁
  ⇒ 由外部调度（K8s CronJob）按间隔调 `POST /deliveries/retry-due`，与 10.4.2.2 巡检同一套纪律。
- **告警落在本表自己的列上** —— 刻意**不写** `operation_log`（每张事实表只有一个写入所有者）。

### 模块 1.1.2.2 店铺概览（reporting，**无迁移**）

**这个功能做什么**：工作台上的「**我的店铺排座次**」—— 把同一时间窗内各店铺的销售额、订单量、
利润率、库存天数、广告花费五个维度并排放，一眼看出**掉队的店铺**，
点进去直接跳到该店铺的经营分析页或订单页。

**技术落地**：

- **迁移**：**无**。卷 01 卷首写死「不产生新的业务事实，只聚合各系统的投影」「工作台只读 + 跳转」
  ⇒ 本模块读的就是 13.1.1.2 多店对比**已经投影好的**店铺维度事实
  ⇒ **派生值落库只会比它描述的经营数字更早过期** ⇒ **不新建任何表**
  （reporting 服务本车道仍只有既有的 `V376` / `V379` / `V381`）
- **端点**：**3 个 GET** 挂 `/reporting/store-overview`
  （`GET /reporting/store-overview` 按店铺横向对比当期表现 + 识别掉队店铺、
  `GET /reporting/store-overview/capabilities` 五个维度逐条报指标码 / 接不接得通 / 口径归谁 + 判定规则 + 未交付清单、
  `GET /reporting/store-overview/shops` 可见店铺集合 + 每个店铺在这个窗口有没有事实）⇒ **零写端点**
- **页面**：`/dashboard/store-overview`
  （`apps/web/src/app/dashboard/store-overview/page.tsx` + `components/dashboard/store-overview-{model,workspace}.tsx`）
  ⇒ 三态齐全、无占位数据、**前端不重算任何数字**、**多币种不相加**
- **测试**：`StoreOverviewIntegrationTest` **11 例** + `StoreOverviewTenancyIntegrationTest` **3 例**
  + 前端 model 33 例 + allowlist 6 例

**关键设计决策**：

- 🔴 **「算不出来」与「等于 0」必须分开**：没有广告事实的店铺那一格是 `NO_DATA` + `null`，**不是 0**；
  而两个口径**一致**时口径差异额**真的等于 0**（不是「无数据」）—— 这两件事必须能区分开。
- 🔴 **「销售额」那一格结构上恒定三行**（平台侧 / 我方还原 / 口径差，少一行就是它自己坏了）；
  **接不通的维度一条取值都不给**：`values()` 必须为空、`available=false`、**归属模块号非空**、原因非空
  ⇒ **不给公式，也不给分母**（给了就是编一个）。
- **五个维度的诚实结论**（本模块核心验收项）：
  销售额 ✅ 三口径并列 · 订单量 ✅ · 利润率 🔴 无口径码（`available=false`，
  归属 13.2 利润分析 / 12.4.1.2 成本计算 —— 成本与利润只有**法人/公司维度**，挂到店铺上就是造假）·
  库存天数 🔴 无口径码（在手库存按 SKU × 法人 × 仓组织，**没有店铺维度**）· 广告花费 ✅
- **金额在展示层走「十进制字面串 + BigInt 缩放整数」**（**不碰浮点**：HALF_UP 下 1.005 ⇒ 1.01，浮点会算成 1.00）。
- **「今日/昨日/本月」的窗口边界按本租户时区算**（**不写死 UTC**）。
- **权限码沿用 `reporting.store-comparison.read`** —— 本模块读的就是 13.1.1.2 那套店铺维度读模型
  （同一批表、同一套口径、同一份 RLS）⇒ 另起一个码会让「谁能看店铺经营数据」出现两个答案。

---

## 九、本轮交付的台账与说明

- 台账：`docs/product/capability-blueprint/module-progress.md`
  —— `1.3.2.2` / `1.1.2.2` 两行由「未开发」变「已交付」并补齐证据（**只改这两行**）
- 本说明：本文件（`docs/sessions/2026-10/2026-10-26-batch-104-l2-supplement-and-dispatch.md`）
- 跨模块 TODO：`docs/backlog/2026-10-2026-10-26-hardcoded-fixture-dates-self-failing.md`（新增）
- 本轮调度者写入集成头的代码改动：**只有一处** —— `fix(test)`：`LogisticsExceptionIntegrationTest.RECENT_AT`
  改成相对当前时刻（详见第六节）

---

## 十、收尾三件套

① **写说明** → ② **commit** → ③ **push**（顺序不可颠倒）

1. **说明**：本文件（每模块一节，见第八节；含迁移 / 端点 / 页面 / 测试 / 关键设计决策）
   + `docs/backlog/2026-10-2026-10-26-hardcoded-fixture-dates-self-failing.md`（跨模块 TODO）
2. **commit**：
   - `ac583a14` `fix(test):` —— 夹具日期脆弱性的修复（**独立 atomic commit**，与 `cd9e125e` 同套路）
   - `docs(batch-104):` —— **本文件 + backlog + 台账三个文件在同一个 commit**
3. **push**：`git push origin integration/module-dev`（**只推集成头，绝不推 `main`**）

---

## 十一、吞吐预期（按当前配置现算）

| 项 | 值 |
| --- | --- |
| 档位 | **降档**（`TOKEN_HORIZON` 已过）⇒ `LANES` = **2** |
| cron | `0 * * * *`（整点，60 分钟栅格）· `SILENT_WINDOW=0`（全天运行） |
| 节流闸 | **100 分钟**（降档后）⇒ 60 分钟栅格下 **每 2 格放行一次** ⇒ **实际约 2 小时一批** |
| 每批车道 | 2 |
| 名义吞吐 | `2 × 12 批/天` = **24 个/天**（全天运行、每 2 小时一批） |
| 折损后（×0.70） | **≈ 17 个/天** |
| 剩余 | **55** 个未开发（本轮派发 2 个后为 **53**） |
| 诚实区间 | **约 3～4 天**（区间而非单点：受 L2 返工、长尾车道占位、以及「上游未交付 / 5.1 定价环依赖」这类硬阻塞影响） |

⚠️ **不承诺精确完成日期**：剩下 55 个里有 **5.1 定价 3 个是循环依赖**（自 batch-98 起连续点名，需人工拍板拆环），
以及 **18.x 21 个**（卷 18 整卷未开发，其中「规则任务」这类模块会卡在上游未交付）。

---

## 十二、风险提示

1. 🔴 **「L2 红 ⇒ 并入的东西弄坏了」这个判定本轮差点被误导**：真红是**夹具的日期脆弱性**，
   与本批代码**完全无关**（channel 一行未改、套件 5 小时前全绿、测试数一模一样）。
   已登记 backlog（见 6.3），**建议下一轮做一次专门巡检**。
2. 🟡 **后台 `sleep` 被 macOS 延迟**（第二节）⇒ 续期守护不能依赖长 `sleep`。
3. 🟡 **PG 容器在本轮 L2 峰值到 113 个**（surefire 不 stop 容器、ryuk 只在 session 结束回收；
   第一遍单 reactor 峰值 86、channel 重跑峰值 113）
   ⇒ 虽然 swap 全程**零增长**、RAM 45% 空闲、**没有**出现 round-104 的 thrashing 假红，
   但这个峰值离出事很近。
   ⇒ **建议下一轮把 L2 的后端部分也按「一服务一 session」全量拆开跑**
   （本轮已对 7 个模块这么做；channel 因为在第一遍里已跑过、只重跑了一次，
   下一轮**从一开始就拆**可以同时省掉「重跑一遍」的 15 分钟）。
4. 🟡 **`packages/sdk` 目前是空壳**（`packages/sdk/src/` 无内容，已交付模块的类型也都不在里面）
   ⇒ 与本批无关，但**前后端类型不同源**这件事在 SDK 侧尚未落地，值得单独确认是「还没生成」还是「设计上不用」。
5. 🟡 **本轮并入的两条车道都改了共享 `apps/web/src/lib/api-proxy.ts`**（`+112 / −0`）⇒
   并行车道越多，这个文件的冲突面越大，后续批次要留意。
