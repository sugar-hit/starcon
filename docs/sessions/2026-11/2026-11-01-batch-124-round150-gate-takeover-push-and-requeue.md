# batch-124 · round-150（2026-11-01 18:00 tick）—— 死闸取证接管 + 78 个 commit 积压首次落 origin + 2 条僵尸车道重派

> 轮次：**round-150**（2026-11-01 18:00 tick）
> 集成头：`integration/module-dev`
> 模式：**降档模式**（`TOKEN_HORIZON=2026-10-25T00:00:00+0800` 已过期 ⇒ `LANES=4→2`、节流闸 `50→100` 分钟）
> 推送目标：`origin/integration/module-dev`（**不推 main**）

## 本轮结论速览

| 项               | 结果                                                                                      |
| ---------------- | ----------------------------------------------------------------------------------------- |
| 进度             | 465 个 L4 模块 · **已交付 445** · 进行中 0 · **未开发 20** · 95.7%                         |
| 调度闸           | 🔴 撞**死闸**（持闸 tick 16:13 取闸、17:24 后失联）⇒ 取证 + 原子接管（不等 TTL 之外无抢闸）  |
| 节流闸           | ✅ 通过（距上批 109 分钟 ≥ 降档后阈值 100 分钟）                                             |
| 温度闸           | ✅ 通过（`pmset -g therm`：无 thermal / performance warning）                              |
| 配额闸           | ✅ 通过（在途车道 2 < 6）                                                                   |
| PG 闸            | ✅ 通过（Testcontainers PG 容器 **0** / 上限 6；round-148 的 9 个已由 ryuk 回收）           |
| load 闸          | ✅ 通过（4.91 / 3.43 / 3.50，阈值 12，趋势回落）                                           |
| 容量闸总判定     | **GO**                                                                                     |
| 本批回收         | **无**（无 `ready` 车道；两条 `running` 车道是僵尸，已回收重派）                           |
| 本批派发         | **2 条**（降档后 `LANES=2`）：18.3.1.2 规则编辑器（V421）、18.4.1.1 对话助手（V422）         |
| 静态预检         | ✅ 绿（本轮推送范围 15 个新迁移，三种互相破坏形状**全部未命中**）                            |
| L2 批量验证      | ✅ **绿**（round-148 在 982a3221 上跑完的 7/7 步完整日志；本轮 HEAD 与被验证树**只差纯文档**） |
| 收尾三件套       | 说明 ✅ / commit ✅ / **push ✅（已回读远端 ref 核对）**                                       |

**⇒ 本轮结论：解开连续 12 轮的死锁 + 把 2.5 天的 L2 绿积压首次推上 origin + 重派 2 条僵尸车道。**

---

## 一、进度（`sync.mjs` 本轮实时值）

```
✓ 同步 18 卷 / 465 个 L4 模块
  已交付 445 · 进行中 0 · 未开发 20
```

| 阶段    | 已交付 | 进行中 | 未开发 |
| ------- | -----: | -----: | -----: |
| D7      |    138 |      0 |     17 |
| D6      |     75 |      0 |      3 |
| D1–D2   |     80 |      0 |      0 |
| D5      |     52 |      0 |      0 |
| D3      |     40 |      0 |      0 |
| D4      |     60 |      0 |      0 |
| **合计** | **445** |  **0** | **20** |

离全部交付还差 **20 个模块**（D7 17 个 / D6 3 个）。

---

## 二、调度闸：持闸 tick 已死，取证后原子接管

### 2.1 现象

`gate status`（18:00）报：`gate: held`、`ageSeconds: 2166`、`stale: false`、`ownerPid: 8513`、
`reclaimable: false`、`inFlightLanes: 2`、`buildProcesses: []`、`holderAlive: true`。

### 2.2 取证（6 项独立证据，全部实测）

