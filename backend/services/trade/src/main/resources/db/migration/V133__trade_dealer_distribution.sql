-- 9.7.1.2 经销与分销管理（授权台账 · 销量配额与回款考核 · 返点计提 · 窜货预警）。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：管理经销商/分销商：授权区域与产品线、授权期限、经销价与返点、
--             销量配额与回款考核、窜货监控（跨区域销售预警）。
--   **输入**：经销商档案与授权（区域/品类/期限/价格）；经销订单；销量与回款。
--   **输出**：经销授权台账；配额完成率；返点计提；窜货预警。
--   **关系**：上游 = 3.6.1.2、16 合规（授权）；下游 = 12 应付/返点、13 分析、5 定价。
--             **经销返点是应收外的负债性承诺**，需与财务确认计提口径；
--             窜货预警可复用 5.5.2 的站点价差倒挂逻辑。
--
-- 版本号由调度器预分配为 V133（trade 同批还有 V131 / V132，分别属于别的车道）。
-- **不自己找最大版本号**：并行车道同时提交迁移，撞号会让对方的 Flyway 直接炸掉。
--
-- ===========================================================================
-- 第零节：为什么经销商是**自己的表族**，而不是 b2b_* 上加一个 dealer 标记
-- ===========================================================================
-- 同一 L3（9.7.1）下的 9.7.1.1 已交付（trade V120），它是**批发订单与账期**；
-- 本模块是**经销商授权 / 配额 / 返点 / 窜货**。两者相邻但不同：
--
--   * 9.7.1.1 的事实是「一张批发订单走完下单→发货→账期应收」，
--     写入所有者是那条车道，V120 明确写了「零 ALTER 既有表」是它的验收项。
--   * 本模块的事实是「某个经销商在某个授权区域与期限内卖了多少、
--     够不够配额、欠多少返点、有没有窜货」——
--     这五件事在 b2b_order 上**一行都放不下**：
--     授权区域是多值集合、授权期限是区间、配额是「期间 × 目标」、
--     返点是负债性承诺（不是应收）、窜货要拿「授权区域」去比对「实际销售区域」。
--
-- 给别人的事实表加 dealer_id / authorized_regions / rebate_rate / quota_target，
-- 会同时踩三个雷：
--   ① 那条车道正在跑的集成测试会在迁移顺序变化时炸掉；
--   ② 一半的行永远是 NULL（「这个客户不是经销商所以没有配额」）——
--      那正是 catalog V30 拒绝掉的「巨大可空字段表」；
--   ③ 「一张事实表一个写入所有者」这条 AGENTS.md 模块边界纪律被打破。
--
-- 所以本模块建自己的 dealer_* 表族，**零 ALTER 任何既有表**，
-- 需要引用 9.7.1.1 的口径时只用**只读 join**（同服务同库，不是跨库），
-- 无跨库 FK、无触发器、无对他人表的写入。
--
-- ===========================================================================
-- 第一节：三段式结构 —— 档案投影 / 授权台账 / 考核与预警
-- ===========================================================================
--
--   上游投影（只读，由本服务的授权端点同步写入）
--     dealer_profile        ← 3.6.1.2 catalog.party_customer_profile（经销商是 CUSTOMER 角色）
--
--   授权台账（本模块的写入所有者）
--     dealer_authorization       区域 + 产品线 + 期限 + 经销价 + 返点比例 + 合规状态
--     dealer_authorization_event 台账事件流（append-only，改授权必留痕）
--
--   考核与预警（事实表）
--     dealer_quota            期间 × 授权 的销量/金额/回款目标
--     dealer_sale             经销商实际销售与回款记录（配额完成率与窜货判定的唯一来源）
--     dealer_rebate           返点计提台账（**本模块口径，尚未与财务确认**）
--     dealer_diversion_alert  窜货预警（跨区域 / 跨产品线销售）
--
-- **跨服务不建 FK、不建视图、不跨库 JOIN**（AGENTS.md 模块边界纪律）：
-- 3.6.1.2 已交付（catalog V27/V30），但那是**另一个服务另一个库**，
-- 本服务只能持有逻辑 party_id 与自己的只读投影。
--
-- ===========================================================================
-- 第二节：金额与比率 —— 本模块最容易翻车的地方
-- ===========================================================================
-- 蓝图的四个输出里有三个是**除法或乘法**（配额完成率、返点计提、窜货判定），
-- 而 AGENTS.md 明令「禁止浮点记账」。因此：
--
--   * 一切金额 numeric(19,4) / numeric(24,4) + Java BigDecimal，全链路无 float/double。
--   * 一切数量 numeric(19,6)。
--   * rebate_rate 用 **numeric(9,6)**，CHECK 限定 0..1（不是 0..100，
--     0.05 与 5% 在两种表示下差 100 倍，CHECK 是唯一的护栏）。
--   * 配额完成率**不是落库列**，而是 SQL 里的 NUMERIC 除法
--     （round(actual/target, 6)），服务层用 BigDecimal 复算一遍 ——
--     落库一个 REAL 列会让「同一个数在列表和详情里不一样」。
--   * **除零**：目标为 0（不限配额）时完成率是「无定义」而不是 100%，
--     返回 null，不返回 0 —— 0 会被读成「一个都没完成」。
--
-- ===========================================================================
-- 第三节：授权期限是**日期区间**，过期不是「写入时刻 + N 天」
-- ===========================================================================
-- dealer_authorization 落 valid_from / valid_to **两个 date**。
-- 过期判定一律是「给定日期是否落在区间外」的比较，
-- **不**从 created_at + N 天反推：后者在补录历史授权时会把期限整体平移，
-- 而「这个经销商的授权到哪天为止」是必须逐字对得上的合同事实。

