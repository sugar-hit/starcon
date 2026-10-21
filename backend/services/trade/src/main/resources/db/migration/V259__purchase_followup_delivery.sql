-- 6.3.2.1 跟单与交期（卷 06 采购与供应 / L3 6.3.2 采购跟踪的第一格）。
--
-- 蓝图 06-purchase-and-supply.md 原文：
--   职责：记录与供应商的沟通跟进（催单、确认、异常反馈），维护承诺到货日变更历史，统计平均延迟天数。
--   输入：跟单记录；新的承诺日期与原因。
--   输出：跟单时间线；交期变更历史；延迟统计。
--   关系：上游 = 6.3.1.1；下游 = 6.2.1.1（交期变化影响断货预测）、6.1.1.3 评级（准时率）。
--         **交期变更是断货风险的主要输入**，必须回写补货建议。
--
-- 归属：**trade 服务**（卷 06 后端主责服务 = trade + catalog（party），本仓不存在 purchase 服务）。
--
-- 本迁移**只新建 3 张表 + 2 个序列 + 触发器，零 ALTER 零 DROP 既有表** ⇒ 跨车道 DDL 回归面 = 0
-- （同批另有车道往 trade 落 V255 / V256 / V257 / V258，版本号互不撞号）。
--
-- ===========================================================================
-- 一、这个模块拥有什么、不拥有什么
-- ===========================================================================
--
-- ⚠️ **本模块拥有「跟单沟通记录」与「交期变更历史」两份举证凭据**：
--
--   1. `purchase_followup_record` —— 跟单记录（催单 / 确认 / 异常反馈 / 其他）。
--   2. `purchase_delivery_change` —— 交期变更历史（每一次变更的 from / to / 原因 / 操作人 / 时刻）。
--   3. `purchase_delivery_writeback` —— 回写留痕（交期变更落到 6.2.1.1 / 6.2.1.2 的**真实结果**）。
--
-- 🔴 **本模块**不**拥有「采购单」**：
--   `purchase_order` 归 **6.3.1.1 采购单列表**所有（V254 已交付）。
--   V254 第 140 行对该列的注释原文是：「承诺到货日（对供应商的正式承诺；**6.3.2.1 跟单改的是它的历史**）」
--   ⇒ **本迁移零 ALTER `purchase_order`、零 UPDATE `purchase_order`**。
--
--   ⚠️ **「当前承诺到货日」有两个口径，本模块显式区分，不混成一个数**：
--
--     * **单据承诺日** = `purchase_order.promised_arrival_date`
--       （6.3.1.1 所有，是**单据上的正式承诺**；本模块**只 SELECT**）。
--     * **跟单承诺日** = 本表 `purchase_delivery_change` 里最后一条变更的 `to_promised_date`
--       （没有变更过则等于单据承诺日）。
--
--   ⇒ **延迟天数的统计口径用「跟单承诺日」**（最后一次变更后的承诺日），
--     而**逾期 / 列表口径仍以单据承诺日为准**（6.3.1.1 自己的纪律：
--     「两边若不一致（跟单改了交期而本单没更新），以本单为准并在能力声明里说明」）。
--     两者不一致时，本模块把**两个日期都如实返回**并标 `promise_diverged = true`，
--     **绝不**替 6.3.1.1 改它的单 —— 改单是 6.3.1.2（采购单创建与编辑，**未交付**）的职责，
--     且 V254 的 `guard_purchase_order_status` 对 `CLOSED` 单据冻结了 `promised_arrival_date`
--     （对已关闭的单子改交期会被 DB 直接拒）。
--
-- ⚠️ **本模块**不**拥有「到货事实」**：
--   `purchase_arrival_tracking` / `purchase_arrival_receipt` 归 **6.3.2.2 到货与逾期**所有。
--   ⇒ 本迁移**只 SELECT 它们**（实际到货日 = 到货流水的 `arrived_at`），
--     **不 ALTER、不加约束、不建指向它们的外键**。
--     关联键用 `purchase_order_no`（同库同租户，RLS 覆盖），
--     与 6.3.1.1 / 6.3.2.2 之间的口径钉死一致。
--
-- ⚠️ **本模块**不**拥有「补货建议」与「断货风险」**：
--   `replenishment_suggestion*` / `stockout_risk_*` 归 **6.2.1.2 / 6.2.1.1**所有。
--   ⇒ 本迁移**零 INSERT / ZERO UPDATE 那些表**。
--     交期变更落库后**复用它们自己的写入入口**去触发重算
--     （6.2.1.1 的 `StockoutRiskService.compute` / 6.2.1.2 的 `ReplenishmentSuggestionService.compute`），
--     写入所有权仍归原模块，本模块只是**触发**它 —— 结果如实落在
--     `purchase_delivery_writeback` 里（成功 / 失败 / 跳过都留痕）。
--
-- ===========================================================================
-- 二、三个口径（本迁移把它们钉进 DB 与代码，页面与测试都按这个来）
-- ===========================================================================
--
-- 🔴 **口径①：实际到货日**
--   = 6.3.2.2 到货流水 `purchase_arrival_receipt.arrived_at` 的 **UTC 日历日**。
--   一张跟踪单的「全部到货日」= 它所有流水里 `arrived_at` **最晚**那条的 UTC 日期。
--   ⚠️ 只有 `arrival_status = 'FULLY_RECEIVED'` 的跟踪单进入「已到货」样本 ——
--     部分到货的单还没到齐，用它算延迟等于把「到了一半」当成「到了」。
--
-- 🔴 **口径②：延迟天数（整数天）**
--   延迟天数 = 实际到货日 − **到货时刻生效的承诺日**
--   「到货时刻生效的承诺日」= 最后一条 `changed_at <= arrived_at` 的交期变更的 `to_promised_date`；
--   没有这样的变更则取单据承诺日 `purchase_order.promised_arrival_date`。
--   ⚠️ **多次改期时按「到货那一刻生效的承诺日」算**，不是按原始承诺日，也不是按最后一次变更 ——
--     供应商在 3 月 1 日答应 3 月 10 日到，3 月 5 日改成 3 月 20 日，3 月 18 日到货：
--     这单**不是**逾期 8 天，而是逾期 8 天…（承诺 3/20，实际 3/18）⇒ 提前 2 天。
--     若按原始承诺日 3/10 算会得出「逾期 8 天」，把供应商的改期说成它违约 —— 结论完全相反。
--   延迟天数**可以是负数**（提前到货）。整数天，不带时刻，**不存浮点**。
--
-- 🔴 **口径③：未到货的单子算不算延迟？**
--   **不算「延迟天数」** —— 延迟天数必须有终点（实际到货日），没到货就没有终点。
--   但**另算「已逾期天数」**：今天（数据库时钟）− 当前跟单承诺日，仅对**逾期且尚未全部到货**的单子。
--   ⇒ 统计同时给两个数，**不混成一个**：平均延迟天数（已到货样本） + 已逾期天数（未到货样本）。
--   把「还没到」当成「延迟 0」会让供应商看起来完美，把「还没到」算进平均延迟会让均值失真。
--
-- ===========================================================================
-- 三、状态机 / 不变量
-- ===========================================================================
--
-- 跟单记录与交期变更都是**追加式举证凭据** ⇒ 触发器 + GRANT 双重收回 UPDATE / DELETE。
-- （照抄 trade 既有做法 `guard_*_append_only`。）
--
-- 交期变更的 DB 级不变量：
--   * `from_promised_date <> to_promised_date` —— 改成同一天不是变更，是空动作，拒。
--   * `reason` 非空 —— **改期必须写原因**（「供应商说产能排满了」是凭据的一部分）。
--   * 金额与数量：本模块**不产出金额**（跟单与交期是凭据，不是单据）；
--     唯一与数量相关的是「延迟天数」，它是**整数天**（`int`），**不是**数量口径。
--
-- ===========================================================================
-- 四、序号
-- ===========================================================================
CREATE SEQUENCE purchase_followup_no_seq START 1;
CREATE SEQUENCE purchase_delivery_change_no_seq START 1;

