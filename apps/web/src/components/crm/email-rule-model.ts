import type {
  CrmEmailRuleAssignmentPage,
  CrmEmailRuleAssignmentRow,
  CrmEmailRuleCapabilities,
  CrmEmailRuleEvaluateBatchResult,
  CrmEmailRuleEvaluateResult,
  CrmEmailRuleHitDetail,
  CrmEmailRuleHitPage,
  CrmEmailRuleHitRow,
  CrmEmailRuleInput,
  CrmEmailRuleOptions,
  CrmEmailRulePage,
  CrmEmailRulePreviewResult,
  CrmEmailRuleResult,
  CrmEmailRuleRow,
  CrmEmailRuleStatistics,
  CrmEmailRuleMatchBasis,
  CrmEmailRuleConditionDetail,
  CrmEmailRuleSnapshot,
  CrmEmailTagAssignmentRow,
  CrmEmailTicketTagInput,
  CrmEmailTicketTagPage,
  CrmEmailTicketTagResult,
  CrmEmailTicketTagRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.1.2.2 邮件规则前端模型。
 *
 * <p>蓝图：「配置邮件自动处理规则：按发件人/关键词/工单标签自动分配、自动回复、静默规则
 * （重要沟通不可静默）。输入：规则配置；15.1.1.3 分类结果。输出：规则命中记录；自动分配结果。」
 * 关系：上游 = 15.1.1.3；下游 = 15.4.1.1 工单分配。**关键沟通静默会造成平台违规**，规则需强制留痕。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>声明的动作 / 实际生效的动作，两个都要显示</b>：静默被拒时后端返回
 *       {@code effectiveAction = 'ESCALATE_MANUAL'} —— 页面必须把
 *       「规则要静默」与「实际没静默、已转人工」<b>同时</b>显示出来。</li>
 *   <li><b>「静默被拒」是必须显眼的结论</b>：重要沟通命中静默规则时，
 *       页面用告警色标出并显示理由 —— 静默重要沟通会造成平台违规。</li>
 *   <li><b>命中记录只追加</b>：页面不提供「删除命中记录」「改命中结果」的入口。</li>
 *   <li><b>命中凭据冻结当时的规则</b>：详情里显示<b>当时那一版</b>的规则快照，
 *       并标出「这是第 N 版」—— 改规则不会追溯改变历史命中记录。</li>
 *   <li><b>未交付的下游不编</b>：15.4.1.1 工单与 15.5.1 自动回复**都未交付**，
 *       页面**不提供**「已转工单」「已自动回复」这种看起来能点的按钮，
 *       只显示「等下游取」与「等下游投递」。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有规则」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/email-rules}，与同服务的 {@code crm/buyer-emails}
 * （15.1.2.1 邮件收发）刻意不共用 —— 那一个管**邮件本身**，本模块管**收到邮件之后按什么规则处理**。
 */

const base = 'crm/email-rules';

/**
 * 规则列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface EmailRuleParams {
  keyword?: string | undefined;
  action?: string | undefined;
  enabled?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 命中记录的筛选条件。 */
