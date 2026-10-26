# 2026-10-25 · batch-92 —— 续做 13.1.1.1 + 首次派发 `platform` 服务引导（零回收 · 双车道在途）

> 批次号 **92**（承接 `batch-91`；取号时 `ls docs/sessions/2026-10/ | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1` ⇒ 最大为 `batch-91`）。
> 集成头 `integration/module-dev`（`f23c3e95`）；调度者：Mavis（pipeline cron tick，2026-10-25 04:00 触发）。
> **强度档位：降档模式** —— 当前时刻 04:00 ≥ `TOKEN_HORIZON=2026-10-25T00:00:00+0800`
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（自动降档，不是调度者自选）。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度 | 465 总数 · **400 已交付** · 0 进行中 · **65 未开发**（完成率 **86.0%**，离 465 还差 **65**） |
| 容量闸判定 | **GO** —— running **1**/6、`pmset -g therm` **无降频项**、load **1.70 / 1.87 / 1.96**（10 核）、Testcontainers PG **0** 个 |
| 节流闸 | 距上批 **119 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 本批回收 | **0 条** —— `merge-ready` 返回「没有待并入的车道」；`gc` 移除 0 个 |
| 本批派发 | **2 条**（降档后 LANES=2）：**13.1.1.1 经营总览**（V376，续做 commit 2）+ **18.1.1.1 应用与凭证**（V377，含 **`platform` 服务引导**，首次进入卷 18） |
| 静态预检 | 对**待并入的 V376** 复查：**三项零命中**；V377 尚在车道内未写，规则已写进任务书 |
| L2 批量闸 | **本轮延后**（在途车道 L1 并发禁令）⇒ **本轮不 push**，理由与欠账见下节 |
| 台账完整性 | `grep -c` = **465**，无重复 ID；`sync.mjs --check` 绿 |
| 收尾三件套 | 说明 ✅ / commit ✅ / **push ❌（L2 未绿 —— 本轮明确缺 push）** |
| 调度闸 | `gate acquire` 成功（TTL 4200s），收尾 `gate release` |

---

## 一、进度

`sync.mjs` 实测：**18 卷 / 465 个 L4 模块，已交付 400 · 进行中 0 · 未开发 65**。

| 阶段 | 已交付 | 进行中 | 未开发 |
| --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 |
| D3 | 40 | 0 | 0 |
| D4 | 60 | 0 | 0 |
| D5 | 52 | 0 | 0 |
| D6 | 75 | 0 | 3 |
| D7 | 93 | 0 | 62 |

未开发 65 个按卷分布：卷 1（5）· 卷 5（3）· 卷 13（**23**）· 卷 14（4）· 卷 15（6）· 卷 18（**24**）。
分解口径与 batch-91 一致：① 主责微服务不存在 **47**（卷 13 reporting 23 + 卷 18 platform 24）·
② 蓝图循环依赖 **4**（`5.1.1.1 ⇄ 5.1.2.1`、`15.3.1.1 ⇄ 15.3.1.3`）· ③ 上游未交付（传递自 ①②）**14** · ④ 可排 **0**。

---

## 二、容量闸判定：GO

