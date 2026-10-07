-- 3.6.2.1 供应商报价：供应商对 SKU 的报价 —— 供应商货号、采购单位、币种、MOQ、交期、
-- 有效期、阶梯价，支持同一供应商同一 SKU 多个报价版本与按租户优先级选择。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：维护供应商对 SKU 的报价：供应商货号、采购单位、币种、MOQ、交期、有效期、阶梯价，
--             支持同一供应商同一 SKU 多个报价版本与按租户优先级选择。
--   **输入**：报价录入/导入；有效期；优先级设置。
--   **输出**：有效报价列表（默认取优先级最高且在有效期内的版本）。
--   **关系**：上游 = 3.6.1.2；下游 = 06 采购（采购单带出价格）、05 定价（成本参考）、
--             13 利润（成本波动）。**同一优先级多条有效报价必须报错让用户选**，不能随机取一条。
--
-- ============================================================ 第一节：本表与 3.6.1.2 的 MOQ 不是一回事
--
-- V30 第八节点名了这件事，这里兑现：
--
--   `party_supplier_profile.moq_quantity` = 「这家供应商最少下多少」—— 供应商**级别**的默认起订门槛。
--   `supplier_quote.moq_quantity`          = 「这个 SKU 最少下多少」—— **SKU 级别**的起订门槛。
--
-- 两者口径不同、都不废：同一个供应商 A 订「螺丝刀」可以只订 2 件，订「整套工具箱」要订 50 箱。
-- 报价的 MOQ 更精确，所以 06 采购单<b>优先取报价上的 MOQ</b>，报价缺失时才退回档案级 MOQ。
-- 刻意**不**在报价表上建指向 party_supplier_profile 的外键：那会把「这条报价必须有一份档案」
-- 变成 DB 事实，而档案是 3.6.1.2 的写入所有者（本模块不得因为别人没建档案就写不进报价）。
-- 由服务层在读出口给出提示（"该供应商还没建角色档案，取不到账期与合作等级"）。
--
-- ============================================================ 第二节：为什么报价是独立实体而不是 (供应商,SKU) 上的可覆盖列
--
-- 蓝图说「支持同一供应商同一 SKU 多个报价版本」—— 这一句否掉了在 sku 上挂
-- `supplier_price numeric` 那种一行一价的做法：
--
--   `sku_supplier_price(tenant_id, sku_id, supplier_id, price)` 每次改价都是 UPDATE，
--   于是「上周的报价是多少」这个问题**没有答案**—— 不是查不到，是根本没存过。
--   06 采购单冻结的是下单那一刻的价格，而 13 利润要看的是成本波动，
--   两个都要回看"当时那版报价是什么"，单行表只能给出一个数，而那个数一定是最后一次改的。
--
-- 所以报价是**一版一行**：新建一版报价就是 INSERT 一行，旧版本原样留着（靠 valid_to 划区间）。
-- 报价不是凭证，但它是**带有效期的约定**，删掉一行就等于让历史单据失去解释能力
-- （与 V23 套装版本 / V25 BOM 版本「发布后不可变」同一个理由）。
--
-- ============================================================ 第三节：阶梯价为什么单独一张表
--
-- 阶梯价是「数量 → 单价」的映射。一张表里塞两个数组（数量数组 + 单价数组）看着省事，
-- 但那样就没法回答「买 137 个时是第几档」—— 要在应用层把两个数组对齐后再比较，
-- 而数组顺序在 DB 层没有任何保证（PostgreSQL 不保证 text[] 的元素顺序被保留在更新里）。
-- 更糟的是「两档数量相同」这种错位数据可以写进去，只有在按量取价时才炸。
--
-- 一行一档之后：
--   * 取价是 `min_quantity <= qty` 里取最大的一行 —— 一条 SQL，不依赖任何内存对齐；
--   * 「两档数量相同」由主键 (tenant_id,quote_id,min_quantity) 在 DB 层不可能发生；
--   * 「档位必须严格递增」由服务层判 + 写入前排序，不靠 DB 排序保证。
--
-- **阶梯是开口的（最后一档无上限）**，不是每档带上下界：
-- 带上下界就必须回答「买 137 个（超过最后一档上界）怎么办」，而真实采购里
-- 超量价格是**与供应商另议**的，不是一条能查出来的数据。开口阶梯的规则简单到不会错：
-- 落在哪一档就是哪一档；低于第一档（起订量之内）没有适用价，此时返回 applicableTier=null
-- 而不是编一个价出来。
--
-- ============================================================ 第四节：priority 的方向与"歧义必须报错"
--
-- priority：**1 = 首选**，数字越小越优先（取值 1~99）。不用"越大越优先"，
-- 因为 1 起的序号天然可读（首选 / 备选 / 应急），而 0 起的方向每次读都要想一下。
--
-- 有效报价 = 满足三条：status='ACTIVE'、valid_from <= at、valid_to 为空或 at <= valid_to。
-- at 缺省取库当前日期（调用方显式传 at 是回看历史某一刻的报价用）。
--
-- **蓝图点名的那条规则**：默认取优先级最高的那一版，但**同一优先级有多条有效报价时必须报错
-- 让用户选，不能随机取一条**。这条在本表里刻意**不**用 UNIQUE 约束实现：
--
--   * 用 UNIQUE(tenant_id, supplier_role_id, sku_id, priority) 会禁掉"两个供应商角色对同一 SKU
--     报同一个优先级但币种不同"这类合法行（1688 上同一卖家美元与人民币报价并存是常态），
--     更重要的是它把"歧义"从一个**必须由人解决的状态**变成一个**根本写不进来的状态**——
--     运营会以为"系统不允许重复优先级"，于是改填别的数字绕过，而真实的价格歧义还在。
--   * 歧义是**读出口**的事：`GET /catalog/supplier-quotes/effective` 返回全部有效报价并给每行标
--     selectionState（SELECTED / AMBIGUOUS / LOWER_PRIORITY），**不替用户挑**；
--     `.../effective/resolve`（06 采购带出价格走这个）在歧义时返回 409 并附候选清单。
--
-- 这就是"报错让用户选"的字面兑现：409 的 body 里带着并列的那几条，
-- 运营点其中一条把它的 priority 调低一位（1 → 2）之后歧义自然消失。

