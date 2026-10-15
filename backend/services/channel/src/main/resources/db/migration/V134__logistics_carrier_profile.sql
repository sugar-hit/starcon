-- ===========================================================================
-- 10.1.1.1 承运商列表（channel 服务）
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/10-logistics-and-freight.md）：
--   ### 10.1.1.1 承运商列表
--   - 职责：管理承运商（快递、专线、邮政、FBA 承运、末端派送）：接口类型、账号、
--           覆盖国家、时效等级、支持业务类型（自发货/退货/FBA 入仓）。
--   - 输入：承运商定义与接口配置；账号凭据（存密钥管理器引用）。
--   - 输出：承运商档案 + 可用性状态。
--   - 关系：上游 = 3.6.1.2 承运商角色档案；下游 = 10.1.1.2 渠道配置。
--           **承运商是往来单位的一种角色**（实体目录 §3.5），不在此处重复建档。
--
-- 归属 channel：蓝图卷 10 卷首「后端归属：channel 拥有物流商连接与轨迹」。
--
-- ---------------------------------------------------------------------------
-- 零、先纠正一处上游归属（本轮集成头实查结论，与派单说明里的写法不同）
-- ---------------------------------------------------------------------------
-- 派单说明写的是「上游 3.6.1.2 已交付在 **identity**（V30__*.sql，/legal-entities 形状）」。
-- 实查结果是：**上游确实已交付，但归属服务是 catalog 不是 identity**：
--   * catalog `V27__party_profile.sql`      → party（主体）+ party_role（角色指派，role_type 含 CARRIER）
--   * catalog `V30__party_role_profiles.sql` → party_carrier_profile（接口类型 / 账号 / 服务能力）
--   * 读出口 `GET /catalog/parties?roleType=CARRIER&page&size`（真分页）
--             `GET /catalog/parties/{id}/carrier-profile`
-- identity 那边是 17.1.2.1 **法人档案**（V5__legal_entity.sql，`/legal-entities`），
-- 那是「我们自己这个开票/记账主体」，与「承运商这个对手方」是**两回事**。
-- 照派单说明去读 /legal-entities 会把「我方法人」当成「上游承运商」，
-- 那是一个**能跑通但业务上完全错误**的接法，所以这里显式钉死。
--
-- ⚠️ 但蓝图那条加粗纪律**一字不改地成立**：**承运商是往来单位的一种角色，
--   不在此处重复建档。** 下游 3.6.1.1/3.6.1.2 已经把「一个对手方只有一份主体记录、
--   身份可以有多个」这件事做完了（catalog `party` + `party_role`）。本迁移
--   **一条 CREATE 都不建承运商主档**，见第二节的字段归属表。
--
-- ---------------------------------------------------------------------------
-- 一、字段归属：哪些是 catalog 的主权，哪些才是本模块的
-- ---------------------------------------------------------------------------
-- 本模块最容易出的错是「顺手把承运商的法人信息也抄一份过来」，因为页面上
-- 「承运商列表」看上去就是要显示公司名、公司国家、电话。**那些全部是 catalog 的主权**。
-- 本迁移把每个字段的归属钉死（服务层与接口层同样按这张表执行）：
--
--   | 字段                     | 归属          | 本模块的动作                       |
--   |--------------------------|---------------|------------------------------------|
--   | 公司名 / 编码 / 类型      | catalog party | **只读快照，不可编辑**              |
--   | 承运商身份（CARRIER 角色）| catalog party_role | **只存 role_id 逻辑引用**       |
--   | 接口类型 interface_type   | catalog party_carrier_profile | **只读快照**（V30 已建，不重建）|
--   | 账号 account_ref          | catalog party_carrier_profile | **只读快照**                   |
--   | 服务能力 service_capabilities | catalog party_carrier_profile | 只读快照                 |
--   | 主体状态 / 可用性          | catalog       | **只读快照**（可用性判定的输入）    |
--   | **覆盖国家**              | **本模块**    | 新建多值关系表                      |
--   | **时效等级**              | **本模块**    | 新建列                              |
--   | **业务类型（自发货/退货/FBA 入仓）** | **本模块** | 新建多值关系表              |
--   | **承运商类别（快递/专线/邮政/FBA/末端派送）** | **本模块** | 新建列                  |
--   | **凭据引用 secret_ref + 掩码** | **本模块** | 新建列（只存引用，见第四节）      |
--   | **可用性状态**            | **本模块**    | 读时计算 + DB 层复查（见第三节）    |
--
-- 换句话说：本表是**物流侧的角色投影**，不是承运商主档。改名、改公司国家、
-- 改接口类型都要回 catalog 的 /partners 去做；本模块的任何写端点都**不接受**
-- 主体身份字段，从源头杜绝「同一个承运商在两处各改一次」。
--
-- 为什么不每次穿透读 catalog（照抄 10.3.2.1 的投影理由）：
--   ① 承运商列表要按「可用性」分组汇总，可用性依赖主体可用性 + 覆盖国家 +
--      业务类型三处判定，逐行穿透会把 N+1 次 HTTP 压到每一次列表查询；
--   ② 主体可用性是**会变的**（被拉黑、被停用），而「这条物流配置当时长什么样」
--      应当留在快照里 —— 追责时要看到的是当时的事实，不是今天的。
--
-- ---------------------------------------------------------------------------
-- 二、凭据：只存密钥管理器引用，DB 层就拒真凭据
-- ---------------------------------------------------------------------------
-- 蓝图输入句写死「账号凭据（**存密钥管理器引用**）」，AGENTS.md 安全段也写死
-- 「接入第三方 API 的 key 全部走 secret manager」。所以：
--
--   * `secret_ref` 只接受**引用形状**：`secret://` / `kv://` / `vault://` /
--     `external-secrets://` 开头（CHECK 钉死）。粘一个真实 API token 进来
--     会在**写库那一刻**被拒，而不是等到泄露之后才发现 —— 因为真实 token
--     不可能带这四个前缀。
--   * `secret_masked` 必须**真的是掩码**（含 `*`）。要求「有引用就必须有掩码」
--     是因为界面上要显示「已配置（sk_live_****3f2a）」，只存引用不存掩码
--     会逼着前端每次去问密钥管理器，或者干脆显示成空白 —— 两者都会让运营
--     以为没配凭据，于是重复配置，把同一个承运商登记两遍。
--   * 本迁移与集成测试里**不出现任何真实凭据**（测试只断言引用与掩码形状）。
--
-- 这里刻意**不做**的：不去校验引用在密钥管理器里是否真的存在。密钥管理器
-- 不在本服务的数据源里，跨系统存在性校验只能靠部署侧；本服务只保证「存下来的
-- 是一个引用而不是一个凭据」，这个才是数据层能负责的部分。
--
-- ---------------------------------------------------------------------------
-- 三、可用性状态：**可判定的依据**，不是管理员手填的下拉框
-- ---------------------------------------------------------------------------
-- 蓝图输出句要「承运商档案 + 可用性状态」。最容易出的假活是把可用性做成一个
-- 管理员自己选的下拉框（选「可用」就显示可用）—— 那它什么都没判定，
-- 只是把人的判断原样存下来。本迁移按 V129 的既有做法钉死：
--
--   * `readiness` 列**不接受调用方指定**：由 `guard_carrier_logistics_readiness`
--     触发器在写入路径上**重算一遍**并比对，对不上直接拒。绕过服务层直接写
--     SQL 把它改成「READY」同样被拒（与 guard_handover_batch_counts 同款）。
--   * 重算只用**本服务自己看得见的输入**：是否登记了凭据引用、是否至少覆盖
--     一个国家、是否至少支持一种业务类型。
--   * 依赖上游的部分（主体是否可用、接口类型是不是只能人工）**不进 DB 重算**，
--     由服务层在读时与 `readiness` 合成对外的 `availability`。原因很直接：
--     上游那些事实在本库里不存在，DB 触发器看不见，硬要存一个值就会变成
--     管理员手填 —— 那正是本节要消灭的东西。
--
--   readiness 取值（DB 层，优先级从高到低，唯一可判）：
--     NOT_REGISTERED     物流侧档案还没登记（承运商类别或时效等级为空）——
--                        同步只读上游，推不出这两个字段，所以它们由运营登记
--     NO_CREDENTIAL      没有凭据引用 —— 系统对接拿不到鉴权材料
--     NO_COVERAGE        没有覆盖任何国家 —— 一个国家都送不到
--     NO_BUSINESS_SCOPE  没有声明任何业务类型 —— 自发货/退货/FBA 入仓都不能选它
--     READY              四项齐备
--
--   availability 取值（服务层读时合成，priority 见下）：
--     PARTY_BLOCKED   上游主体不可用（停用 / 拉黑 / 已删除）
--     NOT_REGISTERED / NO_CREDENTIAL / NO_COVERAGE / NO_BUSINESS_SCOPE  透传 readiness
--     MANUAL_ONLY     配置齐了，但接口类型是 WEB_PORTAL/EMAIL，只能人工处理
--     AVAILABLE       可用于系统自动对接
--
-- 顺序有讲究：**主体不可用排第一**（上游说这个对手方已经不能做生意了，
-- 配置得再齐也没用），配置缺口排第二（那才是本模块自己能解决的），
-- MANUAL_ONLY 排最后（它不是缺陷，是一种真实的对接方式）。
--
-- ---------------------------------------------------------------------------
-- 四、多值维度：覆盖国家与业务类型都是真关系表，不是逗号串
-- ---------------------------------------------------------------------------
-- 覆盖国家与支持业务类型都要「能按它筛选」。逗号分隔字符串在筛选时只能写
-- `like '%,SEA,%'`，而 `SEA` 排在靠前位置时会漏匹配（这正是 catalog V30
-- 拒绝拼接索引、V27 拒绝逗号串的同一个坑，已有前科）。所以两张都是
-- **每值一行**的关联表，带主键与父表复合外键，可以真 JOIN、真 GIN/btree 索引。
--
-- 五、下游 10.1.1.2 未交付 ⇒ capabilities 显式写缺口
-- 蓝图关系句点明「下游 = 10.1.1.2 渠道配置」。本轮集成头台账确认 10.1.1.2 未开发，
-- 所以本模块只交付**承运商侧**，渠道侧（所属渠道 / 计费方式 / 附加费 /
-- 申报限制 / 保价规则）如实标 available=false，不提前造渠道表。
--
-- 六、跨车道回归自查
-- 本迁移**只 CREATE 表 / 索引 / 触发器 / 函数，零 ALTER 任何既有表**：
--   * 不碰 channel V1/V2/V3/V4/V93/V129 的任何一张表
--   * **不碰 catalog 的任何一张表**（party / party_role / party_carrier_profile
--     一条 SELECT 都没有，更没有跨库 FK 或视图）
--   * 三张表的 FK 全部指向**本迁移自己新建的表**，属同库引用
--   * 复用 identity 已有的 channel.operation.* 权限码，**identity 零改动**
-- 三张表落在 carrier_* 命名空间，与 handover_*（10.3.2.1 交运批次）并列而不混用。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 投影来源表的复合唯一键（供子表外键与「一个主体只挂一个 CARRIER」用）
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. carrier_logistics_profile · 承运商的物流侧投影
-- ---------------------------------------------------------------------------
-- 一行 = catalog 上一个挂了 CARRIER 角色的往来单位，在**物流侧**的档案。
-- 主体身份（名称/编码/公司类型/国家/联系方式）**不在这里维护**，只保留同步快照。
CREATE TABLE carrier_logistics_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 上游 catalog 3.6.1.1 / 3.6.1.2（逻辑引用，无跨服务 FK）===
  -- role_id 指向 catalog party_role(tenant_id,id) 里 role_type='CARRIER' 的那一条。
  -- 事实归 catalog，本服务只存这个键：改接口类型、改名称都回 /partners 去做。
  role_id uuid NOT NULL,
  party_id uuid NOT NULL,

  -- === 以下 7 列是**上游只读快照**，本模块任何写端点都不接受它们 ===
  -- 建档后 party_code 不可改（照抄 3.6.1.1 的 P1：编码是下游单据对齐主体的锚点，
  -- 改编码会让已有单据下一次读取就找不到这个主体），由同步按上游原值写回。
  party_code varchar(64) NOT NULL CHECK (btrim(party_code) <> ''),
  party_name varchar(200) NOT NULL CHECK (btrim(party_name) <> ''),
  party_status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  party_usable boolean NOT NULL DEFAULT true,
  role_status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  interface_type varchar(16) NOT NULL DEFAULT 'API'
    CHECK (interface_type IN ('API','SFTP_FILE','WEB_PORTAL','EMAIL')),
  account_ref varchar(128) NOT NULL DEFAULT '',
  service_capabilities text[] NOT NULL DEFAULT '{}',

  -- === 以下才是本模块（10 物流）的主权字段 ===

  -- 承运商类别：蓝图职责句括号里的五种。**物流侧分类**，catalog 不管这件事
  -- （catalog 的 service_capabilities 说的是「空运/海运/快递」这些运输能力，
  --  与「这个承运商是快递还是专线还是末端派送」是**两个维度**：
  --  顺丰既是快递又能做专线，两者不能互相替代，所以这里单列一列而不是
  --  从 service_capabilities 推）。
  carrier_category text
    CHECK (carrier_category IS NULL
           OR carrier_category IN ('EXPRESS_COURIER','LINE_HAUL','POSTAL','FBA_CARRIER','LAST_MILE')),

  -- 时效等级：蓝图职责句点名的维度。刻意**只有三档**而不是自由文本 ——
  -- 时效等级是下游 10.1.1.2 渠道配置选线路时的筛选条件，自由文本没法筛。
  service_tier text
    CHECK (service_tier IS NULL
           OR service_tier IN ('STANDARD','EXPRESS','PRIORITY')),

  -- 凭据：**只存密钥管理器引用**（见文件头第二节）。留空 = 尚未配置凭据。
  secret_ref varchar(200) NOT NULL DEFAULT ''
    CHECK (secret_ref = '' OR secret_ref ~ '^(secret://|kv://|vault://|external-secrets://)'),
  -- 掩码：有引用就必须有掩码，且掩码必须真的带 `*`（见文件头第二节）。
  secret_masked varchar(64) NOT NULL DEFAULT ''
    CHECK (secret_ref = '' OR (btrim(secret_masked) <> '' AND secret_masked LIKE '%*%')),

  -- 可用性（DB 层那半）：**不接受调用方指定**，由触发器重算比对，见第三节。
  readiness text NOT NULL DEFAULT 'NOT_REGISTERED'
    CHECK (readiness IN ('READY','NOT_REGISTERED','NO_CREDENTIAL','NO_COVERAGE','NO_BUSINESS_SCOPE')),

  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  -- 最近一次从 catalog 同步快照的时刻（NULL = 从未同步过）。
  -- 与 updated_at 分开存：改备注不该刷新它（照抄 3.6.1.1 的 status_changed_at ——
  -- updated_at 每次编辑都动，拿它显示「这个快照是什么时候从上游取的」是错的）。
  synced_at timestamptz,

  -- 一个 CARRIER 角色只对应一条物流投影（catalog 侧已保证一个主体一个 CARRIER 角色，
  -- 这里再钉一道，免得同一角色被登记两遍而在列表上出现两行）。
  UNIQUE (tenant_id, role_id),
  -- 供两张子表的复合外键使用（同库引用，不是跨服务 FK）。
  UNIQUE (tenant_id, id),
  -- 一个主体只挂一个 CARRIER 角色 ⇒ 一个主体只可能有一条物流投影。
  -- 缺了这条约束，"先建主体后指派角色"的正常流程能造出两条投影行，
  -- 而列表上它们看起来是两个承运商。
  UNIQUE (tenant_id, party_id)
);

