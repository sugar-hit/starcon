-- ===========================================================================
-- 7.2.1.3 释放与过期
-- ===========================================================================
--
-- 蓝图原文（07-inventory.md 7.2.1.3）：
--   职责：处理预占的释放（订单取消、支付超时、拆单减少）与过期回收，提供手动释放入口与
--         批量过期清理，释放时同步恢复可用量。
--   输入：释放请求（业务事件或人工）；过期时间规则；批量清理任务。
--   输出：预占终态记录 + 余额恢复分录。
--   关系：上游 = 09 订单取消事件、7.2.1.1；下游 = 7.1.1.3 可用量。**承诺分配（committed）
--         后不再自动 TTL 释放**，只能由消费或显式释放终结（实体目录 §6.5）——否则已承诺
--         发货的货会被误释放导致超卖。
--
-- ===========================================================================
-- 零、本迁移建两张表，且**不 ALTER V102 / V107 的既有表**
-- ===========================================================================
-- 上一批车道留下的警示照抄执行：ALTER 既有表若无 DEFAULT / 无触发器兜底，会破坏别人的
-- INSERT（仓库有真实事故记录）。本模块需要的一切都已经在 V102 / V107 里预留好了：
--
--   * inventory_reservation.released_quantity（V102 §二）：本模块独占维护，
--     7.2.1.2 的注释逐字写明「released_quantity 归 7.2.1.3，本模块绝不碰」。
--   * reservation_allocation.released_quantity + RELEASED / EXPIRED 两个终态（V107 §三）：
--     容量守卫遇这两个终态跳过、回写触发器把它们排除在占额之外 —— 路已经铺好。
--   * reservation_entry.transition 的 RELEASED / EXPIRED（V107 守卫四）：释放与过期
--     本来就是一次状态迁移，必须落在同一张 append-only 流水上，不另开一张表。
--   * 两个过期候选部分索引：inventory_reservation_expiry_candidates（V102）与
--     reservation_allocation_open（V107），文件头注释都点名「7.2.1.3 会用」。
--
-- 所以本迁移只补两张**本模块自有**的表：一张是单次释放的事实凭据，一张是批量清理的
-- 运行凭据。没有它们的话，批量清理只能留下一堆流水而「谁在什么时候按什么规则扫了哪一批」
-- 无处可查 —— 那正是账实不符开始的地方。
--
-- **表归属**：同 V102 / V107 第零节，两张表都落 warehouse，不跨服务读写 trade 的表。
--
-- ===========================================================================
-- 一、过期规则是什么，为什么不在这里配置
-- ===========================================================================
-- 蓝图输入栏把「过期时间规则」列为本模块的输入之一。实查确认：**规则不是本模块定义的，
-- 是本模块消费并执行的**，来源有两条：
--
--   1. **判据 = 状态 + 过期时刻**：只回收 `status='HELD' AND expires_at IS NOT NULL
--      AND expires_at <= 判定时刻` 的行。这条判据已被 V102 的部分索引
--      （`WHERE status='HELD' AND expires_at IS NOT NULL`）与超时谓词在 DB 层固化，
--      COMMITTED 天然不在候选集里 —— 蓝图那句「承诺后不再自动 TTL 释放」不是靠
--      服务层自觉，而是靠索引谓词与本迁移 CHECK 一起兜住。
--   2. **过期时刻的来源 = 上游登记时写入的 inventory_reservation.expires_at**，
--      7.2.1.1 的入参注释逐字写着「不传则该预占不参与超时筛选（可稍后由 7.2.1.3 补）」——
--      「补」指的是补写时刻（服务层有 setExpiry 动作），不是另建一张配置表。
--
-- 刻意**不建**「默认 TTL 配置表」：库存策略的**配置**归 7.2.2 库存策略（可售量口径 / 安全库存），
-- 7.2.1.3 是执行者不是配置者。在这里造第二套规则来源，只会和 7.2.2 的口径分叉，
-- 而「两个地方各算一次」正是本卷反复拒绝的事。
--
-- ===========================================================================
-- 二、释放为什么不是「一个字段改掉」而是三层记账
-- ===========================================================================
-- 一笔释放要同时说清三件事，缺一件都会让人事后对不上账：
--
--   1. **谁、在什么时候、因为什么** → reservation_release_record（本迁移 §3）
--   2. **状态怎么走的**              → reservation_entry 的 RELEASED / EXPIRED 分录（V107 表）
--   3. **多少量真的还回了可用池**    → 两张表的 restored_quantity
--
-- 第 3 点单列的原因：预占的「已释放量」与「还回可用池的量」**在需求没被全部分配时不相等**。
-- 一笔需求 12、只圈到 10 的货，整笔释放时预占口径上释放 12，但真正占着库存的只有那 10。
-- 把两者混成一个数，可用量的恢复量就会凭空多出 2 —— 而「可用量只准有一个来源」是本卷硬约束。
-- 所以：reservation_release_record.restored_quantity = 各分配本次释放量之和（进可用池的），
--       released_demand_quantity = 预占口径的已释放量（需求量 − 已消耗量）。
--
-- **幂等与并发**：批量清理是「一事务、全成功或全回滚」，候选行 `for update skip locked`
-- 逐条终结，更新一律带 `and status='HELD'` 且检查影响行数 —— 并发两次清理时后一个拿到锁的
-- 事务看到的已是终态，计入 skipped 而不重复释放。手动释放按幂等键（幂等键表 + 请求指纹）
-- 与「已进终态则回放既有凭据」两重保证，见 §3 的两条守卫。
--
-- ===========================================================================
-- 三、两张表
-- ===========================================================================
--
-- 3.1 批量过期清理的运行凭据
--
-- **一条运行 = 一个事务**：候选判定、逐条释放、流水写入、运行凭据落库在同一事务里。
-- 半途失败时整批回滚，不会出现「处理了 37 条然后失败」的中间态 —— 那种状态事后无法判断
-- 哪 37 条是真的终结了。本仓的作业规模（预占是订单/加工驱动，不是 IoT 级频次）完全撑得住
-- 单事务；真需要拆批时由调用方分批调用并各自带幂等键。
--
-- 干跑（dry_run）**只写这一条运行凭据**，processed_count 与 restored_quantity 恒为 0，
-- 且不碰任何库存事实：CHECK 钉死，让「干跑也动了数据」这件事在 DB 层不可能发生。
--
CREATE TABLE reservation_expiry_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 干跑标记。干跑只写本表这一行，不碰预占 / 分配 / 流水（见下方 CHECK）。
  dry_run boolean NOT NULL DEFAULT false,

  -- 规则判定时刻。**由调用方提供**（与 V102 held_at / V107 occurred_at 同纪律）：
  -- 调度器重放同一批作业时必须用同一个判定时刻，否则「同一批候选」两次算出来不一样，
  -- 而幂等键也就失去意义。不传则取服务端当前时刻。
  evaluated_at timestamptz NOT NULL,

  -- 单批处理上限。**上限写进凭据**：事后能回答「这一批是不是被截断了」。
  limit_count integer NOT NULL CHECK(limit_count>0 AND limit_count<=1000),

  -- 三个计数语义不同，不要合并：
  --   candidate_count 判定时刻上的候选总数（**不受 limit 影响**，否则看不出被截断）
  --   processed_count 本次真正终结的预占条数
  --   skipped_count   因并发/状态变化或「无可释放量」而跳过的条数
  candidate_count bigint NOT NULL CHECK(candidate_count>=0),
  processed_count bigint NOT NULL CHECK(processed_count>=0),
  skipped_count   bigint NOT NULL CHECK(skipped_count>=0),
  release_count   bigint NOT NULL CHECK(release_count>=0),

  -- 恢复量与释放量口径同 §二：**两个数不是同一个数**。
  restored_quantity        numeric(19,6) NOT NULL CHECK(restored_quantity>=0),
  released_demand_quantity numeric(19,6) NOT NULL CHECK(released_demand_quantity>=0),

  -- 状态：只有 SUCCEEDED 一档。运行凭据在事务末尾**一次写入**（不先插 RUNNING），
  -- 因为事务失败时整批回滚，凭据与事实要么都在、要么都不在，不存在「失败了一半的凭据」。
  status varchar(16) NOT NULL DEFAULT 'SUCCEEDED'
    CHECK(status='SUCCEEDED'),

  actor_id uuid,
  reason varchar(200),

  -- 幂等键：调度器重投同一次作业时带同一个键，命中即回放既有凭据（不重复扫描）。
  -- NULL 表示调用方没给键 —— 重复调用仍然是安全的（第二次扫不到候选），
  -- 但不会合并成一条凭据，所以推荐调度器显式传键。
  idempotency_key varchar(64),

  created_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz NOT NULL,

  UNIQUE(tenant_id,id),
  CHECK(btrim(coalesce(idempotency_key,'')) <> '' OR idempotency_key IS NULL),
  -- 干跑的恒等式：没处理任何东西，也没恢复任何量。
  CHECK((dry_run AND processed_count=0 AND release_count=0
         AND restored_quantity=0 AND released_demand_quantity=0)
        OR NOT dry_run)
);

