-- 3.9.2.1 完整度评分：给每个 SPU/SKU 打主数据完整度分（必填属性、类目必填、图片数量与尺寸、
-- 报关属性、包装尺寸重量、认证），按分数分档，驱动补全清单。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：给每个 SPU/SKU 打主数据完整度分：必填属性、类目必填、图片数量与尺寸、报关属性
--             （HS 编码、申报品名）、包装尺寸重量、认证；按分数分档，驱动补全清单。
--   **输入**：主数据集合；类目必填规则；16 认证状态。
--   **输出**：完整度评分卡 + 缺失项清单 + 批量补全入口。
--   **关系**：上游 = 3.1/3.2/3.7、16 合规；下游 = 4.2.3.2 预检、3.8 批量导入。
--             **完整度直接决定能否刊登与能否清关**，是主数据质量与业务动作的连接点。
--
-- ============================================================ 第一节：六个维度与它们的权重
--
-- 维度不是随便挑的六件事，它们对应**两条互不相干的阻断链**（这是蓝图那句
-- 「完整度直接决定能否刊登与能否清关」的字面兑现）：
--
--   刊登链：必填属性 → 类目必填 → 图片 → 认证
--   清关链：报关属性 → 包装尺寸重量
--
-- 一次评分同时判两条链，所以「刊登就绪」与「清关就绪」可以是两个不同的结论：
-- 一个带图有属性的商品完全可能因为没有 HS 编码而刊登就绪、清关未就绪 ——
-- 这两个结论混成一个"完整度 X%"之后，运营就看不出**自己到底被哪一条链拦住**。
--
-- 权重（服务层 CompletenessScoreRules.DIMENSIONS 是唯一实现，前端 model 与之同源）：
--
--   ATTRIBUTE       0.25   必填属性     刊登链根节点，缺一条属性值就可能拼不出标题与变体
--   MEDIA           0.20   图片         刊登链，平台主图位数与像素下限是硬要求
--   CATEGORY        0.15   类目必填     刊登链，决定这个商品要满足哪套属性要求
--   CUSTOMS         0.15   报关属性     清关链根节点，HS 编码与申报品名缺失 = 报关行退单
--   PACKAGING       0.15   包装尺寸重量  清关链 + 10 物流算体积重的输入
--   CERTIFICATION   0.10   认证         刊登链末节点，多数品类无强制认证，权重刻意压低
--
-- 六项之和精确等于 1.0000：**分档阈值直接用这个 0..1 的比值，不用百分数**，
-- 百分数会在权重改动时让阈值与权重不同源（有人改权重忘了改阈值，分档就整体偏了，
-- 而页面上没有任何异常表现）。
--
-- ============================================================ 第二节：报关属性与认证为什么建在本模块
--
-- 本表的**两个上游事实表在集成头里不存在**，这不是猜的，是逐条核对过的：
--
--   * 台账 3.7.1.1「HS 编码与申报要素」= 未开发，且带着一条明确的阻塞说明
--     （"product_compliance 同为 LIKE product_research INCLUDING ALL 复制，装不了
--     HS 编码与认证要素。须先拆表"）。
--   * 台账 3.7.1.2「目的地报关版本」= 未开发。
--   * `16 合规` 在 backend/services/ 下没有服务（channel/trade/warehouse/finance 均未实现），
--     而 AGENTS.md 明令禁止跨服务读表 / 跨库 FK / 共享视图。
--
-- 蓝图把「报关属性（HS 编码、申报品名）」与「认证」写进**本模块的评分维度**，
-- 所以本模块必须在 catalog 自己的库里拥有这两类数据，否则这两个维度只能恒为缺失，
-- 评分卡就成了假数据（而假分数比没分数危险得多：它看起来是有依据的）。
--
-- 本迁移的选择是**在 catalog 侧建这两张声明表并成为它们的写入所有者**，理由与代价：
--
--   * 代价（必须写清）：3.7.1.1 落地时，这两张表要被它接管或被迁移并入它的表。
--     接管路径见第九节（唯一一条，不留 TODO）。
--   * 不这么做的代价更大：评分维度要么读不到数据（恒缺失 = 假信号），
--     要么跨服务读表（违反模块边界纪律，AGENTS.md 红线）。
--
-- **刻意不建的东西**（与 V28 第一节同一条纪律，逐字）：
--   * 不建目的地版本化报关口径 —— 同一件货在不同目的地申报口径不同，
--     那是 3.7.1.2 的职责；本表是**目的地无关的基线申报要素**。
--   * 不建 UN 编号 / 电池类型 / 危险品类别 —— V28 的 sku_safety_attribute 已有类别标识，
--     再建一份就是第二个写入所有者。
--   * 不建认证类型的受控词表 —— 认证种类是外部标准（CE / FCC / UL / PSE / 3C ...，
--     一百多个且按品类与目的地增删），在这里硬编码一份就等于造出第二个真相源。
--     所以 code 是自由文本 + 格式校验，与 V32 币种「只判格式不判词表」同一取舍。
--
-- ============================================================ 第三节：为什么评分是「现算 + 落快照」两件事
--
-- 蓝图输出是「评分卡 + 缺失项清单」，而**评分本身必须现算，不能读缓存的分数**：
-- 主数据每改一次，缓存分数就错一次，而错分数的后果是"系统说它完整、刊登时被平台驳回" ——
-- 那比不评分更难查。
--
-- 但仍然落一份快照 `completeness_evaluation`，因为下游 4.2.3.2 预检与 3.8 批量导入
-- 要回答的是**过去**的问题："上周三拦住这批刊登的那个分数是几分、缺哪几项？"
-- 现算的结果答不了过去，而"当时为什么被拦住"是复盘与追责唯一需要的数据。
--
-- 快照只读不写回主数据，删掉它不影响任何读出口（评分卡永远是现算的）。
-- 这与 V32「报价是一版一行」的同源理由不同 —— 这里刻意**不**给快照做累计版本链：
-- 完整度是**当前状态**的度量，不是凭证；累计下来的历史只能通过 evaluation 记录回看。
--
-- ============================================================ 第一节（续）：评分标度 NUMERIC(5,4)
--
-- `score numeric(5,4)` 存的是 **0..1 的比值**（0.0000 ~ 1.0000），不是百分数。
-- NUMERIC(5,4) 的整数部分只有 1 位，所以它装不下 100.0000 —— 这是刻意的：
-- 存不下百分数就逼着所有读出口除以 100，百分数与比值混用是这类评分系统最常见的
-- 「看起来对但差 100 倍」的 bug。禁浮点：AGENTS.md 要求一律 BigDecimal + NUMERIC。
--
-- 维度权重同样 numeric(5,4) 且合计 1.0000。权重本身**不落表**（第七节：
-- 评分口径的唯一实现放在 Java），但两处约束保证它不会悄悄配不平：
--   * 服务层 {@code CompletenessScoreRules} 在构造维度表时断言权重合计 == 1，
--     否则启动失败 —— 权重表改漏一项会在**启动**时炸，而不是上线后分数静默偏移；
--   * 评估快照写入时按同一套维度表算出 score，权重与判定口径不可能来自两个版本。

