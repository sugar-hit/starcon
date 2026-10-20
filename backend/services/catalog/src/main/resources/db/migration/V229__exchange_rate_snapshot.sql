-- 5.2.2.2 汇率快照：在单据/定价生效时**冻结**所用汇率，保留换算过程（原价、汇率、舍入方式、目标币金额），
-- 保证历史利润可复算。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：在单据/定价生效时冻结所用汇率，保留换算过程（原价、汇率、舍入方式、目标币金额），保证历史利润可复算。
--   **输入**：定价/单据生成时的汇率；舍入规则配置。
--   **输出**：汇率快照记录；利润复算能力。
--   **关系**：上游 = 5.2.2.1；下游 = 09 订单、12 财务、13 利润。**汇率快照是利润可追溯的前提**：
--             今天汇率变了不能让上个月的利润报表数字跟着变。
--
-- ============================================================ 第一节：这张表归谁
--
-- 上游 5.2.2.1（V225）把**汇率事实**放在 catalog 服务里；本迁移是那张表的**下游**，
-- 同样落在 catalog（卷 05「定价与价目」的主责服务），**不跨服务读写、不建跨库 FK**。
--
-- **🔴 本迁移纯新建、零 ALTER 任何既有表**：不碰 V225 的 exchange_rate / exchange_rate_source，
-- 也不碰任何别的模块的表 —— 上一批有车道在迁移里给既有表加外键，打破了同服务既有测试的 TRUNCATE。
-- 本迁移对既有数据的唯一依赖是**读** exchange_rate（软引用，校验在触发器里）。
--
-- ============================================================ 第二节：🔴 快照 = 冻结 = 只追加
--
-- 蓝图加粗句：**今天汇率变了不能让上个月的利润报表数字跟着变**。
--
-- 这一句在**两个层面**落地，缺一个都不算数：
--
--   1. **汇率的值被复制进快照行**（rate / rate_base_currency / rate_quote_currency /
--      rate_effective_date / rate_source_code 全部冗余冻结）。
--      ⇒ 事后汇率表改了、汇率来源被停用了，历史快照上的数字**一个都不动**。
--      快照不 JOIN 汇率表取值，读快照不依赖汇率表当前的任何一行。
--
--   2. **快照行本身不可改写**（append-only）：
--      * GRANT 里**不发 UPDATE / DELETE**（与 V225 的 exchange_rate_fetch_log 同一手法）；
--      * 再加一道 `guard_exchange_rate_snapshot_immutable` 触发器挡住 UPDATE ——
--        万一将来有人扩了 GRANT，这条触发器仍然把「改写历史」这条路堵死。
--
-- ⇒ 「更正一次冻结」在本仓**不是 UPDATE**：同一 单据行 + 同一 币种对 只能有一条快照
--   （UNIQUE 约束），要更正就换 line_key（新的版本行号）另冻一条，两条都在，历史不被抹掉。
--
-- ============================================================ 第三节：🔴 方向必须显式，而且冻结的是**哪一条**汇率
--
-- 5.2.2.1 的加粗句「汇率方向必须显式」在本模块继续成立，而且要求更高一层：
-- 快照必须说清楚**用的是汇率表里的哪一条**、**方向朝哪边**。
--
-- 所以快照行同时记三组事实：
--
--   rate_id                 —— 指向 exchange_rate.id（**软引用**，校验在触发器里，见第五节）
--   rate_base_currency      —— 冻结的正向基准币（1 rate_base = rate rate_quote）
--   rate_quote_currency     —— 冻结的正向报价币
--   rate                    —— 冻结的汇率值（numeric(19,8)）
--   conversion_direction    —— 原价站在这条汇率的哪一侧：
--                               BASE_TO_QUOTE：原价是基准币，目标币 = 报价币（乘）
--                               QUOTE_TO_BASE：原价是报价币，目标币 = 基准币（除）
--   source_currency / target_currency —— 实际换算的两侧
--
-- ⇒ 「1 USD = 7.1234 CNY 这条汇率，被用来把 100 CNY 换成 USD」这件事，
--   在数据上是**五个字段的组合**而不是一句口头约定；少任何一个都写不进来（CHECK 约束）。
--
-- **🔴 反向汇率仍然只能推导、不能手工再录一条**（V225 第二节的纪律在本模块继续生效）：
-- conversion_direction='QUOTE_TO_BASE' 时用的是 1/rate，
-- 这个除法由**服务层**按冻结的 rate 现算，算出来的目标币金额与 rate 一起冻结进快照。
-- 本迁移**不**在库里再存一条方向相反的汇率。
--
-- ============================================================ 第四节：🔴 换算过程**整行冻结**，复算不依赖任何别的表
--
-- 蓝图要求「保留换算过程（原价、汇率、舍入方式、目标币金额）」——
-- 这四项必须**落在同一行**里，因为「利润复算」的能力就是：
-- 拿着这一行自己再算一遍，看能不能算出同一个目标币金额。
--
--   source_amount    —— 原价（numeric(19,6)，**禁浮点**）
--   rate             —— 汇率（numeric(19,8)）
--   rounding_mode    —— 舍入方式（HALF_UP / HALF_DOWN / DOWN / UP / CEILING / FLOOR）
--   rounding_scale   —— 目标币金额保留的小数位
--   target_amount    —— 目标币金额（numeric(19,6)）
--   rounding_rule_code —— 用的是哪条舍入规则（配置的名字）
--
-- ⇒ 复算时**不需要**回查舍入规则表当前长什么样：
--   规则改了（scale 从 2 改成 3）也不影响任何历史快照 —— 快照里冻的是当时的 mode 与 scale。
--   这正是「分摊规则变更会导致历史利润变化」那条教训在汇率侧的对应物：
--   **配置可改，历史不可改**。
--
-- **🔴 舍入规则表是配置，快照表是事实**：规则表给 UPDATE/DELETE（配置本来就要能改），
-- 快照表只给 SELECT/INSERT（事实不能改写）。两张表的授权刻意不同。
--
-- ============================================================ 第五节：引用校验用触发器，不用外键
--
-- exchange_rate_snapshot.rate_id → exchange_rate.id
--
-- 用**触发器**而不是 FOREIGN KEY 的理由（与 V225 第四节、V224 同款取舍）：
--   * 引用校验的失败原因要可解释（「汇率记录不存在」「冻结的汇率与汇率记录不一致」…），
--     外键只会给一条语焉不详的数据库报错；
--   * 上一批 batch-37 有车道在迁移里给既有表加外键，打破了同服务既有测试的 TRUNCATE ⇒ 39 例 Errors。
--     本迁移**纯新建、零 ALTER 任何既有表**，但仍按「优先触发器」的纪律走。
--
-- 触发器 `guard_exchange_rate_snapshot_rate` 逐项核对冻结值与汇率记录是否一致：
--   * rate / rate_base_currency / rate_quote_currency / rate_effective_date / rate_source_code
--     必须与被引用的那一行**逐字相同**；
--   * conversion_direction 决定的 (source_currency, target_currency) 必须正好是
--     (rate_base, rate_quote) 或 (rate_quote, rate_base)。
--
-- ⇒ 「快照上写着一个汇率表里根本不存在的汇率」这种行**写不进来**。
--   这比服务层校验强：直连 SQL 也绕不过去。
--
-- ============================================================ 第六节：舍入规则配置的取值域
--
-- rounding_mode 的六个取值与 java.math.RoundingMode 逐字对应（服务层直接映射，不另造词表）：
--   HALF_UP 四舍五入 / HALF_DOWN 五舍六入 / DOWN 截断 / UP 进位 / CEILING 向上取整 / FLOOR 向下取整。
--
-- rounding_scale ∈ [0,6]：金额列是 numeric(19,6)，保留超过 6 位小数存不下。
--
-- 规则的**适用币种**（target_currency）可空：
--   * 有值 = 只对这一个目标币种生效（如 JPY 零位小数）；
--   * 为空 = 通用兜底规则。
-- 部分唯一索引保证**一个租户最多一条通用规则**（否则「这条单据该用哪条通用规则」有多个答案）。
--
-- **🔴 解析顺序是「先具体后通用」两步**，不是按 priority 一把抓：
--   先找 target_currency = 目标币种 的规则（同组内按 priority 升序）；
--   找不到再找 target_currency IS NULL 的通用规则。
--   否则一条 priority 更高的通用规则会把针对 JPY 的零位规则盖掉 ——
--   日元按两位小数入账是真实存在的账务事故。
--
-- ============================================================ 第七节：RLS 与授权
--
-- 两张表都是租户级业务表（快照是租户自己的定价事实）：
-- tenant_id uuid NOT NULL + ENABLE ROW LEVEL SECURITY + FORCE ROW LEVEL SECURITY
-- + isolation 策略（照抄 V225 第五节的写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的快照。
--
-- 本模块**没有** data_scope 策略：快照不挂 SKU / 法人，可见性只到租户级。

