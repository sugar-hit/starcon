import type {
  CrmLifecycleAlertDetail,
  CrmLifecycleAlertPage,
  CrmLifecycleAlertRow,
  CrmLifecycleCapabilities,
  CrmLifecycleComputeResult,
  CrmLifecyclePage,
  CrmLifecyclePolicyPage,
  CrmLifecyclePolicyRow,
  CrmLifecycleRiskFactor,
  CrmLifecycleRunPage,
  CrmLifecycleStats,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.6.2.1 生命周期与流失预警前端模型。
 *
 * <p>蓝图：「客户生命周期分层（RFM/新客-活跃-沉睡-流失-召回）与流失预警：按复购间隔识别
 * 本应复购未复购的客户，给出召回建议与流失风险分；支持品牌化客户的核心留存看板。
 * 输入：订单历史（9）；复购周期基线；RFM 计算配置。输出：生命周期分层结果；流失预警清单；
 * 召回建议。关系：上游 = 9 订单、15.3.1.1；下游 = 15.5.2 触达、15.6.2.2 挽回、13 分析。
 * **复购周期因品类差异极大**（快消周级、家具月级），预警阈值需可按类目配置，不能一刀切。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>阈值来源必须摆在脸上</b>：命中类目策略 / 命中全局策略 / 用的是租户真实订单历史
 *       的中位复购间隔 / 不可判定 —— 四种里没有一种是「代码里写死的 30 天」。
 *       页面**不自己**算复购周期，只显示后端给的 {@code thresholdSource}。</li>
 *   <li><b>「不可判定」是一等状态</b>：阈值不可判定、客户没有自己的复购间隔基线，
 *       都要有**自己的说法**，不能画成「正常」。</li>
 *   <li><b>风险分必须能拆开看</b>：四个因子各带权重、原始值、得分与说明，
 *       页面把它们逐条列出来，而不是只给一个「56 分」。</li>
 *   <li><b>召回只是建议</b>：建议归属写明「15.5.2 触达 / 15.6.2.2 弃单挽回」，
 *       页面**不提供**任何「立即发送」的入口（触达归 15.5.2，本模块没那个能力）。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有算过」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/customer-lifecycle}，与同服务的 {@code crm/customers}
 * （15.3.1.1）、{@code crm/cart-recovery}（15.6.2.2）**刻意不共用**。
 */

