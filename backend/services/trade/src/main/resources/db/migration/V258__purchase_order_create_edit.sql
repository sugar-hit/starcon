-- 6.3.1.2 采购单创建与编辑（trade 服务）。
--
-- 蓝图 06-purchase-and-supply.md 原文：
--   职责：创建/编辑采购单：选择供应商、添加 SKU 行（带出报价与库存快照）、填写数量/采购单位/
--         单价/币种、约定交期与发货地址、备注与附件。支持草稿与提交。
--   输入：表单数据；expected_version；SKU 与供应商报价快照。
--   输出：采购单（草稿/已提交）；金额与税额计算结果。
--   关系：上游 = 6.2.2.2、3.6.2 报价；下游 = 6.3.1.3 审批、8 收货、12 应付。
--         **单价、汇率、单位换算在提交时冻结快照**，之后改报价不影响已提交采购单的成本核算。
--
-- 归属：**trade 服务**（卷 06 后端主责 = trade + catalog（party），取列表第一项 = trade）。
--
-- ===========================================================================
-- 一、本模块拥有什么、不拥有什么
-- ===========================================================================
--
-- ⚠️ **「采购单」这个聚合本身归 6.3.1.1**（V254 的 purchase_order / purchase_order_line /
--   purchase_order_event 三张表）。本迁移**一个字节都不改那三张表的结构**：
--   零 ALTER、零 DROP、零改既有触发器、零 CREATE（撞 relation already exists 会让整批 Flyway 挂掉）。
--
-- 本模块**自己新建**五张表（全部是新表 ⇒ 本迁移的 DDL 回归面只在"新建"这一侧）：
--
--   1. purchase_order_line_snapshot    行级快照：单位换算 / 报价 / 库存 / 税额口径（提交时冻结）
--   2. purchase_order_submit_snapshot  单级快照：本位币与汇率（提交时冻结）+ 税额口径
--   3. purchase_order_ship_address     发货地址（草稿期可改，提交后冻结）
--   4. purchase_order_attachment       附件引用（catalog 媒体库的逻辑引用 + 元数据快照）
--   5. purchase_order_draft_source     6.2.2.2 采购单草稿 → 本采购单的来源（追加式）
--
--   行的**可编辑集合**直接用 6.3.1.1 的 purchase_order_line（见第三节）——
--   所以草稿在 6.3.1.1 的列表上立刻就有行数与金额，不会显示成一张空单。
--
-- ===========================================================================
-- 二、🔴 三个最容易做错的口径，本文件显式钉死
-- ===========================================================================
--
-- ① **汇率快照在「提交」那一刻冻结，不在建单那一刻**。
--    蓝图加粗句原文：「单价、汇率、单位换算在提交时冻结快照，之后改报价不影响已提交采购单的成本核算」。
--    ⇒ 草稿期间 purchase_order.functional_currency / fx_snapshot_at 与
--      purchase_order_line.fx_rate_* 一律是 **NULL**（=「还没冻结」，不是「汇率是 1」）。
--    ⇒ 取不到汇率就是 NULL，**绝不**按 1:1 或固定汇率填一个数进去：
--      一个假汇率会让「跨币种合计」变成一个看起来能对账、其实对不上的数。
--    （6.3.1.1 的转单建单路径是在**建单**那一刻冻结的 —— 那是它自己的口径，
--      本模块按蓝图原文走「提交时冻结」，两者不混。）
--
-- ② **税额本模块不算**。
--    蓝图输出句写「金额与**税额**计算结果」，但税率的**权威在 12 财务**，
--    而 **finance 服务当前不存在**（backend/services/ 下只有 identity / catalog / channel /
--    crm / trade / warehouse）⇒ 本模块**不造税率表**、不猜税率、不跨库读 finance。
--    ⇒ tax_rate / tax_amount 一律 **NULL**，tax_status 恒为 'NOT_AVAILABLE'，
--      并在能力声明里写清归属模块（12 财务）。
--
-- ③ **单位换算（箱规）来自 catalog 的 3.1.2.4**。
--    采购单位 1 个 = 多少基础单位（quantityPerBase）是 catalog 的事实，
--    本模块**不猜是 1**（猜成 1 会让订量差一个数量级：1 箱 = 12 件时差 12 倍）。
--    取不到就是 **UNKNOWN + NULL**，由界面如实标「未取到箱规」。
--
-- ===========================================================================
-- 三、对 6.3.1.1 既有表的**两处最小补充**（为什么不是 0）
-- ===========================================================================
--
-- V254 收回了运行角色对 purchase_order / purchase_order_line 的 DELETE 权限
-- （理由：采购单是对供应商的正式承诺，删行会抹掉「这张单当时长什么样」的证据）。
-- 那条保护**针对的是「删掉一张采购单」**（它会级联抹掉留痕）。
-- 本模块要的是**另一种删除**：**草稿阶段删掉一行刚加错的 SKU**。
--
-- ⇒ 因此只做两件事，**都不改 6.3.1.1 的表结构**：
--
--   (1) GRANT DELETE ON purchase_order_line TO trade_app
--       —— **只放行行表**；purchase_order（单头）**仍然不可删** ⇒
--          6.3.1.1 的「采购单不提供 DELETE」保护原封不动。
--   (2) 新增一个 BEFORE DELETE 触发器 guard_purchase_order_line_delete_guard
--       —— 只有**草稿**的单才允许删行；已提交 / 已审批 / 已关闭的单的行删不掉。
--          这是 V254 自己的纵深防线写法（"CHECK 可被迁移脚本绕过，触发器不行"）的同款口径：
--          现有的 guard_purchase_order_line_amount 只挂在 INSERT OR UPDATE 上，
--          放行 DELETE 之后必须补上这一层，否则已关闭单的证据可以被直接 SQL 删掉。
--
-- ⚠️ 这两处是**纯增量**：不改任何既有列 / 约束 / 触发器，不改任何既有端点行为，
--   6.3.1.1 的集成测试一条都不受影响（它从不删行）。
--
-- ===========================================================================
-- 四、来源口径（🔴 两套来源不许混成一列）
-- ===========================================================================
--
-- purchase_order.source_type 的枚举在 V254 里就钉死了，只有两个值：
--   'PURCHASE_REQUEST_CONVERSION' —— 来自 6.2.2.1 的转单事实
--   'MANUAL'                     —— V254 明写「留给 6.3.1.2 手工建单」
--
-- ⇒ **手工建单写 'MANUAL'**（写错会把两套来源混成一套，事后分不清这单是人开的还是系统转的）。
-- ⇒ **由 6.2.2.2 的采购单草稿转来的单写 'PURCHASE_REQUEST_CONVERSION'**：
--   合并草稿的每一条行都来自 6.2.2.1 的申请行（V253 的 purchase_demand_merge_line_source
--   是 append-only 的行来源留痕），语义上就是「从采购申请转来的单」，
--   只是中间多了一道合并 —— 这道合并的**精确出处**记在本模块自己的
--   purchase_order_draft_source 表里（草稿 id / 批次 id / 背后的申请单 id 集合）。
--
-- 🔴 **草稿 id 绝不塞进 purchase_order_line.source_conversion_id**：
--   那一列指向的是 **6.2.2.1 的转单事实**（purchase_request_conversion），
--   而且它带 UNIQUE(tenant_id, source_conversion_id) ⇒ 塞草稿 id 会把两种语义混成一列，
--   还可能误撞唯一键。草稿来源只记在 purchase_order_draft_source。
--
-- ===========================================================================
-- 五、多租户
-- ===========================================================================
--
-- 五张业务表一视同仁 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + GRANT。
-- 租户由 backend/libraries/tenancy 在事务内设置（app.current_tenant），
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- ===========================================================================
-- 1. 行级快照（提交时冻结）
-- ===========================================================================
CREATE TABLE purchase_order_line_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- **逻辑引用** 6.3.1.1 的 purchase_order（**不建外键**：V254 的表归它所有，
  -- 本仓先例见 V254 对 purchase_arrival_tracking 的处理 —— 同库同租户，按 id 关联即可）。
  order_id uuid NOT NULL,
  line_no int NOT NULL CHECK(line_no > 0),

  -- ---- SKU 快照（catalog 独立库 ⇒ 逻辑引用 + 展示快照）----
  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 🔴 单位换算快照（3.1.2.4；**取不到就是没有，绝不猜成 1**）----
  base_unit_code varchar(32) NOT NULL DEFAULT '',
  purchase_unit_code varchar(32) NOT NULL DEFAULT '',
  quantity_per_base numeric(19,6) CHECK(quantity_per_base IS NULL OR quantity_per_base > 0),
  base_quantity numeric(19,6) CHECK(base_quantity IS NULL OR base_quantity >= 0),
  conversion_status varchar(24) NOT NULL DEFAULT 'UNKNOWN'
      CHECK(conversion_status IN ('CATALOG_UNIT_CONVERSION','MANUAL','UNKNOWN')),

  -- ---- 🔴 报价快照（3.6.2.1；歧义时**不替用户挑**，提交直接 409）----
  quote_id uuid,
  quote_supplier_id uuid,
  quote_supplier_name varchar(200) NOT NULL DEFAULT '',
  quote_currency char(3) CHECK(quote_currency IS NULL OR quote_currency ~ '^[A-Z]{3}$'),
  quote_purchase_unit_code varchar(32) NOT NULL DEFAULT '',
  quote_moq_quantity numeric(19,6) CHECK(quote_moq_quantity IS NULL OR quote_moq_quantity >= 0),
  quote_lead_time_days int CHECK(quote_lead_time_days IS NULL OR quote_lead_time_days >= 0),
  quote_unit_price numeric(19,6) CHECK(quote_unit_price IS NULL OR quote_unit_price >= 0),
  quote_valid_from date,
  quote_valid_to date,
  -- 单价来源（与 purchase_order_line.price_source 同一套词表）。
  price_source varchar(24) NOT NULL DEFAULT 'MANUAL'
      CHECK(price_source IN ('REQUEST_ESTIMATE','QUOTE','MANUAL')),

  -- ---- 库存快照（08 仓储；**取不到就是没有**，不填 0 冒充「一件都没有」）----
  stock_available_qty numeric(19,6) CHECK(stock_available_qty IS NULL OR stock_available_qty >= 0),
  stock_sellable_qty numeric(19,6) CHECK(stock_sellable_qty IS NULL OR stock_sellable_qty >= 0),
  stock_snapshot_at timestamptz,
  stock_status varchar(24) NOT NULL DEFAULT 'UNAVAILABLE'
      CHECK(stock_status IN ('CAPTURED','UNAVAILABLE')),

  -- ---- 🔴 税额（12 财务才有税率的权威 ⇒ 本模块**不算**，恒为 NULL）----
  tax_rate numeric(9,6),
  tax_amount numeric(19,6),
  tax_status varchar(24) NOT NULL DEFAULT 'NOT_AVAILABLE'
      CHECK(tax_status IN ('NOT_AVAILABLE','NOT_APPLICABLE')),

  -- 冻结时刻：这一行的价格 / 汇率 / 箱规是**这一刻**的。
  snapshot_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_id,line_no)
);

