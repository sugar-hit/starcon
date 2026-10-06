-- 3.3.2.1 品牌管理：品牌字典 + 多语言名 + Logo 引用 + 授权状态 + 关联类目 + 授权凭证，
-- 以及蓝图点名的业务规则「品牌需授权但无有效凭证时应阻止刊登」所需的判定数据。
--
-- ============================================================ 本迁移兑现的是 3.3.1.1 之后才成立的前提
--
-- V14 交付分类树时在结尾写了一句：「品牌字典尚未交付，关联类目无处可挂」。
-- 本迁移不再建任何类目表：品牌关联类目**直接挂 3.3.1.1 的 product_category 主键**
-- （复合外键 + ON DELETE CASCADE），与 V14 把 category_attribute_rule 改挂类目主键
-- 走的是同一条路——关联关系指向已交付模块的权威键，不复刻一份类目文本。
--
-- ============================================================ 模型决策：授权凭证独立成表，不塞进 brand 列
--
-- 候选方案与取舍：
--
--   (A) **brand_authorization 独立成表，一个品牌多条凭证（本迁移选它）**。
--       蓝图写的是「授权状态维护（可引用 16 的授权凭证）」——「凭证」是可数实体：
--       同一个代理品牌往往先签了一年期、再续签三年期，两条并存且都要看有效期。
--       塞成 brand 上的 license_no/expires_on 两列，这两件事就只能留最新的一条，
--       历史凭证一续签就丢，而 16 侵权检测恰恰要回溯「出事那天有没有授权」。
--
--   (B) 凭证放 16 合规服务、品牌只存一个外键引用：模块边界上看着更干净，
--       但 16 合规**尚未交付**（台账里 23 个 L4 全部未开发），
--       留一个指向不存在表的列就是硬编码的假引用——比缺功能更糟。
--       选 (A) 并在结尾写清「16 落地后凭证的写入所有权归 16，catalog 只读」。
--
--   (C) 用 owner_id/department_id 那套数据范围列套到凭证上：凭证是**租户级配置**，
--       与 spec_axis / unit_of_measure / product_category 同级，不该有个人数据范围。否掉。
--
-- ============================================================ 1. 品牌字典
--
-- 品牌是租户级字典（与类目树、规格轴、单位同级）：所有商品共用同一批品牌，
-- 授权状态也是租户层面的合规事实（代理合约签给的是公司不是某个人）。
CREATE TABLE brand (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 编码在租户内唯一：刊登的品牌字段与 16 侵权检测按 code 对齐这个品牌。
  code varchar(64) NOT NULL,
  -- 基础名称。任何语言下都要有一个可读名字，不允许「只配了日文、中文名为空」。
  name varchar(100) NOT NULL,
  -- Logo 引用：存的是**引用**（对象存储 key 或外部 URL），不是二进制。
  -- 3.4.1 媒体库未交付，所以这里不建媒体表也不挂外键——挂一个不存在的表等于假数据。
  -- 长度 500 与 product_asset.reference 的量级一致。
  logo_uri varchar(500),
  -- 授权状态：OWNED=自有；AGENCY=代理（已有代理协议）；LICENSED=需授权（须有有效凭证才能刊登）。
  -- 约束而不是自由文本：这三者的语义差别是**刊登是否被允许**（见第 5 节），
  -- 枚举化才能在服务层给出「需授权品牌必须先有有效凭证」这种可读判定。
  authorization_status text NOT NULL CHECK(authorization_status IN ('OWNED','AGENCY','LICENSED')),
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  CHECK(btrim(code)<>'' AND btrim(name)<>''),
  CHECK(logo_uri IS NULL OR btrim(logo_uri)<>'')
);

-- 多语言品牌名。按 (tenant_id,brand_id,locale) 建主键：
-- 同一个品牌的同一个语言只能有一条，不允许两种写法各存一份，
-- 刊登时按 locale 取名也就不会在两条候选之间随机挑一条。
CREATE TABLE brand_name (
  tenant_id uuid NOT NULL, brand_id uuid NOT NULL,
  locale varchar(16) NOT NULL,
  name varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,brand_id,locale),
  FOREIGN KEY(tenant_id,brand_id) REFERENCES brand(tenant_id,id) ON DELETE CASCADE,
  CHECK(btrim(locale)<>'' AND btrim(name)<>'')
);

-- ============================================================ 2. 授权凭证
--
-- 一个品牌多条凭证，按有效期并存（见头部 (A) 的理由）。
-- 有效期用 date 而不是 timestamptz：授权合约按**日**生效与失效，
-- 存到秒只会制造「昨天 23:59 到期、今天 00:00 就失效」这种没人能预期的边界。
-- 跨时区判定在服务层做（按 UTC 当天），见 BrandRules.validOn。
CREATE TABLE brand_authorization (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, brand_id uuid NOT NULL,
  -- 凭证编号：合约 / 授权书的业务编号，16 侵权检测出具材料时要按它回溯。
  certificate_no varchar(64) NOT NULL,
  -- 授权方（品牌权利人或授权代理商）。
  grantor varchar(150) NOT NULL,
  valid_from date NOT NULL,
  -- valid_to 为空表示长期有效（自有品牌的历史合约常见），不是「没填」。
  valid_to date,
  -- 凭证材料引用（扫描件 / 合同在对象存储里的位置）。与 logo 同性质：引用不是本体。
  evidence_uri varchar(500),
  note text NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一品牌下凭证编号唯一：重复导入同一份合约会让「有几条有效凭证」翻倍。
  UNIQUE(tenant_id,brand_id,certificate_no),
  FOREIGN KEY(tenant_id,brand_id) REFERENCES brand(tenant_id,id) ON DELETE CASCADE,
  CHECK(btrim(certificate_no)<>'' AND btrim(grantor)<>''),
  CHECK(valid_to IS NULL OR valid_to>=valid_from)
);

