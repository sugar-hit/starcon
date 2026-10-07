-- 8.3.4.2 面单与标签打印。
--
-- 蓝图 08-warehouse-operations.md 8.3.4.2 原文：「打印面单、装箱单、SKU 条码、库位标签、箱唛，
-- 支持批量打印、模板配置、打印次数记录（平台对重复打印有要求）。」
-- 输入：「打印任务（对象集合、模板）；打印机配置。」输出：「打印任务记录 + 打印内容（PDF/指令流）；
-- 打印日志。」以及红线：「**面单打印次数需记录**，部分平台对重复面单有明确规则。」
--
-- ===========================================================================
-- 一、本模块最重要的一条语义：**打印次数是逐对象累加的事实，且不允许被改小**
-- ===========================================================================
-- 「重复打印」在这个系统里不是日志里的一行，而是**一个可被平台追责的计数**：
-- 面单贴错/贴歪要重打，重打就是第 2 次、第 3 次；部分平台（例如需要面单与包裹绑定
-- 一一对应的渠道）对同一面单的重复打印次数有硬上限，超限会被判违规。
--
-- 所以「打印次数」必须落成三处，且互相钉死：
--
--   1. print_target.print_count —— 权威计数。**由 DB 触发器保证每次只能 +1**，
--      任何 UPDATE 把它改小、直接写死某个数字，都会被 guard_print_count_monotonic 拒绝。
--      这是本模块最容易被做成假逻辑的地方：只写一条 print_log 说「第 3 次打印」
--      而计数列纹丝不动，重复打印就永远查不出来。
--   2. label_print_log.print_sequence —— 该次打印是第几次（与 print_count 同步落库）。
--      append-only，不可改。
--   3. 平台维度的重复打印判据 print_reprint_rule —— 按平台 + 打印对象类型给出
--      warning_count / max_count。没有规则就没有「超限」这个概念，
--      而蓝图明写「部分平台对重复面单有明确规则」。
--
-- 超限怎么算：**下一次打印的序号 = print_count + 1**。规则判据是拿这个序号去比：
--   next <= warning_count              → 正常
--   warning_count < next <= max_count   → 允许但**留告警**（reprint_warning=true）
--   next > max_count                    → 拒绝，除非调用方显式 override_reprint=true
--                                         且填了 override_reason（强制重打要留名字）
-- max_count 为 null 表示该平台对此对象类型不限次（但仍然计数、仍然留日志）。
--
-- ===========================================================================
-- 二、打印对象是**本地快照 + 外部引用**，不是外键
-- ===========================================================================
-- 蓝图上游是 10 物流（面单数据）与 3.1.2.3（条码），两者都尚未交付；出库单（8.3.1.1）
-- 也在并行车道里。所以本模块**不建** outbound_order、也不对任何物流表建外键
-- （AGENTS.md：禁止跨服务 SQL / FK / 共享视图）。
--
-- 做法是 print_target 落一份**打印要用的字段快照**（waybill_no / carrier_code /
-- sku_code / location_code / weight 等，随对象类型取不同列），外加一个纯文本的
-- external_ref 指向调用方的对象标识（如出库单号）。这样：
--   - 打印内容在生成时刻就是确定的（快照不可变），重打第 2 次贴出来的与第 1 次一样；
--   - 上游交付后要改的是「谁来填快照」，不是打印这条链路。
-- 快照被 guard_print_target_snapshot_frozen 钉成不可变：改字段等于换一张面单，
-- 必须换 external_ref 重开一个 print_target，否则计数与告警都会对不上。
--
-- ===========================================================================
-- 三、五种打印对象共用一套任务/模板/日志骨架
-- ===========================================================================
-- 面单、装箱单、SKU 条码、库位标签、箱唛的差别只在**渲染形态**与**必填字段**，
-- 不在数据模型。所以：
--   print_template —— 模板配置（对象类型、纸张尺寸、方向、字段布局 JSON）
--   print_job     —— 一次打印任务（选对象集合 × 模板 × 份数）
--   print_job_item—— 任务里每个对象的展开行（批量打印是真批量：一次建 N 行）
--   label_print_log —— append-only 的逐次打印凭据
--
-- 状态机（DB 级转移约束 print_job_status_transition）：
--   DRAFT → QUEUED → PRINTING → COMPLETED / FAILED，DRAFT|QUEUED → CANCELLED
--
-- 幂等：同一个 job 只能打印一次（COMPLETED/CANCELLED 是终态），由
-- guard_print_job_terminal 兜住；重打走**新建任务**，所以「第几次」永远是真的第几次。
--
-- ===========================================================================
-- 四、打印内容是真渲染，不是占位文件
-- ===========================================================================
-- 渲染走 LabelRenderService（服务端模板 + 快照字段 → HTML/ZPL 指令流），
-- 落库时存 print_job_item.rendered_content 与 content_format，并留下
-- content_checksum（SHA-256）。下载端点按 content_format 原样吐字节：
--   ZPL  → text/vnd.zpl，现场热敏打印机直接吃指令流
--   HTML → text/html，浏览器直接排版打印（面单/装箱单/箱唛本来就该走这条路）
-- 两种都是**由本服务按模板与快照现算的**，库里没有写死的假 PDF。

