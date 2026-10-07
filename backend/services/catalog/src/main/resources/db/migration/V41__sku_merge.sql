-- 3.9.1.2 SKU 合并：执行归并、迁移「当前引用」、原记录标记合并并停用、可回溯合并来源。
--
-- 蓝图 3.9.1.2 的原话：
--   「职责：执行 SKU/SPU 合并：把重复记录归并到主记录，迁移关联（listing 映射、库存 bucket、
--     采购历史、价格、订单快照保留指向），原记录标记合并并停用而非物理删除；可回溯合并来源。」
--   「输入：合并决定（主记录、被合并记录）；`expected_version`。」
--   「输出：合并结果；引用迁移报告；原记录合并标记。」
--   「关系：上游 = 3.9.1.1；下游 = 04 映射、07 库存、06 采购。历史订单行冻结当时 SKU 快照，
--     不随合并回溯改写（实体目录 §3.6）——只迁移"当前引用"（映射/库存/价格），历史事实保持冻结。」
--
-- ============================================================ 本表只承接「决定」，不重新判断
--
-- 3.9.1.1 的 `sku_duplicate_decision` 已经把「主记录 / 被合并记录 / 理由 / 登记时刻」记成了
-- 一条可查的事实，本模块**不重新扫描、不重新打分、不改判**：它拿到一条 `decision='MERGE'` 的
-- 决定，照着执行。所以 `sku_merge` 对 `sku_duplicate_decision` 建 FK，
-- 合并来源因此天然可回溯，不需要另建一张「合并来源表」再手工维护对应关系。
--
-- ============================================================ expected_version 在这里的落法
--
-- 蓝图把 `expected_version` 写成本模块的输入。V36 把它留在
-- `sku_duplicate_decision.primary_version / secondary_version`（登记那一刻的 `sku.version` 快照）。
-- 本模块**照着那个快照比对**：
--   * 对得上 → 登记之后没人动过这两条商品，合并的前提仍然成立 → 放行；
--   * 对不上 → 有人在登记之后改过主记录或被合并记录（「像不像」的结论、报价、包装都可能变了）
--     → 409 让人重新确认，而不是拿一份已经过期的决定去改数据。
-- 两端的实际版本也一起存进 `sku_merge`（`actual_*`），这样事后能回答
-- 「当时到底比的是哪一版」，而不必去翻已滚动的历史。
--
-- ============================================================ 1. 原记录的合并标记（sku / spu 增列）
--
-- 蓝图：「原记录标记合并并停用而非物理删除」。这两件事落成两类列：
--   * `merged_into_*` = **合并来源指针**（「可回溯合并来源」的本体：任意一条 SKU 都能反查
--     「它被并进了哪一条」，而主记录也能正查「有哪几条被并进来了」）；
--   * `merged_at / merged_by` = 合并那一刻的时刻与操作人。
--
-- **为什么不给 sku.status 加 'MERGED' 枚举值**：V1 的
-- `CHECK(status IN ('DRAFT','ACTIVE','INACTIVE','ARCHIVED'))` 是四值状态域，
-- 3.1.1.3 的 `sku_status_event` 也按这四值记账。加第五个枚举值要同时改 CHECK、事件表 CHECK
-- 与所有按状态过滤的下游（3.1.2.1 列表、3.8.1.1 导入、刊登预检…），
-- 属于跨模块的状态域扩张，而本模块要表达的语义是**「它被合并了」而不是「它有第五种状态」**。
-- 所以：状态落 `INACTIVE`（停用，落在既有四值域内，列表与刊登自然过滤掉），
-- 合并这件事的事实由 `merged_into_*` 表达。`merged_into_*` 非空 ⟺ 已被合并，
-- 两者同时成立由下面的 CHECK 钉死，DB 层不会出现「标了 INACTIVE 但没说是被合并的」
-- 与「说是被合并的却还在 ACTIVE」这两种自相矛盾的行。
--
-- **不物理删除的另一个硬理由**：`sku_unit_conversion_snapshot` / `import_job_row` /
-- `bom_explosion_line` 这些历史行都带 `ON DELETE CASCADE` 指向 sku，
-- 删掉被合并记录等于连带删掉采购单行冻结快照、导入历史与爆炸结果 ——
-- 那正是蓝图「历史事实保持冻结」明令禁止的。
--
-- **合并链保持一层**：`merged_into_sku_id` 可以指向任意 SKU，但服务层拒绝把一条
-- **已经被合并过**的记录再作为主记录或被合并记录（见 S41 服务层 S2）。
-- 合并链一旦成环（A 并进 B、B 又并进 A），「主记录」就失去了含义，
-- 而一条多跳链会让「这个 SKU 的当前有效归属」变成一个需要递归查询的问题。
ALTER TABLE sku ADD COLUMN merged_into_sku_id uuid;
ALTER TABLE sku ADD COLUMN merged_at timestamptz;
ALTER TABLE sku ADD COLUMN merged_by uuid;
ALTER TABLE spu ADD COLUMN merged_into_spu_id uuid;
ALTER TABLE spu ADD COLUMN merged_at timestamptz;
ALTER TABLE spu ADD COLUMN merged_by uuid;

