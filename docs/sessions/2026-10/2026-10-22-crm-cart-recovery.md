# 15.6.2.2 购物车与弃单挽回（阶段 D7，L3 锚点 15.6.2）

日期：2026-10-22 ｜ 分支 `feat/15-6-2-2` ｜ 基线 `da1f96f9`
迁移版本：**V296**（本车道预分配；同批另三条车道用 V293 / V294 / V295）
归属服务：**crm**（`backend/services/crm/`，包 `com.starcon.crm`，端口 8086，DB 角色 `crm_app`）

## 决策摘要

### 1. 加粗句「此项是独立站(DTC)特有能力，平台订单无购物车概念」落三层

蓝图只有一句话，但这一句是**本模块的准入条件**——平台店铺的购物车事件一旦进表，整张弃单清单就失去意义。
所以它不是"一个判断"，而是**三道各自独立的闸**：

| 层                     | 落点                                                                   | 效果                                                   |
| ---------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------ |
| ① 表级 CHECK           | `crm_cart_abandonment.platform_type CHECK (platform_type = 'DTC')`      | 平台店铺的购物车事件**在数据库层就进不来**这张表       |
| ② 服务层显式拒绝       | 按平台码判定，登记弃单一律 **409 且不写任何数据**                       | 平台店铺的事件**不进弃单清单**，且调用方拿到明确原因   |
| ③ 平台类型是快照       | 每条弃单存 `platform_code` + `platform_type`                             | 事后能回答"这条弃单到底是哪个平台的"                   |

**平台码判定表 `PLATFORM_KINDS` 在 crm 服务里**（不是 channel）。原因：channel 的 platform 表
**没有**"独立站 / 平台店铺"这一列（它的 `operating_mode` 是经营模式，语义不同），
而 channel 的平台码是权威事实，crm 通过 `GET /channel/shops` 拿到的就是那套码。

判定结果（**租户不可改**）：已知 12 个平台里**只有 `SHOPIFY` 是 DTC**，其余 11 个
（Amazon / eBay / TikTok Shop / Temu / Walmart / Shopee / Lazada / Mercado Libre / AliExpress / Etsy / Wayfair）
一律 MARKETPLACE。**未知平台码必须由租户显式声明**（`crm_cart_shop_profile`），未声明即 409 ——
「未知」不等于「独立站」，安全默认取严。

### 2. 平台购物车事件适配器未交付 ⇒ 端口 + 恒 409，不放假实现

`channel/vendor` 只有 19 个 OAuth 适配器，**没有任何购物车 / 结账适配器**。照本服务
`UnavailableInfluencerDataPort`（14.5.1.1 达人库）的同构先例处理：

- `CartEventPort`（接缝）+ `UnavailableCartEventPort`（当前实现）：
  对所有平台答「未接入」，**不返回任何购物车事件、不编造平台接口、不返回硬编码列表、不抛异常**。
- 同步端点据此返回 **409 且不写任何数据**（测试断言「行数不变」）。

**为什么不自己写 Shopify 适配器**：本模块的职责是"管理购物车与弃单挽回"，
**采集**是渠道侧（2 渠道）的职责，跨服务写 channel 的 vendor 包是越界。

### 3. 加粗句「触达须遵守退订与频次上限」= 服务端 + DB 触发器两道

只在一侧实现是不够的：服务层能被绕过（内部脚本、后台任务），触发器能被绕过（手工 SQL）。
所以**两道用同一判据**：

- **退订是硬拦**：`guard_crm_cart_recovery_touch_unsubscribe` 在触达**落库之前**回查退订名单，命中即 RAISE；
  服务层同一判据预检并给**可分类的原因**。
- **频次上限可配置且有默认值**：每日 1 次 / 周期 3 次 / 周期 30 天 / 弃单超时 24 小时。
  上限值在 DB 层另有天花板，**"调到 999" 被 CHECK 堵死**。
- **被拒时的形状**：**明确 409 + 写明原因、一行不写**。不落 `REJECTED` 触达行 ——
  一条"看起来发过"的记录会让运营以为已经联系过客户。
- **频次策略约束 `perCycle >= perDay`**：否则周期线永不触发（周期窗口永远比每日上限小）。
  代价是"每日 0 次 + 周期 3 次"这种配置不合法，但对本模块是合理的（每日 0 次即"不按天，只按周期"）。

### 4. 加粗句「挽回话术需人工确认后启用」落成状态机，且由 DB 触发器统一拥有

`DRAFT → APPROVED → ENABLED → DISABLED`，**只有 `ENABLED` 能被触达流程选用**
（服务层 + `guard_crm_cart_recovery_touch_message` 两道）。

