-- 11.2.3.1 质检与判定（卷 11 售后与退货 / L3 11.2.3 质检与处置的第一格）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：退回商品质检：外观、功能、配件完整性、是否可再销售；给出可再销售/残次/报废判定与残值估算。
--   输入：待检退货；质检模板（按类目）；质检结果。
--   输出：质检结论 + 残值金额 + 质量状态指令。
--   关系：上游 = 11.2.2.1；下游 = 11.2.3.2、12 财务（残值处理）。
--   **可再销售判定要保守**：包装破损但商品完好可判可再销售，缺少配件则应判残次。
--
-- 归属：**trade 服务**（与 11.2.1.x / 11.2.2.1 同一条退货侧单据链）。
--
-- 本迁移**只新建八张表与触发器、零 ALTER 零 DROP 既有表**：
--   * return_inventory_posting / return_receipt_line（V172，11.2.2.1 已交付）**只读**：
--     待检分录与两个判定维度（packaging_condition / goods_condition）是本模块的**输入**；
--     跨表一致性由触发器在**写入时刻**真查一次钉死（刻意不建 FK —— 加 FK 会让本迁移
--     对别的车道的表产生 DDL 依赖，而同批另有三条车道也在往 trade 落迁移）。
--   * sales_order_line / sales_order（V99）**只读**：原值（单价 × 数量）与币种的实时真值。
--     ⚠️ 残值不允许调用方传入 ⇒ 原值只能从这里取，没有第二个来源。
--
-- ===========================================================================
-- 一、两条硬规则各自钉在哪 —— 为什么不能只写在服务层
-- ===========================================================================
--
-- 【硬规则 1】判定要保守，且**两条路径必须不同**：
--   「包装破损但商品完好 → 可再销售」与「缺少配件 → 残次」是蓝图里同一句话的两半，
--   而它们**不可能**由同一个布尔开关表达：一个笼统的 packaging_damaged / missing_parts
--   布尔量没法区分「包装坏了但货是好的（可以卖）」与「配件少了（不能当整机卖）」——
--   而这两件事的处置结论**完全相反**（一个转可售，一个转残次）。
--   ⇒ 落成 return_quality_inspection.verdict 的**三值枚举**（RESALABLE / DEFECTIVE / SCRAPPED），
--     再由触发器 guard_return_quality_verdict 按**三条显式路径**判定它够不够格：
--     ① **配件缺失 ⇒ 只能是 DEFECTIVE**（既不许可再销售，也不许报废 ——
--        缺配件的机器仍然是机器，报废等于把一台能当残次处理的货当废铁处理掉）；
--     ② **商品本体损坏 / 功能失效 ⇒ RESALABLE 被禁**（只能残次或报废）；
--     ③ **外包装破损 / 损毁 / 无包装 ⇒ 一律不禁止 RESALABLE**
--        （这正是蓝图那句加粗句的左半；把包装当成商品来判是这条规则要防的唯一事故）。
--   判定同时看**两个来源**：入库时记的两个维度（V172 的 goods_condition / packaging_condition）
--   与本次质检逐项结果里 FAIL 的项 —— 只看一个的话，另一半就成了可以绕过的后门。
--
-- 【硬规则 2】残值是财务数据，且**必须真算**：
--   ① 传输层**没有**残值字段（调用方想传也传不进来）。
--   ② 原值 / 成色系数 / 档位上限三列由 **DB 触发器**在判定那一刻按模板与逐项结果算出，
--      插入语句里不接受任何调用方取值（照抄 V172 生成待检分录的同一条纪律）。
--   ③ 残值金额由服务层按可审阅规则算出后写入，触发器**重新算一遍并比对**，
--      不相等当场拒 —— 「成色系数 × 原值」这个式子每一步都能被人手算复核。
--   ④ 全部 NUMERIC(19,4) + BigDecimal，**禁浮点**；数量 NUMERIC(19,6)。
--   计算式（人可复核）：
--      原值      base_value   = 订单行单价 × 本行实收数量
--      扣减      deduction    = Σ(FAIL 检查项的 salvage_rate)
--      成色系数  coefficient  = clamp(模板 base_coefficient − deduction, min_coefficient, 1)
--      档位上限  cap          = RESALABLE→resale_cap / DEFECTIVE→defective_cap / SCRAPPED→scrap_cap
--      生效系数  effective    = min(coefficient, cap)
--      残值      salvage      = round(base_value × effective, 4)
--
-- ===========================================================================
-- 二、质检模板为什么要「按类目」，而类目为什么是租户自维护
-- ===========================================================================
-- 蓝图输入句写「质检模板（**按类目**）」。品类不同的退回货，检项与成色口径完全不同
-- （服装看外观与吊牌，家电看功能与配件，配件类根本无主机可检）。
-- ⚠️ **类目真值在 catalog 服务**（1 商品中心），按 AGENTS.md 模块边界纪律**禁止跨服务 SQL / 视图 / FK**，
--   本服务读不到也不该读。而「这个 SKU 属于哪个质检类目」本身是**经营口径**而非商品主数据：
--   同一个 SKU 在不同卖家眼里可以归到不同作业类目。所以本迁移给租户一张自维护的
--   映射表 return_quality_category_sku，**真落库、可增删改、按 sku_id 生效**。
--   没有它就只剩两种假货：写死一份 SKU 前缀表（租户改不动），或者让调用方自己挑类目
--   （挑错就是拿家电模板去检衣服）。
--
-- ===========================================================================
-- 三、状态机：一个状态列到底
-- ===========================================================================
-- DRAFT      草稿：逐项结果还在录，**没有**判定，也没有残值
-- CONCLUDED  已判定：结论 + 残值 + 质量状态指令三者同时成立（由 DB 触发器保证）
-- CANCELLED  已撤销：录错了 / 判错了还没生成指令
-- ⚠️ 结论**终态不可逆**：判定是要拿去和买家、供应商、平台对质的结论，
--   而且报废本身就是**不可逆**的（蓝图 11.2.3.2 原话「报废是不可逆的」），
--   判错了只能作废重判，不能就地改数 —— 那会让「这批货到底判成什么」出现两个答案。
--
-- ⚠️ **本模块刻意不实现 11.2.3.2 的转库存端点**：质量状态指令只是**一张交给下游的数据结构**，
--   消费侧（真正改可售 / 残次 / 报废库存量）属 11.2.3.2，未交付。
--   本模块对这张指令**只有 INSERT 权**（运行角色 UPDATE/DELETE 已收回），
--   因此「在本模块里偷偷把指令改成已消费」没有代码路径也没有 SQL 权限。
--
-- ===========================================================================
-- 四、照片：引用入库时的举证材料，不复制
-- ===========================================================================
-- 退货入库（11.2.2.1）已经按角色拍了照并留了 SHA-256。质检是在那批证据**之上**判断，
--   复制一份照片会让「举证材料有几张、哪一张被改过」出现两个答案，
--   而纠纷里被改过的那一张就是全部问题所在。所以逐项结果只**引用 photo_id**（可空）。