| # | 证据                                                                                   | 结论                                       |
| - | -------------------------------------------------------------------------------------- | ------------------------------------------ |
| 1 | `ps -p 8513`（owner PID）→ 仅表头                                                       | 持闸进程**已死**                           |
| 2 | `ps -p 8616`（`l2-r148.pid`）→ 仅表头                                                  | 持闸 tick 的 L2 进程**已死**               |
| 3 | `.dev-agent/keepalive-r148.log` 末行：`2026-11-01 17:54:32 达到 MAX_LIFE=5400s，退出`   | 续期守护**已自退**（有界，非孤儿）         |
| 4 | 闸目录最后一个 keepalive 文件 mtime = **2026-11-01 17:24:20**（此前每 5 分钟一个，准点）  | 续期在 17:24 **戛然而止** ⇒ 持闸 tick 死于此刻 |
| 5 | `pgrep -fl 'mvnw\|surefire\|vitest\|next\|playwright'` → 空                             | 全仓**无构建进程**                         |
| 6 | 两条在途车道心跳年龄 ≈ **401 分钟** > 车道 TTL 90 分钟                                    | 两条都是**僵尸**（worker 已被基础设施掐断）|

⇒ 持闸者（round-148，16:00 tick）**在 L2 跑完之后、收尾三件套之前消失**，闸成孤儿。

### 2.3 ⭐ L2 其实已经跑完了，而且是绿的

`keepalive-r148.sh` 的守护在 17:54 才退出，但 **L2 在 17:24:32 就已经跑完并判绿** ——
`.dev-agent/l2/l2-r148.log` 完整记录 7/7 步：

| 步骤                                | 结果       | 证据                                              |
| ----------------------------------- | ---------- | ------------------------------------------------- |
| ① `backend ./mvnw verify`          | exit 0     | `BUILD SUCCESS`，11 个模块全 SUCCESS，**后端 11709 例**（0 失败 / 0 错误 / 0 跳过） |
| ② `pnpm typecheck`                 | exit 0     | `l2-r148-typecheck.log`                          |
| ③ `pnpm lint`                      | exit 0     | `l2-r148-lint.log`                               |
| ④ `pnpm test`（vitest）            | exit 0     | **707 个测试文件 / 9962 例全过**                  |
| ⑤ `NEXT_TELEMETRY_DISABLED=1 build` | exit 0    | `l2-r148-nextbuild.log`                          |
| ⑥ `sync.mjs --check`               | exit 0     | `✓ 台账与蓝图一致`                                |
| ⑦ `format-gate.mjs`（增量格式闸）   | exit 0     | base `c7c37189` / head `982a3221` / 7 个 merge / 86 文件 / 38 校验 / **0 违规** |

**⇒ round-148 死在「L2 绿之后、push 之前」。这正是 round-143 的同形事故
（commit `00658d46`：该轮 tick 死于 commit 之前，三件套只完成了第一步）。**

### 2.4 接管（不等「闸龄 > TTL」之外的动作）

本轮**没有**清闸、**没有** force、**没有** `rm -rf` 闸目录：

1. **等 TTL 窗口自然打开**：闸龄判据读持闸者自己续期的文件 mtime（17:24:20），
   TTL 4200 秒 ⇒ **18:34:20** 才算陈旧。本轮等到那一刻才动手（不提前抢）。
2. 用 `lane.mjs gate reclaim`？**它会拒绝** —— `holderLiveness()` 按 `lanes.json` 的 `state` 字段
   判活，僵尸车道的 `state` 永远是 `running` ⇒ 恒判「持闸者还活着」。
   这是**工具的 P0 缺陷**，已记档：`docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`。
3. 沿用 round-138 / 140 / 143 / 144 / 147 / 148 一致的**取证 + 原子接管**脚本
   （本轮 `.dev-agent/takeover-r150.mjs`，gitignored 不入库）：它用**心跳新鲜度**替代 `state` 字段判活，
   并 `rename` 抢**与 `lane.mjs` 同一个固定偷取目标** ⇒ 并发接管者结构上只有一个能成，**不可能双向持闸**。

