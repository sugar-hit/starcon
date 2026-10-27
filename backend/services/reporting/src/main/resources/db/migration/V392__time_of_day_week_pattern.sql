-- ===========================================================================
-- V392 · time_of_day_week_pattern —— 13.2.1.2 分时与分周模式
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:121）：
--
--   ### 13.2.1.2 分时与分周模式
--   - **职责**：分析出单时间分布（按小时、按星期），识别高峰低谷，支持按此调整仓内人力与促销排期。
--   - **输入**：订单时间戳（按店铺时区换算）；时段配置。
--   - **输出**：时段分布热力图 + 高峰时段排序。
--   - **关系**：上游 = 9 订单；下游 = 8.5 作业排班、14 广告（分时投放）。
--     **跨境多时区店铺必须按各自时区统计**，汇总时区不同会造成误判。
--
-- ---------------------------------------------------------------------------
-- 0. 这四张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_time_pattern_metric_definition —— 本模块**新增**指标口径的唯一表
--   reporting_time_pattern_slot             —— **时段配置**（租户可配置：高峰 / 平峰 / 低谷）
--   reporting_time_pattern_shop_profile     —— **店铺时区档案**（每个店铺按哪个时区统计）
--   reporting_time_pattern_fact             —— **分时投影事实**（一份事实，两种切法）
--
-- ⚠️ **本文件仍然只是投影**：成交金额与销量真值在 trade（销售订单 + 订单明细），
--   店铺归属在 trade 的平台单（platform_order.sales_order_id 回挂），
--   店铺时区在 channel（2.3.1.1 店铺启用的**站点**的 default_timezone，
--   经 shop_marketplace 绑定到店铺）。本服务只**读**它们的授权只读 API，
--   把结果落成读模型。任何数字与业务系统冲突，**以业务系统为准**（卷 13 卷首原文）。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 时区是本模块的命门（蓝图关系句的加粗句）
-- ---------------------------------------------------------------------------
--
--   「**跨境多时区店铺必须按各自时区统计**，汇总时区不同会造成误判。」
--
--   ⇒ **「按小时」= 店铺本地时区的小时**，不是 UTC 小时，也不是服务器时区小时。
--   ⇒ **「按星期」= 店铺本地时区的星期几**（本地周一 00:00 到本地周日 23:59:59），
--     **不是** ISO 周的周数（周数是另一回事，本模块不产出周数）。
--
--   ⚠️ **时区用 IANA 标识**（如 `America/Los_Angeles`），**不用固定 UTC 偏移** ——
--     固定偏移会错过夏令时（本模块的 local_hour 会整整差一小时）。
--     本表用**触发器**钉死「写进来的时区必须是 `pg_timezone_names` 里真实存在的 IANA 标识」。
--
--   ⚠️ **店铺时区从哪来**：channel 的 `shop` 表**没有时区列**；时区在
--     `marketplace.default_timezone`（站点参数，2.1.1.3），店铺与站点的绑定在
--     `shop_marketplace`。⇒ 店铺时区 = **该店铺启用的站点的 default_timezone**：
--       启用 1 个站点 ⇒ 唯一可定（RESOLVED）
--       启用多个站点但**时区相同** ⇒ 仍可定（RESOLVED）
--       启用多个站点且**时区不同** ⇒ **定不了**（UNRESOLVED，不能挑一个凑数）
--       启用 0 个站点 ⇒ **定不了**（UNRESOLVED）
--     定不了的订单**不进任何小时 / 星期桶**，单列成「未能按店铺本地时分桶」——
--     摊给某个时区是造假。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 汇总口径：跨店铺汇总时必须说清对齐的是哪一个槽位
-- ---------------------------------------------------------------------------
--
--   「各店铺本地 17:00 的订单」和「UTC 17:00 的订单」**不是同一批订单**。
--   本模块**两种都算得出**（事实里同时存了本地切面与精确时刻），但**默认只出一种**：
--
--   **默认 = 本地时间对齐（LOCAL_TIME）**：
--     「小时 17」= **每个店铺各自的**本地 17:00–17:59。
--     选它的理由：蓝图的下游是 **8.5 作业排班**与 **14 广告（分时投放）**——
--     这两件事问的都是「**买家在什么时间点下单**」，而买家的时钟是**店铺所在市场的本地时钟**；
--     广告后台的分时投放也是按**站点当地时间**设的。
--     把它对齐到 UTC 槽位，得到的是「所有市场在同一绝对时刻的合计」——
--     那是仓内同一时刻的人力分布，不是消费者的下单节奏。
--
--   **可选 = UTC 槽位对齐（UTC_SLOT）**：
--     「小时 17」= 所有店铺共同�� **UTC 17:00–17:59**。
--     它的用处是**看仓内同一绝对时刻的压力**（跨时区团队排班）；
--     但它**不能**回答「我们的买家什么时候下单」。
--
--   ⚠️ 两个口径**结果不同**，本模块在**能力声明**与响应里都写明当前用的是哪一个、为什么。
--     页面必须显示它 —— 不显示口径的汇总就是让人误判。
--
-- ---------------------------------------------------------------------------
-- 3. 🔴 事实只落**一份**，两种切法在查询期重分组
-- ---------------------------------------------------------------------------
--
--   事实粒度 = **一张订单 × 一个店铺 × 店铺本地日期 × 本地小时 × 本地星期几**。
--
--   ⚠️ **不按「小时」和「按星期」各投影一份事实** —— 那是「一个数两个真相」，
--     两次刷新之间两份行可能对不上。
--   ⇒ 事实表**同时**存了三种切法所需的全部信息：
--       local_date / local_hour / local_weekday —— 本地时间对齐用（已按店铺时区换算）
--       occurred_at                            —— UTC 槽位对齐用（**精确时刻**，timestamptz）
--     「按小时」「按星期」两种视图从**同一份事实**在查询期聚合出来。
--
--   ⚠️ **事实存的是精确时刻 + 本地切面，不是「只有小时」**：
--     保留 occurred_at 才能做 UTC 槽位对齐；保留本地切面才能让「店铺本地小时」
--     在查询期直接按列聚合（不用每次都按 IANA 时区重算一遍）。
--
-- ---------------------------------------------------------------------------
-- 4. 🔴 时段配置是**租户数据**，不是常量
-- ---------------------------------------------------------------------------
--
--   蓝图输入句明写「订单时间戳（按店铺时区换算）；**时段配置**」。
--   ⇒ 「某小时属于哪个时段」由**本表**的区间判定算出，**不是** if-else 硬编码；
--     「高峰 / 平峰 / 低谷」的判定依据也来自本表（层级 + 阈值 + 比较方向）。
--
--   ⚠️ **区间不可重叠、不留空洞**（这是配置能被信任的前提）：
--     跨行不变量**在数据层**用触发器钉死（PostgreSQL 的 CHECK 约束**不能引用其它行**，
--     而 `EXCLUDE USING gist` 要 `CREATE EXTENSION btree_gist` —— 本仓 V226 / V232
--     已明确不引这个扩展，所以这里用触发器，与本仓其它跨行不变量同一写法）。
--     行内形状（小时范围、起 < 止、阈值非负）用 **CHECK** 约束。
--
--   ⚠️ **边界是左闭右开** `[start_hour, end_hour)`：start_hour 含、end_hour 不含。
--     例：`[9, 12)` = 本地 09:00–11:59 三个小时，**不含 12:00**。
--
--   ⚠️ 启用时段必须**完整覆盖 0..24 小时**：少一个小时就是「留了空洞」，
--     那个小时**不属于任何时段** ⇒ 时段层面的汇总会漏掉它 ⇒ 会被读成「那个时段没单」。
--
-- ---------------------------------------------------------------------------
-- 5. 🔴 口径唯一
-- ---------------------------------------------------------------------------
--
--   公司维度已有的三个基础口径（SALES_AMOUNT / UNIT_SOLD / ORDER_COUNT）
--   登记在 **13.1.1.1 的 `reporting_metric_definition`** ——
--   ⇒ 本模块**直接引用**它们（事实行的 metric_code 就是这三个码），**不重定义**。
--     分时 / 分周只是**同一个指标换了一种切法**（订单总额仍然是订单总额），
--     不是另一个口径。
--
--   本表只登记 13.1.1.1 **没有**的两类口径：
--     ① **时段占比口径**（GROUP_SHARE）：时段订单占比 / 时段成交金额占比 ——
--        13.1.1.1 的 aggregation 枚举里没有这一档；
--     ② **分位阈值口径**（PERCENTILE）：小时订单量的 P90 阈值（数据驱动的高峰刻度）。
--
--   ⚠️ 派生型口径**只写被比较指标的引用**（base_metric_code），不写公式 SQL
--     ⇒ 口径改一处，引用它的地方自动跟着改。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_time_pattern_metric_definition —— 本模块新增的指标口径
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：口径是**租户自己**的（13.1.1.1 / 13.1.1.2 / 13.2.1.1 / 13.2.2.1
--   已经是这个纪律）。服务层在第一次投影时按标准口径播种一份（播种内容是服务层的常量，
--   **不是数据源**）。
--
-- ⚠️ **被比较指标必须已登记**（本表 **或** 13.1.1.1 的 `reporting_metric_definition`）：
--   DB 触发器钉死 ⇒ 口径不成立时写不进来。
CREATE TABLE reporting_time_pattern_metric_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY 金额 / COUNT 笔数 / QUANTITY 数量 / PERCENT 百分比。
  unit text NOT NULL CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT')),
  aggregation text NOT NULL
    CHECK(aggregation IN ('GROUP_SHARE','PERCENTILE')),
  -- **被比较 / 被分位的指标**：
  --   GROUP_SHARE —— 「谁被分成多少份」（分母 = 同一窗口的全部时段合计）
  --   PERCENTILE —— 「按谁排序取分位」（被分位的指标）
  base_metric_code varchar(48) NOT NULL,
  -- 是否涉币：涉币指标**不跨币种相加**，只按币种分别报。
  currency_aware boolean NOT NULL DEFAULT true,
  -- 口径说明：页面上原样显示，让用户知道这个数字是怎么来的。
  description varchar(500) NOT NULL DEFAULT '',
  -- 上游归属模块。**空串 = 上游已在本服务内闭环**。
  owner_module varchar(16) NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个指标码只能有一份定义：口径唯一。
  UNIQUE(tenant_id, metric_code)
);

