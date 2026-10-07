-- ===========================================================================
-- 7.4.2.1 盘点单：发起盘点、冻结时点快照、生成盘点任务、录入实盘
-- ===========================================================================
-- 蓝图原文：「发起盘点：选择范围（按仓库/类目/库位/ABC 分类）、冻结时点快照、
-- 生成盘点任务（按库位或按 SKU）、录入实盘数。」
-- 「输入：盘点范围；冻结快照；实盘结果录入（扫码）。输出：盘点单 + 账面/实盘/差异三列数据。」
-- 以及本模块唯一被点名的红线：
-- 「**盘点时点必须冻结账面数**，否则边盘边卖会产生虚假差异。」
--
-- ---------------------------------------------------------------------------
-- 一、这张迁移要解决的核心问题：冻结是「数据」不是「查询」
-- ---------------------------------------------------------------------------
-- 「冻结账面数」有两种实现方式，差别是本模块成立与否：
--
--   (A) 每次看盘点单时回查 stock_bucket 的当前 on_hand —— 这**没有冻结**。
--       盘点期间任何一笔出入库都会改写账面列，于是「盘点员还没走到这个库位，
--       系统账面就先变了」，最后拿到的差异里混着正常出入库，人根本分不清
--       哪些是真盘亏、哪些只是边盘边卖。这正是蓝图警告的虚假差异。
--   (B) 发起盘点那一刻把当时的 on_hand **复制成一行**存进 stocktake_line ——
--       此后库存怎么动都不再影响这一行。
--
-- 本迁移取 (B)，并且**不**只靠应用层自觉：3.1 的触发器把 book_on_hand 与那八个
-- 维度列钉成不可更新，任何 UPDATE 只能改 counted_* 那一组。冻结因此是数据库不变量，
-- 拿到连接直接改也改不动 —— 与 V56 口径不可变触发器同一条纪律（"应用层不写 UPDATE"
-- 不是保证）。
--
-- 差异列同理是 generated column：variance 恒等于 counted_qty - book_on_hand，
-- 没有任何第二份真相，也不接受人工填写。蓝图说差异「三列数据」是输出，
-- 而差异是**算出来的不是填的** —— 手工填差异等于允许出现一个解释不了的数。
--
-- ---------------------------------------------------------------------------
-- 二、为什么「未录入」用 NULL 而不是 0
-- ---------------------------------------------------------------------------
-- counted_qty 为 NULL = 这一行还没盘。写成 0 的话，「盘了结果是 0」与「还没盘」
-- 变成同一个值，而这两件事的后续动作完全相反（前者进差异处理，后者要去派人盘）。
-- generated column 在 counted_qty 为 NULL 时自然算出 NULL 差异，不需要额外分支。
--
-- ---------------------------------------------------------------------------
-- 三、盘点范围只给三种，第四种与类目如实说明
-- ---------------------------------------------------------------------------
-- 蓝图列了四种（仓库/类目/库位/ABC 分类），本表 scope_type 只落三个可实现的：
--
--   WAREHOUSE  按仓库    warehouse.id            本服务自有数据，可直接筛
--   LOCATION   按库位    warehouse_location.id   本服务自有数据，可直接筛
--   CATEGORY   按类目    ——                      **枚举里留着，但当前显式拒绝(422)**
--
-- CATEGORY 为什么留着却不放行：类目与品牌属 03 商品主数据（catalog），
-- sku_ref 刻意**不存**类目（V1 文件头：「不存类目/品牌/售价/库存 —— 那些必须走
-- catalog 授权 API 或事件」）。本服务的 CatalogProjectionPort 当前是
-- UnwiredCatalogProjectionPort，尚未接入读取适配器。按 7.1.1.1 已建立的同一套约定：
-- 未接入的筛选维度**显式拒绝 422**，而不是返回空集假装「按这个类目盘不出东西」。
-- 上游接入后只换那个 Bean，盘点逻辑不动。
--
-- ABC 分类为什么连枚举都不给：全库（warehouse / catalog 两侧所有迁移 + db/schemas）
-- **不存在任何 ABC 分类字段**，也没有动销率/帕累托一类可推导它的原始列。
-- 臆造一个算法（按金额分位）冒充已有数据，会让盘点员以为盘的是公司认定的
-- A 类货，实际是一个没人批准过的猜测 —— 比明说「没有这个维度」危险得多。
-- 所以它不出现在枚举里，改由 /stocktakes/capabilities 如实报出。
--
-- ---------------------------------------------------------------------------
-- 四、任务状态为什么不存列
-- ---------------------------------------------------------------------------
-- stocktake_task 只存「派给谁」这一件它独有的事实，**不存**状态列。
-- 任务状态恒等于它那些行的录入进度（0 行已录=PENDING、部分=COUNTING、全=DONE），
-- 现算比存列更可靠：存列就要在每次录入后同步它，而漏同步一次就出现
-- 「任务显示已完成但还有行没盘」。派生查询走 stocktake_line 的 (tenant_id,sheet_id,task_id)
-- 索引，代价可接受。
--
-- ---------------------------------------------------------------------------
-- 五、任务单元只有两种：按库位 / 按 SKU
-- ---------------------------------------------------------------------------
-- 蓝图原文「生成盘点任务（按库位或按 SKU）」。task_key 因此在两种语义之间复用同一列：
--   BY_LOCATION  task_key = location_id   「先去把 A-01 这个货位盘完」
--   BY_SKU       task_key = sku_id        「先把某个 SKU 在各库位的量盘完」
-- 用 CHECK 钉死配对关系，防止出现「task_unit 说按库位、task_key 里躺着一个 sku_id」
-- 这种录入时才会暴露的错误。
--
-- ---------------------------------------------------------------------------
-- 六、口径：数量与时间
-- ---------------------------------------------------------------------------
--   数量 NUMERIC(19,4) + Java BigDecimal，全程禁浮点（与 V1/V2/V54/V56 同口径）。
--   时间 timestamptz：frozen_at 是**发起盘点那一刻**（服务端取，不接受请求参数传入），
--   counted_at 是实盘录入时刻。二者都由应用层以 OffsetDateTime 绑定并写 ?::timestamptz。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. stocktake_sheet · 盘点单（头部）
-- ---------------------------------------------------------------------------
CREATE TABLE stocktake_sheet (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 单号。服务派生（ST-<yyyyMMdd>-<8 位 uuid 十六进制>），不接受调用方自填：
  -- 人手填的单号会撞、会重，而盘点单是差异追责的凭据。
  code varchar(32) NOT NULL,

  -- 范围类型。见文件头第三节。CATEGORY 当前显式拒绝（422），枚举里保留是为了
  -- 上游接入后不必再动数据模型。
  scope_type varchar(16) NOT NULL CHECK(scope_type IN ('WAREHOUSE','LOCATION','CATEGORY')),

  -- 范围定位键。WAREHOUSE→warehouse.id；LOCATION→warehouse_location.id；
  -- CATEGORY→catalog 的类目 id（逻辑 ID，不建外键，与 V56 的 reference_id 同一条理由）。
  scope_key uuid NOT NULL,

  -- 范围可读名在发起那一刻快照：库位三个月后被停用改用途，三个月前的盘点单
  -- 仍然要能读懂自己盘的是哪儿。
  scope_label varchar(150) NOT NULL,

  -- 任务单元：按库位发任务还是按 SKU 发任务（见文件头第五节）。
  task_unit varchar(16) NOT NULL CHECK(task_unit IN ('BY_LOCATION','BY_SKU')),

  -- **冻结时刻**：发起盘点那一刻。账面数就是这一瞬间的数。
  -- 刻意不接受请求参数传入 —— 调用方传时刻等于允许「盘三天前的账」，
  -- 而账面数与三天前那一刻的库存无从对齐（中间的交易还没落进这条单）。
  frozen_at timestamptz NOT NULL,

  -- 录入进度。OPEN=一行未录，COUNTING=部分已录，COUNTED=全部已录（此时移交 7.4.2.2）。
  -- 由服务在每次录入的同一事务里推进，见 StocktakeService.count()。
  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','COUNTING','COUNTED')),

  note varchar(500) NOT NULL DEFAULT '',
  -- 操作人：已认证用户 id（来自 identity 解析出的成员关系，与 V54/V56 同一纪律，
  -- 不接受请求参数传入 —— 否则「谁发起的盘点」就是一个能伪造的字段）。
  created_by_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 单号在租户内唯一：撞号会让「这张单是哪张」失去唯一答案。
  UNIQUE(tenant_id,code),
  CHECK(btrim(code)<>''),
  CHECK(btrim(scope_label)<>'')
);

