-- 17.5.2.1 数据字典。
--
-- 蓝图对本模块的三条要求，各自落到下面四张表 + 一张出厂目录上：
--
--   「维护业务字典：状态枚举、原因码、分类标签、单位类型等，
--     支持租户级自定义扩展与停用」
--     dict_catalog 是平台随代码发布的字典目录（全局码表，不带 tenant_id，
--     只授予 SELECT），对应职责里点名的几类字典；
--     dict_item 是租户私有的字典项，租户自己增删改 —— 这就是「租户级自定义扩展」。
--     目录不出租户、项才出租户，与 V48 业务参数「码表 + 租户档」是同一套分层做法。
--
--   「不允许修改已被历史数据引用的编码」
--     dict_item_update_guard() 触发器：只要这个字典项被 dict_item_usage 引用过，
--     item_code 就不许改（名称、标签、排序、备注都还能改）。
--     「被引用过」按**引用记录是否存在**判定，而不是按当前计数是否大于 0：
--     计数归零只说明当下没人用它，历史单据里还留着它，编码仍然不可改。
--
--   「字典项被引用后只能停用不能删除，否则历史单据会显示空白」
--     四道防线（沿用 17.2.2.3 离职模块的先例）：
--       1. 控制器不提供 DELETE 端点；
--       2. 迁移只授予 SELECT/INSERT/UPDATE，不授予 DELETE；
--       3. dict_item_usage.item_id 的外键是 ON DELETE RESTRICT；
--       4. dict_item_delete_guard() 触发器在数据库层挡掉一切 DELETE。
--     历史单据引用的是 item_code 这个字符串，删掉字典项不会让它报错，
--     只会让老单据的下拉位置渲染成空白——这种错误要等几年后翻旧账才会发现。
--
-- 引用统计来自 dict_item_usage 的真实聚合（left join + sum），不是任何写死的数字。
--
-- 边界（重要）：下游各业务系统的表在各自服务的库里，AGENTS.md 禁止跨服务数据库访问，
-- 所以本服务**不查询**它们的表，也建不了跨库外键。dict_item_usage 是引用台账：
-- 下游经授权 API 登记「我这边有 N 条记录用了这个字典项」，identity 只负责计数、聚合
-- 与据此判定「能不能删 / 能不能改编码」。计数归零或下游撤销登记都不会让台账行消失，
-- 因为"曾经被引用过"本身就是需要留痕的事实。

-- ---------------------------------------------------------------------------
-- 1. dict_catalog · 字典目录（全局，不带 tenant_id）
-- ---------------------------------------------------------------------------
-- 为什么不是租户表：这一张回答的是「平台定义了哪些业务字典」，与具体租户无关，
-- 和 identity 的 page_resource / permission、V48 的 business_parameter_definition
-- 是同一类全局码表。租户要加自己的东西，是往这些字典里加 dict_item，
-- 而不是往目录里塞私有条目——否则目录会变成每个租户一份、全表膨胀且无法共读。
CREATE TABLE dict_catalog (
  code        varchar(64) PRIMARY KEY,
  name        varchar(100) NOT NULL,
  -- 对应蓝图职责点名的四类：状态枚举 / 原因码 / 分类标签 / 单位类型。
  -- 「等」字留给后续目录条目：新增一类字典只是往这张表加一行，CHECK 跟着放宽即可。
  item_kind   text NOT NULL CHECK(item_kind IN ('STATUS_ENUM','REASON_CODE','CATEGORY_LABEL','UNIT_TYPE')),
  description varchar(300) NOT NULL DEFAULT '',
  -- 这类字典项被单据引用时，页面上必须提示「只能停用不能删除」。
  code_locked boolean NOT NULL DEFAULT true,
  sort_order  int NOT NULL DEFAULT 0,
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','RETIRED')),
  CHECK(btrim(name)<>'')
);

