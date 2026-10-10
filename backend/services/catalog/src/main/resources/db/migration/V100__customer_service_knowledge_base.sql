-- 11.4.2.2 客服知识库与话术：知识条目（常见问题标准答法 / 平台政策说明 / 退换货话术）、
-- 多语言版本、**平台规则版本挂钩**、真实引用与命中记录、命中率统计。
--
-- 蓝图 11.4.2.2 的原话：
--   「职责：维护客服知识库与回复模板：常见问题标准答法、平台政策说明、退换货话术、
--     多语言版本；记录使用效果。」
--   「输入：模板与知识条目；多语言版本；使用统计。」
--   「输出：话术库 + 客服一键引用；命中率统计。」
--   「关系：上游 = 3.4.2.2 文案资产；下游 = 15 客服消息发送。
--     **话术需与平台规则版本挂钩**，平台政策变化时能定位哪些话术需要更新。」
--
-- ============================================================ 与 3.4.2.2 描述与卖点库的边界
--
-- 3.4.2.2（V21）管**商品侧文案**：详情描述 / 使用说明 / 注意事项 / 卖点段落，
-- 消费者在详情页读到它。它的下游是 04 刊登与 15 客服，工作流是「批量填充 SKU」。
--
-- 本模块管**客服侧话术**：客服在会话窗口里一键引用给买家看的内容。
-- 判据是**谁在什么时候读**，不是文本长短：
--   * 3.4.2.2 的读者是消费者（详情页），本模块的读者是客服（会话窗口）；
--   * 3.4.2.2 的输入是 SKU + 变量占位填充，本模块的输入是**买家的问题**；
--   * 3.4.2.2 的效果指标是「多少段落缺变量」，本模块的是「被引用了多少次、答没答上」。
--
-- 两者都要多语言，但**翻译挂在不同的宿主上**（那边挂模板，这边挂知识条目），
-- 也不共用一张表：把客服话术塞进 content_template 会让它继承「批量填充 SKU」那套
-- 与客服毫无关系的机制，并让「这条话术被引用过几次」这个问题跨不过 SKU 边界去回答。
--
-- ============================================================ 「话术与平台规则版本挂钩」怎么落
--
-- 蓝图点名的硬要求：**平台政策变化时能定位哪些话术需要更新。**
-- 「定位」要能在页面上查到，所以下面三件事钉在数据里而不是拼在服务层的 if 里：
--
--   1. kb_rule_version —— 平台政策条目的**版本**（一个 policy_key 下有多版，
--      每版带 effective 区间与 status）。政策变化 = 这一张表多了一版。
--   2. kb_entry_rule —— 话术挂在**哪一版**规则上。一条话术可挂多条（亚马逊退换货 +
--      平台 A-to-Z 政策各一条），一条规则可被多条话术挂。
--   3. kb_review_task —— **待复核任务**。当一版规则被标记为 SUPERSEDED（被新版接替），
--      挂在它上面的所有话术自动各生成一条待复核任务，并逐条记下是哪一版接替了它。
--
-- 为什么要有第 3 张表而不是在查询时现算「这版已被接替，所以话术该复核」：
-- 现算的话，「已被接替」这个事实会随着**别的**规则版本被编辑而变化，
-- 客服手上那条待办会在他没做任何事的情况下自己变多或变少。
-- 落成任务行之后，「因为 2026-03 的退换货政策改版，这 7 条话术需要复核」
-- 是一个**带时间戳的历史事实**，能派工、能认领、能查谁复核过。
--
-- 复核状态三态（review_status）：
--   PENDING   待复核（规则改版自动生成）
--   REVIEWING 已认领（有人正在改）
--   DONE      已复核（话术已按新规则确认或改写）
-- 不设「已忽略」：规则改版后话术确实要过一遍，没有人认领的任务会一直挂在列表第一页。
--
-- ============================================================ 「记录使用效果」怎么落
--
-- 蓝图输出是「话术库 + 客服一键引用；**命中率统计**」，职责是「**记录使用效果**」。
-- 「效果」不能靠前端内存计数，所以：
--
--   4. kb_usage —— **引用事件**行：谁（agent）、在哪个会话（conversation_ref）、
--      引用了哪条话术的哪一版（kb_entry + kb_entry_translation，可空）、
--      结果如何（outcome）。
--
-- 五种 outcome：
--   RESOLVED  一次解决（买家接受，问题结案）
--   PARTIAL   部分解决（给了话术但买家仍有疑问）
--   UNRESOLVED 没解决（引用了但仍要转人工或升级）
--   ABANDONED 放弃引用（客服打开了话术但最终没发出去 —— 检索噪声的信号）
--   REJECTED  话术不可用（照话术答复被买家或平台驳回 —— **最该被发现的一种**）
--
-- **「命中率」的口径在这里钉死**（服务层只统计不重定义）：
--   命中率 = 有引用事件的启用话术条数 / 启用话术总条数。
-- 分子**不是**「被引用且 RESOLVED 的条数」——那样的话一条被引用 1 次的烂话术
-- 和一条被引用 800 次的好话术都得 1，而「多少条话术根本没人用」恰恰是要回答的问题。
-- 分母只算启用的：停用的话术没被引用是预期的，不该拉低命中率。
--
-- REJECTED 不计入命中（它是失败信号），ABANDONED 同样不算。
-- 这条口径在服务层 KnowledgeBaseStats 里算，页面上的数字与它同源。
--
-- ============================================================ 1. 平台规则版本（挂钩的锚点）
--
-- 一行 = 「某个平台某条政策在某个版本」。
-- policy_key 是稳定的政策身份（如 AMAZON_RETURN_POLICY），version_label 是版本号
-- （如 2026-03）。两者组合租户内唯一：同一政策不能有两个同名版本。
--
-- status 三态与 V23/V25 的版本语义对齐：
--   DRAFT      草稿（还没接替现行版本）
--   ACTIVE     当前生效
--   SUPERSEDED 已被更新版本接替（**改版的事实就落在这个状态上**）
--
-- effective_from 必填：没有生效起点的政策版本无法回答「这条话术当时依据的是哪版」。
-- effective_to 可空（= +∞，当前生效的那一版）。
-- 区间是左闭右开 [from, to)：与 V23/V25 同一口径，跨模块一致。
CREATE TABLE kb_rule_version (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 平台代码。与 3.4.x 的类目树命名空间（AMAZON / SHOPIFY / TIKTOK / EBAY / ALI1688）同词表。
  platform varchar(16) NOT NULL CHECK(btrim(platform)<>''),
  -- 政策条目的稳定身份（如 AMAZON_RETURN_POLICY）。**改版不改这条**，改的是 version_label。
  policy_key varchar(48) NOT NULL CHECK(btrim(policy_key)<>''),
  -- 版本标签（如 2026-03）。人读的不是 uuid，是它。
  version_label varchar(32) NOT NULL CHECK(btrim(version_label)<>''),
  title varchar(120) NOT NULL CHECK(btrim(title)<>''),
  -- 这一版改了什么。改版时**必填**：没有变更摘要的话，接替之后没人知道该复核什么。
  change_summary text NOT NULL DEFAULT '',
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','SUPERSEDED')),
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  -- 同一政策的同一版本号只能有一条。
  UNIQUE(tenant_id,policy_key,version_label),
  -- 终点严格大于起点（与 V23/V25 同一口径；草稿允许两端都空不了头，from 必填）。
  CHECK(effective_to IS NULL OR effective_to>effective_from)
);

