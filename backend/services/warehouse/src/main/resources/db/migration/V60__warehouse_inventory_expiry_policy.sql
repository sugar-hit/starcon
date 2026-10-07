-- ===========================================================================
-- 7.3.1.2 效期与先进先出：出库分配策略（策略开关 + 提前天数阈值）
-- ===========================================================================
-- 蓝图原文：「对有保质期的商品，按 FEFO（先到期先出）或 FIFO 分配出库批次，预警临期与过期库存」，
-- 输入「批次失效日期；出库分配策略（FEFO/FIFO/指定批次）；预警提前天数」，
-- 输出「推荐出库批次；临期/过期预警清单」。
--
-- ---------------------------------------------------------------------------
-- 为什么只有一张新表，效期数据本身不建表
-- ---------------------------------------------------------------------------
-- 效期的两个事实来源在 V2 与 V57 里已经坐实，本迁移**不重复建模**：
--   inventory_lot.expires_on   失效日期（date 业务日期，V2）
--   stock_bucket               库位 × 批次粒度的数量事实层（V2）
-- FEFO/FIFO 推荐要排序的正是 stock_bucket 上的可发量，所以「效期维度」无需新表。
--
-- 真正缺的是**分配策略**：FEFO/FIFO/指定批次是租户对某个 SKU 的经营选择，
-- 「提前 N 天预警」是可调阈值。两者都是配置，不是事实，所以单独一张策略表。
--
-- ---------------------------------------------------------------------------
-- 为什么策略表要建在这一层，而不是塞进 inventory_lot
-- ---------------------------------------------------------------------------
-- 策略的主体是 **SKU**，不是批次：同一箱奶的每一批都用同一条 FEFO 策略。
-- 塞进 inventory_lot 会让「改一次策略」变成「逐批改」，
-- 而且新建批次时必须重新抄一遍策略 —— 漏抄一批，那批货就静默退回先进先出。
--
-- ---------------------------------------------------------------------------
-- enabled 开关：蓝图「第一期是否启用可延后，但维度已保留」
-- ---------------------------------------------------------------------------
-- 这句话落到 schema 上就是这一列。默认 **false**（未启用）：
--   - 效期/FIFO 是食品化妆品类目的**硬需求**，但对非效期品类是多余开销；
--   - 默认打开等于替所有租户做了「这个 SKU 受效期管理」的判断，而系统并不知道
--     哪个 SKU 是效期品（catalog 的品类属性在本服务之外，拿不到也不该复制）。
-- 所以维度先留好，谁家的效期品由谁自己开；没开时出库退回「按库位顺序取可用量」，
-- 响应里 policyEnabled=false 如实报出，不假装策略已生效。
--
-- ---------------------------------------------------------------------------
-- 撤销 DELETE：停用是 enabled=false，不是删行
-- ---------------------------------------------------------------------------
-- 与 7.2.2.1 可售量口径同一纪律（作废而非删除）。策略行是**审计对象**：
-- 「这个 SKU 曾经按 FEFO 出过货」这件事要能从行本身看出来。
-- 禁用走 enabled=false，行永久保留；不给 DELETE 权限，让「删掉一条策略」在物理上不成立。
-- ===========================================================================