-- ---------------------------------------------------------------------------
-- 2. reporting_time_pattern_slot —— 时段配置（租户可配置）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个时段（本地小时区间 + 层级 + 判定阈值）。
--
-- ⚠️ **左闭右开** `[start_hour, end_hour)`：start_hour 含、end_hour 不含。
--   `end_hour = 24` 表示「一直到本地 24:00」（即当天的最后一小时 23:00–23:59）。
--
-- ⚠️ **level 是租户自己的判断**（高峰 / 平峰 / 低谷），不是系统算出来的；
--   系统只做一件事：**用阈值核对它**（qualify_threshold + qualify_comparison），
--   核对结果与理由都回给页面（「为什么这个时段算高峰」）。
--
-- ⚠️ **qualify_basis / qualify_comparison 一起才是一条完整判据**：
--   基准（按订单占比还是按订单笔数）+ 方向（至少达到还是至多达到）。
--   例：TROUGH（低谷）+ ORDER_SHARE + AT_MOST + 0.05
--       ⇒ 「这个低谷时段的订单占比**不超过** 5% 时，它确实是个低谷」。
CREATE TABLE reporting_time_pattern_slot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 时段码（租户内唯一）。
  slot_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 🔴 **本地小时区间（左闭右开）**：start 含、end 不含。
  start_hour smallint NOT NULL CHECK(start_hour BETWEEN 0 AND 23),
  end_hour smallint NOT NULL CHECK(end_hour BETWEEN 1 AND 24),
  -- 租户配置的层级：高峰 / 平峰 / 低谷。
  level varchar(16) NOT NULL CHECK(level IN ('PEAK','SHOULDER','TROUGH')),
  -- 判定基准：ORDER_SHARE（订单占比）/ ORDER_COUNT（订单笔数）。
  qualify_basis varchar(16) NOT NULL DEFAULT 'ORDER_SHARE'
    CHECK(qualify_basis IN ('ORDER_SHARE','ORDER_COUNT')),
  -- 判定方向：AT_LEAST（至少达到阈值）/ AT_MOST（至多达到阈值）。
  qualify_comparison varchar(16) NOT NULL DEFAULT 'AT_LEAST'
    CHECK(qualify_comparison IN ('AT_LEAST','AT_MOST')),
  -- 判定阈值（占比用 0..1 的小数，笔数用整数笔）。
  qualify_threshold numeric(19,6) NOT NULL DEFAULT 0 CHECK(qualify_threshold >= 0),
  -- 显示顺序（小时升序之外的额外排序用）。
  sort_order integer NOT NULL DEFAULT 0,
  enabled boolean NOT NULL DEFAULT true,
  -- 说明（页面上原样显示）。
  description varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个时段码只有一条配置。
  UNIQUE(tenant_id, slot_code),
  -- 区间不能是空区间 / 反向区间。
  CHECK(end_hour > start_hour)
);

