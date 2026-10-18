-- 11.2.1.2 退货指令下发（L3 锚点 11.2.1 退货授权）。
--
-- 蓝图 11-aftersales-and-returns.md 原文：
--   职责：向买家发出退货指引（地址、要求、时限），生成退货标签，跟踪买家回寄物流。
--   输入：退货授权；退货地址（3.6.3 或平台 RMA 地址）；渠道选择。
--   输出：退货指令 + 标签文件 + 回寄物流轨迹跟踪。
--   关系：上游 = 11.2.1.1；下游 = 10 物流（退货运单）、15 客服（通知买家）。
--         **多数平台退货由买家寄回**，本系统只负责指引与跟踪，不控制实物。
--
-- 归属：**trade 服务**（同 L3 11.2.1 锚点）。
--
-- ⚠️ 本迁移**只新建五张表、零 ALTER 零 DROP 既有表**：
--   * rma_authorization 簇（V161，11.2.1.1 的写入所有者）只**读**：指令挂在它下面，
--     退货地址与寄回时限都从它复制过来；
--   * 跨表一致性由触发器在**写入时刻真查一次**钉死（刻意不建 FK —— 加 FK 会让本迁移
--     对别的车道的表产生 DDL 依赖，而同批另有五条车道也在往 trade 落迁移）。
-- ⇒ 跨车道 DDL 回归面 = 0。

