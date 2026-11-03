# batch-119 · 18.1.2.2 投递记录 + 18.1.1.3 调用监控 —— 并入与静态预检全绿，但 **L2 在 channel 判红**

> 批次号 **batch-119**（接着 batch-118 往后排；本分支上可能有别的 agent 同时在写）。
> 产出本轮的是 **round-142（2026-10-31 13:00 tick）**。
>
> **本轮一句话**：两条车道（18.1.2.2 投递记录 / 18.1.1.3 调用监控）**已并入集成头、静态预检零命中、
> api-proxy 白名单回归核对通过**，但 L2 全量在 **channel** 判红 —— 根因是
> **channel 测试夹具把种子时间硬编码成 2026-10-01 等固定日期，而 channel 服务用「`LocalDate.now(UTC)` 滚动 30 天」
> 作为默认取数窗口** ⇒ **墙钟跨过 2026-10-30 03:00 UTC 那一刻起必然红**。
> **这是与本批无关的存量缺陷（同样代码在 2026-10-30 的 batch-116 L2 还是全绿的）**，
> 本批 `backend/services/channel/` 的 diff **为空**。
>
> ⇒ **L2 未绿，本轮不 push**；已派一条**定点修复车道**（只改 channel 测试夹具，不改服务行为），
> 连同 2 条新模块车道一起交给下一轮 tick 并入 + 跑全量 L2。

---

## 一、进度（`sync.mjs` 实时值，本轮自己跑的第一件事）

| 项 | 值 |
| --- | --- |
| 模块总数 | **465** |
| 已交付 | **439**（**94.4%**） |
| 进行中 | 0 |
| 未开发 | **26** |
| 离全部交付还差 | **26 个** |

分阶段（并入后）：**D7 132 已交付 / 23 未开发**；D6 75 / 3；D1–D2 80 / 0；D5 52 / 0；D3 40 / 0；D4 60 / 0。

台账完整性复核：`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ '` → **465**；
`uniq -d` → **空**（无重复 ID）；`sync.mjs --check` → **✓ 台账与蓝图一致**（exit 0）。

## 二、强度档位与容量闸（判定 **GO**）

