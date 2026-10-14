-- 3.7.2.1 物理与安全属性：维护裸品物理参数与安全属性，决定物流渠道可用性与是否需要特殊申报。
--
-- 蓝图 3.7.2.1 的原话：
--   「职责：维护裸品物理参数（长宽高、净重、毛重、包装方式）与安全属性（是否带电、是否液体、
--     是否危险品、是否磁性），决定可走哪些物流渠道与是否需要特殊申报。」
--   「输入：物理参数与安全属性设置；包装信息。」
--   「输出：SKU 物理档案；供 10 物流渠道筛选、8 拣货装箱体积核算。」
--   「关系：上游 = —；下游 = 10 物流（渠道适配、体积重）、16 合规（危险品与认证）。
--     **这些属性直接决定物流可用性**，采集时点应尽量前置到 SKU 建档。」
--
-- ============================================================ 零、与 3.1.2.2 的分工（本文件钉死的 B1）
--
-- 蓝图把「长宽高、净重、毛重、包装方式」写进 3.7.2.1 的职责，而 **V6 早已把这些列建在
-- `sku_package` 上并让 3.1.2.2 的详情页读写**。本文件**不再建一张表把它们抄一遍**——
-- 那是 AGENTS.md 明令禁止的「第二个事实来源」：
--
--   * 一张 SKU 出现两个不同答案的毛重时，10 物流按哪个算体积重？没人能回答，
--     而算错的那一次会变成一张真实的面单。
--   * V6 的文件头已经把这句分工写死了：「**3.7.2.1 物理与安全属性在 sku_package 之上
--     补是否带电/液体/危险品/磁性**」。本文件照这句话执行。
--
-- 因此本文件的职责边界是：
--   * **物理参数**（长/宽/高、净重、毛重、包装方式）：**读**走 `sku_package`，
--     **写**也只写 `sku_package`（同一个 ON CONFLICT 目标，不新增列、不新增表）。
--   * **安全属性**（带电/液体/危险品/磁性）：本文件新建 `sku_safety_attribute`。
--   * **体积、体积重、渠道可用性、特殊申报、装箱体积核算**：全部由服务层现算
--     （`PhysicalSafetyRules` 纯函数），**一律不落表**。
--
-- **派生值为什么不落表**：体积重取决于「体积 ÷ 体积重除数」，而除数是**渠道自己的口径**
-- （空运 6000、快递 5000…），10 物流才有资格决定用哪个除数。落一列「体积重」等于
-- 偷偷替 10 物流选定了一个除数，而它会随渠道变化 —— 改一次渠道就要全表重算一次。
--
-- ============================================================ 一、四个安全标记为什么是四个独立布尔（B2）
--
-- 蓝图逐字列了四个：**是否带电、是否液体、是否危险品、是否磁性**。它们在物流上互不蕴含：
--   * 带电（锂电）→ 空运需电池申报与包装 2 级；
--   * 液体 → 需防漏包装，部分渠道直接拒收；
--   * 危险品 → 需危险品申报，多数快递渠道不接；
--   * 磁性 → 需磁场强度报告（磁性物申报）。
-- 用一个「特殊属性」枚举会把「带电且危险」这种真实存在的组合压成一行，
-- 而组合恰恰是渠道判定要用的东西。所以四个独立布尔，不合并。
--
-- **默认全 false 而不是全 null**：与 `sku_package` 的「可空 = 还没采集」不同，
-- 这里一行代表「已采集且结论是都不带」。**没有这一行**才表示「还没人采集过」——
-- 这两件事的下一步动作完全不同（前者是「确认无需申报」，后者是「去 SKU 建档时补采集」），
-- 蓝图的「采集时点应尽量前置到 SKU 建档」也正是冲这个缺口说的。
--
-- **不建 UN 编号 / 电池类型 / 申报要素列**：那是 3.7.1.1「HS 编码与申报要素」与
-- 3.7.1.2「目的地报关版本」的职责。3.7.1.1 已交付（V94：customs_attribute /
-- customs_declaration_element 两张表 + GET /catalog/customs-attributes/declaration-ready），
-- 但**本迁移仍然不引用**它们：安全属性回答「是否需要特殊申报」，
-- 报关属性回答「怎么申报」，两张表各是各的事实，写入所有权不混。
-- **仍未开发的是 3.7.1.2**（同一件货在不同目的地的报关版本）。
-- 本模块只回答「是否需要特殊申报」，**不回答「怎么申报」**——后者是 3.7.1.x 的事实。

