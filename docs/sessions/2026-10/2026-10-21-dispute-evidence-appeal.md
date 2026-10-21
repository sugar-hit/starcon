# 2026-10-21 11.4.1.2 证据与申诉：判据只有一份、举证只认系统记录

## 决策摘要

实现能力蓝图 11.4.1.2「证据与申诉」（卷 11 售后与退货 / L3 11.4 平台纠纷与客诉，
阶段 D4，L3 锚点 11.4.1），归属后端 **trade** 服务（沿用上游 11.4.1.1 的归属）。

蓝图原文：

- 职责：管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、速卖通纠纷）：类型、金额、平台时限、
  举证要求、我方立场、结果记录。
- 输入：平台纠纷事件/同步；责任判定；举证材料。
- 输出：纠纷单 + 时效倒计时 + 举证清单。
- 关系：上游 = 2 平台同步、15 客服；下游 = 11.4.1.2 申诉、12 财务（争议金额影响结算）。
  **平台纠纷有严格举证时限，超期自动判败，是硬性时效要求。**

上游 11.4.1.1（V265）已交付 `platform_dispute`（纠纷单）+ `platform_dispute_evidence_item`
（举证清单）+ 三个守卫（超期自动判败 / 超期后不可提交举证 / 时限只能收紧不能放宽）。
本模块在其之上做**申诉**与**举证材料包**。

### 1. 「提交申诉」与库层共用同一个判据

**机制：单个 DB 函数 `platform_dispute_appeal_gate(tenant, dispute, appeal)`（STABLE）。**

- 服务的 Java 层与 DB 触发器守卫① **调同一个函数**，照抄 6.3.1.3
  `purchase_order_approval_match` 的做法（本仓已有先例）。
- 函数给出：窗口状态（`OPEN` / `EXPIRED` / `RELEASED` / `CLOSED` / `UNKNOWN`）、
  是否可提交、清单项数、**必举证项没具备数**、**必举证项没被材料覆盖数**、
  已覆盖数、未覆盖数。
- 判定用 **DB 自己的 `now()`**，函数**不接受调用方传入的时刻**
  ⇒ 服务层与触发器在同一时钟域，不会因 JVM 墙钟与库时钟的偏差误判超期。
- 服务的 Java 层**没有**自己的 `evidence_deadline <= now()` 表达式；
  「必举证未具备数」的判据与 V265 守卫⑥ ② 逐字同款。

测试直接断言这条性质：查 `pg_proc` 确认函数存在；服务层给出的判据与**直连调用该函数**
的输出**逐字一致**；判据说不可提交时**直连 SQL 也被 DB 拒**；判据说可提交时直连 SQL **过得去**。

### 2. 举证材料只来自系统记录

**四类来源，全是 trade 自己的库里已有的事实记录**（同服务同库，无跨库 FK）：

| 来源模块 | 记录 | 语义 |
| --- | --- | --- |
| `SHIPMENT_TRACKING_OBSERVATION` | `shipment_record_tracking_observation` | 物流签收 |
| `AFTERSALES_MESSAGE` | `aftersales_message` | 沟通记录 |
| `AFTERSALES_ATTACHMENT` | `aftersales_attachment` | 商品照片 / 凭证 |
| `QUALITY_INSPECTION_ORDER` | `quality_inspection_order` | 检测报告 |

三条硬约束：

1. **没有「上传任意文件」这一档** —— 端点与前端都不提供；白名单里刻意不放行
   `{id}/upload`、`{id}/files`。
2. **取快照与判存在是同一个 DB 函数** `platform_dispute_appeal_source_snapshot()`
   ⇒ 指向不存在的记录，服务层 400、**直连 SQL 也被 DB 守卫② 拒**。
3. **证据类型由来源记录推导**（附件按 `evidence_kind` 映射、消息是沟通记录、轨迹是物流签收、
   质检单是履约证明）⇒ 调用方**不能自由填类型**，填错类型一律拒。

存储形态 = **逻辑引用 + 快照元数据**：`source_module` + `source_ref_id` + `source_ref_no` /
`source_label` / `source_locator` / `source_occurred_at` / `source_kind`。
**不复制文件本体、不建跨库外键**（照抄 7.4.1.1 的附件做法）。
测试查 `information_schema` 断言库里**没有文件本体列**、**没有「剩余时长」列**。

### 3. 不假装能调平台

**取证结论：channel 服务没有申诉提交入口。**
全服务 grep `dispute` / `appeal`，唯一命中是
`services/channel/src/main/java/com/starcon/channel/ListingLifecycleService.java:327`
的字符串常量 `"block_appeal_workflow"` —— 那是 listing 下架原因标签，不是纠纷申诉。
（`ReturnDisposalController` 的 `/claim-bases/{id}/submit` 属 10.7.1.3 销毁索赔依据。）

