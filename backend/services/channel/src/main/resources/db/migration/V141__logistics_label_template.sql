-- ===========================================================================
-- 10.2.2.2 面单模板（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.2.2.2 面单模板
--   - 职责：维护面单模板与打印规格（尺寸、条码类型、附加声明、电池标识、语言版本），
--           支持按渠道与国家配置。
--   - 输入：模板定义；渠道要求。
--   - 输出：模板 + 打印任务（经 8.3.4.2）。
--   - 关系：上游 = 10.1.1.2；下游 = 8.3.4.2 打印。
--           **附加声明（如电池、液体）漏打会导致清关问题**，模板配置要与渠道规则强绑定。
--
-- 归属 channel：蓝图 L2 表 10.2 运单管理「主责服务 = trade + channel」；模板是
-- **渠道侧的合规规格**，渠道要求来自同服务的 10.1.1.2（V136），同库同服务一跳可读。
-- 下游 8.3.4.2 面单与标签打印在 warehouse（V73），经授权 HTTP 端点对接 ——
-- **本迁移零跨库 FK、零跨服务 SQL、零共享视图**。
--
-- ---------------------------------------------------------------------------
-- 零、本迁移约束到的既有模块 = 【只有 10.1.1.2 一个】
-- ---------------------------------------------------------------------------
--   * 唯一被引用的是 V136 自建的 `logistics_channel_config`，且是**同库复合外键**
--     （带 tenant_id）——「按渠道与国家配置」里那个「渠道」必须是真实渠道配置，
--     跨租户挂载由复合 FK 在 DB 层直接不可能。
--   * 不碰 channel V1/V2/V3/V4/V93/V129/V134/V136 的任何一张表（**零 ALTER**）。
--   * 不碰 trade（10.2.1.2 运单申请）与 warehouse（10.2.2.1 / 8.3.4.2）的任何表。
--   * 复用 identity 已注册的 channel.operation.* 权限码，**identity 零改动**。
--
-- ---------------------------------------------------------------------------
-- 一、职责句逐项落点（「不漏项」自检）
-- ---------------------------------------------------------------------------
--   | 蓝图职责句的词            | 落点                                                    |
--   |-------------------------|---------------------------------------------------------|
--   | 尺寸                      | `paper_width_mm` / `paper_height_mm` + 每个元素的 x/y/w/h |
--   |                          | （**全部 NUMERIC(8,2)，单位 mm**，见第三节）              |
--   | 条码类型                  | `primary_barcode_symbology` + 元素级 `symbology` 受控枚举 |
--   | 附加声明（电池、液体）    | `logistics_label_declaration`（🔴 见第四节 trigger c）    |
--   | 电池标识                  | `logistics_label_battery_mark`（瓦时/重量/UN38.3/标识符号）|
--   | 语言版本                  | `language_code` 受控枚举（不是自由文本，见第二节）        |
--   | 支持按渠道与国家配置      | `channel_id` 复合 FK + `country_code` 二位大写           |
--   | 输出：模板 + 打印任务     | 本表只到**模板与渲染产物**；打印任务由 8.3.4.2 拥有        |
--   | 关系：与渠道规则强绑定    | 服务层 `POST /eligibility` + `POST /{id}/preview` 的判定   |
--
-- ---------------------------------------------------------------------------
-- 二、受控枚举：条码类型与语言版本都**不许自由文本**
-- ---------------------------------------------------------------------------
-- 条码类型用自由文本的代价是「界面上看着有填、打印机不认」：现场只有到扫码枪扫不出
-- 才知道，而那时票已经贴出去了。所以 symbology 走 CHECK 白名单，与服务层枚举一一对应。
--
--   CODE128_B / CODE128_A  一维，承运商运单号最常见；Set B 含 ASCII 32..126，
--                          中文与重音字符**编不进去**（这是真实的输入约束，不是配置项）
--   CODE39                 一维，仅 0-9 A-Z - . $ / + % 与空格
--   QR / DATAMATRIX        二维，载运单与路由信息
--
-- 语言版本同理：写 `de` 还是 `de-DE` 会让同一批面单出现两种拼法，模板失去意义。
-- 取值是 BCP 47 的语言-地区形态，白名单固定。
--
-- ---------------------------------------------------------------------------
-- 三、尺寸：**明确单位 mm + NUMERIC(8,2)，禁浮点**
-- ---------------------------------------------------------------------------
-- AGENTS.md「禁止浮点记账」在这里延伸成「禁止浮点排版」：一张 100×150mm 的面单，
-- 用 float 存 100.0 会在 25.4 英寸换算与 dpi 取整里攒出 ±1 dot 的偏移，而 1 dot
-- 在 203dpi 下就是 0.125mm —— 条码高度被吃掉一角，扫码枪开始读不出。
--
-- 所以：列一律 `numeric(8,2)`（两位小数 = 0.01mm 精度，远细于任何标签打印机），
-- dpi 是 `integer`，毫米→dot 的换算在服务层用 BigDecimal 做**四舍五入到整数 dot**，
-- 同一份输入永远得到同一串 ZPL（可复算，不用墙钟、不用随机）。
--
-- 元素坐标允许为负（热敏机的走纸原点与标签物理原点常有零点几毫米偏差），
-- 但**越界超过一个元素自身尺寸**由 trigger d 拒绝 —— 那不是校准，是把内容印到纸外。
--
-- ---------------------------------------------------------------------------
-- 四、🔴🔴 本迁移最要紧的一条：附加声明漏打 = 清关问题
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「**附加声明（如电池、液体）漏打会导致清关问题**，模板配置要与渠道规则
-- 强绑定」。
--
-- 最容易出的假活有三个，这里逐个堵掉：
--
--   假活 1 —— 把声明当**纯文本字段**存起来（「备注：电池件」）。
--       那样没人能回答「这一条声明印在哪个位置」，也就没法验证它真的印出来了。
--       所以声明必须**指向一个真实的打印元素**（element_key），DB 双向钉死：
--         * 写声明时，指向的 element_key 必须在同模板的 logistics_label_element 里存在
--         * 删/改元素时，仍被强制声明引用的那个 element_key 不许消失
--       绕过服务层直接写 SQL 造一条「声明挂在一个不存在的位置上」同样被拒 ——
--       这正是「硬绑定」与「存了没人看」的分界线。
--
--   假活 2 —— 让**服务层**判「这条渠道需不需要电池声明」，前端自己看限制字段。
--       判断规则会散落到每个调用方，而总有一个调用方会漏掉「液体」。
--       渠道要求来自 10.1.1.2 的 logistics_channel_restriction（同库只读），
--       判定在**服务层一个方法里**（POST /eligibility），规则只有一份。
--
--   假活 3 —— 模板存进库就算完，**没人真的渲染过**。
--       所以有 logistics_label_render：每次预览都**真出**一份 ZPL（含真条码编码）、
--       真算校验和、真落库，并把渠道规则比对出的 verdict（PASS/REVIEW/BLOCK）
--       一起记下来。DECLARATION_GAP 的模板**不允许进入 ACTIVE**（trigger a）。
--
-- 五、readiness（DB 层那半）：**不接受调用方指定**，由 trigger a 重算比对。
--   可在 DB 层判定的四档（全部只看本服务看得见的输入）：
--     NO_LAYOUT        一条打印元素都没有 —— 印出来是一张白纸
--     NO_BARCODE       声明了主条码类型，却没有条码类元素承载它 —— 票到了扫不出
--     DECLARATION_GAP  强制声明 / 电池标识指向的打印位不存在 —— 🔴 清关漏打
--     READY            三项齐备
--
--   第五档 NO_CHANNEL（绑定的渠道本身不可用）**刻意不在 DB 层**：
--   它取决于 10.1.1.2 那张表，而本迁移不允许依赖它的变更时机（渠道被停用时，
--   本表一行都不会被写，DB 触发器也就不会跑）。这一档由服务层在**读取时**合成
--   —— 与 10.1.1.2 复用 10.1.1.1 的 carrierAvailability 同一个做法。
--   落库的 readiness 是「本地齐备度」，服务层返回的 effectiveReadiness 才是
--   「现在能不能用」。
--
-- ---------------------------------------------------------------------------
-- 六、五张表：每张回答一个真问题
-- ---------------------------------------------------------------------------
--   logistics_label_template          这张面单按哪个渠道/国家/语言、用多大纸、什么条码
--   logistics_label_element           纸上印什么、印在哪（mm 坐标）
--   logistics_label_declaration       附加声明：必须印哪几条、印在哪
--   logistics_label_battery_mark      电池标识：什么电池、多少瓦时、UN38.3 是否齐
--   logistics_label_render             真渲染产物：ZPL + 真条码 + 校验和 + 判定结论
--
-- 「附加声明」与「电池标识」分表而不是合成一张：电池标识有一组**专属数值字段**
-- （瓦时 / 重量 / UN38.3 / 标识符号），合成一张会给每个液体声明塞四个恒为 NULL 的列。
--
-- ---------------------------------------------------------------------------
-- 七、多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置，客户端的 X-Tenant-Id 头进不来也改不动它。
-- ---------------------------------------------------------------------------
-- 八、打印任务（8.3.4.2）与本表的关系
-- ---------------------------------------------------------------------------
-- 本表**不建第二个打印引擎**：打印机、打印任务、重复打印、打印日志全部是 8.3.4.2
-- （warehouse，V73）的主权，本模块经授权 HTTP 端点对接，不跨库读写。
-- 本模块的渲染输出是**模板合规预览**：用来自 10.1.1.2 的渠道要求 + 本模板的声明与
-- 布局，真算一遍「这张面单印出来够不够承运商收」，供人在启用前确认。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. logistics_label_template · 面单模板头
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_label_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 上游 10.1.1.2：绑定的渠道配置（同库复合外键，带 tenant_id）===
  -- 「按渠道与国家配置」里的渠道必须是真实渠道配置；跨租户挂载复合 FK 直接不可能。
  -- ON DELETE RESTRICT：渠道不能被随手删掉从而让在用面单模板失去归属。
  channel_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, channel_id)
    REFERENCES logistics_channel_config(tenant_id, id) ON DELETE RESTRICT,

  -- 模板代码：下游单据与 8.3.4.2 对齐这张模板的口径。建后不可改（trigger b）。
  template_code varchar(64) NOT NULL CHECK (btrim(template_code) <> ''),
  template_name varchar(200) NOT NULL CHECK (btrim(template_name) <> ''),

  -- === 「支持按渠道与国家配置」的国家维度 ===
  -- 两位大写字母；A4 面单按国家分版本是承运商的常规要求（申报文案逐国不同）。
  country_code varchar(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),

  -- === 语言版本：受控枚举，不是自由文本（见文件头第二节）===
  language_code varchar(10) NOT NULL CHECK (
    language_code IN ('en-US','en-GB','de-DE','fr-FR','es-ES','it-IT','nl-NL','pt-BR','ja-JP','zh-CN')
  ),

  -- DRAFT 草稿（可编辑）· ACTIVE 启用（面单打印只认 ACTIVE）· ARCHIVED 归档（只读）
  status text NOT NULL DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),

  -- ⚠️ 刻意**没有** label_format 列：蓝图「打印规格」点名的五项是尺寸 / 条码类型 /
  --   附加声明 / 电池标识 / 语言版本，**输出格式不在其中**；加一个预选 PDF/HTML 而
  --   渲染器只出 ZPL 的字段，等于给现场一个会骗人的下拉。渲染产物的格式记在
  --   logistics_label_render.label_format 上（记录**实际产出**是什么，不是许愿）。

  -- === 打印规格：尺寸（NUMERIC，单位 mm，见第三节）===
  paper_width_mm numeric(8,2) NOT NULL CHECK (paper_width_mm > 0 AND paper_width_mm <= 1000),
  paper_height_mm numeric(8,2) NOT NULL CHECK (paper_height_mm > 0 AND paper_height_mm <= 2000),
  -- DPI 是整数：它同时是毫米→dot 的换算分母，不是物理量而是设备档位。
  dpi integer NOT NULL DEFAULT 203 CHECK (dpi IN (152,203,300,600)),
  orientation text NOT NULL DEFAULT 'PORTRAIT'
    CHECK (orientation IN ('PORTRAIT','LANDSCAPE')),
  -- 打印零点校准偏移（mm，可负）：标签机走纸原点与标签物理原点的差
  origin_offset_x_mm numeric(8,2) NOT NULL DEFAULT 0,
  origin_offset_y_mm numeric(8,2) NOT NULL DEFAULT 0,

  -- === 条码类型：受控枚举（见第二节）===
  -- 主条码类型；NONE 表示这张面单不带条码（仅极少数渠道的买家地址页会这样）。
  primary_barcode_symbology text NOT NULL DEFAULT 'CODE128_B'
    CHECK (primary_barcode_symbology IN ('NONE','CODE128_B','CODE128_A','CODE39','QR','DATAMATRIX')),
  -- 主条码高度（mm）：低于 10mm 的一维码在扫码枪上经常读不出，这是承运商的常见硬性要求。
  primary_barcode_height_mm numeric(8,2) NOT NULL DEFAULT 12
    CHECK (primary_barcode_height_mm > 0),
  human_readable boolean NOT NULL DEFAULT true,

  -- readiness（DB 层那半）：**不接受调用方指定**，由 trigger a 重算比对。
  -- NO_CHANNEL 这一档在服务层读取时合成，见文件头第五节。
  readiness text NOT NULL DEFAULT 'NO_LAYOUT'
    CHECK (readiness IN ('NO_LAYOUT','NO_BARCODE','DECLARATION_GAP','READY','NO_CHANNEL')),

  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  -- 同一渠道 + 同一国家 + 同一语言 + 同一模板代码唯一
  -- （否则列表上会出现两条一模一样的面单模板，且启用逻辑会二选一）
  UNIQUE (tenant_id, channel_id, country_code, language_code, template_code),
  -- 供三张子表的复合外键使用（同库引用，不是跨服务 FK）
  UNIQUE (tenant_id, id)
);