-- ============================================================ 第四节：sku_customs_declaration

CREATE TABLE sku_customs_declaration (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 报关属性挂在 SKU 上而不是 SPU 上（第一节的清关链是逐 SKU 的）：
  -- 同一 SPU 的不同变体可以是完全不同的 HS 编码（同一款衣服的短袖与长袖），
  -- 挂在 SPU 上会让报关行只能报其中一个，另一个静默报错编码。
  sku_id uuid NOT NULL,

  -- HS 编码：海关商品编号。**格式校验但不校验是否在某个词表里**（与 V32 币种同一取舍）：
  -- 各国有自己的 HS 体系，且子目年年调整；在这里存一份"标准 HS 词表"会在第一次改版时
  -- 变成一份永远对不上的死数据。
  -- 存的是**文本而不是整数**：HS 前 6 位是国际段，后面的各国子目长度不等（8/10/13 位），
  -- 且可能带前缀（如欧盟的 10 位 CN 码），用 bigint 会在第 10 位截断或溢出。
  -- 空格/短横线在规范化时去掉（'8471.30' → '847130'），让同一编码只有一种写法。
  hs_code varchar(32) NOT NULL DEFAULT '',

  -- 申报品名：报关单上写的商品名称。**必填语义由评分判，不是 NOT NULL**（第二节），
  -- 与 HS 编码同一个规格的问题：这里只存文本，不校验它与任何平台词库一致。
  declared_name varchar(200) NOT NULL DEFAULT '',

  -- 原产国：ISO 3166-1 alpha-2 两位大写码。跨境申报必填，且它决定协定税率（自贸协定零关税）。
  origin_country varchar(2) NOT NULL DEFAULT '',

  -- 法定计量单位：海关按这个单位计数量，不能填"件/套/箱"这种口语单位。
  -- 刻意<b>不</b>建 unit_of_measure 外键：法定计量单位是海关公布的申报口径（"台""千克"），
  -- 与 3.3.3.1 的经营计量单位（"件""箱"）不是同一套词表，两边迟早会不一样。
  -- 与 V32 币种同一取舍：只做长度与格式校验。
  legal_unit varchar(32) NOT NULL DEFAULT '',

  -- 申报要素：海关要求的成套申报要素（材质、用途、品牌、型号...），按平台/海关口径排列。
  -- 用数组而不是自由文本：申报要素是**有序多值**，塞进一个文本字段之后
  -- 「第十项要素缺失」这个问题无法回答，而那正是本模块评分要报的缺失项。
  declaration_elements text[] NOT NULL DEFAULT ARRAY[]::text[],

  -- 是否为该 SKU 的当前申报口径。刻意<b>不</b>用"已失效"状态列：
  -- 失效与否是相对某个目的地判定的（3.7.1.2 的事），在没有目的地上下文时
  -- 存一个"是否有效"等于凭空造一个永远没人能更新的布尔。
  enabled boolean NOT NULL DEFAULT true,

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个 SKU 只有一份申报口径：两份报关属性时报关行不知道报哪份，
  -- 而症状是"同一个 SKU 两次报关金额不同"，没有任何报错。
  UNIQUE(tenant_id, sku_id),
  FOREIGN KEY(tenant_id, sku_id) REFERENCES sku(tenant_id, id) ON DELETE CASCADE,
  -- 原产国要么没填（空串），要么是两位大写字母。
  CHECK(origin_country = '' OR origin_country ~ '^[A-Z]{2}$'),
  CHECK(btrim(declared_name) <> '' OR btrim(hs_code) <> '' OR origin_country <> ''
        OR cardinality(declaration_elements) > 0 OR NOT enabled)
);

