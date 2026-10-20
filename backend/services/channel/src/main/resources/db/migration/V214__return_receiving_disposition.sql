-- ===========================================================================
-- 10.7.1.1 退货接收与处置决策（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.7.1.1 退货接收与处置决策
--   - 职责：管理买家退回商品的接收与处置决策：本地仓/海外仓/平台仓接收登记，判定
--     **转销售（可再售）/退运回国/弃置销毁**，记录处置成本（退货运费、销毁费、平台处理费）
--     与残值。
--   - 输入：退货实收记录（11.2.2.1）；处置决策与成本参数；商品再销售限制（品牌方要求/平台政策）。
--   - 输出：处置单（处置方式、数量、成本、残值）；库存状态变更指令（→ 7）；处置成本（→ 12 费用）。
--   - 关系：上游 = 11.2 退货管理、8 仓储作业；下游 = 7 库存、12 财务（费用/减值）。
--     **跨境退货三大去向（转卖/退运/弃置）对利润影响巨大**，默认按"可再售"处理会
--     高估回收价值，转卖/弃置决策必须显式。
--
-- ---------------------------------------------------------------------------
-- 零、🔴🔴🔴 本迁移最重要的一件事：**处置方式不许有默认值**
-- ---------------------------------------------------------------------------
-- 蓝图加粗句「默认按"可再售"处理会高估回收价值，转卖/弃置决策必须显式」在本迁移上
-- 是**三道结构闸**，缺一道那道闸就只是注释：
--
--   ① **列上不写 DEFAULT**：`return_disposition_decision.disposition_method` 与
--      `return_disposition_order.disposition_method` 都**没有列默认值**，
--      且列级 CHECK 钉死三值白名单（RESALE / RETURN_TO_COUNTRY / DISCARD）。
--      写一个 `DEFAULT 'RESALE'` 就正好是蓝图点名禁止的那种默认 —— 少写一个
--      dispositionMethod 的请求会**直接被 DB 拒**（not-null violation），
--      而不是悄悄落成「转销售」。
--   ② **决策与处置单两段分离**：处置方式先落在**决策**上（谁、什么时候、依据什么），
--      处置单只能**引用**一条已记录的决策；触发器 `guard_return_disposition_order_decision`
--      钉死「处置单的处置方式必须与决策逐字相同」「数量必须相同」「决策只能被消费一次」。
--      于是「这张处置单为什么是转销售」永远有一条可审的决策记录可追，
--      而不是处置单自己带一个 disposition_method 就完事。
--   ③ **转销售另有两道服务端否决**（见下「二、🔴 转销售不是随便能选的」）：
--      ① 上游 11.2.3.1 的质检结论必须是 RESALABLE；② 本服务的再销售限制表里
--      不能有命中的品牌方 / 平台政策限制。两条都由**服务端**判，触发器再钉一道。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块的职责边界：站在 11.2 之上做**处置决策**，不重做入库
-- ---------------------------------------------------------------------------
-- 上游真值全在别的服务（AGENTS.md 禁止跨服务 SQL / FK / 共享视图）：
--   * trade（11.2.2.1 退货入库 / 11.2.3.1 质检与判定）——
--     `return_receipt` / `return_receipt_line` / `return_quality_inspection` /
--     `return_quality_disposition_instruction` 等；
--   * warehouse（7 库存 / 8 仓储作业）—— `parcel` / `parcel_item` / `warehouse` 等。
-- 所以本迁移里**零外键、零跨库 join、零共享视图**：
-- 上游标识只以**裸 uuid 列 + 快照列**出现（receipt_id / receipt_line_id 是 trade 的
-- 主键原文，不是本库的引用），服务层经授权 HTTP API 读上游（ReturnReceiptQueryPort）。
--
-- 「接收登记」本模块只登记**一个视角**：这批货到了哪个仓（本地仓 / 海外仓 / 平台仓）、
-- 由谁在什么时候登记的。它**不是** 11.2.2.1 的入库流程 —— 入库分录、待检分录、
-- 撤销与冲正都是 trade 的事实，本模块一行都不写。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 转销售不是随便能选的：两道服务端否决
-- ---------------------------------------------------------------------------
-- 「转销售 / 退运回国 / 弃置销毁」里，只有「转销售」会让货**重新变成能卖的东西**，
-- 另外两个都是把货往外送或毁掉。于是：
--
--   * **质量否决**：上游 11.2.3.1 判定为 RESALABLE 才可能转销售。
--     判成 DEFECTIVE / SCRAPPED 的货转销售 ⇒ 直接拒。
--     **上游还没有质检结论 ⇒ 同样拒**（没有证据证明它能再卖，不是「大概率能卖」）。
--     蓝图 11.2.3.1 加粗句「可再销售判定要保守」在这里就是硬闸。
--   * **政策否决**：品牌方要求 / 平台政策限制再销售的 SKU（`return_resale_restriction`）
--     ⇒ 拒。这张表**本服务自持** —— 查过了：trade / catalog / warehouse 全库
--     都没有「再销售限制」这类表（brand 表在 catalog，只有品牌档案与授权凭证；
--     `return_quality_disposition_instruction` 是**质量状态**指令不是**去向**限制），
--     而蓝图把「商品再销售限制」列为本模块的**输入**，所以它归本模块自持。
--
-- 两道否决的**判定结果**都**冻结在决策行上**（quality_verdict / quality_inspection_id /
-- resale_restricted / resale_restriction_note）：事后上游改判、政策撤销，都不会把
-- 「当时凭什么转销售」这段证据改掉。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 库存状态变更指令（→ 7 库存）：**只出事实，不动对方的表**
-- ---------------------------------------------------------------------------
-- `return_inventory_change_instruction` 是交给 7 库存的**交接凭据**：
-- 本模块只产出一条指令记录，**绝不**去写 warehouse 的 inventory_* / stock_bucket /
-- parcel 等任何表（跨服务写库是 AGENTS.md 明令禁止的）。
--
-- 消费方 = 7.4.1.1 调整单管理（warehouse 服务，**当前未开发**，台账里仍是「未开发」）。
-- 消费方未交付 ⇒ 指令**恒为 PENDING**，且**运行角色对本表的 UPDATE / DELETE 已收回**
-- —— 于是「在本模块里把它标成已消费」既没有代码路径也没有 SQL 权限。
-- （照抄 trade V179 `return_quality_disposition_instruction` 的同一纪律：
--   那张表也是「只出，不消费」，consumer_module 恒为 11.2.3.2。）
--
-- target_inventory_state 用本模块的**去向**词表（SELLABLE / RETURN_PENDING /
-- DISPOSAL_PENDING）。其中 SELLABLE 与 warehouse `inventory_quality_status.role`
-- 的 'SELLABLE' 角色**逐字对齐**；另两个状态在 warehouse 侧尚无对应角色词条
-- （7.4.1.1 未交付），由消费侧落地时映射 —— 本模块**不替 7 库存发明角色词表**。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 处置成本（→ 12 财务）：finance 服务**当前不存在**
-- ---------------------------------------------------------------------------
-- backend/services 下只有 identity / catalog / channel / warehouse / trade / crm，
-- **没有 finance**。仓库先例（11.2.3.3 供应商索赔）写得很清楚：
--   「下游 12 财务（finance 服务）当前不存在 ⇒ 以本服务侧自有事实记录落地，
--     与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接；
--     本模块绝不读写一个不存在的 finance 服务」。
-- 本迁移照此办理：**处置成本与残值落在本服务自持的处置单 / 成本明细里**，
-- 金额一律 BigDecimal ↔ NUMERIC(19,4)，**禁浮点**。
--
-- 🔴 币种口径（本模块**不做任何汇率换算**）：
--   * 处置成本有一个**成本币种** `cost_currency`；成本明细行的币种**必须**与它
--     逐字相同（触发器 `guard_return_disposition_cost_currency` 钉死）——
--     混币种的「合计」是没有意义的数字。
--   * 残值有自己的**残值币种** `salvage_currency`，**可以**与成本币种不同
--     （真实场景：退运成本记 USD，海外仓清仓残值在 EUR 结算）。
--   * 本模块**没有汇率表、没有折算列、没有隐式净额**：「成本 − 残值」的净额与折算
--     归 12 财务。本模块只产出**两笔各自带币种的事实**。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 成本**合计不落表**，查询时现算
-- ---------------------------------------------------------------------------
-- 与 4.5.2.2「比率 / 变化量一律不落表，SQL 现算」同纪律：
-- 处置单上**没有 cost_total 列**。成本合计 = SUM(成本明细行)，
-- 一次查询现算 —— 落表就得在每次加成本、每次改数量时同步它，
-- 漏同步一次就会出现一个比明细更笃定的错数。
--
-- ---------------------------------------------------------------------------
-- 六、七张表：为什么是七张
-- ---------------------------------------------------------------------------
--   return_receiving_registration   接收登记（事实：货到了哪个仓、谁登记的）
--   return_resale_restriction       再销售限制（**配置**：品牌方 / 平台政策）
--   return_disposition_decision     处置决策（**判定**：去向 + 依据 + 判定人）
--   return_disposition_order        处置单（**执行**：成本 + 残值 + 下发）
--   return_disposition_cost         处置成本明细（逐笔费用）
--   return_inventory_change_instruction  库存状态变更指令（→ 7 库存，只出不消费）
--   return_disposition_event        留痕（追加式）
--
-- 「限制」是**配置**（可以登记、可以撤销，与某次处置无关）；
-- 「决策」是**判定**（某一次凭什么这么判）；
-- 「处置单」是**执行**（这次处置花了多少钱、回收了多少）。
-- 三者混在一张表上就会出现「一行既是被处置的货、又是结论、又是政策」。
--
-- ===========================================================================

