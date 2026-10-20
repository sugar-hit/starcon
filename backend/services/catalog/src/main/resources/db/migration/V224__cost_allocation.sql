-- 5.2.1.3 成本分摊：把混合成本的货件（一次采购多个 SKU 共用头程）按重量/体积/金额/数量规则
-- 分摊到各 SKU，支持人工微调并留痕。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：把混合成本的货件（一次采购多个 SKU 共用头程）按重量/体积/金额/数量规则分摊到各 SKU，
--             支持人工微调并留痕。
--   **输入**：货件成本总额；分摊规则；人工调整值与原因。
--   **输出**：分摊明细（每 SKU 分摊金额）；重算与差异对比。
--   **关系**：上游 = 10 头程费用、12 landed_cost_allocation；下游 = 5.2.1.1 成本构成、13 利润。
--             **分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖。
--
-- ============================================================ 第一节：两个上游，两个真实状态
--
-- 蓝图这句话里的两个上游，本仓的真实状态**不一样**，迁移的第一件事就是把它们分清楚：
--
--   | 上游                       | 本仓真实状态                                             |
--   |----------------------------|----------------------------------------------------------|
--   | 10 头程费用                | ✅ warehouse `first_mile_shipment` / `_line`（V80）已交付 |
--   | 12 landed_cost_allocation  | 🔴 **finance 服务在本仓不存在**                          |
--
-- **1) 头程发运单就是「一次采购多个 SKU 共用头程」的那个货件**
--
-- warehouse 的 `first_mile_shipment` 是一张发运单（发运单号 / 币种 / 运费 / 保险费 / 操作费），
-- `first_mile_shipment_line` 是它的行（一个 SKU 一行，带发出数量）。
-- **一张发运单里出现多个 SKU，就是蓝图说的「混合成本的货件」** ——
-- 那笔运费是几个 SKU 一起花的，必须摊到各 SKU 上，这就是本模块。
--
-- AGENTS.md 明令禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒ 本库**不建头程发运单的副本表**，
-- 由服务层经**授权 HTTP 只读端点**取数（见 `CostAllocationPort`）。
-- 本迁移因此**零个外键指向 warehouse 的表**。
--
-- **2) 12 财务（finance 服务）不存在 ⇒ 分摊单由本服务自持**
--
-- 仓库先例（10.6.1.1 物流成本分析、11.2.3.3 供应商索赔、5.2.1.1 成本构成）已经把这类情形钉死：
-- 「下游 12 财务（finance 服务）当前不存在 ⇒ 以本服务侧自有事实记录落地，
--   与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接」。
--
-- ⇒ 本模块**不读写任何 finance 侧的表**，分摊单与分摊明细全部由本服务自持，
-- 能力声明里如实写明「这是本服务口径，不是 12 财务的 landed_cost_allocation 结果」。
--
-- ============================================================ 第二节：三张表，职责互不重叠
--
--   | 表                              | 一行是什么                          | 写入方式     |
--   |---------------------------------|-------------------------------------|--------------|
--   | `cost_allocation`               | 一个货件的分摊单（**当前**版本指针） | 可改（乐观锁）|
--   | `cost_allocation_revision`      | 一次分摊的**当时快照**（规则/总额/生效时间/原因） | **只追加** |
--   | `cost_allocation_revision_line` | 快照里的**一个 SKU 一行**的分摊明细  | **只追加**   |
--
-- **🔴 为什么分摊明细是「快照的行」而不是「可改的行」**
--
-- 蓝图加粗句：「**分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖。」
--
-- 同一批货，按重量摊是 12.00，按金额摊是 18.00。规则一换，13 利润拿到的每个 SKU 的
-- 成本就变了 —— 而**历史利润已经用旧规则算过了**。所以：
--
--   * 分摊明细行**属于某个版本**（`cost_allocation_revision_line.revision_id`），
--     换规则 = **追加一个新版本**，旧版本的行原样留在库里；
--   * `cost_allocation.current_revision_id` 是「当前」的**指针**，历史是**行** ——
--     指针一动，历史一行不动；
--   * 「当前的分摊明细」= 当前版本的那一组行。**没有 UPDATE 端点，也永远不会有。**
--
-- 这与 5.2.1.1 的 `sku_cost_revision` 是同一条纪律（那一条是「换成本法留历史」，
-- 这一条是「换分摊规则留历史」），所以 5.2.1.3 回溯 5.2.1.1 的历史时口径是对得上的。
--
-- **🔴 合计不落表，查询时 SUM 现算**
--
-- 版本快照里**没有**「分摊合计」「调整合计」「差额」这些列：它们全部是**逐行金额的聚合**，
-- 落库就多一份可能与逐行明细对不上的副本（改了行、忘了改合计，就是一个偏低的成本）。
-- 服务层每次读取都 `SUM` 逐行金额现算。**逐行金额是唯一的事实**。
--
-- ============================================================ 第三节：分摊规则与「基数取不到怎么办」
--
-- 四种规则（蓝图：「按重量/体积/金额/数量规则分摊」）：
--
--   | 规则       | 基数来源                                                | 不可得的典型原因 |
--   |------------|---------------------------------------------------------|------------------|
--   | QUANTITY   | 发运单行的发出数量（已折算到 SKU 基础单位）             | 数量为 0         |
--   | WEIGHT     | catalog `sku_package.gross_weight_g`（毛重）             | 包装重量未维护   |
--   | VOLUME     | catalog `sku_package` 的 长×宽×高（mm³ → cm³）          | 外形尺寸未维护   |
--   | AMOUNT     | 5.2.1.1 `sku_cost_revision.purchase_cost` × 数量          | 该 SKU 尚未维护成本构成 / 币种不同 |
--
-- **🔴 基数取不到的那一行不是「摊到 0」，是「这一行摊不到」**：
--
-- 一行的基数不可得（包装重量没维护、这个 SKU 还没维护成本构成）时：
--   * 它**不参与**分母（否则分母被一个假 0 拉偏，剩下的行多分）；
--   * 它**明确记成「基数不可得」并写明原因**（`basis_available=false` + `basis_note`）；
--   * 它的分摊额是 0，**且 0 的含义是「没分到」而不是「它不用花钱」** ——
--     页面上必须显示「基数不可得，未参与分摊」，而不是显示一个安静的 0.00。
--
-- **🔴 所有行都取不到基数 ⇒ 拒绝分摊**（不是摊成一张全 0 的单）：
-- 那张单会让下游以为「这个货件不用花钱」，而真实情况是「没有基数可分」。
--
-- **🔴 逐行分摊额之和必须严格等于货件成本总额**
--
-- 分摊是**拆分**不是**缩水**。数学上 Σ(总额 × 行基数 ÷ Σ基数) = 总额，
-- 但逐行四舍五入到 4 位小数后会差几分钱。⇒ 服务层用**最大余数法**
-- （先各自向下取整到 4 位，再把剩下的分按小数部分从大到小逐个 0.0001 地补回去），
-- 保证 Σ分摊额 **逐分等于** 总额；DB 层再加一道**延迟约束触发器**兜底
-- （事务提交时校验，不相等就拒绝提交）。
--
-- ============================================================ 第四节：人工微调与「差额」必须看得见
--
-- 蓝图输入句：「人工调整值与原因」。⇒ 人工微调是**逐行**的：
--   * `adjustment_amount`：相对计算分摊额的**增减**（可正可负）；
--   * `adjustment_reason`：**调整额 ≠ 0 时必填**（DB 层 CHECK 兜底）。
--
-- 人工微调**故意**会让「逐行最终分摊额之和」≠「货件成本总额」——
-- 这就是「微调」的意思。所以版本里必须能算出**差额**（variance）并在页面上显示：
-- **差额是人工决定的结果，不是错误**；把它藏起来才是错误。
--
-- ============================================================ 第五节：外键用触发器，不用 FOREIGN KEY
--
-- 本批（batch-37）有一条车道在迁移里给既有表加了 FOREIGN KEY，结果打破了同服务既有测试的
-- `TRUNCATE` ⇒ 39 例 Errors ⇒ L2 直接红。
--
-- ⇒ 本迁移**一个 FOREIGN KEY 都不建**。引用校验一律用**触发器**做：
--   * 版本必须属于本租户的一张分摊单；
--   * 明细行必须属于本租户的一个版本，且 allocation_id 与之���致；
--   * 分摊单的当前版本指针必须指向本租户、且属于本分摊单的版本；
--   * 明细行的 SKU 必须存在于本租户（防止把货摊给一个不存在的 SKU）。
--
-- 触发器只在 INSERT/UPDATE 时跑，**TRUNCATE 不会触发它们** ⇒ 既有测试的 truncate 不受影响。

