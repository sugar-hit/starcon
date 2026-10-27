# 2026-10-27 21:00 · pipeline round 114 · 调度闸被 r113 持锁 + PG 闸命中 + L2 在飞 ⇒ 零派发

> 本轮**没有派发新车道**，原因是三重的、可取证的：
> ① **调度闸被 round 113 的 keepalive 守护进程持着**（`gate=held` / `stale=false` / 2 分钟一次续期）；
> ② **PG 闸命中**（带 Testcontainers 标签的 `postgres:16-alpine` **7 个 ≥ 上限 6**）；
> ③ **集成头上正在跑 round 113 的 L2 批次闸**（`l2-r113.sh`，PID 72285，19:32:09 起）。
>
> 但本轮**不是空转**：round 113 的 agent 会话在 20:47:55 崩掉（`net::ERR_CONNECTION_CLOSED`），
> 它的 L2 / watcher / keepalive 三件套作为**脱离会话的进程**继续活着 ——
> **L2 跑完没人写说明、没人 commit、没人 push**。
> 本轮接管的就是这段尾巴：**① 写说明（本文件）② commit ③ push**（③ 以 L2 结果为准）。

## 决策摘要

| 项       | 值                                                                                                                |
| -------- | ----------------------------------------------------------------------------------------------------------------- |
| 轮次     | **round 114**（2026-10-27 21:00 tick）                                                                            |
| 档位     | **reduced（降档）** —— `TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过（当前 2026-10-27 21:00）                   |
| 车道数   | `LANES` 4 → **2**（减半）；节流闸 `THROTTLE_MIN` 50 → **100** 分钟                                               |
| 台账     | **417 / 465 已交付（89.7%）**，进行中 0，未开发 **48**；465 行、**0 重号**                                      |
| 节流闸   | 距上批 **293** 分钟（上批 16:07:14）≥ 阈值 **100** ⇒ **放行**                                                    |
| 调度闸   | `gate=held` / `ageSeconds=111` / **`stale=false`** ⇒ **撞闸，不取闸、不派发**                                    |
| 容量闸   | **PG 闸命中**（7 ≥ 6）⇒ STOP；温度闸 / 配额闸 / load 闸全过                                                    |
| 派发     | **0 条**（闸 + PG 闸 + L2 在飞，三重拦下）                                                                        |
| 回收     | `merge-ready` **未执行**（闸被占，协议禁止）；223 条车道状态**全部 `merged`**，无待并入                              |
| 静态预检 | V390 / V391 / V392 三条新迁移：① 无 DEFAULT 的 ADD COLUMN **0** · ② SET NOT NULL **0** · ③ ALTER-ADD-CONSTRAINT **0** |
| L2       | **round 113 的 L2 在飞**（`l2-r113.sh`，STEP 1/6 backend `mvnw -fae verify`），本轮**未另起 L2**                   |
| 说明文件 | 本文件                                                                                                           |
| commit   | ✅ 本轮做（说明文件 + 台账同一个 commit）                                                                         |
| push     | ⏳ **取决于在飞的 L2 结果** —— L2 没绿绝对不 push；绿则本轮补 push（见文末「收尾三件套」）                        |

## 容量闸判定（四条只读命令全跑 ⇒ 一条命中 ⇒ STOP）

| 闸         | 阈值      | 实测                                                                        | 判定              |
| ---------- | --------- | --------------------------------------------------------------------------- | ----------------- |
| 温度闸     | —         | `pmset -g therm`：No thermal warning / performance warning / CPU power status has been recorded | ✅ 无降频         |
| 配额闸     | 6         | running **0**（223 条车道全 `merged`，`lanesRunning=0`）                    | ✅ 0/6            |
| **PG 闸**  | **6**     | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` **7 个**（21:02 实测） | 🔴 **命中 ≥6 ⇒ STOP** |
| load 闸    | 12        | **4.37**（1min）/ 4.20（5min）/ 4.19（15min）—— 三点**逐点回落**，非爬升形态；核数 10 | ✅             |

