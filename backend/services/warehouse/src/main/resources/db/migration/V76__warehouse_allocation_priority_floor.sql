-- ===========================================================================
-- 7.8.1.2 优先级与保底：在分摊冲突里先保住高价值店铺的底线
-- ===========================================================================
-- 蓝图原文：「在分摊冲突时按优先级裁决，并为高价值店铺设置保底可用量与安全垫，
-- 防止低优先级店铺长期断货影响其店铺绩效与平台考核。输入：店铺优先级；保底数量/比例；
-- 当前销售与绩效。输出：分摊结果（含被压低店铺的提示）；保底生效记录。」
-- 以及关系段的红线：「**保底是策略不是事实**，需可解释可调整。」
--
-- ---------------------------------------------------------------------------
-- 这个模块为什么不能是 7.8.1.1 里的一列
-- ---------------------------------------------------------------------------
-- 7.8.1.1 的 allocation_policy_member.priority 回答的是「余量按什么次序分」。
-- 本模块回答的是**另一个问题**：「货不够分的时候，先保住谁」。
-- 这两个次序会打架：一家销量高但优先级低的店，在余量分摊里排最后，
-- 而它的保底又必须被满足 —— 若把保底做成 member 上的一列，
-- 「余量次序」与「保底次序」就只能共用一个数字，于是
-- 「既排在余量最后、又必须最先保底」这家店无法表达。
-- 所以保底是**独立的一档**（allocation_floor），仲裁次序复用策略解析出的次序。
--
-- ---------------------------------------------------------------------------
-- 守恒依然要成立，而且必须是 DB 不变量
-- ---------------------------------------------------------------------------
--   可用量 available = 分摊缓冲 buffer + 可分配量 allocatable      （V68 的 CHECK 仍然在跑）
--   保底池 floorPool = min(allocatable, Σ(保底 + 安全垫))
--   余量池 residual   = allocatable − 保底池
--   可分配量 allocatable = Σ各店最终分摊 + 未分配
--
-- 本迁移不改 allocation_run / allocation_run_line 的任何一列（那是 7.8.1.1 的表，
-- 它的 CHECK 与延迟触发器会照常核对守恒）。本模块只多写一张
-- allocation_priority_resolution，把「保底要了多少 / 给到多少 / 差多少 / 为��么差」
-- 逐店留痕。这正是蓝图输出里的「保底生效记录」与「被压低店铺的提示」。
--
-- ---------------------------------------------------------------------------
-- 「保底是策略不是事实」落到 schema 上是什么
-- ---------------------------------------------------------------------------
-- 与 7.8.1.1 同一套：一档 = 一个时间区间 + 写定后不可改。
-- 改保底 = 收口旧档 + 开新档（触发器 guard_allocation_floor_immutable 钉死）。
-- 而 allocation_priority_resolution 是**事实**：哪一次分摊、哪个店、保底给了多少，
-- 那一行一旦写下就不许改（append-only 触发器）—— 事后复盘「当时为什么它没保住」只能靠它。
--
-- ---------------------------------------------------------------------------
-- 未交付的上游（如实标注，不臆造）
-- ---------------------------------------------------------------------------
-- 「输入：……当前销售与绩效」里的**绩效**没有真相来源：
-- 店铺考核指标属 17 绩效域，仓库服务里既没有表也没有授权 API，
-- 所以本模块不接绩效、不算绩效分。保底的依据只有两样真实存在的东西：
-- 保底数量/比例（人定的策略）与安全垫（人定的策略）。
-- 换句话说：**保底是人为承诺的底线，不是系统按绩效算出来的下限**，
-- 这句话会原样出现在 capabilities 端点里。

