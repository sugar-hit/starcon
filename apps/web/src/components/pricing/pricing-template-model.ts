import { api } from '../inventory/api';

/**
 * 5.3.2.1 定价模板前端模型。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：沉淀定价策略模板：成本加成、固定毛利、竞品跟随（若允许）、按站点差异化、按库存周转调整，新品与老品用不同模板。
 *
 * **输入**：模板定义（基准 + 调整项）；适用对象（分类/品牌/店铺）。
 *
 * **输出**：模板记录；一键应用到商品生成建议价。
 *
 * **关系**：上游 = 5.3.1.1；下游 = 5.4.1.1。**模板与价目表的关系**：模板是"怎么算"，价目表是"算完是多少"。
 *
 * </blockquote>
 *
 * <h2>🔴 建议价 ≠ 生效价</h2>
 *
 * <p>本页产出的每一个价格都是**建议价**（蓝图输出句：「一键应用到商品生成建议价」），
 * 改价是 5.4.1.1 调价单的写入所有权。所以页面上：
 *
 * <ul>
 *   <li>建议价一栏永远带「建议价」字样，不写成「价格」；
 *   <li>{@link suggestedPriceText} 在后端拒绝给价时显示「不给建议价（成本不完整）」，
 *       <b>不是 0</b>、也不是一个估出来的数；
 *   <li>能力声明固定挂在页面顶部，把「模板是"怎么算"，价目表是"算完是多少"」写在脸上。
 * </ul>
 *
 * <h2>🔴 null 绝不渲染成 0</h2>
 *
 * <p>「取不到」与「确实是 0」是两件事：没登记竞品价 ≠ 竞品价是 0；
 * 成本不完整 ≠ 毛利率是 0%。{@link moneyText} / {@link percentText} 对 {@code null}
 * 一律渲染成兜底文案。
 */

/** 基准方法。与后端 {@code BaseMethod} 逐字对应。 */
export type BaseMethod = 'COST_PLUS' | 'FIXED_MARGIN' | 'COMPETITOR_FOLLOW';

/** 商品阶段。与后端 {@code ProductStage} 逐字对应。 */
export type ProductStage = 'NEW' | 'EXISTING';

/** 调整项类型。与后端 {@code AdjustmentType} 逐字对应。 */
export type AdjustmentType = 'SITE_DIFFERENTIAL' | 'INVENTORY_TURNOVER';

/** 适用对象类型。与后端 {@code ScopeType} 逐字对应。 */
export type ScopeType = 'CATEGORY' | 'BRAND' | 'SHOP';

/** 能力声明里的一个词表项。 */
export interface CapabilityItem {
  code: string;
  label: string;
  description: string;
  requiredFields: string[];
}

/** 能力声明。页面顶部固定显示它。 */
export interface PricingTemplateCapabilities {
  baseMethods: CapabilityItem[];
  adjustmentTypes: CapabilityItem[];
  scopeTypes: CapabilityItem[];
  productStages: CapabilityItem[];
  upstreamNote: string;
  downstreamNote: string;
  priceListNote: string;
  competitorNote: string;
  turnoverNote: string;
  assumptionNote: string;
  stageNote: string;
  applyNote: string;
  sourceNote: string;
}

/** 分类选项（内部分类树）。 */
export interface CategoryOption {
  code: string;
  name: string;
  parentCode: string | null;
}

/** 品牌选项。 */
export interface BrandOption {
  code: string;
  name: string;
  authorizationStatus: string;
}

/** 适用对象目录（分类 + 品牌）。 */
export interface TemplateDirectory {
  categories: CategoryOption[];
  brands: BrandOption[];
  sourceNote: string;
}

/** 目标店铺（站点）选项。 */
export interface SiteOption {
  platformCode: string;
  platformName: string;
  siteCode: string;
  siteName: string;
  countryCode: string | null;
  defaultCurrency: string | null;
  vatRequired: boolean;
}

/** 一个调整项（输入）。 */
export interface TemplateAdjustmentInput {
  type: AdjustmentType;
  siteCode: string | null;
  deltaRate: number | null;
  daysCoverThreshold: number | null;
  discountRate: number | null;
  note: string;
}

/** 一个调整项（读取）。 */
export interface AdjustmentView {
  id: string;
  type: AdjustmentType;
  typeLabel: string;
  siteCode: string | null;
  deltaRate: number | null;
  daysCoverThreshold: number | null;
  discountRate: number | null;
  note: string;
}

