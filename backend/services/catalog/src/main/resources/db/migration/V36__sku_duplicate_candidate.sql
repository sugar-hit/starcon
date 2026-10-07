-- 3.9.1.1 重复 SKU 识别：扫出重复/近重复候选、给相似度与重叠维度、登记人工决定。
--
-- 蓝图 3.9.1.1 的原话：
--   「职责：识别重复/近重复商品：同 SPU 下重复规格组合、同商品不同 SPU 重复建档、
--     同条码多 SKU、近似标题+相似图片疑似同品，输出候选与相似度供人工确认。」
--   「输入：商品与 SKU 全量；条码、标题、属性、图片特征。」
--   「输出：重复候选清单（相似度、重叠维度、影响引用数）；确认合并/保留决定。」
--   「关系：上游 = 3.1.1.1/3.1.2.1；下游 = 3.9.1.2。重复 SKU 会污染销量、采购、库存与利润统计，
--     同一商品被拆成两条，各算各的，治理的价值在于报表可信。」
--
-- ============================================================ 本模块只「识别 + 登记决定」，不合并
--
-- 蓝图把「确认合并/保留决定」写在本模块的**输出**里，而把「执行合并」写在 3.9.1.2 的职责里。
-- 这条边界在本文件里落成三件事：
--   * `sku_duplicate_decision` 登记人工决定（MERGE / KEEP + 主记录 + 理由 + 决定时刻）；
--   * 它**同时记下登记那一刻两个 SKU 的 `version`**，那是 3.9.1.2 说的 `expected_version` ——
--     执行合并前必须带着它回来比对，对不上说明登记之后商品被人改过，合并要重新确认；
--   * 本模块**不改 sku / spu 的任何一列**，也不动 listing、库存、采购、价格（那是 3.9.1.2 与
--     04/07/06 的写入所有权）。历史订单行冻结当时 SKU 快照，不随合并回溯改写（实体目录 §3.6）。
--
-- ============================================================ 四条检测线对应蓝图的四类重复
--
-- 维度词表与蓝图那四类一一对应，不是实现自创的分类：
--   BARCODE              「同条码多 SKU」—— 最硬的一条：条码是外部唯一标识，
--                                       同一个条码挂在两个 SKU 上，扫码拣货就必然有一个拿错。
--   VARIANT_COMBINATION  「同 SPU 下重复规格组合」—— 按**轴值现算**的组合签名比对，
--                                       不读 `sku.variant_signature`（理由见下）。
--   CROSS_SPU            「同商品不同 SPU 重复建档」—— 品牌 + 类目 + 归一化标题都对上。
--   SIMILAR_PRODUCT       「近似标题 + 相似图片疑似同品」—— 标题相似**且**图片有重合，
--                                       两条同时成立才算（理由见信号表注释）。
--
-- 为什么同 SPU 重复规格组合还能被发现（V1 已经 UNIQUE(tenant_id,spu_id,variant_signature)）：
--   那条唯一约束管的是**存进去的签名文本**，而签名文本不总是轴值的真实投影 ——
--   3.8.1.1 导入向导对批量导入的 SKU 写的是 `IMPORT:<编码>` 这种占位签名（V29 上游做法，
--   它要满足同 SPU 唯一约束又不要求运营先建轴），手工建的 SKU 也可能带着历史签名文本。
--   于是两个 SKU 完全可能承载**同一套真实规格取值**而签名文本不同，唯一约束拦不住。
--   本模块按 3.1.3.1 的同一套办法现算：`attribute_value`（state='SET' 且 sku_id 非空）
--   × `attribute_definition.variant_axis` × `attribute_value_item.option_value`
--   → `CombinationRules.signature`，与是否真的比对过轴值无关，只用签名文本本身当身份的
--   那些 SKU（一个轴值都解析不出来的）不参与本维度，见 V36 服务层注释。
--
-- ============================================================ 为什么一张候选表 + 一张信号表
--
-- 蓝图要的清单有两栏：「相似度」与「**重叠维度**」。后者意味着**同一对商品可能被多条线同时命中**
-- （条码撞了 + 标题也像），而运营的判断恰恰依赖这件事：只中一条和四条全中，处理优先级完全不同。
-- 所以：
--   * `sku_duplicate_candidate` 一行 = **一对 SKU**，UNIQUE(tenant_id,scan_id,left,right)，
--     `overlap_dimension_count` 就是「几条线同时命中」，候选页上那一栏读它；
--   * `sku_duplicate_signal` 一行 = **一对 SKU 上的一个维度**，带该维度的独立得分与证据，
--     详情页逐条列给人看，也让人能回答「凭什么说它像」。
-- 若按维度各存一行候选，同一对会散成四行，「重叠维度」这一栏就无处可查 —— 而它正是蓝图点名的输出。
--
-- 左右两侧**按 uuid 字符串升序**存放并在 DB 层 CHECK 钉死：
--   同一对 (X,Y) 与 (Y,X) 必须是同一行，否则扫描顺序一变，UNIQUE 就拦不住，
--   候选清单里会出现两条内容完全一样的行，人数与决策数都会凭空翻倍。
--
-- ============================================================ 1. 扫描运行
--
-- 每一次扫描是一份**独立且不可变**的结论（显式动作，不是每次查询现算）。
-- 与 V34 校验报告的差别在于：那边预校验会就地覆盖 `import_row_error`，所以必须做快照；
-- 这边候选行挂在扫描上、从不覆盖，所以不需要 V34 那种「报告已过期」标记，
-- 也不需要「一个任务一份当前报告」的唯一约束 —— 历史扫描就是要留着对比的
-- （「治理之后重新扫一次，候选数从 87 降到 6」这句话需要两次扫描都在）。
CREATE TABLE sku_duplicate_scan (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 本次判定阈值：低于它的信号不成候选。记下来是因为「为什么这份清单里有/没有某对」
  --   答案是阈值 + 口径，不是一个可以事后猜的数。
  min_similarity numeric(5,4) NOT NULL CHECK(min_similarity>=0 AND min_similarity<=1),
  -- 扫描范围（空串 = 全量）。支持按类目 / 品牌收窄是为了让大租户能分片扫，
  --   代价是**可能漏掉跨范围的重复**，所以范围非空时页面必须显式提示（见 scopeApplied）。
  scope_category varchar(100) NOT NULL DEFAULT '',
  scope_brand varchar(100) NOT NULL DEFAULT '',
  scanned_sku_count int NOT NULL CHECK(scanned_sku_count>=0),
  scanned_spu_count int NOT NULL CHECK(scanned_spu_count>=0),
  -- 下面的三个计数是**扫描那一刻**的快照。
  -- candidate_count 之后可能变小：候选行在参与比较的 SKU 被硬删除时随之删除（停用不影响），
  --   所以它是这份扫描的**上界**；要当前值读候选表，不要读这里的数字。
  candidate_count int NOT NULL CHECK(candidate_count>=0),
  signal_count int NOT NULL CHECK(signal_count>=0),
  -- 范围收窄了 = 可能漏检，页面据此提示。
  scope_applied boolean NOT NULL DEFAULT false,
  started_at timestamptz NOT NULL,
  finished_at timestamptz NOT NULL,
  duration_ms bigint NOT NULL CHECK(duration_ms>=0),
  actor_id uuid NOT NULL,
  UNIQUE(tenant_id,id),
  -- 范围标记必须与两个范围列一致，不能只靠应用层自觉（页面要靠它决定显不显示漏检提示）。
  CHECK(scope_applied = (btrim(scope_category)<>'' OR btrim(scope_brand)<>'')),
  CHECK(finished_at>=started_at)
);