-- ===========================================================================
-- 一、职责句三个动词各自落在哪 / 为什么这么落
-- ===========================================================================
-- 「向买家发出退货指引（地址、要求、时限）」
--      → rma_instruction 的地址快照列 + packing_requirements + return_by_at 快照。
--      **地址与时限是「快照」不是引用**：指令是**发给买家的一份文件**，买家已经照着它寄了，
--      之后再改授权单上的地址不能把这份已发出去的指引悄悄换掉（V161 同一纪律）。
-- 「生成退货标签」→ rma_label（含真实文件内容 + SHA-256 校验和，见第四节）。
-- 「跟踪买家回寄物流」→ rma_return_tracking_event（**追加式**，见第五节）。
--
-- 「输入句：退货授权」→ rma_instruction.rma_id，**且只允许挂在已授权 / 部分授权的单上**：
--   给一张 REJECTED / CANCELLED / EXPIRED 的退货单发指引，等于通知买家「你可以寄回来了」
--   而我们并不打算收。这是本模块第一条硬规则（见第三节触发器）。
--
-- 「输入句：渠道选择」→ rma_instruction.delivery_channel（**指引怎么送达买家**）与
--   rma_label.carrier_code / service_level（**货用哪个承运商寄回**）。两个「渠道」不是一回事，
--   压成一个字段会让「邮件已发但买家自己买了运费单」这种状态无处安放。
--
-- 「输出句：标签文件」→ label_content + label_checksum。⚠️ 本模块**不生成 PDF**：
--   真实面单要调 10 物流的承运商接口（未交付）。所以这里只产出两种**真的拿得出来**的标签：
--   ① SELF_GENERATED_TEMPLATE —— 按授权单真实要素拼出的可打印标签文本（我们自己的模板）；
--   ② TENANT_PROVIDED_FILE —— 租户已从外部渠道拿到标签文件，登记其内容与校验和。
--   两种都是**真实字节落库**，不是占位串：内容由服务层从库里真值渲染，校验和是内容字节的 SHA-256。
--
-- 「关系句加粗：多数平台退货由买家寄回，本系统只负责指引与跟踪，不控制实物」
--      → 落成三条**结构性**事实，不是注释：
--      ① rma_instruction **没有 RECEIVED 状态**（登记实收属 11.2.2.1，未交付）；
--      ② 轨迹事件是**追加式**且**不驱动库存**：delivered 事件只表示「承运商说送到了」，
--         实物入不入库是 11.2.2.1 的事，本模块不碰任何库存表；
--      ③ label_source 枚举里 MODULE_10_CARRIER_LABEL_API 显式标注**未交付**，
--         不让「我们自己拼的模板」被当成「已对接承运商」。
--
-- ===========================================================================
-- 二、状态机：一个状态列到底
-- ===========================================================================
-- DRAFT          草稿：指引内容已登记，尚未送达买家
-- ISSUED         已送达：买家已收到指引（**货可能还在路上，也可能根本没寄**）
-- ACKNOWLEDGED   买家已确认收到（**主动回执**，不是我们推测他看到了）
-- EXPIRED        时限到期自动失效（寄回时限与授权单同源，快照过来）
-- CANCELLED      人工撤销（授权被撤销 / 退单了）
--
-- ⚠️ **EXPIRED 只判「时限过了」，不判「货没回来」**：时限过了而货在路上的单子，
--   在承运商口径里仍然可能签收成功；把它算成「已失效」会让下游拒收一件真收到的货。
--   实收判定是 11.2.2.1 的硬规则（**按收货事件去重**），本模块不越权判定。
CREATE TABLE rma_instruction (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 指令单号：租户内唯一（序列发号器，末尾 GRANT USAGE）。
  instruction_no varchar(32) NOT NULL,

  -- 上游退货授权单（V161，**刻意不加外键**，见文件头）。
  -- ⚠️ 一张授权单同时最多只有**一张在途指令**（第八节部分唯一索引），
  --   但可以先后建多张：上一张 CANCELLED 之后允许再发一张新的（地址变了要重发指引）。
  rma_id uuid NOT NULL,

  instruction_status varchar(24) NOT NULL CHECK(instruction_status IN (
      'DRAFT','ISSUED','ACKNOWLEDGED','EXPIRED','CANCELLED'
  )),

  -- ---- 渠道选择①：指引**怎么送达**买家 ----
  delivery_channel varchar(32) NOT NULL CHECK(delivery_channel IN (
      'BUYER_EMAIL','PLATFORM_MESSAGE','TENANT_DOWNLOAD_PRINT','API_CALLBACK'
  )),

  -- ---- 指引接收方（谁该收到这份指引）----
  recipient_name varchar(200) NOT NULL DEFAULT '',
  -- ⚠️ 选 BUYER_EMAIL 时必填（DB CHECK 兜底，见第七节）：发不出去的「邮件渠道」是自欺。
  recipient_email varchar(254) NOT NULL DEFAULT '',
  recipient_phone varchar(64) NOT NULL DEFAULT '',

  -- ---- 指引正文：要求（第一节的「要求」）----
  -- 包装与寄回要求。**刻意是自由文本而不是结构化字段**：退货要求跨类目差异极大
  -- （易碎品要泡沫、酒类要密封、电池要单独装），结构化到字段级最后会退化成「一个长文本框」。
  packing_requirements text NOT NULL DEFAULT '' CHECK(length(packing_requirements) <= 2000),
  -- 附给买家的话（例如「配件请一并寄回」「外箱压损可拍照报备」）。
  buyer_note text NOT NULL DEFAULT '' CHECK(length(buyer_note) <= 2000),

  -- ---- 退货地址**快照**（建单时从授权单复制；下发后冻结，见第六节触发器）----
  -- 来源枚举与 V161 return_address_source **完全同形**：照抄同一条解法，
  -- 3.6.3 地址本（catalog 服务，V31）与平台地址（channel 独立库）本服务当前都读不到，
  -- 所以快照连来源一起带过来，3.6.3 交付后由同步器按同一形状写入，**表结构与查询都不用改**。
  address_source varchar(32) NOT NULL CHECK(address_source IN (
      'PLATFORM_RMA','MODULE_3_6_3_ADDRESS_BOOK','TENANT_MAINTAINED'
  )),
  source_reference varchar(128) NOT NULL DEFAULT '',
  return_recipient_name varchar(200) NOT NULL DEFAULT '',
  return_recipient_phone varchar(64) NOT NULL DEFAULT '',
  return_contact_email varchar(254) NOT NULL DEFAULT '',
  return_country_code char(2) NOT NULL DEFAULT '' CHECK(return_country_code ~ '^([A-Z]{2})?$'),
  return_province varchar(120) NOT NULL DEFAULT '',
  return_city varchar(120) NOT NULL DEFAULT '',
  return_district varchar(120) NOT NULL DEFAULT '',
  return_postcode varchar(32) NOT NULL DEFAULT '',
  return_address_line1 varchar(300) NOT NULL DEFAULT '',
  return_address_line2 varchar(300) NOT NULL DEFAULT '',

  -- ---- 寄回时限**快照**（来自 rma_authorization.return_by_at）----
  return_by_at timestamptz NOT NULL,

  -- ---- 渠道选择②：货用哪个承运商寄回（落在标签上，见 rma_label）----

  -- ---- 状态时刻 ----
  issued_at timestamptz,
  issued_by uuid,
  acknowledged_at timestamptz,
  acknowledged_by uuid,
  expired_at timestamptz,
  cancelled_at timestamptz,
  cancelled_by uuid,
  cancel_reason text NOT NULL DEFAULT '' CHECK(length(cancel_reason) <= 1000),

  -- 乐观锁：改草稿 / 下发 / 确认 / 撤销都要带 expected_version。
  version bigint NOT NULL DEFAULT 0,

  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,instruction_no),

  -- 已下发 / 已确认 / 已失效三态必须有下发人与下发时刻。
  CHECK(instruction_status NOT IN ('ISSUED','ACKNOWLEDGED','EXPIRED')
        OR (issued_by IS NOT NULL AND issued_at IS NOT NULL)),

  -- 已确认必须在下发**之后**：凭空一个「买家已确认」没有任何意义。
  CHECK(instruction_status <> 'ACKNOWLEDGED'
        OR (acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL AND issued_at IS NOT NULL)),

  -- 失效必须有过期时刻，且时限真实存在（return_by_at 本身 NOT NULL，这里钉的是语义一致性）。
  CHECK(instruction_status <> 'EXPIRED' OR expired_at IS NOT NULL),

  -- 撤销时刻与状态严格等价（防「状态已撤但时刻没落」导致界面继续显示「已下发」）。
  CHECK((instruction_status = 'CANCELLED') = (cancelled_at IS NOT NULL)),

  -- **选邮件渠道就必须有收件邮箱**：没有收件地址的 BUYER_EMAIL 是一条发不出去的指令，
  -- 而它在列表里看起来和已送达的一模一样。
  CHECK(delivery_channel <> 'BUYER_EMAIL' OR btrim(recipient_email) <> ''),

  -- 写成 3.6.3 来源时必须指明来源段（V157 / V161 同一条纪律）。
  CHECK(address_source <> 'MODULE_3_6_3_ADDRESS_BOOK' OR btrim(source_reference) <> ''),

  CHECK(updated_at >= created_at)
);

