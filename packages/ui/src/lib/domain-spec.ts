/**
 * starcon Domain 功能清单 — 单一真相源
 *
 * 每个 domain 一份 spec:
 *   - id / label / purpose: 与 nav-config 对齐
 *   - upstream / downstream: 数据流方向,告诉用户这个 domain 从哪里拿数据、写到哪里
 *   - features: 应具备的功能清单(描述性,不展示数据)
 *   - status: 当前页面落地状态(用户一眼看出进度)
 *
 * 注意:
 *   - 这是"设计文档",不是已实现的功能
 *   - 实施某个 feature 时把 status 改成 in-progress;实施完成后改成 shipped
 *   - 永远不放 mock 数据
 */

export type FeatureStatus = 'planned' | 'in-progress' | 'shipped';

export interface DomainFeature {
  /** 功能名(短) */
  name: string;
  /** 功能描述(说明这个功能解决什么问题) */
  description: string;
  /** 当前实施状态 */
  status: FeatureStatus;
}

export interface DomainSpec {
  /** 路由路径,与 nav-config.href 对齐 */
  id: string;
  /** 中文名,与 nav-config.label 对齐 */
  label: string;
  /** 一句话目标 — 这个 domain 解决什么问题 */
  purpose: string;
  /** 数据来源 — 这个 domain 依赖哪些上游模块 */
  upstream: string[];
  /** 数据去向 — 这个 domain 给哪些下游模块输出 */
  downstream: string[];
  /** 功能清单 */
  features: DomainFeature[];
  /** 页面落地状态 */
  pageStatus: 'scaffold' | 'partial' | 'complete';
}

const PLANNED: FeatureStatus = 'planned';
const SHIPPED: FeatureStatus = 'shipped';

/* ============================================================
 * 开发 系统
 * ============================================================ */

const products: DomainSpec = {
  id: 'products',
  label: '商品中心',
  purpose: '管理 SKU 与变型,为下游刊登/库存/采购提供主数据',
  upstream: [],
  downstream: ['刊登中心', '定价中心', '采购中心', '订单中心', '库存中心', '仓储中心'],
  pageStatus: 'partial',
  features: [
    {
      name: 'SPU / SKU 与自定义属性',
      description: '真实存储 / 规格组合 / 公共属性继承与覆写 / 单位与条码',
      status: SHIPPED,
    },
    {
      name: '商品分类与标签',
      description: '多级类目树 + 业务标签(季节/活动/合规)',
      status: PLANNED,
    },
    {
      name: '商品图片与多媒体',
      description: '主图 / 详情图 / 视频 / 3D 模型集中管理',
      status: PLANNED,
    },
    { name: '多语言商品资料', description: '中/英/日/德/西等多语言资料与本地化', status: PLANNED },
    { name: '包装与尺寸', description: '长宽重 / 材积 / HS Code / 危险品标识', status: PLANNED },
    { name: '成本结构', description: '采购 / 头程 / 关税 / 包材等全链路成本分摊', status: PLANNED },
    {
      name: '商品生命周期',
      description: '草稿 / 启用 / 停用 / 归档，启用时校验必填属性',
      status: SHIPPED,
    },
    {
      name: '合规与品牌资质',
      description: '已支持资质记录、链接、国家与有效期；自动审核和通知待接入',
      status: 'in-progress',
    },
  ],
};

const listings: DomainSpec = {
  id: 'listings',
  label: '刊登中心',
  purpose: '跨平台商品刊登、内容编辑、Listing 健康监控',
  upstream: ['商品中心', '定价中心'],
  downstream: ['订单中心', '库存中心', '数据中心'],
  pageStatus: 'scaffold',
  features: [
    {
      name: '多平台 Listing 同步',
      description: 'Amazon / Shopify / eBay / TikTok Shop / Lazada 等',
      status: PLANNED,
    },
    {
      name: 'Listing 内容编辑器',
      description: '标题 / 五点描述 / A+ / 视频 / 主图',
      status: PLANNED,
    },
    { name: '多语言翻译与本地化', description: '机器翻译 + 人工审校工作流', status: PLANNED },
    { name: '关键词与 SEO 优化', description: '平台搜索词分析与关键词埋词建议', status: PLANNED },
    { name: '批量刊登与定时上架', description: '批量模板 / 定时发布 / 大促日历', status: PLANNED },
    {
      name: 'Listing 健康监控',
      description: '被跟卖 / 合规风险 / 类目审核 / 评分告警',
      status: PLANNED,
    },
    { name: '评论与评分聚合', description: '多平台评论聚合、负面评论告警', status: PLANNED },
    { name: '变型与捆绑销售', description: 'Variant / Bundle / 多件装刊登', status: PLANNED },
  ],
};

