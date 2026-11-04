# 2026-11-05: 全仓扫描硬编码占位数据 —— 七个域首页的假页面与四处会落库的假数据

## 摘要

用户要求扫一遍全仓逻辑，查是否存在硬编码（占位数据 / 假数据 / 假逻辑），发现即修，
然后单独开分支跟进、合进 main，并保证 CI 全绿。

本轮分三路并行审计（前端 `apps/web`、后端 `backend/services`、共享包与部署件），
**结论是后端与共享包基本干净，真正的系统性假数据集中在前端**：

1. **七个域首页挂着「域占位页」** —— `pricing` / `crm` / `supply` / `purchase` /
   `logistics` / `warehouse` / `profit` 全部渲染 `DomainPlaceholder`：一张写死
   「骨架就位 · 功能待实施」+「汇率源与刷新策略：待实施」的静态计划表，
   **一条业务数据都没有**，而这些域下面真实交付的模块页面（物流 26、CRM 24、仓储 21、
   采购 20、定价 13、供应 7、利润 5）就躺在同一个目录里。
   本仓自己的判定标准（`tools/module-progress/sync.mjs`）把 `DomainPlaceholder`
   列为「未开发」的典型形态，而台账写的是 465/465 已交付 —— **占位页说的话全是假的**。
2. **两处手维护的交付状态漂移** —— `domain-spec.ts` 的 `pageStatus` 与每个 feature 的
   `status`，以及 `task-pages.ts` 里 30 条已实现任务的「占位字段表」
   （`fields: ['规则名','站点','币种',...]` 描述的是一张并不存在的表）。
3. **四处会写进真实数据库的假数据** —— 见「决策 3」。
4. **后端一处「0 冒充算不出来」** —— 违反该类自己声明的 `NO_FAKE_ZERO` 契约。
5. **部署件的占位值不会被拒绝** —— Helm 示例地址静默渲染、dev compose 弱口令 + 全网卡端口。

## 决策 / 结论

### 1. 域首页改成「真实域索引」，不是换一个占位组件

**做法**：新增 `apps/web/src/components/domain-hub/`，把域首页变成**真实模块目录**，
每一条都指向 `apps/web/src/app` 下真实存在的页面。

**关键 trade-off —— 清单从哪来？**
候选方案有三个：

| 方案 | 问题 |
| --- | --- |
| 手写清单 | 必然漂移；且 `fields` / `workflow` 那类描述只能靠编 —— **那就是新的假数据** |
| 从功能台账解析 | 台账 465 条里只有 56 条记了路由，其余路由字段是 `—`，解析不出完整清单 |
| **从真实页面生成** ✅ | 路由 = 文件系统事实；模块号与模块名 = 页面**自己写的文档注释**；分组名 = 蓝图原文。三样都不是编的 |

⇒ 定 `tools/codegen/build-domain-modules.mjs`：扫 `apps/web/src/app` 下所有 `page.tsx`，
从文档注释抽模块号与模块名，从蓝图功能树抽 L2 / L3 分组名，
生成 `apps/web/src/lib/domain-modules.generated.ts`（393 条 / 27 个域）。

**抽不到模块号的页面一律跳过**（46 个，多半是 `CatalogWorkspace kind="research"` 这类
薄壳页）—— 宁可少列，也不编一个名字出来。

**同步守卫**：`apps/web/src/lib/domain-modules.test.ts` 重新生成一遍与仓库里的文件
**逐字比对**，并逐条验「路由真的有 page.tsx」「模块名逐字等于页面文档注释里的那个」。
加页面忘了重新生成 ⇒ CI 立刻红。

**生成器自己跑 prettier**：因为 `pnpm format:check` 查所有文件、同步测试要求逐字相同，
两条叠在一起 ⇒ 生成器吐出来的必须已经是 prettier 之后的样子。

### 2. `/profit` 的模块不在 `/profit` 下面 —— 按路由前缀挑，不按目录挑

蓝图 13 卷的前端入口是 `/analytics`（经营总览已占），利润侧模块落在 `/analytics/profit`。
按目录名挑会一个都挑不到 ⇒ 域索引接受**路由前缀列表**，而不是域名。

### 3. 四处会落库的假数据，全部改成「用户给真实值」

