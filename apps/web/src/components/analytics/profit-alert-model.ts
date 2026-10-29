/**
 * 13.4.3.1 利润预警 前端模型（卷 13「利润与经营分析」L3 13.4.3 利润预警下的第一格）。
 *
 * <p>蓝图原文：「利润异常预警：SKU 毛利转负、店铺利润率骤降、费用占比异常、广告亏损超标、
 * 订单级亏损订单占比突增。」输入「利润指标投影；阈值规则配置。」输出「预警清单 + 通知 + 处置入口。」
 * 关系：上游 = 13.4.1.1；下游 = 1 工作台、5 调价、14 广告。
 *
 * <p><b>🔴 这个页面上最容易做丢的四件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「判不出来」≠「没异常」</b>。服务端把判不了的条目单独列在 {@code undecided} 里并写明原因，
 *       这里必须**原样带出来**，绝不能把它们渲染成「正常」—— 判不了被当成「没异常」比误报更危险：
 *       误报会被处理，「没异常」会被忽略。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端给 {@code null} 的观测值、基线、差额一律渲染成「—」，
 *       **绝不显示成 0.00** —— 假 0 会被读成「完全没花钱 / 完全没问题」。</li>
 *   <li><b>阈值有单位，且单位不是前端猜的</b>。「骤降 10」是 10 个**百分点**，
 *       「占比 80」是 80**%**，「倍数 1」是 1**倍** —— 三种含义完全不同，
 *       单位由服务端按 (预警类型 × 阈值口径) 派生后下发。</li>
 *   <li><b>前端不做业务算术</b>。观测值、基线、差额、占比全部由服务端（BigDecimal 定标）算好，
 *       这里只做**展示格式化**。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  ProfitAlert,
  ProfitAlertCapabilities,
  ProfitAlertNotification,
  ProfitAlertNotificationPage,
  ProfitAlertPage,
  ProfitAlertRule,
  ProfitAlertUndecided,
} from '../../../../../packages/api-contracts/src/index';

export type {
  ProfitAlert,
  ProfitAlertCapabilities,
  ProfitAlertNotification,
  ProfitAlertNotificationPage,
  ProfitAlertPage,
  ProfitAlertRule,
  ProfitAlertUndecided,
};

// ================================================================ 展示格式化

/** 百分比刻度（服务端已经算成百分数，例如 -15.0000 = -15%）。 */
const PERCENT_DECIMALS = 2;

/** 倍数刻度（服务端已经算成倍数，例如 1.66666667）。 */
const MULTIPLE_DECIMALS = 2;

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 {@code null} ⇒ 「—」，**绝不用 0 顶替**。
 */
export function amountText(value: number | null, currency: string): string {
  if (value === null) return '—';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency}`;
}

/**
 * 毛利。
 *
 * <p>🔴 {@code null} ⇒ 「—（不可比）」：毛利算不出来时**整格没有值**，
 * 而不是「毛利是 0」。
 */
export function grossProfitText(value: number | null, currency: string): string {
  if (value === null) return '—（不可比）';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency}`;
}

/**
 * 单位的显示名。
 *
 * <p>🔴 **单位由服务端下发**（{@code observedUnit} / {@code thresholdUnit}），
 * 前端**不按预警类型猜** —— 猜错会让「10 个百分点」显示成「10%」，阈值含义整个变掉。
 */
export function unitLabel(unit: string | null | undefined): string {
  switch (unit) {
    case 'PERCENT':
      return '%';
    case 'PERCENTAGE_POINTS':
      return ' 个百分点';
    case 'MULTIPLE':
      return ' 倍';
    default:
      return '';
  }
}

/**
 * 观测值文本。
 *
 * <p>🔴 {@code null} ⇒ 「—（算不出来）」：**不是 0**。
 */
export function observedText(alert: ProfitAlert): string {
  if (alert.observedValue === null) return '—（算不出来）';
  const decimals = alert.observedUnit === 'MULTIPLE' ? MULTIPLE_DECIMALS : PERCENT_DECIMALS;
  return `${formatNumber(alert.observedValue, decimals)}${unitLabel(alert.observedUnit)}`;
}

/**
 * 阈值文本（**带上单位**）。
 *
 * <p>⚠️ 方向由预警类型决定（跌破线用「低于」，超标用「达到」），
 * 但这里**只显示阈值本身**与单位，判定方向交给服务端给的 {@code reason} 说明 ——
 * 前端不重述判定逻辑（两处各说一套就会对不上）。
 */
