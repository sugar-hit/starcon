/** Task destinations shared by navigation, page headers, search and workspace tabs. */
export interface TaskPage {
  href: string;
  domain: string;
  label: string;
  description: string;
  /**
   * 该任务页的表格字段。
   *
   * 🔴 **只有「还没有真实页面」的条目才需要它** —— 它是 `TaskWorkspace`
   * （`[...slug]` 兜底页）渲染出来的**占位表头**，不是真实表结构。
   *
   * <p>已经有真实 `page.tsx` 的条目**不许**写这一串字段：那串字段描述的是一张
   * 并不存在的表，写在那里只会让后来的人以为那一页真有那些列。
   * 本仓库此前有 30 个条目挂着这样的字段表，其中 7 个是域首页
   * （定价 / CRM / 供应 / 采购 / 物流 / 仓储 / 利润）—— 它们真正的内容是**域索引**，
   * 压根不是一张表。
   */
  fields?: string[];
  /**
   * 该任务页的处理流程（三步）。
   *
   * 🔴 同 {@link fields}：只有「还没有真实页面」的条目才需要它。
   * 有真实页面的条目写一条流程，等于替那一页编了一条它并不执行的流程。
   */
  workflow?: string[];
  /**
   * 该任务页是否已有真实页面（apps/web/src/app 下存在对应 page.tsx）。
   *
   * 这是「先有页面、后有菜单」的开关：未实现的页面不进导航、工作区 Tab 与搜索，
   * 避免用户点进去落到 [...slug] 兜底页。设计意图见
   * docs/product/capability-blueprint/README.md，功能蓝图里记录"应该有什么"，
   * 这里只记录"现在有什么"。
   *
   * 该标记由 nav-routes.test.ts 对照真实文件系统校验，防止手滑写成假阳性。
   */
  implemented: boolean;
}
export const TASK_PAGES: TaskPage[] = [
  {
    href: '/channels',
    implemented: true,
    domain: 'channels',
    label: '渠道与店铺',
    description: '平台目录、授权连接、店铺档案与启用站点。',
  },
  {
    href: '/products',
    implemented: true,
    domain: 'products',
    label: '商品档案',
    description: '维护 SPU、SKU、自定义属性与公共值继承。',
  },
  {
    href: '/products/research',
    implemented: true,
    domain: 'products',
    label: '选品与开发',
    description: '从选品线索、样品评估到开发立项，保留决策依据。',
  },
  {
    href: '/products/compliance',
    implemented: true,
    domain: 'products',
    label: '合规与资质',
    description: '按销售国家与品类管理认证、品牌授权和到期提醒。',
  },
  {
    href: '/products/assets',
    implemented: true,
    domain: 'products',
    label: '素材与翻译',
    description: '集中管理产品图片、文案和各站点语言版本。',
  },
  {
    href: '/org/roles',
    implemented: true,
    domain: 'org',
    label: '角色配置',
    description: '为角色勾选页面权限与数据范围，保存后立即生效。',
  },
  {
    href: '/org/members',
    implemented: true,
    domain: 'org',
    label: '用户与成员',
    description: '维护成员账号、所属部门与角色分配，必要时转移所有者。',
  },
  {
    href: '/org/departments',
    implemented: true,
    domain: 'org',
    label: '部门管理',
    description: '维护部门作为成员归属，本部门数据范围按此判定。',
  },
  {
    href: '/org/children',
    implemented: true,
    domain: 'org',
    label: '子租户管理',
    description: '创建子租户并指定首位所有者，子租户需分别授权才能访问。',
  },
  {
    href: '/org/hierarchy',
    implemented: true,
    domain: 'org',
    label: '层级授权',
    description: '父租户访问子租户数据的显式授权：数据域、只读可写、有效期与撤销。',
  },
  {
    href: '/listings',
    implemented: true,
    domain: 'listings',
    label: '在线商品',
    description: '按平台与店铺管理在线商品、映射和同步状态。',
  },
  {
    href: '/listings/drafts',
    implemented: false,
    domain: 'listings',
    label: '刊登草稿',
    description: '集中准备多站点刊登资料并进行发布前校验。',
    fields: ['草稿编号', '平台', '店铺', '语言', '负责人', '校验结果'],
    workflow: ['选择模板', '补齐资料', '校验发布'],
  },
  {
    href: '/listings/publish',
    // 4.3.1.2 批量发布已交付：apps/web/src/app/listings/publish/page.tsx 已存在。
    // domain 'listings' 与 devSystem 的「刊登中心」(/listings) 匹配，
    // nav-config 的 children 是按 domain 从 IMPLEMENTED_TASK_PAGES 派生的，
    // 所以这里翻成 true 就会自动获得菜单入口，不需要另改 nav-config。
    implemented: true,
    domain: 'listings',
    label: '发布任务',
    description: '追踪批量刊登、平台反馈和失败重试。',
  },
  {
    href: '/pricing',
    implemented: true,
    domain: 'pricing',
    label: '定价中心',
    description: '多币种价格、汇率、定价策略与平台费率模型。',
  },
  {
    href: '/pricing/simulation',
    implemented: false,
    domain: 'pricing',
    label: '价格测算',
    description: '纳入采购、头程、平台费和履约成本测算售价。',
    fields: ['SKU', '币种', '采购成本', '平台费', '履约费', '目标毛利'],
    workflow: ['选择商品', '核对费用', '比较方案'],
  },
  {
    href: '/pricing/changes',
    implemented: false,
    domain: 'pricing',
    label: '调价记录',
    description: '审阅每次价格变更的原因、审批与平台结果。',
    fields: ['SKU', '旧价', '新价', '币种', '审批人', '执行状态'],
    workflow: ['提出调整', '审批', '回查结果'],
  },
  {
    href: '/purchase',
    implemented: true,
    domain: 'purchase',
    label: '采购中心',
    description: '采购需求、采购单、1688 档口直连。',
  },
  {
    href: '/purchase/plans',
    implemented: false,
    domain: 'purchase',
    label: '采购计划',
    description: '将补货需求合并为可审批的采购计划。',
    fields: ['计划编号', 'SKU', '建议数量', '需求日期', '供应商', '审批状态'],
    workflow: ['汇总需求', '评审数量', '转采购单'],
  },
  {
    href: '/purchase/receiving',
    implemented: false,
    domain: 'purchase',
    label: '到货与质检',
    description: '登记采购到货与检验差异，关联仓库入库。',
    fields: ['到货单', '采购单', '实收数量', '合格数量', '差异原因', '质检人'],
    workflow: ['收货登记', '质检', '生成入库'],
  },
  {
    href: '/purchase/returns',
    implemented: false,
    domain: 'purchase',
    label: '采购退货',
    description: '处理不良品与供应商退换货并追踪退款。',
    fields: ['退货单', '供应商', '采购单', '退货数量', '原因', '退款状态'],
    workflow: ['申请退货', '审核出库', '核对退款'],
  },
  {
    href: '/supply',
    implemented: true,
    domain: 'supply',
    label: '供应中心',
    description: '供应商档案、评分、分级管理。',
  },
  {
    href: '/supply/quotes',
    implemented: false,
    domain: 'supply',
    label: '询价与报价',
    description: '比较采购报价、起订量、交期与币种。',
    fields: ['询价单', '供应商', 'SKU', '报价', 'MOQ', '交期'],
    workflow: ['发起询价', '对比报价', '确认报价'],
  },
  {
    href: '/supply/performance',
    implemented: false,
    domain: 'supply',
    label: '供应商绩效',
    description: '按准时交付、质检和售后表现复核供应能力。',
    fields: ['供应商', '统计周期', '准时率', '合格率', '退货情况', '改进项'],
    workflow: ['汇总交付', '评审表现', '跟踪改进'],
  },
  {
    href: '/orders',
    implemented: true,
    domain: 'orders',
    label: '全部订单',
    description: '按平台、店铺、订单状态和履约方式核对订单。',
  },
  {
    // 9.7.1.1 批发订单与账期。与 /orders 同属订单域，domain 取域级 'orders'，
    // 导航条目由 SYSTEMS 的 children 从 IMPLEMENTED_TASK_PAGES 派生（见 nav-config），
    // 所以这里**只**登记任务页，不需要改共享的导航常量文件。
    // 刻意不是 /orders 的第三个 tab：那边是零售订单列表与手工录入（只读投影 / 零售录入），
    // 本页是 B2B 录入 + 发货确认应收，账期应收按到期日排序（资金计划 12.1.2.2 取数顺序）。
    href: '/orders/b2b',
    implemented: true,
    domain: 'orders',
    label: '批发订单',
    description: '按客户分级价与阶梯价录单，管理起订量、账期结算与开票对账需求。',
  },
  {
    // 9.7.1.2 经销与分销管理。与 /orders/b2b 同属订单域且**紧邻**，domain 取域级 'orders'，
    // 导航条目同样由 SYSTEMS 的 children 从 IMPLEMENTED_TASK_PAGES 派生（见 nav-config），
    // 这里只登记任务页，不改共享的导航常量文件。
    // 刻意不是 /orders/b2b 的 tab：那边是「下单 → 发货 → 账期应收」的流转，
    // 本页是「授权 → 考核 → 计提 → 预警」的台账，四件事与读写边界都不同。
    href: '/orders/dealers',
    implemented: true,
    domain: 'orders',
    label: '经销商与分销',
    description:
      '管理经销商授权区域与产品线、授权期限、经销价与返点，按销量配额与回款考核，并监控跨区域窜货。',
  },
  {
    href: '/orders/review',
    implemented: false,
    domain: 'orders',
    label: '订单审核',
    description: '集中处理地址、缺货、风控和商品匹配异常。',
    fields: ['订单号', '异常类型', '库存状态', '地址校验', '责任人', '处理进度'],
    workflow: ['识别异常', '人工核对', '放行履约'],
  },
  {
    href: '/orders/review/rules',
    implemented: true,
    domain: 'orders',
    label: '审单规则',
    description:
      '按店铺、金额、买家等级、商品、收货国家配置自动审单；命中黑名单、地址异常、大额、异常折扣的强制转人工。',
  },
  {
    href: '/orders/review/intercept',
    implemented: true,
    domain: 'orders',
    label: '异常拦截',
    description:
      '识别并拦截异常订单：地址不可达/信息不全、黑名单买家、重复下单、异常折扣（低于成本）、疑似欺诈、超出收货国家限制、平台合规风险。',
  },
  {
    href: '/orders/risk-validation',
    implemented: true,
    domain: 'orders',
    label: '风控校验',
    description: '多信号风险打分：买家历史、设备与 IP 关联、下单频次、金额异常、地址集中度。',
  },
  {
    href: '/orders/address-payment-check',
    implemented: true,
    domain: 'orders',
    label: '地址与支付校验',
    description:
      '校验买家地址可投递性（国家/邮编/街道格式）、禁运国家清单、支付状态一致性，输出校验结果与风险等级。',
  },
  {
    href: '/orders/fulfillment',
    implemented: true,
    domain: 'orders',
    label: '待发货订单',
    description: '按发货时限、仓库与物流渠道安排订单履约。',
  },
  {
    // 9.3.1.1 拆单。**不占用** /orders/fulfillment 本身（那是 9.3.3.1 履约请求的路由），
    // 与 9.3.1.2 合单 / 9.3.2.1 分仓 / 9.3.2.2 履约路径并列挂在锚点之下。
    href: '/orders/fulfillment/split',
    implemented: true,
    domain: 'orders',
    label: '订单拆单',
    description:
      '把一个订单拆成多个履约单元：按仓库拆、按承运商拆、按发货时效拆、按平台限制拆。拆单不产生新的销售订单，订单总金额与买家视角不变。',
    // workflow 固定三步（task-pages.test.ts 钉死这个长度）：
    // 「能拆」→「拆成什么」→「发出去没有」就是运营脑子里真实的三段。
  },
  {
    href: '/orders/rules',
    implemented: false,
    domain: 'orders',
    label: '订单规则',
    description: '配置商品匹配、分仓与审核规则并审计变更。',
    fields: ['规则名称', '适用店铺', '触发条件', '执行动作', '优先级', '状态'],
    workflow: ['定义条件', '校验规则', '启用监控'],
  },
  {
    href: '/aftersales',
    implemented: true,
    domain: 'aftersales',
    label: '售后工单',
    description: '统一跟进退款、退货、补发和平台争议。',
  },
  {
    // 11.2.1.1 退货授权单。/aftersales/returns 是蓝图 11.2「退货管理」的 L3 锚点，
    // 而本路由上部署的是 11.2.1.1（审批退货申请），**不是** 11.2.2.1 退货入库。
    // 原登记的 label/description/fields/workflow 全在描述下游的 11.2.2.x（收件、质检、
    // 可售库存恢复），页面真存在之后只翻 implemented 布尔值，菜单就会声称一个本页
    // 根本没有的能力——比守卫报红更糟（守卫只校验 implemented，抓不到文案错配）。
    // 因此按 /aftersales/refunds 的同一惯例：L3 锚点 href 按实际部署在那里的 L4 命名。
    // 11.2.2.1 退货入库将来另立子路由（参照 /orders/fulfillment/split 与
    // /warehouse/location-allocations 的处理），不占用本条。
    href: '/aftersales/returns',
    implemented: true,
    domain: 'aftersales',
    label: '退货授权单',
    description: '审批退货申请：校验可退数量、登记退货地址与买家回寄物流，超时未寄自动关闭。',
  },
  {
    href: '/aftersales/refunds',
    implemented: true,
    domain: 'aftersales',
    label: '退款状态',
    // 原标签是「退款与补发」。**补发（REISSUE）是 11.1.1.1 的售后单类型，不是本模块**，
    // 11.3.1.2 只交付退款执行状态与对账差异；沿用旧标签会让导航声称覆盖了一个不存在的能力。
    description: '跟踪退款执行与平台同步状态，查证后重试，裁定对账差异。',
  },
  {
    href: '/crm',
    implemented: true,
    domain: 'crm',
    label: 'CRM / 客服',
    description: '客户档案、工单、平台站内信、评价管理。',
  },
  {
    href: '/crm/inbox',
    implemented: false,
    domain: 'crm',
    label: '消息中心',
    description: '按店铺与平台集中处理买家消息及回复时限。',
    fields: ['会话编号', '平台店铺', '订单号', '最新消息时间', '回复截止', '负责人'],
    workflow: ['接收消息', '分派回复', '归档'],
  },
  {
    href: '/crm/templates',
    implemented: false,
    domain: 'crm',
    label: '回复模板',
    description: '维护多语言客服模板及审核版本。',
    fields: ['模板名称', '适用场景', '语言', '版本', '审核人', '状态'],
    workflow: ['编写', '审核', '使用复盘'],
  },
  {
    href: '/analytics',
    implemented: true,
    domain: 'analytics',
    label: '销售分析',
    description: '按店铺、站点、商品及币种分析销售表现。',
  },
  {
    href: '/analytics/ads',
    implemented: false,
    domain: 'analytics',
    label: '广告分析',
    description: '比较广告花费、归因销售与投放效果。',
    fields: ['站点', '广告活动', '花费', '归因销售', 'ACOS', '统计窗口'],
    workflow: ['同步报表', '核对归因', '优化投放'],
  },
  {
    href: '/analytics/operations',
    implemented: false,
    domain: 'analytics',
    label: '运营报表',
    description: '查看订单时效、退款与缺货等运营指标。',
    fields: ['统计周期', '店铺', '指标名称', '统计口径', '结果', '更新时间'],
    workflow: ['选择指标', '核对来源', '追踪异常'],
  },
  {
    href: '/finance',
    implemented: true,
    domain: 'finance',
    label: '平台结算',
    description: '按结算单核对销售、佣金、退款与平台回款。',
  },
  {
    href: '/finance/payables',
    implemented: true,
    domain: 'finance',
    label: '应付与付款',
    description: '跟进采购、物流和服务商费用的审批与付款。',
  },
  {
    href: '/finance/reconciliation',
    implemented: false,
    domain: 'finance',
    label: '差异对账',
    description: '集中处理平台、仓库和物流账单差异。',
    fields: ['差异编号', '来源账单', '差异类型', '金额', '处理人', '状态'],
    workflow: ['识别差异', '核实凭证', '调整确认'],
  },
  {
    href: '/finance/exchange',
    implemented: false,
    domain: 'finance',
    label: '汇率与费用',
    description: '维护原币、结算币、汇率快照和费用口径。',
    fields: ['币种对', '汇率来源', '汇率日期', '费用类型', '生效范围', '版本'],
    workflow: ['获取汇率', '复核口径', '留存快照'],
  },
  {
    href: '/profit',
    implemented: true,
    domain: 'profit',
    label: '利润中心',
    description: '毛利、分润、成本归集。',
  },
  {
    href: '/profit/products',
    implemented: false,
    domain: 'profit',
    label: '商品利润',
    description: '比较 SKU 在各站点与店铺的利润表现。',
    fields: ['SKU', '站点', '期间', '销量', '分摊成本', '毛利率'],
    workflow: ['选择维度', '核对成本', '分析差异'],
  },
  {
    href: '/profit/costs',
    implemented: false,
    domain: 'profit',
    label: '成本分摊',
    description: '管理头程、仓储和其他费用的分摊批次。',
    fields: ['分摊批次', '费用类型', '币种', '分摊依据', '关联单据', '审批状态'],
    workflow: ['归集费用', '选择依据', '确认分摊'],
  },
  {
    href: '/account',
    implemented: true,
    domain: 'org',
    label: '个人资料',
    description: '维护个人资料、登录安全与界面偏好。',
  },
  {
    href: '/org',
    implemented: true,
    domain: 'org',
    label: '租户概览',
    description: '查看当前企业工作空间与组织管理入口。',
  },
  {
    href: '/org/legal-entities',
    implemented: true,
    domain: 'org',
    label: '法人档案',
    description: '维护法人主体、注册信息、本位币与账簿舍入规则。',
  },
  {
    href: '/org/accounting-books',
    implemented: true,
    domain: 'org',
    label: '会计账簿',
    description: '为法人配置会计账簿、本位币与记账规则（精度、舍入、期间设置）。',
  },
  {
    href: '/org/business-parameters',
    implemented: true,
    domain: 'org',
    label: '业务参数',
    description: '配置全局与分场景业务参数默认值，按全局、法人、店铺三档解析生效值并保留变更历史。',
  },
  {
    href: '/org/stores',
    implemented: false,
    domain: 'org',
    label: '店铺与授权',
    description: '管理多平台多店铺的授权、有效期和同步状况。',
    fields: ['平台', '店铺', '站点', '授权状态', '有效期', '同步时间'],
    workflow: ['连接店铺', '校验授权', '监控同步'],
  },
  {
    href: '/org/permissions',
    implemented: false,
    domain: 'org',
    label: '数据权限',
    description: '按团队、店铺、仓库与业务动作管理权限范围。',
    fields: ['角色', '店铺范围', '仓库范围', '动作权限', '审批人', '状态'],
    workflow: ['定义范围', '审批授权', '定期审计'],
  },
  {
    // 17.5.3.1 审计日志。只读：记录由各操作点追加，页面没有新建/编辑入口。
    // 与 17.5.3.2 操作日志用途不同，这里不含登录流水、错误堆栈与请求 ID 链路。
    href: '/org/audit',
    implemented: true,
    domain: 'org',
    label: '审计日志',
    description: '检索关键操作留痕、授权上下文与脱敏后的变更详情，并按条件导出。',
  },
  {
    // 17.5.3.2 操作日志。排障用，含技术细节（耗时、状态码、错误堆栈）与请求 ID 链路。
    // 与审计日志的分野是实的：这里可按留存期清理，审计不可篡改、长期留存。
    href: '/org/operation-log',
    implemented: true,
    domain: 'org',
    label: '操作日志',
    description: '检索登录、API 调用、任务执行与错误堆栈，按请求 ID 查看完整链路。',
  },
  {
    href: '/logistics',
    implemented: true,
    domain: 'logistics',
    label: '物流中心',
    description: '物流商对接、路由推荐、轨迹跟踪。',
  },
  {
    href: '/logistics/fba',
    implemented: false,
    domain: 'logistics',
    label: 'FBA 货件',
    description: '跟进 FBA 备货、装箱、头程、签收与差异。',
    fields: ['货件编号', '目的仓', '箱数', '发货数量', '签收数量', '差异状态'],
    workflow: ['创建计划', '装箱发运', '核对签收'],
  },
  {
    href: '/logistics/tracking',
    implemented: true,
    domain: 'logistics',
    label: '轨迹查询',
    description:
      '查询并展示包裹物流轨迹：节点时间线、预计时效对比、异常节点标记，支持按承运商批量拉取。',
  },
  {
    href: '/logistics/channels',
    implemented: true,
    domain: 'logistics',
    label: '物流渠道',
    description: '维护承运商服务、计费规则与可达范围。',
  },
  {
    href: '/logistics/claims',
    implemented: false,
    domain: 'logistics',
    label: '索赔与账单',
    description: '核对物流计费、丢损证据及索赔进度。',
    fields: ['账单或索赔号', '承运商', '运单号', '金额币种', '证据', '状态'],
    workflow: ['核对费用', '提交证据', '跟进结算'],
  },
  {
    href: '/warehouse',
    implemented: true,
    domain: 'warehouse',
    label: '仓储中心',
    description: '仓库档案、仓位、库内作业。',
  },
  {
    // 8.1.1.1 仓库列表：路由取蓝图 08 的 L3 锚点 /warehouse/facilities。
    // 导航条目由 SYSTEMS 的 children 从 IMPLEMENTED_TASK_PAGES 派生（见 nav-config），
    // 所以这里**只**登记任务页，不需要改共享的导航常量文件。
    // 与上一条 /warehouse（L3「仓库与库位」入口，库位树属 8.1.2.x）并列而不同名：
    // 两条都指向仓库域，但一个是 L3 伞页，一个是已交付的列表本体。
    href: '/warehouse/facilities',
    implemented: true,
    domain: 'warehouse',
    label: '仓库列表',
    description: '查看仓库类型、货权法人、国家与时区、库存量与作业量，并新增仓库。',
  },
  {
    // 8.1.2.2 库位自动分配：按规则为入库商品推荐库位（残次品入残次库位是硬规则）。
    // 与 8.1.2.1 库位树同属「仓库与库位」这个 L3 下的两个子功能，所以共用 domain
    // 'warehouse'，由导航的 children 从 IMPLEMENTED_TASK_PAGES 派生 —— 同样只登记
    // 任务页、不改共享的 nav-config.ts。
    href: '/warehouse/location-allocations',
    implemented: true,
    domain: 'warehouse',
    label: '库位自动分配',
    description: '为入库商品推荐库位：同品类聚类、最短上架路径、容量与承重匹配，并给出推荐理由。',
  },
  {
    href: '/warehouse/inbound',
    implemented: false,
    domain: 'warehouse',
    label: '入库作业',
    description: '处理采购、退货、调拨等来源的收货与上架。',
    fields: ['入库单号', '来源单据', '仓库', '待收数量', '上架库位', '作业状态'],
    workflow: ['收货', '复核', '上架'],
  },
  {
    href: '/warehouse/outbound',
    implemented: true,
    domain: 'warehouse',
    label: '拣货与出库',
    description: '按波次完成拣货、复核、包装和出库交接。',
  },
  {
    href: '/warehouse/transfers',
    implemented: false,
    domain: 'warehouse',
    label: '调拨管理',
    description: '追踪仓间调拨、在途及收货差异。',
    fields: ['调拨单号', '调出仓', '调入仓', '数量', '预计到达', '差异'],
    workflow: ['申请调拨', '出库运输', '收货核对'],
  },
  {
    href: '/warehouse/counts',
    implemented: false,
    domain: 'warehouse',
    label: '盘点与调整',
    description: '组织循环盘点、差异复核与库存调整。',
    fields: ['盘点单号', '仓库库位', '账面数', '实盘数', '差异', '审批状态'],
    workflow: ['制定盘点', '复核差异', '审批调整'],
  },
  {
    href: '/inventory',
    implemented: true,
    domain: 'inventory',
    label: '全仓库存',
    description: '统一查看本地、海外、FBA 与在途库存。',
  },
  {
    href: '/inventory/replenishment',
    implemented: false,
    domain: 'inventory',
    label: '补货建议',
    description: '结合销售、交期、在途与安全库存制定补货方案。',
    fields: ['SKU', '日均销量', '可售天数', '交期', '建议采购量', '建议发货量'],
    workflow: ['设置口径', '计算需求', '转采购或发货计划'],
  },
  {
    href: '/inventory/ledger',
    implemented: true,
    domain: 'inventory',
    label: '库存流水',
    description: '逐笔核对出入库、调拨和调整的库存变化。',
  },
  {
    href: '/inventory/aging',
    implemented: true,
    domain: 'inventory',
    label: '库龄与预警',
    description: '识别断货、滞销和超龄库存并安排处理。',
  },
  {
    // 7.2.1.1 预占需求列表。与 ledger / aging 同属库存卷，按 href 排序插在 ledger 之后。
    // 本条是**追加**：该文件是多车道共用的注册表，并入时按 href 求并。
    href: '/inventory/reservation',
    implemented: true,
    domain: 'inventory',
    label: '预占需求',
    description: '查看订单履约、加工领料与其他占用的预占需求及剩余量、超时时长。',
  },
];
export function findTaskPage(href: string) {
  return TASK_PAGES.find((page) => page.href === href);
}