/** 一条适用对象（输入）。 */
export interface TemplateScopeInput {
  type: ScopeType;
  ref: string;
  platformCode: string | null;
}

/** 一条适用对象（读取）。 */
export interface ScopeView {
  id: string;
  type: ScopeType;
  typeLabel: string;
  ref: string;
  platformCode: string | null;
}

/** 模板列表的一行。 */
export interface TemplateSummary {
  id: string;
  code: string;
  name: string;
  baseMethod: BaseMethod;
  baseMethodLabel: string;
  baseRate: number | null;
  productStage: ProductStage;
  productStageLabel: string;
  currency: string;
  competitorReferencePrice: number | null;
  enabled: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  adjustmentCount: number;
  scopeCount: number;
  createdBy: string | null;
  updatedAt: string;
}

/** 模板详情。 */
export interface TemplateDetail {
  summary: TemplateSummary;
  description: string;
  baseMethod: BaseMethod;
  baseRate: number | null;
  competitorReferencePrice: number | null;
  competitorCurrency: string | null;
  productStage: ProductStage;
  currency: string;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  costCurrency: string | null;
  fxRate: number | null;
  enabled: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
  version: number;
  adjustments: AdjustmentView[];
  scopes: ScopeView[];
  createdAt: string;
  updatedAt: string;
}

/** 模板定义（新建与修改共用）。 */
export interface TemplateInput {
  code: string;
  name: string;
  description: string;
  baseMethod: BaseMethod;
  baseRate: number | null;
  competitorReferencePrice: number | null;
  competitorCurrency: string | null;
  productStage: ProductStage;
  currency: string;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  costCurrency: string | null;
  fxRate: number | null;
  enabled: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
  adjustments: TemplateAdjustmentInput[];
  scopes: TemplateScopeInput[];
  version: number | null;
}

/** 模板列表（分页）。 */
export interface TemplatePage {
  items: TemplateSummary[];
  page: number;
  size: number;
  total: number;
}

/** 一键应用的请求。 */
export interface ApplyRequest {
  skuId: string;
  siteCode: string | null;
  platformCode: string | null;
  currency: string | null;
  quantity: number | null;
  competitorReferencePrice: number | null;
  competitorCurrency: string | null;
  daysOfCover: number | null;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  costCurrency: string | null;
  fxRate: number | null;
  note: string;
}

/** 一条被应用的调整项。 */
export interface AppliedAdjustment {
  type: AdjustmentType;
  typeLabel: string;
  siteCode: string | null;
  deltaRate: number | null;
  daysCoverThreshold: number | null;
  discountRate: number | null;
  applied: boolean;
  reason: string;
}

/** 一键应用的结果（建议价 + 依据）。 */
export interface ApplyResult {
  applicationId: string;
  templateId: string;
  templateCode: string;
  templateName: string;
  skuId: string;
  skuCode: string | null;
  skuName: string | null;
  siteCode: string | null;
  platformCode: string | null;
  currency: string;
  quantity: number;
  baseMethod: BaseMethod;
  baseMethodLabel: string;
  effectiveRate: number | null;
  appliedAdjustments: AppliedAdjustment[];
  suggestedPrice: number | null;
  decisionReady: boolean;
  grossMarginRate: number | null;
  grossProfit: number | null;
  calculation: ProfitCalculationView | null;
  note: string;
  createdAt: string;
}

/** 5.3.1.1 的测算结果（只列本模块要用到的字段，完整结构见利润测算器页面）。 */
export interface ProfitCalculationView {
  skuId: string;
  currency: string;
  decisionReady: boolean;
  decisionBasis: string;
  revenue: number | null;
  knownDeductionTotal: number | null;
  grossProfit: number | null;
  grossMarginRate: number | null;
  targetMarginPrice: number | null;
  blockingGaps: {
    code: string;
    label: string;
    ownerModule: string;
    impact: string;
    remedy: string;
    manualAssumptionAccepted: boolean;
  }[];
  waterfall: {
    code: string;
    label: string;
    available: boolean;
    amount: number | null;
    unavailableReason: string | null;
    sourceKind: string;
    ownerModule: string;
  }[];
}

/** 应用留痕的一行。 */
export interface ApplicationSummary {
  id: string;
  templateId: string;
  templateCode: string;
  skuId: string;
  skuCode: string | null;
  siteCode: string | null;
  currency: string;
  quantity: number;
  baseMethod: BaseMethod;
  effectiveRate: number | null;
  suggestedPrice: number | null;
  decisionReady: boolean;
  grossMarginRate: number | null;
  createdAt: string;
  appliedBy: string | null;
}

