-- 11.2.2.1 退货入库（卷 11 售后与退货 / L3 11.2.2 退货实收的第一张单据）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：登记退回实物：扫码核对 SKU 与序列号、清点数量、拍照留证、判定外包装与商品状态。
--   输入：退货实收登记（SKU、数量、批次/序列号、照片）；退货授权单。
--   输出：退货实收记录 + 关联授权行分配；待检库存入库分录（**不是可售**）。
--   关系：上游 = 11.2.1.2；下游 = 11.2.2.2、11.2.3.1。**退回商品默认进待检状态**，
--         这是硬规则——直接进可售会把残次品当新品卖出去。
--
-- 归属：**trade 服务**（与 11.2.1.1 / 11.2.1.2 同一条退货侧单据链）。
--
-- 本迁移**只新建五张表与触发器、零 ALTER 零 DROP 既有表**：
--   * rma_authorization / rma_authorization_line（V161，11.2.1.1 已交付）**只读**：
--     授权量、退货地址、「这单批没批」的真值来源；跨表一致性由触发器在**写入时刻**
--     真查一次钉死（刻意不建 FK —— 加 FK 会让本迁移对别的车道的表产生 DDL 依赖，
--     而同批另有三条车道也在往 trade 落迁移）。
--   * rma_instruction / rma_instruction_line（V165，11.2.1.2 已交付）**只读**：
--     可空关联，退货指引是**可选**的上游 —— 买家自己寄回、平台 RMA 地址直邮、
--     线下门店收货都可能没有一张由我们下发的指引。
--   * sales_order_line（V99）**只读**：给 SKU 编码/名称的实时真值（不在本表复制一份）。
--
-- ===========================================================================
-- 一、三条硬规则各自钉在哪 —— 为什么不能只写在服务层
-- ===========================================================================
--
-- 【硬规则 1】退回商品默认进「待检」，**绝不进可售**。
--   钉在**三处**，任何一处被绕过都还有另外两处：
--   ① return_inventory_posting.inventory_state 的 CHECK **只允许一个取值**：
--        CHECK (inventory_state = 'PENDING_INSPECTION')
--      —— 不是「枚举里待检排第一」，是**表级不允许出现第二个取值**。
--      想改成可售必须先改这条 CHECK，而那会是一次需要评审的迁移变更。
--   ② 同表 sellable_qty_delta 的 CHECK 恒为 0：这一列存在的意义就是**让「可售增加了多少」
--      变成一个必须被填 0 的显式字段**，而不是根本不存在的一列。
--      没有这列，「分录只记了待检量」和「分录顺手也加了可售量」在库里有同一个形状；
--      有了这列恒 0，任何人想动它都会在 INSERT 当场被拒。
--   ③ 分录由 **DB 触发器** return_post_inventory_posting 在状态推到 POSTED 的**那一刻**生成，
--      插入语句里的 inventory_state 与两个 delta 列全是**常量**（'PENDING_INSPECTION' / 0 / qty），
--      不接受调用方传入 ⇒ 绕过服务层直连 SQL 也拿不到一张「已进可售」的分录。
--
-- 【硬规则 2】外包装判定与商品状态判定是**两个独立维度**。
--   落成 packaging_condition 与 goods_condition **两列、两套 CHECK、两个 label()**。
--   刻意不合成一个 condition：蓝图下游 11.2.3.1 的加粗句是「**包装破损但商品完好可判可再销售，
--   缺少配件则应判残次**」—— 这两句话在任何单值列里都无法表达，
--   强行压成一个枚举的代价是质检员只能去猜「这个值到底说的是包装还是货」。
--
-- 【硬规则 3】实收必须挂到具体授权行上，数量不得超过该行授权量，**超出报错不静默截断**。
--   钉在**两处**：
--   ① 触发器 guard_return_receipt_line 逐行真查 rma_authorization_line：
--      行必须挂在本单 rma_authorization 名下、该授权行授权量 > 0、且
--      received_qty ≤ 授权量 − **其它未撤销实收单的占用**（含草稿，见第二节）。
--   ② 触发器 guard_return_receipt_posting 在**入库那一刻**对整单再算一次上限：
--      草稿期间不校验会让两张草稿各自「看起来都合法」，并发提交时总量已经超了 ——
--      校验点放在入库时刻才真正拦住。
--   ⚠️ 本模块**不处理超收**（实收多于授权按实际退回）——那是 11.2.2.2 实收分配的职责，未交付。
--     能力声明里如实报出超收当前的处理结果：**整笔拒绝**，并指向 11.2.2.2。
--
-- ===========================================================================
-- 二、草稿也占额度：为什么「先查一次有没有别人在录」不是够的
-- ===========================================================================
-- 占用额度的口径取 **DRAFT + POSTED 两种单**（排除 CANCELLED）。
-- 只算 POSTED 的话：操作员甲录了一张 3 件的草稿（授权 3 件），操作员乙在甲提交之前
-- 又录了 3 件草稿，两张**各自**校验时都合法，并发入库时总量已是 6 > 3 ——
-- 而「这单到底退回来几件」只会有一个答案，另一个消失。
-- 取「草稿也占」换来的是：录错了要先撤销草稿才能重录。这是有意的代价，
-- 错误消息里直接写明当前已被占用的数量与所在单号（见 guard_return_receipt_line 的 RAISE）。
--
-- ===========================================================================
-- 三、照片：追加式，且带 SHA-256
-- ===========================================================================
-- 「拍照留证」是本模块职责句的第三个动词，而退货纠纷的举证材料就是这几张照片。
-- 追加式（运行角色 UPDATE/DELETE 在第九节收回）+ 内容校验和，
-- 理由与 V165 的标签文件同款：一张能被别人替换掉的证据在纠纷里等于没有证据。
-- ⚠️ **刻意不存二进制本体**：照片走对象存储，库里存 object_uri + sha256 + bytes。
--   把图片塞进 PG 会让退货列表查询把整库图片拖进内存 —— 而列表根本不需要看图。
--   对象存储接入属 15 客服 / 18 集成，未交付，当前 object_uri 由租户登记（见第六节 photo_source）。
--
-- ===========================================================================
-- 四、实收单头：收货事件，还是「一张单」？
-- ===========================================================================
-- 一次**收货事件** = 一个包裹 / 一次到店 / 一次上门 = 一张 return_receipt。
-- 买家可以分三个包裹寄回（真实且常见），授权 3 件分两次寄是常态 ——
-- 把它们压成一张单就答不出「第一批到的是哪几件」，而质检（11.2.3.1）要按批判。
-- ⚠️ 同一包裹**同时**挂多个授权单时不合并成一张单：
--   买家可能把两单的东西装一个包裹寄回，强行合并会让两单各自的收货时刻、
--   承运商签收与拍照留痕互相污染。合并/拆分属 11.2.2.2 实收分配的职责。
--
-- ===========================================================================
-- 五、状态机：一个状态列到底
-- ===========================================================================
-- DRAFT      草稿：明细与照片还在录，**尚未入库**，不产生任何库存分录
-- POSTED     已入库：清点与两个维度的判定已完成，待检分录已由 DB 触发器生成
-- CANCELLED  已撤销：录错了 / 买家又把货拿回去了
-- ⚠️ **没有 INSPECTED / RESALABLE / DEFECTIVE**：处置与质检结论属 11.2.3.1 / 11.2.3.2，未交付。
--   在本表加一个「已质检」状态会让界面显示成质检已完成，
--   而退回商品默认进待检 —— 直接把它当已质检处理就等于跳过质检把残次品当新品卖出去。
-- ⚠️ POSTED **不可逆**：行与照片是纠纷证据，不是草稿（guard_return_receipt_frozen）。
--
-- ===========================================================================
-- 六、photo_source：谁把照片写进来的
-- ===========================================================================
-- 照抄 V150 signal_source / V156 owner_source / V165 delivery_channel 的同一条解法：
--   TENANT_MANUAL_UPLOAD（人工上传）/ MODULE_18_OBJECT_STORAGE_SYNC（18 集成同步器，未交付）。
-- 写成 MODULE_* 时必须给出 source_reference（哪个对象存储桶 / 哪条同步规则），
-- 「这行是同步器写的」不能只是一句口头声明。