-- ===========================================================================
-- 1. 接收登记
-- ===========================================================================
CREATE TABLE return_receiving_registration (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 上游标识（trade 服务主键原文，**无外键**：跨服务不得建 FK）
  receipt_id uuid NOT NULL,
  receipt_line_id uuid NOT NULL,
  -- 上游快照：登记那一刻看到的实收事实，事后上游改单也不影响这里的凭据
  receipt_no varchar(32) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',
  -- 品牌 / 平台：🔴 收货现场**看得见**的事实（外箱与标签上的品牌、包裹上的平台标识），
  -- 是「品牌方限制 / 平台政策」能不能命中到这一批货的依据。
  -- 刻意**不**从 catalog 反查品牌主数据：那是跨服务读取，而这里要的是
  -- 「这批货在仓里到底贴着谁的标」—— 收货现场看到的那个。
  brand_code varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',

  -- 本地仓 / 海外仓 / 平台仓：蓝图职责句点名的三个接收视角
  warehouse_type varchar(16) NOT NULL CHECK(warehouse_type IN (
      'LOCAL','OVERSEAS','PLATFORM')),
  warehouse_ref varchar(128) NOT NULL,
  warehouse_country varchar(2) NOT NULL DEFAULT '',

  received_qty numeric(19,6) NOT NULL CHECK(received_qty > 0),

  received_at timestamptz NOT NULL,
  registered_by uuid NOT NULL,
  note text NOT NULL DEFAULT '',

  registration_no varchar(32) NOT NULL,
  version bigint NOT NULL DEFAULT 1,
  -- 负责人归属快照（与 V1 shop / V171 listing 同构）
  owner_id uuid,
  department_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一条上游实收行只登记一次：登记错了要改行，不允许再登记一条把数量记两遍
  UNIQUE(tenant_id, receipt_line_id),
  UNIQUE(tenant_id, registration_no),
  CHECK(btrim(warehouse_ref) <> ''),
  CHECK(warehouse_country = '' OR length(warehouse_country) = 2)
);

