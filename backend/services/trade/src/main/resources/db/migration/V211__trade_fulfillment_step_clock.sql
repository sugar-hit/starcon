-- 9.3.3.1 履约请求 —— 流程步骤记录的**跨时钟域**修复。
--
-- 蓝图 09-order-and-fulfillment.md 9.3.3.1 原文（不因本次修复而改动）：
--   职责：编排一次履约：锁定预占 → 生成出库单 → 编排作业 → 跟踪完成；持久化流程状态，
--         支持断点恢复与超时重试。
--   输入：分仓与拆合单结果；库存预占指令；`expected_version`。
--   输出：履约请求 + 出库单 + 流程状态记录 + 事件（下游消费）。
--
-- 归属：**trade 服务**（9.3 履约编排 → 主责服务 trade）。本迁移是 V152
-- （`V152__trade_fulfillment_request.sql`）的**缺陷修复**，只动本模块自己的窄函数。
--
-- ===========================================================================
-- 一、缺陷：同一行的两个时间戳来自两个时钟源
-- ===========================================================================
-- V152 在 fulfillment_request_step 上留了一条表级约束：
--
--   CHECK (finished_at IS NULL OR finished_at >= started_at)
--
-- 它在防一个**真实**错误：一步「结束」早于它的「开始」。这条约束**不许**放宽、
-- 不许删 —— 放宽等于把「结束早于开始」这个真 bug 放进来。
--
-- 但旧实现里，这一对时间戳是**两个时钟各写一个**：
--
--   started_at  ← 服务层的 JVM 墙钟（OffsetDateTime.now()，FulfillmentRequestService.markStepRunning）
--   finished_at ← DB 时钟（V152 第 10.8 节的窄函数 complete_fulfillment_step 里 clock_timestamp()）
--
-- ⇒ 同一行的两个字段分属两个时钟域。JVM 比 DB 快**哪怕几十微秒**，
--   约束就违约，一次**完全合法**的「履约步完成」会被 DB 拒掉
--   （生产后果：履约流程卡在 RUNNING，不是测试脆弱性）。
--
-- 实测（2026-10-20 23:29，本机 PG 容器）：
--   started_at  = 2026-10-20 23:29:54.130301+08   ← JVM
--   finished_at = 2026-10-20 23:29:54.130226+08   ← DB，比 JVM 慢 75 微秒
-- ⇒ 约束被击穿。
--
-- ===========================================================================
-- 二、修法：整个模块的落库时间戳**都取 DB 时钟**（含服务层写的那几列）
-- ===========================================================================
-- 本迁移把 complete_fulfillment_step 换成 4 参版本：
--   * finished_at     = clock_timestamp()（与旧版同源，不变）
--   * elapsed_seconds = 由 DB 用**库里存着的那两个值**算出来
--                          round(EXTRACT(EPOCH FROM (finished_at - started_at))::numeric, 3)
--   * started_at      = 仍由写 RUNNING 那一行的 INSERT 用 clock_timestamp() 落下
--                       （服务层 FulfillmentRequestService.markStepRunning 同步改掉：
--                        不再把 JVM 墙钟塞进 started_at）
--
-- ⚠ **本模块里同一个缺陷不止 step 表一处**，一并收口（都在 9.3.3.1 自己的表上，
--   零跨模块改动）：服务层写「请求侧」时刻（JVM 墙钟），窄函数写「转移侧」时刻（DB 时钟），
--   而两者被同一张表的 CHECK 拿来比大小 ——
--     fulfillment_request_step        CHECK (finished_at >= started_at)          ← 报告的那一处
--     fulfillment_request_reservation CHECK (acknowledged_at >= requested_at)
--                                   CHECK (released_at    >= requested_at)
--     fulfillment_request_outbound_order CHECK (acknowledged_at >= requested_at)
--     fulfillment_request             CHECK (completed_at >= requested_at)
--                                   CHECK (cancelled_at >= requested_at)
--                                   CHECK (blocked_at   >= requested_at)
--   ⇒ 只要服务层还往 requested_at / started_at 里塞 JVM 墙钟，这些 CHECK 就随时会被
--     「JVM 比 DB 快几十微秒」击穿，**一次合法的履约步完成 / 预占回执 / 取消 / 挂起
--     会被 DB 随机拒掉**，流程卡在 RUNNING / RESERVING。
--   ⇒ 收口办法只有一个：**服务层也不再从 JVM 落任何时间戳**，
--     requested_at / deadline_at / last_step_at / step.started_at / 出库单与预占指令的
--     requested_at / 事件的 occurred_at 全部改由 DB 时钟（clock_timestamp()）生成。
--     这些改动在 Java 侧（无 DDL）；本迁移只负责**窄函数**那一半。
--     改完之后模块里再没有「同一行的两个时间戳来自两个时钟源」这回事。
--
-- 三件事一起成立：
--   1. 同一行的两个时间戳来自**同一个时钟源**（DB 时钟）⇒ CHECK 不再可能被两个
--      时钟之间的偏移击穿。JVM 与 DB 快慢多少都无所谓。
--   2. elapsed_seconds 与这两个时间戳**构造上就自洽**：它是 DB 用同一对值算出来的，
--      不存在「服务层算的耗时」与「库里写的时间戳」对不上的可能
--      （旧实现正是在时钟倒挂时把它算成**负数**，然后被函数里那条
--      「耗时必须为非负数」挡下，整个履约步完成失败）。
--   3. CHECK **原样保留**，继续防「结束早于开始」这个真错误：手工把 started_at
--      改成一个未来时刻的行，完成时仍会被拒（见 FulfillmentRequestIntegrationTest
--      的「结束早于开始的行仍然被拒_约束没有被放宽」）。
--
-- 为什么选「都取 DB 时钟」而不是「都取 JVM 时钟」：
--   * 这一步的「开始」与「完成」是**两次独立的 HTTP 请求**（发起预占 → 收到
--     warehouse 回执），在多副本部署里可能落在**不同的实例**上。「都取 JVM 时钟」
--     只是把「JVM vs DB」的比较换成「主机 A vs 主机 B」的比较 —— 失败率会变小，
--     但缺陷类别没消失（NTP 同步在毫秒级，主机间差几毫秒就足以让完成早于开始）。
--     DB 时钟是所有实例**共享**的那一个时钟，取它才是结构性修好。
--   * 同模块的 fulfillment_request_work_order 就是这么写的：它的 started_at 与
--     finished_at **都由 DB 窄函数 update_fulfillment_work_order 取 clock_timestamp()**，
--     并且同样挂着一条 `finished_at >= started_at` 的 CHECK。step 表是唯一的例外。
--   * 本模块的「状态转移时刻」本来就都是 DB 时钟：last_step_at / completed_at /
--     blocked_at / acknowledged_at 全部写在 V152 第 10 节的窄函数里。
--
-- 被否决的另外两种改法：
--   * 「放宽 / 删掉那条 CHECK」：任务书明令禁止，且它防的是真错误 —— 否决。
--   * 「finished_at = greatest(clock_timestamp(), started_at)」（把完成时刻夹到
--     开始时刻之后）：这等于把约束变成永真 —— 一个被写坏的 started_at 会被悄悄
--     抹平，CHECK 再也拦不住任何东西。约束必须继续有牙齿 —— 否决。
--   * 「started_at 用 now() 而不是 clock_timestamp()」：now() 是**事务开始时刻**，
--     长事务里写出来的 started_at 会早于这一步真正开始的时刻，耗时随之算错。
--     V152 文件头第三节已经为此踩过坑（三个推进函数的 deadline 一律用
--     clock_timestamp()）—— 否决。
--
-- ===========================================================================
-- 三、影响面与边界
-- ===========================================================================
--   * 零 ALTER 任何既有表（含 V92 / V116 / V144 / V152 的表与 V1 的
--     trade_tenancy_probe）—— 约束一条没动，索引一条没动，RLS 一条没动。
--     被本迁移碰到的既有对象**只有本模块自己的那一个窄函数**。
--   * 零新建表 ⇒ 不涉及 RLS / GRANT 的建表流程。
--   * 跨车道 DDL 回归面 = 0（别的 trade 模块的表、函数、端点一条没碰）。
--   * 对外契约（OpenAPI）零变化：step 的 DTO 字段（started_at / finished_at /
--     elapsed_seconds）形状没变 ⇒ 不需要重新生成 packages/api-contracts。
--   * 数据零迁移：旧的 step 行都是**合法**的（约束当时就把违约的写拒了），
--     不需要修历史数据。
--
-- ===========================================================================
-- 四、窄函数
-- ===========================================================================

