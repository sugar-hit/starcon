-- 6.1.1.1 供应商列表：**采购视角**的供应商清单与运营动作。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：维护供应商档案清单：编码、名称、联系方式、合作状态、供货 SKU 数、采购金额累计、
--             准时交付率、质量合格率，支持筛选与导出。
--   **输入**：查询条件（状态、类目、合作等级）；用户数据范围。
--   **输出**：供应商列表 + 关键指标；点击进入详情。
--   **关系**：上游 = 3.6.1.2 角色档案；下游 = 6.1.1.2 详情、6.3 采购单、6.5 对账。
--             **供应商数据主数据在 03，本模块呈现采购视角的视图与运营动作，
--             避免同一实体两处可编辑。**
--
-- ============================================================ 第一节：加粗那句话是硬要求，本迁移第一节就是它
--
-- 蓝图那句「避免同一实体两处可编辑」否掉的是**建一张 supplier 表**：
--   supplier(id, code, name, email, phone, supply_status, cooperation_level, ...)
-- 那个形状在本域里具体会坏在哪里 —— 上游 3.6.1.2 已经交付了 party / party_role /
-- party_supplier_profile 三张表，「编码 / 名称 / 联系方式」在 party 上，
-- 「合作等级」在 party_supplier_profile 上。再建一份意味着：
--
--   * 改名要改两处，而只有一处是 3.6.1.1 写权限的 —— 采购侧改了名字，
--     11 售后单上冻结的供应商名与 12 应付里按 party_id 对上的对手方名称就对不上了；
--   * 06 采购单带出合作等级时读 party_supplier_profile，而 6.1.1.1 列表按自己的表显示，
--     同一时刻界面上会出现两个等级，且**没有任何东西会报错**；
--   * 3.6.1.1 的「一个主体最多一条供应商身份」这条唯一性被绕过，
--     会出现两个都自称供应商的 party id，06 采购单只能二选一，另一条数据静默丢失。
--
-- 所以本迁移**没有 supplier 表**，一个字都没有。字段分三处，各有唯一所有者：
--
--   | 蓝图职责里的字段        | 存在哪                        | 写入所有者 |
--   |-----------------------|-------------------------------|-----------|
--   | 编码 / 名称 / 联系方式  | party（3.6.1.1）              | 3.6.1.1   |
--   | 合作等级                | party_supplier_profile（3.6.1.2）| 3.6.1.2   |
--   | 主体状态 / 拉黑        | party.status（3.6.1.1）        | 3.6.1.1   |
--   | **合作状态（采购侧）**  | vendor_supply_profile（本表）  | 本模块    |
--   | **类目归属**            | vendor_supply_profile（本表）  | 本模块    |
--   | **到货事实 / 金额**     | vendor_delivery_record（本表） | 本模块    |
--   | **质检事实**            | vendor_quality_record（本表）  | 本模块    |
--   | **供货 SKU 数**         | 派生自 supplier_quote（3.6.2.1）| 不落库    |
--
-- 「合作状态」与 party.status **是两个不同的东西**，这是本表存在的第一理由：
-- party.status 说的是「这个往来单位本身还认不认」（ACTIVE / INACTIVE / BLACKLISTED），
-- 3.6.1.1 写；本表的 supply_status 说的是「**采购这条线上**还跟不跟他做」 ——
-- 一个战略供应商可能主体照常 ACTIVE（11 售后单还要引用他），
-- 但采购侧已经暂停下单（试单不通过、资质过期、报价全部失效）。
-- 把这两件事折叠成一个字段的后果是：为了表达「暂停采购」去把主体停用，
-- 于是 11 售后单找不到这个供应商。**所以它必须落在自己的表上。**
--
-- 「类目归属」同理是采购视角独有的：party 上没有任何类目字段，
-- 而 06 采购拆单、6.5 对账按类目分组都要问「这个供应商主要供哪一类」。
--
-- ============================================================ 第二节：四个关键指标各自的口径（分子 / 分母 / 从哪张表算起）
--
-- 四个指标**没有一个是存下来的字段**，全部是列表查询里的 SQL 聚合。
-- 存比率的那张表（「supplier.on_time_rate = 0.92」）会在第一次改口径时变成假数据：
-- 没有任何东西会提醒它「上个月的 92% 是按另一个分母算的」。
--
--   **M1 供货 SKU 数** —— 派生自 supplier_quote（3.6.2.1 已交付，catalog 自有表）。
--       SQL： count(distinct sq.sku_id) from supplier_quote sq
--             where sq.tenant_id = v.tenant_id and sq.supplier_role_id = v.role_id
--               and sq.status = 'ACTIVE' and sq.deleted... （无 deleted 列，见下）
--       分子分母：这是一个**计数**而不是比率，没有分子分母。
--       只数 status='ACTIVE'：停用的报价不再代表「他供得上这个 SKU」，
--       把它算进去会让一个已经把货下架的供应商仍然显示供货 SKU 数很高。
--       **与「在售 SKU 数」刻意不同**：本表不引入有效期过滤
--       （valid_from/valid_to 会让「今天供得上」与「曾经供得上」两个口径
--       在列表页上混成一个数），口径就是「有效报价覆盖的不同 SKU 数」。
--
--   **M2 采购金额累计** —— sum(vd.amount) group by vd.currency，取自 vendor_delivery_record。
--       分子：窗口内到货记录的金额之和；分母：无（这是累计量不是比率）。
--       口径是**已到货金额**，不是下单金额 —— 下单金额属于 6.3 采购单的表，
--       那是 trade 服务的写入所有权，跨服务读表是 AGENTS.md 明令禁止的。
--       到货是采购侧在本服务内可举证的事实（收货单 + 质检 + 金额同时落在这里）。
--       **按币种分组返回，不跨币种相加**：本库没有汇率表，
--       ISO 4217 与折算是 12 财务的资产（与 V30 第七节 R7 同一个道理）。
--       把 100 USD + 90 EUR 直接相加得到 190 是一个**看起来对、实际错**的数字，
--       而它错得极难被发现 —— 量级差不多、没有任何异常、报表照样能出。
--       所以返回 [{currency, amount}, ...]，需要排序时取该供应商金额最大的那一笔
--       （并在响应里写明），而不是编一个汇率。
--
--   **M3 准时交付率** —— vendor_delivery_record 的窗口内计数比。
--       分子：count(*) filter (where received_date <= due_date)
--       分母：count(*)（窗口内全部到货记录）
--       **不存 on_time 布尔列**：准时与否由 received_date 与 due_date 两个事实推出，
--       存一份推导结果就多一个可能与事实不一致的副本（改期后忘了同步就是错的比率）。
--       分母为 0 时返回 **null 而不是 0**：「没交货过」与「交货全部延期」是相反的两件事，
--       折叠成 0 会让一个还没开始供货的 TRIAL 供应商看起来是最差的那个。
--
--   **M4 质量合格率** —— vendor_quality_record 的窗口内数量比。
--       分子：sum(qualified_qty)；分母：sum(inspected_qty)。
--       用**数量比**而不是「合格批次数 / 检验批次数」：一批 10000 件里合格 9999 件
--       与一批 3 件里合格 1 件，按批次算是同一个结果（1/2），
--       按数量算才是 99.99% 与 33.33% —— 后者才是采购真正承担的风险大小。
--       分母为 0 时同样返回 null（理由同 M3）。
--
--   **M3 / M4 是有时间窗的统计**，所以列表接口带 metricFrom / metricTwo 两个参数，
--   并把**实际生效的窗口回显在响应里**。默认窗口是「最近 365 天」，
--   但**界面上必须显示这个窗口**：一个「准时率 96%」不说是哪 365 天的，
--   在评审供应商时是没有意义的数字。
--
-- ============================================================ 第三节：到货 / 质检记录为什么是本服务的事实而不是 trade 的
--
-- 06 采购单与 6.5 对账在 **trade** 服务（AGENTS.md：跨服务禁止读写对方表 / 跨库 FK / 共享视图）。
-- 那为什么不把到货记录也放在 trade、本模块跨服务读？
-- 两个理由，按重要性排：
--
--   1. **履约与质检结论是供应商档案的一部分，不是单据的一部分。**
--      「准时交付率」这个指标跨单据聚合而成，它在 06 采购单里算一次、
--      在 6.1.1.1 列表里算一次，两次必然会在某次口径变更后不一致 ——
--      而这次不一致**不会报错**，只会让运营对着两个数字做决策。
--      指标的**定义**与它的**原料**应该同属一个写入所有者：trade 收货时
--      通过授权 API（POST /catalog/vendors/{roleId}/deliveries）把事实推进来，
--      本服务负责留档与聚合。
--   2. 单据侧的金额与交期是**可变的**（改单、退货、部分收货），
--      而这里的记录是**到货那一刻的事实**。用可变的单据行去支撑一个
--      写进供应商评估结论的比率，改单一发生，供应商的历史表现就跟着变了。
--
-- 本表因此是**只追加**的事实表：没有 UPDATE 端点，只有追加与（整条作废）标记。
-- 错的到货记录用 is_void 标记而不是删除 —— 「这个供应商的准时率里
-- 混进了一条录错的记录」必须能追溯到是哪一条，而不是悄悄消失。
--
-- ============================================================ 第四节：本迁移约束到的既有模块
--
-- 本迁移**不 ALTER 任何既有表**，因此对并行开发的其它车道**零 DDL 回归面**。
-- 读到的既有表（全部只读，不加列不加索引不加约束）：
--   * party                  (V27)  编码 / 名称 / 联系方式 / 主体状态
--   * party_role             (V27)  SUPPLIER 身份
--   * party_supplier_profile (V30)  合作等级
--   * supplier_quote         (V32)  供货 SKU 数（M1）
--   * product_category       (V6)   类目归属的字典目标
--
-- 本迁移**唯一**新建的外键指向 party_role(tenant_id, id) —— V30 第二节已论证
-- 那是 party_role 上真实存在的唯一列组，且加外键不改那张表一行。

