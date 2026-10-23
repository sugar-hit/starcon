# 2026-10-23 00:20 集成分支并入 main（第 37 轮 · batch-67 四项能力交付 · 已合入）

## 摘要

「`integration/module-dev` → `main`」同步任务第 37 轮。

**结果：已合入。** PR #47（squash）落地 `main`，tip = `5b642caf feat: 邮件规则、工单列表等 4 项能力交付`。

本轮合入 batch-67 的 **4 项新交付**：邮件规则（15.1.2.2）、工单列表（15.4.1.1）、自动回复（15.5.1.1）、
违规与处罚（16.1.2.1）；台账「已交付」由 **324 → 328**，完成率 **69.7% → 70.5%**。

合并形态：**真合并有冲突**（31 个文件冲突），逐个按证据裁决。

## 决策 / 结论

### 1. 31 个冲突文件分三类裁决

| 类别 | 文件数 | 裁决 | 依据 |
| --- | --- | --- | --- |
| 纯排版差异 | 29 | **取 main 侧** | 两侧经 Prettier 归一化后**逐字节相同**（A=29）；main 侧已是规范态（B=29 / C=0），取集成侧会退回未格式化字节 |
| 台账·绩效指标监控证据列 | 1 | **取集成侧** | 集成侧是**校正后**的版本，main 侧是旧快照（见下「判据 2」） |
| `api-proxy.ts` 白名单 | 1 | **取集成侧** | 累积型三件套全部成立，取新侧在结果上等同于完整求并（见下「判据 3」） |

⚠️ `merge-resolve.mjs` 把 29 个纯排版文件全部判成「并入（人工）」—— 它比的是 **blob 原始字节**，
Prettier 规范化过的字节必然对不上原始 blob ⇒ **该工具在纯排版场景会全量误报**，结论必须与归一化取证交叉验证。

### 2. 判据 2：绩效指标监控（16.1.1.2）的台账证据列取集成侧

两侧数据行的**模块 ID 集合完全相同**（465 个，一一对应），逐行 diff 只有 **5 行**不同，
且**每一行都是集成侧更新**（4 行「未开发 → 已交付」+ 1 行证据文本校正）⇒ main 侧没有任何集成侧缺的东西。

校正方向拿**真实代码**核对过（不信文档自述）：

| 计数 | main 侧文档 | 集成侧文档 | 实际代码 |
| --- | --- | --- | --- |
| `performance-metrics-model.test.ts` 用例数 | 22 | **23** | 23 |
| `performance-metrics-allowlist.test.ts` 用例数 | 7 | **6** | 6 |
| `channel.openapi.json` 里 `performance-metrics` 路径数 | +11 | **+12** | 12 |

⇒ 集成侧文档正确，main 侧是旧快照。

### 3. 判据 3：`api-proxy.ts` 白名单取集成侧

按 `(prefix, pattern)` **元组**比对（不能只按 prefix —— `services[]` 里多条条目共用同一 prefix）：

- 集合包含：main 的 157 条**全部**在集成侧的 161 条里（缺 0）
- 有序子序列：main 的顺序在集成侧**保序**（`resolve()` 是 first-match-wins，顺序错了会让旧分支吃掉新分支的路径）
- 零删除：新增 4 条纯增（3 条 `crm/` + 1 条 `channel/`）

4 条新条目的首字面量互不重复、也不与相邻条目重复 ⇒ 无遮蔽风险：
`email-rules` / `auto-replies` / `service-tickets` / `platform-violations`。

### 4. 台账被行级兜底并集搞坏 —— 必须整体取集成侧

`contract-union` 驱动对台账走**行级兜底并集**，产出**两处都坏**的结果：

1. **摘要行重复**：顶部留 main 的旧摘要（`| 已交付 | 324 |`），**文件尾部又追加一份**新摘要（`| 已交付 | 328 |`）
2. **数据行被求并成旧版**：`16.1.1.2` 那一行保留了 main 的旧证据文本（22 例 / 7 例 / +11 路径 / +18 DTO）

⚠️ `sync.mjs --check` **发现不了**（它只比**数据行**与蓝图，不看摘要块）⇒ 必须自己断言
`grep -cE '^\| 已交付 '` == **1** 且其值 == 集成侧的值。

⚠️ `git checkout --theirs` 对**未冲突**路径无效 —— 它从 index 取回，行级兜底并集的产物会回来；
本轮用 `git show upstream/integration/module-dev:<path> > <path>` 写入才修对。

### 5. 闸工具的 run 定位在本仓库失效（**需要修**）

