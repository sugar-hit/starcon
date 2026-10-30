# 2026-10-30: 工程遗留三项修复 —— 增量格式闸 / CI 存储根 / 残留 worktree

> 时间：2026-10-30 21:07 → 23:30（Asia/Shanghai）
> 执行机：`/Users/tommy/Project/starcon`（本机是**只读复核 + 合库 + 看板发布**端，
> 模块开发由另一台机器 `/Users/guan/Projects/starcon` 承担）
> 分支：`integration/module-dev`

## 摘要

承接 round-136 遗留的三项工程问题，逐项取证后修复。**未派发任何车道**：
本机三个调度任务全部 `enabled: false`，活执行机自 10-11 起无新车道提交，
两套安装同时指向同一批 `feat/<id>` 分支名会双写并撞 Flyway 版本号。

三项都是**纯仓库改动**，不碰活执行机的车道命名空间。

## 一、增量格式闸（堵住格式债进 main 的口子）

### 问题取证

- `pnpm lint`（ESLint）**不含 prettier 插件** ⇒ 抓不到格式问题。
- CI 的 `Lint` 作业跑 `pnpm format:check` ⇒ 长期红（1085 个文件）。
- L2 批次闸只跑 `mvnw verify` + `typecheck/lint/test` + Next build + `sync --check`，
  **不跑格式检查** ⇒ 每批车道带进来的排版债畅通无阻进 main。
- 实测：**2026-10-30 当天新增的 19 个 `apps/web/src` 文件里 11 个不合规**。

### 为什么不能直接加全仓 `format:check`

全仓有 **589** 个不合规文件（`apps/web/src` 582 + `packages/ui` 3 +
`tools/module-progress` 4）。挂进 L2 ⇒ L2 从第一次运行起就是红的，
而 L2 红的协议是「不 push → 二分定位 → 连续 3 轮红则本轮零派发」
⇒ **流水线会被与本批代码无关的历史债彻底堵死**。

这与「全仓 `spotless:apply` 重排 1360 个 Java 文件」是同构的污染形状：
用存量债的体量去压垮增量流程。

### 解法：只查本批

新增 `tools/module-progress/format-gate.mjs`：

- **批次边界** = 集成头上的 merge commit（标题形如 `merge(<模块ID>): <名称>`，
  由 `lane.mjs merge-ready` 生成）。取本批第一个 merge commit 的第一父作为 base，
  `git diff base...head --name-only` 得文件集合。
- 只对其中 prettier 管辖（扩展名 + `.prettierignore` 过滤）的文件跑 `--list-different`。
- 退出码：0 = 本批合规；1 = 有违规（`violations` 列文件名）；2 = 用法/环境错误。

**核心保证两条**，都有自测锁住（`format-gate.itest.mjs`，10 项全绿）：

| 保证 | 断言 |
| --- | --- |
| 存量债**不得**让闸打红 | 存量债文件不在 `violations` 里 |
| 本批新增**必须**拦 | 本批不合规文件被抓出来 |

另外锁住：批次 merge commit 数识别、`.prettierignore` 过滤、显式 `--files`、
无批次时跳过、分支不存在时报错而非误报「无需检查」。

### Markdown 为什么不管

`docs/` 全域加入 `.prettierignore`。理由是**配置与约定冲突**，不是代码质量问题：

- 仓库对 Markdown 表格的既有约定是**紧凑写法** `| --- | --- |`
  （实测 `docs/backlog` 近 30 天新增 71 个文件，紧凑写法 46 处、对齐写法 **0** 处）。
- prettier 会把表格重排成按列宽对齐的空格填充 ⇒ 两者系统性冲突。
- 后果是「每轮流水线都产出一批新债，永远还不完」，而非内容有错。
- 排除 `docs/` 后全仓债 **1086 → 589**，剩下的全是真实代码排版债。

### 实测效果

在真实集成头上跑闸：识别出 7 个 merge commit、111 个改动文件、50 个受检文件，
抓出 12 个不合规。用 `pnpm exec prettier --list-different` 独立交叉验证，
判定完全一致（无误报）。

### 存量债怎么办

589 个历史债文件**单独立项、分批重排**（每批限定模块范围），
不与闸的接入混在一起做。全仓重排 32.8 万行会产生巨量 diff，
且 511 个文件在近 30 天内被流水线持续改动 ⇒ 必然与在途车道冲突。

### 协议改动

- `PIPELINE.md`：L2 表格行补「增量格式闸」；新增「增量格式闸」章节；
  5.2 节命令块加 `format-gate.mjs`；**worker 任务书**的 L0 闸加格式自检
  （只查自己改动过的文件），让债在车道侧就被拦下。
- `PIPELINE_CRON_PROMPT.md`：L2 表格行同步；5.2 节命令块加 `format-gate.mjs`
  + 闸红处置说明。

## 二、CI 容器起不来（reporting 与 channel）

### 根因链条（真实容器内取证）

- `Dockerfile.backend`：`WORKDIR /app`，进程以 `USER 10001` 运行。
- `LocalReportingExportStorage` / `LocalCredentialDocumentStorage`
  在**构造函数**里就 `Files.createDirectories(root)`，失败抛 `IllegalStateException`
  ⇒ Spring 起不来。
- 未配 env 时用**相对路径默认值**（`target/...`），相对路径按进程工作目录解析
  ⇒ 容器里解析成 `/app/target/...`。
