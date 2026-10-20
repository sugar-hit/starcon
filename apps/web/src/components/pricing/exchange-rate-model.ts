import { api } from '../inventory/api';

/**
 * 5.2.2.1 汇率查询前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **汇率方向必须显式**（1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源）。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在页面上有**两个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>任何一处显示汇率都带方向</b>（{@link rateText} / {@link rateDetailText}）：
 *       显示的是「1 USD = 7.1234 CNY」这样的整句，**不是**裸的 7.1234。
 *       裸数字加上「汇率：7.1234」这种文案，正是 1 USD 与 1 CNY 混淆的来源。
 *   <li><b>反向汇率标注为推导值</b>（{@link inverseText}）：由正向推导出来的反向值
 *       必须写明「由 … 推导」，不能让人以为它是另一条独立事实。
 * </ol>
 *
 * <p><b>🔴 汇率不是金额</b>：它没有「取不到就显示 0」的余地 ——
 * 缺一条汇率就是缺一条汇率（{@link rateText} 对 null 显示「未登记」），
 * 显示 0.0000 会让「1 USD = 0 CNY」这种话被当成事实记下来。
 */

/** 汇率来源类型。与后端 {@code SourceKind} 逐字对应。 */
export type SourceKind = 'MANUAL' | 'EXTERNAL';

/** 录入方式。与后端 {@code EntryMode} 逐字对应。 */
export type EntryMode = 'MANUAL' | 'EXTERNAL';

/** 一个汇率来源（租户级配置）。 */
export interface RateSource {
  id: string;
  code: string;
  name: string;
  kind: string;
  kindLabel: string;
  kindBasis: string;
  enabled: boolean;
  priority: number;
  endpoint: string | null;
  note: string;
  rateCount: number;
  version: number;
  createdAt: string | null;
  updatedAt: string | null;
}

/**
 * 一条汇率记录。
 *
 * <p>{@code display} 是**整句**（后端已经拼好方向）；{@code inverseDisplay} 是**由正向推导**的
 * 反向汇率，带 {@code inverseDerived} 标注。页面直接用这两句，不自己拼数字。
 */
export interface RateRow {
  id: string;
  baseCurrency: string;
  quoteCurrency: string;
  rate: string;
  effectiveDate: string;
  sourceCode: string;
  sourceName: string | null;
  sourceKind: string | null;
  entryMode: string;
  entryModeLabel: string;
  sourceRef: string | null;
  note: string | null;
  acquiredAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  display: string;
  inverseRate: string | null;
  inverseDisplay: string;
  inverseDerived: boolean;
}

