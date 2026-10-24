-- ===========================================================================
-- 12.6.1.1 税率配置 —— finance 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/12-finance.md 第 282-288 行）：
--   ### 12.6.1.1 税率配置
--   - 职责：维护税率配置：目的国/地区的销售税率、VAT 税率、IOSS 适用税率、代扣代缴税；
--           支持按店铺站点绑定默认税率与税码。
--   - 输入：税率定义；适用站点；生效时间。
--   - 输出：税率记录；供订单与申报引用。
--   - 关系：上游 = 2 店铺站点、16 合规；下游 = 9 订单（税额）、12.6.1.2 申报。
--           **税率必须带生效时间**，历史订单用当时税率，不能被新政策追溯修改。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 与 channel 的职责切分（**不要重复建表**）
-- ---------------------------------------------------------------------------
-- 同批交付的 channel V352（16.3.2.3 销售税与 Marketplace Facilitator）**已经**建了：
--   `sales_tax_rate_rule`（美国**州级**销售税率规则：州基准税率 / 州阈值 / 免征额 /
--   是否实施 Marketplace Facilitator / 申报周期）、`sales_tax_shop_state`（店铺所在州 =
--   nexus 事实）、`sales_tax_facilitator_*`（代扣代缴说明与明细）。
--
-- ⇒ 职责切分：
--   * **channel（16.3.2.3）**：美国**州级销售税**的税率规则与州归属事实；
--   * **finance（本模块）**：**VAT / IOSS / 代扣代缴（withholding）** +
--     **目的国/地区的销售税率**（注意：这里的「销售税率」是**目的国/地区**口径，
--     比如目的国自身的消费税口径，**不是**美国的州销售税），以及
--     **店铺站点默认税率与税码的绑定**。
--
--   本模块**不建任何州销售税表**。表前缀 `finance_tax_*` 与 channel 的
--   `sales_tax_*` / `tax_registration_*` **彼此隔离**。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 辖区码不是自由文本（**不建影子码表、不跨库 FK**）
-- ---------------------------------------------------------------------------
-- 「目的国/地区」的值域由 channel 的 `tax_registration_jurisdiction`（16.3.1.1，V312）
-- 这张**受控码表**决定 —— 欧盟 27 国 + 英国 + 美国联邦与 50 州。
--
--   * 本模块**不复制**那张码表（复制 = 两个真值源，改一处就漂移）；
--   * 本模块**不建**指向它的外键（跨库 FK 是模块边界纪律明令禁止的）；
--   * 服务层经**授权只读 HTTP API**（`GET /channel/tax-registration/jurisdictions`）
--     实时读它，校验「辖区码真实存在」后才允许落库；
--   * channel 读不到 ⇒ **503 并写明「读不到」**，绝不接受自由文本辖区码。
--
-- 🔴 **本模块只接受国家一级辖区**（`subdivision_code` 为空）：
--   美国州一级的销售税事实归 channel，本模块重复维护就是制造第二个真值源。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 税率必须带生效时间、历史不可追溯改写（**落成结构而不是文案**）
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗句在这里由四件东西共同钉死：
--
--   1. **生效区间**：`effective_from`（生效起点）+ `effective_to`（失效终点），
--      左闭右开；`effective_to` 为空 = 「这一版还没被换掉」（= 上界无穷）。
--   2. **版本链**：改税率 = **追加新版本**（不是原地改）。触发器钉死
--      版本号递增 + 生效起点严格递进 + 新版本必须接在上一版之后。
--   3. **不可改写**：已存在的版本行**不许**改税率 / 辖区 / 税码 / 版本号 /
--      生效起点 / 接续版本（触发器钉死）；`effective_to` 只允许
--      「从空变成有值」这一次动作 —— 收口之后不许再改。
--   4. **区间不许重叠**（触发器钉死）：同一版本链内，任意两条版本的生效区间
--      不允许有交集 ⇒ 「某一时点命中哪一版」永远唯一。
--
--   配套的部分唯一索引保证「同一版本链同时只有一个生效版本」。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 版本链的键是什么（**决定了整个数据模型的形状**）
-- ---------------------------------------------------------------------------
--   * **税率链** = （租户 × 税种 × 辖区 × **税码**）
--     —— 同一辖区里的不同税档（标准 / 优惠 / 零税率）是**不同的税档**，
--        各自的税率各自版本化。德国标准 VAT 19% 改成 17% 时，
--        只动 STANDARD 那条链，REDUCED 那条链**一个字都不该动**。
--
--   * **绑定链** = （租户 × 店铺站点 × 税种 × 辖区）
--     —— 一家店在德国的**默认**税码。一个店可以同时在 DE 与 FR 各有一条绑定
--        （一条链一个辖区），但同一辖区同一税种只有**一条默认绑定**。
--        🔴 蓝图说的是「**默认**税率与税码」：产品级 / 行级的税码税率属
--        **12.6.2.1 发票管理**，不在本模块。
--
--   🔴 **绑定不指向「某一版税率记录」**，而是钉住（辖区 × 税码）这条**税档**：
--   政策改税率时，只需在该税档的链上追加新版本，**所有绑在这条税档上的店铺
--   自动按新版本算**（从新版本的生效时刻起），历史订单仍按当时那版算。
--   若反过来让绑定钉死某个税率版本 id，就会出现「税率版本被换掉后，
--   店铺还绑着一个已失效的版本」这种悬空状态 —— 而且没有自然的操作顺序
--   能把它解开（先换绑要先有新税率版本，先收口旧版本又被绑定挡着）。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 店铺站点绑定默认税率与税码（**换绑同样不追溯**）
-- ---------------------------------------------------------------------------
-- `finance_tax_shop_binding` 把「一家店铺站点 × 一个税种 × 一个辖区」
-- 绑到**一条税档**（辖区 × 税码）上。
--
--   * 绑定**自己也有生效区间与版本链** ⇒ 换绑 = 追加新版本，历史绑定不被改写；
--   * 🔴 绑定的税种与辖区必须**真的存在税率记录**（触发器钉死）——
--     「绑到一个根本没有任何税率的税档上」是配置错误，不是有效配置。
--
-- ---------------------------------------------------------------------------
-- 六、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS isolation
-- ---------------------------------------------------------------------------
-- 与 finance 既有迁移（V338 ~ V357）同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- ---------------------------------------------------------------------------
-- 七、🔴 零外键（跨表引用一律用触发器）
-- ---------------------------------------------------------------------------
-- 与同服务既有迁移同一纪律：外键会打破同服务既有测试的 TRUNCATE。
--
-- ---------------------------------------------------------------------------
-- 八、🔴 税率是 NUMERIC（禁浮点）
-- ---------------------------------------------------------------------------
-- 税率是小数率：`numeric(9,6)`（0.190000 = 19%），**禁浮点**。
-- 税率**不带币种列**：税率是一个比例，作用于任何币种的应税基数；
-- 币种属于税额（金额）那一侧，由 12.6.1.2 申报模块与订单侧负责。
--
-- ---------------------------------------------------------------------------
-- 九、🔴 时间字段纪律
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。
-- 用 `Timestamp.valueOf(LocalDateTime...)` 会**静默偏 −28800s**，页面上看不出异常。
-- ===========================================================================

