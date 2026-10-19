-- 11.1.2.2 商品问题归因（卷 11 售后与退货 / L3 11.1.2 原因与分类的第二格）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：把质量问题归因到具体环节（生产、运输、仓储、描述），支持批次追溯与照片比对，
--         形成对供应商或流程的改进建议。
--   输入：售后原因、批次信息、退回实物照片、质检结论。
--   输出：归因结论 + 批次质量标记。
--   关系：上游 = 7.3.1.1 批次、11.2.3.1；下游 = 6.4.2.2 索赔、6.1.1.3 评级。
--   **批次归因是「同一个批次退回来 10 件」和「10 个批次各退 1 件」的区别**，
--   前者是供应商责任，后者可能是运输或买家因素。
--
-- 归属：**trade 服务**（售后原因、质检结论、退回实物照片都在本服务；
--   **批次信息在 warehouse 独立库**，按 AGENTS.md 模块边界纪律只经授权 HTTP 端点读，
--   见文件头第四节）。
--
-- 本迁移**只新建五张表与触发器、零 ALTER 零 DROP 既有表**：
--   * aftersales_case（V156，11.1.1）**只读**：售后单存在性与租户归属的校验来源。
--   * aftersales_reason_attribution_set / _item / aftersales_reason_category
--     （V158，11.1.2.1 已交付）**只读**：输入句「售后原因」的**唯一口径来源**。
--     ⚠️ 同库同服务，所以**不加外键也加不加都安全**，但刻意不加 ——
--     加 FK 会让本迁移对同批别的车道表产生 DDL 依赖。
--   * return_quality_inspection / return_quality_check_result
--     （V179，11.2.3.1 已交付）**只读**：输入句「质检结论」。
--   * return_receipt_photo（V172，11.2.2.1 已交付）**只读**：输入句「退回实物照片」。
--   * inventory_lot（V57，**warehouse 独立库**）：**根本不存在于本库**，跨库 FK 被禁止。

-- ===========================================================================
-- 一、两条硬规则各自钉在哪 —— 为什么不能只写在服务层
-- ===========================================================================
--
-- 【硬规则 1】归因必须落在**四个具体环节**之一，不是自由文本：
--   职责句点名的「生产、运输、仓储、描述」是**枚举四值**
--   （PRODUCTION / TRANSPORT / WAREHOUSE / DESCRIPTION），
--   再加 BUYER_FACTOR（买家因素）与 UNDETERMINED（待定）两个诚实的出口 ——
--   「责任待定」是一个必须能被**如实记下来**的结论，
--   把它逼成四选一等于逼着运营编一个环节出来，而编出来的环节会直接流向下游索赔。
--   ⇒ 落成 product_issue_attribution.attributed_stage 的 CHECK 枚举，
--     并由触发器 guard_product_issue_attribution 按**显式规则**判它够不够格：
--     ① **QUALITY_DEFECT 之外的原因码不许归因到 PRODUCTION**：
--        「描述不符」归到「生产」在物理上讲不通（描述是页面问题，不是产线问题），
--        而这条错误一旦流到 6.4.2.2 索赔就是拿错误责任去和供应商对质。
--     ② **归因到 PRODUCTION 必须有 lot_id**：「生产责任」的最小单位是批次 ——
--        没有批次就指不到某一次投料，向供应商索赔时根本说不出「哪一批」。
--     ③ **有 lot_id 时归因到 TRANSPORT 必须说明理由**：运输责任要指得出是哪一段运输。
--     ④ 责任环节**终态不可逆**（见第三节）。
--
-- 【硬规则 2】批次质量标记是**统计结论**，不是「有人标了一下」：
--   蓝图加粗句是本模块存在的理由 —— 「同一个批次退回来 10 件」和
--   「10 个批次各退 1 件」的处置结论**完全相反**（供应商责任 vs 运输/买家因素）。
--   要让这句话成立，标记必须由**真实归因数据聚合**得出，而不是接受调用方传入一个标记。
--   ⇒ product_issue_lot_flag 的
--      suspect_return_count / affected_return_qty / stage_breakdown
--      **三个列全部由 DB 触发器按当前生效归因重算**，插入语句里的任何取值都不被接受；
--      且 SUSTPECT（可疑批次）**要求** affected_return_qty ≥ min_flag_qty
--      （默认 3 **件**），1 件也标「批次有质量问题」会让整个标记体系变成噪声。
--      ⚠️ 判据落在**件数**而不是归因单张数：加粗句说的是「同一个批次退回来 10 件」
--      与「10 个批次各退 1 件」—— 前者在这个批次上是 10 件（该标），
--      后者每个批次都只有 1 件（一个都不该被标）。按张数判会把这两者**判反**。
--   ⚠️ 这也是「每张事实表只有一个写入所有者」在本模块的落法：
--     trade 拥有的是**归因事实**，warehouse 的 inventory_lot 本模块**只读不写**。
--
-- ===========================================================================
-- 二、照片比对：落的是什么口径，说清楚
-- ===========================================================================
-- 职责句写「照片比对」。本表落的口径是**内容指纹比对**，**不是**图像相似度算法：
--   * 落库：object_uri（对象键）+ content_sha256（内容指纹）+ content_bytes + content_type。
--   * 比对：product_issue_photo_comparison 把两张照片的 sha256 对起来，
--     判 IDENTITY_SAME / IDENTITY_DIFFER。
-- ⚠️ **刻意不做 perceptual hash / 向量相似 / 图像分类**：
--   同一件商品在不同光线与角度下拍出来的两张照片，内容指纹必然不同，
--   而本仓没有图像库、没有训练好的模型、也没有第三方视觉服务的接入点。
--   编一个「相似度」数字出来 —— 比如用字节数比值或 md5 前缀碰撞当相似度 ——
--   会让运营以为系统看懂了照片，而它其实只比了字节。
--   ⇒ 口径写在列注释与 capabilities 里，明确报成「内容指纹一致 / 不一致」，
--     **语义级**的「是不是同一处缺陷」由人工在归因说明里写。