-- 复合外键带 tenant_id：跨租户挂合并关系在 DB 层不可能发生
-- （与 V36 引用 sku/spu 的同一手法）。
ALTER TABLE sku ADD CONSTRAINT sku_merged_into_fk
  FOREIGN KEY(tenant_id, merged_into_sku_id) REFERENCES sku(tenant_id, id);
ALTER TABLE spu ADD CONSTRAINT spu_merged_into_fk
  FOREIGN KEY(tenant_id, merged_into_spu_id) REFERENCES spu(tenant_id, id);

ALTER TABLE sku ADD CONSTRAINT sku_merged_shape CHECK(
  (merged_into_sku_id IS NULL AND merged_at IS NULL AND merged_by IS NULL)
  OR (merged_into_sku_id IS NOT NULL AND merged_at IS NOT NULL AND merged_by IS NOT NULL));
-- 自己并进自己是自指死循环，DB 层直接拒绝（服务层也会挡，这里是第二道）。
ALTER TABLE sku ADD CONSTRAINT sku_merged_not_self CHECK(merged_into_sku_id IS NULL OR merged_into_sku_id <> id);
-- **被合并 = 必须停用**：允许「标了合并但还是 ACTIVE」的话，刊登预检与列表页
-- 会继续把一条已经归并掉的记录当作在售主记录，那比不合并更糟。
ALTER TABLE sku ADD CONSTRAINT sku_merged_is_inactive CHECK(merged_into_sku_id IS NULL OR status = 'INACTIVE');

ALTER TABLE spu ADD CONSTRAINT spu_merged_shape CHECK(
  (merged_into_spu_id IS NULL AND merged_at IS NULL AND merged_by IS NULL)
  OR (merged_into_spu_id IS NOT NULL AND merged_at IS NOT NULL AND merged_by IS NOT NULL));
ALTER TABLE spu ADD CONSTRAINT spu_merged_not_self CHECK(merged_into_spu_id IS NULL OR merged_into_spu_id <> id);
-- SPU 侧对应 3.1.1.3 生命周期的**终态** DISCONTINUED（停产，终态不可再流转）：
-- 合并掉的商品不允许被「恢复」回来 —— 恢复它等于把已经归并掉的重复建档又放回商品列表。
ALTER TABLE spu ADD CONSTRAINT spu_merged_is_discontinued
  CHECK(merged_into_spu_id IS NULL OR status = 'DISCONTINUED');