/** 台账分页结果。 */
export interface RateListPage {
  items: RateRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface RateCapabilities {
  sources: RateSource[];
  directionRule: string;
  uniquenessRule: string;
  inverseRule: string;
  entryModes: string[];
  downstreamNote: string;
  ownershipNote: string;
  functionalCurrencyNote: string;
  fetchContractNote: string;
}

/** 手工录入表单。 */
export interface RateInput {
  baseCurrency: string;
  quoteCurrency: string;
  rate: string;
  effectiveDate: string;
  sourceCode: string;
  sourceRef: string;
  note: string;
}

/** 台账筛选条件。 */
export interface RateFilters {
  baseCurrency: string;
  quoteCurrency: string;
  sourceCode: string;
  dateFrom: string;
  dateTo: string;
  keyword: string;
  page: number;
  size: number;
}

/** 空的筛选条件。 */
export const EMPTY_RATE_FILTERS: RateFilters = {
  baseCurrency: '',
  quoteCurrency: '',
  sourceCode: '',
  dateFrom: '',
  dateTo: '',
  keyword: '',
  page: 0,
  size: 20,
};

/** 外部拉取结果。失败时 status=FAILED 且 errorMessage 有话。 */
export interface FetchResult {
  status: string;
  sourceCode: string;
  httpStatus: number | null;
  rowsFetched: number;
  rowsSaved: number;
  errorMessage: string;
  requestedAt: string | null;
  rates: RateRow[];
  failureStatus: number | null;
}

/** 拉取日志一行。 */
export interface FetchLogRow {
  id: string;
  sourceCode: string;
  baseCurrency: string | null;
  quoteCurrency: string | null;
  requestedDate: string | null;
  status: string;
  httpStatus: number | null;
  rowsFetched: number;
  rowsSaved: number;
  errorMessage: string;
  requestedAt: string | null;
}

/** 一个法人的本位币面板行。 */
export interface FunctionalCurrencyRow {
  legalEntityId: string;
  legalEntityCode: string;
  legalEntityName: string;
  baseCurrency: string;
  rates: RateRow[];
}

/** 本位币面板。 */
export interface FunctionalCurrencyPanel {
  rows: FunctionalCurrencyRow[];
  note: string;
}

// ================================================================== 展示

/**
 * 汇率的整句显示。
 *
 * <p>优先用后端给的 {@code display}（后端已经保证「1 基准 = X 报价」的方向）；
 * 万一后端没给（例如旧数据），这里**仍然**按 base/quote 拼出带方向的整句 ——
 * 任何情况下都不返回裸数字。
 */
export function rateText(row: Partial<RateRow> | null | undefined): string {
  if (!row) return '未登记';
  if (row.display) return row.display;
  if (row.baseCurrency && row.quoteCurrency && row.rate) {
    return `1 ${row.baseCurrency} = ${row.rate} ${row.quoteCurrency}`;
  }
  return '未登记';
}

/**
 * 汇率的**完整**显示：正向 + 方向说明 + 反向推导值。
 *
 * <p>页面上「今天该用哪个汇率」这类地方用它 —— 反向值带着「由 … 推导」的标注。
 */
export function rateDetailText(row: Partial<RateRow> | null | undefined): string {
  if (!row) return '未登记';
  const forward = rateText(row);
  const inverse = row.inverseDisplay
    ? row.inverseDisplay
    : row.baseCurrency && row.quoteCurrency && row.rate
      ? `1 ${row.quoteCurrency} = 1/${row.rate} ${row.baseCurrency}（由 ${forward} 推导）`
      : '';
  return inverse ? `${forward}｜${inverse}` : forward;
}

/** 反向汇率的显示（标注为推导值）。 */
export function inverseText(row: Partial<RateRow> | null | undefined): string {
  if (!row) return '未登记';
  return row.inverseDisplay || '未登记';
}

/** 生效日 → 只到日的串。 */
export function dayText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.slice(0, 10);
}

/** ISO 时刻 → 只到秒的串。 */
export function timeText(value: string | null | undefined, empty = '未登记'): string {
  if (!value) return empty;
  return value.replace('T', ' ').replace('Z', 'Z');
}

/** 来源类型的中文名。 */
export function sourceKindLabel(kind: string | null | undefined): string {
  switch (kind) {
    case 'MANUAL':
      return '手工录入';
    case 'EXTERNAL':
      return '外部拉取';
    default:
      return kind || '未登记';
  }
}

/** 录入方式的中文名。 */
export function entryModeLabel(mode: string | null | undefined): string {
  switch (mode) {
    case 'MANUAL':
      return '手工录入';
    case 'EXTERNAL':
      return '外部拉取';
    default:
      return mode || '未登记';
  }
}

/** 录入方式的色标。 */
export function entryModeTone(mode: string | null | undefined): 'success' | 'warning' | 'info' | 'neutral' {
  switch (mode) {
    case 'MANUAL':
      return 'info';
    case 'EXTERNAL':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 拉取结果的状态文案。 */
export function fetchStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '失败';
    default:
      return status || '未知';
  }
}

/**
 * 拉取结果的色标。
 *
 * <p>取值域与 {@code Notice} / {@code Badge} 的 tone 一致（info / success / warning / danger），
 * 避免页面里出现一种组件不认识的色。
 */
export function fetchStatusTone(status: string | null | undefined): 'success' | 'danger' | 'info' {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    default:
      return 'info';
  }
}

// ================================================================== 本地校验

/**
 * 手工录入的本地校验。
 *
 * <p>前端这道校验是为了**早点告诉人**，不是安全边界 ——
 * 真正的约束在数据库（唯一索引 + 两个守卫触发器）与服务层。
 */
