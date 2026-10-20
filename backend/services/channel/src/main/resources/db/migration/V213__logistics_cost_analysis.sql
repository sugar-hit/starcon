-- ===========================================================================
-- 10.6.1.1 成本分析（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.6.1.1 成本分析
--   - 职责：分析物流成本：单位重量/单均运费、头程分摊后到岸成本占比、各渠道成本对比、
--           超成本订单识别、头程费用分摊明细。
--   - 输入：实际运费与头程费用；包裹与发运记录。
--   - 输出：成本报表 + 分摊明细 + 异常订单清单。
--   - 关系：上游 = 10.2、10.4、12 财务；下游 = 5 定价、13 利润。
--           **物流成本常被低估**：真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，
--           报表必须全口径。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（零 ALTER 任何既有表，跨车道回归面 = 0）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 序列 / 索引 / 触发器 / 函数 / 策略**，一张既有表都不碰：
--   * 尾程运费（真实成本分量之一）来自 **trade** 服务的 `logistics_waybill_freight`
--     （V132），经授权 HTTP 端点 `GET /trade/logistics/waybills/list` 读。
--     **跨服务不建 FK、不建视图、不建跨库查询**（AGENTS.md 模块边界纪律）。
--   * 头程费用来自 **warehouse** 服务的 `first_mile_shipment`（V80），
--     经 `GET /warehouse/first-mile-shipments` 读。逻辑引用 source_shipment_id，
--     **不建 FK**（跨服务 FK 属禁止项）。
--   * 关税与到岸成本来自 **warehouse** 服务的 `duty_tax_estimate`（V90），
--     经 `GET /warehouse/duty-taxes` 读。逻辑引用，同样不建 FK。
--   * 包材（`packing_material`，warehouse V121）与仓储费：**全仓库没有任何成本列 /
--     没有任何仓储费表**。本迁移**不凭空造表**（造一张空表等于宣称「仓储费已交付」），
--     改为在能力声明里 available=false 并点名归属模块。
--   * 12 财务（finance 服务）**不存在**（backend/services/ 下只有 identity / catalog /
--     channel / warehouse / trade / crm）。头程费用分摊明细因此由**本服务自持事实**
--     落地，并在能力声明里如实标注这是「本服务自有口径」，
--     与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接。
--   * 复用 identity 已注册的 channel.operation.* 权限码，identity 零改动。
--
-- 表名走 `logistics_cost_*` 命名空间，与 V136 的 `logistics_channel_*`、
-- V140 的 `freight_*`、V141 的 `logistics_label_*` 并列而不混用。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 蓝图加粗句「报表必须全口径」的物理落点：**五个分量各占一位，缺一个也要留位**
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗句是本模块唯一不可让步的一条。把它落成「一张表里五个分量全有」是错的 ——
-- 因为包材成本与仓储费在本仓库**真的没有来源**（没有成本列 / 没有表）。
-- 硬造一个 0 值进去，比不造更糟：0 会被读成「包材不要钱」，而真相是「没人记这笔账」。
--
-- 所以本模块的口径是：
--   * **分量的"有没有"是一等事实**，不是 0/非 0。渠道服务的成本分量表
--     （logistics_cost_component）**逐个分量**记：可得 ⇒ 取数来源与取数口径；
--     不可得 ⇒ available=false + owner_module + 原因 + 对成本口径的影响。
--     报表在界面上把这五位**并排**显示，不可得的那一位显示「暂无数据 + 归属模块」，
--     配色中性，**不显示 0**。
--   * 任何「合计」都带 coverage（覆盖说明）：合总是**在已计入的分量上**的和，
--     并明确列出**没有计入哪些分量**、为什么。这是「全口径」在缺数据时的诚实形态。
--   * 比率分母为 0 ⇒ **null**，不是 0。null 与 0 在界面上长得不一样
--     （null = 暂无数据 / 中性色；0 = 真的就是这个数）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 蓝图 5.2.1.3「分摊规则变更会导致历史利润变化，因此调整必须留历史版本，
--     不能就地覆盖」—— 本迁移最核心的不变量
-- ---------------------------------------------------------------------------
-- 头程分摊规则（按数量 / 按重量 / 按申报价值 / 均摊）一旦变更，
-- 已经分摊过的历史成本就会变 —— 也就是**历史利润会变**。
-- 所以规则**不覆盖**：
--   * `logistics_cost_allocation_rule` 一行 = 一个**规则版本**；
--     (tenant_id, rule_code, version) 唯一，version 从 1 递增。
--   * 同一 rule_code **同时只能有一个 ACTIVE 版本**（部分唯一索引钉死）。
--     启用新版本必须先把旧版本置为 RETIRED —— 旧版本行**留在表里**。
--   * 已经被分摊引用过的规则版本**不可再改口径**（append-only 触发器）：
--     改了等于让已经出过的成本报表失去依据。
--   * 分摊单 `logistics_cost_allocation` 引用的是**具体的 rule_id + rule_version**，
--     不是「当前规则」。历史分摊单永远指向它当时用的那一个版本。
--   * 同一张头程发运单同一时刻只允许有一张「现行」分摊单（部分唯一索引，
--     status <> 'SUPERSEDED'）；用新规则重算 ⇒ 旧单转 SUPERSEDED 而不是被改写。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 结账时点（cost_as_of）—— 「成本按什么时点结的」必须可查
-- ---------------------------------------------------------------------------
-- 5.2.1.1 成本构成的同源加粗句要求：成本口径必须在界面上明示，
-- 否则「同一 SKU 今天显示毛利 30% 明天 25% 无法解释」。
-- 所以分摊单上有两个时刻，且**含义不同、不可互相替代**：
--   * `computed_at`  —— **算的时刻**（这次分摊是什么时候算出来的）
--   * `cost_as_of`   —— **结账时点**（这批货的成本算到哪一刻的头上）
--     例：运费可能是 T+7 才拿到对账单，cost_as_of 就该落在对账单的截止日，
--     而不是"今天算分摊的那天"。
--   * `locked_at`    —— **锁定时刻**。锁定后这张分摊单**不可再改**（触发器钉死）：
--     它是「这批成本已经出账」的凭据，改它等于改历史利润。
--
-- ---------------------------------------------------------------------------
-- 四、金额与数量口径：全程 numeric，禁浮点
-- ---------------------------------------------------------------------------
--   * 金额 numeric(19,4)（与 V140 freight_quote_line、V136 一致）
--   * 数量 numeric(19,6)（与 V80 first_mile_shipment_line 一致）
--   * 比率 numeric(19,6)，且**分母为 0 时是 NULL 而不是 0**：
--     0% 会被读成「这项成本占比为零」，而真相是「分母是零，算不出来」。
--   * 币种 char(3)，**逐币种分组**，跨币种绝不相加（AGENTS.md 与 10.2.1.1 同口径）。
--
-- ===========================================================================