-- 时段查询用：租户 + 启用中的时段按小时顺序。
CREATE INDEX reporting_time_pattern_slot_by_tenant
  ON reporting_time_pattern_slot(tenant_id, enabled, start_hour);

-- ---------------------------------------------------------------------------
-- 3. reporting_time_pattern_shop_profile —— 店铺时区档案
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个店铺「按哪个时区统计」。
--
-- ⚠️ **时区定不了也是一条档案**（timezone_status = 'UNRESOLVED' + 原因）——
--   删掉它的话，查询侧只能报「这个店铺不在档案里」，那会让人以为数据缺失；
--   有一行 UNRESOLVED 就把「它确实定不了时区」这件事说出来了。
--
-- ⚠️ **不硬编码时区表**：时区来自 channel 的站点参数（`marketplace.default_timezone`），
--   写入前用 `pg_timezone_names` 核对（触发器钉死）—— 固定 UTC 偏移会错过夏令时。
CREATE TABLE reporting_time_pattern_shop_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 店铺业务编码（channel `shop.code`）。
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
  shop_name varchar(150) NOT NULL DEFAULT '',
  -- 🔴 **IANA 时区标识**（如 `America/Los_Angeles`）。**空串 = 定不了时区**。
  shop_timezone varchar(64) NOT NULL DEFAULT '',
  -- 归属状态：RESOLVED 定得了 / UNRESOLVED 定不了。
  timezone_status varchar(16) NOT NULL DEFAULT 'UNRESOLVED'
    CHECK(timezone_status IN ('RESOLVED','UNRESOLVED')),
  -- 定不了 / 定得了的原因（页面上原样显示）。
  timezone_reason varchar(300) NOT NULL DEFAULT '',
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL DEFAULT 'channel',
  source_reference varchar(160) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个店铺只有一条档案。
  UNIQUE(tenant_id, shop_code)
);