-- ===========================================================================
-- 五、跟单记录（沟通）
-- ===========================================================================
CREATE TABLE purchase_followup_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 跟单号（租户内唯一）。
  followup_no varchar(32) NOT NULL CHECK(btrim(followup_no) <> ''),

  -- ---- 被跟的采购单：逻辑引用（6.3.1.1 拥有 purchase_order ⇒ 不建 FK）----
  purchase_order_no varchar(64) NOT NULL CHECK(btrim(purchase_order_no) <> ''),
  purchase_order_ref_id uuid,

  -- ---- 供应商：catalog 独立库 ⇒ 逻辑引用 + 展示快照 ----
  supplier_ref_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 跟单类型与渠道 ----
  -- ⚠️ **「催单」在这里是「沟通」而不是「单据上的计数」**：
  --   6.3.1.1 已有 `POST /trade/purchase-orders/batch/urge`（维护 `purchase_order.urge_count`），
  --   那是**单据上的催单次数**；本模块的 URGING 是**一次具体的沟通记录**
  --   （谁、什么时候、跟供应商说了什么、供应商怎么答的）。
  --   两者是**两件事**：前者回答「催过几次」，后者回答「催的时候说了什么」。
  --   ⇒ 本模块**不写** `purchase_order.urge_count`，两张表也不合并。
  followup_type varchar(20) NOT NULL
      CHECK(followup_type IN ('URGING','CONFIRM','EXCEPTION','OTHER')),
  channel varchar(24) NOT NULL
      CHECK(channel IN ('PHONE','EMAIL','IM','WECHAT','PLATFORM','OTHER')),

  -- ---- 沟通内容与供应商答复 ----
  content text NOT NULL CHECK(length(btrim(content)) > 0 AND length(content) <= 2000),
  supplier_reply text NOT NULL DEFAULT '' CHECK(length(supplier_reply) <= 2000),

  -- ---- 时刻：发生时刻（timestamptz，UTC 口径）与记录时刻 ----
  -- ⚠️ 沟通**发生**的时刻与**登记**的时刻不是一回事（补录是常态），
  --   所以两个时刻各存各的，不合并成一个。
  occurred_at timestamptz NOT NULL,
  next_followup_at timestamptz,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,followup_no)
);

