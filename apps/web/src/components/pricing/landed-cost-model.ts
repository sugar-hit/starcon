import { api } from '../inventory/api';

/**
 * 5.2.1.2 到岸成本前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **到岸成本与仓库成本是两个口径**（海外仓 vs 自发货），定价时必须选对，否则自发货商品按海运成本定价会低估。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在本模块有**三个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>页面顶部固定显示口径</b>（{@link LandedCostBasis}）：算了哪六项、**明确不算哪两项**
 *       （仓储费 STORAGE_FEE 与尾程派送费实际值 LAST_MILE_DELIVERY_FEE）。
 *   <li><b>每一行分量都带口径归属</b>（{@link LandedCostComponentRow.sourceKind}）：
 *       FACT = 上游事实、PARAMETER = 租户维护的参数。参数与事实**分色**显示 ——
 *       「清关费 2.00」到底是仓库里的一笔事实，还是有人在参数表里填的一个数，
 *       不分色就答不上「这个数能不能改」。
 *   <li><b>尾程预备 ≠ 尾程派送费</b>：本模块的第六个分量是**定价预留**（LAST_MILE_RESERVE），
 *       已发生的尾程派送费归 10.2 运单与 10.6.1.1 成本分析，**不在**到岸成本里。
 * </ol>
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>「取不到」与「确实是 0」是两件事：没有关税估算 ≠ 关税是 0；参数没维护 ≠ 这笔成本不花钱。
 * 后端对这两种情况分别给了 {@code available=false} 与 {@code amount=null}，
 * 前端这里用 {@link landedMoneyText} 把它们渲染成「缺口」而不是 0.00。
 *
 * <p>渲染成 0.00 的后果是到岸成本**系统性低估**（尤其是关税），
 * 而页面上看不出任何异常 —— 这是本模块最危险的失败模式。
 *
 * <h2>🔴 每一项都要能追到来源单号</h2>
 *
 * <p>缺一项就退化成半截子报表。所以每个分量都带 {@link LandedCostComponentRow.sourceDocumentCode}
 * （头程发运单号 / 关税估算单号 / 到货单号；参数项用「分量|国|币种」作来源键），
 * 页面上必须显示它 —— 否则运营拿着一个数字去问「这个数哪来的」，没人答得上。
 */

/** 六个到岸成本分量。与后端 {@code LandedCostComponent} 逐字对应。 */
export type LandedCostComponentCode =
  | 'PURCHASE_PRICE'
  | 'INTERNATIONAL_FREIGHT'
  | 'DUTY'
  | 'CUSTOMS_CLEARANCE'
  | 'DESTINATION_HANDLING'
  | 'LAST_MILE_RESERVE';

/** 分量的来源类型。与后端 {@code ComponentCapability.kind} 逐字对应。 */
export type LandedCostSourceKind = 'FACT' | 'PARAMETER';

/** 成本口径。与后端 {@code LandedCostBasis} 逐字对应。 */
export interface LandedCostBasis {
  code: string;
  label: string;
  includedComponents: string[];
  excludedComponents: string[];
  note: string;
}

/** 能力声明里的一个分量。 */
export interface LandedCostComponentCapability {
  code: string;
  label: string;
  source: string;
  kind: string;
  available: boolean;
  ownerModule: string;
  impact: string;
  basis: string;
  note: string;
}

/** 能力声明里的一个未交付 / 取不到的依赖。 */
export interface LandedCostGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  impact: string;
  note: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface LandedCostCapabilities {
  basis: LandedCostBasis;
  components: LandedCostComponentCapability[];
  gaps: LandedCostGap[];
  allocationMethods: string[];
  upstreamNote: string;
  downstreamNote: string;
  sourceNote: string;
  actionsNote: string;
}

/** 列表一行 = 一张到岸成本单（一个批次）。 */
export interface LandedCostStatementRow {
  id: string;
  statementCode: string;
  sourceShipmentId: string;
  sourceShipmentCode: string;
  destinationCountryCode: string;
  destinationName: string;
  transportMode: string;
  incoterm: string;
  riskBearer: string;
  currency: string;
  costBasis: string;
  allocationMethod: string;
  status: string;
  totalAmount: number | null;
  totalQuantity: number;
  totalLineCount: number;
  includedComponentCount: number;
  missingComponentCount: number;
  missingComponents: string[];
  computedAt: string;
  computeCount: number;
}