-- ===========================================================================
-- 一、finance_tax_rate · 税率记录（按 税种 × 辖区 × 税码 × 生效区间，版本化）
-- ===========================================================================
-- 🔴 一个租户在同一个（税种 × 辖区 × 税码）下可以有多版税率；生效区间是**左闭右开**
--   [effective_from, effective_to)，effective_to 为 NULL 表示「当前这版还没被换掉」。
--
-- 🔴 税率是**租户级事实**：同一个辖区，不同租户的税率可能不同
--   （IOSS 适用与否、简易征收、免税登记……）⇒ 税率不能做成全局码表，必须带 tenant_id。
--
-- 🔴 **税率零预置行**：税率是用户自己的税务事实，系统只提供结构与辖区选项，
--   **不替用户决定税率**。表里一行都没有是**正确状态**，不是缺数据。
CREATE TABLE finance_tax_rate (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 税种（蓝图点名的四种）
  --   SALES_TAX   目的国/地区的销售税率
  --   VAT         增值税
  --   IOSS        IOSS 适用税率（欧盟进口一站式商店）
  --   WITHHOLDING 代扣代缴税
  tax_type          varchar(16) NOT NULL
                    CHECK(tax_type IN ('SALES_TAX','VAT','IOSS','WITHHOLDING')),
  -- 辖区码（**国家一级**）：必须真实落在 channel 的 tax_registration_jurisdiction
  --   且 subdivision_code 为空 ⇒ 由服务层经授权只读 API 校验后落库，DB 层只钉形状。
  jurisdiction_code varchar(16) NOT NULL,
  -- 辖区名称快照（跨服务引用不建 FK，名称留在本表免得半年后没人说得清是哪个辖区）
  jurisdiction_name varchar(100) NOT NULL,
  -- ISO 3166-1 alpha-2（与辖区码表的 country_code 一致）
  country_code      varchar(2) NOT NULL,
  -- 税码（该辖区下的税档编码，例如标准 / 优惠 / 零税率）—— **版本链的键之一**
  tax_code          varchar(32) NOT NULL,
  -- 税率（小数率：0.190000 = 19%）
  rate              numeric(9,6) NOT NULL CHECK(rate >= 0 AND rate <= 1),
  -- 生效区间（左闭右开）
  effective_from    timestamptz NOT NULL,
  effective_to      timestamptz,
  -- ACTIVE = 当前这版；SUPERSEDED = 已被新版本接替；ARCHIVED = 撤下（不接替）
  status            varchar(16) NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','SUPERSEDED','ARCHIVED')),
  -- 版本号（同一 税种 × 辖区 × 税码 内递增，从 1 开始）
  version           int NOT NULL CHECK(version >= 1),
  -- 接续的上一版（版本链）
  supersedes_id     uuid,
  note              varchar(500) NOT NULL DEFAULT '',
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, tax_type, jurisdiction_code, tax_code, version),
  -- 同一版本链内生效起点唯一（两版不能从同一刻开始）
  UNIQUE(tenant_id, tax_type, jurisdiction_code, tax_code, effective_from),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- ACTIVE ⇔ 未收口；SUPERSEDED / ARCHIVED ⇔ 已收口（生效区间必须有终点）
  CHECK((status = 'ACTIVE') = (effective_to IS NULL))
);
COMMENT ON TABLE finance_tax_rate IS
  '12.6.1.1 · 税率记录（租户级，按 税种 × 辖区 × 税码 × 生效区间 版本化）。'
  '税率是用户自己的税务事实，系统**零预置行** —— 系统只提供结构与辖区选项，不替用户决定税率。'
  '生效区间左闭右开且不许重叠；历史版本不可改写；改税率 = 追加新版本。'
  '辖区码取自 channel 的受控辖区码表（16.3.1.1），**只允许国家一级** —— '
  '美国州级销售税归 channel 16.3.2.3，本模块不重复建表。';