-- ===========================================================================
-- 三、状态机：两个状态列，各自只有一条路径
-- ===========================================================================
-- product_issue_attribution：
--   DRAFT       草稿：证据（照片）与批次引用还在录，**没有归因结论**
--   ATTRIBUTED  已归因：环节 + 理由 + 依据三者同时成立（由 DB 触发器保证）
--   SUPERSEDED  被新版本取代：归因结论是要拿去和供应商对质的，判错了只能改判留痕，
--               不能就地改数 —— 那会让「这批货到底归给谁」出现两个答案。
--               部分唯一索引保证一单同时只有一版生效。
-- ⚠️ 蓝图要求的「形成对供应商或流程的改进建议」落在 improvement_suggestion：
--   **只是一段必须非空的文字**，不假装是 AI 生成 —— 本仓没有模型接入点，
--   编一个「AI 建议」出来就是在冒充智能。建议的**消费**（改进单、索赔发起）
--   属 6 采购 / 6.4.2.2，未交付。

-- ===========================================================================
-- 四、跨服务边界：批次只经 warehouse 的授权 HTTP 端点读
-- ===========================================================================
-- ⚠️ 蓝图输入句的「批次信息」真值在 **warehouse 独立库**（7.3.1.1，V57 inventory_lot）。
--   AGENTS.md 模块边界纪律：禁止跨服务 SQL / 外键 / 共享视图 / 直连对方库。
--   本迁移**因此没有**任何指向 inventory_lot 的外键或视图（本库连那张表都没有）。
--   读法：ProductLotTracePort（RestClient）转发**调用方自己的 Authorization bearer**，
--   warehouse 侧仍按已认证成员关系解析租户并强制 RLS。
--   **绝不传递或信任 X-Tenant-Id 头。**
--   ⇒ product_issue_attribution 里存的是 lot_id + **本地快照列**
--     （lot_code / produced_on / supplier_ref_id），
--     快照的意义与 V172/V179 一致：「交接凭据自带快照」——
--     批次档案日后被改名或删除，这张归因单仍要能说出「当时是哪个批次」。

