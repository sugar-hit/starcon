import { api } from '../inventory/api';
import type { SiteOption } from './profit-calculator-model';

/** 目标店铺（站点）选项：与 5.3.1.1 共用同一份形状（同一个平台目录口径）。 */
export type { SiteOption };

/**
 * 5.3.1.2 批量测算前端模型。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：对一批 SKU 批量测算，按当前成本与规则输出利润表，标记亏损品与低毛利品，支持导出并筛选（毛利率 &lt; X%、售价 &lt; Y）。
 *
 * **输入**：SKU 范围（按分类/品牌/店铺）；测算参数。
 *
 * **输出**：批量测算结果表 + 异常标记。
 *
 * **关系**：上游 = 5.3.1.1；下游 = 5.4.1.2。**批量测算是选品与清库存决策的依据**：亏损品要么涨价要么清仓，人工逐个测算不可行。
 *
 * </blockquote>
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>三种「没有」在这一页同时出现，**它们是三件事**：
 *
 * <ol>
 *   <li><b>跳过（无价格观测）</b>：这个 SKU 在这个店铺没有售价 ⇒ 压根没测。
 *       渲染成 0.00 会被当成「卖 0 元的亏损品」，直接混进清库存名单。</li>
 *   <li><b>成本不完整</b>：测了，但缺项 ⇒ 毛利额与毛利率是 null。
 *       渲染成 0 会被当成「毛利刚好 0」—— 而缺项算出来的毛利率是<b>系统性虚高</b>的。</li>
 *   <li><b>真的是 0</b>：毛利额恰好是 0 ⇒ 正常的边界值。</li>
 * </ol>
 *
 * <p>⇒ 后端用三种不同状态表达（{@code status} / {@code decisionReady} / {@code amount === null}），
 * 前端用 {@link moneyText} / {@link percentText} / {@link rowTone} 把它们渲染成**看得见**的三种样子，
 * 绝不让它们在页面上长得一样。
 *
 * <h2>🔴 两个异常标记互斥</h2>
 *
 * <p>亏损品（毛利额 &lt; 0）与低毛利品（还没亏，但毛利率低于阈值）在数值上本来重叠。
 * 后端把它们判成互斥的，前端也**不能**把它们渲染成两个可以同时亮的徽章 ——
 * 「问题品总数」是运营拿去决定清库存范围的那个数，重复计数就等于多清一批货。
 */

/** 结果行状态。与后端 {@code ResultStatus} 逐字对应。 */
export type BatchResultStatus = 'CALCULATED' | 'SKIPPED_NO_PRICE';

/** 异常标记。与后端 {@code Flag} 逐字对应。 */
export type BatchFlag = 'LOSS' | 'LOW_MARGIN';

/** 结果排序。与后端 {@code Sort} 逐字对应。 */
export type BatchSort = 'MARGIN_ASC' | 'MARGIN_DESC' | 'PRICE_ASC' | 'PRICE_DESC';

