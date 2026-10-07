# 功能蓝图交付进度台账

> **本文件由 `node tools/module-progress/sync.mjs` 生成，请勿手工增删行。**
> 模块的 ID、名称、路由来自 [功能蓝图](README.md)（18 卷，465 个 L4 模块），
> 本脚本只负责同步。**「状态」「证据」两列是人工维护的，脚本按模块 ID 继承。**
>
> 状态词表只有三个值，统计逻辑依赖它，不要新增：
> `已交付`（迁移 + API + 页面 + 测试齐全，无 TODO 残留）· `进行中`（部分交付、未收口，证据列写清缺哪一块）· `未开发`（未开工）。

## 总览

| 指标 | 数量 |
| --- | --- |
| 模块总数 | 465 |
| 已交付 | 60 |
| 进行中 | 0 |
| **未开发** | **405** |
| 完成率 | 12.9% |

## 分阶段进度（交付顺序看这里）

| 阶段 | 已交付 | 进行中 | 未开发 | 合计 |
| --- | --- | --- | --- | --- |
| **D1–D2** | 58 | 0 | 22 | 80 |
| **D3** | 2 | 0 | 38 | 40 |
| **D4** | 0 | 0 | 60 | 60 |
| **D5** | 0 | 0 | 52 | 52 |
| **D6** | 0 | 0 | 78 | 78 |
| **D7** | 0 | 0 | 155 | 155 |

## 分系统进度

| 卷 | 系统 | 阶段 | 已交付 | 模块数 |
| --- | --- | --- | --- | --- |
| 01 | 工作台与门户系统 | D7 | 0 | 23 |
| 02 | 渠道与店铺系统 | D1–D2 / D5 | 20 | 25 |
| 03 | 商品主数据系统 | D1–D2 | 29 | 40 |
| 04 | 刊登与内容系统 | D5 | 0 | 26 |
| 05 | 定价与价目系统 | D6 | 0 | 19 |
| 06 | 采购与供应系统 | D6 | 0 | 26 |
| 07 | 库存核算系统 | D3 | 2 | 31 |
| 08 | 仓储作业系统 | D3 / D4 | 0 | 22 |
| 09 | 订单履约系统 | D4 / D5 | 0 | 27 |
| 10 | 物流与头程系统 | D5 / D7 | 0 | 27 |
| 11 | 售后与退货系统 | D4 / D7 | 0 | 22 |
| 12 | 财务核算系统 | D6 | 0 | 32 |
| 13 | 利润与经营分析系统 | D7 | 0 | 23 |
| 14 | 广告与增长系统 | D7 | 0 | 22 |
| 15 | 客户与客服系统 | D7 | 0 | 24 |
| 16 | 合规与风控系统 | D7 | 0 | 23 |
| 17 | 组织权限与系统设置系统 | D1–D2 / D4 / D6 / D7 | 9 | 29 |
| 18 | 集成开放与智能自动化系统 | D7 | 0 | 24 |

## 判定「已交付」的标准

一个模块标 `已交付` 当且仅当下面四件都成立，缺一件就是 `未开发` 或 `进行中`：

1. **迁移** — `backend/services/<svc>/src/main/resources/db/migration/V*.sql` 里有真实表结构，
   带 `tenant_id NOT NULL` + RLS 策略，不是注释里的设计。
2. **API** — 服务里有对应端点，走 jOOQ 显式 SQL，不是返回硬编码常量的桩。
3. **页面** — `apps/web/src/app/**/page.tsx` 是真实工作区，不是 `DomainPlaceholder`。
4. **测试** — `cd backend && ./mvnw verify` 绿；涉及租户隔离的必须实跑
   「两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户」三条断言。

**判定为 `未开发` 的典型形态**（见到就说明没做完，不许算进已交付）：

- `DomainPlaceholder` 占位页
- 硬编码数组 / 常量当数据源
- 服务里 `TODO` + `throw new UnsupportedOperationException()`
- 迁移文件存在但没有 RLS 策略
- 前端有页面但后端没有对应端点（或反之）

## 跨期说明

「阶段」列按**依赖就绪顺序**逐模块标注，不是卷归属。跨期卷的拆分如下：

- **01 工作台与门户 → 全部 D7**：它是纯投影聚合层（蓝图原文「不写业务数据，只读投影」），
  聚合 09 订单 / 07 库存 / 02 渠道 / 12 财务 / 15 客户 / 17 审批的投影。
  这些数据源没落地之前建「我的待办」只能造假数据，所以整卷排到最后收口。
- **02 渠道与店铺**：D1–D2 交付平台目录、授权连接、店铺站点、同步运行、平台回写；
  映射缺失队列（2.4.2.3）、常用回写操作（2.5.2.1）与 2.6 平台消息接入排 D5，
  因为它们依赖 04 刊登映射与真实平台 API 集成。
- **08 仓储作业**：D3 交付入库侧（仓库档案、库位、收货、上架）；8.3 出库 / 8.4 组装拆解 / 8.5 作业分析排 D4。
- **09 订单履约**：D4 交付手工单闭环；平台订单同步（9.1.2.1）与发货回传（9.4.2.1）排 D5。
- **10 物流与头程**：D5 交付尾程（承运商、运费、运单面单、轨迹交运、逆向退货）；
  10.4 头程与海外仓、10.5 报关清关排 D7。
- **11 售后与退货**：D4 交付售后单、退货授权与实收、退款换货；平台纠纷 / 客诉工单 / 分析排 D7。
- **17 组织权限**：D1–D2 交付租户、法人、部门、成员、角色、页面权限、数据范围、审计；
  17.4 审批流随单据排 D4，17.6.1.1 账簿与本位币随财务排 D6，17.7 套餐与平台参数排 D7。

## 模块明细