-- ===========================================================================
-- 五、类目与类目-SKU 映射
-- ===========================================================================
CREATE TABLE return_quality_category (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 编码：租户内唯一，报表与模板口径的稳定锚点。改名不改编码。
  category_code varchar(48) NOT NULL CHECK(btrim(category_code) <> ''),
  category_name varchar(128) NOT NULL CHECK(btrim(category_name) <> ''),

  -- 停用是**口径变更**：停用后不再被新质检单选中，已判定的质检单不受影响。
  category_status varchar(12) NOT NULL DEFAULT 'ACTIVE'
      CHECK(category_status IN ('ACTIVE','DISABLED')),

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 1000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,category_code)
);

-- ===========================================================================
-- 五之二、类目 ↔ SKU 映射（租户自维护，见第二节）
-- ===========================================================================
CREATE TABLE return_quality_category_sku (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 本表自己的表之间可以建 FK（**同一条迁移内**的约束不构成跨车道 DDL 依赖）。
  category_id uuid NOT NULL
      REFERENCES return_quality_category(id) ON DELETE RESTRICT,

  -- SKU 真值实时读订单行（照抄 V161 / V165 / V172 纪律）；这里只记 id。
  sku_id uuid NOT NULL,
  -- 冗余一份编码只为「租户在页面上按编码找类目」这一个入口，
  -- **判定与取模板一律按 sku_id**，编码被改名不会影响判定。
  sku_code varchar(64) NOT NULL DEFAULT '',

  mapped_by uuid NOT NULL,
  mapped_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一个 SKU 在一个租户内只能归一个质检类目：
  -- 归两个类目会让「这张质检单该用哪个模板」没有唯一答案。
  UNIQUE(tenant_id,sku_id)
);

-- ===========================================================================
-- 六、质检模板（按类目取用）
-- ===========================================================================
CREATE TABLE return_quality_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  template_no varchar(32) NOT NULL,
  template_name varchar(128) NOT NULL CHECK(btrim(template_name) <> ''),

  -- 归属类目（蓝图输入句「按类目」的落点）。
  category_id uuid NOT NULL
      REFERENCES return_quality_category(id) ON DELETE RESTRICT,

  -- 版本：模板改动会改变判定口径，而历史质检单要能复现当时的口径。
  template_version int NOT NULL CHECK(template_version > 0),

  template_status varchar(12) NOT NULL DEFAULT 'DRAFT'
      CHECK(template_status IN ('DRAFT','ACTIVE','RETIRED')),

  -- ---- 残值估算参数（硬规则 2 的输入，全部 NUMERIC(9,6)，禁浮点）----
  -- 成色基准系数：全部检查项通过时的成色。
  base_coefficient numeric(9,6) NOT NULL
      CHECK(base_coefficient >= 0 AND base_coefficient <= 1),
  -- 成色下限：扣减再狠也不会低于它（残次品仍有残值，不是零）。
  min_coefficient numeric(9,6) NOT NULL
      CHECK(min_coefficient >= 0 AND min_coefficient <= 1),
  -- 三档上限：判定档位给残值封顶。
  --   ⚠️ **可再销售档的上限刻意单独一列**：如果三档共用一个上限，
  --   「判成可再销售」与「判成残次」的残值就没法区分，判定档位也就失去了财务含义。
  resale_cap numeric(9,6) NOT NULL CHECK(resale_cap >= 0 AND resale_cap <= 1),
  defective_cap numeric(9,6) NOT NULL CHECK(defective_cap >= 0 AND defective_cap <= 1),
  scrap_cap numeric(9,6) NOT NULL CHECK(scrap_cap >= 0 AND scrap_cap <= 1),

  note text NOT NULL DEFAULT '' CHECK(length(note) <= 1000),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  activated_by uuid,
  activated_at timestamptz,
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,template_no),
  -- 同一类目同一版本号唯一：两个内容不同却同号的模板会让历史质检单无法复现口径。
  UNIQUE(tenant_id,category_id,template_version),

  CHECK(min_coefficient <= base_coefficient),
  CHECK(scrap_cap <= defective_cap AND defective_cap <= resale_cap),
  -- 激活必须留人与时刻：判定口径是谁在什么时候定的，事后要问得出来。
  CHECK(template_status <> 'ACTIVE' OR (activated_by IS NOT NULL AND activated_at IS NOT NULL))
);

-- ===========================================================================
-- 六之二、模板检查项（外观 / 功能 / 配件完整性 —— 职责句的三个动词）
-- ===========================================================================
CREATE TABLE return_quality_template_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  template_id uuid NOT NULL
      REFERENCES return_quality_template(id) ON DELETE CASCADE,

  item_code varchar(48) NOT NULL CHECK(btrim(item_code) <> ''),
  item_name varchar(128) NOT NULL CHECK(btrim(item_name) <> ''),

  -- 检查维度：**四个显式取值，不是笼统的 condition**。
  --   APPEARANCE / PACKAGING 失败**不禁止**可再销售（外观瑕疵可折价再售）；
  --   FUNCTION / ACCESSORY    失败**禁止**可再销售（功能坏了、配件少了就不是原来那台机器了）。
  -- 蓝图那句「包装破损但商品完好可判可再销售，缺少配件则应判残次」就落在这四列上。
  item_dimension varchar(16) NOT NULL CHECK(item_dimension IN (
      'APPEARANCE','FUNCTION','ACCESSORY','PACKAGING'
  )),

  -- 该项失败时对成色系数的扣减（硬规则 2 的 deduction 项）。
  salvage_rate numeric(9,6) NOT NULL DEFAULT 0
      CHECK(salvage_rate >= 0 AND salvage_rate <= 1),

  -- 强制项：判定前必须逐项有结果（PASS / FAIL / NOT_APPLICABLE 三选一，不允许留空）。
  -- 没有强制项的模板等于「什么都不用检就能判」，那不是质检模板。
  item_required boolean NOT NULL DEFAULT true,

  -- 期望值与判定提示：给人看的，不参与计算（计算只用维度 + salvage_rate）。
  expected_value varchar(128) NOT NULL DEFAULT '',
  fail_hint text NOT NULL DEFAULT '' CHECK(length(fail_hint) <= 1000),

  item_no int NOT NULL CHECK(item_no > 0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(template_id,item_code),
  UNIQUE(template_id,item_no)
);

