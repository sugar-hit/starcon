-- ===========================================================================
-- 10.1.2.1 运费计算器（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.1.2.1 运费计算器
--   - 职责：输入收货国家、重量、体积、件数、申报价值、是否带电/液体等，输出各可用渠道的
--           运费与预计时效，供人工决策或规则匹配使用。
--   - 输入：包裹参数；渠道规则集；可能的附加费。
--   - 输出：渠道 × (运费、时效、可承运性) 对照表。
--   - 关系：上游 = 10.1.1.2；下游 = 9.3.2.1 分仓、5.2.3.2 费用测算。
--           **试算用规则、结算用实际**，两者差异进费用差异项 —— 不能拿试算当实际成本。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到上游 10.1.1.2，不跨服务）
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 函数**，零 ALTER 任何既有表：
--   * 唯一被引用的是 V136 的 `logistics_channel_config`，且是**同库复合外键**
--     （带 tenant_id），不是跨服务 FK —— 渠道配置本来就是本服务的主权表
--     （10.1.1.2 已交付，在集成头里 grep 得到）
--   * **本迁移不读 10.1.1.3 时效与限制的任何表**：该模块在并行车道上开发，
--     引用一张还不存在的表会让本车道的 Flyway 直接炸掉。分国家时效落在
--     `freight_channel_country`（本模块自有的**试算口径**），两表语义不同：
--     10.1.1.3 描述渠道的**承诺时效**，本表描述**试算时按哪一段区间报时效**。
--   * 不碰 trade / warehouse 的任何一张表（下游 10.2.1.2 运单申请在 trade）
--   * 复用 identity 已注册的 channel.operation.* 权限码，identity 零改动
--
-- 表名一律走 `freight_` 命名空间（与 V136 的 `logistics_channel_*` 并列而不混用），
-- 刻意**不**复用 `logistics_channel_*` 前缀 —— 并行车道正在同一服务里加自己的表，
-- 撞表名会让并入后的 Flyway 在生产上炸掉。
--
-- ---------------------------------------------------------------------------
-- 一、为什么计分档位要自己建表（而不是读 V136 的 base_rate 就完事）
-- ---------------------------------------------------------------------------
-- V136 给的是**渠道级单值**（base_rate + charge_divisor + min_charge）。真实跨境报价
-- 几乎不可能只有一个单价：
--   * 「首重 1kg 12 元 + 续重每 0.5kg 3 元」—— 这是首重/续重模型，不是线性单价
--   * 「0-0.5kg 18.5 / 0.5-1kg 21 / 1-5kg 26 / 5kg 以上 34」—— 这是计分档位模型
--     两种模型的**边界语义不同**：档位模型问「落在哪一段」（边界闭开真的会改变
-- 归属与重叠），首重续重模型问「进几位」（续重一律进位，0.5 件不存在）。
-- 所以 `charge_model` 显式二选一，**不合并成一个万能公式** —— 合并出来的公式
-- 在界面上看不出差别，在钱上系统性偏差。
--
-- 规则必须来自 DB 而不是代码常量：换一家承运商的费率是**改数据**（新的费率卡），
-- 不是改代码发版。所以档位、单价、进位单位、舍入位数与舍入模式全在表里。
--
-- ---------------------------------------------------------------------------
-- 二、金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 金额（各段单价 / 首重费 / 续重费 / 附加费 / 保费 / 各级运费）一律 **NUMERIC(19,4)**，
-- 数量（重量 / 体积 / 体积重折算基数）一律 **NUMERIC(19,6)**，
-- 百分比（燃油 / 保费费率）一律 **NUMERIC(9,4)**。**没有一列是 float/double** ——
-- AGENTS.md「禁止浮点记账」在这里是硬约束：附加费与保费都是钱。
--
-- ---------------------------------------------------------------------------
-- 三、档位边界的闭开是**数据**（to_qty_inclusive），不是代码里的一个 if
-- ---------------------------------------------------------------------------
-- 同一个「1kg 是 0-1 段还是 1-5 段」的问题，不同承运商答案不同，而且**两个都真实存在**。
-- 所以 `to_qty_inclusive` 是一列，DB 层用它做**重叠校验**（见 trigger b）：
--   [0,1] 闭 + [1,5] 开   ⇒ 1.0 同时落在两段 ⇒ 重叠 ⇒ 拒
--   [0,1) 开 + [1,5] 闭   ⇒ 1.0 只落在第二段 ⇒ 不重叠 ⇒ 放行
-- 闭开对**金额**没有影响（分段求和的总量一样），它影响的是**归属**与**重叠校验**：
-- 归属进 `calc_trace`（界面上要能看出 1.0kg 落在哪一段），重叠会直接把两段
-- 都算一遍钱 —— 那才是真正的钱事故。
--
-- ---------------------------------------------------------------------------
-- 四、试算 ≠ 实际成本：这条约束的**物理**落点（见第 2 节 freight_quote）
-- ---------------------------------------------------------------------------
-- `freight_quote.cost_basis` 是一列 CHECK，且**只允许一个取值**：
--   cost_basis text NOT NULL DEFAULT 'RULE_ESTIMATE'
--     CHECK (cost_basis = 'RULE_ESTIMATE')
-- 一个 CHECK 只容一个值 ⇒ 这一列**在物理上不可能**存成 ACTUAL_SETTLEMENT。
-- 蓝图加粗句是「试算用规则、结算用实际，两者差异进费用差异项 —— 不能拿试算当
-- 实际成本」：最省事的假活是把试算结果当成实收成本直接入账，而那笔差异会静默
-- 吞掉利润。把「这是估算」做成一条写不进 ACTUAL 的 DB 约束，比在文档里写一句话管用。
--
-- ---------------------------------------------------------------------------
-- 一、5 张表
-- ---------------------------------------------------------------------------
--   freight_rate_card         费率卡（档位 / 首重续重 / 舍入口径的容器）
--   freight_rate_band         计分档位（FLAT_RATE 才有）
--   freight_channel_country   分国家：偏远标记 + 试算时效区间
--   freight_quote             试算单头（包裹参数回显 + 汇总）
--   freight_quote_line        试算单行（渠道 × 运费 / 时效 / 可承运性）