-- ===========================================================================
-- 五、类目与 SKU 映射
-- ===========================================================================
CREATE TABLE product_issue_attribution (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  attribution_no varchar(32) NOT NULL,

  -- ---- 上游：售后单（V156，11.1.1）。**刻意不加外键**（跨车道 DDL 依赖）。
  --     存在性与租户归属由触发器 guard_product_issue_attribution 真查一次。
  case_id uuid NOT NULL,

  -- ---- 上游：11.1.2.1 的**当前生效**归因集合（superseded_at IS NULL）。
  --     加它是为了让「售后原因」这个输入句有唯一口径，而不是每次让操作员现填一次。
  reason_set_id uuid,

  -- ---- 上游：11.2.3.1 的**已判定**质检单（input 句「质检结论」）。
  --     归因的对象是「这个被判定为有问题的实物」，没有质检结论就没有归因对象。
  inspection_id uuid,

  -- ---- 上游：7.3.1.1 批次（**warehouse 独立库**，只有 id + 快照，无外键）。
  --     快照三列的意义见文件头第四节「交接凭据自带快照」。
  lot_id uuid,
  lot_code varchar(128) NOT NULL DEFAULT '',
  lot_produced_on date,
  lot_supplier_ref_id uuid,

  -- ---- SKU 快照：批次日后改名 / 下架，归因单仍要能说出「归因的是哪个商品」。
  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- 归因数量 = 本次归因覆盖的退回数量（NUMERIC(19,6)，禁浮点，AGENTS.md）。
  attributed_qty numeric(19,6) NOT NULL CHECK(attributed_qty > 0),

  -- ---- 归因状态机（第三节）----
  attribution_status varchar(16) NOT NULL CHECK(attribution_status IN (
      'DRAFT','ATTRIBUTED','SUPERSEDED'
  )),

  -- ---- 归因结论（硬规则 1）。四环节 + 两个诚实出口。----
  attributed_stage varchar(20) CHECK(attributed_stage IS NULL OR attributed_stage IN (
      'PRODUCTION','TRANSPORT','WAREHOUSE','DESCRIPTION','BUYER_FACTOR','UNDETERMINED'
  )),
  stage_label varchar(64) NOT NULL DEFAULT '',

  -- 归因理由：**必须写**。归因结论是要拿去和供应商 / 平台对质的，
  -- 「点一下下拉框」出来的责任在纠纷里等于没有责任。
  -- 且 improvement_suggestion（改进建议）是职责句明文要求的输出，单独成列而不是塞进理由。
  attribution_note text NOT NULL DEFAULT '' CHECK(length(attribution_note) <= 2000),
  improvement_suggestion text NOT NULL DEFAULT '' CHECK(length(improvement_suggestion) <= 2000),

  -- ---- 归因人 / 归因时刻 ----
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  attributed_by uuid,
  attributed_at timestamptz,
  superseded_by uuid,
  superseded_at timestamptz,
  supersede_reason text NOT NULL DEFAULT '' CHECK(length(supersede_reason) <= 1000),

  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,attribution_no),

  -- 已归因必须四样齐全：环节、理由、改进建议、人与时刻。
  CHECK(attribution_status <> 'ATTRIBUTED'
        OR (attributed_stage IS NOT NULL
            AND length(btrim(attribution_note)) > 0
            AND length(btrim(improvement_suggestion)) > 0
            AND attributed_by IS NOT NULL AND attributed_at IS NOT NULL)),
  -- **只有草稿不许带结论**。
  -- ⚠️ 被取代的那一版（SUPERSEDED）**必须保留它当时的环节与归因时刻** ——
  --   那正是「改判」要留的痕。把它抹掉的话，改判就变成了「从来没有过那个结论」，
  --   而纠纷里的对手方会问「你们当初凭什么是运输责任」。
  CHECK(attribution_status <> 'DRAFT'
        OR (attributed_stage IS NULL AND attributed_at IS NULL)),
  -- 被取代必须说明理由：改判是要留痕的。
  CHECK(attribution_status <> 'SUPERSEDED'
        OR (superseded_by IS NOT NULL AND superseded_at IS NOT NULL
            AND length(btrim(supersede_reason)) > 0)),
  -- 归因数量不得超过该 SKU 的一次退货上限量级：写入时由触发器按实收数量校验，
  -- 这里只挡住 0 与负数（更深的一条在守卫①）。
  CHECK(attributed_qty > 0),
  -- **归因到「生产」必须挂批次**（硬规则 1 ②）。
  -- 这一条写成表级 CHECK 是因为它与 attributed_stage 同一行内可判定，
  -- 不需要跨行去查 —— 让「生产责任」永远带得上一个批次指针。
  CHECK(attributed_stage IS DISTINCT FROM 'PRODUCTION' OR lot_id IS NOT NULL)
);

