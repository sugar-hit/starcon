# batch-37 —— 回收 4 条车道并入集成头 + L2 批量验证

**日期**：2026-10-20 18:00 tick
**模式**：full（`TOKEN_HORIZON=2026-10-25`，未超期；`LANES=4` / `THROTTLE_MIN=50`）
**本批交付**：4 个模块并入集成头（`10.3.1.2` / `10.3.2.2` / `10.6.2.1` / `10.7.1.2`），
另派发 4 条新车道（`15.2.2.1` / `10.7.1.3` / `5.5.2.1` / `5.2.1.2`）。

---

## 模块 10.3.1.2 异常件

**这个功能做什么**：把「这票货出问题了」变成**有人负责、有时限、有凭据**的工单 ——
异常件清单、处置记录、理赔/补发单三件事。货停在哪儿、妥投失败、地址问题、清关滞留、
破损丢件、买家投诉六类异常都进同一个清单，每件从建单起就有责任人和责任时限。

**技术落地**：

- 迁移：`V216__logistics_exception_case.sql` — **纯新建 5 张表，零 ALTER 任何既有表**、
  零触发器挂到别的模块的表上：`logistics_exception_rule`（租户可维护的识别规则）、
  `logistics_exception_case`（异常件主表）、`logistics_exception_disposition`（处置记录）、
  `logistics_exception_claim`（理赔/补发单）、`logistics_exception_downstream_task`（下游待通知）。
  每张业务表 `tenant_id NOT NULL` + RLS + GRANT。
- 端点：`/channel/logistics-tracking` × **18** — `exceptions/capabilities`（能力声明）、
  `exceptions/overview`（全量概览）、`exceptions`（GET 真分页 + 筛选 / POST 登记）、
  `exceptions/{id}`（单件详情）、`exceptions/rules`（GET/POST 规则）、
  `exceptions/scan`（按规则扫描建件，幂等）、`exceptions/{id}/dispositions`（GET/POST 处置）、
  `dispositions`（全量处置记录）、`dispositions/{id}`、`exceptions/{id}/claims`（GET/POST 单据）、
  `dispositions/claims`（全量单据）、`dispositions/claims/{id}`。
- 页面：`apps/web/src/app/logistics/exceptions/page.tsx` +
  `apps/web/src/components/logistics/exceptions/{exceptions-model.ts,exceptions-workspace.tsx}`。
  真分页、真筛选、错误态/空态/加载态齐备。
- 测试：`LogisticsExceptionIntegrationTest` **19 例** + `LogisticsExceptionTenancyIntegrationTest`
  **4 例**（租户隔离三断言 + RLS 策略直查库）；前端
  `apps/web/src/app/api/channel/logistics-exception-allowlist.test.ts` +
  `apps/web/src/app/logistics/exceptions/exceptions-model.test.ts`。

**关键设计决策**：

1. **「异常件必须有人负责到底」落成结构，不落成文案**：① `owner_membership_id NOT NULL`
   —— 无人认领的异常件在库里**不存在**，不用「待分配」糊过去；② `due_at NOT NULL`
   —— 逾期是**可统计的事实**；③ DB CHECK 钉死结案凭据
   `CHECK (status <> 'RESOLVED' OR (closed_at IS NOT NULL AND owner_membership_id IS NOT NULL))`。
   配套状态机触发器让终态**不可回退**。
   否决方案：用「待分配」状态 + 界面提示 —— 那正是「异常池」假活。
2. **来源由服务端按事实派生**（`RULE_SCAN` / `TRACKING_FLAG` / `CUSTOMER_FEEDBACK` / `MANUAL`），
   请求模型里**没有 source 字段**。来源是「这件事是怎么被发现的」这一事实，
   让调用方填等于把它退化成一句能随手写错的备注。
3. **时长一律整数分钟**：`stalled_duration_minutes bigint NOT NULL DEFAULT 0 CHECK (>= 0)`，
   **没有 hours 浮点列**；锚点在将来时截断到 0。界面上的小时数是服务端由整数分钟换算出的
   `BigDecimal`，纯展示，不是第二份事实。
