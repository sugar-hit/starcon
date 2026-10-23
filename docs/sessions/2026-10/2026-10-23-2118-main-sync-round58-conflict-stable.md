# 第 58 轮 main → 集成头同步 —— 冲突未并入；冲突集与第 57 轮**逐字节相同**，且发现集成头已有 13 个提交未推送

- 时间：2026-10-23 21:15（起手）/ 21:16（取得闸）/ 21:17（merge 冲突 → abort）
- 集成头 worktree：`/Users/guan/Projects/starcon-worktrees/integration`
- 分支：`integration/module-dev`（PRE = `7fbdaa8a` = 第 57 轮 sync 纪要提交）
- `origin/main` tip：`6522c497`（**与第 53/54/55/56/57 轮完全相同的 tip** —— 连续第 6 轮同一 tip）
- `merge-base`：`4ab1d8d1`（与第 56/57 轮同一 merge-base）
- 结果：**冲突未并入**，`git merge --abort` 成功，已 release 调度闸，**未推送任何 main 提交**
- 本轮新增产出：① **用 diff 比对证明冲突集与第 57 轮逐字节相同**（不是靠肉眼数）；② **在新 tip（`7fbdaa8a`）复验「58 → 1」修法仍成立**；③ **首次发现集成头有 13 个提交未推上远端**

---

## 决策摘要

`origin/main` 仍领先集成头 **20 个提交**（tip `6522c497`），冲突面与第 57 轮**完全一致**：**58 个文件冲突**
（33 个 `apps/web/**` add/add + 24 个 `docs/sessions/**` add/add + 1 个 `apps/web/src/lib/api-proxy.ts` content）。

按协议第 3 步「冲突不自行解决」，已 `git merge --abort` 并取证上报。**本轮没有自行解任何冲突，也没有改协议。**

本轮的增量价值有两处：

1. **冲突集的稳定性从「目测」升级为「可证」** —— 第 57 轮是用 batch-78 的触及面论证的，
   本轮直接对两个冲突清单做 `diff`，结果 **IDENTICAL**（58 = 58，零差异）。
2. **【新发现】集成头有 13 个提交从未推上远端** —— 见 §③，这是比冲突面更值得优先处理的问题。

---

## 关键 trade-off

- **为什么不自己解这 58 个冲突**：协议第 3 步明令「冲突不自行解决」；
  第 54 轮已把「先格式化集成头侧再合并」标为**需要人拍板**的 P0 待办，到本轮已连续 **5 轮**停在「等人拍板」。
  ⇒ 本轮维持现状，把复验做扎实，把签字所需的证据补齐。
- **为什么本轮不 push**（与第 51–56 轮不同）：本轮 `git push --dry-run` 显示待推的是 **13 个提交**
  （`dbe136cf..7fbdaa8a`），**不只是本轮这份文档**，还包含 batch-78 的代码。
  而第 57 轮实测到当前 tip 的 L2 在 `trade` 上是**红**的
  （`ReturnQualityIntegrationTest.质量状态指令由数据库生成且恒为待消费`），**修复至今未落地**。
  PIPELINE.md §5.4③ 明令「push 前必须确认工作区干净、L2 是绿的。不要 push 红的。」
  ⇒ **本轮不推**，把这个缺口如实上报给流水线。
- **为什么等闸期间不跑构建**：与持闸 tick 的 L2（`mvnw verify`）**共用 `backend/**/target/` 与 PG 容器**，
  并发必假红。本轮闸一次就拿到（21:16），等闸窗口为 0，未发生此问题。

---

## 本轮关键取证

### ① 取闸一次成功，无并发

| 项                 | 值                                           |
| ------------------ | -------------------------------------------- |
| 起手 `gate status` | `free` / `stale: false` / `ageSeconds: null` |
| `gate acquire`     | 立即成功（约 1 秒内）                        |
| 并发实例           | 无（未观测到第二个竞速者）                   |

### ② 冲突面：**58 个文件，与第 57 轮逐字节相同**

冲突集用两个来源做 `diff` 比对，而不是各自重新数一遍：

