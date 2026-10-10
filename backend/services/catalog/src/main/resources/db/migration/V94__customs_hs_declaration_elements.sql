-- 3.7.1.1 HS 编码与申报要素：维护商品的 HS 编码、原产地、申报品名、申报价值参考、
-- 法定计量单位与是否享惠（RCEP 等），供 10 报关清关生成申报资料。
--
-- 蓝图 3.7.1.1 的原话：
--   「职责：维护商品的 HS 编码、原产地、申报品名、申报价值参考、法定计量单位、是否享惠
--     （RCEP 等），供 10 报关清关生成申报资料。」
--   「输入：HS 编码与申报要素维护；原产地设置。」
--   「输出：报关属性记录；供申报单引用。」
--   「关系：上游 = —；下游 = 10 物流与头程（报关）、16 合规（税则校验）。
--     **HS 编码错误直接导致申报失败与罚款**，必须允许审核流程而非任意填写。」
--
-- ============================================================ 零、为什么这个模块必须有审核闸（本文件存在的理由）
--
-- 最后那句粗体是整个模块的设计轴。它不是一句风险提示，而是一条**结构性要求**：
-- 任意填写 = 运营随手敲一个 6 位数字就能报关。那种错码上到海关的结果是退单或罚款，
-- 而 V85 的文件头第 9 行已经写明「**报关数据错误导致的扣货与罚款由卖家承担**」。
--
-- 所以本文件钉死的是：**只有 status='APPROVED' 的报关属性才允许被 10 报关引用**。
-- 草稿与被驳回的行不是「数据不够好」，而是**不可用**——它们不该被下游看到，
-- 更不该被下游「先拿去用着」。这一点由 `GET /catalog/customs-attributes/declaration-ready`
-- 在服务层强制（未审核通过一律报错而不是返回一份看起来能用的数据），
-- 而 DB 层的 `customs_attribute_reviewed` 三条 CHECK 保证**审核留痕不可能缺失**。
--
-- 审核状态机沿用 V25（BOM 版本发布）的形状：**草稿 → 发布**，附发布人 / 发布时刻，
-- 且「已发布不可撤回、只能向前关闭」。本模块的对应形状是：
--   * `submit`（提交审核）—— 草稿 → 已提交待审（记 submitted_by / submitted_at）；
--   * `review`（审核）—— 通过 / 驳回，记 reviewed_by / reviewed_at / reject_reason。
-- 三条审核态不变量（A3）在 DB 层双向钉死，见第三节。
--
-- ============================================================ 零之二、为什么不并进 V1 的 product_compliance
--
-- 台账把本模块标成阻塞，理由是「product_compliance 同为 `LIKE product_research
-- INCLUDING ALL` 复制，装不了 HS 编码与认证要素。须先拆表」。
--
-- 本文件**不拆 product_compliance**，而是**另建两张表**。理由有三条，缺一条都不够：
--
--   1. `product_compliance` 是一张**无主泛化记录面**（V1 克隆自 product_research，
--      没有 sku 维度、没有唯一性约束），往里塞 HS 编码等于在「一段自由文本」里
--      放一个必须唯一的业务主键：一个 SKU 有两个不同 HS 码时，
--      「报哪个」没有答案——而那正是本模块最不能出错的一件事。
--   2. 申报要素是**有序**的（海关按序比对），而 product_compliance 的列是
--      无结构文本。有序性要么塞进 JSON（丢 DB 层的唯一性约束），要么塞进子表
--      —— 选了后者，所以它本来就得是独立的表。
--   3. 拆表是**对既有表的写所有权变更**，属于跨车道动作；本车道不碰别人拥有结构。
--
-- ============================================================ 零之三、派生值为什么不落表
--
-- 蓝图把「是否享惠」写成事实、把「完税价格」留给了下游。本文件**不落**下面三样，
-- 每一样都有具体的「落错了谁受害」：
--
--   * **可报关性**：它是 `status='APPROVED'` 与「申报要素齐备」的**现算结论**，
--     落一列就等于缓存了一个会被审核流程改写的派生状态 —— 审核驳回后那一列
--     要靠触发器同步，同步漏一次就是「已驳回的行仍被当作可报关」。
--   * **建议税率 / 完税价格**：完税价格 = 成交价 × 汇率 × 税率，而**成交价是 10 报关的
--     事实、汇率与税率是 12 财务与 10.5.1.3 的事实**。V85 第 41–44 行专门为汇率保留了
--     精度刻度，正是因为「当时按哪个汇率报的」要能举证。本模块落一个「完税价格」
--     就等于替三个服务选定了口径，而它们三个不一致时错的是报关单。
--   * **本表只有 `declared_value_reference`（申报价值参考）**，它是**参考值不是申报值**：
--     可空（`BigDecimal` + `numeric(19,6)`，禁浮点），供运营填单时有个锚，
--     但绝不会被下游当成申报货值直接落库（V85 的 declared_value 是另一列，另一件事）。
--
-- ============================================================ 一、两张表：属性与申报要素
--
-- `customs_attribute` —— 一个 SKU 的基础报关属性。UNIQUE(tenant_id,sku_id) 与 V28 的
-- `sku_safety_attribute` 同一纪律：一份 SKU 只能有一个申报事实。两个不同答案会变成
-- 两张真实的报关单，而「报哪个」没人能回答。
--
-- `customs_declaration_element` —— 申报要素项，**有序**（海关按 ordinal 逐项比对），
-- UNIQUE(tenant_id,customs_attribute_id,ordinal) 挡住同一位次两行。
-- 落成子表而不是一列 JSONB：JSONB 给不了 DB 层的「同属性内位次唯一」与
-- 「required 的必须有值」这两条约束。
--
-- 注意与下游的形状差异：10 报关的 `customs_declaration_line.declaration_elements`
-- 是 **jsonb 对象**（V85 第 245 行 CHECK `jsonb_typeof(... )='object'`），
-- 那是为了「结构随上游字典回填、不写死键名」而**故意**留的。
-- 本表的**有序行是事实**，拍平只发生在 `/declaration-ready` 的**响应**上——
-- 为迁就下游把有序结构拍平落库，等于把顺序这个业务事实降级成了一个约定。
CREATE TABLE customs_attribute (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
  -- ===== 申报要素本体 =====
  -- HS 编码。varchar(16) 而不是更短：HS 码最长 10 位（规则 A1），留余量给
  -- 带国家子目的地的本地税号（如 8541.43.00 之外的 10 位 + 2 位税则号）。
  hs_code varchar(16) NOT NULL,
  -- 原产地。ISO 3166-1 alpha-2，两位大写字母（规则 A1 用正则钉死）。
  -- **用 varchar(2) 而不是 char(2)**：PostgreSQL 的 char(n) 是**空白填充**类型，
  -- 值会在比较/拼接时带上尾随空格，触发 CHECK 或唯一约束时报出来的错极难排查
  -- （看到的值是 'CN ' 而不是 'CN'）。全仓库既有口径统一是 varchar(2)：
  -- V80 / V85 / V90 / V91 的 origin_country_code 与 destination_country_code
  -- 都是 varchar(2)，本列跟随。
  origin_country_code varchar(2) NOT NULL,
  -- 申报品名。**不照搬销售标题**：V85 第 207–208 行明确「申报口径不照搬销售标题，
  -- 混用是常见被退单原因」，所以这是独立维护的字段。
  declared_name text NOT NULL,
  -- 申报价值参考：**可空**。它只是运营填单时的锚，不是申报货值（见零之三）。
  -- 禁浮点：numeric + BigDecimal。
  declared_value_reference numeric(19,6),
  -- 法定计量单位代码（例：PCE 件 / KGM 千克 / SET 套）。法定单位与销售单位不同，
  -- 海关按法定单位与法定数量申报，所以这是独立字段而不是查销售单位。
  statutory_unit_code varchar(8) NOT NULL,
  -- ===== 是否享惠（规则 A2）=====
  -- 能否申领协定优惠。布尔而不是一个枚举：14 个自贸协定的适用关系是**重叠**的
  -- （同一样商品可能同时符合 RCEP 与 CPTPP），压成一个枚举就答不出「同时符合哪几个」。
  preferential_origin_eligible boolean NOT NULL DEFAULT false,
  -- 协定代码（例 RCEP / CPTPP）。**非空串 = 不适用**，不是 NULL ——
  -- 本表所有「文本类」列一律用「空串 = 无」而不是 NULL，
  -- 免得下游每读一列都要先判一次 null（V28 的 dangerous_substance_class 同一口径）。
  preference_agreement varchar(32) NOT NULL DEFAULT '',
  -- ===== 审核状态机 =====
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','APPROVED','REJECTED')),
  -- 提交审核的留痕。**status 里没有 PENDING**，所以「已提交待审」这一格由这两列承担
  -- （详见第三节 A3 的说明）：否则「已提交无人审」与「还没提交」是同一行，
  -- 审核队列就不存在，而 submit 端点也会退化成一次没有效果的点击。
  submitted_by uuid, submitted_at timestamptz,
  -- 审核留痕。与 V25 的 published_by / published_at 同一形状。
  reviewed_by uuid, reviewed_at timestamptz,
  -- 驳回原因。REJECTED 时必须非空（规则 A3）——驳回不写理由，
  -- 运营就只能猜要怎么改，改完再提交还是被驳回。
  reject_reason text NOT NULL DEFAULT '',
  -- 乐观锁凭据。与 sku_package / sku_safety_attribute 同一套口径：
  -- 并发编辑两个人都提交时，后到的那个必须撞 409 而不是静默覆盖先到的申报事实。
  version bigint NOT NULL DEFAULT 0,
  -- note 是运营补充说明（例：HS 码已与报关行确认过），**不参与任何判定**：
  -- 让自由文本进判定条件，就等于给了一条可以绕过审核的旁路。
  note text NOT NULL DEFAULT '',
  -- created_at / updated_at 都是系统时间；本模块没有「业务时间」维度
  -- （申报事实一旦审过就成立，不存在「按某个历史时刻重建申报资料」的场景 ——
  --  真的要重建，报关单上冻结的是当时那一版的 jsonb，见 V85）。
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,sku_id),

  -- ---- 规则 A1：HS 编码必须是 6–10 位纯数字 ----
  -- 蓝图点名的罚款来源，第一道防线就在 CHECK 上。
  -- 为什么连字母都不行：带字母的形态（如 CN 代号前缀）属于「本地税则号」，
  -- 那是目的国海关的二次编码，不是本模块这个「国际 HS 编码」列该装的东西 ——
  -- 装进来就等于让 10 报关拿着一个格式错误的码去申报。
  -- 为什么下界是 6：国际 HS 编码最短 6 位，少于 6 位一定不是有效编码。
  -- 为什么上界是 10：现行国际 HS 编码最长 10 位，超过就不是 HS 编码了。
  CONSTRAINT customs_attribute_hs_code_format
    CHECK (hs_code ~ '^[0-9]{6,10}$'),
  -- 原产地必须是两位大写字母（ISO 3166-1 alpha-2）。
  CONSTRAINT customs_attribute_origin_format
    CHECK (origin_country_code ~ '^[A-Z]{2}$'),
  -- 申报品名与法定单位不能是空串：两者都会直接出现在报关单上，
  -- 空串会变成一张申报资料齐备但内容为空的单子。
  CONSTRAINT customs_attribute_declared_name_not_blank
    CHECK (btrim(declared_name)<>''),
  CONSTRAINT customs_attribute_statutory_unit_not_blank
    CHECK (btrim(statutory_unit_code)<>''),
  -- 申报价值参考可空，但**给了就必须 > 0**：0 或负的「参考值」没有参考意义，
  -- 而它会被下游误当成申报货值的下限去用。
  CONSTRAINT customs_attribute_value_reference_positive
    CHECK (declared_value_reference IS NULL OR declared_value_reference>0),
  -- 申报价值参考最多 6 位小数，与 numeric(19,6) 对齐：
  -- 多给的小数位会被 PostgreSQL **静默四舍五入**，而「报的是 12.3456789」
  -- 是一个运营以为存在、库里其实已经改掉的数。
  CONSTRAINT customs_attribute_note_length
    CHECK (length(note)<=1000 AND length(reject_reason)<=1000),

  -- ---- 规则 A2：享惠资格与协定代码双向一致 ----
  -- 「勾了能享惠但没说是哪个协定」：海关无法据此适用税率，优惠直接失效，
  -- 而运营以为已经享惠 —— 少缴的税到年底可能被追缴。
  CONSTRAINT customs_attribute_preference_needs_agreement
    CHECK (NOT preferential_origin_eligible OR btrim(preference_agreement)<>''),
  -- 反向：「填了协定却没勾享惠资格」。这一行会同时告诉 10 报关「不能享惠」
  -- 和 16 合规「有协定」，下游拿到两个互相矛盾的答案。与 V28 的
  -- sku_safety_class_needs_dangerous 同一纪律：两个方向都要钉。
  CONSTRAINT customs_attribute_agreement_needs_preference
    CHECK (preferential_origin_eligible OR btrim(preference_agreement)=''),

  -- ---- 规则 A3：审核态一致性（三个方向全钉）----
  -- APPROVED ⟹ 有审核人、有审核时刻、无驳回理由。
  -- 「没有发布人的 PUBLISHED 等于「有人按过发布」这种无法证伪的说法」（V25 第一节），
  -- 换到本模块就是：没有审核人的 APPROVED 无法回答「谁放行了这个申报事实」。
  CONSTRAINT customs_attribute_reviewed
    CHECK (status<>'APPROVED'
        OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND btrim(reject_reason)='')),
  -- DRAFT ⟹ 无审核痕迹。DRAFT 表示「没人审过」，
  -- 带审核痕迹的 DRAFT 会让审核队列里混进已经审过的行。
  CONSTRAINT customs_attribute_draft_unreviewed
    CHECK (status<>'DRAFT'
        OR (reviewed_by IS NULL AND reviewed_at IS NULL AND btrim(reject_reason)='')),
  -- REJECTED ⟹ 有审核人、有审核时刻、且驳回原因非空。
  CONSTRAINT customs_attribute_rejected_needs_reason
    CHECK (status<>'REJECTED'
        OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND btrim(reject_reason)<>'')),
  -- 已审核通过的申报属性**不可原地改内容**（只允许重新审核，见 guard 触发器）：
  -- 一个 APPROVED 行同时被改内容又保持 APPROVED，就等于绕过审核改掉了已放行的申报事实。
  -- 这条与 V25 的 guard_bom_version_published 同一纪律：已发布版本只能向前关闭。
  CONSTRAINT customs_attribute_approved_not_drafted
    CHECK (status<>'APPROVED' OR submitted_by IS NOT NULL),
  -- SKU 被删则报关属性一起删：留下孤儿行会让筛选查询算出一批「永远不能报关」的 SKU，
  -- 而没人知道它们对应的是哪个已不存在的商品。
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE
);

