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

### 🔍 CI 连续揭出三个同型缺口（本机 grep 一次漏了两个）

这三个缺口的共同形态：**服务在容器里启动失败**，而失败点按启动链顺序**逐个暴露** ——
修好一个，下一个才浮出来。CI 每一轮只暴露当时排在最前面的那个。

| 轮次 | 失败的服务 | 根因 | 处置 |
| --- | --- | --- | --- |
| 基线 `41060196055` | reporting + channel | 存储根未配 → `/app/target/...` 无写权限 | — |
| PR `41079858588` | channel | 补上两个存储根后，浮出**第三个**存储根 `certification-store` | 补 `CERTIFICATION_STORAGE_ROOT` |
| PR `41082342953` | channel | 补上三个存储根后，浮出**签名主密钥未配** `CHANNEL_WEBHOOK_SIGNING_KEY` | 补密钥生成与注入 |

### 🔍 最后一轮的系统排查（不再逐个撞）

不再靠 grep 撞，改成一次性枚举：

- 全仓**无默认值**的 `@Value` 占位符共 **3** 个：
  `CHANNEL_WEBHOOK_SIGNING_KEY` / `IDENTITY_URL` / `INTERNAL_KEY`
  ⇒ 后两个 compose 已配，**只有它缺**。
- 没有 `@ConfigurationProperties` 类的必填校验。
- `application.yml` 里的 `${DB_*}` 全部由 compose 提供。
- 五个存储根 env（`MEDIA_STORAGE_ROOT` / `CHANNEL_SECRET_STORE_DIR` /
  `CREDENTIAL_STORAGE_ROOT` / `REPORTING_EXPORT_STORAGE_ROOT` /
  `CERTIFICATION_STORAGE_ROOT`）已全部覆盖。

### 签名主密钥的修法（两个容易做错的点）

1. **值必须是 32 字节的 base64**（AES-256）。
   `WebhookSignature.keyOf` 会拒绝非 32 字节的密钥 ⇒ 用 `token_hex` 生成
   （十六进制文本，base64 解码后不是 32 字节）会失败。
   实测补齐后解码长度恰好 32 字节。
2. **compose 要用 `${VAR:?...}` 而不是普通 `${X}`**。
   普通写法在变量缺失时**静默代入空串** ⇒ 服务能启动、运行时才炸
   （`WebhookService` 构造函数**不校验**密钥长度），属于难查的静默错误。
   用 `:?` 让它在编排阶段就明确失败。

另外 `catalog-dev.sh` 原逻辑是「文件不存在才生成」，
而老环境文件是 2025-09 生成的、本来就没有这个键 ⇒ 改成**对已存在的文件也补齐缺失的键**，
同时保留原有值。

### CI 断言：让修复被持续守护

`Catalog Browser` 作业原有的「验证存储可写」只覆盖 `MEDIA_STORAGE_ROOT`
与 `CHANNEL_SECRET_STORE_DIR`。已补上三个新存储根的断言 ——
否则这个缺陷形态只要回归，CI 不会报警。

### 各轮 CI 的服务启动结果

| 轮次 | 启动成功的服务 | 失败 |
| --- | --- | --- |
| 基线 | identity / catalog / warehouse / trade / crm / finance / platform | reporting、channel |
| PR `41079858588` | 上述 7 个 + **reporting** | channel（`certification-store`） |
| PR `41082342953` | 上述 8 个 | channel（`CHANNEL_WEBHOOK_SIGNING_KEY`） |
| PR `41087784163` | **全部 9 个** | **无** ✅ |

⇒ 失败服务数 2 → 1 → 0。**`Catalog Browser & Contract Tests` 作业从 failure 变成 success。**

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

### 本机验证

| 项 | 结果 |
| --- | --- |
| `format-gate.itest.mjs` | **10 通过 / 0 失败** |
| `lane-gate.itest.mjs` | **21 通过 / 0 失败** |
| `lane-gate-reclaim.itest.mjs` | **24 通过 / 0 失败** |
| `sync.mjs --check` | ✅ 台账与蓝图一致 |
| `docker compose config` | ✅ 语法通过，六个服务 env 到位 |
| UID 10001 目录可写实测 | ✅ 三个新目录均可写，反证 `/app/target` 不可写 |
| 本次改动文件 prettier 合规 | ✅ 全部合规 |
| 全仓 `pnpm format:check` | 仍红（590 个存量文件），**比修复前的 1086 减少 496** |

### CI 侧验证（PR #70）

| 作业 | 基线 `41060196055` | PR 侧（最新轮） | 判定 |
| --- | --- | --- | --- |
| Lint（format:check） | 1085 个不合规 | **588** 个 | **净减 497、零新增** ✅ |
| Java 21 | 644 例 / **3 失败** | 644 例 / **3 失败**（同三个用例名） | **零新增失败** ✅ |
| Security Scan | 失败 | 失败 | 同一根因（lockfile 未变，见下） |
| Catalog Browser | reporting + channel 启动失败 | **失败服务从 2 个降到 1 个**，channel 失败点沿启动链前进 | **持续改善** ✅ |
| Typecheck / Unit & Integration Tests / CodeQL | 绿 | 绿 | ✅ |

闸工具结论（run `41082342953`）：**新增失败作业无 / 新增失败用例无 /
结论：可以合并**（退出码 0）。

Security Scan 两侧漏洞数相同，且本次**未改任何依赖文件**（`package.json` /
`pnpm-lock.yaml` 逐字节相同、未进改动清单）⇒ 差异来自 advisory 库自身漂移，
不是本次合入造成。

### 闸门判定为什么必须下钻

四个失败作业中有三个只能比到作业粒度。逐个下钻后：

