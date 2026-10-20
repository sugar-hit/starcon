import { api } from '../inventory/api';

/**
 * 10.6.1.1 成本分析前端模型。
 *
 * <p>蓝图：「分析物流成本：单位重量/单均运费、头程分摊后到岸成本占比、各渠道成本对比、
 * 超成本订单识别、头程费用分摊明细。输入：实际运费与头程费用；包裹与发运记录。
 * 输出：成本报表 + 分摊明细 + 异常订单清单。」并加粗
 * 「<b>物流成本常被低估</b>：真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，
 * 报表必须全口径」。
 *
 * <p>🔴 这个模型最容易做假活的地方只有一处，但那一处最贵：
 * <b>把「取不到数」画成 0</b>。
 *
 * <p>所以本模型的每一个金额字段都是 {@code number | null}，并且有且只有一个格式化入口
 * {@link formatAmount}：{@code null} ⇒ 「暂无数据」（中性色），{@code 0} ⇒ 「0.00」
 * （真的是这个数）。前端**不许**在任何地方写 {@code value ?? 0}。
 *
 * <p>🔴 第二个容易做假活的地方是**来源**。真实成本 = 尾程（事实）+ 头程（事实）+
 * 包材（**参数**）+ 关税（事实）+ 仓储费（本仓库无来源）。把「租户自己填的每票包材成本」
 * 报成「事实」，比不报包材这一位更危险 —— 前者会让下游（5 定价 / 13 利润）
 * 以为这个数有账单支撑。所以 {@link CostSourceKind} 在界面上**分色**显示。
 */

export type CostSourceKind = 'FACT' | 'PARAMETER';

/** Badge 的 tone 取值（@starcon/ui Badge 有 7 种 tone）。 */
export type Tone = 'neutral' | 'success' | 'warning' | 'danger' | 'brand' | 'info' | 'primary';

/** 「取不到数」的统一文案。**任何** null 金额都显示它，不显示 0。 */
export const NO_DATA = '暂无数据';

export interface CostCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  unavailableReason: string;
  impact: string;
}

/** 一个成本分量的口径与可得性。 */
export interface CostComponentCapability {
  component: string;
  displayName: string;
  available: boolean;
  /** FACT = 事实表算出来的；PARAMETER = 租户维护的参数算出来的。 */
  sourceKind: CostSourceKind;
  source: string;
  basis: string;
  ownerModule: string;
  unavailableReason: string;
  impact: string;
}

export interface CostCapabilities {
  components: CostComponentCapability[];
  dimensions: CostCapabilityDimension[];
  basisNote: string;
  financeNote: string;
  notes: string[];
}

/** 逐币种金额合计。**跨币种绝不相加。 */
export interface CostCurrencyTotal {
  currency: string;
  amount: number;
  count: number;
}

/** 一个成本分量在本次报表里的取数结果。 */
export interface CostComponentAmount {
  component: string;
  displayName: string;
  available: boolean;
  sourceKind: CostSourceKind;
  source: string;
  basis: string;
  ownerModule: string;
  unavailableReason: string;
  impact: string;
  /** 逐币种的**实付 / 已定案**金额 —— 只有这一列进合计。 */
  byCurrency: CostCurrencyTotal[];
  /** 逐币种的**估算**金额 —— **不进合计**。 */
  estimatedByCurrency: CostCurrencyTotal[];
  currencyCount: number;
}

/** 逐币种的一行合计。null = 这个分量在这个币种上取不到数（不是 0）。 */
export interface CostCurrencyRow {
  currency: string;
  lastMileFreight: number | null;
  firstMileAllocation: number | null;
  dutyAndTax: number | null;
  packaging: number | null;
  subtotal: number | null;
  lastMileShare: number | null;
  firstMileShare: number | null;
  dutyAndTaxShare: number | null;
  packagingShare: number | null;
  includedComponents: string[];
  missingComponents: string[];
}

export interface CostUnitMetric {
  currency: string;
  totalFreightAmount: number | null;
  registeredWaybills: number;
  notRegisteredWaybills: number;
  totalWeightKg: number | null;
  perOrderFreight: number | null;
  perKgFreight: number | null;
}

export interface CostCoverage {
  complete: boolean;
  missingComponents: string[];
  note: string;
  notes: string[];
}

export interface CostOverview {
  computedAt: string;
  windowFrom: string;
  windowTo: string;
  components: CostComponentAmount[];
  currencyRows: CostCurrencyRow[];
  unitMetrics: CostUnitMetric[];
  coverage: CostCoverage;
  scannedWaybills: number;
  truncated: boolean;
  notes: string[];
}

export interface CostChannelCurrencyMetric {
  currency: string;
  amount: number;
  waybills: number;
  totalWeightKg: number | null;
  perOrderFreight: number | null;
  perKgFreight: number | null;
}