/** 应用留痕（分页）。 */
export interface ApplicationPage {
  items: ApplicationSummary[];
  page: number;
  size: number;
  total: number;
}

// ================================================================ 纯函数：口径渲染

/**
 * 金额渲染。
 *
 * <p>🔴 {@code null} ⇒ 兜底文案，**不是 0.00**（「取不到」与「确实是 0」是两件事）。
 */
export function moneyText(value: number | null | undefined, fallback = '未维护'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return trimZeros(value.toFixed(4));
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 取不到时**不**再补币种：「不可信 USD」会让人以为这是个金额。
 */
export function moneyWithCurrency(
  value: number | null | undefined,
  currency: string | null | undefined,
  fallback = '未维护',
): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return currency ? `${moneyText(value)} ${currency}` : moneyText(value);
}

/**
 * 百分比渲染。
 *
 * <p>🔴 {@code null} ⇒ 兜底文案，**不是 0%**（毛利率不可信 ≠ 毛利率是 0）。
 */
export function percentText(
  rate: number | null | undefined,
  fallback = '不可信（成本不完整）',
): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return fallback;
  return `${trimZeros((rate * 100).toFixed(2))}%`;
}

/** 数量渲染。 */
export function quantityText(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return trimZeros(value.toFixed(6));
}

/** 去掉小数末尾的 0（10.0000 → 10、0.0000 → 0、24.7059 → 24.7059）。 */
function trimZeros(text: string): string {
  if (!text.includes('.')) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/** 基准方法的中文名。 */
export function baseMethodLabel(method: BaseMethod | null | undefined): string {
  switch (method) {
    case 'COST_PLUS':
      return '成本加成';
    case 'FIXED_MARGIN':
      return '固定毛利';
    case 'COMPETITOR_FOLLOW':
      return '竞品跟随';
    default:
      return '—';
  }
}

/** 商品阶段的中文名。 */
export function productStageLabel(stage: ProductStage | null | undefined): string {
  switch (stage) {
    case 'NEW':
      return '新品';
    case 'EXISTING':
      return '老品';
    default:
      return '—';
  }
}

/** 调整项类型的中文名。 */
export function adjustmentTypeLabel(type: AdjustmentType | null | undefined): string {
  switch (type) {
    case 'SITE_DIFFERENTIAL':
      return '按站点差异化';
    case 'INVENTORY_TURNOVER':
      return '按库存周转调整';
    default:
      return '—';
  }
}

/** 适用对象类型的中文名。 */
export function scopeTypeLabel(type: ScopeType | null | undefined): string {
  switch (type) {
    case 'CATEGORY':
      return '分类';
    case 'BRAND':
      return '品牌';
    case 'SHOP':
      return '店铺';
    default:
      return '—';
  }
}

/**
 * 基准率的文案：**同一个小数在三种基准下含义不同**。
 *
 * <p>「0.25」在加成率下是「加 25%」，在目标毛利率下是「毛利 25%」，
 * 在跟随率下是「比竞品低 25%」—— 所以文案必须跟着基准方法走。
 */
export function baseRateText(
  method: BaseMethod | null | undefined,
  rate: number | null | undefined,
): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return '未维护';
  switch (method) {
    case 'COST_PLUS':
      return `成本加 ${percentText(rate, '未维护')}`;
    case 'FIXED_MARGIN':
      return `目标毛利 ${percentText(rate, '未维护')}`;
    case 'COMPETITOR_FOLLOW':
      return rate < 0
        ? `比竞品低 ${percentText(Math.abs(rate), '未维护')}`
        : `比竞品高 ${percentText(rate, '未维护')}`;
    default:
      return percentText(rate, '未维护');
  }
}

/**
 * 建议价的文案。
 *
 * <p>🔴 后端拒绝给价（成本不完整）时 {@code suggestedPrice} 是 {@code null} ——
 * 这里必须显示「不给建议价（成本不完整）」，**不是 0**。
 */
export function suggestedPriceText(result: ApplyResult | null): string {
  if (!result) return '待测算';
  if (result.suggestedPrice === null || result.suggestedPrice === undefined) {
    return '不给建议价（成本不完整）';
  }
  return moneyWithCurrency(result.suggestedPrice, result.currency, '待测算');
}

/** 决策就绪度的色调。 */
export function decisionTone(ready: boolean | null | undefined): 'success' | 'warning' | 'danger' {
  if (ready === true) return 'success';
  if (ready === false) return 'danger';
  return 'warning';
}

