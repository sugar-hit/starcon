# 2026-10-20 · 4.4.1.2 批量/插件采集

> 车道 4.4.1.2 · 分支 `feat/4-4-1-2` · 基线 `integration/module-dev`（9f0fd1d7）
> 蓝图原文：`docs/product/capability-blueprint/04-listing-and-content.md` §4.4.1.2

## 决策摘要

**蓝图职责**：「支持按分类页、关键词搜索结果批量采集，以及浏览器插件在用户浏览时抓取当前页商品，解决大批量找货场景。输入：分类页 URL / 关键词 + 页数范围；插件上报的页面数据。输出：批量采集任务 + 结果清单。关系：上游 = 4.4.1.1；下游 = 4.4.1.3。**抓取必须有频率限制与去重**（同 URL 不重复抓、已存在的商品跳过），避免重复劳动与外部站点压力。」

**归属 channel 服务**：输入是外部页面 / 插件上报数据，输出是采集草稿，中间不碰任何一张 catalog 的事实表。与 4.1.1.1 / 4.4.1.1 / 4.4.2.1 同一先例。

**🔴 复用 4.4.1.1 的采集模型，不另起一套**（蓝图「上游 = 4.4.1.1」+「下游 = 4.4.1.3 认领采集草稿」）：

| 本模块产出的东西 | 落在哪张**既有**表 | 说明 |
| --- | --- | --- |
| 一次商品抓取 | `listing_collect_task` | 4.4.1.1 的表，只 INSERT，不改结构 |
| 一条采集草稿 | `listing_collect_draft` | 下游 4.4.1.3 采集箱认领的就是它 |
| 规范化 URL | `ListingCollectUrl.normalize` | 直接复用 4.4.1.1 的纯函数 |
| 商品页解析 | `ListingCollectHtmlParser.parse` | 直接复用 4.4.1.1 的解析器（JSON-LD / og / 规格表） |
| 平台白名单 | `listing_collect_source` | 4.4.1.1 的全局码表，只读复用 |

⇒ 本模块**一行判定逻辑都没重写**，只新写「列表页链接解析 + 批量编排 + 限频 + 结果清单」。

**三条纪律的物理落点**：

| 纪律 | 落点 |
| --- | --- |
| 只产生草稿 | ① 草稿表/结果表无 spu/sku/售价/库存任何一列 ② 服务层只写自己的四张表 + 4.4.1.1 的两张草稿表 ③ 能力表 `claim_to_spu` available=false + ownerModule=3.1.1.1 |
| 限频 | 三层（并发数 / 任务启动间隔 / 同 host 抓取间隔），每层都有**明确的 429 拒绝 + 可读原因**；运行中被打断停在 `RATE_LIMITED`，已采结果全部保留 |
| 去重 | 去重键 = 4.4.1.1 的规范化 URL；跨**链接采集 / 批量 / 插件**三种入口共享同一个去重事实；`listing_bulk_collect_result_fetched` 部分唯一索引从 DB 层钉死 |

## 关键 trade-off

### 1. 「建任务」与「执行」分成两个端点（限频的拒绝要发生在**发起时**）

`POST /` 只建任务（PENDING），`POST /{id}/run` 才真抓。理由：限频的拒绝必须是**明确的 429 + 还要等多少秒**，
而不是让调用方在一次长请求里等半天最后拿到一句「太频繁了」。运行被限频打断时任务停在 `RATE_LIMITED`，
**已采到的结果全部保留**，再调一次 `/run` 就从断点 `next_page` 接着采。

### 2. 批量采集有**自己**的同 host 间隔，而不是直接用 4.4.1.1 的 30–60 秒

单条采集是**人点的**，一次一个链接，间隔大没有代价；批量采集是**机器跑的** —— 30 秒一个请求意味着
一个 3 页 × 20 条的任务要跑半小时，那不是限频，那是把功能做成不可用。
⇒ 批量间隔按平台单独配（`listing_bulk_collect_search.bulk_min_interval_seconds`，默认 2 秒 = 0.5 req/s），
与单条采集的保守间隔**并存**：两条路径都受「同租户同 host 的最小间隔」约束，只是速率不同。运维可随时调整。

### 3. 插件上报**不触发服务端抓取**

