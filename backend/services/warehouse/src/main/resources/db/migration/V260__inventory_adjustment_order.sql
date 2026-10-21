-- ===========================================================================
-- 7.4.1.1 调整单管理：通用库存调整单（盘盈盘亏 / 损坏 / 报废 / 错发纠正 / 平台仓差异）
-- ===========================================================================
--
-- 蓝图原文：「**发起库存调整**（盘盈盘亏、损坏、报废、错发纠正、平台仓差异），填写原因与凭证，
-- **支持批量调整**。」输入：「调整请求（bucket、数量、原因码、附件）；`expected_version`。」
-- 输出：「调整单（草稿/待审/已批/已执行）；库存分录（反向或正向）。」
-- 关系：上游 = 7.1.1.2、6.4.2.2；下游 = 7.4.1.2 审批、7.1.2.1 流水。
-- 红线：「**调整必须走单据**，不允许在明细页直接改余额——没有单据就没有责任人与原因。」
--
-- ---------------------------------------------------------------------------
-- 一、为什么是**新表**，而不是复用 V62 的 stocktake_variance_adjustment
-- ---------------------------------------------------------------------------
-- V62（7.4.2.2 盘点差异处理）已经建了一套「调整单」：
--   stocktake_variance_adjustment（头） + stocktake_variance_adjustment_line（行）
--   端点 POST /warehouse/inventory/stocktake-variances/adjustments
--
-- 但那是**盘点差异的调整单**，它的头表上有一列 NOT NULL 的 sheet_id（复合外键指向
-- stocktake_line），也就是说**没有盘点表就没有调整单**。本模块 7.4.1.1 是**通用调整单**：
-- 损坏、报废、错发纠正这些场景根本没有盘点单，「必须先盘一次才能调账」是荒谬的。
--
-- 所以本迁移**建自己的表**（新表名、新端点前缀），**零 ALTER 既有表**，
-- 既不复用 V62 的表当本模块的单据，也不改它一个字节。两条单据线并存，靠
-- inventory_transaction 的 ref_type 区分（见文件头第四节）。
--
-- ---------------------------------------------------------------------------
-- 二、上游形状（7.1.1.2 库存明细，已交付）
-- ---------------------------------------------------------------------------
-- 本模块**按 bucket 六维定位库存事实**，不自己造第二个数量来源：
--   stock_bucket(tenant_id, legal_entity_id, sku_id, warehouse_id, location_id,
--                lot_id, quality_status_id, balance_id, on_hand, reserved,
--                available, version, last_movement_at)
--   - 六个维度一个都不能少、不能多：legal_entity × sku × warehouse × location
--     × lot（可空：非批次管理品没有批次）× quality_status；
--   - balance_id 指回 inventory_balance，**复合外键强制**六维与 balance 逐列相同；
--   - 数量列是 numeric(19,4)（**不是 19,6**，照抄既有口径），全程禁浮点；
--   - version 是乐观锁列（每次数量变动 +1，由 V2 的 guard_bucket_movement_time 触发器强制）；
--   - last_movement_at 是**业务时间**（由写入方提供，不写 now() 糊弄）。
--
-- 调整单行按这六维定位 bucket，因此行上冗余一份可读名快照（sku_code / location_code /
-- lot_code / quality_status_code）：盘点与调整之后 SKU 与库位可能被改名或停用，
-- 而单据要能回看「当时调的是哪一件」。数量是**一次性快照**（COPY 语义），
-- 真正的余额永远由 bucket + balance 说了算。
--
-- ---------------------------------------------------------------------------
-- 三、原因码：受控词表，不是自由文本
-- ---------------------------------------------------------------------------
-- 原因码用 CHECK 约束钉死（6 个），原因**原文**另存 note 文本（给人看）。
-- 蓝图点名的五类逐字对应：
--   OVERAGE          盘盈        实物多于账面
--   SHORTAGE         盘亏        实物少于账面
--   DAMAGE           损坏        破损但未判废
--   SCRAP            报废        已判废，直接核销
--   MISSHIP_CORRECTION 错发纠正  纠正发错造成的账实错位
--   PLATFORM_VARIANCE 平台仓差异  **本模块不可用**，见文件头第五节
--
-- 拿自由文本当原因码的后果是：同一个原因被写成「损坏」「破损」「DAMAGE」三种，
-- 于是「本月损坏调了多少」这个问题答不出来，而调整正是舞弊高发区。
--
-- ---------------------------------------------------------------------------
-- 四、入账：照抄 warehouse 已有的成熟做法（V62 的过账段）
-- ---------------------------------------------------------------------------
-- 一个事务里做五件事，**顺序与 V62 的 StocktakeVarianceService.post 逐条一致**：
--   1. 写业务单 inventory_transaction(kind='ADJUSTMENT', ref_type='INVENTORY_ADJUSTMENT',
--      ref_id=<调整单 id>) —— ref_type 是跨模块的定位键（V62 用 'STOCKTAKE_VARIANCE'）；
--   2. 按 balance 聚合后更新 inventory_balance（同一余额行只 UPDATE 一次）；
--   3. 逐行更新 stock_bucket（on_hand += delta, last_movement_at = 业务时间）；
--   4. 逐行写 inventory_entry（带符号分录，一行一条）；
--   5. 标记行已过账 + 单据转 POSTED + 写 append-only 审计。
--
-- **不绕过触发器直接改余额**：inventory_entry 的 append-only、inventory_balance /
-- stock_bucket 的守恒触发器与 CHECK(on_hand>=0) / CHECK(reserved>=0) /
-- CHECK(available>=0) 全部照旧生效；「不许把库存做成负数」在服务层先判一次给出
-- 能读懂的话，DB 层再兜一道。
--
-- **绝不另建一张「调整流水表」**：7.1.2.1 库存流水台账读的就是 inventory_entry，
-- 分录一落库流水自然可见；另建一张就是同一件事的第二个事实源，账实两套口径。
--
-- ---------------------------------------------------------------------------
-- 五、平台仓差异（PLATFORM_VARIANCE）：**本模块不可用**，且不建半截实现
-- ---------------------------------------------------------------------------
-- V55 已经用 DB 触发器钉死：PLATFORM_FBA 仓**不得有自有账本余额**
-- （guard_platform_bucket_no_ledger 拒绝给平台仓写 balance / bucket 行），
-- 平台仓的数量只存在于 append-only 的 platform_stock_observation 观测序列里
-- （带 source 与 collected_at，且明确「与 balance / bucket 在任何地方都不相加」）。
--
-- 也就是说：**平台仓根本没有 stock_bucket 行可调**。把平台观测值当成自货调整掉，
-- 等于把一条观测读成一个事实，制造出「平台仓也有自有账本」的第二个真相 ——
-- 8.1.1.1 早就警告过：直接求和会把 300+310 算成 610 那个不存在的数。
--
-- 所以本模块的处置是：
--   - 原因码**保留在词表里**（vocabulary 完整，将来货权模型落地不必 ALTER 本表）；
--   - 服务层**显式拒绝**用该码建行（422 + 原因 + 归属模块），不静默接受；
--   - 能力端点如实报 available=false + ownerModule。
--
-- ---------------------------------------------------------------------------
-- 六、审批归属：状态机写全，但「谁该批」不由本模块发明
-- ---------------------------------------------------------------------------
-- 蓝图输出的四个状态「草稿 / 待审 / 已批 / 已执行」**现在就写全**
-- （DRAFT / PENDING_APPROVAL / APPROVED / POSTED，外加 REJECTED / CANCELLED 两条终止边），
-- 这样 7.4.1.2 交付时**不必 ALTER 本表**（跨车道 DDL 回归面 = 0）。
--
-- 「待审 → 已批」这一步：7.4.1.2 与 17 卷审批中心拥有**阈值审批规则**（按金额/数量阈值、
-- 批量审批、驳回），本模块**不建审批人角色表**、不实现阈值判定。
-- 本模块提供的是**显式权限 + 原因 + 职责分离**的最小口径：
--   - 批准走独立权限码（warehouse.inventory.approve），不是普通的 update；
--   - 批准必须写理由（decision_note）；
--   - 提交人 ≠ 审批人（DB 触发器钉死）。
--
-- 「过账的前置条件（需要已批才能过账）必须由 DB 触发器钉死」—— 见第七节 6.2 与 6.3。
--
-- ---------------------------------------------------------------------------
-- 七、DB 级行为约束（照抄 V62 的六道触发器，它们不是可选项）
-- ---------------------------------------------------------------------------
--   6.1 guard_inventory_adjustment_order_state      状态机：只认合法流转，POSTED 只能从 APPROVED 到达
--   6.2 guard_inventory_adjustment_entry_approved   **入账闸门**：未到 APPROVED 的单拿不到任何分录
--   6.3 guard_inventory_adjustment_posting_sum      **入账数量守恒**：Σ分录 == Σ行
--   6.4 guard_inventory_adjustment_line_posted      已过账的行不可再改
--   6.5 guard_inventory_adjustment_order_no_delete  单据不可删（作废走状态机，留痕）
--   6.6 guard_inventory_adjustment_audit_append_only 审计只追加
--   另有三道本模块特有的：
--   6.7 guard_inventory_adjustment_line_dimensions  行的六维必须与 bucket 逐列相同
--   6.8 guard_inventory_adjustment_line_delete      只有草稿能删行（草稿行不是账的一部分）
--   6.9 guard_inventory_adjustment_attachment_draft_only 只有草稿能挂/摘附件
--
-- 「未审批不得入账」是这套单据能信的原因：拿到连接直接 INSERT 也绕不过去。
--
-- ---------------------------------------------------------------------------
-- 八、数量与时间口径
-- ---------------------------------------------------------------------------
--   数量 NUMERIC(19,4) + Java BigDecimal，全程禁浮点（与 V1/V2/V54/V56/V59/V62 同口径）。
--   时间 timestamptz：occurred_at / mounted_at 等由应用层以 OffsetDateTime 绑定并写
--   ?::timestamptz，cast 一律不落在绑定序列末位（V59/V62 同一条纪律）。
--   created_at 是系统时间，posted_at / occurred_at 是业务时间，两者分开存不混用。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 原因码词表：受控值 + 中文名
-- ---------------------------------------------------------------------------
-- 为什么是表而不是 Java 常量：原因是租户经营口径的一部分（不同品类对「损坏」
-- 的界定不同），且 7.4.1.2 与 12 财务都要按原因码聚合统计。写成表以后，
-- 枚举仍然是**封闭**的（available 列决定能否选用），但展示名与说明可查。
CREATE TABLE inventory_adjustment_reason (
  code varchar(32) PRIMARY KEY,
  label varchar(64) NOT NULL,
  description varchar(300) NOT NULL DEFAULT '',
  -- available=false 的码**保留在词表里**但不可选用：平台仓差异就是这一类
  -- （平台仓没有 stock_bucket 行可调，见文件头第五节）。
  available boolean NOT NULL DEFAULT true,
  -- 不可用时归哪个模块。
  owner_module varchar(32) NOT NULL DEFAULT '',
  note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK(btrim(code)<>'' AND btrim(label)<>'')
);

