-- 15.1.1.1 消息收件箱：集中展示各店铺的平台站内信与买家消息。
--
-- 蓝图 15 卷 15.1.1.1 的原话：
--   「职责：集中展示各店铺的平台站内信与买家消息：店铺、买家、主题、正文、附件、平台时间、
--     收发方向、已读状态、关联订单，支持按店铺/类型/优先级/未读筛选与批量处理。
--     输入：平台消息同步（webhook + 拉取）；用户筛选条件；数据范围（店铺维度）。
--     输出：消息列表 + 详情视图 + 关联订单上下文。
--     关系：上游 = 2 渠道与店铺（同步）；下游 = 15.1.1.2 回复、11 售后（转售后）、15.4 工单。
--     **消息必须关联到买家与订单**，否则客服看到消息还要去猜是哪一单。」
--
-- ============================================================ 零、与既有模块的边界
--
-- 本文件是 **crm 数据库的第四个迁移**（前三个：V99 知识库 / V220 索评 / V228 索评规则）。
-- 本模块与它们**不共用任何一张表**：
--   * 知识库管**内容**（这个格子此刻该给买家答哪一条）；
--   * 索评管**触达**（要不要向这位已签收的买家发起一次索评）；
--   * 本模块管**收到的消息**（买家/平台说了什么，关联到哪一单，处理到哪一步了）。
-- 三者的写入所有者、状态机与生命周期都不同。
--
-- ⚠️ 与 **15.1.1.2 消息发送**（未交付）、**15.1.1.3 消息分类**（未交付）刻意分开：
-- 本模块是**收件箱**（读 + 处理已读/优先级/归档 + 登记意图），
-- 不提供发送、不提供自动分类。
--
-- ============================================================ 一、🔴 加粗句的落地：消息必须关联买家与订单
--
-- 「**消息必须关联到买家与订单**，否则客服看到消息还要去猜是哪一单」在本文件里落成
-- **数据库层的 NOT NULL + CHECK**，不是界面上的两个输入框：
--
--   buyer_reference  NOT NULL CHECK (btrim(...) <> '')   —— 买家标识
--   order_reference  NOT NULL CHECK (btrim(...) <> '')   —— 订单标识
--
-- 也就是说：**一条没有买家、没有订单的消息在本库里根本存不进去**。
-- 「留空以后再补」在客服场景里等于「永远补不上」—— 补不上的那一条永远没人知道是哪一单，
-- 于是客服就得回头去平台翻一遍，这正是加粗句要避免的事。
--
-- 订单的**上下文**（平台 / 金额 / 状态 / 收货国家等）不在本库里冗余存一份：
-- 那是 **trade 服务**的事实（9.1.1.1 全渠道统一订单列表），
-- 本服务只经**授权 HTTP API**只读补全（跨服务纪律见第二节）。
-- 查不到时页面必须显示「订单上下文不可用 + 原因」，**不显示空白** ——
-- 空白会被客服读成「这单没有信息」，而不是「我们没查到」。
--
-- ============================================================ 二、跨服务纪律（零跨库 SQL / FK / 视图）
--
-- 上游两样事实都**不在本服务**：
--   * **店铺目录** → channel 服务（2.3.1.1，`GET /channel/shops`，端口 8083）
--   * **订单上下文** → trade 服务（9.1.1.1，`GET /trade/sales-orders/list`，端口 8085）
-- AGENTS.md 模块边界纪律：禁止跨服务 SQL / 跨库 FK / 共享视图，只走授权 HTTP API。
--
-- ⇒ 本库**只存上游的逻辑引用 + 名称快照**：
--   shop_id / shop_name（快照）/ platform_code
--   buyer_reference / order_reference（**逻辑引用**，不是跨库外键）
-- 快照的作用是**列表页不必回源就能显示**；它**不构成引用完整性**，
-- 真正的完整性归各服务自己的域。
--
-- 本文件**不给任何既有表建外键**，自己新建的三张表之间也**一律用触发器做引用校验**
-- （与同批 V220 第一节末的同款手法：同批 batch-37 有一条车道在迁移里给既有表建了
-- FOREIGN KEY，结果打破了同服务既有测试的 TRUNCATE —— PG: cannot truncate a table
-- referenced in a foreign key constraint）。
--
-- ============================================================ 三、三张表：消息本体 / 附件 / 处置意图
--
--   crm_message           · 消息本体（蓝图点名的每一项：店铺、买家、主题、正文、平台时间、
--                           收发方向、已读状态、优先级、关联订单）
--   crm_message_attachment· 附件明细（文件名 / 链接 / 类型 / 大小）
--   crm_message_intent    · **处置意图记录**（转售后 / 建工单）
--
-- 为什么附件独立成表而不是一列 text：蓝图把「附件」与「正文」并列，
-- 而附件是**有结构、可点开、有大小**的东西；塞进一列 text 之后
-- 「这份消息带了几张图、哪几张、总共多大」就只能靠字符串解析。
-- 附件数（attachment_count）在消息行上**冗余**一份，是为了让列表页**不 join** 就能显示
-- 「带附件」徽标；它由 **DB 触发器**维护（sync_crm_message_attachment_count），
-- 不靠应用层自觉 —— 两边各写各的迟早会漂。
--
-- 为什么「处置意图」独立成表：下游 **11 售后（转售后）与 15.4 工单在本车道基线里
-- 都没有可调的授权 API**。所以「转售后 / 建工单」这个动作**在本服务内只留意图**：
-- 记下「这条消息应该转售后 / 应该开工单」+ 原因 + 谁登记的。
-- ⚠️ **本表刻意不存下游单据 id**：下游没交付就没有 id，
-- 编一个 id 填进去会让「已转售后」在数据上看起来发生过，而售后单**根本不存在**。
-- 意图的 status 只有 RECORDED / CANCELLED 两档 —— 没有 HANDED_OFF，
-- 因为「已交接」这个事实要有下游系统来证明，本服务证明不了。
--
-- ============================================================ 四、平台消息同步：未接入，按未接入处理
--
-- 蓝图输入句里的「平台消息同步（webhook + 拉取）」依赖 **channel 服务的买家站内信适配器**，
-- 而 channel/vendor 下**只有 OAuth 适配器**（13 个），**没有**买家站内信适配器。
--
-- ⇒ 本文件给 source 留了两个词表位：
--   MANUAL_ENTRY  人工登记（**当前唯一可用**）
--   PLATFORM_SYNC 平台同步（**未接入**，服务端拒绝写这一档）
-- 一个「返回成功回执」的假同步通道会让「平台消息已经同步进来了」在数据上看起来发生过，
-- 而现场一条真实消息都还没有 —— 那比明确报「未接入」糟得多。
--
-- ============================================================ 五、时点、数量与金额
--
--   * 时刻一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     （用 Timestamp.valueOf(LocalDateTime) 会在东八区静默偏 −28800 秒，本仓反复踩过。）
--   * 平台时间（platform_time）是**买家/平台侧的时刻**，不是本系统登记时刻；
--     登记时刻另存 created_at。
--   * 附件大小用 **bigint**（字节数），其余数量用 integer。
--   * 金额不落在本表（消息本身没有金额）；订单金额由 trade 侧以 numeric 提供，
--     前端**原样展示**，不做任何 JS 侧算术。
--
-- ============================================================ 六、发布后不可变的强制位置
--
--   1. NOT NULL + CHECK：buyer_reference / order_reference 不得为空串
--      （加粗句的 DB 层兜底）。
--   2. CHECK 约束：read_status='READ' 必须有 read_at；
--      direction / message_type / priority / read_status / source 都在词表内。
--   3. 触发器：
--      guard_crm_message_identity（BEFORE UPDATE，身份列建后不可改）
--      guard_crm_message_attachment_target（BEFORE INSERT，附件必须挂在存在的消息上）
--      sync_crm_message_attachment_count（AFTER INSERT/DELETE，维护附件计数）
--      guard_crm_message_intent_target（BEFORE INSERT，意图必须挂在存在的消息上）
--
-- 「身份列不可改」的理由：改了就会出现「同一条平台消息在库里有两个买家 / 两个订单 /
-- 两个平台时刻」的历史 —— 而这条消息**已经被人读过、标记过、转过售后**。
-- 可改的只有处理态列（已读状态 / 优先级 / 附件 / 意图），那正是客服每天要动的东西。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 同一口径：角色可能已由运维预置，因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- ============================================================================
-- 1. crm_message · 消息本体
-- ============================================================================
-- 消息号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一条消息就报权限不足」。
CREATE SEQUENCE crm_message_no_seq START 1;

