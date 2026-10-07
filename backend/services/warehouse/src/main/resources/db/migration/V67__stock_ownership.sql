-- ===========================================================================
-- 7.7.1.1 货主与所有权：显式建模 stock_bucket 上的「货是谁的」
-- ===========================================================================
-- 蓝图原文：「为库存 bucket 显式标注货主（stock_owner）与所有权类型（自有 / 供应商寄售 /
-- 平台所有 / 代销），使『货在谁的仓库』与『货是谁的』不再混为一谈。默认自有，但租户可把整仓
-- 或整 SKU 设为寄售。」
--
-- ---------------------------------------------------------------------------
-- 为什么必须显式建模（实体目录 §8 把寄售列为未决项，此处给出答案）
-- ---------------------------------------------------------------------------
-- V2 的 stock_bucket 最小事实单位是「法人 × SKU × 仓库 × 库位 × 批次 × 质量状态」六元组。
-- 六个维度里**没有一个是货主**。后果不是"少一列"，而是两种完全不同的货在库位上无法区分：
--
--   自有货      on_hand=100，货在 A 仓  →  资产表要 +100
--   供应商寄售  on_hand=100，货在 A 仓  →  资产表**不能** +100（那是别人的货，我们只代管）
--
-- 两行 bucket 的其余五个维度逐列相同，无法互相区分。于是 12 财务算自有资产时只能把整仓
-- 一起算进去 —— 要么把供应商的货算成自己的资产（虚增资产、盘点对不上），
-- 要么把自有货漏出去（少计资产）。**这不是精度问题，是口径错了且无法在事后分辨。**
--
-- 更不能拿 tenant_id 顶替货主：货主与租户不是同一个概念。同一租户里的供应商寄售货，
-- 货主是供应商（catalog 的 party_supplier_profile），owner_kind = SUPPLIER_CONSIGNMENT。
-- 若用 tenant 代替货主，"寄售不进自有资产"这条规则就永远无法表达。
--
-- ---------------------------------------------------------------------------
-- 为什么货主挂在 bucket 而不是 balance（分层纪律不变）
-- ---------------------------------------------------------------------------
-- V1 把 balance 定为「权威可用量」，键是 仓库 × 货权法人 × SKU × 质量状态，
-- **不含库位**；V2 用延迟约束触发器保证 Σbucket == balance。
--
-- 本迁移**不把 stock_owner_id 加进 balance 的键**，理由与 V2 拒绝加 location_id 完全同源：
-- 可用量的预占是仓库级决策单位，一旦货主进键，预占就必须"按货主 SUM 后再 UPDATE"，
-- 重新引入 V1 明令禁止的竞态。
--
-- 那"同一 balance 行下混着两个货主的 bucket"怎么办？守恒式仍然是 Σbucket == balance：
-- 货主是一个**可下钻的明细维度**，balance 上的总量不变、可用量决策单位不变。
-- 代价必须说清楚：**balance 级的 available 无法按货主拆分**。也就是说"自有货的可用量"与
-- "寄售货的可用量"在仓库级预占上是同一个池子 —— 这正是 V1 既有的仓库级共享池语义。
-- 货主拆分只在 bucket 级（报表、货权转移）成立，本模块不改变仓库级的预占口径。
--
-- ---------------------------------------------------------------------------
-- 为什么不直接 SET NOT NULL（对既有车道的写入路径零影响的做法）
-- ---------------------------------------------------------------------------
-- stock_bucket 是 7.1.1.2 / 7.3.1.x / 7.4.2.2 / 7.5.1.1 共同写入的事实表，
-- 它们的 INSERT 与测试夹具**都不写 stock_owner_id**（已全仓确认：没有任何 ON CONFLICT
-- 依赖 bucket 的唯一约束）。所以本迁移的处理顺序刻意是：
--
--   1. ADD COLUMN（可空）              —— 此刻既有写入路径完全不受影响
--   2. 建 BEFORE INSERT 触发器         —— 之后每一条既有 INSERT 自动被赋予货主
--   3. 回填存量行
--   4. 才 SET NOT NULL + 加复合外键   —— 到这一步列上已经没有 NULL
--
-- 静态判据（任务书第 8 条要求自查的那一条）：本迁移同时具备
-- 「ADD COLUMN 后有 BEFORE INSERT 触发器兜底」**且**「SET NOT NULL 前有存量回填」，
-- 两个条件都满足，既有车道的 INSERT 不可能违约。
--
-- 货主的**默认值**不是哨兵值（不是全零 UUID），而是 resolve_default_stock_owner()
-- 现场解析/建立的**该租户真实的兜底货主行**：能落到一��法人上的自有货主，
-- 租户还没登记法人时退回 INTERNAL 并在 API 里如实标 legalEntityLinked=false。
-- 哨兵值会让 FK 无法建立，也会让"这一行的货是谁"变成一个永远查不到答案的 UUID。
--
-- ---------------------------------------------------------------------------
-- 「自有货才进自有资产，寄售不进」做成生成列而不是应用层判断
-- ---------------------------------------------------------------------------
-- 蓝图下游 = 12 财务。counts_as_owned_asset 是 GENERATED ALWAYS AS (owner_kind='SELF') STORED：
-- 它不能被任何人写成与 owner_kind 不一致的第二个真相（V1 的 available、V2 的 available
-- 同一个理由）。下游财务按这一列筛，寄售与代销天然被排除，不需要它再读一遍 owner_kind。
--
-- ---------------------------------------------------------------------------
-- 跨服务边界（AGENTS.md 模块边界纪律）
-- ---------------------------------------------------------------------------
--   法人    identity V5__legal_entity.sql → 本服务有 legal_entity_ref 登记（V1），可建复合外键
--   供应商  catalog  V30__party_role_profiles.sql（party_supplier_profile）
--   平台    channel  V1（platform/shop）
-- 后两者是**跨服务逻辑 ID + 写入时的可读名快照**，不建外键、不联查对方表
-- （与 V56 的 source_system + reference_id、V57 的 supplier_ref_id + supplier_name 同一套约定）。
-- 代价是本服务无法校验"这个供应商真的存在"，由调用方（前端选供应商时从 catalog 取）
-- 保证；换来的是两个服务的部署不被绑死在同一个 PG 实例上。
-- ===========================================================================

