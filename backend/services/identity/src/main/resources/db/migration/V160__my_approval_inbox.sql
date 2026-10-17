-- 17.4.2.1 待我审批。
--
-- 蓝图原文对本模块的界定：
--   「展示当前用户的待审批列表：单据类型、金额、申请人、停留时长、当前节点，支持批量审批与转交。」
--   「上游 = 各业务系统提交；下游 = 1 工作台待办。**审批动作在 17 执行**，工作台只做跳转。」
--
-- 为什么集成头上没有这张表：17.4.1.1 只交付了**流程定义**，17.4.1.2 只交付了**触发规则**，
-- 两者都不产生「有一张单子在等人审」这个事实。审批实例在本服务里是**第一次**被写出来，
-- 所以本模块就是 approval_instance 的**唯一写入所有者**（AGENTS.md「每张事实表只有一个写入所有者」）。
-- 17.4.2.2「审批记录」将来**读**本表做全链路查询与导出，本迁移的表结构按那个读法设计，
-- 但本车道**不实现**记录查询/统计/导出，那仍然是 17.4.2.2 的职责。
--
-- ---------------------------------------------------------------------------
-- 跨服务边界（AGENTS.md 禁止跨服务 SQL / FK / 视图）
-- ---------------------------------------------------------------------------
-- 各业务系统在**各自的库**里（trade / channel / finance …），所以：
--   · source_system + source_reference_id 是**跨服务定位键**，刻意**不建外键** ——
--     建了也指向一张本库不存在的表，等于把别的服务的库结构复制进自己的 schema；
--   · 本服务只保证「**实例在本服务内的权威状态与审批动作**」：谁提交的不重要，
--     重要的是这张单现在停在哪个节点、谁有权动它、动完之后是什么状态；
--   · 提交入口是**授权 API**（X-Internal-Key + 会话成员关系），不是一个跨库直写。
--     各业务系统的对接状态由 GET /identity/my-approvals/capabilities 如实报出，未接的写 Delivered=false。
--
-- ---------------------------------------------------------------------------
-- 1. approval_instance · 审批实例（权威状态）
-- ---------------------------------------------------------------------------
-- 关于「单据类型」为什么不落成本表的列：审批流是**按单据类型注册**的（V83），
-- 同一租户同一单据类型同时只能有一条生效流程，所以 document_type_code 由 flow_id 唯一决定。
-- 再抄一份到本表就多一个可能与流程漂移的副本 —— 读的时候 join approval_flow 取，
-- 单一事实来源，且列表本来就要 join 出流程名与当前节点名。
--
-- 关于「当前节点」：存 node_key 而不是整份节点快照。节点归属由 V83 的 approval_flow_node 拥有，
-- 本表只回答「现在停在哪个节点」。三列复合外键把「这个节点必须属于这条流程」变成库级约束。
--
-- 关于「停留时长」：**不落列**。停留时长 = 现在 − 进入当前节点的时刻，
-- 它对时间是单调的，所以落一个 `node_entered_at` 就够了：
--   · 筛选「停留超过 N 分钟」→ node_entered_at <= now() - N * interval '1 second'，
--     走 approval_instance_pending 部分索引（tenant_id, node_entered_at）；
--   · 排序「最久的排前面」→ order by node_entered_at asc，与按停留时长排序**恒等**，
--     同样走索引，不需要把全表拉回内存排；
--   · 展示值由 IMMUTABLE 函数 approval_dwell_seconds 在 DB 侧算，不在 Java 里算。
-- 终态实例的停留时长冻结在 finished_at，不随时间继续增长。
--
-- 关于 assigned_membership_id（转交指派）：
--   NULL     → 按**节点配置的审批人**判定（ROLE / POSITION / USER，见下）；
--   非 NULL  → 只有这一个人能看见并审批这条待办。
-- 转交不是「给节点换个配置」，它是**实例级的一次性指派**，所以落在实例上而不是节点上。
-- 这也让「转交回去」有确切含义：把指派清空，回到节点配置的那批人。
--
-- 关于 POSITION 审批人无法解析：V83 的 approval_position 是**岗位字典**，不带任何成员绑定
-- （蓝图那句「岗位不携带任何权限，权限仍然只来自 role」）。所以 POSITION 节点上停着的待办
-- **解析不出具体的人**，它不会出现在任何人的待审批列表里。
-- 本模块**不假装**能解析它、也**不发明**岗位-成员绑定关系（那是给 17.4.1.1 加的新权威）：
-- GET /identity/my-approvals 把这类节点如实列进 unclaimedNodeKeys，由页面显示「这些节点当前无人可审」。
--
CREATE TABLE approval_instance (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL REFERENCES tenant(id),
  instance_no           varchar(32) NOT NULL,
  flow_id               uuid NOT NULL,
  current_node_key      varchar(32) NOT NULL,
  -- 提交来源。source_system / source_reference_id 是跨服务定位键，无外键（见文件头）。
  source_system         varchar(32) NOT NULL,
  source_reference_id   varchar(64) NOT NULL,
  -- 单据可读标题：发起时快照。业务系统在别的库，改了标题也不会回写到这里，
  -- 待审批列表要显示的是「发起时它叫什么」，否则历史待办会跟着别人的改名漂移。
  subject_title         varchar(200) NOT NULL DEFAULT '',
  applicant_membership_id uuid NOT NULL,
  -- 转交指派（见文件头）。
  assigned_membership_id uuid,
  -- 金额快照：numeric(19,6) + BigDecimal（AGENTS.md 禁浮点记账）。
  -- 可为 NULL：ROLE_GRANT / DATA_EXPORT 这类变更类操作本来就没有金额，
  -- 强行给 0 会让「金额阈值分支」把 0 当成一个真实金额去命中区间。
  amount                numeric(19,6),
  currency_code         char(3),
  -- PENDING 等待当前节点审批；APPROVED 全部节点通过（终态）；REJECTED 被驳回（终态）。
  -- WITHDRAWN / CANCELLED 刻意**不在**本表的状态词表里：撤回是申请人的动作，
  -- 不在 17.4.2.1「待我审批」的职责内。17.4.2.2 与提交侧车道落地撤回时用各自的迁移扩这条 CHECK，
  -- 不由本车道预先摆两个没有任何代码路径能到达的状态。
  status                text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
  submitted_at          timestamptz NOT NULL DEFAULT now(),
  -- 进入**当前节点**的时刻：停留时长的起算点，每次流转或转交后重置。
  node_entered_at       timestamptz NOT NULL DEFAULT now(),
  finished_at           timestamptz,
  -- 驳回后重新发起要生成新实例并关联原实例（蓝图 17.4.2.2 原话）。
  original_instance_id  uuid,
  created_by_membership_id uuid,
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, instance_no),
  -- 当前节点必须属于这条流程，且申请人是本租户的成员。
  FOREIGN KEY (tenant_id, flow_id) REFERENCES approval_flow(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, flow_id, current_node_key)
    REFERENCES approval_flow_node(tenant_id, flow_id, node_key) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, applicant_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, assigned_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, original_instance_id) REFERENCES approval_instance(tenant_id, id) ON DELETE RESTRICT,
  CHECK(btrim(subject_title) <> ''),
  CHECK(amount IS NULL OR amount >= 0),
  CHECK(currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
  -- 金额与币种同进同出：只有金额没有币种的阈值分支匹配没有唯一答案。
  CHECK((amount IS NULL) = (currency_code IS NULL)),
  -- 终态必须有结束时刻，未结必须有结束时刻**之外**的停留起点。
  CHECK((status = 'PENDING') = (finished_at IS NULL))
);

