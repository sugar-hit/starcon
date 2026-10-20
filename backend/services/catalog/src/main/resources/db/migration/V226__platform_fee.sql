-- 5.2.3.1 平台费用：平台侧费用的计算规则（佣金比例或阶梯、订阅费分摊、Referral Fee、
-- 交易费、罚款）与实际费用事实，两者并列成两个独立事实，差额即「费用差异」。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：维护平台侧费用的计算规则：佣金比例或阶梯、订阅费分摊、Referral Fee、交易费、
--             罚款类型，用于利润测算的扣项。
--   **输入**：平台费用规则配置（按平台/站点/类目）；实际费用的历史值用于校准。
--   **输出**：费用规则；测算时的费用项。
--   **关系**：上游 = 2 平台目录；下游 = 5.3.1.1 测算、13 利润、14 广告。
--             **规则参数用于事前测算，实际费用来自 12 的结算对账**——两者差额要在利润报表中
--             显性展示为"费用差异"，否则测算永远不准却不知道为什么。
--
-- ============================================================ 第一节：本模块的两个事实，与它们各自的真实来源
--
-- 蓝图那句加粗里藏着**两个口径**，本迁移把它们拆成**两张互不相干的表**，
-- 因为它们回答的是两个不同的问题：
--
--   | 事实                | 回答的问题                       | 本仓真实来源                          |
--   |---------------------|----------------------------------|---------------------------------------|
--   | 规则（事前测算口径） | 「这笔单按规则应该扣多少」        | 本服务自持（租户维护的参数）           |
--   | 实际费用（事后对账） | 「平台实际扣了多少」              | ⚠️ 12 财务未交付 ⇒ 当前只有人工登记  |
--
-- 🔴 **两条不可让步的纪律**：
--
--   1. **不能合并成一个字段**。把「规则算出来的数」和「平台实收的数」写进同一列，
--      就再也答不出「这个月是规则定错了，还是平台多扣了」——
--      而这正是加粗那句要解决的事。合并之后差异恒等于 0，
--      页面看起来「测算永远准」，实际是差异根本无处可藏。
--   2. **差异不能恒等于 0 冒充「没有差异」**。没有实际费用时，
--      差异是**算不出来**（available=false + 原因），不是 0。
--
-- 「实际费用来自 12 的结算对账」这一条：**12 财务（finance 服务）在集成头里不存在**
-- （backend/services 下只有 identity / catalog / channel / warehouse / trade / crm）。
-- 按仓库既有先例（10.7.1.3 弃置与销毁 V221 的做法），本迁移**显式声明这个缺口**，
-- 不假装下游已交付：能力声明里 `actual_fee_reconciliation` 维度标成
-- available=false + ownerModule=12 + 一句 impact。
--
-- ⚠️ **那么 `platform_fee_actual` 里的数从哪来？** —— 只有一种合法来源：
-- **人工登记的平台账单观察值**（source='MANUAL_OBSERVATION'）。
-- 它是蓝图输入句「**实际费用的历史值用于校准**」的直接落地：
-- 运营从平台后台的账单里读到「这单被扣了 3.72 美元佣金」，登进来。
-- 本迁移**绝不**把它标成 12 的对账结果（source 的 CHECK 里根本没有第二个取值，
-- 也没有第二个写入所有者），也**不编**一个假的「历史实际费用」数据源。
--
-- ============================================================ 第二节：规则挂在「平台 / 站点 / 类目」三个维度上
--
-- 蓝图输入句：「平台费用规则配置（按平台/站点/类目）」。
-- 三个维度的编码与本仓既有的 `platform_category_mapping`（V145，4.2.3.2 已交付）**逐字同形**：
--
--   * **平台** `platform_code` —— 与 channel 服务 `platform.code` 同一形态
--     （varchar(32)，ASCII 大写字母数字与短横线）。
--     **不建跨服务外键**：channel 的 platform / marketplace 是**全局码表**，
--     AGENTS.md 禁止跨服务 FK / 跨库访问。平台与站点码由服务层经
--     **授权只读 HTTP 端点**向 channel 取数校验（见 `PlatformFeePort`）。
--   * **站点** `site_code` —— 与 V145 同名同形态，NULL = 该平台的**全部站点**。
--   * **类目** —— 与 V145 同构，两个可空列：
--       `internal_category_id`（内部分类，必须来自 tree_kind='INTERNAL' 的树）、
--       `platform_category_id`（平台类目，必须来自 tree_kind='PLATFORM' 且其树
--       的 platform_code = 本行的 platform_code 的树）。
--     两者都**可空**：NULL = 该维度不限定。
--
-- 🔴 **为什么类目用触发器校验而不是外键**（仓库既有先例，逐条抄自
-- V80 / V84 / V221 的注释）：**给既有表建外键会把同服务既有测试的 TRUNCATE 打断**。
-- `product_category` 是既有表，任何一个既有测试的 `truncate` 语句不带本表，
-- 建了 FK 就会报 23503「cannot truncate a table referenced in a foreign key constraint」，
-- 整批测试变红。所以本迁移**零个外键**，引用校验一律用触发器
-- （与 V145 的 `guard_platform_category_mapping_scope` 同一手法）。
--
-- ============================================================ 第三节：计价形态与「费率必须是精确值」
--
-- 四种计价形态，由 `charge_basis` 表达，**互斥**（CHECK 钉死，两个都填时
-- 「这条费率到底怎么算」没有答案，服务层只会安静地挑一个用）：
--
--   | charge_basis        | 含义               | 用的列                    |
--   |---------------------|--------------------|---------------------------|
--   | PERCENTAGE_OF_SALE  | 销售额的一个比例   | `rate`                    |
--   | FIXED_PER_ORDER     | 每单一个固定额     | `fixed_amount`            |
--   | FIXED_PER_UNIT      | 每件一个固定额     | `fixed_amount`            |
--   | TIERED_PERCENTAGE   | 按金额分档的比例   | `platform_fee_rule_tier`  |
--
-- 🔴 **`rate` 是 numeric(19,8)，不是 float / double**（AGENTS.md：费率/比例也必须是精确值）。
-- 口径固定为**小数**：`rate = 0.15000000` 表示 15%。用百分数存（15 表示 15%）会让
-- 「0.15%」和「15%」在同一个列里差 100 倍，而页面上看不出任何异常。
-- 8 位小数 = 0.00000001% 的分辨率，对平台费率绰绰有余。
--
-- 🔴 **`rate` 的取值域是 [0, 1]**（DB CHECK）：费率大于 100% 不存在，
-- 而一个误填成 `15` 的 15% 会被 [0,1] 当场拒掉，而不是变成 1500% 的扣项。
--
-- ============================================================ 第四节：订阅费分摊 —— 「分摊」是真的算出来的
--
-- 蓝图职责句点名「**订阅费分摊**」。订阅费不是一个比例，而是一笔**周期总额**
-- （例如月度订阅 39.99 美元），要摊到每一单 / 每一件上。
--
-- 本迁移把它建成**三个显式列**，而不是一个已经摊好的数字：
--
--   * `period_total_amount` —— 订阅费的周期总额（必须 > 0）
--   * `allocation_basis`   —— 分摊口径：PER_ORDER（摊到每单）/ PER_UNIT（摊到每件）
--   * `allocation_divisor` —— 分摊基数：期间的分摊单量 / 件量（必须 > 0）
--
-- ⇒ 每单分摊额 = `period_total_amount / allocation_divisor`。
-- **分摊基数必须显式登记**，不许服务层用一个魔法常数（"按 100 单摊"）
-- 或干脆取当前订单数（那是**当下的**订单数，不是**期间**的）——
-- 后者会让同一个月的分摊额随着新订单进来而变，历史利润就没有解释力了。
--
-- ============================================================ 第五节：阶梯（tier）
--
-- 「佣金比例**或阶梯**」里的阶梯是**独立的一张表**而不是规则上的一个 JSON 列：
-- 阶梯要能被查询（「这个平台从多少金额开始涨到 15%」）、能被逐档核对、
-- 也要能在整体替换时保证「同一次请求里要么全换要么全不换」（服务层同事务删+插）。
--
-- 阶梯的行是**规则的从属事实**：删规则时阶梯一并消失（触发器级联），
-- 但**不建外键**（理由见第二节）。
--
-- 阶梯的选档口径（下界闭、上界开）：
--   `min_amount <= 销售额 < max_amount` 命中该档；`max_amount IS NULL` = 该档以上无上限。
--   销售额低于最低档下界 ⇒ **该规则不适用**（不是一个 0 费用项），
--   页面要显示「不适用」而不是「扣 0 元」——后者会让人以为平台真的没收钱。
--
-- ============================================================ 第六节：实际费用 —— 独立的事实，只追加
--
-- `platform_fee_actual` 一行 = **一笔费用发生**（一次扣费 / 一笔罚款 / 一笔冲回）。
--
-- 🔴 **它必须带「销售额基数」与「数量基数」**（`sale_amount` / `quantity`，均 NOT NULL）：
-- 没有基数就**算不出差异**（比例规则需要销售额才能重算预期值，
-- 按件规则需要件数才能重算预期值）。所以基数是必填，不是可选。
--
-- 🔴 **只追加 + 作废留理由**：录错的行作废（is_void + void_reason 必填）而不是删除，
-- 「这个月的平台费被改过几次、每次改了什么」必须可追溯。
-- 触发器钉死：除了作废四件套，其余列一律不可改写。
--
-- `amount` **允许为 0**：平台这笔确实没收钱（0）是一个真实的观察值，
-- 而它与「规则算出应该扣 3.72」之间的差异恰恰是最有信息量的那一类。
-- 也允许为负（退款 / 冲回）。
--
-- ============================================================ 第七节：RLS 与授权
--
-- 三张表都是租户级业务表（平台费率与平台费都是租户自己的经营事实）：
-- `tenant_id uuid NOT NULL` + `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`
-- + isolation 策略（照抄 V32 / V215 第八节的 `DO $$ FOREACH` 写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的费率，
-- 而平台费率直接决定别人的利润与报价（能看出竞争者在某个平台给多少佣金）。
--
-- ============================================================ 第一节表：platform_fee_rule —— 费用规则（可改，乐观锁）