/** 列表页。 */
export interface LandedCostListPage {
  items: LandedCostStatementRow[];
  total: number;
  page: number;
  size: number;
  basisNote: string;
  note: string;
}

/** 一个分量。 */
export interface LandedCostComponentRow {
  code: string;
  label: string;
  sourceKind: string;
  sourceModule: string;
  sourceDocumentCode: string;
  available: boolean;
  included: boolean;
  amount: number | null;
  unitAmount: number | null;
  taxDutyAmount: number | null;
  taxOtherAmount: number | null;
  taxBearer: string;
  ownerModule: string;
  impact: string;
  note: string;
}

/** 一个 SKU 的到岸成本行。 */
export interface LandedCostLineRow {
  id: string;
  lineNo: number;
  skuId: string;
  skuCode: string;
  skuName: string;
  baseUnit: string;
  quantity: number;
  baseQuantity: number;
  totalAmount: number | null;
  unitLandedCost: number | null;
  includedComponentCount: number;
  missingComponentCount: number;
  missingComponents: string[];
  components: LandedCostComponentRow[];
}

/** 到岸成本单明细。 */
export interface LandedCostDetail {
  id: string;
  statementCode: string;
  sourceShipmentId: string;
  sourceShipmentCode: string;
  destinationCountryCode: string;
  destinationName: string;
  transportMode: string;
  incoterm: string;
  riskBearer: string;
  currency: string;
  costBasis: string;
  allocationMethod: string;
  status: string;
  totalAmount: number | null;
  totalQuantity: number;
  totalLineCount: number;
  includedComponentCount: number;
  missingComponentCount: number;
  missingComponents: string[];
  computedAt: string;
  computeCount: number;
  basis: LandedCostBasis;
  lines: LandedCostLineRow[];
  note: string;
}

/** 目的地对比的一行 = 一个目的地。 */
export interface LandedCostComparisonRow {
  destinationCountryCode: string;
  destinationName: string;
  currency: string;
  statementCount: number;
  completeStatementCount: number;
  avgUnitLandedCost: number | null;
  minUnitLandedCost: number | null;
  maxUnitLandedCost: number | null;
  missingComponents: string[];
}

/** 目的地对比。跨币种绝不相加。 */
export interface LandedCostComparison {
  skuId: string;
  skuCode: string;
  skuName: string;
  baseUnit: string;
  rows: LandedCostComparisonRow[];
  note: string;
}

/** 租户维护的单件参数（清关费 / 尾程预备）。 */
export interface LandedCostParameterRow {
  id: string;
  componentCode: string;
  componentLabel: string;
  destinationCountryCode: string;
  currency: string;
  unitAmount: number;
  note: string;
  version: number;
  updatedAt: string;
}

/** 参数页。 */
export interface LandedCostParameterPage {
  items: LandedCostParameterRow[];
  basisNote: string;
  note: string;
}

/** 保存参数的入参。 */
export interface LandedCostParameterInput {
  componentCode: string;
  destinationCountryCode: string;
  currency: string;
  unitAmount: number;
  note: string;
  version: number;
}

/** 计算到岸成本单的入参：按上游发运单定位（id 或单号，二选一）。 */
export interface LandedCostComputeInput {
  sourceShipmentId: string | null;
  sourceShipmentCode: string | null;
}

/** 计算结果。 */
export interface LandedCostComputed {
  statementId: string;
  statementCode: string;
  created: boolean;
  computeCount: number;
  lineCount: number;
  totalAmount: number | null;
  currency: string;
  includedComponentCount: number;
  missingComponentCount: number;
  missingComponents: string[];
  note: string;
}

