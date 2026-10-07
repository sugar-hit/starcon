-- 3.4.2.2 描述与卖点库：详情描述模板 / 卖点短语库 / 使用说明 / 注意事项，
-- 支持变量占位与多语言版本，供 04 刊登批量填充。
--
-- 蓝图 3.4.2.2 的原话：
--   「职责：管理详情描述模板、卖点短语库、使用说明、注意事项，支持变量占位与多语言版本，
--     供刊登批量填充。」
--   「输入：模板定义；变量绑定；多语言翻译（可由 18 的 AI 翻译预填）。」
--   「输出：填充后的描述文本（分语言）；供 04 引用。」
--   「关系：上游 = 3.4.2.1；下游 = 04 刊登、15 客服（答复话术可复用）。
--     **变量缺失必须在填充时报告，不能静默留下占位符。**」
--
-- ============================================================ 与 3.4.2.1 标题库的边界
--
-- 3.4.2.1 管**标题短文本**：有平台硬字符上限（eBay 80 / 亚马逊 200 / Shopify 255），
-- 它的 title_keyword 是「标题里放哪几个词」（≤ 60 字符的短词根），
-- 核心矛盾是「相似但不雷同」的重复铺货判定。
--
-- 本模块管**长文本**：详情描述 / 使用说明 / 注意事项是详情页与说明书里的段落，
-- **没有平台字符上限**（本文件的 body 上限是 20000 字符，比标题宽松两个数量级），
-- 核心矛盾是「变量缺失必须报告」与「多语言版本一致性」。
--
-- 两者的排版约束不同，所以本文件不建 title_* 表，也不复用 title_template：
-- 把长文本卖点塞进标题变量只会让标题长度控制失去意义（V20 文件头同一判断）。
-- 本模块的卖点短语库（content_phrase）与标题的 title_keyword 是**两个不同的库**：
-- 一个是长句（≤ 2000 字符，可含标点与换行），一个是短词根（≤ 60 字符）。
--
-- ============================================================ 「变量缺失必须报告」在 DB 层的落点
--
-- 这是蓝图的硬要求（「不能静默留下占位符」），只靠服务层自觉是不够的：
-- 一次手工 INSERT、或一次模板改版与翻译不同步，就会把 {brand} 原样发到平台上。
-- 所以下面五件事钉在数据里：
--   1. content_render_section CHECK (status<>'COMPLETE' OR text NOT LIKE '%{%')
--      —— 声称「已填好」的段落里不许出现任何一个花括号。填充时缺变量就会降级成
--      INCOMPLETE 并把占位符剥掉，绝不可能既缺变量又标成 COMPLETE。
--   2. content_render_section CHECK (status='INCOMPLETE' OR char_length>0)
--      —— COMPLETE 的段落必须有正文（空文本不可能是「填好了」）。
--   3. guard_content_render_section_complete（BEFORE UPDATE）
--      —— INCOMPLETE → COMPLETE 的直接改写被拒：缺掉的变量不会因为改个状态就回来。
--      要变成 COMPLETE 只能重跑填充（那时占位符有值了）。
--   4. guard_content_render_section_text（BEFORE INSERT OR UPDATE）
--      —— 逐条兜底：正文里出现 `{x}` 形式的残留占位符就报错，而不是放行。
--      与第 1 条互为纵深（CHECK 在 PG 里可被 NOT VALID 绕过，触发器不能）。
--   5. content_render_issue 一行一个缺失变量
--      —— 「报告」必须落到可查的行上，而不是拼在 error 字段里的一句话。
--      逐变量记录 variable_name + issue_kind + detail，
--      才能回答「哪 30 个 SKU 因为哪个变量没填上」。
--
-- ============================================================ 1. 内容模板（租户级字典）
--
-- 四类内容共用一张模板表，用 content_type 区分，而不是四张形状一样的表：
--   DESCRIPTION   详情描述（详情页主体）
--   INSTRUCTION   使用说明（怎么装、怎么用、怎么保养）
--   CAUTION       注意事项（禁止事项与安全警示）
--   SELLING_POINT 卖点段落（一组可复用的卖点陈述）
--
-- 四者共享「模板 + 变量占位 + 多语言 + 批量填充」的全部机制，差别只在标签与展示位置；
-- 拆成四张表会让变量、翻译、填充这三套机制各写四遍。
--
-- body 是带占位符的模板正文，形如「本产品采用 {brand} {material} 材质，{selling}」。
-- **字面量与占位符写在同一串里**（分隔符就是正文里的空格/换行本身），
-- 不另设分隔符风格列——分隔风格不同的模板直接是两个模板。
--
-- 规范化（折叠连续空白、去首尾空白）由服务层做，库里存规范形，
-- 这样「同一个模板写成两种空白」不会被当成两个模板，变量解析也不会被多空格坑到。
CREATE TABLE content_template (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  name varchar(100) NOT NULL CHECK(btrim(name)<>''),
  note text NOT NULL DEFAULT '',
  content_type text NOT NULL CHECK(content_type IN (
    'DESCRIPTION','INSTRUCTION','CAUTION','SELLING_POINT')),
  -- 模板正文。**无平台字符上限**（与 3.4.2.1 的标题模板根本不同）：
  -- 详情描述是一整段话，上限 20000 字符是为了挡住「把整本说明书粘进来」这种误操作。
  body text NOT NULL CHECK(btrim(body)<>''),
  -- 源语言（BCP-47 简写，如 zh-CN / en-US）。翻译挂在它下面，
  -- 且**只有源语言才允许没有翻译行**——源语言直接用 body 渲染。
  source_locale varchar(16) NOT NULL CHECK(btrim(source_locale)<>''),
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  -- 业务时间与系统时间分开：created_at 是运营「什么时候建的模板」，
  -- updated_at 是最后改动时刻。批量填充是同步短事务，没有 started/finished 可分。
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code)
);