const pricing: DomainSpec = {
  id: 'pricing',
  label: '定价中心',
  purpose: '多币种价格、汇率、定价策略与平台费率模型',
  upstream: ['商品中心', '财务中心'],
  downstream: ['刊登中心', '订单中心', '利润中心'],
  pageStatus: 'scaffold',
  features: [
    {
      name: '多币种基础价',
      description: 'CNY / USD / EUR / GBP / JPY 等基础价格体系',
      status: PLANNED,
    },
    { name: '汇率源与刷新策略', description: '实时 / T+1 / 锁定等多种汇率策略', status: PLANNED },
    { name: '定价策略', description: '固定 / 动态 / 竞品跟随 / 阶梯 / 促销价', status: PLANNED },
    { name: '价格调整审批流', description: '跨权限的价格变更审批与审计', status: PLANNED },
    { name: '价格历史与回溯', description: '价格变更记录、回溯价格用于退款计算', status: PLANNED },
    { name: '平台费率模型', description: '佣金 / FBA 费 / MFN 费 / 广告费率配置', status: PLANNED },
    {
      name: '促销与折扣引擎',
      description: '满减 / 折扣码 / 限时优惠 / 平台 Promotion',
      status: PLANNED,
    },
  ],
};

const purchase: DomainSpec = {
  id: 'purchase',
  label: '采购中心',
  purpose: '采购需求、采购单、1688 档口直连',
  upstream: ['商品中心', '供应中心', '库存中心'],
  downstream: ['仓储中心', '财务中心'],
  pageStatus: 'scaffold',
  features: [
    {
      name: '采购需求生成',
      description: '基于销量预测 + 安全库存 + FBA 备货周期',
      status: PLANNED,
    },
    { name: '采购单管理', description: '创建 / 审批 / 跟踪 / 入库 全流程', status: PLANNED },
    { name: '1688 / 档口直连', description: '一键下单 / 代采工作流 / 议价记录', status: PLANNED },
    { name: '到货质检', description: '抽检 / 全检 / 不良品退货流程', status: PLANNED },
    { name: '退货与索赔', description: '供应商退货 / 质量索赔 / 补货管理', status: PLANNED },
    { name: '采购报表与对账', description: '采购成本 / 交期达成率 / 供应商对账', status: PLANNED },
    { name: '定金与尾款', description: '分批付款 / 信用期管理 / 票据管理', status: PLANNED },
  ],
};

const supply: DomainSpec = {
  id: 'supply',
  label: '供应中心',
  purpose: '供应商档案、评分、分级管理',
  upstream: [],
  downstream: ['采购中心', '财务中心'],
  pageStatus: 'scaffold',
  features: [
    { name: '供应商档案', description: '联系人 / 营业执照 / 银行账户 / 地址', status: PLANNED },
    { name: '供应商评分', description: '交期 / 品质 / 价格 / 响应 多维度评分', status: PLANNED },
    { name: '供应商分级', description: '战略 / 优选 / 备选 / 淘汰 分级管理', status: PLANNED },
    { name: '1688 / 档口对接', description: '官方 API / 一键收藏 / 选品工作台', status: PLANNED },
    { name: '准入审核与黑名单', description: '资质审核流程 / 黑名单共享', status: PLANNED },
    { name: '供应商对账', description: '账期 / 应付 / 票据 / 结算', status: PLANNED },
    { name: 'MOQ 与起订量', description: '起订量 / 价格阶梯 / 包装规格', status: PLANNED },
  ],
};

/* ============================================================
 * 运营 系统
 * ============================================================ */

const orders: DomainSpec = {
  id: 'orders',
  label: '订单中心',
  purpose: '多平台订单聚合、审单、履约调度',
  upstream: ['商品中心', '库存中心', '定价中心', 'CRM / 客服'],
  downstream: ['物流中心', '仓储中心', '售后中心', '财务中心', '利润中心'],
  pageStatus: 'scaffold',
  features: [
    {
      name: '多平台订单聚合',
      description: 'Amazon / Shopify / eBay / TikTok Shop / Lazada 等',
      status: PLANNED,
    },
    { name: '智能审单', description: '黑名单 / 高风险 / 地址校验 / 关税校验', status: PLANNED },
    { name: '订单拆分与合并', description: '多仓拆分 / 跨店合并 / 拆单策略', status: PLANNED },
    {
      name: '订单状态机',
      description: '待付款 / 待发货 / 已发货 / 已完成 / 退款',
      status: PLANNED,
    },
    { name: '拣货与配货', description: '拣货单生成 / 配货指令 / 批量打单', status: PLANNED },
    { name: '缺货订单处理', description: '缺货告警 / 自动退款 / 替代品推荐', status: PLANNED },
    { name: '异常订单告警', description: '地址异常 / 重复订单 / 风控订单', status: PLANNED },
    { name: '订单备注与标签', description: '运营标签 / 备注 / 内部沟通', status: PLANNED },
  ],
};