- **孤儿容器 0**：同一条 `docker ps` 里无标签的 `postgres:16-alpine` 数量 = **0**（不需清理）。
- 7 个带标签容器全部属于**在飞的 L2**（Testcontainers 为 `mvnw verify` 起的 PG），**不是**孤儿 ⇒ 不清理、不 force。
- 闸优先级（温度 > 配额 > PG > load）本轮命中 **PG 闸**一条。

## 为什么是零派发（三条互相独立的理由，任一条都足以拦下）

### ① 调度闸被**活着的** round 113 持着 —— 不是僵尸闸

```
gate status → { "gate": "held", "ageSeconds": 111, "ttlSeconds": 4200, "stale": false }
.dev-agent/dispatch.gate/owner → 20138 / 2026-10-27T08:00:52.914Z（= 16:00:52 CST，round 113 起闸）
keepalive-66-1793106054       → 21:00:54（= 我 21:01 查闸**之后**才写的）
```

- keepalive 从 16:03 到 21:00 **每 2 分钟一个**（keepalive-1 … keepalive-66），目录 mtime 一直新鲜 ⇒ `stale` 永远为 false。
- 协议明确：`stale=false` **不等于**持闸者在偷懒 —— 它可能正在跑 L2。**实测正是如此**（见下节）。
- ⇒ 本轮**不取闸、不 force、不清闸**。正确动作 = 零派发 + 只读检查 + 汇报。

### ② PG 闸命中（7 ≥ 6）

L2 正在起 Testcontainers PG，7 个带标签容器把额度吃满。此时再派 2 条车道，
每条车道 L1 都要起自己的 PG ⇒ 与 L2 抢 Testcontainers ⇒ **成片假红**
（协议第 5 节禁令：L2 不得与在途车道的 L1 并发跑）。

### ③ L2 批次闸正在集成头上跑

```
PID 72285  bash .tmp/l2-r113.sh          ← STEP 1/6 backend mvnw -fae verify
PID 73032  bash .tmp/wait-l2.sh          ← 守 `L2 RESULT:` 行，4 小时上限（19:32 → 23:32）
PID 21165  bash -c GATE=...              ← keepalive 守护循环，每 2 分钟 touch 闸目录
```

本轮**没有**另起 L2（并发两个全量 = 两个 Testcontainers 池互相抢资源 + 假红）。

## round 113 的 L2 在飞 —— 取证

`.tmp/l2-r113.sh` 的注释写明了本轮要验什么：

```
# Round 113 (2026-10-27 16:00 tick):
#   validates the integration head after this round's merges:
#     - 13.1.2.2 achievement tracking   (merged this round, V390)
#     - 13.2.2.1 store & site breakdown  (merged this round, V391)
#     - the batch-105 backlog still unpushed (48 commits vs origin/integration/module-dev)
#     - the fix/trade-clock-domain lane, merged just before this run
```

**第 1 跑（`.tmp/l2-r113.log`）** 的真实结果：

```
[INFO] Tests run: 1800, Failures: 0, Errors: 0, Skipped: 0     ← 前 4 个 Maven 模块全绿
... java.net.ConnectException: Connection refused                ← 随后的 trade 阶段 PG 连接被拒
```

⇒ 这是协议里点名的**假红家族**（PG 被别的东西挤掉 ⇒ 连接被拒），
所以 round 113 起了**第 2 跑 `.tmp/l2-r113b.log`**（当前正在跑，21:02 时进行到 trade 迁移回放）。
`wait-l2.sh` 在等 `L2 RESULT: GREEN / RED` 那一行。

⚠️ **round 113 的 agent 会话已于 20:47:55 崩掉**（`net::ERR_CONNECTION_CLOSED`），
所以 **L2 跑完之后没有任何 agent 会去写说明 / commit / push** —— 这正是本轮要接管的部分。

## 待推送清单（58 个 commit，`origin/integration/module-dev` → 本地 HEAD `2410aaa0`）

