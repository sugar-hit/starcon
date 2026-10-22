import type {
  CrmReviewReplyBatchDetail,
  CrmReviewReplyBatchInput,
  CrmReviewReplyBatchPage,
  CrmReviewReplyBatchResult,
  CrmReviewReplyCapabilities,
  CrmReviewReplyCreateInput,
  CrmReviewReplyDeliveryResult,
  CrmReviewReplyDetail,
  CrmReviewReplyPage,
  CrmReviewReplyPolicyInput,
  CrmReviewReplyPolicyPage,
  CrmReviewReplyPolicyRow,
  CrmReviewReplyRow,
  CrmReviewReplyTemplateDetail,
  CrmReviewReplyTemplateInput,
  CrmReviewReplyTemplatePage,
  CrmReviewReplyTemplateUpdateInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.2.1.2 评价回复前端模型。
 *
 * <p>蓝图：「回复评价（公开回复 + 部分平台的卖家评论追加），支持模板、多语言、批量回复同类评价。
 * 输入：回复内容；目标评价。输出：回复记录 + 平台回执。关系：上游 = 15.2.1.1；下游 = 平台接口。
 * **差评回复是转化机会也是合规风险**，模板必须符合平台措辞规范。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「未接入」要显式灰掉</b>：平台投递（409、平台回执恒为空）、
 *       差评处理（15.2.1.3 未交付）、买家关怀（15.5.2 不在本模块范围）三项
 *       页面**不提供**看起来能点的假入口。</li>
 *   <li><b>「待投递」不等于「已回复」</b>：回复状态是 {@code PENDING} 时页面必须写
 *       「**尚未发到平台，买家还没收到**」—— 只显示「已回复」会让运营以为事情办完了。</li>
 *   <li><b>版本要看得见</b>：回复行上显示模板版本与规范版本，
 *       批量结果里显示「跳过的每一条 + 原因」—— 只报成功数会让人以为「全都回上了」。</li>
 *   <li><b>空态要区分两种</b>：「筛选无结果」与「还没有回复记录」是两句不同的话。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/review-replies}，与同服务的 {@code crm/reviews}（评价列表）、
 * {@code crm/review-requests}（索评）、{@code crm/review-rules}（索评规则）**刻意不共用**。
 */

const base = 'crm/review-replies';

// ================================================================ 查询串