CREATE SEQUENCE logistics_cost_allocation_code_seq START 1;
CREATE SEQUENCE logistics_cost_rule_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 成本分量台账（五个分量各占一位；可得与不可得都是一等事实）
-- ---------------------------------------------------------------------------
-- 为什么要有这张表：蓝图那句「真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，
-- 报表必须全口径」要求界面上**看得见五个分量**。如果分量清单写在 Java 常量里，
-- 那么「这个分量今天有没有数据」就变成了代码的事 —— 上游交付与否要改代码才能反映出来。
-- 做成**表**，才能让「仓储费的归属模块是谁」在界面上如实显示，
-- 也才能让 16 合规 / 12 财务落地后由它们自己打开那一位，而不需要改本模块的代码。
--
-- 一行 = 一个成本分量在本租户的口径。component 是**闭集**（五位齐全），由服务层维护；
-- 业务数据（金额）不在这里 —— 这里只存**口径与可得性**。
CREATE TABLE logistics_cost_component (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 五个分量。闭集，缺一位就是「报表漏了一个分量」，CHECK 钉死。
  component varchar(24) NOT NULL CHECK (component IN (
    'LAST_MILE_FREIGHT',   -- 尾程运费
    'FIRST_MILE_ALLOCATION',-- 头程分摊
    'PACKAGING',            -- 包材
    'DUTY_AND_TAX',         -- 关税与增值税/GST
    'STORAGE_FEE'           -- 仓储费
  )),

  display_name varchar(60) NOT NULL,

  -- 现在能不能从**已交付的上游**真的取到这个分量的金额。
  -- false ⇒ 界面上这一位显示「暂无数据 + 归属模块」，**不是 0**。
  available boolean NOT NULL DEFAULT false,

  -- 🔴 来源**类别**（与 source 的文字说明是两层）：FACT = 事实表算出来的；
  --   PARAMETER = 租户维护的参数算出来的。界面上两者**分色**显示 ——
  --   把参数报成事实，比不报这个分量更危险。
  source_kind varchar(12) NOT NULL DEFAULT 'FACT'
    CHECK (source_kind IN ('FACT','PARAMETER')),

  -- 取数来源（人可读）。available=true 时必填：答不出「这个数从哪来」就不该显示它。
  source varchar(300) NOT NULL DEFAULT '',

  -- 取数口径（人可读）：这个分量的金额到底含什么 / 按什么时点结。
  -- 例：尾程 = trade 的现行运费登记行（人工登记或渠道对账单），按运单签发时点归集。
  basis varchar(300) NOT NULL DEFAULT '',

  -- available=false 时的归属模块与原因（**必填**：不可得也要说清是谁的活）。
  owner_module varchar(64) NOT NULL DEFAULT '',
  unavailable_reason varchar(300) NOT NULL DEFAULT '',

  -- 这个分量对成本口径的影响（不可得时，报表的「合计」少算了什么）。
  impact varchar(300) NOT NULL DEFAULT '',

  -- 覆盖顺序：报表按这个顺序并排展示五个分量。
  display_order integer NOT NULL DEFAULT 0,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,component),
  CHECK(btrim(display_name)<>''),
  -- 可得 ⇒ 必须说清来源与口径；不可得 ⇒ 必须点名归属模块与原因。
  -- 这条 CHECK 是「假装它有真实数据」的物理拦截器。
  CHECK (available = false OR (btrim(source)<>'' AND btrim(basis)<>'')),
  CHECK (available = true OR (btrim(owner_module)<>'' AND btrim(unavailable_reason)<>''))
);

