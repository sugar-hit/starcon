-- 11.5.2.1 质量分析（卷 11 售后与退货 / L3 11.5.2 质量分析）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：质量问题专项分析：缺陷类型分布、批次质量趋势、供应商质量对比、缺陷照片聚类、
--         支持开箱率与质量成本估算。
--   输入：质检结论与退货原因；供应商与批次维度。
--   输出：质量报表 + 责任分布 + 改进清单。
--   关系：上游 = 11.1.2.2、11.2.3.1；下游 = 6.1.1.3 评级、13 分析。
--   **质量成本（退货物流 + 损耗 + 差评影响）应量化**，否则改进动力不足。
--
-- 归属：**trade 服务**。输入句的两项上游（11.2.3.1 质检结论 = V179、11.1.2.2 归因 = V180）
--   **都在本服务**，所以本模块是**同库只读聚合**，不碰任何跨服务路径。
--   供应商主数据在 catalog 独立库（party_supplier_profile / party_role），
--   按 AGENTS.md 模块边界纪律**不 join、不建视图、不建跨库 FK**，
--   改用 trade 侧的**供应商引用镜像表**（见第三节）。
--
-- 本迁移**只新建四张表与触发器、零 ALTER 零 DROP 既有表**：
--   * return_quality_inspection / return_quality_check_result / return_quality_category
--     / return_quality_template_item（V179，11.2.3.1 已交付）**只读**：
--     缺陷类型分布、批次质量趋势、开箱率的**事实来源**。
--     ⚠️ 同库同服务，理论上加不加 FK 都安全，但**刻意不加** ——
--     加 FK 会让本迁移对同批其他车道的表产生 DDL 依赖。
--   * product_issue_attribution / product_issue_photo / product_issue_lot_flag
--     （V180，11.1.2.2 已交付）**只读**：供应商质量对比、缺陷照片聚类、责任分布、改进清单。
--   * return_receipt_line（V172，11.2.2.1 已交付）**只读**：退货物流成本的件数基数。
-- ⇒ **跨车道 DDL 回归面 = 0**（同批另有三条车道也在往 trade 落迁移）。

-- ======================================================================
-- 第一节：quality_defect_observation —— 缺陷观察（**本模块的写入所有者**）
-- ======================================================================
--
-- 为什么需要这张表：蓝图职责句点名要「**支持开箱率**」，而开箱率的分子是
-- 「**买家开箱那一刻就发现的缺陷**」—— 这个事实**上游一张表都没有**：
--   * V179 记的是「质检员在仓库判了什么」，
--   * V180 记的是「这事该谁负责」，
--   * V172 return_receipt_line.goods_condition 记的是「货到我们手上时什么状态」。
-- 三者都发生在**实物回到卖家之后**，而开箱率问的是**缺陷在买家手上就已经存在**。
-- 反过来「拿入库状态反推开箱率」是把两个不同的时点当同一个 ——
-- 买家开箱发现 3 件、入库时其中 1 件已被人修好，内含推法会得出完全不同的数。
--
-- **为什么是 1:1 挂在一条 FAIL 检项上**（而不是自由记录）：
--   缺陷类型分布的事实来源是 V179 的 **FAIL 检项**（判 FAIL 才是缺陷，PASS 不是）。
--   若允许一条 FAIL 检项对应多条观察，「缺陷类型分布」和「开箱率」两个口径立刻分叉 ——
--   而 11.1.2.1 的蓝图早就写过「原因口径不统一会让退货率分析失真」。
--   1:1 之后，分布的分母恒等于 FAIL 检项数，开箱率的分子恒是其中被标记为开箱发现的那部分。
--
-- **本表刻意允许「作废后重记」**：作废是留痕，不是删除。重记走新行，
-- 活行仍然只有一条（部分唯一索引），分布口径不会被作废行污染。
CREATE TABLE quality_defect_observation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  observation_no varchar(32) NOT NULL,
  inspection_id uuid NOT NULL,
  check_result_id uuid NOT NULL,
  attribution_id uuid,
  defect_code varchar(48) NOT NULL CHECK(btrim(defect_code) <> ''),
  defect_name varchar(128) NOT NULL CHECK(btrim(defect_name) <> ''),
  defect_dimension varchar(16) NOT NULL CHECK(defect_dimension IN (
    'APPEARANCE','FUNCTION','ACCESSORY','PACKAGING'
  )),
  discovery_stage varchar(24) NOT NULL CHECK(discovery_stage IN (
    'BUYER_UNBOXING','DELIVERY_SIGNATURE','WAREHOUSE_INTAKE','POST_USE'
  )),
  discovered_at timestamptz NOT NULL,
  affected_qty numeric(19,6) NOT NULL CHECK(affected_qty > 0),
  severity varchar(16) NOT NULL CHECK(severity IN (
    'MINOR','MODERATE','MAJOR','CRITICAL'
  )),
  observation_note text NOT NULL DEFAULT '' CHECK(length(observation_note) <= 1000),
  observation_status varchar(16) NOT NULL DEFAULT 'RECORDED' CHECK(observation_status IN (
    'RECORDED','VOID'
  )),
  recorded_by uuid NOT NULL,
  recorded_at timestamptz NOT NULL,
  voided_by uuid,
  voided_at timestamptz,
  void_reason text NOT NULL DEFAULT '' CHECK(length(void_reason) <= 1000),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,observation_no)
);