-- ============================================================ 2. 知识条目（话术库主体）
--
-- 一行 = 一条可被客服一键引用的知识 / 话术。
--
-- entry_type 四类（对应蓝图点名的三种 + 一个兜底）：
--   FAQ         常见问题标准答法（「怎么退？」→ 标准答法）
--   POLICY      平台政策说明（「亚马逊 30 天退货政策怎么规定的」）
--   RETURN_EX   退换货话术（换货 / 仅退款 / 部分退款的标准回复）
--   OTHER       其它（尺码对照、物流沟通模板等）
--
-- **不与 3.4.2.2 的 content_template 合并**：见文件头边界说明（读者与工作流都不同）。
--
-- trigger_keywords 是**检索词**（「退」「退货 policy」「换货」）的逗号分隔短词，
-- 每个 ≤ 40 字符、不含逗号。用短词而不是长句：客服检索时打的是「退」「换」，不是整句。
-- 规范化（去首尾空白、折叠连续空白、限长）由服务层做，库里存规范形。
-- **不建索引**：命中靠 trigram（见索引小节），逗号分隔串本身没法用 btree 检索。
--
-- 变量占位沿用 V21 的 {name} 风格（15 客服按买家上下文填，如「{buyer_name}」「{order_no}」）。
-- 与 V21 同样的纪律：**正文里的占位符集合在多语言版本间必须完全一致**，
-- 由服务层校验并有触发器兜底（见第 5 节）。
CREATE TABLE kb_entry (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  title varchar(120) NOT NULL CHECK(btrim(title)<>''),
  entry_type text NOT NULL CHECK(entry_type IN ('FAQ','POLICY','RETURN_EX','OTHER')),
  -- 标准答法正文（源语言）。可含 {name} 占位符，上限 8000 字符。
  -- 比 V21 的 20000 短一个数量级：客服会话窗口里的话术是一屏，不是说明书。
  body text NOT NULL CHECK(btrim(body)<>''),
  -- 源语言（BCP-47 简写，如 zh-CN / en-US）。译文挂在它下面。
  source_locale varchar(16) NOT NULL CHECK(btrim(source_locale)<>''),
  -- 检索词（逗号分隔）。空串合法：有些内部话术不面向检索。
  trigger_keywords text NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 编码租户内唯一：客服与运营用 code 互相引用（「先看看 REFUND_POLICY 这条」）。
  UNIQUE(tenant_id,code)
);

