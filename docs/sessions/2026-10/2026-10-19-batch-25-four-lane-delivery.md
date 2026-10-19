# 批次 25 · 4 条车道交付 + 1 个测试缺陷修复（2026-10-19 11:00 tick）

> 本轮起点：上一轮会话 09:25:55 以 `42212`（Token Plan 上限）暴毙，3 条车道孤儿化。
> 本轮把它们全部收口，并解决了一处自 08:00 起卡了 3 轮的并入冲突。

## 进度

| 时刻 | 已交付 | 未开发 |
| --- | --- | --- |
| 开班（11:00） | 200 | 265 |
| 收班 | **204** | **261** |

## 本批交付的 4 个模块

### 模块 4.1.1.2 Listing 详情

**这个功能做什么**：从 Listing 列表点进去，能看到这条 listing 的全景 —— 平台当前状态、内部草稿、
发布历史、平台操作记录，以及**平台和内部逐字段的差异对照**。差异对照是核心价值：
内部草稿改了但没同步到平台的地方，会被显式高亮标出来，而不是让你自己去两个页面比对。

**技术落地**：

- 迁移：`V175__listing_detail.sql` — listing 详情快照 / 内容对照 / 发布历史 / 平台操作记录；
  `tenant_id NOT NULL` + FORCE RLS
- 端点：`channel` 服务 `/channel/listing-details/**`（capabilities、`{uuid}`、`/diff`、`/content`、
  `/publish-history`、`/platform-operations`、`/content-snapshots`、`/publish-records`）
- 页面：`/listings/[id]` — `apps/web/src/components/listing-detail/`（model + workspace）
- 测试：`ListingDetailIntegrationTest` + `listing-detail-model.test.ts` + `listing-detail-allowlist.test.ts`

**关键设计决策**：

- **新增独立的 `listing-details/` 前缀，而不是在 `listings/{id}/...` 下开子资源。**
  4.1.1.1 交付时把 `/channel/listings/{id}/diff` 显式登记成「不放行」，也在拒绝清单里钉死了
  一批形状 —— 那份清单是别的车道的成果，改它等于踩别人的地。而「列表」与「详情全景」本来就是
  两种资源：前者是筛选结果的一行，后者是聚合了多份上游事实的全景视图，字段集合都不一样。
- 命名空间独立于 `/channel/listings`，所以放行 `listing-details/{uuid}/lifecycle` 不会与
  `listings` 那条的拒绝清单打架。

### 模块 11.2.2.2 实收分配

**这个功能做什么**：买家把货寄回来了，系统按实收件数把退货金额分摊到订单行上。
超收的部分**按实际退回**记，不假装全收；少收的部分如实记账。

**技术落地**：

- 迁移：`V178__trade_return_receipt_allocation.sql` — 实收分配单 + 分配行；
  `tenant_id NOT NULL` + FORCE RLS，幂等键由服务层派生
- 端点：`trade` 服务 `/trade/receipt-allocations/**`（capabilities、集合读、详情、
  reconcile 对账、reverse 撤销）
- 页面：`/aftersales/receipt-allocations` — 含对账处置
- 测试：`ReturnReceiptAllocationIntegrationTest` 28 例 + `ReturnReceiptAllocationTenancyIntegrationTest`

**关键设计决策**：

- **超期退回判定比较的是「收货时刻」与「寄回时限」，不是分配时刻** ——
  人在 11 点做的分配，不该改写 9 点那次收货的事实。
- **分摊数量超过实收数一律拒绝，不静默截断**。截断成实收数看起来更「友好」，
  但它把「实收 2 件却要退 3 件」这个差异掩盖掉了，超收的钱就再也没人追。
- `return_by_at` 取**快照**：授权单后续可能被关闭，但「当时批的时候约定了什么时候寄回」
  必须用当时的事实回答。
- 见下方「时间相关测试缺陷」一节 —— 本模块还带出一个真实缺陷，本轮已修。

### 模块 4.1.2.1 映射管理

**这个功能做什么**：平台 SKU 和内部 SKU 的对应关系管理。**绝不按名称猜 SKU** ——
没建立映射的进「待映射」队列等人确认，而不是按名字自动对上（名字对上的那批往往正好是错的）。

**技术落地**（由上一轮暴毙的孤儿车道**接管**完成）：

