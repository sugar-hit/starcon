-- ===========================================================================
-- 4.2.3.2 合规预检（违禁词/侵权）—— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/04-listing-and-content.md）：
--   ### 4.2.3.2 合规预检
--   - 职责：发布前扫描内容的违规风险：违禁词/敏感词命中、极限词、品牌侵权（外观/商标）、
--           平台类目必填缺失、图片不合规（尺寸/白底/水印）、认证缺失。
--   - 输入：内容草稿；租户违禁词库；平台规则库；16 合规的侵权与认证状态；03 品牌授权状态。
--   - 输出：风险清单（风险等级、命中位置、规则来源、建议替换）；阻断项（未修复不允许发布）与提醒项。
--   - 关系：上游 = 16 合规与风控、03 品牌、02 平台规则；下游 = 4.3.1.1（阻断发布）。
--           **阻断项在服务端强制校验**，前端提示不能作为唯一防线。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移的依赖面（只到已交付的上游，不跨服务）
-- ---------------------------------------------------------------------------
-- 本服务内已交付并被**真实读取**的上游：
--   * channel V1 `platform` / `marketplace` —— 2.1.1.1 平台目录 / 2.1.1.3 站点与国家参数
--     （草稿的平台与站点要还原成平台码 / 站点码，才能去 catalog 解析平台类目映射）。
--   * channel V1 `shop` —— 02 店铺与启用站点（草稿的目标店铺）。
--   * channel V194 `listing_editor_draft` —— 4.2.1.1 刊登编辑器的**内容草稿**。
--     **扫描的内容来源就是它**（按 平台 + 站点 + 语言 分行保存的那一份内容）。
--
-- 跨服务（只经授权 HTTP API，不跨库 SQL / FK / 视图）：
--   * 03 品牌（catalog `GET /catalog/brands`、`/catalog/brands/{id}/listing-eligibility`、
--     `/catalog/brands/{id}/authorizations`）—— 品牌授权状态与刊登资格。
--   * 03 平台类目映射（4.2.3.1，catalog `GET /catalog/category-mappings`、
--     `/catalog/category-mappings/resolve`）—— 平台类目必填属性清单。
--   * 03 媒体库（catalog `GET /catalog/media`）—— 图片素材的尺寸 / 完整性 / 替代文本。
--   * 03 图片处理配方（catalog `GET /catalog/media/processing/recipes`）—— 目标平台图片规格
--     （白底 / 水印 / 目标尺寸）。
--
-- 🔴 **表命名空间**：本批三条车道并行开发，表前缀必须彼此隔离，否则 Flyway 与语义都会撞车：
--   * 本模块（4.2.3.2，刊登与内容域）⇒ `listing_compliance_scan` / `listing_compliance_risk` /
--     `listing_compliance_word`（本迁移）。
--   * 同批 16.2.2.1 合规预检（合规域，体检报告）⇒ `compliance_check_*`。
--   * 未来 16.2.1.1 合规规则库 ⇒ `compliance_rule*`。
--   **不要用 `compliance_*` 裸名**：那是别人的命名空间。
--
-- 🔴 **与 16.2.1.1 合规规则库的边界（这一条必须写进代码里，否则半年后没人说得清）**：
--   本迁移的 `listing_compliance_word` 是**本模块自己拥有的租户级词表** ——
--   它是运营自己维护的「我们卖这个品类时不能出现哪些词」，带建议替换，**没有版本治理**。
--   16.2.1.1 的合规规则库是**受版本治理的平台规则库**（哪一版规则、什么时候生效、
--   规则原文与出处），是另一件事、另一张表、另一个写入所有者。
--   本模块**只做扫描与出清单**，不建「平台规则库」，也不假装自己有它。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 本模块最重要的一件事：**阻断项在服务端强制校验**
-- ---------------------------------------------------------------------------
-- 蓝图那句加粗是硬要求：「前端提示不能作为唯一防线」。它在本迁移里落成**两道**：
--
--   * **服务层**：`ListingCompliancePrecheckService.publishGate(draftId)` 真算一遍，
--     有未修复的阻断项就返回 allowed=false + 逐条阻断项清单。
--   * **DB 层**：本迁移给 `listing_editor_submission` 加一条 BEFORE INSERT 触发器
--     `guard_listing_editor_submission_compliance` ——
--     只要这份草稿的**最近一次已完成扫描**里还有 status='OPEN' 的 BLOCKING 风险，
--     入队（= 提交审核）这一步就被 DB 直接拒（SQLSTATE 23514）。
--     这样一来，即使有人绕过前端直接调 4.2.1.1 的提交端点，**数据库也不会让这份内容进发布通道**。
--
-- 为什么触发器落在 `listing_editor_submission` 而不是本模块自己的表：
--   「允许发布」这件事的闸门在**发布入口**上，而发布入口是 4.2.1.1 的入队动作
--   （4.3.1.1 一键刊登是同批并行车道，此刻尚不存在，本模块不读它的表也不调它的端点）。
--   本迁移**只加触发器、零改 `listing_editor_submission` 的任何列**，
--   不动 V194 的表结构，也不改 V194 的任何一条既有触发器。
--
-- 🔴 没有扫描过 ⇒ **不拦**。理由：把「还没扫过」当成「不合规」会让整个刊登链一步都走不动，
--   而这并不是蓝图的意思。真正的语义是：
--   * 扫过且有阻断项 ⇒ **拒绝**（这是硬要求）。
--   * 扫过且无阻断项 ⇒ 放行（结论仍然要显示「哪些维度没量到」，见下）。
--   * 没扫过 ⇒ 放行，但发布闸门显式返回 `state=NOT_SCANNED`（不假装「已通过」）。
--
-- ---------------------------------------------------------------------------
-- 二、🔴 「不知道」是一个状态，不是一个 0
-- ---------------------------------------------------------------------------
-- 六个维度里有两个在本集成头**量不到**：
--   * **认证缺失** —— 16.2.2.2 认证与资质未交付，没有认证档案来源。
--   * **平台规则库驱动的极限词** —— 16.2.1.1 合规规则库未交付；极限词只能由本模块自己的
--     租户词表覆盖。
-- 量不到的维度在扫描结果里是 `state='UNKNOWN'` + `ownerModule`，
-- **不计入「已判定维度」的分母、也不算通过**：
--   * 结论取值三态：`PASS`（无阻断项且全部维度已判定）/ `BLOCKED`（有阻断项）/
--     `INCOMPLETE`（当前没有已知阻断项，但有维度没量到 —— **不能说"合规通过"**）。
--   * 发布闸门 `allowed` 只看阻断项；`conclusion` 单独把 INCOMPLETE 摆出来。
--
-- ---------------------------------------------------------------------------
-- 三、风险清单的四个必备要素在 DB 层就钉死
-- ---------------------------------------------------------------------------
-- 蓝图：「风险清单（风险等级、命中位置、规则来源、建议替换）」—— **四样缺一不可**。
-- 本迁移用两件东西把它钉住，不靠应用层自觉：
--   * 列上的 CHECK：severity / risk_level / status 各自枚举，field_key / rule_source /
--     suggestion 三个字符串列 NOT NULL 且非空白。
--   * 触发器 `guard_listing_compliance_risk_completeness`：再查一遍
--     **命中位置必须指到具体字段**（field_key 非空）且**建议替换不得为空串**，
--     违反直接 RAISE —— CHECK 挡不住"空串"这种形状。
--
-- 「命中位置要能定位到具体字段」在数据上落成三件套：
--   `field_key`（哪一栏，如 TITLE / BULLETS / DESCRIPTION / IMAGES / CATEGORY_ATTRIBUTES）
-- + `field_label`（人话名）+ `matched_text`（命中的原文）+ `occurrence_index`
--   （在列表值里的第几项，-1 表示该字段不是列表）。
--
-- ---------------------------------------------------------------------------
-- 四、金额与数量纪律
-- ---------------------------------------------------------------------------
-- 本模块**无任何金额列**（合规预检不改价，售价归 05 定价）。
-- 也**无任何业务数量列**：唯一的数是 `blocking_count` / `warning_count` / `risk_count`，
-- 那是**条数计数**（整数），不是业务数量。
--
-- ---------------------------------------------------------------------------
-- 五、时间字段纪律（AGENTS.md）
-- ---------------------------------------------------------------------------
-- 全部 timestamptz。服务层用 OffsetDateTime 绑定，values 位置写 `?::timestamptz`
-- —— jOOQ 3.19 会把 OffsetDateTime 绑成 varchar，PG 直接拒。

