# `git rev-parse` 对不存在的路径把**参数原样回显到 stdout** ⇒ 冲突判定器把 add/add 误标成 content

发现：2026-10-23 05:00（第 43 轮「集成分支并入 main」期间）。发现人：并入主分支的执行者。
影响范围：`tools/module-progress/merge-resolve.mjs` 的**冲突类型标注**与 `pathBlobHistory` 的条目收集。

## 现象

第 43 轮合并命中 **38 个冲突文件**（21 个前端源码 + 17 篇会话纪要），
判定器把**全部 38 个**标成：

```
[并入（人工）] apps/web/src/components/buyer-risk/buyer-risk-model.ts
    冲突类型：content（共同祖先里有这个文件，两侧都改过）
```

但两项独立事实与此矛盾：

1. `git merge-tree --write-tree` 对这 38 个文件报的是 **`CONFLICT (add/add)`**，不是 content。
2. 共同祖先（merge-base `4ab1d8d1`）里**根本没有这些文件** —— 用 `git ls-tree` 查是空的。

另外，每次运行判定器都会往 stderr 打 **38 行** `fatal: path '...' does not exist in '<sha>'`。

## 根因：`rev-parse` 失败时 stdout 不是空的

`blobOf()` 用 `git rev-parse <ref>:<path>` 取 blob：

```js
// tools/module-progress/merge-resolve.mjs
const out = git(['rev-parse', `${ref}:${path}`], { allowFail: true, cwd }).trim();
return out && !out.includes('fatal') ? out : null;
```

该命令在**路径不存在**时：把 `fatal: ... does not exist` 写到 **stderr**、
以退出码 **128** 失败，同时把**未解析的参数原样回显到 stdout**：

```
$ git rev-parse "4ab1d8d1...:apps/web/src/components/buyer-risk/buyer-risk-model.ts" 2>/dev/null
4ab1d8d1c97359b22ead5baa4b246febed22252d:apps/web/src/components/buyer-risk/buyer-risk-model.ts
                                       ↑ 回显文本，不是 blob hash

$ git rev-parse "..." 2>&1 | head -1
fatal: path '...' does not exist in '4ab1d8d1...'      ← 真正的错误在 stderr
```

`allowFail` 接住了失败、取到的却是 stdout 里那串**回显文本**（形如 `<sha>:<path>`），
它非空、也不含 `fatal` ⇒ `blobOf` 把「**路径不存在**」读成「**存在**」。

于是 `classifyFile()` 里：

```js
const baseAtMergeBase = mergeBase ? blobOf(mergeBase, path, cwd) : null;
const kind = baseAtMergeBase === null ? 'add/add（…）' : 'content（…）';
```

`baseAtMergeBase` **永远不是 null** ⇒ `kind` **永远是 `content`**。

## 影响面（以及不影响什么）

**受影响**：

- `kind` 永远算出 `content`，**add/add 分类失真** —— 而协议正是按这个分类给不同文件派不同解法的。
- `pathBlobHistory()` 遍历历史提交时，把「该提交里路径不存在」当成有效 blob 收进历史列表。
- stderr 噪声：38 行 `fatal:` 混在判定器输出里，容易被误读成「合并真的炸了」。

**不受影响（重要）**：本轮 38 个文件的**裁决结论没有因此改变**。
「main 侧 blob 是否出现在集成分支历史里」这个判据取的是 **main 侧**的 blob
（main 确实有这些文件，`rev-parse` 在那里返回的是真 hash），
所以 `isAncestorSnapshot` 的计算是准的 —— 38 个文件判为「并入（人工）」成立，
只是**类型标签**写错了。

⇒ 本轮实际结论（38 个文件纯排版差异、取 main 侧）是由**仓库自身的 prettier 归一化**
与 **AST 指纹**两条独立证据得出的，与判定器的类型标签无关。

## 修复

`blobOf` 换成不会回显的取法。两种都实测过：

