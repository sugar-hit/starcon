-- ===========================================================================
-- 7.2.1.2 预占分配明细
-- ===========================================================================
--
-- 蓝图原文（07-inventory.md 7.2.1.2）：
--   职责：展示一个预占需求实际分配到了哪些 bucket（哪个仓、哪个货位、哪个批次），
--         以及每个分配的 held→终态迁移记录。
--   输入：reservation_id；reservation_allocation 与 reservation_entry。
--   输出：分配明细行 + 状态迁移时间线。
--   关系：上游 = 7.2.1.1；下游 = 7.2.1.3。**分配到具体 bucket 而不是只记总量**，
--         是批次追溯与成本精确分摊的前提。
--
-- ===========================================================================
-- 零、本迁移同时建两张表：一张是**当前事实**，一张是**迁移流水**
-- ===========================================================================
-- V102 §一 明确写着「7.2.1.2 分配明细（reservation_allocation）与 7.2.1.3 的
-- reservation_entry 都不在本车道范围，本迁移一律不建空表占位」。
-- 本车道集成头全库 grep 确认：**这两张表在任何服务里都不存在**，
-- 而蓝图把 reservation_entry 列为本模块的输入之一、输出之一是「状态迁移时间线」。
--
-- 所以本迁移建两张表，理由逐条说清：
--   1. reservation_allocation 是本模块的主表，无可争议。
--   2. reservation_entry 是本模块输出的那张时间线的**唯一数据源**。只建主表不建它，
--      时间线就永远是空的 —— 而「空时间线」会被读成「这个预占没有发生过任何迁移」，
--      正好与「迁移记录功能尚未交付」混淆，正是 V1/V102 反复拒绝的那种假交付。
--   3. 7.2.1.3 释放与过期会**写** reservation_entry（释放也是一次状态迁移），
--      但它是本表的写入方之一而不是唯一写入方；本表是本服务的事实表，
--      同一服务内多写入方共享一张表是正常分工，跨服务才是 AGENTS.md 禁止的那件事。
--
-- **表归属**：同 V102 第零节，两张表都落 warehouse，不跨服务读写 trade 的表。
--
-- ===========================================================================
-- 一、为什么分配行引用「仓 + 货位 + 批次」三个维度，而不是引用 stock_bucket.id
-- ===========================================================================
-- 蓝图把 bucket 举例为「哪个仓、哪个货位、哪个批次」——就是这三个维度。
-- 直接引 stock_bucket(tenant_id,id) 看着更「准」，本迁移刻意不这么做，理由是两条：
--
--   1. stock_bucket 是 7.1.1.1 的**事实表**，它的标识由六个维度
--      （法人 / SKU / 仓 / 库位 / 批次 / 质量状态）唯一决定，还要外键指向
--      inventory_balance 的 balance_id。这意味着分配时必须先有一条 balance 与 bucket 存在，
--      而「预占」这件事在业务上常常**先于**该 bucket 的实物入库发生
--      （先圈住这批货，到货才建 bucket）。引 stock_bucket 会让「先预占后建账」写不进去。
--   2. 预占分配是**长于** bucket 存续时间的承诺。bucket 行会被 7.1.x 的盘点、
--      效期、合桶动作重建，而「这笔货当初圈在了哪个货位」是审计事实，
--      不该因为下游事实表换了一行 id 就查不到。
--
-- 代价与兜底：引三维就意味着「这个货位上真有这批货」不由本表保证。兜底是**维度自洽**：
--   * location 必须是**该 warehouse 名下**的货位 —— 用复合外键
--     (tenant_id,warehouse_id,location_id) → warehouse_location(tenant_id,warehouse_id,id)
--     在 DB 层钉死（V2 为此专门建了 UNIQUE(tenant_id,warehouse_id,id)）。
--     写成两个独立外键的话「仓 A + 仓 B 的货位」这种自相矛盾的行会被放行。
--   * lot 的 sku 必须等于预占的 sku —— 见 §三 guard 里的显式比对。
--   * 可用量是否真的够，由 7.1.1.x 的分配策略（AllocationPolicy / AllocationPriority）裁决，
--     本表**不重复实现**扣减，避免两个地方各算一次且口径分叉。
--
-- ===========================================================================
-- 二、数量口径与「分配到哪里为止」
-- ===========================================================================
-- 每行三个量，口径与 V102 §二 同源：
--   allocated_quantity 圈到这个 bucket 上的量（建行即有）
--   consumed_quantity  真正从这个 bucket 出库/领料的量
--   released_quantity  被释放 / 超时回收的量
--   remaining           = allocated − consumed − released（生成列）
--
-- 父表 inventory_reservation.allocated_quantity 的定义（本迁移 §三 的触发器维护）：
--   **非终态分配之和**（HELD + COMMITTED + CONSUMED），即 RELEASED / EXPIRED 不再占额。
--   CONSUMED 仍计入：货已经出库了，「圈了多少」这个事实不因出库而消失；
--   若出库后就把 allocated 抹掉，列表上「已分配 12」会在出库瞬间跳成 0，
--   批次追溯与成本分摊都失去依据。
--
-- 守恒式（CHECK 钉死，与 V102 的 ck 同一条纪律）：
--   consumed + released ≤ allocated        （同一行不能既出库又释放）
--   单行 active 分配量 ≤ 父预占 requested  （跨行守恒由 §三 的触发器保证，
--                                          单个 CHECK 看不到兄弟行）
--
-- 数量 NUMERIC(19,6)：与 V102 同口径（比 V1/V2 的 19,4 多两位小数，加工领料按重量折算要用），
-- Java 侧一律 BigDecimal，全程禁浮点。
--
-- ===========================================================================
-- 三、状态机与三条 DB 级守卫
-- ===========================================================================
-- 分配级五态，与父表同词表（受控词表，逐字一致）：
--   HELD       已圈到 bucket、尚未承诺（可 → COMMITTED / RELEASED / EXPIRED）
--   COMMITTED  已承诺（可 → CONSUMED / RELEASED）
--   CONSUMED   已真实出库（**终态**）
--   RELEASED   被释放（**终态**，写入方是 7.2.1.3）
--   EXPIRED    超时回收（**终态**，写入方是 7.2.1.3）
--
-- 守卫一 guard_reservation_allocation_insert：建行即 HELD，且不带任何终结痕迹。
--   「插一行 CONSUMED」是脏数据：没圈过就没承诺过，凭什么说已消耗。
--
-- 守卫二 容量守卫 guard_reservation_allocation_capacity，**INSERT 与 UPDATE 共用**：
--   * 父预占存在且未终结 —— 已终结的预占上不能再挂新分配。
--   * **跨行守恒**：该预占下所有非终态分配之和 + 本行 ≤ 父预占 requested_quantity。
--     越界即超卖 —— 这是 DB 级兜底，与 V102 ck_balance_available、V2 available>=0 同一道防线。
--   * lot 的 sku 必须等于父预占的 sku：把 A 品类的批次挂到别的预占上，
--     会让效期与成本分摊算到错的 SKU 上。
--
-- 守卫三 更新守卫 guard_reservation_allocation_update：
--   * 终态不可复活（与 V102 同一条铁律）。已出库的分配不能被改回 HELD，
--     否则那批货会被重新圈一次，直接造成超卖。
--   * **bucket 与预占不可改指**：一行分配永远指向它当初圈的那一个 bucket。
--     改指等于把审计痕迹改掉（「当初圈在 A 货位」被改成「圈在 B 货位」），
--     而批次追溯与成本分摊正是本模块存在的理由。
--   * 状态与时间戳自洽：COMMITTED 必带 committed_at；三个终态必带 settled_at；
--     HELD 不得带 committed_at；非终态不得带 settled_at。
--   * version 自增 + updated_at 维护：乐观锁在 DB 层生效。
--
-- 守卫四 guard_reservation_entry：
--   * ALLOCATED 那条分录 from_status 必为空且 to_status 必为 HELD；
--     其余分录 from_status 必非空且 from→to 必须是合法迁移。
--   * 分配级分录必须挂在**真实存在且属于同一预占**的分配上，且该分配此刻的状态
--     恰等于分录的 to_status —— 否则可以凭空追加一条「它曾经变成 CONSUMED」的假流水，
--     时间线就成了编的。这是时间线可信度的底线。
--   * reservation_entry 全表 append-only：UPDATE / DELETE 一律拒（§三 之四）。
--
-- 第四条触发器 sync_reservation_allocated_quantity：
--   把父表 allocated_quantity 重算成「非终态分配之和」。
--   放触发器而不是 Java 里回写：V102 §一 已经把「allocated_quantity 由 7.2.1.2 回写」
--   写进契约，而 7.2.1.3 的释放路径也会改分配状态 —— 两条写入路径各回写一次的话，
--   迟早有一次漏掉，父表就与明细永久对不上账。触发器是**唯一**能让它对不上的方式
--   就是绕过本服务裸写 SQL，而裸写也照样被重算。
--   父行已终结时不回写：V102 的 guard_reservation_state_machine 会拒任何对终态行的 UPDATE，
--   这里主动跳过，顺带保住「终态行是历史快照、其 allocated_quantity 就是终结那刻的值」这个性质。
--
-- ===========================================================================
-- 四、多租户：tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT（与 V102 §六 同一条）
-- ===========================================================================
-- 刻意排在建表与触发器之后：ALTER TABLE ... ENABLE ROW LEVEL SECURITY 与 CREATE POLICY
-- 都要求目标表已存在，写在建表之前 Flyway 会以「relation does not exist」失败。
--
-- 三张表同属一个预占链路、同一套 RLS 策略表达式：租户由 backend/libraries/tenancy
-- 在事务内 set_config('app.current_tenant') 写入，客户端的 X-Tenant-Id 头进不来也改不动它。
-- 服务层的 tenant_id 谓词是第二道防线：忘了绑定上下文时让查询当场返回空，
-- 而不是静默返回全表。
--