-- ============================================================ 2. 重复候选（一对 SKU = 一行）
CREATE TABLE sku_duplicate_candidate (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, scan_id uuid NOT NULL,
  left_sku_id uuid NOT NULL, right_sku_id uuid NOT NULL,
  left_spu_id uuid NOT NULL, right_spu_id uuid NOT NULL,
  -- 总体相似度：各维度得分的**最大值**。
  -- 取最大而不是加权平均：条码撞了是 1.0000，标题像只有 0.86，两条同时命中时
  -- 加权平均会把「条码已经撞了」这个决定性事实稀释掉（0.93 看起来反而不如纯标题像的 0.86 严重）。
  -- 「四条都中」这件事由 overlap_dimension_count 表达，两个数各有各的职责。
  similarity numeric(5,4) NOT NULL CHECK(similarity>=0 AND similarity<=1),
  -- 重叠维度数（1~4）：与 `sku_duplicate_signal` 里该候选的行数一一对应（跨表约束，
  --   由 DuplicateDetectionService 写入时保证，集成测试逐候选断言两者相等）。
  overlap_dimension_count int NOT NULL CHECK(overlap_dimension_count>=1 AND overlap_dimension_count<=4),
  -- 影响引用数：**catalog 自有的**引用行数（条码 / 素材 / 属性值 / 报价 / 套装组件 /
  --   BOM 组件 / 包装 / 安全属性 / 单位换算快照，见服务层 referenceCounts 的口径注释）。
  -- 为什么不把 07 库存 / 04 映射 / 09 订单的引用算进来：那些是别的服务的库，
  --   跨库读是模块边界纪律明令禁止的（AGENTS.md），3.9.1.2 执行合并时要拿那些数
  --   走各自的授权 API。这里给出的就是 catalog 能负责到底的那部分，并逐项分维度列出，
  --   运营看得到「条码 2 个、素材 1 张、报价 3 条」，而不是一个混着别家数据的总数。
  left_reference_count int NOT NULL CHECK(left_reference_count>=0),
  right_reference_count int NOT NULL CHECK(right_reference_count>=0),
  -- 给运营看的一句话结论（命中了哪几条线、依据是什么）。
  reason varchar(500) NOT NULL CHECK(btrim(reason)<>''),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一份扫描里一对 SKU 只能有一行：候选的粒度是「一对」，不是「一条线」。
  UNIQUE(tenant_id,scan_id,left_sku_id,right_sku_id),
  -- 候选行随扫描删除（整份结论作废）；随 SKU 硬删除也删除 —— 一旦有一侧不存在了，
  --   「这两条疑似重复」这个结论就没有对象了，而且用 RESTRICT 的话
  --   「删掉这个草稿 SKU」会被一个用户无法处理的引用挡住。
  --   注意：主数据纪律是**停用而非删除**（实体目录 §5），所以正常治理路径走不到这里。
  FOREIGN KEY(tenant_id,scan_id) REFERENCES sku_duplicate_scan(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,left_sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,right_sku_id) REFERENCES sku(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,left_spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,right_spu_id) REFERENCES spu(tenant_id,id) ON DELETE CASCADE,
  -- 一对必须是两个不同的 SKU（自己跟自己重复没有意义）。
  CHECK(left_sku_id<>right_sku_id),
  -- 左右规范化方向在 DB 层钉死（uuid::text 与 Java 的 UUID#toString 同样是 32 位小写十六进制，
  -- 两侧同序）。少了它，(X,Y) 与 (Y,X) 能各插一行，清单里会出现两条一模一样的候选。
  CHECK(left_sku_id::text < right_sku_id::text)
);