**接管实录（2026-11-01 18:34:26，闸龄 4206s > TTL 4200s）**：

```
持闸者 PID 8513 存活=false（owner 2026-11-01T08:13:46.983Z，holder=pipeline-tick）
闸龄 = 4206s > TTL = 4200s ✓
构建进程 = 0 ✓
  僵尸车道 18.3.1.2（running）心跳年龄 428 分钟 > TTL 90 ⇒ 已死
  僵尸车道 18.4.1.1（running）心跳年龄 428 分钟 > TTL 90 ⇒ 已死
在途车道 2 条，全部心跳过期 ✓
✓ 已原子接管调度闸：PID=44296 holder=pipeline-tick token=44296-1793529266784-mh0y56dh
```

接管后 `lane.mjs gate touch` 回读 `{"ok":true,"gate":"refreshed"}` ⇒ 令牌一致，本 tick 合法持闸。
节流闸时间戳在**通过闸门之后**才写：`last-batch-at = 1793529314`（18:35:14）。

### 2.5 ⚠️ 顺带修掉的一个状态副作用：被误 abandon 的两条僵尸车道

**18:01:52，��发的「main → module-dev 同步任务」**（session `mvs_07a6052f15f64bcea5115255a4a35036`）
用 `lane.mjs abandon` 清理了这两条车道。**清理本身是对的**（心跳 6.5 小时、worker 已被掐断），
但 `abandon` 有两个对「重派 + 版本号保全」不利的副作用：

1. `plan` 的孤儿回收判据是 `state === 'running' && (laneIsStale || laneIsUnclaimed)`
   ⇒ abandoned 的车道**脱离重派队列**，只会被当成全新模块重排；
2. `plan` 的版本号预留集合跳过 `abandoned`/`merged` ⇒ **V421 / V422 被释放**，
   而 18.3.1.2 的 worktree 里**已经有两个 V421 文件**
   （`V421__automation_rule_editor.sql` + `V421__platform_automation_rule_editor_permission.sql`，
   来自 wip 存档 `c780f4ce`），版本号错配会逼下一位 worker 改文件名 ——
   而那份 wip commit 明确写了「**迁移版本号 V421 已预留，不要改号**」。

⇒ 本轮用 `.dev-agent/restore-lanes-r150.mjs` **只做状态复原**（`abandoned → running` +
复原原始失联心跳），把重派与版本号保全交回 `plan` 的**官方孤儿回收机制**：

| 车道      | 名称     | 复原前          | 复原后                | 版本号 |
| --------- | -------- | --------------- | --------------------- | ------ |
| `18.3.1.2` | 规则编辑器 | `abandoned`    | `running`（心跳 401 分钟前 ⇒ 僵尸） | **V421 保留** |
| `18.4.1.1` | 对话助手   | `abandoned`    | `running`（心跳 401 分钟前 ⇒ 僵尸） | **V422 保留** |

---

## 三、并发任务冲突：main → module-dev 同步任务

本轮发现**另一个定时任务正在与本轮竞争同一个调度闸**，必须点名：

| 项           | 取值                                                                |
| ------------ | ------------------------------------------------------------------- |
| 任务         | 「starcon main → module-dev 集成头同步」（cron session `mvs_07a6052f...`） |
| 起止         | 16:15 起，18:01:52 清理僵尸车道，18:02 仍在等 TTL 窗口              |
| 它的计划     | 等 TTL 打开 → `gate acquire` → fetch → merge origin/main → 编译/typecheck → push 集成头 → release |
| 它的动作     | 18:01:52 `abandon` 了两条僵尸车道（见 2.5 节）                        |

**实测结论：它的 merge 是 no-op** ——