-- ---------------------------------------------------------------------------
-- 1. dealer_profile —— 3.6.1.2 客户角色档案的 trade 侧只读投影
-- ---------------------------------------------------------------------------
-- 经销商在 3.6 实体目录里就是**一个 CUSTOMER 角色的往来单位**，不是新主体。
-- 所以投影的唯一键是 party_id，档案本身不含「是不是经销商」——
-- 那是 dealer_authorization 的事实。
--
-- 词表 STRATEGIC/KEY/STANDARD/PROSPECT/RISK 与 catalog V30
-- party_customer_profile.customer_level 逐字一致：投影与上游词表漂移时，
-- 报错会指到「枚举值不合法」而不是「投影与上游对不上」。
CREATE TABLE dealer_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  party_id uuid NOT NULL,
  party_code varchar(32) NOT NULL DEFAULT '',
  party_name varchar(160) NOT NULL DEFAULT '',
  customer_level text NOT NULL DEFAULT 'STANDARD'
    CHECK(customer_level IN ('STRATEGIC','KEY','STANDARD','PROSPECT','RISK')),
  default_currency char(3) CHECK(default_currency IS NULL OR default_currency ~ '^[A-Z]{3}$'),
  payment_terms_days int NOT NULL DEFAULT 0
    CHECK(payment_terms_days >= 0 AND payment_terms_days <= 365),
  -- 投影的可追溯性：同步事件带版本号，版本回退必须被拒。
  source_system varchar(32) NOT NULL DEFAULT 'catalog',
  source_version bigint NOT NULL DEFAULT 0,
  synced_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, party_id),
  UNIQUE(tenant_id, party_code)
);

