import type {
  CrmNegativeReviewActionInput,
  CrmNegativeReviewActionRow,
  CrmNegativeReviewAppealCancelInput,
  CrmNegativeReviewAppealCreateInput,
  CrmNegativeReviewAppealOutcomeInput,
  CrmNegativeReviewAppealRow,
  CrmNegativeReviewAppealSubmittedInput,
  CrmNegativeReviewCandidatePage,
  CrmNegativeReviewCapabilities,
  CrmNegativeReviewCaseCreateInput,
  CrmNegativeReviewCasePage,
  CrmNegativeReviewCaseRow,
  CrmNegativeReviewClassifyInput,
  CrmNegativeReviewDetail,
  CrmNegativeReviewRuleInput,
  CrmNegativeReviewRulePage,
  CrmNegativeReviewRuleRow,
  CrmNegativeReviewRuleUpdateInput,
  CrmNegativeReviewShopView,
  CrmNegativeReviewStats,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.2.1.3 差评处理前端模型。
 *
 * <p>蓝图：「差评专项处理：标记原因（产品/物流/服务/误判）、判定是否申诉（部分平台支持）、
 * 联系买家解决后请求修改、跟踪解决率。输入：差评内容；处理动作；买家沟通记录。
 * 输出：处理记录 + 申诉任务 + 解决状态。关系：上游 = 15.2.1.1；下游 = 15.1.1.2 联系买家、
 * 11 售后（解决问题）。**解决差评的正确路径是先解决买家问题再请求修改，
 * 直接申诉容易被驳回且消耗申诉配额。**」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「未接入」要显式灰掉</b>：平台差评申诉接口（409 且零行写入）、买家站内信发送、
 *       11 售后转交单据三项，页面**不提供**看起来能点的假入口。</li>
 *   <li><b>解决率只原样展示</b>：分子分母与口径全部由后端算好，页面**不做任何前端算术** ——
 *       前端一算，口径就会与后端漂移。</li>
 *   <li><b>顺序闸要看得见</b>：没走完「联系买家」时，页面把「请求修改 / 建申诉任务」
 *       显式禁用并说明原因，而不是让人点了再吃一个 409。</li>
 *   <li><b>判定依据要显示出来</b>：只显示「可申诉 / 不可申诉」而不给依据，
 *       运营没法复核这条判定。</li>
 *   <li><b>空态要区分两种</b>：「筛选无结果」与「还没有处理单」是两句不同的话。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/negative-reviews}，与同服务的 {@code crm/reviews}（评价列表）、
 * {@code crm/review-replies}（评价回复）、{@code crm/review-requests}（索评）、
 * {@code crm/review-rules}（索评规则）**刻意不共用**。
 */

const base = 'crm/negative-reviews';

// ================================================================ 查询串