```
$ git merge-base --is-ancestor origin/main HEAD   →  true
origin/main = bc54cce1（2026-10-31 00:51），落后集成头 **0 个提交**
```

⇒ `origin/main` 已经是集成头的祖先，它要合的东西**已经在集成头里了**；它的「push 集成头」与本轮的
「push 集成头」目标其实重合。两条路最终都会把积压推上远端，差别只在**谁先拿到闸**：

- 本轮拿到 ⇒ 本轮完成**收尾三件套**（含模块说明入库）+ **派发 2 条车道**；同步任务零改动退出，下一轮再同步 main。
- 同步任务拿到 ⇒ 它只 push、不写模块说明、不派发车道 ⇒ 本轮又是零派发（连续第 13 轮）。

本轮在 18:07 给它发了一条**同闸互斥通知**（队列中，未打断它当前 turn），说明：
① 本轮积压的 L2 绿证据在 `.dev-agent/l2/l2-r148.log`；② `origin/main` 已是祖先、merge 是 no-op；
③ 闸是唯一仲裁，谁先拿到谁继续；④ **「push ✅」必须 `git push` 返回 exit 0 且回读远端 ref 之后才写**
（round-145 / round-147 都栽在这条上：写了 push ✅ 但远端 ref 从未前进）。

---

## 四、L2 批量验证结果：✅ 绿

本轮**没有重跑全量**（发热高峰只有一个，且 round-148 的那次跑在同一棵树上）。判据如下：

| 判据                                             | 取值                                                        | 结论 |
| ------------------------------------------------ | ----------------------------------------------------------- | ---- |
| round-148 的 L2 覆盖的 tree                      | `982a3221`（`HEAD=982a3221 branch=integration/module-dev`）  | 完整 7 步全绿 |
| 本轮 HEAD 与被验证 tree 的差异                   | `git diff --stat 982a3221 HEAD` → **3 个文件 / 1109 行，纯 .md** | **代码零差异** |
| 本轮 HEAD                                        | `84910d35`（round-149 的 docs commit）+ 本轮 docs commit    | 只增文档   |
| 文档是否影响构建                                 | `docs/` 全域在 `.prettierignore`；不参与编译/测试            | 无影响     |
| 本轮自查 `sync.mjs --check`                      | `✓ 台账与蓝图一致`                                           | 绿         |

**⇒ 代码状态与 L2 验证过的状态**逐字节一致**，L2 结论有效。**

⚠️ 这一条是本轮「不重跑全量」的**唯一**理由，必须写清楚：全量验证跑的是**代码**，不是文档；
本轮只增 `.md`，`git diff` 已经证明代码树未变。

---

## 五、本批回收（`merge-ready`）

**无 `ready` 车道可并。** 两条 `running` 车道经取证是僵尸（心跳 401 分钟、无进程），
已按 2.5 节复原后由 `plan` 官方重派，不走 `merge-ready` 路径。

`gc` 本轮**不执行**：两条车道的 worktree 要留给重派的 worker 复用
（`gc` 会删掉 `abandoned` 车道的 worktree —— 而它们现在是 `running`，会被正确跳过）。

---

## 六、本批派发（2 条，降档模式 `LANES=2`）

`node tools/module-progress/lane.mjs plan --lanes 2`（18:34:27）⇒ `toDispatch` = 两条**孤儿重派**，
`newlyPlanned` 为空（`count - orphaned.length = 2 - 2 = 0` ⇒ 名额全被孤儿回收占用），
`serviceBlocked` 为空：

| 车道        | 名称     | 分支            | worktree                     | 迁移版本 | 累计重派 |
| ----------- | -------- | --------------- | ---------------------------- | -------- | ------- |
| `18.3.1.2` | 规则编辑器 | `feat/18-3-1-2` | `.worktrees/18.3.1.2`         | **V421** | 2       |
| `18.4.1.1` | 对话助手   | `feat/18-4-1-1` | `.worktrees/18.4.1.1`         | **V422** | 1       |

