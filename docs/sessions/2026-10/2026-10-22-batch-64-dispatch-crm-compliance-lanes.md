# 2026-10-22 batch-64 —— 派发轮：4 条卷 15 / 卷 16 车道开工

> 批次 `batch-64`，集成头 `integration/module-dev`（HEAD = `17169616`）。
> 本轮 = **零并入 + 派发 4 条车道**（15.1.1.2 / 15.2.1.2 / 15.3.2.2 / 16.1.1.1），
> 车道在后台跑，**下一轮 tick 回收并入 + 跑 L2 + 三件套**。
> 上一批 `batch-63` 是契约并集规范化，集成头与 `origin/integration/module-dev` 同步（0/0）。
>
> **本轮台账变化**：已交付 **316（未变）**、进行中 **0（未变）**、未开发 **149（未变）**。
> 全库 465 个 L4 模块，完成率 **68.0%**（316 / 465），离全部交付还差 **149** 个。
> 台账完整性核对：**465 行**、**重复 ID 0 个**（`grep -cE` + `sort | uniq -d`）。

---

## 本轮闸门判定（全部现取，未引用历史文档数字）

| 闸 | 取值 | 判定 |
| --- | --- | --- |
| 静默窗口闸 | `SILENT_WINDOW=0` → 全天运行，不设时段闸 | 放行 |
| 强度档位 | 当前 `2026-10-22 16:00 +0800` < `TOKEN_HORIZON=2026-10-25T00:00:00+0800` → **full 模式**（LANES=4 / THROTTLE=50） | 满档 |
| 批次节流闸 | `last-batch-at=1792649646` → 距上批 **106 分钟** ≥ 阈值 50 分钟 | 放行 |
| 温度闸 | `pmset -g therm` → `Note: No thermal warning level has been recorded`（另两项亦无记录） | 正常 |
| 配额闸 | 开工时 running = **0** 条 < `RUNNING_QUOTA=6` | 放行 |
| PG 闸 | 带 `org.testcontainers.sessionId` 标签的容器 = **0** < 6；**无标签孤儿容器 0 个** | 放行 |
| load 闸 | 三点采样 `1.27 / 1.31 / 1.68`（1min < 5min < 15min，**连续下降**，远低于 `LOAD_LIMIT=12`） | 放行 |
| 调度闸 | `gate status` → `free / stale=false` → `gate acquire` **第 1 次即命中** | 取得 |

⇒ **容量闸 GO**。节流闸通过**之后**才写 `last-batch-at`（协议硬规定：提前写会把节奏无限后推）。

### 📌 关于本轮 load 与 PG 的读数（口径留档）

本轮 load 1min = **1.27**，是本机空载基线的下半区；PG 容器数 = **0**（连常驻的 `starcon-mig-v2` 都没在跑）。
⇒ 这两条闸本轮**不是**紧箍咒，但也因此**不提供任何余量信息**：
真正会拉高 PG 计数与 load 的是**车道自己的 L1**（每条车道并发 verify = 1 maven JVM + N surefire fork + 1 个 PG 容器 + ryuk），
而那条曲线要到下一轮 tick 开工时才看得到。**下一轮的 PG 闸读数要按「4 条车道刚跑完 L1」来解读，不要按「刚开机」来解读。**

---

## 本批回收：并入车道 —— **0 条**

开工时 `lanes.json` 117 条车道中 **113 条 `merged`、0 条 `ready`、0 条 `running`**。

```
node tools/module-progress/lane.mjs merge-ready
→ { "ok": true, "merged": [], "note": "没有待并入的车道" }
node tools/module-progress/lane.mjs gc
→ { "ok": true, "removed": [] }
```

⇒ 本轮**没有可并入内容**，也没有「并入冲突」要点名。

---

## 本批派发：4 条车道

`node tools/module-progress/lane.mjs plan --lanes 4` 的 `newlyPlanned`：

| 模块 | 名称 | 阶段 | L3 锚点 | 迁移 | 服务 | 分支 | worktree |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 15.1.1.2 | 消息发送 | D7 | 15.1.1 | `V297` | crm | `feat/15-1-1-2` | `.worktrees/15.1.1.2` |
| 15.2.1.2 | 评价回复 | D7 | 15.2.1 | `V298` | crm | `feat/15-2-1-2` | `.worktrees/15.2.1.2` |
| 15.3.2.2 | 接触记录 | D7 | 15.3.2 | `V299` | crm | `feat/15-3-2-2` | `.worktrees/15.3.2.2` |
| 16.1.1.1 | 账号健康总览 | D7 | 16.1.1 | `V300` | channel | `feat/16-1-1-1` | `.worktrees/16.1.1.1` |