-- ============================================================ 第五节：supplier_quote

CREATE TABLE supplier_quote (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 指向 party_role(tenant_id,id) 的 **SUPPLIER 角色**（不是 party）：与 V30 同一个理由 ——
  -- 「报价属于哪个供应商身份」而不是「属于哪个主体」。同一家公司既是供应商又是客户时，
  -- 挂在角色上才不会和它的客户身份混在一起。
  -- FK 只保证"角色真实存在且在本租户内"；"它是 SUPPLIER 角色"由服务层在写入前判定（规则 R2）。
  supplier_role_id uuid NOT NULL,

  -- 报价的 SKU。FK 指向 sku(tenant_id,id)：UNIQUE(tenant_id,id) 是它真实存在的唯一列组。
  sku_id uuid NOT NULL,

  -- 供应商货号：供应商自己那边的编号（1688 的 offerId、工厂的 Part No）。
  -- **UNIQUE(供应商, 货号)**：一个供应商的同一个货号只能对应我们这一个 SKU。
  -- 放开这条会怎样：同一货号挂到两个 SKU 上，06 采购照货号建单时带出的价格
  -- 可能来自另一个 SKU —— 而页面上两行长得不一样（SKU 名不同），没有任何异常表现。
  supplier_sku_code varchar(64) NOT NULL,

  -- 采购单位：报价是「按这个单位报的」，不是按 SKU 的基础单位报的（工厂按箱报价、
  -- 我们按件收货，靠 sku_unit_conversion 换算，见 3.1.2.4）。
  -- 外键指向 unit_of_measure(tenant_id,code)：停用单位仍可引用，
  -- 因为历史报价与已冻结的采购单要继续显示（与 V30 的 moq_unit_code 同一取舍）。
  purchase_unit_code varchar(32) NOT NULL,

  -- 币种：ISO 4217 三位码，**每条报价一个币种**（刻意不逐档设币种 —— 见第六节）。
  -- 不建币种字典外键的理由与 V30 的 default_currency 相同：
  -- ISO 4217 是外部标准，币种表是 12 财务的资产，在 catalog 复制一份就等于造出第二个写入所有者。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- MOQ（起订量），SKU 级口径（第一节）。精度 numeric(19,6) 与 V30 的 moq_quantity、
  -- V8 的 quantity_per_base 同精度：存 6 位是不丢精度，展示位数取该单位 unit_of_measure.scale。
  -- 必填而不是可空：这个报价若没有起订门槛，它就不是一条可执行的采购承诺
  -- （"随时下单多少都行"在跨境采购里不存在），而空值在 06 建单时会退化成"不校验"，
  -- 那比直接报错危险。
  moq_quantity numeric(19,6) NOT NULL CHECK(moq_quantity > 0),

  -- 交期（天）：下单到到货的承诺天数。0 = 当日/即时发货（现货供应商），
  -- 而不是"没填"—— 与 V30 的 payment_terms_days=0 同一语义约定。
  -- 上限 365 挡住误填的 99999（那不是交期，那是把月填成了天）。
  lead_time_days int NOT NULL DEFAULT 0
    CHECK(lead_time_days >= 0 AND lead_time_days <= 365),

  -- 有效期区间。valid_to 可空 = 长期有效（这类供应商很多：常年协议价）。
  -- **区间是闭区间**（含两端）：valid_to 是"这一天仍然有效"的最后一天。
  -- 半开区间（valid_to 当天就失效）在人工维护的场景里几乎必然踩坑：
  -- 运营填 3/31 到期，系统在 3/31 那天突然不让用了，而界面上写的是 3/31。
  valid_from date NOT NULL,
  valid_to date,

  -- 优先级：1 = 首选，越小越优先（第四节）。DEFAULT 1 让"只报一个价"的常见情况可以省一次选择。
  priority int NOT NULL DEFAULT 1 CHECK(priority BETWEEN 1 AND 99),

  -- 状态：ACTIVE = 参与有效报价判定；DISABLED = 运营临时停用（断货、涨价未确认）。
  -- **不用"已过期"这个状态**：过期由 valid_to 与 at 比较得出（第五节的有效报价定义），
  -- 把"过期"存成一列就要一个触发器去维护它，而维护出来的时间与读出口的判定可能差一天。
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),

  -- 备注：贸易条款、含不含运费、汇率基准这类不能进枚举的约定。
  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 区间必须自洽：结束日不能早于开始日。DB 层兜一道 —— 绕过服务层直连写入
  -- 也不该写得出「3 月 1 日生效、2 月 1 日到期」这种永不生效却看起来合法的行。
  CHECK(valid_to IS NULL OR valid_to >= valid_from),
  -- 一个供应商的同一个货号只能对应我们一个 SKU（第五节）。
  UNIQUE(tenant_id, supplier_role_id, supplier_sku_code),
  -- 跨租户挂角色 / 挂 SKU 在 DB 层不可能发生：复合外键都带 tenant_id。
  FOREIGN KEY(tenant_id, supplier_role_id) REFERENCES party_role(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, sku_id) REFERENCES sku(tenant_id, id),
  FOREIGN KEY(tenant_id, purchase_unit_code) REFERENCES unit_of_measure(tenant_id, code)
);