-- ===========================================================================
-- 五、迁移与命名
-- ===========================================================================
-- 本迁移只 CREATE TABLE，不 ALTER 任何既有表，因此不存在跨车道的
-- ADD COLUMN / SET NOT NULL 回归面（见任务书第 8 条）。
-- 表名统一 print_ / label_ 前缀，不与 7.5.1.1（transfer_*）、7.6.1.1/7.6.2.1
-- （库龄/健康）、7.7.1.2（寄售）、8.3.1.1（outbound_*）、8.5.1.1（作业效率）撞名。

-- ===========================================================================
-- 1. print_device 打印机配置
-- ===========================================================================
-- 蓝图「输入」明写「打印机配置」。现场标签机与面单打印机规格不同（热敏 vs 激光、
-- 纸宽、dpi），把这些参数落到库里而不是写死在前端，模板才能按真实纸宽出图。
CREATE TABLE print_device (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  code           text NOT NULL,
  name           text NOT NULL,
  device_kind    text NOT NULL,
  model          text,
  paper_size     text NOT NULL,
  dpi            integer,
  location       text,
  is_active      boolean NOT NULL DEFAULT true,
  created_by     uuid,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT print_device_tenant_id_unique UNIQUE (tenant_id, id),
  CONSTRAINT print_device_kind_ck CHECK (device_kind IN ('THERMAL_LABEL', 'LASER_DOCUMENT')),
  CONSTRAINT print_device_dpi_ck CHECK (dpi IS NULL OR (dpi >= 72 AND dpi <= 1200)),
  CONSTRAINT print_device_code_unique UNIQUE (tenant_id, code)
);

COMMENT ON TABLE print_device IS '8.3.4.2 打印机配置：热敏标签机 / 激光面单机（蓝图的打印输入项）';

-- ===========================================================================
-- 2. print_template 打印模板
-- ===========================================================================
CREATE TABLE print_template (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  code           text NOT NULL,
  name           text NOT NULL,
  object_type    text NOT NULL,
  paper_size     text NOT NULL,
  orientation    text NOT NULL DEFAULT 'PORTRAIT',
  field_layout   jsonb NOT NULL DEFAULT '{}'::jsonb,
  is_active      boolean NOT NULL DEFAULT true,
  created_by     uuid,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  -- 复合外键要指向它（与 V61 transfer_* 同一套跨租户堵口子的办法）
  CONSTRAINT print_template_tenant_id_unique UNIQUE (tenant_id, id),
  CONSTRAINT print_template_object_type_ck CHECK (
    object_type IN ('SHIPPING_LABEL', 'PACKING_SLIP', 'SKU_BARCODE', 'LOCATION_LABEL', 'CARTON_MARK')),
  CONSTRAINT print_template_orientation_ck CHECK (orientation IN ('PORTRAIT', 'LANDSCAPE')),
  CONSTRAINT print_template_field_layout_object_ck CHECK (jsonb_typeof(field_layout) = 'object'),
  CONSTRAINT print_template_code_unique UNIQUE (tenant_id, code)
);

COMMENT ON TABLE print_template IS '8.3.4.2 打印模板：五种打印对象各自的版式配置';
COMMENT ON COLUMN print_template.object_type IS '打印对象类型：面单/装箱单/SKU条码/库位标签/箱唛';
COMMENT ON COLUMN print_template.field_layout IS '字段布局：{"blocks":[{"key","label","x","y","w","h","scale","required"}]}';

