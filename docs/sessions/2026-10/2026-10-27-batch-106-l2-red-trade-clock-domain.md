# 2026-10-27 · batch-106 · L2 批次闸定位到 trade 跨钟域缺陷 + 三车道派发

> 本轮**没有模块落地**（三条车道在后台跑，下一轮 tick 回收），
> 但**本轮是 batch-105 那 30 个 commit 的 L2 验收轮** —— L2 红了，归因已定，
> 修复车道已派出。**收尾三件套的第 ③ 件（push）本轮缺失**，原因见「为什么不 push」。

## 决策摘要

| 项       | 值                                                                                                       |
| -------- | -------------------------------------------------------------------------------------------------------- |
| 档位     | **reduced（降档）** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过（当前 2026-10-27 11:00）              |
| 车道数   | `LANES` 4 → **2**（减半）；节流闸 `THROTTLE_MIN` 50 → **100** 分钟                                        |
| 台账     | **414 / 465 已交付（89.0%）**，进行中 0，未开发 **51**；465 行、0 重号                                      |
| 节流闸   | 距上批 **237** 分钟 ≥ 阈值 **100** ⇒ **放行**（通过后才写 `last-batch-at`）                                 |
| 调度闸   | `gate=free` / `stale=false` → **已取闸**（TTL 4200s，keepalive 续期到收尾）                                 |
| 回收     | `merge-ready` 无待并入 · `gc` 无可回收                                                                    |
| 静态预检 | V387 / V388 / V389 三条新迁移：① 无 DEFAULT 的 ADD COLUMN **0** · ② SET NOT NULL **0** · ③ CHECK **0**      |
| L2       | **第 1 跑 11:02:59 → 14:07:18（3h04m）🔴 红** —— 后端红于 trade 两例；typecheck / lint / test / build 全绿     |
| 归因     | **不是本批回归**：30 个待推送 commit **零文件**触及 `backend/services/trade/`；同一份代码上一轮 trade 绿       |
| 派发     | **3 条车道**：13.1.2.2 达成追踪（V390）· 13.2.2.1 店铺与站点（V391）· **fix-trade-clock-domain（新建修复车道）** |
| 说明文件 | 本文件                                                                                                    |
| commit   | ✅ 已做（说明文件 + 台账同一个 commit）                                                                     |
| push     | ❌ **本轮不做** —— **L2 未绿**（「L2 没绿绝对不 push」是最高优先级禁令）                                       |

## 容量闸判定（只读，四条全过 ⇒ GO）

