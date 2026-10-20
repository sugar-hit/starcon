import type {
  CrmReviewBatchInput,
  CrmReviewBatchResult,
  CrmReviewCandidatePage,
  CrmReviewCapabilities,
  CrmReviewCreateInput,
  CrmReviewFrequencyPolicy,
  CrmReviewPolicyInput,
  CrmReviewRecovery,
  CrmReviewRecoveryInput,
  CrmReviewRecoveryStats,
  CrmReviewRequestDetail,
  CrmReviewRequestRow,
  CrmReviewSendRecord,
  CrmReviewSendRecordInput,
  CrmReviewShopView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.2.2.1 Request a Review（索评）前端模型。
 *
 * <p>蓝图：「按规则向已签收订单的买家发起索评（平台允许范围内），控制频次避免打扰，
 * 支持按店铺/时间/物流状态批量执行。」输出「索评任务 + 发送记录 + 评价回收统计」。
 * 关系：上游 = 10.3.2.2 签收；下游 = 15.2.1.1 评价。
 * **索评频次必须受限**，过度索评会被买家投诉甚至平台处罚。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>频次上限是硬闸，不是提示</b>：页面必须显示租户级硬上限（每买家上限 / 冷却窗口 /
 *       时点下限），并且**不允许**填出比硬上限更松的规则。请求被频次规则挡住时，
 *       页面要把后端返回的 {@code rejectReason} **原文**显示出来 ——
 *       「被挡」与「没发出去」是两件完全不同的事，界面上不能混成一句「操作失败」。</li>
 *   <li><b>「被拒绝」也是一条真实的索评请求</b>：后端把它落库（status=REJECTED + 原因），
 *       所以列表里能查到、能筛出来，页面必须给它一个独立状态而不是过滤掉。</li>
 *   <li><b>未接入的平台能力要显式灰掉</b>：平台侧索评接口未接入（capabilities 里
 *       {@code platform_review_api} 维度 available=false），发送通道只允许「人工交接」，
 *       页面**不提供**「发到平台」的按钮 —— 一个点了就返回成功的假按钮会让运营
 *       以为买家已经被索评过。</li>
 *   <li><b>买家标识是运营提供的</b>：本仓没有买家档案（15.3.1.1 未交付），
 *       页面必须把这一点写在买家输入框旁边，别让人以为系统里有买家主数据。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/review-requests}，与同服务的 {@code crm/knowledge}
 * （15.4.2.1 客服知识库）**刻意不共用**：一个管内容，一个管触达。
 */

const base = 'crm/review-requests';

/**
 * 索评任务列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface ReviewRequestParams {
  shopId?: string | undefined;
  platformCode?: string | undefined;
  status?: string | undefined;
  buyerRef?: string | undefined;
  salesOrderNo?: string | undefined;
  carrierCode?: string | undefined;
  createdFrom?: string | undefined;
  createdTo?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function reviewRequestQuery(params: ReviewRequestParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.platformCode) parts.push(`platformCode=${encodeURIComponent(params.platformCode)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.buyerRef) parts.push(`buyerRef=${encodeURIComponent(params.buyerRef)}`);
  if (params.salesOrderNo) parts.push(`salesOrderNo=${encodeURIComponent(params.salesOrderNo)}`);
  if (params.carrierCode) parts.push(`carrierCode=${encodeURIComponent(params.carrierCode)}`);
  if (params.createdFrom) parts.push(`createdFrom=${encodeURIComponent(params.createdFrom)}`);
  if (params.createdTo) parts.push(`createdTo=${encodeURIComponent(params.createdTo)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchReviewCapabilities() {
  return api<CrmReviewCapabilities>(`${base}/capabilities`);
}

/**
 * 租户级频次硬上限。
 *
 * <p>⚠️ 没配置过时后端返回 **404**（不是一份默认值）：默认上限是**没有**的，
 * 页面必须把它渲染成「必须先配置上限才能索评」，而不是回退到一组写死的数字。
 */
export function fetchFrequencyPolicy() {
  return api<CrmReviewFrequencyPolicy>(`${base}/frequency-policy`);
}

/** 配置 / 更新频次硬上限（幂等 upsert）。 */
export function saveFrequencyPolicy(input: CrmReviewPolicyInput) {
  return api<CrmReviewFrequencyPolicy>(`${base}/frequency-policy`, 'PUT', input);
}

/** 店铺目录（来自上游 channel 的真实清单，不是前端写死的常量）。 */
export function fetchReviewShops() {
  return api<CrmReviewShopView[]>(`${base}/shops`);
}