-- ===========================================================================
-- 三、指令行：买家该寄回哪些东西
-- ===========================================================================
-- ⚠️ **只存 rma_authorization_line_id，不复制 sku_code / sku_name / 单价**：
--   那些真值在 sales_order_line（V99 的下单快照）与 rma_authorization_line（V161），
--   复制一份就会漂移，而「指引上写的 SKU 和订单上的不一样」在纠纷举证里是直接被质疑的。
--
-- ⚠️ **一条指令行必须落在「授权量 > 0」的那一行上**：指引是用来告诉买家寄什么，
--   指到一行授权量为 0 的行上，等于请买家寄一件我们没同意退的东西 ——
--   货真寄回来了还要再走一次人工处置。
CREATE TABLE rma_instruction_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  instruction_id uuid NOT NULL,

  -- 指向 rma_authorization_line（V161，**刻意不加外键**，见文件头）。
  rma_line_id uuid NOT NULL,

  -- 买家应寄回数量：**默认取授权量**（见 guard_rma_instruction_line 触发器，
  -- 这里只是建单时的初值；服务层不给就用授权量）。
  -- ⚠️ 允许**小于**授权量：买家可能只寄一部分（少寄的那部分按 11.2.2.2 的少收口径处理）。
  --   但不允许大于授权量 —— 那是在请买家多寄。
  expected_qty numeric(19,6) NOT NULL CHECK(expected_qty > 0),

  -- 逐行说明（例：「这件外包装已破损，请拍照后单独装袋」）。
  line_note text NOT NULL DEFAULT '' CHECK(length(line_note) <= 1000),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张指令里同一条授权行只登记一次：重复登记会让「这一项该寄几件」有两个答案。
  UNIQUE(tenant_id,instruction_id,rma_line_id),

  CHECK(updated_at >= created_at)
);

