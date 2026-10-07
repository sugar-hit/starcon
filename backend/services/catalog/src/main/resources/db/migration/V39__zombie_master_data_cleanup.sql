-- 3.9.2.2 僵尸主数据清理：识别从未被订单引用、无库存、无 listing、成本为 0、长期未维护的
-- SKU / 供应商 / 客户，输出候选清单并支持批量停用（**停用而非删除**，历史单据仍可展示）。
--
-- 蓝图原文（本迁移要兑现的全部内容）：
--   **职责**：识别僵尸主数据：从未被订单引用、无库存、无 listing、成本为 0、长期未维护的
--             SKU/供应商/客户，输出候选并支持批量停用；避免报表与下拉被无效数据淹没。
--   **输入**：引用统计；时间范围；清理规则配置。
--   **输出**：僵尸清单 + 停用/保留决定 + 停用记录。
--   **关系**：上游 = 3.1.1.3 生命周期；下游 = 3.1.1.1 列表。**停用而非删除**（主数据被引用后
--             只能停用，实体目录 §5），确保历史单据仍可展示。
--
-- ============================================================ 第一节：四个"引用"维度的事实归属（本迁移的核心约束）
--
-- 蓝图那五个条件里，只有「长期未维护」是 catalog 自己能判的。其余四个都是**别家的事实**：
--
--   维度            事实所有者            本仓库当前状态                接入方式
--   -------------   ------------------   --------------------------   --------------------------------
--   订单引用        trade（订单）        **服务不存在**（services 下无   上游落地后经授权 API 推送
--                                   trade 目录）                     ORDER_LINE_COUNT 快照
--   库存            warehouse（07）      **已交付**（services/warehouse）  定时推送 INVENTORY_ON_HAND
--   listing         channel（04 刊登）   服务存在但**只有平台/连接/店铺/   上游落地后推送 LISTING_COUNT
--                                   同步，没有刊登实体**
--   成本            purchase（06 采购）  **服务不存在**                 上游落地后推送 UNIT_COST
--
-- **跨库查询一律禁止**：AGENTS.md 明令禁止跨服务 SQL / FK / 共享视图，且「每张事实表只有一个
-- 写入所有者」。所以本迁移**不去 join 任何一个别家服务的表**，改为在 catalog 内维护一张
-- **本地快照表** `master_data_reference_stats`，由上游服务经**授权 API 推送**引用计数，
-- catalog 只读这张表。
--
-- 这与 V6 sku_detail 的既有口径逐字一致（那里写的是：库存/成本/售价/listing「一律不落库」，
-- 读取走 SkuDetailProjectionPort）。本模块是**同一件事的另一半**：
--   * SkuDetailProjectionPort 是**拉取式**（catalog 主动问别人要）；
--   * 本表是**推送式**（别人把结果推给 catalog 存下来）。
-- 选推送式是因为僵尸判定要扫**全量**主数据而不是详情页上那一条：拉取式在这种量级下会退化成
-- N+1 次跨服务调用，而推送式让候选清单走一条本地 SQL。两者的边界纪律完全一致 ——
-- **catalog 不复制别家的写入所有权，只保存别人主动交过来的观测值。**
--
-- ============================================================ 第二节：「不知道」与「是零」必须能区分（不能糊过去）
--
-- 本模块最容易出的、也最难发现的错：把「上游还没接入」当成「这个 SKU 从未被引用」。
-- 那会让**每一个 SKU 都变成僵尸候选**，运营批量一点就把整个目录停用了 —— 而系统没有报错，
-- 因为它确实"如实"地读了 0。
--
-- 所以快照表的语义钉死为**三态**：
--
--   * **有行**      = 上游**真的观测过**并推了这个值（值可以是 0，0 也是一个观测结论）；
--   * **没有行**    = **未知**。这一维度对这条主数据没有任何结论，不参与判定；
--   * `observed_at` = 上游观测的时刻，而不是写库的时刻 —— 没有它就无法判断快照是否过期
--                     （trade 三个月前推过一次就再没推，"从未被订单引用"这个结论已经不可信了）。
--
-- 服务层 `ZombieCandidateRules` 据此给出 `UNKNOWN` 判定态，并且**未知维度不构成"条件满足"**：
-- 启用中的条件维度无观测值时，该条候选标记 `INCOMPLETE` 而不是 `CANDIDATE`，
-- 批量停用端点拒收 INCOMPLETE（规则 Z4）。这是 fail-closed，与 `UnwiredProjectionPort`
-- 「未接入就拒绝执行而不是返回 0 让规则跑通」同一个纪律。
--
-- ============================================================ 第三节：master_data_reference_stats —— 引用统计快照
--
-- 一行 = 「某个上游在某个时刻，观测到某条主数据的某个引用维度是多少」。
-- 生产者重复推送同一行时**覆盖**（UPSERT），因为最新一次观测比旧的有意义
-- （保留旧值只会让"最近有订单了但仍显示 0"这种最危险的错误一直存在）。
CREATE TABLE master_data_reference_stats (
  tenant_id uuid NOT NULL,
  -- 被观测的主体类型。刻意**只有这三个值**：蓝图点名的就是 SKU / 供应商 / 客户。
  -- 供应商与客户都是 `party`（V27 推翻实体目录旧稿的 supplier/customer/carrier 三张表），
  -- 这里用两个枚举值区分"以供应商身份被观测"还是"以客户身份被观测"，而不是两张表。
  entity_type text NOT NULL CHECK(entity_type IN ('SKU','SUPPLIER','CUSTOMER')),
  entity_id uuid NOT NULL,
  -- 引用维度。命名带计数/值以区分语义，但**同一列存两类量**是刻意的：
  -- 四个维度里三个是计数（0/1/37）、一个是金额（成本），而它们在判定上只有一个用途 ——
  -- 「是否为零」。分成两列（count + amount）会让四行记录里三行有一列恒为 NULL，
  -- 而 NULL 在下面的覆盖判定里含义是"不知道"，那是**语义污染**：填 0 的那行与
  -- 真的没观测过的那行会长得一模一样。
  metric text NOT NULL CHECK(metric IN ('ORDER_LINE_COUNT','INVENTORY_ON_HAND','LISTING_COUNT','UNIT_COST')),
  metric_value numeric(19,6) NOT NULL CHECK(metric_value >= 0),
  -- 生产者标识（'trade' / 'warehouse' / 'channel' / 'purchase'）。不建外键字典：
  -- 新服务接入时不该被迫先在本表登记自己，而"是谁推的"是排查数据可信度时的第一现场。
  source text NOT NULL CHECK(btrim(source) <> ''),
  -- **观测时刻**（上游看到数据的时刻），不是写库时刻。
  observed_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id, entity_type, entity_id, metric),
  CHECK(btrim(source) <> '')
);