/** 分量页（逐项可审阅）。 */
export interface LandedCostComponentPage {
  items: LandedCostComponentRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

// ================================================================== 筛选

export interface LandedCostFilters {
  keyword: string;
  destinationCountryCode: string;
  currency: string;
  status: string;
  page: number;
  size: number;
}

export const EMPTY_LANDED_COST_FILTERS: LandedCostFilters = {
  keyword: '',
  destinationCountryCode: '',
  currency: '',
  status: '',
  page: 0,
  size: 20,
};

// ================================================================== 文案

/** 六个分量的中文名（后端已给 label，这里只做兜底）。 */
export function landedComponentLabel(code: string | null | undefined): string {
  switch (code) {
    case 'PURCHASE_PRICE':
      return '采购价';
    case 'INTERNATIONAL_FREIGHT':
      return '国际头程';
    case 'DUTY':
      return '关税';
    case 'CUSTOMS_CLEARANCE':
      return '清关';
    case 'DESTINATION_HANDLING':
      return '目的地操作费';
    case 'LAST_MILE_RESERVE':
      return '尾程预备';
    default:
      return code || '未知分量';
  }
}

/**
 * 分量来源 → 人话标签。
 *
 * <p>🔴 事实与参数必须分得开：清关费与尾程预备是**租户维护的参数**，
 * 其余四项是**上游事实**。写成同一个「成本」二字，等于让人分不清
 * 「这个数是从仓库读出来的」还是「有人填进去的」。
 */
export function landedSourceLabel(kind: string | null | undefined): string {
  switch (kind) {
    case 'FACT':
      return '上游事实';
    case 'PARAMETER':
      return '租户参数';
    default:
      return kind || '未知来源';
  }
}

/** 来源类型的色：事实 / 参数。 */
export function landedSourceTone(
  kind: string | null | undefined,
): 'success' | 'warning' | 'info' | 'neutral' {
  switch (kind) {
    case 'FACT':
      return 'success';
    case 'PARAMETER':
      return 'warning';
    default:
      return 'neutral';
  }
}

/**
 * 金额 → 展示串。
 *
 * <p>🔴 null 显示「缺口」而**不是 0.00**：
 * 后端把「该分量没有事实 / 上游不可达」表达成 {@code available=false} + {@code amount=null}，
 * 渲染成 0.00 会让到岸成本系统性低估，而页面上看不出任何异常。
 */
export function landedMoneyText(value: number | null | undefined, missing = '缺口'): string {
  if (value === null || value === undefined) return missing;
  return String(value);
}

/** 带币种的金额串。 */
export function landedMoneyWithCurrency(
  value: number | null | undefined,
  currency: string | null | undefined,
  missing = '缺口',
): string {
  const text = landedMoneyText(value, missing);
  if (text === missing) return text;
  return currency ? `${currency} ${text}` : text;
}

/** ISO 时刻 → 只到秒的串；空值显示「—」。 */
export function landedTimeText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.replace('T', ' ');
}

/**
 * 覆盖度提示。
 *
 * <p>🔴 合计**不完整**时不能只给一个数 —— 那个数是「已知部分合计」，
 * 直接拿去算利润会算高。提示语要写明缺哪几项。
 */
export function landedCompleteness(missing: string[] | null | undefined): {
  tone: 'success' | 'warning' | 'danger';
  text: string;
} {
  const list = missing ?? [];
  if (!list.length) {
    return { tone: 'success', text: '六个分量齐全，合计是完整到岸成本。' };
  }
  return {
    tone: 'danger',
    text: `🔴 合计是**已知部分合计**（缺 ${list.join('、')}）—— 不要直接拿去算利润。`,
  };
}

/** 一个分量的状态标签：计入 / 有事实但不计入 / 缺口。 */
export function landedComponentState(component: {
  available: boolean;
  included: boolean;
}): { tone: 'success' | 'warning' | 'danger'; text: string } {
  if (!component.available) return { tone: 'danger', text: '缺口' };
  if (!component.included) return { tone: 'warning', text: '不计入' };
  return { tone: 'success', text: '计入' };
}

/** 分量的一句话解释：缺口写归谁管、缺了会怎样；不计入写为什么。 */
export function landedComponentReason(component: {
  available: boolean;
  included: boolean;
  ownerModule: string;
  impact: string;
  note: string;
}): string {
  if (!component.available) {
    const owner = component.ownerModule ? `归 ${component.ownerModule} 管` : '未指派归谁管';
    const impact = component.impact ? `；缺了会${component.impact}` : '';
    return `${owner}${impact}`;
  }
  if (!component.included) {
    return component.note || '有事实但按口径不计入合计';
  }
  return component.note || '';
}