| 模块 ID | 模块名 | L3 锚点路由 | 阶段 | 状态 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 1.1.1.1 | 我的待办 | /dashboard/todo | D7 | 未开发 | — |
| 1.1.1.2 | 异常订单队列 | /dashboard/todo | D7 | 未开发 | — |
| 1.1.1.3 | 审批待办 | /dashboard/todo | D7 | 未开发 | — |
| 1.1.2.1 | 核心指标卡 | — | D7 | 未开发 | — |
| 1.1.2.2 | 店铺概览 | — | D7 | 未开发 | — |
| 1.1.2.3 | 履约健康 | — | D7 | 未开发 | — |
| 1.1.3.1 | 库存风险 | — | D7 | 未开发 | — |
| 1.1.3.2 | 资金与回款风险 | — | D7 | 未开发 | — |
| 1.2.1.1 | 全局搜索框 | ⌘K | D7 | 未开发 | — |
| 1.2.1.2 | 搜索结果分组 | ⌘K | D7 | 未开发 | — |
| 1.2.2.1 | 常用页面收藏 | /dashboard/shortcuts | D7 | 未开发 | — |
| 1.2.2.2 | 快捷入口配置 | /dashboard/shortcuts | D7 | 未开发 | — |
| 1.3.1.1 | 通知列表 | /notifications | D7 | 未开发 | — |
| 1.3.1.2 | 通知偏好 | /notifications | D7 | 未开发 | — |
| 1.3.2.1 | 预警规则 | /notifications/rules | D7 | 未开发 | — |
| 1.3.2.2 | 外部渠道推送 | /notifications/rules | D7 | 未开发 | — |
| 1.4.1.1 | 工作台布局 | — | D7 | 未开发 | — |
| 1.4.1.2 | 列表与表格偏好 | — | D7 | 未开发 | — |
| 1.4.2.1 | 语言与时区 | — | D7 | 未开发 | — |
| 1.4.2.2 | 默认店铺与仓库 | — | D7 | 未开发 | — |
| 1.5.1.1 | 同步与 API 健康 | — | D7 | 未开发 | — |
| 1.5.1.2 | 数据一致性体检 | — | D7 | 未开发 | — |
| 1.5.2.1 | 服务与任务状态 | — | D7 | 未开发 | — |
| 2.1.1.1 | 平台目录 | /channels/platforms | D1–D2 | 已交付 | channel V1 platform 码表 + GET /channel/platforms + /channels 平台目录段 |
| 2.1.1.2 | 平台能力矩阵 | /channels/platforms | D1–D2 | 已交付 | platform.capabilities 跨系统能力矩阵 + 端点返回 + /channels 展示 |
| 2.1.1.3 | 站点与国家参数 | /channels/platforms | D1–D2 | 已交付 | marketplace 码表 + shop_marketplace 站点启停 + /channels 站点段 |
| 2.1.2.1 | 平台枚举映射 | — | D1–D2 | 已交付 | platform_enum_mapping（全局只读码表，V3 收回运行角色写权限）+ platform_enum_override（租户覆盖，RLS）+ GET/POST/DELETE /channel/platforms/{code}/enums + 平台目录枚举映射面板 |
| 2.2.1.1 | 连接列表 | /channels/connections | D1–D2 | 已交付 | channel_connection + GET /channel/connections + /channels 连接段 |
| 2.2.1.2 | 授权向导 | /channels/connections | D1–D2 | 已交付 | 迁移 V4__oauth_authorization_wizard.sql（platform_oauth_profile 端点配置表 + oauth_authorization_session 与 oauth_shop_candidate + oauth_callback_bootstrap 租户解析 + connection_account_owner 跨租户唯一索引）；端点 POST 与 GET /channel/oauth/sessions、GET .../shops、POST .../confirm、POST .../cancel、GET /channel/oauth/callback，vendor 适配器覆盖 amazon/ebay/shopee/shopify/temu/tiktok/walmart/lazada；页面 apps/web/src/components/channel/oauth-wizard.tsx 六步全部有真实后端支撑；测试 OAuthAuthorizationIntegrationTest 23 条，含本地 stub 授权服务器跑通发起→跳转→回调→换令牌→建店铺全链路 |
| 2.2.1.3 | 凭据引用管理 | /channels/connections | D1–D2 | 已交付 | credential_ref + PUT /connections/{id}/credential；wire model 无明文令牌类型 |
| 2.2.2.1 | 健康诊断 | — | D1–D2 | 已交付 | GET /connections/{id}/health 健康诊断 + /channels 诊断段 |
| 2.2.2.2 | 重新授权提醒 | — | D1–D2 | 已交付 | GET /connections/reminders 到期提醒 + /channels 提醒展示 |
| 2.3.1.1 | 店铺列表 | /channels/shops | D1–D2 | 已交付 | shop 表 + GET /channel/shops + /channels 店铺列表段 |
| 2.3.1.2 | 店铺详情 | /channels/shops | D1–D2 | 已交付 | GET/PUT /channel/shops/{id} + 站点启停 + /channels 店铺详情（可改名称、经营模式、绑定连接与法人，带版本号乐观锁） |
| 2.3.2.1 | 经营模式（自发货/全托管/半托管） | — | D1–D2 | 已交付 | shop.operating_mode CHECK(SELF_FULFILLED/FULL_CONSIGNMENT/SEMI_CONSIGNMENT/…) + 页面展示 |
| 2.3.2.2 | 负责人与数据范围 | — | D1–D2 | 已交付 | shop.owner_id/department_id + channel_scope RLS(scopeMode) + POST /shops/{id}/transfer 显式转让（触发器锁字段）+ 详情页转让表单 |
| 2.4.1.1 | 同步任务列表 | /channels/sync | D1–D2 | 已交付 | channel V2 sync_cursor + GET/POST /sync-cursors + /channels 同步运行段（页面可按连接·站点·资源登记任务） |
| 2.4.1.2 | 运行日志 | /channels/sync | D1–D2 | 已交付 | sync_run + GET /sync-runs 运行日志 + 失败原因分布 |
| 2.4.1.3 | 断点续传 | /channels/sync | D1–D2 | 已交付 | 游标水位 + pause/resume/rewind 端点 + 页面暂停/恢复/回退；游标仅 SUCCEEDED 推进 |
| 2.4.2.1 | 失败队列 | — | D1–D2 | 已交付 | sync_failure + GET /sync-failures + /sync-failures/summary |
| 2.4.2.2 | 人工重放 | — | D1–D2 | 已交付 | POST /sync-failures/{id}/replay 人工重放（归属最初运行） |
| 2.4.2.3 | 映射缺失队列 | — | D5 | 未开发 | — |
| 2.5.1.1 | 回写任务列表 | /channels/operations | D1–D2 | 已交付 | platform_operation UNIQUE(tenant,idempotency_key) + GET /platform-operations |
| 2.5.1.2 | 未知结果核验 | /channels/operations | D1–D2 | 已交付 | POST /platform-operations/{id}/verify 未知结果核验（CONFIRMED_* 三选一） |
| 2.5.2.1 | 常用回写操作 | — | D5 | 未开发 | — |
| 2.6.1.1 | 接收记录 | — | D5 | 未开发 | — |
| 2.6.1.2 | 去重视图 | — | D5 | 未开发 | — |
| 2.6.2.1 | 增量补拉 | — | D5 | 未开发 | — |
| 3.1.1.1 | SPU 列表 | /products/spu | D1–D2 | 已交付 | spu 表 + GET /catalog/products + /products 商品工作区 |
| 3.1.1.2 | SPU 详情 | /products/spu | D1–D2 | 已交付 | GET /catalog/products/{id} + PUT 更新 + 商品详情段 |
| 3.1.1.3 | SPU 生命周期 | /products/spu | D1–D2 | 已交付 | V4__spu_lifecycle.sql（五态迁移 + spu_lifecycle_event/rule/rule_condition，强制 RLS）+ GET/POST /catalog/products/{id}/lifecycle、/catalog/lifecycle/rules CRUD、/preview、/run、/metrics + /products 商品详情「生命周期与批量规则」段 + SpuLifecycleIntegrationTest（11 例，含租户隔离三断言） |
| 3.1.2.1 | SKU 列表 | /products/sku | D1–D2 | 已交付 | V5__sku_list.sql（sku_status_event + sku_batch_operation/item，强制 RLS；列表/状态/条码/规格索引）+ GET /catalog/skus、/catalog/skus/capabilities、/catalog/skus/export（分批流式）+ POST /catalog/skus/batch（停售/改状态，预览+执行）+ /products/sku 工作区 + SkuListIntegrationTest（17 例，含租户隔离三断言） |
| 3.1.2.2 | SKU 详情 | /products/sku | D1–D2 | 已交付 | V6__sku_detail.sql（sku_package + sku_unit_conversion 含生效区间不重叠触发器，强制 RLS + GRANT；attribute_value/product_asset/sku_batch_operation_item 详情查询索引）+ EffectiveValues.java（公共值经 SKU 覆写/清空的有效值解析纯函数，11 例单测）+ SkuDetailService/SkuDetailController/SkuDetailProjectionPort（库存/成本/售价/listing/单据引用一律未接入标记，不落库）+ GET /catalog/skus/{id}、PUT /catalog/skus/{id}（version 乐观锁 409）+ /products/sku/[id] 详情工作区（九分区 + 四种状态 + 编辑面板）+ SkuDetailIntegrationTest（22 例，含租户隔离三断言） |
| 3.1.2.3 | 编码与条码 | /products/sku | D1–D2 | 已交付 | V7__sku_code_barcode.sql（sku_code_rule + sku_code_rule_seq 编码规则与序号分配、sku_identifier 外部条码含 UNIQUE(tenant_id,scheme,value) 与主条码部分唯一索引、sku_code_assignment 编码变更留痕、sku_code_batch/item 逐行拒绝清单；6 表强制 RLS + GRANT；触发器把主条码同步到 sku.barcode，删主条码时清空）+ SkuCodePattern/BarcodeScheme 纯规则（编码展开长度保存即校验、GTIN mod-10 校验位；seller_sku 不在 scheme 词表）+ SkuCodeService/SkuCodeController（GET/POST/PUT /catalog/sku-code/rules、overview、skus/{id}、skus/{id}/code、skus/{id}/identifiers、POST batch、GET batches/{id}、POST identifiers/import）+ /products/sku/code 工作区（概览/规则/批量/条码导入/单 SKU 条码，冲突清单逐行渲染）+ SkuCodePatternTest+BarcodeSchemeTest（14 例）+ SkuCodeIntegrationTest（28 例，含租户隔离三断言与冲突拒绝清单断言） |
| 3.1.2.4 | 单位与换算 | /products/sku | D1–D2 | 已交付 | V8__unit_conversion.sql（unit_of_measure 单位字典含量纲/数量精度/可用场景，sku_unit_conversion_snapshot 单据冻结快照 + refuse_snapshot_mutation 触发器禁止改删 + GRANT 只授 SELECT/INSERT；guard_unit_conversion_dimension 量纲守卫；复用 V6 已有的 sku_unit_conversion 生效区间不重叠触发器；2 表强制 RLS）+ UnitRules 纯规则（量纲/角色/单据类型词表、中文单位码字符集、比例与数量校验、HALF_UP 换算精度、生效区间判定）+ UnitConversionService/UnitConversionController（GET/POST /catalog/unit-conversion/units、PUT/DELETE /units/{id}、GET /overview、GET /skus/{id}、POST /skus/{id}/conversions、POST /convert、POST/GET /snapshots）+ /products/sku/units 工作区（字典/换算版本分组/换算试算/单据冻结，四种状态）+ UnitRulesTest（12 例）+ UnitConversionIntegrationTest（22 例，含租户隔离三断言、快照不可变双层断言与量纲拒绝断言） |
| 3.1.3.1 | 规格轴定义 | — | D1–D2 | 已交付 | V9__spec_axes.sql（spec_axis 租户级规格轴字典、spu_spec_axis 绑定含承载属性复合外键、spec_axis_option 选项、category_spec_axis 类目允许规则；guard_spu_spec_axis 触发器校验承载属性属本 SPU 且为 variant_axis 且 code 与轴一致、guard_spec_axis_option_source 强制规格选项必须在attribute_option 值源中（蓝图硬约束）、guard_attribute_option_in_use 阻止值源被删/改名；4 表强制 RLS + GRANT）+ SpecAxisRules 纯规则（轴编码 ASCII 词表、选项值中文词表与 varchar(100) 同长度、矩阵笛卡尔积 long 防溢出）+ SpecAxisService/SpecAxisController（GET/POST /catalog/spec-axis/axes、PUT/DELETE /axes/{id}、GET /overview、GET /axes/{id}/options、POST /axes/{id}/options、DELETE /axes/{id}/options?value=、GET /spus/{id}、POST /spus/{id}/axes、DELETE /spus/{id}/axes/{axisId}、GET/POST/DELETE /category-rules）+ /products/sku/spec-axis 工作区（概览与治理缺口/轴字典/选项与值源说明/SPU 绑定与矩阵/类目规则，加载空错误停用四态）+ SpecAxisRulesTest（9 例）+ SpecAxisIntegrationTest（27 例，含租户隔离三断言与值源硬约束正反向断言） |
| 3.1.3.2 | 组合唯一性校验 | — | D1–D2 | 已交付 | V10__variant_combination_uniqueness.sql（spu_variant_combination 组合台账 + UNIQUE(tenant_id,spu_id,signature) 唯一约束本体（蓝图硬要求）、spu_variant_combination_value 规范化轴值明细（PK 含 spec_axis_id + UNIQUE 含 axis_code，DB 层表达「一组合每轴恰好一值」）、sku_matrix_draft / sku_matrix_draft_item 待建 SKU 草稿与明细、guard_variant_combination_value 触发器（轴须绑本 SPU、轴与取值须启用、axis_code 由 spec_axis 推导不接受自报）、guard_variant_combination_lines 延迟约束触发器 DEFERRABLE INITIALLY DEFERRED（提交前复核明细行数==axis_count 且按轴编码 COLLATE "C" 重算签名一致）、guard_variant_combination_against_sku 触发器（与既有真实 SKU 签名撞车即 23505，补 V1 只管 sku 表内部的缺口）、4 表 ENABLE+FORCE RLS + isolation/data_scope 双策略 + GRANT catalog_app/starcon_app）+ CombinationRules 纯规则（签名轴编码升序拼接与 DB 侧同序、批次内重复定位到下标、缺轴/混轴区分、笛卡尔积展开与单草稿 2000 上限、matrixSize 先判再乘防 long 溢出）+ CombinationConflictException + ApiErrorHandler 结构化 409（ProblemDetail 挂 conflicts 清单，不压成一句提示）+ VariantCombinationService/VariantCombinationController（GET /catalog/variant-combination/overview、GET /spus/{id}、GET /spus/{id}/combinations、GET /spus/{id}/drafts、GET /spus/{id}/drafts/{draftId}、POST /spus/{id}/check（纯查询不写库）、POST /spus/{id}/drafts（MATRIX |
| 3.2.1.1 | 属性定义列表 | /products/attributes | D1–D2 | 已交付 | V11__attribute_dictionary.sql（spu_id 可空 + usage/filterable/enabled + NULLS NOT DISTINCT 编码唯一 + category_attribute_rule/option + RLS）；/catalog/attributes 列表分页筛选 + 概览 + 增改删 + 选项集合增删改查与停用 + 类目规则增删查；/products/attributes 工作区；AttributeDictionaryIntegrationTest 26 例（含租户隔离三断言与 3.2.2.1 回归桩）+ AttributeDictionaryRulesTest 12 例 |
| 3.2.1.2 | 属性选项管理 | /products/attributes | D1–D2 | 已交付 | V12__attribute_option_management.sql（attribute_option_enabled_list / attribute_option_attribute_lookup / attribute_option_created + category_attribute_option_reverse / attribute_value_item_option，仅索引不改列）；/catalog/attributes/options 跨属性聚合列表（按属性/启用/引用状态筛选 + 白名单排序 + 真实分页，含 deletable 合成字段与引用计数口径说明）+ PUT /catalog/attributes/options/lifecycle 启用停用 + PUT /catalog/attributes/options/order 批量重排（覆盖式、事务内逐条校验）；/products/attributes「选项管理」标签页；AttributeOptionIntegrationTest 24 例（含租户隔离三断言、引用计数如实性、排序白名单注入防护、批量重排原子性）+ attribute-option-model.test.ts 9 例 |
| 3.2.2.1 | SPU 公共属性 | — | D1–D2 | 已交付 | POST/PUT/DELETE /products/{id}/attributes + 属性录入与校验（OPTION/DECIMAL/BOOLEAN/DATE） |
| 3.2.2.2 | SKU 属性覆写 | — | D1–D2 | 已交付 | V13__sku_attribute_override.sql（attribute_value 加 updated_at 系统时间 + sku_attribute_override_lookup 部分索引 + RLS/GRANT 复核；覆写行复用 V1 的 attribute_value(sku_id 非空) + state SET/CLEAR，不另建表）；GET/PUT/PUT clear/DELETE /catalog/sku-attribute-override/skus/{skuId}[/attribute-overrides/{attributeId}[/clear]]；/products/sku/[id]/attributes 工作区（加载/空/错误三态 + 四态来源标注 + 必填补全清单 + 不可改原因）；SkuAttributeOverrideIntegrationTest 14 例（四态解析与显式清空≠未设置、取消覆写回落且幂等、四类型与启用中选项校验、COMMON_LOCKED/已停用可改写性、类目必填阻止并回传清单、权限 403、审计与 sku.version 不变、租户隔离三断言） |
| 3.3.1.1 | 分类树管理 | /products/categories | D1–D2 | 已交付 | V14__category_tree.sql（product_category_tree/product_category/product_category_name/spu_category，强制 RLS + GRANT；防环触发器 guard_product_category_cycle + 子树 path 同步；category_attribute_rule / category_attribute_option / category_spec_axis 加 category_id 并做自由文本数据迁移后收口 NOT NULL）+ GET /catalog/categories/overview、/trees、/trees/{treeId}/nodes，POST /trees、/trees/{treeId}/nodes，PUT /nodes/{id}、/nodes/{id}/move、/nodes/{id}/enabled、/spus/{spuId}，DELETE /trees/{id}、/nodes/{id} + /products/categories 页面（CategoryTreeWorkspace：树切换 / 概览 / 节点树 / 新建 / 编辑 / 移动 / 启用停用 / 删除，含加载态·空态·错误态）+ CategoryTreeIntegrationTest（15 例，含租户隔离三断言与防环断言）与 category-tree-model.test.ts（17 例）+ api-proxy 放行 categories 前缀（含放行/拒绝用例） |
| 3.3.1.2 | 类目属性要求 | /products/categories | D1–D2 | 已交付 | V15__category_attribute_requirements.sql（category_attribute_rule 加 requirement/condition_attribute_id/condition_operator/max_length/allow_free_text/enabled/version/updated_at；新建 category_attribute_condition_value；sync_category_attribute_rule_requirement 触发器让 requirement 为权威列、required 为派生列防两列漂移；guard_category_attribute_condition_value 拒绝条件取值挂错前置属性；(tenant_id,category_id,attribute_id) 唯一索引 + 强制 RLS/GRANT）；GET /catalog/categories/attribute-requirements/overview、/{categoryId}、/{categoryId}/effective，POST /{categoryId}、/{categoryId}/entry-form-schema、/{categoryId}/validate，PUT /{categoryId}/{attributeId}/enabled，DELETE /{categoryId}/{attributeId}；/products/categories/requirements 页面（CategoryRequirementsWorkspace：类目下拉 / 生效规则集含继承来源标注 / 新增·编辑·启停·删除 / 录入表单 schema 预览 / 保存前逐字段校验，加载·空·错误三态）+ 分类树页面入口链接；继承语义为「按属性逐条就近覆盖（nearest-wins），停用最近那条即豁免祖先要求」，表单 schema 与保存前校验共用同一条件求值器；CategoryAttributeRequirementIntegrationTest 12 例（含租户隔离三断言、继承与豁免语义、条件求值四分支、表单 schema 依赖排序、逐字段校验码、平台树拒绝、与 3.2.1.1 写入路径的列同步）+ CategoryAttributeRequirementRulesTest 11 例 + category-requirements-model.test.ts 15 例 + api-proxy 放行/拒绝 2 例 |
| 3.3.2.1 | 品牌 | /products/brands | D1–D2 | 已交付 | V16__brand_management.sql（brand 字典本体 code 租户内唯一 / name / logo_uri / authorization_status 枚举 OWNED·AGENCY·LICENSED / enabled / version；brand_name 多语言补充名主键 (tenant_id,brand_id,locale) 挡同语言两条；brand_authorization 授权凭证 certificate_no 品牌内唯一 + grantor + valid_from/valid_to date + evidence_uri + enabled，一个品牌多条并存按各自有效期判定，撤销即 enabled=false 行仍保留；brand_category 关联类目走复合外键指向 3.3.1.1 的 product_category(tenant_id,id) 不复刻类目表；四表均 tenant_id NOT NULL + 强制 RLS isolation + catalog_app/starcon_app GRANT + 5 条索引）；GET /catalog/brands（分页 + 关键词覆盖编码/名称/多语言名 + 授权状态/启用/刊登可行性/类目筛选，LIKE 通配符 escape 转义）、/overview、/candidates、/{id}、/{id}/authorizations、/{id}/listing-eligibility，POST /catalog/brands、/listing-eligibility（批量，上限 500）、/{id}/authorizations，PUT /{id}、/{id}/enabled、/{id}/authorizations/{authId}、/{id}/authorizations/{authId}/enabled，DELETE /{id}?version=；/products/brands 页面（BrandWorkspace：概览四项指标含「不可刊登数」/ 分页搜索筛选列表每行带刊登判定与原因 / 品牌编辑含多语言名与类目多选整体替换 / 凭证维护含有效期与撤销 / 刊登与合规引用候选区块，加载·空·错误三态且两种空态文案不同）+ brand-model.ts；蓝图业务规则「需授权但无有效凭证时阻止刊登」落成 BrandRules.publishBlock 单一实现 + 三个出口（列表行/详情/单个与批量判定端点），四条原因码 BRAND_DISABLED·LICENSE_MISSING·LICENSE_NOT_YET_EFFECTIVE·LICENSE_EXPIRED（区分未生效与已过期是因为 04 刊登与 16 侵权检测处理动作不同），判定基准日 UTC 当天并回带 asOf；建档不要求凭证（被拒的是刊登不是建档）、撤销不是删除、编码建后不可改、列表 SQL 判定与规则类判定逐字同口径；BrandIntegrationTest 15 例（含租户隔离三断言、阻止刊登四分支与状态改判立刻生效、跨模块关联类目重命名跟随、凭证不随类目删除级联、分页与 LIKE 转义、HTTP 全链路与权限）+ BrandRulesTest 10 例 + brand-model.test.ts 32 例 + api-proxy 放行 1 例 |
| 3.3.3.1 | 计量单位 | /products/units | D1–D2 | 已交付 | V17__measurement_units.sql（unit_group 单位组：code 租户内唯一 / name / dimension 与 V8 同一量纲词表 / base_unit_code 复合外键指向 unit_of_measure(tenant_id,code) / note / enabled / version；unit_group_member 组成员主键 (tenant_id,group_id,unit_code) + unit_code 复合外键指向字典 + position；unit_group_conversion 组内换算版本 quantity_per_base numeric(19,6) + UNIQUE(tenant_id,group_id,unit_code,effective_from) + 复合外键指向组成员保证「换算行必是组内成员」+ 生效区间 CHECK；四个守卫触发器 guard_unit_group_dimension（建组时基准单位量纲要等于组量纲）/ guard_unit_group_member_dimension（每次加成员再判量纲）/ refuse_unit_group_base_removal（组基础单位不可移出组）/ guard_unit_group_conversion_dimension（换算版本再判量纲且基准单位不占行）+ validate_unit_group_conversion_window（区间不重叠，同 V6 形状）；6 条索引含部分索引 unit_group_conversion_open；三表均 tenant_id NOT NULL + ENABLE/FORCE RLS isolation 策略 + catalog_app/starcon_app GRANT）；GET /catalog/unit-groups（分页 + 关键词覆盖编码/名称/基准单位/成员单位名 + 量纲/启用筛选，LIKE 通配符 escape 转义）、/overview（十个指标含未归组单位数与缺生效版本组数 + 按量纲统计）、/{id}?asOf=（组本体 + 成员带当天生效比例 + 换算版本按成员与生效起始日倒序）、POST /convert；POST /catalog/unit-groups、/{id}/conversions，PUT /{id}、/{id}/enabled、DELETE /{id}?version=、/{id}/conversions/{conversionId}（只删已闭合的历史版本）；换算查询返回 200 + denied=true + 七条原因码 SELF_CONVERSION·UNIT_UNREGISTERED·UNIT_DISABLED·GROUP_DISABLED·DIMENSION_MISMATCH·NOT_SAME_GROUP·NO_EFFECTIVE_VERSION 而非 HTTP 错误，判定顺序从最具体到最笼统且量纲守卫先于组内判断；/products/units 页面（UnitGroupWorkspace：概览四项指标含未归组单位与缺生效版本组 / 单位字典维护复用 3.1.2.4 写入端点 / 单位组分页搜索筛选 / 组详情含成员与历史版本 / 组内换算版本新增与删除 / 换算试算含原因码与处理动作，加载·空·错误三态且两种空态文案不同）+ unit-group-model.ts；蓝图硬约束「量纲不匹配的单位之间不允许换算」双层兑现：应用层给可执行的人话、DB 层三个守卫触发器兜底，集成测试直连 SQL 证明绕过应用层也写不进跨量纲数据；UnitGroupIntegrationTest 19 例（含租户隔离三断言、DB 层量纲守卫、七条原因码、基准单位迁移、区间不重叠、RLS 与 numeric(19,6) 表结构断言）+ UnitGroupRulesTest 11 例 + unit-group-model.test.ts 27 例 + api-proxy 放行 1 例 |
| 3.4.1.1 | 媒体库 | /products/media | D1–D2 | 已交付 | V18__media_library.sql（六张新表，**不复用也不改 product_asset**：那张表是 V1 的 `LIKE product_research INCLUDING ALL` 同构复制，实测有两个已交付模块的活读者——`CatalogService.table()` 把 assets 映射到它（/catalog/records/assets 与 /products/assets 在读，CatalogIntegrationTest 对 research/compliance/assets 三种 kind 都有断言）、`SkuDetailService.media()` 读它的 title/notes/reference 给 SKU 详情页媒体区（SkuDetailIntegrationTest 断言能取到 title）；那条记录的形状与媒体元数据无任何交集，为拆表重写两个已交付模块的写入所有权与测试即 V9/V11/V13/V14/V16/V17 反复强调的重开旧账，故原样保留、媒体库走全新六表）；media_asset 素材本体（name/description/media_type IMAGE·VIDEO/purpose/file_format/mime_type/byte_size·width_px·height_px·duration_seconds 统一 numeric(19,6)/content_sha256/storage_key/original_filename/alt_text/current_version + 失效检测 integrity_status 五态 UNKNOWN·VERIFIED·MISSING·SIZE_MISMATCH·CONTENT_MISMATCH 与独立的 integrity_checked_at/启用停用/version 乐观锁，UNIQUE(tenant_id,content_sha256) 即内容寻址复用、UNIQUE(tenant_id,storage_key)、图片必须探测出宽高的 CHECK）；media_asset_version 版本历史（UNIQUE 版本号，旧版永不删，3.4.1.2「原图始终保留」的承载表）；media_tag + media_asset_tag 租户级标签字典；media_reference 引用计数（owner_type 仅 SPU/SKU，复合外键指向 spu/sku 保证跨租户引用不可能，CHECK 约束一行只指向一个对象，coalesce 折叠 NULL 的表达式唯一索引 media_reference_dedupe 去重——普通 UNIQUE 在这里挡不住「两条都指向同一 SPU」因为 PG 的 UNIQUE 视 NULL 互不相等）；media_integrity_check 校验历史（不覆盖「什么时候发现文件不见了」这个要回溯的事实）；四个触发器：sync_media_asset_from_version（AFTER INSERT，唯一写入路径，把素材本体同步成该版本的元数据）、guard_media_asset_current_version（AFTER UPDATE 行级，绕过写入路径直接改媒体元数据时撞下来；行级而非语句级——素材上千行时全表扫会让每次写入退化成 O(n)；AFTER 而非 BEFORE——BEFORE 看到的还是 OLD 行会误判）、refuse_media_asset_delete（有引用不可删，DB 层关掉「判完之后、删除之前被并发挂引用」的窗口）、refuse_media_tag_delete（仍被使用的标签只能停用不能删）；六表均 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + catalog_app/starcon_app GRANT，不套 V3 的 owner_id/department_id 数据范围（素材库是租户共享资产库，同一运营要在别人的素材上复用）；MediaStorage 端口 + LocalMediaStorage 实现（二进制不落业务库，六表零 bytea/oid 只存 storage_key，根目录由 MEDIA_STORAGE_ROOT 配置、含路径穿越防护、换 S3/OSS 只需换实现类）；MediaRules 纯规则层（内容探测：格式按字节签名判定而非文件扩展名、宽高用 ImageIO 从字节读、SHA-256 实算；视频宽高与时长**不探测**——需要解容器而 JDK 没有，与其写只覆盖 MP4 的半吊子解析器（对 WEBM 会给错秒数）不如留空，留空的语义是「未知」不是 0 秒；WEBP 明确拒收并给出可执行原因）；GET /catalog/media（分页 + 关键词覆盖名称/描述/原始文件名/存储键/标签 + 类型/用途/标签/完整性/引用状态/启用筛选，每行自带引用计数与版本数所以删除保护在列表上就看得见）、/overview、/references（反向查「这个 SPU/SKU 用了哪些素材」）、/{id}（本体+版本+标签+引用+校验历史+删除保护判定同一快照）、/{id}/versions、/{id}/references、/{id}/integrity-checks、/tags；POST /catalog/media（multipart，**格式/宽高/时长/哈希一律从字节探测不接受填写**；同内容复用：同租户相同 sha256 返回既有素材带 deduplicated=true，不新建行也不写第二份对象）、/{id}/versions（版本号由服务端取 max+1，不接受指定）、/{id}/references、/{id}/verify、/verify-sweep（单次最多 200）、/tags；PUT /{id}、/{id}/enabled、/tags/{tagId}、/tags/{tagId}/enabled；DELETE /{id}?version=（无引用才删，连带清掉所有版本对象不留孤儿文件）、/{id}/references/{referenceId}、/tags/{tagId}；读 catalog.assets.read 写 catalog.assets.update（沿用 V3 已有的素材域词表）；失效检测真的把对象读回来算 SHA-256 而非信任元数据登记的哈希，读失败报 503 而不是记成 MISSING（否则运维会去清理一个其实还在的文件）；/products/media 页面（MediaWorkspace：概览含缺失数与无引用数 / multipart 上传 / 列表分页搜索六维筛选 / 详情九区块含版本历史·引用清单·校验历史 / 标签字典带使用数 / 批量巡检，加载·空·错误三态，刻意区分「没探测」与 0、四种完整性坏法给不同处理动作、删除保护前置到列表行）+ media-model.ts （上传不走 api() helper：它无条件设 Content-Type: application/json，multipart 必须让浏览器自带 boundary）；MediaIntegrationTest 16 例（含租户隔离三断言、真实 PG16 + 真实文件系统存储、同内容复用与跨租户不复用、版本累积与旧版存活、绕过服务层改元数据被触发器拒、引用计数与删除保护（含 DB 层并发窗口）、重复引用与跨租户目标被拒、删文件后验出 MISSING·同尺寸覆盖后验出 CONTENT_MISMATCH、校验历史不覆盖、标签在用不可删）+ MediaRulesTest 10 例 + media-model.test.ts 24 例 + api-proxy 放行。开发中实测修掉的真缺陷：media_tag 多写的表级 CHECK(btrim(name)<>'') 被 PG 解析成「小于 varchar(100) 字面量」导致任何标签名都插不进去；版本一致性触发器与版本行外键的循环依赖；守卫语句级全表扫退化成 O(n)；存储健康探针用固定键导致多租户随机报「不可写」；assetRow 用 select * 取不到派生列报「Field not contained in row type」。 |
| 3.4.1.2 | 图片处理 | /products/media | D1–D2 | 已交付 | V19__image_processing.sql（五张新表；`media_process_recipe` 处理配方（目标平台规格：去背景 remove_background+background_tolerance / 白底 white_background / 水印 watermark_text+position+opacity / 尺寸裁剪 target_width_px·target_height_px+crop_mode(FIT·CROP_FILL) / 格式转换 target_format（仅 png·jpeg·gif·bmp，WEBP 因 JDK 无写入器在 CHECK 层拒）/ target_platform 为自由文本不跨服务建 FK，模块边界纪律）；`media_process_recipe_ratio` 多比例档（UNIQUE(tenant_id,recipe_id,label)：档内标签唯一，否则产物表里会出现两行无法区分的「主图」）；`media_process_job` 异步任务（status 六态 PENDING·RUNNING·SUCCEEDED·PARTIAL·FAILED·CANCELLED、asset_count 提交时写死、cancel_requested 只存请求不等落任务、业务时间 created_at 与系统时间 started_at/finished_at 分开存——长任务里两者能差很久）；`media_process_job_item` 逐素材结果（提交时快照 source_version·source_storage_key·source_sha256：批量跑到一半素材被换新版本时，处理对象必须仍是提交那一刻的字节，否则同一任务的产物来源前后不一致且再也解释不清；error 是可执行中文原因不是异常类名；UNIQUE(tenant_id,job_id,asset_id) 挡住重复提交导致同一素材产出两套版本）；`media_process_output` 产物溯源（ratio_label + from_version→to_version，CHECK(to_version>from_version) 把「产物不可能是原图自己」钉进数据）；三个触发器把蓝图「处理结果不可逆覆盖原图、原图始终保留」钉在 DB 层：guard_media_asset_version_immutable（BEFORE UPDATE，版本行只允许 INSERT——连改 note 都不给口子，否则「不可逆」能被慢慢绕过）、refuse_media_process_source_version_delete（区分「素材整体删除的级联」与「直接删某个版本行」：父 media_asset 行还在且该版是处理来源/产物即拒；用父行是否存在来判别级联，让 V18 的删素材路径原样可用）、refuse_media_process_recipe_delete（被任务引用的配方只能停用不能删：删配方会级联删比例档，而档是历史产物的口径来源，产物表存的是 label 文本）；五表均 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + catalog_app/starcon_app GRANT，不套 V3 的 owner_id/department_id 数据范围（同 3.4.1.1：素材库是租户共享资产库）；二进制仍不落库：五表零 bytea/oid，产物走 3.4.1.1 的 MediaStorage 端口写对象，库内只存 storage_key；ImageProcessor 像素加工引擎（纯函数、输入输出都是字节，可不依赖 Testcontainers 单测）：去背景=四角泛洪+整块 int[] 访问标记（实测修掉真缺陷：seed 走 seed 而非 offer，漏掉置透明会让四个角永远留着原背景色）、白底=AlphaComposite 合成到纯白画布、水印=SANS_SERIF 按图幅比例取字号并按实测文本宽度收窄（固定字号在缩略图上糊成一条；长文案超出画布等于没加水印）、尺寸裁剪=FIT 等比补白边/CROP_FILL 填满居中裁掉溢出、格式转换=ImageIO 写出并按格式而非按像素决定是否换色彩模型（实测修掉真缺陷：JDK 的 JPEG/BMP 写入器遇到 TYPE_INT_ARGB 即使全不透明也返回 false）、多比例=服务层对每档各调一次 resize+write，每档一行 media_process_output 即原素材一个新版本；ImageProcessExecutor 异步执行器（真实线程池 PARALLELISM=2，并行度不为 CPU 核数——像素加工内存密集，4000×3000 ARGB 单张就 48MB；一个任务同时只有一个工作线程在跑，靠 markRunning 的 `where status='PENDING'` 原子抢占。实测修掉真缺陷：早期版本让两线程各等对方处理完再收尾，而测试把连接池固定成 1 条连接时互相等对方让出连接，直接死锁、整个测试挂住；像素加工在事务外做、只有写对象+插版本行+更新计数进事务；取消在领取条目之前检查。实测修掉真缺陷：先领后判的话，那条已标 RUNNING 的条目既不会被处理也不会被落成 CANCELLED，finish 永远看到一条 RUNNING 而拒绝落终态，任务卡死在「处理中」；单素材失败记 FAILED+原因继续下一条，不整批回滚——200 张里 3 张不合法，用户要的是「哪 3 张、为什么」；工作线程按任务自己记录的 tenant_id 开事务，因为 set_config 是事务内有效、FORCE RLS 下无租户上下文查不到任何行）；ImageProcessRules 纯规则层（词表与 V19 CHECK 逐字一致、容量上限 MAX_ASSETS_PER_JOB=500 / MAX_RATIOS_PER_RECIPE=8 / MAX_OUTPUT_PIXELS=80MP 且在分配画布之前拒绝，否则先分配再失败会连带整批回滚、targetSizeProblem 要求宽高同填或同空、backgroundFormatProblem 与 V19 那个 CHECK 同语义）；GET /catalog/media/processing/overview、/recipes、/recipes/{id}、/assets/{assetId}/history、/jobs、/jobs/{jobId}、/jobs/{jobId}/items；POST /catalog/media/processing/recipes（比例档整体替换：档位集合是「这批产物长什么样」的完整口径，增删式会让「当前有哪些档」有两个答案）、/jobs（异步受理语义：写完任务与条目即返回、毫秒级，像素加工在线程池里跑）、/jobs/{jobId}/cancel（不阻塞，只置标志位）；PUT /recipes/{id}、/recipes/{id}/enabled；DELETE /recipes/{id}?version=；读 catalog.assets.read 写 catalog.assets.update（沿用 V3 已有素材域词表，不新造）；/products/media/processing 页面（ImageProcessWorkspace：概览含进行中/部分失败/产物文件 / 处理配方列表与六项能力编辑器 + 多比例档编辑 / 素材勾选提交（只列图片，视频不参与本模块处理）/ 任务列表分页搜索与状态筛选 / 任务详情进度条 + 逐素材成功失败原因 + 每个产物的「基于 vN 生成 → vM」；轮询只在 PENDING·RUNNING 时进行，终态继续轮就是永不停止的请求循环；加载·空·错误三态；反复强调原图保留是因为不写清楚运营看到版本号前进会以为原图丢了于是再传一遍，素材库里就多一份重复内容）+ image-process-model.ts（像素指标不在前端算：产物尺寸/字节数/哈希一律来自后端；submittedHint 必须写明异步受理与原图保留；进度分母为 0 返回 0 而不是 NaN）+ api-proxy 放行；ImageProcessingIntegrationTest 12 例（真实 PG16 + 真实文件系统 + 真实线程池，无任何内存替身；含租户隔离三断言：两租户配方/任务/条目/处理历史互不可见、伪造 X-Tenant-Id 无效（写进的是凭据说的那个租户）、连接池单连接反复交替不串；六项能力逐项断言像素真的变了：产物字节落盘可解码、尺寸按档裁到位、JPEG 产物验 SOI(FF D8) 标记而不是换扩展名的 png、FIT 补白边 vs CROP_FILL 中央即主体、去背景+白底后无透明像素、水印区域亮度显著高于底色；原图始终保留的 DB 层证明：产物落新版本（to>from，V19 CHECK 强制）、current_version 前进、v1 版本行仍在、磁盘上的原图对象仍存在且哈希与 v1 登记一致、绕过服务层 UPDATE 版本行被触发器拒（连改 note 都不行）、直接 DELETE 被处理引用过的来源版本被拒、而未被处理引用过的版本可以删（守卫不做一刀切）；异步与进度：提交只写元数据就返回、排队期条目可查、跑完进度是库里可查的事实（started_at≤finished_at）、每条目 start/finish 落库、真走线程池 enqueue 后轮询到终态且产物确实由工作线程写出、取消 40 个素材的批次验证「没有条目被落在中间态」+ 成功+取消=总数 + 取消项无产物且有原因 + 已完成项产物不回滚、部分失败 PARTIAL 保留失败原因而好图照常处理出 v2）+ ImageProcessorTest 9 例（纯像素单测，不依赖 PG）+ ImageProcessRulesTest 10 例 + image-process-model.test.ts 20 例 + api-proxy 放行；开发中实测修掉的真缺陷：泛洪种子漏置 alpha 导致四角抠不掉；按像素而非按格式判断色彩模型导致不透明 ARGB 写 jpeg/bmp 直接失败；SELECT_SOURCE 已带 where a.tenant_id=? 又拼 where a.id=? 的 SQL 语法错；afterCommit 里查 tenant_id（set_config 事务内有效，此时已被清空，FORCE RLS 直接藏行导致空指针）；media_process_job 没有 updated_at 列（只有 created_at/started_at/finished_at 三段时间轴）却被 cancel/markRunning 更新；missing 逗号导致的 SQL 语法错；两线程各等对方的连接池死锁（改为单任务单主人 + sleep 移出事务）；取消先领后判把条目卡在 RUNNING 导致任务永不落终态；cancelRequested 写死 false 让「取消已受理」在响应里显示成没取消。 |
| 3.4.2.1 | 标题库 | /products/content | D1–D2 | 已交付 | V20__title_library.sql（七张新表；`title_template` 标题模板（pattern 为带占位符的模板文本，字面量与占位符写在同一串里所以不需要另设分隔符列、空白形态规范化后入库避免「同一模板写成两种空白」、max_similarity_permille CHECK(<=999) 而不是 <=1000——1000‰ 就是允许完全一样等于没有重复度控制，candidate_count 1~8）；`title_template_variable` 变量声明子表（slot_no 是渲染顺序即「相似但不雷同」的词序轮转轴，UNIQUE(tenant_id,template_id,variable_name) 挡住一个占位符两个绑定源、UNIQUE(tenant_id,template_id,slot_no) 挡住两个第 0 位，source 九值枚举：SPU_BRAND·SPU_CATEGORY·SPU_NAME·SKU_NAME·SKU_CODE·SKU_SPEC 六种事实字段 + KEYWORD_CORE·KEYWORD_SELLING·KEYWORD_MODIFIER 三种词池）；`title_template_platform` 模板的各平台标题长度上限（**按平台而不是按模板一个值**：eBay 80 / 亚马逊 200 / Shopify 255 口径不同，批量会同时发给多个平台；模板没配的平台直接被拒而不是悄悄退回默认值——那会让运营以为发到 eBay 的标题没超长）；`title_keyword` 标题词根字典（keyword_type 三类 CORE 核心词 · SELLING 卖点 · MODIFIER 修饰词，UNIQUE(tenant_id,keyword_type,text) 挡住同一个词写成两条——那会让词池「看起来更大」而生成的候选还是那几种、重复度控制被绕过而没人发现）；`title_generation` 批量生成批次（status 三态 SUCCEEDED·PARTIAL·FAILED：**同步执行没有「排队中」这种自欺欺人的状态**——标题生成是纯字符串计算，重复度比较范围收在单个 SKU 内所以几百个 SKU 只是几十毫秒，与 3.4.1.2 的异步像素加工不同，做成异步只会让运营为几百毫秒去盯进度条；sku_count 提交时写死、keyword_filter 词根过滤回显、业务时间 created_at 与系统时间 finished_at 分开存、error 是可执行中文原因不是异常类名）；`title_generation_sku` 逐 SKU 结果（outcome GENERATED·REJECTED + reason；一个 SKU 的多个平台合并成一行，否则「这个 SKU 产出几条」要跨行加总才能回答；UNIQUE(tenant_id,generation_id,sku_id) 挡住重复提交产出两套候选）；`title_record` 生成的标题候选（UNIQUE(tenant_id,generation_id,sku_id,platform,variant_no) 让同序号不可能有两条、**部分唯一索引 title_record_one_selected(tenant_id,sku_id,platform) where status='SELECTED'** 挡住同一 SKU 同平台被选用两条（两个平台上架的是同一个商品，两条都「已选用」意味着 04 不知道该发哪条；切换选用的两步必须先降级旧的再置新的，否则撞索引）、similarity_permille 记「这条与本 SKU 已有标题的最大相似度」即它离重复铺货判定有多远、char_length 按**码点**算（平台上限是按人看到的字数定的，用 UTF-16 char 数会把 emoji 与 CJK 扩展 B 算成两个字）、truncated 标记是否被平台上限裁过、core/selling/modifier_keyword 三列是**词根快照**（改了词库仍能解释这条当时用了哪个词）、created_at/updated_at 业务时间与 updated_at 分开、version 乐观锁）；四个触发器：guard_title_record_selected_immutable（BEFORE UPDATE，逐列比较 title·platform·sku_id·template_id·generation_id·char_length·truncated，**已选用的只允许改 status**——04 会照它刊登，改了就让库里记的和平台上已发布的对不上；用列级比较而不是「SELECTED 后整行冻结」是因为取消选用是正常业务动作）、refuse_selected_title_record_delete（已选用不可删：listing 会失去溯源；未选用的候选可以删，守卫不做一刀切）、refuse_title_template_delete（被批次引用的模板只能停用不能删：删模板会级联删变量与平台上限，而历史候选是按那套上限裁出来的）、refuse_title_keyword_delete（被标题引用过的词根只能停用：title_record 快照的是**文本**，词库行删了那文本就成了没有出处的孤儿）；七表均 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + catalog_app/starcon_app GRANT，不套 V3 的 owner_id/department_id 数据范围（同 3.4.1.x：文案资产是租户共享库，同一运营要在别人的商品上复用标题模板）；与 3.4.2.2 描述与卖点库的边界写进文件头：3.4.2.2 管**长文本**（详情描述模板、卖点短语库、多语言版本，无字符上限），本模块的 title_keyword 是**标题用的短词根**（≤60 字符，服务于「标题里放哪几个词」）；两者排版约束不同（标题有硬字符上限、详情没有），把长文本卖点库塞进标题变量只会让长度控制失去意义；平台是自由文本不跨服务建 FK（模块边界纪律），多语言标题归 3.4.3.1（"同一语言内不雷同"与"跨语言是否算重复"是两套判定）；TitleRules 纯规则层（**相似度算法**、词表与 V20 CHECK 逐字一致、容量上限 MAX_SKUS_PER_GENERATION=200（不是算力上限而是**可用性**上限：2000 个 SKU × 3 平台 × 5 候选 = 3 万条标题，候选集页面根本没法用）/ MAX_PLATFORMS_PER_GENERATION=5 / MAX_CANDIDATES_PER_SKU=8（超过 8 条后候选开始出现凑数的，而凑数出来的候选恰恰是重复铺货判定最爱抓的）/ MAX_ATTEMPTS_PER_CANDIDATE=24 / MAX_SIMILARITY_PERMILLE=999、patternProblem 要求至少一个占位符、variableBindingProblem **双向**检查占位符与变量声明（少一个会在渲染时静默留下 {brand} 字面量，多一个是死配置让模板含义与实际渲染对不上）、needsKeywordVariableProblem 要求至少一个词根变量（只绑事实字段的模板每个 SKU 只有一种文本，候选差异只能来自词序轮转而上限就是变量个数）；TitleGenerator 纯函数生成引擎（不碰数据库/时钟/随机数，**同样输入永远同样输出**，所以「重复度已受控」可被逐条断言；两条轴：词序轮转 rotation=t%变量个数（**只转变量不转字面量**——字面量里含分隔符，连它一起转会让「{brand} - {core}」变成「- {core}{brand}」读起来是坏的、平台判定上也更怪）+ 三类词池下标错开取（t、t+1、t+2，**三条轴必须互相错开**否则三条轴同步轮转时每 n 轮重复一次同样的组合）；**拒绝也是一种输出**：找不到过闸组合时返回带原因的 Rejection 而不是放宽阈值凑一条雷同的，「这个 SKU 只产出 2 条」因此是诚实且可行动的结果（去加修饰词）而不是被掩盖的重复）；**相似度比较范围限定在单个 SKU 内**（该 SKU 已有 + 本次新生成）：平台判重复铺货看的是同一商品在不同站点/店铺的标题是否雷同，而两个不同商品标题相似是品类共性（「收纳盒」），判重复铺货时反倒不会被拿来对质；范围收在单 SKU 也让计算量有界，比较对数与「本 SKU 的候选数」平方成正比而与批次 SKU 总数无关，所以 200 个 SKU 一次生成仍是毫秒级——这正是「同步不做异步」的技术依据）；TitleService（jOOQ 显式 SQL、SKU 范围与词池在提交时快照（批量跑到一半商品改名/词库被改，这一批候选的口径仍必须是提交那一刻的）、平台必须都在模板里配了长度上限否则提交时就拒（先查齐再渲染：用户点完提交才发现「8 个平台里 3 个没配」体验很糟）、overview 的 maxSimilarityPermille 取所有启用模板里**最严**的（最小值）、streamCsv 每批在流式线程里用 access.run 重新开租户事务（流式线程的连接上没有 app.current_tenant，FORCE RLS 会把全部行藏起来）、CSV 按 RFC 4180 转义且前导 =+-@ 加单引号切断 Excel 公式执行）；TitleController 权限读 catalog.spu.read 写 catalog.spu.update（沿用 V3 已有 spu 词表：标题是挂在 SKU 上的商品文案、上游是 3.1.1.1 的 SPU/SKU，不新造——新词表要改 identity 服务的迁移属于另一个服务的写入所有权）；GET /catalog/titles/overview、/templates、/templates/{id}、/keywords、/generations、/generations/{generationId}、/generations/{generationId}/skus、/records、/records/export（CSV 走 GET+query，导出结果与当前筛选完全一致）；POST /templates（变量与平台上限**整体替换**：增删式会让「当前有哪些变量」有两个答案）、/keywords、/generations（**「已生成」而不是「已受理」语义**：返回时候选已落库）、/records/{id}/select |
| 3.4.2.2 | 描述与卖点库 | /products/content | D1–D2 | 已交付 | V21__description_library.sql（八张新表：`content_template` 内容模板（content_type 四值 DESCRIPTION/INSTRUCTION/CAUTION/SELLING_POINT——蓝图的四类内容共用一张表，差别只在标签与展示位置，拆成四张表会让变量、翻译、填充三套机制各写四遍）；`content_template_variable` 变量声明子表（UNIQUE(tenant_id,template_id,variable_name) 挡一个占位符两个绑定源、UNIQUE(tenant_id,template_id,slot_no) 挡两个第 0 位，source 十一值枚举：SPU_BRAND·SPU_CATEGORY·SPU_NAME·SKU_NAME·SKU_CODE·SKU_SPEC 六种事实字段 + SKU_SIBLING_LIST 同 SPU 型号清单（使用说明的「适配型号」那行）+ PHRASE_SELLING·PHRASE_FEATURE·PHRASE_SCENE·PHRASE_CAUTION 四类短语池）；`content_template_translation` 多语言版本（**UNIQUE(tenant_id,template_id,locale)**：同一语言两条译文时04 刊登按 (模板,语言) 取段不知道取哪条；origin 三值 MANUAL·AI·IMPORTED 标记「18 的 AI 翻译预填」，status 两值 DRAFT·REVIEWED，**AI 预填不自动变 REVIEWED**——机器译文最典型的失败是占位符被改写或漏掉）；`content_phrase` 卖点短语库（phrase_type 四类、text ≤ 2000 字符长句可含标点换行、UNIQUE(tenant_id,phrase_type,text) 挡同一句写成两条）；`content_render` 批量填充批次（status 三态 SUCCEEDED·PARTIAL·FAILED，**判据是「有没有产出正文」而不是「变量是否全有值」**——「有正文但缺 brand」报成 FAILED 会让运营以为要重来，实际补上 brand 就能用；business created_at 与系统 finished_at 分开存）；`content_render_sku` 逐 SKU 结果（outcome 三值 RENDERED·INCOMPLETE·REJECTED，一个 SKU 的结果合成一行，UNIQUE(tenant_id,render_id,sku_id) 挡重复提交）；`content_render_section` 填充出的段落（**蓝图输出「填充后的描述文本（分语言）」的落地表**、也是 04 刊登与 15 客服引用的东西；used_phrases jsonb 快照实际用到的短语文本——改短语库后仍能解释「这段当时填了什么」，用 jsonb 而非逗号分隔 text 是因为短语可含换行、任何分隔符都能被正文里的同名字符伪造出来把快照切错）；`content_render_issue` **缺失变量报告**（蓝图硬要求「变量缺失必须在填充时报告，不能静默留下占位符」的落地表，一行一个变量，UNIQUE(tenant_id,section_id,variable_name) 挡同一段里重复报告）；**「不能静默留下占位符」的五处 DB 落点**（只靠服务层自觉的话一次手工 SQL 就能绕过）：① CHECK (status<>'COMPLETE' OR text NOT LIKE '%{%') 声称填好就不许有花括号；② CHECK (status='COMPLETE' OR char_length>0) 声称填好必须有正文；③ guard_content_render_section_complete 触发器 INCOMPLETE→COMPLETE 被拒（缺掉的变量不会因为改状态就回来，要变成 COMPLETE 只能重跑填充）；④ guard_content_render_section_text 触发器逐条兜底，正文有残留占位符直接报错（与 CHECK 互为纵深，CHECK 可被 NOT VALID 绕过而触发器不行）；⑤ 缺失逐变量落表而不是塞进批次的一句 error——只有这样才能回答「哪 30 个 SKU 因为哪个变量没填上」，issue_kind 四值 EMPTY_FACT·EMPTY_PHRASE_POOL·TRANSLATION_MISSING·UNBOUND_PLACEHOLDER 分开报（四种修法完全不同：补字段 / 建短语 / 补译文 / 补绑定，混成一句话运营看到「填充有问题」却不知道该改哪）；另四个触发器：refuse_content_template_delete（被批次引用的模板只能停用）、refuse_content_translation_delete（段落快照的是填充当时的正文，删译文行后「这段是哪一版译文填的」就答不上来）、refuse_content_phrase_delete（段落快照短语文本，删库行那段话成孤儿）；八表均 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + catalog_app/starcon_app GRANT，不套 V3 的 owner_id/department_id 数据范围（同 3.4.1.x/3.4.2.1：文案资产是租户共享库）；trigram 索引给短语删除守卫扫段落正文用（长文本用不上普通 btree）；与 3.4.2.1 的边界写进文件头：那边管**有平台硬字符上限的短文本**（eBay 80/亚马逊 200/Shopify 255，矛盾是重复铺货判定、title_keyword 是 ≤60 字符短词根），本模块管**无字符上限的长文本**（正文上限 20000 vs 标题模板 2000，矛盾是变量缺失必须报告与多语言一致性），把长文本卖点塞进标题变量只会让标题长度控制失去意义，所以不建 title_* 表也不复用 title_template）；DescriptionRules 纯规则层（**不要求至少一个占位符**——「请勿进水」这种固定注意事项完全成立，与 3.4.2.1 的 patternProblem 刻意相反；但花括号必须配平，漏写右括号会让解析吞掉后面一整段；变量声明与占位符**双向**校验，源语言没声明绑定源的占位符在保存时就拒；translationPlaceholdersProblem 译文占位符集合必须与源语言**相等**（三个方向都查，正是 AI 预填最典型的漏一个/多一个/拼错三个失败模式）；MAX_SKUS_PER_RENDER=200 是**可用性**上限不是算力上限（每个 SKU 一次字符串替换，毫秒级））；DescriptionRenderer 纯函数填充引擎（不碰数据库/时钟/随机数，同样输入永远同样输出，所以「变量缺失已被报告」可被逐条断言；**缺变量是一种输出而不是失败**——照样产出正文但把占位符剥掉、段落标 INCOMPLETE、缺失逐条报告，与 3.4.2.1 的「拒绝也是一种输出」方向相反：长文本的运营诉求是「brand 没填上」比「填充失败」有用得多；tidy 只做机械收尾（折叠空白、去标点前空格、折叠剥空造成的连续同标点）**刻意不重排句子不改标点结构**——擅自通顺化会让运营无法预测输出；三条填充不变量用 IllegalStateException 兜住而不是把不一致结果落库）；DescriptionService（模板 CRUD 与变量整体替换、译文 CRUD 与完整度检查、短语库 CRUD、概览；jOOQ 显式 SQL 不带 ORM 懒加载）；DescriptionRenderService（批量填充在提交时快照 SKU 事实与四个短语池、逐 SKU 落库、段落与缺失报告落库、缺失变量按变量汇总「brand 缺 200 次」比 200 行罗列有用、段落查询与 CSV 导出**共用同一段 SQL 拼装**保证导出与筛选一致、**streamCsv 每批在流式线程里用 access.run 重开租户事务**否则那条连接上没有 app.current_tenant、FORCE RLS 会把全部行藏起来，症状是「导出只有表头且不报错」；CSV 按 RFC 4180 转义且前导 =+-@ 加单引号切断 Excel 公式执行）；DescriptionController 路由 /catalog/descriptions（与 /catalog/titles 并列不合并：标题有平台硬上限、描述没有，排版约束不同）；权限读 catalog.spu.read / 写 catalog.spu.update（沿用 V3 已有 spu 词表不新造，新词表要改 identity 服务的迁移属于另一个服务的写入所有权）；GET /overview、/templates、/templates/{id}、/templates/{id}/translations、/templates/{id}/translation-coverage、/templates/{id}/enabled、/phrases、/phrases/{id}、/phrases/{id}/enabled、/renders、/renders/{id}、/renders/{id}/skus、/renders/{id}/missing-variables、/sections、/sections/export；POST /templates、/templates/{templateId}/translations、/phrases、/renders（**「已填充」而不是「已受理」语义**：返回时候选与缺失报告已落库，前端不轮询）、/templates/{templateId}/translations/{translationId}（PUT）、/translations/{translationId}（DELETE）；PUT /templates/{id}、/templates/{id}/enabled、/phrases/{id}、/phrases/{id}/enabled；DELETE /templates/{id}、/phrases/{id}、/translations/{translationId}（都带 version 乐观锁）；`apps/web/src/app/products/content/page.tsx` 页签在标题库与描述库之间切换（同一 L3 3.4.2「标题与内容库」共用页面路由，但**不合并成一个工作区**——切到描述页看不到标题的平台长度上限，切到标题页看不到卖点短语池）；`apps/web/src/components/catalog/description-workspace.tsx` 真实工作区（概览四张 StatCard + 缺变量时置顶「最该先做的一件事」/ 内容模板列表与编辑器（变量绑定、**占位符与变量双向校验的实时提示**）/ 多语言译文面板（完整度检查、逐条占位符一致性报错、AI 预填标记）/卖点短语库（类型筛选、引用数、删除保护）/ 批量填充面板（模板+语言+SKU 勾选，**语言下拉只给源语言与已配译文**因为缺译文的语言提交时会被整批拒）/ 填充批次列表（状态筛选+分页）/填充结果段落列表（缺变量的段落用红框逐条列出「哪个变量·为什么缺·怎么修」）/ 批次详情（按变量汇总 + 逐 SKU 结果）/ CSV 导出；**加载态、空态、错误态齐全**，空态文案区分「还没建过」与「筛选后没有」）；`apps/web/src/components/catalog/description-model.ts`（校验、展示格式化、空态文案与接口，**前端不重算填充结果**——正文由后端 DescriptionRenderer 产出，这里照原样显示，前端擅自把 {brand} 替成什么会让页面看到的和 04 刊登引用的不是同一段文字）；`apps/web/src/lib/api-proxy.ts` 放行 /catalog/descriptions（31 条路径正反断言验到全过，含 4 条必须被拒的负向断言）；`DescriptionRulesTest.java` 19 例 + `DescriptionRendererTest.java` 16 例 + `DescriptionLibraryIntegrationTest.java` 12 例 + `description-model.test.ts` 36 例；**本模块最关键的验收点**（`missingVariablesAreReportedAndNeverLeftAsLiteralPlaceholders`）逐条验证蓝图硬要求：品牌与分类都为空的 SPU 填出来，断言正文里没有 {brand} 与 {category} 字面量、段落被标成缺变量不会被 04 当成可用内容、缺失逐条落到 content_render_issue 带变量名与可执行原因、按变量汇总答得出「brand 缺了几次」、批次解释说清正文照样产出了；`databaseRefusesToPromoteAnIncompleteSectionToComplete` 从 DB 层验三条约束：手工 UPDATE 把缺变量段落改成 COMPLETE 被触发器拒、手工 INSERT 「完整+带占位符」被 CHECK 拒、手工 INSERT 「完整+空正文」被 complete_needs_text 拒；租户隔离三断言实跑（Testcontainers 真实 PostgreSQL 16 + 强制 RLS，连接池固定 1 条连接）：两租户互不可见（模板/短语/译文/段落/批次全部按当前租户过滤，握着别人的 id 也读不到、删不掉、填不了）、伪造 X-Tenant-Id 头无效（租户只来自已认证的成员关系，伪造头写入的批次进的是凭据说的那个租户）、连接池归还后不串租户（单连接反复交替 5 轮，段落列表在连接复用时最容易串所以每轮都验）；另有多语言译文用例（占位符三方向不一致都在保存时拒、缺译文时整批拒且不退回源语言正文、源语言不建译文行）、四类内容各建一个模板的用例、固定文本注意事项无变量的用例、模板与短语引用保护用例、CSV 导出真实行用例、HTTP 端点与权限用例（只读权限写端点 403、未认证 401） |
| 3.4.3.1 | 多语言内容 | /products/translations | D1–D2 | 已交付 | V22__multilingual_content.sql（六张新表；**不复用 V21 的 content_template_translation / content_render_section**：那是「描述模板与其渲染段落」的多语言、主体是 content_template，本模块的主体是商品档案（复合外键指向 V1 的 spu / sku）——文件头用蓝图原话逐条写了不复用的四个理由（主体不同/字段集不同：模板译文要校验占位符集合相等而本表正文是已填好的成品/删除语义相反/取值形状不同），否则以后有人会把两套 locale 合并成一套，两边数字迟早对不上；`translation_locale` 租户语言需求（locale 唯一 + locale_name 展示名 + source MANUAL·SHOP_SITE + enabled + version，**这就是 02 渠道未接入时可真实写入的一侧**，不是假数据；`spu_translation` / `sku_translation` 各一张而不是一张多态表——合表就没法建复合外键，「译文必须挂在真实存在的商品上」只能降级成触发器；三态 status PENDING·TRANSLATED·REVIEWED **待翻是一等状态而不是「行不存在」**（「需要哪些语言」推导出来的待翻项必须落到可查的行上，否则完整度报表无从汇总）；`translation_suggestion` AI 建议（suggested_name/description 快照留在本表，所以译文被人工改过之后「当初 AI 给的是哪一版」仍然答得上来 + batch_ref 归组 + ADOPTED/REJECTED **处置是终态**）；`translation_batch` + `translation_batch_item` 三件同步批量 CREATE_PENDING·ADOPT_SUGGESTIONS·MARK_REVIEWED（**没有「排队中」这种自欺欺人的状态**：几百行短事务，做成异步只会让运营为几十毫秒去盯进度条；逐条结果与逐条原因落库，counts 相加恒等于 item_count 有 CHECK；重复 (商品,语言) 用 coalesce 折叠 NULL 的表达式唯一索引挡住——普通 UNIQUE 认为 NULL 互不相等）；五个触发器：guard_translation_status（NEW=PENDING 而正文非空拒——正文不会因为改状态就消失，静默退回待翻会让已填好的文案从刊登可取范围里消失且不留痕；OLD=PENDING 而 NEW=REVIEWED 拒——跳级抹掉了「有没有人真的读过」这个信号）、guard_translation_no_placeholder（逐条兜底残留的 {x}，占位符是 3.4.2.2 的机制、本表正文必须是成品）、guard_translation_suggestion_decided（处置终态：改回 PENDING 再走一遍会产出两条不同的译文历史）、refuse_translation_locale_delete（仍被商品译文用着的语言只能停用不能删，否则这些译文成了没有需求依据的孤儿、完整度报表会算成「不在需求列表里的语言」，两边数字立刻对不上）、guard_translation_no_placeholder 与三条 CHECK 互为纵深（CHECK 在 PG 里可被 NOT VALID 绕过，触发器不行）；18 条索引含**两个部分索引 spu/sku_translation_publishable(tenant_id,主体,locale) WHERE status='REVIEWED'**——04 刊登只取已校对，而跑几个月后 PENDING 会远多于 REVIEWED；六表均 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + catalog_app/starcon_app GRANT）；GET /catalog/translations（overview / locales 分页+关键词+来源+启用 / locales/{id} / **locales/requirements（人工维护 ∪ 站点推导的合并视图）/ locales/sync** / spus/{id} / skus/{id} / entries 分页+关键词+状态+语言+主体（两张表 UNION ALL 后再排序分页，避免「在 Java 里合并两页再切片」的假分页）/ entries/{id} / entries/export CSV 流式 / **coverage（04 刊登的读形状，publishable 与 status 分开报）** / suggestions 分页+batchRef / suggestions/{id}）、POST /catalog/translations（locales / **locales/sync（未接入返回 409 写明归 02 渠道且不写入任何行**）/ spus |
| 3.5.1.1 | 套装版本管理 | /products/bundles | D1–D2 | 已交付 | V23__bundle_version.sql（三张新表；**套装档案与套装版本分表而不是合一**：编码是租户内唯一且建后不可改、版本号只要求同套装内唯一，合表会让 UNIQUE(tenant_id,code) 与 UNIQUE(tenant_id,code,version_no) 互相打架，且「同套装同时刻至多一个生效版本」是同套装内跨行约束，合一就没有分组键了；`bundle`（code + status ACTIVE·ARCHIVED + note，租户内唯一；归档只表示不再新建版本，历史版本照常可读因为 09 订单要读）；`bundle_version`（version_no 同套装唯一 + **name 挂在版本上**（蓝图把「改名」列进不可改清单，说明名称随版本走）+ effective_from/effective_to + status DRAFT·PUBLISHED + published_by/published_at；两条 CHECK：PUBLISHED 必须带发布人与发布时刻（没有发布人的 PUBLISHED 等于「有人按过发布」这种无法证伪的说法）、DRAFT 不得携带发布痕迹；区间 CHECK 用「终点严格大于起点」，因为空区间等于「从不生效」；`bundle_component`（**行挂在版本上而不是套装上** —— 「已发布版本的组件不可改」要求每条组件能回答我属于哪一版；quantity **NUMERIC(19,6)** 而不是 int —— 套装里出现「0.5 米挂绳 + 2 个挂钩」是真实场景，int 会逼运营写成 1 件而 09 订单展开与 07 库存扣减都照着假数走；CHECK quantity>0，否则展开出一条 0 子行、扣减出一条 0 扣减，两边都答不出这组件要不要；UNIQUE(tenant_id,version_id,sku_id) 挡住同版本内一个 SKU 两行（两行时「要几件」无法回答而订单只能取一列）；sku 用 **RESTRICT** 不是 CASCADE，删被引用的 SKU 应该被拒而不是悄悄把套装拆掉）；**生效区间的裁决规则 R1~R5 写在文件头并逐条落到 SQL**：R1 半开区间 [from,to)、to 为 null = +∞，所以 [10:00,12:00) 与 [12:00,14:00) 在 12:00 处**不**重叠（闭区间会让那一刻两版同时生效，「当前生效版本」就只能返回两行或随便挑一行）；R2 重叠**一律拒绝不做裁决** —— 选「后来者覆盖」要先定义谁是后来者，而版本号先后与区间先后互不相关（v3 可能比 v2 晚发布却从更早开始），一旦要靠规则决定谁生效，「3 月 15 日卖的是哪一版」就有两个答案而订单冻结的正是当时那版；R3 只有已发布版本参与判定（草稿是待办不是事实），发布那一刻才检查；R4 当前生效是纯时刻查询 effective_from<=t 且 (to is null 或 t<to)；R5 **已发布版本只能向前关闭** —— 不留这一个口子的话第一个开放区间的版本发布后就永远无法被接替，整个版本机制第二天就死了，而收紧只能往前且不能落进过去，保证历史某时刻生效的是哪一版永不被追溯改写；四个触发器（CHECK 在 PG 里可被 NOT VALID 绕过，触发器不行）：guard_bundle_version_published（BEFORE UPDATE 逐列比较：改名/改版本号/改归属套装/改生效起点/改备注/改发布留痕全拒，撤回发布拒，effective_to 只允许向前关闭；名字取 alphabetically 在前，报错先说最根本的那条）、refuse_bundle_version_published_delete（已发布不可删，连删套装时的级联一起拦）、guard_bundle_component_published（INSERT/UPDATE/DELETE 都拦，即蓝图标在 3.5.1.2 的「已发布版本的组件不可改」）、guard_bundle_version_interval（重叠判定用 tstzrange(...,'[)') 与触发器同口径）；六条索引含两个部分索引 bundle_version_effective(...) WHERE status='PUBLISHED' （规则 R4 的时刻查询形状）与 bundle_version_open_ended(...) WHERE status='PUBLISHED' AND effective_to IS NULL （每天要盯「有没有版本永远接替不了」）；三表均 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + catalog_app/starcon_app GRANT）；端点 GET /catalog/bundles（overview 分页+关键词+状态 / bundles/{id} 详情含版本与此刻生效 / bundles/{id}/effective?at= **规则 R4 时刻查询，04 刊登与 09 订单的权威入口** / bundles/{id}/versions）、GET /catalog/versions（分页+关键词+状态+套装 / {id} 详情含组件与 editHint / {id}/components 清单，09 订单冻结前读它）、POST /catalog/bundles、POST /catalog/bundles/{id}/versions、POST /catalog/versions/{id}/publish（三道关：组件齐备→区间不重叠（报错点名与哪一版、哪个区间撞了）→记发布留痕）、PUT /catalog/versions/{id}/close（**已发布版本上唯一合法的写操作**）、PUT /catalog/versions/{id}/components（**整体替换而不是增删式** —— 清单有「同版本一 SKU 一行」约束，增删式提交时前端要自己算最终集合，漏对账的表现是剩一条幽灵组件）、PUT /catalog/bundles/{id}/status、PUT /catalog/versions/{id}、DELETE bundles/{id} 与 versions/{id}；权限读 catalog.spu.read 写 catalog.spu.update（沿用已有 spu 词表：套装挂在商品域下、下游是 3.1.2.2 可售 SKU 与 07/09，新词表要改 identity 服务的迁移属于另一个服务的写入所有权）；页面 /products/bundles（bundle-workspace.tsx：套装列表+概览四卡（**开放区间**是需要每天盯的数）、版本列表、草稿编辑器、组件清单编辑器、**按任意时刻查当前生效版本**、提前停用编辑器；错误态/空态/加载态齐备，bundle-model.ts 把「已发布整版冻结」「此刻没有生效版本是真状态不是查不到」「提前停用只能收紧只能往前」三条口径固化在前端不重算）；测试 BundleVersionIntegrationTest.java（17 例，真实 PG16 Testcontainers + 强制 RLS，**含租户隔离三断言**：两租户互不可见 / 伪造 X-Tenant-Id 头无效 / 单连接池反复交替不串租户，外加 publishedVersionIsImmutableEvenThroughDirectSql 直连 SQL 也改不出改名改版本号撤回发布删版本加组件改组件数量删组件、effectiveIntervalRulesArePinnedDown 把 R1/R2/R3/R4 逐条钉死、publishedVersionCanOnlyBeClosedForward 把 R5 钉死、instantsSurviveTheRoundTripWithoutTheDoubleOffsetTrap 钉死 timestamptz 绑值不二次偏移）+ BundleRulesTest.java（13 例纯规则）+ bundle-model.test.ts（19 例前端）；契约 packages/api-contracts 由 python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi 重新生成（+14 个 Bundle* DTO，纯新增）；与 3.5.1.2 组件维护的分工：本模块交付「随草稿版本编辑组件清单 + 发布时校验齐备」（组件清单是蓝图写在本模块输入里的），**循环引用检测与独立组件工作台归 3.5.1.2**；组件只引用 SKU 不直接引用套装，嵌套套装走 3.1.2.2「套装作为可售 SKU」；与 3.5.2.x BOM 分表（BOM 用于生产/加工产生实物成品，套装用于销售虚拟扣组件） |
| 3.5.1.2 | 组件维护 | /products/bundles | D1–D2 | 已交付 | V24__bundle_component_maintenance.sql（**先说为什么必须有 bundle_sku**：V23 的 bundle_component 只引用 sku 不引用 bundle，「套装 A 含套装 B」在数据上根本无法表达，循环也就无从发生；蓝图自己给了表达方式 —— 3.5.1.1 关系栏「下游 = 3.1.2.2（套装作为可售 SKU）」，即套装自己先成为一个可售 SKU 行再被别的套装当组件引用，所以循环检测必须有一条 SKU↔套装指向才可判定；**为什么不给 bundle_component 加 bundle_id 多态列**：组件绝大多数不是套装，多一列可空外键会让 99% 的行带着空值而空值与「指向套装 0」分不开；已发布版本冻结的是「当时那份 SKU 清单」，套装指向是间接且可变的，摊平进组件行会让历史订单冻结的展开结果随 SKU 重新指向一起变；V23 文件头已把这个选择写死，本模块沿用不推翻上游形状） + 新表 bundle_sku（一个 SKU 至多代表一个套装：UNIQUE(tenant_id,sku_id)，两行时「这个组件展开成什么」有两个答案而 09 订单只能取一条；sku 用 RESTRICT —— 删一个「代表套装的 SKU」等于让所有把它当组件的套装静默降级成普通 SKU，而已发布版本里那些组件是冻结的历史） + ALTER bundle_component ADD version（3.5.1.1 整体替换没有单行版本号，逐行改必须有乐观锁：两个人同时打开同一份清单，后写的会静默覆盖先写的，而组件清单是要冻结进订单的东西） + 三个函数（find_bundle_cycle_path 递归可达性，返回一条 uuid[] 路径而不是布尔：报错要能点名整条环；NOT(...=ANY(path)) 剪掉与目标无关的死循环并保证终止；depth<32 只截断「继续往深处找」不影响是否成环的结论，因为目标一旦被找到就立刻返回；bundle_cycle_codes 把路径转成「A → B → A」编码链） + 两个触发器（guard_bundle_component_cycle BEFORE INSERT，guard_bundle_sku_cycle BEFORE INSERT OR UPDATE OF bundle_id,sku_id —— 改指向会一次性改掉所有把该 SKU 当组件的套装的出边，不在这一侧查环的话「先建好 A→B 再把 B 的可售 SKU 改指回 A」就是绕过组件侧检测的成环路径） + 三条索引；bundle_sku 强制 RLS isolation + catalog_app/starcon_app GRANT；**循环规则 R6~R9 写在文件头第三节并逐条落到 SQL**：R6 成环的定义是「从新指向的套装出发能沿包含边走回写入方所属的套装」，注意**成环不是「套装直接引用自己」**而是要沿包含链走一圈（A→B→C→A），所以 A 含 B 永远合法（B 自己没被 A 包含过），只有闭合才拒 —— 这个区别决定了可达性是唯一判定入口而不是「查有没有直接反向边」；R7 遍历范围含草稿（草稿的组件行是真实存在的一行数据且马上就要被发布，只看已发布的话 A 的草稿里塞进 S_B（B 已发布且含 S_A）在保存时不报，等草稿一发布就成环，那时已经晚了）；R8 自引用就是长度为 1 的环，在展开侧加深度截断等于给一个错误答案编造「到这里就够了」的解释，所以写入侧拒绝；R9 拒绝而不是自动拆环（自动拆要替运营决定断哪一条，而运营比我们清楚哪条是笔误）；规则层 BundleComponentRules（纯函数不碰 DB/时钟，cyclePath 用 BFS 而非递归：套装数可达几百、递归会顶爆调用栈，BFS 顺带就是最短环，报错里给运营的那条环越短越好读；MAX_WALK_DEPTH=32；数量/规模规则转发 BundleRules 而非复制 —— 同一个「数量必须大于 0」写成两份，迟早有一份被改松，于是出现「3.5.1.1 拦得住、3.5.1.2 拦不住」这种只在跨模块操作里暴露的漏洞） + BundleComponentService（jOOQ 显式 SQL；清单计数与清单**复用同一段 FROM**，否则「清单按版本过滤」与「总数按版本过滤」会给出两个不同数字、翻页页数对不上；**containmentEdges 加载整个租户的边而不是只加载当前版本**，因为环可能绕经别的版本的组件（A 的组件里有 B、B 的 v2 草稿里有 C、C 的 v1 里有 A），只看当前版本会漏掉而漏掉的环要到对方版本发布才显形；只取「SKU 代表套装」的那部分边，邻接表规模等于嵌套组件数而不是组件总数） + BundleComponentController + CatalogModels 追加 9 个 DTO（BundleComponentOverview 含 selfReferencingCount 与 nonPositiveQuantityCount 两个**必须恒为 0 的体检指标** —— 写出来不是为了好看而是让「一条都没坏」可核对，约束建了不等于数据一定对；BundleComponentItem 带 versionStatus/editable 让跨版本清单里冻结行一眼可见；BundleComponentEditInput **没有 skuId** —— 组件行的身份是「这个版本里的这个 SKU」，换 SKU 走删一行加一行，rowVersion 是行级乐观锁；BundleComponentCheck 是蓝图的「校验结果」输出，与保存报错分开；BundleComponentProblem 带稳定 code + 可直接显示的中文 message）；端点 GET /catalog/components/overview、GET /catalog/components（**跨版本按组件读**，3.5.1.1 只能按版本读，而运营问「这个 SKU 被哪些套装用着」走的是反查方向，07 预占组件与 09 展开也读这个方向；分页+关键词（SKU 编码/名称、套装编码、版本号、角色）+ versionId/bundleId/nested）、GET /catalog/components/{id}、**GET /catalog/versions/{id}/component-check**（结构化校验结果：一次列全循环/数量非法/组件不存在/重复 SKU/空清单/版本冻结，而不是让人反复提交试错）、POST /catalog/versions/{id}/components（加一行）、PUT/DELETE /catalog/components/{id}（改/删单行，rowVersion 乐观锁）、GET /catalog/bundle-skus、POST/PUT/DELETE /catalog/bundle-skus[/{id}]（指向的建/改/解；改指向同样过循环检测；**已发布版本引用着该 SKU 时拒绝解指向** —— 解开后那些冻结的组件会从「套装的可售 SKU」静默降级成普通 SKU，09 订单冻结的展开结果就变了）；与 3.5.1.1 端点的分工：PUT /versions/{id}/components 是**整体替换**（3.5.1.1，不动），POST 同路径是**加一行**（本模块），两条语义不同必须能被 URL 区分开；权限读 catalog.spu.read 写 catalog.spu.update（与 3.5.1.1 完全一致，组件是套装版本的一部分，同一份商品数据不该有两套权限）；页面 /products/bundles 的「组件维护」标签（component-workspace.tsx：概览四卡含两个健康指标 + 校验结果面板按 PROBLEM_CODES 分组显示 + 跨版本清单逐行改删 + 指向列表；component-model.ts 把「已发布整版冻结」「嵌套不是终点」「一次看全而不是反复试错」三条口径固化在前端不重算；错误态/空态/加载态齐备；复用 3.5.1.1 的套装与版本下拉不另建一份数据 —— 复制一份就会出现「这边能选、那边没有」的幽灵选项）；api-proxy 放行 components / bundle-skus 与 versions/{id}/component-check 共 4 段路径；测试 BundleComponentIntegrationTest.java（15 例，真实 PG16 Testcontainers + 强制 RLS，连接池固定 1 条连接；**含租户隔离三断言**：两租户互不可见（含跨租户改组件/删指向都被拒、组件集合与指向集合两边互不相交）/ 伪造 X-Tenant-Id 头无效 / 单连接反复交替 5 轮不串租户；circularReferenceIsRefusedWithTheWholeRing 把合法嵌套放行而自引用与二元/三元环拒绝，且**断言报错点名整条环**（取夹具真实编码而不是写死字面量）；relinkingASkuToCloseACycleIsRefused 证明「不新增任何组件行、只改一条指向就闭合成环」这条绕过组件侧检测的路径也被拦住，并验证换一个不闭合的目标就能改成功（拦的是成环不是改指向本身）；cyclesAreRefusedEvenThroughDirectSql 直连 SQL 也写不出成环的组件与指向；publishedVersionComponentsAreFrozen 增删改三路全拒；rlsPoliciesAreInPlaceOnTheLinkTable 断言 bundle_sku 强制 RLS 且 quantity 仍是 numeric(19,6)） + BundleComponentRulesTest.java（16 例纯规则，把 R6~R9 钉死：三元环、二元环、自引用、DAG 不误报、**aCycleElsewhereInTheGraphDoesNotAffectUnrelatedBundles**、**traversalTerminatesOnSelfLoopsAndRepeatedEdges**（图里本来就有自环时 BFS 不能被带着跑飞）、**depthCapBoundsTheWalkWithoutChangingTheVerdict**（40 层深链：结论仍正确且栈不爆）、null 端点判「无环」而非「有环」、环路文案首尾相接） + component-model.test.ts（21 例前端）+ route.test.ts 新增 3.5.1.2 放行/拒绝断言（21 例）；契约 packages/api-contracts 由 python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi 重新生成（+9 个 Component*/BundleSku* DTO，纯新增）；与 3.5.1.1 的分工：本模块交付独立组件工作台 + 循环引用检测 + 结构化校验结果与 SKU↔套装指向，3.5.1.1 的「随草稿版本整体替换清单 + 发布时校验齐备」不变；与 3.5.2.x BOM 仍分表不碰（BOM 生产/加工用、产生实物成品、持有自己库存，套装销售用、虚拟扣组件） |
| 3.5.2.1 | BOM 版本管理 | /products/bom | D1–D2 | 已交付 | V25__bom_version.sql（三张新表；**BOM 与套装分表不合并**，V25 文件头第零节写了三条实质差异：①**BOM 有成品而套装没有** —— `bom.output_sku_id uuid NOT NULL` 复合外键指向 sku（RESTRICT），因为蓝图的职责第一句就是「成品由哪些组件组成」，而虚拟套装不占 SKU 行、bundle 表里没有任何 sku 外键；②**组件行带损耗率**（`scrap_rate`），套装组件行没有也不该有 —— 生产会损耗（裁切边角/烧制破损/来料不良），卖套装是把组件原样扣出去，给套装加一个永远为 0 的列只会让「这个数字为什么是 0」变成没人回答的问题；③**BOM 冻结的是实物领料依据**，所以触发器比 V23 更严：guard_bom_component_published 连「改 unit_quantity / 改 scrap_rate」都挡住，不只是增删改，因为 08 加工单领的是「单位用量 × 产量 × (1+损耗率)」算出来的数，改损耗率等于追溯改写当时领了多少料；`bom`（code 租户内唯一且建后不可改 + output_sku_id 建后不可改 + status ACTIVE·ARCHIVED + note；归档只表示不再新建版本，历史版本照常可读因为 08 加工单要读）；`bom_version`（version_no 同 BOM 唯一 + **name 挂在版本上** + effective_from/effective_to + status DRAFT·PUBLISHED + published_by/published_at；三条 CHECK 与 V23 同口径：PUBLISHED 必须带发布人与发布时刻、DRAFT 不得携带发布痕迹、终点严格大于起点；`bom_component`（**行挂在版本上**；unit_quantity **NUMERIC(19,6)** 且 CHECK >0 —— 生产型卖家的组件是「0.5 米面料 + 2 颗螺丝」，int 会逼运营写成 1 件而 08 领料与 06 采购量都照着假数走；**scrap_rate NUMERIC(7,4) NOT NULL DEFAULT 0 且 CHECK 0<=x<=100**；UNIQUE(tenant_id,version_id,sku_id) 挡住同版本内一个 SKU 两行；sku 用 RESTRICT 不然删一个被引用的 SKU 会悄悄把 BOM 拆掉）；**生效区间裁决规则 R1~R5 逐字沿用 V23 不另立一套**（BOM 与套装的时间语义都是「某一时刻该用哪一版」）：R1 半开区间 [from,to)、to 为 null = +∞，所以 [10:00,12:00) 与 [12:00,14:00) 在 12:00 处**不**重叠；R2 重叠**一律拒绝不做裁决** —— BOM 比套装更不能自动裁决，用错一版 BOM 就是领错料算错成本而错在仓库里撤不回来；R3 只有已发布版本参与判定，草稿发布那一刻才检查；R4 当前生效是纯时刻查询；R5 **已发布版本只能向前关闭**（不留这个口子的话第一个开放区间的版本发布后永远无法被接替）；四个触发器（guard_bom_version_published / refuse_bom_version_published_delete / guard_bom_component_published / guard_bom_version_interval，后者用 tstzrange(...,'[)') 与判定同口径）；八条索引含两个部分索引 bom_version_effective WHERE status='PUBLISHED'（规则 R4 的时刻查询形状）与 bom_version_open_ended WHERE status='PUBLISHED' AND effective_to IS NULL（每天要盯「有没有版本永远接替不了」），以及倒排形状 bom_component_by_sku（哪些 BOM 用这个料）与 bom_by_output_sku；三表均 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + catalog_app/starcon_app GRANT）；**本模块自己钉死的两条语义 R6/R7**（V25 文件头第三节）：**R6 scrap_rate 是百分数不是比率**（5 = 5%）—— 存比率的话运营填「5」会变成 500% 损耗，采购量翻 6 倍且**不报错**；存百分数让「填 5」只有一个自然解释，也让 CHECK 的上界 100% 自解释（100% 损耗 = 这个组件一丁点都用不上、只需要补损耗，那不是一条有效的 BOM 行，真要表达废料请在成品上记，不该让组装单出现「领料 0 件」）；默认 0 而不是必填，因为委外加工里「不损耗」是绝大多数情况；**R7 需求量 = unit_quantity × outputQuantity × (1 + scrap_rate/100)**，BigDecimal 保留 6 位小数 HALF_UP，**只算一层**（多级递归展开是 3.5.2.2 的独立职责）且**不取整**（取整/补安全库存/判缺口是 3.5.2.2 与 06 的决定，蓝图 3.5.2.2 原话「展开结果是建议不是事实」——提前取整就是在下游拿到单据之前先替它做了决定）；端点 GET /catalog/boms（overview 概览含「已发布但没有组件」这个**必须恒为 0** 的体检指标 / 分页+关键词（编码或成品 SKU 编码名称）+ 状态 / {id} 详情含版本与此刻生效 / {id}/effective?at= **规则 R4 时刻查询，06 采购建议与 08 加工单的权威入口** / {id}/versions）、GET /catalog/bom-versions（分页+关键词+状态+具体 BOM，带 totalUnitQuantity 与 **scrapPercent 本版本最高损耗率** 两个 BOM 独有的可见量 / {id} 详情含组件与 editHint / {id}/components 清单）、**GET /catalog/bom-versions/{id}/requirement?outputQuantity=（规则 R7 单层需求量，返回 singleLevel=true 让页面自证这是单层）**、**GET /catalog/bom-components/usage?skuId=（倒排：哪些 BOM 版本用这个料，逐行带 editable 让「改了会动到哪些已冻结历史」一眼可见）**、POST /catalog/boms、POST /catalog/boms/{id}/versions、POST /catalog/bom-versions/{id}/publish（三道关：组件齐备→区间不重叠（报错点名与哪一版哪个区间撞了）→记发布留痕）、**POST /catalog/bom-versions/{id}/copy（版本复制：整份复制成新草稿，用量与损耗率都带走；生效起点默认「此刻」而不是沿用源版本 —— 沿用已发布版本的起点会直接撞 R2，且运营要的是「改完从现在起接替」）**、PUT /catalog/bom-versions/{id}/close（已发布版本上唯一合法的写操作）、PUT /catalog/bom-versions/{id}/components（**整体替换而不是增删式** —— 清单有「同版本一 SKU 一行」约束，增删式提交时前端要自己算最终集合，漏对账的表现是剩一条幽灵组件）、PUT /catalog/boms/{id}/status、PUT /catalog/bom-versions/{id}、DELETE boms/{id} 与 bom-versions/{id}；权限读 catalog.spu.read 写 catalog.spu.update（沿用已有 spu 词表：BOM 挂在商品域下、下游是 06 采购建议与 08 加工单领料，新词表要改 identity 服务的迁移属于另一个服务的写入所有权）；页面 /products/bom（bom-workspace.tsx：BOM 列表+概览四卡（**开放区间**是需要每天盯的数，**已发布但没有组件非 0 时显红条**）、版本列表、**当前生效版本可按任意时刻查**、组件清单（单位用量 + 损耗率），**需求量试算区**、**倒排反查区**、草稿版本编辑器、提前停用编辑器、版本复制编辑器；错误态/空态/加载态齐备，空态文案逐条说明「为什么空」而不是画成 0；bom-model.ts 把「损耗率是百分数 5=5% 不是 500%」「需求量不取整且必须显示单层提示」「此刻没有生效版本是真状态不是查不到」「已发布整版冻结含用量与损耗率」「提前停用只能收紧只能往前」五条口径固化在前端不重算）；api-proxy 放行 boms / bom-versions / bom-components 3 段路径（与 bundles / versions **并列而不合并** —— 名字都叫「版本」但一个是「几个 SKU 捆着卖」一个是「这个成品要领哪些料」，合并会让「v2 指哪个版本」在 URL 上无法回答；bom-components 只放行 usage，不接 id 段）；测试 BomVersionIntegrationTest.java（24 例，真实 PG16 Testcontainers + 强制 RLS，连接池固定 1 条连接；**含租户隔离三断言**：两租户互不可见（含 BOM/版本/组件/需求量/生效版本读全隔离、跨租户改组件与发布被拒、**倒排方向跨租户直接被拒而不是返回空集** —— 返回空集的话「这个料没人用」与「这个料不属于你」分不开、且两边 BOM 集合与组件集合互不相交）/ 伪造 X-Tenant-Id 头无效（伪造头下写入仍落在 B 自己名下）/ 单连接反复交替 5 轮不串租户；publishedVersionIsImmutableEvenThroughDirectSql 直连 SQL 也改不出改名改版本号改生效起点撤回发布删版本加组件**改损耗率改用量删组件**；effectiveIntervalRulesArePinnedDown 把 R1/R2/R3/R4 逐条钉死（含「拒绝发布无副作用」）；publishedVersionCanOnlyBeClosedForward 把 R5 钉死；scrapRateIsStoredAsAPercentageAndSurvivesTheRoundTrip 钉死 R6 且断言 information_schema 里列类型真是 numeric(7,4)、0.0001 的精度存得住；singleLevelRequirementAppliesScrapWithoutRounding 钉死 R7（210 / 50 / 6.3 逐值断言 + 不取整）；copyingAVersionCarriesQuantityAndScrapIntoANewDraft 钉死版本复制；instantsSurviveTheRoundTripWithoutTheDoubleOffsetTrap **断言写回的值**（effective_from 与 published_at 都断言，+08:00 写法归一到同一时刻）而不是只断言没抛异常；rlsPoliciesAreInPlaceOnAllThreeTables 断言三表 FORCE RLS、unit_quantity 是 numeric(19,6)、四个触发器都在）+ BomRulesTest.java（23 例纯规则，区间 R1–R5 转发 BundleRules 并做**跨模块口径一致性**回归断言，BOM 自有的 R6/R7 与单位用量/成品数量规则逐条钉死）；前端 bom-model.test.ts（38 例）+ route.test.ts 新增 3.5.2.1 放行/拒绝断言（含「bom-components 只放行 usage」与「放行 boms 绝不代表放行 bundles」）（22 例）；契约 packages/api-contracts 由 python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi 重新生成（+18 个 Bom* DTO，纯新增）；**与 3.5.1.x 套装版本管理的分工**：分表不碰，触发器多挡「改用量/改损耗率」；**与 3.5.2.2 BOM 展开的分工**：本模块只做**单层**精确需求量（R7），**多级递归展开、子 BOM 当作组件、按仓库过滤、缺口计算全部归 3.5.2.2**；bom_component 只引用 SKU 不引用 BOM —— 嵌套 BOM 在数据上靠「子 BOM 的成品 SKU 出现在父 BOM 的组件行里」表达，那一层的判定是 3.5.2.2 的职责，本模块不预先摊平它 |
| 3.5.2.2 | BOM 展开 | /products/bom | D1–D2 | 已交付 | V26__bom_explosion.sql（catalog 两张新表 `bom_explosion_run` + `bom_explosion_line`，**落库的是「建议」而不是库存事实**：冻结的是规则、BOM 版本、仓库过滤与 as_of，因为 06 的采购单引用的是**某一次展开的 id** ——「现在的 BOM × 现在的库存」随时会变，单子发出去之后就无法解释「当时为什么按这个数下单」；line 行带 parent_line_id + depth + 物化路径（递归的形状完全落在数据里，页面不必自己猜层级）与 UNIQUE(tenant_id,run_id,path)；两表均 tenant_id NOT NULL + ENABLE/FORCE RLS + catalog_app/starcon_app GRANT）；**文件头钉死三条语义**：**E1 逐层结转**（顶层驱动量 = 成品数量，**子层驱动量 = 父行的缺口而不是需求量** —— 半成品已有库存与在途时只展开缺的那部分，按需求量展开会为「根本不生产的那几件半成品」多算一层料，而多算出来的料会变成一张没人能解释为什么不买的采购单）；**E2 缺口 = max(0, 需求量 − 可用 − 在途)**，在途可抵扣是因为货权已归本租户（已经买了只是没到），抵扣它是为了不把同一批料重复下单；仓库过滤必须**同时**作用在 available 与 in_transit 上（在途按 to_warehouse_id 过滤），否则会出现「深圳仓的缺口被上海仓发来的在途抵扣掉」而那批货根本不会进深圳仓；下限截 0 且 available/in_transit/gap 三列原样保留，因为「在途到货晚于需要它的时刻」属 06/07 的提前期决定；**E3 子 BOM 判定四步**（是不是子装配 → 多个 BOM 声明同一成品时按 (code,id) 确定性取第一个并置 ambiguous_sub_bom 告警 → 子版本按规则 R4 半开区间取 as_of 这一刻的**已发布**版本（R3）→ 取不到生效版本则按叶子件处理并说清原因）；**E4 循环引用直接 409 拒绝并逐个点名绕回路径、深度上限截断但显式标明（status=TRUNCATED + 行级 truncated）** —— 循环时需求量在数学上无界，任何有限截断都是错的小数而它会被当成建议下成真实的单；静默死循环与静默截断都不可接受。R1–R7 逐字沿用 V25 不另立一套（需求量公式转发 BomRules.requiredQuantity，损耗率仍是百分数 5=5%）；**跨服务硬纪律**：inventory_balance / inventory_transfer 是 warehouse 的表，catalog 既不跨库 JOIN 也不建跨库 FK/视图更不复制库存快照；端点 POST /catalog/bom-explosions（展开，一次动作→落一条运行记录）、GET /catalog/bom-explosions（运行记录列表，分页 + 按 BOM/成品 SKU 过滤，只带摘要不带展开树）、GET /catalog/bom-explosions/{id}（读回含完整展开树，**06 的采购单引用的是这个 id**）；权限读 catalog.spu.read —— 展开是**纯计算**，它不改动任何业务事实（蓝图「展开结果是建议不是事实」），落库的是调用者自己这份建议的快照，改动 BOM/组件的入口仍是 3.5.2.1 的 spu.update；**warehouse 侧新增授权端点** POST /warehouse/inventory/availability（AvailabilityQuery/View/Row 三 DTO；按 SKU 集合 + 可选仓库过滤批量返回 available/in_transit；available 只由 inventory_balance 的生成列聚合而来**没有第三个来源且不含在途**（未到货的库存拿去预占就是超卖）；in_transit = Σ inventory_transfer.quantity WHERE status='IN_TRANSIT'；**零库存的 SKU 返回 0 而不是缺席** —— 缺席会让调用方分不清「没库存」与「不存在」，而缺口正是 06 采购单要用的数；一次最多 500 个 SKU）；**catalog 侧 InventoryAvailabilityPort 用 RestClient + @Value 与既有 AccessService 同构，并原样透传调用者的 Authorization 头**：租户仍由 warehouse 向 identity 从**已认证成员关系**解析（catalog 不发明 X-Tenant-Id 之类「客户端自称租户」的旁路头，AGENTS.md 明令禁止）；页面 /products/bom 新增展开工作区（展开对象 + 成品数量 + **按仓库过滤** + 深度上限 + 展开基准时刻，多层树按 depth 缩进，逐行需求量/可用/在途/缺口 + 角色标签（子装配→子 BOM 版本 / 叶子件 / 截断 / 已覆盖），**截断显红**因为它意味着清单不完整，错误态原样显示后端中文原因以便运营知道改哪一行组件，展开运行记录区可回看「当时的数是什么」；错误态/空态/加载态/截断态/库存服务不可用态齐备；bom-model.ts 把「缺口不重算」「子装配不是多了一行」「截断必须显眼」「缺口为 0 的两种原因要分得开」四条口径固化在前端）；api-proxy 放行 bom-explosions（列表 + {id}，**不放行 {id}/xxx 子段** —— 展开是一次动作不是一棵子资源）；契约 packages/api-contracts 由 python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi 重新生成（+8 个 BomExplosion* DTO、+3 个 Availability* DTO，纯新增）；测试 BomExplosionIntegrationTest.java（13 例，真实 PG16 Testcontainers + 强制 RLS，连接池固定 1 条连接；多层展开按父行缺口逐层结转逐值断言 SA 需求 21/可用 4/在途 3 → 缺口 14 → RAW-Y = 14×4 = 56（按需求量会算成 84）、RAW-Z = 14×0.5×1.10 = 7.7 小数需求不被取整；循环引用 409 且**不落任何记录**；深度上限截断但 status=TRUNCATED + 行级 truncated + note 写明清单不完整；子 BOM 无生效版本按采购件处理并说清原因；子 BOM 歧义确定性取第一个并告警；**断言 warehouse 替身确实收到了 catalog 透传的 Authorization 头与 warehouseId** 即生产代码真的走 HTTP 且没丢租户上下文；库存服务不可用时整次展开失败且留不下半份记录而不是把可用当成 0；as_of 断言**写回的值**（+08:00 写法归一到同一时刻且与 created_at 不同）而不是只断言没抛异常；**租户隔离三断言覆盖深层组件行** —— 两棵树都真的有第 2 层，逐轮断言第 2 层 SKU 集合互不相交，只测顶层等于没测；两张新表 FORCE RLS 且 required_quantity/available/in_transit/gap 均 numeric(19,6)、scrap_rate 沿用 numeric(7,4)）+ BomExplosionRulesTest.java（11 例纯规则，含**跨模块口径一致性回归断言**：展开的需求量与 3.5.2.1 的单层需求量逐位相同，因为「同一句公式写两份迟早有一份被改松」而那处偏差只在跨模块比对里暴露）+ InventoryAvailabilityIntegrationTest.java（warehouse 侧 8 例，真实 PG16，钉死生成列聚合/不含在途/仓库过滤同时作用两列/在途按 to_warehouse_id/零库存返回 0/租户隔离三断言）+ bom-model.test.ts（38 → 51 例）+ route.test.ts（+1 例放行断言）；**反向验证**：临时把子层驱动量从「父行缺口」改回「父行需求量」，多层展开测试转红（期望 56.000000），恢复后转绿 —— 证明该断言确实咬住了 E1 而不是恒真；**与 3.5.2.1 的分工**：本模块只做多层递归展开、子 BOM 判定、按仓库过滤与缺口计算，单层精确需求量（R7）与生效区间（R1–R5）仍归 3.5.2.1，两者共用同一套规则类不复制公式；**06 采购单生成与 07 补货不在本模块范围**（蓝图：展开结果是建议不是事实，单据由 06 生成后成立），本模块只给到可被引用的展开记录与缺口列 |
| 3.6.1.1 | 往来单位档案 | /partners | D1–D2 | 已交付 | V27__party_profile.sql（catalog 两张新表 `party` + `party_role`；**推翻实体目录旧稿的 supplier/customer/carrier 三张表** —— 旧形状里同一个对手方既是供应商又是客户必须建两份记录，于是跨表没有稳定锚点、改名要改两行、「这两个手机号不一样」在数据上无法表达；蓝图「用角色档案区分，不用多份记录」就是针对这三条，**主体是自然人/法人，身份可以有多个，主体只有一个**；`party`：code 租户内唯一且**建后不可改**（采购单/应付/售后单按 code 对齐这个主体，改编码会让已有单据下一次读取就找不到）/ name(200，企业的法定后缀要放得下) / party_type 枚举 ORGANIZATION·INDIVIDUAL（下游开票与收件逻辑的分叉点，不能靠 name 猜）/ country_code 可空 ISO alpha-2 / email(254=RFC 5321 路径上限)·phone·website（**联系方式刻意不建 party_contact 表** —— 那是 3.6.3.1 的职责，本表只回答「怎么找到这个主体」，那里回答「跟这个主体对接的那个人」）/ status 枚举 ACTIVE·INACTIVE·BLACKLISTED / status_reason + status_changed_at（分开存：updated_at 每次改名都动，拿它显示「什么时候停用的」是错的）/ deleted_at + deleted_by **软删除且本模块没有物理删除端点**（06 采购单、12 应付、11 售后单都会引用这个 id，删掉行之后那些单据只能显示一个 uuid，而「对手方是谁」是财务凭证上必须显示的东西）/ version；两处 DB CHECK 兜住服务层规则：`email or phone 非空`（P3）与 `status='BLACKLISTED' → status_reason 非空`（P4）；`party_role`：role_type 枚举 SUPPLIER·CUSTOMER·CARRIER + status ACTIVE·INACTIVE + **UNIQUE(tenant_id,party_id,role_type) 挡住同身份两条**（蓝图「不用多份记录」在角色层的落点：重复导入供应商数据时多写一条 SUPPLIER，症状是「采购单挂了这个供应商」与「供应商档案里有两条」对不上而两边没有稳定键能互相定位）+ 复合外键 (tenant_id,party_id) 让跨租户挂角色在 DB 层不可能发生；八条索引含四个 pg_trgm GIN 部分索引（code/name/email/phone 各自的 ILIKE '%kw%' —— 前导通配符用不上 btree，V21 已引入 pg_trgm；**四列各自一条而不是一条拼接表达式索引**，因为检索是四个 OR 分支、PostgreSQL 能 BitmapOr，拼成表达式则要求 SQL 侧也拼、一旦两边不一致索引就静默失效且不报错）；两表均 tenant_id NOT NULL + ENABLE/FORCE RLS isolation + catalog_app/starcon_app GRANT）；**文件头第六节钉死七条语义 P1~P7，与 PartyRules 逐字对应**：P1 编码建后不可改（400 留给不可改字段，409 留给 version 乐观锁与唯一冲突）/ P2 类型显式枚举且**类型可改但角色不随类型变**（「原来是个人所以不能当供应商」不是改类型的理由）/ P3 **联系方式至少给一项，被拒的应该是建档**（既没邮箱也没电话的主体在 06 问交期、11 问退货、12 对账三处都用不了，会造出「查得到但联系不上」的僵尸记录，而 3.9.2.2 清理的正是这类；与 V16「建档不要求凭证、被拒的应该是刊登」相反——凭证是**可以稍后补**的运营事实，联系方式不是）/ P4 状态流转表有方向且**自环被拒**（一次「启用」如果本来就已启用，报「状态没有变化」而不是悄悄 +1 版本号骗过乐观锁）、进出 BLACKLISTED 都要理由（「进出」而不是「进入」：制裁解除通常需要留档，而只进不出的写法会让「已从名单移除」无处可查）、**BLACKLISTED→INACTIVE 单独保留**（拉黑是「不与这个对手方做生意」、停用是「我们自己不用了」，合成一步就分不开）/ P5 **变为 ACTIVE 时必须至少一个 ACTIVE 角色**（允许「先建主体后指派角色」是正常业务流，服务层不阻止建档，被拒的应该是「让它在下游可用」不是「建档」）/ P6 **只拦拉黑、不拦停用**（拉黑 = 不能新增商业身份；而停用**必须允许**——P5 的正常流程就要求在停用状态下先挂好角色再启用，一并拦掉「先建后指派」就走不通了；解指派与改备注在任何状态下都允许，因为清理拉黑主体的角色正是之后要做的动作）/ P7 **删除前必须解掉全部角色且报错逐个点名**（删除后主体对列表不可见而角色行还在、那种状态没人能解释）；**第三节明确留给 3.6.1.2 的是「刻意不做什么」**：不在 party_role 上加任何角色字段（账期/MOQ/客户分级/信用额度/接口账号一律不加），因为那正是蓝图点名否决的「一个巨大可空字段表」——「客户没有账期」与「供应商有账期」在那种表里是同一种存储形态，12 财务取默认账期时分不清「这个客户没设账期」与「这列不属于客户」；3.6.1.2 落地时按 role_type 建三张 1:1 档案表、主键 (tenant_id,party_id) 复合外键指向 party_role，**本表无需改动一行**；端点 GET /catalog/parties（分页 + 关键词覆盖编码/名称/邮箱/电话（运营收到对方邮件后只记得邮箱）**LIKE 通配符 escape 转义**（不转义的话运营搜 100% 会变成「匹配所有」而界面上看不出来）+ 主体类型/状态/角色（只认 ACTIVE 的角色指派）/国家/可用性/含已删除 筛选）、/overview（**multiRoleCount 是本模块最该盯的数**——它证明「一份记录就够用」正在被遵守、它下降说明有人开始建重复主体了；noRoleCount 是 3.9.2.2 的候选来源）、/candidates（06/11/12 的下拉数据源，**用 list 的 SQL 而不是取一页再在内存里过滤** —— 内存过滤会让「第 1 页 20 条里 12 条不可用」的空结果说谎，那正是假分页的一种）、/{id}；POST /catalog/parties，PUT /{id}、/{id}/status、/{id}/roles/{roleId}，POST /{id}/roles，DELETE /{id}?version=（软删除）、/{id}/roles/{roleId}?version=（**角色行允许物理删除**：它是「当前扮演这个身份」的事实，没有「曾经有过供应商身份、后来解除了」这种回溯需求，与 V16「凭证撤销不是删除」的理由正好相反）；权限读 catalog.spu.read 写 catalog.spu.update（沿用商品主数据域词表：不新造词表，那要改 identity 服务的迁移、属于另一个服务的写入所有权）；/partners 页面（party-workspace.tsx：概览四卡（**多身份主体**是本模块的体检指标）/ 分页+搜索+5 个筛选的列表，**每行直接渲染角色徽章**（蓝图那句「同时是供应商和客户」要在一眼看见，否则运营会以为数据错了去建第二条记录——那正是本模块要消灭的形态）/ 详情卡含状态流转动作与角色指派表（指派·启用停用·解除，**拉黑主体下「指派角色」按钮不出现并说明原因**）/ 主体编辑器含整体替换的角色清单并**显式提示「保存后这些身份会被解除：X、Y」**（整体替换语义下少发一条就是静默的身份丢失，而「我明明在改名字怎么供应商身份没了」最难排查）/ 删除按钮在还挂着角色时**禁用并说明为什么**（而不是点了吃一个 400）/ 引用候选区直接消费下游会调用的同一个端点；错误态/空态/加载态/停用态/拉黑态/已删除态齐备，两种空态文案不同（无数据时引导「一个对手方建一条记录、按需指派身份，不要为每个身份各建一条」），api-proxy 放行 parties（overview/candidates/{id}/{id}/status/{id}/roles[/{roleId}]，**刻意不放行 {id}/supplier-profile 之类**——那是 3.6.1.2 的路径，代理层放开会让一个还不存在的模块看起来像已交付））；蓝图业务规则落成 PartyRules（纯规则，P1~P7 + usableBlock **单一实现**，列表行/详情/引用候选三个出口都调它，前端只把原因码翻成话不重算）四条不可用原因码 PARTY_INACTIVE·PARTY_BLACKLISTED·PARTY_NO_ROLE·**PARTY_DELETED****按优先级返回**（对已删除的主体报「没有角色」是误导——解指派角色并不能让它回来）；**保存与流转分开**：PUT /{id} 只在状态**真的变了**时才判 P4（前端把当前状态原样带回来是每次编辑的常态，拿它去判会让「改个名字」报「状态没有变化」），「状态没有变化」这个自查只属于 /{id}/status —— 那是一次明确的状态动作；软删除后**详情仍可读并带 deleted=true**（删完回头看一眼时看到「已删除」而不是凭空消失的一条），但列表默认不显示、**引用候选在两种模式下都不返回它**（includeBlocked 只放宽「状态」不放松「已删除」：新单据永远不该选中一个已删的主体）；测试 PartyIntegrationTest.java（24 例，真实 PG16 Testcontainers + 强制 RLS，连接池固定 1 条连接；**租户隔离三断言覆盖角色指派行** —— 角色行才是本模块真正的越权面（「A 看到自己的供应商角色时混进 B 的客户角色」会直接让 A 的采购单挂到 B 的对手方上），两遍都断言角色集合不相交；伪造 X-Tenant-Id 头无效且断言**行确实落在 B 名下**（不只断言 A 看不见）；单连接反复交替 5 轮不串租户/不串角色/候选不相交；同一主体同时是供应商和客户**且全库只有一条主体记录**（把蓝图那句话钉成数据事实）；角色同身份不可重复指派（整体替换与单条端点两条路径都拒）；角色类型建后不可改；整体替换语义下未提交的角色被解指派；P4 方向表与理由要求逐条；P5 启用前必须有角色 + P6 停用可加角色而拉黑不可；P6 与 P7 的边界（停用可加、拉黑不可、**拉黑状态下解指派仍然允许**）；删除前必须先解角色；软删除保留行且 deleted_by 落库、列表/候选/详情三种口径分别断言；两表 FORCE RLS + party.code 上有 UNIQUE 索引；**无角色主体被判定为 PARTY_NO_ROLE**（直连 SQL 造出来的也要看得见）；概览与列表的三个统计**逐项相等**（为此把 noRoleCount 抽成 NO_ACTIVE_ROLE 表达式 —— 一开始 list 用 `not USABLE` 而 overview 用 `activeCount - usable`，**软删除的主体被前者算进去、后者不算，两个数字对不上**，所以两处改成引同一处表达式）；关键词通配符转义；分页切片；引用候选只给可用的而 includeBlocked 带原因码；HTTP 全链路与读写权限分离（写 403 / 读放行 / 缺 token 401））+ PartyRulesTest.java（21 例纯规则，**含 P4 自环被拒、进出拉黑都要理由、usableBlock 优先级、四种原因码标签与处理动作互不相同**四组钉死断言）+ party-model.test.ts（23 例，含「停用**允许**新增角色、停用状态默认」两条与后端同口径的前置断言）+ route.test.ts（+1 例 parties 全路由放行，含「放行 {id}/roles/{roleId} 但拒绝 {id}/supplier-profile」与「id 段是宽松 [0-9a-f-]+，所以 abc 会放行、not-a-uuid 拒绝」两条边界断言）；契约 packages/api-contracts 由 python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi 重新生成（+10 个 Party* DTO，纯新增）；**与 3.6.1.2 角色档案的分工**：本模块交付**角色指派**（主体扮演哪些身份，数据模型 + 端点 + 界面全部可用），角色特有信息一律留给 3.6.1.2 按「每个角色一张 1:1 档案表」落地，本表**不需要为它改一行**；**与 3.6.3.1 地址与联系人的分工**：地址与「带姓名的联系人」归 3.6.3.1，party 上的 email/phone/website 是主体自己的对外通道，两者不重复；**与 3.6.2.1 供应商报价的分工**：报价引用 role_type='SUPPLIER' 的主体，走授权 API 不在本服务 JOIN；**未在范围内**：3.6.1.2 的角色特有字段、3.6.3.1 的地址与联系人、06/11/12 单据侧的名称与联系方式快照（各自服务所有） |
| 3.6.1.2 | 角色档案 | /partners | D1–D2 | 已交付 | V30__party_role_profiles.sql（catalog 三张 1:1 档案表 `party_supplier_profile` / `party_customer_profile` / `party_carrier_profile`，**按蓝图「每个角色一张 1:1 档案表」**落地，不在 party_role 上加任何角色字段也不改 party_role 一行；**键选 role_id 而不是 party_id 是对 V27 第三节交接说明的一处解释**：那里写「主键 (tenant_id,party_id)，复合外键指向 party_role(tenant_id,id)」，但 party_role 上一个主体最多挂三个角色所以 (tenant_id,party_id) 建不出唯一约束、不是合法外键目标，真实存在的唯一列组是 UNIQUE(tenant_id,id)，所以档案键在角色 id 上（PRIMARY KEY (tenant_id,role_id) + FOREIGN KEY → party_role(tenant_id,id) ON DELETE CASCADE）—— **这样不需要给已交付的 party_role 补任何索引或约束**，而「一个主体最多一份供应商档案」由两条传递性保证自动成立（party_role 的 UNIQUE(tenant_id,party_id,role_type) + 本表主键），刻意不再加 UNIQUE(tenant_id,party_id)：那是推论，多加只会多一个能不一致的口子；ON DELETE CASCADE 的含义是 3.6.1.1 物理删除 party_role 行时档案必须跟着删，否则「角色没了但供应商账期还在」的行会被下游当成别人的档案用掉）；`party_supplier_profile`：**payment_terms_days int NOT NULL DEFAULT 0 CHECK(0~365)**（0 = 不给账期/现结，是有确切含义的值而不是「没填」；整数天而不是月，12 应付到期日 = 单据日 + N 天，中间换成「30 天」再解析会在某个月差 1 天）+ **moq_quantity numeric(19,6) + moq_unit_code varchar(32) 复合外键 → unit_of_measure(tenant_id,code)**（**MOQ 只有数量没有单位是一句没有意义的话** —— 同一个「100」在「件」与「箱」下差几十倍而采购单不会重新问单位；CHECK 保证两者成对在或成对不在；精度 19,6 与 V17 换算表同精度，展示位数取该单位 scale）+ cooperation_level 枚举 STRATEGIC·PREFERRED·STANDARD·TRIAL·RISK（战略/优选/常规/试单/风险，每个取值的业务含义写在迁移第三节）+ settlement_method 枚举 PREPAID·ON_DELIVERY·CREDIT·LETTER_OF_CREDIT + **CHECK(settlement_method='CREDIT' OR payment_terms_days=0)**（结算方式与账期必须自洽：预付/货到付/信用证一律 0 天，账期结算必须 >0——「预付 + 90 天账期」的档案，06 采购单按哪一列带出到期日？带 0 天让本该预付的单子挂着没款，带 90 天让这批货款晚 90 天收，两种实现都符合字段本身、只有一种符合业务）；`party_customer_profile`：customer_level 枚举 STRATEGIC·KEY·STANDARD·PROSPECT·RISK + default_currency char(3) ISO 4217 + **default_credit_limit numeric(19,4) CHECK(>0)** + payment_terms_days int（**客户侧也要账期**：蓝图「输入」行写的是「账期与信用额度设置」两项，而 12 应收的到期日 = 单据日 + 这 N 天，那个 N 是算式里的数不是可选项）+ **CHECK(default_credit_limit IS NULL OR default_currency IS NOT NULL)** 且**刻意不设第二列额度币种** —— 「额度 10 万 USD / 默认币种 EUR」这种行在 12 应收里无法判定该拿哪个币种比、而界面必须让用户挑一个、挑错了没有任何东西会报错，把两者绑成一个字段之后这个状态不存在；**额度留空 = 不限制赊账，而 0 是非法值**（这两种在业务上是**相反**的两种默认，折叠成一个值之后必错其一）；`party_carrier_profile`：interface_type 枚举 API·SFTP_FILE·WEB_PORTAL·EMAIL（枚举化因为这四种在 10 物流的处理动作完全不同：自动取轨迹/解析报文/人工录单/人工收邮件，自由文本没法在 SQL 侧判运单能否自动跟踪）+ **account_ref varchar(128) NOT NULL**（没有账号的档案在 10 物流里取不到凭据、登不进平台，与 3.6.1.1 规则 P3「联系方式至少给一项、被拒的应该是建档」同源；**只存账号标识不存密钥口令** —— 按 AGENTS.md 秘密管理口径凭据由部署侧从 secret manager 按这个键注入，档案是被 06/10/11/12 广泛读取的面，多一份明文就多一个泄露点）+ **service_capabilities text[] NOT NULL DEFAULT '{}' CHECK(<@ ARRAY[九个值])**（数组而不是逗号串：`like '%,SEA,%'` 在 SEA 排第一个时会漏匹配，那正是 V27 第四节拒绝拼接索引的同一个坑；`<@` 在 DB 层挡住大小写不一致的 'sea' —— 大小写不一致在 `@> ARRAY['SEA']` 的查询里是**静默不匹配**，不报错只是永远筛不出来）+ service_scope_note + note；索引：供应商 (tenant_id,cooperation_level,role_id) 与 (tenant_id,payment_terms_days,role_id)、客户 (tenant_id,customer_level,role_id) 与 (tenant_id,default_currency,role_id)、承运商 GIN(service_capabilities)（数组包含过滤 btree 用不上）与 (tenant_id,interface_type,role_id)——三种不同筛选口径各建一条而不是拼成表达式索引；**三张表 tenant_id NOT NULL + ENABLE/FORCE RLS isolation（照抄 V27 第五节 DO \$\$ FOREACH，三张表策略必须逐字相同：复制粘贴的第三遍早晚会漏 FORCE，而漏 FORCE 之后迁移角色作为表 owner 仍能绕过策略读别人的额度，那是一条看不见的越权面）+ catalog_app/starcon_app GRANT**；**迁移第七节钉死七条语义 R1~R7 与 PartyRoleProfileRules 逐字对应**：R1 结算方式与账期自洽（**允许账期为 0 的供应商**，跨境小额供应商确实只做现结，把它们挡在建档之外会逼运营乱填一个假账期，那比 0 更危险）/ R2 信用额度必须配币种且额度币种就是默认币种（**按 P3 同源道理：被拒的应该是「给额度却不给币种」，不是「建客户档案」**）/ R3 档案只能挂在真实存在的对应角色上（不匹配给 409「先指派身份」而不是 constraint name；**不拦已停用主体** —— P6 的正常流程要求「先建主体、后指派角色再启用」，把档案维护一起拦掉会让那条流程走不通）/ R4 风险客户信用额度必须为空（给被记录过逾期的客户留额度，12 应收会照着放单而唯一的风险信号已被额度列盖住；反向不拦）/ R5 档案可删角色不由本模块删（删掉后 06/12 退回「没设过」= 额度不设/账期 0，**可逆且有意义的状态，不是需要历史追溯的凭证**）/ R6 枚举只增不改（已写进历史单据冻结快照，取不准加值还是用备注时**加值**，备注是兜底不是收纳箱）/ R7 币种与单位一律引用字典存 code 不存名字（改字典显示名不改写历史档案）；**迁移第八节明确划出下游边界**：06 采购单带出账期/结算方式/MOQ 并**冻结快照**，12 应收取额度+币种+账期判超额与算到期日但**「已用额度」不在本表**（那要读 12 自己的应收台账，跨服务读表是 AGENTS.md 明令禁止的，所以本模块只给上限不给「剩余额度」），10 物流按 interface_type 决定运单能否自动跟踪，**3.6.2.1 报价自带 MOQ 与本表不重复**（本表是供应商级「这家最少下多少」、报价是 SKU 级「这个品最少下多少」，口径不同都不废）；端点 GET /catalog/role-profiles/overview（**覆盖率只统计 ACTIVE 角色** —— 停用身份当下不会被下游用来取默认值，算进分母会让覆盖率永远差一点；missingProfileCount = 已指派身份却没建档案的条数 = 下游取不到默认值的对手方数；**riskyCustomerWithCreditCount 与 termsWithoutCreditCount 两个「应为 0」的探针**用来发现绕过服务层写进来的脏数据——V30 的两条 DB CHECK 理论上让它们不可能出现，而「理论上」三个字正需要一条读出口来验证）、/role-profiles/options（词表与后端同源；**刻意不返回币种与单位列表** —— ISO 4217 词表是 12 财务的资产、计量单位是 3.3.3 的资产，硬编码一份等于复制别人的写入所有权，两份列表迟早不一样，所以这两项走自由输入 + 格式校验）、/role-profiles?roleType=&keyword=&level=&capability=&settlementMethod=&defaultCurrency=&missingProfile=&activeOnly=（**计数与取行引同一处 conditions()，两处各拼一遍的症状是「页面说筛出 7 条、翻到第 2 页是空的」**；keyword 四列 ILIKE 各绑一次用上 V27 那四条 pg_trgm GIN 索引 + 通配符 escape 转义；默认只列 ACTIVE 角色）、GET /catalog/parties/{id}/role-profiles（三槽汇总，**合成一个端点而不是三个** —— 拆开的话运营在「供应商身份刚被别人解掉」之后看到的是拼接出来的旧数据；**没指派身份时槽位为 null、已指派但没建档案时槽位内 profileFound=false，两者在界面上必须长得不一样**）、/parties/{id}/{supplier |
| 3.6.2.1 | 供应商报价 | /partners/offers | D1–D2 | 未开发 | — |
| 3.6.3.1 | 地址与联系人 | /partners/contacts | D1–D2 | 未开发 | — |
| 3.7.1.1 | HS 编码与申报要素 | — | D1–D2 | 未开发 | 阻塞：product_compliance 同为 `LIKE product_research INCLUDING ALL` 复制，装不了 HS 编码与认证要素。须先拆表。 |
| 3.7.1.2 | 目的地报关版本 | — | D1–D2 | 未开发 | — |
| 3.7.2.1 | 物理与安全属性 | — | D1–D2 | 已交付 | V28__physical_safety_attributes.sql（catalog 新表 `sku_safety_attribute`，四个独立布尔 contains_battery / contains_liquid / dangerous_goods / is_magnetic + note + version，UNIQUE(tenant_id,sku_id) 保证一个 SKU 只有一份结论（查到两行都是 true 然后随便挑一行，渠道判定就失去唯一事实）；tenant_id NOT NULL + ENABLE/FORCE RLS isolation + data_scope RESTRICTIVE 跟随 SKU（没有这条，「按危险品筛选」会变成绕过页面权限的枚举口：只要能调筛选接口就能逐页试出别的租户有哪些危险品商品）+ catalog_app/starcon_app GRANT；四条索引含危险品/带电两条部分索引）；**与 3.1.2.2 的分工（文件头第零节 B1）**：蓝图把「长宽高、净重、毛重、包装方式」写进本模块职责，而 V6 早已把这些列建在 `sku_package` 上并让 3.1.2.2 读写，本模块**读写同一张表**、不另建表抄一遍 —— 两个来源的毛重会让 10 物流按哪个算体积重变成没人回答的问题，而算错的那次会变成一张真实的面单；V6 文件头已把分工写死（「3.7.2.1 在 sku_package 之上补是否带电/液体/危险品/磁性」）；**派生值一律不落表**（体积/体积重/计费重/渠道判定/装箱件数全部现算）：体积重 = 体积 ÷ 除数，而**除数是渠道自己的口径**（空运 6000 / 快递 5000），10 物流才有资格决定用哪个，落一列等于偷偷替它选且随渠道变；**文件头钉死两条语义**：**B2 四个独立布尔不合并成一个枚举**（带电/液体/危险品/磁性互不蕴含，「带电且危险」是真实组合而组合恰是渠道判定要用的）；**无行 = 未采集 ≠ 四项全 false = 确认都不带** —— 两者的下一步动作完全不同，且未采集时渠道判定一律拒发；**不建 UN 编号 / 危险品类别 / 电池类型列**：那是 3.7.1.1 的职责（未开发且台账已记录被 product_compliance 阻塞），本模块只回答「是否需要特殊申报」不回答「申报什么」；**纯规则 PhysicalSafetyRules 三条纪律**：**R1 缺一维不给体积**（拿 0 顶替会让 0 cm³ 的 SKU 算出 0 g 体积重、渠道按小件给运费，少收的钱收不回来；某一维为 0 同样视为未量到）、**R2 安全属性未采集一律判不可发**（默认当成普通货放行 = 一条「不填就能绕过申报」的路）、**R3 渠道规则由调用方给**（不建渠道表 —— 那是 10 的事实；体积重除数是入参不是常量，不给就不给而不是套默认值）；计费重 = max(实重, 体积重) 且实重用**毛重**（包装本身也有重量）；装箱件数取体积与限重**两条约束里更紧的那个**（只看体积会装出超重箱、只看重量会装出塞不下的箱），同一 SKU 多行先合并再算（分两行各算一次会得到两个各自成立的箱数，而真实拣货是把它们装进同一批箱子），箱数向上取整（5 件每箱 2 件是 3 箱不是 2 箱）；**端点** GET /catalog/physical-safety/skus/{skuId}（档案 + 现算体积/体积重/计费重，**divisorCm3PerKg 可空**）、PUT 同路径（**一次提交写两张表** —— 职责句把两者写在同一句里且运营建档时也是一次填完，分成两个端点只会让「填了尺寸忘了填安全属性」变成常态；两个 version 各自乐观锁撞 409；勾了任一标记却没确认「已采集」直接 400 —— 自相矛盾的提交会让渠道看到带电而运营以为自己没填）、GET /catalog/physical-safety/profiles（**按安全属性筛选供 10 物流渠道适配**，三个标记都是 Boolean **支持筛 false** 因为运营会问「哪些货不是危险品」；LEFT JOIN 安全属性使「未采集」行也能被筛出来；分页+总数+总页数）、POST /catalog/physical-safety/skus/{skuId}/channel-verdict（**渠道可用性判定**：四个 allowed 开关 + requirePhysicalParameters + 除数由调用方给；可发时**照样返回该办的申报** —— 拦截与申报是两件事）、POST /catalog/physical-safety/carton-calc（**装箱体积核算供 8 拣货**：SKU 行 + 箱规 → 每箱件数/箱数/体积利用率；同 SKU 多行合并）；权限读 catalog.compliance.read 写 catalog.compliance.update（3.7 是「商品合规属性」一级，identity 已有 catalog.compliance 页面资源；且物理参数与安全属性在同一个端点写，用两套权限会出现「能改尺寸却改不了安全属性」）；页面 /products/sku/[id]/physical（physical-safety-workspace.tsx：**未采集是警示态且单独横幅说明后果**「渠道判定一律拒发」、已采集·无特殊属性是中性态 —— 两者文案与语气都不能一样；体积重区带**除数选择器**（空运 6000/快递 5000 快捷键）且没选除数时明确写「未选除数，不给体积重（除数是渠道口径）」而不是默默套默认值；缺一维时显示「缺长宽高，算不出体积」；校验错误逐条列出并禁用保存；错误态/空态/加载态/只读态齐备；SKU 详情页包装尺寸重量卡加「去维护物理与安全属性」入口）；physical-safety-model.ts 把 R1/R2/R3 三条口径固化在前端（null 一律显示「—」或「算不出来」**绝不渲染成 0**）；契约 packages/api-contracts 由 python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi 重新生成（+11 个 PhysicalSafety*/ChannelVerdict*/Carton* DTO，纯新增，identity/channel/warehouse 快照零漂移）；**测试** PhysicalSafetyIntegrationTest.java（28 例，真实 PG16 Testcontainers + 强制 RLS，连接池固定 1 条连接；物理参数与安全属性一次提交写入两张表且回传档案与库内一致；毛重小于净重被拒且**两张表都不留半截**；**体积重按调用方给的除数算**（6000→0.5kg、5000→0.6kg，不给除数就不给）；计费重双向逐值断言（泡货 1m³/0.2kg → 166.666667kg 体积重大；重货 3000cm³/8kg → 8kg 实重大）；缺一维不给体积；**未采集与都不带在档案上必须显示成不同状态**；**未采集一律判不可发**（连「渠道什么都不限制」也拒发）且附注写明按不知道处理；已采集且都不带才放行；带电遇不收带电的渠道被拦下而收带电的渠道放行但仍返回电池申报；筛危险品/带电/未采集/需要申报/分页总数一致；装箱按更紧约束取件数、多行合并、缺体积显式标明、箱规不合法被拒；**租户隔离三断言** —— 读/写/装箱/渠道判定跨租户全 404 且对方物理参数未被改、**伪造 X-Tenant-Id 头无效**（含带除数与筛选两种调用）、**单连接反复交替 5 轮不串租户**（档案字段与列表 ID 集合逐轮互不相交）；新表 FORCE RLS；**sku_package 的 NUMERIC(19,6) 精度未被本模块改坏**（复用而非另建表的回归断言）；经 HTTP 读回断言**落库的真实值**；写操作按权限收敛 403、未认证 401）+ PhysicalSafetyRulesTest.java（31 例纯规则，逐条钉死 R1/R2/R3、四个标记互不蕴含、申报项一一对应、装箱三条分支、筛选归一化）+ physical-safety-model.test.ts（27 例前端模型）；**反向验证**：把装箱「两条约束都算不出」时的返回值从 Integer.MAX_VALUE 改回 0 之前，单测即转红（Integer.MAX_VALUE 会一路传到「需要几箱」而算出一个看似正常的 1 箱，把「算不出来」变成一个错的小数）—— 证明该断言确实咬住了「算不出来 ≠ 0」；**不在本模块范围**：渠道表/渠道规则/面单（10 的事实）、UN 编号/危险品类别/认证证书（3.7.1.1 的事实）、库存与成本与售价（07/06/05 的事实，一律走各自端口不落本地列） |
| 3.8.1.1 | 导入向导 | — | D1–D2 | 未开发 | — |
| 3.8.1.2 | 校验报告 | — | D1–D2 | 未开发 | — |
| 3.8.2.1 | 导出 | — | D1–D2 | 未开发 | — |
| 3.9.1.1 | 重复 SKU 识别 | — | D1–D2 | 未开发 | — |
| 3.9.1.2 | SKU 合并 | — | D1–D2 | 未开发 | — |
| 3.9.2.1 | 完整度评分 | — | D1–D2 | 未开发 | — |
| 3.9.2.2 | 僵尸主数据清理 | — | D1–D2 | 未开发 | — |
| 4.1.1.1 | Listing 列表 | /listings | D5 | 未开发 | — |
| 4.1.1.2 | Listing 详情 | /listings | D5 | 未开发 | — |
| 4.1.1.3 | 生命周期与下架 | /listings | D5 | 未开发 | — |
| 4.1.2.1 | 映射管理 | — | D5 | 未开发 | — |
| 4.1.2.2 | 映射版本 | — | D5 | 未开发 | — |
| 4.1.2.3 | 套装组件映射 | — | D5 | 未开发 | — |
| 4.2.1.1 | 刊登编辑器 | /listings/editor | D5 | 未开发 | — |
| 4.2.1.2 | 批量编辑 | /listings/editor | D5 | 未开发 | — |
| 4.2.2.1 | 刊登模板 | /listings/templates | D5 | 未开发 | — |
| 4.2.2.2 | 描述模板 | /listings/templates | D5 | 未开发 | — |
| 4.2.3.1 | 平台类目映射 | — | D5 | 未开发 | — |
| 4.2.3.2 | 合规预检（违禁词/侵权） | — | D5 | 未开发 | — |
| 4.3.1.1 | 一键刊登 | /listings/publish | D5 | 未开发 | — |
| 4.3.1.2 | 批量发布 | /listings/publish | D5 | 未开发 | — |
| 4.3.1.3 | 发布任务 | /listings/publish | D5 | 未开发 | — |
| 4.3.2.1 | 内容回写 | — | D5 | 未开发 | — |
| 4.3.2.2 | 平台状态回流 | — | D5 | 未开发 | — |
| 4.4.1.1 | 链接采集 | /listings/collect | D5 | 未开发 | — |
| 4.4.1.2 | 批量/插件采集 | /listings/collect | D5 | 未开发 | — |
| 4.4.1.3 | 采集箱 | /listings/collect | D5 | 未开发 | — |
| 4.4.2.1 | 商品认领 | — | D5 | 未开发 | — |
| 4.4.2.2 | 批量搬迁 | — | D5 | 未开发 | — |
| 4.5.1.1 | Listing 表现 | — | D5 | 未开发 | — |
| 4.5.1.2 | 关键词排名 | — | D5 | 未开发 | — |
| 4.5.2.1 | 跟卖监控 | — | D5 | 未开发 | — |
| 4.5.2.2 | 上新与变更监控 | — | D5 | 未开发 | — |
| 5.1.1.1 | 价目表列表 | /pricing/lists | D6 | 未开发 | — |
| 5.1.1.2 | 价目项批量维护 | /pricing/lists | D6 | 未开发 | — |
| 5.1.2.1 | 优先级与冲突检测 | — | D6 | 未开发 | — |
| 5.2.1.1 | 成本构成 | /pricing/cost | D6 | 未开发 | — |
| 5.2.1.2 | 到岸成本 | /pricing/cost | D6 | 未开发 | — |
| 5.2.1.3 | 成本分摊 | /pricing/cost | D6 | 未开发 | — |
| 5.2.2.1 | 汇率查询 | /pricing/rates | D6 | 未开发 | — |
| 5.2.2.2 | 汇率快照 | /pricing/rates | D6 | 未开发 | — |
| 5.2.3.1 | 平台费用 | /pricing/fees | D6 | 未开发 | — |
| 5.2.3.2 | 物流与头程费用 | /pricing/fees | D6 | 未开发 | — |
| 5.3.1.1 | 利润测算器 | /pricing/calculator | D6 | 未开发 | — |
| 5.3.1.2 | 批量测算 | /pricing/calculator | D6 | 未开发 | — |
| 5.3.2.1 | 定价模板 | — | D6 | 未开发 | — |
| 5.3.2.2 | 价格规则 | — | D6 | 未开发 | — |
| 5.4.1.1 | 调价单 | /pricing/adjust | D6 | 未开发 | — |
| 5.4.1.2 | 批量调价 | /pricing/adjust | D6 | 未开发 | — |
| 5.4.2.1 | 平台价格同步 | — | D6 | 未开发 | — |
| 5.5.1.1 | 价格历史 | /pricing/history | D6 | 未开发 | — |
| 5.5.2.1 | 异常告警 | — | D6 | 未开发 | — |
| 6.1.1.1 | 供应商列表 | /supply/vendors | D6 | 未开发 | — |
| 6.1.1.2 | 供应商详情 | /supply/vendors | D6 | 未开发 | — |
| 6.1.1.3 | 供应商评级 | /supply/vendors | D6 | 未开发 | — |
| 6.1.2.1 | 档口与货源池 | /supply/sources | D6 | 未开发 | — |
| 6.1.2.2 | 同款货源推荐 | /supply/sources | D6 | 未开发 | — |
| 6.1.2.3 | 供应商资质 | /supply/sources | D6 | 未开发 | — |
| 6.2.1.1 | 缺货与断货风险 | /purchase/demand | D6 | 未开发 | — |
| 6.2.1.2 | 补货建议 | /purchase/demand | D6 | 未开发 | — |
| 6.2.1.3 | 采购计划 | /purchase/demand | D6 | 未开发 | — |
| 6.2.2.1 | 采购申请 | — | D6 | 未开发 | — |
| 6.2.2.2 | 需求合并与批量 | — | D6 | 未开发 | — |
| 6.3.1.1 | 采购单列表 | — | D6 | 未开发 | — |
| 6.3.1.2 | 采购单创建与编辑 | — | D6 | 未开发 | — |
| 6.3.1.3 | 采购单审批 | — | D6 | 未开发 | — |
| 6.3.2.1 | 跟单与交期 | — | D6 | 未开发 | — |
| 6.3.2.2 | 到货与逾期 | — | D6 | 未开发 | — |
| 6.4.1.1 | 加工单 | /purchase/processing | D6 | 未开发 | — |
| 6.4.1.2 | BOM 关联 | /purchase/processing | D6 | 未开发 | — |
| 6.4.2.1 | 质检单 | /purchase/quality | D6 | 未开发 | — |
| 6.4.2.2 | 不良品处理 | /purchase/quality | D6 | 未开发 | — |
| 6.5.1.1 | 采购对账 | /purchase/reconciliation | D6 | 未开发 | — |
| 6.5.2.1 | 采购分析 | /purchase/analytics | D6 | 未开发 | — |
| 6.6.1.1 | 开发立项 | — | D6 | 未开发 | — |
| 6.6.1.2 | 打样与样品管理 | — | D6 | 未开发 | — |
| 6.6.2.1 | 试产单 | — | D6 | 未开发 | — |
| 6.6.2.2 | 转量产与建档 | — | D6 | 未开发 | — |
| 7.1.1.1 | 库存总览 | /inventory | D3 | 已交付 | 迁移 warehouse V1__warehouse_inventory_core.sql（inventory_balance 生成列 available + RLS/GRANT、inventory_quality_status、inventory_transaction/entry append-only、inventory_transfer、inventory_policy）+ identity V8__warehouse_page_permissions + GET /warehouse/inventory/overview 与 /inventory/capabilities、阈值 CRUD /warehouse/inventory/policies + 下拉参照数据 /warehouse/{warehouses,legal-entities,skus,quality-statuses} + 页面 /inventory（总览工作区：合计卡 + 分布图 + 汇总表 + 行内展开 + 阈值维护）+ 测试 InventoryOverviewIntegrationTest 30 例（含总览合计==明细逐行求和、守恒式、租户隔离三断言）+ inventory-model.test.ts 15 例 |
| 7.1.1.2 | 库存明细 | /inventory | D3 | 已交付 | 迁移 warehouse V2__warehouse_inventory_bucket.sql（stock_bucket 六维最小事实单位 + 复合外键锁定归属 balance + 延迟约束触发器守恒 + guard_bucket_movement_time 版本/业务时间 + RLS/GRANT，warehouse_location 库位档案、inventory_lot 批次档案）+ GET /warehouse/inventory/buckets（法人→仓库→库位→SKU→批次逐级下钻，含 summary/reconciliation/actions）、GET /warehouse/inventory/bucket-actions、下拉 /warehouse/{locations,lots} + 页面 /inventory（总览 ↔ 库位明细双视图，总览行按 detailHref 契约下钻，明细含加载/空/错误态、账实核对告警、未分解提示、行内操作入口）+ 测试 InventoryBucketIntegrationTest 31 例（六维形状、DB 级守恒与不平拒绝、版本与业务时间口径、逐级下钻真实生效、租户隔离三断言） |
| 7.1.1.3 | 可用量计算 | /inventory | D3 | 未开发 | — |
| 7.1.2.1 | 库存流水台账 | — | D3 | 未开发 | — |
| 7.1.2.2 | 账实核对 | — | D3 | 未开发 | — |
| 7.1.3.1 | 多仓分布视图 | — | D3 | 未开发 | — |
| 7.2.1.1 | 预占需求列表 | /inventory/reservation | D3 | 未开发 | — |
| 7.2.1.2 | 预占分配明细 | /inventory/reservation | D3 | 未开发 | — |
| 7.2.1.3 | 释放与过期 | /inventory/reservation | D3 | 未开发 | — |
| 7.2.2.1 | 可售量口径 | — | D3 | 未开发 | — |
| 7.2.2.2 | 安全库存 | — | D3 | 未开发 | — |
| 7.3.1.1 | 批次管理 | /inventory/lots | D3 | 未开发 | — |
| 7.3.1.2 | 效期与先进先出 | /inventory/lots | D3 | 未开发 | — |
| 7.3.2.1 | 序列号 | /inventory/serials | D3 | 未开发 | — |
| 7.4.1.1 | 调整单管理 | /inventory/adjustments | D3 | 未开发 | — |
| 7.4.1.2 | 调整审批 | /inventory/adjustments | D3 | 未开发 | — |
| 7.4.2.1 | 盘点单 | /inventory/count | D3 | 未开发 | — |
| 7.4.2.2 | 盘点差异处理 | /inventory/count | D3 | 未开发 | — |
| 7.5.1.1 | 调拨单 | — | D3 | 未开发 | — |
| 7.5.1.2 | 在途库存 | — | D3 | 未开发 | — |
| 7.5.2.1 | 跨法人调拨 | — | D3 | 未开发 | — |
| 7.6.1.1 | 库龄与呆滞 | /inventory/aging | D3 | 未开发 | — |
| 7.6.2.1 | 库存健康 | /inventory/health | D3 | 未开发 | — |
| 7.7.1.1 | 货主与所有权 | — | D3 | 未开发 | — |
| 7.7.1.2 | 寄售与代销 | — | D3 | 未开发 | — |
| 7.7.2.1 | VMI 供应商管理库存 | — | D3 | 未开发 | — |
| 7.7.2.2 | 平台托管与全托管备货 | — | D3 | 未开发 | — |
| 7.8.1.1 | 分配策略配置 | — | D3 | 未开发 | — |
| 7.8.1.2 | 优先级与保底 | — | D3 | 未开发 | — |
| 7.8.2.1 | 分摊结果查看 | — | D3 | 未开发 | — |
| 7.8.2.2 | 缺货与饥饿预警 | — | D3 | 未开发 | — |
| 8.1.1.1 | 仓库列表 | — | D3 | 未开发 | — |
| 8.1.1.2 | 仓库详情 | — | D3 | 未开发 | — |
| 8.1.1.3 | 仓库配置 | — | D3 | 未开发 | — |
| 8.1.2.1 | 库位树 | — | D3 | 未开发 | — |
| 8.1.2.2 | 库位自动分配 | — | D3 | 未开发 | — |
| 8.2.1.1 | 收货单 | — | D3 | 未开发 | — |
| 8.2.1.2 | 收货差异 | — | D3 | 未开发 | — |
| 8.2.2.1 | 上架任务 | — | D3 | 未开发 | — |
| 8.2.2.2 | 待检处理 | — | D3 | 未开发 | — |
| 8.3.1.1 | 出库单列表 | — | D4 | 未开发 | — |
| 8.3.1.2 | 出库单详情 | — | D4 | 未开发 | — |
| 8.3.2.1 | 拣货任务 | — | D4 | 未开发 | — |
| 8.3.2.2 | 波次与合并拣货 | — | D4 | 未开发 | — |
| 8.3.3.1 | 复核扫描 | — | D4 | 未开发 | — |
| 8.3.3.2 | 打包与称重 | — | D4 | 未开发 | — |
| 8.3.4.1 | 发货交接 | — | D4 | 未开发 | — |
| 8.3.4.2 | 面单与标签打印 | — | D4 | 未开发 | — |
| 8.4.1.1 | 组装单 | — | D4 | 未开发 | — |
| 8.4.2.1 | 拆解单 | — | D4 | 未开发 | — |
| 8.5.1.1 | 作业效率 | — | D4 | 未开发 | — |
| 8.5.2.1 | 库位利用率 | — | D4 | 未开发 | — |
| 8.5.3.1 | 出库时效 | — | D4 | 未开发 | — |
| 9.1.1.1 | 订单列表 | — | D4 | 未开发 | — |
| 9.1.1.2 | 订单详情 | — | D4 | 未开发 | — |
| 9.1.1.3 | 订单变更与取消 | — | D4 | 未开发 | — |
| 9.1.2.1 | 平台订单同步 | — | D5 | 未开发 | — |
| 9.1.2.2 | 手工订单 | — | D4 | 未开发 | — |
| 9.2.1.1 | 待审队列 | /orders/review | D4 | 未开发 | — |
| 9.2.1.2 | 审单规则 | /orders/review | D4 | 未开发 | — |
| 9.2.1.3 | 异常拦截 | /orders/review | D4 | 未开发 | — |
| 9.2.2.1 | 地址与支付校验 | — | D4 | 未开发 | — |
| 9.2.2.2 | 风控校验 | — | D4 | 未开发 | — |
| 9.3.1.1 | 拆单 | — | D4 | 未开发 | — |
| 9.3.1.2 | 合单 | — | D4 | 未开发 | — |
| 9.3.2.1 | 分仓规则 | — | D4 | 未开发 | — |
| 9.3.2.2 | FBA 与自发货选择 | — | D4 | 未开发 | — |
| 9.3.3.1 | 履约请求 | — | D4 | 未开发 | — |
| 9.3.3.2 | 履约流程监控 | — | D4 | 未开发 | — |
| 9.4.1.1 | 待发货 | — | D4 | 未开发 | — |
| 9.4.1.2 | 发货记录 | — | D4 | 未开发 | — |
| 9.4.2.1 | 回传状态 | — | D5 | 未开发 | — |
| 9.5.1.1 | 时效分析 | — | D4 | 未开发 | — |
| 9.5.2.1 | 履约质量 | — | D4 | 未开发 | — |
| 9.6.1.1 | 代发模式配置 | — | D4 | 未开发 | — |
| 9.6.1.2 | 代发单与供应商下单 | — | D4 | 未开发 | — |
| 9.6.1.3 | 代发履约跟踪与异常 | — | D4 | 未开发 | — |
| 9.6.2.1 | 代发差价与对账 | — | D4 | 未开发 | — |
| 9.7.1.1 | 批发订单与账期 | — | D4 | 未开发 | — |
| 9.7.1.2 | 经销与分销管理 | — | D4 | 未开发 | — |
| 10.1.1.1 | 承运商列表 | — | D5 | 未开发 | — |
| 10.1.1.2 | 渠道配置 | — | D5 | 未开发 | — |
| 10.1.1.3 | 时效与限制 | — | D5 | 未开发 | — |
| 10.1.2.1 | 运费计算器 | — | D5 | 未开发 | — |
| 10.1.2.2 | 渠道比价 | — | D5 | 未开发 | — |
| 10.2.1.1 | 运单列表 | — | D5 | 未开发 | — |
| 10.2.1.2 | 运单申请 | — | D5 | 未开发 | — |
| 10.2.1.3 | 改址与取消 | — | D5 | 未开发 | — |
| 10.2.2.1 | 包裹详情 | — | D5 | 未开发 | — |
| 10.2.2.2 | 面单模板 | — | D5 | 未开发 | — |
| 10.3.1.1 | 轨迹查询 | — | D5 | 未开发 | — |
| 10.3.1.2 | 异常件 | — | D5 | 未开发 | — |
| 10.3.2.1 | 交运批次 | — | D5 | 未开发 | — |
| 10.3.2.2 | 揽收与签收确认 | — | D5 | 未开发 | — |
| 10.4.1.1 | 头程发运单 | — | D7 | 未开发 | — |
| 10.4.1.2 | 在途跟踪 | — | D7 | 未开发 | — |
| 10.4.2.1 | 海外仓连接 | — | D7 | 未开发 | — |
| 10.4.2.2 | 备货预报 | — | D7 | 未开发 | — |
| 10.5.1.1 | 报关单管理 | — | D7 | 未开发 | — |
| 10.5.1.2 | 申报要素 | — | D7 | 未开发 | — |
| 10.5.1.3 | 关税与税费 | — | D7 | 未开发 | — |
| 10.5.2.1 | 贸易单证 | — | D7 | 未开发 | — |
| 10.6.1.1 | 成本分析 | — | D5 | 未开发 | — |
| 10.6.2.1 | 时效分析 | — | D5 | 未开发 | — |
| 10.7.1.1 | 退货接收与处置决策 | — | D5 | 未开发 | — |
| 10.7.1.2 | 退运回国 | — | D5 | 未开发 | — |
| 10.7.1.3 | 弃置与销毁 | — | D5 | 未开发 | — |
| 11.1.1.1 | 售后单列表 | — | D4 | 未开发 | — |
| 11.1.1.2 | 售后单详情 | — | D4 | 未开发 | — |
| 11.1.1.3 | 售后受理 | — | D4 | 未开发 | — |
| 11.1.2.1 | 原因分类 | — | D4 | 未开发 | — |
| 11.1.2.2 | 商品问题归因 | — | D4 | 未开发 | — |
| 11.2.1.1 | 退货授权单 | — | D4 | 未开发 | — |
| 11.2.1.2 | 退货指令下发 | — | D4 | 未开发 | — |
| 11.2.2.1 | 退货入库 | — | D4 | 未开发 | — |
| 11.2.2.2 | 实收分配 | — | D4 | 未开发 | — |
| 11.2.3.1 | 质检与判定 | — | D4 | 未开发 | — |
| 11.2.3.2 | 转可售/残次/报废 | — | D4 | 未开发 | — |
| 11.2.3.3 | 供应商索赔 | — | D4 | 未开发 | — |
| 11.3.1.1 | 退款申请 | — | D4 | 未开发 | — |
| 11.3.1.2 | 退款状态 | — | D4 | 未开发 | — |
| 11.3.2.1 | 换货授权 | — | D4 | 未开发 | — |
| 11.3.2.2 | 换货新单 | — | D4 | 未开发 | — |
| 11.4.1.1 | 纠纷列表 | — | D7 | 未开发 | — |
| 11.4.1.2 | 证据与申诉 | — | D4 | 未开发 | — |
| 11.4.2.1 | 工单管理 | — | D7 | 未开发 | — |
| 11.4.2.2 | 知识库与话术 | — | D4 | 未开发 | — |
| 11.5.1.1 | 退货分析 | — | D7 | 未开发 | — |
| 11.5.2.1 | 质量分析 | — | D7 | 未开发 | — |
| 12.1.1.1 | 应收单管理 | — | D6 | 未开发 | — |
| 12.1.1.2 | 应收账龄 | — | D6 | 未开发 | — |
| 12.1.1.3 | 坏账与核销 | — | D6 | 未开发 | — |
| 12.1.2.1 | 应付单管理 | — | D6 | 未开发 | — |
| 12.1.2.2 | 付款计划 | — | D6 | 未开发 | — |
| 12.1.2.3 | 供应商对账 | — | D6 | 未开发 | — |
| 12.2.1.1 | 收款单 | — | D6 | 未开发 | — |
| 12.2.1.2 | 核销 | — | D6 | 未开发 | — |
| 12.2.2.1 | 付款单与审批 | — | D6 | 未开发 | — |
| 12.2.2.2 | 付款执行 | — | D6 | 未开发 | — |
| 12.3.1.1 | 结算单管理 | — | D6 | 未开发 | — |
| 12.3.1.2 | 交易明细 | — | D6 | 未开发 | — |
| 12.3.1.3 | 延迟结算 | — | D6 | 未开发 | — |
| 12.3.2.1 | 对账任务 | — | D6 | 未开发 | — |
| 12.3.2.2 | 差异处理 | — | D6 | 未开发 | — |
| 12.3.2.3 | 自动匹配规则 | — | D6 | 未开发 | — |
| 12.4.1.1 | 计价方法配置 | — | D6 | 未开发 | — |
| 12.4.1.2 | 成本计算执行 | — | D6 | 未开发 | — |
| 12.4.1.3 | 成本差异分析 | — | D6 | 未开发 | — |
| 12.4.2.1 | 费用归集 | — | D6 | 未开发 | — |
| 12.4.2.2 | 分摊规则 | — | D6 | 未开发 | — |
| 12.5.1.1 | 会计科目 | — | D6 | 未开发 | — |
| 12.5.1.2 | 会计期间 | — | D6 | 未开发 | — |
| 12.5.1.3 | 关账 | — | D6 | 未开发 | — |
| 12.5.2.1 | 凭证管理 | — | D6 | 未开发 | — |
| 12.5.2.2 | 财务报表 | — | D6 | 未开发 | — |
| 12.6.1.1 | 税率配置 | — | D6 | 未开发 | — |
| 12.6.1.2 | VAT/IOSS 申报数据 | — | D6 | 未开发 | — |
| 12.6.2.1 | 发票管理 | — | D6 | 未开发 | — |
| 12.7.1.1 | 账户管理 | — | D6 | 未开发 | — |
| 12.7.1.2 | 银行对账 | — | D6 | 未开发 | — |
| 12.7.2.1 | 资金分析 | — | D6 | 未开发 | — |
| 13.1.1.1 | 经营总览 | — | D7 | 未开发 | — |
| 13.1.1.2 | 多店对比 | — | D7 | 未开发 | — |
| 13.1.2.1 | 目标设定 | — | D7 | 未开发 | — |
| 13.1.2.2 | 达成追踪 | — | D7 | 未开发 | — |
| 13.2.1.1 | 销量趋势 | — | D7 | 未开发 | — |
| 13.2.1.2 | 分时与分周模式 | — | D7 | 未开发 | — |
| 13.2.2.1 | 店铺与站点 | — | D7 | 未开发 | — |
| 13.2.2.2 | SKU/SPU/Listing | — | D7 | 未开发 | — |
| 13.3.1.1 | 产品表现分析 | — | D7 | 未开发 | — |
| 13.3.1.2 | 生命周期与分层 | — | D7 | 未开发 | — |
| 13.3.2.1 | 流量转化 | — | D7 | 未开发 | — |
| 13.3.2.2 | 关联营销效果 | — | D7 | 未开发 | — |
| 13.4.1.1 | 多模式利润报表 | — | D7 | 未开发 | — |
| 13.4.1.2 | SKU 利润 | — | D7 | 未开发 | — |
| 13.4.2.1 | 成本结构 | — | D7 | 未开发 | — |
| 13.4.2.2 | 费用差异 | — | D7 | 未开发 | — |
| 13.4.3.1 | 利润预警 | — | D7 | 未开发 | — |
| 13.5.1.1 | 库存分析 | — | D7 | 未开发 | — |
| 13.5.2.1 | 采购分析 | — | D7 | 未开发 | — |
| 13.5.3.1 | 物流分析 | — | D7 | 未开发 | — |
| 13.6.1.1 | 自定义报表 | — | D7 | 未开发 | — |
| 13.6.1.2 | 看板搭建 | — | D7 | 未开发 | — |
| 13.6.2.1 | 导出与订阅 | — | D7 | 未开发 | — |
| 14.1.1.1 | 活动列表 | — | D7 | 未开发 | — |
| 14.1.1.2 | 活动详情 | — | D7 | 未开发 | — |
| 14.1.1.3 | 批量操作 | — | D7 | 未开发 | — |
| 14.1.2.1 | 广告组管理 | — | D7 | 未开发 | — |
| 14.1.2.2 | 广告类型差异 | — | D7 | 未开发 | — |
| 14.1.3.1 | 关键词管理 | — | D7 | 未开发 | — |
| 14.1.3.2 | 投放定位 | — | D7 | 未开发 | — |
| 14.2.1.1 | 竞价管理 | — | D7 | 未开发 | — |
| 14.2.1.2 | 分时策略 | — | D7 | 未开发 | — |
| 14.2.2.1 | 预算分配 | — | D7 | 未开发 | — |
| 14.2.2.2 | 自动规则 | — | D7 | 未开发 | — |
| 14.3.1.1 | 活动报表 | — | D7 | 未开发 | — |
| 14.3.1.2 | 广告组与词报表 | — | D7 | 未开发 | — |
| 14.3.1.3 | 商品维度报表 | — | D7 | 未开发 | — |
| 14.3.2.1 | 归因分析 | — | D7 | 未开发 | — |
| 14.3.2.2 | 效率诊断 | — | D7 | 未开发 | — |
| 14.4.1.1 | 促销列表 | — | D7 | 未开发 | — |
| 14.4.1.2 | 优惠券与秒杀 | — | D7 | 未开发 | — |
| 14.4.2.1 | 促销效果分析 | — | D7 | 未开发 | — |
| 14.5.1.1 | 达人库 | — | D7 | 未开发 | — |
| 14.5.1.2 | 建联与样品 | — | D7 | 未开发 | — |
| 14.5.2.1 | 带货归因 | — | D7 | 未开发 | — |
| 15.1.1.1 | 消息收件箱 | — | D7 | 未开发 | — |
| 15.1.1.2 | 消息发送 | — | D7 | 未开发 | — |
| 15.1.1.3 | 消息分类 | — | D7 | 未开发 | — |
| 15.1.2.1 | 邮件收发 | — | D7 | 未开发 | — |
| 15.1.2.2 | 邮件规则 | — | D7 | 未开发 | — |
| 15.2.1.1 | 评价列表 | — | D7 | 未开发 | — |
| 15.2.1.2 | 评价回复 | — | D7 | 未开发 | — |
| 15.2.1.3 | 差评处理 | — | D7 | 未开发 | — |
| 15.2.2.1 | Request a Review | — | D7 | 未开发 | — |
| 15.2.2.2 | 索评规则 | — | D7 | 未开发 | — |
| 15.3.1.1 | 客户列表 | — | D7 | 未开发 | — |
| 15.3.1.2 | 客户详情 | — | D7 | 未开发 | — |
| 15.3.1.3 | 跨店合并 | — | D7 | 未开发 | — |
| 15.3.2.1 | 客户标签 | — | D7 | 未开发 | — |
| 15.3.2.2 | 接触记录 | — | D7 | 未开发 | — |
| 15.4.1.1 | 工单列表 | — | D7 | 未开发 | — |
| 15.4.1.2 | 工单处理 | — | D7 | 未开发 | — |
| 15.4.2.1 | 知识库 | /crm/knowledge | D7 | 未开发 | — |
| 15.5.1.1 | 自动回复 | — | D7 | 未开发 | — |
| 15.5.2.1 | 关怀与催办 | — | D7 | 未开发 | — |
| 15.6.1.1 | 会员与等级 | — | D7 | 未开发 | — |
| 15.6.1.2 | 积分与权益 | — | D7 | 未开发 | — |
| 15.6.2.1 | 生命周期与流失预警 | — | D7 | 未开发 | — |
| 15.6.2.2 | 购物车与弃单挽回 | — | D7 | 未开发 | — |
| 16.1.1.1 | 账号健康总览 | — | D7 | 未开发 | — |
| 16.1.1.2 | 绩效指标监控 | — | D7 | 未开发 | — |
| 16.1.2.1 | 违规与处罚 | — | D7 | 未开发 | — |
| 16.1.2.2 | 申诉管理 | — | D7 | 未开发 | — |
| 16.2.1.1 | 规则管理 | — | D7 | 未开发 | — |
| 16.2.1.2 | 拦截执行 | — | D7 | 未开发 | — |
| 16.2.2.1 | 合规预检 | — | D7 | 未开发 | — |
| 16.2.2.2 | 认证与资质 | — | D7 | 未开发 | — |
| 16.3.1.1 | 税号与辖区 | — | D7 | 未开发 | — |
| 16.3.1.2 | 申报日历 | — | D7 | 未开发 | — |
| 16.3.2.1 | VAT/IOSS | — | D7 | 未开发 | — |
| 16.3.2.2 | EPR/包装法 | — | D7 | 未开发 | — |
| 16.3.2.3 | 销售税与 marketplace facilitator | — | D7 | 未开发 | — |
| 16.4.1.1 | 侵权库 | — | D7 | 未开发 | — |
| 16.4.1.2 | 检测与预警 | — | D7 | 未开发 | — |
| 16.4.2.1 | 品牌授权 | — | D7 | 未开发 | — |
| 16.4.2.2 | 授权凭证 | — | D7 | 未开发 | — |
| 16.5.1.1 | 黑名单 | — | D7 | 未开发 | — |
| 16.5.1.2 | 风险评分 | — | D7 | 未开发 | — |
| 16.5.2.1 | 登录与访问安全 | — | D7 | 未开发 | — |
| 16.5.2.2 | 关联风险 | — | D7 | 未开发 | — |
| 16.6.1.1 | PII 管理 | — | D7 | 未开发 | — |
| 16.6.2.1 | 数据出境与留存 | — | D7 | 未开发 | — |
| 17.1.1.1 | 租户列表 | — | D1–D2 | 未开发 | — |
| 17.1.1.2 | 子租户 | — | D1–D2 | 已交付 | tenant + tenant_closure 闭包表 + /children CRUD（含审计与 outbox）+ /org/children 层级工作区 |
| 17.1.1.3 | 层级授权 | — | D1–D2 | 未开发 | — |
| 17.1.2.1 | 法人档案 | — | D1–D2 | 已交付 | identity V5 legal_entity + CRUD + 删除保护触发器 + /org/legal-entities |
| 17.1.2.2 | 本位币与账簿 | — | D1–D2 | 未开发 | — |
| 17.2.1.1 | 部门树 | — | D1–D2 | 已交付 | 迁移 `backend/services/identity/src/main/resources/db/migration/V6__department_tree.sql`（code/parent_id/legal_entity_id/leader_membership_id + 自引用与法人/成员复合外键 + 树索引 + `department_tree()` SECURITY DEFINER 递归 CTE）；端点 `GET/POST/PUT/DELETE /identity/departments` + `PUT /identity/departments/{id}/parent`（防环校验、删除拒绝并回报具体数量）；页面 `apps/web/src/components/iam/department-tree.tsx` + `administration-panel.tsx`（真实树视图、HTML5 拖拽移动、成员分布计数）；测试 `IdentityIntegrationTest` 5 个部门专项用例（层级树/移动/防环/跨租户/删除保护） |
| 17.2.1.2 | 部门负责人 | — | D1–D2 | 未开发 | — |
| 17.2.2.1 | 成员管理 | — | D1–D2 | 已交付 | membership + app_user + role_assignment 表 + /members CRUD + /org/members 成员工作区 |
| 17.2.2.2 | 成员开通与入职 | — | D1–D2 | 未开发 | — |
| 17.2.2.3 | 离职与停用 | — | D1–D2 | 未开发 | — |
| 17.3.1.1 | 角色管理 | — | D1–D2 | 已交付 | role 表 + /roles CRUD + /org/roles 角色管理 |
| 17.3.1.2 | 页面权限矩阵 | — | D1–D2 | 已交付 | identity V1/V3/V4/V5 page_resource + permission + role_permission 表 + GET /identity/permissions + 权限矩阵页 |
| 17.3.2.1 | 角色分配 | — | D1–D2 | 未开发 | — |
| 17.3.2.2 | 数据范围 | — | D1–D2 | 已交付 | catalog V3 record_data_scopes + RLS scopeMode 数据范围隔离 |
| 17.3.3.1 | 越权检测 | — | D1–D2 | 已交付 | RLS FORCE + isolation；三条断言实跑（互不可见/伪造头无效/归还不串租户） |
| 17.4.1.1 | 审批流定义 | — | D4 | 未开发 | — |
| 17.4.1.2 | 审批规则 | — | D4 | 未开发 | — |
| 17.4.2.1 | 待我审批 | — | D4 | 未开发 | — |
| 17.4.2.2 | 审批记录 | — | D4 | 未开发 | — |
| 17.5.1.1 | 业务参数 | — | D1–D2 | 未开发 | — |
| 17.5.1.2 | 编号规则 | — | D1–D2 | 未开发 | — |
| 17.5.2.1 | 数据字典 | — | D1–D2 | 未开发 | — |
| 17.5.3.1 | 审计日志 | — | D1–D2 | 已交付 | 迁移 backend/services/identity/src/main/resources/db/migration/V7__audit_log.sql（补 resource_type/actor_name/authorization_context/changes 列、三组检索索引、SELECT+INSERT 授权、UPDATE/DELETE 触发器拒绝）· 端点 GET /identity/audit、/identity/audit/{id}、/identity/audit/export（AuditController.java）· 页面 apps/web/src/app/org/audit/page.tsx · 测试 AuditIntegrationTest.java（10 项：检索/详情/CSV 导出、租户隔离三断言、不可篡改、明文不入库）与 AuditRedactorTest.java（7 项脱敏） |
| 17.5.3.2 | 操作日志 | — | D1–D2 | 未开发 | — |
| 17.6.1.1 | 账簿与本位币 | — | D6 | 未开发 | — |
| 17.7.1.1 | 套餐与权益 | — | D7 | 未开发 | — |
| 17.7.1.2 | 用量统计 | — | D7 | 未开发 | — |
| 17.7.2.1 | 平台参数与开关 | — | D7 | 未开发 | — |
| 17.7.2.2 | 平台运维视图 | — | D7 | 未开发 | — |
| 18.1.1.1 | 应用与凭证 | — | D7 | 未开发 | — |
| 18.1.1.2 | 接口目录 | — | D7 | 未开发 | — |
| 18.1.1.3 | 调用监控 | — | D7 | 未开发 | — |
| 18.1.2.1 | 事件订阅 | — | D7 | 未开发 | — |
| 18.1.2.2 | 投递记录 | — | D7 | 未开发 | — |
| 18.1.3.1 | 导入导出任务 | — | D7 | 未开发 | — |
| 18.1.3.2 | 数据迁移 | — | D7 | 未开发 | — |
| 18.2.1.1 | 财务软件 | — | D7 | 未开发 | — |
| 18.2.2.1 | 支付与资金 | — | D7 | 未开发 | — |
| 18.2.3.1 | 其他对接 | — | D7 | 未开发 | — |
| 18.3.1.1 | 规则列表 | — | D7 | 未开发 | — |
| 18.3.1.2 | 规则编辑器 | — | D7 | 未开发 | — |
| 18.3.2.1 | 执行日志 | — | D7 | 未开发 | — |
| 18.3.2.2 | 规则效果 | — | D7 | 未开发 | — |
| 18.3.3.1 | 自动审批 | — | D7 | 未开发 | — |
| 18.4.1.1 | 对话助手 | — | D7 | 未开发 | — |
| 18.4.1.2 | 智能查询 | — | D7 | 未开发 | — |
| 18.4.2.1 | 文本与翻译 | — | D7 | 未开发 | — |
| 18.4.2.2 | 选品与需求预测 | — | D7 | 未开发 | — |
| 18.4.2.3 | 异常诊断 | — | D7 | 未开发 | — |
| 18.4.3.1 | 使用与配额 | — | D7 | 未开发 | — |
| 18.4.3.2 | 效果评估 | — | D7 | 未开发 | — |
| 18.5.1.1 | 模板管理 | — | D7 | 未开发 | — |
| 18.5.2.1 | 投递记录 | — | D7 | 未开发 | — |
