-- 5.1.2.1 优先级与冲突检测：租户可配置的**价目表优先级规则**。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：定义多张价目表的适用优先级，保存时检测冲突（同 SKU + 同店铺 + 同站点 + 同时段生效的多个价目），
--             冲突即拒绝并列出冲突明细。
--   **输入**：适用范围配置（法人/店铺/站点）；现有生效价目集合。
--   **输出**：冲突检测结果；最终的生效价目解析规则。
--   **关系**：上游 = 5.1.1.1；下游 = 5.3.1.1、5.4 调价。**这是价格正确性的守门员**：
--             允许多价目但必须能唯一解析出"这个 SKU 在这个店铺此刻该卖多少"。
--
-- ============================================================ 第一节：这张表归谁
--
-- 蓝图 05 卷首写：「`catalog` 服务拥有 `price_list` `price_list_item`」。
-- 本迁移是 `price_list_priority` 的**唯一写入所有者**（优先级规则是本模块的产物）。
--
-- 🔴 **本迁移不碰 `price_list` / `price_list_scope` 的表结构**：
--   那两张表是 5.1.1.1 的写入所有权（V431）。本模块只**读**它们，
--   在服务层做冲突判定与解析，不改它们的列、不加它们的约束。
--
-- 🔴 **本迁移不建 `price_list_item`**：价目项是 5.1.1.2 的写入所有权。
--   ⇒ 「同 SKU」这一维度的**价目项级**判定不在本模块（详见第五节）。
--
-- ============================================================ 第二节：优先级规则的形状
--
-- 「优先级是租户可配置的，不是代码常量 —— 各租户的销售场景不同。」
--
-- ⇒ 规则以**行**存在：`price_list_priority` 一行 = 「这张价目表在某个上下文里排第几」。
--
-- 上下文用 5.1.1.1 的同一套适用范围词表（法人 / 店铺 / 站点）加一个 `GLOBAL`：
--
--   | scope_type   | scope_ref 的含义     | 生效范围                             |
--   |--------------|---------------------|--------------------------------------|
--   | GLOBAL       | 固定写 `*`          | 该价目表的**默认优先级**（所有上下文） |
--   | LEGAL_ENTITY | 法人编码            | 该法人下的全部店铺与站点              |
--   | SHOP         | 店铺编码            | 该店铺（含它名下的站点）              |
--   | SITE         | 站点码              | 该站点                                |
--
-- 🔴 **每张参与解析的价目表必须有一条 GLOBAL 规则**（服务层强制）：
--   没有它，价目表在某个上下文里就没有优先级 ⇒ 它不参与解析。
--   服务层要求「首次保存的规则必须是 GLOBAL」，之后 GLOBAL 只能改不能删。
--
-- 🔴 **数字越小 = 优先级越高**（1 是最高优先级），与国内 ERP 的习惯一致。
--
-- 🔴 **GLOBAL 与 scope_ref='*' 严格绑定**（列级 CHECK）：
--   写成 `scope_type='GLOBAL'` + `scope_ref='AMZ-US'`（或反过来）会写进一条
--   自相矛盾的规则 —— 那是「全局优先级只对某个店铺生效」的骗人写法，
--   在 DB 层就被挡住（23514）。
--
-- ============================================================ 第三节：为什么没有外键
--
-- 与 V431 同一手法（零外键 + 触发器 guard）：
-- 给**既有表**加 FOREIGN KEY 会打破同服务既有测试的 TRUNCATE ⇒ 整批用例 Errors。
-- 触发器的判据与外键等价（引用不存在 ⇒ 报 23514），但不参与 TRUNCATE 的依赖图。
CREATE FUNCTION guard_price_list_priority() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM price_list p
     WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.price_list_id) THEN
    RAISE EXCEPTION 'Price list priority % must reference an existing price list',
      NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

