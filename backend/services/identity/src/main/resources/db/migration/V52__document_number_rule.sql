-- 17.5.1.2 编号规则。
--
-- 蓝图对本模块的三条硬要求，落到下面五张表上：
--
--   「编号是业务单据的可读标识，不是外键」
--     编号本身不指向任何业务表。document_number_issued.document_type /
--     document_no 是**可读标识**（业务单据本体在各自服务的库里，AGENTS.md 禁止跨服务
--     数据库访问），没有外键；顺序与前缀来自规则与计数器，同样不与任何业务主键耦合。
--     唯一性由 (tenant_id, entity_code, number_text) 兜：同一个租户同一实体的同一个编号
--     不可能被发放两次。
--
--   「规则冲突要在配置时校验」
--     同一 entity_code 上两条**主规则**（rule_kind='PRIMARY'）的生效区间不允许重叠，
--     交叠判定在写入时由 Java 查一次同实体在启区间（见 NumberRuleController.overlaps），
--     并用部分索引支撑。重叠意味着某一刻"该用哪条规则"没有唯一答案，必须在配置时拒。
--     可复用子规则（COMPONENT）不参与该判定——它只被 NESTED 段引用，结构上不可能
--     与谁争同一实体的同一时刻。
--
--   「序号按实体/日期重置」
--     计数器表 document_number_sequence 的键是
--     (tenant_id, entity_code, scope_key, reset_key)：
--       · entity_code  —— 按实体重置。计数器**永远用根规则的实体**，所以同一个子规则被
--                         两个不同实体的规则嵌套时各自独立计数；被同一实体的两条规则嵌套时
--                         按 scope=ENTITY 连续计数（换规则不等于从头开始）。
--       · reset_key    —— 按日期重置。NONE 恒为 'ALL'，DAILY 为 yyyy-MM-dd，
--                         MONTHLY 为 yyyy-MM，YEARLY 为 yyyy。换一天/一月/年就是一个新桶，
--                         序号自然从 sequence_start 重新开始。
--     真正取号走 upsert（... on conflict do update set current_value = current_value + 1
--     returning current_value），在数据库层原子递增，不依赖应用层读-改-写。
--
-- 「支持多级嵌套」：规则由有序的段（document_number_rule_segment）拼成，NESTED 段引用
-- 另一条 COMPONENT 规则，渲染时递归展开。嵌套的环与深度在**配置时**就拒（写入前查可达图），
-- 渲染时再兜一层深度上限。

-- ---------------------------------------------------------------------------
-- 1. document_entity · 业务单据实体码表（全局，不带 tenant_id）
-- ---------------------------------------------------------------------------
-- 「序号按实体重置」里的实体是这张码表：与具体租户无关，租户没有新增单据类型的需求
-- （新增单据类型意味着新增一整条业务链路，不是配置项能决定的），与
-- business_parameter_definition / page_resource 是同一类出厂码表，按先例不加 RLS。
CREATE TABLE document_entity (
  code        varchar(32) PRIMARY KEY,
  name        varchar(100) NOT NULL,
  description varchar(300) NOT NULL DEFAULT '',
  sort_order  int NOT NULL DEFAULT 0,
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
  CHECK(btrim(name)<>'')
);

-- ---------------------------------------------------------------------------
-- 2. document_number_rule · 编号规则定义
-- ---------------------------------------------------------------------------
CREATE TABLE document_number_rule (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  entity_code     varchar(32) NOT NULL REFERENCES document_entity(code) ON DELETE RESTRICT,
  rule_name       varchar(100) NOT NULL,
  -- PRIMARY=该实体的正式编号规则（参与生效区间冲突判定）；
  -- COMPONENT=可复用子规则，只被 NESTED 段引用，不直接给实体发号。
  rule_kind       text NOT NULL DEFAULT 'PRIMARY' CHECK(rule_kind IN ('PRIMARY','COMPONENT')),
  status          text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  -- 日期段按哪个时区渲染。整棵嵌套树用**根规则**的时区，一次预览只有一个"今天"。
  timezone        varchar(64) NOT NULL DEFAULT 'UTC',
  effective_from  timestamptz NOT NULL DEFAULT now(),
  -- NULL = 长期有效。
  effective_to    timestamptz,
  description     varchar(300) NOT NULL DEFAULT '',
  version         bigint NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  created_by_membership_id uuid,
  UNIQUE(tenant_id, id),
  CHECK(btrim(rule_name)<>'' AND btrim(timezone)<>''),
  -- 生效区间不能倒置。
  CHECK(effective_to IS NULL OR effective_to > effective_from)
);

-- 冲突判定的热路径：查「某实体上启用的主规则及其生效区间」。
CREATE INDEX document_number_rule_entity
  ON document_number_rule(tenant_id, entity_code) WHERE rule_kind='PRIMARY' AND status='ACTIVE';
-- 「这个子规则被谁引用了」与删除前的引用检查。
CREATE INDEX document_number_rule_kind
  ON document_number_rule(tenant_id, rule_kind, status);