CREATE INDEX return_receiving_registration_receipt
  ON return_receiving_registration(tenant_id, receipt_id);
CREATE INDEX return_receiving_registration_sku
  ON return_receiving_registration(tenant_id, sku_code, received_at DESC);
CREATE INDEX return_receiving_registration_time
  ON return_receiving_registration(tenant_id, received_at DESC);
CREATE INDEX return_receiving_registration_warehouse
  ON return_receiving_registration(tenant_id, warehouse_type, received_at DESC);

-- ===========================================================================
-- 2. 再销售限制（品牌方要求 / 平台政策）—— 本服务自持的配置
-- ===========================================================================
-- 🔴 为什么是本服务自持：查过了 trade / catalog / warehouse 全库，
--   没有任何「再销售限制」表。品牌方限制与平台政策是**经营口径**，
--   蓝图把它列为 10.7.1.1 的**输入**，所以归本模块自持（不假装上游有）。
--
-- subject_type 三值：
--   SKU      —— 针对单个 SKU（subject_ref = SKU 编码）
--   BRAND    —— 品牌方要求（subject_ref = 品牌编码，与登记行的 brand_code 对齐）
--   PLATFORM —— 平台政策（subject_ref = 平台编码，与登记行的 platform_code 对齐）
CREATE TABLE return_resale_restriction (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  subject_type varchar(16) NOT NULL CHECK(subject_type IN ('BRAND','PLATFORM','SKU')),
  subject_ref varchar(128) NOT NULL,

  -- 为什么限制：品牌方要求 / 平台政策 / 法规 / 合同条款……
  restriction_basis varchar(32) NOT NULL CHECK(restriction_basis IN (
      'BRAND_REQUIREMENT','PLATFORM_POLICY','REGULATION','CONTRACT')),
  reason text NOT NULL,

  effective_from timestamptz NOT NULL,
  -- 结束时间为空 = 长期有效
  effective_to timestamptz,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),

  revoked_at timestamptz,
  revoked_by uuid,
  revoke_reason text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, subject_type, subject_ref),
  CHECK(btrim(subject_ref) <> ''),
  CHECK(btrim(reason) <> ''),
  CHECK(effective_to IS NULL OR effective_to > effective_from),
  -- 撤销必须留痕：撤销时间 / 撤销人 / 撤销理由三者缺一不可
  CHECK(status <> 'REVOKED' OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL
      AND btrim(revoke_reason) <> ''))
);

-- 判定热路径：某租户当前生效的限制
CREATE INDEX return_resale_restriction_active
  ON return_resale_restriction(tenant_id, subject_type, subject_ref)
  WHERE status = 'ACTIVE';

