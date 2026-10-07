-- 17.5.1.1 业务参数。
--
-- 蓝图对本模块的两条硬要求，落到下面四张表 + 一张码表上：
--
--   「参数必须有默认值与生效范围」
--     默认值在 business_parameter_definition（全局码表，随代码发布，不带 tenant_id）；
--     生效范围在 business_parameter_override，三档 GLOBAL / LEGAL_ENTITY / SHOP，
--     解析优先级 SHOP > LEGAL_ENTITY > GLOBAL > 码表默认值。
--
--   「改参数不改历史单据已冻结的行为」
--     光有变更历史不够：单据创建时读到的是**当时**的值，改参数之后历史单据必须还能
--     读回创建时那一份。所以 business_parameter_freeze 在单据创建时把生效值整份拷进
--     冻结快照（freeze + freeze_value），一个单据只冻结一次，重复冻结直接 409。
--     变更历史 business_parameter_history 追加不可改，与快照一起回答
--     「这张单据按什么参数建的」和「这个参数什么时候被谁改成什么」。
--
-- 边界：shop 属于 channel 服务的库，AGENTS.md 禁止跨服务数据库访问，
-- 所以店铺维度只存逻辑 ID + 写入时快照的店铺标签，不建跨库外键
-- （与 channel 侧 shop.legal_entity_id 存逻辑 ID 的既有做法一致）。

-- ---------------------------------------------------------------------------
-- 1. business_parameter_definition · 参数码表（全局，不带 tenant_id）
-- ---------------------------------------------------------------------------
-- 为什么不是租户表：这一张回答的是「平台定义了哪些参数、它们的出厂默认值是什么」，
-- 与具体租户无关，和 channel 的 platform / marketplace、identity 的 page_resource /
-- permission 是同一类全局码表。租户要改出厂默认值，走 GLOBAL 档的 override，
-- 不去改码表——否则一个租户改默认值会污染所有租户。
--
-- 校验规则也放在码表里（allowed_values / validation_pattern）而不是写死在 Java：
-- 新增一类参数时，校验口径跟着数据一起进版本库，Java 侧不需要新增分支。
CREATE TABLE business_parameter_definition (
  code                 varchar(64) PRIMARY KEY,
  name                 varchar(100) NOT NULL,
  -- 参数归类，对应蓝图职责里的六类：审单 / 库存 / 售后 / 成本 / 编号 / 展示。
  category             varchar(32) NOT NULL
                       CHECK(category IN ('ORDER_REVIEW','INVENTORY','AFTERSALE','COST','NUMBERING','DISPLAY')),
  -- 值类型决定取值与展示方式：ENUM 单选枚举、MULTI_ENUM 多选（逗号分隔）、
  -- INTEGER 数值（带单位，可能是金额/天数/数量）、STRING 自由文本。
  value_type           text NOT NULL
                       CHECK(value_type IN ('ENUM','MULTI_ENUM','INTEGER','STRING')),
  -- 出厂默认值。INTEGER 类型的默认值同时写进 default_value_numeric，
  -- 数值一律 NUMERIC，禁止浮点（AGENTS.md）。
  default_value        text NOT NULL,
  default_value_numeric numeric(19,6),
  -- 逗号分隔的合法取值；空串表示自由文本（改由 validation_pattern 约束）。
  allowed_values       text NOT NULL DEFAULT '',
  -- 该参数值的正则约束（空串 = 不约束）。存进库而不是写死在 Java，
  -- 是为了让"这个参数长什么样才算合法"随数据一起可查。
  validation_pattern   text NOT NULL DEFAULT '',
  unit                 varchar(16) NOT NULL DEFAULT '',
  description          varchar(300) NOT NULL DEFAULT '',
  sort_order           int NOT NULL DEFAULT 0,
  status               text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
  -- default_value 允许空串（MULTI_ENUM 的「当前没有必退情形」是合法配置），
  -- 但不允许 NULL：NULL 会让「这个参数有没有默认值」本身变成一个问号。
  CHECK(btrim(name)<>'' AND default_value IS NOT NULL)
);

-- 码表里的出厂默认值的类型自洽性：INTEGER 必须带数值，其余类型不得带。
ALTER TABLE business_parameter_definition ADD CONSTRAINT business_parameter_definition_numeric_shape
  CHECK((value_type='INTEGER') = (default_value_numeric IS NOT NULL));