const aftersales: DomainSpec = {
  id: 'aftersales',
  label: '售后中心',
  purpose: '退款、退货、客诉、平台索赔',
  upstream: ['订单中心', 'CRM / 客服'],
  downstream: ['财务中心', '物流中心', '库存中心'],
  pageStatus: 'scaffold',
  features: [
    { name: '退款管理', description: '全额 / 部分 / 拒退 / 平台介入', status: PLANNED },
    { name: '退货管理(RMA)', description: '申请 / 审核 / 标签 / 跟踪 / 入库', status: PLANNED },
    { name: '客诉与争议', description: 'Amazon A-to-z / eBay SNAD / Shopee 争议', status: PLANNED },
    { name: '平台索赔', description: '丢失 / 破损 / 延误 / 仓租 / 移除费', status: PLANNED },
    { name: '售后原因分析', description: '退货原因 / 退款率 / 客诉分类统计', status: PLANNED },
    { name: '售后政策配置', description: '免邮额度 / 退货窗口 / 客户分级政策', status: PLANNED },
    { name: '补发与换货', description: '补发流程 / 换货路径 / 成本归集', status: PLANNED },
  ],
};

const crm: DomainSpec = {
  id: 'crm',
  label: 'CRM / 客服',
  purpose: '客户档案、工单、平台站内信、评价管理',
  upstream: ['订单中心'],
  downstream: ['售后中心', '数据中心'],
  pageStatus: 'scaffold',
  features: [
    { name: '客户档案', description: '基本信息 / 购买历史 / 价值分层', status: PLANNED },
    { name: '客户分群与标签', description: 'RFM 模型 / 自定义标签 / 圈选', status: PLANNED },
    { name: '平台站内信', description: 'Amazon BSM / eBay Message / Shopee Chat', status: PLANNED },
    { name: '工单系统', description: '分配 / SLA / 升级 / 内部协作', status: PLANNED },
    { name: '客户评价管理', description: '好评激励 / 差评预警 / 评价模板', status: PLANNED },
    { name: '复购提醒与营销', description: '弃购挽回 / 复购提醒 / 触达规则', status: PLANNED },
    { name: '客户黑名单与风控', description: '恶意买家 / 弃件 / 退款套利拦截', status: PLANNED },
  ],
};

const analytics: DomainSpec = {
  id: 'analytics',
  label: '数据中心',
  purpose: '数据看板、报表、订阅',
  upstream: ['订单中心', '财务中心', '利润中心', '库存中心', '广告 ROI'],
  downstream: [],
  pageStatus: 'scaffold',
  features: [
    { name: '销售看板', description: '今日 / 7 天 / 30 天 GMV / 订单数 / 客单价', status: PLANNED },
    { name: '平台维度报表', description: 'Amazon vs Shopify vs ... 多平台对比', status: PLANNED },
    { name: '商品维度报表', description: '销量 / 毛利 / 周转 / 退货率 / Top SKU', status: PLANNED },
    { name: '广告报表', description: 'ACoS / ROAS / CTR / 广告 SKU 维度', status: PLANNED },
    { name: '库存周转报表', description: '周转天数 / 滞销 / 长库龄 / FBA 库容', status: PLANNED },
    { name: '自定义看板', description: '指标拖拽 / 自定义筛选 / 保存视图', status: PLANNED },
    { name: '数据导出', description: 'Excel / CSV / API / 邮件订阅', status: PLANNED },
    { name: '日报 / 周报 / 月报', description: '定时报表订阅与推送', status: PLANNED },
  ],
};

