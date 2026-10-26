# 2026-10-24 · batch-87 —— 回收 3 条车道并入集成头 + 派发 2 条新车道

> 批次号 87（承接 `batch-86`，取号时两次确认目录内最大为 `batch-86`）。
> 集成头 `integration/module-dev`，本批并入 3 条车道：**1.1.3.2 / 1.3.1.1 / 16.3.1.2**。

## 本批概览

| 项 | 值 |
| --- | --- |
| 回收并入 | 3 条（1.1.3.2、1.3.1.1、16.3.1.2）—— `merge-ready` 全部成功，**零冲突** |
| 新迁移 | `V371__filing_calendar.sql`（channel）、`V373__notification_inbox.sql`（identity）；1.1.3.2 无迁移 |
| 后端服务 | channel（16.3.1.2）· identity（1.3.1.1）· 前端聚合层（1.1.3.2，**后端零改动**） |
| 台账 | 392 → **395 已交付**，未开发 73 → **70**，465 行完整性校验通过、无重复 ID |
| 静态预检 | ①`ADD COLUMN` 无 DEFAULT、②`SET NOT NULL`、③`ADD CONSTRAINT CHECK` —— **三项均零命中** |
| L2 批次闸 | 全量 6 步，`MVN_EXIT=0 / TYPECHECK_EXIT=0 / LINT_EXIT=0 / TEST_EXIT=0 / BUILD_EXIT=0 / SYNCCHECK_EXIT=0 / ITEST_EXIT=0 / CODEGEN_EXIT=0 / CONTRACT_GENERATE_EXIT=0 / CONTRACT_GATE_EXIT=0 / CONTRACT_TREE_DRIFT_EXIT=0` → **L2 GREEN**（18:04:05 起，19:05:13 结束，日志 `.dev-agent/l2-run-batch-87.log`） |
| 派发 | **2 条**新车道（`--lanes 4` 但编排器只排得出 2 条，原因见「排不出 4 条车道」一节） |
| 未并入 | 12.3.2.1「对账任务」在本轮 L2 跑批期间（18:45:26）落地 ⇒ 按「集成头只接受绿批次」留到下一轮 |

---

## 模块 1.1.3.2 资金与回款风险

**这个功能做什么**：在工作台上看到**钱在哪里、有没有风险**——应收账龄分布、平台待结算金额、
逾期应收清单、即将到期的供应商应付、在途资金占用。每个数字都能点进 12 财务的明细去处理。

**技术落地**：

- 迁移：**无**。卷 01 是只读投影层，「不产生新的业务事实，只聚合各系统的投影」⇒ 派生值落库只会比它描述的资金更早过期。
- 端点：**零新增**。纯前端聚合层，复用 **6 个上游只读端点**（全部已在基线 api-proxy 白名单里，本车道用脚本逐条实测命中，不是目测、不是 `grep -c`）：
  `GET finance/receivable-aging/distribution`（12.1.1.2）· `GET finance/receivable-aging/overdue`（12.1.1.2）· `GET finance/settlement?status=PENDING` 与 `?status=PARTIAL`（12.3.1.1）· `GET finance/payables/unpaid`（12.1.2.1）· `GET finance/deferred-settlement/summary`（12.3.1.3）· `GET warehouse/inventory-risk/projection`（07 库存）。
- 页面：`/dashboard/fund-payment-risk` —— 账龄分布图 + 逾期清单 + 待结算金额 + 应付到期 + 在途占用；筛选维度含**币种**、**即将到期窗口（3/7/15/30 天，默认 7）**、**法人/店铺/仓库范围**；错误态/空态/加载态三态齐全。
- 测试：前端 **54 例**（`fund-payment-risk-model.test.ts` 47 + `fund-payment-risk-allowlist.test.ts` 7，真跑 proxy 断言状态码）；**后端零改动、无新增集成测试类，未启动任何 Testcontainers 容器**。
- 契约：**无变化**（纯前端聚合，未新增后端端点）。

**关键设计决策**：

