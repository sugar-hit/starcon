-- ===========================================================================
-- V121 · 8.3.3.2 打包与称重（warehouse 服务）
-- ===========================================================================
--
-- 蓝图 08 卷 8.3.3.2 原文：
--   职责：打包作业：选择包材、填充物、打印面单、称重与体积测量、记录包装尺寸
--         （用于体积重与平台申报）。
--   输入：复核结果；包材选择；称重数据；申报信息（3.7.2 物理属性）。
--   输出：包裹记录（parcel + parcel_item）+ 实测重量体积。
--   关系：上游 = 8.3.3.1；下游 = 8.3.4.1 发货、10 物流（取重算费）。
--         **包裹必须含明细项**（哪些 SKU、多少件），只有重量和运单号无法追溯与对账。
--
-- 卷级关键约束（本迁移逐条对应）：
--   1)「**包裹必须含明细项**」→ 蓝图这一句是本模块存在的理由，所以它在 DB 层是
--      硬约束而不是提示：空包裹建不出来（parcel_item 一个都没有的 parcel 不许
--      结算），且每一行明细都**必须**指回一条复核基线行（recount_line_id NOT NULL
--      + 复合外键）—— 没有来源行的明细就是「凭空装进去的东西」。
--   2)「输出：实测重量体积」→ 三个实测值（毛重 / 外形尺寸 / 由尺寸派生的体积）
--      都由**触发器**从实测输入算出来并冻结：净重 = 毛重 − 包材自重、
--      体积 cbm = 长×宽×高/10⁶。服务层写的净重与体积一律被覆盖。
--   3)「选择包材」不是自由文本 → packing_material 是真实目录，且包材的自重与
--      外形尺寸是**权威值**：实测毛重不许超过包材承重、实测外形不许超出包材
--      外形（留 5mm 装配公差），超了就是「选错了包材」，在 DB 层当场拒。
--   4)「上游 = 8.3.3.1」→ 打包单**只能**从一张 PASSED 的复核单开，且开单瞬间
--      把复核基线冻结到 packing_order 上（不是每次去 join recount_line）：
--      打包是凭据的一半，半年后要能答出「这一箱当时是按哪一版基线装的」。
--   5)「下游 = 10 物流（取重算费）」→ **体积重不在本表算**：除数（6000/5000/…）
--      是承运商渠道口径，归 10 物流。本表给的是实测尺寸与实测体积，
--      10 物流按自己的除数取这几个数去算 —— 在这里先乘一个除数就是替渠道定价。
--
-- ---------------------------------------------------------------------------
-- 1. 本迁移建什么、不建什么
-- ---------------------------------------------------------------------------
--
-- 建五张表：
--   packing_material  包材与填充物目录（输入之一：包材选择）
--   packing_order     打包作业单头（一次打包作业 = 一张出库单的一次打包）
--   parcel            包裹头（输出：实测重量体积 + 包材/填充物 + 面单打印引用）
--   parcel_item       包裹明细项（输出：哪些 SKU、多少件）—— 蓝图点名要的那张表
--   packing_event     append-only 作业流水
--
-- **为什么不挂 label/print_job 的真外键当必填**：面单内容与打印次数的权威是
-- 8.3.4.2 已交付的 print_job / label_print_log（V73）。本模块只在 parcel 上挂一个
-- 可空的引用 + 打印次数，**不建第二个打印引擎、不自己渲染 ZPL**：面单数据来自
-- 10 物流（未交付），在此自造一份渲染内容会出现两份「印的是什么」。
-- 打包完成也**不**强制已打印 —— 运单号与承运渠道是 8.3.4.1 的事。
--
-- **为什么申报信息只快照 customs_declaration_line、不去读 3.7.2 物理属性**：
-- 蓝图输入写的「申报信息（3.7.2 物理属性）」在 catalog 服务（V6 sku_detail 的
-- length_mm/width_mm/height_mm/net_weight_g/gross_weight_g + V28 安全属性），
-- 本服务跨服务读表是被禁止的，而 3.7.2.1 也还没交付读取端口。所以：
--   * 本服务**真正读得到**的申报真相是 V85 的 customs_declaration_line（同服务），
--     装包时把它冻结到 parcel_item 上（hs_code / declared_item_name / 原产国），
--     取不到就如实记 NOT_FOUND，**不猜一个 HS 编码填上去**；
--   * 3.7.2 物理属性在 capabilities 里显式报 available=false + ownerModule，
--     而不是编一份数字让界面显示「已带出申报信息」。
--
-- ---------------------------------------------------------------------------
-- 2. 打包对库存做了什么、没做什么
-- ---------------------------------------------------------------------------
-- **什么都不做。** 打包只读、只记：
--   * 货还在打包区（warehouse_location.type='SHIPPING'），on_hand / reserved 一动不动；
--   * **不写 inventory_entry**（与 8.3.3.1 同一纪律）；
--   * 不释放预占、也不实扣。实发扣减与预占消费在 8.3.4.1 的同一事务里完成。
-- 打包能做的状态动作只有两个：完成 → 出库单 PENDING_SHIPMENT（交给 8.3.4.1）；
-- 发现缺件失败 → 出库单退回 PENDING_RECHECK（V72 留的返工边）。
--
-- ---------------------------------------------------------------------------
-- 3. 口径：重量一律克（g），体积一律立方米（cbm），禁浮点
-- ---------------------------------------------------------------------------
-- 复核与拣货都拿到货了，剩下的是「这一箱有多重、多大」。两个口径写死在列名里：
--   * 重量 numeric(19,3)，单位**克**（秤按克读，三位小数 = 毫克级余量）；
--   * 外形 numeric(14,3)，单位**厘米**；体积 numeric(19,6)，单位**立方米**。
-- 全部 NUMERIC，不用 double precision / real（AGENTS.md「禁止浮点记账」同一条精神，
-- 实测值要进对账与申报，浮点误差会变成退单原因）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. packing_material · 包材与填充物目录
-- ---------------------------------------------------------------------------
CREATE TABLE packing_material (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  material_code varchar(32) NOT NULL,
  name varchar(200) NOT NULL,

  -- 角色。蓝图「选择包材、填充物」是两个东西：包材算进毛重与外形尺寸，
  -- 填充物（气泡柱/泡沫/纸团）只影响防护，不进净重口径。分两列而不是两张表。
  material_role varchar(12) NOT NULL CHECK(material_role IN ('PACKAGING','CUSHIONING')),

  -- 外形种类（现场按形状挑，不是自由文本）。
  material_type varchar(16) NOT NULL CHECK(material_type IN
    ('CARTON','ENVELOPE','BAG','BOX','BUBBLE','PAPER','FOAM','OTHER')),

  -- 适用仓。**可空 = 全仓通用**（与 recount_rule 同一形状）。
  warehouse_id uuid,

  -- 外形尺寸（厘米）：包材的名义内径与外形尺寸。**实测不许超出**（留 5mm 装配公差），
  -- 超出即「选错包材」—— 一个 30×20×10 的箱子不可能量出 35×25×15。
  outer_length_cm numeric(14,3) NOT NULL DEFAULT 0 CHECK(outer_length_cm>0),
  outer_width_cm  numeric(14,3) NOT NULL DEFAULT 0 CHECK(outer_width_cm>0),
  outer_height_cm numeric(14,3) NOT NULL DEFAULT 0 CHECK(outer_height_cm>0),

  -- 包材自重（克）：净重 = 实测毛重 − 自重，所以这一列是净重口径的一部分。
  -- 填充物角色下为 0（填充物不进净重口径）。
  tare_weight_g numeric(19,3) NOT NULL DEFAULT 0 CHECK(tare_weight_g>=0),

  -- 承重上限（克）：实测毛重超过它就是超载，当场拒。
  -- 0 = 未设上限（不是「承重无限」，capabilities 里如实报出来）。
  max_gross_weight_g numeric(19,3) NOT NULL DEFAULT 0 CHECK(max_gross_weight_g>=0),

  status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  retired_at timestamptz,
  retire_reason varchar(300),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,material_code),

  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),

  CHECK(btrim(material_code)<>'' AND btrim(name)<>''),
  -- 填充物不参与净重与外形口径：给填充物填自重会让净重凭空少一块。
  CHECK(material_role<>'CUSHIONING' OR tare_weight_g=0),
  -- 停用一条还在用的包材却不写原因，事后没人说得清从哪一刻起换了口径。
  CHECK(status<>'RETIRED' OR (retired_at IS NOT NULL AND retire_reason IS NOT NULL
                              AND btrim(retire_reason)<>'')),
  CHECK(status='RETIRED' OR (retired_at IS NULL AND retire_reason IS NULL))
);