-- 「这个 SKU 的有效报价有哪些、按优先级排」—— 蓝图点名的输出（有效报价列表）的**主**查询。
-- 列顺序按选择路径排：先 sku_id 定位，再 priority 升序（越小越优先），日期区间最后过滤。
-- 06 采购单带出价格、05 定价取成本参考、13 利润看成本波动，三条读路径共用这一条。
CREATE INDEX supplier_quote_by_sku_priority
  ON supplier_quote(tenant_id, sku_id, priority, valid_from, valid_to);

-- 「这个供应商报了哪些价」（报价台账按供应商筛选；同一 SKU 多版本的浏览视图）。
CREATE INDEX supplier_quote_by_supplier
  ON supplier_quote(tenant_id, supplier_role_id, priority);

-- 「哪些报价快到期了」—— 报价治理的主查询：供应商下个月要涨的价，
-- 运营必须**提前**重新询价，而不是等 valid_to 过了才发现没价可下。
-- 不建表达式索引（date + 30 那种）：查询侧不拼同样表达式时它会静默不被使用，且没人报错。
CREATE INDEX supplier_quote_by_expiry
  ON supplier_quote(tenant_id, valid_to, supplier_role_id);

-- 「这个币种下的报价有哪些」—— 05 定价按币种归集成本、12 财务按币种做汇兑差异。
CREATE INDEX supplier_quote_by_currency
  ON supplier_quote(tenant_id, currency, sku_id);

