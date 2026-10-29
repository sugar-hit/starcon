# 14.2.1.2 分时策略（阶段 D7）

> 车道：`feat/14-2-1-2` · 工作树：`integration/.worktrees/14.2.1.2`
> 基线：集成头 `f8b9383a`（merge-catalog-timestamp-assertion-clock）

## 决策摘要

**模块**：14.2.1.2 分时策略（卷 14 广告与增长 → L3 锚点 14.2.1，本车道是该 L3 的第一个模块，命名与分层照抄同服务 `backend/services/channel` 相邻模块）。

蓝图职责：「按小时设置不同竞价与预算（高峰提价提预算、低谷降价停投），支持按历史出单时段自动推荐分时曲线。输入：分时规则；13.2.1.2 时段分布数据。输出：分时策略配置 + 定时调整任务。关系：上游 = 13.2.1.2；下游 = 14.2.2.2 自动规则。**分时策略需按店铺时区配置**，跨时区店铺用错时区会完全失效。」

落地四件套：

- **迁移** `backend/services/channel/src/main/resources/db/migration/V393__ad_daypart_strategy.sql`（7 表 + RLS + 4 个触发器）
- **端点** `backend/services/channel/.../AdDaypartController.java` + `AdDaypartService.java` + `AdDaypartModels.java` + `AdDaypartSourcePort.java`（`/channel/ad-dayparts/**` 共 17 个端点）
- **页面** `apps/web/src/app/ads/dayparts/page.tsx` + `apps/web/src/components/ads/ad-daypart-model.ts` + `ad-dayparts.tsx`
- **测试** `AdDaypartIntegrationTest`（27 例）+ `AdDaypartTenancyIntegrationTest`（3 例）+ 前端 27 例

## 关键 trade-off

### 1. 「按小时」= 店铺本地时区的小时（模块命门）

店铺**没有**自己的时区字段；时区在 `marketplace.default_timezone`（站点参数 2.1.1.3），店铺与站点的绑定在 `shop_marketplace`（V1）。⇒ 店铺时区 = 该店铺**启用**站点（`status='ACTIVE'`）的 `default_timezone`：

| 场景 | 结论 |
| --- | --- |
| 启用 1 个站点 | `RESOLVED` |
| 启用多站点但时区相同 | `RESOLVED`（不因为站点多就放弃） |
| 启用多站点且时区不同 | `UNRESOLVED` |
| 没启用站点 | `UNRESOLVED` |

定不了的店铺**不摊给任何默认时区**（摊一个就是「用错时区」），在页面上**单列**成「未能按店铺本地时区归属」并写清原因，且其策略**不许激活**（DB CHECK 钉死，绕过服务层直接写 SQL 也激活不了）。两侧（channel 解析 vs 上游 13.2.1.2 解析）不一致 ⇒ `CONFLICT`，**不产出推荐曲线**。

**取舍**：用 IANA 标识而非固定 UTC 偏移。固定偏移在冬夏整整差一小时，整条曲线会**整体错位一小时**；代价是必须依赖 PostgreSQL 的 tzdata（触发器钉死「时区必须在 `pg_timezone_names` 里真实存在」）。

### 2. 目标值 = 基准 × 系数（防复利）

拿**当前值**再乘一次系数 ⇒ 17:00 乘 1.5、18:00 再乘 1.5 就是 2.25 倍，一天把预算烧穿。⇒ 引 `ad_daypart_baseline` 基准快照表，目标值相对**基准**算 ⇒ 同一小时重复跑结果相同（幂等），不复利。

### 3. 「停投」= 预算系数置 0，**不碰活动状态**

活动状态是**活动级**的，表达不了「只在 15:00 停投」；且把活动置 PAUSED 会**覆盖人工暂停**（人工暂停是人的决定，机器不该替他撤销）。⇒ 本模块只改预算与竞价两个数字，capabilities 里 `campaign_state_toggle` 维度 `available=false`。

### 4. 定时在外部调度，服务不装 `@Scheduled`

多副本下自带定时器要做主节点选举与分布式锁 —— 那是平台能力不是领域能力。⇒ 策略上带 `schedule_enabled` + `run_interval_minutes`，由 K8s CronJob 调 `POST /channel/ad-dayparts/tasks/run`，天然单次、可重试、可审计。与 V212 / V317 同一纪律。幂等键 `(tenant_id, strategy_id, local_hour, run_mode)` ⇒ 调度重试不会调两次钱。

### 5. 上游只经授权只读 HTTP API，读不到一律 503

上游 13.2.1.2（reporting，V392，已交付）**零跨库 SQL / 零 FK / 零影子表**。转发调用方自己的 bearer、**不传租户号**（租户仍由上游按已认证成员关系解析）。**逐店铺**取分布 —— 一次拉全部店铺再聚合会把不同时区的小时混在一起。读不到抛 503，**不返回空集合**冒充「上游没有数据」。

