/**
 * 11.3.1.1 退款申请的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「发起退款：选择退款方式（原路/平台/余额）、金额（可部分）、
 * 原因，校验累计退款不超过可退余额，提交审批后交财务执行。」
 * 输出：「退款申请单（待审/已批/执行中/成功/失败）+ 可退余额更新。」
 * 加粗句：「<b>可退余额校验必须在服务端做</b>——重复提交退款是资损事故的高发点。」
 *
 * <p><b>这一层对那条加粗句负四件事</b>：
 *
 * <ol>
 *   <li><b>页面上没有任何一个输入框叫「可退余额」</b>：余额只读，从服务端来
 *       （{@link fetchRefundableBalance}）。前端**不自己算** —— 前端算的那份一旦和后端
 *       分叉，运营就会按前端那份填金额，然后被后端拒，而两边都说自己算的是对的。</li>
 *   <li><b>五个占用桶逐个显示</b>（{@link balanceRows}）：看到「可退 0」时必须能立刻答出
 *       「是已经退掉了、还是有一笔在途、还是被对账争议卡住了」—— 三种情况补救动作完全不同。</li>
 *   <li><b>审批规则默认 fail-closed</b>：{@code policySource === 'DEFAULT_NO_POLICY'} 时
 *       页面必须显示「本租户还没配规则，按最严格档执行」，而不是显示一个编造的阈值。</li>
 *   <li><b>执行状态与申请状态分开显示</b>：执行记录停在 {@code UNKNOWN} 时，
 *       申请单停在「执行中」而**不是**失败（{@link executionTone} / {@link executionText}）。</li>
 * </ol>
 */
import type {
  RefundApplicationFilterOptions,
  RefundBalance,
  RefundCapabilities,
  RefundDetail,
  RefundPage,
  RefundPolicyView,
  RefundSectionCapability,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/refund-applications} 同前缀。 */
const base = 'trade/refund-applications';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  const response = init
    ? await fetch(`/api/${path}`, {
        credentials: 'include',
        ...init,
        ...(init.body ? { headers: { 'Content-Type': 'application/json' } } : {}),
      })
    : await fetch(`/api/${path}`, { credentials: 'include' });
  if (!response.ok) {
    // ApiError 的签名是 (status, message, body)。
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}

export const fetchRefundCapabilities = () => call<RefundCapabilities>(`${base}/capabilities`);

export const fetchRefundList = (params: {
  page?: number;
  size?: number;
  status?: string;
  method?: string;
  reason?: string;
  pendingReviewOnly?: boolean;
  keyword?: string;
  sort?: string;
}) => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return call<RefundPage>(suffix ? `${base}?${suffix}` : base);
};

export const fetchRefundFilters = () => call<RefundApplicationFilterOptions>(`${base}/filters`);

export const fetchRefundDetail = (id: string) => call<RefundDetail>(`${base}/${id}`);

export const fetchRefundableBalance = (caseId: string) =>
  call<RefundBalance>(`${base}/refundable-balance?caseId=${caseId}`);

export const fetchRefundPolicy = (currency: string, amount?: string) =>
  call<RefundPolicyView>(
    amount
      ? `${base}/approval-policy?currency=${currency}&amount=${amount}`
      : `${base}/approval-policy?currency=${currency}`,
  );

