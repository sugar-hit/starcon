-- 10.5.1.3 关税与税费。
--
-- 蓝图 10-logistics-and-freight.md 10.5.1.3 原文：
--   职责：计算与管理进口税费：关税（按 HS 编码税率）、增值税/GST、关税承担方（DDP/DDU）、
--         到岸成本构成，供成本核算与报价参考。
--   输入：HS 编码税率表；目的国税则；申报价值；贸易条款。
--   输出：税费估算 + 实际缴纳记录；到岸成本明细。
--   关系：上游 = 10.5.1.1、16 合规（税则）；下游 = 5.2.1.2 到岸成本、12 财务。
--         **税费估算与实际缴纳差异要记录**，长期差异大说明 HS 归类或税率配置有误。
--
-- ===========================================================================
-- 一、本迁移**只新建表，不 ALTER 任何既有表** —— 并行车道的零回归面
-- ===========================================================================
-- 同服务的 10.5.1.1（V85 报关单六表）与 10.4.1.1（V80）都由别的车道交付，
-- 本迁移对它们**只读**：不 ADD COLUMN、不 SET NOT NULL、不加 CHECK、不碰它们的触发器。
-- 复合外键指向 customs_declaration / customs_declaration_line 是**本服务**的表，
-- 与 V63 对 inventory_lot、V80 对 goods_receipt_event_line、V85 对 first_mile_shipment_line
-- 同一条纪律。跨车道回归面 = 0。
--
-- ===========================================================================
-- 二、**估算与实缴分别留痕，这是蓝图加粗的硬要求**
-- ===========================================================================
-- 蓝图原文：「**税费估算与实际缴纳差异要记录**，长期差异大说明 HS 归类或税率配置有误」。
-- 「要记录」意味着不能只存最终一个数 —— 只存一个数的话，差异就永远无法被回答。
-- 所以本迁移分三张表：
--   duty_tax_estimate     税费估算（按 HS 税率表算出来的**应有**税额，带完整计算依据快照）
--   duty_tax_payment      实际缴纳记录（海关/报关行给的**实收**税额，append-only）
--   duty_tax_variance     估算与实缴的**差异**（由前两者算出来的差，带归因与复核痕迹）
-- 三张表互相不覆盖：估算改了不影响已录的实缴，实缴补录不影响已定的估算。
--
-- 差异之所以必须单独成表而不是在 payment 上加两个列：归因（HS 归类错 / 税率配置错 /
-- 申报价值错 / 汇率差 / 海关加收）是**独立的人工判断**，有复核人和复核时间，
-- 而且蓝图点名它是「长期差异大说明 HS 归类或税率配置有误」的判据 ——
-- 也就是说这张表是**纠错闭环的载体**，不是一个可以随时重算的派生值。
--
-- duty_tax_variance 同样 append-only：已归因的差异被改掉，「我们从哪一次差异里
-- 学会了 HS 归类要改」这条证据就没了。
--
-- ===========================================================================
-- 三、税率表是**真实落库的数据**，不是代码里的常量
-- ===========================================================================
-- 蓝图输入写的是「HS 编码税率表；目的国税则」，所以 duty_tax_rate 是本模块的一等表，
-- 按（目的国 + HS 编码 + 生效期）检索，每条税率都带生效/失效日期 ——
-- 税则是会变的，用代码常量记税率等于「改了税率就改了历史」。
--
-- 税率用 numeric(9,6) 存**小数形式**（0.0325 = 3.25%），不是百分数：
-- 小数形式在乘算时不需要再除 100，而百分数形式一旦漏除就成了 100 倍的账。
-- CHECK(duty_rate<1) 钉死「小于 100%」—— 少数国家有超过 100% 从价税的复税税目，
-- 但那属于特殊裁定，本表显式拒绝而不是默默接受一个荒谬的税率。
--
-- 增值税/GST 同样按目的国税则走：vat_rate 用同一张表但 tax_kind='VAT'，
-- 因为 GST 0%（澳大利亚/英国）与免税额度是同一套机制的不同取值。
--
-- ===========================================================================
-- 四、16 合规（税则）与 3.7.1.1（HS 编码字典）上游未交付 —— 如实标注，不编造
-- ===========================================================================
-- 本车道对集成头实查：backend/services/ 下只有 catalog / channel / identity /
-- trade / warehouse，**没有 product_compliance**；台账里 3.7.1.1 是「阻塞」，
-- 16.3.1.1 税号与辖区、16.3.2.1 VAT/IOSS 都是「未开发」。因此：
--   * 税率表由**本租户**维护（source_system / source_reference 如实标注录入方式），
--     不假装存在一个国家海关的官方税率库 —— 本项目里没有可调用的官方税则接口；
--   * 每条税率带 tax_source（'MANUAL' / 'MODULE_16_3_1_1'）与 verified 标记，
--     把「这条税率是人工查来的还是合规模块给的」如实记下来；
--   * 16.3.1.1 交付后由它写 tax_source='MODULE_16_3_1_1'，**不需要改结构**。
--
-- ===========================================================================
-- 五、关税承担方（DDP/DDU）改变**到岸成本归属**，不是改变税率
-- ===========================================================================
-- 蓝图职责里点名「关税承担方（DDP/DDU）」。DDP 下卖方付关税与目的国税费，
-- 所以到岸成本里要算进去；DDU 下买方付，卖方的到岸成本里**不能**算 ——
-- 把它算进去就是在给一个不属于自己的成本记账。
--
-- 所以 estimate 里 duty_bearer + incoterm 一起定，而 landed_cost_* 四个金额
-- 由延迟约束触发器钉死自洽：DDU 时 landed_cost_seller 不得含关税与目的国税费。
-- 这不是「算一次就存下来」，是**每次重算都必须自洽**的 DB 级不变量。
--
-- 10.4.1.1 的 first_mile_shipment 已有 incoterm / risk_bearer 且已有
-- 「EXW⟹BUYER / DDP⟹SELLER」的 CHECK，本表沿用同一组取值口径（DDU 与 DDU 一致），
-- 并对 estimate 建**复合外键**到报关单、再由触发器把报关单的 incoterm 抄过来，
-- 保证「算关税用的贸易条款」与「报关时申报的贸易条款」不会各说各话。
--
-- ===========================================================================
-- 六、金额全程 numeric + 显式 HALF_UP，禁浮点
-- ===========================================================================
-- 金额 numeric(19,4)，税率 numeric(9,6)，数量 numeric(19,6)（沿用 V85 / V80 口径）。
-- 结算口径在 SQL 侧用 round(x, 2) 显式指定（关税/增值税报到分），
-- 落库前 Java 侧同样 setScale(2, HALF_UP) —— 两处口径一致，
-- 且 delay 约束触发器按同一口径复核，任何一侧漂移都会在提交时报错。
--
-- 精度刻度汇率（exchange_rate + scale + date）沿用 V85 的快照纪律：
-- 到岸成本是报价依据，事后要能回答「当时按哪个汇率算的」。