-- 活行唯一：一个 FAIL 检项在同一时刻只允许一条**有效**观察。
-- ⚠️ 用**部分唯一索引**而不是表级 UNIQUE：作废是合法动作，
--   作废后重记必须能插进去。表级 UNIQUE 会让「记错了改一下」变成不可能，
--   于是运营只会去改原来的行 —— 而改原来的行正是我们不想要的（不留痕）。
CREATE UNIQUE INDEX quality_defect_observation_live
  ON quality_defect_observation(tenant_id, check_result_id)
  WHERE observation_status = 'RECORDED';

-- 分析端最热的两个访问路径：按质检单读（详情页）、按发现环节聚合（开箱率）。
CREATE INDEX quality_defect_observation_by_inspection
  ON quality_defect_observation(tenant_id, inspection_id);
CREATE INDEX quality_defect_observation_by_stage
  ON quality_defect_observation(tenant_id, observation_status, discovery_stage);

COMMENT ON TABLE quality_defect_observation IS
  '11.5.2.1 缺陷观察：一条 FAIL 检项对应一条，记录「这个缺陷什么时候、什么环节被发现」，开箱率的唯一来源';
COMMENT ON COLUMN quality_defect_observation.discovery_stage IS
  '发现环节：BUYER_UNBOXING=买家开箱即发现（开箱率分子）/DELIVERY_SIGNATURE=签收时发现/WAREHOUSE_INTAKE=入库开箱发现/POST_USE=使用后发现';
COMMENT ON COLUMN quality_defect_observation.defect_dimension IS
  '四个维度逐字对齐 V179 return_quality_check_result.item_dimension，由守卫②强制一致';

-- ======================================================================
-- 第二节：quality_cost_parameter —— 质量成本参数（租户自维护、版本化）
-- ======================================================================
--
-- 蓝图关系句写死「**质量成本（退货物流 + 损耗 + 差评影响）应量化**」，
-- 而三项里只有**损耗**在库里是实测的（V179 的 original_value − salvage_amount）：
--   * 退货物流：本仓**任何表都没有**记录买家回寄运费的成本金额
--     （V161 rma_authorization 只记 carrier / tracking_no / shipped_at，**没有金额列**；
--      V165 rma_label 只有 weight_kg / 长宽高，没有实收运费）。
--   * 差评影响：本仓**根本没有商品评价表**（全库无 product_review / rating 表）。
-- 编一个「行业平均差评损失率」乘以退货金额，就是把凭空假设报成测算结果 ——
-- 而这恰恰是「改进动力不足」的根源：一个说不清来源的数字没人会当真去改。
--
-- 所以这里把**参数做成租户可维护的真实数据**，并且**在报表里逐项标明哪几项是实测、
-- 哪几项是按参数估算**：损耗标 MEASURED，退货物流与差评影响标 ESTIMATED。
-- 参数本身一旦落库就是**租户自己的口径**（可按店铺/品类给出不同的真实值），
-- 不是我们替他们编的常数。
CREATE TABLE quality_cost_parameter (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  return_logistics_per_case numeric(19,4) NOT NULL DEFAULT 0 CHECK(return_logistics_per_case >= 0),
  review_impact_per_defect numeric(19,4) NOT NULL DEFAULT 0 CHECK(review_impact_per_defect >= 0),
  parameter_status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK(parameter_status IN (
    'ACTIVE','SUPERSEDED'
  )),
  effective_from date NOT NULL,
  effective_to date,
  source_note varchar(200) NOT NULL DEFAULT '' CHECK(length(source_note) <= 200),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  superseded_by uuid,
  -- ⚠️ 刻意**可空**：生效中的参数这三列本来就是空的，写成 NOT NULL 会让建行就违约。
  --   「换过版必须留痕」由下面那条 CHECK 保证，不靠列的可空性。
  superseded_at timestamptz,
  supersede_reason varchar(1000) NOT NULL DEFAULT '' CHECK(length(supersede_reason) <= 1000),
  -- 乐观锁：换版是「读版本 → 改状态」，两个人同时换版时必须有一个人被挡住，
  -- 否则两份 SUPERSEDED 留痕会指向同一次换版。
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  CHECK(effective_to IS NULL OR effective_to >= effective_from),
  CHECK(parameter_status <> 'SUPERSEDED'
    OR (superseded_by IS NOT NULL AND length(btrim(supersede_reason)) > 0)),
  CHECK(parameter_status <> 'ACTIVE' OR effective_to IS NULL)
);