CREATE INDEX packing_material_active
  ON packing_material(tenant_id, warehouse_id, material_role, material_code)
  WHERE status='ACTIVE';
CREATE INDEX packing_material_code
  ON packing_material(tenant_id, material_code);

-- 停用只能一次、且不能复活：AFTER UPDATE 上把 RETIRED 钉死（与 V117 同一纪律）。
CREATE FUNCTION guard_packing_material_retire_fn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status <> 'RETIRED' THEN
      RAISE EXCEPTION '包材 % 停用后不能复活（包材口径的历史必须可追）。', OLD.material_code
        USING ERRCODE='23514';
    END IF;
    IF NEW.retired_at IS NULL OR NEW.retire_reason IS NULL OR btrim(NEW.retire_reason)='' THEN
      RAISE EXCEPTION '停用包材 % 必须写时刻与原因。', OLD.material_code USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER packing_material_retire_guard
  BEFORE UPDATE OF status ON packing_material
  FOR EACH ROW EXECUTE FUNCTION guard_packing_material_retire_fn();

-- ---------------------------------------------------------------------------
-- 2. packing_order · 打包作业单头
-- ---------------------------------------------------------------------------
CREATE TABLE packing_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号：PK-<UTC 日期>-<8 位十六进制>，服务端派生，不接受调用方自填。
  packing_code varchar(32) NOT NULL,

  -- 上游真外键：8.3.3.1 的复核单。**一张 PASSED 的复核单只能开一张打包单**。
  recount_session_id uuid NOT NULL,
  outbound_order_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,

  -- 口径原文，随响应返回。看这一箱的人有权知道「它是按哪一版复核基线装的」。
  packing_basis varchar(600) NOT NULL,

  -- **开单时出库单处在哪一档**（冻结快照）。PACKED 时守卫靠它判
  -- 「这一单本来就在待打包」—— 否则一次打包可以把一张不在打包队列里的单推出去。
  outbound_status_before varchar(20) NOT NULL,

  -- 四态。**刻意没有「已发货」**：打包完成只是把包裹交给 8.3.4.1
  -- （与 8.3.3.1「复核通过 ≠ 已发货」同一纪律）。
  status varchar(12) NOT NULL DEFAULT 'OPEN' CHECK(status IN
    ('OPEN','PACKED','FAILED','CANCELLED')),

  packer_user_id uuid,

  -- 基线（开单时从复核单冻结的读数，**不随明细漂移**）：
  -- baseline_line_count / baseline_quantity 是「应该装多少」，
  -- packed_line_count / packed_quantity 是「实际装了多少」，两个数分开。
  baseline_line_count integer NOT NULL DEFAULT 0 CHECK(baseline_line_count>=0),
  baseline_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(baseline_quantity>=0),

  -- 合计：**唯一写入者是 refresh_packing_order_totals_fn 触发器**，
  -- 服务层只读回来，不自己算（服务层算的数与账上的数可能差一位）。
  packed_line_count integer NOT NULL DEFAULT 0 CHECK(packed_line_count>=0),
  packed_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(packed_quantity>=0),
  parcel_count integer NOT NULL DEFAULT 0 CHECK(parcel_count>=0),
  parcel_item_count integer NOT NULL DEFAULT 0 CHECK(parcel_item_count>=0),
  measured_parcel_count integer NOT NULL DEFAULT 0 CHECK(measured_parcel_count>=0),
  total_gross_weight_g numeric(19,3) NOT NULL DEFAULT 0 CHECK(total_gross_weight_g>=0),
  total_tare_weight_g numeric(19,3) NOT NULL DEFAULT 0 CHECK(total_tare_weight_g>=0),
  total_volume_cbm numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_volume_cbm>=0),

  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  failed_at timestamptz,
  cancelled_at timestamptz,
  fail_reason varchar(300),
  cancel_reason varchar(300),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,packing_code),
  UNIQUE(tenant_id,recount_session_id),

  FOREIGN KEY(tenant_id,recount_session_id) REFERENCES recount_session(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_order_id) REFERENCES outbound_order(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),

  CHECK(btrim(packing_code)<>''),
  CHECK(btrim(packing_basis)<>''),
  CHECK(outbound_status_before='PENDING_PACKING'),
  -- 打包人与完成时刻同进同出（只有「装好了」而没有「谁装的」等于没交接）。
  CHECK((status='PACKED') = (completed_at IS NOT NULL)),
  CHECK((status='FAILED') = (failed_at IS NOT NULL)),
  CHECK((status='CANCELLED') = (cancelled_at IS NOT NULL)),
  CHECK(status<>'PACKED' OR packer_user_id IS NOT NULL),
  CHECK(status<>'FAILED' OR (fail_reason IS NOT NULL AND btrim(fail_reason)<>'')),
  CHECK(status<>'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason)<>''))
);

