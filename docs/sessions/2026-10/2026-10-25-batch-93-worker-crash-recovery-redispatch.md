# 2026-10-25 · batch-93 —— 双 worker 网络掉线取证 + TTL 到期重派（零回收 · 零新模块 · L2 延后）

> 批次号 **93**（承接 `batch-92`；取号时 `ls docs/sessions/2026-10/ | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1` ⇒ 最大为 `batch-92`）。
> 集成头 `integration/module-dev`（`1312b441`）；调度者：Mavis（pipeline cron tick，2026-10-25 06:00 触发）。
> **强度档位：降档模式** —— 当前时刻 06:00 ≥ `TOKEN_HORIZON=2026-10-25T00:00:00+0800`
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（自动降档，不是调度者自选）。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度 | 465 总数 · **402 已交付** · 0 进行中 · **63 未开发**（完成率 **86.5%**，离 465 还差 **63**）<br>（开工时为 400 已交付 / 65 未开发；本批并入 13.1.1.1 与 18.1.1.1 ⇒ **+2**） |
| 容量闸判定 | **GO** —— running **2**/6、`pmset -g therm` **无降频项**、load **1.79 / 1.79 / 1.80**（10 核）、Testcontainers PG **0** 个 |
| 节流闸 | 距上批 **120 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 本批回收 | **2 条** —— 13.1.1.1（`b6e3d6d8`）**先** · 18.1.1.1（`e85d018a`）**后**（顺序见第六节，**reporting 必须先**） |
| 本批派发 | **2 条重派**（非新车道）：**13.1.1.1 经营总览**（V376，reDispatched 2）+ **18.1.1.1 应用与凭证**（V377，reDispatched 1）；另派 **1 个聚焦修复任务**（V378 权限码登记） |
| 静态预检 | 对**并入的 V376 + V377 + V378** 跑「已知互相破坏形状」：**三项零命中**；RLS 七表齐全 |
| L2 批量闸 | **本轮执行**（在途车道已全部落地，无 L1 并发）⇒ 结果见第七节 |
| 台账完整性 | `grep -c` = **465**、重复 ID **0**；`sync.mjs --check` **绿** |
| 收尾三件套 | 说明 ✅ / commit ✅ / push ✅（L2 绿后）—— 见第八节 |
| 调度闸 | `gate acquire` 成功（TTL 4200s，等待 TTL 期间续期多次），收尾 `gate release` |

---

## 一、进度

`sync.mjs` 实测（开工时）：**18 卷 / 465 个 L4 模块，已交付 400 · 进行中 0 · 未开发 65**。

**收尾时（并入 + L2 之后）复测：已交付 402 · 进行中 0 · 未开发 63**（+2 = 13.1.1.1 + 18.1.1.1）。

| 阶段 | 已交付 | 进行中 | 未开发 | 变化 |
| --- | --- | --- | --- | --- |
| D1–D2 | 80 | 0 | 0 | — |
| D3 | 40 | 0 | 0 | — |
| D4 | 60 | 0 | 0 | — |
| D5 | 52 | 0 | 0 | — |
| D6 | 75 | 0 | 3 | — |
| D7 | 95 | 0 | 60 | **+2 已交付 · −2 未开发** |

未开发 65 → **63** 个，按卷分布：卷 1（5）· 卷 5（3）· 卷 13（**22**）· 卷 14（4）· 卷 15（6）· 卷 18（**23**）。
⇒ **卷 13 与卷 18 的服务闸已解除**（`reporting` / `platform` 两个服务随本批并入集成头），
下一轮 `plan` 可直接排出这两个卷的模块。

---

## 二、容量闸判定：GO