INSERT INTO inventory_adjustment_reason(code,label,description,available,owner_module,note) VALUES
  ('OVERAGE','盘盈','实物多于账面：补记入库（漏记收货、多收、退货未记）',true,'',
   '正数调整会增加在手量。'),
  ('SHORTAGE','盘亏','实物少于账面：漏记出库、少发、丢失',true,'',
   '负数调整会减少在手量；减到 0 以下会被数据库拒绝。'),
  ('DAMAGE','损坏','破损但尚未判废：货还在库位上，只是不可用',true,'',
   '通常与「质量状态改为残次」一起做；本模块只改数量，不改质量状态。'),
  ('SCRAP','报废','已判废核销：货已不可用且不可修复',true,'',
   '报废量必须与实物处置单据一致，否则账实立刻分叉。'),
  ('MISSHIP_CORRECTION','错发纠正','纠正发错货造成的账实错位',true,'',
   '纠正的是错发那一笔，不是把差额抹平。'),
  ('PLATFORM_VARIANCE','平台仓差异','平台仓（FBA）观测值与自有账本的差异',false,'10.1.1.1 / 8.1.1.1',
   '平台仓库存是**观测值**不是自有账本：V55 的 guard_platform_warehouse_no_ledger 禁止给平台仓写 balance/bucket，'
   '平台数量只在 append-only 的 platform_stock_observation 里。把平台观测值当成自货调整掉会制造第二个真相。'
   '平台仓差异请走 10 外部仓对账与 7.1.3.1 多仓分布，本模块不接受该原因码。');

