import { api } from '../inventory/api';

/**
 * 5.3.2.2 价格规则前端模型。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：定义自动调价规则：触发条件（库存低于阈值/竞品降价/成本上涨/毛利低于下限/库龄超期）+ 动作（降价/涨价/暂停促销/下架），支持优先级与生效时段。
 *
 * **输入**：规则条件与动作；作用范围；生效时段。
 *
 * **输出**：规则记录 + 命中记录 + 建议调价列表。
 *
 * **关系**：上游 = 7 库存、5.3.2.1；下游 = 5.4.1.1。**自动调价必须先"建议"再"执行"**，高风险规则（降价超过 X%）需人工确认，避免错误规则批量改价造成损失。
 *
 * </blockquote>
 *
 * <h2>🔴 先建议，再执行</h2>
 *
 * <p>本页产出的每一个价格都是**建议价**（蓝图输出句：「建议调价列表」），
 * 改价是 5.4.1.1 调价单的写入所有权。所以页面上：
 *
 * <ul>
 *   <li>价格一栏永远带「建议价」字样，不写成「价格」；
 *   <li>{@link suggestedPriceText} 在取不到当前价时显示「不产出建议价」，
 *       <b>不是 0</b>、也不是一个估出来的数；
 *   <li>{@link requiresConfirmationText} 把「高风险 ⇒ 需人工确认」写在每一行上，
 *       已放行的建议也要显示它**曾经**被哪条规则挡下来过；
 *   <li>能力声明固定挂在页面顶部，把「自动调价必须先建议再执行」写在脸上。
 * </ul>
 *
 * <h2>🔴 null 绝不渲染成 0</h2>
 *
 * <p>「取不到」与「确实是 0」是两件事：库存服务读不到 ≠ 可售量是 0；
 * 成本不完整 ≠ 毛利率是 0%。{@link moneyText} / {@link percentText} 对 {@code null}
 * 一律渲染成兜底文案。
 */

/** 触发条件。与后端 {@code TriggerType} 逐字对应。 */
export type TriggerType =
  | 'INVENTORY_BELOW_THRESHOLD'
  | 'COMPETITOR_PRICE_DROP'
  | 'COST_INCREASE'
  | 'MARGIN_BELOW_FLOOR'
  | 'AGING_OVER_THRESHOLD';

/** 动作。与后端 {@code ActionType} 逐字对应。 */
export type ActionType = 'LOWER_PRICE' | 'RAISE_PRICE' | 'PAUSE_PROMOTION' | 'DELIST';

/** 作用范围类型。与后端 {@code ScopeType} 逐字对应。 */
export type ScopeType = 'CATEGORY' | 'BRAND' | 'SHOP' | 'SKU';

/** 建议调价的状态。与后端 {@code SuggestionStatus} 逐字对应。 */
export type SuggestionStatus = 'PENDING_CONFIRMATION' | 'READY' | 'CONFIRMED' | 'REJECTED';

/** 人工决策。与后端 {@code Decision} 逐字对应。 */
export type Decision = 'CONFIRM' | 'REJECT';

/** 能力声明里的一个词表项。 */
export interface PricingRuleCapabilityItem {
  code: string;
  label: string;
  description: string;
  requiredFields: string[];
}

/** 能力声明。页面顶部固定显示它。 */
export interface PricingRuleCapabilities {
  triggerTypes: PricingRuleCapabilityItem[];
  actionTypes: PricingRuleCapabilityItem[];
  scopeTypes: PricingRuleCapabilityItem[];
  statuses: PricingRuleCapabilityItem[];
  upstreamNote: string;
  downstreamNote: string;
  suggestFirstNote: string;
  competitorNote: string;
  priorityNote: string;
  assumptionNote: string;
  sourceNote: string;
  actionNote: string;
}

/** 分类选项（内部分类树）。 */
export interface PricingRuleCategoryOption {
  code: string;
  name: string;
  parentCode: string | null;
}

/** 品牌选项。 */
export interface PricingRuleBrandOption {
  code: string;
  name: string;
  authorizationStatus: string;
}

/** 作用范围目录（分类 + 品牌）。 */
export interface PricingRuleDirectory {
  categories: PricingRuleCategoryOption[];
  brands: PricingRuleBrandOption[];
  sourceNote: string;
}