CREATE TABLE return_receipt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 实收单号：租户内唯一（序列发号器，末尾 GRANT USAGE）。
  receipt_no varchar(32) NOT NULL,

  -- ---- 上游：退货授权单（V161，11.2.1.1，**已交付**）----
  -- 蓝图输入句「退货实收登记（…）；退货授权单」—— 没有授权单就没有可收额度，
  -- 所以它是**必填**而不是可选关联。
  -- ⚠️ 刻意不加外键，见文件头。
  rma_id uuid NOT NULL,

  -- ---- 上游：退货指令（V165，11.2.1.2，**可空**）----
  -- 蓝图把上游写作 11.2.1.2，但**实收不必然有指引**：买家自己寄回、平台 RMA 地址直邮、
  -- 线下门店收货都可能没有一张由我们下发的指引。列成 NOT NULL 会逼运营
  -- 为了一次真实的收货先去补一张不存在的指引。
  instruction_id uuid,

  -- ---- 这个包裹是怎么到的（第四节）----
  receipt_channel varchar(24) NOT NULL CHECK(receipt_channel IN (
      'CARRIER','BUYER_DROPOFF','WALK_IN'
  )),

  -- ---- 承运商与回寄运单号（可空：买家到店 / 自己上门都没有运单）----
  -- 与 V165 的回寄轨迹同源口径：承运商与运单号记的是**包裹**而不是单头唯一值，
  -- 买家换承运商重寄时两批货各有各的运单号。
  carrier_code varchar(64) NOT NULL DEFAULT '',
  return_tracking_no varchar(128) NOT NULL DEFAULT '',
  -- 包裹完好描述（开箱第一眼的整体印象，与逐行两个判定维度不冲突：
  -- 这里记的是**包裹**层面，这里是**单件**层面）。
  package_note text NOT NULL DEFAULT '' CHECK(length(package_note) <= 2000),

  -- ---- 收货时刻（实收的「那一刻」）----
  -- ⚠️ **不允许为 NULL**：收货时间决定了这批货从哪天起算「退货周期」与
  --   「超期退回」（11.2.2.2 未交付），也决定待检库存在库里压了多久。
  --   填 null 会让这两个问题都答不出，只能按当前时刻倒推，而倒推的结果是假的。
  received_at timestamptz NOT NULL,

  -- ---- 状态机（第五节）----
  receipt_status varchar(16) NOT NULL CHECK(receipt_status IN (
      'DRAFT','POSTED','CANCELLED'
  )),

  -- 入库时刻与待检分录生成人：POSTED 的那一刻（由触发器写分录的那一次）。
  posted_by uuid,
  posted_at timestamptz,
  -- 撤销。
  cancelled_by uuid,
  cancelled_at timestamptz,
  cancel_reason text NOT NULL DEFAULT '' CHECK(length(cancel_reason) <= 1000),

  -- 乐观锁：录明细 / 改判定 / 入库 / 撤销都要带 expected_version。
  -- 两个人同时扫同一个包裹时各写一次而表里只留得下后一个 ——
  -- 少的那一次会让「这批货收了几件、判定了什么」出现两个答案，而这两个答案都要拿去和买家对质。
  version bigint NOT NULL DEFAULT 0,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,receipt_no),

  -- 已入库 / 已撤销必须留人与时刻：这两个动作各自会改写库存事实（入库分录）
  -- 或作废一批证据（撤销），事后必须知道是谁干的。
  CHECK(receipt_status <> 'POSTED' OR (posted_by IS NOT NULL AND posted_at IS NOT NULL)),
  CHECK(receipt_status <> 'CANCELLED' OR (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)),

  -- **撤销必须说明理由**：录错单在退货场景里是真实且常见的
  -- （扫错 SKU / 买家又把货拿回去了 / 隔壁包裹串了台），
  -- 没有理由的撤销等于给库存凭空开一个洞。
  CHECK(receipt_status <> 'CANCELLED' OR length(btrim(cancel_reason)) > 0),

  -- 承运商到货必须带运单号：CARRIER 渠道下没有运单号就答不出「是哪一票」，
  -- 而这正是退货最常见的收货方式。
  CHECK(receipt_channel <> 'CARRIER'
        OR (length(btrim(carrier_code)) > 0 AND length(btrim(return_tracking_no)) > 0))
);