审核留痕记**谁 / 何时 / 哪个版本**（`approved_by` / `approved_at` / `approved_version`），
触发器校验 `approved_version = version`。

**改了内容自动回到草稿并把版本 +1**（`guard_crm_cart_recovery_message_content`）——
避免"审核的是 A 版、发出去的是 B 版"。这一条让**触发器成为状态机的唯一所有者**：
状态迁移规则只写一份（SQL），服务层不再各写一遍。

### 5. 「按超时策略触发挽回」= 未到时点不允许触达（第四道闸）

蓝图说"按超时策略触发挽回"，字面上是**超时之后**才触发。所以除了退订 / 频次 / 话术三闸，
本模块再加**第四道闸**：事件时刻 + 超时小时数未到，**不允许登记触达**（409 + 原因）。
否则"按超时策略"退化成一句装饰。

### 6. 下游 9 订单 / 13 分析 / 15.5.2 触达 / 15.3.1.1 客户档案均未交付 ⇒ 不假造下游

- 触达只**留触达记录**：`transport='MANUAL_HANDOFF'`、状态恒为「已登记待发」，
  **不假装已发出**（15.5.2 触达模块未交付，没有真发信通道）。
- 成交订单号**人工登记**，**不编造下游 id**（9 订单未交付）。
- 挽回率由**本服务真算**（SQL 聚合），**不是写死的数字**。
- capabilities 逐维标 `available=false` + `ownerModule` + 影响说明。

### 7. 恢复记录不单独建表

成交直接落弃单行的 `status / recovered_at / recovered_amount / recovered_order_no`。
单独建表会和弃单行形成 1:1 双写，两处状态可能不一致，而"弃单是否挽回"是**一个**问题。

## 关键 trade-off

| 决策                                   | 取舍                                                                                                       |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| 平台判定放 crm 而非 channel            | 放弃"在 channel 加一列"的天然归属，换 crm 单点判定。代价是平台码在两处都要维护；收益是**不越界改 channel** |
| 未知平台码 → 409 而非默认 DTC         | 牺牲"新平台自动可用"，换"绝不把平台店铺误判成独立站"。新平台需租户显式声明一次                     |
| 触达被拒不落行 vs 落 `REJECTED` 行     | 放弃"留痕"，换"触达记录 = 真发过"。运营据触达记录判断客户有没有被联系过，混入未发记录会误导             |
| 改内容自动回草稿 vs 保留已审核状态     | 放弃"改完直接生效"，换"发出去的永远是审核过的那一版"                                                   |
| 跨服务不建 FK（触发器回表校验）        | 放弃 FK 的引用完整性，换"不打断 TRUNCATE / 不给既有表加约束"。与同服务 V99 / V220 / V228 / V292 同一手法   |
| 契约只提交 crm 两个文件                 | codegen 顺带重排 channel / trade（**基线里就有的漂移**），已**刻意复原为基线版本**，保持本车道 diff 原子  |
| 白名单**末尾**新增一条独立元素          | 放弃"并入既有 crm alternation"，换"既有条目一字未动"。本文件是多车道共用的**累积型**白名单，长单行正则并分支必冲突 |

## 文件清单

**迁移（crm 服务）**

- `backend/services/crm/src/main/resources/db/migration/V296__crm_cart_recovery.sql`
  —— 6 张新表（`crm_cart_abandonment` 弃单本体 / `crm_cart_recovery_message` 挽回话术 /
  `crm_cart_recovery_touch` 触达记录 / `crm_cart_unsubscribe` 退订名单 /
  `crm_cart_recovery_policy` 租户级频次上限 + 弃单超时 / `crm_cart_shop_profile` 店铺平台类型声明）
  + 序列 `crm_cart_abandonment_no_seq` + 9 个守卫触发器
  + RLS（ENABLE **与** FORCE ROW LEVEL SECURITY，策略名 `isolation`）
  + GRANT（表与**序列分开**授权：`GRANT USAGE, SELECT ON SEQUENCE crm_cart_abandonment_no_seq`）
  + `REVOKE ALL ON flyway_schema_history FROM crm_app`。
  **零 ALTER 任何既有表**；**零外键**（含本文件自己新建的表之间）；数量与金额一律 `NUMERIC`（`numeric(19,6)`），**禁浮点**。

**后端（crm 服务，包 `com.starcon.crm`）**