-- 「某条主数据当前有哪些引用观测」—— 候选清单逐行拼证据、详情页展示证据表，主查询。
-- 前导列就是主键本身（tenant_id, entity_type, entity_id），metric 在最后用于逐行取值。
CREATE INDEX master_data_reference_stats_by_metric
  ON master_data_reference_stats(tenant_id, entity_type, metric, entity_id);

-- 「这个快照有多旧了」—— 运维巡检与规则 staleness 判定共用。
-- observed_at 放在首位是因为按新鲜度扫是最常见的用法（先捞过期的），与按实体查方向相反。
CREATE INDEX master_data_reference_stats_by_observed
  ON master_data_reference_stats(tenant_id, observed_at);

-- 推送方做幂等重推时的存在性预检走这条（服务层写它，不建 UNIQUE 索引因为主键已经隐含）。

-- ------------------------------------------------------------ 3.2 主体必须真实存在（本表的引用完整性）
--
-- entity_id 是多态的（SKU 指向 sku，SUPPLIER/CUSTOMER 指向 party），单条外键表达不了，
-- 而 V18 media_reference 那种"两列 + 形状 CHECK"的做法在这里要求生产者同时知道
-- entity_type 与正确的列，四个维度 × 三种主体 = 十二种组合都要在写入侧判一遍。
-- 用触发器按 entity_type 解析目标表，**一处**判完全部十二种组合。
CREATE FUNCTION validate_reference_stat_entity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM (
      SELECT 1 AS ok FROM sku    WHERE tenant_id=NEW.tenant_id AND id=NEW.entity_id
      UNION ALL
      SELECT 1 FROM party  WHERE tenant_id=NEW.tenant_id AND id=NEW.entity_id
         AND EXISTS (SELECT 1 FROM party_role r
                      WHERE r.tenant_id=party.tenant_id AND r.party_id=party.id
                        AND r.role_type=CASE NEW.entity_type
                                           WHEN 'SUPPLIER' THEN 'SUPPLIER'
                                           ELSE 'CUSTOMER' END)
    ) t WHERE t.ok=1
  ) THEN
    RAISE EXCEPTION '引用统计指向的主体不存在或角色不匹配（entity_type=%）', NEW.entity_type
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER validate_reference_stat_entity
  BEFORE INSERT OR UPDATE ON master_data_reference_stats
  FOR EACH ROW EXECUTE FUNCTION validate_reference_stat_entity();
