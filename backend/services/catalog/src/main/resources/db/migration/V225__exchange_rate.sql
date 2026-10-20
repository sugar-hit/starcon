-- 5.2.2.1 汇率查询：各币种对各本位币的汇率（汇率、方向、生效日期、来源）+ 手工录入 + 来源配置。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：查询各币种对各本位币的汇率（来源：中行/央行/平台结算汇率，可配置），展示汇率日期与来源，
--             支持手动录入特定日期的汇率。
--   **输入**：币种对与日期；汇率来源配置；手工汇率录入。
--   **输出**：汇率记录（汇率、方向、生效日期、来源）。
--   **关系**：上游 = 外部汇率源；下游 = 5.2.2.2、5.3 测算、12 财务。
--             **汇率方向必须显式**（1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源）。
--
-- ============================================================ 第一节：这张表归谁
--
-- V215（5.2.1.1 成本构成）的第三节写着「本库**没有汇率表**（ISO 4217 与折算是 12 财务的资产，
-- 本仓没有 finance 服务），所以币种不同就不相加」——
-- 也就是说**汇率表的归属方就是本模块**（5.2.2.1），不是 12 财务。
--
-- 蓝图卷 05 卷首写「成本与汇率来自 finance，本系统只读引用」，但本仓**没有 finance 服务**
-- （backend/services/ 下只有 identity / catalog / channel / warehouse / trade / crm）。
-- 所以：
--   * 本表落在 **catalog**（卷 05 定价与价目的主责服务），本迁移是这张表的**唯一写入所有者**；
--   * 本迁移**不建任何 finance 侧的表或外键** —— 那个服务不存在；
--   * 本迁移**不 ALTER 5.2.1.1 的任何表**（sku_cost_profile 等），避免打断既有测试。
--
-- **⛔ 不碰 trade 的 fulfillment_dropship_fx_rate（V130）**：那是 trade 服务 dropship 利润对账
-- 的**另一个领域**（另一个服务、另一个库、另一个口径）。跨服务读它 / 建跨库 FK 都被
-- AGENTS.md「模块边界纪律」禁止。本服务只建自己的汇率表。
--
-- ============================================================ 第二节：🔴 方向必须显式 —— 这一条是结构，不是注释
--
-- 蓝图加粗句：**汇率方向必须显式**（1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源）。
--
-- 「不靠注释兑现」的做法是：**换算方向由两个字段唯一确定**，而不是靠「rate 到底是乘还是除」的
-- 隐含约定。所以每一行都同时存：
--
--   base_currency   —— 基准币种（左边那个 1）
--   quote_currency  —— 报价币种（右边那个数字的单位）
--   rate            —— 1 base_currency = rate quote_currency
--
-- 于是「1 USD = 7.1234 CNY」这一行是
--   base_currency='USD', quote_currency='CNY', rate=7.1234
-- 而「1 CNY = 0.1404 USD」是**另一行形状**：
--   base_currency='CNY', quote_currency='USD', rate=0.1404
-- 两者在结构上不可能混淆 —— 少了 base/quote 任何一个，这条事实都写不进去。
--
-- **🔴 反向汇率只能推导，不能手工再录一条方向相反的**（两个互相矛盾的事实）：
-- 若已存在 (USD→CNY, 2026-10-20)，再录一条 (CNY→USD, 2026-10-20) 就是两个互相矛盾的事实
-- （一个说 7.1234，一个说 0.1400，差着 0.5%）。这由**触发器**在 DB 层挡住
-- （guard_exchange_rate_direction），应用层把报错翻译成一句人话并指出已有的那条记录。
-- 反向汇率由**正向显式推导**（服务层算 1/rate，标注「由正向推导」），不落库。
--
-- **同一 币种对 + 同一生效日 只能有一条生效记录**（UNIQUE）：
-- 否则「今天的汇率」有多个答案。这是硬约束，不是「一般不会重复」。
--
-- ============================================================ 第三节：表结构
--
-- 三张表，职责互不重叠：
--
--   | 表                          | 一行是什么                                   | 写入方式        |
--   |-----------------------------|----------------------------------------------|-----------------|
--   | `exchange_rate_source`      | 租户配置的**一个汇率来源**（可配置）          | 可改（乐观锁）  |
--   | `exchange_rate`             | 一条**汇率事实**（方向 + 汇率 + 生效日 + 来源）| 可改（同日更正）|
--   | `exchange_rate_fetch_log`   | 一次**外部拉取**的结果（成功 / 失败 + 原因）  | 只追加          |
--
-- 第一节表：exchange_rate_source —— 汇率来源配置
--
-- 蓝图说来源是「中行/央行/平台结算汇率，**可配置**」。所以来源是**租户级配置**而不是代码常量：
-- code（机器码）、name（显示名）、kind（手工还是外部）、enabled、priority、endpoint（外部地址）。
-- 租户可以把自己的平台结算汇率也登记成一个来源（code 例如 PLATFORM_SETTLEMENT）。
--
-- kind 的作用是钉死「这条汇率是怎么来的」：kind='MANUAL' 的来源只接受手工录入，
-- kind='EXTERNAL' 的来源**必须**有 endpoint（否则「外部拉取」无从发生）。
-- exchange_rate.entry_mode 必须与来源的 kind 一致（触发器 guard_exchange_rate_source 校验）——
-- 否则就会出现「声称来自外部拉取、实际来源根本没有外部地址」的记录。
--
-- 第二节表：exchange_rate —— 汇率事实
--
-- 精度：rate 用 **numeric(19,8)**（8 位小数）。汇率是本仓精度要求最高的数字：
-- 1 USD = 7.12345678 CNY 是现实存在的牌价；6 位小数会把 0.14% 的偏差写进每一次换算，
-- 而换算误差会被 5.3 测算与 13 利润放大。**禁止浮点**（AGENTS.md）。
--
-- rate > 0：汇率不可能是 0 或负数（0 会让任何换算除零，负数没有经济含义）。
-- base_currency <> quote_currency：本币对本币不需要汇率（trade V130 已是同一口径）。
--
-- effective_date 用 **date** 而不是 timestamptz：汇率是**按日**公布的（中行牌价、央行中间价、
-- 平台结算汇率都是「某一天的一个数」），按日粒度也让「同一天只能有一条」这句话有确定含义。
-- 采集时刻（什么时候从源上拿到的）另存 acquired_at timestamptz —— 这两件事不是一回事：
-- 10-20 的牌价可能是 10-21 才录进来的。
--
-- 第三节表：exchange_rate_fetch_log —— 外部拉取日志（只追加）
--
-- 「若实现了外部拉取，那也必须真的发请求、真的处理失败（失败要有明确错误态，不许静默返回空）」。
-- 所以每一次外部拉取都留一行：成功写 SUCCEEDED + 行数，失败写 FAILED + http_status + 原因。
-- **失败不是异常路径，是要被看见的常态**：外部汇率源挂掉是运营每天都会遇到的事。
--
-- ============================================================ 第四节：引用校验用触发器，不用外键
--
-- exchange_rate.source_code → exchange_rate_source.code
--
-- 用**触发器**而不是 FOREIGN KEY 的理由（与 V221 / V8 同款取舍）：
--   * 引用校验的**失败原因要可解释**（「来源未登记」/「来源已停用」/「录入方式与来源类型不符」），
--     外键只会给一条语焉不详的数据库报错；
--   * 上一批 batch-37 有车道在迁移里给既有表加外键，打破了同服务既有测试的 TRUNCATE ⇒ 39 例 Errors。
--     本迁移**纯新建、零 ALTER 任何既有表**，但仍按「优先触发器」的纪律走，
--     避免任何既有测试的 truncate 语句被牵连。
--
-- 同理，删除来源时若有汇率引用它，**拒绝删除**（触发器 guard_exchange_rate_source_delete）：
-- 删掉来源会让历史汇率指向一个查不到的定义。停用（enabled=false）才是正确动作 ——
-- 停用挡住的是「再用它录入」，历史汇率照常可读。
--
-- ============================================================ 第五节：RLS 与授权
--
-- 三张表都是租户级业务表（汇率是租户自己的事实）：
-- tenant_id uuid NOT NULL + ENABLE ROW LEVEL SECURITY + FORCE ROW LEVEL SECURITY
-- + isolation 策略（照抄 V215 第八节的写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的汇率。
--
-- 本模块**没有** data_scope 策略：它不挂 SKU / 法人，可见性只到租户级。

