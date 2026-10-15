# 2026-10-15: MiniMax 开发流水线审计与优化

## 摘要

审计 `sugar-hit/starcon` 的 main `d8b15e936d46139eedbe1e09966b4a89fc8dbca1`、
近期提交、全部 26 次 Actions 记录、现有调度代码与任务书。改进集中在减少重复上下文、
防止重复开发/误报完成/误删成果，以及保持原检查覆盖的增量验证与缓存。
本次不开发业务功能，不更改生产、权限、secrets 或本地定时任务。

## 证据与结论

- [最新主分支 CI](https://github.com/sugar-hit/starcon/actions/runs/38611746534) 成功。
  Java 检查约 34 分 57 秒；前端单测 job 34 秒；build 3 分 47 秒；浏览器/契约 3 分 30 秒。
  后端是当前可观测的主要等待来源，不能把全部慢归咎于模型。
- 26 次历史运行：2 success、19 failure、5 cancelled。失败包含基础配置问题，
  不能当成模型写错代码的比例；取消也不等于质量失败。
- PR #7 汇总 279 提交/827 文件；更早 PR #5 汇集 75 分支；PR #3/#6 曾涉及重复游标实现。
  近期批次仍有 proxy 求并、导航登记、时钟依赖修复，说明小批次与边界回归比增加并发更重要。
- 旧 L2 命令连续两次 `cd ..`，build 会跑到仓库父目录。两份任务书约 1,100 行，
  重复参数、互相冲突，且有未经保证的精确吞吐/二分次数承诺；已收敛为单一协议和短入口。
- `pickModules` 没有把在途/待验收/冲突车道的 L3 纳入排他集合；`land` 比较短 base SHA 与完整 HEAD；
  状态解析失败会静默清空；gc 对 abandoned worktree 强删。这些是实际代码缺陷，均加入回归。
- [现有主分支规则](https://github.com/sugar-hit/starcon/rules/24724745) enforcement 为 disabled。
  传统 branch protection 读取被 GitHub App 拒绝（403），所以不能断言完全没有其他保护。
  本次未改设置：新增 Quality Gate 并不自动成为服务器 required check。

## 决策与涉及文件

- `tools/module-progress/PIPELINE.md`：小任务卡、代码查重、依赖/文件所有权、队列优先级、
  L0/L1 增量检查、同一集成快照 L2、失败反馈、Bugfix 先复现、预算/资源规则、PR 验收。
- `PIPELINE_CRON_PROMPT.md` 与根 `AGENTS.md`：统一入口，保留用户本地运行参数与暂停状态。
- `lane.mjs`：修复上述调度与回收问题；dry-run 不创建集成分支，参数校验、保留历史迁移号、
  frozen lockfile 安装、完整 base SHA。保留 `land` 仅待验收的语义，不声称它已运行测试。
- `lane-safety.itest.mjs`、`test-workflow.mjs` 与两个现有 fixture：真实临时 Git 仓库回归，
  7 组顺序运行，无模型 API、无数据库、无付费服务。
- `.github/workflows/ci.yml`：保留所有原检查名与覆盖，增加工具回归和 Quality Gate；
  格式先于 Java 数据库测试；build/E2E 等轻检查成功后再启动；超时、失败报告、7 天产物保留。
  默认只读权限，CodeQL job 保留已有权限。未加 cron、额外矩阵或外部 secrets。
- `turbo.json`：移除不完整的手写 inputs，恢复完整 package 默认输入并纳入共享配置；
  配合 CI 分 job 的 `.turbo` 缓存，key 含平台/架构/Node 配置/锁文件/Turbo 配置与 SHA。
- `package.json`：新增 `test:workflow`；`.github/PULL_REQUEST_TEMPLATE.md`：验收、回归、真实结果。

## 已验证与限制

本地：lint、typecheck、format:check、2,801 个 Vitest 用例、17 个 codegen 用例、
7 组 workflow 回归、`sync.mjs --check`、`git diff --check` 通过。
真实修改 Next 配置与共享 Tailwind preset 后，Turbo dry-run 的 web build hash 均变化，
确认这些先前易遗漏的输入可以使缓存失效（探针已恢复）。

本机构建最初因 sandbox 无法访问 Google Fonts 失败，允许网络后完整生产构建通过（133 页，退出码 0）。
本机默认 JDK 25 且 sandbox 无法访问 Docker，因此未在本机重复整套 Java/浏览器数据库测试；
PR 使用既有 JDK 21、PostgreSQL 与完整 CI 验证。GitHub 最终检查状态以 PR 页面为准。
不据此承诺 MiniMax 用量一定用尽、速度提升倍数或全仓业务代码已审计无 bug。

## 关键取舍与后续待办

不立即把 35 分钟 Java 检查拆成多个 runner：先保留租户/数据库回归覆盖与现有成本边界。
本地 workers 用单服务测试降低重复，PR 仍跑全套。后续可按实测耗时评估少量分片。

- 维护者审阅合入 PR 后，现有 agent 才会读到新版协议；本次没有自动重启任何开发任务。
- 维护者可将 Quality Gate 设为 required check；本 PR 不更改保护设置。
- 正在运行的旧 worker 需安全收口后再迁移协议；缺本地配置时不默认派发。
- 共享文件冲突、调度锁过期/所有者校验、完整测试证据自动绑定仍有进一步工程空间；
  本次通过保守协议与真实回归降低风险，不把 prompt 当成强制执行器。