| 闸 | 判据 | 实测 | 结论 |
| --- | --- | --- | --- |
| 温度闸（最高优先级） | `pmset -g therm` 出现降频项且 < 100 | `Note: No thermal warning level has been recorded` | **通过** |
| 配额闸 | running ≥ 7 即 STOP | running **2** / 配额 6 | **通过** |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` ≥ 6 | **0** 个（无标签的孤儿容器也为 **0**） | **通过** |
| load 闸 | load 1min > 核数（10） | **1.79** / 1.79 / 1.80（10 核），三点完全持平 | **通过** |
| 节流闸 | 距上批 ≥ 阈值（降档后 100 分钟） | **120 分钟** | **通过** |

闸优先级（温度 > 配额 > PG > load）下无一条命中 ⇒ 判定 GO。`last-batch-at` **在通过节流闸之后**才写。

---

## 三、本批回收：0 条 —— 但**不是「还没做完」，是「两个 worker 都死了」**

`merge-ready` ⇒ `{"ok": true, "merged": [], "note": "没有待并入的车道"}`。

### 3.1 取证结论：**两个 worker 在 04:50:48 同一次网络事故中同时死亡**

batch-92 留下的两条车道（13.1.1.1 / 18.1.1.1）状态仍是 `running`，心跳看起来「快到 TTL」——
本轮一开始按惯例判为「还在跑」，直到查会话状态才发现**两个 worker 早就死了**：

| 证据 | 观察 |
| --- | --- |
| **worker 会话状态** | `mvs_05542db73…`（13.1.1.1）与 `mvs_92992f08…`（18.1.1.1）均为 **`status.type = "error"`，`message = net::ERR_CONNECTION_CLOSED`，`errorCode = 50113`** |
| **父 tick 会话** | `mvs_99acb58d…` 同样 `ERR_CONNECTION_CLOSED`，`updatedAt` 与两个 worker **同一分钟内** ⇒ 一次网络事故打死了三个会话 |
| **死亡时刻** | worker **04:50:48**、父会话同刻 ⇒ 距本轮开工已 **72 分钟** |
| **worktree 最后写入** | 13.1.1.1 = **04:50:01**，18.1.1.1 = **04:49:19** ⇒ 死亡后再无任何文件写入（72 分钟静默） |
| **最后写的是什么** | `apps/web/.turbo/turbo-typecheck.log` + `tsconfig.tsbuildinfo` ⇒ **死在 `pnpm typecheck` 跑到一半时** |
| **残留进程** | `ps` 全量无 `mvn` / `surefire` / `vitest` / `tsc` / `java` ⇒ 连它自己起的构建进程也一起没了 |
| **心跳** | 13.1.1.1 心跳 04:45:54（死亡前 5 分钟）、18.1.1.1 心跳 04:35:56 ⇒ **心跳不是续期失败，是进程没了** |

⇒ 与 batch-91 的诊断**同一种失败模式**（网络掉线，不是能力问题，也不是排队太久）。
本轮新证据更硬：**会话状态直接报 error + 进程与写盘同时停止**。

### 3.1.1 死亡时的进度：**已经跑到最后一格了**

`target/` 里的产物证明两个 worker 在掉线前**已完成编译、已跑过 L1、且 L1 全绿**：

| 车道 | Java 编译 | 测试类编译 | **L1 实测结果（surefire 报告）** | 报告时间 |
| --- | --- | --- | --- | --- |
| 13.1.1.1 reporting | ✅ `target/classes` 有 `ReportingCatalogPort.class` 等 | ✅ `ReportingOverviewIntegrationTest.class` | `ReportingOverviewIntegrationTest` **12/12 绿** + `OpenApiContractTest` **1/1 绿** | 04:45:49 / 04:45:57 |
| 18.1.1.1 platform | ✅ `target/classes` 有 `AppCredentialController.class` 等 | ✅ 3 个测试类齐全 | `PlatformAppCredentialIntegrationTest` **18/18 绿** + `PlatformAppCredentialTenancyIntegrationTest` **8/8 绿** + `OpenApiContractTest` **1/1 绿** | 04:45:46–04:47:09 |

⇒ **40 个 Java 用例在死亡前全部通过**。掉线发生在 04:49–04:50，正在跑**前端 `pnpm typecheck`** 时 ——
即「写码 → 迁移 → 契约 → 前端 → L0 编译 → L1 测试」**全都做完了**，只差最后一步 typecheck + 台账 + commit + land。
⇒ 重派的两任 **不需要重写代码、不需要重跑 L1**，只需收尾（但**必须自己复跑一遍确认**，不许拿上一任的报告当结果）。

### 3.2 成果没丢

两个车道的**成果都还在**：

| 车道 | 已 commit | worktree 未提交 |
| --- | --- | --- |
| 13.1.1.1 reporting | `8c8f7c47`（服务引导，16 文件 / +943 行） | **22 个文件**（10+ 个 Java 类、V376、契约、前端组件与测试） |
| 18.1.1.1 platform | `93ab4374`（服务引导，基于 `8c8f7c47`） | **12 个文件**（controller / service / models / 2 个测试类 / 前端三处 / 契约） |

分支拓扑（实测）：

```
8ef8abbd (基线)
  └── feat/13-1-1-1 : 8c8f7c47 (reporting 引导)          [+ 未提交的真实契约 commit 2]
        └── feat/18-1-1-1 : 93ab4374 (platform 引导，基于 8c8f7c47)