1. **本模块后端零改动** —— 与兄弟模块 1.1.3.1「库存风险」的差别在于**缺不缺投影**：1.1.3.1 缺（可用量/阈值/预占/在途没有一个端点能一次给全 ⇒ 才在 warehouse 加了投影端点），1.1.3.2 不缺（五类事实全部已在归属服务的既有只读端点里）⇒ 落点就是纯前端聚合层，与同卷 1.1.1.1「我的待办」同一形态。
2. **待结算读两次而不是一次** —— 结算单的 `status` 过滤只吃一个取值，而「待结算」口径是「待到账 + 部分到账」两档 ⇒ 读两次再**按币种**合并。同一币种内相加合法，跨币种相加不合法。
3. **「即将到期」的窗口必须显形** —— 蓝图没给天数 ⇒ 做成界面可调筛选并**始终写在界面上**。窗口只管「未来多久之内到期」，**已逾期的恒计入**（无论窗口多小）——逾期的永远是风险。
4. **库存货值如实报不可判定** —— 蓝图输入写了「07 库存货值」，但 warehouse 与 catalog **全库没有库存成本列**（只有采购报价，不是库存计价，且属跨服务引用），12 财务自己的资金分析也把存货周转天数恒置为 null ⇒ 给**数量**口径的完整拆分，**金额口径显式报不可用并说明原因**。用采购报价冒充库存成本会把账面资产算错，比不给更糟。类型层面写成 `amount: null` 而不是 `number | null` —— 让任何试图填数的代码**编译期就过不去**。
5. **截断时总量判不了，不报部分和** —— warehouse 投影端点分页上限 500 行；只取回一部分却报「总量」就是在报一个假的数 ⇒ 截断时总量/分类量/腿数一律 null，并如实报出「只取回前 N 条（共 M 条）」。
6. **多币种绝不相加** —— `CurrencyRiskSummary` 类型层面**没有**跨币种总额字段；汇率不可得时也不按 1:1 兜底。**「不可判定」≠ 0**：分母为 0、来源读不到、字段缺失一律 null，界面显示「—」并说明为什么。
7. **精确十进制** —— 金额合计与占比全程在 BigInt 缩放整数上做（`0.1 + 0.2 = 0.3`），占比四舍五入到 4 位。（兄弟模块 1.1.3.1 的教训：浮点下 `0.3 ÷ 0.1` 是 2.999…，向下取整会得 2 而不是 3。）
8. **隔离继承自上游，不重复造一份** —— 本模块后端零改动、不建任何表 ⇒ 三条租户隔离断言由 5 个上游模块各自的 Testcontainers PG16 隔离测试证明；前端**不下发租户 id、不下发范围参数**，伪造的 `X-Tenant-Id` 与 `X-Internal-Key` **不被转发**。

---

## 模块 1.3.1.1 通知列表

**这个功能做什么**：一个**通知收件箱**——把缺货、超时、同步失败、对账差异、站内信等各类通知聚到一处，
可以标记已读/未读、按类型订阅；通知内容点进去直接跳到对应业务页处理。

**技术落地**：

- 迁移：`backend/services/identity/src/main/resources/db/migration/V373__notification_inbox.sql` — **两张新表**：
  `notification_read_state`（已读状态，按 `tenant_id` + **`membership_id`**（个人维度）+ `notification_type`（受控词表，CHECK 约束钉死五个码）+ `notification_ref`（上游主键））· `notification_type_subscription`（按类型订阅）。
  两表均 `tenant_id NOT NULL` + ENABLE + **FORCE** RLS + GRANT；**零 ALTER 任何既有表、零 FOREIGN KEY**（跨表引用走触发器，与 V312/V368 同一纪律）。
- 端点：**6 个**，挂 `/identity/notification-inbox`：
  `GET /capabilities`（能力声明：受控类型词表 + 批量上限 + 未交付的相邻模块）· `POST /read-state`（查一批通知的已读状态）· `POST /read`（批量标记已读）· `POST /unread`（批量取消已读，幂等）· `GET /subscriptions` · `PUT /subscriptions`（整体替换）。
