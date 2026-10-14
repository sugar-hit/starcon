-- ===========================================================================
-- V106 8.1.2.2 库位自动分配
--
-- 蓝图 08 卷 8.1.2.2：
--   职责：按规则为入库商品推荐库位：同品类聚类、最短上架路径、容量与承重匹配、
--         待检/残次专用库位强制规则。
--   输入：入库 SKU 与数量；库位属性与占用；路径距离。
--   输出：推荐库位列表（带推荐理由）。
--   关系：上游 = 8.1.2.1、8.2.1.1；下游 = 8.2.2.1 上架任务。
--         **质量状态决定库位类型**：残次品入残次库位是硬规则，不允许混放。
--
-- ---------------------------------------------------------------------------
-- 一、只建一张新表：location_allocation（一条上架决策）
-- ---------------------------------------------------------------------------
-- 库位的**属性与占用**不在本表里各存一份 —— 它们属于 warehouse_location /
-- stock_bucket（8.1.2.1 与 7.1.x 的事实表）。本表只存「决策」：
--
--   * 哪张收货单的哪一行（receipt_id / receipt_line_id）要上架；
--   * 从哪个收货暂存位搬到哪个目标库位（source_location_id / location_id）；
--   * **决策当时**的规则快照（目标类型硬规则、路径跳数、聚类命中数、推荐理由）。
--
-- 快照是必须的：库位占用会随作业变化，事后再算「当初为什么选它」已经算不出来了。
-- 排序与过滤**永远读当前事实**（warehouse_location / stock_bucket），只有
-- 「当时的理由」读快照 —— 两个口径的边界在这里写死，避免日后有人拿快照当现状。
--
-- ---------------------------------------------------------------------------
-- 二、推荐本身不落库（纯计算），只有「选定」才落库
-- ---------------------------------------------------------------------------
-- 蓝图说「输出：推荐库位列表（带推荐理由）」，而 8.2.2.1 上架任务尚未交付：
-- 如果把每次推荐的候选列表都存成行，用户点两下就能刷出几百行垃圾数据，
-- 而下游没有人消费它们。推荐按需算（Service 层 jOOQ 显式 SQL），
-- **落库的只有人真正选定的那一个库位** —— 那才是需要审计的事实。
--
-- ---------------------------------------------------------------------------
-- 三、质量状态 → 库位类型：受控三映射，写成函数而不是散在各处
-- ---------------------------------------------------------------------------
-- 蓝图关系段的红线：「残次品入残次库位是硬规则，不允许混放」。
-- inventory_quality_status.role 是 V1 建的三值词表（SELLABLE /
-- PENDING_INSPECTION / DEFECTIVE），warehouse_location.type 是 V2 建的六值词表。
-- 二者的交集就是三条映射：
--
--   SELLABLE           -> STORAGE     可售品进正式存储库位
--   PENDING_INSPECTION -> QUARANTINE  待检品进待检专用库位
--   DEFECTIVE          -> DEFECTIVE   残次品进残次专用库位
--
-- **另外三类（RECEIVING / SHIPPING / FROZEN）刻意不是任何自动分配的目标**：
--   RECEIVING 是收货暂存位（货还没上架，源位就在那儿），SHIPPING 是发货暂存位，
--   FROZEN 是业务/海关冻结位 —— 冻结的是「这批货不许动」，把它当上架目标等于
--   让一条推荐把货送进监管冻结区。三者都不是「入库商品应该放的地方」。
--
-- 写成函数 location_allocation_required_type 而不是把 CASE 抄进触发器与应用层，
-- 是为了让**规则只有一个定义**；应用层仍按 Java 侧同一张映射先行校验以便给中文报错，
-- 但权威判据是这个函数 —— 测试逐条断言两侧一致。
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION location_allocation_required_type(p_role text) RETURNS varchar
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE p_role
    WHEN 'SELLABLE'           THEN 'STORAGE'
    WHEN 'PENDING_INSPECTION' THEN 'QUARANTINE'
    WHEN 'DEFECTIVE'          THEN 'DEFECTIVE'
  END;
$$;