-- 打包作业台：未完的按开始时刻排。
CREATE INDEX packing_order_open_queue
  ON packing_order(tenant_id, warehouse_id, started_at, id)
  WHERE status='OPEN';
CREATE INDEX packing_order_order
  ON packing_order(tenant_id, outbound_order_id, started_at DESC, id);
CREATE INDEX packing_order_session
  ON packing_order(tenant_id, recount_session_id);
CREATE INDEX packing_order_packer
  ON packing_order(tenant_id, packer_user_id, started_at DESC, id)
  WHERE packer_user_id IS NOT NULL;

-- 2.1 打包单的入口守卫：**只能从一张 PASSED 的复核单开**（蓝图「上游 = 8.3.3.1」）。
CREATE FUNCTION guard_packing_order_open_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status varchar; v_baseline_lines int; v_baseline_qty numeric;
        v_outbound varchar;
BEGIN
  SELECT status, baseline_line_count, baseline_quantity
    INTO v_status, v_baseline_lines, v_baseline_qty
    FROM recount_session WHERE tenant_id = NEW.tenant_id AND id = NEW.recount_session_id;
  IF v_status IS NULL THEN
    RAISE EXCEPTION '复核单不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_status <> 'PASSED' THEN
    RAISE EXCEPTION '复核单当前是 %，只有「复核通过」（PASSED）才能开打包单 —— '
                    '打包接的是已核齐的货。', v_status USING ERRCODE='23514';
  END IF;
  IF v_baseline_lines = 0 OR v_baseline_qty <= 0 THEN
    RAISE EXCEPTION '复核单没有基线，没有可装的货，不能开打包单。' USING ERRCODE='23514';
  END IF;

  SELECT status INTO v_outbound
    FROM outbound_order WHERE tenant_id = NEW.tenant_id AND id = NEW.outbound_order_id;
  IF v_outbound <> 'PENDING_PACKING' THEN
    RAISE EXCEPTION '出库单当前是 %，不是「待打包」（PENDING_PACKING），不能开打包单。', v_outbound
      USING ERRCODE='23514';
  END IF;

  -- 基线冻结：调用方写的两个数一律作废，以复核单上的读数为准。
  NEW.baseline_line_count := v_baseline_lines;
  NEW.baseline_quantity   := v_baseline_qty;
  RETURN NEW;
END $$;
CREATE TRIGGER packing_order_open_guard
  BEFORE INSERT ON packing_order
  FOR EACH ROW EXECUTE FUNCTION guard_packing_order_open_fn();

