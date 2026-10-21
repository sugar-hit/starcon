# 2026-10-21: 5.2.1.3 成本分摊 —— 并入冲突修复（merge-ready 干净并入）

> 车道 `feat/5-2-1-3`，worktree `.worktrees/5.2.1.3`，并入基线 `integration/module-dev`（`3ea1d108`）。
> **纯并入冲突修复**：无新功能、无 Flyway 迁移、无后端改动、无契约改动。

## 摘要

本车道（5.2.1.3 成本分摊）的成果早已写完，但并入集成头时撞了三处冲突：
`apps/web/src/lib/api-proxy.ts`（共享白名单累积文件）+
`cost-composition-workspace.tsx` + `cost-composition-model.test.ts`。
本轮把集成头并进车道分支并解掉冲突，使调度器下一轮 `merge-ready` 能**干净并入**。

## 决策摘要

### 根因

`apps/web/src/lib/api-proxy.ts` 是**共享白名单累积文件**（`services[]` 数组，每条车道在末尾追加一条自己的元素）。
本仓只给 `packages/api-contracts/src/**` 与台账配了并集合并驱动器，**这个文件没有** ⇒ 走普通 git 三方合并。
三条车道基于**同一个旧基线** `4983a9cc` ⇒ 对数组同一段的追加**互相重叠** ⇒ 顺序合入时逐个撞。

### api-proxy.ts 的解法：三条车道必须**逐字完全相同**

2026-10-21 实测过：让一边是另一边的**超集**照样冲突，**只有逐字相同才干净合并**。
所以目标文件不是「三方合并的结果」，而是**确定性构造**出来的：

> 目标文件 = 当前集成头版本的 `api-proxy.ts`（105 条元素）**+ 末尾追加 3 条新元素（顺序固定）**

| 顺序 | 元素 token | 来源分支 |
| --- | --- | --- |
| ① | `cost-allocations` | `feat/5-2-1-3` |
| ② | `platform-fees` | `feat/5-2-3-1` |
| ③ | `profit-calculator` | `feat/5-3-1-1` |

三个元素块**整块**取自各自分支（连同其上方注释行），**既有 105 条一字未动**，
最终 `services[]` 元素总数 = **108**。三条车道分支的该文件因此逐字完全相同 ⇒ 下一轮顺序 `merge-ready` 互不冲突。

⚠️ **漏掉任何一条 = 静默删掉一个已交付模块的路由**：`typecheck` / `lint` / `vitest` / `Playwright` **全绿**，
页面表现是 404 —— 所以 `pnpm vitest run apps/web/src/app/api`（全量 allowlist 回归）是必跑项。

### 5.2.1.2 与 5.2.1.3 两个分区都在

语义固定为「**5.2.1.2 到岸成本与 5.2.1.3 成本分摊两个分区都在**」：

- `cost-composition-model.ts` —— git 自动合上，**已核对**：`COST_SECTIONS` 同时有
  5.2.1.1 / 5.2.1.2 / 5.2.1.3 三条分区，5.2.1.2 的 `delivered = true`。
  （5.2.1.2 已并入并交付；本车道分支里那份「5.2.1.2 未交付」是它基于旧基线开发的**陈旧副本**，
  合并时必须以集成头为准。）
- `cost-composition-workspace.tsx` —— **两个分区都渲染**：保留集成头的 5.2.1.2 `<LandedCostWorkspace />`，
  同时保留本车道新增的 5.2.1.3 `<CostAllocationSection />`。**未重写 5.2.1.1 的页面骨架**。
- `cost-composition-model.test.ts` —— 断言改成「**5.2.1.2 与 5.2.1.3 都已交付，且不得再把已交付的写成未交付**」。
  本车道分支里那条「5.2.1.2 **未交付**」的断言是**陈旧的**，已改成「已交付」。

## 关键 trade-off

- **为什么不走「三方合并 + 手工调顺序」**：三方合并的结果依赖合并顺序与 git 的 hunk 切分，
  三条车道各跑一遍就会得到三份**不同**的文件 ⇒ 下一轮照样互撞。确定性构造 + 逐字对拍是唯一可靠解。
- **为什么不把 3 条新元素并进既有 `catalog/` 长正则**：那一支是单行超长正则，
  多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。三条新路由各占**独立元素**。
- **为什么 `apps/web/src/app/pricing/cost/cost-composition-page.test.ts` 一字未动**：
  它已由 `fix/5-2-1-2-cost-page-assert` 车道改成**数据驱动的不变量断言**（对全体分区成立），
  本轮只核对它与集成头**逐字相同**（shasum `20ec315b…`），没有再改。

## 涉及文件

- `apps/web/src/lib/api-proxy.ts` —— 解冲突（108 条元素）
- `apps/web/src/components/pricing/cost-composition-workspace.tsx` —— 解冲突（两个分区都渲染）
- `apps/web/src/components/pricing/cost-composition-model.test.ts` —— 解冲突（断言改为「都已交付」）
- `apps/web/src/components/pricing/cost-composition-model.ts` —— git 自动合上，已核对
- `docs/sessions/2026-10/2026-10-21-merge-conflict-repair-5.2.1.3.md` —— 本文件

## 验证

L0/L1 实跑（`pnpm typecheck` / `pnpm lint` / vitest）全绿：

| 命令 | 结果 |
| --- | --- |
| `pnpm typecheck` | 3 tasks successful，exit 0 |
| `pnpm lint` | 3 tasks successful，exit 0（仅既有无关 warning） |
| `pnpm vitest run apps/web/src/app/api` | **110 文件 / 645 例全绿** |
| `pnpm vitest run apps/web/src/components/pricing apps/web/src/app/pricing/cost` | **6 文件 / 107 例全绿** |

`api-proxy.ts` 核对：`services[]` = 108 条；`cost-allocations` / `exchange-rates` / `platform-fees` / `profit-calculator` 四个 token 全在。

## 后续待办

- 调度器下一轮顺序 `merge-ready` 并入 `feat/5-2-1-3` → `feat/5-2-3-1` → `feat/5-3-1-1`，
  三条分支的 `api-proxy.ts` 逐字相同 ⇒ 应干净合并。
- `apps/web/src/lib/api-proxy.ts` 这个共享累积文件**结构性易撞**已复发多次
  （见 `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`）：
  建议给它也配一个「按元素 token 求并集」的合并驱动器，从根上消除这类冲突。

## 关联

- `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`
- `docs/backlog/2026-10/2026-10-20-batch38-shared-cost-partition-merge-conflict.md`
- `docs/sessions/2026-10/2026-10-21-cost-page-assert-fix.md`
