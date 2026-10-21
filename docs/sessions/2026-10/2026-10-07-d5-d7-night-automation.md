# 2026-10-07 夜间 D5–D7 冲刺自动化作业手册

> 本文件是夜间两个定时自动化任务的**唯一事实来源**。任务在独立会话中运行，没有历史上下文，
> 每次运行前必须先读本文件。

## 0. 切片 0（最高优先级，先做）：修 CI 的 pnpm 版本重复

**背景**：这个仓库的 CI **从 2026-09-25 第一次运行起就没绿过**，main 的每次 push、每个 PR 全部 failure。
根因与代码质量无关：

```
Error: Multiple versions of pnpm specified:
Remove one of these versions to avoid version mismatch errors like ERR_PNPM_BAD_PM_VERSION
```

- `package.json:6` 已有 `"packageManager": "pnpm@9.0.0"`
- `.github/workflows/ci.yml` 里 **3 个 job**（lint / typecheck / test）都用 `pnpm/action-setup@v4` 且带 `with: version: 9`
- `pnpm/action-setup@v4` 在「action 输入 version」与「package.json 的 packageManager」**同时存在**时直接报错退出

**修法**：删掉 `ci.yml` 三处 `pnpm/action-setup@v4` 下的 `with: version: 9` 整块，让 `packageManager` 单一来源驱动版本
（这是 pnpm 官方推荐方向，也与 AGENTS.md「包管理器 pnpm >= 9.0.0（package.json#packageManager）」一致）。
**不要**反过来删 `packageManager`。

**判定**：修完后 `gh pr checks <n>` 应从 `fail` 变 `pass`。若仍红，才是真实代码问题。
在此之前，**任何 MR 的红 CI 都不代表该切片代码有问题**，不要据此回滚或重做切片。

## 1. 目标与时间盒

在 **21:00 – 24:00（Asia/Shanghai）** 内推进 D5 阶段需求，每个功能切片独立分支、
独立提交、开独立 MR。

**现实预期**：D5 覆盖系统 02 / 04 / 09 / 10，而 D6 依赖的 finance、D7 依赖的
reporting/support 服务尚不存在。今晚 3 小时（约 6 个 30 分钟周期）现实产出是
**4–6 个可验证纵向切片**，不是把 12 个系统做完。不做空壳（见 `docs/sessions/2026-09/2026-09-29-channel-service-and-d1-d2-start.md`）。

## 2. 环境事实（必读，否则会踩坑）

| 事项 | 事实 | 应对 |
|---|---|---|
| git | `/usr/bin/git` 被 Xcode 许可协议挡住，报 `You have not agreed to the Xcode license agreements` | **必须**用 `/Library/Developer/CommandLineTools/usr/bin/git`。所有命令前置 `export PATH=/Library/Developer/CommandLineTools/usr/bin:$PATH` |
| Docker Desktop | 需运行状态，集成测试靠 Testcontainers 起真实 PostgreSQL | 已启动。若报 `Could not find a valid Docker environment`，执行 `open -a Docker` 后等 20 秒重试。**不要改 `~/.docker/daemon.json`、不要重启 Docker Desktop** —— 里面有 crewhub / langfuse 容器在跑，重启会打断 |
| **Docker Hub 不通** | `registry-1.docker.io` 直连返回 EOF（网络层被挡），但 `github.com` 等外网正常 | Testcontainers 拉镜像会 2 分钟超时，报 `Can't get Docker image: testcontainers/ryuk:0.12.0` → **看起来像 Docker 环境问题，实际是拉不到镜像**。已用镜像加速源拉取并打回原名，`postgres:16-alpine` 与 `testcontainers/ryuk:0.12.0` 均已在本地 |
| jOOQ 绑定 | **只有 `LocalDateTime` 可绑**；`Instant`/`OffsetDateTime`/String 会绑成 varchar | SQL 里写 `?::timestamptz`，或用 `cast(? as ...)`；直接用 String 会 bad grammar |
| jOOQ 搜索 | `where (?='' or a ilike ?)` 这类混合写法会让 jOOQ 误判参数个数 | 在 Java 侧决定是否拼接过滤片段 |
| RLS 权限码 | `channel_operation()` 返回的已是完整权限码 | 策略里**不要再拼前缀**，否则永不命中 |
| `spotless:apply` | 在 Java 25 上崩溃（`NoSuchMethodError`），仓库既有问题 | 不要跑；`./mvnw verify` 不受影响 |
| 契约可空性 | SpringDoc 从 Bean Validation 推导 `required`，可空 `UUID` 会被标必填 | 用 `@Schema(nullable = true)` 在后端显式声明，不要改 codegen |