// ================================================================== 本地校验

/** 参数的本地校验。前端这道校验是为了**早点告诉人**，不是安全边界。 */
export function validateLandedCostParameter(input: LandedCostParameterInput): string[] {
  const problems: string[] = [];
  if (input.componentCode !== 'CUSTOMS_CLEARANCE' && input.componentCode !== 'LAST_MILE_RESERVE') {
    problems.push('只有清关费与尾程预备是参数；其余四项是上游事实，不许人工填');
  }
  const country = input.destinationCountryCode.trim().toUpperCase();
  if (!country) problems.push('目的国必填');
  else if (!/^[A-Z]{2}$/.test(country))
    problems.push(`目的国请用两位国家码：${input.destinationCountryCode}`);
  const currency = input.currency.trim().toUpperCase();
  if (!currency) problems.push('币种必填');
  else if (!/^[A-Z]{3}$/.test(currency))
    problems.push(`币种请用 ISO 4217 三位字母码：${input.currency}`);
  if (!Number.isFinite(input.unitAmount)) problems.push('单件金额请填数字');
  else if (input.unitAmount < 0) problems.push('单件金额不能为负');
  return problems;
}

/** 计算到岸成本单的本地校验：必须给出上游发运单的 id 或单号。 */
export function validateLandedCostCompute(input: LandedCostComputeInput): string[] {
  const problems: string[] = [];
  const hasId = typeof input.sourceShipmentId === 'string' && input.sourceShipmentId.trim() !== '';
  const hasCode = typeof input.sourceShipmentCode === 'string' && input.sourceShipmentCode.trim() !== '';
  if (!hasId && !hasCode) {
    problems.push('必须给出上游头程发运单的 id 或单号：到岸成本是按一张发运单算的');
  }
  return problems;
}

// ================================================================== 端点

export function fetchLandedCostCapabilities(): Promise<LandedCostCapabilities> {
  return api<LandedCostCapabilities>('catalog/landed-costs/capabilities');
}

export function fetchLandedCostList(filters: LandedCostFilters): Promise<LandedCostListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.destinationCountryCode)
    query.set('destinationCountryCode', filters.destinationCountryCode);
  if (filters.currency) query.set('currency', filters.currency);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<LandedCostListPage>(`catalog/landed-costs?${query.toString()}`);
}

export function fetchLandedCostDetail(statementId: string): Promise<LandedCostDetail> {
  return api<LandedCostDetail>(`catalog/landed-costs/${statementId}`);
}

export function fetchLandedCostComponents(
  statementId: string,
  page: number,
  size: number,
): Promise<LandedCostComponentPage> {
  const query = new URLSearchParams();
  query.set('page', String(page));
  query.set('size', String(size));
  return api<LandedCostComponentPage>(
    `catalog/landed-costs/${statementId}/components?${query.toString()}`,
  );
}

export function fetchLandedCostComparison(skuId: string): Promise<LandedCostComparison> {
  return api<LandedCostComparison>(`catalog/landed-costs/comparisons?skuId=${skuId}`);
}

export function fetchLandedCostParameters(): Promise<LandedCostParameterPage> {
  return api<LandedCostParameterPage>('catalog/landed-costs/parameters');
}

export function saveLandedCostParameter(input: LandedCostParameterInput): Promise<LandedCostParameterRow> {
  return api<LandedCostParameterRow>('catalog/landed-costs/parameters', 'PUT', input);
}

/** 计算一张到岸成本单（按上游头程发运单）。幂等：重复提交是重算同一张单。 */
export function computeLandedCost(input: LandedCostComputeInput): Promise<LandedCostComputed> {
  return api<LandedCostComputed>('catalog/landed-costs', 'POST', input);
}

/** 重算一张到岸成本单（按它自己的来源发运单重新取数）。 */
export function recomputeLandedCost(statementId: string): Promise<LandedCostComputed> {
  return api<LandedCostComputed>(`catalog/landed-costs/${statementId}/recompute`, 'POST');
}
