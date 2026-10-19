# 4.5.2.1 跟卖监控（D5 阶段，L3 锚点 4.5.2）

> 车道 `feat/4-5-2-1`，worktree `.worktrees/4.5.2.1`，基线 `integration/module-dev` 的 `9f0fd1d7`。
> 兄弟模块 4.5.1.1（Listing 表现）已交付，本模块**照抄它的写法**（真分页 / `capabilities` 能力声明 /
> 筛选词表 / 比率不落表一律 SQL 现算、分母为 0 返回 null），但**不复用它的表** —— 跟卖记录是本模块自己的新表。

## 决策摘要

蓝图 §4.5.2.1 只有四行，成立的与否全在一句加粗上：

> **跟卖数据只能作为参考**，处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒。

所以本模块的第一件事不是写判定，而是**先把「只发现与提醒」变成结构**：

1. **表里没有任何处置状态**。`listing_hijacker_alert.status` 只有 `OPEN` / `ACKNOWLEDGED`
   （内部「已阅」），没有 `complaint_id` / `ticket_no` / 「已举报」列。
2. **服务层没有任何投诉 / 举报 / 申诉端点**，api-proxy 白名单里**刻意不放行**这类形状 ——
   放行一个后端不存在的映射比 404 更糟：界面会以为「已交付」。
3. **每个响应都带 `dataSource='PLATFORM_SCRAPED'` 与 `sourceNote` / `actionsNote`** ——
   蓝图加粗句因此是**契约级**的硬要求，不是页面上的装饰。
4. **预警是记录不是通知**。本模块不主动发通知（1 工作台预警未交付），
   在能力表里 `available=false` + `ownerModule` + `impact` 显式声明 ——
   在这里伪造一个「已通知」字段比不通知更危险（运营会以为通知已经发出去了）。

