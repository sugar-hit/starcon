# 2026-10-24 既有文件的排版退化：「新增文件合规」不等于「没有退化」

创建：2026-10-24。发现人：main 同步任务（并入 `main` 的闸门，第 65 轮）。
影响范围：**前端既有组件文件**（`apps/web/src/components/**`、`apps/web/src/lib/api-proxy.ts`、`packages/ui/**`）。

本条是一条**方法论缺口**：只看「PR 新增了哪些不合规文件」会漏掉「既有文件从合规变成不合规」这一类劣化。

## 现象

本轮 PR 的 Lint 作业排版债对账：

| 口径              | 数量              |
| ----------------- | ----------------- |
| `main` 基线不合规 | 646               |
| PR 侧不合规       | 763               |
| PR 独有           | 117               |
| 基线独有          | 0                 |
| 对账              | 646 + 117 = 763 ✓ |

按「只看新增文件」的口径，117 个全是新增文件 ⇒ **看起来零退化**。

⚠️ **但逐个核对后发现：其中 5 个是 `main` 上已存在的既有文件**，它们在 `main` 上**合规**、
在本 PR 上**不合规** ⇒ **真实退化 5 个**。

| 文件                                                                            | main 侧 | PR 侧 |
| ------------------------------------------------------------------------------- | ------- | ----- |
| `apps/web/src/components/catalog/catalog-workspace.tsx`                         | CLEAN   | DIRTY |
| `apps/web/src/components/warehouse/warehouse-outbound-timeliness-workspace.tsx` | CLEAN   | DIRTY |
| `apps/web/src/lib/api-proxy.ts`                                                 | CLEAN   | DIRTY |
| `packages/ui/src/components/common/command-palette.tsx`                         | CLEAN   | DIRTY |
| `packages/ui/src/components/layout/app-shell.tsx`                               | CLEAN   | DIRTY |

## 根因：集成侧的新交付改动了这些既有文件，且新写的代码没按仓库的 Prettier 规范排版

这 5 个文件在**集成侧与 PR 侧逐字节相同** ⇒ 违规由集成侧引入，不是合并动作造成的。

具体形态是**折行宽度不符**：仓库 `.prettierrc` 是 `printWidth: 100`，
而集成侧新写的 JSX 用了更窄的折行（属性列表、参数列表被拆成多行），
Prettier 会把它们**合并回单行**（只要合并后不超 100 列）。

⇒ 表现为「集成侧原样就 DIRTY，仓库格式化器归一化后 CLEAN」。

## 判据（可复算）

**不要用 `prettier --check <path>`** —— 被 gitignore 排除的路径会被**静默跳过**，产生假绿灯。
必须用 **stdin 管道**：

```bash
git show "upstream/main:$f" > /tmp/_m.txt
git show "upstream/chore/sync-module-dev:$f" > /tmp/_p.txt
node_modules/.bin/prettier --stdin-filepath "$f" < /tmp/_m.txt | diff -q - /tmp/_m.txt   # main 是否合规
node_modules/.bin/prettier --stdin-filepath "$f" < /tmp/_p.txt | diff -q - /tmp/_p.txt   # PR 是否合规
```

⇒ **两侧都测**：只测一侧得不出「退化」的结论。

## 为什么这个口径不能省

`基线 + 新增 == PR 总数` 这个等式**只有在「既有文件零退化」时才平**。
等式对不上时，**缺口本身就是退化清单**。

本轮：646 + 117 = 763，**等式是平的** —— 但那 5 个既有文件的退化被「PR 独有」这个口径吸收掉了
（它们既不在基线清单里，也不在「新增文件」这一类里，而是混在 117 这个数字里）。
⇒ **必须逐个核对，不能只看计数。**

## 处置口径

- **修在同步分支上是允许的**，前提是：
  - 改动**纯粹来自仓库自己的格式化器**（`prettier --write` / `spotless:apply`）；
  - 语义等价性**逐文件证明**（本轮用「标识符与字符串字面量序列完全不变」来证明）；
  - 不改业务逻辑、不改断言、不改文案。
- 本轮该 5 个文件的修正：**标识符与字符串字面量序列逐文件完全相同**
  （折行合并只影响 JSX 属性列表与函数参数列表的断行位置）。
  修正后前端 **539 个测试文件 / 7351 条断言全绿**。
- ⚠️ **集成侧后续修复时不必再解一次冲突** —— 同步分支上的排版修正是格式化器产物，
  集成侧只要也用同一个格式化器，两边自然一致。

## 影响面

- 上述 5 个文件所在的前端模块（商品工作台、仓库出库时效工作台、全局搜索面板、应用外壳）。
- 本条是**通用方法论**：任何「PR 新增大量前端交付」的合并都要按这个口径核。

## 完成标准

- PR 侧与 `main` 基线的排版债对账中，**既有文件的退化数为 0**。
- 对账方式：逐个核对 PR 独有清单里的每个文件在 `main` 上是否存在且当时是否合规。

## 状态

- **已在同步分支修正**（第 65 轮）。集成侧应在下批交付前统一跑一次格式化。
