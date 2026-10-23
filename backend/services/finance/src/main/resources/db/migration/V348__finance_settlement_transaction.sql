-- ===========================================================================
-- V348 · 12.3.1.2 交易明细（在 V345 的「结算明细行」上补齐本模块的职责缺口）
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.3.1.2 交易明细）原文：
--
--   职责：管理结算单下的逐笔明细：订单销售、退款、费用（佣金/仓储/配送/广告）、
--         赔付、调整、代扣税，保留外部流水号与币种；**允许无订单行的费用类明细**。
--   输入：平台交易报告解析结果；`idempotency_key`。
--   输出：明细行 + 汇总校验（明细合计 = 结算单汇总）。
--   关系：上游 = 12.3.1.1；下游 = 12.3.2.1 对账、13 利润。
--         **不强制每行都有订单**是硬约束——佣金、罚款、月度调整天然无订单行，
--         硬要匹配会产生大量虚假异常。
--
-- ---------------------------------------------------------------------------
-- 0. 本模块与 V345（12.3.1.1 结算单管理）是什么关系 —— 先读，再决定
-- ---------------------------------------------------------------------------
--
--   V345 已经建了 `finance_settlement_line`（结算明细行：平台账单的一行一笔）。
--   读完 V345 与 SettlementService 之后的判定：
--
--     **`finance_settlement_line` 就是「结算单下的逐笔明细」这张表** ——
--     它已经是按结算单挂载、append-only、tenant_id NOT NULL + FORCE RLS 的明细行表。
--     ⇒ 本模块**不另建一张平行的明细表**（两张表各写一份金额 ⇒ 结算金额有两个来源 ⇒
--       「明细合计 = 结算单汇总」这条等式立刻变成两条互相对不上的账）。
--     ⇒ 本模块在**同一张表**上补齐 V345 没覆盖的职责缺口（第五节逐条列出）。
--
--   ⚠️ **V345 一字未改**（它已进过集成头并被应用过，改内容会让 Flyway checksum 校验炸掉）。
--     本迁移只做 **ADD COLUMN**（追加，不动既有列的语义与约束）与**新增表**。
--     唯一一处「改既有约束」是**类别词表加一个值**（'TAX' 代扣税）——
--     理由见第二节：12.3.1.2 点名的「代扣税」在 V345 的七个类别里**没有位置**，
--     而「汇总项的类别 = 明细行的类别」是 V345 派生链的前提，
--     只给明细行加类别、不给汇总项加 ⇒ 派生触发器一插就炸。
--
-- ---------------------------------------------------------------------------
-- 1. 本迁移刻意**不做**什么（上游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **「平台交易报告解析结果」的自动同步来源不可用** ——
--     蓝图说的输入是「平台交易报告解析结果」，它的自动来源是「卷 02 同步」。
--     全仓 grep 过：`backend/services/channel` **没有**任何交易报告 / 结算报告端点
--     （`SyncController` 只有通用同步游标 / 批次设施，不产出平台交易报告）。
--     ⇒ DB 词表**保留** `PLATFORM_SYNC`（上游交付后还要能用），
--       但服务层**拒绝**用它登记明细并写明归属模块 **卷 02 同步**；
--       能力声明里如实写「平台交易报告自动同步不可用，归属模块 卷 02 同步」。
--     **绝不造一份「同步来的」假交易报告顶替它。**
--     **人工上传交易报告这条路径是真的能用**（见第三节）。
--
--   * **12.3.2.1 对账任务 —— 未交付** ⇒ 本模块**只做「明细 + 汇总校验」**：
--     **不实现**自动匹配、**不产出**匹配结果、**不产出**差异分类。
--     汇总校验（明细合计 = 结算单汇总）是**实时算出来**的核对，不是对账任务。
--
--   * **13 利润 —— 未交付**（reporting 服务不在本仓）⇒ 本模块**不产出任何利润数字**。
--
--   * **12.3.1.3 延迟结算 —— 未交付** ⇒ 本模块不碰在途未结算金额口径。
--
--   * **订单行不做强制匹配**：订单主数据在 **trade 服务**（卷 09），跨服务**不建 FK**
--     （AGENTS.md：禁止跨服务 SQL / FK / 共享视图），订单行引用只存**逻辑 ID 字符串**。
--     蓝图加粗句「**不强制每行都有订单**是硬约束」在数据层的落法见第五节 (c)。
--
-- ---------------------------------------------------------------------------
-- 2. 类别词表加一个值：'TAX'（代扣税）
-- ---------------------------------------------------------------------------
--
--   V345 的类别词表：SALES / REFUND / COMMISSION / FBA_FEE / ADVERTISING /
--                    COMPENSATION / ADJUSTMENT（七个）。
--   12.3.1.2 点名的「代扣税」在**这七个里没有位置**：
--     · 塞进 ADJUSTMENT ⇒ 汇总项里「其他调整」就不再是「其他」，口径被污染；
--     · 塞进 COMMISSION ⇒ 代扣税不是佣金，金额口径错。
--   ⇒ 加一个独立类别 'TAX'，汇总项里单独一行。
--
--   ⚠️ **必须同时放宽明细行与汇总项两张表的 CHECK**：
--     V345 的派生链 `finance_settlement_refresh_totals` 是
--       `insert into finance_settlement_summary(category) select l.category from ... finance_settlement_line ...`
--     ⇒ 汇总项的词表**必须**是明细行词表的**超集**，
--     只改一边的话，插入一行代扣税明细会让整条派生链在 DB 层炸掉。
--
--   ⚠️ CHECK 约束在 V345 里是**匿名表约束**（Postgres 自动命名），本迁移**按定义查找**
--     （`pg_get_constraintdef` 里同时含 'category' 与 'SALES'）而不是写死约束名 ——
--     写死名字在 V345 的写法下能对上，但按定义找更抗改名。
--     （V345 的**文件内容**一个字都没动。）