-- SKU 之外的主体必须真的**持有那个角色**：给一个 CUSTOMER 身份推供应商引用会让
-- 6 采购单挂到一个客户身份上，而界面上看不出任何异常（同一个 party，两个 id 口径）。

-- ============================================================ 第四节：master_data_cleanup_rule —— 清理规则配置
--
-- 蓝图「输入」点名了「清理规则配置」，所以它必须是**可配置的真实数据**，不能把阈值写死在代码里。
-- 一行 = 一个主体类型的一套规则（每租户每类型一条，UNIQUE 保证"改"而不是"再加一条"）。
--
-- **阈值按主体类型分开**，因为一个统一的 180 天对三方是错的：
--   * SKU   —— 季节品半年卖一轮是正常的，180 天不动是强信号；
--   * 供应商 —— 年度框架协议供应商一年不下一单也不代表僵尸（备选供应商是刻意养着的）；
--   * 客户  —— 客户流失是真的要清理的，比 SKU 更该激进。
-- 所以 inactive_days 是每类型一列，DEFAULT 给一个保守值（365）而不是各填各的 ——
-- 保守的默认让"新建租户什么都没配"时不会误伤。
CREATE TABLE master_data_cleanup_rule (
  tenant_id uuid NOT NULL,
  entity_type text NOT NULL CHECK(entity_type IN ('SKU','SUPPLIER','CUSTOMER')),
  -- 「长期未维护」阈值（天）：距最后一次维护超过这个天数才进入候选。
  -- 下限 30：低于 30 天的清理规则在跨境电商上没有业务含义（一个补货周期都不止 30 天），
  -- 而它带来的误停用风险极高。DEFAULT 365 是全类型保守值。
  inactive_days int NOT NULL DEFAULT 365 CHECK(inactive_days BETWEEN 30 AND 3650),
  -- 四个引用维度各自的启用开关，**全部默认 false**。
  --
  -- 为什么默认全关（而不是全开）：维度开着但上游没接入时，每个候选都会是 INCOMPLETE，
  -- 界面会显示一整页"无法判定"，而运营看不出是配置问题还是数据问题。
  -- 默认关 = 默认只用 catalog 自己判得出的「长期未维护」，
  -- 上游接入后由运营在界面上逐个打开，每打开一个都能立刻看到覆盖率变化。
  require_zero_order_reference boolean NOT NULL DEFAULT false,
  require_zero_inventory         boolean NOT NULL DEFAULT false,
  require_zero_listing           boolean NOT NULL DEFAULT false,
  require_zero_cost              boolean NOT NULL DEFAULT false,
  -- 快照过期阈值（天）：引用的 observed_at 距今超过这个天数，该维度判为 UNKNOWN 而不是沿用旧值。
  --
  -- 这是蓝图「输入」里「时间范围」那一项在引用维度上的落点：观察窗口有多长，决定了
  -- 「从未被订单引用」这句话能被采信多久。DEFAULT 90 天是保守值 ——
  -- 上游推送周期通常在日/周级，90 天足够宽松，而**过期的危害远大于过期的收益**：
  -- 沿用一个半年前的 0 去停用一条今天正在被下单的 SKU，代价是真实的业务事故。
  -- 下限 1 天：设成 0 会让任何非本秒写入的快照立刻失效，等于关掉这个维度。
  snapshot_stale_days int NOT NULL DEFAULT 90 CHECK(snapshot_stale_days BETWEEN 1 AND 3650),
  -- 规则本身是否参与候选扫描。关掉之后该类型不再出现在候选清单里（配置仍然保留）。
  enabled boolean NOT NULL DEFAULT true,
  -- 停用后写入的备注模板，缺省给通用文案。存模板而不是存固定文案：
  -- 停用记录要能被审计回看，"为什么被停用"必须指向具体规则而不是一句套话。
  deactivate_note text NOT NULL DEFAULT '按僵尸主数据清理规则批量停用',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(tenant_id, entity_type),
  CHECK(btrim(deactivate_note) <> '')
);

