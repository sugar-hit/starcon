-- 17.4.1.1 审批流定义。
--
-- 蓝图原文对本模块的界定：
--   「定义审批流程：适用单据类型、审批节点（角色/岗位/指定人）、审批方式（串行/并行/条件分支）、
--     金额阈值分支、超时升级规则。」
--   「**审批流按单据类型注册**，未注册的变更类操作默认可直接执行——需要显式定义才有管控。」
--
-- 那句「按单据类型注册」是本模块的核心语义，它落在三处：
--
--   1. approval_document_type 是出厂码表：一个租户能对哪些单据类型装审批流，是出厂决定的，
--      不是租户能自己新增的类型（新增类型意味着新增一整条业务链路）。与 V52 的
--      document_entity 同类，所以按先例**不加 RLS**（全局共读）。
--   2. approval_flow 上有部分唯一索引
--      UNIQUE(tenant_id, document_type_code) WHERE status='ACTIVE'：
--      **同一租户同一单据类型同时只能有一条生效流程**。下游 17.4.2.1 拿到一张单据要回答
--      「该走哪条流程」，如果同时挂着两条生效流程，这个答案就没有唯一解 —— 必须在配置期
--      就让第二条进不来，而不是在运行时挑一条。
--   3. approval_flow_no_registration 视图直接回答「这个单据类型注册了没有」：
--      未注册就是「默认可直接执行」，注册了才有管控。蓝图的默认放行语义在这里是可查询的事实，
--      而不是一句注释。
--
-- 「串行/并行/条件分支」怎么落到表上（这是本模块最容易做糊的一处，所以写死在这里）：
--   · stage_index  —— 阶段。**阶段之间串行**：stage_index 小的先走完才轮到大的。
--   · approval_mode —— 阶段**内部**的协作方式：
--       SERIAL     独占本阶段（阶段内只允许有一个，Java 侧校验；等价于串行审批）
--       PARALLEL   与本阶段其他节点并行
--       CONDITIONAL 仅当 condition_expression 命中时参与本阶段，不命中则跳过
--   · node_order —— 节点的稳定总序。阶段之间本来由 stage_index 定序，阶段内并行节点没有先后，
--     这个字段只用来让展示与并行节点的稳定排序有确定结果。
--   条件表达式与「审批方式」是两回事：条件分支（CONDITIONAL）决定**节点是否参与**，
--   而 approval_flow_amount_branch 决定**金额落到哪条路径**。两者都留了入口，但本模块
--   **只做定义与校验**：不解析、不执行表达式（执行是 17.4.1.2 触发规则 / 17.4.2.1 运行时的事）。
--
-- 关于「岗位」：蓝图点名的三种审批人有「岗位」，但上游 17.3.1.1 只交付了 role，没有岗位表。
-- 这里按本模块职责内最小可用补一个租户级岗位字典 approval_position —— 它是**岗位**，
-- 不是第二套角色体系：岗位不携带任何权限，权限仍然只来自 role（role_permission 表不变）。
-- 岗位只回答「这个流程的第几步由哪个岗位的人审」。
--
-- 关于金额：阈值一律 numeric(19,6) + BigDecimal（AGENTS.md 禁浮点记账）。
-- 币种是三个大写字母的**可读标识**，不建到 accounting_book / legal_entity 的外键 ——
-- 17.6.1.1 账簿与本位币正在另一条车道上开发，跨模块外键按 AGENTS.md 禁止。
--
-- 关于「版本」：approval_flow.version 每次保存 +1，供下游识别「流程定义变了」。
-- 本模块**不做版本历史留档表** —— 变更前后快照由 17.5.3.1 的 identity_audit 承担，
-- 再建一张历史表是同一份事实的第二个写入所有者，违反 AGENTS.md「每张事实表只有一个写入所有者」。
--
-- 关于「生效区间」：蓝图没要求，本模块也不实现按日期自动生效/失效，因此**不建**
-- effective_from / effective_to 两列。摆着两列却没有任何代码在生效期做判定，
-- 会让读表的人以为存在一套按日期切换的机制，那是假的。

-- ---------------------------------------------------------------------------
-- 1. approval_document_type · 可注册审批的单据类型（出厂码表，不带 tenant_id）
-- ---------------------------------------------------------------------------
-- 与 document_entity 的区别要说清楚，否则会被当成同一张表的两份拷贝：
--   · document_entity（17.5.1.2）回答「这张单据用哪个码取号」，是**编号**实体；
--   · approval_document_type 回答「这类单据要不要走审批」，是**管控**范围。
-- 两者刻意各有独立条目：ROLE_GRANT / DATA_EXPORT 这类变更类操作没有编号需求，
-- 但恰恰是蓝图说的「未注册的变更类操作默认可直接执行」里最该被显式注册的那一类。
CREATE TABLE approval_document_type (
  code        varchar(32) PRIMARY KEY,
  name        varchar(100) NOT NULL,
  description varchar(300) NOT NULL DEFAULT '',
  sort_order  int NOT NULL DEFAULT 0,
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
  CHECK(btrim(name)<>'')
);

