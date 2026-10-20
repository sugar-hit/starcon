# starcon 集成头同步协议（main → integration/module-dev）

> **本文件是「同步任务」的 prompt 来源。协议只存这一份，且在版本控制里。**
> 改策略改文件，不要改 cron prompt 字段。
>
> 完整流水线协议在 `tools/module-progress/PIPELINE.md`，**两者不一致时以 PIPELINE.md 为准**。

你每次运行是**全新的独立会话**，不继承任何上下文。**所有判断必须从仓库现状重新取证，不能凭记忆。**

---

## 0. 职责与边界

**职责**：把 `origin/main` 上其他 agent 的合并结果，同步进本流水线的集成头 `integration/module-dev`。

**边界（硬规定）**：

| 允许                                        | 禁止                                                       |
| ------------------------------------------- | ---------------------------------------------------------- |
| `fetch` / `merge origin/main` / `push` 集成头 | ❌ push 到 `main`                                         |
| 同步后刷新台账 `sync.mjs`                    | ❌ 在 `main` 上提交任何东西                                |
| 同步后跑**快速预检**（编译 + typecheck）       | ❌ 跑全量 `mvnw verify`（那是流水线每批一次的 L2）         |
| 合并冲突时**如实上报**                        | ❌ 自行解冲突（`merge-union` 只覆盖契约/台账两类文件）       |
|                                             | ❌ 动 `origin/integration/module-dev` 以外任何分支          |

⚠️ **本任务不改任何业务代码。** 它只搬运 commit。

---

## 1. 本地参数（先读）

```bash
TOP=$(git rev-parse --show-toplevel)             # 本任务的工作目录（主工作区）
CFG="$TOP/.dev-agent/PIPELINE.local.md"

PROJECT_ROOT=$(grep -E '^PROJECT_ROOT=' "$CFG" | cut -d= -f2-)  # 集成头 worktree
PUSH_REMOTE=$(grep -E '^PUSH_REMOTE=' "$CFG" | cut -d= -f2-)

cd "$PROJECT_ROOT"
```

⚠️ **所有 git 操作都在 `$PROJECT_ROOT`（集成头 worktree）里做**，主工作区留给人工。

---

## 2. 步骤

### 第 1 步：取调度闸（与流水线互斥）

```bash
cd "$PROJECT_ROOT"
node tools/module-progress/lane.mjs gate status
```

| `gate` | `stale` | 动作                                                          |
| ------ | ------- | ------------------------------------------------------------- |
| `free` | false   | `gate acquire` 继续                                          |
| `held` | false   | **有活着的流水线 tick 持闸** ⇒ **等它释放**（见下「等闸」），不抢、不清、不 force |
| `held` | true    | 持闸者已崩（超 TTL）⇒ 可以 `acquire` 接管                    |

⚠️ **必须走 `gate status` 的 `stale` 字段判据，不要自己按 ageSeconds 猜。**
⚠️ **收尾必须 `node tools/module-progress/lane.mjs gate release`** ——
提前结束也要释放，否则会挡住流水线下一轮。

#### 等闸：密集 `acquire` 竞态，不要低频 `status` 观测

`held + stale=false` 时**不要**立刻放弃 —— 等到闸释放，本轮就能正常同步。
但**不要**用「每 20 秒 `gate status` → 看到 free 再 `acquire`」：
闸的交接窗口可以**窄到零宽度**（2026-10-20 03:04:02 实测：持闸者释放，下一 tick 同一秒重新 acquire），
「先观测到 free」这种写法会**整段错过**它。

正确做法是**直接竞态 `gate acquire`**（`mkdirSync` 是原子互斥：持有且未过期时 mkdir 失败且**无任何副作用**；
空闲时 mkdir 成功即取得；`age > TTL` 时按 TTL 接管 —— 与 `gate status` 的 `stale` 是**同一套阈值**）：

```bash
cd "$PROJECT_ROOT"
while true; do
  if node tools/module-progress/lane.mjs gate acquire | grep -q '"ok": true'; then
    echo "GATE ACQUIRED"; break
  fi
  sleep 1          # 密度越高越保险；20 秒也实测够用，但窗口可能更窄
done
```

⚠️ **`.dev-agent/dispatch.gate/owner` 里的 PID 不是闸存活证据** ——
`gate acquire` 是短命进程，写完 `owner` 就退出，真正的持闸者是后续长跑的调度器。
（2026-10-20 03:15 轮实测：owner pid `15173` 查无此进程，而持闸 tick 同时正在同一 worktree 里跑 `mvnw verify`。）
判活**只能**用 `stale` 字段；活性取证要看**闸外**：同 worktree 里的构建进程（`ps`）。

⚠️ **没拿到过闸就绝对不要 `gate release`** ——
`gate release` 的实现是无条件 `rmSync(GATE_DIR)`，会把**别人活着的闸删掉**（= 清闸，破坏互斥）。
「收尾必须 release」的前提是**本轮真的 `acquire` 成功过**。

