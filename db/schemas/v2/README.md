# V2：Java 微服务数据模型设计

> 2026-09-28：商品中心已完成第一阶段真实实现，SQL 以 backend 各服务 Flyway 迁移为准。[已实施范围与设计差异](../../../docs/operations/catalog-local-development.md) 是当前运行状态说明；下文仍包含未实施的完整目标模型。

> 最新属性模型见 [SPU 公共属性与 SKU 完整属性](03-product-flexible-attributes.md)：支持公共固定、公共默认/覆写、SKU 独立值及商品局部自定义。下文旧属性值表和规格值表方案由该文统一替代；规格轴和值仅有一份写入来源。

> 补充设计：[SPU/SKU、子租户与页面 CRUD 权限](02-product-tenant-permissions.md)。商品表明确命名为 spu；子租户、属性扩展与页面权限的细化规则以该文为准。

日期：2026-09-27。状态：逻辑设计基线，未生成迁移，未跑数据库验证。架构以 [ADR-0004](../../../docs/decisions/0004-java-spring-boot-microservices.md) 为准。这里覆盖可预见的核心业务实体，不把“所有可能的业务”伪装成已经定稿；未实施领域在各自阶段细化物理字段。

## 1. 现状与起点

当前不存在 backend 工程。父目录 01–09、erd.md 是旧模块化单体 SQL 草稿，有实体基础，但不能直接执行或直接转换为微服务迁移。本版替代旧稿的所有权、关系、事务与命名约定。旧稿字段只作为参考。

先按业务事实划分实体，再决定表；先明确事务边界，再划分服务。主数据说明“是什么”，单据说明“要做什么”，流水说明“已经发生什么”，投影说明“目前算出来是什么”。四者不能混在同一张万能表。

## 2. 服务与数据所有权

| 服务              | 唯一拥有的数据                                                     | 不能做的事                           |
| ----------------- | ------------------------------------------------------------------ | ------------------------------------ |
| identity          | 租户、身份映射、成员、部门、法人、角色、数据范围                   | 不处理商品、订单、库存               |
| catalog           | 商品、SKU、组合版本、分类、品牌、单位、商业伙伴、报价、价目        | 不维护可售库存；不拥有平台 listing   |
| channel           | 平台授权连接、店铺、站点、listing、SKU 映射、同步与 webhook        | 不直接写 trade 订单或 warehouse 库存 |
| trade             | 销售、采购、售后申请、履约需求、跨服务业务流程状态                 | 不扣库存、不自行确认已退款           |
| warehouse         | 仓库、库位、批次、序列号、库存账、预占、实物收发、调拨、盘点、包裹 | 不改销售价格、不写会计账             |
| finance           | 收付款、退款执行、应收应付、对账结算、成本账、总账                 | 不把订单状态当真实资金流水           |
| support（后续）   | 文件元数据、通知模板与投递                                         | 业务审计/outbox 不从本地事务搬到这里 |
| reporting（后续） | 事件构建的可重建报表投影                                           | 不成为业务写入来源                   |

这六个业务服务不是第一天全部实现。先 identity + catalog，后 warehouse + trade，再 channel 和 finance。一个服务可含多个内部领域模块；同服务内保留明确表 owner。

每个服务独立数据库及运行/迁移账号；初期可同一 PG 集群。服务内使用 `(tenant_id, id)` 复合外键；服务间只存逻辑 ID、必要快照和来源版本，不跨库建 FK。跨服务资源存在性及租户归属由 owner 的授权 API/可信事件确认；停用保留 tombstone，不硬删除已有业务引用。

## 3. 最需要分开的概念