-- 列表主查询：按状态筛选 + 最近发起的排在最前。
CREATE INDEX stocktake_sheet_status ON stocktake_sheet(tenant_id,status,created_at DESC);
-- 反查「这个仓/这个库位历史上盘过几次」（同一范围重复盘点时判断账准不准）。
CREATE INDEX stocktake_sheet_scope ON stocktake_sheet(tenant_id,scope_type,scope_key,created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. stocktake_task · 盘点任务（按库位或按 SKU）
-- ---------------------------------------------------------------------------
-- 先建任务再建明细：明细的 task_id 要外键指回任务表，而 PG 不允许指向尚不存在的表。
--
-- 任务状态为什么不存列：见文件头第四节 —— 它恒等于该任务下各行的录入进度，
-- 现算比存列更可靠（存列就要在每次录入后同步它，漏同步一次就会出现
-- 「任务显示已完成但还有行没盘」）。
CREATE TABLE stocktake_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sheet_id uuid NOT NULL,

  -- 任务单元。见文件头第五节。
  task_unit varchar(16) NOT NULL CHECK(task_unit IN ('BY_LOCATION','BY_SKU')),

  -- 任务键：BY_LOCATION→location_id，BY_SKU→sku_id。用 CHECK 钉死配对关系。
  task_key uuid NOT NULL,

  -- 可读名快照（库位编码或 SKU 编码）。
  task_code varchar(64) NOT NULL,
  task_name varchar(200) NOT NULL,

  -- 派给谁。NULL = 还没派。**不接受请求参数传入租户**，派工对象由调用方给出
  -- 已认证用户 id；本服务**无法**校验它是否真是本租户成员 —— identity 才是成员关系的
  -- 所有者（V56 的 reference_id 用的是同一条理由：跨服务不建 FK、不查对方的表）。
  -- 代价写在这里而不是藏起来：派错人只影响派工，不会越权读写别人的盘点单。
  assigned_to_user_id uuid,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张盘点单里同一个任务键只有一行。
  UNIQUE(tenant_id,sheet_id,task_key),
  FOREIGN KEY(tenant_id,sheet_id) REFERENCES stocktake_sheet(tenant_id,id),
  CHECK(btrim(task_code)<>'')
);

