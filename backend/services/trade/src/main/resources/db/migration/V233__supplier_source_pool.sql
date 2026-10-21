-- 6.1.2.1 档口与货源池（trade 服务）。
--
-- 蓝图 06-purchase-and-supply.md 6.1.2.1 原文：
--   职责：管理 1688/义乌购等货源渠道的店铺档口，抓取与同步在售货源（标题、起订量、
--         价格区间、规格、图片），支持收藏、比价、建立与内部 SKU 的货源关联。
--   输入：档口 URL 或插件采集；货源同步任务；收藏操作。
--   输出：货源条目 + 档口列表；价格与起订量信息。
--   关系：上游 = 外部货源平台；下游 = 3.6.2 报价、6.3.1.2 采购单（带入货源价）。
--         **货源数据是外部快照，会变**，采购下单时必须以实际下单金额为准，
--         货源快照仅作参考。
--
-- 归属：**trade 服务**（蓝图 README 系统地图 06 行「后端主责服务：trade + catalog（party）」）。
-- 前端路由 /supply/sources，后端命名空间 /trade/sources —— 与 6.4.1.1（前端 /purchase/processing、
-- 后端 /trade/processing）同一纪律。本迁移**不 ALTER 任何既有表**，跨车道 DDL 回归面 = 0。
--
-- ===========================================================================
-- 一、模块边界：上游是**外部平台**，不是内部服务
-- ===========================================================================
-- 蓝图那句「上游 = 外部货源平台」是本模块最容易被做错的一条：
--   * 采集是**主动抓取**（本服务发 HTTP 请求到 1688 / 义乌购），不是读某个内部库；
--   * 因此本模块**不建任何外部货源主档表**，只建「我们自己抓到的快照」；
--   * 外部平台的账号 / token **一律不入库**：只存 secret manager 的**引用名**
--     （supplier_source_collector_endpoint.credential_ref），DB 层用 CHECK 钉死前缀，
--     粘一个真实 token 在写库那一刻就被拒（与 10.1.1.1 承运商档案同一纪律）。
--
-- ===========================================================================
-- 二、**快照语义**是本模块的立身之本（蓝图加粗句：「货源数据是外部快照，会变」）
-- ===========================================================================
-- 蓝图加粗句写死了两条硬要求：
--   1. 采购下单时必须以实际下单金额为准，**货源快照仅作参考**；
--   2. 因此货源的价格 / 起订量是「**某一次采集看到的值**」，不是「当前价」。
--
-- 落到结构上是三条硬约束：
--   * supplier_source_snapshot 是**只追加**的：价格区间 / 起订量 / 标题 / 采集时刻
--     写进去之后**任何一列都不可改写**（触发器钉死 UPDATE 与 DELETE），
--     于是「上周三看到的那个价是什么」永远答得出来；
--   * supplier_source_item 只存**身份**（档口 + 平台货源 ID + 指向当前快照的指针），
--     **它自己一个价格列都没有** —— 「当前价」与「快照价」不会在表里混成一列，
--     「当前价」永远是「当前指针所指的那一版快照里的价格」；
--   * 每次同步**新增一版快照**并把指针移到新版本，旧版本原地保留。
--
-- 顺带钉死一个容易漏的细节：**价格区间必须同时有下限与上限**（都是 numeric，
-- 禁 float / double），且上限不得低于下限。外部货源普遍有「¥1.20 ~ ¥9.80」这种阶梯价，
-- 只存一个价就是把区间假装成精确值 —— 而蓝图要求比价时「价格区间重叠时给出区间」。
--
-- ===========================================================================
-- 三、档口（店铺）与货源条目**分开建模**
-- ===========================================================================
-- 档口是「店铺」实体：平台 + 档口 ID + 档口名 + URL + 采集方式。
-- 货源条目挂在档口下。两者生命周期不同（档口长期存在，货源条目随采集增删），
-- 合成一张表就得把「档口还在但货没了」和「档口本身没了」混成一行。
--
-- ===========================================================================
-- 四、供应商在 **catalog**，本库只存逻辑 ID + 快照
-- ===========================================================================
-- 卷 06 的主责是「trade + catalog（party）」，而供应商主数据（party / party_role）
-- 在 catalog 服务的库里。本模块要把档口关联到供应商，**禁止**建跨库 FOREIGN KEY、
-- **禁止**跨库 SQL（AGENTS.md 模块边界纪律）。做法照抄既有范式：
--   * supplier_source_stall.supplier_party_id 存 catalog 的 **SUPPLIER 角色 id**
--     （逻辑引用，跨库不建约束，与 trade V92 的 sku_ref、warehouse V1 的 sku_ref 同一纪律）；
--   * supplier_name_snapshot 存**关联那一刻的供应商名快照**：catalog 改名后历史档口
--     仍能显示当时的供应商名，与 processing_order 的 output_sku_code 快照同一做法；
--   * 「这个供应商到底存不存在 / 叫什么」由服务层经 catalog 的**授权只读接口**核对
--     （见 SupplierPartyQueryPort），核对不到就显式失败，不凭空造一个供应商。
--
-- ===========================================================================
-- 1. supplier_source_stall · 档口（店铺）
-- ===========================================================================
-- 平台是**封闭词表**：每个平台对应一个独立的 vendor 适配器（AGENTS.md「每个 vendor 独立包，
-- 只暴露统一接口」），不在词表里的平台没有适配器，也就无从采集。
CREATE TABLE supplier_source_stall (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 编码：租户内唯一、建后不可改（服务层强制）。它是档案上的稳定引用。
  code varchar(64) NOT NULL CHECK(btrim(code)<>''),
  -- 平台词表（封闭）。加平台 = 加一个 vendor 适配器 + 一条新迁移放开这个 CHECK。
  platform varchar(32) NOT NULL CHECK(platform IN ('ALIBABA_1688','YIWU_GOU')),
  -- 平台侧的档口 ID（1688 的 shopId / 义乌购的店铺号）。租户内同平台唯一。
  platform_stall_id varchar(128) NOT NULL CHECK(btrim(platform_stall_id)<>''),
  name varchar(200) NOT NULL CHECK(btrim(name)<>''),
  -- 档口 URL：采集入口。URL_SYNC 档口用它拼采集请求；PLUGIN_PUSH 档口用它让人核对来源。
  url text NOT NULL DEFAULT '',
  -- 采集方式（蓝图输入句「档口 URL 或插件采集」）：
  --   URL_SYNC   —— 本服务主动抓取（走 vendor 适配器）
  --   PLUGIN_PUSH—— 外部插件把采到的货源条目推上来（本服务不主动发起外部请求）
  collect_method varchar(24) NOT NULL CHECK(collect_method IN ('URL_SYNC','PLUGIN_PUSH')),
  -- **供应商（catalog SUPPLIER 角色）的逻辑 ID** —— 跨服务引用，不建外键。
  supplier_party_id uuid,
  -- 关联时刻的供应商名快照（catalog 改名后仍能显示当时的供应商名）。
  supplier_name_snapshot varchar(200) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','PAUSED')),
  note text NOT NULL DEFAULT '',
  -- 最近一次采集的结果（成功 / 失败 + 失败原因）。失败原因必须留痕：
  -- 界面上「同步失败」四个字没有原因，等于让运营猜。
  last_collection_id uuid,
  last_collected_at timestamptz,
  last_collection_status varchar(16) CHECK(last_collection_status IN ('SUCCEEDED','FAILED')),
  last_failure_reason text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,code),
  UNIQUE(tenant_id,platform,platform_stall_id)
);

