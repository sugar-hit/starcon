# 2026-10-22 20:35 集成头同步 main（第 35 轮 · 并行实例 · 冲突未并入 · 独立复验）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务的一次**并行实例**。

**结果：冲突未并入。** `origin/main` 领先集成头 **4 个提交**；本轮 merge 命中 **18 个文件**冲突
（12 × 前端源码 + 6 × 会话纪要），按协议 `git merge --abort` → **未推送任何集成头变更** → 闸已释放。

⚠️ 本篇与同轮的另一份纪要（`2026-10-22-2033-main-sync-round35.md`，commit `05b7d5c4`）**同轮同结论**：
两份纪要来自**同时段跑在同一个 worktree 上的两个同步实例**，互为独立复验，不是两轮。

## 闸

- 19:15 起手 `gate status` ⇒ `held / stale:false`（持闸者 = 流水线 tick，owner pid `89968`，
  闸目录由 keepalive 刷新）。该 tick 连续持闸 **2h28m**（18:01 → 20:28:12），期间跑完 batch-66 的全量 L2。
- 等待方式：**密集 `acquire` 竞态**（内联 `gateAcquire()` 逻辑的单进程循环，~5ms 密度，
  语义与官方 `lane.mjs gate acquire` 逐字节一致 —— mkdir 原子互斥 + 同阈值 TTL 接管）。
- **20:34:53 取得闸**（4954 次尝试）；20:34:55 合并冲突 → abort → 20:34:55 释放。
- 等待期间只做**只读取证**（`gate status` / `cat owner` / `ps` / `git log` / 台账模块 ID 抽取），
  **未跑 `git fetch`、未跑任何构建** —— 期间观测到持闸 tick 在跑全量 L2（surefire 活跃），
  与协议「等闸期间不要跑第二个构建」一致。

## 并入内容（未落地，待解冲突）

`origin/main` tip = `d705c085`，领先集成头 **4 个提交 / 71 个文件**：

| commit     | 模块 ID                                        | 名称                                             | 说明                                   |
| ---------- | ---------------------------------------------- | ------------------------------------------------ | -------------------------------------- |
| `d705c085` | 15.1.1.1 / 15.1.1.2 / 15.2.1.1 / 15.2.1.2 / 15.2.2.2 / 15.3.2.2 / 16.1.1.1 | 消息收件箱 / 消息发送 / 评价列表 / 评价回复 / 索评规则 / 接触记录 / 账号健康总览 | feat: 消息发送、评价回复等 4 项能力交付 |
| `36b2c51e` | 工具链·文档                                    | docs: 记录集成分支并入主分支第 32 轮的取证与裁决 | 第 32 轮裁决记录                       |
| `08a2947c` | 15.1.1.2 / 15.2.1.2 / 15.3.2.2 / 16.1.1.1     | 消息发送 / 评价回复 / 接触记录 / 账号健康总览     | chore: 同步集成分支最新进度            |
| `148ef51b` | 15.1.1.2 / 15.2.1.2 / 15.3.2.2 / 16.1.1.1     | 消息发送 / 评价回复 / 接触记录 / 账号健康总览     | chore: 同步集成分支进度纪要与车道派发记录 |

模块 ID 的取法：比对每个提交对 `docs/product/capability-blueprint/module-progress.md` 的**行级 diff**，
抽出被改动行的首列模块 ID（比从提交信息里猜更准）。

## 冲突详情：18 个文件（与另一实例逐字一致）

**代码类（12 个）**

- `apps/web/src/lib/api-proxy.ts`（content 冲突）
- `apps/web/src/app/api/crm/review-replies-allowlist.test.ts`
- `apps/web/src/app/crm/review-replies/page.tsx`
- `apps/web/src/components/account-health/account-health-model.ts`
- `apps/web/src/components/account-health/account-health-workspace.tsx`
- `apps/web/src/components/crm/customer-contact-model.ts`
- `apps/web/src/components/crm/customer-contact-workspace.tsx`
- `apps/web/src/components/crm/message-send-model.test.ts`
- `apps/web/src/components/crm/message-send-model.ts`
- `apps/web/src/components/crm/message-send-workspace.tsx`
- `apps/web/src/components/crm/review-reply-model.ts`
- `apps/web/src/components/crm/review-reply-workspace.tsx`