CREATE INDEX finance_tax_rate_jurisdiction
  ON finance_tax_rate(tenant_id, tax_type, jurisdiction_code, tax_code, effective_from DESC);
CREATE INDEX finance_tax_rate_active
  ON finance_tax_rate(tenant_id, tax_type, jurisdiction_code, tax_code)
  WHERE status = 'ACTIVE';

-- 同一版本链同时只能有一个生效版本
CREATE UNIQUE INDEX finance_tax_rate_one_active
  ON finance_tax_rate(tenant_id, tax_type, jurisdiction_code, tax_code)
  WHERE status = 'ACTIVE';

-- ===========================================================================
-- 二、finance_tax_shop_binding · 店铺站点的默认税率与税码绑定（版本化）
-- ===========================================================================
-- 🔴 蓝图：「支持按店铺站点绑定默认税率与税码」。
--
--   * `shop_id` 是 **channel 2.3.1.1 店铺目录**的逻辑 ID（跨服务引用，**不建 FK**、
--     **不建影子店铺表**）；店铺编码与名称是**快照**。
--   * `tax_code` 是被绑定的**税档**（与辖区、税种一起构成税率链的键）。
--   * 绑定**自己也有生效区间与版本链** ⇒ 换绑同样不追溯。
CREATE TABLE finance_tax_shop_binding (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  -- 店铺站点（channel 2.3.1.1 店铺目录的逻辑 ID）
  shop_id           uuid NOT NULL,
  shop_code         varchar(64) NOT NULL DEFAULT '',
  shop_name         varchar(200) NOT NULL DEFAULT '',
  tax_type          varchar(16) NOT NULL
                    CHECK(tax_type IN ('SALES_TAX','VAT','IOSS','WITHHOLDING')),
  -- 辖区码（国家一级）
  jurisdiction_code varchar(16) NOT NULL,
  -- 税码（被绑定的税档）
  tax_code          varchar(32) NOT NULL,
  -- 生效区间（左闭右开）
  effective_from    timestamptz NOT NULL,
  effective_to      timestamptz,
  status            varchar(16) NOT NULL DEFAULT 'ACTIVE'
                    CHECK(status IN ('ACTIVE','SUPERSEDED','ARCHIVED')),
  -- 版本号（同一 店铺 × 税种 × 辖区 内递增，从 1 开始）
  version           int NOT NULL CHECK(version >= 1),
  -- 接续的上一版（版本链）
  supersedes_id     uuid,
  note              varchar(500) NOT NULL DEFAULT '',
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, shop_id, tax_type, jurisdiction_code, version),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  CHECK((status = 'ACTIVE') = (effective_to IS NULL))
);
COMMENT ON TABLE finance_tax_shop_binding IS
  '12.6.1.1 · 店铺站点的默认税率与税码绑定（租户级，版本化）。'
  '绑的是**一条税档**（辖区 × 税码）而不是某一个税率版本 ⇒ 政策改税率时，'
  '绑在这条税档上的店铺自动从新版本的生效时刻起按新税率算，'
  '而历史订单仍按当时那版算。'
  '🔴 蓝图说的是「**默认**」：产品级 / 行级的税码税率属 12.6.2.1 发票管理。';