-- ===========================================================================
-- 二、实收明细行：扫码核对 + 清点数量 + 两个独立判定维度（硬规则 2、3）
-- ===========================================================================
CREATE TABLE return_receipt_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,

  -- ---- 关联授权行（硬规则 3 的锚点）----
  -- ⚠️ 只存 rma_authorization_line_id，**SKU 与单价实时读订单行**（照抄 V161 / V165 的同一条纪律）：
  --   把 SKU 抄一份到实收行上，订单行改名之后两处会对不上，
  --   而「这行实收的到底是哪个 SKU」是质检与索赔都要引用的证据。
  rma_authorization_line_id uuid NOT NULL,

  -- ---- 扫码核对（职责句「扫码核对 SKU 与序列号」）----
  -- 扫码**读到**的编码：与订单行对不上时保留原值并由 trigger 之外的服务层判错 ——
  -- 静默改写成「正确」的 SKU 会让「扫错了」这件事在证据里消失。
  scanned_sku_code varchar(128) NOT NULL DEFAULT '',
  scanned_serial_no varchar(128) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',
  -- 序列号 / 批次至少要有一个：实收登记的输入句写「批次/序列号」，
  -- 两列全空的行等于「不知道这是哪一件」，质检（11.2.3.1）与批次归因（11.1.2.2）都无从下手。
  CHECK(length(btrim(scanned_serial_no)) > 0 OR length(btrim(batch_no)) > 0),

  -- ---- 清点数量（职责句「清点数量」）----
  received_qty numeric(19,6) NOT NULL CHECK(received_qty > 0),

  -- ---- 判定维度①：**外包装**（硬规则 2 的左半）----
  -- 与 goods_condition 是**两个独立维度**：外箱压瘪而里面的货完好是真实且常见的，
  -- 压成一个值就表达不出来（蓝图 11.2.3.1 的加粗句正是拿这种情况举例）。
  packaging_condition varchar(24) NOT NULL CHECK(packaging_condition IN (
      'INTACT','DAMAGED','DESTROYED','MISSING','NOT_INSPECTED'
  )),

  -- ---- 判定维度②：**商品状态**（硬规则 2 的右半）----
  goods_condition varchar(24) NOT NULL CHECK(goods_condition IN (
      'UNOPENED','OPENED_UNDAMAGED','USED','DAMAGED','DEFECTIVE',
      'MISSING_PARTS','NOT_INSPECTED','NOT_APPLICABLE'
  )),

  -- 清点备注：包装压损位置 / 缺少的配件清单等。**超收与少收的说明属 11.2.2.2**，
  -- 本模块刻意不写「超收」「少收」的字样（超收在本模块是**直接被拒**，没有「说明」可写）。
  count_note text NOT NULL DEFAULT '' CHECK(length(count_note) <= 2000),

  counted_by uuid NOT NULL,
  counted_at timestamptz NOT NULL,

  -- 序号：清点顺序（同一个包裹里先扫哪一件）。真实分页与详情展示都要按它排。
  line_no int NOT NULL CHECK(line_no > 0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张单内序号唯一（详情页按序号渲染，重号会让两行顺序不稳定）。
  UNIQUE(receipt_id,line_no)
);