const base = 'crm/customer-lifecycle';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface LifecycleSegmentParams {
  segment?: string | undefined;
  sourceShop?: string | undefined;
  categoryCode?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface LifecycleAlertParams {
  status?: string | undefined;
  riskLevel?: string | undefined;
  categoryCode?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface LifecyclePolicyParams {
  scope?: string | undefined;
  categoryCode?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 生命周期分层的可读名（后端也给了 label，这份是**兜底**）。 */
export function lifecycleSegmentLabel(code: string | undefined): string {
  switch (code) {
    case 'NEW':
      return '新客';
    case 'ACTIVE':
      return '活跃';
    case 'DORMANT':
      return '沉睡';
    case 'RECALL':
      return '待召回';
    case 'CHURNED':
      return '已流失';
    default:
      return code ?? '';
  }
}

/**
 * 阈值来源的可读名。
 *
 * <p>🔴 `ORDER_HISTORY_BASELINE` **不能**画成「按类目生效」——
 * 那是本模块要防的头号事故：运营以为阈值是按类目配的，实际用的是租户整体中位数。
 */
export function lifecycleThresholdSourceLabel(code: string | undefined): string {
  switch (code) {
    case 'CATEGORY_POLICY':
      return '命中类目策略';
    case 'GLOBAL_POLICY':
      return '命中全局策略';
    case 'ORDER_HISTORY_BASELINE':
      return '无类目 / 全局策略，用租户真实订单历史的中位复购间隔';
    case 'NONE':
      return '不可判定（既无策略也无订单历史基线）';
    default:
      return code ?? '';
  }
}

/** 流失风险等级的可读名。 */
export function lifecycleRiskLevelLabel(code: string | undefined): string {
  switch (code) {
    case 'LOW':
      return '低';
    case 'MEDIUM':
      return '中';
    case 'HIGH':
      return '高';
    case 'CRITICAL':
      return '极高';
    default:
      return code ?? '';
  }
}

/** 预警处理状态的可读名。 */
export function lifecycleAlertStatusLabel(code: string | undefined): string {
  switch (code) {
    case 'OPEN':
      return '待处理';
    case 'ACKNOWLEDGED':
      return '已知悉';
    case 'RESOLVED':
      return '已处理';
    default:
      return code ?? '';
  }
}

/** 策略作用域的可读名。 */
export function lifecyclePolicyScopeLabel(code: string | undefined): string {
  switch (code) {
    case 'CATEGORY':
      return '按类目';
    case 'GLOBAL':
      return '全局兜底';
    default:
      return code ?? '';
  }
}

/**
 * 建议归属模块的可读名。
 *
 * <p>🔴 页面**必须**把归属写出来：召回建议不是本模块执行的，
 * 触达归 15.5.2、独立站弃单挽回归 15.6.2.2。
 */
export function lifecycleSuggestionOwnerLabel(code: string | undefined): string {
  switch (code) {
    case '15.5.2':
      return '15.5.2 触达';
    case '15.6.2.2':
      return '15.6.2.2 弃单挽回';
    default:
      return code ?? '';
  }
}

/**
 * 金额的显示文本（**一行一个币种**，不做任何前端算术）。
 *
 * <p>「USD 1.00」比裸数字更诚实 —— 金额是带币种的，
 * 页面**不**把它换算成别的币种（换算口径不在本模块）。
 */
export function lifecycleMonetaryText(
  amount: string | number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined || amount === '') return '—';
  if (!currency) return String(amount);
  return `${currency} ${amount}`;
}

/** RFM 分档的一行文本（R / F / M 各 0..5，0 = 不可判定）。 */
export function lifecycleRfmText(row: { rScore: number; fScore: number; mScore: number }): string {
  const mark = (score: number) => (score === 0 ? '不可判定' : String(score));
  return `R ${mark(row.rScore)} / F ${mark(row.fScore)} / M ${mark(row.mScore)}`;
}

/**
 * 阈值的显示文本。
 *
 * <p>🔴 阈值不可判定时显示「不可判定」而不是「—」：
 * 「—」会被读成「有阈值但没显示出来」，而「不可判定」说的是「**判不了**」。
 */
export function lifecycleThresholdText(row: {
  expectedRepurchaseDays: number | null | undefined;
  toleranceDays: number | null | undefined;
  thresholdSource: string | undefined;
}): string {
  if (row.expectedRepurchaseDays === null || row.expectedRepurchaseDays === undefined) {
    return '不可判定';
  }
  const tolerance = row.toleranceDays ?? 0;
  return `预期复购 ${row.expectedRepurchaseDays} 天 + 容差 ${tolerance} 天（${lifecycleThresholdSourceLabel(
    row.thresholdSource,
  )}）`;
}

/**
 * 客户本人历史复购间隔基线的显示文本。
 *
 * <p>🔴 样本不足（只下过一单）是**一件独立的事**，不能画成「基线 0 天」——
 * 「0 天」会被读成「他每天都买」。
 */
export function lifecycleBaselineText(row: {
  customerBaselineDays: number | null | undefined;
  customerBaselineSamples: number | undefined | null;
  baselineSource: string | undefined;
}): string {
  if (row.customerBaselineDays === null || row.customerBaselineDays === undefined) {
    return '不可判定（该客户没有足够的复购样本）';
  }
  return `本人历史复购间隔中位数 ${row.customerBaselineDays} 天（${row.customerBaselineSamples ?? 0} 段间隔）`;
}

/** 风险分的显示文本（分数 + 等级）。 */
export function lifecycleRiskText(alert: {
  riskScore: string | number | null | undefined;
  riskLevel: string | undefined;
}): string {
  if (alert.riskScore === null || alert.riskScore === undefined || alert.riskScore === '') {
    return '不可判定';
  }
  return `${alert.riskScore}（${lifecycleRiskLevelLabel(alert.riskLevel)}）`;
}

/**
 * 一个风险因子的显示文本。
 *
 * <p>🔴 风险分**必须**能拆开看 —— 只给一个「56 分」没法复核，
 * 也没法排召回优先级。
 */
export function lifecycleRiskFactorText(factor: CrmLifecycleRiskFactor): string {
  return `${factor.label}：${factor.points} / ${factor.weight} 分（${factor.rawValue}）—— ${factor.explanation}`;
}

/**
 * 空态文案：**区分**「筛选无结果」与「还没有算过」。
 *
 * <p>这两种空态要的行动完全不同：前者是「把筛选条件放宽」，
 * 后者是「先从上游重算一次」。
 */
export function lifecycleEmptyStateText(
  filtered: boolean,
  hasRuns: boolean,
): { title: string; description: string } {
  if (filtered) {
    return {
      title: '没有命中任何客户',
      description: '当前筛选条件下没有客户。放宽筛选条件再试一次 —— 这不代表这个租户没有客户。',
    };
  }
  if (!hasRuns) {
    return {
      title: '还没有算过生命周期分层',
      description:
        '分层结果来自上游 9 订单的真实订单事实（经授权 HTTP API 只读）。点「重算分层与预警」从上游拉一次。',
    };
  }
  return {
    title: '还没有算过生命周期分层',
    description: '上一次重算没有产出分层结果（上游窗口内没有订单，或那次重算失败了）。',
  };
}

/** 预警清单的空态文案。 */
export function lifecycleAlertEmptyStateText(
  filtered: boolean,
  hasRuns: boolean,
): { title: string; description: string } {
  if (filtered) {
    return {
      title: '没有命中任何预警',
      description: '当前筛选条件下没有预警记录。放宽筛选条件再试一次。',
    };
  }
  if (!hasRuns) {
    return {
      title: '还没有预警清单',
      description: '预警来自「本应复购未复购」的判定。先点「重算分层与预警」从上游拉一次订单事实。',
    };
  }
  return {
    title: '当前没有需要召回的客户',
    description: '上一次重算之后，没有客户超过「预期复购间隔 + 容差」。',
  };
}

/** 分层统计的一句话说明（**不受分页影响**，后端单独一条查询算出来的）。 */
export function lifecycleStatsNote(stats: CrmLifecycleStats | undefined): string {
  if (!stats) return '';
  return `客户 ${stats.totalCustomers} 位 · 超期 ${stats.overdueCustomers} 位 · 预警 ${stats.totalAlerts} 条。${stats.note ?? ''}`;
}

/** 阈值策略的一句话说明。 */
export function lifecyclePolicyNote(policy: CrmLifecyclePolicyRow): string {
  const scope = lifecyclePolicyScopeLabel(policy.scope);
  const category = policy.categoryCode ? `（类目 ${policy.categoryCode}）` : '';
  return `${scope}${category}：预期复购 ${policy.expectedRepurchaseDays} 天 + 容差 ${policy.toleranceDays} 天`;
}

// ================================================================ 请求

export function fetchLifecycleCapabilities() {
  return api<CrmLifecycleCapabilities>(`${base}/capabilities`);
}

export function fetchLifecycleSegments(params: LifecycleSegmentParams = {}) {
  return api<CrmLifecyclePage>(`${base}/segments${query({ ...params })}`);
}

export function fetchLifecycleAlerts(params: LifecycleAlertParams = {}) {
  return api<CrmLifecycleAlertPage>(`${base}/alerts${query({ ...params })}`);
}

export function fetchLifecycleAlertDetail(alertId: string) {
  return api<CrmLifecycleAlertDetail>(`${base}/alerts/${alertId}`);
}

export function acknowledgeLifecycleAlert(alertId: string, note?: string) {
  return api<CrmLifecycleAlertRow>(`${base}/alerts/${alertId}/acknowledge`, 'POST', {
    note: note ?? '',
  });
}

export function resolveLifecycleAlert(alertId: string, note?: string) {
  return api<CrmLifecycleAlertRow>(`${base}/alerts/${alertId}/resolve`, 'POST', {
    note: note ?? '',
  });
}

export function fetchLifecyclePolicies(params: LifecyclePolicyParams = {}) {
  return api<CrmLifecyclePolicyPage>(`${base}/policies${query({ ...params })}`);
}

export function createLifecyclePolicy(input: {
  scope: string;
  categoryCode?: string | undefined;
  expectedRepurchaseDays: number;
  toleranceDays?: number | undefined;
  rfmWindowDays?: number | undefined;
  recencyScoreDays?: number | undefined;
  frequencyMinOrders?: number | undefined;
  effectiveFrom: string;
  effectiveTo?: string | undefined;
  note?: string | undefined;
}) {
  return api<CrmLifecyclePolicyRow>(`${base}/policies`, 'POST', input);
}

export function updateLifecyclePolicy(
  policyId: string,
  input: {
    scope: string;
    categoryCode?: string | undefined;
    expectedRepurchaseDays: number;
    toleranceDays?: number | undefined;
    effectiveFrom: string;
    effectiveTo?: string | undefined;
    enabled?: boolean | undefined;
    note?: string | undefined;
  },
) {
  return api<CrmLifecyclePolicyRow>(`${base}/policies/${policyId}`, 'PUT', input);
}

export function computeLifecycle(input: { windowFrom?: string; windowTo?: string } = {}) {
  return api<CrmLifecycleComputeResult>(`${base}/compute`, 'POST', input);
}

export function fetchLifecycleRuns() {
  return api<CrmLifecycleRunPage>(`${base}/runs?size=20`);
}

/** 拼查询串（**空值一律不拼** —— 拼一个空的 segment= 会把筛选悄悄变成「分层为空」）。 */
function query(params: Record<string, unknown>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}