COMMENT ON TABLE logistics_cost_component IS
  '10.6.1.1 成本分量的口径与可得性台账：五个分量各占一位，可得与不可得都是一等事实。'
  '金额不在这里（金额在各自的取数处），这里只回答「这一位今天有没有数、缺了是谁的活」。';

-- ---------------------------------------------------------------------------
-- 2. 成本参数（租户维护的**参数**口径，与事实表口径分开）
-- ---------------------------------------------------------------------------
-- 为什么要有这张表：蓝图那句加粗句要求「真实成本 = 尾程 + 头程分摊 + 包材 + 关税 +
-- 仓储费，报表必须全口径」，而**包材成本在本仓库真的没有事实来源**
-- （warehouse 的 packing_material 只有外形尺寸与自重，没有任何成本列）。
--
-- 两条路：
--   ① 把它标成「不可用」，报表里那一位显示「暂无数据」 —— 诚实，但报表永远补不齐；
--   ② 给它一个**参数**口径：租户维护「每票包材成本」，报表按
--      「参数 × 本窗该币种的运单票数」算出包材金额，**并明确标注来源是「参数」**。
--
-- 选 ②，但**绝不把它伪装成事实**：这张表的行是租户自己维护的口径，不是承运商账单。
-- 所以 `source_kind` 把它标成 PARAMETER，界面上与 FACT 分色显示，
-- 而且**没有维护参数的币种那一位仍然是缺项**（null + missingComponents），不是 0。
--
-- 🔴 为什么参数只允许 PACKAGING：其它四个分量都有事实来源，
--    给它们开参数入口等于制造第二个真相（「这个数到底是算出来的还是填出来的」）。
CREATE TABLE logistics_cost_parameter (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 闭集：目前只有包材走参数口径。
  component varchar(24) NOT NULL CHECK (component IN ('PACKAGING')),

  -- 逐币种：跨币种金额绝不相加，参数也一样。
  currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),

  -- 每票包材成本（参数值）。报表里：包材 = 本参数 × 本窗该币种的运单票数。
  amount_per_waybill numeric(19,4) NOT NULL CHECK (amount_per_waybill >= 0),

  -- 计分口径：PER_WAYBILL = 按票。留成闭集是为了以后加口径时**必须**同步改算法，
  -- 而不是悄悄沿用「按票」却以为按件算了。
  unit varchar(16) NOT NULL DEFAULT 'PER_WAYBILL' CHECK (unit = 'PER_WAYBILL'),

  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一分量同一币种只有一行参数（改就是改，不新增一条制造两个真相）。
  UNIQUE(tenant_id,component,currency),
  CHECK(btrim(note) IS NOT NULL)
);