/** 一条作用范围（输入）。 */
export interface PricingRuleScopeInput {
  type: ScopeType;
  ref: string;
  platformCode: string | null;
}

/** 一条作用范围（读取）。 */
export interface PricingRuleScopeView {
  id: string;
  type: ScopeType;
  typeLabel: string;
  ref: string;
  platformCode: string | null;
}

/** 规则列表的一行。 */
export interface PricingRuleSummary {
  id: string;
  code: string;
  name: string;
  triggerType: TriggerType;
  triggerTypeLabel: string;
  actionType: ActionType;
  actionTypeLabel: string;
  thresholdQuantity: number | null;
  thresholdRate: number | null;
  marginFloorRate: number | null;
  agingDaysThreshold: number | null;
  actionRate: number | null;
  priority: number;
  currency: string;
  maxAutoPriceDropRate: number;
  enabled: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  scopeCount: number;
  createdBy: string | null;
  updatedAt: string;
}

/** 规则详情。 */
export interface PricingRuleDetail {
  summary: PricingRuleSummary;
  description: string;
  triggerType: TriggerType;
  thresholdQuantity: number | null;
  thresholdRate: number | null;
  marginFloorRate: number | null;
  agingDaysThreshold: number | null;
  actionType: ActionType;
  actionRate: number | null;
  priority: number;
  currency: string;
  maxAutoPriceDropRate: number;
  enabled: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
  version: number;
  scopes: PricingRuleScopeView[];
  createdAt: string;
  updatedAt: string;
}

/** 规则定义（新建与修改共用）。 */
export interface PricingRuleInput {
  code: string;
  name: string;
  description: string;
  triggerType: TriggerType;
  thresholdQuantity: number | null;
  thresholdRate: number | null;
  marginFloorRate: number | null;
  agingDaysThreshold: number | null;
  actionType: ActionType;
  actionRate: number | null;
  priority: number | null;
  currency: string;
  maxAutoPriceDropRate: number | null;
  enabled: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
  scopes: PricingRuleScopeInput[];
  version: number | null;
}

/** 规则列表（分页）。 */
export interface PricingRulePage {
  items: PricingRuleSummary[];
  page: number;
  size: number;
  total: number;
}

/** 一条命中记录（含未命中的判定留痕）。 */
export interface PricingRuleHitView {
  id: string;
  ruleId: string;
  ruleCode: string;
  ruleName: string;
  skuId: string;
  skuCode: string | null;
  skuName: string | null;
  spuId: string;
  triggerType: TriggerType;
  triggerTypeLabel: string;
  matched: boolean;
  superseded: boolean;
  supersededByRuleId: string | null;
  supersededByRuleCode: string | null;
  reason: string;
  evidence: Record<string, unknown> | null;
  evaluatedAt: string;
}

/** 命中记录（分页）。 */
export interface PricingRuleHitPage {
  items: PricingRuleHitView[];
  page: number;
  size: number;
  total: number;
}

/** 一条建议调价。🔴 这里的价是**建议价**，不是生效价。 */
export interface PricingRuleSuggestionView {
  id: string;
  ruleId: string;
  ruleCode: string;
  ruleName: string;
  hitId: string;
  skuId: string;
  skuCode: string | null;
  skuName: string | null;
  spuId: string;
  siteCode: string | null;
  platformCode: string | null;
  currency: string;
  actionType: ActionType;
  actionTypeLabel: string;
  currentPrice: number | null;
  suggestedPrice: number | null;
  priceChangeRate: number | null;
  requiresConfirmation: boolean;
  status: SuggestionStatus;
  statusLabel: string;
  decisionReady: boolean;
  grossMarginRate: number | null;
  grossProfit: number | null;
  reason: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string;
  createdAt: string;
}

/** 建议调价列表（分页）。 */
export interface PricingRuleSuggestionPage {
  items: PricingRuleSuggestionView[];
  page: number;
  size: number;
  total: number;
}

/** 一次评估的请求。 */
export interface PricingRuleEvaluateRequest {
  skuIds: string[];
  ruleId: string | null;
  siteCode: string | null;
  platformCode: string | null;
  currency: string;
  costCurrency: string | null;
  quantity: number | null;
  fxRate: number | null;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  note: string;
}

/** 一次评估的结果。 */
export interface PricingRuleEvaluationResult {
  evaluatedSkuCount: number;
  evaluatedRuleCount: number;
  hitCount: number;
  suggestionCount: number;
  pendingConfirmationCount: number;
  hits: PricingRuleHitView[];
  suggestions: PricingRuleSuggestionView[];
  note: string;
}