-- ===========================================================================
-- 七、质检单：判定 + 残值（蓝图输出句的前两样）
-- ===========================================================================
CREATE TABLE return_quality_inspection (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  inspection_no varchar(32) NOT NULL,

  -- ---- 上游：待检入库分录（V172）。**一张分录同时只能有一张在途质检单**（部分唯一索引）。----
  posting_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  receipt_line_id uuid NOT NULL,

  -- ---- 质检模板：判定口径的锚点。判定后**不改**（复现历史口径的前提）。----
  template_id uuid NOT NULL
      REFERENCES return_quality_template(id) ON DELETE RESTRICT,
  template_version int NOT NULL CHECK(template_version > 0),
  category_id uuid NOT NULL,
  -- SKU 快照：判定与残值都要在订单行改名之后仍然读得出「检的是哪件货」
  --（与 V172 的 return_inventory_posting 同一纪律：**交接凭据自带快照**）。
  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',

  -- 被检数量 = 本次质检覆盖的实收数量（真实值，由触发器从实收行取）。
  inspected_qty numeric(19,6) NOT NULL CHECK(inspected_qty > 0),

  -- ---- 判定状态机（第三节）----
  inspection_status varchar(16) NOT NULL CHECK(inspection_status IN (
      'DRAFT','CONCLUDED','CANCELLED'
  )),

  -- ---- 判定结论（硬规则 1）。**三值枚举，不是布尔开关。**----
  verdict varchar(16) CHECK(verdict IS NULL OR verdict IN (
      'RESALABLE','DEFECTIVE','SCRAPPED'
  )),
  verdict_label varchar(64) NOT NULL DEFAULT '',
  -- 判定理由：必须写。判残次要拿去向供应商与平台索赔，判报废要经审批，
  -- 「点一下下拉框」出来的结论在纠纷里等于没有结论。
  conclusion_note text NOT NULL DEFAULT '' CHECK(length(conclusion_note) <= 2000),

  -- ---- 残值（硬规则 2）：下面四列全部由 DB 触发器算，调用方一个都传不进来 ----
  -- 原值 = 订单行单价 × 实收数量（币种取订单头）。
  original_value numeric(19,4) NOT NULL DEFAULT 0 CHECK(original_value >= 0),
  salvage_currency char(3) NOT NULL DEFAULT 'USD' CHECK(salvage_currency ~ '^[A-Z]{3}$'),
  -- 扣减 / 成色系数 / 档位上限：留成列是为了让「成色系数 × 原值」能被**手算复核**。
  salvage_deduction numeric(9,6) NOT NULL DEFAULT 0
      CHECK(salvage_deduction >= 0 AND salvage_deduction <= 1),
  grade_coefficient numeric(9,6) NOT NULL DEFAULT 1
      CHECK(grade_coefficient >= 0 AND grade_coefficient <= 1),
  salvage_cap numeric(9,6) NOT NULL DEFAULT 1
      CHECK(salvage_cap >= 0 AND salvage_cap <= 1),
  -- 残值金额本身：服务层按可审阅规则算出后写入，触发器**重算比对**，不等就拒。
  salvage_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(salvage_amount >= 0),

  -- ---- 判定人 / 判定时刻 ----
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  concluded_by uuid,
  concluded_at timestamptz,
  cancelled_by uuid,
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '' CHECK(length(cancel_reason) <= 1000),

  -- 乐观锁：录逐项结果与判定都要带 expected_version。
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,inspection_no),

  -- **残值不得超过原值**：超过原值的「残值」不是残值，是把损失算成了收入。
  CHECK(salvage_amount <= original_value),

  -- 已判定必须三样齐全：结论、判定理由、人与时刻。
  CHECK(inspection_status <> 'CONCLUDED'
        OR (verdict IS NOT NULL
            AND length(btrim(conclusion_note)) > 0
            AND concluded_by IS NOT NULL AND concluded_at IS NOT NULL)),
  -- 未判定不许带结论（也不能带残值：残值是判定的产物）。
  CHECK(inspection_status = 'CONCLUDED'
        OR (verdict IS NULL AND salvage_amount = 0 AND concluded_at IS NULL)),
  -- 撤销必须说明理由。
  CHECK(inspection_status <> 'CANCELLED' OR (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)),
  CHECK(inspection_status <> 'CANCELLED' OR length(btrim(cancel_reason)) > 0)
);

-- ===========================================================================
-- 八、逐项检查结果（蓝图输入句「质检结果」的落点）
-- ===========================================================================
CREATE TABLE return_quality_check_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  inspection_id uuid NOT NULL
      REFERENCES return_quality_inspection(id) ON DELETE CASCADE,
  template_item_id uuid NOT NULL
      REFERENCES return_quality_template_item(id) ON DELETE RESTRICT,

  -- 检项快照：模板改版后历史质检单仍要显示「当时检的是什么」。
  item_code varchar(48) NOT NULL,
  item_name varchar(128) NOT NULL,
  item_dimension varchar(16) NOT NULL CHECK(item_dimension IN (
      'APPEARANCE','FUNCTION','ACCESSORY','PACKAGING'
  )),

  -- 三值结果，**刻意不给「未检」**：未检就是这一行还不存在。
  -- 给一个 UNCHECKED 取值的话，「判定时漏了一项」和「判定为不适用」在库里同形。
  check_outcome varchar(16) NOT NULL CHECK(check_outcome IN (
      'PASS','FAIL','NOT_APPLICABLE'
  )),

  check_note text NOT NULL DEFAULT '' CHECK(length(check_note) <= 1000),
  -- 举证照片：引用 11.2.2.1 已登记的照片（**不复制**，见第四节）。
  photo_id uuid,

  checked_by uuid NOT NULL,
  checked_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一个检项在一张质检单里只能有一条结果：两条结果会让「这个项到底过没过」没有答案。
  UNIQUE(inspection_id,template_item_id),
  -- 失败必须写说明：判残次要拿去索赔，没有理由的 FAIL 在纠纷里等于没有 FAIL。
  CHECK(check_outcome <> 'FAIL' OR length(btrim(check_note)) > 0)
);

