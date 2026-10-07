-- 3.6.3.1 地址与联系人：往来单位的收发货地址（带变更版本链）与带姓名的联系人。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：维护往来单位的收发货地址与联系人；地址变更留历史版本。
--   **输入**：地址与联系人增删改；地址版本。
--   **输出**：地址与联系人记录。
--   **关系**：上游 = 3.6.1.1；下游 = 06 采购（发货地址）、10 物流（提货地址）。
--             **单据上的地址是冻结快照**，本模块改地址不影响已发生单据。
--
-- ============================================================ 第零节：与 V27 的分工
--
-- V27（3.6.1.1）刻意没建联系人表，理由写在 V27 第一节的注释里：
--   * `party.email / phone / website` 回答的是「怎么找到这个主体」
--     （主体自己的对外通道，公司总机 / info@ 邮箱）；
--   * 本模块的 `party_contact` 回答的是「跟这个主体对接的**那个人**是谁」
--     （采购对接人姓名、职务、手机号，以及谁是默认联系人）。
--   两者语义不同、生命周期也不同（总机不会因为对接人离职而失效，对接人会）。
--   所以这里**不**往 party 表里加联系人字段，也不复用它的联系方式列。
--
-- 上游引用一律走 (tenant_id, party_id) 复合外键指向 `party(tenant_id,id)`：
-- 只引用 party.id 的话，另一个租户构造一个 id 就能把地址挂到别人的主体上，
-- 而「这个提货地址属于我的供应商吗」是 10 物流发货前必须为真的事。
--
-- ============================================================ 第一节：四张表与它们的分工
--
--   party_address          地址本体（物理地址内容），**可变**，是「当前态」
--   party_address_usage    地址用途 + 默认标记（收货 / 发货 / 提货各一个默认）
--   party_address_version  地址内容的 append-only 历史版本链（冻结快照的载体）
--   party_contact          带姓名的联系人 + 默认联系人标记
--
-- **为什么用途不并进 party_address 的一个 address_type 枚举**：
--   一个供应商的仓库往往**既是发货地址又是提货地址**。用 address_type 枚举的话，
--   同一个物理地点要录两行（SHIP_FROM 一行、PICKUP 一行），改一次地址要改两处，
--   而两行内容不一致的症状（发货地址改了、提货地址没改）要到发货那天才暴露。
--   更要紧的是「默认提货地址」这个蓝图点名要的查询在枚举写法下没有落点：
--   默认是**按用途**的（每人每用途一个默认），枚举列放不下这层语义，
--   只能再加一张表 —— 那不如一开始就把它建成这张表。
--
--   `usages` 拆开之后，「这条地址既能发货又能提货」是数据上的一行两列，
--   「默认提货地址」是 `party_address_usage` 上的一条 is_default，
--   而「一个主体一个提货地址默认」由第五节的部分唯一索引在 DB 层钉死。
--
-- **地址里刻意没有收件人姓名 / 电话两列**：
--   「跟这个主体对接的那个人」是 `party_contact` 的职责。在地址上再存一份
--   就是蓝图与 V27 都点名否决的重复来源 —— 两处不一致时（联系人离职换了手机号、
--   地址上的旧号码没清），10 物流按地址上的号码打过去会打到一个空号，
--   而运营在联系人页面上看到的还是正确的那个。地址只回答「在哪里」。
--
-- ============================================================ 第二节：party_address —— 地址本体
--
-- 形状照抄 V27 的 `party`（同一域里同一性质的主数据：租户级、带软删除、
-- 带 version 乐观锁），字段是跨境地址的标准列集。
CREATE TABLE party_address (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, party_id uuid NOT NULL,

  -- 运营给地址起的名字：同一个供应商往往有「深圳工厂仓」「义乌office」「德国仓库」三处，
  -- 界面上只显示一串地址行没法让人在 06 采购 / 10 物流里挑对那一个。可空（只有一个地址时不必起名）。
  label varchar(100),

  -- 国家：ISO 3166-1 alpha-2。**NOT NULL**：跨境是这个系统的地基，
  -- 一个没有国家的地址在报关面单与运费试算里都用不了 —— 与 party.country_code 可空的取舍相反，
  -- 那是因为个人主体在「寄回寄件人」场景里可能真的没有国家，而地址必须落在某个国家里。
  country_code varchar(2) NOT NULL,

  -- 省 / 州（state / province）。跨境差异大：US 有州、DE 是 Bundesland、SG 没有，
  -- 所以统一叫 state_province 而不是更具体的 province。
  state_province varchar(100),
  city varchar(100) NOT NULL,          -- 城市：必填。跨国地址里 city 是运费分区与税务辖区的主要输入
  district varchar(100),               -- 区 / 县（区县在中国、邮编前缀在部分欧洲国家起同样作用），可空
  postal_code varchar(32),             -- 邮编：可空 —— 相当多国家没有邮编体系（HK / AE / 部分非洲国家），
                                        -- 把它设成 NOT NULL 会逼运营编一个假邮编，而假邮编会让面单打印失败

  -- 街道地址行 1 / 行 2。行 2 放门牌号、楼层、Unit；把门牌号塞进行 1 是常见做法，
  -- 但那样就没法表达「1 号楼 3 层」这种只在行 2 放得下的内容。
  street_line1 varchar(200) NOT NULL,
  street_line2 varchar(200),

  -- 用途与默认不在本表（见第零节）；本表只存「这个地址现在长什么样」。
  version bigint NOT NULL DEFAULT 0,
  -- 软删除而不是物理删除，理由与 V27 的 party 相同但更强：
  -- 已发生的 06 采购单 / 10 物流单会引用这个地址（address_id + version_no 两个指路牌列），
  -- 物理删除会让那两张单据的地址指向一个不存在的行，而地址链路的版本行还在。
  deleted_at timestamptz,
  deleted_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 三列唯一：让「地址 + 它归属的主体」可被复合外键引用。
  -- 用途表与版本表都要同时钉住 address_id 与 party_id，缺了它就只能在应用层保证
  -- 「用途行的 party_id 与地址行的 party_id 是同一个」，而那正是本模块最容易写错的地方。
  UNIQUE(tenant_id,id,party_id),
  FOREIGN KEY(tenant_id,party_id) REFERENCES party(tenant_id,id) ON DELETE CASCADE,
  CHECK(country_code ~ '^[A-Z]{2}$'),
  CHECK(btrim(city)<>''),
  CHECK(btrim(street_line1)<>''),
  -- 可空列留空白串是脏数据：要么 NULL 要么非空白（与 V27 的 email/phone 同一口径）。
  CHECK(label IS NULL OR btrim(label)<>''),
  CHECK(state_province IS NULL OR btrim(state_province)<>''),
  CHECK(district IS NULL OR btrim(district)<>''),
  CHECK(postal_code IS NULL OR btrim(postal_code)<>''),
  CHECK(street_line2 IS NULL OR btrim(street_line2)<>''),
  CHECK(deleted_at IS NULL OR deleted_by IS NOT NULL)
);