-- ===========================================================================
-- 1. allocation_floor · 保底分档
-- ===========================================================================
CREATE TABLE allocation_floor (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 保底挂在**策略**上，与 7.8.1.1 的成员同一个理由：
  -- 同一店铺在不同 SKU 下可能参与不同的分摊、也可能有不同的保底承诺。
  -- 挂在店铺上会让「这一批货承诺保底给谁」必须靠另一张表回答。
  policy_id uuid NOT NULL,

  -- 跨服务定位键：店铺在 channel 服务（2.3.1.1/2.3.2.1 已交付），不建外键。
  source_system varchar(32) NOT NULL,
  reference_id uuid NOT NULL,
  reference_label varchar(150) NOT NULL DEFAULT '',

  -- 保底口径。二选一，互斥由下面的 CHECK 钉死：
  --   QUANTITY 按件数保底（「这家店无论卖多少，至少给 50 件」），
  --           这也是高价值店铺的真实诉求 —— 平台考核看的是断货天数不是占比。
  --   RATIO    按可分配量的比例保底（「任何时候至少给它 20%」）。
  floor_mode varchar(8) NOT NULL CHECK(floor_mode IN ('RATIO','QUANTITY')),
  floor_ratio numeric(9,6) NOT NULL DEFAULT 0,
  floor_quantity numeric(19,6) NOT NULL DEFAULT 0,

  -- 安全垫：保底之上再留的件数，抵御平台回写延迟与临时订单。
  -- **只支持按件数**，不提供比例口径 —— 7.2.2.1 的 buffer_ratio 已经是比例口径，
  -- 再造一个比例口径的安全垫，界面上就会出现两个都叫「比例」的百分比，
  -- 运营必然会把它们当成同一个东西（与 V68 文件头同一条理由）。
  safety_quantity numeric(19,6) NOT NULL DEFAULT 0,

  -- 生效区间（左闭右开）。effective_to 为 NULL = 至今仍在生效。
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,

  note varchar(500) NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,policy_id) REFERENCES allocation_policy(tenant_id,id) ON DELETE CASCADE,

  -- 保底与安全垫都必须是整件：半件既不属于这家也不属于那家 —— 那是超卖。
  CHECK(floor_quantity >= 0 AND floor_quantity = trunc(floor_quantity)),
  CHECK(safety_quantity >= 0 AND safety_quantity = trunc(safety_quantity)),
  CHECK(floor_ratio >= 0 AND floor_ratio <= 1),
  -- 两列互斥：同时给比例和件数，「到底保底保多少」这个问题就没有唯一答案。
  CHECK((floor_mode='RATIO'    AND floor_quantity = 0) OR
        (floor_mode='QUANTITY' AND floor_ratio = 0)),

  -- 保底必须真的保得住一点。保底为 0 且安全垫为 0 的那一行是「以为自己配了保底」，
  -- 而它在算法里与「没配」完全等价 —— 界面上看起来配了，实际一点作用没有。
  CHECK(floor_quantity > 0 OR safety_quantity > 0 OR floor_ratio > 0),

  CHECK(effective_to IS NULL OR effective_to > effective_from)
);

-- 同一策略下同一店铺**至多一档保底仍在生效**。
-- 同一个店出现两个保底承诺，「它到底该保多少」就有两套答案，
-- 而算法只能挑一个 —— 挑哪一个都不对，因为另一个也是人定的承诺。
-- （部分唯一索引：effective_to IS NULL 的那些行 PG 不会用普通 UNIQUE 去重。）
CREATE UNIQUE INDEX allocation_floor_open
  ON allocation_floor(tenant_id, policy_id, reference_id) WHERE effective_to IS NULL;

-- 分摊时读「这一档有哪些保底」的主查询。
CREATE INDEX allocation_floor_policy
  ON allocation_floor(tenant_id, policy_id, effective_from DESC);
-- 反查「这个店铺被承诺过哪几档保底」（页面的承诺时间线走这条）。
CREATE INDEX allocation_floor_reference
  ON allocation_floor(tenant_id, source_system, reference_id);

