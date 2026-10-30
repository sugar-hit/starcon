# merge-union 台账分支：两个父分支改了不同卷时，汇总块整块重复 —— 汇总数字与行数永久对不上

> 发现时间：2026-10-30 集成头同步第 109 轮（`origin/main` 无新提交，取证过程中发现）
> 严重度：**中** —— **交付事实不丢**（465 行模块行逐字节一致），但**汇总块自相矛盾**
> 归属：`tools/module-progress/merge-union.mjs`（台账合并分支，行级兜底并集）
> 与 `docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md` **同源但更严重**：
> 那条只记了「结构塌陷（空行 17→1、分隔行 4→3）」，**没记「汇总块重复」这一层内容级损坏**。

---

## 现象

集成头 `integration/module-dev` 的 HEAD（`84599442`）上，
`docs/product/capability-blueprint/module-progress.md` 的**汇总块**同时出现两套互相矛盾的数值：

| 位置 | 内容 | 行号 |
| --- | --- | --- |
| 「分系统进度」表 | `\| 13 \| 利润与经营分析系统 \| D7 \| **21** \| 23 \|` | 40 |
| 「分系统进度」表 | `\| 14 \| 广告与增长系统 \| D7 \| **21** \| 22 \|` | 41 |
| **文件末尾（模块表之后）** | `\| 13 \| 利润与经营分析系统 \| D7 \| **20** \| 23 \|` | 543 |
| **文件末尾（模块表之后）** | `\| 14 \| 广告与增长系统 \| D7 \| **22** \| 22 \|` | 544 |

同时「总览」块写着 **432 / 33**，而**它自己下面那 465 行模块行实际是 433 / 32**。

⇒ **`node tools/module-progress/sync.mjs --check` 在 HEAD 版本上失败**（退出码 1），
报「台账与蓝图不一致」。

## 根因

`merge-union.mjs` 的台账分支只对**模块 ID 行**（`LEDGER_ROW = /^\| (\d+\.\d+\.\d+\.\d+) \|/`）做按 ID 求并集；
**汇总块里的行（总览 / 分阶段 / 分系统）不是模块 ID 行**，于是掉进兜底分支的
「非台账行按整行文本去重」：

```js
if (!m) {                       // 不是模块 ID 行
  if (seen.has(line)) continue; // ← 整行文本相同就丢
  seen.add(line);
  outLines.push(line);
}
```

两个父分支**各自改了不同的卷**时，汇总块里那几行的**文本就不相同**（数值不同）：

| | 卷 13 已交付 | 卷 14 已交付 |
| --- | --- | --- |
| ours `e93fc3d3` | 21 | 21 |
| theirs `9ad839a3` | 20 | 22 |
| **合并结果** | **21 和 20 都留着** | **21 和 22 都留着** |

⇒ 文本不同 ⇒ 都不被去重 ⇒ **两套卷行同时进文件**（ours 那套留在原位，theirs 那套被追加到文件末尾）。
「总览」的 432/33 也来自 ours/theirs 中某一侧的旧值，**没有任何一侧是合并后的正确值**（正确值是 433/32）。

**触发条件**：两个父分支**改了不同的卷**。
若两个父分支改了**同一卷**（数值相同），整行文本相同 ⇒ 被去重 ⇒ **不会触发**。
实测反例：`8e16e70c`（merge 13.4.3.1），两父的卷 13/14 行完全相同 ⇒ 结果只有 18 行卷行，**没坏**。

## 复现（确定性，无需运气）

```bash
cd /Users/guan/Projects/starcon-worktrees/integration
W=.tmp/merge-union-repro && mkdir -p $W
git show e93fc3d3:docs/product/capability-blueprint/module-progress.md > $W/ours.md
git show 9ad839a3:docs/product/capability-blueprint/module-progress.md > $W/theirs.md
cp $W/ours.md $W/result.md
node tools/module-progress/merge-union.mjs $W/ours.md $W/result.md $W/theirs.md
diff -q $W/result.md <(git show HEAD:docs/product/capability-blueprint/module-progress.md)
# ⇒ IDENTICAL：HEAD 的坏台账可从两个父分支确定性复现
```