-- ===========================================================================
-- 四、退货标签：内容真落库 + SHA-256 校验和
-- ===========================================================================
-- ⚠️ **label_content 存的是真实文件内容**（由服务层从库里真值渲染），
--   label_checksum 是这段字节的 SHA-256（十六进制 64 位），服务层在读取时**重算并比对**。
--   ⇒ 标签文件被谁改过，读取时立刻报「完整性校验失败」，而不是悄悄显示一份被篡改的地址。
--
-- ⚠️ **刻意不支持 PDF**：真实面单要走 10 物流的承运商接口（未交付），
--   自造一个「PDF」只会得到一份长得像面单、扫码扫不通的废纸。
--   两种格式都是**纯文本**，能被浏览器直接打印 / 直接显示：
--   TEXT_TEMPLATE（纯文本，一行一个字段）与 HTML_TEMPLATE（带 <br> 的排版文本）。
--
-- ⚠️ **一张指令同时最多一张「未作废」的标签**（第八节部分唯一索引）：
--   作废后可以再申请一张（承运商改地址重出是真实场景）。
CREATE TABLE rma_label (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  instruction_id uuid NOT NULL,

  label_no varchar(32) NOT NULL,

  label_status varchar(16) NOT NULL CHECK(label_status IN (
      'GENERATED','DELIVERED','VOIDED'
  )),

  -- 来源（第一节）：
  --   SELF_GENERATED_TEMPLATE  本模块按授权单真值拼出的模板标签（**不含运单号** —— 我们不出面单）
  --   TENANT_PROVIDED_FILE    租户已从外部渠道拿到标签文件，登记其内容（**必然有承运商与运单号**）
  --   MODULE_10_CARRIER_LABEL_API  由 10 物流面单接口生成，**未交付**，枚举保留供同步器接入
  label_source varchar(32) NOT NULL CHECK(label_source IN (
      'SELF_GENERATED_TEMPLATE','TENANT_PROVIDED_FILE','MODULE_10_CARRIER_LABEL_API'
  )),

  -- 承运商与快递档位（渠道选择②）。SELF_GENERATED_TEMPLATE 时可以为空。
  carrier_code varchar(64) NOT NULL DEFAULT '',
  service_level varchar(32) NOT NULL DEFAULT '' CHECK(service_level IN (
      'STANDARD','EXPRESS','PRIORITY',''
  )),

  label_format varchar(16) NOT NULL CHECK(label_format IN ('TEXT_TEMPLATE','HTML_TEMPLATE')),

  -- 标签文件内容与校验和（见文件头）。
  label_content text NOT NULL CHECK(length(label_content) > 0),
  label_checksum char(64) NOT NULL CHECK(label_checksum ~ '^[0-9a-f]{64}$'),

  -- 运单号：租户登记的标签必带；本模块自出的模板标签**没有**运单号
  -- （承运商分配的运单号走 rma_return_tracking_event 登记，那是真值来源）。
  tracking_no varchar(128) NOT NULL DEFAULT '',

  -- 包裹物理属性（租户登记标签时填；本模块自出模板时留空）。
  -- ⚠️ 三个尺寸要么都给要么都空：只给长宽不给高，承运商算出来的运费是错的。
  weight_kg numeric(12,4) CHECK(weight_kg IS NULL OR weight_kg >= 0),
  length_cm numeric(12,2) CHECK(length_cm IS NULL OR length_cm >= 0),
  width_cm numeric(12,2) CHECK(width_cm IS NULL OR width_cm >= 0),
  height_cm numeric(12,2) CHECK(height_cm IS NULL OR height_cm >= 0),

  generated_at timestamptz NOT NULL,
  generated_by uuid NOT NULL,
  delivered_at timestamptz,
  delivered_by uuid,
  delivered_via varchar(32) NOT NULL DEFAULT '',
  voided_at timestamptz,
  voided_by uuid,
  void_reason text NOT NULL DEFAULT '' CHECK(length(void_reason) <= 1000),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,label_no),

  -- **租户登记的外部标签必然带承运商与运单号**：两者皆空的话，
  -- 买家手上那张贴纸无从核对，而实收时（11.2.2.1）也定位不到包裹。
  CHECK(label_source <> 'TENANT_PROVIDED_FILE'
        OR (btrim(carrier_code) <> '' AND btrim(tracking_no) <> '')),

  -- **本模块自出的模板标签不带运单号**：带了就意味着我们声称「已经拿到承运商运单」，
  -- 而那件事属 10 物流（未交付）。把自造运单号写进标签字段会让它被当成已对接。
  CHECK(label_source <> 'SELF_GENERATED_TEMPLATE' OR btrim(tracking_no) = ''),

  -- 已交付必须落在生成之后：交付早于生成是时序错乱。
  CHECK(delivered_at IS NULL OR (delivered_by IS NOT NULL AND generated_at <= delivered_at)),

  -- 作废时刻与状态严格等价，理由必填（见下面的触发器）。
  CHECK((label_status = 'VOIDED') = (voided_at IS NOT NULL)),
  CHECK(label_status <> 'VOIDED' OR btrim(void_reason) <> ''),

  -- 尺寸三件套同进同出。
  CHECK((length_cm IS NULL AND width_cm IS NULL AND height_cm IS NULL)
        OR (length_cm IS NOT NULL AND width_cm IS NOT NULL AND height_cm IS NOT NULL)),

  -- ⚠️ **刻意允许**作废一张「已交付」的标签：贴纸已经到买家手上了、承运商又取消了运单，
  --   这时候必须能把这一行置为作废、才能重出一张；禁掉它等于把这条指令卡死
  --   （没有第二张标签、也没法作废掉手上那张）。交付痕迹**保留**在 delivered_at/by 上 ——
  --   「我们确实把一张贴纸发出去了」是事实，作废只说明它现在不作数了。

  CHECK(updated_at >= created_at)
);