-- ===========================================================================
-- 2. supplier_source_collection · 采集批次（一次同步 = 一行）
-- ===========================================================================
-- 为什么要有「批次」这一层：一次采集要么整体成功、要么整体失败，
-- 中途失败不能把上一次的好数据冲掉。批次行把「这次采到了什么 / 为什么失败」
-- 变成一条**可查的事实**，而不是日志里的一行字。
CREATE TABLE supplier_source_collection (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  stall_id uuid NOT NULL,
  -- 采集方式（与档口一致，落一份快照便于事后核对当时是怎么采的）。
  collect_method varchar(24) NOT NULL CHECK(collect_method IN ('URL_SYNC','PLUGIN_PUSH')),
  -- SUCCEEDED 成功（写了新快照）/ FAILED 失败（**零快照写入**）。
  status varchar(16) NOT NULL CHECK(status IN ('SUCCEEDED','FAILED')),
  -- 触发来源：MANUAL 人工点同步 / PLUGIN 插件推送 / SCHEDULED 定时任务。
  trigger_source varchar(16) NOT NULL CHECK(trigger_source IN ('MANUAL','PLUGIN','SCHEDULED')),
  -- 采集时刻：**服务端**写入的时刻（不采信客户端时钟 ——
  -- 客户端时钟漂移会把快照时间线搅乱，而时间线正是本模块的价值所在）。
  collected_at timestamptz NOT NULL,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  -- 本次采到的条目数（失败时为 0）。
  item_count integer NOT NULL DEFAULT 0 CHECK(item_count >= 0),
  -- 本次**跳过**的条目数与原因（外部返回的坏数据：价格区间写反 / 起订量非法 / 币种非法）。
  -- 跳过必须留痕：「采到 12 条」与「采到 12 条、2 条价格区间不自洽被跳过」是两条不同的事实，
  -- 混起来就查不清货为什么少了。
  skipped_count integer NOT NULL DEFAULT 0 CHECK(skipped_count >= 0),
  skipped_reason text NOT NULL DEFAULT '',
  -- 失败原因（成功时为空串）。**必须写清楚**，界面照实显示。
  failure_reason text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id)
);