-- ============================================================ 第一节表：cost_allocation —— 分摊单（当前版本指针）

CREATE TABLE cost_allocation (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 来源类型：
  --   FIRST_MILE_SHIPMENT —— 头程发运单（warehouse）。一张发运单里多个 SKU 就是「混合成本的货件」。
  --   MANUAL              —— 人工登记的货件（12 财务未交付时，本服务自持的货件事实）。
  -- **枚举只增不改**：来源类型会进版本快照的历史语义。
  source_type text NOT NULL CHECK(source_type IN ('FIRST_MILE_SHIPMENT','MANUAL')),

  -- 来源标识：头程发运单的 id（UUID 串），或人工货件号。
  source_ref text NOT NULL CHECK(btrim(source_ref) <> ''),

  -- 展示用标签（发运单号 / 货件名）。**快照**，源单改名不影响历史可读性。
  source_label text NOT NULL DEFAULT '',

  -- 当前版本指针：指向 cost_allocation_revision(tenant_id,id)。
  -- 「当前」是**指针**，历史是**行** —— 换分摊规则改的是指针，历史一行不动（第二节）。
  current_revision_id uuid,

  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个货件只有一张分摊单：同一批发运单不允许被摊两次。
  UNIQUE(tenant_id, source_type, source_ref)
);