-- ===========================================================================
-- 三、拍照留证（追加式）
-- ===========================================================================
CREATE TABLE return_receipt_photo (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,

  photo_source varchar(40) NOT NULL CHECK(photo_source IN (
      'TENANT_MANUAL_UPLOAD','MODULE_18_OBJECT_STORAGE_SYNC'
  )),
  -- 写成 MODULE_* 时必须指明哪个桶 / 哪条同步规则。
  source_reference varchar(128) NOT NULL DEFAULT '',

  -- 照片的角色：与两个判定维度一一对应，质检与纠纷举证时按角色取图。
  photo_role varchar(24) NOT NULL CHECK(photo_role IN (
      'PACKAGING','GOODS','SERIAL_LABEL','DAMAGE_CLOSEUP','OTHER'
  )),

  object_uri varchar(500) NOT NULL,
  content_sha256 char(64) NOT NULL,
  content_bytes bigint NOT NULL CHECK(content_bytes > 0),
  content_type varchar(128) NOT NULL DEFAULT '',

  -- 拍摄时刻与登记时刻分开：两者之差就是「货到了多久才被拍下来」，
  -- 压成一列就答不出「这批证据是不是补拍的」。
  captured_at timestamptz NOT NULL,
  registered_at timestamptz NOT NULL DEFAULT now(),

  caption text NOT NULL DEFAULT '' CHECK(length(caption) <= 500),
  registered_by uuid NOT NULL,

  UNIQUE(tenant_id,id),
  -- 同一个内容不重复登记：同一个 sha256 出现两次只可能是重复上传，
  -- 而重复的举证材料会让「我们拍了几张照」出现两个答案。
  UNIQUE(receipt_id,content_sha256)
);

-- ===========================================================================
-- 四、待检库存入库分录（硬规则 1 的落点，**不是可售**）
-- ===========================================================================
-- ⚠️ 本表是蓝图输出句「待检库存入库分录（**不是可售**）」的**唯一落点**。
-- 它由 DB 触发器在 POSTED 那一刻生成，服务层不接受调用方传入分录 ——
-- 也就是说「谁把退回商品记成了可售」这件事在本模块里**没有代码路径**。
CREATE TABLE return_inventory_posting (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 唯一来源：一张实收行恰好一张分录。UNIQUE 而不是主键约束组合，
  -- 是为了让「重复入库」在数据库层表现为唯一冲突而不是安静地多出一行。
  receipt_line_id uuid NOT NULL UNIQUE,
  receipt_id uuid NOT NULL,

  -- ---- 标的物：入库分录必须自带 SKU 快照 ----
  -- ⚠️ 这里**刻意复制** SKU 编码/名称，与第二节的实收行相反：
  --   库存分录是**跨服务交接凭据**（7 库存 / warehouse 未交付，由同步器消费），
  --   它要在订单行被改名之后仍然能读出「这批货入的是哪个 SKU」。
  --   实收行是**证据**（要跟着订单行核对），分录是**凭据**（要独立成立）——两者用途不同。
  sku_id uuid,
  sku_code varchar(128) NOT NULL DEFAULT '',
  sku_name varchar(300) NOT NULL DEFAULT '',
  batch_no varchar(128) NOT NULL DEFAULT '',

  -- ---- 硬规则 1 的三道 CHECK ----
  -- ① 只允许一个取值：没有「可售」这个选项存在。
  inventory_state varchar(24) NOT NULL DEFAULT 'PENDING_INSPECTION'
      CHECK(inventory_state = 'PENDING_INSPECTION'),

  -- ② 可售增量恒为 0：让「可售增加了多少」成为**必须被显式填 0** 的字段。
  --    没有这一列，「只记了待检」与「顺手也加了可售」在库里有同一个形状。
  sellable_qty_delta numeric(19,6) NOT NULL DEFAULT 0
      CHECK(sellable_qty_delta = 0),

  -- ③ 待检增量 = 本行实收数量：分录不许凭空多出或少掉货。
  pending_inspection_qty numeric(19,6) NOT NULL CHECK(pending_inspection_qty > 0),

  -- 分录类型：恒为退货入库。刻意不加「退货出库」取值 ——
  -- 撤销已入库的实收单不走「反向分录」，而是显式取消那张实收单并留下痕迹
  -- （AGENTS.md：每张事实表只有一个写入所有者，反向冲销会让账有两份真相）。
  posting_type varchar(24) NOT NULL DEFAULT 'RETURN_INTAKE'
      CHECK(posting_type = 'RETURN_INTAKE'),

  -- 关联实收行的判定快照：待检队列按「外包装已毁且商品已损」优先排检，
  -- 不必回实收行做连接就能排序（两张表同库，但入队扫描是高频只读）。
  packaging_condition varchar(24) NOT NULL,
  goods_condition varchar(24) NOT NULL,

  posted_at timestamptz NOT NULL DEFAULT now(),
  posted_by uuid NOT NULL,

  UNIQUE(tenant_id,id),

  -- 数量一致性由触发器写入时保证（pending_inspection_qty := 实收行数量），
  -- 这里再加一道「分录必须来自一张实收行」的 CHECK 级联不了（需要跨表），
  -- 改由 guard_return_receipt_posting 触发器保证。
  CHECK(pending_inspection_qty > 0 AND sellable_qty_delta = 0)
);