DO $$
DECLARE
  con text;
BEGIN
  -- 明细行：摘掉旧的类别 CHECK，换成含 'TAX' 的新词表。
  FOR con IN
    SELECT c.conname FROM pg_constraint c
     WHERE c.conrelid = 'finance_settlement_line'::regclass
       AND c.contype = 'c'
       AND pg_get_constraintdef(c.oid) LIKE '%category%'
       AND pg_get_constraintdef(c.oid) LIKE '%SALES%'
  LOOP
    EXECUTE format('ALTER TABLE finance_settlement_line DROP CONSTRAINT %I', con);
  END LOOP;
  ALTER TABLE finance_settlement_line
    ADD CONSTRAINT finance_settlement_line_category_check
    CHECK(category IN ('SALES','REFUND','COMMISSION','FBA_FEE','ADVERTISING',
                       'COMPENSATION','ADJUSTMENT','TAX'));

  -- 汇总项：同一份词表（派生链要求它是明细行词表的超集）。
  FOR con IN
    SELECT c.conname FROM pg_constraint c
     WHERE c.conrelid = 'finance_settlement_summary'::regclass
       AND c.contype = 'c'
       AND pg_get_constraintdef(c.oid) LIKE '%category%'
       AND pg_get_constraintdef(c.oid) LIKE '%SALES%'
  LOOP
    EXECUTE format('ALTER TABLE finance_settlement_summary DROP CONSTRAINT %I', con);
  END LOOP;
  ALTER TABLE finance_settlement_summary
    ADD CONSTRAINT finance_settlement_summary_category_check
    CHECK(category IN ('SALES','REFUND','COMMISSION','FBA_FEE','ADVERTISING',
                       'COMPENSATION','ADJUSTMENT','TAX'));
END $$;

