-- ===========================================================================
-- V380 · text_translation —— 18.4.2.1「文本与翻译」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.4.2.1 原文：
--
--   「**AI 文本能力**：多语言翻译（标题/描述/客服话术，支持保留品牌词与专业术语）、
--     标题优化与生成、卖点提炼、违禁词规避建议、客服回复草稿。
--     输入：源文本；目标语言；风格要求；术语表。
--     输出：翻译/生成结果 + 置信度提示 + 可人工编辑。
--     关系：上游 = 3.4.3 多语言、4.2.1.1 编辑器、15.1.1.2 发送；下游 = 无（结果需人工确认）。
--     **翻译结果必须人工确认后发布**，直接发机器翻译是品牌风险。」
--
-- 落点：**platform 服务**（18 卷的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。18.1.1.1「应用与凭证」已用 V377 建好服务骨架，本迁移是它的
-- 第二批表。
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不伪造一条「已翻译成功」的结果**。
--     本仓目前**没有任何真实的 AI / LLM 供应商适配器**（backend / services / tools 下不存在
--     openai / anthropic / qwen / deepseek 之类的客户端）。
--     ⇒ 「翻译 / 生成」这一段做成一个**显式的未接入端口**
--       （AiTextGenerationPort + UnavailableAiTextGenerationPort）：
--       端点返回 **409**，**一次数据都不写**。
--     理由（照抄 crm 的 UnavailablePlatformMessageSendPort 的判断）：
--     一条返回成功结果的假通道，会让「文案已经翻译好了」在数据上看起来发生过，
--     而现场一个字都没翻译出来 —— 运营会拿着一条不存在的译文去发版。
--
--   * **不把确定性检查也一起砍掉**。
--     术语保护、占位符完整性、违禁词命中、长度/字符集校验**都不依赖任何 AI** ——
--     它们是纯规则，真实实现、真实生效、真实拦得住。
--     本文件里落的就是这部分：**规则** + **词表** + **置信度的计算依据**。
--
--   * **不把「人工确认」做成建议**。
--     蓝图加粗句「翻译结果必须人工确认后发布」落到**状态机 + 数据库约束**上：
--     未经 CONFIRMED 的任务**取不走**（见 platform_text_task 的 CHECK 与服务层的发布闸）。
--
--   * **不落任何金额**。翻译服务不涉及单价 / 费用 / 结算（那是 18.1/18.2 的商业条款）。
--     测试用 information_schema 断言本批表**无金额语义列**。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_text_glossary    —— 术语表（品牌词 / 专业术语 / 商品专名；**翻译时必须保护**）
--   platform_text_banned_term —— 违禁词表（租户可配；**规避建议的事实依据**）
--   platform_text_rule        —— 长度与字符集规则（内容类型 × 目标语言）
--   platform_text_template    —— 客服回复草稿模板（**封闭变量词表**）
--   platform_text_task        —— 翻译任务（源文本 / 目标语言 / 风格要求 / 状态机）
--   platform_text_draft       —— 译文草稿版本（**人工可编辑** + 置信度 + 置信度依据）
--   platform_text_check       —— 确定性检查结果（每次评估一行一条）
--
--   七张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 「内置词表」与「租户词表」的分工（照抄 crm ReplyWordingChecker 的纪律）
-- ---------------------------------------------------------------------------
--
--   * **内置**（语言层面的合规红线：绝对化用语、医疗功效宣称、虚假承诺、导流联系方式、
--     诱导好评）—— 写死在**服务层的规则**里，不落库。
--     理由（crm 的原话）：内置词表描述的是**语言层面**的红线，与租户、店铺、平台无关；
--     它是**规则**，不是数据源。
--   * **租户可配**（品牌专名、专业术语、店铺自己的违禁词、长度与字符集上限）—— **落库**，
--     是**数据**，服务层只读不写死。
--
--   ⇒ 本文件里 `platform_text_banned_term` / `platform_text_glossary` / `platform_text_rule`
--     三张表都是**租户数据**，**系统零预置行**：不替用户默认一套词表或长度上限。
--
-- ---------------------------------------------------------------------------
-- 3. 置信度提示：为什么它不是一个拍脑袋的数
-- ---------------------------------------------------------------------------
--
--   蓝图要求输出「置信度提示」。一个凭空给分、或直接给一个模型自报分的数字，
--   运营没法据此决定「这条要不要多看两眼」。
--   ⇒ 本模块的置信度是**四个可解释维度的加权**，每一维都由**本次真实数据**算出来：
--
--     ① 术语覆盖率  term_coverage
--        源文本里命中的术语，有多少在译文里**原样保住了**（品牌词缺失 ⇒ 硬伤）。
--     ② 占位符完整率 placeholder_integrity
--        源文本里的 {{变量}}，译文里是否**一个不少、一个不多**。
--     ③ 校验通过率  check_pass_rate
--        本次确定性检查的加权通过率（PASS=1 / WARN=0.5 / FAIL=0）。
--     ④ 长度合规率  length_compliance
--        译文长度是否落在该内容类型 × 目标语言的规则区间内。
--
--   🔴 **某维度不适用时，权重按剩余适用维度重新归一**（而不是当成 0 分，
--      也不是当成满分）。理由：没有术语可保护的任务，术语覆盖率**没有信息量**，
--      把它算成 0 会让所有「无术语任务」的置信度看起来都很低 —— 那是在惩罚一件
--      本来就不该扣分的事。归一后「不适用」与「得 0 分」在数据上是两件不同的事，
--      且 `NULL` 与 `0.0000` 在库里长得不一样（前者是 NULL，后者是 0）。
--
--   权重（写死在服务层，因为它是**口径**不是数据）：
--     术语覆盖率 0.35 · 占位符完整率 0.25 · 校验通过率 0.30 · 长度合规率 0.10
--
--   置信度一律 **numeric(5,4)**（AGENTS.md：禁浮点）。
--
-- ---------------------------------------------------------------------------
-- 4. 「人工确认后发布」怎么落到数据层
-- ---------------------------------------------------------------------------
--
--   任务状态机（platform_text_task.status）：
--
--     PENDING   已建任务，等待生成（**AI 供应商未接入时停在这一步**）
--     DRAFTED   有译文草稿（人工录入或人工编辑过），等待人工复核
--     CONFIRMED 人工已确认（**这是发布的硬门槛**）
--     PUBLISHED 已发布（下游可取）
--     REJECTED  被驳回（终态）
--
--   🔴 **三条 DB 约束把「状态与事实一致」钉死**：
--     * CONFIRMED / PUBLISHED 必须有 confirmed_at；
--     * PUBLISHED 必须有 published_at；
--     * 有译文草稿的三个状态必须挂 current_draft_id。
--   ⇒ 「跳步」在**数据库层**就写不进去，不依赖服务层记得检查。
--
--   🔴 **发布闸在服务层**：只有 status='CONFIRMED' 的任务能被发布；
--     未确认的译文**取不走** —— 「下游可取」的读路径（published-replies）
--     只返回 status='PUBLISHED' 的行。
--
-- ---------------------------------------------------------------------------
-- 5. 时间与金额
-- ---------------------------------------------------------------------------
--
--   * 时间列一律 **timestamptz**；服务层用 **OffsetDateTime** 绑定并在 values 位置写
--     `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--   * 数量（长度）一律 **int**（字符数，按 Unicode **码点**计，中文与 emoji 才不会多算一倍）。
--   * 本模块**不落任何金额**：没有单价、费用、账单、结算。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_text_glossary —— 术语表（品牌词与专业术语保护）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个「源词 → 目标词」的保护条目。
--
-- 🔴 **品牌词（BRAND）缺了就是硬伤**：品牌名译错会让「文案已经翻译好了」这句话变成假话，
--   所以 BRAND 类术语在译文里缺失 ⇒ 检查判 **FAIL**，直接拦住发布；
--   PROFESSIONAL（专业术语）缺失 ⇒ 判 **WARN**（允许运营自己决定）。
--
-- target_locale 为空串 = **对所有目标语言生效**（品牌名通常不因语言而变）。
CREATE TABLE platform_text_glossary (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 源词（原文里出现的那个词）。按租户 + 目标语言唯一。
  source_term varchar(200) NOT NULL,
  -- 目标词（期望译文里出现的形态）。留空表示「只保形态，不指定译法」。
  target_term varchar(200) NOT NULL DEFAULT '',

  -- 术语类型：
  --   BRAND        品牌词 / 商标（缺失 ⇒ FAIL）
  --   PROFESSIONAL 专业术语（缺失 ⇒ WARN）
  --   PRODUCT      商品专名 / 型号（缺失 ⇒ WARN）
  term_type text NOT NULL DEFAULT 'PROFESSIONAL'
    CHECK(term_type IN ('BRAND','PROFESSIONAL','PRODUCT')),

  -- 适用目标语言（BCP-47 简码，如 en-US / de / ja）。空串 = 全部语言。
  target_locale varchar(16) NOT NULL DEFAULT '',

  -- 大小写敏感匹配（默认不敏感：Brand 与 brand 在品牌语境下是同一个词）。
  case_sensitive boolean NOT NULL DEFAULT false,
  enabled boolean NOT NULL DEFAULT true,

  note varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一个源词在同一目标语言下只能有一条（重复登记会让检查重复计分）。
  UNIQUE(tenant_id, source_term, target_locale),

  CHECK(btrim(source_term) <> '' AND length(source_term) <= 200),
  CHECK(length(target_term) <= 200),
  CHECK(length(target_locale) <= 16)
);

CREATE INDEX platform_text_glossary_by_locale
  ON platform_text_glossary(tenant_id, target_locale, enabled);

-- ---------------------------------------------------------------------------
-- 2. platform_text_banned_term —— 违禁词表（租户可配）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个违禁词。
--
-- 🔴 **「违禁词规避建议」必须基于真实词表的命中检测**，而不是泛泛地说一句
--   「建议注意合规」 —— 那样的建议运营没法执行。这里落的是**词**，
--   服务层做的是**逐条命中检测**，命中了才给规避建议。
--
-- severity：
--   BLOCK —— 命中即拦住发布（绝对化用语、虚假承诺、导流联系方式等）
--   WARN  —— 命中给警告，由运营自己判断
CREATE TABLE platform_text_banned_term (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  term varchar(200) NOT NULL,
  -- 分类（用于界面分组与建议文案）：
  --   ABSOLUTE   绝对化用语（最 / 第一 / 国家级 / 100%）
  --   CLAIM      功效或效果宣称
  --   GUARANTEE  承诺与担保
  --   COMPETITOR 竞品指向
  --   CONTACT    导流联系方式
  --   INCENTIVE  诱导好评 / 刷单
  category varchar(32) NOT NULL DEFAULT 'CLAIM'
    CHECK(category IN ('ABSOLUTE','CLAIM','GUARANTEE','COMPETITOR','CONTACT','INCENTIVE')),

  severity varchar(16) NOT NULL DEFAULT 'BLOCK'
    CHECK(severity IN ('BLOCK','WARN')),

  -- 适用目标语言。空串 = 全部语言。
  target_locale varchar(16) NOT NULL DEFAULT '',

  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, term, target_locale),

  CHECK(btrim(term) <> '' AND length(term) <= 200),
  CHECK(length(target_locale) <= 16)
);

CREATE INDEX platform_text_banned_term_by_locale
  ON platform_text_banned_term(tenant_id, target_locale, enabled);

-- ---------------------------------------------------------------------------
-- 3. platform_text_rule —— 长度与字符集规则
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个（内容类型 × 目标语言）的确定性约束。
--
-- 🔴 **长度上限与字符集要求是「数据」，不是「规则」**：不同平台 / 不同语言的标题长度上限
--   完全不同，把它写死成常量就等于假数据。所以它落在这里，由租户自己配。
--   （**系统零预置行** —— 没有配规则 ⇒ 长度与字符集这一维**不适用**，
--   置信度按「剩余维度重新归一」处理，检查结果里显式记一条 SKIPPED，
--   **不是**假装它通过了。）
--
-- require_charset：
--   ANY   不限制
--   LATIN 只允许拉丁字母 / 数字 / 常见标点（空格、-、.、,、' 等）
--   CJK   要求含 CJK 字符（中文等）
CREATE TABLE platform_text_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 内容类型：与 platform_text_task.content_type 同一词表。
  content_type text NOT NULL
    CHECK(content_type IN ('TITLE','DESCRIPTION','HIGHLIGHT','CUSTOMER_REPLY')),
  -- 目标语言。空串 = 全部语言（兜底规则）。
  target_locale varchar(16) NOT NULL DEFAULT '',

  -- 长度区间：按 Unicode **码点**计。空 = 该侧不限制。
  min_length int,
  max_length int,

  -- 字符集要求。
  require_charset varchar(16) NOT NULL DEFAULT 'ANY'
    CHECK(require_charset IN ('ANY','LATIN','CJK')),

  -- 术语是否强制保护（true 时 BRAND 术语缺失即 FAIL；false 时降为 WARN）。
  terminology_strict boolean NOT NULL DEFAULT true,

  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一（内容类型, 目标语言）只有一条规则。
  UNIQUE(tenant_id, content_type, target_locale),

  CHECK(min_length IS NULL OR min_length >= 0),
  CHECK(max_length IS NULL OR max_length > 0),
  -- 上下限同时存在时必须自洽。
  CHECK(min_length IS NULL OR max_length IS NULL OR min_length <= max_length),
  CHECK(length(target_locale) <= 16)
);

CREATE INDEX platform_text_rule_by_locale
  ON platform_text_rule(tenant_id, content_type, target_locale);

-- ---------------------------------------------------------------------------
-- 4. platform_text_template —— 客服回复草稿模板（封闭变量词表）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个客服回复模板。
--
-- 🔴 **变量词表是封闭的**（照抄 crm MessageSendService 的 TEMPLATE_VARIABLES 纪律）：
--   模板里的占位符必须是**服务端能确定填上**的，否则正文里会留下 {{xxx}}
--   这种没人看得懂的字样 —— 而那封信是要发给买家的。
--   ⇒ 模板登记时就校验「变量在封闭词表内」，越界的模板存不进来。
--
-- 🔴 **模板是「客服回复草稿」的确定性来源**：AI 供应商未接入时，
--   「客服回复草稿」仍然可以真做出来 —— 模板 + 变量绑定 + 确定性检查，
--   缺的只是「把源文翻成另一种语言」那一段。
CREATE TABLE platform_text_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  template_code varchar(64) NOT NULL,
  template_name varchar(120) NOT NULL,
  -- 模板正文（含 {{变量}} 占位符）。
  body text NOT NULL,
  -- 变量清单（jsonb 数组，元素是封闭词表里的名字）。登记时服务端会重算并覆盖。
  variables jsonb NOT NULL DEFAULT '[]'::jsonb,

  -- 适用内容类型（当前只用于 CUSTOMER_REPLY；留空 = 通用）。
  content_type text NOT NULL DEFAULT 'CUSTOMER_REPLY'
    CHECK(content_type IN ('TITLE','DESCRIPTION','HIGHLIGHT','CUSTOMER_REPLY')),
  -- 适用目标语言。空串 = 全部语言。
  target_locale varchar(16) NOT NULL DEFAULT '',

  enabled boolean NOT NULL DEFAULT true,
  note varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, template_code),

  CHECK(btrim(template_code) <> '' AND length(template_code) <= 64),
  CHECK(btrim(template_name) <> '' AND length(template_name) <= 120),
  CHECK(btrim(body) <> ''),
  CHECK(length(target_locale) <= 16),
  -- variables 必须是 jsonb 数组（不是对象 / 标量）。
  CHECK(jsonb_typeof(variables) = 'array')
);

CREATE INDEX platform_text_template_by_locale
  ON platform_text_template(tenant_id, content_type, target_locale);

-- ---------------------------------------------------------------------------
-- 5. platform_text_task —— 翻译任务
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条待翻文案（标题 / 描述 / 卖点 / 客服话术）。
--
-- 🔴 **源文本与目标语言都是真数据**，风格要求是运营给的输入，术语表是租户配的。
--
-- 🔴 **upstream_ref_* 指向「这条文案是从哪儿来的」**：
--   CATALOG_SPU / CATALOG_SKU —— 3.4.3 多语言内容（catalog 服务）
--   CHANNEL_LISTING        —— 4.2.1.1 刊登编辑器（channel 服务）
--   空                    —— 运营手工录入
--   ⚠️ 这里**只记引用，不建跨服务 FK**（AGENTS.md：禁止跨服务 FK）。
CREATE TABLE platform_text_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 人可读的单号（租户内唯一）。由发号序列生成。
  task_no varchar(32) NOT NULL,

  -- 内容类型。
  content_type text NOT NULL DEFAULT 'DESCRIPTION'
    CHECK(content_type IN ('TITLE','DESCRIPTION','HIGHLIGHT','CUSTOMER_REPLY')),

  -- 源文本与两端语言。
  source_text text NOT NULL,
  source_locale varchar(16) NOT NULL,
  target_locale varchar(16) NOT NULL,

  -- 风格要求（运营输入，如「简洁口语」「正式专业」）。
  style_requirement varchar(500) NOT NULL DEFAULT '',

  -- 上游引用（只记引用，不跨库 FK）。
  upstream_ref_type text NOT NULL DEFAULT ''
    CHECK(upstream_ref_type IN ('','CATALOG_SPU','CATALOG_SKU','CHANNEL_LISTING')),
  upstream_ref_id uuid,

  -- 状态机：PENDING → DRAFTED → CONFIRMED → PUBLISHED，另有终态 REJECTED。
  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','DRAFTED','CONFIRMED','PUBLISHED','REJECTED')),

  -- 当前生效的译文草稿（人工可编辑的那一版）。
  current_draft_id uuid,

  -- 人工确认与发布。
  confirmed_by uuid,
  confirmed_at timestamptz,
  published_at timestamptz,
  -- 驳回原因（REJECTED 时必填）。
  reject_reason varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, task_no),

  CHECK(btrim(source_text) <> ''),
  CHECK(btrim(source_locale) <> '' AND length(source_locale) <= 16),
  CHECK(btrim(target_locale) <> '' AND length(target_locale) <= 16),
  -- 源语言与目标语言不能相同（那不叫翻译）。
  CHECK(source_locale <> target_locale),
  -- 🔴 状态与事实一致（三条硬约束）。
  --   已确认 / 已发布 ⇒ 必须有确认时刻。
  CHECK(status NOT IN ('CONFIRMED','PUBLISHED') OR confirmed_at IS NOT NULL),
  --   已发布 ⇒ 必须有发布时刻。
  CHECK(status <> 'PUBLISHED' OR published_at IS NOT NULL),
  --   有译文草稿的三个状态 ⇒ 必须挂着一份草稿。
  CHECK(status NOT IN ('DRAFTED','CONFIRMED','PUBLISHED') OR current_draft_id IS NOT NULL),
  --   被驳回 ⇒ 必须写明原因。
  CHECK(status <> 'REJECTED' OR btrim(reject_reason) <> '')
);

CREATE INDEX platform_text_task_by_status
  ON platform_text_task(tenant_id, status, created_at DESC);

-- 发号序列（人可读单号）。照抄 crm 的 crm_buyer_email_no_seq 纪律。
CREATE SEQUENCE platform_text_task_no_seq;

-- ---------------------------------------------------------------------------
-- 6. platform_text_draft —— 译文草稿版本（人工可编辑 + 置信度 + 依据）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个译文草稿版本。**可人工编辑**落在「版本」二字上：
-- 每次编辑都追加一个新版本，旧版本留着（哪一版是谁改的、什么时候改的，要能查清）。
--
-- 🔴 **origin='AI' 在本仓当前**无法产生**（AI 供应商未接入），
--   但列留着 —— 适配器交付后不需要改表结构。
--   服务层**只**写 origin='MANUAL'。
--
-- 🔴 **置信度与其四个依据分列存放**：
--   只存一个总分，运营看到「0.62」根本不知道低在哪；
--   分列之后，界面可以直接说「术语覆盖率 0.50（两个品牌词没保住）」。
CREATE TABLE platform_text_draft (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  -- 版本号：同一任务内从 1 递增。
  version int NOT NULL DEFAULT 1,

  -- 译文正文（人工可编辑的内容本体）。
  target_text text NOT NULL,

  -- 来源：MANUAL（人工录入 / 人工编辑）。AI 供应商未接入 ⇒ 只能是 MANUAL。
  origin text NOT NULL DEFAULT 'MANUAL' CHECK(origin IN ('MANUAL','AI')),

  -- 置信度总分（numeric(5,4)，禁浮点）。口径见文件头第 3 节。
  confidence numeric(5,4) NOT NULL,
  -- 四个依据（各自独立列；不适用时为 NULL —— NULL 与 0.0000 是两件不同的事）。
  confidence_term_coverage numeric(5,4),
  confidence_placeholder_integrity numeric(5,4),
  confidence_check_pass_rate numeric(5,4),
  confidence_length_compliance numeric(5,4),

  -- 本次检查的计数（口径写死在列上，便于界面直接显示）。
  check_total int NOT NULL DEFAULT 0,
  check_failed int NOT NULL DEFAULT 0,
  check_warned int NOT NULL DEFAULT 0,

  -- 编辑者（来自已认证会话的 user_id，**不接受请求参数传入**）。
  edited_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一任务内版本号唯一。
  UNIQUE(tenant_id, task_id, version),

  FOREIGN KEY(tenant_id, task_id) REFERENCES platform_text_task(tenant_id, id) ON DELETE CASCADE,

  CHECK(version >= 1),
  CHECK(btrim(target_text) <> ''),
  -- 总分必须落在 [0,1]。
  CHECK(confidence >= 0 AND confidence <= 1),
  -- 四个依据同理，且各自在 [0,1] 或 NULL。
  CHECK(confidence_term_coverage IS NULL OR (confidence_term_coverage >= 0 AND confidence_term_coverage <= 1)),
  CHECK(confidence_placeholder_integrity IS NULL OR (confidence_placeholder_integrity >= 0 AND confidence_placeholder_integrity <= 1)),
  CHECK(confidence_check_pass_rate IS NULL OR (confidence_check_pass_rate >= 0 AND confidence_check_pass_rate <= 1)),
  CHECK(confidence_length_compliance IS NULL OR (confidence_length_compliance >= 0 AND confidence_length_compliance <= 1)),
  -- 计数不能为负，且失败 + 警告不超过总数。
  CHECK(check_total >= 0 AND check_failed >= 0 AND check_warned >= 0),
  CHECK(check_failed + check_warned <= check_total)
);

-- 当前草稿指针（可空：任务可能还没有草稿）。
ALTER TABLE platform_text_task
  ADD CONSTRAINT fk_platform_text_task_current_draft
  FOREIGN KEY(tenant_id, current_draft_id) REFERENCES platform_text_draft(tenant_id, id)
  ON DELETE SET NULL;

CREATE INDEX platform_text_draft_by_task
  ON platform_text_draft(tenant_id, task_id, version DESC);

-- ---------------------------------------------------------------------------
-- 7. platform_text_check —— 确定性检查结果
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次评估里的一条规则结论。
--
-- 🔴 **每次评估替换该任务的旧结果**（同事务内 DELETE + INSERT）：
--   检查结论是「这一版译文的现状」，不是历史流水 —— 留着旧结论会让人对着
--   上一个版本的结论下判断。草稿版本本身才是历史（platform_text_draft 追加不改）。
--
-- rule_code：
--   TERMINOLOGY 术语保护（品牌词 / 专业术语是否保住）
--   PLACEHOLDER 占位符完整性（封闭词表 + 一个不少一个不多）
--   BANNED_TERM 违禁词命中（租户词表 + 内置合规红线）
--   LENGTH      长度区间
--   CHARSET     字符集
--
-- verdict：
--   PASS   通过
--   WARN   警告（不拦发布，由运营判断）
--   FAIL   不通过（**拦发布**）
--   SKIPPED 本次不适用（例：没有配长度规则 ⇒ 长度这一维不参与置信度）
CREATE TABLE platform_text_check (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  draft_id uuid NOT NULL,

  rule_code varchar(48) NOT NULL
    CHECK(rule_code IN ('TERMINOLOGY','PLACEHOLDER','BANNED_TERM','LENGTH','CHARSET')),
  verdict varchar(16) NOT NULL
    CHECK(verdict IN ('PASS','WARN','FAIL','SKIPPED')),

  -- 命中项（命中的词 / 变量名 / 长度值）。没有命中时为空串。
  matched_item varchar(200) NOT NULL DEFAULT '',
  -- 人可读的说明。
  detail varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, task_id) REFERENCES platform_text_task(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, draft_id) REFERENCES platform_text_draft(tenant_id, id) ON DELETE CASCADE,

  CHECK(length(matched_item) <= 200)
);

CREATE INDEX platform_text_check_by_task
  ON platform_text_check(tenant_id, task_id, rule_code);

-- ===========================================================================
-- 8. 租户隔离（RLS）
-- ===========================================================================
--
-- 七张表全部：tenant_id NOT NULL + ENABLE **与 FORCE** RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
-- ⚠️ **序列也要隔离**：`platform_text_task_no_seq` 是全局序列，
--    跨租户取号会互相「看见对方的号段」。取号时**刻意**把租户号拼进前缀
--    （服务层生成 `TX-<租户短码>-<序号>`），让单号本身就能看出归属。
ALTER TABLE platform_text_glossary ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_text_glossary FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_text_glossary
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_text_banned_term ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_text_banned_term FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_text_banned_term
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_text_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_text_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_text_rule
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_text_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_text_template FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_text_template
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_text_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_text_task FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_text_task
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_text_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_text_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_text_draft
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_text_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_text_check FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_text_check
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 9. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的术语表 / 词表 / 规则 / 模板 /
-- 任务 / 草稿 / 检查结果，能写（建任务、录译文、确认、发布），能删（撤词条、删任务）。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_text_glossary TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_text_banned_term TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_text_rule TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_text_template TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_text_task TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_text_draft TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_text_check TO platform_app;

-- 发号序列的取号权限：没有它，服务层拿不到单号。
GRANT USAGE, SELECT ON SEQUENCE platform_text_task_no_seq TO platform_app;