-- ===========================================================================
-- 1. 分配明细行
-- ===========================================================================
CREATE TABLE reservation_allocation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 所属预占。**同库外键**：三张表同属 warehouse（V102 第零节），
  -- 外键在这里是纯收益 —— 删掉一条预占会连带把它的分配明细带走，
-- -- 而没有外键的话明细会变成查不到主人的孤儿行。
  reservation_id uuid NOT NULL,

  -- bucket 三维（§一）。warehouse 恒非空；location 可空（虚拟仓 / 直发仓没有货位档案）；
  -- lot 可空（非批次管理品，与 V2 stock_bucket.lot_id 可空同一判断）。
  warehouse_id uuid NOT NULL,
  location_id uuid,
  lot_id uuid,

  -- 三个量 + 一个生成列，口径见 §二
  allocated_quantity numeric(19,6) NOT NULL,
  consumed_quantity  numeric(19,6) NOT NULL DEFAULT 0,
  released_quantity  numeric(19,6) NOT NULL DEFAULT 0,
  remaining numeric(19,6) GENERATED ALWAYS AS
    (allocated_quantity - consumed_quantity - released_quantity) STORED,

  status varchar(16) NOT NULL DEFAULT 'HELD'
    CHECK(status IN ('HELD','COMMITTED','CONSUMED','RELEASED','EXPIRED')),

  -- 时间轴：allocated_at 恒有；committed_at 只在承诺后；settled_at 只在三个终态。
  -- 与父表同构，理由相同（让「什么时候发生的」与「现在是什么状态」不可能互相矛盾）。
  allocated_at timestamptz NOT NULL DEFAULT now(),
  committed_at timestamptz,
  settled_at timestamptz,
  note varchar(200),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 复合外键的目标：让「这条分配属于哪个预占」由数据库裁决，
  -- 且同一预占下的分配 id 组合唯一（父表的复合唯一键，V102 UNIQUE(tenant_id,id) 之上再加一层）。
  UNIQUE(tenant_id,reservation_id,id),

  FOREIGN KEY(tenant_id,reservation_id) REFERENCES inventory_reservation(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouse(tenant_id,id),
  -- 货位必须属于所声明的那个仓：复合外键（V2 建 UNIQUE(tenant_id,warehouse_id,id) 就是为了这个）
  FOREIGN KEY(tenant_id,warehouse_id,location_id)
    REFERENCES warehouse_location(tenant_id,warehouse_id,id),
  FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id),

  CHECK(allocated_quantity>0),
  CHECK(consumed_quantity>=0 AND consumed_quantity<=allocated_quantity),
  CHECK(released_quantity>=0 AND released_quantity<=allocated_quantity),
  -- 同一行不能既出库又释放：那意味着同一批货出了库又回到了可用池。
  CHECK(consumed_quantity+released_quantity<=allocated_quantity)
);

