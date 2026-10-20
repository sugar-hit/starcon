import { api } from '../inventory/api';

/**
 * 5.2.2.2 汇率快照前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **汇率快照是利润可追溯的前提**：今天汇率变了不能让上个月的利润报表数字跟着变。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在页面上有**三个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>换算过程整句显示</b>（{@link snapshotConversionText}）：
 *       显示的是「100.0 USD × 7.1234 = 712.34 CNY（HALF_UP · 2 位小数）」这样的整句，
 *       **不是**裸数字。裸数字加上「汇率：7.12」这种文案，正是 1 USD 与 1 CNY 混淆的来源。
 *   <li><b>汇率整句显示</b>（{@link snapshotRateText}）：冻结的那条汇率带完整方向。
 *   <li><b>复算结论显性</b>（{@link replayTone}）：复算一致 / 不一致要分得开，
 *       界面上不能只给一个「已完成」。
 * </ol>
 *
 * <p><b>🔴 快照是只追加的</b>：界面上没有「编辑」「删除」入口 ——
 * 要更正就换单据行标识另冻一条，原快照原样保留。
 */

/** 换算方向。与后端 {@code ConversionDirection} 逐字对应。 */
export type ConversionDirection = 'BASE_TO_QUOTE' | 'QUOTE_TO_BASE';

/** 舍入方式。与后端 {@code RoundingModeOption} 逐字对应。 */
export type RoundingModeName = 'HALF_UP' | 'HALF_DOWN' | 'DOWN' | 'UP' | 'CEILING' | 'FLOOR';

/** 单据类型。与后端 {@code DocumentType} 逐字对应。 */
export type DocumentTypeName =
  | 'PRICE_LIST'
  | 'PRICE_LIST_ITEM'
  | 'PRICING_TEMPLATE'
  | 'PROFIT_CALCULATION'
  | 'ORDER'
  | 'SETTLEMENT';

