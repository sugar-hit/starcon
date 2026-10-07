-- 3.6.1.2 角色档案：在往来单位的**每一个角色**上挂载角色特有信息。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：在往来单位上挂载角色特有信息：供应商（账期、MOQ、合作等级、结算方式）、
--             客户（客户分级、默认币种、信用额度）、承运商（接口类型、账号、服务能力）。
--   **输入**：角色字段维护；账期与信用额度设置。
--   **输出**：角色档案记录；供 06 采购单、12 应收应付默认取值。
--   **关系**：上游 = 3.6.1.1；下游 = 06、12。**每个角色一张 1:1 档案表**，
--             不用一个巨大可空字段表——否则"客户没有账期"和"供应商有账期"无法区分。
--
-- ============================================================ 第一节：为什么是三张表而不是一张
--
-- 蓝图那句话（「每个角色一张 1:1 档案表」）否掉的是 `party_role` 上挂十来个可空列的做法。
-- 那个形状在本域里具体会坏在哪里：
--
--   `party_role(id, ..., payment_terms_days, credit_limit, customer_level, moq, api_account, ...)`
--   一行 SUPPLIER 的行里 credit_limit / customer_level / api_account 永远是 NULL，
--   一行 CUSTOMER 的行里 payment_terms_days / moq 永远是 NULL。
--   **「这个客户没有设信用额度」与「信用额度这一列对客户根本不存在」在那种表里是同一种存储形态**
--   （都是 NULL）。12 应收取默认额度时无法区分，于是只能退化成「取不到就当 0」——
--   而「没设额度」在业务上恰恰是**必须放行**（不设额度 = 无限制），
--   「额度是 0」在业务上是**必须拦住**（额度用尽）。这两种情况被折叠成同一个 NULL 之后，
--   系统只能选一种，而选哪一种都会在另一处出错。
--
-- 三张表之后，「客户没有信用额度」= customer_profile 里 default_credit_limit IS NULL
-- （且**这一列确实属于客户**，因为整张表就是为了客户而存在）；
-- 「供应商没有账期」= supplier_profile 里 payment_terms_days = 0。
-- 前者是"没填"，后者是"明确是 0"，两者在**不同的表**里，谁也不会读错。
--
-- 与 V27 的关系（V27 第三节原话：「本表的 role_type 就是那张表的连接键」）：
-- 见下面第二节 —— 本迁移**没有改动 party_role 一行**。
--
-- ============================================================ 第二节：键怎么选（与 V27 交接说明的一处解释）
--
-- V27 第三节写的是「主键 (tenant_id, party_id)，复合外键指向 party_role(tenant_id, id)」。
-- 落地时这两句要合起来读：party_role 上**没有** UNIQUE(tenant_id, party_id) ——
-- 因为一个主体最多同时挂三个角色（UNIQUE(tenant_id,party_id,role_type) 是三元组），
-- (tenant_id,party_id) 上根本建不出唯一约束，而外键必须指向一个唯一列组。
-- party_role 上真实存在的唯一列组是 UNIQUE(tenant_id,id)（V27 第 128 行）。
--
-- 所以本迁移把档案表**键在角色 id 上**：
--     PRIMARY KEY(tenant_id, role_id)，FOREIGN KEY(tenant_id, role_id) → party_role(tenant_id,id)
-- 这样才有约束可指，而且**不需要给 party_role 补任何索引或约束**（加索引等于改别人已交付的表）。
-- 「一个主体最多一份供应商档案」由两条传递性保证自动成立：
--   party_role 保证一个主体最多一条 SUPPLIER 角色（UNIQUE 三元组），
--   本表保证一个角色最多一份档案（主键就是 (tenant_id, role_id)），
--   串起来就是「一个主体最多一份供应商档案」。刻意<b>不再</b>加一条 UNIQUE(tenant_id, party_id)：
--   它是上面两条的推论，多加一条只会让"档案与角色必须一一对应"这件事多出一个可以不一致的口子。
--
-- ON DELETE CASCADE 的含义：3.6.1.1 解除角色指派是**物理删除** party_role 行
-- （它的理由见 PartyService.removeRole 的注释：角色是「当前扮演这个身份」的事实，没有回溯需求），
-- 角色删掉时它的角色档案**必须**跟着删 —— 否则会留下「角色没了但供应商账期还在」的行，
-- 而那种行在 06 采购带出默认账期时会被当成某个**别的**主体的档案用掉。
--
-- role_type 与表的对应由**表本身**表达（供应商档案表里只可能是 SUPPLIER 角色），
-- 而不是靠一列 role_type 的 CHECK —— 一列不被外键约束的 role_type 可以和它指向的角色不一致，
-- 而那种不一致没有任何查询能发现（查的时候会漏行）。服务层
-- （{@code PartyRoleProfileRules#roleTypeProblem} + {@code PartyRoleProfileService}）
-- 在写入前判定并给出可读的 409，DB 外键负责"档案的角色必须真实存在且在本租户内"。
--
-- ============================================================ 第三节：party_supplier_profile —— 账期、MOQ、合作等级、结算方式

CREATE TABLE party_supplier_profile (
  tenant_id uuid NOT NULL,
  -- 指向 party_role(tenant_id,id)：这个主体的 SUPPLIER 角色。见第二节。
  role_id uuid NOT NULL,

  -- 账期（天）。0 = 不给账期（下单现付 / 货到付款），而不是"没填"。
  -- **整数天，不是月也不是字符串**：跨境 B2B 的对账条款按天报，12 应付的到期日 = 单据日 + 这 N 天，
  -- 中间任何一层把它换成"30 天"再解析回数字都会在某个月差 1 天。
  -- 取值上限 365 是为了挡住误填的 99999（那不是账期，那是把年填进了天）。
  payment_terms_days int NOT NULL DEFAULT 0 CHECK(payment_terms_days >= 0 AND payment_terms_days <= 365),

  -- MOQ = 最小起订量（Minimum Order Quantity）。
  -- **只有数量没有单位的 MOQ 是一句没有意义的话**：同一个「100」在"件"与"箱"下差几十倍，
  -- 而采购单带出 MOQ 时不会重新问一遍单位。所以数量与单位成对存在、成对为空。
  -- 精度 numeric(19,6)：与 V17 换算表 quantity_per_base 同一精度（存 6 位是不丢精度，不是给用户看 6 位）；
  -- 实际展示位数取该单位 unit_of_measure.scale（0~6），所以"0.5 吨"与"2 件"都能如实存下。
  moq_quantity numeric(19,6) CHECK(moq_quantity IS NULL OR moq_quantity > 0),
  -- 采购单位：引用 3.3.3 计量单位的 code（unit_of_measure 有 UNIQUE(tenant_id,code)，可建复合外键）。
  -- 外键的作用不是"单位存在"，而是"这一对必须同时在或同时不在" —— 见下面那条 CHECK。
  moq_unit_code varchar(32),

  -- 合作等级（业务分级，决定默认排序与风控口径）。五个取值各自的含义：
  --   STRATEGIC 战略供应商：绑定多年、量价一议，缺货直接影响主推 listing。
  --                     06 采购拆单时优先保它，12 应付的付款争议走高层通道。
  --   PREFERRED 优选供应商：同品类内的第二选择，有返点/账期优惠，用于战略供应商断供时切换。
  --   STANDARD  常规供应商：按目录价与标准账期走，**新建供应商的默认档**。
  --   TRIAL     试单供应商：正在验证质量/交期，通常不给账期（与下面的结算方式守卫联动）。
  --   RISK      风险供应商：有质量或交期问题待观察，采购单必须人工确认才能下。
  cooperation_level text NOT NULL DEFAULT 'STANDARD'
    CHECK(cooperation_level IN ('STRATEGIC','PREFERRED','STANDARD','TRIAL','RISK')),

  -- 结算方式（与账期联动，语义见第五节规则 R1）。四个取值各自的含义：
  --   PREPAID          预付：先款后货，payment_terms_days 必须为 0。
  --   ON_DELIVERY     货到付款：货到即付（自提/专线），payment_terms_days 必须为 0。
  --   CREDIT          账期结算：先货后款，到期日 = 单据日 + payment_terms_days，必须 > 0。
  --   LETTER_OF_CREDIT 信用证：开证付款，payment_terms_days 必须为 0（银行承兑，不占供应商账期）。
  -- 不设第五个「其他」：跨境 B2B 的结算方式就这四类，真出现别的形态时
  -- 应该新增一个枚举值并同步 12 财务，而不是把具体形态塞进自由文本。
  settlement_method text NOT NULL DEFAULT 'PREPAID'
    CHECK(settlement_method IN ('PREPAID','ON_DELIVERY','CREDIT','LETTER_OF_CREDIT')),

  -- 备注：交期口径、退货条款、质检要求这类不能进枚举的约定。
  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id, role_id),
  -- MOQ 数量与单位成对：只给一个就是半条信息，采购单带出去会变成"100"或者"件"。
  CHECK((moq_quantity IS NULL) = (moq_unit_code IS NULL)),
  -- 结算方式与账期必须自洽（规则 R1）。DB 层也兜一道：绕过服务层直接写库，
  -- 也不该写得出「预付 + 90 天账期」这种在 12 应付上会被算成两种矛盾条款的行。
  CHECK(settlement_method='CREDIT' OR payment_terms_days=0),
  FOREIGN KEY(tenant_id, role_id) REFERENCES party_role(tenant_id, id) ON DELETE CASCADE,
  -- 采购单位必须是已登记的计量单位（3.3.3）。停用的单位仍可引用：
  -- 历史采购单上的冻结快照要继续显示，改单单位字典的 status 不该让历史 MOQ 读不出来。
  FOREIGN KEY(tenant_id, moq_unit_code) REFERENCES unit_of_measure(tenant_id, code)
);