/** 调整项是否命中的色调。 */
export function adjustmentTone(applied: boolean): 'success' | 'neutral' {
  return applied ? 'success' : 'neutral';
}

/** 真正缺失的缺口（不是「已用手工假设补上」的那些）。 */
export function missingGaps(result: ApplyResult | null): {
  label: string;
  ownerModule: string;
  impact: string;
}[] {
  if (!result?.calculation) return [];
  return result.calculation.blockingGaps
    .filter((gap) => !gap.manualAssumptionAccepted)
    .map((gap) => ({ label: gap.label, ownerModule: gap.ownerModule, impact: gap.impact }));
}

/** 已用手工假设补上的口径提醒。 */
export function assumptionGaps(result: ApplyResult | null): {
  label: string;
  ownerModule: string;
  impact: string;
}[] {
  if (!result?.calculation) return [];
  return result.calculation.blockingGaps
    .filter((gap) => gap.manualAssumptionAccepted)
    .map((gap) => ({ label: gap.label, ownerModule: gap.ownerModule, impact: gap.impact }));
}

/** 建议价的统计卡。 */
export interface SummaryStat {
  label: string;
  value: string;
  hint: string;
  tone: 'success' | 'warning' | 'danger' | 'info';
}

/** 顶部统计卡。🔴 建议价与毛利率不可信时显示兜底文案，而不是 0。 */
export function summaryStats(result: ApplyResult | null): SummaryStat[] {
  if (!result) return [];
  const ready = result.decisionReady;
  return [
    {
      label: '建议价（不是生效价）',
      value: suggestedPriceText(result),
      hint: '改价走 5.4.1.1 调价单；本页只出建议价',
      tone: result.suggestedPrice === null ? 'danger' : 'success',
    },
    {
      label: '建议价对应的毛利率',
      value: percentText(result.grossMarginRate),
      hint: ready ? '由 5.3.1.1 的测算结果复核' : '🔴 成本不完整，不给数字',
      tone: ready ? 'success' : 'danger',
    },
    {
      label: '建议价对应的毛利额',
      value: moneyWithCurrency(result.grossProfit, result.currency, '不可信'),
      hint: ready ? '每单位毛利' : '🔴 缺项算出来的毛利是虚高的',
      tone: ready ? 'success' : 'danger',
    },
    {
      label: '生效基准率',
      value: baseRateText(result.baseMethod, result.effectiveRate),
      hint: '含按站点差异化的增量（加在基准率上，不是乘在价格上）',
      tone: 'info',
    },
    {
      label: '目标商品',
      value: result.skuCode ?? result.skuName ?? '—',
      hint: result.siteCode ? `目标店铺 ${result.siteCode}` : '未限定店铺',
      tone: 'info',
    },
  ];
}

// ================================================================ 纯函数：输入校验

/** 校验失败。 */
export interface ValidationError {
  field: string;
  message: string;
}

/**
 * 模板表单的本地校验（后端还会再校验一遍，这里是为了少一次往返）。
 *
 * <p>🔴 竞品跟随时**必须**有竞品基准价：本仓没有竞品价模块，系统不会凭空生成一个。
 */
