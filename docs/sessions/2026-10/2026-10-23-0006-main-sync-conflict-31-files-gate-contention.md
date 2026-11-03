# 2026-10-23: 集成头同步 main（冲突面扩到 31 文件 · 调度闸争用 2h51m · 并行实例互撞）

## 摘要

按 `tools/module-progress/SYNC-MAIN.md` 协议执行一次 `origin/main` → `integration/module-dev` 同步。

**本轮结果：冲突未并入** —— 合并在 **31 个文件**上真冲突，按协议 `git merge --abort` 后如实上报，
**未自行解冲突、未 push、未刷新台账**。

三个值得下轮注意的事实：

1. **冲突面从上一轮（2026-10-21，4 文件）扩到 31 文件**：新增 30 个 **add/add** 冲突
   （17 个 CRM/合规前端 model + workspace + test，13 篇 `docs/sessions/` 会话纪要）+ 1 个 both-modified
   （`apps/web/src/lib/api-proxy.ts`）。`api-proxy.ts` 是老面孔，其余 30 个是**新增面**。

2. **闸争用 2h51m（21:15 → 00:06:29）**，期间**未取得闸、未跑任何构建**。SYNC-MAIN.md 示例里
   「1 秒间隔竞态」的密度**实测不够**（见下「闸的实测语义」）。

3. **本轮与另一同步实例正面相撞**：00:03:18 另一实例取到闸并在同一 worktree 发起同一笔 merge，
   00:05:2x 它按协议自行 abort；本轮 00:06:29 取闸后**复现了同一组 31 个冲突**（逐个文件比对一致）。
   两轮**独立得出同一结论**（31 个文件 = 30 add/add + 1 both-modified；根因＝8 个 CRM 模块重复交付；
   `api-proxy.ts` 两侧在同一 `services[]` 区域各自插入 ⇒ 插入区间重叠）⇒ 冲突是**确定性**的。
   该实例另有一篇独立纪要：`docs/sessions/2026-10/2026-10-23-0006-main-sync-conflict-crm-duplicate-delivery.md`
   （同分钟前缀，两篇都在主工作区未提交）。

## 决策 / 结论

1. **冲突只报不解**（`SYNC-MAIN.md` §0 硬规定 + 本任务 prompt 逐字要求）。
   `git merge --abort` 退出码 0，`MERGE_HEAD` 已清，工作区干净，HEAD 停在 `f36ac759`，**未 push**。
   仓库里有 `tools/module-progress/merge-union.mjs`（调度器并车道的求并驱动器），
   但**本任务不适用** —— 它是 lane→集成头 的求并器，不是 main→集成头 的同步手段。

2. **冲突根因（逐行取证，不采信工具结论）**：main 的这 5 颗待并入提交里带的是 **8 个** CRM/合规模块
   （`d705c085`：15.1.1.2 消息发送 · 15.2.1.2 评价回复 · 15.3.2.2 接触记录 · 16.1.1.1 账号健康总览；
   `4ebb4b04`：15.1.1.3 消息分类 · 15.2.1.3 差评处理 · 15.5.2.1 关怀与催办 · 16.1.1.2 绩效指标监控），
   **在集成头与 main 两侧都是「已交付」**。
   两条独立实现路径（集成头＝车道并入；main＝上一轮并入主分支）落在**同一批文件路径**上
   ⇒ add/add。
   （另有 15.5.1.1 自动回复：集成头侧是 batch-67 才交付的，main 侧台账行同样为「已交付」，
   但它**不在这 5 颗待并入提交的模块清单里**，也不是本轮冲突文件之一 —— 不计入冲突面。）

3. **两侧台账基线（合并前）**：集成头 `f36ac759` = 已交付 **328** / 进行中 0 / 未开发 **137**（70.5%）；
   `origin/main` `4ebb4b04` = 已交付 **324** / 进行中 0 / 未开发 **141**（69.7%）。
   ⇒ **main 比集成头落后 4 个模块**（batch-67 的 15.1.2.2 / 15.4.1.1 / 15.5.1.1 / 16.1.2.1，
   00:02:32 由流水线 L2 全绿后并入）。但 **main 在这 9 个共享模块上有集成头没有的内容** ——
   「main 落后」不等于「main 是子集」。

4. **行级差集取证（ours=集成头 / theirs=origin/main，unique 行计数）**：

   | 冲突文件 | ours 独有 | theirs 独有 | 判读 |
   | --- | --- | --- | --- |
   | `apps/web/src/lib/api-proxy.ts` | 140 | **0** | **集成头严格超集** → 取 ours 无损失 |
   | `crm/care-reminder-workspace.tsx` | 25 | **52** | 真双向分叉 |
   | `crm/review-reply-workspace.tsx` | 33 | **43** | 真双向分叉 |
   | `crm/message-send-workspace.tsx` | 23 | **35** | 真双向分叉 |
   | `account-health/account-health-workspace.tsx` | 21 | **34** | 真双向分叉 |
   | `crm/negative-review-workspace.tsx` | 30 | 32 | 真双向分叉 |
   | 其余 12 个 model/test/page | 1–15 | 1–11 | 真双向分叉 |
   | `docs/sessions/` 13 篇 | 1–13 | 1–13 | 同题不同措辞的日志 |

   ⇒ **除 `api-proxy.ts` 外，其余 30 个文件两侧都有对方没有的内容**，盲目取一侧会丢内容；
   `api-proxy.ts` 侧双方共享同一条巨型 alternation（文件自带注释已写明「多车道同时并进去 git 必然判冲突，
   结构性，retry 不会自愈」）。**retry 不会自愈，需要一次语义裁决**。

