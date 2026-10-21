# 5.2.1.2 到岸成本 —— 修复 `/pricing/cost` 分区断言的跨模块回归

> 车道 `fix/5-2-1-2-cost-page-assert`，worktree `.worktrees/5.2.1.2-fix`，基线 `integration/module-dev`。
> **纯测试断言修复**：无 Flyway 迁移、无后端改动、无台账改动。

## 决策摘要

**根因不是「断言写错了」，而是断言把「哪个兄弟模块还没交付」这个会变的事实写死了。**

集成头批量验证（L2）红的唯一一条：

```
FAIL apps/web/src/app/pricing/cost/cost-composition-page.test.ts > 分区块约定 >
     未交付的兄弟分区写明「未交付」并说明口径差异
AssertionError: expected true to be false   ❯ cost-composition-page.test.ts:57:31
```

本车道交付 5.2.1.2 到岸成本时把 `COST_SECTIONS` 里 5.2.1.2 的 `delivered` 翻成 `true`、
摘要换成「已交付」口径文案（事实正确），却**漏改了兄弟模块 5.2.1.1 留下的这条共享用例** ——
它仍硬编码「5.2.1.2 是未交付分区」。当日的 L1 前端过滤范围只覆盖
`apps/web/src/components/pricing` 与 `apps/web/src/app/api`，**没覆盖 `apps/web/src/app/pricing/cost/`**，
所以漏网到集成头才爆。

⇒ 但**只把「5.2.1.2 未交付」改成「5.2.1.2 已交付」是错的**：5.2.1.3 成本分摊下一轮并入时，
它的 `delivered` 也会翻成 `true`、摘要整段换掉，同一条用例会**再次红**。
所以这条用例被改写成**对 `COST_SECTIONS` 全体分区成立的不变量**。

**改前**（硬编码「哪个兄弟还没交付」）：

```js
const landed = COST_SECTIONS.find((s) => s.moduleId === '5.2.1.2');
expect(landed?.delivered).toBe(false);
expect(landed?.summary).toContain('未交付');
expect(landed?.summary).toContain('单据级');

const allocation = COST_SECTIONS.find((s) => s.moduleId === '5.2.1.3');
expect(allocation?.delivered).toBe(false);
expect(allocation?.summary).toContain('未交付');
expect(allocation?.summary).toContain('规则表');
```

**改后**（两条用例，数据驱动）：

```js
// 用例 1：对每条分区成立的不变量
for (const section of COST_SECTIONS) {
  if (section.delivered) {
    expect(section.summary, `${section.moduleId} 已交付，摘要不得再写「未交付」`).not.toContain(
      '未交付',
    );
  } else {
    expect(section.summary, `${section.moduleId} 未交付，摘要必须写明「未交付」`).toContain(
      '未交付',
    );
  }
}

// 用例 2：只对「已交付且口径稳定」的两条分区硬断言口径关键词
expect(composition?.summary).toMatch(/SKU(\s*的|\s*级)?\s*单位成本/); // 5.2.1.1
expect(landed?.summary).toContain('单据级'); // 5.2.1.2
expect(landed?.summary).toContain('SKU 级'); // 5.2.1.2 的交叉引用
```

## 关键 trade-off

| 决策                                                    | 选择                                    | 理由                                                                                                                                                                                                                                                                                                                                                     |
| ------------------------------------------------------- | --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 断言形态                                                | **数据驱动的不变量**，不再钉死 moduleId | `delivered` 标志每随兄弟模块落地翻一次，硬编码断言就要改一次测试，而每改一次就会漏一处 —— 本次回归正是这么来的                                                                                                                                                                                                                                           |
| 未交付分区要不要断言具体关键词（`规则表`）              | **不断言**                              | 未交付分区的摘要在该模块落地时会被**整段换掉**（5.2.1.3 落地后的文案里既无「未交付」也无「规则表」）。断言它 = 下一轮必红                                                                                                                                                                                                                                |
| 「至少有一条 `delivered===false` 的分区含规则表」       | **不写**                                | 5.2.1.3 落地后 head 里**三条分区全部 `delivered: true`**，这个集合会变空 ⇒ 该断言必然失败                                                                                                                                                                                                                                                                |
| 口径关键词断言范围                                      | **只对 5.2.1.1 / 5.2.1.2 硬断言**       | 这两条的措辞不随兄弟模块落地而变（5.2.1.3 落地后仍然只有这两条已交付分区）；未交付分区的措辞会变，不进断言                                                                                                                                                                                                                                               |
| 5.2.1.1 的口径关键词怎么写                              | `/SKU(\s*的\|\s*级)?\s*单位成本/`       | **实测**：`COST_SECTIONS` 里 5.2.1.1 的 summary 是「SKU 的单位成本构成：…」，**不含字面量「SKU 级」**；「SKU 级」出现在 5.2.1.2 摘要的交叉引用里（`cost-composition-model.ts:312`）。模型文件是**事实**、本车道不可改 ⇒ 断言「SKU + 单位成本」这个口径本身，不锁死「SKU 的 / SKU 级」的措辞差异；同时在 5.2.1.2 上硬断言字面量「SKU 级」，字面量仍被守住 |
| 要不要改 `cost-composition-model.ts` 的 `COST_SECTIONS` | **不改**                                | `delivered` 标志与摘要文案是事实；本车道把 5.2.1.2 改成已交付是正确的，错的是断言                                                                                                                                                                                                                                                                        |
| 台账 `module-progress.md`                               | **不改**                                | 本车道已随 5.2.1.2 交付更新过该行，再改会与并入冲突；台账由调度者统一处理                                                                                                                                                                                                                                                                                |