| 闸         | 阈值      | 实测                                                                       | 判定     |
| ---------- | --------- | -------------------------------------------------------------------------- | -------- |
| 温度闸     | —         | `pmset -g therm`：No thermal warning / performance warning / CPU power status has been recorded | ✅ 无降频 |
| 配额闸     | 6         | running **0**（两条 `running` 车道心跳均已过期，属**僵尸记录**非在途）     | ✅ 0/6    |
| PG 闸      | 6         | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` **0**；**孤儿容器 0** | ✅ <6    |
| load 闸    | 12        | **1.42**（1min）/ 2.14（5min）/ 3.31（15min）—— 三点**逐点回落**，非爬升形态 | ✅        |

- **无孤儿容器**：同一条 `docker ps` 里无标签的 `postgres:16-alpine` 数量 = **0**（不需清理）。
- 闸优先级（温度 > 配额 > PG > load）本轮**没有任何一条命中**。

## 本轮刻意改了「派发与 L2 的先后顺序」—— 理由是取证，不是偏好

协议 §5.2 的字面顺序是「派发（第 4 步）→ L2（第 5 步）」，
但同协议另一条禁令写着「**L2 不得与在途车道的 L1 并发跑**」。
两者在「本轮要派发车道」的前提下**互相冲突**，取舍要看取证：

- **第 109 轮**把 L2 与两条在途车道的 L1 **并发**跑了（`l2-r109.sh` 07:15:03 启动，
  同一分钟 `plan` 派出 13.1.2.2 / 13.2.2.1）⇒ L2 后端红于
  `reporting/StoreComparisonIntegrationTest` 的 **SSL 握手失败**（16 个 error），
  **隔离复跑 20/20 全绿** ⇒ 假红。
- **第 107 轮**已经踩过同一族坑并留了结论：「L2 与 4 条车道 L1 并发时 PG 峰值 16，
  1364 例里 3 failures + 24 errors **全是假红**」。

⇒ 本轮机器**空闲**（无在途车道、PG 容器 0、load 1.42），
**先跑 L2** 拿到的是**干净信号**，派发放到 L2 之后 ⇒ 既拿到真信号，
又不让「在途 L1 抢 Testcontainers」把 L2 搅成假红。
**代价**：三条车道比「先派后验」晚约 3 小时开工（下一轮 tick 才能回收）。

⚠️ **这个取舍本轮得到了验证**：L2 红在 trade，**而 reporting 模块 38.597 秒 SUCCESS** ——
上一轮假红的那一处**这次没复现**，且这次**没有并发 L1**。两条证据一致。

## L2 第 1 跑（11:02:59 → 14:07:18）—— 🔴 红，且**已定位**

```
[INFO] backend ............................................ SUCCESS [  0.001 s]
[INFO] tenancy ............................................ SUCCESS [  0.504 s]
[INFO] identity ........................................... SUCCESS [02:59 min]
[INFO] catalog ............................................ SUCCESS [05:23 min]
[INFO] channel ............................................ SUCCESS [16:15 min]
[INFO] warehouse .......................................... SUCCESS [13:19 min]
[INFO] trade .............................................. FAILURE [ 01:48 h]   ← 唯一红的一格
[INFO] crm ................................................ SUCCESS [01:20 min]
[INFO] finance ............................................ SUCCESS [34:09 min]
[INFO] reporting .......................................... SUCCESS [ 38.597 s]
[INFO] platform ........................................... SUCCESS [ 17.352 s]
=== [14:07:18] L2 SUMMARY backend=1 typecheck=0 lint=0 test=0 nextbuild=0 synccheck=0 ===
=== [14:07:18] L2 RESULT: RED ===
```

**本轮 L2 验证的对象** = 集成头上**尚未推送的 30 个 commit**：
batch-105 四车道（1.5.2.1 / 13.1.2.1 / 13.2.1.1 / 13.2.2.2）+
三条修复车道（api-proxy 字面量、identity 审批待办时钟、warehouse 货权档时钟）+
第 107/108/110 轮会话日志 + main 同步第 83–86 轮日志。

**前端四件全绿**（typecheck / lint / vitest / Next production build）+ **台账 `sync --check` 一致**。
红只在**后端 trade 一格**。

### 失败 ① —— `OrderSplitIntegrationTest.状态机单向推进且作废连带撤销预占指令`

```
[ERROR] Tests run: 36, Failures: 0, Errors: 1 <<< FAILURE! -- in com.starcon.trade.OrderSplitIntegrationTest
org.springframework.dao.DataIntegrityViolationException:
ERROR: new row for relation "order_split" violates check constraint "order_split_timestamps_ordered"
  在位置：SQL statement "UPDATE order_split SET status='CONFIRMED', confirmed_at=$5,
           updated_at=now(), version=version+1 WHERE tenant_id=s.tenant_id AND id=s.id"
PL/pgSQL function advance_order_split_status(...) line 12
	at com.starcon.trade.OrderSplitService.advanceStatus(OrderSplitService.java:1337)
```

**根因：约束比较的是两个不同钟域的时间。**

| 端                                | 写入方                                                                 | 用哪个钟     |
| --------------------------------- | ---------------------------------------------------------------------- | ------------ |
| `order_split.created_at`          | `OrderSplitService.split()` 的 INSERT 把 `OffsetDateTime.now()` **当参数绑进去** | **JVM 墙钟** |
| `order_split.updated_at`          | `advance_order_split_status()` PL/pgSQL 写 `updated_at=now()`            | **DB 时钟**  |

约束是 `CONSTRAINT order_split_timestamps_ordered CHECK(updated_at>=created_at)`（V151 第 305 行）
⇒ **DB 时钟只要比宿主慢 1 毫秒，一次合法的状态推进就被数据库挡下。**

### 失败 ② —— `PlatformDisputeTenancyIntegrationTest.连接池归还后不串租户`

```
[ERROR] com.starcon.trade.PlatformDisputeTenancyIntegrationTest.连接池归还后不串租户 <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: "RESOLVED_LOST" but was: "OPEN"
	at ...PlatformDisputeTenancyIntegrationTest.java:346
