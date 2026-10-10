-- 17.4.1.2 审批规则（触发规则 + 免审规则）。
--
-- 蓝图原文对本模块的界定：
--   「配置审批触发规则：金额区间、跨法人、跨部门、新供应商、高风险商品等条件触发不同流程，
--     支持免审配置（小额免审）。」
--   「输入：触发条件配置；免审规则。输出：规则集；生效策略。」
--   「**免审要设上限金额**，否则"小额免审"会被用来绕过大额管控。」
--
-- 与上游 17.4.1.1 的分工要写死，否则两张表会被当成同一件事的两种写法：
--   · 17.4.1.1 回答「这张单据**要不要有人审、谁审**」——按单据类型注册流程、节点、金额阈值分支、超时升级。
--   · 17.4.1.2（本模块）回答「这次提交**会不会触发审批**」——触发条件与免审规则，以及一条都没命中时的生效策略。
-- 所以本模块**不重复定义流程**：触发规则只能指向 17.4.1.1 已存在的生效流程
-- （外键 + 写入前查一次），不在本模块里另造一套节点或金额分支。
--
-- 三张表的关系：approval_rule_set（规则集 + 生效策略）1 → N approval_trigger_rule（有序触发/免审规则）。
--
-- 条件维度为什么用三态（ANY / REQUIRED / FORBIDDEN）而不是布尔：
--   布尔只有一个 false，却要表达三件不同的事——「必须命中」「必须不命中」「不约束」。
--   把「不约束」和「必须不命中」压成同一个 false，「小于 500 元的普通采购单」这条规则
--   就会莫名其妙地要求这次提交**不得**跨部门，等于把 AND 写成了 OR 的反面。
--   三态让每一维的判定只有一个答案。
--
-- 免审为什么只允许 ANY / FORBIDDEN（不收 REQUIRED），这是蓝图那句警告的落点：
--   蓝图说「免审要设上限金额，否则小额免审会被用来绕过大额管控」。绕过大额管控有两条路：
--   ① 没有上限（金额再大都免审）—— 由 amount_max_amount NOT NULL 且 > 0 在库上挡死；
--   ② 有上限但把高风险条件一起豁免掉（跨法人也免审、跨部门也免审、高风险商品也免审）——
--      由四个维度不接受 REQUIRED 在库上挡死。所以一条免审规则只能「在低金额**且**不跨法人/
--      不跨部门/非新供应商/非高风险时才免审」，永远不能反过来把管控面挖穿。
--   两条都由 approval_trigger_rule_decision_shape 这一个 CHECK 写成数据库不变量，
--   Java 侧只是把同样的规则翻译成人能读懂的 400，不是靠应用层自律。

-- ---------------------------------------------------------------------------
-- 1. approval_rule_set · 规则集与生效策略
-- ---------------------------------------------------------------------------
-- default_action 是「一条规则都没命中时怎么办」，也就是蓝图说的「生效策略」：
--   SUBMIT_FOR_APPROVAL 一条都没命中也照送审（fail-closed：宁可多审一次，不给绕过的机会）
--   EXEMPT             一条都没命中就免审（fail-open：这个单据类型整体不走审批，
--                      与上游「未注册则默认可直接执行」同一语义）
-- 两者必须显式选，不给「默认」：本模块的核心风险就是免审被当成默认值铺开。
CREATE TABLE approval_rule_set (
  id                      uuid PRIMARY KEY,
  tenant_id               uuid NOT NULL REFERENCES tenant(id),
  document_type_code      varchar(32) NOT NULL REFERENCES approval_document_type(code) ON DELETE RESTRICT,
  rule_set_name           varchar(100) NOT NULL,
  -- DRAFT=草稿（不参与「这个单据类型当前按什么策略判」的答案）
  -- ACTIVE=生效（同一单据类型同时只能有一条）
  -- DISABLED=停用（保留规则定义但不再管控）
  status                  text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','DISABLED')),
  default_action          text NOT NULL DEFAULT 'SUBMIT_FOR_APPROVAL'
                          CHECK(default_action IN ('SUBMIT_FOR_APPROVAL','EXEMPT')),
  description             varchar(300) NOT NULL DEFAULT '',
  -- 每次保存 +1，供下游识别「策略定义变了」（与 approval_flow.version 同口径）。
  version                 bigint NOT NULL DEFAULT 0 CHECK(version >= 0),
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  created_by_membership_id uuid,
  UNIQUE(tenant_id, id),
  CHECK(btrim(rule_set_name)<>'')
);