| 写法                                  | 路径存在                     | 路径不存在                  |
| ------------------------------------- | ---------------------------- | --------------------------- |
| `git rev-parse <ref>:<path>`          | 真 hash                      | ⚠️ **回显参数**（exit 128） |
| `git rev-parse --verify <ref>:<path>` | 真 hash                      | ✅ stdout 空（exit 128）    |
| `git ls-tree <ref> -- <path>`         | `100644 blob <hash>\t<path>` | ✅ stdout 空（exit 0）      |

推荐 `ls-tree`：缺失路径时**退出码为 0**，不依赖 `allowFail` 的异常分支，
也不会把 stderr 噪声打进判定器输出。

## 待办

| 编号 | 待办                                                                                                         | 责任   | 备注                                                       |
| ---- | ------------------------------------------------------------------------------------------------------------ | ------ | ---------------------------------------------------------- |
| L-01 | `merge-resolve.mjs` 的 `blobOf` 改用 `git ls-tree`（或 `rev-parse --verify`），并**断言返回值是 40 位 hash** | 并入者 | 断言能挡住「回显文本被当 hash 用」这一类复发               |
| L-02 | `classifyFile()` 的 `kind` 判定加**自检**：add/add 冲突必须报 `add/add`，不允许被标成 `content`              | 并入者 | 用 `merge-tree` 的 `CONFLICT (add/add)` 输出做交叉验证     |
| L-03 | 判定器**吞掉 stderr**（`stdio: ['ignore','pipe','ignore']`），避免 `fatal:` 行混进输出                       | 并入者 | 噪声会让「路径不存在」看起来像「合并失败」                 |
| L-04 | 协议 §4.2 补一句：**判定器的类型标签只作参考**，最终解法以「归一化取证 + AST 指纹」为准                      | 并入者 | 本轮与第 42 轮都验证过：纯排版差异会被判成「必须人工并入」 |

## 2026-10-23 06:45 复现补充（第 46 轮）：「并入（人工）」在 squash 仓库里是**假警报**

第 46 轮再次命中同一形状：**39 个冲突文件**（22 个前端源码 + 16 篇会话纪要 + 1 个白名单），
判定器对其中 **38 个**输出 `[并入（人工）]`，并打出 38 行 `fatal: path ... does not exist`。

本轮用**独立取证**复核了这些裁决，结论比第 43 轮更强：

| 取证手段                                          | 结果                                                      |
| ------------------------------------------------- | --------------------------------------------------------- |
| `git ls-tree`（不用 `rev-parse`）查祖先快照       | 38 个文件在 merge-base 里**根本不存在**（add/add 属实）   |
| main 侧 blob 是否出现在集成分支历史里             | 38 个**都不在** ⇒ 判「main 有集成分支从未有过的内容」     |
| AST 指纹（归一化 Tailwind 类名后）多重集比对      | 22 个 TS/TSX 文件 **main 独有内容 = 0**                   |
| markdown 规范化（表格分隔宽度 / 列表符号 / 粗体） | 16 篇纪要 **丢失行 = 0**（余 2 行是表格竖线前空白，误报） |

⇒ **「main 侧 blob 不在集成分支历史里」这个判据，在 squash 合入的仓库里会把
「线性演进」误判成「真分叉」**：main 侧那些 blob 是**上一次 squash 合并的解冲突产物**
（本轮实测：每个 blob 在全仓库只出现 1 次，只存在于 main 的 squash 提交里），
它天然不在集成分支的历史中 —— 但它的**内容**已经被集成分支完全覆盖。

**更稳的判据是「内容超集」而不是「blob 在历史里」**：
比较两侧的语义内容（AST 指纹多重集 / 规范化文本多重集），
**main 独有内容为 0 ⇒ 取集成分支侧不丢任何东西**，与「blob 是否在历史里」无关。

本轮 39 个文件全部按此判据取集成分支侧，实测合并结果 =
集成分支内容 + 仅 main 有的 13 篇文档，**零丢失**。

## 相关

- `docs/sessions/2026-10/2026-10-23-0509-main-sync-round43-zero-net-change.md` —— 本条的发现现场。
- `docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md` —— 同一条合并链路的另一个结构缺陷。
