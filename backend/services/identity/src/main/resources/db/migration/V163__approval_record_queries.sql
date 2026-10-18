-- 17.4.2.2 审批记录。
--
-- 蓝图原文对本模块的界定：
--   「记录与查询审批全链路：发起、流转节点、各节点意见与时间、转交、驳回、撤回，最终结果；
--     支持导出用于审计。」
--   「上游 = 17.4.2.1；下游 = 各业务系统（审批结果驱动后续动作）。
--     **审批记录不可修改**，驳回后重新发起要生成新实例并关联原实例。」
--
-- ---------------------------------------------------------------------------
-- 本迁移**不建任何新表**。理由写在这里，免得后来的人以为漏了：
-- ---------------------------------------------------------------------------
-- 本模块是审批事实的**读取、统计与导出**层。上游 17.4.2.1（V160）已经写下了全部事实：
--
--   approval_instance          实例权威状态：当前节点、状态、金额、申请人、停留起点
--   approval_instance_action   只追加的动作留痕：APPROVE / REJECT / TRANSFER + 节点快照 + 意见 + 时刻
--
-- 蓝图点名的每一项都能从这两张表**只读推导**，所以再造一张「审批记录表」等于把同一份事实
-- 复制一份，必然与上游漂移 —— 那正是 AGENTS.md「每张事实表只有一个写入所有者」要避免的东西：
--
--   发起            → approval_instance.submitted_at / applicant_membership_id / source_*
--   流转节点        → approval_instance_action.node_key 按 decided_at 串起来（每次动作都快照了当时节点）
--   各节点意见与时间 → approval_instance_action.comment / decided_at
--   转交            → approval_instance_action.action='TRANSFER' + to_membership_id
--   驳回            → approval_instance_action.action='REJECT'（库上 CHECK 已强制驳回必带理由）
--   最终结果        → approval_instance.status / finished_at
--   重新发起关联原实例 → **上游已经留了列** approval_instance.original_instance_id，
--                       且有 FK (tenant_id,original_instance_id) -> approval_instance(tenant_id,id)，
--                       所以本模块不需要补关联列，只需把它查出来并能顺着它回溯重发链。
--
-- 统计报表同理：走**实时 SQL 聚合**，不建快照表。快照表会变成 approval_instance 的第二个副本，
-- 必然滞后，而且刷新它是本模块职责之外的另一件事。
--
-- 审计导出复用既有的 identity_audit（17.5.3.1 的写入侧），不另建导出留痕表。
--
-- ---------------------------------------------------------------------------
-- 那这个迁移还改什么？两件都必须改的事：
-- ---------------------------------------------------------------------------
--
-- 1. 索引。上游只建了 approval_instance_action_timeline(tenant_id,instance_id,decided_at,id)
--    —— 只服务「取一条实例的时间线」。本模块的查询路径是另外四类，上游一条索引都没给：
--
--      按人查     （某人经手了哪些审批）  → approval_instance_action(tenant_id,actor_membership_id,...)
--      按节点查   （某个节点压了多少单）  → approval_instance_action(tenant_id,node_key,...)
--      按时间范围查（时间段内的全部记录） → approval_instance_action(tenant_id,decided_at)
--      按人查实例（某人提交的）          → approval_instance(tenant_id,applicant_membership_id,submitted_at)
--
--    没有这些索引，「全链路记录查询」会把整表拉回来在内存里筛 —— 那不是查询，是假装有索引。
--
--    统计报表的分组维度（status / document_type / node / actor / source_system）也走同一批索引，
--    聚合本身在 DB 侧完成（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑，关键 SQL 必须可审阅）。
--
-- 2. 页面权限。identity.approval_record.{read,export}。
--    与 approval_inbox 同口径**只授 OWNER**，但这两个码同样可授予任意自定义角色
--    （POST /identity/roles 的 permissions 任意组合）—— 合规/审计岗需要能看审批记录，
--    不必因此拿到「待我审批」的审批权。read 与 export 拆成两个码：
--    「能查」和「能把全量数据导出成文件留到自己机器上」是两种不同的风险。
--
-- ---------------------------------------------------------------------------
-- 关于「审批记录不可修改」：本模块**没有**任何写入端点，
-- 上游给 approval_instance_action 只授 SELECT,INSERT（从不 UPDATE/DELETE）的授权本迁移
-- 原样保留、一行未改。本迁移新增的索引全部只服务 SELECT。
-- ---------------------------------------------------------------------------

