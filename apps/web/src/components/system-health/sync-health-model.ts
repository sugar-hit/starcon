import type {
  SyncHealthBacklogSource,
  SyncHealthCapabilities,
  SyncHealthConnectionCard,
  SyncHealthConnectionPage,
  SyncHealthFailureReasonCount,
  SyncHealthOptions,
  SyncHealthPendingItem,
  SyncHealthPendingList,
  SyncHealthPlatformGap,
  SyncHealthReason,
  SyncHealthRuleDoc,
  SyncHealthSummary,
  SyncHealthThresholds,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 1.5.1.1 同步与 API 健康 —— 前端模型。
 *
 * 蓝图（`docs/product/capability-blueprint/01-dashboard-and-portal.md` 第 215–220 行）：
 * 「一屏回答"现在能不能正常同步"：各平台连接状态、最近同步时间、失败计数、限流命中、
 * 队列积压，按店铺/连接展示红黄绿。输出：连接健康卡片 + 需处理项跳转（重授权、
 * 看失败队列、查积压）。」
 *
 * 🔴 本页是**只读工作台**（卷 01 的边界写死「工作台只读 + 跳转；一切状态变更回到
 * 归属系统完成」）。所以前端**只渲染服务端算好的结论**，不重算红黄绿、不重算计数，
 * 也不提供「重试 / 重新授权」这类写动作 —— 待处理项一律是**跳转**。
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「读不到」与「没有」分开**。后端 503 / 403 时页面显式显示「读不到」并给重试，
 *    **绝不**渲染成一张空的绿色卡片（那等于把「没查到」说成「一切正常」）。
 * 2. **「没有连接」与「连接健康」分开**。没有连接的平台走 `platformsWithoutConnection`
 *    单独一段，措辞是「还没接这个平台」，不是「连接健康」。
 * 3. **两个「最近同步时刻」分开**。卡片上分别标 `lastSyncAt`（连接级声明值）与
 *    `effectiveLastSyncAt`（运行级完成时刻），并按 `lastSyncAtSource` 标明用的是哪一个。
 * 4. **红黄绿不重算**。严重度来自服务端，前端只做展示与筛选；筛选也只是**过滤**，
 *    不改写结论。
 * 5. **积压不是一个来源不明的数字**。每张表一行，带「算作积压」的判定条件。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（channel 服务，**独立一条 services[] 元素**）。 */
export const BASE = 'channel/sync-health';

/** 能力声明。 */
export const CAPABILITIES_PATH = `${BASE}/capabilities`;

/** 筛选项。 */
export const OPTIONS_PATH = `${BASE}/options`;

/** 总览。 */
export const SUMMARY_PATH = `${BASE}/summary`;

/** 连接健康卡片列表（真分页 + 真筛选）。 */
export const CONNECTIONS_PATH = `${BASE}/connections`;

/** 单张卡片。 */
export const CONNECTION_PATH = (connectionId: string) => `${BASE}/connections/${connectionId}`;

/** 需处理项。 */
export const PENDING_ITEMS_PATH = `${BASE}/pending-items`;

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

/** 按严重度排序（红在前）；同severity 时按平台名 + 连接名稳定排序。 */
export function sortBySeverity(cards: SyncHealthConnectionCard[]): SyncHealthConnectionCard[] {
  return [...cards].sort(
    (left, right) =>
      severityRank(left.severity) - severityRank(right.severity) ||
      left.platformName.localeCompare(right.platformName) ||
      left.displayName.localeCompare(right.displayName),
  );
}

// ================================================================ 筛选

/** 筛选条件。 */
export interface SyncHealthFilters {
  severity: string;
  platformCode: string;
  keyword: string;
}

/** 空筛选（全部）。 */
export const EMPTY_FILTERS: SyncHealthFilters = { severity: '', platformCode: '', keyword: '' };

/**
 * 按筛选条件过滤卡片。
 *
 * 🔴 关键词匹配的是**连接显示名 / 平台账号 / 平台名 / 店铺名** ——
 * 运营说「Amazon 那家店」时，多半说的是店铺而不是连接。
 */
export function filterCards(
  cards: SyncHealthConnectionCard[],
  filters: SyncHealthFilters,
): SyncHealthConnectionCard[] {
  const severity = filters.severity.trim();
  const platformCode = filters.platformCode.trim().toUpperCase();
  const keyword = filters.keyword.trim().toLowerCase();
  return cards.filter((card) => {
    if (severity && card.severity !== severity) return false;
    if (platformCode && card.platformCode.toUpperCase() !== platformCode) return false;
    if (!keyword) return true;
    const haystack = [
      card.displayName,
      card.authorizedAccount,
      card.platformName,
      card.platformCode,
      ...card.shops.map((shop) => `${shop.shopCode} ${shop.shopName}`),
    ]
      .join(' ')
      .toLowerCase();
    return haystack.includes(keyword);
  });
}

/** 把筛选条件翻译成后端查询串（平台码 / 状态走后端分页；严重度与关键词在前端筛）。 */
export function buildQuery(filters: SyncHealthFilters, page: number, size: number): string {
  const parts = new URLSearchParams();
  if (filters.platformCode.trim()) parts.set('platformCode', filters.platformCode.trim());
  parts.set('page', String(page));
  parts.set('size', String(size));
  return parts.toString();
}

// ================================================================ 汇总

/** 卡片汇总（红黄绿分布）。 */
export interface SyncHealthCounts {
  red: number;
  yellow: number;
  green: number;
  total: number;
}

/** 汇总计数。 */
export function summarize(cards: SyncHealthConnectionCard[]): SyncHealthCounts {
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
 * 相对时间（"3 小时前"）。
 *
 * <p>🔴 服务端已经把「距今多少小时」算好放进 `staleHours` 了；
 * 这里只做展示，不重算判定阈值。
 */
export function formatStaleHours(hours: number | null | undefined): string {
  if (hours === null || hours === undefined) return '—';
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  const rest = hours % 24;
  return rest === 0 ? `${days} 天前` : `${days} 天 ${rest} 小时前`;
}

/** 耗时（毫秒 → 人话）。 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—';
  if (ms < 1000) return `${ms} 毫秒`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds} 秒`;
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}

// ================================================================ 失败原因

/** 失败原因的人话。 */
export const FAILURE_REASON_LABELS: Record<string, string> = {
  AUTHORIZATION_EXPIRED: '授权已失效',
  FIELD_PARSE_FAILED: '字段解析失败',
  MAPPING_MISSING: '映射缺失',
  RATE_LIMITED: '命中限流',
  NETWORK_ERROR: '网络错误',
  PLATFORM_ERROR: '平台侧错误',
};

/** 逐原因计数的渲染文案。 */
export function formatFailureCount(entry: SyncHealthFailureReasonCount): string {
  const label = FAILURE_REASON_LABELS[entry.reason] ?? entry.reason;
  return `${label} 近 ${entry.windowCount} 小时 ${entry.windowCount} 条 / 累计 ${entry.totalCount} 条`;
}

// ================================================================ 积压

/**
 * 积压合计。
 *
 * 🔴 **只加真实表**。后端返回的 `backlog` 列表里每一行都是一张真实表，
 * 合计 = 逐行相加（不要自己再塞一行「合计」进去 —— 那会被算两遍）。
 */
export function backlogTotal(sources: SyncHealthBacklogSource[]): number {
  return sources.reduce((sum, source) => sum + source.pendingCount, 0);
}

/** 平台缺口的措辞：区分「还没接」与「尚未接入」。 */
export function platformGapTone(gap: SyncHealthPlatformGap): 'warning' | 'neutral' {
  return gap.state === 'NOT_INTEGRATED' ? 'neutral' : 'warning';
}

// ================================================================ 待处理项

/**
 * 待处理项的跳转落点。
 *
 * 🔴 **只给真实存在的路由**。`targetRoute` 为 null 时（没有独立页面）
 * 返回 null，页面上就**不渲染一个死链**，而是显示 targetNote 说明真实状况。
 */
export function pendingHref(item: SyncHealthPendingItem): string | null {
  if (!item.targetAvailable) return null;
  return item.targetRoute ?? null;
}

/** 待处理项按严重度排序（红在前）。 */
export function sortPendingItems(items: SyncHealthPendingItem[]): SyncHealthPendingItem[] {
  return [...items].sort(
    (left, right) =>
      severityRank(left.severity) - severityRank(right.severity) || left.title.localeCompare(right.title),
  );
}

// ================================================================ 规则表

/**
 * 规则码 → 严重度的映射（从服务端下发的规则表里读，**不在前端另写一份**）。
 *
 * 🔴 前端**不重算**红黄绿，但**要**能把服务端下发的规则表按严重度分组展示 ——
 * 分组用的还是服务端那张表。
 */
export function rulesBySeverity(
  rules: SyncHealthRuleDoc[],
): { severity: string; rules: SyncHealthRuleDoc[] }[] {
  const groups: { severity: string; rules: SyncHealthRuleDoc[] }[] = [];
  for (const severity of SEVERITY_ORDER) {
    const matched = rules.filter((rule) => rule.severity === severity);
    if (matched.length > 0) groups.push({ severity, rules: matched });
  }
  const rest = rules.filter((rule) => !SEVERITY_ORDER.includes(rule.severity));
  if (rest.length > 0) groups.push({ severity: '其它', rules: rest });
  return groups;
}

/** 阈值的人话。 */
export function formatThresholds(thresholds: SyncHealthThresholds): string {
  return (
    `超过 ${thresholds.staleSyncWarnHours} 小时没同步 ⇒ 黄；` +
    `超过 ${thresholds.staleSyncCriticalHours} 小时没同步 ⇒ 红；` +
    `失败计数窗口 ${thresholds.failureWindowHours} 小时。`
  );
}

export type {
  SyncHealthBacklogSource,
  SyncHealthCapabilities,
  SyncHealthConnectionCard,
  SyncHealthConnectionPage,
  SyncHealthFailureReasonCount,
  SyncHealthOptions,
  SyncHealthPendingItem,
  SyncHealthPendingList,
  SyncHealthPlatformGap,
  SyncHealthReason,
  SyncHealthRuleDoc,
  SyncHealthSummary,
  SyncHealthThresholds,
};
