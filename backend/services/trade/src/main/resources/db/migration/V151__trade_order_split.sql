-- 9.3.1.1 拆单。
--
-- 蓝图 09-order-and-fulfillment.md 9.3.1.1 原文：
--   职责：把一个订单拆成多个履约单元：按仓库拆、按承运商拆、按发货时效拆（如部分现货先发）、
--         按平台限制拆。
--   输入：拆单请求或自动拆单规则；可用库存分布。
--   输出：多个履约请求 + 库存预占指令 + 发货回传按拆分执行。
--   关系：上游 = 9.2 审单；下游 = 9.3.3.1 履约请求、7 预占。
--         **拆单不产生新的销售订单**，只是履约切分；订单总金额与买家视角不变。
--
-- 归属：**trade 服务**（蓝图 09 卷 L2 表「9.3 履约编排 → 主责服务 trade」）。
-- 本迁移**只新建表、零 ALTER 既有表** —— sales_order / sales_order_line（V99）
-- 是 9.1 车道的写入所有者，本模块对它只做**可选的只读**（见第四节），
-- 跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 一、上游实查结论：9.2 审单整条线未交付 ⇒ 只存逻辑引用 + 快照列
-- ===========================================================================
-- 蓝图上游写的是「9.2 审单」。实查 trade 服务 db/migration 目录：
--   没有任何 review / risk / audit 词表的建表语句（`grep '^CREATE TABLE' | grep -i review`
--   为空）—— 9.2.1.1 待审队列、9.2.1.2 审单规则、9.2.1.3 异常拦截**都未开发**。
-- 因此本模块：
--   * 订单只存 order_ref 逻辑引用 + order_no / 币种 / 总额 / 收货地址 / 平台
--     这一组**快照列**，**不建指向审单记录的引用，也不校验审单结论存在性**；
--   * sales_order（V99）本身已交付，所以服务层提供一条**可选**的只读路径
--     （拆单入参 orderSource=SALES_ORDER 时按 tenant 真实联表取行与总额），
--     取不到就**明确记成 REQUEST_SNAPSHOT 并在返回值里报出**，绝不假装读到了订单；
--   * capabilities 里 **ORDER_REVIEW 维度 available=false** 如实报出「审单未接通」。
-- 与 V116 的 ORDER_DESTINATION 维度、V92 的运费缺口同一手法：不假装接通。
--
-- ===========================================================================
-- 二、下游实查结论：7 预占在 warehouse 独立库、9.3.3.1 与本批并行
-- ===========================================================================
-- 实查 warehouse 服务迁移：inventory_reservation / reservation_entry /
-- reservation_allocation / stock_bucket 全在 **warehouse 自己的数据库**里，
-- 按 AGENTS.md 模块边界纪律**禁止跨库读写对方的表**。
-- 所以蓝图输出②「库存预占指令」在本服务落成
-- order_split_reservation_command（**指令登记**，带状态与来源），并用
--   CONSTRAINT order_split_reservation_never_reserves_upstream
--     CHECK(upstream_reserved = false)
-- 把「本模块不预占上游库存」钉在数据层 —— 否则两边都以为占了同一批货
-- （这正是 V116 里踩过的坑）。capabilities 里 reservesUpstreamStock=false 同报。
--
-- 9.3.3.1 履约请求是**本批并行的另一条车道（V152）**，那些表此刻还不存在。
-- 本模块**不建也不读**它们：蓝图输出①「多个履约请求」落成
-- order_split_unit 履约单元行（带状态），由 9.3.3.1 后续消费。
--
-- ===========================================================================
-- 三、可用库存分布（7.1.1.3）：租户可维护的真实表
-- ===========================================================================
-- 与 V116 的 fulfillment_warehouse_allocation_stock_estimate 同一手法：
-- 7.1.1.3 在 warehouse 独立库不可读，本模块自落地 order_split_warehouse
-- 与 order_split_stock_snapshot，带 stock_source
-- （MODULE_7_1_1_3 / TENANT_MAINTAINED）与 collected_at。
-- 将来同步器按同一形状回灌并把来源改成 MODULE_7_1_1_3 即可，表结构不用改。
--
-- ===========================================================================
-- 四、金额守恒 —— 本模块最核心的不变量（蓝图加粗句）
-- ===========================================================================
-- 「订单总金额与买家视角不变」被做成**数据层的强约束**而不是一句注释：
--   * order_split.creates_sales_order 恒 false，CHECK 直接写死（见第 5 节）——
--     拆单不产生新的销售订单，这是蓝图加粗句，**任何服务层都改不动**；
--   * Σ(order_split_unit_line.allocated_amount + residual_amount)
--     必须**恰好等于** order_split.order_total_amount，由延迟约束触发器
--     guard_order_split_amount_conservation 在提交时实算复核，不等就拒；
--   * **尾差必须有承载**（照 V116 的 PARTIAL_SHORTFALL 做法）：
--     分摊按 HALF_UP 到 4 位，舍入产生的差额落进 residual_amount 列，
--     由服务层**确定性地**归到一行（金额最大者，同额按 (unit_seq, line_no) 字典序），
--     且全表至多一行 residual≠0（触发器独立复核）。
--     绝不允许「差了几分钱就让它消失」—— 那会让每个履约单元的金额都差一点，
--     对账时无法解释差额去哪了。
--
-- ===========================================================================
-- 五、确定性
-- ===========================================================================
-- 「按仓库拆 / 按承运商拆 / 按时效拆 / 按平台限制拆」这四种策略是
-- **租户口径的落库存档配置**（order_split_rule 的四个布尔 + primary_strategy），
-- 不是 Java 里的 if 链。
-- 同样输入必须给出同样的拆单方案：主维度分组内按
-- (warehouse_ref / carrier_code / ship_by_tier / platform_code) **字典序**定序，
-- 再按 unit_seq 落库（照 V116 tiebreak 落到字典序的做法）。
--
-- ===========================================================================
-- 六、时间字段
-- ===========================================================================
-- timestamptz 列一律由服务层用 OffsetDateTime 绑定，并在 values 位置写
-- `?::timestamptz`。用 Timestamp.valueOf(LocalDateTime...) 会静默偏 −28800s，
-- 而「按发货时效拆」直接依赖 ship_by 时刻的正确性。