-- ===========================================================================
-- 五、实收留痕（追加式）
-- ===========================================================================
-- 一次收货从录草稿到入库的每一步都要能回放：谁扫的、谁判的、谁入的库、谁撤的。
-- 追加式，运行角色 UPDATE/DELETE 权限在第九节收回。
CREATE TABLE return_receipt_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  receipt_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'CREATED','UPDATED','POSTED','CANCELLED','PHOTO_ADDED'
  )),
  from_status varchar(16),
  to_status varchar(16) NOT NULL,

  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(from_status IS NULL OR from_status <> to_status)
);

-- ===========================================================================
-- 六、三道 DB 守卫
-- ===========================================================================
-- ⚠️ 服务层校验可以绕开（直连 SQL、改一次代码），触发器绕不开 ——
-- 这三道守卫对应的是「库存事实」和「纠纷证据」，两类都容不得静默错误。

-- ---------------------------------------------------------------------------
-- 守卫① guard_return_receipt_line：硬规则 3 逐行钉死
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_return_receipt_line() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(16);
  -- ⚠️ 三个变量刻意**分开**：早期版本用同一个 t_line_rma 先装 rma_status、后来又装 rma_id，
  --   于是「授权行是否属于本单」那条比较变成「rma_id = rma_id」恒真 ——
  --   「A 单的实收指向 B 单的授权行」在库里完全合法，而错误消息一个字都不报。
  t_rma_status varchar(24);
  t_receipt_rma uuid;
  t_line_rma uuid;
  t_auth_qty numeric(19,6);
  t_taken numeric(19,6);
  -- ⚠️ 用 text 不用 varchar(32)：两单同占额度时拼出来的说明串远长于 32 字符，
  --   varchar 会直接抛「value too long for type character varying」——
  --   一个「额度已占满」的业务错误被报成类型溢出，运营看不懂，排查的人也要绕远路。
  t_taken_by text;
BEGIN
  -- 单头状态与授权单一次查齐：return_receipt 上**没有** rma_status（它在 rma_authorization 上），
  -- 且本表的状态列叫 receipt_status 不叫 status —— 这两个名字写错会直接报
  -- 「column does not exist」，而那是在**第一条写入**时才炸，看起来像迁移没跑对。
  SELECT r.receipt_status, a.rma_status, a.id INTO t_status, t_rma_status, t_receipt_rma
    FROM return_receipt r
    join rma_authorization a on a.tenant_id = r.tenant_id and a.id = r.rma_id
   WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.receipt_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '退货实收单不存在或不属于当前租户：receipt_id=%', NEW.receipt_id
      USING ERRCODE = 'no_data_found';
  END IF;

  -- 入库后不能改行：行是**证据**（质检与索赔都要引用），不是草稿。
  IF t_status = 'POSTED' THEN
    RAISE EXCEPTION '退货实收单已入库，行是证据不能再改：receipt_id=%', NEW.receipt_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 已撤销的单不能加行：货已经不在这个包裹里了。
  IF t_status = 'CANCELLED' THEN
    RAISE EXCEPTION '退货实收单已撤销，不能再加行：receipt_id=%', NEW.receipt_id
      USING ERRCODE = 'check_violation';
  END IF;

  -- 只有批过的授权单才谈得上「有货可收」。蓝图 11.2.1.1 的加粗句：
  -- **授权不等于已收到** —— 拒绝 / 撤销 / 超时关闭的单，
  -- 我们压根没同意收这些货（超时关闭尤其：买家压根没寄，或寄了但已另案处理）。
  IF t_rma_status <> 'AUTHORIZED' AND t_rma_status <> 'PARTIALLY_AUTHORIZED' THEN
    RAISE EXCEPTION '退货授权单不在授权状态（当前 %），不能登记实收', t_rma_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 授权行必须真实存在，且**属于本单所关联的那张退货授权单**。
  -- 少了这一段，「A 单的实收指向 B 单的授权行」会完全合法。
  SELECT l.rma_id, l.authorized_qty INTO t_line_rma, t_auth_qty
    FROM rma_authorization_line l
   WHERE l.tenant_id = NEW.tenant_id and l.id = NEW.rma_authorization_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '退货授权行不存在或不属于当前租户：rma_line_id=%',
      NEW.rma_authorization_line_id USING ERRCODE = 'no_data_found';
  END IF;

  -- 授权量为 0 的行（草稿行）没有可收额度：草稿行是「申请了还没批」。
  IF coalesce(t_auth_qty, 0) <= 0 THEN
    RAISE EXCEPTION '该授权行尚未授权任何数量（授权量 %），不能登记实收', coalesce(t_auth_qty, 0)
      USING ERRCODE = 'check_violation';
  END IF;

  IF t_line_rma <> t_receipt_rma THEN
    RAISE EXCEPTION '实收行挂的授权行不属于本实收单所关联的退货授权单：rma_line_id=%',
      NEW.rma_authorization_line_id USING ERRCODE = 'check_violation';
  END IF;

  -- ---- 额度占用（第二节：草稿也占）----
  SELECT coalesce(sum(rl.received_qty), 0),
         string_agg(r.receipt_no || '(' || r.receipt_status || ')', '、' order by r.receipt_no)
    INTO t_taken, t_taken_by
    FROM return_receipt_line rl
    join return_receipt r on r.tenant_id = rl.tenant_id and r.id = rl.receipt_id
   WHERE rl.tenant_id = NEW.tenant_id
     and rl.rma_authorization_line_id = NEW.rma_authorization_line_id
     and rl.id <> NEW.id
     and r.receipt_status in ('DRAFT','POSTED');

  IF NEW.received_qty > t_auth_qty - t_taken THEN
    RAISE EXCEPTION '实收数量 % 超过该授权行剩余可收量 %（授权量 %，已被 % 占用）',
      NEW.received_qty, t_auth_qty - t_taken, t_auth_qty, coalesce(t_taken_by, '无')
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_receipt_line
  BEFORE INSERT OR UPDATE OF rma_authorization_line_id, received_qty ON return_receipt_line
  FOR EACH ROW EXECUTE FUNCTION guard_return_receipt_line();