-- ---------------------------------------------------------------------------
-- 2. dealer_authorization —— 授权台账（输出①）
-- ---------------------------------------------------------------------------
-- 授权 = 区域 + 产品线 + 期限 + 经销价 + 返点比例 的**一个不可分割的整体**，
-- 所以它们在同一行：把区域与产品线拆到子表会让「这条授权到底授权了什么」
-- 必须 join 两次才答得上来，而台账是给运营逐行看的。
--
-- region_codes / product_line_codes 是 text[]（多值集合，不是单值 FK）：
-- 「授权华东与华南」用一行表达，拆成子表则是「授权几行」这种无意义的问题。
-- 数组上的判据（是否窜货）由服务层用 = ANY(...) 判定。
--
-- **合规状态**：蓝图把「16 合规（授权）」列为上游，而 16 合规**未交付**，
-- 因此本服务没有任何可读的合规结论来源。这里的取值词表声明了三个状态，
-- 但**服务层只写得出 UNVERIFIED** —— 另两个留给 16 合规交付后写入。
-- 刻意不是 NOT NULL DEFAULT 'VERIFIED'：把「没有校验过」画成「校验通过」
-- 是合规类字段最危险的一种默认值。
CREATE TABLE dealer_authorization (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  dealer_profile_id uuid NOT NULL,
  auth_code varchar(48) NOT NULL,
  -- 授权区域与产品线：非空数组（一条授权必须圈定范围，否则它对窜货判定毫无意义）。
  region_codes text[] NOT NULL CHECK(array_length(region_codes, 1) > 0),
  product_line_codes text[] NOT NULL CHECK(array_length(product_line_codes, 1) > 0),
  -- 授权期限：两个 date 的区间，不从写入时刻推算（见第零节第三节）。
  valid_from date NOT NULL,
  valid_to date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 经销价：精确到 4 位（与 V120 的单价同刻度），**禁浮点**。
  dealer_price numeric(19,4) NOT NULL CHECK(dealer_price > 0),
  -- 返点比例：0..1 的小数（不是百分数）。0 是合法值（不返点）。
  rebate_rate numeric(9,6) NOT NULL CHECK(rebate_rate >= 0 AND rebate_rate <= 1),
  status text NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','SUSPENDED','EXPIRED')),
  -- 合规状态：服务层只写得出 UNVERIFIED（16 合规未交付，无可读上游）。
  compliance_state text NOT NULL DEFAULT 'UNVERIFIED'
    CHECK(compliance_state IN ('UNVERIFIED','VERIFIED','REJECTED')),
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, auth_code),
  -- A1 期限必须是合法区间：valid_to < valid_from 的授权没有「有效期内」可言。
  CHECK(valid_to >= valid_from),
  -- A2 数组元素不许是空串（否则 '= ANY' 判定会被空串意外命中）。
  CHECK(array_position(region_codes, '') IS NULL),
  CHECK(array_position(product_line_codes, '') IS NULL)
);

-- 台账事件流：授权的新建与每一次变更都必须留痕（append-only，无 UPDATE/DELETE 形状）。
CREATE TABLE dealer_authorization_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  authorization_id uuid NOT NULL,
  event_type text NOT NULL
    CHECK(event_type IN ('CREATED','SUSPENDED','RESUMED','EXPIRED','TERMS_CHANGED')),
  from_status text CHECK(from_status IS NULL OR from_status IN ('ACTIVE','SUSPENDED','EXPIRED')),
  to_status text CHECK(to_status IS NULL OR to_status IN ('ACTIVE','SUSPENDED','EXPIRED')),
  note varchar(500) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, authorization_id)
    REFERENCES dealer_authorization(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 3. dealer_quota —— 销量配额与回款考核目标（输出② 的目标侧）
-- ---------------------------------------------------------------------------
-- 配额挂在**授权**上而不是经销商上：同一个经销商在同一时期可能有多份授权
-- （不同区域、不同产品线），配额是「这份授权下的目标」而不是「这个经销商要卖多少」，
-- 否则两条授权的目标会被相加，而区域越界的销量也会被算进完成率里。
--
-- 目标为 0 = **不限配额**（不考核），不是「目标是 0 件」。
-- 完成率对 target=0 返回 null 而不是 0 或 100%：见第零节第二节。
CREATE TABLE dealer_quota (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  authorization_id uuid NOT NULL,
  period_start date NOT NULL,
  period_end date NOT NULL,
  target_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(target_quantity >= 0),
  target_amount numeric(24,4) NOT NULL DEFAULT 0 CHECK(target_amount >= 0),
  -- 回款考核目标：0 = 不考核回款。
  target_settled_amount numeric(24,4) NOT NULL DEFAULT 0 CHECK(target_settled_amount >= 0),
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, authorization_id, period_start),
  FOREIGN KEY(tenant_id, authorization_id)
    REFERENCES dealer_authorization(tenant_id, id) ON DELETE CASCADE,
  CHECK(period_end >= period_start)
);