CREATE TABLE freight_rate_card (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- === 上游 10.1.1.2：所属渠道（同库复合 FK，带 tenant_id ⇒ 跨租户挂载不可能）===
  channel_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE,

  -- DRAFT 草稿（可编辑，不参与试算）· ACTIVE 生效 · RETIRED 停用（保留历史试算口径）
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),

  -- FLAT_RATE 计分档位 · FIRST_PLUS_ADDITIONAL 首重 + 续重（见文件头第一节）
  charge_model text NOT NULL
    CHECK (charge_model IN ('FLAT_RATE','FIRST_PLUS_ADDITIONAL')),

  -- 档位卡在哪个量纲上分段：重量(kg) / 体积(折 kg) / 件数
  basis text NOT NULL
    CHECK (basis IN ('WEIGHT','VOLUME','PIECE')),

  -- === 舍入口径：钱怎么舍入是**规则**不是实现细节 ===
  -- 承运商对账到分，不同承运商的半分处理不同；把它做成列，换承运商不用发版。
  rounding_mode text NOT NULL DEFAULT 'HALF_UP'
    CHECK (rounding_mode IN ('HALF_UP','HALF_EVEN','HALF_DOWN','CEILING','FLOOR')),
  rounding_scale smallint NOT NULL DEFAULT 2
    CHECK (rounding_scale BETWEEN 0 AND 6),

  -- 体积重折算基数：计费重量 = max(实重, 长×宽×高(cm³) ÷ 本值)。
  -- 只在 basis='WEIGHT' 时可用 —— 按体积计费的渠道走 V136 的 charge_divisor，
  -- 两个除数同时存在会让「除以哪个」变成调用方的事。
  volumetric_divisor numeric(19,6)
    CHECK (volumetric_divisor IS NULL OR volumetric_divisor > 0),
  CHECK (volumetric_divisor IS NULL OR basis = 'WEIGHT'),

  -- === 首重 + 续重（仅 FIRST_PLUS_ADDITIONAL，见文件头第一节）===
  first_qty numeric(19,6),
  first_amount numeric(19,4),
  additional_qty numeric(19,6),
  additional_amount numeric(19,4),
  -- 四列要么齐备要么全空（半配置 = 永远算不出钱）
  CHECK ((first_qty IS NULL) = (first_amount IS NULL)
         AND (additional_qty IS NULL) = (additional_amount IS NULL)),
  CHECK (first_qty IS NULL OR (first_qty > 0 AND first_amount >= 0)),
  CHECK (additional_qty IS NULL OR (additional_qty > 0 AND additional_amount >= 0)),
  CHECK (charge_model <> 'FIRST_PLUS_ADDITIONAL'
         OR (first_qty IS NOT NULL AND additional_qty IS NOT NULL)),
  -- 分段模型不该带首重续重参数（有的话就是两套规则打架）
  CHECK (charge_model <> 'FLAT_RATE' OR first_qty IS NULL),

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  -- 供两张子表的复合外键使用（同库引用，不是跨服务 FK）
  UNIQUE (tenant_id, id)
);