-- ============================================================ 一之二、危险品类别：本模块必须有
--
-- 蓝图把「是否危险品」写进本模块职责，而 16 合规拿到一个 `dangerous_goods=true` 却不知道
-- **按什么申报**时，只能把单子退回来。所以本表带一个**危险品类别标识**
-- （`dangerous_substance_class`，例：UN 编号 / IATA 类别代码 / 内部危险品编码），
-- 并在 DB 层钉死「勾了危险品就必须给出类别」（规则 B3）。
--
-- **边界**：本列只回答「属于哪一类危险品」，**不回答「怎么申报」**。
-- HS 编码、原产地、申报品名、法定计量单位与完整申报要素属于 3.7.1.1（V94 已交付，
-- 未审核通过时 10 报关取数一律 409），认证证书与按目的地的报关版本属于 3.7.1.2
-- （**仍未开发**）。本迁移**不引用**它们的表与端点，只在口径上与之划清边界。
-- 同一件货在不同目的地申报口径不同，那是 3.7.1.2 的版本化职责，不在本模块。
--
-- **规则 B3（危险品必须有类别）**：`dangerous_goods = true` 时类别不能为空串。
-- 理由：16 合规按类别决定申报路径与所需证书；类别为空的危险品在下游无法处理，
-- 而「先存下来等以后补」会让它一路流到报关单上。
--
-- ============================================================ 2. 安全属性
--
-- 一行 = 一个 SKU 的安全属性结论。UNIQUE(tenant_id, sku_id) 保证一个 SKU 只有一份结论，
-- 避免「查到两行都是 true 然后随便挑一行」——渠道判定必须建立在唯一事实上。
CREATE TABLE sku_safety_attribute (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
  -- 四个安全标记（规则 B2）。均为 NOT NULL DEFAULT false：没有这一行 = 未采集，
  -- 有这一行 = 已采集，false 就是「确认不带」。
  contains_battery boolean NOT NULL DEFAULT false,
  contains_liquid boolean NOT NULL DEFAULT false,
  dangerous_goods boolean NOT NULL DEFAULT false,
  is_magnetic boolean NOT NULL DEFAULT false,
  -- 危险品类别标识（规则 B3）。危险品为真时不能为空 —— 见第一节之二。
  -- 非危险品时**必须**为空：不带危险品标记的 SKU 却挂着危险品类别，
  -- 下游读到的就是两个互相矛盾的答案（10 物流按「不危险」放行，16 合规按类别拦截）。
  dangerous_substance_class varchar(64) NOT NULL DEFAULT '',
  -- 乐观锁凭据。与 sku_package.version 同一套口径：并发编辑两个人都提交时，
  -- 后到的那个必须撞 409 而不是静默覆盖先到的结论。
  version bigint NOT NULL DEFAULT 0,
  -- note 是运营补充说明（例：磁性物已做磁场衰减处理），**不参与任何判定**：
  -- 让自由文本进判定条件，就等于给了一条可以绕过申报的旁路。
  note text NOT NULL DEFAULT '',
  -- created_at / updated_at 都是系统时间；本模块没有「业务时间」维度
  -- （结论一旦写下就成立，不存在「按某个历史时刻重建结论」的场景）。
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,sku_id),
  -- 规则 B3：勾了危险品就必须给出类别，否则 16 合规不知道按什么申报。
  CONSTRAINT sku_safety_dangerous_needs_class
    CHECK (NOT dangerous_goods OR btrim(dangerous_substance_class)<>''),
  -- 反向：不带危险品标记就不许挂类别。两个方向都要钉，否则「取消勾选但没清类别」
  -- 会留下一行自相矛盾的安全属性，而筛选「非危险品」时它仍会被放行。
  CONSTRAINT sku_safety_class_needs_dangerous
    CHECK (dangerous_goods OR btrim(dangerous_substance_class)=''),
  -- SKU 被删则安全属性一起删：留下孤儿行会让筛选查询算出「不可发货」的 SKU。
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 二之二、物理参数的一致性约束（在 sku_package 上）
--
-- 蓝图 3.7.2.1 的一致性约束必须**在 DB 层钉死**，不能只靠 Java 校验：
-- 直接一条裸 SQL 改出脏数据照样能成功，而脏的毛重/净重会算出一张真实的错误面单。
--
-- V6 建 `sku_package` 时写的是 `>= 0`（允许 0，把「量到 0」与「没量到」分开）。
-- 但本模块的体积口径是**「缺一维不给体积」**（PhysicalSafetyRules 规则 R1），
-- 而 0 长宽高在业务上是**脏数据**：0 长度的箱子不是「体积为 0」的箱子，
-- 而是「这一维没量到」——两者在页面上会导出完全不同的运费。
-- 所以这里把三个维度与净重**收紧为 > 0**，毛重仍由 V6 的「≥ 净重」兜底。
--
-- 为什么用 ALTER TABLE ADD CONSTRAINT 而不是改 V6：
--   V6 已经上线，改它的 CHECK 会让「已部署库」与「新迁移库」出现两种 schema。
--   加一条新约束是幂等的，且对已经写进去的 0 值**如实报错**而不是悄悄放行。
--
-- 与 3.1.2.2 的关系：`SkuDetailService` 写的是同一张表同一组列。收紧后
-- 3.1.2.2 若写入 0 会被这条约束拒绝 —— 这是**有意**的：两个入口对 0 的口径
-- 必须一致，否则「详情页存 0、3.7.2.1 算不出体积」就成了一条绕过规则 R1 的路。
ALTER TABLE sku_package ADD CONSTRAINT sku_package_dims_positive
  CHECK ((length_mm IS NULL OR length_mm>0)
     AND (width_mm IS NULL OR width_mm>0)
     AND (height_mm IS NULL OR height_mm>0));
