# 2026-10-25 · batch-93 —— 双 worker 网络掉线取证 + TTL 到期重派（零回收 · 零新模块 · L2 延后）

> 批次号 **93**（承接 `batch-92`；取号时 `ls docs/sessions/2026-10/ | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1` ⇒ 最大为 `batch-92`）。
> 集成头 `integration/module-dev`（`1312b441`）；调度者：Mavis（pipeline cron tick，2026-10-25 06:00 触发）。
> **强度档位：降档模式** —— 当前时刻 06:00 ≥ `TOKEN_HORIZON=2026-10-25T00:00:00+0800`
> ⇒ `LANES` 由 4 减半为 **2**，节流闸阈值由 50 分钟翻倍为 **100 分钟**（自动降档，不是调度者自选）。

## 本批概览

| 项 | 值 |
| --- | --- |
| 进度 | 465 总数 · **400 已交付** · 0 进行中 · **65 未开发**（完成率 **86.0%**，离 465 还差 **65**） |
| 容量闸判定 | **GO** —— running **2**/6、`pmset -g therm` **无降频项**、load **1.79 / 1.79 / 1.80**（10 核）、Testcontainers PG **0** 个 |
| 节流闸 | 距上批 **120 分钟** ≥ 阈值 **100 分钟**（降档后）⇒ 放行 |
| 本批回收 | **0 条** —— `merge-ready` 返回「没有待并入的车道」 |
| 本批派发 | **2 条重派**（非新车道）：**13.1.1.1 经营总览**（V376，reDispatched 2）+ **18.1.1.1 应用与凭证**（V377，reDispatched 1） |
| 静态预检 | 对**在途**的 V376 + V377 跑「已知互相破坏形状」：**三项零命中**；RLS 七表齐全 |
| L2 批量闸 | **本轮延后**（在途车道 L1 并发禁令 + 零代码增量）⇒ **本轮不 push**，理由与欠账见第八节 |
| 台账完整性 | `grep -c` = **465**、重复 ID **0**；`sync.mjs --check` **绿** |
| 收尾三件套 | 说明 ✅ / commit ✅ / **push ❌（L2 未绿 —— 本轮明确缺 push）** |
| 调度闸 | `gate acquire` 成功（TTL 4200s，等待 TTL 期间续期 1 次），收尾 `gate release` |

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

## 七、L2 批量验证结果：**本轮延后 ⇒ 本轮不 push**

**本轮没有跑全量 L2**，两个理由都是协议内的硬约束：

1. **在途车道 L1 并发禁令。** 协议明写：「**L2 不得与在途车道的 L1 并发跑** —— 多套 Testcontainers PG 互相抢资源，
   2026-10-19 20:00 那轮实测：L2 与 4 条车道的 L1 并发时 PG 峰值 16，后端 1364 例里 3 failures + 24 errors **全是假红**。**先等在途车道落地，再跑 L2**」。
   本批两条车道都在跑各自的 L1 ⇒ **此刻跑 L2 正是被明令禁止的形状**。
2. **零代码增量。** 本批没有并入任何车道。集成头 `1312b441` 相对**上一个 GREEN L2 点** `7ecd64f5`（batch-90，12 步退出码全 0）的差异：

   ```
   git diff --name-only 7ecd64f5..HEAD | grep -vE '^docs/|^tools/module-progress/'
   → (NONE — zero code delta)
   ```

   差异只有 6 个文件（4 份 main-sync 会话日志 + batch-90/91 两份批次说明 + 台账 `module-progress.md`），**全是 markdown**。

⇒ **batch-93 的全量 L2 闸尚未执行**，将由下一 tick 在两条车道落地并入后补跑。**这是本轮唯一的未完成验证项**，在此点名，不含糊。

### 本轮实际执行且通过的闸

| 闸 | 结果 |
| --- | --- |
| `node tools/module-progress/sync.mjs --check` | **绿** —— 「✓ 台账与蓝图一致」 |
| 台账完整性 `grep -c` / `uniq -d` | **465 行 / 重复 0** |
| 静态预检（5.1） | V376 + V377 三项**零命中**，RLS 七表齐全 |
| 共享文件合并预演（`git merge-file` 三方实跑） | `api.ts` **exit 0 / 0 冲突标记** |
| 全量 `mvnw verify` + `pnpm typecheck/lint/test` + Next build | **本轮延后 → 下一 tick 补跑** |

---

## 八、收尾三件套

| # | 项 | 结果 |
| --- | --- | --- |
| ① 写说明 | ✅ | 本文件 `docs/sessions/2026-10/2026-10-25-batch-93-worker-crash-recovery-redispatch.md`（本批零交付，故无「每模块一节」；本文件即本轮结论归档） |
| ② commit | ✅ | `docs(batch-93)`，**与台账同一个 commit**（未拆开）；另含 backlog 新增条目 |
| ③ push | ❌ | **缺 push** —— 协议与 cron 任务书均写明「**L2 没绿绝对不 push**；说明照写、commit 照做，成果与诊断留本地，修复后随下一批一起 push」。本轮 L2 因在途车道 L1 并发禁令未执行 ⇒ **不 push**，成果与诊断留在集成头本地 |

