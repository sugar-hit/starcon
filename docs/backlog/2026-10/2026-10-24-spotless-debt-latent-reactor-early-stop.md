# 2026-10-24 spotless 排版债的「潜伏期」：CI 跑不到那些模块，但雷已经埋下

创建：2026-10-24。发现人：main 同步任务（并入 `main` 的闸门，第 65 轮）。
影响范围：**全部后端模块的 Java 源码格式**（`backend/**`），CI 判点 `backend` + `libraries/tenancy`。

本条不是「某个文件排版不对」，是**一条会让 CI 在未来某一轮突然变红、且原因与当时那一轮无关的雷**。

## 为什么它现在不红

CI 的 Java 作业跑的是：

```bash
./mvnw -B -ntp verify spotless:check
```

`spotless:check` 是**独立 goal**（父 pom 里没有 `<executions>` 绑定），它在每个模块内排在 `verify` 之后。
而 **reactor 会在第一个失败模块停下** ⇒

> **CI 实际判定 spotless 的模块集合 = 从 reactor 起点到第一个失败模块为止的那一段。**

实测（run `40017812044` 与基线 run `39883208132` 对照）：

| 场景                     | 第一个失败模块           | spotless 实际覆盖的模块 |
| ------------------------ | ------------------------ | ----------------------- |
| `main` 基线              | identity（测试失败）     | `backend` + `tenancy`   |
| 本 PR（修 tenancy 之前） | tenancy（spotless 失败） | `backend` + `tenancy`   |
| 本 PR（修 tenancy 之后） | identity（测试失败）     | `backend` + `tenancy`   |

⇒ 两侧的 spotless 覆盖范围**始终相同**，这就是「基线绿、PR 也绿」的原因。

## 雷埋在哪：全量口径下 main 基线自己就有 1167 个违规

按模块逐个跑 `mvn spotless:check`（本地全量口径，不受 reactor 提前中断影响）：

| 模块              | `main` 基线 | 本 PR 头 | 增量     |
| ----------------- | ----------- | -------- | -------- |
| identity          | 6           | 60       | +54      |
| catalog           | 146         | 151      | +5       |
| channel           | 486         | 513      | +27      |
| warehouse         | 40          | 54       | +14      |
| trade             | 372         | 393      | +21      |
| crm               | 117         | 117      | 0        |
| finance           | 0           | 71       | +71      |
| libraries/tenancy | 0           | 1        | +1       |
| **合计**          | **1167**    | **1360** | **+193** |

⚠️ **`main` 基线自己就有 1167 个排版违规** —— 它们一直被 identity 的测试失败掩盖着，
CI 从来没报过。⇒ **「CI 绿」在这里不代表排版干净，只代表「CI 没走到那些模块」。**

⚠️ **finance 是本 PR 新交付的模块**，`main` 上它的违规数是 **0** ⇒ 那 71 个是**纯粹的新增债**。

## 什么时候会爆

**identity 的测试缺陷一旦修好**（`PlatformFlagIntegrationTest` 的微秒精度问题），
CI 的 reactor 就会从 identity 继续往下跑 ⇒ 下一个有违规的模块立刻失败。

⇒ 这意味着：**修完 identity 那个缺陷之后，还会出现新的 CI 失败，而且失败原因与那一轮的改动毫无关系。**
这会让人误判为「刚修的修复引入了回归」，从而把正确的修复回滚。

## 处置口径

- **本 PR 只修 CI 实际判定点**（`backend` + `libraries/tenancy`）——
  全量 `spotless:apply` 会改写 1360 个文件（含删未用 import、加 `<p>` 标签等**真实代码改动**），
  **超出「合入」任务的职责**。
- **存量债（1167）不归本 PR**：那是 `main` 上既有的事实，不是本次合入造成的劣化。
- **增量债（193）需要在集成侧处理**：建议由集成侧在下一批交付前统一 `mvn spotless:apply`，
  或至少把**本次新交付模块**（finance 71 个、identity 54 个等）的违规清掉。
- ⚠️ **不要用「CI 绿」当排版干净的证据** —— 要用「全量 `spotless:check` 逐模块跑」的数字。

## 完成标准

- 全量 `mvn spotless:check` 逐模块跑，**每个模块的 `needs changes` 都是 0**。
- 特别地：**finance 模块为 0**（它是本 PR 新交付的模块，`main` 上本来就是 0）。
- `main` 基线的 1167 个存量违规单独排期（不阻塞本 PR，但要让 CI 的判定覆盖范围与实际债对齐）。

## 状态

- **Open**。CI 判定点已修（`backend` + `tenancy` 合规），全量增量待集成侧处理。
