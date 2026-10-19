# 2026-10-19: 交付进度看板网站

## 摘要

为 starcon 建了一个可公开访问的**交付进度看板**网站，把功能蓝图的四级目录（18 个系统 / 465 个 L4 模块）按模块粒度呈现交付状态、总完成率、每个模块的进展与「这个模块是干啥的」。

关键约束：**看板数据不手抄**。`tools/progress-site/build.mjs` 从仓库内的事实来源解析生成 `data.json`：

| 来源                                                   | 取什么                                                                       |
| ------------------------------------------------------ | ---------------------------------------------------------------------------- |
| `docs/product/capability-blueprint/module-progress.md` | 465 个模块的 ID / 名称 / 路由 / 阶段 / 状态 / 证据（台账）                   |
| `docs/product/capability-blueprint/0N-*.md`            | 每个 L4 模块的 职责 / 输入 / 输出 / 关系；每个系统的定位、边界、二级功能职责 |
| `docs/product/capability-blueprint/README.md`          | 18 个系统的层级归类、一句话职责、后端主责服务                                |
| `backend/` `apps/`                                     | 迁移数、测试类数、页面工作区数（现场统计，不引用任何自报数字）               |

构建结果与台账逐项吻合：465 模块 / 190 已交付 / 0 进行中 / 275 未开发 / 40.9%；分阶段（D1–D2 80、D3 34、D4 48、D5 14、D6 5、D7 9）与分系统（01:0/23、02:20/25、03:40/40、04:2/26、05:0/19、06:4/26、07:26/31、08:21/22、09:24/27、10:18/27、11:8/22、12:0/32、13:0/23、14:1/22、15:1/24、16:0/23、17:25/29、18:0/24）完全一致。465 个模块**全部**有职责描述，190 个已交付模块**全部**有交付证据，无空缺。

## 决策 / 结论

1. **看板是台账的投影，不是新台账**。网站不产生任何进度数字，只把已有台账换一种可视化方式呈现。台账改了，重跑 `node tools/progress-site/build.mjs` 即可，网站永远和仓库一致。
2. **「已交付」的判定口径原样搬到页面上**（迁移 / API / 页面 / 测试四件齐全，缺一不可），并把「典型未开发形态」也列出来（DomainPlaceholder、硬编码数据源、TODO + UnsupportedOperationException、无 RLS 迁移、前后端单边）。让看的人能自己判断这个 40.9% 值不值得信。
3. **不引入构建依赖**。纯 HTML + CSS + 原生 JS，零 npm 依赖，产物是可直接托管的静态目录。仓库已装 Playwright 时用 `tools/progress-site/verify.mjs` 做 1440px / 390px 双视口截图回归。
4. **站点内含跨期说明**。「阶段」不是卷归属而是依赖就绪顺序，这段解释只存在于台账的「跨期说明」小节里，不搬过来看的人会误读进度条，直接收进页面。
5. **每日 08:00 自动原地更新**（用户选定）。用运行时的 cron 能力，协议落在 `tools/progress-site/REFRESH.md`（与 `PIPELINE_CRON_PROMPT.md` 同样的「协议存文件、cron prompt 只引用」约定）。cron id `8f4761bb-c483-4695-b95f-e0c8bdc92444`，`0 8 * * *` / `Asia/Shanghai`，每次跑开一个全新会话。
6. **发布必须带 `node_id=454096010989704`**。不带就是新建站点——每天堆一个云盘节点、产生一个新 URL、已分发的链接停在旧数据上。协议里把这条标成硬约束。
7. **发布前有一道数据自检闸**（`check.mjs`）。它不比对「解析没报错」，而是比对「构建产物 vs 台账顶部『总览』的声明值」。台账自身漂了（声明值与逐行状态对不上）就**放弃发布**，让站点保持上一次正确状态 —— 否则站点上的完成率会和 `module-progress.md` 顶部的数字互相矛盾，看的人会以为看板算错了。
8. **cron prompt 里写死了「例行发布不再逐次确认」**。无人值守时每次问一次确认，任务会每晚卡住。用户的授权是常驻的。

