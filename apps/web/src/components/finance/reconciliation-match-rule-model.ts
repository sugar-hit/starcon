import type {
  FinanceMatchRateMonitoring,
  FinanceMatchRateSnapshotInput,
  FinanceMatchRateSnapshotPage,
  FinanceMatchRateSnapshotRow,
  FinanceMatchRuleCapabilities,
  FinanceMatchRuleEventPage,
  FinanceMatchRuleEventRow,
  FinanceMatchRuleInput,
  FinanceMatchRuleOptions,
  FinanceMatchRulePage,
  FinanceMatchRuleRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.3.2.3 自动匹配规则的前端模型。
 *
 * <p>蓝图 12-finance.md：「配置对账自动匹配规则：匹配键（订单号 + 金额 + 日期 + 类型）、
 * 容差（汇率差、手续费）、优先级、模糊匹配兜底，配置匹配率目标与监控。」输出：「规则集 +
 * 匹配率统计 + 未匹配原因分布。」加粗句：「<b>匹配率是财务自动化的核心指标</b>，持续低于
 * 目标时应排查上游数据质量而非调规则。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>🔴 匹配键里没有「金额」。</b>页面上的匹配键只有「订单号 / 日期 / 类型」三档，
 *       至少勾两个；「至少对上的维度数」下限是 2。
 *       ⇒ 「只按金额」「金额 + 单个维度」在界面上**也**写不出来 ——
 *       光靠 DB CHECK 拦住不够，用户在页面上也不该看到那种选项。</li>
 *   <li><b>三种容差分开说。</b>金额容差 = 候选配对带；手续费容差 / 汇率差容差 =
 *       残差**归因**带。页面不把它们混成一个「容差」输入框。</li>
 *   <li><b>模糊兜底要说清「放松的是怎么比，不是要比几个」。</b></li>
 *   <li><b>匹配率按币种分组</b>，绝不把不同币种的行摆进同一列相加；
 *       分母为 0 时显示「—」而**不是 0**（0 会被读成「一条都没配上」）。
 *       判不了达标与否时显示「判不了」而**不是「未达标」**。</li>
 *   <li><b>低于目标时的处置口径写在页面上</b>：先排查上游数据质量，不是先调规则。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「还没有规则」与「筛选无结果」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/reconciliation-match-rules}，
 * 与 12.3.2.1 的 {@code finance/reconciliation-tasks}、
 * 12.7.1.2 的 {@code finance/bank-reconciliation} 各自独立前缀。
 */

const base = 'finance/reconciliation-match-rules';

/** 列表筛选条件（可选属性显式带 `| undefined`：仓库开了 `exactOptionalPropertyTypes`）。 */
export interface MatchRuleParams {
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchMatchRuleCapabilities() {
  return api<FinanceMatchRuleCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchMatchRuleOptions() {
  return api<FinanceMatchRuleOptions>(`${base}/options`);
}

/** 规则列表（真分页 + 真筛选 + 真搜索）。 */
export function fetchMatchRules(params: MatchRuleParams = {}) {
  return api<FinanceMatchRulePage>(`${base}${query({ ...params })}`);
}

/** 规则详情。 */
export function fetchMatchRule(id: string) {
  return api<FinanceMatchRuleRow>(`${base}/${encodeURIComponent(id)}`);
}

/** 建规则（匹配键 / 容差 / 优先级 / 模糊兜底 / 匹配率目标 / 适用范围）。 */
export function createMatchRule(input: FinanceMatchRuleInput) {
  return api<FinanceMatchRuleRow>(base, 'POST', input);
}

/** 改规则。 */
export function updateMatchRule(id: string, input: FinanceMatchRuleInput) {
  return api<FinanceMatchRuleRow>(`${base}/${encodeURIComponent(id)}`, 'PUT', input);
}

/** 启用规则（同一租户内激活态的优先级必须唯一）。 */
export function activateMatchRule(id: string) {
  return api<FinanceMatchRuleRow>(`${base}/${encodeURIComponent(id)}/activate`, 'POST');
}

/** 停用规则。 */
export function deactivateMatchRule(id: string) {
  return api<FinanceMatchRuleRow>(`${base}/${encodeURIComponent(id)}/deactivate`, 'POST');
}

/** 规则事件流（真分页；append-only 举证）。 */
export function fetchMatchRuleEvents(id: string, params: { page?: number; size?: number } = {}) {
  return api<FinanceMatchRuleEventPage>(
    `${base}/${encodeURIComponent(id)}/events${query({ ...params })}`,
  );
}

/** 匹配率监控视图（匹配率统计 + 未匹配原因分布）。 */
export function fetchMatchRateMonitoring(taskId?: string) {
  const params = taskId ? { taskId } : {};
  return api<FinanceMatchRateMonitoring>(`${base}/monitoring${query({ ...params })}`);
}

/** 算一次匹配率快照并落库。 */
export function createMatchRateSnapshot(
  id: string,
  input: FinanceMatchRateSnapshotInput = { taskId: null },
) {
  return api<Array<FinanceMatchRateSnapshotRow>>(
    `${base}/${encodeURIComponent(id)}/rate-snapshots`,
    'POST',
    input,
  );
}

/** 匹配率快照历史（真分页）。 */
export function fetchMatchRateSnapshots(
  id: string,
  params: { page?: number; size?: number } = {},
) {
  return api<FinanceMatchRateSnapshotPage>(
    `${base}/${encodeURIComponent(id)}/rate-snapshots${query({ ...params })}`,
  );
}

// ================================================================ 展示口径

/** 规则状态文案。 */
export function matchRuleStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '未启用';
    case 'ACTIVE':
      return '已启用';
    case 'DISABLED':
      return '已停用';
    default:
      return status;
  }
}