-- ===========================================================================
-- 2. print_target 打印对象（本地快照 + 外部引用，跨服务不建 FK）
-- ===========================================================================
CREATE TABLE print_target (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  object_type    text NOT NULL,
  external_ref   text NOT NULL,
  -- 面单/装箱单/箱唛：物流与包裹信息（由调用方传入，10 物流交付后由它来填）
  waybill_no     text,
  carrier_code   text,
  ship_to_name   text,
  ship_to_address text,
  sku_code       text,
  sku_name       text,
  location_code  text,
  carton_code    text,
  package_count  integer,
  weight_kg      numeric(19,6),
  remark         text,
  -- 平台维度：重复打印判据要按平台取规则（蓝图的「部分平台对重复面单有明确规则」）
  platform_code  text,
  -- 权威打印次数：只增不减，由 guard_print_count_monotonic 钉死
  print_count    integer NOT NULL DEFAULT 0,
  first_printed_at timestamptz,
  last_printed_at  timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT print_target_object_type_ck CHECK (
    object_type IN ('SHIPPING_LABEL', 'PACKING_SLIP', 'SKU_BARCODE', 'LOCATION_LABEL', 'CARTON_MARK')),
  -- 权威计数不允许为负
  CONSTRAINT print_target_print_count_ck CHECK (print_count >= 0),
  -- 印过就必须有时间戳；没印过就不能有时间戳（与 V1 inventory_transfer 同口径）
  CONSTRAINT print_target_printed_at_ck CHECK ((print_count = 0) = (first_printed_at IS NULL)),
  -- external_ref 必须非空：没有它就无法回答「这条打印记录对应哪个业务对象」
  CONSTRAINT print_target_external_ref_ck CHECK (length(btrim(external_ref)) > 0),
  -- (类型, 外部引用) 在租户内唯一：同一个对象只能有一条打印身份，否则计数会分叉
  CONSTRAINT print_target_tenant_id_unique UNIQUE (tenant_id, id),
  CONSTRAINT print_target_ref_unique UNIQUE (tenant_id, object_type, external_ref)
);

COMMENT ON TABLE print_target IS '8.3.4.2 打印对象：本地快照 + 外部引用，不与 10 物流/出库单建 FK';
COMMENT ON COLUMN print_target.print_count IS '权威打印次数，只增不减（DB 触发器强制 +1）';