-- ---------------------------------------------------------------------------
-- 3. parcel · 包裹头（实测重量体积在这里）
-- ---------------------------------------------------------------------------
CREATE TABLE parcel (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  packing_order_id uuid NOT NULL,

  -- 箱序号（1..n，同一打包单内）。打印与对账按它排序。
  parcel_no integer NOT NULL CHECK(parcel_no>0),
  parcel_code varchar(32) NOT NULL,

  -- 包材（角色必须是 PACKAGING，触发器校验）与填充物（角色必须是 CUSHIONING，可空）。
  material_id uuid NOT NULL,
  filler_material_id uuid,

  -- **实测外形尺寸（厘米）**：装好之后量的三个数，不是包材目录里的名义值。
  -- 未量 = NULL（还没称重封箱），**不是 0**（0 会被读成「量出来是 0 厘米」）。
  outer_length_cm numeric(14,3),
  outer_width_cm  numeric(14,3),
  outer_height_cm numeric(14,3),

  -- **实测毛重（克）**：连箱带货上秤的读数。未称 = NULL。
  measured_gross_weight_g numeric(19,3),

  -- 包材自重：开箱时从 packing_material 冻结（**冻结值不是引用** ——
  -- 包材目录以后改自重，这一箱当初用哪个自重要能回看）。
  tare_weight_g numeric(19,3) NOT NULL DEFAULT 0 CHECK(tare_weight_g>=0),
  -- 净重 = 毛重 − 自重：**触发器派生**，应用层写不出与毛重对不上的净重。
  net_content_weight_g numeric(19,3),

  -- 实测体积（立方米）= 长×宽×高/10⁶，**触发器派生**。
  -- 体积重不在这里算（除数归 10 物流渠道，见文件头 §1.5）。
  volume_cbm numeric(19,6),

  measured_at timestamptz,
  measured_by uuid,

  -- 面单打印：**引用** 8.3.4.2 已交付的 print_job，不自建渲染引擎。
  label_print_job_id uuid,
  label_print_count integer NOT NULL DEFAULT 0 CHECK(label_print_count>=0),
  label_printed_at timestamptz,

  -- 明细合计：**唯一写入者是 refresh_parcel_totals_fn 触发器**。
  item_count integer NOT NULL DEFAULT 0 CHECK(item_count>=0),
  total_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(total_quantity>=0),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,packing_order_id,parcel_no),
  UNIQUE(tenant_id,parcel_code),

  FOREIGN KEY(tenant_id,packing_order_id) REFERENCES packing_order(tenant_id,id),
  FOREIGN KEY(tenant_id,material_id) REFERENCES packing_material(tenant_id,id),
  FOREIGN KEY(tenant_id,filler_material_id) REFERENCES packing_material(tenant_id,id),
  FOREIGN KEY(tenant_id,label_print_job_id) REFERENCES print_job(tenant_id,id),

  CHECK(btrim(parcel_code)<>''),
  CHECK(outer_length_cm IS NULL OR outer_length_cm>0),
  CHECK(outer_width_cm  IS NULL OR outer_width_cm>0),
  CHECK(outer_height_cm IS NULL OR outer_height_cm>0),
  CHECK(measured_gross_weight_g IS NULL OR measured_gross_weight_g>0),
  -- 尺寸与体积、重量与净重、称重时刻：要么全有要么全无
  --（半量状态的包裹会让 10 物流拿到一个「量了一半的箱子」去算费）。
  CHECK((volume_cbm IS NULL) =
        (outer_length_cm IS NULL OR outer_width_cm IS NULL OR outer_height_cm IS NULL)),
  CHECK((net_content_weight_g IS NULL) = (measured_gross_weight_g IS NULL)),
  CHECK((measured_at IS NULL) = (measured_gross_weight_g IS NULL)),
  CHECK(measured_by IS NULL OR measured_at IS NOT NULL),
  -- 净重不许为负：毛重比包材自重还轻，说明称错了或秤没归零。
  CHECK(net_content_weight_g IS NULL OR net_content_weight_g>=0),
  CHECK(volume_cbm IS NULL OR volume_cbm>0),
  -- 面单打印次数与时刻同进同出（有次数就该有打印时刻，事后「第几次印的」要答得出）。
  CHECK((label_print_count=0) = (label_print_job_id IS NULL AND label_printed_at IS NULL))
);

CREATE INDEX parcel_packing_order
  ON parcel(tenant_id, packing_order_id, parcel_no);
CREATE INDEX parcel_material
  ON parcel(tenant_id, material_id);

-- 3.1 包材角色校验 + 自重冻结 + 实测派生 + 装配上限。
CREATE FUNCTION guard_parcel_measure_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_role varchar; v_tare numeric(19,3); v_max numeric(19,3);
        v_l numeric(14,3); v_w numeric(14,3); v_h numeric(14,3);
        v_l2 numeric(14,3); v_w2 numeric(14,3); v_h2 numeric(14,3);
        v_status varchar;
        v_tol constant numeric := 0.5;  -- 5mm 装配公差
