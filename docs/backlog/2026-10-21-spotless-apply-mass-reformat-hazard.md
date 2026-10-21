# 车道跑 `spotless:apply` 会把整仓 146 个文件重排版 —— 流水线任务书应加禁令

> 记录时间：2026-10-21 09:1x（batch-47/48 tick，5.5.1.1 修复车道）
> 现象等级：**P1（会污染交付物，但不影响功能正确性）**

## 现象

修复车道 `fix/5-5-1-1-price-history-window` 的 worktree 里出现了 **146 个文件**的修改，
`git diff --stat` = 8832 insertions / 6177 deletions。逐条看 diff，全部是
**google-java-format 把 javadoc 段落重新折行**（`<p>…` 长段落被合成一行），
**没有一行是本车道该改的业务逻辑** —— 而其中 **0 个文件**属于 5.5.1.1 价格历史。

同批的另外 3 条车道（`6.4.2.1` / `6.5.1.1` / `6.5.2.1`）在同样的 worktree 里
`git status --short` 均为 **0 行** ⇒ 这不是构建期副作用，是**某一次显式调用**造成的。

## 根因

- `backend/pom.xml` 里 `spotless-maven-plugin` 只有 `<configuration>`，**没有 `<executions>`**
  ⇒ **没有绑定到任何生命周期阶段**，`mvnw compile` / `mvnw test` 都不会触发它。
- ⇒ 触发者只能是**显式执行了 `spotless:apply`（或 `spotless:check`）**。
- 协议里早就写着「`spotless:apply` 在 Java 25 上崩溃是仓库既有问题，未绑定构建生命周期，不阻塞」，
  但**只说了"会崩"，没说"崩之前已经把文件改了"** —— google-java-format 1.22.0 在 Java 25 上
  处理失败时会退回到可用的格式化路径，把 146 个文件按 google 风格重排版后**才**报错。
  崩溃 ≠ 没改文件。

## 为什么必须处理（不是"只是噪音"）

1. **它会撑爆 diff 并制造并入冲突**：被重排版的是 146 个文件，其中包含
   已被别的车道改过的生产代码（如 5.4.2.1 的 `PlatformPriceSync*`）。
   集成头里那些文件是**原格式**，修复分支里是**重排版** ⇒ git 三方合并看到两边都改 ⇒ 冲突。
2. **`land` 的"工作区干净"检查拦不住它** —— 只要 worker 把它一起 commit，工作区就是干净的，
   脏的是**内容**。
3. **污染的是交付物的可读性**：一次修复任务夹带 8832 行无关重排版，评审时根本看不出改了什么。

## 处置（本轮已做）

- 调度者发现后**立即 steer 修复车道**：先另存实质改动 → `git checkout --` 回滚白名单外的全部文件 →
  **禁止再跑 `spotless:apply` / `fmt:apply`** → 回滚后**重跑 L0 编译闸**确认仍绿。
- 验收口径：最终 `git status --short` 的修改文件数 **≤ 5**（5.5.1.1 测试文件 + 台账 1 行 + 会话日志 1 个），
  且**不含任何非本模块的生产代码**；交付报告须贴回滚前后的文件数对比与回滚后的完整 `git status` 输出。

## 建议（给下一次改任务书的人 / 改 PIPELINE.md 的人）

1. **在 `tools/module-progress/PIPELINE.md` 的子任务模板「硬性要求」里加一条禁令**：
   **禁止执行 `spotless:apply` / `spotless:check` / `fmt:apply`** ——
   本仓 Java 格式化不归流水线管；`spotless` 未绑定构建生命周期，且在 Java 25 上**会先改文件再崩**。
   风格要求改为「与被改文件周围保持一致即可」。
2. 调度者侧可加一道**并入前静态闸**：对候选车道的分支做
   `git diff --stat <base> <branch>`，若**改动文件数 > 20** 或**改动集中在与该模块无关的文件**，
   视为「疑似批量格式化 / 越界改动」→ **先 steer 回滚再并入**，不要让脏内容进集成头。
3. 更根本的一条（**需要人决策**）：仓库到底要不要统一用 google-java-format？
   现在是「有配置、无执行入口、无 CI 校验」的三不管状态 ——
   要么接进 CI 做 check，要么把插件从 pom 里拿掉。现状下它只会制造噪音。

## 关联

- `docs/sessions/2026-10/2026-10-21-batch-47-consignment-pricing-request-certification.md` —— 本轮 L2 诊断
- `docs/sessions/2026-10/2026-10-21-batch-48-quality-reconciliation-analytics.md` —— 本轮第二组车道
- `tools/module-progress/PIPELINE.md` —— 需要加禁令的子任务模板
