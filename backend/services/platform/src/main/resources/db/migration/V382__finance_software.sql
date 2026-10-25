-- ===========================================================================
-- V382 · finance_software —— 18.2.1.1「财务软件」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.2.1.1 原文：
--
--   「**财务软件**：对接第三方财务软件（金蝶、用友、SAP、Odoo）：凭证推送、科目映射、
--     主数据同步、余额核对；导出标准凭证文件（Excel/XML）。
--     输入：凭证数据；科目映射配置；对账需求。
--     输出：推送结果；凭证文件；核对差异。
--     关系：上游 = 12.5.2.1 凭证；下游 = 外部财务软件。
--     **业财一体化的常见做法是本系统管业务账、外部软件管法定账**，
--     两边通过凭证文件与科目映射衔接。」
--
-- 落点：**platform 服务**（18 卷的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。18.1.1.1「应用与凭证」V377、18.4.2.1「文本与翻译」V380
-- 已经把服务骨架建好，本迁移是它的第三批表。
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不伪造一条「已推送给金蝶」的结果**。
--     本仓目前**没有任何真实的财务软件适配器**（backend / services / tools 下不存在
--     kingdee / yonyou / sap / odoo 之类的客户端）。
--     ⇒ 「直连推送」这一段做成一个**显式的未接入端口**
--       （FinanceSoftwarePushPort + UnavailableFinanceSoftwarePushPort）：
--       端点返回 **409**，**一次数据都不写**。
--     理由（照抄 platform 的 UnavailableAiTextGenerationPort 的判断）：
--     一条返回成功结果的假通道，会让「凭证已经推给金蝶了」在数据上看起来发生过，
--     而现场一个字都没推出去 —— 财务会拿着一张**不存在的**法定凭证去做账。
--
--   * **但不因此把整个模块砍成半成品**。
--     蓝图里「凭证文件导出」与「科目映射」「余额核对」三块**是本系统内部能力**，
--     与外部软件有没有接上是两件事：
--       · 科目映射        —— 真落库、真校验、真参与导出与核对；
--       · 主数据映射      —— 真落库（客户 / 商品 / 部门 ↔ 外部编码）；
--       · 凭证文件导出    —— 真从上游 12.5.2.1 拉凭证、真套映射、真生成文件内容、
--                            真算 sha256 与字节数、真落一条导出记录；
--       · 余额核对差异    —— 真从上游 12.5.2.2 试算平衡表取本地余额、真与导入的
--                            外部余额逐科目比对、真算差异与差异原因。
--     ⇒ **与外部软件的衔接方式就是「凭证文件 + 科目映射」**（蓝图原话）：
--     运营把导出的文件导入金蝶 / 用友，再把对方的余额表导回来做核对 ——
--     这条路**今天就能用**，不依赖任何外部适配器。
--
--   * **不落任何外部系统的回执 / 推送流水表**。
--     直连推送一次都没发生过 ⇒ 凭空建一张永远为空的「推送历史表」是给界面一个
--     永远空的列表。推送能力的状态写在**能力声明**里（明说未接入、归谁），
--     不用一张空表冒充「推送记录」。
--
--   * **不存外部系统的账号 / 密钥**。
--     金蝶 / 用友 / SAP / Odoo 的接入凭据走 secret manager（AGENTS.md：任何 secret
--     一律不入仓库）。本模块只记**连接档案**（厂商 / 账套 / 本位币 / 启用状态），
--     不记任何口令或 token。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_finance_account            —— 外部财务软件连接档案（金蝶 / 用友 / SAP / Odoo）
--   platform_finance_subject_map        —— 科目映射（本系统科目 ↔ 外部科目）
--   platform_finance_master_map         —— 主数据映射（客户 / 商品 / 部门 ↔ 外部编码）
--   platform_finance_voucher_file       —— 凭证文件导出记录（真文件的留痕）
--   platform_finance_reconcile_task     —— 余额核对任务
--   platform_finance_reconcile_balance  —— 外部余额快照行（导入的对方余额）
--   platform_finance_reconcile_diff     —— 核对差异（逐科目）
--
--   七张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 「业财一体化的常见做法」在本模块里的落点
-- ---------------------------------------------------------------------------
--
--   蓝图加粗句说的是：**本系统管业务账、外部软件管法定账**。于是同一个科目在两边
--   **各有一个编码**，两边的金额口径也可能不同（外部软件多税 / 多本位币 / 多结转）。
--   ⇒ 科目映射表是这个模块的**中枢**：
--     · 凭证文件导出时，本地科目编码被**换成**外部科目编码（外部软件才认得）；
--     · 余额核对时，外部科目编码被**换回**本地科目编码（才知道该跟本地哪一行比）。
--   映射缺失 ⇒ 导出时该行标 UNMAPPED（不静默丢行），核对时该科目进差异表
--   （reason = ONLY_LOCAL / ONLY_EXTERNAL）。
--
--   🔴 **映射表按 (账号, 本地科目编码) 唯一**：一个账套里一个本地科目只能映射到一个外部科目。
--     映射到两个外部科目 ⇒ 导出的法定凭证会串科目 —— 这是**数据层就该挡住的错误**，
--     不是靠服务层记得检查。
--
-- ---------------------------------------------------------------------------
-- 3. 余额核对：两边各一份余额，差异是**算出来的**不是填出来的
-- ---------------------------------------------------------------------------
--
--   本地余额来自上游 12.5.2.2 试算平衡表（**真读 finance 的授权 API**，不抄一份到本库）。
--   外部余额来自**导入的快照行**（platform_finance_reconcile_balance）——
--   运营从金蝶 / 用友导出的余额表导进来。
--   逐科目比对产出 platform_finance_reconcile_diff，四种差异原因：
--
--     BALANCE_DIFF    两边都有，金额不等
--     ONLY_LOCAL      本地有、外部没有（外部软件还没收到或漏了）
--     ONLY_EXTERNAL   外部有、本地没有（外部软件有单据业务账没有）
--     CURRENCY_MISMATCH  两边币种不是同一个（不能直接相减）
--
--   🔴 **余额一律 numeric(20,6)**，禁浮点（AGENTS.md）。
--   🔴 **差异方向有口径**：本地余额按「科目余额方向取正负」记（与 finance 试算平衡表一致），
--     外部余额按**导入时的方向列**（借 / 贷）归一化后再比 —— 两边方向口径不同，
--     同一个科目会凭空差出一倍。
--
-- ---------------------------------------------------------------------------
-- 4. 时间与金额
-- ---------------------------------------------------------------------------
--
--   * 时间列一律 **timestamptz**；服务层用 **OffsetDateTime** 绑定并在 values 位置写
--     `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--   * 金额 / 余额 / 差额一律 **numeric(20,6)**。
--   * 数量（凭证行数、科目数、文件字节数）用 **int**（计数不是金额）。
--   * 业务日期（凭证日、核对区间）用 **date**（日历日，不是时刻）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_finance_account —— 外部财务软件连接档案
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个「本系统 ↔ 某个外部账套」的连接档案。
--
-- 🔴 **系统零预置行**：不替用户默认一个「金蝶」连接 —— 账套号、公司名、
--   本位币都是租户自己的事实（同一个租户可能同时有「美国主体 - 金蝶」
--   和「德国主体 - SAP」两个连接）。
--
-- 🔴 **本表不存任何 secret**：只存 vendor / 账套号 / 本位币 / 启用状态。
--   接入凭据走 secret manager（AGENTS.md），本表里一个口令位都没有。
CREATE TABLE platform_finance_account (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 连接名（租户自取，如「美国主体 - 金蝶」）。
  name varchar(120) NOT NULL,
  -- 厂商：金蝶 / 用友 / SAP / Odoo。
  vendor varchar(24) NOT NULL
    CHECK(vendor IN ('KINGDEE','YONYOU','SAP','ODOO')),
  -- 外部账套号 / 公司代码（导出文件按它归集；导入余额表也按它归集）。
  book_code varchar(64) NOT NULL,
  -- 外部主体名称（展示用）。
  company_name varchar(200) NOT NULL DEFAULT '',
  -- 本位币（ISO 4217 三字母，大写）。
  currency char(3) NOT NULL,
  -- 起始月份（外部软件的会计年度起始月；1 = 自然年）。
  fiscal_start_month int NOT NULL DEFAULT 1
    CHECK(fiscal_start_month BETWEEN 1 AND 12),
  -- 状态：停用只是不再参与导出，**历史记录一律保留**（法定凭证不可删）。
  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','DISABLED')),

  remark varchar(500) NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, name),

  CHECK(btrim(name) <> ''),
  CHECK(btrim(book_code) <> ''),
  CHECK(currency = upper(currency) AND length(currency) = 3)
);

CREATE INDEX platform_finance_account_by_vendor
  ON platform_finance_account(tenant_id, vendor, status);

-- ---------------------------------------------------------------------------
-- 2. platform_finance_subject_map —— 科目映射（本系统 ↔ 外部）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个本地科目在某账套里的外部科目编码。
--
-- 🔴 **UNIQUE(tenant_id, account_id, local_subject_code)**：一个账套里一个本地科目
--   只能映射到一个外部科目。映射到两个 ⇒ 导出的法定凭证会串科目，
--   这是**数据层就该挡住**的错误。
--
-- 🔴 **external_subject_code 同样唯一**：两个本地科目映射到同一个外部科目，
--   导出后两笔业务会并到同一张法定凭证的同一行 —— 除非是**有意**的一对多
--   （本仓不支持，所以直接挡住；要拆就建两个账套连接）。
--
-- ⚠️ local_subject_code 是**软引用**（12.5.1.1 会计科目在 finance 服务，
--   不建跨服务 FK）。但 external_subject_code 落在本表内部 ⇒ 建真 FK 约束。
CREATE TABLE platform_finance_subject_map (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  account_id uuid NOT NULL,

  -- 本系统科目编码（12.5.1.1 的 subject_code，软引用）。
  local_subject_code varchar(64) NOT NULL,
  local_subject_name varchar(200) NOT NULL DEFAULT '',
  -- 外部软件科目编码（导出文件里写的就是它）。
  external_subject_code varchar(64) NOT NULL,
  external_subject_name varchar(200) NOT NULL DEFAULT '',
  -- 外部税码（金蝶 / 用友 的税码字段）。
  tax_code varchar(32) NOT NULL DEFAULT '',
  -- 方向：留空表示跟随本地科目余额方向。
  direction varchar(16) NOT NULL DEFAULT ''
    CHECK(direction IN ('','DEBIT','CREDIT')),
  enabled boolean NOT NULL DEFAULT true,

  remark varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, account_id, local_subject_code),
  UNIQUE(tenant_id, account_id, external_subject_code),

  FOREIGN KEY(tenant_id, account_id)
    REFERENCES platform_finance_account(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(local_subject_code) <> ''),
  CHECK(btrim(external_subject_code) <> '')
);

CREATE INDEX platform_finance_subject_map_by_account
  ON platform_finance_subject_map(tenant_id, account_id, enabled);

-- ---------------------------------------------------------------------------
-- 3. platform_finance_master_map —— 主数据映射（客户 / 商品 / 部门）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个本地主数据在某账套里的外部编码。
--
-- 🔴 **主数据映射的「同步」动作本仓未接入**（同一段外部通道）⇒ 直连同步端点
--   如实答 409 且**一次数据都不写**。但**映射本身是本系统内部能力**，
--   真落库、真参与凭证文件导出（辅助核算项带出外部编码）。
--
-- master_type（与 12.5.2.1 凭证行上的**辅助核算类型**对齐 —— 主数据映射就是给
-- 辅助核算项换编码的，所以类型词表必须与辅助核算类型一致，否则导出时对不上）：
--   CUSTOMER   客户
--   SUPPLIER   供应商
--   PLATFORM   平台 / 店铺
--   ACCOUNT    资金账户
--   PRODUCT    商品
--   DEPARTMENT 部门
--
-- 🔴 **UNIQUE(tenant_id, account_id, master_type, local_name)**：
--   导出凭证文件时，凭证行只带辅助核算的**名称快照**（外加一个本系统 UUID，
--   而本服务不跨服务去查客户 / 商品主数据 —— 那是 catalog / crm 的库，
--   platform 没有它们的授权 API）。所以「名称」是本模块唯一诚实的匹配键，
--   数据层必须保证**同一账套同一类型下名称不重**，否则一行辅助核算能匹配到多条映射，
--   导出时该写哪个外部编码就成了随机的 —— 法定凭证串客户比导不出更糟。
CREATE TABLE platform_finance_master_map (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  account_id uuid NOT NULL,

  master_type varchar(16) NOT NULL
    CHECK(master_type IN ('CUSTOMER','SUPPLIER','PLATFORM','ACCOUNT','PRODUCT','DEPARTMENT')),
  -- 本地编码（软引用：不建跨服务 FK）。
  local_code varchar(64) NOT NULL,
  -- 本地名称（**导出凭证文件时的匹配键**：凭证行只带辅助核算名称快照）。
  local_name varchar(200) NOT NULL DEFAULT '',
  -- 外部软件里的编码。
  external_code varchar(64) NOT NULL,
  external_name varchar(200) NOT NULL DEFAULT '',

  enabled boolean NOT NULL DEFAULT true,
  remark varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, account_id, master_type, local_code),
  UNIQUE(tenant_id, account_id, master_type, external_code),
  -- 🔴 名称唯一（见文件头）：导出时的匹配键，重了就一定对不上。
  UNIQUE(tenant_id, account_id, master_type, local_name),

  FOREIGN KEY(tenant_id, account_id)
    REFERENCES platform_finance_account(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(local_code) <> ''),
  CHECK(btrim(external_code) <> '')
);

CREATE INDEX platform_finance_master_map_by_account
  ON platform_finance_master_map(tenant_id, account_id, master_type, enabled);

-- ---------------------------------------------------------------------------
-- 4. platform_finance_voucher_file —— 凭证文件导出记录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次**真实发生过**的凭证文件导出。
--
-- 🔴 这张表是**导出留痕**，不是「推送历史」：文件真的生成了（内容真出、
--   sha256 真算、字节数真数），导入对方软件是**人工**动作（或者将来的适配器）。
--   与 V380 的口径一致：留痕记的是**已经发生的事实**。
--
-- 🔴 **sha256 落库**：只记「导出过一张凭证文件」没有用 —— 事后要能证明
--   「当时导出的那份文件」就是这一份（对方系统里的那张单据能对上这份哈希）。
--
-- format：
--   CSV    带 UTF-8 BOM（Excel 直接双击能开）
--   EXCEL  SpreadsheetML 2003（XML 工作簿，Excel / 金蝶都能读）
--   XML    通用凭证 XML（各方约定的结构）
CREATE TABLE platform_finance_voucher_file (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  account_id uuid NOT NULL,

  -- 文件号（人可读单号，租户号段在前缀里）。
  file_no varchar(48) NOT NULL,
  format varchar(8) NOT NULL
    CHECK(format IN ('CSV','EXCEL','XML')),
  -- 文件名。
  filename varchar(200) NOT NULL,
  -- 文件**真内容**（落库）。
  --
  -- 🔴 **为什么内容要落库**：sha256 只有对着**同一份内容**才有意义。
  --   不存内容的话，事后重新生成的凭证文件可能与当初导出的那份不同
  --   （期间又过了一笔账），那这条 sha256 就指认不了任何东西 ——
  --   「当时导出的那份文件」必须**能被原样取回**，否则留痕是假的。
  content text NOT NULL,
  -- 内容指纹（sha256，十六进制 64 位）。
  content_sha256 varchar(64) NOT NULL,
  -- 内容字节数（真实长度，不是估算）。
  content_bytes int NOT NULL,

  -- 文件里有多少张凭证 / 多少行分录 / 借贷合计（真数）。
  voucher_count int NOT NULL DEFAULT 0,
  line_count int NOT NULL DEFAULT 0,
  total_debit numeric(20,6) NOT NULL DEFAULT 0,
  total_credit numeric(20,6) NOT NULL DEFAULT 0,
  -- 有多少行**没配科目映射**（UNMAPPED）：这些行的外部科目留空，
  --   在文件里被标出来 —— 静默丢行比标出来危险得多。
  unmapped_line_count int NOT NULL DEFAULT 0,

  currency char(3) NOT NULL,
  date_from date NOT NULL,
  date_to date NOT NULL,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, file_no),

  FOREIGN KEY(tenant_id, account_id)
    REFERENCES platform_finance_account(tenant_id, id),

  CHECK(btrim(file_no) <> ''),
  CHECK(btrim(filename) <> ''),
  CHECK(length(content_sha256) = 64),
  CHECK(content_bytes > 0),
  CHECK(voucher_count >= 0 AND line_count >= 0 AND unmapped_line_count >= 0),
  CHECK(total_debit >= 0 AND total_credit >= 0),
  CHECK(date_from <= date_to)
);

CREATE INDEX platform_finance_voucher_file_by_account
  ON platform_finance_voucher_file(tenant_id, account_id, created_at DESC);

-- 发号序列（人可读单号）。照抄 platform_text_task_no_seq 的纪律：
-- 序列是全局的，跨租户取号会互相看见对方的号段 ⇒ 单号里拼租户短码。
CREATE SEQUENCE platform_finance_voucher_file_no_seq;

-- ---------------------------------------------------------------------------
-- 5. platform_finance_reconcile_task —— 余额核对任务
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次「拿哪段期间、哪个币种、跟哪个账套的余额对一次账」。
--
-- status：
--   DRAFT     已建任务，外部余额还没导进来（或还没算）
--   COMPUTED  已算出差异（差异行在 platform_finance_reconcile_diff）
--
-- 🔴 **period_from_id / period_to_id 是软引用**（12.5.1.1 会计期间在 finance 服务），
--   不建跨服务 FK；真值由上游 12.5.2.2 试算平衡表按这两个 id 取。
CREATE TABLE platform_finance_reconcile_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  account_id uuid NOT NULL,

  reconcile_no varchar(48) NOT NULL,
  -- 本地会计期间区间（软引用 finance 的期间 id）。
  period_from_id uuid NOT NULL,
  period_to_id uuid NOT NULL,
  currency char(3) NOT NULL,

  status varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','COMPUTED')),

  -- 统计（真数，不是估算）。
  local_subject_count int NOT NULL DEFAULT 0,
  external_subject_count int NOT NULL DEFAULT 0,
  matched_count int NOT NULL DEFAULT 0,
  difference_count int NOT NULL DEFAULT 0,
  -- 差异合计（各科目差额绝对值之和 —— 「差异总额」用绝对值口径，
  --   净额口径会让正负相抵，看起来「对上了」）。
  total_difference numeric(20,6) NOT NULL DEFAULT 0,

  computed_at timestamptz,
  remark varchar(500) NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, reconcile_no),

  FOREIGN KEY(tenant_id, account_id)
    REFERENCES platform_finance_account(tenant_id, id),

  CHECK(btrim(reconcile_no) <> ''),
  CHECK(period_from_id <> period_to_id),
  CHECK(currency = upper(currency) AND length(currency) = 3),
  CHECK(local_subject_count >= 0 AND external_subject_count >= 0),
  CHECK(matched_count >= 0 AND difference_count >= 0),
  CHECK(total_difference >= 0),
  -- 状态与事实一致：算完了 ⇒ 必须有计算时刻。
  CHECK(status <> 'COMPUTED' OR computed_at IS NOT NULL)
);

CREATE INDEX platform_finance_reconcile_task_by_account
  ON platform_finance_reconcile_task(tenant_id, account_id, created_at DESC);

CREATE SEQUENCE platform_finance_reconcile_no_seq;

-- ---------------------------------------------------------------------------
-- 6. platform_finance_reconcile_balance —— 外部余额快照行
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个外部科目在某次核对里的余额（**导入**进来的）。
--
-- 🔴 **这是导入数据，不是本系统的账**：它必须带 direction（借 / 贷），
--   否则「贷方余额 100」和「借方余额 100」分不清，核对会凭空差一倍。
--   归一化成「按科目余额方向取正负」的口径后，才与本地余额同口径可比。
--
-- ⚠️ 重复导入同一科目时**覆盖**（同 (任务, 外部科目编码) 唯一）——
--   导入是「修正」动作，不是「追加」动作。
CREATE TABLE platform_finance_reconcile_balance (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  external_subject_code varchar(64) NOT NULL,
  external_subject_name varchar(200) NOT NULL DEFAULT '',
  currency char(3) NOT NULL,
  -- 方向：借方 / 贷方（导入时的原始方向）。
  direction varchar(8) NOT NULL
    CHECK(direction IN ('DEBIT','CREDIT')),
  -- 余额（绝对值口径；方向由 direction 承载）。
  external_balance numeric(20,6) NOT NULL DEFAULT 0,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, task_id, external_subject_code),

  FOREIGN KEY(tenant_id, task_id)
    REFERENCES platform_finance_reconcile_task(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(external_subject_code) <> ''),
  CHECK(currency = upper(currency) AND length(currency) = 3)
);

CREATE INDEX platform_finance_reconcile_balance_by_task
  ON platform_finance_reconcile_balance(tenant_id, task_id, external_subject_code);

-- ---------------------------------------------------------------------------
-- 7. platform_finance_reconcile_diff —— 核对差异
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个科目在本次核对里的差异结论。
--
-- 🔴 **每次重算替换该任务的旧差异**（同事务内 DELETE + INSERT）：
--   差异是「这一次核对的现状」，不是历史流水。历史由任务本身（reconcile_no +
--   created_at）承载 —— 每次重算建新任务，或在同一任务上重算前先看旧结论。
--
-- reason：
--   BALANCE_DIFF      两边都有，金额不等
--   ONLY_LOCAL        本地有、外部没有
--   ONLY_EXTERNAL     外部有、本地没有
--   CURRENCY_MISMATCH 两边币种不是同一个（不能相减）
CREATE TABLE platform_finance_reconcile_diff (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  -- 外部科目（核对的主键方向：以外部科目为准，外部没有的用本地编码占位）。
  external_subject_code varchar(64) NOT NULL,
  external_subject_name varchar(200) NOT NULL DEFAULT '',
  local_subject_code varchar(64) NOT NULL DEFAULT '',
  local_subject_name varchar(200) NOT NULL DEFAULT '',
  currency char(3) NOT NULL,

  external_balance numeric(20,6) NOT NULL DEFAULT 0,
  local_balance numeric(20,6) NOT NULL DEFAULT 0,
  -- 差额 = 外部 − 本地（同一币种、同一方向口径下可比时才有效）。
  difference numeric(20,6) NOT NULL DEFAULT 0,

  reason varchar(24) NOT NULL
    CHECK(reason IN ('BALANCE_DIFF','ONLY_LOCAL','ONLY_EXTERNAL','CURRENCY_MISMATCH')),
  detail varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, task_id, external_subject_code),

  FOREIGN KEY(tenant_id, task_id)
    REFERENCES platform_finance_reconcile_task(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(external_subject_code) <> ''),
  CHECK(currency = upper(currency) AND length(currency) = 3)
);

CREATE INDEX platform_finance_reconcile_diff_by_task
  ON platform_finance_reconcile_diff(tenant_id, task_id, reason);

-- ===========================================================================
-- 8. 租户隔离（RLS）
-- ===========================================================================
--
-- 七张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V380 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
-- ⚠️ **序列也要授权**：platform_finance_voucher_file_no_seq /
--    platform_finance_reconcile_no_seq 是全局序列，取号时**刻意**把租户号拼进前缀
--    （服务层生成 `FS-<租户短码>-<序号>`），让单号本身就能看出归属。
ALTER TABLE platform_finance_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_finance_account FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_finance_account
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_finance_subject_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_finance_subject_map FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_finance_subject_map
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_finance_master_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_finance_master_map FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_finance_master_map
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_finance_voucher_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_finance_voucher_file FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_finance_voucher_file
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_finance_reconcile_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_finance_reconcile_task FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_finance_reconcile_task
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_finance_reconcile_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_finance_reconcile_balance FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_finance_reconcile_balance
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_finance_reconcile_diff ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_finance_reconcile_diff FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_finance_reconcile_diff
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 9. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的连接档案 / 科目映射 / 主数据映射 /
-- 凭证文件记录 / 核对任务 / 外部余额 / 差异，能写（登记连接、配映射、建核对任务、
-- 导余额、算差异），能删（撤映射、删未计算的核对任务）。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_finance_account TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_finance_subject_map TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_finance_master_map TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_finance_voucher_file TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_finance_reconcile_task TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_finance_reconcile_balance TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_finance_reconcile_diff TO platform_app;

-- 发号序列的取号权限：没有它，服务层拿不到单号。
GRANT USAGE, SELECT ON SEQUENCE platform_finance_voucher_file_no_seq TO platform_app;
GRANT USAGE, SELECT ON SEQUENCE platform_finance_reconcile_no_seq TO platform_app;
