-- 4.2.2.2 描述模板：富文本描述模板（段落结构 / 变量占位 / 多语言版本 / 图片插入位），
-- 产出**渲染后的描述 HTML**供 04 刊登编辑器与平台上传。
--
-- 蓝图 4.2.2.2 的原话：
--   「职责：管理富文本描述模板：段落结构（产品介绍、使用场景、规格参数、注意事项、
--     售后承诺）、变量占位、多语言版本、图片插入位。」
--   「输入：模板结构定义；变量绑定；翻译版本。」
--   「输出：描述模板 + 渲染后的描述 HTML（供编辑器与平台上传）。」
--   「关系：上游 = 3.4.2.2 描述库、3.4.3 多语言；下游 = 4.2.1.1。
--     **变量未绑定必须显式提示**，不允许把 {{尺寸}} 原样发到平台。」
--
-- ============================================================ 与 V21（3.4.2.2 描述与卖点库）的取舍
--
-- V21 已经交付了 content_template / content_template_variable /
-- content_template_translation / content_phrase / content_render /
-- content_render_sku / content_render_section / content_render_issue 一整套机制，
-- 并且已经把「变量缺失必须报告，不能静默留下占位符」钉在了 DB 层。
--
-- **结论：变量、绑定、多语言三套机制一律复用 V21，本文件不另立。**
-- 逐条理由：
--
--   1. **变量声明复用 content_template_variable（不建 description_variable）。**
--      {{尺寸}} 的绑定源枚举（SPU_BRAND / SKU_SPEC / PHRASE_SCENE / ...）在 V21 里
--      已经定义好，且服务层的取值代码（DescriptionRenderer.bindOne）与短语池联动都在那。
--      再建一张变量表就要把 11 个绑定源、槽位唯一、占位符↔声明双向校验各写一遍，
--      而 04 刊登将来要回答「{{尺寸}} 到底取自哪里」时会有两个答案。
--   2. **多语言复用 content_template_translation（不建 description_translation）。**
--      同上：origin（MANUAL / AI / IMPORTED）、status（DRAFT / REVIEWED）与
--      「译文占位符集合必须与源语言相等」这条校验都已经在 V21 的服务层，
--      复制一份会让「这段英文是谁写的」有两个答案。
--   3. **短语内容复用 content_phrase（不建 description_phrase）。**
--      使用场景段落绑 PHRASE_SCENE、注意事项段落绑 PHRASE_CAUTION，
--      与 3.4.2.2 的卖点短语库是同一个池子，本来就该是同一个。
--   4. **段落正文复用 content_template.body**：一个段落就是一行 content_template
--      （content_type='DESCRIPTION'），description_template_section 只是把它挂进
--      五段式骨架并排好顺序。所以「模板正文」这一个概念在全仓只有一张表。
--
-- 那么 V21 到底解决不了、本文件必须新立的是什么？三条**实质**差异：
--
--   A. **段落结构**。V21 的 content_template 是「一整段文本 + 一个 content_type」，
--      而蓝图要求的是**一个模板内部有五类段落**（产品介绍 / 使用场景 / 规格参数 /
--      注意事项 / 售后承诺），且顺序、类型都要能查。硬塞进 V21 就只能糊成一个大字符串，
--      而「规格参数这一段还没配翻译」这种问题就再也答不出来。
--      → description_template + description_template_section。
--   B. **图片插入位**。V21 全程是纯文本，content_render_section 里没有任何图片概念。
--      描述模板要能把图片落到**可解析的位置**（哪个段落、第几个插图位），
--      这在 V21 里无处安放。
--      → description_image_slot + description_render_image。
--   C. **输出是 HTML 文档而不是文本段落**。V21 的 content_render_section 有一条
--      UNIQUE(tenant_id, render_id, sku_id)：**一个批次里同一个 SKU 只能落一段**，
--      所以「一个模板 → 五段结构化 HTML」在 V21 的渲染表里根本表达不了；
--      而且 content_render 是「一批 N 个 SKU 填四种独立文案」，本模块是
--      「一个模板针对一个 SKU 产出一整份详情页 HTML」，是另一种形状。
--      → description_render / description_render_section / description_render_image /
--        description_render_issue。
--
-- **为什么不干脆让 04 刊登去拼**：因为「渲染后的描述 HTML」是本模块蓝图写明的**输出**，
-- 不是下游自己拼的副产品。拼装规则（五类段落顺序、标题层级、图片位、缺变量时怎么办）
-- 必须在服务端有一份可测试的实现，否则每个下游各拼一次，迟早拼出不一样的结果。
--
-- 两套渲染表的边界（别混用）：
--   * content_render*            —— 3.4.2.2：**长文本段落**的批量填充，
--                                  一批 N 个 SKU × 一种文案类型，产出「填充后的描述文本」。
--   * description_render*        —— 4.2.2.2：**结构化 HTML 文档**的渲染，
--                                  一个模板 × 一个 SKU × 一种语言，产出「可直接上传的 HTML」。
-- 两者共享的只有 content_template 一张模板表与同一套变量取值代码。
--
-- ============================================================ 「变量未绑定必须显式提示」的 DB 级落点
--
-- 蓝图这句话在本模块的措辞是「不允许把 {{尺寸}} 原样发到平台」，
-- 落点是五件事（与 V21 同一纪律，但作用对象从 text 换成 html）：
--   1. description_render_section CHECK (status<>'COMPLETE' OR html NOT LIKE '%{%')
--      —— 声称「已填好」的段落里不许出现任何一个花括号。缺变量的段落降级成
--      INCOMPLETE 并把占位符剥掉，绝不可能既缺变量又标成 COMPLETE。
--   2. description_render_section CHECK (status='INCOMPLETE' OR char_length(html)>0)
--      —— COMPLETE 必须有 HTML（空文本不可能是「填好了」）。
--   3. guard_description_render_section_html（BEFORE INSERT OR UPDATE）
--      —— 逐条兜底：HTML 里出现 `{x}` 形式的残留占位符就报错。CHECK 可被 NOT VALID
--      绕过，触发器不能。这条把「手工 UPDATE 把占位符塞回去」也堵死。
--   4. guard_description_render_section_complete（BEFORE UPDATE）
--      —— INCOMPLETE → COMPLETE 被拒：缺掉的变量不会因为改个状态就回来。
--   5. description_render CHECK (status<>'SUCCEEDED' OR html NOT LIKE '%{%')
--      —— 批次级的同一道闸。段落都干净但**拼装**时被插回了占位符，
--      批次层同样拒收（拼装代码是本模块自己写的，必须自己也被钉住）。
--   6. description_render_issue 一行一个缺失变量
--      —— 「显式提示」必须落到可查的行上，逐变量记 variable_name + issue_kind + detail，
--      才能回答「这个 SKU 因为哪个变量没填上」。
--
-- ============================================================ 1. 描述模板（段落结构容器）
--
-- 本表是「蓝图说的那个描述模板」：它只管骨架（有哪些段落、什么顺序、什么源语言），
-- **不存正文**。正文在每个段落背后的 content_template.body 里（见文件头第 4 条）。
--
-- 拆容器与内容是刻意的：段落内容要独立翻译、独立绑定变量、独立被 3.4.2.2 的
-- 批量填充引用，而「这五段按什么顺序拼」是本模块独有的。
CREATE TABLE description_template (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  name varchar(100) NOT NULL CHECK(btrim(name)<>''),
  note text NOT NULL DEFAULT '',
  -- 源语言（BCP-47 简写）。译文挂在每个段落背后的 content_template_translation 上，
  -- 所以这里记的是「这套模板以哪种语言为源」的统一口径——渲染时按它逐段取
  -- 「源语言正文 or 已配译文」，不会出现同一个模板一半用源语言一半用译文。
  source_locale varchar(16) NOT NULL CHECK(btrim(source_locale)<>''),
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code)
);