-- ---------------------------------------------------------------------------
-- 4. dealer_sale —— 经销商销售与回款记录（事实表，本模块的写入所有者）
-- ---------------------------------------------------------------------------
-- 这是配额完成率与窜货判定的**唯一来源**：两者都只读这张表，
-- 判定逻辑因此没有第二条路径（「界面上算的不算」是这类模块最常见的分叉）。
--
-- **sold_on 是业务日期（date）而不是从 timestamptz 截出来的**：
-- 配额期间是 [period_start, period_end] 的日历区间，而「这笔销售算在哪一天」
-- 是**卖方所在时区的日历日**。用 timestamptz 截 date 会在
-- 夜里的单子上偏移一天，且偏移方向取决于 JVM 与数据库各自的时区设置。
-- sold_on 由调用方显式给出（它本来就是业务事实），recorded_at 才是系统入库时刻。
--
-- settled_amount 是**回款**：0 = 已回款；NULL = 未回款。
-- 刻意可空而不是用 0：12 应付未交付，「未回款」与「回款额为 0」在本模块里
-- 只能区分成这两种，回款考核要算的是已回款金额，未回款的部分必须能表达出来。
CREATE TABLE dealer_sale (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  dealer_profile_id uuid NOT NULL,
  authorization_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 销售所属产品线：与授权的 product_line_codes 比对（跨产品线同样是越界）。
  product_line_code varchar(64) NOT NULL,
  -- 实际销售区域：与授权的 region_codes 比对 —— 这就是窜货判定。
  sold_region_code varchar(64) NOT NULL CHECK(btrim(sold_region_code) <> ''),
  sold_on date NOT NULL,
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),
  unit_price numeric(19,4) NOT NULL CHECK(unit_price > 0),
  -- 唯一金额来源：quantity × unit_price，由服务层算出，不接受调用方直接传总额。
  amount numeric(24,4) NOT NULL CHECK(amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  settled_amount numeric(24,4) CHECK(settled_amount IS NULL OR settled_amount >= 0),
  note varchar(500) NOT NULL DEFAULT '',
  recorded_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, dealer_profile_id) REFERENCES dealer_profile(tenant_id, id),
  FOREIGN KEY(tenant_id, authorization_id)
    REFERENCES dealer_authorization(tenant_id, id),
  -- S1 金额必须等于数量 × 单价（服务层算，DB 独立复算一遍）。
  -- 差值容忍 0.0001：单价与数量各自的刻度相乘最多产生 10 位小数，
  -- 而金额列只留 4 位，比较的是「按金额刻度舍入后是否相等」而不是浮点相等。
  CHECK(amount = round(quantity * unit_price, 4)),
  -- S2 回款不得超过销售额（多回的部分是预收，不是回款）。
  CHECK(settled_amount IS NULL OR settled_amount <= amount)
);

-- ---------------------------------------------------------------------------
-- 5. dealer_rebate —— 返点计提台账（输出③）
-- ---------------------------------------------------------------------------
-- ⚠️ **口径未与财务确认**：蓝图原文写死「经销返点是应收外的负债性承诺，
-- 需与财务确认计提口径」。12 应付/返点**未交付**，因此本表的计提口径是
-- **本模块自己的口径**，不是财务已确认口径。finance_confirmed 默认 false
-- 并在界面上如实显示，让看的人不会把这一列当成财务已认可的负债金额。
--
-- rebate_rate 是**计提时刻的快照**，不是引用授权上的当前值：
-- 授权的返点比例改了，已经计提过的期间不能跟着变（那会让历史负债金额漂移）。
--
-- base_amount = 期间内该授权的 Σ 销售金额，由服务层算出并落库
-- （不是视图/生成列）：计提是一次**动作**，动作的结果必须被冻结下来，
-- 而不是每次打开页面重算一遍。
CREATE TABLE dealer_rebate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  authorization_id uuid NOT NULL,
  dealer_profile_id uuid NOT NULL,
  period_start date NOT NULL,
  period_end date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 计提时点的返点比例快照。
  rebate_rate numeric(9,6) NOT NULL CHECK(rebate_rate >= 0 AND rebate_rate <= 1),
  -- 计提基数 = 期间内 Σ 销售金额。
  base_amount numeric(24,4) NOT NULL CHECK(base_amount >= 0),
  -- 返点金额 = base_amount × rebate_rate，**服务层用 BigDecimal 算**，
  -- DB 这条 CHECK 独立复算一次（与 S1 同一手法：算错时提交被挡下）。
  rebate_amount numeric(24,4) NOT NULL CHECK(rebate_amount >= 0),
  accrual_state text NOT NULL DEFAULT 'ACCRUED'
    CHECK(accrual_state IN ('ACCRUED','SETTLED')),
  -- 口径确认状态：false = 本模块口径，**尚未**经财务确认。
  finance_confirmed boolean NOT NULL DEFAULT false,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, authorization_id, period_start),
  FOREIGN KEY(tenant_id, authorization_id)
    REFERENCES dealer_authorization(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, dealer_profile_id) REFERENCES dealer_profile(tenant_id, id),
  CHECK(period_end >= period_start),
  -- R1 计提金额必须等于基数 × 比例（舍到金额刻度 4 位）。
  CHECK(rebate_amount = round(base_amount * rebate_rate, 4))
);

