-- ===========================================================================
-- 8.1.2.1 库位树：给 warehouse_location 补层级、容量与承重
-- ===========================================================================
-- 蓝图原文：「维护库位层级与属性（区 → 巷 → 排 → 架 → 层 → 格），支持容量、承重、
-- 类型（存储/暂存/待检/残次/冻结）、是否可拣。输入：库位增删改与层级调整；防环校验。
-- 输出：库位树 + 占用情况。」以及关系段的红线：「**库位不能跨租户引用**，
-- 也不允许跨仓库（实体目录 warehouse 段）。」
--
-- ---------------------------------------------------------------------------
-- 上游实查（不是「库位表不存在」—— 那是简报的旧认知）
-- ---------------------------------------------------------------------------
-- warehouse_location 由 V2__warehouse_inventory_bucket.sql 建立，当前列只有
--   id / tenant_id / warehouse_id / code / name / type / status / pickable / 时间戳
-- 其中 type 已是六值（STORAGE/RECEIVING/SHIPPING/QUARANTINE/DEFECTIVE/FROZEN），
-- 与蓝图「存储/暂存/待检/残次/冻结」对齐，**本迁移刻意不改它一个字**（不改词表、
-- 不放宽 CHECK、不加 DEFAULT）。
--
-- 缺的正是 8.1.1.2 交付时点名归属本模块的三样：**层级**（无 parent_id）、
-- **容量**、**承重**。8.1.1.2 的证据栏原文：「available=false 的只是层级
-- （表上无 parent_id、无容量承重列，归 8.1.2.1）与自动分配（8.1.2.2）」。
--
-- ---------------------------------------------------------------------------
-- 为什么不新建 warehouse_location_node 之类的表
-- ---------------------------------------------------------------------------
-- 库位**就是**一行档案：stock_bucket 的复合外键
--   FOREIGN KEY(tenant_id,warehouse_id,location_id) REFERENCES warehouse_location(tenant_id,warehouse_id,id)
-- 正指向它。若另建一张层级表，库位的「父子关系」与「库位本身」就分居两处，
-- 每条读库位的 SQL 都得记得 join —— 而 8.1.1.1 / 8.1.1.2 与本模块都要读它。
-- 所以是给既有表补列，不是新表。
--
-- ---------------------------------------------------------------------------
-- 三列全部 nullable 且**没有 DEFAULT** —— 这是跨车道安全的形状（照抄同服务 V77）
-- ---------------------------------------------------------------------------
-- warehouse_location 是**别的已交付车道正在读的表**（8.1.1.1 列表 / 8.1.1.2 详情与库位清单）。
-- 仓库里有真实事故：某车道把列改成 NOT NULL 且无 DEFAULT 兜底，破坏了早先合入车道的
-- INSERT，各自单跑都绿、合并后才炸。本迁移据此取最保守的形状：
--
--   * ADD COLUMN nullable 无 DEFAULT 在 PostgreSQL 是**元数据操作，不重写表**，
--     不长时间持锁，其他车道的读查询不被阻塞；
--   * 全仓库 40 处 `insert into warehouse_location(...)` **全部显式列出列名**
--     （本服务测试夹具与各车道 create() 均如此），不带列名的
--     INSERT VALUES(...) 才会因列数变化而报错 —— 仓库里没有这种写法，
--     所以既有 INSERT 不会违约；
--   * 没有对任何既有列做 SET NOT NULL；
--   * 没有收紧既有 CHECK（type / status 的词表一个字没动）；
--   * 新增的两个 CHECK 都用 `IS NULL OR ...` 短路，对全部存量 NULL 行恒真。
--
-- 换句话说：本迁移没有任何形状能让别的车道违约，它只增列 + 加约束对象。
--
-- ---------------------------------------------------------------------------
-- 「必须有 parent」为什么用触发器而不是 NOT NULL
-- ---------------------------------------------------------------------------
-- 库位树的**根**（顶层「区」）天然没有父节点，parent_id 必须是 NULL；
-- 而 ADD COLUMN NOT NULL 会立刻要求存量行全部有父值 —— 那正是上面那条事故的形状。
-- 所以「层级合法」交给触发器 guard_warehouse_location_tree（防环 + 层数上限），
-- 数据类型上保持 nullable。
--
-- ---------------------------------------------------------------------------
-- 跨租户与跨仓库：一条复合外键同时钉死两条红线
-- ---------------------------------------------------------------------------
-- FOREIGN KEY (tenant_id, warehouse_id, parent_id) REFERENCES warehouse_location
--            (tenant_id, warehouse_id, id)
-- 引用侧带 tenant_id 与 warehouse_id，所以：
--   * parent 属于别的租户  → 外键找不到匹配行 → 拒；
--   * parent 属于别的仓库  → 外键找不到匹配行 → 拒。
-- MATCH SIMPLE 下 parent_id 为 NULL 时该外键整体跳过，根节点照常插入。
-- 被引用列 UNIQUE(tenant_id,warehouse_id,id) 已存在（V2 建立），所以外键合法。
-- 这一条**不依赖应用层**：绕过服务层直连 SQL 也挂不上跨仓库的父子关系。
--
-- 防环校验同样落在 DB：BEFORE INSERT OR UPDATE OF parent_id 上用递归 CTE 沿 parent
-- 链上行，一旦走回 NEW.id 即成环，抛错。应用层也会先查一次（给友好提示），
-- 但**权威判据是数据库** —— 成环会让递归 CTE 永不收敛，整棵树查询打不出结果。
--
-- ---------------------------------------------------------------------------
-- 多租户：本迁移不新增表，因此不写 ENABLE / FORCE ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
-- warehouse_location 的 tenant_id NOT NULL + ENABLE + FORCE + isolation 策略由 V2 建立，
-- 新列自动继承同一张表的策略。刻意**不重复** ALTER ... ENABLE、也**不重复** GRANT：
--   * 重复 ENABLE 虽是幂等的，但写一遍会让人误以为「RLS 是本迁移加的」——归属写错比不写更糟
--     （与 V77 同一理由）；
--   * warehouse_app 在 V2 已获该表 SELECT/INSERT/UPDATE/DELETE 的**表级**权限，
--     表级权限自动覆盖新列，无需也不应再 GRANT 一次。
-- WarehouseLocationTreeIntegrationTest 仍然逐张断言 relforcerowsecurity，
-- 断言的是**运行时事实**，不是本迁移的归属。
--
-- ---------------------------------------------------------------------------
-- 删除：库位不是可随意物理删除的行
-- ---------------------------------------------------------------------------
-- parent_id 外键是 NO ACTION（默认），删一个还有子节点的库位会被 FK 直接拒；
-- stock_bucket 的复合外键同样会拒掉「还压着库存的库位」。
-- 两道防线都在 DB，应用层先把话说清楚（409），而不是让用户撞一个裸 FK 错误。