-- ---------------------------------------------------------------------------
-- 1. inventory_adjustment_order · 调整单（头）
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_adjustment_order (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号：ADJ-INV-<UTC 日期>-<8 位十六进制>，服务端派生，不接受调用方自填。
  code varchar(32) NOT NULL,

  -- 状态机（蓝图四个状态 + 两条终止边，**现在就写全**，7.4.1.2 交付时不必 ALTER 本表）。
  status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN
    ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','POSTED','CANCELLED')),

  -- 单据级原因码：受控词表（inventory_adjustment_reason.code）。
  -- 行的原因码可覆盖单据级默认值（同一张单里可以有盘盈也有盘亏）。
  reason_code varchar(32) NOT NULL CHECK(reason_code IN
    ('OVERAGE','SHORTAGE','DAMAGE','SCRAP','MISSHIP_CORRECTION','PLATFORM_VARIANCE')),
  -- 单据级原因原文（给人看）。**码是受控值，原文是自由文本** —— 两者分工不同。
  note varchar(500) NOT NULL DEFAULT '',

  -- 上游来源：6.4.2.2 不良品处理（**trade 服务**）。跨服务，只能是逻辑 ID：
  -- 不建 FK、不跨库读它的表（AGENTS.md 模块边界纪律）。source_type 目前只有
  -- 'DEFECTIVE_DISPOSITION'，留空表示无来源。
  source_type varchar(32) NOT NULL DEFAULT '',
  source_order_id uuid,
  source_instruction_id uuid,
  -- 来源单号的**快照**：trade 改单号时本地不跟着变，但单据要能回看「当时引的是哪一张」。
  source_reference varchar(128) NOT NULL DEFAULT '',

  -- 提交人 / 提交时刻
  submitted_by_user_id uuid,
  submitted_at timestamptz,
  -- 审批人 / 审批时刻 / 审批意见（驳回时必填）
  decided_by_user_id uuid,
  decided_at timestamptz,
  decision_note varchar(500),
  -- 入账人 / 入账时刻
  posted_by_user_id uuid,
  posted_at timestamptz,
  -- 作废人 / 作废时刻
  cancelled_by_user_id uuid,
  cancelled_at timestamptz,

  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 单号在租户内唯一：撞号会让「这次入账对应哪张单」失去唯一答案。
  UNIQUE(tenant_id,code),
  CHECK(btrim(code)<>''),

  -- 四个动作的字段各自同进同出（与 V62 同一条纪律）：「谁批的」缺一半就是有一半动作没人负责。
  CHECK((submitted_at IS NULL) = (submitted_by_user_id IS NULL)),
  CHECK((decided_at IS NULL) = (decided_by_user_id IS NULL)),
  CHECK((posted_at IS NULL) = (posted_by_user_id IS NULL)),
  CHECK((cancelled_at IS NULL) = (cancelled_by_user_id IS NULL)),

  -- 驳回必须写意见：只给一个 REJECTED 状态，提交的人无法知道该改什么。
  CHECK(status<>'REJECTED' OR (decision_note IS NOT NULL AND btrim(decision_note)<>'')),
  -- 过账必须有审批痕迹：POSTED 的单据不可能没有审批人。
  CHECK(status<>'POSTED' OR (decided_at IS NOT NULL AND posted_at IS NOT NULL)),
  -- 职责分离：**提交人不能批自己提的单**（自提自批的审批与没有审批是同一件事，
  -- 而库存调整正是舞弊高发区）。DB 级钉死，不靠应用层自律。
  CHECK(decided_by_user_id IS NULL OR submitted_by_user_id IS NULL OR decided_by_user_id <> submitted_by_user_id),

  -- 来源要么没有，要么三个字段成套出现：半个来源信息是「引用了哪张单说不清」。
  CHECK((source_type = '') = (source_order_id IS NULL)),
  CHECK((source_order_id IS NULL) = (source_instruction_id IS NULL))
);