-- 按店铺查档案用：租户 + 店铺。
CREATE INDEX reporting_time_pattern_shop_profile_by_shop
  ON reporting_time_pattern_shop_profile(tenant_id, shop_code);

-- ---------------------------------------------------------------------------
-- 4. reporting_time_pattern_fact —— 分时投影事实
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条**事实贡献**（一张订单的一个指标）在某个店铺、某个币种、
--   某个**店铺本地时刻**上的取值。
--
-- ⚠️ **一份事实支撑两种切法**（见上面第 3 节）：
--   local_date / local_hour / local_weekday —— 店铺本地时间对齐用
--   occurred_at                            —— UTC 槽位对齐用
--
-- ⚠️ **local_weekday 是 ISO 星期几**（1 = 周一 … 7 = 周日），
--   取的是**店铺本地**的星期几 —— **不是** ISO 周的周数。
--
-- ⚠️ **定不了时区的订单也落事实**（timezone_status = 'UNRESOLVED'，
--   三个 local_* 列全为 NULL）：它们**不进任何小时 / 星期桶**，
--   但在报表里**单列**出来（不能悄悄少算，更不能摊给某个时区）。
--
-- ⚠️ **金额一律 numeric，禁浮点**：value numeric(24,4)、quantity numeric(19,6)。
--   COUNT 型指标（订单量）落在 value 列，QUANTITY 型落在 quantity 列。
--
-- ⚠️ **口径码必须已登记**（本表 **或** 13.1.1.1 的 `reporting_metric_definition`）：
--   DB 触发器钉死 ⇒ 事实不能自己发明指标码（口径唯一）。
CREATE TABLE reporting_time_pattern_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 店铺（回挂自 trade 的平台单；挂不上的订单**不落任何店铺**）。
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
  -- 🔴 **该订单统计时用的 IANA 时区**（空串 = 定不了时区）。
  shop_timezone varchar(64) NOT NULL DEFAULT '',
  timezone_status varchar(16) NOT NULL DEFAULT 'UNRESOLVED'
    CHECK(timezone_status IN ('RESOLVED','UNRESOLVED')),
  -- 🔴 **店铺本地日期 / 小时 / 星期几**（定不了时区时三个列全为 NULL）。
  local_date date,
  local_hour smallint,
  local_weekday smallint,
  -- 🔴 **事实发生的精确时刻**（timestamptz）—— UTC 槽位对齐用它。
  occurred_at timestamptz NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  metric_code varchar(48) NOT NULL,
  -- 金额型 / 笔数型取值（金额型与笔数型指标用这列；数量型指标恒为 0）。
  value numeric(24,4) NOT NULL DEFAULT 0,
  -- 数量型取值（销量件数）。金额型指标恒为 0。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 🔴 **本地切面与时区状态必须自洽**：
  --   定得了 ⇒ 三个 local_* 列都有值且形状合法；
  --   定不了 ⇒ 三个 local_* 列全为 NULL（不能只填一半）。
  CHECK(
    (timezone_status = 'RESOLVED'
       AND local_date IS NOT NULL
       AND local_hour IS NOT NULL AND local_hour BETWEEN 0 AND 23
       AND local_weekday IS NOT NULL AND local_weekday BETWEEN 1 AND 7)
    OR (timezone_status = 'UNRESOLVED'
       AND local_date IS NULL AND local_hour IS NULL AND local_weekday IS NULL)
  )
);