COMMENT ON TABLE purchase_order_line_snapshot IS
  '6.3.1.2 采购单行快照：提交时冻结的单位换算 / 报价 / 库存 / 税额口径；追加式';

-- ===========================================================================
-- 2. 单级提交快照（追加式）
-- ===========================================================================
CREATE TABLE purchase_order_submit_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,

  -- ---- 🔴 本位币与汇率快照（提交那一刻冻结；取不到就是没有）----
  functional_currency char(3)
      CHECK(functional_currency IS NULL OR functional_currency ~ '^[A-Z]{3}$'),
  fx_rate_to_functional numeric(19,8)
      CHECK(fx_rate_to_functional IS NULL OR fx_rate_to_functional > 0),
  fx_rate_base_currency char(3)
      CHECK(fx_rate_base_currency IS NULL OR fx_rate_base_currency ~ '^[A-Z]{3}$'),
  fx_rate_effective_date date,
  fx_status varchar(24) NOT NULL DEFAULT 'NOT_APPLICABLE'
      CHECK(fx_status IN ('CAPTURED','UNAVAILABLE','NOT_APPLICABLE')),

  -- ---- 🔴 税额口径（本模块不算；归属 12 财务）----
  tax_rate numeric(9,6),
  tax_amount numeric(19,6),
  tax_status varchar(24) NOT NULL DEFAULT 'NOT_AVAILABLE'
      CHECK(tax_status IN ('NOT_AVAILABLE','NOT_APPLICABLE')),
  tax_note text NOT NULL DEFAULT '',

  -- ---- 提交留痕 ----
  from_status varchar(20) NOT NULL DEFAULT 'DRAFT',
  to_status varchar(20) NOT NULL DEFAULT 'PENDING_APPROVAL',
  submitted_by uuid NOT NULL,
  submitted_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '' CHECK(length(note) <= 2000),
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一张单提交多次是不存在的（状态机钉死 DRAFT → PENDING_APPROVAL 只有一次），
  -- 但这个唯一键保证**同一张单不会有两份互相矛盾的冻结快照**。
  UNIQUE(tenant_id,order_id)
);