-- 列表主查询：租户 + 状态筛选 + 时间倒序。
CREATE INDEX inventory_adjustment_order_list
  ON inventory_adjustment_order(tenant_id,status,created_at DESC);
-- 审批工作台：谁在等我批（7.4.1.2 交付后直接用这张索引）。
CREATE INDEX inventory_adjustment_order_pending
  ON inventory_adjustment_order(tenant_id,submitted_at)
  WHERE status='PENDING_APPROVAL';
-- 按来源反查「这批不良品处置后来调了哪些账」（跨服务只存逻辑 ID，反查走本地索引）。
CREATE INDEX inventory_adjustment_order_source
  ON inventory_adjustment_order(tenant_id,source_order_id)
  WHERE source_order_id IS NOT NULL;
-- 按原因码聚合（12 财务与 7.6 库存分析都要按原因分组）。
CREATE INDEX inventory_adjustment_order_reason
  ON inventory_adjustment_order(tenant_id,reason_code,created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. inventory_adjustment_order_line · 调整单行
-- ---------------------------------------------------------------------------
-- 一行 = 一个 bucket 上的一次带符号数量调整。
CREATE TABLE inventory_adjustment_order_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  FOREIGN KEY(tenant_id,order_id) REFERENCES inventory_adjustment_order(tenant_id,id),

  -- 目标事实单位：bucket（库位/批次粒度）与 balance（仓级可用量决策单位）。
  -- balance_id 取自 stock_bucket.balance_id —— 不猜、不重算。
  bucket_id uuid NOT NULL,
  FOREIGN KEY(tenant_id,bucket_id) REFERENCES stock_bucket(tenant_id,id),
  balance_id uuid NOT NULL,
  FOREIGN KEY(tenant_id,balance_id) REFERENCES inventory_balance(tenant_id,id),

  -- 六维定位（与 bucket 逐列相同，由 6.7 的触发器强制）。
  legal_entity_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  location_id uuid NOT NULL,
  -- lot_id 可空：非批次管理品没有批次。
  lot_id uuid,
  quality_status_id uuid NOT NULL,
  -- 与 balance 的复合外键：六维与 balance 逐列相同（与 stock_bucket 同一条纪律）。
  FOREIGN KEY(tenant_id,balance_id,legal_entity_id,sku_id,warehouse_id,quality_status_id)
    REFERENCES inventory_balance(tenant_id,id,legal_entity_id,sku_id,warehouse_id,quality_status_id),

  -- 可读名快照：单据要能回看「当时调的是哪一件」。
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL DEFAULT '',
  warehouse_code varchar(64) NOT NULL,
  location_code varchar(64) NOT NULL,
  lot_code varchar(64) NOT NULL DEFAULT '',
  quality_status_code varchar(32) NOT NULL,

  -- **带符号数量快照**（正=增加在手，负=减少在手）。
  -- 精度与 stock_bucket.on_hand 同口径：numeric(19,4)，不是 19,6。
  qty_delta numeric(19,4) NOT NULL CHECK(qty_delta<>0),

  -- 行级原因码（可覆盖单据级默认值）。受控词表。
  reason_code varchar(32) NOT NULL CHECK(reason_code IN
    ('OVERAGE','SHORTAGE','DAMAGE','SCRAP','MISSHIP_CORRECTION','PLATFORM_VARIANCE')),
  -- 行级原因原文（自由文本，给人看）。
  note varchar(500) NOT NULL DEFAULT '',

  -- **乐观锁**：建行时读到的 stock_bucket.version。
  -- 与当前 version 不一致说明调用方看到的是过期数量（期间有人动过这批货），
  -- 此时拒绝建行 / 过账（409），而不是拿旧数字去改新余额。
  expected_version bigint NOT NULL CHECK(expected_version>=0),

  -- 入账痕迹。posted_at 非空 = 这一行已经进过账。
  posted_at timestamptz,
  posted_by_user_id uuid,
  -- 对应的库存分录 id 与业务单 id（6.2 的入账闸门在写分录时回填）。
  entry_id uuid,
  transaction_id uuid,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张单里同一个 bucket 只能有一行：两行改同一格会让「这批货被调了两次」无从判断，
  -- 乐观锁的 expected_version 也会失去唯一含义。
  UNIQUE(tenant_id,order_id,bucket_id),
  CHECK((posted_at IS NULL) = (posted_by_user_id IS NULL)),
  -- 入账痕迹三件套：要么都没有，要么全有（只记时刻不记人 = 追责断链）。
  CHECK(posted_at IS NULL OR (entry_id IS NOT NULL AND transaction_id IS NOT NULL))
);