-- ============================================================ 第五节：vendor_supply_profile —— 采购侧运营档案（1:1 挂在 SUPPLIER 角色上）

CREATE TABLE vendor_supply_profile (
  tenant_id uuid NOT NULL,
  -- 指向 party_role(tenant_id,id) 且 role_type='SUPPLIER'。理由与 V30 第二节相同：
  -- party_role 上没有 UNIQUE(tenant_id,party_id)（一个主体最多挂三个角色），
  -- 可指的唯一列组是 UNIQUE(tenant_id,id)，所以键在角色上。
  role_id uuid NOT NULL,

  -- 合作状态（**采购视角**，与 party.status 不是一回事，见第一节）。
  -- 五个取值的判定含义（下游各自的动作完全不同，所以枚举化而不是自由文本）：
  --   PENDING_REVIEW 待评估：已建供应商身份但采购侧还没开始评估，**不出现在新建采购单的候选里**。
  --   ACTIVE          合作中：正常可选。
  --   SUSPENDED       暂停下单：主体仍 ACTIVE（11 售后还要引用），但采购侧不放新单
  --                   （资质过期 / 报价全失效 / 账期谈崩）。历史单据照常。
  --   TRIAL           试单中：与 party_supplier_profile.cooperation_level='TRIAL' 同向但**不是同一件事**：
  --                   合作等级是对方的**分级**（3.6.1.2 写），这里是采购侧**推进到哪一步**（本模块写）。
  --                   一个 TRIAL 等级的供应商在这里可以是 ACTIVE（试单已过），反过来也可以。
  --   TERMINATED      已终止：不再合作。保留行 —— 历史采购单与对账单还要引用它。
  supply_status text NOT NULL DEFAULT 'PENDING_REVIEW'
    CHECK(supply_status IN ('PENDING_REVIEW','ACTIVE','SUSPENDED','TRIAL','TERMINATED')),

  -- 主供类目：引用 3.1 的 product_category(tenant_id,id)。
  -- **存 id 不存 code 也不存名字** —— product_category 的唯一列组是
  -- UNIQUE(tenant_id,tree_id,code)，**没有** UNIQUE(tenant_id,code)
  -- （同一个 code 可以在多棵类目树上各有一份），所以复合外键只能指 (tenant_id,id)。
  -- 显示名在查询时 join 出来，字典改了显示名不会改写历史档案（与 V30 第七节 R7 同源）。
  category_id uuid,

  -- 采购侧备注：谈崩原因、资质到期日、内部对接人这类不属于主体档案的内容。
  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id, role_id),
  -- **没有 role_type CHECK 列**：一列不被外键约束的 role_type 可以和它指向的角色不一致，
  -- 而那种不一致没有任何查询能发现（查的时候会漏行）。角色类型由
  -- service 层（VendorListRules#roleTypeProblem）判定 + DB 外键保证角色真实存在，
  -- 与 V30 第二节同一个理由。
  FOREIGN KEY(tenant_id, role_id) REFERENCES party_role(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, category_id) REFERENCES product_category(tenant_id, id)
);

