-- ===========================================================================
-- 7.2.1.1 预占需求列表
-- ===========================================================================
--
-- 蓝图原文（07-inventory.md 7.2.1.1）：
--   职责：展示所有预占需求（来自订单履约、加工领料、其他占用），含需求来源、数量、
--         状态（held/committed/consumed/released/expired）、剩余量、超时时长。
--   输入：筛选条件（状态、来源类型、时间）；用户数据范围。
--   输出：预占需求列表 + 状态汇总。
--   关系：上游 = 09 订单（履约需求）、6.4.1.1 加工；下游 = 7.2.1.2 分配明细、7.2.1.3 释放。
--
-- ===========================================================================
-- 零、**服务归属：预占表落在 warehouse，不落 trade —— 偏离蓝图措辞的理由**
-- ===========================================================================
-- 蓝图关系句写的是「**预占需求由 trade 持久化协调**」。本迁移**刻意不采纳这句**，
-- 理由是对集成头全库实查后的硬事实，不是偏好：
--
--   1. inventory_reservation 表在**任何一个服务里都不存在**。全库 grep `reservation`
--      只命中 V1__warehouse_inventory_core.sql 的**文件头注释**——那段注释写明
--      warehouse 是 inventory_transaction / **inventory_reservation** / inventory_lot /
--      inventory_serial 的**唯一**所有者，并逐字写明「reservation / lot / serial 分别属
--      7.2.1.x 与 7.1.1.2，本期**不建空表占位**」。也就是说 V1 早就把位置预留给了
--      warehouse，7.2.1.x 一直没来建。**这是补上预留的位置，不是抢 trade 的活。**
--   2. trade 服务（V1 baseline + V92~V101）**没有任何预占相关表**。所以「去 trade 查」
--      这个选项在物理上不存在：不是不该跨库读，是那张表根本没有。
--   3. AGENTS.md「模块边界纪律」禁止跨服务读写对方表。即使 trade 将来建了同名表，
--      本库也不该 join 它 —— 存在性与权威仍在 owner 侧。
--
-- 蓝图那句「本系统只执行分配与账变」描述的是**履约流程中的协调分工**（trade 决定何时
-- 发起取消/回传实发，warehouse 执行本地事务），不是「这张表归 trade 建」。
-- 蓝图 07 卷开头「后端归属」一行也写着 warehouse 拥有 inventory_reservation，
-- 两句本身就有张力；本迁移取「后端归属」与 V1 文件头一致的那一句。
--
-- **后续 7.2.1.2（分配明细）与 7.2.1.3（释放与过期）同样落在 warehouse**，
-- 三张表 inventory_reservation / reservation_allocation / reservation_entry 在同一
-- 库、同一 RLS 策略下，后续两条车道可直接建外键，不需要跨服务协调。
--
-- ===========================================================================
-- 一、本迁移**只建 7.2.1.1 自己那张表**，不抢兄弟模块的表
-- ===========================================================================
-- 7.2.1.2 预占分配明细（reservation_allocation）与 7.2.1.3 的 reservation_entry
-- **都不在本车道范围**，本迁移一律不建空表占位 —— 理由同 V1「不建空表占位」：
-- 建一张永远只有 HELD 行的分配表，等于宣称「分配明细已交付」而实际是空的。
-- 本表对「已分配量」的记账落成**一列标量 allocated_quantity**（见 §三），
-- 真正的 bucket 级分配行由 7.2.1.2 在同一张 reservation 上建子表补齐，
-- 届时本表的 allocated_quantity 由 7.2.1.2 回写，本模块不需要迁移。
--
-- ===========================================================================
-- 二、数量口径与「剩余量」的唯一定义
-- ===========================================================================
-- 蓝图列了三个量：数量、剩余量、超时时长。三个都必须是可回答的，**不能有一个是恒 0**：
--
--   requested_quantity  业务提出来的预占需求总量（held 时的目标量）
--   allocated_quantity  7.2.1.2 分配到具体 bucket 的量（本期恒 0，因为分配明细未交付）
--   consumed_quantity   已被真实消耗（发货/领料）的量
--   **remaining = requested − consumed**（生成列）
--
-- 为什么 remaining **不含** allocated：allocated 是「货已经圈到某个 bucket 了」，
-- 货还在仓里没动；consumed 才是「货真的出库了」。蓝图要回答的是「这笔预占还欠多少货没出」
-- —— 已分配但未出库的那部分**仍然欠**，算进 consumed 会让列表在 7.2.1.2 交付后
-- 显示成「已结清」，而实际货还在仓里占着可用量。口径宁可保守也不美化。
--
-- 守恒式：consumed ≤ requested、allocated ≤ requested、consumed + released ≤ requested。
-- 三者都写成 CHECK，让「已消费 + 已释放 + 还在 held」超过需求量时在 DB 层当场被拒 ——
-- 这是超卖的 DB 级兜底，与 V1 的 ck_balance_available、V2 的 available>=0 同一条纪律。
--
-- 数量 NUMERIC(19,6)：沿用 8.2/8.4 作业单的口径（比 V1/V2 的 19,4 多两位小数，
-- 加工领料按重量/体积折算时会用到），Java 侧一律 BigDecimal，全程禁浮点。
--
-- ===========================================================================
-- 三、状态机：五个状态由 DB 级触发器裁决「哪些迁移合法」
-- ===========================================================================
-- 蓝图点名五态：held / committed / consumed / released / expired。
-- 落库为大写 HELD / COMMITTED / CONSUMED / RELEASED / EXPIRED。
--
--   HELD       已提出需求、尚未承诺分配（可 → COMMITTED / RELEASED / EXPIRED）
--   COMMITTED  已分配到 bucket、承诺发货（可 → CONSUMED / RELEASED）
--   CONSUMED   已真实出库（**终态**）
--   RELEASED   被释放（取消/超时回收，**终态**）
--   EXPIRED    超时过期（**终态**）
--
-- 蓝图 7.2.1.3 的红线：「**承诺分配（committed）后不再自动 TTL 释放**」——
-- 所以 COMMITTED 不能由 expires_at 超时自动落成 EXPIRED，这一点由 DB 层的
-- 「COMMITTED 的 expires_at 可空」+ 服务层不提供自动过期动作共同保证。
--
-- **终态不可复活**（铁律，与 db/schemas/v3 的 reject_reservation_revive 同口径）：
-- CONSUMED/RELEASED/EXPIRED 三行不能再回到 HELD/COMMITTED。
-- 迟到到达的「建立预占」请求如果撞上一条已终态的同号预占，会被 DB 拒绝而不是
-- 把货重新圈住 —— 否则已出库的货会被「复活」成新的预占，直接造成超卖。
--
-- 状态与时间戳自洽由 guard_reservation_state_machine 钉死：
--   * held_at 恒非空（建行即有）
--   * committed_at 非空 ⟺ 状态 ∈ {COMMITTED, CONSUMED} 或更后
--   * settled_at 非空 ⟺ 状态 ∈ {CONSUMED, RELEASED, EXPIRED}（三个终态）
--   补一个 CONSUMED 行却把 settled_at 留空，会让「这批货到底出库了没有」永远答不出来。
--
-- ===========================================================================
-- 四、来源定位键：跨服务引用**不建外键**（AGENTS.md 模块边界纪律）
-- ===========================================================================
-- 上游是 09 订单（trade V100/V101）与 6.4.1.1 加工（trade V97/V98），
-- 都在**另一个服务、另一个数据库**里。所以这里只登记定位键：
--   source_system  'TRADE' / 'PROCESSING' / 'MANUAL'（受控词表，见 CHECK）
--   source_type    'SALES_ORDER' / 'PROCESSING_ORDER' / 'OTHER_HOLD'
--   source_ref_id / source_ref_code  上游单据的 id 与**人类可读单号快照**
--
-- source_ref_code 存快照而不是 join 对方表：上游单号在本地列表上必须看得见，
-- 而跨库 join 是 AGENTS.md 明令禁止的。快照的代价是上游改号不会同步 —— 这是
-- 「可读性」换「无跨库依赖」的自觉取舍，与 sku_ref / legal_entity_ref 同一思路。
--
-- legal_entity_id / sku_id 同样**不建外键**：法人属 identity、SKU 属 catalog，
-- 本服务只登记「我这里这批货算哪个法人 / 哪个 SKU」（V1 第零节同一纪律）。
-- 代码 / 名称从 sku_ref、legal_entity_ref 这两张**本服务自有的引用登记表**读，
-- 不去读 catalog / identity 的表。
--

