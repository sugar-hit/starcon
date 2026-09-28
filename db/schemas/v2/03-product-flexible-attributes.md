# 商品可配置属性：SPU 公共值与 SKU 完整属性

日期：2026-09-27。状态：设计稿，尚未实现。补充并取代 [专项设计](02-product-tenant-permissions.md) 中“描述属性与规格属性完全分离、一个 owner 每属性一个值”的简化规则。商品属性允许受类型约束的扩展；核心金额、库存、业务关系仍使用专用列。

## 1. 业务语义

SPU 保存该款商品的公共资料和公共属性；SKU 表达某个具体规格，其**有效属性集**应是完整的，而不是只有颜色/容量。完整属性集可以由 SPU 公共值与 SKU 值解析而来，无需在每个 SKU 重复存相同值。

以用户举例的商品名称和参数作为虚构配置示例，不代表实际产品规格：

- SPU：iPhone18ProMax；公共属性“视频规格=4K”。
- SKU A：外壳颜色=黑色，容量=256GB。
- SKU B：外壳颜色=红色，容量=256GB。
- 查询 A 得到视频规格=4K、外壳颜色=黑色、容量=256GB；查询 B 得到视频规格=4K、外壳颜色=红色、容量=256GB。

颜色可以是销售规格轴，视频规格通常是描述参数。**是否区分 SKU**和**值由谁提供**是两个独立维度，不再用一个 usage 枚举同时承担两种含义。

## 2. 每个 SPU 自己配置属性

一个属性配置包含显示名称、稳定属性键、类型、单位、可选值、是否必填、是否多值、是否作为规格轴、显示顺序、分组以及取值模式。

| 取值模式 value_mode | SPU 值 | SKU 值 | SKU 有效值 |
| --- | --- | --- | --- |
| COMMON_LOCKED 公共固定 | 统一配置 | 禁止覆写 | SPU 公共值 |
| COMMON_DEFAULT 公共默认 | 可设默认 | 可覆写或显式清空 | SKU 覆写优先，否则继承 SPU |
| SKU_ONLY 按 SKU 配置 | 不配置 | 各 SKU 单独设置 | SKU 自身值 |

例子：视频规格所有 SKU 均为 4K → COMMON_LOCKED；随箱配件多数相同、特殊版本不同 → COMMON_DEFAULT；颜色各 SKU 不同 → SKU_ONLY。

`is_variant_axis` 独立配置。第一期销售规格轴必须 SKU_ONLY + 单选 OPTION + 必填，参与 SKU 唯一组合；非规格参数支持上述三种取值模式和多种类型。颜色如果被选为规格轴，就直接使用同一属性的 SKU 值，不再额外保存另一份“颜色描述参数”。不能因为参数值不同就自动创建新 SKU，只有明确勾选的规格轴决定组合。

### 三种自定义来源

1. 从租户属性库选择：例如多个商品复用“材质”“额定电压”“颜色”，统一类型和单位语义，便于筛选。
2. 从类目模板复制：手机模板建议容量、电池等字段。复制生成当前 SPU 配置，之后模板变更不会静默改动现有商品。
3. 当前商品新建：仅该 SPU 使用的属性，不污染所有商品的属性列表；可通过受控映射将其提升为租户属性，不能只因同名就合并。

允许自定义分组，例如“基本参数”“外观”“包装”“技术参数”。内置规格也走同一机制，但核心编码、状态、库存、售价、条码不允许被自定义属性替代。用户可扩展描述参数，不可注入表达式、SQL 或脚本。

## 3. 数据实体

以下替代先前 attribute_definition 的 usage=VARIANT/DESCRIPTIVE 和分散 sku_variant_value 作为第二套值源的设计。旧名称是历史设计，未有数据库迁移，无需迁移实际数据。