COMMENT ON TABLE purchase_followup_record IS
  '6.3.2.1 跟单记录：与供应商的沟通凭据（追加式）；「催单」是沟通，不是 purchase_order.urge_count';

-- ===========================================================================
-- 六、交期变更历史
-- ===========================================================================
CREATE TABLE purchase_delivery_change (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  change_no varchar(32) NOT NULL CHECK(btrim(change_no) <> ''),

  -- ---- 被改期的采购单：逻辑引用（同上，不建 FK）----
  purchase_order_no varchar(64) NOT NULL CHECK(btrim(purchase_order_no) <> ''),
  purchase_order_ref_id uuid,

  -- ---- 供应商快照（catalog 逻辑引用）----
  supplier_ref_id uuid NOT NULL,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 变更前后的承诺到货日（业务日历日，**不存时刻**）----
  -- ⚠️ 承诺到货日是 `date`：它是**日历日**口径（「3 月 10 日到货」不是一个时刻）。
  --   存时刻会在跨时区时把 3 月 10 日变成 3 月 9 日。
  from_promised_date date NOT NULL,
  to_promised_date date NOT NULL,

  -- ---- 原因：改期必须写原因 ----
  reason text NOT NULL CHECK(length(btrim(reason)) > 0 AND length(reason) <= 1000),

  changed_by uuid NOT NULL,
  changed_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,change_no),

  -- 改成同一天不是变更，是空动作 ⇒ 拒。
  CONSTRAINT purchase_delivery_change_moved CHECK(from_promised_date <> to_promised_date)
);

COMMENT ON TABLE purchase_delivery_change IS
  '6.3.2.1 交期变更历史：每一次承诺到货日变更的 from/to/原因/操作人/时刻（追加式）';

-- ===========================================================================
-- 七、回写留痕（交期变更 → 6.2.1.1 / 6.2.1.2 的真实结果）
-- ===========================================================================
--
-- ⚠️ 为什么要有这张表：蓝图要求「交期变更是断货风险的主要输入，必须回写补货建议」。
--   回写是**跨模块触发**（本模块触发 6.2.1.1 / 6.2.1.2 自己的服务方法），
--   它**可能失败**（上游 warehouse 不可达、租户没配覆盖天数……）。
--   ⇒ 「回写了没有」必须是**可查的事实**，而不是一次调用返回就散掉的日志。
--   成功 / 失败 / 跳过都留一行，失败行带原因 —— 绝不假装已经回写。
CREATE TABLE purchase_delivery_writeback (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  change_id uuid NOT NULL
      REFERENCES purchase_delivery_change(id) ON DELETE CASCADE,

  -- ---- 目标模块与它自己的写入入口 ----
  -- 6.2.1.1 = POST /trade/stockout-risk/snapshots
  -- 6.2.1.2 = POST /trade/replenishment-suggestions/batches
  target_module varchar(16) NOT NULL CHECK(target_module IN ('6.2.1.1','6.2.1.2')),
  target_endpoint varchar(128) NOT NULL,
  owner_module varchar(16) NOT NULL,

  -- ---- 这条事实本身（不是那两张表的副本）----
  -- available=false ⇒ 本模块**没有**把交期事实写进去（写入口的语义对不上），如实标出来。
  available boolean NOT NULL,
  -- delivery_input_applied：交期事实**是否**真的作为参数进了那次重算。
  --   6.2.1.1 = true（备货周期就是从交期算出来的）；
  --   6.2.1.2 = false（它的交期来自 catalog 报价与它自有的采购配置，详见服务层注释）。
  delivery_input_applied boolean NOT NULL DEFAULT false,
  status varchar(16) NOT NULL CHECK(status IN ('SUCCEEDED','FAILED','SKIPPED')),

  -- 结果定位（快照号 / 批次号）与失败原因。
  result_ref varchar(64) NOT NULL DEFAULT '',
  result_summary varchar(500) NOT NULL DEFAULT '',
  error_message varchar(1000) NOT NULL DEFAULT '',
  impact varchar(500) NOT NULL DEFAULT '',

  attempted_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,change_id,target_module)
);

