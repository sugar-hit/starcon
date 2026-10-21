/**
 * 11.4.1.1 平台纠纷的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、
 * 速卖通纠纷）：类型、金额、平台时限、举证要求、我方立场、结果记录。」
 * 输出：「纠纷单 + 时效倒计时 + 举证清单。」
 * 加粗句：「<b>平台纠纷有严格举证时限</b>，超期自动判败，是硬性时效要求。」
 *
 * <b>这一层对那条加粗句负三件事</b>：
 *
 * <ol>
 *   <li><b>倒计时由绝对时刻派生，不由接口的剩余秒数派生</b>：
 *       {@link remainingSeconds} 只认 {@code evidenceDeadline} 这一个事实，
 *       每次渲染现算。接口返回的 {@code secondsRemaining} 是**请求那一刻**的快照，
 *       页面停在那里不动就会慢慢变成假数据。</li>
 *   <li><b>「超期自动判负」在界面上必须看得见</b>：{@link deadlineTone} 把
 *       已超期的单据标成警示色，{@link deadlineText} 直接说「已超期」而不是
 *       显示一个负数。</li>
 *   <li><b>不提供「手动标记超期判负」的调用</b>：超期判负是系统做的
 *       （后端 DB 守卫①），一个能提前把自己判负的按钮只会让人用它来提前结案。</li>
 * </ol>
 *
 * <p>⚠️ <b>金额不由前端折算</b>：跨币种**按币种分组**展示，
 * 不做固定汇率折算 —— 折算出来的合计是一个没有意义的数字。
 */
import type {
  PlatformDisputeCapabilities,
  PlatformDisputeCreate,
  PlatformDisputeDetail,
  PlatformDisputePage,
  PlatformDisputePositionUpdate,
  PlatformDisputeResolve,
  PlatformDisputeSettlementConfirm,
  PlatformDisputeSettlementCreate,
  PlatformDisputeSettlementReject,
  PlatformDisputeSubmitEvidence,
  PlatformDisputeWithdraw,
  PlatformEvidenceItemCreate,
  PlatformEvidenceMark,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/platform-disputes} 同前缀。 */
const base = 'trade/platform-disputes';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  //   所以这里按有没有 body 分成两种完整对象，而不是合并一个可能半空的。
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

/**
 * 把筛选对象编成 query string。
 *
 * ⚠️ 空串与 undefined 都要跳过：传一个空 `status=` 会让后端收到
 * 「按空状态筛选」而不是「不筛状态」，两种读法在列表页上表现完全不同。
 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    search.set(key, String(value));
  }
  const suffix = search.toString();
  return suffix ? `?${suffix}` : '';
}

// ==========================================================================
// 能力声明
// ==========================================================================

export const fetchPlatformDisputeCapabilities = () =>
  call<PlatformDisputeCapabilities>(`${base}/capabilities`);

// ==========================================================================
// 纠纷单
// ==========================================================================

export const fetchPlatformDisputes = (params: {
  page?: number;
  size?: number;
  status?: string;
  disputeType?: string;
  platformCode?: string;
  currency?: string;
  ourPosition?: string;
  liabilityStage?: string;
  settlementStatus?: string;
  overdueOnly?: boolean;
  minAmount?: number;
  maxAmount?: number;
  from?: string;
  to?: string;
  keyword?: string;
  sort?: string;
} = {}) => call<PlatformDisputePage>(`${base}${query(params)}`);

export const fetchPlatformDispute = (id: string) =>
  call<PlatformDisputeDetail>(`${base}/${id}`);

/** 登记平台纠纷。⚠️ 平台名要显式给：平台主数据在 channel 独立库，这里落的是快照。 */
export const createPlatformDispute = (body: PlatformDisputeCreate) =>
  call<PlatformDisputeDetail>(`${base}`, { method: 'POST', body: JSON.stringify(body) });

// ==========================================================================
// 举证清单
// ==========================================================================

