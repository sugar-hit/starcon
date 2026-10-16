# batch-13 · 10.1.2.2 渠道比价 / 9.3.1.2 合单（2026-10-16）

集成头 `2df5d49c` → `518cd39e`。本批 2 条车道，L2 一次全量验证通过后 push。

台账：已交付 **168 → 170**，未开发 297 → 295，完成率 **36.6%**。

---

## 模块 10.1.2.2 渠道比价

**这个功能做什么**：运营填入包裹参数（收货国、重量、体积、件数、申报价值、是否带电/液体），系统列出所有可用渠道，并按**成本、时效、成功率**三个维度排序推荐，而不是只挑最便宜的。店铺还能预设渠道优先级，命中时自动选择。

**技术落地**：

- 迁移：`channel/V143__channel_rate_comparison.sql` — 4 张新表（`channel_comparison` / `channel_comparison_line` / `channel_rating_score` / `channel_shop_priority`）、6 个触发器、RLS 策略与 GRANT。**不 ALTER 任何既有表**。
- 端点：`/channel/rate-comparison/**` 9 个形状（capabilities / compare / comparisons 集合与建单 / `{uuid}` 详情 / ratings 读写 / shop-priorities 读写）。
- 页面：`apps/web/src/logistics/rate-comparison/` — 真实工作区，含加载态、空态、错误态。
- 测试：`RateComparisonIntegrationTest` 28 例 + `ChannelScoringEngineTest` 16 例 + 前端 36 例。

**关键设计决策**：

1. **评分引擎是纯函数**（`ChannelScoringEngine`），不碰 DB、不含策略常量、所有权重由调用方传入。理由：多目标排序是这套系统里最容易在补测试时悄悄改坏的部分，隔离成纯函数才能对边界值穷举。
2. **复用 10.1.2.1 的试算结果，不重新计价**。比价消费 `freight_quote` 而不是自己算一遍运价 —— 否则两套计价逻辑必然漂移，且「试算用规则、结算用实际」的口径会被破坏。
3. **触发器用 `DEFERRABLE INITIALLY DEFERRED`，一致性校验挂在行表而非主表**。worker 在写 Java 前先用真实 PostgreSQL 16 验证 DDL，**自查出两个设计错误**：BEFORE 行级触发器看不到写后集合（连续性与选择一致性根本验不了）；一致性校验若挂主表，「先写头后写行」这个服务唯一合法的写入顺序会被判非法。两者都已改为延迟触发器 + 挂在行表。
4. **自动选渠道有硬拦截门**：命中渠道限制（禁限品类、电池液体磁力）的渠道**永远不参与自动选择**，但仍保留在结果表里并显示完整评分与拦截原因。理由：只剔除不解释，运营就看不到「最便宜的为什么没选」，会转而去猜规则。
5. **未交付的上游如实上报，不假装**：`10.6.2.1 时效分析` 未交付，所以渠道评分当前是租户手工维护（`score_source=MANUAL_ENTERED`，枚举位已预留）。`capabilities` 如实返回 `rating_producer.available=false` + ownerModule + 影响面。

## 模块 9.3.1.2 合单

**这个功能做什么**：把同一买家、同一收货地址的多笔订单合并成一个包裹发货，降低运费与包材成本；系统同时识别出**跨店铺**的同买家订单，并保证合并后每个平台订单仍能单独回传物流号。

**技术落地**：

- 迁移：`trade/V144__trade_order_consolidation.sql` — 5 张新表（`order_consolidation_rule` / `order_consolidation` / `order_consolidation_member` / `order_consolidation_writeback_task` / `order_consolidation_event`）、RLS 与 GRANT。**零 ALTER 既有表**，跨车道 DDL 回归面 = 0。
- 端点：`/trade/fulfillment/consolidation` 8 个（capabilities / rule GET+PUT / candidates / consolidations GET+POST / `{uuid}` / `{uuid}/status` / writeback-tasks / `{uuid}/members/{memberUuid}/writeback`）。
- 页面：`apps/web/src/app/orders/fulfillment/consolidation/` — 真实工作区。
- 测试：`OrderConsolidationIntegrationTest` 26 例（含租户隔离三断言 + **跨租户不可合单**）+ 前端 26 例。