-- 列表主查询：按来源找分摊单（成本治理页的默认排序）。
CREATE INDEX cost_allocation_by_source ON cost_allocation(tenant_id, source_type, source_ref);
-- 「最近改过的分摊单」—— 台账默认排序（与 5.2.1.1 的 cost_profile 同款）。
CREATE INDEX cost_allocation_by_updated ON cost_allocation(tenant_id, updated_at DESC);

-- ============================================================ 第二节表：cost_allocation_revision —— 分摊版本（只追加快照）

CREATE TABLE cost_allocation_revision (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 指向 cost_allocation(tenant_id,id)。**用触发器校验，不用外键**（第五节）。
  allocation_id uuid NOT NULL,

  -- 版本号：一个分摊单下从 1 递增。
  -- **不靠 count(*) 现算**：并发写入时 count 会算出两个相同的号，UNIQUE 会把其中一个变成 500。
  -- 版本号由服务层在**同事务**里取 max+1 并靠 UNIQUE 兜底，撞了就重试。
  revision_no int NOT NULL CHECK(revision_no > 0),

  -- 这一版用的分摊规则（重量/体积/金额/数量）。**枚举只增不改**。
  basis_rule text NOT NULL CHECK(basis_rule IN ('WEIGHT','VOLUME','AMOUNT','QUANTITY')),

  -- 这一版摊的货件成本总额（**输入**，不是合计）。
  -- 逐行的分摊额之和由延迟约束触发器校验必须等于它（第三节）。
  total_cost numeric(19,4) NOT NULL CHECK(total_cost >= 0),

  -- 这一版的计价币种：ISO 4217 三位大写码。**跨币种绝不相加**。
  -- 用 varchar(3) 而不是 char(3)：char 会补空格，'USD ' 与 'USD' 不相等。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 生效时间：这一版从哪一刻开始算数。
  -- **timestamptz 而不是 date**：成本切换常常发生在某个具体时刻（一次到港、一次调价），
  -- 只到日会让「今天上午算的毛利」与「今天下午算的毛利」口径不明。
  effective_from timestamptz NOT NULL,

  -- 变更原因：**必填**。换规则、改总额、人工微调 —— 每一次变更都要说清为什么。
  -- 没有理由的历史等于没有历史：半年后没人知道当时为什么把规则从按重量改成按金额。
  change_reason text NOT NULL CHECK(btrim(change_reason) <> ''),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个分摊单下版本号唯一：撞号说明并发写入，服务层重试。
  UNIQUE(tenant_id, allocation_id, revision_no)
);

-- 历史查询主路径：这个分摊单的版本序列（新到旧）。
CREATE INDEX cost_allocation_revision_by_allocation ON cost_allocation_revision(tenant_id, allocation_id, revision_no DESC);
-- 「最近改过规则的一批分摊单」—— 成本治理页的时间线。
CREATE INDEX cost_allocation_revision_by_created ON cost_allocation_revision(tenant_id, created_at DESC);

-- ============================================================ 第三节表：cost_allocation_revision_line —— 分摊明细（只追加快照行）