-- 「还在合作的供应商有哪些」「哪些被暂停了」—— 列表默认筛选 + 6.3 采购单候选下拉的主查询口径。
CREATE INDEX vendor_supply_profile_by_status
  ON vendor_supply_profile(tenant_id, supply_status, role_id);
-- 「按主供类目筛供应商」—— 06 采购拆单、6.5 对账按类目分组。
-- 两条而不是一条 (status, category) 组合索引：两个筛选是**独立**入口，
-- 拼成一条要求查询侧也按同样顺序给条件，而筛类目时通常不带状态条件，
-- 那时组合索引的前导列用不上，退化成全表扫。
CREATE INDEX vendor_supply_profile_by_category
  ON vendor_supply_profile(tenant_id, category_id, role_id);

-- ============================================================ 第六节：vendor_delivery_record —— 到货事实（M2 金额 / M3 准时率 的原料）

CREATE TABLE vendor_delivery_record (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  role_id uuid NOT NULL,

  -- 06 采购单号 / 到货单号。
  -- **刻意不是外键，也不建 trade 侧的联合外键**：那是另一个服务的数据库，
  -- 跨库 FK 是 AGENTS.md 明令禁止的。这里只留一个业务引用键，
  -- 它的作用是「人能顺着这个号回到原始单据」，不是数据库层的完整性保证。
  delivery_ref varchar(64) NOT NULL CHECK(btrim(delivery_ref)<>''),

  -- 行级到货可空：表头到货（整单一次性到）没有单一 SKU。
  sku_id uuid,
  purchase_unit_code varchar(32),
  -- 数量 numeric(19,6)：与 V17 换算表、V30 的 moq_quantity 同一精度
  -- （6 位是不丢精度，不是给用户看 6 位）。
  quantity numeric(19,6) NOT NULL CHECK(quantity > 0),

  -- 采购金额。**NUMERIC 不是浮点**（AGENTS.md：禁止浮点记账）。
  -- numeric(19,4) 而不是 (19,6)：金额到 4 位小数（0.0001 货币单位）已经足够，
  -- 多留两位只会让「这一行到底是不是差一分」的争议更难查。
  amount numeric(19,4) NOT NULL CHECK(amount >= 0),
  -- 币种 ISO 4217 三位码。**没有汇率表**，所以跨币种不可加（见第二节 M2）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  ordered_at timestamptz NOT NULL,
  -- 承诺到货日：来自 06 采购单的交期承诺（冻结值，不随供应商改报价而变）。
  due_date date NOT NULL,
  -- 实际到货日。
  --
  -- **准时与否由 received_date <= due_date 推出，不存 on_time 布尔列**：
  -- 见第二节 M3。存一份推导结果就多一个可能与两个事实不一致的副本。
  received_date date NOT NULL,

  -- 作废标记（录错的行作废而不是物理删除，理由见第三节）。
  -- 作废的行**不计入**任何指标（M2/M3 的 SQL 都带 is_void=false），
  -- 但保留在库里：「这个供应商的准时率里混进过一条录错的记录」必须可追溯。
  is_void boolean NOT NULL DEFAULT false,
  void_reason text NOT NULL DEFAULT '',

  -- 录单备注：收货物料差异、批次号这类不在结构化字段里的现场情况。
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  -- 复合唯一列组：第七节那张质检表的 (tenant_id, delivery_id) 外键要指它。
  -- 单列 PRIMARY KEY(id) 不够 —— 外键必须指向一个**唯一列组**，而 (tenant_id,id) 才是本表的真实键
  -- （与 V30 第二节对 party_role 的论证同一个理由）。
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, role_id) REFERENCES party_role(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, sku_id) REFERENCES sku(tenant_id, id),
  FOREIGN KEY(tenant_id, purchase_unit_code) REFERENCES unit_of_measure(tenant_id, code),
  -- 作废必须留理由：没有理由的作废会让「准时率突然变好」无法解释。
  CHECK(NOT is_void OR btrim(void_reason)<>'')
);

