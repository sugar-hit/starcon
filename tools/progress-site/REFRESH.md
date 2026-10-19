# starcon 交付进度看板 · 每日自动刷新协议

> **本文件是 cron 任务的 prompt 来源。协议只存这一份，且在版本控制里。**
> 改策略改文件，不要改 cron prompt 字段。

你每次运行是**全新的独立会话**，不继承上次上下文。
**所有判断必须从仓库现状重新取证，不能凭记忆。** 本文件里写死的参数是**已确认的固定值**，可以直接用；
但进度数字、模块状态一律以当次构建产物为准，不要引用本文件里的任何历史数字。

---

## 0. 固定参数

| 项                 | 值                                                            |
| ------------------ | ------------------------------------------------------------- |
| 仓库路径           | `/Users/guan/Projects/starcon`                                |
| 站点目录           | `tools/progress-site`                                         |
| 部署产物 `path`    | `tools/progress-site/dist`（根目录须有普通文件 `index.html`） |
| 源码 `source_path` | `tools/progress-site`（在仓库内，允许与 `path` 同级）         |
| 站点名             | `starcon 交付进度看板`                                        |
| **node_id**        | `454096010989704`                                             |
| 公开 URL           | `https://338k302h0q2ii.space.mcode.cn`                        |
| 运行时长           | `Asia/Shanghai`                                               |
| 执行时刻           | 每天 08:00                                                    |

> ⚠️ **`node_id` 是既定值，绝不能改，也不能省略。** 省略 `node_id` 会新建站点，
> 每天堆一个云盘节点并产生一个新 URL，已分发的链接会停留在旧数据上。
> 用户已明确选择「原地更新：复用现有 node_id，链接不变」。

---

## 1. 你的职责

每天 08:00 把看板刷新到仓库的当前状态，然后汇报。

**你不是调度者，也不要派发子任务。** 这个任务只有三步，没有并行价值。

---

## 2. 执行步骤

### 第 1 步：跑确定性脚本

```bash
cd /Users/guan/Projects/starcon && bash tools/progress-site/refresh.sh
```

脚本做三件事：同步远端 → 构建 `dist/data.json` → 数据自检。

| 退出码 | 含义          | 你要做的                                              |
| ------ | ------------- | ----------------------------------------------------- |
| 0      | `READY`       | 继续第 2 步发布                                       |
| 1      | 构建/自检失败 | **立刻停止。不要发布。** 旧站点保持不动，汇报失败原因 |
| 2      | 环境不满足    | **立刻停止。** 汇报缺少什么                           |

成功时脚本最后会输出两行机器可读的状态，**汇报里必须带上**：

```
PULL_STATUS=ok|failed
SOURCE_COMMIT=<短 commit>
```

`PULL_STATUS=failed` 表示没拉到远端最新，台账可能过期——照实说，别当成"今天没进展"。

**自检失败是最需要重视的一种。** 它意味着台账的「总览声明值」和「逐行模块状态」
对不上——仓库文档自己矛盾了。这时发布出去，站点上的完成率会和 `module-progress.md`
顶部的数字对不上，看的人会以为看板算错了。**不发布，让它保持上一次正确的状态，
并把矛盾点原样报出来。**

> **关于网络与同步**：这台机器访问 GitHub 必须走本地代理 `127.0.0.1:7897`，直连不通。
> 代理已写进 `.git/config` 的 `http.proxy`（机器本地配置，不进版本库）。
> 脚本对拉取加了 30 秒硬超时，代理不通时会快速失败并继续用本地工作树，
> 不会把每天 08:00 的刷新挂死。
>
> 拉取用的是 `git pull --rebase --autostash` 而不是 `--ff-only`：站点提交只在本地
> `main` 上（未 push），远端一旦前进就会分叉 —— 实测 `--ff-only` 在这种状态下直接报
> `Diverging branches can't be fast-forwarded`，拉取永远失败，站点会**静默停止更新**。
> rebase 把本地提交重放到远端之上；失败时脚本会 `git rebase --abort` 清掉中间态，
> 避免明天的拉取跟着一起失败。

### 第 2 步：原地发布

第 1 步返回 `READY` 后，用 `website_deploy` 工具发布：

```
path         = tools/progress-site/dist
source_path  = tools/progress-site
project_name = starcon 交付进度看板
node_id      = 454096010989704
```

**必须带 `node_id`。** 带了才是原地更新（URL 不变、内容整体替换）；不带就是新建站点。

> **授权说明（常驻）**：用户已授权每天 08:00 自动原地更新本看板。
> 这是既定的例行发布，**不需要每次再向用户确认**——确认会造成无人值守时每晚卡住。
> 你要确认的只有一点：`node_id` 是 `454096010989704`，目标是同一个站点。

发布后用 `web_fetch` 或 `curl` 校验一次线上可达性：

```bash
curl -s -o /dev/null -w "index:%{http_code}\n" https://338k302h0q2ii.space.mcode.cn/
curl -s https://338k302h0q2ii.space.mcode.cn/data.json \
  | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const d=JSON.parse(s);console.log('线上：',d.summary.delivered+'/'+d.summary.total,'=',d.summary.rate+'%')})"
```

线上返回的 `delivered/total/rate` 必须与本次构建产物一致。不一致就如实报出来。

### 第 3 步：汇报

用中文，简洁，包含：

1. **本次结果**：成功发布 / 放弃发布（原因）
2. **进度快照**：已交付 / 总数、完成率、较上次的变化
3. **本次变化**：如果台账有更新，说清楚哪些系统或模块的进度变了
4. **数据新鲜度**：`SOURCE_COMMIT`（短 commit）与 `PULL_STATUS`；
   `failed` 要明说「本次未能同步远端，数据可能滞后」
5. **异常**：pull 失败、自检失败、线上校验不一致——有就点名，没有就写「无」

---

## 3. 边界

- **不改台账**。`module-progress.md` 的「状态」「证据」两列是人工维护的，
  你只读不写。看板是台账的投影，不是台账的另一个来源。
- **不修构建脚本**。`build.mjs` 解析失败时，你要做的是**放弃发布并报告**，
  不是当场改脚本。改脚本属于开发工作，会在报告里点名。
- **不新建站点、不换 `node_id`**、不改 `project_name`。
- **不碰 `tools/progress-site/` 之外的文件**。
- 台账的「状态」「证据」两列若被改成了新值，那是别的会话的工作，你照实反映即可。

---

## 4. 关联

- 站点源码与构建脚本：`tools/progress-site/`
- 数据来源台账：`docs/product/capability-blueprint/module-progress.md`
- 功能蓝图：`docs/product/capability-blueprint/README.md` 及 18 卷 `0N-*.md`
- 视觉规范：`docs/design/visual-spec.md`
- 首次上线会话日志：`docs/sessions/2026-10/2026-10-19-progress-dashboard-site.md`