-- ------------------------------------------------------------ 五类段落
--
-- 蓝图点名的五类：产品介绍 / 使用场景 / 规格参数 / 注意事项 / 售后承诺。
-- **显式建模成枚举而不是一列自由文本**：段落类型决定它绑哪个短语池
-- （SCENE → PHRASE_SCENE、CAUTION → PHRASE_CAUTION），也让「规格参数这一段
-- 还没配英文」能在 SQL 里一次问出来。
--
-- section_type → 绑定的默认短语池（服务层按这个给建议，不强制）：
--   INTRO            产品介绍  PHRASE_SELLING
--   SCENE            使用场景  PHRASE_SCENE
--   SPEC             规格参数  （事实变量为主，SKU_SPEC / SKU_SIBLING_LIST）
--   CAUTION          注意事项  PHRASE_CAUTION
--   SERVICE_PROMISE  售后承诺  （固定句子为主）
--
-- content_template_id 指向 V21 的 content_template（content_type='DESCRIPTION'）。
-- UNIQUE(tenant_id,content_template_id)：一行 content_template 只能当一个段落用，
-- 否则同一段正文会同时出现在两个描述模板的不同位置，而「这段译文是哪一版」
-- 就再也说不清了。
--
-- 外键**不加 ON DELETE CASCADE**（走 RESTRICT）：段落背后的 content_template 若被
-- 3.4.2.2 的填充批次引用过，删它必须失败——历史段落是按那套变量绑定填出来的。
-- 具体拦在哪见第 5 节的 refuse_description_section_content_delete。
CREATE TABLE description_template_section (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  section_type text NOT NULL CHECK(section_type IN (
    'INTRO','SCENE','SPEC','CAUTION','SERVICE_PROMISE')),
  sort_order int NOT NULL CHECK(sort_order>=0),
  content_template_id uuid NOT NULL,
  -- 段落小标题（产品介绍 / 使用场景 / ...）要不要出现在渲染出的 HTML 里。
  -- 默认要：五段式骨架的价值就在结构可读，而规格参数段落在很多类目里
  -- 运营习惯不写「规格参数」这四个字（下面直接是表格）。关掉它不是删段落。
  show_heading boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一模板里一类段落只能有一段：蓝图给的是五类，不是「可以有三段注意事项」。
  -- 真要三段注意事项，正确做法是拆成两个描述模板。
  UNIQUE(tenant_id,template_id,section_type),
  UNIQUE(tenant_id,template_id,sort_order),
  UNIQUE(tenant_id,content_template_id),
  FOREIGN KEY(tenant_id,template_id) REFERENCES description_template(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,content_template_id) REFERENCES content_template(tenant_id,id)
);