/** 规则状态色调。 */
export function matchRuleStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'DRAFT':
      return 'warning';
    case 'DISABLED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 匹配键的中文标签（🔴 **没有「金额」这一档**）。 */
export function matchRuleKeyLabel(key: string): string {
  switch (key) {
    case 'REFERENCE':
      return '订单号';
    case 'DATE':
      return '日期';
    case 'TYPE':
      return '类型';
    default:
      return key;
  }
}

/** 匹配键列表的文案。 */
export function matchRuleKeysLabel(keys: string[]): string {
  return keys.map(matchRuleKeyLabel).join(' + ');
}

/**
 * 匹配键的**候选档位**（页面下拉用）。
 *
 * <p>🔴 刻意**不包含**「金额」—— 卷 12 硬约束「金额相同只是候选，必须结合订单号、
 * 日期、类型」。用户在界面上也**看不到**「只按金额」这个选项。
 */
export const MATCH_RULE_KEY_OPTIONS: readonly { value: string; label: string }[] = [
  { value: 'REFERENCE', label: '订单号' },
  { value: 'DATE', label: '日期' },
  { value: 'TYPE', label: '类型' },
];

/** 至少可选两个匹配键。 */
export const MATCH_RULE_MIN_KEYS = 2;

/** 至少对上的维度数下限（金额 + 单个维度不构成匹配）。 */
export const MATCH_RULE_MIN_DIMENSION_COUNT = 2;

/** 匹配键是否合法（供前端在提交前给出即时反馈；后端与 DB 也会各自拦一道）。 */
export function matchRuleKeysValid(keys: string[]): boolean {
  if (keys.length < MATCH_RULE_MIN_KEYS) return false;
  return keys.every((key) => MATCH_RULE_KEY_OPTIONS.some((option) => option.value === key));
}

/** 至少对上的维度数是否合法。 */
export function matchRuleDimensionCountValid(count: number, keyCount: number): boolean {
  return count >= MATCH_RULE_MIN_DIMENSION_COUNT && count <= keyCount;
}

/** 未匹配原因的中文标签。 */
export function matchRuleReasonLabel(reason: string): string {
  switch (reason) {
    case 'EXTERNAL_ONLY':
      return '平台账单多出';
    case 'BOOK_ONLY':
      return '我方账多出';
    case 'AMOUNT_MISMATCH':
      return '金额对不齐';
    case 'CURRENCY_MISMATCH':
      return '币种不一致';
    case 'DIMENSION_MISMATCH':
      return '维度对不上';
    case 'FEE_DIFFERENCE':
      return '手续费差异';
    case 'EXCHANGE_RATE_DIFFERENCE':
      return '汇率差';
    default:
      return reason;
  }
}