-- ============================================================ 第三节：party_address_usage —— 用途与默认标记
--
-- usage 的三个值与蓝图点名的三个下游用途一一对应，不做合并：
--   SHIP_TO    收货地址 —— 货送到这里。06 采购单上的「收货地址」
--   SHIP_FROM  发货地址 —— 货从这里发出。06 采购单上的「发货地址」（供应商的仓库）
--   PICKUP     提货地址 —— 承运商到这里提货。10 物流单上的「提货地址」
--
-- SHIP_FROM 与 PICKUP 在多数场景是同一个物理地点（供应商的发货仓既发货又被提货），
-- 这正是「同一条地址可以同时标两个用途、而两个用途各有各的默认」的典型场景 ——
-- 所以它们是两条 usage 行而不是一个枚举值。
CREATE TABLE party_address_usage (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  party_id uuid NOT NULL, address_id uuid NOT NULL,
  usage text NOT NULL CHECK(usage IN ('SHIP_TO','SHIP_FROM','PICKUP')),
  -- 这条地址是不是该用途下的默认。默认是**按用途**的，所以标记落在用途行上而不是地址行上。
  is_default boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一地址同一用途只有一行（整体替换用途时靠它 upsert）。
  UNIQUE(tenant_id,address_id,usage),
  FOREIGN KEY(tenant_id,party_id) REFERENCES party(tenant_id,id) ON DELETE CASCADE,
  -- 复合外键带 party_id：**用途行不能挂到别的主体的地址上**。
  -- 只引用 (tenant_id,address_id) 的话，构造一行 usage.party_id = 另一个主体就能让
  -- 「A 的供应商」在下拉里出现「B 的提货地址」。
  FOREIGN KEY(tenant_id,address_id,party_id) REFERENCES party_address(tenant_id,id,party_id) ON DELETE CASCADE
);