| 表 | 主要字段与关系 |
| --- | --- |
| attribute_definition | tenant_id, id, code, name, data_type, cardinality, unit_dimension?, definition_scope(TENANT/SPU), owner_spu_id?, status；类型属于定义，引用后不可原地改变 |
| attribute_option | tenant_id, id, attribute_id, code, label, position, status；UNIQUE(tenant,attribute_id,code) |
| category_attribute_rule | category_id, attribute_id, recommended_value_mode, required, is_variant_axis, group_name?, position；为复制模板，不是实时继承配置 |
| spu_attribute_group | tenant_id, id, spu_id, code, name, position；UNIQUE(tenant,spu_id,code) |
| spu_attribute_binding | tenant_id, id, spu_id, attribute_id, group_id?, display_name?, value_mode, required, is_variant_axis, position, binding_revision, status；UNIQUE(tenant,spu,attribute)，配置以此为准 |
| spu_attribute_allowed_option | tenant_id, binding_id, attribute_id, option_id；限制本 SPU 可选集；复合 FK 确认 option 属于绑定属性 |
| spu_attribute_default | tenant_id, binding_id, value_revision；一个 binding 至多一个 SPU 值集合头；COMMON_LOCKED/COMMON_DEFAULT 使用 |
| spu_attribute_default_item | tenant_id, default_id, ordinal, value_type, text_value?, decimal_value?, boolean_value?, date_value?, option_id?, uom_id?；公共值集合明细 |
| sku_attribute_override | tenant_id, id, sku_id, spu_id, binding_id, override_state(SET/CLEAR), value_revision；UNIQUE(tenant,sku,binding)，SKU 与 binding 必须同 SPU |
| sku_attribute_override_item | tenant_id, override_id, ordinal, value_type 及相应类型值；SKU 值集合明细 |

`definition_scope=TENANT` 时 owner_spu_id=NULL；SPU 时 owner_spu_id 必填且本地复合 FK→spu。绑定须属于同租户，局部属性只能绑定自身 SPU；组也必须属于绑定 SPU。后续 SQL 应用复合唯一键/FK并结合事务校验实现，不能只校验前端选项。

所有值项带绑定类型依据及 owner 引用；CHECK 保证恰好一个类型值非空，并与定义类型一致。OPTION 必须指向本 binding 允许的 option，不能只指向同租户任意 option。集合头与明细、绑定模式的跨行约束在服务事务内校验，必要时增加约束触发器；不能声称普通单行 CHECK 足以完成全部校验。

### 类型与集合规则

- TEXT、DECIMAL、BOOLEAN、DATE、OPTION；第一期不支持嵌套任意 JSON 或自由计算公式。
- cardinality=SINGLE/MULTI；单值 SET 恰好一项，多值 SET 至少一项；多值 OPTION 禁止重复选项。
- 数值类型可指定量纲与允许单位，转换到标准单位用于比较，保留输入单位；不把“重量=100g”存为无法计算的文本。
- 数值可配置最小/最大值，文本配置最大长度；这些校验参数用结构化列或版本化受控规则配置，禁止任意执行代码。物理精度上限在迁移阶段定，Java 用 BigDecimal。
- SKU 无 override 行表示继承（SKU_ONLY 则为缺失）；CLEAR 表示显式无值且没有 item，不能回退公共默认；SET 使用明细，空字符串不代表继承或清空。
- required 检查**解析后的有效值**。必填属性不可 CLEAR；未完成草稿允许缺失，激活时拒绝缺失。
- COMMON_LOCKED 不允许任何 SKU override；SKU_ONLY 不允许 default；COMMON_DEFAULT 允许两者。多值覆盖采用**整组替换**，不是自动合并。
- 区分 false、0、空值、继承，不能用 truthy/falsy 判断是否存在。

## 4. SKU 完整属性读取与搜索

有效值解析顺序：

1. COMMON_LOCKED：读取 SPU 公共值。
2. COMMON_DEFAULT：存在 SET 则取 SKU 值；存在 CLEAR 则为空；不存在 override 则取 SPU 值。
3. SKU_ONLY：读取 SKU SET，否则为空。

API 返回每个属性的稳定键、名称、类型、有效值、单位、来源 SPU/SKU/NONE、value_mode、是否继承、可否覆写及版本。前端可展示“继承自 SPU”的标识，提供“恢复默认”（删除 override）和“清空”（CLEAR）两个不同操作。

完整商品属性是一个版本一致的读取结果：事务一致性读取 SPU 公共值及 SKU 覆写，返回 spu_attribute_revision 和 sku_attribute_revision；缓存键包含两个版本。对导出、商品对比和筛选统一使用有效值解析，不能列表只看覆写、详情再补默认，造成同 SKU 两套答案。