-- 生效唯一：**每个币种同时只有一份生效参数**。
-- 两份同时生效时「按哪一份算成本」没有唯一答案，而成本汇总会直接算错一个数。
CREATE UNIQUE INDEX quality_cost_parameter_active
  ON quality_cost_parameter(tenant_id, currency)
  WHERE parameter_status = 'ACTIVE';

COMMENT ON TABLE quality_cost_parameter IS
  '11.5.2.1 质量成本参数：退货物流与差评影响的单价（损耗是实测，不需要参数）';
COMMENT ON COLUMN quality_cost_parameter.return_logistics_per_case IS
  '每一件实收退货的买家回寄物流成本；0 表示该租户尚未登记 —— 报表会如实报「未登记」而不是报 0 元';

-- ======================================================================
-- 第三节：quality_supplier_ref —— 供应商引用镜像（trade 侧）
-- ======================================================================
--
-- 供应商**质量对比**是职责句点名的五项之一，而批次上只挂了一个
-- {@code lot_supplier_ref_id}（uuid，见 V180 product_issue_lot_flag /
-- product_issue_attribution），**没有编码也没有名称**。
-- 供应商主数据在 catalog 独立库（party_supplier_profile / party_role / supplier_quote），
-- AGENTS.md 明文禁止跨服务 SQL / FK / 共享视图。
--
-- 沿用 11.1.2.2 处理 platform_enum_mapping 的**同一条既有先例**：
-- 「在 trade 库建同形状的表（内部口径 ⇄ 平台码 + 来源 + 采集时刻），
--   同步器按同一形状写入时结构与查询都不用改」。
-- 本表就是这一形状：supplier_ref_id ⇄ 编码/名称 + 来源 + 采集时刻。
-- **没有登记名称时按 ref_id 分组照常出报表**，只是显示为
-- 「未登记供应商」—— 静默地把 uuid 当名字显示出来，运营会以为那就是供应商名。
CREATE TABLE quality_supplier_ref (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  supplier_ref_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '' CHECK(length(supplier_code) <= 64),
  supplier_name varchar(200) NOT NULL DEFAULT '' CHECK(length(supplier_name) <= 200),
  ref_source varchar(32) NOT NULL CHECK(ref_source IN (
    'TENANT_MAINTAINED','MODULE_6_PURCHASE_SYNC'
  )),
  source_reference varchar(128) NOT NULL DEFAULT '',
  ref_status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK(ref_status IN (
    'ACTIVE','DISABLED'
  )),
  note varchar(1000) NOT NULL DEFAULT '' CHECK(length(note) <= 1000),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,supplier_ref_id),
  CHECK(ref_source <> 'MODULE_6_PURCHASE_SYNC' OR length(btrim(source_reference)) > 0),
  CHECK(length(btrim(supplier_code)) > 0 OR length(btrim(supplier_name)) > 0),
  CHECK(updated_at >= created_at)
);

COMMENT ON TABLE quality_supplier_ref IS
  '11.5.2.1 供应商引用镜像：批次上的 supplier_ref_id 到可读名称的映射（同步器与租户共用同形状）';