-- ============================================================ 第一节表：exchange_rate_source —— 汇率来源配置

CREATE TABLE exchange_rate_source (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 机器码：租户内的来源标识。允许租户登记自己的来源（code 例如 PLATFORM_SETTLEMENT），
  -- 所以这里是自由词表而不是封闭枚举 —— 「可配置」是蓝图原话。
  -- 但必须是稳定的标识符：汇率事实按 source_code 引用它，改 code 等于改历史事实的指向。
  code varchar(32) NOT NULL CHECK(code ~ '^[A-Z0-9_]{2,32}$'),

  -- 显示名：页面上展示的来源名称（如「中行牌价」「平台结算汇率」）。
  name text NOT NULL CHECK(btrim(name) <> ''),

  -- 来源类型：MANUAL = 只接受手工录入；EXTERNAL = 可从 endpoint 拉取。
  kind text NOT NULL CHECK(kind IN ('MANUAL','EXTERNAL')),

  -- 停用的来源不再出现在录入候选里，但**已登记的汇率照常可读**：
  -- 停用是「不许再新建」，不是「抹掉过去」。
  enabled boolean NOT NULL DEFAULT true,

  -- 优先级：数字小的排前面。用于「今天该用哪个来源的汇率」这类排序展示。
  priority int NOT NULL DEFAULT 100,

  -- 外部地址：kind='EXTERNAL' 时**必填**（否则「外部拉取」无从发生）。
  -- 这里只存**地址**，不存凭据：第三方 API key 一律走 secret manager（AGENTS.md「安全」），
  -- 不入库、不进日志、不进迁移。
  endpoint text,

  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),
  -- 地址留空允许（**空串不允许**：那是「配了个空地址」的死配置）。
  CHECK(endpoint IS NULL OR btrim(endpoint) <> ''),
  -- 手工录入来源**不该**有地址：留了就是「配了却用不上」的死配置。
  -- 反过来，外部拉取来源**可以**暂时没有地址 —— 租户常常先把来源登记下来，
  -- 等拿到接口地址再填。缺地址时拉取端点会明确拒绝（不会发一个必然失败的请求）。
  CHECK(kind <> 'MANUAL' OR endpoint IS NULL)
);