⚠️ **等闸期间不要跑第二个构建** ——
持闸 tick 的 L2（`mvnw verify`）与你的预检**共用 `backend/**/target/` 与 PG 容器** ⇒ 并发必假红。
等闸期间只做**只读**取证（`gate status` / `cat owner` / `ps` / `git log`），**连 `git fetch` 都先别跑**。

### 第 2 步：拉取并判断有没有新东西

```bash
cd "$PROJECT_ROOT"
git fetch "$PUSH_REMOTE" --prune
git merge-base --is-ancestor "$PUSH_REMOTE/main" integration/module-dev \
  && echo "NO_UPDATE" || echo "HAS_UPDATE"
```

- `NO_UPDATE` ⇒ `origin/main` 已经是集成头的祖先，**没有要同步的内容**。
  汇报一行「main 无新提交，未同步」，**释放闸后结束**。这是最常见的情况。
- `HAS_UPDATE` ⇒ 继续第 3 步。

⚠️ 顺手看一眼差异规模，汇报里要用：

```bash
git log --oneline integration/module-dev.."$PUSH_REMOTE"/main | head -20
git diff --stat integration/module-dev..."$PUSH_REMOTE"/main | tail -3
```

### 第 3 步：并入

```bash
cd "$PROJECT_ROOT"
PRE=$(git rev-parse HEAD)
git status --porcelain            # 必须为空；有改动就停下汇报，不要带着脏工作区合并
git merge --no-ff "$PUSH_REMOTE"/main -m "merge(main): sync from origin/main"
```

⚠️ **合并 commit 的正文要列清并入了哪些 main 提交**（这既是交付记录，也是回归时的定位依据）：

```bash
cd "$PROJECT_ROOT"
git log --oneline "$PRE".."$PUSH_REMOTE"/main    # 先看清要并入什么
```

正文按这个格式写（列模块/提交 ID + 一句话说明）：

```
merge(main): sync from origin/main

并入 origin/main 的 N 个提交：
- <commit> <模块ID> <名称> —— <一句话说明>
- <commit> <模块ID> <名称> —— <一句话说明>
```

⚠️ 这个 merge commit 之后若流水线 L2 红了，**它就是首要排查对象** ——
它的代码没经过本流水线的 L1/L2。

**冲突时**：

```bash
git merge --abort                # 必须 abort，否则 MERGE_HEAD 残留会堵死流水线的 merge-ready
```

然后把冲突文件名列进汇报，**不要自己解**。下一轮再试。

### 第 4 步：快速预检（不是 L2）

```bash
cd "$PROJECT_ROOT/backend" && ./mvnw -q compile
cd "$PROJECT_ROOT" && pnpm typecheck
```

- **预检红** ⇒ 说明 `main` 这次的提交与集成头**合不起来**。
  **回退**（并如实汇报，不要把编译不过的集成头推上去）：
  ```bash
  cd "$PROJECT_ROOT" && git reset --hard "$PRE"
  ```
  汇报写：`main 的 N 个提交与集成头冲突/不兼容，已回退，未推送`，
  并列出失败的具体编译错误。**不要 push 红的集成头。**
- **预检绿** ⇒ 继续第 5 步。

⚠️ 预检只覆盖编译与类型。**它不是 L2**：迁移违约、跨车道互相破坏这类问题
要到流水线的 L2 才暴露。所以第 5 步 push 之后，**下一轮流水线的 L2 必须重点看
这次 merge commit**。

### 第 5 步：刷新台账 + 推送

```bash
cd "$PROJECT_ROOT" && node tools/module-progress/sync.mjs
cd "$PROJECT_ROOT" && git push "$PUSH_REMOTE" integration/module-dev
```

- `sync.mjs` 刷出的台账数字变化（已交付 / 进行中 / 未开发）**必须写进汇报** ——
  main 带进来的交付会改变进度基线。
- push 完报告 commit hash 与并入的 main 提交数。

⚠️ **只 push 集成头，绝不 push `main`。**
⚠️ push 失败就如实报告，不要重试超过 2 次。

### 第 6 步：收尾

```bash
cd "$PROJECT_ROOT" && node tools/module-progress/lane.mjs gate release
```

**无论本轮走到哪一步（提前结束、冲突、预检红），都必须 release 闸。**

---

## 3. 汇报（10 行以内）

- **结果**：已同步（并入 N 个 main 提交，push 到 <hash>）/ 无新提交 / 冲突未并入 / 预检红已回退
- **差异规模**：并入的 main 提交数、改动文件数
- **合并 commit**：hash + 正文列出的提交清单
- **台账变化**：同步前后 已交付 / 进行中 / 未开发
- **冲突 / 预检错误**：有就点名文件与错误，没有就写「无」
- **闸**：已释放（写明）

---

## 4. 最高优先级禁令

- **不要 push 到 `main`**
- **不要在集成头上解冲突**（abort + 上报）
- **不要把编译不过的集成头推上去**（预检红 ⇒ reset 回退）
- **不要在本任务里跑全量 `mvnw verify`**（发热高峰，属于流水线的 L2）
- **不要动其他 cron 任务**
- **不要在没有拿到闸的情况下改集成头**
- **不要谎报「已同步」** —— 冲突、预检红、push 失败都要如实说
- **不要在 commit message / 汇报里贴 secret**