-- ============================================================ 第四节：party_address_version —— 地址变更历史版本
--
-- **这一节是蓝图那句「地址变更留历史版本」的落点，也是「单据上的地址是冻结快照」
-- 这条硬约束在本模块这一侧的载体。**
--
-- 冻结快照的做法（三层，写清楚免得下一个改代码的人重新发明一遍）：
--
--   ① 本表是 append-only 的：每次地址**内容**真的变了就追加一行，version_no 从 1 开始递增。
--      版本行**只追加、不修改、不删除**（没有 updated_at，因为它的内容一旦落库就不该再变）。
--   ② 06 采购单 / 10 物流单在自己那张单据表里存**地址快照列**（把地址字段抄一份过去），
--      同时存 address_id + address_version_no 两个**指路牌**列。
--      快照列是权威（它属于各自服务的写入所有权，AGENTS.md 的模块边界纪律：跨服务不读对方表）；
--      那两个指路牌列**故意不加外键** —— 跨服务建 FK 同样是禁止的，它们只用于回溯与对账。
--   ③ 读「当时的内容」有两条路，优先级不能反：
--      单据自己的快照列（权威） → 缺失时才回查本表那一版（只读兜底，不是权威）。
--
-- 为什么是「可变当前态 + append-only 版本链」而不是「地址行不可变、每次改新建一行」：
--   * 不可变写法下每次改地址都在单据里换一个 id，而 06/10 的下拉要面对
--     「同一个物理地址有 5 个 id，哪个是真的」；
--   * 更关键的是「默认提货地址」在不可变写法下没有落点：当前态是历史链的末环而不是一个显式标记，
--     「挑当前地址」要写成「挑没有后继的那一行」，而删掉一行就等于把末环判错；
--   * 现状 + 版本链则让默认标记、用途约束都在唯一可查的当前态行上（唯一索引能表达
--     「每人每用途一个默认」），同时历史靠 append-only 行保证「第 N 版当时长什么样」可回放。
--
-- 为什么版本行只快照**地址内容**、不快照用途与默认标记：
--   「单据上的地址是冻结快照」关心的是当时**印在面单/发票上的那个地址**，
--   而不是这条地址今天挂着什么用途标签 —— 用途改了不影响它印出来的字。
--   把用途也快照进去会让每次「把默认提货地址改到 3 号仓」都产生一条内容相同的版本行，
--   而版本列表要回答的问题是「这个地址被改过几次、每次改成什么样」。
--
-- 用带版本号的**快照行**而不是「在 audit_event 里记差异」还有一个实际理由：
--   单据要能按 (address_id, version_no) **精确取回**那一版；差异日志要求读者自己重放历史，
--   而重放链在并发改与回滚场景下会得到与当时不同的结果。
CREATE TABLE party_address_version (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  party_id uuid NOT NULL, address_id uuid NOT NULL,
  -- 该地址内的版本号，从 1 开始随内容变更递增。与地址行的 version 列是两个数：
  -- address.version 是**乐观锁**用的（每次保存都 +1，包括只改用途那种不产生新版本的保存），
  -- version_no 是**历史序号**（只在内容真的变了时 +1）。混用这两个数会得到
  -- 「版本号跳号」的历史列表。
  version_no integer NOT NULL CHECK(version_no >= 1),
  -- ↓↓↓ 内容快照：与 party_address 的地址列一一对应，**当前态与快照同宽**。
  label varchar(100),
  country_code varchar(2) NOT NULL,
  state_province varchar(100),
  city varchar(100) NOT NULL,
  district varchar(100),
  postal_code varchar(32),
  street_line1 varchar(200) NOT NULL,
  street_line2 varchar(200),
  -- ↑↑↑ 快照列到此结束

  -- 什么时候变成这一版、谁改的（DB 自己的时钟，见服务层的写入说明）。
  changed_at timestamptz NOT NULL,
  changed_by uuid NOT NULL,
  -- 改这一版的说明。创建时固定为「建档」（NOT NULL 且服务层总是给值），
  -- 所以版本列表里不会出现一条「无说明的变更」让人猜是不是误操作。
  change_note text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一个地址内版本号唯一：两行同号意味着历史里有歧义，取回「第 N 版」会返回两条。
  UNIQUE(tenant_id,address_id,version_no),
  FOREIGN KEY(tenant_id,party_id) REFERENCES party(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,address_id,party_id) REFERENCES party_address(tenant_id,id,party_id) ON DELETE CASCADE
);