-- 「这个渠道发往德国的面单模板有哪些」—— 复合主键后缀索引。
CREATE INDEX logistics_label_template_by_channel_country
  ON logistics_label_template(tenant_id, channel_id, country_code, language_code, status);

-- 「可用的面单模板」—— 打印侧取生效模板的热点路径。
CREATE INDEX logistics_label_template_by_status
  ON logistics_label_template(tenant_id, status, template_code);

-- ---------------------------------------------------------------------------
-- 2. logistics_label_element · 打印元素（纸上印什么、印在哪）
-- ---------------------------------------------------------------------------
-- 坐标与尺寸一律 mm / NUMERIC(8,2)。越界由 trigger d 拒绝（那是「印到纸外」，
-- 不是零点校准 —— 后者是 origin_offset，两者是不同的事）。
CREATE TABLE logistics_label_element (
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  element_key varchar(48) NOT NULL CHECK (btrim(element_key) <> ''),
  element_kind text NOT NULL CHECK (element_kind IN (
    'SHIP_TO_NAME','SHIP_TO_ADDRESS','SHIP_FROM_ADDRESS','WAYBILL_NUMBER','WAYBILL_BARCODE',
    'SERVICE_NAME','ROUTING_CODE','CUSTOMS_DECLARATION','DECLARATION_TEXT','BATTERY_MARK',
    'LOGO','TEXT','QR_CODE'
  )),
  -- 元素级条码类型：留空表示这个元素不是条码。**必须属于主条码类型或 NONE 之外
  -- 的补充类型**，服务层会校验元素级类型与主类型一致（一条面单不会同时要求
  -- Code128 与 QR 作为主条码 —— 那是配置没想清楚）。
  symbology text CHECK (symbology IS NULL OR symbology IN ('CODE128_B','CODE128_A','CODE39','QR','DATAMATRIX')),
  -- 一维码放高一点（mm）；二维码是方块，w=h。零高度一维码在多数打印机上不输出。
  x_mm numeric(8,2) NOT NULL,
  y_mm numeric(8,2) NOT NULL,
  w_mm numeric(8,2) NOT NULL CHECK (w_mm > 0),
  h_mm numeric(8,2) NOT NULL CHECK (h_mm > 0),
  -- 条码类元素必须给出 symbology；非条码类元素给了 symbology 就是配错了位置。
  CHECK ((symbology IS NOT NULL) = (element_kind IN ('WAYBILL_BARCODE','QR_CODE'))),
  font_pt integer CHECK (font_pt IS NULL OR (font_pt BETWEEN 4 AND 72)),
  -- 旋转角度必须是 90 的整数倍：打印机只认这几个档位，37° 在任何设备上都会糊。
  rotation integer NOT NULL DEFAULT 0
    CHECK (rotation IN (0,90,180,270)),
  -- 强制元素不许删（trigger c 会挡住；这里列出来是为了查询能一眼筛出）。
  mandatory boolean NOT NULL DEFAULT false,
  -- 打印顺序（同一坐标带下 ZPL 的 ^FD 顺序）
  sequence integer NOT NULL DEFAULT 0,
  note text NOT NULL DEFAULT '',
  PRIMARY KEY (tenant_id, template_id, element_key),
  FOREIGN KEY (tenant_id, template_id)
    REFERENCES logistics_label_template(tenant_id, id) ON DELETE CASCADE
);