-- 明细页默认按「圈定时间倒序」；同一预占下的分配数通常是个位数到几十，
-- 索引以 (预占, 状态) 打头让「按状态筛 + 按预占取」走索引而不是全表扫。
CREATE INDEX reservation_allocation_reservation
  ON reservation_allocation(tenant_id,reservation_id,allocated_at DESC,id);
CREATE INDEX reservation_allocation_status
  ON reservation_allocation(tenant_id,reservation_id,status,allocated_at DESC,id);
-- 按 bucket 反查「这个货位/批次上圈着哪些预占」：批次追溯与成本分摊的另一面。
CREATE INDEX reservation_allocation_bucket
  ON reservation_allocation(tenant_id,warehouse_id,location_id,lot_id)
  WHERE status IN ('HELD','COMMITTED');
-- 超时回收候选（7.2.1.3 会用）：只覆盖还在 HELD 的行。
CREATE INDEX reservation_allocation_open
  ON reservation_allocation(tenant_id,reservation_id,allocated_at,id)
  WHERE status='HELD';

-- ---------------------------------------------------------------------------
-- 1.1 建行守卫：建行即 HELD，且不带任何终结痕迹
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_reservation_allocation_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status<>'HELD' THEN
    RAISE EXCEPTION '分配必须以 HELD 建立，终态只能由状态迁移产生'
      USING ERRCODE='23514';
  END IF;
  IF NEW.allocated_at IS NULL THEN
    RAISE EXCEPTION '分配缺少 allocated_at：分配建立时刻是批次追溯的时间基准'
      USING ERRCODE='23514';
  END IF;
  -- 建行即终态的痕迹也拒：未承诺、未出库、未释放的行不该带这些数。
  IF NEW.committed_at IS NOT NULL OR NEW.settled_at IS NOT NULL
     OR NEW.consumed_quantity<>0 OR NEW.released_quantity<>0 THEN
    RAISE EXCEPTION '分配以 HELD 建立时不得携带承诺 / 终结痕迹'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reservation_allocation_insert BEFORE INSERT ON reservation_allocation
 FOR EACH ROW EXECUTE FUNCTION guard_reservation_allocation_insert();