-- ===========================================================================
-- 1. 三列：层级 / 容量 / 承重
-- ===========================================================================
ALTER TABLE warehouse_location
  -- 父库位。NULL = 顶层根节点（区）。
  ADD COLUMN IF NOT EXISTS parent_id uuid,
  -- 容量（体积/容量单位，与 weight_limit 同为 NUMERIC，禁浮点记账）。
  ADD COLUMN IF NOT EXISTS capacity numeric(19,6),
  -- 承重上限。
  ADD COLUMN IF NOT EXISTS weight_limit numeric(19,6);

-- 负容量与负承重没有物理意义：库位不存在「负一立方米」或「负五十公斤」的状态。
-- IS NULL 短路让全部存量 NULL 行恒真 —— 存量库位在补列之前没有容量概念，
-- 「没量过」与「量出来是负数」必须能分开。
ALTER TABLE warehouse_location DROP CONSTRAINT IF EXISTS warehouse_location_capacity_check;
ALTER TABLE warehouse_location
  ADD CONSTRAINT warehouse_location_capacity_check
    CHECK(capacity IS NULL OR capacity >= 0);

ALTER TABLE warehouse_location DROP CONSTRAINT IF EXISTS warehouse_location_weight_limit_check;
ALTER TABLE warehouse_location
  ADD CONSTRAINT warehouse_location_weight_limit_check
    CHECK(weight_limit IS NULL OR weight_limit >= 0);