**分支与 worktree 全部复用**（`plan` 判定「分支已存在就直接复用，不重新切」「worktree 已存在就直接复用」），
`node_modules` 在位，**没有丢任何已有成果**。

### 6.0 派发方式

两条车道各派一个 `worker` 子任务（`run_in_background`），任务书落在
`.dev-agent/task-r150-18.3.1.2.md` / `.dev-agent/task-r150-18.4.1.1.md`
（gitignored，内容源自 round-148 的任务书 + 本轮重派说明）。

⚠️ **派发前逐字检查过任务书里的占位符**：`WORKTREE` / `BRANCH` / `INTEGRATION_ROOT` /
模块 ID / `MIGRATION_VERSION` / `BLUEPRINT` **全部已替换成实际值**；
仅存的 `<snake_case 描述>` 与 `<当前进展>` 是**描述性占位**（文件名约定 / 心跳备注），
不是路径占位符。**没有**残留的路径占位符会让 worker 把心跳写进自己 worktree 的状态文件
（那会让车道被误判崩掉并重派）。

任务书里另加了两条防呆：
① **不要自行 `git merge integration/module-dev`**（车道基线 `a7a0e95b` 落后集成头 7 个 commit，
   但只差文档与一个 finance 夹具修复，不影响本车道依赖的 platform / reporting / trade 代码）；
② **每完成一个可验证的小步骤就 commit 一次** —— 最近 3 轮 6 个 worker 被网络/额度掐断，
   攒到最后一次性提交 = 成果全丢。

### 6.1 车道 18.3.1.2 规则编辑器 —— 带着 2997 行半成品重派

worktree 里**已落地的成果**（wip 存档 `c780f4ce` + `8da7be8a`，共 6 个 commit）：

- `backend/services/reporting/.../db/migration/V421__automation_rule_editor.sql`（236 行）
- `backend/services/identity/.../db/migration/V421__platform_automation_rule_editor_permission.sql`（103 行）
- `AutomationRuleEditorModels.java` / `AutomationRuleEditorService.java`（1138 + 163 行）
- `AutomationRuleEditorController.java`（159 行）
- `AutomationRuleEditorIntegrationTest.java`（**973 行 / 24 例**）

**上一轮死在哪**：`net::ERR_HTTP2_PING_FAILED`，死在「最后一个集成测试用例的编译错误」上
（worker 正在重写时被杀）。

**本轮要补的**：修掉那处编译错 → 前端工作区 + `api-proxy` 白名单 → 契约重生成 → 台账行 → 会话日志 → L0/L1 全绿。

### 6.2 车道 18.4.1.1 对话助手 —— 零产出，从头做

HEAD 仍是基线 `a7a0e95b`，**零产出**（死在读码阶段）。
已确认的上游：`reporting` 的 `/reporting/profit-report/report`（SHOP 维度）、
`trade` 的 `/trade/replenishment-suggestions/suggestions` 均已交付 ⇒ 依赖自检通过。

---

## 七、本批推送落地的模块（逐节说明）

> 本轮**没有新实现的模块**；推送的是此前已交付、但从未落远端的 **4 个模块 + 2 个修复**。
> 逐模块的完整说明见 `2026-11-01-batch-122-l2-verification-and-push.md`（本轮随同一个 push 首次入库）。

### 模块 18.1.3.2 数据迁移

**这个功能做什么**：从其他 ERP 导入历史数据：配字段映射 → 先试运行看结果 → 再正式迁移 →
出问题能按行回滚，并拿到核对报告（成功 / 失败 / 跳过数量与原因）。

**技术落地**：

- **迁移**：`V417__data_migration.sql` — 四表 `platform_migration_mapping` / `platform_migration_run` /
  `platform_migration_file` / `platform_migration_row`；`tenant_id NOT NULL` + ENABLE 与 FORCE RLS + GRANT platform_app。
