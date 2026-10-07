-- ===========================================================================
-- 7.2.2.1 可售量口径：不同渠道/店铺的库存口径配置
-- ===========================================================================
-- 蓝图原文：「配置不同渠道/店铺的库存口径：是否共享同一池、是否允许超卖多少、
-- 平台可售量是按自有仓还是含 FBA、平台可售数保留缓冲比例。输入：店铺/渠道维度配置
-- （共享策略、缓冲比例、是否含在途）。输出：生效的可售量口径；供 7.1.1.3 与 04 回写使用。」
-- 以及：「**平台库存回写缓冲是防超卖的最后一道防线**：多店铺共享池时，缓冲不足会在
-- 同步延迟期间超卖。」
--
-- ---------------------------------------------------------------------------
-- 为什么一张表装"生效区间"而不是「配置表 + 变更历史表」两张
-- ---------------------------------------------------------------------------
-- 分档生效（本模块的 effective_from / effective_to）与 17.5.1.1 业务参数的
-- business_parameter_override.effective_from 是同一套约定。两者的共同点决定了表结构：
-- **一个口径在一个时刻只有一个值**，所以"历史"不是另一份拷贝，而是**同一批行里已关区间的那些**。
-- 改口径的动作是「关掉旧区间 + 开新区间」，旧行连同旧值原地留在表里 —— 历史因此天然完整，
-- 不存在两张表各自漂移的可能（V51 台账迁移已经按同一条理由拒绝过"累计值表"）。
-- 配套的触发器把这件事钉死在 DB 层：一行口径的内容列写定之后不许改，只允许把
-- effective_to 从 NULL 收到一个值（换版或作废）。想改口径必须开新档 —— 配置错了直接超卖，
-- 而能悄悄改掉一条正在生效的口径，等于把那道防线本身拆了。
--
-- ---------------------------------------------------------------------------
-- 为什么"跨服务定位键"是 source_system + reference_id 且刻意不建外键
-- ---------------------------------------------------------------------------
-- 蓝图上游写的是「2.3 店铺」，店铺表在 channel 服务（channel V1 的 shop 表）。
-- AGENTS.md「模块边界纪律」明令禁止跨服务 SQL / FK / 视图。这里照 identity V48
-- business_parameter_override 的先例：只存逻辑 ID + 写入时快照的可读名。
-- 代价是本服务无法校验"这个店铺真的存在"，由调用方（前端选店铺时从 /channel/shops 取）
-- 保证；换来的是两个服务的部署不被绑死在同一个 PG 实例上。
-- 17.2.2.3 离职停用已经用同一条理由落过 source_system + reference_id，本模块与它同构。
--
-- ---------------------------------------------------------------------------
-- 为什么要 sellable_policy_warehouse（口径挂仓库）而不是"店铺 ↔ 仓库"的映射表
-- ---------------------------------------------------------------------------
-- 共享池必须能算账：缓冲够不够 = 池容量 × 各成员分配比例之和 vs 池容量，
-- 而"池容量"要落到具体的仓库行上才有数。店铺 ↔ 仓库 的映射本身也是 channel 的语义，
-- 在本服务另建一张映射表就是复制一份真相。所以映射挂在**本口径**上：口径声明
-- 「我吃哪几个仓的货」，并逐个标注它是自有仓还是平台仓（FBA 观测值）。
--
-- 顺带解决「平台可售量按自有仓还是含 FBA」这个开关怎么真正起作用：
-- warehouse 表**没有** type 列（V1 建表时没有，FBA 分类属 7.1.3.1 那条车道），
-- 所以本模块不臆造全局仓库类型，而是在口径侧逐条标注 warehouse_kind：
--   OWN_PLUS_FBA 时 FBA 仓计入容量；OWN_WAREHOUSE 时只算 OWN 仓，FBA 量被排除并如实报出。
-- 这样这个开关是**可计算的**而不是只挂在那儿，同时不去动别的车道拥有的仓库分类语义。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. sellable_policy · 口径分档
-- ---------------------------------------------------------------------------
CREATE TABLE sellable_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 生效范围三档（与 17.5.1.1 的 GLOBAL/LEGAL_ENTITY/SHOP 同构，这里换成渠道口径）：
  -- DEFAULT = 全租户兜底档；CHANNEL = 按渠道；SHOP = 按店铺。
  -- 解析顺序与 17.5.1.1 同一套优先级：SHOP > CHANNEL > DEFAULT。
  scope_type varchar(16) NOT NULL CHECK(scope_type IN ('DEFAULT','CHANNEL','SHOP')),

  -- 跨服务定位键。店铺/渠道在 channel 服务，这里只存逻辑 ID（不建外键，见文件头）。
  source_system varchar(32) NOT NULL DEFAULT '',
  reference_id uuid,
  -- 可读名在写入时快照：店铺改名或停用后，三个月前那一档口径仍然读得懂自己管的是谁。
  reference_label varchar(150) NOT NULL DEFAULT '',

  -- 共享池策略。这是**策略不是数据**：SHARED 意味着这几个口径扣的是同一个池子，
  -- 配置错了直接超卖，所以下面的 pool_key 与 partial unique 一起把它约束住。
  pool_mode varchar(16) NOT NULL CHECK(pool_mode IN ('EXCLUSIVE','SHARED')),
  -- 池标识。EXCLUSIVE 由服务按 'EXCL-' || reference_id 派生（DB CHECK 钉死，
  -- 防止有人手填一个共享键把独占池变成共享池）；SHARED 由运营显式取名，多档共用同名即共享。
  pool_key varchar(64) NOT NULL,

  -- 超卖容忍：允许超卖多少件。刻意**给件数不给比例** —— 超卖是绝对量（超卖 3 件就是
  -- 多接 3 单），给比例会在池子变大时自动放大容忍量，那不是运营想要的。
  oversell_allowed boolean NOT NULL DEFAULT false,
  oversell_tolerance numeric(19,4) NOT NULL DEFAULT 0,

  -- 平台可售量口径：只按自有仓，还是含 FBA。与 7.1.3.1 的「FBA 库存是平台仓观测值，
  -- 不是自有账本」是同一条边界：FBA 计入平台回写口径，但不计入自有账本的可用量推导。
  platform_stock_scope varchar(20) NOT NULL
    CHECK(platform_stock_scope IN ('OWN_WAREHOUSE','OWN_PLUS_FBA')),

  -- 平台可售数保留缓冲比例。numeric(9,6) 覆盖 0~1 且留 6 位小数，
  -- 取值范围由下面的 CHECK 兜在 DB 层 —— 比例越界必须在这里被拒，
  -- 浮点与"应用层校验一下就行"都不接受（AGENTS.md：禁浮点记账）。
  buffer_ratio numeric(9,6) NOT NULL DEFAULT 0,

  -- 是否把在途计入。默认 false：未到货的库存拿去回写平台可售数就是超卖。
  include_in_transit boolean NOT NULL DEFAULT false,

  -- 生效区间（左闭右开）。effective_to 为 NULL = 至今仍在生效。
  -- 左闭右开是为了相邻区间在边界那一瞬不重复生效两次。
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,

  note varchar(500) NOT NULL DEFAULT '',
  -- 操作人：已认证成员 id（与 V54 同一纪律，不接受请求参数传入）。
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- 范围与跨服务定位键必须配套。少一条就会出现一条永远命中不了的档：
  -- 「SHOP 档却没有 reference_id」在解析时无从回答"它到底管谁"。
  CHECK(
    (scope_type='DEFAULT'  AND source_system=''      AND reference_id IS NULL) OR
    (scope_type IN ('CHANNEL','SHOP') AND source_system<>'' AND reference_id IS NOT NULL)
  ),

  -- 生效区间本身合法。
  CHECK(effective_to IS NULL OR effective_to > effective_from),

  -- 缓冲比例取值范围（DB 层硬约束，见文件头）。
  CHECK(buffer_ratio >= 0 AND buffer_ratio <= 1),

  -- 超卖容忍：件数为负必错；不允许超卖时容忍量必须是 0 —— 留着 5 件容忍却关掉开关，
  -- 解析时会得到一个"明明不许超卖却带着额度"的矛盾档。
  CHECK(oversell_tolerance >= 0),
  CHECK(oversell_allowed OR oversell_tolerance = 0),

  -- 池键必须非空，且独占池的键只能由服务派生。
  CHECK(btrim(pool_key) <> ''),
  CHECK(pool_mode <> 'EXCLUSIVE' OR pool_key = 'EXCL-' || coalesce(reference_id::text,'')),
  -- 共享池名不得占用 EXCL- 命名空间：那里是服务派生的独占池键，
  -- 让人手填一个 EXCL- 开头的共享池名，会让"按名字找池子"在两种含义之间产生歧义。
  CHECK(pool_mode <> 'SHARED' OR (pool_key NOT LIKE 'EXCL-%')),
  -- 兜底档没有"谁与它共享"这个概念，给它一个共享池键解析不出成员集合。
  CHECK(pool_mode <> 'SHARED' OR scope_type <> 'DEFAULT')
);