-- ===========================================================================
-- 九、质量状态指令（蓝图输出句第三样；**只出，不消费**）
-- ===========================================================================
-- ⚠️ 本表是交给 11.2.3.2 与 12 财务的**交接凭据**，由 DB 触发器在判定那一刻生成，
--   服务层不接受调用方传入 ⇒ 「凭空造一张『这批货可再销售』的指令」在本模块没有代码路径。
--   本模块**刻意不提供**消费端点（转可售 / 残次 / 报废属 11.2.3.2，未交付），
--   运行角色 UPDATE/DELETE 已收回，所以也没有「在本模块里把它标成已消费」的可能。
CREATE TABLE return_quality_disposition_instruction (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 一张质检单恰好一条指令：两张指令会让下游收到互相矛盾的两份命令。
  inspection_id uuid NOT NULL UNIQUE
      REFERENCES return_quality_inspection(id) ON DELETE RESTRICT,
  receipt_id uuid NOT NULL,
  receipt_line_id uuid NOT NULL,

  instruction_type varchar(24) NOT NULL CHECK(instruction_type IN (
      'MOVE_TO_SELLABLE','MOVE_TO_DEFECTIVE','WRITE_OFF_SCRAP'
  )),
  -- 目标库存状态：与指令类型一一对应（表级 CHECK 再钉一道）。
  target_inventory_state varchar(16) NOT NULL CHECK(target_inventory_state IN (
      'SELLABLE','DEFECTIVE','SCRAP'
  )),

  -- 标的物快照（跨服务交接凭据自带快照，与 V172 待检分录同一纪律）。
  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',

  -- 数量与残值：数量 = 本次质检数量；残值供 12 财务做损耗处理。
  qty numeric(19,6) NOT NULL CHECK(qty > 0),
  salvage_amount numeric(19,4) NOT NULL CHECK(salvage_amount >= 0),
  salvage_currency char(3) NOT NULL CHECK(salvage_currency ~ '^[A-Z]{3}$'),
  original_value numeric(19,4) NOT NULL CHECK(original_value >= 0),
  grade_coefficient numeric(9,6) NOT NULL
      CHECK(grade_coefficient >= 0 AND grade_coefficient <= 1),

  -- 消费侧：11.2.3.2 未交付 ⇒ 恒为 PENDING，本模块**没有**任何把它推进的代码路径。
  instruction_status varchar(16) NOT NULL DEFAULT 'PENDING'
      CHECK(instruction_status = 'PENDING'),
  consumer_module varchar(16) NOT NULL DEFAULT '11.2.3.2'
      CHECK(consumer_module = '11.2.3.2'),

  issued_at timestamptz NOT NULL DEFAULT now(),
  issued_by uuid NOT NULL,

  UNIQUE(tenant_id,id),

  CHECK(salvage_amount <= original_value),
  -- 指令类型 ↔ 目标状态一一对应：MOVE_TO_SELLABLE 必须是 SELLABLE，以此类推。
  CHECK((instruction_type = 'MOVE_TO_SELLABLE' AND target_inventory_state = 'SELLABLE')
     OR (instruction_type = 'MOVE_TO_DEFECTIVE' AND target_inventory_state = 'DEFECTIVE')
     OR (instruction_type = 'WRITE_OFF_SCRAP' AND target_inventory_state = 'SCRAP'))
);

-- ===========================================================================
-- 十、质检留痕（追加式）
-- ===========================================================================
-- 「这批货到底什么时候判的、判成了什么」是要拿去和买家 / 供应商 / 平台对质的。
CREATE TABLE return_quality_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  inspection_id uuid NOT NULL,
  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'CREATED','RESULT_RECORDED','CONCLUDED','CANCELLED'
  )),
  from_status varchar(16),
  to_status varchar(16) NOT NULL,

  -- 事件专属信息：判定事件记结论与残值（"判成了可再销售、残值 0" 是要引用的那一行）。
  verdict varchar(16),
  salvage_amount numeric(19,4),

  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(from_status IS NULL OR from_status <> to_status)
);

-- ===========================================================================
-- 十一、DB 守卫（服务层校验可以绕开，触发器绕不开）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 守卫① guard_return_quality_category_sku：映射必须指向本租户的真实类目
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_return_quality_category_sku() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(12);
BEGIN
  SELECT category_status INTO t_status
    FROM return_quality_category
   WHERE tenant_id = NEW.tenant_id AND id = NEW.category_id;

  -- 少了这一段，「A 租户的映射指向 B 租户的类目」会完全合法。
  IF NOT FOUND THEN
    RAISE EXCEPTION '质检类目不存在或不属于当前租户：category_id=%', NEW.category_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '不能把 SKU 映射到已停用的质检类目（当前 %）：category_id=%', t_status, NEW.category_id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_quality_category_sku
  BEFORE INSERT OR UPDATE OF category_id ON return_quality_category_sku
  FOR EACH ROW EXECUTE FUNCTION guard_return_quality_category_sku();

-- ---------------------------------------------------------------------------
-- 守卫② guard_return_quality_template_item：检项只能挂在**本租户**的模板上，且模板在编时才能改
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_return_quality_template_item() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_tenant uuid;
  t_status varchar(12);
BEGIN
  SELECT tenant_id, template_status INTO t_tenant, t_status
    FROM return_quality_template
   WHERE id = NEW.template_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '质检模板不存在：template_id=%', NEW.template_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- ⚠️ 跨租户挂载：模板 id 是 UUID，猜不到不等于防得住，而这一段只有一行。
  IF t_tenant <> NEW.tenant_id THEN
    RAISE EXCEPTION '质检模板不属于当前租户：template_id=%', NEW.template_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_status <> 'DRAFT' THEN
    RAISE EXCEPTION '质检模板已不是草稿（当前 %），检项不能再改：template_id=%', t_status, NEW.template_id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_quality_template_item
  BEFORE INSERT OR UPDATE OF template_id ON return_quality_template_item
  FOR EACH ROW EXECUTE FUNCTION guard_return_quality_template_item();

