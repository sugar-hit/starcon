-- 9.2.1.1 待审队列。
--
-- 蓝图 09-order-and-fulfillment.md 9.2.1.1 原文：
--   职责：展示待审订单，按风险与时效排序，支持批量通过/驳回/挂起，显示每单命中的规则与风险信号。
--   输入：新订单事件；审单规则配置；风控信号。
--   输出：待审列表 + 批量审单结果。
--   关系：上游 = 9.1.2.1、9.2.1.2；下游 = 9.3 履约编排。**审单是发货前的最后人工闸门**，
--         批量通过必须基于明确的规则，而不是默认全过。
--
-- 归属：**trade 服务**（蓝图 09 卷 L2 表「9.2 审单 → 主责服务 trade」）。
-- 本迁移**只新建表与函数、零 ALTER 既有表**：
--   * sales_order 簇（V99）只读 —— 队列成员就是「status='SUBMITTED' 的订单」，
--     平台单同步（9.1.2.1，V207）落 SUBMITTED 即进队列，本迁移不复制订单；
--   * order_review_rule*（V149，9.2.1.2）只读 —— 规则、命中链、判定留痕；
--   * order_risk_*（V150，9.2.2.2）只读 —— 风险分与风险信号。
-- 与并行车道的 V208 / V210 零交集，跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 一、队列成员不另造一张表：它就是「已提交且未被审掉的订单」
-- ===========================================================================
-- 蓝图输入句点名「新订单事件」。本服务里那个事件的落点已经是现成的：
--   9.1.2.1 平台订单同步落 sales_order.status='SUBMITTED'（V207 第五节明说
--   「平台单落 SUBMITTED 即进 9.2 审单队列」）；
--   9.1.2.2 手工订单同理。
-- ⇒ 队列 = sales_order.status='SUBMITTED' 且没有终态审单结论的订单。
-- 为什么不复制一张「队列表」再从订单同步过去：
--   复制会立刻带来第二个真相 ——「订单说已提交、队列表说没进队」，
--   而审单是发货前的最后一道闸门，漏单 = 漏审 = 货照发。
--   本文件只存**审单结论**（第 2 节），不存订单的复制品。
--
-- ⚠️ 由此推出一个刻意的不作为：**不改 sales_order.status**。
--   V99 的 CHECK 只允许 ('DRAFT','SUBMITTED')，本迁移不去 ALTER 它
--   （V207 同样明说「本车道不能 ALTER 它」）。
--   「这单审没审过」是**审单侧的结论**，不是订单单据的状态 ——
--   把它塞进 sales_order.status 会让「订单单据状态」与「审单结论」两个维度
--   挤进一个字段，审单结论一变订单单据状态也跟着变，履约侧（9.3）读到的
--   就不是它以为的那个语义。审单结论落在本文件第 2 节的表上。
--
-- ===========================================================================
-- 二、三张表：批量回执 / 队列结论 / 回执逐条
-- ===========================================================================
--   order_review_batch         一次批量审单的**回执**（幂等键 + 实际条数）
--   order_review_queue_case    一张订单一份**当前**审单结论（通过/驳回/挂起）
--   order_review_batch_item    回执的**逐条**结果（哪一单、什么结论、为什么）
--
-- 加粗句「批量通过必须基于明确的规则，而不是默认全过」在本文件里有**三层**落点，
-- 一层比一层硬：
--   (1) 规则缺失 → 服务层显式拒绝（400），不给「默认全过」这条路；
--   (2) 规则不在生效期 / 动作是转人工 / 规则已改版 → 同样显式拒绝；
--   (3) **DB 约束**：回执表与结论表上都有
--       「动作=APPROVE ⇒ 必须指名规则（rule_code + rule_version）」的 CHECK。
--       绕过服务层直接写库也插不进去一条「没有依据的通过」。
--
-- 幂等：order_review_batch 上 UNIQUE(tenant_id, idempotency_key)。
--   同一批重复提交 → 第二次撞唯一索引 → 直接回放上一次的回执，**不再改任何状态**。
--   回执上写明「本批实际通过/驳回/挂起各几条」，而不是写「请求了几条」。
--
-- ===========================================================================
-- 三、「按风险与时效排序」要真排序：两个分都落成 DB 里的表达式
-- ===========================================================================
-- 排序键：
--   风险分 risk_score  ← order_risk_assessment.risk_score（V150 的真列，0..100）
--   时效分 timeliness  ← 本文件的 IMMUTABLE 函数，按「提交至今等了多久」算
-- 两者都在 **ORDER BY 里**，不是前端拿到列表再排一次 ——
--   前端排序只能对当前页生效（第 2 页会乱序），而且排序依据会被前端改写。
--
-- ⚠️ 风险分取的是**最近一次评估**（evaluated_reference_at 最新的一条）。
--   没有评估过就取不到分（risk_score 为 NULL）——
--   **刻意不兜一个 0**：0 分在风控语境里是「量过了，安全」，
--   而「压根没量过」是另一回事，兜 0 会把没评估的单排到最安全的那一档。
--   排序时 NULL 排在有分之后（ORDER BY 里的 (risk_score is null) asc）。
--
-- 时效分为什么用函数而不是物化列：
--   等候时长**每时每刻都在变**（等得越久越急）。物化成列就会立刻变陈旧，
--   而「陈旧的时效分」比「没有时效分」更坏 —— 它看起来像个真分数。
--   函数是 IMMUTABLE 的（给定 submitted_at 与 at 结果确定），
--   所以能进 WHERE 也能进 ORDER BY，Java 与前端都**不重算**。
--
-- ===========================================================================
-- 四、多租户：RLS + 授权（与 trade V149 第 9 节、V150 第 8 节同一套写法）
-- ===========================================================================
-- 三张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它（AccessService.Access 来自已认证成员关系）。
-- 本模块尤其不能省：审单结论直接决定一单发不发，
-- 漏掉租户谓词 = A 租户的审单员能驳回 B 租户的订单。