-- ===========================================================================
-- 1. stock_owner · 货主档案
-- ===========================================================================
CREATE TABLE stock_owner (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  code varchar(32) NOT NULL,
  name varchar(150) NOT NULL,

  -- 所有权类型（蓝图词表，一字不改）：
  --   SELF                   自有
  --   SUPPLIER_CONSIGNMENT   供应商寄售
  --   PLATFORM               平台所有
  --   CONSIGNEE              代销
  owner_kind varchar(20) NOT NULL
    CHECK(owner_kind IN ('SELF','SUPPLIER_CONSIGNMENT','PLATFORM','CONSIGNEE')),

  -- 「是否计入自有资产」是生成列：自有才进自有资产，寄售/代销/平台所有一律不进。
  -- 做成生成列而不是普通列，是为了杜绝"owner_kind 写 SELF、counts_as_owned_asset 写 false"
  -- 这种第二个真相 —— 下游 12 财务只读这一列，它必须是 owner_kind 的函数。
  counts_as_owned_asset boolean GENERATED ALWAYS AS (owner_kind = 'SELF') STORED,

  -- 货主指向谁。跨服务定位键，只对本服务有登记表的 LEGAL_ENTITY 建外键。
  --   LEGAL_ENTITY  → 本服务 legal_entity_ref（identity 是法人主数据的唯一所有者）
  --   SUPPLIER      → catalog party_supplier_profile（跨服务逻辑 ID，不建外键）
  --   PLATFORM      → channel platform（跨服务逻辑 ID，不建外键）
  --   INTERNAL      → 就是本租户自己，没有外部货主
  owner_ref_type varchar(16) NOT NULL
    CHECK(owner_ref_type IN ('LEGAL_ENTITY','SUPPLIER','PLATFORM','INTERNAL')),
  owner_ref_id uuid,
  -- 可读名在写入时快照：供应商改名或停用之后，三个月前的货权报表仍然读得懂这批货是谁的。
  owner_ref_label varchar(150) NOT NULL DEFAULT '',

  -- 默认货主。同一租户至多一个。蓝图「默认自有」由 DB 钉死：默认货主必须是 SELF。
  is_default boolean NOT NULL DEFAULT false,

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  note varchar(500) NOT NULL DEFAULT '',

  -- 建档人。为空 = **系统按需自动建立的兜底货主**（见 resolve_default_stock_owner），
  -- 而不是"某个匿名用户建的"。货权配置 stock_ownership_rule 的建档人则是必填的：
  -- 配货权是人的经营决策，自动建立的兜底货主不是。
  created_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),

  CHECK(btrim(code)<>'' AND btrim(name)<>''),

  -- 所有权类型与指向必须自洽。少这一条就会出现"寄售货挂在自家法人名下"这种
  -- 报表上完全看不出来、但会把别人的货算进自有资产的行。
  CHECK(
    (owner_kind='SELF'                  AND owner_ref_type IN ('LEGAL_ENTITY','INTERNAL')) OR
    (owner_kind='SUPPLIER_CONSIGNMENT'  AND owner_ref_type = 'SUPPLIER') OR
    (owner_kind='PLATFORM'              AND owner_ref_type IN ('PLATFORM','INTERNAL')) OR
    (owner_kind='CONSIGNEE'             AND owner_ref_type IN ('SUPPLIER','LEGAL_ENTITY'))
  ),
  -- INTERNAL 与 PLATFORM 没有外部货主，不该带一个查不到出处的 id；
  -- 反过来 LEGAL_ENTITY / SUPPLIER 缺 id 就是"指向了空气"。
  CHECK(
    (owner_ref_type IN ('INTERNAL','PLATFORM') AND owner_ref_id IS NULL) OR
    (owner_ref_type IN ('LEGAL_ENTITY','SUPPLIER') AND owner_ref_id IS NOT NULL)
  ),
  -- 蓝图：默认自有。默认货主是寄售会让"没配过货权的库存"默认不进自有资产。
  CHECK(is_default = false OR owner_kind = 'SELF')
);