-- 「这张模板上的条码元素有哪些」—— 渲染与 readiness 的热点路径。
CREATE INDEX logistics_label_element_by_kind
  ON logistics_label_element(tenant_id, template_id, element_kind);

-- ---------------------------------------------------------------------------
-- 3. logistics_label_declaration · 附加声明（🔴 清关漏打风险点，见第四节）
-- ---------------------------------------------------------------------------
-- **每条声明都必须指向一个真实的打印元素**（element_key），由 trigger c 双向钉死：
-- 写声明时目标元素必须已存在；删/改元素时仍被引用的 key 不许消失。
-- 「声明挂在哪个位置上」是这条模板唯一能回答「它印没印出来」的方式。
CREATE TABLE logistics_label_declaration (
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  declaration_kind text NOT NULL CHECK (declaration_kind IN (
    'BATTERY','LIQUID','MAGNET','DANGEROUS_GOODS','BIOHAZARD','RETURN_DECLARATION','OTHER'
  )),
  -- 承运商侧的声明代码（如 UN3481 / 「附加声明号」）。逐国逐渠道不同，留空表示
  -- 只印文字不声明代码。
  declaration_code varchar(32) NOT NULL DEFAULT '',
  -- 印在标签上的**文字原文**。留空 + 有代码 = 只印代码；两者都空 = 一个空声明，
  -- 那在清关时等于没声明，所以由 trigger c 一起拒。
  display_text text NOT NULL DEFAULT '',
  -- 印在哪个打印元素上（见文件头第四节「假活 1」）
  element_key varchar(48) NOT NULL CHECK (btrim(element_key) <> ''),
  -- 强制声明 = 渠道要求必须有它。缺了它模板进不了 ACTIVE（trigger a）。
  mandatory boolean NOT NULL DEFAULT true,
  note text NOT NULL DEFAULT '',
  PRIMARY KEY (tenant_id, template_id, declaration_kind),
  FOREIGN KEY (tenant_id, template_id)
    REFERENCES logistics_label_template(tenant_id, id) ON DELETE CASCADE
);