-- ============================================================ 第五节：party_contact —— 带姓名的联系人
--
-- 回答的是「跟这个主体对接的那个人」，与 party 上主体自己的对外通道（总机 / info@）不同，
-- 理由见第零节。
--
-- 本表**允许物理删除**（与 party_address 相反），理由与 V27 的 party_role 相同：
-- 它是「当前正在对接的那个人」的**当前事实**，没有「曾经有过采购对接人、后来离职了」
-- 这种回溯需求 —— 已发生单据里的联系人姓名与电话是单据自己的冻结快照
-- （与 V27 第七节对 06/11/12 的约定同源），不靠这张表回溯。
-- party_address 不能这么删，是因为下游单据引用的是 (address_id, version_no) 指路牌，
-- 删掉地址会让指路牌指向不存在的行。
CREATE TABLE party_contact (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, party_id uuid NOT NULL,

  -- 姓名：必填。与 party.name 的区别是粒度 —— 一个供应商有公司名，也有对接的人名，
  -- 而 06 采购单上「供应商联系人」那一栏要的是后者。
  name varchar(100) NOT NULL,
  -- 职务（采购经理 / 物流主管）：可空，但跨境场景里很常被用来判断这个人在流程里的位置。
  position varchar(100),

  email varchar(254),
  -- 电话与手机号分列：跨境 B2B 里「总机」与「对接人手机」是两个都要的号码，
  -- 06 采购问交期打手机、12 财务对账打总机。
  phone varchar(64),
  mobile varchar(64),

  -- 默认联系人：只有一个。蓝图点名的「默认联系人」落在这里
  -- （地址侧的默认收/发货地址在 party_address_usage 上，两边的默认不是同一个东西：
  --   地址默认是「哪个仓」，联系人默认是「找谁签字」）。
  is_primary boolean NOT NULL DEFAULT false,

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,party_id) REFERENCES party(tenant_id,id) ON DELETE CASCADE,
  CHECK(btrim(name)<>''),
  CHECK(position IS NULL OR btrim(position)<>''),
  -- 留空白串是脏数据，与 V27 的 email/phone 同一口径。
  CHECK(email IS NULL OR btrim(email)<>''),
  CHECK(phone IS NULL OR btrim(phone)<>''),
  CHECK(mobile IS NULL OR btrim(mobile)<>''),
  -- **邮箱 / 电话 / 手机至少给一项**（与 V27 的 P3 同源）：
  -- 三个都空的联系人是一行在 06 采购问交期、10 物流约提货、11 售后约退货时都用不了的记录，
  -- 而它看上去和真联系人完全一样 —— 那正是 3.9.2.2 僵尸主数据清理要处理的形态。
  CHECK(email IS NOT NULL OR phone IS NOT NULL OR mobile IS NOT NULL)
);