CREATE INDEX stocktake_task_sheet ON stocktake_task(tenant_id,sheet_id,task_code);
-- 「谁手上有哪些没干完的盘点任务」：工作台待办与我的任务页走这条。
CREATE INDEX stocktake_task_assignee ON stocktake_task(tenant_id,assigned_to_user_id)
  WHERE assigned_to_user_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. stocktake_line · 盘点明细（**冻结快照就落在这里**）
-- ---------------------------------------------------------------------------
-- 一行 = 冻结那一刻的一个 stock_bucket。维度列与 bucket 的六个维度 + 库位 + 批次
-- 逐列相同，外加源 bucket_id 用于事后回溯「这条账面数是从哪一行抄下来的」。
CREATE TABLE stocktake_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sheet_id uuid NOT NULL,

  -- 来源 bucket。刻意不建外键到 stock_bucket：盘点单要在那个 bucket 之后被
  -- 合并、归零、乃至（理论上）被清理时仍然读得出自己冻结自哪一行，
  -- 而 V2 的 bucket_no_delete 触发器已经挡住删除，可读性由它保证。
  -- 这里**有**外键的是 sheet_id —— 明细离开盘点单没有意义。
  bucket_id uuid NOT NULL,

  -- 冻结的定位维度。8 个维度 + bucket_id 合起来唯一标识冻结自哪一条 bucket。
  legal_entity_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  location_id uuid NOT NULL,
  lot_id uuid,
  quality_status_id uuid NOT NULL,

  -- 可读名同样冻结：sku_ref 与 warehouse_location 都可能被改名或停用，
  -- 盘点单要能回看「当时盘的那个货叫什么」。
  sku_code varchar(64) NOT NULL,
  sku_name varchar(200) NOT NULL,
  warehouse_code varchar(64) NOT NULL,
  location_code varchar(64) NOT NULL,
  lot_code varchar(64) NOT NULL DEFAULT '',
  quality_status_code varchar(32) NOT NULL,

  -- **账面数 = 冻结时刻的 on_hand**。NOT NULL 且由 3.1 钉死不可更新 —— 这就是
  -- 蓝图那句「盘点时点必须冻结账面数」在数据层的落地。
  book_on_hand numeric(19,4) NOT NULL,

  -- 任务归属：发起时按 task_unit 派好，与 stocktake_task 的 key 配对。
  task_id uuid NOT NULL,

  -- 实盘数。NULL = 未录入（见文件头第二节）。
  counted_qty numeric(19,4),
  counted_at timestamptz,
  counted_by_user_id uuid,
  -- 条码。蓝图说「实盘结果录入（扫码）」，本模块把它做成**手工录入 + 条码字段**：
  -- 没有扫码硬件对接协议，凭空造一个扫码流程只会得到一个假的成功提示。
  -- 条码列让后续接扫码枪/ PDA 时不必改表结构，现在也能真的记录「盘的是哪一件」。
  counted_barcode varchar(128),
  counted_note varchar(500),

  -- **差异列 = 算出来的，不是填的**。generated column 保证它永远等于
  -- counted_qty - book_on_hand：没有第二份真相，也不接受人工填写。
  -- counted_qty 为 NULL（未录入）时本列自然为 NULL。
  variance numeric(19,4) GENERATED ALWAYS AS (counted_qty - book_on_hand) STORED,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 同一张盘点单里同一个 bucket 只出现一行。
  UNIQUE(tenant_id,sheet_id,bucket_id),
  FOREIGN KEY(tenant_id,sheet_id) REFERENCES stocktake_sheet(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES stocktake_task(tenant_id,id),
  -- 实盘数不得为负：盘出负数没有物理含义，它是录入错误（少见多记时应该走调整单）。
  CHECK(counted_qty IS NULL OR counted_qty >= 0)
);