-- ---------------------------------------------------------------------------
-- 2. approval_position · 岗位字典（租户级，带 RLS）
-- ---------------------------------------------------------------------------
CREATE TABLE approval_position (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  code        varchar(32) NOT NULL,
  name        varchar(100) NOT NULL,
  description varchar(300) NOT NULL DEFAULT '',
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),
  CHECK(btrim(code)<>'' AND btrim(name)<>'')
);

-- ---------------------------------------------------------------------------
-- 3. approval_flow · 流程定义
-- ---------------------------------------------------------------------------
CREATE TABLE approval_flow (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid NOT NULL REFERENCES tenant(id),
  document_type_code     varchar(32) NOT NULL REFERENCES approval_document_type(code) ON DELETE RESTRICT,
  flow_name              varchar(100) NOT NULL,
  -- DRAFT=草稿（可随意改，不参与「谁在管控这个单据类型」的答案）
  -- ACTIVE=生效（同一单据类型同时只能有一条）
  -- DISABLED=停用（保留定义但不再管控）
  status                 text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','DISABLED')),
  description            varchar(300) NOT NULL DEFAULT '',
  version                bigint NOT NULL DEFAULT 0 CHECK(version >= 0),
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  created_by_membership_id uuid,
  UNIQUE(tenant_id, id),
  CHECK(btrim(flow_name)<>'')
);

-- 蓝图「按单据类型注册」的直接落点：同一租户同一单据类型只能有一条生效流程。
-- 第二条生效流程会在写入时被这条唯一索引挡下（Java 侧先查并给出可读的 409，
-- 这里是最后一道兜底，并发下两条同时生效也只会成功一条）。
CREATE UNIQUE INDEX approval_flow_active_document
  ON approval_flow(tenant_id, document_type_code) WHERE status='ACTIVE';

-- 列表页的主查询路径：按单据类型看当前挂着哪些流程（含非生效的）。
CREATE INDEX approval_flow_document_type
  ON approval_flow(tenant_id, document_type_code, status);

-- 「这个单据类型注册了没有」——蓝图默认放行语义的直接查询面。
CREATE VIEW approval_flow_no_registration AS
SELECT dt.code,
       dt.name,
       dt.sort_order,
       f.id AS flow_id,
       f.flow_name,
       f.status
  FROM approval_document_type dt
  LEFT JOIN approval_flow f
    ON f.document_type_code = dt.code AND f.status = 'ACTIVE'
 WHERE dt.status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- 4. approval_flow_node · 审批节点（角色 / 岗位 / 指定人）
