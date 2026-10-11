-- 9.1.2.2 手工订单：非平台来源订单（独立站线下、B2B、代发、样机单）的录入。
--
-- 蓝图 09-order-and-fulfillment.md 9.1.2.2 原文：
--   职责：支持非平台来源订单（独立站线下、B2B、代发、样机单）的录入，含客户选择、
--         商品行、收货地址、费用与支付方式。
--   输入：表单录入或导入；客户档案（03 party，已交付）；价格来源（05 价目，D6 未交付）。
--   输出：手工销售订单（来源 = MANUAL）+ 后续同样进入审单履约流程。
--   关系：上游 = 3.6.1.2 客户档案；下游 = 9.2 审单。
--         **手工单与平台单共用同一条履约链路，只有来源标记不同，避免两套流程。**
--
-- 归属：**trade 服务**（实体目录 db/schemas/v2/README.md「trade：销售、采购、售后与履约需求」
-- 把 sales_order / sales_order_line / order_address_snapshot / order_charge /
-- order_status_history 整簇划归 trade；蓝图 README 系统地图 09 行后端主责服务亦为 trade）。
-- 前端路由 /orders/manual，后端命名空间按服务走 /trade/sales-orders
-- （与 9.3.2.2 前端 /orders/fulfillment/routing、后端 /trade/fulfillment/routing，
--   6.4.1.1 前端 /purchase/processing、后端 /trade/processing 同一纪律）。
--
-- ===========================================================================
-- 一、最重要的一条：**来源是一个字段，不是另一张表**（蓝图硬要求）
-- ===========================================================================
-- 蓝图加粗句「手工单与平台单共用同一条履约链路，只有来源标记不同，避免两套流程」
-- 是**验收项**，不是描述。它在本文件里的落法是：
--
--   * 只有一张 `sales_order`。**没有** manual_sales_order、platform_sales_order
--     或任何「手工订单专用表」——那样 D5 平台单落地时就必然另建一套，
--     而蓝图要的恰恰是「避免两套流程」；
--   * `order_source` 是 sales_order 上的一列，CHECK 词表已把将来 D5 的来源
--     （PLATFORM / AMAZON / SHOPIFY / EBAY / TIKTOK_SHOP / ALIBABA_1688）
--     **连同 MANUAL 一起**写在同一处，本车道只放行 MANUAL；
--   * 下游履约读的是 sales_order，不带来源过滤 —— 一条 SQL 同时服务两类单，
--     这就是「共用同一条链路」在代码里的样子；
--   * 唯一约束带 order_source 这一维（第三节），是「来源是维度而不是表」最硬的证据：
--     如果来源是一张表，它根本不需要在唯一约束里出现。
--
-- 本车道的 Service 只创建 order_source='MANUAL' 的行：order_source 由**服务端**按端点写死，
-- 不接受请求体传值（否则任何人可以伪造一张「平台单」，而它根本没有平台履约链路可走）。
-- 枚举里预留其它来源是给 D5 的，届时由 D5 的端点写入，同样不接受客户端指定。
--
-- ===========================================================================
-- 二、本迁移的回归面：ALTER 任何既有表 = 0
-- ===========================================================================
-- 与 V92 / V97 / V98 同一纪律：**只新建表，一行既有 DDL 都不改**。
--   * 不碰 V1 的 trade_tenancy_probe；
--   * 不碰 V92 的七张 fulfillment_* 履约表；
--   * 不碰 V97 的五张加工单表，也不碰 V98 的两张 BOM 冻结快照表。
-- 因此没有 ADD COLUMN / SET NOT NULL，也没有既有表被新 CHECK 或新触发器约束，
-- 跨车道 DDL 回归面 = 0，既有三个 trade 模块的集成测试不需要为本车道重跑。
-- 版本号 V99 按**服务内**独立递增（catalog/identity/warehouse/crm 各有自己的 V1 与 V30+），
-- trade 现有 V1/V92/V97/V98，V99 是下一个空位；只有同一服务内撞号才是事故。
--
-- ===========================================================================
-- 三、外部订单号**按来源范围唯一**（实体目录明文要求）
-- ===========================================================================
-- db/schemas/v2/README.md 对 sales_order 的原话：「外部订单号按来源范围唯一」。
-- 落成 `UNIQUE(tenant_id, order_source, external_order_no)`：
--   * 带 order_source 这一维是**必须的** —— Amazon 的订单号 123 与 Shopify 的 123
--     是两张毫不相干的单，只按 (tenant, external_order_no) 唯一会把两张真单判成重复；
--   * 手工单内部编号 `order_no` 另有 `UNIQUE(tenant_id, order_no)`，
--     它与外部单号**是两个不同的列、两条不同的约束**：手工单有我方单号（MO-…），
--     也可能记下客户给的参考号（PO 号），两者互不打架，也不与将来的平台单号打架；
--   * external_order_no 列本身 NOT NULL DEFAULT ''（纯手工单可以没有客户参考号），
--     但**空串不能参与唯一性**：一个租户完全可能有一百张单都没填客户参考号，
--     用普通 UNIQUE 的话第二张就会撞第一张 —— 「没填」被当成了「重复」。
--     PostgreSQL 的普通唯一约束把 NULL 视为互不相等、却把 '' 视为相等，
--     所以「空号不参与唯一性」只能靠**部分唯一索引**表达（见第 1 节的 UNIQUE 注释
--     与第九节的 sales_order_external_no_unique）。本车道第一版就是漏了这一点，
--     由 ManualOrderIntegrationTest「未填外部订单号不算重复」当场抓出来。
--
-- ===========================================================================
-- 四、快照纪律（蓝图硬要求 3 与 4）
-- ===========================================================================
-- 硬要求 3「订单行保留快照：SKU / 规格 / 单价 / 税 / 折扣 / SKU 映射版本。商品参数事后被改，
-- 历史订单行不得跟着变」，对齐 db/schemas/v2/02 与 03 号文档对
-- `sales_order_line_attribute_snapshot` 的口径（「保存交易相关属性的名称、值、单位及来源版本…
-- 不回查实时 SPU 改写历史」）：
--   * sales_order_line 上有 sku_code / sku_name / spec_snapshot **快照列**与
--     sku_mapping_version（SKU 映射版本）——**没有**指向 catalog 的实时查询；
--   * order_line_attribute_snapshot 一行一个「属性名 / 属性值 / 单位 / 来源版本」，
--     整行 append-only（REVOKE + 触发器钉死），事后改 catalog 改不到历史单据；
--   * 单价与税、折扣是**本单录入时的值**，05 价目（D6 未交付）之前不引用任何价目服务，
--     也不写死任何单价。
-- 硬要求 4「地址是快照 order_address_snapshot，不是引用活地址 —— 买家改地址不能改写历史订单」：
--   * 收货 / 账单地址各一行 order_address_snapshot，**不建**指向 3.6.3.1 地址的
--     外键（跨服务本就禁 FK），也**不回查**对方活地址；
--   * 提交后触发器冻结它，让「买家改了地址」在数据上根本改不动历史单。
--
-- ===========================================================================
-- 五、金额与数量
-- ===========================================================================
-- 金额 numeric(19,4)（与 V92 的 cost / freight 同精度：跨币种下单时 JPY 无小数、
-- KWD 有三位，固定 2 位会把某些币种截掉，固定 4 位两边都放得下）；
-- 数量 numeric(19,6)（与 V97 / catalog V25 同精度）。
-- 全程 BigDecimal，禁浮点。结算口径统一 HALF_UP 到分（服务层 setScale(2)），
-- 读回 scale 4 由集成测试逐值断言。
-- 时间一律 timestamptz，绑定 ?::timestamptz + OffsetDateTime（禁 Timestamp.valueOf）。

