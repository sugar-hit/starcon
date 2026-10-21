# 2026-10-21 18:30 集成分支同步 PR：采购单创建编辑与打样样品管理交付

## 摘要

把 `integration/module-dev` 经 Pull Request 合入 `main`。本轮合入 **2 项新交付**：
采购单创建与编辑（6.3.1.2）、打样与样品管理（6.6.1.2）。

合并形态是**真合并有冲突**（4 个文件）。四个冲突文件全部判定为「main 侧是集成分支的祖先快照」，
取集成分支侧，**未丢失任何内容**。

## 决策 / 结论

1. **四个冲突文件全部取集成分支侧**，判据是两条互相独立的证据：
   - **祖先快照成立**：`upstream/main` 的这三个 trade port 文件与 `api-proxy.ts` 的 blob，
     都能在集成分支自己的历史提交里找到**逐字节相同**的一份 ⇒ main 侧不是新交付，
     是集成分支自己工作的旧快照 ⇒ 取新侧等于完整求并。
   - **累积型三件套全部成立**（`api-proxy.ts`）：集合包含 ✓ · 有序子序列 ✓ · 零删除 ✓，
     白名单条目 115 → 117 ⇒ 顺序敏感的第一匹配语义没有被破坏。
   - ⚠️ 判定工具对这四个文件都标注了「冲突类型：content」，但其中三个 trade port 在
     merge base 里**根本不存在**（`git cat-file -e` 证实），实际是 **add/add**。
     **类型标签不可信，结论可信** —— 结论的依据是「main 侧 blob 出现在集成分支历史里」，
     不依赖 merge base。

2. **`api-proxy.ts` 的两层 alternation 不存在被吞分支的风险**：
   本轮新增的是 **2 个全新 `services[]` 元素**（`purchase-order-editor` 与打样样品支），
   不是往既有 trade 分支的超长单行正则里并分支 ⇒ 外层仓库段与内层服务组
   都整体来自新侧，两层都不需要拼接。

3. **台账合并后必须重新生成**：合并驱动按「模块 ID」求并集（折叠 465 行同 ID 记录），
   但总览 / 分阶段 / 分系统三张**汇总表**不是模块 ID 行，走的是整行去重兜底 ⇒
   main 侧的旧计数（已交付 280）被整行留下，与源分支的新计数（282）并存且**与自己的模块行矛盾**，
   `sync.mjs --check` 因此报红。
   处置：跑一次 `node tools/module-progress/sync.mjs` 重生成，并**证明它只改派生汇总区**：
   重生成前后 465 条模块行逐条未变，重生成后与源分支台账**全文件零差异**。
   重生成后 `sync.mjs --check` 转绿。

4. **契约产物是纯顺序差异，不是内容差异**：4 个 OpenAPI JSON 的规范化哈希与源分支完全相同，
   4 个 `contracts/*.ts` 的导出名集合与正文行集合与源分支完全相同（两侧差集都是 0），
   但字节不同 —— 合并驱动重排了导出顺序。**按字节差判断「有没有丢内容」会误判。**

5. **本地跑了全量后端验证**，因为本轮冲突解法动到了后端业务代码与迁移脚本，
   属于协议第 5.1 节列出的必须本地跑全量的例外情形。

## 验证结果

| 项目 | 结果 |
| ---- | ---- |
| 后端 `mvnw verify`（8 个模块） | **BUILD SUCCESS** · 7136 例 · 失败 0 · 错误 0 · 跳过 0 |
| 新增后端测试类 | 5 个全部实跑（含采购单创建编辑与打样样品的**租户隔离**集成测试） |
| 前端 `pnpm typecheck` | 3/3 包绿 |
| 前端 `pnpm lint` | 3/3 包绿（仅既有 warning） |
| 前端 `pnpm test` | 395 文件 / **5980 例全绿** |
| 前端 `pnpm test:codegen` | 17 例 OK |
| 前端 `pnpm --filter @starcon/web build` | 绿 |
| 白名单放行与反向断言 | 4 个白名单测试文件 **277 例全绿**（真跑 proxy 断言状态码 + 反向 404） |
| 台账 `sync.mjs --check` | 重生成后绿 · 模块行恒为 **465** · 重复 ID **0** |
| 两个新迁移的静态预检 | 无 `ADD COLUMN` 无默认值 / `SET NOT NULL` / `CHECK` 收紧形状 |