-- 主体编码检索（照抄 catalog 的 ILIKE '%kw%' 结论：前导通配符用不上 btree，
--  这里先留 btree 供等值与排序用，模糊检索走 pg_trgm 由服务层按需）。
CREATE INDEX carrier_logistics_profile_by_code
  ON carrier_logistics_profile(tenant_id, party_code);

-- 分类 / 时效筛选（页面上的两个下拉）。
CREATE INDEX carrier_logistics_profile_by_category
  ON carrier_logistics_profile(tenant_id, carrier_category);
CREATE INDEX carrier_logistics_profile_by_tier
  ON carrier_logistics_profile(tenant_id, service_tier);

-- 承运商类别 = 快递 ⇔ service_capabilities 含 EXPRESS。这条 GIN 索引**不是**为了让
-- 两者相等（它们是两个维度，见 carrier_category 的注释），而是为了让
-- 「支持海运的承运商有哪些」这类能力检索不必全表扫（与 catalog
-- party_carrier_profile_by_capability 同款用途）。
CREATE INDEX carrier_logistics_profile_by_capability
  ON carrier_logistics_profile USING gin (service_capabilities);

-- ---------------------------------------------------------------------------
-- 2. carrier_country_coverage · 覆盖国家（真多值关系）
-- ---------------------------------------------------------------------------
-- 蓝图职责句「覆盖国家」落在这里：每国一行，可按国家真筛选。
-- 用 varchar(2) 而不是 bpchar：长度已在 CHECK 里约束，不需要 bpchar 的补空格语义
-- （照抄 identity V5 的同一结论）。
CREATE TABLE carrier_country_coverage (
  tenant_id uuid NOT NULL,
  profile_id uuid NOT NULL,
  country_code varchar(2) NOT NULL
    CHECK (country_code ~ '^[A-Z]{2}$'),
  PRIMARY KEY (tenant_id, profile_id, country_code),
  FOREIGN KEY (tenant_id, profile_id)
    REFERENCES carrier_logistics_profile(tenant_id, id) ON DELETE CASCADE
);