4 条车道的 **L3 锚点互不相同**（15.1.1 / 15.2.1 / 15.3.2 / 16.1.1），上游均已交付，
`V297`–`V300` 四个迁移版本号**本批互不重叠**且**基线上均未被占用**（已逐个 `ls` 确认 free）。
三服务归属的判定依据：卷 15 的兄弟模块全部落 **crm** 服务（V99 / V220 / V228 / V292 / V293 / V294 / V295 / V296），
卷 16 的兄弟模块全部落 **channel** 服务（V197 / V247 / V248 / V255）。

### 🚧 服务缺口（`plan` 的 `serviceBlocked` 字段原样带出）

| 卷 | 原因 | 影响 |
| --- | --- | --- |
| 12 | 主责服务 `finance` 不在集成头 | 12 个模块排不出车道 |
| 13 | 主责服务 `reporting` 不在集成头 | 14 个模块排不出车道 |
| 1 | 主责服务「前端聚合」不在集成头 | 20 个模块排不出车道 |

⇒ 这三卷合计 **46 个模块**在本仓**永远排不出车道**，只能等人工决策是否并入对应服务 / 调整锚点。
**调度者不改锚点绕开**，如实留档。

### 📌 本批 api-proxy 锚点分配（**下一轮 tick 不用再指派**）

4 条车道都要改 `apps/web/src/lib/api-proxy.ts`（共享白名单累积正则），**四个锚点已互不重叠地指派**：

| 车道 | 首 token | 插入锚点（dispatch 时行号） |
| --- | --- | --- |
| 15.1.1.2 | `message-sends` | `^message\-inbox...` 那条**之后**（约 5620 行） |
| 15.2.1.2 | `review-replies` | `^reviews(?:...)/` 那条**之后**（约 5793 行） |
| 15.3.2.2 | `customer-contacts` | `^customer\-tags...` 那条**之后**（约 5918 行） |
| 16.1.1.1 | `account-health` | channel 的 `^ad\-rules...` 那条**之后**（约 7503 行） |

四个首 token 在基线全文 192 条正则里出现次数**均为 0**（已逐个 `grep -c` 核实），
前三个落在 `prefix: 'crm/'` 的元素群里，第四个落在 `prefix: 'channel/'` 的元素群里。
⇒ **下一轮并入时 git 能干净求并**，不需要再担心锚点撞车。

### 📌 权限码与表名前缀的分配（避免下一轮发现撞车）

| 车道 | 权限码 | 表名前缀 |
| --- | --- | --- |
| 15.1.1.2 | `crm.message.send`（同族 `.read` 已在既有 grant 里） | `crm_message_send_*`（**不**复用 `crm_buyer_email_send_record`） |
| 15.2.1.2 | `crm.product_review.reply` | `crm_review_reply_*`（**不**复用索评的 `crm_review_send_record` / `crm_review_message_template`） |
| 15.3.2.2 | `crm.contact.read` / `crm.contact.update` | `crm_customer_contact_*` |
| 16.1.1.1 | `channel.account.read` / `channel.account.update` | `account_health_*`（channel 服务内，卷 16 同族均为 `compliance_check_*` / `brand_authz_*` / `brand_cred_*` 风格） |

---

## 静态预检：「已知互相破坏形状」扫描

扫描范围：`backend/services/*/src/main/resources/db/migration/` 全部迁移。

| 检查 | 命中 | 判定 |
| --- | --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | catalog `V14` / `V41` / `V15` | **基线存量**，非本批 |
| ② `SET NOT NULL` | catalog `V14` / `V15`、identity `V7`、channel `V194` / `V196` / `V197` / `V199`（后四者命中的是注释行） | **基线存量**，非本批 |
| ③ `ADD CONSTRAINT ... CHECK` | catalog `V35` / `V41`、channel `V199` | **基线存量**，非本批 |

⇒ **本批三条扫描零命中**：本批 4 个新迁移（V297 / V298 / V299 / V300）**尚未落地**，本轮没有新迁移可扫。
上述命中项全部来自**早于本批、已通过历次 L2 的基线迁移**，不在本轮处置范围。
**下一轮 tick 需对这 4 个新迁移重跑这三条扫描**（重点看 `ADD COLUMN` 无 `DEFAULT` 与 `SET NOT NULL`）。