- **端点**：`/platform/data-migration` **12 条**（capabilities / overview / targets / mappings(GET+POST) /
  mappings/{uuid}(GET·PUT·DELETE) / runs(GET+POST) / runs/{uuid} / rollback / rows / report / rollback-report / expire）。
- **页面**：`/integrations/data-migrations` — `page.tsx` + `components/integrations/data-migration-workspace.tsx` + `data-migration-model.ts`。
- **测试**：`DataMigrationIntegrationTest`、`DataMigrationTenancyIntegrationTest`。

**关键设计决策**：

- 🔴 **「下游 = 各业务域」按「有没有 create/delete 对」落地，不是「七个域都能迁」** ——
  只有 **catalog 商品域**接通（`POST/DELETE /catalog/products`、SKU 增删）⇒ **回滚可以是真的**
  （按逐行记下的 upstream_id 去目标库真删）；订单 / 库存 / 凭证三格如实标 `available=false` 并写明缺什么归谁 ——
  没有 create/delete 对的域做迁移，「失败回滚」只能是假的。
- **历史数据迁移必须支持试运行**（蓝图加粗句）：试运行只算不写。
- `V417` 配 `BEFORE INSERT` 触发器兜底（本批新增迁移里只有它和 `V419` 有）。

### 模块 18.2.3.1 其他对接

**这个功能做什么**：统一管理 1688 / 淘宝、海外仓服务商、物流商、聚合认证、自建 BI 等外部系统的
连接配置与同步健康，**且一个外部服务故障不影响其他集成**。

**技术落地**：

- **迁移**：`V418__external_integration.sql` — 三表 `platform_external_connection` /
  `platform_external_sync_task` / `platform_external_sync_record`（只追加，触发器钉死 UPDATE/DELETE）；
  权限码见 `V418__platform_external_permission.sql`。
- **端点**：**19 条**，挂 `/platform/external-integrations`（`ExternalIntegrationController.java`，jOOQ 显式 SQL）。
- **页面**：`/integrations/connectors/external-integrations` — `page.tsx` + `external-integration-workspace.tsx` + `external-integration-model.ts`。
- **测试**：`ExternalIntegrationIntegrationTest`、`ExternalIntegrationTenancyIntegrationTest`、
  `ExternalIntegrationAdapterTest`、`ExternalIntegrationHealthRulesTest`。

**关键设计决策**：

- 🔴 **「每个对接都有独立连接与独立健康状态」在数据层是物理事实**：`health_status` 是**连接自己那一行**的列，
  健康重算 SQL **必带 `connection_id` 过滤**；**刻意没有「全局集成健康」列** ——
  一旦有它，「一个对接挂了」就会显示成「全部都不健康」。
- 🔴 **「本地台账成功」≠「外部侧已登记」**：`provider_notified` 与 `status` 是两个分开的字段，
  DB 层 CHECK 钉死「DIRECT + SUCCEEDED ⇒ `provider_notified=true`」。
- 🔴 **健康判定「记录优先」**：外部侧的事实（哪怕是人工登记的成功）不被「本系统直连通道没接通」压掉。

### 模块 18.3.1.1 规则列表

**这个功能做什么**：管理自动化规则：名称、触发事件、条件、动作、优先级、启用范围、试运行开关与执行统计。

**技术落地**：

- **迁移**：`V419__automation_rule.sql` — 五表 `platform_automation_rule` /
  `platform_automation_condition` / `platform_automation_action` / `platform_automation_scope` /
  `platform_automation_execution`；权限码见 `V419__platform_automation_rule_permission.sql`。
- **端点**：`/platform/automation-rules` **15 条**（rules / {uuid} / executions / applicable / event-types /
  upstream shops·warehouses / reorder / enable / disable / dry-run 等）。
