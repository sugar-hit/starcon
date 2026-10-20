# 2026-10-20 · 4.4.1.1 链接采集

> 车道 4.4.1.1 · 分支 `feat/4-4-1-1` · 基线 `integration/module-dev`（11f19237）
> 蓝图原文：`docs/product/capability-blueprint/04-listing-and-content.md` §4.4.1.1

## 决策摘要

**蓝图职责**：「粘贴外部商品链接（1688/淘宝/供应商站/同行 listing），抓取标题、主图、规格、属性、描述等，生成采集草稿供认领为内部商品。输入：商品 URL（支持平台白名单）；抓取任务。输出：采集到的商品草稿（图片下载入媒体库）；来源 URL 与抓取时间。**采集只产生草稿**，进入正式商品库必须走 03 的建档流程并补全属性与合规信息。」

**归属 channel 服务**：输入是外部页面 URL、输出是采集草稿，中间不碰任何一张 catalog 的事实表
（建档是 03 的 3.1.1.1，认领是 4.4.1.3 采集箱）。与 4.1.1.1 / 4.4.2.1 / 4.3.1.2 同一先例。

**三条边界各有一层物理落点**（不是靠注释提醒）：

| 边界 | 落点 |
| --- | --- |
| 只产生草稿 | ① 草稿表没有 sku/spu/内部可售量/售价**任何一列** ② 服务层只写两张自己的表 ③ 能力表 `claim_to_spu` available=false + ownerModule=3.1.1.1 |
| 图片只记 URL | 能力表 `media_library` available=false + ownerModule=03；集成测试逐条断言草稿表**没有**媒体表、图片存的是地址字符串 |
| 白名单 + 限频 + 去重 | 白名单是全局码表（只授 SELECT，租户不能自行加域名）；限频基准是抓取历史本身（不另建表）；去重是规范化 URL 上的部分唯一索引 |

## 关键 trade-off

### 1. 「图片下载入媒体库」→ 只记 URL（**跨服务写表被 AGENTS.md 禁止**）

蓝图写「图片下载入媒体库」，但媒体库是 03 catalog 的事实表。本模块**只记录图片 URL**，
**不下载二进制、不写媒体表**，并把这一维在能力声明里显式标成 `available=false` + `ownerModule=03` + 影响说明。
下载入媒体库那一步由 03 侧承担 —— 认领时由下游把 URL 交给 03 落库。
**这不是少做一半，而是把跨服务写的那一半交还给它的所有者**；本模块交付的是「图片的来源 URL 列表」这件自己能独立验证的事。

### 2. 解析落点只认稳定结构（**不认平台私有 DOM 类名**）

1688 / 淘宝的类名随时会变，写死 `detail-desc` 这类选择器等于写一个假实现 —— 站点改版即失效，
而测试里的假 HTML 仍会绿。解析只认真实商品页普遍提供的三类结构：**JSON-LD**（schema.org `Product`）、
**Open Graph / meta 标签**、**`<title>` 与规格表**。

**channel 依赖树里没有 jsoup，而本车道不得改 `channel/pom.xml`**（4 条并行车道共用它，撞 pom = 整批返工）
⇒ 解析器用 JDK 正则 + Jackson 手写（`ListingCollectHtmlParser`），**零新增依赖**。

**商品信号判据**：光有 `<title>` **不算**商品页 —— 404 页 / 列表页 / 登录页也都有 `<title>`。
必须命中 JSON-LD Product / `og:title` / `og:image` 之一，否则判失败并说清「抓到的是错误页而不是商品详情页」，
**不静默产出一份空草稿**。

### 3. 白名单做成**全局码表**而不是租户级配置

与 V1 `platform` / `marketplace` 同一先例：不带 tenant_id、不启用 RLS、**只授 SELECT**。
这样**租户不能自行加白名单域名** ⇒ 抓取面不会因为单个租户而变大（一个能随便填 URL 的采集端点就是 SSRF 入口）。
运维改白名单 = 改码表，不经过任何业务端点。

匹配规则是「host **完全相等**或以 `.`+host 结尾」⇒ `detail.1688.com` 命中而 **`evil-1688.com` 不命中**
（朴素 `endsWith` 会把攻击者控制的域名放进来）。

**重定向到白名单外同样 400**：绕一圈不能把白名单拒绝降级成普通抓取失败。

### 4. 限频状态**不另建表**（避免两份事实）

限频基准是 `listing_collect_task` 里该租户该 host 最近一次抓取的时间 ——
它是抓取历史本身的一个投影。另建一张限频表就会出现「限频表说没抓过、抓取历史说抓过」的两份事实。
最小间隔按平台配（`listing_collect_source.min_interval_seconds`），超限 429 并写明**还需等多少秒**。

### 5. 去重用**部分唯一索引**（失败的抓取不占名额）

`listing_collect_task_one_success ... WHERE state='SUCCEEDED'` + 草稿表 `UNIQUE(tenant_id, normalized_url)`。
整表 UNIQUE 会让**一次网络抖动就把这个 URL 永久锁死** —— 失败的抓取必须能重试。
规范化做三件事：去跟踪参数（utm/spm/from 等）、归一 host、排序查询参数；
**路径大小写保持原样**（擅自小写会把两个不同商品页合并 —— 那是错杀，比多抓一次严重得多）。

### 6. 采集是**同步**的（一次请求 = 一次抓取）