-- ---------------------------------------------------------------------------
-- 1. 拆单规则（租户可维护；**每租户恰好一条**）
-- ---------------------------------------------------------------------------
-- 为什么要求先有规则才允许拆单：拆单会真实改变包裹形态、发货时效与回传口径，
-- 「没配规则 = 默认全拆」与 9.3.2.2 把 MCF 资质 UNKNOWN 当成有是同一类事故。
-- 这里取与 V144 合单规则同一立场：**没登记规则就报「未配置」，不给隐含默认值**。
CREATE TABLE order_split_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 一租户一条规则：UNIQUE(tenant_id) 本身就是「恰好一条」的表达。
  UNIQUE(tenant_id),
  enabled boolean NOT NULL DEFAULT true,
  -- ⚠️ **四个拆分维度是四个独立的租户开关**，不是代码里的 if：
  --   按仓库拆 / 按承运商拆 / 按发货时效拆 / 按平台限制拆。
  -- 「这一单要不要按承运商再切一刀」是运营口径，写进 if 分支就再也改不动。
  split_by_warehouse boolean NOT NULL DEFAULT true,
  split_by_carrier boolean NOT NULL DEFAULT true,
  split_by_timeliness boolean NOT NULL DEFAULT false,
  split_by_platform_restriction boolean NOT NULL DEFAULT true,
  -- **主维度**：分组时以它为准，其余开启的维度只在**主维度分出的组内**再切。
  -- 必须取四个开关里至少一个为真的那个，否则规则自相矛盾（见下方 CHECK）。
  primary_strategy varchar(16) NOT NULL DEFAULT 'WAREHOUSE' CHECK(primary_strategy IN (
    'WAREHOUSE','CARRIER','TIMELINESS','PLATFORM')),
  -- **单次拆单最多拆几个履约单元**。下限 1：拆成一个单元就是「不拆」，
  -- 允许它是为了让「按单一仓全发」在配置上可表达，而不是禁止。
  max_units_per_split int NOT NULL DEFAULT 8 CHECK(max_units_per_split >= 1),
  -- **是否允许部分先发**（蓝图括注「如部分现货先发」）。
  -- 关掉时，任一行都必须在一个单元里全额切完；开掉才允许同一行跨单元分批。
  allow_partial_fulfillment boolean NOT NULL DEFAULT true,
  -- **时效档位上限（小时）**：候选仓的承诺发货时效超过这个值就不可用。
  -- 留空 = 不限（不是 0）。7.1.1.3 未接通，本值与仓上的 ship_by_hours
  -- 一起构成时效拆分的可维护口径。
  max_ship_by_hours int CHECK(max_ship_by_hours IS NULL OR max_ship_by_hours > 0),
  -- **库存新鲜度上限（小时）**：快照采集时刻距今超过这个值就不可用。
  -- 「拿三天前的库存分布去拆单」会把货拆到早就卖光的仓上。
  stock_freshness_hours int CHECK(stock_freshness_hours IS NULL OR stock_freshness_hours > 0),
  version bigint NOT NULL DEFAULT 0,
  updated_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(updated_at>=created_at),
  -- ⚠️ **主维度必须真的开着**：否则规则会说「主维度=承运商」但承运商开关是关的，
  -- 分组时无据可依，同一输入在不同代码路径下可能给出不同方案。
  CONSTRAINT order_split_rule_primary_strategy_enabled
    CHECK(case primary_strategy
      when 'WAREHOUSE' then split_by_warehouse
      when 'CARRIER' then split_by_carrier
      when 'TIMELINESS' then split_by_timeliness
      when 'PLATFORM' then split_by_platform_restriction
      else false end),
  -- ⚠️ **至少开一个拆分维度**：四个全关就是「不许拆」，与「enabled=false 暂停拆单」
  -- 是两件事。四个全关却让调用方以为能拆，只会把请求推到服务层的兜底分支上。
  CONSTRAINT order_split_rule_at_least_one_dimension
    CHECK(split_by_warehouse OR split_by_carrier OR split_by_timeliness OR split_by_platform_restriction)
);

-- ---------------------------------------------------------------------------
-- 2. 发货仓登记（仓 × 承运商 × 时效 × 平台限制）
-- ---------------------------------------------------------------------------
-- 为什么需要这张表：拆单的四个维度里，「这个仓走哪个承运商」「它承诺多久发出」
-- 「它接不接这个平台的单」都是**仓的属性**，不是订单的属性。
-- 9.3.2.1 分仓规则（V116）与本表都自落地仓档案，两个模块各写各的，互不 ALTER。
CREATE TABLE order_split_warehouse (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL CHECK(btrim(warehouse_ref)<>''),
  warehouse_name varchar(120) NOT NULL DEFAULT '',
  -- **货权归属（法人）**：从 A 法人的仓发货意味着收入记在 A 法人。
  -- 拆单把一单切到多个仓，这条归属必须逐单元可追。2.1.1.1 法人档案跨库不可读，
  -- 故为租户登记值。
  legal_entity_code varchar(32) NOT NULL DEFAULT '',
  -- **该仓默认承运商**：拆「按承运商拆」时用它给单元定 carrier。
  carrier_code varchar(32) NOT NULL DEFAULT '',
  -- **承诺发货时效（小时）**：从收单到交运的仓内时效承诺，是「按时效拆」的依据。
  -- 留空 = 未知（不是 0）：未知仓在时效拆分里排不进档，由服务层报出而不是当最快。
  ship_by_hours int CHECK(ship_by_hours IS NULL OR ship_by_hours >= 0),
  -- **平台限制**：逗号分隔的平台码大写列表，空 = 不限平台。
  -- 「按平台限制拆」要求同一仓不能跨平台发货时，单元必须按这个字段切开。
  -- ⚠️ 刻意用字符串而不是关联表：这是一份**只读的约束清单**，服务层按
  -- `= ANY(string_to_array(..., ','))` 在 SQL 里判定，不在本模块里增删平台项；
  -- 真要做「平台 × 仓」关系管理，那是 2.5.1.1 能力矩阵的事，不该由本模块占表。
  allowed_platforms varchar(300) NOT NULL DEFAULT '',
  -- 该仓是否参与拆单候选。停用的仓保留历史（已拆出的单元仍要能查到它当时是谁）。
  active boolean NOT NULL DEFAULT true,
  notes varchar(300) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个仓一个登记行：两条并存时「取哪条」取决于查询顺序，同一输入两次拆单结果会变。
  UNIQUE(tenant_id,warehouse_ref),
  CHECK(updated_at>=created_at),
  -- 平台清单的形状：空，或一串 UPPER/数字/下划线组成、逗号分隔、不含空项。
  -- 不校验形状的话，'amazon,,ebay' 会被当成一个匹配不到的字符串，
  -- 于是「限平台」的仓被静默当成「不限平台」—— 那正是错发。
  CONSTRAINT order_split_warehouse_platform_list_shape
    CHECK(allowed_platforms='' OR allowed_platforms !~ '[^A-Z0-9_,]' AND allowed_platforms !~ ',,'
          AND allowed_platforms !~ '^,' AND allowed_platforms !~ ',$')
);

