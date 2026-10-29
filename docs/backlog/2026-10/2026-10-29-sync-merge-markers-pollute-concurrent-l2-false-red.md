# 🔴 P0：同步任务的 merge 冲突标记污染并发跑的 L2 ⇒ L2 报假红（误判为「代码回归」）

- 创建: 2026-10-29
- Owner: TBD（**调度侧**负责，见「待办内容」）
- 优先级: **P0** —— 已**实测**造成一整轮 L2（r132，1h04m）白跑，且会把假红误判成代码回归
- 关联: `tools/module-progress/SYNC-MAIN.md` · `tools/module-progress/PIPELINE.md` ·
  `docs/sessions/2026-10/2026-10-29-2015-main-sync-round103-conflict-not-merged.md` ·
  `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` ·
  `docs/backlog/2026-10/2026-10-28-orphan-keepalive-daemon-gate-deadlock-batch107.md`

---

## 摘要（结论先行）

**r132 的 L2 红了，但代码没红。** 红的直接原因是 **identity 模块编译失败**，
而编译器读到的**不是 HEAD 的源码**，是**上一轮同步任务（r102）merge 留在工作区里的冲突标记版**。

**字节级证据**：编译报错行号与冲突标记行号**逐行重合**。

```
编译报错：NotificationInboxController.java:[95,1] / [96,21] / [99,1] / [103,1]
冲突标记：  95: <<<<<<< HEAD
            96:     "1.3.1.2 通知偏好…已交付…",
            99: =======
           103: >>>>>>> 823ebca5
```

⇒ 行号 **95 / 96 / 99 / 103** 一一对上 ⇒ 不是「代码写错了」，是**读到了一份带标记的源码**。

---

## 背景

「main → 集成头同步」任务与「并行车道流水线」任务共用**同一个调度闸**（`.dev-agent/dispatch.gate`），
设计意图是互斥：**同步任务撞上正在跑的流水线时应该零改动退出**。

**但这个互斥对 L2 段完全无效**：

```
$ grep -n "gate acquire"  .dev-agent/l2-r132.sh   ⇒ （无）
$ grep -n "gate release"  .dev-agent/l2-r132.sh   ⇒ （无）
$ grep -nE 'git (reset|checkout|commit|push)' .dev-agent/l2-r132.sh ⇒ （无）
```

r132 的 L2 脚本**不取闸、不放闸**，却在 18:24:55 直接开跑 `mvnw verify -fae`。
⇒ 同期持闸的同步任务（r102，18:23:45 取闸）**在同一个 worktree 里做 merge** ⇒
**冲突标记被写进工作区的源码文件** ⇒ L2 的编译器在 18:26 前后正好读到这些文件 ⇒ 编译失败。

## 时间线（集成头 worktree 的 reflog + L2 日志，双向闭合）

| 时刻     | 事件                                                                                  |
| -------- | ------------------------------------------------------------------------------------- |
| 18:23:45 | r102 同步任务 `gate acquire` 成功（owner pid 79303）                                  |
| 18:24:55 | **r132 的 `mvnw verify -fae` 启动（未取闸）**                                         |
| 18:25:20 | r102 `git reset --hard`（reflog `reset: moving to HEAD`）                             |
| 18:26:04 | r102 `git reset --hard`                                                               |
| 18:26:22 | r102 `git reset --hard` ⇒ **27 个冲突文件 mtime 全部 = 18:26:22**（27/27 同一时间戳） |
| ~18:26   | **L2 的编译器正在读 identity 源码** ⇒ 读到冲突标记版 ⇒ **编译失败**                   |
| 19:29:01 | L2 `mvnw verify` 结束，`BUILD FAILURE`，`MVN_EXIT=1`                                  |

`git reflog`（集成头 worktree）：

```
a6ce2bf5 HEAD@{2026-10-29 18:30:13}: commit: docs(sessions): 集成头同步 main 第 102 轮 …
420962f5 HEAD@{2026-10-29 18:26:22}: reset: moving to HEAD
420962f5 HEAD@{2026-10-29 18:26:04}: reset: moving to HEAD
420962f5 HEAD@{2026-10-29 18:25:20}: reset: moving to HEAD
```

