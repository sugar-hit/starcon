# 11.1.1.2 售后单详情

- **车道**：11.1.1.2（分支 `feat/11-1-1-2`，基线 `integration/module-dev` @ 85d2eb48）
- **主责服务**：trade（卷 11 L2 表 11.1 售后受理 → trade）
- **阶段**：D4

## 决策摘要

蓝图的职责句点名九个区块，输出句是「详情 + 操作入口 + 时间线」，关系句里有一句加粗：
**「详情页要把"买家说了什么"和"我们做了什么"按时间线对齐，这是纠纷举证的基础。」**
本模块的核心交付因此不是「再做一个查询」，而是**一条对齐的时间线 + 九个区块各自诚实的数据边界**。

### 1. 时间线的对齐靠三个字段，不靠渲染技巧

- 每条事件带 `actorSide`（BUYER / US / PLATFORM / SYSTEM）—— 谁说的。
- 排序键是 `occurred_at` —— **事件发生的时刻**，不是录入库的时刻。
  `aftersales_message` 因此把 `sent_at`（对话里的那一刻）与 `captured_at`（采集时刻）拆成两列；
  填 `now()` 会让一条三个月前的平台消息在举证里显示成「今天买家说的」，而时间线错位在申诉里往往不被采信。
- 十个来源在**一条 SQL** 里 `UNION ALL` 并起来，而不是在 Java 里查七张表再合并 ——
  「先查出来再合并」很容易在某一路失败时静默少一段，而举证时间线少一段比没有更糟。

### 2. 用一个两行的 CTE 把绑定数从 20 降到 2

十个分支各写一遍 `where x.tenant_id = ? and x.case_id = ?` 需要 20 个占位符，
而 **jOOQ 占位符顺序错位不报错**，只会把值绑到别的 `?` 上 —— 最难查的一类 bug。
改成一个只含两行的 `t` CTE（`tid` / `cid`）后整条语句只有两个绑定，顺序一眼可见。

### 3. 排序必须排到全序（这是被测试逼出来的）

同一事务里追加的 6 条备注**共享同一个 `created_at`**（PostgreSQL 的 `now()` 是事务局部的），
所以只按 `(occurred_at, kind)` 排不是全序。时间线是真分页（LIMIT/OFFSET），
排序不全序时翻页可能漏掉一条或重复一条 —— 这正是「假分页」最难被发现的表现。
最终 ORDER BY 为 `occurred_at, kind, reference nulls last, summary, id nulls last`。

### 4. 未交付的下游一律不建影子表

11.1.1.3 受理 / 11.2 退货 / 11.3 退款 / 11.4 纠纷 / 11.1.2.2 归因**当前全部未交付**。
本模块**刻意不建**任何影子表：一张本服务自造的「退款记录」会被当成已对接，
而退货退款是资金动作，认错账就是资损。capabilities 逐条报 `delivered=false` 与归属模块号，
页面逐块显示「该能力未交付 + 归属 + 原因」，`relatedModules[].href` 恒为 `null`
（未交付的模块没有页面可跳，点进去 404 比没有链接更容易让人误以为功能已交付）。

### 5. 沟通记录与凭证照抄 V150 / V156 的既有解法

卷 2 平台消息在 channel 独立库且**当前无任何平台消息表**（11.1.1.1 已实查），跨服务读被模块边界纪律禁止。
照抄 `order_risk_signal.signal_source` 与 `aftersales_owner_assignment.owner_source` 的同一套：
落成**租户可维护的真实表** + `source` 枚举 + `captured_at` 采集时刻；
正式接口到位后同步器按**同一形状**写入并改来源，**表结构与查询都不用改**。
渠道（PLATFORM）与来源（TENANT_MAINTAINED）是**两个维度**：渠道说「在哪说的」，来源说「谁写进来的」，
合成一列会让「我们转录的买家原话」被读成「系统收到的平台消息」。

### 6. 关键 trade-off：只存引用不存字节

凭证只存 `storage_ref`（对象存储定位符），**不存文件内容**。
本服务没有文件服务，把图片 base64 塞进库会让一行凭证把详情响应顶到几十 MB，
而纠纷举证打包要的是**引用**（对象存储 key + 文件名 + 类别 + 提供方），不是字节。
代价：文件服务交付前凭证点不开。已在 capabilities 的 `fileStorageNote` 与页面上写明。

### 7. 关键 trade-off：订单字段不复制

原订单信息直接读 `sales_order` 簇（V99，同库只读），商品行只存 `order_line_id`，
SKU / 单价 / 规格**读 `sales_order_line` 的下单快照**。
复制一份就会与订单漂移，而「截图上的订单号和系统里的不一样」是纠纷举证里最致命的破绽。
代价：详情聚合依赖 JOIN；但全部是同库 SELECT，跨车道 DDL 回归面仍为 0。

### 8. 刻意不加外键，用触发器替代

订单行归属校验（商品行必须**属于本单所关联的那张订单**）由 `guard_aftersales_case_line` 触发器
在**写入时刻**真查一次 `sales_order_line`。加 FK 会让本迁移对别的车道的表产生 DDL 依赖，
而同批另有五条车道也在往 trade 落迁移；触发器只读一张永远存在的表。

### 9. TENANT_SINGLE 这个槽位等了一个人

V156 的 `aftersales_case_note.note_source` 里**已经**留了 `TENANT_SINGLE`，
但 11.1.1.1 只写 `TENANT_BATCH`（它的入口是批量操作）—— 这个槽位在本模块交付前**没有任何写入者**。
详情页的「加备注」正是它的写入者。刻意**不新增枚举值**：V156 是既成事实，本模块是它的读者，读者不该改写者的词表。