-- ---------------------------------------------------------------------------
-- 3. 可用库存分布快照（7.1.1.3 的租户可维护替身，照 V116 同形）
-- ---------------------------------------------------------------------------
CREATE TABLE order_split_stock_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL,
  -- SKU 是 catalog 服务的逻辑 ID，跨服务不建外键（与 V116 sku_ref 同一纪律）。
  sku_ref varchar(64) NOT NULL CHECK(btrim(sku_ref)<>''),
  available_qty numeric(19,6) NOT NULL CHECK(available_qty>=0),
  -- 观测值的发生时点，由采集方给出，不由写入时刻冒充：
  -- 「这批数据太旧了」这种判断要做得出来，两者混同就没法判断了。
  collected_at timestamptz NOT NULL,
  stock_source varchar(24) NOT NULL DEFAULT 'TENANT_MAINTAINED'
    CHECK(stock_source IN ('MODULE_7_1_1_3','TENANT_MAINTAINED')),
  source_reference varchar(128) NOT NULL DEFAULT '',
  notes varchar(300) NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一仓同一 SKU 只能有一条**当前**分布（UPSERT 语义）：
  -- 两条并存时「取哪条」取决于查询顺序 —— 那会让同一份订单两次拆出不同结果。
  UNIQUE(tenant_id,warehouse_ref,sku_ref),
  FOREIGN KEY(tenant_id,warehouse_ref)
    REFERENCES order_split_warehouse(tenant_id,warehouse_ref),
  CHECK(updated_at>=created_at),
  CONSTRAINT order_split_stock_source_needs_reference
    CHECK(stock_source<>'MODULE_7_1_1_3' OR btrim(source_reference)<>'')
);

-- ---------------------------------------------------------------------------
-- 4. 拆单头（一次拆单 = 一个方案）
-- ---------------------------------------------------------------------------
-- **一条拆单一行，不做「反复重拆」**：拆单是履约凭据，
-- 「当时为什么这么拆」要能复现；要改方案就 CANCELLED + 新建一次。
CREATE TABLE order_split (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  split_no varchar(64) NOT NULL CHECK(btrim(split_no)<>''),
  -- 状态机：PLANNED 已拆分（单元已定）→ CONFIRMED 已确认（可下发）
  --         → DISPATCHED 已下发到 9.3.3.1 / 7 预占；CANCELLED 终态。
  status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(status IN (
    'PLANNED','CONFIRMED','DISPATCHED','CANCELLED')),
  -- ⚠️ **订单是逻辑引用，不建 FK、不校验存在性**：上游 9.2 审单整条线未交付
  -- （实查无 review 词表建表语句），拆单无法也不应校验审单结论。
  order_ref varchar(64) NOT NULL CHECK(btrim(order_ref)<>''),
  order_no varchar(64) NOT NULL DEFAULT '',
  -- **订单内容的来源**：SALES_ORDER = 本服务按 tenant 从 sales_order / sales_order_line
  -- 真实联表读到的；REQUEST_SNAPSHOT = 调用方随拆单请求一起送来的快照
  -- （订单不在本服务可见范围内时的正规路径，不是 mock —— 快照是真实业务输入）。
  order_source varchar(24) NOT NULL DEFAULT 'REQUEST_SNAPSHOT'
    CHECK(order_source IN ('SALES_ORDER','REQUEST_SNAPSHOT')),
  -- 平台归属快照：拆单后每个单元要分别回传（蓝图输出③），
  -- 平台归属逐单保留而不是只挂在头上。
  order_origin_platform varchar(32) NOT NULL DEFAULT '',
  order_origin_shop varchar(64) NOT NULL DEFAULT '',
  platform_order_ref varchar(128) NOT NULL DEFAULT '',
  -- **收货地址快照**：履约单元要能独立发货，地址得随方案固化下来。
  recipient_name varchar(120) NOT NULL DEFAULT '',
  recipient_phone varchar(64) NOT NULL DEFAULT '',
  recipient_email varchar(254) NOT NULL DEFAULT '',
  country_code char(2) NOT NULL DEFAULT '',
  address_line varchar(600) NOT NULL DEFAULT '',
  currency_code varchar(3) NOT NULL,
  -- 🔴 **订单总金额：拆单全程不变**。这是蓝图加粗句「订单总金额与买家视角不变」
  -- 的锚点，也是第四节金额守恒等式的右边。
  order_total_amount numeric(19,4) NOT NULL CHECK(order_total_amount >= 0),
  -- 🔴 **拆单不产生新的销售订单**：这一列恒 false，由 CHECK 写死。
  --    写成列而不是只写注释，是为了让它出现在 \d 输出里 ——
  --    下一次有人想「顺手给拆单建张新单」时，这条约束会直接拒绝。
  creates_sales_order boolean NOT NULL DEFAULT false,
  -- **本次按什么维度拆的**（主维度快照，不随规则后来被改而变）。
  strategy varchar(16) NOT NULL CHECK(strategy IN (
    'WAREHOUSE','CARRIER','TIMELINESS','PLATFORM')),
  -- 下面四个数由触发器 guard_order_split_totals 从单元行实算回填，
  -- **不接受调用方填** —— 写了就会出现「明细 3 个单元、头写 2 个」。
  unit_count int NOT NULL DEFAULT 0 CHECK(unit_count >= 0),
  distinct_warehouse_count int NOT NULL DEFAULT 0 CHECK(distinct_warehouse_count >= 0),
  distinct_carrier_count int NOT NULL DEFAULT 0 CHECK(distinct_carrier_count >= 0),
  allocated_amount_total numeric(19,4) NOT NULL DEFAULT 0 CHECK(allocated_amount_total >= 0),
  -- **尾差合计**：分摊舍入后归到某一行的那几分钱。守恒时它与
  -- allocated_amount_total 之和恰好等于 order_total_amount。
  residual_amount_total numeric(19,4) NOT NULL DEFAULT 0,
  total_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_qty >= 0),
  planned_at timestamptz NOT NULL,
  confirmed_at timestamptz,
  confirmed_by_membership_id uuid,
  dispatched_at timestamptz,
  dispatched_by_membership_id uuid,
  cancelled_at timestamptz,
  cancel_reason varchar(400) NOT NULL DEFAULT '',
  note varchar(400) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,split_no),
  -- 全部命名：未命名约束的 `<表名>_check<N>` 在本表有多条 CHECK 时完全不可读
  -- （提交被拒只报「violates check constraint order_split_check7」）。
  CONSTRAINT order_split_currency_shape CHECK(currency_code ~ '^[A-Z]{3}$'),
  CONSTRAINT order_split_country_shape
    CHECK(country_code='' OR country_code ~ '^[A-Z]{2}$'),
  -- 🔴 蓝图加粗句「拆单不产生新的销售订单」的**物理落点**：任何服务层都改不动它。
  CONSTRAINT order_split_never_creates_sales_order CHECK(creates_sales_order = false),
  CONSTRAINT order_split_confirmed_needs_actor
    CHECK(status<>'CONFIRMED' OR (confirmed_at IS NOT NULL AND confirmed_by_membership_id IS NOT NULL)),
  CONSTRAINT order_split_dispatched_needs_actor
    CHECK(status<>'DISPATCHED' OR (dispatched_at IS NOT NULL AND dispatched_by_membership_id IS NOT NULL)),
  CONSTRAINT order_split_cancelled_needs_reason
    CHECK(status<>'CANCELLED' OR (cancelled_at IS NOT NULL AND btrim(cancel_reason)<>'')),
  CONSTRAINT order_split_planned_has_no_confirm_time
    CHECK(status<>'PLANNED' OR confirmed_at IS NULL),
  CONSTRAINT order_split_timestamps_ordered CHECK(updated_at>=created_at)
);
CREATE INDEX order_split_by_order
  ON order_split(tenant_id,order_ref,planned_at DESC);