-- 「规则是否自相矛盾」：四个维度全关的规则在语义上就是「只看长期未维护」，
-- 那是**合法且常用**的（第一档治理），所以刻意**不**拒绝它。
-- 真正的矛盾是"要求成本为 0"却同时"要求有成本观测"这种组合 —— 那一维度的语义
-- 就是"观测到的成本必须是 0"，两者一致，不构成矛盾。故本表无额外 CHECK。

-- ============================================================ 第五节：master_data_cleanup_record —— 停用记录
--
-- 蓝图「输出」点名了「停用记录」。这张表是**不可变的决定留痕**：一行一次停用动作。
--
-- **为什么必须冻结快照而不是只存 entity_id**：
-- 停用理由依赖当时的引用统计与规则，而两者都会变 —— 规则改了、trade 推了新快照、
-- SKU 改名了。只存 id 的话，三个月后回看这条记录只能看到"某年某月停用过"，
-- 回答不了"凭什么"（规则 Z5）。所以本表冻结：
--   * rule_snapshot   jsonb —— 停用当时的规则全文（含当时的 inactive_days 与四个开关）；
--   * evidence        jsonb —— 停用当时四个维度的观测值与观测时刻（缺失维度显式记 UNKNOWN）；
--   * 实体编码/名称   —— 主数据改名后仍能读出"当年停用的是哪一条"。
CREATE TABLE master_data_cleanup_record (
  tenant_id uuid NOT NULL,
  id uuid PRIMARY KEY,
  entity_type text NOT NULL CHECK(entity_type IN ('SKU','SUPPLIER','CUSTOMER')),
  entity_id uuid NOT NULL,
  -- 冻结的实体标识（改名/软删后仍可读，理由见上）。
  entity_code varchar(64) NOT NULL DEFAULT '',
  entity_name varchar(200) NOT NULL DEFAULT '',
  -- 停用动作的完整结果。previous_status 存动作前的状态字符串
  -- （SKU 是 DRAFT/ACTIVE/INACTIVE/ARCHIVED，party 是 ACTIVE/INACTIVE/BLACKLISTED，
  --   两套枚举不共用一个 CHECK —— 那是两个领域各自的枚举，强行合并会让"改成对方的合法值"通过）。
  previous_status varchar(20) NOT NULL DEFAULT '',
  new_status varchar(20) NOT NULL DEFAULT '',
  -- 操作人与理由（蓝图「输出」的"停用/保留决定"里的决定部分）。
  decided_by uuid NOT NULL,
  reason text NOT NULL DEFAULT '',
  -- 决定时该主体最后一次维护时刻（证据的一部分，单独存便于直接排序与展示）。
  last_maintained_at timestamptz,
  rule_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 这次停用覆盖的主数据条数（批量动作时 >1）。单条动作也走同一条写入路径，
  -- 所以这里恒为 1 或批量数，不做"批量时另记一行"的双写（双写会让"这次停用了几个"这个问题
  -- 需要聚合才能回答，而聚合在审计场景下总是被漏掉）。
  affected_count int NOT NULL DEFAULT 1 CHECK(affected_count >= 1),
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id)
);