/** 未匹配原因色调。 */
export function matchRuleReasonTone(
  reason: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (reason) {
    case 'AMOUNT_MISMATCH':
    case 'EXTERNAL_ONLY':
    case 'BOOK_ONLY':
      return 'warning';
    case 'DIMENSION_MISMATCH':
    case 'CURRENCY_MISMATCH':
    case 'FEE_DIFFERENCE':
    case 'EXCHANGE_RATE_DIFFERENCE':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 金额展示：原样显示服务端给的数（**页面不做任何前端算术**）。 */
export function formatMatchRuleMoney(
  amount: number | null | undefined,
  currency?: string | null,
): string {
  if (amount === null || amount === undefined) return '—';
  const text = amount.toFixed(4);
  return currency ? `${text} ${currency}` : text;
}

/**
 * 匹配率展示：**算不出来就说算不出来**。
 *
 * <p>⚠️ 分母为 0 时服务端给 {@code null} —— 页面显示「—（还没有可比的结果）」，
 * **不显示 0**（0 会被读成「一条都没配上」）。
 */
export function formatMatchRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—（还没有可比的结果）';
  return `${(rate * 100).toFixed(2)}%`;
}

/**
 * 达标结论：**「判不了」是一档独立状态**，不是「未达标」。
 *
 * <p>⚠️ 没有启用中的规则（没有目标）或分母为 0（算不出比率）⇒ 判不了。
 */
export function matchRateTargetVerdict(
  rate: number | null | undefined,
  target: number | null | undefined,
  targetMet: boolean | null | undefined,
): { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' } {
  if (target === null || target === undefined) {
    return { label: '判不了（没有启用中的规则 ⇒ 没有目标）', tone: 'neutral' };
  }
  if (rate === null || rate === undefined || targetMet === null || targetMet === undefined) {
    return { label: '判不了（还没有可比的结果）', tone: 'neutral' };
  }
  return targetMet
    ? { label: `达标（${(rate * 100).toFixed(2)}% ≥ ${target}%）`, tone: 'success' }
    : {
        label: `未达标（${(rate * 100).toFixed(2)}% < ${target}%）`,
        tone: 'danger',
      };
}

/** 规则的适用范围文案（可空 = 全部）。 */
export function matchRuleScopeLabel(row: {
  currency: string | null;
  accountId: string | null;
  shopId: string | null;
}): string {
  const parts: string[] = [];
  parts.push(row.currency ? `币种 ${row.currency}` : '全部币种');
  parts.push(row.accountId ? `账户 ${row.accountId}` : '全部账户');
  parts.push(row.shopId ? `店铺 ${row.shopId}` : '全部店铺');
  return parts.join(' · ');
}

/** 规则口径的一行说明（人话）。 */
export function matchRuleSpecLabel(row: {
  matchKeys: string[];
  minDimensionCount: number;
  dateWindowDays: number;
  amountTolerance: number;
  feeTolerance: number;
  exchangeRateTolerance: number;
  fuzzyEnabled: boolean;
  fuzzyReferencePrefixLength: number;
  fuzzyDateWindowDays: number;
}): string {
  const keys = `匹配键 ${matchRuleKeysLabel(row.matchKeys)}（至少对上 ${row.minDimensionCount} 个）· 日期窗口 ${row.dateWindowDays} 天`;
  const tolerance = `金额容差 ${row.amountTolerance.toFixed(4)} · 手续费容差 ${row.feeTolerance.toFixed(4)} · 汇率差容差 ${row.exchangeRateTolerance.toFixed(4)}`;
  const fuzzy = row.fuzzyEnabled
    ? ` · 模糊兜底（前缀 ${row.fuzzyReferencePrefixLength} 位 / 窗口 ${row.fuzzyDateWindowDays} 天，仍需对上 ${row.minDimensionCount} 个维度）`
    : ' · 不开模糊兜底';
  return `${keys} · ${tolerance}${fuzzy}`;
}

/**
 * 🔴 硬约束的一句话提醒（页面上固定显示，让用户知道为什么没有「金额」这一档）。
 */
export const MATCH_RULE_HARD_CONSTRAINT_HINT =
  '🔴 「金额相同即匹配」不是匹配：匹配键至少两个（订单号 / 日期 / 类型），' +
  '至少对上的维度数至少 2；金额只作为容差（金额 / 手续费 / 汇率差）出现在配平与残差归因上。';

/**
 * 低于目标时的处置口径（蓝图原话）。
 *
 * <p>⚠️ **不是**「调规则」—— 是「排查上游数据质量」。
 */
export const MATCH_RATE_BELOW_TARGET_HINT =
  '匹配率持续低于目标时，应先排查上游数据质量（12.3.1.1 平台账单 / 12.7.1.2 银行流水），' +
  '而不是先调规则 —— 先看「未匹配原因分布」，再决定是改规则还是修上游。';

/** 一条规则「能不能启用」的说明（页面据此禁用按钮并解释原因）。 */
export function matchRuleActivateBlockReason(row: {
  status: string;
  matchKeys: string[];
  minDimensionCount: number;
}): string {
  if (row.status === 'ACTIVE') {
    return '这条规则已启用：引擎按优先级依次试它。';
  }
  if (row.matchKeys.length < MATCH_RULE_MIN_KEYS) {
    return '匹配键至少两个（订单号 / 日期 / 类型）才能启用。';
  }
  if (row.minDimensionCount < MATCH_RULE_MIN_DIMENSION_COUNT) {
    return '至少对上的维度数至少 2 才能启用（金额 + 单个维度不构成匹配）。';
  }
  return '';
}

/** 规则事件类型文案。 */
export function matchRuleEventLabel(eventType: string): string {
  switch (eventType) {
    case 'RULE_CREATED':
      return '建立匹配规则';
    case 'RULE_UPDATED':
      return '改动匹配规则';
    case 'RULE_ACTIVATED':
      return '启用匹配规则';
    case 'RULE_DISABLED':
      return '停用匹配规则';
    case 'RATE_SNAPSHOT':
      return '记录匹配率快照';
    default:
      return eventType;
  }
}

/** 未匹配原因分布的一行结论（人话）。 */
export function matchRateReasonHint(reason: {
  currency: string;
  reasonType: string;
  lineCount: number;
  ratio: number;
}): string {
  return `${reason.currency} · ${matchRuleReasonLabel(reason.reasonType)} ${reason.lineCount} 条（占未匹配的 ${(reason.ratio * 100).toFixed(2)}%）`;
}
