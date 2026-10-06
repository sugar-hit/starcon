-- 3.2.1.2 属性选项管理：跨属性的选项聚合视图所需的索引。
--
-- ============================================================ 这一版只加索引，不动列
--
-- `attribute_option` 的 `position` / `enabled` / `created_at` 三列是 V11 已经建好的，
-- 本迁移**不重复 ALTER**——Flyway 已应用的版本不可改，这里也不需要新列：
-- 3.2.1.2 要补的是「跨属性的聚合读」与「批量重排」，两者都落在既有列上。
--
-- 真正缺的是索引：V11 的 `attribute_option_order(tenant_id,attribute_id,position,value)`
-- 只服务「按单个 attribute_id 取选项」（3.2.1.1 的下拉）。本模块的主查询是
-- **租户级、跨全部枚举属性**的聚合列表：先按 enabled / 引用状态筛，再按属性分组排序，
-- 走的是 (tenant_id, enabled) 前缀，原索引一个都用不上，会退化成全表扫 + 排序。
--
-- ------------------------------------------------------------ 1. 聚合列表主路径
-- 覆盖：按启用状态筛选 → 按属性分组 → 按 position 排序。
CREATE INDEX attribute_option_enabled_list
  ON attribute_option(tenant_id, enabled, attribute_id, position, value);

-- 「按引用状态筛选」（只看被用过的 / 只看还没人用的）是本模块点名的筛选维度。
-- 引用计数本身在 SQL 里用相关子查询算，但**先按属性收窄**再算，比对整租户所有选项逐行算引用快得多。
CREATE INDEX attribute_option_attribute_lookup ON attribute_option(tenant_id, attribute_id);

-- 排序的备选维度：按登记时间倒序（「最近加了哪些选项」）与按引用次数倒序
-- （「哪些选项最热」）都要走全表，所以给 created_at 一条索引。
CREATE INDEX attribute_option_created ON attribute_option(tenant_id, created_at DESC, attribute_id);

-- ------------------------------------------------------------ 2. 引用计数的反查路径
-- 蓝图把「引用计数（该选项被多少商品使用）」列为**输入**：界面上必须在删除前就看见它。
-- 两个计数各自要反查全库：
--
--   * `category_attribute_option`：某选项被哪些类目规则列为可选值。
--     V11 只建了 `category_attribute_rule_reverse`，**漏了这张表**——而这张表才是选项级的引用。
--   * `attribute_value_item`：某选项被多少处商品属性值取用。
--     V1 建的索引（如果有）都指向 value_id 方向，从 option_value 反查没有索引。
CREATE INDEX category_attribute_option_reverse
  ON category_attribute_option(tenant_id, attribute_id, option_value);
CREATE INDEX attribute_value_item_option
  ON attribute_value_item(tenant_id, attribute_id, option_value);

-- ------------------------------------------------------------ 3. 批量重排
-- 批量重排按 `value` 定位行并写 `position`。主键已经是 (tenant_id,attribute_id,value)，
-- 按主键顺序更新可让同一事务里的多行更新走索引而非顺序扫，**不为它额外建索引**：
-- 建一条与主键同序的索引只是把同样的列再存一遍，收益仅在批量行数很大时才显现，
-- 而单个属性的选项上限是 50（见 AttributeDictionaryService.MAX_OPTIONS）。

-- ============================================================ RLS 与表结构说明
-- 本迁移不新建表，所以不重复 V1/V11 的建策略 DO $$ 块：`attribute_option`
-- 在 V1 已 ENABLE + FORCE ROW LEVEL SECURITY 且带 tenant_id=current_setting('app.current_tenant') 的
-- isolation 策略，`category_attribute_option`（V11）与 `attribute_value_item`（V1）同理。
-- 本模块所有读写都经 `AccessService.run` 打开租户上下文，跨租户的行在 DB 层就被过滤掉。

-- ============================================================ 留给下游的口子（不是本模块的缺口）
--   * `productValueCount` 目前恒为 0：`attribute_value` 的复合外键仍锁在 SPU 作用域，
--     字典条目暂时不会被赋属性值。这是数据库现状，界面照实呈现，不伪造计数。
--     3.2.2.1/3.2.2.2 接上赋值链路后该计数会自动开始增长，届时索引已就位。