-- 「哪些 SKU 还没登记报关属性」—— 3.9.2.1 评分卡与下游 4.2.3.2 报关预检的主查询。
-- 部分索引只覆盖 enabled=true：停用的行是"历史口径"，不进任何一条评分路径
-- （与 brand.enabled / media_asset.enabled 同一口径）。
CREATE INDEX sku_customs_declaration_by_hs ON sku_customs_declaration(tenant_id, hs_code) WHERE enabled;
CREATE INDEX sku_customs_declaration_enabled ON sku_customs_declaration(tenant_id, sku_id) WHERE enabled;

-- ============================================================ 第五节：sku_certification

CREATE TABLE sku_certification (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 认证挂在 SKU 上（与报关同一理由：一个变体有认证另一个没有是真实情况，
  -- 例如同一款产品的插电版与电池版，FCC/CE 的适用范围不同）。
  sku_id uuid NOT NULL,

  -- 认证种类代码：CE / FCC / UL / PSE / 3C / RoHS / REACH / FDA ...
  -- **自由文本 + 格式校验**，不建受控词表（第二节）：认证种类是外部标准，
  -- 按品类与目的地增删，一百多个；在 catalog 硬编码一份就等于造出第二个真相源。
  certification_code varchar(32) NOT NULL,

  -- 认证名称（展示用）：「欧盟 CE 认证」「美国 FCC 认证」。可空 —— 代码已经能定位，
  -- 名称只是为了让人在清单里读得懂。
  certification_name varchar(100) NOT NULL DEFAULT '',

  -- 状态。**四个取值，不是两个**，而 NOT_REQUIRED 是整套评分里最要紧的一个：
  --
  --   HELD          已取得且在有效期内（这才是"完整"）
  --   PENDING       已申请未取得
  --   EXPIRED       曾取得但已过期（证书是纸质的，会过期）
  --   NOT_REQUIRED  **已确认该 SKU 无需此认证**
  --
  -- 少了 NOT_REQUIRED 的话，「大多数日用商品不需要任何认证」这个事实无法表达，
  -- 于是每一个不需要认证的商品都会永久停留在"认证缺失"，而运营无论怎么补都补不上 ——
  -- 最后整个认证维度变成一片没人看的红色。
  -- 这与 V28「未采集 ≠ 确认都不带」是同一条纪律的两个方向：
  -- 那边是"没填"不能当成"确认没有"，这边是"确认没有"必须是一个能显式记录的状态。
  --
  -- **待判定与已判定必须可区分**：PENDING/EXPIRED 是"有证书这件事但还没搞定"，
  -- NOT_REQUIRED 是"压根没有这回事"。两者的缺失项文案与下一步动作完全不同。
  status text NOT NULL CHECK(status IN ('HELD','PENDING','EXPIRED','NOT_REQUIRED')),

  -- 证书编号：HELD 时必须有（一张"已取得"却没有编号的证书无法被海关与平台核对），
  -- 由服务层 + 触发器钉死（见第六节）。
  certificate_no varchar(100) NOT NULL DEFAULT '',

  -- 有效期：签发日与到期日。EXPIRED / HENDED 之后到期日是有意义的
  -- （"这张证书上个月过期了"与"这张证书 2030 年才过期"是两个不同的补全优先级）。
  -- 到期日可空 = 长期有效（多数 CE 认证不写到期日）。
  issued_on date,
  expires_on date,

  -- 是否计入评分。刻意提供这个开关而不是"只影响不计入"：
  -- 有些认证（如内部质检报告）确实存在，但不该拉低刊登就绪度，
  -- 运营需要能把它留在档案里而不让它变成一条永远补不上的红项。
  -- **默认 true** —— 任何"不影响评分"的默认都会让真实缺失被静默隐藏。
  scored boolean NOT NULL DEFAULT true,

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, sku_id) REFERENCES sku(tenant_id, id) ON DELETE CASCADE,
  CHECK(btrim(certification_code) <> ''),
  CHECK(certification_code = upper(btrim(certification_code))),
  CHECK(expires_on IS NULL OR issued_on IS NULL OR expires_on >= issued_on)
);