-- 「今天该用哪个来源」与「按来源筛台账」的入口。
CREATE INDEX exchange_rate_source_by_priority ON exchange_rate_source(tenant_id, enabled, priority, code);

-- ============================================================ 第二节表：exchange_rate —— 汇率事实

CREATE TABLE exchange_rate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 🔴 方向的两个字段（第二节）。换算方向由这两个字段唯一确定：
  --    1 base_currency = rate quote_currency
  -- 少了任何一个，写进来的就不是一条「可换算的事实」，而是一个需要人猜的裸数字。
  base_currency varchar(3) NOT NULL CHECK(base_currency ~ '^[A-Z]{3}$'),
  quote_currency varchar(3) NOT NULL CHECK(quote_currency ~ '^[A-Z]{3}$'),

  -- 汇率本体：1 base_currency = rate quote_currency。
  -- **numeric(19,8)**：8 位小数是本仓对汇率的精度要求（见第三节）。
  rate numeric(19,8) NOT NULL CHECK(rate > 0),

  -- 生效日：这一条汇率从哪一天开始算数（date 粒度，理由见第三节）。
  effective_date date NOT NULL,

  -- 来源：指向 exchange_rate_source.code（**软引用**，校验在触发器里，见第四节）。
  source_code varchar(32) NOT NULL,

  -- 录入方式：MANUAL = 手工录入；EXTERNAL = 外部拉取。
  -- 必须与来源的 kind 一致（触发器校验）：来源是 MANUAL 却标成 EXTERNAL 就是在编造来源。
  entry_mode text NOT NULL CHECK(entry_mode IN ('MANUAL','EXTERNAL')),

  -- 来源凭据：如中行牌价页的凭据号、平台结算单号。留空允许，但有值时它必须能回答「这条数哪来的」。
  source_ref text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',

  -- 采集时刻：这一条汇率是什么时候从源上拿到的（timestamptz）。
  -- 与 effective_date 分开：10-20 的牌价可能是 10-21 才录进来的，两者混成一个字段就答不出「录得对不对」。
  acquired_at timestamptz NOT NULL,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- 🔴 同一 币种对 + 同一生效日 只能有一条生效记录。
  -- 否则「今天的汇率」有多个答案 —— 5.2.2.2 快照与 5.3 测算不知道该冻结哪一条。
  UNIQUE(tenant_id, base_currency, quote_currency, effective_date),

  -- 本币对本币不需要汇率（与 trade V130 的 fx_rate_status='SAME_CURRENCY' 同一口径）。
  CHECK(base_currency <> quote_currency)
);

