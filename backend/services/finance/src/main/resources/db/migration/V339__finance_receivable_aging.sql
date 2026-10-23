-- ===========================================================================
-- V339 · finance_aging_bucket —— 12.1.1.2 应收账龄
-- ===========================================================================
--
-- 蓝图 12-finance.md 原文：
--
--   ### 12.1.1.2 应收账龄
--   - **职责**：按账龄区间（未到期/1-30/31-60/61-90/90+）统计应收分布，
--     支持按店铺、客户、币种维度查看，识别逾期集中风险。
--   - **输入**：应收单集合；账龄区间配置。
--   - **输出**：账龄分布报表 + 逾期清单。
--   - **关系**：上游 = 12.1.1.1；下游 = 1.3 催收预警、12.1.1.3。
--     **跨境应收的最大风险是平台争议冻结**，账龄报表需单独标识"争议中"金额。
--
-- ---------------------------------------------------------------------------
-- 0. 本迁移**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不 ALTER V338 的任何一张表**：本文件是纯新增（一张新表 + 索引 + 授权）。
--     V338 是 12.1.1.1 应收单管理的归属文件，改它等于让两个模块共写一张表 ——
--     12.1.1.1 的单据结构（due_date / dispute_flag / status / settled_amount）
--     本模块**只读**，账龄报表不往单据上写任何东西。
--
--   * **不新建「已核销」的判断口径**：核销金额与核销状态**只有一个出处** ——
--     V338 的 `finance_receivable_line.settled_amount`（行级）与
--     `guard_finance_receivable_settlement` 触发器派生出的单头 `settled_amount` /
--     `status`。本模块**只读**这两个字段，不新增任何"是否已核销"的标记。
--     核销的**写路径**属 12.2.1.2（未交付），坏账核销属 12.1.1.3（未交付）——
--     本模块既不写它们，也不假装它们存在。
--
--   * **不实现催收预警**：下游 1.3.2.1 预警规则（催收预警）属卷 01 前端聚合层，
--     本模块只提供它需要的账龄分布与逾期清单。
--
-- ---------------------------------------------------------------------------
-- 1. 为什么要有「账龄区间配置」这张表（而不是把五档写死）
-- ---------------------------------------------------------------------------
--
-- 蓝图的「输入」明确写着两条：**应收单集合** + **账龄区间配置**。
-- 只收「应收单集合」而把区间写死，等于替用户把口径定了；
-- 而完全不给配置口，蓝图的那条输入就是空的。
--
-- 所以这里给一张**租户自己维护**的区间配置表：
--
--   * 一行 = 一个账龄区间；
--   * `code` 是租户自定的**稳定键**（大写字母 / 数字 / 下划线），
--     报表与前端按它引用区间，不受标签改名影响；
--   * `overdue_from_days` / `overdue_to_days` 圈定**逾期天数**区间（含两端）；
--     `overdue_from_days IS NULL` 表示「未到期」档（到期日 >= 账龄基准日）；
--     `overdue_to_days IS NULL` 表示该档**无上界**（如「逾期 90 天以上」）；
--   * **没配置过** ⇒ 生效的是**蓝图给定的五档**（未到期 / 1-30 / 31-60 / 61-90 /
--     90+），响应里会如实标出 `source = 'BLUEPRINT_DEFAULT'`，
--     不会假装「这是你配的」。
--
-- ⚠️ **区间必须连续覆盖 1 天起**（服务层校验，DB 兜底）：
--   档位之间留缝 ⇒ 落在缝里的应收**不属于任何一档**，账龄分布会**凭空少钱**；
--   档位重叠 ⇒ 后面的档永远命中不到，分布会**永远少一档**。
--   两种都是"报表算得出来但算错了"，所以：
--     · 服务层在写配置时整体校验（不合法直接拒绝，并说清哪里不合法）；
--     · DB 用两个**部分唯一索引**兜住最要命的两种错配
--       （两个「未到期」档 / 两个「无上界」档）。
--
-- 「90+」这一档的**边界**：蓝图写的是「90+」，而前一档是「61-90」——
--   两档在 90 天上重叠。本仓 12.1.1.1 的 `GET /finance/receivables/aging`
--   已经是按 `<= 90 → 61-90 档、否则 90+ 档` 实现的，
--   所以本模块**沿用同一条边界**（90+ 档从第 91 天起算），
--   否则同一个租户在两个模块里会看到两套不同的账龄。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. finance_aging_bucket —— 账龄区间配置（租户自己维护）
-- ---------------------------------------------------------------------------
CREATE TABLE finance_aging_bucket (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 区间编码：租户自定的稳定键。**只允许大写字母 / 数字 / 下划线**，
  -- 因为它会被拼进报表 SQL 的 CASE 分支（值必须能安全地当字面量用）。
  code varchar(32) NOT NULL CHECK(code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
  label varchar(64) NOT NULL CHECK(btrim(label) <> ''),
  -- 逾期天数下界（含）。NULL = 「未到期」档（到期日 >= 账龄基准日）。
  overdue_from_days int,
  -- 逾期天数上界（含）。NULL = 无上界。
  overdue_to_days int,
  sort_order int NOT NULL DEFAULT 0,
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 一个租户内区间编码唯一：改配置是「替换这一份」，不是「多存一份」。
  UNIQUE(tenant_id, code),
  -- 区间形状只有两种合法形态：
  --   ① 未到期档：两个边界都空；
  --   ② 逾期档：必须有下界且 >= 1，上界可空（= 无上界）。
  CONSTRAINT finance_aging_bucket_range_ck CHECK (
    (overdue_from_days IS NULL AND overdue_to_days IS NULL)
    OR (overdue_from_days IS NOT NULL AND overdue_from_days >= 1
        AND (overdue_to_days IS NULL OR overdue_to_days >= overdue_from_days))
  )
);

-- ---------------------------------------------------------------------------
-- 2. 数据库不变量（把「档位不能配错」钉在 DB 层）
-- ---------------------------------------------------------------------------
--
-- (a) **最多一个「未到期」档**：两个未到期档 ⇒ 有一档永远命中不到。
-- (b) **最多一个「无上界」的逾期档**：两个无上界档 ⇒ 逾期更久的应收
--     会被前一档吃掉，或者分布里凭空少一档。
--
-- 「连续覆盖 1 天起」这条**不是**索引能表达的（要跨行比较），
-- 由服务层在写入时整体校验；这里两条索引兜住最容易被并发写坏的两条。
CREATE UNIQUE INDEX finance_aging_bucket_one_not_due
  ON finance_aging_bucket(tenant_id) WHERE overdue_from_days IS NULL;

CREATE UNIQUE INDEX finance_aging_bucket_one_unbounded
  ON finance_aging_bucket(tenant_id)
  WHERE overdue_from_days IS NOT NULL AND overdue_to_days IS NULL;

-- 报表按「档位顺序」读配置。
CREATE INDEX finance_aging_bucket_by_order
  ON finance_aging_bucket(tenant_id, sort_order, code);

-- ===========================================================================
-- 3. 多租户（RLS isolation）+ 授权
-- ===========================================================================
--
-- 与 V338 同一套谓词：`tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid`，
-- ENABLE **与 FORCE** 都要 —— 只有 FORCE 才挡得住「运行角色被误授 owner」。
DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY['finance_aging_bucket'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON finance_aging_bucket TO finance_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON finance_aging_bucket TO starcon_app;

-- ===========================================================================
-- 4. 留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **1.3.2.1 预警规则（催收预警，未交付）**：本模块的账龄分布与逾期清单
--     就是它要的输入（蓝图写死「应收超期 Y 天」是它的规则之一）。
--     本模块**只提供数据**，不生成通知、不写待办 —— 那是卷 01 的事。
--
--   * **12.1.1.3 坏账与核销（未交付）**：争议金额已由 `finance_receivable.dispute_flag`
--     单列进账龄分布；争议事实来自 11.4.1.1 纠纷（trade 服务），当前**只支持人工标注**，
--     **未自动联动**。
--
--   * **12.2.1.2 核销（未交付）**：核销写路径未开放。本模块统计的是
--     `amount - settled_amount`（未核销余额），核销一旦落地，账龄分布会**自动**
--     跟着变小 —— 不需要改本表的任何一列，也不需要改报表 SQL。