-- ---------------------------------------------------------------------------
-- 守卫③ guard_return_quality_check_result：检项必须属于**本单用的那个模板**，且单还在草稿
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_return_quality_check_result() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_template uuid;
  t_status varchar(16);
  t_item_tenant uuid;
BEGIN
  SELECT template_id, inspection_status INTO t_template, t_status
    FROM return_quality_inspection
   WHERE tenant_id = NEW.tenant_id AND id = NEW.inspection_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '质检单不存在或不属于当前租户：inspection_id=%', NEW.inspection_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 已判定的结论是终态：逐项结果在那之后一律不许动（第三节）。
  IF t_status <> 'DRAFT' THEN
    RAISE EXCEPTION '质检单已不是草稿（当前 %），逐项结果不能再改：inspection_id=%', t_status, NEW.inspection_id
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT tenant_id INTO t_item_tenant
    FROM return_quality_template_item
   WHERE id = NEW.template_item_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '模板检项不存在：item_id=%', NEW.template_item_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_item_tenant <> NEW.tenant_id THEN
    RAISE EXCEPTION '模板检项不属于当前租户：item_id=%', NEW.template_item_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 检项必须来自**这张质检单实际使用的模板**：
  -- 引用别的模板的检项会让「按哪个口径判的」在库里答不出来。
  IF NOT EXISTS (
    SELECT 1 FROM return_quality_template_item i
     WHERE i.id = NEW.template_item_id AND i.template_id = t_template
  ) THEN
    RAISE EXCEPTION '检项不属于本质检单使用的模板（template_id=%）：item_id=%', t_template, NEW.template_item_id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_quality_check_result
  BEFORE INSERT OR UPDATE OF template_item_id, check_outcome ON return_quality_check_result
  FOR EACH ROW EXECUTE FUNCTION guard_return_quality_check_result();

-- ---------------------------------------------------------------------------
-- 守卫④ guard_return_quality_inspection_source：**标的物与数量由 DB 取**，不接受传入
-- ---------------------------------------------------------------------------
-- 建质检单时，检的是哪一行实收、几件、哪个 SKU、属于哪个质检类目 —— 这四件事
-- 全都是**待检分录与实收行的真值**，不是调用方的判断。
-- 由触发器填 ⇒ 「只检了 1 件却按 3 件算残值」「拿 A 单的分录配 B 单的行」在本模块没有代码路径。
CREATE FUNCTION guard_return_quality_inspection_source() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_posting record;
  t_category uuid;
BEGIN
  SELECT p.receipt_id, p.receipt_line_id, p.sku_id, p.sku_code, p.sku_name,
         p.batch_no, p.inventory_state, rl.received_qty,
         ol.sku_code as order_sku_code, ol.sku_name as order_sku_name
    INTO t_posting
    FROM return_inventory_posting p
    JOIN return_receipt_line rl
      ON rl.tenant_id = p.tenant_id AND rl.id = p.receipt_line_id
    JOIN rma_authorization_line al
      ON al.tenant_id = rl.tenant_id AND al.id = rl.rma_authorization_line_id
    JOIN aftersales_case_line cl
      ON cl.tenant_id = al.tenant_id AND cl.id = al.case_line_id
    JOIN sales_order_line ol
      ON ol.tenant_id = cl.tenant_id AND ol.id = cl.order_line_id
   WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.posting_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '待检入库分录不存在或不属于当前租户：posting_id=%', NEW.posting_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_posting.inventory_state <> 'PENDING_INSPECTION' THEN
    RAISE EXCEPTION '该入库分录已不在待检状态（当前 %），不能开质检单', t_posting.inventory_state
      USING ERRCODE = 'check_violation';
  END IF;

  -- 分录上的 SKU 快照优先：它是入库那一刻的交接凭据，订单行改名不该改写已入库这批货的身份。
  NEW.receipt_id      := t_posting.receipt_id;
  NEW.receipt_line_id := t_posting.receipt_line_id;
  NEW.sku_id          := t_posting.sku_id;
  NEW.sku_code        := coalesce(nullif(t_posting.sku_code, ''), t_posting.order_sku_code, '');
  NEW.sku_name        := coalesce(nullif(t_posting.sku_name, ''), t_posting.order_sku_name, '');
  NEW.batch_no        := coalesce(t_posting.batch_no, '');
  NEW.inspected_qty   := t_posting.received_qty;

  -- 质检类目：由租户维护的 SKU→类目映射解析（见第二节）。
  SELECT m.category_id INTO t_category
    FROM return_quality_category_sku m
   WHERE m.tenant_id = NEW.tenant_id AND m.sku_id = NEW.sku_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'SKU % 还没有归到任何质检类目，无法按类目取用质检模板（类目与 SKU 的映射由租户维护；类目真值在 catalog 服务，本服务跨库读不到）', NEW.sku_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_category <> NEW.category_id THEN
    RAISE EXCEPTION '质检模板所属类目与该 SKU 实际所属质检类目不一致：模板类目 %，SKU % 的质检类目 %',
      NEW.category_id, NEW.sku_id, t_category
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_quality_inspection_source
  BEFORE INSERT ON return_quality_inspection
  FOR EACH ROW EXECUTE FUNCTION guard_return_quality_inspection_source();

-- ---------------------------------------------------------------------------
-- 守卫⑤ guard_return_quality_verdict：**硬规则 1 + 硬规则 2 的落点**
-- ---------------------------------------------------------------------------
-- 只在状态推到 CONCLUDED 时动作（改草稿要素不触发）。它做三件事：
--   ① 逐项结果必须**覆盖全部检项**（强制项至少要有结果）；
--   ② 按三条显式路径判定 NEW.verdict 够不够格（硬规则 1）；
--   ③ 重算原值 / 扣减 / 成色系数 / 档位上限，**并比对残值金额**（硬规则 2）。
CREATE FUNCTION guard_return_quality_verdict() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_template record;
  t_intake record;
  t_item_count int;
  t_mandatory_missing int;
  t_fail_accessory int;
  t_fail_function int;
  t_deduction numeric(19,6);
  t_coefficient numeric(19,6);
  t_cap numeric(9,6);
  t_effective numeric(19,6);
  t_base numeric(19,6);
  t_expected numeric(19,4);
  t_currency char(3);