-- ---------------------------------------------------------------------------
-- 1.2 容量守卫：跨行守恒 + 批次 SKU 自洽 + 父预占未终结
-- ---------------------------------------------------------------------------
-- **INSERT 与 UPDATE 共用同一条守卫**，这一点是被实测打出来的：第一版只把容量判据挂在
-- UPDATE 触发器上，于是「超卖」这件事在插入时被漏过去 —— 插入成功、随后的父表回写
-- 撞上 inventory_reservation 的 allocated_quantity<=requested_quantity 检查而失败。
-- 结果虽然仍是拒绝（父表那道 CHECK 兜住了），但错误文案只剩一句
-- "violates check constraint"，完全指不出是「哪一笔分配把总量顶破了」。
-- 漏的那一笔分配才是运维要找的东西，所以判据必须落在分配行自己身上。
CREATE FUNCTION guard_reservation_allocation_capacity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  requested numeric;
  parent_status varchar(16);
  parent_sku uuid;
  sibling_sum numeric;
  lot_sku uuid;
BEGIN
  -- 父预占：存在，且没进终态。报的是「哪一条分配」，而不是笼统的约束冲突。
  SELECT requested_quantity, status, sku_id INTO requested, parent_status, parent_sku
    FROM inventory_reservation WHERE id=NEW.reservation_id AND tenant_id=NEW.tenant_id;
  IF parent_status IS NULL THEN
    RAISE EXCEPTION '分配挂在不存在的预占 % 上', NEW.reservation_id
      USING ERRCODE='23514';
  END IF;
  IF parent_status IN ('CONSUMED','RELEASED','EXPIRED') THEN
    RAISE EXCEPTION '预占 % 已终结于 %，不能再往它上面分配',
      NEW.reservation_id, parent_status USING ERRCODE='23514';
  END IF;

  -- 跨行守恒：所有非终态分配之和 + 本行 ≤ 父预占需求量。越界即超卖。
  -- RELEASED / EXPIRED 不占额，所以进这两个终态时跳过本判据。
  IF NEW.status NOT IN ('RELEASED','EXPIRED') THEN
    -- BEFORE INSERT 时本行尚未可见，求和天然不含自己；BEFORE UPDATE 时要显式排除自己。
    SELECT coalesce(sum(allocated_quantity),0) INTO sibling_sum
      FROM reservation_allocation
     WHERE reservation_id=NEW.reservation_id
       AND tenant_id=NEW.tenant_id
       AND status NOT IN ('RELEASED','EXPIRED')
       AND (TG_OP='INSERT' OR id<>OLD.id);
    IF sibling_sum + NEW.allocated_quantity > requested THEN
      RAISE EXCEPTION '这一笔分配会让预占 % 的已分配量达到 %，超过需求量 %：超卖',
        NEW.reservation_id, sibling_sum + NEW.allocated_quantity, requested
        USING ERRCODE='23514';
    END IF;
  END IF;

  -- 批次的 SKU 必须等于预占的 SKU：把 A 品的批次挂到别的预占上，
  -- 会让效期判断与成本分摊算到错的 SKU 上。
  IF NEW.lot_id IS NOT NULL THEN
    SELECT sku_id INTO lot_sku FROM inventory_lot WHERE id=NEW.lot_id AND tenant_id=NEW.tenant_id;
    IF lot_sku IS NULL THEN
      RAISE EXCEPTION '批次 % 不存在', NEW.lot_id USING ERRCODE='23514';
    END IF;
    IF lot_sku IS DISTINCT FROM parent_sku THEN
      RAISE EXCEPTION '批次 % 的 SKU 与预占 % 的 SKU 不是同一个商品', NEW.lot_id, NEW.reservation_id
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reservation_allocation_capacity BEFORE INSERT OR UPDATE ON reservation_allocation
 FOR EACH ROW EXECUTE FUNCTION guard_reservation_allocation_capacity();

