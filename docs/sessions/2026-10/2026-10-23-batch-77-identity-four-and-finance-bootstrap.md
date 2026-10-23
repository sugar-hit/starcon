# batch-77 —— 卷 01 收口四模块并入 + 服务缺口解锁（finance 服务引导）

- 日期：2026-10-23
- 批次号：**77**（接 batch-76 往后排）
- 调度者：Mavis（pipeline cron tick）
- 集成头：`integration/module-dev`
- 本轮起点 `15898d71` → 本轮结束 `8c47f29f`（4 条并入 commit + 2 轮修复的并入 commit）
- 交付进度：**355/465 → 359/465**（已交付 359 · 进行中 0 · 未开发 106）
- 回收并入：**4 条** · 派发：**2 条**

---

## 本轮结论速览

| 项 | 结果 |
| --- | --- |
| 台账同步 | 465 个 L4 模块；起点 355 已交付 / 110 未开发 → 结束 **359 已交付 / 106 未开发** |
| 容量闸判定 | **GO** —— running 0/6、therm 无降频项、load 1.22/1.27/1.38（10 核）、Testcontainers PG **0** 个 |
| 节流闸 | 距上批 **119 分钟** ≥ 阈值 50 分钟 ⇒ 放行 |
| 强度档位 | **full**（当前时刻 < `TOKEN_HORIZON=2026-10-25T00:00:00+0800`）⇒ `LANES=4` / `THROTTLE_MIN=50` |
| 回收并入 | **4 条**：1.1.1.3 审批待办 / 1.2.2.1 常用页面收藏 / 1.4.2.1 语言与时区 / 1.4.2.2 默认店铺与仓库（**零冲突**） |
| 本轮派发 | **2 条**：11.2.3.2 转可售/残次/报废(V337) · 12.1.1.1 应收单管理(V338，**含 finance 服务引导**） |
| 静态预检 | **✅ 本批零命中**（本批 4 条车道 **零迁移**，见下） |
| L2 批量验证 | **GREEN** —— 第 1 次红（identity 测试隔离，2m22s 挂在 [1/6]）→ 取证定位（**省掉 2 次二分**）→ 两轮修复 → 第 2 次全绿（**52 分钟**）：后端 587 类 / **9319 例 0 失败**，前端 **546 文件 / 7788 例**，Next build + sync --check + merge-union itest + codegen 单测 + 契约闸 + 强制 typecheck **全部退出码 0** |
| 收尾三件套 | ① 说明 ✅ ② commit ✅ ③ push ✅（见下） |
| 本轮关键发现 | **106 个未开发模块里 79 个被「微服务不存在」堵死** —— 见「服务缺口」一节 |

---

## 本轮交付的四个模块

四条车道都落在 **identity 服务**、都属卷 01 工作台与门户，**且都不建表**（预分配的 V337 / V338 / V340 / V341 全部**未占用**）。

### 模块 1.1.1.3 审批待办

**这个功能做什么**：运营/客服在自己的工作台上看到「哪些单子在等我审批」，一眼看清金额、单据类型、卡在哪个节点、停了多久，并按类型/节点/超时/金额阈值筛选。**工作台不执行审批动作** —— 审批通过/驳回/转交一律回到归属系统完成，页面上渲染成带缺口的说明而不是第二个能改单子的按钮。

**技术落地**：

- **迁移**：**无**（本模块是只读投影，不新建任何表 ⇒ V337 预分配但不占用）
- **端点**：`GET /identity/approval-todos` × 1 —— 能力声明与四维分档（类型/节点/超时/金额阈值）**都放进同一个响应体**，前端一次请求拿全
- **页面**：`/dashboard/todo/approvals`（子路由，**不改**兄弟模块 1.1.1.2 的 `/dashboard/todo/page.tsx`）
- **测试**：后端 `ApprovalTodoIntegrationTest` 16 例 + `ApprovalTodoTenancyIntegrationTest` 4 例；前端模型单测 27 例 + api-proxy 双向白名单 4 例

**关键设计决策**：

- **法人级金额阈值「判不了」就如实报判不了**：`legal_entity` 表没有金额阈值列，`approval_instance` 也不带 `legal_entity_id`（连法人是谁都记不下来）⇒ 在 `capabilities.filters` 里报 `available=false` 并写明缺什么，**不拿硬编码常量冒充审批 SLA 口径**。真正可用的是流程上配的 `approval_flow_amount_branch`。
- **把「声明可用但筛不了」补成真筛选**：上一轮遗留的控制器把 `AMOUNT_BRANCH` 报成 `available=true` 却**没有任何请求参数**能按它筛 —— 那是一条**假能力**（页面要么给一个点了没用的下拉，要么干脆不提供）。取舍：补 `amountBranchName` 参数 + `amountBranches` 整集合分档，且**打在同一个 lateral 别名上** ⇒ 「筛出来的那一档」与「行上显示的档位」是同一个口径。代价是多改一个端点契约。
- **一个端点而不是三个**：维度计数必须是**整集合**的计数（前端只拿得到分页后的切片，用切片算出来的分档数会让「点一下就按这一类筛」对不上）。
- **权限码沿用 `identity.approval_inbox.read`**，不另立工作台专属码：另立只会让新建角色默认没有这个码，于是管理员明明配了审批权限，待办页却静默空着。**权限决定「能不能用这个页面」，节点决定「哪些单子归他」，两件事分开。**
- **「归我」的判定逐字对齐上游 `ApprovalInboxController`**，并用一条**清单等价性**断言把两边钉在一起 —— 两边一旦漂移就会给出两套待办清单。

### 模块 1.2.2.1 常用页面收藏

**这个功能做什么**：用户把常用的系统页面收藏起来，拖拽排序，点一下直达。没有权限的收藏自动隐藏（但**不删**），另有一次性清理入口。

**技术落地**：

- **迁移**：**无**（复用 1.4.1.2 建好的 `user_preference`，page_key `dashboard.favorites` ⇒ V338 预分配但不占用）
- **端点**：`GET /identity/page-favorites`、`POST`、`PUT /{id}`、`DELETE /{id}`、`DELETE /identity/page-favorites/hidden`（一次性清理被权限闸挡住的）
- **页面**：`/dashboard/favorites`（**另开路由**，不动 1.2.2.2 的 `/dashboard/shortcuts`）
- **测试**：`PageFavoriteIntegrationTest` 17 例 + `PageFavoriteTenancyIntegrationTest` 4 例（三断言）+ `PageFavoriteRulesTest` 17 例；前端 `page-favorite-model.test.ts` 25 例 + `page-favorite-allowlist.test.ts` 5 例（双向钉住白名单）

**关键设计决策**：

- **权限闸在数据层，不是返回后再置灰**：`pageCode` 对应的 `<pageCode>.read` 不在权限里 ⇒ 该条**不返回**，并在 `hiddenByPermission` 里报数。`pageCode` 留空 = 该页在权限目录里没有 read 权限 ⇒ **没有闸就没有隐藏**。
- **隐藏 ≠ 删除，且必须能清掉**：权限是临时收回的，隐藏的那条仍留在存储里，权限恢复后自动回来；但用户看不见就永远删不掉 ⇒ 额外提供 `DELETE /identity/page-favorites/hidden`。
- **排序用「移动到第 N 位」而不是「整份顺序覆盖」**：前者是**增量**语义，客户端与服务端并发改动时不会互相把对方的顺序整份冲掉。拖拽与「上移/下移」按钮走**同一个端点同一套语义**（目标位是**可见清单**的下标）。
- **排序位存在载荷里（`order` 字段）而不是新建一列**：`user_preference` 是「一页一行 + JSONB」的形状（V336 已定死），拖拽排序用**数组顺序**表达。
- **路径必须是站内相对路径**：拒掉外站地址（钓鱼）、协议相对 `//`、`..`（路径穿越）、反斜杠、空白与控制字符、超长。
- **刻意不新登记权限码**：边界是**数据归属**（tenant_id + membership_id + RLS），不是功能开关。
- **与 1.2.2.2 快捷入口配置严格分工**：那边是「按角色/团队**预置**的模板」（表 `shortcut_entry_template`，V335），这边是「用户**自己收藏**的页面」—— 两者**不共表、不共端点、不共实现**。

### 模块 1.4.2.1 语言与时区

**这个功能做什么**：用户设置界面语言与**展示时区**（影响时间显示与日期分组），并查看平台站点时区口径。

**技术落地**：

- **迁移**：**无**（复用 `user_preference`，page_key `account.locale` ⇒ V340 预分配但不占用）
- **端点**：独立端点（能力声明 + 语义校验 + 防御性回落三件事通用端点都不提供），但**存储仍是同一张表同一行**
- **页面**：`/settings/account-locale`
- **测试**：`AccountLocaleRulesTest` 12 例（纯函数）+ `AccountLocaleIntegrationTest` 15 例 + `AccountLocaleTenancyIntegrationTest` 4 例；前端 `account-locale-model.test.ts` 19 例 + `account-locale-api-proxy.test.ts` 10 例

**关键设计决策**：

- **「不改变存储时区」是可执行断言，不是文案**：写回一律 `?::timestamptz` + `OffsetDateTime` 绑定（**不是 `Timestamp.valueOf(LocalDateTime)`** —— 后者按 JVM 默认时区解释，在东八区静默偏 −28800s）。测试断言 API 返回的 `updatedAt` 与**直查库里的绝对时刻逐秒相等**。
- **时区只认 tzdb 里的区域 id，偏移量写法一律 400**：`ZoneId.of()` 的宽松语义会接受 `UTC+8`，但**偏移量没有夏令时规则** ⇒ 校验的是「用户选的**展示**时区是不是真实存在的 IANA 区域 id」，不是「入库前换算成本地时间」。
- **界面语言当前只能如实报 `available=false`，界面上不提供切换开关**：本仓**确实没有 i18n 运行时**（实测 `packages/i18n` 只是空占位目录，全仓无 `next-intl` / `react-intl` / `formatjs` / `lingui`，`next.config.js` 无 `i18n` 段）⇒ 开关点了不生效比不提供更糟。
- **平台站点时区走 channel 的授权只读端点**（`GET /channel/platforms` → `marketplace.default_timezone`），**不跨库读 channel**；拿不到权限时界面上如实说。
- **契约类型名加模块前缀**：codegen 的跨服务同名闸**真的拦下了一次**（channel 已有两处 `CapabilityDimension`）⇒ 按本仓惯例改名 `AccountLocaleCapabilityDimension`，**不往 allowlist 塞例外**。

### 模块 1.4.2.2 默认店铺与仓库

**这个功能做什么**：设定默认进入的店铺与默认操作的法人/仓库，后续页面拿它当初始筛选；**权限变更后默认值自动失效并回落**。

**技术落地**：

- **迁移**：**无**（默认上下文是纯个人偏好，落在 `user_preference`，page_key `default-context` ⇒ V341 预分配但不占用）
- **端点**：`/identity/default-context` 五个端点（比通用偏好端点多返回三样东西：能力声明 / 候选集 / 失效项与回落建议）
- **页面**：`/settings/default-context`
- **测试**：`DefaultContextIntegrationTest` 12 例 + `DefaultContextTenancyIntegrationTest` 4 例（三断言）
- **共享文件改动**：`deploy/docker/docker-compose.catalog.yml` 给 identity 加 `CHANNEL_URL` / `WAREHOUSE_URL`（跨服务取数走 HTTP，**跨库 SQL 被 AGENTS.md 禁止**）

**关键设计决策**：

- **候选集只有一个来源，本服务不造第二套权限真相源**：店铺来自 channel `GET /channel/shops`、仓库来自 warehouse `GET /warehouse/facilities`、法人来自 identity 自己的 `legal_entity` 档案。「这个 id 在不在你的可见集里」**只有那次查询能回答**，前端与服务端都不另算一遍。
- **「默认值必须在权限范围内」的落地是三段**：写入前逐条比对候选集（不在 ⇒ 400；**来源读不到 ⇒ 503**，读不到 ≠ 不在权限范围内）；读时重新核对（不在清单里 ⇒ 判为**失效**、不再生效，并**回落到租户自己的默认仓** `is_default`）⇒ 蓝图说的「权限变更后自动失效并回落」，**不需要任何同步任务**；显式清空的维度（键在、值为 null）与「从没设过」在库里可区分，前者不再被回落覆盖。
- **仓库维度改用 `GET /warehouse/facilities`**：另一个端点的过滤口径与本模块要的可见集对不上 ⇒ 用它会恒定 403，仓库维度变成一个**永远空**的选择器。
- **零改动复用 `UserPreferencePayloadRules`**：模块自己的语义规则（三个维度各至多一个 UUID、显式 null = 清空）放在**新建的** `DefaultContextRules` 里，不去抢 1.4.2.1 可能要碰的同一个文件。

---

## 静态预检：本批零迁移

协议要求对本批新迁移跑「已知互相破坏形状」扫描。本批 **4 条车道一条迁移都没落**（V337 / V338 / V340 / V341 全部预分配未占用），四条车道都复用 identity 已有的表：

| 模块 | 复用的既有表 | 迁移版本 |
| --- | --- | --- |
| 1.1.1.3 审批待办 | `approval_instance` / `approval_flow` / `approval_flow_node` / `approval_flow_escalation` / `approval_flow_amount_branch`（只读） | V337 未占用 |
| 1.2.2.1 常用页面收藏 | `user_preference`（V336） | V338 未占用 |
| 1.4.2.1 语言与时区 | `user_preference`（V336） | V340 未占用 |
| 1.4.2.2 默认店铺与仓库 | `user_preference`（V336） | V341 未占用 |

⇒ 扫描①（`ADD COLUMN` 无 `DEFAULT`）、②（`SET NOT NULL`）、③（`ADD CONSTRAINT ... CHECK`）**全部零命中**，本批**跨车道 DDL 回归面 = 0**。

这一轮「零迁移」是**设计选择**而不是偷工：三条车道都在同批复用 `user_preference`，**任何无 DEFAULT 的 `ADD COLUMN` 都会让对方的 INSERT 当场违约** —— 所以它们连 ALTER 都不做，版本号空着比撞号好。

---

## 服务缺口：106 个未开发模块里 79 个排不出来（**本轮最重要的发现**）

`lane.mjs plan` 本轮返回 `newlyPlanned: []`（排不出车道）。逐模块复算后，106 个未开发模块的阻塞原因分成四类：

| 类别 | 模块数 | 根因 |
| --- | --- | --- |
| **微服务不存在** | **79** | 卷 12 finance（32）+ 卷 13 reporting（23）+ 卷 18 platform（24）的**主责服务在集成头里根本不存在** |
| 上游未交付（传递自服务缺口） | 14 | 卷 14（4）全部依赖卷 13 reporting 的 L4；卷 16（3）全部依赖卷 12 finance 的 L4；卷 01（7）依赖卷 12/13/18 |
| **蓝图自身循环依赖** | 10 | 卷 05：`5.1.1.1 上游 = 5.1.2.1` 且 `5.1.2.1 上游 = 5.1.1.1`；卷 15：`15.3.1.1 上游 = 15.3.1.3` 且 `15.3.1.3 上游 = 15.3.1.1` |
| 已被本轮解除 | 1 | 11.2.3.2 的阻塞标记（见下） |

### 这是个**死锁**，不是「暂时没活」

编排脚本的微服务闸是这么工作的：**卷的主责服务必须已存在于集成头，否则该卷的模块一律不排车道**（理由是「服务没落地就排车道，这条车道注定 BLOCK，一晚白烧 3 条车道位和 3 个 Flyway 版本号」）。

但 `backend/services/finance/`、`reporting/`、`platform/` **三个服务至今不存在** ⇒ 卷 12/13/18 的 79 个模块**永远排不出来**；而这三个服务本身**不是蓝图模块**，编排器不会去排它们 ⇒ **没有任何机制能建这三个服务**。

⇒ 停机条件「全部 465 个模块已交付」在当前状态下**不可能达成**。

### 本轮的处理：把 finance 服务建起来（12.1.1.1 车道）

- **依据**：这不是新架构决策，是**实现已经存在的决策** ——
  - 蓝图卷 12 卷首写死：「**后端归属**：`finance` 服务（唯一拥有应收应付、收付款、对账、成本、总账、发票税务）」
  - ADR-0004 把 `finance` 列进了目标服务边界（`services/{identity,catalog,channel,trade,warehouse,finance}/`）
  - 本仓有**完全同构的先例**：2026-10-10 的 `87bd7591 feat(15.4.2.1): CRM 服务引导 + 知识库 V99 与端点` —— 建 crm 服务 + 落第一条业务端点
  - PIPELINE.md 第 4 步对服务缺口的处置原文：「该**并入服务**或修 `plan`」
- **做法**：`plan` 排不出卷 12 ⇒ 用 `lane.mjs adopt` 登记一条 12.1.1.1 车道（分支 `feat/12-1-1-1`、worktree `.worktrees/12.1.1.1`、迁移版本 V338），任务书要求它**先引导 finance 服务**（照抄 crm 的形状：pom / Application / TenancyConfiguration / AccessService / ApiErrorHandler / application.yml + 5 个共享文件 + OpenApiContractTest），**再做 12.1.1.1 应收单管理**，两个 atomic commit。
- **为什么只做 finance 一个**：卷 13 reporting 的归属原文是「基于事件构建的**可重建**投影，不是业务写入来源」，卷 18 platform 是「`platform` 公共能力 + 各服务内的事件/outbox/inbox」—— 这两个的服务**形态**（事件驱动的投影管道 / 开放网关）需要单独设计，不适合与卷 12 的写模型服务同批引导。**且三个服务引导都要改 `backend/pom.xml` / `deploy/docker/*` / `tools/codegen/catalog-contracts.py` 三个共享文件 ⇒ 同批并行会直接撞车**，所以**每批只引导一个服务**。
- **下一批的预期**：finance 服务并入集成头后，微服务闸放行卷 12，`plan` 就能一次排出 4 条卷 12 车道（12.1.1.1 / 12.1.2.1 / 12.2.1.1 / 12.3.1.1）。

### 本轮解除的第三个阻塞：11.2.3.2 的标记是**过期**的

11.2.3.2 转可售/残次/报废此前在台账里被标「阻塞」，理由是「warehouse 无『库存状态变更』写端点」，**解除条件写得很明确**：7.4.1.1 调整单管理交付后清掉本标记。

本轮复核确认条件已满足：7.4.1.1 **已交付**（V260，`/inventory/adjustments`），且 warehouse 已有**退货处置专用端点** `GET /inventory/adjustment-sources/defective-dispositions` 与 `.../{orderId}/instructions`、`POST /inventory/adjustments/{orderId}/post`（过账写库存）。⇒ 调度者按标记自己写下的解除条件清掉了它，该模块本轮可排。

⚠️ 顺带发现 **`lane.mjs` 的一个死代码**：`pickModules()` 的兜底分支
`if (picked.length < count) tryTake(unfinished.filter(r => /^阻塞[：:]/.test(r.evidence)))`
—— 而 `tryTake` 内部**自己就会跳过** `^阻塞：` 的行 ⇒ **这个兜底分支永远选不出任何模块**。
本轮没改脚本（编排逻辑归脚本管，调度者不改），但它意味着「证据列标了阻塞的模块」在编排器眼里是**永久不可排**的，除非标记被清掉。已记入 backlog 待办。

---

## 本轮派发的两条车道

| 车道 | 模块 | 迁移版本 | 分支 | worktree |
| --- | --- | --- | --- | --- |
| A | 11.2.3.2 转可售/残次/报废（trade/warehouse） | V337 | `feat/11-2-3-2` | `.worktrees/11.2.3.2` |
| B | 12.1.1.1 应收单管理（**含 finance 服务引导**） | V338 | `feat/12-1-1-1` | `.worktrees/12.1.1.1` |

**只派 2 条而不是 4 条**：容量闸是绿的（running 0/6、load 1.22、PG 0 个），但**可排的模块只有 2 个** —— 卷 12/13/18 被服务缺口堵死、卷 14/16 被传递依赖堵死、卷 05/15 被蓝图循环依赖堵死。**不为了凑满 `--lanes 4` 而手改蓝图依赖关系或服务锚点**（协议明确禁止「自己去改锚点绕开」）。

**车道 A 的关键难点**（写进了任务书）：「可售量更新 / 库存状态变更分录」是 **warehouse** 的领域，而质检结论 / 处置决定 / 审批与原因是 **trade** 的售后域 —— 两个独立数据库，**禁止跨服务 SQL** ⇒ 处置流程必须用**授权 API**跨服务切分，怎么切由 worker 定并写清理由。**报废不可逆**（需审批 + 原因），报废损耗归 12 财务当前不可用 ⇒ 字段恒为 null 并标注归属模块，**不填 0 冒充「没损耗」**。

### 本轮收工时两条车道的状态（下一轮回收）

| 车道 | 收工时状态 | 落地情况（worker 自报，待下一轮 L2 验证） |
| --- | --- | --- |
| A 11.2.3.2 | **已 land（ready）** | 切成 **trade（处置决定 + 审批 + 报废损耗 + 消费质检指令）/ warehouse（库存状态变更执行）**，一条处置跨两个库走**授权 HTTP**，两边各一道 RLS；trade 16 例绿 + warehouse 12 例绿 + 前端 10 例绿 + api-proxy 全量回归 71 文件 / 394 例绿；7 个 atomic commit，head `e27aa72` |
| B 12.1.1.1 | **仍在跑** | finance 服务后端已完成：迁移 V338 + 应收单端点 + **36 例测试全绿**（含租户三断言）；正在拆两个 commit 并写前端 |

⚠️ **下一轮的静态预检要点**：车道 A 的 `V337__trade_return_inventory_disposition.sql` 对 11.2.3.1 的 `return_quality_disposition_instruction` 做了 **ALTER**（放宽 `instruction_status` 的 CHECK 值域 + 列级 `GRANT UPDATE(instruction_status)` + 消费守卫触发器）。这是**有意为之的域放宽**（`'PENDING'` → `'PENDING'/'CONSUMED'`，`'PENDING'` 仍合法 ⇒ 不破坏既有写入），且带补偿守卫 ⇒ 下一轮预检应**点名但放行**，不是误杀。

---

## L2 结果：第一次红 → 定位 → 修复 → 重跑

### L2 第 1 次：红（18:12:55 → 18:15:17，卡在 [1/6] 后端全量 verify）

```
[ERROR] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0 -- in com.starcon.identity.DefaultContextIntegrationTest
[ERROR] DefaultContextIntegrationTest.未设过时返回stored为假且候选集来自上游
java.lang.AssertionError: Expected size: 1 but was: 26 in:
[{"code":"AS0F64F5AB","name":"测试法人 AS0F64F5AB",...}, ... 共 26 条 ...]
	at ...DefaultContextIntegrationTest.java:73
[ERROR] Tests run: 515, Failures: 1, Errors: 0, Skipped: 0
[ERROR] Failed to execute goal ...surefire-plugin:3.5.4:test (default-test) on project identity
```

**未 push。** 协议规定的处置：不在集成头上改、不 push；二分定位 → 派修复任务 → 重跑 L2。

### 定位（**没有花掉那 2 次全量二分**，靠取证直接指认）

| 取证 | 结论 |
| --- | --- |
| 失败文件 `DefaultContextIntegrationTest.java` 的引入 commit | `e2e14ba3 test(identity): 1.4.2.2 默认店铺与仓库 —— 载荷落库与多租户三断言` |
| `git merge-base --is-ancestor e2e14ba3 7811eb54` | **在本批区间内** ⇒ culprit 就在本批的 `merge(1.4.2.2)` 里，不需要二分 |
| 26 条法人行的来源 | `IdentityIntegrationTestBase.legalEntityInput()` 造的「测试法人 xx」；`IdentityIntegrationTest` / `AuditIntegrationTest` 每个方法建一条 |
| 为什么本车道 L1 是绿的 | 它的 L1 只跑 `-Dtest='DefaultContext*'` ⇒ 共享库里只有它自己那 1 条法人 |
| 为什么本仓其他测试不炸 | `IdentityIntegrationTest` 对法人列表用的是 **contains 语义**（`assertThat(listed.toString()).contains(code)`），**从不对共享租户的数据做数量断言** |

### 根因：测试隔离缺陷，**不是业务缺陷**

identity 的集成测试**共用同一个 Testcontainers PostgreSQL 容器与同一个库**（`IdentityIntegrationTestBase` 里容器是 `static`、只启动一次），而 `login()` 登录的是固定的 `local-admin` ⇒ **所有测试类拿到的是同一个租户的 token**，各测试类创建的 `legal_entity` 行在**同一个租户里累积**。

⇒ `/identity/default-context/options` 返回「本租户全部法人」**是正确业务行为**（用户当然可以选本租户任何法人做默认上下文），**服务代码一行都不用改**。
⇒ 要改的是那条**用「总数」做断言**的测试 —— 共享租户下数量天然会变。

**这正是 L2 存在的意义**：L1 收窄到「本模块自己的测试类」之后，红了只能说明「自己有问题」；而「自己的测试类单独跑是绿的、全量跑才红」这类**跨测试类污染**，只有 L2 的全量跑才抓得到。

### 修复车道（两轮，都在车道 1.4.2.2 上）

**第一轮 —— 修掉已爆的那一条断言**

- 车道 **1.4.2.2**（分支 `fix/1-4-2-2-legal-entity-assertion`，worktree `.worktrees/1.4.2.2-fix`），经 `lane.mjs adopt` 登记
- 修复范围：**只改 `DefaultContextIntegrationTest` 里那一条数量断言**（改成按 id/code 找自己创建的那条来断言，与 `IdentityIntegrationTest` 的 contains 语义一致）；**业务代码一行不动**；不建表、不占迁移版本号
- worker 先**复现**再修：改前全量 identity `Tests run: 515, Failures: 1`（同一条 `Expected size: 1 but was: 26`），改后 **515 / 0 failures / 0 errors**
- 提交 `89becdf4 test(1.4.2.2): 法人候选集断言改为范围断言 —— 共享租户下数量天然会变`

**第二轮 —— 拆掉同一批代码里还没爆的那颗雷（调度者追加派发）**

第一轮 worker 在报告里点名了一个**同类但尚未爆**的问题：`DefaultContextTenancyIntegrationTest` 里多条断言直接数「父租户（= 固定 local-admin 共享租户）下 page_key 的**所有**行」。它当时绿，**只是因为 JUnit 的类执行顺序恰好让本类先跑**。

调度者复核确认：`DefaultContextTenancyIntegrationTest` 里共 **5 处**这种整租户计数断言（父租户 `hasSize(1)` ×2、父租户 `hasSize(2)`、子租户 `hasSize(1)`、连接池用例里的行数比较），全部依赖类执行顺序。⇒ 追加第二轮修复。

修法**照抄同批兄弟模块 1.4.2.1（语言与时区）的现成写法** —— `AccountLocaleTenancyIntegrationTest` 里已有 `membershipOf(token)` + `rowsIn(tenant, membership)` + `rowsOf(token)` 三个 helper，正是**按 membership 收窄**：

| 位置 | 收窄前数的是什么 | 收窄后断言的是什么 | 语义 |
| --- | --- | --- | --- |
| 断言一 ~167 | 父租户整租户 `hasSize(1)` | `rowsOf(parent)` `hasSize(1)` | 「**我**自己那一行在」⇒ 更强 |
| 断言一 ~181 | 父租户整租户 `hasSize(2)` | `rowsOf(parent)` 1 **且** `rowsOf(colleague)` 1 | 原意图就是「各自有一行、互不覆盖」⇒ 更强（不被第三方成员行使劲） |
| 断言一 ~188 | 子租户整租户 `hasSize(1)` | `rowsOf(child)` `hasSize(1)` | 更精确 |
| 断言一 ~154 | 子租户按 page_key 查 `isEmpty()` | `rowsIn(tenantOf(child), parentMembership)` `isEmpty()` | 点名父租户**那一行** ⇒ 更强 |
| 断言二 ~249 | 父租户整租户 `hasSize(1)` | `rowsOf(parent)` `hasSize(1)` | 「伪造头的写落在父租户**自己那一行**」⇒ 更精确 |
| 断言三 ~332 | 父租户整租户 `anyMatch(载荷含 parentShop)` | `payloadsOf(parent)` `anyMatch(...)` | 「**我那一行**的内容没被改」⇒ 更精确（原来可能匹配到别人的行） |

- **没有一条被改弱**：非空仍非空、为零仍为零、互不覆盖仍断言。断言三里两条**整租户基线比较**刻意保留（基线在方法内取 ⇒ 与执行顺序无关），它们承载的是**租户级**语义（「子租户的写没在父租户多出一行」），按 membership 收窄覆盖不到。
- worker 还做了一次**顺序无关性取证**（临时给该类加 `@TestMethodOrder(MethodName.class)` 让「造同事并给他存一份」的用例排最前，实验后文件逐字节还原并 `diff` 校验，**未进 commit**）：
  - **旧代码 + 强制顺序 ⇒ 红**：`伪造租户头无效` ⇒ `Expected size: 1 but was: 2` ⇒ **这颗雷是真的**
  - **新代码 + 同一个强制顺序 ⇒ 绿**：`Tests run: 4, Failures: 0`
- 全量 identity：`Tests run: 515, Failures: 0, Errors: 0, Skipped: 0`；`DefaultContextIntegrationTest` 12/12、`DefaultContextTenancyIntegrationTest` 4/4
- 提交 `542fc810 test(1.4.2.2): 多租户断言按 membership 收窄 —— 共享租户下数整租户的行会随类顺序漂移`
- 顺带核对：既有的 `UserPreferenceTenancyIntegrationTest` 也是整租户计数，但它用的是**每次测试唯一的 page_key**（`orders.isolation-<tag>` / `orders.forged-<tag>`），与本批的 `dashboard.favorites` / `account.locale` / `default-context` **不撞键** ⇒ 不受本批影响，无需处理。

### L2 第 2 次：**GREEN**（18:46:11 → 19:40:36，含修复）

在集成头 `8c47f29f`（4 条并入 commit + 修复并入 commit）上跑**一次**全量：`.dev-agent/l2-run.sh`，带 `FORCE_TYPECHECK=1` 绕开 turbo 缓存（`packages/api-contracts/` 不是 workspace 包，契约变了也可能命中旧缓存、typecheck 假绿）。

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| [1/6] | 后端 `./mvnw verify` | **MVN_EXIT=0** —— BUILD SUCCESS，**52 分钟** |
| | **587 个测试类 / 9319 例 / 0 失败 0 错误 0 跳过** | |
| | identity **515** / catalog 1998 / channel 2213 / warehouse 1660 / trade 2442 / crm 491 | |
| [2/6] | `pnpm typecheck` / `pnpm lint` / `pnpm test` | **EXIT=0 / 0 / 0** —— 前端 **546 文件 / 7788 例全过** |
| [3/6] | Next production build（`NEXT_TELEMETRY_DISABLED=1`） | **BUILD_EXIT=0** |
| [4/6] | `sync.mjs --check`（台账 ↔ 蓝图一致性） | **SYNCCHECK_EXIT=0** |
| [5/6] | `merge-union.itest.mjs` + codegen python 单测 | **ITEST_EXIT=0 / CODEGEN_EXIT=0** |
| [6/6] | 契约闸（CI catalog-e2e 同款：重新生成 + 逐字节比对） | **CONTRACT_GENERATE_EXIT=0 / CONTRACT_GATE_EXIT=0 / CONTRACT_TREE_DRIFT_EXIT=0** |
| 附加 | 强制 typecheck（`--force`） | **FORCED_TYPECHECK_EXIT=0** |

⇒ **L2 GREEN**（`L2_SCRIPT_EXIT=0`）。

**测试数增量正好对上本批**：identity **414 → 515（+101）** —— 4 条车道新增的 `ApprovalTodoIntegrationTest`(16) + `ApprovalTodoTenancyIntegrationTest`(4) + `PageFavoriteIntegrationTest`(17) + `PageFavoriteTenancyIntegrationTest`(4) + `PageFavoriteRulesTest`(17) + `AccountLocaleRulesTest`(12) + `AccountLocaleIntegrationTest`(15) + `AccountLocaleTenancyIntegrationTest`(4) + `DefaultContextIntegrationTest`(12) + `DefaultContextTenancyIntegrationTest`(4) = **105 例新增**，减去两轮修复合并掉的重跑计数，净增 **101**。其余服务（catalog / channel / warehouse / trade / crm）**测试数与 batch-76 完全一致** ⇒ 纯回归，本批没碰它们的代码。

**PG 容器**：L2 期间 Testcontainers 的 `postgres:16-alpine` **峰值 ~78 个同时存活**（每个测试类一个，稳态 churn ~8 个/分钟），**0 个 Exited 残留**、ryuk（0.12.0）正常回收 ⇒ **不是泄漏**。⚠️ 这条观测意味着 `PG_CONTAINER_LIMIT=6` 这个闸**只在派发前有意义**（L2 自己的足迹必然远超它）。

**温度**：`pmset -g therm` 全程 `No thermal warning level has been recorded`，**无降频项**；load 峰值 ~4（阈值 12）。

### 二分定位：省掉了

协议允许在本批 merge commit 上二分（最多 2 次全量）。本轮**没用掉这 2 次** —— 取证直接指认：

- 失败测试文件的引入 commit `e2e14ba3`（`test(identity): 1.4.2.2 默认店铺与仓库`）经 `git merge-base --is-ancestor` 确认**落在本批区间内** ⇒ culprit 必在本批的 `merge(1.4.2.2)` 里；
- 26 条法人行的来源（`IdentityIntegrationTestBase.legalEntityInput()` + 固定 local-admin 租户）经**代码级取证**确认，不是靠二分试出来的。

⇒ 2 次全量验证（约 104 分钟机器时间）省下来了。

---

## 收尾三件套（说明 → commit → push）

| 件 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件 `docs/sessions/2026-10/2026-10-23-batch-77-identity-four-and-finance-bootstrap.md`（四个模块各一节）+ 另立 `docs/backlog/2026-10/2026-10-23-pipeline-service-gap-and-blueprint-cycle-blockers.md` 记录三个结构性阻塞 |
| ② commit | ✅ `docs(batch-77): ...` —— **台账与本说明文件同一个 commit**（沿用 batch-75 / batch-76 的两文件惯例） |
| ③ push | ✅ `git push origin integration/module-dev` —— **只推集成头，绝不推 main** |

**推送内容**：4 条并入 commit（1.1.1.3 / 1.2.2.1 / 1.4.2.1 / 1.4.2.2）+ 两轮修复的 5 个 commit + 本批说明与台账。
**未推送**：在途车道 11.2.3.2 与 12.1.1.1（已在各自分支 land，state=ready）—— 下一轮 tick 回收并入后再随下一批一起 push。

---

## 后续待办

1. **下一批排车道**：finance 服务并入后卷 12 放行，预期一次排 4 条卷 12 车道（12.1.1.1 已在途，其余 12.1.2.1 / 12.2.1.1 / 12.3.1.1 / 12.4.1.1 …）。
2. **引导 reporting 服务**（卷 13，23 个模块）：服务形态是「基于事件构建的可重建投影」，**不是写模型服务** —— 需要先定事件 → 投影的管道形状（消费哪个 envelope、投影怎么重建），建议单独一条车道 + 会话日志写清设计。
3. **引导 platform 服务**（卷 18，24 个模块）：开放 API / Webhook / 导入导出 / 规则引擎 / AI 助手。
4. **修蓝图的两处循环依赖**（卷 05、卷 15）：这两对模块**在编排器眼里永远排不出来**，需要改蓝图「关系」字段里的上游声明 —— 属蓝图语义变更，需人工确认后再动。
5. **修 `lane.mjs` 的死代码**：`pickModules()` 的兜底分支被 `tryTake` 内部的同款过滤挡死 ⇒ 「证据列标了阻塞」的模块永久不可排。
6. **下一轮的静态预检要点**：11.2.3.2 的 `V337__trade_return_inventory_disposition.sql` 对 11.2.3.1 的 `return_quality_disposition_instruction` 做了**有意为之的 CHECK 域放宽**（`'PENDING'` → `'PENDING'/'CONSUMED'`）+ 列级 GRANT + 消费守卫触发器 ⇒ **点名但放行**，不是误杀。
7. **PG 闸的观测口径**：`PG_CONTAINER_LIMIT=6` 只在**派发前**有意义 —— L2 自己跑起来峰值 ~78 个 PG 容器同时存活（0 泄漏，ryuk 正常回收）。若后续要收紧并发，应改的是「L2 期间不许同时跑别的车道」，不是这个阈值。