-- 调整单明细页主查询。
CREATE INDEX inventory_adjustment_order_line_order
  ON inventory_adjustment_order_line(tenant_id,order_id);
-- 「这个 bucket 有没有已经被调过账」的探测（幂等与追责用）。
CREATE INDEX inventory_adjustment_order_line_bucket
  ON inventory_adjustment_order_line(tenant_id,bucket_id);
-- 按原因码聚合行数（7.6 库存分析与 12 财务按原因分组）。
CREATE INDEX inventory_adjustment_order_line_reason
  ON inventory_adjustment_order_line(tenant_id,reason_code)
  WHERE posted_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. inventory_adjustment_attachment · 凭证附件（逻辑引用 + 快照元数据）
-- ---------------------------------------------------------------------------
-- 媒体库归 catalog（3.4.1.1 /catalog/media），是**独立库**。所以这里：
--   - 不建跨库 FK（AGENTS.md 禁止跨服务 FK）；
--   - 不跨库写（上传动作在 catalog 侧完成，本模块只登记引用）；
--   - 存**快照元数据**（媒体 id + 文件名 + 类型 + 大小 + 挂载时刻），
--     免得 catalog 那边改名之后这里连「当时凭的是哪张图」都答不出来。
CREATE TABLE inventory_adjustment_attachment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  FOREIGN KEY(tenant_id,order_id) REFERENCES inventory_adjustment_order(tenant_id,id),

  -- 媒体逻辑 ID（catalog 侧的唯一标识）。
  media_id uuid NOT NULL,
  file_name varchar(255) NOT NULL,
  media_type varchar(128) NOT NULL DEFAULT '',
  size_bytes bigint CHECK(size_bytes IS NULL OR size_bytes>=0),

  -- 挂载时刻：挂到这张单上的业务时间，由应用层提供。
  mounted_at timestamptz NOT NULL,
  mounted_by_user_id uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张单里同一份媒体不挂两次（重复挂载会让凭证清单出现两条一样的）。
  UNIQUE(tenant_id,order_id,media_id),
  CHECK(btrim(file_name)<>'')
);

-- 凭证清单查询（单据详情页）。
CREATE INDEX inventory_adjustment_attachment_order
  ON inventory_adjustment_attachment(tenant_id,order_id,mounted_at);

-- ---------------------------------------------------------------------------
-- 4. inventory_adjustment_audit · 调整单审计（append-only）
-- ---------------------------------------------------------------------------
-- 谁在什么时候建了单、加了行、挂了什么凭证、提交了、批了、驳回了、入账了、作废了。
-- 「没有单据就没有责任人与原因」这句话里的「责任人」就落在这张表上。
CREATE TABLE inventory_adjustment_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_id uuid NOT NULL,
  FOREIGN KEY(tenant_id,order_id) REFERENCES inventory_adjustment_order(tenant_id,id),

  -- 动作。固定枚举而不是自由文本：自由文本让「这个动作算不算入账」变成读日志的人自己判断。
  action varchar(24) NOT NULL CHECK(action IN
    ('CREATED','LINE_ADDED','LINE_REMOVED','ATTACHED','ATTACHMENT_REMOVED',
     'SUBMITTED','APPROVED','REJECTED','POSTED','CANCELLED')),

  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  -- 人读得懂的一句话：为什么、批了什么、入了多少账。
  detail text NOT NULL CHECK(btrim(detail)<>''),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id)
);

-- 审计时间线主查询（一张调整单的完整处理过程）。
CREATE INDEX inventory_adjustment_audit_order
  ON inventory_adjustment_audit(tenant_id,order_id,occurred_at,id);

-- append-only：审计只能追加。
CREATE FUNCTION guard_inventory_adjustment_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'inventory_adjustment_audit 是 append-only：不允许 %，'
    '调整追责依赖未被改写的时间线。', TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER inventory_adjustment_audit_append_only
  BEFORE UPDATE OR DELETE ON inventory_adjustment_audit
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_audit_append_only();