-- 旧签名（5 参，elapsed 由调用方算）先撤掉：它把「耗时」的责任放在客户端，
-- 正是跨时钟域的入口。DROP 之后按新签名重建并重新授权。
DROP FUNCTION IF EXISTS complete_fulfillment_step(uuid,uuid,uuid,int,numeric);

-- 10.8 把一步流程从 RUNNING 置为 COMPLETED（_step 表只授权 INSERT，所以必须有窄函数）
--     为什么 step 表严格 append-only 却还要「把一行改掉」：
--       append-only 保证的是「**每一次尝试**都留痕」——重试会开 attempt+1 的新行；
--       同一 attempt 内 RUNNING → COMPLETED 是**同一次尝试的完成**，不是新事实。
--       真正的不可改写性落在「不能把 COMPLETED 改成别的、不能删掉 COMPLETED」上，
--       由本函数的终态守卫 + 第 11 节收权共同保证。
--
--     ⭐ 与 V152 的差别只有一处，但那一处是全部：
--       V152：finished_at = clock_timestamp()，elapsed_seconds = 调用方传进来的值。
--       本版：finished_at = clock_timestamp()，elapsed_seconds = **本函数用
--             库里存着的 started_at 与刚取的 finished_at 算出来的值**。
--       调用方不再传时刻进来 ⇒ 服务层与 DB 不可能对「这一步什么时候开始、
--       什么时候结束」各执一词。
CREATE FUNCTION complete_fulfillment_step(
  p_step_id uuid, p_reservation_id uuid, p_outbound_order_id uuid, p_work_order_count int)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE row_tenant uuid; row_status varchar(16); row_started timestamptz;
        v_finished timestamptz; v_elapsed numeric;