-- ---------------------------------------------------------------------------
-- 2. business_parameter_override · 分范围生效值
-- ---------------------------------------------------------------------------
CREATE TABLE business_parameter_override (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  parameter_code  varchar(64) NOT NULL REFERENCES business_parameter_definition(code) ON DELETE RESTRICT,
  -- 生效范围三档。GLOBAL=全租户默认，LEGAL_ENTITY=按法人，SHOP=按店铺。
  scope_type      text NOT NULL CHECK(scope_type IN ('GLOBAL','LEGAL_ENTITY','SHOP')),
  -- 法人维度：LEGAL_ENTITY 档必须给；GLOBAL 档必须为空。
  legal_entity_id uuid,
  -- 店铺维度：SHOP 档必须给。店铺在 channel 服务的库，这里只存逻辑 ID。
  shop_id         uuid,
  -- 店铺可读名在写入时快照：店铺改名或停用后，变更历史与冻结快照仍然能自解释。
  shop_label      varchar(150),
  value_text      text NOT NULL,
  value_numeric   numeric(19,6),
  -- 生效时刻：回答"从哪一刻起是这个值"。配合 freeze 使用，
  -- 历史单据读的是冻结快照而不是这一列，所以改这里不会回溯。
  effective_from  timestamptz NOT NULL DEFAULT now(),
  note            varchar(300) NOT NULL DEFAULT '',
  version         bigint NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  created_by_membership_id uuid,
  UNIQUE(tenant_id, id)
);

-- 生效范围与目标对象必须一一对应：否则会出现"GLOBAL 档却挂着某个法人"这种
-- 解析时无法解释的行——它到底对谁生效？
ALTER TABLE business_parameter_override ADD CONSTRAINT business_parameter_override_scope_shape
  CHECK(
    (scope_type='GLOBAL'       AND legal_entity_id IS NULL     AND shop_id IS NULL) OR
    (scope_type='LEGAL_ENTITY' AND legal_entity_id IS NOT NULL AND shop_id IS NULL) OR
    (scope_type='SHOP'         AND shop_id IS NOT NULL)
  );

-- 法人维度建复合外键，钉住"这个法人确实属于本租户"；
-- legal_entity 上有 UNIQUE(tenant_id,id)，复合外键才能表达租户归属。
ALTER TABLE business_parameter_override ADD CONSTRAINT business_parameter_override_legal_entity_fk
  FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES legal_entity(tenant_id, id) ON DELETE CASCADE;

-- 一个「参数 × 生效范围」只能有一条覆盖值。分三个部分唯一索引而不是一个
-- 三列唯一约束：PG 的 UNIQUE 认为 NULL 互不相等，LEGAL_ENTITY/SHOP 档里
-- 有一列为 NULL 时唯一约束根本不会去重，会放进任意多条重复配置。
CREATE UNIQUE INDEX business_parameter_override_global
  ON business_parameter_override(tenant_id, parameter_code) WHERE scope_type='GLOBAL';
CREATE UNIQUE INDEX business_parameter_override_legal_entity
  ON business_parameter_override(tenant_id, parameter_code, legal_entity_id) WHERE scope_type='LEGAL_ENTITY';
CREATE UNIQUE INDEX business_parameter_override_shop
  ON business_parameter_override(tenant_id, parameter_code, shop_id) WHERE scope_type='SHOP';

-- 解析生效值的主查询：按租户 + 参数取全部候选档，再按优先级挑。
CREATE INDEX business_parameter_override_resolve
  ON business_parameter_override(tenant_id, parameter_code, scope_type);
-- 按法人/店铺反查"这个法人（或店铺）上配了哪些参数"。
CREATE INDEX business_parameter_override_legal_entity_list
  ON business_parameter_override(tenant_id, legal_entity_id) WHERE scope_type='LEGAL_ENTITY';
CREATE INDEX business_parameter_override_shop_list
  ON business_parameter_override(tenant_id, shop_id) WHERE scope_type='SHOP';