/** 已签收候选单的筛选条件。 */
export interface ReviewCandidateParams {
  carrierCode?: string | undefined;
  batchNo?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function candidatesQuery(params: ReviewCandidateParams = {}): string {
  const parts: string[] = [];
  if (params.carrierCode) parts.push(`carrierCode=${encodeURIComponent(params.carrierCode)}`);
  if (params.batchNo) parts.push(`batchNo=${encodeURIComponent(params.batchNo)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 已签收候选单（上游 channel 的确认单清单）。 */
export function fetchReviewCandidates(params: ReviewCandidateParams = {}) {
  return api<CrmReviewCandidatePage>(`${base}/candidates${candidatesQuery(params)}`);
}

/** 索评任务列表。 */
export function fetchReviewRequests(params: ReviewRequestParams = {}) {
  return api<CrmReviewRequestRow[]>(`${base}${reviewRequestQuery(params)}`);
}

/** 单条索评任务详情 = 请求本体 + 发送记录 + 评价回收。 */
export function fetchReviewRequest(requestId: string) {
  return api<CrmReviewRequestDetail>(`${base}/${encodeURIComponent(requestId)}`);
}

/**
 * 单条发起索评。
 *
 * <p>返回行的 {@code status} 可能是 {@code REJECTED}：那次索评被频次规则挡住了，
 * 原因写在 {@code rejectReason} 里，**这一行已经落库**（留痕）。
 */
export function createReviewRequest(input: CrmReviewCreateInput) {
  return api<CrmReviewRequestRow>(`${base}`, 'POST', input);
}

/** 批量执行：按店铺 / 时间 / 物流状态批量索评，逐条给出结论与原因。 */
export function runReviewBatch(input: CrmReviewBatchInput) {
  return api<CrmReviewBatchResult>(`${base}/batch`, 'POST', input);
}

/**
 * 登记一次触达（发送记录）。
 *
 * <p>⚠️ 只允许 {@code transport=MANUAL_HANDOFF}：平台侧索评接口未接入，
 * 后端会拒绝 {@code PLATFORM_API}。页面不提供「发到平台」的入口。
 */
export function createSendRecord(requestId: string, input: CrmReviewSendRecordInput) {
  return api<CrmReviewSendRecord>(
    `${base}/${encodeURIComponent(requestId)}/send-records`,
    'POST',
    input,
  );
}

/** 登记一条评价回收（收到的评价原文 + 关联到索评请求）。 */
export function createRecovery(requestId: string, input: CrmReviewRecoveryInput) {
  return api<CrmReviewRecovery>(
    `${base}/${encodeURIComponent(requestId)}/recoveries`,
    'POST',
    input,
  );
}

/** 评价回收统计的筛选条件。 */
export interface RecoveryStatsParams {
  days?: number | undefined;
  shopId?: string | undefined;
}

export function recoveryStatsQuery(params: RecoveryStatsParams = {}): string {
  const parts: string[] = [];
  if (params.days !== undefined) parts.push(`days=${params.days}`);
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 评价回收统计。 */
export function fetchRecoveryStats(params: RecoveryStatsParams = {}) {
  return api<CrmReviewRecoveryStats>(`${base}/recovery-stats${recoveryStatsQuery(params)}`);
}

// ================================================================ 可读形态

/** 索评请求状态的可读名（与后端 DB 词表一一对应）。 */
export function reviewStatusLabel(status: string): string {
  switch (status) {
    case 'SCHEDULED':
      return '已排期';
    case 'SENT':
      return '已发出';
    case 'CANCELLED':
      return '已取消';
    case 'REJECTED':
      return '被频次规则拒绝';
    default:
      return status;
  }
}

/** 触达结果的可读名。 */
export function sendOutcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'QUEUED':
      return '已登记待发';
    case 'SENT':
      return '已发出';
    case 'FAILED':
      return '失败';
    case 'CANCELLED':
      return '已作废';
    default:
      return outcome;
  }
}

/** 拒绝原因码的可读名。 */
export function rejectCodeLabel(code: string): string {
  switch (code) {
    case 'PER_ORDER_LIMIT':
      return '每订单上限';
    case 'PER_BUYER_LIMIT':
      return '每买家上限';
    case 'COOLDOWN_ACTIVE':
      return '冷却窗口未到';
    case 'ALREADY_ACTIVE_FOR_CASE':
      return '同一票已有在途索评';
    case 'BATCH_SIZE_EXCEEDED':
      return '超过单批上限';
    case 'NO_BUYER_BINDING':
      return '缺买家标识';
    case 'NO_SALES_ORDER_NO':
      return '缺订单号';
    default:
      return code;
  }
}

/**
 * 本次规则**能不能**用（比租户硬上限更松就不行）。
 *
 * <p>页面用它把「填了也发不出去」的输入**当场**标出来 ——
 * 但**真正**的判定在后端与 DB 触发器，这里只是提前告诉运营。
 */
export function ruleAgainstPolicy(
  rule: { delayHours: number; cooldownHours: number; maxPerBuyer: number; maxPerOrder: number },
  policy: CrmReviewFrequencyPolicy | null,
): string | null {
  if (!policy) return '本租户还没有配置索评频次策略：没有硬上限就不允许发起索评。';
  if (rule.maxPerBuyer > policy.maxPerBuyerPerWindow) {
    return `每买家上限 ${rule.maxPerBuyer} 次比租户硬上限 ${policy.maxPerBuyerPerWindow} 次更松：规则只能比硬上限更严。`;
  }
  if (rule.maxPerOrder > policy.maxPerOrder) {
    return `每订单上限 ${rule.maxPerOrder} 次比租户硬上限 ${policy.maxPerOrder} 次更松：规则只能比硬上限更严。`;
  }
  if (rule.cooldownHours < Number(policy.minCooldownHours)) {
    return `冷却窗口 ${rule.cooldownHours} 小时比租户下限 ${policy.minCooldownHours} 小时更短：冷却窗口只能更长。`;
  }
  if (rule.delayHours < Number(policy.minDelayHours)) {
    return `时点 ${rule.delayHours} 小时比租户下限 ${policy.minDelayHours} 小时更早：刚签收就索评会打扰买家。`;
  }
  return null;
}

/** 回收率的可读形态：分母为 0 时后端报 null，这里显示「不计算」而不是 0%。 */
export function recoveryRateText(rate: number | null): string {
  if (rate === null || rate === undefined) return '不计算（时间窗内没有已发出的索评）';
  return `${(rate * 100).toFixed(1)}%`;
}

/** 星级分布的可读形态：`5★×2 · 4★×0 · ...`。 */
export function ratingDistributionText(
  buckets: { rating: number; count: number }[] | undefined,
): string {
  if (!buckets || buckets.length === 0) return '暂无回收';
  return buckets
    .map((bucket) => `${bucket.rating}★×${bucket.count}`)
    .join(' · ');
}