BEGIN
  -- 打包单已结束就不许再动包裹（与 8.3.3.1「事实只能处置一次」同一纪律）。
  SELECT status INTO v_status FROM packing_order
   WHERE tenant_id = NEW.tenant_id AND id = NEW.packing_order_id;
  IF v_status IS DISTINCT FROM 'OPEN' THEN
    RAISE EXCEPTION '打包单已结束（%），不能再改包裹。', v_status USING ERRCODE='23514';
  END IF;

  SELECT material_role, tare_weight_g, max_gross_weight_g,
         outer_length_cm, outer_width_cm, outer_height_cm
    INTO v_role, v_tare, v_max, v_l, v_w, v_h
    FROM packing_material WHERE tenant_id = NEW.tenant_id AND id = NEW.material_id;
  IF v_role IS NULL THEN
    RAISE EXCEPTION '包材不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_role <> 'PACKAGING' THEN
    RAISE EXCEPTION '包裹必须用「包材」角色的物料，% 是「填充物」角色 —— '
                    '填充物不构成外形也不计入自重。', NEW.material_id
      USING ERRCODE='23514';
  END IF;
  IF NEW.filler_material_id IS NOT NULL THEN
    SELECT material_role INTO v_role
      FROM packing_material WHERE tenant_id = NEW.tenant_id AND id = NEW.filler_material_id;
    IF v_role IS DISTINCT FROM 'CUSHIONING' THEN
      RAISE EXCEPTION '填充物必须是「填充物」角色的物料。' USING ERRCODE='23514';
    END IF;
  END IF;

  -- 包材换了就把自重**重新冻结**到这一箱上（目录改了口径，这一箱当下的自重也要对）。
  NEW.tare_weight_g := COALESCE(v_tare, 0);

  -- 实测三边不许超出包材外形（留公差）：超出即「选错包材」。
  IF NEW.outer_length_cm IS NOT NULL THEN
    IF NEW.outer_length_cm > v_l + v_tol OR NEW.outer_width_cm > v_w + v_tol
       OR NEW.outer_height_cm > v_h + v_tol THEN
      RAISE EXCEPTION '实测外形 %×%×% cm 超出包材 % 的外形 %×%×% cm（公差 % cm）—— 选错包材了。',
        NEW.outer_length_cm, NEW.outer_width_cm, NEW.outer_height_cm,
        NEW.material_id, v_l, v_w, v_h, v_tol USING ERRCODE='23514';
    END IF;
    -- 体积：长×宽×高/10⁶（厘米 → 立方米）。派生列，应用层写的值一律被覆盖。
    NEW.volume_cbm := round(NEW.outer_length_cm * NEW.outer_width_cm * NEW.outer_height_cm
                            / 1000000, 6);
  ELSE
    NEW.volume_cbm := NULL;
  END IF;

  -- 净重 = 毛重 − 自重，同样派生。
  IF NEW.measured_gross_weight_g IS NOT NULL THEN
    IF COALESCE(v_max, 0) > 0 AND NEW.measured_gross_weight_g > v_max THEN
      RAISE EXCEPTION '实测毛重 % g 超过包材 % 的承重上限 % g —— 超载，换箱或改包材。',
        NEW.measured_gross_weight_g, NEW.material_id, v_max USING ERRCODE='23514';
    END IF;
    IF NEW.measured_gross_weight_g < NEW.tare_weight_g THEN
      RAISE EXCEPTION '实测毛重 % g 比包材自重 % g 还轻 —— 秤没归零或称错了。',
        NEW.measured_gross_weight_g, NEW.tare_weight_g USING ERRCODE='23514';
    END IF;
    NEW.net_content_weight_g := NEW.measured_gross_weight_g - NEW.tare_weight_g;
  ELSE
    NEW.net_content_weight_g := NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER parcel_measure_guard
  BEFORE INSERT OR UPDATE ON parcel
  FOR EACH ROW EXECUTE FUNCTION guard_parcel_measure_fn();

-- ---------------------------------------------------------------------------
-- 4. parcel_item · 包裹明细项（蓝图点名要的那张表）
-- ---------------------------------------------------------------------------
CREATE TABLE parcel_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  parcel_id uuid NOT NULL,

  item_no integer NOT NULL CHECK(item_no>0),

  -- **来源行**：8.3.3.1 的复核基线行。NOT NULL + 复合外键 ——
  -- 没有来源行的明细就是「凭空装进去的东西」，那正是「无法追溯与对账」的形态。
  recount_line_id uuid NOT NULL,
  outbound_line_id uuid NOT NULL,

  -- SKU 冻结（COPY 语义）：商品之后改名或停用，这一箱的凭据要能回看「当时装的是哪件」。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL,

  -- 件数。NUMERIC(19,6)，禁浮点。**逐件核对品（requires_serial）一行一件一号**。
  quantity numeric(19,6) NOT NULL CHECK(quantity>0),

  -- 序列号（可空）。带号时件数必须为 1：把三个号塞进一行「共 3 件」，
  -- 就回到「只有件数无法追溯」的毛病上了。
  serial_id uuid,
  serial_number varchar(128),

  -- 申报快照（输入「申报信息」的落点）：**只快照本服务真读得到的
  -- customs_declaration_line（V85，同服务）**，取不到就如实记 NOT_FOUND，
  -- 绝不猜一个 HS 编码。3.7.2 的 SKU 物理属性属 catalog，跨服务读不到，
  -- 在 capabilities 里显式报 available=false（见文件头 §1.4）。
  declaration_source varchar(24) NOT NULL DEFAULT 'NOT_FOUND' CHECK(declaration_source IN
    ('CUSTOMS_DECLARATION_LINE','NOT_FOUND')),
  declaration_line_id uuid,
  hs_code varchar(32) NOT NULL DEFAULT '',
  declared_item_name varchar(200) NOT NULL DEFAULT '',
  declared_origin_country_code varchar(2) NOT NULL DEFAULT '',

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,parcel_id,item_no),

  FOREIGN KEY(tenant_id,parcel_id) REFERENCES parcel(tenant_id,id),
  FOREIGN KEY(tenant_id,recount_line_id) REFERENCES recount_line(tenant_id,id),
  FOREIGN KEY(tenant_id,outbound_line_id) REFERENCES outbound_order_line(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku_ref(tenant_id,id),
  FOREIGN KEY(tenant_id,serial_id) REFERENCES inventory_serial(tenant_id,id),
  FOREIGN KEY(tenant_id,declaration_line_id) REFERENCES customs_declaration_line(tenant_id,id),

  CHECK(btrim(sku_code)<>'' AND btrim(sku_name)<>''),
  CHECK(length(btrim(declared_origin_country_code))<=2),
  -- 序列号与件数同进同出：带号即一件，不带号才能是多项。
  CHECK((serial_id IS NULL) = (serial_number IS NULL)),
  CHECK(serial_id IS NULL OR quantity=1),
  CHECK(btrim(serial_number) IS NULL OR btrim(serial_number)<>''),
  -- 申报快照三件套：有申报行就必须带 HS/品名/原产国；
  -- NOT_FOUND 就必须什么都没填（不许「没找到但先填个编码」）。
  CHECK(declaration_source='NOT_FOUND' OR
        (declaration_line_id IS NOT NULL AND btrim(hs_code)<>'' AND btrim(declared_item_name)<>''))
);