-- ===========================================================================
-- 2. 销售订单头（**本模块的事实表**：通用销售订单，来源是一个字段）
-- ===========================================================================
CREATE TABLE sales_order (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- **我方单号**：租户内唯一且建后不可改。手工单由服务层按 MO- 前缀生成，
  -- 将来平台单用同一张表写入自己的单号 —— 两者在**不同的约束**下各管各的。
  order_no varchar(64) NOT NULL CHECK(btrim(order_no)<>''),
  -- **来源是字段，不是表**（第一节）。本车道只写 MANUAL，其余留给 D5 平台单同步。
  -- 词表刻意**已经包含**将来的平台来源：D5 落地时不需要 ALTER 改 CHECK，
  -- 而「加值」这条纪律（R6，V30 第七节）在这里同样适用。
  order_source text NOT NULL CHECK(order_source IN (
    'MANUAL','PLATFORM','AMAZON','SHOPIFY','EBAY','TIKTOK_SHOP','ALIBABA_1688')),
  -- 手工单子类：独立站线下 / B2B / 代发 / 样机单（蓝图职责句点名的四种来源）。
  -- 只对手工单有意义，故 DB 不强制 —— 平台单留空即可。
  manual_kind text NOT NULL DEFAULT ''
    CHECK(manual_kind IN ('','OFFLINE_STORE','B2B','DROPSHIP','SAMPLE')),
  -- **外部订单号**：客户侧的参考号（B2B 的 PO 号、代发方的单号）。
  -- 唯一性**带来源这一维**（第三节）。
  external_order_no varchar(128) NOT NULL DEFAULT '',
  -- 币种：ISO 4217 alpha-3。手工单按币种表单录入，不从 05 价目取（该模块 D6 未交付）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- **支付方式**（蓝图输入句「费用与支付方式」）：账期、预付、货到付、
  -- 银行转账、货代代收。手工单**不接支付网关**，这里记的是约定而不是实收。
  payment_method text NOT NULL CHECK(payment_method IN (
    'PREPAID','COD','BANK_TRANSFER','CREDIT_TERM','FREIGHT_FORWARDER','OTHER')),
  -- 账期天数：仅 CREDIT_TERM 有意义（与 3.6.1.2 的 payment_terms_days 同一口径，
  -- 整数天不是月 —— 「30 天」在某个月差 1 天）。
  -- 账期必带天数、非账期不许带天数：两条 CHECK 合起来让「赊 90 天」和
  -- 「预付 + 90 天账期」这种自相矛盾的档案进不来（与 V30 R1 同源）。
  payment_terms_days int NOT NULL DEFAULT 0 CHECK(payment_terms_days >= 0 AND payment_terms_days <= 365),
  -- **状态机**：DRAFT 草稿 → SUBMITTED 已提交（进入 9.2 审单）。
  -- 本车道**不实现审单**：9.2.1.1 待审队列是独立模块，审单结论由它推进，
  -- 所以这里只到 SUBMITTED 为止。后续状态由 9.2 用同样的方式加值。
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','SUBMITTED')),
  submitted_at timestamptz,
  -- 金额汇总（见第五节口径）。存汇总而不是每次现算，是为了让列表页排序与筛选
  -- 能直接走索引与 SQL 聚合；明细真值仍在 sales_order_line 与 order_charge，
  -- 并由 2.4 的触发器保证三者**永远相等**。
  subtotal_amount numeric(19,4) NOT NULL DEFAULT 0,
  discount_amount numeric(19,4) NOT NULL DEFAULT 0,
  tax_amount numeric(19,4) NOT NULL DEFAULT 0,
  shipping_amount numeric(19,4) NOT NULL DEFAULT 0,
  -- 「其他费用」汇总（PACKAGING 特殊包装 / OTHER 清关费等）：
  -- 没有这一列时这两类费用会被静默塞进运费，报表上「运费」就永远对不上运单。
  other_amount numeric(19,4) NOT NULL DEFAULT 0,
  grand_total_amount numeric(19,4) NOT NULL DEFAULT 0,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_no),
  -- **外部订单号按来源范围唯一**（第三节）。带 order_source 这一维是必须的：
  -- Amazon 的 123 与 Shopify 的 123 是两张毫不相干的单。
  -- ⚠️ 这里**不写**普通 UNIQUE：external_order_no 是 NOT NULL DEFAULT ''，
  -- 而 PostgreSQL 把 '' 视为相等 —— 普通唯一约束会让「第一张没填参考号的单」
  -- 撞上「第二张没填参考号的单」。空号不参与唯一性必须用部分唯一索引表达，
  -- 见第九节的 sales_order_external_no_unique。
  -- 金额一律非负（折扣是 order_charge 里的负数行，不在这里做减法）。
  CONSTRAINT sales_order_amounts_non_negative
    CHECK (subtotal_amount >= 0 AND discount_amount >= 0 AND tax_amount >= 0
      AND shipping_amount >= 0 AND other_amount >= 0 AND grand_total_amount >= 0),
  -- **已提交必须带提交时刻**，草稿不许带（与 V97 的 released_at 自洽同一写法）。
  CONSTRAINT sales_order_submitted_needs_time
    CHECK (status <> 'SUBMITTED' OR submitted_at IS NOT NULL),
  CONSTRAINT sales_order_draft_has_no_submit_time
    CHECK (status <> 'DRAFT' OR submitted_at IS NULL),
  -- 手工单必须说明子类（B2B / 代发 / 样机的下游处理完全不同，来源只写 MANUAL 不够用）；
  -- 非手工单**不许**带 manual_kind —— 「平台单 + 代发」在 9.6 代发里是另一张表的事，
  -- 混在这里会让一张平台单的履约路径出现两个答案。
  CONSTRAINT sales_order_manual_kind_matches_source
    CHECK ((order_source = 'MANUAL') = (manual_kind <> '')),
  -- 账期与非账期必须自洽（与 V30 规则 R1 同源）。
  CONSTRAINT sales_order_payment_terms_self_consistent
    CHECK ((payment_method = 'CREDIT_TERM') = (payment_terms_days > 0))
);