-- 同一 SKU 的同一认证种类只能有一条：两条 CE 记录时评分只能说"已取得"，
-- 而运营看到清单上有两条 CE 会以为是两张不同的证书（其实一张过期了），
-- 于是永远不知道该去续哪一张。
CREATE UNIQUE INDEX sku_certification_unique_code ON sku_certification(tenant_id, sku_id, certification_code);

-- 评分与「哪些 SKU 没有有效认证」的主查询：只覆盖 scored=true 的行，
-- 不计入评分的行不进任何一条评分路径（与第三节的 enabled 部分索引同一取舍）。
CREATE INDEX sku_certification_by_sku ON sku_certification(tenant_id, sku_id, status) WHERE scored;
-- 按认证种类统计缺口：「全店 137 个 SKU 缺 CE」是补全清单上一个真实的问题。
CREATE INDEX sku_certification_by_code ON sku_certification(tenant_id, certification_code, status) WHERE scored;

-- HELD 必须有证书编号；NOT_REQUIRED 不能有证书编号与有效期。
-- 「已取得但没有编号」是一行写进去之后只会让人误以为已完成的记录。
CREATE FUNCTION guard_sku_certification_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = 'HELD' AND btrim(NEW.certificate_no) = '' THEN
    RAISE EXCEPTION '认证状态为 HELD（已取得）时必须填写证书编号：一张没有编号的证书无法被海关与平台核对'
      USING ERRCODE='23514';
  END IF;
  IF NEW.status = 'NOT_REQUIRED'
     AND (btrim(NEW.certificate_no) <> '' OR NEW.issued_on IS NOT NULL OR NEW.expires_on IS NOT NULL) THEN
    RAISE EXCEPTION '认证状态为 NOT_REQUIRED（无需认证）时不能有证书编号与有效期：这一行表达的是「压根没有这张证书」'
      USING ERRCODE='23514';
  END IF;
  IF NEW.status <> 'NOT_REQUIRED' AND NEW.issued_on IS NOT NULL AND NEW.expires_on IS NOT NULL
     AND NEW.expires_on < NEW.issued_on THEN
    RAISE EXCEPTION '认证到期日不能早于签发日（% → %）', NEW.issued_on, NEW.expires_on USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_sku_certification_status
  BEFORE INSERT OR UPDATE ON sku_certification FOR EACH ROW EXECUTE FUNCTION guard_sku_certification_status();

