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
-- **不建 UN 编号 / 危险品类别 / 电池类型列**：那是 3.7.1.1「HS 编码与申报要素」的职责，
-- 那个模块当前**未开发**且台账已记录它被 `product_compliance` 的表结构缺陷阻塞。
-- 本模块只回答「是否需要特殊申报」，**不回答「申报什么」**——后者是 3.7.1.1 的事实。
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
  -- SKU 被删则安全属性一起删：留下孤儿行会让筛选查询算出「不可发货」的 SKU。
  FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE
);

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
--   * **16 合规（危险品与认证）**：读同一份安全属性决定是否拦下申报流程。
--     **UN 编号、危险品类别、认证证书一律不在本表**（那是 3.7.1.1 的事实，见第一节）。
--   * **08 拣货（装箱体积核算）**：用 `POST /catalog/physical-safety/carton-calc`
--     传 SKU 行与箱规，算每箱可装件数与箱数。