1. **租户 / 法人 / 部门**：租户及子租户每个节点均是安全边界，层级不自动赋权；法人是货权、收付款和记账主体；部门是组织及数据权限。一个租户可含多法人、多部门，不能用一个 organization 混装。
2. **用户 / 成员 / 员工**：用户是全局登录身份，成员是用户加入租户的关系；员工人事档案按需要扩展，不强行要求每个员工有登录账号。一个成员可在多个部门、拥有多个带范围的角色。
3. **SPU / SKU / 渠道变体**：SPU 是商品族；SKU 是可计量、可管理库存的规格；渠道变体是店铺商品。多个渠道变体可映射一个 SKU，同一变体的组合映射必须版本化，历史订单冻结展开结果。
4. **组合销售 / 预组装商品**：虚拟套装扣组件库存，不持有独立库存；实物套装是普通可库存 SKU，由组装单消耗组件并产生成品。两者不共享一个含义模糊的 stock 字段。
5. **往来方 / 身份角色**：party 表示业务主体；同一主体可同时是供应商、客户、承运商。各角色独立详情表。不同店铺买家身份不按邮箱自动合并。
6. **订单行 / 履约行 / 库存行 / 财务行**：销售按客户购买口径，履约按组件和仓库，库存按实际货位/批次/货权，财务按费用和资金事实，必须有明确来源关联。
7. **库存位置 / 货权 / 状态**：在同一仓不等于属于同一法人，可用、待检、损坏、冻结也不是同一种库存。FBA 是外部履约仓模型，不是特殊 SKU。
8. **退货 / 退款**：退货是逆向实物流，退款是资金流；可仅退款、部分退款、退货不退款、换货。一个 return 不应直接触发增加可售库存。
9. **业务时间 / 系统时间**：平台下单时间、入库时间、记账日期、接收时间分别保存；不能用 created_at 代替全部时间。

## 4. 实体清单与关系

表名统一单数 snake_case。每行列出的实体分别建表，除明确说明的值对象/外部身份。所有租户业务表有 tenant_id；全局码表及 app_user 例外，不能暴露无授权的全表查询。

### identity：身份、组织与授权

| 实体                                                | 关键数据与关系                                                                       |
| --------------------------------------------------- | ------------------------------------------------------------------------------------ |
| tenant / tenant_closure / tenant_hierarchy_event    | 父子租户树、祖先索引、层级审计；每节点 1:N 法人、成员、部门，父子业务默认隔离        |
| app_user / external_identity                        | 全局用户；身份提供方 + subject 唯一，不以邮箱作为不可变身份；密码/MFA 交给身份提供方 |
| membership / invitation                             | tenant × user 唯一；邀请令牌摘要、失效时间、接收状态；离职停用成员而非删除用户       |
| legal_entity                                        | 法定名称、注册国家、登记号、本位币；记账主体，不等于租户                             |
| org_unit / membership_org_unit                      | 部门树、成员多对多关联；层级防环，不允许跨租户父节点                                 |
| role / page_resource / permission / role_permission | 租户角色、稳定页面资源、页面 CRUD/扩展动作与角色授权矩阵；后端执行鉴权               |
| role_assignment / role_assignment_scope             | 成员角色分配及作用范围；范围按法人、店铺、仓库、部门分别限定，不靠前端菜单做鉴权     |

### catalog：商品、商业伙伴和价格

| 实体                                                                              | 关键数据与关系                                                                                     |
| --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| spu / spu_translation                                                             | SPU，编码、名称、品牌、分类；翻译按语言唯一                                                        |
| sku / sku_identifier                                                              | spu 1:N SKU；SKU 编码租户唯一；外部条码按 scheme/value 明确唯一范围，不把店铺 seller_sku 当内部 ID |
| spu_attribute_group / category_attribute_rule                                     | 属性分组、类目模板；SPU 配置独立演进                                                               |
| sales_order_line_attribute_snapshot（trade）                                      | 交易相关有效属性快照，不受后续商品参数变更影响                                                     |
| spu_media / sku_media / sku_translation / sku_physical_spec / sku_customs_profile | 图片/视频、SKU 翻译、裸品物理参数、目的地报关版本                                                  |
| spu_category_link                                                                 | SPU 辅助分类，主分类在 spu.primary_category_id                                                     |
| tenant_catalog_publication / catalog_import_mapping                               | 跨租户显式商品发布、导入本地 SKU 及来源版本映射                                                    |
| category / brand / unit_of_measure                                                | 分类树、品牌、计量单位；同租户层级；单位含量纲与数量精度                                           |
| attribute_definition / attribute_option                                           | 属性的数据类型、用途、枚举值；核心字段不用任意 EAV                                                 |
| spu_attribute_binding / spu_attribute_allowed_option                              | 每商品配置属性、取值模式及规格轴，规格选项与属性共享一个值源                                       |
| spu_attribute_default / spu_attribute_default_item                                | SPU 公共值集合，支持类型化单值/多值                                                                |
| sku_attribute_override / sku_attribute_override_item                              | SKU 覆写与显式清空；有效值由公共值与覆写解析                                                       |
| sku_packaging / sku_uom_conversion                                                | 包装尺寸重量、采购箱规；同 SKU 单位换算及有效版本，单据冻结换算比例                                |
| bundle_version / bundle_component                                                 | 套装版本、组件 SKU 与数量；发布后不可变，禁止循环；订单冻结版本和组件                              |
| party / party_role                                                                | 主体类型 ORGANIZATION/PERSON；角色 SUPPLIER/CUSTOMER/CARRIER；不要跨租户自动共享                   |
| supplier_profile / customer_profile / carrier_profile                             | 角色特有信息、账期、服务能力；1:1 party，不能用一个巨大可空字段表                                  |
| party_address / party_contact                                                     | 可变联系方式与地址；订单/收发货另存不可变快照，非“全局地址服务”                                    |
| supplier_offer / supplier_offer_tier                                              | 供应商货号、SKU、采购单位、币种、MOQ、交期、有效期与阶梯价；允许一个供应商多个报价版本             |
| price_list / price_list_item                                                      | 一价目一币种、法人/店铺适用范围、生效区间；SKU、数量门槛、价格；同优先级冲突要拒绝，不随机选价     |
| customer_tag / customer_tag_link / customer_interaction                           | 标签与接触记录，后续 CRM；联系方式属敏感数据，不写普通日志                                         |

