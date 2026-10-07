-- 3.6.1.1 往来单位档案：统一业务主体（party）+ 角色指派关联（party_role）。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：统一维护业务主体（`party`）：企业或个人，可同时扮演供应商/客户/承运商；
--             包含编码、名称、联系方式、状态。
--   **输入**：新增/编辑往来单位；角色指派；状态变更。
--   **输出**：往来单位档案 + 关联的角色档案。
--   **关系**：上游 = —；下游 = 3.6.1.2 角色档案、06 采购、11 售后、12 财务。
--             **同一主体可同时是供应商和客户，用角色档案区分，不用多份记录**（实体目录 §3.5）。
--
-- ============================================================ 第零节：为什么是「一张主体 + 角色关联」两张表
--
-- 实体目录旧稿 `db/schemas/02-master-data.md` 的「往来单位」一节写的是
-- supplier / customer / carrier **三张表**。本迁移**不**照抄它，理由是蓝图在同一卷里
-- 明确推翻了那个形状：
--
--   旧稿的形状里，一个既是供应商又是客户的对手方必须**建两份记录**：
--   `supplier` 一行 + `customer` 一行，两行各有各的 code、name、status。
--   后果不是「数据多了一点」，而是三件**做不到**的事：
--     ① 06 采购单引用 supplier.id、12 财务的应付引用同一个供应商，
--        07 售后要联系的那个客户在另一张表里 —— 同一个对手方有 2 个 id，
--        跨表做「这个供应商其实也是我们的客户」的对账/返点结算没有任何稳定锚点；
--     ② 对方公司改名要改两行，改漏一行的症状是采购单上的名称与收款方对不上，
--        而这种错要等到对账日才暴露；
--     ③ 11 售后联系不上人的时候要分别查两张表，而「这两个手机号不一样」这件事
--        在数据上根本无法表达（只能靠人记住）。
--
--   蓝图那句「用角色档案区分，不用多份记录」就是针对这三条。**主体是自然人/法人，
--   角色是它在业务里的身份** —— 身份可以有多个，主体只有一个。所以：
--     `party`      = 主体（企业或个人）：编码、名称、类型、联系方式、状态
--     `party_role` = 主体在业务里的身份（供应商 / 客户 / 承运商），一个主体可以同时有多个
--
-- 与已交付模块的口径一致：本迁移不建任何类目/品牌/SKU 表，也不引用不存在的上游
-- （蓝图「上游 = —」，台账里 3.6.1.2 尚未开发）。`backend/services/trade` 目前不存在，
-- 而往来单位按蓝图归属是商品主数据域（卷 03），所以落 catalog 而不是 trade。
--
-- ============================================================ 第一节：party —— 主体档案
--
-- 主体是**租户级主数据**（与 product_category / spec_axis / brand 同级）：
-- 同一批对手方被 06 采购、11 售后、12 财务共同引用，不属于任何个人。
-- 所以没有 owner_id / department_id 那套数据范围列（那是 V3 给「个人负责的业务单据」用的）。
CREATE TABLE party (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,

  -- 编码：租户内唯一，是 06 采购单 / 12 应付账款 / 11 售后单对齐这个主体的锚点。
  -- 形态照抄 V16 的 brand.code（同一域里同一性质的字典编码），字符集一致以便人工维护。
  code varchar(64) NOT NULL,

  -- 名称：企业的法定名称或个人的姓名。跨境场景下「对方公司名」经常需要带后缀
  -- （Co., Ltd / GmbH / Inc），100 列不够用，所以 200。
  name varchar(200) NOT NULL,

  -- 主体类型：企业或个人。两者在下游的处理不同 —— 企业要开票、要看税号与结算主体，
  -- 个人在 B2C 场景里就是收件人本人，所以类型必须显式区分而不是靠 name 猜。
  -- 这两个值就是蓝图职责里「企业或个人」的全部取值，不设第三类
  -- （「政府/机构」在跨境 B2B 里按企业处理会错，按个人处理会错得更离谱，
  --   真出现时应该是新增一个枚举值并同步下游，而不是靠备注硬塞进企业）。
  party_type text NOT NULL CHECK(party_type IN ('ORGANIZATION','INDIVIDUAL')),

  -- 注册国 / 所在地：ISO 3166-1 alpha-2 两位大写。跨境是这个系统的地基，
  -- 下游 06 采购判贸易条款、12 财务判关税与增值税、10 报关判原产地都要读它。
  -- 可空而不是 NOT NULL：个人主体在 07 售后「寄回寄件人」这类场景里可能确实没有，
  -- 而把「没有」表达成空串或 'XX' 才是真的脏数据。
  country_code varchar(2),

  -- ------------------------------------------------------------ 联系方式
  -- 蓝图职责里的「联系方式」落在这三列上。**刻意不建 party_contact 表**：
  -- 「带姓名的联系人」是 3.6.3.1 地址与联系人的职责（那边还要管地址版本与默认地址），
  -- 本模块只回答「怎么找到这个主体」。两者的区别是：这里是主体自己的对外通道，
  -- 那里是「跟这个主体对接的那个人」。在这里提前建联系人表会和 3.6.3.1 撞车。
  email varchar(254),   -- 254 是 RFC 5321 的路径长度上限，不是随手取的数
  phone varchar(64),
  -- 官网：很多供应商的「联系方式」就是官网（下单要查规格书）。可选。
  website varchar(500),

  -- 状态：ACTIVE=可用 / INACTIVE=停用（不再用于新单据，历史单据照常显示）/
  -- BLACKLISTED=拉黑（制裁名单、纠纷对手方）。
  -- 枚举化而不是自由文本：这三个值在下游的处理动作**完全不同**
  -- （选单据时被选中要拦下、报表要分组、付款要冻结），自由文本没法在 SQL 侧判。
  status text NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','INACTIVE','BLACKLISTED')),

  -- 状态变更的理由：进出 BLACKLISTED 必填（服务层 P6 强制），其余转换可空。
  -- 存理由而不是只写 audit_event 是因为列表页要能一眼看到「为什么这个供应商被拉黑」，
  -- 而不是每次都去审计日志里翻（审计日志是 17.5.3.1 的读出口，不适合高频展示）。
  status_reason text NOT NULL DEFAULT '',

  -- 状态最后一次变更的时刻。与 created_at/updated_at 分开：
  -- updated_at 每次改名改邮箱都会动，拿它显示「这个供应商什么时候停用的」会得到错误答案。
  status_changed_at timestamptz,

  -- ------------------------------------------------------------ 软删除
  -- deleted_at 非空 = 这个主体已被删除，列表默认不显示。
  -- **软删除而不是物理删除**：06 采购单、12 应付、11 售后单将来都会引用这个 id，
  -- 物理删除会让历史单据指向一个不存在的行，而「对手方是谁」是财务凭证上必须显示的东西。
  -- 删掉行之后那笔单据就只能显示一个 uuid —— 那正是要避免的形态。
  -- 所以本模块**只提供软删除端点，没有物理删除端点**（物理删除留给运维在归档期做）。
  deleted_at timestamptz,
  deleted_by uuid,

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 编码在租户内唯一。蓝图要的「唯一编码校验」在 DB 层是兜底，服务层给可读原因（409）。
  UNIQUE(tenant_id,code),

  CHECK(btrim(code)<>'' AND btrim(name)<>''),
  CHECK(country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
  -- 邮箱与电话留空是「没填」，留空白串是脏数据，所以要么 NULL 要么非空白。
  CHECK(email IS NULL OR btrim(email)<>''),
  CHECK(phone IS NULL OR btrim(phone)<>''),
  CHECK(website IS NULL OR btrim(website)<>''),
  -- 联系方式至少给一项，否则是一张在 06/12 里用不了的档案（服务层 P3 也会判，双保险）。
  -- DB CHECK 让「绕过服务层直接写库」也写不出这种行。
  CHECK(email IS NOT NULL OR phone IS NOT NULL),
  -- 拉黑必须留理由：没有理由的拉黑在三个月后没人敢解开，也没人知道该不该解开。
  CHECK(status<>'BLACKLISTED' OR btrim(status_reason)<>''),
  -- 删除必须留操作人：与 audit_event 双向可查。
  CHECK(deleted_at IS NULL OR deleted_by IS NOT NULL)
);

-- ============================================================ 第二节：party_role —— 角色指派（3.6.1.1 的范围内）
--
-- 蓝图「输入」点名了「角色指派」，「输出」点名了「关联的角色档案」，
-- 所以角色指派必须**真的落成可用的数据与端点** —— 不能只留一个 TODO 字段，
-- 也不能因为 3.6.1.2 未开发就假装它已经存在。本表就是那个可用的关联。
CREATE TABLE party_role (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, party_id uuid NOT NULL,
  -- 角色类型：供应商 / 客户 / 承运商。这就是蓝图职责里点名的三个身份。
  role_type text NOT NULL CHECK(role_type IN ('SUPPLIER','CUSTOMER','CARRIER')),
  -- 指派自身的启停：ACTIVE=这个身份在用；INACTIVE=暂时不用这个身份
  -- （例如某个客户只做零售所以不给他开供应商身份，但主体与另一条角色都留着）。
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- **同一主体同一角色只有一条**：这是蓝图「不用多份记录」在角色层的具体落点。
  -- 重复导入供应商数据时多写一条 SUPPLIER，症状是「采购单挂了这个供应商」与
  -- 「供应商档案里有两条」对不上，而两者没有任何稳定键可以互相定位。
  UNIQUE(tenant_id,party_id,role_type),
  -- 复合外键带 tenant_id：跨租户挂角色在 DB 层不可能发生
  -- （只引用 party.id 的话，另一个租户构造一个 id 就能把角色挂到别人的主体上）。
  FOREIGN KEY(tenant_id,party_id) REFERENCES party(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 第三节：刻意留给 3.6.1.2 的东西（不是缺口）
--
-- 蓝图 3.6.1.2 写死了两件事：
--   ① 角色特有信息：供应商（账期、MOQ、合作等级、结算方式）、
--      客户（客户分级、默认币种、信用额度）、承运商（接口类型、账号、服务能力）；
--   ② **每个角色一张 1:1 档案表**，不用一个巨大可空字段表 ——
--      否则「客户没有账期」和「供应商有账期」无法区分。
--
-- 所以本迁移**不在 party_role 上加任何角色字段**（不加 payment_terms_days、不加
-- credit_limit、不加 moq）。理由不是省事：
--   * 加上去就是蓝图点名否决的那个「巨大可空字段表」：10 列里 8 列对某个角色永远为 NULL，
--     而「客户没有账期」与「供应商有账期」这两种情况在那种表里是**同一种存储形态**，
--     12 财务取默认账期时无法区分「这个客户没设账期」与「这列不属于客户」；
--   * 加上去还要现在就定 20 个字段的类型、默认值与校验口径，而这些字段的语义
--     归 3.6.1.2 决定，本模块先定会让 3.6.1.2 变成「照抄已有实现」而不是独立设计。
--
-- 3.6.1.2 落地时的接口：按 role_type 建三张 1:1 档案表
-- （party_supplier_profile / party_customer_profile / party_carrier_profile），
-- 主键 (tenant_id, party_id)，复合外键指向本表 (tenant_id, party_id)。
-- **本表的 role_type 就是那张表的连接键** —— 3.6.1.2 不需要改本表一行就能长出来。
--
-- 与 3.6.3.1 的分工同理：地址与「带姓名的联系人」归 3.6.3.1（那边还要管地址版本），
-- 本表与 party 上的 email/phone/website 是主体自己的对外通道。
--
-- ============================================================ 第四节：索引
--
-- 列表页主查询：租户 + 未删除 + 状态筛选 + 编码稳定排序。
-- deleted_at 放在状态前面是因为**每一个**列表查询都带 `deleted_at is null`
-- （软删除是默认语义而不是可选项），放在前面能让它先被索引用上。
CREATE INDEX party_list ON party(tenant_id,deleted_at,status,code);
-- 按主体类型筛（企业/个人在商品主数据视图里是两个口径）。
CREATE INDEX party_by_type ON party(tenant_id,deleted_at,party_type,code);
-- 「这个主体有哪些角色」——详情页与「同一主体可同时是供应商和客户」的展示。
CREATE INDEX party_role_by_party ON party_role(tenant_id,party_id,status,role_type);
-- 「谁是活跃的供应商」——06 采购单选供应商、12 应付选供应商都走这条，
-- 与上面那条方向相反：这里是按角色找主体。
CREATE INDEX party_role_by_role ON party_role(tenant_id,role_type,status,party_id);
-- 「这个编码存在吗」的唯一性预检（服务层给可读 409 之前的那次 select）。
-- UNIQUE(tenant_id,code) 已经隐含这个索引，这里**不再**重复建一个同列索引。

-- 关键词搜索：编码 / 名称 / 邮箱 / 电话四列的 ILIKE '%kw%'。
-- 前导通配符用不上 btree，所以用 V21 已经引入的 pg_trgm 建 GIN 索引
-- （`CREATE EXTENSION IF NOT EXISTS pg_trgm` 在 V21 已执行，这里不重复声明）。
-- 四条独立索引而不是一条拼接表达式索引：服务层的检索是四个 OR 分支，
-- PostgreSQL 能把四条索引 BitmapOr 起来；拼成一条表达式索引则要求 SQL 侧也拼，
-- 一旦两边不一致索引就静默失效（而且不报错，只是变慢）。
CREATE INDEX party_code_trgm ON party USING gin (code gin_trgm_ops) WHERE deleted_at IS NULL;
CREATE INDEX party_name_trgm ON party USING gin (name gin_trgm_ops) WHERE deleted_at IS NULL;
CREATE INDEX party_email_trgm ON party USING gin (email gin_trgm_ops)
  WHERE deleted_at IS NULL AND email IS NOT NULL;
CREATE INDEX party_phone_trgm ON party USING gin (phone gin_trgm_ops)
  WHERE deleted_at IS NULL AND phone IS NOT NULL;

-- ============================================================ 第五节：RLS
--
-- 两张表都是租户级主数据（与 brand / product_category / unit_of_measure 同级）：
-- 只需要租户隔离，可见性由端点层权限把关。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['party','party_role'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON party, party_role TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON party, party_role TO starcon_app;

-- ============================================================ 第六节：本迁移钉死的七条语义（服务层 PartyRules 的对应实现）
--
-- 写在这里是因为「数据库为什么这样设计」与「服务层为什么这样判」必须是同一份承诺，
-- 两处各说一套的话，下一个改代码的人只会读到其中一处。
--
--   **P1 编码建后不可改。** code 是 06 采购单 / 12 应付 / 11 售后单对齐这个主体的锚点，
--      改编码会让已有单据在下一次读取时突然「找不到这个供应商」。
--      与 V16 对 brand.code 的同一口径：不可改字段给 400，409 留给 version 乐观锁与唯一冲突。
--
--   **P2 主体类型显式枚举。** ORGANIZATION / INDIVIDUAL，理由见第一节。
--      类型**可以**改（个人升级成公司注册是真实业务动作），但角色不随类型变化：
--      「原来是个人所以不能当供应商」不是改类型的理由。
--
--   **P3 联系方式至少给一项（email 或 phone）。** 一张既没有邮箱也没有电话的主体档案，
--      在 06 采购（问交期）、11 售后（问退货）、12 财务（对账）三处都用不了 ——
--      允许它建档只会造出一批「查得到但联系不上」的僵尸记录，而 3.9.2.2 僵尸主数据清理
--      要处理的正是这类东西。**被拒的应该是建档**，因为这种记录从一开始就无法履约。
--
--   **P4 状态流转有方向，且进出拉黑必须留理由。**
--        ACTIVE      → INACTIVE   停用（不再用于新单据）
--        ACTIVE      → BLACKLISTED 拉黑（必须给理由）
--        INACTIVE    → ACTIVE     重新启用
--        BLACKLISTED → INACTIVE   拉黑转停用（必须给理由：为什么不再直接启用）
--        BLACKLISTED → ACTIVE     解除拉黑（必须给理由）
--        自环（同状态 → 同状态）拒绝：一次「启用」如果本来就已启用，
--        应当报「状态没有变化」而不是悄悄 +1 版本号骗过乐观锁。
--      **为什么允许 BLACKLISTED → INACTIVE 而不是一步到位回 ACTIVE**：
--      两个状态在业务上的含义不同，INACTIVE 是「我们自己不用了」，
--      ACTIVE 是「我们可以正常合作」。从拉黑直接跳回 ACTIVE 会让「制裁解除」
--      与「我们自己决定恢复合作」两件事在数据上无法区分，而前者通常需要留档。
--
--   **P5 主体变为 ACTIVE 时必须至少有一个已指派的角色。** 没有角色的主体是
--      一条填在所有下拉里、但谁都选不出意义的记录。允许「先建主体、后指派角色」
--      是正常业务流（服务层不阻止建档），但**启用**时才拦住 ——
--      理由与 V16 对「建档不要求凭证、被拒的应该是刊登」同源：
--      被拒的应该是「让它在下游可用」，不是「建档」。
--
--   **P6 拉黑的主体不允许新增角色指派**（停用状态允许，解指派与改备注在任何状态下都允许）。
--      只拦拉黑、不拦停用，是因为 **P5 的正常流程要求在停用状态下先把角色挂好再启用**
--      （「先建主体、后定角色」是真实业务流）；而拉黑是「不与这个对手方做生意」，
--      给它新增一个商业身份正是要拦的动作。停用主体上挂新角色看起来像僵尸，
--      但它并不持久：主体重新启用时那些角色立刻有用。
--
--   **P7 删除是软删除，且删除前必须解掉全部角色。** 软删除的理由见第一节的 deleted_at。
--      「必须先解掉角色」是因为删除后主体对列表不可见，而它的角色行还在、
--      详情端点直接 404 —— 那种「角色还在但主体不见了」的状态没有任何人能解释，
--      运营也无法自己把它收拾干净。报错要逐个点名还挂着哪些角色。
--
-- ============================================================ 第七节：留给下游的口子（不是本模块的缺口）
--
--   * **3.6.1.2 角色档案**：按第三节的三张 1:1 表扩展，主键 (tenant_id, party_id)
--     复合外键指向 party_role，本表无需改动。
--   * **3.6.2.1 供应商报价**：引用 party_role 里 role_type='SUPPLIER' 的主体，
--     走授权 API / event，不在本服务里 JOIN（本服务与 trade 服务的边界见第六节）。
--   * **3.6.3.1 地址与联系人**：地址与带姓名的联系人归它。本表的 email/phone/website
--     是主体自己的对外通道，两者不重复。
--   * **06 采购 / 11 售后 / 12 财务**：单据冻结当时的名称与联系方式快照
--     （与 3.6.3.1 同口径：改主体不影响已发生单据），快照写入归各自服务所有。
--   * **4.1.x 平台客户映射**：客户侧的平台 ID（Amazon 的 buyer id 等）按蓝图
--     属于 04 渠道，不在本表 —— 跨服务的渠道 ID 放本表就是复制一份别人的写入所有权。