-- ===========================================================================
-- 五之二、照片与照片比对（口径见第二节）
-- ===========================================================================
CREATE TABLE product_issue_photo (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  attribution_id uuid NOT NULL
      REFERENCES product_issue_attribution(id) ON DELETE CASCADE,

  -- **引用** 11.2.2.1 已登记的照片（不复制，见 V179 第四节的同一条纪律）：
  -- 复制一份会让「举证材料有几张、哪一张被改过」出现两个答案。
  source_photo_id uuid,

  -- 照片的角色：决定它能否支撑某个环节的归因（比对时按角色取图）。
  photo_role varchar(24) NOT NULL CHECK(photo_role IN (
      'PRODUCT_OVERVIEW','DEFECT_CLOSEUP','PACKAGING','LABEL_BATCH','OTHER'
  )),

  -- ---- 本地快照：对象键 + 内容指纹 + 字节数 + 类型。**不存二进制大对象**。----
  object_uri varchar(500) NOT NULL,
  content_sha256 char(64) NOT NULL CHECK(content_sha256 ~ '^[0-9a-f]{64}$'),
  content_bytes bigint NOT NULL CHECK(content_bytes > 0),
  content_type varchar(128) NOT NULL DEFAULT '',

  -- 拍摄时刻与登记时刻分开：两者之差就是「货到了多久才被拍下来」，
  -- 压成一列就答不出「这批证据是不是补拍的」（照抄 V172 的同一纪律）。
  captured_at timestamptz NOT NULL,
  registered_at timestamptz NOT NULL DEFAULT now(),

  caption text NOT NULL DEFAULT '' CHECK(length(caption) <= 500),
  registered_by uuid NOT NULL,

  UNIQUE(tenant_id,id),
  -- ⚠️ **刻意不加 UNIQUE(attribution_id, content_sha256)**：
  --   加了它，同一张图就存不进两次，而照片比对的**核心价值恰恰是识别「同一份内容被登记了两次」**
  --   （同步器重复投递、客服又拍了一遍同一个缺陷）。内容相同的两个对象键必须能共存，
  --   否则 IDENTITY_SAME 永远不可能出现，这个比对就只剩下一半功能。
  --   「同一张归因单上重复登记同一份内容」的提示由服务层的统计与界面承担，不靠唯一索引堵死。
  CHECK(content_sha256 ~ '^[0-9a-f]{64}$')
);

-- 照片比对记录：**落的是内容指纹比对**，不是图像相似度（见第二节）。
CREATE TABLE product_issue_photo_comparison (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  attribution_id uuid NOT NULL
      REFERENCES product_issue_attribution(id) ON DELETE CASCADE,

  left_photo_id uuid NOT NULL REFERENCES product_issue_photo(id) ON DELETE CASCADE,
  right_photo_id uuid NOT NULL REFERENCES product_issue_photo(id) ON DELETE CASCADE,

  -- **只有两种结果**：内容指纹一致 / 不一致。
  -- 刻意没有 0.87 这种「相似度」—— 见第二节：比不出图像相似度，
  -- 编一个数字等于冒充图像识别能力。
  compare_result varchar(16) NOT NULL CHECK(compare_result IN (
      'IDENTITY_SAME','IDENTITY_DIFFER'
  )),

  -- 比对口径：把「我们到底比了什么」写在数据里，而不是只写在文档里。
  -- 恒为 CONTENT_FINGERPRINT —— 它是**枚举**而不是自由文本，
  -- 未来真接入图像服务时可以新增取值，而不会让历史记录的语义漂移。
  compare_basis varchar(32) NOT NULL DEFAULT 'CONTENT_FINGERPRINT'
      CHECK(compare_basis = 'CONTENT_FINGERPRINT'),

  comparison_note text NOT NULL DEFAULT '' CHECK(length(comparison_note) <= 1000),
  compared_by uuid NOT NULL,
  compared_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  -- 一对照片在一个归因单里只比对一次：重复比对会让「比对了几次」出现两个答案。
  UNIQUE(attribution_id,left_photo_id,right_photo_id),
  -- 左右必须是**不同**两张照片（自己跟自己比对没有意义，且会让「比对了几对」虚高）。
  CHECK(left_photo_id <> right_photo_id)
);

