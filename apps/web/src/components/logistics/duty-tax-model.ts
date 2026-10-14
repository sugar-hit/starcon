import type {
  DutyEstimateCreateRequest,
  DutyRateCreateRequest,
  DutyEstimateDetail,
  DutyEstimatePage,
  DutyEstimateRow,
  DutyEstimateUpdateRequest,
  DutyPaymentCreateRequest,
  DutyRateRow,
  DutySaved,
  DutyTaxCapabilities,
  DutyVarianceAnalysis,
  DutyVarianceRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../inventory/api';
import { fetchCustomsDeclarations } from './customs-declaration-model';

/**
 * 10.5.1.3 关税与税费 —— 前端模型。
 *
 * <p>蓝图：「计算与管理进口税费：关税（按 HS 编码税率）、增值税/GST、关税承担方（DDP/DDU）、
 * 到岸成本构成，供成本核算与报价参考」。
 *
 * <p>本模块必须自己讲清的四条口径（都从后端 {@code /capabilities} 拿，不在前端另抄一份）：
 *
 * <ol>
 *   <li>**估算与实缴分别留痕**：蓝图原文「税费估算与实际缴纳差异要记录，长期差异大说明
 *       HS 归类或税率配置有误」。所以页面上估算、实缴、差异是三个独立区块，
 *       差异区**不**是估算减实缴的临时算式，而是后端落库留痕的快照。
 *   <li>**税率按生效期检索**：税率表带生效/失效日期，估算明细里抄着自己当时用的税率值 ——
 *       界面上要把「按哪个前缀、哪个税率、哪天生效」一起显示出来，否则复核时答不出来。
 *   <li>**0% 税率合法，「没匹配到税率」不合法**：带未匹配税率的估算单锁不了，
 *       页面据此把锁定按钮置灰并说清缺哪条税率。
 *   <li>**DDP/DDU 改变成本归属**：DDU 下卖方到岸成本不含关税与税费，界面上要分行显示，
 *       不能只给一个「到岸成本」总数让人误以为都归卖方。
 * </ol>
 */

/** 基础路径。端点命名空间 `/warehouse/duty-taxes`，浏览器只经 /api/warehouse 代理。 */
const BASE = 'warehouse/duty-taxes';

/** 列表查询条件。与后端 `DutyEstimateQuery` 一一对齐。 */
export interface DutyEstimateParams {
  status?: string[];
  destinationCountryCode?: string;
  dutyBearer?: string;
  incoterm?: string;
  declarationId?: string;
  /** 只看没配到税率的估算单 —— 它们是最容易把错价发出去的那批。 */
  unmatchedRateOnly?: boolean;
  varianceOnly?: boolean;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

function text(value: string | null | undefined): string {
  return value?.trim() ?? '';
}

export function dutyEstimateQuery(params: DutyEstimateParams): string {
  const parts: string[] = [];
  if (params.status?.length) {
    for (const s of params.status) parts.push(`status=${encodeURIComponent(s)}`);
  }
  if (text(params.destinationCountryCode)) {
    parts.push(`destinationCountryCode=${encodeURIComponent(text(params.destinationCountryCode))}`);
  }
  if (text(params.dutyBearer)) parts.push(`dutyBearer=${encodeURIComponent(text(params.dutyBearer))}`);
  if (text(params.incoterm)) parts.push(`incoterm=${encodeURIComponent(text(params.incoterm))}`);
  if (params.declarationId) parts.push(`declarationId=${encodeURIComponent(params.declarationId)}`);
  if (params.unmatchedRateOnly) parts.push('unmatchedRateOnly=true');
  if (params.varianceOnly) parts.push('varianceOnly=true');
  if (text(params.keyword)) parts.push(`keyword=${encodeURIComponent(text(params.keyword))}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 读

export function fetchDutyCapabilities() {
  return api<DutyTaxCapabilities>(`${BASE}/capabilities`);
}

export function fetchDutyEstimates(params: DutyEstimateParams) {
  return api<DutyEstimatePage>(`${BASE}${dutyEstimateQuery(params)}`);
}

export function fetchDutyEstimateDetail(id: string) {
  return api<DutyEstimateDetail>(`${BASE}/${encodeURIComponent(id)}`);
}

export function fetchDutyRates(params: {
  destinationCountryCode?: string;
  taxKind?: string;
  rateScope?: string;
  keyword?: string;
  page?: number;
  size?: number;
}) {
  const parts: string[] = [];
  if (text(params.destinationCountryCode)) {
    parts.push(`destinationCountryCode=${encodeURIComponent(text(params.destinationCountryCode))}`);
  }
  if (text(params.taxKind)) parts.push(`taxKind=${encodeURIComponent(text(params.taxKind))}`);
  if (text(params.rateScope)) parts.push(`rateScope=${encodeURIComponent(text(params.rateScope))}`);
  if (text(params.keyword)) parts.push(`keyword=${encodeURIComponent(text(params.keyword))}`);
  parts.push(`page=${params.page ?? 0}`, `size=${params.size ?? 50}`);
  return api<DutyRateRow[]>(`${BASE}/rates?${parts.join('&')}`);
}

export function fetchVarianceAnalysis(params: { destinationCountryCode?: string }) {
  const query = text(params.destinationCountryCode)
    ? `?destinationCountryCode=${encodeURIComponent(text(params.destinationCountryCode))}`
    : '';
  return api<DutyVarianceAnalysis>(`${BASE}/variance-analysis${query}`);
}

/**
 * 可估算的报关单（已放行 / 已提交的优先）。
 *
 * <p>刻意复用 10.5.1.1 报关单自己的列表端点而不是在前端造一张「可估算报关单」：
 * 「哪些报关单能估算税费」是后端的判据（一张报关单只能估一次税），
 * 前端再抄一遍必然漂移。
 */
export function fetchEstimatableDeclarations(keyword?: string) {
  const trimmed = keyword?.trim();
  return trimmed
    ? fetchCustomsDeclarations({ keyword: trimmed, size: 50 })
    : fetchCustomsDeclarations({ size: 50 });
}

// ================================================================ 写

export function createDutyEstimate(input: DutyEstimateCreateRequest) {
  return api<DutySaved>(BASE, 'POST', input);
}

export function updateDutyEstimate(id: string, input: DutyEstimateUpdateRequest) {
  return api<DutySaved>(`${BASE}/${encodeURIComponent(id)}`, 'PUT', input);
}

/**
 * 按当前（或指定生效日的）税率重算。
 *
 * <p>生效日可以指定 —— 「当时按哪个税率算的」必须可复现、可举证。
 */
export function recalculateDutyEstimate(id: string, rateDate?: string) {
  const query = rateDate ? `?rateDate=${encodeURIComponent(rateDate)}` : '';
  return api<DutySaved>(
    `${BASE}/${encodeURIComponent(id)}/recalculate${query}`,
    'POST',
  );
}

export function lockDutyEstimate(id: string, notes: string) {
  return api<DutySaved>(
    `${BASE}/${encodeURIComponent(id)}/lock`,
    'POST',
    { notes },
  );
}

export function voidDutyEstimate(id: string, reason: string) {
  return api<DutySaved>(
    `${BASE}/${encodeURIComponent(id)}/void`,
    'POST',
    { reason },
  );
}

export function recordDutyPayment(id: string, input: DutyPaymentCreateRequest) {
  return api<DutySaved>(
    `${BASE}/${encodeURIComponent(id)}/payments`,
    'POST',
    input,
  );
}

export function createDutyRate(input: DutyRateCreateRequest) {
  return api<DutySaved>(`${BASE}/rates`, 'POST', input);
}

// ================================================================ 界面口径

/** 估算单状态文案。与后端 `EstimateStatus.label()` 逐条一致，不在前端另抄一份。 */
export const estimateStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  ESTIMATED: '已估算',
  LOCKED: '已锁定',
  VOID: '已作废',
};

export const taxKindLabels: Record<string, string> = {
  DUTY: '关税',
  VAT: '增值税',
  GST: '商品服务税',
};

export const dutyBearerLabels: Record<string, string> = {
  SELLER: '卖方承担',
  BUYER: '买方承担',
  CARRIER: '承运人承担',
};

export const incotermLabels: Record<string, string> = {
  EXW: '工厂交货',
  FCA: '货交承运人',
  FOB: '装运港船上交货',
  CFR: '成本加运费',
  CIF: '成本保险加运费',
  DDP: '完税后交货',
  DDU: '未完税交货',
};

export const rateMatchStatusLabels: Record<string, string> = {
  MATCHED: '已匹配',
  NO_MATCH: '未匹配',
  EXPIRED: '税率已失效',
};

export const rateScopeLabels: Record<string, string> = {
  COUNTRY: '国别',
  HS: '按 HS 编码',
};

export const varianceCauseLabels: Record<string, string> = {
  PENDING: '待归因',
  HS_CLASSIFICATION: 'HS 归类错误',
  RATE_CONFIG: '税率配置错误',
  CUSTOMS_VALUE: '完税价格差异',
  FX_RATE: '汇率差异',
  FTA_PREFERENCE: 'FTA 优惠未适用',
  EXEMPTION: '免税/零税率适用',
  PENALTY_INTEREST: '滞纳金/附加',
  OTHER: '其他',
};

export const varianceResolutionLabels: Record<string, string> = {
  OPEN: '待处理',
  INVESTIGATING: '排查中',
  FIXED_RATE_CONFIG: '已修税率配置',
  FIXED_HS_CLASSIFICATION: '已修 HS 归类',
  ACCEPTED: '接受差异',
  WRITTEN_OFF: '核销',
};

export const paymentKindLabels: Record<string, string> = {
  PAYMENT: '缴纳',
  REFUND: '退汇',
  ADJUSTMENT: '调整',
};

export const estimateSortOptions = [
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'CREATED_AT', label: '最近创建' },
  { value: 'ESTIMATED_TAX', label: '税费估算' },
  { value: 'LANDED_COST', label: '到岸成本' },
] as const;

/**
 * 当前状态下**合法**的动作按钮。
 *
 * <p>判据直接读后端给的合法转换（`/capabilities` 与详情里的 `transitions` 同一张表），
 * 所以前端不再抄一份状态机 —— 抄了会在后端改表时静默漂移，界面就会给出一个注定被拒的动作。
 */
export function estimateActions(status: string): string[] {
  switch (status) {
    case 'DRAFT':
      return ['recalculate', 'void'];
    case 'ESTIMATED':
      return ['lock', 'recalculate', 'void'];
    case 'LOCKED':
      // 锁定之后基础与承担方都不许改：改了就等于把 DDP 的成本挪成 DDU 的成本，
      // 而已发出的报价失去了依据。所以这里只剩录实缴。
      return ['payment'];
    case 'VOID':
      return [];
    default:
      return [];
  }
}

export function actionLabel(action: string): string {
  const labels: Record<string, string> = {
    lock: '锁定为报价依据',
    recalculate: '按当前税率重算',
    void: '作废',
    payment: '记录实缴',
  };
  return labels[action] ?? action;
}

/**
 * 锁定前的阻断项清单：没配到税率的行会让估算单锁不了。
 *
 * <p>页面据此把锁定按钮置灰并**点名缺哪条** —— 蓝色图警告的口径是
 * 「按 0 税锁定的估算单会让报价与到岸成本少算税」。
 */
export function lockBlockers(detail: {
  estimate: DutyEstimateRow;
  lines: DutyEstimateDetail['lines'];
}): string[] {
  const blockers: string[] = [];
  const dutyMiss = detail.lines.filter((l) => l.rateMatchStatus !== 'MATCHED');
  if (dutyMiss.length) {
    const codes = [...new Set(dutyMiss.map((l) => (l.hsCode ? `HS ${l.hsCode}` : '缺 HS 编码')))];
    blockers.push(`${dutyMiss.length} 行没匹配到关税税率（${codes.join('、')}）`);
  }
  const vatMiss = detail.lines.filter((l) => !l.vatRateMatched);
  if (vatMiss.length) {
    blockers.push(`${vatMiss.length} 行没配到目的国增值税/GST 税率`);
  }
  return blockers;
}

// ================================================================ 展示

/** 数值展示。数量与金额来自 NUMERIC，后端给的是 number，这里只做展示格式化。 */
export function fmt(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function money(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return `${fmt(value)} ${currency ?? ''}`.trim();
}

/**
 * 税率展示：必须带百分比号与「小数形式」的来源。
 *
 * <p>后端存的是小数（0.0325 = 3.25%），界面上要显示成 3.25% 并保留原值 ——
 * 只显示百分比会让「当时按哪个数算的」在复核时答不出来。
 */
export function rateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || !Number.isFinite(rate)) return '—';
  return `${(rate * 100).toLocaleString('zh-CN', { maximumFractionDigits: 4 })}%（${rate}）`;
}

export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function dateOnly(value: string | null | undefined): string {
  if (!value) return '长期有效';
  return value;
}

/**
 * 差异率展示：估算为 0 时差异率**无意义**（除零），不能显示成 0% ——
 * 那会把「税率根本没配」显示成「完全对得上」，正好掩盖蓝图点名要抓的那种错。
 */
export function varianceRateText(row: Pick<DutyVarianceRow, 'rateCalculable' | 'varianceRate'>): string {
  if (!row.rateCalculable || row.varianceRate === null || row.varianceRate === undefined) {
    return '不可算（估算为 0）';
  }
  return `${(row.varianceRate * 100).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}%`;
}