-- ------------------------------------------------------------ 模板的变量声明
--
-- 变量拆成子表而不是在模板上放一个数组：变量要能按顺序被查，
-- 而且变量名在模板内必须唯一，否则同一个占位符有两个绑定源时无法解析。
--
-- **与 3.4.2.1 的 title_template_variable 同形但不同源**：
-- 那边第三类是「标题词根池」（取一个词换着来），这边是「卖点短语池」
-- （取一整句卖点文案填进正文），所以 source 枚举完全不同。
CREATE TABLE content_template_variable (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  -- slot_no 是**渲染顺序**：占位符在正文里的出现次序（长文本不需要轮转词序来避重，
  -- 这里只是让「按顺序读变量」有确定口径，例如导出给运营看模板结构）。
  slot_no int NOT NULL CHECK(slot_no>=0),
  -- 占位符名（不含花括号），正文里写 {brand}。
  variable_name varchar(32) NOT NULL CHECK(btrim(variable_name)<>''),
  -- 绑定源：事实字段从 SPU/SKU 取，短语池类从 content_phrase 取。
  -- 枚举化而不是自由文本：每个源对应服务层里一段确定的取值代码。
  source text NOT NULL CHECK(source IN (
    'SPU_BRAND','SPU_CATEGORY','SPU_NAME','SKU_NAME','SKU_CODE','SKU_SPEC',
    'PHRASE_SELLING','PHRASE_FEATURE','PHRASE_SCENE','PHRASE_CAUTION','SKU_SIBLING_LIST')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一模板内变量名唯一：一个占位符只能有一个绑定源。
  UNIQUE(tenant_id,template_id,variable_name),
  -- 同一模板内槽位唯一。
  UNIQUE(tenant_id,template_id,slot_no),
  FOREIGN KEY(tenant_id,template_id) REFERENCES content_template(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 2. 多语言版本
--
-- 「支持变量占位与多语言版本」「多语言翻译（可由 18 的 AI 翻译预填）」的落地表。
-- 一行 = 一个模板在一种语言下的一份译文。
--
-- origin 三态区分「谁写的」：
--   MANUAL    人工撰写
--   AI        18 的 AI 翻译预填（蓝图点名的来源）
--   IMPORTED  从既有 SKU 多语言内容导入
-- 不设「已确认」状态：那是 3.4.3.1 多语言内容的字段，本模块只管「这段译文现在是谁写的」。
--
-- status 两态：DRAFT 草稿（AI 预填后未复核）、REVIEWED 已复核。
-- **AI 预填不自动变 REVIEWED**：机器译文里的占位符被改写或漏掉是最典型的失败模式，
-- 而 3.4.2.2 的硬要求是「变量缺失必须报告」，所以草稿译文照样要过占位符一致性校验，
-- 只是界面上标出来提醒复核。
CREATE TABLE content_template_translation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  -- BCP-47 简写：zh-CN / en-US / ja-JP。不跨服务建 FK 到任何语言字典表。
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  name varchar(100) NOT NULL CHECK(btrim(name)<>''),
  -- 译文正文。**必须与源语言的占位符集合完全一致**——由服务层校验并有触发器兜底。
  body text NOT NULL CHECK(btrim(body)<>''),
  origin text NOT NULL DEFAULT 'MANUAL' CHECK(origin IN ('MANUAL','AI','IMPORTED')),
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','REVIEWED')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  -- 同一模板同一语言只能有一条译文：两条译文时「这条模板的英文是哪个」无法回答，
  -- 而 04 刊登按 (template, locale) 取一条填进详情页。
  UNIQUE(tenant_id,template_id,locale),
  FOREIGN KEY(tenant_id,template_id) REFERENCES content_template(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 3. 卖点短语库
--
-- 「卖点短语库」的落地表。四个 phrase_type 对应四类可复用文案：
--   SELLING  卖点陈述（「双单元立体声，低音下潜到 20Hz」）
--   FEATURE  特性描述（「IP67 防水，1.5 米深水 30 分钟」）
--   SCENE    适用场景（「露营 / 泳池 / 车载」）
--   CAUTION  注意事项短语（「请勿在充电时拆解」）
--
-- 与 3.4.2.1 的 title_keyword 是**两个不同的库**：那是 ≤ 60 字符的短词根（标题用），
-- 这里是 ≤ 2000 字符的长句（正文用），可含标点与换行。
-- 两者服务不同的排版约束，不合并——见文件头边界说明。
CREATE TABLE content_phrase (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  text text NOT NULL CHECK(btrim(text)<>''),
  phrase_type text NOT NULL CHECK(phrase_type IN ('SELLING','FEATURE','SCENE','CAUTION')),
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  -- 同一类型内文本唯一：同一句卖点写成两条会让短语池「看起来更大」，
  -- 批量填充出来的描述却还是那几种，而这正是「变量缺失被误判为已填」的温床。
  UNIQUE(tenant_id,phrase_type,text)
);

-- ============================================================ 4. 批量填充批次
--
-- 蓝图说「供刊登批量填充」。填充本身是纯字符串计算（毫秒级），**不做异步任务**——
-- 与 3.4.2.1 同理：把同步字符串计算做成异步只会让运营为几十毫秒去盯进度条。
--
-- status 三态（同步执行，没有「排队中」这种自欺欺人的状态）：
--   SUCCEEDED 全部段落都填好了（每个 SKU 至少一段 COMPLETE）
--   PARTIAL    有段落因变量缺失被降级成 INCOMPLETE
--   FAILED     一个 COMPLETE 段落都没产出（通常是模板压根没绑定变量，或短语池全空）
CREATE TABLE content_render (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  requested_by uuid NOT NULL,
  -- 本批次的输出语言（源语言或某个已配置译文）。
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  status text NOT NULL CHECK(status IN ('SUCCEEDED','PARTIAL','FAILED')),
  -- 提交时选中的 SKU 数（写死，不用 count：列表页要显示 N/N，而 count 会随段落变动）。
  sku_count int NOT NULL CHECK(sku_count>=0),
  -- 段落总数与其中填好 / 没填好的数量。「没填好」是一等公民：
  -- 它不是错误，而是「哪些变量还缺」这个可行动答案的载体。
  section_count int NOT NULL DEFAULT 0 CHECK(section_count>=0),
  complete_count int NOT NULL DEFAULT 0 CHECK(complete_count>=0),
  incomplete_count int NOT NULL DEFAULT 0 CHECK(incomplete_count>=0),
  -- 缺失变量总数（= content_render_issue 行数）。
  issue_count int NOT NULL DEFAULT 0 CHECK(issue_count>=0),
  -- 可执行的中文失败原因，不是异常类名。
  error text NOT NULL DEFAULT '',
  -- 业务时间与系统时间分开：created_at 是运营「几点点的填充」，
  -- finished_at 是系统「什么时候真的算完」。
  created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,template_id) REFERENCES content_template(tenant_id,id)
);

-- ------------------------------------------------------------ 逐 SKU 结果
--
-- 一行 = 一个 SKU 在这次填充里的完整结果：填好几段、没填好几段、缺了什么。
-- 批次级只有计数，问「哪个 SKU 一个段落都没填好」必须能落到行上。
CREATE TABLE content_render_sku (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, render_id uuid NOT NULL, sku_id uuid NOT NULL,
  outcome text NOT NULL CHECK(outcome IN ('RENDERED','INCOMPLETE','REJECTED')),
  complete_count int NOT NULL DEFAULT 0 CHECK(complete_count>=0),
  incomplete_count int NOT NULL DEFAULT 0 CHECK(incomplete_count>=0),
  issue_count int NOT NULL DEFAULT 0 CHECK(issue_count>=0),
  reason text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一批次里同一个 SKU 只出现一次：重复提交会让同一 SKU 产出两套段落。
  UNIQUE(tenant_id,render_id,sku_id),
  FOREIGN KEY(tenant_id,render_id) REFERENCES content_render(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id)
);

-- ------------------------------------------------------------ 填充出的段落
--
-- 一行 = 一个 SKU 的一段填充结果。这是蓝图点名的输出
-- 「填充后的描述文本（分语言）」的落地表，也是 04 刊登与 15 客服引用的东西。
CREATE TABLE content_render_section (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  render_id uuid NOT NULL, render_sku_id uuid NOT NULL, sku_id uuid NOT NULL,
  -- 段落类型快照：改模板的 content_type 不影响历史批次（批次要能解释「当时填的是什么」）。
  content_type text NOT NULL CHECK(content_type IN (
    'DESCRIPTION','INSTRUCTION','CAUTION','SELLING_POINT')),
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  -- 填充后的正文。
  --
  -- **硬约束一**（头部第 1 条）：status='COMPLETE' 时正文里不许有 `{`。
  -- 缺变量的段落会被降级成 INCOMPLETE 并把占位符剥掉，
  -- 所以「既缺变量又标成填好了」这个组合在数据里不存在。
  text text NOT NULL,
  -- 字符数按**码点**算（中日韩一个字、emoji 一个码点），不是 UTF-16 的 char 数。
  char_length int NOT NULL CHECK(char_length>=0),
  -- 实际用到的短语文本快照（JSON 字符串数组）：改了短语库之后仍能解释
  -- 「这段当时填的是哪几句卖点」。
  --
  -- 用 jsonb 而不是逗号分隔的 text：短语是长句且**可以含换行**
  -- （MediaRules.normalize 只去首尾空白，不折叠内部空白），
  -- 任何分隔符都能被短语正文里的同名字符伪造出来，把快照切成错的两条。
  used_phrases jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- COMPLETE 可用、INCOMPLETE 缺变量。**没有第三态**：
  -- 「填好了但你得自己看看」这种状态在库里分不出来，运营只会当真。
  status text NOT NULL CHECK(status IN ('COMPLETE','INCOMPLETE')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,render_id,sku_id),
  -- 硬约束二（头部第 2 条）：声称填好就必须有正文。
  CONSTRAINT content_render_section_complete_needs_text
    CHECK(status<>'COMPLETE' OR char_length>0),
  -- 硬约束三（头部第 1 条）。
  CONSTRAINT content_render_section_complete_no_placeholder
    CHECK(status<>'COMPLETE' OR text NOT LIKE '%{%'),
  FOREIGN KEY(tenant_id,render_id) REFERENCES content_render(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,render_sku_id) REFERENCES content_render_sku(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id)
);

-- ------------------------------------------------------------ 缺失变量报告
--
-- 蓝图硬要求的落地表：「**变量缺失必须在填充时报告，不能静默留下占位符。**」
--
-- 一行 = 一个变量在一个段落上没取到值。逐变量记而不是往批次上塞一句 error：
-- 只有这样才能回答「这 30 个 SKU 分别是因为哪个变量没填上」。
--
-- issue_kind 四值：
--   EMPTY_FACT          绑的是 SPU/SKU 事实字段，而那个字段在这个 SKU 上是空的
--   EMPTY_PHRASE_POOL   绑的是短语池，而该类型的短语池一条启用短语都没有
--   TRANSLATION_MISSING 本批次要的语言没有译文（且不是源语言）
--   UNBOUND_PLACEHOLDER 正文里有占位符，而模板里没给它声明绑定源
CREATE TABLE content_render_issue (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  render_id uuid NOT NULL, section_id uuid NOT NULL, render_sku_id uuid NOT NULL, sku_id uuid NOT NULL,
  -- 没取到值的变量名（源语言正文里的那个占位符名）。
  variable_name varchar(32) NOT NULL CHECK(btrim(variable_name)<>''),
  issue_kind text NOT NULL CHECK(issue_kind IN (
    'EMPTY_FACT','EMPTY_PHRASE_POOL','TRANSLATION_MISSING','UNBOUND_PLACEHOLDER')),
  -- 人能读的原因（指到具体字段或短语池类型），不是异常类名。
  detail text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个变量在一个段落上只报一次：同一段正文里 {brand} 出现三次也只是同一个缺失。
  UNIQUE(tenant_id,section_id,variable_name),
  FOREIGN KEY(tenant_id,section_id) REFERENCES content_render_section(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,render_id) REFERENCES content_render(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id)
);

-- ============================================================ 5. 触发器
--
-- ---- 段落正文里不许残留占位符（头部第 4 条，逐条兜底）
--
-- 与 CHECK 互为纵深：CHECK 在 PG 里可以被 NOT VALID / 迁移脚本绕过，
-- 触发器不行。宁可让填充失败，也不能让 `{brand}` 原文发到平台上。
-- RAISE 的格式串里 %% 表示一个字面量 %，所以下面那条 LIKE 的百分号要写两个。
CREATE FUNCTION guard_content_render_section_text() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.text LIKE '%{%' THEN
  RAISE EXCEPTION '段落正文里残留了占位符：SKU %，语言 %，正文开头「%」——3.4.2.2 的硬要求是「变量缺失必须报告，不能静默留下占位符」。缺变量的段落请写成 INCOMPLETE 并在 content_render_issue 里报告原因',
    LEFT(NEW.sku_id::text,8), NEW.locale, LEFT(NEW.text, 40) USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_content_render_section_text BEFORE INSERT OR UPDATE ON content_render_section
  FOR EACH ROW EXECUTE FUNCTION guard_content_render_section_text();

-- ---- INCOMPLETE 不能被直接改成 COMPLETE（头部第 3 条）
--
-- 缺掉的变量不会因为改个状态就回来。要变成 COMPLETE 只能重跑填充，
-- 那时占位符确实有值了。
CREATE FUNCTION guard_content_render_section_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE issues bigint;
BEGIN
 IF OLD.status='INCOMPLETE' AND NEW.status='COMPLETE' THEN
  SELECT count(*) INTO issues FROM content_render_issue WHERE section_id=NEW.id;
  RAISE EXCEPTION '段落还有 % 个缺失变量，不能直接标记为已填好：缺掉的变量不会因为改状态就回来。要修请补上模板的变量绑定或短语池后重新填充（SKU %, 语言 %）',
    issues, LEFT(NEW.sku_id::text,8), NEW.locale USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_content_render_section_complete BEFORE UPDATE ON content_render_section
  FOR EACH ROW EXECUTE FUNCTION guard_content_render_section_complete();

-- ---- 模板被填充批次引用时不允许删
--
-- 与 V19 的 refuse_media_process_recipe_delete、V20 的 refuse_title_template_delete 同一纪律：
-- 删模板会级联删掉它的变量与全部译文，而 content_render_section.template 溯源指向它、
-- 历史段落是按那套变量绑定填出来的。
CREATE FUNCTION refuse_content_template_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE runs bigint;
BEGIN
 SELECT count(*) INTO runs FROM content_render r
  WHERE r.tenant_id=OLD.tenant_id AND r.template_id=OLD.id;
 IF runs>0 THEN
  RAISE EXCEPTION '内容模板「%」已被 % 次批量填充引用，不可删除：请改用停用（停用后不能再用它新建批次，历史段落照常可读）',
    OLD.code, runs USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_content_template_delete BEFORE DELETE ON content_template
  FOR EACH ROW EXECUTE FUNCTION refuse_content_template_delete();

-- ---- 译文被段落引用时不允许删
--
-- 段落上的 locale + text 是**填充当时的快照**，而 template_id 是溯源：
-- 删掉译文行之后，「这段英文是哪一版译文填的」就再也答不上来了。
-- 改译文请新增一版（改 locale 会撞 UNIQUE，所以实际做法是改正文 + 保留历史段落对照）。
CREATE FUNCTION refuse_content_translation_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE refs bigint;
BEGIN
 SELECT count(*) INTO refs FROM content_render_section s
  WHERE s.tenant_id=OLD.tenant_id AND s.render_id IN
    (SELECT r.id FROM content_render r
      WHERE r.tenant_id=OLD.tenant_id AND r.template_id=OLD.template_id AND r.locale=OLD.locale);
 IF refs>0 THEN
  RAISE EXCEPTION '语言「%」的译文已被 % 段填充结果引用，不可删除：段落上快照的是填充当时的正文，删掉译文行之后「这段是哪一版译文填的」就答不上来了。请改用停用模板或换别的语言',
    OLD.locale, refs USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_content_translation_delete BEFORE DELETE ON content_template_translation
  FOR EACH ROW EXECUTE FUNCTION refuse_content_translation_delete();

-- ---- 短语被段落引用时不允许删
--
-- 与 V20 的 refuse_title_keyword_delete 同一纪律：段落快照的是**文本**，
-- 短语库行被删之后，那段话就成了没有出处的孤儿。
CREATE FUNCTION refuse_content_phrase_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE refs bigint;
BEGIN
 SELECT count(*) INTO refs FROM content_render_section s
  WHERE s.tenant_id=OLD.tenant_id AND s.text LIKE ('%' || OLD.text || '%');
 IF refs>0 THEN
  RAISE EXCEPTION '短语「%」已被 % 段填充结果引用，不可删除：段落快照的是它的文本，删掉短语库行那段话就成了没有出处的孤儿。请改用停用',
    LEFT(OLD.text, 40), refs USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_content_phrase_delete BEFORE DELETE ON content_phrase
  FOR EACH ROW EXECUTE FUNCTION refuse_content_phrase_delete();

-- ============================================================ 6. 索引
--
-- 批次列表页：按创建时间倒序（页面上最常见的是「我刚填的那批」）。
CREATE INDEX content_render_list ON content_render(tenant_id, created_at DESC, id DESC);
CREATE INDEX content_render_by_template ON content_render(tenant_id, template_id, created_at DESC);
-- 逐 SKU 结果按批次读。
CREATE INDEX content_render_sku_by_render ON content_render_sku(tenant_id, render_id, outcome, created_at);
-- 段落列表：按 SKU + 语言读（04 刊登取「这个 SKU 的这段描述」的主查询形状）。
CREATE INDEX content_render_section_by_sku ON content_render_section(tenant_id, sku_id, locale, created_at DESC, id DESC);
-- 按批次读段落（批次详情页）。
CREATE INDEX content_render_section_by_render ON content_render_section(tenant_id, render_id, sku_id);
-- **部分索引：只索引填好的段落**。04 刊登与 15 客服只引用 COMPLETE，
-- 而跑上几个月后 INCOMPLETE（模板待补变量）会远多于 COMPLETE。
CREATE INDEX content_render_section_usable ON content_render_section(tenant_id, sku_id, locale, content_type)
  WHERE status='COMPLETE';
-- 按状态筛批次详情（「这批哪些段落没填好」）。
CREATE INDEX content_render_section_by_status ON content_render_section(tenant_id, status, render_id)
  WHERE status='INCOMPLETE';
-- 缺失变量报告：批次详情页逐条列出，以及「这个变量历史上缺过多少次」。
CREATE INDEX content_render_issue_by_render ON content_render_issue(tenant_id, render_id, section_id);
CREATE INDEX content_render_issue_by_variable ON content_render_issue(tenant_id, variable_name, created_at DESC);
-- 短语删除守卫（refuse_content_phrase_delete）要按 text 扫 content_render_section。
-- 段落正文是长文本且有通配符，普通 btree 用不上，所以用 trigram 索引。
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX content_render_section_text_trgm ON content_render_section
  USING gin (text gin_trgm_ops) WHERE status='COMPLETE';
-- 模板字典列表与读取。
CREATE INDEX content_template_list ON content_template(tenant_id, content_type, enabled, code);
CREATE INDEX content_template_variable_by_template ON content_template_variable(tenant_id, template_id, slot_no, id);
-- 译文按模板取（填一批之前先查齐有没有译文，避免开跑之后才发现语言没配）。
CREATE INDEX content_translation_by_template ON content_template_translation(tenant_id, template_id, locale);
CREATE INDEX content_translation_by_locale ON content_template_translation(tenant_id, locale, status);
-- 短语池按类型取词（每次填充都要按类型读一遍）。
CREATE INDEX content_phrase_by_type ON content_phrase(tenant_id, phrase_type, enabled, code);

-- ============================================================ 7. RLS 与授权
--
-- 八张表全部租户级数据（文案资产是租户共享库，与 media_* / title_* 同一形状）：
-- 描述与素材、标题一样，同一运营要在别人的商品上复用模板，
-- 不套 V3 的 owner_id/department_id 数据范围。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['content_template','content_template_variable',
   'content_template_translation','content_phrase','content_render',
   'content_render_sku','content_render_section','content_render_issue'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  content_template, content_template_variable, content_template_translation, content_phrase,
  content_render, content_render_sku, content_render_section, content_render_issue
  TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  content_template, content_template_variable, content_template_translation, content_phrase,
  content_render, content_render_sku, content_render_section, content_render_issue
  TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **04 刊登**：取 content_render_section 里 status='COMPLETE' 的行
--     （按 sku_id + locale + content_type）。本模块不感知 listing，也不写刊登状态。
--   * **15 客服**：复用同一批段落作为答复话术，取用形状与 04 相同。
--   * **18 AI 翻译**：翻译行的 origin='AI' 就是「预填」的落点。本模块不调 AI，
--     18 翻译完调用 POST /catalog/descriptions/translations 写回即可。
--   * **3.4.2.1 标题库**：标题与描述是两条独立的资产线（见文件头边界）。