COMMENT ON TABLE logistics_cost_parameter IS
  '10.6.1.1 成本参数：租户维护的**参数**口径（目前只有包材）。'
  '来源是参数不是事实，界面上必须与 FACT 分色；没维护参数的币种在报表里是缺项而不是 0。';

CREATE INDEX logistics_cost_parameter_list
  ON logistics_cost_parameter(tenant_id,component,currency);

-- ---------------------------------------------------------------------------
-- 3. 头程分摊规则（版本化，不就地覆盖 —— 见文件头第二节）
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_cost_allocation_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则码：一套规则的身份。同一 rule_code 的不同 version 是**同一个规则的历代版本**。
  rule_code varchar(32) NOT NULL,
  rule_name varchar(120) NOT NULL,

  -- 版本号：从 1 递增。**历史版本永不删除、永不原地改口径**。
  version integer NOT NULL CHECK (version >= 1),

  -- 分摊基准：
  --   BY_QUANTITY        按发出数量（PCS）
  --   BY_WEIGHT          按毛重（KG）
  --   BY_DECLARED_VALUE  按申报价值（VALUE）
  --   EQUAL              均摊（每行一份）
  -- 闭集：分摊基准是**口径**的一部分，写成自由文本就等于没有口径。
  allocation_basis varchar(24) NOT NULL CHECK (allocation_basis IN (
    'BY_QUANTITY','BY_WEIGHT','BY_DECLARED_VALUE','EQUAL')),

  -- 状态：DRAFT 草稿 / ACTIVE 现行 / RETIRED 已退役（历史版本仍留档）。
  status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),

  -- 生效区间。effective_to 为空 = 一直有效（现行版本）。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  note text NOT NULL DEFAULT '',

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,rule_code,version),
  CHECK(btrim(rule_code)<>'' AND btrim(rule_name)<>''),
  CHECK(effective_to IS NULL OR effective_to > effective_from)
);

-- 同一 rule_code 同时只能有一个 ACTIVE 版本。
CREATE UNIQUE INDEX logistics_cost_rule_one_active
  ON logistics_cost_allocation_rule(tenant_id,rule_code) WHERE status='ACTIVE';

CREATE INDEX logistics_cost_rule_list
  ON logistics_cost_allocation_rule(tenant_id,rule_code,version DESC);

