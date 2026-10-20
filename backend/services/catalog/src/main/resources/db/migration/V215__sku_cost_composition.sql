-- 5.2.1.1 成本构成：SKU 的单位成本构成（分项 + 合计 + 生效时间）与成本变动历史。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：展示并维护 SKU 的单位成本构成：采购成本、头程分摊、包装成本、关税、其他直接成本。
--             采购成本来自 06 采购的实际单价（按成本法加权），其他项来自参数或手工调整。
--   **输入**：成本法选择（移动加权/先进先出/批次）；12 财务的成本核算结果；手工调整项及原因。
--   **输出**：单位成本明细（分项 + 合计 + 生效时间）；成本变动历史。
--   **关系**：上游 = 06 采购、12 财务、10 头程费用；下游 = 5.3.1.1 测算、13 利润。
--             **成本口径必须在页面上明示用了哪种成本法**，
--             否则同一 SKU 今天显示毛利 30% 明天 25% 无法解释。
--
-- ============================================================ 第一节：本模块的三个上游，三个真实状态
--
-- 蓝图这句话里的四个上游，本仓的真实状态**互不相同**，迁移的第一件事就是把它们分清楚，
-- 因为「分项的来源」决定了表上能存什么、不能存什么：
--
--   | 分项     | 蓝图说的来源              | 本仓真实状态                                       |
--   |----------|---------------------------|----------------------------------------------------|
--   | 采购成本 | 06 采购的**实际单价**     | 🔴 06 采购单（6.3.1.1/6.3.1.2/6.3.1.3）**未开发**   |
--   | 头程分摊 | 10 头程费用               | ✅ warehouse `first_mile_shipment` + `_line` 已交付  |
--   | 包装成本 | 参数或手工调整            | ⚠️ **全仓没有任何包装成本事实**（见第四节）          |
--   | 关税     | 12 财务的成本核算结果      | ✅ warehouse `duty_tax_estimate_line` 已交付        |
--   | 成本法   | 移动加权/先进先出/批次     | ✅ 本服务自持（第五节）                            |
--
-- **1) 06 采购单未开发 ⇒ 采购成本分项改用同库的「实际到货事实」**
--
-- `vendor_delivery_record`（V137，6.1.1.1 供应商列表）是一张**只追加**的到货事实表：
-- 一行 = 一次真实到货，带 `quantity`（采购单位）、`amount`（采购金额）、`currency`、
-- `received_date`（实际到货日）、`is_void`（作废标记）。
-- 它的 `amount` 就是**已经付出去的钱**，`amount / quantity` 就是**实际成交单价** ——
-- 这正是蓝图要的「实际单价」，而且它就在本库（本服务是 05 定价与价目的主责服务，
-- 06 采购与 12 财务是它的**供数方**）。
--
-- `supplier_quote`（V32，3.6.2.1 供应商报价）**不是**实际价：它是供应商的**报价**
-- （分档 `min_quantity` + `unit_price`），是「还没下单时谈下来的价」。
-- 拿报价当实际采购成本，在界面上写「采购成本」四个字，就是在骗人：
-- 报价是意向，实际价是事实，两者混在一起会让 13 利润把「谈下来的价」当成「花出去的钱」。
-- ⇒ **本迁移不建任何从报价抄成本的列**；报价口径只作为「无到货记录时的口径提示」出现在响应里。
--
-- **2) 12 财务（finance 服务）不存在 ⇒ 成本核算由本服务按成本法自算**
--
-- `backend/services/` 下只有 identity / catalog / channel / warehouse / trade / crm，
-- **没有 finance**。蓝图输入句「12 财务的成本核算结果」当前**没有来源**。
-- 仓库先例（11.2.3.3 供应商索赔）已经把这类情形的做法钉死了：
--
--   「下游 12 财务（finance 服务）当前不存在 ⇒ 以本服务侧自有事实记录落地，
--     与 12 财务的最终对账留待 finance 落地后经授权 API / event 衔接；
--     本模块绝不读写一个不存在的 finance 服务」
--
-- ⇒ 本模块**按所选成本法自算**单位成本，并在能力声明里如实写明
-- 「这是本服务口径，不是 12 财务的核算结果」。本迁移**不建任何 finance 侧的表或外键**。
--
-- **3) 头程分摊与关税是 warehouse 的事实 ⇒ 跨服务只读，不在本库留副本**
--
-- 头程在 warehouse 的 `first_mile_shipment`（发运单：运费 / 保险费 / 操作费 / 币种）
-- 与 `first_mile_shipment_line`（行：sku_id / 发出数量）里；
-- 关税在 warehouse 的 `duty_tax_estimate`（估算单：币种 / 状态）与
-- `duty_tax_estimate_line`（行：sku_id / 数量 / 预估关税）里（10.5.1.3 已交付）。
-- AGENTS.md 明令禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒ 本库**不建这两者的副本表**，
-- 由服务层经**授权 HTTP 只读端点**取数（见 `CostBasisPort`）。
-- 本迁移因此**零个外键指向 warehouse 的表**。
--
-- ============================================================ 第二节：表结构与「谁拥有哪一个数字」
--
-- 三张表，职责互不重叠：
--
--   | 表                    | 一行是什么                              | 写入方式 |
--   |-----------------------|-----------------------------------------|----------|
--   | `sku_cost_profile`    | 一个 SKU 在一个币种下的**当前**成本构成  | 可改（乐观锁） |
--   | `sku_cost_adjustment` | 一条**手工调整项**（原因必填）           | **只追加** |
--   | `sku_cost_revision`   | 一次成本构成变更的**当时快照**           | **只追加** |
--
-- **「谁拥有哪一个数字」**（这张表是本模块最容易出错的地方，所以先钉死）：
--
--   * **采购成本** —— **不落库**。它由 `vendor_delivery_record` 按成本法**实时派生**。
--     落库就会立刻变成第二个事实来源（到货记录一改，库存里的成本还是旧值），
--     而成本的正确性恰恰依赖「成本永远等于当前事实按所选成本法算出来的值」。
--   * **头程分摊 / 关税** —— **不落库**。同上，来源在 warehouse，实时派生。
--   * **包装成本** —— **落库**（`sku_cost_profile.packaging_cost`）。它是**参数**：
--     全仓没有包装成本事实（第四节），所以它只能是人维护的参数。
--     可空 = **尚未维护**（不是「维护成了 0」）。
--   * **其他直接成本** —— **落库**（`sku_cost_adjustment` 的行聚合）。它是**手工调整**：
--     每一笔都要有**原因**，所以它是一张 append-only 的事实表而不是一个数字列。
--
-- **「合计」是算出来的，不是存出来的**：`sku_cost_revision.total_unit_cost` 是
-- **当时的快照值**（历史不可变，这是它的意义），而**当前**合计由服务层按当前五个分项现算。
-- 当前值不落库：落了就多一份可能与分项不一致的副本。
--
-- ============================================================ 第三节：一个币种一条构成 —— 跨币种绝不相加
--
-- `UNIQUE(tenant_id, sku_id, currency)`：一个 SKU 在**一个币种**下有一条当前构成。
--
-- 为什么不是一个 SKU 一条？因为**跨币种金额绝不相加**（仓库铁律，V137 第二节 M2 已钉）：
-- 100 USD + 90 EUR = 190 是一个**看起来对、实际错**的数字，量级差不多、没有异常、报表照样能出。
-- 本库**没有汇率表**（ISO 4217 与折算是 12 财务的资产，本仓没有 finance 服务），
-- 所以唯一的诚实做法是**逐币种各存一条，逐币种各自算合计**。
--
-- 上游事实的币种与本条构成的币种不一致时（到货是 USD、头程单是 EUR），
-- 该分项的金额**不能折算**进本条构成 —— 服务层把这类金额放进响应的
-- `foreignAmounts`（只读、不计入合计）并写明「未折算：汇率表属 12 财务，本服务不持有」。
-- **绝不编一个汇率**。
--
-- ============================================================ 第四节：包装成本为什么是参数而不是派生值
--
-- 蓝图说「其他项来自参数或手工调整」。本仓对「包装成本」的真实情况是：
--
--   * catalog `sku_package`（V6）—— 只有长宽高 / 净重 / 毛重 / 包装类型，**没有成本列**；
--   * warehouse `packing_material`（V121，包材目录）—— 只有尺寸 / 自重 / 承重上限，
--     **没有成本列**；`parcel`（实测包裹）只有实测读数，**没有成本列**；
--   * 全仓 `grep -iE "material_cost|packaging_cost|package_cost"` **零命中**。
--
-- ⇒ **包装成本当前没有事实来源**。它不是「值为 0」，也不是「上游读不到」，
-- 而是**「这个东西没有成本数据」**。所以：
--
--   1. 它是**参数**（`sku_cost_profile.packaging_cost`，可空 = 尚未维护）；
--   2. 能力声明里写明来源是「人工参数」并点名「包材目录无成本列，包材单价待 06 采购
--      或 12 财务补齐」；
--   3. 界面上这一项显示「未维护」，**不显示 0.00** ——
--      显示 0.00 等于宣称「这个 SKU 不花钱包装」，而真实情况是「没人填过」。
--
-- ============================================================ 第五节：成本法与「换成本法会不会覆盖历史」
--
-- 成本法三选一：`MOVING_AVERAGE`（移动加权）/ `FIFO`（先进先出）/ `LOT`（批次）。
-- 三种**都支持**，都作用在同一份实际到货事实上（第四节第 1 点），口径写进响应：
--
--   * MOVING_AVERAGE：窗口内全部有效到货的加权平均单价 = Σ金额 ÷ Σ基础数量。
--   * FIFO：按到货日升序消耗到目标数量后的加权平均单价。
--   * LOT：以**每一条到货记录为一个批次**，单位成本取目标批次的单价；响应同时给全部批次清单。
--
-- **🔴 换成本法会不会覆盖历史？—— 不会，而且这是硬要求。**
--
-- 5.2.1.3 成本分摊的加粗句说得很直接：
--   「分摊规则变更会导致历史利润变化，因此调整必须留历史版本，不能就地覆盖」。
-- 成本法就是成本构成的口径规则，换成本法 = 换口径 = 同样的事：
-- 「这个 SKU 昨天的成本 12.00 是移动加权算的，今天变成了 10.50（先进先出）」——
-- 如果就地覆盖，历史利润就没有解释力了。
--
-- ⇒ **每次变更都追加一条 `sku_cost_revision` 快照**（含当时的成本法、币种、
-- 五个分项的当时取值、当时的合计、生效时间、变更原因）。
-- 旧版本**原样留着**（append-only，无 UPDATE 端点）。
-- `sku_cost_profile` 只是「当前版本」的指针（`current_revision_id`），
-- 改的是指针，历史不是指针。
--
-- ============================================================ 第六节：手工调整项 —— 原因必填、不可改、留历史
--
-- 蓝图输入句明写「手工调整项**及原因**」。原因不是备注，是**必填**：
-- 没有原因的调整会让「这个 SKU 的其他直接成本为什么突然多了 3 块」永远答不上来。
--
--   * **原因必填**：`reason text NOT NULL CHECK(btrim(reason) <> '')` ——
--     DB 层兜底，绕过服务层直连写入也写不出没有理由的调整。
--   * **不可改**：这张表**没有 UPDATE 端点**。改 = 作废旧行（`is_void` + `void_reason` 必填）
--     + 追加新行。旧行仍在库里，所以「这个 SKU 的成本历史上被改过几次、每次改了什么」可追溯。
--   * **留历史**：作废与新增各自都再落一条 `sku_cost_revision` 快照。
--
-- 调整项只允许作用在两个分项上（`PACKAGING` 之外的 `OTHER_DIRECT` 与 `PACKAGING`）：
-- 采购成本是**派生值**（改了它就和事实脱节了），头程与关税是**上游事实**（改了它就和
-- warehouse 脱节了）—— 那两个分项要改，去改上游的单据，不是来这里改一行数字。
-- 手工调整项的币种必须与构成的币种一致（服务层判），否则它会变成第二个跨币种加总的口子。
--
-- ============================================================ 第七节：单位 —— 为什么采购成本要折算到基础单位
--
-- `vendor_delivery_record.quantity` 的单位是 `purchase_unit_code`（采购单位），
-- 而**单位成本必须折算到 SKU 的基础单位**（`sku.base_unit`）：
-- 工厂按箱报价、我们按件收货时，`amount / quantity` 算出来的是「每箱多少钱」，
-- 而 5.3.1.1 利润测算要的是「每个多少钱」。两者差一个换算系数。
--
-- 折算走 `sku_unit_conversion`（V6，role='PURCHASE'，`quantity_per_base` =
-- 「1 个该单位等于多少个基础单位」）。V6 的触发器 `validate_unit_conversion_window`
-- 保证同一 (sku, unit, role) 的生效区间不重叠 ⇒ 在任一 `received_date` 上
-- **至多命中一个**版本，不存在「取哪一版」的问题。
--
-- **折算不出来的到货行怎么办**：不是静默丢弃，也不是整条作废，而是
-- **排除该行 + 如实报出被排除的行数**（响应的 `excludedDeliveryCount` 与警告），
-- 并且该分项标记为**不完整**（`complete=false`）⇒ 合计也标为「已知部分合计」。
-- 丢掉几行却报一个完整数字，会让成本看起来比实际低。
--
-- ============================================================ 第八节：RLS 与授权
--
-- 三张表都是租户级业务表（成本是租户自己的成本事实）：
-- `tenant_id uuid NOT NULL` + `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`
-- + isolation 策略（照抄 V32 第八节的 `DO $$ FOREACH` 写法，逐字相同）。
--
-- **FORCE 不能漏**：漏掉 FORCE 之后表 owner（迁移角色）仍能绕过策略读别人的成本，
-- 而成本是最敏感的数据之一（能看出别的租户在什么价位采购）。
--
-- 可见性跟随 SKU：看不到 SKU 就看不到它的成本构成（与 V6 的 sku_package 同一条纪律）。
--
-- ============================================================ 第九节：本迁移钉死的七条语义（服务层 SkuCostCompositionRules 的对应实现）
--
--   **R1 采购成本是派生值，不是列。** 见第一节第 1 点与第二节。
--   **R2 报价不是实际价。** 界面上不得把 `supplier_quote` 的档位价写成「采购成本」。
--   **R3 成本法三选一都支持；换成本法追加历史版本，不就地覆盖。** 见第五节。
--   **R4 包装成本是参数，可空 = 未维护；没有包装成本事实来源。** 见第四节。
--   **R5 手工调整项原因必填、只追加、作废留理由。** 见第六节。
--   **R6 跨币种绝不相加。** 一 SKU 一币种一条构成；上游异币种金额单列且不折算。见第三节。
--   **R7 派生分项读不到时标不完整，不填 0。** 上游不可达 ⇒ 该分项 available=false +
--   原因；合计标「已知部分合计」。**绝不返回 0 冒充「上游没有这笔成本」**。
--
-- ============================================================ 第十节：与兄弟模块的边界（5.2.1.2 / 5.2.1.3 未交付）
--
--   * **5.2.1.2 到岸成本**（同一页面路由 /pricing/cost）：到岸成本是**单据级**的概念
--     （一批货的 CIF 价 + 关税 + 到港），本模块是 **SKU 级单位成本构成**。
--     本迁移**不建 landed cost 表** —— 那是 5.2.1.2 的写入所有权。
--   * **5.2.1.3 成本分摊**（同一页面路由）：分摊规则是**规则表**（按重量/体积/金额分摊到
--     订单行），本模块**不建分摊规则表**。5.2.1.3 引用本模块的**历史版本**做回溯，
--     所以本模块的历史（`sku_cost_revision`）必须**不可变** —— 这也是第五节的硬要求。
--   * 页面路由 `/pricing/cost` 三者共用：前端按**分区块**组织，
--     5.2.1.1 只占「成本构成」这一块，兄弟模块后续在别的块上加内容，不重写页面。