-- ---------------------------------------------------------------------------
-- 1.3 更新守卫：终态不可复活 + 不可改指 + 时间戳自洽
-- ---------------------------------------------------------------------------
-- 触发器按名字字母序执行，capacity 在 update 之前跑：先判「这一笔合不合法」，
-- 再判「这一行能不能被改」。顺序反过来时，越界的那一笔会先被别的原因挡掉，
-- 报错指不到真正的问题上。
CREATE FUNCTION guard_reservation_allocation_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- 终态不可复活。三个终态分别报各自的名字：运维看到 "EXPIRED 不能复活"
  -- 比看到一句笼统的非法迁移更知道是哪条迟到命令撞上了终态。
  IF OLD.status IN ('CONSUMED','RELEASED','EXPIRED') AND NEW.status<>OLD.status THEN
    RAISE EXCEPTION '分配 % 已处于终态 %，不能迁回到 %：迟到命令不得复活已终结的分配',
      OLD.id, OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;
  -- 终态 → 终态之间也不许改（已 RELEASED 的行改成 CONSUMED 同样荒谬：货已经还回可用池）。
  IF OLD.status IN ('CONSUMED','RELEASED','EXPIRED') THEN
    RAISE EXCEPTION '分配 % 已终结于 %，不可再改', OLD.id, OLD.status
      USING ERRCODE='23514';
  END IF;

  -- 不可改指：bucket 三维与所属预占一旦写定就锁死。
  -- 改掉等于把「当初圈在哪」这段审计痕迹改写掉，而批次追溯与成本分摊正是本模块存在的理由。
  IF NEW.reservation_id IS DISTINCT FROM OLD.reservation_id
     OR NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
     OR NEW.location_id IS DISTINCT FROM OLD.location_id
     OR NEW.lot_id IS DISTINCT FROM OLD.lot_id THEN
    RAISE EXCEPTION '分配 % 的预占与 bucket 三维不可改指：审计痕迹必须留在原处', OLD.id
      USING ERRCODE='23514';
  END IF;

  -- 状态与时间戳自洽（与 V102 guard_reservation_state_machine 同一组判据）
  IF NEW.status='COMMITTED' AND NEW.committed_at IS NULL THEN
    RAISE EXCEPTION '分配 % 承诺时必须写 committed_at', OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.status='HELD' AND NEW.committed_at IS NOT NULL THEN
    RAISE EXCEPTION '分配 % 还在 HELD，不应有 committed_at', OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.status IN ('CONSUMED','RELEASED','EXPIRED') AND NEW.settled_at IS NULL THEN
    RAISE EXCEPTION '分配 % 进入终态 % 时必须写 settled_at', OLD.id, NEW.status
      USING ERRCODE='23514';
  END IF;
  IF NEW.status NOT IN ('CONSUMED','RELEASED','EXPIRED') AND NEW.settled_at IS NOT NULL THEN
    RAISE EXCEPTION '分配 % 状态为 %，不该有 settled_at', OLD.id, NEW.status
      USING ERRCODE='23514';
  END IF;

  -- 版本自增 + 更新时间：并发更新时后写者拿不到旧版本的行。
  NEW.version := OLD.version + 1;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reservation_allocation_update BEFORE UPDATE ON reservation_allocation
 FOR EACH ROW EXECUTE FUNCTION guard_reservation_allocation_update();