```

**根因：夹具用 JVM 墙钟造举证截止时刻，而超期判定在 DB 侧。**
夹具写 `evidence_deadline = OffsetDateTime.now(UTC).minusMinutes(1)`（宿主钟），
DB 守卫判 `evidence_deadline <= now()`（DB 钟）⇒ 宿主钟比 DB 钟快时，deadline 落在未来，
**不该过期却判成 OPEN**。
⚠️ 生产侧这一侧**是对的**（`evidence_deadline` 来自入参 `request.evidenceDeadline()`，
判定统一在 DB 钟域）⇒ **要改的是测试夹具**。

### 为什么判定为「本仓既有的跨钟域缺陷族」，而不是本批回归

| 取证维度                                 | 结果                                                                                     |
| ---------------------------------------- | ---------------------------------------------------------------------------------------- |
| 本批 30 个待推 commit 触及 `backend/services/trade/` | **零文件、零 commit**（`git log origin/integration/module-dev..HEAD -- backend/services/trade/` 空） |
| 两个失败测试类最后一次改动                 | `bdec8cb3`（11.4.1.1 平台纠纷）与 `f85807a4`（9.3.1.1 拆单）—— **都远早于本批**        |
| 同一份代码在第 109 轮 L2 的表现           | trade **SUCCESS [17:03 min]**，两个类分别 4/4 与 36/36 **全绿**                          |
| 隔离复跑（调度者实跑 3 次）               | `Tests run: 4/4` + `36/36`，`BUILD SUCCESS` × 3 —— **全绿**                              |
| 机器负载异常信号                           | `Thread starvation or clock leap detected` **488 次**（上一轮仅 **4** 次）；trade 耗时 **1h48m** vs 上一轮 **17m** |

⇒ **两个失败都是「机器负载抬高 → 容器钟与宿主钟的毫秒级偏差放大 → 边界比较被翻掉」**。
第 107 轮已在**真实容器内**实测过这个偏差：**容器钟落后宿主 0.35~1.0 ms（空载）/ 1.9~4.2 ms（加载）**
（`docs/sessions/2026-10/2026-10-27-0200-pipeline-round107-l2-red-fix-lanes.md` §3.5）。

⚠️ **一条需要纠正的既往结论**：第 107 轮曾用「另起一个全新容器」测偏差，
得出「容器钟**领先**宿主 +48~67ms」，并据此**否定**了跨钟域解释；
修复车道在**真实容器、真实时间点**上复测才把方向纠正回来（容器钟**落后**）。
⇒ **测时钟偏差必须在「真实的那个容器、真实的时间点」上测**，另起容器测出来的数不能解释既有失败。

### 为什么没有做 `git bisect`

协议允许最多 2 次全量二分。**本轮不做**，理由是取证已经把范围锁死：
待推送的 30 个 commit **零文件**触及 trade，而失败的两个测试类最后一次改动远早于本批
⇒ 二分**不可能**在「本批 merge commit」里找到红点，跑了也是白烧 3 小时机器时间。
**二分是给「本批引入的红」用的工具，本轮的红不属于本批。**

## 本轮派发的三条车道

| 车道                   | 名称                             | 分支                   | 迁移版本 | 性质                                                                                          |
| ---------------------- | -------------------------------- | ---------------------- | -------- | --------------------------------------------------------------------------------------------- |
| `13.1.2.2`             | 达成追踪                         | `feat/13-1-2-2`        | V390     | **续派**（上一轮 worker 会话被网络中断，代码已提交、工作区干净，**只差 L1 + land**）        |
| `13.2.2.1`             | 店铺与站点                       | `feat/13-2-2-1`        | V391     | **续派**（实现全部在位但**一个 commit 都没有**，台账第 421 行仍是「未开发」）                 |
| `fix-trade-clock-domain` | trade 跨钟域取时修复            | `fix/trade-clock-domain` | 无     | **新建修复车道**（r107 同款合成 ID 做法；对应 L2 定位到的两个缺陷）                          |

**三条车道互不依赖**：前两条在 **reporting 服务 + apps/web**（L3 锚点分别是 13.1.2 / 13.2.2），
第三条只在 **trade 服务**；文件面零重叠。
⚠️ 前两条都改 `apps/web/src/lib/api-proxy.ts`（共享白名单累积文件）——
**插入锚点已核对过**：13.1.2.2 插在 `13.2.1.1` 那条之后（基线第 176 行），
13.2.2.1 插在 `targets` 那条之后（基线第 322 行），**相隔 3 个条目 ⇒ 并入时 git 能干净求并**。

## 模块 13.1.2.2 达成追踪（V390）

**这个功能做什么**：给经营目标配一条「现在到哪了」的进度线 —— 当前值、达成率、
**按当前速度推算的期末达成**、未达标高亮、人员/产品维度排名、预警。
预测达成值比当前值更有管理价值：月初就知道会不会达标，才有时间干预。

**技术落地**：

- **迁移**：`V390__target_attainment.sql` —— 一张表 `reporting_target_attainment`（一个目标 × 一个评估日的达成快照），
  纯 CREATE TABLE、零 ALTER 既有表、零跨库外键；`tenant_id NOT NULL` + ENABLE **且** FORCE RLS
  + GRANT reporting_app / starcon_app + REVOKE ALL ON flyway_schema_history；
  与 `reporting_target` 之间是**同库复合外键 (tenant_id, target_id)** ⇒ 快照不可能挂到别的租户的目标上，
  目标被删时快照级联删除。
  **🔴 「算不出来」与「等于 0」做成两个正交受控词表 + CHECK 约束**：
  `actual_status`（AVAILABLE / NO_DATA / NOT_AVAILABLE）× `status`
  （ACHIEVED / ON_TRACK / AT_RISK / BEHIND / NOT_STARTED / NOT_COMPARABLE / UNAVAILABLE）——
  **0% 达成率是有业务含义的断言，「不可得」不是**。
  **🔴 时间进度与预测按「日历日」算**：elapsed_ratio = 已过日历日 ÷ 周期总日历日，
  预测期末值 = 当前值 × 总天数 ÷ 已过天数（**用精确比例算，不拿舍入后的比例去乘**）；
  elapsed_ratio = 0 ⇒ 预测算不出来（null，不是 0）；目标值不是正数 ⇒ 达成率无定义（NOT_COMPARABLE）。
  **🔴 排名不跨币种、不跨指标**（rank_group = 归属维度 + 口径码 + 币种 + 周期），
  **算得出达成率的目标才进排名**（rank_position = 0 ⇒ 未参与排名）。
- **端点**（5 个，挂 `/reporting/target-attainment`）：
  `GET /board`（达成追踪看板：达成率表 + 预测 + 未达标高亮 + 排名 + 预警，真分页真计数）·
  `GET /ranking`（排名）· `GET /snapshots`（已落快照清单）·
  `GET /capabilities`（能力声明）· `POST /evaluate`（评估并落快照 —— 写的是**派生读模型**，
  同一目标同一天重复评估是**覆盖**不是堆积）。
  权限 `reporting.target-attainment.read` / `.write`；租户**不**来自任何请求头。
- **页面**：`/analytics/dashboard/attainment` —— 真实工作区（筛选 + 概览计数 + 预警清单 +
  分组成就率表（未达标行高亮）+ 分解计划逐项达成 + 排名清单 + 已落快照 + 能力声明），
  **加载态 / 错误态 / 空态三态齐备**，「算不出来」与「没有目标」分开说，
  **前端不做业务算术**（达成率 / 预测达成率 / 时间进度一律来自服务端 BigDecimal）。
- **测试**：`TargetAttainmentIntegrationTest`（业务口径：当前值 / 达成率 / 预测达成 /
  周期未开始与「从来没有事实」都算不出来而不是 0 / 有过事实但窗口内没有 ⇒ 当前值是真的 0 /
  部门与人员维度如实报不可得 / 产品维度投影跑过则算得出、没跑过则不可得 / 毛利额无等价口径 /
  排名只在同维度同口径同币种同周期内且算不出来的不进排名 / 预警分「达不到」与「算不出」两类 /
  概览把「算不出来」单独计数 / 分解项逐项达成 / 评估落快照且**断言写回的时间戳** /
  快照真分页真计数 / 能力声明如实列出拿不到的部分）·
  `TargetAttainmentTenancyIntegrationTest`（多租户三条断言：两租户互不可见 /
  伪造 tenant header 无效 / 连接池归还后不串租户）·
  前端 `target-attainment-model.test.ts` + `target-attainment-allowlist.test.ts`。
- **🔴 时钟域**：评估日与评估时刻**同源于 PG 的 `now()`**，不是 JVM 墙钟
  （本仓已有两次同类事故：identity 审批待办超时、warehouse 货权档漂移判定）；
  评估时刻落库用 `?::timestamptz` 绑定 OffsetDateTime。

**关键设计决策**：
- **实际值来源按归属维度分三种，结果不一样**：店铺维度 ⇒ `reporting_store_metric_fact`（owner 级事实真的存在）；
  产品维度 ⇒ `reporting_product_metric_fact` + 三条口径等价映射
  （`SALES_AMOUNT`→`PRODUCT_SALES_AMOUNT` 等；**毛利额/毛利率在商品维度没有等价口径 ⇒ 如实报「不可得」**）；
  部门/人员维度 ⇒ 上游只到公司/法人/店铺维度 ⇒ **如实报「不可得」**
  （**绝不拿租户级数字冒充某个部门的实际值**）。
- **未达标高亮只认 AT_RISK 与 BEHIND** ——「算不出来」的三类**不是「没达标」，是「还没法判定」**，
  混进未达标名单等于告诉管理者「你落后了」。
- **判定规则全部写进能力声明**（页面上照着显示，用户看得懂「为什么这条是 BEHIND」）。
- **本模块只算达成**，不算绩效、不改人事；下游 17 绩效核算读 `reporting_target_attainment`；
  下游 1 工作台预警读 `board` 的 `alerts` —— 本模块**不订阅、不推送**。

**本轮状态**：**续派**。上一轮 6 个 commit 已在分支上、工作区干净、台账已改「已交付」。
本轮任务书的核心是：**核实完整性 → 跑 L0 + L1 → 跑绿 → `land`**。
**注意**：`apps/web/src/lib/api-proxy.ts` 已改 ⇒ 必须跑**全量 allowlist 回归**
（`pnpm vitest run apps/web/src/app/api`），并用 **TypeScript 编译器真实求值数组**确认
`services[]` 元素数 = 249（只 +1）—— **不要用文本计数猜**。

## 模块 13.2.2.1 店铺与站点（V391）

**这个功能做什么**：把销量与销售额按**店铺 / 站点 / 平台**三层拆开，
并算出**增长贡献度**（这个店/这个品带来了多少增量）、区分新店老店、给出渠道占比。
增长贡献度比绝对值有用 —— 它告诉老板「增量是谁带来的」。

**技术落地**：

- **迁移**：`V391__store_site_breakdown.sql` —— 4 张表
  （`reporting_store_site_metric_definition` / `reporting_store_site_profile` /
  `reporting_store_site_metric_fact` / `reporting_store_site_drilldown_fact`），
  `tenant_id NOT NULL` + ENABLE **且** FORCE ROW LEVEL SECURITY + 隔离策略 + GRANT；
  **零 ADD COLUMN、零 SET NOT NULL、零 CHECK 约束新增**（静态预检三条全 0）。
- **端点**：后端 `ReportingStoreSiteController` / `ReportingStoreSiteService` /
  `ReportingStoreSiteRefreshService` / `ReportingStoreSiteCatalog` / `ReportingStoreSitePort`，
  前端路由 `apps/web/src/app/api/reporting/[...path]/` + `store-site` 页面。
  ⚠️ 端点的具体路径与数量以 worker 落地结果为准（本轮仍在续做中）。
- **页面**：`/analytics/sales/store-site` —— `store-site-model.ts` + `store-site-workspace.tsx`。
- **测试**：`StoreSiteBreakdownIntegrationTest` · `StoreSiteBreakdownTenancyIntegrationTest` ·
  前端 `store-site-model.test.ts` + `store-site-allowlist.test.ts`。

**关键设计决策**（上一轮已定，本轮照此收敛）：

1. **三个维度是同一棵树的三层**（平台 → 站点 → 店铺），**不是三套独立事实**。
   事实只落**店铺层**，站点 / 平台维度在**查询期**按 `reporting_store_site_profile` 重分组。
   理由：把同一批订单投影三遍 ⇒ 一次刷新写三倍行，且三份行之间可能不一致 ——
   **那是自找的「一个数三个真相」**。站点维度键必须是 `平台码:站点码`（channel 的 `marketplace` 只在平台内唯一）。
2. **不消费 13.2.1.1 的 `reporting_sales_trend_fact` 作为金额来源**，改为自投影完整事实集
   （金额 / 销量 / 订单数），口径码一律 `STORE_SITE_` 前缀 ——
   硬接会让占比/贡献度的口径分裂成两套。
3. **站点归属有一条硬边界：订单不带站点**。站点归属只来自 channel 的 `shop_marketplace`：
   店铺启用 1 个站点 = UNIQUE、≥2 个 = AMBIGUOUS、0 个 = NONE。
   归不了的部分**单列**成「无法唯一归属到站点」，**不摊给任何站点**、**不计入站点维度的占比分母**
   （摊给谁是造假）。归属状态用 `attribution_status` **钉进数据层**。
4. **新店 / 老店按成交事实判定**：当前窗口有成交、**基期没有任何成交事实** ⇒ 新店。
   `site_enabled_at` 只作**旁证** ——「站点开了但一直没出单」在经营上仍然是新店。
5. **增长贡献度 = 该维度的增量 ÷ 全部维度的增量之和**。总增量为**负**时贡献度**照样是负数**。

**本轮状态**：**续派**。实现全部在位但**一个 commit 都没有**（上一轮 worker 会话被网络中断）。
⚠️ **台账第 421 行仍是「未开发」** —— 本轮必须改成「已交付」并写上证据。
⚠️ 同样改了 `apps/web/src/lib/api-proxy.ts` ⇒ 必须跑全量 allowlist 回归，
并确认另一条车道的 `reporting/target-attainment` 分支**仍在**（取错一侧会互相删掉对方的路由）。

## 模块 fix-trade-clock-domain —— trade 跨钟域取时修复（无迁移）

**这个功能做什么**：把「一次合法的状态推进被数据库挡下」和「不该过期的纠纷被判成没过期」
这两个**只在高负载机器上才出现**的缺陷修掉，并登记全服务同族隐患。

**技术落地**：

- **迁移**：**无**（修的是既有 Java 代码与既有测试，**不新建、不修改任何迁移文件**）。
- **端点**：无（不改任何端点）。
- **页面**：无。
- **测试**：改 `OrderSplitIntegrationTest` 与 `PlatformDisputeTenancyIntegrationTest`
  两个既有类 + 补「同域」回归断言；
  L1 命令：`-Dtest='PlatformDisputeTenancyIntegrationTest,OrderSplitIntegrationTest'`，
  **必须连跑 3 次**（这族缺陷就是负载相关的，1 次绿不算数）。

**关键设计决策**：

- **`order_split` 的 INSERT 改成让 DB 自己取时刻**（`created_at` / `updated_at` 都用 `now()`），
  同一方法里紧邻的 `order_split_unit` / `order_split_unit_line` 一并改 ——
  那两张表**也带同一条 CHECK**（V151 第 182 / 217 行），不改只是把同一个雷从一张表挪到另一张表。
- **不改 `advance_order_split_status` 函数、不放宽也不删除那条 CHECK 约束** ——
  `updated_at=now()` **本来就是对的**，错的是喂给它的跨钟域值；
  约束正是在保护「记账时刻不能早于建行时刻」。
- **纠纷那一侧只改测试夹具**（生产侧是对的）：把 `OffsetDateTime.now(UTC).minusMinutes(1)`
  换成 DB 钟域的「现在减 1 分钟」，照抄 `StockOwnershipIntegrationTest` 的 `dbNow()` 写法。
  ⚠️ **不许把断言改弱**（不许把「判成 RESOLVED_LOST」改成「判成 OPEN 也行」）——
  那是多租户三断言的用例。
- **全服务同族隐患登记到 `docs/backlog/`，不在这一轮全改**：
  `CHECK(updated_at>=created_at)` 在 trade 服务里**横跨 31 张表**
  （V92 / V116 / V119 / V144 / V149 / V150 / V151 / V152 / V153 / V154 / V210 共 11 个迁移文件），
  而 `OrderSplitService` 里有 **22 处** `OffsetDateTime.now()` 绑定。
  **一条修复车道不能收这个口** —— 那会把修复车道变成大重构。
- **doctrine 依据**（本仓第三次修同一族，前两次：`32a16a6a` identity、`8375e648` warehouse）：
  「**在服务端判定所用的那个钟域里比**」；「两个钟域各管一段时，只换一端反而制造新的跨钟域依赖」。

## 落地状态补记（14:17，写完上文之后）

- **`13.1.2.2` 达成追踪 —— 已落地**（`land` 成功，车道状态 `ready`，HEAD `b569286`）。
  worker 报告：**L0 编译绿**；**L1 后端 19 例绿**（`TargetAttainmentIntegrationTest` 15 +
  `TargetAttainmentTenancyIntegrationTest` 4）；**前端本模块 24 例绿**；
  **白名单全量回归 261 文件 / 1473 例绿**；**`services[]` 元素数 248 → 249（只 +1，逐条集合差：增 1 删 0）**；
  契约 path 45→50 / schema 100→113，**被删 0 条**；结束后 `postgres:16-alpine` 残留 **0**（ryuk 已回收）。
  ⚠️ **L1 首跑是红的**，归因精确：`产品维度_毛利额没有等价口径_如实报不可得` 报
  「指标 GROSS_PROFIT 的分母指标 COGS_AMOUNT 尚未定义」——
  这是**夹具缺口不是实现缺口**（V376 触发器 `guard_reporting_metric_definition_ref` 正确挡住了
  凭空捏出的派生口径），修复只动测试夹具（新增 `seedCogsCaliber()`），**生产代码一行未改**。
  本轮新增 2 个 commit：`a4d02ccd`（补齐派生型口径的分母夹具）+ `b5692868`（会话日志补记）。
  ⇒ **该车道等待下一轮 tick 的 `merge-ready` 并入 + L2 验收后随修复车道一起 push。**
- **`13.2.2.1` 与 `fix-trade-clock-domain` 仍在途**（心跳正常）。
- ⚠️ **本轮没有并入任何车道**：14:18 起调度闸由 **main→集成头同步任务（第 87 轮）**持有
  （它等了我这一轮 3 小时的闸，提交 `eb7a585e`）⇒ 两个任务共用同一道闸互斥的机制按设计生效，
  但也意味着**并入动作要等下一轮 tick** —— 协议明令「两个 tick 同时 merge 同一个集成头会留 MERGE_HEAD 残骸」。

## 为什么不 push（三件套的第 ③ 件，**本轮明确缺失**）

1. **「L2 没绿绝对不 push」是最高优先级禁令**。本轮 L2 **红了**（backend=1）。
2. 集成头上累积 **30 个未推送 commit**（`origin/integration/module-dev` = `e6f443ae`，本地 HEAD = `18f812e5`），
   其中包含 batch-105 四车道的**代码合并**与三条修复车道。
   **这些代码本轮的 L2 已逐格验过**（identity / catalog / channel / warehouse / crm / finance /
   reporting / platform 八格全绿，前端四件全绿），**但批次闸整体是红的** ⇒ 按禁令不推。
3. ⇒ **成果与诊断留在集成头本地**，随修复车道落地 + 下轮 L2 转绿后一起 push。
   本轮三件套状态：**① 说明 ✅ / ② commit ✅ / ③ push ❌（L2 未绿，协议禁止）**。

## 本轮过程中的两个运维教训

1. **keepalive 计时器会被系统休眠冻结**：v1 续期循环（`sleep 600`）在 11:42 与 12:40 之间
   **只跑了一轮**（漂移 57 分钟），闸的 `ageSeconds` 一度到 **4007 / TTL 4200** —— **差 193 秒就过期**。
   已改为**双 keepalive**（600 秒 + 120 秒各一条）。
   ⚠️ **macOS 没有 `setsid`**（util-linux 才有）⇒ 用 `setsid nohup … &` 起后台循环会**静默失败**
   （`setsid: command not found`），必须用 `nohup … &` + 显式 `cd`。
2. **`gate status` 必须在正确的目录下跑**：一次误报「`gate=free`」——
   根因是 bash 里 `cd a && b && c &` 会把**整条链**放进后台，
   链外的 `node lane.mjs gate status` 就在**主工作区**跑了，读的是主工作区那个**不存在的**闸目录。
   ⇒ **闸从来没丢**。凡是要 `cd` 进集成头再跑的命令，**每一段都要自己 `cd`**。

## 后续待办

1. **下一轮 tick（本批 3 条车道落地后）**：`merge-ready` → `sync` → **L2 第 2 跑** → 绿则 **push**。
   L2 第 2 跑**必须**验证到 `trade`（本轮红的那一格）与 `reporting`（上轮假红的那一格）。
2. **修复车道落地后**，若 `order_split` 的 INSERT 改动让别的 trade 测试暴露新问题，
   按协议走「L2 红 → 二分 → 修复车道」，**不要**在集成头上直接改。
3. **`docs/backlog/` 的 trade 同族隐患登记**要在修复车道落地时核对是否存在。
4. **卷 13 子页面仍没有导航入口**（batch-105 遗留）—— 四个已交付的分析页互相不可达，
   backlog：`docs/backlog/2026-10-2026-10-26-analytics-section-no-navigation-entry.md`。
5. **13.2.2.2 的产品维度实际值尚未投影**（batch-105 遗留）⇒ 13.1.2.2 达成追踪在产品维度会报「不可得」。
6. **同步任务连续多轮「冲突未并入」**（第 83/84/85/86 轮各 10~11 个冲突）：
   根因是 PR #66 用了 squash merge，压掉了集成分支的祖先链 ⇒ 每次同步都在「同源快照」上重新长冲突。
   **改用 merge commit 应能自然消失**，仍待人工跟进。
7. **遗留 worktree `/Users/guan/Projects/starcon-worktrees/l2-verify`**（detached HEAD `d35c11da`，
   内容早已并入集成头）—— 上一轮修 1.1.1.2 时留下的，可清理。
8. **`ApprovalInboxIntegrationTest` 仍是同一族潜伏写法**（第 107 轮取证时发现，至今未处理）：
   它的 `node_entered_at` 夹具仍用 **JVM 墙钟**播种，与生产侧 `approval_dwell_seconds(now(), …)` 的
   **DB 钟域**跨域。目前它用的是整天偏移，离取整边界很远 ⇒ 不会翻车，但属同类潜伏缺陷。
9. **本轮的 trade 两例说明「跨钟域」不是孤例而是分布式的**：
   `CHECK(updated_at>=created_at)` + JVM 墙钟绑定这个组合在 trade 有 31 张表 / 22 处，
   **修复车道只收了 order_split 一处**。其余 30 张表的收口方向已写进 backlog，
   建议**按「记账时刻归 DB，业务时刻另设独立列」的原则分批收**，不要一条车道全改。