/** 一条舍入规则（租户级配置）。 */
export interface RoundingRule {
  id: string;
  code: string;
  name: string;
  /** 适用目标币种；null = 通用兜底规则。 */
  targetCurrency: string | null;
  roundingMode: string;
  roundingModeLabel: string;
  roundingModeBasis: string;
  roundingScale: number;
  enabled: boolean;
  priority: number;
  note: string;
  version: number;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 一条冻结的汇率快照。 */
export interface SnapshotRow {
  id: string;
  documentType: string;
  documentTypeLabel: string;
  documentId: string;
  documentNo: string;
  lineKey: string;
  sourceCurrency: string;
  sourceAmount: number;
  targetCurrency: string;
  targetAmount: number;
  /** 「1 USD = 7.1234 CNY」—— 冻结的那条汇率，带完整方向。 */
  rateDisplay: string;
  rateId: string;
  rateBaseCurrency: string;
  rateQuoteCurrency: string;
  rate: number;
  conversionDirection: string;
  conversionDirectionLabel: string;
  conversionDirectionBasis: string;
  rateEffectiveDate: string;
  rateSourceCode: string;
  roundingRuleCode: string;
  roundingMode: string;
  roundingModeLabel: string;
  roundingScale: number;
  /** 「100.0 USD × 7.1234 = 712.34 CNY（HALF_UP · 2 位小数）」—— 整条换算过程。 */
  conversionDisplay: string;
  frozenAt: string | null;
  createdAt: string | null;
  note: string | null;
}

/** 快照台账分页结果。 */
export interface SnapshotListPage {
  items: SnapshotRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 台账筛选条件。 */
export interface SnapshotFilters {
  documentType: string;
  documentId: string;
  sourceCurrency: string;
  targetCurrency: string;
  dateFrom: string;
  dateTo: string;
  keyword: string;
  page: number;
  size: number;
}

/** 空的筛选条件。 */
export const EMPTY_SNAPSHOT_FILTERS: SnapshotFilters = {
  documentType: '',
  documentId: '',
  sourceCurrency: '',
  targetCurrency: '',
  dateFrom: '',
  dateTo: '',
  keyword: '',
  page: 0,
  size: 20,
};

/** 能力声明里的一条单据类型说明。 */
export interface DocumentTypeOption {
  value: string;
  label: string;
  basis: string;
}

/** 能力声明里的一条舍入方式说明。 */
export interface RoundingModeInfo {
  value: string;
  label: string;
  basis: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface SnapshotCapabilities {
  documentTypes: DocumentTypeOption[];
  roundingModes: RoundingModeInfo[];
  roundingRules: RoundingRule[];
  directionRule: string;
  immutabilityRule: string;
  roundingRule: string;
  upstreamNote: string;
  downstreamNote: string;
  ownershipNote: string;
  replayContractNote: string;
}

/** 冻结一条快照的表单。 */
export interface SnapshotInput {
  /** 直接指定要冻结的汇率记录（uuid）。留空则按币种对 + asOf 取最新一条。 */
  rateId: string;
  baseCurrency: string;
  quoteCurrency: string;
  asOf: string;
  sourceCurrency: string;
  sourceAmount: string;
  documentType: string;
  documentId: string;
  documentNo: string;
  lineKey: string;
  roundingRuleCode: string;
  note: string;
}

/** 复算请求：复算一条快照，或复算某张单据上的全部快照。 */
export interface ReplayRequest {
  snapshotId: string | null;
  documentType: string | null;
  documentId: string | null;
}

/** 一条复算结果。 */
export interface SnapshotReplayRow {
  snapshotId: string;
  documentType: string;
  documentId: string;
  documentNo: string;
  lineKey: string;
  sourceCurrency: string;
  sourceAmount: number;
  targetCurrency: string;
  targetAmount: number;
  recomputedTargetAmount: number;
  matched: boolean;
  difference: number;
  rateDisplay: string;
  conversionDirection: string;
  roundingMode: string;
  roundingModeLabel: string;
  roundingScale: number;
  roundingRuleCode: string;
  rateEffectiveDate: string;
  rateSourceCode: string;
  note: string;
}

/** 一次复算的结论。 */
export interface SnapshotReplayReport {
  rows: SnapshotReplayRow[];
  total: number;
  matched: number;
  mismatched: number;
  note: string;
}

// ================================================================== 展示

/**
 * 冻结汇率的整句显示。
 *
 * <p>优先用后端给的 {@code rateDisplay}（后端已经保证「1 基准 = X 报价」的方向）；
 * 万一后端没给，这里**仍然**按 base/quote 拼出带方向的整句 ——
 * 任何情况下都不返回裸数字。
 */
export function snapshotRateText(row: Partial<SnapshotRow> | null | undefined): string {
  if (!row) return '未冻结';
  if (row.rateDisplay) return row.rateDisplay;
  if (row.rateBaseCurrency && row.rateQuoteCurrency && row.rate !== undefined) {
    return `1 ${row.rateBaseCurrency} = ${row.rate} ${row.rateQuoteCurrency}`;
  }
  return '未冻结';
}

/**
 * 换算过程的整句显示。
 *
 * <p>优先用后端给的 {@code conversionDisplay}（原价、汇率、舍入方式、目标币金额四件都在句子里）；
 * 后端没给时按本地字段拼 —— 但**仍然**不出现裸数字。
 */
export function snapshotConversionText(row: Partial<SnapshotRow> | null | undefined): string {
  if (!row) return '—';
  if (row.conversionDisplay) return row.conversionDisplay;
  const operator = row.conversionDirection === 'QUOTE_TO_BASE' ? '÷' : '×';
  if (!row.sourceCurrency || !row.targetCurrency) return '—';
  return `${amountText(row.sourceAmount)} ${row.sourceCurrency} ${operator} ${row.rate} = ${amountText(
    row.targetAmount,
  )} ${row.targetCurrency}`;
}

/**
 * 金额的显示串。
 *
 * <p>后端给的是 JSON number（BigDecimal 序列化），这里只做展示格式化：
 * 去掉小数尾零、保留至多 6 位（金额列是 numeric(19,6)）。
 */
export function amountText(value: number | string | null | undefined, empty = '—'): string {
  if (value === null || value === undefined || value === '') return empty;
  if (typeof value === 'string') {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return value;
    return amountText(parsed, empty);
  }
  if (!Number.isFinite(value)) return empty;
  const fixed = value.toFixed(6);
  const stripped = fixed.replace(/0+$/, '').replace(/\.$/, '');
  return stripped === '' || stripped === '-' ? '0' : stripped;
}

/** 单据类型的中文名（后端已给 label，这里兜底一份）。 */
export function documentTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'PRICE_LIST':
      return '价目表';
    case 'PRICE_LIST_ITEM':
      return '价目项';
    case 'PRICING_TEMPLATE':
      return '定价模板';
    case 'PROFIT_CALCULATION':
      return '利润测算';
    case 'ORDER':
      return '订单';
    case 'SETTLEMENT':
      return '结算';
    default:
      return type || '未知单据';
  }
}

/** 换算方向的中文名。 */
export function directionLabel(direction: string | null | undefined): string {
  switch (direction) {
    case 'BASE_TO_QUOTE':
      return '基准币→报价币（乘）';
    case 'QUOTE_TO_BASE':
      return '报价币→基准币（除）';
    default:
      return direction || '未记录';
  }
}

/** 换算方向的色标。 */
export function directionTone(direction: string | null | undefined): 'success' | 'info' | 'neutral' {
  switch (direction) {
    case 'BASE_TO_QUOTE':
      return 'success';
    case 'QUOTE_TO_BASE':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 舍入方式的中文名。 */
export function roundingModeLabel(mode: string | null | undefined): string {
  switch (mode) {
    case 'HALF_UP':
      return '四舍五入';
    case 'HALF_DOWN':
      return '五舍六入';
    case 'DOWN':
      return '截断';
    case 'UP':
      return '进位';
    case 'CEILING':
      return '向上取整';
    case 'FLOOR':
      return '向下取整';
    default:
      return mode || '未记录';
  }
}

/** 复算结论的色标：一致 / 不一致要一眼分得开。 */
export function replayTone(matched: boolean): 'success' | 'danger' {
  return matched ? 'success' : 'danger';
}

/** 生效日 → 只到日的串。 */
export function dayText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.slice(0, 10);
}

/** ISO 时刻 → 只到秒的串。 */
export function timeText(value: string | null | undefined, empty = '未记录'): string {
  if (!value) return empty;
  return value.replace('T', ' ').replace('Z', 'Z');
}

// ================================================================== 本地校验

/**
 * 冻结快照的本地校验。
 *
 * <p>前端这道校验是为了**早点告诉人**，不是安全边界 ——
 * 真正的约束在数据库（CHECK / UNIQUE / 触发器）与服务层。
 */
export function validateSnapshotInput(input: SnapshotInput): string[] {
  const problems: string[] = [];
  if (!input.rateId.trim() && !(input.baseCurrency.trim() && input.quoteCurrency.trim())) {
    problems.push('必须指明要冻结的汇率：填 rateId，或填基准币种 + 报价币种（按生效日取最新一条）');
  }
  if (!input.sourceCurrency.trim()) problems.push('原价币种必填：换算方向必须显式');
  else if (!/^[A-Za-z]{3}$/.test(input.sourceCurrency.trim()))
    problems.push(`原价币种请用 ISO 4217 三位字母码：${input.sourceCurrency.trim()}`);
  if (!input.sourceAmount.trim()) problems.push('原价必填');
  else {
    const value = Number(input.sourceAmount.trim());
    if (!Number.isFinite(value)) problems.push(`原价不是数字：${input.sourceAmount.trim()}`);
    else if ((input.sourceAmount.trim().split('.')[1]?.length ?? 0) > 6)
      problems.push('原价最多保留 6 位小数（数据库列是 numeric(19,6)）');
  }
  if (!input.documentType.trim()) problems.push('单据类型必填：快照必须挂在某张单据上');
  if (!input.documentId.trim()) problems.push('单据 id 必填：快照必须挂在某张单据上');
  return problems;
}

/** 舍入规则配置的本地校验。 */
export function validateRoundingRuleInput(input: {
  code: string;
  name: string;
  targetCurrency: string;
  roundingMode: string;
  roundingScale: number;
}): string[] {
  const problems: string[] = [];
  if (!input.code.trim()) problems.push('规则代码必填');
  else if (!/^[A-Za-z0-9_]{2,32}$/.test(input.code.trim()))
    problems.push('规则代码请用 2~32 位字母、数字或下划线');
  if (!input.name.trim()) problems.push('规则名称必填');
  if (input.targetCurrency.trim() && !/^[A-Za-z]{3}$/.test(input.targetCurrency.trim()))
    problems.push(`适用币种请用三位大写币种代码；通用规则请留空：${input.targetCurrency.trim()}`);
  if (!input.roundingMode.trim()) problems.push('舍入方式必填');
  if (!Number.isInteger(input.roundingScale) || input.roundingScale < 0 || input.roundingScale > 6)
    problems.push('小数位数必须在 0~6 之间（金额列是 numeric(19,6)）');
  return problems;
}

// ================================================================== 接口

/** 能力声明。 */
export function fetchSnapshotCapabilities(): Promise<SnapshotCapabilities> {
  return api<SnapshotCapabilities>('catalog/exchange-rate-snapshots/capabilities');
}

/** 快照台账。 */
export function fetchSnapshotList(filters: SnapshotFilters): Promise<SnapshotListPage> {
  const query = new URLSearchParams();
  if (filters.documentType.trim()) query.set('documentType', filters.documentType.trim());
  if (filters.documentId.trim()) query.set('documentId', filters.documentId.trim());
  if (filters.sourceCurrency.trim()) query.set('sourceCurrency', filters.sourceCurrency.trim());
  if (filters.targetCurrency.trim()) query.set('targetCurrency', filters.targetCurrency.trim());
  if (filters.dateFrom) query.set('dateFrom', filters.dateFrom);
  if (filters.dateTo) query.set('dateTo', filters.dateTo);
  if (filters.keyword.trim()) query.set('keyword', filters.keyword.trim());
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<SnapshotListPage>(`catalog/exchange-rate-snapshots?${query.toString()}`);
}

/** 冻结一条汇率快照。 */
export function freezeSnapshot(input: SnapshotInput): Promise<SnapshotRow> {
  return api<SnapshotRow>('catalog/exchange-rate-snapshots', 'POST', {
    rateId: input.rateId.trim() || null,
    baseCurrency: input.baseCurrency.trim().toUpperCase() || null,
    quoteCurrency: input.quoteCurrency.trim().toUpperCase() || null,
    asOf: input.asOf || null,
    sourceCurrency: input.sourceCurrency.trim().toUpperCase(),
    sourceAmount: input.sourceAmount.trim(),
    documentType: input.documentType.trim().toUpperCase(),
    documentId: input.documentId.trim(),
    documentNo: input.documentNo.trim(),
    lineKey: input.lineKey.trim(),
    roundingRuleCode: input.roundingRuleCode.trim().toUpperCase() || null,
    note: input.note.trim(),
  });
}

/** 复算：拿快照里冻结的原价、汇率、方向、舍入方式重新算一遍。 */
export function replaySnapshots(request: ReplayRequest): Promise<SnapshotReplayReport> {
  return api<SnapshotReplayReport>('catalog/exchange-rate-snapshots/replay', 'POST', {
    snapshotId: request.snapshotId,
    documentType: request.documentType,
    documentId: request.documentId,
  });
}

/** 舍入规则配置列表。 */
export function fetchRoundingRules(): Promise<RoundingRule[]> {
  return api<RoundingRule[]>('catalog/exchange-rate-snapshots/rounding-rules');
}

/** 初始化内置舍入规则（通用两位 + 常见币种 + 日元/韩元零位），幂等。 */
export function initializeRoundingRules(): Promise<RoundingRule[]> {
  return api<RoundingRule[]>('catalog/exchange-rate-snapshots/rounding-rules/initialize', 'POST');
}

/** 新建舍入规则。 */
export function createRoundingRule(input: {
  code: string;
  name: string;
  targetCurrency: string | null;
  roundingMode: string;
  roundingScale: number;
  enabled: boolean;
  priority: number;
  note: string;
}): Promise<RoundingRule> {
  return api<RoundingRule>('catalog/exchange-rate-snapshots/rounding-rules', 'POST', {
    code: input.code.trim().toUpperCase(),
    name: input.name.trim(),
    targetCurrency: input.targetCurrency?.trim().toUpperCase() || null,
    roundingMode: input.roundingMode.trim().toUpperCase(),
    roundingScale: input.roundingScale,
    enabled: input.enabled,
    priority: input.priority,
    note: input.note.trim(),
  });
}