CREATE TABLE crm_message (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- MSG-yyyyMMdd-000001
  message_no varchar(32) NOT NULL DEFAULT '',

  -- ===== 店铺（来自 channel 的真实清单；只存逻辑 id + 名称快照）=====
  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL CHECK (platform_code ~ '^[A-Z][A-Z0-9_]*$'),

  -- ===== 🔴 买家与订单：蓝图加粗句的 DB 层兜底 =====
  -- 买家标识（平台买家号等）。本仓**没有买家档案**（15.3.1.1 未交付），
  -- 所以它不是主数据，只是一条**逻辑引用**，用于把消息归到人。
  buyer_reference varchar(128) NOT NULL CHECK (btrim(buyer_reference) <> ''),
  -- 订单标识（平台订单号或本仓销售单号）。**逻辑引用**，不建跨库外键。
  order_reference varchar(64) NOT NULL CHECK (btrim(order_reference) <> ''),

  -- ===== 消息本体 =====
  subject varchar(400) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  -- 收发方向：INBOUND 收到的（平台站内信 / 买家消息）· OUTBOUND 发出的。
  direction varchar(16) NOT NULL CHECK (direction IN ('INBOUND','OUTBOUND')),
  -- 消息类型：PLATFORM_INBOX 平台站内信 · BUYER_MESSAGE 买家消息 ·
  -- SYSTEM_NOTICE 平台通知 · COMPLIANCE 平台合规通知。
  message_type varchar(24) NOT NULL
    CHECK (message_type IN ('PLATFORM_INBOX','BUYER_MESSAGE','SYSTEM_NOTICE','COMPLIANCE')),
  -- 优先级：LOW / NORMAL / HIGH / URGENT。
  priority varchar(16) NOT NULL DEFAULT 'NORMAL'
    CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
  -- 已读状态：UNREAD 未读 · READ 已读 · ARCHIVED 已归档。
  read_status varchar(16) NOT NULL DEFAULT 'UNREAD'
    CHECK (read_status IN ('UNREAD','READ','ARCHIVED')),
  read_at timestamptz,
  read_by uuid,
  -- 已读必须有已读时刻：否则「这条什么时候被谁看的」永远查不出来。
  CHECK (read_status <> 'READ' OR read_at IS NOT NULL),

  -- 平台时间（买家/平台侧的时刻，**不是**本系统登记时刻）。
  platform_time timestamptz NOT NULL,

  -- 附件计数：与 crm_message_attachment 一致，由触发器维护。
  attachment_count integer NOT NULL DEFAULT 0 CHECK (attachment_count >= 0),

  -- 来源：MANUAL_ENTRY 人工登记（**当前唯一可用**）；
  -- PLATFORM_SYNC 平台同步（**未接入**，服务端拒绝写这一档）。
  source varchar(24) NOT NULL DEFAULT 'MANUAL_ENTRY'
    CHECK (source IN ('MANUAL_ENTRY','PLATFORM_SYNC')),
  -- 平台侧消息 id（同步幂等用；人工登记为空串）。
  external_message_id varchar(128) NOT NULL DEFAULT '',

  -- 登记人（已认证成员，由服务端从 AccessService.Access 取，不接受客户端传值）。
  created_by uuid,
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, message_no)
);