-- ============================================================ 3. 品牌关联类目
--
-- 指向 3.3.1.1 的 product_category 主键（复合外键带 tenant_id，跨租户引用不可能发生）。
-- ON DELETE CASCADE：类目没了，「这个品牌属于该类目」的关联也就没有意义，
-- 与 V11「删属性会级联删掉它的类目规则」同一口径。
CREATE TABLE brand_category (
  tenant_id uuid NOT NULL, brand_id uuid NOT NULL, category_id uuid NOT NULL,
  position int NOT NULL DEFAULT 0 CHECK(position>=0),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,brand_id,category_id),
  FOREIGN KEY(tenant_id,brand_id) REFERENCES brand(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,category_id) REFERENCES product_category(tenant_id,id) ON DELETE CASCADE,
  UNIQUE(tenant_id,category_id,brand_id)
);

-- ============================================================ 4. 索引
--
-- 列表页主查询：租户 + 启用状态 + 授权状态（筛选下拉的组合），按编码稳定排序。
CREATE INDEX brand_list ON brand(tenant_id,authorization_status,enabled,code);
-- 「这个类目下有哪些品牌」——04 刊登选类目后列品牌、16 侵权检测按类目圈范围都是这条。
CREATE INDEX brand_category_by_category ON brand_category(tenant_id,category_id,position,brand_id);
-- 「这个品牌有几条凭证、哪条在有效期内」——刊登判定的主查询。
CREATE INDEX brand_authorization_by_brand ON brand_authorization(tenant_id,brand_id,enabled,valid_from,valid_to);
-- 「哪些凭证快到期了」：运营巡检与概览里的临期指标。
CREATE INDEX brand_authorization_expiring ON brand_authorization(tenant_id,valid_to) WHERE valid_to IS NOT NULL;
-- 多语言名按语言取（刊登按 locale 渲染品牌名）。
CREATE INDEX brand_name_locale ON brand_name(tenant_id,locale,brand_id);

-- ============================================================ 5. RLS
--
-- 四张表都是租户级配置（与 product_category / spec_axis / unit_of_measure 同级）：
-- 只有租户隔离，可见性由端点层权限把关。数据范围（owner_id/department_id）那套
-- 是给「个人负责的业务单据」用的（V3 的 spu / product_research 等），字典表不适用。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['brand','brand_name','brand_authorization','brand_category'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON brand, brand_name, brand_authorization, brand_category TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON brand, brand_name, brand_authorization, brand_category TO starcon_app;

-- ============================================================ 6. 阻止刊登的判定为什么不写成触发器
--
-- 蓝图写死了「品牌『需授权』但无有效凭证时应阻止发布」。这是**读取时刻**的判定，
-- 不是写入时刻的约束，所以落成服务层的 BrandRules.publishBlock + BrandService 的
-- listing-eligibility 端点，而不是 brand 表上的 CHECK：
--
--   * CHECK 只能看见 brand 自己，判定要读 brand_authorization 的有效期
--     （还要按 asOf 当天求值），跨表条件在 CHECK 里写不出来；
--   * 更关键的是语义：允许「先把品牌登记成需授权、凭证稍后补」是正常业务流，
--     如果在写入时就拒，运营连建档都做不到——被拒的应该是**刊登**，不是建档。
--
-- 判定口径（BrandRules.publishBlock 是唯一实现，测试与前端提示都引它）：
--   BRAND_DISABLED         品牌已停用，任何状态都不许刊登；
--   LICENSE_MISSING         状态为 LICENSED 且一条凭证都没有；
--   LICENSE_NOT_YET_EFFECTIVE  有凭证但最早的一条还没到生效日；
--   LICENSE_EXPIRED         有凭证但全部已过期（长期有效的凭证不在此列）。
--   OWNED / AGENCY 恒可刊登——它们本身就是「已有授权」的状态。

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--
--   * **`spu.brand varchar(100)` 仍是自由文本**（V1 的列），与 V14 处理 category 时同一口径：
--     那是已交付模块（3.1.1.1 / 3.1.1.2）的写入所有权，本模块不夺走。
--     改挂 brand 主键要连带重写三个已交付模块的 Service / DTO / 契约 / 页面 / 测试夹具，
--     属于重开旧账，与 V9 / V11 / V13 / V14 反复强调的口径不符。
--     在那之前，**品牌字典的权威是本表**，`spu.brand` 文本由已交付模块继续按文本读写。
--   * **授权凭证的最终写入所有权归 16 合规**：本迁移把它放在 catalog 是因为 16 尚未交付，
--     留一个指向不存在表的列比缺功能更糟。16 落地后凭证由 16 维护，catalog 只读，
--     届时按模块边界改成授权 API / event 调用，不做跨库读写。
--   * **Logo 的二进制不归本模块**：3.4.1 媒体库负责媒体本体与图片处理，
--     本表只存引用（logo_uri），与 product_asset.reference 同一性质。
--   * **刊登侧的品牌字段校验**（04）调本模块的 listing-eligibility 端点，
--     不在这里替 04 做决定——04 的服务尚未交付。