-- ---------------------------------------------------------------------------
-- 1. 决策表
-- ---------------------------------------------------------------------------
CREATE TABLE location_allocation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 上游 8.2.1.1 收货单（V70）：本决策要上架的那批货就是它的某一行。
  receipt_id uuid NOT NULL,
  receipt_line_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  -- 落库质量状态。**决定目标库位类型**（见文件头第三节），所以 NOT NULL：
  -- 没有质量状态就没有硬规则，等于把红线留了个后门。
  quality_status_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  -- 货现在在哪个库位（收货暂存位，来自 8.2.1.1 goods_receipt_event.location_id）。
  -- 「最短上架路径」= 从这里到目标库位的树跳数，必须记下起点，否则事后无从复核。
  source_location_id uuid NOT NULL,
  -- 目标库位：本次要上的架位。
  location_id uuid NOT NULL,
  allocate_quantity numeric(19,6) NOT NULL,
  -- 需求容量与需求承重：调用方量出来的这批货要占多少容量/承重。
  -- **可空 = 未提供**。可空不是「零」：「没量过这批货多大」与「这批货不占地方」
  -- 是两件事，折成 0 会让没量过的批次凭空通过容量校验。
  required_capacity numeric(19,6),
  required_weight numeric(19,6),
  -- 路径跳数（源位 -> 目标位，按 V105 的 parent_id 树算）与同品类聚类命中 SKU 数。
  -- 都是**决策当时**的快照：库存一动，事实就变了，只有快照留得住。
  source_distance int NOT NULL DEFAULT 0,
  affinity_skus bigint NOT NULL DEFAULT 0,
  -- 硬规则推出的目标库位类型快照（varchar(16) 而非 FK：它是决策依据不是外键目标，
  -- 词表变了也不该让历史决策行失效）。
  required_type varchar(16) NOT NULL,
  -- 推荐理由（人可读，整条快照）。蓝图要求「推荐库位列表（带推荐理由）」，
  -- 而理由必须在选定那一刻就冻住 —— 库位明天满了，不代表当初不该选它。
  rule_summary text NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'PLANNED'
    CHECK(status IN ('PLANNED','RELEASED','CANCELLED')),
  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  released_at timestamptz,
  released_by uuid,
  cancelled_at timestamptz,
  cancel_reason varchar(200) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES goods_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_line_id) REFERENCES goods_receipt_line(tenant_id,id),
  FOREIGN KEY(tenant_id,quality_status_id) REFERENCES inventory_quality_status(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  -- 源位与目标位都用**带 warehouse_id 的三列复合外键**，一次钉死三条红线：
  -- 跨租户引用、跨仓库挂库位、库里根本不存在的 id，全被 DB 拒（23503）。
  FOREIGN KEY(tenant_id,warehouse_id,source_location_id)
    REFERENCES warehouse_location(tenant_id,warehouse_id,id),
  FOREIGN KEY(tenant_id,warehouse_id,location_id)
    REFERENCES warehouse_location(tenant_id,warehouse_id,id),
  CHECK(allocate_quantity>0),
  CHECK(required_capacity IS NULL OR required_capacity>=0),
  CHECK(required_weight IS NULL OR required_weight>=0),
  CHECK(source_distance>=0),
  CHECK(affinity_skus>=0),
  -- 取消必须写原因：「为什么撤掉这个上架决定」是现场复盘的必答题。
  CHECK(status<>'CANCELLED' OR btrim(cancel_reason)<>'')
);

-- 一行收货明细同时只能有一个**未取消**的上架决策。
-- 同一批货同时指派到两个库位，上架时会有一半无处可放 —— 这是 DB 不变量，
-- 不靠应用层先查一次（并发下两个请求都能查到「还没有」）。
-- 部分唯一索引而不是普通 UNIQUE：取消之后要能重新指派。
CREATE UNIQUE INDEX location_allocation_line_open
  ON location_allocation(tenant_id,receipt_line_id)
  WHERE status <> 'CANCELLED';