const finance: DomainSpec = {
  id: 'finance',
  label: '财务中心',
  purpose: '对账、结算、多币种核算、税务合规',
  upstream: ['订单中心', '物流中心', '售后中心'],
  downstream: ['利润中心', '数据中心'],
  // 12.1.1.1 应收单管理已交付（真实工作区：应收确认政策 + 按币种汇总 + 账龄分布 +
  // 应收单列表/详情 + 催收任务 + 人工录入）。
  // ⚠️ 是 **partial** 不是 complete：卷 12 的 32 个模块里目前只交付了 12.1.1.1，
  //   结算 / 应付 / 收付款 / 总账 / 税务 / 资金都还没落地。
  pageStatus: 'partial',
  features: [
    {
      name: '应收单管理',
      description: '来源 / 应收对象 / 金额与币种 / 到期日 / 账龄 / 明细核销状态（12.1.1.1 已交付）',
      status: SHIPPED,
    },
    {
      name: '平台结算单抓取',
      description: 'Amazon Payout / Shopify Payments / eBay Payout',
      status: PLANNED,
    },
    {
      name: '支付网关对账',
      description: 'PayPal / Stripe / PingPong / 连连 / 空中云汇',
      status: PLANNED,
    },
    { name: '应收应付管理', description: 'AR / AP / 账期 / 票据管理', status: PLANNED },
    { name: '多币种核算', description: '汇率锁定 / 浮动 / 重估', status: PLANNED },
    { name: '头程成本分摊', description: '海运 / 空运 / 快件 按 SKU 分摊', status: PLANNED },
    {
      name: 'VAT / IOSS / EPR 申报',
      description: '欧盟 IOSS / EPR / 日本 JCT 申报数据',
      status: PLANNED,
    },
    { name: '发票管理', description: '进项发票 / 销项发票 / 电子发票', status: PLANNED },
    { name: '银行流水与对账', description: '银行 API / 流水导入 / 自动对账', status: PLANNED },
  ],
};

const profit: DomainSpec = {
  id: 'profit',
  label: '利润中心',
  purpose: '毛利、分润、成本归集',
  upstream: ['财务中心', '商品中心', '物流中心'],
  downstream: ['数据中心'],
  pageStatus: 'scaffold',
  features: [
    { name: 'SKU 维度毛利', description: '收入 - 成本 - 费用 = 毛利', status: PLANNED },
    { name: '订单维度净利', description: '订单级净利计算与回溯', status: PLANNED },
    {
      name: '广告费归集',
      description: 'Amazon SP / TikTok Ads / 独立站 Marketing',
      status: PLANNED,
    },
    { name: '退款与售后成本归集', description: '退款 / 退货 / 索赔成本分摊', status: PLANNED },
    { name: '分润与提成', description: '团队 / 供应商 / 合伙人分润规则', status: PLANNED },
    { name: '利润报表', description: '日 / 周 / 月 / 自定义周期', status: PLANNED },
    { name: '异常毛利告警', description: '负毛利 SKU / 异常订单告警', status: PLANNED },
  ],
};

const org: DomainSpec = {
  id: 'org',
  label: '组织 / 权限',
  purpose: '组织架构、员工、权限、平台账号授权',
  upstream: [],
  downstream: ['所有业务域'],
  pageStatus: 'scaffold',
  features: [
    { name: '组织架构', description: '团队 / 部门 / 汇报线 / 跨团队协作', status: PLANNED },
    { name: '员工档案', description: '入职 / 离职 / 转岗 / 绩效', status: PLANNED },
    { name: '角色与权限(RBAC)', description: '角色定义 / 权限分配 / 数据范围', status: PLANNED },
    { name: '平台账号授权', description: '独立店铺 / IP / 浏览器指纹隔离', status: PLANNED },
    { name: '操作审计日志', description: '关键操作全留痕,可追溯', status: PLANNED },
    { name: '双因素认证(2FA)', description: 'TOTP / 短信 / 硬件 key', status: PLANNED },
    { name: '计费与套餐', description: 'Free / Pro / Enterprise / 用量计费', status: PLANNED },
  ],
};

/* ============================================================
 * 物流 系统
 * ============================================================ */

const logistics: DomainSpec = {
  id: 'logistics',
  label: '物流中心',
  purpose: '物流商对接、路由推荐、轨迹跟踪',
  upstream: ['订单中心', '仓储中心'],
  downstream: ['售后中心', '财务中心'],
  pageStatus: 'scaffold',
  features: [
    {
      name: '物流商对接',
      description: 'DHL / FedEx / 顺丰 / 燕文 / 4PX / 云途 / UPS',
      status: PLANNED,
    },
    {
      name: '智能路由推荐',
      description: '基于目的地 / 重量 / 时效 / 成本四维算法',
      status: PLANNED,
    },
    { name: '面单批量打印', description: 'FNSKU / GS1 / 国际面单模板', status: PLANNED },
    { name: '物流轨迹跟踪', description: '实时轨迹 + 异常件告警', status: PLANNED },
    { name: '异常件处理', description: '丢件 / 延误 / 签收异常 工作流', status: PLANNED },
    { name: '运费对账与结算', description: '物流商账单核对 / 索赔', status: PLANNED },
    { name: '物流商绩效评估', description: '时效 / 妥投率 / 客诉率', status: PLANNED },
  ],
};