-- ===========================================================================
-- 3. 处置决策（**判定**：去向 + 依据 + 判定人）
-- ===========================================================================
CREATE TABLE return_disposition_decision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  registration_id uuid NOT NULL,
  -- 上游快照（与登记行一致，决策时冻结）
  receipt_id uuid NOT NULL,
  receipt_line_id uuid NOT NULL,
  receipt_no varchar(32) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',
  brand_code varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',

  -- 🔴🔴 处置方式：**没有列默认值**。三值白名单，缺值直接 not-null violation。
  disposition_method varchar(24) NOT NULL CHECK(disposition_method IN (
      'RESALE','RETURN_TO_COUNTRY','DISCARD')),

  decided_qty numeric(19,6) NOT NULL CHECK(decided_qty > 0),

  -- 判定依据（**必填**）：人话写清「凭什么这么判」
  decision_basis text NOT NULL,
  -- 判定人由触发器从 app.actor 盖章（客户端伪造不了「谁判的」）
  decided_by uuid NOT NULL,
  decided_at timestamptz NOT NULL,

  -- 上游 11.2.3.1 质检结论快照（判定那一刻的值）
  quality_inspection_id uuid,
  quality_verdict varchar(16) CHECK(quality_verdict IS NULL
      OR quality_verdict IN ('RESALABLE','DEFECTIVE','SCRAPPED')),
  quality_verdict_at timestamptz,
  -- 上游给出的残值**估算**（处置单上的残值是实际口径，两者不是一回事）
  quality_salvage_estimate numeric(19,4),
  quality_salvage_currency char(3),
  quality_original_value numeric(19,4),

  -- 再销售限制判定快照（**冻结在决策那一刻**）
  resale_restricted boolean NOT NULL,
  resale_restriction_note text NOT NULL DEFAULT '',

  -- RECORDED 已记录待下发 · CONSUMED 已被处置单消费 · SUPERSEDED 被后来的决策取代
  status varchar(16) NOT NULL DEFAULT 'RECORDED' CHECK(status IN (
      'RECORDED','CONSUMED','SUPERSEDED')),
  consumed_by_order_id uuid,
  superseded_by_decision_id uuid,

  decision_no varchar(32) NOT NULL,
  version bigint NOT NULL DEFAULT 1,
  -- 负责人归属快照（与 V1 shop / V171 listing 同构）
  owner_id uuid,
  department_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, decision_no),
  CHECK(btrim(decision_basis) <> ''),
  -- 🔴 质量否决的结构化表达：判了转销售就必须有「可再销售」的质检结论
  CHECK(disposition_method <> 'RESALE'
     OR (quality_verdict = 'RESALABLE' AND quality_inspection_id IS NOT NULL)),
  -- 🔴 政策否决的结构化表达：判了转销售就不得同时带着「被限制」的标记
  CHECK(disposition_method <> 'RESALE' OR resale_restricted = false),
  CHECK(quality_salvage_currency IS NULL
     OR quality_salvage_currency ~ '^[A-Z]{3}$'),
  -- 已消费就必须指向消费它的那张处置单
  CHECK(status <> 'CONSUMED' OR consumed_by_order_id IS NOT NULL)
);

-- 一个接收登记行同一时刻只能有一条「待下发」的决策（重判时旧决策先作废）
CREATE UNIQUE INDEX return_disposition_decision_open_unique
  ON return_disposition_decision(tenant_id, registration_id)
  WHERE status = 'RECORDED';
CREATE INDEX return_disposition_decision_registration
  ON return_disposition_decision(tenant_id, registration_id);
CREATE INDEX return_disposition_decision_receipt_line
  ON return_disposition_decision(tenant_id, receipt_line_id);
CREATE INDEX return_disposition_decision_status
  ON return_disposition_decision(tenant_id, status, decided_at DESC);
CREATE INDEX return_disposition_decision_method
  ON return_disposition_decision(tenant_id, disposition_method, decided_at DESC);

-- ===========================================================================
-- 4. 处置单（**执行**：成本 + 残值 + 下发）
-- ===========================================================================
CREATE TABLE return_disposition_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 一条决策只能被消费一次（并发下两张处置单抢同一条决策 ⇒ 唯一键直接拒）
  decision_id uuid NOT NULL,
  registration_id uuid NOT NULL,
  receipt_id uuid NOT NULL,
  receipt_line_id uuid NOT NULL,
  receipt_no varchar(32) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',
  brand_code varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',

  -- 🔴 处置方式**快照**（触发器钉死与决策逐字相同；不写默认值）
  disposition_method varchar(24) NOT NULL CHECK(disposition_method IN (
      'RESALE','RETURN_TO_COUNTRY','DISCARD')),
  disposed_qty numeric(19,6) NOT NULL CHECK(disposed_qty > 0),

  -- 🔴 成本币种（成本明细行必须与它逐字相同）与残值币种（可以与成本币种不同）
  cost_currency char(3) NOT NULL CHECK(cost_currency ~ '^[A-Z]{3}$'),
  salvage_currency char(3) NOT NULL CHECK(salvage_currency ~ '^[A-Z]{3}$'),
  -- 实际口径的残值（不是上游的估算）
  salvage_amount numeric(19,4) NOT NULL CHECK(salvage_amount >= 0),

  -- ISSUED 已下发（指令已随单产出）· CANCELLED 已撤销
  status varchar(16) NOT NULL DEFAULT 'ISSUED' CHECK(status IN ('ISSUED','CANCELLED')),
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '',

  issued_by uuid NOT NULL,
  issued_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',

  disposition_no varchar(32) NOT NULL,
  version bigint NOT NULL DEFAULT 1,
  -- 负责人归属快照（与 V1 shop / V171 listing 同构）
  owner_id uuid,
  department_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, decision_id),
  UNIQUE(tenant_id, disposition_no),
  CHECK(btrim(cancel_reason) <> '' OR status <> 'CANCELLED')
);

CREATE INDEX return_disposition_order_registration
  ON return_disposition_order(tenant_id, registration_id);
CREATE INDEX return_disposition_order_receipt_line
  ON return_disposition_order(tenant_id, receipt_line_id);
CREATE INDEX return_disposition_order_status
  ON return_disposition_order(tenant_id, status, issued_at DESC);
CREATE INDEX return_disposition_order_method
  ON return_disposition_order(tenant_id, disposition_method, issued_at DESC);

