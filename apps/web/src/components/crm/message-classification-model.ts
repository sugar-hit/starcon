import type {
  CrmMessageClassificationBatchResult,
  CrmMessageClassificationCapabilities,
  CrmMessageClassificationClassifyResult,
  CrmMessageClassificationDetail,
  CrmMessageClassificationFeedbackPage,
  CrmMessageClassificationFeedbackResult,
  CrmMessageClassificationMatchBasis,
  CrmMessageClassificationOptions,
  CrmMessageClassificationPage,
  CrmMessageClassificationRulePage,
  CrmMessageClassificationRuleResult,
  CrmMessageClassificationRuleRow,
  CrmMessageClassificationRow,
  CrmMessageClassificationStatistics,
  CrmMessageClassificationSummary,
  CrmMessageClassificationTrainingSample,
  CrmMessageClassificationTrainingSamplePage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.1.1.3 消息分类前端模型。
 *
 * <p>蓝图：「对消息自动分类（售前咨询、物流查询、退货退款、投诉、平台合规、垃圾信息），
 * 驱动自动回复与优先级，支持人工纠正以改进分类。输入：消息内容；分类规则（关键词 + 平台类型 +
 * 历史纠正数据）。输出：分类标签 + 优先级 + 是否可自动回复。」
 * 关系：上游 = 15.1.1.1；下游 = 15.5.1 自动回复、1.3 预警。
 * **人工纠正要回流为训练样本**，否则分类准确率永远上不去。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>人工值 / 建议值 / 是否冲突，三者都要显示</b>：后端<b>不改</b>人工设过的优先级，
 *       只把「建议值」和「人工值」两个数一起给出来并标记冲突 ——
 *       只显示一个的话，看的人会以为那就是唯一结论。</li>
 *   <li><b>「可自动回复」是判定结果，不是自动回复功能</b>：15.5.1 未交付，
 *       页面必须写清这一点，并<b>不提供</b>「触发自动回复」这种看起来能点的按钮。</li>
 *   <li><b>命中依据要能复核</b>：命中了哪条规则的哪个关键词、在哪个字段命中，都显示出来。</li>
 *   <li><b>纠正只追加</b>：页面不提供「撤销纠正」「修改纠正」的入口 ——
 *       训练样本改得掉，准确率指标就随时可以被改漂亮。</li>
 *   <li><b>训练样本带正文</b>：少了正文就只剩标签对，训不出「哪句话被判成了什么」。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有分类过」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/message-classifications}，与同服务的 {@code crm/message-inbox}、
 * {@code crm/message-sends} 刻意不共用 —— 那两个管**消息本身**，本模块管**怎么给消息分类**。
 */

const base = 'crm/message-classifications';

/**
 * 分类结论的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface ClassificationParams {
  messageId?: string | undefined;
  label?: string | undefined;
  suggestedPriority?: string | undefined;
  priorityConflict?: boolean | undefined;
  autoReplyEligible?: boolean | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 分类结论的筛选条件。 */