-- ======================================================================
-- 第四节：quality_analysis_event —— 留痕（追加式）
-- ======================================================================
CREATE TABLE quality_analysis_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  event_scope varchar(24) NOT NULL CHECK(event_scope IN (
    'DEFECT_OBSERVATION','COST_PARAMETER','SUPPLIER_REF'
  )),
  event_type varchar(32) NOT NULL CHECK(btrim(event_type) <> ''),
  subject_id uuid NOT NULL,
  event_note varchar(1000) NOT NULL DEFAULT '' CHECK(length(event_note) <= 1000),
  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,event_scope,subject_id,event_type,occurred_at)
);

CREATE INDEX quality_analysis_event_by_subject
  ON quality_analysis_event(tenant_id, event_scope, subject_id, occurred_at);

COMMENT ON TABLE quality_analysis_event IS
  '11.5.2.1 分析侧留痕：缺陷观察登记/作废、成本参数换版、供应商引用登记与启停';

-- ======================================================================
-- 第五节：守卫触发器（把「不能编」钉在 DB 层，服务层与 DB 各判一次）
-- ======================================================================
CREATE OR REPLACE FUNCTION guard_quality_defect_observation() RETURNS trigger AS $$
DECLARE
  cr record;
  ins record;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    -- 守卫①：已作废的观察**只许改作废相关列**，业务列一律冻结。
    --   作废是终态：一次作废后再改缺陷内容，这条痕迹就不再是「当时记错了」，
    --   而是「当时随便记的」，开箱率也就失去了可追溯性。
    IF OLD.observation_status = 'VOID' AND (
         NEW.inspection_id     IS DISTINCT FROM OLD.inspection_id
      OR NEW.check_result_id  IS DISTINCT FROM OLD.check_result_id
      OR NEW.attribution_id   IS DISTINCT FROM OLD.attribution_id
      OR NEW.defect_code      IS DISTINCT FROM OLD.defect_code
      OR NEW.defect_name      IS DISTINCT FROM OLD.defect_name
      OR NEW.defect_dimension IS DISTINCT FROM OLD.defect_dimension
      OR NEW.discovery_stage  IS DISTINCT FROM OLD.discovery_stage
      OR NEW.discovered_at    IS DISTINCT FROM OLD.discovered_at
      OR NEW.affected_qty     IS DISTINCT FROM OLD.affected_qty
      OR NEW.severity         IS DISTINCT FROM OLD.severity
      OR NEW.observation_note IS DISTINCT FROM OLD.observation_note
      OR NEW.observation_status <> 'VOID'
    ) THEN
      RAISE EXCEPTION '缺陷观察已作废，业务列不可再改（作废即终态，判错了请另记一条）'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  SELECT c.*, i.inspection_status, i.inspected_qty, i.concluded_at, i.tenant_id AS ins_tenant
    INTO cr
    FROM return_quality_check_result c
    JOIN return_quality_inspection i ON i.id = c.inspection_id AND i.tenant_id = c.tenant_id
   WHERE c.id = NEW.check_result_id AND c.tenant_id = NEW.tenant_id;

  -- 守卫②：检项必须**真的存在**且**真的判 FAIL**。
  --   把一条 PASS 的检项记成缺陷，是最省事的造假方式：不写任何假数据，
  --   只是把「判过」说成「坏了」。所以这条在 DB 层再钉一次。
  IF NOT FOUND THEN
    RAISE EXCEPTION '缺陷观察必须挂在本租户真实存在的质检检项上'
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF cr.check_outcome <> 'FAIL' THEN
    RAISE EXCEPTION '只有判 FAIL 的检项才能登记为缺陷（当前检项结论为 %）', cr.check_outcome
      USING ERRCODE = 'check_violation';
  END IF;

  -- 守卫③：维度必须与检项**逐字一致**。
  --   维度是「外观/功能/配件/包装」四个语义面，报表按它聚合；
  --   允许它与检项不一致，等于让同一个缺陷在分布表里落进两个桶。
  IF cr.item_dimension <> NEW.defect_dimension THEN
    RAISE EXCEPTION '缺陷维度必须与检项维度一致（检项为 %，观察填了 %）',
      cr.item_dimension, NEW.defect_dimension
      USING ERRCODE = 'check_violation';
  END IF;

  -- 守卫④：质检单必须**已判定**。
  --   草稿态的质检结论还会被改，此时登记的「发现环节」可能随判定一起作废。
  IF cr.inspection_status <> 'CONCLUDED' THEN
    RAISE EXCEPTION '质检单尚未判定（当前 %），不能登记缺陷观察', cr.inspection_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 守卫⑤：件数不得超过该质检单的实检数量。
  IF NEW.affected_qty > cr.inspected_qty THEN
    RAISE EXCEPTION '缺陷件数 % 超过该质检单的实检数量 %', NEW.affected_qty, cr.inspected_qty
      USING ERRCODE = 'check_violation';
  END IF;

  -- 守卫⑥：买家开箱发现，**必然早于**仓内判定完成。
  --   这是开箱率最常见也最要命的造假：把入库发现记成「买家开箱」，
  --   就能把一个 2% 的开箱率报成 30%，而改进重点会因此整个跑偏。
  IF NEW.discovery_stage = 'BUYER_UNBOXING'
     AND cr.concluded_at IS NOT NULL
     AND NEW.discovered_at > cr.concluded_at THEN
    RAISE EXCEPTION '买家开箱发现的时刻 (%) 不可能晚于仓内判定完成时刻 (%)',
      NEW.discovered_at, cr.concluded_at
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER quality_defect_observation_guard
  BEFORE INSERT OR UPDATE ON quality_defect_observation
  FOR EACH ROW EXECUTE FUNCTION guard_quality_defect_observation();