蓝图没要求异步。同步的代价是请求线程最多占 20 秒（连接 5s / 读取 15s 超时），
换来的是失败原因、限频与去重都在**同一次响应**里说清楚，测试完全确定。
4.4.1.2 的批量采集如果需要异步，那条链路的形状由它自己定。

## 文件清单

### 后端（channel 服务）

| 文件 | 说明 |
| --- | --- |
| `backend/services/channel/src/main/resources/db/migration/V202__listing_collect.sql` | 三张新表 + RLS + 索引 + GRANT + 白名单种子 |
| `backend/services/channel/src/main/java/com/starcon/channel/ListingCollectModels.java` | 传输模型（具名 record，不用 Map） |
| `backend/services/channel/src/main/java/com/starcon/channel/ListingCollectService.java` | 白名单 / 限频 / 去重 / 真抓 / 落库 / 能力表 |
| `backend/services/channel/src/main/java/com/starcon/channel/ListingCollectController.java` | 端点 |
| `backend/services/channel/src/main/java/com/starcon/channel/ListingCollectHtmlParser.java` | 依赖零的 HTML 解析器（JSON-LD / og / 规格表） |
| `backend/services/channel/src/main/java/com/starcon/channel/ListingCollectUrl.java` | URL 规范化与白名单匹配（纯函数） |
| `backend/services/channel/src/test/java/com/starcon/channel/ListingCollectIntegrationTest.java` | 24 例，真实本地 HTTP 服务 |
| `backend/services/channel/src/test/java/com/starcon/channel/ListingCollectTenancyIntegrationTest.java` | 3 例，多租户三断言 |

### 契约（codegen 产物，**禁止手改**）

- `packages/api-contracts/src/channel.openapi.json`
- `packages/api-contracts/src/contracts/channel.ts`

其余 5 个服务的契约**逐字节不变**（已核对）。

### 前端

| 文件 | 说明 |
| --- | --- |
| `apps/web/src/app/listings/collect/page.tsx` | 页面 |
| `apps/web/src/components/listing-collect/listing-collect-model.ts` | 纯函数 + 取数 |
| `apps/web/src/components/listing-collect/listing-collect-model.test.ts` | 25 例单测 |
| `apps/web/src/components/listing-collect/listing-collect-workspace.tsx` | 工作区（加载 / 空 / 错误三态俱全） |
| `apps/web/src/app/api/channel/listing-collect-allowlist.test.ts` | 白名单双向断言 |
| `apps/web/src/lib/api-proxy.ts` | **纯增量**：新增一条 `listing-collects` 元素，插在 `listing-claims` 之后 |

### 文档

- `docs/product/capability-blueprint/module-progress.md` —— **只改 4.4.1.1 那一行**的状态与证据两格

## 端点清单

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/channel/listing-collects/capabilities` | GET | 逐维度能力声明 + 当前白名单 |
| `/channel/listing-collects` | GET | 采集历史（真分页 + 状态/来源筛选 + 时间倒序） |
| `/channel/listing-collects` | POST | 发起一次链接采集 |
| `/channel/listing-collects/{id}` | GET | 采集任务详情（任务 + 草稿） |

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 通过（exit 0） |
| L0 类型 | `pnpm typecheck` | 通过（3/3 包） |
| L1 模块 | `./mvnw -pl services/channel -am test -Dtest='ListingCollect*' -Dsurefire.failIfNoSpecifiedTests=false` | **27 例全绿**（集成 24 + 多租户 3），0 失败 0 错误；Flyway 跑到 `v202` |
| 前端单测 | `pnpm vitest run apps/web/src/app/api/channel/listing-collect-allowlist.test.ts apps/web/src/components/listing-collect` | 29 例全绿（2 文件） |
| allowlist 全量回归 | `pnpm vitest run apps/web/src/app/api` | **87 文件 / 537 例全绿**（改了 `api-proxy.ts` ⇒ 按流水线规则跑全量） |
| Lint | `pnpm lint` | 通过（3/3；余 6 条 warning 全在 catalog / orders / warehouse 的**既有**文件里，本模块零告警） |

**容器**：L1 期间 Testcontainers 峰值同时存活 **2 个 `postgres:16-alpine` + 1 个 `testcontainers/ryuk:0.12.0`**
（两个测试类各起一个库），ryuk 在测试结束后**全部回收** —— 事后 `docker ps -a` 里已无 `postgres:16` 与 ryuk 容器。

## 后续待办

- **4.4.1.3 采集箱**：消费 `listing_collect_draft`（暂存分组 / 批量认领 / 批量删除 / 批量打标）。
  认领时必须做品牌与分类映射 —— 本模块已经把品牌 / 货号 / 类目抓进 `attributes`，可作映射输入。
- **4.4.1.2 批量/插件采集**：限频与去重的**机制**已在 4.4.1.1 这一层就位（限频基准是抓取历史，
  去重键是规范化 URL），批量链路直接复用即可，不需要另建一套。
- **03 catalog 媒体库**：认领时把草稿里的图片 URL 交给 03 落库（本模块**不**跨服务写表）。
- **白名单扩容**：新来源走 `listing_collect_source` 的运维变更（**不是**业务端点）。
- **售价（05 定价）**：草稿上的 `source_price_amount` 是**外部页面原文标价**，与我方售价无关；
  我方售价归 05 定价整卷，交付前采集草稿不带任何我方价格事实。