-- ------------------------------------------------------------ 图片插入位
--
-- 蓝图点名的「图片插入位」。**不是文字描述，是渲染时真的落成 <img> 标签的位置。**
--
-- slot_no 是段落内的插图序号（0 起），渲染时拼成 data-position 全局序号，
-- 所以「这张图是第几段第几个插图位」在 HTML 里是可解析的，不是靠肉眼数。
--
-- bind_source 三值：
--   RENDER_INPUT  由 4.2.1.1 编辑器在渲染请求里按 slot_no 直接给图
--                 （它手上已经有对外可访问 / 已签名的 URL）
--   SKU_PRIMARY   取该 SKU 的主图素材（media_reference → media_asset purpose='PRIMARY'）
--   SKU_GALLERY   取该 SKU 的附图素材（purpose='GALLERY'），按 slot_no 取第 N 张
--
-- **为什么 src 允许调用方给**：V18 的 media_asset 只有 storage_key（对象存储定位符），
-- 文件头明写「对外 URL 由读取端现签」，本仓没有可直出的公开 URL。
-- 所以规则是「有已解析 URL 就用它，没有就退回 /api/media/{storage_key} 这条约定路径，
-- 由部署侧的对象存储网关提供」——两条都不是假地址，且都落进 HTML 的 src。
--
-- required=true 的位在渲染时取不到图会产出一条 IMAGE_UNBOUND 报告，
-- 并把该段落降级成 INCOMPLETE（发布出去的详情页少一张图同样是事故）。
CREATE TABLE description_image_slot (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, section_id uuid NOT NULL,
  slot_no int NOT NULL CHECK(slot_no>=0),
  -- 图注：渲染成 <img> 的 alt。亚马逊等平台强制 alt text，所以它是数据不是 UI 文案。
  caption varchar(500) NOT NULL DEFAULT '',
  bind_source text NOT NULL CHECK(bind_source IN ('RENDER_INPUT','SKU_PRIMARY','SKU_GALLERY')),
  -- 固定素材（绑 RENDER_INPUT 时可不填；填了就必须属于本租户）。
  asset_id uuid,
  required boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一段落里插图序号唯一。
  UNIQUE(tenant_id,section_id,slot_no),
  FOREIGN KEY(tenant_id,section_id) REFERENCES description_template_section(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,asset_id) REFERENCES media_asset(tenant_id,id)
);