## 影响评估

- ✅ **交付事实不丢**：465 行模块行与 `sync.mjs` 重新生成的**逐字节一致**
  （两版模块行 md5 均为 `f9ad8d700c0ad0237439d9b4570b034d`），
  每行的「状态」「证据」都完整。**没有任何模块的交付状态被合并搞错。**
- 🔴 **汇总数字自相矛盾**：「总览 432/33」vs 实际行 433/32。
  任何读台账做进度汇报的地方（日报、进度看板、汇报里的数字）都会**读到错的数**。
- 🔴 **卷行重复**：「分系统进度」表 18 行变成 20 行，且多出的 2 行**落在文件末尾的模块表区域里**，
  渲染上与模块行混在一起。
- ⚠️ **markdown 结构同时塌陷**（空行 17→1、分隔行 4→3）—— 这是 2026-10-20 那条 backlog 记的同一现象。
- ⚠️ **`sync.mjs --check` 红** ⇒ 台账的**唯一自检**失灵。

### 为什么之前一直没被发现

**每次并入都会发生，但只有「两父改不同卷」的并入才产生「重复」**；
其余并入要么数值相同被去重、要么结构塌陷被下一轮 `sync.mjs` 顺手修好。
⇒ 从台账的**最终状态**看永远正常。

实测最近 12 个改台账的 first-parent 提交：

| 提交 | 类型 | 模块行 | 汇总 | 卷行数 | 空行 | 分隔行 | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `84599442` | merge(14.2.1.1) | 433/32 | 432/33 | **20** | **1** | **3** | **BAD** |
| `ec720b03` | merge(13.6.1.1) | 432/33 | 432/33 | 18 | 17 | 4 | OK |
| `763d7287` | docs(batch-113) | 431/34 | 431/34 | 18 | 17 | 4 | OK |
| `72ada60e` | merge(13.5.3.1) | 431/34 | 多套 | **22** | **1** | **3** | **BAD** |
| `80f1d84e` | merge(14.3.2.1) | 430/35 | 多套 | **22** | **1** | **3** | **BAD** |
| `f7687705` | docs(batch-112) | 429/36 | 429/36 | 18 | 17 | 4 | OK |
| `cfa76762` | merge(13.5.2.1) | 429/36 | 多套 | **21** | **1** | **3** | **BAD** |
| `3bf74160` | docs(batch-111) | 428/37 | 428/37 | 18 | 17 | 4 | OK |
| `f2c5f630` | merge(13.5.1.1) | 428/37 | 多套 | **20** | **1** | **3** | **BAD** |
| `0907e76d` | merge(14.3.1.3) | 427/38 | 多套 | **19** | **1** | **3** | **BAD** |
| `f05f7c13` | docs(batch-110) | 426/39 | 426/39 | 18 | 17 | 4 | OK |
| `8e16e70c` | merge(13.4.3.1) | 426/39 | 多套 | 18 | **1** | **3** | BAD |

**规律**：`docs(batch-*)` 提交（走 `sync.mjs` 重算）**一律 OK**；
`merge(...)` 提交（走 `merge-union`）**多数 BAD**。
⇒ **只要走 merge-union 就可能坏**，只是坏法不同（结构塌陷 / 汇总重复）。

### 2026-10-20 那条 backlog 的修法**没有真正解决本问题**

`docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md` 建议
「台账分支**整体交给 `sync.mjs` 重新生成**」（建议修法 2）。
但**实测该建议未被采纳**：`merge-union.mjs` 里仍无「跳过空行 / 分隔行去重」的例外
（`grep` 无命中），且**汇总块重复这一层从未被记过**。
⇒ 那条 backlog 里「不丢数据、渲染坏」的结论**不完整**：本条证明汇总数字也会错。

