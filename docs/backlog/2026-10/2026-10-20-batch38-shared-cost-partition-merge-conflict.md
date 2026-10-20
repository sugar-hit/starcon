# batch-38 预判（并已实测命中）：api-proxy.ts 与共享分区文件的多车道合并冲突（2026-10-20 23:1x–23:4x 取证）

**优先级：P0 —— 已实际发生。23:00 tick 的 `merge-ready` 把 3 条车道标成了 `conflict`。**

## 实测结果

batch-38 派发的 4 条车道，23:00 tick 回收时 **1 条干净合入、3 条冲突**：

| 车道 | 结果 | 冲突文件 |
| --- | --- | --- |
| 5.2.2.1 汇率查询 | ✅ 已并入（`006b3201`） | —— |
| 5.2.1.3 成本分摊 | ❌ `conflict` | `cost-composition-model.test.ts`、`cost-composition-workspace.tsx`、**`api-proxy.ts`** |
| 5.2.3.1 平台费用 | ❌ `conflict` | **仅 `api-proxy.ts`** |
| 5.3.1.1 利润测算器 | ❌ `conflict` | **仅 `api-proxy.ts`** |

台账 `module-progress.md` 与 `packages/api-contracts/**` 都**自动合上了**（有 `merge=contract-union` 驱动器），不是问题源。

## 根因①：`apps/web/src/lib/api-proxy.ts` —— 共享白名单累积文件（3 条冲突里 2 条只栽在这）

三条车道**各自在 `services[]` 数组末尾追加了一条自己的元素**（这正是任务书要求的做法），
但它们都基于**同一个旧基线** `4983a9cc` ⇒ 三条分支对数组同一段的追加**互相重叠** ⇒ 顺序合入时逐个撞。

`git merge-tree` 预演（对当时的 head `d6084add`）说「5.2.2.1 / 5.3.1.1 干净可合」——
**那一刻是对的**：它们和 5.2.1.3 的三条追加在数组里恰好不相邻。
但 **5.2.2.1 先落地**之后，head 里多了一条 `exchange-rates` 元素，
后面两条的追加位置就与它相邻了 ⇒ 预演结论**随并入顺序失效**。
**教训：并发车道互相追加同一个数组时，「预演一遍」不能替代「按实际并入顺序逐条预演」。**

### 解决后的验收标准（可直接照着查）

| 检查 | 必须满足 |
| --- | --- |
| `services[]` 元素总数 | **84**（当前 head 是 81，+3 条新车道的元素） |
| 四个新前缀都在 | `cost-allocations`、`exchange-rates`、`platform-fees`、`profit-calculator` **一个都不能少** |
| 全量 allowlist 回归 | `pnpm vitest run apps/web/src/app/api` **全绿** |
| 既有元素 | 既有条目**一字未动**（只许在数组末尾追加） |

⚠️ **漏掉任何一条 = 静默删掉一个已交付模块的路由**，
而 typecheck / lint / vitest / Playwright **全绿**、页面表现是 404。
这就是本仓反复踩的那个坑，**必须逐条核对元素数与前缀，不能只看「合上了」**。

## 根因②：同一个 L3 锚点下的兄弟模块共用页面路由 + 共用分区数组（只影响 5.2.1.3）

`/pricing/cost` 被 5.2.1.1（已交付）、5.2.1.2（已并入 `d6084add`）、5.2.1.3（本批）共用，
三者都改同一处：

| 文件 | 5.2.1.2（已并入） | 5.2.1.3（待并入） |
| --- | --- | --- |
| `cost-composition-model.ts` | +7 / -（`COST_SECTIONS` 加自己那条） | +8 / -（同一数组加自己那条） |
| `cost-composition-workspace.tsx` | +9（import + 渲染） | +4（import + 渲染） |
| `cost-composition-model.test.ts` | +23 / -（断言哪个分区未交付） | +19 / -（同一断言） |

**解决后的验收标准**：`COST_SECTIONS` 里**同时保留 5.2.1.2 与 5.2.1.3 两条分区**，
workspace 两个分区都渲染；测试里「未交付」断言改成
「5.2.1.2 与 5.2.1.3 都已交付，且**不得**再把已交付的写成未交付」。
修复只许碰**这两个文件**，**不许**重写 5.2.1.1 的页面骨架。

## 下一轮（00:00 tick）的正确顺序

1. **先派一个只解这 3 条冲突的修复车道**，不要先跑 `merge-ready`（现在跑必然 exit 2）：
   把 `api-proxy.ts` 解成 **84 个元素、四个新前缀齐全**，
   把 `cost-composition-{model.test.ts,workspace.tsx}` 解成「两个分区都在」。
   修复车道**必须跑** `pnpm vitest run apps/web/src/app/api`（全量 allowlist 回归）。
2. 修完 commit 回各自车道分支（或新分支后合回）。
3. 再跑 `merge-ready` ⇒ 应当干净合入 ⇒ L2 ⇒ 说明/commit/push。

## 结构性解法（**不是本轮该做的事，记下来待决**）

- **`api-proxy.ts` 改用并集式合并驱动**（像 `packages/api-contracts/**` 那样挂 `merge=contract-union`），
  或者让每个模块的路由白名单放在**自己的文件**里、由一个汇总文件引用 ——
  追加就不重叠了。
- **同 L3 的兄弟模块换独立页面路由**（蓝图允许每个模块一个路由；
  5.2.1.1 当时的「三个模块各占一个独立分区」是**当时的约定**，不是蓝图强制）。
- 任务书层面的教训：**派发前不能只「预演一遍」**，
  要**按 lane 的实际并入顺序**逐条预演（`git merge-tree --write-tree HEAD feat/<b>` 依次跑）。

## 证据

- 实测：`node tools/module-progress/lane.mjs status` → 5.2.1.3 / 5.2.3.1 / 5.3.1.1 `state=conflict`；
  5.2.2.1 `state=merged`（`006b3201`）。
- 逐条预演：`git merge-tree --write-tree --name-only HEAD feat/5-2-1-3|feat/5-2-3-1|feat/5-3-1-1`
  → 冲突文件如上表。
- 元素计数：head `apps/web/src/lib/api-proxy.ts` = **81** 个 `prefix:` 元素，
  含 `exchange-rates`；三条分支各 78 个（基线更旧），
  三条各含自己的新前缀（`cost-allocations` / `platform-fees` / `profit-calculator`）。
- 车道头：`5.2.1.3=ec1d3701`、`5.2.2.1=4a314108`、`5.2.3.1=824e4ecf`、`5.3.1.1=19a5d677`。
