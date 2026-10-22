import type {
  CrmCustomerTagAssignmentInput,
  CrmCustomerTagAssignmentPage,
  CrmCustomerTagAssignmentRow,
  CrmCustomerTagCapabilities,
  CrmCustomerTagCustomerCandidatePage,
  CrmCustomerTagDistribution,
  CrmCustomerTagEvaluationPage,
  CrmCustomerTagInput,
  CrmCustomerTagPage,
  CrmCustomerTagReviewInput,
  CrmCustomerTagRuleInput,
  CrmCustomerTagRulePage,
  CrmCustomerTagRuleRow,
  CrmCustomerTagRunInput,
  CrmCustomerTagRunPage,
  CrmCustomerTagRunRow,
  CrmCustomerTagRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.3.2.1 客户标签前端模型。
 *
 * <p>蓝图：「维护客户标签体系（VIP、复购、退货率高、高客单、风险、渠道来源等），
 * 支持自动规则打标（按订单行为）与手工打标，用于筛选与营销。」
 * 输出「标签记录 + 标签分布统计」。
 * 关系：上游 = 9 订单、11 售后；下游 = 15.3.1.1 筛选、14.4 促销定向、15.5.2 关怀邮件。
 * **"退货率高"标签要谨慎使用**，避免对正常买家产生歧视性动作。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「不可判定」是一等状态</b>：样本不足的结论在界面上必须和「不命中」分开显示。
 *       合成一个「否」会让「样本不足」看起来像「这个人没问题」。</li>
 *   <li><b>来源可分</b>：人工标的标签与规则标的标签**分开显示** ——
 *       运营要知道这枚标签是谁给的。</li>
 *   <li><b>判定依据必须看得见</b>：每条自动标签都能展开看「依据哪几笔订单 / 哪几张售后单」。</li>
 *   <li><b>不触发任何动作</b>：能力声明里 {@code promotion_targeting} /
 *       {@code care_email} / {@code customer_segmentation} 恒为 available=false，
 *       页面**不提供**「按标签发促销」「按标签发关怀邮件」的按钮。</li>
 *   <li><b>金额与数量只原样展示</b>：金额由后端给出，页面**不做任何前端算术**
 *       （占比也由后端算好，前端只原样显示）。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有标签定义」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/customer-tags}，与同服务的 {@code crm/knowledge}、
 * {@code crm/review-requests}、{@code crm/review-rules}、{@code crm/buyer-emails} **刻意不共用**。
 */

const base = 'crm/customer-tags';

/** 判定依据快照（后端 jsonb，结构随口径而异）。 */
export type CustomerTagEvidence = Record<string, unknown>;