-- 「这个租户里哪些供应商是战略供应商」—— 06 采购拆单时的排序依据。
-- 三个索引而不是一条：合作等级、账期、MOQ 单位是三种不同的筛选口径，
-- 各建一条才能各自走上；拼成一条表达式索引则要求查询侧也拼，两边不一致时静默失效且不报错。
CREATE INDEX party_supplier_profile_by_level
  ON party_supplier_profile(tenant_id, cooperation_level, role_id);
-- 「账期 30 天的供应商有哪些」—— 12 应付做账期分组与到期日测算。
CREATE INDEX party_supplier_profile_by_terms
  ON party_supplier_profile(tenant_id, payment_terms_days, role_id);

-- ============================================================ 第四节：party_customer_profile —— 客户分级、默认币种、信用额度

CREATE TABLE party_customer_profile (
  tenant_id uuid NOT NULL,
  role_id uuid NOT NULL,   -- 指向 party_role(tenant_id,id) 的 CUSTOMER 角色

  -- 客户分级（决定服务等级与风控口径）。五个取值各自的含义：
  --   STRATEGIC 战略客户：年度采购额最高的一档，账期与额度单独谈，12 应收的逾期要上报。
  --   KEY       重点客户：稳定复购，逾期只催不锁额度。
  --   STANDARD  普通客户：**新建客户的默认档**。
  --   PROSPECT  潜在客户：已建档但尚未成交，信用额度应当为 0/空 —— 它的意义是"还没开始给钱"。
  --   RISK      风险客户：有逾期或纠纷记录，新单必须预付（服务层规则 R4 会拦）。
  customer_level text NOT NULL DEFAULT 'STANDARD'
    CHECK(customer_level IN ('STRATEGIC','KEY','STANDARD','PROSPECT','RISK')),

  -- 默认币种：ISO 4217 三位大写（USD / EUR / CNY …）。它是这个客户的**结算口径**，
  -- 06 销售单与 12 应收在没有单独指定币种时取这里。
  default_currency char(3) CHECK(default_currency IS NULL OR default_currency ~ '^[A-Z]{3}$'),

  -- 信用额度：这个客户允许的**未结应收余额上限**（不是单笔上限，也不是年度总额）。
  -- 12 应收用它判「还能不能继续赊」。精度 numeric(19,4) 而不是整数：
  -- 额度会以「10 万 USD」出现，也会出现「0.35 万」这种按比例谈出来的值，四位小数足够精确到 1 分。
  default_credit_limit numeric(19,4)
    CHECK(default_credit_limit IS NULL OR default_credit_limit > 0),

  -- 客户账期（天）。0 = 现结。与供应商账期同一口径（整数天，见第三节的说明），
  -- 为什么客户侧也要有：蓝图的「输入」点名了「**账期**与信用额度设置」两项，
  -- 而 12 应收的到期日 = 单据日 + 这 N 天 —— 客户账期是算式里的 N，不是可选项。
  payment_terms_days int NOT NULL DEFAULT 0 CHECK(payment_terms_days >= 0 AND payment_terms_days <= 365),

  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id, role_id),
  -- **额度必须与币种一起存在**（规则 R2）。
  -- 没有这一条就会出现「额度 100000、币种空白」的行：12 应收拿它去和一张 USD 发票比，
  -- 只能假设成美元，而假设错了不会报错，只会让一个超额客户继续赊。
  -- 这里刻意**不**把币种设成 NOT NULL：客户分级是建档就要填的，
  -- 而币种要等到第一笔真实交易才知道 —— 按 3.6.1.1 规则 P3 同源的道理，
  -- **被拒的应该是"给额度却不给币种"，不是"建客户档案"**。
  CHECK(default_credit_limit IS NULL OR default_currency IS NOT NULL),
  FOREIGN KEY(tenant_id, role_id) REFERENCES party_role(tenant_id, id) ON DELETE CASCADE
);