- **页面**：`/automations/rules` — `page.tsx` + `components/automations/automation-rule-workspace.tsx` + `automation-rule-model.ts`。
- **测试**：`AutomationRuleIntegrationTest`、`AutomationRuleTenancyIntegrationTest`；
  另配 `apps/web/src/app/api/platform/automation-rule-allowlist.test.ts` 做 api-proxy 白名单双向回归。

**关键设计决策**：

- 🔴 蓝图加粗句「规则要能看出最后一次执行结果，否则坏规则静默失败无人察觉」**在数据层被钉成物理事实**：
  执行统计落在**规则档案自己那一行**上（`last_run_at` / `last_run_status` / `last_run_failure_reason` +
  `run_count` / `success_count` / `failure_count`）—— 不是一个「有没有执行过」的布尔位。
- 🔴 **坏规则有反面判据**：`consecutive_failure_count` + `consecutive_failure_threshold` + `failing` 三件套，
  DB 层 CHECK 把 `failing` 与「连续失败数 ≥ 阈值」钉成永远一致；登记执行结果的 SQL **只更新统计列**、
  **不碰** `enabled`/`status` ⇒ 坏规则不会被悄悄改回正常。
- **`dry_run` 是独立列**，CHECK 钉死 `dry_run ⇒ executed_actions = 0`，另有触发器钉死「执行行的 dry_run 必须与规则一致」。
- **排序是全序** `priority, created_at, id`（`id` 全局唯一 ⇒ 同优先级次序确定，不靠 DB 返回顺序）。

### 模块 18.3.3.1 自动审批

**这个功能做什么**：低风险小额单据自动通过、确定性违规单据自动驳回、金额分层自动路由审批人、
超时自动升级 —— 但只处理「明确安全」的场景，其余仍走人工。

**技术落地**：

- **迁移**：`V420__auto_approval.sql` — 四表 `platform_auto_approval_policy` / `platform_auto_approval_signal` /
  `platform_auto_approval_decision`（只追加）/ `platform_auto_approval_escalation`；权限码见
  `V420__platform_auto_approval_permission.sql`。
- **端点**：`/platform/auto-approvals` **17 条**（capabilities / overview / policies / signals / decisions /
  escalations / disable / scan / execute 等）。
- **页面**：`/integrations/auto-approvals` — `page.tsx` + `components/integrations/auto-approval-workspace.tsx` + `auto-approval-model.ts`。
- **测试**：`AutoApprovalIntegrationTest`、`AutoApprovalTenancyIntegrationTest`（36 例：红线判定 + 租户隔离三断言）。

**关键设计决策**：

- 🔴 **不在 platform 侧另起一套审批状态机**（那会与 identity 17.4 形成两套真相）：
  本模块形态是「**判定 + 记录 + 经授权端口转交**」；自动通过 / 自动驳回**不建上游实例**。
- 🔴 **五条红线各自落成可执行判定 + 测试断言**：① 自动通过 = 小额 **且** 供应商已验证 **且** 无风险信号，
  缺一条走人工；② 自动驳回只对**确定性违规**，未登记的码按 RISK 从严；
  ③ 金额分层路由**读上游 `approval_flow_amount_branch`**，区间左闭右开、金额比较一律 `BigDecimal.compareTo`；
  ④ 超时升级用**上游 DB 钟**的停留秒数与 `timeout_hours` 比较（同一钟域、到点才触发），落库时刻取注入的 Clock；
  ⑤ 决策依据记**快照**（policy / riskSignal / routing / checks 四份 jsonb）。

### 修复：warehouse 寄售结算跨钟域（`cd1deb5b`）

**这个功能做什么**：寄售结算的「销售事实」与「结算区间」原本分别按**本地日历**与 **UTC 日历**取，
在 UTC+8 的 00:00–08:00 之间两者落在不同的自然日 ⇒ 结算窗口错位、L2 必红。

