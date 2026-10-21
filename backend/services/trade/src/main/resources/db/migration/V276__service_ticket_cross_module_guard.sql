-- ===========================================================================
-- V276 · 跨模块引用守卫（修复 V270 / V269 引入的跨车道破坏）
-- ===========================================================================
--
-- 一、这个迁移修的是什么
-- ---------------------------------------------------------------------------
--
-- 11.4.2.1 工单管理（V270）与 11.4.1.2 证据与申诉（V269）各自给**别的模块的表**
-- 加了外键：
--
--   V270:375  aftersales_service_ticket.escalated_case_id
--               → aftersales_case(id)          —— 11.4.2.1 → 11.1.1.1（跨模块）
--   V270:381  aftersales_service_ticket.escalated_dispute_id
--               → platform_dispute(id)         —— 11.4.2.1 → 11.4.1.1（跨模块）
--   V269:136  platform_dispute_appeal.dispute_id
--               → platform_dispute(id) ON DELETE CASCADE —— 11.4.1.2 → 11.4.1.1（跨模块）
--
-- ⚠️ 这三条外键把「某个模块的写入约束」伸到了**别的模块的表**上，
--   后果不是「多了一条约束」，而是**别的模块的测试直接炸**：
--   PostgreSQL 规定 —— 只要有**任何**子表带外键指向某张表，
--   `TRUNCATE` 那张表就会被拒绝（要连带 CASCADE 或把子表列进同一批）。
--   而 11.4.1.1 / 11.4.1.2 的四个集成测试类，
--   都在 `@BeforeEach` 里用 `truncate ... platform_dispute` 做测试隔离：
--
--     PlatformDisputeIntegrationTest        （11.4.1.1）truncate
--       platform_dispute_event, platform_dispute_settlement,
--       platform_dispute_evidence_item, platform_dispute
--     PlatformDisputeTenancyIntegrationTest （11.4.1.1）同上
--     PlatformDisputeAppealIntegrationTest   （11.4.1.2）truncate
--       …_appeal_event, …_appeal_submission, …_appeal_material, …_appeal,
--       platform_dispute_event, platform_dispute_settlement,
--       platform_dispute_evidence_item, platform_dispute
--     PlatformDisputeAppealTenancyIntegrationTest（11.4.1.2）同上
--
--   ⇒ 11.4.2.1 一交付，11.4.1.1 的两个测试类全红：
--       ERROR: cannot truncate a table referenced in a foreign key constraint
--       详细：Table "aftersales_service_ticket" references "platform_dispute".
--   ⇒ 11.4.1.2 一交付，11.4.1.1 的两个测试类同样全红：
--       详细：Table "platform_dispute_appeal" references "platform_dispute".
--
--   也就是说：**同一个坑被两个车道各埋了一遍**，
--   而受害的都是**更早批次已交付**的 11.4.1.1。
--   ⇒ 本迁移一次把**三条跨模块外键**都换成守卫触发器。
--
-- ---------------------------------------------------------------------------
-- 二、逐条定性：哪条留、哪条换（grep 取证，全库无遗漏）
-- ---------------------------------------------------------------------------
--
--   出处        外键                                              性质                        处置
--   ----------  -----------------------------------------------  --------------------------  ----------
--   V265:247    platform_dispute_evidence_item → platform_dispute  **自己模块**（11.4.1.1）  保留，不动
--   V265:299    platform_dispute_settlement     → platform_dispute  **自己模块**（11.4.1.1）  保留，不动
--   V269:232    platform_dispute_appeal_material → platform_dispute_appeal  自己模块      保留，不动
--   V269:293    platform_dispute_appeal_submission → platform_dispute_appeal 自己模块    保留，不动
--   V269:136    platform_dispute_appeal         → platform_dispute  **跨模块**（11.4.1.2）  ⇦ 本迁移换掉
--   V270:375    aftersales_service_ticket        → aftersales_case  **跨模块**（11.4.2.1）  ⇦ 本迁移换掉
--   V270:381    aftersales_service_ticket        → platform_dispute **跨模块**（11.4.2.1）  ⇦ 本迁移换掉
--
-- ⚠️ 上面五条「保留」的是**自己模块内的父子表**外键（配自己的 ON DELETE CASCADE），
--   是本仓一贯的正确写法，**一条都不许动** ——
--   它们不挡 `truncate platform_dispute`（对应子表都在 11.4.1.1 的 truncate 名单里）。
--
-- ---------------------------------------------------------------------------
-- 三、为什么不改那四个测试类的 truncate（否决方案留档）
-- ---------------------------------------------------------------------------
--
-- 最省事的做法是给四个测试类的 truncate 加 `cascade` 或补上缺漏的子表。
-- **本迁移明确否决这条路**：
--
--   ① **把锅甩给别的批次**：受影响的两个测试类属于 11.4.1.2、另两个属于 11.4.1.1，
--      它们交付时这些外键还不存在 —— 「它们本来是绿的」是事实，
--      让它们为了迁就后来的模块改断言，是**后来者改前人的账**。
--   ② **留下复发陷阱**：只要允许「用 truncate 绕开约束」，
--      下一条指向 `platform_dispute` 的外键会**再炸一次**，
--      而这次炸的仍然是别人的测试 —— 同一个坑埋第三遍。
--   ③ **保护意图会被悄悄丢掉**：外键挡的不只是「truncate」，
--      更是「升级了但对方根本不存在」这张查无实据的工单（V270 第 368–370 行的原话）。
--
-- ---------------------------------------------------------------------------
-- 四、为什么换成「同租户存在性守卫触发器」
-- ---------------------------------------------------------------------------
--
-- **本仓对「跨模块引用」的纪律就是「不建外键」**（grep 取证）：
--   * V269（11.4.1.2）第 243 行：引用 11.4.1.1 的举证清单时写明
--     「**跨模块，不建外键**：清单是上游的表，它的生命周期不由本模块决定；
--       服务层与守卫② 校验它确实属于同一件纠纷」。
--     —— 11.4.1.2 对 `evidence_item_id` 守了这条纪律，
--        却在**自己的 `dispute_id` 上破了自己的例**（第 134–136 行还写着「同模块 FK」，
--        而 11.4.1.1 与 11.4.1.2 是**两个模块**，不是一个模块）。
--   * V266（供应商评级）全服务只有 1 条外键，指向**自己模块**的表。
--   * V268（量产与建档）对 catalog 的引用是「**零跨库外键**」。
--
-- ⇒ 这三条外键**违反了这个纪律**：它们让后来的模块去约束上游模块的表生命周期。
--
-- **触发器正好是纪律要求的形状**：
--   * 触发器**不阻止 TRUNCATE** ⇒ 那四个测试类一行都不用改就能跑通；
--   * 触发器**照样拦住「引用了但对方根本不存在」** ⇒ 保护意图**一字不丢**；
--   * 触发器挂在 **BEFORE INSERT OR UPDATE** ⇒ 绕过服务层直连 SQL 也一样被拦。
--
-- ⚠️ 触发器函数是 **VOLATILE**（触发器函数不能是 IMMUTABLE/STABLE：
--   它要读表，「读到哪一行」取决于当时库里的内容，不是入参能决定的）。
--
-- ---------------------------------------------------------------------------
-- 五、守卫比原来的裸 id 外键**更严**一处
-- ---------------------------------------------------------------------------
--
-- 原外键是**裸 id**（`REFERENCES platform_dispute(id)`，不含 tenant_id）
-- ⇒ **A 租户的工单可以引用 B 租户的纠纷**：只要那个 uuid 存在就放行。
--   这是一个实打实的跨租户洞（工单属于 A，内容却指向 B 的纠纷）。
--
-- 本守卫按 **tenant_id + id 联合判**：
--   * 对方不存在 ⇒ 拒绝；
--   * 对方存在但属于**别的租户** ⇒ **同样拒绝**（原外键放行）；
--   * 对方存在且同租户 ⇒ 放行。
--
-- （另有 RLS 兜底：`platform_dispute` / `aftersales_case` 都开了
--   `isolation` 策略（`tenant_id = current_setting('app.current_tenant')`），
--   触发器里的 SELECT 本身就看不到别的租户的行 ——
--   但守卫里**显式**再判一次 tenant_id：
--   触发器是 SECURITY INVOKER，RLS 是运行环境的事，
--   「这条引用属于本租户」这条业务纪律得由守卫自己说清楚。）
--
-- ---------------------------------------------------------------------------
-- 六、语义变化（如实记录）：本轮**唯一一处「保护被换掉」**
-- ---------------------------------------------------------------------------
--
-- V269:136 那条外键带的是 **`ON DELETE CASCADE`** ——
-- 「删掉一件纠纷 ⇒ 它的申诉单被连带删掉」。
-- 换成守卫触发器后，**删除纠纷不再由 DB 自动级联删申诉**。
--
-- 取证结论：**全仓没有任何一条 DELETE 路径会去删 `platform_dispute`**
--   （服务层与测试层 grep 均无命中；11.4.1.2 的 `withdrawAppeal` 是**状态流转**
--   `appeal_status='WITRAWN'`，不是删行；11.4.1.2 里唯一的 delete 是
--   「删除草稿态下的一项材料」`delete from platform_dispute_appeal_material`，
--   与「删纠纷」无关）
--   ⇒ 这条 CASCADE 目前是**空转的**，去掉它不改变任何现存行为。
-- ⇒ 若将来 11.4.1.1 真要支持「删纠纷」，
--   应当由**那条线的拥有者**在 11.4.1.1 自己决定「连带删还是拦住」，
--   而不是让 11.4.1.2 用一条 CASCADE 外键替它决定 ——
--   静默级联删掉别人的申诉单，正是跨模块外键最典型的破坏方式。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 撤掉 V270 的两条跨模块外键
-- ---------------------------------------------------------------------------
-- ⚠️ 这两个约束名是 V270 里**显式 ADD CONSTRAINT 写的**（不是 PostgreSQL 自动命名），
--   所以名字是确定的，可以直接写死；仍带 `IF EXISTS` 以便重复执行不炸。
ALTER TABLE aftersales_service_ticket
  DROP CONSTRAINT IF EXISTS aftersales_service_ticket_escalated_case_fk;