4. **上游边界按「同服务 / 跨服务」一刀切**：10.3.1.1（V212）轨迹事件在**同库同服务**，
   用复合外键 `(tenant_id, subject_id)` 挂上去；11 售后 / 15 客服 / 7 库存**当前都不存在**，
   所以只产出本服务内单据 + `downstream_task`（状态恒 `PENDING_NOTIFY`），
   绝不用空表假装下游已交付。

---

## 模块 10.3.2.2 揽收与签收确认

**这个功能做什么**：盯住「货到底有没有被揽收、有没有被签收」——
主动巡检超时件、人工或承运商确认揽收、签收见证与提醒闭环。
不用等轨迹同步顺带产生建单，**巡检端点可以被调度器显式调用**。

**技术落地**：

- 迁移：`V217__logistics_pickup_signoff.sql` — 5 张表：
  `logistics_pickup_signoff_sla_rule`（时限规则）、`logistics_pickup_signoff_case`（确认单）、
  `logistics_pickup_signoff_event`（事件）、`logistics_pickup_signoff_reminder`（提醒）、
  `logistics_pickup_signoff_notification`（待通知）。`tenant_id NOT NULL` + RLS + GRANT。
- 端点：`/channel/logistics-pickup-signoff` × **15** — `capabilities`、`overview`、
  `cases`（GET 真分页）/ `cases/{id}` / `cases/sync`（POST 从上游同步）、
  `reconcile`（**POST 巡检超时件**）、`cases/{id}/attest`（POST 确认）、
  `reminders`（GET）/ `reminders/{id}/acknowledge` / `reminders/{id}/resolve`、
  `notifications`（GET）/ `notifications/{id}/consume`、`events`、`sla-rules`（GET/POST）。
- 页面：`apps/web/src/app/logistics/pickup-signoff/page.tsx` +
  `apps/web/src/components/logistics/pickup-signoff-{model.ts,pickup-signoff-workspace.tsx}`。
- 测试：`LogisticsPickupSignoffIntegrationTest` **22 例** +
  `LogisticsPickupSignoffTenancyIntegrationTest` **3 例**（租户隔离三断言）；前端
  `apps/web/src/app/api/channel/pickup-signoff-allowlist.test.ts` +
  `apps/web/src/components/logistics/pickup-signoff-model.test.ts`。

**关键设计决策**：

1. **「批次交接是责任分界点」的物理落点 = 确认单以交运件为粒度**：
   `source_manifest_item_id` 对 `handover_manifest_item(tenant_id, id)` 同库复合外键 +
   `UNIQUE(tenant_id, source_manifest_item_id)`，并快照 `source_batch_status`
   （**批次作废 ⇒ 责任从未转移**）。逐件粒度才有追责落点 —— 纠纷问的是「哪一箱没被揽收」。
2. **主动巡检 vs 同步顺带建单**：不把建单挂进 10.3.1.1 的 `POST /subjects/sync`。
   挂进去代码更省，但语义变成「同步轨迹的人才盯揽收」，而同步是稀疏动作、
   履约指标要求的是**持续**监控 —— 独立 `reconcile` 端点让「盯不盯」成为可被调度器显式调用的事。
3. **超时是读时算出来的，时限是建单时的快照**：`pickup_state='OVERDUE'` 由巡检按 `now()`
   与 `pickup_deadline_at` 比对得出；时限建单算好即定住，事后改 SLA 规则**不会**把已经延误的货变成准点。
4. **运单号对不上时如实报缺口而不是猜**：`NO_WAYBILL` / `TRACKING_SUBJECT_MISSING` /
   `SUBJECT_AMBIGUOUS` 三个缺口码；命中多个轨迹主体时**不自动确认**。
5. **承运商出网通道未交付 ⇒ 刻意不放假 vendor 实现**。没有通道时只能来自承运商推送或
   人工登记（`MANUAL_ATTESTATION` 带凭据号与时刻，**不覆盖**承运商事件的来源标记）。
   一个「返回成功回执」的假适配器会让「已揽收」在数据上看起来发生过 —— 履约指标一旦被伪造就没人再盯。
6. **本模块不涉及任何金额**：自造运费列会造出第二套成本口径，成本归 12 财务。