-- 幂等键：同一 (租户, 店铺, 指标, 币种, 来源服务, 上游行) 只有一条事实。
-- ⚠️ 键里**不含** local_*（它们由订单时刻 + 店铺时区**决定**，不是独立维度）：
--   时区档案改了，同一条事实会被**更新**到新的本地桶，而不是多出一条。
CREATE UNIQUE INDEX reporting_time_pattern_fact_identity
  ON reporting_time_pattern_fact(
    tenant_id, shop_code, metric_code, currency, source_service, source_reference);

-- 本地时间对齐用：租户 + 店铺 + 本地日期。
CREATE INDEX reporting_time_pattern_fact_by_local_date
  ON reporting_time_pattern_fact(tenant_id, local_date, local_hour, local_weekday);
-- 店铺筛选用：租户 + 店铺 + 时刻。
CREATE INDEX reporting_time_pattern_fact_by_shop
  ON reporting_time_pattern_fact(tenant_id, shop_code, occurred_at);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 派生 / 分位口径的**被比较指标必须真的存在**
--     （本表里，或者 13.1.1.1 的 `reporting_metric_definition` 里 —— 公司维度的三个基础口径在那里）。
CREATE OR REPLACE FUNCTION guard_reporting_time_pattern_metric_definition_ref() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_time_pattern_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.base_metric_code)
     AND NOT EXISTS (
    SELECT 1 FROM reporting_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.base_metric_code) THEN
    RAISE EXCEPTION '指标 % 的被比较指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.base_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_time_pattern_metric_definition_ref
  BEFORE INSERT OR UPDATE ON reporting_time_pattern_metric_definition
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_time_pattern_metric_definition_ref();