-- ------------------------------------------------------------ 多语言版本
--
-- 蓝图硬要求的落地：「多语言版本」必须**真实成行**，不是 json 挂一列。
-- 一行 = 一个条目在一种语言下的一份译文。
--
-- origin 三态（与 V21 的 content_template_translation 同词表）：
--   MANUAL 人工撰写 / AI 18 的 AI 翻译预填 / IMPORTED 从既有资产导入
-- status 两态：DRAFT 草稿（AI 预填未复核）/ REVIEWED 已复核。
--
-- 占位符纪律与 V21 完全一致（译文的 {name} 集合必须等于源语言的），
-- 理由也一样：漏一个占位符 → 答复里出现裸 `{buyer_name}`；多一个 → 每条答复都缺变量。
CREATE TABLE kb_entry_translation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, entry_id uuid NOT NULL,
  locale varchar(16) NOT NULL CHECK(btrim(locale)<>''),
  title varchar(120) NOT NULL CHECK(btrim(title)<>''),
  body text NOT NULL CHECK(btrim(body)<>''),
  origin text NOT NULL DEFAULT 'MANUAL' CHECK(origin IN ('MANUAL','AI','IMPORTED')),
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','REVIEWED')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id,id),
  -- 同一条目同一语言只能一条译文：两条时「这条话术的英文是哪个」无法回答，
  -- 而 15 客服是按 (entry, locale) 取一条直接发出去的。
  UNIQUE(tenant_id,entry_id,locale),
  FOREIGN KEY(tenant_id,entry_id) REFERENCES kb_entry(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 3. 话术 ↔ 平台规则版本挂钩
--
-- 蓝图硬要求「话术需与平台规则版本挂钩」的落地表。
--
-- 一行 = 一条话术挂在某一版平台规则上。UNIQUE(tenant_id,entry_id,rule_version_id)
-- 挡住「同一条话术挂同一版规则两次」（那会让「这条话术依据了哪几版规则」多一个假的）。
--
-- **刻意不建「一条话术只能挂一版规则」的约束**：一条退换货话术通常同时依据
-- 平台的退货政策与 A-to-Z 政策两条规则。限死一版会逼运营去抄一份几乎相同的话术，
-- 而副本正是「哪条话术需要更新」这个问题答不对的根源。
CREATE TABLE kb_entry_rule (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  entry_id uuid NOT NULL, rule_version_id uuid NOT NULL,
  -- 关联备注：这条话术具体怎么用这条规则（「只适用于自发货订单」）。
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,entry_id,rule_version_id),
  -- 规则版本用 RESTRICT：规则版本是政策存档，删掉它之后「这条话术当时依据哪版政策」
  -- 就再也答不上来了。要换版请走 supersede（状态改成 SUPERSEDED），行本身留着。
  FOREIGN KEY(tenant_id,entry_id) REFERENCES kb_entry(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,rule_version_id) REFERENCES kb_rule_version(tenant_id,id)
);