export interface EmailRuleHitParams {
  emailId?: string | undefined;
  ruleCode?: string | undefined;
  action?: string | undefined;
  effectiveAction?: string | undefined;
  silenceRefused?: boolean | undefined;
  important?: boolean | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 自动分配结果的筛选条件。 */
export interface EmailRuleAssignmentParams {
  emailId?: string | undefined;
  ruleCode?: string | undefined;
  targetCode?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 工单标签的筛选条件。 */
export interface EmailRuleTagParams {
  keyword?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 新建 / 修改一条规则时的表单。 */
export interface EmailRuleDraft {
  ruleCode: string;
  ruleName: string;
  priorityOrder: number;
  enabled: boolean;
  action: string;
  senderEmails: string[];
  senderDomains: string[];
  keywords: string[];
  ticketTagCodes: string[];
  matchMode: string;
  assignTargetType: string;
  assignTargetCode: string;
  replyTemplateCode: string;
  replySubject: string;
  replyBody: string;
  importantMarkers: string[];
  note: string;
}

/** 拼装查询串（空值一律不拼，省得后端收到 `?action=`）。 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 规则列表的查询串。 */
export function emailRuleQuery(params: EmailRuleParams = {}): string {
  return query({
    keyword: params.keyword,
    action: params.action,
    enabled: params.enabled,
    page: params.page,
    size: params.size,
  });
}

/** 命中记录列表的查询串。 */
export function emailRuleHitQuery(params: EmailRuleHitParams = {}): string {
  return query({
    emailId: params.emailId,
    ruleCode: params.ruleCode,
    action: params.action,
    effectiveAction: params.effectiveAction,
    silenceRefused: params.silenceRefused,
    important: params.important,
    keyword: params.keyword,
    page: params.page,
    size: params.size,
  });
}

/** 自动分配结果列表的查询串。 */
export function emailRuleAssignmentQuery(params: EmailRuleAssignmentParams = {}): string {
  return query({
    emailId: params.emailId,
    ruleCode: params.ruleCode,
    targetCode: params.targetCode,
    status: params.status,
    page: params.page,
    size: params.size,
  });
}

/** 工单标签列表的查询串。 */
export function emailRuleTagQuery(params: EmailRuleTagParams = {}): string {
  return query({
    keyword: params.keyword,
    status: params.status,
    page: params.page,
    size: params.size,
  });
}

// ================================================================ 能力声明与词表

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchEmailRuleCapabilities() {
  return api<CrmEmailRuleCapabilities>(`${base}/capabilities`);
}

/** 筛选词表（动作与命中方式是 DB 允许的取值域；标签码来自本租户真实数据）。 */
export function fetchEmailRuleOptions() {
  return api<CrmEmailRuleOptions>(`${base}/options`);
}

// ================================================================ 规则

/** 规则列表（真分页 + 真筛选）。 */
export function fetchEmailRules(params: EmailRuleParams = {}) {
  return api<CrmEmailRulePage>(`${base}/rules${emailRuleQuery(params)}`);
}

/** 新建一条规则。 */
export function createEmailRule(input: EmailRuleDraft) {
  return api<CrmEmailRuleResult>(`${base}/rules`, 'POST', input);
}

/** 修改一条规则（规则编码建后不可改；版本 +1）。 */
export function updateEmailRule(ruleId: string, input: EmailRuleDraft) {
  return api<CrmEmailRuleResult>(`${base}/rules/${encodeURIComponent(ruleId)}`, 'PUT', input);
}

/** 删除一条规则（已产生的命中记录与分配结果不受影响）。 */
export function deleteEmailRule(ruleId: string) {
  return api<CrmEmailRuleResult>(`${base}/rules/${encodeURIComponent(ruleId)}`, 'DELETE');
}

/** 试算：这条规则对这封邮件会不会命中（**不写命中记录**）。 */
export function previewEmailRule(ruleId: string, emailId: string) {
  return api<CrmEmailRulePreviewResult>(
    `${base}/rules/${encodeURIComponent(ruleId)}/preview`,
    'POST',
    {
      emailId,
    },
  );
}

// ================================================================ 工单标签

/** 工单标签列表（真分页 + 真筛选）。 */
export function fetchEmailRuleTags(params: EmailRuleTagParams = {}) {
  return api<CrmEmailTicketTagPage>(`${base}/tags${emailRuleTagQuery(params)}`);
}

/** 新建一个工单标签。 */
export function createEmailRuleTag(input: CrmEmailTicketTagInput) {
  return api<CrmEmailTicketTagResult>(`${base}/tags`, 'POST', input);
}

/** 修改一个工单标签（标签码建后不可改）。 */
export function updateEmailRuleTag(tagId: string, input: CrmEmailTicketTagInput) {
  return api<CrmEmailTicketTagResult>(`${base}/tags/${encodeURIComponent(tagId)}`, 'PUT', input);
}

/** 删除一个工单标签（被规则条件或打标记录引用时删不掉）。 */
export function deleteEmailRuleTag(tagId: string) {
  return api<CrmEmailTicketTagResult>(`${base}/tags/${encodeURIComponent(tagId)}`, 'DELETE');
}

/** 某封邮件当前挂的工单标签。 */
export function fetchEmailTagsOf(emailId: string) {
  return api<CrmEmailTagAssignmentRow[]>(`${base}/emails/${encodeURIComponent(emailId)}/tags`);
}

/** 给一封邮件打上工单标签（幂等：同一标签重复打不重复记账）。 */
export function assignEmailTag(emailId: string, tagCode: string) {
  return api<CrmEmailTagAssignmentRow>(
    `${base}/emails/${encodeURIComponent(emailId)}/tags`,
    'POST',
    {
      tagCode,
    },
  );
}

// ================================================================ 评估

/** 评估一封邮件（按顺序评估启用中的规则，第一条命中即生效）。 */
export function evaluateEmail(emailId: string, ruleId?: string) {
  return api<CrmEmailRuleEvaluateResult>(
    `${base}/emails/${encodeURIComponent(emailId)}/evaluate`,
    'POST',
    ruleId ? { emailId, ruleId } : {},
  );
}

/** 批量评估：逐条给出结论与原因。 */
export function evaluateEmails(emailIds: string[]) {
  return api<CrmEmailRuleEvaluateBatchResult>(`${base}/evaluate-batch`, 'POST', { emailIds });
}

// ================================================================ 命中记录

/** 命中记录列表（真分页 + 真筛选）。 */
export function fetchEmailRuleHits(params: EmailRuleHitParams = {}) {
  return api<CrmEmailRuleHitPage>(`${base}/hits${emailRuleHitQuery(params)}`);
}

/** 单条命中详情 = 命中记录 + 邮件快照 + 当时的规则快照 + 分配结果。 */
export function fetchEmailRuleHit(hitId: string) {
  return api<CrmEmailRuleHitDetail>(`${base}/hits/${encodeURIComponent(hitId)}`);
}

// ================================================================ 自动分配结果

/** 自动分配结果列表（真分页 + 真筛选）。 */
export function fetchEmailRuleAssignments(params: EmailRuleAssignmentParams = {}) {
  return api<CrmEmailRuleAssignmentPage>(`${base}/assignments${emailRuleAssignmentQuery(params)}`);
}

/** 单条自动分配结果。 */
export function fetchEmailRuleAssignment(assignmentId: string) {
  return api<CrmEmailRuleAssignmentRow>(`${base}/assignments/${encodeURIComponent(assignmentId)}`);
}

/** 取消一条自动分配结果（必须写理由）。 */
export function cancelEmailRuleAssignment(assignmentId: string, cancelReason: string) {
  return api<CrmEmailRuleAssignmentRow>(
    `${base}/assignments/${encodeURIComponent(assignmentId)}/cancel`,
    'POST',
    { cancelReason },
  );
}

// ================================================================ 统计

/** 规则命中统计 + 动作分布 + 静默被拒数。 */
export function fetchEmailRuleStatistics() {
  return api<CrmEmailRuleStatistics>(`${base}/statistics`);
}

// ================================================================ 可读形态

/** 命中动作的可读名。 */
export function actionLabel(action: string): string {
  switch (action) {
    case 'AUTO_ASSIGN':
      return '自动分配';
    case 'AUTO_REPLY':
      return '自动回复';
    case 'SILENCE':
      return '静默';
    case 'ESCALATE_MANUAL':
      return '不静默 + 转人工';
    default:
      return action;
  }
}

/** 命中方式的可读名。 */
export function matchModeLabel(mode: string): string {
  switch (mode) {
    case 'ANY':
      return '任一命中';
    case 'ALL':
      return '全部命中';
    default:
      return mode;
  }
}

/** 分配目标类型的可读名。 */
export function targetTypeLabel(type: string): string {
  switch (type) {
    case 'TEAM':
      return '组';
    case 'AGENT':
      return '人';
    default:
      return type;
  }
}

/** 重要沟通判定来源的可读名。 */
export function importantSourceLabel(source: string): string {
  switch (source) {
    case 'CLASSIFICATION':
      return '分类结果（上游 15.1.1.3）';
    case 'RULE_MARKER':
      return '规则标记的关键词';
    default:
      return '不是重要沟通';
  }
}

/**
 * 🔴 命中结论的可读形态：**声明的动作 / 实际生效的动作 / 是否被拒** 三件事都要说清。
 *
 * <p>只显示一个动作的话，看的人会以为「规则要静默 ⇒ 这封真的静默了」——
 * 而实际上重要沟通命中静默规则时会被强制降级成「不静默 + 转人工」。
 */
export function hitActionText(hit: {
  action: string;
  effectiveAction: string;
  silenceRefused: boolean;
  refusalReason?: string | undefined;
}): string {
  const parts = [`规则动作 ${actionLabel(hit.action)}`];
  if (hit.action !== hit.effectiveAction) {
    parts.push(`实际生效 ${actionLabel(hit.effectiveAction)}`);
  }
  if (hit.silenceRefused) {
    parts.push('⚠️ 静默被拒（重要沟通不可静默）');
    if (hit.refusalReason) parts.push(hit.refusalReason);
  }
  return parts.join(' · ');
}

/** 一条规则的可读摘要。 */
export function ruleText(rule: CrmEmailRuleRow): string {
  const parts = [`顺序 ${rule.priorityOrder}`, `版本 ${rule.version}`, actionLabel(rule.action)];
  if ((rule.senderEmails ?? []).length > 0) {
    parts.push(`发件人邮箱 ${(rule.senderEmails ?? []).join('、')}`);
  }
  if ((rule.senderDomains ?? []).length > 0) {
    parts.push(`发件人域名 ${(rule.senderDomains ?? []).join('、')}`);
  }
  if ((rule.keywords ?? []).length > 0) {
    parts.push(`关键词 ${(rule.keywords ?? []).join('、')}`);
  }
  if ((rule.ticketTagCodes ?? []).length > 0) {
    parts.push(`工单标签 ${(rule.ticketTagCodes ?? []).join('、')}`);
  }
  if (
    (rule.senderEmails ?? []).length === 0 &&
    (rule.senderDomains ?? []).length === 0 &&
    (rule.keywords ?? []).length === 0 &&
    (rule.ticketTagCodes ?? []).length === 0
  ) {
    parts.push('⚠️ 没有配置任何条件维度（永远不会命中）');
  }
  if (rule.action === 'AUTO_ASSIGN') {
    parts.push(`分给 ${targetTypeLabel(rule.assignTargetType)} ${rule.assignTargetCode}`);
  }
  if (rule.action === 'AUTO_REPLY') {
    parts.push(`话术 ${rule.replyTemplateCode || '（无模板码）'}`);
  }
  if ((rule.importantMarkers ?? []).length > 0) {
    parts.push(`重要标记 ${(rule.importantMarkers ?? []).join('、')}`);
  }
  parts.push(rule.enabled ? '已启用' : '已停用');
  return parts.join(' · ');
}

/** 一条命中记录的可读摘要。 */
export function hitText(hit: CrmEmailRuleHitRow): string {
  const parts = [
    `规则 ${hit.ruleCode}（第 ${hit.ruleVersion} 版）`,
    hitActionText(hit),
    hit.important ? `重要沟通（${importantSourceLabel(hit.importantSource)}）` : '普通沟通',
  ];
  if (hit.assignmentId) parts.push('已产出分配结果');
  if (hit.deliveryStatus === 'PENDING_DOWNSTREAM') {
    parts.push('自动回复待下游投递（未发出）');
  }
  return parts.join(' · ');
}

/** 命中依据的可读形态。 */
export function matchBasisText(basis: CrmEmailRuleMatchBasis | null | undefined): string {
  if (!basis) return '没有命中依据（这条邮件还没评估过）。';
  const matches = basis.matches ?? [];
  if (matches.length === 0) {
    return '没有规则命中：邮件保持原样（不静默、不分配、不回复）。';
  }
  const parts = matches.map((match) => {
    const conditions = match.conditions as CrmEmailRuleConditionDetail | undefined;
    const hits: string[] = [];
    const sender = conditions?.sender;
    if (sender && (sender.matchedEmails ?? []).length > 0) {
      hits.push(`发件人邮箱命中 ${(sender.matchedEmails ?? []).join('、')}`);
    }
    if (sender && (sender.matchedDomains ?? []).length > 0) {
      hits.push(`发件人域名命中 ${(sender.matchedDomains ?? []).join('、')}`);
    }
    const keywords = conditions?.keywords;
    if (keywords && (keywords.matchedKeywords ?? []).length > 0) {
      hits.push(`关键词命中 ${(keywords.matchedKeywords ?? []).join('、')}`);
    }
    const tags = conditions?.ticketTags;
    if (tags && (tags.matchedCodes ?? []).length > 0) {
      hits.push(`工单标签命中 ${(tags.matchedCodes ?? []).join('、')}`);
    }
    const won = match.won ? '，**生效**' : '，未生效';
    return `规则 ${match.ruleCode}（${match.ruleName}）命中 ${hits.join('、') || '无'}${won}`;
  });
  return parts.join('；');
}

/** 规则快照的可读形态（冻结当时那一版）。 */
export function snapshotText(snapshot: CrmEmailRuleSnapshot | null | undefined): string {
  if (!snapshot) return '没有规则快照。';
  const parts = [
    `第 ${snapshot.version} 版`,
    actionLabel(snapshot.action),
    `顺序 ${snapshot.priorityOrder}`,
  ];
  if ((snapshot.keywords ?? []).length > 0)
    parts.push(`关键词 ${(snapshot.keywords ?? []).join('、')}`);
  if ((snapshot.senderEmails ?? []).length > 0) {
    parts.push(`发件人邮箱 ${(snapshot.senderEmails ?? []).join('、')}`);
  }
  if ((snapshot.ticketTagCodes ?? []).length > 0) {
    parts.push(`工单标签 ${(snapshot.ticketTagCodes ?? []).join('、')}`);
  }
  if (snapshot.action === 'AUTO_ASSIGN') {
    parts.push(`分给 ${targetTypeLabel(snapshot.assignTargetType)} ${snapshot.assignTargetCode}`);
  }
  if (snapshot.action === 'AUTO_REPLY') {
    parts.push(`话术 ${snapshot.replyTemplateCode || '（无模板码）'}`);
  }
  return parts.join(' · ');
}

/** 一条自动分配结果的可读摘要。 */
export function assignmentText(assignment: CrmEmailRuleAssignmentRow): string {
  const parts = [
    `规则 ${assignment.ruleCode}（第 ${assignment.ruleVersion} 版）`,
    `分给 ${targetTypeLabel(assignment.assignTargetType)} ${assignment.assignTargetCode}`,
  ];
  if (assignment.status === 'PENDING_DISPATCH') {
    parts.push('等下游取（15.4.1.1 工单未交付 ⇒ 还没有工单）');
  } else if (assignment.status === 'DISPATCHED') {
    parts.push('已投递');
  } else {
    parts.push(`已取消：${assignment.cancelReason}`);
  }
  return parts.join(' · ');
}

/** 统计的可读形态（计数全部来自服务端，JS 侧不做算术）。 */
export function statisticsText(statistics: CrmEmailRuleStatistics | null | undefined): string {
  if (!statistics) return '暂无统计';
  const parts = [
    `命中 ${statistics.totalHitCount} 次`,
    `静默被拒 ${statistics.refusedCount} 次`,
    `重要沟通 ${statistics.importantCount} 次`,
    `待下游取 ${statistics.pendingAssignmentCount} 条`,
  ];
  if ((statistics.byEffectiveAction ?? []).length > 0) {
    parts.push(
      `实际动作 ${statistics.byEffectiveAction.map((b) => `${b.label}×${b.count}`).join(' · ')}`,
    );
  }
  return parts.join(' · ');
}

/** 一条规则命中统计的可读形态。 */
export function ruleStatText(stat: {
  ruleCode: string;
  ruleName: string;
  hitCount: number;
  refusedCount: number;
  assignmentCount: number;
  enabled: boolean;
}): string {
  const parts = [
    `命中 ${stat.hitCount} 次`,
    `静默被拒 ${stat.refusedCount} 次`,
    `分配结果 ${stat.assignmentCount} 条`,
    stat.enabled ? '已启用' : '已停用',
  ];
  return `${stat.ruleCode}（${stat.ruleName}）· ${parts.join(' · ')}`;
}

/**
 * 当前是否**有筛选条件**。
 *
 * <p>空态要区分两件完全不同的事：「**筛选无结果**」（条件太窄，换个条件就有了）
 * 与「**还没有规则**」（一条规则都没配）。
 */
export function hasActiveFilter(params: EmailRuleHitParams = {}): boolean {
  return Boolean(
    params.emailId ||
    params.ruleCode ||
    params.action ||
    params.effectiveAction ||
    params.silenceRefused !== undefined ||
    params.important !== undefined ||
    params.keyword,
  );
}

/**
 * 解析关键词草稿：一行一个关键词。
 *
 * <p>空行跳过；**空关键词不静默丢弃** —— 前端悄悄丢一条关键词，
 * 用户会以为规则已经配好了，而它其实永远不会命中。
 */
export function parseKeywordDraft(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

/**
 * 解析「发件人域名 / 工单标签码」草稿：逗号分隔。
 *
 * <p>留空 = 该维度不参与判定（后端按空值放行，不是一份写死的清单）。
 */
export function parseCodeDraft(text: string): string[] {
  return text
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
}