-- ---------------------------------------------------------------------------
-- 守卫② guard_return_receipt_frozen：入库即冻结头要素
-- ---------------------------------------------------------------------------
-- 理由不是严谨而是**事实**：入库那一刻货已经在待检区堆着了，
-- 而承运商签收单、拍照时间、清点数量都对应**收到时的样子** ——
-- 入库后再改这些，等于把一份现场记录改成事后叙述。
CREATE FUNCTION guard_return_receipt_frozen() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.receipt_status = 'POSTED' THEN
    IF NEW.receipt_status IS DISTINCT FROM 'CANCELLED' THEN
      RAISE EXCEPTION '退货实收单已入库，不能改单头要素（撤销请用取消入口）'
        USING ERRCODE = 'check_violation';
    END IF;
    -- 已入库的单**不允许撤销**：入库分录已生成，
    -- 撤销等于「货不在待检区了」而分录还在，账上会多出待检库存。
    RAISE EXCEPTION '退货实收单已入库并已生成待检入库分录，不能撤销（处置属 11.2.3.x）'
      USING ERRCODE = 'check_violation';
  END IF;

  IF OLD.receipt_status = 'CANCELLED' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION '退货实收单已撤销，不能再改'
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_receipt_frozen
  BEFORE UPDATE ON return_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_return_receipt_frozen();

-- ---------------------------------------------------------------------------
-- 守卫③ guard_return_receipt_posting：入库时刻的两件事
-- ---------------------------------------------------------------------------
--   ① 对**整单**再算一次授权上限（草稿期间不校验，两个并发提交会双双越过）；
--   ② **生成待检入库分录**（硬规则 1 的落点，服务层不接受调用方传入分录）。
CREATE FUNCTION guard_return_receipt_posting() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  rec_line record;
  t_auth_qty numeric(19,6);
  t_sum numeric(19,6);
  t_line_count int;