-- (b) 事实行的**指标码必须已登记**（本表 **或** 13.1.1.1 的口径表）
--     ⇒ 事实不能自己发明指标码（口径唯一）。
CREATE OR REPLACE FUNCTION guard_reporting_time_pattern_fact_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_time_pattern_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code)
     AND NOT EXISTS (
    SELECT 1 FROM reporting_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在口径表中登记，不能落事实', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_time_pattern_fact_known
  BEFORE INSERT OR UPDATE ON reporting_time_pattern_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_time_pattern_fact_known();

-- (c) 🔴 **时段区间不可重叠、不留空洞**（跨行不变量 ⇒ 用触发器，不是 CHECK：
--     PostgreSQL 的 CHECK 约束**不能引用其它行**；
--     而 `EXCLUDE USING gist` 要 `CREATE EXTENSION btree_gist` ——
--     本仓 V226 / V232 已明确不引这个扩展，所以这里用触发器，与本仓其它跨行不变量同一写法）。
--
--     ① 重叠：任一其它**启用**时段与本时段的小时区间相交 ⇒ 拒绝；
--     ② 空洞：启用时段必须**完整覆盖 0..24 小时**，少一个小时就拒绝。
--
--     ⚠️ 用**延迟约束触发器**（DEFERRABLE INITIALLY DEFERRED）：
--        它在**事务提交时**才检查，那时表的最终状态已经定型 ——
--        ① 「整体替换时段配置」（先清空再重建）因此能通过：中间态允许有空洞，
--           提交时那一套必须完整合法；
--        ② 「只改一条、改出一个空洞」会在提交时被拒（事务回滚），数据层照样兜住。
--        服务层在事务结束前用 `SET CONSTRAINTS ALL IMMEDIATE` 提前触发，
--        好把错误翻成 400（而不是 500）。
--
-- 🔴 **判定的是「该租户在该时刻的最终状态」，不是「这一行 vs 其它行」**：
--    早先的写法拿**被删掉那一行**的区间去和**幸存下来的行**比 ——
--    「整体替换」是「先 DELETE 旧的、再 INSERT 新的」，提交时新行已经在表里，
--    于是旧行的区间必然和新区间相交 ⇒ **整体替换永远被拒**（合法的替换也过不了）。
--    现在改成**自连接**判重叠 + **逐小时**判覆盖：
--    无论 INSERT / UPDATE / DELETE，提交时校验的都是**这一租户的整张时段表**，
--    语义变成「这一租户此刻的时段配置必须是一个合法划分」——
--    正好是「时段区间不可重叠、不留空洞」这句话本身。
CREATE OR REPLACE FUNCTION guard_reporting_time_pattern_slot_partition() RETURNS trigger AS $$
DECLARE
  -- ⚠️ 变量名不能用 `overlaps`：那是 PostgreSQL 的**保留字**
  --    （SQL 标准的行比较运算符 `(a,b) OVERLAPS (c,d)`），
  --    拿它当 plpgsql 变量名 ⇒ 函数体在 CREATE 时就解析失败。
  overlap_count integer;
  uncovered_count integer;
  t_tenant uuid;
BEGIN
  -- 🔴 **DELETE 时用 OLD、其余用 NEW**：被删掉的那一行已经不在表里了，
  --    但它属于哪个租户仍然要用来圈定「校验哪张表」。
  IF TG_OP = 'DELETE' THEN
    t_tenant := OLD.tenant_id;
  ELSE
    t_tenant := NEW.tenant_id;
  END IF;

  -- ① 重叠：同一租户内**任意两个启用时段**的小时区间相交 ⇒ 拒绝。
  SELECT count(*) INTO overlap_count
    FROM reporting_time_pattern_slot a
    JOIN reporting_time_pattern_slot b
      ON a.tenant_id = b.tenant_id
     AND a.id <> b.id
     AND a.enabled
     AND b.enabled
     AND a.start_hour < b.end_hour
     AND b.start_hour < a.end_hour
   WHERE a.tenant_id = t_tenant;
  IF overlap_count > 0 THEN
    RAISE EXCEPTION '该租户的启用时段区间互相重叠（时段区间不可重叠）'
      USING ERRCODE = 'check_violation';
  END IF;

  -- ② 空洞：启用时段必须**完整覆盖 0..24 小时**。
  SELECT count(*) INTO uncovered_count
    FROM generate_series(0, 23) h
   WHERE NOT EXISTS (
     SELECT 1 FROM reporting_time_pattern_slot s
      WHERE s.tenant_id = t_tenant
        AND s.enabled
        AND h >= s.start_hour
        AND h < s.end_hour);
  IF uncovered_count > 0 THEN
    RAISE EXCEPTION '启用时段必须完整覆盖 0..24 小时（不留空洞），当前有 % 个小时不属于任何时段', uncovered_count
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER reporting_time_pattern_slot_partition
  AFTER INSERT OR UPDATE OR DELETE ON reporting_time_pattern_slot
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_time_pattern_slot_partition();

-- (d) 🔴 **店铺时区必须是 `pg_timezone_names` 里真实存在的 IANA 标识**
--     （固定 UTC 偏移会错过夏令时 ⇒ 本地小时会整整差一小时）。
--     定不了时区 ⇒ 必须是 UNRESOLVED 且时区为空串。
CREATE OR REPLACE FUNCTION guard_reporting_time_pattern_shop_profile_timezone() RETURNS trigger AS $$
BEGIN
  IF NEW.timezone_status = 'RESOLVED' THEN
    IF btrim(NEW.shop_timezone) = '' THEN
      RAISE EXCEPTION '店铺 % 标记为时区已定，但时区标识为空', NEW.shop_code
        USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.shop_timezone) THEN
      RAISE EXCEPTION '时区 % 不是 pg_timezone_names 里真实存在的 IANA 标识（店铺 %）',
        NEW.shop_timezone, NEW.shop_code
        USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    IF btrim(NEW.shop_timezone) <> '' THEN
      RAISE EXCEPTION '店铺 % 标记为时区定不了，但带了时区标识 %', NEW.shop_code, NEW.shop_timezone
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_time_pattern_shop_profile_timezone
  BEFORE INSERT OR UPDATE ON reporting_time_pattern_shop_profile
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_time_pattern_shop_profile_timezone();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_time_pattern_metric_definition',
    'reporting_time_pattern_slot',
    'reporting_time_pattern_shop_profile',
    'reporting_time_pattern_fact'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_time_pattern_metric_definition,
  reporting_time_pattern_slot,
  reporting_time_pattern_shop_profile,
  reporting_time_pattern_fact TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_time_pattern_metric_definition,
  reporting_time_pattern_slot,
  reporting_time_pattern_shop_profile,
  reporting_time_pattern_fact TO starcon_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **8.5 作业排班 / 14 广告（分时投放）**：本模块的时段桶与高峰排序就是它们的输入
--     —— 按**店铺本地时间**给出来，广告后台的分时投放也是按站点当地时间设的。
--   * **13.2.1.1 销量趋势**：那边按**调用方指定的时区**分桶，本模块按**店铺各自时区**分桶
--     —— 两者的口径差在响应里都写明了，不混用。