CREATE INDEX finance_tax_shop_binding_shop
  ON finance_tax_shop_binding(tenant_id, shop_id, tax_type, jurisdiction_code, effective_from DESC);
CREATE INDEX finance_tax_shop_binding_active
  ON finance_tax_shop_binding(tenant_id, shop_id, tax_type, jurisdiction_code)
  WHERE status = 'ACTIVE';

-- 同一（租户 × 店铺 × 税种 × 辖区）同时只能有一个生效绑定
CREATE UNIQUE INDEX finance_tax_shop_binding_one_active
  ON finance_tax_shop_binding(tenant_id, shop_id, tax_type, jurisdiction_code)
  WHERE status = 'ACTIVE';

-- ===========================================================================
-- 三、触发器：税率的版本链 / 区间不重叠 / 历史不可改写
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (a) 版本链守卫：版本号递增 + 生效起点严格递进 + 必须接在上一版之后
-- ---------------------------------------------------------------------------
--   ⚠️ 守卫比对的是**版本号最大的那一版**（而不是 status='ACTIVE' 的那一行）：
--   写库顺序是「先把上一版置为 SUPERSEDED 并收口生效区间，再插入新的一版」，
--   插入时库里已经不存在 ACTIVE 行 —— 若守卫拿 ACTIVE 行来比，
--   就会要求 supersedes_id 为空，正常的版本切换反而会被挡住。
--   「同一链同时只有一个生效版本」由**部分唯一索引**钉死（不是靠这里的守卫）。
CREATE OR REPLACE FUNCTION finance_tax_rate_guard() RETURNS trigger AS $$
DECLARE
  v_last finance_tax_rate%ROWTYPE;
