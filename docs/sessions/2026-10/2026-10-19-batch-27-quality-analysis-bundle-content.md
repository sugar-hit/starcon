# 批次 27 · 质量分析 / 套装组件映射 / 内容回写

> 时间：2026-10-19 20:00–21:00 轮次（cron `0 8-20`）
> 集成头：`integration/module-dev`，本批合并后 head `894d8ab3`（合并前 `5531936d`）
> 状态：**3 条车道已并入集成头；L2 批次闸因环境争抢变红，内容已用隔离复跑证明无回归；按协议本轮不 push，顺延到 08:00 干净复跑后统一 push。**

---

## 本批回收

`lane.mjs merge-ready` 干净并入 3 条 ready 车道，无冲突：

| 模块 | 名称 | 迁移版本 | 车道分支 | 合并 commit |
| --- | --- | --- | --- | --- |
| 11.5.2.1 | 质量分析 | V185 | `feat/11-5-2-1` | `94fd678f` |
| 4.1.2.3 | 套装组件映射 | V186 | `feat/4-1-2-3` | `ab0acb74` |
| 4.3.2.1 | 内容回写 | V187 | `feat/4-3-2-1` | `894d8ab3` |

并入后 `sync.mjs` 台账：已交付 **207 → 210**，未开发 258 → 255。

**共享白名单校验（`apps/web/src/lib/api-proxy.ts`）**：合并驱动对 api-proxy 走了「行级兜底并集」，
按三方比较（base `5531936d` 为裁判）复核 —— base 42 条 → 合并后 45 条，
三条车道各新增 1 条且**零丢失、零凭空**。台账 465 行、无重复 ID、`sync --check` 绿。

**静态预检**（本批新迁移 V185/V186/V187）：`ADD COLUMN` 无 DEFAULT = 0、`SET NOT NULL` = 0、
`ADD CONSTRAINT CHECK` = 0 —— 三种「已知互相破坏形状」零命中。

---

## 模块 11.5.2.1 质量分析

**这个功能做什么**：售后侧的质量分析台账。运营能看到缺陷类型分布、批次质量趋势、
供应商对比、照片聚类、开箱率与质量成本，并能登记缺陷观察、换版成本参数、维护供应商引用。

**技术落地**：

- 迁移：`V185__trade_quality_analysis.sql` — **四张新表、零 ALTER 零 DROP 既有表**：
  `quality_defect_observation`（缺陷观察，开箱率的唯一来源）/
  `quality_cost_parameter`（成本参数，每币种一份生效版本）/
  `quality_supplier_ref`（供应商引用镜像）/
  `quality_analysis_event`（留痕 append-only）。
  四表 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS isolation + GRANT。
  观察表用**部分唯一索引** `WHERE observation_status='RECORDED'`（表级 UNIQUE 会让「作废后重记」不可能），
  成本参数表收回 DELETE（同步器写回来会复活）。**七条守卫钉在 DB 层**（服务层与 DB 各判一次）。
- 端点：`/trade/quality-analysis/**` 共 17 个 —— capabilities / overview / defect-distribution /
  lot-trend / supplier-comparison / photo-clusters / open-box-rate / quality-cost / improvements（9 只读）
  + defect-observations（GET/POST/void）、cost-parameters（GET/POST/supersede）、
  supplier-refs（GET/POST/status）。
- 页面：`/aftersales/quality-analysis`（`quality-analysis-workspace.tsx` + `quality-analysis-model.ts`），
  api-proxy **新增一条独立 services[] 元素**（不并进既有 trade 长正则）。
- 测试：`QualityAnalysisIntegrationTest` 22 例 + `QualityAnalysisTenancyIntegrationTest` 3 例，
  实跑 Testcontainers PostgreSQL 16（连接池压到 1）。

**关键设计决策**：

- 质量成本**绝不合成一个来源的总成本**：损耗 = 原值 − 残值（实测）、
  退货物流（按参数估算 × 全部实收件数 —— 只按缺陷件算等于让没问题的件白花的钱从报表消失）、
  差评影响（按参数估算 × 已登记缺陷件数）。**参数未登记时标 `NOT_REGISTERED`、金额为 null 而不是 0**，
  并排除在合计外 —— 按 0 算出来的总成本会被读成「质量没花钱」，而偏小的质量成本正是改进动力不足的方向。
  混合了估算的合计**依据等级自动降级**，绝不冒充全实测。