-- ------------------------------------------------------------ 待复核任务
--
-- 「平台政策变化时能定位哪些话术需要更新」的落地表（文件头第 3 条）。
--
-- 一行 = 一条话术因某一版规则被接替而需要复核。
-- 触发点：kb_rule_version 的 status 变成 SUPERSEDED 时，服务层按 kb_entry_rule
-- 逐条生成（DB 层由 guard_rule_supersede 生成器触发器兜底，见第 5 节）。
--
-- superseding_rule_id 是**接替它的那一版**：没有这一列的话，客服看到「待复核」
-- 不知道该去比对哪一版，只能一条条翻规则历史。
CREATE TABLE kb_review_task (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  entry_id uuid NOT NULL, rule_version_id uuid NOT NULL, superseding_rule_id uuid NOT NULL,
  review_status text NOT NULL DEFAULT 'PENDING' CHECK(review_status IN ('PENDING','REVIEWING','DONE')),
  -- 复核结论 / 认领备注（人写的，不是异常类名）。
  note text NOT NULL DEFAULT '',
  -- 认领人与认领时刻（REVIEWING 起才有值）。
  assigned_to uuid, assigned_at timestamptz,
  -- 复核完成时刻（DONE 起才有值）。
  reviewed_by uuid, reviewed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一话术 × 同一被接替版本只生成一条任务：重复触发 supersede 不会让任务翻倍。
  UNIQUE(tenant_id,entry_id,rule_version_id),
  FOREIGN KEY(tenant_id,entry_id) REFERENCES kb_entry(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,rule_version_id) REFERENCES kb_rule_version(tenant_id,id),
  FOREIGN KEY(tenant_id,superseding_rule_id) REFERENCES kb_rule_version(tenant_id,id),
  -- 复核痕迹必须成套：DONE 必须有复核人与时刻，否则「已复核」这个状态无法追溯是谁做的。
  CHECK(review_status<>'DONE' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
  -- 认领痕迹必须成套。
  CHECK(review_status='PENDING' OR assigned_to IS NOT NULL)
);

-- ============================================================ 4. 引用与命中记录
--
-- 蓝图输出「话术库 + 客服一键引用；命中率统计」与职责「记录使用效果」的落地表。
--
-- 一行 = 一次引用事件（客服把某条话术的某个语言版本用在了某个会话里）。
--
-- **不建「话术被引用次数」的累计列**：累计列会与 kb_usage 分叉，
-- 而命中率统计的全部可信度来自「明细行是唯一真相」。统计一律现算（有索引，不慢）。
--
-- entry_translation_id 可空：客服直接用了源语言正文（没走译文）时，
-- 「引用的到底是哪一版」仍然可查（null = 源语言），而不是记一个假的译文 id。
CREATE TABLE kb_usage (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  entry_id uuid NOT NULL,
  entry_translation_id uuid,
  -- 谁引用的（已认证成员的 user_id，来自成员关系，不信任客户端头）。
  agent_id uuid NOT NULL,
  -- 会话/工单外部引用（如平台会话 id）。用于回溯「这条话术用在哪个买家身上」。
  conversation_ref varchar(64) NOT NULL DEFAULT '',
  outcome text NOT NULL CHECK(outcome IN ('RESOLVED','PARTIAL','UNRESOLVED','ABANDONED','REJECTED')),
  -- 结果备注（人写的）。
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 规则版本可以 RESTRICT（同上：政策存档不可删），话术用 CASCADE
  -- （话术删了，它的历史引用记录跟着走 —— 保留悬空引用行只会让统计分母失真）。
  FOREIGN KEY(tenant_id,entry_id) REFERENCES kb_entry(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,entry_translation_id) REFERENCES kb_entry_translation(tenant_id,id)
);

-- ============================================================ 5. 触发器
--
-- ---- 译文的占位符集合必须与源语言一致（V21 同一纪律，逐条兜底）
--
-- CHECK 在 PG 里可被 NOT VALID / 迁移脚本绕过，触发器不行。
-- 宁可让保存译文失败，也不能让 `{buyer_name}` 原样发到买家会话里。
CREATE FUNCTION guard_kb_translation_body() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE src text; missing text; extra text;
BEGIN
 SELECT e.body INTO src FROM kb_entry e WHERE e.id=NEW.entry_id AND e.tenant_id=NEW.tenant_id;
 -- 占位符集合：**与 V21 / KnowledgeBaseRules.placeholdersOf 同一宽松口径** ——
 -- 一对花括号之间的内容去首尾空白后非空即是一个占位符（不限定 ASCII 标识符）。
 -- 这里必须与服务层判定一致，否则服务层放行、触发器拦下（或反过来），
 -- 「同一句话在 3.4.2.2 与 11.4.2.2 上算出不同的变量集合」就会发生。
 -- 写法说明（两处都是踩过的坑，不要"简化"回去）：
 --   a) PG 的 string_agg 里把 DISTINCT 与 ORDER BY 混用时对表达式要求很严，
 --      排序表达式与聚合的不一致就会报 "function string_agg(text) does not exist"。
 --      所以改成**先在子查询里去重、再聚合成有序串**。
 --   b) regexp_matches 的结果集别名**不能取 m / s 这类单字母**：在 plpgsql 函数体里
 --      `AS m` 之后 `m[1]` 不会按预期取到捕获组，会算出错误的占位符集合
 --      （表现为译文漏抄时给出乱七八糟的名字）。这里用 ph_new / ph_src 这种描述性别名。
 SELECT coalesce(string_agg(p.name, ',' ORDER BY p.name),'') INTO extra
   FROM (SELECT DISTINCT btrim(ph_new[1]) AS name
           FROM regexp_matches(NEW.body, '\{([^{}]*)\}', 'g') AS ph_new
          WHERE btrim(ph_new[1])<>''
            AND NOT EXISTS (
              SELECT 1 FROM regexp_matches(src, '\{([^{}]*)\}', 'g') AS ph_src
               WHERE btrim(ph_src[1])=btrim(ph_new[1]))) p;
 SELECT coalesce(string_agg(p.name, ',' ORDER BY p.name),'') INTO missing
   FROM (SELECT DISTINCT btrim(ph_src[1]) AS name
           FROM regexp_matches(src, '\{([^{}]*)\}', 'g') AS ph_src
          WHERE btrim(ph_src[1])<>''
            AND NOT EXISTS (
              SELECT 1 FROM regexp_matches(NEW.body, '\{([^{}]*)\}', 'g') AS ph_new
               WHERE btrim(ph_src[1])=btrim(ph_new[1]))) p;
 IF extra<>'' OR missing<>'' THEN
  RAISE EXCEPTION '译文「%」(%s)的占位符与源语言正文不一致：源语言多出 {%s}，译文多出 {%s} —— 11.4.2.2 要求多语言版本的占位符集合完全一致，否则答复里会出现裸占位符或永远缺变量',
    NEW.locale, LEFT(NEW.title, 30), missing, extra USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_kb_translation_body BEFORE INSERT OR UPDATE ON kb_entry_translation
  FOR EACH ROW EXECUTE FUNCTION guard_kb_translation_body();