-- ============================================================ 第六节：completeness_evaluation —— 评分快照
--
-- 现算的评分卡是**当前状态**；这张表回答的是**过去某一刻**的分数与缺失项，
-- 供 4.2.3.2 预检复盘与 3.8 批量导入回看"当初为什么被拦住"。
--
-- 快照**不参与任何读出口**：评分卡永远是现算的，删掉全部快照后评分功能完全不受影响。
-- 这条边界很重要 —— 一旦让读出口去读快照，删快照就会变成"功能坏了"。

CREATE TABLE completeness_evaluation (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 被评主体。owner_type 只有 SPU 与 SKU 两个取值（与 media_reference.owner_type 同一词表）。
  -- 刻意**不**做单表按 (type,id) 泛化到任意实体：泛化的 owner_id 没有外键，
  -- 悬空引用无法在 DB 层发现，而"评了一个不存在的 SKU"会产出一条看起来正常的快照。
  -- 两张分支各带各的外键，所以悬空在 DB 层不可能（与 media_reference 同一手法）。
  owner_type text NOT NULL CHECK(owner_type IN ('SPU','SKU')),
  spu_id uuid,
  sku_id uuid,

  -- 评分：0..1 的比值，**不是百分数**（第一节续）。
  score numeric(5,4) NOT NULL CHECK(score >= 0 AND score <= 1),

  -- 分档：EXCELLENT / GOOD / FAIR / POOR。存一份而不是现算 ——
  -- 分档阈值以后可能被调整，而历史快照的分档必须保持**当时**的样子
  -- （否则改一次阈值，所有历史快照的分档都跟着变，复盘结论就全错了）。
  grade text NOT NULL CHECK(grade IN ('EXCELLENT','GOOD','FAIR','POOR')),

  -- 两个阻断结论：刊登就绪 / 清关就绪（第一节的两条链）。
  listing_ready boolean NOT NULL,
  customs_ready boolean NOT NULL,

  -- 各维度得分：{dimension: ratio} 的 jsonb。刻意存 jsonb 而不是六行子表 ——
  -- 六行子表会让"读这张快照"变成一次 join，而这份数据的唯一用途是给人看当时的样子。
  -- 维度集合是代码里的封闭词表（CompletenessScoreRules.DIMENSIONS），不是开放集合。
  dimension_scores jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 缺失项快照：[{code,dimension,subjectType,subjectLabel,detail,remedy}] 的 jsonb 数组。
  -- **空数组与 null 不同义**：空数组 = 一条不缺，null = 没算过。
  missing_checks jsonb NOT NULL DEFAULT '[]'::jsonb,
  missing_count int NOT NULL DEFAULT 0 CHECK(missing_count >= 0),

  -- 评估人与评估时刻：租户内的操作者 id + 时刻。快照要能回答"谁在什么时候评的"。
  evaluated_by uuid NOT NULL,
  evaluated_at timestamptz NOT NULL DEFAULT now(),

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id, id),
  -- owner_type 与它那一侧的 id 必须成对出现，且另一侧必须为空：
  -- 半个快照（type='SKU' 但 sku_id 为空）读不出来，而它是可写进来的状态。
  CHECK((owner_type = 'SPU') = (spu_id IS NOT NULL)),
  CHECK((owner_type = 'SKU') = (sku_id IS NOT NULL)),
  FOREIGN KEY(tenant_id, spu_id) REFERENCES spu(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, sku_id) REFERENCES sku(tenant_id, id) ON DELETE CASCADE
);