### 两项本地跑不出来的验证（如实记录）

- **`spotless:check`（后端格式）**：本地失败，根因是
  `NoSuchMethodError: com.sun.tools.javac.util.Log$DeferredDiagnosticHandler.getDiagnostics()`
  —— spotless-maven-plugin 2.43.0 与本机 **JDK 25** 的 javac 内部 API 不兼容。
  **在未改动的 `upstream/main` 检出上复现出逐字相同的失败** ⇒ 与本次合入无关。
  CI 用 Temurin **21** 跑同一命令，该项由 CI 判定。
- **`pnpm contracts:generate`**：需要后端服务在跑才能取 OpenAPI spec，本地无服务实例 ⇒ 跑不了。
  替代取证：合并侧契约产物与源分支**内容集合完全相同**（只差导出顺序）。

### 格式债的归属（新增 8 个文件，仍是同一个失败作业）

`pnpm format:check` 在 `upstream/main` 基线上就红：**484 个文件**。
合并后是 **492 个**，**多出的 8 个全部来自集成分支**（打样样品与采购单编辑相关的前端文件，
以及 4 篇集成分支侧的会话文档）⇒ 流水线推分支前没有跑过 `pnpm format:check`。
该作业的失败输出是**一条聚合消息**，没有逐文件用例标识 ⇒ 基线相对判据按作业粒度比较，
两侧同为失败、不构成新增失败。**没有为了让格式检查变绿而去改集成分支的代码**。

## 涉及文件

- `apps/web/src/lib/api-proxy.ts` —— 白名单新增 2 个元素（冲突，取集成分支侧）
- `backend/services/trade/src/main/java/com/starcon/trade/{ReplenishmentCatalogPort,StockoutInventoryPort,SupplierPartyQueryPort}.java`
  —— 冲突，取集成分支侧
- `docs/product/capability-blueprint/module-progress.md` —— 合并驱动按模块 ID 求并集后重新生成汇总区
- `packages/api-contracts/src/**` —— 8 个契约产物由合并驱动求并（内容等价，仅导出顺序不同）
- 后端 trade 服务：采购单创建编辑与打样样品的控制器 / 服务 / 模型 / 迁移 / 测试
- 前端：采购单新建与编辑页、打样与样品工作台与模型、两份白名单测试

## 关键 trade-off / 讨论

- **为什么不把本地全量 L2 跳掉**：协议第 5.1 节的例外条款明确写了「冲突解法动到了后端业务代码 /
  迁移脚本」必须本地跑全量。本轮三个冲突文件正是 trade 服务的业务代码 ⇒ 例外成立，
  不能沿用上一轮「合并结果与集成分支逐字节相同所以不用重跑」的结论。**跑完之后后端全绿**。
- **为什么 spotless 的失败不阻塞**：先用「在未改动的 main 检出上复现」把失败归因到本机 JDK 版本，
  再交回 CI 的 Temurin 21 判定。**不把环境问题当成回归，也不为了让它变绿去改代码。**
- **为什么不修 Prettier 格式债**：那 8 个新文件的格式债来自集成分支，属于流水线侧的职责；
  在同步 PR 里替它格式化会让 PR 分支偏离集成分支的实际内容，下一轮同步还要再冲一次冲突。

## 后续待办

1. 集成分支侧的 8 个新文件仍有 Prettier 格式债，`pnpm format:check` 在 main 上仍是红的。
   按仓库级债务处理，不要在同步任务里夹带修复。
2. 本机只有 JDK 25，后端 `spotless:check` 只能由 CI（Temurin 21）判定。
   要在本地跑通它需要装 JDK 21。
3. `main` 的 CI 基线当前有 4 个红作业（Java 集成 / Lint / Security Scan / Catalog 端到端与契约），
   同步任务按基线相对判据推进。

## 关联

- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` —— 本任务的执行协议
- `docs/sessions/2026-10/2026-10-21-0930-main-sync-baseline-refresh-merge.md` —— 上一轮同步 PR 记录
- `docs/sessions/2026-10/2026-10-21-1720-main-sync-conflict-4-trade-ports.md` —— 反向同步任务同一批冲突的独立记录
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）
