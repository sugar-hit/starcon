import type {
  AssessmentInput,
  AssessmentSaved,
  RiskAssessment,
  RiskAssessmentPage,
  RiskCapabilities,
  RiskComponent,
  RiskDetail,
  RiskRule,
  RiskRuleInput,
  RiskSignal,
  RiskSignalInput,
  RiskSignalPage,
  RiskReviewInput,
  ReviewSaved,
  Summary,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.2.2.2 风控校验前端模型。
 *
 * <p>蓝图：「多信号风险打分：买家历史（退款率、纠纷率）、设备与 IP 关联（16）、下单频次、
 * 金额异常、地址集中度；输出风险分与建议动作。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「没测到」与「测到了是 0」是两句话。</b>后端在某个维度不可用时返回
 *       {@code rawValue: null} 且 {@code status} 说明为什么（未登记 / 已过期 /
 *       历史不足 / 不适用）。把 null 渲染成 0 等于宣称「量过了，风险是零」——
 *       那正是误伤正常复购客户的成因。</li>
 *   <li><b>「建议拒发」不能显示成「已拒发」。</b>{@code recommendedAction} 是分数给的建议，
 *       {@code finalAction} 是人工给的结论，未复核时是 null。
 *       两者在列表与详情里必须**分行显示**，且建议拒发那一行永远带
 *       「需人工复核」字样 —— 界面上把两者合成一个「已拒发」徽标，
 *       就等于在 UI 层把蓝图加粗句又违反一次。</li>
 *   <li><b>风险分只在出了分时显示数字。</b>{@code riskScore === null} 表示
 *       「信号不足，压根没出分」，显示成 0.0000 会被当成「量过了，结论是安全」。</li>
 *   <li><b>上游缺口要显形。</b>capabilities 里 {@code upstreamDelivered = false} 的维度
 *       带一句话说明它靠什么撑住，让运营知道这两维的数据是租户自己登记的，
 *       而不是系统从 15 客户 / 16 合规读来的。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

// ⚠️ 每个可选字段都显式带 `| undefined`：仓库开了 exactOptionalPropertyTypes，
// 写成 `keyword?: string` 时「传一个值为 undefined 的 key」本身就是类型错误，
// 而 `keyword || undefined` 正是界面上最自然的清空写法。
export interface AssessmentParams {
  keyword?: string | undefined;
  recommendedAction?: string | undefined;
  evaluationStatus?: string | undefined;
  pendingReviewOnly?: boolean | undefined;
  buyerFingerprint?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface SignalParams {
  keyword?: string | undefined;
  signalCode?: string | undefined;
  signalSource?: string | undefined;
  subjectKey?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
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

const base = 'trade/orders/risk-validation';

export function fetchCapabilities(): Promise<RiskCapabilities> {
  return api<RiskCapabilities>(`${base}/capabilities`);
}

export function fetchOverview(): Promise<Summary> {
  return api<Summary>(`${base}/overview`);
}

export function fetchRule(): Promise<RiskRule> {
  return api<RiskRule>(`${base}/rule`);
}

export function saveRule(input: RiskRuleInput): Promise<RiskRule> {
  return api<RiskRule>(`${base}/rule`, 'PUT', input);
}

export function fetchSignals(params: SignalParams): Promise<RiskSignalPage> {
  return api<RiskSignalPage>(`${base}/signals${query(params)}`);
}

export function createSignal(input: RiskSignalInput): Promise<RiskSignal> {
  return api<RiskSignal>(`${base}/signals`, 'POST', input);
}

export function fetchAssessments(params: AssessmentParams): Promise<RiskAssessmentPage> {
  return api<RiskAssessmentPage>(`${base}/assessments${query(params)}`);
}

export function createAssessment(input: AssessmentInput): Promise<AssessmentSaved> {
  return api<AssessmentSaved>(`${base}/assessments`, 'POST', input);
}

export function fetchAssessment(assessmentId: string): Promise<RiskDetail> {
  return api<RiskDetail>(`${base}/assessments/${assessmentId}`);
}

export function submitReview(
  assessmentId: string,
  input: RiskReviewInput,
): Promise<ReviewSaved> {
  return api<ReviewSaved>(`${base}/assessments/${assessmentId}/review`, 'POST', input);
}

// ------------------------------------------------------------ 展示

/** 风险分：只做展示格式化，**不改值**（后端按 numeric(9,4) 落库）。 */
export function score(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4);
}

/**
 * 风险分的**完整**说法：没出分时必须说清「没出分」，不能只显示一个破折号。
 *
 * <p>破折号会被读成「0 分」，而 0 分在风控语境里是「量过了，安全」的意思 ——
 * 与事实正好相反。
 */
export function scoreText(row: { riskScore: number | null; evaluationStatus: string }): string {
  if (row.riskScore === null || row.riskScore === undefined) {
    return '未出分（信号不足）';
  }
  return row.riskScore.toFixed(4);
}

/** 权重：四位刻度。 */
export function weight(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4);
}

/**
 * 单维原始值：没测到就说「未测到」，**绝不显示 0**。
 *
 * <p>去尾零显示（0.100000 → 0.1、5.000000 → 5），比率与计数用同一套格式化。
 */
export function rawValueText(component: RiskComponent): string {
  if (component.rawValue === null || component.rawValue === undefined) return '未测到';
  return component.rawValue.toFixed(6).replace(/0+$/, '').replace(/\.$/, '') || '0';
}

/** 订单金额：numeric(19,4)，只做展示格式化。 */
export function money(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4).replace(/\.?0+$/, '') || '0';
}