---

## 模块 10.6.2.1 时效分析

**这个功能做什么**：把「哪个渠道在哪个国家系统性延误」读出来 ——
渠道 × 国家交叉时效报表、渠道时效评分、异常集中分析。
总体平均会掩盖某些渠道在某些国家的系统性延误，所以交叉维度是硬要求。

**技术落地**：

- 迁移：`V218__logistics_timeliness_analysis.sql` — **不新建任何事实表**，
  只落一份可审计的评分台账 `logistics_timeliness_score`。
  事实源是同库上游 10.3.1.1（V212）的 `logistics_tracking_subject`。
  `tenant_id NOT NULL` + RLS + GRANT。
- 端点：`/channel/logistics-timeliness` × **9** — `capabilities`、`overview`、
  `reports/matrix`（渠道 × 国家交叉矩阵）、`reports/monthly`（月度）、`reports/anomalies`（异常集中）、
  `reports/scores`（评分）、`reports/score-snapshots`（GET/POST 评分快照）、
  `subjects`（可比样本明细）。
- 页面：`apps/web/src/app/logistics/timeliness/page.tsx` +
  `apps/web/src/app/logistics/timeliness/{timeliness-model.ts,timeliness-workspace.tsx}`。
- 测试：`LogisticsTimelinessIntegrationTest` **16 例** +
  `LogisticsTimelinessTenancyIntegrationTest` **3 例**（租户隔离三断言）；前端
  `apps/web/src/app/logistics/timeliness/{timeliness-model.test.ts,timeliness-proxy.test.ts}`。

**关键设计决策**：

1. **交叉维度进 SQL、进存储形状、进异常分析**（三处同时做，缺一处就退化成「一个总体平均」）：
   查询层一律 `group by channel_id, country_code`；存储层唯一键是
   `(tenant_id, channel_id, country_code, window_start, window_end)` ——
   **物理上就存不下「这条渠道总体表现」那一行**；分析层的贡献率 / 累计贡献率在格上算。
2. **比率的分母是「可比数」而不是「签收数」**：已签收但没有承诺时效可比的那批票单列
   `no_promise_count` —— 把它们算成准点或超时都是编造；塞进分母会让「没登记承诺」
   显示成「100% 不准点」，把运营的注意力引到根本不是问题的地方。
3. **样本不足 ⇒ 评分是 null（不可比）而不是 0 分**：门槛随快照落库，
   事后改门槛不能让已落库的那一格看起来是「够样本」算出来的。DB 用 CHECK 钉死
   「等级 ↔ 分数 ↔ 样本充分性」三者关系。
4. **三个比率用 GENERATED 列**（`GENERATED ALWAYS AS … STORED`）：服务层写不进去，
   绕过服务层直接写 SQL 也编不出一个与计数对不上的比率 —— 比率与计数打架是「假报表」的头号来源。
   Java 侧按同一口径算（先乘 10⁴ 再整数除 + HALF_UP），保证与 PG 的 `round(numeric, 4)` 逐位相同。
5. **承诺时效取发货当时的快照**（V212 主体表上的 `promised_eta_at`），
   **不 join `logistics_transit_rule` 重算**：事后改档期不能让一笔已经延误的货变成准点。
6. **窗口右边界固定到 UTC 自然日**（没给 `to` 时取明天 00:00，而不是「此刻」）：
   窗口键必须在同一天里保持稳定，否则「重算并落库」每次都开出一个新窗口键，
   幂等重算就变成了堆历史快照。
7. **本模块不写 `channel_rating_score`（V143）**：那张表的写入所有者是 10.1.2.2。
   比价换数据来源是比价那条车道的事；本模块只在 capabilities 里说清这个缺口。
8. **全程不依赖未并入的 10.6.1.1 成本分析**：成本侧结论在 capabilities 里显式报
   `available=false` + `ownerModule` + 影响。

---

## 模块 10.7.1.2 退运回国

**这个功能做什么**：把退运做成**批量合单**的生意 —— 多条处置单拼进一张退运批次单、
报关资料、成本按行分摊、货物回来后的重新入库 / 报废去向全程可追。
退运是批量、长周期、高单票成本操作，合单才摊得薄成本。