-- 「这张模板声明了哪些附加声明」—— 渠道规则比对的热点路径。
CREATE INDEX logistics_label_declaration_by_kind
  ON logistics_label_declaration(tenant_id, template_id, declaration_kind);

-- ---------------------------------------------------------------------------
-- 4. logistics_label_battery_mark · 电池标识
-- ---------------------------------------------------------------------------
-- 单独一张表而不是声明表的一个字段：电池标识有一组**专属数值字段**
-- （瓦时 / 重量 / UN38.3 / 标识符号），合成一张会给每个液体声明塞四个恒为 NULL 的列。
--
-- 瓦时（Wh）是 IATA/UN 的真实口径：**瓦时 > 2.7 且 ≤ 100 的锂电池必须带 UN38.3 测试摘要**，
-- 这一条由 trigger e 在 DB 层钉死 —— 违反的后果是整批货被机场扣下。
CREATE TABLE logistics_label_battery_mark (
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  battery_type text NOT NULL CHECK (battery_type IN (
    'LITHIUM_ION','LITHIUM_METAL','LITHIUM_POLYMER','DRY_CELL','OTHER'
  )),
  -- 额定瓦时 Wh：NUMERIC(10,3)（锂电池的 Wh 常带三位小数，禁浮点）
  watt_hours numeric(10,3),
  -- 单件电池净重 kg：NUMERIC(10,4)
  battery_weight_kg numeric(10,4) CHECK (battery_weight_kg IS NULL OR battery_weight_kg > 0),
  un38_3_attested boolean NOT NULL DEFAULT false,
  -- 承运商面上的电池标识符号（锂电池标志 / 9 类危险品标志的打印代号）
  marking_symbol varchar(32) NOT NULL DEFAULT '',
  -- 印在哪个打印元素上（与附加声明同一个硬绑定规则，trigger c 一起管）
  element_key varchar(48) NOT NULL CHECK (btrim(element_key) <> ''),
  note text NOT NULL DEFAULT '',
  PRIMARY KEY (tenant_id, template_id, battery_type),
  FOREIGN KEY (tenant_id, template_id)
    REFERENCES logistics_label_template(tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- 5. logistics_label_render · 真渲染产物（不是「存一条记录就算完」）
-- ---------------------------------------------------------------------------
-- 每次预览都**真出**一份 ZPL（含真条码编码）、真算 sha256、真落库，并把渠道规则
-- 比对出的 verdict 与问题条数一起记下来。渲染**不查库、不回读**：
-- 模板头与子表由服务层在同一请求里读完传入，所以「渲染失败不会连带改了别的」。
CREATE TABLE logistics_label_render (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  FOREIGN KEY (tenant_id, template_id)
    REFERENCES logistics_label_template(tenant_id, id) ON DELETE CASCADE,

  -- 触发来源：PREVIEW 人工预览 · ACTIVATION 启用前闸 · BINDING 渠道规则绑定核对
  trigger_source text NOT NULL
    CHECK (trigger_source IN ('PREVIEW','ACTIVATION','BINDING')),

  -- 渲染用的样例载荷（JSON 文本）：预览必须带真实形状的数据，否则元素印出来是空的
  sample_payload text NOT NULL,
  -- 本次渲染实际用的条码类型与被编码的值（条码编码**校验了可编码性**才落到这里）
  symbology text NOT NULL,
  encoded_value text NOT NULL DEFAULT '',
  -- 条码模块宽度序列（一维）/ 图形矩阵（二维）的可复核表示
  barcode_modules text NOT NULL DEFAULT '',

  label_format text NOT NULL CHECK (label_format IN ('ZPL','PDF','HTML')),
  -- 真渲染产物本体。ZPL 是 ^XA … ^XZ 包边的热敏指令流，现场机器可以直接吃。
  zpl_content text NOT NULL CHECK (btrim(zpl_content) <> ''),
  -- 内容校验和（sha256），供下载端点验内容未被篡改（照抄 8.3.4.2 的同一做法）
  content_sha256 varchar(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),

  -- 渠道规则比对结论：PASS 合规 / REVIEW 需人工确认 / BLOCK 不可用（清关会被打回）
  verdict text NOT NULL CHECK (verdict IN ('PASS','REVIEW','BLOCK')),
  -- 判定明细（JSON 数组文本）：逐条给出命中的是哪条渠道要求、漏了哪个声明。
  -- **不合并成一句话** —— 运营需要知道是「电池」还是「液体」没打。
  findings text NOT NULL DEFAULT '[]',
  finding_count integer NOT NULL DEFAULT 0 CHECK (finding_count >= 0),

  rendered_at timestamptz NOT NULL DEFAULT now()
);

-- 「这张模板的渲染历史」—— 分页读，最近在前。
CREATE INDEX logistics_label_render_by_template
  ON logistics_label_render(tenant_id, template_id, rendered_at desc);

-- ---------------------------------------------------------------------------
-- 6. 触发器
-- ---------------------------------------------------------------------------

-- (a) 🔴 readiness 重算比对：DB 复算一遍，绕过服务层写 SQL 也改不成「READY」。
--     规则与文件头第五节逐条对应，注释互相指向。
CREATE OR REPLACE FUNCTION guard_logistics_label_readiness() RETURNS trigger AS $$
DECLARE
  has_element boolean;
  has_barcode_element boolean;
  gap_count integer;
  expected text;
BEGIN
  SELECT EXISTS (SELECT 1 FROM logistics_label_element e
                  WHERE e.tenant_id = NEW.tenant_id AND e.template_id = NEW.id)
    INTO has_element;

  -- 有条码元素 = element_kind 是两种条码之一（symbology 的 CHECK 已保证二者等价）
  SELECT EXISTS (SELECT 1 FROM logistics_label_element e
                  WHERE e.tenant_id = NEW.tenant_id AND e.template_id = NEW.id
                    AND e.element_kind IN ('WAYBILL_BARCODE','QR_CODE'))
    INTO has_barcode_element;

  -- 🔴 清关漏打：强制声明或电池标识指向的打印位不存在 —— 这就是 DECLARATION_GAP。
  --    两处漏打要**相加**：声明漏一处、电池标识漏一处，是两件独立的事，
  --    只报一件会让现场以为「补了声明就没问题了」。
  SELECT
      count(*) FILTER (WHERE true)
    + (SELECT count(*) FROM logistics_label_battery_mark m
        WHERE m.tenant_id = NEW.tenant_id AND m.template_id = NEW.id
          AND NOT EXISTS (SELECT 1 FROM logistics_label_element e
                           WHERE e.tenant_id = m.tenant_id AND e.template_id = m.template_id
                             AND e.element_key = m.element_key))
    INTO gap_count
    FROM logistics_label_declaration d
   WHERE d.tenant_id = NEW.tenant_id AND d.template_id = NEW.id AND d.mandatory
     AND NOT EXISTS (SELECT 1 FROM logistics_label_element e
                      WHERE e.tenant_id = d.tenant_id AND e.template_id = d.template_id
                        AND e.element_key = d.element_key);

  IF NOT has_element THEN
    expected := 'NO_LAYOUT';
  ELSIF NEW.primary_barcode_symbology <> 'NONE' AND NOT has_barcode_element THEN
    expected := 'NO_BARCODE';
  ELSIF gap_count > 0 THEN
    expected := 'DECLARATION_GAP';
  ELSE
    expected := 'READY';
  END IF;

  IF NEW.readiness <> expected THEN
    -- ⚠️ 占位符一律用**裸 %**：PostgreSQL 的 RAISE 没有 C 的 %s 转换。
    RAISE EXCEPTION
      '面单模板的齐备度判定为 %（打印元素 % / 条码元素 % / 漏打位置 % / 主条码 %），与提交的 % 不符 —— 齐备度由这些算出，不接受手工指定',
      expected,
      CASE WHEN has_element THEN '有' ELSE '无' END,
      CASE WHEN has_barcode_element THEN '有' ELSE '无' END,
      gap_count,
      NEW.primary_barcode_symbology,
      NEW.readiness USING ERRCODE = 'check_violation';
  END IF;

  -- 🔴 齐备度不够的面单模板不许进入 ACTIVE：印出去的每张面单都是真票。
  --    这条与 DECLARATION_GAP 的含义是同一件事：漏打就会清关出问题。
  IF NEW.status = 'ACTIVE' AND expected <> 'READY' THEN
    RAISE EXCEPTION
      '面单模板当前齐备度为 %，不能启用（ACTIVE）：印出去的每张面单都是真票，% 会导致清关问题',
      expected,
      CASE expected
        WHEN 'NO_LAYOUT' THEN '一条打印元素都没有（印出来是白纸）'
        WHEN 'NO_BARCODE' THEN '声明了主条码类型却没有条码元素（票到了扫不出）'
        ELSE '强制附加声明或电池标识没有对应的打印位（漏打会被清关打回）'
      END USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_label_readiness_trg
  BEFORE INSERT OR UPDATE ON logistics_label_template
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_label_readiness();

-- (b) 🔴 模板代码建后不可改（照抄 10.1.1.2 的 trigger b 同一结论）：
--     模板代码是下游打印与单据对齐这张模板的口径，改代码会让已有单据找不到模板。
CREATE OR REPLACE FUNCTION guard_logistics_label_code_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.template_code IS DISTINCT FROM OLD.template_code THEN
    RAISE EXCEPTION
      '模板代码建后不可改（% → %）：模板代码是下游打印与单据对齐这张模板的口径，改代码会让已有单据找不到模板',
      OLD.template_code, NEW.template_code USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_label_code_immutable_trg
  BEFORE UPDATE ON logistics_label_template
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_label_code_immutable();

-- (c) 🔴🔴 **附加声明与电池标识必须挂在一个真实存在的打印元素上（双向）**。
--     正向：写一条声明 / 电池标识，而它指向的 element_key 不在元素表里 → 拒。
--     刻意<b>拆成两个函数</b>而不是共用一个：两张表的列不一样（声明有
--     display_text / declaration_code，电池标识没有），共用一个函数会让电池标识
--     那一路在运行时报「记录 NEW 没有 display_text 字段」—— 症状是「插电池标识报语法错」。
CREATE OR REPLACE FUNCTION guard_logistics_label_declaration_anchor() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM logistics_label_element e
                  WHERE e.tenant_id = NEW.tenant_id AND e.template_id = NEW.template_id
                    AND e.element_key = NEW.element_key) THEN
    RAISE EXCEPTION
      '打印位「%」在这张模板上不存在：附加声明必须指向一个真实的打印元素，否则无法证明它真的印出来了（漏打会导致清关问题）',
      NEW.element_key USING ERRCODE = 'check_violation';
  END IF;

  -- 一条既没代码也没文字的声明在清关时等于没声明，宁可在这里拒。
  IF btrim(NEW.display_text) = '' AND btrim(NEW.declaration_code) = '' THEN
    RAISE EXCEPTION
      '附加声明既有声明代码也没有印面文字：它在面单上什么都不会出现，等于没声明'
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_label_declaration_anchor_trg
  BEFORE INSERT OR UPDATE ON logistics_label_declaration
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_label_declaration_anchor();

-- 电池标识那一路：同样的锚定规则，但没有「代码 / 文字」这两列可查。
CREATE OR REPLACE FUNCTION guard_logistics_label_battery_anchor() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM logistics_label_element e
                  WHERE e.tenant_id = NEW.tenant_id AND e.template_id = NEW.template_id
                    AND e.element_key = NEW.element_key) THEN
    RAISE EXCEPTION
      '打印位「%」在这张模板上不存在：电池标识必须指向一个真实的打印元素，否则无法证明它真的印出来了（漏打会导致清关问题）',
      NEW.element_key USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_label_battery_anchor_trg
  BEFORE INSERT OR UPDATE ON logistics_label_battery_mark
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_label_battery_anchor();

