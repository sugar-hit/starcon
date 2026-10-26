-- ===========================================================================
-- V369 · finance_bank_recon_* —— 12.7.1.2 银行对账
-- ===========================================================================
--
-- 蓝图 12-finance.md（12.7.1.2 银行对账）原文：
--
--   - **职责**：导入银行/支付平台流水与系统记录自动匹配，输出未匹配项，支持人工认领与说明，
--     生成对账报告。
--   - **输入**：流水文件（导入）；系统收支记录；匹配规则。
--   - **输出**：匹配结果 + 未匹配清单 + 对账报告。
--   - **关系**：上游 = 12.7.1.1、12.2；下游 = 12.3.2.1 对账。**未匹配项要能认领到具体单据**，
--     否则余额永远平不了——这是财务日常最高频的工作。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移刻意**不做**什么（上游/下游现状，逐条如实标注而不是造数）
-- ---------------------------------------------------------------------------
--
--   * **不 ALTER 12.2 / 12.3 的任何表。** 12.2.1.1 收款单的 `fund_account_ref`
--     仍由 V341 的 CHECK 钉死恒为 NULL，12.2.2.1 付款单的 `payment_account_id`
--     仍是无 FK 的自由 uuid ⇒ **系统侧候选集按「同币种 + 期间」取**，
--     并把「这笔记录声明自己挂在哪个账户上」作为**附加过滤**（声明了别的账户就排除）。
--     真正的「哪一笔对应哪一笔」由**匹配规则**决定，不由本迁移假装决定。
--     ⇒ 上游把受管账户引用接线之后，本模块**自动**收窄候选集，不需要改本迁移。
--
--   * **不代 12.3.2.1 对账。** 12.3.2.1（下游）拿本模块的未匹配清单与对账报告；
--     本模块**不**做 12.3.2.1 的那套（结算单 ↔ 平台账）核对，那是另一个模块的事。
--
--   * **不做跨币种折算。** 一次对账只对**一个账户的一个币种**
--     （账户的固有属性，单账户单币种是 12.7.1.1 的硬约束）⇒ 全部金额口径唯一。
--
--   * **不写别人的账。** 本模块**只读** 12.7.1.1 的账户与余额历史、12.2 的收付款、
--     12.3.1.2 的结算明细行（**同服务同库** ⇒ 合法读路径）；
--     本模块**零写入**上游任何一张表。认领只写本模块自己的「认领记录」，
--     **不改**被认领单据的状态（那是上游模块的写入所有权）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   finance_bank_recon_session       —— 对账批次（一个账户 × 一个期间）
--   finance_bank_recon_statement_line—— 银行/支付平台流水行（**外部事实，append-only**）
--   finance_bank_recon_system_line   —— 系统收支记录快照（**读模型，可重建**）
--   finance_bank_recon_match         —— 匹配结果（银行行 ↔ 系统记录）
--   finance_bank_recon_unmatched     —— 未匹配清单（当前态）
--   finance_bank_recon_claim         —— 人工认领 / 说明（**append-only 举证**）
--   finance_bank_recon_rule          —— 匹配规则（租户自己维护）
--   finance_bank_recon_report        —— 对账报告（生成时的快照）
--   finance_bank_recon_event         —— 事件流（append-only 举证）
--
--   九张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT finance_app。
--
-- ---------------------------------------------------------------------------
-- 2. 三条设计主线
-- ---------------------------------------------------------------------------
--
--   1. **不允许「金额相同即匹配」（卷 12 硬约束）**
--      卷 12 对 12.3.2.1 写明：「对账不允许『金额相同即匹配』——金额相同只是候选，
--      必须结合订单号、日期、类型。」本模块把它落成**规则层的硬约束**：
--      `finance_bank_recon_rule.rule_type` 只有三种受控值，
--      每一种都**至少**组合了两个独立维度（单号 / 对手方 / 日期窗口）+ 方向 + 金额：
--        REFERENCE_EXACT      单号精确相等 + 金额相等 + 方向相同 + 日期在窗口内
--        COUNTERPARTY_AMOUNT  对手方规范化后相等 + 金额相等 + 方向相同 + 日期在窗口内
--        AMOUNT_DATE_WINDOW   金额相等 + 方向相同 + 日期在窗口内 + 对手方**不矛盾**
--      ⇒ **没有任何一条规则只看金额**。DB 层用 CHECK 钉死规则类型词表与
--      日期窗口上限（不许把窗口放到 365 天 —— 那等于「金额相同即匹配」的变体）。
--
--   2. **未匹配项要能认领到具体单据**（蓝图加粗句）
--      `finance_bank_recon_unmatched` 上有 `claimed_document_type` /
--      `claimed_document_id` / `claimed_document_no` / `claimed_by` / `claimed_at` /
--      `claim_reason` ⇒ 「认领到具体单据」是**数据层的一等公民**，不是备注里的自由文本。
--      CLAIMED 态 ⇒ 这些字段**必须齐全**（CHECK 钉死）；认领的**举证**追加进
--      `finance_bank_recon_claim`（append-only），可以多次认领、留痕不改写。
--      ⚠️ 认领**只写本模块的表** ⇒ 被认领的收款单 / 付款单**状态不变**
--      （改别人的状态是别人的写入所有权；本模块只在报告里把它计为「已认领」）。
--
--   3. **银行流水是外部事实 ⇒ append-only**
--      流水行一旦导入就**不许改**：改流水等于改写外部凭据。
--      正确做法是**换一个对账批次**重新导入。
--      运行角色的 UPDATE/DELETE 权限已收回 + BEFORE UPDATE OR DELETE 触发器双保险。
--      ⇒ 「银行流水行」的匹配状态**不在它自己身上**，而在
--      `finance_bank_recon_match` / `finance_bank_recon_unmatched` 两张派生表上
--      （这两张表才是本模块可以推进状态机的地方）。
--
-- ---------------------------------------------------------------------------
-- 3. 金额口径
-- ---------------------------------------------------------------------------
--
--   * 金额 numeric(19,4)；数量（本模块无数量口径，流水行无数量列）；
--     汇率（本模块不折算，无汇率列）。**全链路禁浮点**。
--   * 币种 char(3) + CHECK 正则；**一次对账只有一个币种** ⇒ 全部合计有意义。
--   * 流水行的金额**恒正**（`CHECK(amount > 0)`），方向由 `direction` 列表达
--     （'IN' 收入 / 'OUT' 支出）⇒ 不出现「负数代表支出」这种二义性。
--   * 系统侧记录的原始金额**可能有正负**（结算明细行的符号约定是
--     「正=增加结算金额」），落快照时**归一**成「恒正 + 方向」两列，
--     原始值另存一列 `raw_amount`（账单里写的那个数）⇒ 口径可追溯。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_bank_recon_session —— 对账批次
-- ---------------------------------------------------------------------------
--
-- 一次对账 = 一个资金账户 × 一个期间。币种**继承账户**（单账户单币种）。
CREATE TABLE finance_bank_recon_session (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 对账单号（租户内唯一、可读）：BR-20261101-a1b2c3d4。
  recon_no varchar(64) NOT NULL CHECK(btrim(recon_no) <> ''),

  -- ---- 对账账户（12.7.1.1，同服务同库 ⇒ 可建复合外键）----
  account_id uuid NOT NULL,
  -- 账户编号 / 名称是**快照**（账户改名后历史对账单仍读得出当时的名字）。
  account_no varchar(48) NOT NULL,
  account_name varchar(120) NOT NULL,
  -- 币种：继承账户（12.7.1.1 的硬约束是单账户单币种）。
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- ---- 期间 ----
  period_start date NOT NULL,
  period_end date NOT NULL,
  CHECK(period_end >= period_start),

  -- ---- 银行对账单侧的余额（来自银行账单，导入时带入）----
  statement_opening_balance numeric(19,4) NOT NULL DEFAULT 0,
  statement_closing_balance numeric(19,4) NOT NULL DEFAULT 0,

  -- ---- 我方账面余额（来自 12.7.1.1 的余额历史，**读不到就是 NULL，不是 0**）----
  book_opening_balance numeric(19,4),
  book_closing_balance numeric(19,4),

  -- 流水来源系统（bank / paypal / stripe / pingpong …）：幂等判定的另一半。
  source_system varchar(32) NOT NULL DEFAULT 'manual',

  -- 状态机（**只能向前**）：
  --   DRAFT      已建批次，还没导入流水
  --   IMPORTED   已导入银行流水
  --   MATCHED    已跑过自动匹配（匹配结果 + 未匹配清单已产出）
  --   RECONCILED 已生成对账报告并确认（**终态**：批次冻结）
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','IMPORTED','MATCHED','RECONCILED')),

  -- ---- 计数（由匹配动作维护，运营字段）----
  statement_line_count int NOT NULL DEFAULT 0 CHECK(statement_line_count >= 0),
  system_line_count int NOT NULL DEFAULT 0 CHECK(system_line_count >= 0),
  matched_count int NOT NULL DEFAULT 0 CHECK(matched_count >= 0),
  unmatched_bank_count int NOT NULL DEFAULT 0 CHECK(unmatched_bank_count >= 0),
  unmatched_system_count int NOT NULL DEFAULT 0 CHECK(unmatched_system_count >= 0),

  -- ---- 确认（**财务侧专属动作**的留痕）----
  reconciled_by uuid,
  reconciled_at timestamptz,

  note varchar(500) NOT NULL DEFAULT '',
  version int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, recon_no),
  FOREIGN KEY(tenant_id, account_id) REFERENCES finance_fund_account(tenant_id, id),

  -- 🔴 状态与留痕必须自洽：RECONCILED ⇒ 有确认人与确认时刻；
  --    其余状态 ⇒ 这两个字段一律为空（**确认之前不许预填**）。
  CONSTRAINT finance_bank_recon_session_state_consistent CHECK(
    (status = 'RECONCILED' AND reconciled_by IS NOT NULL AND reconciled_at IS NOT NULL)
    OR (status <> 'RECONCILED' AND reconciled_by IS NULL AND reconciled_at IS NULL)
  )
);

