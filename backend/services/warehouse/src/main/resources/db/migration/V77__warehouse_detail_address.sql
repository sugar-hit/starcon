-- ===========================================================================
-- 8.1.1.2 仓库详情
-- ===========================================================================
--
-- 蓝图原文：「单仓库全景：基础信息与地址、库位结构、库存分布、作业统计、关联的
-- 自发货/中转/FBA 备货策略、作业人员与设备。」以及「详情页是仓库的控制塔」。
--
-- ---------------------------------------------------------------------------
-- 本迁移刻意**不建新表**，只补 warehouse 表上「地址」这一组列
-- ---------------------------------------------------------------------------
-- 上一条车道 8.1.1.1 的结论是对的：仓库列表是**聚合视图**，不存独立数据。
-- 但本模块与它的差别在于「地址」——蓝图 8.1.1.2 的职责第一条就是「基础信息与**地址**」，
-- 而 V1 建的 warehouse 表只有 country_code（国家地区），没有任何地址列。
--
-- 为什么不给地址单开一张 warehouse_address 表：
--   * 仓库与地址是 1:1。一个仓同时有「收件地址 / 发货地址 / 注册地址」在本模块里
--     没有依据 —— 蓝图没有这个概念，凭空造三套地址等于发明需求。
--   * 单开一张表会让「仓库的地址」有两个真相来源（warehouse 表没有、
--     但任何一条 SQL 都得记得 join），而仓库详情是最常读的一页。
--   * 8.1.1.2 的另一个兄弟 8.1.1.3 配的是**行为参数**（拣货策略、复核比例、工作时段），
--     地址不属于它，所以也不存在「地址归 8.1.1.3 建表」的可能。
--
-- 五列的取舍：
--   address_line1 / address_line2  街道地址，两行是国际件的实际需要（很多国家门牌独占一行）
--   city / region / postal_code     城市 / 省州 / 邮编
-- **不加 contact_name / contact_phone**：收发货联系人是作业与面单的属性，
-- 不在 8.1.1.2 的「基础信息与地址」里，凭空加两列只会造出没有写入方的字段。
--
-- ---------------------------------------------------------------------------
-- 全部可空，且**没有 DEFAULT** —— 这是跨车道安全的形状
-- ---------------------------------------------------------------------------
-- 规则（AGENTS.md 多租户 + 本仓库跨车道纪律）：ADD COLUMN 之后必须看有没有 DEFAULT。
-- 五列都是 nullable 且无 DEFAULT：
--   * PG 对 nullable 无默认列的 ADD COLUMN 是**元数据操作**，不重写表、不持锁到全表扫完，
--     所以 8.1.1.1 列表页与其他车道的读查询不会被阻塞。
--   * 所有既有 INSERT 都**显式列出列名**（本服务测试与 8.1.1.1 的 create() 均如此），
--     不带列名的 INSERT VALUES(...) 才会因为列数变化而报错 —— 仓库里没有这种写法。
--   * 没有 SET NOT NULL，所以不存在「目标表缺 BEFORE INSERT 触发器兜底」那条静态判据的命中。
--   * 没有新增 CHECK 收紧既有行的取值（唯一的 CHECK 是 postal_code 的非空白，
--     用 IS NULL 短路，对所有存量 NULL 行恒真）。
--
-- 换句话说：本迁移没有任何形状能让别的车道违约。它只增列。
--
-- ---------------------------------------------------------------------------
-- 多租户：本迁移不新增表，因此不写 ENABLE / FORCE ROW LEVEL SECURITY
-- ---------------------------------------------------------------------------
-- warehouse 的 tenant_id NOT NULL + ENABLE + FORCE RLS + isolation 策略由 V1 建立，
-- 新列自动继承同一张表的策略。刻意**不重复** ALTER ... ENABLE：Flyway 重复 ENABLE 是幂等的，
-- 但重复写一遍会让人误以为「RLS 是本迁移加的」——归属写错比不写更糟（与 V69 同一理由）。
-- WarehouseDetailIntegrationTest 仍然逐张断言 relforcerowsecurity，
-- 断言的是**运行时事实**，不是本迁移的归属。
--
-- 权限：新列落在 warehouse 表上，warehouse_app 在 V1 已获该表的
-- SELECT/INSERT/UPDATE/DELETE，表级权限自动覆盖新列，无需也不应再 GRANT 一次。
--
-- 索引：详情页是按主键取单行 + 同租户列表扫描，现有 uq_warehouse_code(tenant_id,code) 与
-- 主键已覆盖；地址列只随行读出，不作为筛选维度（蓝图没有「按地址筛仓库」），
-- 因此**刻意不建** address 索引 —— 建一条永不命中的索引只是给写放大找借口。

ALTER TABLE warehouse
  ADD COLUMN IF NOT EXISTS address_line1 varchar(200),
  ADD COLUMN IF NOT EXISTS address_line2 varchar(200),
  ADD COLUMN IF NOT EXISTS city varchar(100),
  ADD COLUMN IF NOT EXISTS region varchar(100),
  ADD COLUMN IF NOT EXISTS postal_code varchar(20);

-- 邮编留空串没有含义（「填了但什么都没填」与「没填」在界面上无法区分），
-- 所以空白串在 DB 层就被拒；NULL 仍然放行 —— 存量行与未填地址的新仓都靠它。
ALTER TABLE warehouse DROP CONSTRAINT IF EXISTS warehouse_postal_code_check;
ALTER TABLE warehouse
  ADD CONSTRAINT warehouse_postal_code_check
    CHECK(postal_code IS NULL OR btrim(postal_code) <> '');

-- 同理清掉其余四列的空白串：界面上「地址栏填了几个空格」没有信息量，
-- 而它在导出与面单上会被原样带出去。
ALTER TABLE warehouse DROP CONSTRAINT IF EXISTS warehouse_address_text_check;
ALTER TABLE warehouse
  ADD CONSTRAINT warehouse_address_text_check CHECK(
        (address_line1 IS NULL OR btrim(address_line1) <> '')
    AND (address_line2 IS NULL OR btrim(address_line2) <> '')
    AND (city        IS NULL OR btrim(city)        <> '')
    AND (region      IS NULL OR btrim(region)      <> '')
  );