-- ===========================================================================
-- 5. DB 级行为约束
-- ===========================================================================

-- 5.1 状态机：合法的转移白名单 + POSTED 终态 + 身份列不可改。
--
-- 为什么必须在 DB：调整是舞弊高发区，而「这张单是怎么从 DRAFT 一路变成 POSTED 的」
-- 正是最容易被质疑的环节。白名单写死之后，「跳过审批直接过账」是一条会当场报错的
-- SQL，而不是一句需要信任应用代码的承诺。
--
-- 允许的转移（其余一律拒绝）：
--   DRAFT            → PENDING_APPROVAL（提交）、CANCELLED（作废）
--   PENDING_APPROVAL → APPROVED（批准）、REJECTED（驳回）、CANCELLED（撤回）
--   REJECTED         → DRAFT（改完重提）、CANCELLED
--   APPROVED         → POSTED（入账）、CANCELLED
--   POSTED           → （无）终态
--   CANCELLED        → （无）终态
CREATE FUNCTION guard_inventory_adjustment_order_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- 身份列不可改：改了就等于把一张单据换成另一张，而账可能已经按旧的记过了。
  IF (NEW.tenant_id,NEW.code,NEW.created_by_user_id,NEW.created_at)
     IS DISTINCT FROM
     (OLD.tenant_id,OLD.code,OLD.created_by_user_id,OLD.created_at) THEN
    RAISE EXCEPTION '调整单的租户/单号/创建痕迹不可修改：单据身份变了，'
      '而账可能已经按旧身份记过' USING ERRCODE='23514';
  END IF;

  IF NEW.status = OLD.status THEN
    -- 同状态更新只允许补齐痕迹字段（见下方痕迹守恒），不放行内容改写。
    NULL;
  ELSIF NOT (
      (OLD.status='DRAFT'            AND NEW.status IN ('PENDING_APPROVAL','CANCELLED')) OR
      (OLD.status='PENDING_APPROVAL'  AND NEW.status IN ('APPROVED','REJECTED','CANCELLED')) OR
      (OLD.status='REJECTED'         AND NEW.status IN ('DRAFT','CANCELLED')) OR
      (OLD.status='APPROVED'         AND NEW.status IN ('POSTED','CANCELLED'))) THEN
    RAISE EXCEPTION '调整单状态不能从 % 走到 %：状态机只允许 '
      'DRAFT→PENDING_APPROVAL→APPROVED→POSTED，以及 PENDING_APPROVAL→REJECTED、'
      '任一未过账状态→CANCELLED、REJECTED→DRAFT。'
      '**未审批的调整单不能过账** —— 调整必须走单据，不允许在明细页直接改余额。',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;

  -- 痕迹守恒：已提交/已审批/已过账/已作废的单据不能把痕迹抹掉。
  IF OLD.submitted_at IS NOT NULL AND NEW.submitted_at IS DISTINCT FROM OLD.submitted_at THEN
    RAISE EXCEPTION '提交痕迹不可改写' USING ERRCODE='23514';
  END IF;
  IF OLD.decided_at IS NOT NULL AND NEW.decided_at IS DISTINCT FROM OLD.decided_at THEN
    RAISE EXCEPTION '审批痕迹不可改写：已审批的单据不允许换审批人或审批时间' USING ERRCODE='23514';
  END IF;
  IF OLD.posted_at IS NOT NULL AND NEW.posted_at IS DISTINCT FROM OLD.posted_at THEN
    RAISE EXCEPTION '入账痕迹不可改写：已过账的调整单是账的一部分，冲正必须写新单据' USING ERRCODE='23514';
  END IF;
  IF OLD.cancelled_at IS NOT NULL AND NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at THEN
    RAISE EXCEPTION '作废痕迹不可改写' USING ERRCODE='23514';
  END IF;
  -- 过账必须真的有审批痕迹（不是只改 status 就能伪造出「已审批已过账」）。
  IF NEW.status='POSTED' AND NEW.decided_at IS NULL THEN
    RAISE EXCEPTION '过账的调整单必须先有审批痕迹' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inventory_adjustment_order_state
  BEFORE UPDATE ON inventory_adjustment_order
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_order_state();

-- 5.2 调整单**不可删除**：它可能已经过账入账，删单等于账没了凭据。
CREATE FUNCTION guard_inventory_adjustment_order_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '调整单不允许删除：它可能已经过账入账，删掉它等于账没了凭据。'
    ' 要作废一张单请走「作废」，那会留下痕迹。' USING ERRCODE='23514';
END $$;
CREATE TRIGGER inventory_adjustment_order_no_delete
  BEFORE DELETE ON inventory_adjustment_order
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_order_no_delete();