CREATE INDEX order_split_by_status
  ON order_split(tenant_id,status,planned_at DESC);
CREATE INDEX order_split_by_strategy
  ON order_split(tenant_id,strategy,planned_at DESC);
CREATE INDEX order_split_by_created
  ON order_split(tenant_id,created_at DESC);

-- ---------------------------------------------------------------------------
-- 5. 履约单元（蓝图输出①「多个履约请求」在本服务的落法）
-- ---------------------------------------------------------------------------
-- **一个订单可以有多张 sales_order？不 —— 拆单不产生新销售订单**（见第 4 节 CHECK）。
-- 履约单元只是「这张单的哪一部分、由哪个仓、走哪个承运商、什么时候发」。
-- 9.3.3.1 履约请求（本批另一条车道 V152）后续消费这些单元行，
-- 本模块**不建也不读**它的表。
CREATE TABLE order_split_unit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  split_id uuid NOT NULL,
  -- **单元序号**：从 1 连续递增，是确定性排序的落点
  -- （同维度内按 warehouse_ref / carrier_code / ship_by_tier / platform_code 字典序定序）。
  unit_seq int NOT NULL CHECK(unit_seq > 0),
  -- 单元状态：PLANNED → ALLOCATED（预占指令已登记）→ DISPATCHED（随下发）；
  -- CANCELLED 随方案作废。与头同向，不许比头更靠前。
  unit_status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(unit_status IN (
    'PLANNED','ALLOCATED','DISPATCHED','CANCELLED')),
  -- **本单元用哪个维度切出来的**（可能与头的主维度不同：主维度分组后
  -- 组内再按次维度切，例如先按仓分、仓内再按承运商分）。
  unit_strategy varchar(16) NOT NULL CHECK(unit_strategy IN (
    'WAREHOUSE','CARRIER','TIMELINESS','PLATFORM')),
  warehouse_ref varchar(64) NOT NULL DEFAULT '',
  carrier_code varchar(32) NOT NULL DEFAULT '',
  -- **发货时效档位**：本单元承诺的 ship_by 时刻。
  -- 「按时效拆」的落点：现货仓今天发、备货仓明天发，各是一个单元。
  ship_by_at timestamptz,
  -- **时效档位（小时）**：该单元的仓承诺时效档，用于列表排序与可解释性。
  ship_by_hours int CHECK(ship_by_hours IS NULL OR ship_by_hours >= 0),
  -- 平台限制维度切出来的单元：同一仓对不同平台必须分开时，platform_code 区分它们。
  platform_code varchar(32) NOT NULL DEFAULT '',
  legal_entity_code varchar(32) NOT NULL DEFAULT '',
  -- 由触发器从单元行实算回填。
  line_count int NOT NULL DEFAULT 0 CHECK(line_count >= 0),
  total_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_qty >= 0),
  allocated_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(allocated_amount >= 0),
  dispatched_at timestamptz,
  note varchar(400) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,split_id,unit_seq),
  FOREIGN KEY(tenant_id,split_id) REFERENCES order_split(tenant_id,id),
  CHECK(updated_at>=created_at),
  -- **发货时效维度切出的单元必须带 ship_by 时刻**：没有时刻的时效单元
  -- 等于「这一刀切了但没说要什么时候发」，那它就没有存在的意义。
  CONSTRAINT order_split_unit_timeliness_needs_ship_by
    CHECK(unit_strategy<>'TIMELINESS' OR (ship_by_at IS NOT NULL AND ship_by_hours IS NOT NULL)),
  -- **仓库维度切出的单元必须带仓**：否则「从哪个仓发」无解。
  CONSTRAINT order_split_unit_warehouse_needs_ref
    CHECK(unit_strategy<>'WAREHOUSE' OR btrim(warehouse_ref)<>'')
);
CREATE INDEX order_split_unit_by_split
  ON order_split_unit(tenant_id,split_id,unit_seq);
CREATE INDEX order_split_unit_by_warehouse
  ON order_split_unit(tenant_id,warehouse_ref,unit_status);

-- ---------------------------------------------------------------------------
-- 6. 单元行级明细（金额守恒的最小粒度）
-- ---------------------------------------------------------------------------
-- ⚠️ 为什么必须有一张行级明细：拆单切的是**行的一部分数量**
-- （「A 仓发 2 件现货、B 仓发剩下的」），所以「这一行分了多少金额」
-- 是货权和开票的真实粒度。没有它，单元金额就只能按比例拍，
-- 守恒也钉不住 —— 而守恒是本模块最核心的不变量。
CREATE TABLE order_split_unit_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  split_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  order_line_ref varchar(64) NOT NULL CHECK(btrim(order_line_ref)<>''),
  sku_ref varchar(64) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- **本单元切走的数量**（不是订单行总量）。
  allocated_qty numeric(19,6) NOT NULL CHECK(allocated_qty > 0),
  -- **本单元分摊到的金额**：按行金额 × 数量比例，HALF_UP 到 4 位。
  allocated_amount numeric(19,4) NOT NULL CHECK(allocated_amount >= 0),
  -- 🔴 **尾差承载列**：分摊舍入产生的差额归到这一行（至多一行，触发器复核）。
  --    不给差额安排去处，它就会在「各单元金额之和 ≠ 订单总额」里静默消失。
  residual_amount numeric(19,4) NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,unit_id) REFERENCES order_split_unit(tenant_id,id),
  -- 同一行在**同一个单元里**只能出现一次：一张订单行的同一批货被两个单元各切一刀，
  -- 就是重复发货。
  --
  -- ⚠️ 这里刻意**不是** UNIQUE(tenant_id, split_id, order_line_ref)：
  -- 「部分现货先发」正是蓝图括注里的核心场景 —— 同一行分两批走两个仓，
  -- 那是本模块存在的理由。把它按「拆单」维度唯一化，等于把这个功能从数据层禁掉
  -- （本条迁移的第一版就是这么写的，被集成测试当场逮住）。
  UNIQUE(tenant_id,unit_id,order_line_ref)
);
-- 🔴 **尾差至多一行承载**：用部分唯一索引表达，而不是表级 CHECK。
-- （表级 CHECK 只能写 residual_amount=0，那等于「永远不许有尾差」，
--   正好把第四节要的「差额必须有承载」给禁掉了。）
CREATE UNIQUE INDEX order_split_unit_line_single_residual
  ON order_split_unit_line(tenant_id,split_id)
  WHERE residual_amount <> 0;