COMMENT ON TABLE purchase_order_submit_snapshot IS
  '6.3.1.2 采购单提交快照：提交那一刻冻结的本位币 / 汇率 / 税额口径；追加式';

-- ===========================================================================
-- 3. 发货地址（草稿期可改，提交后冻结）
-- ===========================================================================
-- ⚠️ 地址是**表单内容**：本仓的地址台账（3.6.3.1）在 catalog 独立库，
--   而 warehouse 的仓档案里**没有街道地址字段** ⇒ 本模块不引用任何外部地址源，
--   地址由采购员在表单里填，提交时冻结。
--   「填错了就改」只对草稿成立：提交后地址是对供应商的收货承诺，改它要走变更单。
CREATE TABLE purchase_order_ship_address (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,

  recipient_name varchar(200) NOT NULL CHECK(btrim(recipient_name) <> ''),
  recipient_phone varchar(64) NOT NULL DEFAULT '',
  country_code varchar(2) NOT NULL DEFAULT '' CHECK(country_code ~ '^[A-Z]{2}$'),
  province varchar(100) NOT NULL DEFAULT '',
  city varchar(100) NOT NULL DEFAULT '',
  district varchar(100) NOT NULL DEFAULT '',
  detail varchar(500) NOT NULL CHECK(btrim(detail) <> ''),
  postal_code varchar(32) NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '' CHECK(length(note) <= 1000),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一张采购单一个发货地址（表单里就是一份）。
  UNIQUE(tenant_id,order_id)
);