COMMENT ON TABLE purchase_delivery_writeback IS
  '6.3.2.1 回写留痕：交期变更落到 6.2.1.1 / 6.2.1.2 的真实结果（成功/失败/跳过都留痕，追加式）';

-- ===========================================================================
-- 八、追加式（举证凭据不可改写）
-- ===========================================================================
CREATE FUNCTION guard_purchase_followup_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的留痕表（%）：跟单记录、交期变更与回写结果都是「当时发生了什么」的举证凭据',
    TG_TABLE_NAME, TG_OP
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER purchase_followup_record_append_only BEFORE UPDATE OR DELETE ON purchase_followup_record
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_followup_append_only();
CREATE TRIGGER purchase_delivery_change_append_only BEFORE UPDATE OR DELETE ON purchase_delivery_change
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_followup_append_only();
CREATE TRIGGER purchase_delivery_writeback_append_only BEFORE UPDATE OR DELETE ON purchase_delivery_writeback
  FOR EACH ROW EXECUTE FUNCTION guard_purchase_followup_append_only();

-- ===========================================================================
-- 九、索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 跟单记录：按采购单读时间线（最常见的形状）。
CREATE INDEX purchase_followup_by_order ON purchase_followup_record(tenant_id, purchase_order_no, occurred_at DESC, id);
-- 按供应商读（供应商维度跟单汇总）。
CREATE INDEX purchase_followup_by_supplier ON purchase_followup_record(tenant_id, supplier_ref_id, occurred_at DESC, id);
-- 按类型筛（「把所有异常反馈捞出来」）。
CREATE INDEX purchase_followup_by_type ON purchase_followup_record(tenant_id, followup_type, occurred_at DESC, id);
-- 下次跟进提醒（未完成的跟进）。
CREATE INDEX purchase_followup_by_next ON purchase_followup_record(tenant_id, next_followup_at, id)
    WHERE next_followup_at IS NOT NULL;

-- 交期变更：按采购单读历史（口径②「到货时刻生效的承诺日」要按 changed_at 取最后一条）。
CREATE INDEX purchase_delivery_change_by_order ON purchase_delivery_change(tenant_id, purchase_order_no, changed_at, id);
-- 交期变更：按供应商读。
CREATE INDEX purchase_delivery_change_by_supplier ON purchase_delivery_change(tenant_id, supplier_ref_id, changed_at DESC, id);

-- 回写留痕：按变更读。
CREATE INDEX purchase_delivery_writeback_by_change ON purchase_delivery_writeback(tenant_id, change_id, target_module);

-- ===========================================================================
-- 十、多租户：RLS + 授权（与 trade V1 / V92 / V234 / V238 / V254 同一套写法）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_followup_record','purchase_delivery_change','purchase_delivery_writeback'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_followup_record,purchase_delivery_change,purchase_delivery_writeback TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_followup_record,purchase_delivery_change,purchase_delivery_writeback TO starcon_app;
-- 收回改写权（"授权在前、收回在后"，顺序不能颠倒，否则会被上面的 GRANT 覆盖回去）。
-- 三张表全是举证凭据 ⇒ UPDATE / DELETE 一律拒。
REVOKE UPDATE,DELETE ON
  purchase_followup_record,purchase_delivery_change,purchase_delivery_writeback FROM trade_app;

GRANT USAGE, SELECT ON SEQUENCE purchase_followup_no_seq TO trade_app;
GRANT USAGE, SELECT ON SEQUENCE purchase_followup_no_seq TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE purchase_delivery_change_no_seq TO trade_app;
GRANT USAGE, SELECT ON SEQUENCE purchase_delivery_change_no_seq TO starcon_app;