-- ---------------------------------------------------------------------------
-- 2. 硬规则进 DB：质量状态 -> 库位类型、不许混放、容量不得超占
-- ---------------------------------------------------------------------------
-- 蓝图点名「残次品入残次库位是**硬规则**，不允许混放」。硬规则就该由 DB 裁决：
-- 只在应用层 if 的话，一条运维 SQL 或一次 type 字段的手改就能把残次品放进存储位。
-- 触发器 BEFORE INSERT OR UPDATE 覆盖「决策建立」与「决策被改成另一个库位」两种入口。
CREATE FUNCTION guard_location_allocation_placement() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_role       varchar(20);
  v_type       varchar(16);
  v_required   varchar(16);
  v_need_cap   numeric;
  v_cap_limit  numeric;
  v_need_wt    numeric;
  v_wt_limit   numeric;
  v_taken_cap  numeric;
  v_taken_wt   numeric;
  v_warehouse  uuid;
  v_status     varchar(16);
  -- 不许混放：目标库位上已有的**其他质量角色**（一个即可，全部相同最省事）。
  v_conflict   varchar(20);
BEGIN
  SELECT q.role INTO v_role
    FROM inventory_quality_status q
   WHERE q.tenant_id = NEW.tenant_id AND q.id = NEW.quality_status_id;
  IF v_role IS NULL THEN
    -- 参数必须与格式串**同一行**：plpgsql 的 RAISE 一旦遇到换行就认为语句结束，
    -- 参数另起一行会被解析成新语句（syntax error at or near "NEW.quality_status_id"）。
    -- 长消息按 V105 的写法用字符串字面量续行，而不是把参数推到下一行。
    RAISE EXCEPTION '质量状态 % 在本租户不存在'
      '（库位类型由质量状态决定，不能凭空指派）', NEW.quality_status_id
      USING ERRCODE='23514';
  END IF;

  SELECT l.type, l.warehouse_id, l.status INTO v_type, v_warehouse, v_status
    FROM warehouse_location l
   WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.location_id;
  IF v_type IS NULL THEN
    RAISE EXCEPTION '目标库位 % 在本租户不存在', NEW.location_id USING ERRCODE='23514';
  END IF;

  IF v_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '目标库位 % 当前状态是 %', NEW.location_id, v_status
      USING ERRCODE='23514';
  END IF;

  IF v_warehouse <> NEW.warehouse_id THEN
    RAISE EXCEPTION '目标库位 % 属于仓库 %', NEW.location_id, v_warehouse
      USING ERRCODE='23514';
  END IF;

  v_required := location_allocation_required_type(v_role);
  IF v_required IS NULL THEN
    RAISE EXCEPTION '质量状态角色 % 没有对应的库位类型', v_role
      USING ERRCODE='23514';
  END IF;
  IF v_type <> v_required THEN
    RAISE EXCEPTION '质量状态「%」只能进 % 类库位，目标库位 % 是 % 类：%', v_role, v_required,
      NEW.location_id, v_type,
      CASE WHEN v_role = 'DEFECTIVE' THEN '残次品混进非残次库位会让它跟着正常品一起被拣走发出去'
           WHEN v_role = 'PENDING_INSPECTION' THEN '待检品混进非待检库位等于未过质检就进了可发货区'
           ELSE '质量状态与库位类型不一致会让「这批货在不在待检区」永远答不出来' END
      USING ERRCODE='23514';
  END IF;

  -- 不允许混放：目标库位上已经压着别的质量角色的货，就不能再塞当前角色。
  -- 类型映射已经挡掉了大部分情况，但库位类型是可以被人直接改的（8.1.2.1 允许改），
  -- 所以这里独立再查一次当前实际存放的 bucket，让「混放」是一条运行时事实。
  SELECT min(q.role) INTO v_conflict
    FROM stock_bucket k
    JOIN inventory_quality_status q ON q.tenant_id = k.tenant_id AND q.id = k.quality_status_id
   WHERE k.tenant_id = NEW.tenant_id AND k.location_id = NEW.location_id AND k.on_hand > 0
     AND q.role <> v_role;
  IF v_conflict IS NOT NULL THEN
    RAISE EXCEPTION '目标库位 % 上已压着质量角色「%」的货，与「%」不允许混放',
      NEW.location_id, v_conflict, v_role USING ERRCODE='23514';
  END IF;

  -- 容量与承重：两边都有数才判。任一为空都**不**拦 —— 因为那不是「够」，
  -- 而是「没量过」，服务层会把 evaluated=false 如实报出来（见文件头第四节）。
  IF NEW.required_capacity IS NOT NULL THEN
    SELECT l.capacity INTO v_cap_limit
      FROM warehouse_location l WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.location_id;
    IF v_cap_limit IS NOT NULL THEN
      SELECT coalesce(sum(a.required_capacity),0) INTO v_taken_cap
        FROM location_allocation a
       WHERE a.tenant_id = NEW.tenant_id AND a.location_id = NEW.location_id
         AND a.status <> 'CANCELLED' AND a.id <> NEW.id;
      IF v_taken_cap + NEW.required_capacity > v_cap_limit THEN
        RAISE EXCEPTION '目标库位 % 容量 % 已被未取消的上架承诺占用 %，本次还要 %，超出上限', NEW.location_id,
          v_cap_limit, v_taken_cap, NEW.required_capacity USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;

  IF NEW.required_weight IS NOT NULL THEN
    SELECT l.weight_limit INTO v_wt_limit
      FROM warehouse_location l WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.location_id;
    IF v_wt_limit IS NOT NULL THEN
      SELECT coalesce(sum(a.required_weight),0) INTO v_taken_wt
        FROM location_allocation a
       WHERE a.tenant_id = NEW.tenant_id AND a.location_id = NEW.location_id
         AND a.status <> 'CANCELLED' AND a.id <> NEW.id;
      IF v_taken_wt + NEW.required_weight > v_wt_limit THEN
        RAISE EXCEPTION '目标库位 % 承重上限 % 已被未取消的上架承诺占用 %，本次还要 %，超出上限', NEW.location_id,
          v_wt_limit, v_taken_wt, NEW.required_weight USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER location_allocation_placement_guard
  BEFORE INSERT OR UPDATE ON location_allocation
  FOR EACH ROW EXECUTE FUNCTION guard_location_allocation_placement();