/** 候选客户的累计消费（**按币种分开**：USD 与 EUR 加起来不是任何一个金额）。 */
export type CustomerTagAmounts = Record<string, string>;

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CustomerTagParams {
  category?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CustomerTagRuleParams {
  tagId?: string | undefined;
  metric?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CustomerTagAssignmentParams {
  tagId?: string | undefined;
  customerRef?: string | undefined;
  source?: string | undefined;
  state?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CustomerTagRunParams {
  ruleId?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CustomerTagEvaluationParams {
  outcome?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CustomerTagCandidateParams {
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function query(params: Record<string, string | number | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 能力声明

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchCustomerTagCapabilities() {
  return api<CrmCustomerTagCapabilities>(`${base}/capabilities`);
}

// ================================================================ 标签定义

export function fetchCustomerTags(params: CustomerTagParams = {}) {
  return api<CrmCustomerTagPage>(`${base}/tags${query({ ...params })}`);
}

export function fetchCustomerTag(tagId: string) {
  return api<CrmCustomerTagRow>(`${base}/tags/${encodeURIComponent(tagId)}`);
}

export function createCustomerTag(input: CrmCustomerTagInput) {
  return api<CrmCustomerTagRow>(`${base}/tags`, 'POST', input);
}

export function updateCustomerTag(tagId: string, input: CrmCustomerTagInput) {
  return api<CrmCustomerTagRow>(`${base}/tags/${encodeURIComponent(tagId)}`, 'PUT', input);
}

/**
 * 删标签定义。
 *
 * <p>⚠️ 已被规则或标签记录引用过的**删不掉**（后端 409）：
 * 删掉之后「这位客户身上有哪枚标签」在历史里就只剩一个孤零零的 tag_id。
 */
export function deleteCustomerTag(tagId: string) {
  return api<void>(`${base}/tags/${encodeURIComponent(tagId)}`, 'DELETE');
}

// ================================================================ 自动打标规则

export function fetchCustomerTagRules(params: CustomerTagRuleParams = {}) {
  return api<CrmCustomerTagRulePage>(`${base}/rules${query({ ...params })}`);
}

export function fetchCustomerTagRule(ruleId: string) {
  return api<CrmCustomerTagRuleRow>(`${base}/rules/${encodeURIComponent(ruleId)}`);
}

export function createCustomerTagRule(input: CrmCustomerTagRuleInput) {
  return api<CrmCustomerTagRuleRow>(`${base}/rules`, 'POST', input);
}

export function updateCustomerTagRule(ruleId: string, input: CrmCustomerTagRuleInput) {
  return api<CrmCustomerTagRuleRow>(`${base}/rules/${encodeURIComponent(ruleId)}`, 'PUT', input);
}

/** 删规则（已有标签记录或运行记录的删不掉，后端 409）。 */
export function deleteCustomerTagRule(ruleId: string) {
  return api<void>(`${base}/rules/${encodeURIComponent(ruleId)}`, 'DELETE');
}

/**
 * 跑一次规则。
 *
 * <p>⚠️ 上游（trade）读不到时，后端**一条标签都不打**，只把失败记进运行记录
 * （{@code status='FAILED'} + 原因）。页面必须把失败显式显示出来，
 * 绝不能让它看起来像「跑过了但没人命中」。
 */
export function runCustomerTagRule(ruleId: string, input: CrmCustomerTagRunInput) {
  return api<CrmCustomerTagRunRow>(
    `${base}/rules/${encodeURIComponent(ruleId)}/run`,
    'POST',
    input,
  );
}

// ================================================================ 运行记录

export function fetchCustomerTagRuns(params: CustomerTagRunParams = {}) {
  return api<CrmCustomerTagRunPage>(`${base}/runs${query({ ...params })}`);
}

export function fetchCustomerTagRun(runId: string) {
  return api<CrmCustomerTagRunRow>(`${base}/runs/${encodeURIComponent(runId)}`);
}

/** 逐客户判定结论（`outcome=INDETERMINATE` 能筛出「样本不足 ⇒ 不可判定」的那批人）。 */
export function fetchCustomerTagEvaluations(
  runId: string,
  params: CustomerTagEvaluationParams = {},
) {
  return api<CrmCustomerTagEvaluationPage>(
    `${base}/runs/${encodeURIComponent(runId)}/evaluations${query({ ...params })}`,
  );
}

// ================================================================ 标签记录

export function fetchCustomerTagAssignments(params: CustomerTagAssignmentParams = {}) {
  return api<CrmCustomerTagAssignmentPage>(`${base}/assignments${query({ ...params })}`);
}

export function fetchCustomerTagAssignment(assignmentId: string) {
  return api<CrmCustomerTagAssignmentRow>(
    `${base}/assignments/${encodeURIComponent(assignmentId)}`,
  );
}

/**
 * 手工打标。
 *
 * <p>⚠️ 可争议标签（退货率高 / 风险）**必须带原因**，否则后端 400。
 */
export function createCustomerTagAssignment(input: CrmCustomerTagAssignmentInput) {
  return api<CrmCustomerTagAssignmentRow>(`${base}/assignments`, 'POST', input);
}

/**
 * 人工复核（确认 / 撤销）。
 *
 * <p>⚠️ 撤销**必须**写原因；撤销之后自动规则重跑**不会**把它恢复。
 */
export function reviewCustomerTagAssignment(
  assignmentId: string,
  input: CrmCustomerTagReviewInput,
) {
  return api<CrmCustomerTagAssignmentRow>(
    `${base}/assignments/${encodeURIComponent(assignmentId)}/review`,
    'POST',
    input,
  );
}

// ================================================================ 分布统计

/**
 * 标签分布统计。
 *
 * <p>占比在**服务端**算好；分母为 0 时后端给 {@code null}，
 * 页面显示「不计算」而不是 0%。
 */
export function fetchCustomerTagDistribution() {
  return api<CrmCustomerTagDistribution>(`${base}/distribution`);
}

// ================================================================ 候选客户

/** 候选客户（来自上游订单事实的真实聚合；上游读不到时后端 503）。 */
export function fetchCustomerTagCustomerCandidates(params: CustomerTagCandidateParams = {}) {
  return api<CrmCustomerTagCustomerCandidatePage>(
    `${base}/customer-candidates${query({ ...params })}`,
  );
}

// ================================================================ 可读形态

/** 标签分类的可读名。 */
export function customerTagCategoryLabel(category: string): string {
  switch (category) {
    case 'VALUE':
      return '价值分层';
    case 'BEHAVIOR':
      return '行为';
    case 'RISK':
      return '风险与售后';
    case 'SOURCE':
      return '渠道来源';
    case 'CUSTOM':
      return '自定义';
    default:
      return category;
  }
}

/** 判定口径的可读名。 */
export function customerTagMetricLabel(metric: string): string {
  switch (metric) {
    case 'ORDER_COUNT':
      return '窗口内订单数达到阈值';
    case 'REPEAT_PURCHASE':
      return '窗口内订单数达到阈值（复购）';
    case 'HIGH_VALUE':
      return '窗口内该币种累计消费达到阈值';
    case 'RETURN_RATE':
      return '退货率显著高于租户基线';
    case 'CHANNEL_SOURCE':
      return '窗口内出现过指定渠道来源';
    default:
      return metric;
  }
}

/** 标签来源的可读名。 */
export function customerTagSourceLabel(source: string): string {
  switch (source) {
    case 'MANUAL':
      return '人工打标';
    case 'RULE':
      return '规则打标';
    default:
      return source;
  }
}

/** 标签记录状态的可读名。 */
export function customerTagStateLabel(state: string): string {
  switch (state) {
    case 'ACTIVE':
      return '生效';
    case 'REVOKED':
      return '已撤销';
    default:
      return state;
  }
}

/**
 * 判定结论的可读名。
 *
 * <p>🔴 「不可判定」与「不命中」是**两件不同的事**，这里刻意用不同的文案：
 * 把它们合成一个「否」会让「样本不足」看起来像「这个人没问题」。
 */
export function customerTagOutcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'TAGGED':
      return '命中并打标';
    case 'ALREADY_TAGGED':
      return '命中且此前已打标（幂等，未重复打）';
    case 'REVOKED':
      return '本次不再命中，已撤销自动标签';
    case 'SKIPPED_REVOKED':
      return '本次仍命中，但人工已撤销，未恢复';
    case 'INDETERMINATE':
      return '不可判定（样本不足）';
    case 'NOT_MATCHED':
      return '判定为不命中';
    default:
      return outcome;
  }
}

/**
 * 占比的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：百分数由后端算好（{@code sharePercent}），这里只原样拼上 `%`；
 * 分母为 0 时后端给 {@code null} —— 这里显示「不计算」而不是 0%。
 */
export function customerTagShareText(sharePercent: number | null | undefined): string {
  if (sharePercent === null || sharePercent === undefined) return '不计算';
  return `${sharePercent}%`;
}

/** 判定依据快照转成可读对象（后端 jsonb，结构随口径而异）。 */
export function customerTagEvidence(value: unknown): CustomerTagEvidence {
  if (value && typeof value === 'object') return value as CustomerTagEvidence;
  return {};
}

/** 候选客户的金额（**按币种分开**，不跨币种加）。 */
export function customerTagAmounts(value: unknown): CustomerTagAmounts {
  if (value && typeof value === 'object') return value as CustomerTagAmounts;
  return {};
}

/**
 * 判定依据里最要紧的几项（供界面摘要用）。
 *
 * <p>⚠️ 这里只做**取字段**，不做任何换算 —— 数值原样透传给界面显示。
 */
export function customerTagEvidenceSummary(evidence: CustomerTagEvidence): string[] {
  const lines: string[] = [];
  const orderCount = evidence['windowOrderCount'];
  if (typeof orderCount === 'number') lines.push(`窗口内订单 ${orderCount} 单`);
  const orderNos = evidence['orderNos'];
  if (Array.isArray(orderNos) && orderNos.length > 0) {
    lines.push(`依据订单：${orderNos.join('、')}`);
  }
  const caseNos = evidence['returnCaseNos'];
  if (Array.isArray(caseNos) && caseNos.length > 0) {
    lines.push(`依据售后单：${caseNos.join('、')}`);
  }
  const rate = evidence['returnRate'];
  if (typeof rate === 'number') lines.push(`退货率 ${rate}`);
  const baseline = evidence['baseline'];
  if (typeof baseline === 'number') lines.push(`租户基线 ${baseline}`);
  return lines;
}
