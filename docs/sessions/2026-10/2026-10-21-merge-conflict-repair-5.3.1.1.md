# 2026-10-21: 5.3.1.1 利润测算器 —— 并入冲突修复（merge-ready 干净并入）

> 车道 `feat/5-3-1-1`，worktree `.worktrees/5.3.1.1`，并入基线 `integration/module-dev`（`3ea1d108`）。
> **纯并入冲突修复**：无新功能、无 Flyway 迁移、无后端改动、无契约改动。

## 摘要

本车道（5.3.1.1 利润测算器）的成果早已写完，但并入集成头时撞了 `apps/web/src/lib/api-proxy.ts`
这一个冲突（共享白名单累积文件）。本轮把集成头并进车道分支并解掉冲突，
使调度器下一轮 `merge-ready` 能**干净并入**。

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

### 契约与台账：走并集驱动器，无需手工干预

本车道并入时 `packages/api-contracts/src/**` 与台账 `docs/product/capability-blueprint/module-progress.md`
由仓库自带的并集合并驱动器处理（合并输出：`merge-union: 走了行级兜底并集` /
`merge-union: 台账按模块 ID 求并集`）。已核对合并结果是**两侧的超集**：

- `packages/api-contracts/src/contracts/catalog.ts`：合并后 686 个导出类型，
  集成头 671 个、本车道 639 个 ⇒ 相对两侧**零缺失**。
- 台账：`5.2.1.3` / `5.2.2.1` / `5.2.3.1` / `5.3.1.1` 四个模块行**都在**。

## 关键 trade-off

- **为什么不走「三方合并 + 手工调顺序」**：三方合并的结果依赖合并顺序与 git 的 hunk 切分，
  三条车道各跑一遍就会得到三份**不同**的文件 ⇒ 下一轮照样互撞。确定性构造 + 逐字对拍是唯一可靠解。
- **为什么不把新元素并进既有 `catalog/` 长正则**：那一支是单行超长正则，
  多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。新路由各占**独立元素**。
- **本车道除 `api-proxy.ts` 外无其它冲突**：利润测算器走 `/pricing/calculator` 路由，
  不共享 `/pricing/cost` 的分区骨架（那是 5.2.1.1/5.2.1.2/5.2.1.3 三兄弟的共享文件），
  所以不存在「哪个兄弟还没交付」这类陈旧断言要更正。

## 涉及文件

- `apps/web/src/lib/api-proxy.ts` —— 解冲突（108 条元素）
- `docs/sessions/2026-10/2026-10-21-merge-conflict-repair-5.3.1.1.md` —— 本文件

（其余并入内容为集成头侧原样带入的既有文件，未改动。）

## 验证

L0/L1 实跑（`pnpm typecheck` / `pnpm lint` / vitest）全绿：

| 命令 | 结果 |
| --- | --- |
| `pnpm typecheck` | 3 tasks successful，exit 0 |
| `pnpm lint` | 3 tasks successful，exit 0（仅既有无关 warning） |
| `pnpm vitest run apps/web/src/app/api` | **110 文件 / 645 例全绿** |
| `pnpm vitest run apps/web/src/components/pricing apps/web/src/app/pricing/cost` | **6 文件 / 112 例全绿** |

`api-proxy.ts` 核对：`services[]` = 108 条；`cost-allocations` / `exchange-rates` / `platform-fees` / `profit-calculator` 四个 token 全在。

## 后续待办

- 调度器下一轮顺序 `merge-ready` 并入 `feat/5-2-1-3` → `feat/5-2-3-1` → `feat/5-3-1-1`，
  三条分支的 `api-proxy.ts` 逐字相同 ⇒ 应干净合并。
- `apps/web/src/lib/api-proxy.ts` 这个共享累积文件**结构性易撞**已复发多次
  （见 `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`）：
  建议给它也配一个「按元素 token 求并集」的合并驱动器，从根上消除这类冲突。

## 关联

- `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`
- `docs/sessions/2026-10/2026-10-21-merge-conflict-repair-5.2.1.3.md`
- `docs/sessions/2026-10/2026-10-21-merge-conflict-repair-5.2.3.1.md`