-- ============================================================ 2. 渲染结果（渲染后的描述 HTML）
--
-- 蓝图输出：「描述模板 + **渲染后的描述 HTML**（供编辑器与平台上传）」。
--
-- 一行 = 一个描述模板针对一个 SKU 在一种语言下的一次渲染。
-- 与 V21 的 content_render（一批 N 个 SKU 填长文本）**形状不同**，见文件头差异 C。
CREATE TABLE description_render (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  template_id uuid NOT NULL, sku_id uuid NOT NULL, requested_by uuid NOT NULL,
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  -- SUCCEEDED 五段全填好；PARTIAL 有段落因变量或图片缺失被降级；
  -- FAILED 一段都没填出来（模板没配段落，或所有段落都没译文）。
  status text NOT NULL CHECK(status IN ('SUCCEEDED','PARTIAL','FAILED')),
  -- **拼装出来的整份详情页 HTML**：五个 <section> 按 sort_order 串起来，
  -- 每个 section 带 data-section-type，段落内的 <img> 带 data-position。
  -- 这是直接交给 4.2.1.1 编辑器与平台上传的产物，不是模板字符串。
  html text NOT NULL DEFAULT '',
  section_count int NOT NULL DEFAULT 0 CHECK(section_count>=0),
  complete_count int NOT NULL DEFAULT 0 CHECK(complete_count>=0),
  incomplete_count int NOT NULL DEFAULT 0 CHECK(incomplete_count>=0),
  image_count int NOT NULL DEFAULT 0 CHECK(image_count>=0),
  issue_count int NOT NULL DEFAULT 0 CHECK(issue_count>=0),
  -- 可执行的中文说明（缺什么、怎么修），不是异常类名。
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  -- 硬约束（头部第 5 条）：声称全部填好，HTML 里就不许有任何一个花括号。
  CONSTRAINT description_render_succeeded_no_placeholder
    CHECK(status<>'SUCCEEDED' OR html NOT LIKE '%{%'),
  CONSTRAINT description_render_succeeded_needs_html
    CHECK(status<>'SUCCEEDED' OR char_length(html)>0),
  -- 硬约束（头部第 6 条的另一半）：声称「五段全填好」就必须真的没有缺失。
  --
  -- **这一条不是重复前一条**：前一条只挡「HTML 里还留着占位符」，
  -- 而一个 PARTIAL 的渲染它的 HTML 是**干净的**（占位符已经被剥掉了），
  -- 所以只靠前一条的话，把 status 从 PARTIAL 改成 SUCCEEDED 就能把一份
  -- 「brand 没填上」的描述标成可发布——4.2.1.1 会照单上传。
  -- 计数是写死在本行的（不用 count()，列表页要显示 N/N，而 count 会随段落变动），
  -- 所以这里可以在 DB 层直接判。
  CONSTRAINT description_render_succeeded_has_no_gap
    CHECK(status<>'SUCCEEDED' OR (incomplete_count=0 AND issue_count=0)),
  FOREIGN KEY(tenant_id,template_id) REFERENCES description_template(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id)
);