-- ---- 源语言正文改了占位符时，已有译文会被判为不一致（挡住静默失配）
--
-- 只改标题或措辞不影响占位符集合，这里拦的是「增删了占位符」。
-- 触发条件是集合真的变了，措辞改动不触发（否则每次润色都要重译，运营会直接把触发器绕过）。
CREATE FUNCTION guard_kb_entry_body_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bad record;
BEGIN
 -- 比较用 ARRAY(...) 包一层再比：regexp_matches 是**集合返回函数**，直接写在 IF 的
 -- 表达式里会被逐行求值，正文里有两个以上占位符时 PostgreSQL 会报
 -- "query returned more than one row"。排序后再比，集合相同就判为没变（与出现顺序无关）。
 IF ARRAY(SELECT ph_old[1] FROM regexp_matches(OLD.body,'\{([^{}]*)\}','g') AS ph_old ORDER BY ph_old[1])
     IS DISTINCT FROM
    ARRAY(SELECT ph_new[1] FROM regexp_matches(NEW.body,'\{([^{}]*)\}','g') AS ph_new ORDER BY ph_new[1]) THEN
  SELECT NEW.id AS entry_id, t.locale, t.id AS translation_id INTO bad    FROM kb_entry_translation t
   WHERE t.tenant_id=NEW.tenant_id AND t.entry_id=NEW.id LIMIT 1;
  IF bad.entry_id IS NOT NULL THEN
   RAISE EXCEPTION '知识条目「%」的源语言正文改了占位符集合，但已有 % 条译文：占位符集合变了就意味着所有译文都要同步改。请先改完译文或删掉旧译文，再改源语言正文',
     NEW.code,
     (SELECT count(*) FROM kb_entry_translation t
       WHERE t.tenant_id=NEW.tenant_id AND t.entry_id=NEW.id) USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_kb_entry_body_change BEFORE UPDATE ON kb_entry
  FOR EACH ROW EXECUTE FUNCTION guard_kb_entry_body_change();