-- ============================================================ 3. 逐维度得分与证据
--
-- 详情页要让人能回答「凭什么说它像」，所以每条信号都带**可核对的证据**而不是一个孤零零的分数：
-- BARCODE 会写明撞的是哪个条码值，VARIANT_COMBINATION 写明是哪个轴、哪个取值撞了，
-- CROSS_SPU 写明品牌与类目，SIMILAR_PRODUCT 写明标题重合度与重合了几张图。
CREATE TABLE sku_duplicate_signal (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, scan_id uuid NOT NULL, candidate_id uuid NOT NULL,
  -- 四个维度与蓝图的四类重复一一对应（文件头有对照表）。
  dimension text NOT NULL
    CHECK(dimension IN ('BARCODE','VARIANT_COMBINATION','CROSS_SPU','SIMILAR_PRODUCT')),
  score numeric(5,4) NOT NULL CHECK(score>=0 AND score<=1),
  evidence varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一对 SKU 的同一个维度只有一条信号：重复写入会让「重叠维度数」与实际行数对不上。
  UNIQUE(tenant_id,candidate_id,dimension),
  FOREIGN KEY(tenant_id,candidate_id) REFERENCES sku_duplicate_candidate(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 4. 人工决定（MERGE / KEEP）
--
-- 蓝图：「输出：……确认合并/保留决定。」决定是**一条可查的事实**，不是一次性的动作：
-- 谁在什么时候把哪一对判成合并/不合并、理由是什么、保留哪一条，都要能回溯。
-- 一个候选一份当前决定（重选就原地改，带 version 乐观锁），不存逐轮流水 ——
--   审计流水是 17.5.3.1 的职责，本模块不造第二个审计表。
CREATE TABLE sku_duplicate_decision (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, scan_id uuid NOT NULL, candidate_id uuid NOT NULL,
  decision text NOT NULL CHECK(decision IN ('MERGE','KEEP')),
  -- MERGE 时必填：保留哪一条（primary）与被合并掉哪一条（secondary）。
  -- 必须是该候选两侧之一 —— 跨表约束，由服务层保证（集成测试断言指向第三个 SKU 会被拒）。
  primary_sku_id uuid,
  secondary_sku_id uuid,
  -- **为下游 3.9.1.2 预留的 expected_version**：
  -- 登记决定那一刻两个 SKU 的 `sku.version` 快照。3.9.1.2 执行合并要带着它回来比对，
  -- 对不上就说明「登记完决定之后商品又被改过」，此时合并的前提已经不成立，必须重新确认。
  -- 本模块只登记不执行，所以这两列此刻只是快照，不是锁。
  primary_version bigint,
  secondary_version bigint,
  note varchar(1000) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  decided_by uuid NOT NULL,
  decided_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,candidate_id),
  FOREIGN KEY(tenant_id,candidate_id) REFERENCES sku_duplicate_candidate(tenant_id,id) ON DELETE CASCADE,
  -- 「判成合并」与「指定了主记录」必须是同一件事：只给其中一个是自相矛盾的登记。
  CHECK((decision='MERGE') = (primary_sku_id IS NOT NULL)),
  CHECK((decision='MERGE') = (secondary_sku_id IS NOT NULL)),
  -- 主被合并的必须是两个不同的 SKU，否则「合并」就是把一条并进它自己。
  CHECK(primary_sku_id IS NULL OR primary_sku_id<>secondary_sku_id)
);

-- ============================================================ 5. 索引
--
-- 候选页主路径：某次扫描的候选清单，按相似度倒序（最该先看的排最前），分页 + 筛选。
CREATE INDEX sku_duplicate_candidate_by_scan ON sku_duplicate_candidate(tenant_id, scan_id, similarity DESC, id);
-- 「只看待确认的」「只看判了合并的」这类筛选挂在扫描上再按决定走。
CREATE INDEX sku_duplicate_candidate_by_pair ON sku_duplicate_candidate(tenant_id, left_sku_id, right_sku_id);
-- 逐维度信号：详情页逐条列，以及「这一条线命中了多少对」这种统计。
CREATE INDEX sku_duplicate_signal_by_dimension ON sku_duplicate_signal(tenant_id, scan_id, dimension, score DESC);
CREATE INDEX sku_duplicate_signal_by_candidate ON sku_duplicate_signal(tenant_id, candidate_id, dimension);
-- 扫描列表（按时间倒序）与决定列表。
CREATE INDEX sku_duplicate_scan_by_time ON sku_duplicate_scan(tenant_id, started_at DESC, id DESC);
CREATE INDEX sku_duplicate_decision_by_decision ON sku_duplicate_decision(tenant_id, candidate_id, decision);

-- ============================================================ 6. RLS 与授权
--
-- 四张表全部租户级数据，一次扫描跨成百上千对商品，逐对判部门归属既不可能也没有意义：
-- 判定的对象是「这份候选清单能不能给本租户看」，由 tenant_id 隔离与页面权限两层把关。
-- 候选清单里含的是本租户商品的标题、品牌与条码，属于敏感面，
-- 所以 **FORCE RLS** 让表 owner 也绕不过去。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['sku_duplicate_scan','sku_duplicate_candidate','sku_duplicate_signal','sku_duplicate_decision'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  sku_duplicate_scan, sku_duplicate_candidate, sku_duplicate_signal, sku_duplicate_decision TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  sku_duplicate_scan, sku_duplicate_candidate, sku_duplicate_signal, sku_duplicate_decision TO starcon_app;

-- ============================================================ 7. 留给下游 / 本模块的边界
--
--   * **3.9.1.2 SKU 合并**：读 `sku_duplicate_decision` 的 MERGE 决定 + `primary_version`
--     / `secondary_version` 作为 `expected_version`，执行归并并迁移当前引用。
--     历史订单行冻结当时快照，不随合并回溯改写（实体目录 §3.6）。
--   * **07 库存 / 04 映射 / 09 订单**：本模块不读它们的库（模块边界纪律），
--     所以 `left_reference_count` / `right_reference_count` 只是 catalog 这一侧；
--     合并执行方要按各自授权 API 补齐，并在报告里说明搬不搬得动。
--   * **不做物理删除**：原记录应标记合并并停用，本模块只登记决定，不改生命周期状态。
--   * **不新增词表**：权限沿用 `catalog.spu.read` / `catalog.spu.update`（与 3.8.1.1/3.8.1.2 同一口径），
--     新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。