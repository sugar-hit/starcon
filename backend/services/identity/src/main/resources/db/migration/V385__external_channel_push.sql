-- ===========================================================================
-- V385 · external_push_channel / external_push_delivery
--      —— 1.3.2.2「外部渠道推送」（卷 01 · L3 1.3.2 预警订阅 下的第二格）
-- ===========================================================================
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：把选定的通知推送到企业 IM（企业微信/钉钉/飞书）或邮件，支持卡片化消息与点击跳转。
--   **输入**：1.3.1.2 偏好；1.3.2.1 命中；外部渠道 Webhook 地址（存密钥管理器引用）。
--   **输出**：外部渠道投递记录（成功/失败/重试）；失败自动重试并告警。
--   **关系**：上游 = 1.3.1、1.3.2.1；下游 = 外部 IM。**凭据不落库**，只存 secret manager 引用
--            （见 18 集成系统）。
--
-- ===========================================================================
-- 一、为什么这两张表在 identity
-- ===========================================================================
--
-- 卷 01 §1.3「消息与预警」整域都在 identity：1.3.1.1 通知列表（V373）、
-- 1.3.1.2 通知偏好（user_preference，V336）、1.3.2.1 预警规则（V384）都在这里。
-- 外部渠道推送是**同一域的第四块**：它记的是「本租户把通知投到哪个外部通道、投了几次、
-- 成了没有」—— 这不是任何业务服务的事实（warehouse / trade / channel / finance 都不拥有
-- 「投没投出去」这个概念），落同一处、同一套 jOOQ 显式 SQL、同一套 RLS。
--
-- ===========================================================================
-- 二、🔴 凭据不落库：**只存 secret manager 引用**
-- ===========================================================================
--
-- 蓝图加粗句「**凭据不落库**，只存 secret manager 引用（见 18 集成系统）」在本迁移里
-- 落成**一个数据层 CHECK 约束**，而不是一句注释：
--
--   `secret_ref` 只接受 `secret://` / `vault://` / `external-secrets://` / `k8s://`
--   开头的**引用串**；
--   `https://…` 这类**Webhook 地址本体**被 CHECK 直接拒掉。
--
-- 为什么用 CHECK 而不是服务层校验：服务层校验挡得住「走 API 的人」，挡不住
-- 「直连 SQL 的人」。而凭据落库这件事**一旦发生就是不可逆的泄露** ——
-- Webhook 地址进了日志、进了备份、进了 binlog。所以这条纪律必须落在**数据层**：
-- 即使有人拿到库连接直接 insert，也写不进一个真实的 Webhook 地址。
--
-- ⚠️ 本仓**没有 secret manager 服务**（台账 18.1.1.1 应用与凭证未交付）⇒ 引用**解析不了**。
-- 本迁移**不假装能解析**：服务层如实报「secret manager 未接通」，投递如实失败，
-- 能力声明里如实标 available=false 并写清归 18 系统。
--
-- ===========================================================================
-- 三、external_push_channel：租户登记的外部渠道
-- ===========================================================================
--
-- 蓝图输入写的是「外部渠道 Webhook 地址（**存密钥管理器引用**）」⇒ 落成一张
-- **渠道登记表**：租户登记「我有一个企业微信机器人 / 一个钉钉群 / 一个飞书群 / 一个邮件通道」，
-- 登记的**不是地址**，而是地址在 secret manager 里的**引用**。
--
-- 渠道码是**受控词表**（蓝图点名的四个）：WECHAT_WORK / DINGTALK / FEISHU / EMAIL。
-- 一个租户每种渠道登记一条（UNIQUE(tenant_id, channel_code)）——
-- 「我在哪个群发」是租户自己的运营决定，但**同一渠道码登记两条**只会让投递记录
-- 的渠道归属说不清（"到底投给了哪个机器人"）。
--
-- `card_enabled` / `link_enabled` 是蓝图「支持卡片化消息与点击跳转」的落点：
-- 卡片化与可点击是**渠道属性**（有的通道只收纯文本、有的通道不渲染跳转链接），
-- 不是消息属性 —— 同一条通知在邮件里是卡片、在某个 IM 里可能只能是一行字。
--
-- ===========================================================================
-- 四、external_push_delivery：投递记录（成功 / 失败 / 重试 / 告警）
-- ===========================================================================
--
-- 蓝图输出：「外部渠道投递记录（成功/失败/重试）；失败自动重试并告警。」
--
-- 这张表是**投递尝试的事实**，不是模板维度的统计（那是 18.5.2.1，未交付）。
-- 每一次「准备投出去」落一行；后续重试**更新同一行**（attempt_count 递增），
-- 而不是每次重试插一行 —— 一条通知投 3 次是**一次投递的三次尝试**，
-- 记成三行会让「触达率」这类统计从一开始就错。
--
-- 状态是**四个受控码**，各自对应一种真实语义：
--
--   DELIVERED        —— 真的投出去了（delivered_at 必填、failure_code 必空）
--   FAILED           —— 这次没投出去，但**还有重试次数**（next_attempt_at 非空）
--   FAILED_EXHAUSTED —— 重试次数耗尽，**告警已升起**（next_attempt_at 必空）
--   DEFERRED         —— **没投**，因为落在收件人的免打扰时段里（next_attempt_at
--                       指向时段结束的那一刻；时段到了由同一条到期扫描去投）
--
-- 🔴 DEFERRED 与 FAILED 是**两件事**：「现在不该打扰他」和「投不出去」不是一回事，
-- 混成一种状态就既看不出该重试还是该等，也会在告警里误报。
--
-- **自动重试**（蓝图「失败自动重试」）：本服务**不装 @Scheduled**（与本仓既有纪律一致，
-- 见 10.4.2.2 巡检那条注释：多副本下自带定时器要做主节点选举与分布式锁，
-- 那是平台能力不是领域能力）⇒ **外部调度**（K8s CronJob）按间隔调一次
-- 「到期扫描」入口，天然单次、可重试、可审计。扫描口径是 `next_attempt_at <= now()`
-- （失败重试与免打扰到期**共用这一个字段**与这一条扫描）。
--
-- **告警**（蓝图「并告警」）：告警**落在本表自己的 `alert_state` / `alerted_at` /
-- `alert_message` 三列上**，不写别的模块的表（每张事实表只有一个写入所有者），
-- 也不假装能生成站内通知（通知内容是各域投影，1.3.1.1 明确「不作为业务事实来源」）。
-- 告警升起后可以被确认（acknowledge）—— 确认只改告警状态，**不改投递结果**：
-- 「我知道了」和「它投出去了」是两件事。
--
-- `channel_id` **不建外键**：渠道删了投递历史仍要留着（否则「上个月投过几次、
-- 成了几次」会随删渠道一起消失）。渠道码与渠道名在行内留**快照**，
-- 历史因此永远解释得清 —— 参照 V384 的 `alert_rule_hit.rule_id` 同一条纪律。
--
-- ===========================================================================
-- 五、多租户隔离（RLS + 触发器双防线）
-- ===========================================================================
--
-- 两表 `tenant_id NOT NULL` + ENABLE / **FORCE** ROW LEVEL SECURITY，
-- policy 只认 `app.current_tenant`（与 V373 / V384 同构）。
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务
-- 用 owner 身份一连就绕过了隔离。
--
-- 另加 **BEFORE INSERT 触发器兜底**：`app.current_tenant` 没设置 ⇒ 直接拒写；
-- `tenant_id` 与租户上下文不符 ⇒ 直接拒写（42501）。
--
-- 应用连接**不得**持 bypassrls（集成测试里有对应断言）。
--
-- ===========================================================================
-- 六、权限码登记
-- ===========================================================================
--
--   identity.external_push.read  —— 看渠道清单、投递记录、候选清单、能力声明
--   identity.external_push.write —— 登记 / 改 / 删渠道、发起推送、重试、确认告警
--
-- **必须登记**：role_permission.permission_code 有指向 permission(code) 的外键 ——
-- 没登记的码**没有任何角色可能持有**，端点在真实环境里恒 403
-- （V382 补 platform.finance.* 就是同一个坑的补丁）。
--
-- 与 V3 / V8 / V104 / V335 / V382 / V384 同纪律：存量 OWNER 角色也要补上这两个码，
-- 否则新接入的动作对既有租户不可见（新建子租户由 create_child 自动全授，不受影响）。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 一、外部渠道登记
-- ---------------------------------------------------------------------------
CREATE TABLE external_push_channel (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 渠道码（受控词表，蓝图标名的四个）：企业微信 / 钉钉 / 飞书 / 邮件。
  channel_code varchar(24) NOT NULL,

  -- 渠道显示名（租户自己起，例如「跨境运营群机器人」）。
  display_name varchar(100) NOT NULL,

  -- 🔴 **secret manager 引用**（不是凭据本体、不是 Webhook 地址）。
  --    CHECK 拒掉 https:// —— 那正是「凭据落库」的形态。
  secret_ref varchar(200) NOT NULL,

  -- 卡片化消息开关（蓝图「支持卡片化消息」）。
  card_enabled boolean NOT NULL DEFAULT true,
  -- 点击跳转开关（蓝图「点击跳转」）。卡片本身也由 card_enabled 控制。
  link_enabled boolean NOT NULL DEFAULT true,

  -- ENABLED 启用 / DISABLED 停用。
  status varchar(16) NOT NULL DEFAULT 'ENABLED',

  -- 维护人（租户内成员）。**不接受请求参数传入**，取自已认证会话。
  created_by uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一个租户每种渠道只登记一条（见本文件第三节）。
  UNIQUE (tenant_id, channel_code),

  CONSTRAINT external_push_channel_created_by_fk
    FOREIGN KEY (tenant_id, created_by) REFERENCES membership(tenant_id, id),

  CONSTRAINT external_push_channel_code_chk
    CHECK (channel_code IN ('WECHAT_WORK','DINGTALK','FEISHU','EMAIL')),

  CONSTRAINT external_push_channel_status_chk
    CHECK (status IN ('ENABLED','DISABLED')),

  CONSTRAINT external_push_channel_name_chk
    CHECK (btrim(display_name) <> '' AND length(display_name) <= 100),

  -- 🔴 **凭据不落库**（数据层钉死）：只接受 secret manager 引用串。
  --    `https://…` / `http://…` 这类真实地址**写不进来**。
  CONSTRAINT external_push_channel_secret_ref_chk
    CHECK (secret_ref ~ '^(secret|vault|external-secrets|k8s)://[A-Za-z0-9][A-Za-z0-9._/-]{0,180}$')
);