-- 最热的读路径：「USD→CNY 在 2026-10-20 的汇率是多少」「这个币种对最新的一条是哪天」。
CREATE INDEX exchange_rate_by_pair
 ON exchange_rate(tenant_id, base_currency, quote_currency, effective_date DESC);
-- 「按生效日倒序翻台账」与「某天全量汇率」。
CREATE INDEX exchange_rate_by_date
 ON exchange_rate(tenant_id, effective_date DESC, base_currency, quote_currency);
-- 「按来源筛」（某个来源登记过哪些汇率）。
CREATE INDEX exchange_rate_by_source
 ON exchange_rate(tenant_id, source_code, effective_date DESC);
-- 「各币种对**各本位币**」：按报价币种归集（本位币 = 报价币种那一侧）。
CREATE INDEX exchange_rate_by_quote
 ON exchange_rate(tenant_id, quote_currency, effective_date DESC);

-- 🔴 反向汇率守卫：同一 币种对 + 同一生效日 不许再录一条方向相反的。
-- 那是两个互相矛盾的事实（一个说 1 USD = 7.1234 CNY，另一个说 1 CNY = 0.1400 USD）。
-- 反向汇率由**正向显式推导**并标注，不落库（服务层 ExchangeRateRules.inverse）。
CREATE FUNCTION guard_exchange_rate_direction() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE inverse_id uuid; inverse_rate numeric(19,8);
BEGIN
  SELECT id, rate INTO inverse_id, inverse_rate
    FROM exchange_rate r
   WHERE r.tenant_id = NEW.tenant_id
     AND r.base_currency = NEW.quote_currency
     AND r.quote_currency = NEW.base_currency
     AND r.effective_date = NEW.effective_date
   LIMIT 1;
  IF inverse_id IS NOT NULL THEN
    RAISE EXCEPTION
      '同一生效日已存在方向相反的汇率：1 % = % %。反向汇率必须由正向推导，不许手工再录一条方向相反的（那是两个互相矛盾的事实）',
      NEW.quote_currency, inverse_rate, NEW.base_currency USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_exchange_rate_direction BEFORE INSERT OR UPDATE ON exchange_rate
 FOR EACH ROW EXECUTE FUNCTION guard_exchange_rate_direction();

