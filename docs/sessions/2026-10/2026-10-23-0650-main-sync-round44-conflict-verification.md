# 2026-10-23 06:15 集成头同步 main（第 44 轮 · 冲突未并入 · 39 文件 · 独立复核 + 两处取证修正）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 44 轮。

**结果：冲突未并入。** `origin/main` tip = `67966f83`，领先集成头 **14 个提交**；
本轮 merge 命中 **39 个文件**冲突（38 add/add + 1 content），
按协议 `git merge --abort` → **未推送任何合并内容** → 闸已释放。

本轮与第 43 轮（06:15 tick）的**冲突面完全相同**（同 tip、同共同祖先、同 39 文件），
但本轮做了**独立取证复核**，并修正了第 43 轮纪要中的**两处结论**：

1. **第 43 轮的「38/39 归一化后为排版差异」结论正确，但它的取证方法有致命缺口** ——
   若把两侧 blob 抽到**仓库内**的 `.tmp/`（该目录被 `.gitignore` 忽略），
   `prettier --write` 会**静默跳过全部文件**，归一化变成空操作，
   于是 39 个文件**全部**被判成「实质分歧」。必须显式传 `--ignore-path=/dev/null`。
2. **第 43 轮列为「必须人工确认」的 merge-union「行级兜底并集」告警是误判** ——
   该告警对 `.md` 台账是**无条件打印**的（`.md` 永远不是 JSON ⇒ 必然走行级兜底路径），
   **不代表**发生了键撞车。本轮实测台账按模块 ID 求并成功（465 行、0 重复 ID）。

## 闸

- **05:15 起手** `gate status` ⇒ `held / stale:false`。
- 持闸者 = **流水线 tick**（batch-70 那一轮），闸外取证：同 worktree 内 `mvnw verify`
  正在跑 `services/channel` / `warehouse` / `trade`（surefire 活跃）⇒ 持闸者活着。
- 等待方式：协议规定的**密集 `acquire` 竞态**（1s 密度）。等待期间只做只读取证，
  **未跑 `git fetch`、未跑任何构建**。第一轮 45 分钟竞态预算耗尽后**续了一轮**。
- **06:23:06** 流水线 tick 的 L2 转绿并 push `aee9ec60`；
  **06:23 之后**一个**同 cron 的第 43 轮 tick** 取闸、先跑完本轮（已 push 其纪要 `3ad1d222`）并释放闸。
- **06:28:59 本轮取得闸**（等待约 73 分钟），未发生 stale 接管。

## 并入内容（未落地，待解冲突）

`origin/main` tip = `67966f83`，领先集成头 **14 个提交**，共同祖先 = `4ab1d8d1`：

| commit     | 模块 ID                                   | 名称                                      | 说明                         |
| ---------- | ----------------------------------------- | ----------------------------------------- | ---------------------------- |
| `148ef51b` | 工具链·台账                               | 进度纪要与车道派发记录                    | chore: 仅进度同步，无新交付  |
| `08a2947c` | 工具链·台账                               | 同步集成分支最新进度                      | chore: 仅进度同步            |
| `36b2c51e` | 工具链·文档                               | 第 32 轮取证与裁决                        | docs: 仅新增 1 个纪要文件    |
| `d705c085` | 15.1.1.2 / 15.2.1.2 / 15.3.2.2 / 16.1.1.1 | 消息发送/评价回复/接触记录/账号健康总览   | feat: V297–V300 4 项能力交付 |
| `4ebb4b04` | 15.1.1.3 / 15.2.1.3 / 15.5.2.1 / 16.1.1.2 | 消息分类/差评处理/关怀与催办/绩效指标监控 | feat: V301–V304 4 项能力交付 |
| `5b642caf` | 15.1.2.2 / 15.4.1.1 / 15.5.1.1 / 16.1.2.1 | 邮件规则/工单列表/自动回复/违规与处罚     | feat: V305–V308 4 项能力交付 |
| `593b060e` | 15.4.1.2 / 16.1.2.2 / 16.2.1.1 / 16.3.1.1 | 工单处理/申诉管理/规则管理/税号与辖区     | feat: V309–V312 4 项能力交付 |
| `d764140c` | 工具链·文档                               | 第 38 轮零净变更复核                      | docs: 仅新增 1 个纪要文件    |
| `26370073` | 工具链·台账                               | 同步集成分支最新进度                      | chore: 仅进度同步            |
| `a3ba9ee7` | 工具链·文档                               | 第 41 轮零净变更复核                      | docs: 仅新增 1 个纪要文件    |
| `4afc4423` | 16.2.1.2 / 16.3.2.2 / 16.4.1.1 / 16.5.1.2 | 拦截执行/EPR包装法/侵权库/风险评分        | feat: V313–V316 4 项能力交付 |
| `fbdf691c` | 工具链·台账                               | 同步集成分支最新进度                      | chore: 仅进度同步            |
| `f108c894` | 工具链·台账                               | 同步集成分支最新进度                      | chore: 仅进度同步            |
| `67966f83` | 工具链·台账                               | 同步集成分支最新进度                      | chore: 仅进度同步            |