-- ------------------------------------------------------------ 逐段落 HTML
--
-- 一行 = 一次渲染里的一段。与 V21 的 content_render_section 最大的不同：
-- 这里**一个 SKU 一批可以有很多段**（五段骨架），所以主键不是 (render, sku)
-- 而是 (render, section)。这正是本文件必须另立渲染表的原因（见文件头差异 C）。
CREATE TABLE description_render_section (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  render_id uuid NOT NULL, section_id uuid NOT NULL,
  -- 段落类型快照：改模板的段落类型不影响历史渲染（要能解释「当时渲染的是什么」）。
  section_type text NOT NULL CHECK(section_type IN (
    'INTRO','SCENE','SPEC','CAUTION','SERVICE_PROMISE')),
  sort_order int NOT NULL CHECK(sort_order>=0),
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  -- 渲染出的段落小标题（产品介绍 / 使用场景 / ...）。多语言时随译文走。
  heading varchar(200) NOT NULL DEFAULT '',
  -- **这一段的 HTML**：<section data-section-type="INTRO">…</section> 的内部内容，
  -- 已把变量替换掉、图片落成 <img>。
  html text NOT NULL,
  image_count int NOT NULL DEFAULT 0 CHECK(image_count>=0),
  -- COMPLETE 可用、INCOMPLETE 缺变量或缺必填图片。**没有第三态**。
  status text NOT NULL CHECK(status IN ('COMPLETE','INCOMPLETE')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,render_id,section_id),
  -- 硬约束一（头部第 1 条）：声称填好就必须不含任何花括号。
  CONSTRAINT description_render_section_complete_no_placeholder
    CHECK(status<>'COMPLETE' OR html NOT LIKE '%{%'),
  -- 硬约束二（头部第 2 条）：声称填好就必须有 HTML。
  CONSTRAINT description_render_section_complete_needs_html
    CHECK(status<>'COMPLETE' OR char_length(html)>0),
  FOREIGN KEY(tenant_id,render_id) REFERENCES description_render(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,section_id) REFERENCES description_template_section(tenant_id,id)
);

-- ------------------------------------------------------------ 图片落点
--
-- 一行 = 渲染时真正落进 HTML 的一个 <img>。
-- position 是**渲染全局序号**（0 起，跨段落连续），写进 HTML 的 data-position，
-- 所以「这张图在整个详情页里是第几张」是可解析的。
CREATE TABLE description_render_image (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  render_id uuid NOT NULL, render_section_id uuid NOT NULL,
  -- 渲染全局序号 → HTML 的 data-position。
  position int NOT NULL CHECK(position>=0),
  -- 段落内序号 → HTML 的 data-slot-no。
  slot_no int NOT NULL CHECK(slot_no>=0),
  image_slot_id uuid,
  asset_id uuid,
  -- 落进 <img src> 的地址。见 description_image_slot 文件头的说明：
  -- 有已解析 URL 就用它，否则退回 /api/media/{storage_key}。
  src text NOT NULL CHECK(btrim(src)<>''),
  -- 落进 <img alt>。亚马逊等平台强制 alt text。
  alt varchar(500) NOT NULL DEFAULT '',
  -- 图片像素宽高来自 media_asset（NUMERIC，与 V18 同一口径，禁浮点）；
  -- 调用方直给 URL 而没指素材时这两列留空——**不猜、不填假值**。
  width_px numeric(19,6) CHECK(width_px IS NULL OR width_px>0),
  height_px numeric(19,6) CHECK(height_px IS NULL OR height_px>0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,render_id,position),
  FOREIGN KEY(tenant_id,render_id) REFERENCES description_render(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,render_section_id) REFERENCES description_render_section(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,image_slot_id) REFERENCES description_image_slot(tenant_id,id)
);

