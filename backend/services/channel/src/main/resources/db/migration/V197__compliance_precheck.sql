-- ===========================================================================
-- 16.2.2.1 合规预检（阶段 D7，L3 锚点 16.2.2）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md）：
--   「发布前的合规体检：内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、
--     标签与说明书要求，多维度检查并给出整改建议。
--     输入：listing 内容草稿；商品属性；认证状态；平台要求。
--     输出：体检报告（分维度得分 + 问题清单 + 整改建议）。
--     关系：上游 = 4.2.1.1 编辑器；下游 = 16.2.1.2 拦截。
--     **预检在保存草稿时就跑一次**（即时反馈），发布时再跑一次（强制校验）。」
--
-- 🔴 命名空间隔离（与同批车道必须不撞）：
--   * 本模块三张表一律 `compliance_check_*` 前缀。
--   * 16.2.1.1 规则库未来要占 `compliance_*`（规则 / 词库 / 版本）——**本模块不建规则库**，
--     规则与词库的治理归 16.2.1.1。
--   * 4.2.3.2（同批另一条车道，卷 04 的「合规预检（违禁词/侵权）」）用 `listing_compliance_*`。
--     两者职责不同、并行开发、互不依赖：那边出「阻断项 + 提醒项」的风险清单，
--     这边出「分维度得分 + 问题清单 + 整改建议」的体检报告。
--     本迁移**不建**任何 `listing_compliance_*` 表，也不引用 4.2.3.2 的任何对象。
--
-- 🔴 **纯新建、零 ALTER 任何既有表**：不加列、不 SET NOT NULL、不加会收紧旧值域的 CHECK。
--   既有表 `listing_editor_draft` / `listing_editor_submission`（4.2.1.1，V194）本模块
--   **只读**，写入所有权仍在 4.2.1.1 手里 —— 因此草稿 id 走**逻辑 id 引用**、不建跨模块 FK。
--
-- 🔴 与 16.2.1.1 规则库、与 16.2.1.2 拦截执行、与 16.2.2.2 认证与资质的边界：
--   * 16.2.1.1 未交付 ⇒ 尺寸规格 / 材质 / 危险品 / 标签这四维的**细则规则**没有来源。
--     本迁移**不硬编码任何细则**；对应维度在报告里是 UNKNOWN 且**不计入分母**。
--   * 16.2.1.2 未交付 ⇒ 发布前的**拦截、豁免、审批**不归本模块。本模块只出报告与结论。
--   * 16.2.2.2 未交付 ⇒ 认证档案没有来源，「认证缺失」维度是 UNKNOWN 且不计入分母。
-- ===========================================================================