-- ===========================================================================
-- 3. 新表：平台交易报告的原文与解析版本
-- ===========================================================================
--
--   一行 = **一次解析**：原文 + 内容指纹 + 解析版本号 + 解析结果 + 告警。
--   同一份交易报告（file_key）每重新解析一次，版本号 +1
--   （UNIQUE(tenant_id, file_key, parse_version)）。
--
--   ⚠️ 与 V345 的 `finance_settlement_source_file` **刻意分开**：
--     那张表记的是**平台结算报告**（12.3.1.1 的输入），它带 `parse_version`
--     且 12.3.1.1 用 `file_key` 查它的最新版本来决定新结算单的版本号；
--     交易报告是**另一份文档**，混进同一张表会让「同一份账单重解析版本递增」
--     这条语义被两种文档搅在一起。
--
--   ⚠️ 这张表**不**参与结算金额的派生链：它只是**举证凭据**
--     （平台交易报告也是外部事实，原文要留着）。
CREATE TABLE finance_settlement_transaction_source_file (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 交易报告的身份（平台交易报告号 / 上传文件名）：同一份报告的判据。
  file_key varchar(160) NOT NULL,
  file_name varchar(200) NOT NULL DEFAULT '',
  content_type text NOT NULL CHECK(content_type IN ('CSV','JSON')),
  -- **原始内容**（原文，一字不改）。
  raw_content text NOT NULL,
  -- 内容指纹（sha256 十六进制）。
  content_hash varchar(64) NOT NULL,
  byte_size bigint NOT NULL DEFAULT 0 CHECK(byte_size >= 0),
  -- **解析版本号**：同一 file_key 每重新解析一次 +1。
  parse_version int NOT NULL CHECK(parse_version >= 1),
  -- PARSED = 解析成功并已落库；REJECTED = 文件级错误，整份没进来。
  parse_status text NOT NULL CHECK(parse_status IN ('PARSED','REJECTED')),
  -- 解析告警（逐条：第几行 + 为什么）。
  parse_warnings jsonb NOT NULL DEFAULT '[]'::jsonb,
  parsed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, file_key, parse_version)
);

-- ===========================================================================
-- 4. 明细行上补齐的列（12.3.1.2 的职责缺口）
-- ===========================================================================
--
--   ⚠️ 全部是 **ADD COLUMN**（追加），不改 V345 既有列的语义：
--     category / amount / raw_amount / currency / quantity / occurred_on /
--     external_reference / description / line_no 一个都没动。
--
--   * `transaction_type` —— **交易类型分类**（本模块的核心词表，10 个值）。
--     为什么要有它而不能只用 V345 的 `category`：
--       V345 的类别是**汇总桶**（7 个），本模块要分的是**交易类型**：
--       「仓储费」与「配送费」在 V345 里都落进 FBA_FEE 同一个桶，
--       而「代扣税」连桶都没有。两个词表的关系由数据层的 CHECK 钉死（第五节 (b)）。
--
--   * `order_reference` / `order_line_reference` —— **订单行引用（可空）**。
--     蓝图加粗句：「**允许无订单行的费用类明细**」—— 见第五节 (c)。
--
--   * `idempotency_key` —— 蓝图输入里点名的幂等键。见第五节 (d)。
--
--   * `source` —— 这一行**怎么登记进来的**：
--     MANUAL（人工逐笔登记）/ MANUAL_UPLOAD（人工上传平台交易报告解析后入库）/
--     PLATFORM_SYNC（平台交易报告自动同步 —— **上游「卷 02 同步」未交付，当前不可产生**）。
--     留空时由触发器**继承父结算单的来源**（V345 登记的明细行因此仍然是「账单上传来的」，
--     而不是被默认成「人工登记」）。
ALTER TABLE finance_settlement_line
  ADD COLUMN transaction_type text,
  ADD COLUMN order_reference varchar(160),
  ADD COLUMN order_line_reference varchar(160),
  ADD COLUMN idempotency_key varchar(200),
  ADD COLUMN source text;

