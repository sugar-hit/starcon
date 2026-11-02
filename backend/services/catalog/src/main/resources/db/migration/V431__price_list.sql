-- 5.1.1.1 价目表列表：价目表（一张表 = 一个币种 + 一组适用范围 + 一个生效区间）。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：管理价目表（如"北美自发货零售价"），每张表绑定一个币种、适用法人/店铺/站点、
--             生效区间，展示表内价目项数量与最近更新时间。
--   **输入**：价目表增删改；币种与适用范围选择；生效区间设置。
--   **输出**：价目表记录 + 覆盖范围摘要。
--   **关系**：上游 = —（价目表自身的增删改只依赖法人/店铺/站点主数据，卷 02/03 已交付）；
--             下游 = 5.1.1.2 价目项、5.3.2.1 定价模板。
--             **一价目一币种**是硬约束——混币种的价目表会让利润计算失效。
--
-- ============================================================ 第一节：这张表归谁
--
-- 蓝图 05 卷首写：「`catalog` 服务拥有 `price_list` `price_list_item`」。
-- 本迁移是 `price_list` 的**唯一写入所有者**。
--
-- 🔴 **本迁移只建 `price_list` 与 `price_list_scope` 两张表**：
--   * `price_list` —— 一张价目表（表头）。
--   * `price_list_scope` —— 一条适用对象（法人 / 店铺 / 站点）。
-- **不建 `price_list_item`**：价目项是 5.1.1.2 的写入所有权（蓝图 05 卷首写明
-- catalog 拥有 price_list 与 price_list_item **两张**表，5.1.1.2 拥有后者）。
-- 两边都建 ⇒ 同一张事实表出现两个写入所有者（AGENTS.md「模块边界纪律」）。
-- 本仓既有的 5.3.2.2 价格规则（V236）与 5.5.2.1 异常告警（V222）都明确写着
-- 「本仓没有 price_list 表」—— 本迁移交付之后，那两处的措辞需要各自更新，
-- 但**那两处的文件不在本车道的所有权范围内**，本迁移不改它们。
--
-- ============================================================ 第二节：🔴 一价目一币种 —— 结构性落法
--
-- 蓝图加粗句：「**一价目一币种**是硬约束——混币种的价目表会让利润计算失效。」
--
-- 本仓里最自然的表形状是「价目表头 + 价目项」两张表，价目项上挂单价。
-- 如果价目项自带币种列，那么「一张表里混了 USD 与 CNY」在结构上就是**能写进去的**，
-- 而利润计算（5.3.1.1）会把它折成某个币种 —— 那正是这条硬约束要防的事故。
--
-- ⇒ 本模块采取的落法是**把币种钉在表头上**：
--   * `price_list.currency` 是**单列**，且 NOT NULL ⇒ 一张表**结构上**只有一个币种；
--   * 列级命名 CHECK `price_list_one_currency`：`currency ~ '^[A-Z]{3}$'`
--     ⇒ 「USD,CNY」「USDCNY」「usd」这类混币种 / 非法写法**在 DB 层**写不进去
--     （报错 SQLSTATE 23514，约束名 `price_list_one_currency`）；
--   * **给下游 5.1.1.2 的结构性约定**（写在第三节）：`price_list_item` **不得自带币种列** ——
--     价目项的币种只能来自 `price_list.currency`。这一条是本约束在下游的落法。
--
-- 🔴 本表**不**提供「一张表挂多个币种」的任何表达方式：没有 currency 数组列、
-- 没有 currency 明细表、没有 JSON 列。蓝图要的是硬约束，不是可配置的宽松模式。
--
-- ============================================================ 第三节：给 5.1.1.2（价目项）的接口约定
--
-- 5.1.1.2 交付时**必须**满足这三条，否则「一价目一币种」会在下游破掉：
--   1. `price_list_item` **不得**有 `currency` 列（币种来自 `price_list.currency`）；
--   2. `price_list_item.price_list_id` 指向本表的 `id`（**稳定主键**，不随改名而变）；
--   3. `price_list_item` 上建 AFTER INSERT/UPDATE/DELETE 触发器调用本迁移提供的
--      `price_list_sync_item_count()`，把价目项数量写回 `price_list.item_count`
--      （表内价目项数量的唯一来源，见第五节）。
--
-- 本迁移**只提供函数，不建触发器**：触发器必须建在 5.1.1.2 自己的表上，
-- 而那张表还不存在（第一节）。函数在这里建好 ⇒ 5.1.1.2 只需一句
-- `CREATE TRIGGER ... EXECUTE FUNCTION price_list_sync_item_count();`。
--
-- ============================================================ 第四节：适用范围（法人 / 店铺 / 站点）
--
-- 蓝图：「每张表绑定一个币种、适用法人/店铺/站点、生效区间」。
-- 三层的取值形状不同（法人在 identity 的 legal_entity，店铺与站点在 channel 的
-- shop / marketplace），跨服务 FK 被 AGENTS.md 禁止 ⇒ 全部用**编码**而不是外键，
-- 与 5.3.2.1 定价模板的 `pricing_template_scope` 同一手法。
--
--   | scope_type   | scope_ref 的含义        | 来源                          |
--   |--------------|-------------------------|-------------------------------|
--   | LEGAL_ENTITY | 法人编码                | 17.1.2.2 identity legal_entity|
--   | SHOP         | 店铺编码                | 2.3.1.1 channel shop          |
--   | SITE         | 站点码（marketplace code）| 2.1.1.1 channel marketplace   |
--
-- 🔴 **站点必须带平台码**：channel 的 marketplace 一定挂在某个 platform 下
-- （`marketplace.platform_id` NOT NULL），所以「站点属于哪个平台」是事实而不是猜测。
-- 平台码冗余在范围行上，是为了覆盖摘要能说清「这个价目表管的是哪个平台的价格」。
--
-- 🔴 **适用范围为空 = 适用于全租户**（显式语义，不是漏填）：
-- 与 5.3.2.1 定价模板的适用对象同一约定。没有范围的价目表对全租户生效，
-- 这一点会写在页面上与能力声明里 —— 运营必须知道自己这张表是不是「全局价目表」。
--
-- 🔴 **5.1.2.1 优先级与冲突检测是本表的守门员，不是本表的前置**：
-- 蓝图原文写着「5.1.2.1 优先级与冲突检测是本表的守门员（保存时校验冲突、
-- 同 SKU 冲突即拒）」，同时又把它列成本表的**下游**（因为两者互相声明上游会形成死锁）。
-- ⇒ 本迁移**不实现**任何冲突检测或优先级：**本表还没有价目项**（第一节），
-- 而「同 SKU + 同店铺 + 同站点 + 同时段生效」这个判据的另一半在 5.1.1.2 手里。
-- 本表只保证自己的硬约束（一个币种 + 一个合法区间 + 范围内编码真实存在）。
--
-- ============================================================ 第五节：表结构
--
-- 5.1 表头
CREATE TABLE price_list (
  tenant_id uuid NOT NULL,
  -- 稳定主键：5.1.1.2 的价目项引用它，改名不改引用。
  id uuid PRIMARY KEY,

  -- 编码：租户内唯一。运营按编码找表（「北美自发货零售价」）。
  code varchar(64) NOT NULL,
  name varchar(200) NOT NULL,
  description text NOT NULL DEFAULT '',

  -- 🔴 币种：**一张表一个币种**（第二节）。单列 + NOT NULL + 命名 CHECK。
  -- 列宽刻意留到 16（而不是 3）：PG 先判列宽再判 CHECK，列宽收成 3 的话
  -- 「USD,CNY」会报一句看不出来的 `value too long for type character varying(3)`，
  -- 而**命名约束**才是给运维看的那个名字。列宽放宽后，第一道闸就是
  -- `price_list_one_currency` —— 混币种写法的报错里能一眼看出是哪条规矩挡的。
  currency varchar(16) NOT NULL
    CONSTRAINT price_list_one_currency CHECK (currency ~ '^[A-Z]{3}$'),

  -- 生效区间：**半开区间** [valid_from, valid_to)。
  --   valid_from 含（起点那一刻已生效），valid_to 不含（终点那一刻已失效）。
  --   valid_to 为 NULL = 长期有效（没有上限不是"没填"，是一个显式语义）。
  valid_from timestamptz NOT NULL,
  valid_to timestamptz,

  -- 停用 = 这张表不再参与定价。停用**不**删区间：历史价目项仍然引用这张表。
  enabled boolean NOT NULL DEFAULT true,

  -- 表内价目项数量。由 5.1.1.2 的价目项写入经触发器维护（第三节）。
  -- 🔴 价目项模块未交付前它恒为 0 —— 那是**真实的 0**（表里确实还没有价目项），
  --    不是"取不到"。所以服务层**不读** price_list_item 表（那张表还不存在）。
  item_count integer NOT NULL DEFAULT 0 CHECK(item_count >= 0),

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 编码在租户内唯一（DB 层兜底；服务层给可读原因，409）。
  UNIQUE(tenant_id, code),

  CHECK(btrim(code) <> '' AND btrim(name) <> ''),
  -- 半开区间必须非空且起点早于终点（valid_to 为 NULL = 长期有效）。
  CHECK(valid_to IS NULL OR valid_to > valid_from)
);