| 批次 / 来源                     | 模块 / 内容                                                                  | 迁移    |
| ------------------------------- | --------------------------------------------------------------------------- | ------- |
| batch-105（4 条车道 + 4 个修复）| 1.5.2.1 服务与任务状态 · 13.1.2.1 目标设定 · 13.2.1.1 销量趋势 · 13.2.2.2 SKU/SPU/Listing + api-proxy 字面量修复 + 7.7.1.1 货权档生效起点跨钟域 + 1.1.1.3 审批待办跨钟取整 + warehouse 货权漂移夹具 | 各车道自有 |
| batch-106 / round 113（3 条）   | 13.1.2.2 达成追踪（V390）· 13.2.2.1 店铺与站点（V391）· 13.2.1.2 分时与分周模式（V392） | V390 / V391 / V392 |
| 修复车道                         | fix-trade-clock-domain —— order_split 记账时刻归 DB + 纠纷夹具改用 DB 钟域   | 无       |
| main→集成头同步第 83–88 轮      | 冲突 11 个未并入（与第 83/84 轮逐字节一致），只留取证文档                    | 无       |

逐模块细节见下面四节；batch-105 的四个模块已在 `2026-10-26-batch-105-four-lane-merge-and-l2.md` 有完整说明，本轮不重复。

---

## 模块 13.2.1.2 分时与分周模式

**这个功能做什么**：按**店铺本地时区**看一周里哪天、哪个时段出单最多，把热力图、时段判定（高峰 / 平峰 / 低谷）与高峰排序直接摆出来，供排班、备货、客服排班用。

**技术落地**：

- 迁移：`V392__time_of_day_week_pattern.sql` — **4 张新表，纯 CREATE TABLE**；`ADD COLUMN` 0 处、`SET NOT NULL` 0 处、`ALTER TABLE … ADD CONSTRAINT` 0 处；RLS `ENABLE` + `FORCE ROW LEVEL SECURITY` + `tenant_id` 隔离策略（`current_setting('app.current_tenant')`）+ `GRANT`
- 端点：`/reporting/time-pattern` 共 **10 个**
  `GET /`（热力图 + 小时/星期分布 + 时段判定 + 高峰排序）· `GET /capabilities` · `GET /metrics` · `GET /shops` ·
  `GET /slots`（时段配置清单）· `POST /slots`（改单条）· `PUT /slots`（整体替换）· `PUT /slots/{id}` ·
  `DELETE /slots/{id}` · `POST /refresh`（刷新投影）
- 页面：`/analytics/sales/time-pattern` — 筛选维度：店铺 / 星期 / 时段；操作：时段配置改单条与整体替换、手动刷新投影；错误态 / 空态 / 加载态齐备
- 测试：`TimePatternIntegrationTest` **19 例** + `TimePatternTenancyIntegrationTest` **5 例**

**关键设计决策**：

- **两个口径都做，但默认按店铺本地时间**，且把 UTC / 本地两种口径的说明**原样显示在页面上** —— 排班问题用 UTC 槽位会系统性错位
- **时区取自「站点」的 `default_timezone`**，不是店铺、不是硬编码表
- **高峰与低谷是两个互不替代的刻度**，页面上分开显示，不合并成一个数
- **一份事实、两种切法**：同一批订单既可按本地时间切，也可按 UTC 切，不复制事实表
- **落库时刻一律用 PG 的 `now()`**，`timestamptz` 用 `?::timestamptz` 绑 `OffsetDateTime` —— 避免 `Timestamp.valueOf(LocalDateTime)` 静默偏 −28800s；写时间的测试**断言写回的值**
- 本车道另外**修掉 3 个真实实现缺陷**（不是测试期望算错）：迁移触发器的 DELETE 分支拿「被删行」与「新行」比较 ⇒ 整体替换时段配置永远被拒；另修 3 处测试期望算错（夹具漏数、星期算错、阈值该传 0..1 比例）

## 模块 13.2.2.1 店铺与站点

**这个功能做什么**：把销量按**店铺 / 站点 / 平台**三维拆开，回答「哪个店、哪个站点、哪个平台贡献了多少、增长了多少、是新店还是老店」。

**技术落地**：