### channel：多平台与同步

| 实体                                                | 关键数据与关系                                                                    |
| --------------------------------------------------- | --------------------------------------------------------------------------------- |
| platform / marketplace                              | 全局平台码、站点码、国家/默认币种；平台不等于站点                                 |
| channel_connection / credential_ref                 | 一次商家授权连接及密钥管理器引用、有效期、权限范围；库里不存明文 token            |
| shop / shop_marketplace                             | 商业店铺及启用站点；绑定一个法人，connection 可服务多个 shop/站点                 |
| listing / listing_variant                           | 平台商品与可售变体；唯一键含租户、店铺、站点、平台 ID；seller_sku 为属性          |
| listing_mapping_version / listing_mapping_component | 渠道变体到内部 SKU/套装的映射版本；组件数量>0，旧版保留，错误映射进入人工处理队列 |
| external_customer_identity                          | 店铺 + 平台 buyer ID，逻辑关联 catalog party；不跨店按邮箱合并                    |
| sync_cursor / sync_run / sync_failure               | 按连接、站点、资源、分区保存游标；只有处理提交后推进，失败可续传                  |
| webhook_receipt / external_snapshot                 | 签名验证结果、外部事件 ID、来源版本、脱敏原文或对象引用、处理状态；保留期可控     |
| platform_operation                                  | 上架、回传物流、更新库存的持久化任务；业务幂等键、外部请求 ID、未知结果及核验状态 |

### trade：销售、采购、售后与履约需求

| 实体                                                            | 关键数据与关系                                                                                                    |
| --------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| sales_order / sales_order_line                                  | 法人、渠道来源、币种、买家引用、下单时间；行保留商品、单价、税、折扣及 SKU/映射版本快照；外部订单号按来源范围唯一 |
| order_address_snapshot / order_charge / order_charge_allocation | 地址快照、运费/折扣/税/其他费用、费用到行的分摊；分摊之和等于头金额，保留舍入差额                                 |
| order_line_component                                            | 售出一行展开的组件与数量，历史快照，不受后续 bundle 修改影响                                                      |
| order_status_history                                            | 状态变更、原因、操作者、时间、来源事件；支付、履约、售后状态分别表达                                              |
| fulfillment_request / fulfillment_request_line                  | 订单履约批次与组件需求；一订单可多次、多仓履约，不设 order_id 唯一                                                |
| fulfillment_process                                             | 流程版本、reservation ID、超时、尝试次数、补偿状态；断电重启可继续，不能只放内存                                  |
| purchase_order / purchase_order_line                            | 法人、供应商、币种、采购单位、基础单位换算快照、数量、承诺日期；允许分批到货                                      |
| purchase_receipt_allocation                                     | warehouse 收货行事件到采购行的验收数量分配；按收货事件去重，不重复记已收数量                                      |
| return_authorization / return_authorization_line                | 销售退货授权、原订单行、批准数量、原因、目标处理方式；不等同实际收到                                              |
| return_receipt_allocation                                       | 实收退货行到授权行的关联及处置结果；质检合格才可转为可售                                                          |
| refund_request / refund_request_line                            | 退款申请、原资金记录引用、币种、金额及原因，finance 执行并回报结果                                                |
| exchange_order_link                                             | 换货授权与新销售单关联，保留原单追溯，不修改原始出库事实                                                          |
| purchase_return / purchase_return_line                          | 采购退货授权及原收货行引用；由 warehouse 执行出库，finance 处理供应商退款/贷项                                    |