-- 「这个 SPU 的评分历史」「这个 SKU 最近一次评分」—— 预检复盘与批量对比的主查询。
CREATE INDEX completeness_evaluation_by_spu ON completeness_evaluation(tenant_id, spu_id, evaluated_at DESC) WHERE spu_id IS NOT NULL;
CREATE INDEX completeness_evaluation_by_sku ON completeness_evaluation(tenant_id, sku_id, evaluated_at DESC) WHERE sku_id IS NOT NULL;
-- 「哪些商品还没评过」「刚评出来不达标的那些」—— 治理视图的主查询。
CREATE INDEX completeness_evaluation_by_grade ON completeness_evaluation(tenant_id, grade, evaluated_at DESC);

-- ============================================================ 第七节：为什么不在 DB 层放评分函数
--
-- 六个维度的判定里有一半是**语义判定**而不是字段判空：
--   * 「认证是否完整」= 有 NOT_REQUIRED 行 或 有未过期的 HELD 行；
--   * 「图片尺寸是否达标」= 主图的最短边 >= 阈值 px；
--   * 「必填属性是否有值」= attribute_value.state='SET' 且**至少有一个 item**；
--   * 「类目必填是否有值」= 条件必填（V15 的 condition_attribute_id）要先看前置属性的值。
--
-- 把它写成一个 plpgsql 函数看着"原子"，代价是：评分口径会散成几份。
-- 本迁移把口径**按性质分开，每一份只存在一处**：
--
--   * **维度权重、分档阈值、两个就绪门槛**：唯一实现是 Java 的
--     {@code CompletenessScoreRules}。SQL 片段由服务层**用这些值拼出来**
--     （{@code CompletenessScoreService#weightedScoreExpression}），
--     不是在 SQL 里抄一份常量 —— 改权重时 SQL 自动跟着变，不存在两处不同步。
--   * **单行布尔事实**（属性有没有值、图够不够、包装齐不齐、报关填了没有）：
--     SQL 的 CTE 显式聚合（AGENTS.md 要求显式 SQL，不靠隐式查询）。
--   * **认证四态**（规则 R5）：唯一一个 {@code sku_cert} 聚合，
--     评分卡 / 列表 / 概览 / 缺失清单四条读出口全部读它。
--     Java 侧**刻意不**再判一次（否则会出现"评分卡说已齐全、清单说待续期"）。
--   * **可解释部分**（缺失码、文案、下一步动作）：唯一实现是 Java。
--
-- 而无论怎么分工，**评分本身一律现算**（规则 R1），因为完整度的输入
-- （属性值、图片、报关、认证）每时每刻都在变，缓存的分数一旦过期就是错的分数，
-- 而错分数比没有分数危险得多 —— 它看起来是有依据的。
--
-- 这与 V1 的校验触发器、V28 的危险品 CHECK 是同一套分工：
-- **约束该由 DB 钉的钉在 DB，评分口径按性质各归一处。**
--
-- ============================================================ 第八节：RLS 与授权

DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['sku_customs_declaration','sku_certification','completeness_evaluation'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON sku_customs_declaration TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_customs_declaration TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_certification TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_certification TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON completeness_evaluation TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON completeness_evaluation TO starcon_app;

-- ============================================================ 第九节：交给 3.7.1.1 的接管路径（不是 TODO）
--
-- 3.7.1.1 落地时按下面**唯一**的一条路径走，否则会出现两个写入所有者：
--
--   1. 3.7.1.1 复用本表（`sku_customs_declaration` + `sku_certification` 的**列**不变），
--      只新增它自己的目的地版本化表（3.7.1.2）并在本表上加版本引用列。
--      —— 这是首选，因为本表的列是按蓝图「HS 编码、申报品名」写的，没有多余列。
--   2. 若 3.7.1.1 判定需要更宽的形状（例如它要支持多语种申报品名），
--      则由迁移把本表数据迁到它的新表，本表随之**删除**，
--      3.9.2.1 的评分改读新表（读出口本来就是服务层里唯一的 SQL，改一处）。
--
-- 两条路都**不允许**"两边各存一份、评分读其中一份"：
-- 那会让同一个 HS 编码有两个真相来源，而报关行按哪份报出去取决于读的是哪张表。
--
-- ============================================================ 第十节：本迁移钉死的语义（服务层 CompletenessScoreRules 的对应实现）
--
--   **R1 评分是现算的。** 读出口永远从主数据现算，不读快照（第三节）。
--       快照只回答"过去某一刻"，删掉全部快照不影响评分功能。
--
--   **R2 六维度权重合计 1.0000，分档阈值作用在 0..1 比值上。**
--       阈值：EXCELLENT >= 0.90、GOOD >= 0.75、FAIR >= 0.60、POOR < 0.60（第一节）。
--
--   **R3 两个就绪结论分开判，不合并成一个"完整度 X%"。** 刊登链
--       （ATTRIBUTE/MEDIA/CATEGORY/CERTIFICATION）与清关链（CUSTOMS/PACKAGING）
--       各自要求：总分不低于各自门槛 **且** 该链上每个维度无缺失项。
--       合并的症状是运营"补到 80% 了"却仍被拦住，且看不出是哪条链。
--
--   **R4 无 SKU 的 SPU 在三条 SKU 侧维度上各记一条缺失项（SPU_HAS_NO_SKU），
--       而不是把权重摊给别的维度。** 摊权重会让"一个变体都没有的商品"
--       在属性与图片上拿满分，看起来比它实际的状态好得多。
--
--   **R5 认证的 NOT_REQUIRED 是完整的两种方式之一。** 只有 HELD 且未过期才算 HELD；
--       全部 PENDING 与全部 EXPIRED 各有各的缺失码（区分"还没批下来"与"该续了"）。
--
--   **R6 图片阈值是本模块的默认下限，写进缺失项文案里。** 主图 >= 1 张、
--       图片总数 >= 3、主图最短边 >= 1000px。这些是跨境平台主图的通行下限，
--       不是平台规则的真相（那在 04 刊登），所以它们出现在**缺失说明**里而
--       不是写死在判定里无法看见 —— 运营看到"主图最短边 800px < 1000px"才知道差多少。
--
--   **R7 报关属性与认证挂在 SKU 上，不挂 SPU。** 同一 SPU 的变体可以有不同 HS 编码
--       与不同认证适用范围（第一节、第四节）。
--
--   **R8 枚举只增不改。** grade 与 certification status 都已写进快照，
--       改语义会让历史快照在回看时解释错（与 V32 的 R7 同一纪律）。
--
--   **R9 类目必填只计入 `requirement='REQUIRED'` 的无条件规则，
--       `CONDITIONAL_REQUIRED` 不参与评分。** 这不是漏做，是一条边界：
--       条件必填是**录入期**的表单规则（3.3.1.2 用它生成录入表单 schema 并在保存时校验），
--       而"条件成立时才需要取值"这件事的求值必须复用
--       {@code CategoryAttributeRequirementRules.conditionHolds} 那<b>唯一一份</b>算子实现。
--       把它抄进本模块的评分 SQL 就多出第二份算子语义（第七节否掉的正是这种复制），
--       而两处对 GT/不可比类型的处理迟早会不一致 —— 症状是"表单没标必填、
--       评分却说缺"。被条件性要求的属性本身若在属性定义上勾了 required，
--       仍会被 ATTRIBUTE 维度正常计入，所以真实缺失不会被放过。
--       3.3.1.2 的录入期校验不受本条影响。