-- 「支持送到德国的承运商有哪些」—— 主查询走这条（复合主键的前缀已够用）。
CREATE INDEX carrier_country_coverage_by_country
  ON carrier_country_coverage(tenant_id, country_code);

-- ---------------------------------------------------------------------------
-- 3. carrier_business_scope · 支持业务类型（真多值关系）
-- ---------------------------------------------------------------------------
-- 蓝图职责句「支持业务类型（自发货/退货/FBA 入仓）」逐值一行。
-- FBA_INBOUND 与 FBA_CARRIER 类别的区别：类别说的是「这个承运商是什么」，
-- 业务类型说的是「这个承运商能不能接这类单」，两者不能互相推导 ——
-- 一个 FBA 承运商可以送自发货也可以送 FBA 入仓（这正是要分开建列的原因）。
CREATE TABLE carrier_business_scope (
  tenant_id uuid NOT NULL,
  profile_id uuid NOT NULL,
  business_type text NOT NULL
    CHECK (business_type IN ('OWN_FULFILLMENT','RETURN','FBA_INBOUND')),
  PRIMARY KEY (tenant_id, profile_id, business_type),
  FOREIGN KEY (tenant_id, profile_id)
    REFERENCES carrier_logistics_profile(tenant_id, id) ON DELETE CASCADE
);