⇒ 本模块落**「待提交平台」事实**而不是调端点：
`platform_dispute_appeal_submission` = 目标系统（`CHANNEL`）+ 目标键 + `jsonb` 载荷
+ **幂等键** + 状态机（`PENDING` / `SENT` / `ACKNOWLEDGED` / `FAILED`）+ **重试计数**
+ 下次重试时刻 + 上次错误。
平台侧经**授权 API** 回写：`POST /{id}/platform-reply`（平台回复与结果）与
`POST /{id}/submission-attempts`（一次提交尝试的结果）。
**重试复用同一行**（幂等键必须稳定，平台侧靠它去重），尝试次数由 `attempt_count` 承担，
历史由留痕表承担。

### 4. 写上游 vs 写自己的表

本模块**只读** `platform_dispute` / `platform_dispute_evidence_item`，
所有对上游的写都**驱动** `PlatformDisputeService`
（`submitEvidence` / `resolve` / `withdraw` / `markEvidence`）
⇒ 上游仍是 `platform_dispute` 的**唯一写入者**，「每张事实表只有一个写入所有者」不破。

**提交申诉的顺序不能反**：`gate` 校验 → 更新申诉为 `SUBMITTED` → **驱动上游提交举证**
→ 落待提交平台事实。
若先驱动上游，纠纷状态变 `EVIDENCE_SUBMITTED` 后 gate 会判 `RELEASED`
⇒ DB 守卫① 反过来拒自己的更新。整套动作在一个事务内，任一步失败全回滚。

这条顺序还消掉了一个假事实：**「按时提交了申诉，却因超期被判负」** 结构上进不来
（测试同时断言「把时限改到提交时刻之前」被 V265 守卫③ 拒）。

## 关键 trade-off

1. **判据落成 DB 函数而不是两处表达式。**
   服务层自己判一遍、触发器再判一遍，两处代码迟早漂移；漂移的那天就会出现
   「界面说能提交，DB 说超期」。函数是唯一口径。
   代价：服务层必须多一次函数调用。
2. **函数不接受调用方传入的时刻。**
   传时刻进去看似更灵活，实则允许调用方指定判定基准 —— 那就等于开了「自称现在还没超期」
   这条后门。DB 的 `now()` 是唯一时间源。
3. **举证材料只引用系统记录，不接受上传。**
   代价：现实中确实存在「这次纠纷的证据是一张平台外发来的 PDF」这类场景，本模块做不了。
   收益：申诉材料一旦进了这个库，它就能被追溯到系统里某条真实发生过的事实。
   蓝图加粗句要的正是后者。缺口在能力声明里如实报出。
4. **证据类型由来源推导，不让调用方填。**
   代价：新增一种来源类型要改 DB 函数 + Java 映射。收益：库里不会出现
   「一条物流轨迹被登记成质检报告」这种事后无法自证的材料。
5. **「部分」这一档不代填上游结果码。**
   11.4.1.1 的结果码域里**没有「部分」**。代填一个语义不符的码（例如拿部分支持硬塞进
   `PARTIALLY_WON` 之外的结果码）比留明确缺口更糟 —— 结算口径会跟着错。
   ⇒ 申诉层落 `RESOLVED_PARTIAL` + `awarded_amount`（部分支持金额必须**小于**主张金额），
   **不代填上游结果码**，金额口径仍走 11.4.1.1 的结算分录，缺口写进能力声明。
   胜诉 / 败诉 / 撤诉则正常驱动上游 `resolve` / `withdraw`。
6. **主张金额不进 API 入参。**
   服务端从纠纷单上读（快照），不信客户端 —— 客户端传来的主张金额是「我方主张」，
   平台判的争议金额是另一回事。
7. **前端不自己算时限。**
   窗口状态直接透传后端 gate 的输出 ⇒ 前端**刻意没有倒计时函数**（多一个口径就多一处漂移）。
   清单「齐了」（必举证项都具备）与「包里覆盖了」（材料覆盖了清单项）
   是**两个数字**，分开显示。
8. **api-proxy 插入锚点选在 11.4.1.1 那条之前**，而不是 `services[]` 最末尾。
   最末尾是同批其它车道也会盯的默认位置，四条车道都往那里插就是四路冲突。
   本锚点绑在 11.4.1.x 这一族自己的注释上，与 6.3.x / 6.6.x / 7.4.x 车道的锚点互不重叠。
   首 token 用 `platform-dispute-appeals`，与上游的 `platform-disputes`
   **不是同一个字面量**（`platform-dispute` 后面跟的是 `-appeals` 而不是 `s`）
   ⇒ 上游那条正则吃不掉它；逐条核过本文件全部 117 条正则，命中 0。