/* ============================================================
 * 仓储 系统
 * ============================================================ */

const warehouse: DomainSpec = {
  id: 'warehouse',
  label: '仓储中心',
  purpose: '仓库档案、仓位、库内作业',
  upstream: ['商品中心', '采购中心'],
  downstream: ['库存中心', '物流中心'],
  pageStatus: 'scaffold',
  features: [
    { name: '仓库档案', description: '本地仓 / 海外仓 / FBA / 第三方仓', status: PLANNED },
    { name: '仓位与货架', description: '库区 / 货架 / 库位 多级管理', status: PLANNED },
    { name: '入库作业', description: '采购入库 / 退货入库 / 调拨入库', status: PLANNED },
    { name: '出库作业', description: '订单出库 / 调拨出库 / 退货出库', status: PLANNED },
    { name: '库内作业', description: '盘点 / 移库 / 合并 / 拆分', status: PLANNED },
    { name: 'WMS 与硬件', description: 'PDA / 扫码枪 / 称重设备集成', status: PLANNED },
    { name: '多仓协同与越库', description: '跨仓调拨 / 越库发货 (Cross-Docking)', status: PLANNED },
  ],
};

const inventory: DomainSpec = {
  id: 'inventory',
  label: '库存中心',
  purpose: '中央库存池、跨平台库存协同',
  upstream: ['仓储中心', '采购中心', '订单中心'],
  downstream: ['订单中心', '刊登中心', '采购中心', '数据中心'],
  pageStatus: 'scaffold',
  features: [
    { name: '中央库存池', description: '跨仓 / 跨平台实时库存汇总', status: PLANNED },
    { name: 'SKU 实时库存', description: '可用 / 在途 / 锁定 / 预占 拆分', status: PLANNED },
    { name: '安全库存预警', description: '基于销量与备货周期阈值', status: PLANNED },
    { name: '预留库存(防超卖)', description: '下单即锁库 / 释放策略', status: PLANNED },
    { name: '多仓调拨', description: '调拨单 / 在途库存 / 库存平衡', status: PLANNED },
    { name: '库存周转分析', description: '周转天数 / 滞销 / 长库龄', status: PLANNED },
    { name: 'FBA 库存与库容', description: 'IPI / 库容利用率 / 仓储费预估', status: PLANNED },
    { name: '滞销与长库龄告警', description: '90 / 180 / 365 天滞销分级', status: PLANNED },
  ],
};

/* ============================================================
 * 顶层
 * ============================================================ */

const overview: DomainSpec = {
  id: 'overview',
  label: '工作台',
  purpose: '今日关键指标 + 待办,业务一日开屏',
  upstream: ['所有业务域'],
  downstream: [],
  pageStatus: 'scaffold',
  features: [
    { name: '今日 KPI', description: 'GMV / 订单 / 退款 / 待办 一屏总览', status: PLANNED },
    { name: '趋势图', description: '营收 / 订单 / 库存周转趋势', status: PLANNED },
    { name: '待办与异常', description: '缺货 / 退款待审 / 异常订单', status: PLANNED },
    { name: '快捷入口', description: '常用功能 / 最近访问', status: PLANNED },
    { name: '团队动态', description: '团队成员今日操作', status: PLANNED },
  ],
};

const copilot: DomainSpec = {
  id: 'copilot',
  label: 'AI Copilot',
  purpose: 'AI Agent / 自动化工作流 / 智能问答',
  upstream: ['所有业务域'],
  downstream: [],
  pageStatus: 'scaffold',
  features: [
    {
      name: '对话式查询',
      description: '自然语言查询业务数据(SKU 销量 / 平台毛利)',
      status: PLANNED,
    },
    { name: '自动化工作流', description: '条件触发的 Agent / 定时任务', status: PLANNED },
    { name: 'AI 选品 / 调价建议', description: '基于历史数据的选品 / 调价建议', status: PLANNED },
    { name: '智能客服辅助', description: '站内信草稿 / 退款话术', status: PLANNED },
    { name: '操作日志与回放', description: 'Agent 操作可审计、可回滚', status: PLANNED },
  ],
};

/* ============================================================
 * 索引
 * ============================================================ */

export const DOMAIN_SPECS: Record<string, DomainSpec> = {
  overview,
  products,
  listings,
  pricing,
  purchase,
  supply,
  orders,
  aftersales,
  crm,
  analytics,
  finance,
  profit,
  org,
  logistics,
  warehouse,
  inventory,
  copilot,
};

/** 由 id 查 spec */
export function getDomainSpec(id: string): DomainSpec | null {
  return DOMAIN_SPECS[id] ?? null;
}