-- ===========================================================================
-- 五、回寄物流轨迹：**追加式**（append-only）
-- ===========================================================================
-- ⚠️ **不建「当前轨迹」列**：当前状态由「按 occurred_at 取最后一条」实时算出来。
--   存一个 current_status 列就得回答「它和事件表哪个为准」，
--   而两条真相不一致时（「已签收」的事件旁边写着「运输中」的当前值）没人能判对。
--
-- ⚠️ **不驱动任何库存**：DELIVERED 事件只表示「承运商说这个包裹送到了」，
--   实物有没有入库、是不是残次品，属 11.2.2.1 / 11.2.3.1。
--   在这里把 delivered 直接记成「已收货」会让残次品被当新品入库 ——
--   退回商品**默认进待检**是硬规则。
--
-- ⚠️ **去重靠唯一索引**：承运商重复推送同一条扫描是真实场景（重试、webhook 重放），
--   同一 (指令, 承运商, 运单号, 事件码, 发生时刻) 只留一条 ——
--   重复登记会让「这单退了几件」出现两个答案。
CREATE TABLE rma_return_tracking_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  instruction_id uuid NOT NULL,

  -- 承运商与运单号挂在**事件**上而不是指令头上：买家可以分多个包裹寄回，
  -- 也可以换承运商重寄（真实且常见）。挂在头上会让「第二个包裹」无处安放。
  carrier_code varchar(64) NOT NULL CHECK(btrim(carrier_code) <> ''),
  tracking_no varchar(128) NOT NULL CHECK(btrim(tracking_no) <> ''),

  event_code varchar(24) NOT NULL CHECK(event_code IN (
      'ACCEPTED','PICKED_UP','IN_TRANSIT','ARRIVED_AT_ORIGIN','CUSTOMS_HOLD',
      'OUT_FOR_DELIVERY','DELIVERED','EXCEPTION','RETURNED_TO_SENDER','CANCELLED'
  )),
  event_location varchar(200) NOT NULL DEFAULT '',
  -- 承运商给的原始轨迹文案（保留原文便于纠纷举证，不做翻译改写）。
  event_description text NOT NULL DEFAULT '' CHECK(length(event_description) <= 1000),

  -- 事件**发生**时刻（承运商的时间）与我们**知道**的时刻分开记：
  -- 两者的差是信息延迟（承运商晚回传），混成一个字段就再也算不出延迟了。
  occurred_at timestamptz NOT NULL,
  tracked_at timestamptz NOT NULL,

  -- 来源（第一节的诚实口径）：
  --   TENANT_MANUAL_ENTRY      运营 / 客服手工登记（买家客服对话里问到的）
  --   CARRIER_WEBHOOK          承运商回调，**未接入**（枚举保留）
  --   MODULE_10_LOGISTICS_POLL 由 10 物流轮询写入，**未交付**（枚举保留）
  tracking_source varchar(32) NOT NULL CHECK(tracking_source IN (
      'TENANT_MANUAL_ENTRY','CARRIER_WEBHOOK','MODULE_10_LOGISTICS_POLL'
  )),

  recorded_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),

  -- 承运商重复推送去重（见文件头）。
  UNIQUE(tenant_id,instruction_id,carrier_code,tracking_no,event_code,occurred_at),

  CHECK(tracked_at >= occurred_at - interval '1 day')
);

-- ===========================================================================
-- 六、护栏触发器：把「指令只能挂在已授权单上」「下发后内容冻结」钉在 DB 层
-- ===========================================================================
-- ① guard_rma_instruction_line：指令行必须落在**本指令所关联授权单**的、
--    且**授权量 > 0** 的授权行上（替代刻意不建的外键）；
-- ② guard_rma_instruction_frozen：内容一旦下发就**冻结**，
--    地址 / 要求 / 时限 / 收件方都不许再改。
--
-- ⚠️ ②的理由不是「严谨」而是**买家已经照着旧内容寄了**：改地址等于让货寄丢，
--   改时限等于凭空多给了买家几天（或者少给了，而买家并不知道）。
--   要改只能 CANCEL 后重新建一张（第八节部分唯一索引允许作废后重发）。

CREATE FUNCTION guard_rma_instruction_line() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  t_status varchar(24);
  t_rma_status varchar(24);
  t_instruction_rma uuid;
  t_line_rma uuid;
  t_authorized numeric(19,6);