### warehouse：库存账与实物流

| 实体                                                               | 关键数据与关系                                                                                                                |
| ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| warehouse / warehouse_location                                     | 自营/三方/FBA 仓、国家、时区；库位层级、类型；仓库和库位不能跨租户引用                                                        |
| stock_item_projection                                              | 来自 catalog 的 SKU 可库存性、单位、追踪模式及版本；只读副本，catalog 是事实来源                                              |
| inventory_lot / inventory_serial                                   | lot 保存批号、生产/失效日期；serial 保存单件序列号、SKU、lot、当前位置及状态，两者不能合成同一实体                            |
| stock_bucket / inventory_balance                                   | bucket=法人货权+SKU+库位+批次+质量状态；余额 on_hand/reserved/version；无批次用明确 NULL 唯一语义；available=on_hand-reserved |
| inventory_transaction / inventory_entry                            | 一次库存业务及带正负号的分录；数量、bucket、来源单据行、基础单位、发生时间；append-only，反向分录纠错                         |
| inventory_reservation / reservation_allocation / reservation_entry | 业务需求与实际 bucket 分配；数量、消费/释放/过期事实；held/committed/consumed/released/expired 明确迁移，终态不可复活         |
| goods_receipt / goods_receipt_line                                 | 采购/退货/调拨到仓实收；预期与实收分开，支持部分、差异、拒收、待检                                                            |
| outbound_order / outbound_order_line                               | 履约/采购退货/调拨实发；来源需求、已拣/已发量，不等同销售订单                                                                 |
| pick_task / pick_task_line                                         | 拣货任务、实际库位/批次/序列号、操作者；任务完成不代表已发货                                                                  |
| parcel / parcel_item / shipment / tracking_event                   | 包裹内 SKU/数量与出库行对应；运单可含多个包裹，轨迹按承运商事件 ID 去重；承运商网络副作用本地 outbox 驱动                     |
| inventory_transfer / inventory_transfer_line                       | 源仓、目的仓、计划/已发/实收数量；发出进入在途 bucket，分次收货从在途转入目的仓，不假装异地收发是同一瞬间                     |
| stock_count / stock_count_line / stock_adjustment                  | 盘点时点、账面/实盘/差异、复核批准人与原因；差异审批后生成库存账，不直接覆盖余额                                              |
| assembly_order / assembly_component                                | 实物套装生产/拆解：同本地事务消耗组件并增加成品；仅虚拟套装不产生组装库存                                                     |
| external_stock_observation                                         | 第三方仓报告的库存与采集时间/版本；与 ERP 自算库存并存对账，不能用平台快照覆盖自营库存账                                      |

库存数量是否负数由库存策略决定；第一期默认禁止负库存。质量状态为不可售时禁止销售预占。bucket 转移的数量合计为零；采购入库/销售出库有外部来源/去向，不能强求整个账总和永远零。序列号库存每件只能处于一个有效位置，出入库必须校验序列号与数量一致。

### finance：业务资金与会计

| 实体                                                                 | 关键数据与关系                                                                                    |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| currency / exchange_rate                                             | 共享发布的币种定义、本服务汇率源和时点；单据记录实际使用汇率及舍入方式                            |
| financial_account / accounting_book / accounting_period              | 银行/支付/平台资金账户；法人账簿、本位币与会计期间、关账状态                                      |
| receivable / payable / obligation_line                               | 应收/应付及明细、来源事实、到期日；未发货订单不默认等于收入，具体确认政策单独设计                 |
| payment / payment_attempt / payment_allocation                       | 一次资金收付、多次执行尝试、多笔应收应付核销；支持部分与合并支付、预收预付，不能只挂单个 order_id |
| refund / refund_attempt                                              | 原收款引用、申请幂等键、币种、金额、执行状态；累计成功+在途退款不能超过可退余额，失败释放占用     |
| settlement / settlement_line                                         | 平台账单与逐笔销售/退款/费用/调整/代扣税明细，保留外部流水和币种，不强制每行都有订单              |
| reconciliation_run / reconciliation_match / reconciliation_exception | 核对范围、匹配分配、未匹配原因及人工决议；支持一对多、多对一，不能“金额相同即匹配”                |
| cost_layer / inventory_cost_entry / landed_cost_allocation           | 入库成本层、出库成本与运费关税分摊；warehouse 管数量，finance 管价值；成本算法一期确认后再实施    |
| ledger_account / journal_entry / journal_entry_line                  | 法人账簿科目、凭证、分录；本位币借贷平衡、原币及汇率快照；已过账不可改，只能冲销                  |
| invoice / invoice_line / credit_note                                 | 商业发票、贷项；本期不承诺各国税务申报，税码/税率/税额用来源快照保留                              |