- 迁移：`V176__listing_mapping.sql` — 4 表 / 9 索引 + 部分唯一索引 / 6 触发器；
  RLS isolation + data_scope / GRANT / `pg_trgm`；**纯新建、零 ALTER 既有表**
- 端点：`channel` 服务 `/channel/listing-mappings/**` 10 个（集合读、详情、建、删、
  capabilities、filter-options、批量候选、候选判定、unmapped、resolve、by-sku）
- 页面：`/listings/mappings` — `apps/web/src/components/listing-mapping/`
- 测试：`ListingMappingIntegrationTest` 16 例（含租户隔离三断言）+ 前端 12 + 6 例

**关键设计决策**：

- 接管的 5 个既有文件**全部保留**，只做了必要修正 —— 上一个 worker 的代码质量高于平均。
- 车道分支基线前移：接手期间集成头合入了 4.1.1.2 与 4.2.1.2，且 `api-proxy.ts` 的插入锚点
  附近已被占了三个位置。不前移则并入必撞冲突。
- 契约只提交 channel 两个文件：codegen 一次生成 6 个服务，trade 侧差异是既有合并产物，
  还原以免干扰并行的 trade 车道。
- **未交付段显式声明**（不是遗漏）：`4.1.2.2` 的「按版本回溯受影响订单」需 09 订单真实来源、
  `backflowDelivered=false`（2.4.2.3 未交付）、套装组件明细归 4.1.2.3、手填 `internalSkuId`
  无跨服务存在性强校验 —— 四条都在 capabilities 里 `available=false` + ownerModule + 影响。

### 模块 11.2.3.1 质检与判定

**这个功能做什么**：退货入库后的质检流程 —— 建类目、建模板、看待检队列、
提交前预览判定结果、开质检单、录逐项结果、给出判定结论。**残值由公式算出**，
不是人随手填。

**技术落地**（同样由孤儿车道**接管**完成）：

- 迁移：`V179__trade_return_quality_inspection.sql` — 8 张新表，`tenant_id NOT NULL` + FORCE RLS，
  **零 ALTER、零跨车道外键**
- 端点：`trade` 服务 `/trade/return-quality/**` 15 个
- 页面：`/aftersales/return-quality` — 待检队列为第一屏，判定卡片显示可判/不可判与理由，
  残值明细只读
- 测试：`ReturnQualityIntegrationTest` 19 + `ReturnQualityTenancyIntegrationTest` 4 = **23 例**

**关键设计决策**：

- 接管的 6 个文件里 4 个直接采用、2 个改名修正。
- **发现并修掉一个会静默覆盖别人成果的契约冲突**：`FulfillmentQualityModels` 已经有一个
  `QualityEventRow`（9.x：`seq/kind/summary/orderId/alertId`）。两个同名 record 在同一个
  Java 包里**不冲突**，但 OpenAPI 契约按简单名拍平，codegen 让新的**静默覆盖**旧的、
  **不报任何错**，`fulfillment-quality-workspace.tsx` 直接编译失败。
  上一轮只对照了 `ReturnReceipt*` 因此漏掉。改名 `ReturnQualityEventRow`。
- **硬规则写在 DB 触发器里，不只写在服务层**。
- 跨表一致性在写入时一次性校验，**刻意不加外键** —— 把跨车道 DDL 回归降到零。
- **检查方法**：不要 diff 类型**名字集合**（那是 888 个既有名字的交集，没有信号），
  要看 `git diff` 里的 **`^-export type` 删除行** —— 那才是「某个定义被改写」的直接证据。

## 本轮修掉的真实缺陷：时间相关的测试（11.2.2.2）

**症状**：L2 在 11:04 红在 `ReturnReceiptAllocationIntegrationTest:586`，`expected: 4 but was: 3`。

**根因**（测试构造问题，不是服务算法问题）：

```java
OffsetDateTime returnByAt  = now8().plusDays(10);
OffsetDateTime receivedAt = returnByAt.plusDays(4).withHour(9).withMinute(30);  // ← 病根
assertThat(d.head().daysOverdue()).isEqualTo(4);
```

`withHour(9).withMinute(30)` 改写的是**已推进结果的时刻**，于是真实偏移变成
「4 天 − (当前时刻 − 09:30)」而非 4 天整。服务端 `Duration.toDays()` 向下取整，
本地 09:30 之后一律算成 3。

**全天扫描取证**：修前 1440 个分钟点里有 **869 个**不等于 4（09:31 起变 3，即 60.3% 的时段必红），
修后 **0 个**。

