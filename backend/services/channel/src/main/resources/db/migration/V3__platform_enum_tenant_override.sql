-- ---------------------------------------------------------------------------
-- 2.1.2.1 平台枚举映射的租户覆盖层
-- ---------------------------------------------------------------------------
-- 上一版只落了 platform_enum_mapping：一张**全局只读码表**，没有任何写入方，
-- 也没有租户维度。结果是映射永远为空、解析永远返回"未映射"，2.1.2.1 实际不可用。
--
-- 蓝图 2.1.2.1 的原文是"平台默认 + 租户覆盖"两层：
--   · 平台默认 —— 随平台适配器发布的全局码表，所有租户一致，因此**不允许租户改写**。
--     租户改写它等于污染别的租户的订单状态机、售后原因与轨迹状态。
--   · 租户覆盖 —— 某个租户要把平台 A 的 canceled 归到内部的"用户取消"，
--     只能登记在自己的覆盖层，全局默认保持原样。
--
-- 生效顺序：租户覆盖 > 平台默认 > 未映射。缺失一律显式暴露，绝不兜底。

CREATE TABLE platform_enum_override (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  platform_id    uuid NOT NULL REFERENCES platform(id),
  enum_kind      varchar(64) NOT NULL,
  external_value varchar(128) NOT NULL,
  internal_value varchar(64) NOT NULL,
  UNIQUE(tenant_id, platform_id, enum_kind, external_value),
  CHECK(btrim(enum_kind)<>'' AND btrim(external_value)<>'' AND btrim(internal_value)<>'')
);

-- 覆盖层与全局码表的区别就在 tenant_id：它必须和其他业务表一样受 RLS 约束，
-- 否则任何登录租户都能改别人的映射。
ALTER TABLE platform_enum_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_enum_override FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_enum_override
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- 覆盖本身是租户的业务配置，绩效与合规上都要能查"谁在什么时候改了映射口径"。
CREATE INDEX platform_enum_override_lookup ON platform_enum_override(tenant_id, platform_id, enum_kind);

DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN CREATE ROLE channel_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_enum_override TO channel_app;
-- platform_enum_mapping 继续是只读码表：V1 末尾的 blanket GRANT 把它连写权限一起给了，
-- 这里显式收回，否则"全局默认不可被租户改写"这条纪律只在代码里成立、库层是敞开的。
REVOKE INSERT,UPDATE,DELETE ON platform_enum_mapping FROM channel_app;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