CREATE INDEX parcel_item_parcel
  ON parcel_item(tenant_id, parcel_id, item_no);
-- 「这一行基线装了没有 / 装在哪几个箱」：分箱与对账都走它。
CREATE INDEX parcel_item_recount_line
  ON parcel_item(tenant_id, recount_line_id, parcel_id);
CREATE INDEX parcel_item_sku
  ON parcel_item(tenant_id, sku_id, created_at DESC, id);

-- 4.1 明细的件数不许超过复核基线（**跨包裹合计**，不是单箱内合计）：
--     同一行基线拆成三个箱子装，三个箱子加起来也不许超发。
CREATE FUNCTION guard_parcel_item_quantity_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_expected numeric(19,6); v_packed numeric(19,6); v_req_serial boolean;
        v_line_sku uuid; v_line_outbound uuid; v_this numeric(19,6);
        v_status varchar;
BEGIN
  -- 打包单已结束就不许再装东西（包裹所属的打包单必须还在 OPEN）。
  SELECT po.status INTO v_status FROM parcel p
    JOIN packing_order po ON po.tenant_id = p.tenant_id AND po.id = p.packing_order_id
   WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.parcel_id;
  IF v_status IS NULL THEN
    RAISE EXCEPTION '包裹不存在或不属于本租户。' USING ERRCODE='23503';
  END IF;
  IF v_status <> 'OPEN' THEN
    RAISE EXCEPTION '打包单已结束（%），不能再装东西。', v_status USING ERRCODE='23514';
  END IF;

  -- 件数必须等于该复核基线行的应发件数口径之内，且明细的 SKU / 订单行必须与基线行一致
  -- （不能拿着 A 行的号装 B 行的货）。
  SELECT expected_quantity, requires_serial, sku_id, outbound_line_id
    INTO v_expected, v_req_serial, v_line_sku, v_line_outbound
    FROM recount_line WHERE tenant_id = NEW.tenant_id AND id = NEW.recount_line_id;
  IF v_expected IS NULL THEN
    RAISE EXCEPTION '复核基线行不存在或不属于本租户 —— 包裹明细必须指回一条复核基线行。'
      USING ERRCODE='23503';
  END IF;
  IF NEW.sku_id <> v_line_sku THEN
    RAISE EXCEPTION '明细的 SKU 与复核基线行不是同一个商品 —— 不能拿着 A 行的号装 B 行的货。'
      USING ERRCODE='23514';
  END IF;
  IF NEW.outbound_line_id <> v_line_outbound THEN
    RAISE EXCEPTION '明细的订单行与复核基线行不是同一行。' USING ERRCODE='23514';
  END IF;
  -- 逐件核对的基线行（序列号品）必须逐件登记序列号。
  IF v_req_serial AND NEW.serial_id IS NULL THEN
    RAISE EXCEPTION '这一行复核时做了逐件核对，装包也必须一行一号（带序列号）。'
      USING ERRCODE='23514';
  END IF;
  IF NOT v_req_serial AND NEW.serial_id IS NOT NULL THEN
    RAISE EXCEPTION '这一行复核时没有逐件核对（未登记序列号），装包时不应带序列号。'
      USING ERRCODE='23514';
  END IF;

  -- 本次这行的件数；UPDATE 时要把自己旧的那一行动态扣掉再重算。
  IF TG_OP = 'INSERT' THEN
    v_this := NEW.quantity;
  ELSE
    v_this := NEW.quantity - COALESCE(OLD.quantity, 0);
  END IF;

  SELECT COALESCE(sum(pi.quantity), 0)
    INTO v_packed
    FROM parcel_item pi
    JOIN parcel p2 ON p2.tenant_id = pi.tenant_id AND p2.id = pi.parcel_id
   WHERE pi.tenant_id = NEW.tenant_id
     AND pi.recount_line_id = NEW.recount_line_id
     AND (TG_OP = 'INSERT' OR pi.id <> NEW.id);
  v_packed := v_packed + v_this;

  IF v_packed > v_expected THEN
    RAISE EXCEPTION '复核基线行应发 % 件，加上本次后已装 % 件 —— 超装（分箱合计也不许超）。',
      v_expected, v_packed USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER parcel_item_quantity_guard
  BEFORE INSERT OR UPDATE OF quantity, parcel_id, recount_line_id ON parcel_item
  FOR EACH ROW EXECUTE FUNCTION guard_parcel_item_quantity_fn();

-- 4.2 蓝图那句「**包裹必须含明细项**」与「事实只能改一次」合起来的守卫。
--     打包单一旦结束（含 PACKED），包裹与它的明细项就都不许再动 ——
--     于是 PACKED 时每个包裹必带明细项这件事由 6.1(b) 一次性拦死。
--     （注意：**不**禁止删掉一个「建错了的空箱」，那是操作纠错，不是篡改凭据。）
CREATE FUNCTION guard_parcel_delete_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status varchar;
BEGIN
  SELECT status INTO v_status FROM packing_order
   WHERE tenant_id = OLD.tenant_id AND id = OLD.packing_order_id;
  IF v_status IS DISTINCT FROM 'OPEN' THEN
    RAISE EXCEPTION '打包单已结束（%），包裹 % 不许再删 —— 装好的箱是交给 8.3.4.1 的凭据。',
      v_status, OLD.parcel_code USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER parcel_delete_guard
  BEFORE DELETE ON parcel
  FOR EACH ROW EXECUTE FUNCTION guard_parcel_delete_fn();