三张表：`listing_hijacker_watch`（监控对象，配置）/ `listing_hijacker_record`（跟卖记录，事实）/
`listing_hijacker_alert`（预警记录，判定结论）。分成三张是因为三者的**生命周期不同**：
配置可暂停可删、事实只追加、结论一旦「已阅」就不可再改（DB 触发器钉着）。

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 跟卖者的「所在平台」 | **不落列**，由 `listing → shop → platform` 派生 | 跟卖是同一平台上的事。冗余一列就会出现「记录说在 A 平台、被监控商品在 B 平台」这种自相矛盾的数据 —— 而跟卖记录的正确性恰恰是本模块最要紧的事（它驱动预警与对比视图）。响应里平台码一个不少，但**只存一份答案** |
| 己方其他店铺跟卖怎么认 | **算出来的**：按跟卖者的**平台侧店铺标识**匹配本租户 `shop.platform_shop_id` | 调用方不能直接声明这个性质 —— 否则一个抓取适配器报错了类型，整条数据的性质就错了。命中即 `OWN_SHOP`（并**一律**判为异常跟卖：自家店铺跟自家 listing 抢生意是渠道冲突） |
| 自家参考售价从哪来 | **租户人工维护的参考值**（`reference_price`） | 实测集成头**卷 05 定价 0/19 未交付**，全库不存在任何平台售价事实表。建一个价格影子列 = 假装有第二个价格事实来源，05 上线后两个数字迟早对不上。参考价不给 ⇒ 异常跟卖的价格差判定**不生效**（只有恶意压价阈值那条还能命中） |
| 阈值不给时怎么办 | **不判定**（不猜一个默认门槛） | 猜出来的门槛会凭空造出预警。阈值是**配置**（运营自己定的），判定结果才是结论 |
| 恶意压价与异常跟卖同时命中 | 类型取**更严重的恶意压价**，判定依据里写明全部命中的规则 | 一条记录一条预警（`UNIQUE(tenant_id, record_id)`）。两条规则都命中时不能只报一条 —— 依据写全，半年后才看得懂为什么报这一条 |
| 价格差比例 | **一律现算**（SQL），**分母为 0 时 null** | 落了表就会与事实漂移（改一次参考价，旧比例列不会跟着变）。报 0 会被读成「跟卖价和自家价一样」，那是结论不是缺口 |
| 预警的参考价 | 存**判定时刻的快照**（`listing_hijacker_alert.reference_price`） | 事后改了参考价，这条预警的依据仍然是当时那个数。响应里的 `referencePrice` 是快照、不是当前值 —— 字段注释写明了 |
| 跟卖记录的唯一性 | `UNIQUE(tenant_id, watch_id, hijacker_seller_id, observed_at)` + 批次键另存 | 两个不同的问题：**记录级**（同一条跟卖被重复观察）靠唯一约束 + 跳过并写明原因；**批次级**（平台重投同一批）靠 `batch_idempotency_key`，由服务层按 `(watch, observed_at, key)` 精确判定后整批跳过。混成一个键就分不清「重投」与「新一轮抓取」 |
| 幂等重投的回执 | `replayed=true` + `appliedCount=0` | 页面必须显示出来，否则运营会以为平台又同步了一遍 |
| 跟卖者就是被监控 listing 自己的店铺 | **跳过并写明原因**（不是落库） | 那不是跟卖，是自家 listing 本身。把它记成跟卖，运营会对着自己的商品去查「谁在跟卖我」 |
| 对比视图的粒度 | **一个跟卖者一行**（`GROUP BY hijacker_seller_id`），服务端聚合 + 真分页 | 「跟卖者」是运营的思考单位（"这个卖家到底在跟我们几次"）。前端拼表 + 自己算比例 = 两套口径 |
| 对比视图的排序 | **压得最狠的排最前**（价格差比例降序） | 跟卖监控最该先看到的是「谁压得最狠」，不是「谁最新出现」 |
| 监控对象的更新语义 | `PUT` = **全量替换**（字段不给 = 清空 / 复位） | 半更新语义（null = 不改）会让人**清不掉**一个阈值。加 `clearXxx` 布尔旗标又啰嗦 |
| 监控对象能不能换商品 | **不能**（给了不同 listingId ⇒ 422） | 换了会让历史跟卖记录挂到别的 ASIN 上。要换目标就新建一条 |
| 暂停的语义 | 暂停 = **拒收**新的跟卖信息（422），但**历史不删** | 暂停是「不收」，不是「收了就藏起来」。历史事实不因为配置变了就消失 |
| 删除监控对象 | 连带删掉记录与预警，回执**写明删了几条** | 删掉的是事实，调用方必须知道自己销毁了多少东西 |
| 权限码 | 复用 `channel.operation.*` | identity 零改动，与 4.1.1.1 / 4.3.2.2 / 4.4.2.x 同族 |
| 端点前缀 | `/channel/listing-hijackers`（**独立前缀**） | 4.1.1.1 已把 `/channel/listings/{uuid}/...` 登记成「不放行」，在同一棵路径树上开子资源要同时改别人已交付的白名单与拒绝清单 |
| 路径里的英文 | **`hijackers`**（跟卖 = 抢 listing 的人） | 与既有 `listing-claims`（跨店搬家）、`listing-compliance-precheck`（扫内容）、`listing-keyword-rankings`（看排名，未交付）**都不混用** |

## 未交付的上游（如实标注，不假装有）

| 段 | 状态 | 本模块的处理方式 |
| --- | --- | --- |
| **16 合规（品牌保护）的处置动作** | 整卷未交付 | `capabilities` 里 `brand_protection_enforcement.available=false` + `ownerModule=16 合规与风险`。**本模块只负责发现与提醒**；投诉/举报在平台侧做完之后，由 16 侧来消费本模块的预警记录并跟进 |
| **1 工作台预警的通知** | 整卷未交付 | `workbench_alert_notification.available=false` + `ownerModule=01 工作台与门户`。本模块产出的是**预警记录**，**不主动发通知** |
| **02 的抓取调度器** | 归 02 | `real_hijack_crawl_scheduler.available=false`。本模块只提供**入库端点**（通道 `WEBHOOK` / `POLL` / `API` 都记下来），平台限流与店铺配额由 02 统一裁决，不自建轮询 |
| **05 平台权威售价** | 整卷未交付 | `authoritative_own_price.available=false`。系统内不存在平台售价事实表 ⇒ 对比视图里的「自家参考售价」是**人工维护的参考值**，不是平台权威价 |