/** 批量测算请求。 */
export interface BatchCalculationRequest {
  platformCode: string;
  siteCode: string;
  currency: string;
  costCurrency: string;
  category: string;
  brand: string;
  quantity: number | null;
  fxRate: number | null;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  monthlyFixedCost: number | null;
  lowMarginThreshold: number | null;
  costWindowFrom: string | null;
  costWindowTo: string | null;
  note: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface BatchCalculationCapabilities {
  sites: SiteOption[];
  priceSourceNote: string;
  upstreamNote: string;
  downstreamNote: string;
  decisionGateNote: string;
  scopeNote: string;
  flagNote: string;
  filterNote: string;
  exportNote: string;
  skipNote: string;
  limitNote: string;
  precisionNote: string;
}

/** 批量测算结果的一行。 */
export interface BatchResultRow {
  id: string;
  runId: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  category: string | null;
  brand: string | null;
  status: BatchResultStatus;
  skipReason: string;
  unitPrice: number | null;
  priceSource: string | null;
  priceObservedAt: string | null;
  currency: string | null;
  knownCostTotal: number | null;
  platformFeeAmount: number | null;
  logisticsAmount: number | null;
  totalDeductions: number | null;
  grossProfit: number | null;
  grossMarginRate: number | null;
  breakEvenPrice: number | null;
  decisionBasis: string;
  decisionReady: boolean;
  loss: boolean;
  lowMargin: boolean;
  blockingGaps: string[];
}

/** 批次的汇总数字（后端读时聚合）。 */
export interface BatchSummary {
  total: number;
  calculated: number;
  skippedNoPrice: number;
  notReady: number;
  loss: number;
  lowMargin: number;
  problem: number;
  avgGrossMarginRate: number | null;
  totalUnitPrice: number | null;
  totalGrossProfit: number | null;
}

/** 一次批量测算。 */
export interface BatchRunDetail {
  id: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  costCurrency: string;
  scopeCategory: string;
  scopeBrand: string;
  priceSource: string;
  quantity: number;
  fxRate: number | null;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  monthlyFixedCost: number | null;
  lowMarginThreshold: number;
  costWindowFrom: string | null;
  costWindowTo: string | null;
  note: string;
  createdBy: string | null;
  createdAt: string;
  summary: BatchSummary;
}

/** 批次列表（分页）。 */
export interface BatchRunPage {
  items: BatchRunDetail[];
  page: number;
  size: number;
  total: number;
}

/** 结果列表（分页）。 */
export interface BatchResultPage {
  items: BatchResultRow[];
  page: number;
  size: number;
  total: number;
}

/** 结果筛选。列表与导出共用同一套。 */
export interface BatchResultFilters {
  /** 毛利率 &lt; X（小数口径）。 */
  marginBelow: number | null;
  /** 售价 &lt; Y。 */
  priceBelow: number | null;
  /** 异常标记筛选。 */
  flag: BatchFlag | '';
  /** 状态筛选。 */
  status: BatchResultStatus | '';
  /** 关键字（SKU 编码 / 名称）。 */
  keyword: string;
  /** 排序。 */
  sort: BatchSort;
}

// ================================================================ 分区描述符

/** 分区里的一个表单项。 */
export interface BatchSectionField {
  id: string;
  label: string;
  hint: string;
  kind: 'select' | 'text' | 'number' | 'textarea';
  required?: boolean;
}

/**
 * 批量测算分区的描述符 —— **组件从它渲染，测试也从它断言**。
 *
 * <p>🔴 这样做的理由：断言写在字面量上，兄弟模块一旦交付就会红
 * （本仓 2026-10-20 踩过这个坑，害得整批 L2 返工一轮）。
 * 断言「本分区渲染出了它自己的标题与字段」这件事本身是稳的 ——
 * 它只依赖本模块自己的描述符。
 */
export interface BatchSectionDescriptor {
  key: string;
  title: string;
  description: string;
  fields: BatchSectionField[];
}

export const BATCH_SECTION: BatchSectionDescriptor = {
  key: 'batch-calculation',
  title: '批量测算',
  description:
    '对一批 SKU 批量跑利润测算：按当前成本与规则输出利润表，标记亏损品与低毛利品，支持筛选与导出。亏损品要么涨价要么清仓 —— 人工逐个测算不可行。',
  fields: [
    { id: 'platformCode', label: '平台', hint: '来自 2.1.1.1 平台目录', kind: 'select', required: true },
    { id: 'siteCode', label: '店铺（站点）', hint: '决定取价与币种', kind: 'select', required: true },
    { id: 'category', label: '分类', hint: 'spu 分类精确匹配，空 = 不限定', kind: 'text' },
    { id: 'brand', label: '品牌', hint: 'spu 品牌精确匹配，空 = 不限定', kind: 'text' },
    { id: 'quantity', label: '数量', hint: '用于阶梯与运费', kind: 'number' },
    { id: 'platformFeeRate', label: '平台费率（手工假设）', hint: '0.15 = 15%', kind: 'number' },
    { id: 'logisticsCostPerUnit', label: '物流每单位运费（手工假设）', hint: '每单位尾程运费', kind: 'number' },
    { id: 'lowMarginThreshold', label: '低毛利阈值', hint: '0.15 = 15%，小数口径', kind: 'number' },
  ],
};

// ================================================================ 纯函数：口径渲染

/**
 * 金额渲染。
 *
 * <p>🔴 {@code null} ⇒ 「未计入」，**不是 0.00**。
 * 「取不到」与「确实是 0」是两件事。
 */
export function moneyText(value: number | null | undefined, fallback = '未计入'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return trimZeros(value.toFixed(4));
}

/** 金额 + 币种。取不到时**不**补币种。 */
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

/** 数量渲染（6 位口径）。 */
export function quantityText(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return trimZeros(value.toFixed(6));
}

function trimZeros(text: string): string {
  if (!text.includes('.')) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/** 结果状态的中文名。 */
export function statusLabel(status: BatchResultStatus | null | undefined): string {
  switch (status) {
    case 'CALCULATED':
      return '已测算';
    case 'SKIPPED_NO_PRICE':
      return '跳过（无价格观测）';
    default:
      return '—';
  }
}

/**
 * 一行的异常标记。
 *
 * <p>🔴 两个标记互斥（后端已判），所以这里返回**一个**标签而不是一个数组。
 */
export function flagLabel(row: BatchResultRow): string {
  if (row.loss) return '亏损品';
  if (row.lowMargin) return '低毛利品';
  return '—';
}

/**
 * 一行的色调。
 *
 * <p>🔴 跳过的行**不是** danger —— 它不是「有问题」，它是「没测」，
 * 标成 danger 会让一堆待补数据的 SKU 混进异常列表。
 */
export function rowTone(
  row: BatchResultRow,
): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  if (row.status === 'SKIPPED_NO_PRICE') return 'neutral';
  if (row.loss) return 'danger';
  if (row.lowMargin) return 'warning';
  if (!row.decisionReady) return 'info';
  return 'success';
}

/** 一行是否「有问题」（亏损或低毛利）。 */
export function isProblem(row: BatchResultRow): boolean {
  return row.loss || row.lowMargin;
}

/** 售价来源的中文名。 */
export function priceSourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'INTERNAL_PRICE':
      return '我方价';
    case 'PLATFORM_PRICE':
      return '平台在售价';
    default:
      return '—';
  }
}