--     反向：删 / 改一个元素，而它仍被强制声明或电池标识引用 → 拒。
--     没有这一条，「先把声明配好、之后再调布局」这条正常路径会在删元素那一刻
--     静默留下一条指向虚空的声明 —— 而它的表现是**清关漏打**。
CREATE OR REPLACE FUNCTION guard_logistics_label_element_referenced() RETURNS trigger AS $$
DECLARE
  hit record;
BEGIN
  SELECT d.declaration_kind, d.mandatory INTO hit
    FROM logistics_label_declaration d
   WHERE d.tenant_id = OLD.tenant_id AND d.template_id = OLD.template_id
     AND d.element_key = OLD.element_key;

  IF FOUND THEN
    RAISE EXCEPTION
      '打印位「%」仍被附加声明「%」（%）引用，不能删除或改名：漏打会导致清关问题。要去掉这条声明，请先删声明再删这个打印位',
      OLD.element_key, hit.declaration_kind,
      CASE WHEN hit.mandatory THEN '强制' ELSE '非强制' END
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT b.battery_type INTO hit
    FROM logistics_label_battery_mark b
   WHERE b.tenant_id = OLD.tenant_id AND b.template_id = OLD.template_id
     AND b.element_key = OLD.element_key;

  IF FOUND THEN
    RAISE EXCEPTION
      '打印位「%」仍被电池标识「%」引用，不能删除或改名：漏打会导致清关问题。要去掉电池标识，请先删标识再删这个打印位',
      OLD.element_key, hit.battery_type USING ERRCODE = 'check_violation';
  END IF;

  RETURN COALESCE(NEW, OLD);
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_label_element_referenced_trg
  BEFORE DELETE OR UPDATE OF element_key ON logistics_label_element
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_label_element_referenced();