BEGIN
  IF NEW.inspection_status IS DISTINCT FROM 'CONCLUDED'
     OR NEW.inspection_status IS NOT DISTINCT FROM OLD.inspection_status THEN
    RETURN NEW;
  END IF;

  -- ---- 模板口径：必须是 ACTIVE（拿 RETIRED 模板判定的货没法复现口径）----
  SELECT * INTO t_template
    FROM return_quality_template
   WHERE tenant_id = NEW.tenant_id AND id = NEW.template_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '质检模板不存在或不属于当前租户：template_id=%', NEW.template_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_template.template_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '质检模板未激活（当前 %），不能用于判定：template_id=%',
      t_template.template_status, NEW.template_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_template.template_version <> NEW.template_version THEN
    RAISE EXCEPTION '质检单记录的模板版本（%）与模板当前版本（%）不一致，判定口径会算错',
      NEW.template_version, t_template.template_version
      USING ERRCODE = 'check_violation';
  END IF;

  -- ---- ① 逐项结果必须覆盖全部检项 ----
  SELECT count(*) INTO t_item_count
    FROM return_quality_template_item i
   WHERE i.tenant_id = NEW.tenant_id AND i.template_id = NEW.template_id;

  IF t_item_count = 0 THEN
    RAISE EXCEPTION '质检模板没有任何检项，不能判定（没有检项的模板不是质检模板）：template_id=%',
      NEW.template_id USING ERRCODE = 'check_violation';
  END IF;

  -- 强制项必须逐项有结果；非强制项允许整体不检（「这批货不适用这套模板」是真实业务）。
  SELECT count(*) INTO t_mandatory_missing
    FROM return_quality_template_item i
   WHERE i.tenant_id = NEW.tenant_id AND i.template_id = NEW.template_id AND i.item_required
     AND NOT EXISTS (
       SELECT 1 FROM return_quality_check_result cr
        WHERE cr.tenant_id = NEW.tenant_id
          AND cr.inspection_id = NEW.id
          AND cr.template_item_id = i.id);

  IF t_mandatory_missing > 0 THEN
    RAISE EXCEPTION '还有 % 个强制检项没有结果，不能判定', t_mandatory_missing
      USING ERRCODE = 'check_violation';
  END IF;

  -- 结论不能为空：放在最前面判，是为了给「没传 verdict」一个能看懂的错，
  -- 而不是让它一路走到残值比对再报一个「残值金额不一致」。
  IF NEW.verdict IS NULL THEN
    RAISE EXCEPTION '判定必须给出结论（RESALABLE 可再销售 / DEFECTIVE 残次 / SCRAPPED 报废）'
      USING ERRCODE = 'check_violation';
  END IF;

  -- ---- ② 硬规则 1：三条显式路径 ----
  -- 入库时记的两个判定维度（V172）是**第一条证据链**：
  SELECT rl.goods_condition, rl.packaging_condition, p.inventory_state
    INTO t_intake
    FROM return_inventory_posting p
    JOIN return_receipt_line rl
      ON rl.tenant_id = p.tenant_id AND rl.id = p.receipt_line_id
   WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.posting_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '待检入库分录不存在或不属于当前租户：posting_id=%', NEW.posting_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 待检分录必须仍在待检：已流转走的货不该再判一次。
  IF t_intake.inventory_state <> 'PENDING_INSPECTION' THEN
    RAISE EXCEPTION '该入库分录已不在待检状态（当前 %），不能重复判定', t_intake.inventory_state
      USING ERRCODE = 'check_violation';
  END IF;

  -- 路径 ①：**配件缺失 ⇒ 只能是 DEFECTIVE**（蓝图加粗句的右半）。
  --   入库维度与本次逐项结果**任一**指向缺配件都走这条路径 ——
  --   只看其中一边的话，另一边就成了可以绕过的后门。
  SELECT count(*) INTO t_fail_accessory
    FROM return_quality_check_result cr
   WHERE cr.tenant_id = NEW.tenant_id AND cr.inspection_id = NEW.id
     AND cr.check_outcome = 'FAIL' AND cr.item_dimension = 'ACCESSORY';

  -- 功能类检项失败与「商品本体损坏」走同一条路径②（都禁止可再销售）。
  SELECT count(*) INTO t_fail_function
    FROM return_quality_check_result cr
   WHERE cr.tenant_id = NEW.tenant_id AND cr.inspection_id = NEW.id
     AND cr.check_outcome = 'FAIL' AND cr.item_dimension = 'FUNCTION';

  IF t_intake.goods_condition = 'MISSING_PARTS' OR t_fail_accessory > 0 THEN
    IF NEW.verdict IS DISTINCT FROM 'DEFECTIVE' THEN
      RAISE EXCEPTION '配件缺失必须判残次（当前 verdict=%）：入库维度 goods_condition=%，本次配件类检项失败数 %。蓝图加粗句「缺少配件则应判残次」—— 缺配件的机器仍然是机器，判可再销售等于当整机卖，判报废等于把能作残次处理的货当废铁丢掉',
        coalesce(NEW.verdict, '<空>'), t_intake.goods_condition, t_fail_accessory
        USING ERRCODE = 'check_violation';
    END IF;
  -- 路径 ②：**商品本体损坏 / 功能失效 ⇒ 可再销售被禁**。
  ELSIF t_intake.goods_condition IN ('DAMAGED','DEFECTIVE') OR t_fail_function > 0 THEN
    IF NEW.verdict = 'RESALABLE' THEN
      RAISE EXCEPTION '商品本体损坏或功能失效不能判可再销售（当前 verdict=RESALABLE）：入库维度 goods_condition=%，本次功能类检项失败数 %',
        t_intake.goods_condition, t_fail_function
        USING ERRCODE = 'check_violation';
    END IF;
  -- 路径 ③：**外包装破损 / 损毁 / 无包装 一律不禁止可再销售** ——
  --   蓝图加粗句的左半。这里刻意**什么都不判**：把 packaging_condition 引进
  --   「能不能判可再销售」是把包装当成商品，正是这条规则要防的事。
  END IF;

  -- ---- ③ 硬规则 2：残值真算（人可复核的式子）----
  -- 原值 = 订单行单价 × **本行实收数量**（照抄 V172 纪律：SKU 与单价实时读订单行，
  -- 不在本模块复制一份单价快照 —— 订单行改价之后残值要跟着改）。
  SELECT coalesce(ol.unit_price, 0) * coalesce(rl.received_qty, 0), o.currency
    INTO t_base, t_currency
    FROM return_receipt_line rl
    JOIN rma_authorization_line al
      ON al.tenant_id = rl.tenant_id AND al.id = rl.rma_authorization_line_id
    JOIN aftersales_case_line cl
      ON cl.tenant_id = al.tenant_id AND cl.id = al.case_line_id
    JOIN sales_order_line ol
      ON ol.tenant_id = cl.tenant_id AND ol.id = cl.order_line_id
    JOIN sales_order o
      ON o.tenant_id = ol.tenant_id AND o.id = ol.order_id
   WHERE rl.tenant_id = NEW.tenant_id AND rl.id = NEW.receipt_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '实收行不存在或订单行真值读不到，无法估算原值：receipt_line_id=%', NEW.receipt_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  t_base := coalesce(t_base, 0);

  SELECT coalesce(sum(i.salvage_rate), 0) INTO t_deduction
    FROM return_quality_check_result cr
    JOIN return_quality_template_item i
      ON i.tenant_id = cr.tenant_id AND i.id = cr.template_item_id
   WHERE cr.tenant_id = NEW.tenant_id AND cr.inspection_id = NEW.id
     AND cr.check_outcome = 'FAIL';

  t_coefficient := greatest(
      t_template.base_coefficient - coalesce(t_deduction, 0),
      t_template.min_coefficient);
  -- 成色系数不可能超过 1（扣减只会往下走，但 base_coefficient 本身可能被填成 1.000000）。
  t_coefficient := least(t_coefficient, 1);

  t_cap := CASE NEW.verdict
      WHEN 'RESALABLE'  THEN t_template.resale_cap
      WHEN 'DEFECTIVE'  THEN t_template.defective_cap
      ELSE t_template.scrap_cap
    END;

  t_effective := least(t_coefficient, t_cap);
  t_expected := round(t_base * t_effective, 4);

  -- 四个数由触发器填（不接受调用方取值），残值金额比对。
  NEW.original_value    := round(t_base, 4);
  NEW.salvage_deduction := round(coalesce(t_deduction, 0), 6);
  NEW.grade_coefficient := round(t_coefficient, 6);
  NEW.salvage_cap       := round(t_cap, 6);
  NEW.salvage_currency  := coalesce(t_currency, 'USD');

  IF NEW.salvage_amount IS DISTINCT FROM t_expected THEN
    RAISE EXCEPTION '残值金额与「原值 × 生效系数」算出来的不一致：提交 %，应为 round(% × %, 4) = %',
      NEW.salvage_amount, round(t_base, 4), round(t_effective, 6), t_expected
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_quality_verdict
  BEFORE UPDATE OF inspection_status ON return_quality_inspection
  FOR EACH ROW EXECUTE FUNCTION guard_return_quality_verdict();