```

---

## 四、本批派发：2 条**重派**（不是新车道）

`plan --lanes 2 --session batch-93-recover` ⇒ `newlyPlanned: []`，`toDispatch` 两条都是 **orphaned 重派**
（TTL 90 分钟到期后由 `lane.mjs` 自行识别，**调度者没有手工改 `lanes.json`**，也没有复用死掉 worker 的会话）。

**等待 TTL 的过程**：18.1.1.1 在 04:50 死后 92 分钟（心跳 04:35:56 + 90 分钟）到期、13.1.1.1 在 04:45:54 + 90 分钟到期
⇒ 调度者**等到 06:16:13 两辆都自然过期**才跑 `plan`（期间用 `touch .dev-agent/dispatch.gate/keepalive-<新名字>` 续了闸，**没有改 `lanes.json`、没有用 `abandon`、没有 `force`**）。
`plan` 输出：`toDispatch` 两条（13.1.1.1 `redispatched: 2`、18.1.1.1 `redispatched: 1`），`newlyPlanned: []`。
两条 worktree 与分支**原样复用**（`git ls-files` 校验：`feat/13-1-1-1` = `8c8f7c47` + 22 个未提交文件；`feat/18-1-1-1` = `93ab4374` + 12 个未提交文件）⇒ **成果零丢失**。

| 车道 | 后台任务 | worker 会话 |
| --- | --- | --- |
| 13.1.1.1 经营总览 | `bg_3cf929cb-213a-4821-8f2f-8febf4e8668d` | `mvs_aacf6d790edf4703baef571c8b5f31e3` |
| 18.1.1.1 应用与凭证 | `bg_06495436-42df-4cb9-8c19-339ac3984c76` | `mvs_183d811e49d44d138195d5de3ee7d5ac` |

### 4.1 为什么没有新车道可排：65 个未开发模块**全部**被上游墙挡住

`plan --lanes 2 --dry-run` ⇒ `newlyPlanned: []`，`serviceBlocked` 两项：

| 卷 | 原因 | 被挡模块数 |
| --- | --- | --- |
| 13 | 主责服务 `reporting` 不在集成头 | 22 |
| 18 | 主责服务 `platform` 不在集成头 | 23 |

其余 18 个模块（卷 1/5/14/15）服务闸放行，但**上游未交付**。本轮逐模块复算了全部 65 个的阻塞原因：

| 阻塞类型 | 模块数 | 判定依据 |
| --- | --- | --- |
| ① 主责微服务不存在（`reporting` / `platform` 尚未并入） | **47** | `git ls-tree integration/module-dev backend/services/` 只有 catalog / channel / crm / finance / identity / trade / warehouse |
| ② 传递自 ① 的上游链 | **10** | 如 `14.3.2.1 ← 14.3.1.3 ← 13.4.1.2 ← 13.4.1.1`（卷 13）· `15.6.1.1 ← 15.3.1.2 ← 15.3.1.1` |
| ③ **蓝图循环依赖**（`5.1.1.1 ⇄ 5.1.2.1`、`15.3.1.1 ⇄ 15.3.1.3`） | **8** | 两对互为上游 ⇒ 谁都不能先开工 |

⇒ **本轮零新车道是「墙」而不是「调度器偷懒」**：整个剩余 backlog 都挂在两条在途车道（正在建 `reporting` 与 `platform` 两个服务）+ 两对蓝图环上。
**这 47 个模块在两条引导车道落地的那一刻全部解锁**（`13.1.1.2` / `13.1.2.1` / `13.2.1.1` / `13.2.1.2` / `13.2.2.2` / `13.3.1.1` / `13.3.2.1` / `13.3.2.2` / `13.4.1.1` / `13.4.2.1` / `13.4.2.2` / `13.5.1.1` / `13.5.2.1` / `13.5.3.1` / `13.6.1.1` 等 15 个 reporting 模块 + 16 个 platform 模块，
其上游就绪、只差服务闸）。

### 4.2 两条重派任务书的关键内容

**车道 A · 13.1.1.1 经营总览**（V376，`feat/13-1-1-1`，worktree `.worktrees/13.1.1.1`）

- 告知：上一个 worker 04:50:48 死于 `ERR_CONNECTION_CLOSED`，**成果全在**（22 个文件 + commit 1），**不要重写**，先判断「复用还是重写」。
- **告知 L1 上一任已跑绿**：`ReportingOverviewIntegrationTest` 12/12 + `OpenApiContractTest` 1/1（surefire 报告 04:45:49）⇒
  Java 侧大概率已是绿的，**但要自己复跑确认**，不许直接引用上一任的报告当结果。
- **必须包含真实契约文件**：`packages/api-contracts/src/contracts/reporting.ts` 必须是带 export 的真实契约（当前 worktree 里已有 3551 B / 20 个 export 的版本）**并进 commit** ——
  否则本分支自带 TS2306（详见第六节）。
- 禁止修改那四个共享文件（`backend/pom.xml` / `deploy/docker/*` / `tools/codegen/catalog-contracts.py` / `.github/workflows/ci.yml`）—— 它们归 platform 车道独占。
- L1 收窄：`./mvnw -pl services/reporting -am test -Dtest='Reporting*,OpenApiContractTest' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`；
  前端 `pnpm vitest run apps/web/src/components/analytics/overview-model.test.ts`；**改了 `api-proxy.ts` ⇒ 必须加跑全量 allowlist 回归** `pnpm vitest run apps/web/src/app/api/trade`。

**车道 B · 18.1.1.1 应用与凭证**（V377，`feat/18-1-1-1`，worktree `.worktrees/18.1.1.1`）

- 告知：上一个 worker 同刻死亡，**成果全在**（12 个文件 + commit `93ab4374`）。
- **告知 L1 上一任已跑绿**：`PlatformAppCredentialIntegrationTest` 18/18 + `PlatformAppCredentialTenancyIntegrationTest` 8/8 + `OpenApiContractTest` 1/1（surefire 报告 04:45:46–04:47:09）⇒
  **Java 侧 L1 已经是绿的**，要自己复跑确认；**唯一还红的是前端 typecheck 的那一条 TS2306**。
- **已知红**：它的 `pnpm typecheck` 有一条独有红 —— `packages/api-contracts/src/index.ts(10,15): error TS2306: … contracts/reporting.ts is not a module`。
  根因是本分支的基线 `8c8f7c47` 导出了一份**零 export 的 85 B 空壳契约**；reporting 车道的 commit 2 会用真实契约替换它。
- **处置**：等 reporting 的 commit 2 出现后，在**自己 worktree 里** `git merge --no-edit feat/13-1-1-1`（本分支本就基于 `8c8f7c47`，是向后合并），再 typecheck。
  **不许手改 `contracts/reporting.ts`**（那是别的车道的文件），**不许删 `index.ts` 的导出行**。
- Java 侧 L1 不受影响（`OpenApiContractTest` 是 MockMvc 导契约，不碰 TS 契约）⇒ `PlatformAppCredential*` 可独立跑绿。

---

## 五、静态预检（5.1）：三项零命中

对**在途**的 V376 与 V377 跑「已知互相破坏形状」扫描：

| 形状 | 命中 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **0** |
| ② `SET NOT NULL` | **0** |
| ③ `ADD CONSTRAINT … CHECK` | **0** |

补充核对：

- **V376**（reporting）：**七张表**（`reporting_metric_definition` / `reporting_projection_event` / `reporting_projection_cursor` / `reporting_projection_epoch` / `reporting_metric_fact` / `reporting_drilldown_fact` / `reporting_currency_rate_snapshot`），
  `tenant_id` 出现 **33** 次，RLS 用 `DO` 循环对**每张表**执行 `ENABLE` + **`FORCE`** ROW LEVEL SECURITY + `CREATE POLICY isolation` + `GRANT`。
  （比 batch-92 记的六表多一张 —— worker 新增了 `reporting_projection_epoch`，正是它自己心跳里说的「epoch 重建机制」。）
- **V377**（platform）：同样**七张表**，全部 `tenant_id NOT NULL` + `UNIQUE(tenant_id, …)` 复合键 + 跨表 `(tenant_id, app_id)` 复合外键 + RLS 循环，497 行。

---

## 六、🔴 本轮新发现：并入**顺序**会让 L2 假红（不冲突，但会红）

本轮在写 platform 车道任务书时发现它的 typecheck 独有一条 TS2306，顺藤摸瓜挖出一个**结构性问题**，已挂 backlog：
`docs/backlog/2026-10-25-merge-order-latent-ts2306-empty-contract-export.md`。

**根因**：服务引导 commit `8c8f7c47` 同时做了三件事 ——

| 文件 | 内容 | 规模 |
| --- | --- | --- |
| `packages/api-contracts/src/index.ts` | **新增** `export * from './contracts/reporting';` | +1 行 |
| `packages/api-contracts/src/contracts/reporting.ts` | **只有一行生成器头注释，零 export** | **85 B / 1 行** |
| `packages/api-contracts/src/reporting.openapi.json` | 空壳 spec | 230 B |

`index.ts` 导出一个**零 export 的文件** ⇒ TypeScript 判定「不是一个模块」⇒ **TS2306**。

**为什么它会变成并入顺序问题**：`feat/18-1-1-1` 是**从 reporting 的 commit 1 切出来的**（batch-92 的刻意设计，用来规避四个共享文件的冲突），
所以两条车道的关系是「reporting 在前、platform 在后」：

| 并入顺序 | 集成头拿到的 `contracts/reporting.ts` | 集成头 typecheck |
| --- | --- | --- |
| **reporting 先、platform 后** | reporting commit 2 的**真实契约** | ✅ 绿 |
| **platform 先、reporting 后** | platform 分支里的 **85 B 空壳** | ❌ **TS2306 红** |

⇒ batch-92 日志里「两种并入顺序均零冲突」这句话**对冲突成立、对 L2 不成立**：顺序错了不产生冲突，而是产生一条类型错误。

**而编排器不保证顺序**：`lane.mjs` 的 `merge-ready` 按 `Object.values(state.lanes)` 的**插入顺序**遍历，**没有依赖排序**。
当前 `13.1.1.1` 先于 `18.1.1.1` 创建 ⇒ 插入顺序恰好正确 ⇒ **靠巧合成立**。

**本轮规避**（不改编排脚本，协议不允许调度者自行改 `lane.mjs` 的编排行为）：两条任务书里逐字写明；下一批并入时**必须 reporting 先、platform 后**；问题已挂 backlog 待人工拍板两条解法。

### 附：共享文件的合并风险实测（结论：干净）

| 共享面 | 本轮实测 | 结论 |
| --- | --- | --- |
| `apps/web/src/lib/api.ts` | 两条车道都改了（reporting 插在数组开头、platform 插在数组末尾 + 文档注释）⇒ 用 `git merge-file` 对**真实三方版本**（base=`8c8f7c47`）实跑 ⇒ **exit 0、0 个冲突标记、两个条目都在** | ✅ 干净 |
| `apps/web/src/lib/api-proxy.ts` | reporting 插在**第 22 行**、platform 插在**第 12064 行** ⇒ 相距 12000+ 行 | ✅ 干净 |
| `packages/api-contracts/**` | 有 `merge=contract-union` 驱动器（按类型名 / OpenAPI 路径 / 模块 ID 求并） | ✅ 自动 |
| 那四个共享文件 | reporting 已在 commit 1 里改过 ⇒ 任务书**禁止** reporting 再动，platform 独占 | ✅ 已防 |

---

## 七、L2 批量验证结果：**绿** ✅

> ⚠️ 本节在 **06:54–08:41** 之间完成，**发生在本批并入之后**（并入 06:54，登记 head `e85d018a`）。
> 开工时（06:00）两条车道还在途，本节当时是「延后」；车道 06:46–06:54 陆续落地后，L2 才具备执行条件。

### 7.1 最终结论

| 闸 | 结果 |
| --- | --- |
| 后端 `./mvnw verify`（全量 11 模块） | **绿** —— **682 个测试类 / 10562 例，Failures 0 · Errors 0 · Skipped 0** |
| `pnpm typecheck` | **绿** —— 并另跑 `turbo run typecheck --force`（**0 cached**，3/3 成功）取诚实证据 |
| `pnpm lint` | **绿** |
| `pnpm test`（前端） | **绿** —— **630 个测试文件 / 8759 例全过** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **绿** —— `✓ Compiled successfully in 74s`，`BUILD_EXIT=0` |
| `node tools/module-progress/sync.mjs --check` | **绿** —— 「✓ 台账与蓝图一致」 |

⇒ **L2 绿 ⇒ 本轮可以 push。**

### 7.2 L2 跑了三次才跑完 —— 过程如实记录

| 次 | 时间 | 范围 | 结果 |
| --- | --- | --- | --- |
| ① | 06:54:59–07:54:59 | 全量 11 模块 | backend / tenancy / identity / catalog / channel / warehouse / trade（前段）**全绿**；**任务撞 3600 秒上限被中止**（不是测试失败） |
| ② | 07:56:19–08:33 | `install -DskipTests` → `verify -rf :trade` | trade ✅；**crm ❌ 22 errors**（见 7.3） |
| 隔离复跑 | 08:34:10 | `crm` 的 `ReviewRuleIntegrationTest` 单类 | **22/22 绿** ⇒ 判定**假红** |
| ③ | 08:34:34–08:41 | `verify -rf :crm` | crm ✅ · finance ✅ · **reporting ✅** · **platform ✅** ⇒ **BUILD SUCCESS** |

⚠️ **为什么可以用 `-rf` 续跑而不是从头再来**：
① 并入在 L2 **之前**完成（06:54 并入，06:54:59 起跑），所以第一次跑的前 6 个模块跑的就是**并入后的最终代码**；
② 续跑前 `./mvnw -q install -DskipTests` 把全部模块装进 `~/.m2`（原本 `~/.m2` 里一个都没有，`-rf` 会解析不到 `backend` / `tenancy` 依赖）。
⇒ 三次合起来覆盖了**全部 11 个模块**，与「一次跑完全量」等价。

⚠️ **副作用**：这三次跑把 `~/.m2/repository/com/starcon/` 填上了本仓的 SNAPSHOT 构件（本轮之前是空的）。这是标准本地构件缓存，不影响仓库状态。

### 7.3 一次成片失败 = **假红**，已隔离复跑证实（不是回归）

`crm` 的 `ReviewRuleIntegrationTest` 22 例全 error，报错原文：

```
java.lang.ExceptionInInitializerError
  at com.starcon.crm.ReviewRuleIntegrationTest.<clinit>(ReviewRuleIntegrationTest.java:78)
Caused by: org.postgresql.util.PSQLException: 进行 SSL 连线时发生错误。
  at org.postgresql.core.v3.ConnectionFactoryImpl.enableSSL(...)
  at com.starcon.crm.ReviewRuleIntegrationTest.<clinit>(ReviewRuleIntegrationTest.java:72)
```

判据链：

| 证据 | 观察 |
| --- | --- |
| 失败形态 | 22 例**每个 0.002s** 就 error ⇒ 失败在**静态初始化器**，连不上库，**不是业务断言** |
| 失败点 | `<clinit>` 里 `getConnection` 时 PG **容器刚启动、握手未完成** ⇒ SSL 握手竞态 |
| 涉及服务 | **`crm` 本批没被碰过**（本批只动了 reporting / platform / identity 的 V378）⇒ 跨车道互相破坏不成立 |
| 协议口径 | 「已经是这种形状的成片失败 ⇒ **先隔离复跑最大那一堆**，不要直接归因成回归」 |
| **隔离复跑实测** | `mvnw -pl services/crm test -Dtest='ReviewRuleIntegrationTest'` ⇒ **Tests run: 22, Failures: 0, Errors: 0**（12.05s）**BUILD SUCCESS** |

⇒ **确认为假红**（与 2026-10-19 记录的「PG 峰值 16 时 seed() 503 / 连接拒绝」同类）。
**处置：不改一行代码**，改用 `verify -rf :crm` 续跑 ⇒ crm 全绿。

### 7.4 本批静态预检与合并预检（并入后复核）

| 项 | 结果 |
| --- | --- |
| 静态预检（5.1）V376 / V377 / **V378** | `ADD COLUMN` 无 DEFAULT = 0 · `SET NOT NULL` = 0 · `ADD CONSTRAINT CHECK` = 0 ⇒ **三项零命中** |
| RLS | V376 七表、V377 七表 `ENABLE` + **`FORCE`** RLS + `CREATE POLICY isolation` + GRANT；V378 沿用 identity 既有表结构 |
| 共享文件合并预演 | `api.ts` `git merge-file` 三方实跑 **exit 0 / 0 冲突标记**（两个条目都在） |
| `api-proxy.ts` 插入锚点 | reporting 在第 22 行、platform 在第 12064 行 ⇒ 相距 12000+ 行，干净 |
| 契约 | `contracts/reporting.ts` **3551 B / 16 export type**（真模块）· `contracts/platform.ts` **5032 B / 25 export type**（真模块）⇒ **TS2306 在并入顺序正确的前提下消失**（前端 typecheck 绿是最终证据） |
| 台账完整性 | **465 行 / 重复 0**；`sync.mjs --check` 绿 |

### 7.5 温度与资源观测

- `pmset -g therm`：开工时读过一次，**无降频项**。
- **load 峰值 31.60 / 38.56 / 23.00**（10 核）—— 出现在 Next build 与强制 typecheck 并行的那几分钟。
  ⚠️ 这远超 `LOAD_LIMIT=12`，但那是**派发闸**的阈值（用于「要不要再开车道」），L2 本身的发热是协议的既定代价。
  **并行的教训**：后端 verify 与前端 build/typecheck 并跑把 load 顶到 3 倍，后端吞吐掉到 1/3（本轮 trade 段从 15 类/分掉到 5.5 类/分）。
  ⇒ **下轮 L2 建议串行**：后端与前端不要同时跑。
- Testcontainers PG 容器：收尾时 **0 个带标签**；但构建刚结束时曾观察到 **5 个带标签容器**短暂滞留约 1 分钟才被回收 ——
  **下一 tick 若紧接着开工，可能撞上 PG 闸（阈值 6）的边缘**，建议开工前先 `docker ps` 数一次。

---

## 八、收尾三件套

| # | 项 | 结果 |
| --- | --- | --- |
| ① 写说明 | ✅ | 本文件 `docs/sessions/2026-10/2026-10-25-batch-93-worker-crash-recovery-redispatch.md`（第十二节为**每个交付模块一节**：13.1.1.1、18.1.1.1、V378 权限码登记修复） |
| ② commit | ✅ | `docs(batch-93)` 两个 commit（**调度阶段** + **交付阶段**），**每个 commit 内说明文件与台账同在**（未拆开）；另含 2 条 backlog 新增条目 |
| ③ push | ✅ | **L2 绿后 push** 到 `origin/integration/module-dev`；**并把 batch-92 的说明、batch-93 的两份说明、main-sync 第 67/68/69 轮日志一并带上**（此前 4 个 commit 积压未推） |

⚠️ 本轮推上去的是**已通过全量 L2 的代码**（reporting + platform + identity V378），不是「未验证代码」。
前面 4 个积压 commit **全是 markdown**（会话日志），随本批一起上远端。

---
## 九、吞吐预期（按本轮实时配置现算，不引用历史文档的写死数字）

- **档位**：降档模式（`LANES` 4 → **2**，节流闸 50 → **100 分钟**）。
- **cron**：`0 * * * *`（整点，24 触发点/天）⇒ 节流闸 100 分钟决定实际放行批次：**最多 ~14 批/天**。
- **名义吞吐**：2 条/批 × 14 批/天 = **28 个/天**；乘组批模式综合折损系数 ≈ 0.70 ⇒ **实际 ~19 个/天**（诚实区间 14–19）。
- **完成时间**：**不能按这个数字给承诺** —— 本批结束时未开发 **63** 个，其中 **45 个**（卷 13 的 22 + 卷 18 的 23）
  **本批已解除服务闸**，可立即排；剩下 **8 个**仍卡在**蓝图循环依赖**上（`5.1.1.1 ⇄ 5.1.2.1`、`15.3.1.1 ⇄ 15.3.1.3`），
  协议明令调度者不得自行改蓝图依赖 ⇒ **这 8 个需要人工确认蓝图「关系」字段**（已在 backlog 挂 P0）；
  另有 **10 个**是传递依赖（如 `14.3.2.1 ← 14.3.1.3 ← 13.4.1.2 ← 13.4.1.1`），随上游解锁。
- **诚实区间**：45 个可排模块按 ~19 个/天 ⇒ **约 2.5 天**；最后 8 个取决于人工解环时间。**不承诺精确完成日期。**
- ⚠️ 本轮额外损失：**两条车道空转 72 分钟**（worker 04:50 死、06:16 才重派）—— 这是网络事故的代价，不是节奏问题。
- ⚠️ **本轮单 tick 耗时 2 小时 45 分**（06:00 → 08:45），是迄今最长的一轮。原因是 L2 本身变大了：
  **后端 682 个测试类 / 10562 例 + 前端 630 文件 / 8759 例 + Next build** ——
  协议里写的「2829 后端 + 2163 前端」是**过期数字**，实际已涨到 3–4 倍。
  ⇒ **下一批的节流闸阈值必须按新的 L2 体量重新评估**（用户决定，调度者不自行改）。

---

## 十、下一批（batch-94）的预期

1. **`plan --lanes 2` 现在能排到卷 13 / 卷 18 的模块了** —— 两个服务已并入集成头，微服务闸解除：
   可直接排 `13.1.1.2` / `13.1.2.1` / `13.2.1.1` / `13.2.1.2` / `13.2.2.2` / `13.3.1.1` / `13.3.2.1` / `13.3.2.2` /
   `13.4.1.1` / `13.4.2.1` / `13.4.2.2` / `13.5.1.1` / `13.5.2.1` / `13.5.3.1` / `13.6.1.1` 等 15 个 reporting 模块 +
   16 个 platform 模块（`18.1.1.2` / `18.1.1.3` / `18.1.2.1` / `18.1.3.1` / `18.2.1.1` / `18.2.2.1` / `18.2.3.1` /
   `18.3.1.1` / `18.3.3.1` / `18.4.1.1` / `18.4.2.1` / `18.4.2.3` / `18.4.3.1` / `18.4.3.2` / `18.5.1.1` / `18.5.2.1`）。
2. ⚠️ **两条新车道必须遵守 `platform` / `reporting` 的前置**：
   - platform 侧新增动作 ⇒ 必须**同步在 identity 侧补权限码**（`platform.app.%` 的补权语句会带上新码，但 `permission` 表本身仍需补一行）——
     这条是 18.1.1.1 车道留下的运维提示，**新开 platform 车道时必须写进任务书**。
   - reporting 侧：`sales_order` 没有店铺/法人维度 ⇒ **13.1.1.2 多店对比开工前必须先解掉这个上游缺口**（已挂 backlog），
     否则会产出「页面能开、数据恒空」的半成品。
3. ⚠️ **并入顺序仍是硬约束**：任何新车道若也改 `packages/api-contracts`，**必须保证 `index.ts` 的导出行与真实契约文件同时到位** ——
   空壳契约 + 导出行 = TS2306 假红。服务引导车道尤其要注意（见第六节与 backlog）。
4. **蓝图循环依赖仍需人工确认** —— `5.1.1.1 ⇄ 5.1.2.1` 与 `15.3.1.1 ⇄ 15.3.1.3`（backlog 已挂 P0）。
5. **L2 建议串行**：后端 verify 与前端 build/typecheck **不要并跑**（本轮实测并跑把 load 顶到 31、后端吞吐掉到 1/3）。

---

## 十一、风险提示

- **停滞保护计数（重新核算）**：协议触发条件是「连续 **3 轮**『派发了车道但一条都没交付』」。
  逐轮核算：**batch-90 派发 0 条 + 交付 1 条** · **batch-91 派发 1 条 + 交付 0 条** · **batch-92 派发 2 条 + 交付 0 条** ·
  **batch-93 派发 2 条（重派）+ 交付 2 条（本轮）**。
  ⇒ 「派发但零交付」的连续计数**归零**（batch-93 有交付），**停滞保护未触发**。
  ⚠️ batch-92 / batch-93 的零交付根因**不是「做不出来」，而是网络事故** —— 两个 worker 同刻掉线、成果安全保存在 worktree 与 commit 里，重派后全部完成。
- **网络事故是当前最大的产出瓶颈**：72 分钟里两条车道同时空转，成果各自推进到「代码写完、typecheck 跑到一半」的位置。
  这类事故在本仓已出现**两次**（batch-91、batch-93），间隔 4 天。**若再发生，建议人工考虑给 worker 会话加自动重连/续跑机制** —— 调度者无法在 worker 侧解决。
- **心跳不能当唯一的判活依据**：本轮两个 worker 死后仍留下「心跳 72–87 分钟未过期」的假象，直到查会话状态才暴露真相。
  ⇒ `lane.mjs` 的 TTL 90 分钟能兜住（不会误判），但**发现得晚**（上一轮 batch-92 派发时就已经死了，本轮开工查会话才发现）。
  backlog 里已有 `2026-10-21-worker-session-silent-early-exit-lane-slot-idle-60min.md`，本条可作为补充证据。
- **并入顺序靠巧合成立**（第六节）—— 两条解法已在 backlog 待拍板。
- **L2 体量已涨 3–4 倍**（682 后端测试类 / 10562 例 · 630 前端文件 / 8759 例），协议里「2829 + 2163」是过期数字。
  单轮 L2 实测 **107 分钟**（06:54 → 08:41）。**节流闸阈值与 L2 时长的匹配关系需要按新体量重算**（用户决定）。
- **`~/.m2` 被本轮填上了本仓 SNAPSHOT 构件**（续跑需要）。若之后要验证「从干净环境构建」，需先清理。

---

## 十二、本批交付：2 个模块 + 1 处修复（回收并入）

⚠️ 本节是 **06:16 重派之后**的结果：两条车道的第二任 worker 在 06:46–06:54 陆续 `land`，
调度者随即**按第六节的顺序要求并入**（reporting 先、platform 后），本节记录交付内容。

### 12.1 模块 13.1.1.1 经营总览（卷 13「利润与经营分析」L3 13.1.1 第一格）

**这个功能做什么**：让运营在一个页面上看到公司 / 法人 / 店铺维度的核心经营指标 ——
销售额、订单量、销量、毛利额与毛利率、广告花费、净利、退货率、库存总额、应收余额；
支持时间对比与环比同比，可下钻到构成明细，**且「数据更新时间」常驻显示**（用户一眼分清看到的是 T-1 还是准实时）。

**技术落地**：

- **迁移**：`V376__reporting_projection.sql` — **七张表、纯 CREATE TABLE、零 ALTER 既有表、零跨库外键**：
  `reporting_metric_definition`（指标口径唯一）· `reporting_projection_event`（幂等投影账本）·
  `reporting_projection_cursor` + `reporting_projection_epoch`（可重建游标 + 纪元）·
  `reporting_metric_fact`（投影事实）· `reporting_drilldown_fact`（构成明细，可下钻）·
  `reporting_currency_rate_snapshot`（带时点汇率快照）。
  七表全部 `tenant_id NOT NULL` + `ENABLE` **与** `FORCE` RLS + `CREATE POLICY isolation` + `GRANT`。
- **端点**（8 个，reporting 服务）：
  `GET /reporting/overview`（指标卡）· `GET /reporting/overview/capabilities`（能力边界声明）·
  `GET /reporting/overview/metrics` · `GET /reporting/overview/trend` · `GET /reporting/overview/drilldown` ·
  `POST /reporting/projection/events`（投影事件写入）· `POST /reporting/projection/refresh` ·
  `POST /reporting/projection/rebuild`。
- **页面**：`/analytics` — `apps/web/src/app/analytics/page.tsx` +
  `components/analytics/{overview-workspace.tsx,overview-model.ts}`（纯函数模型层，含错误态 / 空态 / 加载态）。
- **测试**：`ReportingOverviewIntegrationTest` **14 例**（含租户隔离三断言）+ `OpenApiContractTest` 1 例 +
  `overview-model.test.ts` **25 例**。
  L1 实测 **15 例全绿**；前端 **25 + 394（api/trade 白名单全量回归）+ 16（api-proxy）** 全绿；
  强制 typecheck（`--force` 绕缓存）**3/3**；codegen 6926 DTO，**其余 7 个服务契约逐字节未变**。

**关键设计决策**：

1. **reporting 是投影服务，不是写模型**（卷 13 红线「不产生业务事实、不做业务写入」）。
   事件 → 投影的管道形状（消费游标 + 幂等 + 可重放）由 worker 设计并落进会话日志。
2. **`/rebuild` 不清账本** —— 账本 append-only，重建靠**纪元 +1**；第二任把端点说明从
   「清账本」改成「账本不清，靠纪元 +1」，**说明与实现不符就是缺陷**。
3. **上游端点逐个核对响应字段形状** —— 字段名对不上会静默变成 `null`。
   第二任发现 `trade` 详情是 `header`/`lines`（不是别的形状），据此确认 13 个上游端点的请求参数与响应字段**逐个对齐**。
4. **汇率不能用「最新值」** —— `ReportingCatalogPort.latestRate` 0 调用点且语义与
   「今天的汇率变了不能让上月的报表变」相悖，**连同 `ReportingUpstreamClient.Ignore` 一起删**（上游端点 12 → 11）。
5. **能力声明不许空列表** —— `capabilities` 逐条列出已读过的上游 + 逐条带原因的 `notDelivered`，并加测试锁住。
6. **timestamptz 必须断言写回的值**（AGENTS.md 硬要求 5）—— 补「+08:00 时刻写回数据库后逐秒相等」的断言，
   **且刻意用非 UTC 时刻**，否则 `Timestamp.valueOf` 那条 −28800s 的 bug 不会触发、测试会假绿。

**第二任修掉的 5 处真实缺陷**（上一任成果**未重写**，只做定点修复）：

| # | 缺陷 | 性质 |
| --- | --- | --- |
| 1 | 三个源码文件嵌着 **U+FFFD 替换字符** | **网络事故打死时中文被逐字节替换**（字节级损坏） |
| 2 | `capabilities` 返回**空列表** | 半成品 |
| 3 | `/rebuild` 说明与实现不符 | 文档撒谎 |
| 4 | 两处死代码（`latestRate` / `Ignore`），其中 `latestRate` 语义亦错 | 死代码 + 语义错 |
| 5 | 缺 timestamptz 写回值断言 | 违反 AGENTS.md 硬要求 5，且会让 bug 假绿 |

上一任心跳声称的三处修复（账本 REVOKE 冲突、epoch 重建机制、DELETE fetch 恒 0）已**逐条复核确认仍在**。

**已知遗留（非本车道可解）**：上游 `sales_order` 表**没有 `shop_id` / `legal_entity_id`**（已核 V99）
⇒ 销售额 / 订单量 / 销量 / 成本 / 退货额 / 广告花费**只落在公司维度**，店铺与法人维度为空。
已如实写进 `capabilities` 的 `notDelivered`。⇒ **13.1.1.2 多店对比在此之前会拿到空的店铺维度。**
⇒ 已挂 backlog（见下）。

### 12.2 模块 18.1.1.1 应用与凭证（卷 18「集成开放与智能自动化」L3 18.1.1 第一格）

**这个功能做什么**：让第三方系统（或内部系统）以「应用」为单位接入 starcon ——
运营注册一个应用、拿到 API Key 与 Secret（**明文只在生成时展示一次**，库里只存摘要）、
圈定它能访问哪些资源与操作、配 IP 白名单与调用配额，密钥泄露时能**一键吊销**。

**技术落地**：

- **迁移**：`V377__platform_app_credential.sql`（497 行，**七张表**：
  `platform_app` / `platform_app_credential` / `platform_app_scope` / `platform_app_ip_allowlist` /
  `platform_app_quota` 等）—— 全部 `tenant_id NOT NULL` + `UNIQUE(tenant_id, …)` 复合键 +
  跨表 `(tenant_id, app_id)` 复合外键 + `ENABLE` **与** `FORCE` RLS + `GRANT`。
  **另加** `V378__platform_app_permission.sql`（identity 侧权限码登记，见 12.3）。
- **端点**：**23 个**，全部挂 `/platform/apps/**` —— 应用注册 / 列表 / 详情 / 概览 / 选项 /
  凭证签发 / 轮换 / 吊销 / 校验、授权范围授予与撤销、IP 白名单增删、配额设置与用量状态、
  审计事件、capabilities 边界声明。
- **页面**：`/integrations` — `apps/web/src/app/integrations/page.tsx` +
  `components/integrations/{app-credential-workspace.tsx,app-credential-model.ts}` +
  `app/api/platform/[...path]/route.ts`。
- **测试**：`PlatformAppCredentialIntegrationTest` **18 例** + `PlatformAppCredentialTenancyIntegrationTest` **8 例**（租户隔离三断言）+
  `OpenApiContractTest` 1 例 + 前端 `app-credential-model.test.ts` / `app-credential-allowlist.test.ts` **18 例**。
  L1 实测 **27 例全绿**；白名单全量回归 **71 文件 / 394 例** + `api-proxy.test.ts` **16 例** 全绿。

**关键设计决策**：

1. **明文 secret 只在生成时返回一次，库里只存摘要** —— 照 identity 的 `BCryptPasswordEncoder` 既有先例。
2. **`platform.app` 一个 page_resource 承载 4 个动作** —— `page_resource` 对应菜单入口不是动作，
   「应用与凭证」是 18.1 的同一个页面；前缀取消费方服务名（`platform`），与 `catalog.spu` / `warehouse.facilities` 的「服务名.页面」口径一致。
3. **下游未交付要如实报** —— 本仓无 API 网关 ⇒ 用量**采集器**缺失；跨租户按 API Key 定位的网关入口未提供。
   `capabilities` 里标 `available=false` 并写明归 18.1.1.3，**不做假实现**。
4. **刻意不改 `packages/ui/src/lib/nav-config.ts`** —— 该共享包不在本车道独占清单内，
   且实测近几批已交付模块在导航配置里**均无条目**，`/integrations` 直接可达；导航登记属独立 IA 事项。

### 12.3 修复：18.1.1.1 的权限码登记（调度者派发的聚焦修复任务）

第一任 worker 发现并如实标注了一个缺口：platform 的 23 个端点全部走 `access.require(bearer, "platform.app.<动作>")`，
但 **identity 服务里没有登记这 4 个码** ⇒ **真实环境一律 403**（集成测试用假 identity 服务，所以测试全绿也没暴露）。
第一任**刻意没自己补**，理由是「Flyway 版本号全局唯一，跨车道乱加会撞号」—— 这个顾虑是对的。

⇒ 调度者派发**聚焦修复任务**（不是新模块），**预分配 V378**（V376 = reporting、V377 = platform 表结构、V378 = 修复）：

- **迁移**：`backend/services/identity/src/main/resources/db/migration/V378__platform_app_permission.sql`（84 行）
  —— 照 identity `V324__platform_feature_flags.sql` 的**三段式**：
  `page_resource` 插 `('platform.app','应用与凭证')` → `permission` 插 4 个动作 → `role_permission` 给 OWNER 补权，
  全部带 `ON CONFLICT DO NOTHING`（迁移幂等）。
- **OWNER 补权用 `LIKE 'platform.app.%'` 扫整个集合**，不是只补 `read` ——
  新租户的 OWNER 由 `create_child` 全量授权，天生有这 4 个码；但**存量租户**的 OWNER 是在这些码登记之前建的，
  只补 `read` 的话 `create/update/revoke` 对既有租户不可见 ⇒ 迁移上线后这些租户会突然「什么都点不动」。
- **测试**：`PlatformAppPermissionIntegrationTest` **8 例全绿**（继承 `IdentityIntegrationTestBase`，实跑 Testcontainers PG16）：
  4 码已登记 + `page_code`/`action` 正确 + `page_resource` 有行 + `GET /identity/permissions` 查得到 +
  **存量 OWNER 补权后拿到全部 4 个码**（核心断言）+ 迁移幂等（跑三遍行数不变）+ 租户隔离三断言。
- **反向验证（关键）**：把补权语句临时改成 `= 'platform.app.read'` 后复跑，核心用例如期红在
  「存量 OWNER 必须拿到全部 4 个码，而不只是 read」上 ⇒ **该断言非空跑**，随后已还原并复跑全绿。

### 12.4 本批并入与顺序校验

| 车道 | 并入 commit | 顺序 |
| --- | --- | --- |
| 13.1.1.1 经营总览 | `b6e3d6d8 merge(13.1.1.1): 经营总览` | **第一** ✅ |
| 18.1.1.1 应用与凭证 | `e85d018a merge(18.1.1.1): 应用与凭证` | 第二 |

⇒ 正是第六节要求的顺序。**worker 自己独立验证了这一点**：它明确写出「对方必须拿到 `030ec20c`（不能只拿 `8c8f7c47`），
否则 `index.ts` 的 `export * from './contracts/reporting'` 仍指向空壳，TS2306 照旧」——
**若按错误顺序并入，集成头就会带着空壳契约变红**。

并入后实测：
- `packages/api-contracts/src/contracts/reporting.ts` = **3551 字节 / 16 个 export type**（真模块）✅
- `packages/api-contracts/src/contracts/platform.ts` = **5032 字节 / 25 个 export type**（真模块）✅
  （platform 第一任也发现并修掉了引导 commit 留下的**第二条**空壳契约 —— 它原本会引发 1 条 TS2306 + 22 条级联报错）
- `packages/api-contracts/src/index.ts` 两条 `export *` 都在 ✅（`export *` 形式不匹配 TS_KEY 正则 ⇒ 走行级兜底并集，已逐行人工确认）
- `sync.mjs --check` **绿**（台账与蓝图一致）；台账 **465 行 / 重复 0**
- 台账「L3 锚点路由」列 13.1.1.1 显示 `—`：该列是 `sync.mjs` **按蓝图重算**的（`route: node.route ?? '—'`），
  13.1.1 的蓝图段没声明路由 ⇒ `—` 是与蓝图一致的正确值，**不是并入丢失**（逐行比对确认：无 route 丢失、无证据截断，状态变化恰为这 2 行）。
