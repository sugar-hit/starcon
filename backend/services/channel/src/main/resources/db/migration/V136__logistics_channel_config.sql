-- ===========================================================================
-- 10.1.1.2 渠道配置（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.1.1.2 渠道配置
--   - 职责：配置具体物流渠道：所属承运商、渠道代码、适用店铺/国家/品类、计费方式
--           （按重/按件/按体积）、附加费（燃油、偏远、旺季）、申报限制、保价规则。
--   - 输入：渠道定义；计费与附加费参数；限制条件。
--   - 输出：渠道配置；供运费试算与自动匹配使用。
--   - 关系：上游 = 10.1.1.1；下游 = 10.1.2.2、9.3.2.1、10.2.1.2。
--           **渠道限制（禁限品类、电池液体、磁力）要硬拦截**，否则会被承运商拒收
--           产生额外费用。
--
-- 归属 channel：蓝图卷 10 卷首「后端归属：channel 拥有物流商连接与轨迹」，
-- 且同 L3 的 10.1.1.1 承运商列表已落在 channel（V134）—— 渠道配置挂在承运商之下，
-- 同库同服务引用是自然的一跳。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移约束到的既有模块 = 【只有 10.1.1.1 一个】
-- ---------------------------------------------------------------------------
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 函数，零 ALTER 任何既有表**：
--   * 唯一被引用的是 V134 自建的 `carrier_logistics_profile`，且是**同库复合外键**
--     （带 tenant_id），不是跨服务 FK —— 承运商档案本来就归本服务（10.1.1.1 已交付）
--   * 不碰 channel V1/V2/V3/V4/V93/V129 的任何一张表
--   * **不碰 trade 的任何一张表**（下游 10.2.1.2 运单申请在 trade，
--     `V128__trade_waybill_application.sql`）：渠道配置是它的**上游数据源**，
--     经本服务的授权 HTTP 端点提供，**一条跨服务 SQL / 跨库 FK / 共享视图都没有**
--   * 复用 identity 已注册的 channel.operation.* 权限码，**identity 零改动**
-- 四张表落在 logistics_channel_* 命名空间，与 V134 的 carrier_*（承运商档案）
-- 与 V129 的 handover_*（交运批次）并列而不混用。
--
-- ---------------------------------------------------------------------------
-- 一、职责句逐项落点（「不漏项」自检）
-- ---------------------------------------------------------------------------
--   | 蓝图职责句的词        | 落点                                                    |
--   |----------------------|---------------------------------------------------------|
--   | 所属承运商            | `logistics_channel_config.carrier_profile_id`（复合 FK）  |
--   | 渠道代码              | `channel_code`（建后不可改，见第四节 trigger b）           |
--   | 适用店铺/国家/品类    | `logistics_channel_scope`（每值一行，不是逗号串，见第五节）|
--   | 计费方式（重/件/体积）| `billing_mode` + `base_rate` + `charge_divisor`           |
--   | 附加费（燃油/偏远/旺季）| `logistics_channel_surcharge`（旺季带生效区间，见第六节） |
--   | 申报限制              | `declaration_*` 四列（见第七节）                          |
--   | 保价规则              | `insurance_*` 四列 + `insurance_max_value` 保价上限（第七节）|
--   | **渠道限制硬拦截**    | `logistics_channel_restriction` + trigger c + 判定端点     |
--
-- ---------------------------------------------------------------------------
-- 二、金额与数量：禁浮点
-- ---------------------------------------------------------------------------
-- 计费单价 / 最低收费 / 附加费金额 / 申报价值 / 保价上限 / 保费一律
-- **NUMERIC(19,4)**（金额，BigDecimal 绑定）；重量折算与体积折算系数一律
-- **NUMERIC(19,6)**（数量）。**没有一列是 float/double**，AGENTS.md
-- 「禁止浮点记账」在这里是硬约束不是建议 —— 附加费与保价上限都是钱。
--
-- `charge_divisor` 的语义随 `billing_mode` 变化（这是「按体积」能算准的关键）：
--   BY_WEIGHT  → 除重系数（默认 1 = 按实重每公斤）
--   BY_PIECE   → 每件基数（必须 = 1，否则「按件」就不是按件）
--   BY_VOLUME  → 体积折算基数（默认 6000 = 行业通用的 cm³/kg 折算）
-- 用 DB 层 CHECK 钉死「按件时基数必须是 1」，否则会出现「按件计费但每件按 0.5 公斤算」
-- 这种界面上完全看不出来、试算结果系统性偏低的配置。
--
-- ---------------------------------------------------------------------------
-- 三、适用维度是**真关系表**，不是逗号串（照抄 V134 第五节的同一个坑）
-- ---------------------------------------------------------------------------
-- 适用店铺 / 国家 / 品类都要「能按它筛选」。逗号串在筛选时只能写
-- `like '%,SEA,%'`，而 `SEA` 排在靠前位置时会漏匹配。`logistics_channel_scope`
-- 是**每值一行**的关联表，带复合主键与父表复合外键，可以真 JOIN、真索引。
--
-- ---------------------------------------------------------------------------
-- 四、触发器：把「可判定的依据」钉在写入路径上
-- ---------------------------------------------------------------------------
-- (a) 🔴 readiness 不接受调用方指定 —— 照抄 10.3.2.1 的 guard_handover_batch_counts
--     与 10.1.1.1 的 guard_carrier_logistics_readiness：服务层算一遍，DB 这里再算
--     一遍，绕过服务层直接写 SQL 把它改成 READY 同样被拒。
--     重算只用本服务看得见的输入：基础单价是否 > 0、是否至少登记一条适用维度、
--     渠道状态。**禁限品类登记了几条不是缺陷**（没登记限制的渠道照样能发货），
--     所以它不进 readiness —— 那会让「先建渠道、后补限制」这个正常流程永远
--     拿不到 READY，而 READY 在这里是**可用性**不是**合规完备度**。
--
--   readiness 取值（DB 层，优先级从高到低，唯一可判）：
--     NO_BASE_RATE  基础单价为 0 —— 试算算不出运费
--     NO_SCOPE      一条适用维度都没登记 —— 这个渠道对谁都不可用
--     SUSPENDED     渠道已停用
--     READY         三项齐备
--
-- (b) 🔴 渠道代码建后不可改。照抄 10.1.1.1 的 party_code：渠道代码是下游单据
--     （10.2.1.2 运单申请、10.3.2.1 交运批次）与承运商对接口径的锚点，
--     改代码会让已有单据下一次读取就找不到这条渠道配置。
--
-- (c) 🔴🔴 **本迁移最要紧的一条：适用品类与禁限品类不得同时命中同一个品类。**
--     蓝图加粗句「渠道限制（禁限品类、电池液体、磁力）要硬拦截，否则会被承运商
--     拒收产生额外费用」。最容易出的假活是「把禁限品类当一个字段存起来，
--     让前端自己去判断」—— 那不是硬拦截，那是把「会被承运商拒收」这件事
--     交给一个可以绕过、可以漏、也可以被下一个页面的同事改掉的判断。
--
--     所以这条冲突在 **DB 层双向钉死**，两个方向各一个触发器：
--       * 往适用维度里加一个品类，而该渠道已把它列为禁限 → 拒
--       * 登记一条禁限规则，而该渠道已把该品类列为适用 → 拒
--     只做单向是不够的：只查「新增适用是否撞禁限」，那么「先加适用、后加禁限」
--     这条正常路径就会造出一条「自己和自己打架」的渠道配置，而它的表现是
--     **下单时被承运商拒收** —— 正是这条加粗句要避免的那笔额外费用。
--
--     这里刻意**只挡「同一个品类既是适用又是禁限」**，不挡「一个渠道有禁限品类」
--     本身：后者是完全合法的配置，绝大多数渠道都禁液体。
--
-- (d) 🔴 禁限品类必须真的是「禁限」。`restriction_mode='ALLOWED'` 记在一个
--     `PROHIBITED_CATEGORY` 行上，会让「限制」这个表里躺着一行「不限制」——
--     判定端点读到它会放行，而运营在界面上明明勾了「禁限」。宁可在这里拒。
--
-- ---------------------------------------------------------------------------
-- 五、附加费：三种性质不同，所以是行而不是三个恒为 0 的列
-- ---------------------------------------------------------------------------
-- 燃油是**百分比**（随基础运费浮动），偏远/旺季是**固定额**，旺季还带**生效区间**。
-- 做成一张表而不是三个列，是因为「旺季附加费有生效区间」这件事在列里放不下：
-- 一个 `peak_rate numeric` 配上 `peak_from` / `peak_to` 三列，任何时候都会有
-- 「配了区间但 rate=0」或「有 rate 但区间留空」这种半配置状态，而判定端点
-- 必须能唯一地回答「这个时刻要不要加旺季费」。
--
-- `effective_from` / `effective_to` 是 **timestamptz**，且**要么都给要么都不给**
-- （CHECK 钉死），`from < to`（CHECK 钉死）。只允许旺季费带区间 —— 燃油与偏远
-- 是常驻的，加了区间就意味着「某些时候不加」，那不是附加费，是另一个规则。
--
-- ---------------------------------------------------------------------------
-- 六、申报限制与保价规则：四个约束各自回答一个真问题
-- ---------------------------------------------------------------------------
--   declaration_min_value / declaration_max_value  —— 低于下限或高于上限，这个渠道
--       就不适合这票货（多数渠道对申报价值有上下限，高价值件走保险渠道）
--   declaration_currency                            —— 申报用什么币种；与计费币种
--       不同是常态（运费按 USD 结算、申报按 CNY），但必须登记，否则试算没法换算
--   insurance_mode                                  —— NONE / DECLARED_VALUE（按申报
--       价值投保）/ FIXED（固定保价费）
--   insurance_rate_pct / insurance_min_fee          —— 费率与最低保费
--   insurance_max_value                             —— **保价上限**：超过这个申报价值
--       承运商不接保（寄丢了赔不到），这不是「可选项」而是一道硬边界，所以
--       独立成列而不是混在 insurance_mode 里
--
-- CHECK 把自相矛盾的组合在写库那一刻就拒掉，而不是让判定端点自己猜：
--   insurance_mode='NONE' 时费率与最低保费必须是 0（配了费率却不生效 = 幽灵配置）
--   insurance_mode='DECLARED_VALUE' 时必须配费率
--   申报上下限必须 min < max
--
-- ---------------------------------------------------------------------------
-- 七、下游未交付 ⇒ capabilities 显式写缺口，**不提前造**
-- ---------------------------------------------------------------------------
-- 下游 = 10.1.2.2 渠道比价、9.3.2.1 分仓、10.2.1.2 运单申请（trade）。
-- 本迁移只交付**渠道配置与其判定口径**；运费试算（10.1.2.1）、渠道时效
-- （10.1.1.3）、承运商接口接通均未交付，服务层 capabilities 逐段写
-- available=false + ownerModule + 影响，**不用空列表或 0 冒充「这一项没有数据」**。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 投影来源表的复合唯一键（V134 已建，这里直接引用）
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. logistics_channel_config · 渠道配置头
-- ---------------------------------------------------------------------------
-- 一行 = 一个承运商名下的一条**具体物流渠道**（顺丰美国小包、DHL 德国专线…）。
-- 承运商档案归 10.1.1.1，本表只持有 FK 引用，不复制承运商任何字段 ——
-- 复制一份就会造出「同一个承运商在两处各改一次」。
CREATE TABLE logistics_channel_config (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 上游 10.1.1.1：所属承运商（同库复合外键，带 tenant_id ⇒ 跨租户挂载不可能）===
  carrier_profile_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, carrier_profile_id)
    REFERENCES carrier_logistics_profile(tenant_id, id) ON DELETE RESTRICT,

  -- 渠道代码：下游单据对齐这条渠道配置的口径。建后不可改（trigger b）。
  channel_code varchar(64) NOT NULL CHECK (btrim(channel_code) <> ''),
  channel_name varchar(200) NOT NULL CHECK (btrim(channel_name) <> ''),

  -- DRAFT 草稿（可编辑，不参与试算）· ACTIVE 启用 · SUSPENDED 停用
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED')),

  -- === 计费方式（蓝图职责句括号里的三种）===
  billing_mode text NOT NULL
    CHECK (billing_mode IN ('BY_WEIGHT','BY_PIECE','BY_VOLUME')),
  billing_currency varchar(3) NOT NULL CHECK (billing_currency ~ '^[A-Z]{3}$'),
  base_rate numeric(19,4) NOT NULL CHECK (base_rate >= 0),
  -- 折算基数：语义随 billing_mode 变化（见文件头第二节）。按件计费必须是 1。
  charge_divisor numeric(19,6) NOT NULL DEFAULT 1
    CHECK (charge_divisor > 0 AND (billing_mode <> 'BY_PIECE' OR charge_divisor = 1)),
  -- 最低收费：低于它按它收。缺了它，1 件小件会被算成几分钱运费。
  min_charge numeric(19,4) NOT NULL DEFAULT 0 CHECK (min_charge >= 0),

  -- === 申报限制（见文件头第六节）===
  declaration_min_value numeric(19,4) CHECK (declaration_min_value IS NULL OR declaration_min_value >= 0),
  declaration_max_value numeric(19,4) CHECK (declaration_max_value IS NULL OR declaration_max_value >= 0),
  -- 下限 < 上限（两者都给时才成立）
  CHECK (declaration_min_value IS NULL OR declaration_max_value IS NULL
         OR declaration_min_value < declaration_max_value),
  declaration_currency varchar(3) CHECK (declaration_currency IS NULL OR declaration_currency ~ '^[A-Z]{3}$'),

  -- === 保价规则（见文件头第六节）===
  -- NONE 不承保 · DECLARED_VALUE 按申报价值投保 · FIXED 固定保价费
  insurance_mode text NOT NULL DEFAULT 'NONE'
    CHECK (insurance_mode IN ('NONE','DECLARED_VALUE','FIXED')),
  insurance_rate_pct numeric(9,4) NOT NULL DEFAULT 0
    CHECK (insurance_rate_pct >= 0 AND insurance_rate_pct <= 100),
  insurance_min_fee numeric(19,4) NOT NULL DEFAULT 0 CHECK (insurance_min_fee >= 0),
  -- 保价上限：超过这个申报价值承运商不接保（寄丢了赔不到）—— 一道硬边界。
  insurance_max_value numeric(19,4) CHECK (insurance_max_value IS NULL OR insurance_max_value >= 0),
  -- 不承保就不该有费率/最低保费（有费率却不生效 = 幽灵配置）
  CHECK (insurance_mode <> 'NONE' OR (insurance_rate_pct = 0 AND insurance_min_fee = 0)),
  -- 按申报价值投保必须有费率，否则算出保费恒为 0
  CHECK (insurance_mode <> 'DECLARED_VALUE' OR insurance_rate_pct > 0),

  -- readiness（DB 层那半）：**不接受调用方指定**，由 trigger a 重算比对。
  readiness text NOT NULL DEFAULT 'NO_BASE_RATE'
    CHECK (readiness IN ('READY','NO_BASE_RATE','NO_SCOPE','SUSPENDED')),

  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  -- 一个承运商下渠道代码唯一（否则列表上会出现两条一模一样的渠道配置）
  UNIQUE (tenant_id, carrier_profile_id, channel_code),
  -- 供两张子表的复合外键使用（同库引用，不是跨服务 FK）
  UNIQUE (tenant_id, id)
);