-- 交易类型词表（本模块的分类词表）。
--   ORDER_SALES     订单销售
--   REFUND          退款
--   COMMISSION      佣金
--   FBA_FEE         FBA 费用（平台账单里的通用 FBA 费用桶）
--   STORAGE_FEE     仓储费
--   SHIPPING_FEE    配送费
--   ADVERTISING     广告费
--   COMPENSATION    赔付
--   ADJUSTMENT      其他调整
--   WITHHOLDING_TAX 代扣税
ALTER TABLE finance_settlement_line
  ADD CONSTRAINT finance_settlement_line_transaction_type_check
  CHECK(transaction_type IS NOT NULL
        AND transaction_type IN ('ORDER_SALES','REFUND','COMMISSION','FBA_FEE',
                                 'STORAGE_FEE','SHIPPING_FEE','ADVERTISING',
                                 'COMPENSATION','ADJUSTMENT','WITHHOLDING_TAX'));

-- 来源词表（PLATFORM_SYNC 的值保留，但服务层拒绝用它登记）。
ALTER TABLE finance_settlement_line
  ADD CONSTRAINT finance_settlement_line_source_check
  CHECK(source IS NOT NULL AND source IN ('MANUAL_UPLOAD','PLATFORM_SYNC','MANUAL'));

-- ===========================================================================
-- 5. 数据层不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================
--
-- ---------------------------------------------------------------------------
-- (a) 补默认值：交易类型与来源在留空时由数据层补齐
-- ---------------------------------------------------------------------------
--
--   ⚠️ 为什么用触发器而不是 DEFAULT：
--     `transaction_type` 的默认值**依赖同一行的 `category`**，而 Postgres 的
--     DEFAULT 是常量表达式、做不到「看别的列」。
--     同理 `source` 的默认值依赖**父结算单**（跨表），DEFAULT 更做不到。
--
--   ⚠️ 为什么不让 V345 的插入语句改口：
--     V345 已进过集成头，本模块**不改它的代码**；触发器让 V345 原样的插入语句
--     继续工作（留空 → 按 category / 父单来源补齐），且补齐后的值仍受
--     第五节 (b) 的 CHECK 约束管着 —— 补不出合法值就**插不进去**。
CREATE OR REPLACE FUNCTION finance_settlement_line_default_fields() RETURNS trigger AS $$
BEGIN
  IF NEW.transaction_type IS NULL THEN
    NEW.transaction_type := CASE NEW.category
      WHEN 'SALES'        THEN 'ORDER_SALES'
      WHEN 'REFUND'       THEN 'REFUND'
      WHEN 'COMMISSION'   THEN 'COMMISSION'
      WHEN 'FBA_FEE'      THEN 'FBA_FEE'
      WHEN 'ADVERTISING'  THEN 'ADVERTISING'
      WHEN 'COMPENSATION' THEN 'COMPENSATION'
      WHEN 'ADJUSTMENT'   THEN 'ADJUSTMENT'
      WHEN 'TAX'          THEN 'WITHHOLDING_TAX'
      ELSE NULL
    END;
  END IF;

  IF NEW.source IS NULL THEN
    SELECT s.source INTO NEW.source
      FROM finance_settlement s
     WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.settlement_id;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_settlement_line_default_fields
  BEFORE INSERT ON finance_settlement_line
  FOR EACH ROW EXECUTE FUNCTION finance_settlement_line_default_fields();