CREATE SEQUENCE duty_tax_estimate_code_seq START 1;

-- ---------------------------------------------------------------------------
-- 1. 税则税率表（蓝图输入：HS 编码税率表 / 目的国税则）
-- ---------------------------------------------------------------------------
-- 一条税率 = （目的国 + HS 编码 + 税种 + 生效期）唯一。
-- HS 编码按**前缀**匹配：真实税则是一棵树（章 → 品目 → 子目），
-- 精确到 8 位子目的税率往往查不到，查的是章级或品目级的从价税率。
-- 所以存 hs_code_prefix 并允许它比 8 位短，匹配时取**最长前缀**那条 ——
-- 这与海关实际归类规则一致（越具体的税率优先）。
CREATE TABLE duty_tax_rate (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 目的国税则：税率按目的国取，同一个 HS 编码在不同国家税率完全不同。
  destination_country_code varchar(2) NOT NULL,
  -- 税率适用范围：COUNTRY = 整个目的国一律适用（增值税/GST 就是这样：
  -- 绝大多数国家 VAT 按国征收、不按 HS 编码细分），HS = 按 HS 编码前缀适用（关税）。
  -- 这两种不能混在一张「HS 税率表」里硬塞：把国别增值税硬绑到某个 HS 码上，
  -- 结果是换一个 HS 码就换了一个增值税率 —— 那是凭空造出来的税率。
  rate_scope varchar(8) NOT NULL DEFAULT 'HS' CHECK(rate_scope IN ('COUNTRY','HS')),
  -- HS 编码前缀：2–10 位数字。允许短于 8 位表示章/品目级税率。
  -- COUNTRY 适用范围下必须为空：国别税率没有 HS 前缀可言。
  hs_code_prefix varchar(16) NOT NULL DEFAULT '',
  -- 税种：DUTY 关税 / VAT 增值税 / GST 商品服务税。
  -- 关税与增值税/GST 分行存，因为它们是**两笔独立的钱**：
  -- 合并成一个「综合税率」会让到岸成本明细答不出「哪部分是关税哪部分是税」。
  tax_kind varchar(8) NOT NULL CHECK(tax_kind IN ('DUTY','VAT','GST')),
  -- 从价税率，小数形式（0.0325 = 3.25%），不是百分数。见文件头第三节。
  ad_valorem_rate numeric(9,6) NOT NULL DEFAULT 0 CHECK(ad_valorem_rate>=0 AND ad_valorem_rate<1),
  -- 从量税率（本币/计量单位）。真实税则常见「从价或从量取高」，
  -- 保留这一列才能表达纯从量税目，而不是把从量税目错记成一个低从价税率。
  specific_rate_per_unit numeric(19,4) NOT NULL DEFAULT 0 CHECK(specific_rate_per_unit>=0),
  specific_uom varchar(8) NOT NULL DEFAULT 'PCS',
  -- 优惠税率（如 RCEP / 自贸协定）：蓝图 10.5.2.1 原文点名 FTA 优惠原产地证
  -- 能显著降低关税。本表**只负责按规则计算与留档**，适用性判断交给业务确认
  -- （蓝图原文：实质性改变等条件需要业务确认），所以这里有 preferential 标记
  -- 与 treaty_code 记录依据，但不自动判定。
  preferential boolean NOT NULL DEFAULT false,
  treaty_code varchar(32) NOT NULL DEFAULT '',
  -- 生效期：税则会变，**必须能按日期取当时的税率**，不能用「当前税率」算历史。
  effective_from date NOT NULL,
  effective_to date,
  -- 税率来源（见文件头第四节：16 合规未交付，税率由本租户维护）
  tax_source varchar(24) NOT NULL DEFAULT 'MANUAL' CHECK(tax_source IN ('MANUAL','MODULE_16_3_1_1')),
  tax_rate_verified boolean NOT NULL DEFAULT false,
  source_system varchar(32) NOT NULL DEFAULT 'MANUAL_ENTRY'
    CHECK(source_system IN ('MANUAL_ENTRY','TARIFF_FEED','COMPLIANCE_API')),
  source_reference varchar(64) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  -- 同一目的国 + 同一适用范围/HS 前缀 + 同一税种在同一生效日只能有一条：
  -- 否则「取当时税率」会随机命中其中一条，账算出来不可复现。
  UNIQUE(tenant_id,destination_country_code,rate_scope,hs_code_prefix,tax_kind,effective_from),
  CHECK(length(btrim(destination_country_code))=2),
  -- 适用范围与 HS 前缀必须自洽：国别税率没有 HS 前缀，HS 税率必须有前缀。
  CHECK((rate_scope='COUNTRY') = (hs_code_prefix='')),
  CHECK(rate_scope='COUNTRY' OR hs_code_prefix ~ '^[0-9]{2,10}$'),
  -- 注意这里**不**要求「至少一个税率 > 0」：0% 是真实且常见的税率
  -- （英国零税率商品、免税供货），把它禁掉等于表达不了最常见的合法配置。
  -- 「配了税率表但没匹配到」这个风险不靠这里的 CHECK 挡，而是靠
  -- duty_tax_estimate_line.rate_match_status=NO_MATCH 被显式暴露、
  -- 并禁止锁定（见第 11 节与 guard_duty_estimate_line_amounts）。
  CHECK(effective_to IS NULL OR effective_to>=effective_from),
  -- 税率必须能追到出处（哪份税则/公告查来的）：没有出处的税率无法复核，
  -- 而税率算错是要按海关核定补税的。
  CHECK(btrim(source_reference)<>''),
  -- 优惠税率必须有协定代码：没有依据的「优惠」就是错税率，按最惠国算才对。
  CHECK(preferential=false OR btrim(treaty_code)<>'')
);
CREATE INDEX duty_rate_lookup ON duty_tax_rate(tenant_id,destination_country_code,tax_kind,hs_code_prefix);
CREATE INDEX duty_rate_effective ON duty_tax_rate(tenant_id,destination_country_code,effective_from DESC);
CREATE INDEX duty_rate_prefix ON duty_tax_rate(tenant_id,hs_code_prefix);
CREATE INDEX duty_rate_treaty ON duty_tax_rate(tenant_id,treaty_code) WHERE preferential;