-- ============================================================ 第六节：钉死「唯一默认」的部分唯一索引
--
-- 这两条索引是「每个主体每个用途只有一个默认地址」「每个主体只有一个默认联系人」
-- 在 DB 层的落点。服务层也判，但服务层是应用代码：绕过它直接写库的导入脚本、
-- 运维手工修数都会撞这两条 —— 而「两个默认提货地址」的症状是 10 物流随机挑一个发货，
-- 到对账那天才会暴露。
--
-- 部分索引（where is_default / where is_primary）而不是普通唯一索引：
-- 非默认的那些行必须能有很多条（一个主体可以有五个收货地址，只有一个是默认的）。
CREATE UNIQUE INDEX party_address_one_default_per_usage
  ON party_address_usage(tenant_id,party_id,usage) WHERE is_default;
CREATE UNIQUE INDEX party_contact_one_primary_per_party
  ON party_contact(tenant_id,party_id) WHERE is_primary;

-- ============================================================ 第七节：索引
--
-- 与 V27 同一套思路：先覆盖「按主体取集合」与「按主体取默认」这两条真实查询，
-- 再为关键词检索建 pg_trgm GIN 索引（V21 已 CREATE EXTENSION，这里不重复声明）。
--
-- 地址列表：按主体取，按内容稳定排序（address 用 id 稳定排序而不是街道名 ——
-- 街道名会变，用它排序会让「翻页时这一行跳走」）。
CREATE INDEX party_address_by_party ON party_address(tenant_id,party_id,deleted_at,id);
-- 下游按用途取默认地址：06 采购（收货/发货）、10 物流（提货）走这条。
CREATE INDEX party_address_usage_by_party ON party_address_usage(tenant_id,party_id,usage,is_default);
-- 「这条地址当前挂着哪些用途」——列表页每行都要显示用途徽章，与默认标记一起取。
CREATE INDEX party_address_usage_by_address ON party_address_usage(tenant_id,address_id,usage);
-- 版本链：按地址取历史并按 version_no 倒序（版本列表与「取回第 N 版」都走这条）。
CREATE INDEX party_address_version_by_address ON party_address_version(tenant_id,address_id,version_no);
-- 联系人：按主体取（列表、默认联系人）、按主体取默认那条。
CREATE INDEX party_contact_by_party ON party_contact(tenant_id,party_id,is_primary,name);
-- 关键词检索：运营从一封邮件的签名里抄下一个名字/邮箱就来找那个联系人，
-- 前导通配符用不上 btree，所以走 pg_trgm。三列各自一条而不是一条拼接表达式索引，
-- 理由与 V27 的四条 trgm 索引相同（SQL 侧是三个 OR 分支，PostgreSQL 能 BitmapOr；
-- 拼成表达式则要求两边一致，一致性一破索引静默失效）。
CREATE INDEX party_address_label_trgm ON party_address USING gin (label gin_trgm_ops)
  WHERE deleted_at IS NULL;
CREATE INDEX party_address_street_trgm ON party_address USING gin (street_line1 gin_trgm_ops)
  WHERE deleted_at IS NULL;
CREATE INDEX party_address_city_trgm ON party_address USING gin (city gin_trgm_ops)
  WHERE deleted_at IS NULL;
CREATE INDEX party_contact_name_trgm ON party_contact USING gin (name gin_trgm_ops);
CREATE INDEX party_contact_email_trgm ON party_contact USING gin (email gin_trgm_ops)
  WHERE email IS NOT NULL;
CREATE INDEX party_contact_phone_trgm ON party_contact USING gin (mobile gin_trgm_ops)
  WHERE mobile IS NOT NULL;