-- ---------------------------------------------------------------------------
-- 2. dict_item · 字典项（租户私有）
-- ---------------------------------------------------------------------------
CREATE TABLE dict_item (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  -- 所属字典目录。RESTRICT：目录还在被字典项引用时不能从目录里删掉，
  -- 否则这些字典项就成了没有归属的孤儿。
  dict_code   varchar(64) NOT NULL REFERENCES dict_catalog(code) ON DELETE RESTRICT,
  -- 字典内唯一的编码，单据里存的就是这个字符串。
  -- 「不允许修改已被历史数据引用的编码」由 dict_item_update_guard() 钉死。
  item_code   varchar(64) NOT NULL,
  -- 可改的部分：名称与标签随时可以改，历史单据显示的字典项含义不能因此变样。
  name        varchar(100) NOT NULL,
  description varchar(300) NOT NULL DEFAULT '',
  sort_order  int NOT NULL DEFAULT 0,
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  version     bigint NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  created_by_membership_id uuid,
  -- 停用留痕：谁在什么时候停用的，停用是为了什么。
  disabled_at timestamptz,
  disabled_by_membership_id uuid,
  disabled_reason varchar(200) NOT NULL DEFAULT '',
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, dict_code, item_code),
  CHECK(btrim(item_code)<>'' AND btrim(name)<>''),
  -- 停用必须带原因与时刻，否则事后无法解释「为什么这个字典项不能选了」。
  CHECK((status='ACTIVE') = (disabled_at IS NULL))
);

-- 字典项列表的主查询：按字典 + 状态 + 排序读。
CREATE INDEX dict_item_list ON dict_item(tenant_id, dict_code, status, sort_order);
-- 停用项的单独索引：下拉选项默认只取 ACTIVE，这条路径每次请求都会走。
CREATE INDEX dict_item_active ON dict_item(tenant_id, dict_code, sort_order) WHERE status='ACTIVE';
-- 按名称搜（配置页搜索框）。
CREATE INDEX dict_item_search ON dict_item(tenant_id, dict_code, lower(name));

-- ---------------------------------------------------------------------------
-- 3. dict_item_usage · 引用台账（引用统计的数据来源）
-- ---------------------------------------------------------------------------
-- 一个「字典项 × 来源系统」一行，登记该来源系统当前有多少条记录用了这个字典项。
-- 引用统计是对这张表做 sum/count 聚合得到的，不是存出来的常数。
CREATE TABLE dict_item_usage (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL REFERENCES tenant(id),
  -- RESTRICT 而不是 CASCADE：被引用的字典项删不掉，这正是蓝图要的那条硬约束。
  item_id        uuid NOT NULL,
  -- 登记方服务（trade / catalog / channel / ...），与 V46 离职台账同一套跨服务边界做法。
  source_system  varchar(32) NOT NULL CHECK(source_system ~ '^[a-z_]{2,32}$'),
  -- 该来源系统当前引用条数。允许为 0：0 表示「曾经用过、现在不用了」，
  -- 但它仍然证明历史数据引用过这个编码，所以 dict_item 的编码依然锁死。
  usage_count    bigint NOT NULL DEFAULT 0 CHECK(usage_count>=0),
  first_referenced_at timestamptz NOT NULL DEFAULT now(),
  last_referenced_at  timestamptz NOT NULL DEFAULT now(),
  note           varchar(200) NOT NULL DEFAULT '',
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, item_id, source_system),
  FOREIGN KEY (tenant_id, item_id) REFERENCES dict_item(tenant_id, id) ON DELETE RESTRICT
);

-- 引用统计的聚合入口：按字典项汇总。
CREATE INDEX dict_item_usage_item ON dict_item_usage(tenant_id, item_id);
-- 反查「哪个来源系统在用这个字典」，供下游对接时定位责任方。
CREATE INDEX dict_item_usage_source ON dict_item_usage(tenant_id, source_system);

