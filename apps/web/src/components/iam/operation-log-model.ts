import type {
  OperationLogDetail,
  OperationLogPage,
  OperationLogTrace,
  OperationLogView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 17.5.3.2 操作日志前端模型。
 *
 * <p>与 17.5.3.1 审计日志的分工：审计回答「谁对什么做了什么、凭什么」，
 * 这里回答「这次调用发生了什么、慢在哪、错在哪」，外加把一次请求串成链路的关联视图。
 */

/** 事件类型 → 中文。与后端 OperationLog.EVENT_TYPES 同一份取值。 */
export const eventTypeLabels: Record<string, string> = {
  LOGIN: '登录',
  LOGOUT: '登出',
  API_CALL: 'API 调用',
  TASK_RUN: '任务执行',
  ERROR: '错误',
};

export const eventTypes = ['LOGIN', 'LOGOUT', 'API_CALL', 'TASK_RUN', 'ERROR'] as const;

/** 级别 → 中文与色调。4xx 是调用方的问题，5xx 是服务端的问题，排障时必须一眼分开。 */
export const levelLabels: Record<string, string> = {
  INFO: '正常',
  WARNING: '告警',
  ERROR: '错误',
};

export function eventTypeLabel(type: string) {
  return eventTypeLabels[type] ?? type;
}

export function levelLabel(level: string) {
  return levelLabels[level] ?? level;
}

export type OperationLogFilters = {
  actorId: string;
  eventType: string;
  level: string;
  requestId: string;
  from: string;
  to: string;
};

export const emptyFilters: OperationLogFilters = {
  actorId: '',
  eventType: '',
  level: '',
  requestId: '',
  from: '',
  to: '',
};

/**
 * 把筛选条件变成 query string。
 *
 * <p>只带非空项：`?actorId=` 这种「看着像筛选、其实被后端当未传」的参数会让
 * 「查不到结果」和「没在筛」看起来一模一样。
 */
export function operationLogQuery(filters: OperationLogFilters, page: number, size: number) {
  const parts: string[] = [`page=${page}`, `size=${size}`];
  if (filters.actorId) parts.push(`actorId=${encodeURIComponent(filters.actorId)}`);
  if (filters.eventType) parts.push(`eventType=${encodeURIComponent(filters.eventType)}`);
  if (filters.level) parts.push(`level=${encodeURIComponent(filters.level)}`);
  if (filters.requestId) parts.push(`requestId=${encodeURIComponent(filters.requestId)}`);
  if (filters.from) parts.push(`from=${encodeURIComponent(new Date(filters.from).toISOString())}`);
  if (filters.to) parts.push(`to=${encodeURIComponent(new Date(filters.to).toISOString())}`);
  return parts.join('&');
}

export function fetchOperationLog(filters: OperationLogFilters, page: number, size = 50) {
  return api<OperationLogPage>(`identity/operation-log?${operationLogQuery(filters, page, size)}`);
}

export function fetchOperationLogDetail(id: string) {
  return api<OperationLogDetail>(`identity/operation-log/${id}`);
}

/**
 * 链路关联视图：按请求 ID 把一次调用引发的多条日志串起来。
 *
 * <p>聚合统计由服务端在数据库里算好（见 operation_trace），这里只负责取回与呈现，
 * 不在前端数条目——那会让列表分页与链路聚合给出两个互相矛盾的口径。
 */
export function fetchOperationLogTrace(requestId: string) {
  return api<OperationLogTrace>(`identity/operation-log/trace/${encodeURIComponent(requestId)}`);
}

/** 导出链接：与列表共用同一套条件，导出结果和当前看到的完全一致。 */
export function operationLogExportUrl(filters: OperationLogFilters) {
  return `/api/iam/identity/operation-log/export?${operationLogQuery(filters, 0, 1)}`;
}

/**
 * 链路里的一步。
 *
 * <p>供 TracePanel 按真实发生顺序展示，并标出这一步的级别与耗时。
 */
export function traceSteps(trace: OperationLogTrace) {
  return trace.entries.map((entry) => ({
    id: entry.id,
    at: entry.occurredAt,
    eventType: entry.eventType,
    eventLabel: eventTypeLabel(entry.eventType),
    level: entry.level,
    summary: entry.summary,
    durationMs: entry.durationMs,
    actorName: entry.actorName,
    errorClass: entry.errorClass,
  }));
}

/** 耗时展示：保留亚毫秒精度，排障时「偶发卡顿」正是靠这一点才看得见。 */
export function formatDuration(value: number | null | undefined) {
  if (value === null || value === undefined) return '—';
  if (value >= 1000) return `${(value / 1000).toFixed(3)} s`;
  return `${value} ms`;
}

/** 请求 ID 缩写：链路面板里完整 ID 太长，但尾部几段才是区分不同调用的部分。 */
export function shortRequestId(value: string) {
  if (value.length <= 12) return value;
  return `…${value.slice(-12)}`;
}

/** 时间格式化：后端给的是 UTC ISO 串，界面按本地时区展示。 */
export function formatTime(value: string) {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export type { OperationLogView };