CREATE TABLE customs_declaration_element (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, customs_attribute_id uuid NOT NULL,
  -- 位次。**有序**是业务事实：海关按序比对申报要素，
  -- 同一份要素换个顺序会被判为填报不符。
  ordinal smallint NOT NULL,
  -- 要素代码（例 01 品名 / 02 品牌类型 / 03 用途）。varchar(32) 而不是数字：
  -- 各国的申报要素编号并不统一是数字（常见的是 "01" 但也有 "A1" 形态），
  -- 收窄成数字等于把一部分目的地拒之门外。
  element_code varchar(32) NOT NULL,
  element_name text NOT NULL,
  element_value text NOT NULL,
  -- 是否必填。**与 element_value 的空值是两件事**：一条 required 且已填的要素
  -- 和一条 required 但还空着的要素，在报关单上是两个完全不同的状态。
  required boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一位次不能有两行：两行时「这一位填什么」无法回答。
  UNIQUE(tenant_id,customs_attribute_id,ordinal),
  CONSTRAINT customs_element_code_not_blank CHECK (btrim(element_code)<>''),
  CONSTRAINT customs_element_name_not_blank CHECK (btrim(element_name)<>''),
  CONSTRAINT customs_element_ordinal_positive CHECK (ordinal>0),
  -- 属性被删则要素一起删（不留孤儿要素）。
  FOREIGN KEY(tenant_id,customs_attribute_id)
    REFERENCES customs_attribute(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 二、索引（每条都对应一个真实的查询路径）
--
-- 16 合规（税则校验）的取数形状：某 HS 编码下有哪些 SKU、某原产地/协定下有哪些申报事实。
CREATE INDEX customs_attribute_hs_code
  ON customs_attribute(tenant_id, hs_code, sku_id);
CREATE INDEX customs_attribute_origin
  ON customs_attribute(tenant_id, origin_country_code, sku_id);
-- 享惠资格筛选（10 报关按「可享惠」批量核）与协定反查：行数远小于全表，走部分索引。
CREATE INDEX customs_attribute_preference
  ON customs_attribute(tenant_id, preference_agreement, sku_id) WHERE preferential_origin_eligible;
-- 审核队列：已提交待审的行（状态 DRAFT 且已留提交痕）。这是本模块最高频的后台查询
-- —— 审核人每天打开的就是这张表，所以用部分索引而不是全表扫 status。
CREATE INDEX customs_attribute_review_queue
  ON customs_attribute(tenant_id, submitted_at, sku_id)
  WHERE status='DRAFT' AND submitted_at IS NOT NULL;
-- 列表页默认排序（updated_at DESC, id DESC）——与 sku_safety_updated 同一形状。
CREATE INDEX customs_attribute_updated
  ON customs_attribute(tenant_id, updated_at DESC, id DESC);
-- 按状态筛选（DRAFT / APPROVED / REJECTED 三档），与列表排序共用一条。
CREATE INDEX customs_attribute_status
  ON customs_attribute(tenant_id, status, updated_at DESC, id DESC);
-- 「还没维护报关属性」的 SKU 在筛选页里要能快速捞出来：反查走 sku 表的 updated_at 索引，
-- 不另建（与 V28 同一取舍）。
--
-- 申报要素：按属性取全部要素是最高频形状（/declaration-ready 每次都要读一遍）。
CREATE INDEX customs_element_by_attribute
  ON customs_declaration_element(tenant_id, customs_attribute_id, ordinal);
-- 16 合规按要素码反查（例：全库「品牌类型」都填了没有）。
CREATE INDEX customs_element_by_code
  ON customs_declaration_element(tenant_id, element_code, customs_attribute_id);

-- ============================================================ 三、触发器兜底（纵深防线）
--
-- 上一层的 CHECK 可以被 `ALTER TABLE ... DROP CONSTRAINT` 或迁移脚本绕过，触发器不行
-- （与 V21 / V22 / V23 / V25 / V28 同一纪律）。
--
-- **guard_customs_attribute_review**（BEFORE INSERT OR UPDATE ON customs_attribute）
-- 把三条审核不变量与**跨表的申报要素齐备性**（规则 A4）再钉一遍，
-- 并给可读的中文报错（照 V28 的 guard_sku_package_weights 风格：说清后果，
-- 而不是只报一个约束名 —— 运营看到的是「这条会被退单」而不是「23514」）。

-- 规则 A4：**APPROVED 时该属性至少有一条申报要素，且每条 required 的都有非空值**。
-- 这条必须用触发器：它是**跨表**的（同属性下至少一行 + 那几行的值非空），
-- CHECK 只能看本行，天然做不到。
--
-- 为什么非要这条：海关按申报要素逐项比对，缺一项就是填报不符，
-- 而**逐项缺哪一项**只有一次报全才有意义（V85 第 536–537 行用的就是这个口径：
-- 「逐项缺失要逐项报出来，一次报一条会让运营反复提交试错」）。
CREATE FUNCTION guard_customs_attribute_review() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE missing_count integer; blank_required text;
BEGIN
 -- 规则 A1 的可读版本：CHECK 拦住的同时说清为什么这个码不能申报。
 IF NEW.hs_code !~ '^[0-9]{6,10}$' THEN
  RAISE EXCEPTION 'HS 编码「%」不是 6–10 位纯数字：国际 HS 编码形如 851712 或 8517123090，
   带字母或位数不对的是目的国本地税则号，不是这一列该装的编码。填错会直接导致申报失败与罚款（V85：扣货与罚款由卖家承担）',
   NEW.hs_code USING ERRCODE='23514';
 END IF;
 IF NEW.origin_country_code !~ '^[A-Z]{2}$' THEN
  RAISE EXCEPTION '原产地「%」不是两位大写字母的 ISO 3166-1 alpha-2 代码（如 CN / VN / DE）：原产地决定适用税率与是否适用自贸协定，填错会让优惠失效或被追缴',
   NEW.origin_country_code USING ERRCODE='23514';
 END IF;
 -- 规则 A2 的可读版本，两个方向都报。
 IF NEW.preferential_origin_eligible AND btrim(NEW.preference_agreement)='' THEN
  RAISE EXCEPTION '勾了可享惠就必须填写协定代码（RCEP / CPTPP 等）：海关要凭协定代码才能适用优惠税率，
   没有它这项优惠直接失效，而运营以为已经享惠 —— 少缴的税到年底可能被追缴' USING ERRCODE='23514';
 END IF;
 IF NOT NEW.preferential_origin_eligible AND btrim(NEW.preference_agreement)<>'' THEN
  RAISE EXCEPTION '没勾可享惠就不该填协定代码（当前填了「%」）：这一行会同时告诉 10 报关「不能享惠」
   和 16 合规「有协定」，下游拿到两个互相矛盾的答案', NEW.preference_agreement USING ERRCODE='23514';
 END IF;
 -- 规则 A3 的可读版本。
 IF NEW.status='APPROVED' AND (NEW.reviewed_by IS NULL OR NEW.reviewed_at IS NULL) THEN
  RAISE EXCEPTION '审核通过必须同时记录审核人与审核时刻：没有审核人的「已通过」等于「有人按过通过」这种无法证伪的说法，
   而 HS 编码错误导致的罚款最终要问到人' USING ERRCODE='23514';
 END IF;
 IF NEW.status='REJECTED' AND btrim(NEW.reject_reason)='' THEN
  RAISE EXCEPTION '驳回必须写明原因：运营只能靠理由知道该怎么改，驳回不给理由就变成一次没有信息量的点击，
   改完再提交还是会被驳回' USING ERRCODE='23514';
 END IF;
 IF NEW.status='APPROVED' THEN
   IF NEW.submitted_by IS NULL THEN
     RAISE EXCEPTION '审核通过前必须先提交审核：没有提交留痕就无法回答「这份申报资料是谁在什么时候交上来的」' USING ERRCODE='23514';
   END IF;
   -- 规则 A4 之一：至少要有一条申报要素。一条都没有的「申报要素齐备」是自相矛盾的。
   IF NOT EXISTS(SELECT 1 FROM customs_declaration_element e
                  WHERE e.tenant_id=NEW.tenant_id AND e.customs_attribute_id=NEW.id) THEN
     RAISE EXCEPTION '审核通过前必须至少填写一条申报要素：海关按申报要素逐项比对，
     一条都没有的报关资料会被判为填报不符并退单' USING ERRCODE='23514';
   END IF;
   -- 规则 A4 之二：每条 required 的都要有非空值。**逐项点名**而不是只报「有空的」，
   -- 让运营一次改完而不是反复提交试错（与 V85 同一口径）。
   SELECT count(*), string_agg(e.element_code||' '||e.element_name, '、')
     INTO missing_count, blank_required
     FROM customs_declaration_element e
    WHERE e.tenant_id=NEW.tenant_id AND e.customs_attribute_id=NEW.id
      AND e.required AND btrim(e.element_value)='';
   IF missing_count>0 THEN
     RAISE EXCEPTION '审核通过前必须补齐 % 条必填申报要素（当前为空）：%。海关按申报要素逐项比对，
     缺一项就是填报不符并退单，补齐后重新提交即可', missing_count, blank_required USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_customs_attribute_review BEFORE INSERT OR UPDATE ON customs_attribute
  FOR EACH ROW EXECUTE FUNCTION guard_customs_attribute_review();

-- **guard_customs_element_under_review**（BEFORE INSERT OR UPDATE OR DELETE ON
-- customs_declaration_element）：**已审核通过的属性，其申报要素不可再动**。
--
-- 沿用 V25 的 guard_bom_component_published 形状（蓝图标在 3.5.2.2 的
-- 「已发布版本的组件不可改」），只是判定键换成 status='APPROVED'。
--
-- 为什么必须连 DELETE 一起拦：只拦增改的话，「把全部要素删掉」这条路径是敞开的，
-- 而规则 A4 的齐备性判定只在**审核那一刻**跑一次 —— 审完之后把要素全删掉，
-- A4 不会再次触发，行却变成了一份「已审核通过但申报要素为空」的申报事实，
-- 10 报关会拿着一份空资料去申报。
CREATE FUNCTION guard_customs_element_under_review() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_id uuid; parent_status text;
BEGIN
 parent_id := COALESCE(NEW.customs_attribute_id, OLD.customs_attribute_id);
 SELECT status INTO parent_status FROM customs_attribute
  WHERE tenant_id=COALESCE(NEW.tenant_id, OLD.tenant_id) AND id=parent_id;
 IF parent_status='APPROVED' THEN
  RAISE EXCEPTION '这条报关属性已审核通过，申报要素不可再增删改：已审核通过的申报事实就是 10 报关引用的那一份，
   改动它等于让下游拿着一份与审核结论不一致的资料去申报。要改请先在审核里驳回它' USING ERRCODE='23514';
 END IF;
 -- 非 APPROVED 的删除交给 ON DELETE CASCADE，元素表本行不做额外判断。
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_customs_element_under_review
  BEFORE INSERT OR UPDATE OR DELETE ON customs_declaration_element
  FOR EACH ROW EXECUTE FUNCTION guard_customs_element_under_review();

-- ============================================================ 四、A5：不提供物理删除接口（本模块的边界，不是不够）
--
-- **本模块没有 DELETE 端点，也不给 `customs_attribute` 授 DELETE 权限**，
-- 只有「驳回后改」。
--
-- ⚠️ 这条边界**只针对报关属性本身**，**不包括它的申报要素子表**：
-- 子表 `customs_declaration_element` 是授了 DELETE 的，因为草稿态改清单
-- 必须能删行（见第五节末尾对两张表授权差异的完整说明）。
--
-- 理由（照抄任务书的设计论证，写在这里免得以后有人当成缺口来补）：
-- 蓝图把本模块的下游点名成 10 报关，而 10 报关的 `customs_declaration_line` 在
-- **warehouse 服务的库里**（V85）。跨服务读对方表是模块边界纪律明令禁止的，
-- 所以本库**根本查不到**「这条报关属性是否已经被某张报关单引用」——
-- 也就无法在删除时给出正确的拒绝。
--
-- 与其提供一个「看着能删、实际会毁掉已提交报关单引用」的删除接口，
-- 本模块选择**不给删除**：改内容走「驳回 → 改 → 重新提交」，改错了的申报事实
-- 留在库里可追溯（audit_event 记着每一轮审核），这与 V25
-- 「已发布版本不可删」是同一条纪律。
--
-- 所以 `customs_attribute` 的 runtime GRANT **不含 DELETE**。
-- FK 的 ON DELETE CASCADE 仍然照常工作：PostgreSQL 的引用完整性动作由系统执行，
-- 不受删除者对该子表权限的影响 —— 这也是子表仍需要自己授 DELETE 的原因：
-- 级联删的是「属性没了」，而子表授 DELETE 服务的是「属性还在、清单要改」，
-- 两者是不同的操作，权限必须分开考虑。
--
-- **留待 10 报关车道跟进**（本模块只做到「能接上」，不越界去改 warehouse）：
-- 交付后 10.5.1.1 应把回填来源从 MANUAL 改成 MODULE_3_7_1_1，
-- 并把 `declaration_elements_ref` 填成本表的 `customs_attribute.id`（UUID，36 字符，
-- 放得进 varchar(64)）—— 那正是 /declaration-ready 返回体里的 declarationElementsRef。

-- ============================================================ 五、RLS 与授权
--
-- 与 V5 / V6 / V28 同一口径：租户级数据（报关属性是租户共享的商品定义，
-- 不套 V3 的 owner_id / department_id 数据范围）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['customs_attribute','customs_declaration_element'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
  -- 可见性跟随 SKU（与 V28 的 sku_safety_attribute 同一纪律）：看不到 SKU 就看不到它的报关属性。
  -- 没有这条，「按 HS 编码筛选」会成为一个绕过页面权限的枚举口：只要能调筛选接口，
  -- 就能逐页试出别的租户有哪些商品走哪个税号。
  CREATE POLICY data_scope ON customs_attribute AS RESTRICTIVE
    USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=customs_attribute.tenant_id AND s.id=customs_attribute.sku_id))
    WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=customs_attribute.tenant_id AND s.id=customs_attribute.sku_id));
  -- 要素的可见性跟随**属性**（进而跟随 SKU）：看不到属性就看不到它的要素。
  CREATE POLICY data_scope ON customs_declaration_element AS RESTRICTIVE
    USING (EXISTS(SELECT 1 FROM customs_attribute a
                    WHERE a.tenant_id=customs_declaration_element.tenant_id
                      AND a.id=customs_declaration_element.customs_attribute_id))
    WITH CHECK (EXISTS(SELECT 1 FROM customs_attribute a
                         WHERE a.tenant_id=customs_declaration_element.tenant_id
                           AND a.id=customs_declaration_element.customs_attribute_id));
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
--
-- **两张表的 DELETE 授权是有意不同的，不要「对齐」成一致**：
--
--   * `customs_attribute` **不给 DELETE**：本模块确实没有删除报关属性的端点，
--     改内容一律走「驳回 → 改 → 重新提交」（A5 的论证）。
--   * `customs_declaration_element` **给 DELETE**：保存时申报要素是**全量替换**
--     语义（Models 里有说明），运营在草稿态删掉填错的一行是合法且必要的操作。
--     扣掉这个权限只会让草稿永远改不干净，却**保护不了任何东西** ——
--     「已审核通过的申报资料不可增删改」是由 `guard_customs_element_under_review`
--     触发器强制，而该触发器是 `BEFORE INSERT OR UPDATE OR DELETE`，
--     连裸 SQL 的 DELETE 都在它的 A4 判定里被点名拒绝（见第三节之二之二）。
--     换句话说：保护来自触发器，不来自权限。用权限当保护是把纵深防线用反了 ——
--     触发器能被有 DDL 权限的人绕过，但那是另一个量级的威胁；
--     而误伤合法草稿编辑是**每一次保存**都会发生的。
GRANT SELECT,INSERT,UPDATE ON customs_attribute TO catalog_app;
GRANT SELECT,INSERT,UPDATE ON customs_attribute TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON customs_declaration_element TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON customs_declaration_element TO starcon_app;