-- ===========================================================================
-- 5. 处置成本明细（逐笔费用）
-- ===========================================================================
-- 蓝图点名的三类：退货运费 / 销毁费 / 平台处理费。
-- 合计**不落表**（见本迁移第五节），查询时 SUM 现算。
CREATE TABLE return_disposition_cost (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,
  cost_type varchar(24) NOT NULL CHECK(cost_type IN (
      'RETURN_FREIGHT','DESTROY_FEE','PLATFORM_HANDLING_FEE','OTHER')),
  amount numeric(19,4) NOT NULL CHECK(amount >= 0),
  -- 币种必须与处置单的成本币种逐字相同（触发器钉死）
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 谁收的钱：退运代理 / 销毁厂 / 平台 —— 用来对账与追责
  vendor_ref varchar(128) NOT NULL DEFAULT '',
  incurred_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(btrim(vendor_ref) <> '' OR cost_type = 'OTHER')
);

CREATE INDEX return_disposition_cost_order
  ON return_disposition_cost(tenant_id, order_id);

-- ===========================================================================
-- 6. 库存状态变更指令（→ 7 库存）—— **只出，不消费**
-- ===========================================================================
-- 🔴 instruction_status 恒为 PENDING：消费方 7.4.1.1 调整单管理**未交付**，
--   本模块**刻意不提供**消费端点，运行角色对本表的 UPDATE / DELETE 也已收回，
--   所以「在本模块里把它标成已消费」既没有代码路径也没有 SQL 权限。
CREATE TABLE return_inventory_change_instruction (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  order_id uuid NOT NULL,
  disposition_method varchar(24) NOT NULL CHECK(disposition_method IN (
      'RESALE','RETURN_TO_COUNTRY','DISCARD')),
  instruction_type varchar(24) NOT NULL CHECK(instruction_type IN (
      'MOVE_TO_SELLABLE','HOLD_FOR_REPATRIATION','HOLD_FOR_DISPOSAL')),
  -- 去向状态词表。SELLABLE 与 warehouse inventory_quality_status.role 的
  -- 'SELLABLE' 角色逐字对齐；另两个在 warehouse 侧尚无角色词条（7.4.1.1 未交付），
  -- 由消费侧落地时映射 —— 本模块不替 7 库存发明角色词表。
  target_inventory_state varchar(24) NOT NULL CHECK(target_inventory_state IN (
      'SELLABLE','RETURN_PENDING','DISPOSAL_PENDING')),

  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',
  qty numeric(19,6) NOT NULL CHECK(qty > 0),

  -- 恒为 PENDING；消费方恒为 7.4.1.1
  instruction_status varchar(16) NOT NULL DEFAULT 'PENDING'
      CHECK(instruction_status = 'PENDING'),
  consumer_module varchar(16) NOT NULL DEFAULT '7.4.1.1'
      CHECK(consumer_module = '7.4.1.1'),

  issued_at timestamptz NOT NULL,
  issued_by uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一张处置单恰好一条指令：两条指令会让 7 库存收到互相矛盾的两份命令
  UNIQUE(tenant_id, order_id),
  CHECK((instruction_type = 'MOVE_TO_SELLABLE' AND target_inventory_state = 'SELLABLE')
     OR (instruction_type = 'HOLD_FOR_REPATRIATION' AND target_inventory_state = 'RETURN_PENDING')
     OR (instruction_type = 'HOLD_FOR_DISPOSAL' AND target_inventory_state = 'DISPOSAL_PENDING'))
);

CREATE INDEX return_inventory_change_instruction_order
  ON return_inventory_change_instruction(tenant_id, order_id);

-- ===========================================================================
-- 7. 留痕（追加式）
-- ===========================================================================
CREATE TABLE return_disposition_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'RECEIVED','DECIDED','ORDER_ISSUED','COST_RECORDED',
      'INSTRUCTION_ISSUED','ORDER_CANCELLED','RESTRICTION_REGISTERED',
      'RESTRICTION_REVOKED')),
  registration_id uuid,
  decision_id uuid,
  order_id uuid,
  restriction_id uuid,
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  detail text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

CREATE INDEX return_disposition_event_registration
  ON return_disposition_event(tenant_id, registration_id, occurred_at DESC);
CREATE INDEX return_disposition_event_order
  ON return_disposition_event(tenant_id, order_id, occurred_at DESC);

-- ===========================================================================
-- 8. 单据号序列
-- ===========================================================================
CREATE SEQUENCE return_receiving_registration_no_seq;
CREATE SEQUENCE return_disposition_decision_no_seq;
CREATE SEQUENCE return_disposition_order_no_seq;

GRANT USAGE,SELECT ON SEQUENCE return_receiving_registration_no_seq TO channel_app;
GRANT USAGE,SELECT ON SEQUENCE return_disposition_decision_no_seq TO channel_app;
GRANT USAGE,SELECT ON SEQUENCE return_disposition_order_no_seq TO channel_app;

-- ===========================================================================
-- 9. 触发器：结构事实不许靠服务层自觉
-- ===========================================================================
-- (a) 🔴 判定人 / 登记人 / 下单人由 app.actor 盖章：客户端伪造不了「谁判的」。
CREATE FUNCTION stamp_return_disposition_actor() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.registered_by := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.registered_by IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_receiving_registration_actor
  BEFORE INSERT ON return_receiving_registration
  FOR EACH ROW EXECUTE FUNCTION stamp_return_disposition_actor();

CREATE FUNCTION stamp_return_disposition_decided_by() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.decided_by := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.decided_by IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposition_decision_actor
  BEFORE INSERT ON return_disposition_decision
  FOR EACH ROW EXECUTE FUNCTION stamp_return_disposition_decided_by();