-- ---------------------------------------------------------------------------
-- 4. 头程分摊单（一次头程发运单 × 一个规则版本）
-- ---------------------------------------------------------------------------
-- 一行 = 「这张头程发运单的这些成本，按这个规则版本，分摊到这些行上」。
--
-- 🔴 source_shipment_id 是 **warehouse 的 first_mile_shipment.id**（逻辑引用）。
--   跨服务**不建 FK**（AGENTS.md 禁止跨服务 FK / 共享视图）：加 FK 会让 warehouse
--   将来 ALTER 那张表时把本迁移炸掉。真实存在性由**服务层在事务里核**
--   （取不到就是 404，不是 500），本表只如实记下 id 与可读快照 shipment_code。
CREATE TABLE logistics_cost_allocation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  allocation_code varchar(32) NOT NULL,

  -- 规则版本快照：既存 id，也存 code / version / basis 三个可读字段。
  -- 只存 rule_id 不够 —— 规则行将来若被归档，报表要还能说清「当时按哪套规则分的」。
  rule_id uuid NOT NULL,
  rule_code varchar(32) NOT NULL,
  rule_version integer NOT NULL CHECK (rule_version >= 1),
  allocation_basis varchar(24) NOT NULL CHECK (allocation_basis IN (
    'BY_QUANTITY','BY_WEIGHT','BY_DECLARED_VALUE','EQUAL')),

  -- 来源：warehouse 头程发运单（逻辑引用 + 可读快照）。
  source_shipment_id uuid NOT NULL,
  source_shipment_code varchar(32) NOT NULL,
  source_transport_mode varchar(16) NOT NULL DEFAULT '',
  source_carrier_name varchar(100) NOT NULL DEFAULT '',
  destination_country_code varchar(2) NOT NULL DEFAULT '',

  -- 币种与金额。**逐币种**：一张分摊单只有一个币种（上游那批发运单的币种）。
  currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  -- 待分摊总额（上游发运单上的成本合计：运费 + 保险 + 关税预估 + 操作费）。
  source_total_amount numeric(19,4) NOT NULL CHECK (source_total_amount >= 0),
  -- 已分摊总额。必须等于 source_total_amount（延迟约束触发器钉死）。
  allocated_total_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK (allocated_total_amount >= 0),
  -- 分摊基准的合计（数量合计 / 重量合计 / 价值合计）。
  basis_total numeric(19,6) NOT NULL DEFAULT 0 CHECK (basis_total >= 0),
  -- 明细行数。
  line_count integer NOT NULL DEFAULT 0 CHECK (line_count >= 0),

  -- COMPUTED 已算 / LOCKED 已结账（锁定后不可改）/ SUPERSEDED 已被新规则版本取代。
  status varchar(12) NOT NULL DEFAULT 'COMPUTED' CHECK (status IN ('COMPUTED','LOCKED','SUPERSEDED')),

  -- 🔴 两个时刻含义不同（见文件头第三节）：算的时刻 vs 结账时点。
  computed_at timestamptz NOT NULL DEFAULT now(),
  cost_as_of timestamptz NOT NULL,

  computed_by uuid NOT NULL,
  locked_at timestamptz,
  locked_by uuid,
  superseded_at timestamptz,
  superseded_by uuid,
  superseded_by_rule_version integer,

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,allocation_code),
  FOREIGN KEY(tenant_id,rule_id) REFERENCES logistics_cost_allocation_rule(tenant_id,id),
  CHECK(btrim(allocation_code)<>''),
  CHECK(btrim(source_shipment_code)<>''),
  -- 结账时点不得晚于算出时刻：cost_as_of 是「这批货的成本算到哪一刻的头上」，
  -- 它可以是**过去**（比如运费 T+7 才对账，结账时点落在开船那天），
  -- 但不能是**将来** —— 把成本记到还没到来的时刻上，那笔账在账期里根本不存在。
  -- 两个时刻都由服务层显式写入（同一组值），所以这条 CHECK 是确定性的。
  CHECK (cost_as_of <= computed_at)
);

-- 同一张头程发运单同时只能有一张「现行」分摊单。
-- 用新规则版本重算 ⇒ 旧单转 SUPERSEDED，而不是被改写（见文件头第二节）。
CREATE UNIQUE INDEX logistics_cost_allocation_one_current
  ON logistics_cost_allocation(tenant_id,source_shipment_id) WHERE status <> 'SUPERSEDED';

CREATE INDEX logistics_cost_allocation_list
  ON logistics_cost_allocation(tenant_id,status,computed_at DESC);

CREATE INDEX logistics_cost_allocation_shipment
  ON logistics_cost_allocation(tenant_id,source_shipment_id);

-- ---------------------------------------------------------------------------
-- 5. 头程分摊明细行（分摊到 SKU 行上）
-- ---------------------------------------------------------------------------
-- 一行 = 一个 SKU（一条头程发运明细行）分到了多少钱。
-- 这是蓝图输出里的「分摊明细」本身 —— 不是派生视图，是留档的事实行。
CREATE TABLE logistics_cost_allocation_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  allocation_id uuid NOT NULL,
  line_no integer NOT NULL CHECK (line_no >= 1),

  -- 分摊目标：SKU（头程发运单的一行 = 一个 SKU × 一个批次）。
  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  hs_code varchar(32) NOT NULL DEFAULT '',

  -- 本行分摊基准的取值（按数量 ⇒ 件数；按重量 ⇒ kg；按价值 ⇒ 金额）。
  basis_value numeric(19,6) NOT NULL DEFAULT 0 CHECK (basis_value >= 0),
  -- 基准单位：PCS / KG / VALUE / EQUAL。写清楚单位才知道这一列能不能横向比。
  basis_unit varchar(16) NOT NULL CHECK (basis_unit IN ('PCS','KG','VALUE','EQUAL')),

  -- 占基准合计的比例。**basis_total = 0 时必须是 NULL**（不是 0）：
  -- 0% 会被读成「这个 SKU 占了 0%」，真相是「分母是零，算不出来」。
  ratio numeric(19,6) CHECK (ratio IS NULL OR (ratio >= 0 AND ratio <= 1)),

  -- 分摊到的金额。
  allocated_amount numeric(19,4) NOT NULL CHECK (allocated_amount >= 0),
  currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),

  allocated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,allocation_id,line_no),
  FOREIGN KEY(tenant_id,allocation_id) REFERENCES logistics_cost_allocation(tenant_id,id)
);

