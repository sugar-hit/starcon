# `apps/web/src/lib/api-proxy.ts` 共享白名单累积文件 —— 并入冲突的结构性根因与两条解法（2026-10-21 取证）

**优先级：P0 —— 这是当前吞吐的**首要瓶颈**，不是理论风险。每批只要有 ≥2 条车道改 `api-proxy.ts`，就必然产生 N-1 条并入冲突。**

**状态：待决（等用户拍板）。本轮只做了「机械解冲突」，没有改合并驱动器的行为。**

---

## 现象（本轮实测，3 条车道全栽在这一个文件上）

batch-38 派发的 4 条车道的成果在 2026-10-20 23:00 tick 回收时：

| 车道 | 结果 | 冲突文件 |
| --- | --- | --- |
| 5.2.2.1 汇率查询 | ✅ 并入 `006b3201` | —— |
| 5.2.1.3 成本分摊 | ❌ `conflict` | `cost-composition-model.test.ts`、`cost-composition-workspace.tsx`、**`api-proxy.ts`** |
| 5.2.3.1 平台费用 | ❌ `conflict` | **仅 `api-proxy.ts`** |
| 5.3.1.1 利润测算器 | ❌ `conflict` | **仅 `api-proxy.ts`** |

台账 `module-progress.md` 与 `packages/api-contracts/**` 都**自动合上了**（有 `merge=contract-union` 驱动器），**不是问题源**。

## 根因

`.gitattributes` 只给这两个路径配了并集合并驱动器：

```
packages/api-contracts/src/**/*.ts            merge=contract-union
packages/api-contracts/src/*.openapi.json      merge=contract-union
docs/product/capability-blueprint/module-progress.md  merge=contract-union
```

**`apps/web/src/lib/api-proxy.ts` 不在名单里** ⇒ 走普通 git 三方合并。

而它恰恰是「共享白名单累积文件」：`services[]` 数组，每条车道按任务书要求在**数组末尾追加一条自己的元素**。
各条车道**基于同一个旧基线** `4983a9cc` ⇒ 对数组同一段的追加**互相重叠** ⇒ 顺序合入时逐个撞。

### 实测的元素数（**不是旧文档里写的 84 / 81，那两个数已过期**）

| 状态 | `services[]` 元素数 | 怎么数的 |
| --- | --- | --- |
| 基线 `4983a9cc` | **101** | `git show <ref>:apps/web/src/lib/api-proxy.ts` → 截 `const services` 到 `\n];` → 数 `prefix: '` |
| batch-38 的 4 条车道**各自**的分支 | **102**（每条 +1） | 同上 |
| 并入本批 4 个模块后的 head | **105** | + review-requests / price-anomaly-alerts / landed-costs / exchange-rates |
| **解完 3 条冲突后的目标** | **108** | + cost-allocations / platform-fees / profit-calculator |

⇒ 三条分支的 `api-proxy.ts` **都是纯追加**（各 +1 条、0 删除），
冲突纯粹是**插入位置重叠**，语义上**无歧义** ⇒ 完全可以机械求并。

### ⚠️ 失败形状：漏掉任何一条 = 静默删掉一个已交付模块的路由

typecheck / lint / vitest / Playwright **全绿**，页面表现是 **404**。
这是本仓反复踩的那个坑，**必须逐条核对元素数与四个新前缀，不能只看「合上了」**。

---

## 🔬 一条关键实验结论：**只有「两边逐字相同」才能干净合并**

2026-10-21 在临时 git 仓库里做了两组 `git merge-tree` 预演（`ours` / `theirs` 都只改同一个数组末尾）：

| 场景 | 结果 |
| --- | --- |
| A：`ours` = base+3 条，`theirs` = base+4 条（**前 3 条与 ours 逐字相同**） | ❌ **CONFLICT** |
| B：`ours` = base+3 条，`theirs` = base+1 条（不同内容） | ❌ **CONFLICT** |
| C：`ours` = base+3 条，`theirs` = **base+3 条，逐字完全相同** | ✅ **干净合并** |

⇒ **「让并入方是当前方的超集」并不能避免冲突** —— 这一点与直觉相反，是本轮最贵的实验结论。
⇒ **唯一可靠的机械解法：把并入的每一条车道分支的 `api-proxy.ts` 解成**同一个逐字相同的版本**，
这样无论并入顺序如何都命中场景 C。

本轮派发的修复车道就是这么做的（把三条分支都解成 108 元素的同一份文件）。

---

## 两条结构性解法（**待决，本轮不做**）

### 解法 A：给 `api-proxy.ts` 也配一个按「元素」求并的合并驱动器（推荐）

- 扩展 `tools/module-progress/merge-union.mjs` + `.gitattributes`，
  让 `apps/web/src/lib/api-proxy.ts` 也走 `merge=contract-union`。
- **求并的键 = `services[]` 元素的 `pattern` 正则源文本**（不是 `prefix` ——
  实测 `catalog/` 前缀下已有 **5** 个元素、`channel/` **43** 个、`trade/` **51** 个，
  用 `prefix` 当键会把 5 条不同的 catalog 路由吃掉）。
- ⚠️ **解析不了必须大声失败（退出码非 0），绝不能落到「行级去重并集」兜底** ——
  那个兜底会把对象数组拼坏（本轮 merge 日志里已经能看到 `merge-union: 走了行级兜底并集` 这条告警）。
- ⚠️ 这个文件里 `pattern` 是**正则字面量**、且**上方带注释**，解析器必须能处理
  「注释 + 多行正则字面量」的形状。
- 验收：`tools/module-progress/merge-union.itest.mjs` 要补一个 api-proxy 用例，
  证明「两边各加一条不同 pattern 的元素 ⇒ 并集后两条都在，且既有元素一条不少」。

### 解法 B：让兄弟分区各自独立成文件（治 `/pricing/cost` 那条冲突）

- `COST_SECTIONS` 只存**文件名**而不是组件本体，各兄弟分区各自一个文件 ⇒ 追加行不重叠。
- 或者给同 L3 的第二条车道**换页面路由**（蓝图允许每个模块一个独立路由；
  5.2.1.1 当时的注释说「三个模块各占一个独立分区」是**当时的约定**，不是蓝图强制）。

---

## 为什么本轮不自己做

- 协议明令：**调度器绝不自己动手写代码**，代码由派发的子任务完成；
  合并驱动器的行为变更属于「编排逻辑」，上一轮已把它记为**待决**。
- 本轮已经派了 4 条子任务（4 个并发上限已满），没有余量再开一条工具链车道。
- 解法 A 有一个**静默风险**：求并键选错会把别人已交付的路由吃掉，
  而 typecheck / lint / vitest / Playwright **全绿** ⇒ 需要自带 itest 才能安全落地。

⇒ **建议下一轮（或用户指定时）单独派一条工具链车道做解法 A，并把本文件作为它的任务书。**

---

## 附：并入顺序会改变预演结论（2026-10-20 23:43 已经踩过一次）

旧 backlog 说「`git merge-tree` 预演说 5.2.2.1 / 5.3.1.1 干净可合」——
**那一刻是对的**（它们和 5.2.1.3 的三条追加在数组里恰好不相邻）。
但 **5.2.2.1 先落地**之后，head 里多了一条 `exchange-rates` 元素，
后面两条的追加位置就与它相邻了 ⇒ 预演结论**随并入顺序失效**。

⇒ **教训：并发车道互相追加同一个数组时，「预演一遍」不能替代「按实际并入顺序逐条预演」。**
