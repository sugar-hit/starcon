# 2026-11-03: module-dev 合 main 被 platform 存储根目录缺陷阻塞

## 摘要

每日「合 main + 刷看板」10:30 轮次：main 无新提交（同步步骤整体跳过）；
`integration/module-dev` → `main` 的合并走完「建分支 → 合并 → 解冲突 → 验证 → 推分支 → 建 PR」全流程，
本地定向验证全绿，但 CI 出现 `main` 基线里没有的新增失败作业（`Catalog Browser & Contract Tests`），
按基线相对判据**本轮不合并**；PR #72 保持开启待下轮跟进。交付进度看板已原地刷新发布（数字与上次一致，因 main 未前进）。

## 决策 / 结论

- **同步步骤整体跳过**：`git merge-base --is-ancestor origin/main integration/module-dev` 成立 ⇒ main 无新提交
  ⇒ 按协议不取闸、不 merge、不预检、不写文档，直接进合并流程。
- **合并形态 = 真合并无冲突**（`merge-tree` 报 0 个冲突文件，落地树 `79e624e6…`）。
  1.5 放行判据通过（`main` 上的 `merge-union.mjs` 已引用 `json-format.py`）⇒ 契约产物可随本次一并合入，
  无需拆 PR#1/PR#2。
- **本轮不合 PR**：CI 闸工具（**PR 头那份** `ci-gate.mjs`，`unreachedJobs` 计数 6，确认是修复版）
  结论「阻塞」—— 新增失败作业 `Catalog Browser & Contract Tests`。
  基线 run `#41093657737` 已 completed（排除「基线在跑 ⇒ 假阻塞」这一误判路径），该判定成立。
- **失败归属：集成分支内容固有，不是合并动作引入** —— 合并结果里 `backend/services/platform` 的树
  与 `origin/integration/module-dev` **逐字节相同**。根因与修复方向见 backlog：
  [`docs/backlog/2026-11-03-platform-io-storage-root-blocks-catalog-browser-ci.md`](../backlog/2026-11-03-platform-io-storage-root-blocks-catalog-browser-ci.md)。
- **看板照发**：看板是观测面不是发布面，合并失败也刷；数据取 `origin/main`（脚本判定「远端已前进」）。

## 涉及文件

- `backend/services/platform/src/main/java/com/starcon/platform/LocalImportExportStorage.java`（本次新增，缺陷所在）
- `deploy/docker/docker-compose.catalog.yml`（platform 服务缺 `PLATFORM_IO_STORAGE_ROOT`）
- `deploy/docker/Dockerfile.backend`（`/app/data/*` 已 chown 10001，是修复落点）
- `.github/workflows/ci.yml`（第 140-147 行已把该失败模式写成注释，但断言漏了 platform）
- `tools/module-progress/ci-gate.mjs`（PR 头那份是修复版，结论可信）
- `tools/progress-site/`（看板构建与发布）

## 关键 trade-off / 讨论

- **本地全量 L2 没跑**：按任务书 §5.1，合并无冲突、无后端业务代码解冲突，不触发「必须本地跑全量 L2」的例外；
  且全量 L2 是发热高峰、CI 才是真闸。改跑定向验证 + 后端 `compile` 兜底。
- **typecheck 是 turbo 缓存命中**：没有直接重跑，而是核验了 `apps/web`、`packages/ui`、`packages/config-tailwind`
  三个包的 tree 哈希与集成头**逐字节相同**，据此判定缓存命中对本合并结果有效。
- **Prettier 判据看差集**：本次触及的 240 个 `.md/.ts/.json/.yaml` 文件**全部通过**，
  即零新增格式债（仓库级 621 个格式债是存量，未触碰）。
- **闸工具版本选择**：必须用 PR 头那份（旧版会把「PR 侧作业挂在构建期」误判成「无新增失败」），
  执行前先 `grep -c unreachedJobs` 确认为 6 > 0 才采信其结论。

## 后续待办

- [ ] **修 platform 存储根目录**（阻塞项）：compose 补 `PLATFORM_IO_STORAGE_ROOT=/app/data/platform-io-store` + Dockerfile 建目录同属主 + CI 补 platform 可写性断言 ⇒ 落到 `integration/module-dev`。
- [ ] 下轮（18:30）跟进 PR #72：集成分支带修复后更新同步分支、重跑 CI，闸工具给「可以合并」再合。
- [ ] 集成头本地 L2 覆盖不到 compose 全栈启动 ⇒ platform 这类「容器内起不来」的缺陷需在流水线侧加一道冒烟。

## 关联

- ADR：[ADR-0004](../decisions/0004-java-spring-boot-microservices.md)（Java 微服务栈）
- 台账：`docs/product/capability-blueprint/module-progress.md`
- 看板：https://338k302h0q2ii.space.mcode.cn
- PR：https://github.com/sugar-hit/starcon/pull/72