-- ============================================================ 第四节：表结构
CREATE TABLE price_list_priority (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,

  -- 所属价目表。**不建外键**（第三节），引用完整性由触发器 guard 保证。
  price_list_id uuid NOT NULL,

  -- 上下文类型（第二节）。
  scope_type text NOT NULL CHECK(scope_type IN ('GLOBAL','LEGAL_ENTITY','SHOP','SITE')),
  -- 上下文取值：法人编码 / 店铺编码 / 站点码；GLOBAL 时固定写 '*'。
  scope_ref varchar(64) NOT NULL,

  -- 🔴 **数字越小优先级越高**（第二节）。下限 0 允许「排最后」，上限 9999 防呆。
  priority integer NOT NULL CHECK(priority >= 0 AND priority <= 9999),

  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,

  UNIQUE(tenant_id, id),
  -- 同一张价目表里同一个上下文只登记一条规则。
  UNIQUE(tenant_id, price_list_id, scope_type, scope_ref),

  CHECK(btrim(scope_ref) <> ''),
  -- GLOBAL ⇔ scope_ref='*'（第二节）。
  CHECK((scope_type = 'GLOBAL') = (scope_ref = '*'))
);

-- 取一张价目表的全部规则（页面上的规则表：主查询路径）。
CREATE INDEX price_list_priority_by_list
  ON price_list_priority(tenant_id, price_list_id, scope_type, scope_ref);
-- 按上下文反查规则（「这个店铺上各价目表排第几」—— 解析与冲突检测的主查询路径）。
CREATE INDEX price_list_priority_by_scope
  ON price_list_priority(tenant_id, scope_type, scope_ref, price_list_id);
-- 「同优先级的价目表有哪些」—— 冲突检测（并列 ⇒ 不可解析）的主查询路径。
CREATE INDEX price_list_priority_by_rank
  ON price_list_priority(tenant_id, priority, price_list_id);

-- 🔴 触发器建在**表之后**（第三节的函数在前）：PG 要求触发器挂在已存在的表上。
CREATE TRIGGER price_list_priority_guard
  BEFORE INSERT OR UPDATE ON price_list_priority
  FOR EACH ROW EXECUTE FUNCTION guard_price_list_priority();

-- ============================================================ 第五节：「同 SKU」这一维度的边界
--
-- 蓝图的冲突判据是「同 SKU + 同店铺 + 同站点 + 同时段生效的多个价目」，
-- 其中「同 SKU」要求知道**哪些 SKU 落在哪张价目表里** —— 那是 `price_list_item`
-- （价目项，**5.1.1.2 的写入所有权**）的事实。
--
-- 🔴 **5.1.1.2 尚未交付 ⇒ 本表不存在 ⇒ 价目项级冲突检测本模块不覆盖。**
--   本模块实现的是**价目级**冲突检测（同一 SKU 落在两个生效价目表的适用范围交叠里）：
--   两张价目表覆盖同一（店铺, 站点）上下文且生效区间重叠 ⇒ 冲突。
--
-- 本模块**不假装**检测了价目项级冲突，也不静默返回「无冲突」：
--   * 服务层的能力声明里写明「同 SKU 维度未覆盖」及原因；
--   * 冲突报告里 `skuDimensionCovered = false` 并附未覆盖原因；
--   * 解析结果里 `skuCoverage.covered = false` 并附未覆盖原因。
--   5.1.1.2 交付后，由它自己的写入路径在同一张 `price_list_priority` 上补齐这一维度，
--   本模块**不改**它的表结构与写入逻辑。
--
-- ============================================================ 第六节：RLS 与授权
--
-- 租户级业务表（优先级规则是这个租户自己的定价策略）：
-- `tenant_id uuid NOT NULL` + `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`
-- + isolation 策略（照抄 V431 第八节的 `DO $$ FOREACH` 写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的优先级规则，
-- 而优先级规则写着一个租户「哪张价目表压过哪张价目表」—— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'price_list_priority'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_priority TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON price_list_priority TO starcon_app;