export interface CostChannelRow {
  carrierCode: string;
  carrierChannel: string;
  carrierChannelLabel: string;
  waybills: number;
  freightRegistered: number;
  freightNotRegistered: number;
  byCurrency: CostChannelCurrencyMetric[];
}

export interface CostChannelSummary {
  carrierCount: number;
  waybills: number;
  freightRegistered: number;
  freightNotRegistered: number;
}

export interface CostChannelComparison {
  computedAt: string;
  windowFrom: string;
  windowTo: string;
  rows: CostChannelRow[];
  summary: CostChannelSummary;
  scannedWaybills: number;
  truncated: boolean;
  notes: string[];
}

export interface CostCurrencyThreshold {
  currency: string;
  averageUnitFreight: number | null;
  thresholdUnitFreight: number | null;
  evaluatedWaybills: number;
  overCostWaybills: number;
}

export interface CostOverCostOrderRow {
  id: string;
  waybillNo: string;
  salesOrderNo: string;
  outboundOrderNo: string;
  carrierCode: string;
  carrierChannel: string;
  destinationCountry: string;
  currency: string;
  freightAmount: number;
  grossWeightKg: number | null;
  unitFreight: number | null;
  averageUnitFreight: number | null;
  thresholdUnitFreight: number | null;
  excessAmount: number | null;
  excessReason: string;
  createdAt: string;
}

export interface CostOverCostOrderPage {
  computedAt: string;
  windowFrom: string;
  windowTo: string;
  thresholdMultiplier: number;
  thresholds: CostCurrencyThreshold[];
  items: CostOverCostOrderRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  notes: string[];
}

export interface CostAllocationRuleRow {
  id: string;
  ruleCode: string;
  ruleName: string;
  version: number;
  allocationBasis: string;
  allocationBasisLabel: string;
  status: string;
  statusLabel: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
  allocationCount: number;
  createdAt: string;
}