### 6. 改预算 / 改竞价走已交付的写路径

改预算走 14.1.1.1 的 `AdCampaignService.bulkOperation`，改竞价走 14.1.3.1 的 `AdKeywordService.bulkAction` ⇒ 护栏 / 幂等 / 操作审计全部复用，本模块**不另开绕过护栏的写路径**。

### 7. 与下游 14.2.2.2 的关系

本模块**不改它的表、不 import 它的类**，只提供读取形状 `GET /channel/ad-dayparts/caps`（某店铺当前生效的上限 + 适用策略清单），两边**取更严的那个**。本模块自己执行调整时用同一套上限列 ⇒ 「上限」在两侧是同一份数字。

## 本轮（重派）实际改动

上一轮 worker 的成果**全部保留**。本轮核验 + 补齐：

| 项 | 处置 |
| --- | --- |
| `V393__ad_daypart_strategy.sql` | 保留，未改名未改号 |
| `AdDaypartController/Models/Service/SourcePort.java` | 保留 |
| `AdDaypartIntegrationTest` / `AdDaypartTenancyIntegrationTest` | **补 `@AfterAll`** —— 原先只起 HTTP stub（identity / reporting）**不回收容器**，与仓库既有约定（`BackfillIntegrationTest` 等）不符；一个测试类漏一个容器，同一服务堆到上百个会把机器压进 swap ⇒ **断言假红**（负载型假红，历史上踩过）。现按「**先停外部服务、再停容器**」回收 |
| `packages/api-contracts/*` | 保留；本轮**重新导出 SpringDoc 快照 + 重跑 codegen** 逐字节比对，确认是 codegen 产物、非手改 |
| `apps/web/src/lib/api-proxy.ts` | 保留；核验为**纯增量 +1 条** `services[]` 元素（既有 251 条一字未改、不重排，`git diff --numstat` = **64 / 0**） |
| `ad-daypart-model.ts` / `ad-dayparts.tsx` / `dayparts/page.tsx` | 保留；核验错误态 / 加载态 / 空态**已齐全**（加载态 `loading && !page`、错误态 `error`、空态：店铺 / 策略清单 / 推荐批次 / 任务 / 任务明细 / 上限 六处） |
| 台账 14.2.1.2 行 | 状态改「已交付」+ 补齐证据格（`node tools/module-progress/sync.mjs` 重算总览：已交付 417→418、未开发 48→47、完成率 89.7%→89.9%） |

**未改动**：别的模块的迁移 / 端点 / 前端页面；台账里别的行（同步只重算了总览计数与 D7 / 卷 14 汇总行，`git diff` = 6 增 6 删）。

## 验证

```
L0 编译闸  backend: ./mvnw -q compile            → 退出码 0
L0 类型闸  pnpm typecheck（+ apps/web 内 npx tsc --noEmit 直跑）→ 0 错
L1 后端    ./mvnw -pl services/channel -am test -Dtest='AdDaypart*' \
             -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
           → Tests run: 30, Failures: 0, Errors: 0（27 + 3）
L1 前端    pnpm vitest run ad-daypart-model.test.ts ad-dayparts-allowlist.test.ts
           → Test Files 2 passed, Tests 27 passed
共享文件回归 pnpm vitest run apps/web/src/app/api/trade
           → Test Files 71 passed, Tests 394 passed（api-proxy 是多车道共用的累积白名单）
契约      OpenApiContractTest（1 例绿）重导出快照 → 重跑 catalog-contracts.py
           → channel.openapi.json / contracts/channel.ts 与仓库内产物**逐字节相同**
```

**容器回收**：本车道两个测试类的容器由新增 `@AfterAll` 显式回收。跑前后 `docker ps --filter ancestor=postgres:16-alpine` 计数**均为 1**（净增 0）—— 残留那 1 个属于并行车道 `fix-channel-l2-red`（其 `OpenApiContractTest` 无 `@AfterAll`，属**既有**仓库测试，不在本车道范围）。

## 后续待办

1. **平台回写队列未交付** ⇒ 改预算 / 改竞价只落**本地**并标记「待回写平台」，界面显式标出，不把「本地已改」说成「平台侧已改」。
2. **按星期分曲线属 14.2.1.3（未交付）** —— 本模块曲线**只按小时**；api-proxy 刻意**不放行** `ad-dayparts/weekdays`。
3. **活动状态不在本模块范围**（见 trade-off 3）。
4. **下游 14.2.2.2 自动规则**消费本模块上限的读取形状已提供，但回写队列那侧的联动不在本模块。
5. `OpenApiContractTest` 无 `@AfterAll` 回收容器 —— **既有**仓库问题，会在并发跑 channel 全量测试时堆容器；不在本车道范围，建议单独开一条修。