**技术落地**：

- 迁移：`V219__return_shipback_to_origin.sql` — **8 张表**：
  `return_shipback_consignment`（批次单）、`return_shipback_line`（明细行）、
  `return_shipback_customs_declaration`（报关资料）、`return_shipback_cost`（成本）、
  `return_shipback_cost_allocation`（逐行分摊）、`return_shipback_arrival`（到达）、
  `return_shipback_inventory_entry`（重新入库 / 报废分录）、`return_shipback_event`（事件）。
  `tenant_id NOT NULL` + RLS + GRANT。
- 端点：`/channel/return-shipbacks` × **19** — `capabilities`、`overview`、`candidates`（候选清单）、
  `consignments`（GET 真分页 / POST 建单）、`consignments/{id}`、
  `consignments/{id}/lines`（POST 加明细行，**合单**）、
  `consignments/{id}/customs`（POST 报关资料）、`consignments/{id}/costs`（POST 记成本）、
  `consignments/{id}/allocations`（GET 逐行分摊）、
  `consignments/{id}/confirm|dispatch|arrival|entries|close|cancel`（状态机动作）、`events`。
- 页面：`apps/web/src/app/logistics/return-shipback/page.tsx` +
  `apps/web/src/app/logistics/return-shipback/{return-shipback-model.ts,return-shipback-workspace.tsx}`。
- 测试：`ReturnShipbackIntegrationTest` **29 例** +
  `ReturnShipbackTenancyIntegrationTest` **3 例**（租户隔离三断言）；前端
  `apps/web/src/app/api/channel/return-shipback-allowlist.test.ts` +
  `apps/web/src/app/logistics/return-shipback/return-shipback-model.test.ts`。

**关键设计决策**：

1. **合单是真的**：退运单是**批次单**，明细行可拼进**多条**处置单；
   **一条处置单最多进一个退运单**（`UNIQUE(tenant_id, disposition_order_id)` +
   触发器 `guard_return_shipback_line_order`）。同一条货进两个退运单 ⇒ 成本被摊两次、退运量算两遍。
2. **成本分摊是真的算出来的**：每记一笔成本就立刻产出逐行分摊
   （权重 / 权重合计 / 比例 scale 4 / 分摊金额 / 尾差调整额）。
   算法是「先算总额 → 按权重逐行分摊 → **尾差补到最后一笔**」，
   **SUM(分摊金额) 恒等于该笔成本金额**（提交时由 DEFERRABLE 约束触发器保证，不依赖浮点累加）。
3. **分摊口径是数据不是代码常量**：每笔成本自带口径（重量 / 件数 / 申报货值 / 平均）；
   **权重合计为 0 时显式回退 EQUAL 并把实际口径写进 `applied_basis`**，绝不静默换口径。
4. **上游边界按「同服务 / 跨服务」一刀切**：待退运清单来自 10.7.1.1，它**就在 channel（同一服务）**
   ⇒ 直接读 `return_disposition_order`，并由触发器钉死「必须是 `RETURN_TO_COUNTRY` 且未撤销」
   （转销售 / 弃置销毁的处置单**根本进不来**）；反之 10.5.1 报关与 3.7.1.1 HS 编码**全部跨服务**
   ⇒ 本服务自持报关资料快照（`source_reference` 只记上游单号做逻辑引用，零跨库 join、零跨服务 FK）。
5. **关税是派生的，不给两个互相矛盾的数**：`tariff_amount = declared_value × tariff_rate`
   （HALF_UP 到 4 位），`total_amount = tariff_amount + tax_amount` 由 DB CHECK 钉死；
   税率填 `15`（而不是 `0.15`）直接拒。
6. **申报品名独立必填**，不照搬销售标题 —— 申报品名给清关看，销售标题给转化看，混用是常见退单原因。
7. **状态机只有一处真相**（DRAFT → CONFIRMED → DISPATCHED → ARRIVED → CLOSED，
   CANCELLED 只在 DRAFT / CONFIRMED）：DB 触发器钉死合法迁移对；
   **确认前**必须既有明细行（空单摊不了成本）又有报关资料（退运回国必须报关）；
   **关闭前**每一行都要有重新入库 / 报废去向；**入库分录只在 ARRIVED 之后**可登记（货没回来就入库是假的）。