-- 列表默认排序：对账单号；账户 / 期间 / 状态各自是常用筛选维度。
CREATE INDEX finance_bank_recon_session_by_account
  ON finance_bank_recon_session(tenant_id, account_id, period_start DESC);
CREATE INDEX finance_bank_recon_session_by_status
  ON finance_bank_recon_session(tenant_id, status);
CREATE INDEX finance_bank_recon_session_by_period
  ON finance_bank_recon_session(tenant_id, period_end DESC);

-- ---------------------------------------------------------------------------
-- 2. finance_bank_recon_statement_line —— 银行/支付平台流水行（append-only）
-- ---------------------------------------------------------------------------
--
-- 一行 = 银行账单上的一行流水（**外部事实**）。导入后**不可改写**。
CREATE TABLE finance_bank_recon_statement_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  -- 事实时刻：timestamptz。列是 timestamptz，服务层一律 OffsetDateTime +
  -- `?::timestamptz` 绑定（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
  occurred_at timestamptz NOT NULL,

  -- 方向：IN 收入（银行账户流入）/ OUT 支出（流出）。
  direction text NOT NULL CHECK(direction IN ('IN','OUT')),

  -- 金额恒正（方向单独一列 ⇒ 不出现「负数代表支出」的二义性）。
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 银行流水号：与 session 组成**幂等键**（同一份账单重复导入不重复入账）。
  bank_reference varchar(128) NOT NULL CHECK(btrim(bank_reference) <> ''),

  -- 对方单号（订单号 / 收付款单号 / 平台交易号）：**匹配的主依据**。
  external_reference varchar(160) NOT NULL DEFAULT '',

  -- 对方名称（付款方 / 收款方）：匹配的第二依据。
  counterparty varchar(160) NOT NULL DEFAULT '',

  -- 摘要 / 备注（银行账单上的说明文字）。
  memo varchar(500) NOT NULL DEFAULT '',

  -- 导入批次（同一批导入的一批行，便于回溯是哪一次导入）。
  import_batch varchar(64) NOT NULL DEFAULT '',
  imported_by uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 幂等键：同一批次内同一银行流水号只入账一次。
  UNIQUE(tenant_id, session_id, bank_reference),
  FOREIGN KEY(tenant_id, session_id) REFERENCES finance_bank_recon_session(tenant_id, id) ON DELETE CASCADE
);
-- ⚠️ 币种与批次一致这条闸用**触发器**而不是 CHECK ——
--    Postgres 的 CHECK 约束**不允许子查询**（写进去迁移会直接失败），
--    而「币种 = 批次的币种」必须查另一张表 ⇒ 只能落在 BEFORE INSERT 触发器里。