/** 处理单列表的筛选条件。 */
export interface NegativeReviewCaseParams {
  shopId?: string | undefined;
  platformCode?: string | undefined;
  causeCode?: string | undefined;
  caseStatus?: string | undefined;
  resolutionStatus?: string | undefined;
  appealEligible?: boolean | undefined;
  rating?: number | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 候选差评（还没建处理单的那些）的筛选条件。 */
export interface NegativeReviewCandidateParams {
  shopId?: string | undefined;
  platformCode?: string | undefined;
  rating?: number | undefined;
  keyword?: string | undefined;
  negativeOnly?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 规则的筛选条件。 */
export interface NegativeReviewRuleParams {
  ruleScope?: string | undefined;
  platformCode?: string | undefined;
  causeCode?: string | undefined;
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

/** 处理单列表的查询串。 */
export function negativeReviewCaseQuery(params: NegativeReviewCaseParams = {}): string {
  return queryOf({ ...params });
}

/** 候选差评的查询串。 */
export function negativeReviewCandidateQuery(params: NegativeReviewCandidateParams = {}): string {
  return queryOf({ ...params });
}

/** 规则的查询串。 */
export function negativeReviewRuleQuery(params: NegativeReviewRuleParams = {}): string {
  return queryOf({ ...params });
}

/**
 * 解决率统计的查询串（比列表多一个 {@code days}，少分页参数）。
 *
 * <p>刻意做成一个**独立的函数**而不是在模板里拼字符串 ——
 * 空白筛选时不能产出 `?` 后面直接跟 `&` 的畸形查询串。
 */
export function negativeReviewStatsQuery(params: NegativeReviewCaseParams = {}, days = 30): string {
  const query = negativeReviewCaseQuery({ ...params, page: undefined, size: undefined });
  return query ? `${query}&days=${days}` : `?days=${days}`;
}

// ================================================================ 端点

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchNegativeReviewCapabilities() {
  return api<CrmNegativeReviewCapabilities>(`${base}/capabilities`);
}

/** 店铺目录（来自上游 channel 的真实清单）+ 每个平台的差评处理策略状态。 */
export function fetchNegativeReviewShops() {
  return api<CrmNegativeReviewShopView[]>(`${base}/shops`);
}

/** 规则列表（真分页 + 真筛选）。 */
export function fetchNegativeReviewRules(params: NegativeReviewRuleParams = {}) {
  return api<CrmNegativeReviewRulePage>(`${base}/rules${negativeReviewRuleQuery(params)}`);
}

/** 单条规则。 */
export function fetchNegativeReviewRule(ruleId: string) {
  return api<CrmNegativeReviewRuleRow>(`${base}/rules/${encodeURIComponent(ruleId)}`);
}

/** 新建规则（平台策略 / 原因规则）。 */
export function createNegativeReviewRule(input: CrmNegativeReviewRuleInput) {
  return api<CrmNegativeReviewRuleRow>(`${base}/rules`, 'POST', input);
}

/** 更新规则（原地改 = 版本 +1）。 */
export function updateNegativeReviewRule(ruleId: string, input: CrmNegativeReviewRuleUpdateInput) {
  return api<CrmNegativeReviewRuleRow>(`${base}/rules/${encodeURIComponent(ruleId)}`, 'PUT', input);
}

/** 候选差评清单（还没建处理单的那些）。 */
export function fetchNegativeReviewCandidates(params: NegativeReviewCandidateParams = {}) {
  return api<CrmNegativeReviewCandidatePage>(
    `${base}/candidates${negativeReviewCandidateQuery(params)}`,
  );
}

/** 处理单列表（真分页 + 真筛选）。 */
export function fetchNegativeReviewCases(params: NegativeReviewCaseParams = {}) {
  return api<CrmNegativeReviewCasePage>(`${base}${negativeReviewCaseQuery(params)}`);
}

/** 为一条差评建处理单。 */
export function createNegativeReviewCase(input: CrmNegativeReviewCaseCreateInput) {
  return api<CrmNegativeReviewCaseRow>(`${base}`, 'POST', input);
}

/**
 * 解决率统计（分母 = 处理单数，分子 = 已解决数）。
 *
 * <p>⚠️ 全部由**服务端**现算，前端只原样展示。
 */
export function fetchNegativeReviewStats(params: NegativeReviewCaseParams = {}, days = 30) {
  return api<CrmNegativeReviewStats>(`${base}/stats${negativeReviewStatsQuery(params, days)}`);
}

/** 处理单详情 = 处理单 + 动作流水 + 申诉任务 + 关联订单上下文 + 当时用的规则。 */
export function fetchNegativeReviewCase(caseId: string) {
  return api<CrmNegativeReviewDetail>(`${base}/${encodeURIComponent(caseId)}`);
}

/** 标记原因分类（顺带按规则判定是否可申诉）。 */
export function classifyNegativeReviewCase(caseId: string, input: CrmNegativeReviewClassifyInput) {
  return api<CrmNegativeReviewCaseRow>(
    `${base}/${encodeURIComponent(caseId)}/classify`,
    'POST',
    input,
  );
}

/** 处理单的动作流水。 */
export function fetchNegativeReviewActions(caseId: string) {
  return api<CrmNegativeReviewActionRow[]>(`${base}/${encodeURIComponent(caseId)}/actions`);
}

/**
 * 记录一个处理动作。
 *
 * <p>⚠️ 走平台侧的动作（{@code transport=PLATFORM_API}）在适配器未接入时
 * 返回 **409** 且**一行都不写** —— 页面据此显示「未接入」，不给假回执。
 */
export function recordNegativeReviewAction(caseId: string, input: CrmNegativeReviewActionInput) {
  return api<CrmNegativeReviewActionRow>(
    `${base}/${encodeURIComponent(caseId)}/actions`,
    'POST',
    input,
  );
}

/** 处理单的申诉任务列表。 */
export function fetchNegativeReviewAppeals(caseId: string) {
  return api<CrmNegativeReviewAppealRow[]>(`${base}/${encodeURIComponent(caseId)}/appeals`);
}

/** 建一个申诉任务（配额即占用）。 */
export function createNegativeReviewAppeal(
  caseId: string,
  input: CrmNegativeReviewAppealCreateInput,
) {
  return api<CrmNegativeReviewAppealRow>(
    `${base}/${encodeURIComponent(caseId)}/appeals`,
    'POST',
    input,
  );
}

/**
 * 向平台提交申诉。
 *
 * <p>⚠️ 当前恒为 409（平台差评申诉适配器未交付），后端**一行都不写**。
 */
export function submitNegativeReviewAppeal(caseId: string, appealId: string) {
  return api<CrmNegativeReviewAppealRow>(
    `${base}/${encodeURIComponent(caseId)}/appeals/${encodeURIComponent(appealId)}/submit`,
    'POST',
  );
}

/** 人工交接登记：运营已在平台侧实际提交申诉。 */
export function markNegativeReviewAppealSubmitted(
  caseId: string,
  appealId: string,
  input: CrmNegativeReviewAppealSubmittedInput,
) {
  return api<CrmNegativeReviewAppealRow>(
    `${base}/${encodeURIComponent(caseId)}/appeals/${encodeURIComponent(appealId)}/submitted`,
    'POST',
    input,
  );
}

/** 人工交接登记：平台对申诉的判定（通过 / 驳回）。 */
export function recordNegativeReviewAppealOutcome(
  caseId: string,
  appealId: string,
  input: CrmNegativeReviewAppealOutcomeInput,
) {
  return api<CrmNegativeReviewAppealRow>(
    `${base}/${encodeURIComponent(caseId)}/appeals/${encodeURIComponent(appealId)}/outcome`,
    'POST',
    input,
  );
}

/** 撤回申诉任务（撤回归还申诉配额）。 */
export function cancelNegativeReviewAppeal(
  caseId: string,
  appealId: string,
  input: CrmNegativeReviewAppealCancelInput,
) {
  return api<CrmNegativeReviewAppealRow>(
    `${base}/${encodeURIComponent(caseId)}/appeals/${encodeURIComponent(appealId)}/cancel`,
    'POST',
    input,
  );
}

// ================================================================ 可读形态

/** 原因分类的可读名。 */
export function causeLabel(causeCode: string): string {
  switch (causeCode) {
    case 'PRODUCT':
      return '产品问题';
    case 'LOGISTICS':
      return '物流问题';
    case 'SERVICE':
      return '服务问题';
    case 'MISJUDGMENT':
      return '误判';
    default:
      return causeCode || '（未分类）';
  }
}

/** 处理状态的可读名。 */
export function caseStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '待处理';
    case 'IN_PROGRESS':
      return '处理中';
    case 'RESOLVED':
      return '已解决';
    case 'CLOSED':
      return '已关闭';
    default:
      return status;
  }
}

/** 解决状态的可读名。 */
export function resolutionStatusLabel(status: string): string {
  switch (status) {
    case 'UNRESOLVED':
      return '未解决';
    case 'RESOLVED':
      return '已解决';
    case 'CLOSED_UNRESOLVED':
      return '已关闭未解决';
    default:
      return status;
  }
}

/** 处理动作类型的可读名。 */
export function actionTypeLabel(actionType: string): string {
  switch (actionType) {
    case 'CONTACT_BUYER':
      return '联系买家';
    case 'REQUEST_MODIFICATION':
      return '请求修改';
    case 'APPEAL_SUBMISSION':
      return '申诉提交';
    case 'APPEAL_RESULT':
      return '申诉结果';
    case 'APPEAL_CANCEL':
      return '撤回申诉';
    case 'AFTER_SALES_HANDOFF':
      return '转交售后';
    case 'RESOLVE':
      return '标记已解决';
    case 'CLOSE':
      return '关闭处理单';
    case 'NOTE':
      return '备注';
    default:
      return actionType;
  }
}

/** 触达方式的可读名。 */
export function transportLabel(transport: string): string {
  switch (transport) {
    case 'PLATFORM_API':
      return '平台接口';
    case 'MANUAL_HANDOFF':
      return '人工交接';
    case 'INTERNAL':
      return '内部动作';
    default:
      return transport;
  }
}

/** 动作结果的可读名。 */
export function outcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'PENDING':
      return '进行中';
    default:
      return outcome;
  }
}