-- 同一范围槽位**至多一档仍在生效**（effective_to IS NULL）。
-- 两个部分唯一索引而不是一条三列 UNIQUE：PG 认为 NULL 互不相等，
-- DEFAULT 档的 reference_id 恒为 NULL，普通 UNIQUE 根本不去重（V48 同一理由）。
-- 顺带把 DEFAULT 档"只能有一条兜底"这件事钉死。
CREATE UNIQUE INDEX sellable_policy_open_default
  ON sellable_policy(tenant_id) WHERE scope_type='DEFAULT' AND effective_to IS NULL;
CREATE UNIQUE INDEX sellable_policy_open_reference
  ON sellable_policy(tenant_id, scope_type, reference_id)
  WHERE scope_type<>'DEFAULT' AND effective_to IS NULL;

-- 生效值解析的主查询：取某档在某一时刻的行。effective_from DESC 让"最近开的一档"排在最前。
CREATE INDEX sellable_policy_resolve
  ON sellable_policy(tenant_id, scope_type, reference_id, effective_from DESC);
-- 反查"这个店铺/渠道配过哪几档口径"（页面上的版本时间线走这条）。
CREATE INDEX sellable_policy_reference
  ON sellable_policy(tenant_id, source_system, reference_id) WHERE reference_id IS NOT NULL;