-- 实例号用全局序列：本表只存在于本服务，序列号不承载租户语义，
-- 租户内唯一由 UNIQUE(tenant_id, instance_no) 兜底。
CREATE SEQUENCE approval_instance_no_seq;
CREATE OR REPLACE FUNCTION approval_next_instance_no() RETURNS varchar
LANGUAGE sql VOLATILE AS $$
  SELECT 'AP-' || lpad(nextval('approval_instance_no_seq')::text, 12, '0')
$$;

-- 待审批列表的主查询路径：待办按「停留最久」排序是这条页面默认顺序，
-- 部分索引只收 PENDING，已结实例不进索引。
CREATE INDEX approval_instance_pending
  ON approval_instance(tenant_id, node_entered_at) WHERE status='PENDING';

-- 转交指派的待办：只看「有人被单独指派」的实例。
CREATE INDEX approval_instance_assigned
  ON approval_instance(tenant_id, assigned_membership_id) WHERE status='PENDING';

-- 提交入口的幂等：同一来源单据在**存活期间**只能有一条实例（§4 说明为什么用部分唯一）。
CREATE UNIQUE INDEX approval_instance_live_source
  ON approval_instance(tenant_id, source_system, source_reference_id) WHERE status='PENDING';

-- 列表页按单据类型筛选：单据类型来自 flow_id，所以走 join 后的流程表索引。
CREATE INDEX approval_instance_by_flow ON approval_instance(tenant_id, flow_id);

-- ---------------------------------------------------------------------------
-- 2. approval_dwell_seconds · 停留时长（DB 侧计算）
-- ---------------------------------------------------------------------------
-- IMMUTABLE 且**两个时间都由调用方传入**：now() 是 STABLE 不是 IMMUTABLE，
-- 把 now() 写进函数体既不能声明 IMMUTABLE，也会让计划器不敢缓存结果。
-- 调用方写 approval_dwell_seconds(now(), node_entered_at) —— 语义仍然是「停留了多久」，
-- 但函数本身保持可内联、可索引。
-- 冻结语义由调用方决定：未结实例传 now()，已结实例传 finished_at。
CREATE FUNCTION approval_dwell_seconds(clock timestamptz, entered timestamptz)
RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN entered IS NULL THEN 0
    WHEN clock <= entered THEN 0
    ELSE floor(extract(epoch FROM (clock - entered)))::bigint
  END