-- ============================================================ 2. 合并事实（一行 = 一次执行的归并）
CREATE TABLE sku_merge (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 溯源到 3.9.1.1 的登记决定：合并来源不需要另建关系表，靠这三列 + 下面的 FK 就够了。
  decision_id uuid NOT NULL,
  candidate_id uuid NOT NULL,
  scan_id uuid NOT NULL,
  -- 归并的两端。primary = 保留（主记录），secondary = 被合并（原记录，停用并打标记）。
  primary_sku_id uuid NOT NULL,
  secondary_sku_id uuid NOT NULL,
  primary_spu_id uuid NOT NULL,
  secondary_spu_id uuid NOT NULL,
  -- 执行范围：两条 SKU 同一个 SPU → 'SKU'；跨 SPU（同商品不同 SPU 重复建档，3.9.1.1 的
  -- CROSS_SPU 维度）→ 'SPU'，此时源 SPU 连带停产（见下面 secondary_spu_* 三列）。
  scope text NOT NULL CHECK(scope IN ('SKU','SPU')),
  -- expected_version 比对：登记时（expected）与执行时（actual）两端的 sku.version。
  -- 四个数都存下来，是因为「当时比的是哪一版」是事后唯一能自证的凭据。
  expected_primary_version bigint NOT NULL CHECK(expected_primary_version >= 0),
  expected_secondary_version bigint NOT NULL CHECK(expected_secondary_version >= 0),
  actual_primary_version bigint NOT NULL CHECK(actual_primary_version >= 0),
  actual_secondary_version bigint NOT NULL CHECK(actual_secondary_version >= 0),
  -- 两端的身份快照。合并之后主记录仍可改名、原记录仍可被再次编辑，
  -- 而「这条合并当初是把哪两条并起来的」必须永远答得出来，所以在这里留一份合并那一刻的编码与名称。
  primary_code varchar(64) NOT NULL,
  primary_name varchar(200) NOT NULL,
  secondary_code varchar(64) NOT NULL,
  secondary_name varchar(200) NOT NULL,
  secondary_status_before text NOT NULL,
  secondary_status_after text NOT NULL,
  -- 源 SPU 的退役情况（scope='SKU' 时两列留空串、计数为 0：源 SPU 没被碰过，
  -- 这本身就是报告里要显示的事实 —— 「源商品还在，只并掉了一条 SKU」）。
  secondary_spu_status_before text NOT NULL DEFAULT '',
  secondary_spu_status_after text NOT NULL DEFAULT '',
  -- 源 SPU 上还剩几条**没被这次合并处理**的 SKU。它们随源 SPU 一起停产，
  -- 但引用一条都没动：把它们搬到主 SPU 需要逐条判断它们是不是同一个商品，
  -- 而登记的决定只覆盖了「这一对 SKU」，不是「这一整个 SPU 的所有 SKU」。
  -- 数出来是为了让运营看得见这个边界，而不是以为整个 SPU 都被搬空了。
  secondary_spu_remaining_sku_count int NOT NULL DEFAULT 0 CHECK(secondary_spu_remaining_sku_count >= 0),
  -- 引用迁移报告的合计（分项在 sku_merge_reference 里逐维度展开）。
  -- 五个计数都是 int 而非 bigint：一条 SKU 的引用再密也远小于 2^31，
  -- 而它们的用途是给人看的一个数，不是累加账。
  migrated_count int NOT NULL DEFAULT 0 CHECK(migrated_count >= 0),
  summed_count int NOT NULL DEFAULT 0 CHECK(summed_count >= 0),
  deduplicated_count int NOT NULL DEFAULT 0 CHECK(deduplicated_count >= 0),
  conflict_count int NOT NULL DEFAULT 0 CHECK(conflict_count >= 0),
  frozen_count int NOT NULL DEFAULT 0 CHECK(frozen_count >= 0),
  note varchar(1000) NOT NULL DEFAULT '',
  executed_by uuid NOT NULL,
  executed_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一份决定只执行一次：重复提交同一个决定不会产生第二次归并
  -- （接口另有 expected_version 乐观锁，这里是 DB 层的第二道）。
  UNIQUE(tenant_id, decision_id),
  -- 一条记录只能被合并掉一次：它被并进 A 之后不可能再被并进 B。
  UNIQUE(tenant_id, secondary_sku_id),
  -- 合并链保持一层在 DB 层的落点：secondary 与 primary 都必须是「没被合并过」的。
  -- 跨行约束（要看 sku.merged_into_sku_id），由服务层 S2 保证并由集成测试逐条断言。
  FOREIGN KEY(tenant_id, decision_id) REFERENCES sku_duplicate_decision(tenant_id, id),
  FOREIGN KEY(tenant_id, candidate_id) REFERENCES sku_duplicate_candidate(tenant_id, id),
  FOREIGN KEY(tenant_id, primary_sku_id) REFERENCES sku(tenant_id, id),
  FOREIGN KEY(tenant_id, secondary_sku_id) REFERENCES sku(tenant_id, id),
  -- 把一条并进它自己毫无意义，与 V36 的决定表同一条 CHECK。
  CHECK(primary_sku_id <> secondary_sku_id),
  -- 比对必须真的过了才允许落这一行：expected 与 actual 不相等就存下来，
  -- 等于把「比对着呢」这件事留成一句可以被质疑的话。
  CHECK(expected_primary_version = actual_primary_version),
  CHECK(expected_secondary_version = actual_secondary_version),
  -- 'SPU' 范围才允许带源 SPU 的状态；'SKU' 范围必须两个状态列都空着。
  CHECK((scope = 'SPU') = (secondary_spu_status_after <> ''))
);