-- ------------------------------------------------------------ 缺失变量 / 缺图报告
--
-- 蓝图硬要求「变量未绑定必须显式提示」的落地表。
--
-- issue_kind 五值（前四值与 V21 的 content_render_issue **逐字一致**，共用取值口径）：
--   EMPTY_FACT          绑的是 SPU/SKU 事实字段，而那个字段在这个 SKU 上是空的
--   EMPTY_PHRASE_POOL   绑的是短语池，而该类型的短语池一条启用短语都没有
--   TRANSLATION_MISSING 这段没有该语言的译文（且不是源语言）
--   UNBOUND_PLACEHOLDER 正文里有占位符，而段落背后的模板没给它声明绑定源
--   IMAGE_UNBOUND       本模块新增：必填的图片插入位没取到图
CREATE TABLE description_render_issue (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  render_id uuid NOT NULL, render_section_id uuid NOT NULL, sku_id uuid NOT NULL,
  -- 没取到值的变量名。IMAGE_UNBOUND 时存插图位标识（如 slot-2），不是变量名。
  variable_name varchar(32) NOT NULL CHECK(btrim(variable_name)<>''),
  issue_kind text NOT NULL CHECK(issue_kind IN (
    'EMPTY_FACT','EMPTY_PHRASE_POOL','TRANSLATION_MISSING','UNBOUND_PLACEHOLDER','IMAGE_UNBOUND')),
  -- 人能读的原因（指到具体字段 / 短语池类型 / 插图位），不是异常类名。
  detail text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一段落里同一个占位符只报一次：正文里 {brand} 出现三次也只是同一个缺失。
  UNIQUE(tenant_id,render_section_id,variable_name),
  FOREIGN KEY(tenant_id,render_section_id) REFERENCES description_render_section(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,render_id) REFERENCES description_render(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id)
);

-- ============================================================ 3. 触发器
--
-- ---- 段落 HTML 里不许残留占位符（头部第 3 条，逐条兜底）
--
-- 与 CHECK 互为纵深：CHECK 在 PG 里可以被 NOT VALID / 迁移脚本绕过，触发器不行。
-- 宁可让写入失败，也不能让 `{{尺寸}}` 原文发到平台上。
-- RAISE 的格式串里 %% 表示一个字面量 %，所以下面那条 LIKE 的百分号要写两个。
CREATE FUNCTION guard_description_render_section_html() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.html LIKE '%{%' THEN
  RAISE EXCEPTION '渲染出的段落 HTML 里残留了占位符：语言 %，正文开头「%」——4.2.2.2 的硬要求是「变量未绑定必须显式提示，不允许把 {{尺寸}} 原样发到平台」。缺变量的段落请写成 INCOMPLETE 并在 description_render_issue 里报告原因',
    NEW.locale, LEFT(NEW.html, 40) USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_description_render_section_html BEFORE INSERT OR UPDATE ON description_render_section
  FOR EACH ROW EXECUTE FUNCTION guard_description_render_section_html();