-- ---- 规则版本被接替时，自动给挂在它上面的话术生成待复核任务
--
-- 「平台政策变化时能定位哪些话术需要更新」不能只靠服务层记得调用：
-- 有人直接改库、或某个写端点漏了调，这条硬要求就静默失效了。
-- 所以生成逻辑钉在触发器里：status 首次变成 SUPERSEDED 时，对每个挂在该版规则上的
-- 话术插一条 PENDING 任务。UNIQUE(tenant_id,entry_id,rule_version_id) 保证重复触发不翻倍。
CREATE FUNCTION spawn_kb_review_tasks_on_supersede() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sup uuid;
BEGIN
 IF NEW.status='SUPERSEDED' AND OLD.status<>'SUPERSEDED' THEN
  -- 接替它的那一版：**同 policy_key、生效起点不早于本版**的那一版。
  -- 方向只能是往后（新版从更晚的时点接替旧版），所以判据是 effective_from>= 本版起点。
  -- 优先取已生效的那一版；还没有人发布新版时（先接替、后发布）退回取最早的那一版，
  -- 保证「接替了，但新版还是草稿」这种真实顺序下待办照样生成。
  SELECT r.id INTO sup FROM kb_rule_version r
   WHERE r.tenant_id=NEW.tenant_id AND r.policy_key=NEW.policy_key
     AND r.id<>NEW.id AND r.effective_from>=NEW.effective_from
   ORDER BY (r.status='ACTIVE') DESC, r.effective_from ASC, r.created_at ASC LIMIT 1;
  IF sup IS NOT NULL THEN
   INSERT INTO kb_review_task(id,tenant_id,entry_id,rule_version_id,superseding_rule_id,review_status)
   SELECT gen_random_uuid(), NEW.tenant_id, er.entry_id, er.rule_version_id, sup, 'PENDING'
     FROM kb_entry_rule er
    WHERE er.tenant_id=NEW.tenant_id AND er.rule_version_id=NEW.id
    ON CONFLICT (tenant_id,entry_id,rule_version_id) DO NOTHING;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER spawn_kb_review_tasks_on_supersede BEFORE UPDATE OF status ON kb_rule_version
  FOR EACH ROW EXECUTE FUNCTION spawn_kb_review_tasks_on_supersede();