$$;

-- ---------------------------------------------------------------------------
-- 3. approval_instance_action · 本模块写下的审批动作（只追加）
-- ---------------------------------------------------------------------------
-- 为什么本表在 17.4.2.1 而不是 17.4.2.2：审批与转交是**本模块的输出**
-- （蓝图：「输出：待审批列表；审批动作」）。执行一次审批动作却不记录是谁、在哪个节点、
-- 什么时刻、什么意见做的，这个动作就不可追责，转交更是连「现在该谁审」都说不清。
-- 所以这里是**动作的写入侧**。
-- 17.4.2.2「审批记录」是同一批事实的**查询与报表侧**（全链路检索、各节点意见、导出、统计）——
-- 本车道只写不读，读取端点是 17.4.2.2 的职责，本模块一个都不实现。
--
-- 「审批记录不可修改」（蓝图 17.4.2.2 原话）在本表上是**权限级**保证：
-- 迁移只授 SELECT,INSERT，从不授 UPDATE/DELETE，控制器也没有任何修改端点。
CREATE TABLE approval_instance_action (
  id                   uuid PRIMARY KEY,
  tenant_id            uuid NOT NULL REFERENCES tenant(id),
  instance_id          uuid NOT NULL,
  -- APPROVE 通过 / REJECT 驳回 / TRANSFER 转交。
  action               text NOT NULL CHECK(action IN ('APPROVE','REJECT','TRANSFER')),
  actor_membership_id  uuid NOT NULL,
  -- 动作发生时所处的节点：审批记录按节点串时间线，所以快照当时的 node_key 而不是查实例的当前值。
  node_key             varchar(32) NOT NULL,
  comment              varchar(500) NOT NULL DEFAULT '',
  -- 转交目标。TRANSFER 可空：空表示「交回节点配置的审批人」（撤销指派）。
  to_membership_id     uuid,
  decided_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES approval_instance(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, actor_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, to_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  -- 通过与驳回都不是转交：带上 to_membership_id 就是「一边通过一边把人转走」这种自相矛盾的记录。
  CHECK(action = 'TRANSFER' OR to_membership_id IS NULL),
  -- 驳回必须写理由：驳回是把一条已经发出去的单子打回去，没有理由的驳回对申请人毫无意义，
  -- 对事后审计也等于没有结论。通过可以留空（同意不需要解释），转交可以留空（转交不是表态）。
  CHECK(action <> 'REJECT' OR btrim(comment) <> '')
);

-- 时间线读法：按实例取全部动作，按时刻串。
CREATE INDEX approval_instance_action_timeline
  ON approval_instance_action(tenant_id, instance_id, decided_at, id);

-- ---------------------------------------------------------------------------
-- 4. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
-- 两张表 ENABLE + FORCE RLS，policy 与 V83 / V89 逐字一致。
-- approval_document_type / approval_flow / approval_flow_node / membership 的既有 RLS
-- 在实例查询的每一条路径上都生效：拿子租户的 flow_id 去父租户建实例会被外键与 RLS 双重拒绝。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['approval_instance','approval_instance_action'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 5. 授权
-- ---------------------------------------------------------------------------
-- approval_instance_action 只授 SELECT,INSERT：**审批记录不可修改**在这里是硬保证，
-- 不依赖「没有人去调 UPDATE」这种约定。
GRANT SELECT,INSERT,UPDATE ON approval_instance TO identity_app;
GRANT SELECT,INSERT ON approval_instance_action TO identity_app;
GRANT USAGE,SELECT ON SEQUENCE approval_instance_no_seq TO identity_app;
GRANT EXECUTE ON FUNCTION approval_dwell_seconds(timestamptz,timestamptz) TO identity_app;
GRANT EXECUTE ON FUNCTION approval_next_instance_no() TO identity_app;

-- ---------------------------------------------------------------------------
-- 6. 页面授权
-- ---------------------------------------------------------------------------
-- **刻意只授 OWNER**，与审批流定义 / 审批规则同口径：审批动作是本仓风险最高的写操作之一。
-- 但待审批列表的本质是「每个人的工作台」，所以这三个权限码是可以**授予任意自定义角色**的
-- （POST /identity/roles 的 permissions 任意组合）：把 read + approve 授给一个「财务审批人」
-- 角色并分配给成员，该成员才会在自己的列表里看到分派给他的待办。
-- 节点判定仍以 role_assignment 为准 —— 权限只决定「能不能用这个页面」，节点决定「哪些单子归他」，
-- 两件事分开，否则一个人拿到页面就能审批所有单据。
INSERT INTO page_resource(code,name) VALUES ('identity.approval_inbox','待我审批')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.approval_inbox.'||action,'identity.approval_inbox',action
FROM unnest(ARRAY['read','approve','transfer']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.approval_inbox.%' ON CONFLICT DO NOTHING;