-- ---- INCOMPLETE 不能被直接改成 COMPLETE（头部第 4 条）
--
-- 与 V21 的 guard_content_render_section_complete 同一纪律：缺掉的变量不会因为
-- 改个状态就回来。要变成 COMPLETE 只能重跑渲染，那时占位符确实有值了。
CREATE FUNCTION guard_description_render_section_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE issues bigint;
BEGIN
 IF OLD.status='INCOMPLETE' AND NEW.status='COMPLETE' THEN
  SELECT count(*) INTO issues FROM description_render_issue WHERE render_section_id=NEW.id;
  RAISE EXCEPTION '渲染段落还有 % 个缺失变量或缺图，不能直接标记为已填好：缺掉的内容不会因为改状态就回来。要修请补上模板的变量绑定、短语池或插图位后重新渲染（语言 %）',
    issues, NEW.locale USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_description_render_section_complete BEFORE UPDATE ON description_render_section
  FOR EACH ROW EXECUTE FUNCTION guard_description_render_section_complete();

-- ---- 整份 HTML 也不许残留占位符（头部第 5 条）
--
-- 段落都干净但**拼装**时被插回了占位符，批次层同样拒收：
-- 拼装代码是本模块自己写的，必须自己也被同一道闸钉住。
CREATE FUNCTION guard_description_render_html() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='SUCCEEDED' AND NEW.html LIKE '%{%' THEN
  RAISE EXCEPTION '渲染结果被标成「已填好」，但整份描述 HTML 里仍有占位符（开头「%」）——4.2.2.2 的硬要求是「变量未绑定必须显式提示，不允许把 {{尺寸}} 原样发到平台」。要么把缺失降级成 PARTIAL 并报告，要么补齐绑定后重渲染',
    LEFT(NEW.html, 40) USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_description_render_html BEFORE INSERT OR UPDATE ON description_render
  FOR EACH ROW EXECUTE FUNCTION guard_description_render_html();

-- ---- 描述模板被渲染结果引用时不允许删
--
-- 与 V21 的 refuse_content_template_delete 同一纪律：删模板会级联删掉它的
-- 五个段落、段落背后的 content_template 及其全部译文，而 description_render_section
-- 溯源指向它们——历史 HTML 是按那套段落与变量绑定渲染出来的。
CREATE FUNCTION refuse_description_template_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE runs bigint;
BEGIN
 SELECT count(*) INTO runs FROM description_render r
  WHERE r.tenant_id=OLD.tenant_id AND r.template_id=OLD.id;
 IF runs>0 THEN
  RAISE EXCEPTION '描述模板「%」已被 % 次渲染引用，不可删除：历史渲染的 HTML 是按当时的段落与变量绑定产出的。请改用停用（停用后不能再用它新建渲染，历史渲染照常可读）',
    OLD.code, runs USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_description_template_delete BEFORE DELETE ON description_template
  FOR EACH ROW EXECUTE FUNCTION refuse_description_template_delete();

-- ---- 段落背后的 content_template 被描述模板引用时不允许删
--
-- 这是本文件**唯一**挂在 V21 表上的触发器，所以单独说明它为什么不越界：
-- 它只在「该 content_template 已经是某个描述模板的段落」时才拦，
-- 对 3.4.2.2 自己的模板（没有任何描述模板引用）行为完全不变——V21 自己的
-- refuse_content_template_delete 照旧生效，两者并存、互不覆盖。
--
-- 没有它的话，删除描述段落会撞出一条裸的 FK 违反（23503），
-- 运营看到的是机器报错而不是「这段文案还挂在某个描述模板里」。
CREATE FUNCTION refuse_description_section_content_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_code text;
BEGIN
 SELECT t.code INTO owner_code
  FROM description_template_section s
   JOIN description_template t ON t.tenant_id=s.tenant_id AND t.id=s.template_id
  WHERE s.tenant_id=OLD.tenant_id AND s.content_template_id=OLD.id;
 IF owner_code IS NOT NULL THEN
  RAISE EXCEPTION '内容模板「%」正作为描述模板「%」的段落在使用，不可单独删除：请改段落正文（3.4.2.2 的内容模板编辑），或停用那个描述模板后连它一起删',
    OLD.code, owner_code USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_description_section_content_delete BEFORE DELETE ON content_template
  FOR EACH ROW EXECUTE FUNCTION refuse_description_section_content_delete();