-- ===========================================================================
-- 1. 客户档案（下游引用的**逻辑 ID + 快照**，本服务不拥有 party）
-- ===========================================================================
-- 上游 3.6.1.1/3.6.1.2（catalog V27/V30）已交付，party 与 party_customer_profile
-- 在 **catalog 的库**里。本表是**订单侧的客户快照**，不是客户档案本身：
--   * customer_party_id 只存逻辑 ID，不建跨库 FK、不跨库 JOIN（AGENTS.md 模块边界纪律）；
--   * customer_code / customer_name / customer_type / default_currency 一并快照，
--     客户改名、改级别、改默认币种之后，历史订单仍显示下单当时的样子；
--   * 一单一客户：一张订单只能有一个买家，这是「客户选择」这步输入的直接后果。
CREATE TABLE sales_order_party_snapshot (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, order_id uuid NOT NULL,
  -- catalog 的 party.id 逻辑引用（跨服务不建外键）。
  customer_party_id uuid NOT NULL,
  -- **快照四件套**：客户改名 / 改级别 / 改默认币种都不影响历史订单。
  customer_code varchar(64) NOT NULL CHECK(btrim(customer_code)<>''),
  customer_name varchar(200) NOT NULL CHECK(btrim(customer_name)<>''),
  customer_type text NOT NULL CHECK(customer_type IN ('ORGANIZATION','INDIVIDUAL')),
  -- 客户档案上的默认币种（ISO 4217 alpha-3）。**只作快照提示，不自动采用**：
  -- 订单币种由订单自己的 currency 决定，两者不同是正常业务（客户改过默认币种）。
  customer_default_currency char(3) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一单一客户快照：两张快照时「这张订单是卖给谁」有两个答案。
  UNIQUE(tenant_id,order_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 3. 销售订单行（**含全部快照列，不引用实时商品**）
-- ===========================================================================
CREATE TABLE sales_order_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, order_id uuid NOT NULL,
  -- 行号：订单内可读次序（拣货、打印、对账都按它说「第几行」）。
  line_no int NOT NULL CHECK(line_no > 0),
  -- **SKU 快照**：catalog 的逻辑 ID + 当时的编码与名称。
  -- 不建跨库 FK、不回查 catalog —— 商品事后改名或改规格都不影响历史订单行。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code)<>''),
  sku_name varchar(200) NOT NULL CHECK(btrim(sku_name)<>''),
  -- **规格快照**：下单当时的规格组合（如「黑色 / L」），人可读即可
  -- （结构化规格由 order_line_attribute_snapshot 逐属性承载，这里是列表页直接显示的一行摘要）。
  spec_snapshot varchar(300) NOT NULL DEFAULT '',
  -- **SKU 映射版本**：这张行的 SKU 是按 catalog 哪一版映射对上的（与 trade V97 冻结
  -- bom_version_no、warehouse V1 sku_ref 同一纪律）。SKU 重新映射之后，
  -- 历史订单行仍能说清「当时按哪一版对上的号」。
  sku_mapping_version varchar(32) NOT NULL DEFAULT '',
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  -- **单价由表单录入**：05 价目（卷 05，D6 未交付）尚未交付，
  -- 所以这里既不引用任何价目服务，也不写死任何单价 —— 由人按实际成交价填。
  unit_price numeric(19,4) NOT NULL CHECK(unit_price >= 0),
  -- 行级折扣与税额（同样表单录入）。**折扣是负数**，与费用行的正负口径一致。
  discount_amount numeric(19,4) NOT NULL DEFAULT 0,
  tax_amount numeric(19,4) NOT NULL DEFAULT 0,
  -- 行小计 = 单价 × 数量，**服务端算**并落库；DB 再用 CHECK 复核一次
  -- （见下方 line_amount_matches）。把口径钉在 DB 是因为「行小计怎么算」
  -- 一旦出现第二个实现，订单总额就会有两个答案。
  line_amount numeric(19,4) NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一张单里同一个 SKU 只能一行：两行时「这个 SKU 买了几个」无法回答，
  -- 而拣货与开票都按 (order, sku) 取一个数。
  UNIQUE(tenant_id,order_id,sku_id),
  -- 行号在单内唯一（否则「第 3 行」指向两行）。
  UNIQUE(tenant_id,order_id,line_no),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE,
  -- **折扣行不允许出现正的折扣额**：正数折扣会让「减钱」变成「加钱」，
  -- 而这一列的名字与展示文案都写着折扣。
  -- 行小计 = 单价 × 数量，**DB 侧独立复算**一次（round 两位参数都是精确数值运算）。
  -- 服务层算错时提交会被这里挡下，而不是等对账时才发现总额对不上。
  CONSTRAINT sales_order_line_amount_matches
    CHECK (line_amount = round(unit_price * quantity, 4)),
  CONSTRAINT sales_order_line_discount_is_non_positive CHECK (discount_amount <= 0),
  CONSTRAINT sales_order_line_amounts_non_negative
    CHECK (tax_amount >= 0 AND line_amount >= 0)
);