-- 台账页（默认按最近改过的排序）。
CREATE INDEX price_list_by_updated ON price_list(tenant_id, updated_at DESC, id);
-- 「这个币种的价目表」（跨币种混算的第一道筛查）。
CREATE INDEX price_list_by_currency ON price_list(tenant_id, currency);
-- 「此刻生效的价目表」。
CREATE INDEX price_list_by_validity ON price_list(tenant_id, valid_from, valid_to);

-- 5.2 适用范围
CREATE TABLE price_list_scope (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 所属价目表。**不建外键**（第七节），引用完整性由触发器 guard 保证。
  price_list_id uuid NOT NULL,

  -- 适用对象类型：法人 / 店铺 / 站点（第四节）。
  scope_type text NOT NULL CHECK(scope_type IN ('LEGAL_ENTITY','SHOP','SITE')),
  -- 取值：法人编码 / 店铺编码 / 站点码。
  scope_ref varchar(64) NOT NULL,
  -- 显示名快照：编码改了以后老范围行仍能显示当时的名称。
  scope_name varchar(200) NOT NULL DEFAULT '',
  -- 平台码：站点必填（第四节），店铺可填，法人必空。
  platform_code varchar(32),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一张价目表里同一个对象不重复登记。
  UNIQUE(tenant_id, price_list_id, scope_type, scope_ref),

  CHECK(btrim(scope_ref) <> ''),
  CHECK(platform_code IS NULL OR btrim(platform_code) <> ''),
  -- 法人没有平台；站点必须有平台（第四节的渠道事实）。
  CHECK(CASE
    WHEN scope_type = 'SITE' THEN platform_code IS NOT NULL
    WHEN scope_type = 'LEGAL_ENTITY' THEN platform_code IS NULL
    ELSE TRUE
  END)
);