-- ---------------------------------------------------------------------------
-- 1. 批量审单回执（幂等键在这里）
-- ---------------------------------------------------------------------------
-- 为什么回执是「一张表两段写」而不是一次写完：
--   幂等键必须**先占住**再干活 —— 否则两个并发同键请求都会跑一遍规则判定
--   都写一遍结论，最后其中一个撞唯一索引，那一份的副作用已经发生了。
--   所以：先 insert 一行 completed=false 占住键 → 干活 → 回写统计数并置 completed=true。
--   统计数是**唯一**允许被改写的部分（列级授权，见第七节），
--   动作、规则、幂等键、操作人一经写入就不可改 —— 回执的身份不能改。
--
-- ⚠️ 本表**先建**（第 1 节），因为第 2 节的结论表与第 3 节的逐条表都要外键指到它。
CREATE TABLE order_review_batch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- **幂等键**：调用方给的业务键（不是请求体哈希），
  -- 同一租户内唯一。重复提交同一个键 ⇒ 回放上一次的回执，不再改任何状态。
  idempotency_key varchar(128) NOT NULL CHECK(btrim(idempotency_key)<>''),
  -- 批量动作。通过 / 驳回 / 挂起三种，**没有第四种**（没有「无脑全过」）。
  action text NOT NULL CHECK(action IN ('APPROVE','REJECT','SUSPEND')),
  -- 依据的规则：APPROVE 时必填（见下面的 CHECK）。
  rule_code varchar(64) NOT NULL DEFAULT '',
  rule_version bigint,
  -- 请求了几条（**去重之后**的条数，见服务层的重复校验）。
  requested_count int NOT NULL CHECK(requested_count >= 0),
  -- 实际条数：回执要写清「本批实际通过/驳回/挂起各几条」，
  -- 而不是「请求了几条」—— 两者不相等正是常态（有一单命中了强制转人工规则）。
  approved_count int NOT NULL DEFAULT 0 CHECK(approved_count >= 0),
  rejected_count int NOT NULL DEFAULT 0 CHECK(rejected_count >= 0),
  suspended_count int NOT NULL DEFAULT 0 CHECK(suspended_count >= 0),
  -- 跳过：不在队列里的、已经审过的、跨租户查不到的、规则判定不通过的。
  skipped_count int NOT NULL DEFAULT 0 CHECK(skipped_count >= 0),
  -- 人话理由（驳回/挂起必填；通过时是可选备注）。
  reason varchar(600) NOT NULL DEFAULT '',
  -- 处理完成标记。false = 正在处理（并发同键的第二个请求会看到它并被拒）。
  completed boolean NOT NULL DEFAULT false,
  completed_at timestamptz,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 幂等的物理落点。
  UNIQUE(tenant_id,idempotency_key),
  -- 🔴 加粗句的 DB 级落点（一）：**通过批次必须指名规则**。
  --   这一条让「没有依据的批量通过」在库里根本写不进去。
  CONSTRAINT order_review_batch_approve_needs_rule
    CHECK(action <> 'APPROVE' OR (rule_code <> '' AND rule_version IS NOT NULL)),
  -- 规则与版本必须成对。
  CONSTRAINT order_review_batch_rule_version_paired
    CHECK((rule_code = '') = (rule_version IS NULL)),
  -- 处理完成后，四个计数必须加起来等于请求条数。
  -- ⚠️ 条件带 completed：占键那一刻统计数还是 0，
  --   而那时 requested_count 已经是真实条数，不加这个条件会当场违反。
  CONSTRAINT order_review_batch_counts_add_up
    CHECK(completed = false
          OR (approved_count + rejected_count + suspended_count + skipped_count = requested_count)),
  -- 驳回与挂起必须说清理由。
  CONSTRAINT order_review_batch_reason_present
    CHECK(action = 'APPROVE' OR btrim(reason) <> ''),
  -- 完成时必须带完成时刻。
  CONSTRAINT order_review_batch_completed_needs_time
    CHECK(completed = false OR completed_at IS NOT NULL)
);
CREATE INDEX order_review_batch_by_created
  ON order_review_batch(tenant_id,created_at DESC);
