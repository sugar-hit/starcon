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
| `held` | false   | **有活着的流水线 tick 持闸** ⇒ 本轮零改动，结束并如实汇报    |
| `held` | true    | 持闸者已崩（超 TTL）⇒ 可以 `acquire` 接管                    |

⚠️ **必须走 `gate status` 的 `stale` 字段判据，不要自己按 ageSeconds 猜。**
⚠️ **收尾必须 `node tools/module-progress/lane.mjs gate release`** ——
提前结束也要释放，否则会挡住流水线下一轮。

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