-- 每个租户至多一个默认货主（部分唯一索引：带条件的唯一性不能用 UNIQUE 约束表达）。
CREATE UNIQUE INDEX stock_owner_default ON stock_owner(tenant_id) WHERE is_default;
CREATE INDEX stock_owner_list ON stock_owner(tenant_id, status, owner_kind, code);
-- 按跨服务货主反查"这个供应商/平台名下有哪些货主档案"（报表下钻与改名后定位）。
CREATE INDEX stock_owner_ref ON stock_owner(tenant_id, owner_ref_type, owner_ref_id)
  WHERE owner_ref_id IS NOT NULL;

-- 兜底货主的建立是幂等的，并发下只能建出一条。
CREATE UNIQUE INDEX stock_owner_default_code ON stock_owner(tenant_id, code)
  WHERE code = 'DEFAULT-OWN';

-- ---------------------------------------------------------------------------
-- 1.1 自有货主必须真的挂在本租户登记过的法人名下
-- ---------------------------------------------------------------------------
-- 复合外键做不到：owner_ref_id 对 SUPPLIER / PLATFORM 行是 catalog / channel 的逻辑 ID，
-- 建 (tenant_id, owner_ref_id) → legal_entity_ref 的外键会把那些行全部拒掉。
-- 所以只在 owner_ref_type='LEGAL_ENTITY' 时用触发器校验，错误信息直接告诉调用方缺什么。
CREATE FUNCTION guard_stock_owner_legal_entity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_code text;
BEGIN
 IF NEW.owner_ref_type <> 'LEGAL_ENTITY' THEN RETURN NEW; END IF;
 SELECT l.code INTO v_code FROM legal_entity_ref l
  WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.owner_ref_id;
 IF v_code IS NULL THEN
  RAISE EXCEPTION '货主「%」声明挂在法人 % 名下，但本租户的法人引用登记里没有这一条：'
    '自有货必须归属于一个已登记的法人，否则 12 财务无法确定它进哪个法人的资产表',
    NEW.name, NEW.owner_ref_id USING ERRCODE='23514';
 END IF;
 -- 快照名没给时用法人名补上：报表上"货主"列不该是一串 UUID。
 IF btrim(NEW.owner_ref_label) = '' THEN
  SELECT l.name INTO NEW.owner_ref_label FROM legal_entity_ref l
   WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.owner_ref_id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stock_owner_legal_entity
  BEFORE INSERT OR UPDATE ON stock_owner
  FOR EACH ROW EXECUTE FUNCTION guard_stock_owner_legal_entity();