ALTER TABLE sku_package ADD CONSTRAINT sku_package_net_weight_positive
  CHECK (net_weight_g IS NULL OR net_weight_g>0);

-- ============================================================ 二之三、触发器兜底（纵深防线）
--
-- 上一层的 CHECK 可以被 `ALTER TABLE ... DROP CONSTRAINT` 或迁移脚本绕过，触发器不行
-- （与 V21 / V22 / V23 同一纪律）。
--
-- 毛重 ≥ 净重是本模块对外最要紧的一条（算错它就是一张错的附加费面单），
-- 所以除了 V6 已有的 CHECK，再用一个触发器把同样的规则钉一遍并给出可读报错。
CREATE FUNCTION guard_sku_package_weights() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.net_weight_g IS NOT NULL AND NEW.gross_weight_g IS NOT NULL
    AND NEW.gross_weight_g < NEW.net_weight_g THEN
  RAISE EXCEPTION '毛重 % g 小于净重 % g：毛重必须不小于净重。包装本身也有重量、净重不含它，渠道按实重与体积重取大者计费，这个错值会直接变成一张错的附加费面单',
   NEW.gross_weight_g, NEW.net_weight_g USING ERRCODE='23514';
 END IF;
 IF NEW.net_weight_g IS NOT NULL AND NEW.net_weight_g<=0 THEN
  RAISE EXCEPTION '净重必须大于 0（当前 % g）：净重为 0 的 SKU 算不出实重，计费重会退化成只看体积重', NEW.net_weight_g USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_sku_package_weights BEFORE INSERT OR UPDATE ON sku_package
  FOR EACH ROW EXECUTE FUNCTION guard_sku_package_weights();

-- 危险品 ↔ 类别的一致性同样用触发器兜一遍（CHECK 与触发器互为纵深）。
CREATE FUNCTION guard_sku_safety_dangerous_class() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.dangerous_goods AND btrim(NEW.dangerous_substance_class)='' THEN
  RAISE EXCEPTION '勾了危险品就必须填写危险品类别：16 合规要按类别决定申报路径与所需证书，类别为空的危险品只能被退回来。请填 UN 编号 / IATA 类别代码 / 内部危险品编码之一' USING ERRCODE='23514';
 END IF;
 IF NOT NEW.dangerous_goods AND btrim(NEW.dangerous_substance_class)<>'' THEN
  RAISE EXCEPTION '没有勾危险品就不该填危险品类别（当前填了「%」）：这一行会同时告诉 10 物流「不危险」和 16 合规「有类别」，下游拿到两个互相矛盾的答案', NEW.dangerous_substance_class USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_sku_safety_dangerous_class BEFORE INSERT OR UPDATE ON sku_safety_attribute
  FOR EACH ROW EXECUTE FUNCTION guard_sku_safety_dangerous_class();