/** 决策就绪度的中文名。与 5.3.1.1 的口径同名同义。 */
export function decisionLabel(basis: string | null | undefined): string {
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

/** 顶部统计卡。 */
export interface BatchSummaryStat {
  label: string;
  value: string;
  hint: string;
  tone: 'success' | 'warning' | 'danger' | 'info';
}

/**
 * 批次的汇总卡。
 *
 * <p>🔴 「问题品合计」= 亏损品 + 低毛利品（两个标记互斥，可以直接相加）。
 */
export function summaryStats(summary: BatchSummary | null): BatchSummaryStat[] {
  if (!summary) return [];
  return [
    {
      label: '范围内 SKU',
      value: String(summary.total),
      hint: `已测算 ${summary.calculated} · 跳过 ${summary.skippedNoPrice}`,
      tone: 'info',
    },
    {
      label: '亏损品',
      value: String(summary.loss),
      hint: '毛利额 < 0 —— 要么涨价，要么清仓',
      tone: summary.loss > 0 ? 'danger' : 'success',
    },
    {
      label: '低毛利品',
      value: String(summary.lowMargin),
      hint: '还没亏，但毛利率低于阈值',
      tone: summary.lowMargin > 0 ? 'warning' : 'success',
    },
    {
      label: '问题品合计',
      value: String(summary.problem),
      hint: '亏损品 + 低毛利品（两个标记互斥，不重复计数）',
      tone: summary.problem > 0 ? 'danger' : 'success',
    },
    {
      label: '平均毛利率',
      value: percentText(summary.avgGrossMarginRate, '—'),
      hint: summary.notReady > 0 ? `其中 ${summary.notReady} 行成本不完整，未参与平均` : '按有毛利率的行取平均',
      tone: summary.notReady > 0 ? 'warning' : 'success',
    },
  ];
}

// ================================================================ 纯函数：输入与筛选

/** 校验失败。 */
export interface BatchValidationError {
  field: string;
  message: string;
}

/** 空表单。 */
export function emptyRequest(): BatchCalculationRequest {
  return {
    platformCode: '',
    siteCode: '',
    currency: 'USD',
    costCurrency: 'USD',
    category: '',
    brand: '',
    quantity: 1,
    fxRate: null,
    platformFeeRate: null,
    platformFeeFixed: null,
    logisticsCostPerUnit: null,
    monthlyFixedCost: null,
    lowMarginThreshold: null,
    costWindowFrom: null,
    costWindowTo: null,
    note: '',
  };
}

/** 空筛选。 */
export function emptyFilters(): BatchResultFilters {
  return {
    marginBelow: null,
    priceBelow: null,
    flag: '',
    status: '',
    keyword: '',
    sort: 'MARGIN_ASC',
  };
}

/**
 * 提交前的本地校验（后端还会再校验一遍，这里是为了少一次往返）。
 *
 * <p>🔴 跨币种没给汇率 ⇒ 拒绝提交。绝不按 1:1 折算。
 */
export function validateRequest(request: BatchCalculationRequest): BatchValidationError | null {
  if (!request.platformCode.trim()) {
    return { field: 'platformCode', message: '必须指定平台' };
  }
  if (!request.siteCode.trim()) {
    return { field: 'siteCode', message: '必须指定店铺（站点）—— 没有店铺就没有售价' };
  }
  if (!/^[A-Za-z]{3}$/.test(request.currency)) {
    return { field: 'currency', message: '币种必须是三位大写字母' };
  }
  if (request.costCurrency && !/^[A-Za-z]{3}$/.test(request.costCurrency)) {
    return { field: 'costCurrency', message: '币种必须是三位大写字母' };
  }
  if (request.quantity !== null && request.quantity <= 0) {
    return { field: 'quantity', message: '数量必须大于 0' };
  }
  if (
    request.platformFeeRate !== null &&
    (request.platformFeeRate < 0 || request.platformFeeRate >= 1)
  ) {
    return { field: 'platformFeeRate', message: '平台费率是小数口径（0.15 表示 15%）' };
  }
  if (request.logisticsCostPerUnit !== null && request.logisticsCostPerUnit < 0) {
    return { field: 'logisticsCostPerUnit', message: '物流每单位运费不能为负' };
  }
  if (
    request.lowMarginThreshold !== null &&
    (request.lowMarginThreshold < 0 || request.lowMarginThreshold >= 1)
  ) {
    return { field: 'lowMarginThreshold', message: '低毛利阈值是小数口径（0.15 表示 15%）' };
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
  return null;
}

/**
 * 把筛选拼成查询串。
 *
 * <p>🔴 列表与导出**共用**这一段 —— 导出的就是查到的那些行。
 */
export function buildResultQuery(filters: BatchResultFilters, page?: number, size?: number): string {
  const params = new URLSearchParams();
  if (filters.marginBelow !== null && filters.marginBelow !== undefined) {
    params.set('marginBelow', String(filters.marginBelow));
  }
  if (filters.priceBelow !== null && filters.priceBelow !== undefined) {
    params.set('priceBelow', String(filters.priceBelow));
  }
  if (filters.flag) params.set('flag', filters.flag);
  if (filters.status) params.set('status', filters.status);
  if (filters.keyword.trim()) params.set('keyword', filters.keyword.trim());
  if (filters.sort) params.set('sort', filters.sort);
  if (page !== undefined) params.set('page', String(page));
  if (size !== undefined) params.set('size', String(size));
  const text = params.toString();
  return text ? `?${text}` : '';
}

/** 导出的浏览器地址（普通 <a download>，不走 api()：它无条件设 JSON content-type）。 */
export function batchExportUrl(runId: string, filters: BatchResultFilters): string {
  return `/api/catalog/catalog/batch-calculations/runs/${runId}/export${buildResultQuery(filters)}`;
}

/** 筛选的人话摘要（页面上写明当前在看哪一批）。 */
export function filterSummary(filters: BatchResultFilters): string {
  const parts: string[] = [];
  if (filters.marginBelow !== null && filters.marginBelow !== undefined) {
    parts.push(`毛利率 < ${percentText(filters.marginBelow, '—')}`);
  }
  if (filters.priceBelow !== null && filters.priceBelow !== undefined) {
    parts.push(`售价 < ${moneyText(filters.priceBelow)}`);
  }
  if (filters.flag === 'LOSS') parts.push('只看亏损品');
  if (filters.flag === 'LOW_MARGIN') parts.push('只看低毛利品');
  if (filters.status === 'SKIPPED_NO_PRICE') parts.push('只看跳过的行');
  if (filters.keyword.trim()) parts.push(`关键字「${filters.keyword.trim()}」`);
  if (!parts.length) return '未筛选（全部结果）';
  return parts.join(' · ');
}

// ================================================================ 端点

export function fetchBatchCapabilities(): Promise<BatchCalculationCapabilities> {
  return api<BatchCalculationCapabilities>('catalog/batch-calculations/capabilities');
}

export function fetchBatchSites(): Promise<SiteOption[]> {
  return api<SiteOption[]>('catalog/batch-calculations/sites');
}

export function createBatchRun(request: BatchCalculationRequest): Promise<BatchRunDetail> {
  return api<BatchRunDetail>('catalog/batch-calculations/runs', 'POST', request);
}

export function fetchBatchRuns(page = 0, size = 20): Promise<BatchRunPage> {
  return api<BatchRunPage>(`catalog/batch-calculations/runs?page=${page}&size=${size}`);
}

export function fetchBatchResults(
  runId: string,
  filters: BatchResultFilters,
  page = 0,
  size = 20,
): Promise<BatchResultPage> {
  return api<BatchResultPage>(
    `catalog/batch-calculations/runs/${runId}/results${buildResultQuery(filters, page, size)}`,
  );
}