-- 列表主筛选：按店铺 + 平台时间倒序。
CREATE INDEX crm_message_by_shop
  ON crm_message(tenant_id, shop_id, platform_time DESC);
-- 未读筛选（蓝图点名的「未读」）。
CREATE INDEX crm_message_by_read
  ON crm_message(tenant_id, read_status, platform_time DESC);
-- 优先级筛选。
CREATE INDEX crm_message_by_priority
  ON crm_message(tenant_id, priority, platform_time DESC);
-- 类型筛选。
CREATE INDEX crm_message_by_type
  ON crm_message(tenant_id, message_type, platform_time DESC);
-- 订单维度筛选（客服最常用的一句：「这一单都说了什么」）。
CREATE INDEX crm_message_by_order
  ON crm_message(tenant_id, order_reference, platform_time DESC);
-- 买家维度筛选。
CREATE INDEX crm_message_by_buyer
  ON crm_message(tenant_id, buyer_reference, platform_time DESC);
-- 平台时间范围筛选。
CREATE INDEX crm_message_by_platform_time
  ON crm_message(tenant_id, platform_time DESC);
-- 同步幂等：同一店铺下同一条平台消息只入库一次。
-- ⚠️ 用**部分唯一索引**而不是表级 UNIQUE：external_message_id 人工登记时是空串，
-- 表级 UNIQUE 会让「同一家店的第二条人工登记消息」直接撞唯一键。
CREATE UNIQUE INDEX crm_message_one_per_external
  ON crm_message(tenant_id, shop_id, external_message_id)
  WHERE external_message_id <> '';

