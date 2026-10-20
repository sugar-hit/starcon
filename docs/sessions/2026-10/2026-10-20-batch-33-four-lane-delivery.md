# 批次 33 · 四车道交付（4.4.1.3 / 9.2.1.1 / 9.2.2.1 / 4.5.2.2）

> 时间线：2026-10-20 07:37 派发 batch-33 → 08:05 四车道全部落地 → 08:07 合并 + 开 L2
> → 08:56 L2 第一次红（宿主机与 PG 时钟漂移，非本批回归）→ 08:56:55 整批重跑 → L2 第二次绿
> → 说明/commit/push
> ⚠️ **本说明由 09:00 tick 收尾**：08:00 那个 tick 在写出草稿后于 08:58:58 因
> `net::ERR_NETWORK_CHANGED` 失联（L2 第二次的脚本仍在后台跑，闸未释放）。
> 09:00 tick **没有另起第二个 L2**（避免 Testcontainers 争抢造成假红），
> 而是只读接管那个在途的 L2，拿到结论后补齐「写说明 → commit → push」三件套。
> 草稿正文与全部取证均来自 08:00 tick，09:00 tick 逐项复核了其中可复核的部分
> （白名单零丢失、迁移零 ALTER、465 行 / 0 重复 ID、四行「已交付」）。
> 集成头：`integration/module-dev`（并入前 `f9bee3d5` → 并入后 `b463ffb8`）
> 模式：`MODE=full`（TOKEN_HORIZON=2026-10-25 前），LANES=4，THROTTLE_MIN=50，SILENT_WINDOW=0

---

## 本轮概览

一批 4 条车道，**跨 2 个后端服务、2 个 L3 锚点、4 个共享文件**，全部零冲突并入、L2 全量绿。

| # | 模块 ID | 名称 | 阶段 | L3 | 服务 | 迁移 | 车道 commit |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 4.4.1.3 | 采集箱 | D5 | 4.4.1 | channel | V208 | `cfd2fa15` |
| 2 | 9.2.1.1 | 待审队列 | D4 | 9.2.1 | trade | V209 | `d9db9fd6` |
| 3 | 9.2.2.1 | 地址与支付校验 | D4 | 9.2.2 | trade | V210 | `3a2c7ae4` |
| 4 | 4.5.2.2 | 上新与变更监控 | D5 | 4.5.2 | channel | V211 | `367f0147` + `93a7b331` |

台账：**已交付 230 → 234**，未开发 235 → 231，完成率 49.5% → **50.3%**（465 行 / 0 重复模块 ID / `sync --check` 绿）。

---

## 四个模块各一节

### 4.4.1.3 采集箱（channel，D5，L3 4.4.1）

| 件 | 落地位置 |
| --- | --- |
| 迁移 | `V208__listing_collect_box.sql` —— 5 张新表（`listing_collect_box_tag` / `box_item` / `box_item_tag` / `claim_rule` / `claim_result`），**零 ALTER 任何既有表**；4.4.1.1 / 4.4.1.2 的表只读复用 |
| 端点 | `/channel/listing-collect-box` —— capabilities / items(+`{id}`) / groups / candidates / POST items(收录) / items/claim / items/tags / items/remove / tags(+`{id}`) / rules(+`{id}`) |
| 页面 | `/listings/collect/box`（`listing-collect-box-workspace.tsx` + 纯函数 `listing-collect-box-model.ts`） |
| 测试 | `ListingCollectBoxIntegrationTest` 24 + `ListingCollectBoxTenancyIntegrationTest` 3 + 前端 model 23 + allowlist 8 |

**关键裁决 —— 认领只产出草稿。**
`build_state` 恒为 `PENDING_BUILD`；能力表把「商品草稿落库」标成 `available=false` + `ownerModule=3.1.1.1` + impact。
理由：04 卷边界「不新建 SKU（引导到 03）」+ AGENTS.md 禁跨服务写表 + 03 建档需要租户自有 SPU 编码 / 属性字典 id / 计量单位
（channel 造这些即假数据）。集成测试**专门断言 channel 库不存在 `spu` / `sku` 表**。