-- ---------------------------------------------------------------------------
-- 3. 热读路径索引
-- ---------------------------------------------------------------------------
-- 列表按仓库 + 状态筛并按创建时间倒序；决策详情按收货单拉；
-- 容量/承重占用按目标库位求和（触发器与服务层推荐排序都用这个形状）。
CREATE INDEX location_allocation_list
  ON location_allocation(tenant_id, warehouse_id, status, created_at DESC);
CREATE INDEX location_allocation_receipt
  ON location_allocation(tenant_id, receipt_id);
CREATE INDEX location_allocation_location
  ON location_allocation(tenant_id, location_id, status);

-- ---------------------------------------------------------------------------
-- 四、容量口径的诚实边界（服务层 capabilities 如实声明同一件事）
-- ---------------------------------------------------------------------------
-- 「容量匹配」比看上去难：capacity 是**体积单位**，而 stock_bucket.on_hand 是**件数**。
-- 本服务里没有「每个 SKU 占多少体积/多少重量」的真相来源 ——
-- sku_ref 明文不存体积/重量（V1 文件头：那些属 catalog，绝不复制成本地权威列），
-- 跨服务又不许 join。所以拿 sum(on_hand) 去比 capacity 是**单位错配**，
-- 一个「容量利用率 80%」会是个没人能解释的数。
--
-- 本迁移只让容量口径覆盖**本模块自己承诺过的量**：
-- location_allocation 里未取消决策的 required_capacity / required_weight 之和。
-- 这是真数（本表自己维护的），而库位上**既有库存占了多少体积**在本服务里
-- 无真相来源 —— 所以服务层在推荐里把 capacityEvaluated / weightEvaluated 标成
-- false 并写明原因，而不是拿件数凑一个假的利用率。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 5. 多租户：RLS + 授权（与 V1 第 9 节、V70 第 10 节、V103 第 5 节同一条纪律）
-- ---------------------------------------------------------------------------
-- 本迁移**新建**表，所以 ENABLE / FORCE / policy / GRANT 都是本表的归属，
-- 不重复写 warehouse_location（V2 建立）与 goods_receipt（V70 建立）的策略 ——
-- 归属写错比不写更糟。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['location_allocation'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON location_allocation TO warehouse_app;
-- 本表**没有** code 序列（决策行不需要人可读单号：它的身份是收货明细行），
-- 所以没有 sequence 授权这一项 —— 别照抄 V70/V103 的 GRANT SEQUENCE，那会报
-- 「relation does not exist」。
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V85、V103 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