export const saveRefundPolicy = (body: unknown) =>
  call<RefundPolicyView>(`${base}/approval-policy`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const createRefundApplication = (body: unknown) =>
  call<RefundDetail>(base, { method: 'POST', body: JSON.stringify(body) });

export const updateRefundApplication = (id: string, body: unknown) =>
  call<RefundDetail>(`${base}/${id}`, { method: 'PUT', body: JSON.stringify(body) });

export const submitRefundApplication = (id: string, body: unknown) =>
  call<RefundDetail>(`${base}/${id}/submit`, { method: 'POST', body: JSON.stringify(body) });

export const decideRefundApplication = (id: string, body: unknown) =>
  call<RefundDetail>(`${base}/${id}/decide`, { method: 'POST', body: JSON.stringify(body) });

export const dispatchRefundApplication = (id: string, body: unknown) =>
  call<RefundDetail>(`${base}/${id}/dispatch`, { method: 'POST', body: JSON.stringify(body) });

export const recordRefundOutcome = (id: string, body: unknown) =>
  call<RefundDetail>(`${base}/${id}/execution-outcome`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const cancelRefundApplication = (id: string, body: unknown) =>
  call<RefundDetail>(`${base}/${id}/cancel`, { method: 'POST', body: JSON.stringify(body) });

// ================================================================ 展示口径

/** 申请状态 → 徽标色调。在途（还要管）与终态（办完了）必须一眼分得开。 */
export function stateTone(status: string): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'PENDING_APPROVAL':
    case 'SECOND_APPROVAL_PENDING':
      return 'warning';
    case 'APPROVED':
    case 'SECOND_APPROVED':
      return 'info';
    case 'EXECUTING':
      return 'info';
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
    case 'REJECTED':
      return 'danger';
    case 'CANCELLED':
    case 'DRAFT':
    default:
      return 'neutral';
  }
}

/**
 * 退款执行状态 → 徽标色调。
 *
 * <p>⚠️ {@code UNKNOWN} 用 **warning** 而不是 **danger**：外部超时的结果未知，
 * **不是失败**。标成红色会让运营把它当成「退失败了」去点重试 —— 而重试一次退款的代价是真金白银。
 */