**品牌/分类映射是三层硬约束**：DB CHECK + 建规则 400 + 认领逐条校验（映射不上的那一条**认领失败**并把原因写回条目）。
映射形态用具名键值项列表（`Map` 会被 codegen 推成空对象 `{}`）。

**踩坑记录**（worker 自报，本轮未复现但值得留档）：PG JDBC 驱动会改写 SQL 里的 `{}`
（psql 合法、驱动发出后报 `Unterminated string literal`）⇒ 带花括号的值一律走占位符；
jOOQ 3.19 把 `UUID[]` 绑成单个 uuid ⇒ 自拼 IN 列表而非 `= any(?::uuid[])`。

### 9.2.1.1 待审队列（trade，D4，L3 9.2.1）

| 件 | 落地位置 |
| --- | --- |
| 迁移 | `V209__trade_order_pending_review.sql` —— 3 张新表（`order_review_batch` / `order_review_queue_case` / `order_review_batch_item`）+ 2 个 IMMUTABLE 函数，**零 ALTER** |
| 端点 | `/trade/order-pending-review` —— 7 个 |
| 页面 | `/orders/review/queue`（canonical）+ `/orders/pending-review`（别名，**同一组件**） |
| 测试 | `OrderPendingReviewIntegrationTest` 30 + `OrderPendingReviewTenancyIntegrationTest` 5 + 前端 model 16 + allowlist 5 |

**关键裁决 —— 队列不另起表。**
队列**就是** `sales_order.status='SUBMITTED'` 且无终态结论的订单，不建队列表（避免双写与口径漂移）。

**批量审单必须指名规则**：缺 `ruleCode` / 规则未知 / 版本不对 / 不在生效窗口 / 规则结论是 `MANUAL_REVIEW` / `forced_manual`
—— 六种情况全部显式拒绝；且每一单必须**确实是那条被指名的规则**判出的 `AUTO_APPROVE`。
DB 层用 CHECK 兜住「无规则的批准写不进去」，**绕过服务层打库也过不去**。

**排序是真 SQL**：`risk_score` → `order_review_timeliness_score()` → `submitted_at` → `id`；
`risk_score` 为 `null` 时**不**被当成 0 参与排序。
命中链复用 9.2.1.2 的 `RuleHit` / `HitCondition` 形状 ⇒ 可回溯到规则 ID。
批次按 `UNIQUE(tenant_id, idempotency_key)` 幂等，回执报实际 approve/reject/suspend/skip 条数。

**一处有意的取舍**：`order_review_batch_item` **故意不建到 `sales_order` 的外键** ——
回执是「当时批过什么」的证据，订单被删也不该把证据带走。代价：订单删除后 item 行不会自动清理。

### 9.2.2.1 地址与支付校验（trade，D4，L3 9.2.2）

| 件 | 落地位置 |
| --- | --- |
| 迁移 | `V210__trade_order_address_payment_check.sql` —— 6 张新表 + 基线安装函数 + 4 触发器 + 7 索引 + ENABLE/FORCE RLS + GRANT，**零 ALTER** |
| 端点 | `/trade/order-address-payment-check` —— 14 个，含 `GET /orders/{uuid}/latest`（**9.2.1.3 的读路径**） |
| 页面 | `/orders/address-payment-check` + `packages/ui/src/lib/task-pages.ts` 已登记 |
| 测试 | `AddressPaymentCheckIntegrationTest` 33 + `AddressPaymentCheckTenancyIntegrationTest` 9 + 前端 model 17 + allowlist 9 |

**加粗句「货到付款订单的履约风险最高，需额外风控与保险策略」的五层落点**：

1. `CRITICAL` 风险等级**只能**由货到付款触发，且有**表级 CHECK 双向钉死**（造不出别的组合，也拆不坏）；
2. 货到付款是**独立的一项**，不与地址问题混算；
3. **保险策略恒为未交付** —— 能力表 `available=false` + `ownerModule=10.1.1.2` + `impact`，**不假装有保险能力**；
4. 额外风控**真接通** 9.2.2.2；
5. **没有端点能改写风险等级**。

