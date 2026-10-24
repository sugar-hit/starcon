# 并入顺序依赖：契约导出行先于真实契约文件落地 ⇒ 集成头 TS2306（2026-10-25 batch-93 取证）

**优先级：P1 —— 不是理论风险，是**已经存在的**潜在破损。当前批次一旦按错误顺序并入，L2 必然红。**

**状态：待处理。本轮只取证 + 用任务书规避，未改任何编排脚本。**

---

## 现象

batch-93 静态预检与合并预演时发现：`feat/18-1-1-1`（platform 车道）的 `pnpm typecheck` 有一条**独有的**红：

```
packages/api-contracts/src/index.ts(10,15): error TS2306:
  File '.../packages/api-contracts/src/contracts/reporting.ts' is not a module.
```

## 根因：bootstrap commit 留下的**空契约导出行**

`feat/13-1-1-1` 的第 1 个 commit（服务引导）`8c8f7c47` 同时做了三件事：

| 文件 | 内容 | 行数 |
| --- | --- | --- |
| `packages/api-contracts/src/index.ts` | **新增** `export * from './contracts/reporting';` | +1 |
| `packages/api-contracts/src/contracts/reporting.ts` | **只有一行生成器头注释，零 export** | 85 B / 1 行 |
| `packages/api-contracts/src/reporting.openapi.json` | 空壳 spec | 230 B / 15 行 |

`index.ts` 导出的是一个**没有任何 export/import 语句的文件** ⇒ TypeScript 判定它「不是一个模块」⇒ **TS2306**。

⇒ **任何包含 `8c8f7c47` 的分支，`pnpm typecheck` 都是红的**，直到该分支真正跑一次 codegen 生成出带 export 的契约文件为止。
`13.1.1.1` 车道之所以 typecheck 没红，是因为它的 worktree 里已经有一份**未提交**的 3551 B / 20 个 export 的真实契约。

## 为什么会变成并入顺序问题

两条车道的分支拓扑（实测）：

```
8ef8abbd (基线)
  └── feat/13-1-1-1 : 8c8f7c47 (reporting 引导)          [+ 未提交的真实契约 commit 2]
        └── feat/18-1-1-1 : 93ab4374 (platform 引导，**基于 8c8f7c47**)
```

`feat/18-1-1-1` 是**从 reporting 的 commit 1 切出来的**（batch-92 的刻意设计：让「第二条服务引导车道」天然含第一条的改动，
以规避 `backend/pom.xml` / `deploy/docker/*` / `tools/codegen/catalog-contracts.py` / `.github/workflows/ci.yml` 四个共享文件的冲突）。

但这带来一个**方向性依赖**：

| 并入顺序 | 集成头拿到 `contracts/reporting.ts` | 集成头 `pnpm typecheck` |
| --- | --- | --- |
| **reporting 先、platform 后** | reporting commit 2 的**真实契约**（platform 未改该文件 ⇒ 取 reporting 的版本） | ✅ 绿 |
| **platform 先、reporting 后** | platform 分支里的 **85 B 空壳**（reporting 还没并入） | ❌ **TS2306 红** |

⇒ batch-92 会话日志里写的「两种并入顺序均零冲突」是对的（**冲突**为零），但**"都能过 L2"是错的** ——
顺序错了不产生冲突，而是**产生一条类型错误**，让 L2 假红。

## 编排器不保证顺序

`lane.mjs` 的 `merge-ready`（约 1035 行）按 `Object.values(state.lanes)` 的**插入顺序**遍历 `ready` 车道：

```js
for (const lane of ready) { git(['merge', '--no-ff', ...]) }
```

**没有依赖排序、没有拓扑序**。当前 `13.1.1.1` 先于 `18.1.1.1` 创建 ⇒ 插入顺序恰好正确 ⇒ **靠巧合成立**。
一旦两条车道的创建顺序调换（例如先派 platform 再派 reporting），就会按错误顺序并入 ⇒ L2 红。

## 建议的两条解法（待人工拍板，调度者不自行改脚本）

1. **`merge-ready` 加依赖排序**：并入前按「被并入分支是不是另一条车道的祖先」做拓扑排序。
   `8c8f7c47` 是 `feat/18-1-1-1` 的祖先 ⇒ reporting 必须在 platform 之前。改动小、直击根因。
2. **服务引导 commit 不许提交空壳契约**：任务书要求「引导 commit 必须同时包含 `index.ts` 导出行**与** codegen 生成的真实契约文件（哪怕只含 `export type X = ...` 的空类型集）」。
   即 `contracts/<svc>.ts` 至少要有一个 export，这样空壳分支也是**合法模块**，顺序就不再敏感。

> ⚠️ 解法 2 只能消除「顺序敏感」，不能消除「集成头带着空契约」这件事本身；
> 解法 1 才能保证「先有真实契约、后有依赖方」。

## 本轮（batch-93）实际采取的规避

- **不修编排脚本**（协议：调度者不自行改 `lane.mjs` 的编排行为）。
- 在两条重派任务书里**逐字写明**：
  - reporting 车道：真实契约文件**必须包含在 commit 里**，否则本分支自身 typecheck 红；
  - platform 车道：`pnpm typecheck` 的**那一条** TS2306 是**已知的跨车道现象**，由 reporting 的 commit 2 解决 —— **不许手改 `contracts/reporting.ts`**（那是别的车道的文件），**不许删 `index.ts` 的导出行**。
- 在 batch-93 会话日志与下一批（batch-94）预期里点名：**回收并入时必须 reporting 先、platform 后**。

## 关联

- `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md` —— 同一类问题的另一面（共享白名单累积文件的**冲突**）。
  本条是共享累积文件的**顺序敏感**变种：不冲突，但会红。
