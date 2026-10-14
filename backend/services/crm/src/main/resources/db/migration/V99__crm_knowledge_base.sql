-- 15.4.2.1 客服知识库：常见问题标准答法、平台政策说明、产品使用指导、退换货规则，
-- **按店铺 / 类目 / 语言组织**，支持版本与生效日期，并记录每一次引用。
--
-- 蓝图 15.4.2.1 的原话：
--   「职责：维护客服知识库：常见问题标准答法、平台政策说明、产品使用指导、退换货规则，
--     按店铺/类目/语言组织，支持版本与生效日期。」
--   「输入：知识条目编辑；版本管理。」
--   「输出：知识库内容 + 引用记录。」
--   「关系：上游 = 3.4.2.2 文案资产；下游 = 15.1.1.2 发送、15.5.1 自动回复。
--     **知识条目必须标注适用平台与生效时间**，平台政策变更时才能快速定位过期内容。」
--
-- ============================================================ 零、与 11.4.2.2「知识库与话术」的边界
--
-- 11.4.2.2（catalog V100，**已交付，不在本车道改动范围**）建了 kb_rule_version / kb_entry /
-- kb_entry_translation / kb_entry_rule / kb_review_task / kb_usage 六张表，端点 /catalog/kb，
-- 页面 /aftersales/knowledge。
--
-- **本文件与那六张表没有一张重名、没有一个字段复用、没有一个端点共用**，理由不是「避免冲突」
-- 而是两者回答的根本不是同一个问题：
--
--   * **11.4.2.2 的组织维度是「平台规则版本」**：kb_rule_version 记的是平台政策条目的版本，
--     平台政策改版 → 挂在这版上的话术自动生成待复核任务（kb_review_task）。
--     它的主语是「平台政策这一版变了没有」。
--   * **本模块的组织维度是「店铺 / 类目 /语言」**：一条知识回答的是
--     「**这家店、这个类目、这个语言**下，客服此刻该给买家什么答法」。
--     它的主语是「这个格子里此刻该答哪一条」。
--
-- 两者都叫「知识库」是因为蓝图在 15.4.2 与 11.4.2.2 各写了一次同一类需求，但归属不同：
-- 11 售后（11.4.2.2）是**工单侧**的话术，读者是处理客诉的客服；
-- 15 客户与客服（15.4.2.1）是**全店铺**的客服知识，读者是所有买家消息的应答者。
-- 把它们合并成一张表，「同一家店同一类目同一种语言，售后口径与客服口径分别是什么」这个问题
-- 就无法回答 —— 合并只能加一个 source 列把两套口径混在一张表里，而它们连生效区间都不同步。
--
-- ⚠️ 因此本车道**刻意不复用、不扩展、不改名** kb_* 那六张表，也不把端点塞进 /catalog/kb
-- 命名空间：那是另一个 bounded context 已经通过集成测试的既有契约。
--
-- ============================================================ 一、三张表：档案 / 版本 / 引用
--
--   crm_knowledge_entry     —— 一条知识的**稳定身份**（它是哪个格子里的一条什么问题），
--                              以及蓝图点名要标的**适用平台**。
--   crm_knowledge_version   —— 这条知识的**一个版本**（答法正文 + 生效区间 + 发布留痕）。
--   crm_knowledge_reference —— **一次引用**（谁、在什么时候、引用了哪一版）。
--
-- 为什么「档案」与「版本」必须分两张表（与 catalog V23 套装 / V25 BOM 同一纪律，逐条成立）：
--   1. 「同一知识在同一时刻至多一个生效版本」是**同一档案内跨行**的约束。
--      两行合一就没有 entry_id 这个分组键，重叠判定退化成全表两两比较。
--   2. topic_key 是**格案内唯一**且建后不可改（它是客服与下游 15.1.1.2 引用时的稳定凭据），
--      version_no 只要求**同一档案内**唯一。合并后 UNIQUE(tenant_id,shop_id,category_id,locale,topic_key)
--      与 UNIQUE(tenant_id,entry_id,version_no) 互相打架。
--   3. 归档一条知识不该动它的历史版本 —— 下游 15.1.1.2 已发出的消息引用过某一版，
--      要能回溯「当时答复买家的是哪一版、当时的生效时间是什么」。
--
-- ============================================================ 二、按店铺 / 类目 / 语言组织（蓝图的三个维度）
--
-- 蓝图把三者并列写出，本文件**逐个钉成 NOT NULL 的真实组织维度**，而不是三个可选的下拉框：
-- 「这家店这个类目中文版当前有效的条目有哪些」必须是一个**能查出来的确定答案**。
--
--   * shop_id      —— 店铺。属 channel 服务（V1 channel_registry_connections_and_shops），
--                     本库只存逻辑 ID + shop_name 快照（trade V1 / warehouse V1 同一纪律）：
--                     **不建跨库外键**，跨服务引用一律走授权 API / event。
--   * category_id  —— 类目。属 catalog 服务（V14 category_tree），同样只存逻辑 ID + category_name 快照。
--   * locale       —— 语言。BCP-47 简写（如 zh-CN / en-US），CHECK 强制小写语言码 + 可选地区码。
--
-- ⚠️ 为什么三者都 NOT NULL 而不是「类目可空表示全店通用」：
-- 一旦允许 category_id 为空，「这家店这个类目该答哪一条」就有**两个**答案
-- （一条全店通用 + 一条类目专属），而生效区间是按档案分组判定的，两条档案之间没有共同的分组键，
-- 重叠检查根本覆盖不到它们。运营要写全店通用的政策说明，正确做法是**在每个相关类目各建一条**，
-- 让「这个格子归谁管」永远有唯一答案。想要「一处改、处处生效」请用版本接替，不要引入第二种归属。
--
-- UNIQUE(tenant_id,shop_id,category_id,locale,topic_key) 让「一个格子里同一条知识只能建一次」，
-- 否则「这个格子这条问题的标准答法是什么」会出现两个都自称标准的答案。
--
-- ============================================================ 三、生效区间的裁决规则 R1–R5（沿用 V23 / V25 / V95，不改判）
--
-- 本模块的时间语义与套装、BOM、目的地报关版本**完全一致**（都是「某一时刻该用哪一版」），
-- 所以 R1–R5 逐字沿用 V25 文件头第二节与 V95 第一节，不另立一套：
--
--   **R1（半开区间）**：[effective_from, effective_to)，to 为 NULL = +∞。
--     [10:00,12:00) 与 [12:00,14:00) 在 12:00 处**不**重叠。
--     本表用 CHECK(effective_to IS NULL OR effective_to > effective_from) 挡住空区间与倒挂区间，
--     用 tstzrange(from,to,'[)') 做区间比较（见 guard_crm_knowledge_version_interval）。
--
--   **R2（重叠一律拒绝，不做自动裁决）**：同一 entry 下任意两条**已发布**版本区间不得重叠。
--     冲突时报错点名与哪一版（version_no + 区间）撞了。
--     为什么客服知识库更不能「自动裁决」：15.5.1 自动回复会**不通知任何人**地
--     按「此刻生效的那一版」答复买家，用错一版就是平台违规或错误承诺，
--     而这类错误在下单前撤不回来。
--
--   **R3（只有已发布版本参与判定）**：草稿区间随便重叠，发布那一刻才检查。
--
--   **R4（当前生效版本的确定性查询）**：
--     published AND from <= t AND (to IS NULL OR t < to)，由 R2 保证至多命中一行。
--     服务层 crm_knowledge_entry / crm_knowledge_version 两条查询都走这个形状。
--
--   **R5（已发布版本只能向前关闭）**：只允许把 effective_to 从 NULL（或较晚值）
--     收紧到一个**仍在未来**的时刻，不允许改 effective_from。
--
-- ============================================================ 四、「平台政策变更时快速定位过期内容」（蓝图粗体句的落地）
--
-- 蓝图那句粗体不是一句风险提示，它要求**一条能查出来的查询**。本文件把它做成三件可执行的事实：
--
--   1. **适用平台是档案上的一等列**（entry.platform_code，NOT NULL + CHECK 平台码形状）。
--      没有它，「这条答法是按哪个平台的口径写的」根本无法回答。
--   2. **生效时间在版本上**（version.effective_from / effective_to，规则 R1）。
--      「这条答法是什么时候开始生效的」有了确定答案。
--   3. **待复核判定是一条真实 SQL**（GET /crm/knowledge/review-due）：
--      给定平台 P 与该平台政策改版的生效时刻 T，列出
--        「当前生效的那一版**在 T 之前**就写好了、且至今仍是开放区间（没有接替版本）」的条目。
--      换句话说：**内容停留在旧政策口径下、还没人改过**的条目，全部一次列出。
--      这不是人工翻页能得到的答案 —— 它依赖 (platform_code) × (effective_from, effective_to) 的
--      倒排形状，所以第五节为它建了专用部分索引。
--
-- 为什么不用「最后更新时间 > 改版时刻」这种更宽松的判据：知识条目的 updated_at 会因为
-- 改备注、改标题而变化，与答法正文是否按新政策重写毫无关系；用它会让改过备注的条目
-- 冒充「已按新政策复核过」，那正是「定位过期内容」要消灭的假绿。
--
-- ============================================================ 五、引用记录：真实落库，不是内存计数
--
-- 蓝图「输出：知识库内容 + **引用记录**」。引用记录必须是**可审计的事实行**：
-- 谁（referenced_by）、什么时候（referenced_at）、引用了哪一版（version_id）、
-- 由哪个消费方引用（consumer：15.1.1.2 人工发送 / 15.5.1.1 自动回复）。
--
-- 一行引用**不可改也不可删**（refuse_crm_knowledge_reference_mutation，BEFORE UPDATE OR DELETE）：
-- 引用记录的唯一用途就是回答「这条知识到底被用过没有、被谁用过」。
-- 允许删行等于允许把「没人用过」这个答案**做出来**，而「多少条知识根本没人用」
-- 恰恰是运营最该先看到的那个数。
--
-- 引用指向的是**具体版本**而不是档案：15.1.1.2 发出的一条消息引用的是某一版答法，
-- 之后档案出了 v2 也**不能**让那条历史消息看起来引用过 v2。
--
-- ============================================================ 六、跨服务纪律
--
-- shop_id（channel）与 category_id（catalog）在本库**只存逻辑 ID + 名称快照**，
-- 不建跨库外键、跨库视图或任何读上游库的 SQL（与 trade V1 第「不建跨服务外键」一节同一纪律）。
-- 名称快照的作用是**列表页不必回源就能显示**；它不构成引用完整性 ——
-- 真正的完整性校验属于各服务自己的域，本库不越界。
--
-- ============================================================ 七、发布后不可变的强制位置（纵深三层）
--
--   1. 服务层（KnowledgeBaseService）：发布后拒绝改标题 / 版本号 / 归属档案 / 生效起点 / 正文。
--   2. CHECK 约束：PUBLISHED 必须带 published_by + published_at。
--   3. 触发器（CHECK 在 PostgreSQL 里可被 NOT VALID 或迁移脚本绕过，触发器不行）：
--      guard_crm_knowledge_version_published（BEFORE UPDATE，逐列比较 + R5 向前关闭）
--      refuse_crm_knowledge_version_published_delete（BEFORE DELETE）
--      guard_crm_knowledge_version_interval（BEFORE INSERT/UPDATE，区间重叠）
--      refuse_crm_knowledge_reference_mutation（BEFORE UPDATE OR DELETE，引用只追加）
--
-- ============================================================================
-- 零之二、角色
-- ============================================================================
-- 本迁移是 **crm 数据库的第一个迁移**（本库随 15 客户与客服服务引导，端口 8086）。
-- 角色可能已由运维预置（deploy/docker/catalog-init/01-databases.sh），因此只在缺失时创建，
-- 保证迁移可重复执行（与 channel V1 / warehouse V1 / trade V1 同口径）。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- ============================================================================
-- 1. crm_knowledge_entry · 知识档案（一个格子里的一条问题）
-- ============================================================================
CREATE TABLE crm_knowledge_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 稳定业务键：**同一格子里唯一**，建后不可改（服务层强制）。
  -- 它是客服与下游 15.1.1.2 引用知识时的稳定凭据；两行并存时
  -- 「这个格子这条问题的标准答法是什么」会有两个都自称标准的答案。
  topic_key varchar(64) NOT NULL CHECK(btrim(topic_key)<>''),

  title varchar(200) NOT NULL CHECK(btrim(title)<>''),

  -- ===== 蓝图点名的三个组织维度，逐个 NOT NULL（见文件头第二节）=====
  -- 店铺（属 channel）：逻辑 ID + 名称快照，不建跨库外键。
  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  -- 类目（属 catalog）：逻辑 ID + 名称快照，不建跨库外键。
  category_id uuid NOT NULL,
  category_name varchar(200) NOT NULL DEFAULT '',
  -- 语言：BCP-47 简写。强制小写语言码 + 可选地区/脚本段（zh-CN / en-US / pt-BR）。
  locale varchar(16) NOT NULL CHECK(locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),

  -- ===== 蓝图粗体句的第一半：「必须标注适用平台」=====
  -- 平台码（AMAZON / SHOPIFY / TIKTOK_SHOP ...）与 channel 的平台码同一形状。
  -- 没有这一列，「这条答法是按哪个平台的口径写的」根本无法回答，
  -- 而 review-due 查询（文件头第四节）正是按它倒排。
  platform_code varchar(32) NOT NULL CHECK(platform_code ~ '^[A-Z][A-Z0-9_]*$'),

  -- 四类知识（蓝图的职责句逐字拆开）：常见问题标准答法 / 平台政策说明 / 产品使用指导 / 退换货规则。
  topic_type varchar(24) NOT NULL
    CHECK(topic_type IN ('FAQ','PLATFORM_POLICY','PRODUCT_USAGE','RETURN_POLICY')),

  -- 状态。ARCHIVED 只表示「不再新建版本」，历史版本照常可读
  -- （15.1.1.2 已发出的消息引用过某一版，要能回溯当时答的是什么）。
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')),

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  -- 主键是**全局** uuid，租户内再唯一一次：两个租户不会因为撞主键而互相阻塞写入
  -- （隔离要靠 RLS，不是靠撞键）。
  UNIQUE(tenant_id,id),
  -- 一个格子里同一条知识只能建一次（见 topic_key 注释）。
  UNIQUE(tenant_id,shop_id,category_id,locale,topic_key)
);