-- ---------------------------------------------------------------------------
-- (b) 交易类型 ↔ 汇总类别：两个词表不许各说各话
-- ---------------------------------------------------------------------------
--
--   「明细行说它是仓储费，汇总项却把它算进 FBA 费用」——
--   这类错位会让「明细合计 = 结算单汇总」这条等式在**分类口径**上失真，
--   而金额层面看起来还是平的（最难查的一类错账）。
--   ⇒ 合法配对在数据层写死：
--       ORDER_SALES     → SALES
--       REFUND          → REFUND
--       COMMISSION      → COMMISSION
--       FBA_FEE / STORAGE_FEE / SHIPPING_FEE → FBA_FEE
--       ADVERTISING     → ADVERTISING
--       COMPENSATION    → COMPENSATION
--       ADJUSTMENT      → ADJUSTMENT
--       WITHHOLDING_TAX → TAX
ALTER TABLE finance_settlement_line
  ADD CONSTRAINT finance_settlement_line_type_category_check
  CHECK(
    (transaction_type = 'ORDER_SALES'     AND category = 'SALES')        OR
    (transaction_type = 'REFUND'          AND category = 'REFUND')       OR
    (transaction_type = 'COMMISSION'      AND category = 'COMMISSION')   OR
    (transaction_type IN ('FBA_FEE','STORAGE_FEE','SHIPPING_FEE')
                                        AND category = 'FBA_FEE')        OR
    (transaction_type = 'ADVERTISING'     AND category = 'ADVERTISING')  OR
    (transaction_type = 'COMPENSATION'    AND category = 'COMPENSATION') OR
    (transaction_type = 'ADJUSTMENT'      AND category = 'ADJUSTMENT')   OR
    (transaction_type = 'WITHHOLDING_TAX' AND category = 'TAX')
  );

-- ---------------------------------------------------------------------------
-- (c) **允许无订单行的费用类明细**（蓝图加粗句）
-- ---------------------------------------------------------------------------
--
--   蓝图原文：「**不强制每行都有订单**是硬约束——佣金、罚款、月度调整天然无订单行，
--   硬要匹配会产生大量虚假异常。」
--
--   这条硬约束在数据层的落法是**三件事同时成立**：
--
--   ① `order_reference` / `order_line_reference` **可空**（没有 NOT NULL，
--      也没有指向订单表的 FK —— 订单在 trade 服务，跨库 FK 本来就被禁止）。
--   ② **没有任何触发器 / CHECK 要求某类明细必须带订单号** ——
--      也就没有「费用类明细缺订单 ⇒ 报异常」这条会刷屏的假异常来源。
--   ③ 只有**一条**弱约束：给了订单行号就必须给订单号
--      （`order_line_reference IS NULL OR order_reference IS NOT NULL`）——
--      「有行号却不知道是哪张订单的行」是数据错误，不是「缺订单」。
--      它**不**要求订单号必须存在。
ALTER TABLE finance_settlement_line
  ADD CONSTRAINT finance_settlement_line_order_line_needs_order_check
  CHECK(order_line_reference IS NULL OR order_reference IS NOT NULL);