-- ---- 一个政策同时只能有一版 ACTIVE
--
-- 「当前生效」必须唯一：两版同时 ACTIVE 时「这条话术依据的是哪版政策」无法回答，
-- 而这正是本模块挂钩机制要回答的问题。
CREATE FUNCTION guard_kb_rule_single_active() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='ACTIVE' THEN
  IF EXISTS (SELECT 1 FROM kb_rule_version r
              WHERE r.tenant_id=NEW.tenant_id AND r.policy_key=NEW.policy_key
                AND r.id<>NEW.id AND r.status='ACTIVE') THEN
   RAISE EXCEPTION '政策「%」已有一版处于生效中，不能同时激活第二版：一个政策同时只能有一版生效，否则「这条话术依据的是哪版政策」无法回答。请先把原生效版标记为已被接替',
     NEW.policy_key USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_kb_rule_single_active BEFORE INSERT OR UPDATE ON kb_rule_version
  FOR EACH ROW EXECUTE FUNCTION guard_kb_rule_single_active();

-- ---- 引用记录不可改删（append-only）
--
-- 与 warehouse V1 的库存流水同一纪律：命中率的全部可信度来自明细行是唯一真相。
-- 改一行 outcome 就等于把统计往自己想要的数字上掰。
CREATE FUNCTION refuse_kb_usage_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '引用记录不可修改或删除：命中率的全部可信度来自明细行是唯一真相，改一行就等于把统计往想要的数字上掰。如需纠正请再记一条并把原条标注为误记'
   USING ERRCODE='42501';
END $$;
CREATE TRIGGER refuse_kb_usage_update BEFORE UPDATE ON kb_usage
  FOR EACH ROW EXECUTE FUNCTION refuse_kb_usage_mutation();
CREATE TRIGGER refuse_kb_usage_delete BEFORE DELETE ON kb_usage
  FOR EACH ROW EXECUTE FUNCTION refuse_kb_usage_mutation();

-- ---- 复核任务做完也不可删（留痕）
--
-- 「这条话术在政策改版那天被谁复核过」是要能回答的历史问题。
CREATE FUNCTION refuse_kb_review_task_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '复核任务不可删除：政策改版后哪条话术被谁复核过是要能回答的历史问题。话术被删除时任务会随话术级联删除，那才是它唯一的合法去路'
   USING ERRCODE='42501';
END $$;
CREATE TRIGGER refuse_kb_review_task_delete BEFORE DELETE ON kb_review_task
  FOR EACH ROW EXECUTE FUNCTION refuse_kb_review_task_delete();

-- ============================================================ 6. 索引
--
-- 规则版本：按政策取全部版本（改版历史页的主查询形状）。
CREATE INDEX kb_rule_version_by_policy ON kb_rule_version(tenant_id, policy_key, effective_from DESC, id DESC);
-- **部分索引：一个政策当前生效的那一版**（话术详情要显示「依据哪版政策」）。
CREATE INDEX kb_rule_version_active ON kb_rule_version(tenant_id, policy_key, effective_from DESC)
  WHERE status='ACTIVE';
-- 规则列表页：按平台 + 状态筛。
CREATE INDEX kb_rule_version_list ON kb_rule_version(tenant_id, platform, status, effective_from DESC, id DESC);

-- 话术字典列表与读取。
CREATE INDEX kb_entry_list ON kb_entry(tenant_id, entry_type, enabled, code);
CREATE INDEX kb_entry_by_updated ON kb_entry(tenant_id, updated_at DESC, id DESC);
-- 译文按条目取（引用某条话术前先查齐有没有译文）。
CREATE INDEX kb_entry_translation_by_entry ON kb_entry_translation(tenant_id, entry_id, locale);
CREATE INDEX kb_entry_translation_by_locale ON kb_entry_translation(tenant_id, locale, status);

-- 挂钩：话术 → 它依据的规则（话术详情页）；规则 → 挂在它上面的术（改版影响面）。
CREATE INDEX kb_entry_rule_by_entry ON kb_entry_rule(tenant_id, entry_id);
CREATE INDEX kb_entry_rule_by_rule ON kb_entry_rule(tenant_id, rule_version_id);