-- ---------------------------------------------------------------------------
-- 6. dealer_diversion_alert —— 窜货预警（输出④）
-- ---------------------------------------------------------------------------
-- 判定基于**本模块自己的两张表**：授权的 region_codes / product_line_codes
-- 对上 dealer_sale 的 sold_region_code / product_line_code。
--
-- ⚠️ 蓝图那句「窜货预警可复用 5.5.2 的站点价差倒挂逻辑」是**给未来的建议，
-- 不是现成依赖** —— 5.5.2 未交付，因此本表**没有**价格倒挂维度的判定，
-- 只做「授权范围越界」这一个维度。该口径未与 5.5.2 合并，
-- 在 /trade/dealers/capabilities 里显式报 available=false。
--
-- 一条销售最多产生一条预警（UNIQUE(tenant_id, dealer_sale_id)）：
-- 同一笔单既跨区域又跨产品线时合并成一条并记两个越界维度，
-- 拆成两条会让「有几笔窜货」这个最该被盯的数被算大。
CREATE TABLE dealer_diversion_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  dealer_profile_id uuid NOT NULL,
  authorization_id uuid NOT NULL,
  dealer_sale_id uuid NOT NULL,
  -- 越界维度：REGION（卖了未授权区域）/ PRODUCT_LINE（卖了未授权产品线）/ BOTH。
  breach_kind text NOT NULL CHECK(breach_kind IN ('REGION','PRODUCT_LINE','BOTH')),
  -- 快照：预警要能独立回答「当时授权了什么」，授权后来改了也不能把证据改掉。
  authorized_regions text[] NOT NULL,
  authorized_product_lines text[] NOT NULL,
  sold_region_code varchar(64) NOT NULL,
  product_line_code varchar(64) NOT NULL,
  sale_amount numeric(24,4) NOT NULL CHECK(sale_amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  severity text NOT NULL DEFAULT 'MEDIUM'
    CHECK(severity IN ('LOW','MEDIUM','HIGH')),
  alert_state text NOT NULL DEFAULT 'OPEN'
    CHECK(alert_state IN ('OPEN','ACKNOWLEDGED','DISMISSED')),
  note varchar(500) NOT NULL DEFAULT '',
  detected_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, dealer_sale_id),
  FOREIGN KEY(tenant_id, dealer_profile_id) REFERENCES dealer_profile(tenant_id, id),
  FOREIGN KEY(tenant_id, authorization_id)
    REFERENCES dealer_authorization(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, dealer_sale_id) REFERENCES dealer_sale(tenant_id, id),
  -- D1 判为误报必须写明理由（否则「 dismissed 」与「查过了确实没窜」无法区分）。
  CHECK(alert_state <> 'DISMISSED' OR btrim(note) <> '')
);

-- ===========================================================================
-- 第四节：索引
-- ===========================================================================
CREATE INDEX dealer_profile_by_party ON dealer_profile(tenant_id, party_id);
CREATE INDEX dealer_authorization_by_dealer
  ON dealer_authorization(tenant_id, dealer_profile_id, valid_from desc, id);
CREATE INDEX dealer_authorization_by_status
  ON dealer_authorization(tenant_id, status, valid_to, id);
-- 窜货判定的取数顺序：按授权找期间内的销售。
CREATE INDEX dealer_sale_by_authorization
  ON dealer_sale(tenant_id, authorization_id, sold_on, id);