⇒ **27 个冲突文件的 mtime 全部等于 18:26:22**，与 L2 的 identity 编译窗口**完全重合**。

## 反证：HEAD 的代码本身是绿的

```
git archive HEAD backend | tar -x -C <隔离 scratch 树>
cd <隔离 scratch 树>/backend && ./mvnw -q -o -pl services/identity -am compile
⇒ COMPILE_RC=0（只有 JVM 的 jansi/Unsafe 警告，无编译错误）
```

⇒ **identity 模块在 HEAD 上编译通过** ⇒ L2 的红**不是代码缺陷**。

## 危害

1. **一整轮 L2 白跑**：r132 的 backend verify 跑了 **1 小时 04 分**，结果被一个本不该存在的编译错误打断。
2. **假红被误判为「代码回归」**：r132 流水线会话当时的假设是「`git reset` 恢复了旧文件 ⇒ 回退了什么修复」。
   实际上 `git reset` 恢复后的内容与 HEAD **完全一致**（`git status` 干净、隔离树编译 rc=0）
   ⇒ **真正的污染源是冲突标记**，不是恢复后的内容。
3. **浪费 `git bisect`**：PIPELINE.md §「批量验证未过时的处置」要求 L2 红了就二分定位，
   但 r132 本批**零并入**（最后 3 个提交全是 `.md`）⇒ **无业务代码可二分** ⇒ bisect 必然空手而归。
4. **归因被带偏**：若按「最近并入的 main 有回归」的假设排查（PIPELINE.md:847），
   会把同步任务往「main 带进来的回归」上引导 —— 而本轮 main 侧**零新内容**（已字节级证明）。

## 待办内容

- [ ] **（P0，调度侧）所有 L2 脚本启动前必须 `gate acquire`，失败即退出、不跑 L2；收尾必须 `gate release`。**
      ⇒ 这条已在 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` 的待办里，
      本文件提供了**首次实测的假红后果**作为优先级依据。
- [ ] **（P0，调度侧）同步任务在拿到闸之前，绝不允许在集成头 worktree 里做 merge。**
      ⇒ 本轮 r103 严格遵守了这条（「拿到闸再动手」），
      ⇒ r103 的合并确实产生了冲突标记，但**没有任何并发构建在跑**，⇒ **没有造成假红**。
      ⇒ 建议把这条写进 `SYNC-MAIN.md` 的第 1 步（当前只在正文注释里隐含）。
- [ ] **（P0，调度侧）L2 的 keepalive 守护不应刷新闸目录 mtime。**
      `l2-r132.sh:47` 用 `touch "$GATE/keepalive-r132-l2-$i-$NOW"` **新建文件**进闸目录
      ⇒ 目录 mtime 被刷新 ⇒ `lane.mjs:620` 的 `stale = age > 4200` **永远不成立**
      ⇒ **TTL 接管机制性不可达** ⇒ r103 为此白等 **96 分钟**。
      ⇒ 修法二选一：keepalive 改成**只写闸目录之外**的路径；或 `lane.mjs` 的 `age` 改为看
      **owner 文件**的 mtime 而不是目录 mtime。
- [ ] **（P1）L2 应当把「工作区里有未解决的冲突标记」当作硬前置检查。**
      ⇒ 在跑 `mvnw verify` 之前先 `git diff --check` / 扫 `<<<<<<<` 标记，
      有则**直接跳过 L2 并报错**，而不是让它编译失败后被误判为代码回归。
- [ ] **（P1）`lane.mjs` 的 TTL 接管路径非原子** ——
      `rmSync(GATE_DIR)` + `mkdirSync(GATE_DIR)`（`lane.mjs:576-578`）
      ⇒ 两个并发接管者**可能都拿到 `ok: true`** ⇒ 互斥失效。
      ⇒ 建议「先 `rename` 到唯一临时名再 `mkdir`」或引入持有令牌。
- [ ] **（P1）`gate release` 应只删自己持有的闸** —— 现实现是无条件 `rmSync(GATE_DIR)`，
      任何没拿到闸的一方误调都会删掉别人活着的闸。