BEGIN
  SELECT * INTO v_last
    FROM finance_tax_rate x
   WHERE x.tenant_id = NEW.tenant_id
     AND x.tax_type = NEW.tax_type
     AND x.jurisdiction_code = NEW.jurisdiction_code
     AND x.tax_code = NEW.tax_code
   ORDER BY x.version DESC
   LIMIT 1;

  IF NOT FOUND THEN
    IF NEW.version <> 1 THEN
      RAISE EXCEPTION '税率的第一个版本必须是 1，实际是 %', NEW.version
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.supersedes_id IS NOT NULL THEN
      RAISE EXCEPTION '税率的第一个版本没有可接续的版本，supersedes_id 必须为空'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.version <> v_last.version + 1 THEN
    RAISE EXCEPTION '税率版本号必须递增：上一个版本是 %，本次是 %',
      v_last.version, NEW.version USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.supersedes_id IS DISTINCT FROM v_last.id THEN
    RAISE EXCEPTION '新版本必须接在上一版本之后（supersedes_id = %）', v_last.id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 历史订单用当时税率，不能被新政策追溯修改 ⇒ 生效起点必须严格递进。
  IF NEW.effective_from <= v_last.effective_from THEN
    RAISE EXCEPTION '新版本的生效时间必须晚于上一版本（%），否则历史订单会被追溯改写',
      v_last.effective_from USING ERRCODE = 'check_violation';
  END IF;

  -- 新版本必须接在上一版**之后**（不许与上一版的生效区间重叠）。
  IF v_last.effective_to IS NOT NULL AND NEW.effective_from < v_last.effective_to THEN
    RAISE EXCEPTION '新版本的生效时间（%）必须不早于上一版的失效时间（%）',
      NEW.effective_from, v_last.effective_to USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_tax_rate_guard
  BEFORE INSERT ON finance_tax_rate
  FOR EACH ROW EXECUTE FUNCTION finance_tax_rate_guard();

-- ---------------------------------------------------------------------------
-- (b) 生效区间不许重叠：任意两条版本的区间不得有交集
-- ---------------------------------------------------------------------------
--   「某一时点命中哪一版」必须**唯一**。版本链 + 部分唯一索引已经保证了
--   「同时只有一个 ACTIVE」，但那只管住了**当前**；历史上留一个重叠区间，
--   按生效时间取税率时就会命中两条 —— 而「命中不到就报错、命中两条也必须报错」
--   都不该靠服务层兜底。这里在数据层直接把重叠钉死。
--
--   `tstzrange(from, NULL, '[)')` 的上界是**无穷** ⇒ 语义正好是「还没被换掉」。
CREATE OR REPLACE FUNCTION finance_tax_rate_no_overlap() RETURNS trigger AS $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM finance_tax_rate x
     WHERE x.tenant_id = NEW.tenant_id
       AND x.tax_type = NEW.tax_type
       AND x.jurisdiction_code = NEW.jurisdiction_code
       AND x.tax_code = NEW.tax_code
       AND x.id <> NEW.id
       AND tstzrange(x.effective_from, x.effective_to, '[)')
          && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ) THEN
    RAISE EXCEPTION '同一税种同一辖区同一税码的生效区间不许重叠：与已有版本的生效区间有交集'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_tax_rate_no_overlap
  BEFORE INSERT OR UPDATE ON finance_tax_rate
  FOR EACH ROW EXECUTE FUNCTION finance_tax_rate_no_overlap();