-- ===========================================================================
-- 2. allocation_priority_resolution · 保底生效记录（事实，append-only）
-- ===========================================================================
-- 这一张表同时回答蓝图输出里的两句话：
--   「保底生效记录」 → requested / granted / shortfall / status / shortfall_reason
--   「被压低店铺的提示」→ base_allocated_qty 与 allocated_qty 的差 + suppressed
CREATE TABLE allocation_priority_resolution (
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL,
  -- 逐店一行。同一店铺在一次分摊里出现两行会让「它一共被压低了多少」需要额外求和，
  -- 而那个和并不存在于表里。
  reference_id uuid NOT NULL,

  source_system varchar(32) NOT NULL,
  reference_label varchar(150) NOT NULL DEFAULT '',

  -- 仲裁次序（1 = 最先保住）。它是**结果的一部分**而不是配置：
  -- 事后能直接看出「当时是谁先保住的货、谁排在后面没保住」。
  -- **每个成员都有名次，包括没配保底的那几家** —— 对它们来说这一列回答的是
  -- 「它本该排第几却没货保」，而这正是复盘被压低时最想知道的一句话。
  -- 若允许没有保底的店留 0，「排在最后」与「压根不在这次裁决里」就长得一模一样了。
  floor_order integer NOT NULL CHECK(floor_order > 0),

  -- 当时的保底承诺快照。档位一旦被收口，这次分摊就再也解释不清了。
  floor_mode varchar(8) NOT NULL CHECK(floor_mode IN ('RATIO','QUANTITY')),
  floor_ratio numeric(9,6) NOT NULL DEFAULT 0,
  floor_quantity numeric(19,6) NOT NULL DEFAULT 0,
  safety_quantity numeric(19,6) NOT NULL DEFAULT 0,

  -- 应得 = 轮到它时保底池还剩多少；实得 = 实际兑现。
  -- 两者之差即 shortfall，**显式记下来而不是丢掉**：
  -- 「保底没给到」如果只表现为最终分摊额变小，界面上就和
  -- 「它本来就不够格」长得一模一样，复盘时就分不出是货不够还是没配保底。
  requested_qty numeric(19,6) NOT NULL CHECK(requested_qty >= 0 AND requested_qty = trunc(requested_qty)),
  granted_qty numeric(19,6) NOT NULL CHECK(granted_qty >= 0 AND granted_qty = trunc(granted_qty)),
  shortfall_qty numeric(19,6) NOT NULL CHECK(shortfall_qty >= 0 AND shortfall_qty = trunc(shortfall_qty)),
  CHECK(granted_qty + shortfall_qty = requested_qty),

  status varchar(12) NOT NULL CHECK(status IN ('FULFILLED','PARTIAL','UNFULFILLED')),
  -- 差额的来源。给 null 只允许一种情况：全额兑现。
  -- 「没给到却说不出为什么」是这条记录最没用的形态，所以用 CHECK 钉住。
  shortfall_reason varchar(20) CHECK(shortfall_reason IS NULL OR shortfall_reason IN (
    'POOL_EXHAUSTED','MEMBER_DISABLED','NO_STOCK','NOT_A_MEMBER')),
  CHECK((shortfall_qty = 0 AND shortfall_reason IS NULL)
     OR (shortfall_qty > 0  AND shortfall_reason IS NOT NULL)),

  -- 保底之外从余量池另外拿到的量。保底已被足额满足的店铺不再参与余量分摊，
  -- 所以这个列会恰好是 0 —— 那一列的存在让「它没再分到」有据可查而不是靠推断。
  residual_qty numeric(19,6) NOT NULL DEFAULT 0
    CHECK(residual_qty >= 0 AND residual_qty = trunc(residual_qty)),

  -- 同一批货在「没有保底」时它本该拿到多少（7.8.1.1 的算式直接跑一遍得到的基线）。
  -- 保底就是拿这个基线和最终值作差，所以这一列是「被压低提示」的唯一依据：
  -- 没有它，界面上只能显示一个绝对分摊额，而看不出这个数是被谁挤下去的。
  base_allocated_qty numeric(19,6) NOT NULL
    CHECK(base_allocated_qty >= 0 AND base_allocated_qty = trunc(base_allocated_qty)),
  allocated_qty numeric(19,6) NOT NULL
    CHECK(allocated_qty >= 0 AND allocated_qty = trunc(allocated_qty)),
  -- 保底足额 + 余量零 = 最终分摊额。DB 层核对，别信应用层写上来的那个数。
  CHECK(allocated_qty = granted_qty + residual_qty),

  -- 被压低：同一批货，它拿到的比「没有保底时」少。
  suppressed boolean NOT NULL DEFAULT false,
  suppression_reason varchar(20) CHECK(suppression_reason IS NULL OR suppression_reason IN (
    'RESERVED_FOR_FLOOR','POOL_EXHAUSTED','MEMBER_DISABLED','NO_SALES')),
  -- 没有被压低就不许写原因，反之亦然 —— 与 shortfall_reason 同一道防线。
  CHECK((suppressed AND suppression_reason IS NOT NULL)
     OR (NOT suppressed AND suppression_reason IS NULL)),
  -- 声称「被压低」却比基线还多，是自相矛盾的记录，直接拒。
  CHECK(NOT suppressed OR allocated_qty < base_allocated_qty),

  created_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id,run_id,reference_id),
  FOREIGN KEY(tenant_id,run_id) REFERENCES allocation_run(tenant_id,id) ON DELETE CASCADE
);

-- 「哪几次分摊因为保底压低了店铺」的复盘查询。
CREATE INDEX allocation_priority_resolution_run
  ON allocation_priority_resolution(tenant_id, run_id, floor_order);
CREATE INDEX allocation_priority_resolution_suppressed
  ON allocation_priority_resolution(tenant_id, reference_id, created_at DESC) WHERE suppressed;