-- ============================================================ 第六节：supplier_quote_price_tier —— 阶梯价

CREATE TABLE supplier_quote_price_tier (
  tenant_id uuid NOT NULL,
  -- 指向 supplier_quote(tenant_id,id)：这一档属于哪条报价。
  -- ON DELETE CASCADE：报价行删掉，阶梯必须跟着删 —— 留下孤儿阶梯时，
  -- 按量取价的查询（join supplier_quote）读不到它，而单独读阶梯表的查询读得到，
  -- 两种读法给出**不一致的价格**，那是最难查的一类错。
  quote_id uuid NOT NULL,

  -- 本档的起订量（含）：数量 >= min_quantity 时适用本档。
  -- **主键的一部分** → 同一个报价里两档数量相同在 DB 层不可能（第三节）。
  min_quantity numeric(19,6) NOT NULL CHECK(min_quantity > 0),

  -- 单价：每 min_quantity 个 purchase_unit_code 的价格。
  -- numeric(19,4) 与 V30 的 default_credit_limit 同一精度（4 位小数）：
  -- 跨境采购的单价常有厘级精度（0.0235 USD/个），2 位小数会把它们全部截成 0.02，
  -- 而 13 利润算出来的毛利率会因此偏掉几个点，且**没有任何报错**。
  -- 禁浮点：AGENTS.md 要求金额一律 BigDecimal + NUMERIC。
  unit_price numeric(19,4) NOT NULL CHECK(unit_price > 0),

  note text NOT NULL DEFAULT '',

  PRIMARY KEY(tenant_id, quote_id, min_quantity),
  FOREIGN KEY(tenant_id, quote_id) REFERENCES supplier_quote(tenant_id, id) ON DELETE CASCADE
);

-- 「按数量取价」的主查询：给定 quote_id 与 qty，取 min_quantity <= qty 里最大的一档。
-- 前导列就是 (tenant_id, quote_id)（主键本身），这条索引补上 min_quantity 的顺序扫描，
-- 让 ORDER BY min_quantity DESC LIMIT 1 走上索引而不是对整条报价的阶梯做排序。
CREATE INDEX supplier_quote_price_tier_by_quantity
  ON supplier_quote_price_tier(tenant_id, quote_id, min_quantity DESC);

-- ============================================================ 第七节：币种同口径（"阶梯价同口径"的落点）
--
-- 阶梯的 unit_price **没有 currency 列** —— 币种只在报价行上，且同一报价的全部阶梯同币种。
-- 逐档设币种会造出这些行：min_quantity=100 的档是 USD、min_quantity=500 的档是 EUR。
-- 在 06 采购单上它无法判定该用哪个币种去和供应商结算，而界面必须让用户挑一个，
-- 挑错了没有任何东西会报错。把币种绑在报价上之后这个状态**不存在**。
-- 服务层在建 / 改报价时要求至少一档（规则 R5），所以"只有报价没有阶梯"的行也写不进来。

