import type {
  CandidateOrder,
  CandidateSet,
  ConsolidationDetail,
  ConsolidationInput,
  ConsolidationPage,
  ConsolidationRow,
  ConsolidationRule,
  ConsolidationRuleInput,
  ConsolidationSaved,
  ConsolidationStatusInput,
  ExcludedOrder,
  OrderConsolidationCapabilities,
  WritebackInput,
  WritebackPage,
  WritebackTask,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.3.1.2 合单前端模型。
 *
 * <p>蓝图：「把同一买家的多笔订单合并成一个包裹发货，降低运费与包材成本；
 * 同时识别跨店铺同买家订单（需分别回传平台）。」输出是
 * 「合并履约请求 + 各原订单的关联记录」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「没报过合并运费价」显示成「未报价」，绝不显示成 0。</b>后端在租户没给报价时
 *       返回 {@code freightSavingAmount: null} —— 那是「还不知道能省多少」，
 *       而 0 会被读成「合了单一点运费没省」。两者在界面上必须是两句话。</li>
 *   <li><b>「没进候选」也要显示，且要带原因。</b>只列出候选而不说排除项，
 *       运营会以为系统漏单了；后端已经把每张排除单的原因码带回来了，直接显示。</li>
 *   <li><b>跨店铺必须显眼。</b>蓝图加粗句说「合单后每个平台订单仍要单独回传物流号」，
 *       所以列表与详情都要把「跨了 N 个平台 / N 个店铺」放在第一眼能看到的位置，
 *       并且回传任务按**平台订单**逐条列出，而不是给一个包裹一条。</li>
 *   <li><b>金额不再二次四舍五入。</b>后端已按 numeric(19,4) 落库并实算汇总，
 *       前端只做展示格式化，不再改值，否则界面上会比账面多出或少掉一分钱。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

export interface CandidateParams {
  recipientName?: string;
  recipientPhone?: string;
  recipientEmail?: string;
  addressLine1?: string;
  addressLine2?: string;
  countryCode?: string;
  province?: string;
  city?: string;
  district?: string;
  postcode?: string;
}

export interface ConsolidationParams {
  keyword?: string;
  status?: string;
  buyerName?: string;
  crossShopOnly?: boolean;
  page?: number;
  size?: number;
}

export interface WritebackParams {
  consolidationId?: string;
  platformCode?: string;
  status?: string;
  platformOrderRef?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'trade/fulfillment/consolidation';

export function fetchCapabilities(): Promise<OrderConsolidationCapabilities> {
  return api<OrderConsolidationCapabilities>(`${base}/capabilities`);
}

export function fetchRule(): Promise<ConsolidationRule> {
  return api<ConsolidationRule>(`${base}/rule`);
}

export function saveRule(input: ConsolidationRuleInput): Promise<ConsolidationRule> {
  return api<ConsolidationRule>(`${base}/rule`, 'PUT', input);
}

export function fetchCandidates(params: CandidateParams): Promise<CandidateSet> {
  return api<CandidateSet>(`${base}/candidates`, 'POST', params);
}

export function fetchConsolidations(params: ConsolidationParams): Promise<ConsolidationPage> {
  return api<ConsolidationPage>(`${base}/consolidations${query(params)}`);
}

export function createConsolidation(input: ConsolidationInput): Promise<ConsolidationSaved> {
  return api<ConsolidationSaved>(`${base}/consolidations`, 'POST', input);
}

export function fetchConsolidation(consolidationId: string): Promise<ConsolidationDetail> {
  return api<ConsolidationDetail>(`${base}/consolidations/${consolidationId}`);
}

export function advanceConsolidationStatus(
  consolidationId: string,
  input: ConsolidationStatusInput,
): Promise<ConsolidationSaved> {
  return api<ConsolidationSaved>(`${base}/consolidations/${consolidationId}/status`, 'POST', input);
}

export function fetchWritebackTasks(params: WritebackParams): Promise<WritebackPage> {
  return api<WritebackPage>(`${base}/writeback-tasks${query(params)}`);
}

export function recordWriteback(
  consolidationId: string,
  memberId: string,
  input: WritebackInput,
): Promise<WritebackTask> {
  return api<WritebackTask>(
    `${base}/consolidations/${consolidationId}/members/${memberId}/writeback`,
    'POST',
    input,
  );
}

// ------------------------------------------------------------ 展示

/** 金额：只做展示格式化，**不改值**（后端已按 numeric(19,4) 落库并实算汇总）。 */
export function money(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4).replace(/\.?0+$/, '') || '0';
}

/**
 * 运费节省：区分「未报价」与「省了 0」。
 *
 * <p>这是本模型最重要的一处：{@code freightSavingAmount === null} 表示
 * 租户没给合并运费报价，**不知道**能省多少；0 表示报价了但没省到钱。
 * 两者在界面上必须分别说「未报价」与「0.0000」，合成一句话就是撒谎。
 */
export function freightSavingText(row: {
  freightSavingAmount: number | null;
  consolidatedShippingAmount: number | null;
}): string {
  if (row.consolidatedShippingAmount === null || row.consolidatedShippingAmount === undefined) {
    return '未报价合并运费，节省暂不可计算（不是 0）';
  }
  if (row.freightSavingAmount === null || row.freightSavingAmount === undefined) {
    return '未报价合并运费，节省暂不可计算（不是 0）';
  }
  return money(row.freightSavingAmount);
}

/** 合单规模：几笔订单 / 几行 / 几件。 */
export function sizeText(row: {
  orderCount: number;
  totalLineCount: number;
  totalQty: number | null;
}): string {
  return `${row.orderCount} 笔 / ${row.totalLineCount} 行 / ${row.totalQty ?? 0} 件`;
}

/**
 * 跨店铺标签。
 *
 * <p>蓝图要「识别跨店铺同买家订单」：非跨店铺时返回 null（不渲染徽标），
 * 跨店铺时给出平台数与店铺数。**店铺数为 0 是正常的** ——
 * D5 平台单同步未交付时 sales_order 没有 shop_code 列，店铺口径拿不到，
 * 这时只报平台数，不编一个店铺数出来。
 */
export function crossShopText(row: {
  crossShop: boolean;
  distinctPlatformCount: number;
  distinctShopCount: number;
}): string | null {
  if (!row.crossShop) return null;
  const shop = row.distinctShopCount > 0 ? ` / ${row.distinctShopCount} 个店铺` : '';
  return `跨 ${row.distinctPlatformCount} 个平台${shop}`;
}

/** 平台归属：非平台单返回 null（而不是编一个哨兵值）。 */
export function platformText(member: {
  platformCode: string;
  shopCode: string;
  platformOrderRef: string;
}): string | null {
  if (!member.platformCode) return null;
  const shop = member.shopCode ? ` / ${member.shopCode}` : '';
  const ref = member.platformOrderRef ? ` / ${member.platformOrderRef}` : '';
  return `${member.platformCode}${shop}${ref}`;
}

/** 时间：后端给 ISO 值，前端**不做时区换算**（换算会让「几点合的单」与账面对不上）。 */
export function isoText(value: string | null | undefined): string {
  return value ? value : '—';
}

/** 排除原因 → 给运营看的一句话（后端已给 reasonLabel，这里兜底未知码）。 */
export function excludedText(item: ExcludedOrder): string {
  return item.reasonLabel || item.reason;
}

/** 候选是否可合（未被任何原因排除）。 */
export function isConsolidatable(item: CandidateOrder): boolean {
  return !!item.orderNo;
}

/** 能否现在落合并：至少两笔候选、且规则未阻断。 */
export function canConsolidate(set: CandidateSet | null, selectedCount: number): boolean {
  if (!set) return false;
  if (!set.ruleConfigured) return false;
  if (set.ruleBlockedReason) return false;
  return selectedCount >= 2;
}

/**
 * 落合并前要显示的阻断理由；没有阻断返回 null。
 *
 * <p>刻意**不只**看后端给的 `ruleBlockedReason`：规则未配置时后端通常也会给一句理由，
 * 但那一栏若因为任何原因为空，页面就会显示「没有阻断」而按钮是禁用的 ——
 * 两者自相矛盾，用户无从判断是自己选错了还是系统坏了。这里把
 * 「规则未配置」当作**前端自己就能确定**的阻断原因，不依赖后端那一栏。
 */
export function blockedReason(set: CandidateSet | null): string | null {
  if (!set) return null;
  if (!set.ruleConfigured) {
    return '本租户尚未登记合单规则：候选可见，但落合并会被拒绝（见上方规则区）';
  }
  return set.ruleBlockedReason || null;
}

// ------------------------------------------------------------ 错误

export function errorText(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return '合单服务暂时不可用，请稍后重试';
}

/** 供列表页筛选用的状态选项（与后端枚举同源，不在前端另立一份词表）。 */
export const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'PLANNED', label: '已合并' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'DISPATCHED', label: '已下发打包' },
  { value: 'CANCELLED', label: '已作废' },
];

/** 回传状态选项。 */
export const WRITEBACK_STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待回传' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'CONFIRMED', label: '已回传' },
  { value: 'REJECTED', label: '平台拒绝' },
  { value: 'PENDING_VERIFICATION', label: '结果待查证' },
];
