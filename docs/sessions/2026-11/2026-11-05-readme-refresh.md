# 2026-11-05: 主页 README 全面刷新 —— 事实对齐、换 logo、补更新时间、中英文重写

## 摘要

仓库主页 README 停留在 **2026-10-15** 的快照，已经和 main 脱节：后端服务从 6 个
增加到 9 个（新增 `finance` / `reporting` / `platform`），交付台账从 152/465（32.7%）
推进到 **465/465（100.0%）**，而 README 仍写着「465 个规划模块，152 个已交付」。
本轮分两批做完，经 PR #75 合入：

1. **事实对齐**：进度表、端口表、架构图、仓库结构、演示路线全部刷到当前状态；
2. **视觉与文案**：换 logo（app icon + 深浅色适配）、补 Spring Boot / PostgreSQL
   两个 badge、文末加更新时间、中英文两版全文重写。

全程只做文档：没有运行时代码、依赖、迁移或数据库改动。

## 决策 / 结论

1. **进度表按 18 个蓝图卷分域列出，标注卷号与模块数**，而不是继续用 6 行的粗粒度
   领域表 —— 卷号是台账与蓝图之间的稳定键，读者可以直接跳到对应卷核对。
2. **「已交付」的口径写进 README 正文**：迁移 + 接口 + 页面 + 测试四件齐全才算，
   并且明确声明这个百分比衡量的是**蓝图覆盖度**，**不是生产成熟度，也不是平台实网
   连通性**。100% 的数字单看容易被读成「产品已完成」，必须同口径一起出现。
3. **平台适配器的现状照实写**：渠道服务里 Amazon / Shopify / eBay / TikTok Shop /
   Temu / Lazada / Shopee / Walmart 八个平台的 **OAuth 授权适配器**已实现，
   但订单接入、刊登同步、广告数据等**实网业务接口尚未验收**。README 保留了这句
   限制，没有把 OAuth 适配器写成「已接入平台」。
4. **架构图手工维护并与 compose 端口矩阵对齐**：9 个服务一行排开，编号
   8081–8089 与 `deploy/docker/docker-compose.catalog.yml` 一一对应。