ALTER TABLE aftersales_service_ticket
  DROP CONSTRAINT IF EXISTS aftersales_service_ticket_escalated_dispute_fk;

-- ---------------------------------------------------------------------------
-- 2. 撤掉 V269 的那条跨模块外键（**约束名靠反查，不靠猜**）
-- ---------------------------------------------------------------------------
-- ⚠️ V269 第 136 行写的是**列级内联** `REFERENCES platform_dispute(id) ON DELETE CASCADE`，
--   约束名由 PostgreSQL 自动生成 —— 那是**实现细节**（V270 第 77–79 行自己也踩过这个坑：
--   「约束名不能硬写… 约束名对不上时 DROP CONSTRAINT 会直接报错，迁移当场炸掉」）。
--   ⇒ 这里按「子表 + 父表」反查 `pg_constraint` 拿到真名再删，
--     照抄 V270 第 80–89 行的既有写法。
--   ⇒ 查到就删，查不到就跳过（这份迁移可重复执行）。
DO $$ DECLARE cname text; BEGIN
  SELECT c.conname INTO cname
    FROM pg_constraint c
    JOIN pg_class child  ON child.oid  = c.conrelid
    JOIN pg_class parent ON parent.oid = c.confrelid
   WHERE c.contype = 'f'
     AND child.relname  = 'platform_dispute_appeal'
     AND parent.relname = 'platform_dispute';
  IF cname IS NOT NULL THEN
    EXECUTE format('ALTER TABLE platform_dispute_appeal DROP CONSTRAINT %I', cname);
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 3. 守卫①：工单的升级去向必须真实存在且属于当前租户
-- ---------------------------------------------------------------------------
-- 写法照抄本仓既有守卫（V265 第 423–478 行的 guard_platform_dispute_evidence_item）：
--   CREATE FUNCTION ... RETURNS trigger LANGUAGE plpgsql
--   + CREATE TRIGGER ... BEFORE INSERT OR UPDATE ON ... FOR EACH ROW EXECUTE FUNCTION ...
CREATE FUNCTION guard_aftersales_service_ticket_escalation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- ---- 升级去向①：新建的售后单（11.1.1.1 的表）----
  IF NEW.escalated_case_id IS NOT NULL THEN
    PERFORM 1 FROM aftersales_case
     WHERE tenant_id = NEW.tenant_id AND id = NEW.escalated_case_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION '工单升级去向的售后单不存在或不属于当前租户：escalated_case_id=%', NEW.escalated_case_id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  -- ---- 升级去向②：新建的平台纠纷（11.4.1.1 的表）----
  IF NEW.escalated_dispute_id IS NOT NULL THEN
    PERFORM 1 FROM platform_dispute
     WHERE tenant_id = NEW.tenant_id AND id = NEW.escalated_dispute_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION '工单升级去向的平台纠纷不存在或不属于当前租户：escalated_dispute_id=%', NEW.escalated_dispute_id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_aftersales_service_ticket_escalation
  BEFORE INSERT OR UPDATE ON aftersales_service_ticket
  FOR EACH ROW EXECUTE FUNCTION guard_aftersales_service_ticket_escalation();