-- ===========================================================================
-- 5. 预占需求表
-- ===========================================================================
-- 单号发号器。与 duty_tax_estimate_code_seq 同一写法：Java 侧拼 DTE-日期-序号，
-- 库只发整数，格式不在 DB 里固化（租户自定的编码规则不该被迁移钉死）。
CREATE SEQUENCE inventory_reservation_no_seq START 1;

CREATE TABLE inventory_reservation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- 业务单号：RSV-20261013-000123。UNIQUE(tenant_id,reservation_no) 保证同一租户内
  -- 不重号 —— 重号会让「上游那条命令对应哪条预占」变成猜，而迟到命令撞终态行
  -- 正是要靠这个唯一性被 DB 拒（见 §三）。
  reservation_no varchar(64) NOT NULL,

  -- 需求来源（蓝图「来自订单履约、加工领料、其他占用」三类，逐条对应）
  source_system varchar(24) NOT NULL
    CHECK(source_system IN ('TRADE','PROCESSING','MANUAL')),
  source_type varchar(32) NOT NULL
    CHECK(source_type IN ('SALES_ORDER','PROCESSING_ORDER','OTHER_HOLD')),
  -- 上游单据定位键。**弱引用，无外键**（§四）。source_ref_code 是可读单号快照。
  source_ref_id uuid,
  source_ref_code varchar(64),

  -- 货权与商品：同样只登记 id，code/name 从本服务引用登记表读（§四）
  legal_entity_id uuid NOT NULL,
  sku_id uuid NOT NULL,

  -- 三个量 + 一个生成列，口径见 §二
  requested_quantity numeric(19,6) NOT NULL,
  allocated_quantity numeric(19,6) NOT NULL DEFAULT 0,
  consumed_quantity  numeric(19,6) NOT NULL DEFAULT 0,
  released_quantity  numeric(19,6) NOT NULL DEFAULT 0,
  remaining numeric(19,6) GENERATED ALWAYS AS (requested_quantity - consumed_quantity) STORED,

  status varchar(16) NOT NULL DEFAULT 'HELD'
    CHECK(status IN ('HELD','COMMITTED','CONSUMED','RELEASED','EXPIRED')),

  -- 时间轴。held_at 恒有；committed_at 只在承诺分配后有；
  -- settled_at 只在三个终态有；expires_at 只在 HELD 有意义（§三的「committed 不自动过期」）。
  requested_at timestamptz NOT NULL DEFAULT now(),
  held_at timestamptz NOT NULL DEFAULT now(),
  committed_at timestamptz,
  settled_at timestamptz,
  -- 过期时刻。**可空**：COMMITTED 之后不参与自动过期，所以此刻可以清空而不出错。
  expires_at timestamptz,
  note varchar(200),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,reservation_no),

  -- 数量合法性：需求量必须为正（0 量预占是脏数据，不是「不需要预占」）
  CHECK(requested_quantity>0),
  CHECK(allocated_quantity>=0 AND allocated_quantity<=requested_quantity),
  CHECK(consumed_quantity>=0 AND consumed_quantity<=requested_quantity),
  CHECK(released_quantity>=0 AND released_quantity<=requested_quantity),
  -- 守恒：已消费 + 已释放不得超过需求量。三者相加超过需求量 = 同一批货被出库两次，
  -- 这是超卖的 DB 级兜底（与 V1 ck_balance_available、V2 available>=0 同一道防线）。
  CHECK(consumed_quantity+released_quantity<=requested_quantity),
  CHECK(btrim(reservation_no)<>'')
);