- `CartRecoveryModels.java` —— 传输模型（`CrmCart*` 前缀，避免跨服务同名 DTO 冲突）
- `CartRecoveryService.java` —— jOOQ 显式 SQL；`PLATFORM_KINDS` 唯一判定点；能力声明；
  弃单登记 / 列表 / 详情；触达四道闸；话术状态机；退订；策略；**挽回率 SQL 真算**；sync 恒 409
- `CartRecoveryController.java` —— `/crm/cart-recovery` 共 **18 个 shape / 24 个方法映射**
- `CartEventPort.java` / `UnavailableCartEventPort.java` —— 平台购物车事件接缝与当前实现
- `src/test/.../CartRecoveryIntegrationTest.java`（28 例）、
  `src/test/.../CartRecoveryTenancyIntegrationTest.java`（4 例）

**契约（codegen 产物，禁止手改）**

- `packages/api-contracts/src/contracts/crm.ts` —— 新增 26 个 `CrmCart*` 类型
- `packages/api-contracts/src/crm.openapi.json` —— 新增 **18 条路径 + 26 个 schema**（**纯增量**，既有路径与 schema 零漂移）

> 跑 codegen 时 channel / trade 的既有文件也被重排（**基线里就有的漂移**，与本车道无关），
> 已**刻意复原为基线版本**，保持本车道 diff 只含 crm。

**前端**

- `apps/web/src/app/crm/cart-recovery/page.tsx` —— 页面入口（与 `/crm/emails` 同级）
- `apps/web/src/components/crm/cart-recovery-model.ts` —— API 客户端 + 可读形态 + 查询串拼装
- `apps/web/src/components/crm/cart-recovery-workspace.tsx` —— 真实工作区
  （能力边界 / 挽回率报表 / 弃单清单（**真分页真筛选**）/ 弃单详情 + 触达时间线 / 登记弃单 /
  店铺平台类型 / 话术状态机 / 退订名单 / 频次策略），加载·空·错误三态齐全，
  空态区分「筛选无结果」与「还没有弃单」，**金额与数量只原样展示、不做前端算术**
- `apps/web/src/components/crm/cart-recovery-model.test.ts`（18 例）
- `apps/web/src/app/api/crm/cart-recovery-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**，既有条目一字未动、不重排

**台账 / 日志**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 15.6.2.2 那一行的「状态」「证据」两格
- `docs/sessions/2026-10/2026-10-22-crm-cart-recovery.md` —— 本文件

## api-proxy 锚点与零命中核对

- **锚点**：**`services[]` 数组的末尾**（最后一条元素之后、数组结尾的 `];` 之前）。
  同批另外三条车道的锚点分别是「`crm/knowledge` 之后」「`crm/review-requests` 之后」
  「`crm/review-rules` 之后」，与本锚点互不重叠，git 能干净求并。
- **零命中核对**（Node 逐条 `new RegExp(p).test(shape)` 实测）：
  - 基线 `services[]` 条目 **141** 条，本车道新增后 **142** 条（**净 +1**）；
  - 基线里含 `cart` 字样的条目 **0** 条；
  - 基线 141 条正则 × 本模块 18 个 shape ⇒ **命中 0**；
  - 本模块新正则对本模块 18 个 shape **命中 18/18**，对裸路径 `cart-recovery` **不放行**。
- **末尾刻意不带 `?`**：带了就等于放行裸路径 `cart-recovery`，而后端没有"一条不存在的汇总列表接口"，
  放行它会让界面以为可用。
- **id 段用完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是 `[0-9a-f-]+`）：
  后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。

## 后续待办

- **平台购物车 / 结账事件适配器**接上后：`CartEventPort` 被真正的实现替换，
  `sync` 端点自动放行（落库路径已由测试替身覆盖）。
- **15.5.2 触达**落地后：若它提供真实发送通道，本模块的 `transport` 词表需要与之对齐
  （当前只允许 `MANUAL_HANDOFF`），触达记录的状态也要从"已登记待发"改为真实回执。
- **9 订单**落地后：`recovered_order_no` 改为**系统写入**的订单号，本模块不再人工登记。
- **15.3.1.1 客户档案**落地后：退订名单与联系方式改为从档案读取，本模块自建的表降级为覆盖层。
- **权限码**：本车道用 `crm.cart.read` / `crm.cart.update`（与同服务
  `crm.email.*` / `crm.knowledge.*` / `crm.review.*` / `crm.rule.*` 同一命名约定）。
  ⚠️ 任务书写的 `crm.operation.*` 在**全仓任何文件里都不存在**（identity 的 `permission`
  表里也没有任何 `crm.` 前缀的权限码，既有 crm 模块同样用自由词表），
  故按**兄弟模块的实际约定**取名，**identity 服务零改动**。