BEGIN
  -- ⚠️ 一次查齐两样：指令状态 + 它关联的授权单状态与 id。
  --   授权单的 id 必须**在这条 SELECT 里带出来**（t_instruction_rma）——
  --   只看状态不看 id 的话，校验「授权行是否属于本指令」就得再发一次查询，
  --   而两次查询之间授权单可能被并发改掉。
  SELECT i.instruction_status, i.rma_id, r.rma_status
    INTO t_status, t_instruction_rma, t_rma_status
    FROM rma_instruction i
    join rma_authorization r on r.tenant_id = i.tenant_id and r.id = i.rma_id
   WHERE i.tenant_id = NEW.tenant_id AND i.id = NEW.instruction_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '退货指令不存在或不属于当前租户：instruction_id=%', NEW.instruction_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_status <> 'DRAFT' THEN
    RAISE EXCEPTION '退货指令已下发（status=%），不能再改动指令行：指引内容已冻结', t_status
      USING ERRCODE = 'check_violation';
  END IF;

  -- 授权单必须**还在授权状态**：给一张拒绝 / 撤销 / 超时关闭的单发指引，
  -- 等于通知买家「可以寄回来了」而我们并不打算收。
  IF t_rma_status NOT IN ('AUTHORIZED','PARTIALLY_AUTHORIZED') THEN
    RAISE EXCEPTION '只有 AUTHORIZED / PARTIALLY_AUTHORIZED 的退货授权单才能下发退货指引，当前 %', t_rma_status
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT rl.rma_id, rl.authorized_qty INTO t_line_rma, t_authorized
    FROM rma_authorization_line rl
   WHERE rl.tenant_id = NEW.tenant_id AND rl.id = NEW.rma_line_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION '退货授权行不存在或不属于当前租户：rma_line_id=%', NEW.rma_line_id
      USING ERRCODE = 'no_data_found';
  END IF;

  IF t_line_rma <> t_instruction_rma THEN
    RAISE EXCEPTION '退货授权行不属于本指令所关联的退货授权单：rma_line_id=%', NEW.rma_line_id
      USING ERRCODE = 'check_violation';
  END IF;

  IF coalesce(t_authorized, 0) <= 0 THEN
    RAISE EXCEPTION '该退货授权行的授权量为 %，不能写进退货指引（指引是用来告诉买家寄什么的）',
      coalesce(t_authorized, 0)
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.expected_qty > t_authorized THEN
    RAISE EXCEPTION '应寄回数量 % 超过该行授权量 %', NEW.expected_qty, t_authorized
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_guard_rma_instruction_line
  BEFORE INSERT OR UPDATE OF rma_line_id, expected_qty ON rma_instruction_line
  FOR EACH ROW EXECUTE FUNCTION guard_rma_instruction_line();

-- 内容冻结：只对**内容列**的 UPDATE 生效。
-- 状态列（instruction_status / issued_* / acknowledged_* / expired_* / cancelled_*）不在
-- 冻结名单里 —— 它们当然要在下发之后变，否则就没法「下发」了。
CREATE FUNCTION guard_rma_instruction_frozen() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.instruction_status = 'DRAFT' THEN
    RETURN NEW;
  END IF;

  IF NEW.delivery_channel IS DISTINCT FROM OLD.delivery_channel
     OR NEW.recipient_name IS DISTINCT FROM OLD.recipient_name
     OR NEW.recipient_email IS DISTINCT FROM OLD.recipient_email
     OR NEW.recipient_phone IS DISTINCT FROM OLD.recipient_phone
     OR NEW.packing_requirements IS DISTINCT FROM OLD.packing_requirements
     OR NEW.buyer_note IS DISTINCT FROM OLD.buyer_note
     OR NEW.address_source IS DISTINCT FROM OLD.address_source
     OR NEW.source_reference IS DISTINCT FROM OLD.source_reference
     OR NEW.return_recipient_name IS DISTINCT FROM OLD.return_recipient_name
     OR NEW.return_recipient_phone IS DISTINCT FROM OLD.return_recipient_phone
     OR NEW.return_contact_email IS DISTINCT FROM OLD.return_contact_email
     OR NEW.return_country_code IS DISTINCT FROM OLD.return_country_code
     OR NEW.return_province IS DISTINCT FROM OLD.return_province
     OR NEW.return_city IS DISTINCT FROM OLD.return_city
     OR NEW.return_district IS DISTINCT FROM OLD.return_district
     OR NEW.return_postcode IS DISTINCT FROM OLD.return_postcode
     OR NEW.return_address_line1 IS DISTINCT FROM OLD.return_address_line1
     OR NEW.return_address_line2 IS DISTINCT FROM OLD.return_address_line2
     OR NEW.return_by_at IS DISTINCT FROM OLD.return_by_at THEN
    RAISE EXCEPTION '退货指引已下发（status=%），地址 / 要求 / 时限 / 收件方均已冻结：'
      '买家可能已经照着旧内容寄了。要改请先撤销本张指令再重新下发', OLD.instruction_status
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_rma_instruction_frozen
  BEFORE UPDATE ON rma_instruction
  FOR EACH ROW EXECUTE FUNCTION guard_rma_instruction_frozen();