-- 「哪些承运商能接 FBA 入仓」—— 10.1.1.2 选渠道时的候选查询走这条。
CREATE INDEX carrier_business_scope_by_type
  ON carrier_business_scope(tenant_id, business_type);

-- ---------------------------------------------------------------------------
-- 4. 触发器：把「可判定的依据」钉在写入路径上
-- ---------------------------------------------------------------------------

-- (a) 🔴 readiness 不接受调用方指定 —— 重算一遍再比对。
--     照抄 10.3.2.1 的 guard_handover_batch_counts：服务层算一遍，DB 这里再算一遍，
--     绕过服务层直接写 SQL 把状态改成 READY 同样被拒。
--     重算只覆盖本服务看得见的输入（见文件头第三节）。
CREATE OR REPLACE FUNCTION guard_carrier_logistics_readiness() RETURNS trigger AS $$
DECLARE
  has_cov boolean;
  has_scope boolean;
  expected text;
BEGIN
  SELECT EXISTS (SELECT 1 FROM carrier_country_coverage
                  WHERE tenant_id = NEW.tenant_id AND profile_id = NEW.id),
         EXISTS (SELECT 1 FROM carrier_business_scope
                  WHERE tenant_id = NEW.tenant_id AND profile_id = NEW.id)
    INTO has_cov, has_scope;

  -- 优先级：尚未登记物流档案 > 没有凭据 > 没有覆盖国家 > 没有业务类型
  --          （见文件头第三节）
  IF NEW.carrier_category IS NULL OR NEW.service_tier IS NULL THEN
    expected := 'NOT_REGISTERED';
  ELSIF btrim(NEW.secret_ref) = '' THEN
    expected := 'NO_CREDENTIAL';
  ELSIF NOT has_cov THEN
    expected := 'NO_COVERAGE';
  ELSIF NOT has_scope THEN
    expected := 'NO_BUSINESS_SCOPE';
  ELSE
    expected := 'READY';
  END IF;

  IF NEW.readiness <> expected THEN
    -- ⚠️ 占位符一律用**裸 %**：PostgreSQL 的 RAISE 没有 C 的 %s 转换，
    --    写成 '%s' 会被当成「占位符 + 字面量 s」，消息里就会冒出「缺s」这种话。
    --    与 10.3.2.1 V129 的 RAISE 同款写法。
    RAISE EXCEPTION
      '承运商物流档案的可用性判定为 %（物流档案登记 % / 凭据引用 % / 覆盖国家 % / 业务类型 %），与提交的 % 不符 —— 可用性由这四项算出，不接受手工指定',
      expected,
      CASE WHEN NEW.carrier_category IS NULL OR NEW.service_tier IS NULL THEN '缺' ELSE '有' END,
      CASE WHEN btrim(NEW.secret_ref) = '' THEN '缺' ELSE '有' END,
      CASE WHEN has_cov THEN '有' ELSE '缺' END,
      CASE WHEN has_scope THEN '有' ELSE '缺' END,
      NEW.readiness USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_carrier_logistics_readiness_trg
  BEFORE INSERT OR UPDATE ON carrier_logistics_profile
  FOR EACH ROW EXECUTE FUNCTION guard_carrier_logistics_readiness();