-- 「这个租户登记了哪些渠道、哪些是启用的」的主查询口径。
CREATE INDEX external_push_channel_by_code
  ON external_push_channel(tenant_id, channel_code);

-- ---------------------------------------------------------------------------
-- 二、投递记录
-- ---------------------------------------------------------------------------
CREATE TABLE external_push_delivery (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 目标渠道。**不建外键**：渠道删了历史仍要留着（见本文件第四节）。
  channel_id uuid NOT NULL,
  -- 渠道码与渠道名的**快照**：渠道后来改名 / 被删，历史仍解释得清。
  channel_code varchar(24) NOT NULL,
  channel_name varchar(100) NOT NULL,

  -- 收件人（租户内成员）。推送要按**这个人的**偏好过滤（1.3.1.2）。
  recipient_membership_id uuid NOT NULL,

  -- 来源：ALERT_HIT = 1.3.2.1 的规则命中；NOTIFICATION = 1.3.1 的通知投影。
  source_kind varchar(16) NOT NULL,
  -- 来源定位：命中 id（ALERT_HIT）或通知键（NOTIFICATION）。
  source_ref varchar(200) NOT NULL,

  -- 通知类型（**受控词表**，与 1.3.1.1 的五个码逐字同表）。
  -- 偏好过滤与记录都靠它；规则命中本身不带通知类型 ⇒ 由推送时选定。
  notification_type varchar(24) NOT NULL,
  -- 上游主键（1.3.1.1 的通知键后半段）。
  notification_ref varchar(120) NOT NULL,

  -- 消息快照（发出当时的内容）。模板管理（18.5.1.1）未交付 ⇒
  -- 没有「模板版本」这个概念，快照就是这次投递的真实内容。
  title varchar(200) NOT NULL,
  body varchar(1000),
  -- 点击跳转目标。**只允许站内相对路径**：绝对地址等于让一条通知
  -- 把收件人带到站外去，而那个地址没有任何审核环节。
  link_url varchar(500),
  -- CARD 卡片化消息 / TEXT 纯文本。
  card_style varchar(8) NOT NULL DEFAULT 'CARD',

  -- DELIVERED / FAILED / FAILED_EXHAUSTED / DEFERRED。
  status varchar(16) NOT NULL,
  -- 已尝试次数（每次尝试递增）。
  attempt_count integer NOT NULL DEFAULT 0,
  -- 最大尝试次数（含首次）。
  max_attempts integer NOT NULL DEFAULT 3,

  -- 失败码与失败说明（如实写清「为什么没投出去」）。
  failure_code varchar(48),
  failure_message varchar(500),

  -- 下次可再试的时刻。NULL = 不会再自动重试。
  -- 失败重试与免打扰到期**共用这一个字段**。
  next_attempt_at timestamptz,

  first_attempt_at timestamptz,
  last_attempt_at timestamptz,
  -- 真的投出去的时刻。
  delivered_at timestamptz,

  -- 告警状态：NONE / RAISED / ACKNOWLEDGED。
  alert_state varchar(16) NOT NULL DEFAULT 'NONE',
  alerted_at timestamptz,
  alert_message varchar(500),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),

  CONSTRAINT external_push_delivery_channel_code_chk
    CHECK (channel_code IN ('WECHAT_WORK','DINGTALK','FEISHU','EMAIL')),

  CONSTRAINT external_push_delivery_source_kind_chk
    CHECK (source_kind IN ('ALERT_HIT','NOTIFICATION')),

  -- 受控词表：通知类型（与 1.3.1.1 的五个码逐字同表）。
  CONSTRAINT external_push_delivery_notification_type_chk
    CHECK (notification_type IN (
      'STOCKOUT','TIMELINESS_OVERDUE','SYNC_FAILURE','RECONCILIATION_DIFF','MESSAGE'
    )),

  CONSTRAINT external_push_delivery_status_chk
    CHECK (status IN ('DELIVERED','FAILED','FAILED_EXHAUSTED','DEFERRED')),

  CONSTRAINT external_push_delivery_card_style_chk
    CHECK (card_style IN ('CARD','TEXT')),

  CONSTRAINT external_push_delivery_attempts_chk
    CHECK (attempt_count >= 0 AND max_attempts >= 1 AND attempt_count <= max_attempts),

  CONSTRAINT external_push_delivery_title_chk
    CHECK (btrim(title) <> '' AND length(title) <= 200),

  CONSTRAINT external_push_delivery_body_chk
    CHECK (body IS NULL OR length(body) <= 1000),

  -- 跳转目标只允许站内相对路径（不允许绝对地址、不允许空白）。
  CONSTRAINT external_push_delivery_link_url_chk
    CHECK (link_url IS NULL
      OR (link_url ~ '^/[^[:space:]]*$' AND link_url NOT LIKE '%://%' AND length(link_url) <= 500)),

  -- 成功必须有一个投出去的时刻，且不许同时挂着失败码。
  CONSTRAINT external_push_delivery_delivered_chk
    CHECK (status <> 'DELIVERED' OR (delivered_at IS NOT NULL AND failure_code IS NULL)),

  -- 失败必须写清失败码与说明（「没投出去」与「没记录」分不开）。
  CONSTRAINT external_push_delivery_failure_chk
    CHECK (status NOT IN ('FAILED','FAILED_EXHAUSTED')
      OR (failure_code IS NOT NULL AND failure_message IS NOT NULL AND btrim(failure_message) <> '')),

  -- 还有重试次数 ⇒ 必须排下一次；耗尽了 ⇒ 不许再排。
  CONSTRAINT external_push_delivery_next_attempt_chk
    CHECK (
      (status = 'FAILED' AND next_attempt_at IS NOT NULL)
      OR (status = 'DEFERRED' AND next_attempt_at IS NOT NULL)
      OR (status IN ('DELIVERED','FAILED_EXHAUSTED') AND next_attempt_at IS NULL)
    ),

  -- 告警状态与告警时刻必须自洽。
  CONSTRAINT external_push_delivery_alert_chk
    CHECK (
      (alert_state = 'NONE' AND alerted_at IS NULL)
      OR (alert_state IN ('RAISED','ACKNOWLEDGED') AND alerted_at IS NOT NULL)
    ),

  -- 重试耗尽 ⇒ 告警必须已升起（蓝图的「失败自动重试**并告警**」）。
  CONSTRAINT external_push_delivery_exhausted_alert_chk
    CHECK (status <> 'FAILED_EXHAUSTED' OR alert_state <> 'NONE')
);