- 页面：`/notifications` —— 收件箱工作区；筛选维度含通知类型与已读状态；错误态/空态/加载态三态齐全。
- 测试：后端 **16 例**（`NotificationInboxIntegrationTest` 12 + `NotificationInboxTenancyIntegrationTest` 4，含租户三断言）· 前端 **28 例**（`notification-inbox-model.test.ts` 22 + `notification-inbox-allowlist.test.ts` 6）。
- 契约：`packages/api-contracts/src/contracts/identity.ts` 新增 8 个类型（+8/-0）；`identity.openapi.json` +339 行（codegen 产物，未手改）。

**关键设计决策**：

1. **不新建 `support` 微服务，也不建「通知内容表」** —— 蓝图写死「通知是投影，允许丢弃后重算，不作为业务事实来源」，卷 01 卷首写死「不新增业务服务」，而集成头里没有 `support` 服务。⇒ **通知内容**来自各域**已交付**的授权只读端点、前端聚合；**个人状态**（已读/订阅）落 identity。各域在不同库里，跨库 SQL/FK/视图禁止 ⇒ 通知只带**引用 + 跳转链接**，不复制业务数据。
2. **已读状态按 `membership_id` 而非按租户** —— 已读是**个人**的；各业务服务都不拥有「个人已读状态」这个概念。
3. **不复用 `user_preference`，另建两张表** —— 三条理由：①**形状不匹配**：`user_preference` 的 JSONB 载荷上限 16 KB / 单数组 64 项，读上几百条通知就撞顶，届时已读状态**静默写不进去**（界面表现为「读了就还是未读」）；②**语义不匹配**：`user_preference` 是**整份覆盖**（upsert 同一 page_key），而批量已读是**增量** ⇒ 整份覆盖要先读后写，两个标签页同时操作会互相把对方的已读状态冲掉；③**键受控**：JSONB 载荷做不到在**数据层**钉住类型词表。
4. **刻意不放行的路径** —— api-proxy **不放行** `notification-inbox`（裸路径，后端没有「一条汇总列表接口」）、`notification-inbox/read-state/all`（🔴 刻意没有「把本租户全部通知标已读」这种端点，批量已读只对**点名**的键生效）、`notification-inbox/preferences`（1.3.1.2 未交付）、`notification-inbox/rules`（1.3.2.1 未交付）、`notification-inbox/push`（1.3.2.2 未交付）—— **放行后端不存在的映射比 404 更糟**，界面会以为功能已交付。
5. **白名单条目新增为独立元素，不并进既有 identity 巨型 alternation** —— 本文件是多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里并分支会让多车道并入时 git 必然判冲突。既有条目**一字未改、也不重排** ⇒ 并入集成头时该文件上零冲突面。
6. **首 token 零命中核对是实测的** —— 既有 224 条 `services[]` 正则里含 `notification` 串的只有 2 条（`price-anomaly-alerts/notifications` · `logistics-pickup-signoff/notifications`），都是别的模块的子路径、首 token 不同 ⇒ 互不吃掉。
7. **订阅集合不允许为空** —— 「没自定义过」= 表里没有我的行 ⇒ 生效值 = 全部类型（不需要额外标记位）。但这样「取消勾选最后一项」会让集合为空，而空集合与「没行」无法区分 ⇒ **服务端拒绝空集合（400）**，页面把最后一类复选框**禁用**并说明「至少要订阅一类」。备选方案（存一份「默认订阅全部」的标记位）要多一列 + 多一套语义，收益只是允许「订阅零类」这个无用状态 ⇒ 不取。
8. **已读状态查用 POST 而不是 GET** —— 「我读过哪些」有两种问法：把全部已读记录拉回来（载荷随时间无上界），或把手上这批通知的键拿去问 ⇒ 取后者。**用 POST 是因为键集合可能几百条，GET 的 URL 扛不住这个长度**（几十个键就会顶到代理的 URL 上限）。这是一次**读**操作，但被读的是请求体，不在 URL 上。
9. **严重级别按各来源各自判据推，不统一硬编码阈值重算业务口径** —— warehouse 给 `severity` ⇒ 只做词表对齐（`WARNING`→`HIGH`）；trade 按超期时长；channel 按失败原因（解析失败/映射缺失要人改规则 ⇒ `HIGH`，限流/网络错误 ⇒ `MEDIUM`）；finance 按未结差异条数；crm 按 `priority`。**判不了 ≠ 没有**：推不出严重度时落在 `UNKNOWN`，绝不用 `LOW` 兜底 —— 用 `LOW` 兜底会让它们排到最后然后永远没人处理；时刻缺失同理（「时刻判不了」而不是「刚刚」）。
10. **未读计数与清单同源** —— 概览的未读数由**清单渲染的那一批行**算出（`summarizeNotifications(visible)`），**不另查 count 端点** —— 另查就会与清单口径漂移（清单改了、角标没改）。按类型筛选条上的计数来自**订阅后**的收件箱集合，与概览分开标注（「当前清单 N 条 · 订阅内共 M 条」），不混成两个口径。
11. **「读不到 / 无权限 / 没数据」严格分开** —— **读不到**（服务报错）→ 逐类单列并报出原因；**无权限**（会话里没有该来源的读权限）→ 单独标「对你不开放」且**不去读**那个来源（读了必然 403，而且会把「无权限」说成「服务挂了」）；**没数据** → 才是 0 条。三件事在页面上是三种不同的呈现，任何一种都不显示成别的。

