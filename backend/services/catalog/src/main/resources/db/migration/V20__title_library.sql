-- 3.4.2.1 标题库：标题模板（变量占位）、标题词根字典、批量生成批次、生成的标题记录。
--
-- 蓝图 3.4.2.1 的原话：
--   「沉淀可复用的标题模板与标题记录，支持变量占位（品牌+品类+核心词+卖点），
--     批量生成差异化标题（同一商品在多平台不同标题以避重复铺货判定）。」
--   「输入：标题模板；批量生成参数（SKU 范围、平台、词根）。」
--   「输出：生成的标题候选集；供 04 选用或导出。」
--   「上游 = 3.1.1.1；下游 = 04 刊登。**批量生成要保证"相似但不雷同"**，
--     需在生成算法中显式控制重复度。」
--
-- ============================================================ 与 3.4.2.2 描述与卖点库的边界
--
-- 3.4.2.2 管「详情描述模板、卖点短语库、使用说明、注意事项、多语言版本」，
-- 那是**长文本**在详情页/说明书里的填充。本模块的 title_keyword 是**标题用的短词根**：
-- 一个词根是一个可用于标题变量占位的短语（≤ 60 字符），它服务于
-- 「标题里放哪几个词」而不是「详情描述里怎么讲这个卖点」。
-- 两者服务不同的排版约束（标题有硬字符上限、详情没有），
-- 把长文本卖点库塞进标题变量只会让标题长度控制失去意义，所以本文件不建描述/长卖点表。
--
-- ============================================================ 「显式控制重复度」在 DB 层的落点
--
-- 蓝图的算法要求不能只靠服务层自觉，否则一次手工 SQL 就能绕过。所以下面四件事钉在数据里：
--   1. title_template.max_similarity_permille CHECK(<= 999)
--      —— 999 而不是 1000：1000‰ 就是「允许与已有标题完全一样」，
--      那等于没有重复度控制。上限 999 让**完全雷同**在 DB 层就不可能被配出来。
--   2. title_record.similarity_permille CHECK(0..999)
--      —— 每条生成的标题都必须带着「它与本 SKU 已有标题的最大相似度」落库，
--      没有这一列就无法回答「这批标题的重复度到底控制住了没有」。
--   3. title_record UNIQUE(tenant_id,generation_id,sku_id,platform,variant_no)
--      —— 同一条生成里，同一 SKU 同一平台的第 N 条候选只有一个位置，
--      两条候选不可能靠「同序号」混进来。
--   4. guard_title_record_selected_immutable（BEFORE UPDATE）
--      —— 一旦被选用（04 刊登要用的那条），它的文本/平台/SKU/模板就不可再改。
--      否则库里记的标题与平台上已经发布的内容对不上，且再也解释不清是哪一版发出去的。
--
-- ============================================================ 1. 标题模板
--
-- 模板是**租户级字典**（与 media_process_recipe / brand / unit_group 同级）：
-- 「亚马逊主图标题的排法」「eBay 的排法」要反复用，批处理时每次重填既容易错，
-- 也让「这批标题为什么长这样」无法回溯。
--
-- pattern 是带占位符的模板文本，形如 `{brand} {category} {core} {selling}`。
-- **字面量与占位符写在同一串里**（分隔符就是 pattern 里的空格/短横线本身），
-- 所以不需要另设「分隔符风格」列：分隔风格不同的模板直接是两个模板，
-- 租户想覆盖各平台写法时改的是模板，不是模板加一堆开关。
CREATE TABLE title_template (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  name varchar(100) NOT NULL CHECK(btrim(name)<>''),
  note text NOT NULL DEFAULT '',
  -- 变量占位模板。规范化（折叠连续空白、去首尾空白）由服务层做，库里存的是规范形，
  -- 这样「同一个模板写成两种空白」不会被当成两个模板，变量解析也不会被多空格坑到。
  pattern text NOT NULL CHECK(btrim(pattern)<>''),
  -- **重复度上限（千分比）**：任意两条标题的相似度超过它就换下一个组合。
  -- 上限 999 而不是 1000，见头部第 1 条。
  max_similarity_permille int NOT NULL DEFAULT 850
    CHECK(max_similarity_permille BETWEEN 0 AND 999),
  -- 每个 SKU 每个平台产出几条候选。
  candidate_count int NOT NULL DEFAULT 3 CHECK(candidate_count BETWEEN 1 AND 8),
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  -- 业务时间与系统时间分开：created_at 是运营「什么时候建的模板」，
  -- updated_at 是最后改动时刻。批量生成是同步的短事务，没有 started/finished 可分。
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code)
);