-- ---------------------------------------------------------------------------
-- 4. 守卫②：申诉单挂的纠纷必须真实存在且属于当前租户
-- ---------------------------------------------------------------------------
-- ⚠️ V269 自己在 `platform_dispute_appeal` 上挂的触发器**全是 BEFORE UPDATE / AFTER**：
--   submit（提交）、transition（状态流转）、event（留痕）——
--   **没有一条管 INSERT 时的纠纷存在性**。
--   ⇒ 本守卫补上的正是这一段：申诉单**建单那一刻**就必须挂在一件真实存在的纠纷上。
CREATE FUNCTION guard_platform_dispute_appeal_dispute() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.dispute_id IS NOT NULL THEN
    PERFORM 1 FROM platform_dispute
     WHERE tenant_id = NEW.tenant_id AND id = NEW.dispute_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION '申诉单挂的纠纷不存在或不属于当前租户：dispute_id=%', NEW.dispute_id
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_platform_dispute_appeal_dispute
  BEFORE INSERT OR UPDATE ON platform_dispute_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_platform_dispute_appeal_dispute();

-- ---------------------------------------------------------------------------
-- 5. 早先车道（11.1.1.1 / 11.4.1.2）的既有写入不受本守卫影响
-- ---------------------------------------------------------------------------
-- ⚠️ 11.1.1.1 的「批量转工单」INSERT 只填标识列，
--   `escalated_case_id` / `escalated_dispute_id` 全是 NULL
--   ⇒ 两个 IF 都不进 ⇒ 守卫对它们**不构成破坏**。
--   与 V270 第 4.1 节那条守卫是同一条纪律：护栏不许反过来打掉上游车道的写入。
-- ⚠️ 11.4.1.2 的申诉单 INSERT 走的是服务层，
--   争议单是**先建好再挂上去**的（申诉单草稿的 dispute_id 指向真实纠纷）
--   ⇒ 守卫只会把它**已经合法**的那条引用再确认一遍。