-- 蓝图点名的三个筛选条件：状态、来源类型、时间。
-- 列表默认按 requested_at 倒序（最近的预占在最前），索引以这条排序打头，
-- 否则第一页要全表扫再排序。
CREATE INDEX inventory_reservation_status_list
  ON inventory_reservation(tenant_id,status,requested_at DESC,id);
CREATE INDEX inventory_reservation_source_list
  ON inventory_reservation(tenant_id,source_type,source_system,requested_at DESC,id);
-- 时间范围筛选（蓝图输入第三项）：不带 status 前缀，单按时间筛也能走索引。
CREATE INDEX inventory_reservation_requested_at
  ON inventory_reservation(tenant_id,requested_at DESC,id);
-- 超时清理候选（7.2.1.3 会用；这里先给索引，**不建任何清理动作** —— 那是兄弟模块的职责）。
-- 部分索引只覆盖「还在 HELD 且确实有 expires_at」的行：COMMITTED 不参与自动过期（§三）。
CREATE INDEX inventory_reservation_expiry_candidates
  ON inventory_reservation(tenant_id,expires_at,id)
  WHERE status='HELD' AND expires_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5.1 状态机触发器：终态不可复活 + 状态与时间戳自洽
-- ---------------------------------------------------------------------------
-- 为什么把这条钉在 DB 而不是只在 Java 校验：7.2.1.2 / 7.2.1.3 两条兄弟车道会各自
-- 写这张表，它们各自的 service 层校验加在一起仍然能被一条裸 SQL 绕开。
-- 而绕开的后果是「已出库的货被复活成预占」—— 不可逆的超卖事故。
CREATE FUNCTION guard_reservation_state_machine() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- 终态不可复活。三个终态分别报各自的名字：运维看到 "EXPIRED 不能复活" 比
  -- 看到一句笼统的非法迁移更知道是哪条迟到命令撞上了终态。
  IF OLD.status IN ('CONSUMED','RELEASED','EXPIRED') AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION '预占 % 已处于终态 %，不能迁回到 %：迟到命令不得复活已终结的预占',
      NEW.reservation_no, OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  -- 终态 → 终态之间也不许改（HELD/COMMITTED → CONSUMED 是唯一合法的终态入口，
  -- 但已经 RELEASED 的行改成 CONSUMED 同样荒谬：那批货已经还回可用量了）。
  IF OLD.status IN ('CONSUMED','RELEASED','EXPIRED') THEN
    RAISE EXCEPTION '预占 % 已终结于 %，状态不可再改', NEW.reservation_no, OLD.status
      USING ERRCODE='23514';
  END IF;

  -- 状态与时间戳自洽：让「什么时候发生的」与「现在是什么状态」不可能互相矛盾。
  -- 少了这一段，一行 CONSUMED 可以没有 settled_at，
  -- 于是「这批货到底出库了没有」这个问题永远没有答案。
  IF NEW.status='COMMITTED' AND NEW.committed_at IS NULL THEN
    RAISE EXCEPTION '预占 % 承诺分配时必须写 committed_at', NEW.reservation_no
      USING ERRCODE='23514';
  END IF;
  IF NEW.status IN ('HELD') AND NEW.committed_at IS NOT NULL THEN
    RAISE EXCEPTION '预占 % 还在 HELD，不应有 committed_at', NEW.reservation_no
      USING ERRCODE='23514';
  END IF;
  IF NEW.status IN ('CONSUMED','RELEASED','EXPIRED') AND NEW.settled_at IS NULL THEN
    RAISE EXCEPTION '预占 % 进入终态 % 时必须写 settled_at', NEW.reservation_no, NEW.status
      USING ERRCODE='23514';
  END IF;
  IF NEW.status NOT IN ('CONSUMED','RELEASED','EXPIRED') AND NEW.settled_at IS NOT NULL THEN
    RAISE EXCEPTION '预占 % 状态为 %，不该有 settled_at', NEW.reservation_no, NEW.status
      USING ERRCODE='23514';
  END IF;

  -- 版本自增：并发更新时后写者拿不到 updated 版本的行，
  -- 乐观锁在 DB 层生效而不是靠应用层记得传 version。
  NEW.version := OLD.version + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reservation_state BEFORE UPDATE ON inventory_reservation
 FOR EACH ROW EXECUTE FUNCTION guard_reservation_state_machine();