-- ---------------------------------------------------------------------------
-- (d) 幂等键：蓝图输入里点名的 `idempotency_key`
-- ---------------------------------------------------------------------------
--
--   幂等键的作用：**同一笔平台交易不许记两次**。
--
--   ⚠️ 为什么**不是**表级 UNIQUE(tenant_id, idempotency_key)：
--     12.3.1.1 支持「同一份账单重解析 → 新版本 → 新结算单」（旧单置 SUPERSEDED）。
--     若幂等键在租户内全局唯一，旧单里的那些键会把新单**永久挡住** ——
--     而「重新解析」是上游必须支持的流程。
--     ⇒ 幂等键的**持有者**是「在册的结算单」：作废（VOID）与被取代（SUPERSEDED）
--       的单不再持有幂等键，新版本因此总能拿到这些键。
--
--   两道闸：
--     ① 部分唯一索引：同一张结算单内，同一个幂等键只允许出现一次。
--     ② BEFORE INSERT 触发器：同一个幂等键**不许**同时出现在两张**在册**结算单下
--        （跨单重复 = 同一笔交易被记两次 = 金额多算）。
--
--   ⚠️ 触发器**刻意不是** SECURITY DEFINER：
--     跨租户查同一把键会让「A 租户被 B 租户的键挡住」这种跨租户串扰出现，
--     而错误消息里还会带出别家的键。以调用方身份执行 ⇒ RLS 生效 ⇒ 只看得到本租户的行。
CREATE UNIQUE INDEX finance_settlement_line_idempotency_unique
  ON finance_settlement_line(tenant_id, settlement_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

CREATE OR REPLACE FUNCTION guard_finance_settlement_line_idempotency() RETURNS trigger AS $$
BEGIN
  IF NEW.idempotency_key IS NULL THEN
    RETURN NEW;
  END IF;

  IF EXISTS (
    SELECT 1
      FROM finance_settlement_line l
      JOIN finance_settlement s
        ON s.tenant_id = l.tenant_id AND s.id = l.settlement_id
     WHERE l.tenant_id = NEW.tenant_id
       AND l.idempotency_key = NEW.idempotency_key
       AND l.settlement_id <> NEW.settlement_id
       AND s.status NOT IN ('VOID','SUPERSEDED')
  ) THEN
    RAISE EXCEPTION '幂等键 % 已被另一张在册结算单占用：同一笔平台交易不许记两次',
      NEW.idempotency_key USING ERRCODE = 'unique_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_settlement_line_idempotency_guard
  BEFORE INSERT ON finance_settlement_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_settlement_line_idempotency();

-- ===========================================================================
-- 6. 索引
-- ===========================================================================
--
-- 明细行按结算单取（12.3.1.1 的既有索引 V345 已建，这里不重复）。
-- 交易类型筛选（本模块列表的默认维度）。
CREATE INDEX finance_settlement_line_by_transaction_type
  ON finance_settlement_line(tenant_id, transaction_type, settlement_id, line_no);
-- 订单引用反查（从订单号找它的费用行）。
CREATE INDEX finance_settlement_line_by_order
  ON finance_settlement_line(tenant_id, order_reference)
  WHERE order_reference IS NOT NULL;
-- 外部流水号反查。
CREATE INDEX finance_settlement_line_by_external_reference
  ON finance_settlement_line(tenant_id, external_reference)
  WHERE external_reference IS NOT NULL;
-- 账单日期区间（结算周期口径）。
CREATE INDEX finance_settlement_line_by_occurred_on
  ON finance_settlement_line(tenant_id, occurred_on, settlement_id)
  WHERE occurred_on IS NOT NULL;
-- 交易报告原文反查（这个文件版本产出了哪些明细）。
CREATE INDEX finance_settlement_transaction_source_file_by_key
  ON finance_settlement_transaction_source_file(tenant_id, file_key, parse_version DESC);

-- ===========================================================================
-- 7. 多租户（RLS isolation）+ 授权
-- ===========================================================================

ALTER TABLE finance_settlement_transaction_source_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_settlement_transaction_source_file FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON finance_settlement_transaction_source_file
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_settlement_transaction_source_file TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_settlement_transaction_source_file TO starcon_app;

-- ⚠️ `finance_settlement_line` 的 RLS 与 GRANT 在 V345 里已经建好，**这里不重复建**：
--   重复建 policy 会得到两条同名 policy（Postgres 允许，但语义就说不清了）。
--   ALTER TABLE 不会影响既有的 RLS 状态（ENABLE/FORCE 是表属性，不被 ADD COLUMN 影响）。

-- ===========================================================================
-- 8. 留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **12.3.2.1 对账任务（未交付）**：本模块只提供「明细 + 汇总校验」，
--     **不实现**自动匹配、**不产出**匹配结果与差异分类。
--     匹配用的键（订单号 + 外部流水号 + 交易类型 + 日期 + 金额）本模块**已经落库**，
--     上游交付时直接读这一张表即可，不必再抄一份。
--
--   * **13 利润（未交付）**：交易明细是它的一处上游输入，
--     本模块**不产出任何利润数字**。
--
--   * **12.3.1.3 延迟结算（未交付）**：在途未结算金额口径不在本模块。
--
--   * **卷 02 同步（未交付该端点）**：平台交易报告自动同步不可用；
--     人工上传交易报告可用。