-- ===========================================================================
-- 3. supplier_source_item · 货源**身份**（一个平台货源一行，稳定 id）
-- ===========================================================================
-- 它只回答「这个档口下的这个平台货源，是哪一条」，**不存任何价格 / 起订量 / 标题**。
-- 收藏与 SKU 关联都挂在它身上，因此货源重新同步时，收藏与关联**不会丢**。
CREATE TABLE supplier_source_item (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  stall_id uuid NOT NULL,
  -- 平台侧的货源 ID（1688 的 itemId）。
  platform_item_id varchar(128) NOT NULL CHECK(btrim(platform_item_id)<>''),
  -- 首次 / 最近一次被采集到的时刻。
  first_seen_at timestamptz NOT NULL,
  last_seen_at timestamptz NOT NULL,
  -- **当前快照指针**：指向 supplier_source_snapshot 的一行。
  -- 「当前价」= 这一行快照里的价格区间 —— 不是一个另存一份、会被改写的副本。
  current_snapshot_id uuid,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,stall_id,platform_item_id)
);

-- ===========================================================================
-- 4. supplier_source_snapshot · 采集快照（**只追加，一列都不可改写**）
-- ===========================================================================
CREATE TABLE supplier_source_snapshot (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 归属的货源身份。
  item_id uuid NOT NULL,
  -- 产生这一版的采集批次（可回溯到「这是哪一次同步采到的」）。
  collection_id uuid NOT NULL,
  -- 该货源的第几版快照（从 1 开始，逐版递增）。
  snapshot_seq integer NOT NULL CHECK(snapshot_seq >= 1),
  -- ---- 采集到的原始事实 ----
  title varchar(400) NOT NULL CHECK(btrim(title)<>''),
  -- **价格区间：下限与上限同时存在**（都是 numeric(19,4)，禁 float / double）。
  -- 外部货源普遍是阶梯价，只存一个价就是把区间假装成精确值。
  price_min numeric(19,4) NOT NULL CHECK(price_min > 0),
  price_max numeric(19,4) NOT NULL CHECK(price_max > 0),
  -- 上限不得低于下限。
  CHECK(price_max >= price_min),
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 起订量（MOQ）。数量刻度与全仓一致：numeric(19,6)。
  moq numeric(19,6) NOT NULL CHECK(moq > 0),
  spec varchar(200) NOT NULL DEFAULT '',
  image_url text NOT NULL DEFAULT '',
  item_url text NOT NULL DEFAULT '',
  -- 是否在售。外部下架的货在下一版快照里 on_sale=false —— 不是把旧快照改成 false。
  on_sale boolean NOT NULL DEFAULT true,
  -- **采集时刻**：这一版快照是什么时候从外部平台看到的。
  collected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,item_id,snapshot_seq)
);

-- ===========================================================================
-- 5. supplier_source_favorite · 收藏（事实，不是临时标记）
-- ===========================================================================
-- 挂在**货源身份**上而不是某一版快照上：重新同步会产生新快照，
-- 挂在快照上的收藏会在每次同步后消失，那不是收藏，是「收藏了这一版价格」。
CREATE TABLE supplier_source_favorite (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  item_id uuid NOT NULL,
  -- 收藏时刻与收藏人（收藏是「谁在什么时候收藏的」这件事本身）。
  favorited_by uuid,
  favorited_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,item_id)
);