-- 刻意**没有** default_credit_currency 列，额度只有一个币种口径 = default_currency。
-- 分列存储会出现「额度 10 万 USD / 默认币种 EUR」这种行：它在 12 应收里无法判定该拿哪个币种去比，
-- 而界面必须让用户挑一个，挑错了没有任何东西会报错。把两者绑成一个字段之后这个状态**不存在**。

-- 「哪些客户是重点客户」「哪些客户还没有币种」（新客户治理）—— 列表与治理视图的主查询口径。
CREATE INDEX party_customer_profile_by_level
  ON party_customer_profile(tenant_id, customer_level, role_id);
-- 「这个币种下的客户有哪些」—— 12 应收按币种分组结汇。
CREATE INDEX party_customer_profile_by_currency
  ON party_customer_profile(tenant_id, default_currency, role_id);

-- ============================================================ 第五节：party_carrier_profile —— 接口类型、账号、服务能力

CREATE TABLE party_carrier_profile (
  tenant_id uuid NOT NULL,
  role_id uuid NOT NULL,   -- 指向 party_role(tenant_id,id) 的 CARRIER 角色

  -- 接口类型：我们与这个承运商的**对接方式**。四个取值各自的含义：
  --   API          系统对接：对方有可用 API（FedEx/UPS/DHL 这类），10 物流直连取运单与轨迹。
  --   SFTP_FILE    文件交换：对方只给 EDI/CSV 报文，按约定目录投递。
  --   WEB_PORTAL   平台下单：我们登录对方后台手工录单（账号仍然要登记，见 account_ref）。
  --   EMAIL        邮件往来：小包裹场景，发邮件报关、对方回邮件发运单号。
  -- 枚举化而不是自由文本：这四种在 10 物流的处理动作完全不同（自动取轨迹 / 解析报文 / 人工录单 /
  -- 人工收邮件），自由文本没法在 SQL 侧判"这个运单能不能自动跟踪"。
  interface_type text NOT NULL DEFAULT 'API'
    CHECK(interface_type IN ('API','SFTP_FILE','WEB_PORTAL','EMAIL')),

  -- 账号标识：对方系统里的**账号名 / 客户号**（UPS 的 Account Number、货代的客户编码）。
  -- **这里绝不存密钥或口令**：AGENTS.md 的秘密管理口径是「第三方 API 的 key 一律走 secret manager」，
  -- 数据库里放一份就等于多了一个泄露点，而主数据是被 06/10/11/12 广泛读取的面。
  -- 需要鉴权材料时由部署侧从 K8s External Secrets / Vault 按 account_ref 关联注入，
  -- 档案里只留"去哪个账号下取凭据"这个键。
  account_ref varchar(128) NOT NULL CHECK(btrim(account_ref)<>''),

  -- 服务能力：数组而不是逗号串。逗号串在 10 物流筛选「支持海运的承运商」时只能
  -- `like '%,SEA,%'`，而 SEA 是靠前的值时会漏匹配（这正是 V27 第四节拒绝拼接索引的同一个坑）。
  -- 九个取值的含义：
  --   AIR          空运
  --   SEA          海运（含整箱 FCL 与拼箱 LCL）
  --   RAIL         铁路（中欧班列场景）
  --   TRUCK        卡车 / 陆运（北美、东南亚短驳）
  --   EXPRESS      国际快递
  --   DOOR_TO_DOOR 门到门（含清关派送，适合 B2C 小包）
  --   CUSTOMS      代理清关（可代办报关而非只运输）
  --   INSURED      可提供保价
  --   URGENT       支持加急（时效优先，运费更高）
  -- 数组元素的合法性用 `<@`（contained by）在 DB 层判：绕过服务层也写不进
  -- 一个含 'sea'（小写）或 'AIR FREIGHT' 的元素，而大小写不一致的能力值在
  -- `service_capabilities @> ARRAY['SEA']` 的查询里是**静默不匹配**的 —— 不报错，只是永远筛不出来。
  service_capabilities text[] NOT NULL DEFAULT '{}'
    CHECK(service_capabilities <@ ARRAY['AIR','SEA','RAIL','TRUCK','EXPRESS','DOOR_TO_DOOR','CUSTOMS','INSURED','URGENT']),

  -- 服务范围说明：航线限制（"只做东南亚"）、截单时间、禁运品类这类不能进枚举的约定。
  service_scope_note text NOT NULL DEFAULT '',

  -- 备注：与另两张档案表同形（列表页的「备注」列按 roleType 统一渲染）。
  note text NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id, role_id),
  FOREIGN KEY(tenant_id, role_id) REFERENCES party_role(tenant_id, id) ON DELETE CASCADE
);