## 文件清单

| 文件                                                          | 说明                                                                              |
| ------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `apps/web/src/app/pricing/cost/cost-composition-page.test.ts` | **唯一改动文件**：两条用例改写（硬编码兄弟状态 → 数据驱动不变量）+ 文件头注释同步 |

**未改动**（刻意）：`apps/web/src/components/pricing/cost-composition-model.ts`（`COST_SECTIONS` 是事实）、
`apps/web/src/components/pricing/cost-composition-model.test.ts`（本来就对）、
`apps/web/src/app/pricing/cost/page.tsx`（5.2.1.1 页面骨架）、台账。

## 验证

### 临时制造矛盾 → 新断言真的红（脚本 `.tmp/mutate-model.py`，事后已全部撤销）

| 场景 | 制造的矛盾                                                                            | 期望   | 实测                                                                                  |
| ---- | ------------------------------------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------- |
| A    | 5.2.1.2 `delivered` 翻成 `false`（摘要里没有「未交付」）                              | 红     | `Tests 2 failed \| 5 passed (7)`                                                      |
| B    | 5.2.1.3 `delivered` 翻成 `true`（摘要里还留着「未交付」）                             | 红     | `Tests 1 failed \| 6 passed (7)`，报错信息带 `5.2.1.3 已交付，摘要不得再写「未交付」` |
| C    | **模拟 5.2.1.3 完整落地**（`delivered: true` + 已交付文案，无「未交付」无「规则表」） | **绿** | `Tests 7 passed (7)` —— 证明下一轮并入不会再红                                        |
| D    | 5.2.1.2 摘要里的「单据级」被抹成「整批口径」                                          | 红     | `Tests 1 failed \| 6 passed (7)`                                                      |

场景 B 的真实报错片段：

```
AssertionError: 5.2.1.3 已交付，摘要不得再写「未交付」: expected '把混合成本的货件按重量/体积/金额/数量规则分摊到各 SKU…'
not to contain '未交付'
```

撤销后 `git status --short` 只剩目标测试文件一行，`diff` 与改动前的模型文件副本逐字相同。

### L0 + L1

| 命令                                              | 结果                                                                              |
| ------------------------------------------------- | --------------------------------------------------------------------------------- |
| `pnpm typecheck`                                  | `Tasks: 3 successful, 3 total`                                                    |
| `pnpm vitest run apps/web/src/app/pricing/cost`   | `Test Files 1 passed (1)` / `Tests 7 passed (7)`                                  |
| `pnpm vitest run apps/web/src/components/pricing` | `Test Files 4 passed (4)` / `Tests 87 passed (87)`（兄弟模型测试未被带坏）        |
| `pnpm lint`                                       | `Tasks: 3 successful, 3 total`（仅存量 warning，均在其它文件的 `useMemo` 依赖上） |
| `npx prettier --check`（目标文件）                | `All matched files use Prettier code style!`                                      |

## 后续待办

1. **5.2.1.3 成本分摊并入后，无需再动本文件** —— 不变量对「三条全已交付」同样成立（场景 C 已实测）。
   若 5.2.1.3 车道顺手改了 5.2.1.1 / 5.2.1.2 两条分区的**口径措辞**，本文件的口径断言需要跟着改
   （这是有意的硬断言：口径措辞变了就该有人确认）。
2. **本车道的 L1 前端过滤范围要补 `apps/web/src/app/pricing/cost/`** —— 这次回归漏网的直接原因。
   后续车道的 L1 过滤范围按「改动的文件所在目录」圈定，不要按「模块名」圈。
3. `pnpm vitest` 在未装依赖的 worktree 里不可用（报 `Command "vitest" not found`）——
   worktree 需要先 `pnpm install --frozen-lockfile --prefer-offline`（走共享 store，约 4 秒）。