export function thresholdText(alert: ProfitAlert): string {
  const decimals = alert.thresholdUnit === 'MULTIPLE' ? MULTIPLE_DECIMALS : PERCENT_DECIMALS;
  return `${formatNumber(alert.thresholdValue, decimals)}${unitLabel(alert.thresholdUnit)}`;
}

/**
 * 基线文本。
 *
 * <p>🔴 {@code null} ⇒ 「—（基线算不出来）」。
 */
export function baselineText(alert: ProfitAlert): string {
  if (alert.baselineValue === null) return '—（基线算不出来）';
  const decimals = alert.observedUnit === 'MULTIPLE' ? MULTIPLE_DECIMALS : PERCENT_DECIMALS;
  return `${formatNumber(alert.baselineValue, decimals)}${unitLabel(alert.observedUnit)}`;
}

/**
 * 与基线的差额。
 *
 * <p>🔴 {@code null} ⇒ 「—」（任一侧不可比时**不做差**，而不是差出 0）。
 */
export function deltaText(alert: ProfitAlert): string {
  if (alert.delta === null) return '—';
  const decimals = alert.observedUnit === 'MULTIPLE' ? MULTIPLE_DECIMALS : PERCENT_DECIMALS;
  return `${formatNumber(alert.delta, decimals)}${unitLabel(alert.observedUnit)}`;
}

/** 基线口径的说明（服务端给的两个取值之一）。 */
export function baselineModeLabel(mode: string | null | undefined): string {
  switch (mode) {
    case 'BASELINE_MEAN':
      return '前 N 期同口径窗口均值';
    case 'PREVIOUS_WINDOW':
      return '上一窗口（仅作参考，不参与判定）';
    default:
      return '—';
  }
}

/** 阈值口径的说明。 */
export function thresholdModeLabel(mode: string | null | undefined): string {
  switch (mode) {
    case 'FIXED':
      return '固定阈值线';
    case 'BASELINE':
      return '相对历史基线';
    default:
      return '—';
  }
}

// ================================================================ 语义色（Badge tone）

/**
 * Badge 的 tone 取值（与 `@starcon/ui` 的 `Badge` 一致）。
 *
 * <p>本文件是纯函数层，不 import UI 包 —— 但 tone 的**取值集合**必须与 UI 一致，
 * 所以在这里显式列出来（多一个值就会在编译期报出来，而不是运行时悄悄变灰）。
 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/**
 * 严重度的语义色。
 *
 * <p>⚠️ **tone 跟语义挂钩，不跟颜色挂钩**：严重 ⇒ danger，警告 ⇒ warning，提示 ⇒ info。
 */
export function severityTone(severity: string): Tone {
  switch (severity) {
    case 'CRITICAL':
      return 'danger';
    case 'WARNING':
      return 'warning';
    case 'INFO':
      return 'info';
    default:
      return 'neutral';
  }
}

/**
 * 严重度的中文名。
 *
 * <p>🔴 **服务端给了中文名就用服务端的**（那是权威口径）；没给才回落到本地词表。
 */
export function severityLabel(severity: string, serverLabel: string): string {
  if (serverLabel) return serverLabel;
  switch (severity) {
    case 'CRITICAL':
      return '严重';
    case 'WARNING':
      return '警告';
    case 'INFO':
      return '提示';
    default:
      return severity;
  }
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「**数据延迟要显示**」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: ProfitAlertPage | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何利润事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: ProfitAlertPage | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 维度与选项

/** 预警类型下拉（**空串 = 全部**）。 */
export const ALERT_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'SKU_NEGATIVE_GROSS_PROFIT', label: 'SKU 毛利转负' },
  { value: 'SHOP_MARGIN_DROP', label: '店铺利润率骤降' },
  { value: 'FEE_RATIO_ANOMALY', label: '费用占比异常' },
  { value: 'AD_LOSS_EXCEEDED', label: '广告亏损超标' },
  { value: 'LOSS_ORDER_RATIO_SPIKE', label: '亏损订单占比突增' },
];

/** 严重度下拉（**空串 = 全部**）。 */
export const SEVERITY_OPTIONS: { value: string; label: string }[] = [
  { value: 'CRITICAL', label: '严重' },
  { value: 'WARNING', label: '警告' },
  { value: 'INFO', label: '提示' },
];

/** 归属维度下拉（**空串 = 全部**）。 */
export const SCOPE_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'SKU', label: 'SKU' },
  { value: 'SHOP', label: '店铺' },
];