**关键设计决策**：

1. **候选锚点按最早订单定，不用随机 tiebreak**。worker 修掉一个真实的确定性缺陷：最初用随机 UUID 打破 `submitted_at` 平手 ⇒ **同一批数据每次刷新给出不同候选集**。改为锚定该地址下最早订单。
2. **延迟触发器不能挂在被自己更新的行上**。原本的一致性延迟触发器监听整行，导致自触发无限循环、**提交时挂死连接**。已改为挂在行表。
3. **`separate_shipping_amount` 不在 CHECK 约束里**。它在 INSERT 时还是 `DEFAULT 0`（由后续触发器计算），写成 CHECK 会**把所有带报价的合并在插头时就拒掉**。已移到计算该值的触发器里。
4. **买家指纹优先用 `customer_party_id`**，不是地址文本。理由：两个不同客户可能共用同一收货点，只按地址匹配会把他们错误合并。
5. **刻意没有 DELETE**：合并单与事件流是履约凭据，删掉等于抹掉「这批货一起发过」的证据。

---

## 本批 L2 暴露并修掉的问题

### 1. `api-proxy` 白名单守卫的提取逻辑有缺口（前端，11 例假红）

9.3.1.2 合单车道**合法地**新增了第二条 `prefix: 'trade/'` 元素，并刻意插在既有那条**之前**（锚点约定：让多车道的插入点被既有条目隔开、避免并入冲突）。

而 5 个以 `readFileSync` 文本刮擦 api-proxy.ts 的守卫只取**第一条** `prefix:` 元素、且扫描 `/^` 时**没有以元素边界收敛**，于是抽到了合单那条窄正则，拿它去断言**全部** trade 路径。

**运行时本来就是对的**：`resolve()` 是逐条 fallthrough（前缀或正则不匹配就 `continue`），新元素不会遮蔽既有规则。已用复刻 `resolve()` 语义的探针实测确认。

⇒ 修的是守卫，不是 `api-proxy.ts`。改元素顺序会推翻车道的刻意锚点选择，并把并入冲突风险加回来。

守卫改为**收集全部同前缀元素**，判定「放行」= 任一条匹配，与运行时同构。同类隐患在 `warehouse/`、`identity/` 守卫里也存在（当时是潜伏的），一并修掉。

**六个文件的 `expect` / `it()` 计数修改前后逐个相等，未引入 skip/only/todo** —— 变绿是因为守卫修对了，不是因为断言被放松。另做了反向对照实验（回退改动 → 11 红；恢复 → 253 绿）与对真实 `proxy` 处理器的 39 路径同构探针（0 分歧）。

### 2. 守卫把「已修复的缺陷」写死成预期（前端，1 例）

`api-proxy-union.test.ts` 有一段 `CH10_FREIGHT_KNOWN_BROKEN`，断言 `freight/quotes/{uuid}` 返回 404，注释写明「10.1.2.1 的既有缺陷」。10.1.2.2 顺手把这个洞补上了（该路径之前**在浏览器里静默 404**，而端点存在、契约里有、typecheck/lint/vitest 全绿），现在返回 200 → 断言变假红。已翻转为回归守卫并保留历史原因。

### 3. 任务书前提被 worker 推翻（记账）

本批给 9.3.1.2 的任务书写了「同 L3 兄弟 **9.3.1.1 拆单已交付**，产出 `fulfillment_routing_*`」——**这是错的**。实查：台账 317 行 `9.3.1.1 = 未开发`，`V92` 文件头写明属 9.3.2.2，全仓无拆单迁移。

worker 验出前提不成立后没有照着改，而是查清真正上游（V99/V119 的 6 张表全部存在）、判断拆单是兄弟非上游、继续开发，并在报告中明确上报该纠正。**修正方向以 worker 的实测为准。**

另：任务书给的代理白名单路径 `apps/web/src/app/api/proxy/api-proxy.ts` 不存在，真实路径是 `apps/web/src/lib/api-proxy.ts`，两条车道都自行找到了真文件。