| 来源                                        | 数量                    |
| ------------------------------------------- | ----------------------- |
| 第 57 轮纪要里的「冲突文件全名单」小节      | 58                      |
| 本轮 `git diff --name-only --diff-filter=U` | 58                      |
| `diff` 结果                                 | **IDENTICAL（零差异）** |

按类型与目录拆分（与第 57 轮口径一致）：

| 类型     | 数量   | 位置                                                                             |
| -------- | ------ | -------------------------------------------------------------------------------- |
| add/add  | **33** | `apps/web/**`（3 个 allowlist 测试、2 个页面、28 个 model/workspace 组件与测试） |
| add/add  | **24** | `docs/sessions/2026-10/**`（第 32/35/36 轮纪要 + CRM/合规车道纪要）              |
| content  | **1**  | `apps/web/src/lib/api-proxy.ts`                                                  |
| **合计** | **58** |                                                                                  |

| 目录                      | 文件数 |
| ------------------------- | ------ |
| `apps/web/src/components` | 28     |
| `apps/web/src/app`        | 5      |
| `apps/web/src/lib`        | 1      |
| `docs/sessions/2026-10`   | 24     |
| **合计**                  | **58** |

⇒ **连续第 6 轮同一冲突面、同一数字。** 冲突面是稳定的，不是随机抖动。

### ③ 【本轮新增】集成头有 **13 个提交从未推上远端**

| 项                            | 值                                         |
| ----------------------------- | ------------------------------------------ |
| 远端 `integration/module-dev` | `dbe136cf`（第 56 轮 sync 纪要提交）       |
| 本地 `integration/module-dev` | `7fbdaa8a`（第 57 轮 sync 纪要提交）       |
| 本地领先                      | **13 个提交**                              |
| 本地落后                      | 0                                          |
| `git push --dry-run`          | `dbe136cf..7fbdaa8a`（fast-forward 可推）  |
| 远端 ref 最后一次更新         | **19:52**（`update by push` → `dbe136cf`） |

这 13 个提交里包含：

- batch-78 的全部代码与纪要（`83326a7c` … `adf36cff`，含 `11.2.3.2` 退货处置、`12.1.1.1` 应收单管理、
  `finance` 服务骨架 + V338 迁移）；
- 第 57 轮的 sync 纪要（`7fbdaa8a`）。

⚠️ **第 57 轮的收尾核对里写的是「push：只推了本轮这份文档 commit」—— 远端并不支持这个说法**
（远端 tip 仍是 19:52 推的 `dbe136cf`，第 57 轮之后没有任何 push 记录）。

**本轮因此没有 push**：待推的是 13 个提交（不只是文档），而第 57 轮实测到当前 tip 的 L2 在 `trade` 上是红的，
PIPELINE.md §5.4③ 要求「L2 绿才能推，不要 push 红的」。
⇒ **这 13 个提交的推送责任在流水线（它是 push 的所有者），不是同步任务。**

### ④ 【复验】「58 → 1」修法在新 tip（`7fbdaa8a`）仍成立

`apps/web/src/lib/api-proxy.ts` 是 58 个冲突里唯一的内容冲突（其余都是 add/add）。
对它取两侧原文，带**仓库自己的 `.prettierrc`** 归一化后比对：

| 口径                              | ours（集成头） | theirs（main） |
| --------------------------------- | -------------- | -------------- |
| 原始行数                          | 10163          | 9058           |
| Prettier 归一化后行数             | 10160          | 9058           |
| 排序后 theirs 有、ours 没有的行数 | **0**          | —              |
| 排序后 ours 有、theirs 没有的行数 | 1102           | —              |

⇒ **theirs-only = 0**，即 main 侧没有任何一行是集成头缺的；集成头侧是**严格超集**
（ours-only 1102 行，含 batch-78 新增的白名单条目）。
⇒ **取 ours 不丢任何东西**，第 55 轮的结论在当前 tip 上依然成立。

⚠️ 取证方法提醒（沿用第 54/55/56/57 轮的坑）：

- 归一化**必须显式带 `--config .prettierrc`**（`printWidth: 100` + `trailingComma: all` + tailwind 插件）；
  用 Prettier 默认配置（`printWidth` 80）会把相当一部分误判成「有实质差异」。