-- ============================================================ 第一节表：exchange_rate_rounding_rule —— 舍入规则配置
--
-- 蓝图输入写的是「舍入规则配置」—— 所以舍入方式是**租户级配置**而不是代码里的常量：
-- 同一个租户里，结算口径（两位小数）与日元口径（零位小数）可以并存。

CREATE TABLE exchange_rate_rounding_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 机器码：租户内的规则标识（例：DEFAULT / JPY / CNY_SETTLEMENT）。
  -- 必须是稳定标识符：快照按 rounding_rule_code 引用它，改 code 等于改历史事实的指向。
  code varchar(32) NOT NULL CHECK(code ~ '^[A-Z0-9_]{2,32}$'),

  -- 显示名：页面上展示的规则名称。
  name text NOT NULL CHECK(btrim(name) <> ''),

  -- 适用目标币种：为��� = 通用兜底规则（任何目标币种都能用它）。
  target_currency varchar(3) CHECK(target_currency IS NULL OR target_currency ~ '^[A-Z]{3}$'),

  -- 舍入方式：与 java.math.RoundingMode 逐字对应（服务层直接映射，不另造词表）。
  rounding_mode text NOT NULL
    CHECK(rounding_mode IN ('HALF_UP','HALF_DOWN','DOWN','UP','CEILING','FLOOR')),

  -- 目标币金额保留的小数位：金额列是 numeric(19,6)，保留超过 6 位存不下。
  rounding_scale int NOT NULL CHECK(rounding_scale BETWEEN 0 AND 6),

  -- 停用的规则不再参与解析，但**已冻结的快照照常可读**（停用是「不许再新建」，不是「抹掉过去」）。
  enabled boolean NOT NULL DEFAULT true,

  -- 优先级：数字小的排前面。**只在同一适用范围内比较**（见第六节：先具体后通用）。
  priority int NOT NULL DEFAULT 100,

  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code)
);