-- ===========================================================================
-- 一、租户违禁词库（本模块自己拥有的租户级词表）
-- ===========================================================================
-- 🔴 边界见文件头：这是**运营自己维护的词表**，不是 16.2.1.1 的受版本治理规则库。
CREATE TABLE listing_compliance_word (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  -- 归一化后的词（小写、去首尾空白）。同一个词在不同平台 / 不同语言下可以各有一条。
  word          varchar(200) NOT NULL,
  -- 录入时的原样（大小写 / 空格可能有讲究），给界面显示用
  display_word  varchar(200) NOT NULL,
  -- 词的类型：PROHIBITED 违禁词 / SENSITIVE 敏感词 / SUPERLATIVE 极限词
  -- （蓝图原文点名的就是这三类：「违禁词/敏感词命中、极限词」——不另造第四类。）
  category      text NOT NULL
                CHECK(category IN ('PROHIBITED','SENSITIVE','SUPERLATIVE')),
  -- 命中后的处置：BLOCKING 阻断项（未修复不允许发布）/ WARNING 提醒项
  severity      text NOT NULL CHECK(severity IN ('BLOCKING','WARNING')),
  -- 风险等级（清单四要素之一）：CRITICAL / HIGH / MEDIUM / LOW
  risk_level    text NOT NULL
                CHECK(risk_level IN ('CRITICAL','HIGH','MEDIUM','LOW')),
  -- 规则来源（清单四要素之一）：这条词是谁定的、依据是什么
  rule_source   varchar(200) NOT NULL,
  -- 建议替换（清单四要素之一）：命中之后应该改成什么
  suggestion    varchar(500) NOT NULL,
  -- 平台维度：空 = 全部平台；非空 = 只在这个平台生效（FK 到 channel V1 的全局平台码表）
  platform_code varchar(32) REFERENCES platform(code),
  -- 语言维度：空 = 全部语言
  locale        varchar(16),
  enabled       boolean NOT NULL DEFAULT true,
  note          text NOT NULL DEFAULT '',
  version       bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(word)<>'' AND btrim(display_word)<>''),
  CHECK(btrim(rule_source)<>'' AND btrim(suggestion)<>''),
  CHECK(locale IS NULL OR btrim(locale)<>'')
);
COMMENT ON TABLE listing_compliance_word IS
  '4.2.3.2 · 租户级违禁词库（运营自己维护）。**不是** 16.2.1.1 的受版本治理合规规则库 —— '
  '后者未交付且归它自己的写入所有权。';