-- 部分唯一索引而不是表级 UNIQUE：PostgreSQL 的 UNIQUE 把多个 NULL 视为互不相等，
-- 本来就能允许多条无键凭据 —— 但写成表级 UNIQUE 会让读者以为「键必填」。
-- 部分唯一索引同时表达了「有键才唯一」这个真实规则。
CREATE UNIQUE INDEX reservation_expiry_run_idempotency
  ON reservation_expiry_run(tenant_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX reservation_expiry_run_list
  ON reservation_expiry_run(tenant_id,created_at DESC,id);
CREATE INDEX reservation_expiry_run_evaluated
  ON reservation_expiry_run(tenant_id,evaluated_at DESC,id);

-- ---------------------------------------------------------------------------
-- 3.2 单次释放 / 回收的事实凭据
--
-- append-only：写进去就不许改、不许删（与 V107 的 reservation_entry 同一条纪律）。
-- 「谁把货还回去了」这件事能被事后改写，等于账实核对彻底失去意义。
--
CREATE TABLE reservation_release_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  reservation_id uuid NOT NULL,

  -- mode：RELEASE = 显式释放（人工或业务事件）；EXPIRE = 超时回收。
  -- 两者终态不同且不可互换，所以分开存而不是只存一个「结束」标志：
  -- 事后要能回答「这笔是被取消的还是等太久了」—— 这两者的业务含义完全不同。
  mode varchar(16) NOT NULL CHECK(mode IN ('RELEASE','EXPIRE')),

  -- 释放来源（受控词表）。蓝图点名三类触发：订单取消、支付超时、拆单减少，
  -- 加上人工与本模块自己的批量回收。
  trigger_source varchar(24) NOT NULL
    CHECK(trigger_source IN ('ORDER_CANCELLED','PAYMENT_TIMEOUT','SPLIT_REDUCTION',
                             'MANUAL','EXPIRY_SWEEP')),
  reason varchar(200),

  -- 整笔终结还是部分释放。部分释放时预占继续占额，所以必须能区分。
  terminal boolean NOT NULL,

  -- 迁移前后状态。partial 时两者相同（预占不动）。
  status_before varchar(16) NOT NULL
    CHECK(status_before IN ('HELD','COMMITTED','CONSUMED','RELEASED','EXPIRED')),
  status_after varchar(16) NOT NULL
    CHECK(status_after IN ('HELD','COMMITTED','CONSUMED','RELEASED','EXPIRED')),

  allocation_count integer NOT NULL CHECK(allocation_count>0),

  -- 两个量，口径见 §二，**不要合并成一个字段**
  restored_quantity        numeric(19,6) NOT NULL CHECK(restored_quantity>0),
  released_demand_quantity numeric(19,6) NOT NULL CHECK(released_demand_quantity>0),

  occurred_at timestamptz NOT NULL,
  actor_id uuid,

  -- 所属批量运行。**可空**（手动释放没有运行）。
  -- DEFERRABLE INITIALLY DEFERRED 是必需的：逐条释放时运行凭据在**事务末尾**才写入，
  -- 非延迟外键会在循环中途就报「引用的行还不存在」。
  expiry_run_id uuid,

  -- 幂等键与请求指纹：同键同参 → 回放既有凭据；同键不同参 → 409（而不是悄悄改写历史）。
  idempotency_key varchar(64),
  request_fingerprint varchar(64),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,reservation_id) REFERENCES inventory_reservation(tenant_id,id),
  FOREIGN KEY(tenant_id,expiry_run_id) REFERENCES reservation_expiry_run(tenant_id,id)
    DEFERRABLE INITIALLY DEFERRED,

  -- 守卫一：**只回收 EXPIRED，只释放 RELEASED / 保持原状**。
  --   把「超时回收」写成 RELEASED 会让事后统计分不清「等太久」与「被取消」，
  --   而这两个数在运营上要分开看。
  CHECK((mode='EXPIRE' AND status_after='EXPIRED')
        OR (mode='RELEASE' AND status_after IN ('HELD','COMMITTED','RELEASED'))),
  -- 守卫二：终态标记与状态自洽。partial 不得声称终态，终态也不得停在非终态值上。
  CHECK((terminal AND status_after IN ('RELEASED','EXPIRED'))
        OR (NOT terminal AND status_after=status_before))
);