8. **下游缺口按仓库先例显式声明**：12 财务（finance 服务**不存在**）⇒ 退运成本以本服务自持的
   成本行 + 分摊明细落地；7 库存 7.4.1.1 与 10.7.1.3 **均未交付** ⇒ 重新入库 / 报废分录
   **只出事实不消费**（`posting_status` 恒 PENDING、`consumer_module` 恒 7.4.1.1 / 10.7.1.3，
   且 `REVOKE UPDATE/DELETE`）。

---

## 静态预检（L2 之前）

对本批 4 个新迁移（V216 / V217 / V218 / V219）跑「已知互相破坏形状」扫描：

| 形状 | 命中 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT` | **0** |
| ② `SET NOT NULL` | **0** |
| ③ 新增 `ADD CONSTRAINT ... CHECK` | **0** |

⇒ 本批**零 DDL 回归面**：4 个迁移全是 `CREATE TABLE` 新建，没有一条 `ALTER` 打到既有表上，
也没有触发器挂到别的模块的表（`10.7.1.2` 的 `guard_return_shipback_line_order` 挂的是**本模块自己的**表）。
每张业务表 `tenant_id NOT NULL` + RLS + GRANT 已逐张核对。

## 并入过程与契约求并

4 条车道并入**零冲突**（`merge-ready` 退出码 0，`failed: []`）。两处共享文件的处理：

- `packages/api-contracts/src/{channel.openapi.json,contracts/channel.ts}` — 由
  `merge=contract-union` 驱动器按「OpenAPI 路径 / 类型名」求并。核对结果：
  合并后 channel 契约共 **430 条 path**，本批 4 个模块的
  **43 条 path 全部在场**（异常件 8 + 处置/单据 4 + 揽收签收 15 + 时效 9 + 退运 16，路径前缀互不重叠）。
- `apps/web/src/lib/api-proxy.ts` — **不在** union 驱动器的覆盖范围内，
  由 4 条车道**各自新增一条 `services[]` 元素**（而不是往 channel 既有 alternation 里并分支）
  来规避结构性冲突：每个服务在 `services[]` 里是一段**超长单行正则**，
  两条车道都往同一 alternation 并分支时 git 必然判冲突且 retry 永不自愈。
  4 个插入锚点刻意互不重叠，git 能干净求并。

## 台账完整性

- `sync.mjs` 汇总：**已交付 242 · 进行中 0 · 未开发 223**（465 个 L4 模块）
- `grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ '` = **465** ✔
- 同 ID 重复行 = **0** ✔

## ✅/❌ L2 第一轮：backend 红，其余全绿

| 阶段 | 结果 |
| --- | --- |
| `backend && ./mvnw verify` | ❌ **红** —— channel 39 例 Errors |
| `pnpm typecheck` | ✅ 绿 |
| `pnpm lint` | ✅ 绿 |
| `pnpm test` | ✅ 绿（318 个测试文件 / **5031 例**全过） |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ 绿（Compiled successfully，214/214 静态页） |
| `node tools/module-progress/sync.mjs --check` | ✅ 绿 |

逐模块 surefire 汇总：

| 模块 | 测试数 | 失败 | 错误 |
| --- | --- | --- | --- |
| identity | 270 | 0 | 0 |
| catalog | 1527 | 0 | 0 |
| channel | 1086 | 0 | **39** |
| warehouse / trade / crm | — | — | **SKIPPED**（reactor 在 channel 失败处停下，尚未跑到） |

⇒ **本轮 L2 不是「红成一片」，是精确的 39 例 Errors，且全部是 `@BeforeEach` 清理动作直接抛异常，
没有一个业务断言失败。**

### 根因：V216/V217 的外键打断了既有测试的 TRUNCATE

PG 报错原文：