5. **闸的实测语义（对 `SYNC-MAIN.md` 第 1 步的补充）**：
   - `ageSeconds` 取的是**闸目录 mtime**，keepalive 新建文件会刷新它 ⇒ 持闸 tick 跑 L2 期间
     `ageSeconds` 会**归零重算**，不能据此判断持闸者已死（owner 文件的 PID 更不可判活：写它的
     `gate acquire` 命令写完就退出）。
   - **交接窗口实测窄到 ~1 秒**。`SYNC-MAIN.md` 示例的「1 秒间隔 `gate acquire`」**跑不满 2h51m
     一次都没中**；改成**同进程内 5ms 间隔竞态 `mkdirSync`（≈170 次/秒）**后，一轮就命中。
     ⇒ 建议把协议里的竞态写法从「起 node 子进程 + sleep 1」改成「单进程内直接 `mkdirSync` 密集竞态」。

6. **并行实例互撞（本轮新现象，必须记）**：00:03:18 另一同步实例取闸 → 00:03:1x 在**同一 worktree**
   发起同一笔 merge（`MERGE_HEAD` = `4ebb4b04`）→ 撞同样 31 个冲突 → 00:05:2x 自行 abort → 放闸。
   本轮取闸后 merge 仍然撞同一组冲突 ⇒ **冲突是确定性的，不是偶发并发**。
   ⚠️ 但**并行实例本身是真实风险**：两个实例在同一 worktree 上先后 merge 同一笔提交，
   `MERGE_HEAD` / `MERGE_MSG` 会互相复用（git dir 里留的合并信息被下一个实例读到）。
   建议：同步任务的闸除了「与流水线互斥」，还要**能识别同任务的其他实例**（例如 owner 文件带任务标识，
   同一 kind 的实例互相让位）。

## 关键 trade-off

- **等闸 2h51m 而不是抢闸**：持闸 tick 是**活着的**（keepalive 持续续期、L2 在跑），
  `stale=false`。抢闸 ⇒ 两个构建并发跑 `backend/**/target/` 与 PG 容器 ⇒ **成片假红**。
  「宁可等，不可抢」这条在本轮被完整验证。
- **等闸期间只做只读取证**：未跑构建、未 merge、未动工作区；期间唯一一次 `git fetch` 发生在
  **已放弃等闸、准备如实汇报**时（只更新 remote-tracking ref，不碰工作区与 HEAD）。
- **不跑全量 `mvnw verify`**：预检只到编译级；本轮因冲突未进预检阶段。

## 文件清单

- **未新增/未修改任何业务文件**；集成头 `integration/module-dev` 停在 `f36ac759`，工作区干净。
- 冲突文件 31 个（完整清单见本轮汇报）：
  - 代码 18：`apps/web/src/lib/api-proxy.ts`（both-modified）+ 17 个 `apps/web/src/app/crm/**`、
    `apps/web/src/components/{crm,account-health}/**`（add/add）
  - 文档 13：`docs/sessions/2026-10/2026-10-22-{15-1-1-3,15-2-1-3,15-5-2-1,16-1-1-2,2035-…,2040-…,
    batch-64,batch-65,batch-66,channel-account-health,crm-customer-contact,crm-message-send,
    crm-review-reply}.md`（add/add）
- 本篇会话纪要落**主工作区** `docs/sessions/2026-10/`（未提交：同步任务不在 main 上提交任何东西）。

## 后续待办

1. **⚠️ 下一轮同步会撞同一组冲突**（确定性，不会自愈）。需要一次**语义裁决**：
   - `api-proxy.ts` ⇒ 可直接取集成头侧（严格超集，无损失）；
   - 13 篇会话纪要 ⇒ 同题日志，可取一侧或求并；
   - **17 个 CRM 前端文件 ⇒ 必须逐个语义合并**，两侧各有对方没有的内容（theirs 最多多 52 行）。
     需要判断哪一侧是权威实现（集成头侧过 L1+L2：batch-67 后端 8440 例 / 前端 7079 例全绿；
     main 侧是上一轮并入主分支时验证过的另一套实现）。
2. **并入顺序建议**：先把 main 那 9 个共享模块与集成头侧对齐（择一为准 + 求并），
   再跑同步；否则每轮同步都会在同一点反复 abort。
3. **流水线 L2 归因**：若下一轮流水线 L2 红了，按 `PIPELINE.md` §5.2，**「最近有没有并入 main」是首要嫌疑** ——
   但本轮**没有并入任何东西**（未 merge、未 push），所以 L2 红**与本轮无关**。
4. **协议修订建议（给人工裁决）**：`SYNC-MAIN.md` 第 1 步的等闸竞态写法改为「单进程内 5ms 级密集 `mkdirSync` 竞态」；
   并考虑给 owner 文件加**任务 kind 标识**，让同 kind 的并行实例互相让位。