-- ===========================================================================
-- 6. supplier_source_sku_link · 与内部 SKU 的货源关联
-- ===========================================================================
-- 蓝图输出句之一「建立与内部 SKU 的货源关联」，下游 6.3.1.2 采购单要带入货源价。
-- SKU 事实（sku / spu）在 **catalog 的库**里 ⇒ 只存逻辑 ID + 编码 / 名称快照，
-- **不建跨库外键**（与 processing_order.output_sku_id 同一纪律）。
CREATE TABLE supplier_source_sku_link (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  item_id uuid NOT NULL,
  -- **内部 SKU 的逻辑 ID**（catalog，跨服务不建约束）。
  sku_id uuid NOT NULL,
  -- SKU 编码 / 名称快照：catalog 改名后历史关联仍能显示当时的品名。
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code)<>''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  linked_at timestamptz NOT NULL,
  linked_by uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,item_id,sku_id)
);

-- ===========================================================================
-- 7. supplier_source_collector_endpoint · 采集接入登记（**只存引用，不存凭证**）
-- ===========================================================================
-- 接线与否是**租户数据**而不是代码常量：同一个 1688 适配器对没登记的租户未接线、
-- 对登记了的租户已接线（与 Alibaba1688SupplierAdapter 读
-- fulfillment_dropship_supplier_endpoint 的做法同一纪律）。
CREATE TABLE supplier_source_collector_endpoint (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  platform varchar(32) NOT NULL CHECK(platform IN ('ALIBABA_1688','YIWU_GOU')),
  -- 外部平台网关地址（不同部署 / 不同区域地址不同，所以是租户数据）。
  base_url varchar(300) NOT NULL DEFAULT '',
  -- **凭证引用**：指向 secret manager / K8s External Secrets 的键。
  -- CHECK 钉死前缀：粘一个真实 token 在写库那一刻就被拒。
  credential_ref varchar(300) NOT NULL DEFAULT ''
    CHECK(credential_ref = '' OR credential_ref ~ '^(secret://|kv://|vault://|external-secrets://)'),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  -- 未登记 = 该平台**未接线**：服务层显式报失败并写明缺什么，绝不伪造一份平台响应。
  wired boolean NOT NULL DEFAULT false,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,platform),
  -- 声称已接线就必须真的给出调用信息，否则「已接线」只是个标签：
  -- 网关地址与凭证引用缺一不可（只给地址没给引用 = 调过去必然 401）。
  CHECK(wired = false OR (btrim(base_url) <> '' AND btrim(credential_ref) <> ''))
);

-- ===========================================================================
-- 8. 约束（只在本迁移自建的表之间建；跨库一律不建）
-- ===========================================================================
ALTER TABLE supplier_source_collection
  ADD CONSTRAINT supplier_source_collection_stall_fk
  FOREIGN KEY (tenant_id, stall_id) REFERENCES supplier_source_stall(tenant_id, id);
ALTER TABLE supplier_source_item
  ADD CONSTRAINT supplier_source_item_stall_fk
  FOREIGN KEY (tenant_id, stall_id) REFERENCES supplier_source_stall(tenant_id, id);
ALTER TABLE supplier_source_snapshot
  ADD CONSTRAINT supplier_source_snapshot_item_fk
  FOREIGN KEY (tenant_id, item_id) REFERENCES supplier_source_item(tenant_id, id);
ALTER TABLE supplier_source_snapshot
  ADD CONSTRAINT supplier_source_snapshot_collection_fk
  FOREIGN KEY (tenant_id, collection_id) REFERENCES supplier_source_collection(tenant_id, id);
ALTER TABLE supplier_source_item
  ADD CONSTRAINT supplier_source_item_current_snapshot_fk
  FOREIGN KEY (tenant_id, current_snapshot_id) REFERENCES supplier_source_snapshot(tenant_id, id);
ALTER TABLE supplier_source_favorite
  ADD CONSTRAINT supplier_source_favorite_item_fk
  FOREIGN KEY (tenant_id, item_id) REFERENCES supplier_source_item(tenant_id, id);
ALTER TABLE supplier_source_sku_link
  ADD CONSTRAINT supplier_source_sku_link_item_fk
  FOREIGN KEY (tenant_id, item_id) REFERENCES supplier_source_item(tenant_id, id);
ALTER TABLE supplier_source_stall
  ADD CONSTRAINT supplier_source_stall_collection_fk
  FOREIGN KEY (tenant_id, last_collection_id) REFERENCES supplier_source_collection(tenant_id, id);