/**
 * 账户域：组织、角色、成员、权限、租户与店铺授权。
 * 这些页面从 ce5c78e 起不再进入业务系统侧栏，改由账户卡片与账户 Tab 承载，
 * 因此不参与业务菜单（NAV_DESTINATIONS）的构成。
 */
export const ACCOUNT_DOMAINS = new Set(['org']);

/** 账户域中已实现（存在真实页面）的任务页。 */
export const IMPLEMENTED_TASK_PAGES = TASK_PAGES.filter((page) => page.implemented);

/**
 * 账户菜单展示顺序。成员关系只有一个真相源 = TASK_PAGES 的账户域，
 * 这里只规定「按什么顺序摆」，不再单独维护一份入口清单——
 * 否则就会出现「有页面没入口」或「有入口没页面」的割裂。
 */
export const ACCOUNT_MENU_ORDER = [
  '/account',
  '/org',
  '/org/members',
  '/org/roles',
  '/org/departments',
  '/org/children',
  '/org/hierarchy',
  '/org/legal-entities',
  '/org/accounting-books',
  '/org/business-parameters',
  '/org/stores',
  '/org/permissions',
  '/org/audit',
  '/org/operation-log',
];
export function isAccountDomain(domain: string): boolean {
  return ACCOUNT_DOMAINS.has(domain);
}
