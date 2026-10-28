# 2026-10-28: 并行车道第 122 轮 —— 降档派发 2 条车道 + identity L2 红点取证

## 摘要

本轮是**降档模式**（已过 `TOKEN_HORIZON=2026-10-25T00:00:00+0800`）下的组批派发轮：
台账 422/465 已交付，本轮**无车道可回收**，通过全部容量闸后派发 **2 条**互不依赖的车道
（13.4.1.1 多模式利润报表 / 13.4.2.1 成本结构），二者落在同一个 `reporting` 服务但 **L3 锚点不同**、
迁移版本号预分配为 **V398 / V399**。

本轮没有可验证的新合并批次（L2 未跑），但**做了一件此前几轮都没做的事：把集成头上那条
长期未定位的 `identity` L2 红点取证到「跨测试状态污染」这一层**，并落成 backlog P0。
它是当前阻塞全部 push 的**唯一**红点。

## 档位与闸门判定

| 项                   | 取值                                       | 判定                       |
| -------------------- | ------------------------------------------ | -------------------------- |
| 强度档位             | `TOKEN_HORIZON` 已过 → **reduced**         | `LANES=4/2=2`、`节流闸=100` |
| 静默窗口             | `SILENT_WINDOW=0`（全天运行）              | 未命中                     |
| 批次节流闸           | 距上批 **132 分钟** ≥ 阈值 **100**          | ✅ 放行（通过后才写时间戳） |
| 温度闸 `pmset -g therm` | `No thermal warning level has been recorded` | ✅ 通过（无降频项）   |
| 配额闸               | running **0** / 配额 **6**                  | ✅ 通过                     |
| PG 闸                | 带 `org.testcontainers.sessionId` 标签 **0** / 上限 6 | ✅ 通过（无孤儿容器） |
| load 闸              | 1min **3.33** / 5min 2.33 / 15min 3.15，阈值 12 | ✅ 通过（后一度因本机自跑隔离复跑冲到 12.03，30 秒内回落至 7.59） |
| 调度闸               | `gate=free` → `acquire` 成功（TTL 4200s）    | ✅ 独占整轮                 |

## 进度（`sync.mjs` 实时输出，非手工计数）

- 总数 **465** · 已交付 **422**（90.8%）· 进行中 **0** · 未开发 **43**
- 未开发分布：**D7 40 个** + **D6 3 个**（`5.1.1.1` 价目表列表 / `5.1.1.2` 价目项批量维护 / `5.1.2.1` 优先级与冲突检测）
- 台账完整性：`grep -c` = **465**，模块 ID 无重复
- 缺口集中在卷 18（接口/开放平台 16 个）、卷 15（CRM 客户 5 个）、卷 13（利润分析 11 个）、卷 14（广告 3 个）、卷 5（定价 3 个）

## 本轮回收

`lane.mjs merge-ready` → `{"ok": true, "merged": [], "note": "没有待并入的车道"}`；
`lane.mjs gc` → `{"removed": []}`。工作区干净，无并入冲突。

## 本轮派发（2 条，reduced 档）

| 模块 ID   | 名称         | 阶段 | L3 锚点 | 分支              | 迁移版本 | 归属服务    |
| --------- | ------------ | ---- | ------- | ----------------- | -------- | ----------- |
| 13.4.1.1  | 多模式利润报表 | D7 | 13.4.1  | `feat/13-4-1-1`  | **V398** | `reporting` |
| 13.4.2.1  | 成本结构     | D7 | 13.4.2  | `feat/13-4-2-1`  | **V399** | `reporting` |

两条车道的**上游已交付**（派发前逐条核对台账）：`2.3.2.1` 经营模式、`12.4.2.1` 费用归集、
`12.4.2.2` 分摊规则、`12.3.1.2` 交易明细、`5.2.1.3` 成本分摊、`5.2.3.1` 平台费用、
`5.2.3.2` 物流与头程费用、`13.4.2.2` 费用差异（同 L3 兄弟，已交付，是 13.4.2.1 的直接参照）。

**迁移版本号无冲突**：集成头 `reporting` 已用到 V397，V398/V399 为空位；全仓 grep 无同名迁移文件。
**worktree 无冲突**：两条 worktree 均新建于基线 `22507bf8`，初始工作区干净。

## L2 批量验证：**本轮未跑**（两条独立理由）

1. **本批没有可验证的合并** —— 本轮派发的两条车道尚在开发中，集成头内容未变；
   L2 的职责是「抓跨车道互相破坏」，没有新合并就没有新风险面。