-- 保底没兑现的店铺清单（7.8.2.2 饥饿预警的上游之一）。
CREATE INDEX allocation_priority_resolution_shortfall
  ON allocation_priority_resolution(tenant_id, status, created_at DESC) WHERE shortfall_qty > 0;

-- ===========================================================================
-- 3. DB 级行为约束
-- ===========================================================================

-- 3.1 保底内容写定后不可改，只允许把 effective_to 从空收到一个值。
-- 与 V56 / V68 同一道防线、同一句理由：能悄悄改掉一条正在生效的保底承诺，
-- 等于把「这家店一定拿得到 50 件」这个承诺本身改掉了。
CREATE FUNCTION guard_allocation_floor_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '保底内容写定后不可修改，effective_to 只能从空收到一个时刻（换版或作废）：'
     '改保底请开新的一档，否则历史那一档的承诺会被悄悄改掉，而它可能正是当时'
     '让某家店没有断货的那道闸门'
     USING ERRCODE='23514';
 END IF;
 IF OLD.effective_to IS NOT NULL THEN
   RAISE EXCEPTION '这一档保底的生效区间已经被收口（effective_to=%），不允许再次修改或复活', OLD.effective_to
     USING ERRCODE='23514';
 END IF;
 IF NEW.effective_to <= OLD.effective_from THEN
   RAISE EXCEPTION '收口时刻必须晚于该档的生效起点 %，否则这一档从未真正生效过', OLD.effective_from
     USING ERRCODE='23514';
 END IF;
 IF (NEW.policy_id,NEW.source_system,NEW.reference_id,NEW.reference_label,
     NEW.floor_mode,NEW.floor_ratio,NEW.floor_quantity,NEW.safety_quantity,
     NEW.effective_from,NEW.note,NEW.created_by_membership_id)
    IS DISTINCT FROM
    (OLD.policy_id,OLD.source_system,OLD.reference_id,OLD.reference_label,
     OLD.floor_mode,OLD.floor_ratio,OLD.floor_quantity,OLD.safety_quantity,
     OLD.effective_from,OLD.note,OLD.created_by_membership_id) THEN
   RAISE EXCEPTION '保底内容（策略/店铺/口径/数量/安全垫）写定后不可修改，请开新的一档'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER allocation_floor_immutable
  BEFORE UPDATE ON allocation_floor
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_floor_immutable();

-- 3.2 同一 (策略, 店铺) 的生效区间不得重叠。
-- 与 V68 的 allocation_policy_interval 同一句理由：两个重叠的保底承诺
-- 在解析时没有唯一答案，而它平时完全看不出来，直到某天货真的不够分时才爆发。
CREATE FUNCTION guard_allocation_floor_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE other_id uuid; other_from timestamptz; other_to timestamptz;
BEGIN
 SELECT f.id, f.effective_from, f.effective_to INTO other_id, other_from, other_to
   FROM allocation_floor f
  WHERE f.tenant_id = NEW.tenant_id
    AND f.policy_id = NEW.policy_id
    AND f.reference_id = NEW.reference_id
    AND f.id <> NEW.id
    AND tstzrange(f.effective_from, f.effective_to, '[)')
      && tstzrange(NEW.effective_from, NEW.effective_to, '[)')
  ORDER BY f.effective_from
  LIMIT 1;
 IF other_id IS NOT NULL THEN
   RAISE EXCEPTION '该店铺在本策略下的保底在 % ~ % 区间与另一档重叠（id=%）：'
     '同一店铺同一时刻只能有一个保底承诺，否则「它该保多少」没有唯一答案',
     NEW.effective_from, coalesce(NEW.effective_to::text,'至今'), other_id
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER allocation_floor_interval
  BEFORE INSERT OR UPDATE ON allocation_floor
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_floor_interval();

-- 3.3 保底生效记录是事实：不可改、不可删。
-- 它回答的是「那次分摊为什么它没保住」，超卖与断货复盘恰恰要问它。
CREATE FUNCTION guard_allocation_resolution_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'allocation_priority_resolution 是 append-only：不允许 %，'
   '重算请开一次新的分摊（分摊 run=%，店铺=%）', TG_OP,
   coalesce(OLD.run_id::text,'?'), coalesce(OLD.reference_id::text,'?')
   USING ERRCODE='23514';
END $$;
CREATE TRIGGER allocation_priority_resolution_append_only
  BEFORE UPDATE OR DELETE ON allocation_priority_resolution
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_resolution_append_only();