-- ===========================================================================
-- 六、批次质量标记（硬规则 2 的落点）
-- ===========================================================================
-- ⚠️ 这张表**在 trade 库**，引用 warehouse 的 lot_id 但**不写 warehouse 的 inventory_lot**。
--   批次事实的写入所有者是 warehouse（7.3.1.1），本表是 trade 侧**基于归因事实的统计结论**，
--   两者是不同的东西：inventory_lot 记「这个批次有多少货」，
--   本表记「这个批次**退回来多少、归到了哪些环节**」。
CREATE TABLE product_issue_lot_flag (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 批次：warehouse 独立库的 id（无外键，本库无 inventory_lot 表）。
  lot_id uuid NOT NULL,

  -- 批次快照（与归因单同纪律「交接凭据自带快照」）。
  lot_code varchar(128) NOT NULL DEFAULT '',
  lot_produced_on date,
  lot_supplier_ref_id uuid,
  sku_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',

  -- ---- 统计结论：下面四列全部由 DB 触发器重算，**插入语句不接受任何取值** ----
  -- 命中该批次的**当前生效**归因单数（superseded 的那几版不算）。
  suspect_return_count integer NOT NULL DEFAULT 0 CHECK(suspect_return_count >= 0),
  -- 命中该批次的当前生效归因数量合计（NUMERIC(19,6)，禁浮点）。
  affected_return_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(affected_return_qty >= 0),
  -- 按环节拆分的件数分布：production/transport/warehouse/description/
  -- buyer_factor/undetermined。JSONB 让「这批货主要坏在哪」可聚合，
  -- 而六个独立小计数列会让这张表宽到没法一眼看懂。
  stage_breakdown jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 标记状态。可疑批次要求 ≥ min_flag_qty **件**（默认 3，见第二节）。
  flag_status varchar(16) NOT NULL CHECK(flag_status IN (
      'NONE','SUSPECT','CONFIRMED'
  )),
  -- 触发本条标记的最低**件数**口径（记下来，让「为什么这条被标了」可复核）。
  -- ⚠️ 名字与判据都落在**件数**（affected_return_qty）上，不是归因单张数 ——
  --   蓝图加粗句说的是「同一个批次退回来 **10 件**」与「10 个批次各退 **1 件**」：
  --   一张单退 10 件和十张单各退 1 件，在这个批次上都是「10 件」，
  --   按张数判会把前者算成 1、把后者算成 10，正好判反。
  min_flag_qty numeric(19,6) NOT NULL DEFAULT 3 CHECK(min_flag_qty > 0),

  flag_note text NOT NULL DEFAULT '' CHECK(length(flag_note) <= 1000),

  -- 重算时刻：统计结论的时点，让「这份统计是什么时候的」有唯一答案。
  recomputed_at timestamptz NOT NULL DEFAULT now(),
  flagged_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一个批次在同一租户内只有一行标记（重算走 UPDATE，不另起一行）。
  UNIQUE(tenant_id,lot_id),

  -- **可疑批次必须有真实件数支撑**（硬规则 2）：
  -- 1 件也标「批次有质量问题」会让整个标记体系变成噪声，
  -- 而噪声标记的代价是运营再也不看它 —— 那才是真正把蓝图加粗句毁掉的方式。
  CHECK(flag_status = 'NONE' OR affected_return_qty >= min_flag_qty),
  -- CONFIRMED 意味着已确认要向供应商追责：必须说明理由。
  CHECK(flag_status <> 'CONFIRMED' OR length(btrim(flag_note)) > 0)
);

-- ===========================================================================
-- 七、留痕（追加式）
-- ===========================================================================
CREATE TABLE product_issue_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  attribution_id uuid NOT NULL
      REFERENCES product_issue_attribution(id) ON DELETE CASCADE,

  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'CREATED','PHOTO_REGISTERED','PHOTO_COMPARED','ATTRIBUTED','SUPERSEDED','LOT_FLAGGED'
  )),
  event_note text NOT NULL DEFAULT '' CHECK(length(event_note) <= 1000),
  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id)
);

-- ===========================================================================
-- 八、索引
-- ===========================================================================
CREATE INDEX product_issue_attribution_by_case
  ON product_issue_attribution(tenant_id, case_id, attribution_status);
CREATE INDEX product_issue_attribution_by_lot
  ON product_issue_attribution(tenant_id, lot_id) WHERE lot_id IS NOT NULL;
CREATE INDEX product_issue_attribution_by_stage
  ON product_issue_attribution(tenant_id, attributed_stage, attributed_at DESC);
CREATE INDEX product_issue_attribution_by_sku
  ON product_issue_attribution(tenant_id, sku_code, attributed_at DESC);
-- **一单同时只能有一版生效归因**（DB 不变量而不是应用层先查一次 ——
-- 并发下两个请求都能查到「还没有生效版本」，靠应用层判会双双通过）。
CREATE UNIQUE INDEX product_issue_one_effective_per_case
  ON product_issue_attribution(tenant_id, case_id)
  WHERE attribution_status <> 'SUPERSEDED';

CREATE INDEX product_issue_photo_by_attribution
  ON product_issue_photo(tenant_id, attribution_id, registered_at);
CREATE INDEX product_issue_photo_comparison_by_attribution
  ON product_issue_photo_comparison(tenant_id, attribution_id, compared_at);
CREATE INDEX product_issue_lot_flag_by_lot
  ON product_issue_lot_flag(tenant_id, lot_id);
CREATE INDEX product_issue_lot_flag_by_status
  ON product_issue_lot_flag(tenant_id, flag_status, recomputed_at DESC);
CREATE INDEX product_issue_event_by_attribution
  ON product_issue_event(tenant_id, attribution_id, occurred_at);

CREATE SEQUENCE product_issue_attribution_no_seq;
GRANT USAGE,SELECT ON SEQUENCE product_issue_attribution_no_seq TO trade_app;