-- ---------------------------------------------------------------------------
-- 触发器⑤ return_quality_append_instruction：**质量状态指令由 DB 生成**（输出句第三样）
-- ---------------------------------------------------------------------------
CREATE FUNCTION return_quality_append_instruction() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.inspection_status IS DISTINCT FROM 'CONCLUDED'
     OR NEW.inspection_status IS NOT DISTINCT FROM OLD.inspection_status THEN
    RETURN NEW;
  END IF;

  -- 下面两列全是**由结论派生**的常量或直取质检单，不接受任何调用方传入值：
  --   instruction_type / target_inventory_state 由 verdict 三分支派生，
  --   数量与残值直取质检单（残值本身已被守卫④按可审阅规则校验过）。
  INSERT INTO return_quality_disposition_instruction(
      id, tenant_id, inspection_id, receipt_id, receipt_line_id,
      instruction_type, target_inventory_state,
      sku_id, sku_code, sku_name, batch_no,
      qty, salvage_amount, salvage_currency, original_value, grade_coefficient,
      instruction_status, consumer_module, issued_at, issued_by)
  VALUES (
      gen_random_uuid(),
      NEW.tenant_id,
      NEW.id,
      NEW.receipt_id,
      NEW.receipt_line_id,
      CASE NEW.verdict
        WHEN 'RESALABLE' THEN 'MOVE_TO_SELLABLE'
        WHEN 'DEFECTIVE' THEN 'MOVE_TO_DEFECTIVE'
        ELSE 'WRITE_OFF_SCRAP'
      END,
      CASE NEW.verdict
        WHEN 'RESALABLE' THEN 'SELLABLE'
        WHEN 'DEFECTIVE' THEN 'DEFECTIVE'
        ELSE 'SCRAP'
      END,
      NEW.sku_id,
      NEW.sku_code,
      NEW.sku_name,
      NEW.batch_no,
      NEW.inspected_qty,
      NEW.salvage_amount,
      NEW.salvage_currency,
      NEW.original_value,
      NEW.grade_coefficient,
      'PENDING',
      '11.2.3.2',
      coalesce(NEW.concluded_at, now()),
      NEW.concluded_by);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_return_quality_instruction
  AFTER UPDATE OF inspection_status ON return_quality_inspection
  FOR EACH ROW EXECUTE FUNCTION return_quality_append_instruction();