-- 5.3 **入账闸门（核心）**：调整的分录只能挂在已审批的调整单上。
--
-- inventory_entry 的 CHECK 只保证「一条分录非零」，不保证「它凭什么存在」。
-- 这里补上：凡是 ref_type='INVENTORY_ADJUSTMENT' 的业务单，它引用的调整单必须已经
-- APPROVED 或 POSTED。
--
-- 结果：**未审批（DRAFT / PENDING_APPROVAL / REJECTED / CANCELLED）的调整单在数据库
-- 层拿不到任何一条库存分录**。这就是蓝图红线「调整必须走单据」的**可执行形式** ——
-- 拿到连接直接 INSERT 也绕不过去。
--
-- 对其他 ref_type 一律放行：收货/出库/调拨/盘点差异等既有单据不属于通用调整单，
-- 本模块没有资格给它们加规则（那是跨模块越界）。
CREATE FUNCTION guard_inventory_adjustment_entry_approved() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t record;
BEGIN
  SELECT ref_type, ref_id, tenant_id INTO t
    FROM inventory_transaction tx
   WHERE tx.tenant_id = NEW.tenant_id AND tx.id = NEW.transaction_id;
  IF t IS NULL THEN
    RAISE EXCEPTION '库存分录引用了不存在的业务单 %', NEW.transaction_id USING ERRCODE='23503';
  END IF;
  IF t.ref_type <> 'INVENTORY_ADJUSTMENT' THEN
    RETURN NEW;   -- 非通用调整业务单：不管（见本函数注释）
  END IF;
  IF NOT EXISTS (SELECT 1 FROM inventory_adjustment_order o
                  WHERE o.tenant_id = t.tenant_id AND o.id = t.ref_id
                    AND o.status IN ('APPROVED','POSTED')) THEN
    RAISE EXCEPTION '调整单 % 尚未审批通过，不允许写入库存分录：调整必须走单据，'
      '必须先生成调整单并经审批。当前状态不是 APPROVED/POSTED。', t.ref_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inventory_adjustment_entry_approved
  BEFORE INSERT ON inventory_entry
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_entry_approved();

-- 5.4 **入账数量守恒**：Σ 分录 on_hand_delta == Σ 调整单行 qty_delta。
--
-- 延迟约束触发器：入账是「写业务单 → 写若干条分录 → 改余额」三步，必须在**提交前**
-- 一次性核对，中途核对会对着半成品报错。
--
-- 作用：不许少记、不许多记、不许只入一部分就过账。少记的那部分会永远挂着，
-- 而账面已经动过 —— 那是对不上账的经典形态。
CREATE FUNCTION guard_inventory_adjustment_posting_sum() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t record; expected numeric(19,4); actual numeric(19,4);
BEGIN
  SELECT ref_type, ref_id, tenant_id INTO t
    FROM inventory_transaction tx
   WHERE tx.tenant_id = NEW.tenant_id AND tx.id = NEW.transaction_id;
  IF t IS NULL OR t.ref_type <> 'INVENTORY_ADJUSTMENT' THEN RETURN NULL; END IF;

  SELECT coalesce(sum(qty_delta),0) INTO expected
    FROM inventory_adjustment_order_line
   WHERE tenant_id = t.tenant_id AND order_id = t.ref_id;
  SELECT coalesce(sum(e.on_hand_delta),0) INTO actual
    FROM inventory_entry e
   WHERE e.tenant_id = NEW.tenant_id AND e.transaction_id = NEW.transaction_id;
  IF actual IS DISTINCT FROM expected THEN
    RAISE EXCEPTION '调整单入账数量不平：调整单 % 的行合计是 %，实际写进分录的是 %。'
      ' 入账必须与调整单明细逐行相等，不允许少记或多记。', t.ref_id, expected, actual
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER inventory_adjustment_posting_sum
  AFTER INSERT ON inventory_entry
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_posting_sum();

-- 5.5 已过账的调整单行**不可改**数量与定位：过账之后它就是账的一部分。
CREATE FUNCTION guard_inventory_adjustment_line_posted() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.posted_at IS NOT NULL THEN
    RAISE EXCEPTION '已过账的调整单行不可修改：它已经是库存分录的一部分，'
      '冲正必须写一张新单据，而不是改旧行。' USING ERRCODE='23514';
  END IF;
  -- 行与单据的绑定不可改：换了 order_id 等于把一行从一张单挪到另一张。
  IF (NEW.tenant_id,NEW.order_id,NEW.bucket_id,NEW.balance_id,NEW.legal_entity_id,NEW.sku_id,
      NEW.warehouse_id,NEW.location_id,NEW.lot_id,NEW.quality_status_id,
      NEW.qty_delta,NEW.reason_code,NEW.expected_version,NEW.created_at)
     IS DISTINCT FROM
     (OLD.tenant_id,OLD.order_id,OLD.bucket_id,OLD.balance_id,OLD.legal_entity_id,OLD.sku_id,
      OLD.warehouse_id,OLD.location_id,OLD.lot_id,OLD.quality_status_id,
      OLD.qty_delta,OLD.reason_code,OLD.expected_version,OLD.created_at) THEN
    RAISE EXCEPTION '调整单行的数量/定位不可修改：行是一次性快照，'
      '改它等于让账与单据对不上' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inventory_adjustment_line_posted
  BEFORE UPDATE ON inventory_adjustment_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_line_posted();