2. **在途车道的 L1 与 L2 并发 = 协议明令禁止** —— `PIPELINE.md` 记录的 2026-10-19 实测：
   L2 与 4 条车道 L1 并发时 PG 峰值 16，后端 1364 例里 3 failures + 24 errors **全是假红**
   （`seed()` 503 / 连接拒绝）。本轮有 2 条车道在途 ⇒ 不满足跑 L2 的前置条件。

⇒ 结论：**「L2 没绿」不是本轮的红，是本轮没跑**；集成头的红是下面那条**已定位但未修**的
`identity` 红点。

## 🔴 本轮主要产出：`identity` L2 红点取证（跨测试状态污染）

集成头的 L2 红点长期未定位（`.tmp/l2-batch107b.status` 与 `.tmp/l2-batch108.status` 都是
`backend_verify=1`），它阻塞了**全部 104 个未推送提交**的发布。本轮做了四步取证，把它从
「不明红点」推到「可派修复任务的形状」。

### 1. 失败签名：**403，不是 503**

```
Tests run: 639, Failures: 38, Errors: 0, Skipped: 0   (identity 服务)
  34 × expected:<200> but was:<403>
   1 × expected:<400> but was:<403>
```

⇒ **0 errors、34 条断言失败、全部是 403（权限不足）**。
这**不是** 10-19 那种「`seed()` 503 / 连接拒绝」的负载型假红形状 —— 是**确定性鉴权失败**。

失败类：`ApprovalTodoIntegrationTest`(11) · `ApprovalInboxIntegrationTest`(6) ·
`ShortcutTemplateIntegrationTest`(6) · `ApprovalInboxTenancyIntegrationTest`(4) ·
`ApprovalRecordIntegrationTest`(3) · `ApprovalRecordTenancyIntegrationTest`(3) ·
`DepartmentLeaderIntegrationTest`(2) · `AlertRuleIntegrationTest`(2) · `GlobalSearchRecentIntegrationTest`(1)。

### 2. 排除「机器负载」：batch108 是**空载隔离复跑**

`.tmp/l2-batch108.runner.log` 记录：`run-before labeled PG containers: 0`、`run-before load: 1.75 1.87 1.76`
（16:05:58 起，空机器）⇒ **仍然红**。⇒ 与机器负载无关。

### 3. 排除「生产代码回归」：identity 生产代码没动

```
git log e6f443ae..HEAD -- backend/services/identity/   →  仅 1 个提交：
  32a16a6a test(identity): 审批待办超时夹具改用 DB 时钟，消除跨时钟取整抖动
git log e6f443ae..HEAD -- backend/libraries/           →  0 个提交
```

`32a16a6a` 是**测试夹具**提交（改的是 `ApprovalTodoIntegrationTest` 的时间基准），
且它修的正是**另一种**失败形状（7199 vs 7200 的取整抖动），与现在的 403 无关。

### 4. 定位到「跨测试状态污染」：**单类隔离跑 12/12 全绿**

本轮实测（空载、0 个 PG 容器）：

```
./mvnw -pl services/identity -am test -Dtest='ApprovalTodoIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
→ Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 （37.18s，BUILD SUCCESS）
```

⇒ **同一个类，单独跑 12/12 绿；在全套 639 例里 11/12 红。**
⇒ 结论：**红点不在这个测试类自己身上，而在「谁在它之前污染了共享状态」**。
最可能的机制：同一 Testcontainers 容器 + 同一租户下，另一个 identity 测试类
**写了 / 删了 / 覆盖了权限授予（role / permission / member 绑定）**，导致后续类里的
actor 拿不到授权 ⇒ 403。

旁证：`ApprovalInboxIntegrationTest` 在 batch108 耗时 **522.0s**（batch107b 只用 11.59s）
⇒ 存在**等待/重试**，符合「共享行被锁住或状态被改写后要绕路」的形态。

### 已排除的方向（不要再重复查）

- ❌ 机器负载 / PG 容器抢资源（空载隔离复跑仍红）
- ❌ identity 生产代码回归（生产代码自 `e6f443ae` 起 0 改动）
- ❌ `ApprovalTodoIntegrationTest` 自身的夹具（隔离跑 12/12 绿）
- ❌ 共享库 `backend/libraries/` 变更（0 提交）

### 下一步（已落 backlog P0）