-- ============================================================ 第一节表：sku_cost_profile —— 当前成本构成

CREATE TABLE sku_cost_profile (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  -- 指向 sku(tenant_id,id)：成本构成挂在 SKU 上。UNIQUE(tenant_id,id) 是它真实存在的唯一列组。
  sku_id uuid NOT NULL,

  -- 成本法：移动加权 / 先进先出 / 批次。见第五节。
  -- **枚举只增不改**：成本法会进历史快照（sku_cost_revision.cost_method），
  -- 改语义会让历史成本读起来是错的。
  cost_method text NOT NULL CHECK(cost_method IN ('MOVING_AVERAGE','FIFO','LOT')),

  -- 计价币种：ISO 4217 三位大写码。**没有汇率表**（12 财务的资产），所以币种不同就不相加。
  -- 用 varchar(3) 而不是 char(3)：char 会补空格，'USD ' 与 'USD' 不相等。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 包装成本（**参数**，见第四节）。**可空 = 尚未维护**，不是 0。
  -- 0 与 NULL 是两件事：0 是「维护成了 0」（这个 SKU 不花钱包装），
  -- NULL 是「没人填过」（不知道是多少）。把后者显示成 0.00 是在编数据。
  packaging_cost numeric(19,4) CHECK(packaging_cost IS NULL OR packaging_cost >= 0),

  -- 生效时间：这一版构成的从哪一刻开始算数。
  -- **timestamptz 而不是 date**：成本切换常常发生在某个具体时刻
  -- （一批到货、一次调价），只到日会让「今天上午算的毛利」与「今天下午算的毛利」口径不明。
  effective_from timestamptz NOT NULL,

  -- 当前版本指针：指向 sku_cost_revision(tenant_id,id)。
  -- 「当前」是**指针**，历史是**行** —— 换成本法改的是指针，历史一行不动（第五节）。
  current_revision_id uuid,

  version bigint NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一 SKU 一币种一条构成（第三节）。同 SKU 换币种就是**新增一条**，
  -- 不是就地改币种 —— 就地改会把旧币种下的历史版本与新币种混在一起。
  UNIQUE(tenant_id, sku_id, currency),
  FOREIGN KEY(tenant_id, sku_id) REFERENCES sku(tenant_id, id) ON DELETE CASCADE
);