export function validateRateInput(input: RateInput): string[] {
  const problems: string[] = [];
  const base = input.baseCurrency.trim().toUpperCase();
  const quote = input.quoteCurrency.trim().toUpperCase();
  if (!base) problems.push('基准币种必填：汇率方向必须显式，不能只填一个数');
  else if (!/^[A-Z]{3}$/.test(base)) problems.push(`基准币种请用 ISO 4217 三位字母码：${base}`);
  if (!quote) problems.push('报价币种必填：汇率方向必须显式，不能只填一个数');
  else if (!/^[A-Z]{3}$/.test(quote)) problems.push(`报价币种请用 ISO 4217 三位字母码：${quote}`);
  if (base && quote && base === quote) problems.push('本币对本币不需要汇率');
  if (!input.rate.trim()) problems.push('汇率必填');
  else {
    const value = Number(input.rate);
    if (!Number.isFinite(value)) problems.push(`汇率不是数字：${input.rate}`);
    else if (value <= 0) problems.push('汇率必须大于 0');
    else if ((input.rate.trim().split('.')[1]?.length ?? 0) > 8)
      problems.push('汇率最多保留 8 位小数（数据库列是 numeric(19,8)）');
  }
  if (!input.effectiveDate.trim()) problems.push('生效日期必填：这一条汇率从哪一天开始算数');
  if (!input.sourceCode.trim()) problems.push('汇率来源必填：先在「汇率来源配置」里登记来源');
  return problems;
}

/** 校验来源配置。 */
export function validateSourceInput(input: {
  code: string;
  name: string;
  kind: string;
  endpoint: string;
}): string[] {
  const problems: string[] = [];
  if (!input.code.trim()) problems.push('来源代码必填');
  else if (!/^[A-Za-z0-9_]{2,32}$/.test(input.code.trim()))
    problems.push('来源代码请用 2~32 位字母、数字或下划线');
  if (!input.name.trim()) problems.push('来源名称必填');
  if (input.kind !== 'MANUAL' && input.kind !== 'EXTERNAL') problems.push('来源类型只能是手工录入或外部拉取');
  if (input.kind === 'MANUAL' && input.endpoint.trim())
    problems.push('手工录入来源不应配地址：留了就是「配了却用不上」的死配置');
  return problems;
}

// ================================================================== 分区

/**
 * 5.2 的汇率分区 —— 两个兄弟模块共用 L3 路由 {@code /pricing/rates}。
 *
 * <p>未交付的分区**显式标成未交付**，不画空白框冒充已交付。
 */
export const RATE_SECTIONS: readonly {
  id: string;
  title: string;
  description: string;
  delivered: boolean;
}[] = [
  {
    id: '5.2.2.1',
    title: '汇率查询',
    description: '各币种对各本位币的汇率：方向显式、生效日与来源可见，支持手工录入与外部拉取。',
    delivered: true,
  },
  {
    id: '5.2.2.2',
    title: '汇率快照',
    description:
      '在单据/定价生效时冻结所用汇率（原价、汇率、舍入方式、目标币金额），保证历史利润可复算。' +
      '**尚未交付**：它的上游是本模块，当前本模块只产出汇率事实，不假装快照已存在。',
    delivered: false,
  },
];

// ================================================================== 接口

/** 能力声明。 */
export function fetchRateCapabilities(): Promise<RateCapabilities> {
  return api<RateCapabilities>('catalog/exchange-rates/capabilities');
}

/** 汇率台账。 */
export function fetchRateList(filters: RateFilters): Promise<RateListPage> {
  const query = new URLSearchParams();
  if (filters.baseCurrency.trim()) query.set('baseCurrency', filters.baseCurrency.trim());
  if (filters.quoteCurrency.trim()) query.set('quoteCurrency', filters.quoteCurrency.trim());
  if (filters.sourceCode.trim()) query.set('sourceCode', filters.sourceCode.trim());
  if (filters.dateFrom) query.set('dateFrom', filters.dateFrom);
  if (filters.dateTo) query.set('dateTo', filters.dateTo);
  if (filters.keyword.trim()) query.set('keyword', filters.keyword.trim());
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<RateListPage>(`catalog/exchange-rates?${query.toString()}`);
}