-- 5.6 行的六维必须与 bucket **逐列相同**。
--
-- 为什么要有这条：行上的 balance_id 与六维都来自应用层，而「balance_id 属于哪个仓 /
-- 哪个 SKU / 哪个质量状态」是有严格含义的。少了这条，一条行可以声称自己属于 A 仓的
-- balance，却定位 B 仓的 bucket —— 复合外键管得住 balance 那一侧，管不住 bucket 那一侧。
--
-- 维度与 balance 的自洽性由行上的复合外键强制；bucket 那一侧由本触发器强制。
CREATE FUNCTION guard_inventory_adjustment_line_dimensions() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b record;
BEGIN
  SELECT k.balance_id,k.legal_entity_id,k.sku_id,k.warehouse_id,k.location_id,k.lot_id,k.quality_status_id
    INTO b
    FROM stock_bucket k
   WHERE k.tenant_id = NEW.tenant_id AND k.id = NEW.bucket_id;
  IF b IS NULL THEN
    RAISE EXCEPTION '调整单行引用的库位明细 % 不存在（或不属于当前租户）', NEW.bucket_id
      USING ERRCODE='23503';
  END IF;
  IF (b.balance_id,b.legal_entity_id,b.sku_id,b.warehouse_id,b.location_id,b.lot_id,b.quality_status_id)
     IS DISTINCT FROM
     (NEW.balance_id,NEW.legal_entity_id,NEW.sku_id,NEW.warehouse_id,NEW.location_id,NEW.lot_id,NEW.quality_status_id) THEN
    RAISE EXCEPTION '调整单行的六维与库位明细 % 不一致：行必须按同一个 bucket 的'
      '法人/SKU/仓库/库位/批次/质量状态定位，不允许张冠李戴', NEW.bucket_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inventory_adjustment_line_dimensions
  BEFORE INSERT OR UPDATE ON inventory_adjustment_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_line_dimensions();

-- 5.7 只有**草稿**能删行。
--
-- 草稿行还不是账的一部分（没提交、更没入账），删掉它只是「还没写完的单据改主意」；
-- 而已经提交的单据行一旦删除，「谁因为什么调了这批货」这条链就断了 —— 调整单
-- 不允许靠删行来消灭证据（6.7 的行不可改 + 6.5 的单不可删是同一条纪律的两面）。
CREATE FUNCTION guard_inventory_adjustment_line_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE st varchar(20);
BEGIN
  SELECT o.status INTO st FROM inventory_adjustment_order o
   WHERE o.tenant_id = OLD.tenant_id AND o.id = OLD.order_id;
  IF st IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION '只有草稿状态的调整单才能删行（当前是 %）：已提交的单据行是证据，'
      '不允许删除。', st USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER inventory_adjustment_line_delete
  BEFORE DELETE ON inventory_adjustment_order_line
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_line_delete();

-- 5.8 只有**草稿**能挂 / 摘附件：凭证一旦随单提交，就是这次调整的依据，不许事后换。
CREATE FUNCTION guard_inventory_adjustment_attachment_draft_only() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE st varchar(20); rid uuid; tid uuid;
BEGIN
  IF TG_OP='DELETE' THEN rid := OLD.order_id; tid := OLD.tenant_id;
  ELSE rid := NEW.order_id; tid := NEW.tenant_id; END IF;
  SELECT o.status INTO st FROM inventory_adjustment_order o
   WHERE o.tenant_id = tid AND o.id = rid;
  IF st IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION '只有草稿状态的调整单才能增删凭证附件（当前是 %）：'
      '凭证是这次调整的依据，随单提交后不可更换。', st USING ERRCODE='23514';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inventory_adjustment_attachment_draft_only
  BEFORE INSERT OR DELETE ON inventory_adjustment_attachment
  FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_attachment_draft_only();

-- ===========================================================================
-- 6. 多租户与授权（与 V1/V2/V62 同一套写法）
-- ===========================================================================
-- 五张新表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'inventory_adjustment_order',
    'inventory_adjustment_order_line',
    'inventory_adjustment_attachment',
    'inventory_adjustment_audit'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- 原因码词表是**全局**参考数据（不按租户分行）：同一套原因码对所有租户语义一致，
-- 而按租户分行会让「原因码 CHECK 与词表」两处真相漂移。
-- 它没有 tenant_id 列，所以不能套用上面的租户 RLS 模板；改为显式放开读。
ALTER TABLE inventory_adjustment_reason DISABLE ROW LEVEL SECURITY;
GRANT SELECT ON inventory_adjustment_reason TO warehouse_app;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  inventory_adjustment_order, inventory_adjustment_order_line,
  inventory_adjustment_attachment, inventory_adjustment_audit TO warehouse_app;
-- 审计是追责凭据：连运行角色都不给改删（触发器再收一道）。
REVOKE UPDATE,DELETE ON inventory_adjustment_audit FROM warehouse_app;
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V62 同口径）。
REVOKE ALL ON
  inventory_adjustment_order, inventory_adjustment_order_line,
  inventory_adjustment_attachment, inventory_adjustment_audit FROM starcon_app;