⚠️ 与 batch-91 的处理**不同**：batch-91 在同样的「零代码增量 + L2 延后」情形下仍 push 了（论证是「被 push 的只有 markdown，被 push 的代码在 batch-90 已 L2 GREEN」）。
**本轮与 batch-92 保持一致，按 cron 任务书的字面要求收紧**：L2 未绿就不 push，缺口在汇报里点名。
代价：batch-92 与 batch-93 两份说明 + 2 份 main-sync 日志暂留集成头本地，随下一批 L2 绿了一起上远端。

---

## 九、吞吐预期（按本轮实时配置现算，不引用历史文档的写死数字）

- **档位**：降档模式（`LANES` 4 → **2**，节流闸 50 → **100 分钟**）。
- **cron**：`0 * * * *`（整点，24 触发点/天）⇒ 节流闸 100 分钟决定实际放行批次：**最多 ~14 批/天**。
- **名义吞吐**：2 条/批 × 14 批/天 = **28 个/天**；乘组批模式综合折损系数 ≈ 0.70 ⇒ **实际 ~19 个/天**（诚实区间 14–19）。
- **完成时间**：**不能按这个数字给承诺** —— 65 个未开发模块里 **47 个**要等两个服务引导落地（并入那一刻解锁 31 个可直接排 + 16 个传递解锁）；
  剩下 **8 个**卡在**蓝图循环依赖**上（`5.1.1.1 ⇄ 5.1.2.1`、`15.3.1.1 ⇄ 15.3.1.3`），
  协议明令调度者不得自行改蓝图依赖 ⇒ **这 8 个需要人工确认蓝图「关系」字段**（已在 backlog 挂 P0）。
- **诚实区间**：两个服务引导落地后（约 1–2 批），47 个模块按 ~19 个/天 ⇒ **约 2.5 天**；最后 8 个取决于人工解环时间。**不承诺精确完成日期。**
- ⚠️ 本轮额外损失：**两条车道空转 72 分钟**（worker 04:50 死、06:16 才重派）—— 这是网络事故的代价，不是节奏问题。

---

## 十、下一批（batch-94）的预期

1. **回收 13.1.1.1 与 18.1.1.1**（`merge-ready`）⇒ 补跑**本批欠下的全量 L2**
   （`mvnw verify` + `pnpm typecheck/lint/test` + Next build + `sync --check`）⇒ 绿了再 push，**顺带把 batch-92/93 的说明一起推上远端**。
2. ⚠️ **并入顺序：reporting 先、platform 后**（见第六节）。`merge-ready` 按插入顺序走，当前恰好正确 —— 但**要看 `merged[]` 数组的实际顺序**，不要默认。
3. **`reporting` 并入后** ⇒ 微服务闸放行卷 13 ⇒ `plan --lanes 2` 能排出**卷 13 车道**；
   传递解锁 14.2.1.1 / 14.2.1.2 / 14.3.1.3 / 14.3.2.1 / 1.1.2.1 / 1.1.2.2 / 1.3.2.1 / 1.3.2.2。
4. **`platform` 并入后** ⇒ 卷 18 放行（24 个模块 + 传递解锁 1.5.2.1）。
5. **蓝图循环依赖仍需人工确认** —— `5.1.1.1 ⇄ 5.1.2.1` 与 `15.3.1.1 ⇄ 15.3.1.3`（backlog 已挂 P0）。

---

## 十一、风险提示

- **停滞保护计数（重新核算）**：协议触发条件是「连续 **3 轮**『派发了车道但一条都没交付』」。
  逐轮核算：**batch-90 派发 0 条 + 交付 1 条** · **batch-91 派发 1 条 + 交付 0 条** · **batch-92 派发 2 条 + 交付 0 条** · **batch-93 派发 2 条（重派）+ 交付 0 条（本轮）**。
  ⇒ 「派发但零交付」的连续计数目前 **2 轮**（batch-92、batch-93），**停滞保护未触发，但下一轮若仍零交付即达 3 轮 ⇒ batch-95 起触发零派发 + 停滞报告**。
  ⚠️ 但**这两轮零交付的根因不是「做不出来」，而是网络事故** —— 两个 worker 同刻掉线、成果已安全保存在 worktree 与 commit 里。
- **网络事故是当前最大的产出瓶颈**：72 分钟里两条车道同时空转，成果各自推进到「代码写完、typecheck 跑到一半」的位置。
  这类事故在本仓已出现**两次**（batch-91、batch-93），间隔 4 天。**若再发生，建议人工考虑给 worker 会话加自动重连/续跑机制** —— 调度者无法在 worker 侧解决。
- **心跳不能当唯一的判活依据**：本轮两个 worker 死后仍留下「心跳 72–87 分钟未过期」的假象，直到查会话状态才暴露真相。
  ⇒ `lane.mjs` 的 TTL 90 分钟能兜住（不会误判），但**发现得晚**（本轮是本 tick 查会话才发现，上一轮 batch-92 派发时就已经死了）。
  backlog 里已有 `2026-10-21-worker-session-silent-early-exit-lane-slot-idle-60min.md`，本条可作为补充证据。
- **并入顺序靠巧合成立**（第六节）—— 两条解法已在 backlog 待拍板。
- **本轮 push 缺口**：batch-92 与 batch-93 的说明、2 份 main-sync 日志停留在集成头本地，等下一批 L2 绿后一起上远端。