-- (b) 🔴 主体编码建后不可改。
--     照抄 3.6.1.1 的 P1：编码是下游单据对齐主体的锚点。同步重跑可以把它
--     写回上游的**原值**（两边本来就一样），但不允许有人在这里改成一个新编码。
CREATE OR REPLACE FUNCTION guard_carrier_party_code_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.party_code IS DISTINCT FROM OLD.party_code THEN
    RAISE EXCEPTION
      '承运商主体编码建后不可改（% → %）：主体身份归 catalog 3.6.1.1，改编码要让 /partners 那边改，否则下游按编码对齐的单据会找不到这个主体',
      OLD.party_code, NEW.party_code USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_carrier_party_code_immutable_trg
  BEFORE UPDATE ON carrier_logistics_profile
  FOR EACH ROW EXECUTE FUNCTION guard_carrier_party_code_immutable();

-- (c) 🔴 快照列不许被「随手改」成与上游不一样的值。
--     这几条列的主权在 catalog（接口类型 / 账号 / 服务能力 / 主体状态）。
--     本服务只允许**同步**按上游读数写回，不允许业务端点把它们改成别的东西 ——
--     改了就会出现「页面上说这个承运商是 API 对接，catalog 里其实登记的是邮件往来」，
--     而可用性判定正建立在 interface_type 上，这种漂移会让判定结论变成假的。
--
--     实现方式：要求这些列**每次变更都带上同步时刻**（synced_at 前进）。
--     同步是唯一会改它们的入口，它总是写新的 synced_at；业务端点改这些列时
--     不动 synced_at，在这里被拒。
--     （用时间戳而不是「谁改的」是因为时间戳是数据里已有的真实事实，
--      不需要额外的审计表，也不需要在每个写路径上传调用方身份。）
CREATE OR REPLACE FUNCTION guard_carrier_snapshot_refresh() RETURNS trigger AS $$
BEGIN
  IF (NEW.party_name        IS DISTINCT FROM OLD.party_name
   OR NEW.party_status      IS DISTINCT FROM OLD.party_status
   OR NEW.party_usable      IS DISTINCT FROM OLD.party_usable
   OR NEW.role_status       IS DISTINCT FROM OLD.role_status
   OR NEW.interface_type    IS DISTINCT FROM OLD.interface_type
   OR NEW.account_ref       IS DISTINCT FROM OLD.account_ref
   OR NEW.service_capabilities IS DISTINCT FROM OLD.service_capabilities)
     AND NEW.synced_at IS NOT DISTINCT FROM OLD.synced_at THEN
    RAISE EXCEPTION
      '接口类型 / 账号 / 服务能力 / 主体状态的主权在 catalog 3.6.1.2，只能由同步按上游读数刷新（本次修改没有推进 synced_at）'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER guard_carrier_snapshot_refresh_trg
  BEFORE UPDATE ON carrier_logistics_profile
  FOR EACH ROW EXECUTE FUNCTION guard_carrier_snapshot_refresh();