-- ---------------------------------------------------------------------------
-- 2. 税费估算单（蓝图输出：税费估算 + 到岸成本明细）
-- ---------------------------------------------------------------------------
-- 一张报关单在同一个目的国税则下只估一次税：UNIQUE(tenant_id,declaration_id) 钉死。
-- 重复估算会让「这次货到底该交多少税」有两个答案，而报价与成本核算都取其中一个。
-- 要改估算走状态机与版本（estimated → 锁定），不靠再造一张单。
--
-- 汇率与基准币是**快照**（沿用 V85 纪律）：到岸成本是报价依据，12 个月后
-- 复盘要能回答「当时按哪个汇率算的」。
--
-- 落地口径（delivered landed cost，DDP）：含货值 + 运费 + 保险 + 关税 + 目的国增值税/GST。
-- landed_cost_seller 是**卖方自己承担**的部分：DDP 含全部，DDU 不含关税与目的国税费。
CREATE TABLE duty_tax_estimate (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  estimate_code varchar(32) NOT NULL,
  -- ===== 上游：10.5.1.1 报关单（已交付 V85，同服务 → 复合外键）=====
  declaration_id uuid NOT NULL,
  declaration_code varchar(32) NOT NULL DEFAULT '',
  -- 贸易条款与关税承担方（见文件头第五节：改变到岸成本归属）
  incoterm varchar(8) NOT NULL CHECK(incoterm IN (
    'EXW','FCA','FOB','CFR','CIF','DDP','DDU')),
  duty_bearer varchar(16) NOT NULL CHECK(duty_bearer IN ('SELLER','BUYER','CARRIER')),
  legal_entity_id uuid NOT NULL,
  destination_country_code varchar(2) NOT NULL,
  -- ===== 计价与汇率快照 =====
  estimate_currency char(3) NOT NULL DEFAULT 'USD' CHECK(estimate_currency ~ '^[A-Z]{3}$'),
  base_currency char(3) NOT NULL DEFAULT 'CNY' CHECK(base_currency ~ '^[A-Z]{3}$'),
  exchange_rate numeric(20,10) NOT NULL DEFAULT 1 CHECK(exchange_rate>0),
  exchange_rate_scale integer NOT NULL DEFAULT 6 CHECK(exchange_rate_scale>=0 AND exchange_rate_scale<=10),
  exchange_rate_date date NOT NULL DEFAULT CURRENT_DATE,
  -- ===== 计价基础 =====
  -- 完税价格（customs value）：海关的计税基础，通常是 CIF 价。
  -- 单独存而不是从货值推：低报/高报完税价格是真实且常见的申报操作，
  -- 事后复核必须能答出「当时按哪个完税价格算的」。
  customs_value numeric(19,4) NOT NULL DEFAULT 0 CHECK(customs_value>=0),
  goods_value numeric(19,4) NOT NULL DEFAULT 0 CHECK(goods_value>=0),
  freight_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(freight_amount>=0),
  insurance_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(insurance_amount>=0),
  other_charge_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(other_charge_amount>=0),
  -- ===== 估算结果（按 HS 税率表算出，round 到 2 位）=====
  estimated_duty_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(estimated_duty_amount>=0),
  estimated_vat_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(estimated_vat_amount>=0),
  estimated_total_tax numeric(19,4) NOT NULL DEFAULT 0 CHECK(estimated_total_tax>=0),
  -- ===== 到岸成本构成（见文件头第五节）=====
  landed_cost_total numeric(19,4) NOT NULL DEFAULT 0,
  landed_cost_seller numeric(19,4) NOT NULL DEFAULT 0,
  -- ===== 状态机 =====
  -- DRAFT 草稿（可改可重算）→ ESTIMATED 已估算（可重算）→ LOCKED 已锁定（只读，可录实缴）
  --                    → VOID 已作废
  -- LOCKED 之后明细税率不允许再改：锁定后再改税率等于「先按错的税率报出去，再改成对的」。
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ESTIMATED','LOCKED','VOID')),
  estimated_at timestamptz,
  estimated_by uuid,
  locked_at timestamptz,
  locked_by uuid,
  voided_at timestamptz,
  void_reason varchar(200) NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,estimate_code),
  -- 一张报关单只估一次税（见本节开头）
  UNIQUE(tenant_id,declaration_id),
  FOREIGN KEY(tenant_id,declaration_id) REFERENCES customs_declaration(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),
  CHECK(btrim(estimate_code)<>''),
  CHECK(length(btrim(destination_country_code))=2),
  -- 与 10.4.1.1 同一组判据：DDP 卖方承担关税与目的国税费。
  CHECK(incoterm<>'DDP' OR duty_bearer='SELLER'),
  CHECK(incoterm<>'DDU' OR duty_bearer='BUYER'),
  -- 锁定必须留下时间与人：没有痕迹的「已锁定」等于没锁。
  CHECK(status<>'LOCKED' OR (locked_at IS NOT NULL AND locked_by IS NOT NULL)),
  CHECK(status<>'ESTIMATED' OR estimated_at IS NOT NULL)
);
CREATE INDEX duty_estimate_list ON duty_tax_estimate(tenant_id,status,updated_at DESC);
CREATE INDEX duty_estimate_declaration ON duty_tax_estimate(tenant_id,declaration_id);
CREATE INDEX duty_estimate_country ON duty_tax_estimate(tenant_id,destination_country_code,created_at DESC);
CREATE INDEX duty_estimate_bearer ON duty_tax_estimate(tenant_id,duty_bearer,incoterm);