-- 同一租户同一单据类型同时只能有一条生效规则集：下游要回答「这次提交按哪套规则判」，
-- 挂着两套生效规则时这个问题没有唯一解。与 approval_flow_active_document 同一手法。
CREATE UNIQUE INDEX approval_rule_set_active_document
  ON approval_rule_set(tenant_id, document_type_code) WHERE status='ACTIVE';

CREATE INDEX approval_rule_set_document_type
  ON approval_rule_set(tenant_id, document_type_code, status);

-- ---------------------------------------------------------------------------
-- 2. approval_trigger_rule · 触发条件与免审规则（规则集内有序，先命中先生效）
-- ---------------------------------------------------------------------------
CREATE TABLE approval_trigger_rule (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid NOT NULL REFERENCES tenant(id),
  rule_set_id            uuid NOT NULL,
  rule_name              varchar(100) NOT NULL,
  -- 规则集内的判定顺序。**先命中先生效**，所以 priority 必须唯一：
  -- 两条同序规则谁能生效没有答案，那种配置期就该拒。
  priority               int NOT NULL CHECK(priority >= 0),
  -- 金额条件（三个一起表达「金额落在这个币种的哪个区间」）：
  --   三个全空 = 不约束金额（这条规则只看别的维度）
  --   给了币种就必须至少给一端区间；给了区间就必须给币种
  --     —— 区间只在同一币种内有意义，「不分币种」的金额区间是没法判定的。
  -- 半开区间 [min, max)：上一条的上限等于下一条的下限是正常接续，不算交叠。
  amount_currency_code   char(3),
  amount_min_amount      numeric(19,6),
  amount_max_amount      numeric(19,6),
  -- 蓝图点名的另外四个条件维度。三态，理由见文件头。
  cross_legal_entity     text NOT NULL DEFAULT 'ANY'
                         CHECK(cross_legal_entity IN ('ANY','REQUIRED','FORBIDDEN')),
  cross_department       text NOT NULL DEFAULT 'ANY'
                         CHECK(cross_department IN ('ANY','REQUIRED','FORBIDDEN')),
  new_vendor             text NOT NULL DEFAULT 'ANY'
                         CHECK(new_vendor IN ('ANY','REQUIRED','FORBIDDEN')),
  high_risk_product      text NOT NULL DEFAULT 'ANY'
                         CHECK(high_risk_product IN ('ANY','REQUIRED','FORBIDDEN')),
  -- 这条条件命中之后怎么办：送审（走 target_flow_id）或免审（直接通过）。
  decision               text NOT NULL CHECK(decision IN ('SUBMIT_FOR_APPROVAL','EXEMPT')),
  -- decision='SUBMIT_FOR_APPROVAL' 时必填：走哪条**已存在的生效流程**。
  -- 本模块不定义流程；指向别的单据类型的流程在 Java 侧写入前就被拒（跨单据类型送审是笔糊涂账）。
  target_flow_id         uuid,
  status                 text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  description            varchar(300) NOT NULL DEFAULT '',
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, rule_set_id, rule_name),
  UNIQUE(tenant_id, rule_set_id, priority),
  FOREIGN KEY (tenant_id, rule_set_id) REFERENCES approval_rule_set(tenant_id, id) ON DELETE CASCADE,
  -- RESTRICT 而不是 CASCADE：删掉一条正被规则引用的流程时宁可失败，也不要静默删掉管控规则
  -- —— 那会让这个单据类型无声地掉回「默认放行」，正是本模块要防的事。
  FOREIGN KEY (tenant_id, target_flow_id) REFERENCES approval_flow(tenant_id, id) ON DELETE RESTRICT,
  CHECK(amount_currency_code IS NULL OR amount_currency_code ~ '^[A-Z]{3}$'),
  CHECK(amount_min_amount IS NULL OR amount_min_amount >= 0),
  CHECK(amount_max_amount IS NULL OR amount_max_amount >= 0),
  CHECK(amount_min_amount IS NULL OR amount_max_amount IS NULL OR amount_max_amount > amount_min_amount)
);