---

## 模块 16.3.1.2 申报日历

**这个功能做什么**：管理各辖区的**申报日历**——申报周期、截止日、所需数据、状态（未开始/数据就绪/已提交/已确认）、
到期提醒与责任人。系统**不替你申报**，只跟踪状态并在到期前提醒；漏报罚款远高于系统能提供的价值，日历提醒是最低成本的合规保障。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V371__filing_calendar.sql` — **三张新表**：
  `filing_calendar_cycle_config`（申报周期配置）· `filing_calendar_entry`（申报日历条目）· `filing_calendar_reminder`（到期提醒）。
  **纯 CREATE TABLE，零 ALTER 零 DROP 零 ADD COLUMN 到任何既有表，零 FOREIGN KEY**（跨表引用一律走触发器，与 V312/V368 同一纪律）；6 个 BEFORE INSERT 触发器；全部 `tenant_id NOT NULL` + ENABLE + **FORCE** RLS + GRANT。
- 端点：**14 个**，挂 `/channel/filing-calendar`：
  `GET /capabilities` · `GET /options` · `GET /cycle-configs` · `POST /cycle-configs` · `PUT /cycle-configs/{id}` · `GET /entries` · `POST /entries` · `POST /entries/generate` · `GET /entries/{id}` · `PUT /entries/{id}` · `POST /entries/{id}/advance` · `POST /entries/readiness` · `GET /reminders` · `POST /reminders/{id}/acknowledge`。
  权限复用 `channel.operation.read / .create / .update`（identity 零改动）。
- 页面：`/compliance/filing-calendar` —— 申报日历视图 + 周期配置 + 条目生成/推进 + 提醒确认；筛选维度含辖区、申报周期、状态；错误态/空态/加载态三态齐全。
- 测试：后端 **43 例**（`FilingCalendarIntegrationTest` 25 + `FilingCalendarTenancyIntegrationTest` 3 三断言 + `FilingCalendarRulesTest` 15 纯函数）· 前端 **27 例**（`filing-calendar-model.test.ts` 22 + `filing-calendar-allowlist.test.ts` 5）· 全量 api allowlist 回归 78 文件 / 393 例。
- 契约：`packages/api-contracts/src/contracts/channel.ts` 新增 18 个 `FilingCalendar*` 类型（+18/-0）；spec 新增 11 条路径、**零删除**。

**关键设计决策**：

1. **「本模块不执行申报」落成结构而不是文案** —— 蓝图 12.6.1.2 写死「本系统提供申报底稿，不替代税务申报动作」，本模块同理。落成三处结构：①`status` 是受控词表（`NOT_STARTED / DATA_READY / SUBMITTED / CONFIRMED`），**DB 触发器钉死单调**（rank 不许下降 —— 倒退 = 漏报被悄悄抹掉）；②推进到 `SUBMITTED`/`CONFIRMED` **必须带依据**（谁/何时/凭据号），触发器钉死「没有依据就推不到已提交」，且**凭据号是用户从税务机构拿到的外部凭据，系统不代造受理号**；③api-proxy 刻意**不放行** `filing-calendar/submit`、`filing-calendar/receipts` —— 放行一个不存在的入口比 404 更糟。
2. **「数据就绪」是投影，不是抄一份底稿** —— 12.6.1.2 的底稿住在 **finance 服务**（另一个库）⇒ 只经授权只读 HTTP API（`GET /finance/filing-draft/drafts`）读，**不跨库 SQL / FK / 视图**。`filing_calendar_entry` **不含任何金额列**：底稿金额留在 finance，日历只存状态 —— 抄金额进来就是两个真值源。
3. **🔴「读不到」与「没就绪」是两个状态** —— 上游不可达 ⇒ `data_readiness_available = false` + 原因，界面显示「读不到」；**绝不显示成「数据没就绪」** —— 前者要去查上游，后者是「查过了，确实没有」，处置动作完全相反。12.6.1.2 只做 VAT / IOSS 两条申报线 ⇒ 其余申报线**判不了就绪**（`available=true` + 「12.6.1.2 未覆盖该申报线」），**不假装就绪**。
4. **辖区不另建码表，引用走触发器** —— 真值源是 16.3.1.1 的 `tax_registration_jurisdiction`，与本模块**在同一个库**（channel）⇒ 直接读、**不复制它的行**；落库前由 DB 触发器钉死 ⇒ 辖区码不是自由文本（服务层与 DB 层各拒一次）。
5. **截止日是当地时刻，不是 UTC** —— 欧盟各国申报截止时间都是当地时间（Berlin 23:59 ≠ UTC 23:59），写死 UTC 会差几小时，而**差几小时就足以错过一个截止日**。周期配置存 **IANA 时区名**（如 `Europe/Berlin`）+ 小时 + 期起始日（1..28，**刻意不允许 29/30/31** —— 短月没有那一天）；申报期以**配置的生效起始日为原点**按周期在月份上排格子。条目**快照** `due_at` + `due_timezone` ⇒ **改配置不改历史条目**（否则「上个月到底哪天截止」会被今天的配置改写，而那正是稽查要看的东西）。读库时 JDBC 会把 `timestamptz` 换算到 JVM 默认时区（本机 +08:00）⇒ 显示口径必须跟着条目自己的时区走，否则德国截止日会显示成「第二天 05:00」，看起来像差了一天。
6. **「配置」与「条目」分两张表** —— `filing_calendar_cycle_config` 是**模板**（怎么算期、怎么算截止日、提前几天提醒），会改；`filing_calendar_entry` 是**那一次申报的事实**（截止日快照、所需数据、状态、责任人），不会改。一条表就分不清「规则」和「这一次的事」，改规则会顺手改掉历史。
7. **责任人 = 租户内成员（跨库软引用 + 落库前校验）** —— 只存 identity 的 `membership` 逻辑 ID，**不建跨库外键、也不复制成员姓名**；落库前经 identity 的授权只读 API 校验它确实是**本租户**的成员；**成员目录读不到 ⇒ 拒绝落库**（503），不存一个查无此人的 id。调用方自己（已认证会话里的 membership）永远合法 —— 目录读不到时至少还能把自己设成责任人。
8. **漏报是高代价事件 ⇒ 排序与截断都要说实话** —— 默认排序把「逾期未提交 / 临近截止未提交」排在最前；逾期判定在**服务端**按 DB 当前时刻算（**不信任客户端时间**）；提醒清单**如实报总数**（「只取回前 N 条（共 M 条，未确认 K 条）」），`unacknowledgedCount` 是**全量计数**，不受分页影响 —— 到期提醒不能被分页吞掉。
9. **提醒是派生行，但「已知悉」是事实** —— `filing_calendar_reminder` 由条目派生两档（提前 / 截止），但确认已知悉（谁、什么时候）会落库，不能每次读都重算一遍。「是否已逾期」**不在表里**：逾期是当前时刻与截止日的关系，读时现算。

---

## 排不出 4 条车道：结构性服务缺口（本轮最重要的一条发现）

本轮 `plan --lanes 4 --dry-run` 只排得出 **2** 条车道（1.3.1.2、1.5.1.2），而不是 4 条。原因不是上游没就绪，而是**主责微服务不存在**：

| 卷 | 阻塞模块数 | 根因 |
| --- | --- | --- |
| 13 利润与分析 | **23** | 主责服务 `reporting` 不在集成头 |
| 18 集成与自动化 | **24** | 主责服务 `platform` 不在集成头 |

当前 70 个未开发模块的完整构成：

| 类别 | 模块数 |
| --- | --- |
| **可派发** | 2（1.3.1.2、1.5.1.2） |
| **服务缺口**（卷 13 + 卷 18） | **47（67%）** |
| 上游未交付 / 蓝图循环依赖（卷 1/5/12/14/15） | 21 |

⇒ **流水线已到结构性天花板**：每批名义上能派 4 条，实际最多派 2 条，且这 2 条派完就只剩服务缺口那 47 个模块在等。
该问题早已登记为 P0：`docs/backlog/2026-10/2026-10-23-pipeline-service-gap-and-blueprint-cycle-blockers.md`
（① 微服务缺口 79 个模块 · ② 蓝图循环依赖 10 个模块 · ③ `lane.mjs` 兜底分支死代码 1 个模块）。
本轮**未改动**该 backlog（引导新服务要改 `backend/pom.xml` / `deploy/docker/*` / `tools/codegen/catalog-contracts.py` / CI 配置，
且**每批只能引导一个服务** —— 同批并行会直接撞车）。

## 静态预检

对本批两个新迁移（V371、V373）跑「已知互相破坏形状」三项扫描：

| 形状 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 无 DEFAULT | **零命中**（V371 唯一一处命中是注释行里的「零 ADD COLUMN 到任何既有表」这句话） |
| ② `SET NOT NULL` | **零命中** |
| ③ `ADD CONSTRAINT ... CHECK` | **零命中** |

补充核对：V371 = 3 × CREATE TABLE + 0 × ALTER TABLE + 6 个 BEFORE INSERT 触发器；V373 = 2 × CREATE TABLE + 4 × ALTER TABLE
（**四条全是 RLS ENABLE/FORCE，作用于它自己新建的表**，无 ADD COLUMN / SET NOT NULL / DROP）⇒ 两个迁移对既有表的回归面 = 0。

## L2 批次闸结果

本批唯一一次全量验证在集成头上跑完（脚本 `.dev-agent/l2-run.sh`，日志 `.dev-agent/l2-run-batch-87.log`）：

| 步骤 | 指标 | 结果 |
| --- | --- | --- |
| [1/6] | 后端 `./mvnw verify` | `MVN_EXIT=0`（后端 **881 例，0 failures / 0 errors**，BUILD SUCCESS） |
| [2/6] | `pnpm typecheck` / `lint` / `test` | `TYPECHECK_EXIT=0` / `LINT_EXIT=0` / `TEST_EXIT=0`（vitest **616 文件 / 8521 例全绿**） |
| [3/6] | Next production build（`NEXT_TELEMETRY_DISABLED=1`） | `BUILD_EXIT=0` |
| [4/6] | `sync.mjs --check` | `SYNCCHECK_EXIT=0` |
| [5/6] | merge-union / codegen 回归 | `ITEST_EXIT=0` / `CODEGEN_EXIT=0` |
| [6/6] | 契约闸（重新生成 + 逐字节比对） | `CONTRACT_GENERATE_EXIT=0` / `CONTRACT_GATE_EXIT=0` / `CONTRACT_TREE_DRIFT_EXIT=0` |

⇒ **L2 GREEN**（18:04:05 起，19:05:13 结束，共 61 分钟）。静态预检三项（`ADD COLUMN` 无 DEFAULT /
`SET NOT NULL` / `ADD CONSTRAINT CHECK`）**零命中**。

⚠️ **本轮 L2 只覆盖本批并入的 3 条车道。** 车道 12.3.2.1「对账任务」在本轮 L2 **跑批期间**（18:45:26）落地，
按「集成头只接受绿批次」的纪律**本轮不并入、留到下一轮**——它将由下一轮 tick 的 L2 覆盖后再 push。

## 本轮派发

本轮另派发 **2 条新车道**（后台跑，落地后由下一轮 tick 回收并入）：

| 模块 ID | 名称 | 阶段 | L3 | 迁移版本号 | 分支 | 后端归属 |
| --- | --- | --- | --- | --- | --- | --- |
| 1.3.1.2 | 通知偏好 | D7 | 1.3.1 | `V374` | `feat/1-3-1-2` | identity（个人偏好） |
| 1.5.1.2 | 数据一致性体检 | D7 | 1.5.1 | `V375` | `feat/1-5-1-2` | 前端聚合层（跨域只读投影） |

两条车道互不依赖（L3 锚点不同），迁移版本号不重叠。⚠️ 1.5.1.2 的上游写了「12.3.2 对账差异」，
而 12.3.2.2 / 12.3.2.3 尚未交付（12.3.2.1 对账任务在本轮末才落地）⇒ 该车道的 10 分钟依赖自检若判定上游缺失会直接回报 `BLOCKED`，
这是编排器的既定行为，不是缺陷。

⚠️ **派发前做了一次基线修正**：`lane.mjs plan` 对「分支已存在」的复用逻辑只复用分支、**不重新切基线**
（batch-86 已取证过同一个缺陷），导致 1.5.1.2 停在旧基线 `1e9ab421` 上 ——
比集成分支**少 32 条已并入车道**，**api-proxy 白名单少 3 条元素**（共享白名单取错一侧会静默删掉别人已交付的路由）。
该分支当时**没有任何独有提交**（`git rev-list --count integration/module-dev..HEAD` = 0）、工作区干净
⇒ `reset --hard` 到集成分支是**无损**的，已执行。两条车道现在基线统一为 `162bd188`，
`apps/web/src/lib/api-proxy.ts` 均为 11965 行。

两份任务书落在 `.dev-agent/taskbooks/{1.3.1.2,1.5.1.2}.md`（该目录不进版本库，权威副本是
`lanes.json` 的车道记录 + 各车道的分支内容）。派发前已逐字检查：**无残留 `<...>` 占位符**。

## 遗留待办（沿用上一轮结论，本轮未改动）

- **微服务缺口（P0，仍是停机的头号阻塞）** —— 卷 13 `reporting`（23 模块）与卷 18 `platform`（24 模块）
  的主责服务不在集成头，**47/70（67%）的未开发模块排不出车道**。详见
  `docs/backlog/2026-10/2026-10-23-pipeline-service-gap-and-blueprint-cycle-blockers.md`。
  引导新服务要改 `backend/pom.xml` / `deploy/docker/*` / `tools/codegen/catalog-contracts.py` / CI 配置，
  且**每批只能引导一个服务**（同批并行会直接撞车）。
- **蓝图循环依赖（10 个模块）** —— 卷 05 与卷 15 各有一对互为上游的模块（5.1.1.1 ↔ 5.1.2.1 · 15.3.1.1 ↔ 15.3.1.3），
  编排器的 `upstreamReady` 判 `every(u => delivered.has(u))` ⇒ 两对永远互锁。需人工确认后改蓝图「关系」字段。
- **Flyway 版本倒挂（P1，不影响 L2，影响部署）** —— `V366` 低于集成分支已应用的 `V367`，
  存量库跑 `flyway migrate` 会报 `Detected resolved migration not applied to database: 366`（`outOfOrder=false`）。
  见 `docs/backlog/2026-10/2026-10-24-flyway-out-of-order-version-inversion.md`。
- **`lane.mjs` 闸龄判据 / 孤儿 keepalive 守护** —— 见
  `docs/backlog/2026-10/2026-10-24-orphan-keepalive-daemon-gate-deadlock.md`。