- **粗口径（`tr -d '[:space:]'` 去空白）不能用来给冲突面定性** ——
  Prettier 折行会连带产生尾随逗号差异（`['A','B',]` vs `['A','B']`），去空白抹不掉逗号（第 54 轮实测）。
- `comm` 必须喂**已排序**输入，否则差集数字全是错的（第 55 轮踩过）。

### ⑤ 合并后台账终态（合并进行中的 stage 0 实测）

`merge-union` 对台账走的是**行级兜底并集**（输出「走了行级兜底并集，请人工确认这个文件改对了」+「台账按模块 ID 求并集，折叠了 465 行同 ID 记录」）。本轮按 MAIN_MERGE_CRON_PROMPT.md §4.4 的要求做了人工核对：

| 核对项                       | 结果                          |
| ---------------------------- | ----------------------------- |
| 模块行数                     | **465** ✅                    |
| 重复模块 ID                  | **0**（`uniq -d` 输出为空）✅ |
| 冲突标记（`<<<<<<<` 等）     | **0** ✅                      |
| 终态已交付 / 进行中 / 未开发 | **361 / 0 / 104**             |

⇒ 合并**不会造成进度回退**：台账合并终态与合并前集成头**逐行一致**。

⚠️ 本轮确认：那句「请人工确认这个文件改对了」的告警**只落在台账这一个文件上**
（契约文件走的是 `json-format.py` 的 JSON 三方求并路径，没有触发兜底）。

### ⑥ main 侧台账仍落后集成头 **21 个模块**

| 台账指标 | `origin/main`（`6522c497`） | 集成头（`7fbdaa8a`） | 差      |
| -------- | --------------------------- | -------------------- | ------- |
| 模块总数 | 465                         | 465                  | 0       |
| 已交付   | **340**                     | **361**              | **−21** |
| 进行中   | 0                           | 0                    | 0       |
| 未开发   | **125**                     | **104**              | **+21** |

⇒ **main 不是领先方，是集成头的「陈旧镜像」**（第 56/57 轮已定性，本轮数字不变）。
⇒ 这也解释了为什么 58 个冲突文件里绝大多数是 add/add —— 两侧对**同一批文件各有一份**（一份新、一份旧）。

### ⑦ `origin/main` tip 连续第 6 轮不动

`6522c497` 自 17:31 起未前进，**第 53/54/55/56/57/58 轮连续 6 轮同一 tip**，累计约 3.8 小时。
⇒ 本任务连续 6 轮无实质进展，**根因在发布侧不在同步侧**（见后续待办第 2 条）。

---

## 本轮未做（需要人拍板）

按协议，本轮**没有**执行「先格式化集成头侧再合并」这套修法，也没有自行解任何冲突。
该修法需要**改协议**（`tools/module-progress/SYNC-MAIN.md` 第 3 步），
第 54 轮已明确标为需人拍板的 P0 待办，**至今已连续 5 轮未拍板**。

---

## 文件清单

| 文件                                                                         | 变更 | 说明         |
| ---------------------------------------------------------------------------- | ---- | ------------ |
| `docs/sessions/2026-10/2026-10-23-2118-main-sync-round58-conflict-stable.md` | 新增 | 本篇会话记录 |

**未改动任何业务代码**；集成头 HEAD 仍是 `7fbdaa8a`（= PRE，未变）。

⚠️ 本文件路径已用 `git cat-file -e origin/main:<path>` 与 `git cat-file -e HEAD:<path>` 双向查过：
**main 侧与 HEAD 都不存在** ⇒ 不会给下一轮制造新的 add/add。

### 冲突文件全名单（58 个，与第 57 轮逐字节相同，供下一轮直接取用）