-- 判定时的主查询路径：某规则集内按 priority 取第一条命中的。
CREATE INDEX approval_trigger_rule_set_priority
  ON approval_trigger_rule(tenant_id, rule_set_id, priority) WHERE status='ACTIVE';

-- 「这条免审规则的封顶额度是多少」要能直接查：免审是本模块最需要被盯住的一处。
CREATE INDEX approval_trigger_rule_exemption
  ON approval_trigger_rule(tenant_id, rule_set_id, amount_max_amount) WHERE decision='EXEMPT';

-- 蓝图那句「免审要设上限金额」与「免审不得豁免高风险条件」，在这里成为一条数据库不变量：
--   SUBMIT_FOR_APPROVAL
--     · 必须指向一条流程
--     · 金额要么完全不约束（三个全空），要么给币种 + 至少一端区间
--   EXEMPT
--     · 不指向流程（免审就是不走流程）
--     · 币种必填、**上限必填且必须 > 0**（没有上限的免审等于取消管控）
--     · 跨法人 / 跨部门 / 新供应商 / 高风险商品四维**不允许 REQUIRED**
--       （否则「小额免审」可以顺手把跨法人大额也一起免掉，正是蓝图警告的绕法）
ALTER TABLE approval_trigger_rule ADD CONSTRAINT approval_trigger_rule_decision_shape CHECK(
  (decision='SUBMIT_FOR_APPROVAL'
     AND target_flow_id IS NOT NULL
     AND ((amount_currency_code IS NULL AND amount_min_amount IS NULL AND amount_max_amount IS NULL)
       OR (amount_currency_code IS NOT NULL AND (amount_min_amount IS NOT NULL OR amount_max_amount IS NOT NULL))))
  OR (decision='EXEMPT'
     AND target_flow_id IS NULL
     AND amount_currency_code IS NOT NULL
     AND amount_max_amount IS NOT NULL
     AND amount_max_amount > 0
     AND cross_legal_entity <> 'REQUIRED'
     AND cross_department <> 'REQUIRED'
     AND new_vendor <> 'REQUIRED'
     AND high_risk_product <> 'REQUIRED')
);

-- ---------------------------------------------------------------------------
-- 3. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
-- 本模块三张表全是业务表（规则是租户自己配的管控策略），全部 ENABLE + FORCE RLS。
-- 与 V83 的 policy 逐字一致；approval_document_type / approval_flow 的 RLS 由 V83 负责，这里不重复处理。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'approval_rule_set',
    'approval_trigger_rule'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 4. 授权
-- ---------------------------------------------------------------------------
GRANT SELECT,INSERT,UPDATE,DELETE ON approval_rule_set TO identity_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON approval_trigger_rule TO identity_app;

-- ---------------------------------------------------------------------------
-- 5. 页面授权
-- ---------------------------------------------------------------------------
-- 独立页面权限码，不与 identity.approval_flow 共用一组：
-- 「配流程」和「决定这次提交要不要走流程」是两种不同风险，改后者能绕过前者。
-- 组织管控类变更沿用「仅 OWNER」纪律（见 ApprovalFlowController.call 的 mutation 分支）。
INSERT INTO page_resource(code,name) VALUES ('identity.approval_rule','审批规则')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.approval_rule.'||action,'identity.approval_rule',action
FROM unnest(ARRAY['read','create','update','delete']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.approval_rule.%' ON CONFLICT DO NOTHING;