CREATE INDEX logistics_cost_allocation_line_list
  ON logistics_cost_allocation_line(tenant_id,allocation_id,line_no);

CREATE INDEX logistics_cost_allocation_line_sku
  ON logistics_cost_allocation_line(tenant_id,sku_id);

-- ---------------------------------------------------------------------------
-- 6. 护栏触发器
-- ---------------------------------------------------------------------------
-- 6.1 规则版本 append-only：被分摊引用过的版本不许改口径。
--     「分摊规则变更会导致历史利润变化，因此调整必须留历史版本，不能就地覆盖」
--     —— 这条是蓝图 5.2.1.3 的加粗句，落到 DB 层就是这条触发器。
CREATE FUNCTION guard_cost_rule_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE used_count integer;
BEGIN
  SELECT count(*) INTO used_count FROM logistics_cost_allocation a
   WHERE a.tenant_id = OLD.tenant_id AND a.rule_id = OLD.id;
  IF used_count > 0 THEN
    IF NEW.allocation_basis <> OLD.allocation_basis
       OR NEW.rule_code <> OLD.rule_code
       OR NEW.rule_name <> OLD.rule_name
       OR NEW.version <> OLD.version THEN
      RAISE EXCEPTION '规则 % 版本 % 已被 % 张分摊单引用，不允许改口径：改了就等于让已出账的历史成本失去依据（蓝图 5.2.1.3：调整必须留历史版本，不能就地覆盖）。要改请新建版本',
       OLD.rule_code, OLD.version, used_count USING ERRCODE='23514';
    END IF;
  END IF;
  -- 生效区间不得倒挂。
  IF NEW.effective_to IS NOT NULL AND NEW.effective_to <= NEW.effective_from THEN
    RAISE EXCEPTION '规则的失效时刻必须晚于生效时刻' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cost_rule_immutable
 BEFORE UPDATE ON logistics_cost_allocation_rule
 FOR EACH ROW EXECUTE FUNCTION guard_cost_rule_immutable();

-- 6.2 规则一旦被引用就不可删除（删掉等于让历史分摊单失去依据）。
CREATE FUNCTION guard_cost_rule_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE used_count integer;
BEGIN
  SELECT count(*) INTO used_count FROM logistics_cost_allocation a
   WHERE a.tenant_id = OLD.tenant_id AND a.rule_id = OLD.id;
  IF used_count > 0 THEN
    RAISE EXCEPTION '规则 % 版本 % 已被 % 张分摊单引用，不允许删除：历史成本必须留得住',
     OLD.rule_code, OLD.version, used_count USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER cost_rule_no_delete
 BEFORE DELETE ON logistics_cost_allocation_rule
 FOR EACH ROW EXECUTE FUNCTION guard_cost_rule_no_delete();