CREATE INDEX order_review_batch_by_action
  ON order_review_batch(tenant_id,action,created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. 审单结论（一张订单一份「当前结论」）
-- ---------------------------------------------------------------------------
-- 为什么是「当前结论」而不是历史流水：
--   队列要回答的是「这单现在审成什么样了」，历史由 order_review_batch_item
--   （每次批量审单的逐条结果）承载。结论表保持一单一行，
--   状态推进（挂起 → 通过）就是改这一行。
CREATE TABLE order_review_queue_case (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 复合外键指到本服务自己的 sales_order：同服务同库不是跨服务读
  -- （与 V150 对 sales_order 的复合外键同一手法）。订单被删则结论一并消失
  -- —— 订单没了，「这单审没审过」也就没有意义。
  order_id uuid NOT NULL,
  -- **三种结论，没有第四种**：
  --   APPROVED  已通过（可下发货）
  --   REJECTED  已驳回
  --   SUSPENDED 已挂起（**仍在队列里**，等人回来处理）
  -- 「未审」不是一种状态，而是**这一行不存在** —— 见第一节。
  status text NOT NULL CHECK(status IN ('APPROVED','REJECTED','SUSPENDED')),
  -- 入队时刻 = 订单的提交时刻（不另造一个时钟）。
  -- 另造时钟会让「等了多久」有两个答案。
  enqueued_at timestamptz NOT NULL,
  decided_at timestamptz NOT NULL,
  decided_by_membership_id uuid NOT NULL,
  -- 人话结论：为什么通过/驳回/挂起。驳回与挂起**必须**有理由。
  decision_reason varchar(600) NOT NULL DEFAULT '',
  -- **依据的规则**（批量通过时必填，见下面的 CHECK）。
  -- rule_code + rule_version 一起存：光有编号说不清「按哪一版判的」，
  -- 规则改版之后历史结论仍然可复算。
  rule_code varchar(64) NOT NULL DEFAULT '',
  rule_version bigint,
  -- **本次决策所依据的判定留痕**（order_review_rule_evaluation，V149）。
  -- 指向具体某一次判定，而不是「大概按某条规则判的」——
  -- 客服被追问时能一路点到那条判定记录上。
  evaluation_id uuid,
  -- 产生这份结论的批次（单条审单也是一个批次，行数 = 1）。
  batch_id uuid,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一单一份结论：两个「当前结论」会让队列对同一张单给出两个答案。
  UNIQUE(tenant_id,order_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,evaluation_id) REFERENCES order_review_rule_evaluation(tenant_id,id),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES order_review_batch(tenant_id,id),
  -- 🔴 加粗句的 DB 级落点（二）：**通过必须指名规则**。
  --   没有规则就没有「明确的规则」，只有「默认全过」。
  CONSTRAINT order_review_queue_case_approve_needs_rule
    CHECK(status <> 'APPROVED' OR (rule_code <> '' AND rule_version IS NOT NULL)),
  -- 🔴 加粗句的 DB 级落点（三）：**通过必须指向一次具体判定**。
  --   规则编号可以被人手改，判定 id 指向的是落库的留痕 —— 后者改不动（V149 已 REVOKE）。
  CONSTRAINT order_review_queue_case_approve_needs_evaluation
    CHECK(status <> 'APPROVED' OR evaluation_id IS NOT NULL),
  -- 驳回与挂起必须说清理由：没有理由的驳回，客服没法向买家解释。
  CONSTRAINT order_review_queue_case_reason_present
    CHECK(status = 'APPROVED' OR btrim(decision_reason) <> ''),
  -- 规则与判定必须成对出现：指名了规则就必须指名那次判定，反之亦然。
  CONSTRAINT order_review_queue_case_rule_and_evaluation_paired
    CHECK((rule_code = '') = (evaluation_id IS NULL))
);
CREATE INDEX order_review_queue_case_by_status
  ON order_review_queue_case(tenant_id,status,updated_at DESC);
CREATE INDEX order_review_queue_case_by_batch
  ON order_review_queue_case(tenant_id,batch_id);

-- ---------------------------------------------------------------------------
-- 3. 回执逐条结果
-- ---------------------------------------------------------------------------
-- 「本批实际通过/驳回/挂起各几条」之外的第二层：**是哪一单、为什么**。
-- 只有计数时，客服仍然答不出「为什么第 3 单没通过」。
CREATE TABLE order_review_batch_item (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  batch_id uuid NOT NULL,
  -- **请求内的序号**（从 1 开始）。回执的逐条结果按**请求下标**返回，
  -- 不按 order_id 归并 —— 归并会让第 1 条的结果顶到第 2 个位置上。
  seq_no int NOT NULL CHECK(seq_no > 0),
  -- **刻意不建到 sales_order 的外键**。
  -- 逐条结果记录的是「**我们被要求处理的那个 id**」，它完全可能解析不到订单：
  --   * 跨租户的 id（本租户看不见那张单）；
  --   * 已经删掉的订单。
  -- 建了外键，这两种情况就**写不进回执**了 —— 而「这一单没找到 / 不属于本租户」
  -- 恰恰是批量审单里最需要留痕的一条结果（它出现在 skipped 里，还带着原因）。
  -- 订单是否存在由服务层在 RLS 下**实查**得出，不是靠外键兜。
  -- ⚠️ 同样的理由，回执不随订单删除而消失：回执是举证凭据，
  --   订单被删不该把「当时审了什么」的证据一起带走。
  order_id uuid NOT NULL,
  -- 逐条结论。SKIPPED = 这一条没被处理（不在队列 / 已审过 / 规则判定不通过）。
  outcome text NOT NULL CHECK(outcome IN ('APPROVED','REJECTED','SUSPENDED','SKIPPED')),
  -- 人话原因：为什么是这个结论。SKIPPED 时**必填** ——
  -- 「跳过」不说理由就等于把失败藏起来了。
  reason varchar(600) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一批里同一条序号只能有一行。
  UNIQUE(tenant_id,batch_id,seq_no),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES order_review_batch(tenant_id,id) ON DELETE CASCADE,
  -- 跳过必须说清为什么。
  CONSTRAINT order_review_batch_item_skipped_needs_reason
    CHECK(outcome <> 'SKIPPED' OR btrim(reason) <> '')
);
CREATE INDEX order_review_batch_item_by_batch
  ON order_review_batch_item(tenant_id,batch_id,seq_no);
CREATE INDEX order_review_batch_item_by_order
  ON order_review_batch_item(tenant_id,order_id,created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. 时效分与时效状态（IMMUTABLE 函数，排序与筛选全在 DB 层）
-- ---------------------------------------------------------------------------
-- 与 V156 的 aftersales_timeliness_state 同一手法：**判定真身只有 DB 函数一处**，
-- Java 与前端都刻意不重算 —— 两处实现必然漂移，而超时直接关系账号绩效。
--
-- ⚠️ 为什么用函数而不是物化列：等候时长每时每刻都在变（等得越久越急）。
--   物化成列会立刻变陈旧，而「陈旧的时效分」比「没有时效分」更坏 ——
--   它看起来像个真分数。函数给定 (submitted_at, at) 结果确定 ⇒ IMMUTABLE。
--
-- at 由调用方显式给出（服务层取一次，整页复用同一个值），
-- 否则 WHERE 与行内展示会各取一次 now()，出现「筛了临期、第一条不临期」。
CREATE FUNCTION order_review_timeliness_score(submitted_at timestamptz, at timestamptz)
RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN submitted_at IS NULL OR at IS NULL OR at <= submitted_at THEN 0
    WHEN at - submitted_at < interval '2 hours'  THEN 0
    WHEN at - submitted_at < interval '6 hours'  THEN 25
    WHEN at - submitted_at < interval '12 hours' THEN 50
    WHEN at - submitted_at < interval '24 hours' THEN 75
    ELSE 100 END::numeric;
$$;
COMMENT ON FUNCTION order_review_timeliness_score(timestamptz,timestamptz) IS
  '9.2.1.1 待审队列的时效分（0..100）：按订单提交至今的等候时长分档。IMMUTABLE，可进 WHERE 与 ORDER BY。';

CREATE FUNCTION order_review_timeliness_state(submitted_at timestamptz, at timestamptz)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN submitted_at IS NULL OR at IS NULL THEN 'NO_DEADLINE'
    WHEN at <= submitted_at THEN 'FRESH'
    WHEN at - submitted_at < interval '6 hours'  THEN 'FRESH'
    WHEN at - submitted_at < interval '12 hours' THEN 'DUE_SOON'
    ELSE 'OVERDUE'
  END;
$$;
COMMENT ON FUNCTION order_review_timeliness_state(timestamptz,timestamptz) IS
  '9.2.1.1 待审队列的时效状态：FRESH / DUE_SOON / OVERDUE / NO_DEADLINE。判定真身只有这一处。';

-- ---------------------------------------------------------------------------
-- 5. 结论推进的护栏：终态不可回退
-- ---------------------------------------------------------------------------
-- APPROVED / REJECTED 是**终态**：已通过的不能再被驳回，已驳回的不能再被通过。
-- 否则「这单到底发不发」会随操作顺序给出两个答案 —— 而审单是发货前的最后闸门。
-- 挂起（SUSPENDED）可以再推进（挂起 → 通过 / 驳回），它是中间态不是终态。
CREATE FUNCTION guard_order_review_queue_case_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('APPROVED','REJECTED') AND NEW.status <> OLD.status THEN
    RAISE EXCEPTION '订单「%」的审单结论已是终态（%），不能改成 %：审单是发货前的最后闸门，结论必须唯一',
      OLD.order_id, OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER order_review_queue_case_terminal
  BEFORE UPDATE ON order_review_queue_case
  FOR EACH ROW EXECUTE FUNCTION guard_order_review_queue_case_terminal();

-- ---------------------------------------------------------------------------
-- 6. 回执逐条结果是留痕：不可改不可删
-- ---------------------------------------------------------------------------
-- 一次批量审单的结果是要向平台、买家与监管解释的东西。
-- 「改一下回执里的数字」在别的模块是修数据，在这里是销毁证据。
CREATE FUNCTION guard_order_review_batch_item_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是不可改写的审单回执明细表（%）：审单结论是客服向买家解释的依据',
    TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
REVOKE UPDATE,DELETE ON order_review_batch_item FROM trade_app;
CREATE TRIGGER order_review_batch_item_append_only BEFORE UPDATE OR DELETE ON order_review_batch_item
  FOR EACH ROW EXECUTE FUNCTION guard_order_review_batch_item_append_only();

-- ---------------------------------------------------------------------------
-- 7. 多租户：RLS + 授权
-- ---------------------------------------------------------------------------
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'order_review_batch','order_review_queue_case','order_review_batch_item'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_review_batch,order_review_queue_case,order_review_batch_item TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  order_review_batch,order_review_queue_case,order_review_batch_item TO starcon_app;

-- 「授权在前、收回在后」，顺序不能颠倒，否则下面几条会被上面的 GRANT 覆盖回去。
-- 逐条结果是留痕：不可改不可删。
REVOKE UPDATE,DELETE ON order_review_batch_item FROM trade_app;
-- 结论行只推进状态，不许删（删掉 = 让这单在队列里消失）。
REVOKE DELETE ON order_review_queue_case FROM trade_app;
-- 回执是凭证，不许删。
REVOKE DELETE ON order_review_batch FROM trade_app;
-- 🔴 **列级收紧**：回执的身份不可改，只有统计数允许在处理完成时写一次。
-- ⚠️ 顺序不能颠倒：PG 里**表级 UPDATE 蕴含每列 UPDATE**，
--   所以必须先收回表级 UPDATE，再逐列授回 —— 直接 REVOKE UPDATE(某列) 是空操作
--   （V156 车道实测踩过这个坑：has_column_privilege 仍为 true）。
REVOKE UPDATE ON order_review_batch FROM trade_app;
GRANT UPDATE(approved_count,rejected_count,suspended_count,skipped_count,completed,completed_at)
  ON order_review_batch TO trade_app;

-- 本迁移**不 ALTER 任何既有表**：
--   sales_order / sales_order_line / sales_order_party_snapshot /
--   order_address_snapshot / order_charge（V99）、
--   order_review_rule*（V149，9.2.1.2）、
--   order_risk_*（V150，9.2.2.2）
--   —— 全部只读。
-- 与并行车道的 V208 / V210 零交集，跨车道 DDL 回归面 = 0。