-- 列表主查询：按 SKU 找它的构成（一个 SKU 可能有多条，逐币种）。
CREATE INDEX sku_cost_profile_by_sku ON sku_cost_profile(tenant_id, sku_id);
-- 「本租户按币种归集成本」—— 5.2.1.1 测算与 13 利润按币种分组时的入口。
CREATE INDEX sku_cost_profile_by_currency ON sku_cost_profile(tenant_id, currency, sku_id);
-- 「最近变更过的 SKU」—— 成本治理页的默认排序。
CREATE INDEX sku_cost_profile_by_updated ON sku_cost_profile(tenant_id, updated_at DESC);

-- ============================================================ 第二节表：sku_cost_adjustment —— 手工调整项（只追加）

CREATE TABLE sku_cost_adjustment (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  sku_id uuid NOT NULL,

  -- 作用在哪个分项。只有两个可手工调整的分项（第六节）：
  -- PACKAGING（包装成本参数的人工微调）与 OTHER_DIRECT（其他直接成本）。
  -- 采购成本是派生值、头程与关税是上游事实 —— 那三个不在这里改。
  component text NOT NULL CHECK(component IN ('PACKAGING','OTHER_DIRECT')),

  -- 金额：numeric(19,4)（与 V137 的采购金额同一精度：4 位小数，足够分辨「差一分钱」）。
  -- **允许为负**：冲回一笔多记的调整是真实场景（正负都合法才有冲回的意义）。
  -- 但**不允许为 0**：金额为 0 的调整行没有信息量，只会让「调整过几次」这个计数虚高。
  amount numeric(19,4) NOT NULL CHECK(amount <> 0),

  -- 币种：与构成的币种一致（服务层判）。跨币种不相加（第三节）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 调整原因：**必填**（第六节）。DB 层兜底 ——
  -- 绕过服务层直连写入也写不出没有理由的调整行。
  reason text NOT NULL CHECK(btrim(reason) <> ''),

  -- 生效时间：这一笔从哪一刻起计入单位成本。
  effective_from timestamptz NOT NULL,

  -- 作废标记：录错的行**作废而不是删除**（与 V137 的 vendor_delivery_record 同一纪律）。
  -- 作废的行不计入任何聚合，但留在库里 —— 「这个 SKU 的成本历史上被改过几次」必须可追溯。
  is_void boolean NOT NULL DEFAULT false,
  void_reason text NOT NULL DEFAULT '',
  voided_by uuid,
  voided_at timestamptz,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, sku_id) REFERENCES sku(tenant_id, id) ON DELETE CASCADE,
  -- 作废必须留理由：没有理由的作废会让「成本突然降了」无法解释。
  CHECK(NOT is_void OR btrim(void_reason) <> '')
);