```
org.postgresql.util.PSQLException: ERROR: cannot truncate a table referenced in a foreign key constraint
  详细：Table "logistics_exception_case" references "logistics_tracking_event".
  建议：Truncate table "logistics_exception_case" at the same time, or use TRUNCATE ... CASCADE.
	at com.starcon.channel.LogisticsPickupSignoffIntegrationTest.truncateAll(...:171)
```

链条：

1. PostgreSQL 规则：**被 TRUNCATE 的表若被别的表的外键引用，且引用方不在同一条 TRUNCATE 语句里，直接拒绝。**
2. 本批 `V216__logistics_exception_case.sql` 给 `logistics_exception_case` 加了
   `FOREIGN KEY (tenant_id, subject_id) REFERENCES logistics_tracking_subject(tenant_id, id)` 与
   `FOREIGN KEY (tenant_id, source_event_id) REFERENCES logistics_tracking_event(tenant_id, id)`；
   `V217__logistics_pickup_signoff.sql` 给 `logistics_pickup_signoff_case` 加了
   `FOREIGN KEY (tenant_id, source_manifest_item_id) REFERENCES handover_manifest_item(tenant_id, id)`。
3. 于是**凡是 TRUNCATE 了这三张父表、但没写 `CASCADE` 的测试，迁移一落地就全红**：

| 测试类 | 所属模块 | Errors | 状态 |
| --- | --- | --- | --- |
| `LogisticsPickupSignoffIntegrationTest` | 10.3.2.2（本批） | 22 | 🔴 |
| `LogisticsPickupSignoffTenancyIntegrationTest` | 10.3.2.2（本批） | 3 | 🔴 |
| `HandoverBatchIntegrationTest` | 10.1.2.x（**既有，本批之前是绿的**） | 14 | 🔴 |
| `LogisticsExceptionIntegrationTest` | 10.3.1.2（本批） | 0 | ✅ 写了 cascade |
| `LogisticsTimelinessIntegrationTest` | 10.6.2.1（本批） | 0 | ✅ 写了 cascade |
| `TrackingQueryIntegrationTest` | 10.3.1.1（既有） | 0 | ✅ 写了 cascade |

⇒ 这就是组批模式要抓的那一层：**一条车道的迁移打破了另一条车道（乃至更早批次）的测试，
而它们各自单跑都是绿的** —— 单跑时对方的表根本不存在。

### 决定性旁证：同批的 V219 用了另一种做法，所以它没打断任何人

`V219__return_shipback_to_origin.sql` 同样要引用 `return_disposition_order`（10.7.1.1 的表），
但它**没有建外键**，而是用触发器 `guard_return_shipback_line_order` 在写入时校验
（触发器里 `RAISE EXCEPTION 'Return shipback line % references a cancelled disposition order'`）。
所以 `ReturnDispositionIntegrationTest`（同样 TRUNCATE `return_disposition_order` 且**没写 cascade**）**全绿**。

⇒ 同一个批次里，两种「引用上游」的做法产生了两种结局：**建外键 ⇒ 打断别人的 TRUNCATE；
用触发器 ⇒ 不打断。**

### 为什么没有 `git bisect`

协议允许在 merge commit 上二分（最多 2 次全量验证）。本轮**没有二分**，理由是证据已经是决定性的：

- PG 报错**直接点名了具体的表与具体的外键方向**（`logistics_exception_case` → `logistics_tracking_event`）；
- 被引用的 `logistics_exception_case` 表由**本批**的 V216 创建；
- 失败清单与「哪些测试 TRUNCATE 了这三张父表且没写 CASCADE」**逐条对得上**，无一例外。

二分要花 2 次全量验证（本机实测一次 34 分钟～1 小时 23 分，取决于构建缓存冷热），
只会再次指向上面这个已经由报错原文确认的 commit。与 batch-36 的同一判断保持一致。

### 修复方案：给 truncate 补 `cascade`（不改迁移、不改业务代码）

在独立修复车道 `.worktrees/fix-batch37`（分支 `fix/batch-37-l2-green`）上修，
**只改 7 个 channel 测试文件的 truncate 语句**：

- 必改 3 个（当前就是红的）：`HandoverBatchIntegrationTest`、
  `LogisticsPickupSignoffIntegrationTest`、`LogisticsPickupSignoffTenancyIntegrationTest`；