-- ---------------------------------------------------------------------------
-- (c) 历史不可改写：已存在的版本行不许改税率 / 辖区 / 税码 / 版本号 / 生效起点
-- ---------------------------------------------------------------------------
--   只允许改 status / effective_to（且只能「从空变成有值」这一次）/ note / updated_at。
CREATE OR REPLACE FUNCTION finance_tax_rate_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.tax_type IS DISTINCT FROM OLD.tax_type
     OR NEW.jurisdiction_code IS DISTINCT FROM OLD.jurisdiction_code
     OR NEW.jurisdiction_name IS DISTINCT FROM OLD.jurisdiction_name
     OR NEW.country_code IS DISTINCT FROM OLD.country_code
     OR NEW.tax_code IS DISTINCT FROM OLD.tax_code
     OR NEW.rate IS DISTINCT FROM OLD.rate
     OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
     OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id THEN
    RAISE EXCEPTION '税率的历史版本不可改写（只能收口生效区间或撤下）'
      USING ERRCODE = 'check_violation';
  END IF;

  -- effective_to 只能被设置一次：收口之后不许再改（否则历史区间会被追溯挪动）。
  IF OLD.effective_to IS NOT NULL AND NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
    RAISE EXCEPTION '税率版本的失效时间一经收口不可再改'
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_tax_rate_immutable
  BEFORE UPDATE ON finance_tax_rate
  FOR EACH ROW EXECUTE FUNCTION finance_tax_rate_immutable();

-- ===========================================================================
-- 四、触发器：店铺绑定的版本链 / 区间不重叠 / 历史不可改写 / 税档必须真实存在
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- (e) 绑定版本链守卫（与税率同一手法）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_tax_shop_binding_guard() RETURNS trigger AS $$
DECLARE
  v_last finance_tax_shop_binding%ROWTYPE;
BEGIN
  SELECT * INTO v_last
    FROM finance_tax_shop_binding x
   WHERE x.tenant_id = NEW.tenant_id
     AND x.shop_id = NEW.shop_id
     AND x.tax_type = NEW.tax_type
     AND x.jurisdiction_code = NEW.jurisdiction_code
   ORDER BY x.version DESC
   LIMIT 1;

  IF NOT FOUND THEN
    IF NEW.version <> 1 THEN
      RAISE EXCEPTION '店铺绑定的第一个版本必须是 1，实际是 %', NEW.version
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.supersedes_id IS NOT NULL THEN
      RAISE EXCEPTION '店铺绑定的第一个版本没有可接续的版本，supersedes_id 必须为空'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.version <> v_last.version + 1 THEN
    RAISE EXCEPTION '店铺绑定版本号必须递增：上一个版本是 %，本次是 %',
      v_last.version, NEW.version USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.supersedes_id IS DISTINCT FROM v_last.id THEN
    RAISE EXCEPTION '新版本必须接在上一版本之后（supersedes_id = %）', v_last.id
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.effective_from <= v_last.effective_from THEN
    RAISE EXCEPTION '新版本的生效时间必须晚于上一版本（%），否则历史订单会被追溯改写',
      v_last.effective_from USING ERRCODE = 'check_violation';
  END IF;

  IF v_last.effective_to IS NOT NULL AND NEW.effective_from < v_last.effective_to THEN
    RAISE EXCEPTION '新版本的生效时间（%）必须不早于上一版的失效时间（%）',
      NEW.effective_from, v_last.effective_to USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_tax_shop_binding_guard
  BEFORE INSERT ON finance_tax_shop_binding
  FOR EACH ROW EXECUTE FUNCTION finance_tax_shop_binding_guard();

-- ---------------------------------------------------------------------------
-- (f) 绑定的生效区间不许重叠
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION finance_tax_shop_binding_no_overlap() RETURNS trigger AS $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM finance_tax_shop_binding x
     WHERE x.tenant_id = NEW.tenant_id
       AND x.shop_id = NEW.shop_id
       AND x.tax_type = NEW.tax_type
       AND x.jurisdiction_code = NEW.jurisdiction_code
       AND x.id <> NEW.id
       AND tstzrange(x.effective_from, x.effective_to, '[)')
          && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ) THEN
    RAISE EXCEPTION '同一店铺同一税种同一辖区的绑定生效区间不许重叠：与已有版本的生效区间有交集'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_tax_shop_binding_no_overlap
  BEFORE INSERT OR UPDATE ON finance_tax_shop_binding
  FOR EACH ROW EXECUTE FUNCTION finance_tax_shop_binding_no_overlap();