## 过程中被测试逼出来的四个真实缺陷（均已修）

1. **11.1.1.1 的 `platform_received_at` 少写 `::timestamptz`**（跨模块一行修复）。
   该列是 `timestamptz` 而 jOOQ 把 `OffsetDateTime` 按 varchar 绑定，PG 报
   「column platform_received_at is of type timestamp with time zone but expression is of type character varying」，
   而且**只在调用方真的传了平台受理时刻时才炸** —— 上游测试恰好传 `null`，所以一直没暴露。
   而平台受理时刻是时效倒计时的**起点**（内部补录的单不能因为晚录入就多算时限），
   这个洞会让它永远写不进去。
2. **`timelinePage` 对未知 / 跨租户 id 会 NPE**：`fetchOne` 返回 null 后直接 `.get(...)`。
   NPE 冒到 HTTP 层是 500，调用方看到「服务器坏了」而不是「这张单不存在或不属于你」。
3. **时间线排序不全序会让分页漏条或重条**（见上）。
4. **时间线计数查询漏传 CTE 的两个绑定** → jOOQ 抛 `DataIntegrityViolation`，
   而 HTTP 层把约束类异常统一映射成 409 —— 一个 GET 返回 409 会让人以为「数据冲突」，与根因毫无关系。

另有一处**跨模块断言的必然翻转**：11.1.1.1 的集成测试与前端 allowlist 测试原先断言
`cases/{uuid}` 返回 404，前提是「11.1.1.2 未交付」。前提不成立，已改为断言放行。
继续断言 404 等于把一条**真的存在**的路由钉成「不存在」，任何人删掉它时测试还会照绿。

## 文件清单

**迁移（1）**
- `backend/services/trade/src/main/resources/db/migration/V157__trade_aftersales_case_detail.sql`

**后端（3 新 + 1 上游一行）**
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesCaseDetailModels.java`（新）
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesCaseDetailService.java`（新）
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesCaseDetailController.java`（新）
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesCaseService.java`（上游：`::timestamptz` 一行）

**后端测试（2 新 + 1 上游断言翻转）**
- `backend/services/trade/src/test/java/com/starcon/trade/AftersalesCaseDetailIntegrationTest.java`（新，15 例）
- `backend/services/trade/src/test/java/com/starcon/trade/AftersalesCaseDetailTenancyIntegrationTest.java`（新，4 例）
- `backend/services/trade/src/test/java/com/starcon/trade/AftersalesCaseIntegrationTest.java`（上游断言翻转）

**前端（5 新 + 3 改）**
- `apps/web/src/app/aftersales/cases/[id]/page.tsx`（新）
- `apps/web/src/components/aftersales/aftersales-case-detail-model.ts`（新）
- `apps/web/src/components/aftersales/aftersales-case-detail-model.test.ts`（新）
- `apps/web/src/components/aftersales/aftersales-case-detail-workspace.tsx`（新）
- `apps/web/src/app/api/trade/aftersales-detail-allowlist.test.ts`（新）
- `apps/web/src/lib/api-proxy.ts`（新增一条独立 services[] 元素）
- `apps/web/src/components/aftersales/aftersales-case-workspace.tsx`（列表行加详情链接）
- `apps/web/src/app/aftersales/page.tsx`（注释随现实更新）
- `apps/web/src/app/api/trade/aftersales-allowlist.test.ts`（断言翻转）

**契约（codegen 产物，只由脚本改）**
- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（仅 11.1.1.2 那一行）
- 本文件

## 验证

- **L0**：`./mvnw -q compile` exit 0；`TURBO_FORCE=true pnpm typecheck` 3 successful，
  **Cached: 0 cached, 3 total**（显式绕过 turbo 缓存，绿不是别的 worktree 的缓存）。
- **L1**：`-Dtest='AftersalesCaseDetail*'` **19 例全绿**（Flyway 实跑 33 条迁移到 **v157**）。
  因改了上游 service，补跑 11.1.1.1 两个类 **34 例全绿**。
- **前端**：`pnpm vitest run apps/web/src/app/api/trade apps/web/src/components/aftersales`
  **204 例全绿 / 32 个文件**（含 api-proxy 白名单**全量 allowlist 回归** —— 改了 api-proxy.ts 的例外条款）。

## 后续待办

- **11.1.1.3 售后受理**未交付：详情页的「受理判定」操作入口当前 `available=false`，
  售后期校验 / 条件判定 / 责任人自动分配 / 回复时限设置四件事仍缺。
  11.1.1.1 的 `POST /cases` 仍是「只做单据落库 + 平台时限登记」的临时入口，应由 11.1.1.3 接管。
- **11.2 / 11.3 / 11.4** 未交付后，`relatedModules` 的 `delivered` 与 `href` 需逐条翻转；
  退货 / 退款 / 申诉的**数据**届时应落在它们自己的服务与表里，**不是**回填进本簇。
- **2 平台消息 / 2 平台同步**到位后，同步器按同一形状写 `MODULE_2_PLATFORM_MESSAGE` /
  `MODULE_2_PLATFORM_MESSAGE` 行，本模块表结构与查询都不用改。
- **11.1.2.2 商品问题归因**到位后，`liability.attributionDelivered` 与
  `relatedModules[ATTRIBUTION]` 需翻转。**在那之前不得编归因结论**。
- **文件服务**交付后由它签发同形状的 `storage_ref`；在此之前凭证点不开（已写明）。
- `sales_order` 若将来补 `shop_code` 列，`platformShopCode` 与
  `platformShopCodeAvailable` 翻转即可，**表结构不用改**。
