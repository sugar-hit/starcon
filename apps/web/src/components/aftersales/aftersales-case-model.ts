import type {
  AftersalesBulkItemResult,
  AftersalesBulkResult,
  AftersalesCaseCapabilities,
  AftersalesCasePage,
  AftersalesCaseRow,
  AftersalesFilterOptions,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 11.1.1.1 售后单列表前端模型。
 *
 * <p>蓝图：「售后单主列表：售后号、关联订单、店铺、类型（退货退款/仅退款/换货/补发/维修）、金额、
 * 原因、状态、时效（平台要求回复/处理时限）、责任人，支持多维筛选与批量处理。」
 * 加粗句：「<b>平台时效倒计时必须显眼</b>：超时会直接影响账号绩效与资金冻结。」
 *
 * <p><b>本模型的唯一职责是让「时效显眼」这件事在界面上真的成立</b>：
 *
 * <ul>
 *   <li>{@link timelinessTone} 把后端的时效状态映射成**四档视觉语气**（含倒计时文本与排序权重），
 *       判定依据全部来自后端的 {@code timelinessState} 枚举 ——
 *       <b>前端绝不自己拿 dueAt 比大小</b>。阈值写在后端的 DB 函数里，
 *       前端再抄一份必然会与后端漂移，而超时的代价是账号绩效与资金冻结。
 *   <li>{@link countdownText} 把 {@code remainingSeconds} 渲染成「还剩 3 小时 12 分」这种
 *       人话，**已结案或无时限显示为明确的中文说明而不是「—」**。
 *   <li>{@link unavailableCell} 让不可得维度（店铺）显示「—」并带原因 ——
 *       空白会被读成「这个租户没有售后单」，而真相是「本服务还读不到店铺」。
 * </ul>
 *
 * <p>路径前缀是 {@code trade/aftersales}，前端路由是 {@code /aftersales}。
 */

const base = 'trade/aftersales';

export interface AftersalesListParams {
  caseType?: string;
  status?: string;
  reasonCode?: string;
  platformSource?: string;
  timelinessState?: string;
  currency?: string;
  authorizationState?: string;
  orderNo?: string;
  caseNo?: string;
  minAmount?: string;
  maxAmount?: string;
  ownerRef?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function aftersalesListQuery(params: AftersalesListParams): string {
  const parts: string[] = [];
  const put = (k: string, v?: string) => {
    if (v && v.trim()) parts.push(`${k}=${encodeURIComponent(v.trim())}`);
  };
  put('caseType', params.caseType);
  put('status', params.status);
  put('reasonCode', params.reasonCode);
  put('platformSource', params.platformSource);
  put('timelinessState', params.timelinessState);
  put('currency', params.currency);
  put('authorizationState', params.authorizationState);
  put('orderNo', params.orderNo);
  put('caseNo', params.caseNo);
  put('minAmount', params.minAmount);
  put('maxAmount', params.maxAmount);
  put('ownerRef', params.ownerRef);
  put('keyword', params.keyword);
  put('sort', params.sort);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchAftersalesCapabilities() {
  return api<AftersalesCaseCapabilities>(`${base}/capabilities`);
}

export function fetchAftersalesFilters() {
  return api<AftersalesFilterOptions>(`${base}/filters`);
}

export function fetchAftersalesList(params: AftersalesListParams) {
  return api<AftersalesCasePage>(`${base}/cases${aftersalesListQuery(params)}`);
}

export interface AftersalesBulkInput {
  operation: 'AUTHORIZE' | 'NOTE' | 'ESCALATE';
  caseIds: string[];
  // ⚠️ 显式带上 `| undefined`：本仓开了 exactOptionalPropertyTypes，
  // 只写 `note?: string` 时调用方**不能**传 undefined，而批量三种形态恰恰是
  // 「按操作类型只填其中几个字段」，必然要传 undefined。
  note?: string | undefined;
  decision?: 'APPROVED' | 'PARTIALLY_APPROVED' | 'REJECTED' | undefined;
  authorizedAmount?: string | undefined;
  reasonNote?: string | undefined;
  priority?: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT' | undefined;
}

export function bulkAftersales(input: AftersalesBulkInput) {
  return api<AftersalesBulkResult>(`${base}/cases/bulk`, 'POST', input);
}

export function registerAftersalesCase(payload: Record<string, unknown>) {
  return api<AftersalesCaseRow>(`${base}/cases`, 'POST', payload);
}

// ================================================================ 展示口径

/**
 * 时效语气：四档视觉 + 一个显式的「已解决」档。
 *
 * <p>`rank` 用来在表格里排序（0 最紧急），让「超时的一定在前面」不依赖用户去点排序。
 * 后端已经把 `DUE_ASC` 做成了默认排序，这里是**第二道**保险 ——
 * 因为一旦有人把排序改掉，超时的单沉到第 30 页是最容易被漏掉的失败模式。
 */
export type TimelinessTone = 'overdue' | 'due-soon' | 'on-track' | 'done' | 'no-deadline';

export interface TimelinessPresentation {
  tone: TimelinessTone;
  label: string;
  rank: number;
  /** 界面上的短标记，用于表格里最显眼的那一列（如「超时 3 天」）。 */
  badge: string;
}

// 用 satisfies 而不是直接声明成 Record<string, ...>：
// 后者是一个**索引签名**，在 noUncheckedIndexedAccess 下连
// `TIMELINESS_PRESENTATION.OVERDUE` 都会被判成 `| undefined`，
// 于是每次取值都要写断言，断言写漏一处就是运行时的 undefined。
// satisfies 保留字面量键，命名取值的类型是确定的。
const TIMELINESS = {
  OVERDUE: { tone: 'overdue', label: '已超时', rank: 0, badge: '超时' },
  DUE_SOON: { tone: 'due-soon', label: '临期', rank: 1, badge: '临期' },
  ON_TRACK: { tone: 'on-track', label: '正常', rank: 2, badge: '正常' },
  NO_DEADLINE: { tone: 'no-deadline', label: '无平台时限', rank: 3, badge: '无限时' },
  RESOLVED: { tone: 'done', label: '已处理', rank: 4, badge: '已处理' },
} satisfies Record<string, TimelinessPresentation>;

export const TIMELINESS_PRESENTATION: Readonly<Record<string, TimelinessPresentation>> = TIMELINESS;

/**
 * 取时效语气。
 *
 * <p>未知状态**刻意**回落到 `no-deadline` 而不是抛错或给一个乐观的绿色 ——
 * 认不出的状态意味着后端加了一个本界面还没接的口径，
 * 此时显示「无限时」是安全的（不会把一条真超时的单染成绿色），
 * 显示「正常」则会直接掩盖一次超时。
 */
export function timelinessTone(state: string | null | undefined): TimelinessPresentation {
  if (!state) return TIMELINESS.NO_DEADLINE;
  return TIMELINESS_PRESENTATION[state] ?? TIMELINESS.NO_DEADLINE;
}

/**
 * 倒计时文本。
 *
 * <p>⚠️ **`remainingSeconds` 为 null 时不能显示「已超时」或「0 秒」**：
 * null 的两种含义是「已处理，倒计时已停止」与「平台没给时限」，
 * 它们都不是超时。返回明确的中文短语，让运营一眼分得清。
 *
 * <p>已超时的行 `remainingSeconds` 是负数，这时显示**超了多久**而不是「还剩 -3 天」。
 */
export function countdownText(
  state: string | null | undefined,
  remainingSeconds: number | null | undefined,
): string {
  if (state === 'RESOLVED') return '倒计时已停止';
  if (state === 'NO_DEADLINE') return '平台未给时限';
  if (remainingSeconds === null || remainingSeconds === undefined) return '—';
  const overdue = remainingSeconds < 0;
  const text = humanizeDuration(Math.abs(remainingSeconds));
  return overdue ? `已超时 ${text}` : `还剩 ${text}`;
}

/** 秒数转「3 天 4 小时」这种可读文本；不足 1 分钟时给「不足 1 分钟」而不是「0 分 0 秒」。 */
export function humanizeDuration(seconds: number): string {
  const total = Math.floor(seconds);
  if (total < 60) return '不足 1 分钟';
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (days > 0) return hours > 0 ? `${days} 天 ${hours} 小时` : `${days} 天`;
  if (hours > 0) return minutes > 0 ? `${hours} 小时 ${minutes} 分` : `${hours} 小时`;
  return `${minutes} 分钟`;
}

/**
 * 时效徽标的 CSS 类名片段。
 *
 * <p>四档必须**视觉上真的分得开**（配色 + 边框 + 字重），
 * 这是蓝图「必须显眼」在 UI 层的落点。只有文字变化而没有视觉差异，
 * 在一屏 50 行里等于没有提醒。
 */
export function timelinessBadgeClass(state: string | null | undefined): string {
  return `tone-${timelinessTone(state).tone}`;
}

/** 不可得维度的单元格：显示「—」并带上可解释的原因。 */
export function unavailableCell(
  value: string | null | undefined,
  dimension?: { label: string; ownerModule: string; reason: string },
): { text: string; title: string } {
  if (value !== null && value !== undefined && value !== '') {
    return { text: value, title: '' };
  }
  return {
    text: '—',
    title: dimension
      ? `${dimension.label}尚不可得：归属 ${dimension.ownerModule}。${dimension.reason}`
      : '该维度尚不可得（见「能力」面板里的归属模块与原因）',
  };
}

/**
 * 汇总条文案：总数 + 超时 / 临期 + 分币种金额。
 *
 * <p><b>超时数排在最前面</b>：这是运营打开这个页面唯一要先看的东西。
 */
export function summaryLine(page: AftersalesCasePage): string {
  const s = page.summary;
  const head = `${s.totalCount} 张 · 超时 ${s.overdueCount} · 临期 ${s.dueSoonCount} · 已处理 ${s.resolvedCount}`;
  if (!s.amountsByCurrency || s.amountsByCurrency.length === 0) return head;
  return `${head} · ${s.amountsByCurrency
    .map((a) => `${formatAmount(a.amount, a.currency)}（${a.caseCount} 张）`)
    .join(' / ')}`;
}

/** 金额格式化。codegen 把 number 展平，这里只做展示。 */
export function formatAmount(value: string | number | null | undefined, currency?: string): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return currency ? `${n.toFixed(2)} ${currency}` : n.toFixed(2);
}

/** 数量格式化（numeric(19,6) 展示时去掉多余的尾零，但至少留 1 位小数）。 */
export function formatQuantity(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return String(Number(n.toFixed(3)));
}

/** 时间展示：带时区的 ISO 串转本地可读格式，解析失败时原样返回而不是显示 Invalid Date。 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
}

/**
 * 批量结果文案。
 *
 * <p>「30 条批了 12 条」必须拆开说：失败条数、单条失败原因、以及**哪几条**。
 * 只给一个数字的话，运营只能重跑一遍去猜是哪几条没成。
 */
export function bulkSummaryText(result: AftersalesBulkResult): string {
  return `${result.operationLabel}：成功 ${result.succeeded} / ${result.requested}，失败 ${result.failed}`;
}

/** 单条批量结果的展示：成功给产物引用，失败给人话原因（+ 已有工单号之类的关键信息）。 */
export function bulkItemText(item: AftersalesBulkItemResult): string {
  if (item.success) {
    return item.detail ? `${item.detail}${item.reference ? `（${item.reference}）` : ''}` : '成功';
  }
  return item.detail || item.outcome;
}

/** 责任人展示：没有责任人要说「未分配」，而不是显示空白。 */
export function ownerText(row: {
  ownerDisplayName?: string | null;
  ownerRef?: string | null;
  ownerUpstreamDelivered?: boolean;
}): string {
  if (row.ownerDisplayName) return row.ownerDisplayName;
  if (row.ownerRef) return row.ownerRef;
  return '未分配责任人';
}

/** 平台来源展示。只有平台级；店铺维度不可得（见 unavailableCell）。 */
export const platformSourceLabels: Record<string, string> = {
  MANUAL: '手工订单',
  PLATFORM: '平台订单',
  AMAZON: 'Amazon',
  SHOPIFY: 'Shopify',
  EBAY: 'eBay',
  TIKTOK_SHOP: 'TikTok Shop',
  ALIBABA_1688: '1688',
};

export function platformSourceLabel(code: string | null | undefined): string {
  if (!code) return '—';
  return platformSourceLabels[code] ?? code;
}

/**
 * 授权状态展示。NONE 是「没有任何授权行」，与三种授权决定分开显示。
 */
export function authorizationStateLabel(state: string | null | undefined): string {
  switch (state) {
    case 'APPROVED':
      return '已批准';
    case 'PARTIALLY_APPROVED':
      return '部分批准';
    case 'REJECTED':
      return '已拒绝';
    case 'NONE':
      return '未授权';
    default:
      return '—';
  }
}

/**
 * 能力声明里的「不可得维度」按字段名取，供表格列的 tooltip 用。
 *
 * <p>找不到时返回 undefined —— 界面退化成普通的「—」，**不编一条原因出来**。
 */
export function dimensionByField(
  caps: AftersalesCaseCapabilities | null | undefined,
  field: string,
): { label: string; ownerModule: string; reason: string } | undefined {
  const found = caps?.unavailableDimensions?.find((d) => d.field === field);
  return found ? { label: found.label, ownerModule: found.ownerModule, reason: found.reason } : undefined;
}

/** 表格列定义。店铺列也在列里（显示「—」），缺席比「假装支持」更容易被误读。 */
export const listColumns: { key: string; label: string; unavailable?: boolean }[] = [
  { key: 'caseNo', label: '售后号' },
  { key: 'orderNo', label: '关联订单' },
  { key: 'platformSource', label: '平台（无店铺）' },
  { key: 'caseType', label: '类型' },
  { key: 'status', label: '状态' },
  { key: 'requestedAmount', label: '申请金额' },
  { key: 'reason', label: '原因' },
  { key: 'timeliness', label: '时效倒计时' },
  { key: 'owner', label: '责任人' },
  { key: 'authorizationState', label: '授权' },
  { key: 'ticketNo', label: '工单' },
  { key: 'receivedAt', label: '受理时间' },
];

/** 单次批量上限由后端 capabilities 报出，前端不自己写死一个数字。 */
export function bulkMaxSize(
  caps: AftersalesCaseCapabilities | null | undefined,
  fallback: number,
): number {
  const max = caps?.bulkOperations?.reduce((acc, op) => Math.max(acc, op.maxBatchSize), 0);
  return max && max > 0 ? max : fallback;
}

/** 三件批量操作在界面上用的中文标签（顺序固定：授权 / 备注 / 转工单）。 */
export const bulkOperations: { code: 'AUTHORIZE' | 'NOTE' | 'ESCALATE'; label: string }[] = [
  { code: 'AUTHORIZE', label: '授权' },
  { code: 'NOTE', label: '备注' },
  { code: 'ESCALATE', label: '转工单' },
];
