# 2026-10-21 11.4.1.1 平台纠纷列表：超期自动判败的落点

## 决策摘要

实现能力蓝图 11.4.1.1「纠纷列表」（卷 11 售后与退货 / L3 11.4 平台纠纷与客诉，
阶段 D7，锚点 11.4.1），归属后端 **trade** 服务（卷 11 主责服务 = trade + finance + warehouse
取第一项；同族先例 11.2.3.3 供应商索赔也在 trade）。

蓝图原文：

- 职责：管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、速卖通纠纷）：类型、金额、平台时限、
  举证要求、我方立场、结果记录。
- 输入：平台纠纷事件/同步；责任判定；举证材料。
- 输出：纠纷单 + 时效倒计时 + 举证清单。
- 关系：上游 = 2 平台同步、15 客服；下游 = 11.4.1.2 申诉、12 财务（争议金额影响结算）。
  **平台纠纷有严格举证时限，超期自动判败，是硬性时效要求。**

### 1. 「超期自动判败」落成了什么结构

**机制：DB 侧触发器（守卫①），不是服务层的软判定。**

- `platform_dispute.evidence_deadline timestamptz NOT NULL` —— 真实时刻列，
  不是文本、不是「7 天」这种相对描述。
- 守卫① `guard_platform_dispute_deadline` 挂在 **`BEFORE INSERT OR UPDATE`**
  （刻意不是 `UPDATE OF dispute_status`）：只要 `evidence_deadline <= now()`
  且**尚未提交举证**，就把 `dispute_status` 强制改写成 `RESOLVED_LOST` +
  `resolution_code='DEADLINE_EXPIRED'` + `resolved_at=now()`。
  ⇒ **任何一次写入**（哪怕只是更新一句备注）都会触发判负，绕过服务层直连 SQL 也一样。
- 同一函数的**前半段**：举证提交时刻晚于截止时刻 ⇒ 直接 **RAISE**（超期后不可再提交举证）。
- 守卫②：举证清单在超期后不可再改。
- 守卫③：**举证时限只能收紧不能放宽**（放宽等于给自己续命）。
- 守卫⑨：建单只能从「已立案」开始（补上状态机在 INSERT 上的盲区，
  否则直连 SQL 能凭空插一条「我方胜诉」）。

**倒计时是派生态**：库里**没有**「剩余小时数」列 —— 那一列写下去就开始自己过期。
剩余秒数与时限状态在**读取时刻**由 `evidence_deadline - now()` 算出。
测试直接查 `information_schema.columns` 断言没有这一类列。

**测试怎么断言**（PlatformDisputeIntegrationTest，27 例实跑 Testcontainers PG 16）：

| 断言 | 做法 |
| --- | --- |
| 超期自动判败**不需要任何人工动作** | 缩短截止时刻（DB 守卫③ 允许收紧）→ **读一次列表**即见 `RESOLVED_LOST`；再直连 SQL 查那一行本身也是败诉、`resolved_by` 为空、留痕里有「举证超期，自动判负」 |
| 建单即已超期当场判负 | 用过去的截止时刻建单 → 响应直接是 `RESOLVED_LOST` |
| 超期后不可再提交举证 | 服务层 400（写之前就拦）+ **直连 SQL 塞一个晚于截止时刻的提交时刻被 DB 拒** |
| 超期后举证清单不可再改 | 服务层拒 + 直连 SQL 改清单被 DB 拒 |
| 举证时限只能收紧不能放宽 | 直连 SQL 放宽 ⇒ DB 拒 |
| 按时提交举证后不再受截止时刻约束 | 提交后 `deadlineState=RELEASED`；「按时提交了却因超期被判负」是另一条假事实 |
| 倒计时是派生态 | `information_schema` 断言无 `remaining/countdown/hours_left/days_left` 列；剩余秒数 ≈ 截止时刻 − 现在 |

**服务层只做「读时收口」**：`expireOverdueDisputes()` 用与守卫① **完全相同**的判据
（`evidence_deadline <= now() and evidence_submitted_at is null`）在读列表 / 详情前
把结论显形并写回 —— 否则「自动判负」要等下一次**别的**写入才会显形，界面上会一直显示
「举证准备中」。**最终结论由 DB 写进那一行**（状态、结果码、结果时刻全部由触发器落），
服务层不自己判一遍。

### 2. 平台同步上游缺失

grep 取证：channel 服务**零** `dispute` 命中（已交付的是平台目录 / 连接 / 店铺 / 同步任务 /
同步运行 / 回写 / webhook / 增量补拉）；crm 服务只有索评与知识库，**客服工单 / 客户模块
（15.1.x / 15.3.x / 15.4.1.x）全部未开发**。

⇒ **不假装有个平台推送接口，也不 mock 一个「Amazon 拉到的纠纷」**：
本模块提供**自己的建单入口**（授权 API `POST /trade/platform-disputes`），
纠纷由平台侧或人工登记，平台来源信息以**快照**落库
（平台码 + 平台名 + 平台纠纷号 + 平台订单号）。
平台主数据在 channel 独立库 ⇒ **禁止跨服务 FK / 跨库视图**，平台码用 CHECK 钉死形状
（照抄 catalog V145 的 `^[A-Z0-9][A-Z0-9_-]{0,31}$`）。
「与 channel 自动同步的衔接」作为**下游缺口**写进能力声明
（`caps.platformSyncGap`），前端也在「模块边界」卡片里如实显示。

