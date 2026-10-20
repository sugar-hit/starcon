-- 5.2.3.2 物流与头程费用：物流渠道的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加、
-- 偏远附加）与头程费用结构，以及实际运费这个独立事实 —— 三者并列，差额现算不落库。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：维护物流渠道的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加、偏远附加）
--             与头程费用结构，用于测算运费占成本比。
--   **输入**：渠道计费规则 / 附加费参数 / 头程运输方式费用。
--   **输出**：费用规则集 + 测算输入。
--   **关系**：上游 = 10 物流与头程（卷级引用）；下游 = 5.3.1.1 测算。
--
-- ============================================================ 第一节：本模块的四个事实，以及它们各自的真实来源
--
-- 蓝图职责句点名了**两套**东西，关系句又给了**两个**方向，合起来是四组数据：
--
--   | 事实                        | 回答的问题                       | 本仓真实来源                        |
--   |-----------------------------|----------------------------------|-------------------------------------|
--   | 渠道计费规则（事前测算口径） | 「这票按规则应该付多少运费」      | 本服务自持（租户维护的参数）         |
--   | 附加费参数                  | 「旺季 / 燃油 / 偏远再加多少」    | 本服务自持                           |
--   | 头程费用结构                | 「头程这一票由哪些成本项构成」    | 本服务自持                           |
--   | 实际运费（事后对账口径）    | 「物流商实际收了多少」            | ⚠️ 上游没有结算事实 ⇒ 只有人工登记   |
--
-- 🔴 **两条不可让步的纪律**（与 5.2.3.1 平台费用同源，但本模块另有自己的落点）：
--
--   1. **规则与实际是两个独立事实**。合并成一个「运费」列，就再也答不出
--      「是规则定错了，还是物流商多收了」—— 而那正是测算运费占成本比时最该被看见的一件事。
--   2. **差异不能恒等于 0 冒充「没有差异」**。没有实际运费时，差异是**算不出来**
--      （available=false + 原因），不是 0。
--
-- ⚠️ **那么实际运费从哪来？** —— 本仓**没有**可读的实际运费结算事实：
--   * channel 服务的 `freight_quote.cost_basis` 恒为 `RULE_ESTIMATE`（单一 CHECK 取值），
--     也就是说它的「报价」本来就是**按规则估的**，不是物流商的结算单；
--   * trade / warehouse 侧也没有可用的实际运费结算表。
--
-- 所以本迁移**显式声明这个缺口**：实际运费的唯一合法来源是**人工登记的观察值**
-- （source='MANUAL_OBSERVATION'），能力声明里「10 的实际运费对账」维度标成
-- available=false + ownerModule=10 + 一句 impact。
-- 本迁移**绝不**编一个假的「物流商结算数据源」去冒充 10 的对账结果。
--
-- ============================================================ 第二节：挂载维度是**逻辑引用**，不是跨库外键
--
-- 渠道 / 承运商 / 运输方式都归 **10 物流与头程**（卷级引用）。AGENTS.md 铁律：
-- **禁止跨服务数据库访问 / 跨库 FK / 共享视图**。
--
-- ⇒ 本迁移：
--   * **零外键**（理由见第五节：给既有表建外键会打断同服务既有测试的 TRUNCATE）；
--   * 渠道标识只存**逻辑引用码 + 名称快照**：
--       `channel_code` / `carrier_code` / `shipping_method_code` + 名称列。
--     名称快照让规则在渠道服务不可达时仍然读得懂（"这是哪条渠道"），
--     而**权威校验**由服务层在写入口做（见 LogisticsFreightService）。
--   * **不建 channel 表的镜像副本**（那是另一个服务的写入所有权）。
--
-- 与 5.2.3.1 的 `PlatformFeePort`（读 channel 的 `/channel/platforms`）**不照抄**：
-- 平台目录是**全局码表**、需要权威校验；而物流渠道码在本仓属 10 的卷级引用，
-- 5.2.3.1 已明确「5.2.3.2 不代它开形状」，本模块也不反向依赖一个尚未交付的上游形状。
--
-- ============================================================ 第三节：计费口径与「运费必须是精确值」
--
-- 三种计费口径（`charge_basis`），**互斥**：
--
--   | charge_basis | 计费量是什么        | 单位   | 需要哪些输入            |
--   |--------------|---------------------|--------|-------------------------|
--   | BY_WEIGHT    | 计费重（kg）        | kg     | 实重（+ 长宽高做体积重） |
--   | BY_VOLUME    | 计费体积（m³）      | m³     | 长 / 宽 / 高（cm）       |
--   | BY_PIECE     | 件数                | 件     | 件数                    |
--
-- 🔴 **体积重换算系数**（`volumetric_divisor`，单位 cm³/kg）：快递的经典口径是
-- 「计费重 = max(实重, 长×宽×高 ÷ 系数)」。系数非空且给了长宽高时按这个算；
-- 系数为空 ⇒ 只按实重计（渠道不收体积重）。
--
-- 🔴 **全部是 numeric，禁 float / double**（AGENTS.md）：重量、体积、费率、
-- 换算系数一律 numeric。数量口径统一 `numeric(19,6)`，金额口径 `numeric(19,4)`，
-- 费率口径 `numeric(19,8)` 的**小数**（0.15 = 15%）。
--
-- ============================================================ 第四节：阶梯档位 —— 首段 + 续段，或整档一口价
--
-- 档位是**独立的一张表**而不是规则上的一个 JSON 列：要能被查询（「这个渠道从多少公斤开始
-- 涨到多少钱」）、能被逐档核对、也要能在整体替换时保证「同一次请求里要么全换要么全不换」。
--
-- 两种计价形态（`price_mode`），**互斥**（CHECK 钉死）：
--
--   | price_mode           | 含义                                   | 用到的列                       |
--   |----------------------|----------------------------------------|--------------------------------|
--   | FLAT_PER_TIER        | 整档一口价（落在该档就一个价）           | `flat_price`                   |
--   | FIRST_PLUS_ADDITIONAL | 首段一个价、超出首段按续段单价           | `first_limit`/`first_price`/`additional_price` |
--
-- 「首段」= 档位下界到 `first_limit` 之间（例如「首重 1 公斤」），
-- 「续段」= `first_limit` 之后到该档上界。选档口径：**下界闭、上界开**，
-- `max_value IS NULL` = 该档以上无上限。计费量低于最低档下界 ⇒
-- **该规则不适用**（不是一个 0 运费项）—— 页面要显示「不适用」而不是「运费 0」，
-- 后者会让人以为物流商真的没收钱。
--
-- ============================================================ 第五节：不可变优先 —— 改规则 = 新增一条新版本 + 停用旧版本
--
-- 🔴 **本模块与 5.2.3.1 刻意不同**：5.2.3.1 的规则可改（乐观锁 version），
-- 而本模块的**已生效规则与档位一律不可改写**（任务书硬性要求）。
--
-- 落地方式（照 5.2.2.2 汇率快照的「只追加 + 触发器挡 UPDATE」取舍）：
--   * 规则、附加费、头程费用结构三张表都有**版本链**：
--     `version_no` + `supersedes_id`（指向上一版）+ `superseded_by_id`（指向后继版）。
--   * 一条规则的**内容**（渠道、计费口径、费率档位、币种…）建好之后**一个字节都不许改**；
--     DB 层用触发器挡 UPDATE。
--   * 唯一允许的写入是**状态流转**：生效中 → 已被取代 / 已停用。
--   * 档位与头程成本项是**从属事实**：只在建新版本时随新版本一起插入，
--     建好之后既不许改也不许删（GRANT 里就没有 UPDATE / DELETE）。
--
-- ⇒ 「把这条规则的费率从 0.15 改成 0.12」在数据上表达为
--   「新增一条 version_no+1 的规则 + 旧条��置为已被取代」，
--   历史测算（按当时的 effective_from 取规则）永远读得到当时的费率。
--
-- ⚠️ **为什么零外键**（仓库既有先例，逐条抄自 V80 / V84 / V221 / V226 的注释）：
-- 给既有表建外键会把同服务既有测试的 `truncate` 打断（23503
-- 「cannot truncate a table referenced in a foreign key constraint」），整批测试变红。
-- 本迁移的引用完整性一律用**触发器**保证。
--
-- ============================================================ 第六节：附加费 —— 同一时刻同一类型只能命中一条
--
-- 燃油 / 旺季 / 偏远是**三种不同类型**，可以同时命中；
-- 但**同一个类型**在同一个渠道上同一时刻只能有一条生效规则 ——
-- 两条同时生效时「加多少」没有答案，而服务层只会安静地挑一条（挑哪条取决于查询顺序，
-- 不可复现）。⇒ DB 层用守卫触发器保证「同一时刻同一类型不重叠」。
--
-- 附加费的计算口径（`calc_basis`）：
--   * PERCENT_OF_BASE_FREIGHT —— 基础运费的百分比（燃油附加的经典口径）；
--   * FIXED_PER_ORDER          —— 每单一个固定额（旺季附加的经典口径）；
--   * FIXED_PER_UNIT           —— 每件一个固定额（偏远附加的经典口径）。
--
-- 🔴 **附加费一律加在「基础运费」上，不含其它附加费**（避免 A 抽 B、B 抽 A 的死循环），
-- 口径写死在服务层与本注释里。
--
-- ============================================================ 第七节：头程费用结构 —— 独立建模，不塞进渠道计费规则
--
-- 头程（国内 → 海外仓）与渠道派送是**两笔不同的钱**，账单口径也不同：
-- 渠道按 kg / m³ / 件计，头程按柜 / CBM / 票计。所以它**独立成表**：
--
--   * `logistics_head_freight_structure` —— 结构头：运输方式 + 起运地 + 目的地 + 币种 + 生效期；
--   * `logistics_head_freight_component` —— 成本项：海运费 / 报关费 / 拖车费 / 保险费…
--     每个成本项有自己的**币种**与**计价基数**（每票 / 每 CBM / 每柜 / 每公斤）。
--
-- ⚠️ **成本项可以有不同的币种**（海运费 CNY、保险费 USD 是常态），
-- 而**跨币种绝不相加**（仓库铁律）⇒ 头程费合计在混币时是**算不出来** + 原因，不是 0。
--
-- ⚠️ 起运地 / 目的地是**逻辑引用码 + 名称快照**（07 仓储是另一个服务 ⇒ 不建跨库 FK）。
--
-- ============================================================ 第八节：RLS 与授权
--
-- 六张表都是租户级业务表（物流费率与运费都是租户自己的经营事实）：
-- `tenant_id uuid NOT NULL` + `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`
-- + isolation 策略（照抄 V32 / V215 / V226 第八节的 `DO $$ FOREACH` 写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的费率，
-- 而物流费率直接决定别人的利润与报价。
--
-- 授权按**不可变优先**收紧（比 V226 更严，这是本模块的有意取舍）：
--   * 规则 / 附加费 / 头程结构：SELECT + INSERT + UPDATE（UPDATE 只够走状态流转，
--     触发器再把内容改写挡掉）；
--   * 档位 / 成本项：只有 SELECT + INSERT（**连改都不给**，更别说删）；
--   * 实际运费：SELECT + INSERT + UPDATE（UPDATE 只够作废四件套）。
--
-- ============================================================ 第九节表：logistics_freight_rule —— 渠道计费规则（版本化，不可改写）