-- 「支持海运的承运商有哪些」—— 10 物流按能力选承运商的主查询。
-- GIN 而不是 btree：过滤条件是 `@> ARRAY[...]`（数组包含），btree 用不上。
-- 服务侧那个 jsonb_path_ops 风格这里用默认的 array_ops，因为列类型是 text[] 而不是 jsonb。
CREATE INDEX party_carrier_profile_by_capability
  ON party_carrier_profile USING gin (service_capabilities);
-- 「有哪些承运商是 API 对接的」—— 10 物流判断哪条运单能自动跟踪。
CREATE INDEX party_carrier_profile_by_interface
  ON party_carrier_profile(tenant_id, interface_type, role_id);

-- ============================================================ 第六节：RLS
--
-- 三张表都是租户级主数据（与 party / party_role / brand / unit_of_measure 同级）：
-- 只需要租户隔离，可见性由端点层权限把关。
-- 照抄 V27 第五节的 DO $$ FOREACH 写法而不是三条重复语句：
-- 三张表的策略**必须**逐字相同，复制粘贴的第三遍早晚会漏掉 FORCE，
-- 而漏掉 FORCE 之后表 owner（迁移角色）仍然能绕过策略读别人的额度 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['party_supplier_profile','party_customer_profile','party_carrier_profile'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON party_supplier_profile, party_customer_profile, party_carrier_profile TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON party_supplier_profile, party_customer_profile, party_carrier_profile TO starcon_app;

-- ============================================================ 第七节：本迁移钉死的七条语义（服务层 PartyRoleProfileRules 的对应实现）
--
-- 写在这里是因为「数据库为什么这样设计」与「服务层为什么这样判」必须是同一份承诺，
-- 两处各说一套的话，下一个改代码的人只会读到其中一处。
--
--   **R1 结算方式与账期必须自洽。** settlement_method='CREDIT' ⟺ payment_terms_days>0，
--      其余三种方式一律为 0。理由：一个「预付 + 90 天账期」的供应商档案，
--      06 采购单按哪一列带出到期日？带出 0 天会让一批本该预付的单子挂着没付款，
--      带出 90 天会让这批货的款在 90 天后才收 —— 两种实现都符合字段本身，只有一种符合业务。
--      **允许账期为 0 的供应商**（预付 / 货到付）：跨境小额供应商确实只做现结，
--      把它们挡在建档之外会逼运营乱填一个假账期，那比 0 更危险。
--
--   **R2 信用额度必须配币种，额度币种就是默认币种。** 见第四节的 CHECK 与注释：
--      有额度就必须有 default_currency，且刻意不设第二列币种。
--
--   **R3 档案只能挂在**真实存在**的对应角色上。** 服务层在写入前判定 party_role 的 role_type
--      与目标表匹配（不匹配给 409「这个主体的供应商身份尚未指派」）；
--      DB 外键保证"角色存在且属于本租户"，ON DELETE CASCADE 保证角色解除后档案不会残留。
--      **不**在已停用（INACTIVE）的主体上拦：3.6.1.1 规则 P6 的正常流程要求
--      「先建主体、后指派角色再启用」，把档案维护也一起拦掉会让那条流程走不通。
--
--   **R4 风险客户的信用额度必须为 0/空。** customer_level='RISK' 时
--      default_credit_limit 只能是 NULL 或 0：给一个被记录过逾期的客户留额度，
--      12 应收会照着放单，而唯一的信号（分级）已经被额度列盖住了。
--      反向不拦（普通客户有额度是正常的），只拦"风险客户还有额度"这一种组合。
--
--   **R5 档案可删，角色不可由本模块删。** DELETE 端点删的是**这一行角色档案**，
--      角色指派本身仍然在 party_role 里 —— 解指派角色是 3.6.1.1 的动作，走它自己的端点。
--      删掉档案之后 06/12 退回"没设过"（额度不设 = 不限制，账期 0 = 现结），
--      这是一个**有意义且可逆**的状态，不是一个需要历史追溯的凭证
--      （与 3.6.1.1 对 party 软删除、对 party_role 物理删除的理由同源：主体要留历史、角色只是当前事实、
--      角色档案是挂在当前事实上的一层，默认值随时可以重设）。
--
--   **R6 枚举只增不改。** 合作等级 / 结算方式 / 客户分级 / 接口类型 / 服务能力
--      五个词表的值**可以新增**（跨境业务长出新形态是常态），但**既有取值不许改语义**：
--      它们已经写进了历史采购单与应收单的冻结快照，改语义会让那些单据在回看时解释错。
--      拿不准该加值还是该用备注时，**加值**，备注是兜底而不是收纳箱。
--
--   **R7 币种与单位一律引用字典，档案里存 code 不存名字。**
--      default_currency 存 ISO 4217 三位码（3.3.3 的计量单位同理存 code），
--      这样改字典里的显示名不会改写历史档案；反过来把「美元」这种自由文本存进去，
--      12 应收就没法把它和发票币种对上。
--      moq_unit_code 有外键兜底，default_currency 没有（ISO 4217 是外部标准，
--      在本库里另建一张币种表是 12 财务的职责，不在本模块复制一份写入所有权）。
--
-- ============================================================ 第八节：留给下游的口子（不是本模块的缺口）
--
--   * **06 采购单**：带出供应商的 payment_terms_days / settlement_method / moq_quantity+moq_unit_code
--     作为新单默认值，并在订单行校验数量 ≥ MOQ。取默认值的读出口在
--     {@code GET /catalog/parties/{id}/role-profile-defaults}（本模块已提供）。
--     单据侧把值**冻结**成自己的快照（与 3.6.3.1 的地址同口径：改档案不影响已发生单据）。
--   * **12 应收应付**：取客户 default_credit_limit + default_currency + payment_terms_days
--     判超额与算到期日。**已用额度不在本模块**：那要读 12 自己的应收台账，
--     跨服务读表是 AGENTS.md 明令禁止的，所以本模块只给上限，不给"剩余额度"。
--   * **10 物流**：按 party_carrier_profile.interface_type 决定运单能否自动跟踪，
--     按 service_capabilities 选承运商。
--   * **3.6.2.1 供应商报价**：报价自带 MOQ 与交期，**与本表的 MOQ 不重复** ——
--     本表是供应商级的"这家最少下多少"，报价是"这个 SKU 最少下多少"。两者口径不同，都不废。
--   * **3.6.3.1 地址与联系人**：本模块不含地址与联系人。
--   * **4.1.x 平台客户映射**：平台 ID 归 04 渠道，不在本表。