CREATE FUNCTION stamp_return_disposition_issued_by() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.issued_by := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.issued_by IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposition_order_actor
  BEFORE INSERT ON return_disposition_order
  FOR EACH ROW EXECUTE FUNCTION stamp_return_disposition_issued_by();

-- 撤销限制的撤销人同样盖章
CREATE FUNCTION stamp_return_resale_restriction_revoked_by() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='UPDATE' AND NEW.status = 'REVOKED' THEN
    NEW.revoked_by := nullif(current_setting('app.actor',true),'')::uuid;
    IF NEW.revoked_by IS NULL THEN
      RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501';
    END IF;
    NEW.revoked_at := COALESCE(NEW.revoked_at, now());
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_resale_restriction_revoked_by
  BEFORE UPDATE ON return_resale_restriction
  FOR EACH ROW EXECUTE FUNCTION stamp_return_resale_restriction_revoked_by();

-- (b) 🔴 决策必须挂在一张**存在**的接收登记上，且数量不得超过登记数量。
--     「判了 50 件而只收到 30 件」是最容易发生也最伤利润的一种错。
CREATE FUNCTION guard_return_disposition_decision_registration() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  r return_receiving_registration%ROWTYPE;
BEGIN
  SELECT * INTO r FROM return_receiving_registration
   WHERE tenant_id=NEW.tenant_id AND id=NEW.registration_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return disposition decision % must reference an existing receiving registration',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF r.receipt_line_id <> NEW.receipt_line_id OR r.receipt_id <> NEW.receipt_id THEN
    RAISE EXCEPTION 'Return disposition decision % must reference the same upstream receipt line as its registration',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF NEW.decided_qty > r.received_qty THEN
    RAISE EXCEPTION 'Return disposition decision % quantity % exceeds received quantity %',
      NEW.id, NEW.decided_qty, r.received_qty USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposition_decision_registration
  BEFORE INSERT OR UPDATE ON return_disposition_decision
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposition_decision_registration();

-- (c) 🔴 决策的快照必须与登记行一致（品牌 / 平台 / SKU / 批次不许在判定时漂移）。
CREATE FUNCTION guard_return_disposition_decision_snapshot() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  r return_receiving_registration%ROWTYPE;
BEGIN
  SELECT * INTO r FROM return_receiving_registration
   WHERE tenant_id=NEW.tenant_id AND id=NEW.registration_id;
  IF NOT FOUND THEN RETURN NEW; END IF;
  IF NEW.sku_code <> r.sku_code OR NEW.sku_name <> r.sku_name
     OR NEW.batch_no <> r.batch_no OR NEW.brand_code <> r.brand_code
     OR NEW.platform_code <> r.platform_code THEN
    RAISE EXCEPTION 'Return disposition decision % snapshot must match its receiving registration',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposition_decision_snapshot
  BEFORE INSERT OR UPDATE ON return_disposition_decision
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposition_decision_snapshot();

-- (d) 🔴 决策一旦离开「待下发」就是**终态**：不许改去向、不许改数量、不许改依据。
--     「当时凭什么这么判」是拿去和品牌方 / 平台对质的凭据，改它等于改历史。
--     被消费（CONSUMED）与被取代（SUPERSEDED）都算终态 —— 一条被新决策顶掉的
--     旧决策同样是对质凭据，改它等于把「当初判错了、后来改了」这段过程抹掉。
CREATE FUNCTION guard_return_disposition_decision_final() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF OLD.status <> 'RECORDED' AND (
       NEW.disposition_method IS DISTINCT FROM OLD.disposition_method
    OR NEW.decided_qty IS DISTINCT FROM OLD.decided_qty
    OR NEW.decision_basis IS DISTINCT FROM OLD.decision_basis
    OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
    OR NEW.resale_restricted IS DISTINCT FROM OLD.resale_restricted
    OR NEW.quality_verdict IS DISTINCT FROM OLD.quality_verdict) THEN
    RAISE EXCEPTION 'Return disposition decision % is consumed; its conclusion is immutable',
      OLD.id USING ERRCODE='23514';
  END IF;
  -- 已消费的决策必须指向消费它的那张处置单
  IF NEW.status = 'CONSUMED' AND NEW.consumed_by_order_id IS NULL THEN
    RAISE EXCEPTION 'Return disposition decision % is consumed but has no order reference',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposition_decision_final
  BEFORE UPDATE ON return_disposition_decision
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposition_decision_final();

-- (e) 🔴 处置单必须逐字复述它的决策：去向相同、数量相同、决策存在且待下发。
--     这是「处置单自己带一个 disposition_method 就完事」的反面。
CREATE FUNCTION guard_return_disposition_order_decision() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  d return_disposition_decision%ROWTYPE;
BEGIN
  SELECT * INTO d FROM return_disposition_decision
   WHERE tenant_id=NEW.tenant_id AND id=NEW.decision_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return disposition order % must reference an existing decision',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF d.status <> 'RECORDED' THEN
    RAISE EXCEPTION 'Return disposition order % references a decision that is not pending issuance',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF d.disposition_method <> NEW.disposition_method THEN
    RAISE EXCEPTION 'Return disposition order % disposition method must match its decision',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF d.decided_qty <> NEW.disposed_qty THEN
    RAISE EXCEPTION 'Return disposition order % quantity must match its decision',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF d.registration_id <> NEW.registration_id OR d.receipt_line_id <> NEW.receipt_line_id THEN
    RAISE EXCEPTION 'Return disposition order % must reference the same registration as its decision',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposition_order_decision
  BEFORE INSERT ON return_disposition_order
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposition_order_decision();