export interface RuleParams {
  keyword?: string | undefined;
  targetLabel?: string | undefined;
  enabled?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 训练样本的筛选条件。 */
export interface SampleParams {
  correctedLabel?: string | undefined;
  predictedLabel?: string | undefined;
  ruleCode?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 拼装查询串（空值一律不拼，省得后端收到 `?label=`）。 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 分类结论列表的查询串。 */
export function classificationQuery(params: ClassificationParams = {}): string {
  return query({
    messageId: params.messageId,
    label: params.label,
    suggestedPriority: params.suggestedPriority,
    priorityConflict: params.priorityConflict,
    autoReplyEligible: params.autoReplyEligible,
    keyword: params.keyword,
    page: params.page,
    size: params.size,
  });
}

/** 规则列表的查询串。 */
export function ruleQuery(params: RuleParams = {}): string {
  return query({
    keyword: params.keyword,
    targetLabel: params.targetLabel,
    enabled: params.enabled,
    page: params.page,
    size: params.size,
  });
}

/** 训练样本列表的查询串。 */
export function sampleQuery(params: SampleParams = {}): string {
  return query({
    correctedLabel: params.correctedLabel,
    predictedLabel: params.predictedLabel,
    ruleCode: params.ruleCode,
    keyword: params.keyword,
    page: params.page,
    size: params.size,
  });
}

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchClassificationCapabilities() {
  return api<CrmMessageClassificationCapabilities>(`${base}/capabilities`);
}

/** 筛选词表（标签与优先级是 DB 允许的取值域；平台与消息类型来自本租户真实数据）。 */
export function fetchClassificationOptions() {
  return api<CrmMessageClassificationOptions>(`${base}/options`);
}

/** 规则列表（真分页 + 真筛选）。 */
export function fetchClassificationRules(params: RuleParams = {}) {
  return api<CrmMessageClassificationRulePage>(`${base}/rules${ruleQuery(params)}`);
}

/** 新建一条规则。 */
export function createClassificationRule(input: {
  ruleCode: string;
  ruleName: string;
  priorityOrder: number;
  targetLabel: string;
  suggestedPriority: string;
  autoReplyEligible: boolean;
  keywords: string[];
  matchMode: string;
  platformCodes: string[];
  messageTypes: string[];
  enabled: boolean;
  note: string;
}) {
  return api<CrmMessageClassificationRuleResult>(`${base}/rules`, 'POST', input);
}

/** 修改一条规则（规则编码建后不可改）。 */
export function updateClassificationRule(
  ruleId: string,
  input: {
    ruleCode: string;
    ruleName: string;
    priorityOrder: number;
    targetLabel: string;
    suggestedPriority: string;
    autoReplyEligible: boolean;
    keywords: string[];
    matchMode: string;
    platformCodes: string[];
    messageTypes: string[];
    enabled: boolean;
    note: string;
  },
) {
  return api<CrmMessageClassificationRuleResult>(
    `${base}/rules/${encodeURIComponent(ruleId)}`,
    'PUT',
    input,
  );
}

/** 删除一条规则（已产生的结论与纠正不受影响）。 */
export function deleteClassificationRule(ruleId: string) {
  return api<CrmMessageClassificationRuleResult>(
    `${base}/rules/${encodeURIComponent(ruleId)}`,
    'DELETE',
  );
}

/** 分类结论列表（真分页 + 真筛选 + 与分页无关的全量汇总）。 */
export function fetchClassifications(params: ClassificationParams = {}) {
  return api<CrmMessageClassificationPage>(`${base}/classifications${classificationQuery(params)}`);
}

/** 单条结论详情 = 结论 + 命中依据 + 消息正文 + 纠正历史。 */
export function fetchClassification(classificationId: string) {
  return api<CrmMessageClassificationDetail>(
    `${base}/classifications/${encodeURIComponent(classificationId)}`,
  );
}

/** 某条消息的当前结论 + 命中依据 + 纠正历史。 */
export function fetchMessageClassification(messageId: string) {
  return api<CrmMessageClassificationDetail>(`${base}/messages/${encodeURIComponent(messageId)}`);
}

/** 分类一条消息。 */
export function classifyMessage(messageId: string) {
  return api<CrmMessageClassificationClassifyResult>(`${base}/classify`, 'POST', { messageId });
}

/** 批量分类（逐条给出结论与原因）。 */
export function classifyMessages(messageIds: string[]) {
  return api<CrmMessageClassificationBatchResult>(`${base}/classify-batch`, 'POST', { messageIds });
}

/** 人工纠正（只追加）。 */
export function correctClassification(input: {
  messageId: string;
  correctedLabel: string;
  correctedPriority: string;
  correctedAutoReplyEligible: boolean;
  reason: string;
}) {
  return api<CrmMessageClassificationFeedbackResult>(`${base}/feedback`, 'POST', input);
}

/** 纠正记录列表（真分页 + 真筛选）。 */
export function fetchClassificationFeedback(params: SampleParams = {}) {
  return api<CrmMessageClassificationFeedbackPage>(`${base}/feedback${sampleQuery(params)}`);
}

/** 训练样本：机器预测 + 人工纠正 + 消息正文。 */
export function fetchTrainingSamples(params: SampleParams = {}) {
  return api<CrmMessageClassificationTrainingSamplePage>(
    `${base}/training-samples${sampleQuery(params)}`,
  );
}

/** 分类统计：规则命中 + 标签分布 + 冲突 + 纠正一致率。 */
export function fetchClassificationStatistics() {
  return api<CrmMessageClassificationStatistics>(`${base}/statistics`);
}

// ================================================================ 可读形态

/** 分类标签的可读名（与后端词表一一对应）。 */
export function labelLabel(label: string): string {
  switch (label) {
    case 'PRE_SALE':
      return '售前咨询';
    case 'LOGISTICS':
      return '物流查询';
    case 'RETURN_REFUND':
      return '退货退款';
    case 'COMPLAINT':
      return '投诉';
    case 'PLATFORM_COMPLIANCE':
      return '平台合规';
    case 'SPAM':
      return '垃圾信息';
    case 'UNCLASSIFIED':
      return '未分类';
    default:
      return label;
  }
}

/** 优先级的可读名。 */
export function priorityLabel(priority: string): string {
  switch (priority) {
    case 'LOW':
      return '低';
    case 'NORMAL':
      return '普通';
    case 'HIGH':
      return '高';
    case 'URGENT':
      return '紧急';
    default:
      return priority;
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

/**
 * 当前是否**有筛选条件**。
 *
 * <p>空态要区分两件完全不同的事：「**筛选无结果**」（条件太窄，换个条件就有了）
 * 与「**还没有分类过**」（一条消息都没跑过分类）。
 */
export function hasActiveFilter(params: ClassificationParams = {}): boolean {
  return Boolean(
    params.messageId ||
    params.label ||
    params.suggestedPriority ||
    params.priorityConflict !== undefined ||
    params.autoReplyEligible !== undefined ||
    params.keyword,
  );
}

/**
 * 🔴 优先级的可读形态：**人工值 / 建议值 / 生效值 / 是否冲突**四件事都要说清。
 *
 * <p>只显示一个数字的话，看的人会以为那就是唯一结论 —— 而实际上
 * 「机器建议低」和「客服设的紧急」是两回事。
 */
export function priorityText(row: {
  suggestedPriority: string;
  manualPriority: string;
  effectivePriority: string;
  priorityConflict: boolean;
}): string {
  const parts = [
    `建议 ${priorityLabel(row.suggestedPriority)}`,
    `人工 ${priorityLabel(row.manualPriority)}`,
    `生效 ${priorityLabel(row.effectivePriority)}`,
  ];
  if (row.priorityConflict) {
    parts.push('⚠️ 冲突：人工值与建议值不一致，按人工值生效（要不要改由人决定）');
  } else {
    parts.push('人工值与建议值一致');
  }
  return parts.join(' · ');
}

/**
 * 命中依据的可读形态。
 *
 * <p>⚠️ 没有命中任何规则时**不返回空串**：返回「没有规则命中」，
 * 否则页面上的空白会被读成「还没分类」。
 */
export function matchBasisText(
  basis: CrmMessageClassificationMatchBasis | null | undefined,
): string {
  if (!basis) return '没有命中依据（这条消息还没分类过）。';
  const matches = basis.matches ?? [];
  if (matches.length === 0) {
    return '没有规则命中：标签记为「未分类」，且不建议自动回复。';
  }
  const parts = matches.map((match) => {
    const keywords = (match.keywords ?? [])
      .map((hit) => `「${hit.keyword}」(${hit.field === 'SUBJECT' ? '主题' : '正文'})`)
      .join('、');
    const won = match.won ? '，**生效**' : '，未生效';
    return `规则 ${match.ruleCode}（${match.ruleName}）命中 ${keywords}${won}`;
  });
  return parts.join('；');
}

/**
 * 分类结论一行摘要。
 *
 * <p>⚠️ 金额与数量**原样展示**，JS 侧不做任何算术（AGENTS.md）。
 */
export function classificationText(row: CrmMessageClassificationRow): string {
  const parts = [
    labelLabel(row.label),
    priorityText(row),
    row.autoReplyEligible ? '可自动回复（判定结果，15.5.1 未交付）' : '不可自动回复',
  ];
  if (row.matchedRuleCode) {
    parts.push(`命中规则 ${row.matchedRuleCode}`);
  }
  if (row.correctionCount > 0) {
    parts.push(`已纠正 ${row.correctionCount} 次`);
  }
  return parts.join(' · ');
}

/** 一条规则的可读摘要。 */
export function ruleText(rule: CrmMessageClassificationRuleRow): string {
  const parts = [
    `顺序 ${rule.priorityOrder}`,
    labelLabel(rule.targetLabel),
    `建议 ${priorityLabel(rule.suggestedPriority)}`,
    rule.autoReplyEligible ? '可自动回复' : '不可自动回复',
    `${matchModeLabel(rule.matchMode)}：${(rule.keywords ?? []).join('、')}`,
  ];
  if ((rule.platformCodes ?? []).length > 0) {
    parts.push(`适用平台 ${(rule.platformCodes ?? []).join('、')}`);
  } else {
    parts.push('适用全部平台');
  }
  if ((rule.messageTypes ?? []).length > 0) {
    parts.push(`适用类型 ${(rule.messageTypes ?? []).join('、')}`);
  } else {
    parts.push('适用全部类型');
  }
  parts.push(rule.enabled ? '已启用' : '已停用');
  return parts.join(' · ');
}

/** 分类汇总的可读形态（计数全部来自服务端，JS 侧不做算术）。 */
export function summaryText(summary: CrmMessageClassificationSummary | null | undefined): string {
  if (!summary) return '暂无汇总';
  const parts = [
    `共 ${summary.totalCount} 条结论`,
    `人工与建议冲突 ${summary.conflictCount} 条`,
    `可自动回复 ${summary.autoReplyEligibleCount} 条`,
    `已纠正 ${summary.correctedCount} 条`,
  ];
  if ((summary.byLabel ?? []).length > 0) {
    parts.push(`标签 ${summary.byLabel.map((b) => `${b.label}×${b.count}`).join(' · ')}`);
  }
  return parts.join(' · ');
}

/** 统计的可读形态。 */
export function statisticsText(
  statistics: CrmMessageClassificationStatistics | null | undefined,
): string {
  if (!statistics) return '暂无统计';
  const parts = [
    `已分类 ${statistics.totalCount} 条`,
    `冲突 ${statistics.conflictCount} 条`,
    `可自动回复 ${statistics.autoReplyEligibleCount} 条`,
    `纠正 ${statistics.correctionCount} 条`,
    `纠正一致率 ${statistics.correctionAgreementRate}`,
  ];
  return parts.join(' · ');
}

/**
 * 一条规则命中统计的可读形态。
 *
 * <p>「分歧」= 人工改成了别的标签的次数 —— 那是「这条规则要改」的信号。
 */
export function ruleStatText(stat: {
  ruleCode: string;
  ruleName: string;
  hitCount: number;
  correctionCount: number;
  disagreementCount: number;
  enabled: boolean;
}): string {
  const parts = [
    `命中 ${stat.hitCount} 次`,
    `被纠正 ${stat.correctionCount} 次`,
    `标签分歧 ${stat.disagreementCount} 次`,
    stat.enabled ? '已启用' : '已停用',
  ];
  return `${stat.ruleCode}（${stat.ruleName}）· ${parts.join(' · ')}`;
}

/** 训练样本的可读形态。 */
export function trainingSampleText(sample: CrmMessageClassificationTrainingSample): string {
  const parts = [
    `机器判 ${labelLabel(sample.predictedLabel)}`,
    `人工改成 ${labelLabel(sample.correctedLabel)}`,
    sample.predictedRuleCode ? `命中规则 ${sample.predictedRuleCode}` : '没命中规则',
    `建议 ${priorityLabel(sample.predictedPriority)} → 人工 ${priorityLabel(sample.correctedPriority)}`,
  ];
  if (!sample.labelAgreed) {
    parts.push('⚠️ 机器判错了 —— 这一条就是训练样本');
  }
  return parts.join(' · ');
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
 * 解析「适用平台 / 适用消息类型」草稿：逗号分隔。
 *
 * <p>留空 = 适用全部（后端按空值放行，不是一份写死的清单）。
 */
export function parseCodeDraft(text: string): string[] {
  return text
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
}
