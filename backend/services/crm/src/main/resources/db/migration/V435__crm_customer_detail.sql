-- 15.3.1.2 客户详情：统一时间线（订单 / 售后 / 消息 / 评价 / 接触记录合并）与详情档案。
--
-- 蓝图 15 卷 15.3.1.2 的原话：
--   「职责：客户全档案：基本信息、跨店铺身份关系、订单历史、售后历史、消息历史、评价、
--     标签、接触记录、风险与价值分层。
--     输入：customer_id；聚合各域数据。
--     输出：详情视图 + 时间线（订单/消息/售后合并时间线）。
--     关系：上游 = 15.3.1.1；下游 = 9 订单、11 售后、15.1 消息。
--     **统一时间线是客服理解客户的唯一高效方式** —— 跨系统翻找会显著拖慢响应。」
--
-- ============================================================================
-- 零、本文件与既有模块的边界（写入所有者各自独立）
-- ============================================================================
--
-- 本文件是 **crm 数据库的第二十一个迁移**（V99 知识库 … V436 生命周期，见目录）。
--
-- 本模块**只拥有两张表**：
--   crm_customer_timeline_entry  统一时间线条目（合并后的时间线本体）
--   crm_customer_timeline_run    一次「重建该客户时间线」的运行记录（可审计）
--
-- 与兄弟模块的边界 —— 本模块**只读**它们，**一个字都不写**：
--   * V432（15.3.1.1 客户列表）：`crm_customer_profile` / `crm_customer_amount`
--     —— 详情视图的**基本信息、价值分层、风险标记、金额**全部来自这里。
--     本模块**只读**，不新增客户主档列、不改它的身份语义。
--   * V295（15.3.2.1 客户标签）：`crm_customer_tag_assignment` —— 详情页的「标签」段。
--   * V299（15.3.2.2 接触记录）：`crm_customer_contact` / `crm_customer_opt_out`
--     —— 详情页的「接触记录 / 退订」段。
--   * V293（15.1.1.1 消息收件箱）：`crm_message` —— 详情页的「消息历史」段。
--   * V294（15.2.1.1 商品评价）：`crm_product_review` —— 详情页的「评价」段。
--   * V436（15.6.2.1 生命周期）：`crm_customer_lifecycle` —— 详情页的「复购 / 流失预警」段。
--
-- 🔴 **15.3.1.3 跨店合并拥有 `crm_customer_merge_*` 系列表**
--   （逻辑客户 / 合并映射 / 候选 / 操作记录 / 运行台账）。
--   本文件**不建**其中任何一张、**不读**其中任何一张、**不写**其中任何一张。
--   详情页的「跨店铺身份关系」段按**合并映射尚未交付**的形态实现：
--   如实显示「未合并 / 合并能力由 15.3.1.3 负责」，
--   只把**同租户下的同名 / 同联系方式指纹**列成**线索**（clue），
--   线索**不是**合并结论 —— 合并由 15.3.1.3 自己判定、自己落库。
--
-- ============================================================================
-- 一、🔴 为什么时间线要落库，而不是每次请求现算
-- ============================================================================
--
-- 蓝图加粗句：「**统一时间线是客服理解客户的唯一高效方式** —— 跨系统翻找会显著拖慢响应。」
--
-- 「不拖慢响应」这句话反过来要求：详情页打开时**不能**为了拼一条时间线
-- 去串行地打 5 个上游（订单 / 售后 / 消息 / 评价 / 接触记录）。
-- 所以时间线**物化**在本模块自己的表里：
--   * 重建是**一次显式动作**（端点 POST），把 5 个来源拉齐、合并、落库；
--   * 详情页**读**这张表 —— 一次查询拿到全部，不再串上游；
--   * 重建是**幂等**的：唯一键 (租户, 客户, 来源类别, 来源标识)，
--     重跑不会产生重复条目，也不会因为上游改状态而留下重复行。
--
-- ⚠️ 时间线是**派生数据**，不是新的事实源：每个条目都记 `source_module` 与
-- `source_ref`，指回真正的拥有者。真相永远在拥有者那边。
--
-- ============================================================================
-- 二、🔴 禁浮点与时间纪律
-- ============================================================================
--
--   * 金额：numeric(19,4) + BigDecimal；数量：numeric(19,6) + BigDecimal。
--   * 时间：一律 timestamptz + OffsetDateTime，绑定写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime…)` 会在东八区**静默偏 −28800 秒**。
--
-- 🔴 **统一时钟域**：排序与「这次重建收进来了什么」必须落在**同一个时钟域**里，
--   否则拿 JVM 墙钟和 DB 时钟比大小会得到随机的顺序。
--   本文件的落法：
--     * `occurred_at` —— 事实**发生**的时刻（来自上游自己的时钟）；
--     * `indexed_at`  —— **入库**时刻，**DEFAULT now()**（DB 时钟）；
--     * 时间线排序 = `occurred_at DESC, indexed_at DESC, id DESC`，
--       三个键里后两个都是 DB 时钟写的 ⇒ 同源可比；
--     * 运行记录的 `started_at` / `finished_at` 由服务层**从 DB 取 now()** 再回写，
--       不拿 JVM 的 `OffsetDateTime.now()`。
--
-- ============================================================================
-- 三、🔴 联系方式默认脱敏（沿用 15.3.1.1 的口径）
-- ============================================================================
--
-- 时间线条目的 `title` / `summary` 里**不写**买家联系方式。
-- 明文联系方式只在详情端点单独授权（`crm.customers.pii.read`）时才返回，
-- 且只来自 V432 的客户主档列 —— 本表**不新增**任何联系方式列。
--
-- ============================================================================
-- 四、⚠️ 为什么用**触发器回表**而不是**数据库外键**
-- ============================================================================
--
-- 同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
-- constraint）会被外键打破。本文件**不给任何既有表建外键**，本文件自己新建的表之间
-- 也**一律用触发器做引用校验**，与 V99 / V220 / V228 / V292 / V295 / V299 / V432 同一手法。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 / V432 同一口径：角色可能已由运维预置，
-- 因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;
GRANT USAGE ON SCHEMA public TO starcon_app;

-- 运行号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一次重建时间线就报权限不足」。
CREATE SEQUENCE crm_customer_timeline_run_no_seq START 1;

-- ============================================================================
-- 1. crm_customer_timeline_entry —— 统一时间线条目
-- ============================================================================
CREATE TABLE crm_customer_timeline_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 客户主档行（**逻辑引用**，触发器校验，不用外键 —— 见文件头第四节）。
  -- 指 V432 的 crm_customer_profile.id。
  customer_id uuid NOT NULL,

  -- ===== 来源类别（五个来源合并成一条时间线）=====
  -- ORDER       9 订单（trade）
  -- AFTERSALES  11 售后（trade）
  -- MESSAGE     15.1 消息（本服务 crm_message）
  -- REVIEW      评价（本服务 crm_product_review / 平台评价）
  -- CONTACT     15.3.2.2 接触记录（本服务 crm_customer_contact）
  source_kind varchar(16) NOT NULL
    CHECK (source_kind IN ('ORDER','AFTERSALES','MESSAGE','REVIEW','CONTACT')),

  -- 来源标识：上游 / 本服务的原始 id 的字符串形态。
  -- 🔴 不是数据库主键，只是**幂等重建**的幂等键的一部分。
  source_ref varchar(128) NOT NULL CHECK (btrim(source_ref) <> ''),

  -- 真正拥有这条事实的模块（责任归属；真相在它那边，不在本表）。
  source_module varchar(32) NOT NULL CHECK (btrim(source_module) <> ''),

  -- ===== 时间 =====
  -- 事实**发生**的时刻（上游自己的时钟）。
  occurred_at timestamptz NOT NULL,
  -- **入库**时刻（DB 时钟 now()）。排序的并列键之一。
  indexed_at timestamptz NOT NULL DEFAULT now(),

  -- ===== 人读字段 =====
  title varchar(200) NOT NULL DEFAULT '',
  summary varchar(600) NOT NULL DEFAULT '',
  -- 状态 / 分类（各来源自己的状态码，原样透传，不做翻译）。
  status varchar(32) NOT NULL DEFAULT '',
  category varchar(64) NOT NULL DEFAULT '',

  -- ===== 金额与数量 =====
  -- 金额：numeric(19,4) + BigDecimal（禁浮点）。没有金额的来源留 NULL。
  amount numeric(19,4),
  -- ISO 4217 alpha-3。有金额**必须**有币种（「100 是多少钱」不可判定）。
  currency char(3),
  -- 数量：numeric(19,6)（订单件数等）。
  quantity numeric(19,6),

  -- 判定依据快照（JSON）：原始载荷，供详情页展开，不做二次解释。
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 🔴 幂等重建的唯一键：同一条上游事实**重建多少次都只有一行**。
  UNIQUE (tenant_id, customer_id, source_kind, source_ref),
  -- 有金额必须有币种；币种必须是 ISO alpha-3。
  CONSTRAINT crm_customer_timeline_entry_amount_currency
    CHECK ((amount IS NULL) OR (currency IS NOT NULL AND currency ~ '^[A-Z]{3}$')),
  -- 数量不能是负数。
  CONSTRAINT crm_customer_timeline_entry_quantity_nonneg
    CHECK (quantity IS NULL OR quantity >= 0)
);

-- ============================================================================
-- 2. crm_customer_timeline_run —— 重建时间线的运行记录
-- ============================================================================
CREATE TABLE crm_customer_timeline_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 本次重建针对的客户（逻辑引用，触发器校验）。
  customer_id uuid NOT NULL,

  -- TL-yyyyMMdd-000001
  run_no varchar(32) NOT NULL DEFAULT '',

  -- SUCCEEDED 五个来源都取到 / PARTIAL 部分来源读不到（明细见 facts）/
  -- FAILED      一条都没落（上游整体读不到）
  status varchar(16) NOT NULL CHECK (status IN ('SUCCEEDED','PARTIAL','FAILED')),

  -- 本次落库 / 命中的条目总数。
  entry_count integer NOT NULL DEFAULT 0 CHECK (entry_count >= 0),

  -- ===== 各来源取到多少条（分来源计数，让「哪一段是空的」可复核）=====
  order_count integer NOT NULL DEFAULT 0 CHECK (order_count >= 0),
  aftersales_count integer NOT NULL DEFAULT 0 CHECK (aftersales_count >= 0),
  message_count integer NOT NULL DEFAULT 0 CHECK (message_count >= 0),
  review_count integer NOT NULL DEFAULT 0 CHECK (review_count >= 0),
  contact_count integer NOT NULL DEFAULT 0 CHECK (contact_count >= 0),

  -- 读不到的来源（jsonb：来源类别 → 人读原因）。
  -- 🔴 「读不到」与「没有数据」是**两件事**：前者必须如实报出来。
  unavailable_sources jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 扫描被截断（翻页到上限）：时间线**不是全量**，如实标出来。
  truncated boolean NOT NULL DEFAULT false,

  -- 失败原因（FAILED 时必填）。
  failure_reason varchar(600) NOT NULL DEFAULT '',
  -- 判定依据快照（JSON）：时间窗口、各来源窗口、原始计数。
  facts jsonb NOT NULL DEFAULT '{}'::jsonb,

  operator_id uuid,
  -- 🔴 由服务层**从 DB 取 now()** 再回写（DB 时钟），不拿 JVM 墙钟。
  started_at timestamptz NOT NULL,
  finished_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, run_no)
);

-- ============================================================================
-- 3. 索引
-- ============================================================================
-- 时间线默认排序：occurred_at 降序 / indexed_at 降序 / id 降序（稳定全序）。
CREATE INDEX crm_customer_timeline_entry_by_customer
  ON crm_customer_timeline_entry(tenant_id, customer_id, occurred_at DESC, indexed_at DESC, id DESC);

-- 按来源类别分面（「只看订单」这类过滤）。
CREATE INDEX crm_customer_timeline_entry_by_kind
  ON crm_customer_timeline_entry(tenant_id, customer_id, source_kind, occurred_at DESC);

-- 运行记录按客户倒序（详情页要显示「上次重建是什么时候」）。
CREATE INDEX crm_customer_timeline_run_by_customer
  ON crm_customer_timeline_run(tenant_id, customer_id, created_at DESC, id DESC);

-- ============================================================================
-- 4. 触发器（**一律不用外键** —— 理由见文件头第四节）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 4.1 guard_crm_customer_timeline_entry_target · 条目必须挂在存在的客户行上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_timeline_entry_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_ref varchar(128);
BEGIN
  SELECT customer_ref INTO v_ref FROM crm_customer_profile
   WHERE tenant_id = NEW.tenant_id AND id = NEW.customer_id;
  IF v_ref IS NULL THEN
    RAISE EXCEPTION '客户行 % 不存在（tenant=%），不能给它挂时间线条目',
      NEW.customer_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_timeline_entry_target_trg
  BEFORE INSERT OR UPDATE ON crm_customer_timeline_entry
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_timeline_entry_target();

-- ---------------------------------------------------------------------------
-- 4.2 guard_crm_customer_timeline_run_target · 运行记录必须挂在存在的客户行上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_timeline_run_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_ref varchar(128);
BEGIN
  SELECT customer_ref INTO v_ref FROM crm_customer_profile
   WHERE tenant_id = NEW.tenant_id AND id = NEW.customer_id;
  IF v_ref IS NULL THEN
    RAISE EXCEPTION '客户行 % 不存在（tenant=%），不能给它记时间线运行',
      NEW.customer_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_timeline_run_target_trg
  BEFORE INSERT OR UPDATE ON crm_customer_timeline_run
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_timeline_run_target();

-- ---------------------------------------------------------------------------
-- 4.3 guard_crm_customer_timeline_run_result · 运行结果必须自洽
-- ---------------------------------------------------------------------------
-- FAILED ⇒ 一条都没落（entry_count 必须是 0）；
-- SUCCEEDED / PARTIAL ⇒ 至少落了一条。
-- 「状态说失败、计数说落了 800 条」这种行没法复核。
CREATE FUNCTION guard_crm_customer_timeline_run_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = 'FAILED' AND NEW.entry_count <> 0 THEN
    RAISE EXCEPTION '失败的运行不能有条目（entry_count=0），实际 %', NEW.entry_count USING ERRCODE='23514';
  END IF;
  IF NEW.status IN ('SUCCEEDED','PARTIAL') AND NEW.entry_count = 0 THEN
    RAISE EXCEPTION '成功的运行至少要有一条时间线条目（entry_count >= 1）' USING ERRCODE='23514';
  END IF;
  IF NEW.status = 'FAILED' AND btrim(NEW.failure_reason) = '' THEN
    RAISE EXCEPTION '失败的运行必须写明原因' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_timeline_run_result_trg
  BEFORE INSERT OR UPDATE ON crm_customer_timeline_run
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_timeline_run_result();

-- ============================================================================
-- 5. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 / V432 同一口径：策略名固定 isolation，
-- USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_customer_timeline_entry','crm_customer_timeline_run'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_customer_timeline_entry, crm_customer_timeline_run TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_customer_timeline_entry, crm_customer_timeline_run TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一次重建时间线就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_customer_timeline_run_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_customer_timeline_run_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 6. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--
--   * **15.3.1.3 跨店合并**（并行交付中）：本模块的「跨店铺身份关系」段按
--     **未合并**的形态实现，只列**线索**（同名 / 同联系方式指纹），
--     线索**不是**合并结论。合并映射与逻辑客户由 15.3.1.3 拥有。
--   * **上游 9 订单 / 11 售后**在 trade 服务（端口 8085）：本服务**只经授权 HTTP API 读**，
--     一条跨库 SQL / 跨库 FK / 跨库视图都没有。读不到 ⇒ 如实标「该来源未接入」，
--     **不**编造一条时间线条目。
--   * **15.1 消息**：本服务 `crm_message`（V293，15.1.1.1）—— 同服务**只读**。
--   * **评价**：本服务 `crm_product_review`（V294，15.2.1.1）+ 平台评价只读端口。