---

## L2 批量验证：**本轮未运行**

**原因（不是跳过，是本轮没有可验证的批次）**：本轮 `merge-ready` 回收 **0 条**车道，
4 条车道刚在 16:00 派发、尚未 land ⇒ 集成头工作区**与基线完全一致**（`git status --porcelain` 空，
`git diff --stat` 空），**本批没有任何代码变更进入集成头**。

⇒ 对一个**没有代码变更**的集成头跑全量 `mvnw verify` + `pnpm typecheck/lint/test` + Next build，
只会重跑一遍基线（后端 2829 例 + 前端 2163 例 + Next production build），
**把本轮唯一的发热高峰浪费在零信息量上**，而车道此时正在跑各自的 L1 —— 两者并发正是协议点名过的
「成片假红」成因（2026-10-19 20:00 实测：L2 与 4 条车道的 L1 并发时 PG 峰值 16，1364 例里 3 failures + 24 errors **全是假红**）。

⇒ **L2 留到下一轮 tick 跑**，届时 4 条车道已 land、**没有在途车道的 L1 与它抢 Testcontainers**。

---

## 本批车道健康快照（16:00 派发后约 4 分钟）

| 车道 | 状态 | worktree 改动 | commits ahead | 备注 |
| --- | --- | --- | --- | --- |
| 15.1.1.2 | `running`，心跳 `08:00:45Z` | 1 个新文件（`V297__crm_message_send.sql`，**473 行**） | 0 | 已在写迁移 |
| 15.2.1.2 | `running`，心跳 `08:00:50Z` | 0 | 0 | 仍在读蓝图与相邻代码 |
| 15.3.2.2 | `running`，心跳 `08:00:54Z` | 0 | 0 | 同上 |
| 16.1.1.1 | `running`，心跳 `08:00:59Z` | 0 | 0 | 同上 |

⚠️ **心跳已确认写在集成头**（`/Users/guan/Projects/starcon-worktrees/integration/.dev-agent/lanes.json`，
不是各车道 worktree 自己的那份）—— 这是「占位符没替换会让车道被误判崩掉重派」那个坑的**正面证据**：
4 条车道的 `heartbeatAt` 都是 16:00:4x–16:01:0x，与派发时刻吻合。

### 💡 15.1.1.2 的一个设计决策（值得留给下一轮）

`V297__crm_message_send.sql` 把蓝图加粗句「**发送失败要保留原消息状态**」落成：
**「已回复」这个事实只由发送记录推导，不在 `crm_message` 上加列。**
⇒ 失败的那次发送**根本没有能力**把源消息改成「已回复」，也就没有「忘了改回来」这种事故；
同时**天然满足「不 ALTER 既有表」**，避免与同批车道抢既有表的 DDL。

---

## 收尾三件套（本轮 = 派发轮，无代码变更批次）

| 件 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件（`docs/sessions/2026-10/2026-10-22-batch-64-dispatch-crm-compliance-lanes.md`） |
| ② commit | ✅ 与台账同一 commit（台账本轮无变更，`git add` 覆盖之） |
| ③ push | ✅ 推 `origin integration/module-dev` |

⚠️ **本轮没有 L2，因此没有「本批交付模块」的逐模块说明节** —— 4 个模块本轮**尚未交付**，
逐模块说明（迁移 / 端点 / 页面 / 测试 / 关键设计决策）**随 batch-65 一起写**。
本文件的职责是记录**派发轮**的事实：闸门判定、车道分配、锚点分配、静态预检、以及 L2 为何留到下一轮。

---

## 下一轮 tick 可以直接接手的动作

1. `node tools/module-progress/lane.mjs merge-ready` 回收已 land 的车道
   —— 重点看 4 个新迁移是否**零 ALTER 既有表**（本批任务书已逐条要求「纯新建、零 ALTER」）；
2. 跑「已知互相破坏形状」扫描（**本批 4 个新迁移：V297 / V298 / V299 / V300**），
   重点看 ① `ADD COLUMN` 无 `DEFAULT` 与 ② `SET NOT NULL`；