| 位置 | 原来的假数据 | 后端怎么校验的 | 现在 |
| --- | --- | --- | --- |
| `platform-violations-workspace.tsx` 挂主体 | `subjectRef: 'MANUAL-SKU'` + `skuId: null` | `SKU` 必须给 `skuId` ⇒ **必然 422**，而前端 `.catch(() => undefined)` 把错吞了 ⇒ **界面假装成功，其实什么都没发生** | 提示框里由用户填真实 id；错误不再吞 |
| 同上 · 整改要求 | `'按平台通知补齐材料'` | 只校验非空 ⇒ **真的落库**，每张违规单长出同一句假线索 | 用户自己写「要补什么」 |
| 同上 · 整改凭证 | `'evidence.pdf'` + `'整改凭证'` | 引用/说明二选一即可 ⇒ **真的落库**，攒出一批指向不存在文件的凭证 | 用户自己填引用或说明 |
| 同上 · 扣款 | `penaltyAmount: 100` / `'USD'` | 扣款必须给金额与币种 ⇒ **真的落库**，一笔凭空扣款还跟着店铺走进 12 财务 | 用户填金额与币种 |

**取舍**：用 `window.prompt`（仓库惯例，128 处在用）而不是新造弹窗 ——
四处输入都是一两行文本，弹窗的成本大于收益。校验逻辑落在模型层的纯函数
（`buildSubjectInput` / `buildRemediationInput` / `buildEvidenceInput` / `buildDeductionInput`），
这样能单测。

### 4. 后端「0 冒充算不出来」

`ReportingStoreSiteService.cellValue` 的 Javadoc 写着「取不到 ⇒ null = 算不出来」，
实现却返回 `BigDecimal.ZERO`，而该类开头明令「**不用 0 冒充**」。
⇒ 改成返回 `null`，排序改用 `nullsLast`（算不出来的排**最后**，不与真卖 0 件并列）。
抽成包级可见的 `bySalesQuantityDesc()`，用**不需要 Testcontainers** 的纯单测钉住。

### 5. 部署件：占位值必须被拒绝，而不是静默带进生产

- Helm `starcon-backend`：`web.publicUrl` 为空或仍是 `starcon.example.com` ⇒ `fail`。
  留着占位值的话，渠道 OAuth 授权完成会把浏览器送进不存在的域名，**安装过程一声不响**。
- Helm `starcon-web`：`ingress.host` 仍是示例值 ⇒ `fail`（后端 chart 早就有这道守卫）。
- `docker-compose.dev.yml`：口令从 env 注入且**缺了直接失败**（`${VAR:?…}`），
  端口全部绑 `127.0.0.1`（此前是 `5432:5432` 这种全网卡写法）。
  `scripts/bootstrap.sh` 生成 `.env` 时把口令换成随机值 —— 仓库里那个
  `starcon/starcon` 弱口令从此只存在于 `.env.example` 的占位里。

**Helm 那两条去了另一个分支**：两个 chart 本身还在 PR #76
（`feat/helm-kubernetes-deployment`）里没合进 `main`，而守卫必须改的是那两个
chart 的文件 —— 所以守卫跟着 chart 走，提交到该分支（`1829cec9`），
本分支只留 dev compose 那一半。否则把本分支合进 `main` 会顺手把 PR #76 的内容
也带进去，变成「借道合入别人的 PR」。

### 6. 交付状态字段：**删掉**，不是改对

`domain-spec.ts` 的 `pageStatus` 与 feature 的 `status`、`task-pages.ts` 的
`fields` / `workflow` 都是**手维护的交付状态**，而它们已经漂移过一次。
⇒ 交付状态改成**从真实存在的东西推出来**（域索引的生成清单 + 功能台账），
手写的那份直接删掉，连同只服务于它的 `DomainStatusBanner` 组件一起删。

`TaskPage.fields` / `workflow` 改成可选：**只有「还没有真实页面」的条目才需要**
（它们是 `[...slug]` 兜底页的占位表头），有真实页面的条目写一串字段
描述的是一张并不存在的表。

## 文件清单

