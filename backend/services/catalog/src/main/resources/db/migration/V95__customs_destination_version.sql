-- 3.7.1.2 目的地报关版本：维护同一 SKU 在不同目的地国家的报关差异（不同税号、
-- 不同申报品名、不同认证要求），按目的地版本化，供 10 报关按目的地取数、12 财务算关税成本、
-- 16 合规校验认证要求。
--
-- 蓝图 3.7.1.2 的原话：
--   「职责：维护同一 SKU 在不同目的地国家的报关差异（不同税号、不同申报品名、不同认证要求），
--     按目的地版本化。」
--   「输入：目的地国家；对应报关版本内容；生效区间。」
--   「输出：按目的地查询的有效报关版本。」
--   「关系：上游 = 3.7.1.1；下游 = 10 报关、12 财务（关税成本）、16 合规（认证要求）。
--     **不同目的地必须用不同版本，不能用一套申报资料打通全球**。」
--
-- ============================================================ 零、最后那句粗体是本模块的存在理由
--
-- 「**不同目的地必须用不同版本，不能用一套申报资料打通全球**」不是一句风险提示，
-- 而是一条**结构性要求**。用一套申报资料打通全球的具体形态是：同一个 HS 码申报到
-- 欧盟会被判归类不符、申报到美国会因为 FCC 要求缺失被扣货 —— 而两种后果都由卖家承担
-- （V85 文件头第 9 行：「报关数据错误导致的扣货与罚款由卖家承担」）。
--
-- 所以本文件钉死的是三条：
--
--   1. **目的国不可通配**（规则 B5）：`'*'` 一律拒绝。理由见第三节 B5 的完整论证 ——
--      仓库里确实有 `'*'` 的先例（V85 / V84 / V92），但那三处的 `*` 读作
--      「这次申报可以发往任何国家」，是一个**筛选**语义；本表的目的地是
--      **申报资料本身的口径归属**，一条 `*` 的申报资料正是「用一套打通全球」。
--   2. **没有生效版本就报错，不静默回退到基础属性**（规则 B6 之上的服务层闸）：
--      `GET /catalog/customs-destination-versions/effective` 与 `/customs-declaration/resolve`
--      在该目的地没有生效版本时一律报错。回退到基础属性看起来是「优雅降级」，
--      实际后果是：运营没维护 DE 的版本，系统却拿 CN 的税号申报到德国，
--      而报错永远不会被看到。这条与 V94 第一节「未审核通过一律 409」是同一条纪律。
--   3. **覆盖与继承的边界写死在代码与注释里**（第二节）。目的地版本覆盖**税号与品名**
--      两项，**法定计量单位与申报要素不覆盖**，享惠要两边都满足。
--
-- ============================================================ 一、两张表：版本与认证要求
--
-- `customs_destination_version` —— 一个 SKU 在一个目的地国家的一个版本。
-- UNIQUE(tenant_id,sku_id,destination_country_code,version_no) 与 V25 的
-- `UNIQUE(tenant_id,bom_id,version_no)` 同一纪律：同 SKU 同目的地内版本号唯一，
-- 两行时「v2 是哪一个」无法回答，而区间判定会两边都算数。
--
-- ⚠️ 与 `customs_attribute` 的关系是**外键而不是软引用**：蓝图把 3.7.1.1 写成
-- 「上游 = 3.7.1.1」，而「目的地版本挂在一个不存在的基础属性上」这一行既无法回答
-- 「那它的税号差异相对谁」，也会在 3.7.1.1 改完之后静默变成一份没有依据的覆盖值。
-- 外键 + ON DELETE CASCADE 让「基础属性删了，这些覆盖值一起消失」成为结构性事实。
--
-- `customs_destination_requirement` —— 该目的地版本的认证要求
-- （CE / FCC / RoHS / UKCA / 危包证 …），UNIQUE(tenant_id,destination_version_id,
-- requirement_code) 挡住同一版本里同一个认证码两行（两行时「这个目的地要不要 CE」
-- 无法回答，而 16 合规与清关仓会拿到两个互相矛盾的答案）。
--
-- ============================================================ 二、覆盖 vs 继承（合成规则，本模块最要紧的一条）
--
-- 基础属性（V94）提供**通用部分**，目的地版本提供**差异部分**。合成规则逐条钉死：
--
--   * **目的地税号**：目的地版本非空则用目的地版本，否则用基础 `hs_code`。
--   * **申报品名**：目的地版本非空则用目的地版本，否则用基础 `declared_name`。
--   * **法定计量单位**：**只能来自基础属性**，目的地版本不覆盖。理由：法定单位是
--     申报口径的一部分，但它的差异是「同一商品在不同法域的法定单位不同」，
--     而那正是**基础属性这一层的维护范围**（V94 的职责句里它就是基础事实）。
--   * **申报要素**：**只能来自基础属性**，目的地版本**不复制**它。
--     **这是本条最重要的一句**：各国海关的申报要素体系确实不同，但要素的**有序清单**
--     是 3.7.1.1 的事实（V94 第一节已经论证过「有序行是事实，拍平只发生在响应上」）。
--     在本表复制一份要素 = 出现第二个事实来源，而两个来源不一致时
--     「10 报关按哪份申报」变成没人能回答的问题 —— 那正是 V94 要消灭的毛病。
--     所以本表**没有** declaration_elements 列，`/customs-declaration/resolve`
--     的申报要素永远从基础属性读。
--   * **享惠**：基础属性说「有资格」（`preferential_origin_eligible`），
--     目的地版本说「该目的地承认与否」（`preferential_applicable`），
--     **两者都满足才享惠**。注意这两个布尔语义不同：前者是原产地资格，
--     后者是目的地是否承认；把它们合并成一列就答不出「有资格但这个目的地不认」
--     这个真实存在的状态（例：符合 RCEP 原产地规则，但目的国未签署该协定）。
--
-- **为什么不落派生值**（照 V94 零之三的纪律）：合成后的申报资料与关税成本一律现算不落表。
-- 完税价格 = 成交价 × 汇率 × 税率，而**成交价是 10 报关的事实、汇率与税率是 12 财务的
-- 事实**；V85 第 41–44 行专门为汇率保留了精度刻度，正是因为「当时按哪个汇率报的」
-- 要能举证。落一列等于替两个服务选定口径，而它们不一致时错的是报关单。
--
-- ============================================================ 三、生效区间裁决规则 R1–R5（沿用 V25，不改判）
--
-- 时间语义与 BOM 版本**完全一致**（「某一时刻该用哪一版」），所以 R1–R5 逐字沿用
-- V25 文件头第二节：
--
--   **R1（半开区间）**：[effective_from, effective_to)，to 为 NULL = +∞。
--     [10:00,12:00) 与 [12:00,14:00) 在 12:00 处**不**重叠。
--   **R2（重叠一律拒绝，不做裁决）**：同一 SKU 同一目的地下任意两条**已发布**版本
--     区间不得重叠。冲突时报错点名与哪一版（version_no + 目的地 + 区间）撞了。
--   **R3（只有已发布版本参与判定）**：草稿区间随便重叠，发布那一刻才检查。
--   **R4（当前生效版本的确定性查询）**：
--     published AND from <= t AND (to IS NULL OR t < to)，由 R2 保证至多命中一行。
--   **R5（已发布版本只能向前关闭）**：只允许把 effective_to 从 NULL（或较晚值）
--     收紧到一个**仍在未来**的时刻，不允许改 effective_from。
--
-- 本文件用 C1–C6 标记本模块自己的不变量，编号**不复用** A/B/C 之外的字母，
-- 免得与 V94 的 A1–A5、V28 的 B1–B3 在读代码时混掉。

