import type {
  BatchDecisionInput,
  BatchPage,
  BatchReceipt,
  PendingReviewCapabilities,
  PendingReviewDetail,
  PendingReviewPage,
  PendingReviewRow,
  PendingReviewSummary,
  ReviewDetail,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.2.1.1 待审队列前端模型。
 *
 * <p>蓝图：「展示待审订单，按风险与时效排序，支持批量通过/驳回/挂起，
 * 显示每单命中的规则与风险信号。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「没评估过」不是 0 分。</b>{@code riskScore === null} 表示压根没出分，
 *       显示成 0.0000 会被读成「量过了，安全」—— 而 0 分在风控语境里正是这个意思。
 *       所以这里显示「未评估」，并说清是谁没评估（9.2.2.2 风控校验）。</li>
 *   <li><b>排序是后端的事，界面不许再排一次。</b>列表顺序就是「风险分 → 时效分 →
 *       提交时刻」的顺序，界面照单显示；前端再排一次只能对当前页生效，翻页会乱序。</li>
 *   <li><b>「批量通过」必须先挑一条规则。</b>没有规则就没有「明确的规则」，
 *       所以「通过」按钮在没挑规则时是**禁用**的，并说明原因；
 *       驳回与挂起则必须写理由 —— 没有理由的结论没法向买家解释。</li>
 *   <li><b>回执要逐条读。</b>「本批通过 3 单、跳过 2 单」是一句话；
 *       「第 2 单为什么被跳过」是逐条结果里的 reason。两者都要能看见。</li>
 *   <li><b>命中规则要能点回具体规则。</b>列表给的是规则编号数组，
 *       详情给的是逐条件到订单字段与实际值的命中链。</li>
 * </ul>
 *
 * <p>路径前缀是 {@code trade/order-pending-review} —— 与 9.2.1.2 的
 * {@code trade/order-review}、9.2.1.3 的 {@code trade/order-intercept} 首 token 不同，
 * 白名单上互不吃。
 */

const base = 'trade/order-pending-review';

// ------------------------------------------------------------ 参数

// ⚠️ 每个可选字段都显式带 `| undefined`：仓库开了 exactOptionalPropertyTypes，
// 写成 `keyword?: string` 时「传一个值为 undefined 的 key」本身就是类型错误，
// 而 `keyword || undefined` 正是界面上最自然的清空写法。
export interface QueueParams {
  keyword?: string | undefined;
  orderSource?: string | undefined;
  queueStatus?: string | undefined;
  reviewDecision?: string | undefined;
  riskEvaluated?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface BatchParams {
  action?: string | undefined;
  keyword?: string | undefined;
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

export function fetchCapabilities(): Promise<PendingReviewCapabilities> {
  return api<PendingReviewCapabilities>(`${base}/capabilities`);
}

export function fetchSummary(): Promise<PendingReviewSummary> {
  return api<PendingReviewSummary>(`${base}/summary`);
}

export function fetchQueue(params: QueueParams): Promise<PendingReviewPage> {
  return api<PendingReviewPage>(`${base}/queue${query(params)}`);
}

export function fetchQueueItem(orderId: string): Promise<PendingReviewDetail> {
  return api<PendingReviewDetail>(`${base}/queue/${orderId}`);
}

/**
 * 试算：按此刻生效中的规则跑一次判定，标成试跑，不冒充生产结论。
 *
 * <p>返回的是 **9.2.1.2 的判定详情形状**（结论 + 命中链 + 订单事实）——
 * 判定链是 9.2.1.2 的主权，本模块不另起一套形状（两套形状会让「回溯到规则 ID」断链）。
 */
export function evaluateOrder(orderId: string): Promise<ReviewDetail> {
  return api<ReviewDetail>(`${base}/queue/${orderId}/evaluate`, 'POST', {
    evaluatedAt: new Date().toISOString(),
  });
}

export function fetchBatches(params: BatchParams): Promise<BatchPage> {
  return api<BatchPage>(`${base}/batches${query(params)}`);
}

export function fetchBatch(batchId: string): Promise<BatchReceipt> {
  return api<BatchReceipt>(`${base}/batches/${batchId}`);
}

/**
 * 发起批量审单。
 *
 * <p>幂等键由调用方给：同一次操作重试要带**同一个**键，
 * 否则后端会当成两次不同的请求（幂等的前提是「键 ↔ 一份请求」一一对应）。
 */
export function decideBatch(input: BatchDecisionInput): Promise<BatchReceipt> {
  return api<BatchReceipt>(`${base}/batches`, 'POST', input);
}

// ------------------------------------------------------------ 展示

/** 风险分：没评估过就说「未评估」，**绝不显示 0**。 */
export function riskScoreText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '未评估';
  return value.toFixed(4);
}

/** 风险分的完整说法：没评估过时必须说清「谁没评估」，不能只留一个破折号。 */
export function riskScoreHint(row: {
  riskScore: number | null;
  riskEvaluationStatus: string | null;
}): string {
  if (row.riskScore === null || row.riskScore === undefined) {
    return '未评估：9.2.2.2 风控校验还没有对这一单出过分（信号不足或压根没触发过评估）。'
      + '「未评估」不是 0 分 —— 0 分在风控语境里是「量过了，安全」。';
  }
  return row.riskEvaluationStatus === 'SCORED'
    ? '已出分（9.2.2.2 风控校验）'
    : '风控评估状态异常，未出分';
}

/** 队列状态中文名。 */
export function queueStatusText(status: string | null | undefined): string {
  switch (status) {
    case 'PENDING':
      return '待审';
    case 'SUSPENDED':
      return '已挂起';
    case 'APPROVED':
      return '已通过';
    case 'REJECTED':
      return '已驳回';
    default:
      return '—';
  }
}

/** 时效状态中文名。判定真身在后端的 DB 函数里，界面不重算。 */
export function timelinessText(state: string | null | undefined): string {
  switch (state) {
    case 'FRESH':
      return '未超时';
    case 'DUE_SOON':
      return '临近超时';
    case 'OVERDUE':
      return '已超时';
    case 'NO_DEADLINE':
      return '无时限';
    default:
      return '—';
  }
}

/** 等候时长：小时，一位小数。 */
export function waitingText(hours: number | null | undefined): string {
  if (hours === null || hours === undefined) return '—';
  return `${hours.toFixed(1)} 小时`;
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

/** 审单判定结论中文名。null = 没判定过。 */
export function reviewDecisionText(decision: string | null | undefined): string {
  switch (decision) {
    case 'AUTO_APPROVE':
      return '规则判定通过';
    case 'MANUAL_REVIEW':
      return '规则判定转人工';
    default:
      return '未判定';
  }
}

/**
 * 判定模式中文名。
 *
 * <p>试跑（DRY_RUN）出来的结论**不是**生产结论，界面上必须分开标 ——
 * 把试算结果当成正式判定显示，就是在替规则引擎撒谎。
 */
export function reviewModeText(mode: string | null | undefined): string {
  switch (mode) {
    case 'RECORDED':
      return '生效判定';
    case 'DRY_RUN':
      return '试跑结果';
    default:
      return '—';
  }
}

/** 命中的规则编号：列表那一列。空数组说「无」而不是留白。 */
export function hitRuleText(row: PendingReviewRow): string {
  if (!row.hitRuleCodes || row.hitRuleCodes.length === 0) return '无';
  return row.hitRuleCodes.join('、');
}

/** 逐条结果中文名。 */
export function outcomeText(outcome: string | null | undefined): string {
  switch (outcome) {
    case 'APPROVED':
      return '已通过';
    case 'REJECTED':
      return '已驳回';
    case 'SUSPENDED':
      return '已挂起';
    case 'SKIPPED':
      return '已跳过';
    default:
      return '—';
  }
}

/**
 * 这一行能不能勾选去批量通过。
 *
 * <p>已通过 / 已驳回是终态，勾了也只会被跳过 —— 与其让人点了再吃一个「已审过」，
 * 不如直接不给勾。
 */
export function canSelect(row: PendingReviewRow): boolean {
  return row.queueStatus === 'PENDING' || row.queueStatus === 'SUSPENDED';
}

/**
 * 「通过」按钮现在能不能点。
 *
 * <p>加粗句的界面落点：**没挑规则就不能批量通过**。
 * 规则清单为空（租户没配生效中的自动通过规则）时更不能点。
 */
export function approveDisabledReason(
  selectedCount: number,
  ruleCode: string,
  approvableRuleCount: number,
): string | null {
  if (selectedCount === 0) return '先勾选要审的订单';
  if (!ruleCode) return '批量通过必须先选一条依据的规则（没有规则就没有「明确的规则」）';
  if (approvableRuleCount === 0) return '本租户没有生效中的「自动通过」规则，无法批量通过';
  return null;
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