-- 「这个承运商下有哪些渠道」—— 详情页与筛选的主查询走这条。
CREATE INDEX logistics_channel_config_by_carrier
  ON logistics_channel_config(tenant_id, carrier_profile_id);

-- 渠道代码检索（照抄 catalog 的 ILIKE '%kw%' 结论：前导通配符用不上 btree，
-- 这里先留 btree 供等值与排序用，模糊检索走 pg_trgm 由服务层按需）。
CREATE INDEX logistics_channel_config_by_code
  ON logistics_channel_config(tenant_id, channel_code);

-- 计费方式 / 状态筛选（页面上的两个下拉）。
CREATE INDEX logistics_channel_config_by_billing_mode
  ON logistics_channel_config(tenant_id, billing_mode);
CREATE INDEX logistics_channel_config_by_status
  ON logistics_channel_config(tenant_id, status);
CREATE INDEX logistics_channel_config_by_readiness
  ON logistics_channel_config(tenant_id, readiness);

-- ---------------------------------------------------------------------------
-- 2. logistics_channel_scope · 适用店铺 / 国家 / 品类（真多值关系）
-- ---------------------------------------------------------------------------
-- 蓝图职责句「适用店铺/国家/品类」逐值一行。三个维度合成一张表而不是三张，
-- 因为它们语义完全同构（都是「渠道适用范围的一个取值」）且总行数很小；
-- scope_kind 上有复合主键前缀索引，按任一维度筛都走索引。
CREATE TABLE logistics_channel_scope (
  tenant_id uuid NOT NULL,
  channel_id uuid NOT NULL,
  scope_kind text NOT NULL
    CHECK (scope_kind IN ('SHOP','COUNTRY','CATEGORY')),
  scope_value varchar(64) NOT NULL CHECK (btrim(scope_value) <> ''),
  PRIMARY KEY (tenant_id, channel_id, scope_kind, scope_value),
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE,
  -- 国家维度只接受两位大写字母（照抄 V134 carrier_country_coverage 的同一结论：
  -- 长度已在语义里约束，不需要 bpchar 的补空格）
  CHECK (scope_kind <> 'COUNTRY' OR scope_value ~ '^[A-Z]{2}$')
);