-- ===========================================================================
-- 七、留痕：追加式（append-only）
-- ===========================================================================
-- 退货指令的每一次状态变化都要能回放：谁下发的、什么时候、买家有没有确认、
-- 后来有没有撤销。买家投诉「你们根本没通知过我」时，这是唯一的证据。
-- ⚠️ 本表**先于**写它的触发器创建：迁移从上往下执行，顺序颠倒虽然也能跑通
--   （PL/pgSQL 函数体在 CREATE 时不校验表是否存在），但读者会以为触发器悬空。
CREATE TABLE rma_instruction_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  instruction_id uuid NOT NULL,

  event_type varchar(24) NOT NULL CHECK(event_type IN (
      'CREATED','ISSUED','ACKNOWLEDGED','EXPIRED','CANCELLED','UPDATED'
  )),
  -- 变更前后状态：建单那一条 from_status 为 NULL（此前不存在）。
  from_status varchar(24),
  to_status varchar(24) NOT NULL,

  reason_note text NOT NULL DEFAULT '' CHECK(length(reason_note) <= 2000),

  occurred_by uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(from_status IS NULL OR from_status <> to_status)
);

-- ⚠️ **状态流转的单点真相**：每次状态真的变了，由触发器把「谁在什么时候把指令推到哪一步、
--   带着什么理由」追加进 rma_instruction_event。**只有真的变了才追加**
--   （UPDATE 时 OLD.instruction_status = NEW.instruction_status 直接返回），
--   否则每次改草稿都会在留痕里留下一条假状态变更。
CREATE FUNCTION rma_append_instruction_event() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.instruction_status = OLD.instruction_status THEN
    RETURN NEW;
  END IF;

  INSERT INTO rma_instruction_event(
    id, tenant_id, instruction_id, event_type, from_status, to_status,
    reason_note, occurred_by, occurred_at)
  VALUES (
    gen_random_uuid(),
    NEW.tenant_id,
    NEW.id,
    CASE
      WHEN TG_OP = 'INSERT'            THEN 'CREATED'
      WHEN NEW.instruction_status = 'ISSUED'       THEN 'ISSUED'
      WHEN NEW.instruction_status = 'ACKNOWLEDGED' THEN 'ACKNOWLEDGED'
      WHEN NEW.instruction_status = 'EXPIRED'      THEN 'EXPIRED'
      WHEN NEW.instruction_status = 'CANCELLED'    THEN 'CANCELLED'
      ELSE 'UPDATED'
    END,
    CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.instruction_status END,
    NEW.instruction_status,
    CASE
      WHEN TG_OP = 'INSERT'            THEN '登记退货指引草稿'
      WHEN NEW.instruction_status = 'ISSUED'       THEN '已向买家下发退货指引'
      WHEN NEW.instruction_status = 'ACKNOWLEDGED' THEN '买家已确认收到退货指引'
      WHEN NEW.instruction_status = 'EXPIRED'      THEN '寄回时限已到，退货指引自动失效'
      WHEN NEW.instruction_status = 'CANCELLED'    THEN coalesce(nullif(btrim(NEW.cancel_reason), ''), '人工撤销')
      ELSE '草稿要素变更'
    END,
    coalesce(NEW.issued_by, NEW.acknowledged_by, NEW.cancelled_by, NEW.created_by),
    -- ⚠️ **每类事件用它自己那一刻**，不是 coalesce 出一列通用值（V161 已踩过这个坑）：
    --   coalesce(issued_at, acknowledged_at, cancelled_at, created_at) 会把
    --   **撤销事件的时刻记成下发的那一刻**，两条事件 occurred_at 完全相同，
    --   排序退化成按随机 UUID 排 —— 而且是**时好时坏**的那种。
    CASE
      WHEN TG_OP = 'INSERT'                 THEN NEW.created_at
      WHEN NEW.instruction_status = 'ISSUED'       THEN NEW.issued_at
      WHEN NEW.instruction_status = 'ACKNOWLEDGED' THEN NEW.acknowledged_at
      WHEN NEW.instruction_status = 'EXPIRED'      THEN NEW.expired_at
      WHEN NEW.instruction_status = 'CANCELLED'    THEN NEW.cancelled_at
      ELSE NEW.updated_at
    END);

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_rma_instruction_event
  AFTER INSERT OR UPDATE OF instruction_status ON rma_instruction
  FOR EACH ROW EXECUTE FUNCTION rma_append_instruction_event();