/** 回复记录的筛选条件。 */
export interface ReviewReplyParams {
  reviewId?: string | undefined;
  shopId?: string | undefined;
  platformCode?: string | undefined;
  reviewScope?: string | undefined;
  replyType?: string | undefined;
  locale?: string | undefined;
  status?: string | undefined;
  templateKey?: string | undefined;
  batchId?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 措辞规范的筛选条件。 */
export interface ReviewReplyPolicyParams {
  platformCode?: string | undefined;
  replyType?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 模板的筛选条件。 */
export interface ReviewReplyTemplateParams {
  platformCode?: string | undefined;
  replyType?: string | undefined;
  ratingScope?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 批次的筛选条件。 */
export interface ReviewReplyBatchParams {
  platformCode?: string | undefined;
  replyType?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 把筛选条件拼成查询串（可选值为空就不出现）。 */
function queryOf(params: object): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 回复记录的查询串。 */
export function reviewReplyQuery(params: ReviewReplyParams = {}): string {
  return queryOf({ ...params });
}

/** 措辞规范的查询串。 */
export function reviewReplyPolicyQuery(params: ReviewReplyPolicyParams = {}): string {
  return queryOf({ ...params });
}

/** 模板的查询串。 */
export function reviewReplyTemplateQuery(params: ReviewReplyTemplateParams = {}): string {
  return queryOf({ ...params });
}

/** 批次的查询串。 */
export function reviewReplyBatchQuery(params: ReviewReplyBatchParams = {}): string {
  return queryOf({ ...params });
}

// ================================================================ 端点

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchReviewReplyCapabilities() {
  return api<CrmReviewReplyCapabilities>(`${base}/capabilities`);
}

/** 措辞规范列表（真分页 + 真筛选）。 */
export function fetchReviewReplyPolicies(params: ReviewReplyPolicyParams = {}) {
  return api<CrmReviewReplyPolicyPage>(`${base}/policies${reviewReplyPolicyQuery(params)}`);
}

/** 单条措辞规范。 */
export function fetchReviewReplyPolicy(policyId: string) {
  return api<CrmReviewReplyPolicyRow>(`${base}/policies/${encodeURIComponent(policyId)}`);
}

/** 配置平台措辞规范（**落成新的一版**：规范不原地改）。 */
export function saveReviewReplyPolicy(input: CrmReviewReplyPolicyInput) {
  return api<CrmReviewReplyPolicyRow>(`${base}/policies`, 'PUT', input);
}

/** 模板列表（真分页 + 真筛选）。 */
export function fetchReviewReplyTemplates(params: ReviewReplyTemplateParams = {}) {
  return api<CrmReviewReplyTemplatePage>(`${base}/templates${reviewReplyTemplateQuery(params)}`);
}

/** 新建回复模板（落地为草稿；改内容要发新版本）。 */
export function createReviewReplyTemplate(input: CrmReviewReplyTemplateInput) {
  return api<CrmReviewReplyTemplateDetail>(`${base}/templates`, 'POST', input);
}

/** 模板详情 = 模板本体 + 多语言条目。 */
export function fetchReviewReplyTemplate(templateId: string) {
  return api<CrmReviewReplyTemplateDetail>(`${base}/templates/${encodeURIComponent(templateId)}`);
}

/** 改草稿模板（已生效的模板内容冻结）。 */
export function updateReviewReplyTemplate(
  templateId: string,
  input: CrmReviewReplyTemplateUpdateInput,
) {
  return api<CrmReviewReplyTemplateDetail>(
    `${base}/templates/${encodeURIComponent(templateId)}`,
    'PUT',
    input,
  );
}

/** 发布模板（草稿 → 已生效）。 */
export function publishReviewReplyTemplate(templateId: string) {
  return api<CrmReviewReplyTemplateDetail>(
    `${base}/templates/${encodeURIComponent(templateId)}/publish`,
    'POST',
    {},
  );
}

/** 下架模板（已生效 → 已停用）。 */
export function archiveReviewReplyTemplate(templateId: string) {
  return api<CrmReviewReplyTemplateDetail>(
    `${base}/templates/${encodeURIComponent(templateId)}/archive`,
    'POST',
    {},
  );
}

/** 回复记录列表（真分页 + 真筛选）。 */
export function fetchReviewReplies(params: ReviewReplyParams = {}) {
  return api<CrmReviewReplyPage>(`${base}${reviewReplyQuery(params)}`);
}

/** 写一条回复（公开回复 / 卖家评论追加）。 */
export function createReviewReply(input: CrmReviewReplyCreateInput) {
  return api<CrmReviewReplyRow>(`${base}`, 'POST', input);
}

/** 单条回复详情 = 回复本体 + 投递历史。 */
export function fetchReviewReply(replyId: string) {
  return api<CrmReviewReplyDetail>(`${base}/${encodeURIComponent(replyId)}`);
}

/**
 * 把回复投递到平台。
 *
 * <p>⚠️ 当前恒为 409（平台评价回复适配器未交付）—— **不会**返回假回执。
 */
export function deliverReviewReply(replyId: string) {
  return api<CrmReviewReplyDeliveryResult>(
    `${base}/${encodeURIComponent(replyId)}/deliver`,
    'POST',
    {},
  );
}

/**
 * 撤回一条待投递的回复。
 *
 * <p>⚠️ 备注拼在**模板串之外**：模板串里出现 `${...}` 会被白名单守卫的
 * 「反向抽取真实调用点」逻辑当成路径的一部分，抽出来的形状就不对了。
 */
export function cancelReviewReply(replyId: string, note?: string) {
  const suffix = note ? `?note=${encodeURIComponent(note)}` : '';
  return api<CrmReviewReplyRow>(
    `${base}/${encodeURIComponent(replyId)}/cancel` + suffix,
    'POST',
    {},
  );
}

/** 批次列表（真分页 + 真筛选）。 */
export function fetchReviewReplyBatches(params: ReviewReplyBatchParams = {}) {
  return api<CrmReviewReplyBatchPage>(`${base}/batches${reviewReplyBatchQuery(params)}`);
}

/** 批量回复「同类评价」。 */
export function createReviewReplyBatch(input: CrmReviewReplyBatchInput) {
  return api<CrmReviewReplyBatchResult>(`${base}/batches`, 'POST', input);
}

/** 批次详情 = 批次本体 + 该批次下的回复 + 被跳过的评价与原因。 */
export function fetchReviewReplyBatch(batchId: string) {
  return api<CrmReviewReplyBatchDetail>(`${base}/batches/${encodeURIComponent(batchId)}`);
}

// ================================================================ 可读形态

/** 回复形态的可读名。 */
export function replyTypeLabel(replyType: string): string {
  switch (replyType) {
    case 'PUBLIC_REPLY':
      return '公开回复';
    case 'SELLER_COMMENT_APPEND':
      return '卖家评论追加';
    default:
      return replyType;
  }
}

/** 评价档位的可读名。 */
export function reviewScopeLabel(scope: string): string {
  switch (scope) {
    case 'POSITIVE':
      return '好评（4-5 星）';
    case 'NEUTRAL':
      return '中评（3 星）';
    case 'NEGATIVE':
      return '差评（1-2 星）';
    case 'ALL':
      return '不限档位';
    default:
      return scope;
  }
}

/**
 * 回复状态的可读名。
 *
 * <p>🔴 {@code PENDING} 的说法必须**带上「还没发到平台」** ——
 * 只写「待投递」会被读成「已经在路上了」。
 */
export function replyStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待投递（尚未发到平台，买家还没收到）';
    case 'DELIVERED':
      return '平台已确认送达';
    case 'REJECTED':
      return '平台拒绝';
    case 'CANCELLED':
      return '已撤回';
    default:
      return status;
  }
}

/** 模板状态的可读名。 */
export function templateStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿（可编辑）';
    case 'ACTIVE':
      return '已生效（内容冻结）';
    case 'ARCHIVED':
      return '已停用';
    default:
      return status;
  }
}

