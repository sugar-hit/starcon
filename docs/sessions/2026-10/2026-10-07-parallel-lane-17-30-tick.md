# 17:30 tick —— 三起冲突解完，白名单「改宽」不是「新增」

回收 3.9.1.2 与 17.1.2.2，交付数 70 → 72。调度闸已释放，工作区干净。

## 关键：并入冲突分三类，之前只处理过第一类

1. **纯新增**（3.9.2.2 / 3.9.2.1）：base 段两侧都无缺失，ours = base+N、theirs = base+1，
   无 contested 分支。求并 = 以 ours 为基底追加 theirs 的新分支。
2. **改宽既有分支**（3.9.1.2 / 17.1.2.2）：车道**替换**了某条既有分支的正文以放宽子路径。
   判据：`BASE missing from THEIRS` 里出现的那条，**不是被删了，是被改宽了**。
   典型两例：
   - `duplicate-candidates/{uuid}(?:/decision)?` → `...(?:\/(?:decision|merge-preview|merge)?)?`
   - `legal-entities/references` → `legal-entities/(?:[0-9a-f-]+/accounting-books|references)`
     求并 = ours 基底 + **替换**该条为宽的那版 + 追加新增分支。
3. **文件尾双侧追加**（route.test.ts）：见 17:10 那份记录，公共收尾 `});` 只剩一份。

**改宽的语义依据要写进 commit**：3.9.1.1 那轮刻意不放行 `merge`，
理由是「执行合并归 3.9.1.2，当时没交付，放行会让子路径看起来像已交付」。
现在 3.9.1.2 落地了才放开 —— 所以这是**正确放宽**，不是车道乱扩白名单。

## 差点踩的坑：按首词配对会误替换

写脚本判断「同一分支是否被改宽」时，我用 first-segment 相同来配对：

```js
const headWord = (b) => b.match(/^[^?([/\\]+/)[1];
```

结果 `legal-entities(?:\/[0-9a-f-]+)?` 与 `legal-entities\/references`
**首词都是 `legal-entities`**，被误配成一对「宽化」，
于是脚本会把**根本没被改过**的第一条也替换掉 —— 而且不报错，产物仍是一个平衡的合法正则。

**规则**：legal-entities / products 这类前缀下有多条并列分支时，
必须按**整条字符串精确匹配**再替换，并**断言该子串在字面量里只出现一次**：

```js
if (lit.split(OLD).length - 1 !== 1) throw new Error('ambiguous replacement');
```

## 验证脚本自身也要审

我这次连续三次把自己的断言写反/写漏：

- 三元标签 `A.test(p) ? 'DENY->ALLOW' : 'ALLOW->DENY'` —— A 为 false 时打的是后者，全是反的；
- 期望清单漏了 `sku-merges` 本身（车道的分支把整段后缀做成可选，裸路径确实该放行）；
- 同一个路径同时列进「必须不变」和「新增放行」两张表。

教训：**判据写成「范围外变化数 == 0 且 泄漏数 == 0」这种单值断言**，
不要靠人眼读 diff 标签。`.tmp/verify-allowlist.js` 里的探针清单是按上一次合并写死的，
换一次合并就失配 —— 这种脚本要么参数化探针，要么每次重写，别当成可复用资产。

## 流程侧

- `merge-ready` 一次调用里第一条冲突后，**后续车道全部报同一个假冲突**（工作区还留着未解的
  unmerged 文件，`git merge` 直接 fatal）。所以 `failed[]` 里第二条起的冲突先别当真。
- 手工解完并 commit 之后，记得把车道 state 从 `conflict` 改回正确值
  （`merged` 或 `ready`），否则下一 tick 不会回收它 —— 本轮 3.9.1.2 与 17.1.2.2 都踩了这个。
- 台账重复行本轮又出现两次（5 条、3 条），判据与修法见 17:10 那份记录，流程照旧有效。