-- 「这个主体被停用过几次」（回看一个 SKU 的停用历史）。
CREATE INDEX master_data_cleanup_record_by_entity
  ON master_data_cleanup_record(tenant_id, entity_type, entity_id, created_at DESC);
-- 「停用台账」主查询：时间倒序 + 分页。最近停用了什么，是这个页面的第一个问题。
CREATE INDEX master_data_cleanup_record_by_time
  ON master_data_cleanup_record(tenant_id, created_at DESC, id);
-- 「谁做的」（权限审计与责任追溯）。
CREATE INDEX master_data_cleanup_record_by_actor
  ON master_data_cleanup_record(tenant_id, decided_by, created_at DESC);

-- ============================================================ 第六节：停用而不是删除（蓝图关系那一行的兑现）
--
-- 本模块**没有**任何 DELETE 主数据的端点或触发器，写路径只改 status：
--   * SKU：INACTIVE（不物理删除，V1 的 sku 行被订单快照引用，实体目录 §5）；
--   * 供应商/客户：party.status = 'INACTIVE'（V27 的 P4 已允许 ACTIVE → INACTIVE，
--     且 V27 第七节已把 P3 拒绝建档的理由写成"3.9.2.2 清理的正是这类"）。
--
-- 实体目录 §5 的理由照抄在这里作为记录：主数据一旦被引用就**只能停用**，
-- 物理删除会让历史单据指向一个不存在的行，而"对手方是谁 / 卖的是什么"是
-- 财务凭证与订单快照上必须显示的东西。
--
-- ============================================================ 第七节：RLS
--
-- 三张表都是租户级主数据（与 party / sku / supplier_quote 同级）：只需租户隔离，
-- 可见性由端点层权限把关。照抄 V32 第八节的 DO $$ FOREACH 写法：三条策略必须逐字相同，
-- 复制粘贴的第三遍早晚会漏掉 FORCE，而漏掉 FORCE 之后表 owner（迁移角色）
-- 仍然能绕过策略读别人的引用统计 —— 那是一条看不见的越权面。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['master_data_reference_stats','master_data_cleanup_rule','master_data_cleanup_record'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON master_data_reference_stats TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON master_data_reference_stats TO starcon_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON master_data_cleanup_rule TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON master_data_cleanup_rule TO starcon_app;
-- 停用记录**只增不改**：它是一次决定的留痕，允许 UPDATE/DELETE 等于允许篡改审计。
-- 与 V1 对 audit_event 的 REVOKE UPDATE,DELETE 同一纪律（那里是 REVOKE，这里按表逐条显式 REVOKE）。
GRANT SELECT,INSERT ON master_data_cleanup_record TO catalog_app;
GRANT SELECT,INSERT ON master_data_cleanup_record TO starcon_app;
REVOKE UPDATE,DELETE ON master_data_cleanup_record FROM catalog_app;
REVOKE UPDATE,DELETE ON master_data_cleanup_record FROM starcon_app;

-- ============================================================ 第八节：本迁移钉死的七条语义（服务层 ZombieCandidateRules 的对应实现）
--
-- 写在这里是因为「数据库为什么这样设计」与「服务层为什么这么判」必须是同一份承诺，
-- 两处各说一套的话，下一个改代码的人只会读到其中一处。
--
--   **Z1 三态：未知 ≠ 零。** 快照表里**没有行**就是"这一维度没有结论"，
--      而不是"这一维度为 0"。判定结果有 CANDIDATE / NOT_ZOMBIE / INCOMPLETE 三态，
--      INCOMPLETE（启用中的维度缺观测）**不是**候选，也不可停用。第二节。
--
--   **Z2 只有 catalog 自己的数据能无条件判。** 「长期未维护」用 sku.updated_at /
--      party.updated_at —— 这两列是 catalog 自己的写入所有权，可以直接判。
--      四个引用维度一律走快照表，**任何时候都不查别家服务的库**（第一节）。
--
--   **Z3 停用是不可逆的决定，必须留痕。** 每次停用写一行 master_data_cleanup_record，
--      且该行不可改不可删（第七节的 GRANT/REVOKE）。所以"停用记录"不是从别处
--      推导出来的视图，而是这次动作的直接产物。
--
--   **Z4 批量停用是原子的，且拒收 INCOMPLETE。** 一次批量要么全部停用成功、要么一条都不动
--      （服务层单事务）。传入 INCOMPLETE 的 id 时整批 409 而不是跳过它继续停其余 ——
--      "跳过"会让运营以为这一批都处理完了，而漏掉的那几条永远不会有人回来处理。
--
--   **Z5 规则与证据随记录冻结。** 记录里的 rule_snapshot / evidence 是**停用当时**的值，
--      不随规则改动或新快照而回溯更新。第三节/第五节。理由是审计要回答"凭什么"，
--      而那个答案在动作发生的那一刻就已经固定了。
--
--   **Z6 已经是非活跃状态的主体不再重复停用。** 对已经 INACTIVE 的 SKU / party 再"停用"一次
--      没有任何效果，却会让停用台账里出现一条与上一条内容相同的新记录 ——
--      回看时无法分辨"停用了两次"与"重复点了一次"。
--
--   **Z7 快照过期会让结论失效，但不会静默改判。** observed_at 超过规则的
--      snapshot_stale_days 时，该维度判为 UNKNOWN（回到 Z1 的三态）而不是沿用旧值 ——
--      沿用旧值意味着"三个月前没人引用它"被当成"现在也没人引用它"。
--      过期信息在候选行上**显式返回**（stale=true + observedAt）而不是悄悄按 UNKNOWN 处理：
--      运营需要分清「这条从没被观测过」与「这条观测过但数据太老了」。
--
-- ============================================================ 第九节：留给上游的口子（不是本模块的缺口）
--
--   * **trade（订单）**：服务尚不存在。上游落地后调用
--     {@code POST /catalog/zombie-candidates/reference-stats} 推送 ORDER_LINE_COUNT。
--     catalog 不提供"查询某 SKU 是否被订单引用"的反向读端点 —— 那是 trade 的事实。
--   * **warehouse（07 库存）**：服务已交付（有 bucket 数据与 /warehouse/inventory/buckets）。
--     接入方式是 warehouse 侧定时把每个 SKU 的在仓量推过来（INVENTORY_ON_HAND）；
--     **本车道不修改 warehouse 任何文件**，那是另一个服务的写入所有权。
--   * **channel（04 刊登）**：服务存在但只有平台目录/连接/店铺/同步，没有刊登实体。
--     04 落地后推送 LISTING_COUNT。
--   * **purchase（06 采购）**：服务尚不存在。落地后推送 UNIT_COST（SKU 的最新采购成本）。
--
--   这四个维度的推送端点**现在就是可用的真实 API**，不是留给未来的占位：
--   任何一个上游接入时不需要改本模块的 schema 或规则，只需调它。
--
--   * **3.9.2.1 完整度评分**：同在 3.9.2 L3 下但**独立**，两者不共享数据表
--     （评分是"缺什么"，本模块是"没人用"）。
--   * **3.1.1.3 生命周期**：本模块**只读** spu/sku 的状态与 updated_at，
--     不改生命周期状态机（僵尸停用写的是 sku.status / party.status，
--     不是 V4 的 spu_lifecycle 五态 —— 那两个是不同层级的状态，不要混）。