- **开箱率必须与覆盖率并排**：分母只取已登记观察，覆盖率 = 已登记观察数 ÷ 期间内 FAIL 检项数。
  覆盖率 40% 时只代表「已登记的那 40%」，剩下的沉默不能被读成「没开箱问题」。
  **签收时发现刻意不计入分子**（货到买家手上有没有拆开验过没有任何证据）。
- 趋势按 `date_trunc` **UTC** 分桶（用会话时区的话换一条连接就换一套分桶）。
- 照片聚类按 `content_sha256` **完全相同**分组 —— 本仓无图像库、无模型、无第三方视觉服务接入点，
  **响应里没有任何「相似度」数字**，编一个 0.87 就是冒充图像识别能力。
- 跨服务边界：输入句两项上游都在 trade 服务（同库只读聚合，不经跨服务调用）；
  供应商主数据在 catalog 独立库 ⇒ **不 join、不建视图、不建跨库 FK**，改用 trade 侧镜像表
  （沿用 11.1.2.2 处理 `platform_enum_mapping` 的既有先例）。
- 刻意不做：供应商评级（6.1.1.3 未交付）、供应商索赔（6.4.2.2 未交付）、
  图像相似度聚类、**造一个 `NEGATIVE_REVIEW` 发现环节**（全库没有评价表）。
  契约里 `CostItem.amount` / `basisQty` / `unitPrice` 刻意标成 `number | null` ——
  写成非空 number，前端就只会被迫把「算不出来」渲染成 0。

---

## 模块 4.1.2.3 套装组件映射

**这个功能做什么**：管理「平台变体的套装版本选择」与「逐组件 SKU 与数量」的映射记录，
并能校验某套装在指定仓库**整体是否可售**（组件数为 0，或任一组件可售量 < 每件用量 ⇒ 整套不可售）。

**技术落地**：

- 迁移：`V186__listing_bundle_component.sql` — **纯新建两张表、零 ALTER 任何既有表**：
  `listing_bundle_binding`（平台变体当前指向哪个内部套装版本 + 历史）/
  `listing_bundle_component`（逐组件 SKU 与数量）。
  `UNIQUE(tenant_id,mapping_id) WHERE effective_to IS NULL` 部分唯一索引挡并发，
  触发器 `guard_listing_bundle_component_frozen` 拒绝改写已退役绑定的组件（改组件的唯一办法是换版）。
- 端点：`/channel/bundle-components/**` 共 9 个 —— GET /capabilities、GET /filter-options、
  GET /bindings、POST /bindings、GET /bindings/{uuid}、GET /bindings/{uuid}/sellable-check、
  GET /components、POST /components、PUT /components/{uuid}、DELETE /components/{uuid}。
- 页面：`/listings/bundle-components`（筛选 → 绑定列表 → 组件明细登记 → 可售性校验），
  加载态 / 空态 / 错误态齐全，**空态区分「还没有绑定」「还没选绑定」「这一版没有组件」三种**。
- 测试：`ListingBundleComponentIntegrationTest` 15 例（含租户隔离三断言 + timestamptz 往返断言时刻本身）
  + `bundle-component-model.test.ts` 19 例 + `bundle-component-allowlist.test.ts` 6 例。

**关键设计决策**：

- **组件表归本模块而不是复用 catalog `bundle_component`**：分界线是**两个问题不是两个真相** ——
  catalog 答「这个内部套装由哪些 SKU 组成」（跟着内部商品走），
  本表答「这个平台变体按哪一版、每卖 1 件扣几件」（刊登侧绑定）。
  两者**允许不同**才是本表存在的理由（同一套装 A 店 1:1、B 店 2:1）。
  因此本表**引用** `bundle_version_id` 而**不复制**构成 —— 构成快照是 09 订单的冻结职责，复制就有两个真相。
- **「缺任一组件时整个套装不可售」只有一半落得到 DB**：数量那半句是硬约束；
  可售那半句的真值在 **warehouse** 库里（在手−预占−安全缓冲），跨服务表达不成 CHECK ——
  硬写一个无跨库视图的假约束只会造成「库里说可售、实际不可售」的假保证。
  所以它落在服务层 `sellable-check`，且是**整体合取**。