## 2.1 镜像被清理后如何恢复（重要）

新服务若换了 PostgreSQL 版本，或镜像被 prune，重新用加速源拉取并**打回 Testcontainers 期望的原名**
（Testcontainers 只认原名，不认带加速源前缀的名字）：

```bash
M=docker.m.daocloud.io        # 备用：docker.1ms.run（已验证两个的 /v2/ 都返回 401 = 活的）
IMG=postgres:16-alpine
docker pull $M/library/$IMG && docker tag $M/library/$IMG $IMG
# ryuk 同理（注意它不在 library/ 下）：
docker pull $M/testcontainers/ryuk:0.12.0 && docker tag $M/testcontainers/ryuk:0.12.0 testcontainers/ryuk:0.12.0
```

验证是否已就位：`docker images | grep -E 'ryuk|postgres:16'`。

**不要用 `postgres:15-alpine` 冒充 `16-alpine`**（本地有 15，但项目基线是 PostgreSQL 16，
改标签会让门禁假绿、掩盖真实不兼容）。

## 3. Worktree 隔离（两个任务必须遵守）

两个定时任务并发跑 git 会互相踩（都���要切分支）。因此：

| 任务 | 工作目录 | 分支 |
|---|---|---|
| 开发循环 | `/Users/guan/Projects/starcon-worktrees/dev` | `codex/dev-runner` |
| MR / 冲突循环 | `/Users/guan/Projects/starcon-worktrees/mr` | `codex/mr-runner` |

- 主仓库 `/Users/guan/Projects/starcon` 留给人工，**两个任务都不要在里面 checkout 分支**。
- 需要在别处操作时用 `git -C <worktree> ...`。
- 新建功能分支时基于 `main`（必要时先 `git fetch origin && git rebase origin/main`）。

## 4. 今晚工作队列（按优先级往下取，不要跳过编号硬做后面的）

前置事实：`identity` / `catalog` / `channel` 三个服务已落地（D1–D2 闭环，39 个后端测试全绿）。
`trade` / `warehouse` / `finance` **服务不存在**，D5 的订单与库存回写都依赖它们。

| # | 分支 slug | 内容 | 归属系统 |
|---|---|---|---|
| **0** | `ci-pnpm-version-dedup` | **修 CI：删 `ci.yml` 三处 `pnpm/action-setup` 的 `version: 9`（见第 0 节）。这是唯一能让 CI 变绿的改动，优先于一切业务切片** | CI 基建 |
| 1 | `d5-channel-sync-cursor` | channel：`sync_cursor` / `sync_run` / `sync_failure` 迁移 + RLS + 模型层 + 集成测试（游标只在处理提交后推进、失败可续传） | 02.4 |
| 2 | `d5-channel-webhook-receipt` | channel：`webhook_receipt` / `external_snapshot` 迁移 + 签名验证端点 + 外部事件 ID 去重 | 02.6 |
| 3 | `d5-channel-platform-operation` | channel：`platform_operation` 迁移 + 持久化任务模型（业务幂等键、外部请求 ID、未知结果核验） | 02.5 |
| 4 | `d5-trade-service-skeleton` | 新建 trade 服务骨架：pom / `application.yml` / Application / AccessService / ApiErrorHandler / `backend/pom.xml` 加 module / 契约 codegen 接入 | 09 |
| 5 | `d5-trade-sales-order` | trade：`sales_order` / `sales_order_line` / `order_address_snapshot` / `order_charge` / `order_charge_allocation` 迁移 + RLS + 订单录入闭环（外部订单号按来源范围唯一；分摊之和等于头金额，保留舍入差额） | 09 |
| 6 | `d5-trade-order-status` | trade：`order_status_history` + `fulfillment_request` / `_line` / `fulfillment_process` + 审单状态机（一订单可多次多仓履约，不设 order_id 唯一） | 09 |