### 3. finance 下游缺失

`backend/services/` 下只有 identity / catalog / channel / warehouse / trade / crm
—— **finance 服务不存在**。

⇒ 「争议金额影响结算」照抄本仓先例 11.2.3.3 的 `supplier_claim_settlement`，
落成 **trade 侧自有事实记录** `platform_dispute_settlement`：
登记金额（`requested_amount`）与生效金额（`effective_amount`）**分两列**，
登记时生效**恒为 0**；表级 CHECK 钉死「未 CONFIRMED ⇒ effective = 0；
CONFIRMED ⇒ effective = requested 且确认人 / 确认时刻 / 财务单据号三样齐全」；
纠纷单头上的口径由守卫④ **从分录重算**（直连 SQL 也写不进假的「已生效」）；
已确认的分录整行冻结。财务确认用**独立权限码** `trade.aftersales.finance`。
最终对账留待 finance 落地后经**授权 API / event** 衔接。

## 关键 trade-off

1. **DB 侧强制判负，不做「服务层读到过期就返回个过期状态」的软判定。**
   软判定的问题是：绕过服务层直连 SQL 写进去的那一行仍然是「举证准备中」，
   而这行是要拿去跟平台对质的凭据。DB 触发器让「超期即败」在任何路径下都成立。
2. **触发器挂在 `BEFORE INSERT OR UPDATE`（全部列），不是 `UPDATE OF dispute_status`。**
   代价是每次写都过一次判据；收益是「顺手更新一下备注」也会把超期单据判负 ——
   超期的纠纷没有「再等等看」这条路径。
   连带后果：留痕触发器也只能挂 `AFTER INSERT OR UPDATE`（否则自动判负**没有留痕**）。
3. **举证时限只允许收紧。** 平台缩短时限与时钟走到时限，在库里是同一件事。
   代价：平台真的延长时限时改不了 —— 但那正是「给自己续命」的入口，
   要改只能另立一件纠纷。宁可少一个口子。
4. **建单时若截止时刻已过，当场判负而不是拒收。** 拒收会让「补录一件已经超期的纠纷」
   这件正常业务做不了；当场判负既保留了记录，又没有放过它。
5. **结算状态拆成两个枚举**（`PlatformSettlementStatus` = 分录口径 /
   `PlatformSettlementState` = 纠纷单头口径），**刻意不合并** ——
   合并就会出现「分录是 CONFIRMED，头上的状态也叫 CONFIRMED」这种两处都读得通、
   却在同一张单据上出现两个答案的口径。
6. **前端倒计时每秒由绝对截止时刻现算**，不用接口返回的 `secondsRemaining` 快照 ——
   快照是「请求那一刻」的，页面停着不动就会慢慢变成假数据。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V265__platform_dispute.sql`
  —— 四张新表 + 十个守卫 + RLS + GRANT + 索引，零 ALTER 零 DROP 既有表。
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformDisputeModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformDisputeService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformDisputeController.java`
  —— 15 个 HTTP 映射（14 条路径形状）挂在 `/trade/platform-disputes`；jOOQ 显式 SQL，无 JPA。
- `backend/services/trade/src/test/java/com/starcon/trade/PlatformDisputeIntegrationTest.java`（27 例）
- `backend/services/trade/src/test/java/com/starcon/trade/PlatformDisputeTenancyIntegrationTest.java`（4 例）

**前端**

- `apps/web/src/components/aftersales/platform-dispute-model.ts`
- `apps/web/src/components/aftersales/platform-dispute-workspace.tsx`
- `apps/web/src/components/aftersales/platform-dispute-model.test.ts`（26 例）
- `apps/web/src/app/aftersales/disputes/platform-disputes/page.tsx`
- `apps/web/src/app/api/trade/platform-dispute-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 services[] 元素**（末尾锚点）

**共享生成物（多车道共用，按类型名 / OpenAPI 路径求并）**

- `packages/api-contracts/src/contracts/trade.ts`（+24 类型，-0）
- `packages/api-contracts/src/trade.openapi.json`（schemas +24 / paths +13）
- 其余五份 `contracts/*.ts` 与 `*.openapi.json`：**+0/-0**（JSON 快照里有 10 处
  `maximum: 0` → `maximum: 0.0` 的**数值书写差异**，语义相同、不影响 TS 类型）

**台账**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 11.4.1.1 那一行的
  「状态」「证据」两格（1 行 diff）。

## 后续待办

1. **11.4.1.2 证据与申诉（未开发）** —— 本模块的举证清单是它的事实输入；
   申诉提交与材料上传在它那边落地后，经**授权 API** 接本模块的清单。
2. **channel 纠纷同步（未交付）** —— 落地后经**授权 API / event** 调本模块的建单入口，
   平台来源字段就不用人工填了。
3. **12 财务（finance 服务不存在）** —— 结算影响当前是 trade 侧自有事实记录，
   finance 落地后经授权 API / event 做最终对账。
4. **15 客服工单（未开发）** —— 纠纷与客服工单的关联尚无事实来源。
5. `platform_dispute` 表上**没有**「剩余小时数」列是**刻意的** ——
   若将来有人要求把它落成列以便排序/筛选，应当改用
   `ORDER BY evidence_deadline`（本模块已按截止时刻排），
   不要把派生态重新物化成列。
