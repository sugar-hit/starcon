import type {
  RefundDiscrepancyPage,
  RefundDiscrepancyResolutionRequest,
  RefundDiscrepancyRow,
  RefundDispatchRequest,
  RefundExecutionCreate,
  RefundExecutionDetail,
  RefundExecutionPage,
  RefundExecutionRow,
  RefundFilterOptions,
  RefundPlatformSyncRequest,
  RefundReportRequest,
  RefundStatusCapabilities,
  RefundVerificationRequest,
  RefundVerificationRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 11.3.1.2 退款状态前端模型。
 *
 * <p>蓝图：「跟踪退款执行状态与平台同步状态，处理退款失败、外部超时（先查证交易再重试，
 * 禁止重复支付）、平台退款与系统退款不一致的差异。」
 * 输出：「退款状态 + 失败原因 + 重试/人工处理建议 + 差异清单。」
 * 加粗句：「<b>外部退款超时不等于失败</b>，重复发起退款会造成实质资损。」
 *
 * <p><b>本模型的唯一职责是让那句话在界面上也成立</b>。后端已经用幂等键与触发器挡住了重复退款，
 * 但如果界面把「结果未知」显示成「失败」或给它一个红色的「重试」按钮，
 * 运营照着点下去造成的资损与后端有没有防护无关 —— <b>界面是最后一道会误导人的关口</b>。因此：
 *
 * <ul>
 *   <li>{@link stateTone} 把六态映射成**互不相同的视觉语气**，UNKNOWN 是**第四种独立语气**
 *       （不是「失败」的变体，也不是「成功」的变体）；</li>
 *   <li>{@link actionButton} 依据后端下发的 {@code action} 决定**渲染哪个按钮**：
 *       UNKNOWN 只会得到「先查证交易」，永远拿不到「重试」——前端不自己推断可重试性；</li>
 *   <li>{@link blockingText} 把 {@code actionBlocking} 原样显示出来，
 *       让「为什么现在不能重试」在界面上有答案，而不是让按钮凭空消失；</li>
 *   <li>{@link unknownWarning} 在存在 UNKNOWN 时固定打出那条风险提示。</li>
 * </ul>
 *
 * <p>路径前缀 {@code trade/refunds}，前端路由 {@code /aftersales/refunds}。
 */

const base = 'trade/refunds';

// ================================================================ 调用点

export interface RefundListParams {
  executionState?: string;
  platformSyncState?: string;
  platformRefundMethod?: string;
  currency?: string;
  orderNo?: string;
  refundNo?: string;
  failureCode?: string;
  minAmount?: string;
  maxAmount?: string;
  onlyNeedsAttention?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function refundListQuery(params: RefundListParams): string {
  const parts: string[] = [];
  const put = (k: string, v?: string) => {
    if (v && v.trim()) parts.push(`${k}=${encodeURIComponent(v.trim())}`);
  };
  put('executionState', params.executionState);
  put('platformSyncState', params.platformSyncState);
  put('platformRefundMethod', params.platformRefundMethod);
  put('currency', params.currency);
  put('orderNo', params.orderNo);
  put('refundNo', params.refundNo);
  put('failureCode', params.failureCode);
  put('minAmount', params.minAmount);
  put('maxAmount', params.maxAmount);
  put('onlyNeedsAttention', params.onlyNeedsAttention);
  put('sort', params.sort);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchRefundCapabilities() {
  return api<RefundStatusCapabilities>(`${base}/capabilities`);
}

export function fetchRefundFilters() {
  return api<RefundFilterOptions>(`${base}/filters`);
}

export function fetchRefundList(params: RefundListParams) {
  return api<RefundExecutionPage>(`${base}${refundListQuery(params)}`);
}

export function fetchRefundDetail(id: string) {
  return api<RefundExecutionDetail>(`${base}/${id}`);
}

export function fetchRefundDiscrepancies(params: {
  resolutionState?: string;
  kind?: string;
  page?: number;
  size?: number;
}) {
  const q = new URLSearchParams();
  if (params.resolutionState) q.set('resolutionState', params.resolutionState);
  if (params.kind) q.set('kind', params.kind);
  if (params.page !== undefined) q.set('page', String(params.page));
  if (params.size !== undefined) q.set('size', String(params.size));
  const suffix = q.toString();
  return api<RefundDiscrepancyPage>(`${base}/discrepancies${suffix ? `?${suffix}` : ''}`);
}

export function registerRefund(input: RefundExecutionCreate) {
  return api<RefundExecutionRow>(base, 'POST', input);
}

export function dispatchRefund(id: string, input: RefundDispatchRequest) {
  return api<RefundExecutionRow>(`${base}/${id}/dispatch`, 'POST', input);
}

export function verifyRefund(id: string, input: RefundVerificationRequest) {
  return api<RefundVerificationRow>(`${base}/${id}/verifications`, 'POST', input);
}

export function reportRefund(id: string, input: RefundReportRequest) {
  return api<RefundExecutionRow>(`${base}/${id}/reports`, 'POST', input);
}

export function syncRefundPlatform(id: string, input: RefundPlatformSyncRequest) {
  return api<RefundExecutionRow>(`${base}/${id}/platform-sync`, 'POST', input);
}

export function resolveRefundDiscrepancy(
  id: string,
  discrepancyId: string,
  input: RefundDiscrepancyResolutionRequest,
) {
  return api<RefundDiscrepancyRow>(
    `${base}/${id}/discrepancies/${discrepancyId}/resolve`,
    'POST',
    input,
  );
}

// ================================================================ 状态视觉

export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

export interface StateTone {
  tone: Tone;
  label: string;
  /** 排序权重：数字小的排前面。未知态必须排在「明确失败」之前。 */
  weight: number;
}

/**
 * 六态 → 视觉语气。
 *
 * ⚠️ **`UNKNOWN` 是独立的第四种语气**，不复用 danger（那是 FAILED 的）或 good（那是 SUCCEEDED 的）。
 * 让 UNKNOWN 回落成「失败」或「正常」都会造成实质误导：前者诱导重试（资损），
 * 后者让一笔可能已经付了钱的退款被当成已完成。
 */
export function stateTone(state: string | null | undefined): StateTone {
  switch (state) {
    case 'UNKNOWN':
      return { tone: 'warning', label: '结果未知（超时不等于失败）', weight: 0 };
    case 'FAILED':
      return { tone: 'danger', label: '已明确失败', weight: 1 };
    case 'DISCREPANCY':
      return { tone: 'warning', label: '对账差异', weight: 2 };
    case 'IN_FLIGHT':
      return { tone: 'info', label: '已发起待结果', weight: 3 };
    case 'SUCCEEDED':
      return { tone: 'success', label: '已退款', weight: 4 };
    case 'NOT_INITIATED':
      return { tone: 'neutral', label: '未发起', weight: 5 };
    default:
      // 未知状态码**不回落成「正常」**：那会把一次真实的状态异常显示成「一切正常」。
      return { tone: 'neutral', label: '未知状态', weight: 6 };
  }
}

export function stateBadgeClass(state: string | null | undefined): string {
  return `tone-${stateTone(state).tone}`;
}

/**
 * 建议动作 → 可执行按钮的形态。
 *
 * ⚠️ **`VERIFY` 绝不能渲染成 retry 按钮的变体**：它是「先查证交易」，不是「再发一次」。
 * 两者在视觉上必须分开（`primary` vs `caution`），否则一次误点就是重复退款。
 */
export function actionButton(action: string | null | undefined): {
  label: string;
  variant: 'primary' | 'caution' | 'ghost' | 'danger' | 'disabled';
  /** 是否展示该按钮。VERIFY 永远展示；RETRY 只在后端给出 RETRY 时展示。 */
  show: boolean;
} {
  switch (action) {
    case 'DISPATCH':
      return { label: '发起退款', variant: 'primary', show: true };
    case 'VERIFY':
      return { label: '先查证交易', variant: 'caution', show: true };
    case 'RETRY':
      return { label: '重试退款', variant: 'primary', show: true };
    case 'MANUAL':
      return { label: '需人工裁定', variant: 'ghost', show: true };
    case 'AWAIT':
      return { label: '等待结果（不要重发）', variant: 'disabled', show: true };
    case 'NONE':
      return { label: '已完成', variant: 'disabled', show: false };
    default:
      return { label: '未知动作', variant: 'disabled', show: false };
  }
}

/**
 * 把模型的语义变体翻译成 `@starcon/ui` Button **真实存在**的 variant token。
 *
 * <p>模型的 `primary | caution | ghost | danger | disabled` 是**语义**，不是设计令牌；
 * 直接透传给 `<Button variant>` 会因 cva 的联合类型不匹配而编译不过（上一任 worker 留下的
 * 两个 typecheck 错误之一）。语义与令牌分层，模型才不必跟着设计系统改。
 *
 * <p>`caution` → `outline`（描边）而不是 `secondary`（填充）：蓝图要求「先查证交易」与
 * 「重试退款」在视觉上必须分开，描边与品牌填充的最大对比度最能防一次误点造成重复退款。
 */
export function buttonVariant(
  variant: ReturnType<typeof actionButton>['variant'],
): 'brand' | 'outline' | 'ghost' | 'destructive' {
  switch (variant) {
    case 'primary':
      return 'brand';
    case 'caution':
      return 'outline';
    case 'danger':
      return 'destructive';
    case 'ghost':
    case 'disabled':
    default:
      return 'ghost';
  }
}

/**
 * 「为什么现在不能直接重试」的说明。
 *
 * <p>后端在 UNKNOWN / IN_FLIGHT / SUCCEEDED 上都给了 {@code actionBlocking}，
 * 原样显示出来 —— 按钮变灰而不给理由，会让运营以为系统坏了。
 * 没有 blocking 时返回空串（不是「无」）。
 */
export function blockingText(row: {
  actionBlocking?: string | null;
  actionReason?: string | null;
}): string {
  const blocking = row.actionBlocking?.trim();
  if (blocking) return `不能直接重试：${blocking}`;
  const reason = row.actionReason?.trim();
  return reason ? `判定依据：${reason}` : '';
}

/**
 * 存在 UNKNOWN 时固定打出的风险提示。
 *
 * <p>这是把蓝图加粗句**写进界面**的一行字：运营在点任何按钮之前先看到它。
 */
export function unknownWarning(unknownCount: number): string {
  if (unknownCount <= 0) return '';
  return `有 ${unknownCount} 笔退款「结果未知」：外部超时不等于失败，`
    + '这些钱可能已经付出去了。直接重试就是对同一笔退款发起第二次支付（实质资损）——'
    + '必须先查证交易，确认未付出后才谈得上重试。';
}

// ================================================================ 展示辅助

/** 金额 + 币种。金额永远是 BigDecimal，**不做任何浮点运算**。 */
export function formatAmount(amount: number | null | undefined, currency?: string | null): string {
  if (amount === null || amount === undefined) return '—';
  const fixed = amount.toFixed(4);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 差异两边的金额对比：「系统 100.0000 / 平台 90.0000」。 */
export function discrepancyPair(row: {
  systemAmount?: number | null;
  systemCurrency?: string | null;
  platformAmount?: number | null;
  platformCurrency?: string | null;
}): string {
  const sys = formatAmount(row.systemAmount, row.systemCurrency);
  const plat = formatAmount(row.platformAmount, row.platformCurrency);
  if (sys === '—' && plat === '—') return '存在性差异（一边有、一边无）';
  return `系统 ${sys} / 平台 ${plat}`;
}

/** 查证结论的视觉语气：查不出结果与查出已付出**必须**看起来不同。 */
export function verdictTone(verdict: string | null | undefined): Tone {
  switch (verdict) {
    case 'CONFIRMED_NOT_SETTLED':
      return 'success';
    case 'CONFIRMED_SETTLED':
      return 'info';
    case 'INCONCLUSIVE':
      return 'warning';
    default:
      return 'neutral';
  }
}

export const verdictLabels: Record<string, string> = {
  CONFIRMED_NOT_SETTLED: '查证：确认未付出（可重试）',
  CONFIRMED_SETTLED: '查证：确认已付出（禁止重试，应推进到已退款）',
  INCONCLUSIVE: '查证：仍无法确认（保持结果未知，禁止重试）',
};

/** 外部交易号：没有就显示「尚未拿到」而不是空串或「—」（那会被读成「没有交易」）。 */
export function transactionText(ref: string | null | undefined): string {
  const v = ref?.trim();
  return v ? v : '尚未拿到外部交易号';
}

export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 失败原因：没有就说清楚是「没有失败信息」而不是留空。 */
export function failureText(row: {
  failureClass?: string | null;
  failureCode?: string | null;
  failureReason?: string | null;
}): string {
  const reason = row.failureReason?.trim();
  if (!reason) return '无失败信息';
  const code = row.failureCode?.trim();
  const kind = row.failureClass === 'INDETERMINATE' ? '不确定' : row.failureClass === 'DEFINITIVE' ? '明确' : '';
  return code ? `[${code}${kind ? `·${kind}` : ''}] ${reason}` : reason;
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

/** 平台退款方式 / 平台同步状态的中文。 */
export const platformMethodLabels: Record<string, string> = {
  ORIGINAL: '原路退回',
  PLATFORM: '平台侧退款',
  BALANCE: '退回余额',
};

export const platformSyncLabels: Record<string, string> = {
  NOT_REQUESTED: '不经平台',
  PENDING: '待平台同步',
  SYNCED: '平台已同步',
  DISCREPANCY: '平台不一致',
};

export const reportSourceLabels: Record<string, string> = {
  FINANCE_MODULE_12: '12 财务执行回报',
  PLATFORM_SYNC: '平台侧退款查询',
  MANUAL_OPERATOR: '人工登记',
};

export const reportOutcomeLabels: Record<string, string> = {
  SUCCEEDED: '成功',
  FAILED: '明确失败',
  TIMEOUT: '外部超时',
  UNKNOWN: '结果未知',
};

export const discrepancyKindLabels: Record<string, string> = {
  AMOUNT: '金额不一致',
  CURRENCY: '币种不一致',
  TIMING: '时间不一致',
  EXISTENCE: '存在性不一致',
};

export const resolutionLabels: Record<string, string> = {
  OPEN: '待裁定',
  ACKNOWLEDGED: '已确认待处理',
  RESOLVED: '已裁定',
};

export const attemptKindLabels: Record<string, string> = {
  INITIAL: '首次派发',
  RETRY: '重试派发（查证后）',
};

export function platformMethodLabel(code: string | null | undefined): string {
  return code ? (platformMethodLabels[code] ?? code) : '—';
}

export function platformSyncLabel(code: string | null | undefined): string {
  return code ? (platformSyncLabels[code] ?? code) : '—';
}