-- ---------------------------------------------------------------------------
-- 3. 估算明细行（按 HS 编码逐行算税 —— 蓝图的「关税（按 HS 编码税率）」）
-- ---------------------------------------------------------------------------
-- 逐行而不是单头一个数：不同 HS 编码税率不同，合并算出来的「总关税」既无法
-- 归因也无法纠错。蓝图那句「长期差异大说明 HS 归类或税率配置有误」要能落到
-- **具体哪一行**的税率配错了，就必须是逐行的。
--
-- 对 customs_declaration_line 建复合外键（**本服务**的表）：
-- 保证「算税的这一行真的来自那张报关单、且与本租户一致」。
-- sku_id 属 catalog 服务 → **不建外键**（AGENTS.md 禁止跨服务 FK），只记快照。
--
-- 税率匹配：取（目的国 + 税种 + 生效日）下**最长前缀**匹配的那条税率。
-- matched_rate_id 记录命中了哪一条 —— 没有它，税率改了之后无法回答
-- 「当时是按哪条税率算的」，差异归因也就无从谈起。
CREATE TABLE duty_tax_estimate_line (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  estimate_id uuid NOT NULL,
  line_no integer NOT NULL CHECK(line_no>0),
  -- 来源报关明细行（10.5.1.1，本服务 → 复合外键）
  declaration_line_id uuid NOT NULL,
  source_line_no integer NOT NULL DEFAULT 0,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  -- HS 编码与原产地：算税的三个输入之一
  hs_code varchar(32) NOT NULL DEFAULT '',
  origin_country_code varchar(2) NOT NULL DEFAULT '',
  uom varchar(8) NOT NULL DEFAULT 'PCS',
  quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(quantity>=0),
  -- ===== 逐行计价基础 =====
  declared_value numeric(19,4) NOT NULL DEFAULT 0 CHECK(declared_value>=0),
  line_customs_value numeric(19,4) NOT NULL DEFAULT 0 CHECK(line_customs_value>=0),
  allocated_freight numeric(19,4) NOT NULL DEFAULT 0 CHECK(allocated_freight>=0),
  allocated_insurance numeric(19,4) NOT NULL DEFAULT 0 CHECK(allocated_insurance>=0),
  allocated_other_charge numeric(19,4) NOT NULL DEFAULT 0 CHECK(allocated_other_charge>=0),
  -- ===== 命中的税率（税率表 → 本服务 → 复合外键）=====
  -- null 表示**没有匹配到税率**：这类行必须被看到而不是静默按 0 税算过去，
  -- 所以 rate_match_status 区分 MATCHED / NO_MATCH / EXPIRED。
  duty_rate_id uuid,
  duty_ad_valorem_rate numeric(9,6),
  duty_specific_rate numeric(19,4) NOT NULL DEFAULT 0,
  duty_rate_prefix varchar(16) NOT NULL DEFAULT '',
  rate_match_status varchar(16) NOT NULL DEFAULT 'NO_MATCH' CHECK(rate_match_status IN (
    'MATCHED','NO_MATCH','EXPIRED')),
  -- 是否按优惠税率计算（FTA）：只记录依据，适用性由业务确认（见文件头第三节）
  preferential_applied boolean NOT NULL DEFAULT false,
  treaty_code varchar(32) NOT NULL DEFAULT '',
  -- ===== 逐行税额（round 到 2 位）=====
  estimated_duty_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(estimated_duty_amount>=0),
  -- 增值税/GST 计税基础 = 完税价格 + 关税（多数国家进口增值税对关税计征）
  vat_base_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(vat_base_amount>=0),
  vat_rate numeric(9,6) NOT NULL DEFAULT 0 CHECK(vat_rate>=0 AND vat_rate<1),
  -- 目的国增值税/GST 税率是否配到了。没配到就锁不了这张估算单（见本节末尾 CHECK）——
  -- 漏掉进口增值税的到岸成本会系统性偏低，而这种偏低在报价里看不出来。
  vat_rate_matched boolean NOT NULL DEFAULT false,
  estimated_vat_amount numeric(19,4) NOT NULL DEFAULT 0 CHECK(estimated_vat_amount>=0),
  estimated_line_total_tax numeric(19,4) NOT NULL DEFAULT 0 CHECK(estimated_line_total_tax>=0),
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,estimate_id,line_no),
  FOREIGN KEY(tenant_id,estimate_id) REFERENCES duty_tax_estimate(tenant_id,id),
  FOREIGN KEY(tenant_id,declaration_line_id) REFERENCES customs_declaration_line(tenant_id,id),
  FOREIGN KEY(tenant_id,duty_rate_id) REFERENCES duty_tax_rate(tenant_id,id),
  -- 命中了税率就必须把税率值抄下来（税率表那条将来可能失效或被改）：
  -- 「当时按 3.25% 算的」这句话必须能从本行自己读出来，不靠回查税率表。
  CHECK(rate_match_status<>'MATCHED' OR duty_rate_id IS NOT NULL),
  CHECK(rate_match_status<>'MATCHED' OR duty_ad_valorem_rate IS NOT NULL),
  -- 「按 HS 编码税率」这句职责的最小保证：没有 HS 码就不许声称命中了税率。
  -- 3.7.1.1 未交付时 hs_code 允许为空（沿用 V85 的自由码口径），
  -- 但那种行必须落在 NO_MATCH 上被看见，而不是按 0 税静默算过去。
  CHECK(rate_match_status<>'MATCHED' OR btrim(hs_code)<>''),
  -- 目的国增值税对关税计征，所以它的计税基础必须不小于完税价格。
  CHECK(vat_base_amount>=line_customs_value OR vat_rate=0),
  -- 目的国增值税/GST 也要配了才算得出来。没配 VAT 税率的行**同样不许锁定**：
  -- 到岸成本漏掉进口增值税，报出来的成本会系统性偏低，而这种偏低在报价里看不出来。
  -- 零税率国家（英国零税率商品、免税供货）配一条 0% 的国别税率行即可 ——
  -- 见本节上一条 CHECK 的注释：0% 合法，「没配」才不合法。
  CHECK(vat_rate_matched OR vat_rate=0)
);
CREATE INDEX duty_estimate_line_estimate ON duty_tax_estimate_line(tenant_id,estimate_id,line_no);
CREATE INDEX duty_estimate_line_declaration ON duty_tax_estimate_line(tenant_id,declaration_line_id);
CREATE INDEX duty_estimate_line_hs ON duty_tax_estimate_line(tenant_id,hs_code) WHERE btrim(hs_code)<>'';
-- 「长期差异大说明 HS 归类或税率配置有误」的查询入口：按 HS 编码聚合差异。
CREATE INDEX duty_estimate_line_unmatched ON duty_tax_estimate_line(tenant_id,estimate_id)
  WHERE rate_match_status<>'MATCHED';