**一个刻意不做的判断**：货到付款**不**判「不可投递」——
它是**收不到钱的**风险，不是投递问题。判错会导致地址规则把货到付款单全拦掉。

**支付规则基线落 24 条全量**（11 一致 / 6 WARN / 7 BLOCK），而不是只落「不一致」的组合 ——
这样「没配规则」与「配了且一致」在数据上**可区分**，不会静默地把未配置读成一致。

**禁运国家清单落成码表**（`order_address_check_embargo_rule` / `country_rule`），**不写死国家数组在 Java 里**。

**遗留（不属本车道）**：9.2.1.3 自己的 `ADDRESS_DELIVERABILITY` 仍恒为 `UPSTREAM_UNAVAILABLE` ——
那是 V153 的文件所有权；本模块已备好授权读路径 `GET /orders/{uuid}/latest`，**改接线属后续车道**。

### 4.5.2.2 上新与变更监控（channel，D5，L3 4.5.2）

| 件 | 落地位置 |
| --- | --- |
| 迁移 | `V211__listing_changes_monitor.sql` —— 5 张新表（`listing_change_watch` / `crawl` / `snapshot` / `event` / `opportunity`）+ 5 触发器 + 索引 + ENABLE/FORCE RLS + GRANT，**零 ALTER** |
| 端点 | `/channel/listing-changes` —— 12 组（capabilities / filter-options / watches CRUD / crawls / events / opportunities(+acknowledge) / history / comparison） |
| 页面 | `/listings/changes` |
| 测试 | `ListingChangeIntegrationTest` 26 + `ListingChangeTenancyIntegrationTest` 4 + 前端 model 24 + allowlist 4 |

**加粗句「监控对象与自有商品分离，竞品数据不进自有商品模型，只做参考指标」的落成是结构性的**：

- 监控对象表**没有 `listing_id` 列**，也**没有到 `listing` 表的外键**；
- 五张表**零自有商品列** —— 测试**逐表查 `information_schema`** 断言这一点；
- 每个响应都带 `dataSource='PLATFORM_SCRAPED'` + `sourceNote` / `actionsNote`；
- **没有抢占 / 调价端点** —— 本模块只产出参考指标，不产生任何写自有商品的路径。

**变化量不落表、SQL 现算**（照 4.5.1.1 / 4.5.2.1 的比率纪律），分母为 0 时返回 `null`（**不是 0 也不是 100**）；
判定基准取「上一个**已知**值」而不是「上一次观测」—— 观测缺失不该被当成一次真实变化。

**与 4.5.2.1 不合并**：按 4.5.2.1 会话日志留的待办「届时可能需要复核两者的配置是否该合并到一张表」，
本车道**各建各的、零 ALTER**，并把边界写进会话日志与台账证据。
两者身份模型本就不同：4.5.2.1 有 `listing_id`，本模块没有 ⇒ 合并需要先定身份口径，属后续复核。

---

## 调度者独立复核（不只看 worker 自报）

### 1. 共享白名单 `api-proxy.ts`：纯新增，零丢失

用**集合差**（不是 numstat、不是看 diff）比对并入前后：

| | 元素数 | 丢失 | 新增 |
| --- | --- | --- | --- |
| 并入前 `f9bee3d5` | 64 | — | — |
| 并入后 `b463ffb8` | **68** | **0** | **4**（`listing-collect-box` / `listing-changes` / `order-pending-review` / `order-address-payment-check`） |

4 条新前缀全部**锚定**（`/^listing\-collect\-box(?:\/capabilities|…)?$/` 形式），
且首 token 与既有全部不同 ⇒ 互不覆盖。

### 2. 契约：6 个服务逐个做集合差，零丢失