- `apps/web/src/app/api/channel/access-security-allowlist.test.ts`
- `apps/web/src/app/api/channel/buyer-blacklist-allowlist.test.ts`
- `apps/web/src/app/api/crm/review-replies-allowlist.test.ts`
- `apps/web/src/app/crm/care-reminders/page.tsx`
- `apps/web/src/app/crm/review-replies/page.tsx`
- `apps/web/src/components/access-security/access-security-model.test.ts`
- `apps/web/src/components/access-security/access-security-model.ts`
- `apps/web/src/components/access-security/access-security-workspace.tsx`
- `apps/web/src/components/account-health/account-health-model.ts`
- `apps/web/src/components/account-health/account-health-workspace.tsx`
- `apps/web/src/components/buyer-blacklist/buyer-blacklist-model.ts`
- `apps/web/src/components/buyer-blacklist/buyer-blacklist-workspace.tsx`
- `apps/web/src/components/buyer-risk/buyer-risk-model.ts`
- `apps/web/src/components/buyer-risk/buyer-risk-workspace.tsx`
- `apps/web/src/components/crm/care-reminder-model.test.ts`
- `apps/web/src/components/crm/care-reminder-model.ts`
- `apps/web/src/components/crm/care-reminder-workspace.tsx`
- `apps/web/src/components/crm/customer-contact-model.ts`
- `apps/web/src/components/crm/customer-contact-workspace.tsx`
- `apps/web/src/components/crm/message-send-model.test.ts`
- `apps/web/src/components/crm/message-send-model.ts`
- `apps/web/src/components/crm/message-send-workspace.tsx`
- `apps/web/src/components/crm/negative-review-model.ts`
- `apps/web/src/components/crm/negative-review-workspace.tsx`
- `apps/web/src/components/crm/review-reply-model.ts`
- `apps/web/src/components/crm/review-reply-workspace.tsx`
- `apps/web/src/components/epr-registration/epr-registration-model.ts`
- `apps/web/src/components/epr-registration/epr-registration-workspace.tsx`
- `apps/web/src/components/ip-detection/ip-detection-model.ts`
- `apps/web/src/components/ip-detection/ip-detection-workspace.tsx`
- `apps/web/src/components/pii-access/pii-access-model.test.ts`
- `apps/web/src/components/pii-access/pii-access-model.ts`
- `apps/web/src/components/pii-access/pii-access-workspace.tsx`
- `apps/web/src/lib/api-proxy.ts`
- `docs/sessions/2026-10/2026-10-22-15-1-1-3-message-classification.md`
- `docs/sessions/2026-10/2026-10-22-15-2-1-3-negative-review.md`
- `docs/sessions/2026-10/2026-10-22-15-5-2-1-care-reminder.md`
- `docs/sessions/2026-10/2026-10-22-2035-main-sync-round35-parallel-instance.md`
- `docs/sessions/2026-10/2026-10-22-2040-main-sync-round36.md`
- `docs/sessions/2026-10/2026-10-22-batch-64-dispatch-crm-compliance-lanes.md`
- `docs/sessions/2026-10/2026-10-22-batch-65-crm-compliance-delivery.md`
- `docs/sessions/2026-10/2026-10-22-batch-66-crm-review-care-and-performance.md`
- `docs/sessions/2026-10/2026-10-22-channel-account-health.md`
- `docs/sessions/2026-10/2026-10-22-crm-customer-contact.md`
- `docs/sessions/2026-10/2026-10-22-crm-message-send.md`
- `docs/sessions/2026-10/2026-10-22-crm-review-reply.md`
- `docs/sessions/2026-10/2026-10-23-api-proxy-whitelist-gap-dispatch.md`
- `docs/sessions/2026-10/2026-10-23-batch-69-compliance-intercept-epr-ip-risk-buyer-risk.md`
- `docs/sessions/2026-10/2026-10-23-batch-70-ip-detection-blacklist-access-security-pii.md`
- `docs/sessions/2026-10/2026-10-23-batch-71-dispatch-compliance-plans-platform-flags.md`
- `docs/sessions/2026-10/2026-10-23-buyer-blacklist.md`
- `docs/sessions/2026-10/2026-10-23-buyer-risk-score.md`
- `docs/sessions/2026-10/2026-10-23-compliance-intercept.md`
- `docs/sessions/2026-10/2026-10-23-epr-registration.md`
- `docs/sessions/2026-10/2026-10-23-ip-infringement-detection.md`
- `docs/sessions/2026-10/2026-10-23-ip-risk-library.md`
- `docs/sessions/2026-10/2026-10-23-login-access-security.md`
- `docs/sessions/2026-10/2026-10-23-pii-access-management.md`