-- ---------------------------------------------------------------------------
-- 3. document_number_rule_segment · 规则的组成段（前缀 + 日期 + 序号 + 后缀，可嵌套）
-- ---------------------------------------------------------------------------
CREATE TABLE document_number_rule_segment (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  rule_id         uuid NOT NULL,
  position        int NOT NULL CHECK(position >= 0),
  segment_type    text NOT NULL
                  CHECK(segment_type IN ('PREFIX','DATE','SEQUENCE','SUFFIX','LITERAL','NESTED')),
  -- PREFIX / SUFFIX / LITERAL：固定文本
  literal_text    varchar(64),
  -- DATE：日期格式，白名单在下面用 CHECK 钉死（Java 侧同一份白名单渲染）
  date_format     varchar(16),
  -- SEQUENCE：宽度 / 起始值 / 重置口径 / 计数作用域
  sequence_width  int CHECK(sequence_width BETWEEN 1 AND 12),
  sequence_start  bigint CHECK(sequence_start >= 0),
  sequence_reset  text CHECK(sequence_reset IN ('NONE','DAILY','MONTHLY','YEARLY')),
  sequence_scope  text CHECK(sequence_scope IN ('ENTITY','RULE')),
  -- NESTED：内嵌哪条子规则（复合外键钉住"这条子规则确实属于本租户"）
  child_rule_id   uuid,
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_id, position),
  FOREIGN KEY (tenant_id, rule_id) REFERENCES document_number_rule(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, child_rule_id) REFERENCES document_number_rule(tenant_id, id) ON DELETE RESTRICT
);

-- 每种段只认属于它自己的列：写错形状在插入时就该被挡下，而不是渲染时才发现。
ALTER TABLE document_number_rule_segment ADD CONSTRAINT document_number_rule_segment_shape CHECK(
  (segment_type IN ('PREFIX','SUFFIX','LITERAL')
     AND literal_text IS NOT NULL AND btrim(literal_text)<>''
     AND date_format IS NULL AND sequence_width IS NULL AND sequence_start IS NULL
     AND sequence_reset IS NULL AND sequence_scope IS NULL AND child_rule_id IS NULL)
  OR
  (segment_type='DATE'
     AND literal_text IS NULL AND date_format IS NOT NULL
     AND sequence_width IS NULL AND sequence_start IS NULL
     AND sequence_reset IS NULL AND sequence_scope IS NULL AND child_rule_id IS NULL)
  OR
  (segment_type='SEQUENCE'
     AND literal_text IS NULL AND date_format IS NULL
     AND sequence_width IS NOT NULL AND sequence_start IS NOT NULL
     AND sequence_reset IS NOT NULL AND sequence_scope IS NOT NULL AND child_rule_id IS NULL)
  OR
  (segment_type='NESTED'
     AND literal_text IS NULL AND date_format IS NULL
     AND sequence_width IS NULL AND sequence_start IS NULL
     AND sequence_reset IS NULL AND sequence_scope IS NULL AND child_rule_id IS NOT NULL)
);

-- 日期格式白名单：渲染实现按这一份翻译，新增格式必须同时改这里。
ALTER TABLE document_number_rule_segment ADD CONSTRAINT document_number_rule_segment_date_format
  CHECK(date_format IS NULL OR date_format IN ('yyyyMMdd','yyyyMM','yyyy','yyMMdd','MMdd','yyyy-MM-dd'));

CREATE INDEX document_number_rule_segment_rule
  ON document_number_rule_segment(tenant_id, rule_id, position);
CREATE INDEX document_number_rule_segment_child
  ON document_number_rule_segment(tenant_id, child_rule_id) WHERE child_rule_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. document_number_sequence · 序号计数器（真落库，重置语义在键上）
-- ---------------------------------------------------------------------------
CREATE TABLE document_number_sequence (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL REFERENCES tenant(id),
  -- 永远写**根规则**的实体：子规则被两个不同实体引用时各自独立计数。
  entity_code   varchar(32) NOT NULL REFERENCES document_entity(code) ON DELETE RESTRICT,
  -- 计数作用域：'ENTITY' 表示换规则也连续计数；否则写 'RULE:<该段所属规则 id>'。
  scope_key     varchar(48) NOT NULL,
  -- 重置桶：NONE→'ALL'、DAILY→yyyy-MM-dd、MONTHLY→yyyy-MM、YEARLY→yyyy。
  reset_key     varchar(16) NOT NULL,
  -- 序号是计数不是金额，用 bigint（AGENTS.md 的 NUMERIC 约束针对金额与数量）。
  current_value bigint NOT NULL CHECK(current_value >= 0),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 取号就是 upsert 这一个键，所以它必须是唯一约束而不是普通索引。
  UNIQUE(tenant_id, entity_code, scope_key, reset_key),
  CHECK(btrim(scope_key)<>'' AND btrim(reset_key)<>'')
);