-- 「送到德国的渠道有哪些」/「这个店铺能用的渠道有哪些」—— 复合主键后缀索引。
CREATE INDEX logistics_channel_scope_by_value
  ON logistics_channel_scope(tenant_id, scope_kind, scope_value);

-- ---------------------------------------------------------------------------
-- 3. logistics_channel_surcharge · 附加费（燃油 / 偏远 / 旺季）
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_channel_surcharge (
  tenant_id uuid NOT NULL,
  channel_id uuid NOT NULL,
  surcharge_kind text NOT NULL
    CHECK (surcharge_kind IN ('FUEL','REMOTE_AREA','PEAK_SEASON')),
  -- PERCENT 按基础运费的百分比 · FIXED 固定额
  charge_mode text NOT NULL DEFAULT 'FIXED'
    CHECK (charge_mode IN ('PERCENT','FIXED')),
  amount numeric(19,4) NOT NULL CHECK (amount >= 0),
  -- 生效区间：**timestamptz**，要么都给要么都不给，只允许旺季费带（见文件头第五节）
  effective_from timestamptz,
  effective_to timestamptz,
  CHECK ((effective_from IS NULL) = (effective_to IS NULL)),
  CHECK (effective_from IS NULL OR effective_from < effective_to),
  CHECK (surcharge_kind <> 'PEAK_SEASON' OR effective_from IS NOT NULL),
  note text NOT NULL DEFAULT '',
  PRIMARY KEY (tenant_id, channel_id, surcharge_kind),
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE
);