3. 跑**一次** L2 全量（`mvnw verify` + `pnpm typecheck/lint/test` + Next build + `sync --check`）；
4. L2 绿 ⇒ 写 **batch-65** 说明（4 个模块各一节）⇒ commit ⇒ push；
5. L2 红 ⇒ **不 push**，二分定位（最多 2 次全量），派修复任务；
6. **⚠️ 先确认 4 条车道的 L1 都已经结束再跑 L2** —— 否则会拿到成片假红；
7. **服务缺口仍在**：卷 12（finance）/ 卷 13（reporting）/ 卷 1（前端聚合）不在集成头，
   这三卷的 46 个模块会一直排不出车道 —— 需要人工决策是否并入对应服务。

### 📌 契约并入的一个已知雷（batch-63 已部分处理）

三条 crm 车道都会重生成 `packages/api-contracts/src/contracts/crm.ts`（**同一个文件**），
一条 channel 车道重生成 `channel.ts`（**不同文件**）。
crm 三条之间靠并入驱动器的「按 OpenAPI 路径 / 类型名求并」处理 —— **并入后必须核一遍**：
`crm.ts` 里新出现的三组类型**都还在**，且**没有重名 `export type`**。
⚠️ 重名类型不会让任何测试变红（batch-56 抓到过一个真 bug：`AdBudgetGuardrails` 与 14.1.1.1 同名，
SpringDoc 静默丢掉后一个，契约字段串味，而 typecheck / lint / vitest **全绿**）。
⇒ 判据：`grep -oE 'export type [A-Za-z0-9_]+' packages/api-contracts/src/contracts/crm.ts | sort | uniq -d` **须为空**。

---

## 吞吐预期（按 `.dev-agent/PIPELINE.local.md` 当前档位现算）

- **名义吞吐**：`LANES=4` × 每小时放行 1 批（`CRON=0 * * * *` + `THROTTLE_MIN=50` 的 60 分钟栅格留 10 分钟余量）
  = **96 个/天**（派发轮每轮约 5 分钟，节流闸每格都放行）。
- **综合折损**（协议经验系数 ≈ 0.70，含 BLOCKED / 不可调度 / L2 返工 / 长尾占位）⇒ **有效约 67 个/天**。
- **实测校准**：`batch-56`（00:08）到本轮（16:00）的 15.9 小时里，实际交付 **19** 个
  ⇒ 实测约 **1.19 个/小时 ≈ 29 个/天**，**明显低于理论值**（回收轮要跑 L2，会吃掉整格并顺延下一格）。
- **剩余 149 个未开发** ⇒ 按理论口径约 **2.2 天**，按实测口径约 **5.2 天**。
  **诚实区间：约 2–6 天**，随 L2 红绿比例与长尾车道占位浮动。**这是估算，不是承诺，也不是完成日期。**

---

## 附一：三条已落地车道的并入预演（`git merge-tree` 干跑，2026-10-22 16:5x）

本批已有 **3 条车道 land**（`state=ready`）：

| 车道 | head | 迁移 | api-proxy 首 token | 落点核对 |
| --- | --- | --- | --- | --- |
| 15.2.1.2 评价回复 | `598e41c7` | `V298`（crm） | `review-replies` | 落在 `reviews` 与 `review-rules` **之间** = 指定锚点 ✅ |
| 15.3.2.2 接触记录 | `2bd54d3` | `V299`（crm） | `customer-contacts` | 落在 `customer-tags` 之后 = 指定锚点 ✅ |
| 16.1.1.1 账号健康总览 | `31dc6cfd` | `V300`（channel） | `account-health` | 落在 channel 的 `ad-rules` 之后 = 指定锚点 ✅ |

三条车道**同时改**了协议点名的四个求并文件：`packages/api-contracts/src/contracts/crm.ts`、
`packages/api-contracts/src/crm.openapi.json`、`apps/web/src/lib/api-proxy.ts`、
`docs/product/capability-blueprint/module-progress.md`（第三条另加 `channel.ts` / `channel.openapi.json`）。

### 结论（`git merge-tree --write-tree` 干跑：**不改 ref、不改工作区、不产生合并 commit**）

按**下一轮 tick 的真实顺序**逐条合并（base → 15.2.1.2 → 15.3.2.2 → 16.1.1.1），
每步都是一次带并集驱动的 2 路合并，**三步 exit 0，无一步出现 `CONFLICT_KEYS`**。
最终合并树 `e6419d08`。

