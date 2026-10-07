# 集成头 `catalog.ts` 重复声明 —— 阻塞级根因定位（只读诊断）

## 结论先说

**集成头 `packages/api-contracts/src/catalog.ts` 有 3 个导出名各重复声明 2 次，导致
`pnpm typecheck` 与 `next build` 在集成头就是红的。**

这不是任何单条车道引入的 bug，而是**并集合并驱动器 `merge-union.mjs` 的语义缺陷**：
它保留了同一导出名的两个不同版本，而不是按键求并。

实测（独立于子任务报告复现）：

```
catalog.ts(440,13): error TS2300: Duplicate identifier 'PhysicalSafetyInput'.
catalog.ts(442,13): error TS2300: Duplicate identifier 'PhysicalSafetyProfile'.
catalog.ts(443,13): error TS2300: Duplicate identifier 'PhysicalSafetyRow'.
catalog.ts(445,13): error TS2300: Duplicate identifier 'PhysicalSafetyInput'.
catalog.ts(446,13): error TS2300: Duplicate identifier 'PhysicalSafetyProfile'.
catalog.ts(447,13): error TS2300: Duplicate identifier 'PhysicalSafetyRow'.
```

字段构成实证（`PhysicalSafetyProfile` 两份）：

| 版本 | 字段数 | dangerousGoods | dangerousSubstanceClass | packagingVersion |
|---|---|---|---|---|
| 第 1 份（旧） | 24 | ✓ | ✗ | ✗ |
| 第 2 份（新） | 26 | ✓ | ✓ | ✓ |

已提交的 `catalog.openapi.json` 里 `PhysicalSafetyProfile` **26 个字段，新旧字段都在**
（`dangerousGoods` / `dangerousSubstanceClass` / `packagingVersion` 均为 True）。
前端 `physical-safety-model.ts` / `physical-safety-workspace.tsx` 用的正是
`dangerousSubstanceClass` 与 `packagingVersion`，后端
`PhysicalSafetyModels.java` 也确实有这两个字段。

**即：spec 是对的、后端是对的、前端是对的、生成物里有正确的那一份，
只有「两份都留着」这件事是错的。** 第 2 份（新，26 字段）才是正确的。

## 引入点：每次 merge 提交都把 dup 从 1 变成 2

逐提交追查 `packages/api-contracts/src/catalog.ts`：

```
33428da merge(3.6.3.1): 地址与联系人          dup=2
3d076db feat(web,catalog): 3.6.3.1 契约重生成…  dup=1   ← 车道分支干净
00dc04c merge(3.7.2.1): 物理与安全属性          dup=2
08cce71 docs(catalog): 3.7.2.1 重新生成契约…    dup=1   ← 车道分支干净
a8d78e5 merge(3.7.2.1): 物理与安全属性          dup=1   ← 第一次合并干净
2187a02 feat(catalog): 3.7.2.1 物理与安全属性    dup=1   ← 车道分支干净
```

**所有车道分支 dup 都是 1，只有 merge 提交后变 2。** 在 3.6.3.1 / 3.6.1.2 两个
正在跑的车道 worktree 里实测同样是 1。污染只发生在并入产物。

`git show 00dc04c -- packages/api-contracts/src/catalog.ts` 是铁证：
该合并只改了 5 行，而 diff 里同时出现 `+` 侧（旧 24 字段定义）和 `++` 侧
（新 26 字段定义）—— **并集驱动把同名导出的两个版本都保留了**。

## 为什么驱动会这样（代码级定位）

`tools/module-progress/merge-union.mjs:136-156`，TS 分支：

```js
const TS_KEY = /^export\s+(?:type|interface|const|enum)\s+([A-Za-z0-9_$]+)/;

const toMap = (text) => {
  const map = new Map();
  const head = [];
  for (const line of text.split('\n')) {
    const m = TS_KEY.exec(line);
    if (m) map.set(m[1], line);   // ← 同名键后者覆盖前者
    else head.push(line);
  }
  return { map, head };
};
…
writeFileSync(oursPath, `${merged.map(([, v]) => v).join('\n')}\n${o.head.join('\n')}`);
```

我一度怀疑是 `o.head` 把重复行拼了回去，**实测推翻了这一假设**：
`catalog.ts` 459 行里 457 行匹配 `TS_KEY`，只有 2 行进 `head`，且 `head` 不含
任何重复导出行。重复确实留在 `map` 输出里。

这说明**这份文件根本没走驱动的 TS 分支**。最可能的原因是驱动只在 git 判定
该文件**冲突**时才被调用；而 `catalog.ts` 在两侧改动位置不重叠时 git 能自动
文本合并（不同位置各插一段），驱动压根没运行，git 的三方合并就把两份同名声明
都留下了 —— 而 git 不理解「同名 type 重复声明」是语义冲突。

**这与 3.8.1.1 的 `api-proxy.ts` 冲突是同一个结构性缺口的两面**：
- `api-proxy.ts`：单行巨型正则 → git 无法逐块合并 → 冲突（但驱动没覆盖，手工解）
- `catalog.ts`：双方在不同位置各插一段 → git 能文本合并 → **静默产出重复声明**

后者更危险：**不报冲突、不进 `CONFLICT_KEYS`、CI 绿、只在 typecheck 才炸。**

## 影响面

- `pnpm typecheck` 在集成头失败 → 任何要求「typecheck 绿」的车道都无法自证完成
- `next build` 失败 → 下一条车道无论代码多干净都构建不出来
- 3.7.2.1 已标记 `已交付`，但其前端代码依赖的第 2 份（新）定义被第 1 份（旧）遮蔽
  —— TypeScript 报错正是 `Property 'dangerousSubstanceClass' does not exist`

## 建议的修复（未执行，等下一个 tick 决策）

1. **立即止血**：跑 `OpenApiContractTest`（它把 spec 导出到 `backend/target/openapi`）
   刷新快照，再 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
   重新生成契约。我已试过用**现有的** `target/openapi`（14:31 的旧产物）重新生成：
   重复声明消失、`tsc` 单独跑通、schema 与 path 零丢失（358 schemas / 215 paths 不变），
   **但**因 `target` 快照本身是旧的，重新生成会把新字段退回旧字段，导致 5 处
   `TS2339` —— **必须先刷新 spec 再生成，顺序不能反**。我已 `git checkout` 还原，
   没留下半吊子改动。
2. **根治**：给 `.gitattributes` 的并集驱动加「同名导出只保留一份」的兜底，
   或把契约生成从「并集合并生成物」改成「合并 spec 后整体重新生成」
   —— 后者更符合 codegen 产物的本质：生成物本就不该靠文本合并拼，
   应合并**输入**（openapi.json）再重新生成。
3. `api-proxy.ts` 建议从单行巨型正则重构成多行数组，冲突与正则出错风险一并消失。

## 本轮未做的事（纪律说明）

15:15 轮另一个 tick 正在处理 `api-proxy.ts` 冲突、已把 3.6.3.1 并入集成头
（`560ae6d`），其工作区有未提交改动。本轮**只做只读诊断**，未改任何文件、
未取调度闸、未派发车道、未碰对方车道分支。台账重复行问题已被那个 tick 修好
（3.6.1.1 / 3.7.2.1 / 3.8.1.1 各剩 1 行），无需重复处理。