-- 列表页取一行供应商的全部到货聚合（M2 + M3）走的是
-- `where tenant_id=? and role_id=? and is_void=false and received_date between ? and ?`，
-- 前导列必须是 tenant_id, role_id，再按 received_date 做范围。
CREATE INDEX vendor_delivery_record_by_role_date
  ON vendor_delivery_record(tenant_id, role_id, received_date DESC);
-- 「这张 06 采购单到货了没有」—— trade 侧的回查口径。
CREATE INDEX vendor_delivery_record_by_ref
  ON vendor_delivery_record(tenant_id, delivery_ref);

-- ============================================================ 第七节：vendor_quality_record —— 质检事实（M4 合格率 的原料）

CREATE TABLE vendor_quality_record (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  role_id uuid NOT NULL,

  -- 关联的到货记录。**可空**：来料首检、第三方验货未必挂在某一次到货上。
  -- 不建复合外键到 vendor_delivery_record 是有意的：那会让「先有到货才有质检」
  -- 变成 DB 强制的顺序，而首检恰恰发生在到货之前。存在性由服务层判（给了就必须真实存在）。
  delivery_id uuid,

  -- 质检单号（同 delivery_ref：不跨库 FK，只留可追溯的业务引用键）。
  inspection_ref varchar(64) NOT NULL CHECK(btrim(inspection_ref)<>''),

  -- 检验数与合格数。**合格率按数量比算，不按批次数比**（理由见第二节 M4）。
  inspected_qty numeric(19,6) NOT NULL CHECK(inspected_qty > 0),
  qualified_qty numeric(19,6) NOT NULL CHECK(qualified_qty >= 0),

  inspected_at timestamptz NOT NULL,
  is_void boolean NOT NULL DEFAULT false,
  void_reason text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  FOREIGN KEY(tenant_id, role_id) REFERENCES party_role(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, delivery_id) REFERENCES vendor_delivery_record(tenant_id, id),
  -- 合格数不得超过检验数。**在 DB 层**：抽检数据录错（合格 120 / 检验 100）
  -- 会让这家供应商的合格率算出 120%，而报表不会报错只会显示一个荒谬的数字。
  CHECK(qualified_qty <= inspected_qty),
  CHECK(NOT is_void OR btrim(void_reason)<>'')
);