-- (d) 元素不得印到纸外。**越界超过自身尺寸**即拒。
--     注意这与 origin_offset_x/y 是两回事：那是打印机零点校准（mm 级、小数值），
--     这里判的是「内容被印在纸张物理边界之外」—— 那不是校准，是废纸。
CREATE OR REPLACE FUNCTION guard_logistics_label_element_bounds() RETURNS trigger AS $$
DECLARE
  paper_w numeric;
  paper_h numeric;
BEGIN
  SELECT t.paper_width_mm, t.paper_height_mm INTO paper_w, paper_h
    FROM logistics_label_template t
   WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.template_id;

  IF NEW.x_mm < 0 OR NEW.y_mm < 0 THEN
    RAISE EXCEPTION
      '打印位「%」的坐标为负（%, %）：负坐标只能来自打印机零点校准（origin_offset），元素坐标必须落在纸内，越界部分印不出去',
      NEW.element_key, NEW.x_mm, NEW.y_mm USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.x_mm + NEW.w_mm > paper_w OR NEW.y_mm + NEW.h_mm > paper_h THEN
    RAISE EXCEPTION
      '打印位「%」越出纸张（%, %）+ (%, %) = 右边界 % / 下边界 %，而纸张只有 % × % mm：请缩小元素或换更大的纸张规格',
      NEW.element_key, NEW.x_mm, NEW.y_mm, NEW.w_mm, NEW.h_mm,
      NEW.x_mm + NEW.w_mm, NEW.y_mm + NEW.h_mm, paper_w, paper_h
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_label_element_bounds_trg
  BEFORE INSERT OR UPDATE ON logistics_label_element
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_label_element_bounds();