/** ISO 时刻 → 本地可读文本；空值说「—」而不是 Invalid Date。 */
export function isoText(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
}

/** 建议动作的中文名。三个值都只是「建议」。 */
export function actionText(action: string | null | undefined): string {
  switch (action) {
    case 'RELEASE':
      return '放行';
    case 'MANUAL_REVIEW':
      return '人工复核';
    case 'HOLD_SHIPMENT':
      return '建议拒发';
    default:
      return '—';
  }
}

/** 最终动作的中文名。null = 还没人复核。 */
export function finalActionText(action: string | null | undefined): string {
  switch (action) {
    case 'SHIP':
      return '复核后发货';
    case 'REJECT':
      return '复核后拒发';
    default:
      return '尚未复核';
  }
}

/** 维度中文名。 */
export function dimensionText(dimension: string): string {
  switch (dimension) {
    case 'BUYER_HISTORY':
      return '买家历史（退款率 / 纠纷率）';
    case 'DEVICE_IP_LINK':
      return '设备与 IP 关联';
    case 'ORDER_FREQUENCY':
      return '下单频次';
    case 'AMOUNT_ANOMALY':
      return '金额异常';
    case 'ADDRESS_CONCENTRATION':
      return '地址集中度';
    default:
      return dimension;
  }
}

/** 单维状态中文名。四种不可用各有各的处置动作，合成一句话会误导。 */
export function componentStatusText(status: string): string {
  switch (status) {
    case 'SCORED':
      return '已计分';
    case 'MISSING_SIGNAL':
      return '缺信号';
    case 'STALE_SIGNAL':
      return '信号已过期';
    case 'INSUFFICIENT_HISTORY':
      return '历史笔数不足';
    case 'NOT_APPLICABLE':
      return '该单不适用';
    default:
      return status;
  }
}

/** 评估状态中文名。 */
export function evaluationStatusText(status: string): string {
  return status === 'SCORED' ? '已出分' : '信号不足，未出分';
}

/**
 * 这一行到底能不能复核。
 *
 * <p>只有「建议动作不是放行」且「还没人复核」时才可复核 —— 界面据此禁用按钮，
 * 避免让人点一次再吃一个 409。
 */
export function canReview(row: RiskAssessment): boolean {
  return row.manualReviewRequired && row.finalAction === null;
}

/**
 * 人工复核结论文本：把「建议」与「结论」分行讲清。
 *
 * <p>蓝图加粗句「风控分数不能单独作为拒绝依据」的界面落点：
 * 建议拒发的单必须显示「尚无人工结论」，而不是一个红色的「已拒发」。
 */
export function verdictText(row: RiskAssessment): string {
  if (row.finalAction !== null) {
    return `最终动作：${finalActionText(row.finalAction)}（人工复核结论）`;
  }
  if (row.recommendedAction === 'HOLD_SHIPMENT') {
    return '建议拒发 —— 尚无人工结论，分数不能单独作为拒绝依据';
  }
  if (row.recommendedAction === 'MANUAL_REVIEW') {
    return '建议人工复核 —— 尚无人工结论';
  }
  return '建议放行';
}

/** 维度来源标签：让人一眼看出哪些维是系统查的、哪些是租户登记的。 */
export function sourceText(source: string): string {
  switch (source) {
    case 'TENANT_MAINTAINED':
      return '租户登记';
    case 'MODULE_15_CUSTOMER_PROFILE':
      return '15 客户画像（未交付，租户代登记）';
    case 'MODULE_16_RISK_LINKAGE':
      return '16 合规信号（未交付，租户代登记）';
    case 'DERIVED_FROM_ORDERS':
      return '本服务订单簇现算';
    default:
      return source;
  }
}

/** 信号码中文名。 */
export function signalCodeText(code: string): string {
  switch (code) {
    case 'REFUND_RATE':
      return '退款率';
    case 'DISPUTE_RATE':
      return '纠纷率';
    case 'DEVICE_IP_LINK_COUNT':
      return '设备与 IP 关联数';
    case 'SHARED_DEVICE_COUNT':
      return '共用设备数';
    case 'SHARED_IP_COUNT':
      return '共用 IP 数';
    default:
      return code;
  }
}

/** 复核原因码中文名。FALSE_POSITIVE 是「误伤正常复购客户」在数据里的正式名字。 */
export function reasonText(code: string): string {
  switch (code) {
    case 'CONFIRMED_RISK':
      return '复核确认有风险';
    case 'FALSE_POSITIVE':
      return '误判，疑似正常复购客户';
    case 'PENDING_EVIDENCE':
      return '仍需材料';
    case 'ESCALATED_COMPLIANCE':
      return '升级合规处理';
    default:
      return code;
  }
}

/** 错误信息：把后端的人话原样带出来，不要压成「操作失败」。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    const body = error.body as { message?: string; detail?: string } | null;
    const text = body?.detail || body?.message;
    return text ? `${error.status} · ${text}` : `${error.status} · ${error.message}`;
  }
  if (error instanceof Error) return error.message;
  return '未知错误';
}

/** 分页器文案。 */
export function pageText(page: number, size: number, total: number): string {
  if (total === 0) return '共 0 条';
  const from = page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  return `第 ${from}–${to} 条 / 共 ${total} 条`;
}