-- ---------------------------------------------------------------------------
-- 4. 实际缴纳记录（蓝图输出：实际缴纳记录）—— append-only
-- ---------------------------------------------------------------------------
-- **append-only**：不允许 UPDATE / DELETE。缴过的税是向海关交钱的事实，
-- 改一笔缴款记录等于让一笔已经付出的钱凭空消失或凭空多出。
-- 缴错了（多缴要退、算错了要补）走**新增一条 kind='REFUND' / 'ADJUSTMENT' 的记录**，
-- 不改旧行 —— 这样「这票货一共缴了多少、其中退了多少」永远可加总。
--
-- 一条记录对应**一个税种**（DUTY / VAT / GST）：海关的缴款通知书也是分项的，
-- 合并记一笔就答不出「关税实缴多少」。
CREATE TABLE duty_tax_payment (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  estimate_id uuid NOT NULL,
  declaration_id uuid NOT NULL,
  -- 缴款条目：付款期次（第一次/第二次补缴…），UNIQUE 保证同一期次同税种只记一次
  payment_sequence integer NOT NULL DEFAULT 1 CHECK(payment_sequence>0),
  tax_kind varchar(8) NOT NULL CHECK(tax_kind IN ('DUTY','VAT','GST')),
  -- 缴款性质：正常缴纳 / 退汇（多缴退回）/ 调整（海关核定后补退）
  payment_kind varchar(16) NOT NULL DEFAULT 'PAYMENT' CHECK(payment_kind IN ('PAYMENT','REFUND','ADJUSTMENT')),
  paid_amount numeric(19,4) NOT NULL CHECK(paid_amount<>0),
  currency char(3) NOT NULL DEFAULT 'USD' CHECK(currency ~ '^[A-Z]{3}$'),
  -- 付款方：谁真的掏了这笔钱（DDP 卖方 / DDU 买方）
  payer varchar(16) NOT NULL CHECK(payer IN ('SELLER','BUYER','CARRIER','CUSTOMS')),
  -- 缴款凭证：海关缴款通知书号 / 银行流水号
  payment_reference varchar(64) NOT NULL,
  paid_at timestamptz NOT NULL,
  payer_name varchar(200) NOT NULL DEFAULT '',
  -- 来源标注（沿用 V85 纪律：外部海关缴款接口未交付，回执由人工回填并如实标注）
  source_system varchar(32) NOT NULL DEFAULT 'MANUAL_ENTRY'
    CHECK(source_system IN ('MANUAL_ENTRY','CUSTOMS_FEED','BROKER_API')),
  source_reference varchar(64) NOT NULL,
  notes text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一期次同税种只记一次：重复录入同一张缴款通知书就是双记钱。
  UNIQUE(tenant_id,estimate_id,tax_kind,payment_sequence),
  -- 同一张缴款凭证在本租户内唯一：同一张通知书贴两次等于把一笔钱记两次。
  UNIQUE(tenant_id,payment_reference),
  FOREIGN KEY(tenant_id,estimate_id) REFERENCES duty_tax_estimate(tenant_id,id),
  FOREIGN KEY(tenant_id,declaration_id) REFERENCES customs_declaration(tenant_id,id),
  -- 凭证必填且必非空：没有凭证号的缴款记录无法向审计举证。
  CHECK(btrim(payment_reference)<>''),
  CHECK(btrim(source_reference)<>'')
);
CREATE INDEX duty_payment_estimate ON duty_tax_payment(tenant_id,estimate_id,tax_kind,paid_at);
CREATE INDEX duty_payment_declaration ON duty_tax_payment(tenant_id,declaration_id);
CREATE INDEX duty_payment_paid_at ON duty_tax_payment(tenant_id,paid_at DESC);
CREATE INDEX duty_payment_reference ON duty_tax_payment(tenant_id,payment_reference);

-- ---------------------------------------------------------------------------
-- 5. 估算与实缴差异（蓝图加粗硬要求：差异要记录）—— append-only
-- ---------------------------------------------------------------------------
-- 这是本模块存在的核心理由。蓝图原文：
--   「**税费估算与实际缴纳差异要记录**，长期差异大说明 HS 归类或税率配置有误」
--
-- 三张表的关系：estimate 是「按税率表算出应该有」，
-- payment 是「海关实际收了多少」，本表是「两者差多少、为什么差、谁认了」。
--
-- 为什么不能只存最终一个数：只存一个数的话，
--   * 差异永远无法被回答（没法算「这个月估算与实缴差了多少」）；
--   * 「HS 归类错了」这个结论就没有证据支撑，只能靠感觉；
--   * 归因与复核是**人工判断**且带责任人，有复核痕迹 —— 不是派生值。
--
-- variance_amount = 实缴合计 - 估算额（正数 = 多缴，负数 = 少缴）。
-- 归因（variance_cause）按蓝图的判据设计：长期差异集中在 HS 归类或税率配置上。
CREATE TABLE duty_tax_variance (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  estimate_id uuid NOT NULL,
  -- 差异按**税种 + 报关明细行**留痕：归因要落到具体哪一行，
  -- 否则「HS 归类错了」这句话无法转成「把哪个 HS 码改掉」。
  tax_kind varchar(8) NOT NULL CHECK(tax_kind IN ('DUTY','VAT','GST')),
  -- 对账到第几次缴款：与 duty_tax_payment.payment_sequence 对齐。
  -- 实缴是逐次发生的，差异是逐次对出来的 —— 所以差异必须能回答
  -- 「第 2 次补缴之后，累计差了多少」，而不仅仅是「最终差了多少」。
  payment_sequence integer NOT NULL DEFAULT 1 CHECK(payment_sequence>0),
  declaration_line_id uuid,
  estimate_line_id uuid,
  estimated_amount numeric(19,4) NOT NULL DEFAULT 0,
  actual_amount numeric(19,4) NOT NULL DEFAULT 0,
  variance_amount numeric(19,4) NOT NULL DEFAULT 0,
  -- 差异率：分母是估算额。估算为 0 而实缴非 0 时**不设比率**（除零无意义），
  -- 用 rate_calculable=false 标记 —— 那正是「税率没配」最典型的表现。
  variance_rate numeric(12,6),
  rate_calculable boolean NOT NULL DEFAULT true,
  currency char(3) NOT NULL DEFAULT 'USD' CHECK(currency ~ '^[A-Z]{3}$'),
  -- 归因：这是纠错闭环的关键字段
  variance_cause varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(variance_cause IN (
    'PENDING','HS_CLASSIFICATION','RATE_CONFIG','CUSTOMS_VALUE','FX_RATE',
    'FTA_PREFERENCE','EXEMPTION','PENALTY_INTEREST','OTHER')),
  -- 归因备注与处理结果：改了 HS 码还是改了税率，要写清楚
  cause_note text NOT NULL DEFAULT '',
  resolution varchar(24) NOT NULL DEFAULT 'OPEN' CHECK(resolution IN (
    'OPEN','INVESTIGATING','FIXED_RATE_CONFIG','FIXED_HS_CLASSIFICATION','ACCEPTED','WRITTEN_OFF')),
  -- 复核痕迹：差异是人工判定的，所以必须留人留时间
  reviewed_at timestamptz,
  reviewed_by uuid,
  review_note text NOT NULL DEFAULT '',
  source_reference varchar(64) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- **一次缴款对账一个差异快照**：实缴是逐次发生的，差异是逐次对出来的。
  -- 第 1 次缴款留下「对到第 1 次为止差多少」，第 2 次再留一条新的 ——
  -- 既有约束保证同一笔缴款不会被对账两次（双记差异），
  -- 增量约束又保证补缴之后能看到「第一次对账时差多少」这个历史瞬间。
  UNIQUE(tenant_id,estimate_id,tax_kind,payment_sequence),
  FOREIGN KEY(tenant_id,estimate_id) REFERENCES duty_tax_estimate(tenant_id,id),
  FOREIGN KEY(tenant_id,declaration_line_id) REFERENCES customs_declaration_line(tenant_id,id),
  FOREIGN KEY(tenant_id,estimate_line_id) REFERENCES duty_tax_estimate_line(tenant_id,id),
  -- 汇率不可算时比率必须为 null；可算时必须有值。两个方向都钉死，
  -- 免得「差异率」这一列出现 NaN 或随手填的 0。
  CHECK((rate_calculable AND variance_rate IS NOT NULL) OR (NOT rate_calculable AND variance_rate IS NULL)),
  -- 有实缴没估算（rate_calculable=false）时，估算额必须是 0：
  -- 「没配税率所以估 0」与「估了一个非零数」是两种完全不同的账。
  CHECK(rate_calculable OR estimated_amount=0),
  -- 已复核的差异必须有复核人：OPEN 的差异才允许没有复核痕迹。
  CHECK(resolution='OPEN' OR reviewed_at IS NOT NULL)
);
CREATE INDEX duty_variance_estimate ON duty_tax_variance(tenant_id,estimate_id,tax_kind);
CREATE INDEX duty_variance_cause ON duty_tax_variance(tenant_id,variance_cause,created_at DESC);
CREATE INDEX duty_variance_open ON duty_tax_variance(tenant_id,created_at DESC)
  WHERE resolution='OPEN';