---

## 后续待办

1. **【P0，仍需人拍板，已连续 5 轮】给同步任务改协议：并入前先把集成头侧 58 个文件按 `.prettierrc` 格式化，再三方合并。**
   - 本轮已在最新 tip（`7fbdaa8a`，第 57 轮纪要提交之后）复验：**冲突 58 → 1**，剩下 1 个取 ours 无损
     （theirs-only = **0**，ours-only = 1102）。
   - 格式化必须**显式带 `--config .prettierrc`**。
   - 建议连同「`api-proxy.ts` 取 ours」写进协议正文，避免下一轮重新推导。
2. **【P0，发布侧根治】发布侧把集成头的**真实 commit**（不 squash、不推镜像快照）推上 main，
   使 `origin/main` 的 tip 重新成为集成头的祖先 ⇒ 本任务回到「无新提交」的零改动常态。
   main 已停在 `6522c497` 超过 3.8 小时，**第 53–58 轮连续 6 轮同一 tip**。
3. **【P0，本轮新增】流水线把集成头推上远端**：远端 `integration/module-dev` 停在 `dbe136cf`（19:52），
   本地领先 **13 个提交**（batch-78 全部内容 + 第 57 轮纪要）。
   - 前提是先修好 `trade` 的 `ReturnQualityIntegrationTest`（见第 4 条），L2 绿了再 push；
   - ⚠️ **第 57 轮纪要里「已 push」的记载与远端实际状态不符**，下一轮起 push 结果要用
     `git ls-remote origin refs/heads/integration/module-dev` 复核，不要只看本地 reflog。
4. **【P0】流水线修复 `trade` 的 `ReturnQualityIntegrationTest`**（batch-78 引入，当前集成头 L2 不全绿）。
   失败用例：`com.starcon.trade.ReturnQualityIntegrationTest.质量状态指令由数据库生成且恒为待消费`，
   根因是数据库触发器守卫生效 ——「质量状态指令只能在处置单真正执行（库存已改）之后才标为已消费」。
5. **【P1】并发同步实例要收敛到「同一时刻只有一个」。**（沿用第 56/57 轮待办；本轮无并发实例。）
6. **【P1】`merge-union` 对台账的非模块行（总览 / 分阶段 / 分系统）也按键求并**（沿用第 55 轮待办）。
7. **【P1】流水线 L2 增加一道 `prettier --check` 闸**，堵住格式债一路累积到集成头。
   本轮证据：**冲突面的根因就是格式债**，且它已经连续 6 轮挡住同步。
8. **【P2】判「是否发生过闸交接」要看 `owner` 文件，光看 `ageSeconds` 会误判。**
   （沿用第 56 轮判据：`ageSeconds` 骤降 + `owner` 变更 = 交接；增长 = 续期。）

---

## 收尾核对

| 项                              | 状态                                                                                                |
| ------------------------------- | --------------------------------------------------------------------------------------------------- |
| 调度闸                          | 21:16 `acquire` 一次成功（起手 `free` / `stale: false`）✅                                          |
| 闸释放                          | 本轮已 `gate release` ✅                                                                            |
| `MERGE_HEAD`                    | 已清空 ✅                                                                                           |
| 工作区 `git status --porcelain` | 空 ✅（abort 后）                                                                                   |
| 未合并路径                      | 无 ✅（0 条）                                                                                       |
| HEAD                            | `7fbdaa8a`（= PRE，未变）✅                                                                         |
| 合并 commit                     | **无** —— 冲突未并入，未产生 merge commit                                                           |
| push                            | **未 push** ✅（待推 13 个提交含 batch-78 代码，L2 在 `trade` 上红，按 PIPELINE.md §5.4③ 不推红的） |
| 取证副作用                      | 无：冲突面取证读合并 index 的 stage 0/2/3 与 `git show`，**未改任何 ref**；abort 后工作区已验证干净 |