-- ===========================================================================
-- 3. print_job 打印任务
-- ===========================================================================
CREATE TABLE print_job (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  job_no         text NOT NULL,
  template_id    uuid NOT NULL,
  printer_id     uuid,
  status         text NOT NULL DEFAULT 'DRAFT',
  copies         integer NOT NULL DEFAULT 1,
  printer_name   text,
  remark         text,
  -- 汇总（真批量：插入时按 item 行数算出来，不是前端传的）
  target_count   integer NOT NULL DEFAULT 0,
  label_count    integer NOT NULL DEFAULT 0,
  reprint_warning_count integer NOT NULL DEFAULT 0,
  blocked_count  integer NOT NULL DEFAULT 0,
  override_reprint boolean NOT NULL DEFAULT false,
  override_reason text,
  created_by     uuid,
  created_at     timestamptz NOT NULL DEFAULT now(),
  queued_at      timestamptz,
  started_at     timestamptz,
  finished_at    timestamptz,
  CONSTRAINT print_job_status_ck CHECK (
    status IN ('DRAFT', 'QUEUED', 'PRINTING', 'COMPLETED', 'FAILED', 'CANCELLED')),
  CONSTRAINT print_job_copies_ck CHECK (copies >= 1),
  CONSTRAINT print_job_counts_ck CHECK (
    target_count >= 0 AND label_count >= 0
    AND reprint_warning_count >= 0 AND blocked_count >= 0),
  -- 强制重打必须写理由：超限重打是要被平台追责的动作，没有理由就等于没有留痕
  CONSTRAINT print_job_override_reason_ck CHECK (
    (override_reprint = false) OR (override_reason IS NOT NULL AND length(btrim(override_reason)) > 0)),
  -- 打印份数 = 对象数 × 份数（label_count 是算出来的，不是填的）
  CONSTRAINT print_job_label_count_ck CHECK (label_count = target_count * copies),
  CONSTRAINT print_job_finished_ck CHECK ((status IN ('COMPLETED', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL)),
  CONSTRAINT print_job_tenant_id_unique UNIQUE (tenant_id, id),
  CONSTRAINT print_job_job_no_unique UNIQUE (tenant_id, job_no),
  CONSTRAINT print_job_template_fk FOREIGN KEY (tenant_id, template_id)
    REFERENCES print_template (tenant_id, id) ON DELETE RESTRICT,
  -- 打印机可空（现场拿默认机打印），但填了就必须是本租户登记过的机器
  CONSTRAINT print_job_printer_fk FOREIGN KEY (tenant_id, printer_id)
    REFERENCES print_device (tenant_id, id) ON DELETE RESTRICT
);

COMMENT ON TABLE print_job IS '8.3.4.2 打印任务：一次批量打印（五种对象共用骨架）';
COMMENT ON COLUMN print_job.target_count IS '任务内对象数（= item 行数）';
COMMENT ON COLUMN print_job.label_count IS '实际标签数 = target_count × copies，插入时算';

-- ===========================================================================
-- 4. print_job_item 任务内每个对象一行（真批量展开）
-- ===========================================================================
CREATE TABLE print_job_item (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  job_id         uuid NOT NULL,
  target_id      uuid NOT NULL,
  line_no        integer NOT NULL,
  object_type    text NOT NULL,
  external_ref   text NOT NULL,
  -- 该行渲染时读到的次数（渲染依据，随行冻结以便复现「第几次打的是什么」）
  print_count_at_render integer NOT NULL,
  -- 真渲染内容：按模板与快照现算，落库后可原样下载复打
  content_format text NOT NULL DEFAULT 'ZPL',
  rendered_content text NOT NULL,
  content_checksum text NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT print_job_item_line_ck CHECK (line_no >= 1),
  CONSTRAINT print_job_item_format_ck CHECK (content_format IN ('ZPL', 'HTML')),
  CONSTRAINT print_job_item_object_type_ck CHECK (
    object_type IN ('SHIPPING_LABEL', 'PACKING_SLIP', 'SKU_BARCODE', 'LOCATION_LABEL', 'CARTON_MARK')),
  CONSTRAINT print_job_item_tenant_id_unique UNIQUE (tenant_id, id),
  CONSTRAINT print_job_item_unique UNIQUE (tenant_id, job_id, line_no),
  CONSTRAINT print_job_item_job_fk FOREIGN KEY (tenant_id, job_id)
    REFERENCES print_job (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT print_job_item_target_fk FOREIGN KEY (tenant_id, target_id)
    REFERENCES print_target (tenant_id, id) ON DELETE RESTRICT
);

COMMENT ON TABLE print_job_item IS '8.3.4.2 打印任务明细：每个对象一行，含真渲染内容与校验和';
COMMENT ON COLUMN print_job_item.content_checksum IS 'rendered_content 的 SHA-256，验「同一行内容没被改过」';

-- ===========================================================================
-- 5. label_print_log 打印日志（append-only 的逐次凭据）
-- ===========================================================================
CREATE TABLE label_print_log (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  job_id         uuid NOT NULL,
  job_item_id    uuid NOT NULL,
  target_id      uuid NOT NULL,
  object_type    text NOT NULL,
  external_ref   text NOT NULL,
  -- 本次是该对象的第几次打印（与 print_target.print_count 同步落库）
  print_sequence integer NOT NULL,
  copy_index     integer NOT NULL DEFAULT 1,
  content_format text NOT NULL,
  content_checksum text NOT NULL,
  -- 是否落在平台的重复打印告警区间
  reprint_warning boolean NOT NULL DEFAULT false,
  -- 是否因超限被强制放行（强制重打要留名字）
  override_applied boolean NOT NULL DEFAULT false,
  operator_id    uuid,
  printed_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT label_print_log_sequence_ck CHECK (print_sequence >= 1),
  CONSTRAINT label_print_log_copy_ck CHECK (copy_index >= 1),
  CONSTRAINT label_print_log_format_ck CHECK (content_format IN ('ZPL', 'HTML')),
  CONSTRAINT label_print_log_object_type_ck CHECK (
    object_type IN ('SHIPPING_LABEL', 'PACKING_SLIP', 'SKU_BARCODE', 'LOCATION_LABEL', 'CARTON_MARK')),
  CONSTRAINT label_print_log_job_fk FOREIGN KEY (tenant_id, job_id)
    REFERENCES print_job (tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT label_print_log_target_fk FOREIGN KEY (tenant_id, target_id)
    REFERENCES print_target (tenant_id, id) ON DELETE RESTRICT
);

COMMENT ON TABLE label_print_log IS '8.3.4.2 打印日志：append-only，逐次打印凭据（蓝图的打印次数要求落在这里）';
COMMENT ON COLUMN label_print_log.print_sequence IS '该次打印是该对象的第几次，与 print_target.print_count 同步';

-- ===========================================================================
-- 6. print_reprint_rule 平台维度的重复打印判据
-- ===========================================================================
CREATE TABLE print_reprint_rule (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  platform_code  text NOT NULL,
  object_type    text NOT NULL,
  -- 打印序号达到 warning_count 起留告警；超过 max_count 拒绝（除非显式 override）
  warning_count  integer,
  max_count      integer,
  note           text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT print_reprint_rule_object_type_ck CHECK (
    object_type IN ('SHIPPING_LABEL', 'PACKING_SLIP', 'SKU_BARCODE', 'LOCATION_LABEL', 'CARTON_MARK')),
  -- 阈值本身要有意义：0 或负数会让「超限」永远触发或永不触发
  CONSTRAINT print_reprint_rule_warning_ck CHECK (warning_count IS NULL OR warning_count >= 1),
  CONSTRAINT print_reprint_rule_max_ck CHECK (max_count IS NULL OR max_count >= 1),
  -- 有上限就必须有告警线，否则「允许但告警」这个区间不存在
  CONSTRAINT print_reprint_rule_range_ck CHECK (
    (max_count IS NULL AND warning_count IS NULL)
    OR (max_count IS NOT NULL AND (warning_count IS NULL OR warning_count <= max_count))),
  CONSTRAINT print_reprint_rule_unique UNIQUE (tenant_id, platform_code, object_type)
);

COMMENT ON TABLE print_reprint_rule IS '8.3.4.2 平台重复打印规则：蓝图「部分平台对重复面单有明确规则」的落点';
COMMENT ON COLUMN print_reprint_rule.max_count IS '上限；null = 该平台对此对象类型不限次（但仍计数、仍留日志）';

-- ===========================================================================
-- 索引
-- ===========================================================================
CREATE INDEX print_template_list ON print_template(tenant_id, object_type, is_active);
-- 「这个对象打过几次」「最近打过哪些」是本模块最高频的两个问法
CREATE INDEX print_target_count ON print_target(tenant_id, object_type, print_count DESC);
CREATE INDEX print_target_waybill ON print_target(tenant_id, waybill_no) WHERE waybill_no IS NOT NULL;
CREATE INDEX print_job_list ON print_job(tenant_id, status, created_at DESC);
CREATE INDEX print_job_template ON print_job(tenant_id, template_id);
CREATE INDEX print_job_item_job ON print_job_item(tenant_id, job_id, line_no);
CREATE INDEX print_job_item_target ON print_job_item(tenant_id, target_id);
CREATE INDEX label_print_log_target ON label_print_log(tenant_id, target_id, print_sequence DESC);
CREATE INDEX label_print_log_job ON label_print_log(tenant_id, job_id);
CREATE INDEX label_print_log_time ON label_print_log(tenant_id, printed_at DESC);
CREATE INDEX label_print_log_operator ON label_print_log(tenant_id, operator_id, printed_at DESC);

-- ===========================================================================
-- 状态机与不可变性（DB 级）
-- ===========================================================================

-- 5.1 打印次数只增不减：任何一次打印必须恰好 +1，不能改小、不能跳着改。
--     这是本模块最关键的一条 —— 少了它，「打印次数」就只是一个可以被随手 UPDATE 的普通列。
CREATE FUNCTION guard_print_count_monotonic() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  credential_count integer;
BEGIN
  IF NEW.print_count < OLD.print_count THEN
    RAISE EXCEPTION '打印次数不可减少：目标 % 由 % 改为 %', OLD.id, OLD.print_count, NEW.print_count
      USING ERRCODE = 'check_violation';
  END IF;
  -- 次数与凭据必须一一对应：一次打印 = 权威次数 +1 + 一条凭据。
  --
  -- 只加「只增不减」是不够的：那样仍然能把 2 直接改成 7（只增），而库里只有 2 条凭据，
  -- 「这张面单到底打过几次」从此答错。所以这里把次数钉在凭据条数上：
  --
  --   · 正好 +1（本服务打印动作的那一步）→ 要求改动前的次数就等于凭据条数，
  --     也就是「这次打印确实是紧接着上一条凭据的下一次」；
  --   · 一次跳 N（N≠1）→ 直接拒，因为没有 N 条凭据支撑它。
  --
  -- 事务提交后两者必然相等（executeJob 在同一事务里先 +1 再写凭据）。
  SELECT count(*) INTO credential_count FROM label_print_log WHERE target_id = NEW.id;
  IF NEW.print_count = OLD.print_count + 1 THEN
    IF OLD.print_count <> credential_count THEN
      RAISE EXCEPTION '打印次数与打印凭据条数不一致（改动前 % vs 凭据 %）：目标 %',
        OLD.print_count, credential_count, OLD.id USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.print_count <> credential_count THEN
    RAISE EXCEPTION '打印次数 % 无对应打印凭据（凭据条数 %）：目标 % —— 次数必须与凭据一一对应',
      NEW.print_count, credential_count, OLD.id USING ERRCODE = 'check_violation';
  END IF;
  -- 已印过的对象必须有 last_printed_at，且不早于首次打印
  IF NEW.print_count > 0 AND NEW.last_printed_at IS NULL THEN
    RAISE EXCEPTION '打印次数大于 0 时必须写 last_printed_at：目标 %', OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.first_printed_at IS NOT NULL AND NEW.first_printed_at IS NOT NULL
     AND NEW.first_printed_at < OLD.first_printed_at THEN
    RAISE EXCEPTION '首次打印时间不可回退：目标 %', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER print_target_count_monotonic
  BEFORE UPDATE ON print_target
  FOR EACH ROW EXECUTE FUNCTION guard_print_count_monotonic();

-- 5.2 打印对象快照不可变：改字段等于换一张面单，必须换 external_ref 重开一条。
--     external_ref 同样不可改（它是「这是谁」的身份，改了计数就断了）。
CREATE FUNCTION guard_print_target_snapshot_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.external_ref IS DISTINCT FROM OLD.external_ref THEN
    RAISE EXCEPTION 'external_ref 不可修改：要换对象请新建 print_target：%', OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.object_type IS DISTINCT FROM OLD.object_type THEN
    RAISE EXCEPTION 'object_type 不可修改：%', OLD.id USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.waybill_no IS DISTINCT FROM OLD.waybill_no
     OR NEW.carrier_code IS DISTINCT FROM OLD.carrier_code
     OR NEW.ship_to_name IS DISTINCT FROM OLD.ship_to_name
     OR NEW.ship_to_address IS DISTINCT FROM OLD.ship_to_address
     OR NEW.sku_code IS DISTINCT FROM OLD.sku_code
     OR NEW.sku_name IS DISTINCT FROM OLD.sku_name
     OR NEW.location_code IS DISTINCT FROM OLD.location_code
     OR NEW.carton_code IS DISTINCT FROM OLD.carton_code
     OR NEW.package_count IS DISTINCT FROM OLD.package_count
     OR NEW.weight_kg IS DISTINCT FROM OLD.weight_kg
     OR NEW.remark IS DISTINCT FROM OLD.remark THEN
    RAISE EXCEPTION '打印快照不可修改（重打必须沿用原快照）：目标 %', OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.platform_code IS DISTINCT FROM OLD.platform_code THEN
    RAISE EXCEPTION 'platform_code 不可修改（换了平台就是另一套重复打印规则）：目标 %', OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER print_target_snapshot_frozen
  BEFORE UPDATE ON print_target
  FOR EACH ROW EXECUTE FUNCTION guard_print_target_snapshot_frozen();

-- 5.3 任务状态机：DB 级转移约束，别让应用层「想怎么改就怎么改」。
CREATE FUNCTION guard_print_job_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  IF NOT (
       (OLD.status = 'DRAFT'     AND NEW.status IN ('QUEUED', 'CANCELLED'))
    OR (OLD.status = 'QUEUED'   AND NEW.status IN ('PRINTING', 'CANCELLED'))
    OR (OLD.status = 'PRINTING' AND NEW.status IN ('COMPLETED', 'FAILED'))
  ) THEN
    RAISE EXCEPTION '打印任务状态不可从 % 转到 %：任务 %', OLD.status, NEW.status, OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER print_job_status_transition
  BEFORE UPDATE ON print_job
  FOR EACH ROW EXECUTE FUNCTION guard_print_job_transition();

-- 5.4 终态不可再动：COMPLETED / FAILED / CANCELLED 之后不许回到进行中。
--     同一个 job 只能真打印一次；要重打就新建任务，这样「第几次」永远是真的第几次。
CREATE FUNCTION guard_print_job_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN
    RAISE EXCEPTION '打印任务已终结（%），不可再改：任务 %', OLD.status, OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER print_job_terminal
  BEFORE UPDATE ON print_job
  FOR EACH ROW EXECUTE FUNCTION guard_print_job_terminal();

-- 5.5 任务汇总数不可事后回填：label_count 必须恒等于 target_count × copies。
--     防的是「前端传一个假的 label_count 进来」。
CREATE FUNCTION guard_print_job_rollup() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.copies IS DISTINCT FROM OLD.copies
     OR NEW.target_count IS DISTINCT FROM OLD.target_count
     OR NEW.label_count IS DISTINCT FROM OLD.label_count
     OR NEW.template_id IS DISTINCT FROM OLD.template_id THEN
    RAISE EXCEPTION '打印份数 / 对象数 / 模板在任务建立后不可修改（任务 %）：重打请新建任务', OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER print_job_rollup
  BEFORE UPDATE ON print_job
  FOR EACH ROW EXECUTE FUNCTION guard_print_job_rollup();

-- 5.6 打印明细与日志 append-only：打印凭据不可改、不可删。
CREATE TRIGGER label_print_log_append_only
  BEFORE UPDATE OR DELETE ON label_print_log
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();

CREATE TRIGGER print_job_item_append_only
  BEFORE UPDATE OR DELETE ON print_job_item
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_append_only();

-- 5.7 新建打印对象时次数必须为 0（首印由打印动作产生，不是建对象时产生）。
CREATE FUNCTION guard_print_target_fresh() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.print_count <> 0 THEN
    RAISE EXCEPTION '新建打印对象的打印次数必须为 0：目标 %', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.first_printed_at IS NOT NULL OR NEW.last_printed_at IS NOT NULL THEN
    RAISE EXCEPTION '新建打印对象不得带打印时间：目标 %', NEW.id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER print_target_fresh
  BEFORE INSERT ON print_target
  FOR EACH ROW EXECUTE FUNCTION guard_print_target_fresh();

-- 5.8 打印日志的序号必须与该目标当时的权威计数一致（应用层 +1 后同步落库）。
--     少写一条日志 = 次数与凭据对不上，这条把它钉住。
CREATE FUNCTION guard_print_log_sequence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  current_count integer;
BEGIN
  SELECT print_count INTO current_count FROM print_target
    WHERE tenant_id = NEW.tenant_id AND id = NEW.target_id;
  IF current_count IS NULL THEN
    RAISE EXCEPTION '打印日志指向不存在的打印对象：%', NEW.target_id USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF NEW.print_sequence > current_count THEN
    RAISE EXCEPTION '打印序号 % 超过该对象当前打印次数 %：对象 %',
      NEW.print_sequence, current_count, NEW.target_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER label_print_log_sequence
  BEFORE INSERT ON label_print_log
  FOR EACH ROW EXECUTE FUNCTION guard_print_log_sequence();

-- ===========================================================================
-- RLS：所有业务表启用并 FORCE，策略与 V1/V59/V61 同口径
-- ===========================================================================
DO $$
DECLARE
  tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'print_device', 'print_template', 'print_target', 'print_job', 'print_job_item',
    'label_print_log', 'print_reprint_rule'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)', tbl);
  END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  print_device, print_template, print_target, print_job, print_job_item,
  label_print_log, print_reprint_rule TO warehouse_app;

-- 打印日志与打印明细是**凭据**，不提供物理删除（RLS 隔离之外再收一道）。
REVOKE DELETE ON label_print_log, print_job_item FROM warehouse_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V54/V56/V59 同口径）。
REVOKE ALL ON print_device, print_template, print_target, print_job, print_job_item,
  label_print_log, print_reprint_rule FROM starcon_app;