-- 「旺季费现在生效吗」—— 判定端点按时刻区间筛走这条。
CREATE INDEX logistics_channel_surcharge_by_window
  ON logistics_channel_surcharge(tenant_id, surcharge_kind, effective_from, effective_to);

-- ---------------------------------------------------------------------------
-- 4. logistics_channel_restriction · 渠道限制（禁限品类 / 电池 / 液体 / 磁力）
-- ---------------------------------------------------------------------------
-- 蓝图加粗句的物理落点。**判定必须在这里做，且由服务端做**（见 trigger c 与
-- 服务层的判定端点），不是「存一个字段然后前端自己判断」。
--
-- `subject` 的语义随 restriction_kind 变化：
--   PROHIBITED_CATEGORY  subject = 被禁限的品类编码（必填）
--   BATTERY / LIQUID / MAGNET  subject = ''（整条渠道层面的限制）
-- 这样「电池」不需要为每个品类各登记一行 —— 一个渠道要么能走电池，要么不能。
CREATE TABLE logistics_channel_restriction (
  tenant_id uuid NOT NULL,
  channel_id uuid NOT NULL,
  restriction_kind text NOT NULL
    CHECK (restriction_kind IN ('PROHIBITED_CATEGORY','BATTERY','LIQUID','MAGNET')),
  subject varchar(64) NOT NULL DEFAULT '',
  -- BLOCK 硬拦截（渠道拒收）· MANUAL_REVIEW 需人工确认后放行 · ALLOWED 明确放行
  restriction_mode text NOT NULL DEFAULT 'BLOCK'
    CHECK (restriction_mode IN ('BLOCK','MANUAL_REVIEW','ALLOWED')),
  -- 承运商侧的原文理由（拒收通知、合同条款）。留空不影响判定，
  -- 但界面让人知道「为什么被拒」而不是只看到一个 BLOCK。
  carrier_reason text NOT NULL DEFAULT '',
  PRIMARY KEY (tenant_id, channel_id, restriction_kind, subject),
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE CASCADE,
  -- 禁限品类必须点名到具体品类（subject 不能为空）
  CHECK (restriction_kind <> 'PROHIBITED_CATEGORY' OR btrim(subject) <> ''),
  -- 电池/液体/磁力是渠道级限制，subject 必须为空（见上面 subject 语义说明）
  CHECK (restriction_kind = 'PROHIBITED_CATEGORY' OR btrim(subject) = ''),
  -- 🔴 「限制」表里不允许躺一行「不限制」（trigger d 的列级部分）
  CHECK (restriction_kind <> 'PROHIBITED_CATEGORY' OR restriction_mode <> 'ALLOWED')
);