-- 聚合主查询：这个 SKU 的调整项（按生效时间排序）。
-- 前导列 (tenant_id, sku_id)，再按 effective_from 排序 —— 服务层的聚合查询走这一条。
CREATE INDEX sku_cost_adjustment_by_sku ON sku_cost_adjustment(tenant_id, sku_id, effective_from DESC);
-- 「哪些 SKU 还没维护其他直接成本」—— 成本治理页的待办入口。
CREATE INDEX sku_cost_adjustment_by_component ON sku_cost_adjustment(tenant_id, component, sku_id);

-- ============================================================ 第三节表：sku_cost_revision —— 成本变动历史（只追加快照）

CREATE TABLE sku_cost_revision (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  sku_id uuid NOT NULL,

  -- 计价币种：快照的计价口径。跨币种不相加（第三节）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),

  -- 版本号：一个 (sku, 币种) 下从 1 递增。
  -- **不靠 count(*) 现算**：并发写入时 count 会算出两个相同的号，
  -- 而 UNIQUE 约束会把其中一个变成 500 —— 用序列式的 max+1 也一样有竞态。
  -- 所以版本号由服务层在**同事务**里取 max+1 并靠 UNIQUE 兜底，撞了就重试。
  revision_no int NOT NULL CHECK(revision_no > 0),

  -- 当时的成本法。**换成本法不改这一列的含义**（枚举只增不改，第五节）。
  cost_method text NOT NULL CHECK(cost_method IN ('MOVING_AVERAGE','FIFO','LOT')),

  -- ===== 五个分项的**当时取值**（快照）=====
  -- 派生分项（采购 / 头程 / 关税）存的是**那一刻按当时成本法算出来的值**，
  -- 不是「读不到时的 null」—— 读不到时该列是 null 且 snapshot_complete=false。
  -- 历史不可变：这一行永远不被 UPDATE 改写。
  purchase_cost numeric(19,4),
  first_mile_allocation numeric(19,4),
  packaging_cost numeric(19,4),
  duty_cost numeric(19,4),
  other_direct_cost numeric(19,4),

  -- 当时的合计快照（五项之和；派生项缺失时是「已知部分合计」）。
  total_unit_cost numeric(19,4),

  -- 快照完整性：false 表示**有分项当时读不到**（上游不可达 / 该分项无事实）。
  -- 有了它，「这条历史是不是一个完整成本」一眼可判，
  -- 而不必逐列去猜哪个是 null。
  snapshot_complete boolean NOT NULL DEFAULT true,
  -- 缺失分项的代码列表（逗号分隔）：缺了哪些，写清楚。
  missing_components text NOT NULL DEFAULT '',

  -- 生效时间：这一版从哪一刻起算数。
  effective_from timestamptz NOT NULL,

  -- 变更原因：**必填**。改成本法、改币种、改包装成本参数、加调整项 —— 每一次变更都要说清为什么。
  -- 没有理由的历史等于没有历史：半年后没人知道当时为什么把成本法从移动加权改成先进先出。
  change_reason text NOT NULL CHECK(btrim(change_reason) <> ''),

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一个 (sku, 币种) 下版本号唯一：撞号说明并发写入，服务层重试（见上面 revision_no 的注释）。
  UNIQUE(tenant_id, sku_id, currency, revision_no),
  FOREIGN KEY(tenant_id, sku_id) REFERENCES sku(tenant_id, id) ON DELETE CASCADE
);

