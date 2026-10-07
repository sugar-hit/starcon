-- 17.2.2.3 离职与停用。
--
-- 本模块的核心约束是**停用而非删除**：成员离职时 membership 走状态流转（可逆、有原因、有时刻），
-- 离职员工此前的操作记录是审计追溯的依据（蓝图 17.2.2.3「关系」栏）。因此：
--   * 三张表都**不授予 identity_app DELETE**——物理删除在权限层就不存在，不是靠调用方自觉；
--   * 所有指向 membership 的外键都是 ON DELETE RESTRICT，删不掉一个留有离职史的成员；
--   * membership 上再加一道删除保护触发器（沿用 V5 legal_entity 的先例）。
--
-- **边界（重要）**：审批（17.4.2.2）与工作台待办（01）服务尚未交付，AGENTS.md 禁止跨服务读表，
-- 所以本服务**不查询它们的表**。这里只落两张台账：
--   * offboarding_responsibility_item —— 下游经授权 API 登记「我这边还有哪些在办单据/审批」，
--     identity 只记录谁转给谁，不去 trade/workflow 的库里核对真实存在性；
--   * offboarding_reclaim_item —— 待办与任务的回收台账，identity 只把条目从 PENDING 标到
--     RECLAIMED/REASSIGNED，真正的删除与改派由持有方服务消费。
-- 这样「谁转给谁、哪些条目被标记待回收」是真实落库的事实，下游缺失时台账如实停在 PENDING，
-- 而不是伪装成「已全部处理」。

CREATE TABLE offboarding_case (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL REFERENCES tenant(id),
  -- 被停用的成员。RESTRICT 而不是 CASCADE：离职记录要跟着人一起留一辈子。
  membership_id       uuid NOT NULL,
  -- 操作人（当前租户所有者）。同样 RESTRICT，审计追溯不能因为删了操作人而断链。
  operator_membership_id uuid NOT NULL,
  -- 离职原因：状态流转必须带原因，否则事后无法解释「为什么停用」。
  reason              text NOT NULL,
  -- 停用生效时刻（「有时刻」）。用 timestamptz，跨时区协作下唯一确定。
  effective_at        timestamptz NOT NULL,
  -- OPEN：仍有未处理的责任转移/回收条目；COMPLETED：全部结清；REVERTED：已撤销并复职。
  status              text NOT NULL DEFAULT 'OPEN'
                        CHECK(status IN ('OPEN','COMPLETED','REVERTED')),
  -- 停用前的状态，复职时按它恢复，而不是假定它一定是 ACTIVE。
  previous_status     text NOT NULL,
  note                text NOT NULL DEFAULT '',
  created_at          timestamptz NOT NULL DEFAULT now(),
  -- 撤销时刻可空：没撤销就是空，这是「可逆」的证据。
  reverted_at         timestamptz,
  reverted_by_membership_id uuid,
  UNIQUE(tenant_id, id),
  -- 同一个成员同一租户只能有一条在办的离职：重复发起会让责任转移台账出现两个来源。
  -- 已撤销/已结清的可以再次发起（复职后二次离职是正常业务）。
  FOREIGN KEY (tenant_id, membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, operator_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, reverted_by_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  CHECK(btrim(reason)<>'')
);

-- 在办单据与审批的责任转移台账。下游服务登记「谁名下还有活」，identity 记录转移结果。
-- 刻意**不建**指向 trade / workflow 的外键：跨服务 FK 是 AGENTS.md 明令禁止的。
CREATE TABLE offboarding_responsibility_item (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL REFERENCES tenant(id),
  case_id             uuid NOT NULL,
  -- DOCUMENT 在办单据 / APPROVAL 审批单。
  item_kind           text NOT NULL CHECK(item_kind IN ('DOCUMENT','APPROVAL')),
  -- 登记方服务（trade / workflow 等），与 source+reference_id 一起定位真实条目。
  source_system       text NOT NULL CHECK(source_system ~ '^[a-z_]{2,32}$'),
  reference_id        uuid NOT NULL,
  reference_no        varchar(64) NOT NULL DEFAULT '',
  title               varchar(200) NOT NULL,
  -- 原责任人 = 被停用的成员。
  owner_membership_id uuid NOT NULL,
  -- 接手人可空：还没决定接手人时条目停在 PENDING，不允许「先结案再补」。
  successor_membership_id uuid,
  status              text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','TRANSFERRED')),
  registered_at       timestamptz NOT NULL DEFAULT now(),
  transferred_at      timestamptz,
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, source_system, reference_id),
  -- 台账必须跟着离职记录一起留：RESTRICT，不允许级联清掉。
  FOREIGN KEY (tenant_id, case_id) REFERENCES offboarding_case(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, owner_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, successor_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  -- 状态与时刻必须一致：TRANSFERRED 一定有接手人和转移时刻，PENDING 一定没有。
  CHECK((status='TRANSFERRED') = (successor_membership_id IS NOT NULL)),
  CHECK((status='TRANSFERRED') = (transferred_at IS NOT NULL))
);

-- 待办与任务的回收台账。待办与任务不是同一件事：待办可关闭，任务通常改派给别人继续做。
CREATE TABLE offboarding_reclaim_item (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL REFERENCES tenant(id),
  case_id             uuid NOT NULL,
  item_kind           text NOT NULL CHECK(item_kind IN ('TODO','TASK')),
  source_system       text NOT NULL CHECK(source_system ~ '^[a-z_]{2,32}$'),
  reference_id        uuid NOT NULL,
  title               varchar(200) NOT NULL,
  holder_membership_id uuid NOT NULL,
  -- 改派接手人：TASK 走改派，TODO 走直接回收。
  successor_membership_id uuid,
  reclaimed_by_membership_id uuid,
  status              text NOT NULL DEFAULT 'PENDING'
                        CHECK(status IN ('PENDING','RECLAIMED','REASSIGNED')),
  registered_at       timestamptz NOT NULL DEFAULT now(),
  reclaimed_at        timestamptz,
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, source_system, reference_id),
  FOREIGN KEY (tenant_id, case_id) REFERENCES offboarding_case(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, holder_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, successor_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, reclaimed_by_membership_id) REFERENCES membership(tenant_id, id) ON DELETE RESTRICT,
  CHECK((status IN ('RECLAIMED','REASSIGNED')) = (reclaimed_at IS NOT NULL)),
  -- 改派必须指定接手人，直接回收不许指定——否则「转给谁」这个问题永远没有答案。
  CHECK((status='REASSIGNED') = (successor_membership_id IS NOT NULL)),
  CHECK((status<>'REASSIGNED') OR (reclaimed_by_membership_id IS NOT NULL))
);