- **Lint** 比的是**不合规文件集合**（不是文件数）：基线 1085 → PR 侧 588。
- **Java 21** 比到了**用例粒度**：两侧同为 644 例 / 3 失败，用例名逐字相同
  （`NotificationInboxIntegrationTest` / `PlatformFlagIntegrationTest` /
  `ApprovalRecordIntegrationTest`，均在 identity 模块）⇒ 存量问题，非本次引入。
- **Catalog Browser** 比的是**失败服务集合与根因链**：
  基线 {reporting, channel} → PR 侧 {channel} ⇒ 失败面收窄，非新增。
- **Security Scan** 比的是**审计条目与严重度分布**，且确认 lockfile 未变。

## 六、合并入 main（PR #70）

### 闸门判定

run `41087784163` 的结论：**新增失败作业无 / 新增失败用例无 / 可以合并**（退出码 0）。

下钻到作业粒度之后的实际对比：

| 作业 | 基线 `41060196055` | PR 侧 `41087784163` | 变化 |
| --- | --- | --- | --- |
| **Catalog Browser & Contract Tests** | failure | **success** | ✅ **修好了** |
| Lint | failure | failure | 债 1085 → 588，净减 497 |
| Java 21 | failure | failure | 两侧同为 644 例 / 3 失败，用例名逐字相同 |
| Security Scan | failure | failure | lockfile 逐字节未变 ⇒ advisory 库漂移 |
| Typecheck / Unit & Integration | success | success | 持平 |

### 合并形态：用**双父** merge commit（不是 squash）

协议第 7.4 步写的是 `--squash`，但那条是 squash 时代的写法。
`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`
（优先级 P0）把根治办法写在「待办内容」第 1 条：

> **（首选，改发布侧）发布侧把集成头的真实 commit（不 squash）推到 main**，
> 使 `origin/main` 的 tip 重新成为集成头的祖先 ⇒ 冲突从根上消失。
> **这是唯一的根治办法** —— 在集成头这边做任何解冲突都是治标。

⇒ 本轮用 `gh pr merge --merge`（双父）。上一轮 PR #69 也是这个形态。

**合并后核验**：

- `upstream/main` = `e10007ad`，**双父**（父1 `7852acdd` = 原 main 头 /
  父2 `18cf7a7e` = 集成分支头）；
- main 的树与 `upstream/integration/module-dev` **逐字节相同**（零差异）；
- **集成分支头已是 main 的祖先**（这是根治目标）；
- 双向同步模拟（`git merge-tree --write-tree`）**冲突数 0** ——
  活执行机下一轮 main → 集成分支的同步回到无冲突常态；
- main 台账：465 总数 / 437 已交付 / 0 进行中 / 28 未开发 / 完成率 94.0%
  （与合并前一致，本轮不含模块交付）。

## 七、协议更新合入 main（PR #71）

PR #70 合并后发现协议第 7.4 步仍写 `--squash`，与 P0 文档的根治建议直接冲突。
既然 main 已改双父形态，协议不改就会把后续轮次带回老路 ⇒ 单独走一个 PR 修。

改动：7.4 步改用 `gh pr merge --merge` 并写清为什么改；补上合并后四项核验
（双父 / 树逐字节相同 / 集成分支头是 main 祖先 / 双向同步零冲突）；
写清两个坑（数父提交用 `git cat-file -p` 而不是 `git log --format='%p'`，
后者恒得 0 会把双父误判成单父；GitHub 的 `--merge` 可能被静默降级）；
最高优先级禁令加「不许用 `--squash` 合 main」；
3.1 / 3.2 节表述改为区分现状与历史（main 历史里仍有 squash 时代的提交，
那些提交的文件版本确实来自较早快照 ⇒ 冲突解法仍需要）。

**PR #71 的闸门判定**（run `41091002171` vs 基线 `41090537661`）：
新增失败作业无 / 新增失败用例无 / **可以合并**（退出码 0）。

**回归守护已生效**（这是本 PR 的额外价值）：

| | 基线 `41090537661`（main） | PR #71 `41091002171` |
| --- | --- | --- |
| **Catalog Browser & Contract Tests** | **success** | **success** ✅ |
| Lint | failure（588） | failure（**588**） |
| Java 21 / Security Scan | failure | failure |

⇒ 基线侧 `Catalog Browser` 也已是 success ⇒ PR #70 的修复已落到 main 并稳定保持，
不是偶然。Lint 债稳定在 588 未回退。

**合并后核验**：`upstream/main` = `bc54cce1`，**双父**（父1 `e10007ad` = 原 main 头 /
父2 `2785f6c8` = 集成分支头）；树与集成分支逐字节相同；集成分支头已是 main 祖先；
双向同步模拟冲突数 **0**。main 台账：465 / 437 已交付 / 28 未开发 / 94.0%。

## 八、本轮未做 / 留给后续

- **589 个存量源码格式债**未重排：需单独立项、分批做（全仓一次重排 32.8 万行
  会与在途车道冲突）。
- **本机三个调度任务仍全部暂停**。活执行机（`/Users/guan/Projects/starcon`，
  远端名 `origin`）在 22:26 有提交，**是活的** ——
  它推的 `origin/integration/module-dev` 与本机 `upstream` 是同一个仓库。
  ⇒ 本机与活执行机共用同一批 `feat/<id>` 分支名，
  **本机不能启用调度器**，否则双写并撞 Flyway 版本号。
- `TOKEN_HORIZON` 延期仍只在**本机** `.dev-agent/PIPELINE.local.md` 生效，
  活执行机上必须人工改同一行，否则流水线一直停在降档模式。