/** 时间范围文本。 */
export function rangeText(from: string, to: string): string {
  return `${from} ~ ${to}`;
}

/** 默认窗口：最近 30 天（含今天）。 */
export function defaultRange(today: string): { from: string; to: string } {
  const end = new Date(`${today}T00:00:00Z`);
  const start = new Date(end.getTime() - 29 * 24 * 60 * 60 * 1000);
  return { from: start.toISOString().slice(0, 10), to: today };
}

// ================================================================ 页面状态

/** 页面的几种状态分开说。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态判定。
 *
 * <p>🔴 **「读不到」与「没有数据」是两件事**：
 * 读不到（服务不可达 / 无权限）⇒ 错误态；读到了但这个窗口一条预警都没有 ⇒ 空态。
 *
 * <p>⚠️ **空态不等于「一切正常」**：空态只说明「没有命中阈值」，
 * 「判不出来」的条目仍然要显示（它们在 {@code undecided} 里）。
 */
export function pageState(response: ProfitAlertPage | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.alerts.length === 0 && response.undecided.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案（**写清「没有命中」不等于「没有异常」**）。 */
export function emptyText(response: ProfitAlertPage | null): string {
  if (!response) return '';
  if (response.alerts.length === 0 && response.undecided.length === 0) {
    return '这个时间窗口里没有命中任何阈值，也没有判不出来的条目。'
      + '⚠️ 注意：**没有命中 ≠ 没有异常** —— 先确认利润事实已经投影过（看页面顶部的「数据截至」）。';
  }
  if (response.alerts.length === 0) {
    return '这个时间窗口里没有命中阈值，但有判不出来的条目 —— 见下方「判不出来」清单。';
  }
  return '';
}

/** 「判不出来」清单的标题说明。 */
export function undecidedTitle(count: number): string {
  return `判不出来（${count} 条）`;
}

/** 「判不出来」清单的说明（**这一段最容易被写成「无异常」**）。 */
export function undecidedNote(response: ProfitAlertPage | null): string {
  if (!response) return '';
  const count = response.summary.undecidedCount;
  if (count === 0) return '';
  return `${count} 条**判不出来**（不是「没异常」）：成本项缺失 / 历史期数不足 / 订单毛利算不出来 —— `
    + '每条都写明了原因。判不出来的东西不会被当成正常，也不会进通知。';
}

/** 一条「判不出来」记录的归属说明。 */
export function undecidedScopeText(item: ProfitAlertUndecided): string {
  return item.scopeLabel || '（全部）';
}

// ================================================================ 阈值规则

/** 阈值规则那一行的阈值说明（**带单位**）。 */
export function ruleThresholdText(rule: ProfitAlertRule): string {
  const decimals = rule.thresholdUnit === 'MULTIPLE' ? MULTIPLE_DECIMALS : PERCENT_DECIMALS;
  return `${formatNumber(rule.thresholdValue, decimals)}${unitLabel(rule.thresholdUnit)}`;
}

/** 规则的口径说明（服务端给的原文，不改写）。 */
export function ruleDescription(rule: ProfitAlertRule): string {
  return rule.description ?? '';
}

// ================================================================ 通知

/** 通知状态的中文名。 */
export function notificationStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待处理';
    case 'ACKNOWLEDGED':
      return '已知悉';
    case 'DISMISSED':
      return '已忽略';
    default:
      return status;
  }
}

/** 通知状态的语义色。 */
export function notificationStatusTone(status: string): Tone {
  switch (status) {
    case 'PENDING':
      return 'warning';
    case 'ACKNOWLEDGED':
      return 'success';
    case 'DISMISSED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 通知是不是还能处理（**终态不可复活**）。 */
export function isActionable(status: string): boolean {
  return status === 'PENDING';
}

/** 一条通知的观测值文本。 */
export function notificationObservedText(item: ProfitAlertNotification): string {
  if (item.observedValue === null) return '—';
  const decimals = item.observedUnit === 'MULTIPLE' ? MULTIPLE_DECIMALS : PERCENT_DECIMALS;
  return `${formatNumber(item.observedValue, decimals)}${unitLabel(item.observedUnit)}`;
}

// ================================================================ 能力声明

/** 能力声明里的「确实拿不到」清单标题。 */
export function notDeliveredTitle(capabilities: ProfitAlertCapabilities | null): string {
  if (!capabilities) return '';
  return `确实拿不到的部分（${capabilities.notDelivered.length} 条）`;
}
