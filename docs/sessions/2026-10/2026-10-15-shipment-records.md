# 2026-10-15 · 9.4.1.2 发货记录（阶段 D4）

## 决策摘要

**本模块是「已发货」这个事实的登记处，这是 9.4.1.1 留给它的位置。**
9.4.1.1（V124 文件头第四节）的状态机刻意最远只到 `HANDED_OVER`、**从不产生 `SHIPPED`**，
并写明理由：「已发货是一个由 8.3.4.1 在承运商揽收后确认的事实，本模块提前置它，
会让 9.4.1.2 的发货记录与 17 绩效的迟发率算在一个从未发生的时刻上」。
本模块照这条约定落地：已发货事实的**唯一来源**是 8.3.4.1 已确认交接的交接单
（`status=HANDED_OVER`）——那一档才同时写死了承运商、主运单号、交接人、交接时刻，
并在同一事务里完成实发扣减。

**命名空间与端点刻意与 9.4.1.1 并列，不嵌套。**
`/trade/fulfillment/shipment-records` 与 `/trade/fulfillment/pending-shipment` 并列。
把已发货挂到待发货之下，会让人以为「待发货推进一下就成了发货记录」——
而那正是 9.4.1.1 拒绝做、会让迟发率算在从未发生的时刻上的那一步。
白名单测试专门反向钉住了这一点（`不存在把待发货推进成已发货的形状`）。

**三列状态登记而不伪造，三个 UNSET 独立成桶。**
蓝图职责句点名七样东西，其中轨迹状态、买家可见状态、平台回传状态在集成头里
**都没有可读上游**（10.3 未开发；9.4.2.1 与 2.5.1.1 未开发；买家可见无可读上游）。
照抄 9.4.1.1 已验证过的 SLA 做法：独立 UNSET 桶、登记有主体有时刻有来源、
凭据行 append-only、**快照列由 DB 触发器钉死必须等于最新凭据行**。

## 关键 trade-off

1. **跨服务只读 join（9.4.1.1 的 `shipment_pending_unit`）而不是跨服务 FK。**
   迟发率需要「平台要求的发货时限」，该列在 trade 服务里唯一的真实来源是
   9.4.1.1 的投影。同服务同库**只读 SELECT join**（无 FK / 无触发器 / 无写入 /
   不 ALTER 对方表），且把 `sla_deadline_at` 冗余成**快照**进本表——
   迟发率要按**发货当时**的时限判定，事后再改时限不能让一笔已经迟发的货变成准时。
   代价：9.4.1.1 还没同步过这批货时这些列留空，如实计入 `recordsWithoutOrderSnapshot`。

2. **订单快照允许为空，不编占位值。**
   8.3.4.1 的交接单压根没有销售订单 / 店铺 / 买家 / 收货国。
   同步**不因此失败**（发货事实不依赖它），只如实报数。

3. **服务层不抄状态迁移白名单，唯一权威是 DB 触发器。**
   最初在 `changeStatus` 里多写了一份「目标状态是否合法」的判断，结果把 `VOID`
   （唯一的逃生舱）拦掉了。删掉之后：迁移白名单只有 DB 触发器一处，
   绕过服务层写 SQL 同样被拒；服务层多抄一份，两处就会各自漂移，
   最后出现「界面点不动但脚本能写」的裂缝。

4. **绩效口径写在服务端一处（`GET /performance`）。**
   迟发率的分母**只算登记过时限且非作废**的记录；有效追踪率要求**同时**有主运单号
   与至少一条轨迹观测。两个口径**全程不碰墙上时钟**，只比对已落库的两个时刻，
   同一夹具任何钟点跑结果都一样（测试里时刻全部 pin 死，不构造被断言的
   `now()` 字段，否则该用例只在某个钟点通过，批次调度会随机引爆）。

5. **「跳过」与「漏了」在四层都分开**（枚举、DB CHECK、接口报错文案、界面标签与配色）：
   `SKIPPED`（决定不回传，note 必填）vs `UNSET`（压根没登记 = 漏了）；
   以及 `UNSET`（没登记）vs `NO_TRACE`（登记过、承运商明确说没有轨迹）vs `EXCEPTION`。