-- (f) 🔴 成本明细的币种必须与处置单的成本币种逐字相同。
--     混币种的「合计」是没有意义的数字。
CREATE FUNCTION guard_return_disposition_cost_currency() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  o return_disposition_order%ROWTYPE;
BEGIN
  SELECT * INTO o FROM return_disposition_order
   WHERE tenant_id=NEW.tenant_id AND id=NEW.order_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return disposition cost % must reference an existing order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF o.status = 'CANCELLED' THEN
    RAISE EXCEPTION 'Return disposition order % is cancelled; no further cost may be recorded',
      o.id USING ERRCODE='23514';
  END IF;
  IF o.cost_currency <> NEW.currency THEN
    RAISE EXCEPTION 'Return disposition cost % currency must match the order cost currency',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposition_cost_currency
  BEFORE INSERT ON return_disposition_cost
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposition_cost_currency();

-- (g) 🔴 库存指令必须与处置单同去向、同数量；已撤销的处置单不再补发。
CREATE FUNCTION guard_return_inventory_change_instruction_order() RETURNS trigger
LANGUAGE plpgsql AS $$ DECLARE
  o return_disposition_order%ROWTYPE;
BEGIN
  SELECT * INTO o FROM return_disposition_order
   WHERE tenant_id=NEW.tenant_id AND id=NEW.order_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Return inventory change instruction % must reference an existing order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF o.status = 'CANCELLED' THEN
    RAISE EXCEPTION 'Return disposition order % is cancelled; no further instruction may be issued',
      o.id USING ERRCODE='23514';
  END IF;
  IF o.disposition_method <> NEW.disposition_method THEN
    RAISE EXCEPTION 'Return inventory change instruction % disposition method must match its order',
      NEW.id USING ERRCODE='23514';
  END IF;
  IF o.disposed_qty <> NEW.qty THEN
    RAISE EXCEPTION 'Return inventory change instruction % quantity must match its order',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_inventory_change_instruction_order
  BEFORE INSERT ON return_inventory_change_instruction
  FOR EACH ROW EXECUTE FUNCTION guard_return_inventory_change_instruction_order();

-- (h) 🔴 留痕追加式：已写入的事件不许改、不许删。
CREATE FUNCTION guard_return_disposition_event_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  RAISE EXCEPTION 'Return disposition event % is append-only', OLD.id USING ERRCODE='23514';
END $$;
CREATE TRIGGER return_disposition_event_append_only
  BEFORE UPDATE OR DELETE ON return_disposition_event
  FOR EACH ROW EXECUTE FUNCTION guard_return_disposition_event_append_only();

-- ===========================================================================
-- 10. 多租户隔离：ENABLE / FORCE RLS + 数据范围
-- ===========================================================================
-- 🔴 ENABLE 与 FORCE **都要有**：只 ENABLE 的话，表 owner（迁移的执行者）能绕过策略，
--    隔离测试就成了自欺欺人。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'return_receiving_registration','return_disposition_decision','return_disposition_order',
    'return_disposition_cost','return_inventory_change_instruction',
    'return_disposition_event'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 🔴🔴 `return_resale_restriction` **只挂 isolation，不挂 data_scope**：
--    这是一张**租户级政策表**（同款写法照抄 V136 `logistics_channel_restriction`）。
--    理由不是省事，而是**正确性**：限制判定是服务端做的「全租户否决」，
--    如果可见性按负责人收窄，A 登记的限制在 B 眼里就不存在 ——
--    于是 B 判「转销售」时限制**不生效**，等于绕过了品牌方 / 平台政策。
--    可见性收窄会让「否决」变成「可绕过的建议」。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['return_resale_restriction'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 登记行 / 决策 / 处置单自带负责人归属快照（与 V1 shop / V171 listing 同构），
-- 可见性随**本行自己的归属**收敛。
CREATE FUNCTION stamp_return_receiving_registration_owner() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.owner_id:=nullif(current_setting('app.actor',true),'')::uuid;
    NEW.department_id:=nullif(current_setting('app.department',true),'')::uuid;
    IF NEW.owner_id IS NULL THEN RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501'; END IF;
  ELSE
    IF (NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.department_id IS DISTINCT FROM OLD.department_id)
       AND coalesce(nullif(current_setting('app.transfer',true),'')::boolean,false) IS NOT TRUE
    THEN RAISE EXCEPTION 'Return receiving registration ownership transfer must use the explicit transfer flag'
      USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_receiving_registration_owner
  BEFORE INSERT OR UPDATE ON return_receiving_registration
  FOR EACH ROW EXECUTE FUNCTION stamp_return_receiving_registration_owner();

CREATE FUNCTION stamp_return_disposition_decision_owner() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.owner_id:=nullif(current_setting('app.actor',true),'')::uuid;
    NEW.department_id:=nullif(current_setting('app.department',true),'')::uuid;
    IF NEW.owner_id IS NULL THEN RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposition_decision_owner
  BEFORE INSERT ON return_disposition_decision
  FOR EACH ROW EXECUTE FUNCTION stamp_return_disposition_decision_owner();

CREATE FUNCTION stamp_return_disposition_order_owner() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    NEW.owner_id:=nullif(current_setting('app.actor',true),'')::uuid;
    NEW.department_id:=nullif(current_setting('app.department',true),'')::uuid;
    IF NEW.owner_id IS NULL THEN RAISE EXCEPTION 'Authenticated actor required' USING ERRCODE='42501'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER return_disposition_order_owner
  BEFORE INSERT ON return_disposition_order
  FOR EACH ROW EXECUTE FUNCTION stamp_return_disposition_order_owner();

CREATE POLICY data_scope ON return_receiving_registration AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

CREATE POLICY data_scope ON return_disposition_decision AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

CREATE POLICY data_scope ON return_disposition_order AS RESTRICTIVE
  USING (channel_scope(channel_operation(),owner_id,department_id))
  WITH CHECK (channel_scope(channel_operation(),owner_id,department_id));

-- 子表可见性随**父行**收敛，不允许绕过父行直接枚举。
CREATE POLICY data_scope ON return_disposition_cost AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_disposition_order o
             WHERE o.tenant_id=return_disposition_cost.tenant_id AND o.id=return_disposition_cost.order_id
             AND channel_scope(channel_operation(),o.owner_id,o.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_disposition_order o
             WHERE o.tenant_id=return_disposition_cost.tenant_id AND o.id=return_disposition_cost.order_id
             AND channel_scope(channel_operation(),o.owner_id,o.department_id)));