/** 人工确认 / 驳回一条建议调价。 */
export interface PricingRuleDecideRequest {
  decision: Decision;
  note: string;
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
  const value = finiteOrNull(rate);
  if (value === null) return fallback;
  return `${trimZeros((value * 100).toFixed(2))}%`;
}

/** 数量渲染。 */
export function quantityText(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return trimZeros(value.toFixed(6));
}

/** 天数渲染。 */
export function daysText(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${value} 天`;
}

/** 去掉小数末尾的 0（10.0000 → 10、0.0000 → 0、24.7059 → 24.7059）。 */
function trimZeros(text: string): string {
  if (!text.includes('.')) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/** number | null | undefined ⇒ number | null（把 NaN 也归到 null）。 */
function finiteOrNull(value: number | null | undefined): number | null {
  if (value === null || value === undefined || Number.isNaN(value)) return null;
  return value;
}

/** 触发条件的中文名。 */
export function triggerTypeLabel(type: TriggerType | null | undefined): string {
  switch (type) {
    case 'INVENTORY_BELOW_THRESHOLD':
      return '库存低于阈值';
    case 'COMPETITOR_PRICE_DROP':
      return '竞品降价';
    case 'COST_INCREASE':
      return '成本上涨';
    case 'MARGIN_BELOW_FLOOR':
      return '毛利低于下限';
    case 'AGING_OVER_THRESHOLD':
      return '库龄超期';
    default:
      return '—';
  }
}

/** 动作的中文名。 */
export function actionTypeLabel(type: ActionType | null | undefined): string {
  switch (type) {
    case 'LOWER_PRICE':
      return '降价';
    case 'RAISE_PRICE':
      return '涨价';
    case 'PAUSE_PROMOTION':
      return '暂停促销';
    case 'DELIST':
      return '下架';
    default:
      return '—';
  }
}

/** 作用范围类型的中文名。 */
export function scopeTypeLabel(type: ScopeType | null | undefined): string {
  switch (type) {
    case 'CATEGORY':
      return '分类';
    case 'BRAND':
      return '品牌';
    case 'SHOP':
      return '店铺';
    case 'SKU':
      return 'SKU';
    default:
      return '—';
  }
}

/** 建议状态的中文名。 */
export function suggestionStatusLabel(status: SuggestionStatus | null | undefined): string {
  switch (status) {
    case 'PENDING_CONFIRMATION':
      return '待人工确认';
    case 'READY':
      return '已放行';
    case 'CONFIRMED':
      return '已确认';
    case 'REJECTED':
      return '已驳回';
    default:
      return '—';
  }
}

/**
 * 触发条件阈值的文案。
 *
 * <p>🔴 阈值按触发条件各用各的列（数量 / 比例 / 天数），**不可用的一律显示「未维护」**
 * 而不是 0：把「没配」显示成 0 会让人以为「阈值就是 0」。
 */
export function thresholdText(input: {
  triggerType: TriggerType | null | undefined;
  thresholdQuantity: number | null | undefined;
  thresholdRate: number | null | undefined;
  marginFloorRate: number | null | undefined;
  agingDaysThreshold: number | null | undefined;
}): string {
  switch (input.triggerType) {
    case 'INVENTORY_BELOW_THRESHOLD':
      return `可售量 < ${quantityText(input.thresholdQuantity)} 件`;
    case 'COMPETITOR_PRICE_DROP':
      return `降幅 ≥ ${percentText(input.thresholdRate, '未维护')}`;
    case 'COST_INCREASE':
      return `涨幅 ≥ ${percentText(input.thresholdRate, '未维护')}`;
    case 'MARGIN_BELOW_FLOOR':
      return `毛利率 < ${percentText(input.marginFloorRate, '未维护')}`;
    case 'AGING_OVER_THRESHOLD':
      return `库龄 > ${daysText(input.agingDaysThreshold)}`;
    default:
      return '—';
  }
}

/** 动作幅度的文案。 */
export function actionRateText(
  action: ActionType | null | undefined,
  rate: number | null | undefined,
): string {
  if (!action) return '—';
  if (action === 'PAUSE_PROMOTION' || action === 'DELIST') {
    return action === 'PAUSE_PROMOTION' ? '暂停促销（非价格动作）' : '下架（非价格动作）';
  }
  if (rate === null || rate === undefined || Number.isNaN(rate)) return '未维护';
  return `${percentText(rate, '未维护')}`;
}

/**
 * 建议价的文案。
 *
 * <p>🔴 取不到当前价时后端**不产出建议**（{@code suggestedPrice} 为 null），
 * 这里必须显示「不产出建议价」，**不是 0**。
 */
export function suggestedPriceText(suggestion: PricingRuleSuggestionView | null): string {
  if (!suggestion) return '待评估';
  if (suggestion.suggestedPrice === null || suggestion.suggestedPrice === undefined) {
    return '不产出建议价（取不到当前价）';
  }
  return moneyWithCurrency(suggestion.suggestedPrice, suggestion.currency, '待评估');
}

/** 当前价的文案。 */
export function currentPriceText(suggestion: PricingRuleSuggestionView | null): string {
  if (!suggestion) return '—';
  if (suggestion.currentPrice === null || suggestion.currentPrice === undefined) {
    return '取不到当前价';
  }
  return moneyWithCurrency(suggestion.currentPrice, suggestion.currency, '取不到当前价');
}

/**
 * 建议价相对当前价的变动文案。
 *
 * <p>🔴 负数 = 降价。**只报降幅的绝对值**：用户关心的是「降了多少」。
 */
export function priceChangeText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return '—';
  if (rate === 0) return '不变';
  return rate < 0 ? `降 ${percentText(Math.abs(rate), '—')}` : `涨 ${percentText(rate, '—')}`;
}

/**
 * 高风险提示的文案。
 *
 * <p>🔴 这条是蓝图生死线的页面落点：「高风险规则（降价超过 X%）需人工确认」。
 */
export function requiresConfirmationText(suggestion: PricingRuleSuggestionView | null): string {
  if (!suggestion) return '';
  if (suggestion.requiresConfirmation) {
    return suggestion.status === 'PENDING_CONFIRMATION'
      ? '🔴 高风险：需人工确认后才放行'
      : '🔴 曾按高风险处理（需人工确认）';
  }
  if (!suggestion.decisionReady) {
    return '🔴 成本不完整（复核不了毛利）⇒ 不自动放行';
  }
  return '低风险：已放行，可被 5.4.1.1 消费';
}

/** 状态的色调。 */
export function statusTone(status: SuggestionStatus | null | undefined): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'READY':
      return 'success';
    case 'CONFIRMED':
      return 'success';
    case 'PENDING_CONFIRMATION':
      return 'warning';
    case 'REJECTED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 命中与否的色调。 */
export function hitTone(matched: boolean, superseded: boolean): 'success' | 'warning' | 'neutral' {
  if (superseded) return 'warning';
  return matched ? 'success' : 'neutral';
}

/** 命中状态的中文名。 */
export function hitStatusText(hit: PricingRuleHitView): string {
  if (hit.superseded) return '被更高优先级规则覆盖';
  return hit.matched ? '命中' : '未命中';
}

/** 决策就绪度的色调。 */
export function decisionTone(ready: boolean | null | undefined): 'success' | 'warning' | 'danger' {
  if (ready === true) return 'success';
  if (ready === false) return 'danger';
  return 'warning';
}

/** 建议调价的统计卡。 */
export interface SummaryStat {
  label: string;
  value: string;
  hint: string;
  tone: 'success' | 'warning' | 'danger' | 'info';
}

/**
 * 顶部统计卡。
 *
 * <p>🔴 建议价与毛利率不可信时显示兜底文案，而不是 0。
 */
export function summaryStats(result: PricingRuleEvaluationResult | null): SummaryStat[] {
  if (!result) return [];
  return [
    {
      label: '评估的 SKU 数',
      value: String(result.evaluatedSkuCount),
      hint: `跑了 ${result.evaluatedRuleCount} 条当前生效的规则`,
      tone: 'info',
    },
    {
      label: '命中记录',
      value: String(result.hitCount),
      hint: '含未命中的判定留痕（运营要回答「为什么没动我的商品」）',
      tone: 'info',
    },
    {
      label: '建议调价',
      value: String(result.suggestionCount),
      hint: '🔴 建议价，不是生效价 —— 改价走 5.4.1.1 调价单',
      tone: result.suggestionCount > 0 ? 'success' : 'info',
    },
    {
      label: '待人工确认',
      value: String(result.pendingConfirmationCount),
      hint: '高风险（降价超阈值 / 成本不完整 / 非价格动作）',
      tone: result.pendingConfirmationCount > 0 ? 'warning' : 'success',
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
 * 规则表单的本地校验（后端还会再校验一遍，这里是为了少一次往返）。
 *
 * <p>🔴 阈值按触发条件各用各的列：不适用的那一列必须为空，
 * 填了会被后端拒（DB 层也有 CHECK 兜底）。
 */
export function validateRule(input: PricingRuleInput): ValidationError | null {
  if (!input.code.trim()) return { field: 'code', message: '规则编码必填' };
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(input.code.trim())) {
    return { field: 'code', message: '规则编码只能是大写字母、数字、短横线与下划线' };
  }
  if (!input.name.trim()) return { field: 'name', message: '规则名称必填' };
  if (!/^[A-Za-z]{3}$/.test(input.currency)) {
    return { field: 'currency', message: '币种必须是三位大写字母' };
  }
  switch (input.triggerType) {
    case 'INVENTORY_BELOW_THRESHOLD':
      if (input.thresholdQuantity === null || input.thresholdQuantity < 0) {
        return { field: 'thresholdQuantity', message: '库存低于阈值必须给库存阈值（≥ 0 的件数）' };
      }
      break;
    case 'COMPETITOR_PRICE_DROP':
    case 'COST_INCREASE':
      if (input.thresholdRate === null || input.thresholdRate <= 0 || input.thresholdRate >= 1) {
        return {
          field: 'thresholdRate',
          message: '涨跌幅阈值必须是 0 与 1 之间的小数（0.1 = 10%）',
        };
      }
      break;
    case 'MARGIN_BELOW_FLOOR':
      if (
        input.marginFloorRate === null ||
        input.marginFloorRate < 0 ||
        input.marginFloorRate >= 1
      ) {
        return {
          field: 'marginFloorRate',
          message: '毛利下限必须是 0 与 1 之间的小数（0.2 = 20%）',
        };
      }
      break;
    case 'AGING_OVER_THRESHOLD':
      if (input.agingDaysThreshold === null || input.agingDaysThreshold < 0) {
        return { field: 'agingDaysThreshold', message: '库龄超期必须给库龄阈值（≥ 0 的天数）' };
      }
      break;
    default:
      return { field: 'triggerType', message: '必须选触发条件' };
  }
  if (input.actionType === 'LOWER_PRICE' || input.actionType === 'RAISE_PRICE') {
    if (input.actionRate === null || input.actionRate <= 0 || input.actionRate >= 1) {
      return {
        field: 'actionRate',
        message: '降价 / 涨价必须给调价幅度（0 与 1 之间的小数：0.05 = 5%）',
      };
    }
  } else if (input.actionRate !== null) {
    return {
      field: 'actionRate',
      message: '暂停促销 / 下架不是价格动作，不能给调价幅度',
    };
  }
  if (
    input.maxAutoPriceDropRate !== null &&
    (input.maxAutoPriceDropRate < 0 || input.maxAutoPriceDropRate >= 1)
  ) {
    return {
      field: 'maxAutoPriceDropRate',
      message: '自动放行的最大降价幅度必须是 0 与 1 之间的小数（0.1 = 10%）',
    };
  }
  if (input.effectiveTo && input.effectiveTo <= input.effectiveFrom) {
    return { field: 'effectiveTo', message: '生效终点必须晚于生效起点' };
  }
  for (const scope of input.scopes) {
    if (!scope.ref.trim()) {
      return { field: 'scopes', message: '作用范围必须给取值（分类码 / 品牌码 / 站点码 / SKU 编码）' };
    }
  }
  return null;
}

/**
 * 评估表单的本地校验。
 *
 * <p>🔴 当前价来自 5.5.2.1 的价格观测台账（按「平台 + 站点 + SKU」登记），
 * 所以目标店铺与目标平台必填 —— 没有它们就取不到价，也就没有建议价。
 */
export function validateEvaluate(
  request: PricingRuleEvaluateRequest,
): ValidationError | null {
  if (request.skuIds.length === 0) return { field: 'skuIds', message: '至少给一个 SKU' };
  if (!request.siteCode?.trim()) {
    return { field: 'siteCode', message: '必须给目标店铺（当前价按「平台 + 站点 + SKU」登记）' };
  }
  if (!request.platformCode?.trim()) {
    return { field: 'platformCode', message: '必须给目标平台（当前价按「平台 + 站点 + SKU」登记）' };
  }
  if (!/^[A-Za-z]{3}$/.test(request.currency)) {
    return { field: 'currency', message: '币种必须是三位大写字母' };
  }
  if (
    request.costCurrency &&
    request.costCurrency.toUpperCase() !== request.currency.toUpperCase() &&
    (request.fxRate === null || request.fxRate <= 0)
  ) {
    return {
      field: 'fxRate',
      message: '售价币种与成本币种不同，必须给汇率（1 成本币种 = X 售价币种）—— 绝不按 1:1 折算',
    };
  }
  if (request.quantity !== null && request.quantity <= 0) {
    return { field: 'quantity', message: '数量必须大于 0' };
  }
  return null;
}

/** 空规则表单。 */
export function emptyRule(): PricingRuleInput {
  return {
    code: '',
    name: '',
    description: '',
    triggerType: 'INVENTORY_BELOW_THRESHOLD',
    thresholdQuantity: 10,
    thresholdRate: null,
    marginFloorRate: null,
    agingDaysThreshold: null,
    actionType: 'LOWER_PRICE',
    actionRate: 0.05,
    priority: 100,
    currency: 'USD',
    maxAutoPriceDropRate: 0.1,
    enabled: true,
    effectiveFrom: new Date().toISOString(),
    effectiveTo: null,
    note: '',
    scopes: [],
    version: null,
  };
}

/** 空评估表单。 */
export function emptyEvaluate(): PricingRuleEvaluateRequest {
  return {
    skuIds: [],
    ruleId: null,
    siteCode: 'US',
    platformCode: 'AMAZON',
    currency: 'USD',
    costCurrency: null,
    quantity: 1,
    fxRate: null,
    platformFeeRate: null,
    platformFeeFixed: null,
    logisticsCostPerUnit: null,
    note: '',
  };
}

/**
 * 切换触发条件时，把不适用的阈值列清空。
 *
 * <p>🔴 不清空的话，上一个条件的阈值会被后端当成「这个条件也配了」而拒（DB 层有 CHECK）。
 */
export function applyTrigger(input: PricingRuleInput, trigger: TriggerType): PricingRuleInput {
  const next: PricingRuleInput = {
    ...input,
    triggerType: trigger,
    thresholdQuantity: null,
    thresholdRate: null,
    marginFloorRate: null,
    agingDaysThreshold: null,
  };
  switch (trigger) {
    case 'INVENTORY_BELOW_THRESHOLD':
      next.thresholdQuantity = 10;
      break;
    case 'COMPETITOR_PRICE_DROP':
    case 'COST_INCREASE':
      next.thresholdRate = 0.1;
      break;
    case 'MARGIN_BELOW_FLOOR':
      next.marginFloorRate = 0.2;
      break;
    case 'AGING_OVER_THRESHOLD':
      next.agingDaysThreshold = 90;
      break;
    default:
      break;
  }
  return next;
}

/**
 * 切换动作时，把不适用的幅度列清空。
 *
 * <p>🔴 非价格动作带幅度会被后端拒（DB 层也有 CHECK）。
 */
export function applyAction(input: PricingRuleInput, action: ActionType): PricingRuleInput {
  if (action === 'LOWER_PRICE' || action === 'RAISE_PRICE') {
    return { ...input, actionType: action, actionRate: input.actionRate ?? 0.05 };
  }
  return { ...input, actionType: action, actionRate: null };
}

/** 规则详情 → 表单（编辑时用）。 */
export function toRuleInput(detail: PricingRuleDetail): PricingRuleInput {
  return {
    code: detail.summary.code,
    name: detail.summary.name,
    description: detail.description,
    triggerType: detail.triggerType,
    thresholdQuantity: detail.thresholdQuantity,
    thresholdRate: detail.thresholdRate,
    marginFloorRate: detail.marginFloorRate,
    agingDaysThreshold: detail.agingDaysThreshold,
    actionType: detail.actionType,
    actionRate: detail.actionRate,
    priority: detail.priority,
    currency: detail.currency,
    maxAutoPriceDropRate: detail.maxAutoPriceDropRate,
    enabled: detail.enabled,
    effectiveFrom: detail.effectiveFrom,
    effectiveTo: detail.effectiveTo,
    note: detail.note,
    scopes: detail.scopes.map((scope) => ({
      type: scope.type,
      ref: scope.ref,
      platformCode: scope.platformCode,
    })),
    version: detail.version,
  };
}

// ================================================================ 端点

export function fetchPricingRuleCapabilities(): Promise<PricingRuleCapabilities> {
  return api<PricingRuleCapabilities>('catalog/pricing-rules/capabilities');
}

export function fetchPricingRuleDirectory(): Promise<PricingRuleDirectory> {
  return api<PricingRuleDirectory>('catalog/pricing-rules/directory');
}

export function fetchPricingRules(
  query: {
    keyword?: string | undefined;
    triggerType?: TriggerType | null | undefined;
    actionType?: ActionType | null | undefined;
    enabled?: boolean | null | undefined;
    scopeType?: ScopeType | null | undefined;
    scopeRef?: string | null | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<PricingRulePage> {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.triggerType) params.set('triggerType', query.triggerType);
  if (query.actionType) params.set('actionType', query.actionType);
  if (query.enabled !== null && query.enabled !== undefined) {
    params.set('enabled', String(query.enabled));
  }
  if (query.scopeType) params.set('scopeType', query.scopeType);
  if (query.scopeRef) params.set('scopeRef', query.scopeRef);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return api<PricingRulePage>(`catalog/pricing-rules/rules?${params.toString()}`);
}

export function fetchPricingRule(id: string): Promise<PricingRuleDetail> {
  return api<PricingRuleDetail>(`catalog/pricing-rules/rules/${id}`);
}

export function createPricingRule(input: PricingRuleInput): Promise<PricingRuleDetail> {
  return api<PricingRuleDetail>('catalog/pricing-rules/rules', 'POST', input);
}

export function updatePricingRule(id: string, input: PricingRuleInput): Promise<PricingRuleDetail> {
  return api<PricingRuleDetail>(`catalog/pricing-rules/rules/${id}`, 'PUT', input);
}

export function deletePricingRule(id: string): Promise<void> {
  return api<void>(`catalog/pricing-rules/rules/${id}`, 'DELETE');
}

/**
 * 跑一次规则评估。
 *
 * <p>🔴 产出的是**命中记录 + 建议调价**，不是生效价 —— 改价是 5.4.1.1 的写入所有权。
 */
export function evaluatePricingRules(
  request: PricingRuleEvaluateRequest,
): Promise<PricingRuleEvaluationResult> {
  return api<PricingRuleEvaluationResult>('catalog/pricing-rules/evaluate', 'POST', request);
}

export function fetchPricingRuleHits(
  query: {
    ruleId?: string | null | undefined;
    skuId?: string | null | undefined;
    matched?: boolean | null | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<PricingRuleHitPage> {
  const params = new URLSearchParams();
  if (query.ruleId) params.set('ruleId', query.ruleId);
  if (query.skuId) params.set('skuId', query.skuId);
  if (query.matched !== null && query.matched !== undefined) {
    params.set('matched', String(query.matched));
  }
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return api<PricingRuleHitPage>(`catalog/pricing-rules/hits?${params.toString()}`);
}

export function fetchPricingRuleSuggestions(
  query: {
    ruleId?: string | null | undefined;
    skuId?: string | null | undefined;
    status?: SuggestionStatus | null | undefined;
    requiresConfirmation?: boolean | null | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<PricingRuleSuggestionPage> {
  const params = new URLSearchParams();
  if (query.ruleId) params.set('ruleId', query.ruleId);
  if (query.skuId) params.set('skuId', query.skuId);
  if (query.status) params.set('status', query.status);
  if (query.requiresConfirmation !== null && query.requiresConfirmation !== undefined) {
    params.set('requiresConfirmation', String(query.requiresConfirmation));
  }
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return api<PricingRuleSuggestionPage>(`catalog/pricing-rules/suggestions?${params.toString()}`);
}

/** 人工确认 / 驳回一条建议调价（「执行」这一步的人工闸）。 */
export function decidePricingRuleSuggestion(
  id: string,
  request: PricingRuleDecideRequest,
): Promise<PricingRuleSuggestionView> {
  return api<PricingRuleSuggestionView>(`catalog/pricing-rules/suggestions/${id}/decide`, 'POST', request);
}