-- 🔴 部分唯一索引：只约束「生效中」的那一张 —— 两张都生效时「这一票按哪张算」
-- 无法回答，而试算单里存下来的口径就再也复算不出来。
-- 刻意**不是** UNIQUE(tenant_id, channel_id, status)：那样会把并存多张 DRAFT
-- 也一起禁掉，而「先存两张草稿对比再启用一张」是正常的运营流程。
CREATE UNIQUE INDEX freight_rate_card_one_active
  ON freight_rate_card(tenant_id, channel_id) WHERE status = 'ACTIVE';

-- 「哪些渠道配了生效费率卡」/ 试算时按渠道取卡
CREATE INDEX freight_rate_card_by_channel
  ON freight_rate_card(tenant_id, channel_id, status);

-- ---------------------------------------------------------------------------
-- freight_rate_band · 计分档位（仅 FLAT_RATE）
-- ---------------------------------------------------------------------------
-- 段区间语义（文件头第三节）：
--   下界 from_qty **恒为闭**（闭开混着写会让「1.0 属于哪一段」在代码里变成 if）
--   上界 to_qty 由 to_qty_inclusive 决定闭/开，NULL = 无上限（最后一档）
CREATE TABLE freight_rate_band (
  tenant_id uuid NOT NULL,
  card_id uuid NOT NULL,
  band_no smallint NOT NULL CHECK (band_no > 0),
  from_qty numeric(19,6) NOT NULL CHECK (from_qty >= 0),
  to_qty numeric(19,6),
  to_qty_inclusive boolean NOT NULL DEFAULT false,
  -- 本段内每单位（kg / 折 kg / 件）的单价
  rate_amount numeric(19,4) NOT NULL CHECK (rate_amount >= 0),
  note text NOT NULL DEFAULT '',
  PRIMARY KEY (tenant_id, card_id, band_no),
  FOREIGN KEY (tenant_id, card_id)
    REFERENCES freight_rate_card(tenant_id, id) ON DELETE CASCADE,
  CHECK (to_qty IS NULL OR to_qty > from_qty),
  UNIQUE (tenant_id, card_id, from_qty)
);

-- 试算时按卡取全部档位（一次性取回，避免逐段查）
CREATE INDEX freight_rate_band_by_card
  ON freight_rate_band(tenant_id, card_id, band_no);