-- 守卫⑦：成本参数换版**必须留痕**。
--   成本汇总会因为换了一版参数而整页数字不同 —— 三个月后再看这张报表，
--   「上次是多少」答不上来的话，这个数字就再也不能用来做改进决策了。
CREATE OR REPLACE FUNCTION guard_quality_cost_parameter() RETURNS trigger AS $$
BEGIN
  IF NEW.parameter_status = 'SUPERSEDED'
     AND OLD.parameter_status <> 'SUPERSEDED'
     AND NOT EXISTS (
       SELECT 1 FROM quality_analysis_event e
        WHERE e.tenant_id = NEW.tenant_id
          AND e.event_scope = 'COST_PARAMETER'
          AND e.subject_id = NEW.id
          AND e.event_type = 'SUPERSEDED'
     ) THEN
    RAISE EXCEPTION '成本参数换版必须先写留痕（quality_analysis_event / COST_PARAMETER / SUPERSEDED）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER quality_cost_parameter_guard
  BEFORE UPDATE ON quality_cost_parameter
  FOR EACH ROW EXECUTE FUNCTION guard_quality_cost_parameter();

-- ======================================================================
-- 第六节：RLS / GRANT
-- ======================================================================
DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'quality_defect_observation','quality_cost_parameter',
    'quality_supplier_ref','quality_analysis_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  quality_defect_observation, quality_cost_parameter,
  quality_supplier_ref, quality_analysis_event TO trade_app;

-- 授权在前、收回在后。
-- 留痕追加式：缺陷观察的发现环节是开箱率的唯一来源，能被悄悄改掉的开箱率等于没有开箱率。
REVOKE UPDATE,DELETE ON quality_analysis_event FROM trade_app;
-- ⚠️ **刻意不收回 quality_supplier_ref 的 DELETE**：
--   服务层只允许「停用（DISABLED）」而不允许删除 —— 停用是留痕的，删除不是。
--   理由与 11.1.2.2 对 platform_enum_mapping 的同一决定：同步器按同一形状写回来时，
--   删掉的那行会立刻复活，于是「明明停用了却又生效」。此处把删除权限收回给运行角色，
--   真正的删除只能由 DBA 用迁移来做。
REVOKE DELETE ON quality_supplier_ref FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：V179 的 return_quality_* 簇、V180 的 product_issue_* 簇、
-- V172 的 return_receipt_line、V156 的 aftersales_case 全部只读。
-- 本模块对它们的全部依赖是 SELECT / JOIN 与「写入时刻查一次」，
-- **跨车道 DDL 回归面 = 0**（同批另有三条车道也在往 trade 落迁移）。
-- catalog 的 party_supplier_profile / party_role 更是在**另一个数据库**里，
-- 本迁移连引用都没有 —— 供应商名称走 trade 侧镜像表（第三节）。