- 迁移：`V391__store_site_breakdown.sql` — **4 张新表，纯 CREATE TABLE**（`reporting_store_site_metric_definition` / `_profile` / `_metric_fact` / `_drilldown_fact`）；`ADD COLUMN` 0 · `SET NOT NULL` 0 · `ALTER … ADD CONSTRAINT` 0；RLS `ENABLE` + `FORCE` + 租户策略 + `GRANT`
- 端点：`/reporting/store-site` 共 **5 个** — `GET /`（主拆解）· `GET /capabilities` · `GET /metrics` · `GET /drilldown`（下钻）· `POST /refresh`
- 页面：`/analytics/sales/store-site` — 筛选维度：店铺 / 站点 / 平台 / 时间窗；操作：下钻、刷新投影；不可比时显示「—」或「不可比」+ 原因（`null` + `NO_DATA` / `NOT_COMPARABLE`）
- 测试：`StoreSiteBreakdownIntegrationTest` **11 例** + `StoreSiteBreakdownTenancyIntegrationTest` **4 例**（L1 实跑 `Tests run: 15, Failures: 0, Errors: 0`）

**关键设计决策**：

- **白名单必须双向断言**：只断言 `proxy()` 直调不够 —— 真实页面还要过 **Next 路由层**，否则 Next 层没出口、页面永远停在错误态，而 typecheck / lint / vitest / Playwright **全部全绿**。本轮为此在白名单测试里加了一条「Next 路由层有出口」的断言把洞钉住
- **销量逐单取（N+1）**：上游没有批量明细端点，只能逐单取；代价是慢，换来的是口径与上游一致
- **L1 首跑 15 例里 9 例红**的根因是**夹具的字符串形状伪装成「上游不可达」**：端口层先报「上游不可达」，端点级错误信息永远看不到 handler 里的真实异常 —— 修法是让夹具形状与真实端口一致，而不是改断言
- **端点测试必须钉死时间窗**：不传 `from / to` 时端点默认「最近 30 天」，那个窗口会把**基期**切掉 ⇒ 新老店判定读到错误基期
- 契约：`reporting.openapi.json` 重新生成（`Tests run: 1, Failures: 0`，**不连库、未新增 PG 容器**）

## 模块 13.1.2.2 达成追踪

**这个功能做什么**：给目标（部门 / 成员 / 店铺 / 商品维度）算出当前值、达成率、**预测达成率**，把未达标的挑出来高亮并排名，让「离目标还差多少、能不能赶上」一眼可见。

**技术落地**：

- 迁移：`V390__target_attainment.sql` — **1 张新表 `reporting_target_attainment`**，纯 CREATE TABLE；`ADD COLUMN` 0 · `SET NOT NULL` 0 · `ALTER … ADD CONSTRAINT` 0；RLS `ENABLE` + `FORCE` + 租户策略 + `GRANT`
- 端点：`/reporting/target-attainment` 共 **5 个** — `GET /board`（看板）· `GET /ranking`（排名）· `GET /snapshots`（历史快照）· `GET /capabilities` · `POST /evaluate`（评估落库）
- 页面：`/analytics/dashboard/attainment` — 筛选维度：目标范围（部门 / 成员 / 店铺 / 商品）+ 周期；操作：手动评估、查看排名与快照
- 测试：`TargetAttainmentIntegrationTest` **15 例** + `TargetAttainmentTenancyIntegrationTest` **4 例**（**19 例全绿**）

**关键设计决策**：

- **写的是派生读模型，不是业务事实** —— reporting 的纪律不破：一个目标 × 一个评估日一行，`POST /evaluate` 写、`GET /snapshots` 读
- **达成率的分子分母必须有等价口径**：缺分母口径时如实报「不可得」而不是给 0 或近似值；为此在测试里补了 `seedCogsCaliber()` 夹具先播种分母口径
- **跨钟域取时统一走 DB 钟**（夹具用 DB 时钟造数据）—— 「一天在预测达成率上就是几个百分点」，机器本地时钟与 DB 钟不一致足以让预测漂移
- **白名单不放行裸路径**：`reporting/target-attainment` 全部在子路径下 ⇒ 白名单按后端真实形状逐条对齐（多写一条裸路径 = 多放行一条别人没交付的路由，症状是页面 404 而所有测试全绿）
- **格式错误翻 400 而不是 500**：评估日格式非法属客户端错误，不该是服务端 500；前端 POST 补空 JSON 体
- **归属对象标识归一成 uuid 小写再查事实** —— 否则同一条记录按大小写不同的标识查不到