-- 3.4 保底必须挂在一条真实存在且未作废的策略上。
-- 与 V68 的 guard_allocation_member_policy_open 同一句理由：
-- 换档时（新档开、旧档收口）保底要跟着复制，顺序不能反。
CREATE FUNCTION guard_allocation_floor_policy_open() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE p_to timestamptz;
BEGIN
 SELECT p.effective_to INTO p_to FROM allocation_policy p
  WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.policy_id;
 IF p_to IS NULL THEN RETURN NEW; END IF;
 RAISE EXCEPTION '策略 % 的生效区间已被收口（生效至 %），不能再往它上面挂保底',
   NEW.policy_id, p_to USING ERRCODE='23514';
END $$;
CREATE TRIGGER allocation_floor_policy_open
  BEFORE INSERT ON allocation_floor
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_floor_policy_open();

-- 3.5 保底必须挂在**同一个策略**的成员上。
-- 给一个不在名册里的店铺配保底看起来无害（它多半分不到货），
-- 但货一多它就会以「保底」的名义凭空抢走别人的货 —— 而名册里根本没有它，
-- 界面上查不到、复盘时也找不到是谁把货拿走的。这条 CHECK 把那种配置挡在写入那一刻。
CREATE FUNCTION guard_allocation_floor_member() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE member_enabled boolean;
BEGIN
 SELECT m.enabled INTO member_enabled FROM allocation_policy_member m
  WHERE m.tenant_id = NEW.tenant_id AND m.policy_id = NEW.policy_id
    AND m.reference_id = NEW.reference_id;
 IF member_enabled IS NULL THEN
   RAISE EXCEPTION '店铺 % 不是策略 % 的成员，不能给它配保底：'
     '不在名册里的店铺配了保底，货一多就会以保底的名义凭空抢走别人的货，'
     '而界面上查不到是谁拿走的', NEW.reference_id, NEW.policy_id
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER allocation_floor_member
  BEFORE INSERT ON allocation_floor
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_floor_member();

-- 3.6 守恒：Σ 保底发放额 ≤ 该次分摊的可分配量。
-- 这是本模块自己的那一条超卖防线。V68 的延迟触发器管的是
-- 「Σ分摊行 == 头」，管不到「保底发放」这一层 —— 保底是**在**分摊之前先发的，
-- 它的总量如果超过可分配量，被挤掉的余量就会变成负数。
-- 延迟触发器：一次分摊要先写头再写若干保底行，即时触发会在事务中途对着半成品报错。
CREATE FUNCTION guard_allocation_resolution_pool() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE t uuid; r uuid; head record; summed numeric(19,6);
BEGIN
 IF TG_OP='DELETE' THEN
   t := OLD.tenant_id;
   r := OLD.run_id;
 ELSE
   t := NEW.tenant_id;
   r := NEW.run_id;
 END IF;
 SELECT h.allocatable_quantity INTO head FROM allocation_run h
  WHERE h.tenant_id = t AND h.id = r;
 IF head IS NULL THEN RETURN NULL; END IF;
 SELECT coalesce(sum(g.granted_qty), 0) INTO summed
   FROM allocation_priority_resolution g
  WHERE g.tenant_id = t AND g.run_id = r;
 IF summed > head.allocatable_quantity THEN
   RAISE EXCEPTION '本次分摊的保底发放合计 % 超过可分配量 %：'
     '保底是在分摊之前先发的，发放总量超过可分配量时余量池会变成负数，'
     '而被挤掉的店铺会分到负数件 —— 那是超卖（分摊 run=%）', summed, head.allocatable_quantity, r
     USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER allocation_resolution_pool
  AFTER INSERT OR UPDATE OR DELETE ON allocation_priority_resolution
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_allocation_resolution_pool();

-- ===========================================================================
-- 4. 多租户：RLS + 授权（与 V1/V68 同一套写法）
-- ===========================================================================
-- 两张表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['allocation_floor','allocation_priority_resolution'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 既有迁移的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE
  ON allocation_floor, allocation_priority_resolution TO warehouse_app;

-- 保底档不删除：作废走 retire（关区间），档位行永久保留 ——
-- 它是「当时承诺过什么」的唯一凭据。权限层先收一道，§3.1 再收一道。
REVOKE DELETE ON allocation_floor FROM warehouse_app;
-- 保底生效记录是事实：权限层先收一道 DELETE，§3.3 的触发器再收一道 UPDATE。
REVOKE DELETE ON allocation_priority_resolution FROM warehouse_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V68 同口径）。
REVOKE ALL ON allocation_floor, allocation_priority_resolution FROM starcon_app;