- 顺带加固 4 个（当前是绿的、但属同一类隐患）：
  `ReturnDispositionIntegrationTest`、`ReturnDispositionTenancyIntegrationTest`、
  `ReturnShipbackIntegrationTest`、`ReturnShipbackTenancyIntegrationTest`。

**否决的方案与理由**：

1. **否决「删掉 V216/V217 的外键」**（照抄 V219 的触发器做法）——
   ① 外键是 10.3.1.2 / 10.3.2.2 **明确写进会话日志的设计决策**（租户作用域内的复合外键是完整性保证），
   删掉会削弱引用完整性；② 改**已并入集成头**的迁移文件会造成 **Flyway 校验和（checksum）不匹配**，
   比测试红更麻烦；③ 本仓既有正确写法就是 `cascade`（同目录 3 个兄弟类都这么写）。
2. **否决「把子表名补进 truncate 清单」**（PG 给的第一条建议）——
   那是打地鼠：下一条车道再加一个外键，又会红一次。`cascade` 一次性把这类问题封死。
3. **否决「在集成头上直接改」** —— 协议明确「L2 红了不在集成头上继续改」，
   修复必须走独立车道，改完并入再重跑 L2。

## 本轮调度上的一个决定：L2 跑在**派发之前**

上一轮（batch-36）的实测教训：那次 L2 是**与 4 条 worker 车道并发**跑的，
**PG 峰值 41、load 峰值 63**，并因此产生了一例假红
（`ImportWizardIntegrationTest` 的跨时钟域断言）。

⇒ 本轮改成 **先跑 L2、再派发新车道**：
L2 跑的时候机器上**一条在途车道都没有**（`lanesRunning=0`、Testcontainers 容器数 0、load 1.33），
所以本轮 L2 的 39 例 Errors 是**真红**，不是资源争抢出来的假红。
派发放在 L2 绿之后，新车道从零开始写代码，L2 的发热高峰与它们的 L1 天然错开。

⚠️ **代价**：本轮从 18:00 跑到约 19:1x，比「派发 + 立即 L2」的排法长。
但换来的是**每轮只有一次可信的全量验证**，而不是一次可能带假红的全量验证。

## 本批派发的 4 条新车道

L2 绿之后派发（`plan --lanes 4`，迁移版本号由编排脚本预分配）：

| 模块 ID | 名称 | 阶段 | L3 | 服务 | 迁移版本 | 分支 |
| --- | --- | --- | --- | --- | --- | --- |
| 15.2.2.1 | Request a Review（索评） | D7 | 15.2.2 | crm | **V220** | `feat/15-2-2-1` |
| 10.7.1.3 | 弃置与销毁 | D5 | 10.7.1 | channel | **V221** | `feat/10-7-1-3` |
| 5.5.2.1 | 异常告警 | D6 | 5.5.2 | catalog | **V222** | `feat/5-5-2-1` |
| 5.2.1.2 | 到岸成本 | D6 | 5.2.1 | catalog | **V223** | `feat/5-2-1-2` |

四条车道**互不依赖**（L3 锚点各不相同），且上游都已交付：
- 15.2.2.1 的上游 10.3.2.2 签收（**本批刚并入**，channel，V217）—— 跨服务，走端口；
- 10.7.1.3 的上游 10.7.1.1 退货接收与处置决策（channel，V214）—— 同服务，可直接读；
- 5.5.2.1 的上游 5.2.1.1 成本构成（catalog，V215）—— 同服务，可直接读；
- 5.2.1.2 的上游 6 采购 / 10 头程 / 16 税务参数 —— 全部跨服务，走端口。

### 本批任务书里新增的一条纪律：外键纪律

四份任务书里都写进了**上一批刚踩的坑**：

> 上一批（batch-37）有一条车道在迁移里给既有表加了 FOREIGN KEY，
> 结果打破了同服务既有测试的 `TRUNCATE` ⇒ 39 例 Errors ⇒ L2 直接红。
> ⇒ **优先用触发器做引用校验，而不是数据库外键**（同批 V219 就是这么做的，它没打断任何既有测试）；
> 若确实要建外键，**必须同车**把引用你新表的那些既有测试的 `truncate` 补上 `cascade`，
> 且只许改 truncate 语句、不许改断言。