规模（相对共同祖先 `4ab1d8d1`）：**336 文件 / +224,016 / −42,968**。

⚠️ 5 个 `feat` 提交带来的 **20 个模块交付**（V297–V316）**集成头侧全部已具备**
⇒ 这 14 个提交对集成头而言**没有新增能力**。
本轮实测佐证：merge 的**非冲突增量部分共 14 个文件，全部是 `docs/`**，
**零代码文件**（`apps/` / `backend/` / `packages/` 全部无增量）。

## 冲突面分类（本轮独立复核）

判据：两侧 blob 经**仓库自身的 prettier**（3.9.9 + 仓库 `.prettierrc`）归一化后逐字节比较。

| 分类             | 判据                             | 数量   |
| ---------------- | -------------------------------- | ------ |
| **纯排版差异**   | 两侧 blob 归一化后**逐字节相同** | **38** |
| **实质内容分歧** | 归一化后仍有差异                 | **1**  |

### ⚠️ 取证陷阱（本轮修正第 43 轮的方法缺口）

第 43 轮纪要只提了「必须显式传 `--config <repo>/.prettierrc`」。**这条不够**：

- 若把两侧 blob 抽到**仓库目录内**的 `.tmp/`（被 `.gitignore` 忽略），
  `prettier` 的默认 `--ignore-path` 含 `.gitignore` ⇒ **静默跳过全部文件**，
  `--write` 变成**空操作**，且**不报任何错**（本轮首次尝试即踩中，39 个文件全判「实质分歧」）。
- 正确做法：归一化时**显式传 `--ignore-path=/dev/null`**（或把 blob 抽到**仓库外**并同时带 `--config`）。
  本轮加上 `--ignore-path=/dev/null` 后重跑 ⇒ 38 排版 + 1 真分歧，与第 43 轮结论一致。

⚠️ 同一陷阱已单独入 backlog `2026-10-23-prettier-cli-check-skips-gitignored-paths-false-green.md`
（那里记的是 `--check` 假绿；本条记的是 **`--write` 静默空跑导致假冲突**）。

### ⚠️ 修正：merge-union「行级兜底并集」告警是**无条件**的（第 43 轮误判）

第 43 轮把它列为「下一轮落地合并时必须人工确认 ⇒ 契约/台账里有键撞车」。**本轮实测不成立**：

- `merge-union.mjs:352` 的告警位于**行级兜底路径**内。该路径的进入条件是
  `mergeOpenApiSnapshot()` 返回 `status === 3`（**输入不是 JSON**）。
- 台账 `module-progress.md` 是 `.md` ⇒ **永远不是 JSON** ⇒ **每次合并必然打印这条告警**，
  与是否发生键撞车**无关**。
- 本轮直接单跑驱动验证：台账打印告警**且**打印
  `台账按模块 ID 求并集，折叠了 465 行同 ID 记录` ⇒ **模块 ID 级求并确实生效**。