-- 取一张价目表的适用范围（按类型 + 编码）。
CREATE INDEX price_list_scope_by_list
  ON price_list_scope(tenant_id, price_list_id, scope_type, scope_ref);
-- 按范围反查价目表（「这个店铺上有哪些价目表」—— 列表筛选的主查询路径）。
CREATE INDEX price_list_scope_by_ref
  ON price_list_scope(tenant_id, scope_type, scope_ref, price_list_id);

-- ============================================================ 第六节：价目项数量维护函数（给 5.1.1.2 用）
--
-- 🔴 本函数**现在还没有触发器**（第三节）：触发器必须建在 5.1.1.2 的
-- `price_list_item` 上，而那张表还不存在。
--
-- 函数在运行时才解析 `price_list_item`（plpgsql 惰性解析），所以它现在可以安全地
-- 存在于库里 —— 等 5.1.1.2 建表 + 建触发器，它才开始工作。
--
-- 🔴 计数口径 = **该价目表当前真实存在的价目项行数**（含阶梯价的每一档 ——
-- 阶梯价也是一条价目项行；5.1.1.2 交付后由它自己决定行的粒度，本函数只数行）。
-- 写回时同时更新 `updated_at` ⇒ 「最近更新时间」= 这张表（含它的价目项）最近一次变化的时间。
CREATE FUNCTION price_list_sync_item_count() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    UPDATE price_list p
       SET item_count = (SELECT count(*) FROM price_list_item i
                          WHERE i.tenant_id = OLD.tenant_id
                            AND i.price_list_id = OLD.price_list_id),
           updated_at = now()
     WHERE p.tenant_id = OLD.tenant_id AND p.id = OLD.price_list_id;
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.price_list_id <> NEW.price_list_id THEN
    -- 价目项被搬到别的价目表：两张表的计数都要重算。
    UPDATE price_list p
       SET item_count = (SELECT count(*) FROM price_list_item i
                          WHERE i.tenant_id = OLD.tenant_id
                            AND i.price_list_id = OLD.price_list_id),
           updated_at = now()
     WHERE p.tenant_id = OLD.tenant_id AND p.id = OLD.price_list_id;
  END IF;
  UPDATE price_list p
     SET item_count = (SELECT count(*) FROM price_list_item i
                        WHERE i.tenant_id = NEW.tenant_id
                          AND i.price_list_id = NEW.price_list_id),
         updated_at = now()
   WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.price_list_id;
  RETURN NEW;
END $$;

-- ============================================================ 第七节：引用校验用触发器，不用外键
--
-- 上一批（batch-37）踩过的坑：给**既有表**加 FOREIGN KEY 会打破同服务既有测试的
-- TRUNCATE ⇒ 整批用例 Errors ⇒ L2 直接红。本迁移因此**零个外键**：
-- 适用对象 → 价目表，用触发器校验（与 V227 / V231 的 guard 同一手法）：
-- 触发器的判据与外键等价（引用不存在 ⇒ 报 23514），但**不参与 TRUNCATE 的依赖图**。
CREATE FUNCTION guard_price_list_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM price_list p
     WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.price_list_id) THEN
    RAISE EXCEPTION 'Price list scope % must reference an existing price list',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER price_list_scope_guard
  BEFORE INSERT OR UPDATE ON price_list_scope
  FOR EACH ROW EXECUTE FUNCTION guard_price_list_scope();

-- ============================================================ 第八节：RLS 与授权
--
-- 两张表都是租户级业务表（价目表是这个租户自己的定价策略）：
-- `tenant_id uuid NOT NULL` + `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`
-- + isolation 策略（照抄 V231 第九节的 `DO $$ FOREACH` 写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的价目表，
-- 而价目表里写着这个租户的币种、适用法人/店铺/站点与定价区间 ——
-- 那是一条看不见的越权面（别人的北美零售价表与生效区间）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'price_list',
    'price_list_scope'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_scope TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_scope TO starcon_app;