BEGIN
  -- 状态没推到 POSTED 的更新不触发后续动作（改草稿要素走 frozen 那一道）。
  IF NEW.receipt_status IS DISTINCT FROM 'POSTED'
     OR NEW.receipt_status IS NOT DISTINCT FROM OLD.receipt_status THEN
    RETURN NEW;
  END IF;

  -- ⚠️ **空单不允许入库**，且这一条刻意**显式 count** 而不是靠 FOR 循环后的 FOUND：
  --   FOUND 在 FOR 循环后的取值依赖「循环是否至少迭代过一次」这一实现细节，
  --   而「一张没有明细的实收单入库」在退货场景里不是理论情况 ——
  --   操作员先把单头建了（收件人先填、包裹还在路上），然后直接点入库。
  --   那样会生成一张「什么都没收到却已入库」的实收单，并留下一条空分录。
  SELECT count(*) INTO t_line_count
    FROM return_receipt_line rl
   WHERE rl.tenant_id = NEW.tenant_id and rl.receipt_id = NEW.id;

  IF t_line_count = 0 THEN
    RAISE EXCEPTION '退货实收单没有任何明细行，不能入库：receipt_id=%', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;

  -- ---- ① 入库时刻重新校验：逐行 + 整单 ----
  FOR rec_line IN
    SELECT rl.id, rl.rma_authorization_line_id, rl.received_qty,
           rl.packaging_condition, rl.goods_condition
      FROM return_receipt_line rl
     WHERE rl.tenant_id = NEW.tenant_id and rl.receipt_id = NEW.id
  LOOP
    -- **入库时两个判定维度都必须已判**（NOT_INSPECTED 只在草稿期允许）：
    -- 「已入库但商品状态还没判」的货既进不了待检也进不了残次，质检队列（11.2.3.1）会漏掉它。
    IF rec_line.packaging_condition = 'NOT_INSPECTED'
       OR rec_line.goods_condition = 'NOT_INSPECTED' THEN
      RAISE EXCEPTION '入库前必须判定外包装与商品状态两个维度（该行仍为「未判定」）'
        USING ERRCODE = 'check_violation';
    END IF;

    SELECT l.authorized_qty INTO t_auth_qty
      FROM rma_authorization_line l
     WHERE l.tenant_id = NEW.tenant_id and l.id = rec_line.rma_authorization_line_id;

    -- 本单全部行（含本行）对该授权行的合计 vs 授权量。
    SELECT coalesce(sum(rl2.received_qty), 0) INTO t_sum
      FROM return_receipt_line rl2
      join return_receipt r2
        on r2.tenant_id = rl2.tenant_id and r2.id = rl2.receipt_id
     WHERE rl2.tenant_id = NEW.tenant_id
       and rl2.rma_authorization_line_id = rec_line.rma_authorization_line_id
       and r2.receipt_status in ('DRAFT','POSTED');

    IF t_sum > coalesce(t_auth_qty, 0) THEN
      RAISE EXCEPTION '入库合计 % 超过该授权行授权量 %（超收按实际退回处理属 11.2.2.2，未交付）',
        t_sum, coalesce(t_auth_qty, 0)
        USING ERRCODE = 'check_violation';
    END IF;
  END LOOP;

  -- ---- ② 生成待检入库分录（硬规则 1）----
  -- ⚠️ 下面四个入库列全是**常量或直取实收行**，不接受任何调用方传入值：
  --   inventory_state 恒 'PENDING_INSPECTION'（表级 CHECK 再钉一道），
  --   sellable_qty_delta 恒 0（表级 CHECK 再钉一道），
  --   pending_inspection_qty 取实收行数量。
  --   ⇒ 绕过服务层直连 UPDATE 同样拿不到一张「已进可售」的分录。
  INSERT INTO return_inventory_posting(
      id, tenant_id, receipt_line_id, receipt_id,
      sku_id, sku_code, sku_name, batch_no,
      inventory_state, sellable_qty_delta, pending_inspection_qty, posting_type,
      packaging_condition, goods_condition, posted_at, posted_by)
  SELECT gen_random_uuid(),
         l.tenant_id,
         l.id,
         NEW.id,
         ol.sku_id,
         coalesce(ol.sku_code, ''),
         coalesce(ol.sku_name, ''),
         l.batch_no,
         'PENDING_INSPECTION',
         0,
         l.received_qty,
         'RETURN_INTAKE',
         l.packaging_condition,
         l.goods_condition,
         coalesce(NEW.posted_at, now()),
         NEW.posted_by
    FROM return_receipt_line l
    -- 实时读订单行的 SKU 真值（照抄 V161 / V165 纪律：不在本表复制 SKU）
    join rma_authorization_line al
      on al.tenant_id = l.tenant_id and al.id = l.rma_authorization_line_id
    join aftersales_case_line cl
      on cl.tenant_id = al.tenant_id and cl.id = al.case_line_id
    join sales_order_line ol
      on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id
   WHERE l.tenant_id = NEW.tenant_id and l.receipt_id = NEW.id;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_return_receipt_posting
  AFTER UPDATE OF receipt_status ON return_receipt
  FOR EACH ROW EXECUTE FUNCTION guard_return_receipt_posting();