CREATE INDEX order_split_unit_line_by_unit
  ON order_split_unit_line(tenant_id,unit_id);

-- ---------------------------------------------------------------------------
-- 7. 逐单元候选依据（照 V116 的 ..._candidate 做法落库）
-- ---------------------------------------------------------------------------
-- **为什么要落库而不是查询时联算**：库存会被补上、仓会被停用、规则会被改。
-- 查询时联算的结果只能回答「现在如果重拆一次会怎么拆」，
-- 「当时为什么这么拆」永远答不出来 —— 而那正是出问题时唯一要问的问题。
CREATE TABLE order_split_candidate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  split_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  order_line_ref varchar(64) NOT NULL,
  warehouse_ref varchar(64) NOT NULL DEFAULT '',
  carrier_code varchar(32) NOT NULL DEFAULT '',
  -- 该候选是否被选中。落选候选与被选候选**同等重要**：
  -- 运营问「为什么这批货没从上海仓发」时，答案在这里而不是靠猜。
  selected boolean NOT NULL DEFAULT false,
  -- 落选原因码（被选中时为空串 ''，所以 '' 必须**在** CHECK 词表里）。
  -- ⚠️ 刻意把空串写进词表而不是「被选中就不写这一列」：列是 NOT NULL DEFAULT ''，
  --   省略它得到的也是 ''，词表里没有 '' 就会让每一条**被选中**的候选都被 CHECK 拒掉。
  reject_reason varchar(32) NOT NULL DEFAULT '' CHECK(reject_reason IN (
    '','INSUFFICIENT_STOCK','PLATFORM_RESTRICTED','SHIP_BY_TOO_SLOW','STOCK_TOO_STALE',
    'WAREHOUSE_INACTIVE','NO_CARRIER','BEATEN_BY_TIEBREAK','MAX_UNITS_EXCEEDED',
    'PARTIAL_FULFILMENT_DISABLED','SAME_WAREHOUSE_ALREADY_FULL')),
  reject_detail varchar(300) NOT NULL DEFAULT '',
  available_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(available_qty >= 0),
  demand_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(demand_qty >= 0),
  collected_at timestamptz,
  stock_source varchar(24) NOT NULL DEFAULT '',
  -- **候选排序键**：同一仓多行快照或同一行多仓时，靠它确定性地定出唯一赢家。
  -- 落库而不是只靠查询时 ORDER BY，是为了让「为什么它赢了」可以被复算。
  rank_no int NOT NULL DEFAULT 0 CHECK(rank_no >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,unit_id) REFERENCES order_split_unit(tenant_id,id),
  -- 被选中的候选必须说明它为什么赢（或为什么没理由被否）。
  CONSTRAINT order_split_candidate_selected_has_no_reject
    CHECK(selected = false OR reject_reason = '')
);
CREATE INDEX order_split_candidate_by_split
  ON order_split_candidate(tenant_id,split_id,order_line_ref,rank_no);
CREATE INDEX order_split_candidate_rejected
  ON order_split_candidate(tenant_id,split_id) WHERE selected = false;

-- ---------------------------------------------------------------------------
-- 8. 库存预占指令（蓝图输出②；**本服务内登记，不真的锁上游库存**）
-- ---------------------------------------------------------------------------
-- ⚠️ 7 预占（inventory_reservation 等）在 warehouse 独立库，按模块边界纪律
-- 不得跨库读写。所以这里登记的是**指令**而不是预占事实：
-- 有状态、有来源、有 warehouse 侧回执号，但没有、也不该有「已预占」的本地断言。
CREATE TABLE order_split_reservation_command (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  split_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  sku_ref varchar(64) NOT NULL CHECK(btrim(sku_ref)<>''),
  order_line_ref varchar(64) NOT NULL DEFAULT '',
  reserved_qty numeric(19,6) NOT NULL CHECK(reserved_qty > 0),
  warehouse_ref varchar(64) NOT NULL DEFAULT '',
  -- **目标服务**：7 预占的归属服务，写死成 WAREHOUSE 而不是留空 ——
  -- 让「这条指令发给谁」在库里就能查出来，而不是靠读代码。
  target_service varchar(16) NOT NULL DEFAULT 'WAREHOUSE'
    CHECK(target_service IN ('WAREHOUSE')),
  -- 指令来源：SPLIT_PLAN 由拆单方案自动生成，MANUAL 由人工补登。
  command_source varchar(16) NOT NULL DEFAULT 'SPLIT_PLAN'
    CHECK(command_source IN ('SPLIT_PLAN','MANUAL')),
  -- 状态：REQUESTED 待下游认领 → ACKNOWLEDGED 已预占（下游回执）
  --       / REJECTED 下游拒绝 / CANCELLED 方案作废连带撤销。
  command_status varchar(16) NOT NULL DEFAULT 'REQUESTED' CHECK(command_status IN (
    'REQUESTED','ACKNOWLEDGED','REJECTED','CANCELLED')),
  -- **warehouse 侧返回的预占号**：真实预占住在 warehouse 库，本列只是回执。
  external_reservation_ref varchar(128) NOT NULL DEFAULT '',
  -- 🔴 **本模块不预占上游库存**，恒 false，由 CHECK 写死。
  --    两边都以为占了同一批货是 V116 里踩过的坑，这里从数据层杜绝。
  upstream_reserved boolean NOT NULL DEFAULT false,
  reject_reason varchar(400) NOT NULL DEFAULT '',
  acknowledged_at timestamptz,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个单元一个 SKU 一条预占指令：重复指令 = warehouse 侧重复预占 = 货被锁两次。
  UNIQUE(tenant_id,unit_id,sku_ref),
  FOREIGN KEY(tenant_id,unit_id) REFERENCES order_split_unit(tenant_id,id),
  CHECK(updated_at>=created_at),
  CONSTRAINT order_split_reservation_never_reserves_upstream
    CHECK(upstream_reserved = false),
  -- 已预占必须有 warehouse 侧的回执号，否则「已预占」是无内容的一句谎。
  CONSTRAINT order_split_reservation_acked_needs_ref
    CHECK(command_status<>'ACKNOWLEDGED' OR btrim(external_reservation_ref)<>''),
  CONSTRAINT order_split_reservation_rejected_needs_reason
    CHECK(command_status<>'REJECTED' OR btrim(reject_reason)<>'')
);
CREATE INDEX order_split_reservation_pending
  ON order_split_reservation_command(tenant_id,command_status,created_at)
  WHERE command_status IN ('REQUESTED','REJECTED');
