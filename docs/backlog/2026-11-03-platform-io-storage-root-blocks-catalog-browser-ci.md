# platform 服务导入导出存储根目录不可写 ⇒ 容器内启动失败 ⇒ Catalog Browser CI 作业挂 ⇒ 阻塞 main 合入

> 发现时间：2026-11-03 「每日合 main」10:30 轮次（PR #72 的 CI 闸工具判定为「阻塞」时定位）
> 严重度：**高** —— 阻塞 `integration/module-dev` 合入 `main`
> 归属：`backend/services/platform` + `deploy/docker/docker-compose.catalog.yml`
> 状态：**已修复并合入 `main`**（2026-11-03，提交 `fd5aa652`，经 PR #73 合入）—— 本条关闭

---

## 现象

PR #72（`chore/sync-module-dev` → `main`）的 CI run `#41566934482` 中，
`Catalog Browser & Contract Tests` 作业**失败**；而 `main` 基线 run `#41093657737` 的同一作业**通过**。

⇒ 基线相对判据下属于**新增失败作业** ⇒ 闸工具（PR 头那份 `tools/module-progress/ci-gate.mjs`）结论「阻塞」，本轮未合并。

作业日志（job `#125690595309`）中 platform 容器连续两次启动失败：

```
platform-1 | ERROR o.s.boot.SpringApplication : Application run failed
  UnsatisfiedDependencyException: Error creating bean with name 'dataMigrationController'
    ... Unsatisfied dependency expressed through constructor parameter 1:
    ... Error creating bean with name 'dataMigrationService'
    ... Error creating bean with name 'localImportExportStorage'
    ... Failed to instantiate [LocalImportExportStorage]: Constructor threw exception
  Caused by: java.lang.IllegalStateException: 无法创建导入导出存储根目录：/app/target/platform-io-store
  Caused by: java.nio.file.AccessDeniedException: /app/target
```

## 根因

`backend/services/platform/src/main/java/com/starcon/platform/LocalImportExportStorage.java`
（**本次新增**，`main` 上不存在）构造器里：

```java
public LocalImportExportStorage(
    @Value("${PLATFORM_IO_STORAGE_ROOT:target/platform-io-store}") String root) {
  this.root = Path.of(root).toAbsolutePath().normalize();
  Files.createDirectories(this.root);   // ← 不可写时直接抛
}
```

默认值是**相对路径** `target/platform-io-store`，按进程工作目录解析：

| 位置       | 解析结果                                                    | 可写性                                         |
| ---------- | ----------------------------------------------------------- | ---------------------------------------------- |
| 本地开发机 | `<repo>/backend/services/platform/target/platform-io-store` | 可写                                           |
| CI 容器    | `/app/target/platform-io-store`                             | ❌ `/app` 属主 root，进程以 UID **10001** 运行 |

⇒ `AccessDeniedException: /app/target` ⇒ 构造器抛 `IllegalStateException`
⇒ `dataMigrationService` / `dataMigrationController` bean 创建失败
⇒ **platform 服务起不来** ⇒ CI 的 `Wait for all services` 等 8089 端口超时 ⇒ 作业失败。

关键缺口：`deploy/docker/docker-compose.catalog.yml` 的 `platform` 服务**没有配** `PLATFORM_IO_STORAGE_ROOT`：

```yaml
platform:
  environment:
    DB_URL: jdbc:postgresql://postgres:5432/starcon_platform
    ...
    # ← 缺 PLATFORM_IO_STORAGE_ROOT
```

同批次的其他服务都配了（`deploy/docker/Dockerfile.backend` 里 `/app/data/*` 已 `chown 10001:10001`）：

| 服务         | 环境变量                                              | 容器内路径                       |
| ------------ | ----------------------------------------------------- | -------------------------------- |
| catalog      | `MEDIA_STORAGE_ROOT`                                  | `/app/data/media`                |
| channel      | `CREDENTIAL_STORAGE_ROOT`、`CHANNEL_SECRET_STORE_DIR` | `/app/data/channel-secrets`      |
| reporting    | `REPORTING_EXPORT_STORAGE_ROOT`                       | `/app/data/...`                  |
| **platform** | **（缺）**                                            | **（退化为 `/app/target/...`）** |