凭证 source_type/source_id 是逻辑来源引用，不给多态 source_id 加指向 sales_order 的假 FK。过账事务锁凭证头，校验至少两行、金额有效、期间开放、借贷平衡，并禁止过账后改行；数据库防护须覆盖 INSERT/UPDATE/DELETE、旧/新凭证头与整单删除。旧稿触发器不能作为已验证实现。

### 各服务本地技术实体

| 实体                                      | 内容                                                                                                                             |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| outbox_event                              | event_id、tenant、aggregate_id/version、event_type/schema_version、payload、发生时间、发布状态、下次重试、租约；业务与事件同事务 |
| inbox_message                             | consumer + event_id 唯一；与业务结果同事务，不能先标已消费再写业务                                                               |
| idempotency_request                       | tenant + scope + key 唯一，request_hash、处理状态、响应/资源 ID、过期时间；业务长期唯一约束不能因清理此表失效                    |
| audit_event                               | actor、动作、资源、授权上下文、脱敏变更；本地持久化后可异步集中；不记录密码/完整客户地址                                         |
| reference_projection                      | 各服务按需要建具名投影如 legal_entity_projection，不建万能跨库实体表                                                             |
| file_object / resource_attachment（后续） | support 管对象存储元数据；各 owner 管业务资源与文件授权关联，不能凭 storage_key 越权下载                                         |

## 5. 统一物理设计规则

- 业务实体 UUID（应用生成 UUIDv7，库使用 uuid；具体 Java 库实现阶段验证）；编码是可读业务标识，不是外键。
- 业务表 tenant_id NOT NULL；被本地引用实体建 UNIQUE(tenant_id,id)，本地 FK 包含 tenant_id；列表索引一般以 tenant_id 开头。
- 跨库 tenant_id/legal_entity_id/sku_id/user_id 是逻辑引用；事件和任务必须恢复可信租户上下文。每服务独立做认证授权，不能只依赖网关。
- 每服务启用 ENABLE + FORCE RLS，运行账号非 owner/superuser/BYPASSRLS；事务内 set_config(..., true)，无上下文拒绝访问。引导/成员查询使用单独窄权限路径，不给普通请求绕过 RLS 权限。
- 金额 NUMERIC(19,4)、单价/汇率 NUMERIC(19,8)、数量 NUMERIC(19,6)，Java BigDecimal；数量精度、汇率方向、金额舍入都明确；账簿本位币归法人，租户默认币种只是展示默认。
- 绝对时间 timestamptz，业务日期 date，另存需要解释业务日的 IANA 时区；状态 TEXT + CHECK，允许值不等于合法转换，转换需版本条件与状态机。
- 可变聚合带 version bigint，更新用 expected_version；created_at/updated_at 由写路径维护，DEFAULT now() 不会自动更新 updated_at。
- 主数据停用保留编码；业务单据取消保留历史；流水只冲销。GDPR/PII 清理与财务留存分开设计，不能以 append-only 为由无限保存原始 PII。
- JSONB 用于事件载荷、外部扩展和脱敏快照；核心查询、数量、金额、关系和约束必须结构化。无通用 EAV 承载全业务，也不建万能 business_document 让所有订单继承。
- 状态查询建 tenant/status/time 索引；单据行建 tenant/header_id 索引；外部来源建幂等唯一键；不先给每列建索引。大表分区按实际规模再定。

## 6. 核心业务闭环与失败恢复

### 销售履约

1. channel 验签并持久化 webhook/原始来源，解析 shop→tenant；不能信任请求传来的 tenant header。调用 trade 使用稳定来源键。
2. trade 本地事务写订单快照、组件与 outbox。映射缺失时进入待映射状态，不凭名称猜 SKU。
3. warehouse 处理预占命令：锁定/条件更新相关 bucket，所有组件不足则本次整体回滚；预占+余额+预占流水+inbox+outbox 同事务。按固定 bucket 顺序加锁防死锁。
4. trade 收到结果更新持久化流程；超时进入核验/重试而非断定失败。取消与预占乱序用流程版本和取消终态记录阻止迟到预占复活。
5. warehouse 承诺分配后停止普通 TTL 自动释放；消费预占与实发扣减同事务。销售取消如已发货，转售后，不“回滚 HTTP 发货”。
6. 发货事件到 trade 更新数量，到 finance 按收入/成本政策处理，到 channel 回传物流。每个消费者本地去重；持续失败告警并人工恢复。