## 文件清单

新增（后端 trade）

- `backend/services/trade/src/main/resources/db/migration/V127__trade_shipment_records.sql`
  —— 六张表 + 序列 + 触发器 + RLS/GRANT；**零 ALTER 既有表**
- `backend/services/trade/src/main/java/com/starcon/trade/ShipmentHandoverQueryPort.java`
  —— 读 8.3.4.1 的跨服务只读端口
- `backend/services/trade/src/main/java/com/starcon/trade/ShipmentRecordModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ShipmentRecordService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ShipmentRecordController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/ShipmentRecordIntegrationTest.java`
  —— 19 例，实跑 Testcontainers PostgreSQL 16，连接池压到 1

新增（前端）

- `apps/web/src/components/orders/shipment-records-model.ts`
- `apps/web/src/components/orders/shipment-records-model.test.ts`（15 例）
- `apps/web/src/components/orders/shipment-records-workspace.tsx`
- `apps/web/src/app/orders/fulfillment/shipment-records/page.tsx`
- `apps/web/src/app/api/trade/shipment-records-allowlist.test.ts`（6 例）

改动（各自一行 / 只碰自己那一行）

- `apps/web/src/lib/api-proxy.ts` —— trade 段 `fulfillment/shipment-records` 一支（净增 150 字节）
- `packages/api-contracts/src/contracts/trade.ts` + `trade.openapi.json` —— codegen **纯新增** 21 个 schema
- `docs/product/capability-blueprint/module-progress.md` —— 只改 `| 9.4.1.2 |` 那一行

## 踩过的坑（留给下一个人）

- **索引名不能与同 schema 的表同名。** `CREATE INDEX shipment_record_visibility` 与后面
  的表 `shipment_record_visibility` 撞名，PostgreSQL 直接顶回
  `relation "shipment_record_visibility" already exists`。改名 `shipment_record_buyer_visible`
  / `shipment_record_platform_callback`。同理 `shipment_record_callback` 也会撞。
- **SQL 占位符与绑定值个数必须对得上**，`?::timestamptz` 那个 `?` 也占一位。
  多传一个 `String` 会让后面的 `where id = ?` 变成 `uuid = character varying`——
  报错信息离病因很远，逐列数一遍比读堆栈快。
- **`HandoverDetail` 的 `parcels` 与 `order` 平级**，不是嵌在 `order` 里。
  测试替身里嵌错一层，逐箱运单号会整段丢失，而一票多箱的有效追踪恰恰按箱走。
- **jOOQ 的 `fetchValue(sql, Class, args)` 在本仓库会把 Class 当成绑定值**，
  标量读一律 `fetchOne` + `get(0, Type)`（与 warehouse 的既有做法同口径）。
- **`sql.execute` 的参数个数与 `?` 严格一一对应**；多一个少一个都不会在编译期报。

## 后续待办

- **10.3 物流轨迹落地后**：`CARRIER_FEED` 来源已在 DB CHECK 里预留，
  接上后把 `shipment_record_tracking_observation` 的写入方从「人工登记」换成
  承运商回调，`tracking_status` 快照规则不必改（它已经取最近一条观测）。
- **9.4.2.1 / 2.5.1.1 平台回传落地后**：`CHANNEL_SERVICE` 来源已预留；
  接上后本模块的 `platform_callback_status` 应改为由下游回执驱动，
  而不是由人工登记 —— 现在的人工登记是**过渡形态**，不是最终形态。
- **买家可见状态**目前完全靠人工登记；2.5 店铺/平台对接域落地后应有真实读数来源。
- **平台绩效模块（17）**可以开始消费本模块的 `/performance` 口径；
  口径定义在本模块一处，17 侧**不要**另算一份迟发率。
- **有效追踪率**目前天然偏低（几乎所有记录都没有轨迹观测），
  10.3 落地前这个指标只宜作趋势观察，不宜直接对平台对标。
