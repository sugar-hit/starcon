import type {
  AutomationRuleEffectAdviceInput,
  AutomationRuleEffectAdvicePage,
  AutomationRuleEffectAdviceRow,
  AutomationRuleEffectBaselineInput,
  AutomationRuleEffectBaselinePage,
  AutomationRuleEffectBaselineRow,
  AutomationRuleEffectCapabilities,
  AutomationRuleEffectFeedbackInput,
  AutomationRuleEffectFeedbackPage,
  AutomationRuleEffectFeedbackRow,
  AutomationRuleEffectOverview,
  AutomationRuleEffectRuleDetail,
  AutomationRuleEffectRulePage,
  AutomationRuleEffectRunPage,
  AutomationRuleEffectSavingBasis,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.3.2.2「规则效果」前端模型。
 *
 * 蓝图：「<strong>统计规则效果</strong>：触发次数、成功率、平均耗时、节省工时预估、
 * 误触发反馈与规则优化建议。 输入：执行日志聚合。 输出：规则效果报表。
 * 关系：上游 = 18.3.2.1；下游 = 18.3.1.1（优化依据）。
 * <strong>误触发率高的规则要能快速定位并关闭</strong>，自动化失控比没有自动化更危险。」
 *
 * <p>🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「触发次数」是「记录条数」，不是「规则自动触发了几次」**。18.3.2 执行引擎
 *    <b>尚未交付</b> ⇒ 这些行绝大多数是人工登记或「未执行」的重跑请求。
 *    页面顶部常驻提示条写清这一点，绝不把人工登记说成自动触发。
 * 2. **「未执行」既不是成功也不是失败**。它单列一档，<b>不进</b>成功率的母 ——
 *    否则「执行器没接通所以一次没跑」会被算成失败，报表会说谎。
 * 3. **「节省工时预估」必须带口径**。只显示一个数字而不显示「怎么算出来的」，
 *    那个数字不能用于决策 ⇒ 口径说明与数字一起渲染。
 * 4. **「没有口径」是空，不是 0**。没有登记人工基线的规则，
 *    节省工时显示「—（未登记口径）」而不是「0 分钟」。
 * 5. **「关闭」不是本模块的动作**。误触发率高的规则要能<b>快速定位</b>
 *    （规则 id + 规则名 + 指向规则档案的引用），但「关闭」归 18.3.1.1 ——
 *    页面写清这一点，绝不提供一个点了也没用的「关闭」按钮。
 *
 * <p><b>类型全部从契约派生</b>（{@code packages/api-contracts} 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/automation/effect';

/** 误触发分类（与后端 `AutomationRuleEffectFalseTriggerType` 逐字一致）。 */
export const FALSE_TRIGGER_TYPES = [
  'WRONG_CONDITION',
  'WRONG_SCOPE',
  'TOO_FREQUENT',
  'UNEXPECTED_ACTION',
  'OTHER',
] as const;

export type FalseTriggerType = (typeof FALSE_TRIGGER_TYPES)[number];

/** 反馈状态（与后端 `AutomationRuleEffectFeedbackStatus` 逐字一致）。 */
export const FEEDBACK_STATUSES = ['OPEN', 'ACCEPTED', 'DISMISSED'] as const;

export type FeedbackStatus = (typeof FEEDBACK_STATUSES)[number];

/** 建议类型（与后端 `AutomationRuleEffectAdviceType` 逐字一致）。 */
export const ADVICE_TYPES = [
  'FALSE_TRIGGER_HIGH',
  'HIGH_FAILURE_RATE',
  'TIMEOUT_HIGH',
  'NOT_ATTEMPTED_HIGH',
  'SKIPPED_HIGH',
  'SLOW',
  'NO_BASELINE',
  'INSUFFICIENT_DATA',
  'PERFORMING_WELL',
] as const;

export type AdviceType = (typeof ADVICE_TYPES)[number];

/** 严重度（与后端 `AutomationRuleEffectAdviceSeverity` 逐字一致）。 */
export const SEVERITIES = ['CRITICAL', 'WARNING', 'INFO'] as const;

export type Severity = (typeof SEVERITIES)[number];

/** 徽章只有 7 种 tone，这里收敛成 4 种语义。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

// ================================================================ 读取

/** 能力声明：哪些真交付、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<AutomationRuleEffectCapabilities> {
  return api<AutomationRuleEffectCapabilities>(`${base}/capabilities`);
}

/** 规则效果概览。 */
export function fetchOverview(): Promise<AutomationRuleEffectOverview> {
  return api<AutomationRuleEffectOverview>(`${base}/overview`);
}

/** 按规则的效果明细（**真分页 + 真筛选**，后端 SQL 过滤）。 */
export function fetchRules(params?: {
  keyword?: string | undefined;
  onlyFalseTrigger?: boolean | undefined;
  onlyWithAdvice?: boolean | undefined;
  falseTriggerRateAtLeast?: string | undefined;
  adviseDisableOnly?: boolean | undefined;
  order?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AutomationRuleEffectRulePage> {
  const search = new URLSearchParams();
  if (params?.keyword) search.set('keyword', params.keyword);
  if (params?.onlyFalseTrigger !== undefined) {
    search.set('onlyFalseTrigger', String(params.onlyFalseTrigger));
  }
  if (params?.onlyWithAdvice !== undefined) {
    search.set('onlyWithAdvice', String(params.onlyWithAdvice));
  }
  if (params?.falseTriggerRateAtLeast) {
    search.set('falseTriggerRateAtLeast', params.falseTriggerRateAtLeast);
  }
  if (params?.adviseDisableOnly !== undefined) {
    search.set('adviseDisableOnly', String(params.adviseDisableOnly));
  }
  if (params?.order) search.set('order', params.order);
  if (params?.page !== undefined) search.set('page', String(params.page));
  if (params?.size !== undefined) search.set('size', String(params.size));
  const query = search.toString();
  // ⚠️ 查询串**不拼在模板串里**：嵌套模板会让白名单守卫的反向抽取把 `${suffix` 当成路径的一部分。
  return api<AutomationRuleEffectRulePage>(query ? `${base}/rules?${query}` : `${base}/rules`);
}

/** 单条规则的效果详情（聚合 + 口径 + 反馈 + 建议 + 可下钻的执行记录）。 */
export function fetchRuleDetail(ruleId: string): Promise<AutomationRuleEffectRuleDetail> {
  return api<AutomationRuleEffectRuleDetail>(`${base}/rules/${encodeURIComponent(ruleId)}`);
}

/** 执行记录下钻（**真分页 + 真筛选**）。 */
export function fetchRuns(params?: {
  ruleId?: string | undefined;
  outcome?: string | undefined;
  keyword?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AutomationRuleEffectRunPage> {
  const search = new URLSearchParams();
  if (params?.ruleId) search.set('ruleId', params.ruleId);
  if (params?.outcome) search.set('outcome', params.outcome);
  if (params?.keyword) search.set('keyword', params.keyword);
  if (params?.from) search.set('from', params.from);
  if (params?.to) search.set('to', params.to);
  if (params?.page !== undefined) search.set('page', String(params.page));
  if (params?.size !== undefined) search.set('size', String(params.size));
  const query = search.toString();
  return api<AutomationRuleEffectRunPage>(query ? `${base}/runs?${query}` : `${base}/runs`);
}

/** 节省工时口径列表（**真分页**）。 */
export function fetchBaselines(params?: {
  ruleId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AutomationRuleEffectBaselinePage> {
  const search = new URLSearchParams();
  if (params?.ruleId) search.set('ruleId', params.ruleId);
  if (params?.page !== undefined) search.set('page', String(params.page));
  if (params?.size !== undefined) search.set('size', String(params.size));
  const query = search.toString();
  return api<AutomationRuleEffectBaselinePage>(
    query ? `${base}/baselines?${query}` : `${base}/baselines`,
  );
}

/** 误触发反馈列表（**真分页 + 真筛选**）。 */
export function fetchFeedbacks(params?: {
  ruleId?: string | undefined;
  status?: string | undefined;
  falseTriggerType?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AutomationRuleEffectFeedbackPage> {
  const search = new URLSearchParams();
  if (params?.ruleId) search.set('ruleId', params.ruleId);
  if (params?.status) search.set('status', params.status);
  if (params?.falseTriggerType) search.set('falseTriggerType', params.falseTriggerType);
  if (params?.keyword) search.set('keyword', params.keyword);
  if (params?.page !== undefined) search.set('page', String(params.page));
  if (params?.size !== undefined) search.set('size', String(params.size));
  const query = search.toString();
  return api<AutomationRuleEffectFeedbackPage>(
    query ? `${base}/feedbacks?${query}` : `${base}/feedbacks`,
  );
}

/** 优化建议列表（**真分页 + 真筛选**）。 */
export function fetchAdvices(params?: {
  ruleId?: string | undefined;
  status?: string | undefined;
  adviceType?: string | undefined;
  severity?: string | undefined;
  source?: string | undefined;
  onlyActive?: boolean | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AutomationRuleEffectAdvicePage> {
  const search = new URLSearchParams();
  if (params?.ruleId) search.set('ruleId', params.ruleId);
  if (params?.status) search.set('status', params.status);
  if (params?.adviceType) search.set('adviceType', params.adviceType);
  if (params?.severity) search.set('severity', params.severity);
  if (params?.source) search.set('source', params.source);
  if (params?.onlyActive !== undefined) search.set('onlyActive', String(params.onlyActive));
  if (params?.keyword) search.set('keyword', params.keyword);
  if (params?.page !== undefined) search.set('page', String(params.page));
  if (params?.size !== undefined) search.set('size', String(params.size));
  const query = search.toString();
  return api<AutomationRuleEffectAdvicePage>(
    query ? `${base}/advices?${query}` : `${base}/advices`,
  );
}

// ================================================================ 写入

/**
 * 🔴 **登记一条误触发反馈**。
 *
 * <p>反馈**必须能定位**：{@code ruleId} 必填；{@code runId} 可空（反馈不一定能定位到某一次）。
 */
export function submitFeedback(
  input: AutomationRuleEffectFeedbackInput,
): Promise<AutomationRuleEffectFeedbackRow> {
  return api<AutomationRuleEffectFeedbackRow>(`${base}/feedbacks`, 'POST', input);
}

/**
 * 🔴 **配置 / 更新一条规则的节省工时口径**。
 *
 * <p>一条规则一条**当前**口径：重复提交是 upsert，不会堆出一串历史行。
 */
export function saveBaseline(
  input: AutomationRuleEffectBaselineInput,
): Promise<AutomationRuleEffectBaselineRow> {
  return api<AutomationRuleEffectBaselineRow>(`${base}/baselines`, 'PUT', input);
}

/** 🔴 **登记一条人工优化建议**（系统重新生成时**不动**它）。 */
export function submitAdvice(
  input: AutomationRuleEffectAdviceInput,
): Promise<AutomationRuleEffectAdviceRow> {
  return api<AutomationRuleEffectAdviceRow>(`${base}/advices`, 'POST', input);
}

/** 🔴 **依当前聚合重新生成系统建议**（幂等；已处理的状态不被覆盖）。 */
export function refreshAdvices(): Promise<{
  createdAdvices: number;
  updatedAdvices: number;
  deactivatedAdvices: number;
  scannedRules: number;
}> {
  return api<{
    createdAdvices: number;
    updatedAdvices: number;
    deactivatedAdvices: number;
    scannedRules: number;
  }>(`${base}/advices/refresh`, 'POST', {});
}

/**
 * 🔴 **处理一条误触发反馈**（标成「已采纳」或「已忽略」）。
 *
 * <p>⚠️ 处理**不改变**误触发率：一条被「已忽略」的反馈仍然是发生过的误触发，
 * 把它从误触发率里抹掉等于让报表说谎。
 */
export function setFeedbackStatus(
  id: string,
  status: 'OPEN' | 'ACCEPTED' | 'DISMISSED',
): Promise<AutomationRuleEffectFeedbackRow> {
  return api<AutomationRuleEffectFeedbackRow>(
    `${base}/feedbacks/${encodeURIComponent(id)}/status?status=${status}`,
    'PUT',
  );
}

/**
 * 🔴 **处理一条优化建议**（标成「已采纳」或「已忽略」）。
 *
 * <p>⚠️ 系统重新生成建议时**不会**覆盖状态 —— 人的决定不该被重新计算推翻。
 */
export function setAdviceStatus(
  id: string,
  status: 'OPEN' | 'ADOPTED' | 'IGNORED',
): Promise<AutomationRuleEffectAdviceRow> {
  return api<AutomationRuleEffectAdviceRow>(
    `${base}/advices/${encodeURIComponent(id)}/status?status=${status}`,
    'PUT',
  );
}

// ================================================================ 纯函数（可单测）

/** 结论 → 徽章语气（五档与 18.3.2.1 逐字一致）。 */
export function outcomeTone(outcome: string): Tone {
  switch (outcome) {
    case 'SUCCEEDED':
      return 'ok';
    case 'FAILED':
      return 'danger';
    case 'TIMEOUT':
      return 'warn';
    case 'NOT_ATTEMPTED':
      return 'warn';
    default:
      return 'muted';
  }
}

/** 结论 → 人话（页面上的中文标签；后端也给了 label，这里做兜底）。 */
export function outcomeLabel(outcome: string, fallback?: string): string {
  switch (outcome) {
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'SKIPPED':
      return '跳过';
    case 'TIMEOUT':
      return '超时';
    case 'NOT_ATTEMPTED':
      return '未执行';
    default:
      return fallback || outcome || '未知';
  }
}

/** 严重度 → 徽章语气。 */
export function severityTone(severity: string): Tone {
  switch (severity) {
    case 'CRITICAL':
      return 'danger';
    case 'WARNING':
      return 'warn';
    default:
      return 'muted';
  }
}

/** 反馈状态 → 徽章语气。 */
export function feedbackStatusTone(status: string): Tone {
  switch (status) {
    case 'OPEN':
      return 'warn';
    case 'ACCEPTED':
      return 'ok';
    default:
      return 'muted';
  }
}

/** 误触发分类 → 人话。 */
export function falseTriggerTypeLabel(type: string, fallback?: string): string {
  switch (type) {
    case 'WRONG_CONDITION':
      return '条件写错了';
    case 'WRONG_SCOPE':
      return '生效范围不对';
    case 'TOO_FREQUENT':
      return '触发太频繁';
    case 'UNEXPECTED_ACTION':
      return '执行了不该执行的动作';
    case 'OTHER':
      return '其它';
    default:
      return fallback || type || '未知';
  }
}

/**
 * 成功率 → 人话。
 *
 * <p>🔴 **分母为 0 时是「—」，不是「0%」** —— 「没有样本」与「成功率是零」
 * 是两件事，混成一件报表就会说谎。
 */
export function formatRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || !Number.isFinite(rate)) return '—';
  return `${(rate * 100).toFixed(1)}%`;
}

/**
 * 节省工时（分钟）→ 人话。
 *
 * <p>🔴 **没有口径时是「—（未登记口径）」，不是「0 分钟」** ——
 * 「按 0 分钟算」会把「从没评估过」说成「评估结果是省了 0 分钟」。
 */
export function formatSavedMinutes(
  minutes: number | null | undefined,
  hasBaseline: boolean,
): string {
  if (!hasBaseline || minutes === null || minutes === undefined || !Number.isFinite(minutes)) {
    return '—（未登记口径）';
  }
  return `${formatDecimal(minutes)} 分钟`;
}

/**
 * 数字 → 人话（保留最多 2 位小数，去掉多余的 0）。
 *
 * <p>⚠️ 前端**不碰浮点记账**：这里只做**显示**用的格式化，不参与任何计算 ——
 * 账在库里是 {@code numeric(19,6)}，前端只把它显示出来。
 */
export function formatDecimal(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return Number(value.toFixed(2)).toString();
}

/**
 * 耗时（**整数毫秒**）→ 人话。
 *
 * <p>⚠️ 前端**不碰浮点**：毫秒是整数，这里只用整数除法。
 */
export function describeDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—';
  if (ms < 1000) return `${Math.trunc(ms)} 毫秒`;
  const seconds = Math.trunc(ms / 1000);
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.trunc(seconds / 60);
  const restSeconds = seconds % 60;
  if (minutes < 60) return restSeconds ? `${minutes} 分 ${restSeconds} 秒` : `${minutes} 分`;
  const hours = Math.trunc(minutes / 60);
  const restMinutes = minutes % 60;
  return restMinutes ? `${hours} 小时 ${restMinutes} 分` : `${hours} 小时`;
}

/**
 * 口径说明 → 人话（**随数字一起显示**，只给数字不给口径，那个数字不可用于决策）。
 */
export function describeBasis(basis: AutomationRuleEffectSavingBasis | null | undefined): string {
  if (!basis) return '';
  return `${basis.formula}（口径来源：${basis.sourceTable}.${basis.sourceField}；不计入：${basis.excluded}）`;
}

/**
 * 「这一行到底意味着什么」—— 一句人话，写在列表行的副标题上。
 *
 * <p>🔴 尤其要把 {@code NOT_ATTEMPTED} 说清楚：**没执行** ≠ **失败** ≠ **成功**。
 */
export function describeOutcome(run: { outcome: string; dryRun: boolean }): string {
  if (run.outcome === 'NOT_ATTEMPTED') return '未执行 · 执行器未接通（没有真的跑）';
  if (run.dryRun) return '试运行 · 不产生真实副作用';
  if (run.outcome === 'FAILED') return '执行失败';
  if (run.outcome === 'SKIPPED') return '已跳过';
  if (run.outcome === 'TIMEOUT') return '执行超时';
  return '执行成功';
}

/**
 * 「建议关闭」的理由 —— 一句人话，写清楚**关闭动作归谁**。
 *
 * <p>🔴 本模块**不做关闭动作**（那归 18.3.1.1 规则档案）⇒ 这句话必须带上这句归属，
 * 否则读的人会以为这里能直接关掉规则。
 */
export function describeAdviseReason(rule: {
  adviseDisable: boolean;
  falseTriggerRuns: number;
  falseTriggerRate: number | null;
}): string {
  if (!rule.adviseDisable) return '';
  const rate = formatRate(rule.falseTriggerRate);
  return (
    `有效执行里有 ${rule.falseTriggerRuns} 次被登记为误触发（误触发率 ${rate}）` +
    ' —— 建议到规则档案里停用或收紧条件' +
    '（关闭动作归 18.3.1.1，本模块不做）'
  );
}

/** 建议的严重度 → 中文标签（后端也给了 label，这里做兜底）。 */
export function severityLabel(severity: string, fallback?: string): string {
  switch (severity) {
    case 'CRITICAL':
      return '紧急';
    case 'WARNING':
      return '警告';
    case 'INFO':
      return '提示';
    default:
      return fallback || severity || '未知';
  }
}

/** 建议来源 → 中文标签。 */
export function adviceSourceLabel(source: string, fallback?: string): string {
  switch (source) {
    case 'SYSTEM':
      return '系统生成';
    case 'MANUAL':
      return '人工登记';
    default:
      return fallback || source || '未知';
  }
}

/**
 * 规则档案的定位引用（**快速定位**用的）。
 *
 * <p>🔴 这是**跳转引用**，不是「关闭」动作 —— 页面拿它把人带到规则档案那一页。
 */
export function ruleArchiveRef(ruleId: string): string {
  return `/automations/rules?ruleId=${encodeURIComponent(ruleId)}`;
}