CREATE TABLE cost_allocation_revision_line (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 指向 cost_allocation_revision(tenant_id,id)。**用触发器校验，不用外键**（第五节）。
  revision_id uuid NOT NULL,

  -- 冗余一份分摊单 id：让「按分摊单取全部明细行」不必 join 版本表，
  -- 也让触发器能核对「这一行与它的版本说的是同一张分摊单」。
  allocation_id uuid NOT NULL,

  -- 被分摊的 SKU。
  sku_id uuid NOT NULL,

  -- SKU 的**当时**编码与名称（快照）。SKU 后来改名或被删，历史明细仍然读得懂。
  sku_code text NOT NULL DEFAULT '',
  sku_name text NOT NULL DEFAULT '',

  -- 本行的基础单位数量（分摊基数之一：QUANTITY 规则直接用它）。
  base_quantity numeric(19,6) NOT NULL CHECK(base_quantity >= 0),

  -- 本行的毛重（克）。WEIGHT 规则的基数。**可空 = 包装重量未维护**。
  weight_grams numeric(19,6) CHECK(weight_grams IS NULL OR weight_grams >= 0),

  -- 本行的体积（立方厘米，由外形尺寸算出）。VOLUME 规则的基数。**可空 = 尺寸未维护**。
  volume_cm3 numeric(19,6) CHECK(volume_cm3 IS NULL OR volume_cm3 >= 0),

  -- 本行的货值（金额基数）：采购单价 × 数量。AMOUNT 规则的基数。**可空 = 该 SKU 尚未维护成本构成**。
  amount_basis numeric(19,4) CHECK(amount_basis IS NULL OR amount_basis >= 0),

  -- 基数是否可得。false 时上面四列里**该规则对应的那一列**没有值，这一行不参与分母。
  basis_available boolean NOT NULL DEFAULT true,

  -- 基数来源 / 不可得的原因说明。空串 = 一切正常。
  basis_note text NOT NULL DEFAULT '',

  -- 计算分摊额 = 总额 × 本行基数 ÷ 全部基数。**逐行之和严格等于 total_cost**（第三节）。
  -- 这是本行唯一的「分摊额」事实 —— 下面的最终分摊额是**读时现算**的（allocated + adjustment），
  -- **不落表**：落一份副本就多一处可能与两个来源对不上的地方。
  allocated_amount numeric(19,4) NOT NULL CHECK(allocated_amount >= 0),

  -- 人工微调（相对计算分摊额的增减）。可正可负；**≠ 0 时 adjustment_reason 必填**。
  adjustment_amount numeric(19,4) NOT NULL DEFAULT 0,

  -- 人工微调原因。**没有理由的微调会让「成本为什么突然变了」永远答不上来**。
  adjustment_reason text NOT NULL DEFAULT '',

  UNIQUE(tenant_id, id),
  -- 一个版本里一个 SKU 只有一行（同一 SKU 不拆行）。
  UNIQUE(tenant_id, revision_id, sku_id),
  -- 人工微调必须带原因：DB 层兜底，绕过服务层直连写入也写不出没有理由的微调。
  CHECK(adjustment_amount = 0 OR btrim(adjustment_reason) <> '')
);

-- 「当前分摊明细」的读取路径：按版本取行。
CREATE INDEX cost_allocation_line_by_revision ON cost_allocation_revision_line(tenant_id, revision_id, sku_id);
-- 「按 SKU 反查它被摊到多少」—— 13 利润与 5.2.1.1 的入口。
CREATE INDEX cost_allocation_line_by_sku ON cost_allocation_revision_line(tenant_id, sku_id, revision_id);
-- 「按分摊单取全部明细行」。
CREATE INDEX cost_allocation_line_by_allocation ON cost_allocation_revision_line(tenant_id, allocation_id);

-- ============================================================ 第四节：引用校验触发器（**替代外键**）

-- 1) 版本必须属于本租户的一张分摊单。
CREATE FUNCTION validate_cost_allocation_revision_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS(SELECT 1 FROM cost_allocation a
                 WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.allocation_id) THEN
    RAISE EXCEPTION '分摊版本指向的分摊单不存在或不属于本租户' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER validate_cost_allocation_revision_owner BEFORE INSERT ON cost_allocation_revision
  FOR EACH ROW EXECUTE FUNCTION validate_cost_allocation_revision_owner();