-- ============================================================ 3. 索引（每条都对应一个真实的查询路径）
--
-- 10 物流的渠道筛选页：「筛出所有带电的商品」/「筛出所有危险品」。
-- 四列组合索引让「四个标记全 false 的普通货」这类最高频查询走索引前缀。
CREATE INDEX sku_safety_flags
  ON sku_safety_attribute(tenant_id, contains_battery, contains_liquid, dangerous_goods, is_magnetic);
-- 危险品与带电是申报的高频入口，各给一条部分索引（行数远小于全表）。
CREATE INDEX sku_safety_dangerous
  ON sku_safety_attribute(tenant_id, sku_id) WHERE dangerous_goods;
CREATE INDEX sku_safety_battery
  ON sku_safety_attribute(tenant_id, sku_id) WHERE contains_battery;
-- 列表页默认排序（updated_at DESC, id DESC）——与 sku_list_updated 同一形状。
CREATE INDEX sku_safety_updated
  ON sku_safety_attribute(tenant_id, updated_at DESC, id DESC);
-- 16 合规按类别批量取待办：某类危险品有哪些 SKU。行数远小于全表，走部分索引。
CREATE INDEX sku_safety_substance_class
  ON sku_safety_attribute(tenant_id, dangerous_substance_class, sku_id) WHERE dangerous_goods;
-- 体积核算（8 拣货）按 SKU 取物理参数：sku_package 的 UNIQUE(tenant_id,sku_id) 已服务它。
-- 「还没采集过安全属性」的 SKU 在筛选页里要能快速捞出来：部分索引只收有结论的行，
-- 与之配对的反查（LEFT JOIN ... IS NULL）走 sku 表的 updated_at 索引即可，不另建。

-- ============================================================ 4. RLS 与授权
--
-- 与 V5 / V6 同一口径：租户级数据（BOM/包装都是租户共享的商品定义，不套 V3 的
-- owner_id / department_id 数据范围）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['sku_safety_attribute'] LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
   EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
  -- 可见性跟随 SKU（与 V6 的 sku_package 同一纪律）：看不到 SKU 就看不到它的安全属性。
  -- 没有这条，「按危险品筛选」会成为一个绕过页面权限的枚举口：只要能调筛选接口，
  -- 就能逐页试出别的租户有哪些危险品商品。
  CREATE POLICY data_scope ON sku_safety_attribute AS RESTRICTIVE
    USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_safety_attribute.tenant_id AND s.id=sku_safety_attribute.sku_id))
    WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=sku_safety_attribute.tenant_id AND s.id=sku_safety_attribute.sku_id));
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_safety_attribute TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_safety_attribute TO starcon_app;

-- ============================================================ 5. 留给下游的口子（不是本模块的缺口）
--
--   * **10 物流（渠道适配、体积重）**：读 `GET /catalog/physical-safety/skus/{skuId}` 拿到
--     物理档案 + 体积重（自己传除数，本模块不替它选除数）；
--     用 `GET /catalog/physical-safety/profiles?...` 按安全属性筛选可发某渠道的商品。
--     **渠道表、渠道规则、面单全部不在本库**（那是 10 的事实）。
--   * **16 合规（危险品与认证）**：读同一份安全属性决定是否拦下申报流程，
--     并按 `dangerous_substance_class` 取该类别需要的证书与申报路径。
--     **HS 编码、原产地、申报品名、完整申报要素与认证证书一律不在本表**
--     （那是 3.7.1.1 / 3.7.1.2 的事实：前者见 V94，后者仍未开发，见第一节之二）。
--   * **08 拣货（装箱体积核算）**：用 `POST /catalog/physical-safety/carton-calc`
--     传 SKU 行与箱规，算每箱可装件数与箱数。