- 本轮 merge 中台账的**实际合并结果**：**465 行、0 个重复模块 ID** ⇒ 无键撞车。
  （该文件被 `.gitattributes` 指定为 `merge=contract-union`，不在冲突面内。）

⇒ **下一轮不必再为此单独人工确认**，除非台账合并结果出现重复行或模块丢失。

## 唯一实质分歧：`apps/web/src/lib/api-proxy.ts`（content 冲突）

本轮用**两条独立证据**确认方向（与第 43 轮结论一致）：

1. **仓库自带的判定器** `tools/module-progress/merge-resolve.mjs`（`base=origin/main`、`head=integration/module-dev`）⇒
   `recommendation: 取集成分支侧`，累积型三件套**全部成立**：
   集合包含 ✓（`missingCount: 0`）、有序子序列 ✓、零删除 ✓（条目 164 → 168）。
2. **路由级解析**（不是文本 diff）：
   集成头侧 **173** 条 `services[]` 条目，main 侧 **169** 条；
   **main 有、集成头没有的路由 = 0**；仅集成头有的 **4** 条正是 batch-70 的模块
   （`access-security` 16.5.2.1 / `buyer-blacklist` 16.5.1.1 / `ip-detections` 16.4.1.2 / `pii-access` 16.6.1.1）。

⇒ **集成头侧是 main 侧的严格超集，取 ours 不丢任何 main 侧能力。**

## 冲突文件清单（39）

**前端代码（22，其中 21 个 add/add + 1 个 content）**

- `apps/web/src/lib/api-proxy.ts`（content 冲突，**唯一实质分歧**，见上）
- `apps/web/src/app/api/crm/review-replies-allowlist.test.ts`
- `apps/web/src/app/crm/care-reminders/page.tsx`、`apps/web/src/app/crm/review-replies/page.tsx`
- `apps/web/src/components/account-health/account-health-model.ts`、`account-health-workspace.tsx`
- `apps/web/src/components/buyer-risk/buyer-risk-model.ts`、`buyer-risk-workspace.tsx`
- `apps/web/src/components/crm/` 下 10 个：`care-reminder-model.test.ts`、`care-reminder-model.ts`、
  `care-reminder-workspace.tsx`、`customer-contact-model.ts`、`customer-contact-workspace.tsx`、
  `message-send-model.test.ts`、`message-send-model.ts`、`message-send-workspace.tsx`、
  `negative-review-model.ts`、`negative-review-workspace.tsx`、`review-reply-model.ts`、
  `review-reply-workspace.tsx`
- `apps/web/src/components/epr-registration/epr-registration-model.ts`、`epr-registration-workspace.tsx`

**会话纪要（17，全部 add/add）** —— `docs/sessions/2026-10/` 下：

`2026-10-22-15-1-1-3-message-classification.md`、`2026-10-22-15-2-1-3-negative-review.md`、
`2026-10-22-15-5-2-1-care-reminder.md`、`2026-10-22-2035-main-sync-round35-parallel-instance.md`、
`2026-10-22-2040-main-sync-round36.md`、`2026-10-22-batch-64-dispatch-crm-compliance-lanes.md`、
`2026-10-22-batch-65-crm-compliance-delivery.md`、`2026-10-22-batch-66-crm-review-care-and-performance.md`、
`2026-10-22-channel-account-health.md`、`2026-10-22-crm-customer-contact.md`、
`2026-10-22-crm-message-send.md`、`2026-10-22-crm-review-reply.md`、
`2026-10-23-batch-69-compliance-intercept-epr-ip-risk-buyer-risk.md`、
`2026-10-23-buyer-risk-score.md`、`2026-10-23-compliance-intercept.md`、
`2026-10-23-epr-registration.md`、`2026-10-23-ip-risk-library.md`

## 根因与可执行的下一步

### 排版债的方向（本轮实测澄清）

对同一对 blob 用仓库 prettier 单独检查两侧：

- **main 侧 = 已是规范态**（`All matched files use Prettier code style!`）
- **集成头侧 = 不是规范态**（`[warn] Code style issues found`）