-- 2) 明细行必须属于本租户的一个版本，且与版本的 allocation_id 一致。
CREATE FUNCTION validate_cost_allocation_line_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  rev_allocation uuid;
BEGIN
  SELECT r.allocation_id INTO rev_allocation
    FROM cost_allocation_revision r
   WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.revision_id;
  IF rev_allocation IS NULL THEN
    RAISE EXCEPTION '分摊明细指向的版本不存在或不属于本租户' USING ERRCODE='23514';
  END IF;
  IF rev_allocation <> NEW.allocation_id THEN
    RAISE EXCEPTION '分摊明细的分摊单与它的版本不一致' USING ERRCODE='23514';
  END IF;
  -- 3) 被分摊的 SKU 必须存在于本租户：把货摊给一个不存在的 SKU 是错账，不是分摊。
  IF NOT EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sku_id) THEN
    RAISE EXCEPTION '分摊明细指向的 SKU 不存在或不属于本租户' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER validate_cost_allocation_line_owner BEFORE INSERT ON cost_allocation_revision_line
  FOR EACH ROW EXECUTE FUNCTION validate_cost_allocation_line_owner();

-- 4) 分摊单的当前版本指针必须指向本租户、且属于本分摊单的版本。
CREATE FUNCTION validate_cost_allocation_current_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  rev_allocation uuid;
BEGIN
  IF NEW.current_revision_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT r.allocation_id INTO rev_allocation
    FROM cost_allocation_revision r
   WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.current_revision_id;
  IF rev_allocation IS NULL THEN
    RAISE EXCEPTION '当前版本指针指向的版本不存在或不属于本租户' USING ERRCODE='23514';
  END IF;
  IF rev_allocation <> NEW.id THEN
    RAISE EXCEPTION '当前版本指针指向的版本不属于本分摊单' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER validate_cost_allocation_current_revision BEFORE INSERT OR UPDATE ON cost_allocation
  FOR EACH ROW EXECUTE FUNCTION validate_cost_allocation_current_revision();

-- 5) 🔴 逐行分摊额之和必须严格等于货件成本总额。
--
-- **延迟约束触发器**（DEFERRABLE INITIALLY DEFERRED）：版本行与其明细行是同一事务里写的，
-- 校验必须在**事务提交时**做（那时明细行已经齐了），不能在版本行插入的那一刻做。
-- 分摊是**拆分**不是缩水 —— 少摊了钱就是错账，DB 层必须拦住。
CREATE FUNCTION validate_cost_allocation_revision_totals() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  line_count int;
  sum_allocated numeric(19,4);
BEGIN
  SELECT count(*), coalesce(sum(l.allocated_amount), 0)
    INTO line_count, sum_allocated
    FROM cost_allocation_revision_line l
   WHERE l.tenant_id=NEW.tenant_id AND l.revision_id=NEW.id;
  IF line_count = 0 THEN
    RAISE EXCEPTION '分摊版本必须至少有一行明细' USING ERRCODE='23514';
  END IF;
  IF sum_allocated <> NEW.total_cost THEN
    RAISE EXCEPTION '分摊明细逐行合计(% )必须严格等于货件成本总额(% )',
      sum_allocated, NEW.total_cost USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER cost_allocation_revision_totals
  AFTER INSERT ON cost_allocation_revision
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION validate_cost_allocation_revision_totals();

-- ============================================================ 第五节：RLS 与授权

-- 三张表都是租户级业务表（分摊是租户自己的成本事实），只需要租户隔离，
-- 可见性由端点层权限把关。
-- 照抄 V215 第四节的 DO $$ FOREACH 写法：三张表的 isolation 策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的成本 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['cost_allocation','cost_allocation_revision','cost_allocation_revision_line'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性跟随父对象：看不到分摊单就看不到它的版本，看不到版本就看不到它的明细行。
-- 这两条是 RESTRICTIVE：与 isolation 策略**同时**满足才放行。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['cost_allocation_revision','cost_allocation_revision_line'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM cost_allocation a WHERE a.tenant_id=%I.tenant_id AND a.id=%I.allocation_id)) WITH CHECK (EXISTS(SELECT 1 FROM cost_allocation a WHERE a.tenant_id=%I.tenant_id AND a.id=%I.allocation_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON cost_allocation TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON cost_allocation TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON cost_allocation_revision TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON cost_allocation_revision TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON cost_allocation_revision_line TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON cost_allocation_revision_line TO starcon_app;