-- 蓝图那句「长期差异大说明 HS 归类或税率配置有误」的落地查询入口：
-- 按估算行反查它累计的差异（而不是扫全表）。
CREATE INDEX duty_variance_line ON duty_tax_variance(tenant_id,estimate_line_id)
  WHERE estimate_line_id IS NOT NULL;
CREATE INDEX duty_variance_resolution ON duty_tax_variance(tenant_id,resolution,created_at DESC);

-- ---------------------------------------------------------------------------
-- 6. 估算自洽：单头金额 = 明细金额合计（延迟约束触发器）
-- ---------------------------------------------------------------------------
-- 头与明细在同一个事务里先后写（即时触发器会对着半成品报错），必须 DEFERRABLE。
-- 三条钉死：
--   estimated_duty_amount  = 明细 estimated_duty_amount 合计
--   estimated_vat_amount   = 明细 estimated_vat_amount 合计
--   estimated_total_tax    = 关税 + 增值税/GST
-- 少一条就可能出现「单头总额与逐行明细对不上」—— 那时成本核算取哪个数都有理，
-- 而报价与财务必须取同一个数。
--
-- ⚠ 这里必须**回表读当前行**，不能直接用 NEW：延迟约束触发器把 NEW 固定在「事件发生那一刻」
-- 的行值上。服务层的写法是「先 INSERT 头（合计先落 0）→ 写明细 → UPDATE 头回填合计」，
-- 于是同一个事务里既触发了 INSERT 事件又触发了 UPDATE 事件；延迟到提交时，
-- INSERT 那一次仍然拿着合计=0 的旧 NEW 去核对明细（此时明细已经算完了），
-- 于是每次都报「单头 ≠ 明细合计」。回表读当前行才能拿到提交那一刻的真实值。
-- （V85 的 customs_rate_snapshot 用的就是同一个办法。）
CREATE FUNCTION guard_duty_estimate_totals() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_duty numeric(19,4); head_vat numeric(19,4); head_total numeric(19,4);
  duty_sum numeric(19,4); vat_sum numeric(19,4);
BEGIN
  SELECT estimated_duty_amount, estimated_vat_amount, estimated_total_tax
    INTO head_duty, head_vat, head_total
    FROM duty_tax_estimate WHERE id = NEW.id;
  SELECT coalesce(sum(estimated_duty_amount),0), coalesce(sum(estimated_vat_amount),0)
    INTO duty_sum, vat_sum
    FROM duty_tax_estimate_line WHERE estimate_id = NEW.id;
  IF head_duty <> duty_sum THEN
    RAISE EXCEPTION '估算单头关税 % ≠ 明细关税合计 %：逐行与单头必须对得上，否则成本核算与报价会各取一个数',
     head_duty, duty_sum USING ERRCODE='23514';
  END IF;
  IF head_vat <> vat_sum THEN
    RAISE EXCEPTION '估算单头增值税/GST % ≠ 明细合计 %：逐行与单头必须对得上', head_vat, vat_sum
     USING ERRCODE='23514';
  END IF;
  IF head_total <> round(duty_sum + vat_sum, 2) THEN
    RAISE EXCEPTION '估算税费合计 % ≠ round(关税 % + 增值税/GST %, 2) = %：合计口径必须是 HALF_UP 到分',
     head_total, duty_sum, vat_sum, round(duty_sum + vat_sum, 2) USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER duty_estimate_totals
 AFTER INSERT OR UPDATE ON duty_tax_estimate
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_duty_estimate_totals();

-- ---------------------------------------------------------------------------
-- 7. 逐行自洽：行内税额 = 该行各分项（延迟约束触发器）
-- ---------------------------------------------------------------------------
-- 单头对得上还不够：单行内部也要自洽，否则「这一行为什么算出这个税」
-- 答不出来，而归因正是要落到行上的。
--   estimated_line_total_tax = 关税 + 增值税/GST
--   增值税计税基础 ≥ 该行完税价格（有从价增值税时）
CREATE FUNCTION guard_duty_estimate_line_amounts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE line_total numeric(19,4); line_duty numeric(19,4); line_vat numeric(19,4);
  line_base numeric(19,4); line_customs numeric(19,4); line_vat_rate numeric(9,6);
BEGIN
  -- 同样回表读当前行（理由见上一节：延迟触发器里的 NEW 是事件发生那一刻的值）。
  SELECT estimated_line_total_tax, estimated_duty_amount, estimated_vat_amount,
         vat_base_amount, line_customs_value, vat_rate
    INTO line_total, line_duty, line_vat, line_base, line_customs, line_vat_rate
    FROM duty_tax_estimate_line WHERE id = NEW.id;
  IF line_total <> round(line_duty + line_vat, 2) THEN
    RAISE EXCEPTION '明细行税费合计 % ≠ round(关税 % + 增值税/GST %, 2) = %',
     line_total, line_duty, line_vat, round(line_duty + line_vat, 2) USING ERRCODE='23514';
  END IF;
  IF line_vat_rate>0 AND line_base < line_customs THEN
    RAISE EXCEPTION '明细行增值税计税基础 % < 该行完税价格 %：进口增值税对关税计征，计税基础不能低于完税价格',
     line_base, line_customs USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER duty_estimate_line_amounts
 AFTER INSERT OR UPDATE ON duty_tax_estimate_line
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_duty_estimate_line_amounts();

-- ---------------------------------------------------------------------------
-- 8. 到岸成本归属：DDU 时卖方到岸成本**不得**含关税与目的国税费
-- ---------------------------------------------------------------------------
-- 见文件头第五节。这条是「DDP/DDU 到底改变了什么」的唯一真相：
-- 改变的是**成本归属**，不是税率。
--
-- DDU 下：
--   landed_cost_total   = 货值 + 运费 + 保险 + 其他 + 关税 + 增值税/GST
--   landed_cost_seller  = 货值 + 运费 + 保险 + 其他（**不含**关税与税）
-- DDP / 其他承担方（CARRIER 等）下卖方成本含全部。
CREATE FUNCTION guard_duty_landed_cost() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE base_amount numeric(19,4); tax_total numeric(19,4);
  g_goods numeric(19,4); g_freight numeric(19,4); g_ins numeric(19,4); g_other numeric(19,4);
  g_duty numeric(19,4); g_vat numeric(19,4); g_total numeric(19,4); g_seller numeric(19,4);
  g_bearer varchar(16);
