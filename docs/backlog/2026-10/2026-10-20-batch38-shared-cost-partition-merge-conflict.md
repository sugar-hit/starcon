# batch-38 预判：5.2.1.3 × 5.2.1.2 的共享前端分区合并冲突（2026-10-20 23:1x 取证）

**优先级：P0 —— 下一轮 `merge-ready` 会命中它。已用 `git merge-tree` 预演确认，不是猜测。**

## 现象

batch-38 派发的 4 条车道里，3 条已 `ready`（5.2.1.3 / 5.2.2.1 / 5.3.1.1），1 条在跑（5.2.3.1）。
其中 **5.2.1.2 到岸成本**（batch-37 遗留下来、23:00 tick 回收的）**已经并进集成头**（`d6084add`），
而 **5.2.1.3 成本分摊** 与它**同属 L3 锚点 5.2.1、同一个页面路由 `/pricing/cost`**。

预演结果（`git merge-tree --write-tree integration/module-dev feat/5-2-1-3`）：

```
CONFLICT (content): Merge conflict in apps/web/src/components/pricing/cost-composition-model.test.ts
Auto-merging apps/web/src/components/pricing/cost-composition-workspace.tsx
CONFLICT (content): Merge conflict in apps/web/src/components/pricing/cost-composition-workspace.tsx
Auto-merging apps/web/src/components/pricing/cost-composition-model.ts      ← 这个自动合上了
```

⇒ **2 个文件冲突、1 个自动合上**。`5.2.2.1` 与 `5.3.1.1` 两条**干净可合**（无冲突输出）。

## 双方各自改了什么

| 文件 | 5.2.1.2（已并入 `d6084add`） | 5.2.1.3（在 `feat/5-2-1-3`） |
| --- | --- | --- |
| `cost-composition-model.ts` | +7 / -（`COST_SECTIONS` 加自己那条） | +8 / -（同一数组加自己那条） |
| `cost-composition-workspace.tsx` | +9（import + 渲染） | +4（import + 渲染） |
| `cost-composition-model.test.ts` | +23 / -（断言哪个分区未交付） | +19 / -（同一断言） |

**根因是同一个文件的同一处**：`COST_SECTIONS` 数组（两个车道各追加一条）+
workspace 的 import/渲染清单（各加一行）+ 测试里「哪些分区还是未交付」的断言（各改一次）。

## 后果与正确处置

`lane.mjs merge-ready` 遇冲突会**退出码 2** 并把车道标成 `conflict`，
按协议**不许调度者自己强行解冲突** ⇒ 5.2.1.3 的成果（V224 + 后端 + 契约 + 页面 + 48 例测试）会被卡住一轮。

**下一轮（00:00 tick）的正确顺序是**：

1. **先派一个只解这一个冲突的修复车道**（不要先跑 `merge-ready`）：
   把 `feat/5-2-1-3` 分支上那两个文件的冲突解掉 —— 语义上就是
   **`COST_SECTIONS` 里同时保留 5.2.1.2 与 5.2.1.3 两条分区**，
   workspace 两个分区都渲染，测试里「未交付」断言改成
   「5.2.1.2 与 5.2.1.3 都已交付，且**不得**再把已交付的写成未交付」。
   修完 commit 到 `feat/5-2-1-3`（或新分支后合回该分支）。
2. 再跑 `merge-ready` ⇒ 应当干净合入。
3. 修复车道只许改**这两个文件** + 它自己的测试断言，**不许**重写 5.2.1.1 的页面骨架。

## 根因（值得记，因为它会重复发生）

**同一个 L3 锚点下的两个兄弟模块共用一个页面路由 + 一个分区数组 + 一个共享测试文件，
而车道是并行写的。** 本轮任务书已经写了「共享文件只做最小改动、`COST_SECTIONS` 追加在数组末尾」，
但**追加在末尾仍会撞** —— 因为两个车道追加的是**同一个数组的同一段**，git 看到的是重叠的上下文。

⇒ 结构性解法（**不是本轮该做的事，记下来待决**）：

- 让兄弟分区各自独立成文件，由 `COST_SECTIONS` 只存**文件名**而不是组件本体（这样追加行不重叠）；
- 或者给同 L3 的第二条车道**换页面路由**（蓝图允许每个模块一个独立路由，
  5.2.1.1 当时的注释说「三个模块各占一个独立分区」是**当时的约定**，不是蓝图强制）。

## 证据

- 预演：`git merge-tree --write-tree --name-only integration/module-dev feat/5-2-1-3` → 2 个 CONFLICT。
- 车道状态：5.2.1.3 `ready`（head `ec1d3701`）、5.2.1.2 已并入（`d6084add`）。
- 涉及文件：`apps/web/src/components/pricing/cost-composition-{model.test.ts,model.ts,workspace.tsx}`。