-- ============================================================ 3. 引用迁移报告（逐维度一行）
--
-- 蓝图输出点名「引用迁移报告」。为什么必须是**逐维度**而不是一个总数：
-- 运营要判断的是「合并不合并得动」，而「报价 3 条冲突、包装 1 行冲突、素材 2 张已去重」
-- 三种情况的处理成本完全不同 —— 一个混在一起的数字答不了这个问题。
--
-- 五个计数的语义（服务层保证 migrated+summed+deduplicated+conflict = source-count 可行部分，
-- frozen 单列，因为它属于「本来就不该动」而不是「没搬动」）：
--   source_count      被合并记录在被扫描这一维度上的原始行数
--   migrated_count    改指主记录的行数
--   summed_count      并进主记录已有同行（用量相加）后消失的行数 —— 组件类引用的常态
--   deduplicated_count 与主记录重复、被删掉的行数（素材：同一张图本来两条 SKU 都挂着）
--   conflict_count    因唯一约束/业务规则搬不动、**留在被合并记录上**的行数
--   frozen_count      属于历史事实、刻意不动的行数（采购单行冻结快照、生命周期事件、导入历史…）
CREATE TABLE sku_merge_reference (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, merge_id uuid NOT NULL,
  -- 维度词表与 V36 的九类引用刻意对齐（BARCODE / ATTRIBUTE / MEDIA / QUOTE / BUNDLE /
  -- BOM / PACKAGE / SAFETY / UNIT_CONVERSION），另加三类 catalog 侧当前引用
  -- （TRANSLATION 多语言标题 / BUNDLE_SKU 套装代表 / BOM_OUTPUT BOM 产出物）
  -- 与五类冻结历史（见下）。对齐的理由：候选详情页上给运营看的正是 V36 那九个数字，
  -- 合并报告如果换一套词表，页面上「引用 3 条」与报告里「冲突 3 条」就对不上了。
  dimension text NOT NULL CHECK(dimension IN (
    'BARCODE','ATTRIBUTE','MEDIA','TRANSLATION','QUOTE','BUNDLE','BUNDLE_SKU',
    'BOM_OUTPUT','BOM','PACKAGE','SAFETY','UNIT_RULE',
    'UNIT_CONVERSION_SNAPSHOT','STATUS_EVENT','IMPORT_HISTORY','TITLE_HISTORY',
    'RENDER_ARTIFACT','TRANSLATION_HISTORY','BOM_EXPLOSION_HISTORY','CODE_HISTORY',
    'DUPLICATE_HISTORY')),
  label varchar(64) NOT NULL,
  -- 这一维度采取的动作：MOVE 改指主记录 / SUM 用量相加并入已有行 / FROZEN 刻意不动。
  action text NOT NULL CHECK(action IN ('MOVE','SUM','FROZEN','NONE')),
  source_count int NOT NULL DEFAULT 0 CHECK(source_count >= 0),
  migrated_count int NOT NULL DEFAULT 0 CHECK(migrated_count >= 0),
  summed_count int NOT NULL DEFAULT 0 CHECK(summed_count >= 0),
  deduplicated_count int NOT NULL DEFAULT 0 CHECK(deduplicated_count >= 0),
  conflict_count int NOT NULL DEFAULT 0 CHECK(conflict_count >= 0),
  frozen_count int NOT NULL DEFAULT 0 CHECK(frozen_count >= 0),
  -- 整维度被拒绝迁移（当前只有跨 SPU 时的属性值，见下）。它是**独立于 conflict_count 的一列**而不是
  -- 从计数里推断出来的：conflict 的含义是「主记录本身已有同类数据，搬不过去」，
  -- blocked 的含义是「这个维度在原理上就不能跨 SPU 搬」，两者的处置完全不同 ——
  -- 前者要人工去解冲突，后者要换一个合并范围才能处理。混在一起的话，
  -- 页面只能显示一个「搬不动」的总数，运营分不清该干什么。
  -- 被拒维度按「全部计入 conflict_count」落账（source_count = conflict_count = frozen_count = 0），
  -- 所以五个计数仍能对上「被合并记录在这张表上原本有几行」。
  blocked boolean NOT NULL DEFAULT false,
  -- 这一维度为什么是这个动作 / 为什么搬不动，写给人看。
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一次合并一个维度一行：重复行会让合计数与分项对不上。
  UNIQUE(tenant_id, merge_id, dimension),
  FOREIGN KEY(tenant_id, merge_id) REFERENCES sku_merge(tenant_id, id) ON DELETE CASCADE
);