COMMENT ON TABLE purchase_order_ship_address IS
  '6.3.1.2 采购单发货地址：草稿期可改，提交后由触发器冻结';

-- ===========================================================================
-- 4. 附件引用（catalog 媒体库：逻辑引用 + 元数据快照）
-- ===========================================================================
-- ⚠️ 素材本体在 **catalog 服务**（3.4.1.1 的 /catalog/media）的独立库里
--   ⇒ 逻辑引用 + 展示元数据快照，**不建跨库外键、不跨库写**。
--   快照的是**元数据**（文件名 / 类型 / 大小 / 存储键），文件本体仍在 catalog。
CREATE TABLE purchase_order_attachment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,

  -- ---- catalog 媒体库的逻辑引用 ----
  media_id uuid NOT NULL,
  media_name varchar(200) NOT NULL DEFAULT '',
  media_type varchar(64) NOT NULL DEFAULT '',
  mime_type varchar(128) NOT NULL DEFAULT '',
  byte_size numeric(19,6) CHECK(byte_size IS NULL OR byte_size >= 0),
  storage_key varchar(500) NOT NULL DEFAULT '',
  original_filename varchar(300) NOT NULL DEFAULT '',

  -- ---- 本模块自己的分类与说明 ----
  attachment_type varchar(24) NOT NULL DEFAULT 'OTHER'
      CHECK(attachment_type IN ('CONTRACT','QUOTE','ORDER','OTHER')),
  note text NOT NULL DEFAULT '' CHECK(length(note) <= 1000),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一个素材在一张单上只挂一次（重复挂载没有意义）。
  UNIQUE(tenant_id,order_id,media_id)
);

COMMENT ON TABLE purchase_order_attachment IS
  '6.3.1.2 采购单附件：catalog 媒体库的逻辑引用 + 元数据快照；草稿期可改，提交后冻结';

