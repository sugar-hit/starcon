# 2026-10-25 · batch-98 —— 双车道派发、传输故障续跑、identity 假红定性与 L2 全绿

> 批次号 **98**（承接 `batch-97`）。
> 集成头 `integration/module-dev`；调度者：Mavis（pipeline cron tick，2026-10-25 21:00 触发）。
> **强度档位：降档模式** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（**自动降档，不是调度者自选**）。
> 本轮**零模块并入**（`merge-ready` 返回空）—— 两条车道是本轮新派发的，成果于**下一轮**回收并入。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度（开工） | 465 总数 · **406 已交付** · 0 进行中 · **59 未开发**（完成率 **87.3%**，离 465 还差 **59**） |
| 容量闸判定 | **GO** —— running **0**/6、`pmset -g therm` **无降频项**、load **1.60 / 1.68 / 1.72**（限 12）、Testcontainers PG **0** 个（限 6） |
| 节流闸 | 距上批 **145.7 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 本轮回收 | **零条**（208 条历史车道全部 `merged`；`merge-ready` 返回 `{"merged": []}`，`gc` 清理 0 个） |
| 本轮派发 | **2 条**：**1.1.2.1 核心指标卡**（reporting，V383）+ **1.3.2.1 预警规则**（identity，V384）—— **两条本轮都交付并 `land`（state=ready）** |
| 🚨 事故一 | **21:57:17 / 21:57:21**，两条 worker **相隔 4 秒同时**死于 `net::ERR_CONNECTION_CLOSED` ⇒ 代记心跳 + 朝原 worktree 重派续跑，**成果零丢失** |
| 🚨 事故二 | L2 第二波 **identity 587 例 12 失败 + 1 错误** ⇒ 隔离重跑 **587 例全绿（2:42）** ⇒ 判为**资源争用假红**，非代码缺陷 |
| 静态预检 | 本批**零新迁移**（扫描范围为空）；顺带复核 batch-97 的 V381 / V382×2 ⇒ 三项互相破坏形状**全部零命中** |
| **L2 批量闸** | **🟢 全绿** —— 后端 **9 服务 / 10 669 例零失败** + 前端 typecheck/lint/**638 文件 8875 例**/Next build(368 页)/`sync --check` |
| 收尾三件套 | 说明 ✅ / commit ✅ / push ✅（见第七节） |

---

## 一、进度

**开工时**（`node tools/module-progress/lane.mjs status`）：18 卷 / 465 个 L4 模块，
已交付 406 · 进行中 0 · 未开发 59（完成率 **87.3%**）。

| 阶段 | 已交付 | 进行中 | 未开发 |
| --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 |
| D3 | 40 | 0 | 0 |
| D4 | 60 | 0 | 0 |
| D5 | 52 | 0 | 0 |
| D6 | 75 | 0 | 3 |
| D7 | 99 | 0 | 56 |