-- ============================================================ 第八节：RLS

-- 两张表都是租户级主数据（与 party / sku / party_supplier_profile 同级）：
-- 只需要租户隔离，可见性由端点层权限把关。
-- 照抄 V30 第六节的 DO $$ FOREACH 写法：两张表的策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的报价 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['supplier_quote','supplier_quote_price_tier'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON supplier_quote TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON supplier_quote TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON supplier_quote_price_tier TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON supplier_quote_price_tier TO starcon_app;

-- ============================================================ 第九节：本迁移钉死的七条语义（服务层 SupplierQuoteRules 的对应实现）
--
-- 写在这里是因为「数据库为什么这样设计」与「服务层为什么这么判」必须是同一份承诺，
-- 两处各说一套的话，下一个改代码的人只会读到其中一处。
--
--   **R1 报价挂角色，且角色必须是 SUPPLIER。** 键在 supplier_role_id 上而不是 party_id 上
--       （与 V30 第二节同一个理由）。role_type 由服务层在写入前判定，不匹配给 409
--       「这个主体的供应商身份尚未指派」；DB 外键负责"角色存在且在本租户内"。
--       ON DELETE CASCADE 保证解指派供应商身份时报价一起清掉 —— 留下的话，
--       06 采购会带出一家已不再是供应商的报价。
--
--   **R2 优先级越小越优先；同优先级多条有效报价一律报错，不许自动挑。** 见第四节。
--       读出口给每条有效报价标 selectionState，严格取价端点在歧义时返回 409 + 候选清单。
--
--   **R3 币种是 ISO 4217 三位大写码；阶梯与报价同币种。** 第七节。
--       刻意不建币种字典外键（12 财务的写入所有权），只做格式校验。
--
--   **R4 MOQ 与交期必填。** MOQ > 0（报价必须有起订门槛），交期 >= 0 天（0 = 当日发货）。
--       缺了 MOQ 的报价在 06 建单时会退化成"不校验起订量"，而那比报错危险。
--
--   **R5 阶梯至少一档，且起订量严格递增、无重复。** 无阶梯的报价取不出价格，
--       06 采购单会带出一个空单价 —— 那比"这条报价不可用"更难发现。
--
--   **R6 报价可删，但单价不可由本模块改写历史。** 删掉一条报价之后 06 退回"该 SKU 无有效报价"
--       （建单时报错要求重新询价），这是一个**有意义且可逆**的状态。
--       已发生的采购单带的是自己的冻结快照，不读本表（与 3.6.3.1 地址同一口径）。
--
--   **R7 枚举只增不改。** status 只有 ACTIVE / DISABLED 两个值，
--       它们已经写进了历史采购单的冻结快照，改语义会让那些单据在回看时解释错。
--
-- ============================================================ 第十节：留给下游的口子（不是本模块的缺口）
--
--   * **06 采购单**：带出价格走 {@code GET /catalog/supplier-quotes/effective/resolve}
--     （skuId + supplierId + 可选 quantity 与 at）。歧义时它返回 409 与候选清单，
--     建单页据此要求人工选择 —— 这一条就是蓝图点名的"必须报错让用户选"。
--     单据侧把价格与阶梯**冻结**成自己的快照，改报价不影响已发生单据。
--   * **05 定价**：按 SKU 读全部有效报价（不指定供应商）做成本参考与毛利下限。
--   * **13 利润**：按 {@code valid_from} 分期统计成本波动。
--   * **3.6.1.2 角色档案**：本表**不**引用 party_supplier_profile（第一节）；
--     反向读出口"该供应商还没建角色档案"由服务层在读报价时给提示。
--   * **3.8.1.1 导入向导**：{@code import_job.target_entity} 的 CHECK **有意不含 QUOTE**
--     （那是 3.8.1.1 交付时钉死的边界，见台账该行）。本模块的"录入"路径是手工与 API 录入。
