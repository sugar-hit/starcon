/**
 * 13.1.2.2 达成追踪 前端模型（卷 13「利润与经营分析」L3 13.1.2 目标管理下的第二格）。
 *
 * <p>蓝图原文：「追踪目标达成：当前值、达成率、预测达成（按当前速度推算期末值）、未达标项高亮、
 * 人员/产品维度排名。」输入「13.1.2.1 目标；实际值投影；时间进度。」输出「达成率表 + 预测 + 预警。」
 * 关系：上游 = 13.1.2.1；下游 = 17 绩效核算、1 工作台预警。
 *
 * <p><b>🔴 这一页最容易做丢的四件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端给 {@code null} 时这里渲染成「不可得」，
 *       <b>绝不用 0% 顶替</b> —— <b>0% 达成率是有业务含义的断言</b>（确实一件都没做成），
 *       <b>「不可得」不是</b>（我根本拿不到这个维度的实际值）。</li>
 *   <li><b>「没达标」≠「还没法判定」</b>。未达标高亮只认服务端算出来的
 *       {@code underAchieved}（AT_RISK / BEHIND）—— 「周期还没开始」「目标值不是正数」
 *       「实际值不可得」都<b>不算未达标</b>，页面把它们单列，不混进预警里的落后名单。</li>
 *   <li><b>前端不做业务算术</b>：达成率、预测达成率、时间进度全部由服务端
 *       （{@code BigDecimal} 定标）算好，这里只做<b>展示格式化</b>。</li>
 *   <li><b>多币种绝不相加</b>：页面不提供「折算合计」入口，排名也不跨币种。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  AttainmentAlert,
  AttainmentBoard,
  AttainmentBucket,
  AttainmentCapabilities,
  AttainmentGroup,
  AttainmentRankEntry,
  AttainmentRanking,
  AttainmentRow,
  AttainmentSnapshot,
  AttainmentSnapshotPage,
  AttainmentSummary,
} from '../../../../../packages/api-contracts/src/index';

export type {
  AttainmentAlert,
  AttainmentBoard,
  AttainmentBucket,
  AttainmentCapabilities,
  AttainmentGroup,
  AttainmentRankEntry,
  AttainmentRanking,
  AttainmentRow,
  AttainmentSnapshot,
  AttainmentSnapshotPage,
  AttainmentSummary,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度。 */
const PERCENT_DECIMALS = 2;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 {@code null} ⇒ 「不可得」，**绝不用 0 顶替**。
 */
export function amountText(value: number | null, currency: string | null): string {
  if (value === null) return '不可得';
  const suffix = currency ? ` ${currency}` : '';
  return `${formatNumber(value, MONEY_DECIMALS)}${suffix}`;
}

/**
 * 达成率（百分比）。
 *
 * <p>🔴 {@code null} ⇒ 「不可得」（**不是 0%**）。
 */
export function rateText(value: number | null): string {
  if (value === null) return '不可得';
  return `${formatNumber(value * 100, PERCENT_DECIMALS)}%`;
}

/**
 * 时间进度（百分比）。
 *
 * <p>🔴 时间进度恒有值（服务端算得出），但 {@code null} 仍按「不可得」处理，不显示 0%。
 */
export function progressText(value: number | null): string {
  if (value === null) return '不可得';
  return `${formatNumber(value * 100, PERCENT_DECIMALS)}%`;
}

/** 排名（第几名 / 共几个）。🔴 {@code rank === 0} ⇒ 未参与排名（算不出达成率）。 */
export function rankText(rank: number, rankTotal: number): string {
  if (rank <= 0) return '未参与排名';
  return `第 ${rank} / ${rankTotal} 名`;
}

// ================================================================ 状态

