# 2026-10-19 批次 26：三条车道并入 + 台账解析缺陷修复

> 本批的起因不是「派发新车道」，而是**回收两条被 Token Plan 上限掐断的车道**。
> 13:00 那轮 tick 在 13:27:36 被运行时强杀（错误码 `42212`，Token Plan 用量上限），
> 留下 `11.1.2.2` 与 `4.3.1.2` 两条**有大量未提交成果**的车道；
> 14:00 那轮 tick 更是在启动 1.65 秒后即因同一原因终止。
> 本轮把两条车道接手续写，与上一轮遗留的 `4.1.2.2` 一起并入并验收。

---

## 模块 4.1.2.2 映射版本

**这个功能做什么**：运营能按 SKU、店铺、生效区间筛出「哪些商品走的是哪一版映射」，
并能以某个版本为快照回溯它当时覆盖了哪些商品；改动映射后，
历史版本保持冻结，不会被后来的改动追着改。受影响订单查不到时如实报「不可用」并写明归属模块，
而不是给一个空列表让人以为没影响。

**技术落地**：

- 迁移：`V182__listing_mapping_version_scope.sql` — 版本冻结快照与商品范围
- 端点：`ListingMappingVersionController` — 列表（真分页 + 生效区间重叠筛选）、
  能力声明、筛选词表、单版详情、按版本回溯商品范围、受影响订单范围
- 页面：`/listings/mapping-versions`
- 测试：`ListingMappingVersionIntegrationTest` 11 例（含多租户三断言 + 改映射不影响既有版本）
- api-proxy：新增一条独立 `services[]` 元素，前缀 `mapping-versions`，**全是 GET** ——
  放行一个能改写历史版本的写方法比 404 更糟

**关键设计决策**：用 `mapping-versions` 独立前缀，而不是挂在
`listing-mappings/{uuid}/versions` 下。因为 4.1.2.1 已把 `affected-orders` 显式登记为「不放行」，
往那条分支加子路径会让两个车道改同一行超长正则，合并必冲突。

---

## 模块 4.3.1.2 批量发布

**这个功能做什么**：一次筛出一批待发布商品，生成批次任务，按各平台自己的限流配额排队派发；
过程中可以暂停、恢复、调整顺序、只重试失败的那些，人工中止后未派发的立刻退回。
批量发布是**队列**语义，与单条发布的「列表行」不是同一种资源。

**技术落地**：

- 迁移：`V183__listing_publish_batch.sql`
- 端点：`PublishController` / `PublishService` — `listing-publish/**` 共 14 个形状：
  能力声明、限流配置、批次列表/详情、任务列表、派发、暂停、恢复、中止、调序、批量重试、任务回执
- 页面：`/listings/publish`（含加载 / 空 / 错误三态）
- 测试：`PublishIntegrationTest` 14 例，实跑 Testcontainers PostgreSQL 16
- api-proxy：独立 `services[]` 元素，锚在 `listing-details` 之后；
  `capabilities` / `rate-limits` 必须排在 `{uuid}` 之前，否则被 uuid 分支吃掉半截

**刻意不放行**：`listing-publish/batches/{uuid}/retry-all`（没有「连成功的一起重跑」的入口，
那是本模块最危险的假实现——重复发布）、`tasks/{uuid}`、`rate-limits/{uuid}`。

**接手时纠正的一处误判**：上一位 worker 的台账记录说「剩余前端工作区 + 白名单」，
实际两者都已写完。真正的缺口是**前端从未通过 typecheck**——3 处非法 Button 取值
（`variant="primary"`、`size="icon-sm"`），上一位被杀在跑验证之前。
顺带修了「排队排队」重复字。

---

## 模块 11.1.2.2 商品问题归因

**这个功能做什么**：售后里一条「这批货有问题」的记录能被归因到具体批次与具体商品，
并把当时的证据（照片前后对比、批次标记、事件流）冻结下来，改判也留痕。

**技术落地**：

- 迁移：`V180__product_issue_attribution.sql` — 5 张新表，**零 ALTER 既有表、零外键**
- 端点：`ProductIssueAttributionController`（`/trade/product-attribution` 下 10 个形状）、
  `ProductLotTracePort`（跨服务**读**端口，不跨库查）
- 页面：`/aftersales/attributions`
- 测试：`ProductIssueAttributionIntegrationTest` 13 + `…Tenancy…` 4 + `ProductLotTracePort…` 4 = **21 例**

**接手时发现并纠正的两处**：

1. **契约的「假删除」**：`git diff trade.ts` 看似把兄弟车道 11.2.2.2 的全部 `ReturnAllocation*`
   和 11.2.3.1 的 `QualityAction` 删了。以真基线做集合比对后确认：
   schemas 942→965、**删除集为空**、paths 0 删除/10 新增 —— 是字母序重排的位移，契约为纯超集。
2. **台账有个核对不上的数字**：原写「2989 DTO 全在」，实测口径是 942→965，已改正，
   并补上原先完全缺失的 L1 测试数与多租户三断言证据。

---

## 本批修掉的两个真缺陷

### 1. 台账解析会跨行吃掉邻行（`d031150f`）

并入后 `sync.mjs` 报 **206** 已交付，而基线 204 + 本批 3 条 = **207**。
少的那一个不是没并进去，是**已交付模块被误判成未开发**：
`11.2.1.1 退货授权单`（2026-10-18 交付，lane head 是集成分支祖先）状态被抹成 `未开发`、证据清空。