-- ============================================================ 四、库层强制：CHECK + 触发器双防线
--
-- CHECK 可以被 `ALTER TABLE ... DROP CONSTRAINT` 或迁移脚本绕过，触发器不行
-- （与 V21 / V22 / V23 / V25 / V28 / V94 同一纪律）。所以每条不变量都钉两遍，
-- 触发器那一遍负责给**可读的中文报错**（照 V28 的 guard_sku_package_weights 风格：
-- 说清后果，而不是只报一个约束名 —— 运营看到的是「这条会被退单」而不是「23514」）。
--
--   * **B1 区间重叠拒绝**（跨行，只能触发器）：`guard_destination_version_interval`。
--   * **B2 发布态一致性**（行内，CHECK + 触发器都能做，钉两遍）。
--   * **B3 已发布只能向前关闭**（跨列，触发器）：`guard_destination_version_published`。
--   * **B4 目的地税号格式**（行内，CHECK + 触发器）：6–10 位纯数字，与 3.7.1.1 的
--     A1 同口径。**允许为空串**（空 = 沿用基础 HS 编码），非空时才校验。
--   * **B5 目的国不可通配**（行内，CHECK + 触发器）：见下。
--   * **B6 基础属性必须存在**（外键 + 触发器 + 服务层）：见下。
CREATE TABLE customs_destination_version (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
  -- ===== 与 3.7.1.1 的关系 =====
  -- **外键而不是软引用**（第一节的论证）：基础报关属性是本表的根，
  -- 基础属性删了这些覆盖值一起消失。ON DELETE CASCADE。
  customs_attribute_id uuid NOT NULL,
  -- ===== 目的地 =====
  -- 目的地国家。ISO 3166-1 alpha-2，两位大写字母。
  -- **用 varchar(2) 而不是 char(2)**：PostgreSQL 的 char(n) 是**空白填充**类型，
  -- 值会在比较/拼接时带上尾随空格，触发 CHECK 或唯一约束时报出来的错极难排查
  -- （看到的值是 'CN ' 而不是 'CN'）。全仓库既有口径统一是 varchar(2)：
  -- V80 / V85 / V90 / V91 的 origin_country_code 与 destination_country_code
  -- 都是 varchar(2)，本列跟随。
  destination_country_code varchar(2) NOT NULL,
  -- 版本号。同 SKU 同目的地内唯一（运营手打的「2026 春季税则」「UKCA 版」），
  -- **建后不可改**（触发器钉死，与 V25 的 version_no 同一纪律）。
  version_no integer NOT NULL CHECK(version_no>0),
  -- ===== 覆盖字段（第二节的合成规则）=====
  -- 目的地税号。**可能不同于基础 HS 编码** —— 这正是本模块存在的理由
  -- （各国在 6 位 HS 之上有自己的 8 位税则号）。
  -- **默认空串而不是必填**：空 = 沿用基础 HS 编码（第二节的「否则用基础」）。
  -- 本表所有「文本类」列一律用「空串 = 无」而不是 NULL
  -- （V94 的 preference_agreement / V28 的 dangerous_substance_class 同一口径），
  -- 免得下游每读一列都要先判一次 null。
  destination_hs_code varchar(16) NOT NULL DEFAULT '',
  -- 目的地申报品名。**不照搬销售标题**（V85 第 207–208 行：申报口径不照搬销售标题，
  -- 混用是常见被退单原因）。空 = 沿用基础申报品名。
  destination_declared_name text NOT NULL DEFAULT '',
  -- **该目的地是否承认这个优惠**。注意它与 3.7.1.1 的 `preferential_origin_eligible`
  -- 语义不同：那是「原产地资格」，这是「该目的地承认与否」（第二节末段）。
  preferential_applicable boolean NOT NULL DEFAULT false,
  -- ===== 审核状态机（照 V25：草稿 → 发布，单向）=====
  -- 撤回发布不是合法动作（触发器在第五节钉死）：10 报关引用的申报资料一旦发布，
  -- 撤回会让「当时报的是什么」无从查证。
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),
  -- 发布留痕。与 V25 / V94 的 published_by / published_at、reviewed_by / reviewed_at 同一形状。
  published_by uuid, published_at timestamptz,
  -- ===== 生效区间 [effective_from, effective_to)（规则 R1）=====
  -- 业务时间（税则版本换版是有时间的：EU 每 5 年一换税则、美国 HTSU 每年调整）。
  effective_from timestamptz NOT NULL,
  -- NULL = 一直有效（+∞）。
  effective_to timestamptz,
  -- 乐观锁凭据。与 sku_package / customs_attribute 同一套口径：
  -- 并发编辑两个人都提交时，后到的那个必须撞 409 而不是静默覆盖先到的差异声明。
  version bigint NOT NULL DEFAULT 0,
  -- note 是运营补充说明（例：DE 税号已与报关行确认），**不参与任何判定**：
  -- 让自由文本进判定条件，就等于给了一条可以绕过发布流程的旁路。
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同 SKU 同目的地同版本号只能有一行：两行时「v2 是哪一个」无法回答，
  -- 而区间判定（规则 R2）会两边都算数。
  UNIQUE(tenant_id,sku_id,destination_country_code,version_no),

  -- 空区间没有意义（等于「从不生效」）。左闭右开，所以 to 必须严格大于 from。
  CONSTRAINT customs_destination_version_range_ordered
    CHECK (effective_to IS NULL OR effective_to > effective_from),

  -- ---- 规则 B4：目的地税号格式（非空时 6–10 位纯数字）----
  -- 与 3.7.1.1 的 A1 同口径。**允许空串**是这条与 A1 唯一的差别，
  -- 因为空串在这里是「沿用基础 HS 编码」而不是「没填」（第二节）。
  CONSTRAINT customs_destination_version_hs_code_format
    CHECK (destination_hs_code='' OR destination_hs_code ~ '^[0-9]{6,10}$'),

  -- ---- 规则 B5：目的国不可通配 ----
  -- **`'*'` 一律拒绝**。这不是「格式收紧」而是本模块的结构性要求，
  -- 完整论证见第五节 guard_destination_version 的 B5 分支。
  CONSTRAINT customs_destination_version_no_wildcard
    CHECK (destination_country_code<>'*'),
  -- 顺带把 ISO 3166-1 alpha-2 的形状也钉住：两位大写字母。
  -- 与 3.7.1.1 的 origin 格式 CHECK 同一口径，但**没有**默认值 ——
  -- 目的地是本表的业务主键之一，缺了它这一行就没有意义。
  CONSTRAINT customs_destination_version_country_format
    CHECK (destination_country_code ~ '^[A-Z]{2}$'),

  -- ---- 规则 B2：发布态一致性（两个方向都钉）----
  -- PUBLISHED ⟹ 有发布人、有发布时刻。没有发布人的「已发布」等于
  -- 「有人按过发布」这种无法证伪的说法，而这份资料会被 10 报关原样引用。
  CONSTRAINT customs_destination_version_published_needs_actor
    CHECK (status<>'PUBLISHED' OR (published_by IS NOT NULL AND published_at IS NOT NULL)),
  -- DRAFT ⟹ 无发布痕迹。带发布痕迹的 DRAFT 会让「哪些还没发布」这个查询失真：
  -- 运营看到一堆带发布时间的草稿，会以为它们已经在下游生效了。
  CONSTRAINT customs_destination_version_draft_has_no_release
    CHECK (status<>'DRAFT' OR (published_by IS NULL AND published_at IS NULL)),

  -- 覆盖字段的长度上限。申报品名会原样出现在报关单上，截断成另一个商品比报错糟得多。
  CONSTRAINT customs_destination_version_declared_name_length
    CHECK (length(destination_declared_name)<=500 AND length(note)<=1000),

  -- SKU 被删则目的地版本一起删：留下孤儿行会让筛选查询算出一批
  -- 「永远不能报关」的 SKU，而没人知道它们对应的是哪个已不存在的商品。
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE,
  -- **规则 B6 的外键那一半**：基础报关属性必须真实存在（复合外键，保证同租户）。
  -- 另一半（这一行指向的属性确实是这个 SKU 的属性）是跨行不变量，
  -- CHECK 做不到，由 guard_destination_version 的 B6 分支用触发器钉。
  FOREIGN KEY(tenant_id,customs_attribute_id)
    REFERENCES customs_attribute(tenant_id,id) ON DELETE CASCADE
);