| 服务 | path | schema | 丢失 path | 丢失 schema |
| --- | --- | --- | --- | --- |
| channel | +22 | +56 | **0** | **0** |
| trade | +16 | +37 | **0** | **0** |
| catalog / identity / warehouse / crm | 0 | 0 | **0** | **0**（逐字节不变） |

channel +22 = 4.4.1.3 的 +12 + 4.5.2.2 的 +10；trade +16 = 9.2.1.1 的 +7 + 9.2.2.1 的 +9。

### 3. 静态预检：四份迁移零命中

| 迁移 | ALTER TABLE | ADD COLUMN | SET NOT NULL | ADD CONSTRAINT | CREATE TABLE | RLS/GRANT |
| --- | --- | --- | --- | --- | --- | --- |
| V208 | 0 | 0 | 0 | 0 | 5 | 8 |
| V209 | 0 | 0 | 0 | 0 | 3 | 13 |
| V210 | 0 | 0 | 0 | 0 | 6 | 12 |
| V211 | 0 | 0 | 0 | 0 | 5 | 12 |

**19 张新表、零 ALTER 既有表**；V208–V211 全仓各只 1 个文件（无撞号）；
channel 最高 V211、trade 最高 V210，与其余服务无冲突。

### 4. 负向断言纪律：batch-32 那个坑没复发

batch-32 的 L2 第二次红，是 4.5.2.1 拿兄弟车道前缀当「未交付形状」要求 404。
本批任务书加了「🔴 负向断言纪律」硬约束，四条车道**逐条自查全部通过**：

- 4.4.1.3：7 条负向样本（`base = 'channel/listing-collect-box'`）
- 9.2.1.1：13 条负向样本（`base = 'trade/order-pending-review'`）
- 9.2.2.1：9 条负向样本（`base = 'trade/order-address-payment-check'`）
- 4.5.2.2：11 条负向样本（`base = 'channel/listing-changes'`）

⇒ **40 条负向样本，0 条拿兄弟车道的前缀当负向样本**（逐条用脚本解析数组块 + `base` 变量核对，不是抽样）。

**而且比要求多做了一层**：4.4.1.3 与 4.5.2.2 各额外加了 **17 / 24 条「防误伤」正向守卫**
—— 断言**别的模块**的路由在本次改动后**仍然不是 404**。
这正面覆盖了「取错一侧静默删掉别人已交付的路由」这个**假绿**风险（batch-32 踩过的坑）。

### 5. 共享文件 `packages/ui/src/lib/task-pages.ts`

**只有 9.2.2.1 一条车道碰它**（新增一条 `/orders/address-payment-check` 条目，纯增量 17 行）⇒ 并入零冲突。
另三条车道的 diff 里它**一行未动**。

### 6. 并入前先验了「两条 channel 车道 + 两条 trade 车道」的契约并集

channel 有两条车道同时改 `channel.openapi.json`、trade 也有两条同时改 `trade.openapi.json`
—— 这是本批唯一的真冲突风险点。直接拿 `merge-union.mjs` 做并集测试：

| | base | ours | theirs | merged | 应有 | 丢失 |
| --- | --- | --- | --- | --- | --- | --- |
| channel path | 326 | 338 | 336 | **348** | 348 | **0** |
| channel schema | 572 | 602 | 598 | **628** | 628 | **0** |
| trade path | 459 | 466 | 468 | **475** | 475 | **0** |
| trade schema | 1056 | 1073 | 1076 | **1093** | 1093 | **0** |

⇒ 合并驱动（`.gitattributes` + `merge.contract-union.driver`）**按 key 求并成功**，
两侧新增全部保留、零丢失。台账也按模块 ID 求并：465 行、**0 重复 ID**、
batch-33 四行全为「已交付」且证据完整。

---

## 并入过程

`lane.mjs merge-ready` 一次并入 4 条车道，**零冲突**：

```
4.4.1.3 采集箱        f9bee3d5 → 461f33e8
9.2.1.1 待审队列      461f33e8 → 0ae307dd
9.2.2.1 地址与支付校验 0ae307dd → a55d6a46
4.5.2.2 上新与变更监控  a55d6a46 → b463ffb8
```