CREATE INDEX document_number_sequence_entity
  ON document_number_sequence(tenant_id, entity_code, reset_key);

-- ---------------------------------------------------------------------------
-- 5. document_number_issued · 已发放编号（取号留痕，不可改）
-- ---------------------------------------------------------------------------
-- 与 17.5.3.1 审计、17.5.1.1 变更历史同一纪律：已发放的编号不能被改或删。
-- 一个编号一旦发出去就被业务单据引用，改它等于让历史单据上的标识悄悄变样。
CREATE TABLE document_number_issued (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL REFERENCES tenant(id),
  entity_code        varchar(32) NOT NULL REFERENCES document_entity(code) ON DELETE RESTRICT,
  rule_id            uuid NOT NULL,
  number_text        varchar(128) NOT NULL,
  sequence_value     bigint NOT NULL,
  scope_key          varchar(48) NOT NULL,
  reset_key          varchar(16) NOT NULL,
  -- 业务单据引用：可读标识 + 类型，**不是外键**（单据在各服务自己的库里）。
  document_type      varchar(32),
  document_no        varchar(64),
  issued_by_membership_id uuid,
  actor_name         varchar(100),
  issued_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY (tenant_id, rule_id) REFERENCES document_number_rule(tenant_id, id) ON DELETE RESTRICT,
  -- 同一个租户同一实体的同一个编号只能发出去一次：两条规则碰巧渲染出同一个编号时，
  -- 第二条必须失败而不是制造两个"看起来一样"的单据号。
  UNIQUE(tenant_id, entity_code, number_text)
);

CREATE INDEX document_number_issued_entity
  ON document_number_issued(tenant_id, entity_code, issued_at DESC);
CREATE INDEX document_number_issued_rule
  ON document_number_issued(tenant_id, rule_id, issued_at DESC);
CREATE INDEX document_number_issued_document
  ON document_number_issued(tenant_id, document_type, document_no);

CREATE FUNCTION document_number_issued_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '已发放的编号不可修改或删除（17.5.1.2 编号一经发出即为业务标识）' USING ERRCODE='42501';
END $$;

CREATE TRIGGER document_number_issued_immutable_guard
  BEFORE UPDATE OR DELETE ON document_number_issued
  FOR EACH ROW EXECUTE FUNCTION document_number_issued_immutable();

-- ---------------------------------------------------------------------------
-- 6. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
-- document_entity 是全局码表，不带 tenant_id，按 V3 / V48 的先例不加 RLS：
-- 它是所有租户共读的同一份出厂单据类型。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'document_number_rule',
    'document_number_rule_segment',
    'document_number_sequence',
    'document_number_issued'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 7. 授权
-- ---------------------------------------------------------------------------
GRANT SELECT ON document_entity TO identity_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON document_number_rule TO identity_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON document_number_rule_segment TO identity_app;
GRANT SELECT,INSERT,UPDATE ON document_number_sequence TO identity_app;
-- 已发放编号只追加：与 identity_audit / business_parameter_history 同一授权口径。
GRANT SELECT,INSERT ON document_number_issued TO identity_app;
REVOKE UPDATE, DELETE ON document_number_issued FROM identity_app;

-- ---------------------------------------------------------------------------
-- 8. 出厂单据实体码表
-- ---------------------------------------------------------------------------
-- 对应蓝图点名的下游：全部单据创建（订单、采购、出库、售后等）。
INSERT INTO document_entity (code,name,description,sort_order) VALUES
  ('ORDER','销售订单','下游 9 审单 / 订单聚合创建单据时取号',10),
  ('PURCHASE_ORDER','采购订单','6 采购：向供应商下单',20),
  ('INBOUND','入库单','7 库存：采购到货或退货入库',30),
  ('OUTBOUND','出库单','8 仓储作业：拣货发货出库',40),
  ('STOCK_TRANSFER','调拨单','7 库存：仓间调拨',50),
  ('STOCK_ADJUSTMENT','库存调整单','7 库存：盘点与损耗调整',60),
  ('AFTERSALE','售后单','11 售后：退款、退货与换新',70),
  ('RETURN_ORDER','退货单','8 仓储作业：买家退货入库',80),
  ('PAYMENT','付款单','15 财务：付供应商 / 平台',90),
  ('RECEIPT','收款单','15 财务：收买家 / 平台款项',100),
  ('CREDIT_NOTE','贷项单','15 财务：红字冲销凭证',110);

-- ---------------------------------------------------------------------------
-- 9. 页面授权
-- ---------------------------------------------------------------------------
-- 与会计账簿、业务参数同口径：按页面 CRUD 授权，组织变更沿用「仅 OWNER」纪律。
INSERT INTO page_resource(code,name) VALUES ('identity.numbering_rule','编号规则')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.numbering_rule.'||action,'identity.numbering_rule',action
FROM unnest(ARRAY['read','create','update','delete']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.numbering_rule.%' ON CONFLICT DO NOTHING;