`.github/workflows/ci.yml:140-147` 已经把这个失败模式写进了注释
（「目录不存在或不可写会让服务直接启动失败 —— 漏配 env 时它们退回相对路径默认值，
在容器里解析到 `/app/target/...`，而 `/app` 属主是 root、进程以 UID 10001 运行 ⇒ AccessDeniedException」），
但**断言里没有 platform**，说明是「已知模式，新增服务时漏配」。

## 修复方向（二选一）

1. **compose 补 env（推荐，与既有服务一致）**
   在 `deploy/docker/docker-compose.catalog.yml` 的 `platform.environment` 下加
   `PLATFORM_IO_STORAGE_ROOT: /app/data/platform-io-store`，
   并在 `deploy/docker/Dockerfile.backend` 的 `RUN mkdir -p /app/data/...` 里建该目录（同属主 10001）。
   同时在 `ci.yml` 的「Verify writable storage as the runtime user」里补一条 platform 断言。
2. **改 Java 默认值**：把默认值从相对路径改成一个容器内一定可写的绝对路径 —— 但会牺牲
   「开发机与容器行为一致」的初衷，类注释里已明确拒绝给绝对路径默认值，**不推荐**。

## 解除条件

- 修复提交进入 `integration/module-dev`；
- 本任务下一轮把同步分支更新到含修复的提交、重跑 CI；
- `Catalog Browser & Contract Tests` 相对 `main` 基线不再新增失败 ⇒ 闸工具给「可以合并」⇒ 合 PR。

## 影响面备注

- 该失败**与合并动作无关**：合并结果里 `backend/services/platform` 的树与 `origin/integration/module-dev` 逐字节相同
  ⇒ 是**集成分支内容固有的缺陷**，不是合并引入的回归。
- 集成头的本地 L2 不会暴露它（它跑的是单元/集成测试，不起 compose 全栈）⇒ 需要 CI 的 E2E 作业才能发现。
- 未修复前，**每次「合 main」轮次都会被同一个原因阻塞**。

---

## 处置结果（2026-11-03 关闭）

**修复落点**：提交 `fd5aa652`「fix(platform): 补 PLATFORM_IO_STORAGE_ROOT —— 存储根退回相对路径导致容器内 platform 起不来」，
按上面「修复方向」的第 1 种做法落地：

- `deploy/docker/docker-compose.catalog.yml` 的 `platform.environment` 补 `PLATFORM_IO_STORAGE_ROOT: /app/data/platform-io-store`
- `deploy/docker/Dockerfile.backend` 建 `/app/data/platform-io-store` 并与 `/app/data/*` 一同 `chown 10001:10001`
- `LocalImportExportStorage` 的默认值仍是相对路径 `target/platform-io-store`（按原设计保留「开发机与容器行为一致」，
  不改成绝对路径默认值）

**合入路径**：`fd5aa652` → 集成分支 → PR #73（`chore/sync-module-dev` → `main`）→ 已合并。

**解除条件逐条核对**：

| 解除条件 | 证据 |
| --- | --- |
| 修复提交进入 `integration/module-dev` | `fd5aa652` 在 `origin/integration/module-dev` 上，且 `git merge-base --is-ancestor fd5aa652 origin/main` 成立 |
| 同步分支更新到含修复的提交、重跑 CI | PR #73 的 CI run `#41621594784`（main，2026-11-03 10:43） |
| `Catalog Browser & Contract Tests` 相对 main 基线不再新增失败 | 该 run 中 `Catalog Browser & Contract Tests` = **success**；而修复前的基线 run `#41093657737` 中同一作业虽通过，但 platform 容器起不来的失败模式已消除 |

**顺带补上的回归防线**：`.github/workflows/ci.yml` 的「Verify writable storage as the runtime user」现在包含 platform 一条
（`test -w "$PLATFORM_IO_STORAGE_ROOT"`），同类「存储根漏配 ⇒ 容器内起不来」的失败模式不会再只靠人眼发现。

**仍留的尾巴（另立条目跟踪，不在本条内）**：集成头的本地 L2 覆盖不到 compose 全栈启动 ⇒ 「容器内起不来」这一类缺陷
仍需要在流水线侧补一道冒烟，不能只靠 CI 的 E2E 作业兜底。

