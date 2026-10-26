import type {
  ServiceTaskStatusAlert,
  ServiceTaskStatusAlertList,
  ServiceTaskStatusCapabilities,
  ServiceTaskStatusFailureClassCount,
  ServiceTaskStatusFailureClassDoc,
  ServiceTaskStatusFailureReasonCount,
  ServiceTaskStatusOptions,
  ServiceTaskStatusReason,
  ServiceTaskStatusRuleDoc,
  ServiceTaskStatusSourceDoc,
  ServiceTaskStatusSourceStat,
  ServiceTaskStatusSummary,
  ServiceTaskStatusTaskCard,
  ServiceTaskStatusTaskPage,
  ServiceTaskStatusThresholds,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 1.5.2.1 服务与任务状态 —— 前端模型。
 *
 * 蓝图（`docs/product/capability-blueprint/01-dashboard-and-portal.md` 第 229 行起）：
 * 「展示平台自身运行状态：后台任务 / 同步任务最近运行与耗时、失败任务、计划任务是否停滞，
 * 便于运维与用户自助判断"是系统问题还是数据问题"。输出：任务状态列表；停滞 / 失败任务提示。」
 *
 * 🔴 本页是**只读工作台**（卷 01 的边界写死「工作台只读 + 跳转；一切状态变更回到
 * 归属系统完成」）。所以前端**只渲染服务端算好的结论**，不重算严重度、不重算停滞、
 * 也不重算失败分类 —— 那些判定全在服务端，前端改了就是两套真相。
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「停滞」与「失败」分开**。两个独立列表、两种徽标、两种措辞 ——
 *    「没在跑」和「跑挂了」不是一回事，混在一起运维就分不清该看调度器还是看任务日志。
 * 2. **「系统故障」与「业务数据问题」分开**。`failureClass` 决定徽标与措辞：
 *    BUSINESS 说「系统没坏，是数据 / 配置不对」，SYSTEM 说「毛病在平台侧或网络侧」。
 * 3. **「判不了」是一等公民**。UNCLASSIFIED / UNDETERMINED / STALL_UNDETERMINED
 *    都**不能**渲染成绿色 —— 判不了不等于没卡住。
 * 4. **「读不到」与「没有」分开**。后端 503 / 403 时页面显式显示「读不到」并给重试，
 *    **绝不**渲染成一张空的绿色卡片。
 * 5. **跳转只给真实存在的路由**。`targetRoute` 为 null 时不渲染死链，而是显示 targetNote。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（channel 服务，**独立一条 services[] 元素**）。 */
export const BASE = 'channel/service-task-status';

/** 能力声明。 */
export const CAPABILITIES_PATH = `${BASE}/capabilities`;

/** 筛选项。 */
export const OPTIONS_PATH = `${BASE}/options`;

/** 总览。 */
export const SUMMARY_PATH = `${BASE}/summary`;

/** 任务状态列表（真分页 + 真筛选）。 */
export const TASKS_PATH = `${BASE}/tasks`;

/** 单条任务。 */
export const TASK_PATH = (taskId: string) => `${BASE}/tasks/${taskId}`;

/** 提示清单（停滞与失败是两个独立列表）。 */
export const ALERTS_PATH = `${BASE}/alerts`;

// ================================================================ 严重度

/** 严重度：红（先处理）。 */
export const RED = 'RED';

/** 严重度：黄（预警）。 */
export const YELLOW = 'YELLOW';

/** 严重度：绿（正常）。 */
export const GREEN = 'GREEN';

/** 严重度顺序（红 → 黄 → 绿）。 */
export const SEVERITY_ORDER: readonly string[] = [RED, YELLOW, GREEN];

/** 严重度的人话。 */
export const SEVERITY_LABELS: Record<string, string> = {
  RED: '红 · 先处理',
  YELLOW: '黄 · 预警',
  GREEN: '绿 · 正常',
};

/** 严重度 → Badge 色调。 */
export const SEVERITY_TONES: Record<string, 'danger' | 'warning' | 'success' | 'neutral'> = {
  RED: 'danger',
  YELLOW: 'warning',
  GREEN: 'success',
};

/** 严重度排序权重（数字越小越靠前）。 */
export function severityRank(severity: string): number {
  const index = SEVERITY_ORDER.indexOf(severity);
  return index < 0 ? SEVERITY_ORDER.length : index;
}

// ================================================================ 失败分类

/** 失败分类：业务 / 配置问题。 */
export const FAILURE_CLASS_BUSINESS = 'BUSINESS';

/** 失败分类：系统 / 外部问题。 */
export const FAILURE_CLASS_SYSTEM = 'SYSTEM';

/** 失败分类：两类都有。 */
export const FAILURE_CLASS_MIXED = 'MIXED';

/** 失败分类：有失败但判不了分类。 */
export const FAILURE_CLASS_UNCLASSIFIED = 'UNCLASSIFIED';

/** 失败分类：失败队列读不到。 */
export const FAILURE_CLASS_UNDETERMINED = 'UNDETERMINED';

/** 失败分类：没有失败。 */
export const FAILURE_CLASS_NONE = 'NONE';

/** 失败分类的人话。 */
export const FAILURE_CLASS_LABELS: Record<string, string> = {
  BUSINESS: '业务 / 配置问题',
  SYSTEM: '系统 / 外部问题',
  MIXED: '两类都有',
  UNCLASSIFIED: '判不了分类',
  UNDETERMINED: '判不了（读不到）',
  NONE: '没有失败',
};

/** 失败分类 → Badge 色调。🔴 「判不了」用中性色 —— 它既不是红也不是黄。 */
export const FAILURE_CLASS_TONES: Record<string, 'danger' | 'warning' | 'success' | 'neutral'> = {
  BUSINESS: 'warning',
  SYSTEM: 'danger',
  MIXED: 'danger',
  UNCLASSIFIED: 'neutral',
  UNDETERMINED: 'neutral',
  NONE: 'success',
};

/**
 * 失败分类的一句话说明（页面直接用服务端给的 `failureClassNote`，
 * 这里只给一个**短的**徽标副标题）。
 */
export const FAILURE_CLASS_HINTS: Record<string, string> = {
  BUSINESS: '系统没坏，是数据 / 配置不对',
  SYSTEM: '毛病在平台侧或网络侧',
  MIXED: '两类原因都有，两件都要处理',
  UNCLASSIFIED: '这张表没有失败原因词表，判不了',
  UNDETERMINED: '失败队列读不到，判不了',
  NONE: '没有失败',
};

/** 失败原因的人话。 */
export const FAILURE_REASON_LABELS: Record<string, string> = {
  AUTHORIZATION_EXPIRED: '授权已失效',
  FIELD_PARSE_FAILED: '字段解析失败',
  MAPPING_MISSING: '映射缺失',
  NETWORK_ERROR: '网络错误',
  PLATFORM_ERROR: '平台侧错误',
  RATE_LIMITED: '命中限流',
};

// ================================================================ 任务状态

/** 规范化任务状态的人话。 */
export const TASK_STATE_LABELS: Record<string, string> = {
  RUNNING: '执行中',
  PENDING: '待执行',
  SUCCEEDED: '已完成',
  PARTIAL: '部分完成',
  FAILED: '失败',
  CANCELLED: '已中止',
  UNKNOWN: '状态未知',
};

/** 状态 → Badge 色调。 */
export const TASK_STATE_TONES: Record<string, 'danger' | 'warning' | 'success' | 'neutral'> = {
  RUNNING: 'warning',
  PENDING: 'neutral',
  SUCCEEDED: 'success',
  PARTIAL: 'warning',
  FAILED: 'danger',
  CANCELLED: 'neutral',
  UNKNOWN: 'neutral',
};

// ================================================================ 筛选

/** 筛选条件。 */
export interface ServiceTaskStatusFilters {
  sourceKey: string;
  state: string;
  triggerSource: string;
  keyword: string;
  stalled: '' | 'true' | 'false';
  failed: '' | 'true' | 'false';
}

/** 空筛选（全部）。 */
export const EMPTY_FILTERS: ServiceTaskStatusFilters = {
  sourceKey: '',
  state: '',
  triggerSource: '',
  keyword: '',
  stalled: '',
  failed: '',
};

/**
 * 把筛选条件翻译成后端查询串。
 *
 * 🔴 严重度**不在**这里 —— 服务端算好了，前端只按它过滤展示，不重算。
 */
export function buildQuery(filters: ServiceTaskStatusFilters, page: number, size: number): string {
  const parts = new URLSearchParams();
  if (filters.sourceKey.trim()) parts.set('sourceKey', filters.sourceKey.trim());
  if (filters.state.trim()) parts.set('state', filters.state.trim().toUpperCase());
  if (filters.triggerSource.trim())
    parts.set('triggerSource', filters.triggerSource.trim().toUpperCase());
  if (filters.keyword.trim()) parts.set('keyword', filters.keyword.trim());
  if (filters.stalled) parts.set('stalled', filters.stalled);
  if (filters.failed) parts.set('failed', filters.failed);
  parts.set('page', String(page));
  parts.set('size', String(size));
  return parts.toString();
}

// ================================================================ 排序与汇总

/** 按严重度排序（红在前）；同严重度时按来源名 + 标题稳定排序。 */
export function sortBySeverity(cards: ServiceTaskStatusTaskCard[]): ServiceTaskStatusTaskCard[] {
  return [...cards].sort(
    (left, right) =>
      severityRank(left.severity) - severityRank(right.severity) ||
      left.sourceLabel.localeCompare(right.sourceLabel) ||
      left.title.localeCompare(right.title),
  );
}

/** 任务汇总（红黄绿分布）。 */
export interface ServiceTaskStatusCounts {
  red: number;
  yellow: number;
  green: number;
  total: number;
}

/** 汇总计数。 */
export function summarize(cards: ServiceTaskStatusTaskCard[]): ServiceTaskStatusCounts {
  let red = 0;
  let yellow = 0;
  let green = 0;
  for (const card of cards) {
    if (card.severity === RED) red += 1;
    else if (card.severity === YELLOW) yellow += 1;
    else green += 1;
  }
  return { red, yellow, green, total: cards.length };
}

/** 逐来源统计（前端只做展示排序，不重算数字）。 */
export function sortSources(stats: ServiceTaskStatusSourceStat[]): ServiceTaskStatusSourceStat[] {
  return [...stats].sort((left, right) => left.sourceKey.localeCompare(right.sourceKey));
}

// ================================================================ 时间

/**
 * 格式化时刻。
 *
 * 🔴 **null ⇒「—」，不是「1970 年」**。时间读不到就是读不到，
 * 渲染成一个纪元时间会让人以为数据坏了。
 */
export function formatTimestamp(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 已执行多少分钟（服务端按数据库 now() 算好）。
 *
 * <p>🔴 服务端已经把分钟数算好放进 `stallMinutes` 了；这里只做展示，**不重算**。
 */
export function formatMinutes(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return '—';
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} 小时` : `${hours} 小时 ${rest} 分钟`;
}

/** 耗时（毫秒 → 人话）。 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—';
  if (ms < 1000) return `${ms} 毫秒`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds} 秒`;
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}

// ================================================================ 失败计数

/** 逐分类计数的渲染文案。 */
export function formatFailureClassCount(entry: ServiceTaskStatusFailureClassCount): string {
  return `近 ${entry.windowCount} 小时 ${entry.windowCount} 条 / 累计 ${entry.totalCount} 条`;
}

/** 逐原因计数的渲染文案。 */
export function formatFailureReasonCount(entry: ServiceTaskStatusFailureReasonCount): string {
  return `${entry.label} · 近 ${entry.windowCount} 小时 ${entry.windowCount} 条 / 累计 ${entry.totalCount} 条`;
}

// ================================================================ 跳转

/**
 * 提示的跳转落点。
 *
 * 🔴 **只给真实存在的路由**。`targetRoute` 为 null 时（没有独立页面）
 * 返回 null，页面上就**不渲染一个死链**，而是显示 targetNote 说明真实状况。
 */
export function alertHref(alert: ServiceTaskStatusAlert): string | null {
  if (!alert.targetAvailable) return null;
  return alert.targetRoute ?? null;
}

/** 提示按严重度排序（红在前）；同severity 时按标题稳定排序。 */
export function sortAlerts(alerts: ServiceTaskStatusAlert[]): ServiceTaskStatusAlert[] {
  return [...alerts].sort(
    (left, right) =>
      severityRank(left.severity) - severityRank(right.severity) ||
      left.title.localeCompare(right.title),
  );
}

// ================================================================ 规则表

/**
 * 规则码 → 严重度的映射（从服务端下发的规则表里读，**不在前端另写一份**）。
 *
 * <p>🔴 前端**不重算**严重度，但**要**能把服务端下发的规则表按严重度分组展示 ——
 * 分组用的还是服务端那张表。
 */
export function rulesBySeverity(
  rules: ServiceTaskStatusRuleDoc[],
): { severity: string; rules: ServiceTaskStatusRuleDoc[] }[] {
  const groups: { severity: string; rules: ServiceTaskStatusRuleDoc[] }[] = [];
  for (const severity of SEVERITY_ORDER) {
    const matched = rules.filter((rule) => rule.severity === severity);
    if (matched.length > 0) groups.push({ severity, rules: matched });
  }
  const rest = rules.filter((rule) => !SEVERITY_ORDER.includes(rule.severity));
  if (rest.length > 0) groups.push({ severity: '其它', rules: rest });
  return groups;
}

/** 阈值的人话。 */
export function formatThresholds(thresholds: ServiceTaskStatusThresholds): string {
  return (
    `超过 ${thresholds.stallWarnMinutes} 分钟还在跑 ⇒ 停滞预警（黄）；` +
    `超过 ${thresholds.stallCriticalMinutes} 分钟还在跑 ⇒ 停滞严重（红）；` +
    `失败计数窗口 ${thresholds.failureWindowHours} 小时。`
  );
}

/**
 * 一条任务的人话标题。
 *
 * 🔴 标题里带来源名 —— 否则五个来源的任务混在一起时，
 * 用户根本不知道「这条是同步运行还是发布任务」。
 */
export function taskTitle(card: ServiceTaskStatusTaskCard): string {
  return `${card.sourceLabel} · ${card.title}`;
}

export type {
  ServiceTaskStatusAlert,
  ServiceTaskStatusAlertList,
  ServiceTaskStatusCapabilities,
  ServiceTaskStatusFailureClassCount,
  ServiceTaskStatusFailureClassDoc,
  ServiceTaskStatusFailureReasonCount,
  ServiceTaskStatusOptions,
  ServiceTaskStatusReason,
  ServiceTaskStatusRuleDoc,
  ServiceTaskStatusSourceDoc,
  ServiceTaskStatusSourceStat,
  ServiceTaskStatusSummary,
  ServiceTaskStatusTaskCard,
  ServiceTaskStatusTaskPage,
  ServiceTaskStatusThresholds,
};