`ci-gate.mjs` 的 `baselineRun()` / `prRun()` 用 `gh run list --branch main` 与 `gh run list --commit <sha>`
定位 run，**这两个服务端过滤器在本仓库一律返回 0 条**
（GitHub API 的 `?branch=main` 与 `?head_sha=` 同样 `total_count: 0`，而不带过滤器的列表能正常列出全部 run）
⇒ 闸工具直接报「取不到 CI 状态：main 上没有任何 CI run」并以退出码 2 假阻塞。

本轮**未改仓库**，改用**不带过滤器**的列表做客户端筛选定位 run，
再调用闸工具**自己导出的比较逻辑**（`compareRuns` / `extractFailureMarkers` / `jobLog`）得出结论：

- 基线 run = `39790153753`（main，push，completed/failure）
- PR run = `39811544294`（pull_request，failure）
- 失败作业两侧**完全相同**：Catalog Browser & Contract Tests / Java 21 & PostgreSQL Integration / Lint / Security Scan
- **新增失败作业：无；新增失败用例：无**
- 结论：**可以合并**

⚠️ **「明细不可比的作业」（只比对了作业粒度）**：Catalog Browser & Contract Tests、Lint、Security Scan
—— 这三个作业只做到作业粒度比较，PR 侧若在其中新增了失败文件，闸工具看不见。

## 验证结果

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 静态预检 | `grep 'ADD COLUMN' / 'SET NOT NULL' / 'ADD CONSTRAINT.*CHECK'` | 命中项**全在 catalog 服务既有迁移**（V14 / V15 / V35 / V41），与本次合入无关，**未新增** |
| 台账完整性 | `grep -cE '^\| [0-9.]+ '` | **465** ✓ |
| 台账重复 ID | `grep -oE '...' \| sort \| uniq -d` | **0** ✓ |
| 台账摘要行 | `grep -cE '^\| 已交付 '` | **1**，值 `328` ✓ |
| 台账同步自检 | `node tools/module-progress/sync.mjs --check` | `✓ 台账与蓝图一致` ✓ |
| 白名单全量回归 | `vitest run apps/web/src/app/api` | **184 文件 / 1062 例全绿** ✓ |
| 前端全量单测 | `vitest run` | **486 文件 / 7079 例全绿** ✓ |
| 类型检查 | `pnpm typecheck` | **3/3 成功** ✓ |
| Lint | `pnpm lint` | **3/3 成功**（仅既有 warning） ✓ |

**未跑本地全量 L2**（后端全量 + Next build）：本轮冲突解法**未触碰后端业务代码或迁移脚本**
（合并结果的 `backend/` 目录与集成侧**逐字节一致**），且定向验证全绿 ⇒ 按协议走定向级别 + 远端 CI 全量闸。

## CI 结果

PR #47 run `39811544294`：Typecheck / Unit & Integration Tests / CodeQL **通过**；
Lint / Java 21 & PostgreSQL Integration / Catalog Browser & Contract Tests / Security Scan **失败**
—— 与 `main` 基线 run `39790153753` 的失败集合**完全相同**，**无新增失败作业、无新增失败用例**
⇒ 基线相对判据：**可以合并**。

## 涉及文件

- `apps/web/src/lib/api-proxy.ts` —— 白名单新增 4 条独立 `services[]` 元素
- `docs/product/capability-blueprint/module-progress.md` —— 台账「已交付」324 → 328
- `packages/api-contracts/src/{channel.openapi.json,contracts/channel.ts,contracts/crm.ts,crm.openapi.json}` —— codegen 产物
- `backend/services/crm/src/main/resources/db/migration/V305__crm_email_rule.sql`、`V306__crm_service_ticket_list.sql`、`V307__crm_auto_reply.sql`
- `backend/services/channel/src/main/resources/db/migration/V308__channel_platform_violation.sql`

## 后续待办

1. **修 `tools/module-progress/ci-gate.mjs` 的 run 定位**：把 `gh run list --branch main` /
   `gh run list --commit <sha>` 换成「不带过滤器的列表 + 客户端筛选」
   （基线 = `headBranch === 'main'` 且 `conclusion !== null` 的最新一条；PR run = `headSha` 匹配且 `event === 'pull_request'`）。
   **不修则每轮都会以退出码 2 假阻塞。**
2. **给台账合并补一条断言**：`grep -cE '^\| 已交付 '` 必须 == 1，
   且其值 == 集成侧的值（`sync.mjs --check` 覆盖不到摘要块）。
3. `main` 与集成分支的**排版不对称**依然存在（main 侧是规范态、集成侧是原始态），
   根治要靠「把规范化后的字节回流到集成分支」，超出本任务授权范围 ⇒ **每轮都要重新取证**。