-- ============================================================ 第八节：RLS
--
-- 四张表都是租户级主数据（与 party / brand 同级）：只需要租户隔离，
-- 可见性由端点层权限把关。写法照抄 V27 第五节。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['party_address','party_address_usage','party_address_version','party_contact'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON party_address, party_address_usage, party_address_version, party_contact TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON party_address, party_address_usage, party_address_version, party_contact TO starcon_app;

-- ============================================================ 第九节：本迁移钉死的语义（服务层 PartyAddressRules / PartyContactRules 的对应实现）
--
-- 写在这里是因为「数据库为什么这样设计」与「服务层为什么这样判」必须是同一份承诺，
-- 两处各说一套的话，下一个改代码的人只会读到其中一处（与 V27 第六节同一个理由）。
--
--   **A1 国家必填、邮编可空。** 见第二节两列注释。地址必须落在某个国家里，
--      而邮编在 HK / AE 等地确实不存在 —— 逼运营编假邮编会让面单打印失败。
--
--   **A2 一条地址可以有多个用途，每个用途各有一个默认。** 见第零节与第六节。
--      「默认提货地址」不是地址行上的一个布尔列，而是按 (主体, 用途) 唯一的。
--
--   **A3 版本链只记内容变更。** 内容真的变了才追加一行版本（版本号 +1）；
--      只改用途 / 只改默认标记**不产生新版本**（那是标签变化，不是地址变化）。
--      保存时内容一字未改也不追加 —— 否则版本列表里全是无法区分的行，
--      而它要回答的问题是「这个地址被改过几次、每次改成什么样」。
--      address.version（乐观锁）与 version_no（历史序号）**是两个数，不要混用**。
--
--   **A4 软删除，且删除前必须先取消默认。** 软删除理由见第二节。
--      删除时若这条地址还是某个用途的默认，报错要**点名是哪个用途**，
--      并让运营先把默认改到别的地址 —— 直接删掉的后果是「这个供应商没有提货地址了」
--      而 10 物流的单子已经填好了原地址。删掉之后它的用途行保留（is_default=false），
--      这样「它曾经是提货地址」在数据上仍然说得通。
--
--   **A5 拉黑的主体不允许新增地址或联系人**（停用允许）。与 V27 的 P6 同源同理由：
--      拉黑是「不与这个对手方做生意」，给这样的对手方新增提货地址与对接人正是要拦的动作；
--      而停用状态必须允许维护 —— 「先建主体、停用状态下把地址与联系人补齐、再启用」
--      是正常业务流（P5 的正常流程就要求停用状态下先准备好再启用）。
--      **改**地址与**改**联系人在任何主体状态下都允许：清理一个已拉黑对手方的
--      地址与联系人正是之后要做的动作。
--
--   **A6 选中阻断与原因码。** 已软删除的地址、被删除 / 停用 / 拉黑的主体的地址与联系人
--      都<b>不能</b>被下游新单据选中，各自带一个原因码（ADDRESS_DELETED /
--      PARTY_DELETED / PARTY_INACTIVE / PARTY_BLACKLISTED），
--      与 V27 的 usableBlock 同一形态：下游显示「为什么选不到」而不是让选项凭空消失。
--      读仍然放行 —— 删完之后回头看一眼要看到「已删除」，而不是一个 404。
--
--   **C1 邮箱 / 电话 / 手机至少给一项。** 见第五节 CHECK。
--
--   **C2 默认联系人只有一个，删除前必须先改默认。** 与 A4 同一个理由：
--      「这个供应商找谁签字」不能因为一次删除就静默消失。
--
-- ============================================================ 第十节：留给下游的口子（不是本模块的缺口）
--
--   * **06 采购 / 10 物流**：单据表里存**地址快照列**（权威）+ (address_id, address_version_no)
--     两个指路牌列（不建跨服务外键），写法见第四节的三层说明。
--     默认地址走 `GET /catalog/parties/{partyId}/addresses/defaults?usage=...`，
--     下拉候选走 `.../addresses/candidates?usage=...`。
--   * **03.6.1.2 角色档案**：不碰本模块任何一张表（账期 / MOQ / 客户分级与地址无关）。
--   * **3.9.2.2 僵尸主数据清理**：本模块不造僵尸行 ——
--     A5 拦下「给拉黑对手方新增地址」，A1/C1 拦下「填不全的地址与联系人」。
--   * **主体删除（3.6.1.1 的 DELETE /parties/{id}）**：本模块**刻意没有**去改上游的删除规则
--     （V27 的 P7 只要求先解掉角色，不要求先清地址 —— 那是 3.6.1.1 的文件，本模块不碰）。
--     后果是主体被软删除后它下面的地址与联系人仍在库里：A6 让它们在界面上带
--     PARTY_DELETED 原因码、不可被新单据选中，而详情仍可读。
--     这与 3.6.1.1 对角色行的处理同源（角色也被级联保留，主体删除后不可用）。