-- ============================================================ 4. 索引
--
-- 模板字典列表与读取。
CREATE INDEX description_template_list ON description_template(tenant_id, enabled, code);
-- 渲染一次要按 sort_order 读全部段落（「这个模板有哪几段、按什么顺序」）。
CREATE INDEX description_section_by_template ON description_template_section(tenant_id, template_id, sort_order, id);
-- 按段落类型筛（「哪些描述模板还没配售后承诺段」）。
CREATE INDEX description_section_by_type ON description_template_section(tenant_id, section_type, template_id);
-- 删除保护要按 content_template_id 反查。
CREATE INDEX description_section_by_content_template ON description_template_section(tenant_id, content_template_id);
-- 渲染一次要按 slot_no 读某段的全部插图位。
CREATE INDEX description_image_slot_by_section ON description_image_slot(tenant_id, section_id, slot_no);
-- 渲染结果列表：按创建时间倒序（页面上最常见的是「我刚渲染的那份」）。
CREATE INDEX description_render_list ON description_render(tenant_id, created_at DESC, id DESC);
CREATE INDEX description_render_by_template ON description_render(tenant_id, template_id, created_at DESC);
-- 04 刊登取「这个 SKU 在这个语言下的最新一份 HTML」的主查询形状。
CREATE INDEX description_render_by_sku ON description_render(tenant_id, sku_id, locale, created_at DESC, id DESC);
-- **部分索引：只索引全部填好的渲染**。刊登与平台上传只引用 SUCCEEDED，
-- 而跑上几个月后 PARTIAL（模板待补变量）会远多于 SUCCEEDED。
CREATE INDEX description_render_publishable ON description_render(tenant_id, sku_id, locale, template_id)
  WHERE status='SUCCEEDED';
-- 渲染详情页按段落顺序读。
CREATE INDEX description_render_section_by_render ON description_render_section(tenant_id, render_id, sort_order);
-- 图片落点按渲染全局序号读。
CREATE INDEX description_render_image_by_render ON description_render_image(tenant_id, render_id, position);
-- 缺失报告：渲染详情页逐条列出，以及「这个变量历史上缺过多少次」。
CREATE INDEX description_render_issue_by_render ON description_render_issue(tenant_id, render_id);
CREATE INDEX description_render_issue_by_variable ON description_render_issue(tenant_id, variable_name, created_at DESC);

-- ============================================================ 5. RLS 与授权
--
-- 七张表全部租户级数据（描述模板是租户共享资产，与 V21 的 content_* 同一形状）：
-- 同一运营要在别人的商品上复用描述模板，不套 V3 的 owner_id/department_id 数据范围。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['description_template','description_template_section',
   'description_image_slot','description_render','description_render_section',
   'description_render_image','description_render_issue'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  description_template, description_template_section, description_image_slot,
  description_render, description_render_section, description_render_image, description_render_issue
  TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  description_template, description_template_section, description_image_slot,
  description_render, description_render_section, description_render_image, description_render_issue
  TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **4.2.1.1 刊登编辑器**：取 description_render 里 status='SUCCEEDED' 的
--     html（按 template_id / sku_id / locale），需要结构化视图时取
--     description_render_section 的逐段 HTML 与 description_render_image 的
--     position/alt。本模块不感知 listing，也不写刊登状态。
--   * **4.2.2.1 刊登模板**：套用刊登模板时可以指定一个 description_template，
--     产出「内容草稿」时直接带渲染好的 HTML。
--   * **3.4.2.2 描述与卖点库**：段落正文 / 变量 / 译文 / 短语全部在 V21 那套表里，
--     本模块不复制内容，只组装。改文案去 3.4.2.2，改骨架来本模块。
--   * **3.4.3.1 多语言内容**：译文行的 origin='AI' 就是 AI 翻译预填的落点。