-- 判定端点的热路径：「这条渠道禁了哪些品类 / 电池 / 液体 / 磁力」。
CREATE INDEX logistics_channel_restriction_by_kind
  ON logistics_channel_restriction(tenant_id, channel_id, restriction_kind);

-- ---------------------------------------------------------------------------
-- 5. 触发器
-- ---------------------------------------------------------------------------

-- (a) readiness 重算比对（照抄 guard_carrier_logistics_readiness）。
CREATE OR REPLACE FUNCTION guard_logistics_channel_readiness() RETURNS trigger AS $$
DECLARE
  has_scope boolean;
  expected text;
BEGIN
  SELECT EXISTS (SELECT 1 FROM logistics_channel_scope
                  WHERE tenant_id = NEW.tenant_id AND channel_id = NEW.id)
    INTO has_scope;

  IF NEW.base_rate <= 0 THEN
    expected := 'NO_BASE_RATE';
  ELSIF NOT has_scope THEN
    expected := 'NO_SCOPE';
  ELSIF NEW.status = 'SUSPENDED' THEN
    expected := 'SUSPENDED';
  ELSE
    expected := 'READY';
  END IF;

  IF NEW.readiness <> expected THEN
    -- ⚠️ 占位符一律用**裸 %**：PostgreSQL 的 RAISE 没有 C 的 %s 转换。
    RAISE EXCEPTION
      '渠道配置的可用性判定为 %（基础单价 % / 适用维度 % / 状态 %），与提交的 % 不符 —— 可用性由这三项算出，不接受手工指定',
      expected,
      CASE WHEN NEW.base_rate <= 0 THEN '为 0' ELSE '已填' END,
      CASE WHEN has_scope THEN '有' ELSE '无' END,
      NEW.status,
      NEW.readiness USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_channel_readiness_trg
  BEFORE INSERT OR UPDATE ON logistics_channel_config
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_channel_readiness();