-- 共享池盘点：把同名 pool_key 的在效档聚在一起算缓冲够不够（04 回写前的那道体检）。
CREATE INDEX sellable_policy_pool
  ON sellable_policy(tenant_id, pool_key, effective_from DESC) WHERE pool_mode='SHARED';
-- 当前在生效的全部档（列表默认视图）。
CREATE INDEX sellable_policy_open
  ON sellable_policy(tenant_id, effective_from DESC) WHERE effective_to IS NULL;

-- ---------------------------------------------------------------------------
-- 2. sellable_policy_warehouse · 口径吃哪几个仓、哪些算自有
-- ---------------------------------------------------------------------------
-- 池容量要落到具体仓库才有数（见文件头）。warehouse_kind 在**口径侧**标注而不改
-- warehouse 表：warehouse 没有 type 列，FBA 分类归 7.1.3.1 那条车道，这里不去动它的语义。
--   OWN         自有仓，计入容量
--   PLATFORM_FBA 平台仓观测值，**仅当口径的 platform_stock_scope='OWN_PLUS_FBA' 时**计入
CREATE TABLE sellable_policy_warehouse (
  tenant_id uuid NOT NULL,
  policy_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  warehouse_kind varchar(16) NOT NULL CHECK(warehouse_kind IN ('OWN','PLATFORM_FBA')),
  created_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id,policy_id,warehouse_id),
  -- 复合外键钉住"这个仓确实属于本租户"（warehouse 上有 UNIQUE(tenant_id,id)）。
  -- 两个都指向本服务的表，不是跨服务引用。
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  -- 口径行不许物理删除（有作废动作），但删档时它挂的仓要跟着走，用 CASCADE 收干净。
  FOREIGN KEY(tenant_id,policy_id) REFERENCES sellable_policy(tenant_id,id) ON DELETE CASCADE
);

-- 池容量的主查询：按 pool_key 找成员 → 取成员挂的仓。走这条覆盖索引。
CREATE INDEX sellable_policy_warehouse_pool
  ON sellable_policy_warehouse(tenant_id,warehouse_id,warehouse_kind);
-- 从仓反查"哪些口径吃这个仓的货"（共享池成员盘点与排查重复计入走这条）。
CREATE INDEX sellable_policy_warehouse_policy
  ON sellable_policy_warehouse(tenant_id,policy_id);