-- 「按渠道看这个租户的投递流水」的主查询口径。
CREATE INDEX external_push_delivery_by_channel
  ON external_push_delivery(tenant_id, channel_id, created_at DESC);

-- 「到期扫描」的口径：只看还没投成功、且排了下一次的那些行。
CREATE INDEX external_push_delivery_by_due
  ON external_push_delivery(tenant_id, next_attempt_at)
  WHERE next_attempt_at IS NOT NULL;

-- 「按来源回查」（同一命中 / 同一条通知投过几次）。
CREATE INDEX external_push_delivery_by_source
  ON external_push_delivery(tenant_id, source_kind, source_ref);

-- 「某个人的外部投递流水」。
CREATE INDEX external_push_delivery_by_recipient
  ON external_push_delivery(tenant_id, recipient_membership_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 三、多租户隔离：RLS
-- ---------------------------------------------------------------------------
ALTER TABLE external_push_channel ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_push_channel FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON external_push_channel
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE external_push_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_push_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON external_push_delivery
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 四、BEFORE INSERT 触发器兜底（租户上下文）
-- ---------------------------------------------------------------------------
CREATE FUNCTION external_push_channel_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入外部渠道'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '外部渠道的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION external_push_channel_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION external_push_channel_tenant_guard() TO identity_app;

CREATE TRIGGER external_push_channel_tenant_guard_trigger
  BEFORE INSERT OR UPDATE ON external_push_channel
  FOR EACH ROW EXECUTE FUNCTION external_push_channel_tenant_guard();

-- 投递记录同一道兜底（重试只 UPDATE，不 INSERT，所以只挂在 INSERT 上）。
CREATE FUNCTION external_push_delivery_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入外部渠道投递记录'
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '外部渠道投递记录的 tenant_id 必须等于当前租户上下文'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION external_push_delivery_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION external_push_delivery_tenant_guard() TO identity_app;

CREATE TRIGGER external_push_delivery_tenant_guard_trigger
  BEFORE INSERT ON external_push_delivery
  FOR EACH ROW EXECUTE FUNCTION external_push_delivery_tenant_guard();

-- ---------------------------------------------------------------------------
-- 五、授权
-- ---------------------------------------------------------------------------
-- 运行角色是 identity_app：
--   · external_push_channel  —— 读（清单）+ 写（登记 / 改 / 删）。
--   · external_push_delivery —— 读（投递记录）+ 写（INSERT 新投递 / UPDATE 重试与告警状态）。
--     **不给 DELETE**：投递记录是「投过没有」的事实，删掉它等于让这件事没发生过
--     （与 V384 的 alert_rule_hit 同一条纪律）。
GRANT SELECT, INSERT, UPDATE, DELETE ON external_push_channel TO identity_app;
GRANT SELECT, INSERT, UPDATE ON external_push_delivery TO identity_app;

-- ---------------------------------------------------------------------------
-- 六、权限码登记
-- ---------------------------------------------------------------------------
INSERT INTO page_resource(code, name) VALUES ('identity.external_push', '外部渠道推送')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permission VALUES
  ('identity.external_push.read', 'identity.external_push', 'read'),
  ('identity.external_push.write', 'identity.external_push', 'write')
ON CONFLICT (code) DO NOTHING;

-- 存量 OWNER 补权（与 V3 / V8 / V104 / V335 / V382 / V384 同规则）：
-- 新建子租户由 create_child（V47）把 permission 表里的全部权限码授给 OWNER，
-- 所以新租户自动就有这两个码 —— 但**存量租户**的 OWNER 角色是在这两个码登记之前建的，
-- 不会自动获得它们 ⇒ 迁移上线后这些租户会突然「什么都点不动」。
INSERT INTO role_permission SELECT r.tenant_id, r.id, p.code FROM role r CROSS JOIN permission p
 WHERE r.code = 'OWNER'
   AND p.code IN ('identity.external_push.read', 'identity.external_push.write')
ON CONFLICT DO NOTHING;