CREATE INDEX order_split_reservation_by_split
  ON order_split_reservation_command(tenant_id,split_id);

-- ---------------------------------------------------------------------------
-- 9. 发货回传任务（蓝图输出③「发货回传按拆分执行」）
-- --------------------------------------------------------------------------
-- 「按拆分执行」在表结构上的落法：一个履约单元一条回传任务，
-- UNIQUE(tenant_id,unit_id) 保证不多（重复回传 = 平台侧重复发货记录）、
-- 不少（漏一条 = 那部分货在平台永远显示未发货）。
CREATE TABLE order_split_writeback_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  split_id uuid NOT NULL,
  unit_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  platform_order_ref varchar(128) NOT NULL DEFAULT '',
  tracking_number varchar(128) NOT NULL DEFAULT '',
  carrier_code varchar(32) NOT NULL DEFAULT '',
  status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(status IN (
    'PENDING','SUBMITTED','CONFIRMED','REJECTED','PENDING_VERIFICATION')),
  attempt_count int NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  last_error varchar(500) NOT NULL DEFAULT '',
  submitted_at timestamptz,
  settled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,unit_id),
  FOREIGN KEY(tenant_id,split_id) REFERENCES order_split(tenant_id,id),
  FOREIGN KEY(tenant_id,unit_id) REFERENCES order_split_unit(tenant_id,id),
  CONSTRAINT order_split_writeback_confirmed_needs_tracking
    CHECK(status<>'CONFIRMED' OR btrim(tracking_number)<>''),
  CONSTRAINT order_split_writeback_settled_status_only
    CHECK((settled_at IS NULL) OR status IN ('CONFIRMED','REJECTED','PENDING_VERIFICATION')),
  CONSTRAINT order_split_writeback_timestamps_ordered
    CHECK(updated_at>=created_at)
);
CREATE INDEX order_split_writeback_pending
  ON order_split_writeback_task(tenant_id,status,created_at)
  WHERE status IN ('PENDING','PENDING_VERIFICATION','REJECTED');
CREATE INDEX order_split_writeback_by_split
  ON order_split_writeback_task(tenant_id,split_id);

-- ---------------------------------------------------------------------------
-- 10. 事件流（append-only 留痕，与 V144 / V92 同形）
-- ---------------------------------------------------------------------------
CREATE TABLE order_split_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  split_id uuid NOT NULL,
  -- varchar(32) 而不是 24：'RESERVATION_COMMAND_ISSUED' 是 28 个字符，
  -- 塞进 varchar(24) 会让每一次成功落拆单都在插事件时炸掉 ——
  -- 词表长度要按**最长那个**量，不是按最常见的那个量。
  event_type varchar(32) NOT NULL CHECK(event_type IN (
    'SPLIT_PLANNED','CONFIRMED','DISPATCHED','CANCELLED',
    'RESERVATION_COMMAND_ISSUED','WRITEBACK_RECORDED')),
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  detail varchar(400) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,split_id) REFERENCES order_split(tenant_id,id)
  -- ⚠️ **刻意不加 occurred_at>=created_at 这类 CHECK**：occurred_at 记的是业务上
  -- 真实发生的那一刻，它允许早于本行被写库的日期 —— 补录昨天的拆单、
  -- 把一批历史单一次性拆进来，都是正常业务动作。
);