-- ------------------------------------------------------------ 模板的变量声明
--
-- 变量拆成子表而不是在模板上放一个数组：变量要能按顺序被查（「这条标题的第二个词是什么」），
-- 数组列查不了；而且变量名在模板内必须唯一，否则同一个占位符有两个绑定源时无法解析。
CREATE TABLE title_template_variable (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  -- slot_no 是**渲染顺序**：第 0 个变量紧跟 pattern 开头，第 1 个紧随其后……
  -- 生成算法做词序轮转时按 slot_no 循环左移，这就是「相似但不雷同」的第一个轴。
  slot_no int NOT NULL CHECK(slot_no>=0),
  -- 占位符名（不含花括号），pattern 里写 {brand}。
  variable_name varchar(32) NOT NULL CHECK(btrim(variable_name)<>''),
  -- 绑定源：事实字段从 SPU/SKU 取，词根类从 title_keyword 取。
  -- 枚举化而不是自由文本：每个源对应服务层里一段确定的取值代码，
  -- 自由文本会让「{x} 绑定到什么」这个问题在渲染时才有答案（那时已经错了）。
  source text NOT NULL CHECK(source IN (
    'SPU_BRAND','SPU_CATEGORY','SPU_NAME','SKU_NAME','SKU_CODE','SKU_SPEC',
    'KEYWORD_CORE','KEYWORD_SELLING','KEYWORD_MODIFIER')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一模板内变量名唯一：一个占位符只能有一个绑定源。
  UNIQUE(tenant_id,template_id,variable_name),
  -- 同一模板内槽位唯一：渲染顺序不能有两个第 0 位。
  UNIQUE(tenant_id,template_id,slot_no),
  FOREIGN KEY(tenant_id,template_id) REFERENCES title_template(tenant_id,id) ON DELETE CASCADE
);

-- ------------------------------------------------------------ 模板的各平台长度上限
--
-- 平台标题有硬字符上限（eBay 80、亚马逊 200、Shopify 255…），而批量生成会同时发给多个平台，
-- 所以长度上限必须**按平台**而不是按模板只有一个值。模板没配平台的批次直接被拒，
-- 不用「悄悄退回一个默认值」——那会让运营以为自己发到 eBay 的标题没超长。
CREATE TABLE title_template_platform (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  -- 目标平台：自由文本而不是跨服务 FK 到 channel 服务的平台表（模块边界纪律）。
  platform varchar(64) NOT NULL CHECK(btrim(platform)<>''),
  max_length int NOT NULL CHECK(max_length BETWEEN 1 AND 255),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,template_id,platform),
  FOREIGN KEY(tenant_id,template_id) REFERENCES title_template(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 2. 标题词根字典
--
-- 「批量生成参数（SKU 范围、平台、**词根**）」里的词根就是这里的行。
-- 三类：核心词（品类/用途主体）、卖点（标题里的卖点短语）、修饰词（差异化用）。
--
-- **修饰词（MODIFIER）是「相似但不雷同」的主力**：不同平台的标题靠换修饰词拉开距离，
-- 而它必须是**独立的词池**而不是现场造词——现场造词会造出「品质优良」这种到处都能用的废话，
-- 那种词对平台判定来说等于没改。
CREATE TABLE title_keyword (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  text varchar(60) NOT NULL CHECK(btrim(text)<>''),
  keyword_type text NOT NULL CHECK(keyword_type IN ('CORE','SELLING','MODIFIER')),
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  -- 同一类型内文本唯一：同一个词写成两条会让词池「看起来更大」，
  -- 生成的候选却还是那几种，重复度控制因此被绕过而没人发现。
  UNIQUE(tenant_id,keyword_type,text)
);

-- ============================================================ 3. 批量生成批次
--
-- 蓝图说「批量生成」。生成本身是纯字符串计算（毫秒级），**不做异步任务**——
-- 与 3.4.1.2 的图片处理不同，那里的长耗时来自逐像素加工，而这里的成本只与
-- 「同一 SKU 内的候选数」有关（重复度比较的范围见 title_record 段落的说明），
-- 几百个 SKU 一次生成是几十毫秒。做成异步任务只会让运营为一个几百毫秒的操作去盯进度条。
--
-- status 三态（同步执行，没有「排队中」这种自欺欺人的状态）：
-- SUCCEEDED 全部产出候选；PARTIAL 有 SKU 被拒（词根池太小、变量全部渲染为空等）；
-- FAILED 一个候选都没产出。
CREATE TABLE title_generation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  requested_by uuid NOT NULL,
  status text NOT NULL CHECK(status IN ('SUCCEEDED','PARTIAL','FAILED')),
  -- 提交时选中的 SKU 数（写死，不用 count：列表页要显示 N/N，而 count 会随候选生成变动）。
  sku_count int NOT NULL CHECK(sku_count>=0),
  -- 产出的标题候选总数与因重复度上限被拒的候选数。
  candidate_count int NOT NULL DEFAULT 0 CHECK(candidate_count>=0),
  rejected_count int NOT NULL DEFAULT 0 CHECK(rejected_count>=0),
  -- 词根过滤：本次限定了哪些核心词 code（已排序、逗号分隔）。空串表示用全部启用的核心词。
  -- 这是**请求回显**（解释「这批为什么只用了这个词根」）；
  -- 真正可查询的溯源在 title_record.core_keyword 上——每条候选都快照了它实际用到的词。
  keyword_filter text NOT NULL DEFAULT '',
  -- 可执行的中文失败原因，不是异常类名。
  error text NOT NULL DEFAULT '',
  -- 业务时间与系统时间分开：created_at 是运营「几点点的生成」，
  -- finished_at 是系统「什么时候真的算完」。批量虽然快，但失败排查时要区分
  -- 「什么时候点的」与「什么时候出的结果」。
  created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,template_id) REFERENCES title_template(tenant_id,id)
);