-- 同一个成员同一租户同时只能有一条在办离职（COMPLETED/REVERTED 的可以再次发起）。
CREATE UNIQUE INDEX offboarding_case_open ON offboarding_case(tenant_id, membership_id)
  WHERE status='OPEN';
CREATE INDEX offboarding_case_list ON offboarding_case(tenant_id, status, created_at);
CREATE INDEX offboarding_case_member ON offboarding_case(tenant_id, membership_id);
CREATE INDEX offboarding_responsibility_case ON offboarding_responsibility_item(tenant_id, case_id, status);
CREATE INDEX offboarding_responsibility_pending ON offboarding_responsibility_item(tenant_id, owner_membership_id)
  WHERE status='PENDING';
CREATE INDEX offboarding_reclaim_case ON offboarding_reclaim_item(tenant_id, case_id, status);
CREATE INDEX offboarding_reclaim_pending ON offboarding_reclaim_item(tenant_id, holder_membership_id)
  WHERE status='PENDING';

DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['offboarding_case','offboarding_responsibility_item','offboarding_reclaim_item'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- 「停用而非删除」的第二道防线（第一道是权限层不给 DELETE）。
-- 成员只要留下一条离职记录就**不允许物理删除**：他此前的操作是审计追溯的依据，
-- 删掉人会让审计日志里的操作者指向一个不存在的身份。与 V5 的 guard_legal_entity_delete 同一套路。
CREATE FUNCTION guard_member_offboarding_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cases bigint;
BEGIN
  SELECT count(*) INTO cases FROM offboarding_case
   WHERE tenant_id=OLD.tenant_id AND (membership_id=OLD.id OR operator_membership_id=OLD.id);
  IF cases > 0 THEN
    RAISE EXCEPTION '该成员仍关联 % 条离职与停用记录，请改为停用而不是删除', cases USING ERRCODE='23503';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER membership_offboarding_delete_guard
  BEFORE DELETE ON membership FOR EACH ROW EXECUTE FUNCTION guard_member_offboarding_delete();

-- 17.2.2.3 离职与停用：读路径按页面权限，组织变更沿用「仅 OWNER」纪律（控制器侧 call(...,true,...)）。
INSERT INTO page_resource(code,name) VALUES ('identity.offboarding','离职与停用')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.offboarding.'||action,'identity.offboarding',action
FROM unnest(ARRAY['read','create','update']::text[]) action
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code LIKE 'identity.offboarding.%' ON CONFLICT DO NOTHING;

-- 刻意**不授予 DELETE**：本模块没有物理删除语义，三张表的行只进不出。
GRANT SELECT,INSERT,UPDATE ON offboarding_case TO identity_app;
GRANT SELECT,INSERT,UPDATE ON offboarding_responsibility_item TO identity_app;
GRANT SELECT,INSERT,UPDATE ON offboarding_reclaim_item TO identity_app;