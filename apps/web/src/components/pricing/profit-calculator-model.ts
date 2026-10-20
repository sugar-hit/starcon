import { api } from '../inventory/api';

/**
 * 5.3.1.1 利润测算器前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **这是定价决策的核心工具**，必须显式列出每一项扣减并允许展开查看来源，不能只给一个毛利率数字。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在页面上有**两个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>每一项扣减都有一行</b>（{@link waterfallRows}），缺的那几项也在行里，
 *       标成「未交付 / 该扣减未计入」并写明归谁管 —— 把它从瀑布图里删掉，
 *       就等于假装它不存在，而缺项恰恰是最需要看见的那一项。
 *   <li><b>每一行都能展开看来源</b>（{@link WaterfallLine.source}）：
 *       哪张表 / 哪个上游模块 / 什么口径 / 什么生效时间。
 * </ol>
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>「取不到」与「确实是 0」是两件事：上游规则模块（5.2.3.1 平台费用、5.2.3.2 物流费用）
 * 未交付 ≠ 这笔费用是 0；包装成本没维护 ≠ 包装成本是 0。
 * 后端对这两种情况分别给了 {@code available=false} 与 {@code amount=null}，
 * 前端这里用 {@link moneyText} 渲染成「未计入」/「未维护」而不是 0.00。
 * 渲染成 0.00 的后果是单位成本偏低、毛利偏高，而页面上看不出任何异常。
 *
 * <h2>🔴 毛利率不可信时不给数字</h2>
 *
 * <p>后端在成本不完整时返回 {@code grossMarginRate = null}（不是 0、不是估算值）。
 * 前端用 {@link percentText} 把它渲染成「不可信（成本不完整）」，
 * 并用 {@link decisionTone} 把整页的状态标红 —— 这个提示**不能藏在 tooltip 里**，
 * 因为下游 5.3.2.1 模板与 5.4 调价会程序化消费这里的数字。
 */

/** 测算方向。与后端 {@code CalcMode} 逐字对应。 */
export type CalcMode = 'PRICE' | 'TARGET_MARGIN';

/** 决策就绪度。与后端 {@code DecisionBasis} 逐字对应。 */
export type DecisionBasis =
  'COMPLETE_FROM_RULES' | 'COMPLETE_FROM_MANUAL_ASSUMPTIONS' | 'INCOMPLETE';

/** 来源类型。与后端 {@code SourceKind} 逐字对应。 */
export type SourceKind = 'RULE' | 'FACT' | 'PARAMETER' | 'DERIVED' | 'MANUAL_ASSUMPTION' | 'INPUT';

