-- 3.4.3.1 多语言内容：SPU/SKU 的多语言名称与描述（spu_translation / sku_translation），
-- 按语言唯一，支持人工校对与 AI 翻译的建议采纳流程。
--
-- 蓝图 3.4.3.1 的原话：
--   「职责：维护 SPU/SKU 的多语言名称与描述（`spu_translation` `sku_translation`），
--     按语言唯一，支持人工校对与 AI 翻译的建议采纳流程。」
--   「输入：翻译文本（人工/AI 建议）；语言列表（由店铺启用的站点决定实际需要哪些语言）。」
--   「输出：各语言的名称与描述记录 + 翻译状态（待翻/已翻/已校对）。」
--   「关系：上游 = 18 AI 翻译、4 刊登（实际站点需求）；下游 = 04 刊登填充。
--     **需要哪些语言由店铺启用站点推导**，不靠人肉记忆。」
--
-- ============================================================ 与 3.4.2.2 描述库的边界（务必读）
--
-- V21 已经建了带 `locale` 的 `content_template_translation` 与 `content_render_section`。
-- **那是「描述模板与其渲染段落」的多语言，不是「SPU/SKU 主数据的多语言名称与描述」。**
-- 两处 locale 是**不同的两套口径**，不要合并：
--
--   V21 content_template_translation.locale
--     主体 = content_template（租户级**文案模板**，与具体商品无关）
--     用途 = 3.4.2.2 的批量填充按 locale 取一份译文去拼正文
--     生命周期 = 跟着模板走；模板停用译文照常可读
--
--   本文件 spu_translation.locale / sku_translation.locale
--     主体 = **商品档案**（spu_id / sku_id，复合外键指向 V1 的 spu / sku）
--     用途 = 04 刊登按**站点语言**取「这个商品的德语标题与描述」
--     生命周期 = 跟着商品走；商品删掉译文随之删
--
-- 不复用对方表的四个理由（任意一条都足以单独成立）：
--   1. **主体不同**：一条模板译文能被 500 个商品复用，一条商品译文只属于一个商品。
--      合成一张表就得加「这行到底是模板的还是商品的」判别式，而 FK 表达不了「OR 关系」——
--      用表达式唯一索引 + 触发器兜，等于把参照完整性从 DB 降级到应用层。
--   2. **字段集不同**：模板译文是「模板名 + 带占位符的正文」，占位符集合必须与源模板相等
--      （V21 的硬要求）；商品译文的正文是**已填好的成品**，里面不允许再有 `{`。
--      混在一张表里，「这行要不要校验占位符」就成了每次读都要判断的事。
--   3. **删除语义相反**：删模板要连带删译文（V21 的 refuse_content_template_delete 已钉住），
--      而删一条商品译文只是「这个商品少一种语言」，是正常的日常操作。
--   4. **取值形状不同**：V21 的 locale 取自模板的 `source_locale`（运营选的一个源语言）；
--      这里的 locale 取自**店铺启用站点**（可能五六种，且随店铺开关变化）。
--
-- 把两套 locale 合并成一套，后面一定会出现「模板的德语译文被当成商品的德语译文填进刊登」，
-- 而两边数字谁也不对得上。本文件只认商品档案这一侧。
--
-- ============================================================ 「需要哪些语言由店铺启用站点推导」的落点
--
-- 蓝图标了「不靠人肉记忆」，但**店铺与站点是 02 渠道的事实**
-- （backend/services/channel 的 shop / shop_marketplace / marketplace.default_locale），
-- catalog 禁止跨服务 SQL 访问（AGENTS.md 模块边界纪律）。所以本文件**不建任何指向站点的列**：
--   * 站点 → 语言的推导走 `TranslationLocaleProjectionPort` 端口，
--     由 `UnwiredTranslationLocaleProjectionPort` 声明当前未接入，接口写明归 02 渠道；
--   * 未接入时**不编语言列表、不回 0、不回空数组**：
--     回 0 会被读成「这个店铺没有站点所以没有语言需求」，空数组会被读成
--     「不需要做多语言」——两者都是编造出来的假事实。
--   * 同时本文件提供 `translation_locale`：**租户可维护的语言需求来源**（人工维护），
--     让本模块在 02 渠道未接入时也有真实可写入、可校验的数据来源，
--     而不是一堆只能靠假数据撑起来的功能。
--
-- ============================================================ 三态「待翻 / 已翻 / 已校对」的落点
--
-- 蓝图的输出是「各语言的名称与描述记录 + 翻译状态（待翻/已翻/已校对）」。
-- 这里刻意**不用「行不存在」表示待翻**，而让 PENDING 是一等状态：
-- 「需要哪些语言」推导出来的待翻项必须落到可查的行上，否则「这个商品的德语还没翻」
-- 只能靠「查一下哪些行不存在」这种反直觉的口径得到，完整度报表也无从汇总。
--
-- 于是三个状态在 DB 层互斥且自带可判定的形状（不是服务层的自觉）：
--   PENDING    待翻：name 与 description 都必须为空（没有文本就谈不上已翻）
--   TRANSLATED 已翻：有文本，但还没人校对（AI 采纳后落在这里）
--   REVIEWED   已校对：有名称、有文本，且**必须留校对人与校对时刻**
--
-- 三条 CHECK + 一个触发器把它们钉死：
--   1. CHECK (status<>'PENDING' OR (name='' AND description=''))
--      —— 声称「还没翻」就不许有文本，否则「已校对」会因为改个状态就达成。
--   2. CHECK (status<>'REVIEWED' OR name<>'')
--      —— 声称「已校对」就必须有名称；空名称进不了刊登，标成已校对是自欺。
--   3. CHECK (status<>'REVIEWED' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
--      —— 「已校对」必须能回答「谁校对的、什么时候」。没有校对人的 REVIEWED
--      在数据里等于「有人看过」这种无法证伪的说法。
--   4. guard_translation_status（BEFORE UPDATE）
--      —— 两条规则服务层以外的纵深兜底：
--        (a) NEW.status='PENDING' 而正文非空 → 拒。正文不会因为改状态就消失，
--            静默退回 PENDING 会让已校对的文案从刊登可取范围里消失而无痕。
--        (b) OLD.status='PENDING' 而 NEW.status='REVIEWED' → 拒。待翻不能跳级，
--            因为「已翻」这一步正是「有没有人真的读过」的信号。
--
-- ============================================================ 1. 租户语言需求（人工维护的一侧）
--
-- 一行 = 租户需要的一种语言。source 区分这行是怎么来的：
--   MANUAL     人工维护（现在就写数据的入口，02 渠道未接入时也用得上）
--   SHOP_SITE  由 02 渠道的启用站点推导后同步进来（同一语言被人工也维护过时以 SHOP_SITE 为准）
--
-- **locale_name 是给人看的名字**（德语 / Japanese），不是权威语言登记；
-- BCP-47 标签才是键。与 V21 同一口径：catalog 不维护语言字典字典表。
CREATE TABLE translation_locale (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- BCP-47 简写：zh-CN / en-US / pt-BR。下划线写法（en_US）被服务层拒，
  -- 否则同一个语言的两种拼法各建一行，locale 唯一键形同虚设。
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  -- 展示名（运营自己写的「德语」「日语」）。locale 已经是键，这里只是给人读的。
  locale_name varchar(60) NOT NULL DEFAULT '',
  source text NOT NULL DEFAULT 'MANUAL' CHECK(source IN ('MANUAL','SHOP_SITE')),
  enabled boolean NOT NULL DEFAULT true,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一租户同一种语言只能有一行需求：两行时「我们到底需不需要德语」无法回答，
  -- 而完整度报表会把它算成两个语言。
  UNIQUE(tenant_id,locale)
);

-- ============================================================ 2. SPU 多语言名称与描述
--
-- 一行 = 一个 SPU 在一种语言下的名称与描述。蓝图点名的「按语言唯一」落在这里。
--
-- **不建成 spu_translation / sku_translation 合一的「多态表」**（哪怕只多一列 target_type）：
-- 合表就没法建复合外键，「译文必须挂在一个真实存在的商品上」这条只能降级成触发器；
-- 而删除语义也不同——删 SPU 要连带删它的译文，删 SKU 只删自己的。
-- 蓝图原文点名了两张表，这里就照蓝图分两张。
CREATE TABLE spu_translation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, spu_id uuid NOT NULL,
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  -- 商品名（刊登标题的中文源）。**允许为空**是因为 PENDING（待翻）这一态就是空的，
  -- 空与「有内容」同样是一等状态，见文件头三态说明。
  name varchar(200) NOT NULL DEFAULT '',
  -- 商品描述（刊登详情的中文源）。与 3.4.2.2 的模板正文不同：
  -- 这里是**已填好的成品**，不允许再出现 `{x}` 占位符（触发器逐条兜底）。
  description text NOT NULL DEFAULT '',
  -- 谁写的。与 V21 的 origin 同词表但**不共享行**（见文件头边界）。
  origin text NOT NULL DEFAULT 'MANUAL' CHECK(origin IN ('MANUAL','AI','IMPORTED')),
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','TRANSLATED','REVIEWED')),
  -- 人工校对留痕。status='REVIEWED' 时两者都必须有值（CHECK 3）。
  reviewed_by uuid, reviewed_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  -- created_at 是业务时间（运营什么时候开始管这个语言的这条商品），
  -- updated_at / reviewed_at 是系统时间（改动与校对动作发生的时刻），三者分开存。
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  translated_at timestamptz,
  UNIQUE(tenant_id,id),
  -- 蓝图「按语言唯一」：一个 SPU 同一种语言只能有一条译文。
  -- 两条时「这个 SPU 的德语标题是哪个」无法回答，而 04 刊登按 (spu, locale) 取一条。
  UNIQUE(tenant_id,spu_id,locale),
  -- 成品译文里不许残留占位符：占位符是 3.4.2.2 的东西，
  -- 混进商品多语言名称/描述会原样发到平台上（触发器再兜一次底）。
  CONSTRAINT spu_translation_no_placeholder CHECK (name NOT LIKE '%{%' AND description NOT LIKE '%{%'),
  CONSTRAINT spu_translation_pending_is_empty CHECK (status<>'PENDING' OR (name='' AND description='')),
  CONSTRAINT spu_translation_reviewed_needs_name CHECK (status<>'REVIEWED' OR name<>''),
  CONSTRAINT spu_translation_reviewed_needs_reviewer
    CHECK (status<>'REVIEWED' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
  -- 非待翻必须真的有文本，否则「已翻」是个没有内容的标签。
  CONSTRAINT spu_translation_translated_needs_text CHECK (status='PENDING' OR name<>'' OR description<>''),
  FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE
);

-- ------------------------------------------------------------ SKU 多语言名称与描述
--
-- 与 SPU 同形但**不合并**：SKUID 的生命周期、删除级联与商品维度统计都独立
-- （一个 SPU 的 20 个 SKU 可能只有 3 个需要德语）。
CREATE TABLE sku_translation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  name varchar(200) NOT NULL DEFAULT '',
  description text NOT NULL DEFAULT '',
  origin text NOT NULL DEFAULT 'MANUAL' CHECK(origin IN ('MANUAL','AI','IMPORTED')),
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','TRANSLATED','REVIEWED')),
  reviewed_by uuid, reviewed_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  translated_at timestamptz,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,sku_id,locale),
  CONSTRAINT sku_translation_no_placeholder CHECK (name NOT LIKE '%{%' AND description NOT LIKE '%{%'),
  CONSTRAINT sku_translation_pending_is_empty CHECK (status<>'PENDING' OR (name='' AND description='')),
  CONSTRAINT sku_translation_reviewed_needs_name CHECK (status<>'REVIEWED' OR name<>''),
  CONSTRAINT sku_translation_reviewed_needs_reviewer
    CHECK (status<>'REVIEWED' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
  CONSTRAINT sku_translation_translated_needs_text CHECK (status='PENDING' OR name<>'' OR description<>''),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 3. AI 翻译建议与采纳流程
--
-- 蓝图「支持人工校对与 AI 翻译的建议采纳流程」的落地表。一行 = 一条还没被处置的
-- （商品, 语言）AI 译文建议，或一条已经被采纳/驳回的处置结果。
--
-- **AI 建议的正文快照在本表自己身上**：18 AI 翻译把译文写回这里，catalog 不调 AI
-- （18 是另一个服务，调它等于跨服务依赖调用顺序）。采纳时正文写进
-- spu_translation / sku_translation，本表保留快照 —— 这样「当初 AI 给的是哪一版」
-- 在译文被人工改过之后仍然答得上来。
CREATE TABLE translation_suggestion (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 建议挂在哪个主体上。CHECK 保证恰好指向一个商品（与 V18 的 media_reference 同一纪律）。
  target_type text NOT NULL CHECK(target_type IN ('SPU','SKU')),
  spu_id uuid, sku_id uuid,
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  -- 建议正文（AI 给的译文，或人工草拟的候选）。
  suggested_name varchar(200) NOT NULL DEFAULT '',
  suggested_description text NOT NULL DEFAULT '',
  -- 谁提的。AI 是蓝图点名的来源，MANUAL 是「这次不调 AI，我手写一版」，
  -- IMPORTED 是从既有商品内容搬过来的。
  origin text NOT NULL DEFAULT 'AI' CHECK(origin IN ('AI','MANUAL','IMPORTED')),
  -- 哪个模型出的（自由文本，不跨服务建 FK —— 模型清单归 18）。
  -- 只在 origin='AI' 时有意义，其余留空即可。
  model_name varchar(100) NOT NULL DEFAULT '',
  -- 18 一次翻一批，用它把同一批建议归组（「这 30 条一起采纳 / 一起驳回」）。
  -- 不是外键：批次是 18 的编排概念，catalog 不拥有它。
  batch_ref varchar(64) NOT NULL DEFAULT '',
  -- 处置状态。ADOPTED / REJECTED 是**终态**（触发器钉住），
  -- 采纳错了的补救办法是直接改译文内容，不是把建议改回 PENDING 再走一遍
  -- ——否则同一条建议两次采纳会产出两条不同的译文历史，无从判断哪次算数。
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ADOPTED','REJECTED')),
  -- 处置留痕：谁在什么时候采纳或驳回的。
  decided_by uuid, decided_at timestamptz,
  -- 驳回理由（可执行的中文原因，不是异常类名）。采纳后为空。
  reject_reason text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 恰好指向一个主体：不能两个都空（那行谁提的？），也不能两个都有（那它挂哪儿？）。
  CHECK ((target_type='SPU' AND spu_id IS NOT NULL AND sku_id IS NULL)
      OR (target_type='SKU' AND sku_id IS NOT NULL AND spu_id IS NULL)),
  -- 建议正文不许有占位符（同上：成品多语言内容里没有占位符这回事）。
  CHECK (suggested_name NOT LIKE '%{%' AND suggested_description NOT LIKE '%{%'),
  -- 建议不能什么都没有，否则「采纳」采纳的是一个空字符串。
  CHECK (btrim(suggested_name)<>'' OR btrim(suggested_description)<>''),
  -- 已处置的建议必须有处置人与处置时刻。
  CHECK (status='PENDING' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
  -- 驳回必须写理由：不给理由的驳回让下一轮 AI 翻译无从改进。
  CHECK (status<>'REJECTED' OR btrim(reject_reason)<>''),
  FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 4. 批量操作批次
--
-- 蓝图的「批量」在本模块有三件真实的事，逐条落库而不是只回一个计数：
--   CREATE_PENDING    按语言需求为一批商品补「待翻」占位行（推导完语言后一键铺开）
--   ADOPT_SUGGESTIONS 批量采纳 18 交来的一批 AI 建议
--   MARK_REVIEWED     批量把已翻的译文标成已校对（运营看完一批以后一次性提交）
--
-- 同步执行（与 V20 标题生成、V21 批量填充同一判断）：三件事都是几百行的短事务，
-- 做成异步只会让运营为几百毫秒去盯进度条。所以没有「排队中」这种状态。
CREATE TABLE translation_batch (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  action text NOT NULL CHECK(action IN ('CREATE_PENDING','ADOPT_SUGGESTIONS','MARK_REVIEWED')),
  requested_by uuid NOT NULL,
  target_type text NOT NULL CHECK(target_type IN ('SPU','SKU')),
  -- 提交时选中的商品数（写死不用 count：列表页要显示 N/N，
  -- 而 count 会随待翻占位行被补出来而变化）。
  item_count int NOT NULL CHECK(item_count>=0),
  -- 本次涉及的期望语言数（ADOPT_SUGGESTIONS 时等于 0，见下）。
  locale_count int NOT NULL DEFAULT 0 CHECK(locale_count>=0),
  succeeded_count int NOT NULL DEFAULT 0 CHECK(succeeded_count>=0),
  skipped_count int NOT NULL DEFAULT 0 CHECK(skipped_count>=0),
  rejected_count int NOT NULL DEFAULT 0 CHECK(rejected_count>=0),
  status text NOT NULL CHECK(status IN ('SUCCEEDED','PARTIAL','FAILED')),
  -- 可执行的中文失败原因，不是异常类名。
  error text NOT NULL DEFAULT '',
  -- 业务时间（运营几点点的批量）与系统时间（真的算完了）分开存。
  created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  -- 计数自洽：三类结果之和等于提交时选中的商品数（按期望语言展开后的条数）。
  CONSTRAINT translation_batch_counts_add_up
    CHECK (item_count = succeeded_count + skipped_count + rejected_count)
);

-- ------------------------------------------------------------ 逐条结果
--
-- 一行 = 一次批量里一个（商品, 语言）的结果。失败与跳过**是一等公民**：
-- 「这批 50 个商品的德语，有 8 个因为已有译文被跳过」要能逐条回答。
--
-- UNIQUE 用 coalesce 折叠 NULL 的表达式索引：普通 UNIQUE 在这里挡不住「两行都指向同一个 SPU」
-- 因为 PostgreSQL 的 UNIQUE 认为 NULL 互不相等（与 V18 的 media_reference_dedupe 同一形状）。
CREATE TABLE translation_batch_item (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, batch_id uuid NOT NULL,
  target_type text NOT NULL CHECK(target_type IN ('SPU','SKU')),
  spu_id uuid, sku_id uuid,
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  outcome text NOT NULL CHECK(outcome IN ('SUCCEEDED','SKIPPED','REJECTED')),
  -- 人能读的原因（指到具体字段或具体建议），不是异常类名。
  reason text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  CHECK ((target_type='SPU' AND spu_id IS NOT NULL AND sku_id IS NULL)
      OR (target_type='SKU' AND sku_id IS NOT NULL AND spu_id IS NULL)),
  -- 驳回必须写理由（与 suggestion 同一纪律）。
  CHECK (outcome<>'REJECTED' OR btrim(reason)<>''),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES translation_batch(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 5. 触发器
--
-- ---- 译文状态流转（文件头第 4 条，服务层以外的纵深兜底）
--
-- CHECK 在 PostgreSQL 里可以被 NOT VALID / 迁移脚本绕过，触发器不行。
-- 两条规则在正常流程里根本触发不到 —— 它们的全部价值就是拦住绕过应用层的写入。
CREATE FUNCTION guard_translation_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='PENDING' AND (btrim(NEW.name)<>'' OR btrim(NEW.description)<>'') THEN
  RAISE EXCEPTION '带正文的译文不能退回「待翻」：正文不会因为改状态就消失，而静默退回待翻会让这条已填好的文案从刊登可取范围里消失且不留痕迹（% / %，语言 %）',
    CASE WHEN TG_TABLE_NAME='spu_translation' THEN 'SPU' ELSE 'SKU' END,
    LEFT(NEW.id::text,8), NEW.locale USING ERRCODE='23514';
 END IF;
 IF OLD.status='PENDING' AND NEW.status='REVIEWED' THEN
  RAISE EXCEPTION '「待翻」不能直接标成「已校对」：中间那一步「已翻」正是有没有人真的读过的信号。要直接校对请先填入译文（语言 %）',
    NEW.locale USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_spu_translation_status BEFORE UPDATE ON spu_translation
  FOR EACH ROW EXECUTE FUNCTION guard_translation_status();
CREATE TRIGGER guard_sku_translation_status BEFORE UPDATE ON sku_translation
  FOR EACH ROW EXECUTE FUNCTION guard_translation_status();

-- ---- 译文正文里不许残留占位符（与 CHECK 互为纵深）
--
-- 占位符是 3.4.2.2 模板正文的概念；商品多语言名称/描述是**已填好的成品**，
-- `{brand}` 原样发到平台上就是模板没被填。逐条兜底而不是只在 CHECK 里写。
CREATE FUNCTION guard_translation_no_placeholder() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.name LIKE '%{%' OR NEW.description LIKE '%{%' THEN
  RAISE EXCEPTION '商品多语言名称/描述里残留了占位符：{...} 是 3.4.2.2 描述模板的机制，本表的正文必须是已填好的成品（语言 %，名称开头「%」）',
    NEW.locale, LEFT(COALESCE(NULLIF(NEW.name,''),NEW.description), 40) USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_spu_translation_placeholder BEFORE INSERT OR UPDATE ON spu_translation
  FOR EACH ROW EXECUTE FUNCTION guard_translation_no_placeholder();
CREATE TRIGGER guard_sku_translation_placeholder BEFORE INSERT OR UPDATE ON sku_translation
  FOR EACH ROW EXECUTE FUNCTION guard_translation_no_placeholder();

-- ---- 已处置的 AI 建议是终态
--
-- 采纳错了的补救办法是直接改译文内容，不是把建议改回 PENDING 再走一遍：
-- 同一条建议两次采纳会产出两条不同的译文历史，无从判断哪次算数。
CREATE FUNCTION guard_translation_suggestion_decided() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'PENDING' THEN
  RAISE EXCEPTION '这条 AI 翻译建议已经被处置过（%），处置结果是终态：采纳错了请直接改译文内容，不要把建议改回待处置再走一遍——否则同一条建议两次采纳会产出两条不同的译文历史，无从判断哪次算数',
    OLD.status USING ERRCODE='23514';
 END IF;
 IF NEW.status<>'PENDING' AND NEW.decided_by IS NULL THEN
  RAISE EXCEPTION '处置建议必须留处置人（决定 % 这条建议的人）', NEW.status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_translation_suggestion_decided BEFORE UPDATE ON translation_suggestion
  FOR EACH ROW EXECUTE FUNCTION guard_translation_suggestion_decided();

-- ---- 仍被商品译文用着的语言不可删（与 V21 的 refuse_content_template_delete 同一纪律）
--
-- 删掉一条语言需求会让「这个商品的德语译文」变成没有需求依据的孤儿数据，
-- 而完整度报表会把它算进「不在需求列表里的语言」——两边数字立刻对不上。
-- 要清掉一个语言请改用停用（停用后不再出现在新建商品的默认语言里，已建的译文照常可读）。
CREATE FUNCTION refuse_translation_locale_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE uses bigint;
BEGIN
 IF NOT OLD.enabled THEN RETURN OLD; END IF;
 SELECT count(*) INTO uses FROM (
   SELECT 1 FROM spu_translation WHERE tenant_id=OLD.tenant_id AND locale=OLD.locale
   UNION ALL
   SELECT 1 FROM sku_translation WHERE tenant_id=OLD.tenant_id AND locale=OLD.locale
 ) u;
 IF uses>0 THEN
  RAISE EXCEPTION '语言「%」仍被 % 条商品译文使用，不可删除：删掉之后这些译文就成了没有需求依据的孤儿，而完整度报表会把它们算成「不在需求列表里的语言」，两边数字立刻对不上。请改用停用',
    OLD.locale, uses USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_translation_locale_delete BEFORE DELETE ON translation_locale
  FOR EACH ROW EXECUTE FUNCTION refuse_translation_locale_delete();

-- ============================================================ 6. 索引
--
-- 语言需求列表与「这个租户需要哪几种语言」的查法。
CREATE INDEX translation_locale_list ON translation_locale(tenant_id, enabled, locale);
-- 按来源筛选（人工维护的 vs 从店铺站点同步来的）。
CREATE INDEX translation_locale_by_source ON translation_locale(tenant_id, source, enabled, locale);
-- 译文列表页的主查询形状：按主体 + 语言读。
CREATE INDEX spu_translation_by_spu ON spu_translation(tenant_id, spu_id, locale);
CREATE INDEX sku_translation_by_sku ON sku_translation(tenant_id, sku_id, locale);
-- 「还有多少待翻 / 待校对」——翻译工作台每天要看的那两个数。
CREATE INDEX spu_translation_by_status ON spu_translation(tenant_id, status, locale);
CREATE INDEX sku_translation_by_status ON sku_translation(tenant_id, status, locale);
-- **部分索引：只索引已校对的译文**。04 刊登填充只取 REVIEWED（只有人校对过的才发平台），
-- 而跑几个月后 PENDING（待翻）会远多于 REVIEWED。
CREATE INDEX spu_translation_publishable ON spu_translation(tenant_id, spu_id, locale)
  WHERE status='REVIEWED';
CREATE INDEX sku_translation_publishable ON sku_translation(tenant_id, sku_id, locale)
  WHERE status='REVIEWED';
-- 建议列表页：按状态倒序（页面上最常见的是「还没处置的」）。
CREATE INDEX translation_suggestion_by_status ON translation_suggestion(tenant_id, status, created_at DESC, id DESC);
-- 某个商品还有哪些没处置的建议（采纳页的入口形状）。
CREATE INDEX translation_suggestion_by_spu ON translation_suggestion(tenant_id, spu_id, locale, created_at DESC)
  WHERE status='PENDING';
CREATE INDEX translation_suggestion_by_sku ON translation_suggestion(tenant_id, sku_id, locale, created_at DESC)
  WHERE status='PENDING';
-- 18 交来一批建议后按 batch_ref 找回来（批量采纳的主查询形状）。
CREATE INDEX translation_suggestion_by_batch_ref ON translation_suggestion(tenant_id, batch_ref, status)
  WHERE batch_ref<>'';
-- 批次列表页与详情页。
CREATE INDEX translation_batch_list ON translation_batch(tenant_id, created_at DESC, id DESC);
CREATE INDEX translation_batch_by_action ON translation_batch(tenant_id, action, created_at DESC);
CREATE INDEX translation_batch_item_by_batch ON translation_batch_item(tenant_id, batch_id, outcome, id);
-- 「这批里哪些没成」：批次详情页按结果筛选。
CREATE INDEX translation_batch_item_by_target ON translation_batch_item(tenant_id, spu_id, sku_id, locale);
-- 重复提交同一批 (商品, 语言) 必须被挡住。用 coalesce 折叠 NULL：
-- 普通 UNIQUE 在这里挡不住「两行都指向同一个 SPU」，因为 PG 认为 NULL 互不相等。
CREATE UNIQUE INDEX translation_batch_item_dedupe
  ON translation_batch_item(tenant_id, batch_id,
    coalesce(spu_id,'00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(sku_id,'00000000-0000-0000-0000-000000000000'::uuid),
    locale);

-- ============================================================ 7. RLS 与授权
--
-- 六张表全部租户级数据（商品多语言内容与商品档案是同一份租户数据，
-- 不套 V3 的 owner_id/department_id 数据范围 —— 译文挂在商品上，
-- 跟着商品的可见范围走，不另开一套）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['translation_locale','spu_translation','sku_translation',
    'translation_suggestion','translation_batch','translation_batch_item'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  translation_locale, spu_translation, sku_translation,
  translation_suggestion, translation_batch, translation_batch_item
  TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  translation_locale, spu_translation, sku_translation,
  translation_suggestion, translation_batch, translation_batch_item
  TO starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **04 刊登填充**：取 spu_translation / sku_translation 里 status='REVIEWED' 的行
--     （按 spu_id/sku_id + locale）。**只有已校对的才可刊登** —— 「已翻」是机器译文，
--     拿去发平台等于没做校对这一步。本模块不感知 listing，也不写刊登状态。
--   * **18 AI 翻译**：POST /catalog/translations/suggestions 登记建议
--     （origin='AI' + batch_ref 归组），然后等 catalog 采纳或驳回回写。
--     本模块不调 AI——调它等于跨服务依赖调用顺序。
--   * **02 渠道**：启用站点 → 语言的推导。catalog 通过
--     `TranslationLocaleProjectionPort` 端口读取，未接入时接口返回 available=false
--     并写明归 02 渠道，而不是编一份语言列表。
--   * **3.4.2.2 描述库**：模板译文与商品译文是**两套 locale**（见文件头边界）。