**为什么它能一路混进集成头**：这个测试多半是在早上开发、早上绿的，
于是每一轮 L2 都跑在 09:30 之前，看不出来。直到本轮 11:04 的 L2 才炸 ——
**看起来像「新车道弄坏了旧模块」，其实没人碰过它**。

**否决的方案**（都记在 `2026-10-19-fix-overdue-test-time-dependence.md`）：
把断言放宽成区间、把服务改成 ceil 或日历日差、冻结 fixture 时间 ——
共同点是**为一个测试 fixture 缺陷牺牲测试保真度或生产正确性**。

## 并入冲突：三处同款形状

4.1.1.2、4.2.1.2（上一轮）、11.2.3.1 都是同一个形状：

> 两侧各自**新增一条独立的 `services[]` 元素**，插入锚点相同 ⇒ git 必然判冲突。
> 但**没有任何取舍空间** —— 各元素的顶层字面量互不相交。

并入前一律先做**以 base 为裁判的三方取证**（`base = merge-base`）：

| 模块 | 车道新增 | 集成头新增 | 丢失 | 凭空发明 | resolved 净增 |
| --- | --- | --- | --- | --- | --- |
| 4.1.1.2 | 1 | 5 | 0 | 0 | +1 |
| 11.2.3.1 | 1 | 4 | 0 | 0 | +1 |

⚠️ **绝对比较必然误报**。车��侧那份大 alternation 缺 `webhook-dedup`，那是**分支基线旧**，
不是删除动作；不拿 base 当裁判就会把它读成「车道删了东西」。

⚠️ **逐行比对时也要问「这行是车道改的还是集成头改的」**，否则会把集成分支的新鲜度
误算成车道的破坏。

⚠️ `merge-ready` 撞到冲突会**整体回退 merge**（不留 MERGE_HEAD），`lanes.json` 记 `conflict`。
解冲突要自己显式 `git merge --no-ff --no-commit`，解完 commit，下一次 `merge-ready`
会按「已并入」补记为 `merged`。

⚠️ `merge-ready` 的守卫是 `git status --porcelain`，**台账总览计数也算脏**。
本轮两次被它拒（`module-progress.md` 的 200→202、202→204 汇总刷新），
需要先把台账那个 commit 掉再跑。

## 本轮踩到的方法论坑（已写进 memory）

**L2 与车道 L1 并发会伪造出成片的假红。** 本轮 L2 跑的时候两条接管车道正在各自 worktree 里
跑 Testcontainers + surefire，结果 `B2BOrderIntegrationTest` **整整 32 例**报 `seed()` 里 `503`，
看起来像跨车道互相破坏。隔离复跑两个类立刻分家：

```
./mvnw -pl services/trade -am test -Dtest='B2BOrderIntegrationTest,ReturnReceiptAllocationIntegrationTest'
→ Tests run: 60, Failures: 1   （32 例假红消失，只剩 1 个真缺陷）
```

⇒ **判「L2 红了是谁的锅」先按失败形状分堆**：成片同形的 `503`/连接拒绝/`seed()` 失败先当
环境争抢，隔离复跑一次再下结论；**孤立的断言值不符**才是回归。这比直接 `git bisect` 便宜 ——
在假红上二分只会得到无意义的结论。

⚠️ 组批协议说「发热与 lanes 无关」，那说的是**热量**，没说**正确性**。
下轮应把 L2 排在车道测试之后，或明确记录「本轮 L2 与 N 条车道并发」。

## 后续待办

1. **`merge-union` 应给 `services[]` 元素加结构化求并** —— 本轮三处同款冲突都是它该自动合掉的。
   （协议层建议，未自行改。）
2. **`lanes.json` 应记录 owner session** —— 心跳无法判活，本轮又一次靠 `session get` 才坐实暴毙。
3. **既有隐患未修**：`ListingIntegrationTest` 的 `AMAZON` 常量 `1111…-111` 被 V1 播种成了 ETSY，
   `on conflict do nothing` 静默失效。根因仍在。
4. **11.2.3.1 的台账行阶段是 D4**，与蓝图的 D7 不符；worker 按「只改状态/证据」纪律未动阶段列，
   需要在生成侧修。
5. **4 个孤儿 PG 容器**（`w11311-mig`、`pg167v61687`、`pg167v61661` 等）非本轮产生，未擅自清理。