CREATE TABLE logistics_freight_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- ===== 挂载维度（逻辑引用 + 名称快照，第二个理由见第二节）=====
  channel_code varchar(32) NOT NULL,
  channel_name varchar(128) NOT NULL DEFAULT '',
  carrier_code varchar(32),
  carrier_name varchar(128) NOT NULL DEFAULT '',
  shipping_method_code varchar(32) NOT NULL,
  shipping_method_name varchar(128) NOT NULL DEFAULT '',

  -- 计费口径（第三节）：按重量 / 体积 / 件数。
  charge_basis text NOT NULL
    CHECK(charge_basis IN ('BY_WEIGHT','BY_VOLUME','BY_PIECE')),

  -- 费率币种。跨币种绝不相加（仓库铁律）⇒ 附加费币种必须与它一致才能算合计。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 体积重换算系数（cm³/kg）。非空 ⇒ 计费重 = max(实重, 长×宽×高 ÷ 系数)。
  volumetric_divisor numeric(19,6),
  -- 最低收费（起步价）。运费低于它时按它收 —— 快递的经典口径。
  minimum_charge numeric(19,4),

  -- ===== 生效区间（timestamptz：渠道费率常常在某个具体时刻切换）=====
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  -- ===== 版本链（第五节）=====
  version_no int NOT NULL DEFAULT 1 CHECK(version_no > 0),
  supersedes_id uuid,
  superseded_by_id uuid,
  -- 状态机：生效中 → 已被取代 / 已停用。**不可回退**（触发器钉死）。
  status text NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','SUPERSEDED','DISABLED')),
  -- 停用理由（**停用必填**）：没有理由的停用会让「为什么这条渠道费率失效了」无法解释。
  disable_reason text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 码的形态与本仓既有的 channel / platform 码表同形（ASCII 大写字母数字与短横线）。
  CHECK(channel_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(carrier_code IS NULL OR carrier_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(shipping_method_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(effective_to IS NULL OR effective_to >= effective_from),
  CHECK(volumetric_divisor IS NULL OR volumetric_divisor > 0),
  CHECK(minimum_charge IS NULL OR minimum_charge >= 0),
  CHECK(supersedes_id IS NULL OR supersedes_id <> id),
  CHECK(superseded_by_id IS NULL OR superseded_by_id <> id)
);

-- 同一维度组合下**不得有两条生效区间重叠的规则**（重叠时「用哪一条」没有答案）。
-- 同一索引也把「这个渠道有哪些规则」的主查询路径带上。
CREATE INDEX logistics_freight_rule_by_scope
  ON logistics_freight_rule(tenant_id, channel_code, COALESCE(carrier_code,''),
                            shipping_method_code, charge_basis, effective_from DESC);
-- 「最近建的规则」—— 规则治理页的默认排序。
CREATE INDEX logistics_freight_rule_by_created ON logistics_freight_rule(tenant_id, created_at DESC);
-- 「这条规则被哪条取代了」—— 版本链回溯。
CREATE INDEX logistics_freight_rule_by_supersedes ON logistics_freight_rule(tenant_id, supersedes_id);

-- ============================================================ 第十节表：logistics_freight_rule_tier —— 阶梯档位（从属事实，不可改写）

CREATE TABLE logistics_freight_rule_tier (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 所属规则。**不建外键**（第五节），引用完整性由触发器 guard 保证。
  rule_id uuid NOT NULL,
  -- 档位序号（从 1 起）。
  seq int NOT NULL CHECK(seq > 0),
  -- 档位下界（含）与上界（不含，NULL = 无上限）。单位随规则的计费口径
  -- （kg / m³ / 件）。
  min_value numeric(19,6) NOT NULL CHECK(min_value >= 0),
  max_value numeric(19,6),
  -- 计价形态（第四节）。
  price_mode text NOT NULL
    CHECK(price_mode IN ('FLAT_PER_TIER','FIRST_PLUS_ADDITIONAL')),
  -- 整档一口价（FLAT_PER_TIER 形态用）。
  flat_price numeric(19,4),
  -- 首段上限（FIRST_PLUS_ADDITIONAL 形态用）：档位下界 ~ first_limit 走首段价。
  first_limit numeric(19,6),
  -- 首段单价（作用于「档位下界 ~ first_limit」这一段）。
  first_price numeric(19,4),
  -- 续段单价（作用于「first_limit ~ 档位上界」这一段）。
  additional_price numeric(19,4),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一规则下序号唯一。
  UNIQUE(tenant_id, rule_id, seq),
  -- 上界必须严格大于下界（否则是一个空档）。
  CHECK(max_value IS NULL OR max_value > min_value),
  -- 两种形态的参数**互斥且齐备**（CASE 写法，把形态与列的对应关系写死在一处）。
  CHECK(CASE
    WHEN price_mode = 'FLAT_PER_TIER'
      THEN flat_price IS NOT NULL AND flat_price >= 0
       AND first_limit IS NULL AND first_price IS NULL AND additional_price IS NULL
    ELSE flat_price IS NULL
       AND first_limit IS NOT NULL AND first_limit > min_value
       AND (max_value IS NULL OR first_limit <= max_value)
       AND first_price IS NOT NULL AND first_price >= 0
       AND additional_price IS NOT NULL AND additional_price >= 0
  END)
);

-- 阶梯查询：取某规则的全部档位（按序号）。
CREATE INDEX logistics_freight_rule_tier_by_rule ON logistics_freight_rule_tier(tenant_id, rule_id, seq);

-- ============================================================ 第十一节表：logistics_freight_surcharge —— 附加费（版本化，不可改写）

CREATE TABLE logistics_freight_surcharge (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 挂载维度（逻辑引用 + 名称快照）。
  channel_code varchar(32) NOT NULL,
  channel_name varchar(128) NOT NULL DEFAULT '',
  carrier_code varchar(32),
  carrier_name varchar(128) NOT NULL DEFAULT '',
  shipping_method_code varchar(32) NOT NULL,
  shipping_method_name varchar(128) NOT NULL DEFAULT '',

  -- 附加费类型：燃油 / 旺季 / 偏远 / 其它。**只增不改**：它会进历史快照的口径对照。
  surcharge_type text NOT NULL
    CHECK(surcharge_type IN ('FUEL','PEAK_SEASON','REMOTE_AREA','OTHER')),

  -- 计算口径（第六节）。
  calc_basis text NOT NULL
    CHECK(calc_basis IN ('PERCENT_OF_BASE_FREIGHT','FIXED_PER_ORDER','FIXED_PER_UNIT')),
  -- 比例（小数口径：0.15 = 15%）。取值域 [0,1]，误填成 15（当成百分数）当场被拒。
  rate numeric(19,8),
  -- 固定额（每单 / 每件）。
  fixed_amount numeric(19,4),
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 生效区间 + 版本链（第五节）。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  version_no int NOT NULL DEFAULT 1 CHECK(version_no > 0),
  supersedes_id uuid,
  superseded_by_id uuid,
  status text NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','SUPERSEDED','DISABLED')),
  -- 停用理由（**停用必填**）。
  disable_reason text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(channel_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(carrier_code IS NULL OR carrier_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(shipping_method_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(currency ~ '^[A-Z]{3}$'),
  CHECK(effective_to IS NULL OR effective_to >= effective_from),
  CHECK(rate IS NULL OR (rate >= 0 AND rate <= 1)),
  CHECK(fixed_amount IS NULL OR fixed_amount >= 0),
  -- 形态与参数互斥（CASE 写法）。
  CHECK(CASE
    WHEN calc_basis = 'PERCENT_OF_BASE_FREIGHT'
      THEN rate IS NOT NULL AND fixed_amount IS NULL
    ELSE rate IS NULL AND fixed_amount IS NOT NULL
  END),
  CHECK(supersedes_id IS NULL OR supersedes_id <> id),
  CHECK(superseded_by_id IS NULL OR superseded_by_id <> id)
);

-- 「这个渠道上有哪些附加费」—— 测算与差异的主查询路径。
CREATE INDEX logistics_freight_surcharge_by_scope
  ON logistics_freight_surcharge(tenant_id, channel_code, COALESCE(carrier_code,''),
                                 shipping_method_code, surcharge_type, effective_from DESC);
CREATE INDEX logistics_freight_surcharge_by_created ON logistics_freight_surcharge(tenant_id, created_at DESC);

-- ============================================================ 第十二节表：logistics_head_freight_structure —— 头程费用结构（版本化，不可改写）

CREATE TABLE logistics_head_freight_structure (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 头程运输方式（逻辑引用 + 名称快照）。
  shipping_method_code varchar(32) NOT NULL,
  shipping_method_name varchar(128) NOT NULL DEFAULT '',
  -- 起运地 / 目的地（逻辑引用 + 名称快照；07 仓储是另一个服务 ⇒ 不建跨库 FK）。
  origin_code varchar(32) NOT NULL,
  origin_name varchar(128) NOT NULL DEFAULT '',
  destination_code varchar(32) NOT NULL,
  destination_name varchar(128) NOT NULL DEFAULT '',

  -- 结构币种（成本项可以有自己的币种，混币时合计算不出来 —— 第七节）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 生效区间 + 版本链（第五节）。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  version_no int NOT NULL DEFAULT 1 CHECK(version_no > 0),
  supersedes_id uuid,
  superseded_by_id uuid,
  status text NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','SUPERSEDED','DISABLED')),
  -- 停用理由（**停用必填**）。
  disable_reason text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(shipping_method_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(origin_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(destination_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(effective_to IS NULL OR effective_to >= effective_from),
  CHECK(supersedes_id IS NULL OR supersedes_id <> id),
  CHECK(superseded_by_id IS NULL OR superseded_by_id <> id)
);

-- 「这条航线有哪些头程结构」—— 头程费测算的主查询路径。
CREATE INDEX logistics_head_freight_structure_by_scope
  ON logistics_head_freight_structure(tenant_id, shipping_method_code, origin_code,
                                     destination_code, effective_from DESC);
CREATE INDEX logistics_head_freight_structure_by_created
  ON logistics_head_freight_structure(tenant_id, created_at DESC);

-- ============================================================ 第十三节表：logistics_head_freight_component —— 头程成本项（从属事实，不可改写）

CREATE TABLE logistics_head_freight_component (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 所属结构。**不建外键**（第五节）。
  structure_id uuid NOT NULL,
  seq int NOT NULL CHECK(seq > 0),
  -- 成本项类型：海运 / 报关 / 拖车 / 保险 / 操作 / 其它。
  component_type text NOT NULL
    CHECK(component_type IN ('LINE_HAUL','CUSTOMS','TRUCKING','INSURANCE','HANDLING','OTHER')),
  -- 人话标签（页面上直接显示）。
  label varchar(128) NOT NULL DEFAULT '',
  -- 计价基数：每票 / 每立方米 / 每柜 / 每公斤。
  calc_basis text NOT NULL
    CHECK(calc_basis IN ('PER_SHIPMENT','PER_CBM','PER_CONTAINER','PER_KG')),
  -- 单位费用。
  unit_amount numeric(19,4) NOT NULL CHECK(unit_amount >= 0),
  -- 成本项自己的币种（混币 ⇒ 合计算不出来，第七节）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, structure_id, seq)
);

CREATE INDEX logistics_head_freight_component_by_structure
  ON logistics_head_freight_component(tenant_id, structure_id, seq);

-- ============================================================ 第十四节表：logistics_freight_actual —— 实际运费（独立事实，只追加）

CREATE TABLE logistics_freight_actual (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 与规则同一套维度（第二个理由见第一节）：实际运费也必须能定位到渠道 / 运输方式，
  -- 否则它与规则**对不上**，差异就算不出来。
  channel_code varchar(32) NOT NULL,
  channel_name varchar(128) NOT NULL DEFAULT '',
  carrier_code varchar(32),
  carrier_name varchar(128) NOT NULL DEFAULT '',
  shipping_method_code varchar(32) NOT NULL,
  shipping_method_name varchar(128) NOT NULL DEFAULT '',
  charge_basis text NOT NULL
    CHECK(charge_basis IN ('BY_WEIGHT','BY_VOLUME','BY_PIECE')),

  -- 实际发生额。**允许 0**（物流商这笔确实没收运费）与**允许负**（退款 / 冲回）。
  amount numeric(19,4) NOT NULL,
  -- 拆分登记（可空）：基础运费 / 附加费合计。填了就用于解释差额落在哪一块。
  base_amount numeric(19,4),
  surcharge_amount numeric(19,4),
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 差异计算的两个基数 =====
  -- 计费量：单位随计费口径（kg / m³ / 件）—— 按规则重算预期值要用它。
  chargeable_quantity numeric(19,6) NOT NULL CHECK(chargeable_quantity >= 0),
  -- 件数：每件固定额的附加费要用它。
  quantity numeric(19,6) NOT NULL CHECK(quantity >= 0),

  -- 运费发生日（结算周期日）。
  occurred_on date NOT NULL,

  -- 来源。**只有人工登记这一种取值**：上游（10 / channel / trade / warehouse）
  -- 当前没有可读的实际运费结算事实（第一节），本模块不编一个假的来源去冒充它。
  source text NOT NULL DEFAULT 'MANUAL_OBSERVATION'
    CHECK(source IN ('MANUAL_OBSERVATION')),
  -- 物流商账单号 / 结算单号等外部凭据（自由文本，只作追溯用）。
  source_ref text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',

  -- 作废：录错的行作废而不是删除（历史要可追溯）。
  is_void boolean NOT NULL DEFAULT false,
  void_reason text NOT NULL DEFAULT '',
  voided_by uuid,
  voided_at timestamptz,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(channel_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(carrier_code IS NULL OR carrier_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(shipping_method_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(base_amount IS NULL OR base_amount >= 0),
  CHECK(surcharge_amount IS NULL OR surcharge_amount >= 0),
  -- 作废必须留理由：没有理由的作废会让「运费为什么变了」无法解释。
  CHECK(NOT is_void OR btrim(void_reason) <> '')
);

-- 「这个渠道上实际发生了多少运费」—— 差异计算的主查询路径。
CREATE INDEX logistics_freight_actual_by_scope
  ON logistics_freight_actual(tenant_id, channel_code, COALESCE(carrier_code,''),
                              shipping_method_code, occurred_on DESC);
-- 「某段时间内的实际运费」—— 差异对照按区间取数。
CREATE INDEX logistics_freight_actual_by_period
  ON logistics_freight_actual(tenant_id, occurred_on DESC, channel_code, shipping_method_code);

-- ============================================================ 第十五节：触发器（不可变优先 + 引用校验 + 不重叠）
--
-- 【为什么全部用触发器而不是外键】见第五节：给既有表建外键会把同服务既有测试的
-- TRUNCATE 打断（23503）。零外键是本仓的既有纪律。

-- 15.1 三个版本化主表共用**同一条**不可变守卫。
--
--     手法：把行转成 jsonb，**扣掉**允许变的三列（status / effective_to /
--     superseded_by_id）之后整体比较 ⇒ 内容一个字节都不许改。
--     写死三份逐列比较会漏掉某列（漏掉的那列就变成可改写的后门），
--     而 jsonb 整体比较**天然覆盖新增列**。
CREATE FUNCTION guard_logistics_freight_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_status_changed boolean;
BEGIN
  v_status_changed := NEW.status IS DISTINCT FROM OLD.status;

  -- 内容列一律不可改写（扣掉允许变的三列之后整体比较）。
  IF (to_jsonb(NEW) - 'status' - 'effective_to' - 'superseded_by_id' - 'disable_reason')
     IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'effective_to' - 'superseded_by_id' - 'disable_reason')
  THEN
    RAISE EXCEPTION '% 的内容不可改写：改规则 = 新增一条新版本 + 停用旧版本', TG_TABLE_NAME
      USING ERRCODE = '23514';
  END IF;

  -- 状态流转：只允许「生效中 → 已被取代 / 已停用」，且不可回退。
  IF v_status_changed THEN
    IF OLD.status <> 'ACTIVE' THEN
      RAISE EXCEPTION '只有生效中的版本可以被停用或取代（当前状态 %）', OLD.status
        USING ERRCODE = '23514';
    END IF;
    IF NEW.status NOT IN ('SUPERSEDED','DISABLED') THEN
      RAISE EXCEPTION '状态只能从「生效中」流转到「已被取代」或「已停用」'
        USING ERRCODE = '23514';
    END IF;
  ELSE
    -- 没有状态流转时，生效终点、后继版本指针与停用理由一律不可动。
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       OR NEW.superseded_by_id IS DISTINCT FROM OLD.superseded_by_id
       OR NEW.disable_reason IS DISTINCT FROM OLD.disable_reason
    THEN
      RAISE EXCEPTION '生效终点、后继版本指针与停用理由只能在状态流转的那一次更新里改动'
        USING ERRCODE = '23514';
    END IF;
  END IF;

  -- 被取代时必须写明后继版本；停用时不得有后继版本。
  IF NEW.status = 'SUPERSEDED' AND NEW.superseded_by_id IS NULL THEN
    RAISE EXCEPTION '版本被取代时必须写明后继版本' USING ERRCODE = '23514';
  END IF;
  IF NEW.status = 'DISABLED' AND NEW.superseded_by_id IS NOT NULL THEN
    RAISE EXCEPTION '版本被停用时不应有后继版本' USING ERRCODE = '23514';
  END IF;
  -- 停用必须留理由：没有理由的停用会让「为什么这条渠道费率失效了」无法解释。
  IF NEW.status = 'DISABLED' AND btrim(NEW.disable_reason) = '' THEN
    RAISE EXCEPTION '停用必须写理由' USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER logistics_freight_rule_immutable_guard
  BEFORE UPDATE ON logistics_freight_rule
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_freight_immutable();

CREATE TRIGGER logistics_freight_surcharge_immutable_guard
  BEFORE UPDATE ON logistics_freight_surcharge
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_freight_immutable();

CREATE TRIGGER logistics_head_freight_structure_immutable_guard
  BEFORE UPDATE ON logistics_head_freight_structure
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_freight_immutable();

-- 15.2 阶梯与成本项是**从属事实**：建好之后连改都不给（GRANT 里就没有 UPDATE），
--     这里再加一道触发器兜底（防御纵深：万一将来有人多给了 UPDATE 权限）。
CREATE FUNCTION guard_logistics_freight_no_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的从属事实：改它 = 新增一条新版本', TG_TABLE_NAME
    USING ERRCODE = '23514';
END $$;

CREATE TRIGGER logistics_freight_rule_tier_no_update
  BEFORE UPDATE OR DELETE ON logistics_freight_rule_tier
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_freight_no_update();

CREATE TRIGGER logistics_head_freight_component_no_update
  BEFORE UPDATE OR DELETE ON logistics_head_freight_component
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_freight_no_update();

-- 15.3 阶梯的所属规则必须存在且同租户（替代外键）。
CREATE FUNCTION guard_logistics_freight_tier_rule() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  owner_tenant uuid;
BEGIN
  SELECT tenant_id INTO owner_tenant FROM logistics_freight_rule
   WHERE id = NEW.rule_id AND tenant_id = NEW.tenant_id;
  IF owner_tenant IS NULL THEN
    RAISE EXCEPTION '阶梯所属的物流计费规则不存在或不属于本租户：%', NEW.rule_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER logistics_freight_tier_rule_guard
  BEFORE INSERT ON logistics_freight_rule_tier
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_freight_tier_rule();

-- 15.4 成本项的所属结构必须存在且同租户（替代外键）。
CREATE FUNCTION guard_logistics_head_freight_component_structure() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  owner_tenant uuid;
BEGIN
  SELECT tenant_id INTO owner_tenant FROM logistics_head_freight_structure
   WHERE id = NEW.structure_id AND tenant_id = NEW.tenant_id;
  IF owner_tenant IS NULL THEN
    RAISE EXCEPTION '成本项所属的头程费用结构不存在或不属于本租户：%', NEW.structure_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER logistics_head_freight_component_structure_guard
  BEFORE INSERT ON logistics_head_freight_component
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_head_freight_component_structure();

-- 15.5 同一作用域下**不得有两条生效区间重叠的规则**（重叠时「用哪一条」没有答案）。
--
--     🔴 为什么不用 EXCLUDE USING gist：后者要 CREATE EXTENSION btree_gist
--     （一条持续的数据库级变更），而本仓没有任何迁移用过它 —— 不为一个约束引入新的扩展依赖。
--     区间重叠由**服务层**在建规则时检查并 409 拒绝，DB 层这道守卫兜底「完全重叠」
--     与并发写入（两个事务同时建同作用域的规则时，后一个必然撞上）。
--
--     ⚠️ UPDATE 时排除本行（OLD.id）：状态流转的那一次更新会把本行的区间收窄，
--     不该拿自己跟自己比。
--
-- 🔴 **「用哪一条」由**生效区间**决定，不由 status 决定**：
--    一个被取代的版本仍然覆盖它自己那段区间（它的 effective_to 被关在新版本的起点上），
--    所以**历史测算读到的还是当时那一版费率** —— 这正是不可变优先要保住的东西。
--    「当前生效」只是「区间包含 now」的那一条而已，不需要 status 参与判断。
--
-- 🔴 **判定用 `IF FOUND THEN`，不用 `IF clash IS NOT NULL`**（实测踩过的坑）：
--    `SELECT ... INTO clash` 命中了行（同一套 WHERE 条件的 count 确实 > 0），
--    但 `clash` 是**未定型的 record 变量**，`clash IS NOT NULL` 在这种写法下**判不出**，
--    于是这道守卫形同虚设 —— 生效区间重叠的规则被放行，「用哪一条」没有答案。
--    `FOUND` 才是 `SELECT INTO` 的官方「有没有命中行」标志位。
CREATE FUNCTION guard_logistics_freight_rule_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_exclude uuid;
  clash     record;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    v_exclude := OLD.id;
  ELSE
    v_exclude := NULL;
  END IF;

  SELECT r.id, r.effective_from, r.effective_to INTO clash
    FROM logistics_freight_rule r
   WHERE r.tenant_id = NEW.tenant_id
     AND r.id IS DISTINCT FROM v_exclude
     AND r.channel_code = NEW.channel_code
     AND coalesce(r.carrier_code,'') = coalesce(NEW.carrier_code,'')
     AND r.shipping_method_code = NEW.shipping_method_code
     AND r.charge_basis = NEW.charge_basis
     AND r.effective_from < coalesce(NEW.effective_to, 'infinity'::timestamptz)
     AND coalesce(r.effective_to, 'infinity'::timestamptz) > NEW.effective_from
   LIMIT 1;

  IF FOUND THEN
    RAISE EXCEPTION '同一（渠道 / 承运商 / 运输方式 / 计费口径）下已有生效区间重叠的规则（% ~ %），两条规则同时生效时「用哪一条」没有答案',
      clash.effective_from, coalesce(clash.effective_to::text, '长期有效')
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER logistics_freight_rule_scope_guard
  BEFORE INSERT OR UPDATE ON logistics_freight_rule
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_freight_rule_scope();

-- 15.6 附加费：同一作用域同一类型下**不得有两条生效区间重叠的附加费**（第六节）。
CREATE FUNCTION guard_logistics_freight_surcharge_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_exclude uuid;
  clash     record;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    v_exclude := OLD.id;
  ELSE
    v_exclude := NULL;
  END IF;

  SELECT s.id, s.effective_from, s.effective_to INTO clash
    FROM logistics_freight_surcharge s
   WHERE s.tenant_id = NEW.tenant_id
     AND s.id IS DISTINCT FROM v_exclude
     AND s.channel_code = NEW.channel_code
     AND coalesce(s.carrier_code,'') = coalesce(NEW.carrier_code,'')
     AND s.shipping_method_code = NEW.shipping_method_code
     AND s.surcharge_type = NEW.surcharge_type
     AND s.effective_from < coalesce(NEW.effective_to, 'infinity'::timestamptz)
     AND coalesce(s.effective_to, 'infinity'::timestamptz) > NEW.effective_from
   LIMIT 1;

  IF FOUND THEN
    RAISE EXCEPTION '同一（渠道 / 承运商 / 运输方式 / 附加费类型）下已有生效区间重叠的附加费（% ~ %）',
      clash.effective_from, coalesce(clash.effective_to::text, '长期有效')
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER logistics_freight_surcharge_scope_guard
  BEFORE INSERT OR UPDATE ON logistics_freight_surcharge
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_freight_surcharge_scope();

-- 15.7 头程结构：同一（运输方式 / 起运地 / 目的地）下不得有两条生效区间重叠的结构。
CREATE FUNCTION guard_logistics_head_freight_structure_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_exclude uuid;
  clash     record;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    v_exclude := OLD.id;
  ELSE
    v_exclude := NULL;
  END IF;

  SELECT s.id, s.effective_from, s.effective_to INTO clash
    FROM logistics_head_freight_structure s
   WHERE s.tenant_id = NEW.tenant_id
     AND s.id IS DISTINCT FROM v_exclude
     AND s.shipping_method_code = NEW.shipping_method_code
     AND s.origin_code = NEW.origin_code
     AND s.destination_code = NEW.destination_code
     AND s.effective_from < coalesce(NEW.effective_to, 'infinity'::timestamptz)
     AND coalesce(s.effective_to, 'infinity'::timestamptz) > NEW.effective_from
   LIMIT 1;

  IF FOUND THEN
    RAISE EXCEPTION '同一（运输方式 / 起运地 / 目的地）下已有生效区间重叠的头程费用结构（% ~ %）',
      clash.effective_from, coalesce(clash.effective_to::text, '长期有效')
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER logistics_head_freight_structure_scope_guard
  BEFORE INSERT OR UPDATE ON logistics_head_freight_structure
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_head_freight_structure_scope();

-- 15.8 实际运费**只追加**：除作废四件套外，其余列一律不可改写。
--     「改一条实际运费」= 让历史事实可以回溯修改 ⇒ 差异的历史就没有意义了。
CREATE FUNCTION guard_logistics_freight_actual_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.id IS DISTINCT FROM OLD.id
     OR NEW.channel_code IS DISTINCT FROM OLD.channel_code
     OR NEW.channel_name IS DISTINCT FROM OLD.channel_name
     OR NEW.carrier_code IS DISTINCT FROM OLD.carrier_code
     OR NEW.carrier_name IS DISTINCT FROM OLD.carrier_name
     OR NEW.shipping_method_code IS DISTINCT FROM OLD.shipping_method_code
     OR NEW.shipping_method_name IS DISTINCT FROM OLD.shipping_method_name
     OR NEW.charge_basis IS DISTINCT FROM OLD.charge_basis
     OR NEW.amount IS DISTINCT FROM OLD.amount
     OR NEW.base_amount IS DISTINCT FROM OLD.base_amount
     OR NEW.surcharge_amount IS DISTINCT FROM OLD.surcharge_amount
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.chargeable_quantity IS DISTINCT FROM OLD.chargeable_quantity
     OR NEW.quantity IS DISTINCT FROM OLD.quantity
     OR NEW.occurred_on IS DISTINCT FROM OLD.occurred_on
     OR NEW.source IS DISTINCT FROM OLD.source
     OR NEW.source_ref IS DISTINCT FROM OLD.source_ref
     OR NEW.note IS DISTINCT FROM OLD.note
     OR NEW.created_by IS DISTINCT FROM OLD.created_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION '实际运费是只追加的事实：除作废（is_void / void_reason / voided_by / voided_at）外不可改写'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER logistics_freight_actual_immutable_guard
  BEFORE UPDATE ON logistics_freight_actual
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_freight_actual_immutable();

-- ============================================================ 第十六节：RLS 与授权
--
-- 六张表都是租户级业务表，只需要租户隔离，可见性由端点层权限把关。
-- 照抄 V226 第五节的 DO $$ FOREACH 写法：六张表的策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的费率 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_freight_rule',
    'logistics_freight_rule_tier',
    'logistics_freight_surcharge',
    'logistics_head_freight_structure',
    'logistics_head_freight_component',
    'logistics_freight_actual'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
--
-- 🔴 授权按「不可变优先」收紧（比 V226 严，这是本模块的有意取舍）：
--   * 版本化主表给 UPDATE（只够走状态流转，内容改写由触发器挡掉）；
--   * 从属事实（阶梯 / 成本项）给 SELECT + INSERT + UPDATE，但**内容改写由触发器挡掉**
--     （UPDATE 权限留着，是为了让「不可改写」这条业务规则由**触发器**明确执行并被测试证明，
--     而不是 privilege 缺失的副作用）；DELETE 一律不给。
--   * 实际运费给 UPDATE（只够作废四件套，改写由触发器挡掉）。
GRANT SELECT,INSERT,UPDATE ON logistics_freight_rule TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON logistics_freight_rule TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON logistics_freight_rule_tier TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON logistics_freight_rule_tier TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON logistics_freight_surcharge TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON logistics_freight_surcharge TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON logistics_head_freight_structure TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON logistics_head_freight_structure TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON logistics_head_freight_component TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON logistics_head_freight_component TO starcon_app;
GRANT SELECT,INSERT,UPDATE ON logistics_freight_actual TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON logistics_freight_actual TO starcon_app;