-- (e) 🔴 锂电池 2.7Wh < Wh ≤ 100 必须有 UN38.3 测试摘要（IATA / UN 口径）。
--     违反的后果是整批货在机场被扣下并退回 —— 属于「漏打/少打」同一族事故，
--     所以在 DB 层钉死，而不是只在校验注解里写一句。
CREATE OR REPLACE FUNCTION guard_logistics_label_battery_wh() RETURNS trigger AS $$
BEGIN
  IF NEW.battery_type IN ('LITHIUM_ION','LITHIUM_METAL','LITHIUM_POLYMER')
     AND NEW.watt_hours IS NOT NULL
     AND NEW.watt_hours > 2.7 AND NEW.watt_hours <= 100
     AND NOT NEW.un38_3_attested THEN
    RAISE EXCEPTION
      '锂电池瓦时 % 落在 2.7 < Wh ≤ 100 区间，按 IATA / UN 口径必须提供 UN38.3 测试摘要：缺了它整批货会在机场被扣下退回',
      NEW.watt_hours USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_logistics_label_battery_wh_trg
  BEFORE INSERT OR UPDATE ON logistics_label_battery_mark
  FOR EACH ROW EXECUTE FUNCTION guard_logistics_label_battery_wh();

-- ---------------------------------------------------------------------------
-- 7. 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 / V134 / V136 同一套写法。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'logistics_label_template',
    'logistics_label_element',
    'logistics_label_declaration',
    'logistics_label_battery_mark',
    'logistics_label_render' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 8. 账号与授权（沿用 V1/V93/V129/V134/V136 的条件建角色，保证迁移可重复执行）
-- ---------------------------------------------------------------------------
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  logistics_label_template, logistics_label_element, logistics_label_declaration,
  logistics_label_battery_mark, logistics_label_render TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 / V134 / V136 同口径）。
REVOKE ALL ON
  logistics_label_template, logistics_label_element, logistics_label_declaration,
  logistics_label_battery_mark, logistics_label_render FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 9. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：面单模板属于「平台/承运商操作」，与 10.1.1.1 承运商、
-- 10.1.1.2 渠道配置、10.3.2.1 交运批次、10.4.2.1 海外仓连接同族，
-- 因此 identity 服务零改动，跨车道回归面 = 0。