CREATE UNIQUE INDEX reservation_release_record_idempotency
  ON reservation_release_record(tenant_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX reservation_release_record_reservation
  ON reservation_release_record(tenant_id,reservation_id,occurred_at DESC,id);
CREATE INDEX reservation_release_record_time
  ON reservation_release_record(tenant_id,occurred_at DESC,id);
CREATE INDEX reservation_release_record_run
  ON reservation_release_record(tenant_id,expiry_run_id,id) WHERE expiry_run_id IS NOT NULL;
CREATE INDEX reservation_release_record_mode
  ON reservation_release_record(tenant_id,mode,terminal,occurred_at DESC,id);

-- ---------------------------------------------------------------------------
-- 3.3 守卫：凭据必须与事实对得上
--
-- 这条守卫的作用是把「凭据」与「预占当前状态」钉在一起：
-- 凭据说 status_after=RELEASED，而预占那一行却是 COMMITTED —— 说明有人在释放之后
-- 又动了这笔预占，或者凭据是编的。两种情况都必须当场拒，而不是等人对账时才发现。
--
-- 写入顺序因此固定为「先改分配 → 写分录 → 改预占 → 写凭据」（服务层遵守），
-- 与 V107 分录守卫要求的「to_status 必须等于当前状态」是同一组纪律。
--
CREATE FUNCTION guard_reservation_release_record() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  current_status varchar(16);
BEGIN
  SELECT status INTO current_status FROM inventory_reservation
   WHERE id=NEW.reservation_id AND tenant_id=NEW.tenant_id;
  IF current_status IS NULL THEN
    RAISE EXCEPTION '释放凭据挂在不存在的预占 % 上', NEW.reservation_id USING ERRCODE='23514';
  END IF;
  IF current_status IS DISTINCT FROM NEW.status_after THEN
    RAISE EXCEPTION '释放凭据声称终态为 %，而预占 % 当前是 %：凭据必须与事实一致',
      NEW.status_after, NEW.reservation_id, current_status USING ERRCODE='23514';
  END IF;
  -- 有幂等键就必须有请求指纹：同键不同参的检测靠它，缺了这条就等于没有幂等。
  IF NEW.idempotency_key IS NOT NULL AND NEW.request_fingerprint IS NULL THEN
    RAISE EXCEPTION '释放凭据 % 带幂等键却没有请求指纹：无法区分「重放」与「改写」',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reservation_release_record BEFORE INSERT ON reservation_release_record
  FOR EACH ROW EXECUTE FUNCTION guard_reservation_release_record();

-- append-only：改与删一律拒。理由同 V107 §2.2 ——
-- 应用角色已经拿到了 UPDATE / DELETE 授权，靠「不给权限」保证不可篡改在同服务内不成立。
CREATE FUNCTION guard_reservation_release_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '释放凭据是 append-only 审计凭据，不允许 % ', TG_OP
    USING ERRCODE='42501';
END $$;
CREATE TRIGGER guard_reservation_release_immutable
  BEFORE UPDATE OR DELETE ON reservation_release_record
  FOR EACH ROW EXECUTE FUNCTION guard_reservation_release_immutable();

CREATE TRIGGER guard_reservation_expiry_run_immutable
  BEFORE UPDATE OR DELETE ON reservation_expiry_run
  FOR EACH ROW EXECUTE FUNCTION guard_reservation_release_immutable();

-- ===========================================================================
-- 四、多租户：tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT（与 V102 §六 / V107 §四 同一条）
-- ===========================================================================
-- 刻意排在建表与触发器之后：ALTER TABLE ... ENABLE ROW LEVEL SECURITY 与 CREATE POLICY
-- 都要求目标表已存在，写在建表之前 Flyway 会以「relation does not exist」失败。
--
-- 租户由 backend/libraries/tenancy 在事务内 set_config('app.current_tenant') 写入，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。服务层的 tenant_id 谓词是第二道防线：
-- 忘了绑定上下文时让查询当场返回空，而不是静默返回全表。
--
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['reservation_expiry_run','reservation_release_record'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
    EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',tbl);
  END LOOP;
END $$;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V63、V80、V85、V90、V102、V107 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;