-- 同一租户里「同一个词 + 同一个类型 + 同一个平台 + 同一个语言」只允许一条。
-- 用 coalesce 表达式索引而不是 UNIQUE(tenant_id, word, category, platform_code, locale)：
-- PG 的 UNIQUE 把 NULL 视作互不相等，那样「全平台 + 全语言」能插进去无数条。
CREATE UNIQUE INDEX listing_compliance_word_unique
  ON listing_compliance_word(tenant_id, word, category,
                             coalesce(platform_code,''), coalesce(locale,''));
CREATE INDEX listing_compliance_word_lookup
  ON listing_compliance_word(tenant_id, enabled, category);

-- ===========================================================================
-- 二、扫描记录（一次扫一份草稿）
-- ===========================================================================
CREATE TABLE listing_compliance_scan (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  -- 被扫描的��容草稿（4.2.1.1 的 listing_editor_draft）。删草稿 ⇒ 扫描记录一起消失。
  draft_id       uuid NOT NULL,
  shop_id        uuid NOT NULL,
  platform_id    uuid NOT NULL REFERENCES platform(id),
  marketplace_id uuid NOT NULL REFERENCES marketplace(id),
  locale         varchar(16) NOT NULL,
  -- COMPLETED 全部维度都判定完 / PARTIAL 有维度没量到（上游读不到或该维度未交付）
  state          text NOT NULL CHECK(state IN ('COMPLETED','PARTIAL')),
  -- 结论：PASS 无阻断项且维度全判定 / BLOCKED 有阻断项 / INCOMPLETE 无已知阻断项但有维度没量到
  conclusion     text NOT NULL
                 CHECK(conclusion IN ('PASS','BLOCKED','INCOMPLETE')),
  -- 触发来源：MANUAL 人工点预检 / PRE_PUBLISH 发布前预检
  trigger_source text NOT NULL CHECK(trigger_source IN ('MANUAL','PRE_PUBLISH')),
  risk_count     int NOT NULL DEFAULT 0 CHECK(risk_count >= 0),
  blocking_count int NOT NULL DEFAULT 0 CHECK(blocking_count >= 0),
  warning_count  int NOT NULL DEFAULT 0 CHECK(warning_count >= 0),
  -- 逐维度结论（维度 / 状态 / 风险数 / 归属模块 / 说明）
  dimension_summary jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- 已判定维度数 / 应判定维度数 —— 界面据此显示「5/6 个维度已判定」，
  -- **不把没量到的维度算进分母当通过**
  evaluated_dimension_count int NOT NULL DEFAULT 0 CHECK(evaluated_dimension_count >= 0),
  total_dimension_count      int NOT NULL DEFAULT 0 CHECK(total_dimension_count >= 0),
  -- 量不到的维度数（UNKNOWN）。它把 state 与 conclusion 一起定死：
  --   * state = PARTIAL ⟺ 有维度没量到
  --   * conclusion = INCOMPLETE ⟺ 没有阻断项、但有维度没量到（**不能说「合规通过」**）
  unknown_dimension_count   int NOT NULL DEFAULT 0 CHECK(unknown_dimension_count >= 0),
  scanned_at     timestamptz NOT NULL DEFAULT now(),
  created_at     timestamptz NOT NULL DEFAULT now(),
  version        bigint NOT NULL DEFAULT 0,
  UNIQUE(tenant_id, id),
  CHECK(btrim(locale)<>''),
  CHECK(jsonb_typeof(dimension_summary)='array'),
  -- 结论与计数必须自洽（应用层算错也在 DB 层被拒）
  --   BLOCKED   ⟺ 有阻断项
  --   INCOMPLETE ⟺ 没有阻断项、但有维度没量到
  --   PASS      ⟺ 没有阻断项、且全部维度都已判定
  CHECK((blocking_count > 0) = (conclusion='BLOCKED')),
  CHECK(conclusion <> 'INCOMPLETE' OR (blocking_count = 0 AND unknown_dimension_count > 0)),
  CHECK(conclusion <> 'PASS' OR (blocking_count = 0 AND unknown_dimension_count = 0)),
  -- 覆盖度与结论口径一致：有维度没量到就是 PARTIAL
  CHECK((state='PARTIAL') = (unknown_dimension_count > 0)),
  FOREIGN KEY(tenant_id, draft_id) REFERENCES listing_editor_draft(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, shop_id) REFERENCES shop(tenant_id, id)
);
COMMENT ON TABLE listing_compliance_scan IS
  '4.2.3.2 · 一次合规预检扫描（针对一份编辑器草稿）。风险清单在 listing_compliance_risk。';