-- ============================================================ 4. 索引
--
-- 「这条 SKU 被并进了哪一条」：任意 SKU 详情页都要反查合并来源。
CREATE INDEX sku_merged_into ON sku(tenant_id, merged_into_sku_id) WHERE merged_into_sku_id IS NOT NULL;
CREATE INDEX sku_merged_marker ON sku(tenant_id, merged_at DESC) WHERE merged_into_sku_id IS NOT NULL;
CREATE INDEX spu_merged_into ON spu(tenant_id, merged_into_spu_id) WHERE merged_into_spu_id IS NOT NULL;
-- 合并历史列表：按执行时间倒序（治理动作的审计视角）。
CREATE INDEX sku_merge_by_time ON sku_merge(tenant_id, executed_at DESC, id DESC);
-- 「这个候选的决定执行了吗」与「主记录被并进来过几批」，两者都从端点的主查询路径上。
CREATE INDEX sku_merge_by_primary ON sku_merge(tenant_id, primary_sku_id, executed_at DESC);
CREATE INDEX sku_merge_by_secondary ON sku_merge(tenant_id, secondary_sku_id);
-- 报告页按动作筛选（只看待搬的 / 只看被冻住的）。
CREATE INDEX sku_merge_reference_by_action ON sku_merge_reference(tenant_id, merge_id, action);

-- ============================================================ 5. RLS 与授权
--
-- 合并报告里装着商品编码、名称、条码与报价数量，是本租户最敏感的一块主数据面，
-- 所以两表都 **FORCE RLS**：表 owner 也绕不过去。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['sku_merge','sku_merge_reference'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON sku_merge, sku_merge_reference TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_merge, sku_merge_reference TO starcon_app;

-- ============================================================ 6. 边界（这一节是本模块最要紧的部分）
--
--   * **只迁移「当前引用」，历史事实一律冻结**（蓝图关系行 + 实体目录 §3.6）。具体到表：
--       - 迁移：条码 / 素材引用 / 多语言标题 / 供应商报价（蓝图的「价格」）/ 套装组件 /
--         套装代表 / BOM 产出物 / BOM 组件 / 包装参数 / 安全属性 / 单位换算规则 / 属性值。
--       - 冻结：单位换算**快照**（采购单/入库单的行冻结，document_ref+line_no 指向真实单据行）、
--         SKU 状态事件、导入作业行、标题生成记录、内容渲染产物、翻译批次与建议、
--         BOM 爆炸运行与明细、编码分配与批量操作历史。
--     冻结的这些行**继续指向被合并记录**，这不是漏搬而是刻意如此：
--     它们是「当时长什么样」的证据，搬走它们等于让采购单上的行指向一个合并后才存在的身份。
--   * **04 映射 / 07 库存 / 06 采购的引用不在本服务库里**（模块边界纪律）。
--     本模块不跨库读、不跨库写：listing 映射、库存 bucket、订单行那部分要走各自的授权 API，
--     由下游在合并之后调用。catalog 这一侧做不了也不假装做了 ——
--     合并记录里存的是 catalog 自有引用的分项报告。
--   * **不做物理删除**：见第一节，被合并记录停用 + 打标记，历史行的 CASCADE 才不会把它们带走。
--   * **不新增词表**：权限沿用 `catalog.spu.read` / `catalog.spu.update`（与 3.9.1.1 同一口径）。