-- 明细分页主查询：按盘点单取全部行（配合 status / 差异筛选）。
CREATE INDEX stocktake_line_sheet ON stocktake_line(tenant_id,sheet_id);
-- 任务进度派生查询（文件头第四节）：按任务取它的行与已录行数。
CREATE INDEX stocktake_line_task ON stocktake_line(tenant_id,sheet_id,task_id);
-- 「只看待处理差异」这一屏的主查询：已录入且有差异。
CREATE INDEX stocktake_line_variance ON stocktake_line(tenant_id,sheet_id,variance)
  WHERE counted_qty IS NOT NULL;

-- ===========================================================================
-- 4. DB 级行为约束：账面数与维度列冻结（3.1）
-- ===========================================================================
-- 这是整个模块的核心不变量，刻意放在 DB 而不是只靠服务层不写那条 UPDATE。
--
-- 为什么不用「只在应用层保证」：盘点差异最终要变成调整单并追责到人，
-- 而「账面数在盘点期间被改过」正是最容易被质疑、也最难自证清白的一类争议。
-- 把冻结做成数据库不变量之后，「这张单的账面数是发起那一刻的数」是一句
-- 可以直接查 trigger 定义确认的话，而不是一句需要信任应用代码的承诺。
--
-- 允许改的只有 counted_* 那一组 —— 那正是实盘录入要反复做的事。
CREATE FUNCTION guard_stocktake_line_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.sheet_id,NEW.bucket_id,NEW.legal_entity_id,NEW.sku_id,NEW.warehouse_id,
     NEW.location_id,NEW.lot_id,NEW.quality_status_id,
     NEW.sku_code,NEW.sku_name,NEW.warehouse_code,NEW.location_code,NEW.lot_code,
     NEW.quality_status_code,NEW.book_on_hand,NEW.task_id,NEW.created_at)
    IS DISTINCT FROM
    (OLD.sheet_id,OLD.bucket_id,OLD.legal_entity_id,OLD.sku_id,OLD.warehouse_id,
     OLD.location_id,OLD.lot_id,OLD.quality_status_id,
     OLD.sku_code,OLD.sku_name,OLD.warehouse_code,OLD.location_code,OLD.lot_code,
     OLD.quality_status_code,OLD.book_on_hand,OLD.task_id,OLD.created_at) THEN
   RAISE EXCEPTION '盘点账面数与冻结维度不可修改：盘点时点的快照一经写入即不可改写，'
     '否则盘点期间发生的出入库会改写账面数，产生蓝图点名的「虚假差异」。'
     '可修改的只有实盘数、实盘时刻、实盘人与条码' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stocktake_line_frozen
 BEFORE UPDATE ON stocktake_line
 FOR EACH ROW EXECUTE FUNCTION guard_stocktake_line_frozen();