⇒ 两侧归一化后相同 ⇒ **差异只在排版**。
⇒ 下一轮若落地合并：**38 个文件取 main 侧**（规范态，零新增格式债）；
**`api-proxy.ts` 取集成头侧**（严格超集）—— 注意这两个方向的取边**相反**。

### add/add 冲突闭环的根治（本轮新增证据）

本轮用受控实验确认 git 的行为：

- add/add 且**内容逐字节相同** ⇒ git **自动合并成功，零冲突**。
- add/add 且**内容有任何字节差**（哪怕纯排版）⇒ `CONFLICT (add/add)`。

⇒ **那 17 个会话纪要之所以每轮撞 add/add，是因为集成头侧与 main 侧的排版基线不同**，
而不是因为内容真的分叉。

⇒ **根治方案（下一轮可直接执行）**：**把本轮新增的纪要按仓库 prettier 规范写**，
使它在集成头侧就与 main 侧（或其规范态副本）**逐字节相同** ⇒ 下轮 merge 自动合并，**不再进冲突面**。
本篇纪要已按此要求写成（prettier `--check` 通过）。

### 备选根治

停止把同步轮次纪要双写回主分支，或在合并前按「文件名 + 内容哈希」去重。

## 台账

- `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（只校验，不写文件）。
- 集成头侧：**已交付 340 · 进行中 0 · 未开发 125**（465 个 L4 模块）。
- `origin/main` 侧：**已交付 336 · 进行中 0 · 未开发 129**。
- 逐行比对 465 行的「状态」列，**差异恰好 4 个模块**（集成头 = 已交付，main = 未开发）：
  `16.4.1.2 检测与预警`、`16.5.1.1 黑名单`、`16.5.2.1 登录与访问安全`、`16.6.1.1 PII 管理`
  ⇒ 这 4 个正是 **batch-70 刚并入、还没回流到 main** 的车道。
- 同步前后**无变化**（merge 未落地，未刷新基线）。

## 本轮未执行

- **快速预检（`mvnw -q compile` + `pnpm typecheck`）未跑** —— merge 已 abort，
  无合并内容落地，无编译对象可预检；本轮 push 的仅是**纯文档**提交。
- **未 push 任何合并内容** —— 集成头无 merge commit，只有本篇纯文档纪要。
- **未自行解任何冲突** —— 按协议 `git merge --abort`
  （`MERGE_HEAD` 无残留，工作区干净，HEAD 与 merge 前逐字节一致：`3ad1d222`）。
- **未 push 到 `main`**。
- **未跑全量 `mvnw verify`**（属流水线 L2，本轮只等它跑完，未自己跑）。

## 下一轮建议

1. **归一化取证必须带 `--ignore-path=/dev/null`**（本轮教训，见上）。
   判据仍**不是** `git diff -w`。
2. **38 个纯排版差异文件取 main 侧**（规范态）；
   **`api-proxy.ts` 取集成头侧**（已用路由级解析证明是严格超集）。
3. **merge-union 的「行级兜底并集」告警不必再单独人工确认**（本轮已证其无条件，
   且台账合并结果 465 行 / 0 重复 ID）；除非台账出现重复行或模块丢失。
4. **新增纪要按仓库 prettier 规范写**（本篇已照做）⇒ 从下一轮起不再撞 add/add。
5. 合并成功后必须跑 `mvnw -q compile` + `pnpm typecheck` 预检，再 `sync.mjs` 刷台账、push。

## 遗留风险（非本任务范围，仅记录）

- **仓库级 UTF-8 字符损坏**：157 个受版本控制的文件仍命中 `U+FFFD`（含已应用的 Flyway 迁移，
  不可直接改否则 checksum 炸）。已入 backlog `ecac9ad8`。
- **17 个会话纪要 add/add** 在本轮给出的根治方案落地前，每一轮都会再命中一次。
- **集成头 → main 的回流链路**还没把 batch-70 的 4 个模块送回 main
  （台账差 4 行、api-proxy 差 4 条路由）⇒ 这不是冲突，是**回流滞后**。