-- ===========================================================================
-- 4. 订单行属性快照（对齐 sales_order_line_attribute_snapshot 的口径）
-- ===========================================================================
-- db/schemas/v2/03 号文档：「保存交易相关属性的名称、值、单位及来源版本。报关、履约等在
-- 各自单据形成时冻结所需属性；不必复制所有营销文案，**不回查实时 SPU 改写历史**。」
-- 一行 = 一个「名称 / 值 / 单位 / 来源版本」四元组，append-only。
CREATE TABLE order_line_attribute_snapshot (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, order_id uuid NOT NULL, line_id uuid NOT NULL,
  attribute_name varchar(120) NOT NULL CHECK(btrim(attribute_name)<>''),
  attribute_value varchar(300) NOT NULL DEFAULT '',
  -- 单位：属性有量纲时必填（如「克」）。**不引用 3.3.3 计量单位表**：
  -- 那是另一个服务的写入所有权，这里存的是快照文本。
  attribute_unit varchar(32) NOT NULL DEFAULT '',
  -- 来源版本：这条属性取自 catalog 的哪一版。改 catalog 改不到这里。
  source_version varchar(32) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,line_id) REFERENCES sales_order_line(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 5. 地址快照（硬要求 4：**不是引用活地址**）
-- ===========================================================================
-- 一张订单两个地址角色：收货（必填）与账单（可与收货相同，但**仍是独立快照行**——
-- 合并成一行会让「收货改了、账单没改」这种正常业务无法表达）。
-- 字段刻意做成订单侧自己的扁平列而不复用 3.6.3.1 的地址表：
-- 地址会变，订单上的地址是**下单当时**的那一份。
CREATE TABLE order_address_snapshot (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, order_id uuid NOT NULL,
  address_role text NOT NULL CHECK(address_role IN ('SHIPPING','BILLING')),
  recipient_name varchar(120) NOT NULL CHECK(btrim(recipient_name)<>''),
  recipient_phone varchar(64) NOT NULL DEFAULT '',
  recipient_email varchar(254) NOT NULL DEFAULT '',
  company_name varchar(200) NOT NULL DEFAULT '',
  country_code char(2) NOT NULL CHECK(country_code ~ '^[A-Z]{2}$'),
  province varchar(120) NOT NULL DEFAULT '',
  city varchar(120) NOT NULL DEFAULT '',
  district varchar(120) NOT NULL DEFAULT '',
  postcode varchar(32) NOT NULL DEFAULT '',
  address_line1 varchar(300) NOT NULL CHECK(btrim(address_line1)<>''),
  address_line2 varchar(300) NOT NULL DEFAULT '',
  tax_id varchar(64) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个角色一条快照：两条收货地址时「货发到哪」有两个答案。
  UNIQUE(tenant_id,order_id,address_role),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 6. 订单费用（硬要求 2：**费用是表单的一部分**）
-- ===========================================================================
-- 「含……费用与支付方式」。费用是**独立的行**，不是把运费/折扣/税塞进订单头的四个列：
-- 订单头那四列是**汇总**（第五节），费用行是**明细**，两者由 7.4 的触发器强制相等。
-- 一行 = 一笔费用（运费、折扣、税费、其他）。
CREATE TABLE order_charge (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, order_id uuid NOT NULL,
  charge_type text NOT NULL CHECK(charge_type IN (
    'SHIPPING','DISCOUNT','TAX','PACKAGING','OTHER')),
  -- 展示名：OTHER 类费用的用途（清关费、特殊包装）靠它说明，
  -- 固定枚举说不了「这一笔 OTHER 到底是什么」。
  label varchar(120) NOT NULL DEFAULT '',
  -- **正数是加、负数是减**：折扣与优惠记负数。刻意不用
  -- 「charge_type=DISCOUNT 时取绝对值」那种写法 —— 那种写法下
  -- 一个填错的正折扣会在另一套实现里变成加钱，而数据上完全看不出来。
  amount numeric(19,4) NOT NULL,
  -- 费用层级：ORDER = 整单费用（不挂到具体某一行），LINE = 记在订单行名下的费用。
  -- ⚠️ 这里**不是**行级分摊明细：v2 README 提过「分摊之和等于头金额，保留舍入差额」，
  -- 但 9.1.2.2 的蓝图职责句只要求「费用是表单的一部分」。把分摊金额摊进行里会让
  -- 「这一行到底买了多少钱」出现两个答案（单价×数量 vs 含分摊的净额）。
  -- 真要做行级分摊时另开 order_charge_allocation 子表，别拿 charge_kind 当分摊记号。
  charge_kind text NOT NULL DEFAULT 'ORDER'
    CHECK(charge_kind IN ('ORDER','LINE')),
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE,
  -- 折扣类费用必须是负数：正数折扣会让「减钱」变成「加钱」。
  CONSTRAINT order_charge_discount_is_negative
    CHECK (charge_type <> 'DISCOUNT' OR amount <= 0),
  -- 其余费用必须为正：运费税包装钱不会是「负运费」。
  CONSTRAINT order_charge_non_discount_is_positive
    CHECK (charge_type = 'DISCOUNT' OR amount >= 0)
);

-- ===========================================================================
-- 7. 状态事件流（append-only 留痕）
-- ===========================================================================
-- 「什么时候提交审单、谁提交的」必须留痕：订单进了 9.2 审单之后，
-- 审单结论追溯回来时第一个问题就是「这张单是谁在什么时候提上去的」。
CREATE TABLE sales_order_status_history (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, order_id uuid NOT NULL,
  event_type text NOT NULL CHECK(event_type IN ('CREATED','SUBMITTED')),
  from_status text NOT NULL,
  to_status text NOT NULL,
  reason text NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 8. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================

-- 8.1 **提交审单时，行 / 地址 / 费用必须已经齐备**。
--     延迟约束触发器，**回表读当前行**而不是看 NEW 的可变列 ——
--     服务层的写入顺序是「先插头 → 写行/地址/费用 → 提交」，
--     提交时约束触发器拿着的 NEW 仍可能是半成品（与 V92 第八节、V97 第 6.1 节同一个坑、同一个解法）。
CREATE FUNCTION guard_sales_order_submitted() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n_line int; n_addr int; head_no text;
BEGIN
 IF NEW.status <> 'SUBMITTED' THEN RETURN NULL; END IF;
 SELECT order_no INTO head_no FROM sales_order WHERE id = NEW.id;
 IF NEW.submitted_at IS NULL THEN
  RAISE EXCEPTION '订单「%」已提交却没有提交时刻：没有提交时刻就答不出这张单是什么时候进的审单队列', head_no
   USING ERRCODE='23514';
 END IF;
 SELECT count(*) INTO n_line FROM sales_order_line WHERE order_id = NEW.id;
 IF n_line = 0 THEN
  RAISE EXCEPTION '订单「%」不能提交：没有任何商品行。一张没有商品行的手工单在审单队列里无事可审，进了队列也只会占一个位子', head_no
   USING ERRCODE='23514';
 END IF;
 SELECT count(*) INTO n_addr FROM order_address_snapshot
  WHERE order_id = NEW.id AND address_role = 'SHIPPING';
 IF n_addr = 0 THEN
  RAISE EXCEPTION '订单「%」不能提交：没有收货地址快照。收货地址是下单当时冻结的那一份，没有它就无法发货', head_no
   USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM sales_order_party_snapshot WHERE order_id = NEW.id) THEN
  RAISE EXCEPTION '订单「%」不能提交：没有客户快照。「客户选择」是手工单的必备输入，缺了它订单挂不到任何对手方名下', head_no
   USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER sales_order_submitted_check
  AFTER INSERT OR UPDATE ON sales_order
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_submitted();

-- 8.2 **订单头汇总必须等于行与费用的真值**（第五节）。
--     同样用延迟约束触发器回表核对：分母（明细）可能有还没写进来的行，
--     当场算就会把「还没写完」误判成「汇总错了」。
CREATE FUNCTION guard_sales_order_totals() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_no text;
  v_subtotal numeric(19,4); v_discount numeric(19,4); v_tax numeric(19,4);
  v_shipping numeric(19,4); v_other numeric(19,4); v_total numeric(19,4);
BEGIN
 IF NEW.status = 'DRAFT' THEN RETURN NULL; END IF;   -- 草稿可以先有头后补行
 SELECT order_no INTO head_no FROM sales_order WHERE id = NEW.id;
 -- 汇总口径（服务层用**同一条公式**算一遍。两边各算一次是刻意的：
 -- 服务层算错了会在提交时被这里挡下，而不是等到对账时才发现总额对不上）：
 --   小计 = Σ 行小计（单价 × 数量）
 --   折扣 = −Σ 行折扣（行折扣恒为负）− Σ DISCOUNT 费用（费用折扣恒为负）→ 取正
 --   税额 = Σ 行税 + Σ TAX 费用
 --   运费 = Σ SHIPPING 费用
 --   其他 = Σ PACKAGING / OTHER 费用
 --   合计 = 小计 − 折扣 + 税额 + 运费 + 其他
 SELECT coalesce(sum(line_amount),0)
   INTO v_subtotal FROM sales_order_line WHERE order_id = NEW.id;
 v_discount := coalesce((SELECT -sum(discount_amount) FROM sales_order_line WHERE order_id=NEW.id),0)
             + coalesce((SELECT -sum(amount) FROM order_charge WHERE order_id=NEW.id AND charge_type='DISCOUNT'),0);
 v_tax := coalesce((SELECT sum(tax_amount) FROM sales_order_line WHERE order_id=NEW.id),0)
        + coalesce((SELECT sum(amount) FROM order_charge WHERE order_id=NEW.id AND charge_type='TAX'),0);
 v_shipping := coalesce((SELECT sum(amount) FROM order_charge WHERE order_id=NEW.id AND charge_type='SHIPPING'),0);
 v_other := coalesce((SELECT sum(amount) FROM order_charge WHERE order_id=NEW.id AND charge_type IN ('PACKAGING','OTHER')),0);
 v_total := v_subtotal - v_discount + v_tax + v_shipping + v_other;
 IF NEW.subtotal_amount <> v_subtotal THEN
  RAISE EXCEPTION '订单「%」的商品小计（%）与明细行真值（%）不一致：头与行两个答案会让订单总额无法对账', head_no, NEW.subtotal_amount, v_subtotal
   USING ERRCODE='23514';
 END IF;
 IF NEW.discount_amount <> v_discount THEN
  RAISE EXCEPTION '订单「%」的折扣合计（%）与明细真值（%）不一致：优惠到底减了多少必须只有一个答案', head_no, NEW.discount_amount, v_discount
   USING ERRCODE='23514';
 END IF;
 IF NEW.tax_amount <> v_tax THEN
  RAISE EXCEPTION '订单「%」的税额合计（%）与明细真值（%）不一致', head_no, NEW.tax_amount, v_tax
   USING ERRCODE='23514';
 END IF;
 IF NEW.shipping_amount <> v_shipping THEN
  RAISE EXCEPTION '订单「%」的运费合计（%）与明细真值（%）不一致', head_no, NEW.shipping_amount, v_shipping
   USING ERRCODE='23514';
 END IF;
 IF NEW.other_amount <> v_other THEN
  RAISE EXCEPTION '订单「%」的其他费用合计（%）与明细真值（%）不一致', head_no, NEW.other_amount, v_other
   USING ERRCODE='23514';
 END IF;
 IF NEW.grand_total_amount <> v_total THEN
  RAISE EXCEPTION '订单「%」的订单总额（%）与「小计−折扣+税+运费+其他」（%）不一致：总额是审单与财务对账的唯一口径，它不能有两个答案', head_no, NEW.grand_total_amount, v_total
   USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER sales_order_totals_check
  AFTER INSERT OR UPDATE ON sales_order
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_totals();

-- 8.3 **已提交的订单，行 / 地址 / 费用 / 客户快照一律冻结**。
--     这就是硬要求 3 与 4 的执行点：商品参数事后被改、买家事后改地址，
--     在数据上**改不动**已进审单队列的订单 —— 而不是靠界面不去提供编辑入口。
CREATE FUNCTION guard_sales_order_snapshot_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text; head_no text;
BEGIN
 SELECT status, order_no INTO parent_status, head_no
   FROM sales_order WHERE tenant_id = NEW.tenant_id AND id = NEW.order_id;
 IF parent_status IS NULL THEN RETURN NEW; END IF;   -- 头还没插完（同一事务内先插子表的场景）
 IF parent_status = 'SUBMITTED' THEN
  RAISE EXCEPTION '订单「%」已提交进审单队列，本行的内容不可再改：订单上的商品、地址、客户都是**下单当时**的快照，改了等于让「当时那笔交易是什么」在历史里出现两个答案。要改请先作废重录', head_no
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sales_order_line_frozen BEFORE UPDATE OR DELETE ON sales_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_snapshot_frozen();
CREATE TRIGGER order_address_snapshot_frozen BEFORE UPDATE OR DELETE ON order_address_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_snapshot_frozen();
CREATE TRIGGER order_charge_frozen BEFORE UPDATE OR DELETE ON order_charge
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_snapshot_frozen();
CREATE TRIGGER sales_order_party_snapshot_frozen BEFORE UPDATE OR DELETE ON sales_order_party_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_snapshot_frozen();

-- 8.4 **订单行的属性快照是 append-only**：REVOKE 之外再加触发器，
--     绕过服务层直连 SQL 也改不动（与 V97 6.5 同一个解法）。
CREATE FUNCTION guard_sales_order_trail_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是不可改写的快照表（%）：订单行的属性快照是「当时按哪一版商品、哪些规格成交」的举证凭据', TG_TABLE_NAME, TG_OP
  USING ERRCODE='23514';
END $$;
CREATE TRIGGER order_line_attribute_snapshot_append_only
  BEFORE UPDATE OR DELETE ON order_line_attribute_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_trail_append_only();

-- 8.5 **订单的身份列建后不可改**：单号、来源、客户、币种、支付方式。
--     改这些等于让「这笔交易当时是怎么记的」在历史里出现两个答案。
--     order_source 同样冻结：一张手工单改成平台单会让它凭空获得一条它从未走过的履约链路。
CREATE FUNCTION guard_sales_order_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.order_no IS DISTINCT FROM OLD.order_no THEN
  RAISE EXCEPTION '订单号建后不可改（% → %）：单号是这张单在系统里的唯一标识，改它等于让所有引用它的单据下一次读取就找不到它', OLD.order_no, NEW.order_no
   USING ERRCODE='23514';
 END IF;
 IF NEW.order_source IS DISTINCT FROM OLD.order_source THEN
  RAISE EXCEPTION '订单「%」的来源不可改（% → %）：来源决定了它走哪条履约链路。手工单与平台单共用一张表靠的就是这一列，把它改掉等于让一张单凭空获得一条它从未走过的链路', OLD.order_no, OLD.order_source, NEW.order_source
   USING ERRCODE='23514';
 END IF;
 IF NEW.external_order_no IS DISTINCT FROM OLD.external_order_no THEN
  RAISE EXCEPTION '订单「%」的外部订单号建后不可改（% → %）：外部号是与客户/平台对账时唯一能对上的键，改它等于让这次交易在对方系统里查无此单', OLD.order_no, OLD.external_order_no, NEW.external_order_no
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sales_order_identity BEFORE UPDATE ON sales_order
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_identity();

-- 8.6 **已提交是终态**：退回草稿等于让「这张单到底进没进审单队列」出现两个答案。
CREATE FUNCTION guard_sales_order_submitted_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status = 'SUBMITTED' AND NEW.status = 'DRAFT' THEN
  RAISE EXCEPTION '订单「%」已提交进审单队列，不允许退回草稿态：退回之后没人说得清它有没有被审过', OLD.order_no
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sales_order_submitted_terminal BEFORE UPDATE ON sales_order
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_submitted_terminal();

-- 8.7 状态事件流 append-only（REVOKE 之外再加触发器）。
CREATE TRIGGER sales_order_status_history_append_only
  BEFORE UPDATE OR DELETE ON sales_order_status_history
  FOR EACH ROW EXECUTE FUNCTION guard_sales_order_trail_append_only();

-- ===========================================================================
-- 9. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 手工单列表：按来源 + 状态 + 创建时间倒序（最新录的在前）。
CREATE INDEX sales_order_by_source ON sales_order(tenant_id, order_source, status, created_at, id);
-- **外部订单号按来源范围唯一**（第三节）：带 order_source 这一维。
-- 写成**部分唯一索引**（而不是 UNIQUE 约束）是必须的：external_order_no 是
-- NOT NULL DEFAULT ''，而 PostgreSQL 的普通唯一约束把 '' 视为相等 —— 一个租户
-- 完全可以有任意多张没填客户参考号的单，普通 UNIQUE 会让第二张撞上第一张，
-- 把「没填参考号」误判成「重复」。WHERE 条件把空号排除在唯一性之外。
-- 本车道第一版就是漏了这一点，由 ManualOrderIntegrationTest
-- 「未填外部订单号不算重复」当场抓出来 —— 集成测试跑过 DDL 之后才算数。
CREATE UNIQUE INDEX sales_order_external_no_unique
  ON sales_order(tenant_id, order_source, external_order_no)
  WHERE external_order_no <> '';
-- 按状态筛（提交进审单的存量）。部分索引只覆盖已提交的行。
CREATE INDEX sales_order_submitted ON sales_order(tenant_id, submitted_at, id)
  WHERE status = 'SUBMITTED';
-- 按客户反查（问「这个客户有哪些手工单」）。customer_party_id 在**客户快照表**上，
-- 不在订单头上（一单一客户快照，见第 2 节），索引跟着建在真正有这一列的表上。
CREATE INDEX sales_order_party_by_customer
  ON sales_order_party_snapshot(tenant_id, customer_party_id, created_at, id);
-- 按外部订单号查（与客户对账的入口）—— 唯一约束已建 (tenant, source, external_no) 索引，
-- 这里**不再重复建**：前缀相同的那条唯一索引已经能服务按来源的查询。
-- 行：按订单读明细（详情页主查询形状）。
CREATE INDEX sales_order_line_by_order ON sales_order_line(tenant_id, order_id, line_no);
-- 属性快照：按行读（报关与履约在各自单据形成时读这份冻结）。
CREATE INDEX order_line_attribute_by_line ON order_line_attribute_snapshot(tenant_id, line_id, attribute_name);
CREATE INDEX order_line_attribute_by_order ON order_line_attribute_snapshot(tenant_id, order_id);
-- 地址快照：按订单读两个角色。
CREATE INDEX order_address_by_order ON order_address_snapshot(tenant_id, order_id, address_role);
-- 费用：按订单读明细 + 按类型聚合。
CREATE INDEX order_charge_by_order ON order_charge(tenant_id, order_id, charge_type);
-- 状态事件流：按订单读时间线。
CREATE INDEX sales_order_status_history_by_order
  ON sales_order_status_history(tenant_id, order_id, occurred_at, id);
-- 客户快照：按订单读（UNIQUE(tenant_id,order_id) 已建唯一索引，不再重复）。

-- ===========================================================================
-- 10. 多租户：RLS + 授权（与 trade V1 / V92 / V97 同一套写法）
-- ===========================================================================
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它（AccessService.Access 来自已认证成员关系）。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'sales_order','sales_order_line','sales_order_party_snapshot',
   'order_address_snapshot','order_charge',
   'order_line_attribute_snapshot','sales_order_status_history'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  sales_order,sales_order_line,sales_order_party_snapshot,
  order_address_snapshot,order_charge,
  order_line_attribute_snapshot,sales_order_status_history TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  sales_order,sales_order_line,sales_order_party_snapshot,
  order_address_snapshot,order_charge,
  order_line_attribute_snapshot,sales_order_status_history TO starcon_app;
-- 「授权在前、收回在后」，顺序不能颠倒，否则下面这两条会被上面的 GRANT 覆盖回去：
-- 属性快照与状态事件流是 append-only 举证凭据，收回改写权。
REVOKE UPDATE,DELETE ON order_line_attribute_snapshot FROM trade_app;
REVOKE UPDATE,DELETE ON sales_order_status_history FROM trade_app;
-- 已提交订单的行/地址/费用/客户快照在触发器里被冻结，这里**同时收回删除权**：
-- ON DELETE CASCADE 之外的显式删除同样不该发生。
REVOKE DELETE ON sales_order_line,order_address_snapshot,order_charge,sales_order_party_snapshot FROM trade_app;

-- ---------------------------------------------------------------------------
-- 11. 与上下游的边界（本文件明确划出，不越界）
-- ---------------------------------------------------------------------------
--   * **05 价目（卷 05，D6 未交付）**：单价、折扣、税费**全部表单录入**，
--     本文件既不引用任何还不存在的价目服务，也不写死任何单价。价目落地后由
--     订单侧读取价目**填进表单**，落库形态不变。
--   * **3.6.1.2 客户档案（catalog V27/V30 已交付）**：只存逻辑 ID + 快照四件套，
--     **不建跨库 FK、不跨库 JOIN**。客户改名/改级别/改默认币种不影响历史订单。
--   * **9.2 审单（未交付）**：本文件的状态机只到 SUBMITTED。
--     审单结论由 9.2 用同样的「加值」方式扩展（V30 规则 R6 同一纪律），
--     本表**不需要**为它 ALTER 一次。
--   * **9.1.2.1 平台订单同步（D5，未交付）**：写的是**同一张 sales_order**，
--     order_source 取平台来源。届时不需要新建订单表，也不需要为本表改结构。
--   * **9.1.1.1 订单列表 / 9.1.1.2 订单详情**：那是**读投影**，
--     本车道是 sales_order 的**第一个写入所有者**，但**不替它们建表**，
--     也不由它们写入；跨服务不建 FK 或视图（AGENTS.md 模块边界纪律）。
--
-- 12. 新增函数全部带 sales_order_ / order_ 前缀，与 V1 / V92 / V97 / V98 的
--     既有函数名不冲突；表名 sales_order_* / order_* 与既有
--     processing_* / fulfillment_* 互不重名。