-- ===========================================================================
-- 2. stock_ownership_rule · 货权配置（按仓库 / 法人 / SKU 维度，带生效区间）
-- ===========================================================================
-- 与 17.5.1.1 业务参数、V56 可售量口径同一套分档约定：**一个范围槽位在一个时刻只有一个值**，
-- 所以"历史"不是另一份拷贝，而是同一批行里已关区间的那些。改货权的动作 = 关旧档 + 开新档。
CREATE TABLE stock_ownership_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 范围四档，解析优先级 SKU > LEGAL_ENTITY > WAREHOUSE > DEFAULT（最具体者胜）。
  scope_type varchar(16) NOT NULL
    CHECK(scope_type IN ('DEFAULT','WAREHOUSE','LEGAL_ENTITY','SKU')),

  -- 三个可选定位键。仓库与法人是本服务表（可建复合外键）；sku 属 catalog，是逻辑 ID。
  warehouse_id uuid,
  legal_entity_id uuid,
  sku_id uuid,

  -- 这一档把货归给谁。NOT NULL：没有"这一档暂不指定货主"的中间状态 ——
  -- 那样解析时就要回答"那这一档算命中了还是没命中"，而两句话都成立。
  owner_id uuid NOT NULL,

  -- 生效区间（左闭右开）。effective_to 为 NULL = 至今仍在生效。
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,

  note varchar(500) NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  FOREIGN KEY(tenant_id,owner_id) REFERENCES stock_owner(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity_ref(tenant_id,id),

  -- 范围与定位键必须配套。少一条就会出现一条永远命中不了的档：
  -- 「SKU 档却没有 sku_id」在解析时无从回答"它到底管谁"。
  CHECK(
    (scope_type='DEFAULT'      AND warehouse_id IS NULL     AND legal_entity_id IS NULL AND sku_id IS NULL) OR
    (scope_type='WAREHOUSE'    AND warehouse_id IS NOT NULL AND legal_entity_id IS NULL AND sku_id IS NULL) OR
    (scope_type='LEGAL_ENTITY' AND warehouse_id IS NULL     AND legal_entity_id IS NOT NULL AND sku_id IS NULL) OR
    (scope_type='SKU'          AND warehouse_id IS NULL     AND legal_entity_id IS NULL AND sku_id IS NOT NULL)
  ),
  CHECK(effective_to IS NULL OR effective_to > effective_from)
);

-- 同一范围槽位**至多一档仍在生效**（effective_to IS NULL）。
-- NULLS NOT DISTINCT 而非普通 UNIQUE：DEFAULT 档的三个定位键恒为 NULL，
-- 普通 UNIQUE 认为 NULL 互不相等，根本不去重（V48 同一理由）。
CREATE UNIQUE INDEX stock_ownership_rule_open
  ON stock_ownership_rule(tenant_id, scope_type, warehouse_id, legal_entity_id, sku_id)
  WHERE effective_to IS NULL;