插件已经在用户的浏览器里把数据取到了，服务端再抓一遍等于把外部站点的压力翻倍 —— 而这正是蓝图要避免的。
⇒ 服务端只做三件事：**白名单校验**（插件不能把任意 URL 塞进采集箱）、**去重**、**落成采集草稿**。
插件上报的采集任务记 `http_status=0`，表示「不是本服务发起的 HTTP 抓取」。
（`listing_collect_draft.task_id` 是 NOT NULL 且外键指向采集任务 ⇒ 草稿不可能脱离采集任务存在，
这不是本模块的选择，是 4.4.1.1 已经定下的结构纪律。）

### 4. 关键词模式需要「平台搜索模板」配置（新增一张全局码表）

蓝图的关键词入口是「**关键词 + 页数范围**」，光有关键词拼不出 URL —— 每个平台的搜索页地址与翻页参数都不一样。
把模板做成**配置**（`listing_bulk_collect_search`）而不是写死在 Java 里：平台改版时只改码表，不用改代码；
请求里还可以**临时覆盖**模板，不必等运维改配置。种子填的是各平台真实的公开搜索入口
（1688 `beginPage` 1 基、淘宝/天猫 `s` 0 基 ⇒ 用 `page_origin` 表达偏移）。

### 5. 列表页解析只认**结构信号**，不认平台私有类名也不认路径前缀

写死 `.offer-title` 这类选择器等于把某个平台的目录结构刻进代码（换平台即失效，而测试里的假 HTML 仍会绿）；
写死 `/item/` `/offer/` 路径前缀则会漏掉平台自己的 `/detail/xxx.html`。
⇒ 判据是「商品卡片 = 一个带**图**或带 **title 属性**的链接」—— 这是几乎所有列表页都有的稳定结构。
（光有链接文字**不算**：导航栏里每个链接都有文字，那样整页导航都会被当成商品。）
channel 依赖树里没有 jsoup 且**不得改 pom** ⇒ 解析器用 JDK 正则手写（`ListingBulkCollectHtmlParser`），**零新增依赖**。

### 6. 页码参数拼装是「尽力而为」，异常平台的逃生口是请求里的模板覆盖

分类页翻页用平台配置的 `category_page_param` 拼（淘宝的 `s` 是 0 基、1688 的 `beginPage` 是 1 基 ⇒ `page_origin` 表达偏移），
但**不是所有平台的翻页都是线性的**（淘宝 `s=48` 才是第二页）。⇒ 请求里可以覆盖 `pageUrlTemplate`，
拼不出来时给出可读原因而不是拿半成品 URL 去抓。

## 文件清单

### 后端（channel 服务）

| 文件 | 说明 |
| --- | --- |
| `backend/services/channel/src/main/resources/db/migration/V204__listing_bulk_collect.sql` | 四张新表 + RLS + 索引 + GRANT + 两张码表种子 |
| `backend/services/channel/src/main/java/com/starcon/channel/ListingBulkCollectModels.java` | 传输模型（具名 record，不用 Map） |
| `backend/services/channel/src/main/java/com/starcon/channel/ListingBulkCollectService.java` | 限频 / 去重 / 真抓 / 落库 / 能力表 |
| `backend/services/channel/src/main/java/com/starcon/channel/ListingBulkCollectController.java` | 端点 |
| `backend/services/channel/src/main/java/com/starcon/channel/ListingBulkCollectHtmlParser.java` | 依赖零的**列表页**解析器（商品链接 / 图 / 价） |
| `backend/services/channel/src/test/java/com/starcon/channel/ListingBulkCollectIntegrationTest.java` | 21 例，真实本地 HTTP 服务 |
| `backend/services/channel/src/test/java/com/starcon/channel/ListingBulkCollectTenancyIntegrationTest.java` | 3 例，多租户三断言 |

### 契约（codegen 产物，**禁止手改**）

- `packages/api-contracts/src/channel.openapi.json`
- `packages/api-contracts/src/contracts/channel.ts`

其余 5 个服务的契约**逐字节不变**（已核对：codegen 只报告了 channel 两个文件被改）。

### 前端