根因在 `readExisting()` 的行正则：每格用 `[^|]*`，而 `[^|]` **也匹配换行**。
本批 `11.1.2.2` 的台账行少写了结尾竖线（6 根而不是 7 根），
于是它的「证据」格一路吃到下一行，把 `11.2.1.1` 整行吞进捕获组：

```
| 11.1.2.2 | … | 已交付 | 迁移 …     ← 本行缺结尾竖线
| 11.2.1.1 | 退货授权单 | …            ← 被当成上一行的证据吃掉
```

`11.2.1.1` 于是拿不到自己的匹配，状态不被继承，按既有回落规则变成 `未开发`、证据被抹平。

**这个故障完全静默**：`sync` 照常打印统计，`sync --check` 也通过。
只有把基线与现状做**集合差**才看得出来 —— 而集合差同时证明「3 条新增、0 条回归」。

修法：每格改成 `[^|\n]*`。畸形行仍匹配失败（自己回落，符合既有语义），但不再跨行伤及邻行；
对所有格式正确的行行为完全不变。

### 2. 交付了页面却没给入口（`34e835c8`）

`4.3.1.2` 交付了 `apps/web/src/app/listings/publish/page.tsx`，
但 `packages/ui/src/lib/task-pages.ts` 里这条仍是 `implemented: false`，
于是**页面存在但菜单里点不到**。L2 前端闸抓到 2 例红，正是
`nav-routes.test.ts` 里「有真实页面的任务页不能被标成未实现（防止漏做入口）」那条守卫的职责。

改一个标记即可，**不需要另改 nav-config**：`nav-config` 的 `children` 是按 `domain`
从 `IMPLEMENTED_TASK_PAGES` 派生的，`/listings/publish` 的 `domain: 'listings'`
与 devSystem 的「刊登中心」(`/listings`) 天然匹配，翻 `true` 就自动进 `NAV_DESTINATIONS`。

---

## 验收

| 闸 | 结果 |
| --- | --- |
| 静态预检（V180/V182/V183 互相破坏形状） | 0 命中：三条都是**纯新建表**，无 `ADD COLUMN` 无 DEFAULT、无 `SET NOT NULL`、无新 CHECK |
| 后端 `mvnw verify` | **1338 例 / 0 失败 / 0 错误 / BUILD SUCCESS** |
| 前端 typecheck / lint | 绿 |
| 前端 vitest | **248 文件 / 4174 例全绿** |
| Next production build | 绿 |
| `sync.mjs --check` | 绿 |
| 台账完整性 | 465 行、无重复 ID、与基线集合差 = 3 增 0 减 |

**一次 L2 全量跑出过 27 个错，全部归因后才动的手**：

- `WarehouseLocationTreeIntegrationTest` 26 例 · PG 连接在 SSL 协商阶段 EOF，
  全部 0.001s 内挂在类初始化上 —— **隔离复跑 25/25 绿**；本批**未改动 warehouse 任何文件**。
- `FulfillmentRequestIntegrationTest` 1 例 · `fulfillment_request_step_check3` 违约 ——
  **隔离复跑 33/33 绿**；本批在 trade 只加了 V180（5 张新表，其 `ALTER` 只给自己的新表开 RLS），
  未碰该测试、该服务与定义该约束的 `V152__trade_fulfillment_request.sql`。

两个都是「成片同形失败」形状（PG/Testcontainers 在全量并发下的争抢），
按纪律先隔离复跑再下结论，而不是直接归因到新车道。

**`api-proxy.ts` 的并入冲突**同样是先取证再动手：`4.1.2.2` 与 `4.3.1.2` 各自选了**同一个插入锚点**，
git 行级兜底并集把两段注释并进了同一个元素槽。解法是按「两个独立元素」求并而不是二选一 ——
相对 HEAD 是纯追加（0 行删 / 46 行增），两个元素各自与其已落地版本**逐字节一致**，
其后 9.2.1.2 条目未受波及。判据不是肉眼，是这个文件的指定仲裁测试：
`pnpm vitest run apps/web/src/app/api/trade` → **41 文件 / 218 例全绿**。
（该文件取错一侧会静默删掉别人已交付的路由，而 typecheck / lint / vitest 全绿。）

---

## 后续待办

- **3 个 `postgres:16-alpine` 容器自 2026-10-18 18:50 起未退出**（`w11311-mig`、
  `pg167v61687`、`pg167v61661`），是硬杀 JVM 后 ryuk 没来得及回收的残留。
  它们持续占用 PG 闸额度（阈值 6，现为 3），也可能是上述 SSL EOF 的诱因。清理属破坏性操作，留给用户决定。
- 本轮只派了 2 条**恢复**车道，未派新车道：`--lanes 6` 的配额仍空 4 位。
  理由是今天已有 4 轮 tick 死于 Token Plan 上限，此时再开新车道大概率同样被中途掐断。
  `plan --dry-run` 显示下一个可排候选是 `4.1.2.3 套装组件映射`（V184，L3 `4.1.2`）。
- `4.1.2.3` 与 `4.3.1.2` 同属刊登域、都会改 `api-proxy.ts`，下一轮若并发派发需注意错开插入锚点。