-- 🔴 来源引用校验（软引用 → 触发器，不建外键；理由见第四节）。
-- 三种可解释的失败：来源未登记 / 来源已停用 / 录入方式与来源类型不符。
CREATE FUNCTION guard_exchange_rate_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_kind text; source_enabled boolean;
BEGIN
  SELECT kind, enabled INTO source_kind, source_enabled
    FROM exchange_rate_source s
   WHERE s.tenant_id = NEW.tenant_id AND s.code = NEW.source_code;
  IF source_kind IS NULL THEN
    RAISE EXCEPTION '汇率来源「%」未登记，请先在汇率来源配置里登记该来源', NEW.source_code
      USING ERRCODE = '23514';
  END IF;
  IF NOT source_enabled THEN
    RAISE EXCEPTION '汇率来源「%」已停用：停用只挡住「再用它录入」，已登记的汇率仍可读', NEW.source_code
      USING ERRCODE = '23514';
  END IF;
  IF source_kind <> NEW.entry_mode THEN
    RAISE EXCEPTION '录入方式与来源类型不符：来源「%」是 %，不能登记为 % 录入',
      NEW.source_code, source_kind, NEW.entry_mode USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_exchange_rate_source BEFORE INSERT OR UPDATE ON exchange_rate
 FOR EACH ROW EXECUTE FUNCTION guard_exchange_rate_source();

-- 来源不可被删：历史汇率按 source_code 指向它，删掉会让那些汇率指向一个查不到的定义。
-- 正确动作是停用（enabled=false）—— 停用挡住「再用它录入」，历史照常可读。
CREATE FUNCTION guard_exchange_rate_source_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE used_count bigint;
BEGIN
  SELECT count(*) INTO used_count
    FROM exchange_rate r
   WHERE r.tenant_id = OLD.tenant_id AND r.source_code = OLD.code;
  IF used_count > 0 THEN
    RAISE EXCEPTION '汇率来源「%」已被 % 条汇率引用，不能删除；请改为停用，停用只挡住录入，历史汇率仍可读',
      OLD.code, used_count USING ERRCODE = '23514';
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER guard_exchange_rate_source_delete BEFORE DELETE ON exchange_rate_source
 FOR EACH ROW EXECUTE FUNCTION guard_exchange_rate_source_delete();

-- ============================================================ 第三节表：exchange_rate_fetch_log —— 外部拉取日志（只追加）

CREATE TABLE exchange_rate_fetch_log (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 拉的是哪个来源（软引用 exchange_rate_source.code：来源被删后日志仍在，
  -- 日志要能说清「当时拉的是哪个来源」）。
  source_code varchar(32) NOT NULL,

  base_currency varchar(3),
  quote_currency varchar(3),
  requested_date date,

  -- 成功 / 失败。失败必须**有原因**（error_message 必填），否则日志只是一串「失败」，
  -- 运营看不出该找谁、该查什么。
  status text NOT NULL CHECK(status IN ('SUCCEEDED','FAILED')),
  http_status int,
  rows_fetched int NOT NULL DEFAULT 0,
  rows_saved int NOT NULL DEFAULT 0,
  error_message text NOT NULL DEFAULT '',

  requested_by uuid NOT NULL,
  requested_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  CHECK(status <> 'FAILED' OR btrim(error_message) <> '')
);

-- 拉取日志按时间倒序翻（页面上的「拉取记录」）。
CREATE INDEX exchange_rate_fetch_log_by_time
 ON exchange_rate_fetch_log(tenant_id, requested_at DESC, id DESC);
-- 按来源看这个来源最近拉得怎么样。
CREATE INDEX exchange_rate_fetch_log_by_source
 ON exchange_rate_fetch_log(tenant_id, source_code, requested_at DESC);

DO $$ DECLARE tbl text; BEGIN
  -- 三张表都是租户级业务表：tenant_id NOT NULL + ENABLE/FORCE RLS + isolation 策略。
  -- 写法与 V215 第八节逐字相同。
  FOREACH tbl IN ARRAY ARRAY['exchange_rate_source','exchange_rate','exchange_rate_fetch_log'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON exchange_rate_source TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON exchange_rate_source TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON exchange_rate TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON exchange_rate TO starcon_app;
-- 拉取日志只追加：GRANT 里不发 UPDATE/DELETE，日志是可追溯的凭据，不该被改写。
GRANT SELECT,INSERT ON exchange_rate_fetch_log TO catalog_app;
GRANT SELECT,INSERT ON exchange_rate_fetch_log TO starcon_app;