-- 解析主查询：取某槽位某一时刻的行。effective_from DESC 让"最近开的一档"排在最前。
CREATE INDEX stock_ownership_rule_resolve
  ON stock_ownership_rule(tenant_id, scope_type, warehouse_id, legal_entity_id, sku_id, effective_from DESC);
-- 反查"这个货主名下的货权档"（报表与货主档案详情走这条）。
CREATE INDEX stock_ownership_rule_owner
  ON stock_ownership_rule(tenant_id, owner_id, effective_from DESC);
-- 当前在生效的全部档（列表默认视图）。
CREATE INDEX stock_ownership_rule_live
  ON stock_ownership_rule(tenant_id, effective_from DESC) WHERE effective_to IS NULL;

-- ---------------------------------------------------------------------------
-- 2.1 货权档的货主必须是 ACTIVE
-- ---------------------------------------------------------------------------
-- 停用一个货主档不该让引用它的货权档静默失效 —— 那会让"整仓设为寄售"在某次保存配置之后
-- 悄悄不再生效，而界面上那一档还在（它只是不再被解析到）。宁可建档当场就被拒。
CREATE FUNCTION guard_ownership_rule_owner_active() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status text; v_name text;
BEGIN
 SELECT o.status, o.name INTO v_status, v_name FROM stock_owner o
  WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.owner_id;
 IF v_status IS NULL THEN
  RAISE EXCEPTION '货主 % 不存在或不属于本租户', NEW.owner_id USING ERRCODE='23514';
 END IF;
 IF v_status <> 'ACTIVE' THEN
  RAISE EXCEPTION '货主「%」当前状态为 %，不能作为货权档的归属：'
    '配一条指向停用货主的货权档，它会在解析时被静默跳过，而界面上那一档还在',
    v_name, v_status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER stock_ownership_rule_owner_active
  AFTER INSERT OR UPDATE ON stock_ownership_rule
  DEFERRABLE INITIALLY IMMEDIATE
  FOR EACH ROW EXECUTE FUNCTION guard_ownership_rule_owner_active();