-- (b) 🔴 渠道代码建后不可改。
CREATE OR REPLACE FUNCTION guard_logistics_channel_code_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.channel_code IS DISTINCT FROM OLD.channel_code THEN
    RAISE EXCEPTION
      '渠道代码建后不可改（% → %）：渠道代码是下游运单与交运批次对齐这条渠道配置的口径，改代码会让已有单据找不到渠道配置',
      OLD.channel_code, NEW.channel_code USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_channel_code_immutable_trg
  BEFORE UPDATE ON logistics_channel_config
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_channel_code_immutable();

-- (c) 🔴🔴 适用品类 ⇄ 禁限品类不得同时命中（**双向**）。
--     正向：新增/改一条适用品类，而该渠道已把它列为禁限 → 拒。
CREATE OR REPLACE FUNCTION guard_logistics_scope_vs_restriction() RETURNS trigger AS $$
DECLARE
  hit record;
BEGIN
  IF NEW.scope_kind <> 'CATEGORY' THEN
    RETURN NEW;
  END IF;

  SELECT r.restriction_kind, r.restriction_mode, r.carrier_reason INTO hit
    FROM logistics_channel_restriction r
   WHERE r.tenant_id = NEW.tenant_id
     AND r.channel_id = NEW.channel_id
     AND r.restriction_kind = 'PROHIBITED_CATEGORY'
     AND r.subject = NEW.scope_value;

  IF FOUND AND hit.restriction_mode <> 'ALLOWED' THEN
    RAISE EXCEPTION
      '品类「%」已被这条渠道列为禁限（%：%），不能再登记为适用品类 —— 否则下单会被承运商拒收并产生额外费用',
      NEW.scope_value, hit.restriction_mode,
      CASE WHEN hit.carrier_reason = '' THEN '未登记理由' ELSE hit.carrier_reason END
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_scope_vs_restriction_trg
  BEFORE INSERT OR UPDATE ON logistics_channel_scope
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_scope_vs_restriction();

