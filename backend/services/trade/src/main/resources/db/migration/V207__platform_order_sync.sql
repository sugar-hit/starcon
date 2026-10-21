-- 9.1.2.1 平台订单同步（trade 服务）
--
-- 蓝图 09-order-and-fulfillment.md 9.1.2.1 原文：
--   职责：接收并落库平台订单（webhook 实时 + 轮询补拉），做字段解析、状态归一、
--         金额与税费还原、地址快照，形成统一订单模型。
--   输入：平台订单事件/接口返回；平台枚举映射（2.1.2.1）；idempotency_key
--         （平台订单号 + 来源范围）。
--   关系：上游 = 2 渠道与店铺；下游 = 9.2 审单、9.3 履约、13 销量。
--         **同一外部订单号在同一来源范围内唯一**，重复事件走 inbox 去重，
--         绝不产生第二张订单。
--
-- ===========================================================================
-- 一、本迁移的回归面：ALTER 任何既有表 = 0
-- ===========================================================================
-- 与 V92 / V97 / V99 / V119 同一纪律：**只新建表与索引，不碰任何既有 DDL**。
--   * 不碰 V1 的 trade_tenancy_probe；
--   * 不碰 V92 的七张 fulfillment_* 履约表；
--   * 不碰 V97 / V98 的加工单与 BOM 冻结表；
--   * 不碰 V99 的 sales_order 簇七张表（**只 INSERT，绝不 ALTER**），
--     也不碰 V100 / V101 的两条读投影索引；
--   * 不碰 V119 的订单变更与取消簇。
-- ⇒ 跨车道 DDL 回归面 = 0，既有 trade 模块的集成测试不需要为本车道重跑。
--
-- ===========================================================================
-- 二、**来源是一个字段，不是一张表**（蓝图加粗句的直接落点）
-- ===========================================================================
-- 蓝图原文：「**手工单与平台单共用同一条履约链路，只有来源标记不同，避免两套流程**」。
-- V99（9.1.2.2 手工订单）已经把 `sales_order` 做成**通用**销售订单头：
--   * `order_source` 的 CHECK 词表**已经**含 PLATFORM / AMAZON / SHOPIFY /
--     EBAY / TIKTOK_SHOP / ALIBABA_1688；
--   * 唯一索引 `sales_order_external_no_unique` 已经是
--     (tenant_id, order_source, external_order_no) 的**部分唯一索引** ——
--     也就是「**外部订单号按来源范围唯一**」这条规则**已经被钉死了**。
-- 所以本车道：
--   * **不新建任何「平台订单主表」**：平台订单的头、���、地址、费用、客户快照
--     一律写进 V99 那七张表，order_source 取平台来源值；
--   * 下游 9.2 审单 / 9.3 履约 / 13 销量读 `sales_order` 一张表即可，
--     **不需要知道这单是手工来的还是平台来的** —— 这就是「避免两套流程」；
--   * 9.1.1.1 订单列表与 9.1.1.2 订单详情（sales_order 簇的**读方**）
--     **不需要改一行查询**就能读出本车道的写入（它们读的就是 sales_order）。
--
-- 本迁移新建的六张表全部是**平台侧同步过程的数据**，不是另一套订单模型：
--   platform_order_sync_batch      同步批次（webhook 一批 / 轮询一个时间窗一批）
--   platform_order_sync_event      inbox（受理凭据 + 去重闸）
--   platform_order                 平台单投影（平台状态 / 平台报告金额 / 映射版本）
--   platform_order_component       组件展开（按映射版本）
--   platform_order_enum_mapping    平台枚举映射的**本模块副本**（见第四节）
--   platform_order_sync_exception  异常明细
--
-- ===========================================================================
-- 三、**幂等**：同一外部订单号在同一来源范围内唯一
-- ===========================================================================
-- 蓝图加粗句「**重复事件走 inbox 去重，绝不产生第二张订单**」是验收项。
-- 本文件用**三道数据库闸**把它钉死，缺一道都不算数：
--
--   闸 1（事件级）`UNIQUE(tenant_id, event_key)` on platform_order_sync_event
--        event_key = sha256(来源范围 | 外部订单号 | 事件类型 | 事件内容哈希)。
--        同一份事件被平台**重复投递**时，第二次插入会被 PostgreSQL 直接挡下。
--        ⚠️ PostgreSQL 的 `INSERT ... ON CONFLICT DO NOTHING` 在遇到**未提交**的
--        冲突行时会**等待**对方事务结束再判定 ⇒ 并发重复投递天然串行化成
--        「一个成功、一个判定为重复」，**不会**产生第二张订单。
--        这一道是**并发**场景下唯一真正生效的闸（服务层的先查后写有竞态）。
--
--   闸 2（订单级）`UNIQUE(tenant_id, platform_code, external_order_no)`
--        on platform_order —— 蓝图点名的「同一外部订单号在同一来源范围内唯一」
--        的**字面落法**。来源范围 = **平台来源**（与 V99 已交付的
--        sales_order 外部订单号唯一口径逐字对齐：共享订单模型没有店铺维度，
--        本车道不许 ALTER 既有表，所以范围只能取平台这一层；
--        店铺是追溯维度，同号来自另一店铺时记 EXTERNAL_ORDER_NO_COLLISION 异常）。
--
--   闸 3（下游级）V99 已有的部分唯一索引
--        `sales_order_external_no_unique (tenant_id, order_source, external_order_no)
--         WHERE external_order_no <> ''`
--        —— 即使有人绕过本模块的表直接写 sales_order，同一个外部单号也撞不出第二张。
--
-- `idempotency_key`（蓝图输入句点名的那个键）= `platform_code | external_order_no`，
-- 在 platform_order 上再钉一道 `UNIQUE(tenant_id, idempotency_key)`：
-- 它是**幂等键的字面载体**，闸 2 是它的规范化展开，两者互为备份。
--
-- ===========================================================================
-- 四、平台枚举映射：**trade 自己的副本表**，不 join channel
-- ===========================================================================
-- 实查集成头：上游 2.1.2.1 的 platform_enum_mapping / platform_enum_override
--   在 **channel 服务**的独立库 starcon_channel
--   （V1__channel_registry_connections_and_shops.sql / V3__platform_enum_tenant_override.sql），
--   **不是 trade 库**。AGENTS.md 模块边界纪律明文：**禁止跨服务 SQL / FK / 共享视图**。
-- ⇒ 本模块**不能** join channel 的表，也不能建跨库 FK。
--
-- 取舍（与 V158 售后原因码映射、11.1.2.2 同一先例）：
--   * **主路径 = 本模块自己的副本表** `platform_order_enum_mapping`，
--     带**来源标注**（TENANT_MAINTAINED / MODULE_2_ENUM_MAPPING）与**同步版本**
--     （mapping_version，落在每一行上，订单快照里也存同一份）；
--   * 副本表的**填充**由两个真实入口完成，二者都不是 mock：
--       ① `POST /trade/platform-orders/mappings/sync` → 经**授权 API**
--          调 channel 的 `GET /channel/platforms/{code}/enums?kind={kind}`
--          （2.1.2.1 的生效映射：平台默认 + 本租户覆盖），落成
--          MODULE_2_ENUM_MAPPING 行并带 source_reference；
--       ② `POST /trade/platform-orders/mappings` → 租户自维护
--          （TENANT_MAINTAINED 行），用于平台默认码表尚未覆盖到的新平台状态词。
--     读取时**租户自维护优先于同步来的**（同一 external_value 两行并存，
--     查表时按来源排序取第一条）—— 租户显式登记的口径不该被一次同步覆盖回去。
--
-- ⚠️ **映射缺失不落兜底**：平台状态查不到映射时，本模块**拒收**该事件并写异常明细
--   （UNMAPPED_PLATFORM_STATUS），**不**猜一个内部状态码。与 channel V1 的
--   「映射缺失必须显式报未映射，否则 09 订单状态机…会被污染」同一纪律：
--   猜出来的状态会污染订单状态机，而订单状态机是审单与履约的唯一口径。
--   唯一的例外是**组件类型**（COMPONENT_TYPE）：它只影响展示归类，
--   猜错不会污染状态机，缺映射时归 OTHER 并在明细里写明。
--
-- ===========================================================================
-- 五、金额与数量口径（AGENTS.md：禁浮点）
-- ===========================================================================
-- 金额 numeric(19,4)（与 V99 / V92 同精度）；数量 numeric(19,6)。
-- 全程 BigDecimal，禁浮点。**时间一律 timestamptz**，绑定 ?::timestamptz +
-- OffsetDateTime（禁 Timestamp.valueOf —— 它按 JVM 默认时区解释，静默偏 −28800s）。
--
-- 平台单落到 V99 的七张表时，金额口径与 V99 第 8.2 节
-- `guard_sales_order_totals` 的公式**逐项对齐**（服务层算一遍，DB 再复核一遍）：
--   小计 = Σ 行小计（平台**原价**单价 × 数量）
--   折扣 = |Σ 行级优惠| + |整单优惠|          （V99 的行折扣恒为负、费用折扣恒为负）
--   税额 = Σ 行级税 + 整单税
--   运费 = 整单运费
--   其他 = 整单其他费用
--   合计 = 小计 − 折扣 + 税 + 运费 + 其他
-- 平台报的「应付总额」与还原结果**逐值比对**（容差 0.01，即一分钱 ——
-- 平台侧与本侧各自分到分的舍入差是常态，超过一分钱就不是舍入而是口径不一致，
-- 那时**拒收并记异常**，不静默吸收）。
--
-- ===========================================================================
-- 六、状态归一与单据状态的关系（V99 的 CHECK 不可 ALTER）
-- ===========================================================================
-- `sales_order.status` 的 CHECK 只有 ('DRAFT','SUBMITTED')，本车道**不能** ALTER 它
-- （跨车道 DDL 回归面纪律）。于是两个状态各归其位：
--   * **平台生命周期状态**（归一后）记在 platform_order.normalized_status：
--     PENDING_CONFIRMATION / CONFIRMED / IN_FULFILLMENT / COMPLETED /
--     CANCELLED / ON_HOLD —— 这是 9.3 履约与 9.4 发货回传要看的那个状态；
--   * **单据状态**（有没有进审单队列）落在 sales_order.status：
--       CONFIRMED / IN_FULFILLMENT / COMPLETED → 'SUBMITTED'（进 9.2 审单队列）
--       PENDING_CONFIRMATION / ON_HOLD / CANCELLED → 'DRAFT'（还没确认，不进队列）
--     平台单与手工单因此**共用同一条履约链路**：下游读 sales_order 一张表，
--     不需要知道来源。
--
-- ===========================================================================
-- 七、快照冻结与「已提交不可改」
-- ===========================================================================
-- V99 第 8.3 节：订单一旦 SUBMITTED，sales_order_line / order_address_snapshot /
-- order_charge / sales_order_party_snapshot 的 UPDATE/DELETE 被触发器挡住。
-- ⇒ 平台单**提交后**，后续平台事件**不允许**改写订单快照（商品、地址、金额）。
--   平台侧的数量调整 / 地址修改 / 取消属于 **9.1.1.3 订单变更与取消**的职责
--   （V119 已交付，变更与取消记成独立单据），本模块只把这条事实记成异常明细
--   （ORDER_SNAPSHOT_FROZEN），由人走 9.1.1.3。
--   **不静默改写、也不静默丢弃**：两种都会让「平台上这单现在是什么状态」答不出来。
--
-- ⚠️ **未提交（DRAFT）的平台单也不静默改写**，记 ORDER_CONTENT_DIVERGED：
--   V99 收回了运行角色对 sales_order_line / order_address_snapshot / order_charge /
--   sales_order_party_snapshot 的 **DELETE** 权（V99 第十节），
--   而「整体替换订单内容」必须先删后插 ⇒ 本模块**没有**替换既有单据内容的合法路径。
--   与其绕开这条纪律（那会让「这单现在有哪几行」出现两个答案），
--   不如把「平台改了内容」这件事明确记下来交人工处置。
--   判定依据是 platform_order.content_fingerprint（内容指纹，见第三节）。
--
-- ⚠️ **同号订单来自另一家店铺**时记 EXTERNAL_ORDER_NO_COLLISION：
--   来源范围是平台（第一节），所以另一家店铺的同号单撞的是**同一条**唯一约束。
--   把它默默判成「重复」会让运营以为那家店铺的单已经同步过了 ——
--   真相是它从没进来过。
--
-- ===========================================================================
-- 八、多租户：RLS + 授权（与 trade V1 / V92 / V97 / V99 同一套写法）
-- ===========================================================================
-- 六张业务表一视同仁 ENABLE + FORCE RLS。租户由 backend/libraries/tenancy 在事务内
-- 设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它
-- （AccessService.Access 来自已认证成员关系）。
--
-- ===========================================================================
-- 1. 同步批次（webhook 实时 / 轮询补拉 各记一批）
-- ===========================================================================
CREATE TABLE platform_order_sync_batch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- **来源范围 = 平台来源**（唯一性维度）。幂等键由它与外部订单号拼成。
  -- ⚠️ 刻意**不含店铺这一维**：共享订单模型 sales_order 的外部订单号唯一口径是
  --   (租户, order_source, 外部订单号)（V99 的 sales_order_external_no_unique），
  --   而 order_source 的词表里只有**平台级**取值（PLATFORM/AMAZON/SHOPIFY/EBAY/
  --   TIKTOK_SHOP/ALIBABA_1688），**没有店铺维度**。本车道不许 ALTER 既有表，
  --   所以本模块的唯一性范围必须与它**逐字对齐** ——
  --   否则 platform_order 会接受一张 sales_order 拒收的订单
  --   （「平台侧说这是两张单、统一订单模型说这是同一张单」= 两个答案）。
  --   店铺是**追溯维度**（shop_code 列），不是唯一性维度：
  --   同号订单来自另一家店铺时，本模块**拒收并记 EXTERNAL_ORDER_NO_COLLISION 异常**，
  --   而不是默默判成「重复」—— 后者会让运营以为那家店铺的订单已经同步过了。
  platform_code varchar(64) NOT NULL CHECK(btrim(platform_code) <> ''),
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
  -- 触发方式：webhook 实时接入 / 轮询补拉。两者走**同一条**落库路径。
  trigger_type text NOT NULL CHECK(trigger_type IN ('WEBHOOK','POLL')),
  -- 轮询的时间窗。webhook 没有时间窗（它是单点事件）。
  window_start_at timestamptz,
  window_end_at timestamptz,
  -- 批次结论：SUCCEEDED 全部落地 / PARTIAL 部分落地 / FAILED 一条都没落地。
  status text NOT NULL DEFAULT 'RUNNING'
    CHECK(status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED')),
  received_count int NOT NULL DEFAULT 0 CHECK(received_count >= 0),
  created_count int NOT NULL DEFAULT 0 CHECK(created_count >= 0),
  updated_count int NOT NULL DEFAULT 0 CHECK(updated_count >= 0),
  -- **重复事件**计数：inbox 去重挡下的（同一份事件重复投递 / 更旧的版本）。
  duplicate_count int NOT NULL DEFAULT 0 CHECK(duplicate_count >= 0),
  -- **失败**计数：被拒收的事件（映射缺失 / 金额对不上 / 字段非法）。
  failed_count int NOT NULL DEFAULT 0 CHECK(failed_count >= 0),
  -- 本批使用的**映射版本**（2.1.2.1 副本的版本号）。
  mapping_version varchar(64) NOT NULL DEFAULT '',
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 轮询必须带时间窗，webhook 必须不带：两个都写会让「这批拉的是哪段时间」有两个答案。
  CONSTRAINT platform_order_sync_batch_window_matches_trigger
    CHECK ((trigger_type = 'POLL') = (window_start_at IS NOT NULL AND window_end_at IS NOT NULL)),
  -- 时间窗必须自洽（开始不晚于结束）。
  CONSTRAINT platform_order_sync_batch_window_ordered
    CHECK (window_start_at IS NULL OR window_end_at IS NULL OR window_start_at <= window_end_at),
  -- 批次一旦定案，计数与结论就不再改：批次是「这一趟发生了什么」的留痕。
  CONSTRAINT platform_order_sync_batch_counters_consistent
    CHECK (received_count >= created_count + updated_count + duplicate_count + failed_count)
);