## 涉及文件

- `tools/progress-site/build.mjs` —— 数据构建脚本（解析台账 + 蓝图 + 统计工程现状 → `dist/data.json`）
- `tools/progress-site/check.mjs` —— 数据自检（产物 vs 台账声明值，不一致则拒绝发布）
- `tools/progress-site/refresh.sh` —— 每日刷新确定性部分（同步 → 构建 → 自检；含 git 非交互与 30s 硬超时）
- `tools/progress-site/REFRESH.md` —— 每日刷新的完整协议（cron prompt 的唯一来源）
- `tools/progress-site/src/{index.html,styles.css,app.js}` —— 站点源码
- `tools/progress-site/verify.mjs` —— Playwright 双视口截图与控制台报错回归
- `tools/progress-site/dist/` —— 构建产物（部署用，gitignored）
- `.gitignore` —— 新增 `tools/progress-site/.shots/`

## 关键 trade-off / 讨论

- **自建解析 vs 手工整理 JSON**：手抄 465 行必然漂移。解析脚本的代价是蓝图文件格式变动时脚本要跟着改，但漂移风险从「每次都错」降到「一次构建就报错」—— 解析失败会直接抛异常，不会静默产出错误数字。
- **1.3 MB 的 data.json**：证据列本身极长（单条可达数千字），压掉它就等于砍掉「每个模块的进展」这个核心诉求。保持未压缩直接托管，静态托管默认开 gzip，实际传输约 1/4。
- **品牌视觉照抄视觉规范**：主色 `#1D4ED8`、近黑 `#0A0A0A`、文案调性「清晰、克制、可核对」，取自 `docs/design/visual-spec.md`，不另创风格。
- **这台机器访问 GitHub 必须走本地代理 `127.0.0.1:7897`，直连不通。** 代理已写进 `.git/config` 的 `http.proxy`（机器本地配置，不进版本库）。若只依赖环境变量 `HTTPS_PROXY`，无人值守的 cron 会话未必继承得到 —— 实测 git pull 会挂在凭证/代理提示上（`GIT_TERMINAL_PROMPT=0` 之外再加 30 秒硬超时，两道保险）。这也是为什么「同步远端失败」只降级不中断：拉不到就用本地工作树构建，站点保持自洽，报告里点名数据可能滞后。
- **站点源码必须进版本库**：`lane.mjs` 有「主工作区不干净就拒绝切分支」的硬闸，未跟踪文件在切分支 / `git clean` 下会被弄丢。源码进仓库后随分支走，产物 `dist/` 仍由 `dist/` 规则本地化，符合仓库「源码进仓库，产物全部本地化」的原则。

## 后续待办

- 台账的「状态」「证据」两列仍是人工维护；若后续 `tools/module-progress/sync.mjs` 改为输出机器可读格式，应把 `build.mjs` 改成直接读 JSON，去掉 Markdown 解析。
- `apps/admin` 目前只有空脚手架（0 个页面），看板如实显示；admin 开工后数字会自动跟上。
- 每天 08:00 的自动刷新依赖本机代理 `127.0.0.1:7897` 可用。换机器或代理端口变更时，要同步改 `.git/config` 的 `http.proxy` 与 `REFRESH.md` 里的说明。
- 本次提交**只在本地 main，未 push**。要发布到远端需另开分支走 PR（仓库规定不直接 push main）。

## 关联

- `docs/product/capability-blueprint/README.md` —— 功能蓝图总纲（四级目录约定、18 系统地图）
- `docs/product/capability-blueprint/module-progress.md` —— 交付进度台账（唯一数据源）
- `tools/progress-site/REFRESH.md` —— 每日自动刷新协议（cron prompt 的唯一来源）
- `tools/module-progress/PIPELINE_CRON_PROMPT.md` —— 同类约定的既有先例
- `docs/design/visual-spec.md` —— 视觉规范（品牌色与调性来源）
- `docs/sessions/2026-10/2026-10-18-batch-20-aftersales-and-approval-records.md` —— 最近一次功能交付批次