-- ---------------------------------------------------------------------------
-- 1.3 父表 allocated_quantity 回写：非终态分配之和
-- ---------------------------------------------------------------------------
CREATE FUNCTION sync_reservation_allocated_quantity(target uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  UPDATE inventory_reservation r
     SET allocated_quantity = coalesce((
           SELECT sum(a.allocated_quantity) FROM reservation_allocation a
            WHERE a.reservation_id=r.id AND a.tenant_id=r.tenant_id
              AND a.status NOT IN ('RELEASED','EXPIRED')), 0)
   WHERE r.id=target
     -- 父行已终结则不回写：V102 的状态机触发器会拒任何对终态行的 UPDATE，
     -- 且「终态行是历史快照」本来就要求它的 allocated_quantity 停在终结那刻的值。
     AND r.status NOT IN ('CONSUMED','RELEASED','EXPIRED');
END $$;

CREATE FUNCTION on_reservation_allocation_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    PERFORM sync_reservation_allocated_quantity(OLD.reservation_id);
    RETURN OLD;
  END IF;
  PERFORM sync_reservation_allocated_quantity(NEW.reservation_id);
  RETURN NEW;
END $$;
CREATE TRIGGER sync_reservation_allocated AFTER INSERT OR UPDATE OR DELETE ON reservation_allocation
 FOR EACH ROW EXECUTE FUNCTION on_reservation_allocation_changed();

-- ===========================================================================
-- 2. 状态迁移流水（时间线）
-- ===========================================================================
-- append-only 的迁移凭据：写进去就不许改、不许删。
-- 「谁能改一条历史」这个问题在批次追溯与成本分摊场景下是致命的 ——
-- 能改就意味着追溯结论可以被事后编出来。
CREATE TABLE reservation_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  reservation_id uuid NOT NULL,
  -- **可空**：分配级迁移带上分配 id；预占级迁移（如整笔预占进入终态）留空。
  -- 留空而不是另开一张表，是因为两种迁移在时间线上本来就该按时间混排 ——
  -- 分成两张表就只能并排显示，而并排显示会把「分配在 10:03 承诺、预占在 10:03 也承诺」
  -- 拆成两条看不出因果的记录。
  allocation_id uuid,

  -- 迁移语义：建行 / 承诺 / 消耗 / 释放 / 超时（受控词表，§三 守卫三逐字校验）
  transition varchar(16) NOT NULL
    CHECK(transition IN ('ALLOCATED','COMMITTED','CONSUMED','RELEASED','EXPIRED')),
  -- from_status 只有 ALLOCATED 那条为空（此前没有状态）；
  -- to_status 恒非空。合法性由守卫三裁决，不靠这里的 CHECK。
  from_status varchar(16)
    CHECK(from_status IS NULL OR from_status IN ('HELD','COMMITTED','CONSUMED','RELEASED','EXPIRED')),
  to_status varchar(16) NOT NULL
    CHECK(to_status IN ('HELD','COMMITTED','CONSUMED','RELEASED','EXPIRED')),

  -- 本次迁移涉及的数量（建行 = 圈定的量；消耗 = 本次实际出库的量……）。
  -- 单列出来而不是让前端拿「当前状态量」倒推：时间线上每一步的量各不相同。
  quantity numeric(19,6) NOT NULL CHECK(quantity>0),
  -- 发生时刻。**由调用方提供**（与 V102 的 held_at 同纪律：跨服务调用方与本服务有时钟差，
  -- 用服务端当前时间会把「什么时候发生的」这个事实换成「本服务什么时候知道的」）。
  occurred_at timestamptz NOT NULL,
  -- 谁做的。写审计痕迹时，「谁」和「什么时候」一样不能省。
  actor_id uuid,
  reason varchar(200),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,reservation_id) REFERENCES inventory_reservation(tenant_id,id),
  FOREIGN KEY(tenant_id,reservation_id,allocation_id)
    REFERENCES reservation_allocation(tenant_id,reservation_id,id)
);

-- 时间线默认按发生时刻正序；同刻并列时用 id 收尾，保证两次请求顺序一致。
CREATE INDEX reservation_entry_timeline
  ON reservation_entry(tenant_id,reservation_id,occurred_at,id);