-- ===========================================================================
-- 5. 6.2.2.2 采购单草稿 → 本采购单（追加式来源留痕）
-- ===========================================================================
-- ⚠️ V253 的 purchase_demand_merge_draft 的状态枚举是 ('DRAFT','CONVERTED')，
--   且 guard_demand_merge_draft_guard **允许** status → CONVERTED
--   （它只拦「供应商 / 批次 / 目标仓 / 法人 / 币种改写」与「批次定稿后改内容」）。
--   ⇒ 本模块把草稿置为 CONVERTED 是被上游显式授权的。
--
-- 🔴 V253 的 purchase_demand_merge_line_source / purchase_demand_merge_unmerged /
--   purchase_demand_batch_adjustment 三张是 **append-only 留痕表**
--   （guard_demand_merge_append_only 触发器直接 RAISE 23514）—— 本模块**一个字都不碰**，
--   只 SELECT 读。
CREATE TABLE purchase_order_draft_source (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,

  -- ---- 6.2.2.2 的采购单草稿（**逻辑引用**，不建外键：那张表归 6.2.2.2 所有）----
  source_draft_id uuid NOT NULL,
  source_draft_no varchar(64) NOT NULL DEFAULT '',
  source_batch_id uuid NOT NULL,
  source_batch_no varchar(64) NOT NULL DEFAULT '',

  -- ---- 合并草稿背后的 6.2.2.1 申请单（一个合并批次可能跨多张申请单）----
  source_request_ids uuid[] NOT NULL DEFAULT '{}',

  converted_by uuid NOT NULL,
  converted_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 🔴 一个合并草稿只能转成一张采购单（转两次 = 对供应商承诺两次）。
  UNIQUE(tenant_id,source_draft_id),
  UNIQUE(tenant_id,order_id)
);

COMMENT ON TABLE purchase_order_draft_source IS
  '6.3.1.2 由 6.2.2.2 采购单草稿转单时的来源留痕；追加式，一个草稿只能转一次';

-- ===========================================================================
-- 6. 触发器
-- ===========================================================================
-- 6.1 🔴 **只有草稿能改**：地址与附件在提交后冻结
--     （提交后地址是对供应商的收货承诺，改它要走变更单 —— 与 6.3.1.3 的同一纪律）。
CREATE FUNCTION guard_purchase_order_editor_draft_only() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  target uuid;
  owner_tenant uuid;
  head_status varchar(20);
BEGIN
  IF TG_OP = 'DELETE' THEN
    target := OLD.order_id;
    owner_tenant := OLD.tenant_id;
  ELSE
    target := NEW.order_id;
    owner_tenant := NEW.tenant_id;
  END IF;

  SELECT o.status INTO head_status
    FROM purchase_order o
   WHERE o.tenant_id = owner_tenant AND o.id = target;

  IF head_status IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION '采购单「%」当前状态是 %，它的发货地址与附件不可再改（只有草稿能改；要改请走变更单）',
      target, coalesce(head_status, '不存在')
      USING ERRCODE='23514';
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER purchase_order_ship_address_draft_only
  BEFORE INSERT OR UPDATE OR DELETE ON purchase_order_ship_address
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_editor_draft_only();

CREATE TRIGGER purchase_order_attachment_draft_only
  BEFORE INSERT OR UPDATE OR DELETE ON purchase_order_attachment
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_editor_draft_only();

-- 6.2 快照与来源留痕是 **append-only**：不可改写、不可删。
--     「提交那一刻的价与汇率」与「这单是从哪张合并草稿来的」是事后追溯的凭据。
CREATE FUNCTION guard_purchase_order_editor_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：采购单的提交快照与草稿来源是「当时定了什么」的举证凭据',
    TG_TABLE_NAME, TG_OP
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER purchase_order_line_snapshot_append_only
  BEFORE UPDATE OR DELETE ON purchase_order_line_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_editor_append_only();

CREATE TRIGGER purchase_order_submit_snapshot_append_only
  BEFORE UPDATE OR DELETE ON purchase_order_submit_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_editor_append_only();

CREATE TRIGGER purchase_order_draft_source_append_only
  BEFORE UPDATE OR DELETE ON purchase_order_draft_source
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_editor_append_only();