-- ===========================================================================
-- 九、守卫触发器
-- ===========================================================================
-- ⚠️ 本节全部是 **BEFORE** 触发器：不一致就当场拒，不留半行再回滚。
--   跨表一致性刻意**真查一次**而不建外键（文件头说明）。

CREATE OR REPLACE FUNCTION guard_product_issue_attribution() RETURNS trigger AS $$
DECLARE
  v_case_tenant uuid;
  v_reason_ok   integer;
  v_insp_status varchar(16);
  v_reason_case uuid;
  v_received_qty numeric(19,6);
BEGIN
  -- ---- 守卫①：售后单必须存在且属于本租户（跨租户在 RLS 下查不到 → 拒）----
  SELECT tenant_id INTO v_case_tenant FROM aftersales_case WHERE id = NEW.case_id;
  IF v_case_tenant IS NULL OR v_case_tenant <> NEW.tenant_id THEN
    RAISE EXCEPTION '售后单不存在或不属于当前租户：%', NEW.case_id
      USING ERRCODE = '23503';
  END IF;

  -- ---- 守卫②：质检单必须**已判定**（输入句「质检结论」）----
  IF NEW.inspection_id IS NOT NULL THEN
    SELECT inspection_status INTO v_insp_status
      FROM return_quality_inspection WHERE id = NEW.inspection_id AND tenant_id = NEW.tenant_id;
    IF v_insp_status IS NULL THEN
      RAISE EXCEPTION '质检单不存在或不属于当前租户：%', NEW.inspection_id
        USING ERRCODE = '23503';
    END IF;
    IF v_insp_status <> 'CONCLUDED' THEN
      RAISE EXCEPTION '只能对已判定的质检单做归因（当前状态 %）：%', v_insp_status, NEW.inspection_id
        USING ERRCODE = '23514';
    END IF;
  END IF;

  -- ---- 守卫③：原因集合必须是**当前生效**的那一版，且属于同一张售后单 ----
  IF NEW.reason_set_id IS NOT NULL THEN
    SELECT case_id INTO v_reason_case
      FROM aftersales_reason_attribution_set
     WHERE id = NEW.reason_set_id AND tenant_id = NEW.tenant_id
       AND superseded_at IS NULL;
    IF v_reason_case IS NULL THEN
      RAISE EXCEPTION '原因归因集合不存在、已被取代或不属于当前租户：%', NEW.reason_set_id
        USING ERRCODE = '23503';
    END IF;
    SELECT count(*) INTO v_reason_ok
      FROM aftersales_reason_attribution_item
     WHERE set_id = NEW.reason_set_id AND tenant_id = NEW.tenant_id;
    IF v_reason_ok = 0 THEN
      RAISE EXCEPTION '原因归因集合没有任何成员，拒绝归因（11.1.2.1 的口径不允许零成员）'
        USING ERRCODE = '23514';
    END IF;
    IF v_reason_case <> NEW.case_id THEN
      RAISE EXCEPTION '原因归因集合属于另一张售后单（集合 % ≠ 单 %）', NEW.reason_set_id, NEW.case_id
        USING ERRCODE = '23514';
    END IF;
  END IF;

  -- ---- 守卫④：归因数量不得超过本张售后单的**实收数量** ----
  --   「归因 10 件」而这一单只收到 3 件，会把批次标记的统计基数算虚 ——
  --   而批次标记正是蓝图加粗句里「同一个批次退回来 10 件」的计数来源，算虚了整个模块就没意义了。
  --   路径：aftersales_case → rma_authorization.case_id → return_receipt.rma_id
  --        → return_receipt_line.receipt_id → received_qty。
  --   ⚠️ return_inventory_posting 与 return_quality_inspection **都没有 case_id 列**
  --     （前者只有 receipt_line_id，后者只有 posting_id/receipt_line_id），
  --     所以必须经 rma_authorization 走这一跳，不能图省事直接 join 分录表。
  SELECT COALESCE(SUM(l.received_qty), 0) INTO v_received_qty
    FROM rma_authorization a
    JOIN return_receipt r ON r.tenant_id = a.tenant_id AND r.rma_id = a.id
    JOIN return_receipt_line l ON l.tenant_id = r.tenant_id AND l.receipt_id = r.id
   WHERE a.tenant_id = NEW.tenant_id AND a.case_id = NEW.case_id;
  IF NEW.attributed_qty > v_received_qty THEN
    RAISE EXCEPTION '归因数量 % 超过该售后单的实收数量 %（还没有实收记录的售后单不能做归因）',
      NEW.attributed_qty, v_received_qty
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_guard_product_issue_attribution
  BEFORE INSERT OR UPDATE ON product_issue_attribution
  FOR EACH ROW EXECUTE FUNCTION guard_product_issue_attribution();