-- ---------------------------------------------------------------------------
-- 留痕追加（只写「真变了」的状态变更）
-- ---------------------------------------------------------------------------
CREATE FUNCTION return_quality_append_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.inspection_status = OLD.inspection_status THEN
    RETURN NEW;
  END IF;

  INSERT INTO return_quality_event(
      id, tenant_id, inspection_id, event_type, from_status, to_status,
      verdict, salvage_amount, reason_note, occurred_by, occurred_at)
  VALUES (
      gen_random_uuid(),
      NEW.tenant_id,
      NEW.id,
      CASE
        WHEN TG_OP = 'INSERT'                THEN 'CREATED'
        WHEN NEW.inspection_status = 'CONCLUDED' THEN 'CONCLUDED'
        WHEN NEW.inspection_status = 'CANCELLED' THEN 'CANCELLED'
        ELSE 'UPDATED'
      END,
      CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.inspection_status END,
      NEW.inspection_status,
      -- 判定事件带上结论与残值：「判成了可再销售、残值 0」是事后要被引用的那一行。
      CASE WHEN NEW.inspection_status = 'CONCLUDED' THEN NEW.verdict ELSE NULL END,
      CASE WHEN NEW.inspection_status = 'CONCLUDED' THEN NEW.salvage_amount ELSE NULL END,
      CASE
        WHEN TG_OP = 'INSERT'                THEN '登记质检草稿'
        WHEN NEW.inspection_status = 'CONCLUDED'
          THEN NEW.verdict_label || '：' || coalesce(nullif(btrim(NEW.conclusion_note), ''), '未写理由')
        WHEN NEW.inspection_status = 'CANCELLED'
          THEN coalesce(nullif(btrim(NEW.cancel_reason), ''), '人工撤销')
        ELSE '草稿要素变更'
      END,
      coalesce(NEW.created_by, NEW.concluded_by, NEW.cancelled_by),
      -- ⚠️ **每类事件用它自己那一刻**，不是 coalesce 出一列通用值。
      CASE
        WHEN TG_OP = 'INSERT'                  THEN NEW.created_at
        WHEN NEW.inspection_status = 'CONCLUDED' THEN NEW.concluded_at
        WHEN NEW.inspection_status = 'CANCELLED' THEN NEW.cancelled_at
        ELSE now()
      END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_return_quality_event
  AFTER INSERT OR UPDATE OF inspection_status ON return_quality_inspection
  FOR EACH ROW EXECUTE FUNCTION return_quality_append_event();

-- ---------------------------------------------------------------------------
-- 守卫⑥ guard_return_quality_inspection_frozen：已判定的结论终态不可逆
-- ---------------------------------------------------------------------------
-- 一旦判定或撤销，这一行**整行**冻结。
-- 刻意不做「只冻结结论与残值、其余要素还能改」：判完之后这张单要拿去和买家、
-- 供应商、平台对质，「当时检的是哪件货、用了哪版模板」与结论是一起的 ——
-- 允许事后改 SKU 或模板，等于让同一个结论可以配上一份不同的证据。
CREATE FUNCTION guard_return_quality_inspection_frozen() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.inspection_status IN ('CONCLUDED','CANCELLED') AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION '质检单已% ，是终态不可修改（判错了请作废后重判）：inspection_id=%',
      OLD.inspection_status, OLD.id USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_quality_inspection_frozen
  BEFORE UPDATE ON return_quality_inspection
  FOR EACH ROW EXECUTE FUNCTION guard_return_quality_inspection_frozen();

-- ===========================================================================
-- 十二、索引与发号器
-- ===========================================================================
-- ① **待检队列（最高频读）**：已入库分录里还没有在途质检单的那些，按入库时刻排检。
--    部分索引只看在途分录，已判定的分录不再命中 —— 这是队列的天然推进。
CREATE INDEX return_quality_pending_queue
  ON return_inventory_posting(tenant_id, posted_at)
  WHERE inventory_state = 'PENDING_INSPECTION';
-- ② 按单取质检单列表。
CREATE INDEX return_quality_inspection_by_receipt
  ON return_quality_inspection(tenant_id, receipt_id, created_at);
-- ③ 按 SKU 反查：这批货被退过几次、每次判成什么。
CREATE INDEX return_quality_inspection_by_sku
  ON return_quality_inspection(tenant_id, sku_id, created_at)
  WHERE sku_id IS NOT NULL;
-- ④ 判定档位统计（残值口径报表的取数路径）。
CREATE INDEX return_quality_inspection_by_verdict
  ON return_quality_inspection(tenant_id, verdict, concluded_at);
-- ⑤ 模板列表按类目。
CREATE INDEX return_quality_template_by_category
  ON return_quality_template(tenant_id, category_id, template_status);
-- ⑥ 检项按模板读（判定时的覆盖性校验与结果渲染）。
CREATE INDEX return_quality_template_item_by_template
  ON return_quality_template_item(tenant_id, template_id, item_no);
-- ⑦ 逐项结果按质检单读。
CREATE INDEX return_quality_check_result_by_inspection
  ON return_quality_check_result(tenant_id, inspection_id);
-- ⑧ 留痕回放。
CREATE INDEX return_quality_event_by_inspection
  ON return_quality_event(tenant_id, inspection_id, occurred_at);
-- ⑨ **一张分录同时只能有一张在途质检单**（DB 不变量而不是应用层先查一次 ——
--    并发下两个请求都能查到「还没有」，靠应用层判会双双通过）。
CREATE UNIQUE INDEX return_quality_one_open_per_posting
  ON return_quality_inspection(tenant_id, posting_id)
  WHERE inspection_status <> 'CANCELLED';

CREATE SEQUENCE return_quality_template_no_seq;
CREATE SEQUENCE return_quality_inspection_no_seq;
GRANT USAGE,SELECT ON SEQUENCE return_quality_template_no_seq TO trade_app;
GRANT USAGE,SELECT ON SEQUENCE return_quality_inspection_no_seq TO trade_app;

-- ===========================================================================
-- 十三、多租户：RLS + 授权
-- ===========================================================================
-- 八张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：质检结论决定这批货**以什么身份重新进入销售**，
--   而逐项结果与判定理由是要拿去和供应商 / 平台索赔的举证材料，
--   端点又大多是**按 id 直达**的 URL —— 猜到一个 UUID 就能读，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'return_quality_category','return_quality_category_sku',
    'return_quality_template','return_quality_template_item',
    'return_quality_inspection','return_quality_check_result',
    'return_quality_disposition_instruction','return_quality_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  return_quality_category, return_quality_category_sku,
  return_quality_template, return_quality_template_item,
  return_quality_inspection, return_quality_check_result,
  return_quality_disposition_instruction, return_quality_event TO trade_app;

-- 授权在前、收回在后。
-- 指令表**只给 INSERT / SELECT**：它是交给 11.2.3.2 与 12 财务的交接凭据，
-- 本模块**刻意不提供**消费端点（转可售 / 残次 / 报废未交付），
-- 收回 UPDATE/DELETE 之后，「在本模块里把它改成已消费 / 改成可再销售」既没有代码路径也没有权限。
REVOKE UPDATE,DELETE ON return_quality_disposition_instruction FROM trade_app;
-- 留痕追加式：判定轨迹是要拿去对质的证据，能被悄悄改掉的证据等于没有证据。
REVOKE UPDATE,DELETE ON return_quality_event FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：V172 的 return_* 簇、V161 的 rma_authorization 簇、
-- V157 的 aftersales_case_line 与 V99 的 sales_order 簇全部只读。
-- 本模块对它们的全部依赖都是 SELECT / JOIN 与「写入时刻查一次」，
-- 跨车道 DDL 回归面 = 0（同批另有三条车道也在往 trade 落迁移）。