-- ---------------------------------------------------------------------------
-- freight_channel_country · 分国家：偏远标记 + 试算时效区间
-- ---------------------------------------------------------------------------
-- 为什么不是三列（is_remote / min_days / max_days 塞进 V136）：渠道的**适用范围**
-- 已经在 V136 的 logistics_channel_scope（COUNTRY）里了，再存一份适用范围就是两个
-- 真值。本表只加**试算额外需要**的两样东西，且 PK 与 scope 对齐：
--   remote_area      —— 这个国家对这个渠道算不算偏远（决定 REMOTE_AREA 附加费）
--   transit_min/max_days —— 试算时报的时效区间（天）
-- 时效区间**不给默认值**：没登记就是「未登记」，服务层返回空值并说明原因，
-- 不拿一个编出来的天数冒充估算（那是本仓库一贯拒绝的假活）。
CREATE TABLE freight_channel_country (
  tenant_id uuid NOT NULL,
  channel_id uuid NOT NULL,
  country_code varchar(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
  remote_area boolean NOT NULL DEFAULT false,
  transit_min_days integer CHECK (transit_min_days IS NULL OR transit_min_days >= 0),
  transit_max_days integer,
  CHECK ((transit_min_days IS NULL) = (transit_max_days IS NULL)),
  CHECK (transit_max_days IS NULL OR transit_max_days >= transit_min_days),
  note text NOT NULL DEFAULT '',
  PRIMARY KEY (tenant_id, channel_id, country_code),
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE
);

-- 「寄到 DE 的渠道都按时效怎么排」/ 试算时按目的国一次性取回全部渠道的规则
CREATE INDEX freight_channel_country_by_country
  ON freight_channel_country(tenant_id, country_code);

-- ---------------------------------------------------------------------------
-- freight_quote · 试算单头
-- ---------------------------------------------------------------------------
-- 🔴 cost_basis 只允许 'RULE_ESTIMATE'（文件头第四节）—— 试算结果在物理上不可能
-- 被标成实际结算成本。
CREATE TABLE freight_quote (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 🔴 估算口径：DB 层唯一可写取值（见文件头第四节）
  cost_basis text NOT NULL DEFAULT 'RULE_ESTIMATE'
    CHECK (cost_basis = 'RULE_ESTIMATE'),

  -- === 包裹参数回显（为什么必须回显：试算单要能被人复核，也要能被下游
  --    9.3.2.1 分仓 / 5.2.3.2 费用测算引用 —— 只存一个结果数字，复核不了）===
  dest_country varchar(2) NOT NULL CHECK (dest_country ~ '^[A-Z]{2}$'),
  shop_code varchar(64) NOT NULL DEFAULT '',
  weight_kg numeric(19,6) NOT NULL CHECK (weight_kg >= 0),
  length_cm numeric(19,6),
  width_cm numeric(19,6),
  height_cm numeric(19,6),
  -- 尺寸要么三个都给（体积重 / 按体积计费要用），要么都不给。
  -- PostgreSQL 没有 boolean + boolean，所以用 CASE 数「给了几个」。
  CHECK ((CASE WHEN length_cm IS NULL THEN 0 ELSE 1 END
        + CASE WHEN width_cm IS NULL THEN 0 ELSE 1 END
        + CASE WHEN height_cm IS NULL THEN 0 ELSE 1 END) IN (0, 3)),
  CHECK (length_cm IS NULL OR (length_cm > 0 AND width_cm > 0 AND height_cm > 0)),
  pieces integer NOT NULL CHECK (pieces > 0),
  declared_value numeric(19,4) CHECK (declared_value IS NULL OR declared_value >= 0),
  declared_currency varchar(3) CHECK (declared_currency IS NULL OR declared_currency ~ '^[A-Z]{3}$'),
  contains_battery boolean NOT NULL DEFAULT false,
  contains_liquid boolean NOT NULL DEFAULT false,
  contains_magnet boolean NOT NULL DEFAULT false,
  -- 品类编码清单。刻意存**逗号串而不是 text[]**：jOOQ 的 SQL 解析器吃不下 PG 的
  -- 数组语法（`?::text[]` 与 '{...}' 字面量都试过，都会报 bad SQL grammar），
  -- 而这一列只是「当时发了哪些品类」的回显，没有任何查询按它过滤/连接 ——
  -- 为一个不参与检索的回显列去迁就解析器不值得。形态由下面的 CHECK 钉死。
  category_codes text NOT NULL DEFAULT '',

  -- 🔴 求值时刻：旺季附加费按它判定，且**写进库里**。不存这个时刻，事后没人能
  -- 复算「当时到底加没加旺季费」—— 那正是费用差异项要追问的第一个问题。
  quoted_at timestamptz NOT NULL,

  -- 主币种：对照表里最便宜那条的币种。多币种渠道混排时 lowest/highest 只在
  -- 主币种内统计（不同币种的金额直接比较是本模块最容易出的错之一）。
  -- 空串 = 本次没有任何一条渠道算得出运费（currency 写死一个币种就是编造）。
  currency varchar(3) NOT NULL
    CHECK (currency = '' OR currency ~ '^[A-Z]{3}$'),
  mixed_currency boolean NOT NULL DEFAULT false,
  channel_count integer NOT NULL DEFAULT 0 CHECK (channel_count >= 0),
  pass_count integer NOT NULL DEFAULT 0 CHECK (pass_count >= 0),
  review_count integer NOT NULL DEFAULT 0 CHECK (review_count >= 0),
  block_count integer NOT NULL DEFAULT 0 CHECK (block_count >= 0),
  cheapest_total numeric(19,4),
  dearest_total numeric(19,4),
  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  CHECK (cheapest_total IS NULL OR dearest_total IS NULL
         OR cheapest_total <= dearest_total),
  CHECK ((cheapest_total IS NULL) = (dearest_total IS NULL)),
  -- 品类编码清单：空串（没发品类）或 [A-Z0-9_]{1,64} 的逗号分隔串
  CHECK (category_codes = ''
         OR category_codes ~ '^[A-Z0-9_]{1,64}(,[A-Z0-9_]{1,64})*$')
);

-- 试算单列表：按求值时刻倒序（试算是「刚刚发生的事」，不是历史档案）
CREATE INDEX freight_quote_by_time
  ON freight_quote(tenant_id, quoted_at DESC);

-- 「寄到德国的历史试算」/ 列表按目的国筛选
CREATE INDEX freight_quote_by_country
  ON freight_quote(tenant_id, dest_country, quoted_at DESC);

-- ---------------------------------------------------------------------------
-- freight_quote_line · 试算单行（渠道 × 运费 / 时效 / 可承运性）
-- ---------------------------------------------------------------------------
-- 蓝图输出的「渠道 × (运费、时效、可承运性) 对照表」落在这里。
-- 每一档费用**各自成列**而不是塞一个 JSON：燃油 / 偏远 / 旺季 / 保费要能分别
-- 对账 —— 只给一个总价就看不出「是基础运费涨了还是旺季费加了一笔」。
CREATE TABLE freight_quote_line (
  tenant_id uuid NOT NULL,
  quote_id uuid NOT NULL,
  channel_id uuid NOT NULL,

  -- 渠道身份快照：试算单要能独立读懂（承运商改名后旧单据仍要说得清是哪条渠道）
  channel_code varchar(64) NOT NULL,
  channel_name varchar(200) NOT NULL,
  carrier_name varchar(200) NOT NULL,

  -- 可承运性：PASS 可承运 · REVIEW 需人工确认 · BLOCK 硬拦截（承运商会拒收）
  -- 由 10.1.1.2 的服务端判定端点给出（规则只有一份），本模块**不重写**它。
  eligibility text NOT NULL CHECK (eligibility IN ('PASS','REVIEW','BLOCK')),
  eligibility_reason text NOT NULL DEFAULT '',

  -- 计费口径来源：RATE_CARD 用了本模块的费率卡 · CHANNEL_FLAT 回落到渠道单值
  -- · NO_RULE 渠道没有可用规则（这时**不给钱**，而不是给 0 —— 见下方 NULL 规则）
  rate_source text NOT NULL
    CHECK (rate_source IN ('RATE_CARD','CHANNEL_FLAT','NO_RULE')),
  basis text NOT NULL CHECK (basis IN ('WEIGHT','VOLUME','PIECE')),
  chargeable_qty numeric(19,6),
  -- 体积重（kg）：与实重并列存，才能看出「这一票按体积重算的」
  volumetric_qty numeric(19,6),

  base_charge numeric(19,4) CHECK (base_charge IS NULL OR base_charge >= 0),
  fuel_fee numeric(19,4) CHECK (fuel_fee IS NULL OR fuel_fee >= 0),
  remote_fee numeric(19,4) CHECK (remote_fee IS NULL OR remote_fee >= 0),
  peak_fee numeric(19,4) CHECK (peak_fee IS NULL OR peak_fee >= 0),
  insurance_fee numeric(19,4) CHECK (insurance_fee IS NULL OR insurance_fee >= 0),
  -- 🔴 合计 = 各档之和，**在 DB 层用 CHECK 钉死**：界面上分项与合计必须对得上，
  -- 「分项加起来不等于合计」是这类页面最常见的对账纠纷。
  total_charge numeric(19,4) CHECK (total_charge IS NULL OR total_charge >= 0),
  -- 🔴 算不出规则时一律**留空**，不给 0：一条 0 元运费会被当成「这家最便宜」
  -- 选进分仓（9.3.2.1）与费用测算（5.2.3.2），而真相是「这条渠道没人配规则」。
  CHECK ((rate_source = 'NO_RULE') = (total_charge IS NULL)),
  CHECK (total_charge IS NULL
         OR total_charge = base_charge + fuel_fee + remote_fee + peak_fee + insurance_fee),
  currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),

  transit_min_days integer,
  transit_max_days integer,
  -- 计算轨迹：命中了哪几档、为什么按这个量、进位与舍入怎么做的。
  -- 不可为空字符串之外的无解释状态 —— 试算被质疑时要能复算。
  calc_trace text NOT NULL DEFAULT '',

  PRIMARY KEY (tenant_id, quote_id, channel_id),
  FOREIGN KEY (tenant_id, quote_id)
    REFERENCES freight_quote(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE RESTRICT
);

-- 单据取行 / 按渠道看命中过哪些试算
CREATE INDEX freight_quote_line_by_channel
  ON freight_quote_line(tenant_id, channel_id);

-- ---------------------------------------------------------------------------
-- 触发器：把「算得出钱」与「算不错钱」的边界钉在写入路径上
-- ---------------------------------------------------------------------------

-- (a) 🔴 费率卡的量纲必须与渠道的计费方式一致。
--     渠道按体积计费（BillingMode=BY_VOLUME）却配一张按重分段的卡 ⇒ 试算时
--     「拿哪段价」没有答案，而它表现得像算出了一个合理的数字。
CREATE OR REPLACE FUNCTION guard_freight_rate_card_basis() RETURNS trigger AS $$
DECLARE
  channel_mode text;
  expected text;
BEGIN
  SELECT billing_mode INTO channel_mode
    FROM logistics_channel_config
   WHERE tenant_id = NEW.tenant_id AND id = NEW.channel_id;

  IF channel_mode IS NULL THEN
    RAISE EXCEPTION '渠道配置不存在（tenant=% channel=%）', NEW.tenant_id, NEW.channel_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  expected := CASE channel_mode
    WHEN 'BY_WEIGHT' THEN 'WEIGHT'
    WHEN 'BY_VOLUME' THEN 'VOLUME'
    WHEN 'BY_PIECE' THEN 'PIECE'
  END;

  IF NEW.basis <> expected THEN
    RAISE EXCEPTION
      '费率卡量纲 % 与渠道计费方式 %（%）不一致 —— 按一个量纲分段却拿另一个量纲的量去取价，试算会算出一个看起来合理的错数字',
      NEW.basis, channel_mode, expected USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_freight_rate_card_basis_trg
  BEFORE INSERT OR UPDATE ON freight_rate_card
  FOR EACH ROW EXECUTE FUNCTION guard_freight_rate_card_basis();

-- (b) 🔴🔴 档位不得重叠（闭开语义参与判定，见文件头第三节）。
--     两段重叠 = 同一个重量被算两遍钱，这是最典型的「系统里看不出、账单上看得出来」。
--
-- 段区间的可读写法（下界恒闭 / 上界闭开 / 无上限）刻意收在这一个函数里 ——
-- 判定与报错两处必须用同一个写法，否则报错信息会和实际判定对不上。
CREATE OR REPLACE FUNCTION band_range(lo numeric, hi numeric, hi_closed boolean)
RETURNS text AS $$
  SELECT format('[%s , %s%s',
    lo,
    COALESCE(hi::text, '∞'),
    CASE WHEN hi IS NULL THEN ')' WHEN hi_closed THEN ']' ELSE ')' END);
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION guard_freight_band_overlap() RETURNS trigger AS $$
DECLARE
  model text;
  card_status text;
  other record;
  overlap boolean;
BEGIN
  SELECT charge_model, status INTO model, card_status
    FROM freight_rate_card
   WHERE tenant_id = NEW.tenant_id AND id = NEW.card_id;

  IF model IS NULL THEN
    RAISE EXCEPTION '费率卡不存在（tenant=% card=%）', NEW.tenant_id, NEW.card_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  -- (b1) 档位只属于分段模型
  IF model <> 'FLAT_RATE' THEN
    RAISE EXCEPTION
      '首重/续重模型（FIRST_PLUS_ADDITIONAL）不分档 —— 它的取价规则是首重费 + 续重进位，再加档位就是两套规则同时生效，算出来的钱无法解释'
      USING ERRCODE = 'check_violation';
  END IF;

  -- (b2) 与同一张卡的其它档位比对重叠。
  --     下界恒闭；上界闭开由各自 to_qty_inclusive 决定；to_qty 为 NULL = +∞。
  --     相交判定：两端各一个「严格在前」或「恰好相等且该端闭合」——
  --     少了后一半就会漏掉 [0,1] 闭 + [1,5] 开 这种最典型的边界重叠。
  FOR other IN
    SELECT band_no, from_qty, to_qty, to_qty_inclusive
      FROM freight_rate_band
     WHERE tenant_id = NEW.tenant_id AND card_id = NEW.card_id AND band_no <> NEW.band_no
  LOOP
    overlap :=
      -- 本段下界 是否已越过对方上界
      ( NEW.from_qty < COALESCE(other.to_qty, 'Infinity'::numeric)
        OR (NEW.from_qty = COALESCE(other.to_qty, 'Infinity'::numeric)
            AND other.to_qty_inclusive) )
      AND
      -- 对方下界 是否已越过本段上界
      ( other.from_qty < COALESCE(NEW.to_qty, 'Infinity'::numeric)
        OR (other.from_qty = COALESCE(NEW.to_qty, 'Infinity'::numeric)
            AND NEW.to_qty_inclusive) );

    IF overlap THEN
      -- 区间先拼成一段文本再进 RAISE：占位符个数必须与参数个数严格一致，
      -- 而 RAISE 只认单个字符串字面量做格式串（不能在格式串里写 || 拼接）。
      RAISE EXCEPTION
        '档位重叠：第 % 段 % 与第 % 段 % 相交 —— 同一个重量被算两遍钱，请把两段的首尾改成严格衔接（下界恒闭，上界闭开由 to_qty_inclusive 决定）',
        NEW.band_no,
        band_range(NEW.from_qty, NEW.to_qty, NEW.to_qty_inclusive),
        other.band_no,
        band_range(other.from_qty, other.to_qty, other.to_qty_inclusive)
        USING ERRCODE = 'check_violation';
    END IF;
  END LOOP;

  -- (b3) 生效中的卡不允许有「档位空洞」：量落在两段之间 ⇒ 那一段的钱没人算。
  --     草稿允许留洞（编辑费率卡时先加后调是正常流程），启用时由 trigger c 兜底。
  --     判定只需看「有没有某一段的上界正好落在这一段的下界」——
  --     下界恒闭，所以哪怕前一段的上界是开区间，那个点仍由后一段接住。
  IF card_status = 'ACTIVE' THEN
    FOR other IN
      SELECT band_no, from_qty, to_qty
        FROM freight_rate_band
       WHERE tenant_id = NEW.tenant_id AND card_id = NEW.card_id
    LOOP
      IF other.from_qty > 0 AND NOT EXISTS (
            SELECT 1 FROM freight_rate_band p
             WHERE p.tenant_id = NEW.tenant_id AND p.card_id = NEW.card_id
               AND p.to_qty = other.from_qty
      ) THEN
        RAISE EXCEPTION
          '生效中的费率卡不允许档位空洞：第 % 段从 % 起，而前面没有任何一段的上界落到 %，落在这个区间的货算不出钱',
          other.band_no, other.from_qty, other.from_qty
          USING ERRCODE = 'check_violation';
      END IF;
    END LOOP;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_freight_band_overlap_trg
  BEFORE INSERT OR UPDATE ON freight_rate_band
  FOR EACH ROW EXECUTE FUNCTION guard_freight_band_overlap();

-- (c) 🔴 启用一张卡之前，卡本身必须已经「算得出钱」。
--     分段模型：档位必须**连续覆盖 [0, +∞)** —— 从 0 起、相邻段严格衔接、
--     最高一段无上限。少任何一条，落在这个区间的货就**算不出钱**（引擎会报错，
--     而不是静默少算一段）。
--     首重续重模型：参数齐备（列级 CHECK 已钉），这里钉「启用后不能改计费模型」。
CREATE OR REPLACE FUNCTION guard_freight_rate_card_activate() RETURNS trigger AS $$
DECLARE
  band_count integer;
  lowest numeric;
  gaps integer;
  open_ended integer;
BEGIN
  IF NEW.status = 'ACTIVE' AND (TG_OP = 'INSERT' OR OLD.status <> 'ACTIVE') THEN
    IF NEW.charge_model = 'FLAT_RATE' THEN
      SELECT count(*), min(from_qty) INTO band_count, lowest
        FROM freight_rate_band
       WHERE tenant_id = NEW.tenant_id AND card_id = NEW.id;

      IF band_count = 0 THEN
        RAISE EXCEPTION
          '分段费率卡没有任何档位，不能启用 —— 启用后试算只能回落到渠道单值，而那张卡本来就是为了分段取价才建的' USING ERRCODE = 'check_violation';
      END IF;

      IF lowest <> 0 THEN
        RAISE EXCEPTION
          '分段费率卡的最下一档从 % 起（必须从 0 起）—— 低于它的重量落在任何一段之外，试算会算出 0 元运费', lowest USING ERRCODE = 'check_violation';
      END IF;

      -- 洞：除第一段外，每一段的下界都必须正好是另一段的上界（下界恒闭，
      -- 所以哪怕前一段上界是开的，那个端点仍由后一段接住）
      SELECT count(*) INTO gaps
        FROM freight_rate_band b
       WHERE b.tenant_id = NEW.tenant_id AND b.card_id = NEW.id AND b.from_qty > 0
         AND NOT EXISTS (
           SELECT 1 FROM freight_rate_band p
            WHERE p.tenant_id = b.tenant_id AND p.card_id = b.card_id
              AND p.to_qty = b.from_qty);

      IF gaps > 0 THEN
        RAISE EXCEPTION
          '分段费率卡有 % 段没有前一段接住（档位必须连续覆盖）—— 落在洞里的货算不出钱，请补齐或合并相邻段', gaps USING ERRCODE = 'check_violation';
      END IF;

      -- 最高一段必须无上限：封顶的一段意味着「超过顶的货算不出钱」
      SELECT count(*) INTO open_ended
        FROM freight_rate_band
       WHERE tenant_id = NEW.tenant_id AND card_id = NEW.id AND to_qty IS NULL;

      IF open_ended = 0 THEN
        RAISE EXCEPTION
          '分段费率卡的最高一段有上界 —— 超过这个重量的货落在所有档位之外，不能启用（最高一段请把上界留空表示无上限）' USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;

  -- 生效中不许换计费模型 / 量纲：换了等于把已发出的试算单的算法改掉
  IF TG_OP = 'UPDATE' AND OLD.status = 'ACTIVE' AND (
      NEW.charge_model <> OLD.charge_model OR NEW.basis <> OLD.basis) THEN
    RAISE EXCEPTION
      '生效中的费率卡不能改计费模型或量纲（%/% → %/%）—— 已发出的试算单是按旧口径算的，改了它们就再也复算不出来；要改请先停用再建一张',
      OLD.charge_model, OLD.basis, NEW.charge_model, NEW.basis
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_freight_rate_card_activate_trg
  BEFORE INSERT OR UPDATE ON freight_rate_card
  FOR EACH ROW EXECUTE FUNCTION guard_freight_rate_card_activate();

-- (d) 分国家规则必须落在渠道的适用国家里（防「配了但永远用不上」的死配置）。
CREATE OR REPLACE FUNCTION guard_freight_country_in_scope() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM logistics_channel_scope s
       WHERE s.tenant_id = NEW.tenant_id AND s.channel_id = NEW.channel_id
         AND s.scope_kind = 'COUNTRY' AND s.scope_value = NEW.country_code
  ) THEN
    RAISE EXCEPTION
      '国家 % 不在这条渠道的适用国家里（上游 10.1.1.2 logistics_channel_scope）—— 给它配偏远/时效规则只会让人以为这条渠道走得了这个国家',
      NEW.country_code USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_freight_country_in_scope_trg
  BEFORE INSERT OR UPDATE ON freight_channel_country
  FOR EACH ROW EXECUTE FUNCTION guard_freight_country_in_scope();

-- ---------------------------------------------------------------------------
-- 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'freight_rate_card', 'freight_rate_band', 'freight_channel_country',
    'freight_quote', 'freight_quote_line' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 账号与授权（沿用 V1/V93/V129/V134/V136 的条件建角色，保证迁移可重复执行）
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
  freight_rate_card, freight_rate_band, freight_channel_country,
  freight_quote, freight_quote_line TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 / V136 同口径）。
REVOKE ALL ON
  freight_rate_card, freight_rate_band, freight_channel_country,
  freight_quote, freight_quote_line FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：运费试算属于「平台/承运商操作」，与 10.1.1.1 承运商、
-- 10.1.1.2 渠道配置、10.3.2.1 交运批次同族，因此 identity 服务零改动，
-- 跨车道回归面 = 0。