所有增量事实都逐条按 event_id 去重处理；只允许完整状态快照按版本丢弃过期数据。不能用“只接受最大版本”漏掉中间库存/资金增量；版本有缺口则补取/排队并对账。

### 采购、调拨、退货

- 采购批准→warehouse 分批收货→trade 更新已收数量→finance 确认应付/成本；物理收货不是采购服务写库存。
- 调拨出库→源仓转在途→目的仓多次收货→在途转目的仓；丢失/破损用带原因调整；跨法人属于货权转移和财务业务，不能伪装普通同法人调拨。
- 售后授权→warehouse 实收待检→按处置转可售/残次/报废；退款申请独立交 finance，外部超时先查询交易再重试，不能重复支付。

## 7. 开发顺序和完成标准

| 阶段              | 交付                                             | 必须证明                                                            |
| ----------------- | ------------------------------------------------ | ------------------------------------------------------------------- |
| D0 当前           | 服务边界、实体目录、基础字段设计、未决业务项     | 设计之间不矛盾；不声称 SQL 已能运行                                 |
| D1 身份基础       | identity 工程、Flyway、成员/权限与可信租户上下文 | A/B 租户隔离、伪造 header 无效、连接复用无残留、未授权资源不可访问  |
| D2 商品主数据     | catalog 商品/SKU/变型/单位 API、OpenAPI 前端联调 | 同 SPU 重复组合被拒绝、跨租户关联被拒绝、已引用主数据停用不破坏历史 |
| D3 库存账         | warehouse 入库、余额、预占、释放、库存流水       | 并发不超卖、重复命令只记一次、余额/流水可核对、事务失败无半笔账     |
| D4 手工订单闭环   | trade 手工单→预占→分批出库→取消/售后             | 重复/乱序/丢响应/重启可恢复、拆单不超发、部分退货不超原数量         |
| D5 首个平台       | channel 一个平台的商品映射、订单同步、物流回传   | 重复 webhook、分页续传、限流与外部未知结果可恢复                    |
| D6 采购与基础财务 | 分批采购、退货、应收应付、结算对账               | 部分核销、不同币种、退款上限、费用分摊与对账差异                    |
| D7 扩展           | 高级 WMS、成本核算、总账、CRM、报表、订阅计费    | 逐域补齐字段与迁移，不一次建完未来表                                |

第一段可交付业务建议是：**登录选择租户 → 创建商品与两个规格 SKU → 初始化入库 → 手工销售单 → 预占 → 出库 → 看库存账**。外部平台接入排在这一闭环后，避免业务规则和第三方异常同时调试。

## 8. 假设与需确认项

默认按一个租户多个法人/部门、支持部分收发、虚拟套装、未来批次/序列号建模；这些是设计选择，不表示功能第一期全部上线。

- 第一家客户经营品类：普通标准件，还是食品/化妆品/电子产品？决定第一期是否启用效期与序列号，维度已保留。
- 第一个接入平台、第一期自营仓还是 FBA/第三方仓？不阻塞 D1/D2，影响 D3/D5 实现。
- 是否存在寄售货权、跨法人调拨、分销或 B2B 账期？本版默认法人自有库存；寄售需扩展 stock_owner，不能拿 tenant 代替货主。
- 财务目标先做经营结算/应收应付，还是法定总账？成本法、税务辖区、收入确认政策在 D6 前确认，不替用户擅定会计政策。
- SaaS 商业化若进首发，需要补 subscription_plan/subscription/entitlement/usage_record，独立于 ERP 客户的销售收入；暂不实施。

## 9. 对旧设计的纠偏

- 去除所有跨服务实体 FK，包括每库 REFERENCES tenant/app_user；保留本地复合 FK。
- outbox/inbox 归本地事务，不归公共 platform 服务。
- 一订单多履约/包裹；包裹必须有 parcel_item，不能只有重量和运单号。
- 部门、法人、成员多部门分开；前端 org 已明确是“组织/权限”，不是 Amazon 站点。
- 批次与序列号分开，增加质量、货权、在途与盘点维度。
- 更正旧稿“表复数但 SQL 单数”、outbox 既 append-only 又更新投递字段、幂等记录作为业务事实等矛盾。
- 财务旧稿 journal_entry.source_id 的错误 sales_order FK 与不完备平衡触发器不沿用。