-- 4.2 明细行不删除：清掉一行等于把「盘过这一行」这件事从盘点单上抹掉，
-- 而盘点单是差异追责的凭据。要作废一张盘点单 = 它的账面数从不存在过，
-- 正确做法是不发起它，而不是事后删行（与 V2 的 bucket_no_delete 同一条理由）。
CREATE FUNCTION guard_stocktake_line_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '盘点明细行不允许删除：账面数是盘点时点的冻结快照，删掉它等于让这张'
   '盘点单从未盘过这一行，而差异追责要以这张单为准。' USING ERRCODE='23514';
END $$;
CREATE TRIGGER stocktake_line_no_delete BEFORE DELETE ON stocktake_line
 FOR EACH ROW EXECUTE FUNCTION guard_stocktake_line_no_delete();

-- 4.3 录入一致性：实盘数与实盘时刻/实盘人必须同时有值或同时为空。
-- 有 counted_qty 却没有 counted_at（或反过来）意味着「谁在什么时候盘的」丢了，
-- 而这一栏正是差异追责时要问的第一个问题。
CREATE FUNCTION guard_stocktake_line_counted() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.counted_qty IS NULL) <> (NEW.counted_at IS NULL)
    OR (NEW.counted_qty IS NULL) <> (NEW.counted_by_user_id IS NULL) THEN
   RAISE EXCEPTION '实盘数、实盘时刻、实盘人必须同时有值或同时为空：实盘归属缺一半意味着'
     '「谁在什么时候盘的」丢了，而差异追责要以这一栏为准' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER stocktake_line_counted BEFORE INSERT OR UPDATE ON stocktake_line
 FOR EACH ROW EXECUTE FUNCTION guard_stocktake_line_counted();

-- ===========================================================================
-- 5. 多租户与授权（与 V1/V2/V54/V56 同一套写法）
-- ===========================================================================
-- 三张表都是租户业务表，一视同仁 FORCE。
-- current_setting('app.current_tenant') 由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['stocktake_sheet','stocktake_task','stocktake_line'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- V1 的 GRANT ... ON ALL TABLES 只覆盖当时已存在的表，新表必须显式授权。
GRANT SELECT,INSERT,UPDATE,DELETE ON stocktake_sheet, stocktake_task, stocktake_line TO warehouse_app;
-- 盘点单与明细都是审计凭据，不给 DELETE：4.2 的触发器再收一道。
REVOKE DELETE ON stocktake_sheet, stocktake_line FROM warehouse_app;
-- 任务随盘点单存在，但单张任务的行本身没有单独保留的价值；
-- 这里保留 DELETE 是为了与 V1/V2 的口径一致（明细行受触发器保护，任务行可清理）。
-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1/V2/V54/V56 同口径）。
REVOKE ALL ON stocktake_sheet, stocktake_task, stocktake_line FROM starcon_app;