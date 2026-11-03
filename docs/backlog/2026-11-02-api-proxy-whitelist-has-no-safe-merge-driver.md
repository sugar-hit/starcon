# `apps/web/src/lib/api-proxy.ts` 没有合并驱动器 ⇒ 多车道追加白名单必冲突；且**现有 union 驱动器的兜底并集对它是有害的**

> 发现时间：2026-11-02 10:50（流水线 round 130 调度者做 4 车道合并预演时实测）
> 严重度：**高** —— ① 合并必冲突（阻塞下一轮并入）；② 更要紧的是：
> **一旦有人顺手把这个文件加进 `.gitattributes` 的 union 驱动范围，合并会「静默成功」并产出损坏文件**
> （退出码 0 = 不报冲突，但文件少 4700 行）。

---

## 一、现象：4 条车道并发追加白名单 ⇒ 合并冲突

round 130 的 4 条车道里有 **4 条都改了 `apps/web/src/lib/api-proxy.ts`**（共享白名单累积文件）：

| 车道 | 追加的白名单条目 | 插入锚点 |
| --- | --- | --- |
| 18.4.3.2 效果评估 | `platform/ai/effects` | 它的 18.4.3.1 兄弟条目之后 |
| 18.4.2.3 异常诊断 | `platform/ai/diagnosis` | **数组末尾** |
| 5.1.1.1 价目表列表 | `price-lists` | **数组末尾** |
| 15.3.1.1 客户列表 | `crm/customers` | **数组末尾** |

`git merge-tree` 预演（**未改动任何工作区**）：

```
merge feat/18-4-3-2 => clean
merge feat/18-4-2-3 => clean          ← 两个平台车道的锚点不同，git 三方合并能自动排开
merge feat/5-1-1-1  => CONFLICT (content): Merge conflict in apps/web/src/lib/api-proxy.ts
merge feat/15-3-1-1 => CONFLICT (content): Merge conflict in apps/web/src/lib/api-proxy.ts
```

根因：`.gitattributes` 里 **union 驱动器只覆盖了三类文件**：

```
packages/api-contracts/src/**/*.ts        merge=contract-union
packages/api-contracts/src/*.openapi.json  merge=contract-union
docs/product/capability-blueprint/module-progress.md merge=contract-union
```

`apps/web/src/lib/api-proxy.ts` **不在其中** ⇒ git 直接把它当普通文本文件做三方合并
⇒ 同一个数组末尾、多个车道各自追加 ⇒ **必然重叠冲突**。
（两个平台车道之所以能干净合并，是因为它们的插入锚点**不同** —— 一个在兄弟条目后、一个在数组末尾。）

## 二、🔴 更危险的部分：**现有 union 驱动器的兜底并集，用在这个文件上是错的**

天真的做法是「把它也加进 union 驱动范围」。**实测证明那样会静默产出损坏文件。**

实测命令（`ours` = 18.4.2.3 版、`theirs` = 5.1.1.1 版、`base` = 两者共同祖先）：

```bash
node tools/module-progress/merge-union.mjs ours.ts merged.ts theirs.ts
# merge-union: 走了行级兜底并集，请人工确认这个文件改对了
# DRIVER_EXIT=0        ← 退出码 0 = 「已解决，不报冲突」
```

结果：

| 文件 | 行数 |
| --- | --- |
| `ours.ts`（18.4.2.3 版） | 14811 |
| `theirs.ts`（5.1.1.1 版） | 14849 |
| **`merged.ts`（驱动产出）** | **10092** ❌ |

`merged.ts` 的结构：

- 数组在**第 9939 行**就闭合了（`];`）；
- 而 `theirs` 新增的 `price-lists` 条目落在**第 10075 行之后** ⇒ **在数组外面**；
- `ours` 第 9940–14811 行（整个代理 handler 与导出语句）**整段消失**。

⇒ 驱动兜底做的是**行级去重并集**：它把两边共有的行去掉重复后拼起来，
**不知道数组在哪里闭合、也不知道新条目该插在闭合符之前** ⇒ 产出**语法不完整、且丢代码**的文件，
**而且退出码是 0**（合并层面认为「成功」，CI 与 L2 都不会报冲突，
只有等 Next build / typecheck 才炸 —— 那时已经进了集成头）。

## 三、处置建议

1. ⛔ **不要**把 `apps/web/src/lib/api-proxy.ts` 直接加进 `.gitattributes` 的 `merge=contract-union`。
   实测证明兜底会产出损坏文件且**不报冲突**。
2. ✅ **正解：给这个文件写一个结构化合并驱动器**（不要复用通用 union 兜底）：
   - 解析 `const services: readonly {...}[] = [ ... ];` 这个数组；
   - 按每个元素的 **`prefix`** 字段求并集（这正是该文件天然的键）；
   - 输出时保持「既有元素一字未改、不重排」的纪律，只在闭合符之前插入新元素；
   - 解析失败时**必须返回非 0（报冲突）**，绝不能像现在这样用行级兜底静默产出。
   ⇒ 驱动注册名可复用 `merge.contract-union.driver`（`lane.mjs init` 已注册），
   但要在 `merge-union.mjs` 里**按文件类型分流**：JSON/台账走现有按键求并；
   `api-proxy.ts` 走数组按 `prefix` 求并；**两者都不许落到行级兜底**。
3. 🔴 **同样要审一遍现有 union 驱动器的兜底分支**：
   现在只要解析不出语义就退到「行级去重并集 + 提示人工确认」，
   而**退出码仍是 0** ⇒ 对**任何**非 JSON 的文件它都可能「静默成功」并丢内容。
   ⇒ 建议：**兜底分支改为退出码 3（= 报冲突）**，由调度者显式决定怎么处理，
   不要让驱动自己猜。这正是本次实测暴露出来的机制性风险。
4. **短期（下一轮并入时）**：4 条车道的 `api-proxy.ts` 冲突**必须人工解**，
   解法是机械的 —— **把 4 条条目都保留，并确保它们全部落在 `];` 之前**。
   ⚠️ 解完必须跑：
   ```bash
   pnpm exec prettier --check apps/web/src/lib/api-proxy.ts
   pnpm typecheck
   pnpm vitest run apps/web/src/app/api/trade    # 全量 allowlist 回归
   ```

## 四、通用教训

**共享累积文件必须有「按键求并」的合并语义，且驱动不许有「猜」的兜底。**
没有合并语义 ⇒ 多车道并发必然冲突；有兜底但兜底是错的 ⇒ 冲突从「响亮地失败」变成「安静地损坏」。
⇒ 判据：`git merge-tree --write-tree` 预演合并 + **检查产出文件是否还完整**（行数 + 结构），
不要只看退出码。