-- ---------------------------------------------------------------------------
CREATE TABLE approval_flow_node (
  id                   uuid PRIMARY KEY,
  tenant_id            uuid NOT NULL REFERENCES tenant(id),
  flow_id              uuid NOT NULL,
  node_key             varchar(32) NOT NULL,
  node_name            varchar(100) NOT NULL,
  node_order           int NOT NULL CHECK(node_order >= 0),
  stage_index          int NOT NULL CHECK(stage_index >= 0),
  approver_type        text NOT NULL CHECK(approver_type IN ('ROLE','POSITION','USER')),
  -- approver_type 三选一：ROLE 走 role（17.3.1.1 的真实角色表），
  -- POSITION 走 approval_position，USER 走 membership（指定人）。
  role_id              uuid,
  position_id          uuid,
  membership_id        uuid,
  approval_mode        text NOT NULL DEFAULT 'SERIAL'
                       CHECK(approval_mode IN ('SERIAL','PARALLEL','CONDITIONAL')),
  condition_expression text,
  description          varchar(300) NOT NULL DEFAULT '',
  created_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 金额分支与超时升级规则都按 (flow_id, node_key) 引用节点，所以流程内 node_key 必须唯一。
  UNIQUE(tenant_id, flow_id, node_key),
  UNIQUE(tenant_id, flow_id, node_order),
  FOREIGN KEY (tenant_id, flow_id) REFERENCES approval_flow(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, role_id) REFERENCES role(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, position_id) REFERENCES approval_position(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX approval_flow_node_flow ON approval_flow_node(tenant_id, flow_id, stage_index, node_order);
CREATE INDEX approval_flow_node_role ON approval_flow_node(tenant_id, role_id) WHERE role_id IS NOT NULL;
CREATE INDEX approval_flow_node_membership ON approval_flow_node(tenant_id, membership_id) WHERE membership_id IS NOT NULL;

-- 三种审批人各认各的列：把角色和指定人一起填上，在插入时就该被挡下，
-- 而不是等运行时去猜「两个都填了算谁的」。与 V52 的 document_number_rule_segment_shape 同一手法。
ALTER TABLE approval_flow_node ADD CONSTRAINT approval_flow_node_approver_shape CHECK(
  (approver_type='ROLE'     AND role_id IS NOT NULL AND position_id IS NULL AND membership_id IS NULL)
  OR (approver_type='POSITION' AND role_id IS NULL AND position_id IS NOT NULL AND membership_id IS NULL)
  OR (approver_type='USER'    AND role_id IS NULL AND position_id IS NULL AND membership_id IS NOT NULL)
);

-- 条件分支必须带表达式，串行/并行必须不带：写错形状在插入时就被挡下。
ALTER TABLE approval_flow_node ADD CONSTRAINT approval_flow_node_mode_shape CHECK(
  (approval_mode='CONDITIONAL'
     AND condition_expression IS NOT NULL AND btrim(condition_expression)<>'')
  OR (approval_mode<>'CONDITIONAL' AND condition_expression IS NULL)
);

-- ---------------------------------------------------------------------------
-- 5. approval_flow_amount_branch · 金额阈值分支
-- ---------------------------------------------------------------------------
-- 一条分支说清：「这个币种下、金额落在这个区间时，流程改走 target_node_key 那个节点」。
-- 币种 NOT NULL 是刻意的：区间是否交叠只有在**同一币种内**才有确定答案，
-- 留一个「不分币种」的空档会让交叠判定本身变得没有唯一解。
-- 区间可以留缺口（min 与 max 都可为 NULL 端点），缺口内的金额走流程的默认串行路径 ——
-- 这是定义侧的合法配置，不是遗漏，本模块不做「必须铺满」的强制。
CREATE TABLE approval_flow_amount_branch (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL REFERENCES tenant(id),
  flow_id        uuid NOT NULL,
  branch_name    varchar(100) NOT NULL,
  currency_code  char(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  min_amount     numeric(19,6),
  max_amount     numeric(19,6),
  target_node_key varchar(32) NOT NULL,
  priority       int NOT NULL DEFAULT 0,
  description    varchar(300) NOT NULL DEFAULT '',
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, flow_id, branch_name),
  FOREIGN KEY (tenant_id, flow_id) REFERENCES approval_flow(tenant_id, id) ON DELETE CASCADE,
  -- 目标节点必须属于**同一条流程**：跨流程引用会让一条分支指向一条根本不在这条链上的节点。
  FOREIGN KEY (tenant_id, flow_id, target_node_key)
    REFERENCES approval_flow_node(tenant_id, flow_id, node_key) ON DELETE CASCADE,
  -- 两端至少要有一端，两个都空等于「全金额都走这里」，那不是分支而是默认路径，
  -- 应该直接体现在节点顺序上。
  CHECK(min_amount IS NOT NULL OR max_amount IS NOT NULL),
  CHECK(min_amount IS NULL OR min_amount >= 0),
  CHECK(max_amount IS NULL OR max_amount >= 0),
  CHECK(min_amount IS NULL OR max_amount IS NULL OR max_amount > min_amount)
);

-- 交叠判定只按 (流程, 币种) 分组：不同币种的区间允许重合，Java 侧 overlaps() 与此同口径。
CREATE INDEX approval_flow_amount_branch_lookup
  ON approval_flow_amount_branch(tenant_id, flow_id, currency_code);

-- ---------------------------------------------------------------------------
-- 6. approval_flow_escalation · 超时升级规则
-- ---------------------------------------------------------------------------
-- 「这个节点审批人超过 N 小时没动，就怎么处理」。ESCALATE 把审批权转给另一类人，
-- AUTO_APPROVE 是超时视同通过 —— 后者是一条真实的管控策略，所以本模块只把它**定义**出来，
-- 真正在超时后放行是 17.4.2.1 运行时的事，本模块不实现任何定时器。
CREATE TABLE approval_flow_escalation (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL REFERENCES tenant(id),
  flow_id               uuid NOT NULL,
  node_key              varchar(32) NOT NULL,
  timeout_action        text NOT NULL CHECK(timeout_action IN ('ESCALATE','AUTO_APPROVE')),
  timeout_hours         int NOT NULL CHECK(timeout_hours > 0),
  escalate_approver_type text CHECK(escalate_approver_type IN ('ROLE','POSITION','USER')),
  escalate_role_id      uuid,
  escalate_position_id  uuid,
  escalate_membership_id uuid,
  description           varchar(300) NOT NULL DEFAULT '',
  created_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一节点同一时限只能有一条：同一节点挂两条 24 小时规则，命中哪条没有答案。
  UNIQUE(tenant_id, flow_id, node_key, timeout_hours),
  FOREIGN KEY (tenant_id, flow_id) REFERENCES approval_flow(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, flow_id, node_key)
    REFERENCES approval_flow_node(tenant_id, flow_id, node_key) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, escalate_role_id) REFERENCES role(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, escalate_position_id) REFERENCES approval_position(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, escalate_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT
);

-- ESCALATE 必须指明转给谁；AUTO_APPROVE 不需要人，三列都得空着。
ALTER TABLE approval_flow_escalation ADD CONSTRAINT approval_flow_escalation_shape CHECK(
  (timeout_action='ESCALATE'
     AND escalate_approver_type IS NOT NULL
     AND ((escalate_approver_type='ROLE'     AND escalate_role_id IS NOT NULL AND escalate_position_id IS NULL AND escalate_membership_id IS NULL)
       OR (escalate_approver_type='POSITION' AND escalate_role_id IS NULL AND escalate_position_id IS NOT NULL AND escalate_membership_id IS NULL)
       OR (escalate_approver_type='USER'     AND escalate_role_id IS NULL AND escalate_position_id IS NULL AND escalate_membership_id IS NOT NULL)))
  OR (timeout_action='AUTO_APPROVE'
     AND escalate_approver_type IS NULL
     AND escalate_role_id IS NULL
     AND escalate_position_id IS NULL
     AND escalate_membership_id IS NULL)
);

CREATE INDEX approval_flow_escalation_node ON approval_flow_escalation(tenant_id, flow_id, node_key);

-- ---------------------------------------------------------------------------
-- 7. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
-- approval_document_type 是全局码表，不带 tenant_id，按 V3 / V48 / V52 的先例不加 RLS。
-- 其余六张表全部 ENABLE + FORCE RLS，policy 与既有迁移逐字一致。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'approval_position',
    'approval_flow',
    'approval_flow_node',
    'approval_flow_amount_branch',
    'approval_flow_escalation'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 8. 授权
-- ---------------------------------------------------------------------------
GRANT SELECT ON approval_document_type TO identity_app;
GRANT SELECT ON approval_flow_no_registration TO identity_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON approval_position TO identity_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON approval_flow TO identity_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON approval_flow_node TO identity_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON approval_flow_amount_branch TO identity_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON approval_flow_escalation TO identity_app;

-- ---------------------------------------------------------------------------
-- 9. 出厂可注册审批的单据类型
-- ---------------------------------------------------------------------------
-- 与 document_entity 有意重叠（采购订单这类单据既取号也可能要审批），但这张表多出
-- 「变更类操作」那几类：它们没有编号，却正是蓝图说的「未注册则默认可直接执行」的高风险面。
INSERT INTO approval_document_type (code,name,description,sort_order) VALUES
  ('PURCHASE_ORDER','采购订单','向供应商下单，按金额走分级审批',10),
  ('PURCHASE_PAYMENT','采购付款','付供应商款项，高额需财务负责人会签',20),
  ('STOCK_ADJUSTMENT','库存调整','盘盈盘亏与损耗调整，影响账实',30),
  ('STOCK_TRANSFER','调拨单','仓间调拨出库',40),
  ('PRICE_CHANGE','调价单','售价与成本价调整',50),
  ('CREDIT_NOTE','贷项单','红字冲销与退款凭证',60),
  ('REFUND','退款单','对买家退款',70),
  ('WRITE_OFF','核销单','应收应付核销',80),
  ('VENDOR_ONBOARD','供应商准入','新增与关键信息变更供应商',90),
  ('MEMBER_OFFBOARD','离职停用','停用成员并转移在办审批责任',100),
  ('ROLE_GRANT','角色授权','授予或变更页面操作权限与数据范围',110),
  ('DATA_EXPORT','数据导出','导出单据与报表数据',120);

-- ---------------------------------------------------------------------------
-- 10. 页面授权
-- ---------------------------------------------------------------------------
-- 与会计账簿、业务参数、编号规则同口径：按页面 CRUD 授权，组织变更沿用「仅 OWNER」纪律。
INSERT INTO page_resource(code,name) VALUES ('identity.approval_flow','审批流定义')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.approval_flow.'||action,'identity.approval_flow',action
FROM unnest(ARRAY['read','create','update','delete']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.approval_flow.%' ON CONFLICT DO NOTHING;