-- ===========================================================================
-- 9. 守卫触发器（**快照不可改写**是本模块最硬的一条不变式）
-- ===========================================================================
-- 9.1 快照只追加：任何 UPDATE / DELETE 一律拒绝。
--     这一条把蓝图加粗句「货源数据是外部快照，会变」变成 DB 级不变式：
--     快照**不是**用来改的，是用来**加**的。改价 = 加一版新快照。
CREATE FUNCTION guard_supplier_source_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '货源快照不可改写（表 supplier_source_snapshot）：外部快照只能追加新版本，'
    '改价请重新采集并写入新一版快照（item_id=% , snapshot_seq=%）',OLD.item_id,OLD.snapshot_seq
    USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER supplier_source_snapshot_no_update
  BEFORE UPDATE ON supplier_source_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_source_snapshot_immutable();
CREATE TRIGGER supplier_source_snapshot_no_delete
  BEFORE DELETE ON supplier_source_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_source_snapshot_immutable();

-- 9.2 快照的价格区间自洽：下限 ≤ 上限，且下限 > 0。
--     与列 CHECK 重复一次是为了让「区间写反」这种错误在**写入路径**上就报出来，
--     而不是在读的时候才被发现（列 CHECK 已经兜住了，这里是第二道闸）。
CREATE FUNCTION guard_supplier_source_snapshot_price_range() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.price_min IS NULL OR NEW.price_max IS NULL THEN
    RAISE EXCEPTION '货源快照必须同时有价格下限与上限（区间不是精确值，不能只存一个）'
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF NEW.price_min <= 0 OR NEW.price_max < NEW.price_min THEN
    RAISE EXCEPTION '货源快照价格区间不自洽：下限 % 上限 %',NEW.price_min,NEW.price_max
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER supplier_source_snapshot_price_range
  BEFORE INSERT OR UPDATE ON supplier_source_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_supplier_source_snapshot_price_range();

-- ===========================================================================
-- 10. 索引
-- ===========================================================================
-- 档口列表：按平台 / 采集方式 / 状态筛。
CREATE INDEX supplier_source_stall_list
  ON supplier_source_stall(tenant_id, platform, collect_method, status, code);
-- 采集批次：档口的同步历史按时间倒序读。
CREATE INDEX supplier_source_collection_stall
  ON supplier_source_collection(tenant_id, stall_id, collected_at DESC, id);
-- 货源身份：按档口读。
CREATE INDEX supplier_source_item_stall
  ON supplier_source_item(tenant_id, stall_id, platform_item_id);
-- 快照：某货源的历史版本按版本号倒序读（价格历史）。
CREATE INDEX supplier_source_snapshot_item
  ON supplier_source_snapshot(tenant_id, item_id, snapshot_seq DESC);
-- 快照：按采集时刻读（某次采集产出了什么）。
CREATE INDEX supplier_source_snapshot_collection
  ON supplier_source_snapshot(tenant_id, collection_id, snapshot_seq);
-- 收藏：按收藏时刻倒序读（收藏列表）。
CREATE INDEX supplier_source_favorite_list
  ON supplier_source_favorite(tenant_id, favorited_at DESC, id);
-- SKU 关联：按 SKU 反查货源（比价视图的主查询）。
CREATE INDEX supplier_source_sku_link_sku
  ON supplier_source_sku_link(tenant_id, sku_id, linked_at DESC, id);

-- ===========================================================================
-- 11. 多租户：RLS + 授权（与 trade V1 / V92 / V97 同一套写法）
-- ===========================================================================
-- 七张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'supplier_source_stall','supplier_source_collection','supplier_source_item',
   'supplier_source_snapshot','supplier_source_favorite','supplier_source_sku_link',
   'supplier_source_collector_endpoint'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  supplier_source_stall,supplier_source_collection,supplier_source_item,
  supplier_source_snapshot,supplier_source_favorite,supplier_source_sku_link,
  supplier_source_collector_endpoint TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  supplier_source_stall,supplier_source_collection,supplier_source_item,
  supplier_source_snapshot,supplier_source_favorite,supplier_source_sku_link,
  supplier_source_collector_endpoint TO starcon_app;

-- ===========================================================================
-- 12. 本迁移的回归面（跨车道纪律）
-- ===========================================================================
--   * **不 ALTER 任何既有表**：V1 的 trade_tenancy_probe、V92 的七张履约表、
--     V97 / V98 的加工单五张表都不碰 ⇒ 跨车道 DDL 回归面 = 0。
--   * 对 catalog 的 party / sku **只读逻辑 ID，不加任何约束**：跨库 FK 本来就禁止。
--   * 新增函数全部带 supplier_source_ 前缀，与既有函数名不冲突。
--   * 版本号 V233 由调度者预分配，**不自己找最大版本号**（并行车道会同时提交迁移）。