-- 6.3 状态机：COMPUTED → LOCKED → SUPERSEDED，单向。
--     LOCKED 是终态（出账凭据）；SUPERSEDED 只允许从 COMPUTED 或 LOCKED 到。
CREATE FUNCTION guard_cost_allocation_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF OLD.status = 'LOCKED' AND NEW.status <> 'LOCKED' AND NEW.status <> 'SUPERSEDED' THEN
      RAISE EXCEPTION '分摊单 % 已结账，不允许改回「%」：出账凭据不可改写', OLD.allocation_code, NEW.status
       USING ERRCODE='23514';
    END IF;
    IF OLD.status = 'SUPERSEDED' AND NEW.status <> 'SUPERSEDED' THEN
      RAISE EXCEPTION '分摊单 % 已被新规则版本取代，是历史留档，不允许复活', OLD.allocation_code
       USING ERRCODE='23514';
    END IF;
    -- 锁定的时刻与锁定人必须一起写：没有 locked_by 的「已结账」行等于没结过账。
    IF NEW.status = 'LOCKED' AND (NEW.locked_at IS NULL OR NEW.locked_by IS NULL) THEN
      RAISE EXCEPTION '结账必须写明锁定时刻与锁定人' USING ERRCODE='23514';
    END IF;
    IF NEW.status <> 'LOCKED' AND (NEW.locked_at IS NOT NULL OR NEW.locked_by IS NOT NULL) THEN
      RAISE EXCEPTION '状态「%」不是已结账，不允许带锁定痕迹', NEW.status USING ERRCODE='23514';
    END IF;
    IF NEW.status = 'SUPERSEDED' AND NEW.superseded_at IS NULL THEN
      RAISE EXCEPTION '被取代必须写明取代时刻' USING ERRCODE='23514';
    END IF;
    IF NEW.status <> 'SUPERSEDED' AND NEW.superseded_at IS NOT NULL THEN
      RAISE EXCEPTION '状态「%」不是已被取代，不允许带取代痕迹', NEW.status USING ERRCODE='23514';
    END IF;
  END IF;
  -- 结账后金额与行数都不可再动：出账凭据改金额 = 改历史利润。
  IF TG_OP = 'UPDATE' AND OLD.status = 'LOCKED' THEN
    IF NEW.allocated_total_amount <> OLD.allocated_total_amount
       OR NEW.source_total_amount <> OLD.source_total_amount
       OR NEW.basis_total <> OLD.basis_total
       OR NEW.line_count <> OLD.line_count
       OR NEW.allocation_basis <> OLD.allocation_basis
       OR NEW.rule_id <> OLD.rule_id
       OR NEW.cost_as_of <> OLD.cost_as_of THEN
      RAISE EXCEPTION '分摊单 % 已结账，金额 / 基准 / 规则 / 结账时点都不可再改', OLD.allocation_code
       USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cost_allocation_state
 BEFORE UPDATE ON logistics_cost_allocation
 FOR EACH ROW EXECUTE FUNCTION guard_cost_allocation_state();

-- 6.4 分摊行的金额合计必须等于分摊单的已分摊总额，且等于来源总额。
--     延迟约束触发器：服务层是「先写头再写行」，即时触发器会对着半成品报错。
CREATE FUNCTION guard_cost_allocation_sum() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; line_sum numeric(19,4); line_total integer;
BEGIN
  SELECT allocated_total_amount, source_total_amount, currency, line_count, status
    INTO head
    FROM logistics_cost_allocation
   WHERE tenant_id = NEW.tenant_id AND id = NEW.allocation_id;
  IF head IS NULL THEN
    RAISE EXCEPTION '分摊明细行指向的分摊单 % 不存在', NEW.allocation_id USING ERRCODE='23503';
  END IF;
  SELECT coalesce(sum(allocated_amount),0), count(*) INTO line_sum, line_total
    FROM logistics_cost_allocation_line l
   WHERE l.tenant_id = NEW.tenant_id AND l.allocation_id = NEW.allocation_id;
  -- 已结账或已被取代的分摊单不允许再加行：出账凭据不可增删。
  IF head.status IN ('LOCKED','SUPERSEDED') THEN
    RAISE EXCEPTION '分摊单处于「%」状态，不允许改明细行', head.status USING ERRCODE='23514';
  END IF;
  IF line_sum <> head.allocated_total_amount THEN
    RAISE EXCEPTION '分摊明细合计 % 与分摊单的已分摊总额 % 不一致', line_sum, head.allocated_total_amount
     USING ERRCODE='23514';
  END IF;
  IF line_total <> head.line_count THEN
    RAISE EXCEPTION '分摊明细行数 % 与分摊单记录的行数 % 不一致', line_total, head.line_count
     USING ERRCODE='23514';
  END IF;
  -- 已分摊总额必须等于来源总额：分摊是**拆分**不是**缩水**。
  -- 少分出去的那一块等于凭空少算了这批货的成本 —— 而这正是蓝图那句
  -- 「物流成本常被低估」最常见的发生方式。
  IF head.allocated_total_amount <> head.source_total_amount THEN
    RAISE EXCEPTION '已分摊总额 % 与来源总额 % 不一致：分摊是拆分不是缩水，少分的部分等于凭空少算成本',
     head.allocated_total_amount, head.source_total_amount USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER cost_allocation_sum
 AFTER INSERT OR UPDATE OR DELETE ON logistics_cost_allocation_line
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_cost_allocation_sum();