export function validateTemplate(input: TemplateInput): ValidationError | null {
  if (!input.code.trim()) return { field: 'code', message: '模板编码必填' };
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(input.code.trim())) {
    return { field: 'code', message: '模板编码只能是大写字母、数字、短横线与下划线' };
  }
  if (!input.name.trim()) return { field: 'name', message: '模板名称必填' };
  if (!/^[A-Za-z]{3}$/.test(input.currency)) {
    return { field: 'currency', message: '币种必须是三位大写字母' };
  }
  if (input.baseRate === null) {
    return {
      field: 'baseRate',
      message:
        input.baseMethod === 'COMPETITOR_FOLLOW'
          ? '竞品跟随必须给跟随率'
          : '必须给基准率（加成率 / 目标毛利率）',
    };
  }
  if (input.baseMethod === 'FIXED_MARGIN' && (input.baseRate < 0 || input.baseRate >= 1)) {
    return {
      field: 'baseRate',
      message: '目标毛利率是小数口径（0.25 表示 25%），必须在 0 与 1 之间',
    };
  }
  if (input.baseMethod === 'COST_PLUS' && (input.baseRate < 0 || input.baseRate >= 10)) {
    return { field: 'baseRate', message: '加成率必须落在 0 与 10 之间' };
  }
  if (input.baseMethod === 'COMPETITOR_FOLLOW') {
    if (input.baseRate <= -1 || input.baseRate >= 1) {
      return { field: 'baseRate', message: '跟随率必须落在 −100% 与 100% 之间' };
    }
    if (input.competitorReferencePrice === null || input.competitorReferencePrice <= 0) {
      return {
        field: 'competitorReferencePrice',
        message: '竞品跟随必须登记竞品基准价（本仓没有竞品价模块，只能人工登记）',
      };
    }
    if (!input.competitorCurrency || !/^[A-Za-z]{3}$/.test(input.competitorCurrency)) {
      return { field: 'competitorCurrency', message: '竞品价必须给币种' };
    }
  }
  if (
    input.costCurrency &&
    input.costCurrency.toUpperCase() !== input.currency.toUpperCase() &&
    (input.fxRate === null || input.fxRate <= 0)
  ) {
    return {
      field: 'fxRate',
      message: '售价币种与成本币种不同，必须给汇率（1 成本币种 = X 售价币种）—— 绝不按 1:1 折算',
    };
  }
  for (const adjustment of input.adjustments) {
    if (adjustment.type === 'SITE_DIFFERENTIAL') {
      if (!adjustment.siteCode?.trim()) {
        return { field: 'adjustments', message: '按站点差异化的调整项必须给站点码' };
      }
      if (adjustment.deltaRate === null) {
        return { field: 'adjustments', message: '按站点差异化的调整项必须给增量' };
      }
    } else {
      if (adjustment.daysCoverThreshold === null || adjustment.daysCoverThreshold < 0) {
        return { field: 'adjustments', message: '按库存周转调整的调整项必须给可售天数阈值' };
      }
      if (adjustment.discountRate === null || adjustment.discountRate < 0 || adjustment.discountRate > 1) {
        return { field: 'adjustments', message: '按库存周转调整的调整项必须给折扣率（0 与 1 之间）' };
      }
    }
  }
  for (const scope of input.scopes) {
    if (!scope.ref.trim()) {
      return { field: 'scopes', message: '适用对象必须给取值（分类码 / 品牌码 / 站点码）' };
    }
  }
  return null;
}

/**
 * 应用表单的本地校验。
 *
 * <p>🔴 模板配了库存周转调整而没有可售天数 ⇒ 拒绝提交（后端也会拒）：
 * 静默按「不打折」处理等于让模板的一条策略失效。
 */
export function validateApply(
  request: ApplyRequest,
  hasTurnoverRule: boolean,
): ValidationError | null {
  if (!request.skuId.trim()) return { field: 'skuId', message: '必须指定 SKU' };
  if (request.quantity !== null && request.quantity <= 0) {
    return { field: 'quantity', message: '数量必须大于 0' };
  }
  if (request.currency && !/^[A-Za-z]{3}$/.test(request.currency)) {
    return { field: 'currency', message: '币种必须是三位大写字母' };
  }
  if (
    request.costCurrency &&
    request.currency &&
    request.costCurrency.toUpperCase() !== request.currency.toUpperCase() &&
    (request.fxRate === null || request.fxRate <= 0)
  ) {
    return {
      field: 'fxRate',
      message: '售价币种与成本币种不同，必须给汇率（1 成本币种 = X 售价币种）—— 绝不按 1:1 折算',
    };
  }
  if (hasTurnoverRule && (request.daysOfCover === null || request.daysOfCover < 0)) {
    return {
      field: 'daysOfCover',
      message: '这条模板配了按库存周转调整的规则，必须填当前可售天数（上游 7 库存 / 09 订单未提供可读端点）',
    };
  }
  return null;
}

/** 空模板表单。 */
export function emptyTemplate(): TemplateInput {
  return {
    code: '',
    name: '',
    description: '',
    baseMethod: 'FIXED_MARGIN',
    baseRate: 0.25,
    competitorReferencePrice: null,
    competitorCurrency: null,
    productStage: 'NEW',
    currency: 'USD',
    platformFeeRate: null,
    platformFeeFixed: null,
    logisticsCostPerUnit: null,
    costCurrency: null,
    fxRate: null,
    enabled: true,
    effectiveFrom: new Date().toISOString(),
    effectiveTo: null,
    note: '',
    adjustments: [],
    scopes: [],
    version: null,
  };
}