`ok: true`，`failed: []`。

## L2 批量验证结果

### 第一次（08:07–08:54）：后端 trade 红 —— `Tests run: 1494, Failures: 1, Errors: 31`

**不是本批回归。** 三个失败类**全在既有模块**，本批 4 个模块的测试在同一轮里全绿
（channel 24+3+26+4=57、trade 33+9+30+5=77）：

| 失败测试类 | 归属模块 | 违的约束 | 错误数 |
| --- | --- | --- | --- |
| `FulfillmentRequestIntegrationTest` | 9.3.3.1 履约请求 | `fulfillment_request_step_check3` | 1 failure + 11 errors |
| `FulfillmentMonitorIntegrationTest` | 9.3.3.2 履约流程监控 | `fulfillment_request_reservation_check5` | 18 errors |
| `OrderConsolidationIntegrationTest` | 9.3.1.2 合单 | `order_consolidation_timestamps_ordered` | 2 errors |

**根因：宿主机（JVM）时钟与 Docker 内 PG 时钟的漂移，这次达到数百毫秒。**
失败行直接量出了偏差：

```
requested_at    = 2026-10-20 08:28:01.659602+08   ← 应用/JVM 写
acknowledged_at = 2026-10-20 08:28:01.117591+08   ← PG clock_timestamp() 写
⇒ DB 时钟比 JVM 时钟慢约 542ms
```

同一个窗口，批次 32 那次只有 **1.006ms** —— 同一个病，窗口宽了几百倍，所以一次炸了 31 个而不是 1 个。
**该漂移是瞬时的**：08:28 偏差约 560ms，08:57 复测已回到 **[-52ms, +78ms]**。

**隔离复跑 7 个类 → 162 例全绿（`BUILD SUCCESS`）** ⇒ 确证是环境/时钟诱发的既有脆弱点，不是本批代码回归。

**受影响面已精确定位**（扫了全服务约 40 条「比较两个时间戳」的 CHECK，按两侧写入方分类后，
**真正跨时钟域的只有 4 张表、7 条约束**）：V152 的 `fulfillment_request`（3）/
`fulfillment_request_reservation`（2）/ `fulfillment_request_step`（1）+ V116 的
`fulfillment_warehouse_allocation_decision`（1）。
已确认**同源、不会翻负**的：所有 `updated_at>=created_at`（两侧 `now()`）、
`totals_computed_at>=created_at`、`finished_at>=started_at`（V147 / V36，两侧应用写）、
`finished_at>=requested_at`（V200，两侧应用写）。
详见 `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` §六之三。

### 第二次（08:56:55 → 09:42:29，时钟对齐后重跑整批）：**全绿**

```
===== L2 DONE 2026-10-20 09:42:29 =====
SUMMARY: mvn=0 typecheck=0 lint=0 test=0 build=0 sync=0
```

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `backend/./mvnw verify` | **BUILD SUCCESS**，42:34 min（09:39:30 结束） |
| 类型 | `pnpm typecheck` | 通过 |
| Lint | `pnpm lint` | 通过 |
| 前端单测 | `pnpm test` | **301 个测试文件 / 4822 例全绿**，24.50s |
| Next 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 成功（静态预渲染清单正常产出） |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | **✓ 台账与蓝图一致** |

**后端逐服务计数**（只统计 attempt 2 时间窗内写出的 surefire 报告）：

| 服务 | 测试类 | 例数 | failures | errors |
| --- | --- | --- | --- | --- |
| catalog | 83 | 1502 | 0 | 0 |
| identity | 22 | 270 | 0 | 0 |
| crm | 3 | 18 | 0 | 0 |
| warehouse | 59 | 1535 | 0 | 0 |
| channel | 65 | 915 | 0 | 0 |
| trade | 74 | 1494 | 0 | 0 |
| **合计** | **306** | **5734** | **0** | **0** |