| 项 | 值 | 判定 |
| --- | --- | --- |
| 强度档位 | **reduced**（`TOKEN_HORIZON=2026-10-25` 已过期） | `LANES=4/2=2`，节流闸 `50×2=100` 分钟 |
| 批次节流闸 | 距上批 **174 分钟** ≥ 100 | ✅ 放行（`last-batch-at` **在通过闸门之后**才写） |
| 温度闸 | `pmset -g therm` → `No thermal warning level has been recorded` | ✅ 无降频项 |
| 负载闸 | load **1.51 / 1.55 / 1.71**（核数 10） | ✅ 远低于阈值；`1min < 5min` 未在爬升 |
| 配额闸 | running **0** / 配额 6 | ✅ |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` = **0**；**无标签孤儿容器 = 0** | ✅ 无需清理 |
| 调度闸 | `gate status` → `free` / `stale=false` → `acquire` 成功 | ✅ |

## 三、本批回收（2 条车道，0 冲突）

| 模块 | 名称 | 分支 | 迁移版本 | 并入 commit |
| --- | --- | --- | --- | --- |
| **18.1.2.2** | 投递记录 | `feat/18-1-2-2` | V413 | `15906d76` |
| **18.1.1.3** | 调用监控 | `feat/18-1-1-3` | V414 | `c510e6c9` |

`merge-ready` → **0 冲突**；`sync.mjs` 刷新台账（437 → **439 已交付**）；`gc` 回收 2 个 worktree。

`merge-union` 报了「行级兜底并集 + 台账按模块 ID 求并集」——**那两条告警是同一次调用、都指向台账文件**
（`module-progress.md`），**不是 `api-proxy.ts`**（后者 git 直接干净求并，0 冲突）。

## 四、静态预检 + api-proxy 白名单回归核对（两关都过）

**① 已知互相破坏形状扫描**（本批 4 个新迁移）：

| 检查 | 结果 |
| --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | ✅ **零命中** |
| `SET NOT NULL` | ✅ **零命中** |
| `ADD CONSTRAINT … CHECK` | ✅ **零命中** |

**② `apps/web/src/lib/api-proxy.ts` 白名单回归核对**（协议点名的唯一「绿但坏」形状）——
两条车道都改了这个**共享累积正则**文件，所以**必须**核对「有没有静默删掉别人已交付的路由」。
调度者侧用 **TypeScript 编译器 AST 解析 `services` 变量的真实数组字面量**（不是文本计数），
逐条取 `prefix` + `pattern`，按 `resolve()` 同构口径比较：

| 判据 | 结果 |
| --- | --- |
| 基线 `bf9933a4` → HEAD 的条目数 | **270 → 272** |
| 旧条目**全部保留**（新集合 ⊇ 旧集合） | ✅ **PASS，移除 0 条** |
| 无重复条目 | ✅ PASS |
| 新条目不覆写既有 `pattern` | ✅ PASS（新增 2 条独立元素：`deliveries` / `call-monitor`） |

⇒ **没有一条既有路由被取错一侧删掉**。（脚本落在 `.tmp/verify-api-proxy.mjs`，不入库。）

## 五、L2 批量验证结果：🔴 **红**（根因已定位到行）

### 5.1 各步结果

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 1 后端全量 | `cd backend && ./mvnw verify` | ❌ **BUILD FAILURE**（01:01 h）：`backend` ✅ / `tenancy` ✅ / `identity` ✅（644 例）/ `catalog` ✅（1999 例）/ **`channel` ❌（2484 例，14 failures + 7 errors）** |
| 2 台账一致性 | `node tools/module-progress/sync.mjs --check` | ✅ exit 0「✓ 台账与蓝图一致」 |
| 3 增量格式闸 | `node tools/module-progress/format-gate.mjs` | ✅ exit 0，`violations: []` |
| 4 类型检查 | `pnpm typecheck` | ✅ exit 0 |
| 5 Lint | `pnpm lint` | ✅ exit 0 |
| 6 前端全量 | `pnpm vitest run --maxWorkers=4` | ✅ exit 0 —— **696 文件 / 9826 例全绿**（24.07 s） |
| 7 前端构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ exit 0 —— `✓ Compiled successfully in 20.1 s` + `✓ Generating static pages (401/401)` |

> ⚠️ **步骤 6 用的是有界并发 `--maxWorkers=4`**，不是裸 `pnpm test`。
> 依据 batch-116 的取证：本机 10 核 + Docker 虚拟机常驻 + swap 4.3GB，vitest 4 按核数自动拉 forks 池会
> 「`Failed to start forks worker`」→ 用例超时 → **且静默漏跑文件（错误数不计入总数，只看 `Test Files` 发现不了）**。
> 696 = 全量文件数（batch-116 是 692，差额恰为本批新增的 4 个前端测试文件），**没有漏跑**。
>
> ⚠️ **步骤 1 在第 5 个模块就中断了** ⇒ 后 6 个模块（warehouse / trade / crm / finance / reporting / **platform**）
> **根本没跑到**。其中 **platform 正是本批两个模块所在的服务** ⇒ 它在本轮 L2 里**一次都没被执行**。
> 为此调度者**单独补跑**了 platform 模块的全量测试（见 5.4）。

### 5.2 红的形状：**21 个失败全部落在 `channel`**

失败测试类（6 个）与数量：

| 测试类 | 失败形态 |
| --- | --- |
| `ListingKeywordRankingIntegrationTest` | `historyIsPaginated` 期望 **5** 实得 **4**；`reportAggregatesByKeyword` 期望 **3** 实得 **2** |
| `ListingKeywordRankingTenancyIntegrationTest` | 报表 / 告警列表 **为空**（`Expecting actual not to be empty`） |
| `AdKeywordIntegrationTest` | `ctr` 为 **null**（impressions 变 0 ⇒ CTR 无从计算） |
| `AdKeywordTenancyIntegrationTest` | impressions 期望 **1000L** 实得 **0L** |
| `InfluencerAttributionIntegrationTest` | 归因行 **0 条** ⇒ `items().get(0)` 抛 `ArrayIndexOutOfBounds` |
| `InfluencerAttributionTenancyIntegrationTest` | 同上（3 例全 ERROR） |

**与本批的关系：没有关系。** 三条独立证据：

1. **代码差异为空**：本批 diff `bf9933a4...HEAD -- backend/services/channel/` → **空**。
   本批只碰了 `platform`（新服务代码）、`identity`（权限迁移）、`apps/web`、`packages/api-contracts`。
2. **同样的 channel 代码在 batch-116 是全绿的**：那 3 个 channel 提交
   （`0dc97714` 竞价管理 / `bba5b446` 商品维度报表 / `f0cc39c0` 归因分析，2026-10-29）
   **全部已在 batch-116 那次 L2 全绿的树 `b5476e82` 里**，且 `backend/services/channel/` 的内容与本轮**逐字节相同**。
3. **隔离复跑完全可复现**（不是资源假红）：机器安静后单跑
   `./mvnw -pl services/channel -am test -Dtest='ListingKeywordRanking*,InfluencerAttribution*,AdKeyword*'`
   → **同样的 14 failures + 7 errors、同样的 6 个类**（92 例）。
   ⇒ 确定性失败，**不是**协议里记的那种「`seed()` 503 / 连接拒绝」资源假红。

### 5.3 根因：**测试夹具硬编码 2026-10 日期 vs 服务「滚动 30 天」默认窗口**（墙钟触发）

服务侧（4 个 channel 服务都用同一套「以当下为锚」的默认取数窗口）：

| 服务 | 默认窗口 | 代码位置 |
| --- | --- | --- |
| `ListingKeywordRankingService` | `toDate = LocalDate.now(UTC)`；`fromDate = toDate.minusDays(29)` | `dayRange()` 第 230–246 行 |
| `AdKeywordService` | 同上（`minusDays(29)`，4 处） | 第 361/365、992/993、1820/1821、2349/2350 行 |
| `InfluencerAttributionService` | `OffsetDateTime.now()` 判合作是否在归因窗口内，窗口外的合作被 `skippedOutOfWindow` 跳过 | 第 1061 行 |
| `ListingChangeService` | `LocalDate.now(UTC).minusDays(30)`（**当前绿，但同形状 ⇒ 潜在同类失败**） | 第 1708 行 |
| `ListingHijackerService` | `toDate.minusDays(29)`（当前绿，其 `alerts` 查询未套该窗口） | 第 1392 行 |

测试侧：channel 的集成测试把种子时间**硬编码**成 2026 年 10 月，例如

```java
// ListingKeywordRankingIntegrationTest
static OffsetDateTime at(int day, int hour) {
  return OffsetDateTime.of(2026, 10, day, hour, 0, 0, 0, ZoneOffset.UTC);
}
```

⇒ 种子落在 **2026-10-01 ~ 2026-10-05**。

**墙钟翻页的那一刻**：`fromDate = today − 29d`。

| 日期 | `fromDate` | 2026-10-01 03:00 的种子行 | 结果 |
| --- | --- | --- | --- |
| 2026-10-30（batch-116 L2 当天） | 2026-10-01 00:00 | **在窗口内** | ✅ 全绿 |
| **2026-10-31（本轮 L2 当天）** | **2026-10-02 00:00** | **掉出窗口** | ❌ 红 |

⇒ **精确对应观察到的数字**：`history` 种子 5 行（10-01~10-05）→ 掉 1 行 → **实得 4**；
`report` 种子 3 行 → 实得 **2**；AdKeyword 的指标行在 10-01 → impressions **0** → CTR **null**；
归因合作在 10-01 → 全部 `skippedOutOfWindow` → 归因行 **0**。

**结论：这是测试夹具的墙钟依赖（测试 bug），不是产品缺陷，也不是本批引入的回归。**
服务的「默认最近 30 天」是合理的产品行为；**该改的是夹具的日期来源，不是服务行为**。

### 5.4 补跑：platform 模块（本批两个模块所在的服务）**独立全绿**

因 §5.1 步骤 1 在 channel 就中断、platform 从未执行，调度者单独补跑
`./mvnw -pl services/platform -am test` ⇒ **BUILD SUCCESS，215 例全绿（Failures 0 / Errors 0 / Skipped 0）**：

| 测试类 | 例数 | 结果 |
| --- | --- | --- |
| `DeliveryRecordIntegrationTest` | 21 | ✅ |
| `DeliveryRecordTenancyIntegrationTest` | 6 | ✅ |
| `EventSubscriptionIntegrationTest` | 25 | ✅ |
| `EventSubscriptionTenancyIntegrationTest` | 6 | ✅ |
| `ApiCallMonitorIntegrationTest` | 15 | ✅ |
| `ApiCallMonitorTenancyIntegrationTest` | 11 | ✅ |
| **platform 模块合计** | **215** | ✅ **0 失败 0 错误** |

（另含 `ApiCatalogIntegrationTest` 22 / `PlatformAppCredential*` 26 / `FinanceSoftwareIntegrationTest` 32 /
`TextTranslationIntegrationTest` 25 / `OpenApiContractTest` 1 等既有模块，全部通过。）

⇒ **本批两个模块自己的后端代码（含 V413 / V414 迁移、RLS、租户三断言）本身是绿的**，
本轮 L2 红的是**存量 channel 测试夹具**，与本批交付物无关。

### 5.5 处置（按协议「L2 红 ⇒ 不 push、不在集成头上改」）

| 步骤 | 动作 | 结果 |
| --- | --- | --- |
| 1 | 不 push、不在集成头上改代码 | ✅ |
| 2 | 二分定位 | **不做二分** —— 本批 `channel/` diff 为空且同样代码昨天是绿的，二分必然落在「本批之外」；根因已由 §5.3 直接定位到行（`at()` helper + `dayRange()`），比二分更快且可复现 |
| 3 | 派修复任务 | ✅ 派**定点修复车道**（见下） |
| 4 | 重跑 L2 | 交下一轮 tick（修复车道 + 2 条新模块车道并入后一起跑一次全量） |
| 5 | 连续 3 轮红 ⇒ 零派发 | **不触发** —— 本轮是第 1 轮，且**已定位** |

---

## 六、模块 18.1.2.2 投递记录

**这个功能做什么**：租户登记了 webhook 订阅之后，**事件真的会发出去了** —— 并且每一次投递都有明细可查：
发了什么、对方返回什么码、重试了几次；发不出去的死信可以人工重放。此前 18.1.2.1「事件订阅」把
「事件实际投递」如实答成「未接入」（端点恒 409、一次包都不发），本模块补上的正是那一格。

**技术落地**：

- **迁移**：
  - `backend/services/platform/.../V413__webhook_delivery_record.sql` —— 建 **4 张表**
    `platform_delivery_record` / `platform_delivery_attempt` / `platform_delivery_dead_letter` /
    `platform_delivery_replay_task`；全部 `tenant_id NOT NULL` + **`ENABLE` 与 `FORCE` RLS**
    （policy `isolation`，按 `app.current_tenant` 隔离）+ 14 个索引（含
    `platform_delivery_record_retry_due` 等重试扫描用索引）+ `GRANT` 给 `platform_app`。
  - `backend/services/identity/.../V413__webhook_delivery_record_permission.sql` —— 登记
    `platform.delivery.read` / `.update` 权限码并补存量 OWNER。
- **端点**（11 条，来自生成的 `platform.openapi.json`）：
  - `GET /platform/deliveries` —— 投递记录列表（真分页真筛选）
  - `GET /platform/deliveries/capabilities`、`GET /platform/deliveries/overview` —— 能力声明 / 概览
  - `GET /platform/deliveries/{deliveryId}` —— 投递详情
  - `GET /platform/deliveries/{deliveryId}/attempts` —— 投递日志（每一次尝试一行）
  - `POST /platform/deliveries/{deliveryId}/replay` —— 手动重投
  - `GET /platform/deliveries/dead-letters` —— 死信列表
  - `POST /platform/deliveries/dead-letters/{deadLetterId}/replay` —— 死信重放
  - `GET /platform/deliveries/replay-tasks` —— 重放任务列表
  - `POST /platform/deliveries/publish` —— 发布一个事件并**真的**投递
  - `POST /platform/deliveries/retry-due` —— 到期重试扫描（外部调度触发的一次入口）
- **页面**：`/integrations/deliveries` —— 投递记录工作区（`apps/web/src/app/integrations/deliveries/page.tsx`
  + `components/integrations/deliveries/delivery-record-workspace.tsx` 857 行）
  —— 真分页真筛选、投递详情 / 尝试日志 / 死信 / 重放任务四个视图、加载态 / 空态 / 错误态齐全，
  「响应码 0」显示成「**没有响应**」而不是「0」或「未知」。
- **测试**：
  - `DeliveryRecordIntegrationTest` —— **21 例**（真实 PostgreSQL 16.15）
  - `DeliveryRecordTenancyIntegrationTest` —— **6 例**（租户隔离三断言，连接池压到 1 条）
  - `EventSubscriptionIntegrationTest` —— 25 例（3 条「未接入 ⇒ 409」老断言改成「已接入 ⇒ 真发包」）
  - 前端 `delivery-record-model.test.ts` —— **19 例**；`delivery-allowlist.test.ts` —— **6 例**（白名单守卫）

**关键设计决策**：

1. **「至少一次」落在唯一键上，不落在代码纪律上**：
   `UNIQUE(tenant_id, event_id, subscription_id)` ⇒ 同一事件对同一订阅只有一条投递记录，
   重试/重放都在这一行累加 `attempt_count`；而「投递日志」是**每一次尝试**一行
   （`UNIQUE(tenant_id, delivery_id, attempt_no)`）。两个粒度刻意不同 ——
   混成一张表就没法回答「这个事件一共投了几次」。
   **否决方案**：在平台侧假装「恰好一次」。蓝图那句「接收方应按 event_id 幂等去重」正是它的另一半 ——
   把去重责任推给接收方，比在平台侧编造「恰好一次」诚实。
2. **记录先落库，包后发出去**：反过来（先发包后落库）崩在中途 ⇒ 对方收到了、我们没有记录 ⇒
   「投没投出去」变成答不出来的问题。发包前崩了 ⇒ 留下一条 PENDING，重试扫描会捡起来。
   **代价**：一次慢投递会占住一条 HTTP 连接最长 120 秒 ⇒ `DeliveryRecordController` **刻意不用**
   `access.run(...)`（那会把调用包在一个数据库事务里），自己控制事务边界：
   阶段一（事务）读订阅 / 解析策略 / 解密密钥 / 落记录并提交 → 阶段二（无事务）真的发 HTTP →
   阶段三（事务）把响应码写进投递日志、更新状态、必要时转死信。
3. **响应码 0 是合法值**：它表示「**根本没拿到响应**」（连接被拒 / 超时 / 没发出去）。
   翻成 200 或 -1 都是在编造事实。
4. **原始报文字节落库（Base64）**：签名盖的是**原始字节**。重放时若重新序列化，字节序 / 空格可能变 ⇒
   验签对不上。存原始字节 ⇒ 重放发出去的就是那一份，验签与对端去重都成立。
5. 🔴 **`api-proxy.ts` 只做纯增量，不做全文件 prettier 重排** —— 本车道最重要的工程决策：
   18.1.2.2 把新条目插在数组**开头**（紧邻 18.1.2.1），18.1.1.3 把新条目插在数组**末尾**，
   两个插入点相距 13000 行以上 ⇒ git 能干净求并。车道用 scratch clone 实跑真实 `git merge` 测过 5 种变体：
   **prettier 全文件重排在两种并入顺序下都必冲突**，纯增量两种顺序都 **0 冲突**。
6. 🔴 **修掉一处前任遗留的真实缺陷**：前任把 `dead-letters` 挂在字面量分支里、又把 `{uuid}` 段独立跟在后面 ⇒
   `platform/deliveries/dead-letters/{uuid}`（后端**没有**这个 GET 端点）被放行了 ——
   而同一段注释里恰恰写着「刻意不放行」。现改成**三个平级互斥分支**。
   这个缺陷是靠新写的白名单守卫测出来的，变异测试验证：换回旧正则该用例立即红。
7. **对 18.1.2.1 的越界改动只有一处，且是必须的**：18.1.2.1 有一条**全库子串枚举**守卫
   （`table_name like '%delivery%'` ⇒ 断言全库没有任何投递表），已被本模块合法建表推翻 ⇒
   改成**显式表名清单 + schema 限定**的**正面**断言。
   `EventSubscriptionService.capabilities()` 三维按**实际实现**如实改：
   「事件实际投递」「死信重放」`available=true`，而「**上游 outbox relay**」**保持 false** 并写清依据
   （跨服务读业务服务的 outbox 违反模块边界纪律）—— **绝不为让测试绿而写成假的 true**。

---

## 七、模块 18.1.1.3 调用监控

**这个功能做什么**：外部系统调你的 API 时，**出错能被快速定位** —— 谁调的、哪个接口、多慢、
被配额挡下来没有、错误率多少，以及超阈值时自动开告警并可确认 / 闭环。

**技术落地**：

- **迁移**：
  - `backend/services/platform/.../V414__api_call_monitor.sql` —— 建 **2 张表**
    `platform_api_call_log`（埋点，**只追加**）与 `platform_api_call_alert`（告警）；
    全部 `tenant_id NOT NULL` + `ENABLE` 与 `FORCE` RLS + `GRANT`；
    **只追加**由两道闸锁住：`GRANT` 只给 `SELECT, INSERT`（不给 UPDATE/DELETE）**且**
    用**触发器**在数据层拒绝 UPDATE/DELETE（少授一个权限码会被「迁移脚本用属主身份跑」绕开，触发器不会）；
    另建 `platform_api_credential_lookup(text)` **SECURITY DEFINER** 函数做跨租户凭证定位；
    `platform_api_call_alert_open_unique` 部分唯一索引保证「未闭环期间同一异常只一行」。
  - `backend/services/identity/.../V414__platform_call_monitor_permission.sql` —— 登记
    `platform.call-monitor.read` / `.update`（**有端点的权限码才注册**）+ 补存量 OWNER。
- **端点**：
  - 监控侧 **12 条**：`GET /platform/call-monitor/capabilities`、`/options`、`/overview`、`/trend`、
    `/apps`、`/endpoints`、`/slow-calls`、`/calls`（调用明细，真分页真筛选）、`/alerts`；
    `POST /platform/call-monitor/alerts/scan`（扫描并开告警）、
    `POST /platform/call-monitor/alerts/{alertId}/acknowledge`、`/resolve`。
  - 网关侧 **8 条**：`/open/v1/ping`、`/echo`、`/quota`、`/scopes`、`/credential`、`/calls`、`/calls/{requestId}`，
    以及 `/open/v1/**` 的兜底 404。
- **页面**：`/integrations/call-monitor` —— 五个页签工作区
  （`components/integrations/call-monitor-workspace.tsx` 938 行 + `call-monitor-model.ts` 325 行）
  —— 概览 / 趋势 / 维度 / 慢调用 / 告警五视图，阈值口径显式给出（平台固定口径、**零预置行**），
  不可用下游（卷 01 工作台告警、18.1.1.1 自动配额调整）显式标 `available=false`，
  加载态 / 空态 / 错误态齐全。
- **测试**：
  - `ApiCallMonitorIntegrationTest` —— **15 例**（真实 PostgreSQL 16.15）
  - `ApiCallMonitorTenancyIntegrationTest` —— **11 例**（租户三断言，连接池压到 1 条）
  - 前端 `call-monitor-model.test.ts` —— **7 例**；`call-monitor-allowlist.test.ts` —— **5 例**（白名单双向断言）

**关键设计决策**：

1. 🔴 **网关入口由本车道自建 vs 停下来说明** —— 任务书给的两条路是「把它做成真数据」或「停下来说明」，
   本车道选了前者。理由：蓝图把「上游 = API 网关」写进了模块定义，而**网关不是本模块能顺带交付的东西**
   （它是独立的基础设施决策）；但**埋点是本模块的职责**（蓝图原文「输入：调用埋点」）。
   不补网关就没有埋点 ⇒ 没有埋点就只能编数据 ⇒ 那才是真正违反「禁止 mock」。
   ⇒ 网关做成**最小但真实**的形态：放行清单**封闭 7 条**、路径参数按段数与段形状判、**兜底 404、不做路径转发**
   （放行任意路径等于开放代理，能探测内网；放行后端不存在的映射比 404 更糟）。
2. 🔴 **跨租户凭证定位函数不做 `LIMIT 1`**：`UNIQUE(tenant_id, api_key_lookup)` 允许**同一个 Key 值在两个租户下各登记一次** ⇒
   `LIMIT 1` 就是「随便挑一个」= 把 A 的调用算到 B 头上。函数返回全部候选行，Java 侧逐行比 BCrypt，
   比中唯一一行才放行，比中多行判为歧义并拒绝。**代价**是 O(候选数) 次 BCrypt 比对，收益是歧义时拒绝而不是静悄悄挑一个租户。
3. **五种失败（凭证错 / 应用吊销 / 范围不足 / IP 不在白名单 / 超配额）全部落埋点** ——
   它们恰是对接方最需要被定位的失败（蓝图原话「外部系统调用错误要能被快速定位」）。
   **定位不到租户的调用则不落埋点**（没有租户可归属，硬塞一个等于伪造归属）。
4. **限流命中 vs 错误：两列分开**（`outcome` + `rate_limited`），迁移里用 CHECK 把结论码与状态码**逐字钉死** ——
   「有多少次被配额挡下来」与「有多少个 4xx」是两个问题；两列一旦各说各话，页面上两个数字就互相打架。
5. 🔴 **修 flaky 的方向是「固定时钟」，不是 sleep / 重试 / 放宽断言**：
   根因不是「时钟不准」，而是**「窗口是离散的」** —— 网关判限流时窗口起点是「现在」按窗口类型**截断**出来的
   （MINUTE ⇒ 截断到分钟）。原来 `OffsetDateTime.now()` 散在服务里 ⇒ 一次 200 + 一次应 429 的两次调用
   只要**跨过一次分钟边界**就落进两个窗口 ⇒ 配额判定被清零 ⇒ 本该 429 的那次反而放过去 ⇒ 用例随机红
   （典型概率 = 调用间隔 ÷ 60 秒）。**修法**：新增 `ApiCallMonitorClockConfiguration`
   （照抄 trade 的 `ShipmentWritebackClockConfiguration` 那一套纪律：生产 = `Clock.systemUTC()`，
   测试用 `@Primary` 的 `Clock.fixed(...)` 覆盖那一个 Bean）。
   **顺带修掉三处「本来就不该那样」的判定**：① 配额视图与配额执行必须同一个窗口；
   ② 告警的「建议配额」要读同一个窗口（原来会读到另一个窗口的用量，把「用得最满的窗口」挑错）；
   ③ 按**事实列**断言而不是按行序断言（原来断言 `rows.get(1)`，而 `called_at` 是 DB 时钟、两行可能落在同一微秒）。
6. **埋点表只追加**：GRANT 只给 `SELECT,INSERT` **且** 触发器在数据层拒绝 UPDATE/DELETE —— 两道闸都留着
   （少授一个权限码会被「迁移脚本用属主身份跑」绕开，触发器不会）。
7. **告警去重**：未闭环期间同一异常只一行（部分唯一索引）+ 重复扫描只刷新指标 + 指标回落后自动闭环 ——
   否则告警列表会被同一件事刷成几十行，运营分不清「现在还好吗」。
8. **阈值口径是平台固定口径、零预置行**，但必须随 `/options` 显式给出（运营看到告警要知道是按什么判的）。
9. 🔴 **网关入口不进浏览器 api-proxy 白名单**：它是给**外部系统**调的路径，租户来自 API Key 反查。
   放进浏览器白名单等于让任意浏览器带着别人的 Key 来打 ⇒ 刻意不放行，并有**负向用例**钉住。
10. **工作台告警 / 自动配额调整如实答 `available=false`**：卷 01 工作台告警至今未交付；
    改配额是写操作，**不该由告警副作用触发** —— 本模块只给建议并指路。

---

## 八、收尾三件套（本轮状态）

| # | 环节 | 状态 |
| --- | --- | --- |
| ① | **写说明** | ✅ 本文件 `docs/sessions/2026-10/2026-10-31-batch-119-delivery-record-and-call-monitor.md`（覆盖 18.1.2.2 / 18.1.1.3 两个模块，各一节） |
| ② | **commit** | ✅ 说明文件与台账（`module-progress.md`）**同一个 commit**：`docs(batch-119): 18.1.2.2 + 18.1.1.3 —— 投递记录与调用监控交付；L2 在 channel 因测试夹具墙钟依赖判红，本轮不 push` |
| ③ | **push** | ❌ **不 push** —— **L2 未绿**（channel 21 例失败，根因见 §5.3）。成果与诊断留在集成头本地，修复车道落地后随下一批一起 push |

> 协议明确：L2 没绿不 push，但**说明照写、commit 照做**（记清红在哪、根因到哪一行、派了哪条修复车道）。
> ⚠️ push 未发生这件事**不是遗漏**，是协议要求；本轮缺的是第 ③ 件，且原因已完整记录。

## 九、后续待办 / 本轮派发

### 9.1 定点修复车道（本轮派出）

| 项 | 值 |
| --- | --- |
| 车道 ID | **14.3.1.3**（`lane.mjs adopt` 收编，`migrationVersion = null`，**不改服务代码、不加迁移**） |
| 分支 / worktree | `fix/channel-fixture-window-2026-10` / `.worktrees/fix-channel-fixture-window` |
| 基线 | `c510e6c9`（本批并入后的集成头） |
| 任务范围 | **只改 channel 测试夹具的日期来源**（`ListingKeywordRanking*` / `AdKeyword*` / `InfluencerAttribution*` 三个模块的 6 个红测试类，**外加**当前绿但同形状的 `ListingChangeIntegrationTest`），把硬编码的 2026-10 日期改成**相对 `LocalDate.now(ZoneOffset.UTC)`**；**服务代码一行不动**、**断言一条不放宽** |
| 验证 | L0 `./mvnw -q compile` + L1 收窄到上述测试类（**不跑全量 verify** —— 那是 L2） |
| 落地 | `lane.mjs land --lane 14.3.1.3`（并入由调度者做） |

### 9.2 本轮新派模块车道（`plan --lanes 2`，`newlyPlanned` 非空、`serviceBlocked` 为空）

| 模块 | 名称 | 阶段 | L3 | 迁移版本 | 分支 |
| --- | --- | --- | --- | --- | --- |
| **18.1.3.1** | 导入导出任务 | D7 | 18.1.3 | **V415** | `feat/18-1-3-1` |
| **18.2.2.1** | 支付与资金 | D7 | 18.2.2 | **V416** | `feat/18-2-2-1` |

两条 L3 锚点互不相同 ⇒ 互不依赖。

### 9.3 下一轮 tick 要做的三件事

1. `merge-ready` 并入**3 条**车道（修复车道 + 2 条新模块车道）⇒ `sync.mjs` ⇒ `gc`；
2. 跑**一次**全量 L2（`mvnw verify` + `typecheck`/`lint`/`vitest --maxWorkers=4` + Next build + `sync --check` + `format-gate`）
   —— **channel 应当恢复全绿**（若仍红 ⇒ 说明修复没覆盖到，按协议二分并点名）；
3. L2 绿 ⇒ 写 batch-120 说明 → commit → **push**（把本批 18.1.2.2 / 18.1.1.3 与修复一并推上远端）。

### 9.4 其它待办

- **仓库里还有大量 2026-10 硬编码夹具**（finance / reporting / warehouse / trade 都有），
  但那些服务**不做 `now()` 滚动窗口**，当前全绿 ⇒ **本轮不动**，避免用存量债压垮增量流程。
  等有独立立项时再统一处理。
- `docs/site/capability-map` 的页面数字滞后于台账（与 18.1.2.1 的先例一致），刷新是维护者层级的单独动作，CI 不检查。
- **渠道测试夹具的墙钟依赖是一个系统性隐患**：本轮只修了会红的那几个类，
  但任何新增的「`now()` 滚动窗口 + 固定日期夹具」组合都会在某个日子复现同样的失败
  ⇒ 建议在 `AGENTS.md` 的测试约定里补一条「涉及滚动窗口的夹具一律用相对日期」。