5. **占位物一律标注**：apps/admin、apps/api、apps/worker、apps/scheduler、
   apps/notification-gateway、workers/*、services/*、infra/* 在仓库结构里明确写成
   脚手架；packages 里只有 `ui`（设计系统）与 `api-contracts`（OpenAPI 生成物）
   真正有实现，其余仍是占位包 —— 不写成「15 个共享库」这种会误导的措辞。
6. **logo 换成应用图标，并用 `<picture>` 适配深浅色**：原来的裸 mark（`logo-mark-brand.svg`）
   在 README 顶部像一枚孤零零的贴纸；换成 `logo-app-icon-dark.svg`（蓝底白标的圆角
   app icon，96×96），并用 `<picture>` 让 GitHub 深色主题下切到浅色版
   `logo-app-icon-light.svg`。这是品牌规范 `docs/design/brand-source-b1-refined.png`
   里明确列出的「深色 App Icon / 浅色 App Icon」用法，**没有另造图形**。
7. **badge 补到 6 枚**：新增 `spring-boot.svg`、`postgresql.svg`，与既有的
   java / next / typescript / license badge 同规格（24px 高、圆角 5、左灰右彩）。
8. **更新时间写在文末**：`<sub>Last reviewed against main on 2026-11-05 · 提交历史 ·
   贡献者 · Issues</sub>`。README 是静态文件，没有自动时间戳机制，所以**写清楚
   「对齐哪个分支、哪一天」比只写一个日期更重要**。
9. **推送路径走分支 + PR**：AGENTS.md 规定分支命名 `<type>/<ticket-id>-<short-desc>`、
   禁止直接 push main。用户在「直接推 main」与「推分支 + 开 PR」之间选了后者，
   分支为 `docs/readme-refresh`。

## 涉及文件

- `README.md` — 英文主页：实现进度、端口表、亮点、演示路线、仓库结构、开发命令、
  logo、badge、更新时间
- `README.zh-CN.md` — 中文主页，与英文版逐段对齐（中文版按中文的语感重写，
  不是照着英文逐句翻译）
- `docs/design/readme/architecture.svg` — 服务架构图，6 个服务补到 9 个
- `docs/design/readme/spring-boot.svg`、`docs/design/readme/postgresql.svg` — 新 badge
- `docs/sessions/2026-11/2026-11-05-readme-refresh.md` — 本记录

## 关键 trade-off / 讨论

**要不要把「465/465 = 100%」直接写进 README？** 写了。

- 顾虑：首页读者看到「100%」会以为产品已经完成、甚至已上线。
- 处置：数字照写（台账就是这样，不写才是失真），但同段落给出四件齐全的判定标准，
  并显式声明「不是生产成熟度、不是实网连通性」，下一段再补一句「页面、配置记录、
  任务接口的存在不等于外部平台已完成真实连接」。**诚实的前提 + 明确的限定词**，
  比回避数字更安全。

**logo 用 app icon 还是用 lockup（带 STARCON 字标）？** 选 app icon。

- lockup（`logo-lockup.svg`，232×64）自带「STARCON / Commerce OS」字标，但 README
  下方已经有 `# starcon` 一级标题，两者叠在一起会重复；而且 GitHub 上 232×64 的
  横版图在窄屏上偏小。
- app icon 是正方形、深浅两版都在品牌规范里，96×96 居中最稳，也更像「这个产品」
  而不是「一张贴纸」。

**README 的中英文怎么保持一致？** 保持**结构一致、文案各自地道**。

- 两版的小节顺序、表格列、锚点一一对应，读者对照着看不迷路；
- 但中文版重写句子（例：英文 "Cross-platform work stops being reconciliation and
  starts being routine." → 中文「跨平台协作于是从『对账』变回『日常』」），
  不做逐句直译，避免中英夹杂的翻译腔。

**根 README 受 prettier 管辖，`docs/` 不受。** 根目录的 `README.md` /
`README.zh-CN.md` 在 prettier 的管辖范围内（`.prettierignore` 只排除了 `docs/`），
所以表格会被重排成对齐写法；`docs/` 下的表格走仓库的紧凑写法约定（`| --- | --- |`）。
本轮两种风格各自保持原样，没有互相污染。

**共享工作区被并发进程切分支怎么办？** 另开 worktree，不抢主目录。

- 本轮第二批开工时，主工作区被另一个 agent（Helm / K8s 部署那条线）切回了 `main`，
  我的分支改动因此从工作区「消失」——内容没丢（已在分支与远端），但工作区状态
  被别人接管了。
- 处置：按仓库既有约定（`/Users/guan/Projects/starcon-worktrees/<name>`）另开
  `starcon-worktrees/readme` 独立 worktree 干活，主工作区一行不碰；改完把主目录里
  自己那两份 README 还原，避免被别人顺手提交进去。
- **教训**：同一个仓库里有多条 agent 线并行时，改分支前先看 `git worktree list`，
  不要在共享主目录里切来切去。

## 后续待办

- **首页 README 里的「实网验收」是一句承诺，需要兑现**：8 个平台目前只有 OAuth
  授权适配器，订单接入 / 刊登同步 / 广告数据三条链路都还没有实网验收记录。这三项
  应进 backlog，并各自补会话验收记录，README 的 Next steps 列才有依据。
- **`docs/architecture/overview.md` 的服务清单也已过期**：只列了 6 个服务，缺 crm、
  reporting、platform。首页 README 已顺手链到它，但那份文档需要单独一轮更新。
- **`docs/operations/catalog-local-development.md` 同样停留在 2026-09-28**：服务表只
  写了 identity 与 catalog，实际 compose 已拉起 9 个服务。
- **模块台账的 100% 需要定期回看**：若后续把「实网验收」也纳入「已交付」判定口径，
  台账数字会回落，届时 README 与台账要同步改，否则首页又会与仓库脱节。

## 关联

- PR：<https://github.com/sugar-hit/starcon/pull/75>
- 交付台账：`docs/product/capability-blueprint/module-progress.md`
- 功能蓝图：`docs/product/capability-blueprint/README.md`
- 品牌规范：`docs/design/brand-source-b1-refined.png` · `docs/design/visual-spec.md`
- ADR-0004（服务边界）：`docs/decisions/0004-java-spring-boot-microservices.md`
- ADR-0005（租户层级与页面 RBAC）：`docs/decisions/0005-tenant-hierarchy-and-page-rbac.md`
- 相关会话：`docs/sessions/2026-11/2026-11-04-nanosecond-microsecond-boundary.md`（CI 矩阵拆分）