BEGIN
  SELECT tenant_id, status, started_at INTO row_tenant, row_status, row_started
    FROM fulfillment_request_step WHERE id = p_step_id;
  -- SECURITY DEFINER 绕过 RLS，租户判据必须自己写（见 advance_... 里的同一段说明）。
  IF row_status IS NULL
     OR row_tenant IS DISTINCT FROM nullif(current_setting('app.current_tenant', true),'')::uuid THEN
    RAISE EXCEPTION '流程步骤记录 % 不存在或不属于本租户', p_step_id USING ERRCODE='23503';
  END IF;
  IF row_status <> 'RUNNING' THEN
    RAISE EXCEPTION '流程步骤记录 % 当前状态是「%」，只有执行中（RUNNING）才能标记完成',
    p_step_id, row_status USING ERRCODE='23514';
  END IF;
  -- ⭐ 同一行的时间戳只能来自同一个时钟源：
  --   started_at 是写 RUNNING 那一行时的 clock_timestamp()，finished_at 在这里
  --   用**同一个 DB 时钟**取。两个值都出自 DB ⇒ 表级约束
  --   CHECK (finished_at >= started_at) 不再可能被「JVM 与 DB 差几十微秒」击穿。
  v_finished := clock_timestamp();
  -- 耗时由这两个值算出来 ⇒ 与库里存的时间戳在构造上自洽（不会算出负数，
  -- 除非 DB 自己的时钟往回跳了 —— 那是真的时钟故障，理应被拦下）。
  v_elapsed := round(EXTRACT(EPOCH FROM (v_finished - row_started))::numeric, 3);
  IF v_elapsed < 0 THEN
    RAISE EXCEPTION '流程步骤记录 % 的开始时刻 % 晚于完成时刻 %，耗时为负',
      p_step_id, row_started, v_finished USING ERRCODE='23514';
  END IF;
  -- 置标记后 UPDATE：本函数是 SECURITY DEFINER，但触发器照样对所有 UPDATE 生效，
  -- 所以由这个函数自己开窗，且窗口只活在当前事务内。
  PERFORM set_config('app.fulfillment_step_complete', 'on', true);
  UPDATE fulfillment_request_step
    SET status = 'COMPLETED',
        reservation_id = COALESCE(p_reservation_id, reservation_id),
        outbound_order_id = COALESCE(p_outbound_order_id, outbound_order_id),
        work_order_count = p_work_order_count,
        finished_at = v_finished,
        elapsed_seconds = v_elapsed
    WHERE id = p_step_id AND status = 'RUNNING';
  IF NOT FOUND THEN
    RAISE EXCEPTION '流程步骤记录 % 状态已被改动（期望 RUNNING），标记完成失败', p_step_id
     USING ERRCODE='40001';
  END IF;
END $$;

-- 授权跟着新签名重发一遍（旧签名的授权随 DROP 一起消失）。
GRANT EXECUTE ON FUNCTION complete_fulfillment_step(uuid,uuid,uuid,int) TO trade_app;