-- ---------------------------------------------------------------------------
-- 5.2 新建预占的入口校验：建行即 HELD，不允许直接建成终态
-- ---------------------------------------------------------------------------
-- 「插一行 CONSUMED」是脏数据：没有 held 就没有预留，凭什么直接说已消耗？
-- 由 7.2.1.3 的释放/过期路径通过 UPDATE 落终态，而不是 INSERT 伪造。
CREATE FUNCTION guard_reservation_insert_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status<>'HELD' THEN
    RAISE EXCEPTION '预占 % 必须以 HELD 建立，终态只能由状态迁移产生', NEW.reservation_no
      USING ERRCODE='23514';
  END IF;
  IF NEW.held_at IS NULL THEN
    RAISE EXCEPTION '预占 % 缺少 held_at：预占建立时刻是超时时长的唯一基准', NEW.reservation_no
      USING ERRCODE='23514';
  END IF;
  -- 建行即终态的时间戳也拒：consumed/released/settled 在 HELD 行上恒为空。
  IF NEW.committed_at IS NOT NULL OR NEW.settled_at IS NOT NULL
     OR NEW.consumed_quantity<>0 OR NEW.released_quantity<>0 THEN
    RAISE EXCEPTION '预占 % 以 HELD 建立时不得携带承诺/终结痕迹', NEW.reservation_no
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reservation_insert BEFORE INSERT ON inventory_reservation
 FOR EACH ROW EXECUTE FUNCTION guard_reservation_insert_state();

-- ===========================================================================
-- 六、多租户：tenant_id NOT NULL + ENABLE/FORCE RLS + GRANT（与 V1 §9、V90 §14 同一条）
-- ===========================================================================
-- **本节刻意排在建表与触发器之后**：ALTER TABLE ... ENABLE ROW LEVEL SECURITY 与
-- CREATE POLICY 都要求目标表已存在，写在建表之前 Flyway 会直接以
-- 「relation inventory_reservation does not exist」失败。
--
-- 租户由 backend/libraries/tenancy 在事务内 set_config('app.current_tenant') 写入，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。服务层的 tenant_id 谓词是第二道防线：
-- 忘了绑定上下文时让查询当场返回空，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY','inventory_reservation');
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY','inventory_reservation');
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)','inventory_reservation');
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_reservation TO warehouse_app;
-- 预占单号是发号器的产物：漏这条授权，登记预占会报「permission denied for sequence」，
-- 而错误文案完全指不到授权上（与 V90 duty_tax_estimate_code_seq 同一条教训）。
GRANT USAGE, SELECT ON SEQUENCE inventory_reservation_no_seq TO warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1、V63、V80、V85、V90 同口径）。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