CREATE POLICY data_scope ON return_inventory_change_instruction AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM return_disposition_order o
             WHERE o.tenant_id=return_inventory_change_instruction.tenant_id
             AND o.id=return_inventory_change_instruction.order_id
             AND channel_scope(channel_operation(),o.owner_id,o.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM return_disposition_order o
             WHERE o.tenant_id=return_inventory_change_instruction.tenant_id
             AND o.id=return_inventory_change_instruction.order_id
             AND channel_scope(channel_operation(),o.owner_id,o.department_id)));

-- 留痕的行要么挂在登记行 / 处置单上（随父行收敛），
-- 要么挂在再销售限制上（限制是租户级政策 ⇒ 随限制收敛）。
CREATE POLICY data_scope ON return_disposition_event AS RESTRICTIVE
  USING (
    (registration_id IS NOT NULL AND EXISTS(SELECT 1 FROM return_receiving_registration g
        WHERE g.tenant_id=return_disposition_event.tenant_id
        AND g.id=return_disposition_event.registration_id
        AND channel_scope(channel_operation(),g.owner_id,g.department_id)))
    OR (order_id IS NOT NULL AND EXISTS(SELECT 1 FROM return_disposition_order o
        WHERE o.tenant_id=return_disposition_event.tenant_id
        AND o.id=return_disposition_event.order_id
        AND channel_scope(channel_operation(),o.owner_id,o.department_id)))
    OR (restriction_id IS NOT NULL AND EXISTS(SELECT 1 FROM return_resale_restriction r
        WHERE r.tenant_id=return_disposition_event.tenant_id
        AND r.id=return_disposition_event.restriction_id)))
  WITH CHECK (
    (registration_id IS NOT NULL AND EXISTS(SELECT 1 FROM return_receiving_registration g
        WHERE g.tenant_id=return_disposition_event.tenant_id
        AND g.id=return_disposition_event.registration_id
        AND channel_scope(channel_operation(),g.owner_id,g.department_id)))
    OR (order_id IS NOT NULL AND EXISTS(SELECT 1 FROM return_disposition_order o
        WHERE o.tenant_id=return_disposition_event.tenant_id
        AND o.id=return_disposition_event.order_id
        AND channel_scope(channel_operation(),o.owner_id,o.department_id)))
    OR (restriction_id IS NOT NULL AND EXISTS(SELECT 1 FROM return_resale_restriction r
        WHERE r.tenant_id=return_disposition_event.tenant_id
        AND r.id=return_disposition_event.restriction_id)));

-- ===========================================================================
-- 11. 账号与授权（沿用 V1..V206 的条件建角色，保证迁移可重复执行）
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  return_receiving_registration, return_resale_restriction, return_disposition_decision,
  return_disposition_order, return_disposition_cost, return_inventory_change_instruction,
  return_disposition_event TO channel_app;

-- 🔴 库存状态变更指令：**只出，不消费** —— UPDATE / DELETE 收回，
--    于是「在本模块里把它标成已消费」既没有代码路径也没有 SQL 权限。
--    （消费方 7.4.1.1 调整单管理未交付，见本迁移第三节。）
REVOKE UPDATE,DELETE ON return_inventory_change_instruction FROM channel_app;

-- 🔴 留痕追加式：UPDATE / DELETE 收回（触发器也拦，两道闸）。
REVOKE UPDATE,DELETE ON return_disposition_event FROM channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V206 同口径）。
REVOKE ALL ON
  return_receiving_registration, return_resale_restriction, return_disposition_decision,
  return_disposition_order, return_disposition_cost, return_inventory_change_instruction,
  return_disposition_event FROM starcon_app;

-- ===========================================================================
-- 12. 复用 identity 已注册的 channel.operation.* 权限码
-- ===========================================================================
-- 本模块**不新增权限码**：退货接收与处置属于「平台 / 运营操作」语义，
-- 与 10.1.1.1 承运商、10.2.2.2 面单模板、10.3.2.1 交运批次同族，
-- identity 服务零改动。