-- 守卫①-④ 之外再加一条：**环节与售后原因码的相容性**（硬规则 1 ①）。
-- 单独一个触发器是因为它需要 join reason_set → item → category 拿 root_kind，
-- 与守卫①-④ 的关注点不同，混在一起会让报错信息指向不清楚。
CREATE OR REPLACE FUNCTION guard_product_issue_stage_consistency() RETURNS trigger AS $$
DECLARE
  v_root_kind varchar(32);
BEGIN
  IF NEW.attributed_stage IS NULL OR NEW.attributed_stage = 'UNDETERMINED' THEN
    RETURN NEW;
  END IF;

  -- 生产责任只对「质量问题」成立：描述不符归到生产在物理上讲不通，
  -- 而这条错误一旦流到 6.4.2.2 索赔就是拿错误责任去和供应商对质。
  IF NEW.attributed_stage = 'PRODUCTION' AND NEW.reason_set_id IS NOT NULL THEN
    -- 11.1.2.1 的子分类按 effective_root_kind 汇总进七类，
    -- 所以这里看的是**根口径**而不是成员自己的分类。
    SELECT DISTINCT c.root_kind INTO v_root_kind
      FROM aftersales_reason_attribution_item i
      JOIN aftersales_reason_category c ON c.id = i.category_id
     WHERE i.set_id = NEW.reason_set_id AND i.tenant_id = NEW.tenant_id;
    IF v_root_kind IS NOT NULL AND v_root_kind <> 'QUALITY_DEFECT' THEN
      RAISE EXCEPTION '售后原因根口径是 %，不能归因到「生产」（生产责任只对质量问题成立）', v_root_kind
        USING ERRCODE = '23514';
    END IF;
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_guard_product_issue_stage
  BEFORE INSERT OR UPDATE ON product_issue_attribution
  FOR EACH ROW EXECUTE FUNCTION guard_product_issue_stage_consistency();

-- ---------------------------------------------------------------------------
-- 照片比对的真实性守卫：compare_result 必须**与两张照片的指纹真相比对**一致。
-- 绕过后端直接写 SQL 时，「指纹明明不同却写 SAME」会被当场拒 ——
-- 这正是「不假装做过图像相似度」这条纪律的物理落法：
-- 只要这个触发器在，任何写 SAME 的行都必然意味着两个 sha256 真的相等。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_product_issue_photo_comparison() RETURNS trigger AS $$
DECLARE
  v_left_sha  char(64);
  v_right_sha char(64);
  v_left_att  uuid;
  v_right_att uuid;
BEGIN
  SELECT content_sha256, attribution_id INTO v_left_sha, v_left_att
    FROM product_issue_photo WHERE id = NEW.left_photo_id AND tenant_id = NEW.tenant_id;
  IF v_left_sha IS NULL THEN
    RAISE EXCEPTION '左侧照片不存在或不属于当前租户：%', NEW.left_photo_id USING ERRCODE = '23503';
  END IF;

  SELECT content_sha256, attribution_id INTO v_right_sha, v_right_att
    FROM product_issue_photo WHERE id = NEW.right_photo_id AND tenant_id = NEW.tenant_id;
  IF v_right_sha IS NULL THEN
    RAISE EXCEPTION '右侧照片不存在或不属于当前租户：%', NEW.right_photo_id USING ERRCODE = '23503';
  END IF;

  IF v_left_att <> NEW.attribution_id OR v_right_att <> NEW.attribution_id THEN
    RAISE EXCEPTION '比对的两张照片必须都属于同一张归因单' USING ERRCODE = '23514';
  END IF;

  IF (v_left_sha = v_right_sha) AND NEW.compare_result <> 'IDENTITY_SAME' THEN
    RAISE EXCEPTION '两张照片内容指纹相同，比对结果必须是 IDENTITY_SAME' USING ERRCODE = '23514';
  END IF;
  IF (v_left_sha <> v_right_sha) AND NEW.compare_result <> 'IDENTITY_DIFFER' THEN
    RAISE EXCEPTION '两张照片内容指纹不同，比对结果只能是 IDENTITY_DIFFER（本仓不做图像相似度）'
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_guard_product_issue_photo_comparison
  BEFORE INSERT ON product_issue_photo_comparison
  FOR EACH ROW EXECUTE FUNCTION guard_product_issue_photo_comparison();