派一条**修复车道**（非新蓝图模块），目标：在**空载**下复现「identity 全套 639 例」
⇒ 用 surefire 执行顺序二分出污染源测试类 ⇒ 修掉碰撞（大概率是测试夹具用了
**固定租户 / 固定 actor / 固定权限码**这类跨类共享标识）⇒ 重跑全套绿。
详见 `docs/backlog/2026-10/2026-10-28-identity-l2-red-403-cross-test-pollution.md`。

## 静态预检（互相破坏形状扫描）

本批两条车道**尚未产生迁移文件**（还在开发中），扫描结果：

- ① `ADD COLUMN` 无 `DEFAULT`：**113**（历史存量，本批未新增）
- ② `SET NOT NULL`：**42**（历史存量，本批未新增）
- ③ `ADD CONSTRAINT…CHECK`：**28**（历史存量，本批未新增）

⇒ 本批**新增表、零 ALTER 既有表**，协议点名的两种互相破坏形状**不可能发生**。
两条车道各自独占一张新表（V398 / V399），纯 `CREATE TABLE`。

## 收尾三件套（本轮逐件点名）

| 件       | 状态      | 说明                                                                                                                                                    |
| -------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| ① 写说明 | ✅ 完成   | 本文件（round 122）+ backlog P0（identity 红点）。本轮**无模块交付**，故不写 `batch-<n>` 交付说明（沿用仓库既有 `pipeline-round<n>` 惯例，见 round-118 / round-121） |
| ② commit | ✅ 完成   | 说明文件 + backlog + 台账**同一个 commit**（台账本轮净改动 = 0，422/43 维持不变）                                                                     |
| ③ push   | ❌ **不做** | **两条独立理由，任一条都足够**：① **集成头 L2 是红的**（identity 38 failures，已定位为跨测试污染，未修）⇒ 按「L2 没绿绝对不 push」不推；② **push 会被 non-fast-forward 拒**（远端多 1 个提交 `94192b24`，本地多 104 个提交 ⇒ 双向分叉） |

### 为什么本轮不推

1. **L2 红**：`identity` 639 例 / 38 failures。`PIPELINE.md`「L2 没绿绝对不 push」是最高优先级禁令。
   `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md` 也要求
   「**必须在 L2 绿的窗口**把远端那 1 个提交合回本地再 push」。
2. **push 会被直接拒**：远端 tip `94192b24`（纯文档，第 107 轮会话语录）不在本地 104 个提交里。
   可解性已实测：`git merge-tree --write-tree HEAD origin/integration/module-dev` ⇒ **rc=0、无冲突**，
   但那是**发布侧在 L2 绿窗口**做的动作，本轮 L2 没跑 ⇒ 不做。**禁止 force-push**（会丢远端那 1 个提交）。

⇒ **104 个提交（含 13.3.1.1 / 13.3.1.2 / 13.3.2.1 / 13.3.2.2 等已交付模块的实现）继续留在集成头本地。**
⚠️ 风险仍成立：这些交付**只存在于本机**，worktree 丢失即不可恢复。

## 吞吐预期（按当前档位现算，不引用任何历史文档的写死数字）

- 档位：`MODE=reduced` ⇒ `LANES=2`、`THROTTLE_MIN=100`
- cron：`0 * * * *`（整点，24 触发点/天）+ `SILENT_WINDOW=0` ⇒ 全天可放行
- 节流闸 100 分钟 > 60 分钟栅格 ⇒ **每 2 个整点放行一批 ≈ 12 批/天**
- 名义吞吐 = 2 × 12 = **24 个/天**；× 综合折损 **0.70**（BLOCKED 1.5% × 不可调度 5% × L2 返工 10% × 长尾占位 10%）
  ⇒ **实际约 16–17 个/天**
- 剩余 **43** 个 ⇒ 43 ÷ 16.8 ≈ **2.6 天** ⇒ **诚实区间 3–5 天**
  （下沿假设 L2 红点本轮内修好；上沿假设 L2 红点修不好或长尾车道占位严重）

## 后续待办（归下一轮）

1. **【最高优先】派 `identity` L2 红点修复车道**（P0，见本轮 backlog）。本轮因 load 1min 一度冲到
   12.03（自跑隔离复跑所致，30 秒内回落）且在途车道 L1 尚未结束，**没有派第三条车道**；
   下一轮负载回落后立即派发。