/** 投递结果的可读名。 */
export function deliveryOutcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'NOT_INTEGRATED':
      return '未接入（没发出去）';
    case 'DELIVERED':
      return '平台已确认';
    case 'REJECTED':
      return '平台拒绝';
    case 'FAILED':
      return '投递失败';
    default:
      return outcome;
  }
}

/**
 * 模板版本的追溯说明。
 *
 * <p>🔴 「用了哪一版」必须看得见 —— 平台政策会变，没有版本号的话，
 * 事后没人说得清「当时那条回复到底算不算违规」。
 */
export function templateVersionLabel(reply: {
  templateKey: string | null;
  templateVersion: number | null;
  templateLocale: string | null;
  policyVersion: number | null;
}): string {
  if (!reply.templateKey || reply.templateVersion === null) {
    return reply.policyVersion === null
      ? '手写回复（未套用模板）'
      : `手写回复（规范第 ${reply.policyVersion} 版）`;
  }
  const locale = reply.templateLocale ? ` · 语言 ${reply.templateLocale}` : '';
  const policy =
    reply.policyVersion === null ? '' : ` · 规范第 ${reply.policyVersion} 版`;
  return `模板 ${reply.templateKey} 第 ${reply.templateVersion} 版${locale}${policy}`;
}

/**
 * 批量结果的一句话总结。
 *
 * <p>🔴 **成功数与跳过数必须一起说**：只报「写了 N 条」会让人以为「这一批全都回上了」，
 * 而被跳过的那几条其实一条没回。
 */
export function batchSummaryLabel(result: {
  batch: { targetCount: number; createdCount: number; skippedCount: number };
}): string {
  const { targetCount, createdCount, skippedCount } = result.batch;
  if (targetCount === 0) return '本次没有匹配到任何目标评价（可能都已经回过了）。';
  if (skippedCount === 0) return `目标 ${targetCount} 条，全部写成 ${createdCount} 条回复。`;
  return `目标 ${targetCount} 条，写成 ${createdCount} 条，**跳过 ${skippedCount} 条**（每条都有原因）。`;
}