这条纪律的价值在于：**它把本轮花 1 次全量验证 + 1 个修复车道 + 1 次重跑才定位到的坑，
前置成了新车道开工前就知道的约束** —— 下一批的 4 条车道不会再犯同一个错。

## ✅ L2 第二轮（修复并入后）：**绿**

修复车道 `fix/batch-37-l2-green`（commit `12c245b5`）并入集成头（merge commit `789f0db4`）后重跑 L2，
**六个阶段全绿**：

| 阶段 | 退出码 |
| --- | --- |
| `backend && ./mvnw verify` | **0** |
| `pnpm typecheck` | **0** |
| `pnpm lint` | **0** |
| `pnpm test` | **0** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **0** |
| `node tools/module-progress/sync.mjs --check` | **0** |

后端 reactor（**8/8 模块全过**，第一轮被跳过的 warehouse / trade / crm 这次全部跑到）：

```
backend ....... SUCCESS [  0.000 s]
tenancy ....... SUCCESS [  0.331 s]
identity ...... SUCCESS [01:16 min]
catalog ....... SUCCESS [02:23 min]
channel ....... SUCCESS [08:12 min]     ← 第一轮在这里红（39 例 Errors）
warehouse ..... SUCCESS [14:06 min]     ← 第一轮被 SKIPPED
trade ......... SUCCESS [14:25 min]     ← 第一轮被 SKIPPED
crm ........... SUCCESS [  6.616 s]     ← 第一轮被 SKIPPED
BUILD SUCCESS
```

逐模块 surefire 汇总：

| 模块 | 测试数 | 失败 | 错误 |
| --- | --- | --- | --- |
| identity | 270 | 0 | 0 |
| catalog | 1527 | 0 | 0 |
| channel | 1086 | 0 | 0 |
| warehouse | 1535 | 0 | 0 |
| trade | 1494 | 0 | 0 |
| crm | 18 | 0 | 0 |
| **合计** | **5930** | **0** | **0** |

前端：**318 个测试文件 / 5031 例全过**；Next production build **Compiled successfully**、
**214/214 静态页**生成。

⇒ **本批 L2 两轮跑下来：真红 1 次（39 例 Errors，根因已定位到具体的两个外键），
修复后 5930 + 5031 例全绿。这正是组批模式存在的意义** ——
这 39 例 Errors 在任何单条车道的 L1 里都不可能出现（L1 跑的时候对方的表根本不存在），
只有「整组并入后跑一次全量」才抓得到。

## 修复车道的 L1 结果（7 个类全绿）

`./mvnw -pl services/channel -am test -Dtest='…7 个类…'` → **BUILD SUCCESS**，
**Tests run: 100, Failures: 0, Errors: 0**：

| 测试类 | Errors（修前 → 修后） |
| --- | --- |
| `HandoverBatchIntegrationTest` | 14 → **0** |
| `LogisticsPickupSignoffIntegrationTest` | 22 → **0** |
| `LogisticsPickupSignoffTenancyIntegrationTest` | 3 → **0** |
| `ReturnDispositionIntegrationTest` | 0 → **0**（加固） |
| `ReturnDispositionTenancyIntegrationTest` | 0 → **0**（加固） |
| `ReturnShipbackIntegrationTest` | 0 → **0**（加固） |
| `ReturnShipbackTenancyIntegrationTest` | 0 → **0**（加固） |

`pnpm typecheck` exit 0、`pnpm lint` exit 0。
另查了 `spotless:check`（CI 的 gate）：本机默认 JDK 25 跑不了 `google-java-format` 1.22.0（需 JDK 21），
换 JDK 21 跑出来**集成头基线上本就有 239 个文件违反 gjf 格式** —— 但修复车道用 `git stash` 对比确认
**改动前后都是 239 个**，即本次改动对 Spotless **零影响**（没有把任何文件从「合规」变成「不合规」）。
这 239 个文件的存量债单独记入 backlog，不混进业务批次。