export const addPlatformEvidenceItem = (id: string, body: PlatformEvidenceItemCreate) =>
  call<PlatformDisputeDetail>(`${base}/${id}/evidence-items`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/**
 * 更新一项举证材料的状态。
 *
 * ⚠️ 确认缺失**必须**写清缺什么（后端表级 CHECK 钉死，前端也不给绕过的地方）。
 */
export const markPlatformEvidence = (
  id: string,
  itemId: string,
  body: PlatformEvidenceMark,
) =>
  call<PlatformDisputeDetail>(`${base}/${id}/evidence-items/${itemId}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const removePlatformEvidenceItem = (id: string, itemId: string) =>
  call<PlatformDisputeDetail>(`${base}/${id}/evidence-items/${itemId}`, { method: 'DELETE' });

/** 进入举证准备（已立案 → 举证准备中）。必须已有举证清单。 */
export const startPlatformEvidence = (id: string, expectedVersion: number) =>
  call<PlatformDisputeDetail>(`${base}/${id}/start-evidence?expectedVersion=${expectedVersion}`, {
    method: 'POST',
  });

// ==========================================================================
// 立场与结果
// ==========================================================================

export const updatePlatformDisputePosition = (
  id: string,
  body: PlatformDisputePositionUpdate,
) =>
  call<PlatformDisputeDetail>(`${base}/${id}/position`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/**
 * 提交举证。
 *
 * ⚠️ **必举证项必须全部具备**且**未超期** —— 后端两道都拦（清单齐全 + 超期判负）。
 */
export const submitPlatformEvidence = (id: string, body: PlatformDisputeSubmitEvidence) =>
  call<PlatformDisputeDetail>(`${base}/${id}/submit-evidence`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/**
 * 记录平台裁决结果。
 *
 * ⚠️ 结果码**不接受**「超期自动判负」：那是系统判的，不接受人工声明。
 */
export const resolvePlatformDispute = (id: string, body: PlatformDisputeResolve) =>
  call<PlatformDisputeDetail>(`${base}/${id}/resolve`, { method: 'POST', body: JSON.stringify(body) });

export const withdrawPlatformDispute = (id: string, body: PlatformDisputeWithdraw) =>
  call<PlatformDisputeDetail>(`${base}/${id}/withdraw`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ==========================================================================
// 结算影响
// ==========================================================================

/**
 * 登记结算影响。
 *
 * ⚠️ **登记 ≠ 生效**：后端把生效金额留成 0，等财务确认。
 */
export const registerPlatformDisputeSettlement = (
  id: string,
  body: PlatformDisputeSettlementCreate,
) =>
  call<PlatformDisputeDetail>(`${base}/${id}/settlements`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 财务确认结算生效（**唯一**能让结算影响生效的入口）。 */
export const confirmPlatformDisputeSettlement = (
  id: string,
  settlementId: string,
  body: PlatformDisputeSettlementConfirm,
) =>
  call<PlatformDisputeDetail>(`${base}/${id}/settlements/${settlementId}/confirm`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 财务驳回结算影响。 */
export const rejectPlatformDisputeSettlement = (
  id: string,
  settlementId: string,
  body: PlatformDisputeSettlementReject,
) =>
  call<PlatformDisputeDetail>(`${base}/${id}/settlements/${settlementId}/reject`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ==========================================================================
// 纯函数：界面上要自己讲清楚的规则
// ==========================================================================

/** 纠纷单状态。顺序 = 状态机推进顺序。 */
export const DISPUTE_STATUS_OPTIONS = [
  { code: 'OPEN', label: '已立案' },
  { code: 'EVIDENCE_PREPARING', label: '举证准备中' },
  { code: 'EVIDENCE_SUBMITTED', label: '举证已提交' },
  { code: 'RESOLVED_WON', label: '我方胜诉' },
  { code: 'RESOLVED_LOST', label: '我方败诉' },
  { code: 'WITHDRAWN', label: '我方撤诉' },
] as const;

/** 纠纷类型（蓝图点名的三种 + 兜底）。 */
export const DISPUTE_TYPE_OPTIONS = [
  { code: 'A_TO_Z', label: 'Amazon A-to-Z 索赔' },
  { code: 'EBAY_DISPUTE', label: 'eBay 买家争议' },
  { code: 'ALIEXPRESS_DISPUTE', label: '速卖通纠纷' },
  { code: 'OTHER', label: '其它平台纠纷' },
] as const;

/** 我方立场。 */
export const POSITION_OPTIONS = [
  { code: 'ACCEPT', label: '承认责任' },
  { code: 'REJECT', label: '否认责任' },
  { code: 'PARTIAL_ACCEPT', label: '部分承认' },
  { code: 'NEGOTIATE', label: '协商处理' },
  { code: 'UNDETERMINED', label: '立场待定' },
] as const;

/** 责任判定。 */
export const LIABILITY_STAGE_OPTIONS = [
  { code: 'MERCHANT_PRODUCT', label: '商品问题' },
  { code: 'MERCHANT_FULFILLMENT', label: '履约问题' },
  { code: 'LOGISTICS', label: '物流问题' },
  { code: 'BUYER', label: '买家问题' },
  { code: 'PLATFORM', label: '平台问题' },
  { code: 'UNDETERMINED', label: '责任待定' },
] as const;

/** 举证材料类型。 */
export const EVIDENCE_TYPE_OPTIONS = [
  { code: 'ORDER_FULFILLMENT_PROOF', label: '订单履约证明' },
  { code: 'LOGISTICS_TRACKING_PROOF', label: '物流轨迹证明' },
  { code: 'INVOICE', label: '发票 / 付款凭证' },
  { code: 'PRODUCT_PHOTO', label: '商品实物照片' },
  { code: 'PRODUCT_DESCRIPTION_SNAPSHOT', label: '商品描述快照' },
  { code: 'COMMUNICATION_LOG', label: '与买家沟通记录' },
  { code: 'PLATFORM_MESSAGE', label: '平台消息截图' },
  { code: 'OTHER', label: '其它材料' },
] as const;

/** 单项举证材料的状态（三态：待核查 / 已具备 / 确认缺失）。 */
export const EVIDENCE_STATE_OPTIONS = [
  { code: 'PENDING', label: '待核查' },
  { code: 'PROVIDED', label: '已具备' },
  { code: 'MISSING', label: '确认缺失' },
] as const;

/** 结算口径（纠纷单头上的口径）。 */
export const SETTLEMENT_STATE_OPTIONS = [
  { code: 'NOT_APPLIED', label: '未登记结算影响' },
  { code: 'PENDING_FINANCE_CONFIRM', label: '待财务确认' },
  { code: 'APPLIED', label: '结算影响已生效' },
  { code: 'FINANCE_REJECTED', label: '财务已驳回' },
] as const;

/** 平台裁决结果码（**不含**「超期自动判负」—— 那是系统判的）。 */
export const RESOLUTION_OPTIONS = [
  { code: 'PLATFORM_RULING_FOR', label: '平台判我方胜诉' },
  { code: 'PLATFORM_RULING_AGAINST', label: '平台判我方败诉' },
  { code: 'MUTUAL_AGREEMENT', label: '双方达成一致' },
] as const;

/**
 * 平台码与纠纷类型的对应（与后端 {@code PlatformDisputeType.requiredPlatformCode} 一致）。
 *
 * ⚠️ 蓝图点名的三种纠纷各自绑定一个平台，后端 DB 守卫⑧ 也钉着一道。
 * 前端给同样的提示，是为了**在提交之前**就说清楚，而不是等后端 409。
 */
export const PLATFORM_FOR_TYPE: Record<string, string> = {
  A_TO_Z: 'AMAZON',
  EBAY_DISPUTE: 'EBAY',
  ALIEXPRESS_DISPUTE: 'ALIEXPRESS',
};

/** 常用平台码（渠道在 channel 独立库，这里只是录入时的候选，不是平台主数据）。 */
export const PLATFORM_OPTIONS = [
  { code: 'AMAZON', label: 'Amazon' },
  { code: 'EBAY', label: 'eBay' },
  { code: 'ALIEXPRESS', label: '速卖通' },
  { code: 'SHOPIFY', label: 'Shopify' },
  { code: 'TMALL', label: '天猫' },
  { code: 'LAZADA', label: 'Lazada' },
  { code: 'TEMU', label: 'Temu' },
  { code: 'WALMART', label: 'Walmart' },
  { code: 'ALIBABA', label: '1688' },
] as const;

/** 平台码与类型不一致时的说明（界面上要自己讲清楚为什么不能提交）。 */
export function platformTypeMismatchReason(
  disputeType: string | null | undefined,
  platformCode: string | null | undefined,
): string | null {
  if (!disputeType || !platformCode) return null;
  const required = PLATFORM_FOR_TYPE[disputeType];
  if (!required) return null;
  if (required === platformCode) return null;
  const label = DISPUTE_TYPE_OPTIONS.find((o) => o.code === disputeType)?.label ?? disputeType;
  return `「${label}」只属于平台 ${required}，与当前平台码 ${platformCode} 不一致（库里也存不进去）`;
}

/** 剩余秒数：由**绝对截止时刻**现算，不看接口给的快照。 */
export function remainingSeconds(
  deadline: string | null | undefined,
  now: number = Date.now(),
): number {
  if (!deadline) return 0;
  const target = new Date(deadline).getTime();
  if (Number.isNaN(target)) return 0;
  // ⚠️ 过了截止时刻就是 0，**不是负数** —— 负数在列表里会被当成一个奇怪的数字划过去。
  return Math.max(0, Math.round((target - now) / 1000));
}

/**
 * 倒计时文案。
 *
 * ⚠️ 已超期显示「已超期」而不是负数 —— 负数在列表里会被当成一个奇怪的数字划过去。
 */
export function countdownText(
  deadline: string | null | undefined,
  now: number = Date.now(),
): string {
  const seconds = remainingSeconds(deadline, now);
  if (seconds <= 0) return '已超期';
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  if (days > 0) return `${days} 天 ${hours} 小时`;
  if (hours > 0) return `${hours} 小时 ${minutes} 分`;
  if (minutes > 0) return `${minutes} 分 ${rest} 秒`;
  return `${rest} 秒`;
}

/** 时限状态的徽章色调（已超期 = danger，即将到期 = warning）。 */
export function deadlineTone(
  state: string | null | undefined,
): 'neutral' | 'info' | 'warning' | 'danger' {
  switch (state) {
    case 'PASSED':
      return 'danger';
    case 'URGENT':
      return 'warning';
    case 'RELEASED':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 时限文案：时限已解除时不显示倒计时（提交举证之后倒计时已经没有意义了）。 */
export function deadlineText(
  deadline: string | null | undefined,
  state: string | null | undefined,
  now: number = Date.now(),
): string {
  if (state === 'RELEASED') return '时限已解除';
  return countdownText(deadline, now);
}

/**
 * 结算分录的**生效提示**。
 *
 * <p>蓝图关系句「争议金额影响结算」在界面上必须看得见：
 * 登记了结算影响但还没确认时，金额栏要显示「登记 40 / 生效 0」。
 */
export function settlementEffectiveHint(settlement: {
  requestedAmount: number;
  effectiveAmount: number;
  settlementStatus: string;
}): string {
  if (settlement.settlementStatus === 'CONFIRMED') {
    return `已生效：${settlement.effectiveAmount}`;
  }
  if (settlement.settlementStatus === 'REJECTED') {
    return `已被财务驳回，生效金额 0（登记 ${settlement.requestedAmount}）`;
  }
  return `已登记 ${settlement.requestedAmount}，**尚未生效**（生效 0，等待财务确认）`;
}

/** 结算分录是否处于「已登记但未生效」状态（页面上要标成醒目的待办）。 */
export function isSettlementPending(settlement: { settlementStatus: string }): boolean {
  return settlement.settlementStatus === 'PENDING_FINANCE_CONFIRM';
}

/**
 * 取某个动作的禁用理由。
 *
 * <p>⚠️ 后端在 {@code actions} 里已经给了 {@code blockedReason}，前端只是转述 ——
 * 规则只有一份（后端那份），前端不自己再编一套。
 */
export function actionBlockedReason(
  actions: { code: string; allowed: boolean; blockedReason: string | null }[] | undefined,
  code: string,
): string | null {
  const action = actions?.find((a) => a.code === code);
  if (!action) return '该动作在当前纠纷单上不可用';
  if (action.allowed) return null;
  return action.blockedReason || '当前状态不允许这个动作';
}

/** 金额格式化：后端给的是 number（契约把 NUMERIC 映射成 number），这里只做展示。 */
export function formatAmount(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 时间格式化。契约里是 ISO 字符串。 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 举证截止时刻的输入格式（datetime-local 用的是本地时间字符串）。 */
export function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return '';
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
}