-- ---------------------------------------------------------------------------
-- (g) 绑定的历史不可改写
-- ---------------------------------------------------------------------------
--   换绑 = 追加新版本：店铺 / 税种 / 辖区 / 税码 一律不可就地改
--   （税码**可以**在版本之间变 —— 那正是「换绑到另一个税档」——
--     但同一行内不可就地改）。
CREATE OR REPLACE FUNCTION finance_tax_shop_binding_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.shop_id IS DISTINCT FROM OLD.shop_id
     OR NEW.shop_code IS DISTINCT FROM OLD.shop_code
     OR NEW.shop_name IS DISTINCT FROM OLD.shop_name
     OR NEW.tax_type IS DISTINCT FROM OLD.tax_type
     OR NEW.jurisdiction_code IS DISTINCT FROM OLD.jurisdiction_code
     OR NEW.tax_code IS DISTINCT FROM OLD.tax_code
     OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
     OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id THEN
    RAISE EXCEPTION '店铺绑定的历史版本不可改写（换绑请追加新版本）'
      USING ERRCODE = 'check_violation';
  END IF;

  IF OLD.effective_to IS NOT NULL AND NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
    RAISE EXCEPTION '店铺绑定的失效时间一经收口不可再改'
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_tax_shop_binding_immutable
  BEFORE UPDATE ON finance_tax_shop_binding
  FOR EACH ROW EXECUTE FUNCTION finance_tax_shop_binding_immutable();

-- ---------------------------------------------------------------------------
-- (h) 🔴 绑定的税档必须**真的存在税率记录**
-- ---------------------------------------------------------------------------
--   「把店铺绑到一个根本没有任何税率的税档上」是配置错误，不是有效配置：
--   取税率时必然落空 ⇒ 订单侧永远报「该时点无生效税率」，而配置侧看不出错在哪。
--   这里在数据层直接挡下，并写明「请先登记税率」。
CREATE OR REPLACE FUNCTION finance_tax_shop_binding_band_guard() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM finance_tax_rate r
     WHERE r.tenant_id = NEW.tenant_id
       AND r.tax_type = NEW.tax_type
       AND r.jurisdiction_code = NEW.jurisdiction_code
       AND r.tax_code = NEW.tax_code
  ) THEN
    RAISE EXCEPTION '该辖区该税种该税码下还没有任何税率记录，请先登记税率再绑定'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_tax_shop_binding_band_guard
  BEFORE INSERT ON finance_tax_shop_binding
  FOR EACH ROW EXECUTE FUNCTION finance_tax_shop_binding_band_guard();

-- ===========================================================================
-- 五、多租户（RLS isolation）+ 授权
-- ===========================================================================

ALTER TABLE finance_tax_rate ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_tax_rate FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_tax_rate
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_tax_shop_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_tax_shop_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_tax_shop_binding
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_tax_rate TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_tax_rate TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_tax_shop_binding TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_tax_shop_binding TO starcon_app;

-- ===========================================================================
-- 六、留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--  * 12.6.1.2 VAT/IOSS 申报数据（**同服务，未交付**）：本模块**只**产出税率记录与
--    「某时点命中哪一版」的取数能力，**不产出任何申报底稿 / 汇总数字 / 申报文件**。
--  * 9 订单（税额，**别的服务**）：订单侧经授权只读 API 取「某时点的生效税率」，
--    并把当时那**一版**快照进订单 —— 本模块**不写订单，也不改订单**。
--  * 16 合规（**别的服务**）：税务登记 / 申报日历归合规；本模块**不建登记表**。
--  * 12.6.2.1 发票管理（**同服务，未交付**）：行级税码税率归它；本模块只到
--    **店铺默认**税码这一层。
--
--  ⇒ 税率版本（finance_tax_rate）与店铺绑定（finance_tax_shop_binding）都是
--    **本模块的写入所有者**，每张表**只有一个写入所有者**。
-- ===========================================================================