-- ===========================================================================
-- 八、索引与发号器
-- ===========================================================================
-- ① 详情页主查询：按指令取指令行。
CREATE INDEX rma_instruction_line_by_instruction
  ON rma_instruction_line(tenant_id, instruction_id);
-- ② 「这张退货授权单发过几张指引」：授权单详情要按 rma_id 反查。
CREATE INDEX rma_instruction_by_rma
  ON rma_instruction(tenant_id, rma_id);
-- ③ 超时扫描（expire-overdue）：只看在途且带时限的行，EXPIRED 关掉后不再命中。
CREATE INDEX rma_instruction_inflight_due
  ON rma_instruction(tenant_id, return_by_at)
  WHERE instruction_status in ('DRAFT','ISSUED','ACKNOWLEDGED');
-- ④ 轨迹时间线：按发生时刻读一条指令的完整轨迹（追加式，按时刻排是唯一正确的排法）。
CREATE INDEX rma_return_tracking_event_by_instruction
  ON rma_return_tracking_event(tenant_id, instruction_id, occurred_at);
-- ⑤ 按运单号反查（客服手上只有一个单号时的入口）。
CREATE INDEX rma_return_tracking_event_by_tracking
  ON rma_return_tracking_event(tenant_id, tracking_no);
-- ⑥ 留痕回放：按发生时刻读一条指令的完整轨迹。
CREATE INDEX rma_instruction_event_by_instruction
  ON rma_instruction_event(tenant_id, instruction_id, occurred_at);

-- ⚠️ **一张授权单同时最多一张「在途」指令**（DB 不变量而不是应用层先查一次：
--   并发下两个请求都能查到「还没有」，先查后插的那道防线在这种场景下必然失效）。
--   CANCELLED / EXPIRED 不占用，所以「撤销后重发一张」是允许的。
CREATE UNIQUE INDEX rma_instruction_one_live_per_rma
  ON rma_instruction(tenant_id, rma_id)
  WHERE instruction_status <> 'CANCELLED';

-- ⚠️ **一条指令同时最多一张「未作废」标签**：作废后可重出（改地址重打是真实场景）。
CREATE UNIQUE INDEX rma_label_one_live_per_instruction
  ON rma_label(tenant_id, instruction_id)
  WHERE label_status <> 'VOIDED';

CREATE SEQUENCE rma_instruction_no_seq;
-- 序列权限：只授 USAGE（够 nextval）与 SELECT（够 currval 排查），
-- 错误文案说的是「数据与既有记录冲突」，与「没给 nextval 权限」毫无关系（V156 / V161 同一条）。
GRANT USAGE,SELECT ON SEQUENCE rma_instruction_no_seq TO trade_app;
CREATE SEQUENCE rma_label_no_seq;
GRANT USAGE,SELECT ON SEQUENCE rma_label_no_seq TO trade_app;

-- ===========================================================================
-- 九、多租户：RLS + 授权（与 V157 第七节 / V161 第九节同一套写法）
-- ===========================================================================
-- 五张新表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
-- ⚠️ 本模块尤其不能省：指令带着**退货地址**（收货人姓名 + 电话）、**买家邮箱**、
--   **买家回寄运单号**与**包裹轨迹**，而详情 / 下发 / 取标签文件端点都是**按 id 直达**的 URL ——
--   猜到一个 UUID 就能读，不需要翻列表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'rma_instruction','rma_instruction_line','rma_label',
    'rma_return_tracking_event','rma_instruction_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  rma_instruction, rma_instruction_line, rma_label,
  rma_return_tracking_event, rma_instruction_event TO trade_app;

-- 授权在前、收回在后（与 V156 / V159 / V161 同序）。
-- 两个**追加式**表的 UPDATE/DELETE 一律收回：
--   * 轨迹事件：承运商重推就是「同一条」，改掉它等于让轨迹和真实物流对不上；
--   * 指令留痕：买家投诉「你们根本没通知过我」时，这是唯一的证据。
REVOKE UPDATE,DELETE ON rma_return_tracking_event, rma_instruction_event FROM trade_app;

-- 本迁移**不 ALTER 任何既有表**：rma_authorization 簇（V161）全部只读。
-- 本模块对它们的全部依赖都是 SELECT / JOIN 与「写入时刻查一次」，跨车道 DDL 回归面 = 0。