### 4. trade 模块存在间歇性 flake（后端，已取证非本批引入）

首轮全量 `mvnw verify` 在 `OrderChangeCancelIntegrationTest` 红 4 例（1 failure + 3 errors）。取证：

| 实验 | 结果 |
| --- | --- |
| 基线 `2df5d49c` 的该类 | 40 / 0 绿 |
| 单跑该类 | 40 / 0 绿 |
| 与新增合单类并跑 | 66 / 0 绿 |
| 全 trade 模块 `-am test` | **639 / 0 绿** |
| 全反应堆重跑 `mvnw verify` | **BUILD SUCCESS，trade 639 / 0** |

且 `git diff feat/9-3-1-2 <合并头> -- backend/services/trade/` **完全为空** —— 生产代码在车道分支与合并头上逐字节相同。

失败的用例全是**挂钟窗口类**（`deadline_hours` + `effective_from = OffsetDateTime.now()`），且两个测试类的 `@BeforeEach` 都对同一批共享表做 `truncate sales_order cascade`。**结论：测试隔离/时序敏感，不是本批的回归。** 未在本批修，记为待办（见下）。

---

## L2 批次闸结果（全绿）

| 闸 | 结果 |
| --- | --- |
| `mvnw verify` | BUILD SUCCESS，6 服务 **4109 例 0 失败** |
| `pnpm test` | **3420 / 3420**，185 / 185 文件 |
| `pnpm typecheck` | 3 / 3 |
| `pnpm lint` | 3 / 3 |
| Next production build | exit 0，`/logistics/rate-comparison`、`/orders/fulfillment/consolidation` 均在产物中 |
| `sync --check` | ✓ 台账与蓝图一致 |

静态预检（互相破坏形状）：V143 / V144 均为**纯新建表**，`ADD COLUMN` 无 DEFAULT、`SET NOT NULL`、`ADD CONSTRAINT CHECK` 三类命中均为 0 ⇒ 跨车道 DDL 回归面为 0。

## 本批提交

| commit | 内容 |
| --- | --- |
| `3a4a1710` | feat(10.1.2.2) 渠道比价 |
| `83a26524` | feat(9.3.1.2) 合单 |
| `86c3b562` | fix(test) api-proxy 守卫改为收集全部同前缀元素 |
| `b5fe70c7` / `6ca92f11` | merge 两条车道 |
| `72a66d0d` | merge 守卫修复 |
| `518cd39e` | chore(progress) 台账汇总 168 → 170 |

## 后续待办

1. **并发配额三处不一致**（`.dev-agent/PIPELINE.local.md` 的 `RUNNING_QUOTA=2` / cron 任务书的 `running >= 4` / `PIPELINE.md` 的「每条车道都派」）。本批按最严值派 2 条，`4.2.3.1`（catalog V145）与 `14.1.2.2`（channel V146）顺延下批。需用户定值。
2. **trade 测试隔离**：两个测试类对同一批共享表做 `truncate ... cascade`，且平台可改窗口用例依赖墙上时钟。建议把 fixture 收敛到按租户隔离的 schema，并把窗口基准时间钉死。
3. **全仓 U+FFFD 扫描**：本批修复守卫时发现 `api-proxy.test.ts` 里两处**已提交的**替换字符（本该是「跳过」），已随改动修复；其余文件未扫。
4. **10.6.2.1 时效分析 / 9.3.1.1 拆单** 未交付：前者是渠道评分的理想生产者，后者落地后需复核与合单是否共用「履约切分」入口约定。
5. **跨店铺暂不可区分**：`sales_order` 无 `shop_code` 列（D5 未交付）。已在 `capabilities.platformShopResolved=false` 如实报出，D5 落地后补列即可，表结构不用改。
6. **契约可空性缺口**：`maxWindowHours` / `consolidatedShippingAmount` / `ruleBlockedReason` 在 codegen 里生成成非空而后端返回 null，前端用显式 cast 绕开并注明。若 codegen 支持 `nullable` 应从源头修。