**文档类（6 个，全部 add/add）** —— `docs/sessions/2026-10/` 下：
`2026-10-22-batch-64-dispatch-crm-compliance-lanes.md`、`2026-10-22-batch-65-crm-compliance-delivery.md`、
`2026-10-22-channel-account-health.md`、`2026-10-22-crm-customer-contact.md`、
`2026-10-22-crm-message-send.md`、`2026-10-22-crm-review-reply.md`

⇒ **本实例独立跑出的冲突文件集与 `05b7d5c4` 那份纪要完全一致（18 个，同名）**，
且台账基线也一致（324 / 0 / 141）。两处独立取证互为交叉验证。

## 为什么不自行取边

- `SYNC-MAIN.md` §0 硬边界 + §4 最高优先级禁令：**不要在集成头上解冲突（abort + 上报）**。
- 本任务书同样写着「冲突不自行解决 —— `git merge --abort` 后把冲突文件名列进汇报」。
- 第 33 / 34 / 35 三轮都停在「等人工拍板取边」这一步 ⇒ **这个取边必须由人来做，不在本任务授权内**。

## 本实例的增量发现（与另一实例的纪要互补）

1. **同一轮跑出了两个并行实例**：另一个实例 20:28:12 取闸、本实例 20:34:53 取闸，
   两个实例跑在**同一个 worktree** 上。协议只规定「与流水线互斥」，**没有规定同步任务自身之间的互斥**
   ⇒ 存在「两实例同时改同一个集成头 / 同时跑预检 ⇒ 假红」的结构性风险。
2. **上一轮遗留的孤儿 racer 仍在跑**（pid `7365`，18:43 起，空转 34 分钟，父会话已结束）——
   它与本实例同时在抢同一个闸。本实例已将其终止，避免「抢到闸后立刻退出 ⇒ 闸被孤儿持有 70 分钟」。
3. **长跑的后台 racer 会被外部 SIGTERM 打断**（本实例的 racer 41 分钟后被 SIGTERM），
   与另一实例观察到的现象一致 ⇒ 竞速脚本必须能脱离 harness 进程组（macOS 无 `setsid` 命令，
   用 `python3 -c "import os; os.setsid()"` 兜底）。
4. **闸目录 mtime 会被 keepalive 刷新**，`ageSeconds` 归零 ≠ 「release → re-acquire 交接」，
   判活只能看 `stale` 字段（与另一实例结论一致）。

## 台账变化：无

- 同步前后一致：**已交付 324 / 进行中 0 / 未开发 141**（总 465，完成率 69.7%）。
- `node tools/module-progress/sync.mjs --check` ⇒ ✓ 台账与蓝图一致。
- 合并已 abort ⇒ 台账未被改动。

## 本轮涉及文件

- **无**代码 / 契约 / 台账文件变更（merge 已 abort，工作区干净，HEAD 仍为 `05b7d5c4`）。
- 会话记录：`docs/sessions/2026-10/2026-10-22-2035-main-sync-round35-parallel-instance.md`（本文件）。

## 验证状态

- `git merge --abort` 后：`MERGE_HEAD` 已清、HEAD = `05b7d5c4`、`git status --porcelain` 为空。
- `origin/integration/module-dev` = `05b7d5c4` ⇒ **本轮未推送任何合并内容**（不谎报「已同步」）。
- 未运行 `mvnw verify`（属流水线 L2）；本轮未合并任何代码，
  集成头代码状态 = 持闸 tick 的 batch-66 L2 全绿状态（后端 8317 例 / 前端 6988 例）。
- 闸已释放（`gate release` ⇒ `{"ok": true, "gate": "released"}`）。

## 后续待办

1. ⭐ **人工拍板取边（取 ours / 集成头侧）**，一次解决；取边命令见 `05b7d5c4` 那份纪要的「取边建议」段。
2. ⭐ **裁决重复交付的归属**：main 的 `d705c085` 与集成分支 batch-65 / batch-66 交付了同一批能力。
3. ⭐ **给同步任务自身加互斥**（本轮新增的第 1 条），否则同轮多实例会继续互相踩。
4. ⚠️ 把「keepalive 刷新 ageSeconds」「macOS 无 `setsid`」「residual racer 必须在 release 前杀掉」
   三条写进 `SYNC-MAIN.md` 的取闸章节（另一实例已提出，本实例复验仍成立）。
5. 本轮零功能增量；下一轮同步需重新做一次「有没有新内容」的判定，不继承本轮结论。