export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/** 达成状态标签。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'ACHIEVED':
      return '已达标';
    case 'ON_TRACK':
      return '按当前速度能达标';
    case 'AT_RISK':
      return '差一点（预测 80% ~ 100%）';
    case 'BEHIND':
      return '明显跟不上（预测 < 80%）';
    case 'NOT_STARTED':
      return '周期尚未开始';
    case 'NOT_COMPARABLE':
      return '不可比（目标值不是正数）';
    case 'UNAVAILABLE':
      return '实际值不可得';
    default:
      return status;
  }
}

/** 达成状态色调。 */
export function statusTone(status: string): Tone {
  switch (status) {
    case 'ACHIEVED':
      return 'success';
    case 'ON_TRACK':
      return 'info';
    case 'AT_RISK':
      return 'warning';
    case 'BEHIND':
      return 'danger';
    case 'NOT_STARTED':
    case 'NOT_COMPARABLE':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/**
 * 实际值状态标签。
 *
 * <p>🔴 **「取不到」与「取到但是 0」是两件事** —— 这里刻意用不同的措辞。
 */
export function actualStatusLabel(actualStatus: string): string {
  switch (actualStatus) {
    case 'AVAILABLE':
      return '已取到实际值';
    case 'NO_DATA':
      return '还没有实际值事实';
    case 'NOT_AVAILABLE':
      return '该维度没有实际值来源';
    default:
      return actualStatus;
  }
}

/** 实际值状态色调。 */
export function actualStatusTone(actualStatus: string): Tone {
  switch (actualStatus) {
    case 'AVAILABLE':
      return 'success';
    case 'NO_DATA':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 归属维度标签。 */
export function scopeLabel(scopeType: string): string {
  switch (scopeType) {
    case 'DEPARTMENT':
      return '部门';
    case 'MEMBER':
      return '人员';
    case 'SHOP':
      return '店铺';
    case 'PRODUCT':
      return '产品';
    default:
      return scopeType;
  }
}

/** 预警级别标签。 */
export function alertLevelLabel(level: string): string {
  return level === 'CRITICAL' ? '严重' : '提醒';
}

/** 预警色调。 */
export function alertTone(level: string): Tone {
  return level === 'CRITICAL' ? 'danger' : 'warning';
}

/** 预警类别标签。 */
export function alertKindLabel(kind: string): string {
  switch (kind) {
    case 'FORECAST_BELOW_TARGET':
      return '按当前速度达不到';
    case 'NO_ACTUALS':
      return '算不出达成率';
    case 'TARGET_VALUE_INVALID':
      return '目标值不可比';
    default:
      return kind;
  }
}

// ================================================================ 概览

/**
 * 概览计数（**「算不出来」的那几类单独说，不并进未达标**）。
 */
export function summaryText(summary: AttainmentSummary | null): string {
  if (!summary) return '—';
  return [
    `目标 ${summary.total}`,
    `已达标 ${summary.achieved}`,
    `按当前速度能达标 ${summary.onTrack}`,
    `差一点 ${summary.atRisk}`,
    `明显跟不上 ${summary.behind}`,
    `周期尚未开始 ${summary.notStarted}`,
    `不可比 ${summary.notComparable}`,
    `实际值不可得 ${summary.unavailable}`,
    `未达标合计 ${summary.underAchieved}`,
  ].join(' · ');
}

/**
 * 未达标项（**只认服务端判定的 {@code underAchieved}**）。
 *
 * <p>🔴 「周期尚未开始」「不可比」「实际值不可得」**都不是**未达标 ——
 * 它们是「还没法判定」，混进未达标名单等于告诉管理者「你落后了」。
 */
export function underAchievedRows(rows: AttainmentRow[]): AttainmentRow[] {
  return rows.filter((row) => row.underAchieved);
}

/** 算不出达成率的行（**单独一类，页面单列**）。 */
export function unavailableRows(rows: AttainmentRow[]): AttainmentRow[] {
  return rows.filter((row) => row.attainmentRate === null);
}

/** 一个分组里的行。 */
export function groupRows(group: AttainmentGroup): AttainmentRow[] {
  return group.rows;
}

/** 看板里的全部行（**摊平分组**）。 */
export function allRows(board: AttainmentBoard | null): AttainmentRow[] {
  if (!board) return [];
  return board.groups.flatMap((group) => group.rows);
}

// ================================================================ 分解计划

/** 分解项区间文本。 */
export function bucketPeriodText(bucket: AttainmentBucket): string {
  return `${bucket.bucketStart} ~ ${bucket.bucketEnd}`;
}

/** 分解项的达成情况（一句话）。 */
export function bucketAttainmentText(bucket: AttainmentBucket): string {
  return `目标 ${amountText(bucket.targetValue, bucket.currency)} · 当前 ${amountText(
    bucket.currentValue,
    bucket.currency,
  )} · 达成率 ${rateText(bucket.attainmentRate)} · 预测 ${rateText(bucket.forecastRate)}`;
}

// ================================================================ 排名

/** 排名条目的一行文本。 */
export function rankEntryText(entry: AttainmentRankEntry): string {
  return `${entry.ownerName} · ${statusLabel(entry.status)} · 达成率 ${rateText(
    entry.attainmentRate,
  )} · 预测 ${rateText(entry.forecastRate)}`;
}

// ================================================================ 能力声明

/** **确实拿不到**的部分。 */
export function notDeliveredLines(capabilities: AttainmentCapabilities | null): string[] {
  if (!capabilities) return [];
  return capabilities.notDelivered;
}

/** 判定规则（阈值写清楚，页面上照着显示）。 */
export function capabilityRules(capabilities: AttainmentCapabilities | null): string[] {
  if (!capabilities) return [];
  return capabilities.rules.map((rule) => `${rule.ruleCode}：${rule.description}`);
}

/** 本服务真读过的上游模块。 */
export function upstreamModules(capabilities: AttainmentCapabilities | null): string[] {
  if (!capabilities) return [];
  return capabilities.upstreamModules;
}

// ================================================================ 页面状态

export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/** 三态判定（**加载态 / 错误态 / 空态**）。 */
export function pageState(board: AttainmentBoard | null, error: string, loading: boolean): PageState {
  if (loading) return 'loading';
  if (error) return 'error';
  if (!board || board.total === 0) return 'empty';
  return 'ready';
}

/** 空态文案（**「没有目标」与「目标算不出达成率」分开说**）。 */
export function emptyText(board: AttainmentBoard | null): string {
  if (!board) return '还没有读取到达成追踪数据。';
  if (board.total === 0) {
    return '当前筛选条件下**没有目标** —— 先到 13.1.2.1「目标设定」设一个目标，本模块才有东西可追踪。';
  }
  return '有目标，但这一页还没有数据。';
}

/** 评估日与时区说明（**时区必须显式给出**）。 */
export function asOfText(board: AttainmentBoard | null): string {
  if (!board) return '—';
  const source =
    board.timezoneSource === 'PROJECTION' ? '取自本租户投影事实' : '本租户还没有投影事实，暂按 UTC';
  return `评估日 ${board.asOfDate}（时区 ${board.timezone} —— ${source}）`;
}

/** 数据更新说明（**读不到与没有数据分开**）。 */
export function evaluatedAtText(board: AttainmentBoard | null): string {
  if (!board) return '—';
  return `评估时刻 ${board.evaluatedAt}`;
}