-- ---------------------------------------------------------------------------
-- 3. business_parameter_history · 变更历史（追加，不可改）
-- ---------------------------------------------------------------------------
-- 与 17.5.3.1 审计同一纪律：应用层"不写 UPDATE"不是保证，拿到连接就能直接改。
-- 这里用触发器在数据库层挡住 UPDATE/DELETE，权限层再 REVOKE 一次，双重防线。
CREATE TABLE business_parameter_history (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid NOT NULL REFERENCES tenant(id),
  -- 被改的参数与它的生效范围（改哪一档就记哪一档，不合并成"这个参数变了"）。
  parameter_code         varchar(64) NOT NULL REFERENCES business_parameter_definition(code) ON DELETE RESTRICT,
  scope_type             text NOT NULL CHECK(scope_type IN ('GLOBAL','LEGAL_ENTITY','SHOP')),
  legal_entity_id        uuid,
  shop_id                uuid,
  shop_label             varchar(150),
  action                 text NOT NULL CHECK(action IN ('CREATED','UPDATED','DELETED')),
  -- 变更前后值。删除时 after 为 NULL，新建时 before 为 NULL。
  before_value           text,
  before_value_numeric   numeric(19,6),
  after_value            text,
  after_value_numeric    numeric(19,6),
  effective_from         timestamptz,
  note                   varchar(300) NOT NULL DEFAULT '',
  actor_membership_id    uuid,
  actor_name             varchar(100),
  -- 变更时刻是系统时间，用 OffsetDateTime 绑定写入（见 AuditLog.utc 的说明）。
  changed_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

ALTER TABLE business_parameter_history ADD CONSTRAINT business_parameter_history_scope_shape
  CHECK(
    (scope_type='GLOBAL'       AND legal_entity_id IS NULL AND shop_id IS NULL) OR
    (scope_type='LEGAL_ENTITY' AND legal_entity_id IS NOT NULL AND shop_id IS NULL) OR
    (scope_type='SHOP'         AND shop_id IS NOT NULL)
  );

CREATE INDEX business_parameter_history_code
  ON business_parameter_history(tenant_id, parameter_code, changed_at DESC);
CREATE INDEX business_parameter_history_scope
  ON business_parameter_history(tenant_id, scope_type, changed_at DESC);
CREATE INDEX business_parameter_history_time
  ON business_parameter_history(tenant_id, changed_at DESC);

CREATE FUNCTION business_parameter_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '业务参数变更历史不可修改或删除（17.5.1.1 变更留痕）' USING ERRCODE='42501';
END $$;

CREATE TRIGGER business_parameter_history_immutable_guard
  BEFORE UPDATE OR DELETE ON business_parameter_history
  FOR EACH ROW EXECUTE FUNCTION business_parameter_history_immutable();

REVOKE UPDATE, DELETE ON business_parameter_history FROM identity_app;

-- ---------------------------------------------------------------------------
-- 4. business_parameter_freeze(_value) · 单据参数冻结快照
-- ---------------------------------------------------------------------------
-- 「改参数不改历史单据已冻结的行为」的落点：单据创建时把当时解析出的生效值整份拷进来。
-- 之后再改 override，这张单据读的还是 freeze_value 里的那份，不受任何影响。
CREATE TABLE business_parameter_freeze (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL REFERENCES tenant(id),
  -- 单据标识用「类型 + 编号」而不是外键：单据本体在各自服务的库里，
  -- identity 不能建跨库外键（AGENTS.md）。这里存的是可读标识。
  document_type      varchar(32) NOT NULL,
  document_no        varchar(64) NOT NULL,
  legal_entity_id    uuid,
  shop_id            uuid,
  shop_label         varchar(150),
  frozen_at          timestamptz NOT NULL DEFAULT now(),
  frozen_by_membership_id uuid,
  note               varchar(300) NOT NULL DEFAULT '',
  UNIQUE(tenant_id, id),
  -- 一个单据只冻结一次：重复冻结必须显式失败（409）而不是覆盖，
  -- 覆盖等于让"当时按什么参数建的"这个问题重新变得没有唯一答案。
  UNIQUE(tenant_id, document_type, document_no)
);

CREATE TABLE business_parameter_freeze_value (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  freeze_id       uuid NOT NULL,
  parameter_code  varchar(64) NOT NULL REFERENCES business_parameter_definition(code) ON DELETE RESTRICT,
  value_text      text NOT NULL,
  value_numeric   numeric(19,6),
  -- 这份值是从哪一档解析出来的（码表默认值 / GLOBAL / LEGAL_ENTITY / SHOP），
  -- 排查时能直接看出"当时这个单据为什么是这个值"。
  source_scope    text NOT NULL CHECK(source_scope IN ('DEFAULT','GLOBAL','LEGAL_ENTITY','SHOP')),
  source_scope_id uuid,
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, freeze_id, parameter_code),
  FOREIGN KEY (tenant_id, freeze_id) REFERENCES business_parameter_freeze(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX business_parameter_freeze_document
  ON business_parameter_freeze(tenant_id, document_type, document_no);
CREATE INDEX business_parameter_freeze_value_freeze
  ON business_parameter_freeze_value(tenant_id, freeze_id, parameter_code);

-- ---------------------------------------------------------------------------
-- 5. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
-- business_parameter_definition 是全局码表，不带 tenant_id，按 V3 的先例
-- （page_resource / permission）不加 RLS：它是所有租户共读的同一份出厂定义。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'business_parameter_override',
    'business_parameter_history',
    'business_parameter_freeze',
    'business_parameter_freeze_value'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 6. 授权
-- ---------------------------------------------------------------------------
-- 码表只读：出厂定义随代码发布，应用侧改它就等于绕过版本库改配置。
GRANT SELECT ON business_parameter_definition TO identity_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON business_parameter_override TO identity_app;
-- 历史只追加：与 identity_audit 同样的授权口径。
GRANT SELECT,INSERT ON business_parameter_history TO identity_app;
GRANT SELECT,INSERT,DELETE ON business_parameter_freeze, business_parameter_freeze_value TO identity_app;

-- ---------------------------------------------------------------------------
-- 7. 出厂参数码表
-- ---------------------------------------------------------------------------
-- 对应蓝图职责点名的六类参数。\n 是刻意的字面换行分隔，避免用逗号
-- （逗号是 MULTI_ENUM 的取值分隔符）。
INSERT INTO business_parameter_definition
  (code,name,category,value_type,default_value,default_value_numeric,allowed_values,validation_pattern,unit,description,sort_order)
VALUES
  ('ORDER_REVIEW_POLICY','审单策略默认值','ORDER_REVIEW','ENUM','REVIEW_ALL',NULL,
   'REVIEW_ALL,PROXY_DECIDED,AUTO_APPROVE_LOW_RISK','','',
   '9 审单：新单进来先审还是直接放行。低风险免审只应给低风险场景，大额仍需人工审。',10),
  ('INVENTORY_POLICY','库存策略默认值','INVENTORY','ENUM','DISALLOW_NEGATIVE',NULL,
   'DISALLOW_NEGATIVE,ALLOW_NEGATIVE,BACKORDER_ONLY','','',
   '7 库存：可售库存不足时是否允许继续下单。允许负库存会掩盖超卖，须谨慎开启。',20),
  ('AFTERSALE_RETURN_WINDOW_DAYS','售后政策期限','AFTERSALE','INTEGER','30',30,
   '','^[0-9]{1,4}$', '天','11 售后：买家可发起退货申请的天数。改小只影响新单，历史单据仍按创建时冻结的期限。',30),
  ('AFTERSALE_MUST_REFUND_REASONS','售后必退情形','AFTERSALE','MULTI_ENUM',
   'DAMAGED_ON_ARRIVAL,MISSING_ITEM,WRONG_ITEM',NULL,
   'DAMAGED_ON_ARRIVAL,MISSING_ITEM,WRONG_ITEM,NOT_AS_DESCRIBED,QUALITY_DEFECT,DELIVERY_FAILED',
   '^[A-Z][A-Z0-9_]*(,[A-Z][A-Z0-9_]*)*$','',
   '11 售后：命中这些情形时必须退款，不接受商家拒绝。逗号分隔，代码取自下方合法取值。',40),
  ('COST_BASIS','成本口径默认值','COST','ENUM','MOVING_AVERAGE',NULL,
   'MOVING_AVERAGE,FIFO,LIFO,IMPORTED_COST','','',
   '成本核算口径，直接决定毛利算法。同一批货换口径会让前后毛利不可比，切换需谨慎。',50),
  ('DOCUMENT_NUMBER_PREFIX','编号前缀','NUMBERING','STRING','SC',NULL,
   '','^[A-Z0-9_-]{1,16}$','',
   '17.5.1.2 编号规则的上游：这里只定默认前缀，完整编号规则在编号规则模块维护。',60),
  ('DISPLAY_TIMEZONE','时区展示默认值','DISPLAY','STRING','Asia/Shanghai',NULL,
   '','','',
   '界面与报表默认时区。注意这与账簿的会计期间时区不是一回事，后者按 UTC 日历定义。',70),
  ('DISPLAY_CURRENCY','币种展示默认值','DISPLAY','STRING','CNY',NULL,
   '','^[A-Z]{3}$','',
   '展示默认币种。本位币归法人（17.1.2.2），这里只是列表展示的默认，不参与记账换算。',80);

-- ---------------------------------------------------------------------------
-- 8. 页面授权
-- ---------------------------------------------------------------------------
-- 与会计账簿同口径：按页面 CRUD 授权，组织变更沿用"仅 OWNER"纪律。
INSERT INTO page_resource(code,name) VALUES ('identity.business_parameter','业务参数')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.business_parameter.'||action,'identity.business_parameter',action
FROM unnest(ARRAY['read','create','update','delete']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.business_parameter.%' ON CONFLICT DO NOTHING;