-- ============================================================================
-- 2. crm_message_attachment · 附件明细
-- ============================================================================
CREATE TABLE crm_message_attachment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 挂在哪条消息上。**用触发器做引用校验**，不建数据库外键（理由见文件头第二节）。
  message_id uuid NOT NULL,

  file_name varchar(300) NOT NULL CHECK (btrim(file_name) <> ''),
  -- 附件链接（对象存储地址等）。空串表示「只登记了文件名，文件还没落地」。
  file_url varchar(1000) NOT NULL DEFAULT '',
  content_type varchar(120) NOT NULL DEFAULT '',
  -- 字节数。0 表示未知（不是「0 字节的文件」—— 两者在这里分不开，索性只表示未登记）。
  -- ⚠️ 用 **numeric(19,6)** 而非 bigint：与本仓其它字节/数量列同精度（AGENTS.md 禁浮点，
  -- 见 V239 supplier_qualification / V35 product_export 的同款口径）。
  size_bytes numeric(19,6) NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 按消息读附件。
CREATE INDEX crm_message_attachment_by_message
  ON crm_message_attachment(tenant_id, message_id, created_at);

-- ============================================================================
-- 3. crm_message_intent · 处置意图（转售后 / 建工单）
-- ============================================================================
-- ⚠️ 下游 11 售后（转售后）与 15.4 工单**未交付** ⇒ 本表只是**意图**，
-- **不存下游单据 id**：没有下游就没有 id，编一个会让「已转售后」看起来发生过。
CREATE TABLE crm_message_intent (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 挂在哪条消息上。**用触发器做引用校验**，不建数据库外键。
  message_id uuid NOT NULL,

  -- AFTERSALE_CASE 转售后（11 售后未交付）· SERVICE_TICKET 建工单（15.4 未交付）。
  intent_type varchar(24) NOT NULL CHECK (intent_type IN ('AFTERSALE_CASE','SERVICE_TICKET')),
  -- RECORDED 已登记 · CANCELLED 已撤销。
  -- ⚠️ 刻意**没有** HANDED_OFF：没有下游系统就没有「已交接」这个事实的证明方。
  status varchar(16) NOT NULL DEFAULT 'RECORDED'
    CHECK (status IN ('RECORDED','CANCELLED')),
  -- 为什么转售后 / 为什么开工单（必填：没有理由的意图没法复核）。
  reason varchar(600) NOT NULL CHECK (btrim(reason) <> ''),

  -- 登记人（已认证成员，服务端写入）。
  requested_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 按消息读意图。
CREATE INDEX crm_message_intent_by_message
  ON crm_message_intent(tenant_id, message_id, created_at DESC);
-- 同一条消息的同一种意图**同时只允许一条有效**（撤销后可以再登记）。
CREATE UNIQUE INDEX crm_message_intent_one_active
  ON crm_message_intent(tenant_id, message_id, intent_type)
  WHERE status = 'RECORDED';

-- ============================================================================
-- 4. 触发器
-- ============================================================================
-- ⚠️ 为什么用**同库回表**而不是**数据库外键**：
--   本批 batch-37 有一条车道在迁移里给既有表建了 FOREIGN KEY，结果打破了同服务
--   既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）。本文件**不给任何既有表建外键**，自己新建的三张表之间
--   也一律用触发器做引用校验。

-- 4.1 身份列建后不可改（店铺 / 平台 / 买家 / 订单 / 平台时间 / 类型 / 方向 / 来源 / 平台消息号）。
CREATE FUNCTION guard_crm_message_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.shop_id IS DISTINCT FROM OLD.shop_id
     OR NEW.platform_code IS DISTINCT FROM OLD.platform_code
     OR NEW.buyer_reference IS DISTINCT FROM OLD.buyer_reference
     OR NEW.order_reference IS DISTINCT FROM OLD.order_reference
     OR NEW.platform_time IS DISTINCT FROM OLD.platform_time
     OR NEW.message_type IS DISTINCT FROM OLD.message_type
     OR NEW.direction IS DISTINCT FROM OLD.direction
     OR NEW.source IS DISTINCT FROM OLD.source
     OR NEW.external_message_id IS DISTINCT FROM OLD.external_message_id THEN
    RAISE EXCEPTION '消息的身份列（店铺/平台/买家/订单/平台时间/类型/方向/来源/平台消息号）建后不可改：改了就出现「同一条消息有两个买家/两个订单」的历史';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_identity_trg
  BEFORE UPDATE ON crm_message
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_identity();

-- 4.2 附件必须挂在同一租户下真实存在的消息上。
CREATE FUNCTION guard_crm_message_attachment_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_message m
      WHERE m.id = NEW.message_id AND m.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '附件必须挂在同一租户下真实存在的消息上（message_id=%）', NEW.message_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_attachment_target_trg
  BEFORE INSERT ON crm_message_attachment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_attachment_target();

-- 4.3 附件计数由 DB 维护（消息行上的冗余计数只此一处来源）。
CREATE FUNCTION sync_crm_message_attachment_count() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE crm_message
       SET attachment_count = attachment_count + 1, updated_at = now()
     WHERE id = NEW.message_id AND tenant_id = NEW.tenant_id;
    RETURN NEW;
  END IF;
  UPDATE crm_message
     SET attachment_count = GREATEST(attachment_count - 1, 0), updated_at = now()
   WHERE id = OLD.message_id AND tenant_id = OLD.tenant_id;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_message_attachment_count_trg
  AFTER INSERT OR DELETE ON crm_message_attachment
  FOR EACH ROW EXECUTE FUNCTION sync_crm_message_attachment_count();

-- 4.4 意图必须挂在同一租户下真实存在的消息上。
CREATE FUNCTION guard_crm_message_intent_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_message m
      WHERE m.id = NEW.message_id AND m.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '处置意图必须挂在同一租户下真实存在的消息上（message_id=%）', NEW.message_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_intent_target_trg
  BEFORE INSERT ON crm_message_intent
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_intent_target();

-- ============================================================================
-- 5. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_message','crm_message_attachment','crm_message_intent'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_message, crm_message_attachment, crm_message_intent TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_message, crm_message_attachment, crm_message_intent TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条消息就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_message_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_message_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 6. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **平台买家站内信适配器**（channel/vendor 下**只有 OAuth 适配器**，没有站内信）：
--     同步端点逐平台答「未接入」并**不写任何数据**；消息靠人工登记进来。
--   * **15.1.1.2 消息发送**：未交付。本模块是**收件箱**，不提供发送。
--   * **15.1.1.3 消息分类**：未交付。本模块的优先级由**人工**设置，不做自动分类。
--   * **11 售后（转售后）/ 15.4 工单**：未交付。本模块只落**处置意图**，
--     **不写下游单据、不编下游 id**。