-- 明细项同样只许在 OPEN 期间增删改（与上面那条同一纪律，改动仍走 4.1 的件数守卫）。
CREATE FUNCTION guard_parcel_item_mutation_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
DECLARE v_parcel uuid; v_tenant uuid; v_status varchar;
BEGIN
  IF TG_OP = 'DELETE' THEN
    v_parcel := OLD.parcel_id; v_tenant := OLD.tenant_id;
  ELSE
    v_parcel := NEW.parcel_id; v_tenant := NEW.tenant_id;
  END IF;
  SELECT po.status INTO v_status FROM parcel p
    JOIN packing_order po ON po.tenant_id = p.tenant_id AND po.id = p.packing_order_id
   WHERE p.tenant_id = v_tenant AND p.id = v_parcel;
  IF v_status IS DISTINCT FROM 'OPEN' THEN
    RAISE EXCEPTION '打包单已结束（%），包裹明细项不再允许增删改。', v_status
      USING ERRCODE='23514';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER parcel_item_mutation_guard
  BEFORE UPDATE OR DELETE ON parcel_item
  FOR EACH ROW EXECUTE FUNCTION guard_parcel_item_mutation_fn();

-- ---------------------------------------------------------------------------
-- 5. packing_event · append-only 打包流水
-- ---------------------------------------------------------------------------
CREATE TABLE packing_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  packing_order_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN
    ('OPENED','PACKAGE_ADDED','ITEM_ADDED','MEASURED','LABEL_PRINTED',
     'PACKED','FAILED','CANCELLED')),
  from_status varchar(12),
  to_status varchar(12),
  parcel_id uuid,
  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  detail text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,packing_order_id) REFERENCES packing_order(tenant_id,id),
  FOREIGN KEY(tenant_id,parcel_id) REFERENCES parcel(tenant_id,id),
  CHECK(from_status IS NULL OR to_status IS NOT NULL),
  CHECK(btrim(detail)<>'')
);
CREATE INDEX packing_event_order
  ON packing_event(tenant_id, packing_order_id, occurred_at, id);

CREATE FUNCTION guard_packing_event_append_only_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '打包流水是 append-only 的事实凭据，不许 UPDATE/DELETE（event_id=%）。', OLD.id
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER packing_event_append_only
  BEFORE UPDATE OR DELETE ON packing_event
  FOR EACH ROW EXECUTE FUNCTION guard_packing_event_append_only_fn();

-- ---------------------------------------------------------------------------
-- 6. DB 级不变量（绕过服务层直连 SQL 也照样拒）
-- ---------------------------------------------------------------------------

-- 6.1 打包单状态机 + 「打包完成 = 真的装齐了、量过了」
--
-- 「**包裹必须含明细项**（哪些 SKU、多少件），只有重量和运单号无法追溯与对账」
-- 在 DB 层的落点就是这一段：PACKED 要求**同时**满足五件事，缺一不可 ——
--   a) 开单时出库单确实在 PENDING_PACKING（不是从别的状态硬推过来的）；
--   b) 至少一个包裹，且**每个包裹至少一条明细项**；
--   c) 每个包裹都**称过重、量过尺寸**（10 物流要的就是这两个数）；
--   d) 每条复核基线行的实装件数**恰好等于**应发件数（超装在明细触发器已拒，
--      这里拦的是少装 —— 少装了就是「复核过的东西没全发出去」）；
--   e) 有打包人与完成时刻。
CREATE FUNCTION guard_packing_order_status_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
DECLARE v_parcels int; v_empty int; v_unmeasured int;
        v_lines int;
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    -- 合法跃迁：OPEN → PACKED / FAILED / CANCELLED。
    -- 没有 FAILED → OPEN（打包失败要重做是**新的一张打包单**，
    -- 不是把上一张改成 OPEN —— 证据链不能被追认）。
    IF NOT (OLD.status='OPEN' AND NEW.status IN ('PACKED','FAILED','CANCELLED')) THEN
      RAISE EXCEPTION '打包单 % 不能从 % 变成 %（合法跃迁：OPEN→PACKED/FAILED/CANCELLED）。',
        OLD.packing_code, OLD.status, NEW.status USING ERRCODE='23514';
    END IF;
  END IF;

  IF NEW.status = 'PACKED' THEN
    IF NEW.outbound_status_before <> 'PENDING_PACKING' THEN
      RAISE EXCEPTION '打包单 % 开单时出库单不在「待打包」，不许判完成。', OLD.packing_code
        USING ERRCODE='23514';
    END IF;
    IF NEW.packer_user_id IS NULL THEN
      RAISE EXCEPTION '打包单 % 判完成必须有打包人。', OLD.packing_code USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO v_parcels FROM parcel WHERE tenant_id = NEW.tenant_id
                                                       AND packing_order_id = NEW.id;
    IF v_parcels = 0 THEN
      RAISE EXCEPTION '打包单 % 一个包裹都没有，不能判完成。', OLD.packing_code
        USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO v_empty FROM parcel p
     WHERE p.tenant_id = NEW.tenant_id AND p.packing_order_id = NEW.id
       AND NOT EXISTS (SELECT 1 FROM parcel_item pi
                        WHERE pi.tenant_id = p.tenant_id AND pi.parcel_id = p.id);
    IF v_empty > 0 THEN
      RAISE EXCEPTION '打包单 % 还有 % 个包裹一件明细都没有 —— 蓝图要求「包裹必须含明细项」，'
                      '只有重量和运单号无法追溯与对账。', OLD.packing_code, v_empty
        USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO v_unmeasured FROM parcel
     WHERE tenant_id = NEW.tenant_id AND packing_order_id = NEW.id
       AND (measured_gross_weight_g IS NULL OR volume_cbm IS NULL);
    IF v_unmeasured > 0 THEN
      RAISE EXCEPTION '打包单 % 还有 % 个包裹没称重或没量尺寸 —— 实测重量体积是本模块的产出，'
                      '不量就没法交给 8.3.4.1 与 10 物流。', OLD.packing_code, v_unmeasured
        USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO v_lines
      FROM recount_line rl
     WHERE rl.tenant_id = NEW.tenant_id AND rl.session_id = NEW.recount_session_id
       AND (SELECT COALESCE(sum(pi.quantity), 0) FROM parcel_item pi
              JOIN parcel p2 ON p2.tenant_id = pi.tenant_id AND p2.id = pi.parcel_id
             WHERE pi.tenant_id = rl.tenant_id AND pi.recount_line_id = rl.id
               AND p2.packing_order_id = NEW.id) <> rl.expected_quantity;
    IF v_lines > 0 THEN
      RAISE EXCEPTION '打包单 % 有 % 行复核基线的实装件数与应发件数不相等 —— '
                      '复核核过的货没有整箱装出来。', OLD.packing_code, v_lines
        USING ERRCODE='23514';
    END IF;
  END IF;

  IF NEW.status = 'FAILED' THEN
    IF NEW.fail_reason IS NULL OR btrim(NEW.fail_reason)='' THEN
      RAISE EXCEPTION '打包单 % 判失败必须写原因（缺件？破损？）。', OLD.packing_code
        USING ERRCODE='23514';
    END IF;
  END IF;

  RETURN NEW;