-- 按人查：某人经手了哪些审批（作为审批人 / 转交发起人）。
-- 覆盖「按人」这一维的主力路径：actor 定位 + 时间倒序，无需排序。
CREATE INDEX approval_instance_action_by_actor
  ON approval_instance_action(tenant_id, actor_membership_id, decided_at DESC, id);

-- 按转交目标查：谁被转交过。to_membership_id 可空，只收非空的（转交回去的行不占索引）。
CREATE INDEX approval_instance_action_by_transfer_target
  ON approval_instance_action(tenant_id, to_membership_id, decided_at DESC)
  WHERE to_membership_id IS NOT NULL;

-- 按节点查：某个审批节点经手了哪些动作 / 压了多少单。节点维度是审批统计的主分组键。
CREATE INDEX approval_instance_action_by_node
  ON approval_instance_action(tenant_id, node_key, decided_at DESC, id);

-- 按时间范围查：时间段内的全部动作。全链路记录检索与统计报表都按时间窗收敛。
CREATE INDEX approval_instance_action_by_decided_at
  ON approval_instance_action(tenant_id, decided_at DESC, id);

-- 按人查实例：某人提交了哪些审批。与「按人查动作」是两个不同的问题，
-- 前者答「他发起的」，后者答「他批过的」，审批记录页面两个都要能筛。
CREATE INDEX approval_instance_by_applicant
  ON approval_instance(tenant_id, applicant_membership_id, submitted_at DESC);

-- 按时间范围查实例 + 排序：审计导出按提交时间倒序导出，走这条索引避免全表排序。
CREATE INDEX approval_instance_by_submitted_at
  ON approval_instance(tenant_id, submitted_at DESC, id);

-- 状态维度统计：按状态分组并同时按提交时间收敛。
-- 部分索引只收未结实例 —— 「还在压着的单子有多少」是审批记录最常问的一个问题，
-- 它天然只关心 PENDING，已结实例不该占索引空间。
CREATE INDEX approval_instance_by_status_pending
  ON approval_instance(tenant_id, submitted_at DESC) WHERE status='PENDING';

-- 「驳回后重新发起」的重发链：顺着 original_instance_id 找出某个原实例派生出的全部新实例。
-- 部分索引只收真正关联过原实例的行 —— 大多数实例的该列是 NULL，不该为它们建索引条目。
CREATE INDEX approval_instance_by_original
  ON approval_instance(tenant_id, original_instance_id, submitted_at DESC)
  WHERE original_instance_id IS NOT NULL;

-- 跨服务来源定位：按 source_system + source_reference_id 找一条单据的全部审批实例
-- （一条单据可能先被驳回、重新发起过若干次）。
-- 上游 approval_instance_live_source 是「存活期内唯一」的**唯一**索引，
-- 这里不能复用它：它是 UNIQUE，只收 PENDING，既查不到已结实例也查不到一条单据的完整历史。
CREATE INDEX approval_instance_by_source
  ON approval_instance(tenant_id, source_system, source_reference_id, submitted_at DESC);

-- ---------------------------------------------------------------------------
-- 页面授权
-- ---------------------------------------------------------------------------
-- read / export 拆开：能检索审批记录，与能把全量审批链路导出成 CSV 落到自己机器上，
-- 是两种不同的风险。刻意**只授 OWNER**，与 approval_flow / approval_rule / approval_inbox 同口径，
-- 但两个码都可授予任意自定义角色。
INSERT INTO page_resource(code,name) VALUES ('identity.approval_record','审批记录')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.approval_record.'||action,'identity.approval_record',action
FROM unnest(ARRAY['read','export']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.approval_record.%' ON CONFLICT DO NOTHING;