CREATE TABLE customs_destination_requirement (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, destination_version_id uuid NOT NULL,
  -- 认证代码（例 CE / FCC / RoHS / UKCA / UN38.3）。varchar(64) 而不是更短：
  -- 危包证这类代码带版本号与分隔符（如 `UN38.3`），收窄成固定宽度会拒掉一部分真实认证。
  requirement_code varchar(64) NOT NULL,
  -- 中文名称（例「欧盟 CE 认证」）。**允许为空串**：代码本身就是法定标识，
  -- 名称只是给人看的；为了让中文翻译可维护而强制必填，会逼运营编一个英文缩写当名字。
  requirement_name varchar(200) NOT NULL DEFAULT '',
  -- 是否强制。**required=false 不是「不需要」而是「可选」**：
  -- 「已确认不需要」与「不知道要不要」在报关单上是两个不同状态，
  -- 16 合规要能分开渲染，所以这是一个显式布尔而不是「没有这一行」。
  required boolean NOT NULL DEFAULT true,
  -- 备注（例：证书编号 / 发证机构 / 有效期）。不参与判定。
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一版本里同一个认证码只能有一行：两行时「这个目的地要不要 CE」无法回答，
  -- 而 16 合规与清关仓会拿到两个互相矛盾的答案。
  UNIQUE(tenant_id,destination_version_id,requirement_code),
  CONSTRAINT customs_destination_requirement_code_not_blank
    CHECK (btrim(requirement_code)<>''),
  CONSTRAINT customs_destination_requirement_name_length
    CHECK (length(requirement_name)<=200 AND length(note)<=500),
  -- 版本被删则要求一起删（不留孤儿要求）。
  FOREIGN KEY(tenant_id,destination_version_id)
    REFERENCES customs_destination_version(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 五、触发器兜底（纵深防线）
--
-- **guard_destination_version**（BEFORE INSERT OR UPDATE ON customs_destination_version）
-- 把 B4 / B5 / B6 与发布态（B2）再钉一遍，并给可读的中文报错
-- （照 V28 的 guard_sku_package_weights / V94 的 guard_customs_attribute_review 风格）。

-- ⚠️ **规则 B5 的完整论证：为什么这里绝对不接受 `'*'`**：
-- 仓库里确实存在用 `'*'` 表示「不限目的地」的先例：
--   * V85 第 133 行 `destination_country_code varchar(2) NOT NULL DEFAULT '*'`
--     （报关单：这次申报可以发往任何国家）
--   * V84 的 `country_code ... DEFAULT '*'`（在途）
--   * V92 的 `destination_country_code ... DEFAULT '*'`（路由）
-- **照搬 `'*'` 会把 3.7.1.2 要消灭的东西用一个「看起来合法」的默认值重新引入，
-- 而且它在页面上完全正常** —— 运营看到「不限目的地」只会觉得方便。
-- 那三处的 `*` 是**筛选条件**：它回答「这一单发到哪里」，
-- 而本表的目的地是**申报资料本身的口径归属**，回答的是「发到 DE 该报哪个税号」。
-- 前者是「订单事实」，后者是「申报事实」；把订单事实的通配语法搬进申报事实，
-- 一次就足以让一份 CN 口径的资料被申报到德国 —— 后果由卖家承担（V85 第 9 行）。
CREATE FUNCTION guard_destination_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE attribute_sku uuid; attribute_status text;
BEGIN
 -- 规则 B5 的可读版本。放在最前面：目的国错了，后面几条的报错都指错了地方。
 IF NEW.destination_country_code='*' THEN
  RAISE EXCEPTION '目的地不能写成「*」（不限目的地）：不同目的地必须用不同版本，
   不能用一套申报资料打通全球 —— 同一份资料申报到欧盟会因归类不符被退单，申报到美国会因 FCC 要求缺失被扣货，
   而扣货与罚款由卖家承担。请为这个目的地单独建一个版本（例如 DE 一个、US 一个）' USING ERRCODE='23514';
 END IF;
 IF NEW.destination_country_code !~ '^[A-Z]{2}$' THEN
  RAISE EXCEPTION '目的地「%」不是两位大写字母的 ISO 3166-1 alpha-2 代码（如 DE / US / GB）：
   目的地决定了这一版用哪个税号、哪个申报品名与哪套认证要求，填错等于把某个国家的申报资料报给了另一个国家',
   NEW.destination_country_code USING ERRCODE='23514';
 END IF;
 -- 规则 B4 的可读版本。空串不报错：空 = 沿用基础 HS 编码（第二节的合成规则）。
 IF NEW.destination_hs_code<>'' AND NEW.destination_hs_code !~ '^[0-9]{6,10}$' THEN
  RAISE EXCEPTION '目的地税号「%」不是 6–10 位纯数字（如 851712 或 8517123090）：
   带字母或位数不对的是别的税则体系的编号，不是这一列该装的编码；错码会直接导致申报失败与罚款，而扣货与罚款由卖家承担',
   NEW.destination_hs_code USING ERRCODE='23514';
 END IF;
 -- 规则 B6 的另一半：这一行指向的基础报关属性必须是**这个 SKU** 的属性。
 -- 复合外键只能保证「属性存在且同租户」，保证不了「属性属于这个 SKU」——
 -- 那是跨行不变量，CHECK 天然做不到（与 V94 规则 A4「审核前要素齐备」同一形状）。
 SELECT sku_id, status INTO attribute_sku, attribute_status
   FROM customs_attribute
  WHERE tenant_id=NEW.tenant_id AND id=NEW.customs_attribute_id;
 IF attribute_sku IS NULL THEN
  RAISE EXCEPTION '这个 SKU 还没有基础报关属性（3.7.1.1），先在「HS 编码与申报要素」里维护并保存：
   目的地版本是在基础属性之上声明差异，没有基础属性就说不上「差异相对什么」' USING ERRCODE='23514';
 END IF;
 IF attribute_sku<>NEW.sku_id THEN
  RAISE EXCEPTION '目的地版本挂错了基础报关属性：这一行属于 SKU %，而指向的属性属于另一个 SKU。
   两个 SKU 的申报事实混在一起会让「这个目的地该报哪个税号」无法回答',
   NEW.sku_id USING ERRCODE='23514';
 END IF;
 -- 规则 B2 的可读版本（CHECK 已经钉过，这里只负责说人话）。
 IF NEW.status='PUBLISHED' AND (NEW.published_by IS NULL OR NEW.published_at IS NULL) THEN
  RAISE EXCEPTION '发布必须同时记录发布人与发布时刻：没有发布人的「已发布」等于「有人按过发布」这种无法证伪的说法，
   而这份资料会被 10 报关原样引用，报关单上出问题时要能查到是谁发布的' USING ERRCODE='23514';
 END IF;
 IF NEW.status='DRAFT' AND (NEW.published_by IS NOT NULL OR NEW.published_at IS NOT NULL) THEN
  RAISE EXCEPTION '草稿不能带发布痕迹（当前 published_by=% / published_at=%）：带发布痕迹的草稿会让「哪些还没发布」这个查询失真，
   运营看到一堆带发布时间的草稿会以为它们已经在下游生效了', NEW.published_by, NEW.published_at USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_destination_version BEFORE INSERT OR UPDATE ON customs_destination_version
  FOR EACH ROW EXECUTE FUNCTION guard_destination_version();

-- **guard_destination_version_published**（BEFORE UPDATE ON customs_destination_version）
--
-- 已发布版本的逐列不可变：改版本号 / 改目的地 / 改归属 SKU / 改生效起点 /
-- 改**覆盖内容（税号、品名、享惠承认）** / 改发布留痕 / 改备注 一律拒绝；
-- effective_to 只允许按规则 R5 向前关闭。
--
-- 为什么把「覆盖内容」也冻住（V25 冻的是名称与用量，本模块多冻三列）：
-- 已发布版本是 10 报关**正在引用**的那一份。它被原地改掉，等于让下游拿着
-- 一份与发布结论不一致的资料去申报 —— 那正是 V94「已审核通过的属性不可直接改」
-- 要防的事，同一条纪律换个判定键而已。
--
-- 名字按字母序：guard_destination_version（基线校验）在 published 之前。
CREATE FUNCTION guard_destination_version_published() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'PUBLISHED' THEN RETURN NEW; END IF;

 -- 已发布 → 别的状态不允许：发布是单向的。
 IF NEW.status<>'PUBLISHED' THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能撤回发布（目的地 % 版本 %）：10 报关已按这一版生成了申报资料，
   撤回会让「这批货当时按哪个税号、哪套认证要求报的」无从查证。要停止使用请让下一个版本接替它',
   OLD.destination_country_code, OLD.version_no USING ERRCODE='23514';
 END IF;

 IF NEW.destination_country_code IS DISTINCT FROM OLD.destination_country_code THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能改目的地（% → %）：目的地是这一版税号、品名与认证要求的归属，
   换目的地等于让这一版变成另一个国家的资料。要换请新建版本',
   OLD.destination_country_code, NEW.destination_country_code USING ERRCODE='23514';
 END IF;
 IF NEW.version_no IS DISTINCT FROM OLD.version_no THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能改版本号（% → %）：版本号是同 SKU 同目的地内的唯一标识，
   改它会让「v2 是哪一个」在历史数据里出现两个答案。要换请新建版本',
   OLD.version_no, NEW.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.sku_id IS DISTINCT FROM OLD.sku_id THEN
  RAISE EXCEPTION '已发布的目的地报关版本（目的地 % 版本 %）不能改归属 SKU：
   SKU 是这一版差异声明的主体，换 SKU 等于把 A 品的差异套到 B 品上',
   OLD.destination_country_code, OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.customs_attribute_id IS DISTINCT FROM OLD.customs_attribute_id THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能改挂接的基础报关属性（版本 %）：这一版的全部覆盖值都是相对那个基础属性声明的差异，
   换基础属性等于让差异失去参照', OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能改生效起点（版本 %）：移动起点会追溯改写历史上某个时刻该用哪一版，
   而报关单是按当时的版本生成的', OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.destination_hs_code IS DISTINCT FROM OLD.destination_hs_code THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能改目的地税号（版本 %：% → %）：10 报关正在引用这一版的税号，
   原地改掉等于让下游拿着与发布结论不一致的资料去申报，而税号错误导致的罚款由卖家承担。要改请新建一个版本并让新版本接替',
   OLD.version_no, OLD.destination_hs_code, NEW.destination_hs_code USING ERRCODE='23514';
 END IF;
 IF NEW.destination_declared_name IS DISTINCT FROM OLD.destination_declared_name THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能改目的地申报品名（版本 %）：申报品名原样出现在报关单上，
   已发布版本的品名是清关时对外申报过的内容，事后改掉会让「当时报的是什么」无法查证。要改请新建版本',
   OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.preferential_applicable IS DISTINCT FROM OLD.preferential_applicable THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能改享惠承认（版本 %）：这一格决定 12 财务按协定税率还是最惠国税率算关税成本，
   事后改掉会让「这批货当时按哪种税率缴的税」无从查证。要改请新建版本', OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.published_by IS DISTINCT FROM OLD.published_by
    OR NEW.published_at IS DISTINCT FROM OLD.published_at THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能改发布留痕（版本 %）：「谁在什么时候发布的」是这一版可追溯的凭据，
   改掉它等于让发布这件事无法证伪', OLD.version_no USING ERRCODE='23514';
 END IF;
 IF NEW.note IS DISTINCT FROM OLD.note THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能改备注（版本 %）：备注写在发布内容里，发布后与税号、品名一样不可变。
   要补充说明请新建版本', OLD.version_no USING ERRCODE='23514';
 END IF;

 -- effective_to 是唯一的例外，且只允许向前关闭（规则 R5）。
 IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
  IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '已发布的目的地报关版本不能重开生效区间（版本 %）：重新变成「一直有效」会让已经接替它的版本与它同时生效，
   规则 R2（重叠一律拒绝）立刻被破坏，而「这个时刻该报哪一版」将不再唯一',
    OLD.version_no USING ERRCODE='23514';
  END IF;
  IF OLD.effective_to IS NOT NULL AND NEW.effective_to > OLD.effective_to THEN
   RAISE EXCEPTION '已发布的目的地报关版本不能把生效终点往后延（版本 %，% → %）：往后延会与已经接替它的版本重叠，
   「这个时刻该报哪一版」将不再唯一', OLD.version_no, OLD.effective_to, NEW.effective_to USING ERRCODE='23514';
  END IF;
  IF NEW.effective_to <= now() THEN
   RAISE EXCEPTION '已发布的目的地报关版本不能把生效终点设成已经过去的时刻（版本 %，%）：那会追溯改写历史上某个时刻该用哪一版，
   而报关单是按当时的版本生成的', OLD.version_no, NEW.effective_to USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_destination_version_published BEFORE UPDATE ON customs_destination_version
  FOR EACH ROW EXECUTE FUNCTION guard_destination_version_published();

-- 已发布版本不可删（连同删基础属性 / 删 SKU 时的级联一起拦住）。
-- 与 V25 的 refuse_bom_version_published_delete 同一形状：本模块同样**没有 DELETE 端点**
-- （见第七节 A5 的论证），但裸 SQL 的 DELETE 也要被拦。
CREATE FUNCTION refuse_destination_version_published_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='PUBLISHED' THEN
  RAISE EXCEPTION '已发布的目的地报关版本不能删除（目的地 % 版本 %）：10 报关已按这一版生成了申报资料，
   删掉这一版会让「这批货当时按哪个税号、哪套认证要求报的」无从查证。要停止使用请让下一个版本接替它，而不是删版本',
   OLD.destination_country_code, OLD.version_no USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_destination_version_published_delete BEFORE DELETE ON customs_destination_version
  FOR EACH ROW EXECUTE FUNCTION refuse_destination_version_published_delete();

-- **guard_destination_requirement_published**
-- （BEFORE INSERT OR UPDATE OR DELETE ON customs_destination_requirement）：
-- 已发布版本的认证要求不可再动。
--
-- 沿用 V25 的 guard_bom_component_published 形状（只是判定键换成 status='PUBLISHED'），
-- 并补上 V94 的 guard_customs_element_under_review 已经论证过的那一条：
-- **连 DELETE 一起拦**。只拦增改的话，「把全部要求删掉」这条路径是敞开的 ——
-- 而认证要求正是在清关时被查验的那一项，删光之后这一版会变成
-- 「已发布但声称不需要任何认证」，货到港被扣时连「当时是不是真的不需要」都答不上来。
CREATE FUNCTION guard_destination_requirement_published() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text; parent_country text; parent_no integer;
BEGIN
 SELECT status, destination_country_code, version_no INTO parent_status, parent_country, parent_no
   FROM customs_destination_version
  WHERE tenant_id=COALESCE(NEW.tenant_id, OLD.tenant_id) AND id=COALESCE(NEW.destination_version_id, OLD.destination_version_id);
 IF parent_status='PUBLISHED' THEN
  RAISE EXCEPTION '已发布版本的认证要求不可修改（目的地 % 版本 %）：清关时按这一版的认证要求查验申报，
   事后增删改等于让「当时凭哪张证书清的关」无从查证。要改请新建一个版本并让新版本接替',
   parent_country, parent_no USING ERRCODE='23514';
 END IF;
 -- 非 PUBLISHED 的删除交给 ON DELETE CASCADE，本行不做额外判断。
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_destination_requirement_published
  BEFORE INSERT OR UPDATE OR DELETE ON customs_destination_requirement
  FOR EACH ROW EXECUTE FUNCTION guard_destination_requirement_published();

-- 区间重叠拒绝（规则 B2 这里的编号指的是「R2 重叠一律拒绝」，与不变量 B2 发布态无关）。
-- 只有已发布版本参与判定（规则 R3），所以草稿之间随便重叠，发布的那一刻才被检查。
CREATE FUNCTION guard_destination_version_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE clash record;
BEGIN
 IF NEW.status<>'PUBLISHED' THEN RETURN NEW; END IF;
 SELECT v.version_no, v.effective_from, v.effective_to INTO clash
   FROM customs_destination_version v
  WHERE v.tenant_id=NEW.tenant_id
    AND v.sku_id=NEW.sku_id
    AND v.destination_country_code=NEW.destination_country_code
    AND v.id<>NEW.id
    AND v.status='PUBLISHED'
    -- 半开区间比较：'[)' 让 [10:00,12:00) 与 [12:00,14:00) 在 12:00 处不重叠（规则 R1）。
    AND tstzrange(v.effective_from, v.effective_to, '[)')
        && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ORDER BY v.effective_from, v.id
  LIMIT 1;
 IF FOUND THEN
  RAISE EXCEPTION '生效区间与该目的地已发布版本 % 冲突（目的 % 本版本 %：% → %）：同一个 SKU 在同一个目的地同一时刻只能有一个生效版本，
   否则「这批货发到 % 该报哪一版」不再唯一，而申报资料的错误会由卖家承担。
   已发布版本的区间不能改，请调整本版本的区间或把接替版本的终点设为版本 % 的起点',
   clash.version_no, NEW.destination_country_code, NEW.version_no, NEW.effective_from,
   coalesce(NEW.effective_to::text, '一直有效'), NEW.destination_country_code, clash.version_no USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_destination_version_interval BEFORE INSERT OR UPDATE ON customs_destination_version
  FOR EACH ROW EXECUTE FUNCTION guard_destination_version_interval();

-- ============================================================ 六、索引（每条都对应一个真实的查询路径）
--
-- **本模块最高频的查询是「按 SKU + 目的地取版本列表」**（工作区默认视图）
-- 与「按时刻取生效版本」（规则 R4，10 报关与 12 财务走的就是它）。
CREATE INDEX customs_destination_version_by_sku_destination
  ON customs_destination_version(tenant_id, sku_id, destination_country_code, version_no DESC);
-- **规则 R4 的确定性查询形状**：published AND from <= t AND (to IS NULL OR t < to)，
-- 由 R2 保证至多命中一行。10 报关清关、12 财务算关税成本都走它。
CREATE INDEX customs_destination_version_effective
  ON customs_destination_version(tenant_id, sku_id, destination_country_code, effective_from, effective_to)
  WHERE status='PUBLISHED';
-- 倒排到基础属性：基础属性详情页要问「这一份基础资料上有几个目的地的差异声明」，
-- 而 V94 的档案页正是按 sku_id 读的 —— 这一条是那条查询的索引侧。
CREATE INDEX customs_destination_version_by_attribute
  ON customs_destination_version(tenant_id, customs_attribute_id, destination_country_code);
-- 工作台默认视图：草稿优先、改动最近的在上。
CREATE INDEX customs_destination_version_updated
  ON customs_destination_version(tenant_id, updated_at DESC, id DESC);
-- 已发布但「还一直有效」的那些 —— 运营要盯着它们接替（税务换版时的主要工作量）。
CREATE INDEX customs_destination_version_open_ended
  ON customs_destination_version(tenant_id, sku_id, destination_country_code, effective_from)
  WHERE status='PUBLISHED' AND effective_to IS NULL;
-- 16 合规的取数形状：某个认证码被哪些目的地版本要求过（例：全库哪些货要 FCC）。
CREATE INDEX customs_destination_version_by_destination
  ON customs_destination_version(tenant_id, destination_country_code, sku_id);
-- 版本详情页的认证要求清单（工作区编辑一个草稿版本时读它）。
CREATE INDEX customs_destination_requirement_by_version
  ON customs_destination_requirement(tenant_id, destination_version_id, requirement_code);
-- 倒排：某个认证码在哪些版本里被要求过（16 合规的认证台账走它）。
CREATE INDEX customs_destination_requirement_by_code
  ON customs_destination_requirement(tenant_id, requirement_code, destination_version_id);

-- ============================================================ 七、RLS 与授权
--
-- 与 V5 / V6 / V28 / V94 同一口径：租户级数据（目的地报关版本是租户共享的商品申报事实，
-- 不套 V3 的 owner_id / department_id 数据范围）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['customs_destination_version','customs_destination_requirement'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
  -- 可见性跟随**基础报关属性**（进而跟随 SKU）：看不到基础属性就看不到它的差异声明。
  -- 为什么不是直接跟随 SKU：基础属性是这批差异声明的根，跟随它多一层的好处是
  -- 「按 HS 编码筛选」这类枚举口在这里被同一条规则挡住 ——
  -- 能看到某个 SKU 的基础资料就已经是可见性允许的，看到它的目的地差异不该有额外门槛。
  CREATE POLICY data_scope ON customs_destination_version AS RESTRICTIVE
    USING (EXISTS(SELECT 1 FROM customs_attribute a
                    WHERE a.tenant_id=customs_destination_version.tenant_id
                      AND a.id=customs_destination_version.customs_attribute_id))
    WITH CHECK (EXISTS(SELECT 1 FROM customs_attribute a
                         WHERE a.tenant_id=customs_destination_version.tenant_id
                           AND a.id=customs_destination_version.customs_attribute_id));
  -- 要求的可见性跟随**版本**（进而跟随基础属性与 SKU）。
  CREATE POLICY data_scope ON customs_destination_requirement AS RESTRICTIVE
    USING (EXISTS(SELECT 1 FROM customs_destination_version v
                    WHERE v.tenant_id=customs_destination_requirement.tenant_id
                      AND v.id=customs_destination_requirement.destination_version_id))
    WITH CHECK (EXISTS(SELECT 1 FROM customs_destination_version v
                         WHERE v.tenant_id=customs_destination_requirement.tenant_id
                           AND v.id=customs_destination_requirement.destination_version_id));
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
--
-- **两张表的 DELETE 授权是有意不同的，不要「对齐」成一致**（照 V94 第五节）：
--
--   * `customs_destination_version` **不给 DELETE**：本模块确实没有删除版本与认证要求的
--     端点，改内容一律走「新建版本 + 让新版本接替」（V25 的同一纪律）。
--   * `customs_destination_requirement` **给 DELETE**：保存时认证要求是**全量替换**
--     语义（与 3.7.1.1 的申报要素同一形状），运营在草稿态删掉填错的一行是合法且必要的操作。
--     扣掉这个权限只会让草稿永远改不干净，却**保护不了任何东西** ——
--     「已发布版本的认证要求不可增删改」是由 guard_destination_requirement_published
--     触发器强制的，而该触发器是 BEFORE INSERT OR UPDATE OR DELETE，
--     连裸 SQL 的 DELETE 都在它的判定里被点名拒绝。
--     换句话说：保护来自触发器，不来自权限。用权限当保护是把纵深防线用反了 ——
--     触发器能被有 DDL 权限的人绕过，但那是另一个量级的威胁；
--     而误伤合法草稿编辑是**每一次保存**都会发生的。
GRANT SELECT,INSERT,UPDATE ON customs_destination_version TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON customs_destination_version TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON customs_destination_requirement TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON customs_destination_requirement TO starcon_app;

-- ============================================================ 八、A5：不提供物理删除接口（本模块的边界，不是不够）
--
-- **本模块没有 DELETE 端点，也不给 `customs_destination_version` 授 DELETE 权限**，
-- 只有「新建版本让新版本接替」。
--
-- 理由（照 V94 第四节的论证）：蓝图把本模块的下游点名成 10 报关，而 10 报关的
-- `customs_declaration_line` 在 **warehouse 服务的库里**（V85）。跨服务读对方表是
-- 模块边界纪律明令禁止的，所以本库**根本查不到**「这个版本是否已被某张报关单引用」——
-- 也就无法在删除时给出正确的拒绝。
--
-- 与其提供一个「看着能删、实际会毁掉已提交报关单引用」的删除接口，本模块选择**不给删除**。
-- 这与 V25「已发布版本不可删」是同一条纪律。
--
-- ============================================================ 九、留给下游的口子（不是本模块的缺口）
--
--   * **10 报关（10.5.1.1）**：读 `GET /catalog/customs-declaration/resolve?skuId=…&destination=…&at=…`。
--     该端点返回**基础属性 + 目的地覆盖合成后的那一份**资料，字段命名与 V85 的
--     `customs_declaration_line` 逐一对齐：
--       `declaredName` → declared_item_name（**已按目的地覆盖**）；
--       `hsCode` → hs_code（**已按目的地覆盖**，hsCodeSource 标明这一格来自哪一层）；
--       `statutoryUnitCode` → uom（**只来自基础属性**，目的地不覆盖，见第二节）；
--       `declarationElements` → declaration_elements（**只来自基础属性**，已是 jsonb 对象形态）；
--       `declarationElementsRef` → declaration_elements_ref（= 3.7.1.1 的 id，与该模块一致）；
--       `preferentialApplicable` → 12 财务判断协定税率的依据。
--     **该目的地没有生效版本时一律报错，不回退到基础属性**（第零节第 2 条）。
--     **申报货值不在本端点的裁决范围内**：V85 的 declared_value 取决于实际成交价与汇率快照。
--   * **12 财务（关税成本）**：税率按「税号 + 目的地」查本模块的**合成税号**，
--     而协定税率按 `preferentialOriginEligible AND preferentialApplicable` 判定
--     （第二节末段）。完税价格由它自己按成交价与汇率算，本模块**不落派生值**。
--   * **16 合规（认证要求）**：读 `GET /catalog/customs-destination-versions/requirements?...`，
--     按目的地拿到该版本要求的 CE / FCC / RoHS / UKCA / 危包证清单。
--
--     **留给 10.5.1.1 的一条明确口径（3.7.1.1 第六节已写，本文件确认）**：
--     那条车道交付后应把 `hs_code_source` 从 `MANUAL` 改成 `MODULE_3_7_1_2`、
--     并把 `declaration_elements_source` 指向 3.7.1.1 —— 因为**合成后的申报要素
--     永远来自基础属性**（第二节），本模块不复制它。