END $$;
CREATE TRIGGER packing_order_status_guard
  BEFORE UPDATE ON packing_order
  FOR EACH ROW EXECUTE FUNCTION guard_packing_order_status_fn();

-- 6.2 包裹的明细合计：从 parcel_item 派生，服务层算的数不算数。
CREATE FUNCTION refresh_parcel_totals_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
DECLARE v_parcel uuid;
BEGIN
  v_parcel := COALESCE(NEW.parcel_id, OLD.parcel_id);

  UPDATE parcel p
     SET item_count = COALESCE(t.cnt, 0),
         total_quantity = COALESCE(t.qty, 0),
         updated_at = now()
    FROM (
      SELECT count(*) AS cnt, sum(quantity) AS qty
        FROM parcel_item WHERE tenant_id = NEW.tenant_id AND parcel_id = v_parcel
    ) t
   WHERE p.tenant_id = NEW.tenant_id AND p.id = v_parcel;

  RETURN NULL;
END $$;
CREATE TRIGGER parcel_totals_from_item
  AFTER INSERT OR UPDATE OR DELETE ON parcel_item
  FOR EACH ROW EXECUTE FUNCTION refresh_parcel_totals_fn();

-- 6.3 打包单的合计：从包裹与明细派生，服务层算的数不算数。
CREATE FUNCTION refresh_packing_order_totals_fn() RETURNS trigger
  LANGUAGE plpgsql AS $$
DECLARE v_order uuid;
BEGIN
  v_order := COALESCE(NEW.packing_order_id, OLD.packing_order_id);

  UPDATE packing_order o
     SET parcel_count = COALESCE(p.parcels, 0),
         measured_parcel_count = COALESCE(p.measured, 0),
         total_gross_weight_g = COALESCE(p.gross, 0),
         total_tare_weight_g = COALESCE(p.tare, 0),
         total_volume_cbm = COALESCE(p.vol, 0),
         parcel_item_count = COALESCE(p.items, 0),
         packed_quantity = COALESCE(p.qty, 0),
         updated_at = now()
    FROM (
      SELECT count(*) AS parcels,
             count(*) FILTER (WHERE x.gross IS NOT NULL AND x.vol IS NOT NULL) AS measured,
             sum(x.gross) AS gross,
             sum(x.tare) AS tare,
             sum(x.vol) AS vol,
             sum(x.items) AS items,
             sum(x.qty) AS qty
        FROM (
          SELECT pc.measured_gross_weight_g AS gross, pc.tare_weight_g AS tare,
                 pc.volume_cbm AS vol, pc.item_count AS items, pc.total_quantity AS qty
            FROM parcel pc
           WHERE pc.tenant_id = NEW.tenant_id AND pc.packing_order_id = v_order
        ) x
    ) p
   WHERE o.tenant_id = NEW.tenant_id AND o.id = v_order;

  -- 实装行数：装了东西的复核基线行数（不是明细条数）。
  UPDATE packing_order o
     SET packed_line_count = (
           SELECT count(*) FROM recount_line rl
            WHERE rl.tenant_id = NEW.tenant_id AND rl.session_id = o.recount_session_id
              AND EXISTS (SELECT 1 FROM parcel_item pi
                           JOIN parcel pc ON pc.tenant_id = pi.tenant_id AND pc.id = pi.parcel_id
                          WHERE pi.tenant_id = rl.tenant_id AND pi.recount_line_id = rl.id
                            AND pc.packing_order_id = o.id)),
         updated_at = now()
   WHERE o.tenant_id = NEW.tenant_id AND o.id = v_order;

  RETURN NULL;
END $$;
CREATE TRIGGER packing_order_totals_from_parcel
  AFTER INSERT OR UPDATE OR DELETE ON parcel
  FOR EACH ROW EXECUTE FUNCTION refresh_packing_order_totals_fn();

-- ===========================================================================
-- 7. 多租户与授权（与 V1 第 9 节、V72 第 6 节、V117 第 8 节同一条纪律）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['packing_material','packing_order','parcel','parcel_item',
                              'packing_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  packing_material, packing_order, parcel, parcel_item, packing_event
  TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V90、V117 同口径）。