/** 能力声明里的一个扣减项。 */
export interface DeductionCapability {
  code: string;
  label: string;
  source: string;
  sourceKind: string;
  available: boolean;
  ownerModule: string;
  impact: string;
  basis: string;
  note: string;
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

/** 能力声明。页面顶部固定显示它。 */
export interface ProfitCalculatorCapabilities {
  deductions: DeductionCapability[];
  sites: SiteOption[];
  fxNote: string;
  decisionGateNote: string;
  costBasisNote: string;
  upstreamNote: string;
  downstreamNote: string;
  sourceNote: string;
  modeNote: string;
  actionNote: string;
}

/** 来源明细的一行（标签 / 取值）。 */
export interface SourceDetail {
  label: string;
  value: string;
}

/** 一个扣减项的来源（展开看来源）。 */
export interface DeductionSource {
  ownerModule: string;
  service: string;
  table: string;
  kind: string;
  basis: string;
  effectiveFrom: string | null;
  trace: string;
  details: SourceDetail[];
}

/** 瀑布图的一行。 */
export interface WaterfallLine {
  code: string;
  label: string;
  deduction: boolean;
  available: boolean;
  partial: boolean;
  sourceKind: string;
  ownerModule: string;
  basis: string;
  unavailableReason: string | null;
  amount: number | null;
  totalAmount: number | null;
  currency: string;
  source: DeductionSource | null;
}

/** 一个缺口：哪一项取不到、归谁管、缺了会怎样、怎么补。 */
export interface BlockingGap {
  code: string;
  label: string;
  ownerModule: string;
  impact: string;
  remedy: string;
  manualAssumptionAccepted: boolean;
}

/** 汇率换算口径（方向显式）。 */
export interface FxView {
  costCurrency: string;
  currency: string;
  rate: number | null;
  direction: string;
  sourceKind: string;
  ownerModule: string;
  available: boolean;
  note: string;
}

/** 物流阶梯在给定数量下的一段。 */
export interface LogisticsBand {
  seq: number;
  fromQuantity: number | null;
  upToQuantity: number | null;
  units: number;
  perUnitCost: number;
  bandTotal: number;
}

/** 一次测算的完整结果。 */
export interface ProfitCalculation {
  skuId: string;
  skuCode: string;
  skuName: string;
  platformCode: string | null;
  siteCode: string | null;
  currency: string;
  costCurrency: string;
  mode: CalcMode;
  quantity: number;
  decisionBasis: DecisionBasis;
  decisionReady: boolean;
  blockingGaps: BlockingGap[];
  fx: FxView;
  waterfall: WaterfallLine[];
  revenue: number | null;
  knownDeductionTotal: number | null;
  revenueLeftForMissingDeductions: number | null;
  grossProfit: number | null;
  grossMarginRate: number | null;
  breakEvenPrice: number | null;
  targetMarginPrice: number | null;
  breakEvenVolume: number | null;
  unitContribution: number | null;
  logisticsBands: LogisticsBand[];
  costMethod: string;
  costEffectiveFrom: string | null;
  grossProfitSuppressedReason: string;
  breakEvenPriceNote: string;
  targetMarginPriceNote: string;
  breakEvenVolumeNote: string;
  decisionNote: string;
}

/** 物流阶梯的一段（输入）。 */
export interface TierInput {
  upToQuantity: number | null;
  perUnitCost: number;
}

/** 测算输入。 */
export interface CalculateRequest {
  skuId: string;
  siteCode: string;
  platformCode: string;
  currency: string;
  costCurrency: string;
  mode: CalcMode;
  inputPrice: number | null;
  targetMarginRate: number | null;
  quantity: number;
  fxRate: number | null;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  monthlyFixedCost: number | null;
  logisticsTiers: TierInput[];
  costWindowFrom: string | null;
  costWindowTo: string | null;
  note: string;
}

/** 方案列表的一行。 */
export interface ScenarioSummary {
  id: string;
  skuId: string;
  skuCode: string | null;
  skuName: string | null;
  platformCode: string | null;
  siteCode: string | null;
  currency: string;
  costCurrency: string;
  mode: CalcMode;
  inputPrice: number | null;
  targetMarginRate: number | null;
  quantity: number;
  decisionBasis: DecisionBasis | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 方案详情。 */
export interface ScenarioDetail {
  summary: ScenarioSummary;
  request: CalculateRequest;
  logisticsTiers: TierInput[];
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  fxRate: number | null;
  monthlyFixedCost: number | null;
  note: string;
  calculation: ProfitCalculation | null;
}

/** 方案列表（分页）。 */
export interface ScenarioPage {
  items: ScenarioSummary[];
  page: number;
  size: number;
  total: number;
}

// ================================================================ 纯函数：口径渲染

/**
 * 金额渲染。
 *
 * <p>🔴 {@code null} ⇒ 「未计入」/「未维护」，**不是 0.00**。
 * 「取不到」与「确实是 0」是两件事。
 */
export function moneyText(value: number | null | undefined, fallback = '未计入'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return trimZeros(value.toFixed(4));
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 取不到时**不**再补币种：「不可信 USD」会让人以为这是个金额。
 * 兜底文案本身已经说明了状态。
 */
export function moneyWithCurrency(
  value: number | null | undefined,
  currency: string | null | undefined,
  fallback = '未计入',
): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return currency ? `${moneyText(value)} ${currency}` : moneyText(value);
}

/**
 * 毛利率渲染。
 *
 * <p>🔴 {@code null} ⇒ 「不可信（成本不完整）」，**不是 0%**。
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

/** 决策就绪度的中文名。 */
export function decisionLabel(basis: DecisionBasis | null | undefined): string {
  switch (basis) {
    case 'COMPLETE_FROM_RULES':
      return '全部来自上游规则';
    case 'COMPLETE_FROM_MANUAL_ASSUMPTIONS':
      return '含手工假设';
    case 'INCOMPLETE':
      return '成本不完整';
    default:
      return '未测算';
  }
}

/** 决策就绪度的色调。INCOMPLETE 必须是 danger（不可忽略）。 */
export function decisionTone(
  basis: DecisionBasis | null | undefined,
): 'success' | 'warning' | 'danger' {
  switch (basis) {
    case 'COMPLETE_FROM_RULES':
      return 'success';
    case 'COMPLETE_FROM_MANUAL_ASSUMPTIONS':
      return 'warning';
    case 'INCOMPLETE':
      return 'danger';
    default:
      return 'danger';
  }
}

/** 来源类型的中文名。 */
export function sourceKindLabel(kind: string | null | undefined): string {
  switch (kind) {
    case 'RULE':
      return '上游规则';
    case 'FACT':
      return '上游事实';
    case 'PARAMETER':
      return '本服务参数';
    case 'DERIVED':
      return '派生值';
    case 'MANUAL_ASSUMPTION':
      return '手工假设';
    case 'INPUT':
      return '本次输入';
    case 'SAME_CURRENCY':
      return '同币种';
    default:
      return kind ?? '—';
  }
}

/** 来源类型的色调。手工假设要显眼 —— 它不是系统口径。 */
export function sourceTone(
  kind: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (kind) {
    case 'RULE':
    case 'FACT':
      return 'success';
    case 'MANUAL_ASSUMPTION':
      return 'warning';
    case 'INPUT':
      return 'info';
    case 'SAME_CURRENCY':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 缺口的色调：真缺 = danger，已用手工假设补上 = warning。 */
export function gapTone(gap: BlockingGap): 'danger' | 'warning' {
  return gap.manualAssumptionAccepted ? 'warning' : 'danger';
}

/** 真正取不到的扣减（不是「已用手工假设补上」的那些）。 */
export function missingGaps(calculation: ProfitCalculation | null): BlockingGap[] {
  if (!calculation) return [];
  return calculation.blockingGaps.filter((gap) => !gap.manualAssumptionAccepted);
}

/** 已用手工假设补上的口径提醒。 */
export function assumptionGaps(calculation: ProfitCalculation | null): BlockingGap[] {
  if (!calculation) return [];
  return calculation.blockingGaps.filter((gap) => gap.manualAssumptionAccepted);
}

/** 瀑布图的行（后端已经排好序：售价 → 每一项扣减 → 毛利）。 */
export function waterfallRows(calculation: ProfitCalculation | null): WaterfallLine[] {
  return calculation?.waterfall ?? [];
}

/** 瀑布图里「真的没算进去」的扣减行。 */
export function missingRows(calculation: ProfitCalculation | null): WaterfallLine[] {
  return waterfallRows(calculation).filter((row) => row.deduction && !row.available);
}

/** 一行扣减的状态文案。 */
export function rowStatusText(row: WaterfallLine): string {
  if (row.available && row.partial) return '部分取到';
  if (row.available) return '已计入';
  return row.unavailableReason ?? '未计入';
}

/** 一行扣减的状态色调。 */
export function rowStatusTone(row: WaterfallLine): 'success' | 'warning' | 'danger' {
  if (row.available && row.partial) return 'warning';
  if (row.available) return 'success';
  return 'danger';
}

/** 顶部统计卡：把关键数字摆出来。 */
export interface SummaryStat {
  label: string;
  value: string;
  hint: string;
  tone: 'success' | 'warning' | 'danger' | 'info';
}

/** 顶部统计卡。🔴 毛利率不可信时显示「不可信」而不是 0%。 */
export function summaryStats(calculation: ProfitCalculation | null): SummaryStat[] {
  if (!calculation) return [];
  const ready = calculation.decisionReady;
  return [
    {
      label: '售价',
      value: moneyWithCurrency(calculation.revenue, calculation.currency, '待测算'),
      hint: `数量 ${quantityText(calculation.quantity)}（用于阶梯与运费）`,
      tone: 'info',
    },
    {
      label: '毛利额（每单位）',
      value: moneyWithCurrency(calculation.grossProfit, calculation.currency, '不可信'),
      hint: ready ? '每一项扣减都已计入' : '🔴 成本不完整，不给数字',
      tone: ready ? 'success' : 'danger',
    },
    {
      label: '毛利率',
      value: percentText(calculation.grossMarginRate),
      hint: ready ? '可用于定价决策' : '🔴 缺项算出来的毛利率是虚高的',
      tone: ready ? 'success' : 'danger',
    },
    {
      label: '剩余可覆盖缺失扣减的金额',
      value: moneyWithCurrency(
        calculation.revenueLeftForMissingDeductions,
        calculation.currency,
        '待测算',
      ),
      hint: '售价减掉已计入的扣减后还剩多少 —— **这不是毛利**',
      tone: ready ? 'info' : 'warning',
    },
    {
      label: '保本价',
      value: moneyWithCurrency(calculation.breakEvenPrice, calculation.currency, '算不出来'),
      hint: calculation.breakEvenPriceNote,
      tone: calculation.breakEvenPrice === null ? 'warning' : 'info',
    },
    {
      label: '目标毛利价',
      value: moneyWithCurrency(calculation.targetMarginPrice, calculation.currency, '算不出来'),
      hint: calculation.targetMarginPriceNote,
      tone: calculation.targetMarginPrice === null ? 'warning' : 'info',
    },
    {
      label: '盈亏平衡销量',
      value:
        calculation.breakEvenVolume === null
          ? '算不出来'
          : `${quantityText(calculation.breakEvenVolume)} 件`,
      hint: calculation.breakEvenVolumeNote,
      tone: calculation.breakEvenVolume === null ? 'warning' : 'info',
    },
  ];
}

/** 物流阶梯的可读摘要。 */
export function tierSummary(tiers: TierInput[]): string {
  if (!tiers.length) return '无阶梯（按每单位运费）';
  return tiers
    .map((tier) =>
      tier.upToQuantity === null
        ? `超出之后 ${tier.perUnitCost}`
        : `第 1~${quantityText(tier.upToQuantity)} 单 ${tier.perUnitCost}`,
    )
    .join('；');
}

// ================================================================ 纯函数：输入校验

/** 校验失败。 */
export interface ValidationError {
  field: string;
  message: string;
}

/**
 * 提交前的本地校验（后端还会再校验一遍，这里是为了少一次往返）。
 *
 * <p>🔴 跨币种没给汇率 ⇒ 拒绝提交。绝不按 1:1 折算。
 */
export function validateRequest(request: CalculateRequest): ValidationError | null {
  if (!request.skuId.trim()) return { field: 'skuId', message: '必须指定 SKU' };
  if (!/^[A-Za-z]{3}$/.test(request.currency)) {
    return { field: 'currency', message: '币种必须是三位大写字母' };
  }
  if (request.costCurrency && !/^[A-Za-z]{3}$/.test(request.costCurrency)) {
    return { field: 'costCurrency', message: '币种必须是三位大写字母' };
  }
  if (request.quantity <= 0) return { field: 'quantity', message: '数量必须大于 0' };
  if (request.mode === 'PRICE') {
    if (request.inputPrice === null || request.inputPrice <= 0) {
      return { field: 'inputPrice', message: '按售价测算时必须给一个大于 0 的售价' };
    }
  } else if (request.targetMarginRate === null) {
    return { field: 'targetMarginRate', message: '按目标毛利率倒推时必须给目标毛利率' };
  }
  if (
    request.targetMarginRate !== null &&
    (request.targetMarginRate < 0 || request.targetMarginRate >= 1)
  ) {
    return {
      field: 'targetMarginRate',
      message: '目标毛利率是小数口径（0.25 表示 25%），必须在 0 与 1 之间',
    };
  }
  if (
    request.platformFeeRate !== null &&
    (request.platformFeeRate < 0 || request.platformFeeRate >= 1)
  ) {
    return {
      field: 'platformFeeRate',
      message: '平台费率是小数口径（0.15 表示 15%），必须在 0 与 1 之间',
    };
  }
  if (
    request.currency.toUpperCase() !== request.costCurrency.toUpperCase() &&
    (request.fxRate === null || request.fxRate <= 0)
  ) {
    return {
      field: 'fxRate',
      message: '售价币种与成本币种不同，必须给汇率（1 成本币种 = X 售价币种）—— 绝不按 1:1 折算',
    };
  }
  const tiers = request.logisticsTiers;
  if (tiers.length) {
    let previous = 0;
    for (const tier of tiers) {
      if (tier.perUnitCost < 0) return { field: 'tiers', message: '每单位运费不能为负' };
      if (tier.upToQuantity !== null) {
        if (tier.upToQuantity <= 0) return { field: 'tiers', message: '阶梯的段容量必须大于 0' };
        if (tier.upToQuantity <= previous) {
          return { field: 'tiers', message: '物流阶梯必须按数量升序排列' };
        }
        previous = tier.upToQuantity;
      }
    }
    const lastTier = tiers[tiers.length - 1];
    if (lastTier && lastTier.upToQuantity !== null) {
      return { field: 'tiers', message: '物流阶梯的最后一段必须无上限' };
    }
  }
  return null;
}

/** 空表单。 */
export function emptyRequest(): CalculateRequest {
  return {
    skuId: '',
    siteCode: '',
    platformCode: '',
    currency: 'USD',
    costCurrency: 'USD',
    mode: 'PRICE',
    inputPrice: null,
    targetMarginRate: null,
    quantity: 1,
    fxRate: null,
    platformFeeRate: null,
    platformFeeFixed: null,
    logisticsCostPerUnit: null,
    monthlyFixedCost: null,
    logisticsTiers: [],
    costWindowFrom: null,
    costWindowTo: null,
    note: '',
  };
}

// ================================================================ 端点

export function fetchCalculatorCapabilities(): Promise<ProfitCalculatorCapabilities> {
  return api<ProfitCalculatorCapabilities>('catalog/profit-calculator/capabilities');
}

export function fetchCalculatorSites(): Promise<SiteOption[]> {
  return api<SiteOption[]>('catalog/profit-calculator/sites');
}

export function calculateProfit(request: CalculateRequest): Promise<ProfitCalculation> {
  return api<ProfitCalculation>('catalog/profit-calculator/calculate', 'POST', request);
}

export function saveProfitScenario(request: CalculateRequest): Promise<ScenarioDetail> {
  return api<ScenarioDetail>('catalog/profit-calculator/scenarios', 'POST', request);
}

export function fetchProfitScenarios(page = 0, size = 20): Promise<ScenarioPage> {
  return api<ScenarioPage>(`catalog/profit-calculator/scenarios?page=${page}&size=${size}`);
}

export function fetchProfitScenario(id: string): Promise<ScenarioDetail> {
  return api<ScenarioDetail>(`catalog/profit-calculator/scenarios/${id}`);
}

export function deleteProfitScenario(id: string): Promise<void> {
  return api<void>(`catalog/profit-calculator/scenarios/${id}`, 'DELETE');
}