CREATE INDEX listing_compliance_scan_recent
  ON listing_compliance_scan(tenant_id, scanned_at DESC, id);
CREATE INDEX listing_compliance_scan_draft
  ON listing_compliance_scan(tenant_id, draft_id, scanned_at DESC);

-- ===========================================================================
-- 三、风险清单（一条风险）
-- ===========================================================================
CREATE TABLE listing_compliance_risk (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  scan_id       uuid NOT NULL,
  -- 维度：PROHIBITED_WORD 违禁词 / SENSITIVE_WORD 敏感词 / SUPERLATIVE_WORD 极限词 /
  --       BRAND_INFRINGEMENT 品牌侵权 / CATEGORY_REQUIRED 平台类目必填 / IMAGE 图片不合规 /
  --       CERTIFICATION 认证缺失（16.2.2.2 未交付 ⇒ 恒为 UNKNOWN，**不会产生任何风险行**）
  dimension     text NOT NULL CHECK(dimension IN (
                  'PROHIBITED_WORD','SENSITIVE_WORD','SUPERLATIVE_WORD',
                  'BRAND_INFRINGEMENT','CATEGORY_REQUIRED','IMAGE','CERTIFICATION')),
  -- 处置：BLOCKING 阻断项（未修复不允许发布）/ WARNING 提醒项
  severity      text NOT NULL CHECK(severity IN ('BLOCKING','WARNING')),
  -- 风险等级（清单四要素之一）
  risk_level    text NOT NULL CHECK(risk_level IN ('CRITICAL','HIGH','MEDIUM','LOW')),
  -- 风险码：同一维度下的具体类型（WORD_HIT / BRAND_UNAUTHORIZED / IMAGE_TOO_SMALL …）
  code          varchar(64) NOT NULL,
  -- 🔴 命中位置（清单四要素之一）：指到具体字段
  field_key     varchar(64) NOT NULL,
  field_label   varchar(120) NOT NULL,
  -- 命中的原文（词命中时是那一个词；其它维度是触发条件的取值）
  matched_text  varchar(500),
  -- 在列表型字段里是第几项（0 起）；非列表字段为 -1
  occurrence_index int NOT NULL DEFAULT -1,
  -- 🔴 规则来源（清单四要素之一）：这条风险依据哪条规则 / 哪个上游判定
  rule_source   varchar(200) NOT NULL,
  -- 规则的归属模块（如 16.2.2.2 认证、4.2.3.1 类目映射）
  rule_owner_module varchar(32),
  -- 🔴 建议替换（清单四要素之一）
  suggestion    varchar(500) NOT NULL,
  -- 人话说明
  message       text NOT NULL,
  -- 该风险的归属模块（量不到的维度不进这张表，它在扫描的 dimension_summary 里）
  owner_module  varchar(32),
  -- 证据（命中的上下文、上游原始返回的摘要）
  evidence      jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- OPEN 未处理 / RESOLVED 已修复 / IGNORED 已忽略（人工判定的豁免）
  status        text NOT NULL DEFAULT 'OPEN'
                CHECK(status IN ('OPEN','RESOLVED','IGNORED')),
  resolved_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  CHECK(btrim(code)<>''),
  CHECK(btrim(field_key)<>'' AND btrim(field_label)<>''),
  CHECK(btrim(rule_source)<>''),
  CHECK(btrim(suggestion)<>''),
  CHECK(btrim(message)<>''),
  CHECK(jsonb_typeof(evidence)='object'),
  CHECK(status='OPEN' OR resolved_at IS NOT NULL),
  FOREIGN KEY(tenant_id, scan_id) REFERENCES listing_compliance_scan(tenant_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE listing_compliance_risk IS
  '4.2.3.2 · 风险清单的一条。四要素（风险等级 / 命中位置 / 规则来源 / 建议替换）'
  '在列的 CHECK 与触发器里双重钉死。';
CREATE INDEX listing_compliance_risk_scan
  ON listing_compliance_risk(tenant_id, scan_id, severity, status);
CREATE INDEX listing_compliance_risk_open
  ON listing_compliance_risk(tenant_id, scan_id) WHERE severity='BLOCKING' AND status='OPEN';

-- ===========================================================================
-- 四、触发器
-- ===========================================================================
-- (a) 🔴 风险清单四要素在 DB 层钉死：命中位置必须指到具体字段、建议替换不得为空串。
--     CHECK 只能保证列非 NULL，空串它挡不住（空串 btrim 后也是空串，CHECK 里其实挡得住，
--     但这里用触发器再钉一次，是为了让**触发顺序**与错误码可控：
--     23514 + 明确文案，比一个 CHECK 名字更能让调用方看懂发生了什么）。
CREATE FUNCTION guard_listing_compliance_risk_completeness() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF btrim(NEW.field_key) = '' THEN
    RAISE EXCEPTION 'Compliance risk % must point to a concrete field (field_key)', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF btrim(NEW.rule_source) = '' THEN
    RAISE EXCEPTION 'Compliance risk % must carry its rule source', NEW.id
      USING ERRCODE='23514';
  END IF;
  IF btrim(NEW.suggestion) = '' THEN
    RAISE EXCEPTION 'Compliance risk % must carry a suggested replacement', NEW.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_compliance_risk_completeness BEFORE INSERT OR UPDATE ON listing_compliance_risk
  FOR EACH ROW EXECUTE FUNCTION guard_listing_compliance_risk_completeness();

-- (b) 扫描上的计数与结论**由风险行算出来**，不许应用层自己填。
--     应用层把 risk_count 填错的话，「界面说 3 条阻断项、库里其实 5 条」这种漂移就发生了，
--     而发布闸门读的就是这组数。
--     结论也在这里一起算：阻断项优先，其次「有维度没量到」⇒ INCOMPLETE。
CREATE FUNCTION sync_listing_compliance_scan_counts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; blocking int; warning int; total int;
BEGIN
  target := COALESCE(NEW.scan_id, OLD.scan_id);
  SELECT count(*) FILTER (WHERE severity='BLOCKING' AND status='OPEN'),
         count(*) FILTER (WHERE severity='WARNING' AND status='OPEN'),
         count(*) FILTER (WHERE status='OPEN')
    INTO blocking, warning, total
    FROM listing_compliance_risk WHERE scan_id = target;
  UPDATE listing_compliance_scan
     SET blocking_count = blocking,
         warning_count = warning,
         risk_count = total,
         conclusion = CASE WHEN blocking > 0 THEN 'BLOCKED'
                           WHEN unknown_dimension_count > 0 THEN 'INCOMPLETE'
                           ELSE 'PASS' END
   WHERE id = target;
  RETURN NULL;
END $$;
CREATE TRIGGER listing_compliance_risk_counts
  AFTER INSERT OR UPDATE OR DELETE ON listing_compliance_risk
  FOR EACH ROW EXECUTE FUNCTION sync_listing_compliance_scan_counts();

-- (c) 🔴🔴 **阻断项在服务端强制校验**（DB 层的那道闸）。
--     4.2.1.1 提交审核 = 入队（listing_editor_submission）。只要这份草稿的
--     **最近一次已完成扫描**里还有 OPEN 的 BLOCKING 风险，DB 直接拒写入队。
--     ⇒ 前端提示不再是唯一防线：绕过界面直接调提交端点，数据库也不让过。
--
--     「最近一次扫描」用 (scanned_at DESC, id DESC) 取，避免同一毫秒的两条扫描顺序不定。
--     没有扫描过 ⇒ 不拦（见文件头第一节的理由：把「还没扫过」当「不合规」会让刊登链一步都走不动）。
CREATE FUNCTION guard_listing_editor_submission_compliance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE blockers int; scanned uuid;
BEGIN
  SELECT s.id INTO scanned
    FROM listing_compliance_scan s
   WHERE s.tenant_id = NEW.tenant_id AND s.draft_id = NEW.draft_id
   ORDER BY s.scanned_at DESC, s.id DESC
   LIMIT 1;
  IF scanned IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT count(*) INTO blockers
    FROM listing_compliance_risk r
   WHERE r.scan_id = scanned AND r.severity = 'BLOCKING' AND r.status = 'OPEN';
  IF blockers > 0 THEN
    RAISE EXCEPTION
      'Draft % has % unresolved blocking compliance risk(s); publishing is not allowed until they are fixed',
      NEW.draft_id, blockers
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER listing_editor_submission_compliance BEFORE INSERT ON listing_editor_submission
  FOR EACH ROW EXECUTE FUNCTION guard_listing_editor_submission_compliance();

-- ===========================================================================
-- 五、多租户隔离：租户业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ===========================================================================
-- 与 channel V1 / V171 / V177 / V194 / V195 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'listing_compliance_word','listing_compliance_scan','listing_compliance_risk' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 数据范围（RESTRICTIVE，与 V194 同构：可见性随店铺负责人 / 部门收敛）
-- 词库是租户级资产、不挂店铺维度（同 V195 listing_template），只有 isolation 策略。
CREATE POLICY data_scope ON listing_compliance_scan AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_compliance_scan.tenant_id
             AND s.id=listing_compliance_scan.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM shop s WHERE s.tenant_id=listing_compliance_scan.tenant_id
             AND s.id=listing_compliance_scan.shop_id
             AND channel_scope(channel_operation(),s.owner_id,s.department_id)));