-- ===========================================================================
-- 一、compliance_check_report · 体检报告头
-- ===========================================================================
-- 一次体检 = 一份报告。报告是**那一次判定的凭据**：分数、问题清单、整改建议
-- 全部冻结在这一次里，事后不得改写（追加式触发器钉住）。
CREATE TABLE compliance_check_report (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 被体检的内容草稿（4.2.1.1 的 listing_editor_draft，**只读逻辑引用**）
  draft_id      uuid NOT NULL,
  shop_id       uuid NOT NULL,
  platform_id   uuid NOT NULL,
  marketplace_id uuid NOT NULL,
  locale        varchar(16) NOT NULL,
  -- 🔴🔴 两次触发的语义：**报告必须能区分是哪一次触发的**。
  --   DRAFT_SAVE   保存草稿时的即时反馈（4.2.1.1 保存动作旁路，改完就能看到体检结果）
  --   PUBLISH_GATE 发布前的强制校验（供 16.2.1.2 拦截执行消费；**本模块不执行拦截**）
  trigger_kind  text NOT NULL
                CHECK(trigger_kind IN ('DRAFT_SAVE','PUBLISH_GATE')),
  -- SCORED 有维度评得出分 / UNKNOWN 全维度不可知
  state         text NOT NULL
                CHECK(state IN ('SCORED','UNKNOWN')),
  -- 🔴 总体得分：**0..1 比值**（不是百分数 —— NUMERIC(5,4) 装不下 100.0000），
  --    **NULL = 未知**（全维度不可知时给 NULL，**不是 0 分**）
  overall_score numeric(5,4)
                CHECK(overall_score IS NULL OR (overall_score >= 0 AND overall_score <= 1)),
  -- PASS 无违规 / ATTENTION 有违规 / UNKNOWN 没有任何可评维度（判不了）
  verdict       text NOT NULL
                CHECK(verdict IN ('PASS','ATTENTION','UNKNOWN')),
  -- 是否有「量不到」的维度（消费方据此决定要不要更保守 —— 未知不等于合规）
  has_unknown_dimensions boolean NOT NULL DEFAULT false,
  scored_dimension_count integer NOT NULL DEFAULT 0,
  unknown_dimension_count integer NOT NULL DEFAULT 0,
  total_dimension_count  integer NOT NULL DEFAULT 0,
  check_item_count   integer NOT NULL DEFAULT 0,
  passed_item_count  integer NOT NULL DEFAULT 0,
  failed_item_count  integer NOT NULL DEFAULT 0,
  unknown_item_count integer NOT NULL DEFAULT 0,
  -- 🔴 体检时的事实快照（草稿内容 + 平台要求 + 源商品）：这份报告凭什么这么判
  source_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 整改建议（按维度归并后的行动项，数组）
  remediation jsonb NOT NULL DEFAULT '[]'::jsonb,
  checked_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(jsonb_typeof(source_snapshot)='object'),
  CHECK(jsonb_typeof(remediation)='array'),
  CHECK(btrim(locale)<>''),
  -- 声称评得出分就必须有被评分的维度，反之亦然
  CHECK((state='SCORED') = (scored_dimension_count > 0)),
  -- 声称 UNKNOWN 就必须没有总分（**未知不是 0**）
  CHECK((state='UNKNOWN') = (overall_score IS NULL)),
  -- 判不了的结论（verdict=UNKNOWN）必须一个可评维度都没有
  CHECK((verdict='UNKNOWN') = (scored_dimension_count = 0)),
  -- 计数自洽
  CHECK(total_dimension_count = scored_dimension_count + unknown_dimension_count),
  CHECK(check_item_count = passed_item_count + failed_item_count + unknown_item_count),
  CHECK(failed_item_count + unknown_item_count <= check_item_count),
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id),
  FOREIGN KEY(platform_id) REFERENCES platform(id),
  FOREIGN KEY(marketplace_id) REFERENCES marketplace(id)
);
COMMENT ON TABLE compliance_check_report IS
  '16.2.2.1 · 合规预检体检报告头。一次体检一份报告；trigger_kind 区分「保存草稿时的即时反馈」'
  '与「发布前的强制校验」。overall_score 为 NULL 表示全维度不可知（未知不是 0 分）。';

CREATE INDEX compliance_check_report_recent
  ON compliance_check_report(tenant_id, checked_at DESC, id);
CREATE INDEX compliance_check_report_draft
  ON compliance_check_report(tenant_id, draft_id, checked_at DESC);
CREATE INDEX compliance_check_report_trigger
  ON compliance_check_report(tenant_id, trigger_kind, checked_at DESC);