一期使用 PostgreSQL 查询构建有效值；需要搜索投影时，其 owner 仍为 catalog，包含 tenant_id、sku_id、attribute_id/binding_id、类型值、来源和源版本。SPU 公共值变更必须刷新所有受影响继承 SKU；投影允许延迟时 API 明确数据新鲜度，详情及业务校验回源。自定义属性筛选使用 attribute_id，不按可能重名的显示名称匹配。

## 5. 变更、规格组合与历史

- SPU 默认值更新：只影响没有覆写且继承该值的 SKU 当前资料；批量预览受影响 SKU 数量，确认后写入 SPU 新版本、审计和 outbox；不修改历史订单。
- 公共固定属性更新：影响全部 SKU 当前参数；若变化意味着真实不同商品身份，应建新 SPU/SKU，而不是用资料编辑掩盖商品换代。
- COMMON_DEFAULT→COMMON_LOCKED：若存在 SKU 覆写，拒绝直接切换，必须预览冲突并明确统一值；不得静默丢弃。
- SKU_ONLY→COMMON 模式：需选择公共值和覆写保留策略；切换必填、类型、集合基数及规格轴同样走显式校验，不直接 UPDATE 模式字段了事。
- 类型、量纲、已使用 option 身份不可原地改变；新定义/新版本+受控转换，已有引用保留。标签可改但事件/单据保留显示快照。
- 属性删除：未被引用的草稿允许删；已使用配置停用归档，保留历史。不自动清除已发布渠道资料。
- 一个 SKU 每个规格轴恰好一个 OPTION，有效规格来自 sku_attribute_override_item；`variant_signature` 由规格轴稳定 option_id 排序生成，同 SPU 唯一。
- **不再单独写 sku_variant_value**；如接口兼容需要，提供只读视图。spu_variant_axis/option 也由 binding(is_variant_axis)/allowed_option 表达，避免轴和参数重复维护。
- SKU 值、规格签名、必填校验、版本及事件同事务提交；并发编辑用 expected version。已有业务 SKU 不允许更改规格身份，保留上一版新建 SKU 的规则。
- sales_order_line_attribute_snapshot 保存交易相关属性的名称、值、单位及来源版本。报关、履约等在各自单据形成时冻结所需属性；不必复制所有营销文案，不回查实时 SPU 改写历史。

## 6. 配置页面和权限

商品编辑分为“公共资料”“属性配置”“SKU 属性矩阵”：

- 属性配置：添加现有/自定义属性，选择类型、取值模式、必填、规格轴和显示分组。
- SPU 公共属性：填写 COMMON_LOCKED/COMMON_DEFAULT 值；SKU_ONLY 只显示配置。
- SKU 矩阵：行是 SKU，列是属性；继承值带标记，固定公共值只读，覆写可恢复默认，多选属性整组编辑。

权限细分：`catalog.attribute.create/update` 管租户属性库；`catalog.spu.attribute.configure` 管商品绑定及局部属性；`catalog.spu.update` 改公共值；`catalog.sku.update` 改 SKU 覆写。只有 SKU 编辑权限不能修改公共值来影响全部 SKU，也不能把局部属性提升到租户库。读、导入、导出沿用页面与数据范围授权，后端执行同一解析和校验规则。

建议初始可配置上限：每 SPU 200 个属性、10 个规格轴、每多值属性 50 项；只是实现前压测的起点，不是声称已验证容量。自由扩展意味着无需加数据库列或发布代码就能添加参数，不意味着无限数据量和无限类型。

## 7. 验收示例

- SPU 视频规格=4K，黑/红 SKU 均完整返回 4K 及各自颜色。
- 公共默认值更新只影响继承 SKU，SET 覆写保留、CLEAR 不恢复默认。
- 删除覆写“恢复默认”和显式清空行为不同；false、0 能正确返回。
- 固定公共参数的 SKU 覆写被拒绝；SKU_ONLY 的 SPU 默认值被拒绝。
- 不同商品可配置不同属性集合；局部属性不能被其他 SPU 绑定；跨租户属性/option 关联失败。
- 数值、布尔、日期、单选、多选类型、单位、必填和基数校验覆盖导入/API/页面。
- 颜色作为规格轴只有一个值源；重复组合在并发写入时被唯一约束拒绝。
- SPU 改属性后 SKU 缓存/搜索投影失效；历史订单快照不变；过期版本编辑失败。

尚未创建表或执行这些测试。