-- 按批次 + 时刻查（对账期间内的时间序）。
CREATE INDEX finance_bank_recon_statement_line_by_session
  ON finance_bank_recon_statement_line(tenant_id, session_id, occurred_at, id);
-- 按对方单号查（人工查「这笔流水对应哪个单据」）。
CREATE INDEX finance_bank_recon_statement_line_by_reference
  ON finance_bank_recon_statement_line(tenant_id, external_reference)
  WHERE btrim(external_reference) <> '';

-- ---------------------------------------------------------------------------
-- 3. finance_bank_recon_system_line —— 系统收支记录快照（读模型，可重建）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条系统记录（12.2 收款单 / 付款单，12.3.1.2 结算明细行）**在本次对账里的快照**。
--
-- ⚠️ 与流水行不同，这张表**不是外部事实**，而是**读模型**：
--   上游单据的状态会变（收款单从 RECORDED 变 VOID），对账重跑时快照要跟着变
--   ⇒ 允许**重建**（UPSERT / DELETE），不设 append-only。
--   但「当时的值」仍留在快照列里（raw_amount / occurred_at / source_no），
--   报告与未匹配清单引用的是**快照**，不是上游的当前值。
CREATE TABLE finance_bank_recon_system_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  -- 来源类型（受控词表）：
  --   RECEIPT             12.2.1.1 收款单
  --   PAYMENT_ORDER       12.2.2.1 付款单
  --   SETTLEMENT_LINE     12.3.1.2 结算明细行
  source_type text NOT NULL
    CHECK(source_type IN ('RECEIPT','PAYMENT_ORDER','SETTLEMENT_LINE')),
  -- 上游记录 id（**同服务同库** ⇒ 可建复合外键；不同来源指向不同的表 ⇒ 用触发器校验）。
  source_id uuid NOT NULL,
  -- 上游单号 / 行号（快照，便于人读）。
  source_no varchar(64) NOT NULL DEFAULT '',
  source_label varchar(200) NOT NULL DEFAULT '',

  -- 事实时刻：timestamptz。
  -- ⚠️ 12.3.1.2 的结算明细行只有**日历日**（occurred_on，date）⇒
  --    落快照时归一到 **UTC 当日零点**，并由 source_type 标出它的精度只到「天」
  --    （`occurred_precision` = 'DAY' / 'SECOND'）⇒ 匹配时不会把「天」当成「秒」比。
  occurred_at timestamptz NOT NULL,
  occurred_precision text NOT NULL DEFAULT 'SECOND' CHECK(occurred_precision IN ('DAY','SECOND')),

  direction text NOT NULL CHECK(direction IN ('IN','OUT')),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 账单里写的**原始**金额（可能为负，结算明细行的符号约定是「正=增加结算金额」）。
  raw_amount numeric(19,4) NOT NULL DEFAULT 0,

  -- 对方单号 / 对方名称（快照）。
  external_reference varchar(160) NOT NULL DEFAULT '',
  counterparty varchar(160) NOT NULL DEFAULT '',
  memo varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 幂等键：同一批次内同一条上游记录只快照一次。
  UNIQUE(tenant_id, session_id, source_type, source_id),
  FOREIGN KEY(tenant_id, session_id) REFERENCES finance_bank_recon_session(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX finance_bank_recon_system_line_by_session
  ON finance_bank_recon_system_line(tenant_id, session_id, occurred_at, id);
CREATE INDEX finance_bank_recon_system_line_by_reference
  ON finance_bank_recon_system_line(tenant_id, external_reference)
  WHERE btrim(external_reference) <> '';

-- ---------------------------------------------------------------------------
-- 4. finance_bank_recon_match —— 匹配结果
-- ---------------------------------------------------------------------------
--
-- 一行 = 「银行流水行 X 与系统记录 Y 配成了一对」。
--
-- ⚠️ 一对一（银行流水 ↔ 系统记录）：
--   合并回款（12.2.1.1 的一笔收款核销多笔应收）是**核销（12.2.1.2）**的事，
--   不是对账的事 ⇒ 这里坚持「一行流水对一行系统记录」。
--   确实要一对多时，走**人工认领**（认领到具体单据）而不是自动匹配。
CREATE TABLE finance_bank_recon_match (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  statement_line_id uuid NOT NULL,
  system_line_id uuid NOT NULL,

  -- 命中这条匹配**用的是哪条规则**（可回溯：为什么这两行配成一对）。
  rule_code varchar(48) NOT NULL,
  rule_type text NOT NULL
    CHECK(rule_type IN ('REFERENCE_EXACT','COUNTERPARTY_AMOUNT','AMOUNT_DATE_WINDOW')),
  -- 命中的维度说明（人话）：如「单号相等 + 金额相等 + 日期相差 1 天」。
  match_basis varchar(300) NOT NULL DEFAULT '',
  -- 两边时刻相差的天数（DATE 精度的那一侧按整天算）。
  day_difference int NOT NULL DEFAULT 0,

  matched_by uuid NOT NULL,
  matched_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 一行流水只能被匹配一次；一条系统记录也只能被匹配一次
  --    （否则同一笔钱会被算两次，差额就永远平不了）。
  UNIQUE(tenant_id, statement_line_id),
  UNIQUE(tenant_id, system_line_id),
  FOREIGN KEY(tenant_id, session_id) REFERENCES finance_bank_recon_session(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, statement_line_id) REFERENCES finance_bank_recon_statement_line(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, system_line_id) REFERENCES finance_bank_recon_system_line(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX finance_bank_recon_match_by_session
  ON finance_bank_recon_match(tenant_id, session_id);

-- ---------------------------------------------------------------------------
-- 5. finance_bank_recon_unmatched —— 未匹配清单（当前态）
-- ---------------------------------------------------------------------------
--
-- 蓝图输出句之一：「匹配结果 + **未匹配清单** + 对账报告」。
--
-- 一行 = 一条**没配上**的记录，分两侧：
--   BANK   银行有、我方账上没有（银行已收未入账 / 银行已付未入账）
--   SYSTEM 我方账上有、银行账单上没有（我方已收银行未收 / 我方已付银行未付）
--
-- ⚠️ **状态机**：OPEN →（人工认领）→ CLAIMED ／ OPEN →（说明后排除）→ EXCLUDED。
--   CLAIMED / EXCLUDED 是**终态**（这条差异已被人工处理，不再出现在待办里）。
CREATE TABLE finance_bank_recon_unmatched (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  -- 哪一侧多出来的。
  side text NOT NULL CHECK(side IN ('BANK','SYSTEM')),

  -- 两侧各自的行（恰好一侧非空，由 CHECK 钉死）。
  statement_line_id uuid,
  system_line_id uuid,

  -- 事实（快照，金额恒正 + 方向两列）。
  occurred_at timestamptz NOT NULL,
  direction text NOT NULL CHECK(direction IN ('IN','OUT')),
  amount numeric(19,4) NOT NULL CHECK(amount > 0),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  counterparty varchar(160) NOT NULL DEFAULT '',
  external_reference varchar(160) NOT NULL DEFAULT '',
  memo varchar(500) NOT NULL DEFAULT '',

  -- 状态：OPEN 未处理 / CLAIMED 已认领 / EXCLUDED 已说明并排除。
  status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLAIMED','EXCLUDED')),

  -- 🔴 **认领到具体单据**（蓝图加粗句「未匹配项要能认领到具体单据」）：
  --    认领类型（受控词表）+ 认领的单据 id / 单号 + 谁认领的 + 何时 + 为什么。
  claimed_document_type text CHECK(claimed_document_type IS NULL OR claimed_document_type IN (
    'RECEIPT','PAYMENT_ORDER','SETTLEMENT','OTHER')),
  claimed_document_id uuid,
  claimed_document_no varchar(64) NOT NULL DEFAULT '',
  claimed_by uuid,
  claimed_at timestamptz,
  claim_reason varchar(500) NOT NULL DEFAULT '',

  -- 排除理由（EXCLUDED 必填）。
  exclude_reason varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, session_id) REFERENCES finance_bank_recon_session(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, statement_line_id) REFERENCES finance_bank_recon_statement_line(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, system_line_id) REFERENCES finance_bank_recon_system_line(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 两侧必须**恰好一侧**有值（side 说的是哪一侧多出来的）。
  CONSTRAINT finance_bank_recon_unmatched_side_consistent CHECK(
    (side = 'BANK' AND statement_line_id IS NOT NULL AND system_line_id IS NULL)
    OR (side = 'SYSTEM' AND system_line_id IS NOT NULL AND statement_line_id IS NULL)
  ),
  -- 🔴 认领态 ⇒ 认领到**具体单据**（类型 / id / 单号 / 人 / 时刻 / 理由）必须齐全。
  CONSTRAINT finance_bank_recon_unmatched_claim_consistent CHECK(
    (status = 'CLAIMED'
      AND claimed_document_type IS NOT NULL AND claimed_document_id IS NOT NULL
      AND btrim(claimed_document_no) <> '' AND claimed_by IS NOT NULL
      AND claimed_at IS NOT NULL AND btrim(claim_reason) <> '')
    OR (status <> 'CLAIMED'
      AND claimed_document_type IS NULL AND claimed_document_id IS NULL
      AND btrim(claimed_document_no) = '' AND claimed_by IS NULL AND claimed_at IS NULL
      AND btrim(claim_reason) = '')
  ),
  -- 🔴 排除态 ⇒ 必须写排除理由（不许无声无息地把一笔差异抹掉）。
  CONSTRAINT finance_bank_recon_unmatched_exclude_consistent CHECK(
    (status = 'EXCLUDED' AND btrim(exclude_reason) <> '')
    OR (status <> 'EXCLUDED' AND btrim(exclude_reason) = '')
  )
);

CREATE INDEX finance_bank_recon_unmatched_by_session
  ON finance_bank_recon_unmatched(tenant_id, session_id, status, side);
CREATE INDEX finance_bank_recon_unmatched_by_statement
  ON finance_bank_recon_unmatched(tenant_id, statement_line_id)
  WHERE statement_line_id IS NOT NULL;
CREATE INDEX finance_bank_recon_unmatched_by_system
  ON finance_bank_recon_unmatched(tenant_id, system_line_id)
  WHERE system_line_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 6. finance_bank_recon_claim —— 人工认领 / 说明（append-only 举证）
-- ---------------------------------------------------------------------------
--
-- 「可以多次认领、留痕不改写」：每一次认领或排除都**追加**一行。
-- 当前态在 `finance_bank_recon_unmatched` 上，历史在���张表。
CREATE TABLE finance_bank_recon_claim (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,
  unmatched_id uuid NOT NULL,

  -- 动作：CLAIM 认领到具体单据 / EXCLUDE 说明后排除。
  action text NOT NULL CHECK(action IN ('CLAIM','EXCLUDE')),

  -- 认领到的单据（EXCLUDE 时为空）。
  claimed_document_type text CHECK(claimed_document_type IS NULL OR claimed_document_type IN (
    'RECEIPT','PAYMENT_ORDER','SETTLEMENT','OTHER')),
  claimed_document_id uuid,
  claimed_document_no varchar(64) NOT NULL DEFAULT '',

  -- 说明（必填：认领为什么、排除为什么）。
  reason varchar(500) NOT NULL CHECK(btrim(reason) <> ''),

  claimed_by uuid NOT NULL,
  claimed_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, session_id) REFERENCES finance_bank_recon_session(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, unmatched_id) REFERENCES finance_bank_recon_unmatched(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 认领动作 ⇒ 必须指向具体单据；排除动作 ⇒ 不得带单据引用。
  CONSTRAINT finance_bank_recon_claim_action_consistent CHECK(
    (action = 'CLAIM'
      AND claimed_document_type IS NOT NULL AND claimed_document_id IS NOT NULL
      AND btrim(claimed_document_no) <> '')
    OR (action = 'EXCLUDE'
      AND claimed_document_type IS NULL AND claimed_document_id IS NULL
      AND btrim(claimed_document_no) = '')
  )
);

CREATE INDEX finance_bank_recon_claim_by_unmatched
  ON finance_bank_recon_claim(tenant_id, unmatched_id, claimed_at);

-- ---------------------------------------------------------------------------
-- 7. finance_bank_recon_rule —— 匹配规则
-- ---------------------------------------------------------------------------
--
-- 「输入」句之一：「匹配规则」。规则是**租户自己维护**的（表里不预置任何一行），
-- 租户第一次建对账批次时服务层会把**内置默认规则**写进这张表（`builtin = true`），
-- 之后租户可以改优先级、关掉不想要的、加自己的。
--
-- ⚠️ **规则类型是受控词表**，且**没有任何一种只看金额**（卷 12 硬约束）：
--   REFERENCE_EXACT      单号精确相等
--   COUNTERPARTY_AMOUNT  对手方规范化后相等
--   AMOUNT_DATE_WINDOW   金额 + 日期窗口 + 对手方不矛盾
--   三种都**同时**要求：金额相等（在 tolerance 内）+ 方向相同 + 日期在窗口内。
CREATE TABLE finance_bank_recon_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则编码（租户内唯一）。
  rule_code varchar(48) NOT NULL CHECK(rule_code ~ '^[A-Z0-9_-]{1,48}$'),
  rule_name varchar(120) NOT NULL CHECK(length(btrim(rule_name)) > 0),

  rule_type text NOT NULL
    CHECK(rule_type IN ('REFERENCE_EXACT','COUNTERPARTY_AMOUNT','AMOUNT_DATE_WINDOW')),
  -- 优先级：数字小 = 先试。
  priority int NOT NULL DEFAULT 100 CHECK(priority > 0),

  enabled boolean NOT NULL DEFAULT true,

  -- 日期窗口（天）：1 ≤ 窗口 ≤ 31。
  -- 🔴 上限 31 是刻意的：把窗口放到几百天，等于用「金额相同」冒充匹配。
  date_window_days int NOT NULL DEFAULT 3 CHECK(date_window_days BETWEEN 1 AND 31),
  -- 金额容差：匹配时允许的差额上限（默认 0 = 必须完全相等）。
  amount_tolerance numeric(19,4) NOT NULL DEFAULT 0 CHECK(amount_tolerance >= 0),
  -- 对手方是否必须完全一致（AMOUNT_DATE_WINDOW 规则用）。
  --   true  ⇒ 对手方必须规范化后相等
  --   false ⇒ 对手方**不得矛盾**（两边都非空且规范化后不等 ⇒ 不是候选）
  counterparty_required boolean NOT NULL DEFAULT false,

  -- 内置规则：可以被关掉 / 改优先级，**不能被删掉**（删掉会让「默认怎么配」这件事消失）。
  builtin boolean NOT NULL DEFAULT false,

  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_code)
);

CREATE INDEX finance_bank_recon_rule_by_priority
  ON finance_bank_recon_rule(tenant_id, enabled, priority);

-- ---------------------------------------------------------------------------
-- 8. finance_bank_recon_report —— 对账报告（生成时的快照）
-- ---------------------------------------------------------------------------
--
-- 「输出」句之一：「对账报告」。报告是**生成那一刻的快照**：
-- 之后再改流水 / 再跑匹配，旧报告仍读得到当时的口径。
CREATE TABLE finance_bank_recon_report (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  period_start date NOT NULL,
  period_end date NOT NULL,

  -- ---- 笔数 ----
  statement_line_count int NOT NULL DEFAULT 0 CHECK(statement_line_count >= 0),
  system_line_count int NOT NULL DEFAULT 0 CHECK(system_line_count >= 0),
  matched_count int NOT NULL DEFAULT 0 CHECK(matched_count >= 0),
  unmatched_bank_count int NOT NULL DEFAULT 0 CHECK(unmatched_bank_count >= 0),
  unmatched_system_count int NOT NULL DEFAULT 0 CHECK(unmatched_system_count >= 0),
  -- 已认领 / 已排除（人工处理掉的差异笔数）。
  claimed_count int NOT NULL DEFAULT 0 CHECK(claimed_count >= 0),
  excluded_count int NOT NULL DEFAULT 0 CHECK(excluded_count >= 0),

  -- ---- 金额（全部本币；一次对账只有一个币种 ⇒ 相加有意义）----
  matched_amount numeric(19,4) NOT NULL DEFAULT 0,
  statement_in_amount numeric(19,4) NOT NULL DEFAULT 0,
  statement_out_amount numeric(19,4) NOT NULL DEFAULT 0,
  system_in_amount numeric(19,4) NOT NULL DEFAULT 0,
  system_out_amount numeric(19,4) NOT NULL DEFAULT 0,
  unmatched_bank_in_amount numeric(19,4) NOT NULL DEFAULT 0,
  unmatched_bank_out_amount numeric(19,4) NOT NULL DEFAULT 0,
  unmatched_system_in_amount numeric(19,4) NOT NULL DEFAULT 0,
  unmatched_system_out_amount numeric(19,4) NOT NULL DEFAULT 0,

  -- ---- 余额调节（银行对账单 ⇄ 我方账面）----
  statement_opening_balance numeric(19,4) NOT NULL DEFAULT 0,
  statement_closing_balance numeric(19,4) NOT NULL DEFAULT 0,
  -- 我方账面余额：读不到就是 NULL（不是 0）。
  book_closing_balance numeric(19,4),
  -- 调节后余额 = 银行期末 + 银行已收未入账 − 银行已付未入账
  --             − 我方已收银行未收 + 我方已付银行未付
  adjusted_balance numeric(19,4),
  -- 差额 = 调节后余额 − 我方账面余额。差额为 0 ⇒ 账平了。
  difference numeric(19,4),

  -- 账面余额是否读得到（读不到 ⇒ 差额与调节后余额恒为 null，报告如实说「算不出」）。
  book_balance_available boolean NOT NULL DEFAULT false,

  generated_by uuid NOT NULL,
  generated_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个批次一份**当前**报告（重新生成 ⇒ 覆盖同一行；历史口径靠事件流与
  -- 报告里的 generated_at / generated_by 追溯）。
  UNIQUE(tenant_id, session_id),
  FOREIGN KEY(tenant_id, session_id) REFERENCES finance_bank_recon_session(tenant_id, id) ON DELETE CASCADE,

  -- 🔴 差额算得出来 ⇒ 必须有账面余额。
  CONSTRAINT finance_bank_recon_report_book_consistent CHECK(
    (book_balance_available = true AND book_closing_balance IS NOT NULL)
    OR (book_balance_available = false AND book_closing_balance IS NULL)
  )
);

-- ---------------------------------------------------------------------------
-- 9. finance_bank_recon_event —— 事件流（append-only 举证）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_bank_recon_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,

  event_type text NOT NULL CHECK(event_type IN (
    'SESSION_CREATED','STATEMENT_IMPORTED','MATCH_EXECUTED','UNMATCHED_CLAIMED',
    'UNMATCHED_EXCLUDED','REPORT_GENERATED','SESSION_RECONCILED')),
  summary varchar(300) NOT NULL DEFAULT '',
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  operator_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, session_id) REFERENCES finance_bank_recon_session(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX finance_bank_recon_event_by_session
  ON finance_bank_recon_event(tenant_id, session_id, occurred_at DESC, id);

-- ===========================================================================
-- 触发器：把纪律钉在 DB 层（服务层与 DB 各判一次，绕过服务层直连 SQL 也造不出假数据）
-- ===========================================================================

-- (a) 银行流水行 **append-only**：任何 UPDATE / DELETE 一律拒绝。
--
--     改流水等于改写外部凭据。正确做法是换一个对账批次重新导入。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_statement_line_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '银行流水是外部事实，不可 UPDATE / DELETE：%', OLD.id
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_statement_line_append_only
  BEFORE UPDATE OR DELETE ON finance_bank_recon_statement_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_statement_line_append_only();

-- (b) 认领记录 **append-only**：举证不许改写。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_claim_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '认领记录是追加式的（append-only），不可 UPDATE / DELETE：%', OLD.id
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_claim_append_only
  BEFORE UPDATE OR DELETE ON finance_bank_recon_claim
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_claim_append_only();

-- (c) 事件流 **append-only**。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '对账事件流是追加式的（append-only），不可 UPDATE / DELETE：%', OLD.id
    USING ERRCODE = 'check_violation';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_event_append_only
  BEFORE UPDATE OR DELETE ON finance_bank_recon_event
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_event_append_only();

-- (d) 对账批次的**状态机只能向前**：DRAFT → IMPORTED → MATCHED → RECONCILED。
--
--     反向跳（把已确认的批次改回 DRAFT）会让「已出的对账报告」失去意义。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_session_transition() RETURNS trigger AS $$
DECLARE
  rank_old int;
  rank_new int;
BEGIN
  rank_old := CASE OLD.status WHEN 'DRAFT' THEN 1 WHEN 'IMPORTED' THEN 2
                             WHEN 'MATCHED' THEN 3 ELSE 4 END;
  rank_new := CASE NEW.status WHEN 'DRAFT' THEN 1 WHEN 'IMPORTED' THEN 2
                             WHEN 'MATCHED' THEN 3 ELSE 4 END;

  IF rank_new < rank_old THEN
    RAISE EXCEPTION '对账批次 % 的状态不可回退：% → %', OLD.recon_no, OLD.status, NEW.status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 🔴 确认 ⇒ 必须已有对账报告（**报告是确认的前置条件**，不是确认之后的附赠品）。
  IF NEW.status = 'RECONCILED' AND NOT EXISTS (
    SELECT 1 FROM finance_bank_recon_report r
     WHERE r.tenant_id = NEW.tenant_id AND r.session_id = NEW.id
  ) THEN
    RAISE EXCEPTION '对账批次 % 确认前必须先生成对账报告', OLD.recon_no
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_session_transition_guard
  BEFORE UPDATE ON finance_bank_recon_session
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_session_transition();

-- (e) 银行流水行的**币种必须与批次一致**（一次对账只有一个币种 ⇒ 全部金额口径唯一）。
--
--     ⚠️ 这条闸必须写成**触发器**：Postgres 的 CHECK 约束不允许子查询，
--     而「币种 = 批次的币种」要查另一张表。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_statement_line_currency() RETURNS trigger AS $$
DECLARE
  sess finance_bank_recon_session%ROWTYPE;
BEGIN
  SELECT * INTO sess FROM finance_bank_recon_session
   WHERE tenant_id = NEW.tenant_id AND id = NEW.session_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '对账批次不存在或不属于当前租户：%', NEW.session_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF sess.currency <> NEW.currency THEN
    RAISE EXCEPTION '流水币种 % 与对账批次币种 % 不一致：一次对账只有一个币种',
      NEW.currency, sess.currency USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_statement_line_currency_guard
  BEFORE INSERT ON finance_bank_recon_statement_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_statement_line_currency();

-- (f) 匹配结果的两侧必须在**同一个批次**、**方向相同**、**金额相等（在容差内）**。
--
--     「金额相同即匹配」的禁令是**规则层**的；这里是**结果层**的兜底：
--     方向不同 / 金额差太远的匹配连写都写不进去。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_match_consistency() RETURNS trigger AS $$
DECLARE
  st finance_bank_recon_statement_line%ROWTYPE;
  sy finance_bank_recon_system_line%ROWTYPE;
  tol numeric(19,4);
BEGIN
  SELECT * INTO st FROM finance_bank_recon_statement_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.statement_line_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '银行流水不存在或不属于当前租户：%', NEW.statement_line_id
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT * INTO sy FROM finance_bank_recon_system_line
   WHERE tenant_id = NEW.tenant_id AND id = NEW.system_line_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '系统记录快照不存在或不属于当前租户：%', NEW.system_line_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF st.session_id <> NEW.session_id OR sy.session_id <> NEW.session_id THEN
    RAISE EXCEPTION '匹配的两侧必须属于同一个对账批次'
      USING ERRCODE = 'check_violation';
  END IF;

  IF st.direction <> sy.direction THEN
    RAISE EXCEPTION '匹配的两侧方向不一致（银行 % / 系统 %）：方向不同就不是同一笔',
      st.direction, sy.direction USING ERRCODE = 'check_violation';
  END IF;

  -- 容差取该条规则自己的容差（规则被删/改了就退化为 0 ⇒ 宁严勿松）。
  SELECT COALESCE(max(amount_tolerance), 0) INTO tol
    FROM finance_bank_recon_rule
   WHERE tenant_id = NEW.tenant_id AND rule_code = NEW.rule_code;

  IF abs(st.amount - sy.amount) > COALESCE(tol, 0) THEN
    RAISE EXCEPTION '匹配的两侧金额相差过大（银行 % / 系统 %，容差 %）',
      st.amount, sy.amount, COALESCE(tol, 0) USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_match_guard
  BEFORE INSERT ON finance_bank_recon_match
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_match_consistency();

-- (g) 未匹配项的**认领必须指向真实存在的单据**（同服务同库 ⇒ 可校验）。
--
--     「认领到具体单据」如果只是写个 id，那等于编事实。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_unmatched_claim_target() RETURNS trigger AS $$
BEGIN
  IF NEW.status = 'CLAIMED' THEN
    IF NEW.claimed_document_type = 'RECEIPT' THEN
      IF NOT EXISTS (SELECT 1 FROM finance_receipt r
                      WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.claimed_document_id) THEN
        RAISE EXCEPTION '认领目标不是本租户的收款单：%', NEW.claimed_document_id
          USING ERRCODE = 'check_violation';
      END IF;
    ELSIF NEW.claimed_document_type = 'PAYMENT_ORDER' THEN
      IF NOT EXISTS (SELECT 1 FROM finance_payment_order p
                      WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.claimed_document_id) THEN
        RAISE EXCEPTION '认领目标不是本租户的付款单：%', NEW.claimed_document_id
          USING ERRCODE = 'check_violation';
      END IF;
    ELSIF NEW.claimed_document_type = 'SETTLEMENT' THEN
      IF NOT EXISTS (SELECT 1 FROM finance_settlement s
                      WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.claimed_document_id) THEN
        RAISE EXCEPTION '认领目标不是本租户的结算单：%', NEW.claimed_document_id
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_unmatched_claim_guard
  BEFORE INSERT OR UPDATE ON finance_bank_recon_unmatched
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_unmatched_claim_target();

-- (h) 认领记录（append-only 表）的目标单据同样校验存在性。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_claim_target() RETURNS trigger AS $$
BEGIN
  IF NEW.action = 'CLAIM' THEN
    IF NEW.claimed_document_type = 'RECEIPT' THEN
      IF NOT EXISTS (SELECT 1 FROM finance_receipt r
                      WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.claimed_document_id) THEN
        RAISE EXCEPTION '认领目标不是本租户的收款单：%', NEW.claimed_document_id
          USING ERRCODE = 'check_violation';
      END IF;
    ELSIF NEW.claimed_document_type = 'PAYMENT_ORDER' THEN
      IF NOT EXISTS (SELECT 1 FROM finance_payment_order p
                      WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.claimed_document_id) THEN
        RAISE EXCEPTION '认领目标不是本租户的付款单：%', NEW.claimed_document_id
          USING ERRCODE = 'check_violation';
      END IF;
    ELSIF NEW.claimed_document_type = 'SETTLEMENT' THEN
      IF NOT EXISTS (SELECT 1 FROM finance_settlement s
                      WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.claimed_document_id) THEN
        RAISE EXCEPTION '认领目标不是本租户的结算单：%', NEW.claimed_document_id
          USING ERRCODE = 'check_violation';
      END IF;
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_claim_target_guard
  BEFORE INSERT ON finance_bank_recon_claim
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_claim_target();

-- (i) 对账报告与批次的一致性：报告的期间 / 币种必须与批次一致。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_report_consistency() RETURNS trigger AS $$
DECLARE
  sess finance_bank_recon_session%ROWTYPE;
BEGIN
  SELECT * INTO sess FROM finance_bank_recon_session
   WHERE tenant_id = NEW.tenant_id AND id = NEW.session_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '对账批次不存在或不属于当前租户：%', NEW.session_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF sess.currency <> NEW.currency
     OR sess.period_start <> NEW.period_start
     OR sess.period_end <> NEW.period_end THEN
    RAISE EXCEPTION '对账报告的币种 / 期间必须与对账批次一致'
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_report_guard
  BEFORE INSERT OR UPDATE ON finance_bank_recon_report
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_report_consistency();

-- (j) 系统记录快照的**上游记录必须真实存在**（同服务同库 ⇒ 可校验）。
CREATE OR REPLACE FUNCTION guard_finance_bank_recon_system_line_source() RETURNS trigger AS $$
BEGIN
  IF NEW.source_type = 'RECEIPT' THEN
    IF NOT EXISTS (SELECT 1 FROM finance_receipt r
                    WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.source_id) THEN
      RAISE EXCEPTION '收款单不存在或不属于当前租户：%', NEW.source_id
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.source_type = 'PAYMENT_ORDER' THEN
    IF NOT EXISTS (SELECT 1 FROM finance_payment_order p
                    WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.source_id) THEN
      RAISE EXCEPTION '付款单不存在或不属于当前租户：%', NEW.source_id
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.source_type = 'SETTLEMENT_LINE' THEN
    IF NOT EXISTS (SELECT 1 FROM finance_settlement_line l
                    WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.source_id) THEN
      RAISE EXCEPTION '结算明细行不存在或不属于当前租户：%', NEW.source_id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER finance_bank_recon_system_line_source_guard
  BEFORE INSERT ON finance_bank_recon_system_line
  FOR EACH ROW EXECUTE FUNCTION guard_finance_bank_recon_system_line_source();

-- ===========================================================================
-- 多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'finance_bank_recon_session','finance_bank_recon_statement_line',
    'finance_bank_recon_system_line','finance_bank_recon_match',
    'finance_bank_recon_unmatched','finance_bank_recon_claim',
    'finance_bank_recon_rule','finance_bank_recon_report','finance_bank_recon_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_session TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_session TO starcon_app;
-- 银行流水是外部事实：运行角色只给 SELECT + INSERT。
GRANT SELECT,INSERT ON finance_bank_recon_statement_line TO finance_app;
GRANT SELECT,INSERT ON finance_bank_recon_statement_line TO starcon_app;
REVOKE UPDATE,DELETE ON finance_bank_recon_statement_line FROM finance_app;
REVOKE UPDATE,DELETE ON finance_bank_recon_statement_line FROM starcon_app;
-- 系统记录快照是读模型：允许重建（UPSERT / DELETE）。
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_system_line TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_system_line TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_match TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_match TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_unmatched TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_unmatched TO starcon_app;
-- 认领记录与事件流是**追加式**举证：运行角色只给 SELECT + INSERT。
GRANT SELECT,INSERT ON finance_bank_recon_claim TO finance_app;
GRANT SELECT,INSERT ON finance_bank_recon_claim TO starcon_app;
REVOKE UPDATE,DELETE ON finance_bank_recon_claim FROM finance_app;
REVOKE UPDATE,DELETE ON finance_bank_recon_claim FROM starcon_app;
GRANT SELECT,INSERT ON finance_bank_recon_event TO finance_app;
GRANT SELECT,INSERT ON finance_bank_recon_event TO starcon_app;
REVOKE UPDATE,DELETE ON finance_bank_recon_event FROM finance_app;
REVOKE UPDATE,DELETE ON finance_bank_recon_event FROM starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_rule TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_rule TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_report TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_bank_recon_report TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM finance_app;