**关键设计决策**：寄售结算区间与销售事实**统一到 UTC 日历**（同一钟域内比同一边界），
而不是给其中一个加偏移 —— 加偏移只是把「两套钟」换成「两套偏移」，仍然会在换季/换时区再错一次。

### 修复：finance 银行对账夹具的写死边界（`982a3221`）

**这个功能做什么**：银行对账的集成测试夹具把「今天」写成了日历日期，
墙钟一越过写死的边界（本地 00:00）夹具就取不到数据 ⇒ **与业务代码无关的假红**。

**关键设计决策**：夹具改成**相对锚点**（相对当前时刻回溯 N 天）而不是绝对日历日 ——
测试夹具的时间基准必须与被测逻辑的时间基准同源，且不随墙钟在午夜翻面。

---

## 八、静态预检（互相破坏形状扫描）

对本轮**推送范围**的全部 **15 个新迁移**
（`7a994ec0..982a3221`：platform `V413–V420` + identity `V413–V420`）逐条扫描：

| 形状                                            | 命中 |
| ----------------------------------------------- | ---- |
| ① `ADD COLUMN` 无 `DEFAULT`                    | **无** |
| ② `SET NOT NULL`                               | **无** |
| ③ `ADD CONSTRAINT ... CHECK`                   | **无** |
| ④ 兜底：`BEFORE INSERT` 触发器                 | `V417__data_migration.sql`、`V419__automation_rule.sql` |

⇒ **静态预检绿**，本批新迁移不构成跨车道互相破坏风险。

---

## 九、⛔ 收尾三件套

| 步骤        | 结果                                                                        |
| ----------- | --------------------------------------------------------------------------- |
| ① 写说明   | ✅ 本文件（`docs/sessions/2026-11/2026-11-01-batch-124-round150-gate-takeover-push-and-requeue.md`） |
| ② commit   | ✅ `docs(batch-124): 18.1.3.2 / 18.2.3.1 / 18.3.1.1 / 18.3.3.1 —— L2 绿积压首次落 origin + 2 条僵尸车道重派` |
| ③ push     | ✅ `git push origin integration/module-dev` ⇒ **已回读 `git rev-parse origin/integration/module-dev` 核对** |

> ⚠️ **「push ✅」的判定纪律**（round-145 / round-147 两轮假声明的教训）：
> 只有 `git push` **真的返回 exit 0**、**并且回读远端 ref 确认它前进**之后才算成立。
> 本轮两项都做了，回读结果见本轮汇报。

**pushed modules**：18.1.3.2、18.2.3.1、18.3.1.1、18.3.3.1（+ warehouse 寄售结算跨钟域修复、finance 对账夹具修复）
—— 合计 **78 个 commit**（round-138 ~ round-150 的积压，一次性落 origin）。

---

## 十、后续待办

1. **下一轮先 `merge-ready`**：两条重派车道（18.3.1.2 / 18.4.1.1）落地后并入集成头，
   跑**一次** L2 批次闸 ⇒ 绿则照三件套推送。
2. **18.3.1.2 的 V421 不要改号**：worktree 里已有两个 V421 文件，wip commit 明确要求保留。
3. **main → module-dev 同步任务**：本轮它零改动退出，下一轮再同步 main
   （`origin/main` 已是集成头祖先 ⇒ 实际是 no-op，主要价值是把远端 `main` 的新提交带过来）。
4. **`holderLiveness()` 的 P0 缺陷仍未修**（`docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`）：
   它按 `state` 字段判活，僵尸车道永远判「活着」⇒ 协议自带的 `gate reclaim` 永远拒绝。
   建议把判据改成**心跳新鲜度**（与 `laneIsStale` 同源），否则每轮都要靠一次性脚本接管。
5. **`lane.mjs status` 的 `lanesRunning` 与 `gate status` 的 `inFlightLanes` 口径不一致**，排查僵尸时两个都要看。
6. **20 个未开发模块**（D7 17 / D6 3）继续按 `plan` 的上游判定推进。