-- 一个租户最多一条通用规则（target_currency IS NULL）：否则「这条单据该用哪条通用规则」有多个答案。
CREATE UNIQUE INDEX exchange_rate_rounding_rule_one_generic
 ON exchange_rate_rounding_rule(tenant_id) WHERE target_currency IS NULL;
-- 「这个目标币种该用哪条规则」的读路径：先按币种找，找不到再找通用兜底。
CREATE INDEX exchange_rate_rounding_rule_by_currency
 ON exchange_rate_rounding_rule(tenant_id, enabled, target_currency, priority, code);

-- ============================================================ 第二节表：exchange_rate_snapshot —— 冻结的汇率快照（只追加）

CREATE TABLE exchange_rate_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单据类型：快照挂在哪张单据上。词表见服务层（PRICE_LIST / PRICE_LIST_ITEM /
  -- PRICING_TEMPLATE / PROFIT_CALCULATION / ORDER / SETTLEMENT）。
  -- 刻意**不**建外键：09 订单、12 财务的单据不在本库（模块边界纪律），只能用逻辑引用。
  document_type text NOT NULL CHECK(document_type IN
    ('PRICE_LIST','PRICE_LIST_ITEM','PRICING_TEMPLATE','PROFIT_CALCULATION','ORDER','SETTLEMENT')),
  document_id uuid NOT NULL,

  -- 单据号：给人看的定位信息（页面上按它筛）。不唯一 —— 不同租户可以有同一个号。
  document_no text NOT NULL DEFAULT '',

  -- 单据内的行标识：同一张单据里同一个币种对可能有多行（订单的多个 SKU 行），
  -- 用它区分。留空表示「整单一条」。
  line_key text NOT NULL DEFAULT '',

  -- 备注：这条快照为什么冻（比如「平台结算汇率已按结算单确认」）。
  note text NOT NULL DEFAULT '',

  -- ---------------------------------------------------------------- 换算过程（整行冻结）
  --
  -- 原价与它的币种。
  source_currency varchar(3) NOT NULL CHECK(source_currency ~ '^[A-Z]{3}$'),
  source_amount numeric(19,6) NOT NULL,

  -- 目标币与换算后的金额。
  target_currency varchar(3) NOT NULL CHECK(target_currency ~ '^[A-Z]{3}$'),
  target_amount numeric(19,6) NOT NULL,

  -- ---------------------------------------------------------------- 冻结的汇率事实
  --
  -- 被冻结的那条 exchange_rate（**软引用**，校验在触发器里，见第五节）。
  -- NOT NULL：快照只能冻结**已登记**的汇率事实 —— 想冻结一条没登记的汇率，
  -- 先在 5.2.2.1 登记它，这样每一条快照的汇率都有出处。
  rate_id uuid NOT NULL,

  -- 冻结的正向方向与汇率值（冗余复制，不 JOIN 取值：汇率表改了也不影响历史快照）。
  rate_base_currency varchar(3) NOT NULL CHECK(rate_base_currency ~ '^[A-Z]{3}$'),
  rate_quote_currency varchar(3) NOT NULL CHECK(rate_quote_currency ~ '^[A-Z]{3}$'),
  rate numeric(19,8) NOT NULL CHECK(rate > 0),

  -- 原价站在这条汇率的哪一侧：BASE_TO_QUOTE = 乘（原价是基准币），QUOTE_TO_BASE = 除（原价是报价币）。
  conversion_direction text NOT NULL
    CHECK(conversion_direction IN ('BASE_TO_QUOTE','QUOTE_TO_BASE')),

  -- 冻结的汇率生效日与来源（冗余复制，理由同上）。
  rate_effective_date date NOT NULL,
  rate_source_code varchar(32) NOT NULL,

  -- ---------------------------------------------------------------- 冻结的舍入方式
  --
  -- 用的哪条规则 + 当时的模式与位数：**三者一起冻结**，改规则不动历史。
  rounding_rule_code varchar(32) NOT NULL,
  rounding_mode text NOT NULL
    CHECK(rounding_mode IN ('HALF_UP','HALF_DOWN','DOWN','UP','CEILING','FLOOR')),
  rounding_scale int NOT NULL CHECK(rounding_scale BETWEEN 0 AND 6),

  -- 冻结时刻：这一条快照是什么时候冻的（timestamptz）。
  frozen_at timestamptz NOT NULL,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 🔴 同一 单据行 + 同一 币种对 只能有一条快照。
  --    否则「这张单据这一行用的哪个汇率」有多个答案 —— 而快照不可改写，
  --    也就没法靠 UPDATE 收敛。要更正就换 line_key 另冻一条（两条都在，历史不被抹掉）。
  UNIQUE(tenant_id, document_type, document_id, line_key, source_currency, target_currency),

  -- 本币对本币不需要换算。
  CHECK(source_currency <> target_currency),

  -- 🔴 方向必须自洽：换算的两侧必须正好是这条汇率的两侧（顺序由 conversion_direction 决定）。
  --    少了这两条 CHECK，「1 USD = 7.1234 CNY 的汇率被用来把 100 CNY 换成 USD」这种错配写得进去。
  CHECK(source_currency = CASE WHEN conversion_direction = 'BASE_TO_QUOTE'
      THEN rate_base_currency ELSE rate_quote_currency END),
  CHECK(target_currency = CASE WHEN conversion_direction = 'BASE_TO_QUOTE'
      THEN rate_quote_currency ELSE rate_base_currency END)
);