**新增**
- `tools/codegen/build-domain-modules.mjs` —— 域模块目录生成器
- `apps/web/src/lib/domain-modules.generated.ts` —— 生成物（393 条 / 27 域）
- `apps/web/src/lib/domain-modules.test.ts` —— 同步守卫 + 域首页非占位页断言
- `apps/web/src/components/domain-hub/domain-hub-workspace.tsx` —— 真实域索引
- `apps/web/src/components/platform-violations/platform-violations-workspace.test.ts` —— 源码守卫
- `backend/services/reporting/src/test/java/com/starcon/reporting/ReportingStoreSiteSortTest.java`

**改动**
- `apps/web/src/app/{pricing,crm,supply,purchase,logistics,warehouse,profit}/page.tsx` —— 七个域首页
- `packages/ui/src/lib/{domain-spec,task-pages}.ts` —— 删掉手维护的交付状态
- `packages/ui/src/lib/task-pages.test.ts`、`packages/ui/src/components/common/{task-workspace,feature-list,domain-placeholder}.tsx`
- `packages/ui/src/components/common/domain-status-banner.tsx` —— **删除**
- `apps/web/src/components/platform-violations/platform-violations-{model,workspace}.ts(x)` —— 四处假写入
- `apps/web/src/components/platform-order-sync/platform-order-sync-{model,workspace}.ts(x)` —— 模板身份与重放兜底
- `apps/web/src/components/inventory/labor-efficiency-workspace.tsx` —— 默认区间改用 `defaultRange()`
- `backend/services/reporting/.../ReportingStoreSiteService.java` —— 不再用 0 顶
- `deploy/docker/docker-compose.dev.yml`、`.env.example`、`scripts/bootstrap.sh`
- `infra/helm/starcon-backend/{values.yaml,templates/_helpers.tpl}`、`infra/helm/starcon-web/templates/ingress.yaml`
  —— **这条去了 PR #76 的分支**（见「决策 5」）

## 合并结果

| PR | 分支 | 内容 | 合入 main |
| --- | --- | --- | --- |
| [#77](https://github.com/sugar-hit/starcon/pull/77) | `fix/remove-hardcoded-placeholder-data` | 本轮全部修复（7 个 commit） | `76fa3ca8` |
| [#76](https://github.com/sugar-hit/starcon/pull/76) | `feat/helm-kubernetes-deployment` | Helm chart + 占位地址守卫 `1829cec9` | `19fbe465` |

两个 PR 的改动文件**零重叠**（`comm -12` 为空），互不借道；合并走 merge commit
（与仓库既有历史一致）。两个 PR 的 CI 各 16 个作业全绿（含 `Quality Gate`），
合入后 main 上那轮 run 同样全绿。

**合入顺序**：先 #77 再 #76。#77 合入后 main 前进，GitHub 对 #76 重跑了一轮 CI
（run `41879522080`），那轮把 #76 的 head 对着「已含 #77 的 main」验了一遍 ——
比单看各自分支更严格，绿了才合。

## 后续待办

- **域首页只列得出模块，进不去模块** —— 导航侧仍只有 31 个任务页，
  另有 400+ 真实页面在文件系统里却不在菜单。域索引解决了「域首页骗人」，
  但「侧栏看不到某个模块」这件事还在（属于导航信息架构问题，不是假数据问题）。
- **`apps/web/src/components/platform-order-sync` 的模板**仍是「真实写入」入口，
  已把身份标成 `示例` / `EXAMPLE-`、`skuId` 留空，但**还没有**上传式取证。
- **46 个抽不到模块号的页面**（薄壳页）不在域索引里。若要让它们也进去，
  得先把模块号写进这些页面的文档注释 —— 那是一次单独的批量改动。
- **`AccountMenu`（`packages/ui`）** 里的「体验用户」原型身份：只被 `/styleguide` 走到，
  UI 已自陈「交互原型 · 未接入真实账户」，暂按「诚实原型」处理，没动。
- **夹具写死日期是同一族问题，但本轮没碰** —— backlog 条目
  [`2026-10-2026-10-26-hardcoded-fixture-dates-self-failing`](../backlog/2026-10-2026-10-26-hardcoded-fixture-dates-self-failing.md)
  记的是**测试夹具**把「昨天」写成绝对日期、到点自己变红那一类。本轮只修了一处前端默认区间
  （`labor-efficiency-workspace.tsx` 改用模型层 `defaultRange()`），后端夹具那批仍是系统性待办，
  且按该条目的判定形状要求**逐条判、不许批量替换**。
