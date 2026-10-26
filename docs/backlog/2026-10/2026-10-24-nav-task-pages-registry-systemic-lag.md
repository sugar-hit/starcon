# 导航注册表（`packages/ui/src/lib/task-pages.ts`）系统性滞后：78 登记 / 374 真实页面（2026-10-24 batch-86 取证）

**优先级：P2**（不影响构建、不影响测试、不阻塞交付；影响「页面能不能从菜单点进去」）

## 现象

batch-86 派发的 4 条车道里，有 3 条**主动拒绝**改 `packages/ui/src/lib/task-pages.ts`
（共享的多车道真值源），理由都是「留给调度者 / 留给 nav lane」：

| 车道 | 声明 |
| --- | --- |
| 1.1.3.2 资金与回款风险 | 刻意未登记 `workbench-card-registry.ts`（1.4.1.1 的系统事实文件，其单测把卡片清单写死） |
| 1.3.1.1 通知列表 | 刻意未登记 `packages/ui` 导航配置；「`/notifications` 是多车道真值源」 |
| 12.3.2.1 对账任务 | 刻意未登记 `packages/ui/src/lib/task-pages.ts`（「四车道共享文件，留给调度者」） |

## 取证：这不是 batch-86 的债，是**系统性滞后**

```
TASK_PAGES 已登记条目          :  78
apps/web/src/app 下真实 page.tsx: 374
真实存在但未登记的路由          : 323   （覆盖率 13.6%）
```

⇒ 绝大多数**已交付**的模块页面（`/compliance/*`、`/dashboard/*`、`/ads/*` ……）本来就不在导航里。
本批 4 个页面只是**跟随了既有模式**，不是本批引入的缺口。

## 为什么「登记一下」不是机械编辑

`packages/ui/src/lib/nav-config.ts` 的导航组是这么拼的：

```ts
// nav-config.ts:140-141
IMPLEMENTED_TASK_PAGES.filter(
  (page) => page.domain === item.href.slice(1) && !isAccountDomain(page.domain),
)
```

⇒ **`TaskPage.domain` 必须等于某个 `SYSTEMS[].items[].href`**（去掉前导斜杠），
页面才会挂在那个系统分组下；`resolveFeatureFromPath` 也是按 `/${task.domain}` 反查的。

集成头现有的 system item href 只有这些：

```
aftersales  analytics  channels  copilot  crm  finance  inventory
listings    logistics  orders     pricing  products  profit  purchase
supply      warehouse
```

对照本批 4 个页面：

| 页面 | 需要 domain | 是否存在现成 system item | 可否直接登记 |
| --- | --- | --- | --- |
| `/finance/reconciliation-tasks`（12.3.2.1） | `finance` | ✅ 有 | **可以**（但见下面的时序陷阱） |
| `/compliance/filing-calendar`（16.3.1.2） | `compliance` | ❌ 没有 | **需要新增导航结构** |
| `/dashboard/fund-payment-risk`（1.1.3.2） | `dashboard` | ❌ 没有 | **需要新增导航结构** |
| `/notifications`（1.3.1.1） | `notifications` | ❌ 没有 | **需要新增导航结构** |

⇒ 4 个里有 **3 个需要先决定「合规 / 工作台风险 / 消息」要不要成为一级导航分组** ——
这是**导航信息架构的设计决策**，不是补几条数据能解决的，归口给导航 owner。

## 🔴 时序陷阱：登记 `implemented` 标记前，页面必须真的已并入集成头

`packages/ui/src/lib/nav-routes.test.ts` 的两条断言：

```ts
// ① 已登记的条目，implemented 标记必须与真实文件系统一致
const wrong = TASK_PAGES.filter((page) => page.implemented !== hasPage(page.href));
// ② 有真实页面的条目不能被标成未实现
const missing = TASK_PAGES.filter((page) => hasPage(page.href) && !page.implemented);
```

`hasPage(href)` 查的是 **仓库根** `apps/web/src/app/<href>/page.tsx` 是否存在。

⇒ **在模块并入集成头之前登记它，会直接把 L2 弄红**：

- 登记 `implemented: true` ⇒ 页面前端还没并进来 ⇒ 断言①失败（「标记=true 实际=false」）
- 登记 `implemented: false` ⇒ 等页面并进来之后 ⇒ 断言②失败

**本批的具体时序**：
- 1.1.3.2 / 1.3.1.1 / 16.3.1.2 已由 18:00 tick 并入集成分支 ⇒ 页面已存在，
  但三者都需要**新导航结构**才能登记 ⇒ 留给 nav owner
- 12.3.2.1 的 `/finance/reconciliation-tasks` 页面**尚未并入**（batch-86 结束时它是 `ready`）
  ⇒ **最早可登记时点 = 它被并入集成头之后的那一轮**

## 后续待办

- [ ] **导航 owner**：决定「合规 / 工作台 / 消息」是否成为一级导航分组，
      再把 323 个未登记页面分批登记（现在 78/374，覆盖率 13.6%）。
      登记前必须确认目标页面的 `page.tsx` **已经并入集成分支**，否则 L2 必红。
- [ ] **`lane.mjs` / 任务书模板**：把「页面交付后需登记 TASK_PAGES」写成**调度者的收尾检查项**，
      并在任务书里给出**该模块应挂到哪个既有 domain**（不存在则明确写「需新增导航分组」）。
      现在是让 worker 猜，猜错的后果是 3 个页面都登记不了。
- [ ] 顺带核对 `workbench-card-registry.ts`（1.4.1.1 的系统事实文件）：它的卡片清单被
      `workbench-layout-model.test.ts` 写死，新模块加卡片要连带改别人的测试 ——
      与 `task-pages.ts` 同形问题，建议同一个 owner 一并处理。

## 🔴 附带的协议缺口：`docs/backlog/` 里的 TODO **调度器读不到**

取证：`tools/module-progress/PIPELINE.md` 与 `PIPELINE_CRON_PROMPT.md` **全文 grep「backlog」零命中**
—— 两个协议文件都没有要求调度者在开工时读 `docs/backlog/`。
而 `AGENTS.md:142` 把 `docs/backlog/` 定为「跨模块 TODO」的归档地。

⇒ **后果**：`docs/backlog/` 对**人**是可检索的（仓库目录速记里就写着「我想看跨模块 TODO → docs/backlog/」），
但对**自动化调度器**是**死信**：本文件写得再细，下一轮 tick 也不会读它。

⇒ 这次的实际代价就发生在本批：3 条车道把共享文件登记这件事「留给调度者」，
而调度者如果只看协议文件就**永远不会知道该去登记**（本轮是靠读车道的交付报告才发现的）。

**建议（需要人决策，属协议改动）**：在 `PIPELINE.md` 的调度者职责清单里加一行 ——

> 开工第一步除了 `sync.mjs`，再读一遍 `docs/backlog/` 里与本批模块相关的条目；
> backlog 里的 TODO 若与本轮车道相关，本轮闭环。

（协议文件是版本控制的真值源，改策略应改那里；但**不要在另一个 tick 正跑 L2 的 worktree 里改** ——
tracked 文件变更会让在途 tick 的 `merge-ready` 因 `trackedDirty` 跳过车道回收。）