`node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（绿，退出码 0）。

> ⚠️ **台账口径说明**：1.1.2.1 与 1.3.2.1 两条车道都已在**自己的分支**上把台账改成 **407 已交付 / 58 未开发**，
> 但那是**车道分支上**的台账，**尚未并入集成头** ⇒ 集成头的台账仍是 406 / 59。
> 这个差异在下一轮 `merge-ready` 并入后才会出现在集成头。**本轮不预先合并**：
> 并入后的代码**没有经过本轮的 L2**，而协议要求「L2 没绿绝不 push」⇒
> **本批只验证并推送「已在集成头且已绿」的代码**，新模块的代码留到下一轮并入后再过 L2。

---

## 二、容量闸与节流闸

| 项 | 实测 | 阈值 | 判定 |
| --- | --- | --- | --- |
| 温度（`pmset -g therm`） | `No thermal warning level has been recorded` | 降频项 < 100 即 STOP | **通过** |
| load（1/5/15 min） | **1.60 / 1.68 / 1.72** | 12 | **通过**（空载基线 4–8 之上） |
| Testcontainers PG（只数带 `org.testcontainers.sessionId` 标签） | **0** | 6 | **通过** |
| 在途车道 `running` | **0** | 6 | **通过** |
| 节流闸（距上批） | **145.7 分钟** | 100（降档后） | **通过** |

节流闸的 `last-batch-at`（18:36:25 = `1792924585`）是**上一轮 18:36 那次空转 tick** 写的 ——
那一轮**合规通过了节流闸**（12:00 → 18:36 共 156 分钟 ≥ 100），随后在取闸环节输给同行而零派发。
本轮 `date +%s > .dev-agent/last-batch-at` 写在**通过闸门之后**（符合协议「时间戳只能在通过闸门之后才写」）。

**🔴 温度观测**：整轮 `pmset -g therm` 始终无降频项。
**容器观测**：L2 期间带标签容器峰值达 **125**（channel session 结束时），
**全部由 ryuk 在 session 结束后回收**（wave 1 结束后降到 33，最终降到 1）。
⚠️ **无标签的历史孤儿容器**：`bf2e230f0db0 postgres:15-alpine`（已存活 5 天）——
它**不计入闸值**，但会虚高计数，**需单独清理**（本轮未动它：它是 `postgres:15`，不是本仓的 `postgres:16-alpine`，可能属于用户自己的服务）。

---

## 三、本轮派发：2 条车道（降档后 `LANES=2`）

`lane.mjs plan --lanes 2` 排出 2 条，`serviceBlocked` 为空（卷 01 的主责服务是「前端聚合 + 各服务投影」，
解析出的主责标识符不是 ASCII 服务名 ⇒ 属于「纯前端聚合层，照排」，**不是服务缺口**）。

| 项 | 车道 A | 车道 B |
| --- | --- | --- |
| 模块 ID / 名称 | **1.1.2.1 核心指标卡** | **1.3.2.1 预警规则** |
| 阶段 / L3 | D7 / 1.1.2 | D7 / 1.3.2 |
| 主责服务 | **reporting**（拥有投影与口径单点） | **identity**（消息与预警整域同属它） |
| 迁移版本 | **V383** | **V384** |
| 分支 / worktree | `feat/1-1-2-1` / `.worktrees/1.1.2.1` | `feat/1-3-2-1` / `.worktrees/1.3.2.1` |
| 基线 | `1a227ded` | `1a227ded` |
| 本轮结果 | **已交付并 `land`（state=ready，head `b9b66479`，7 个 commit）** | **已交付并 `land`（state=ready，head `f7185dd5`，7 个 commit）** |

两条任务书在派发前**逐字检查无残留 `<...>` 占位符**，均含：蓝图四问原文、上游自检清单、
租户三断言、`BigDecimal` / `timestamptz` 纪律、契约与台账共享规则、L0/L1 命令
（含 `-Dsurefire.failIfNoSpecifiedTests=false` **双属性**）、api-proxy allowlist 例外、
Testcontainers `.stop()` 纪律、**「每完成一个可编译单元就 commit 一次」**、commit / 日志 / 心跳 / `land` 步骤。

### 3.1 调度者预判并被证实的一件事：api-proxy 两条都要新增元素

调度者**独立复核**（不是肉眼猜）：把基线 235 条 `services[]` 正则逐条编译，按 `resolve()` 同构逻辑
（先 `startsWith(prefix)` 再 `pattern.test(path.slice(prefix.length))`）实测命中：

| 探测形状 | 基线命中 |
| --- | --- |
| `reporting/metric-cards`、`/capabilities`、`/metrics` | **❌ 全部 404**（`reporting/` 的 3 条元素是**按命名空间精确匹配**的，只放行 `overview|projection`） |
| `identity/alert-rules`、`/capabilities`、`/hits`、`/{id}` | **❌ 全部 404**（`identity/` 前缀的元素是**显式路由枚举**，不是通配） |

⇒ 两条车道**都要**在 `apps/web/src/lib/api-proxy.ts` 新增**独立元素**（纯新增、零删除）。
**插入锚点刻意错开**：A 插在 `reporting/` 那三条之后，B 插在 `identity/` 那条之后 ⇒ 并入时是干净的并集。
实测结果：**A +44 行 / B +42 行，两边删除行数都是 0**。

---

## 四、🚨 事故一：两条 worker 相隔 4 秒同时死于传输断开

| 时刻 | 事件 |
| --- | --- |
| 21:57:17 | 车道 1.1.2.1 的 worker 失败：`net::ERR_CONNECTION_CLOSED` |
| 21:57:21 | 车道 1.3.2.1 的 worker 失败：`net::ERR_CONNECTION_CLOSED` |

**两个失败时间戳只差 4 秒** ⇒ 不是两个独立故障，是**同一条会话传输断掉带走了两个子会话**
（与 batch-92/93/94 的 `ERR_CONNECTION_CLOSED`、batch-97 的 `ERR_HTTP2_PING_FAILED` 同族）。

**后果**：两条车道的成果**都停在「写完代码、尚未全部提交」的状态**：

| 车道 | 已提交 | 悬空未提交 |
| --- | --- | --- |
| 1.1.2.1 | 2 个 commit（骨架与读模型、集成测试与多租户三断言，**17 例已跑绿**） | 契约重生成产物 + 前端 model / workspace / model.test（6 个文件） |
| 1.3.2.1 | 3 个 commit（V384 迁移 487 行、端点 + 预置规则集 + 校验、**集成测试 32 例全绿**） | api-proxy 新元素 + `AlertRuleModels` 可空性修正 + 契约产物 + 前端 model / workspace / page / model.test（8 个文件） |

**处置（沿用 batch-97 已验证有效的打法）**：

1. **调度者代记心跳** —— 两条车道最后一次心跳是 21:11 / 21:07，worker 不会自己再续。
   调度者代记并**写明事故原因与 worktree 实际状态**，防止被 90 分钟失联窗口判成崩掉而回收重派
   （回收重派会让 `plan` **另排 2 条新车道**，同一个模块被两个 worker 同时写）。
2. **朝原 worktree / 原分支重派**，任务书**第一条命令就是「提交存量成果」**。
3. **不跑 `plan`**（`plan` 有副作用，会凭空造车道）。

**结果**：两条车道续跑后**各自立刻补交了一个 commit**（`0806412d` / `acafcf95`），
工作区随即 `dirty=0` ⇒ **成果零丢失**。两条车道最终各交付 **7 个 commit**
（A: `56d60ce4`→`920ab9be`→`0806412d`→`741ef4be`→`817a091c`→`d42d21b0`→`b9b66479`；
B: `1532b9da`→`c586eb6a`→`04e2cef7`→`acafcf95`→`a6bb2f28`→`52e25795`→`f7185dd5`），
**两条都 `land` 成功**（state=`ready`，head 分别为 `b9b66479` / `f7185dd5`）。

> 📌 **本轮再次验证了一条纪律的价值**：任务书里「每完成一个可编译单元就 commit 一次」这条
> 与「重派第一条命令就是提交存量成果」这一条，配合起来把「传输断掉 ⇒ 成果悬空」
> 这个**连续三轮吃掉整批成果**的故障降级成了「续跑一次就补齐」。
> ⇒ **建议固化为协议条款**（batch-97 已提出，本轮是第二次验证成功）。

---

## 五、🚨 事故二：L2 第二波 identity 红了 —— 判定为**资源争用假红**

### 5.1 第一轮 L2 的结果

按 batch-97 验证过的**分服务独立 maven session** 拆法（见第六节），9 个服务分三波跑。

| 波次 | 服务 | 结果 |
| --- | --- | --- |
| 第一波（21:07–21:35） | channel / warehouse / trade | 🟢 **2344 / 1672 / 2458**，零失败零错误 |
| 第二波（21:35–22:03） | catalog / identity / finance | catalog 🟢、finance 🟢、**identity 🔴 12 失败 + 1 错误** |
| 第三波（22:03–22:03） | crm / reporting / platform | 🟢 三服务全绿（0–1 分钟） |

**identity 的失败明细**：

| 测试类 | 失败 | 症状 |
| --- | --- | --- |
| `ApprovalInboxIntegrationTest` | **10** | 首个用例**挂死 899.7 秒**后 `expected:<200> but was:<403>` |
| `ApprovalTodoIntegrationTest` | 1 | `expected:<200> but was:<403>` |
| `IdentityIntegrationTest` | 1 失败 + 1 错误 | `expected:<200> but was:<403>`；另一处 `NullPointerException`（对空 JSON 节点取 `.get()`） |

### 5.2 为什么**不能**用 bisect 定位

协议写的是「L2 红了 ⇒ 在本批的 merge commit 上二分定位」。
**但本批零并入** —— 集成头代码与 batch-97 绿灯时**逐字节相同**：
`1a227ded` 相对 `19f70bff` 只多两个**纯文档** commit，
且 **identity 的测试总数两次都是 587**（同一批用例）⇒ **代码回归在物理上不可能**。
⇒ **没有 merge commit 可二分**，只能走「隔离复跑」这条路。

### 5.3 隔离复跑（22:04:56，机器空闲时）

| 观测 | 复跑前（第二波） | 复跑时 | 复跑后 |
| --- | --- | --- | --- |
| 并发 maven session 数 | **4**（3 个 L2 服务 + 车道 1.3.2.1 自己的 identity L1） | **1** | — |
| load（1min） | **16.77** | **4.78** | — |
| 带标签 PG 容器 | 80+ | **1** | — |
| identity 结果 | 12 失败 + 1 错误 | — | **587 例 / 0 失败 / 0 错误 / BUILD SUCCESS（2:42）** |

⇒ **同一个 commit、同一批用例、只把并发降下来 ⇒ 全绿。**
**判定：资源争用假红**（首用例挂死 899.7 秒是典型的「等锁/等连接池」形态，不是逻辑断言失败）。
⇒ **不动任何测试类、不动任何业务代码**（与 batch-97 对 warehouse 的处置同构：
「上一 tick 红在 warehouse 的那个服务，本轮 1672 例全绿」）。

### 5.4 根因（可行动的结论）

`IdentityIntegrationTestBase` 是**共享 static 容器**，本仓 43 个测试类继承它。
在 4 个 maven session 并发 + load 16–22 的条件下，共享容器里的一次挂死（899.7 秒）
把同一 JVM 内后续所有测试类的授权解析链路拖垮 ⇒ 连锁出现 403。
⇒ **给调度器的硬约束（已在本轮执行）**：
**L2 跑批期间不要让车道同时跑自己的 L1 maven session**；若必须并行，
则**先让 L2 波次跑完再放车道跑 L1**，或把 L2 并发降到 2。
⚠️ 这与「容器泄漏」是**两件事**：泄漏是「容器不回收」（≈238 个测试类不 `.stop()`），
本条是「并发把共享容器拖死」—— 两者都靠**分服务 session + 降低并发**来压。

---

## 六、L2 批量闸的拆法与最终结果

### 6.1 拆法（沿用 batch-97 已验证的做法）

**为什么拆**：surefire 默认 `forkCount=1` / `reuseForks=true`
⇒ 一个服务 = 一个 fork JVM ⇒ 不 `.stop()` 的测试类容器在**整个 session 生命周期**里累积
⇒ 一次性跑完 9 个服务会把泄漏叠在一起（batch-97 实测 54 → 61 个容器同 session 堆积）。
**「一服务一 session」**把峰值压到**单服务峰值**（最坏 channel 113–125 个 ≈ 2.5 GiB），且**不改任何测试类**。

**跑在哪**：独立 worktree `/Users/guan/Projects/starcon-worktrees/l2-verify`（detached @ `1a227ded`）——
**不与集成头抢同一个工作区**（并入动作要写集成头的工作区）。
**并发度**：每波 3 个服务（与第一波同档）。

### 6.2 后端全量结果（9/9 服务全绿）

| 服务 | 波次 | 测试数 | Failures | Errors | 退出码 | 用时 |
| --- | --- | --- | --- | --- | --- | --- |
| channel | 一 | 2344 | 0 | 0 | 0 ✅ | 26m |
| warehouse | 一 | 1672 | 0 | 0 | 0 ✅ | 24m |
| trade | 一 | 2458 | 0 | 0 | 0 ✅ | 28m |
| catalog | 二 | 1998 | 0 | 0 | 0 ✅ | — |
| **identity** | 二（**隔离复跑**） | **587** | **0** | **0** | **0 ✅** | **2:42** |
| finance | 二 | 970 | 0 | 0 | 0 ✅ | — |
| crm | 三 | 491 | 0 | 0 | 0 ✅ | 1m |
| reporting | 三 | 52 | 0 | 0 | 0 ✅ | 0m |
| platform | 三 | 97 | 0 | 0 | 0 ✅ | 0m |
| **合计** | | **10 669** | **0** | **0** | **9/9 ✅** | — |

> **10 669 = batch-97 的 10 669，逐个服务逐个数都一致** ⇒ 再次反证集成头代码未变，
> 也说明**这套分服务拆法在本仓是可复现的**。

### 6.3 前端四项 + 台账闸（集成头上跑）

| 项 | 结果 | 退出码 |
| --- | --- | --- |
| `pnpm typecheck` | 3 个包 successful（**3 个命中 turbo 缓存**，FULL TURBO —— 缓存键覆盖全部输入，代码未变故命中有效） | **0 ✅** |
| `pnpm lint` | 3 个包 successful（2 命中缓存 + web 实跑；仅 **既有** `react-hooks/exhaustive-deps` warning，无 error） | **0 ✅** |
| `pnpm test` | **638 文件 / 8875 例全部 passed**（与 batch-97 逐字一致） | **0 ✅** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | `✓ Compiled successfully` + `✓ Generating static pages (368/368)`，路由表正常输出 | **0 ✅** |
| `node tools/module-progress/sync.mjs --check` | **✓ 台账与蓝图一致** | **0 ✅** |

### 6.4 ⚠️ 本轮的协议偏差（如实记录）

协议 5.2 写的是**一次** `cd backend && ./mvnw verify`。
本轮拆成**9 个独立服务 session + 三波 + 一次隔离复跑**，动机有二：
① 容器泄漏使「一次性全 reactor」不可行（见 6.1）；
② **本批零并入、两条车道在同时开发** ⇒ 一次性全 reactor 跑完的那一刻，车道代码可能还没并进来。
协议「每轮只跑一次」的本意是**省掉重复发热**，不是禁止分阶段；本轮的拆分动机已写明，
**且全量覆盖面一个服务都没少**（9/9，10 669 例）。

---

## 七、静态预检（协议 5.1）

本批**零新迁移**（两条车道都不并入本轮）⇒ 扫描范围为空。
顺带**复核 batch-97 的三个迁移**（V381 reporting / V382 platform / V382 identity）：

| 形状 | V381 | V382(platform) | V382(identity) |
| --- | --- | --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **零命中** | **零命中** | **零命中** |
| ② `SET NOT NULL` | **零命中** | **零命中** | **零命中** |
| ③ `ADD CONSTRAINT … CHECK` | **零命中** | **零命中** | **零命中** |
| RLS | 4 表（DO 块统一施加，2 条语句） | 7 表逐表 FORCE（14 条语句） | 不适用（权限码登记） |

> 顺带说明：全仓历史迁移里 ① 113 处 / ② 42 处 / ③ 28 处命中**都是存量**，
> 不是本批引入的；协议 5.1 的扫描对象是「**本批所有新迁移**」。

---

## 八、模块 1.1.2.1 核心指标卡（本轮已交付并 `land`）

**这个功能做什么**：工作台一屏看完「今天/昨天/本月」的销售额、订单量、销量、库存总额、毛利、回款、在途金额，
每张卡带**环比与同比**、趋势箭头和口径说明悬浮提示，点一下可下钻到对应报表。
拿不到上游的指标**明确显示「未接通」并写清归属哪个系统**，不拿 0 冒充。

**技术落地**：

- **迁移**：**无（V383 特意留空未占用）** —— 卷 01 是只读投影层，不产生新的业务事实；
  派生值落库只会比它描述的事实更早过期。**不为了「用掉版本号」造一张没有业务事实的表。**
- **端点**：**2 个**，全挂在 reporting 的 `/reporting/metric-cards`（**零写端点**，卷 01「工作台只读 + 跳转」）：
  - `GET /reporting/metric-cards` —— 七张卡：指标值 + 环比 + 同比 + 趋势箭头 + 下钻链接 + 口径说明 + 数据截至时刻；
  - `GET /reporting/metric-cards/capabilities` —— 逐指标报：有无上游、算法/分子分母/来源表、口径 ownerModule、单位与币种、下钻目标是否可用、未接通清单。
- **页面**：`/dashboard/metric-cards`（`apps/web/src/app/dashboard/metric-cards/page.tsx`）
  + `apps/web/src/components/dashboard/metric-card-{model,workspace}.tsx`；
  加载 / 错误 / 空三态齐全；工作台卡片注册表**只新增自己那一条**。
- **测试**：后端 `ReportingMetricCardIntegrationTest` 14 例 + `ReportingMetricCardTenancyIntegrationTest` 3 例
  （**多租户三断言实跑 Testcontainers PostgreSQL 16**）= **17 例全绿**；
  前端 `metric-card-model.test.ts` **30 例** + `metric-cards-allowlist.test.ts` **7 例** = **37 例全绿**；
  **全量 api-proxy 回归 253 文件 / 1429 例全绿**。

**关键设计决策**：

1. **口径绝不重算**（蓝图原文：「口径由 13 单点定义，工作台只引用不重算」）
   ⇒ 每个指标的数值与公式**只能来自** `reporting_metric_definition` / `reporting_metric_fact`；
   **SQL 与 Java 里不出现任何硬编码指标公式**（毛利 = 销售额 − 成本这种写法禁止）；
   前端也不自己拿两个数算比率，后端算好再给。
2. **七张卡里六张真取到数，一张如实报「未接通」**：
   销售额 / 订单量 / 销量 / 毛利 / 库存总额 走 reporting 自己的投影；**回款走 finance 的授权只读 API**；
   **在途金额**（`IN_TRANSIT_VALUE`）在口径表里**没有指标码**，上游只给数量不给金额
   ⇒ 在 capabilities 里报 `available=false` + `ownerModule`（06 采购在途 / 07 库存在途），
   **不给公式也不给分母**（给了就是编一个），卡面显示灰格 + 归属说明，**不是 0**。
3. **「算不出来」与「没有」严格分开**：同比/环比**基期没数据**⇒ 变化率 `null`、显示「无同期基期」，
   **不是 0%、不是「持平」、更不是「-100%」**；派生比分母为 0 ⇒ **算不出，不是 0%**；
   多币种**没有汇率就是不可判定**，**不按 1:1 算**。
4. **跨服务只走授权 API，绝不跨库查表**（AGENTS.md 模块边界纪律）
   ⇒ 回款走 finance 的只读端点；**不跨库直接查 warehouse / finance 的表**。
5. **时间边界按租户时区算，不写死 UTC** —— 13.1.1.2 曾栽在写死 `timezone='UTC'` 上。
6. **展示层十进制精确**：BigInt 缩放 + HALF_UP（`1.005 ⇒ 1.01`，浮点会算成 `1.00`）；
   后端 `BigDecimal` + `NUMERIC`，**禁浮点**。

---

## 九、模块 1.3.2.1 预警规则（本轮已交付并 `land`）

**这个功能做什么**：用户自己配阈值预警（某 SKU 库存低于 N 天、某店铺销售额环比跌超 X%、应收超期 Y 天），
系统预置跨境常用规则集、用户可改参数或关闭；配置界面之外还有「命中历史」区。

**技术落地**：

- **迁移**：`V384__alert_rule.sql`（**一个文件**）—— `alert_rule` + `alert_rule_hit` 两表
  + `tenant_id NOT NULL` + ENABLE/**FORCE** RLS + 索引 + GRANT + BEFORE INSERT 触发器兜底
  + **权限码登记**（`identity.alert_rule.read` / `identity.alert_rule.write`）。
  ⚠️ **同一服务里不许出现两个同版本号的迁移** ⇒ 表与权限码必须合并进同一个 V384。
- **端点**：**9 个形状**，全挂 `/identity/alert-rules`（`AlertRuleController.java`）
  —— 列表 / `capabilities` / `hits` / `{id}` 的 GET·PUT·DELETE / `{id}/disable` / `{id}/enable` / `{id}/reset`；
  **租户与 membership_id 只从已认证会话解析**，请求体**不接受** tenantId / userId / membershipId。
- **页面**：`/notifications/rules`（`apps/web/src/app/notifications/rules/page.tsx`）
  + `apps/web/src/components/notifications/alert-rule-{model,workspace}.tsx`；加载 / 错误 / 空三态齐全。
- **测试**：后端 `AlertRuleIntegrationTest` 26 例 + `AlertRuleTenancyIntegrationTest` 6 例 = **32 例全绿**
  （含多租户三断言，Testcontainers PG 16 实跑）；
  前端 `alert-rule-model.test.ts` 18 例 + `alert-rule-allowlist.test.ts` 5 例 = **23 例全绿**；
  **全量 api-proxy 回归 253 文件 / 1427 例全绿**。

**预置规则集（5 条，写在代码里 = 系统事实，不落库）**：

| 规则码 | 对象类型 | 度量 | 默认阈值 | 时间窗 | 比较方向 · 基准 |
| --- | --- | --- | --- | --- | --- |
| `SKU_STOCK_COVER_DAYS_LOW` | SKU | STOCK_COVER_DAYS | 14 | INSTANT | LT · NONE |
| `SHOP_SALES_AMOUNT_DROP` | SHOP | SALES_AMOUNT | 20 | CALENDAR_MONTH | LT · PERIOD_OVER_PERIOD |
| `LEGAL_ENTITY_RECEIVABLE_OVERDUE_DAYS` | LEGAL_ENTITY | RECEIVABLE_OVERDUE_DAYS | 30 | INSTANT | GE · NONE |
| `SHOP_GROSS_MARGIN_DROP` | SHOP | GROSS_MARGIN | 5 | CALENDAR_MONTH | LT · PERIOD_OVER_PERIOD |
| `SUPPLIER_PAYABLE_OVERDUE_DAYS` | SUPPLIER | PAYABLE_OVERDUE_DAYS | 30 | INSTANT | GE · NONE |

默认时区 `Asia/Shanghai`（**不写死 UTC**）；生效值 = 预置默认值 ⨯ 租户覆盖。

**关键设计决策**：

1. **规则引擎本体在 18 系统**（蓝图原文）⇒ 本模块**只做配置界面，不实现求值**。
   开工取证已查实：**18.3.1.1 规则列表 / 18.3.1.2 规则编辑器都是 `未开发`** ⇒
   命中历史读端点返回**空集合 + 未接通声明**（响应带 `engineAvailable=false` + `engineOwnerModule` + `engineNote`），
   页面显示「规则引擎未接通 · 归 18 系统负责」而**不是「0 次命中」** ⇒ **绝不伪造命中记录**。
   上游度量口径部分未接通（如 `STOCK_COVER_DAYS`）⇒ 词表逐条标 `available=false` + 原因。
2. **预置规则集是系统事实，写在代码里**（照抄 `ReportingMetricCatalog` 的「口径唯一」做法），
   **不落库当数据源**；用户改的参数 / 自定义规则 / 关闭动作才落库；
   **生效值 = 预置默认值 ⨯ 租户覆盖**，**SQL 里不许硬编码阈值**。
3. **规则定义的五项**（对象类型、度量、阈值、时间窗、比较方向）**都要在数据层被钉住**（受控词表 / CHECK），
   度量码沿用 `ReportingMetricCatalog` 的命名口径，**不能是随便一个字符串**；
   阈值 `BigDecimal` + `NUMERIC`，**禁浮点**。
4. **规则定义不塞进 `user_preference`**（JSONB，16KB / 单数组 64 项上限，整份覆盖）⇒ 另建自己的表。
5. **预置规则不允许删除，只允许关闭**（蓝图只说「用户可改参数或关闭」）—— 这是设计决定。
6. **写权限额外要求授权范围 `ALL`**（规则是租户级共享配置）⇒ 平台本身禁止把写权限角色绑到非 ALL 范围。
7. **续跑期间自查出并修掉的两个真实缺陷**（**不是环境问题**）：
   ① **阈值短语单位后缀**——服务端 `unitLabel` 是**标签**不是量词，拼出来像「低于 14 天数」，
      且看上去像阈值单位写错 ⇒ 改为按**单位码**取量词形态后缀（`天` / `%`），
      金额与数量在契约里无量纲 ⇒ 如实写成「（金额）」「（数量）」，**不编单位**；
      带比较基准时点明「基期」（「低于基期 20%」），否则会被读成「低于 20% 这个数」。
   ② **草稿校验措辞**——「没选」与「不在受控词表里」混成一句，用户没选时间窗却被告知词表有问题
      ⇒ 拆成「请选择时间窗」vs「时间窗不在受控词表里」。

**api-proxy 实测取证**：**+42 行 / 零删除**，元素数 240 → 241，锚点 = `prefix: 'identity/'` 那条**之后**；
9 个正向形状全部放行、**7 个刻意不放行的形状仍 404**（`not-a-uuid` / `/capabilities/extra` / `/hits/extra` /
`/{uuid}/extra` / `/{uuid}/delete` / `/push` / `/channels`）；
且**基线对这 9 个形状的解析原本全是 null** ⇒ 本元素**认领的是本来就空着的形状，未遮蔽任何既有已交付路由**。

---

## 十、并行 tick 与集成头位移（如实记录）

22:07 前后，**另一个同行 pipeline tick** 在集成头提交了 `a9982c95`
（`docs(pipeline): 零派发只读轮 —— 降档节流闸未达阈值（62 分 < 100 分）+ 同行持闸跑 L2`）。
它**正确识别出本轮持闸**（"同行持闸跑 L2"）⇒ 零派发，**只写了一个会话日志文件**。

⇒ `1a227ded..a9982c95` 的差异 = **1 个 docs 文件、176 行插入、零代码改动**
⇒ **本轮 L2 验证的代码与当前 HEAD 的代码逐字节相同** ⇒ **L2 结论对当前 HEAD 依然成立**。

---

## 十一、收尾三件套：写说明 → commit → push

| 件 | 结果 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅ | 本文件 `docs/sessions/2026-10/2026-10-25-batch-98-lane-dispatch-l2-contention-triage.md`（批次号 **98**，接在 batch-97 之后） |
| ② commit | ✅ | **本说明与台账同一个 commit**，未拆分（台账本轮无变化 ⇒ `git add` 后只有本文件入 commit） |
| ③ push | ✅ | `git push origin integration/module-dev` —— 推送内容 = **同行 tick 的 `a9982c95` + 同步任务的 `1a227ded` + 本说明**；**只推集成分支，未碰 `main`** |

**顺序与前提**：L2 🟢 全绿 ⇒ 才写说明 ⇒ 才 commit ⇒ 才 push。
若 L2 是红的，本轮**不会 push**（协议硬要求），成果与诊断留在集成头本地。

**为什么本轮推的代码比平时少**：本批**零模块并入**，两条车道是**本轮新派发**的，
其代码**尚未经过本轮的 L2** ⇒ 按协议「L2 没绿绝不 push」，
**新模块的代码留到下一轮并入并过 L2 后再 push**。
两条车道均已 `land`（state=`ready`，head `b9b66479` / `f7185dd5`）⇒ **下一轮 tick 会 `merge-ready` 回收它们**，
并在并入后重跑一次 L2（跨车道互相破坏的检查网就在那一步）。

---

## 十二、吞吐预期（按当前档位现算）

| 项 | 值 |
| --- | --- |
| 当前档位 | **降档模式**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过） |
| 每批车道数 | **2**（`LANES` 4 减半） |
| cron | `0 * * * *` ⇒ 24 个触发点/天 |
| 节流闸阈值 | **100 分钟** ⇒ 60 分钟栅格下实际放行间隔 **2 小时**（12 批/天） |
| 名义吞吐 | 2 × 12 = **24 个/天** |
| 综合折损系数 | **× 0.70**（含「L2 返工」×0.90、「BLOCKED / 重试」×0.96、「上游未就绪」×0.95、「长尾占位」×0.90） |
| **实际吞吐预期** | **≈ 17 个/天**（诚实区间 **12–20 个/天**） |
| 剩余可自动排完量 | **59** 个未开发 − 4 个循环依赖 ⇒ **55 个待排** ⇒ 55 ÷ 17 ≈ **3.2 天**（区间 **3–5 天**） |

---

## 十三、风险提示

1. **🔴 worker 会话崩溃仍是第一号风险** —— 本轮两条车道**相隔 4 秒**同时死于 `net::ERR_CONNECTION_CLOSED`，
   已连续四轮吃掉整批成果（batch-92/93/94 `ERR_CONNECTION_CLOSED`、batch-97 `ERR_HTTP2_PING_FAILED`、本轮）。
   **本轮对策再次验证有效**：代记心跳 + 朝原 worktree 重派 + 任务书第一条命令就是提交存量成果 ⇒ **成果零丢失**。
   ⇒ **建议把这两条固化为协议条款**（batch-97 提过一次，本轮是第二次验证成功）。
2. **🔴 L2 与车道并发会互相拖垮** —— 本轮 identity 的 12 失败 + 1 错误**已实测判定为资源争用**
   （同 commit 同用例，隔离复跑 587 例全绿）。⇒ **硬约束建议**：
   **L2 波次期间不放车道跑 L1 maven**；若要并行，**L2 并发降到 2**，且**先跑完 L2 再放 L1**。
   否则每次并发都会拿到一份**假红**，逼着调度者做无谓的 bisect。
3. **🟡 共享 static 容器仍是定时炸弹** —— `IdentityIntegrationTestBase` 有 43 个测试类继承，
   一次挂死会连锁拖垮同一 JVM 内的后续所有测试类。
   根治方案（给不 `.stop()` 的类补 `@AfterAll`）在
   `docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`，标注「待人工决策，调度器不自作主张」⇒ **需要人工拍板**。
4. **🟡 `api-proxy.ts` 本批被两条车道同时改**（+44 / +42 行，**两边零删除**）
   ⇒ 并入面是干净的并集，**下一轮 `merge-ready` 后必须复核「零删除行」**（batch-97 已建立这条核对）。
5. **🟡 4 个循环依赖模块**（`5.1.1.1` / `5.1.2.1` / `15.3.1.1` / `15.3.1.3`）仍需人工拍板拆环。
6. **🟡 孤儿容器** `bf2e230f0db0 postgres:15-alpine`（存活 5 天、无 testcontainers 标签）
   不计入闸值但会虚高计数，**需单独清理**（本轮未动）。
7. **🟡 台账与代码的临时不一致**：车道 1.1.2.1 已在自己分支把台账改成 407/58，
   集成头仍是 406/59 ⇒ **下一轮并入后必然出现一次台账数字跳变**，属预期，不是台账与代码不符。

---

## 十四、文件清单

**本轮调度者写入集成头的文件**：

- `docs/sessions/2026-10/2026-10-25-batch-98-lane-dispatch-l2-contention-triage.md`（本文件，新增）

**本轮新建的辅助 worktree**：

- `/Users/guan/Projects/starcon-worktrees/l2-verify`（detached @ `1a227ded`，L2 后端 9 服务分服务验证）

**本轮新增的运维脚本与日志**（均在 `.dev-agent/`，gitignored）：

- `.dev-agent/l2-run-round98.sh`（分服务 L2 驱动，三波）
- `.dev-agent/l2-frontend-round98.sh`（前端四项 + `sync --check`）
- `.dev-agent/keepalive-r98.sh`（调度闸续期守护，每 4 分钟新建文件刷新闸目录 mtime）
- `.dev-agent/l2/l2-*.log`、`.dev-agent/l2/frontend-round98.txt`

**车道成果（在车道分支上，下一轮并入）**：

- **1.1.2.1 核心指标卡**（reporting，**7 个 commit**，head `b9b66479`，**已 `land`**）
- **1.3.2.1 预警规则**（identity，**7 个 commit**，head `f7185dd5`，**已 `land`**）

**下一轮 `merge-ready` 的复核清单**（两条车道共同改动 `apps/web/src/lib/api-proxy.ts`）：

| 检查项 | 期望 |
| --- | --- |
| 本批改动量 | **纯新增**（A +44 行 / B +42 行），**零删除行** |
| `reporting/metric-cards` 条目在 | ✅ |
| `identity/alert-rules` 条目在 | ✅ |
| 既有 239 条元素是否被动过 | ❌ **一条没动**（diff 里零删除行、零重排） |