-- 列表页取一行供应商的质检聚合走 `tenant_id, role_id, is_void=false, inspected_at 范围`。
CREATE INDEX vendor_quality_record_by_role_date
  ON vendor_quality_record(tenant_id, role_id, inspected_at DESC);
CREATE INDEX vendor_quality_record_by_ref
  ON vendor_quality_record(tenant_id, inspection_ref);

-- ============================================================ 第八节：RLS 与授权
--
-- 三张表都是租户级数据（供应商是往来单位主数据的采购视角，运营角色可见全租户），
-- 所以沿用 V27 / V30 / V96 第五节那一段 DO $$ FOREACH：只需要租户隔离，
-- 不套 V3 的 owner_id / department_id 数据范围。
--
-- **读 party_* 时 RLS 是第二道闸**：列表查询同时带显式 `tenant_id=?` 绑定
-- 与 party / party_role / party_supplier_profile 三张表自己的 FORCE RLS，
-- 任何一道单独失效都不会放行跨租户 —— 这是 V3 数据范围之外的安全边界。
--
-- 三张表的策略逐字相同（照抄而不是三条复制粘贴：第三遍早晚会漏掉 FORCE，
-- 而漏掉 FORCE 之后表 owner 仍能绕过策略读别人的采购记录）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['vendor_supply_profile','vendor_delivery_record','vendor_quality_record'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON vendor_supply_profile, vendor_delivery_record, vendor_quality_record TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON vendor_supply_profile, vendor_delivery_record, vendor_quality_record TO starcon_app;

-- ============================================================ 第九节：本迁移钉死的六条语义（服务层 VendorListRules 的对应实现）
--
--   **R1 合作等级只读。** 合作等级在 party_supplier_profile.cooperation_level，
--       写入所有权在 3.6.1.2。本模块**只读它、只筛它**，一个字节都不写 ——
--       两处各写一次的后果在第一节已经写死。
--   **R2 合作状态是采购侧的。** vendor_supply_profile.supply_status 与 party.status
--       互不同步：把采购侧暂停**不会**停用主体（11 售后还要引用），
--       停用主体**也不会**自动暂停采购（那是两个不同的决定，要两个不同的动作）。
--   **R3 没有记录时指标是 null，不是 0。** 见第二节 M3 / M4。
--   **R4 跨币种不相加。** 见第二节 M2。排序取该供应商金额最大的币种那一笔，
--       并在响应里写明取了哪一笔。
--   **R5 只追加。** 到货与质检记录没有 UPDATE 端点，错的行用 is_void 作废并留理由。
--   **R6 词表只增不改。** supply_status / 排序键 / 筛选项都是闭集白名单，
--       与 V30 第七节 R6 同源：既有取值不许改语义（已冻结进采购单快照）。