- **「查不到」与「确实不可售」在类型上分得开**：上游 warehouse 不可达一律 **503**，
  绝不返回「全部可售」——那会让运营把「我们查不到」读成「能卖」，
  而那正是会真的超卖的路径（上游返回成功但该 SKU 无行 ⇒ 可售量按 0 计，这是**结论**不是故障）。
- 契约用 `ListingBundle*` 前缀（**不是** `BundleComponent*` —— 那个名字已被 catalog 3.5.1 的同名 DTO 占用，
  codegen 的字段级覆盖会**静默删掉字段**）。
- 已知缺口（如实记录，不冒充已交付）：构成冻结快照归 09 订单，本模块**不复制**
  （`component_snapshot` 显式 available=false）；按组件预占库存是 07 库存的写路径，本模块只读可售量
  （`inventory_reservation` available=false）。

---

## 模块 4.3.2.1 内容回写

**这个功能做什么**：把标题、要点、图片、描述、A+ 这五类**内容**变更推回平台。
界面明确**没有改价 / 改库存入口**；内容通道在途时显示「在途（等平台回报）」，不写「已回写」。

**技术落地**：

- 迁移：`V187__listing_content_writeback.sql` — 三张新表，**纯新建、零 ALTER 任何既有表**：
  `listing_content_writeback`（一次触发 + 口径快照 + 六计数 CHECK 自洽）/
  `listing_content_writeback_task`（`operation_id` → V2 `platform_operation`）/
  `listing_content_writeback_change`（逐字段改前/改后 + 两侧来源逐行记下）。
- 端点：`/channel/listing-content-writeback`（capabilities / writebacks / {id} / {id}/tasks /
  {id}/dispatch / {id}/retry / {id}/cancel / tasks/{id}/changes / tasks/{id}/report /
  listings/{id}/content-history），权限码复用 identity 既有 `channel.operation.*`（identity 零改动）。
- 页面：`/listings/content-writeback`（`content-writeback-workspace.tsx` + 纯函数
  `content-writeback-model.ts`，25 例单测）。
- 测试：`ContentWritebackIntegrationTest` 20 例实跑 Testcontainers PostgreSQL 16（连接池压到 1）。

**关键设计决策**：

- **「内容回写不触碰价格与库存」的三层物理落法**（缺一都不算交付）：
  ① **取值域** —— `change_types` 与 `change.field` 的 CHECK **只有** TITLE/BULLETS/IMAGES/DESCRIPTION/A_PLUS；
  ② **零金额列** —— 本迁移**无任何金额列**，集成测试逐表断言 `information_schema.columns` 里
  numeric/float/money 计数为 0（影子价格列 = 第二个价格事实来源，两个数字迟早对不上且没人说得清信哪个）；
  ③ **服务层逐个点名 422** —— 含 PRICE/STOCK/QUANTITY/CATEGORY 的请求逐个被点名拒绝并写明归属，
  **不静默忽略**（静默忽略会让用户以为价格也推上去了，而它根本没有）。
- **「改后与线上逐字相同 ⇒ SKIPPED_NO_CHANGE 且永不派发」**，它是**独立状态而不是「成功」** ——
  谎报「已推送」比不推送更难发现，而对平台的写调用是真实的外部副作用；
  这类任务进度算 100%（终态）不留在 0%。
- **「三条通道独立失败独立重试」**：retry **只**打回本模块自己的 FAILED，
  DB 触发器 `guard_content_writeback_terminal` 让已 SUCCEEDED 的打不回去（23514）；
  重试**复用同一个 02 队列任务**（operation_id 保留、幂等键不变），平台侧不出现第二个任务。
- 诚实边界三条：实际调平台 API 归 02 适配器 + 4.3.2.2（**不伪造平台返回**）；
  上游 03 商品主数据的变更事件**跨服务读不到**（AGENTS.md 禁跨服务 SQL），本批入口是用户显式触发；
  描述与 A+ 的内部待推送内容属 4.2.1.1 刊登编辑器（未交付，V177 草稿没有这两列）⇒ 只能来自显式覆盖值，
  既无草稿又无覆盖值时判 `SKIPPED_NO_SOURCE`，**不编假值**。