2. **回收本轮 2 条车道**（13.4.1.1 / 13.4.2.1）：`merge-ready` → `sync` → 静态预检 → **L2 全量**。
3. **L2 绿之后**：`git merge --no-ff origin/integration/module-dev` 把远端那 1 个文档提交合回本地
   ⇒ 写 `batch-107` 交付说明（13.4.1.1 + 13.4.2.1）⇒ commit ⇒ **一次 push 走 104+ 个提交**
   ⇒ 顺带关闭 `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md` 的待办 1。
4. **D6 的 3 个定价模块**（`5.1.1.1` / `5.1.1.2` / `5.1.2.1`）单独排一批 —— 它们是卷 5 定价的入口，
   与卷 13/15/18 的 D7 依赖链独立。

## 追加（同轮次内）：13.4.1.1 落地并入 + identity 修复车道派发

派发后 43 分钟，**13.4.1.1「多模式利润报表」车道落地**（`land` 返回 `{"ok": true, "head": "a5e1f08"}`），
本轮随即做了三件追加动作：

### 1. 回收并入（重新取闸后执行）

```
lane.mjs merge-ready → {"lane": "13.4.1.1", "from": "80834214", "to": "a3a2e829"}，无冲突
sync.mjs            → 已交付 422 → 423，未开发 43 → 42
```

并入后在集成头上复核静态预检（`V398__profit_report.sql`）：
① `ADD COLUMN` 无 `DEFAULT` **零命中** · ② `SET NOT NULL` **零命中** · ③ `ADD CONSTRAINT…CHECK` **零命中**
⇒ 本批新迁移**纯 CREATE TABLE、零 ALTER 既有表**。

### 2. 写 batch 交付说明（batch-107）

`docs/sessions/2026-10/2026-10-28-batch-107-profit-analytics.md` —— 13.4.1.1 一节
（功能 / 迁移 / 端点 / 页面 / 测试 / 关键设计决策 / 已知缺口 / 验证输出），
并为在途的 13.4.2.1 预留一节待其落地后追加。
commit：`324f9d70 docs(batch-107): 13.4.1.1 多模式利润报表 —— 模式分行的利润报表与多维核算（V398）`
（说明文件与台账**同一个 commit**）。

### 3. 派发 identity 红点修复车道（P0，已派发）

按本轮取证的结论创建了**修复车道**（仓库既有先例：`fix-channel-l2-red` 等）：

| 项       | 值                                                                 |
| -------- | ------------------------------------------------------------------ |
| 车道 ID  | `fix-identity-l2-red`                                              |
| 分支     | `fix/identity-l2-red-403-pollution`（基线 `324f9d70`）             |
| worktree | `.worktrees/fix-identity-l2-red`                                   |
| 任务书   | 含本轮全部四步取证（403 签名 / 空载仍红 / 生产代码 0 改动 / 单类 12/12 绿） |

任务书里写死三条纪律：**优先改测试夹具不动生产语义**（与 `32a16a6a` 同一原则）；
**不许弱化断言换绿**（宁可红着报，不要假绿）；**必须确认修完之后租户隔离三条断言仍成立**
（别为了消 403 把权限判据放松到跨租户也能过）。

### 本轮收尾三件套（最终状态）

| 件       | 状态    | 说明                                                                                       |
| -------- | ------- | ------------------------------------------------------------------------------------------ |
| ① 写说明 | ✅ 完成 | 本文件（round 122）+ `batch-107` 交付说明 + identity backlog P0                            |
| ② commit | ✅ 完成 | `80834214`（round-122 说明）+ `324f9d70`（batch-107 说明 + 台账 423/42）                  |
| ③ push   | ❌ **不做** | **L2 仍是红的**（identity 38 failures，修复车道在途）**且** push 会被 non-fast-forward 拒（远端多 1 / 本地多 106 个提交）⇒ 两条理由任一条都足够 |

---

## 涉及文件

- `docs/sessions/2026-10/2026-10-28-1800-pipeline-round122-dispatch-two-lanes-identity-l2-red.md`（本文件）
- `docs/sessions/2026-10/2026-10-28-batch-107-profit-analytics.md`（13.4.1.1 交付说明）
- `docs/backlog/2026-10/2026-10-28-identity-l2-red-403-cross-test-pollution.md`（新增 P0）
- `docs/product/capability-blueprint/module-progress.md`（台账，本轮净改动 0）
- `.tmp/l2-batch108.log` / `.tmp/l2-batch107b.log` / `.tmp/l2-batch108.runner.log`（红点原始证据）
- `.tmp/identity-approval-isolated.log`（本轮隔离复跑证据：12/12 绿）
- `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md`（远端分叉 P0，关联）