## 为什么 CI 没拦住

`.github/workflows/ci.yml` 的 job（lint / typecheck / test / build / security / backend / catalog-e2e）
**没有任何一个跑 `sync.mjs --check`**（`grep -rn 'sync.mjs' .github/workflows/` 无命中）。
⇒ 台账自相矛盾时 CI 全绿。

## 影响范围（对已发布内容）

- **远端 `origin/integration/module-dev`（`e93fc3d3`）是健康的**：模块行 432/33、汇总 432/33、
  卷行 18 行、空行 17、分隔行 4 ⇒ **一致**。
- **只有本地未推送的 4 个提交带这个问题**（`84599442` 引入，`2440db45` 继承）。
  ⇒ 尚未污染远端。
- ⚠️ 但这 4 个提交一旦被 push，远端集成头的台账就会变成「汇总 432/33 / 实际 433/32」的自相矛盾状态。

## 建议修法

**推荐：台账分支整体交给 `sync.mjs` 重新生成**（2026-10-20 那条 backlog 的建议修法 2，尚未落实）：

1. `merge-union.mjs` 的台账分支**不自己拼文件**，
   改为在按模块 ID 求并集（`betterRow`）拿到 465 行模块行之后，
   把这些行交给 `sync.mjs` 重新生成整个台账 ⇒ 汇总块永远与模块行一致。
2. 或者（改动更小）：台账分支只输出**模块 ID 行**，
   由调用方（`lane.mjs` merge-ready / `SYNC-MAIN.md` 的同步路径）在 commit 前跑一次
   `node tools/module-progress/sync.mjs`。
3. 兜底护栏：在 `ci.yml` 加一个 job 跑 `node tools/module-progress/sync.mjs --check`，
   让「汇总与模块行对不上」在 CI 里**立刻红**，而不是等下一轮 `sync.mjs` 顺手修。

⚠️ 无论选哪条，都**必须同时考虑 `SYNC-MAIN.md` 那条路径** ——
它也用同一个驱动合台账，且**提交那一刻不跑 `sync.mjs`**（2026-10-20 那条 backlog 已提示）。

⚠️ 改驱动属于**工具链改动**，不属任何业务车道 ⇒ 应**单独派一条修复车道**，
不要塞进正在跑的批次。

## 验收判据

- 用上面的复现脚本跑一遍 `merge-union`，产出台账满足：
  - 模块行 465、唯一 ID 465；
  - 「总览」的已交付/未开发 **等于**模块行实际统计；
  - 「分系统进度」表**恰好 18 行**（每卷一行），无重复、无落到文件末尾的散行；
  - 「分阶段进度」表**恰好 6 行**；
  - 空行 17、`| --- |` 分隔行 4（结构不塌陷）；
  - `node tools/module-progress/sync.mjs --check` ⇒ ✓ 台账与蓝图一致。
- 追加一条回归用例到 `tools/module-progress/merge-union.itest.mjs`
  （现有没有任何一条断言汇总块 / 卷行数 / 空行数的用例 —— 这正是漏网的原因）。
- `ci.yml` 增加 `sync.mjs --check` 护栏。

## 关联

- `docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md` —— 同源（同一行级兜底并集），只记了结构塌陷
- `docs/backlog/2026-10-22-merge-abort-fails-on-merge-union-ledger-file.md` —— 同一驱动的另一个问题
- `tools/module-progress/merge-union.mjs` —— 归属文件（台账分支）
- `tools/module-progress/merge-union.itest.mjs` —— 缺回归用例
- `tools/module-progress/sync.mjs` —— 台账生成器（唯一权威）
- `docs/product/capability-blueprint/module-progress.md` —— 受影响文件
- `docs/sessions/2026-10/2026-10-30-0530-main-sync-round109-no-new-commits.md` —— 发现经过（本轮同步任务）