-- ---------------------------------------------------------------------------
-- 守卫⑤（硬规则 2）：批次标记的统计列**由 DB 重算**，不接受任何传入取值。
-- 服务层按当前生效归因聚合后写入，触发器**独立重算一遍并比对**，不等就拒 ——
-- 「这个批次退回来几件、归到哪些环节」这个式子每一步都能被人手算复核。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_product_issue_lot_flag() RETURNS trigger AS $$
DECLARE
  v_count integer;
  v_qty   numeric(19,6);
  v_breakdown jsonb;
BEGIN
  SELECT count(*), COALESCE(SUM(a.attributed_qty), 0) INTO v_count, v_qty
    FROM product_issue_attribution a
   WHERE a.tenant_id = NEW.tenant_id
     AND a.lot_id = NEW.lot_id
     AND a.attribution_status = 'ATTRIBUTED';

  -- 按环节拆分：jsonb_object_agg + coalesce，让六个环节**全部出现**
  -- （没有命中也要出现并为 0 —— 少列一个环节，界面就显示成空白，
  -- 而空白会被读成「没有这个数据」而不是「真的是 0」）。
  SELECT coalesce(jsonb_object_agg(stage, cnt), '{}'::jsonb) INTO v_breakdown
    FROM (
      SELECT a.attributed_stage AS stage, count(*)::bigint AS cnt
        FROM product_issue_attribution a
       WHERE a.tenant_id = NEW.tenant_id
         AND a.lot_id = NEW.lot_id
         AND a.attribution_status = 'ATTRIBUTED'
       GROUP BY a.attributed_stage
    ) s;

  IF NEW.suspect_return_count IS DISTINCT FROM v_count
     OR NEW.affected_return_qty IS DISTINCT FROM v_qty
     OR NEW.stage_breakdown IS DISTINCT FROM v_breakdown THEN
    RAISE EXCEPTION
      '批次标记统计与当前生效归因不符：传入(count=% , qty=% )，真实(count=% , qty=% )',
      NEW.suspect_return_count, NEW.affected_return_qty, v_count, v_qty
      USING ERRCODE = '23514';
  END IF;

  IF NEW.flag_status IN ('SUSPECT','CONFIRMED') AND v_qty < NEW.min_flag_qty THEN
    RAISE EXCEPTION
      '批次 % 当前生效归因合计只有 % 件，低于口径 % 件，不能标为 %',
      NEW.lot_id, v_qty, NEW.min_flag_qty, NEW.flag_status
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_guard_product_issue_lot_flag
  BEFORE INSERT OR UPDATE ON product_issue_lot_flag
  FOR EACH ROW EXECUTE FUNCTION guard_product_issue_lot_flag();

-- ===========================================================================
-- 十、多租户：RLS + 授权
-- ===========================================================================
-- 五张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：归因结论是要拿去和供应商索赔的**责任判定**，
--   端点大多是**按 id 直达**的 URL —— 猜到一个 UUID 就能读，不需要翻列表。
--   而一个租户能看到另一个租户「这批货判给供应商」，等于拿到了索赔的谈判底牌。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'product_issue_attribution','product_issue_photo',
    'product_issue_photo_comparison','product_issue_lot_flag','product_issue_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  product_issue_attribution, product_issue_photo,
  product_issue_photo_comparison, product_issue_lot_flag, product_issue_event TO trade_app;

-- 授权在前、收回在后。
-- 留痕追加式：归因轨迹是要拿去对质的证据，能被悄悄改掉的证据等于没有证据。
REVOKE UPDATE,DELETE ON product_issue_event FROM trade_app;
-- ⚠️ **刻意不收回 product_issue_photo / product_issue_photo_comparison 的 DELETE**：
--   它们是**草稿态的行级材料**（与 V179 的 return_quality_check_result 同一位置），
--   服务层在归因单仍是 DRAFT 时对它们做整单替换 —— 换两次照片时把上一版抹掉才是对的，
--   否则同一张归因单上会攒着三次登记，「我们拍了几张照」就有三个答案。
--   **草稿态由 requireDraft() 卡住**：一旦出归因结论（终态不可逆），
--   照片与比对都不可再改，那时它们才真的变成只读的举证材料。

-- 本迁移**不 ALTER 任何既有表**：V156 的 aftersales_case、V158 的原因归因三表、
-- V179 的 return_quality_* 簇、V172 的 return_receipt_photo 全部只读。
-- 本模块对它们的全部依赖是 SELECT / JOIN 与「写入时刻查一次」，
-- 跨车道 DDL 回归面 = 0（同批另有三条车道也在往 trade 落迁移）。
-- warehouse 的 inventory_lot 更是在**另一个数据库**里，本迁移连引用都没有。