-- ===========================================================================
-- 二、compliance_check_finding · 问题清单（含「量不到」条目）
-- ===========================================================================
-- 🔴 「量不到」也是一条 finding（finding_state='UNKNOWN'），**不是**被悄悄丢掉：
--   丢掉的话界面上就只剩一个看起来很干净的分数，运营以为全绿，
--   而真相是「这一项我们没能力量」。finding_state 区分两种事实：
--     VIOLATION 实打实的问题（有违规事实）
--     UNKNOWN   量不到（没有事实，但也没有「合规」的结论）
CREATE TABLE compliance_check_finding (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  report_id     uuid NOT NULL,
  -- 🔴 展示顺序：这条 finding 在本报告里的序号。
  --   🔴 **必须有它**：读出来时不能靠「数据库碰巧怎么返回」决定顺序 ——
  --   本表有一个 (tenant_id, report_id, dimension_code, id) 索引，按 id（随机 UUID）扫出来的顺序
  --   是随机的，界面上「问题清单」的行序会每次刷新都变。序号是生产时定下来的，不靠读的时候重排。
  seq           integer NOT NULL CHECK(seq >= 0),
  dimension_code varchar(32) NOT NULL,
  dimension_label varchar(64) NOT NULL,
  -- 检查项编码（内容类：FIELD_REQUIRED / FIELD_MAX_LENGTH / FIELD_MIN_ITEMS /
  -- FIELD_MAX_ITEMS / FIELD_CAPABILITY / FIELD_UNSUPPORTED / BRAND_AUTHORIZATION /
  -- SOURCE_ATTRIBUTE_VALUE / CONTENT_RULE_LIBRARY）
  check_code    varchar(48) NOT NULL,
  -- 命中位置：字段键 / 能力键 / 属性键
  subject_ref   varchar(160) NOT NULL DEFAULT '',
  -- 人话位置（前端直接显示，不用自己翻译字段键）
  location      varchar(200) NOT NULL DEFAULT '',
  -- BLOCKING 阻断项 / WARNING 警告项 / INFO 提示
  severity      varchar(16) NOT NULL
                CHECK(severity IN ('BLOCKING','WARNING','INFO')),
  finding_state varchar(16) NOT NULL
                CHECK(finding_state IN ('VIOLATION','UNKNOWN')),
  message       varchar(1000) NOT NULL,
  -- 🔴 整改建议：量到的也必须给文案（写清"量不到"与归属模块），**不许留空**
  suggestion    varchar(1000) NOT NULL DEFAULT '',
  -- 🔴 归属模块：本模块自己能判的写 16.2.2.1；量到的写真正的归属模块
  owner_module  varchar(16) NOT NULL,
  evidence      jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, report_id, seq),
  CHECK(jsonb_typeof(evidence)='object'),
  CHECK(btrim(message)<>''),
  CHECK(btrim(owner_module)<>''),
  -- 违规项必须有整改建议；量不到的也必须有（写清"量不到"与归属模块）
  CHECK(btrim(suggestion)<>''),
  FOREIGN KEY(tenant_id, report_id) REFERENCES compliance_check_report(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE compliance_check_finding IS
  '16.2.2.1 · 合规预检问题清单。finding_state=UNKNOWN 的条目是「量不到」的维度/检查项，'
  '它们不计入得分分母，但必须在报告里显式出现。';

CREATE INDEX compliance_check_finding_report
  ON compliance_check_finding(tenant_id, report_id, dimension_code, id);
CREATE INDEX compliance_check_finding_state
  ON compliance_check_finding(tenant_id, finding_state, id);
-- 🔴 读「本报告的问题清单」按序号走，顺序是生产时定下来的，不靠索引碰巧怎么扫
CREATE INDEX compliance_check_finding_seq
  ON compliance_check_finding(tenant_id, report_id, seq);

-- ===========================================================================
-- 三、compliance_check_dimension_score · 分维度得分
-- ===========================================================================
-- 🔴「不可知分量不计入分母」在这里落地：score 为 NULL = 该维度不可知；
--   评得出分的维度里，UNKNOWN 的检查项也不进分母（passed + failed 才是分母）。
CREATE TABLE compliance_check_dimension_score (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  report_id     uuid NOT NULL,
  dimension_code varchar(32) NOT NULL,
  dimension_label varchar(64) NOT NULL,
  -- 维度权重（0..1 比值）。六个维度的权重合计为 1 由服务层的类初始化块断言
  -- —— 改权重忘了配平会在启动时炸，而不是上线后让分数静默偏移。
  weight        numeric(9,8) NOT NULL
                CHECK(weight >= 0 AND weight <= 1),
  -- 🔴 维度得分：**0..1 比值**，**NULL = 该维度不可知**（不是 0 分）
  score         numeric(5,4)
                CHECK(score IS NULL OR (score >= 0 AND score <= 1)),
  -- SCORED 评得出分 / UNKNOWN 量不到
  state         varchar(16) NOT NULL
                CHECK(state IN ('SCORED','UNKNOWN')),
  check_item_count integer NOT NULL DEFAULT 0,
  passed_item_count integer NOT NULL DEFAULT 0,
  failed_item_count integer NOT NULL DEFAULT 0,
  unknown_item_count integer NOT NULL DEFAULT 0,
  -- 量不到时的归属模块与原因（可评的维度这两列留空）
  owner_module  varchar(16),
  unavailable_reason varchar(500) NOT NULL DEFAULT '',
  note          varchar(500) NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, report_id, dimension_code),
  CHECK(check_item_count = passed_item_count + failed_item_count + unknown_item_count),
  -- 声称评得出分就必须有分（分母不为空）
  CHECK((state='SCORED') = (score IS NOT NULL)),
  -- 评得出分的维度必须至少有一个**可评**的检查项（UNKNOWN 项不进分母）
  CHECK(state <> 'SCORED' OR (passed_item_count + failed_item_count) > 0),
  -- 量不到的维度必须说清归属模块与原因
  CHECK(state <> 'UNKNOWN' OR (owner_module IS NOT NULL AND btrim(unavailable_reason)<>'')),
  -- 可评的维度不该挂着"量不到"的原因
  CHECK(state <> 'SCORED' OR (owner_module IS NULL AND btrim(unavailable_reason)='')),
  FOREIGN KEY(tenant_id, report_id) REFERENCES compliance_check_report(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE compliance_check_dimension_score IS
  '16.2.2.1 · 合规预检分维度得分。score 为 NULL 表示该维度不可知（**不计入总分分母**）；'
  'UNKNOWN 的检查项也不进该维度的分母（分母 = passed + failed）。';

CREATE INDEX compliance_check_dimension_score_report
  ON compliance_check_dimension_score(tenant_id, report_id, dimension_code);

-- ===========================================================================
-- 四、追加式：报告是「那一次判定的凭据」，事后不许改写
-- ===========================================================================
-- 与 V194 的 guard_editor_submission_immutable 同一纪律：内容与证据一经写入不许改。
-- 没有这条的话，运营可以把一次「ATTENTION」的报告改成「PASS」，体检就没有意义了。
CREATE FUNCTION guard_compliance_check_report_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.state IS DISTINCT FROM OLD.state
     OR NEW.overall_score IS DISTINCT FROM OLD.overall_score
     OR NEW.verdict IS DISTINCT FROM OLD.verdict
     OR NEW.trigger_kind IS DISTINCT FROM OLD.trigger_kind
     OR NEW.draft_id IS DISTINCT FROM OLD.draft_id
     OR NEW.check_item_count IS DISTINCT FROM OLD.check_item_count
     OR NEW.failed_item_count IS DISTINCT FROM OLD.failed_item_count
     OR NEW.unknown_item_count IS DISTINCT FROM OLD.unknown_item_count
     OR NEW.source_snapshot IS DISTINCT FROM OLD.source_snapshot
     OR NEW.remediation IS DISTINCT FROM OLD.remediation
     OR NEW.checked_at IS DISTINCT FROM OLD.checked_at THEN
    RAISE EXCEPTION
      'Compliance check report % is append-only: the verdict, the scores and the evidence are frozen',
      OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER compliance_check_report_immutable BEFORE UPDATE ON compliance_check_report
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_check_report_immutable();

CREATE FUNCTION guard_compliance_check_finding_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.report_id IS DISTINCT FROM OLD.report_id
     OR NEW.dimension_code IS DISTINCT FROM OLD.dimension_code
     OR NEW.check_code IS DISTINCT FROM OLD.check_code
     OR NEW.finding_state IS DISTINCT FROM OLD.finding_state
     OR NEW.severity IS DISTINCT FROM OLD.severity
     OR NEW.message IS DISTINCT FROM OLD.message
     OR NEW.suggestion IS DISTINCT FROM OLD.suggestion
     OR NEW.owner_module IS DISTINCT FROM OLD.owner_module
     OR NEW.evidence IS DISTINCT FROM OLD.evidence THEN
    RAISE EXCEPTION
      'Compliance check finding % is append-only: a finding is the evidence of that moment',
      OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER compliance_check_finding_immutable BEFORE UPDATE ON compliance_check_finding
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_check_finding_immutable();

CREATE FUNCTION guard_compliance_check_dimension_score_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.report_id IS DISTINCT FROM OLD.report_id
     OR NEW.dimension_code IS DISTINCT FROM OLD.dimension_code
     OR NEW.weight IS DISTINCT FROM OLD.weight
     OR NEW.score IS DISTINCT FROM OLD.score
     OR NEW.state IS DISTINCT FROM OLD.state
     OR NEW.check_item_count IS DISTINCT FROM OLD.check_item_count
     OR NEW.passed_item_count IS DISTINCT FROM OLD.passed_item_count
     OR NEW.failed_item_count IS DISTINCT FROM OLD.failed_item_count
     OR NEW.unknown_item_count IS DISTINCT FROM OLD.unknown_item_count
     OR NEW.owner_module IS DISTINCT FROM OLD.owner_module
     OR NEW.unavailable_reason IS DISTINCT FROM OLD.unavailable_reason THEN
    RAISE EXCEPTION
      'Compliance check dimension score % is append-only: a score is the evidence of that moment',
      OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER compliance_check_dimension_score_immutable BEFORE UPDATE ON compliance_check_dimension_score
  FOR EACH ROW EXECUTE FUNCTION guard_compliance_check_dimension_score_immutable();

-- ===========================================================================
-- 五、多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V171 / V177 / V194 同一套写法。租户由 backend/libraries/tenancy 在事务内设置
-- （app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['compliance_check_report','compliance_check_finding',
                             'compliance_check_dimension_score'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围（RESTRICTIVE，与 V171 / V177 / V194 同构：可见性随店铺负责人 / 部门收敛）
CREATE POLICY data_scope ON compliance_check_report AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=compliance_check_report.tenant_id
             AND s.id=compliance_check_report.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=compliance_check_report.tenant_id
             AND s.id=compliance_check_report.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON compliance_check_finding AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM compliance_check_report r
                 JOIN shop s ON s.tenant_id=r.tenant_id AND s.id=r.shop_id
                WHERE r.tenant_id=compliance_check_finding.tenant_id
                  AND r.id=compliance_check_finding.report_id
                  AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM compliance_check_report r
                 JOIN shop s ON s.tenant_id=r.tenant_id AND s.id=r.shop_id
                WHERE r.tenant_id=compliance_check_finding.tenant_id
                  AND r.id=compliance_check_finding.report_id
                  AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON compliance_check_dimension_score AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM compliance_check_report r
                 JOIN shop s ON s.tenant_id=r.tenant_id AND s.id=r.shop_id
                WHERE r.tenant_id=compliance_check_dimension_score.tenant_id
                  AND r.id=compliance_check_dimension_score.report_id
                  AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM compliance_check_report r
                 JOIN shop s ON s.tenant_id=r.tenant_id AND s.id=r.shop_id
                WHERE r.tenant_id=compliance_check_dimension_score.tenant_id
                  AND r.id=compliance_check_dimension_score.report_id
                  AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

-- ===========================================================================
-- 六、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  compliance_check_report, compliance_check_finding, compliance_check_dimension_score TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V195 同口径）。
REVOKE ALL ON
  compliance_check_report, compliance_check_finding, compliance_check_dimension_score FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 合规预检属于「平台操作」语义，
-- 与 4.1.1.1 listing 列表、4.2.1.1 刊登编辑器、4.2.1.2 批量编辑同族，
-- 复用 identity 已注册的 channel.operation.*，identity 服务零改动。
-- ---------------------------------------------------------------------------