-- 待复核列表：待办页的主查询形状（按状态筛 + 最新的排前面）。
CREATE INDEX kb_review_task_by_status ON kb_review_task(tenant_id, review_status, created_at DESC, id DESC);
-- 单条话术的复核历史。
CREATE INDEX kb_review_task_by_entry ON kb_review_task(tenant_id, entry_id, created_at DESC);
-- **部分索引：只索引待办**。DONE 会越积越多，而客服每天只看 PENDING/REVIEWING。
CREATE INDEX kb_review_task_pending ON kb_review_task(tenant_id, created_at DESC, id DESC)
  WHERE review_status<>'DONE';

-- 引用记录：话术的使用历史（详情页）与会话回溯。
CREATE INDEX kb_usage_by_entry ON kb_usage(tenant_id, entry_id, created_at DESC, id DESC);
CREATE INDEX kb_usage_by_translation ON kb_usage(tenant_id, entry_translation_id, created_at DESC)
  WHERE entry_translation_id IS NOT NULL;
CREATE INDEX kb_usage_by_conversation ON kb_usage(tenant_id, conversation_ref, created_at DESC)
  WHERE conversation_ref<>'';
-- **命中率统计的形状**（详见 KnowledgeBaseStats）：按启用状态取有引用事件的条数。
-- 部分索引：停用的话术不进分母。
CREATE INDEX kb_usage_hit_rate ON kb_usage(tenant_id, entry_id)
  WHERE outcome IN ('RESOLVED','PARTIAL');
-- 拒绝与放弃是最该被发现的两类结果，单独一个部分索引供「哪些话术在被驳回」查询。
CREATE INDEX kb_usage_by_problem ON kb_usage(tenant_id, outcome, created_at DESC, id DESC)
  WHERE outcome IN ('REJECTED','ABANDONED');

-- 客服检索：按触发词查话术。trigger_keywords 是逗号分隔短词，
-- trigram 能命中「退货政策」里的「退货」这种子串匹配。
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX kb_entry_keyword_trgm ON kb_entry
  USING gin (trigger_keywords gin_trgm_ops) WHERE enabled;

-- ============================================================ 7. RLS 与授权
--
-- 五张表全部租户级数据（话术库是租户共享资产，与 title_* / content_* 同一形状）：
-- 同一客服团队要在彼此的话术上复用，不套 V3 的 owner_id/department_id 数据范围。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['kb_rule_version','kb_entry','kb_entry_translation',
   'kb_entry_rule','kb_review_task','kb_usage'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  kb_rule_version, kb_entry, kb_entry_translation, kb_entry_rule, kb_review_task
  TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  kb_rule_version, kb_entry, kb_entry_translation, kb_entry_rule, kb_review_task
  TO starcon_app;
-- kb_usage 只给 INSERT/SELECT：append-only 触发器之外再加一道 REVOKE，
-- 两条纵深缺一不可（触发器能被有 superuser 的连接绕，REVOKE 不能）。
GRANT SELECT,INSERT ON kb_usage TO catalog_app;
GRANT SELECT,INSERT ON kb_usage TO starcon_app;
REVOKE UPDATE,DELETE ON kb_usage FROM catalog_app, starcon_app;

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **3.4.2.2 文案资产（V21）**：那边是商品侧文案（详情页给消费者看），
--     这边是客服侧话术（会话窗口给客服引用）。两者都要多语言，但翻译挂在不同宿主上，
--     不共用表也不共用路由 —— 见文件头边界说明。
--   * **15 客服消息发送**：按 (kb_entry, locale) 取 kb_entry + kb_entry_translation
--     组装答复，并用 POST /catalog/kb/entries/{id}/usages 回写引用事件。
--     本模块不感知会话、不发送消息。
--   * **11.4.2.1 工单管理**（未开发）：工单转派时可以带上「相关话术」，
--     出口是 GET /catalog/kb/entries/{id}/usages 与命中率统计，不读工单表。
--   * **2 渠道 / 平台政策**：平台政策变化由 02 同步或运营登记到 kb_rule_version；
--     本模块不拉平台政策接口，只维护版本与接替关系。