-- 「这张单据上冻结了哪些汇率」的读路径（复算与页面都按它查）。
CREATE INDEX exchange_rate_snapshot_by_document
 ON exchange_rate_snapshot(tenant_id, document_type, document_id, line_key);
-- 「按币种对看历史换算」。
CREATE INDEX exchange_rate_snapshot_by_pair
 ON exchange_rate_snapshot(tenant_id, source_currency, target_currency, frozen_at DESC);
-- 「按汇率生效日倒序翻台账」（今天汇率变了没有？答案在这条索引上）。
CREATE INDEX exchange_rate_snapshot_by_rate_date
 ON exchange_rate_snapshot(tenant_id, rate_effective_date DESC, id DESC);
-- 「按冻结时刻倒序翻台账」（最近冻了哪些）。
CREATE INDEX exchange_rate_snapshot_by_frozen
 ON exchange_rate_snapshot(tenant_id, frozen_at DESC, id DESC);
-- 「某个汇率来源冻过哪些快照」。
CREATE INDEX exchange_rate_snapshot_by_source_code
 ON exchange_rate_snapshot(tenant_id, rate_source_code, frozen_at DESC);
-- 「这条汇率被哪些快照引用了」。
CREATE INDEX exchange_rate_snapshot_by_rate
 ON exchange_rate_snapshot(tenant_id, rate_id) WHERE rate_id IS NOT NULL;