- 开发中实测修掉的真缺陷：① 任务 CHECK 漏了 `SKIPPED_NO_CHANGE` 这个**从未派发**的状态
  （派发时刻/队列行两条判据都会误伤它）；② 写 02 队列时误用**限流行解析出的 shop_id**，
  而平台级限流行解析出的是 NULL（`platform_operation.shop_id NOT NULL`）⇒
  整条通道在最常见的「平台级限流」配置下插不进去，改为用**任务自己的店铺**；
  ③ 租户隔离断言原本用 Testcontainers 超级用户连接，那是**永远失效的断言**（超级用户绕过 RLS），
  改连 `channel_app` 才真正验到隔离；④ allowlist 正则把 tasks 段的动作写成可选，放行了一个后端没有的裸 `tasks/{uuid}` GET。

---

## L2 批次闸结果：红，但根因是环境争抢（内容无回归）

**运行**：20:09:24 → 20:29:43（集成头 `894d8ab3`）

| 层 | 结果 |
| --- | --- |
| 后端 `mvnw verify` | **rc=1** — `Tests run: 1364, Failures: 3, Errors: 24` |
| `pnpm typecheck` | rc=0 |
| `pnpm lint` | rc=0 |
| `pnpm test`（前端） | rc=0 |
| Next production build | rc=0 |
| `sync --check` | rc=0 |

**失败只集中在 trade 的 2 个测试类**，且形状是环境形状，不是断言形状：

| 失败簇 | 数量 | 根因 |
| --- | --- | --- |
| `WaybillRedirectIntegrationTest` | 22 errors | 全部 `NoClassDefFound Could not initialize class` → `<clinit>:127` → `PSQLException: 进行 SSL 连线时发生错误`（静态初始化连 Testcontainers PG 时 SSL 握手失败） |
| `OrderChangeCancelIntegrationTest` | 3 failures + 2 errors | 3 条断言的**期望文案各不相同**（超过平台可改窗口 / 超过平台规则上限 2 / 平台规则不允许减量），但**实际返回的是同一句**「平台 AMAZON / 店铺 SHOP-1 没有登记『减少数量』的可变更规则」⇒ seed 没把规则登记进去，5 个失败共享一个前置根因 |

**两条硬证据说明这不是本批引入的回归**：

1. **本批根本没碰这两个文件**：
   `git diff 5531936d..integration/module-dev -- <两个测试文件>` 为空；
   本批对 trade 的全部改动只有 6 个文件（V185 迁移 + 质量分析的 Models/Service/Controller/两个测试类），
   **零 ALTER 零 DROP 既有表**，不可能影响订单改址与订单变更规则。
2. **隔离复跑全绿**：机器空闲后单跑两个类 —— `Tests run: 61, Failures: 0, Errors: 0`、**BUILD SUCCESS**
   （`./mvnw -pl services/trade -am test -Dtest='WaybillRedirectIntegrationTest,OrderChangeCancelIntegrationTest'`）。

**根因**：L2 与 4 条车道的 L1 Testcontainers 跑**并发**了。同一时刻机器上 PG 容器峰值 16 个，
`WaybillRedirect` 的静态初始化 SSL 握手失败、`OrderChangeCancel` 的 seed 没跑完 ——
这正是「L2 与车道 L1 不该同时跑」那条纪律要防的假红（形如 503 / 连接拒绝 / 固定在 `seed()` 的成片失败）。

**处置**：按协议 **不 push**，也不在集成头上改代码。
L2 是发热高峰，20:30 起进入静默窗口，**不允许再起一次全量**；
因此本批的 push 顺延到 **08:00**（机器空闲、无车道并发）的一次干净 L2 之后，
届时与 11.2.3.3 一并验证并 push。
本轮**没有**为了「让 L2 变绿」而重跑全量或改动集成头。

---

## 本批派发（6 条车道，受 runtime 子任务并发上限 4 限制实际发出 4 条 + 后续补 2 条）

`plan --lanes 6` 排出 6 条（L3 锚点两两不同，无兄弟模块撞车）：

