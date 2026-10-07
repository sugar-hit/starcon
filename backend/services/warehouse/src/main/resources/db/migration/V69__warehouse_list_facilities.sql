-- ===========================================================================
-- 8.1.1.1 仓库列表
-- ===========================================================================
--
-- 蓝图原文：「展示租户下所有仓库：类型（自营/三方/海外仓/平台仓/监管仓）、所属法人、
-- 国家地区、时区、启用状态、库存量与作业量，支持筛选与新增。」以及关系里的红线：
-- 「**仓库绑定法人是财务前提**：不同法人的库存货权与收入归属不同。」
--
-- ---------------------------------------------------------------------------
-- 本迁移刻意**不建新表**
-- --------------------------------------------------------------------------
-- warehouse 表由 V1 建立、V55 补了 kind 列，仓库列表要展示的每一列都已经存在。
-- 蓝图里剩下的两个缺口都在这张表上，且都**不是**「加一列」能解决的：
--
--   1. kind 的取值不够。蓝图点名五种类型，V55 只落了四种，**监管仓没有落点**。
--   2. 筛选维度缺索引。类型 / 法人 / 国家是蓝图点名的三个筛选条件，
--      而现有两条索引（warehouse_list 以 status 开头、warehouse_kind_list 以 kind 开头）
--      **都不以国家或法人开头**，按这两个条件筛只能扫全租户的仓库行。
--
-- 建一张并行的「仓库列表投影表」是错的做法：列表是聚合视图，蓝图 7.1.1.1 已经把这条
-- 纪律写成字面（「总览只是聚合视图，不存独立数据」），仓库列表同理 ——
-- 存一份列表数据就意味着「列表上的数」与「warehouse 表上的数」会有两个真相来源。
--
-- ---------------------------------------------------------------------------
-- 1. 监管仓：补齐蓝图点名的第五种类型
-- ---------------------------------------------------------------------------
-- 蓝图五类 → 现有四类的映射，逐条说清为什么这么对：
--   自营     → LOCAL         V55 已有的自有仓
--   海外仓   → OVERSEAS      V55 已有的海外仓
--   平台仓   → PLATFORM_FBA  V55 已有的 FBA 平台仓
--   三方仓   → THIRD_PARTY   V55 已有的三方仓
--   监管仓   → BONDED        **本迁移新增**
--
-- 为什么监管仓是**自有账本**而不是平台仓观测值：监管仓（保税仓）的货是本租户自有货，
-- 只是处于海关监管状态、未完税出区。货权与自有仓完全一样，所以它的数量必须记在
-- inventory_balance / stock_bucket 里，**不是** platform_stock_observation。
-- 这一点由 V55 的两道触发器自动兑现，本迁移不必也不该再加一道：
--   * guard_platform_warehouse_no_ledger 只拦 kind='PLATFORM_FBA'，BONDED 的账本余额放行；
--   * guard_observation_requires_platform_warehouse 只放行 PLATFORM_FBA 写观测值，
--     所以 BONDED 想写观测值会被 DB 拒绝 —— 不会出现「自有账本 + 观测值」两个数。
--
-- 为什么是放宽 CHECK 而不是新开一列：kind 已经是 7.1.3.1 多仓矩阵的行分组轴，
-- 再加一列「监管仓标记」会出现「既不是 OVERSEAS 也不是 BONDED、但同时打两个标记」的行，
-- 而分组轴必须单值。放宽 CHECK 是一次向后兼容的变更：已有四种取值一行都不受影响。
--
-- 存量行的处理：本迁移**不回填**。V55 的存量行都是 LOCAL（V55 文件头已写明理由），
-- 其中是否有仓其实是保税仓，系统无从判断 —— 猜一个值写进去就是伪造事实。
-- 运营在列表里改自己的行时才会落真实类型。
ALTER TABLE warehouse DROP CONSTRAINT IF EXISTS warehouse_kind_check;
ALTER TABLE warehouse
  ADD CONSTRAINT warehouse_kind_check
    CHECK(kind IN ('LOCAL','OVERSEAS','PLATFORM_FBA','THIRD_PARTY','BONDED'));

-- 类型 × 国家 的筛选下钻：V55 的 warehouse_kind_list 以 kind 开头，
-- 按国家筛时它帮不上忙，列表页的「国家」下拉会退化成全租户扫描。
CREATE INDEX warehouse_country_list ON warehouse(tenant_id,country_code,status,code);

-- 所属法人 是蓝图点名的财务前提，也是筛选条件之一。现有两条索引都不含这一列，
-- 按法人筛同样只能扫描。这条索引让「这个法人名下有哪些仓」走索引。
CREATE INDEX warehouse_legal_entity_list
  ON warehouse(tenant_id,default_legal_entity_id,status,code) WHERE default_legal_entity_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. 多租户与授权：本迁移没有新表，沿用 V1 已有的配置
-- ---------------------------------------------------------------------------
-- warehouse 已是租户业务表：tenant_id NOT NULL（V1）、ENABLE + FORCE ROW LEVEL SECURITY
-- + isolation 策略（V1 第 9 节）、GRANT SELECT,INSERT,UPDATE,DELETE TO warehouse_app（V1）。
-- 这里刻意**不再**重复 ALTER ... ENABLE ROW LEVEL SECURITY：
-- Flyway 对同一张表重复 ENABLE 是幂等的，但重复写一遍会让人以为「RLS 是这个迁移加的」，
-- 而它其实是 V1 加的 —— 归属写错比不写更糟。
--
-- 但**必须**留一句可见的断言前提：列表查询按 tenant_id 过滤不是「防御性编程」，
-- 而是第二道防线。第一道是 RLS 策略（current_setting('app.current_tenant')），
-- 它由 backend/libraries/tenancy 在事务内设置，客户端的 X-Tenant-Id 头进不来。
-- 服务层的 tenant_id 谓词让「忘了绑定上下文」这类错误当场失败而不是静默返回全表。
--
-- 本迁移**不放宽**任何权限：warehouse_app 仍然可写 warehouse（新增仓库需要），
-- starcon_app 仍然拿不到任何业务表（V1 末句，跨服务只读角色不得触碰仓库主数据）。