## 文件清单

**后端（`backend/services/channel/`）**

- `src/main/resources/db/migration/V206__listing_hijacker_monitor.sql` —— 三张新表 + 索引 + 4 个触发器 + RLS（ENABLE **与** FORCE）+ GRANT；**零 ALTER 任何既有表**
- `src/main/java/com/starcon/channel/ListingHijackerModels.java` —— 传输模型（15 个具名 record）
- `src/main/java/com/starcon/channel/ListingHijackerService.java` —— jOOQ 显式 SQL + 判定规则 + 对比视图聚合
- `src/main/java/com/starcon/channel/ListingHijackerController.java` —— 8 组端点
- `src/test/java/com/starcon/channel/ListingHijackerIntegrationTest.java` —— 23 例
- `src/test/java/com/starcon/channel/ListingHijackerTenancyIntegrationTest.java` —— 4 例（**多租户三断言**）

**前端（`apps/web/`）**

- `src/components/listing-hijacker/listing-hijacker-model.ts` —— 类型 + 取数 + 纯函数
- `src/components/listing-hijacker/listing-hijacker-model.test.ts` —— 18 例
- `src/components/listing-hijacker/listing-hijacker-workspace.tsx` —— 真实工作区（监控对象 / 跟卖记录 / 预警 / 对比视图 + 三态）
- `src/app/listings/hijackers/page.tsx` —— 页面路由（**新建**，不登记 task-pages）
- `src/app/api/channel/listing-hijackers-allowlist.test.ts` —— 4 例（放行 + 拒绝 + 不误伤兄弟车道）
- `src/lib/api-proxy.ts` —— **只新增一条** `listing-hijackers/` 元素（插在 `listing-compliance-precheck` 之后、`listing-claims` 之前）

**契约与台账**

- `packages/api-contracts/src/channel.openapi.json` / `src/contracts/channel.ts` —— **codegen 重生成**（未手改任何生成物；其余五个服务的契约逐字节不变）
- `docs/product/capability-blueprint/module-progress.md` —— 只改本模块那一行的「状态」「证据」两格

## 验证

```
cd backend && ./mvnw -q compile                                  # 退出 0
cd backend && ./mvnw -pl services/channel -am test \
    -Dtest='ListingHijacker*' -Dsurefire.failIfNoSpecifiedTests=false \
    -DfailIfNoSpecifiedTests=false                              # 27 例 0 失败 0 错误 BUILD SUCCESS
pnpm typecheck                                                  # 3/3 通过
pnpm --filter @starcon/web lint                                 # 通过（本车道零告警）
pnpm vitest run apps/web/src/components/listing-hijacker         # 18 例全绿
pnpm vitest run apps/web/src/app/api                            # 91 文件 553 例全绿（含全量 allowlist 回归）
```

## 后续待办（不属本车道）

1. **16 合规品牌保护交付后**，由它来消费本模块的预警记录并跟进 —— 本模块的预警里**没有**任何处置状态，
   16 侧落地时可能需要在本模块补一个「外部处置单号」列（那时是一次显式的 ALTER，不是现在预埋）。
2. **1 工作台预警交付后**，由它来读本模块的 `listing_hijacker_alert`（跨服务走授权 API，不跨库读表）。
3. **05 定价交付后**，`reference_price` 的语义要收敛（人工维护的参考值 → 平台权威售价或与之对账），
   届时对比视图的「自家参考售价」标题也要改 —— 现在页面上写的是「人工维护的参考值」。
4. **02 的抓取调度器交付后**，本模块的 `source` / `ingest_channel` 会真正出现 `SCRAPE` / `POLL` 的数据。
5. **4.5.2.2 上新与变更监控**（同 L3 的兄弟）未开发，它与本模块的**监控对象**语义相近
   （都是「盯一个 ASIN」），届时可能需要复核两者的配置是否该合并到一张表。