/** 空应用表单。 */
export function emptyApply(): ApplyRequest {
  return {
    skuId: '',
    siteCode: null,
    platformCode: null,
    currency: null,
    quantity: 1,
    competitorReferencePrice: null,
    competitorCurrency: null,
    daysOfCover: null,
    platformFeeRate: null,
    platformFeeFixed: null,
    logisticsCostPerUnit: null,
    costCurrency: null,
    fxRate: null,
    note: '',
  };
}

/** 模板详情 → 表单（编辑时用）。 */
export function toTemplateInput(detail: TemplateDetail): TemplateInput {
  return {
    code: detail.summary.code,
    name: detail.summary.name,
    description: detail.description,
    baseMethod: detail.baseMethod,
    baseRate: detail.baseRate,
    competitorReferencePrice: detail.competitorReferencePrice,
    competitorCurrency: detail.competitorCurrency,
    productStage: detail.productStage,
    currency: detail.currency,
    platformFeeRate: detail.platformFeeRate,
    platformFeeFixed: detail.platformFeeFixed,
    logisticsCostPerUnit: detail.logisticsCostPerUnit,
    costCurrency: detail.costCurrency,
    fxRate: detail.fxRate,
    enabled: detail.enabled,
    effectiveFrom: detail.effectiveFrom,
    effectiveTo: detail.effectiveTo,
    note: detail.note,
    adjustments: detail.adjustments.map((adjustment) => ({
      type: adjustment.type,
      siteCode: adjustment.siteCode,
      deltaRate: adjustment.deltaRate,
      daysCoverThreshold: adjustment.daysCoverThreshold,
      discountRate: adjustment.discountRate,
      note: adjustment.note,
    })),
    scopes: detail.scopes.map((scope) => ({
      type: scope.type,
      ref: scope.ref,
      platformCode: scope.platformCode,
    })),
    version: detail.version,
  };
}

/** 模板上是否配了按库存周转调整的规则。 */
export function hasTurnoverRule(detail: TemplateDetail | null): boolean {
  if (!detail) return false;
  return detail.adjustments.some((adjustment) => adjustment.type === 'INVENTORY_TURNOVER');
}

// ================================================================ 端点

export function fetchPricingTemplateCapabilities(): Promise<PricingTemplateCapabilities> {
  return api<PricingTemplateCapabilities>('catalog/pricing-templates/capabilities');
}

export function fetchPricingTemplateDirectory(): Promise<TemplateDirectory> {
  return api<TemplateDirectory>('catalog/pricing-templates/directory');
}

export function fetchPricingTemplateSites(): Promise<SiteOption[]> {
  return api<SiteOption[]>('catalog/pricing-templates/sites');
}

export function fetchPricingTemplates(
  query: {
    keyword?: string | undefined;
    productStage?: ProductStage | null | undefined;
    baseMethod?: BaseMethod | null | undefined;
    scopeType?: ScopeType | null | undefined;
    scopeRef?: string | null | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<TemplatePage> {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.productStage) params.set('productStage', query.productStage);
  if (query.baseMethod) params.set('baseMethod', query.baseMethod);
  if (query.scopeType) params.set('scopeType', query.scopeType);
  if (query.scopeRef) params.set('scopeRef', query.scopeRef);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return api<TemplatePage>(`catalog/pricing-templates/templates?${params.toString()}`);
}

export function fetchPricingTemplate(id: string): Promise<TemplateDetail> {
  return api<TemplateDetail>(`catalog/pricing-templates/templates/${id}`);
}

export function createPricingTemplate(input: TemplateInput): Promise<TemplateDetail> {
  return api<TemplateDetail>('catalog/pricing-templates/templates', 'POST', input);
}

export function updatePricingTemplate(id: string, input: TemplateInput): Promise<TemplateDetail> {
  return api<TemplateDetail>(`catalog/pricing-templates/templates/${id}`, 'PUT', input);
}

export function deletePricingTemplate(id: string): Promise<void> {
  return api<void>(`catalog/pricing-templates/templates/${id}`, 'DELETE');
}

/** 一键应用：产出**建议价**（不是改价）。 */
export function applyPricingTemplate(id: string, request: ApplyRequest): Promise<ApplyResult> {
  return api<ApplyResult>(`catalog/pricing-templates/templates/${id}/apply`, 'POST', request);
}

export function fetchPricingTemplateApplications(
  query: {
    templateId?: string | null | undefined;
    skuId?: string | null | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<ApplicationPage> {
  const params = new URLSearchParams();
  if (query.templateId) params.set('templateId', query.templateId);
  if (query.skuId) params.set('skuId', query.skuId);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return api<ApplicationPage>(`catalog/pricing-templates/applications?${params.toString()}`);
}