-- 历史查询主路径：这个 SKU 这个币种的版本序列（新到旧）。
CREATE INDEX sku_cost_revision_by_sku ON sku_cost_revision(tenant_id, sku_id, currency, revision_no DESC);
-- 「最近改过成本的一批 SKU」—— 成本治理页的时间线。
CREATE INDEX sku_cost_revision_by_created ON sku_cost_revision(tenant_id, created_at DESC);

-- 当前版本指针的外键：profile → revision。
-- 单独 ALTER（而不是写在 CREATE TABLE 里）：两张表互相引用时，
-- 先建表再加约束才不会自引用失败。**不建**反向外键（revision 不指回 profile）：
-- 快照必须是「即使 profile 被删也还在」的历史（profile 的 SKU 被删时 CASCADE 会带走它，
-- 那是对的 —— SKU 都没了，成本历史留着也没有意义）。
ALTER TABLE sku_cost_profile
  ADD CONSTRAINT sku_cost_profile_current_revision_fk
  FOREIGN KEY(tenant_id, current_revision_id) REFERENCES sku_cost_revision(tenant_id, id);

-- ============================================================ 第四节：RLS 与授权

-- 三张表都是租户级业务表（成本是租户自己的成本事实），只需要租户隔离，
-- 可见性由端点层权限把关。
-- 照抄 V32 第八节的 DO $$ FOREACH 写法：三张表的策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的成本 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['sku_cost_profile','sku_cost_adjustment','sku_cost_revision'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 可见性跟随 SKU：看不到 SKU 就看不到它的成本构成（与 V6 的 sku_package 同一条纪律）。
-- 这条是 RESTRICTIVE：与 isolation 策略**同时**满足才放行。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['sku_cost_profile','sku_cost_adjustment','sku_cost_revision'] LOOP
    EXECUTE format('CREATE POLICY data_scope ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id)) WITH CHECK (EXISTS(SELECT 1 FROM sku s WHERE s.tenant_id=%I.tenant_id AND s.id=%I.sku_id))',tbl,tbl,tbl,tbl,tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_cost_profile TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_cost_profile TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_cost_adjustment TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_cost_adjustment TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_cost_revision TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON sku_cost_revision TO starcon_app;