CREATE INDEX reservation_entry_allocation
  ON reservation_entry(tenant_id,allocation_id,occurred_at,id)
  WHERE allocation_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2.1 分录守卫：迁移必须真实发生过
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_reservation_entry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  current_status varchar(16);
  legal boolean;
BEGIN
  IF NEW.transition='ALLOCATED' THEN
    IF NEW.from_status IS NOT NULL OR NEW.to_status<>'HELD' THEN
      RAISE EXCEPTION '建行分录的 from 必须为空、to 必须为 HELD'
        USING ERRCODE='23514';
    END IF;
  ELSE
    IF NEW.from_status IS NULL THEN
      RAISE EXCEPTION '分录 % 缺 from_status：除建行外每次迁移都有来源状态', NEW.id
        USING ERRCODE='23514';
    END IF;
    -- 合法迁移矩阵与 V102 guard_reservation_state_machine 的后半段同源：
    -- HELD → COMMITTED / RELEASED / EXPIRED；COMMITTED → CONSUMED / RELEASED。
    legal := CASE NEW.from_status
                WHEN 'HELD'      THEN NEW.to_status IN ('COMMITTED','RELEASED','EXPIRED')
                WHEN 'COMMITTED' THEN NEW.to_status IN ('CONSUMED','RELEASED')
                ELSE false
              END;
    IF NOT legal THEN
      RAISE EXCEPTION '非法迁移 % → %：预占与分配的合法迁移只有上面这几种', NEW.from_status, NEW.to_status
        USING ERRCODE='23514';
    END IF;
  END IF;

  -- 分配级分录：必须挂在**同一预占下真实存在**的分配上，
  -- 且该分配此刻的状态恰等于分录的 to_status ——
  -- 否则可以凭空追加一条「它曾经变成 CONSUMED」的假流水，时间线就成了编的。
  IF NEW.allocation_id IS NOT NULL THEN
    SELECT status INTO current_status FROM reservation_allocation
     WHERE id=NEW.allocation_id AND tenant_id=NEW.tenant_id AND reservation_id=NEW.reservation_id;
    IF current_status IS NULL THEN
      RAISE EXCEPTION '分录挂在不存在的分配 % 上（预占 %）', NEW.allocation_id, NEW.reservation_id
        USING ERRCODE='23514';
    END IF;
    IF current_status<>NEW.to_status THEN
      RAISE EXCEPTION '分录的目标状态 % 与分配 % 的当前状态 % 不符：流水不得与事实脱节',
        NEW.to_status, NEW.allocation_id, current_status USING ERRCODE='23514';
    END IF;
  ELSE
    SELECT status INTO current_status FROM inventory_reservation
     WHERE id=NEW.reservation_id AND tenant_id=NEW.tenant_id;
    IF current_status IS NULL THEN
      RAISE EXCEPTION '分录挂在不存在的预占 % 上', NEW.reservation_id USING ERRCODE='23514';
    END IF;
    IF current_status<>NEW.to_status THEN
      RAISE EXCEPTION '分录的目标状态 % 与预占 % 的当前状态 % 不符：流水不得与事实脱节',
        NEW.to_status, NEW.reservation_id, current_status USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reservation_entry_insert BEFORE INSERT ON reservation_entry
 FOR EACH ROW EXECUTE FUNCTION guard_reservation_entry();

-- ---------------------------------------------------------------------------
-- 2.2 append-only：改与删一律拒
-- ---------------------------------------------------------------------------
-- 为什么单独一条触发器而不是靠 GRANT 只给 INSERT：应用角色已经拿到了 UPDATE / DELETE 授权
-- （其他模块的清理作业共用同一套授权），靠「不给他这个权限」来保证不可篡改在同服务内不成立。
CREATE FUNCTION guard_reservation_entry_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '预占迁移流水是 append-only 审计凭据，不允许 % ', TG_OP
    USING ERRCODE='42501';
END $$;
CREATE TRIGGER guard_reservation_entry_immutable
 BEFORE UPDATE OR DELETE ON reservation_entry
 FOR EACH ROW EXECUTE FUNCTION guard_reservation_entry_immutable();

-- ===========================================================================
-- 3. 多租户：tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT（与 V102 §六 同一条）
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['reservation_allocation','reservation_entry'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
    EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',tbl);
  END LOOP;
END $$;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V63、V80、V85、V90、V102 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