-- ---------------------------------------------------------------------------
-- 2.2 同一槽位的生效区间不得重叠
-- ---------------------------------------------------------------------------
-- 「至多一档 open」由部分唯一索引挡住了，但**关掉的两档之间**仍可能重叠 ——
-- 而重叠的区间在解析时没有唯一答案：那个时刻到底听谁的？货权错判直接改变资产归属。
-- 三个定位键都用 coalesce 到全零 uuid：GiST 等值比较里 NULL 与 NULL 不相等，
-- 不 coalesce 的话 DEFAULT 档的区间重叠检查会完全失效。
CREATE FUNCTION guard_ownership_rule_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE other_id uuid; other_from timestamptz; other_to timestamptz;
BEGIN
 SELECT p.id, p.effective_from, p.effective_to INTO other_id, other_from, other_to
   FROM stock_ownership_rule p
  WHERE p.tenant_id = NEW.tenant_id
    AND p.scope_type = NEW.scope_type
    AND coalesce(p.warehouse_id,'00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(NEW.warehouse_id,'00000000-0000-0000-0000-000000000000'::uuid)
    AND coalesce(p.legal_entity_id,'00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(NEW.legal_entity_id,'00000000-0000-0000-0000-000000000000'::uuid)
    AND coalesce(p.sku_id,'00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(NEW.sku_id,'00000000-0000-0000-0000-000000000000'::uuid)
    AND p.id <> NEW.id
    AND tstzrange(p.effective_from, p.effective_to, '[)')
      && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ORDER BY p.effective_from
  LIMIT 1;
 IF other_id IS NOT NULL THEN
  RAISE EXCEPTION '该范围槽位在 % ~ % 区间与另一档重叠（id=%）：同一范围同一时刻只能有一个货主，'
    '否则解析结果没有唯一答案', NEW.effective_from,
    coalesce(NEW.effective_to::text,'至今'), other_id USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stock_ownership_rule_interval
  BEFORE INSERT OR UPDATE ON stock_ownership_rule
  FOR EACH ROW EXECUTE FUNCTION guard_ownership_rule_interval();

-- ===========================================================================
-- 3. 货主解析：唯一一处「这批货归谁」的判定
-- ===========================================================================
-- 三处调用它，且只有它一处实现优先级：bucket 自动赋权触发器、货权解析端点、报表口径。
-- 优先级 SKU > LEGAL_ENTITY > WAREHOUSE > DEFAULT（最具体者胜），命中即止。
--
-- 回落语义：四档全未命中 → 该租户的默认货主（蓝图「默认自有」），
-- 并由 ensure_default_stock_owner 现场建立。这是**兜底而不是猜测**：
-- 调用方拿到的是"没有配过货权"这个状态，API 会如实报 usedFallback。
CREATE FUNCTION resolve_stock_owner(
  p_tenant uuid, p_warehouse uuid, p_legal_entity uuid, p_sku uuid, p_at timestamptz
) RETURNS uuid LANGUAGE plpgsql STABLE AS $$
DECLARE v_owner uuid;
BEGIN
 SELECT r.owner_id INTO v_owner
   FROM stock_ownership_rule r
  WHERE r.tenant_id = p_tenant
    AND r.effective_from <= p_at
    AND (r.effective_to IS NULL OR r.effective_to > p_at)
    AND (
      (r.scope_type = 'SKU'          AND r.sku_id          = coalesce(p_sku,'00000000-0000-0000-0000-000000000000'::uuid)) OR
      (r.scope_type = 'LEGAL_ENTITY' AND r.legal_entity_id = coalesce(p_legal_entity,'00000000-0000-0000-0000-000000000000'::uuid)) OR
      (r.scope_type = 'WAREHOUSE'    AND r.warehouse_id    = coalesce(p_warehouse,'00000000-0000-0000-0000-000000000000'::uuid)) OR
      (r.scope_type = 'DEFAULT')
    )
  ORDER BY CASE r.scope_type WHEN 'SKU' THEN 0 WHEN 'LEGAL_ENTITY' THEN 1
                            WHEN 'WAREHOUSE' THEN 2 ELSE 3 END,
           r.effective_from DESC
  LIMIT 1;
 RETURN v_owner;   -- 可能为 NULL，调用方负责回落到默认货主
END $$;

-- ---------------------------------------------------------------------------
-- 3.1 兜底货主的按需建立（幂等）
-- ---------------------------------------------------------------------------
-- 自有货必须落在某个法人名下：库存余额按法人记账，一个没有法人的"自有"让 12 财务
-- 无法确定这批货进哪张资产表。所以优先挂租户登记过的第一个 ACTIVE 法人；
-- 租户一个法人都没登记时退回 INTERNAL，并让 API 用 legalEntityLinked=false 如实标出来 ——
-- 宁可报"还没挂法人"，也不凭空造一个法人。
CREATE FUNCTION ensure_default_stock_owner(p_tenant uuid) RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE v_id uuid; v_le uuid; v_le_name text; v_le_code text;
BEGIN
 SELECT o.id INTO v_id FROM stock_owner o
  WHERE o.tenant_id = p_tenant AND o.is_default;
 IF v_id IS NOT NULL THEN RETURN v_id; END IF;

 SELECT l.id, l.code, l.name INTO v_le, v_le_code, v_le_name
   FROM legal_entity_ref l
  WHERE l.tenant_id = p_tenant AND l.status = 'ACTIVE'
  ORDER BY l.code LIMIT 1;

 INSERT INTO stock_owner(id,tenant_id,code,name,owner_kind,owner_ref_type,owner_ref_id,
                         owner_ref_label,is_default,status,note,created_by_membership_id)
 VALUES(gen_random_uuid(), p_tenant, 'DEFAULT-OWN', '默认自有', 'SELF',
        CASE WHEN v_le IS NULL THEN 'INTERNAL' ELSE 'LEGAL_ENTITY' END,
        v_le, coalesce(v_le_name,''), true, 'ACTIVE',
        '库存写入路径未指定货主时由 7.7.1.1 自动建立的兜底货主。默认自有（蓝图 7.7.1.1「默认自有」）。',
        NULL)
 ON CONFLICT DO NOTHING;

 SELECT o.id INTO v_id FROM stock_owner o
  WHERE o.tenant_id = p_tenant AND o.is_default;
 RETURN v_id;
END $$;

-- ===========================================================================
-- 4. stock_bucket 加货主维度（对既有写入路径零影响）
-- ===========================================================================

-- 4.1 先加可空列。此刻既有 INSERT 完全不受影响 —— 后面两步（触发器 + 回填）完成后才收口。
ALTER TABLE stock_bucket ADD COLUMN stock_owner_id uuid;

-- ---------------------------------------------------------------------------
-- 4.2 BEFORE INSERT / UPDATE 触发器：既有写入路径自动获得正确的货主
-- ---------------------------------------------------------------------------
-- 这是本迁移对其他车道最关键的一步：7.1.1.2 建库位明细、7.3.1.x 批次、7.5.1.1 调拨收货
-- 的 INSERT 都**不写** stock_owner_id，它们不该为了本模块去改一行代码。
-- 触发器按 bucket 自己的维度解析货权档，解析不到就落该租户的兜底货主。
--
-- 为什么放在 BEFORE 而不是让应用层必填：应用层"记得传"不是保证，拿到连接就能不传。
-- 放在 DB 层之后，"bucket 的货主"才是 V2 那套不变量的同级：事实行不允许自己缺一个维度。
CREATE FUNCTION guard_bucket_stock_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_resolved uuid; v_status text;
BEGIN
 IF NEW.stock_owner_id IS NULL THEN
  -- 未显式指定 → 按 bucket 自身维度解析货权档，解析不到落默认货主（默认自有）。
  v_resolved := resolve_stock_owner(NEW.tenant_id, NEW.warehouse_id,
                                     NEW.legal_entity_id, NEW.sku_id, now());
  IF v_resolved IS NULL THEN
   v_resolved := ensure_default_stock_owner(NEW.tenant_id);
  END IF;
  NEW.stock_owner_id := v_resolved;
  RETURN NEW;
 END IF;

 -- 显式指定 → 必须真的存在且可用。否则 bucket 会挂在一个查不到的货主上，
 -- 报表里这一行的货权列是个 UUID，而按它筛选永远筛不出东西。
 SELECT o.status INTO v_status FROM stock_owner o
  WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.stock_owner_id;
 IF v_status IS NULL THEN
  RAISE EXCEPTION 'bucket 指定的货主 % 不存在或不属于本租户：'
    '货主维度不允许悬空，否则按货主筛选的报表会永远漏掉这一行', NEW.stock_owner_id
    USING ERRCODE='23514';
 END IF;
 IF v_status <> 'ACTIVE' THEN
  RAISE EXCEPTION 'bucket 不能挂在状态为 % 的货主上：货主停用后历史货权仍需可查，'
    '要让这一行换个货主请走货权转移而不是直接挂停用货主', v_status USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_bucket_stock_owner
  BEFORE INSERT OR UPDATE OF stock_owner_id ON stock_bucket
  FOR EACH ROW EXECUTE FUNCTION guard_bucket_stock_owner();

-- 4.3 回填存量行：先把兜底货主建出来，再逐行赋值。
-- 先建后赋的顺序不能反 —— 先赋会撞上后面的复合外键。
DO $$ DECLARE t record; BEGIN
 FOR t IN SELECT DISTINCT tenant_id FROM stock_bucket LOOP
  PERFORM ensure_default_stock_owner(t.tenant_id);
 END LOOP;
END $$;
UPDATE stock_bucket b
   SET stock_owner_id = coalesce(
     resolve_stock_owner(b.tenant_id, b.warehouse_id, b.legal_entity_id, b.sku_id, now()),
     ensure_default_stock_owner(b.tenant_id))
 WHERE b.stock_owner_id IS NULL;

-- 4.4 到这里列上已经没有 NULL，才收口 NOT NULL 并挂复合外键。
-- 顺序是硬约束：先 SET NOT NULL 再回填，或者不回填就加外键，都会在有存量的库上炸。
ALTER TABLE stock_bucket ALTER COLUMN stock_owner_id SET NOT NULL;
ALTER TABLE stock_bucket
  ADD CONSTRAINT fk_stock_bucket_stock_owner
  FOREIGN KEY(tenant_id, stock_owner_id) REFERENCES stock_owner(tenant_id,id);

-- 4.5 唯一约束换成含货主的八元组：同一库位上两个货主的货必须能并存。
--
-- 这是蓝图那句「同一仓内不同货主必须能并存」的直接落地。七元组唯一时，
-- 自有货与寄售货在同一个库位、同一个批次、同一个质量状态下会被迫合并成一行 ——
-- 合并之后这两批货的货权就再也分不开了，模块存在的意义当场归零。
--
-- 约束名不写死：V2 里这条 UNIQUE NULLS NOT DISTINCT 是匿名建的，PG 生成的名字被截断到
-- 63 字符，写死会在换个 PG 版本或改列序时静默匹配不到。这里按**列集合**精确匹配后删。
DO $$ DECLARE c record; BEGIN
 FOR c IN
  SELECT conname FROM pg_constraint
   WHERE conrelid = 'stock_bucket'::regclass AND contype = 'u'
     AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
            FROM unnest(conkey) WITH ORDINALITY AS k(attnum, ord)
            JOIN pg_attribute a ON a.attrelid = 'stock_bucket'::regclass AND a.attnum = k.attnum)
         = ARRAY['tenant_id','legal_entity_id','sku_id','warehouse_id',
                 'location_id','lot_id','quality_status_id']::text[]
 LOOP
  EXECUTE format('ALTER TABLE stock_bucket DROP CONSTRAINT %I', c.conname);
 END LOOP;
END $$;
ALTER TABLE stock_bucket
  ADD CONSTRAINT uq_stock_bucket_owner_scope
  UNIQUE NULLS NOT DISTINCT(
    tenant_id, stock_owner_id, legal_entity_id, sku_id, warehouse_id, location_id,
    lot_id, quality_status_id);

-- 4.6 报表与货权转移的主查询路径：先按货主切片，再按仓库/SKU 下钻。
CREATE INDEX stock_bucket_owner
  ON stock_bucket(tenant_id, stock_owner_id, warehouse_id, sku_id);

-- ===========================================================================
-- 5. 多租户与授权（与 V1 / V2 / V56 同一套写法）
-- ===========================================================================
-- 两张新表都是租户业务表，一视同仁 FORCE。current_setting('app.current_tenant') 由
-- backend/libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['stock_owner','stock_ownership_rule'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON stock_owner, stock_ownership_rule TO warehouse_app;
-- 货主与货权档都不删除：停用走 status/收区间。两张表都是"当时这批货归谁"的唯一凭据，
-- 删掉之后历史报表就答不出"上个月末这批寄售货记在谁名下"了。
REVOKE DELETE ON stock_owner, stock_ownership_rule FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V2 / V54 / V56 同口径）。
REVOKE ALL ON stock_owner, stock_ownership_rule FROM starcon_app;