- `/app` 属主是 root，UID 10001 无写权限
  ⇒ `AccessDeniedException: /app/target` ⇒ 容器启动失败。

**真实容器内实测**（`starcon-catalog-dev-channel-1`）：

```
uid=10001(10001) gid=0(root)
pwd → /app
mkdir -p target/credential-store → Permission denied
```

### 修复

照 `catalog` 服务既有形态（`MEDIA_STORAGE_ROOT` + 挂载卷）：

- `Dockerfile.backend`：合并成一个 RUN 层，建 `/app/data/reporting-export`
  与 `/app/data/credential-store`，`chown -R 10001:10001`，
  权限 750（导出）/ 700（凭证，与 channel-secrets 一致）。
- `docker-compose.catalog.yml`：
  - `reporting` 服务加 `REPORTING_EXPORT_STORAGE_ROOT: /app/data/reporting-export`
    + 挂载 `reporting-export-data`。
  - `channel` 服务加 `CREDENTIAL_STORAGE_ROOT: /app/data/credential-store`
    + 挂载 `channel-credential-data`。
  - `migrate-reporting` / `migrate-channel` 也补同名 env ——
    **它们用同一镜像同一 Spring 上下文，存储实现的构造函数照样会执行**。
  - volumes 声明补两个新卷。

### 验证

- `docker compose config` 渲染确认：四个服务（reporting / channel /
  migrate-reporting / migrate-channel）env 全部到位，路径与 Dockerfile 一致。
- 用最小镜像实跑 Dockerfile 的 RUN 步骤，以 UID 10001 实测：
  - `reporting-export` 与 `credential-store` **可写** ✅
  - 反证 `/app/target` 仍 `Permission denied` ⇒ 精确复现原故障条件 ✅
- 完整镜像重建在本机**未完成**：Maven 依赖拉取阶段无网络活动，改为轻量验证路径。
  ⇒ **CI 上的实际效果尚未由远端验证**，需下一轮看 CI 的 `Catalog Browser` 作业。

## 三、残留 worktree 清理

`.worktrees/main-merge-204536`（10-24 残留）占着本地 `chore/sync-module-dev`，
里面有 816 个文件的未提交合并内容（42 万行插入）。

**清理前逐项取证**（不靠印象）：

| 检查 | 结果 |
| --- | --- |
| 残留 HEAD 是否已被上游包含 | ✅ `6522c497` 已是 `upstream/integration/module-dev` 的祖先 |
| 本地分支独有提交 | **0**（1275 个全是远端独有） |
| 816 个文件逐个比对内容 | 774 个完全一致，42 个不同，0 个已被删除 |
| 42 个差异文件的独有内容 | `lane.mjs` = 旧版闸实现（TTL 600 时代）；集成头是重写版（TTL 4200 + keepalive + reclaim）；`ci.yml` = 少 `platform`/`reporting` 服务与 8088/8089 端口；`api-proxy.ts` 独有行仅是排版差异，**无任何独有白名单路由**；契约文件零独有行；台账两版都 465 行 |
| 结论 | 集成头版本是残留的**更新版**，残留无任何独有成果 |

处置：`git worktree remove --force` → 删除本地 `chore/sync-module-dev`
（远端同名分支保留）→ 验证同名分支现在可正常创建（round-136 记录的阻塞已解除）。

清理前已把暂存区内容备份到 `.tmp/wt-backup-204536/staged.patch`（21MB）作为保险。

## 四、`.gitignore` 提交

上一轮按用户要求把 ERP 产品论纲、官网栏目方案及其会话记录加入 `.gitignore`，
但**未提交**。本轮提交规则文件本身（`e40b64e5`），被挡住的文档内容不提交。

提交前验证三个文件确实未被 Git 跟踪，`git check-ignore` 规则精准命中。

## 五、验证结果汇总

| 项 | 结果 |
| --- | --- |
| `format-gate.itest.mjs` | **10 通过 / 0 失败** |
| `lane-gate.itest.mjs` | **21 通过 / 0 失败** |
| `lane-gate-reclaim.itest.mjs` | **24 通过 / 0 失败** |
| `sync.mjs --check` | ✅ 台账与蓝图一致 |
| `docker compose config` | ✅ 语法通过，四个服务 env 到位 |
| UID 10001 目录可写实测 | ✅ 通过 |
| 本次改动文件 prettier 合规 | ✅ 全部合规 |
| 全仓 `pnpm format:check` | 仍红（590 个存量文件），**比修复前的 1086 减少 496** |
| CI `Catalog Browser` 实际效果 | ⚠️ **未验证** —— 本机未能完成镜像重建 |

## 六、本轮未做 / 留给后续

- **589 个存量源码格式债**未重排：需单独立项、分批做（全仓一次重排 32.8 万行
  会与在途车道冲突）。
- **CI `Catalog Browser` 作业的实际效果未验证**：本机 Maven 依赖拉取无网络活动。
  下一轮看远端 CI 结果，确认 reporting 与 channel 能正常起来。
- **本机三个调度任务仍全部暂停**，活执行机自 10-11 起无新车道提交。
  若要本机接管，需先确认活机器调度器已停。
- `TOKEN_HORIZON` 延期仍只在**本机** `.dev-agent/PIPELINE.local.md` 生效，
  活执行机上必须人工改同一行，否则流水线一直停在降档模式。