-- ---------------------------------------------------------------------------
-- 11. 实算触发器：头的汇总数 + 🔴 金额守恒
-- ---------------------------------------------------------------------------
-- ⚠️ 本文件所有约束触发器在写子行时**一律回表读当前行**，不使用 NEW 的可变列：
-- 服务层的写入顺序是「先插头 → 写单元行 → 写行级明细 → 回填头」，
-- 延迟到提交时那次 INSERT 拿着的仍是半成品的 NEW；用 NEW 每次必报。
-- （与 V144 guard_order_consolidation_totals / V92 guard_routing_decision_switch 同一个坑、同一个解法。）
-- ⚠️ 汇总与守恒的**实算主体是普通函数 recalc_order_split(uuid,uuid)**，
-- 不是触发器函数：PostgreSQL 规定 trigger 函数只能由触发器机制调用，
-- 直接 `PERFORM guard_...()` 会报「trigger functions can only be called as triggers」。
-- 单元行侧与明细行侧两个触发器都要跑同一段实算，所以主体必须先抽成普通函数，
-- 两个触发器函数各自薄薄地转调它 —— 这样实算逻辑也只有一份实现，不会两处各算各的。
CREATE FUNCTION recalc_order_split(uuid,uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  ord_total numeric(19,4);
  -- ⚠️ 这几个变量刻意带 v_ 前缀：UPDATE ... SET total_qty = total_qty 时，
  -- 右侧默认按**列**解析，与同名的 PL/pgSQL 变量撞车，报
  -- 「column reference "total_qty" is ambiguous」。
  -- 本迁移第一版就是这么写的，在第一次落拆单时于提交阶段炸掉 ——
  -- 加前缀是最省事也最不会忘的解法。
  v_total_qty numeric(19,6);
  v_alloc numeric(19,4);
  v_resid numeric(19,4);
  residual_rows int;
  units int;
  whs int;
  cars int;
BEGIN
  SELECT s.order_total_amount INTO ord_total
    FROM order_split s WHERE s.tenant_id = $1 AND s.id = $2;
  IF ord_total IS NULL THEN RETURN; END IF;

  -- 单元行汇总一律从 order_split_unit_line 实算：
  -- line_count / total_qty / allocated_amount 是「这个单元里到底有什么」的唯一答案。
  -- ⚠️ 聚合源**不能**写成「UPDATE ... FROM (SELECT ... FROM order_split_unit ul ...)」：
  -- PostgreSQL 不允许在 UPDATE 的 FROM 子查询里引用更新目标表。
  -- 正确形状是先把本拆单的单元 id 收成一张集合，再对明细行分组。
  UPDATE order_split_unit u
     SET line_count = agg.line_count,
         total_qty = agg.total_qty,
         allocated_amount = agg.alloc
    FROM (SELECT ul.unit_id,
                 count(*) AS line_count,
                 sum(ul.allocated_qty) AS total_qty,
                 sum(ul.allocated_amount) AS alloc
            FROM order_split_unit_line ul
           WHERE ul.tenant_id = $1
             AND ul.unit_id IN (SELECT id FROM order_split_unit
                                 WHERE tenant_id = $1 AND split_id = $2)
           GROUP BY ul.unit_id) agg
   WHERE u.tenant_id = $1
     AND u.id = agg.unit_id;

  SELECT count(*),
         count(DISTINCT nullif(warehouse_ref,'')),
         count(DISTINCT nullif(carrier_code,'')),
         coalesce(sum(u.total_qty),0)
    INTO units, whs, cars, v_total_qty
    FROM order_split_unit u
   WHERE u.tenant_id = $1 AND u.split_id = $2
     AND u.unit_status <> 'CANCELLED';

  -- ⚠️ **金额分摊与守恒算的是「方案本身」，不看单元状态**。
  -- 拆单一旦写下就不可改（要重来是 CANCELLED + 新建一条），作废只是把方案标成终止，
  -- **不改变当初分配了多少钱**。如果这里也按 unit_status<>'CANCELLED' 过滤，
  -- 一次作废就会让「分摊合计 0 ≠ 订单总额」成立 —— 然后守恒触发器会把每一次合法作废
  -- 都当成违规拒掉（第一版就是这么写的，被集成测试当场逮住）。
  -- 活着的单元数 / 去重仓数 / 件数仍然只看未作废的：那几个数回答的是「现在还有几个单元在跑」。
  SELECT coalesce(sum(ul.allocated_amount),0),
         coalesce(sum(ul.residual_amount),0),
         count(*) FILTER (WHERE ul.residual_amount <> 0)
    INTO v_alloc, v_resid, residual_rows
    FROM order_split_unit_line ul
   WHERE ul.tenant_id = $1 AND ul.split_id = $2;

  UPDATE order_split
     SET unit_count = units,
         distinct_warehouse_count = whs,
         distinct_carrier_count = cars,
         total_qty = v_total_qty,
         allocated_amount_total = v_alloc,
         residual_amount_total = v_resid
   WHERE tenant_id = $1 AND id = $2;

  -- 🔴 **金额守恒（本模块最核心的不变量）**：
  --    各履约单元分摊额 + 尾差 必须**恰好等于**订单总金额。
  --    蓝图加粗句「订单总金额与买家视角不变」在这里变成可执行约束 ——
  --    差一分钱提交就会被拒，而不是等对账时才发现这一单少收了。
  IF round(v_alloc + v_resid, 4) <> round(ord_total, 4) THEN
    RAISE EXCEPTION '拆单金额不守恒：各单元分摊合计 % + 尾差 % = % ，不等于订单总金额 %',
      v_alloc, v_resid, round(v_alloc + v_resid, 4), ord_total USING ERRCODE = '23514';
  END IF;

  -- **尾差至多一行承载**：两行都带 residual 时「这笔尾差归谁」就没有答案，
  -- 对账时无法解释。正常的 HALF_UP 分摊只会产生一行尾差。
  IF residual_rows > 1 THEN
    RAISE EXCEPTION '拆单尾差出现在 % 行上，至多允许 1 行承载（尾差必须有确定归属）',
      residual_rows USING ERRCODE = '23514';
  END IF;
END $$;

CREATE FUNCTION guard_order_split_totals() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM recalc_order_split(NEW.tenant_id, NEW.split_id);
  RETURN NULL;
END $$;

-- ⚠️ **UPDATE OF 列清单是防死循环的关键，不是可选的优化**：
-- 本触发器自己会 UPDATE order_split_unit 的 line_count / total_qty / allocated_amount。
-- 若监听整行，更新自己写的那几列会**再次排队**一个本触发器的事件
-- → 每轮又写一次同样的值 → 提交时无限循环，连接被挂死。
-- 只监听「谁属于哪个拆单」与「单元状态」这两类真正影响汇总口径的变更。
CREATE CONSTRAINT TRIGGER order_split_totals_guard
  AFTER INSERT OR DELETE OR UPDATE OF split_id, unit_status
  ON order_split_unit
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_split_totals();

-- 明细行侧的触发器走同一段实算：单元行一旦被删（连带明细行），
-- 汇总与守恒也必须重算，否则头上的 unit_count 会永远停在被删之前的值。
CREATE FUNCTION guard_order_split_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM recalc_order_split(NEW.tenant_id, NEW.split_id);
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER order_split_allocation_guard
  AFTER INSERT OR DELETE OR UPDATE OF unit_id, allocated_amount, residual_amount
  ON order_split_unit_line
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_split_allocation();

-- ---------------------------------------------------------------------------
-- 12. 单元状态不许比头更靠前
-- ---------------------------------------------------------------------------
-- DISPATCHED 的单元不能挂在 PLANNED 的头上，否则「方案还没确认、货已经发走了」
-- 这种记录能写进库。
CREATE FUNCTION guard_order_split_unit_status() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  head_status varchar(16);
BEGIN
  SELECT status INTO head_status FROM order_split
    WHERE tenant_id = NEW.tenant_id AND id = NEW.split_id;
  IF head_status IS NULL THEN RETURN NULL; END IF;
  IF NEW.unit_status IN ('ALLOCATED','DISPATCHED')
     AND head_status NOT IN ('CONFIRMED','DISPATCHED') THEN
    RAISE EXCEPTION '拆单头处于 %，履约单元不能是 %（单元不许比头更靠前）',
      head_status, NEW.unit_status USING ERRCODE = '23514';
  END IF;
  IF NEW.unit_status = 'CANCELLED' AND head_status <> 'CANCELLED' THEN
    RAISE EXCEPTION '拆单头未作废，履约单元不能是 CANCELLED' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER order_split_unit_status_guard
  AFTER INSERT OR UPDATE OF unit_status ON order_split_unit
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_split_unit_status();

-- ---------------------------------------------------------------------------
-- 13. 行级明细的取值域复核（allocated_qty 不得超过订单行数量）
-- ---------------------------------------------------------------------------
-- order_source=SALES_ORDER 时可以拿订单行真值复核；快照来源没有真值可比，
-- 此时只能校验「同一行在一个拆单里只落一个单元」（已由 UNIQUE 表达）。
-- 这条刻意做成**只在能对账时生效**，而不是拿一个必假的上界去卡快照路径。
CREATE FUNCTION guard_order_split_line_qty() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  ordered_qty numeric(19,6);
  sum_alloc numeric(19,6);
BEGIN
  PERFORM 1 FROM order_split WHERE tenant_id = NEW.tenant_id AND id = NEW.split_id;
  IF NOT FOUND THEN RETURN NULL; END IF;

  -- 同一行的分摊数量合计就是这一行的出货总量；单元数量由 allocated_qty 承载。
  SELECT coalesce(sum(ul.allocated_qty),0) INTO sum_alloc
    FROM order_split_unit_line ul
   WHERE ul.tenant_id = NEW.tenant_id AND ul.split_id = NEW.split_id
     AND ul.order_line_ref = NEW.order_line_ref;

  -- 只在订单行真值可取时复核（uuid 形态的 order_line_ref 且能在 sales_order_line 命中）。
  BEGIN
    SELECT l.quantity INTO ordered_qty
      FROM sales_order_line l
     WHERE l.tenant_id = NEW.tenant_id AND l.id::text = NEW.order_line_ref;
  EXCEPTION WHEN others THEN ordered_qty := NULL;
  END;

  IF ordered_qty IS NOT NULL AND sum_alloc > ordered_qty THEN
    RAISE EXCEPTION '订单行 % 分摊数量合计 % 超过订单行数量 %：拆单不能发货发超',
      NEW.order_line_ref, sum_alloc, ordered_qty USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER order_split_line_qty_guard
  AFTER INSERT OR UPDATE OF order_line_ref, allocated_qty ON order_split_unit_line
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_order_split_line_qty();

-- ---------------------------------------------------------------------------
-- 14. 状态推进函数（SECURITY DEFINER，与 V144 advance_order_consolidation_status 同形）
-- ---------------------------------------------------------------------------
-- 单向状态机 PLANNED → CONFIRMED → DISPATCHED，以及任一非终态 → CANCELLED。
-- CANCELLED 不可离开（要重来就新建一次拆单）；DISPATCHED 之后不可作废 ——
-- 货已经进履约了，此时「作废」在业务上等于让仓库凭空多一个包裹。
CREATE FUNCTION advance_order_split_status(uuid,varchar,uuid,varchar,timestamptz)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE s order_split%ROWTYPE;
BEGIN
  SELECT * INTO s FROM order_split
    WHERE tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid AND id = $1 FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION '拆单不存在' USING ERRCODE='P40401'; END IF;
  IF $2 = 'CONFIRMED' AND s.status = 'PLANNED' THEN
    IF (SELECT count(*) FROM order_split_unit
         WHERE tenant_id=s.tenant_id AND split_id=s.id AND unit_status <> 'CANCELLED') < 1 THEN
      RAISE EXCEPTION '拆单至少要有一个履约单元才能确认（本模块是 1→N）' USING ERRCODE='23514';
    END IF;
    UPDATE order_split SET status='CONFIRMED', confirmed_at=$5, confirmed_by_membership_id=$3,
           updated_at=now(), version=version+1 WHERE tenant_id=s.tenant_id AND id=s.id;
  ELSIF $2 = 'DISPATCHED' AND s.status = 'CONFIRMED' THEN
    UPDATE order_split SET status='DISPATCHED', dispatched_at=$5, dispatched_by_membership_id=$3,
           updated_at=now(), version=version+1 WHERE tenant_id=s.tenant_id AND id=s.id;
    UPDATE order_split_unit SET unit_status='DISPATCHED', dispatched_at=$5,
           updated_at=now()
     WHERE tenant_id=s.tenant_id AND split_id=s.id AND unit_status IN ('PLANNED','ALLOCATED');
  ELSIF $2 = 'CANCELLED' AND s.status IN ('PLANNED','CONFIRMED') THEN
    IF btrim(coalesce($4,'')) = '' THEN
      RAISE EXCEPTION '作废必须给原因' USING ERRCODE='23514';
    END IF;
    UPDATE order_split SET status='CANCELLED', cancelled_at=$5, cancel_reason=$4,
           updated_at=now(), version=version+1 WHERE tenant_id=s.tenant_id AND id=s.id;
    UPDATE order_split_unit SET unit_status='CANCELLED', updated_at=now()
     WHERE tenant_id=s.tenant_id AND split_id=s.id;
    -- 连带撤销未落定的预占指令：方案没了，还让 warehouse 去锁货就是在锁一堆没人会取的货。
    UPDATE order_split_reservation_command SET command_status='CANCELLED', updated_at=now()
     WHERE tenant_id=s.tenant_id AND split_id=s.id AND command_status='REQUESTED';
  ELSE
    RAISE EXCEPTION '非法状态流转 % → %', s.status, $2 USING ERRCODE='23514';
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 15. append-only：事件流不可改写（照 V144 / V92 同一纪律）
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_split_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：拆单事件是回传与对账的证据',
    TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_split_event_append_only BEFORE UPDATE OR DELETE ON order_split_event
  FOR EACH ROW EXECUTE FUNCTION guard_split_event_append_only();

-- ---------------------------------------------------------------------------
-- 16. 多租户：RLS + 授权（与 trade V92 第 9 节、V144 第 10 节同一套写法）
-- ---------------------------------------------------------------------------
-- 十张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 拆单尤其不能省：拆单的对象是**一张订单**，而订单里的收货人姓名 / 地址 /
-- 货权法人是全系统最敏感的一组数据。一旦漏掉租户谓词就是**跨租户拆单**——
-- 两家互不相识的公司的订单会被切到对方的仓去，双方还互相看到对方的买家地址。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'order_split_rule','order_split_warehouse','order_split_stock_snapshot',
    'order_split','order_split_unit','order_split_unit_line','order_split_candidate',
    'order_split_reservation_command','order_split_writeback_task','order_split_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_split_rule,order_split_warehouse,order_split_stock_snapshot,
  order_split,order_split_unit,order_split_unit_line,order_split_candidate,
  order_split_reservation_command,order_split_writeback_task,order_split_event TO trade_app;
-- 第 15 节的 REVOKE 写在 GRANT 之前会被覆盖回去，这里按顺序重放：明确「授权在前、收回在后」。
REVOKE UPDATE,DELETE ON order_split_event FROM trade_app;
GRANT EXECUTE ON FUNCTION advance_order_split_status(uuid,varchar,uuid,varchar,timestamptz) TO trade_app;
-- 本迁移**不 ALTER 任何既有表**（sales_order / sales_order_line 全部只读，
-- 且只在 order_source='SALES_ORDER' 时按 tenant 读一次），跨车道 DDL 回归面 = 0。