| 检查项 | 结果 |
| --- | --- |
| 合并是否报冲突 | **否**，三步全 exit 0 |
| `CONFLICT_KEYS` | **三步均未出现** ⇒ 契约按「导出类型名 / paths / schemas 名」求并成功，无同名键冲突 |
| `services[]` 元素数 | base **191** → 合并后 **194**（三条车道各 +1，精确相等） |
| **从 base 掉落的元素** | **0 个** |
| 新增首 token | `crm/:review-replies` · `crm/:customer-contacts` · `channel/:account-health` |
| 台账行 | **465 行**、**重复 ID 0 个**；15.2.1.2 / 15.3.2.2 / 16.1.1.1 **均为「已交付」** |
| 迁移文件 | `V298` / `V299` / `V300` **各 1 份，无重复无缺失** |
| 契约 type 数 | `crm.ts` **159 → 199**；`channel.ts` **1348 → 1365**（+17 = 车道自报值） |
| 契约 path 数 | `crm.openapi.json` **91 → 114**；`channel.openapi.json` **710 → 721**（+11 = 车道自报值） |
| **重名 `export type`** | `crm.ts` **0 个**、`channel.ts` **0 个**（batch-56 抓过的「契约静默串味」形态，本次未复现） |
| 车道纪律 | 三条都**零 ALTER / 零 DROP / 零外键**（`ALTER TABLE … ADD/DROP/ALTER` 计数 0、`REFERENCES` 计数 0） |

⚠️ 干跑 stderr 里出现 `merge-union: 走了行级兜底并集，请人工确认这个文件改对了` ——
这是**台账分支**的固定提示（`merge-union.mjs` 第 374 行），紧跟着的
`台账按模块 ID 求并集，折叠了 465 行同 ID 记录` 证明台账走的是**按模块 ID 的确定性求并**
（证据非占位优先 → 状态 已交付 > 进行中 > 未开发 → 证据更长者优先），**不是内容丢失**。

### 🔎 逐条复核的三处关键纪律

- **V298 / V299 / V300 的 RLS**：V298 对 6 张表、**V299 与 V300 用一条 DO 循环**对各自 3 张表
  逐张 `ENABLE` + `FORCE ROW LEVEL SECURITY` + `CREATE POLICY isolation`（USING 与 WITH CHECK 同表达式）。
  V300 另在 `account_health_metric` / `account_health_assessment` 上加 `data_scope` **RESTRICTIVE** 策略，
  写法与已交付的 V197（16.2.2.1）**逐字同构**（`channel_scope(channel_operation(), s.owner_id, s.department_id)`）；
  `account_health_threshold` 无 `shop_id`、是租户级阈值配置，**故不加店铺维度的 data_scope** —— 这是对的，不是漏。
- **权限码**：三条车道分别用 `crm.product_review.reply` / `crm.contact.read`+`crm.contact.update` / `channel.account.read`+`channel.account.update`，
  与 `AccessService.require` 的「同 grant 内必须同时命中该动作与同族 `.read`」判据兼容。
- **表名前缀不撞车**：`crm_review_reply_*` **不**复用索评的 `crm_review_send_record` / `crm_review_message_template`；
  `crm_message_send_*` **不**复用邮件的 `crm_buyer_email_send_record` —— 两条车道各自另起命名空间。

⇒ **下一轮 tick 可以直接走 `merge-ready`，不必再做并入预演。**

### ⛔ 但仍然**不要**在最后一条车道未齐时跑 L2

15.1.1.2 此刻仍在跑（后端已交付：V297 迁移 + 服务/端点 + 30 个集成测试全绿含租户三断言，正在做契约与前端）。
**L2 不得与在途车道的 L1 并发**（2026-10-19 20:00 实测的成片假红：PG 峰值 16，1364 例里 3 failures + 24 errors **全是假红**）。
⇒ 等 4 条车道全部 land，再一次性 `merge-ready` + **一次** L2 —— 这正是组批模式「每组只验证一次」的本意。

### 🔍 给下一轮 L2 的一条取证线索（重要）

本批开工后，**main→集成头同步任务**在本 worktree 里合入了 `origin/main`：

```
8663273f merge(main): sync from origin/main
```

⇒ 下一轮 L2 跑的那个集成头，**同时包含**「4 条车道的新代码」**和**「这一笔 main 同步」。
按 `PIPELINE.md` §5.6：**若 L2 红，`8663273f` 是跨车道破坏的首要嫌疑对象**
（它的代码没经过本流水线的 L1/L2）。⇒ 二分定位时**先切掉这一笔**再看是不是车道的问题；
若确认是 main 带进来的回归，**不要去修 main 的代码**，如实上报交由同步任务处理。