BEGIN
  -- 回表读当前行（理由见第 6 节：延迟触发器的 NEW 是事件发生那一刻的值）。
  SELECT goods_value, freight_amount, insurance_amount, other_charge_amount,
         estimated_duty_amount, estimated_vat_amount, landed_cost_total, landed_cost_seller,
         duty_bearer
    INTO g_goods, g_freight, g_ins, g_other, g_duty, g_vat, g_total, g_seller, g_bearer
    FROM duty_tax_estimate WHERE id = NEW.id;
  base_amount := g_goods + g_freight + g_ins + g_other;
  tax_total := g_duty + g_vat;
  IF g_total <> round(base_amount + tax_total, 2) THEN
    RAISE EXCEPTION '到岸成本合计 % ≠ 货值+运费+保险+其他 % + 税费 % = %',
     g_total, base_amount, tax_total, round(base_amount + tax_total, 2) USING ERRCODE='23514';
  END IF;
  IF g_bearer='BUYER' THEN
    IF g_seller <> round(base_amount, 2) THEN
      RAISE EXCEPTION 'DDU 下关税与目的国税费由买方承担，卖方到岸成本 % 必须等于不含税的货值+运费+保险+其他 %',
       g_seller, round(base_amount, 2) USING ERRCODE='23514';
    END IF;
  ELSE
    IF g_seller <> round(base_amount + tax_total, 2) THEN
      RAISE EXCEPTION '承担方为 % 时卖方到岸成本 % 必须含全部税费，应为 %',
       g_bearer, g_seller, round(base_amount + tax_total, 2) USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER duty_landed_cost
 AFTER INSERT OR UPDATE ON duty_tax_estimate
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_duty_landed_cost();

-- ---------------------------------------------------------------------------
-- 9. 缴款与差异必须同时存在 —— 把蓝图那句加粗钉进 DB
-- ---------------------------------------------------------------------------
-- 蓝图原文：「**税费估算与实际缴纳差异要记录**」。
-- 只用服务层 if 守不住：任何一处漏调、或者将来有人直接写 SQL 补一条缴款，
-- 就会出现「缴了税但没有差异记录」—— 那正是蓝图明确禁止的「只存最终一个数」。
--
-- 所以这里用**延迟约束触发器**在提交时核对：存在缴款却没有对应差异记录就报 23514。
-- 延迟是为了让「先写 payment 再算差异写 variance」能在同一个事务里完成。
-- 差异记录由服务层在同事务内按 (estimate_id, tax_kind) 汇总实缴后写入。
CREATE FUNCTION guard_duty_payment_has_variance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE recorded numeric(19,4);
BEGIN
  SELECT coalesce(sum(p.paid_amount),0) INTO recorded
    FROM duty_tax_payment p
   WHERE p.estimate_id = NEW.estimate_id AND p.tax_kind = NEW.tax_kind;
  -- 同一 (estimate_id, tax_kind) 已存在差异记录：正常路径，服务层已先写好。
  IF EXISTS (SELECT 1 FROM duty_tax_variance v
              WHERE v.estimate_id = NEW.estimate_id AND v.tax_kind = NEW.tax_kind) THEN
    RETURN NULL;
  END IF;
  RAISE EXCEPTION '估算单 % 的 % 实缴合计 % 没有对应的差异记录：蓝图要求「税费估算与实际缴纳差异要记录」，'
   '不允许只存最终一个数', NEW.estimate_id, NEW.tax_kind, recorded USING ERRCODE='23514';
END $$;
CREATE CONSTRAINT TRIGGER duty_payment_has_variance
 AFTER INSERT ON duty_tax_payment
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_duty_payment_has_variance();

-- ---------------------------------------------------------------------------
-- 10. 实际缴纳记录 append-only —— 缴过的钱不允许被改写
-- ---------------------------------------------------------------------------
-- 理由见第 4 节：缴错了走新增 REFUND / ADJUSTMENT 行，不改旧行。
CREATE FUNCTION guard_duty_payment_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'duty_tax_payment 是 append-only：不允许 %，缴款事实不可改写（多缴/错缴请新增 REFUND 或 ADJUSTMENT 记录）',
   TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER duty_payment_append_only
 BEFORE UPDATE OR DELETE ON duty_tax_payment
 FOR EACH ROW EXECUTE FUNCTION guard_duty_payment_append_only();

-- 差异记录同样 append-only：已归因的差异被改掉，
-- 「我们从哪一次差异里学会了 HS 归类要改」这条证据就没了。
CREATE FUNCTION guard_duty_variance_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'duty_tax_variance 是 append-only：不允许 %，已归因的差异是纠错闭环的证据，不可改写',
   TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER duty_variance_append_only
 BEFORE UPDATE OR DELETE ON duty_tax_variance
 FOR EACH ROW EXECUTE FUNCTION guard_duty_variance_append_only();

-- ---------------------------------------------------------------------------
-- 11. 锁定后不允许再改明细税率（否则等于「先按错的税率报出去再改成对的」）
-- ---------------------------------------------------------------------------
-- 锁定是「这个估算已经作为对外报价/成本依据发出去了」的标记。
-- 锁定之后再改行税率，历史报价就没有依据了 —— 要改只能作废重估。
CREATE FUNCTION guard_duty_estimate_line_lock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_status varchar(16);
BEGIN
  SELECT e.status INTO current_status
    FROM duty_tax_estimate e WHERE e.id = NEW.estimate_id;
  IF current_status IN ('LOCKED','VOID') THEN
    RAISE EXCEPTION '估算单处于「%」状态，不允许改明细税率：锁定后再改税率等于让已发出的报价失去依据，要改请作废重估',
     current_status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER duty_estimate_line_lock
 BEFORE INSERT OR UPDATE ON duty_tax_estimate_line
 FOR EACH ROW EXECUTE FUNCTION guard_duty_estimate_line_lock();

-- 锁定后也不允许改单头的基础金额（完税价格/运费/保险/承担方）。
-- 承担方尤其不能改：改了就等于把 DDP 的成本挪成 DDU 的成本。
CREATE FUNCTION guard_duty_estimate_lock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_status varchar(16);
BEGIN
  SELECT status INTO old_status FROM duty_tax_estimate WHERE id = OLD.id;
  IF old_status IN ('LOCKED','VOID') THEN
    IF NEW.duty_bearer <> OLD.duty_bearer OR NEW.incoterm <> OLD.incoterm
       OR NEW.customs_value <> OLD.customs_value OR NEW.goods_value <> OLD.goods_value
       OR NEW.freight_amount <> OLD.freight_amount OR NEW.insurance_amount <> OLD.insurance_amount
       OR NEW.legal_entity_id <> OLD.legal_entity_id OR NEW.exchange_rate <> OLD.exchange_rate THEN
      RAISE EXCEPTION '估算单已锁定，不允许改承担方/贸易条款/完税价格/成本项/汇率：锁定后再改等于让已发出的报价失去依据，要改请作废重估'
       USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER duty_estimate_lock
 BEFORE UPDATE ON duty_tax_estimate
 FOR EACH ROW EXECUTE FUNCTION guard_duty_estimate_lock();