export function executionTone(state: string | null | undefined): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (state) {
    case 'SUCCEEDED':
      return 'success';
    case 'IN_FLIGHT':
    case 'NOT_INITIATED':
      return 'info';
    case 'UNKNOWN':
      return 'warning';
    case 'FAILED':
      return 'danger';
    case 'DISCREPANCY':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 金额。⚠️ 一律按字符串传入服务端是 BigDecimal 口径，这里只负责显示。 */
export function amount(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * 可退余额那一格的读法。
 *
 * <p>⚠️ **可退 0** 与 **可退为负** 必须分开显示：前者是额度刚好退光，后者是**已经超了**，
 * 补救动作完全不同（一个可以再退一笔，一个要先去查账）。截断成 0 会把两者变成同一个「—」。
 */
export function availableText(balance: RefundBalance): {
  value: string;
  tone: 'neutral' | 'success' | 'warning' | 'danger';
} {
  const n = Number(balance.availableAmount);
  if (n < 0) return { value: `已超 ${amount(-n)}`, tone: 'danger' };
  if (n === 0) return { value: '额度已退光', tone: 'warning' };
  return { value: amount(n), tone: 'success' };
}

/** 五个占用桶逐行 —— 蓝图加粗句「已退 + 在途」在页面上的落点。 */
export function balanceRows(balance: RefundBalance): { label: string; value: string; hint?: string }[] {
  return [
    { label: '可退基数（售后单申请金额）', value: amount(balance.baseAmount) },
    { label: '已退（成功执行记录）', value: `−${amount(balance.settledAmount)}` },
    { label: '在途 · 已派发 / 结果未知', value: `−${amount(balance.dispatchedAmount)}` },
    { label: '在途 · 已登记待发起', value: `−${amount(balance.registeredAmount)}` },
    { label: '待审 / 已批占用', value: `−${amount(balance.pendingAmount)}` },
    {
      label: '对账争议（按宁可拦住也不能放过计入）',
      value: `−${amount(balance.disputedAmount)}`,
      hint: '11.3.1.2：对不上就当还没退',
    },
    { label: '可退余额', value: amount(balance.availableAmount) },
  ];
}

/**
 * 审批规则的读法。
 *
 * <p>⚠️ **没有配置时必须显示成「未配置 · 按最严格档执行」**，不能显示阈值 0 ——
 * 后者会被读成「0 元以下不用第二个人签」，而真相是「任何金额都要两个人」。
 */
export function policyText(policy: RefundPolicyView): { label: string; tone: 'neutral' | 'warning' } {
  if (policy.policySource === 'DEFAULT_NO_POLICY') {
    return { label: '未配置 · 任何金额都要两人签字（fail-closed）', tone: 'warning' };
  }
  const threshold = Number(policy.dualApprovalThreshold);
  if (threshold <= 0) return { label: '任何金额都要两人签字', tone: 'warning' };
  return {
    label: `金额 ≥ ${amount(threshold)} ${policy.currency} 需两人签字，低于则单签`,
    tone: 'neutral',
  };
}

/** 退款方式标签。 */
export function methodText(method: string): string {
  switch (method) {
    case 'ORIGINAL':
      return '原路退回';
    case 'PLATFORM':
      return '平台侧退款';
    case 'BALANCE':
      return '退回余额';
    default:
      return method;
  }
}

/** 退款原因标签。 */
export function reasonText(reason: string): string {
  switch (reason) {
    case 'RETURN_REFUND':
      return '退货退款';
    case 'CANCELLATION':
      return '取消订单退款';
    case 'WRONG_AMOUNT':
      return '退款金额有误';
    case 'GOODWILL':
      return '赔付';
    case 'OTHER':
      return '其他（必须写说明）';
    default:
      return reason;
  }
}

/**
 * 执行结果提示文案。
 *
 * <p>⚠️ UNKNOWN 这一格是**整个页面最重要的提示**：外部超时不等于失败，
 * 必须原样把后端给的 {@code executionStateNote} 显示出来，而不是自己重写一句
 * 「渠道处理中」—— 后者会被读成「再等等就好」，而真相是「必须先查证再决定」。
 */
export function executionText(detail: Pick<RefundDetail, 'executionState' | 'executionStateNote'>): string {
  return detail.executionStateNote;
}

/** 留痕的一行文案。 */
export function eventText(event: {
  eventTypeLabel: string;
  fromStatus: string | null;
  toStatus: string;
  amountSnapshot: number;
  occurredAt: string;
}): string {
  const from = event.fromStatus ? `${event.fromStatus} → ` : '';
  return `${event.eventTypeLabel}（${from}${event.toStatus}）· ${amount(event.amountSnapshot)}`;
}

/** 未交付模块的说明文字（capabilities 里逐条给了 reason，直接显示，不要自己重写）。 */
export function unavailableText(module: {
  ownerModule: string | null;
  reason: string | null;
}): string {
  return `未交付（归属 ${module.ownerModule ?? '—'}）：${module.reason ?? '—'}`;
}

/** 动作按钮：不可用时把理由原样带出来（按钮变灰而不给理由会让人以为系统坏了）。 */
export function actionButton(action: {
  label: string;
  allowed: boolean;
  blockingReason: string | null;
  unavailableReason: string | null;
} | null | undefined): { label: string; disabled: boolean; reason: string | null } {
  if (!action) return { label: '—', disabled: true, reason: '后端未给出动作入口' };
  return {
    label: action.label,
    disabled: !action.allowed,
    reason: action.blockingReason ?? action.unavailableReason ?? null,
  };
}

/** 「刻意不做的事」清单：页面照着显示，而不是让用户自己猜为什么找不到按钮。 */
export function boundaryRows(capabilities: RefundCapabilities): { scope: string; owner: string; reason: string }[] {
  return capabilities.boundaries.map((b) => ({
    scope: b.boundary,
    owner: b.ownerModule,
    reason: b.reason,
  }));
}

/** 能力区块：可用的显示落点，不可用���把归属模块号带出来。 */
export function sectionRows(section: RefundSectionCapability): string {
  return section.available
    ? `${section.landsIn ?? '已实现'}`
    : `未交付（归属 ${section.ownerModule ?? '—'}）`;
}