export interface CostAllocationRulePage {
  items: CostAllocationRuleRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CostAllocationRuleRequest {
  ruleCode: string;
  ruleName: string;
  allocationBasis: 'BY_QUANTITY' | 'BY_WEIGHT' | 'BY_DECLARED_VALUE' | 'EQUAL';
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
  activate: boolean;
}

export interface CostAllocationRow {
  id: string;
  allocationCode: string;
  ruleId: string;
  ruleCode: string;
  ruleVersion: number;
  allocationBasis: string;
  allocationBasisLabel: string;
  sourceShipmentId: string;
  sourceShipmentCode: string;
  sourceTransportMode: string;
  sourceCarrierName: string;
  destinationCountryCode: string;
  currency: string;
  sourceTotalAmount: number;
  allocatedTotalAmount: number;
  basisTotal: number;
  lineCount: number;
  status: string;
  statusLabel: string;
  computedAt: string;
  costAsOf: string;
  lockedAt: string | null;
  supersededAt: string | null;
  supersededByRuleVersion: number | null;
  note: string;
  createdAt: string;
}

export interface CostAllocationLineRow {
  id: string;
  lineNo: number;
  skuId: string | null;
  skuCode: string;
  skuName: string;
  hsCode: string;
  basisValue: number;
  basisUnit: string;
  ratio: number | null;
  allocatedAmount: number;
  currency: string;
}

export interface CostAllocationDetail {
  allocation: CostAllocationRow;
  lines: CostAllocationLineRow[];
}

export interface CostAllocationPage {
  items: CostAllocationRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  computedCount: number;
  lockedCount: number;
  supersededCount: number;
  allocatedByCurrency: CostCurrencyTotal[];
  notes: string[];
}

export interface CostAllocationRequest {
  ruleId: string;
  sourceShipmentId: string;
  costAsOf: string;
  note: string;
}

export interface CostAllocationLockRequest {
  costAsOf: string | null;
  note: string;
}

/** 一条成本参数（租户维护的**参数**口径）。 */
export interface LogisticsCostParameterRow {
  id: string;
  component: string;
  currency: string;
  amountPerWaybill: number;
  unit: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface LogisticsCostParameterPage {
  items: LogisticsCostParameterRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  notes: string[];
}

export interface LogisticsCostParameterRequest {
  component: string;
  currency: string;
  amountPerWaybill: number;
  note: string;
}

// ================================================================== 词表

/** 五个成本分量（闭集）。与后端 {@code CostComponent} 一一对应。 */
export const COST_COMPONENT_LABELS: Record<string, string> = {
  LAST_MILE_FREIGHT: '尾程运费',
  FIRST_MILE_ALLOCATION: '头程分摊',
  PACKAGING: '包材',
  DUTY_AND_TAX: '关税与增值税',
  STORAGE_FEE: '仓储费',
};

export const COST_BASIS_LABELS: Record<string, string> = {
  BY_QUANTITY: '按发出数量',
  BY_WEIGHT: '按毛重',
  BY_DECLARED_VALUE: '按申报价值',
  EQUAL: '均摊',
};

export const COST_ALLOCATION_STATUS_LABELS: Record<string, string> = {
  COMPUTED: '已计算',
  LOCKED: '已结账',
  SUPERSEDED: '已被新规则版本取代',
};

export const COST_RULE_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '现行',
  RETIRED: '已退役',
};

export function componentLabel(code: string): string {
  return COST_COMPONENT_LABELS[code] ?? code;
}

export function basisLabel(code: string): string {
  return COST_BASIS_LABELS[code] ?? code;
}

export function allocationStatusLabel(code: string): string {
  return COST_ALLOCATION_STATUS_LABELS[code] ?? code;
}

export function ruleStatusLabel(code: string): string {
  return COST_RULE_STATUS_LABELS[code] ?? code;
}

/** 来源类别的人读标签。 */
export function sourceKindLabel(kind: string): string {
  return kind === 'PARAMETER' ? '参数' : '事实';
}

/**
 * 来源类别的配色：**参数**用 warning，**事实**用 success。
 *
 * <p>把参数和事实画成同一个颜色，等于抹掉了「这个数有没有账单支撑」这个信息 ——
 * 而这正是成本被低估时最该被看见的一层。
 */
export function sourceKindTone(kind: string): Tone {
  return kind === 'PARAMETER' ? 'warning' : 'success';
}

/** 分量状态：不可用是 danger，其余按来源类别分色。 */
export function componentTone(component: CostComponentAmount): Tone {
  if (!component.available) return 'danger';
  return sourceKindTone(component.sourceKind);
}

// ================================================================== 格式化

/**
 * 🔴 金额格式化的**唯一**入口。
 *
 * <p>{@code null} ⇒ 「暂无数据」（**不是 0**）；{@code 0} ⇒ 「0.00」。
 * 页面里任何地方都**不许**写 {@code value ?? 0} —— 那正是本模块要治的病。
 */
export function formatAmount(value: number | null | undefined, currency?: string): string {
  if (value === null || value === undefined) return NO_DATA;
  const text = value.toFixed(2);
  return currency ? `${text} ${currency}` : text;
}

/** 占比格式化：null ⇒ 「暂无数据」。 */
export function formatShare(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA;
  return `${(value * 100).toFixed(2)}%`;
}

/** 数量格式化：null ⇒ 「暂无数据」。 */
export function formatQuantity(value: number | null | undefined, unit = ''): string {
  if (value === null || value === undefined) return NO_DATA;
  const text = value.toFixed(3);
  return unit ? `${text} ${unit}` : text;
}

/** 一个金额格是不是「取不到数」（界面据此换中性色 + 说明文字）。 */
export function isMissing(value: number | null | undefined): boolean {
  return value === null || value === undefined;
}

/** 覆盖状态：全口径成立是 success，否则 warning（不是 danger —— 缺项是常态）。 */
export function coverageTone(complete: boolean): Tone {
  return complete ? 'success' : 'warning';
}

/** 缺项的逐条中文说明。 */
export function missingComponentLabels(codes: string[]): string[] {
  return codes.map(componentLabel);
}

/** 逐币种的合计：只有**真的取到数**的币种才有一行。 */
export function currencyRowsWithTotals(rows: CostCurrencyRow[]): CostCurrencyRow[] {
  return rows.filter((row) => row.subtotal !== null);
}

/** 分摊单状态色：已结账 = success（凭据），已计算 = info，被取代 = neutral。 */
export function allocationStatusTone(status: string): Tone {
  switch (status) {
    case 'LOCKED':
      return 'success';
    case 'COMPUTED':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 规则版本状态色：现行 = success，草稿 = info，已退役 = neutral。 */
export function ruleStatusTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'DRAFT':
      return 'info';
    default:
      return 'neutral';
  }
}

// ================================================================== 筛选

export interface CostFilters {
  dateFrom: string;
  dateTo: string;
  multiplier: string;
  page: number;
  size: number;
}

/** 默认窗口：最近 30 天（与后端 DEFAULT_WINDOW_DAYS 同口径）。 */
export const EMPTY_COST_FILTERS: CostFilters = {
  dateFrom: '',
  dateTo: '',
  multiplier: '1.5',
  page: 0,
  size: 20,
};

/** 分摊单列表的筛选。 */
export interface CostAllocationFilters {
  status: string;
  page: number;
  size: number;
}

export const EMPTY_ALLOCATION_FILTERS: CostAllocationFilters = {
  status: '',
  page: 0,
  size: 20,
};

/** 时间窗校验：开始必须早于结束。空值表示「让后端用默认窗口」。 */
export function validateCostWindow(filters: CostFilters): string[] {
  const errors: string[] = [];
  if (filters.dateFrom && filters.dateTo && filters.dateFrom >= filters.dateTo) {
    errors.push('时间窗的开始必须早于结束');
  }
  if (filters.multiplier) {
    const value = Number(filters.multiplier);
    if (!Number.isFinite(value) || value <= 0) {
      errors.push('超成本倍数必须是大于 0 的数字');
    }
  }
  return errors;
}

/** 把日期输入（YYYY-MM-DD）转成后端要的 ISO 时刻。 */
export function toIsoInstant(day: string, endOfDay = false): string | null {
  if (!day) return null;
  const suffix = endOfDay ? 'T23:59:59.999Z' : 'T00:00:00.000Z';
  const parsed = new Date(`${day}${suffix}`);
  if (Number.isNaN(parsed.getTime())) return null;
  return parsed.toISOString();
}

// ================================================================== 端点

export function fetchCostCapabilities(): Promise<CostCapabilities> {
  return api<CostCapabilities>('channel/logistics-cost-analysis/capabilities');
}

export function fetchCostOverview(filters: CostFilters): Promise<CostOverview> {
  const query = new URLSearchParams();
  const from = toIsoInstant(filters.dateFrom);
  const to = toIsoInstant(filters.dateTo, true);
  if (from) query.set('dateFrom', from);
  if (to) query.set('dateTo', to);
  return api<CostOverview>(`channel/logistics-cost-analysis/overview?${query.toString()}`);
}

export function fetchCostChannelComparison(filters: CostFilters): Promise<CostChannelComparison> {
  const query = new URLSearchParams();
  const from = toIsoInstant(filters.dateFrom);
  const to = toIsoInstant(filters.dateTo, true);
  if (from) query.set('dateFrom', from);
  if (to) query.set('dateTo', to);
  return api<CostChannelComparison>(`channel/logistics-cost-analysis/by-channel?${query.toString()}`);
}

export function fetchCostOverCostOrders(
  filters: CostFilters,
): Promise<CostOverCostOrderPage> {
  const query = new URLSearchParams();
  const from = toIsoInstant(filters.dateFrom);
  const to = toIsoInstant(filters.dateTo, true);
  if (from) query.set('dateFrom', from);
  if (to) query.set('dateTo', to);
  if (filters.multiplier) query.set('multiplier', filters.multiplier);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<CostOverCostOrderPage>(
    `channel/logistics-cost-analysis/over-cost-orders?${query.toString()}`,
  );
}

export function fetchCostAllocationRules(
  page: number,
  size: number,
  status?: string,
): Promise<CostAllocationRulePage> {
  const query = new URLSearchParams();
  query.set('page', String(page));
  query.set('size', String(size));
  if (status) query.set('status', status);
  return api<CostAllocationRulePage>(
    `channel/logistics-cost-analysis/allocation-rules?${query.toString()}`,
  );
}

export function createCostAllocationRule(
  request: CostAllocationRuleRequest,
): Promise<CostAllocationRuleRow> {
  return api<CostAllocationRuleRow>('channel/logistics-cost-analysis/allocation-rules', 'POST', request);
}

export function fetchCostAllocations(
  filters: CostAllocationFilters,
): Promise<CostAllocationPage> {
  const query = new URLSearchParams();
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  if (filters.status) query.set('status', filters.status);
  return api<CostAllocationPage>(`channel/logistics-cost-analysis/allocations?${query.toString()}`);
}

export function fetchCostAllocation(id: string): Promise<CostAllocationDetail> {
  return api<CostAllocationDetail>(`channel/logistics-cost-analysis/allocations/${id}`);
}

export function createCostAllocation(request: CostAllocationRequest): Promise<CostAllocationRow> {
  return api<CostAllocationRow>('channel/logistics-cost-analysis/allocations', 'POST', request);
}

export function lockCostAllocation(
  id: string,
  request: CostAllocationLockRequest,
): Promise<CostAllocationRow> {
  return api<CostAllocationRow>(`channel/logistics-cost-analysis/allocations/${id}/lock`, 'POST', request);
}

export function fetchCostParameters(page: number, size: number): Promise<LogisticsCostParameterPage> {
  const query = new URLSearchParams();
  query.set('page', String(page));
  query.set('size', String(size));
  return api<LogisticsCostParameterPage>(`channel/logistics-cost-analysis/parameters?${query.toString()}`);
}

export function saveCostParameter(request: LogisticsCostParameterRequest): Promise<LogisticsCostParameterRow> {
  return api<LogisticsCostParameterRow>('channel/logistics-cost-analysis/parameters', 'POST', request);
}

export function deleteCostParameter(id: string): Promise<void> {
  return api<void>(`channel/logistics-cost-analysis/parameters/${id}`, 'DELETE');
}