## 模块 fix-trade-clock-domain（修复车道，非新蓝图模块）

**这个功能做什么**：修掉 L2 批次闸定位到的 **trade 跨钟域取时缺陷** —— 不是新功能，是回归修复，**无迁移 / 无端点 / 无页面 / 无契约改动**。

**技术落地**：

- 迁移：**无** · 端点：**无** · 页面：**无** · 契约：**无**
- 后端：1 个 Java 服务类 + 2 个既有测试类；另登记 backlog 同族隐患（32 张表 / 11 个迁移 / trade 231 处 + 全后端 593 处绑定点）
- 测试：L1 连跑 **3 次**全绿；多租户三断言齐跑

**关键设计决策**：

- **改取时刻的方式，不动约束**：`order_split` 一族的记账时刻改为**由 DB 取**（`now()`），消除 `CHECK(updated_at >= created_at)` 的跨钟域比较 —— 约束本身是对的，错的是拿两个不同时钟域的值比
- **纠纷夹具改用 DB 钟域，不动生产代码**：夹具的「判超期」与「写截止时刻」必须同钟域，否则恒真
- 取证方式是**同钟域恒真的性质断言**，而不是去比某个具体差值 —— 差值断言在跨时区 CI 上必假红

---

## 收尾三件套（本轮逐件点名）

| 件 | 状态 | 说明                                                                        |
| -- | ---- | --------------------------------------------------------------------------- |
| ① 写说明 | ✅ 完成 | 本文件：round 114 决策摘要 + 容量闸取证 + 零派发三理由 + L2 在飞取证 + 4 个模块逐模块说明 |
| ② commit  | ✅ 完成 | 说明文件与台账**同一个 commit**（`git add docs/sessions/2026-10/ docs/product/capability-blueprint/module-progress.md`） |
| ③ push   | ⏳ 见文末 | **只在在飞的 L2 判定为绿时执行**；L2 没绿绝对不 push，成果与诊断留集成头本地随下一批一起 push |

## 后续待办

1. **等在飞的 L2 出结果**：`L2 RESULT: GREEN / RED`（`wait-l2.sh` 守着，4 小时上限 23:32）
   - **绿** → 本轮补 push（`git push origin integration/module-dev`，58 个 commit 一次性放出）+ 汇报
   - **红** → **不 push**，把红点写进本文件并做二分定位；说明与 commit 照做，成果留本地
2. **L2 结束后**才允许派发下一批车道（2 条，reduced 档位），并遵守「L2 不与车道 L1 并发」
3. **48 个未开发模块**全部在 D7（45 个）与 D6（3 个），主责服务 reporting 已就位 ⇒ 下一批可排
4. **卷 13 导航入口缺失**（batch-105 遗留，4 个已交付的分析页互相不可达）—— 已登记 backlog，本轮未做
5. **main→集成头同步第 83–88 轮冲突未并入**（11 个文件，逐轮一致）—— 那是同步任务的事，本流水线不 merge main

## 协议不一致（按 PIPELINE.md 为准，但点名）

- `PIPELINE.md` 禁令写「**不要把 PG 闸从 4 调高**」，而本机 `.dev-agent/PIPELINE.local.md` 的 `PG_CONTAINER_LIMIT=6`；
  本轮按**本地参数文件**（机器相关参数的唯一来源）判定，PG 闸 = 6
- `PIPELINE.md` 汇报章仍留着「诚实区间是 **22–28 天**」这种写死数字，与同文件「不要把进度数字写死在汇报里」冲突 —— 本轮不引用该数字，吞吐按现算口径给