前端页面属**stretch goal**，切片后端 + 契约 + 测试全绿后再做；时间不够就不做，不要为了凑页面牺牲测试。

## 5. 每个切片的执行契约

1. 从 `main` 切分支 `codex/<slug>`。
2. 按 `db/schemas/v2/README.md` 的实体设计写 Flyway 迁移（该文件是数据模型设计入口，写迁移前必读）。
3. Java 侧：jOOQ + 显式 SQL，金额用 `BigDecimal` + `NUMERIC`，业务 ID 用 record 值对象。
4. **必须有测试**：集成测试用 Testcontainers PostgreSQL，且必须实跑 RLS 三条断言 ——
   两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户。
5. 重新生成契约，**必须走离线快照路径**（不要去启动 3 个服务）：
   ```bash
   cd backend && ./mvnw -Dtest=OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test && cd ..
   python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
   ```
   原理：`OpenApiContractTest` 本就随 `./mvnw verify` 运行，并把各服务 spec 导出到
   `backend/target/openapi/{identity,catalog,channel}.openapi.json`。
   `pnpm contracts:generate` 的默认形态会去访问 127.0.0.1:8081/8082/8083 的**活服务**，夜间不要用。
   `packages/api-contracts` 与 `packages/sdk` 是 codegen 产物，**禁止手改**，要改就改后端 DTO 再生成。
6. 会话记录：本轮关键决策落到 `docs/sessions/YYYY-MM/`，按 AGENTS.md 收录规范。

## 6. 门禁（AGENTS.md 规定：全绿才能合，不可跳过）

**已验证基线（2026-10-07 21:15 实测，worktree 内）**：

| 门禁 | 基线结果 | 耗时 |
|---|---|---|
| `./mvnw verify` | identity 8 + catalog 11 + channel 20 = **39 tests 全过**，BUILD SUCCESS | 约 3 分钟（依赖已缓存） |
| `pnpm lint` | 3/3 successful | 秒级（turbo 缓存） |
| `pnpm typecheck` | 3/3 successful | 秒级（turbo 缓存） |
| `pnpm test` | 10 files / **61 tests 全过** | 约 0.6s |

**基线数字变了不是回归，是上一轮切片的成果**（新增测试会让计数上升）。
只有**计数下降或出现 Failures/Errors** 才是回归。

```bash
export PATH=/Library/Developer/CommandLineTools/usr/bin:$PATH
cd /Users/guan/Projects/starcon-worktrees/dev/backend && ./mvnw verify
pnpm lint && pnpm typecheck && pnpm test
```

**门禁不过就不 push、不开 MR**，把失败原因写进报告。宁可少交付，不交红。

## 7. MR 与冲突策略（已获用户授权）

- 自动 push 分支并**自动创建 MR**（`gh pr create`），base 为 `main`。
- **绝不自动 merge 到 main**，绝不 push 到 `main`。
- **自动解决机械性冲突**：锁文件、`import` 排序、同一文件内双方各自新增不重叠段落、
  双方新增同名但内容等价的文件。解决后必须重跑第 6 节门禁，绿了才 push。
- **语义歧义一律停下报告，绝不猜**：涉及同一函数/同一迁移版本号的双方改动、
  同一 SQL 语句双方都改、契约字段增删冲突、数据库迁移顺序冲突。
  停下后在报告里写清：分支名、冲突文件、双方意图、需要用户做的决策。

## 8. 停止条件

- 到 **24:00** 立即停止：新切片不再开，已开分支不再加新功能，只保证已开的 MR 是绿的。
- 队列 6 项全部完成：停止开发循环，只保留 MR / 冲突循环。
- 连续 2 个周期因同一原因失败：停止重试，在报告里升级为需要人工决策的问题。

## 9. 每轮报告格式

```
## 本轮（HH:MM）
切片: <slug>  分支: codex/<slug>  MR: <链接或 未开及原因>
门禁: mvnw verify <绿/红> · pnpm lint/typecheck/test <绿/红>
冲突: <无 / 已解机械性 X 处 / 停下待人工决策 Y 处>
下一步: <下一个切片，或停止原因>
```