CREATE POLICY data_scope ON listing_compliance_risk AS RESTRICTIVE
  USING (EXISTS(SELECT 1 FROM listing_compliance_scan c JOIN shop s
                    ON s.tenant_id=c.tenant_id AND s.id=c.shop_id
                  WHERE c.tenant_id=listing_compliance_risk.tenant_id
                    AND c.id=listing_compliance_risk.scan_id
                    AND channel_scope(channel_operation(),s.owner_id,s.department_id)))
  WITH CHECK (EXISTS(SELECT 1 FROM listing_compliance_scan c JOIN shop s
                    ON s.tenant_id=c.tenant_id AND s.id=c.shop_id
                  WHERE c.tenant_id=listing_compliance_risk.tenant_id
                    AND c.id=listing_compliance_risk.scan_id
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
  listing_compliance_word, listing_compliance_scan, listing_compliance_risk TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V195 同口径）。
REVOKE ALL ON
  listing_compliance_word, listing_compliance_scan, listing_compliance_risk FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 权限码：本模块**不新增权限码** —— 合规预检属于「平台操作」语义，
-- 与 4.1.1.1 listing 列表、4.2.1.2 批量编辑、4.2.2.1 刊登模板、4.4.2.1 商品认领同族，
-- 复用 identity 已注册的 channel.operation.*，identity 服务零改动。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 留给上游 / 下游的口子（不是本模块的缺口）
-- ---------------------------------------------------------------------------
--   * **16.2.1.1 合规规则库**：未交付 ⇒ 平台规则库没有来源。本模块**不建**规则库表，
--     只做扫描与出清单；租户违禁词库（listing_compliance_word）是本模块自己的租户级词表。
--   * **16.2.2.2 认证与资质**：未交付 ⇒ 「认证缺失」维度 UNKNOWN + ownerModule=16.2.2.2，
--     **不计入已判定维度的分母、也不算通过**。
--   * **16.2.2.1 合规预检（体检报告）**：同批并行的另一条车道（合规域，分维度得分），
--     与本模块**不共表、不共端点、不共页面**；本模块不读它的数据也不调它的端点。
--   * **4.3.1.1 一键刊登**：同批并行的另一条车道，尚未交付 ⇒ 本模块不硬依赖它；
--     但**阻断项在服务端强制校验**这件事在 DB 层已经做实（触发器 (c)），
--     它接上来时可以直接读发布闸门。
-- ---------------------------------------------------------------------------