/** 申诉类型的可读名。 */
export function appealTypeLabel(appealType: string): string {
  switch (appealType) {
    case 'REMOVAL':
      return '申请移除差评';
    case 'MODIFICATION':
      return '申请修改差评内容';
    case 'POLICY_VIOLATION':
      return '平台违规申诉';
    default:
      return appealType;
  }
}

/** 申诉任务状态的可读名。 */
export function appealStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '待提交';
    case 'SUBMITTED':
      return '已提交';
    case 'APPROVED':
      return '已通过';
    case 'REJECTED':
      return '已驳回';
    case 'CANCELLED':
      return '已撤回';
    default:
      return status;
  }
}

/** 规则作用域的可读名。 */
export function ruleScopeLabel(scope: string): string {
  switch (scope) {
    case 'PLATFORM':
      return '平台策略';
    case 'CAUSE':
      return '原因规则';
    default:
      return scope;
  }
}

/**
 * 解决率的可读形态。
 *
 * <p>⚠️ **只做展示格式化，不做算术** —— 比例是后端算的（numeric，禁浮点），
 * 前端只把它渲染成百分号文本；分母为 0 时后端给 null ⇒ 这里显示「—」。
 */
export function resolutionRateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—';
  return `${(rate * 100).toFixed(2)}%`;
}