-- 🔴 冻结值必须与被引用的汇率记录逐字一致（第五节）。
CREATE FUNCTION guard_exchange_rate_snapshot_rate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  r_base varchar(3); r_quote varchar(3); r_rate numeric(19,8);
  r_date date; r_source varchar(32);
BEGIN
  SELECT e.base_currency, e.quote_currency, e.rate, e.effective_date, e.source_code
    INTO r_base, r_quote, r_rate, r_date, r_source
    FROM exchange_rate e
   WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.rate_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '汇率记录不存在（rate_id=%），不能冻结一条查不到的汇率；请先在 5.2.2.1 登记该汇率',
      NEW.rate_id USING ERRCODE = '23514';
  END IF;
  IF NEW.rate <> r_rate THEN
    RAISE EXCEPTION '冻结的汇率与汇率记录不一致：快照 % ，汇率记录 %。快照冻结的必须是那一条汇率的真实值',
      NEW.rate, r_rate USING ERRCODE = '23514';
  END IF;
  IF NEW.rate_base_currency <> r_base OR NEW.rate_quote_currency <> r_quote THEN
    RAISE EXCEPTION '冻结的汇率方向与汇率记录不一致：快照是 1 % = ? % ，汇率记录是 1 % = ? %',
      NEW.rate_base_currency, NEW.rate_quote_currency, r_base, r_quote USING ERRCODE = '23514';
  END IF;
  IF NEW.rate_effective_date <> r_date THEN
    RAISE EXCEPTION '冻结的汇率生效日与汇率记录不一致：快照 % ，汇率记录 %',
      NEW.rate_effective_date, r_date USING ERRCODE = '23514';
  END IF;
  IF NEW.rate_source_code <> r_source THEN
    RAISE EXCEPTION '冻结的汇率来源与汇率记录不一致：快照 % ，汇率记录 %',
      NEW.rate_source_code, r_source USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_exchange_rate_snapshot_rate BEFORE INSERT OR UPDATE ON exchange_rate_snapshot
 FOR EACH ROW EXECUTE FUNCTION guard_exchange_rate_snapshot_rate();

-- 🔴 快照不可改写：冻结的东西不许改（UPDATE 一律拒绝）。
--    GRANT 里本来就不给 UPDATE/DELETE（只追加），这道触发器是**第二道**：
--    万一将来有人扩了 GRANT，「改写历史快照」这条路仍然堵死。
CREATE FUNCTION guard_exchange_rate_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '汇率快照是冻结的历史事实，不允许修改。要更正请换 line_key 另冻一条（原快照原样保留）'
    USING ERRCODE = '23514';
END $$;
CREATE TRIGGER guard_exchange_rate_snapshot_immutable BEFORE UPDATE ON exchange_rate_snapshot
 FOR EACH ROW EXECUTE FUNCTION guard_exchange_rate_snapshot_immutable();

DO $$ DECLARE tbl text; BEGIN
  -- 两张表都是租户级业务表：tenant_id NOT NULL + ENABLE/FORCE RLS + isolation 策略。
  -- 写法与 V225 第五节逐字相同。
  FOREACH tbl IN ARRAY ARRAY['exchange_rate_rounding_rule','exchange_rate_snapshot'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
-- 舍入规则是**配置**：可改可删（改了也不影响任何已冻结的快照 —— 快照里冻的是当时的 mode 与 scale）。
GRANT SELECT,INSERT,UPDATE,DELETE ON exchange_rate_rounding_rule TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON exchange_rate_rounding_rule TO starcon_app;
-- 快照是**事实**：只追加。GRANT 里不发 UPDATE/DELETE（与 V225 的拉取日志同一手法），
-- 再加一道 UPDATE 触发器兜底。
GRANT SELECT,INSERT ON exchange_rate_snapshot TO catalog_app;
GRANT SELECT,INSERT ON exchange_rate_snapshot TO starcon_app;