-- ---------------------------------------------------------------------------
-- 11b. **没匹配到税率的估算单不允许锁定**（把「税率没配」这个风险真正堵住）
-- ---------------------------------------------------------------------------
-- 上一节把「至少一个税率 > 0」这条 CHECK 撤掉了，因为 0% 税率（英国零税率、
-- 免税供货）是真实且常见的合法配置，禁掉它等于表达不了最常见的配置。
-- 但这样「税率表没配 / 没匹配上」就变成了一条**可以合法存在**的估算单。
-- 如果它还能被锁定，那么这票货就会以「关税 0 元」进入报价与成本核算 ——
-- 这正是「HS 归类或税率配置有误」在账上最常见的表现形态。
--
-- 所以改在**锁定**这一动作上堵：只要还有任何一行没匹配到税率，就锁不了。
-- 锁定是「这个估算要作为对外报价/成本依据发出去」的标记，
-- 把关卡放在这一步比在税率表上禁 0 率更准确 ——
-- 0 率本身合法，没配税率不合法。
CREATE FUNCTION guard_duty_estimate_lock_completeness() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE unmatched integer; missing_hs integer; missing_vat integer;
BEGIN
  IF NEW.status='LOCKED' AND (TG_OP='INSERT' OR OLD.status<>'LOCKED') THEN
    SELECT count(*) INTO unmatched FROM duty_tax_estimate_line
     WHERE estimate_id=NEW.id AND rate_match_status<>'MATCHED';
    IF unmatched>0 THEN
      RAISE EXCEPTION '估算单还有 % 行没有匹配到关税税率，不允许锁定：按 0 税锁定的估算单会让报价与成本核算少算关税，而这正是「HS 归类或税率配置有误」在账上最常见的表现。请先补税率表或修正 HS 归类',
       unmatched USING ERRCODE='23514';
    END IF;
    -- 没有 HS 编码的行同样不能锁：算不出按 HS 编码的关税，只能按 0。
    SELECT count(*) INTO missing_hs FROM duty_tax_estimate_line
     WHERE estimate_id=NEW.id AND btrim(hs_code)='';
    IF missing_hs>0 THEN
      RAISE EXCEPTION '估算单还有 % 行没有 HS 编码，不允许锁定：蓝图的关税是「按 HS 编码税率」算的，没有 HS 码的行只能按 0 税进账',
       missing_hs USING ERRCODE='23514';
    END IF;
    -- 没配到目的国增值税/GST 税率的行同样不能锁：漏掉进口增值税的到岸成本
    -- 会系统性偏低，而这种偏低在报价与成本核算里都看不出来。
    SELECT count(*) INTO missing_vat FROM duty_tax_estimate_line
     WHERE estimate_id=NEW.id AND NOT vat_rate_matched;
    IF missing_vat>0 THEN
      RAISE EXCEPTION '估算单还有 % 行没配到目的国增值税/GST 税率，不允许锁定：漏掉进口增值税的到岸成本会系统性偏低。请为该目的国补一条国别税率（零税率国家配 0%% 即可）',
       missing_vat USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER duty_estimate_lock_completeness
 AFTER INSERT OR UPDATE ON duty_tax_estimate
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_duty_estimate_lock_completeness();

-- ---------------------------------------------------------------------------
-- 12. 作废后不允许再写任何东西
-- ---------------------------------------------------------------------------
-- VOID 是终态。作废的估算单如果还能录缴款，那这票货就同时存在
-- 「已作废的估算」与「真实的缴款」，账上必然对不上。
CREATE FUNCTION guard_duty_estimate_not_void() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_status varchar(16);
BEGIN
  SELECT e.status INTO current_status
    FROM duty_tax_estimate e WHERE e.id = NEW.estimate_id;
  IF current_status='VOID' THEN
    RAISE EXCEPTION '估算单已作废，不允许 %：作废的估算单上不能再写缴款或差异', TG_OP
     USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER duty_payment_not_void
 BEFORE INSERT ON duty_tax_payment
 FOR EACH ROW EXECUTE FUNCTION guard_duty_estimate_not_void();
CREATE TRIGGER duty_variance_not_void
 BEFORE INSERT ON duty_tax_variance
 FOR EACH ROW EXECUTE FUNCTION guard_duty_estimate_not_void();

-- ---------------------------------------------------------------------------
-- 13. 缴款必须记在对应报关单上，且税种要与估算明细对得上
-- ---------------------------------------------------------------------------
-- 防止「把 A 报关单的缴款记到 B 报关单的估算单上」——
-- 这在账上完全看不出来（两边都合法），但会让差异分析得出错误结论。
CREATE FUNCTION guard_duty_payment_declaration() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE est_declaration uuid;
BEGIN
  SELECT e.declaration_id INTO est_declaration
    FROM duty_tax_estimate e WHERE e.id = NEW.estimate_id;
  IF est_declaration IS NULL OR est_declaration <> NEW.declaration_id THEN
    RAISE EXCEPTION '缴款记录的报关单 % 与估算单 % 的报关单 % 不是同一张：把缴款记到另一张报关单上会让差异分析得出错误结论',
     NEW.declaration_id, NEW.estimate_id, est_declaration USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER duty_payment_declaration
 BEFORE INSERT ON duty_tax_payment
 FOR EACH ROW EXECUTE FUNCTION guard_duty_payment_declaration();

-- ---------------------------------------------------------------------------
-- 14. 多租户：RLS + 授权（与 V1 第 9 节、V63 第 5 节、V80 第 7 节、V85 第 9 节同一条纪律）
-- ---------------------------------------------------------------------------
-- 五张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'duty_tax_rate','duty_tax_estimate','duty_tax_estimate_line',
   'duty_tax_payment','duty_tax_variance'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON duty_tax_rate, duty_tax_estimate, duty_tax_estimate_line,
  duty_tax_payment, duty_tax_variance TO warehouse_app;
-- 序列是发号器，GRANT ... ON TABLE 不会带上它：漏这一条建估算单会报
-- 「permission denied for sequence」，而错误文案完全指不到授权上。
GRANT USAGE, SELECT ON SEQUENCE duty_tax_estimate_code_seq TO warehouse_app;
-- 缴款与差异 append-only：不给 UPDATE/DELETE 权限（触发器再收一道）。
REVOKE UPDATE, DELETE ON duty_tax_payment, duty_tax_variance FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V63、V80、V85 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