CREATE INDEX dealer_sale_by_region ON dealer_sale(tenant_id, sold_region_code, sold_on desc, id);
CREATE INDEX dealer_quota_by_period ON dealer_quota(tenant_id, period_start, period_end, id);
CREATE INDEX dealer_rebate_by_state ON dealer_rebate(tenant_id, accrual_state, period_start desc, id);
CREATE INDEX dealer_rebate_by_dealer
  ON dealer_rebate(tenant_id, dealer_profile_id, period_start desc, id);
CREATE INDEX dealer_diversion_alert_by_state
  ON dealer_diversion_alert(tenant_id, alert_state, detected_at desc, id);
CREATE INDEX dealer_diversion_alert_by_dealer
  ON dealer_diversion_alert(tenant_id, dealer_profile_id, detected_at desc, id);
CREATE INDEX dealer_authorization_event_by_auth
  ON dealer_authorization_event(tenant_id, authorization_id, occurred_at, id);

-- ===========================================================================
-- 第五节：多租户 —— RLS + 授权（与 trade V1 / V92 / V99 / V120 同一套写法）
-- ===========================================================================
-- 七张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它（AccessService.Access 来自已认证成员关系）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'dealer_profile','dealer_authorization','dealer_authorization_event',
    'dealer_quota','dealer_sale','dealer_rebate','dealer_diversion_alert'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  dealer_profile,dealer_authorization,dealer_authorization_event,
  dealer_quota,dealer_sale,dealer_rebate,dealer_diversion_alert TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  dealer_profile,dealer_authorization,dealer_authorization_event,
  dealer_quota,dealer_sale,dealer_rebate,dealer_diversion_alert TO starcon_app;

-- 「授权在前、收回在后」，顺序不能颠倒，否则下面这几条会被上面的 GRANT 覆盖回去：
-- 台账事件流与销售记录是 append-only 的合规举证凭据，收回改写权。
REVOKE UPDATE,DELETE ON dealer_authorization_event FROM trade_app;
REVOKE UPDATE,DELETE ON dealer_sale FROM trade_app;
-- 客户档案投影只有 upsert、从来没有业务上的删除路径，收回删除权。
REVOKE DELETE ON dealer_profile FROM trade_app;
-- 计提结果与预警判定是对账证据，**刻意不给 DELETE**：一条计提要么对要么错，
-- 错了的正确动作是冲销（新建一条 accrual_state 不同的记录）而不是抹掉。
REVOKE DELETE ON dealer_rebate,dealer_diversion_alert FROM trade_app;

-- ===========================================================================
-- 第六节：与上下游的边界（本文件明确划出，不越界）
-- ===========================================================================
--   * **3.6.1.2 客户角色档案（catalog V27/V30 已交付）**：只存逻辑 party_id +
--     trade 侧只读投影 dealer_profile。**不建跨库 FK、不跨库 JOIN、不回查对方表**。
--     服务层的 DealerPartyQueryPort 另有一条**授权只读 HTTP**路径
--     （GET /catalog/parties/{id}/customer-profile），取不到时**显式 503**，
--     绝不返回空集合冒充「这个租户一个经销商都没有」。
--   * **16 合规（授权）未交付**：授权的合规校验没有可读上游，
--     所以 compliance_state 服务层只写得出 UNVERIFIED，
--     且在 /trade/dealers/capabilities 里以 available=false + ownerModule 如实报出，
--     **不画成 VERIFIED 也不画成默认值**。
--   * **12 应付 / 返点未交付**：dealer_rebate 是**本模块自己的计提口径**，
--     finance_confirmed 恒为 false 且在界面上显示。
--     口径确认与核销（SETTLED 的写回）属于 12 财务的写入所有权，
--     本模块只提供 accrual_state 的**只读**呈现与本地流转。
--   * **5 定价未交付**：dealer_price 是本模块授权上的**快照**，
--     改价不影响已成立的授权合同。
--   * **5.5.2 站点价差倒挂未交付**：窜货判定**只做授权范围越界**一个维度，
--     没有价格倒挂维度，且该口径未与 5.5.2 合并 —— capabilities 里报 available=false。
--   * **13 分析未交付**：配额完成率与计提汇总在本模块内出，
--     13 交付后由它读 dealer_rebate / dealer_quota（同服务同库，不是跨库）。