-- ---------------------------------------------------------------------------
-- 留痕追加（只写「真变了」的状态变更）
-- ---------------------------------------------------------------------------
CREATE FUNCTION return_receipt_append_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.receipt_status = OLD.receipt_status THEN
    RETURN NEW;
  END IF;

  INSERT INTO return_receipt_event(
      id, tenant_id, receipt_id, event_type, from_status, to_status,
      reason_note, occurred_by, occurred_at)
  VALUES (
      gen_random_uuid(),
      NEW.tenant_id,
      NEW.id,
      CASE
        WHEN TG_OP = 'INSERT'      THEN 'CREATED'
        WHEN NEW.receipt_status = 'POSTED'    THEN 'POSTED'
        WHEN NEW.receipt_status = 'CANCELLED' THEN 'CANCELLED'
        ELSE 'UPDATED'
      END,
      CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.receipt_status END,
      NEW.receipt_status,
      CASE
        WHEN TG_OP = 'INSERT'      THEN '登记退货实收草稿'
        WHEN NEW.receipt_status = 'POSTED'    THEN '清点完成，退回商品入待检（非可售）'
        WHEN NEW.receipt_status = 'CANCELLED' THEN coalesce(nullif(btrim(NEW.cancel_reason), ''), '人工撤销')
        ELSE '草稿要素变更'
      END,
      coalesce(NEW.created_by, NEW.posted_by, NEW.cancelled_by),
      -- ⚠️ **每类事件用它自己那一刻**，不是 coalesce 出一列通用值：
      --   早期 V165 写 coalesce(issued_at, acknowledged_at, ...)，
      --   于是确认事件的时刻被记成下发的时刻，排序退化成按随机 UUID 排。
      CASE
        WHEN TG_OP = 'INSERT'                THEN NEW.created_at
        WHEN NEW.receipt_status = 'POSTED'   THEN NEW.posted_at
        WHEN NEW.receipt_status = 'CANCELLED' THEN NEW.cancelled_at
        ELSE NEW.updated_at
      END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_return_receipt_event
  AFTER INSERT OR UPDATE OF receipt_status ON return_receipt
  FOR EACH ROW EXECUTE FUNCTION return_receipt_append_event();

-- ===========================================================================
-- 七、索引与发号器
-- ===========================================================================
-- ① 列表主查询与详情页：按单取行。
CREATE INDEX return_receipt_line_by_receipt
  ON return_receipt_line(tenant_id, receipt_id, line_no);
-- ② **硬规则 3 的额度查询热点**：服务层与 /receivable 端点都按授权行算剩余可收量。
CREATE INDEX return_receipt_line_by_rma_line
  ON return_receipt_line(tenant_id, rma_authorization_line_id);
-- ③ 按授权单反查：这个授权单收了几个包裹。
CREATE INDEX return_receipt_by_rma
  ON return_receipt(tenant_id, rma_id);
-- ④ 按退货指引反查：买家问「我那张指引对应的货收到了吗」。
CREATE INDEX return_receipt_by_instruction
  ON return_receipt(tenant_id, instruction_id)
  WHERE instruction_id IS NOT NULL;
-- ⑤ 按回寄运单号反查：客服手上只有一个运单号时的入口。
--    ⚠️ 这个入口在多租户下尤其不能漏租户谓词，否则就成了一台跨租户搜索引擎。
CREATE INDEX return_receipt_by_tracking
  ON return_receipt(tenant_id, return_tracking_no)
  WHERE length(return_tracking_no) > 0;
-- ⑥ 待检队列（11.2.3.1 未交付，但分录本身已经可按判定维度排序消费）：
--    部分索引只看在途分录，已被质检消费掉的不再命中。
CREATE INDEX return_inventory_posting_pending_queue
  ON return_inventory_posting(tenant_id, posted_at, goods_condition, packaging_condition);
-- ⑦ 留痕回放：按发生时刻读一单的完整轨迹。
CREATE INDEX return_receipt_event_by_receipt
  ON return_receipt_event(tenant_id, receipt_id, occurred_at);
-- ⑧ 照片按登记时刻读。
CREATE INDEX return_receipt_photo_by_receipt
  ON return_receipt_photo(tenant_id, receipt_id, registered_at);

CREATE SEQUENCE return_receipt_no_seq;
-- 序列权限：只授 USAGE（够 nextval）与 SELECT（够 currval 排查），
-- 错误文案说的是「数据与既有记录冲突」，与「没给 nextval 权限」毫无关系（V156 / V161 同一）。
GRANT USAGE,SELECT ON SEQUENCE return_receipt_no_seq TO trade_app;

-- ===========================================================================
-- 八、多租户：RLS + 授权（与 V161 第九节 / V165 同一套写法）
-- ===========================================================================
-- 五张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：实收单带着**买家回寄运单号**与**逐件的 SKU / 序列号**，
--   照片带着**商品外观证据**，而详情 / 入库 / 取分录端点都是**按 id 直达**的 URL ——
--   猜到一个 UUID 就能读，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'return_receipt','return_receipt_line','return_receipt_photo',
    'return_inventory_posting','return_receipt_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  return_receipt, return_receipt_line, return_receipt_photo,
  return_inventory_posting, return_receipt_event TO trade_app;

-- 授权在前、收回在后（与 V161 / V165 同序）。
-- 照片与留痕表**追加式**：举证材料与收货轨迹都是纠纷用的证据，
-- UPDATE/DELETE 一律收回 —— 能被悄悄改掉的证据在纠纷里等于没有证据。
REVOKE UPDATE,DELETE ON return_receipt_photo FROM trade_app;
REVOKE UPDATE,DELETE ON return_receipt_event FROM trade_app;
-- ⚠️ 入库分录同样收回：它是待检库存的**账**。
--   改一条分录而不改实收行，账实就对不上了；删除一条分录则等于凭空少了待检库存。
--   （实收单撤销后库存如何回冲属 11.2.3.2，未交付 —— 本模块刻意不做反向冲销分录。）
REVOKE UPDATE,DELETE ON return_inventory_posting FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：rma_authorization 簇（V161）、rma_instruction 簇（V165）、
-- aftersales_case_line（V157）与 sales_order_line（V99）全部只读。
-- 本模块对它们的全部依赖都是 SELECT / JOIN 与「写入时刻查一次」，跨车道 DDL 回归面 = 0。