-- ---------------------------------------------------------------------------
-- 4. dict_history · 变更历史（追加，不可改）
-- ---------------------------------------------------------------------------
-- 与 V48 business_parameter_history、17.5.3.1 审计同一纪律：
-- 应用层「不写 UPDATE」不是保证，拿到连接就能直接改。这里用触发器在数据库层挡住
-- UPDATE/DELETE，权限层再 REVOKE 一次，双重防线。
CREATE TABLE dict_history (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL REFERENCES tenant(id),
  dict_code           varchar(64) NOT NULL,
  item_id             uuid NOT NULL,
  item_code           varchar(100) NOT NULL,
  item_name           varchar(100) NOT NULL,
  action              text NOT NULL
                      CHECK(action IN ('CREATED','UPDATED','DEACTIVATED','REACTIVATED')),
  -- 变更前后值。停用/恢复记录状态与原因，其余字段为空。
  before_name         varchar(100),
  before_description  varchar(300),
  after_name          varchar(100),
  after_description   varchar(300),
  before_status       text,
  after_status        text,
  reason              varchar(200) NOT NULL DEFAULT '',
  actor_membership_id uuid,
  actor_name          varchar(100),
  -- changed_at 是系统时间，用 OffsetDateTime 绑定写入（见 AuditLog.utc 的说明）。
  changed_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

CREATE INDEX dict_history_item ON dict_history(tenant_id, item_id, changed_at DESC);
CREATE INDEX dict_history_dict ON dict_history(tenant_id, dict_code, changed_at DESC);
CREATE INDEX dict_history_time ON dict_history(tenant_id, changed_at DESC);

CREATE FUNCTION dict_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '数据字典变更历史不可修改或删除（17.5.2.1 变更留痕）' USING ERRCODE='42501';
END $$;

CREATE TRIGGER dict_history_immutable_guard
  BEFORE UPDATE OR DELETE ON dict_history
  FOR EACH ROW EXECUTE FUNCTION dict_history_immutable();

REVOKE UPDATE, DELETE ON dict_history FROM identity_app;

-- ---------------------------------------------------------------------------
-- 5. 字典项的两条硬约束触发器
-- ---------------------------------------------------------------------------
-- 防线四：数据库层挡住一切物理删除。
-- 应用侧拿不到 DELETE 权限，但迁移用户 / 表 owner / 将来某个新连接仍然能删，
-- 所以这里再钉一层，把「被引用后只能停用」变成不依赖调用方自觉的物理事实。
CREATE FUNCTION dict_item_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  used bigint;
BEGIN
  SELECT count(*) INTO used FROM dict_item_usage WHERE item_id=OLD.id;
  IF used>0 THEN
    RAISE EXCEPTION '字典项「%」已被下游引用（% 条来源系统登记），只能停用不能删除，否则历史单据会显示空白', OLD.item_code, used
      USING ERRCODE='42501';
  END IF;
  RAISE EXCEPTION '字典项不提供物理删除，请改为停用（停用后不再出现在新单据的下拉里，历史单据照常显示）'
    USING ERRCODE='42501';
END $$;

CREATE TRIGGER dict_item_delete_guard_trigger
  BEFORE DELETE ON dict_item
  FOR EACH ROW EXECUTE FUNCTION dict_item_delete_guard();

-- 「不允许修改已被历史数据引用的编码」。
-- 只锁 item_code：名称、说明、排序、停用原因都还能改——业务含义变了要能改，
-- 承载历史数据的那个字符串不能改。
CREATE FUNCTION dict_item_update_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  used bigint;
BEGIN
  IF NEW.item_code IS DISTINCT FROM OLD.item_code THEN
    SELECT count(*) INTO used FROM dict_item_usage WHERE item_id=OLD.id;
    IF used>0 THEN
      RAISE EXCEPTION '字典项「%」已被历史数据引用，不能修改编码（名称、说明仍可修改）', OLD.item_code
        USING ERRCODE='42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER dict_item_update_guard_trigger
  BEFORE UPDATE ON dict_item
  FOR EACH ROW EXECUTE FUNCTION dict_item_update_guard();

-- ---------------------------------------------------------------------------
-- 6. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
-- dict_catalog 是全局码表，不带 tenant_id，按 V48 的先例不加 RLS：
-- 它是所有租户共读的同一份出厂目录。真正必须隔离的是 dict_item 及其从属台账。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'dict_item',
    'dict_item_usage',
    'dict_history'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 7. 授权
-- ---------------------------------------------------------------------------
-- 防线二：dict_item 只授予 SELECT/INSERT/UPDATE，不授予 DELETE。
-- 目录只读：出厂目录随代码发布，应用侧改它等于绕过版本库改配置。
GRANT SELECT ON dict_catalog TO identity_app;
GRANT SELECT,INSERT,UPDATE ON dict_item TO identity_app;
-- 台账由下游经授权 API 登记与更新（不授予 DELETE：引用登记本身也是历史事实）。
GRANT SELECT,INSERT,UPDATE ON dict_item_usage TO identity_app;
-- 历史只追加：与 business_parameter_history 同样的授权口径。
GRANT SELECT,INSERT ON dict_history TO identity_app;

-- ---------------------------------------------------------------------------
-- 8. 出厂字典目录
-- ---------------------------------------------------------------------------
-- 对应蓝图职责点名的四类字典。code_locked=true 表示这类字典项被引用后编码锁死——
-- 全部为 true：状态枚举与原因码都会被历史单据引用，分类标签与单位类型同样会。
INSERT INTO dict_catalog(code,name,item_kind,description,sort_order) VALUES
  ('ORDER_STATUS','订单状态','STATUS_ENUM',
   '订单全生命周期状态。下游 = 9 审单、全部单据创建；被历史单据引用后只能停用，不能删也不能改编码。',10),
  ('SHELF_STATUS','上架状态','STATUS_ENUM',
   'listing 在各平台的上架状态。跨平台枚举不同，本字典是内部统一口径。',20),
  ('ORDER_CANCEL_REASON','订单取消原因码','REASON_CODE',
   '11 售后与订单侧的取消原因。取消原因会被财务与仓储按码统计，改编码会让前后报表不可比。',30),
  ('AFTERSALE_REASON','售后原因码','REASON_CODE',
   '11 售后：买家发起售后的原因。退货、退款、换货均从这里取值。',40),
  ('INVENTORY_ADJUST_REASON','库存调整原因码','REASON_CODE',
   '7 库存：盘点差异、破损、报损、盘盈盘亏的原因。盘点报告按原因码归类，编码一旦被引用即不可改。',50),
  ('PRODUCT_CATEGORY','商品分类标签','CATEGORY_LABEL',
   '跨渠道通用的商品分类标签。各渠道自己的类目树不在这里，这里存的是内部统一标签。',60),
  ('PRODUCT_TAG','商品标签','CATEGORY_LABEL',
   '运营自定义的分类标签，用于选品与投放分组。租户可自由扩展。',70),
  ('UNIT_TYPE','计量单位类型','UNIT_TYPE',
   '按单位类型分组（重量/体积/长度/计数/包装），决定换算口径与精度。金额与数量禁止浮点，口径写在这里。',80);

-- ---------------------------------------------------------------------------
-- 9. 页面授权
-- ---------------------------------------------------------------------------
-- 与业务参数同口径：按页面授权，组织变更沿用「仅 OWNER」纪律。
-- 刻意不创建 delete 权限：字典项没有删除端点，给一个永远用不上的权限码
-- 只会让人以为可以删。
INSERT INTO page_resource(code,name) VALUES ('identity.dictionary','数据字典')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.dictionary.'||action,'identity.dictionary',action
FROM unnest(ARRAY['read','create','update']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.dictionary.%' ON CONFLICT DO NOTHING;