--     反向：登记/改一条禁限规则，而该渠道已把该品类列为适用 → 拒。
--     没有这一条，「先加适用、后加禁限」这条完全正常的运营路径就会造出一条
--     「自己和自己打架」的渠道配置，而它的表现是**被承运商拒收**。
CREATE OR REPLACE FUNCTION guard_logistics_restriction_vs_scope() RETURNS trigger AS $$
DECLARE
  hit record;
BEGIN
  IF NEW.restriction_kind <> 'PROHIBITED_CATEGORY' THEN
    RETURN NEW;
  END IF;

  SELECT s.scope_value INTO hit
    FROM logistics_channel_scope s
   WHERE s.tenant_id = NEW.tenant_id
     AND s.channel_id = NEW.channel_id
     AND s.scope_kind = 'CATEGORY'
     AND s.scope_value = NEW.subject;

  IF FOUND AND NEW.restriction_mode <> 'ALLOWED' THEN
    RAISE EXCEPTION
      '品类「%」已被这条渠道登记为适用，不能同时列为禁限（%）—— 要改适用范围请先去掉适用品类，或反过来',
      NEW.subject, NEW.restriction_mode USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_restriction_vs_scope_trg
  BEFORE INSERT OR UPDATE ON logistics_channel_restriction
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_restriction_vs_scope();

-- ---------------------------------------------------------------------------
-- 6. 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_channel_config',
    'logistics_channel_scope',
    'logistics_channel_surcharge',
    'logistics_channel_restriction' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 7. 账号与授权（沿用 V1/V93/V129/V134 的条件建角色，保证迁移可重复执行）
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
  logistics_channel_config, logistics_channel_scope,
  logistics_channel_surcharge, logistics_channel_restriction TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 同口径）。
REVOKE ALL ON
  logistics_channel_config, logistics_channel_scope,
  logistics_channel_surcharge, logistics_channel_restriction FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 8. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：渠道配置属于「平台/承运商操作」，与 10.1.1.1 承运商、
-- 10.3.2.1 交运批次、10.4.2.1 海外仓连接同族，因此 identity 服务零改动，
-- 跨车道回归面 = 0。