-- (d) 🔴 子表只往存在的投影上写 —— 顺带保证「覆盖国家 / 业务类型」不会
--     挂在一个查不到出处的 role_id 上（跨租户挂载在 DB 层不可能发生：
--     复合外键带了 tenant_id）。
--     （FK 已经挡住不存在的父行与跨租户，这里补的是「父行正在被删」时的次序说明。）

-- ---------------------------------------------------------------------------
-- 5. 多租户隔离：全部业务表 tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V93 / V129 同一套写法。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'carrier_logistics_profile',
    'carrier_country_coverage',
    'carrier_business_scope' ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 6. 账号与授权（沿用 V1/V93/V129 的条件建角色，保证迁移可重复执行）
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
  carrier_logistics_profile, carrier_country_coverage, carrier_business_scope TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1 / V93 / V129 同口径）。
REVOKE ALL ON
  carrier_logistics_profile, carrier_country_coverage, carrier_business_scope FROM starcon_app;

-- ---------------------------------------------------------------------------
-- 7. 复用 identity 已注册的 channel.operation.* 权限码
-- ---------------------------------------------------------------------------
-- 本模块**不新增权限码**：承运商操作属于「平台/承运商操作」，与 10.3.2.1 交运批次、
-- 10.4.2.1 海外仓连接同族，因此 identity 服务零改动，跨车道回归面 = 0。