-- 跨租户 / 跨仓库挂父子：一条复合外键同时钉死两条红线（见文件头）。
-- ON DELETE 保持默认 NO ACTION：有子节点或压着库存的库位删不掉。
ALTER TABLE warehouse_location DROP CONSTRAINT IF EXISTS warehouse_location_parent_fk;
ALTER TABLE warehouse_location
  ADD CONSTRAINT warehouse_location_parent_fk
    FOREIGN KEY(tenant_id, warehouse_id, parent_id)
    REFERENCES warehouse_location(tenant_id, warehouse_id, id);

-- ===========================================================================
-- 2. 树完整性：防环 + 层数上限（区 → 巷 → 排 → 架 → 层 → 格 = 6 层）
-- ===========================================================================
CREATE FUNCTION guard_warehouse_location_tree() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  reaches_self boolean;
  -- 刻意不叫 depth：plpgsql 变量名会替换 SQL 里同名的列引用，
  -- 而下面的 CTE 里正有一列叫 depth（同名会让 max(depth) 报「column reference is ambiguous」）。
  ancestor_depth int;
BEGIN
  -- 根节点（顶层「区」）没有父节点，直接放行：这一分支让本触发器对
  -- **存量行与既有 INSERT 完全透明** —— 没有 parent_id 的行走都走不到这里。
  IF NEW.parent_id IS NULL THEN
    RETURN NEW;
  END IF;

  IF NEW.parent_id = NEW.id THEN
    RAISE EXCEPTION '库位 % 不能挂在自身之下：层级会成环。'
      '请改挂到它的上级或同级节点。', NEW.code USING ERRCODE='23514';
  END IF;

  -- 沿 parent 链上行，看能不能走回 NEW.id。depth 上界 32 是死循环的保险丝：
  -- 即便库里已经有环（绕过本触发器写入的脏数据），本查询也会停在一个有限深度上，
  -- 而不是把语句挂死。
  WITH RECURSIVE up AS (
    SELECT l.id, l.parent_id, 1 AS depth
      FROM warehouse_location l
     WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.parent_id
    UNION ALL
    SELECT l.id, l.parent_id, up.depth + 1
      FROM warehouse_location l
      JOIN up ON l.id = up.parent_id
     WHERE l.tenant_id = NEW.tenant_id AND up.depth < 32
  )
  SELECT coalesce(bool_or(up.id = NEW.id), false), coalesce(max(up.depth), 0)
    INTO reaches_self, ancestor_depth
    FROM up;

  IF reaches_self THEN
    RAISE EXCEPTION '库位层级成环：把 % 挂到它自己的子孙之下会让这棵树不再收敛。'
      '请改挂到另一支分支。', NEW.code USING ERRCODE='23514';
  END IF;

  -- 蓝图给的层级就是六层（区 → 巷 → 排 → 架 → 层 → 格）。
  -- up.depth 是祖先链长度，所以本节点的层号是 depth + 1，超过 6 即越界。
  IF ancestor_depth + 1 > 6 THEN
    RAISE EXCEPTION '库位层级超过 6 层（区→巷→排→架→层→格）：% 会落在第 % 层。'
      '超过六层的库位树会让拣货路径（8.3.2.1）无从定义。', NEW.code, ancestor_depth + 1
      USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS warehouse_location_tree_guard ON warehouse_location;
CREATE TRIGGER warehouse_location_tree_guard
  BEFORE INSERT OR UPDATE OF parent_id ON warehouse_location
  FOR EACH ROW EXECUTE FUNCTION guard_warehouse_location_tree();

-- ===========================================================================
-- 3. 索引：树遍历是本模块唯一的高频访问形状
-- ===========================================================================
-- 递归 CTE 沿 (tenant_id, warehouse_id, parent_id) 下钻，warehouse_location_list
-- (tenant_id, warehouse_id, status, code) 帮不上这个忙（它不含 parent_id）。
CREATE INDEX IF NOT EXISTS warehouse_location_tree
  ON warehouse_location(tenant_id, warehouse_id, parent_id, code);