-- ===========================================================================
-- 3. DB 级行为约束（两道：口径内容不可改 + 区间不得重叠）
-- ===========================================================================
-- 3.1 口径内容写定后不许改，只允许把 effective_to 从 NULL 收到一个值。
--
-- 为什么不在应用层保证：应用层"不写 UPDATE"不是保证，拿到连接就能直接改。
-- 这里把"改口径 = 开新档"这件事变成数据库不变量：口径是防超卖的最后一道防线，
-- 而能悄悄覆盖一条正在生效的口径，等于把这道防线本身给拆了。
CREATE FUNCTION guard_sellable_policy_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 唯一允许的改动：effective_to 由 NULL 收到一个值（换版或作废）。
 -- 不允许改成另一个值（那等于悄悄把区间拉长/截短），更不允许从有值改回 NULL（复活）。
 IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '口径内容写定后不可修改，effective_to 只能从空收到一个时刻（换版或作废）：'
     '改口径请开新的一档，否则历史那一档的取值会被悄悄改掉，而它可能正是当时真正生效的那道防线'
     USING ERRCODE='23514';
 END IF;
 IF OLD.effective_to IS NOT NULL THEN
   RAISE EXCEPTION '这一档的生效区间已经被收口（effective_to=%），不允许再次修改或复活', OLD.effective_to
     USING ERRCODE='23514';
 END IF;
 IF NEW.effective_to <= OLD.effective_from THEN
   RAISE EXCEPTION '收口时刻必须晚于该档的生效起点 %，否则这一档从未真正生效过', OLD.effective_from
     USING ERRCODE='23514';
 END IF;
 -- 其余列一律逐列比对，防止有人绕开上面的特判改别的字段。
 IF (NEW.scope_type,NEW.source_system,NEW.reference_id,NEW.reference_label,
     NEW.pool_mode,NEW.pool_key,NEW.oversell_allowed,NEW.oversell_tolerance,
     NEW.platform_stock_scope,NEW.buffer_ratio,NEW.include_in_transit,
     NEW.effective_from,NEW.note,NEW.created_by_membership_id)
    IS DISTINCT FROM
    (OLD.scope_type,OLD.source_system,OLD.reference_id,OLD.reference_label,
     OLD.pool_mode,OLD.pool_key,OLD.oversell_allowed,OLD.oversell_tolerance,
     OLD.platform_stock_scope,OLD.buffer_ratio,OLD.include_in_transit,
     OLD.effective_from,OLD.note,OLD.created_by_membership_id) THEN
   RAISE EXCEPTION '口径内容（范围/共享策略/超卖/平台口径/缓冲比例/是否含在途）写定后不可修改，请开新的一档'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sellable_policy_immutable
  BEFORE UPDATE ON sellable_policy
  FOR EACH ROW EXECUTE FUNCTION guard_sellable_policy_immutable();

-- 3.2 同一范围槽位的生效区间不得重叠。
--
-- 「至多一档 open」由部分唯一索引挡住了，但**关掉的两档之间**仍可能重叠 ——
-- 而重叠的区间在解析时没有唯一答案：那个时刻到底听谁的？这是"配置错了直接超卖"
-- 里最难查的一种，因为它平时完全看不出来，直到某天两个档的缓冲比例不一样时爆发。
-- 这里显式挡住并把冲突的那一档 id 报出来，错误信息直接告诉调用方去改哪一档。
--
-- reference_id 用 coalesce 到全零 uuid 才能让 DEFAULT 档的多行互相比较：
-- GiST/等值比较里 NULL 与 NULL 不相等，会让 DEFAULT 档的区间重叠检查完全失效。
CREATE FUNCTION guard_sellable_policy_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE other_id uuid; other_from timestamptz; other_to timestamptz;
BEGIN
 SELECT p.id, p.effective_from, p.effective_to INTO other_id, other_from, other_to
   FROM sellable_policy p
  WHERE p.tenant_id = NEW.tenant_id
    AND p.scope_type = NEW.scope_type
    AND coalesce(p.reference_id,'00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(NEW.reference_id,'00000000-0000-0000-0000-000000000000'::uuid)
    AND p.id <> NEW.id
    AND tstzrange(p.effective_from, p.effective_to, '[)')
      && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ORDER BY p.effective_from
  LIMIT 1;
 IF other_id IS NOT NULL THEN
   RAISE EXCEPTION '该口径在 % ~ % 区间与另一档重叠（id=%）：同一范围同一时刻只能有一个生效值，'
     '否则解析结果没有唯一答案', NEW.effective_from,
     coalesce(NEW.effective_to::text,'至今'), other_id
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sellable_policy_interval
  BEFORE INSERT OR UPDATE ON sellable_policy
  FOR EACH ROW EXECUTE FUNCTION guard_sellable_policy_interval();

-- ===========================================================================
-- 4. 多租户与授权（与 V1/V2/V54 同一套写法）
-- ===========================================================================
-- 两张表都是租户业务表，一视同仁 FORCE。current_setting('app.current_tenant') 由
-- backend/libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['sellable_policy','sellable_policy_warehouse'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON sellable_policy, sellable_policy_warehouse TO warehouse_app;
-- 口径分档不删除：作废走 retire（关区间），档位行永久保留 —— 它是"当时那道防线长什么样"
-- 的唯一凭据。权限层先收一道，3.1 的触发器再收一道。
REVOKE DELETE ON sellable_policy FROM warehouse_app;
-- 挂仓关系随档作废/换版增删，正常业务动作需要它。
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V54 同口径）。
REVOKE ALL ON sellable_policy, sellable_policy_warehouse FROM starcon_app;