-- ===========================================================================
-- 2. inbox：平台订单事件的**受理凭据**（去重闸 1 落在这里）
-- ===========================================================================
CREATE TABLE platform_order_sync_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  batch_id uuid NOT NULL,
  platform_code varchar(64) NOT NULL CHECK(btrim(platform_code) <> ''),
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
  -- **外部订单号**（平台自己的单号）。
  external_order_no varchar(128) NOT NULL CHECK(btrim(external_order_no) <> ''),
  -- **幂等键**（蓝图输入句点名）：平台订单号 + 来源范围。
  idempotency_key varchar(320) NOT NULL CHECK(btrim(idempotency_key) <> ''),
  -- **事件键**：同一份事件被重复投递时用它识别。
  -- = sha256(幂等键 | 事件类型 | 事件内容哈希)。
  event_key varchar(64) NOT NULL CHECK(btrim(event_key) <> ''),
  event_type text NOT NULL
    CHECK(event_type IN ('ORDER_CREATED','ORDER_UPDATED','ORDER_CANCELLED')),
  -- **平台原始状态**（未归一）：平台怎么说就存什么，归一后的值另存一列。
  platform_status varchar(128) NOT NULL DEFAULT '',
  -- **归一后的平台状态**：内部统一口径（第四节 / 第六节）。
  normalized_status text NOT NULL DEFAULT ''
    CHECK(normalized_status IN ('','PENDING_CONFIRMATION','CONFIRMED','IN_FULFILLMENT','COMPLETED','CANCELLED','ON_HOLD')),
  -- 事件内容哈希：同一份事件内容 → 同一个哈希。平台重投同一份内容时用它识别。
  payload_hash varchar(64) NOT NULL CHECK(btrim(payload_hash) <> ''),
  -- **事件原文**：服务层收到的订单事件（按本服务的传输模型序列化）。
  -- 异常明细与事后审计都要看它 —— 只有「处理结果」而没有「当时收到了什么」，
  -- 排查时答不出「平台到底发了什么」。
  event_payload jsonb NOT NULL,
  -- 受理结论：APPLIED 已落地 / DUPLICATE 重复 / REJECTED 拒收。
  outcome text NOT NULL DEFAULT 'RECEIVED'
    CHECK(outcome IN ('RECEIVED','APPLIED','DUPLICATE','REJECTED')),
  -- 落地后指向的两张单：平台单投影 + 统一销售订单。
  order_id uuid,
  sales_order_id uuid,
  error_code varchar(64) NOT NULL DEFAULT '',
  error_message text NOT NULL DEFAULT '',
  -- 平台侧的事件版本与更新时间：用来判「这个事件比已落库的那次更旧吗」。
  platform_event_version bigint NOT NULL DEFAULT 0,
  platform_updated_at timestamptz,
  received_at timestamptz NOT NULL,
  processed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- **闸 1**：同一份事件在同一租户内只能有一行。并发重复投递由它挡住。
  UNIQUE(tenant_id,event_key),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES platform_order_sync_batch(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 3. 平台单投影（平台侧的事实：状态 / 平台报告金额 / 映射版本）
-- ===========================================================================
-- ⚠️ 这**不是**第二套订单模型：统一订单模型仍然是 V99 的 sales_order 簇。
-- 本表只存 sales_order **放不下**的平台侧事实 —— 平台生命周期状态、平台报告的
-- 原价/优惠/税/运费/应付总额、以及**这单是按哪一版映射对上的号**。
CREATE TABLE platform_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  platform_code varchar(64) NOT NULL CHECK(btrim(platform_code) <> ''),
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
  external_order_no varchar(128) NOT NULL CHECK(btrim(external_order_no) <> ''),
  -- **幂等键**（蓝图点名）：平台订单号 + 来源范围。
  idempotency_key varchar(320) NOT NULL CHECK(btrim(idempotency_key) <> ''),
  -- 统一销售订单（V99 簇）的引用。**同服务内**外键，不跨库。
  sales_order_id uuid NOT NULL,
  -- 平台原始状态 + 归一后的平台状态。
  platform_status varchar(128) NOT NULL DEFAULT '',
  normalized_status text NOT NULL CHECK(normalized_status IN (
    'PENDING_CONFIRMATION','CONFIRMED','IN_FULFILLMENT','COMPLETED','CANCELLED','ON_HOLD')),
  -- 平台侧的事件版本与时刻：判「新旧」的依据。
  platform_event_version bigint NOT NULL DEFAULT 0,
  platform_placed_at timestamptz,
  platform_updated_at timestamptz,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- **平台报告的金额**（平台口径，可能与我们的还原口径差一分钱）。
  platform_original_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(platform_original_amount >= 0),
  platform_discount_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(platform_discount_amount >= 0),
  platform_tax_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(platform_tax_amount >= 0),
  platform_shipping_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(platform_shipping_amount >= 0),
  platform_grand_total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(platform_grand_total_amount >= 0),
  -- **还原总额**：由平台的原价/优惠/税/运费按统一口径还原出来、写进
  -- sales_order.grand_total_amount 的那个值。它与 sales_order 永远一致
  -- （提交后由触发器冻住，见第九节）。
  restored_grand_total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(restored_grand_total_amount >= 0),
  -- **映射版本**：这单是按 2.1.2.1 副本的哪一版对上的号。
  -- 映射改了以后，历史订单仍能说清「当时按哪一版归一的」。
  mapping_version varchar(64) NOT NULL DEFAULT '',
  -- **内容指纹**：这单在统一订单模型里的内容（币种 / 行 / 地址 / 买家 / 整单费用 /
  -- 支付方式）的 sha256。作用是判「平台后来又发来的那一份是不是同一件事」：
  --   * 指纹相同 → 只是状态推进，按新状态更新投影即可；
  --   * 指纹不同 → 平台侧改了内容（加行 / 改地址 / 改金额）。
  --     已提交的订单改不动（V99 第 8.3 节），未提交的也**不静默改写** ——
  --     两者都记成异常明细交人工处置（9.1.1.3 订单变更与取消的职责）。
  -- ⚠️ 有指纹才答得出「平台这单现在是什么内容」；没有它就只能靠比金额，
  --   而金额相同的两份内容（换了个收件人）会被误判成「没变化」。
  content_fingerprint varchar(64) NOT NULL DEFAULT '',
  -- 最近一次被采纳的事件（platform_order_sync_event.id）。
  last_event_id uuid,
  synced_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- **闸 2**：蓝图点名的「同一外部订单号在同一来源范围内唯一」的字面落法。
  -- 来源范围 = 平台来源（见第一节的说明：与 V99 的 sales_order 唯一口径逐字对齐，
  -- 共享订单模型没有店铺维度）。带平台这一维是必须的 ——
  -- Amazon 的 123 与 Shopify 的 123 是两张毫不相干的单。
  UNIQUE(tenant_id,platform_code,external_order_no),
  -- **幂等键的字面载体**（与闸 2 等价，互为备份）。
  UNIQUE(tenant_id,idempotency_key),
  -- 一张统一销售订单只对应一个平台单投影。
  UNIQUE(tenant_id,sales_order_id),
  FOREIGN KEY(tenant_id,sales_order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 4. 组件展开（按映射版本）
-- ===========================================================================
-- 蓝图：「组件展开（按映射版本）：按 2.1.2.1 的映射版本展开订单组件，
-- **映射版本要随快照一起存**」。
-- 一行 = 一个展开出来的组件。**没有**显式组件的订单行展开成它自己
-- （component_source='LINE'），所以一张订单至少有��行组件 ——
-- 「这张单由哪些东西组成」永远答得出来。
CREATE TABLE platform_order_component (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  sales_order_id uuid NOT NULL,
  line_no int NOT NULL CHECK(line_no > 0),
  -- LINE = 该订单行本身就是这个组件（没有更细的展开）；COMPONENT = 显式组件。
  component_source text NOT NULL CHECK(component_source IN ('LINE','COMPONENT')),
  -- **行内序号**：平台报的第几个组件（1 起）。它是这一行组件的**占位**，
  -- 平台怎么报就怎么落（不合并、不改写）。
  component_seq int NOT NULL CHECK(component_seq > 0),
  component_code varchar(128) NOT NULL CHECK(btrim(component_code) <> ''),
  component_name varchar(200) NOT NULL DEFAULT '',
  component_type text NOT NULL DEFAULT 'OTHER'
    CHECK(component_type IN ('ITEM','BUNDLE','GIFT','PACKAGING','OTHER')),
  -- **平台原始类型词**（未归一）：保留它才能分辨「平台真报的是 OTHER」
  -- 与「平台报了 BUNDLE 但本租户没登记映射、我们兜底成了 OTHER」——
  -- 后者是**猜的**，界面与审计都必须看得出它不是平台说的。
  external_type varchar(128) NOT NULL DEFAULT '',
  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  unit_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(unit_amount >= 0),
  -- **映射版本随快照一起存**（与 platform_order.mapping_version 同一份）。
  mapping_version varchar(64) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- **同一行的同一个占位只能一行**（服务层按平台报的次序发号，序号不会重���）。
  -- ⚠️ 键里**带 component_source**：一行既有它自己的 LINE 行（这行卖的是个套装），
  --   又有它展开出来的 COMPONENT 行（套装里的件）。
  -- ⚠️ 键里**不含 component_code**（上一轮这里是 (…,component_source,component_code)，
  --   本轮改正）：组件码**允许重复** —— 一个套装里装了两件同款零件是常事，
  --   平台就会报两个同码组件。把码放进唯一键会让这种订单**整张落不下去**
  --   （第二行撞唯一约束 → 整批事务回滚 → 平台那一单永远同步不进来），
  --   而「这行有几个挂件」并不因此有第二个答案：每一行自带数量，
  --   两件同款就是两行各 1 件（或一行 2 件，取决于平台怎么报），
  --   序号让「哪一行是平台报的第几个」始终有唯一答案。
  UNIQUE(tenant_id,order_id,line_no,component_source,component_seq),
  FOREIGN KEY(tenant_id,order_id) REFERENCES platform_order(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 5. 平台枚举映射的**本模块副本**（第四节）
-- ===========================================================================
CREATE TABLE platform_order_enum_mapping (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  platform_code varchar(64) NOT NULL CHECK(btrim(platform_code) <> ''),
  -- 映射种类：ORDER_STATUS 订单状态（**不容兜底**）/ PAYMENT_METHOD 支付方式 /
  -- COMPONENT_TYPE 组件类型（容兜底到 OTHER）。
  enum_kind varchar(64) NOT NULL CHECK(enum_kind IN ('ORDER_STATUS','PAYMENT_METHOD','COMPONENT_TYPE')),
  external_value varchar(128) NOT NULL CHECK(btrim(external_value) <> ''),
  internal_value varchar(64) NOT NULL CHECK(btrim(internal_value) <> ''),
  -- **来源标注**：租户自维护 / 由 2.1.2.1 同步而来。
  mapping_source text NOT NULL CHECK(mapping_source IN ('TENANT_MAINTAINED','MODULE_2_ENUM_MAPPING')),
  -- 同步来的必须说明「哪份来源的哪一段」，否则「这行是同步器写的」只是口头声明。
  source_reference varchar(200) NOT NULL DEFAULT '',
  -- **同步版本**：本行的映射版本。订单快照里存的就是它。
  mapping_version varchar(64) NOT NULL CHECK(btrim(mapping_version) <> ''),
  -- **采集时刻**（不是登记时刻）：填 now() 会让三个月前的映射被当成今天的。
  captured_at timestamptz NOT NULL,
  -- 停用时刻：停用后不再被新订单选中，历史订单仍指向它当时用的那一版。
  superseded_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一来源下 (平台, 种类, 外部值, **版本**) 只能一行。
  -- ⚠️ 键里**必须带 mapping_version**（上一轮这里没有版本，于是「停用旧行 + 写新行」
  --   的留痕设计在 DB 层根本落不下去：第二版会撞第一版的唯一约束，
  --   而删掉旧行又等于毁掉「这单当时按哪一版归一的」这条举证链）。
  -- **带 mapping_source 这一维**也是必须的：租户自维护与同步来的可以并存，
  -- 读取时租户自维护优先。
  UNIQUE(tenant_id,platform_code,enum_kind,external_value,mapping_source,mapping_version),
  -- 同步来的必须带来源说明。
  CONSTRAINT platform_order_enum_mapping_source_reference_required
    CHECK ((mapping_source = 'MODULE_2_ENUM_MAPPING') = (btrim(source_reference) <> ''))
);
-- **同一个 (平台, 种类, 外部值, 来源) 只能有一行「当前生效」**（部分唯一索引）。
-- 有了它，「当前映射」永远只有一个答案：查表时除了这条索引，还要按
-- superseded_at IS NULL 过滤 —— 两个条件合起来才是「当前生效的那一行」。
-- ⚠️ 写成**部分索引**而不是普通 UNIQUE：被停用的历史版本必须能与当前版本共存
-- （历史订单仍指向它当时用的那一版，见上面 mapping_version 的注释）。
CREATE UNIQUE INDEX platform_order_enum_mapping_current_unique
  ON platform_order_enum_mapping(tenant_id, platform_code, enum_kind, external_value, mapping_source)
  WHERE superseded_at IS NULL;

-- 映射版本号发号器：全局单调递增。版本号只用于「按哪一版对上的号」，
-- 不需要按租户连续，因此用一个全局序列即可。
CREATE SEQUENCE platform_order_mapping_version_seq AS bigint START 1;

-- ===========================================================================
-- 6. 异常明细（被拒收的事件：映射缺失 / 金额对不上 / 字段非法 / 快照已冻结）
-- ===========================================================================
CREATE TABLE platform_order_sync_exception (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  batch_id uuid,
  event_id uuid,
  platform_code varchar(64) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  external_order_no varchar(128) NOT NULL DEFAULT '',
  -- 错误码：机器可判定（前端按它给出处置建议），文案另存。
  error_code varchar(64) NOT NULL CHECK(btrim(error_code) <> ''),
  error_message text NOT NULL CHECK(btrim(error_message) <> ''),
  -- 现场：出问题时那批事件的原文（够定位即可，不整包塞进来）。
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES platform_order_sync_batch(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 7. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 批次列表：按创建时间倒序（最近的同步在最前）。
CREATE INDEX platform_order_sync_batch_by_created
  ON platform_order_sync_batch(tenant_id, created_at, id);
-- 批次列表的「按状态筛」。
CREATE INDEX platform_order_sync_batch_by_status
  ON platform_order_sync_batch(tenant_id, status, created_at, id);
-- 事件：按批次读（批次详情页的逐条结果）。
CREATE INDEX platform_order_sync_event_by_batch
  ON platform_order_sync_event(tenant_id, batch_id, received_at, id);
-- 事件：按订单读（这张单被平台投递过几次）。
CREATE INDEX platform_order_sync_event_by_order
  ON platform_order_sync_event(tenant_id, idempotency_key, received_at, id);
-- 事件：按结论筛（只看待处理的失败）。
CREATE INDEX platform_order_sync_event_by_outcome
  ON platform_order_sync_event(tenant_id, outcome, created_at, id);
-- 平台单列表：按平台 + 同步时间倒序。
CREATE INDEX platform_order_by_platform
  ON platform_order(tenant_id, platform_code, synced_at, id);
-- 平台单列表：按归一状态筛。
CREATE INDEX platform_order_by_status
  ON platform_order(tenant_id, normalized_status, synced_at, id);
-- 平台单列表：按店铺筛。
CREATE INDEX platform_order_by_shop
  ON platform_order(tenant_id, shop_code, synced_at, id);
-- 组件展开：按组件码反查（「这个组件码出现在哪些订单里」）。
-- ⚠️ 「按订单读组件」已由唯一索引 (tenant_id,order_id,line_no,component_source,component_seq)
--    的前缀服务，不再重复建。
CREATE INDEX platform_order_component_by_code
  ON platform_order_component(tenant_id, component_code, order_id, line_no);
-- 组件展开：按统一销售订单反查。
CREATE INDEX platform_order_component_by_sales_order
  ON platform_order_component(tenant_id, sales_order_id, line_no);
-- 映射副本：按 (平台, 种类) 读（唯一索引前缀已覆盖 platform_code+enum_kind，
-- 下面这条服务「按内部值反查外部值」）。
CREATE INDEX platform_order_enum_mapping_by_internal
  ON platform_order_enum_mapping(tenant_id, platform_code, enum_kind, internal_value);
-- 异常明细：按时间倒序。
CREATE INDEX platform_order_sync_exception_by_created
  ON platform_order_sync_exception(tenant_id, created_at, id);
-- 异常明细：按批次读。
CREATE INDEX platform_order_sync_exception_by_batch
  ON platform_order_sync_exception(tenant_id, batch_id, id);
-- 异常明细：按错误码筛。
CREATE INDEX platform_order_sync_exception_by_code
  ON platform_order_sync_exception(tenant_id, error_code, created_at, id);

-- ===========================================================================
-- 8. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================

-- 8.1 **inbox 的受理结论只能写一次**。
--     事件行插进来时是 RECEIVED，处理完再定案成 APPLIED / DUPLICATE / REJECTED。
--     定案之后既不许再改、也不许删 —— 「平台当时发了什么、我们怎么处理的」
--     是唯一一份凭据，改了它就等于让这次同步的处理结论出现第二个答案。
CREATE FUNCTION guard_platform_order_sync_event_finalizable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '平台订单事件（%）是不可删改的受理凭据：事件原文与受理结论是「平台当时发了什么、我们怎么处理的」的唯一证据', OLD.event_key
      USING ERRCODE='23514';
  END IF;
  IF OLD.outcome <> 'RECEIVED' THEN
    RAISE EXCEPTION '平台订单事件（%）已定案为「%」，不允许再改：受理结论只能写一次', OLD.event_key, OLD.outcome
      USING ERRCODE='23514';
  END IF;
  IF NEW.outcome NOT IN ('APPLIED','DUPLICATE','REJECTED') THEN
    RAISE EXCEPTION '平台订单事件（%）的受理结论取值非法（%）：只能是 APPLIED / DUPLICATE / REJECTED', OLD.event_key, NEW.outcome
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_order_sync_event_finalizable
  BEFORE UPDATE OR DELETE ON platform_order_sync_event
  FOR EACH ROW EXECUTE FUNCTION guard_platform_order_sync_event_finalizable();

-- 8.2 **异常明细是 append-only 留痕**。
CREATE FUNCTION guard_platform_order_trail_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：同步异常是「当时为什么没落下来」的唯一记录，改了它就等于让这次失败的原因出现第二个答案', TG_TABLE_NAME, TG_OP
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER platform_order_sync_exception_append_only
  BEFORE UPDATE OR DELETE ON platform_order_sync_exception
  FOR EACH ROW EXECUTE FUNCTION guard_platform_order_trail_append_only();

-- 8.3 **订单提交后，还原总额冻住**。
--     平台单的还原总额必须与 sales_order.grand_total_amount 一致；
--     订单一旦 SUBMITTED，V99 就把明细与金额冻住了，这里的还原总额也必须跟着冻住，
--     否则会出现「订单上是 A、平台单投影上是 B」两个答案。
CREATE FUNCTION guard_platform_order_restored_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE st text;
BEGIN
  SELECT status INTO st FROM sales_order
    WHERE tenant_id = NEW.tenant_id AND id = NEW.sales_order_id;
  IF st = 'SUBMITTED'
     AND NEW.restored_grand_total_amount IS DISTINCT FROM OLD.restored_grand_total_amount THEN
    RAISE EXCEPTION '平台订单「%」的还原总额不可再改：订单已提交进审单队列，订单上的金额是**下单当时**的快照（要改金额请走 9.1.1.3 订单变更与取消）', NEW.external_order_no
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER platform_order_restored_frozen
  BEFORE UPDATE ON platform_order
  FOR EACH ROW EXECUTE FUNCTION guard_platform_order_restored_frozen();

-- ===========================================================================
-- 9. 多租户：RLS + 授权（与 trade V1 / V92 / V97 / V99 同一套写法）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_order_sync_batch','platform_order_sync_event','platform_order',
    'platform_order_component','platform_order_enum_mapping','platform_order_sync_exception'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  platform_order_sync_batch,platform_order_sync_event,platform_order,
  platform_order_component,platform_order_enum_mapping,platform_order_sync_exception TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  platform_order_sync_batch,platform_order_sync_event,platform_order,
  platform_order_component,platform_order_enum_mapping,platform_order_sync_exception TO starcon_app;
GRANT USAGE,SELECT ON SEQUENCE platform_order_mapping_version_seq TO trade_app;
-- 「授权在前、收回在后」，顺序不能颠倒，否则下面这几条会被上面的 GRANT 覆盖回去：
--   * 异常明细是 append-only 留痕，收回改写权；
--   * inbox 的受理结论由触发器钉死（只能定案一次），DELETE 一并收回；
--   * 映射副本**不收回 DELETE**：停用是留痕的（superseded_at），删除不是 ——
--     删掉的那行会在下一次同步时被原样写回来，于是「明明停用了却又生效」。
REVOKE UPDATE,DELETE ON platform_order_sync_exception FROM trade_app;
REVOKE DELETE ON platform_order_sync_event FROM trade_app;

-- ---------------------------------------------------------------------------
-- 10. 与上下游的边界（本文件明确划出，不越界）
-- ---------------------------------------------------------------------------
--   * **2.1.2.1 平台枚举映射（channel 服务）**：跨服务禁止读表 ⇒ 本模块维护
--     自己的副本表 platform_order_enum_mapping，同步入口走 channel 的**授权 API**
--     （GET /channel/platforms/{code}/enums），不建跨库 FK、不建跨服务视图。
--   * **2 渠道与店铺**：店铺/平台主数据在 channel 库，本模块只记
--     platform_code / shop_code 两个**逻辑标识**（不建跨库 FK、不回查）。
--   * **9.1.2.2 手工订单（V99）**：写的是**同一张 sales_order**，order_source 取
--     平台来源值，manual_kind 留空。手工单与平台单共用同一条履约链路。
--   * **9.1.1.1 订单列表 / 9.1.1.2 订单详情**：读方。本模块写入 sales_order 簇，
--     它们**不需要改一行查询**就能读出来。
--   * **9.1.1.3 订单变更与取消（V119）**：已提交订单的快照改不动（V99 第 8.3 节），
--     平台侧的数量调整/地址修改/取消由它处理，本模块只记异常明细。
--   * **9.2 审单 / 9.3 履约 / 13 销量**：下游。平台单落 SUBMITTED 即进 9.2 队列，
--     与手工单同一条链路。
--   * **3.6 客户档案（catalog 库）**：跨服务禁 FK、禁 JOIN。平台单的
--     sales_order_party_snapshot 记的是**平台侧买家快照**，customer_party_id 是
--     由「平台 + 店铺 + 平台买家号」确定性派生的**平台侧标识**（不是 catalog 的
--     party id），平台买家与 catalog 客户的对应关系由 3.6 侧自行建立，
--     本模块不越界。
--   * **05 价目（卷 05，D6 未交付）**：本模块**不引用**任何价目服务，
--     单价一律来自平台事件。
--
-- 11. 新增函数全部带 platform_order_ / guard_platform_order_ 前缀，与 V1 / V92 /
--     V97 / V99 / V119 的既有函数名不冲突；表名 platform_order* 与既有
--     sales_order* / order_* / processing_* / fulfillment_* 互不重名。