**第一次红的三个失败面本轮全部未复现**：
`fulfillment_request_step_check3`（9.3.3.1）、`fulfillment_request_reservation_check5`（9.3.3.2）、
`order_consolidation_timestamps_ordered`（9.3.1.2）—— 与「瞬时时钟漂移」的定性一致。

> ⚠️ **本批次的 push 建立在「时钟对齐后的整批重跑全绿」之上，不是首次全量绿。**
> 这一点必须如实记录：第一次全量是红的（`Tests run: 1494, Failures: 1, Errors: 31`），
> 根因是宿主机与 PG 容器的时钟漂移（实测 542ms），不是本批代码回归
> —— 隔离复跑 7 个类 162 例全绿已确证。



---

## 遗留与风险（交给下一轮）

1. **9.3.3.1 履约请求的 1ms 跨时钟竞态**（既有模块，**第 2 次**出现）：
   `FulfillmentRequestService:800` 用 JVM `OffsetDateTime.now()` 写 `started_at`，
   `V152:1290 complete_fulfillment_step` 用 PG `clock_timestamp()` 写 `finished_at`，
   违反 `fulfillment_request_step_check3` = `CHECK(finished_at IS NULL OR finished_at>=started_at)`。
   已记入 `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` §六 / §六之二
   （含 05:00 轮跨会话独立复核与**模块 ID 更正：是 9.3.3.1 履约请求，不是 9.3.1.1 拆单**）。
   **调度者不代改业务代码**，等派修复车道。
2. **merge-union 台账分支压塌 markdown 结构**（本轮新发现，长期存在）：
   台账是 4 张表，驱动按整行文本去重 ⇒ 每次并入把 17 个空行压成 1 个、4 个分隔行压成 3 个
   ⇒ 「分系统进度」表失去分隔行、不再渲染成表格。
   **数据不丢**（465 行 / 0 重复 ID / `sync --check` 绿），且被每轮 `sync.mjs` 重新生成顺手修回，
   所以从最终状态永远看不出来 —— 只有 `SYNC-MAIN` 那条不跑 sync 的路径会真的提交塌陷版。
   已记入 `docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md`。
3. **`api-proxy.ts` 里 4.5.1.2 条目的注释仍写着「未交付」**（该模块已交付），
   `listings/hijackers/page.tsx` 里「4.5.2.2 尚未交付」同样已过期 ——
   都属**别的模块的**文件所有权范围，本轮未改（纪律：只碰自己的文件）。
4. **9.2.1.3 的 `ADDRESS_DELIVERABILITY` 恒为 `UPSTREAM_UNAVAILABLE`**（V153 文件所有权），
   9.2.2.1 已备好授权读路径，接线留给后续车道。
5. **并发会话**：main→module-dev 同步任务共用同一把闸，本轮全程与它互斥（它撞闸只读）。

## 本轮收尾与下一轮预期

**本轮（09:00 tick）实际做了什么**

- 撞闸：`gate acquire` 返回退出码 3（08:56:55 的 keepalive 让闸目录只有几分钟龄）⇒ **零派发**，
  没有 `plan`、没有新车道、没有另起第二个 L2。
- 只读取证：确认持闸的 08:00 tick 已于 08:58:58 因 `net::ERR_NETWORK_CHANGED` 失联，
  而它的 L2 第二次验证（`l2-batch33.sh`，08:56:55 起）仍在后台跑 —— 这是**唯一**在途的 L2。
- 续期闸（新建 `keepalive-*` 文件刷新目录 mtime），等在途 L2 出结论。
- L2 绿后补齐三件套：写本说明 → commit（说明 + 台账 + 2 份 backlog 同 commit）→ push → `gate release`。

**下一轮（10:00 tick）预期**

- 节流闸：`last-batch-at` 会写成本轮收尾时刻 ⇒ 距下批不足 50 分钟的格会被节流闸拦下。
- 闸已释放 ⇒ 10:00 tick 可以正常取闸。
- 派发前置检查：三条 trade 车道 + 两条 channel 车道的 worktree 尚未 `gc`，下一轮可回收。