| 闸 | 判据 | 实测 | 结论 |
| --- | --- | --- | --- |
| 温度闸（最高优先级） | `pmset -g therm` 出现降频项且 < 100 | `Note: No thermal warning level has been recorded` | **通过** |
| 配额闸 | running ≥ 7 即 STOP | running **1** / 配额 6 | **通过** |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` ≥ 6 | **0** 个（无标签的孤儿容器也为 0） | **通过** |
| load 闸 | load 1min > 核数（10） | **1.70** / 1.87 / 1.96（10 核），趋势平稳略降 | **通过** |
| 节流闸 | 距上批 ≥ 阈值（降档后 100 分钟） | **119 分钟** | **通过** |

闸优先级（温度 > 配额 > PG > load）下**无一条命中** ⇒ 判定 GO。
取闸时机遵循「先过节流闸 + 容量闸再 `gate acquire`」——避免空转占闸。`last-batch-at` **在通过节流闸之后**才写。

---

## 三、本批回收：0 条

- `node tools/module-progress/lane.mjs merge-ready` ⇒ `{"ok": true, "merged": [], "note": "没有待并入的车道"}`
- `node tools/module-progress/lane.mjs gc` ⇒ `{"ok": true, "removed": []}`

⇒ 集成头本批**没有任何车道并入**，代码状态与 batch-90 的 GREEN L2 点逐字节相同（见 L2 一节）。

---

## 四、诊断：batch-91 的 13.1.1.1 车道**已死**，不是「慢」

`lanes.json` 中 `13.1.1.1` 状态 `running`，但 `heartbeatAt=2026-10-24T18:12:30Z`（约 108 分钟前 > 90 分钟 TTL ⇒ stale），
`sessionId=batch-91-13.1.1.1`。取证结论：

| 证据 | 观察 |
| --- | --- |
| 分支实况 | `feat/13-1-1-1` 有 **commit 1**：`8c8f7c47 feat(reporting): 服务引导 —— 13 利润与经营分析服务骨架 + 8088 端口 + V376 投影表结构 + 契约导出`（16 文件 / +943 行） |
| commit 2 | **未提交** —— worktree 内 10 个未跟踪 Java 文件（`ReportingProjectionService` / `RefreshService` / `UpstreamClient` / `MetricCatalog` / `ProjectionModels` + 5 个 `*Port`，共 2020 行），**无 controller/端点、无前端页面、无模块测试** |
| 进程 | worktree 最后写入 02:15；无 mvn / surefire / vitest / next 进程 |
| 会话 | 父会话 `mvs_012f37f9c6e84ede9a12d6b0a7616776` 报 `net::ERR_HTTP2_PING_FAILED`；worker 会话 `mvs_cf32db61e9d4477bbd7d805660bb32bd` 报 `net::ERR_CONNECTION_CLOSED` |

⇒ **不是排队太久，是 worker 掉线**。故本轮走 `plan --lanes 2` 的重派路径（`reDispatched: 1`），
**保留 commit 1 与那 10 个半成品**，让新 worker 判断「复用还是重写」。

---

## 五、本批派发：2 条车道

### 5.1 车道 A —— 13.1.1.1 经营总览（续做 commit 2）

| 项 | 值 |
| --- | --- |
| 模块 ID / 名称 | **13.1.1.1 经营总览**（阶段 D7，L3 锚点 13.1.1） |
| 迁移版本 | **V376**（已在 commit 1 内，**不得再新建迁移**，V377 归平台车道） |
| 分支 / worktree | `feat/13-1-1-1` / `.worktrees/13.1.1.1`（基线 `8ef8abbd`） |
| 登记方式 | `lane.mjs plan --lanes 2 --session batch-92-13.1.1.1` ⇒ `reDispatched: 1` |
| 后台任务 | `bg_8cd2967a-2674-4afe-93ab-5e7c3b0ef88d`（worker，后台运行） |

**这个功能做什么**：让运营在一个页面上看到公司 / 法人 / 店铺维度的核心经营指标（销售额、订单量、销量、
毛利额与毛利率、广告花费、净利、退货率、库存总额、应收余额），支持时间对比与环比同比，
并能下钻到构成明细；页面常驻显示「数据更新时间」，让用户一眼分清看到的是 T-1 还是实时数据。

**技术落地（任务书口径，产出以 worker 实际提交为准）**：

- 迁移：`V376__reporting_projection.sql`（commit 1 已建，commit 2 不再新增）—— 六表
  `reporting_metric_definition`（口径唯一）· `reporting_projection_event`（幂等账本，UNIQUE(tenant_id, source_service, source_event_id)）·
  `reporting_projection_cursor`（可重建游标）· `reporting_metric_fact`（投影事实）·
  `reporting_drilldown_fact`（构成明细，可下钻）· `reporting_currency_rate_snapshot`（带时点汇率快照）；
  六表 `tenant_id NOT NULL` + `ENABLE` **与** `FORCE` RLS isolation + GRANT。
- 端点：13.1.1.1 的指标卡 / 趋势 / 下钻 / 数据更新时间戳端点（路径由 worker 按 `reporting` 命名空间定）。
- 页面：`/analytics` 入口下的经营总览（卷 13 卷首写死的前端入口），含错误态/空态/加载态。
- 测试：`Reporting*` + `OpenApiContractTest`，含租户隔离三断言。

**关键设计决策（调度者口径）**：卷 13 红线「不产生业务事实、不做业务写入」⇒ reporting 是**投影服务**，
事件 → 投影的管道形状（消费游标 + 幂等 + 可重放）由 worker 设计并写进会话日志 —— 调度者不替它拍板。

### 5.2 车道 B —— 18.1.1.1 应用与凭证（含 `platform` 服务引导，首次进入卷 18）

| 项 | 值 |
| --- | --- |
| 模块 ID / 名称 | **18.1.1.1 应用与凭证**（阶段 D7，L3 锚点 18.1.1） |
| 迁移版本 | **V377**（`usedVersions()` 口径确认空闲：集成头最大 V375，V376 已被 reporting 占用） |
| 分支 / worktree | `feat/18-1-1-1` / `.worktrees/18.1.1.1`（**基线 `8c8f7c47`**，见第六节） |
| 登记方式 | `lane.mjs adopt --lane 18.1.1.1 --branch feat/18-1-1-1 --worktree … --migration-version V377 --session batch-92-18.1.1.1` |
| 后台任务 | `bg_d8245cf9-b0a8-47e6-a1cf-45c787551e8b`（worker，后台运行） |

**这个功能做什么**：让第三方系统（或内部系统）以「应用」为单位接入 starcon —— 运营注册一个应用、
拿到 API Key 与 Secret（**明文只在生成时展示一次**，库里只存摘要）、圈定它能访问哪些资源与操作、
配 IP 白名单与调用配额；密钥泄露时能一键吊销。

**技术落地（任务书口径，产出以 worker 实际提交为准）**：

- 迁移：`V377__platform_app_credential.sql` —— 应用档案 / 凭证摘要 / 授权范围 / IP 白名单 / 配额；
  全部 `tenant_id NOT NULL` + `ENABLE` **与** `FORCE` RLS isolation + GRANT。
- 端点：应用注册、密钥生成与轮换、授权范围配置、白名单、配额设置与吊销。
- 页面：`/integrations` 入口下的应用与凭证管理（卷 18 卷首写死的前端入口）。
- 测试：`Platform*` + `OpenApiContractTest`，含租户隔离三断言。
- 服务引导（commit 1）：`backend/services/platform/` 骨架 + 端口 **8089** + 五个共享文件 + 契约导出。

**关键设计决策（调度者口径）**：凭据摘要照 identity 的 `BCryptPasswordEncoder` 先例；
**平台服务与租户/权限的边界**（对外应用凭证 vs 平台内部身份体系怎么分界）由 worker 设计并写进会话日志。

### 5.3 为什么平台车道走 `adopt` 而不是 `plan`

`plan --lanes 2` 的 `serviceBlocked` 指出：卷 18 主责服务 `platform` 不在集成头 ⇒ 24 个模块全被微服务闸挡住。
按 PIPELINE.md 第 4 步对服务缺口的处置（原文「该**并入服务**或修 `plan`」）与 batch-77 的 finance 先例，
本轮照此办理。`adopt` 的两处语义修正（与 batch-91 同一套）：

1. 分支 `feat/18-1-1-1` **从 `8c8f7c47` 切出**（不是从集成头 —— 原因见第六节），
   worktree 建立后 `pnpm install --prefer-offline`（3.3 秒）；
2. **`baseSha` 由 `adopt` 默认的 `branch~1`（`8ef8abbd`）改回真实基线 `8c8f7c47`**
   ⇒ `land` 的「无新提交不许落地」闸重新生效（否则 `adopt` 会拿到集成分支的父提交，让这道闸失效）。

---

## 六、跨车道零冲突设计（本批的核心工程决策）

两条车道都要动共享文件，若处理不当会在线下轮并入时炸冲突。实测结论（隔离 clone 中用 `git merge-tree --write-tree` 预演）：

| 共享面 | 冲突原因 | 本批处理 |
| --- | --- | --- |
| `backend/pom.xml`、`deploy/docker/catalog-init/01-databases.sh`、`deploy/docker/docker-compose.catalog.yml`、`tools/codegen/catalog-contracts.py`、`.github/workflows/ci.yml` | 两个服务引导都改这五个文件 | **平台车道基线 = reporting commit1 `8c8f7c47`** ⇒ 平台分支天然含 reporting 的改动，只需在之上追加，两种并入顺序均**零冲突**。同时禁止 13.1.1.1 的 commit 2 修改这五个文件 |
| `apps/web/src/lib/api-proxy.ts`（12166 行 / 248 条 pattern） | 共享白名单累积正则，**不在 `.gitattributes` 的 `merge=contract-union` 名单里** ⇒ 无 union 驱动器，同位置插入必冲突 | **锚点分离**：reporting 插**数组最开头**，platform 插**数组末尾**（相距 10000+ 行） |
| `packages/api-contracts/**`、`*.openapi.json`、`module-progress.md` | 多车道共改 | 已有 union 驱动器（按类型名 / OpenAPI 路径 / 模块 ID 三方求并），并入时自动求并 |

⚠️ 实测记录：若平台车道**改从集成头分叉**，与 reporting commit1 并入时
`.github/workflows/ci.yml`、`backend/pom.xml`、`tools/codegen/catalog-contracts.py` **必冲突**。
⇒ 「第二条服务引导车道必须基于第一条的 commit1 分叉」这条结论已固化进任务书。

⚠️ `packages/api-contracts/src/index.ts` 是 `export * from` 形式，不匹配 TS_KEY 正则
（`^export\s+(?:type|interface|const|enum)\s+…`）⇒ 走行级兜底并集，两条 `export *` 会并存（无语义冲突）。

---

## 七、静态预检（5.1）

协议要求对本批**待并入**的新迁移跑「已知互相破坏形状」扫描。本批待并入的迁移是 **V376**（在 13.1.1.1 worktree 内）：

| 形状 | 命中 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **0** |
| ② `SET NOT NULL` | **0** |
| ③ `ADD CONSTRAINT … CHECK` | **0** |

补充核对：V376 的 **6 张表全部** `tenant_id NOT NULL` + `ENABLE` **与** `FORCE` RLS +
`CREATE POLICY isolation` + `GRANT SELECT,INSERT,UPDATE,DELETE` ⇒ 六表齐全。

V377 尚在平台车道内未写 ⇒ 静态预检将在其落地并入时补做；
「ADD COLUMN 必须带 DEFAULT / 禁止 SET NOT NULL / 禁止 ADD CONSTRAINT CHECK」已写进平台车道任务书。

---

## 八、L2 批量验证结果：**本轮延后 ⇒ 本轮不 push**

**本轮没有跑全量 L2**，两个理由都是协议内的硬约束：

1. **在途车道 L1 并发禁令。** 协议明写：「**L2 不得与在途车道的 L1 并发跑** —— 多套 Testcontainers PG 互相抢资源，
   2026-10-19 20:00 那轮实测：L2 与 4 条车道的 L1 并发时 PG 峰值 16，后端 1364 例里 3 failures + 24 errors
   **全是假红**（`seed()` 503 / 连接拒绝）。**先等在途车道落地，再跑 L2**」。
   本批刚派发 2 条车道（正在跑各自的 L1）⇒ **此刻跑 L2 正是被明令禁止的形状**。
2. **零代码增量。** 本批没有并入任何车道。集成头 `f23c3e95` 相对**上一个 GREEN L2 点** `7ecd64f5`（batch-90，23:16:59→00:18:19，12 步退出码全 0）的差异：

   ```
   git diff --name-only 7ecd64f5..HEAD | grep -vE '^docs/|^tools/module-progress/'
   → (NONE — zero code delta)
   ```

   差异只有 6 个文件（4 份 main-sync 会话日志 + batch-90/91 两份批次说明 + 台账 `module-progress.md`），**全是 markdown**。

⇒ **batch-92 的全量 L2 闸尚未执行**，将由下一 tick 在两条车道落地并入后补跑。
**这是本轮唯一的未完成验证项**，在此点名，不含糊。

### 本轮实际执行且通过的闸

| 闸 | 结果 |
| --- | --- |
| `node tools/module-progress/sync.mjs --check` | **绿** —— 「✓ 台账与蓝图一致」 |
| 台账完整性 `grep -c` / `uniq -d` | **465 行 / 无重复 ID** |
| 静态预检（5.1） | V376 三项**零命中** |
| 并入零冲突预演（`git merge-tree --write-tree`） | 两种并入顺序均 **EXIT 0** |
| 全量 `mvnw verify` + `pnpm typecheck/lint/test` + Next build | **本轮延后 → 下一 tick 补跑** |

---

## 九、收尾三件套

| # | 项 | 结果 |
| --- | --- | --- |
| ① 写说明 | ✅ | 本文件 `docs/sessions/2026-10/2026-10-25-batch-92-dual-lane-dispatch-reporting-continue-platform-bootstrap.md` |
| ② commit | ✅ | `docs(batch-92)`，**与台账同一个 commit**（未拆开） |
| ③ push | ❌ | **缺 push** —— 协议与 cron 任务书均写明「**L2 没绿绝对不 push**；说明照写、commit 照做，成果与诊断留本地，修复后随下一批一起 push」。本轮 L2 因在途车道 L1 并发禁令未执行 ⇒ **不 push**，成果与诊断留在集成头本地 |

⚠️ 这与 batch-91 的处理**不同**：batch-91 在同样的「零代码增量 + L2 延后」情形下仍 push 了（论证是
「被 push 的只有 markdown，被 push 的代码在 batch-90 已 L2 GREEN」）。**本轮按 cron 任务书的字面要求收紧**：
L2 未绿就不 push，缺口在汇报里点名。代价是本轮说明要等下一批随 L2 绿了一起上远端。

---

## 十、吞吐预期（按本轮实时配置现算，不引用历史文档的写死数字）

- **档位**：降档模式（`LANES` 4 → **2**，节流闸 50 → **100 分钟**）。
- **cron**：`0 * * * *`（整点，24 触发点/天）⇒ 节流闸 100 分钟决定实际放行批次：**最多 ~14 批/天**。
- **名义吞吐**：2 条/批 × 14 批/天 = **28 个/天**；乘组批模式综合折损系数 ≈ 0.70 ⇒ **实际 ~19 个/天**（区间 14–19）。
- **完成时间**：**不按这个数字给承诺** —— 65 个未开发模块里 **56 个**要等两个服务引导落地后才可排；
  剩下 **9 个**（`5.1.1.1` / `5.1.2.1` / `15.3.1.1` / `15.3.1.3` + 5 个传递依赖）卡在**蓝图循环依赖**上，
  协议明令调度者不得自行改蓝图依赖 ⇒ **这 9 个需要人工确认蓝图「关系」字段**。
- **诚实区间**：两个服务引导落地后（约 2 批），56 个模块按 ~19 个/天 ⇒ **约 3 天**；最后 9 个取决于人工解环时间。**不承诺精确完成日期。**

---

## 十一、下一批（batch-93）的预期

1. **回收 13.1.1.1 与 18.1.1.1**（`merge-ready`）⇒ 补跑**本批欠下的全量 L2**
   （`mvnw verify` + `pnpm typecheck/lint/test` + Next build + `sync --check`）⇒ 绿了再 push。
   ⚠️ 两条车道本批**已按「平台基于 reporting commit1」分叉**，两种并入顺序都预演过零冲突 —— 但真并入时
   仍要盯 `CONFLICT_KEYS` 输出与 `.github/workflows/ci.yml` / `backend/pom.xml` / `tools/codegen/catalog-contracts.py` 三个点。
2. **`reporting` 并入后** ⇒ 微服务闸放行卷 13 ⇒ 下一轮 `plan --lanes 2` 能排出**卷 13 车道**；
   传递解锁 14.2.1.1 / 14.2.1.2 / 14.3.1.3 / 14.3.2.1 / 1.1.2.1 / 1.1.2.2 / 1.3.2.1 / 1.3.2.2。
3. **`platform` 并入后** ⇒ 卷 18 放行（24 个模块 + 传递解锁 1.5.2.1 = 25 个）。
4. **蓝图循环依赖仍需人工确认** —— `5.1.1.1 ⇄ 5.1.2.1` 与 `15.3.1.1 ⇄ 15.3.1.3`（已在 backlog 挂 P0）。

---

## 十二、风险提示

- **停滞保护计数（重新核算）**：协议触发条件是「连续 **3 轮**『派发了车道但一条都没交付』」。
  逐轮核算：**batch-89 派发 1 条 + 交付 1 条**（12.3.2.2 并入、12.3.2.3 派发）· **batch-90 派发 0 条 + 交付 1 条**（12.3.2.3 并入）·
  **batch-91 派发 1 条 + 交付 0 条**（13.1.1.1）· **batch-92 派发 2 条 + 交付 0 条（本轮，欠账）**。
  ⇒ 「派发但零交付」的连续计数目前只有 **batch-91 一轮**，**停滞保护未触发**，本轮派发 2 条是正确的。
  ⚠️ 若 batch-93 仍然零交付，则计数达 3 ⇒ **batch-94 起触发零派发 + 停滞报告**。
- **13.1.1.1 仍是「第二条命」**：batch-91 的 worker 是网络掉线，不是能力问题。commit 1 已安全落地（16 文件 / +943 行），
  那 10 个半成品（2020 行）尚未编译验证过 —— 新 worker 的第一件事是判断复用还是重写。
- **`platform` 车道基线依赖 reporting commit 1**：若 13.1.1.1 的 commit 2 **越界修改了那五个共享文件**，
  下一轮并入会冲突。任务书已明令禁止，回收时要重点核对这五个文件的 diff。
- **两条车道都改 `packages/api-contracts`**：依赖 union 驱动器的三方求并；若出现 `CONFLICT_KEYS` 输出，
  说明同一个类型名两边都改了 —— 要人工核对，不能静默取 theirs。
- **本轮 push 缺口**：batch-92 的说明与台账停留在集成头本地，等下一批 L2 绿后一起上远端。
