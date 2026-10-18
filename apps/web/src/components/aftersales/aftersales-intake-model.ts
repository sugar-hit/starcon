import type {
  AftersalesIntakeCapabilities,
  AftersalesIntakePage,
  AftersalesIntakeResult,
  AftersalesIntakeVerdictView,
  AftersalesOwnerRosterView,
  AftersalesReplySlaView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 11.1.1.3 售后受理前端模型。
 *
 * <p>蓝图：「受理平台或内部发起的售后请求：判定是否在售后期内、是否符合条件、指定责任人与处理方案、
 * 设置回复时限。」输出句：「售后单（待处理）；政策判定结果；责任人分配。」
 * 加粗句：「<b>必退情形（平台强制、收到即退）要自动判定</b>，人工不能为了业绩拖着不退导致平台判责。」
 *
 * <p><b>本模型的唯一职责是让「必退」这件事在界面上没法被忽略</b>：
 *
 * <ul>
 *   <li>{@link mustRefundPresentation} 把判定结论映射成**视觉语气 + 排序权重**，
 *       「必退且已超时」排在最前（rank 0）—— 蓝图的加粗句要的是
 *       「不能为了业绩拖着不退导致平台判责」，而拖着不退的第一步就是看不见。
 *   <li>{@link eligibilityPresentation} 说清每个判定结论**为什么**是这个结论
 *       （依据字段），而不是只给一个「通过 / 不通过」。
 *   <li>{@link windowMarginText} 把余量小时数渲染成人话，并明确区分
 *       「还在窗口内」与「已超窗多久」—— 前者是余量，后者是欠账。
 * </ul>
 *
 * <p><b>前端绝不重算业务判定</b>：售后期天数与必退词表都在后端
 * （17.5.1.1 业务参数），判定结论是后端算完冻结下来的凭据。
 * 这里只做呈现口径 —— 两处各算一遍必然会漂移，而漂移的方向通常是「显示正常」。
 *
 * <p>路径前缀是 {@code trade/aftersales/intake}，前端路由是 {@code /aftersales/intake}。
 */

const base = 'trade/aftersales/intake';

export interface AftersalesIntakeListParams {
  // ⚠️ 显式带上 `| undefined`：本仓开了 exactOptionalPropertyTypes，
  // 只写 `eligibility?: string` 时调用方**不能**传 undefined，
  // 而「按筛选条件拼查询」恰恰要把「没填」表达成 undefined。
  eligibility?: string | undefined;
  mustRefund?: boolean | undefined;
  intakeSource?: string | undefined;
  ownerAssignmentMode?: string | undefined;
  caseId?: string | undefined;
  caseNo?: string | undefined;
  keyword?: string | undefined;
  currentOnly?: boolean | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function aftersalesIntakeQuery(params: AftersalesIntakeListParams): string {
  const parts: string[] = [];
  const put = (k: string, v?: string) => {
    if (v && v.trim()) parts.push(`${k}=${encodeURIComponent(v.trim())}`);
  };
  put('eligibility', params.eligibility);
  if (params.mustRefund !== undefined) parts.push(`mustRefund=${params.mustRefund}`);
  put('intakeSource', params.intakeSource);
  put('ownerAssignmentMode', params.ownerAssignmentMode);
  put('caseId', params.caseId);
  put('caseNo', params.caseNo);
  put('keyword', params.keyword);
  put('sort', params.sort);
  if (params.currentOnly !== undefined) parts.push(`currentOnly=${params.currentOnly}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchAftersalesIntakeCapabilities() {
  return api<AftersalesIntakeCapabilities>(`${base}/capabilities`);
}

export function fetchAftersalesIntakeList(params: AftersalesIntakeListParams) {
  return api<AftersalesIntakePage>(`${base}/verdicts${aftersalesIntakeQuery(params)}`);
}

/**
 * 受理一张售后请求。
 *
 * <p>受理源只能是平台消息或内部录入两种：
 * 受理判定会把这条请求落成一张**待处理**的售后单 + 一份判定凭据，
 * 不可逆（判定凭据 append-only，只能追加新版本，不能改写）。
 */
export function submitAftersalesIntake(payload: Record<string, unknown>) {
  return api<AftersalesIntakeResult>(`${base}/cases`, 'POST', payload);
}

/**
 * 重新判定。
 *
 * <p>⚠️ 这是「改参数不改历史单据」的操作面：它**追加**一版判定，
 * 旧判定连同它当时读到的参数原值永久留在库里。
 */
export function rejudgeAftersalesIntake(caseId: string) {
  return api<AftersalesIntakeVerdictView>(`${base}/cases/${caseId}/rejudge`, 'POST', {});
}

export function fetchAftersalesReplySla(platformSource?: string) {
  const q = platformSource ? `?platformSource=${encodeURIComponent(platformSource)}` : '';
  return api<AftersalesReplySlaView | null>(`${base}/reply-sla/effective${q}`);
}

export function fetchAftersalesReplySlaList() {
  return api<AftersalesReplySlaView[]>(`${base}/reply-sla`);
}

export function createAftersalesReplySla(payload: Record<string, unknown>) {
  return api<AftersalesReplySlaView>(`${base}/reply-sla`, 'POST', payload);
}

export function retireAftersalesReplySla(id: string, effectiveTo?: string) {
  return api<AftersalesReplySlaView>(`${base}/reply-sla/${id}/retire`, 'POST', {
    effectiveTo: effectiveTo ?? null,
  });
}

export function fetchAftersalesOwnerRoster() {
  return api<AftersalesOwnerRosterView[]>(`${base}/owner-roster`);
}

export function upsertAftersalesOwner(payload: Record<string, unknown>) {
  return api<AftersalesOwnerRosterView>(`${base}/owner-roster`, 'POST', payload);
}

export function updateAftersalesOwnerRoster(
  id: string,
  payload: { acceptingNewCases?: boolean; weight?: number },
) {
  return api<AftersalesOwnerRosterView>(`${base}/owner-roster/${id}`, 'PUT', payload);
}

// ================================================================ 展示口径

export type MustRefundTone = 'must-overdue' | 'must' | 'not-must' | 'unknown';

export interface MustRefundPresentation {
  tone: MustRefundTone;
  label: string;
  /** 表格排序权重：0 最紧急（必退且已超时）。 */
  rank: number;
  badge: string;
}

const MUST_REFUND = {
  MUST_OVERDUE: { tone: 'must-overdue', label: '必退 · 已超时', rank: 0, badge: '必退超时' },
  MUST: { tone: 'must', label: '必退', rank: 1, badge: '必退' },
  NOT_MUST: { tone: 'not-must', label: '非必退', rank: 3, badge: '非必退' },
  UNKNOWN: { tone: 'unknown', label: '判定未知', rank: 2, badge: '未知' },
} satisfies Record<string, MustRefundPresentation>;

export const MUST_REFUND_PRESENTATION: Readonly<Record<string, MustRefundPresentation>> =
  MUST_REFUND;

/**
 * 必退语气。
 *
 * <p>「必退 + 已超时」单独一档（rank 0）：这是加粗句真正要防的那一种 ——
 * 平台已经判责或正在判责。四档必须**视觉上真的分得开**，
 * 一屏 50 行里只有文字变化而没有视觉差异，等于没有提醒。
 */
export function mustRefundPresentation(
  mustRefund: boolean | null | undefined,
  replyOverdue: boolean,
): MustRefundPresentation {
  if (mustRefund === null || mustRefund === undefined) return MUST_REFUND.UNKNOWN;
  if (!mustRefund) return MUST_REFUND.NOT_MUST;
  return replyOverdue ? MUST_REFUND.MUST_OVERDUE : MUST_REFUND.MUST;
}

export function mustRefundBadgeClass(
  mustRefund: boolean | null | undefined,
  replyOverdue: boolean,
): string {
  return `tone-${mustRefundPresentation(mustRefund, replyOverdue).tone}`;
}

/**
 * 必退依据的人话。
 *
 * <p>必须区分「平台声明」与「原因分类命中」—— 两者的可推翻性不同：
 * 前者只能改平台侧，后者随政策词表变化。
 */
export function mustRefundBasisText(basis: string | null | undefined, situation?: string | null): string {
  switch (basis) {
    case 'PLATFORM_DECLARED':
      return '平台声明必退（不可在本地降级）';
    case 'REASON_MAPPED':
      return `原因分类命中必退情形 ${situation ?? ''}`.trim();
    case 'NONE':
      return '不命中任何必退情形';
    default:
      return '—';
  }
}

export type EligibilityTone = 'accepted' | 'must' | 'pending' | 'rejected' | 'unknown';

export interface EligibilityPresentation {
  tone: EligibilityTone;
  label: string;
  rank: number;
}

const ELIGIBILITY = {
  ACCEPTED_MUST_REFUND: { tone: 'must', label: '符合条件 · 必退', rank: 0 },
  OUT_OF_WINDOW_PENDING: { tone: 'pending', label: '超窗待裁定', rank: 1 },
  ACCEPTED: { tone: 'accepted', label: '符合条件', rank: 2 },
  REJECTED_OUT_OF_WINDOW: { tone: 'rejected', label: '超窗不受理', rank: 3 },
} satisfies Record<string, EligibilityPresentation>;

export const ELIGIBILITY_PRESENTATION: Readonly<Record<string, EligibilityPresentation>> = ELIGIBILITY;

/**
 * 受理结论语气。
 *
 * <p>未知口径回落到「超窗待裁定」那一档（rank 1）而不是「符合条件」——
 * 认不出的状态说明后端加了一个本界面还没接的结论，
 * 此时按「待人工看」处理是安全的，按「通过」处理会直接放过一张本该拒的单。
 */
export function eligibilityPresentation(
  eligibility: string | null | undefined,
): EligibilityPresentation {
  if (!eligibility) return ELIGIBILITY.OUT_OF_WINDOW_PENDING;
  return ELIGIBILITY_PRESENTATION[eligibility] ?? ELIGIBILITY.OUT_OF_WINDOW_PENDING;
}

export function eligibilityBadgeClass(eligibility: string | null | undefined): string {
  return `tone-${eligibilityPresentation(eligibility).tone}`;
}

/**
 * 售后期余量文本。
 *
 * <p>正数 = 还在窗口内（还剩多久），负数 = 已超窗（超了多久）。
 * 两者用不同的措辞：一个是余量，一个是欠账，混成「-3 天」没人读得懂。
 */
export function windowMarginText(
  inWindow: boolean,
  marginHours: number | null | undefined,
): string {
  if (marginHours === null || marginHours === undefined) return '—';
  if (inWindow) return `窗口内，余 ${humanizeHours(marginHours)}`;
  return `已超窗 ${humanizeHours(Math.abs(marginHours))}`;
}

/** 小时数转「3 天 4 小时」这种可读文本；不足 1 小时时给「不足 1 小时」。 */
export function humanizeHours(hours: number): string {
  const total = Math.floor(Math.abs(hours));
  if (total < 1) return '不足 1 小时';
  const days = Math.floor(total / 24);
  const rest = total % 24;
  if (days > 0) return rest > 0 ? `${days} 天 ${rest} 小时` : `${days} 天`;
  return `${rest} 小时`;
}

/** 售后期锚点说明：让人知道这个「窗口」是从哪一刻算起的。 */
export function orderAnchorText(kind: string | null | undefined): string {
  switch (kind) {
    case 'SUBMITTED_AT':
      return '自订单提交时刻起算';
    case 'CREATED_AT':
      return '该单尚无提交时刻，自建单时刻起算';
    default:
      return '—';
  }
}

/** 回复时限来源说明。`slaHours` 可省略 —— 平台给的时限压根没有 SLA 小时数。 */
export function replyDueBasisText(
  basis: string | null | undefined,
  slaHours: number | null | undefined = undefined,
): string {
  if (basis === 'PLATFORM_GIVEN') return '平台自己给的时限';
  if (basis === 'POLICY_SLA') return `按租户回复时限策略 ${slaHours ?? '?'} 小时推算`;
  return '—';
}

/** 责任人分配方式说明。 */
export function ownerModeText(mode: string | null | undefined): string {
  switch (mode) {
    case 'OPERATOR_SPECIFIED':
      return '受理人指定';
    case 'ROSTER_ROUND_ROBIN':
      return '名册轮转自动派单';
    case 'UNASSIGNED':
      return '待认领（名册为空且未指定）';
    default:
      return '—';
  }
}

/**
 * 汇总卡的语气。
 *
 * <p>「必退且已超时」这个数是本页面最要紧的一个 —— 它直接对应账号绩效与资金冻结，
 * 所以它是唯一一张用警示色渲染的卡，其余用中性色。
 */
export function summaryTone(count: number): 'alert' | 'warn' | 'neutral' {
  if (count > 0) return 'alert';
  return 'neutral';
}