| 文件 | 说明 |
| --- | --- |
| `apps/web/src/app/listings/collect/bulk/page.tsx` | 页面（**新建**，不改 4.4.1.1 的 `/listings/collect`） |
| `apps/web/src/components/listing-bulk-collect/listing-bulk-collect-model.ts` | 纯函数 + 取数 |
| `apps/web/src/components/listing-bulk-collect/listing-bulk-collect-model.test.ts` | 21 例单测 |
| `apps/web/src/components/listing-bulk-collect/listing-bulk-collect-workspace.tsx` | 工作区（加载 / 空 / 错误三态俱全） |
| `apps/web/src/app/api/channel/listing-bulk-collect-allowlist.test.ts` | 白名单双向断言 |
| `apps/web/src/lib/api-proxy.ts` | **纯增量**：新增一条 `listing-bulk-collects` 元素，插在 `listing-collects` 之后 |

### 文档

- `docs/product/capability-blueprint/module-progress.md` —— **只改 4.4.1.2 那一行**的状态与证据两格

## 端点清单

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/channel/listing-bulk-collects/capabilities` | GET | 逐维度能力声明 + 当前白名单 + 限频阈值 |
| `/channel/listing-bulk-collects` | GET | 批量任务列表（真分页 + 模式/状态/来源筛选 + 时间倒序） |
| `/channel/listing-bulk-collects` | POST | 发起批量采集（只建任务；限频在此 429） |
| `/channel/listing-bulk-collects/{id}/run` | POST | 执行批量采集（真发 HTTP、真落草稿；可续跑） |
| `/channel/listing-bulk-collects/plugin-reports` | POST | 浏览器插件上报当前页商品 |
| `/channel/listing-bulk-collects/{id}` | GET | 批量任务详情 |
| `/channel/listing-bulk-collects/{id}/results` | GET | 结果清单（真分页 + 状态筛选） |

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 通过（exit 0） |
| L0 类型 | `pnpm typecheck` | 通过（3/3 包） |
| L1 模块 | `./mvnw -pl services/channel -am test -Dtest='ListingBulkCollect*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **24 例全绿**（集成 21 + 多租户 3），0 失败 0 错误；Flyway 跑到 `v204` |
| 前端单测 | `pnpm vitest run apps/web/src/components/listing-bulk-collect apps/web/src/app/api/channel/listing-bulk-collect-allowlist.test.ts` | **26 例全绿**（2 文件） |
| allowlist 全量回归 | `pnpm vitest run apps/web/src/app/api` | **91 文件 / 554 例全绿**（改了 `api-proxy.ts` ⇒ 按流水线规则跑全量） |
| Lint | `pnpm lint` | 通过（3/3；余 6 条 warning 全在 catalog / orders / warehouse 的**既有**文件里，本模块零告警） |

**容器**：L1 期间 Testcontainers 峰值同时存活 **2 个 `postgres:16-alpine` + 1 个 `testcontainers/ryuk:0.12.0`**
（两个测试类各起一个库），ryuk 在测试结束后**全部回收** —— 事后 `docker ps -a` 里已无 `postgres:16` 与 ryuk 容器。

## 后续待办

- **4.4.1.3 采集箱**：消费 `listing_collect_draft`（暂存分组 / 批量认领 / 批量删除 / 批量打标）。
  认领时必须做品牌与分类映射 —— 4.4.1.1 已经把品牌 / 货号 / 类目抓进 `attributes`，可作映射输入。
- **浏览器扩展本体**：本模块只交付**上报入口**（服务端接收端）。扩展怎么在页面上取数、怎么装、怎么升级
  都不在本仓范围 —— 需要一个独立的扩展工程来调用 `POST /channel/listing-bulk-collects/plugin-reports`。
- **平台搜索模板维护**：`listing_bulk_collect_search` 的种子是各平台当前的公开搜索入口，平台改版时要改码表
  （**不是**业务端点）。改之前先用请求里的 `pageUrlTemplate` 覆盖试一把。
- **03 catalog 媒体库**：认领时把草稿里的图片 URL 交给 03 落库（本模块**不**跨服务写表）。
- **售价（05 定价）**：清单与草稿上的 `price_amount` 是**外部页面原文标价**，与我方售价无关；
  我方售价归 05 定价整卷，交付前采集草稿不带任何我方价格事实。