CREATE TABLE platform_fee_rule (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- ===== 三个挂载维度（第二节）=====
  platform_code varchar(32) NOT NULL,
  site_code varchar(32),
  internal_category_id uuid,
  platform_category_id uuid,

  -- 费用类型：佣金 / Referral Fee / 交易费 / 订阅费 / 罚款 / 其它。
  -- 枚举**只增不改**：它会进历史快照与实际费用的口径对照，改语义会让历史读起来是错的。
  fee_type text NOT NULL
    CHECK(fee_type IN ('COMMISSION','REFERRAL','TRANSACTION','SUBSCRIPTION','PENALTY','OTHER')),

  -- 计价形态（第三节）。四种互斥。
  charge_basis text NOT NULL
    CHECK(charge_basis IN ('PERCENTAGE_OF_SALE','FIXED_PER_ORDER','FIXED_PER_UNIT','TIERED_PERCENTAGE')),

  -- 费率：numeric(19,8) 的**小数**口径（0.15 = 15%）。见第三节。
  rate numeric(19,8),
  -- 固定额：每单 / 每件一个定值。
  fixed_amount numeric(19,4),
  -- 固定额的币种（比例形态不需要本列：比例费与销售额同币种）。
  currency varchar(3),

  -- ===== 订阅费分摊（第四节）=====
  period_total_amount numeric(19,4),
  period_currency varchar(3),
  allocation_basis text,
  allocation_divisor numeric(19,6),

  -- 生效区间：从哪一刻开始算数 / 到哪一刻为止（NULL = 长期有效）。
  -- **timestamptz**：平台费率常常在某个具体时刻切换（改版、涨价），
  -- 只到日会让「今天上午算的利润」与「今天下午算的利润」口径不明。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  enabled boolean NOT NULL DEFAULT true,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 平台 / 站点码的形态与 V145 的 platform_category_mapping 逐字一致。
  CHECK(platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(site_code IS NULL OR site_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  CHECK(period_currency IS NULL OR period_currency ~ '^[A-Z]{3}$'),

  -- 计价参数互斥（第三节）。CASE 写法而不是四个 OR 分支：
  -- 四个 OR 会让「某个分支的其它列没被约束」变成可能（比如 PERCENTAGE 形态下
  -- fixed_amount 非空恰好落进别的分支），CASE 把形态与列的对应关系写死在一处。
  CHECK(CASE
    WHEN fee_type = 'SUBSCRIPTION'
      THEN charge_basis IN ('FIXED_PER_ORDER','FIXED_PER_UNIT')
       AND rate IS NULL AND fixed_amount IS NULL
    WHEN charge_basis = 'PERCENTAGE_OF_SALE'
      THEN rate IS NOT NULL AND fixed_amount IS NULL
    WHEN charge_basis = 'TIERED_PERCENTAGE'
      THEN rate IS NULL AND fixed_amount IS NULL
    ELSE rate IS NULL AND fixed_amount IS NOT NULL
  END),

  -- 费率的取值域 [0,1]（小数口径）。误填成 15（当成 15%）当场被拒。
  CHECK(rate IS NULL OR (rate >= 0 AND rate <= 1)),
  CHECK(fixed_amount IS NULL OR fixed_amount >= 0),

  -- 币种的必要性：固定额形态必须有币种（否则「3.5」是美元还是欧元？）；
  -- 比例形态没有币种（比例费与销售额同币种）。
  CHECK(CASE
    WHEN fee_type = 'SUBSCRIPTION' THEN period_currency IS NOT NULL AND currency IS NULL
    WHEN charge_basis IN ('FIXED_PER_ORDER','FIXED_PER_UNIT') THEN currency IS NOT NULL
    ELSE currency IS NULL
  END),

  -- 订阅费的三个分摊列必须**齐备且成对出现**（第四节）。
  CHECK(CASE
    WHEN fee_type = 'SUBSCRIPTION'
      THEN period_total_amount IS NOT NULL AND period_total_amount > 0
       AND allocation_basis IN ('PER_ORDER','PER_UNIT')
       AND allocation_divisor IS NOT NULL AND allocation_divisor > 0
    ELSE period_total_amount IS NULL AND period_currency IS NULL
       AND allocation_basis IS NULL AND allocation_divisor IS NULL
  END)
);

-- 同一维度组合下**不得有两条生效区间重叠的规则**：重叠时「用哪一条」没有答案，
-- 而服务层只会安静地挑一条（挑哪条取决于查询顺序，不可复现）。
--
-- ⚠️ 这里用**普通唯一索引**而不是 EXCLUDE USING gist：后者要 CREATE EXTENSION btree_gist
-- （一条持续的数据库级变更），而本仓没有任何迁移用过它 —— 不为一个约束引入新的
-- 扩展依赖。区间重叠由**服务层**在建/改规则时检查并 409 拒绝
-- （`PlatformFeeService` 的 overlap 查询），DB 层这条唯一索引挡掉「完全重复」。
CREATE UNIQUE INDEX platform_fee_rule_scope_start
  ON platform_fee_rule(tenant_id, platform_code, COALESCE(site_code,''),
                       COALESCE(internal_category_id::text,''),
                       COALESCE(platform_category_id::text,''),
                       fee_type, effective_from);

-- 「某个平台 / 站点上有哪些费率」—— 测算与差异的主查询路径。
CREATE INDEX platform_fee_rule_by_scope
  ON platform_fee_rule(tenant_id, platform_code, site_code, fee_type, effective_from DESC);
-- 「这个类目上有哪些费率」（含子树的查询走 internal_category_id 前缀）。
CREATE INDEX platform_fee_rule_by_category
  ON platform_fee_rule(tenant_id, internal_category_id, platform_category_id, fee_type);
-- 「最近改过的费率」—— 费率治理页的默认排序。
CREATE INDEX platform_fee_rule_by_updated ON platform_fee_rule(tenant_id, updated_at DESC);

-- ============================================================ 第二节表：platform_fee_rule_tier —— 阶梯（第五节）

CREATE TABLE platform_fee_rule_tier (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 所属规则。**不建外键**（第二节的理由），引用完整性由触发器 guard 保证。
  rule_id uuid NOT NULL,
  -- 档位序号（从 1 起）。整体替换时按此顺序写回。
  seq int NOT NULL CHECK(seq > 0),
  -- 档位下界（含）与上界（不含，NULL = 无上限）。
  min_amount numeric(19,4) NOT NULL CHECK(min_amount >= 0),
  max_amount numeric(19,4),
  -- 该档的费率（小数口径，numeric(19,8)）。
  rate numeric(19,8) NOT NULL CHECK(rate >= 0 AND rate <= 1),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一规则下序号唯一。
  UNIQUE(tenant_id, rule_id, seq),
  -- 上界必须严格大于下界（否则是一个空档）。
  CHECK(max_amount IS NULL OR max_amount > min_amount)
);

-- 阶梯查询：取某规则的全部档位（按序号）。
CREATE INDEX platform_fee_rule_tier_by_rule ON platform_fee_rule_tier(tenant_id, rule_id, seq);

-- ============================================================ 第三节表：platform_fee_actual —— 实际费用（第六节，只追加）

CREATE TABLE platform_fee_actual (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 与规则同一套维度（第二节）。实际费用也必须能定位到平台 / 站点 / 类目，
  -- 否则它与规则**对不上**，差异就算不出来。
  platform_code varchar(32) NOT NULL,
  site_code varchar(32),
  internal_category_id uuid,
  platform_category_id uuid,

  fee_type text NOT NULL
    CHECK(fee_type IN ('COMMISSION','REFERRAL','TRANSACTION','SUBSCRIPTION','PENALTY','OTHER')),

  -- 实际发生额。**允许 0**（平台这笔确实没收钱，与「规则算出该扣」之间的差异
  -- 恰恰最有信息量）；**允许负**（退款 / 冲回）。
  amount numeric(19,4) NOT NULL,

  -- 实际金额的币种。跨币种绝不相加（仓库铁律）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ===== 差异计算的两个基数（第六节）=====
  -- 销售额基数：这笔费用对应的销售额（比例规则重算预期值要用）。
  sale_amount numeric(19,4) NOT NULL CHECK(sale_amount >= 0),
  -- 数量基数：这笔费用对应的数量（按件规则与订阅费按件分摊要用）。
  quantity numeric(19,6) NOT NULL CHECK(quantity >= 0),

  -- 费用发生日（结算周期日）。
  occurred_on date NOT NULL,

  -- 来源。**只有人工登记这一种取值**：12 财务的结算对账尚未交付（第一节），
  -- 本模块不编一个假的「历史实际费用」数据源去冒充 12 的对账结果。
  -- 等 12 落地后由**它**的写入端点带自己的来源值进来，那一行会写明它来自对账。
  source text NOT NULL DEFAULT 'MANUAL_OBSERVATION'
    CHECK(source IN ('MANUAL_OBSERVATION')),
  -- 平台账单号 / 结算单号等外部凭据（自由文本，只作追溯用）。
  source_ref text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',

  -- 作废：录错的行作废而不是删除（第六节）。
  is_void boolean NOT NULL DEFAULT false,
  void_reason text NOT NULL DEFAULT '',
  voided_by uuid,
  voided_at timestamptz,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  CHECK(site_code IS NULL OR site_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
  -- 作废必须留理由：没有理由的作废会让「平台费为什么变了」无法解释。
  CHECK(NOT is_void OR btrim(void_reason) <> '')
);

-- 「这个平台 / 站点上实际发生了多少」—— 差异计算的主查询路径。
CREATE INDEX platform_fee_actual_by_scope
  ON platform_fee_actual(tenant_id, platform_code, site_code, fee_type, occurred_on DESC);
-- 「某段时间内的实际费用」—— 差异对照按区间取数。
CREATE INDEX platform_fee_actual_by_period
  ON platform_fee_actual(tenant_id, occurred_on DESC, platform_code, fee_type);

-- ============================================================ 第四节：触发器（引用校验 + 级联 + 只追加）
--
-- 【为什么全部用触发器而不是外键】见第二节：给既有表（product_category）建外键
-- 会把同服务既有测试的 TRUNCATE 打断（23503）。零外键是本仓的既有纪律
-- （V80 / V84 / V221 都为此专门留过注释）。

-- 4.1 类目归属守卫：内部分类必须来自 INTERNAL 树；平台类目必须来自 PLATFORM 树
--     且其树的 platform_code 与本行的 platform_code 一致。
--     与 V145 的 guard_platform_category_mapping_scope 同一手法（同一个守卫逻辑写两遍，
--     因为它们守的是两张不同的表）。
CREATE FUNCTION guard_platform_fee_category_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  internal_kind  text;
  platform_kind  text;
  platform_owner varchar(32);
BEGIN
  IF NEW.internal_category_id IS NOT NULL THEN
    SELECT t.tree_kind INTO internal_kind
      FROM product_category c
      JOIN product_category_tree t ON t.tenant_id = c.tenant_id AND t.id = c.tree_id
     WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.internal_category_id;
    IF internal_kind IS DISTINCT FROM 'INTERNAL' THEN
      RAISE EXCEPTION '费用规则的内部分类必须来自 INTERNAL 分类树，实际是 %',
        coalesce(internal_kind, '（类目不存在）') USING ERRCODE = '23514';
    END IF;
  END IF;

  IF NEW.platform_category_id IS NOT NULL THEN
    SELECT t.tree_kind, t.platform_code INTO platform_kind, platform_owner
      FROM product_category c
      JOIN product_category_tree t ON t.tenant_id = c.tenant_id AND t.id = c.tree_id
     WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.platform_category_id;
    IF platform_kind IS DISTINCT FROM 'PLATFORM' THEN
      RAISE EXCEPTION '费用规则的平台类目必须来自 PLATFORM 分类树，实际是 %',
        coalesce(platform_kind, '（类目不存在）') USING ERRCODE = '23514';
    END IF;
    IF platform_owner IS DISTINCT FROM NEW.platform_code THEN
      RAISE EXCEPTION '该平台类目属于平台 %，与规则声明的平台 % 不一致',
        platform_owner, NEW.platform_code USING ERRCODE = '23514';
    END IF;
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER platform_fee_rule_category_guard
  BEFORE INSERT OR UPDATE ON platform_fee_rule
  FOR EACH ROW EXECUTE FUNCTION guard_platform_fee_category_scope();

CREATE TRIGGER platform_fee_actual_category_guard
  BEFORE INSERT OR UPDATE ON platform_fee_actual
  FOR EACH ROW EXECUTE FUNCTION guard_platform_fee_category_scope();

-- 4.2 阶梯的所属规则必须存在且同租户（替代外键）。
CREATE FUNCTION guard_platform_fee_tier_rule() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  owner_tenant uuid;
BEGIN
  SELECT tenant_id INTO owner_tenant FROM platform_fee_rule
   WHERE id = NEW.rule_id AND tenant_id = NEW.tenant_id;
  IF owner_tenant IS NULL THEN
    RAISE EXCEPTION '阶梯所属的费用规则不存在或不属于本租户：%', NEW.rule_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER platform_fee_tier_rule_guard
  BEFORE INSERT OR UPDATE ON platform_fee_rule_tier
  FOR EACH ROW EXECUTE FUNCTION guard_platform_fee_tier_rule();

-- 4.3 删规则时阶梯一并消失（触发器级联，替代外键的 ON DELETE CASCADE）。
--     阶梯是规则的从属事实：规则没了，阶梯留着就是指向空处的死行，
--     而「这个规则的阶梯长什么样」在页面上会被读成「还有一个规则」。
CREATE FUNCTION cascade_platform_fee_rule_tiers() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  DELETE FROM platform_fee_rule_tier
   WHERE tenant_id = OLD.tenant_id AND rule_id = OLD.id;
  RETURN OLD;
END $$;

CREATE TRIGGER platform_fee_rule_tier_cascade
  AFTER DELETE ON platform_fee_rule
  FOR EACH ROW EXECUTE FUNCTION cascade_platform_fee_rule_tiers();

-- 4.4 实际费用**只追加**：除作废四件套外，其余列一律不可改写。
--     「改一条实际费用」= 让历史事实可以回溯修改 ⇒ 差异的历史就没有意义了。
CREATE FUNCTION guard_platform_fee_actual_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.id IS DISTINCT FROM OLD.id
     OR NEW.platform_code IS DISTINCT FROM OLD.platform_code
     OR NEW.site_code IS DISTINCT FROM OLD.site_code
     OR NEW.internal_category_id IS DISTINCT FROM OLD.internal_category_id
     OR NEW.platform_category_id IS DISTINCT FROM OLD.platform_category_id
     OR NEW.fee_type IS DISTINCT FROM OLD.fee_type
     OR NEW.amount IS DISTINCT FROM OLD.amount
     OR NEW.currency IS DISTINCT FROM OLD.currency
     OR NEW.sale_amount IS DISTINCT FROM OLD.sale_amount
     OR NEW.quantity IS DISTINCT FROM OLD.quantity
     OR NEW.occurred_on IS DISTINCT FROM OLD.occurred_on
     OR NEW.source IS DISTINCT FROM OLD.source
     OR NEW.source_ref IS DISTINCT FROM OLD.source_ref
     OR NEW.note IS DISTINCT FROM OLD.note
     OR NEW.created_by IS DISTINCT FROM OLD.created_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION '实际费用是只追加的事实：除作废（is_void / void_reason / voided_by / voided_at）外不可改写'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER platform_fee_actual_immutable_guard
  BEFORE UPDATE ON platform_fee_actual
  FOR EACH ROW EXECUTE FUNCTION guard_platform_fee_actual_immutable();

-- ============================================================ 第五节：RLS 与授权
--
-- 三张表都是租户级业务表，只需要租户隔离，可见性由端点层权限把关。
-- 照抄 V215 第八节的 DO $$ FOREACH 写法：三张表的策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的费率 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['platform_fee_rule','platform_fee_rule_tier','platform_fee_actual'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_fee_rule TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_fee_rule TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_fee_rule_tier TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_fee_rule_tier TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_fee_actual TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_fee_actual TO starcon_app;