/** 某个币种对最新的一条汇率。 */
export function fetchLatestRate(
  baseCurrency: string,
  quoteCurrency: string,
  asOf: string | null,
): Promise<RateRow> {
  const query = new URLSearchParams();
  query.set('baseCurrency', baseCurrency);
  query.set('quoteCurrency', quoteCurrency);
  if (asOf) query.set('asOf', asOf);
  return api<RateRow>(`catalog/exchange-rates/latest?${query.toString()}`);
}

/** 本位币面板（各币种对各本位币的汇率）。 */
export function fetchFunctionalCurrencies(): Promise<FunctionalCurrencyPanel> {
  return api<FunctionalCurrencyPanel>('catalog/exchange-rates/functional-currencies');
}

/** 汇率来源配置列表。 */
export function fetchRateSources(): Promise<RateSource[]> {
  return api<RateSource[]>('catalog/exchange-rates/sources');
}

/** 初始化内置来源（中行 / 央行 / 平台结算 / 手工录入），幂等。 */
export function initializeRateSources(): Promise<RateSource[]> {
  return api<RateSource[]>('catalog/exchange-rates/sources/initialize', 'POST');
}

/** 新建汇率来源。 */
export function createRateSource(input: {
  code: string;
  name: string;
  kind: string;
  enabled: boolean;
  priority: number;
  endpoint: string | null;
  note: string;
}): Promise<RateSource> {
  return api<RateSource>('catalog/exchange-rates/sources', 'POST', input);
}

/** 修改汇率来源。 */
export function updateRateSource(
  code: string,
  input: {
    name: string;
    kind: string;
    enabled: boolean;
    priority: number;
    endpoint: string | null;
    note: string;
    version: number;
  },
): Promise<RateSource> {
  return api<RateSource>(`catalog/exchange-rates/sources/${encodeURIComponent(code)}`, 'PUT', input);
}

/** 手工录入一条汇率。 */
export function createRate(input: RateInput): Promise<RateRow> {
  return api<RateRow>('catalog/exchange-rates', 'POST', {
    baseCurrency: input.baseCurrency.trim().toUpperCase(),
    quoteCurrency: input.quoteCurrency.trim().toUpperCase(),
    rate: input.rate.trim(),
    effectiveDate: input.effectiveDate,
    sourceCode: input.sourceCode.trim().toUpperCase(),
    sourceRef: input.sourceRef,
    note: input.note,
  });
}

/** 更正一条已登记的汇率（同一条，不新增第二条）。 */
export function updateRate(id: string, input: RateInput): Promise<RateRow> {
  return api<RateRow>(`catalog/exchange-rates/${id}`, 'PUT', {
    baseCurrency: input.baseCurrency.trim().toUpperCase(),
    quoteCurrency: input.quoteCurrency.trim().toUpperCase(),
    rate: input.rate.trim(),
    effectiveDate: input.effectiveDate,
    sourceCode: input.sourceCode.trim().toUpperCase(),
    sourceRef: input.sourceRef,
    note: input.note,
  });
}

/**
 * 外部拉取。
 *
 * <p>失败时后端返回 503 / 400 的**响应体**（而不是抛错），所以这里把响应体一起透出：
 * {@link ApiError} 的 body 里带着失败原因。
 */
export async function fetchExternalRate(input: {
  sourceCode: string;
  baseCurrency: string;
  quoteCurrency: string;
  effectiveDate: string;
}): Promise<FetchResult> {
  return api<FetchResult>('catalog/exchange-rates/fetch', 'POST', {
    sourceCode: input.sourceCode.trim().toUpperCase(),
    baseCurrency: input.baseCurrency.trim().toUpperCase(),
    quoteCurrency: input.quoteCurrency.trim().toUpperCase(),
    effectiveDate: input.effectiveDate,
  });
}

/** 外部拉取日志。 */
export function fetchRateLogs(page = 0, size = 20): Promise<FetchLogRow[]> {
  return api<FetchLogRow[]>(`catalog/exchange-rates/fetch-log?page=${page}&size=${size}`);
}