-- 6.5 分摊行的币种必须与分摊单一致；基准为 0 时 ratio 必须是 NULL。
CREATE FUNCTION guard_cost_allocation_line() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record;
BEGIN
  SELECT currency, basis_total, status INTO head
    FROM logistics_cost_allocation
   WHERE tenant_id = NEW.tenant_id AND id = NEW.allocation_id;
  IF head IS NULL THEN
    RAISE EXCEPTION '分摊明细行指向的分摊单 % 不存在', NEW.allocation_id USING ERRCODE='23503';
  END IF;
  IF NEW.currency <> head.currency THEN
    RAISE EXCEPTION '分摊明细的币种 % 与分摊单的币种 % 不一致：跨币种金额绝不相加',
     NEW.currency, head.currency USING ERRCODE='23514';
  END IF;
  -- 分母为 0 ⇒ ratio 必须是 NULL（算不出来），不是 0（读成「占了 0%」）。
  IF head.basis_total = 0 AND NEW.ratio IS NOT NULL THEN
    RAISE EXCEPTION '分摊基准合计为 0，占比必须是 NULL 而不是 0：0 会被读成「这个 SKU 占了 0%%」，真相是「分母是零，算不出来」'
     USING ERRCODE='23514';
  END IF;
  IF head.basis_total > 0 AND NEW.ratio IS NULL THEN
    RAISE EXCEPTION '分摊基准合计大于 0，占比不能为空' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cost_allocation_line
 BEFORE INSERT OR UPDATE ON logistics_cost_allocation_line
 FOR EACH ROW EXECUTE FUNCTION guard_cost_allocation_line();

-- 6.6 分摊单与成本分量的口径对账：分摊单引用的规则版本必须存在且口径一致
--     （同服务复合 FK 已在表定义里保证 rule_id 存在；这里再钉住 basis 一致）。
--
-- ⚠️ 局部变量一律带 `v_` 前缀：PL/pgSQL 里变量名与列名同名会报
--    「column reference "rule_code" is ambiguous」（它可能指变量也可能指列），
--    触发器会在**每一次**写分摊单时炸 —— 症状是「插入时报一个与业务毫无关系的
--    歧义错误」，排查成本极高。
CREATE FUNCTION guard_cost_allocation_rule_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_basis varchar(24); v_ver integer; v_code varchar(32);
BEGIN
  SELECT allocation_basis, version, rule_code INTO v_basis, v_ver, v_code
    FROM logistics_cost_allocation_rule
   WHERE tenant_id = NEW.tenant_id AND id = NEW.rule_id;
  IF v_basis IS NULL THEN
    RAISE EXCEPTION '分摊单引用的规则 % 不存在或不属于本租户', NEW.rule_id USING ERRCODE='23503';
  END IF;
  IF v_basis <> NEW.allocation_basis
     OR v_ver <> NEW.rule_version
     OR v_code <> NEW.rule_code THEN
    RAISE EXCEPTION '分摊单上的规则快照（% v%s / %）与规则行（% v%s / %）不一致：快照必须与它指向的版本逐字相同，否则报表说清不了「当时按哪套规则分的」',
     NEW.rule_code, NEW.rule_version, NEW.allocation_basis,
     v_code, v_ver, v_basis USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cost_allocation_rule_snapshot
 BEFORE INSERT OR UPDATE ON logistics_cost_allocation
 FOR EACH ROW EXECUTE FUNCTION guard_cost_allocation_rule_snapshot();

-- ---------------------------------------------------------------------------
-- 7. 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 / V140 / V141 / V143 同一套写法。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_cost_component',
    'logistics_cost_parameter',
    'logistics_cost_allocation_rule',
    'logistics_cost_allocation',
    'logistics_cost_allocation_line' ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 8. 账号与授权（沿用 V1/V93/V129/V134/V136/V140/V141/V143 的条件建角色，可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  logistics_cost_component,
  logistics_cost_parameter,
  logistics_cost_allocation_rule,
  logistics_cost_allocation,
  logistics_cost_allocation_line TO channel_app;

-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建单会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE logistics_cost_allocation_code_seq TO channel_app;
GRANT USAGE, SELECT ON SEQUENCE logistics_cost_rule_code_seq TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V143 同口径）。
REVOKE ALL ON
  logistics_cost_component,
  logistics_cost_parameter,
  logistics_cost_allocation_rule,
  logistics_cost_allocation,
  logistics_cost_allocation_line FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 9. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：成本分析属于「平台/承运商操作」读域，与 10.1.1.1 承运商、
-- 10.1.1.2 渠道配置、10.1.2.2 渠道比价、10.2.2.2 面单模板同族，
-- 因此 identity 服务零改动，跨车道回归面 = 0。