-- ============================================================ 六、留给下游的口子（不是本模块的缺口）
--
--   * **10 物流与头程（报关，10.5.1.1）**：读
--     `GET /catalog/customs-attributes/declaration-ready?skuId=...`。
--     该端点**只返回 status='APPROVED' 的申报资料**，未审核通过一律报错而不是
--     返回一份看起来能用的数据 —— 这是本模块最重要的一条闸。
--     返回体字段与 V85 的 `customs_declaration_line` 一一对得上：
--       `hsCode` → hs_code（hsCodeSource='MODULE_3_7_1_1'，hsCodeVerified=true，
--                        因为 APPROVED 就是「这个码被人审过」的可信来源）；
--       `declaredName` → declared_item_name；
--       `originCountryCode` → origin_country_code（**列名与 V85 第 225 行同名**，
--                        本模块的 `origin_country_code` 与下游的 `origin_country_code`
--                        是同一个概念，回填时不需要做名称转换 —— 两个模块各叫各的
--                        会在回填时多出一层无意义的映射）；
--       `statutoryUnitCode` → uom；
--       `declarationElements` → declaration_elements（**已是 jsonb 对象形态**，
--                        键 = element_code、值 = element_value，按 ordinal 有序生成）；
--       `declarationElementsRef` → declaration_elements_ref（= 本表 id）。
--     **申报货值不在返回体的裁决范围内**：V85 的 declared_value / declared_unit_value
--     取决于实际成交价与汇率快照，那是 10 报关的事实，本模块只给
--     `declaredValueReference` 作为参考值（见零之三）。
--   * **16 合规（税则校验）**：读 `GET /catalog/customs-attributes/profiles?...`
--     按 HS 编码 / 原产地 / 协定筛选；HS 编码库本身**不在本库**（那是税则主管部门的
--     公开数据，不是租户主数据），本模块只保证「填进来的码是格式合法的 6–10 位数字」。
--   * **3.7.1.2（目的地报关版本）**：同一件货在不同目的地的申报口径不同，
--     那是本模块的**版本化**职责（按目的地 + 税则版本）。本表是
--     「这个 SKU 的基础报关属性」这一层，**不带目的地维度**，
--     也不引用 3.7.1.2 的表与端点（那条车道尚未开工）。
--
--     **留给 3.7.1.2 的一条明确口径（本文件只记录，不代它决定）**：
--     3.7.1.2 的目的地字段必须是 `destination_country_code varchar(2) NOT NULL`
--     且**指向一个真实国家码，不接受通配**。仓库里存在用 `'*'` 表示「不限目的地」的
--     先例（V85 第 133 行 `destination_country_code varchar(2) NOT NULL DEFAULT '*'`、
--     V84 的 `country_code ... DEFAULT '*'`、V92 的 `destination_country_code ... DEFAULT '*'`），
--     但**那三条是报关单 / 在途 / 路由的筛选条件**，它们的 `*` 读作「这次申报可以发往任何国家」，
--     是一个筛选语义；而 3.7.1.2 的目的地是**申报资料本身的口径归属**，
--     蓝图 3.7.1.2 原文是「**不同目的地必须用不同版本，不能用一套申报资料打通全球**」——
--     一条 `*` 的申报资料正是「用一套打通全球」。照搬 `'*'` 会把 3.7.1.2 要消灭的东西
--     用一个「看起来合法」的默认值重新引入，而且它在页面上完全正常。