9. **jOOQ 绑 null 不用裸 null、不用 `DSL.val` 三元**，改为**条件 SQL**
   （能省掉 null 列的赋值就不写）⇒ 彻底避开「未设定参数值 N 的内容」。
10. **RLS 之外再加显式 `tenant_id` 谓词。**
    按 id 直达的端点拿别人 UUID 时，读到的错误与「不存在」**完全相同** ——
    不给探测者区分「存在但无权」与「不存在」的信号。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V269__platform_dispute_appeal.sql`
  —— 四张新表 + 两个共用函数 + 五个 DB 守卫 + RLS（ENABLE 与 FORCE）+ GRANT trade_app
  + 索引 + 序列 `platform_dispute_appeal_no_seq`（申诉号 `PA-<n>`），**零 ALTER 零 DROP 既有表**。
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformDisputeAppealModels.java`
  —— 枚举 + 23 个 record（`PlatformAppealStatus` / `PlatformAppealResolution` /
  `PlatformAppealResult` / `PlatformAppealSubmissionStatus` / `PlatformAppealSubmissionOutcome` /
  `PlatformAppealSourceModule` / `PlatformAppealWindowState` 等）。
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformDisputeAppealService.java`
  —— 能力声明、列表真分页 / 真筛选 / 排序白名单 / 币种分组汇总、详情、建单、改文本、
  挂 / 移除材料、提交申诉、收回平台回复、撤诉、记录提交尝试结果、重试提交、
  待提交平台事实落库、幂等键。
- `backend/services/trade/src/main/java/com/starcon/trade/PlatformDisputeAppealController.java`
  —— 11 个 HTTP 映射（11 条路径形状）挂 `/trade/platform-dispute-appeals`；
  读 `trade.aftersales.read`，写 `trade.aftersales.write`，
  平台回复与提交尝试结果用**独立权限码 `trade.aftersales.platform`**。
- `backend/services/trade/src/test/java/com/starcon/trade/PlatformDisputeAppealIntegrationTest.java`（28 例）
- `backend/services/trade/src/test/java/com/starcon/trade/PlatformDisputeAppealTenancyIntegrationTest.java`（5 例）

**前端**

- `apps/web/src/components/aftersales/platform-dispute-appeal-model.ts`
  —— API 层 + 取值域 + `windowTone` / `windowText` / `coverageText` / `sourceText` /
  `actionBlockedReason` / `isTerminalStatus` / `formatAmount` / `formatTime`；
  **不提供上传文件、不提供证据类型入参、不自己算时限**。
- `apps/web/src/components/aftersales/platform-dispute-appeal-workspace.tsx`
  —— 真实工作区：汇总 4 卡、建申诉、筛选列表、详情、证据材料、待提交平台事实、
  平台回复与结果、动作区、模块边界卡片；加载 / 空 / 错误三态齐全，无占位数据。
- `apps/web/src/components/aftersales/platform-dispute-appeal-model.test.ts`（21 例）
- `apps/web/src/app/aftersales/disputes/appeals/page.tsx`
- `apps/web/src/app/api/trade/platform-dispute-appeal-allowlist.test.ts`
  —— 双向断言：真实调用点放行、写形状放行、刻意不放行的形状仍 404、上游 11.4.1.1 白名单未受影响。
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 services[] 元素**（插在 11.4.1.1 那条之前）。

**共享生成物（codegen 产物，禁止手改）**

- `packages/api-contracts/src/contracts/trade.ts`（+23 类型，-0，全部 `PlatformAppeal*`）
- `packages/api-contracts/src/trade.openapi.json`（paths +11，全部本模块）
- 其余五份 `contracts/*.ts` 与 `*.openapi.json`：**+0/-0**

**台账**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 11.4.1.2 那一行的
  「状态」「证据」两格（`未开发` → `已交付`，1 行 diff）。

## 后续待办

1. **「部分」档在 11.4.1.1 层没有对应结果码** —— 本模块**不代填**，
   金额口径暂落在申诉的 `awarded_amount` 与 11.4.1.1 的结算分录上。
   待 11.4.1.1 的结果码域扩出「部分」语义后，两侧对齐。
2. **channel 申诉适配器（未交付）** —— 本模块已落「待提交平台」事实与幂等键，
   channel 落地后经**授权 API / event** 消费该事实并回写平台回复与结果。
3. **16 合规（账号绩效）未交付** —— 本模块**不产出**绩效指标、不 mock。
4. **平台纠纷同步（上游 11.4.1.1 的缺口）** —— 平台来源信息目前靠人工登记。
5. **平台外发证据（如平台发来的 PDF）** —— 本模块只认系统内已有的记录，
   这类材料需先落成系统记录再引用。