| 模块 | 名称 | 服务 | 迁移 | 车道 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 2.4.2.3 | 映射缺失队列 | channel | V189 | `feat/2-4-2-3` | **running**（后端已绿 14 例，前端待做） |
| 11.2.3.3 | 供应商索赔 | trade | V184 | `feat/11-2-3-3` | **ready**（已 land） |
| 4.4.2.1 | 商品认领 | channel | V188 | `feat/4-4-2-1` | **running**（L1 19 例绿，前端待做） |
| 7.8.2.1 | 分摊结果查看 | warehouse | V190 | `feat/7-8-2-1` | **running**（后端 28 例绿，契约+前端待做） |
| 4.2.1.1 | 刊登编辑器 | channel | V191 | `feat/4-2-1-1` | **running**（依赖自检中） |
| 4.2.2.1 | 刊登模板 | channel | V192 | `feat/4-2-2-1` | **running**（尚未开工） |

**11.2.3.3 供应商索赔已交付并 land**（`71778444`）：V184 迁移（五张新表 + 六个守卫触发器 + RLS + 列级 GRANT，
零 ALTER 既有表）、12 个端点 `/trade/supplier-claims/**`、页面 `/aftersales/supplier-claims`、
测试 `SupplierClaimIntegrationTest` 23 例 + `SupplierClaimTenancyIntegrationTest` 4 例（合计 27 例绿）、
前端 35 例 + **因改了 api-proxy.ts 跑了全量 allowlist 回归 42 文件 223 例绿**、
契约重跑到临时目录后与仓库产物**逐文件字节比对 IDENTICAL**（确认是 codegen 产物非手改）。
过程中修掉 2 个真缺陷：三个 tone 函数用了不存在的 Badge tone `'muted'`（会静默退回默认色）、
白名单调用点抽取只认 `${base}`/`${id}` 而本模块多一个 `${settlementId}`。

**另外 5 条车道的部分实现全部保留在各自 worktree 里**，未提交内容不丢；
下一轮 `plan` 会按孤儿判据（`sessionId` 为空且心跳超 60 分钟 / 心跳超 90 分钟）把它们重派续写。

---

## 本轮运维要点（给下一轮调度者）

1. **调度闸被上一轮遗留占用 3 小时**：17:00 那轮取闸后没有释放（`.dev-agent/dispatch.gate/owner` 时间戳
   `2026-10-19T09:03:30Z`），导致 **18:00 与 19:00 两轮全部撞闸空转**，3 条 ready 车道因此压了两轮没并入。
   `lane.mjs gate acquire` 自带 **600 秒 TTL**，陈旧闸会被自动接管（不是 force 清别人的闸）；
   但更好的做法是**派发完就 `gate release`**。
2. **runtime 的子任务并发上限是 4，比配置里的 `--lanes 6` 小**。`plan` 排了 6 条，但同时只能有 4 个 child task；
   被拒的两条要等空位出来再补派。**每批任务数不等于额外并发名额** —— 排出来 ≠ 派得出去。
3. **L2 不能与车道 L1 并发**：本轮 L2 与 4 条车道的 Testcontainers 同时跑，PG 峰值 16 个，直接造出 27 例假红。
   正确顺序是「派发 → 等车道落地 → 再跑 L2」，或明确把 L2 排在车道测试之后。
4. **`11.2.3.3` / `4.4.2.1` 的车道状态曾滞留在 `running`，但分支其实已被指回集成头** ——
   成果既没进集成头也没在台账里（台账仍是「未开发」）。
   判据不是 `git merge-base --is-ancestor`（分支被指回后它恒为「已并入」），
   而是**集成头里有没有该模块的迁移与测试文件**。本轮据此把两条判为「未交付 → 重派续写」而不是「已交付」。

---

## 后续待办

- [ ] **08:00**：干净环境下重跑一次完整 L2（后端 verify + 前端 typecheck/lint/test + Next build + `sync --check`），
      绿了才 push `integration/module-dev`（本批 3 个模块 + 11.2.3.3）。
- [ ] **08:00**：`merge-ready` 并入 11.2.3.3（已 ready），并重派 5 条未完成车道续写。
- [ ] 08:00 前不要写 `last-l2-at`（本轮 L2 于 20:29:43 结束，落在静默窗口前，但时间戳写入发生在窗口内 —— 已跳过）。
