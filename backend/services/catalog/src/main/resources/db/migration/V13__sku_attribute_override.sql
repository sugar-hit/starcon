-- 3.2.2.2 SKU 属性覆写：覆写链路的索引与覆写记录的系统时间。
--
-- ==================================== 这一版为什么不新建覆写表（模型决策）
--
-- 覆写行在 V1 就已经存在了，本迁移**只加索引与一列时间戳**，不建新表。逐条理由：
--
--   * `attribute_value.sku_id` 本身就可空：`NULL` = SPU 公共侧那一行，非空 = 某个 SKU 的覆写行。
--     V1 的 `UNIQUE NULLS NOT DISTINCT(tenant_id,attribute_id,sku_id)` 保证两侧各自唯一，
--     所以「公共值」与「SKU 覆写」本来就是同一张表里的两类行，而不是两张表的联表结果。
--   * `attribute_value.state` 已经是 `CHECK(state IN ('SET','CLEAR'))`。蓝图点名的
--     **「显式清空」与「未设置」必须是两种状态**在这里已经成立，而且不靠 NULL 表达：
--       - 未设置  → SKU 侧**根本没有这一行**（读不到任何 state）
--       - 显式清空 → 有一行 `state='CLEAR'`
--       - 覆写赋值 → 有一行 `state='SET'` + 至少一条 `attribute_value_item`
--     两者语义相反但物理形态不同，清空绝不会悄悄退回公共值。
--   * 类型化的取值表 `attribute_value_item`（text/decimal/boolean/date/option 五个排他列 +
--     `num_nonnulls(...)=1` CHECK）也已是覆写值的唯一存放处。另建 `sku_attribute_override` 会让
--     同一个 SKU 的属性值同时可能落在两张表里——直接违反「每张事实表只有一个写入所有者」，
--     也会让 3.1.2.2 详情页那条已交付的解析查询漏读一半数据。
--   * 已交付的 3.1.3.1 规格轴、3.2.1.1 属性选项都按 code 对齐 `attribute_definition`，
--     覆写值走同一条链路，值源仍然只有一处。
--
-- 与 V12 同思路：上一个模块（3.2.1.2）同样只加索引、不建表，并写下「留给下游的口子」。
-- 本迁移就是把 V12 结尾那句「3.2.2.1/3.2.2.2 接上赋值链路后该计数会自动开始增长」兑现掉。
--
-- ==================================== 1. 覆写记录的时间戳
--
-- 覆写是**独立于 SPU 资料保存**的一次写入：改一个 SKU 的覆写不该把整个商品档案的
-- updated_at 顶起来（那会让所有并发编辑者都撞上版本冲突）。因此覆写行自己带一个系统时间，
-- 界面上才能回答「这条覆写是什么时候写的 / 谁清空的」。
--
-- 这一列是**系统时间**（写入即 now()），不是业务时间：覆写没有生效日期这种业务概念，
-- 不编一个出来比混用更安全。真正的业务事件留痕仍在 `audit_event`（服务层写
-- SkuAttributeOverridden / SkuAttributeCleared / SkuAttributeOverrideRemoved）。
ALTER TABLE attribute_value ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- 【必须跟着改的代码】V1/V11 的 `insert into attribute_value values(?,?,?,?,?,?)` 是
-- **位置化插入**，加列后会直接报错。与 V11 里 `attribute_option` 同一个坑，
-- 已在 CatalogService.writeValue 里改成显式列名，本迁移不重复该代码改动。
--
-- ==================================== 2. 索引
--
-- ------------------------------------------------------------ 2.1 SKU 侧的覆写读路径（本模块主查询）
-- 本模块的读是**「一个 SKU 的全部覆写」**：`... where sv.tenant_id=? and sv.sku_id=? and sv.attribute_id=?`
-- （SkuDetailService 的有效值解析与本模块的覆写面板共用这个形状）。
-- V1 的唯一约束是 (tenant_id, attribute_id, sku_id) —— 按 attribute_id 打头，
-- 对「先按 sku_id 收窄」的主查询一个前缀都用不上，会退化成全表扫。
-- 部分索引：公共侧那一行（sku_id is null）永远不会被这个查询命中，不入索引。
CREATE INDEX sku_attribute_override_lookup
  ON attribute_value(tenant_id, sku_id, attribute_id)
  WHERE sku_id IS NOT NULL;

-- 改位置（删除 / 写入覆写）按 sku_id + attribute_id 定位，同样走这条索引。
--
-- ------------------------------------------------------------ 2.2 公共侧的读路径（已覆盖，不重复建）
-- 「某个属性的 SPU 公共值」是 `attribute_id=? and sku_id is null`。V1 的唯一约束
-- (tenant_id,attribute_id,sku_id) 对前两列是等值、第三列是 NULL，btree 能直接命中，
-- **不需要**再建一条 (tenant_id,attribute_id) WHERE sku_id IS NULL 的索引。
--
-- ------------------------------------------------------------ 2.3 类目必填的读路径（本模块新增的校验读）
-- 必填校验要按「商品所属类目」取该类目的全部必填属性，一条 (tenant_id,category,attribute_id) 查询。
-- V11 的 `category_attribute_rule_lookup(tenant_id,category,position,attribute_id)` 前缀是
-- (tenant_id,category)，已经覆盖本查询，**不重复建**。
--
-- ==================================== 3. RLS
--
-- 本迁移不新建表，所以不重复 V1/V11 的建策略 DO $$ 块：
-- `attribute_value` 与 `attribute_value_item` 在 V1 已 ENABLE + FORCE ROW LEVEL SECURITY，
-- 且带 `isolation` 策略（tenant_id 必须等于 current_setting('app.current_tenant')）。
-- 本模块所有读写都经 AccessService.run 打开租户上下文，跨租户的行在 DB 层就被过滤掉，
-- 服务层取不到的行按「不存在或没有访问权限」（404）处理，不做跨租户的软判断。
--
-- 显式清空同样受这条策略保护：清空记录与公共值记录落在同一张表，
-- 所以「B 租户能不能清空 A 租户 SKU 的属性」这个问题根本到不了服务层。
--
-- V2 的 `GRANT ... ON ALL TABLES` 只覆盖 V2 当时已存在的表；attribute_value 是 V1 建的、
-- 已分别被 V1（starcon_app）与 V2（catalog_app）授权。这里再显式写一遍，
-- 口径与 V11 结尾一致，避免将来重跑迁移时授权来源只靠推断。
GRANT SELECT,INSERT,UPDATE,DELETE ON attribute_value TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON attribute_value TO starcon_app;

-- ==================================== 留给下游的口子（不是本模块的缺口）
--   * **属性字典条目仍不能被赋属性值**：`attribute_value` 的复合外键
--     `(tenant_id,spu_id,attribute_id) → attribute_definition(tenant_id,spu_id,id)` 仍锁在 SPU 作用域，
--     本模块只处理「商品已建的 SPU 级属性定义」的覆写。字典条目要能直接被赋值，
--     属于把类目配置与商品属性打通的改动，不在本模块。
--   * **类目仍是自由文本**（`spu.category varchar(100)`）：必填校验按类目文本匹配
--     `category_attribute_rule.category`，与 V11 同口径。3.3.1.1 分类树落地后两边一起改挂类目主键。
--   * **本模块不推进 `sku.version`**：sku 的写入所有者是 3.1.2.x，覆写改动版本号会让
--     那些模块的乐观锁凭据被本模块顶掉。见 SkuAttributeOverrideService 的类注释。