-- ============================================================================
-- 2. crm_knowledge_version · 知识版本（草稿 / 已发布 + 生效区间）
-- ============================================================================
CREATE TABLE crm_knowledge_version (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  entry_id uuid NOT NULL,

  -- 同一条知识内唯一。两行时「v2 是哪一个」无法回答，而区间判定会两边都算数。
  version_no integer NOT NULL CHECK(version_no > 0),

  -- 常见问题（客服看到的是它）与标准答法正文（客服复制给买家的是它）。
  -- 答法正文允许含 {buyer_name} 这类占位符；占位符一致性由 15.1.1.2 发送侧校验，
  -- 本模块只负责「正文是什么」这一件事，不替下游做模板校验。
  question text NOT NULL CHECK(btrim(question)<>''),
  answer text NOT NULL CHECK(btrim(answer)<>''),

  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),

  -- ===== 生效区间 [effective_from, effective_to)（规则 R1）=====
  -- to 为 NULL = +∞（这一版还没有接替者）。半开区间让
  -- [10:00,12:00) 与 [12:00,14:00) 在 12:00 处**不**重叠。
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  CHECK (effective_to IS NULL OR effective_to > effective_from),

  published_by uuid,
  published_at timestamptz,

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,entry_id,version_no),

  -- 发布留痕必须齐（服务层与触发器各拦一次；CHECK 可被迁移脚本绕过，触发器不行）。
  CHECK (status<>'PUBLISHED' OR (published_by IS NOT NULL AND published_at IS NOT NULL)),

  -- 版本挂在一条真实存在的知识档案上，否则「这是哪条知识的答法」无法回答。
  -- 用 CASCADE：档案删了，它下面的版本一起消失（草稿没有独立意义）。
  -- 已发布版本另有 refuse_crm_knowledge_version_published_delete 挡住。
  FOREIGN KEY(tenant_id,entry_id) REFERENCES crm_knowledge_entry(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================================
-- 3. crm_knowledge_reference · 引用记录（谁、何时、引用了哪一版）
-- ============================================================================
CREATE TABLE crm_knowledge_reference (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 引用的是**具体版本**而不是档案：15.1.1.2 发出的消息引用的是某一版答法，
  -- 档案后来出了 v2 也不能让那条历史消息看起来引用过 v2。
  version_id uuid NOT NULL,

  -- 谁引用了（已认证成员）。由服务端从 AccessService.Access 取，不信任客户端自称。
  referenced_by uuid NOT NULL,
  -- 什么时候引用的（系统时间，由服务端 now() 写入，不接受客户端传值）。
  referenced_at timestamptz NOT NULL DEFAULT now(),
  -- 消费方：MANUAL_REPLY（15.1.1.2 人工发送）/ AUTO_REPLY（15.5.1.1 自动回复）。
  -- 这两个下游模块当前**都未开发**（蓝图 15.4.2.1 的关系句），
  -- 但词表先按蓝图钉死，将来它们落地时不需要改这里的语义。
  consumer varchar(24) NOT NULL CHECK(consumer IN ('MANUAL_REPLY','AUTO_REPLY')),
  -- 引用发生的上下文（如消息 id / 工单 id），可空：15.1.1.2 的消息模块尚未开发，
  -- 届时由它把自己的单据号写进来。本模块**不猜测**那个 id 的形状。
  source_ref varchar(200),
  note text NOT NULL DEFAULT '',

  UNIQUE(tenant_id,id),
  -- 引用是只追加的事实：版本被删时（仅限草稿）引用一起消失，
  -- 已发布版本根本删不掉，所以引用不会指向空气。
  FOREIGN KEY(tenant_id,version_id) REFERENCES crm_knowledge_version(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================================
-- 4. 触发器（纵深防线，见文件头第七节）
-- ============================================================================

-- 已发布版本的逐列不可变：改归属档案 / 改版本号 / 改标题 / 改正文 / 改问题 /
-- 改生效起点 / 改备注 / 改发布留痕一律拒绝；effective_to 只允许按规则 R5 向前关闭。
-- 名字按字母序：immutable 在 interval 之前，所以同一次 UPDATE 里
-- 「能不能改」先于「会不会撞区间」判定 —— 报错先说最根本的那条。
CREATE FUNCTION guard_crm_knowledge_version_published() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'PUBLISHED' THEN RETURN NEW; END IF;

 -- 已发布 → 别的状态不允许：发布是单向的。
 -- 撤回发布会让这一版从 15.1.1.2 已发出消息与 15.5.1 自动回复的依据里消失，
 -- 而买家已经按当时的答法收到了答复，撤不回来。
 IF NEW.status<>'PUBLISHED' THEN
  RAISE EXCEPTION '已发布的知识版本不能撤回发布（版本 %）：买家已经按当时的答法收到了答复，撤回会让「当时答复买家的是哪一版、什么生效时间」无从查证。要停止使用请把知识归档，或新建一版接替它',
   OLD.version_no USING ERRCODE='23514';
 END IF;

 IF NEW.entry_id IS DISTINCT FROM OLD.entry_id THEN
  RAISE EXCEPTION '已发布的知识版本不能改归属知识（版本 %）：这个格子里的标准答法只能有一个归属，换档案等于让「这个格子的标准答法是什么」出现两个答案。要换请新建档案',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.version_no IS DISTINCT FROM OLD.version_no THEN
  RAISE EXCEPTION '已发布的知识版本不能改版本号（% → %）：版本号是同一条知识内的唯一标识，改它会让「v2 是哪一个」在历史数据里出现两个答案。要换请新建版本',
   OLD.version_no, NEW.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.question IS DISTINCT FROM OLD.question THEN
  RAISE EXCEPTION '已发布的知识版本不能改问题（版本 %）：问题本身就是这一版的识别标志，改它会让「客服当时看到的是哪个问题」查不到。要改请新建版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.answer IS DISTINCT FROM OLD.answer THEN
  RAISE EXCEPTION '已发布的知识版本不能改答法正文（版本 %）：15.1.1.2 已发出的消息与 15.5.1 自动回复都按这一版答复过买家，改正文等于追溯改写当时答复了什么。要改请新建版本并让新版本接替',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
  RAISE EXCEPTION '已发布的知识版本不能改生效起点（版本 %）：移动起点会追溯改写历史上这个格子生效的是哪一版，而买家已经按当时的版本收到了答复',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.published_by IS DISTINCT FROM OLD.published_by
    OR NEW.published_at IS DISTINCT FROM OLD.published_at THEN
  RAISE EXCEPTION '已发布的知识版本不能改发布留痕（版本 %）：「谁在什么时候发布的」是这一版可追溯的凭据，改掉它等于让发布这件事无法证伪',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.note IS DISTINCT FROM OLD.note THEN
  RAISE EXCEPTION '已发布的知识版本不能改备注（版本 %）：备注写在发布内容里，发布后与正文一样不可变。要补充说明请新建版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;

 -- effective_to 是唯一的例外，且只允许向前关闭（规则 R5）。
 IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
  IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '已发布的知识版本不能重开生效区间（版本 %）：重新变成「一直有效」会让已经接替它的版本与它同时生效，规则 R2 立刻被破坏',
    OLD.version_no USING ERRCODE='23514';
  END IF;
  IF OLD.effective_to IS NOT NULL AND NEW.effective_to > OLD.effective_to THEN
   RAISE EXCEPTION '已发布的知识版本不能把生效终点往后延（版本 %，% → %）：往后延会与已经接替它的版本重叠，「这个格子此刻该答哪一版」将不再唯一',
    OLD.version_no, OLD.effective_to, NEW.effective_to USING ERRCODE='23514';
  END IF;
  IF NEW.effective_to <= now() THEN
   RAISE EXCEPTION '已发布的知识版本不能把生效终点设成已经过去的时刻（版本 %，%）：那会追溯改写历史上这个格子生效的是哪一版，而买家已经按当时的版本收到了答复',
    OLD.version_no, NEW.effective_to USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_crm_knowledge_version_published BEFORE UPDATE ON crm_knowledge_version
  FOR EACH ROW EXECUTE FUNCTION guard_crm_knowledge_version_published();

-- 已发布版本不可删（连同删档案时的级联一起拦住）。
CREATE FUNCTION refuse_crm_knowledge_version_published_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='PUBLISHED' THEN
  RAISE EXCEPTION '已发布的知识版本不能删除（版本 %）：15.1.1.2 已发出的消息与 15.5.1 自动回复都引用过这一版，删掉会让「当时答复买家的是哪一版」无从查证，引用记录也会跟着指向空气。要停止使用请把知识归档，或新建一版接替它',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 -- 草稿版本：放行删除。其上的引用行由外键的 ON DELETE CASCADE 一起清掉，
 -- 不会留下指向空气的引用（没有触发器需要为此再拦一次）。
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_crm_knowledge_version_published_delete BEFORE DELETE ON crm_knowledge_version
  FOR EACH ROW EXECUTE FUNCTION refuse_crm_knowledge_version_published_delete();

-- 区间重叠拒绝（规则 R2）。只有已发布版本参与判定（规则 R3），
-- 所以草稿之间随便重叠，发布的那一刻才被检查。
CREATE FUNCTION guard_crm_knowledge_version_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE clash record;
BEGIN
 IF NEW.status<>'PUBLISHED' THEN RETURN NEW; END IF;
 SELECT v.version_no, v.effective_from, v.effective_to INTO clash
   FROM crm_knowledge_version v
  WHERE v.tenant_id=NEW.tenant_id
    AND v.entry_id=NEW.entry_id
    AND v.id<>NEW.id
    AND v.status='PUBLISHED'
    -- 半开区间比较：'[)' 让 [10:00,12:00) 与 [12:00,14:00) 在 12:00 处不重叠（规则 R1）。
    AND tstzrange(v.effective_from, v.effective_to, '[)')
        && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ORDER BY v.effective_from, v.id
  LIMIT 1;
 IF FOUND THEN
  RAISE EXCEPTION '生效区间与已发布版本 % 冲突（本版本 %：% → %）：同一条知识在同一时刻只能有一个生效版本，否则客服与 15.5.1 自动回复都答不出「这个格子此刻该给买家哪一条答法」。已发布版本的区间不能改，请调整本版本的区间或把接替版本的终点设为 % 的起点',
   clash.version_no, NEW.version_no, NEW.effective_from,
   coalesce(NEW.effective_to::text, '一直有效'),
   clash.effective_from USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_crm_knowledge_version_interval BEFORE INSERT OR UPDATE ON crm_knowledge_version
  FOR EACH ROW EXECUTE FUNCTION guard_crm_knowledge_version_interval();

-- 引用记录只追加：既不可改也不可删（见文件头第五节）。
CREATE FUNCTION refuse_crm_knowledge_reference_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '知识引用记录不可修改或删除（版本 %，%）：引用记录的唯一用途就是回答「这条知识到底被用过没有、被谁用过」，允许改删等于可以把「没人用过」这个答案做出来 —— 而「多少条知识根本没人用」恰恰是运营最该先看到的数',
  OLD.version_id, OLD.consumer USING ERRCODE='23514';
END $$;
CREATE TRIGGER refuse_crm_knowledge_reference_mutation BEFORE UPDATE OR DELETE ON crm_knowledge_reference
  FOR EACH ROW EXECUTE FUNCTION refuse_crm_knowledge_reference_mutation();

-- ============================================================================
-- 5. 索引
-- ============================================================================

-- 档案列表页：按格子定位（店铺 + 类目 + 语言）再按更新时间倒序。
CREATE INDEX crm_knowledge_entry_by_scope ON crm_knowledge_entry(tenant_id, shop_id, category_id, locale, updated_at DESC, id);

-- **平台改版定位过期条目的主查询形状**（文件头第四节第 3 条）：
-- 按平台取条目，再联到「当前生效且仍未接替」的那一版。
CREATE INDEX crm_knowledge_entry_by_platform ON crm_knowledge_entry(tenant_id, platform_code, status, id);

-- 「当前生效版本」的确定性查询形状（规则 R4，15.1.1.2 发送与 15.5.1 自动回复都走它）。
CREATE INDEX crm_knowledge_version_effective ON crm_knowledge_version(tenant_id, entry_id, effective_from, effective_to)
  WHERE status='PUBLISHED';

-- **review-due 的倒排形状**：某平台上「生效起点早于某时刻且仍是开放区间」的已发布版本。
-- 这是「改版之后哪些条目还停在旧口径」这条查询真正依赖的索引。
CREATE INDEX crm_knowledge_version_open_ended ON crm_knowledge_version(tenant_id, effective_from, entry_id)
  WHERE status='PUBLISHED' AND effective_to IS NULL;

-- 版本列表页的主查询形状：按档案读全部版本。
CREATE INDEX crm_knowledge_version_by_entry ON crm_knowledge_version(tenant_id, entry_id, version_no DESC);

-- 工作台默认视图：草稿优先、改动最近的在上。
CREATE INDEX crm_knowledge_version_drafts ON crm_knowledge_version(tenant_id, status, updated_at DESC, id DESC);

-- 引用记录：按版本读「这条知识被谁引用过」。
CREATE INDEX crm_knowledge_reference_by_version ON crm_knowledge_reference(tenant_id, version_id, referenced_at DESC, id);
-- 「最近被引用过什么」的倒排：15.4.2 的引用面板与 15.1.1.2 发送记录都走它。
CREATE INDEX crm_knowledge_reference_by_time ON crm_knowledge_reference(tenant_id, referenced_at DESC, id);
-- 按引用人聚合：算「谁最常用这条知识」时不必全表扫。
CREATE INDEX crm_knowledge_reference_by_actor ON crm_knowledge_reference(tenant_id, referenced_by, referenced_at DESC);

-- ============================================================================
-- 6. RLS 与授权
-- ============================================================================
-- 三张表全部租户级数据（客服知识是租户共享的运营资产，
-- 与 catalog V18 媒体库 / V23 套装 / V25 BOM 同一口径，不套 owner_id/department_id 数据范围）。
--
-- ENABLE + FORCE（FORCE 是为了让表 owner 也受策略约束，否则运行角色一旦被误授 owner
-- 就等于 RLS 失效），策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
-- 这条语义由 CrmTenancyIntegrationTest 实跑钉住，改动 nullif 必须同时改测试。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_knowledge_entry','crm_knowledge_version','crm_knowledge_reference'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_knowledge_entry, crm_knowledge_version, crm_knowledge_reference TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_knowledge_entry, crm_knowledge_version, crm_knowledge_reference TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 7. 留给下游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.1.1.2 消息发送**（**未开发**）：按 (shop_id, category_id, locale, at=?) 取
--     「此刻生效的那一条」（GET /crm/knowledge/entries?shopId=&categoryId=&locale=&at=，
--     规则 R4 的时刻查询），发送前用 POST /crm/knowledge/versions/{id}/references
--     记一次引用。**发送失败要保留原消息状态**是它自己的职责，本模块不代劳。
--   * **15.5.1.1 自动回复**（**未开发**）：同一套生效查询取答法，
--     并以 consumer=AUTO_REPLY 记引用。它的规则与分级自动回复也都是它自己的事。
--   * **15.4.1.1 工单列表 / 15.4.1.2 工单处理**（**均未开发**）：
--     可以在处理页引用知识并记 consumer=MANUAL_REPLY。
--   * **3.4.2.2 文案资产**（catalog V21，已交付）：那是**商品侧**文案
--     （消费者在详情页读到），本模块是**客服侧**知识（客服应答买家时读到）。
--     两者都要多语言，但翻译挂在不同宿主上（那边挂模板，这里挂知识版本），
--     本库不存模板变量表，也不引用 content_template。