-- ------------------------------------------------------------ 逐 SKU 结果
--
-- 一行 = 一个 SKU 在这次生成里的完整结果：产出了几条、被拒了几条、为什么。
-- 批次级只有计数，问「哪个 SKU 一个候选都没出来」必须能落到行上。
CREATE TABLE title_generation_sku (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, generation_id uuid NOT NULL, sku_id uuid NOT NULL,
  outcome text NOT NULL CHECK(outcome IN ('GENERATED','REJECTED')),
  candidate_count int NOT NULL DEFAULT 0 CHECK(candidate_count>=0),
  rejected_count int NOT NULL DEFAULT 0 CHECK(rejected_count>=0),
  reason text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一批次里同一个 SKU 只出现一次：重复提交会让同一 SKU 产出两套候选。
  UNIQUE(tenant_id,generation_id,sku_id),
  FOREIGN KEY(tenant_id,generation_id) REFERENCES title_generation(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id)
);

-- ============================================================ 4. 生成的标题记录（候选集）
--
-- 一行 = 一个 SKU 在一个平台上的一条候选标题。这是蓝图点名的输出
-- 「生成的标题候选集；供 04 选用或导出」的落地表。
--
-- ------------------------------------------------------------ 重复度的比较范围
--
-- **相似度只在「同一 SKU」内比较**（该 SKU 已有 + 本次新生成的全部候选），
-- 不跨 SKU 比。理由：平台判重复铺货看的是**同一个商品在不同站点/店铺的标题是否雷同**，
-- 而两个不同商品标题相似是品类共性（例如「收纳盒」），判重复铺货时反倒不会被拿来对质。
-- 范围限定在单个 SKU 也让计算量有界：比较对数与「本 SKU 的候选数」平方成正比，
-- 与批次里的 SKU 总数无关，所以几百个 SKU 一次生成仍然是毫秒级。
--
-- similarity_permille 记的是这条标题与「本 SKU 已有 + 本次已生成」的最大相似度（千分比），
-- 也就是它离重复铺货判定有多远。0 = 与其它标题毫无共同词。
CREATE TABLE title_record (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  generation_id uuid NOT NULL, template_id uuid NOT NULL, sku_id uuid NOT NULL,
  platform varchar(64) NOT NULL CHECK(btrim(platform)<>''),
  -- 第几条候选（1 起）。同一条生成里同 SKU 同平台的第 N 条只有一个位置。
  variant_no int NOT NULL CHECK(variant_no>=1),
  title text NOT NULL CHECK(btrim(title)<>''),
  -- 字符数按**码点**算（中日韩一个字、emoji 一个码点），不是 UTF-16 的 char 数。
  char_length int NOT NULL CHECK(char_length>0),
  -- 因超过平台长度上限而被确定性裁剪过。被裁的标题对平台判定仍可用，
  -- 但界面上要标出来——运营需要知道这条标题在 eBay 上是被砍过的。
  truncated boolean NOT NULL DEFAULT false,
  -- 用的第几档词序轮转（0 起）。与 rotation + 修饰词快照一起解释「这条是怎么生成的」。
  rotation int NOT NULL CHECK(rotation>=0),
  -- 与本 SKU 已有 + 本次已生成标题的最大相似度（千分比）。见头部第 2 条。
  similarity_permille int NOT NULL CHECK(similarity_permille BETWEEN 0 AND 999),
  -- 实际绑定的词根快照：改了词库之后仍能解释「这条标题当时用的是哪个词」。
  core_keyword text NOT NULL DEFAULT '',
  selling_keyword text NOT NULL DEFAULT '',
  modifier_keyword text NOT NULL DEFAULT '',
  -- 候选状态：CANDIDATE 待选用（蓝图输出就是候选集）；SELECTED 已被 04 选用。
  -- REJECTED 保留被淘汰的候选：批量生成里被重复度闸门拒掉的那些也要留痕，
  -- 否则「为什么这个 SKU 只有 2 条」无从回答。
  status text NOT NULL DEFAULT 'CANDIDATE'
    CHECK(status IN ('CANDIDATE','SELECTED','REJECTED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  -- 重复度控制的第三条 DB 落点：同序号不可能有两条。
  UNIQUE(tenant_id,generation_id,sku_id,platform,variant_no),
  FOREIGN KEY(tenant_id,generation_id) REFERENCES title_generation(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,template_id) REFERENCES title_template(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 5. 触发器：选用后不可改、已选用不可删
--
-- 蓝图输出是「供 04 选用或导出」。一旦某条标题被 SELECTED，04 就会照它刊登，
-- 于是「库里这条标题」变成了「平台上已经发布的那句话」的权威记录。改它就等于
-- 让记录和现实对不上，且再也解释不清发出去的是哪一版。
--
-- 只挡**内容列**（title/platform/sku/template/generation）与时间列；
-- 允许改的只有 status（CANDIDATE ↔ SELECTED 互转，即「选用 / 取消选用」）
-- 与 updated_at/version 这类记账列。用列级比较而不是「SELECTED 后整行冻结」，
-- 是因为取消选用是正常业务动作（04 改主意了）。
CREATE FUNCTION guard_title_record_selected_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='SELECTED' AND (
      NEW.title IS DISTINCT FROM OLD.title
   OR NEW.platform IS DISTINCT FROM OLD.platform
   OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
   OR NEW.template_id IS DISTINCT FROM OLD.template_id
   OR NEW.generation_id IS DISTINCT FROM OLD.generation_id
   OR NEW.char_length IS DISTINCT FROM OLD.char_length
   OR NEW.truncated IS DISTINCT FROM OLD.truncated
 ) THEN
  RAISE EXCEPTION '标题「%」已被选用，不可修改内容：3.4.2.1 的候选集是 04 刊登的输入，改了它库里记的和平台上已发布的对不上。要换标题请先取消选用，再改或新建候选',
    LEFT(OLD.title, 60) USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_title_record_selected_immutable BEFORE UPDATE ON title_record
  FOR EACH ROW EXECUTE FUNCTION guard_title_record_selected_immutable();

-- 已选用的标题不可删除：04 可能已经照它刊登，删掉之后那条 listing 就失去了溯源。
-- 未选用的候选可以删（生成器重来一次就会清掉整批，见 refuse_title_generation_record_delete）。
CREATE FUNCTION refuse_selected_title_record_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='SELECTED' THEN
  RAISE EXCEPTION '标题「%」已被选用，不可删除：04 刊登可能已照它发布，删掉之后那条 listing 就无法溯源。要清理请先取消选用',
    LEFT(OLD.title, 60) USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_selected_title_record_delete BEFORE DELETE ON title_record
  FOR EACH ROW EXECUTE FUNCTION refuse_selected_title_record_delete();

-- 模板被生成批次引用时不允许删（配置可以停用，被事实引用的历史不能被抹掉）。
-- 与 V19 的 refuse_media_process_recipe_delete 同一纪律：删模板会让它的变量与平台长度上限
-- 一起级联删掉，而 title_record.template_id 指向它、历史候选是按那套上限裁出来的。
CREATE FUNCTION refuse_title_template_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE runs bigint;
BEGIN
 SELECT count(*) INTO runs FROM title_generation g
  WHERE g.tenant_id=OLD.tenant_id AND g.template_id=OLD.id;
 IF runs>0 THEN
  RAISE EXCEPTION '标题模板「%」已被 % 次批量生成引用，不可删除：请改用停用（停用后不能再用它新建批次，历史标题与候选照常可读）',
    OLD.code, runs USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_title_template_delete BEFORE DELETE ON title_template
  FOR EACH ROW EXECUTE FUNCTION refuse_title_template_delete();

-- 词根被标题记录引用时不允许删：title_record 上快照的是**文本**，
-- 词库里的行被删掉之后，那条文本就成了没有出处的孤儿。
CREATE FUNCTION refuse_title_keyword_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE refs bigint;
BEGIN
 SELECT count(*) INTO refs FROM title_record r
  WHERE r.tenant_id=OLD.tenant_id AND (
       (OLD.keyword_type='CORE'      AND r.core_keyword=OLD.text)
    OR (OLD.keyword_type='SELLING'   AND r.selling_keyword=OLD.text)
    OR (OLD.keyword_type='MODIFIER'  AND r.modifier_keyword=OLD.text));
 IF refs>0 THEN
  RAISE EXCEPTION '词根「%」已被 % 条生成的标题引用，不可删除：标题记录上快照的是它的文本，删掉词库行那条文本就成了没有出处的孤儿。请改用停用',
    OLD.text, refs USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_title_keyword_delete BEFORE DELETE ON title_keyword
  FOR EACH ROW EXECUTE FUNCTION refuse_title_keyword_delete();

-- ============================================================ 6. 索引
--
-- 批次列表页：按创建时间倒序（页面上最常见的是「我刚生成的那批」）。
CREATE INDEX title_generation_list ON title_generation(tenant_id, created_at DESC, id DESC);
CREATE INDEX title_generation_by_template ON title_generation(tenant_id, template_id, created_at DESC);
-- 逐 SKU 结果按批次读。
CREATE INDEX title_generation_sku_by_generation ON title_generation_sku(tenant_id, generation_id, outcome, created_at);
-- 候选列表：按 SKU + 平台筛（「这个商品在 eBay 上有哪些候选」——重复度比较的范围查询）。
CREATE INDEX title_record_by_sku ON title_record(tenant_id, sku_id, platform, created_at DESC, id DESC);
-- 按批次读候选（批次详情页）。
CREATE INDEX title_record_by_generation ON title_record(tenant_id, generation_id, sku_id, platform, variant_no);
-- 按状态筛（「这批里哪些还没选用」）。部分索引：终态行远多于在跑的行，
-- 素材库/标题库跑上几个月后绝大多数条目都已被选用过。
CREATE INDEX title_record_pending ON title_record(tenant_id, status, created_at DESC, id DESC)
  WHERE status='CANDIDATE';
-- 同一 SKU 同一平台只能选用一条标题：两个平台上架的是同一个商品，
-- 两条都「已选用」意味着将来 04 不知道该发哪条。
--
-- 用部分唯一索引（而不是全表 UNIQUE 加 status 列）是因为「只能选一条」这个约束
-- 只对已选中的行成立，CANDIDATE / REJECTED 之间不受限。
-- 切换选用时服务层必须先降级旧的再置新的（同一事务内两步），否则这里会直接撞约束。
CREATE UNIQUE INDEX title_record_one_selected ON title_record(tenant_id, sku_id, platform)
  WHERE status='SELECTED';
-- 词根删除守卫（refuse_title_keyword_delete）要按 text 三路扫 title_record，
-- 所以三列都建索引：只建一列的话另外两路仍是全表扫，而这条触发器每次删词根都走。
CREATE INDEX title_record_by_core ON title_record(tenant_id, core_keyword) WHERE core_keyword<>'';
CREATE INDEX title_record_by_selling ON title_record(tenant_id, selling_keyword) WHERE selling_keyword<>'';
CREATE INDEX title_record_by_modifier ON title_record(tenant_id, modifier_keyword) WHERE modifier_keyword<>'';
-- 模板字典列表与读取。
CREATE INDEX title_template_list ON title_template(tenant_id, enabled, code);
CREATE INDEX title_template_variable_by_template ON title_template_variable(tenant_id, template_id, slot_no, id);
CREATE INDEX title_template_platform_by_template ON title_template_platform(tenant_id, template_id, platform);
-- 词库按类型取词池（生成器每批都要按类型读三遍）。
CREATE INDEX title_keyword_by_type ON title_keyword(tenant_id, keyword_type, enabled, code);

-- ============================================================ 7. RLS 与授权
--
-- 七张表全部租户级数据（文案资产是租户共享库，与 media_* 同一形状）：
-- 标题与素材一样，同一运营要在别人的商品上复用标题模板，不套 V3 的 owner_id/department_id 数据范围。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['title_template','title_template_variable','title_template_platform',
   'title_keyword','title_generation','title_generation_sku','title_record'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  title_template, title_template_variable, title_template_platform, title_keyword,
  title_generation, title_generation_sku, title_record
  TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  title_template, title_template_variable, title_template_platform, title_keyword,
  title_generation, title_generation_sku, title_record
  TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **04 刊登**：候选集就是 title_record，刊登侧按 sku_id + platform 取 status='SELECTED' 的一条。
--     本模块不感知 listing，也不写任何刊登状态——那是 04 的事实。
--   * **多语言标题**：3.4.3.1 多语言内容管 SPU/SKU 的多语言名称与描述。
--     本模块的模板与词根是**单一语言**的（中文标题库），因为「同一语言内不雷同」
--     与「跨语言是否算重复」是两套判定，后者需要 18 的 AI 翻译参与，不在本模块范围。
--   * **3.4.2.2 描述与卖点库**：它管长文本与多语言描述，与本模块的短词根互不覆盖（见头部边界）。