-- 6.3 🔴 **补上 6.3.1.1 的删除防线**：V254 收回了运行角色对 purchase_order_line 的 DELETE
--     （采购单是对供应商的正式承诺，删行会抹掉证据），本迁移为了「草稿阶段删行」
--     放行了行表的 DELETE（**单头 purchase_order 仍然不可删** ⇒ 6.3.1.1 的保护不变）。
--     ⇒ 必须在这里补上纵深防线：**只有草稿的单能删行**。
--     现有的 guard_purchase_order_line_amount 只挂在 INSERT OR UPDATE 上，管不到 DELETE。
CREATE FUNCTION guard_purchase_order_line_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_status varchar(20); head_no varchar(64);
BEGIN
  SELECT o.status, o.order_no INTO head_status, head_no
    FROM purchase_order o
   WHERE o.tenant_id = OLD.tenant_id AND o.id = OLD.order_id;
  IF head_status IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION '采购单「%」当前状态是 %，它的行不可删除（只有草稿能改行；已提交的采购单要改数量与单价请走变更单）',
      coalesce(head_no, OLD.order_id::text), coalesce(head_status, '不存在')
      USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER purchase_order_line_delete_guard
  BEFORE DELETE ON purchase_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_line_delete_guard();

-- ===========================================================================
-- 7. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 行快照：按单读（详情页的「提交时冻结了什么」面板）。
CREATE INDEX purchase_order_line_snapshot_by_order ON purchase_order_line_snapshot(tenant_id, order_id, line_no);
-- 行快照：按 SKU 反查（「这个 SKU 在哪些单上被冻结过什么价」）。
CREATE INDEX purchase_order_line_snapshot_by_sku ON purchase_order_line_snapshot(tenant_id, sku_id, order_id);
-- 提交快照：按单读。
CREATE INDEX purchase_order_submit_snapshot_by_order ON purchase_order_submit_snapshot(tenant_id, order_id);
-- 地址 / 附件：按单读。
CREATE INDEX purchase_order_ship_address_by_order ON purchase_order_ship_address(tenant_id, order_id);
CREATE INDEX purchase_order_attachment_by_order ON purchase_order_attachment(tenant_id, order_id, created_at);
-- 草稿来源：按单读 + 按草稿反查（谁转了这张草稿）。
CREATE INDEX purchase_order_draft_source_by_order ON purchase_order_draft_source(tenant_id, order_id);
CREATE INDEX purchase_order_draft_source_by_draft ON purchase_order_draft_source(tenant_id, source_draft_id);

-- ===========================================================================
-- 8. 多租户：RLS + 授权（与 trade V1 / V92 / V234 / V238 / V242 / V246 / V254 同一套写法）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_order_line_snapshot','purchase_order_submit_snapshot',
    'purchase_order_ship_address','purchase_order_attachment',
    'purchase_order_draft_source'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_order_line_snapshot,purchase_order_submit_snapshot,
  purchase_order_ship_address,purchase_order_attachment,
  purchase_order_draft_source TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_order_line_snapshot,purchase_order_submit_snapshot,
  purchase_order_ship_address,purchase_order_attachment,
  purchase_order_draft_source TO starcon_app;
-- 收回三张留痕表的改写权（"授权在前、收回在后"，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去）。
REVOKE UPDATE,DELETE ON
  purchase_order_line_snapshot,purchase_order_submit_snapshot,
  purchase_order_draft_source FROM trade_app;
-- 采购单**不提供 DELETE**（V254 的保护原封不动）：本模块只放行了**行表**的 DELETE
-- （草稿阶段删行），单头 purchase_order 依然不可删 ⇒ 级联抹掉留痕这条路依然封死。
GRANT DELETE ON purchase_order_line TO trade_app;
GRANT DELETE ON purchase_order_line TO starcon_app;

-- ---------------------------------------------------------------------------
-- 9. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **只新建 5 张表 + 3 个函数 + 1 个触发器**（在本模块自己的表上），
--     **零 ALTER、零 DROP、零改既有触发器** ⇒ 对 V254 三张表的**结构**回归面 = 0。
--   * 对 V254 的 purchase_order_line 只有**两处最小补充**（见文件头第三节）：
--     一个 GRANT（只放行行表，单头仍不可删）+ 一个 BEFORE DELETE 触发器（只允许删草稿的行）。
--     两处都是纯增量，不改任何列 / 约束 / 既有触发器，6.3.1.1 的行为与测试零变化。
--   * 6.2.2.2（V253）与 6.2.2.1（V246）的表只 SELECT，不 ALTER、不加约束、不建外键。
--   * 对 warehouse / catalog 的数据**只经授权 HTTP API 读**：跨库 FK / 跨服务 SQL 均被
--     AGENTS.md 禁止。
--   * 新增函数全部带 purchase_order_editor_ / guard_purchase_order_ 前缀，与既有函数名不冲突。