CREATE TABLE inventory_expiry_policy (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- SKU 是 catalog 的逻辑 ID：不建外键（跨服务 FK 违反模块边界纪律，与 V2 同口径）。
  -- 一个 SKU 至多一条策略：策略的主体是 SKU，重复的行只会让「当前该用哪条」变得不确定。
  sku_id uuid NOT NULL,

  -- 出库分配策略。三值互斥且必须说清差别，界面不能只给一个下拉让人猜：
  --   FEFO         先到期先出：按 expires_on 升序。非效期批次不参与排序。
  --   FIFO         先进先出：按收货日期/建行时间升序，与效期无关。
  --   SPECIFIED_LOT 指定批次：永远从指定批次取，用在「这箱货要单独走」的场景。
  strategy varchar(16) NOT NULL DEFAULT 'FEFO'
    CHECK(strategy IN ('FEFO','FIFO','SPECIFIED_LOT')),

  -- 策略开关。见文件头：默认不启用。
  enabled boolean NOT NULL DEFAULT false,

  -- 预警提前天数：剩余有效期 <= 该值的批次进「临期」清单。
  -- **必须可配置**：食品与日化的临期窗口差一个数量级，写死 30 天对两边都是错的。
  -- 上限 3650：超过十年的保质期在本行业没有意义，不设上限就会有人填 999999
  -- 把「临期」变成「全部批次都临期」，那样这个字段就废了。
  lead_days integer NOT NULL DEFAULT 30
    CHECK(lead_days>=0 AND lead_days<=3650),

  -- 指定批次策略下固定从哪一批出。复合外键保证它真实存在于本租户的 inventory_lot 里
  -- （inventory_lot 有 UNIQUE(tenant_id,id)，可作复合 FK 目标）。
  -- 该批次是否属于本 sku 由下方触发器 guard_expiry_policy_lot_sku 兜底 ——
  -- FK 只能保证「批次存在」，保证不了「批次是这个 SKU 的」，而后者错配会让出库从别的品取货。
  specified_lot_id uuid,

  -- 策略备注：不属于结构化字段的补充说明（为什么这个 SKU 要走 FIFO 之类）。
  -- NOT NULL DEFAULT ''：「没填」与「填了空串」在业务上是同一件事。
  notes text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,sku_id),
  FOREIGN KEY(tenant_id,specified_lot_id) REFERENCES inventory_lot(tenant_id,id),

  -- 指定批次策略必须有指定批次；反向不禁止（FEFO 策略下留一个 specified_lot_id 是无害的冗余，
  -- 代价远小于逼着每次切换策略都去清空一个用不到的列）。
  CHECK(strategy<>'SPECIFIED_LOT' OR specified_lot_id IS NOT NULL)
);

-- 指定批次的 SKU 归属：DB 级守卫，不靠应用层自觉。
CREATE FUNCTION guard_expiry_policy_lot_sku() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_sku uuid;
BEGIN
  IF NEW.specified_lot_id IS NULL THEN RETURN NEW; END IF;
  SELECT l.sku_id INTO owner_sku FROM inventory_lot l
   WHERE l.tenant_id=NEW.tenant_id AND l.id=NEW.specified_lot_id;
  IF owner_sku IS DISTINCT FROM NEW.sku_id THEN
    RAISE EXCEPTION '指定批次 % 不属于该 SKU：批次 % 归属的 SKU 是 %，与策略的 SKU % 不一致。'
      '从别的品取货会让拣货单指向一个根本不是这个 SKU 的批次',
      NEW.specified_lot_id, NEW.specified_lot_id, owner_sku, NEW.sku_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_expiry_policy_lot_sku_membership
  BEFORE INSERT OR UPDATE ON inventory_expiry_policy
  FOR EACH ROW EXECUTE FUNCTION guard_expiry_policy_lot_sku();

-- 策略行**不可删除**：停用走 enabled=false。与 7.2.2.1 可售量口径的「作废而非删除」同一纪律。
CREATE FUNCTION guard_expiry_policy_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '效期分配策略不允许删除：停用请把 enabled 置为 false。'
    '策略行是「这个 SKU 曾经按 FEFO 出过货」的审计凭据，删掉就再也说不清了。'
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER inventory_expiry_policy_no_delete BEFORE DELETE ON inventory_expiry_policy
  FOR EACH ROW EXECUTE FUNCTION guard_expiry_policy_no_delete();

-- ===========================================================================
-- 索引：只为本模块的真实查询建
-- ===========================================================================
-- 1) 效期预警清单的主排序是「按失效日期升序（最紧急的排最前）」，
--    按 SKU 下钻时也要按失效日期取数 —— 复用 V57 的 inventory_lot_expiry 部分索引
--    （tenant_id, expires_on, sku_id）WHERE expires_on IS NOT NULL，本迁移不重复建设。
-- 2) 策略列表的默认筛选是「看启用了哪些」，部分索引只收 enabled=true 的行。
CREATE INDEX inventory_expiry_policy_enabled
  ON inventory_expiry_policy(tenant_id, sku_id) WHERE enabled;
-- 3) 按策略类型筛（「哪些 SKU 走 FIFO」）—— 分配推荐读策略时也走这个唯一键，无重复建设必要。

-- ===========================================================================
-- 多租户：RLS + 授权（与 V2 同一套写法，本表是 V2 之后的新表，必须显式补齐）
-- ===========================================================================
ALTER TABLE inventory_expiry_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_expiry_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON inventory_expiry_policy
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE ON inventory_expiry_policy TO warehouse_app;
-- 停用不是删除：连运行角色都不给 DELETE，兜底在权限层而不只在触发器里。
REVOKE DELETE ON inventory_expiry_policy FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V2 同口径）。
REVOKE ALL ON inventory_expiry_policy FROM starcon_app;