'use client';

import type {
  BackfillCapabilities,
  BackfillCompleteInput,
  BackfillSubmitInput,
  BackfillSubmitResult,
  BackfillTaskInput,
  BackfillTaskView,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 2.6.2.1 增量补拉的前端模型层。
 *
 * <p>契约里 codegen 把 Java 枚举**内联成联合类型**（`"ORDER" | "LISTING"`），不导出独立的
 * 枚举别名 —— 这里按生成物当前形状本地声明，契约一改（多一个取值）typecheck 就会在这里断掉，
 * 而不是等到运行时才发现。
 */

/** 与后端 {@code BackfillController} 同前缀。 */
const base = 'channel/backfills';

export type BackfillResourceType = 'ORDER' | 'LISTING' | 'INVENTORY' | 'SETTLEMENT' | 'PRODUCT';
export type BackfillTrigger = 'WEBHOOK_GAP' | 'CURSOR_REWIND' | 'MANUAL' | 'PLATFORM_RECOVERY';
export type PaginationMode = 'TIME_INCREMENTAL' | 'RECENT_N_SCAN';
export type BackfillStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  const response = init
    ? await fetch(`/api/${path}`, {
        credentials: 'include',
        ...init,
        ...(init.body ? { headers: { 'Content-Type': 'application/json' } } : {}),
      })
    : await fetch(`/api/${path}`, { credentials: 'include' });
  if (!response.ok) throw new ApiError(response.status, await response.text());
  return (await response.json()) as T;
}

export const fetchCapabilities = () => call<BackfillCapabilities>(`${base}/capabilities`);

export const fetchTasks = (params: { connectionId?: string; status?: string } = {}) => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return call<BackfillTaskView[]>(suffix ? `${base}?${suffix}` : base);
};

export const createTask = (input: BackfillTaskInput) =>
  call<string>(base, { method: 'POST', body: JSON.stringify(input) });

export const fetchTask = (id: string) => call<BackfillTaskView>(`${base}/${id}`);

export const submitRecords = (id: string, input: BackfillSubmitInput) =>
  call<BackfillSubmitResult>(`${base}/${id}/records`, { method: 'POST', body: JSON.stringify(input) });

export const completeTask = (id: string, input: BackfillCompleteInput) =>
  call<void>(`${base}/${id}/complete`, { method: 'POST', body: JSON.stringify(input) });

export const cancelTask = (id: string) => call<void>(`${base}/${id}/cancel`, { method: 'POST' });

// ---------------------------------------------------------------------------
// 展示口径
// ---------------------------------------------------------------------------

export const RESOURCE_TYPE_LABELS: Record<BackfillResourceType, string> = {
  ORDER: '订单',
  LISTING: '平台商品',
  INVENTORY: '库存',
  SETTLEMENT: '结算',
  PRODUCT: '平台商品目录',
};

export const TRIGGER_LABELS: Record<BackfillTrigger, string> = {
  WEBHOOK_GAP: '漏推检测',
  CURSOR_REWIND: '游标回退',
  MANUAL: '人工发起',
  PLATFORM_RECOVERY: '平台故障恢复',
};

export const STATUS_LABELS: Record<BackfillStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCEEDED: '已完成',
  PARTIAL: '部分完成',
  FAILED: '失败',
  CANCELLED: '已中止',
};

export const DISPOSITION_LABELS: Record<string, string> = {
  CREATED: '新增',
  UPDATED: '更新',
  DEDUPLICATED: '去重',
  FAILED: '失败',
};

/**
 * 补拉任务的状态色。
 *
 * <p>FAILED 用危险色，CANCELLED 与 PARTIAL 用中性提醒色：部分完成**不是事故**，
 * 它通常意味着「这一段里有几条是重复的」——那是幂等在正常工作，不是出了错。
 */
export function statusTone(status: string): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'PARTIAL':
    case 'RUNNING':
    case 'PENDING':
      return 'warning';
    default:
      return 'neutral';
  }
}

export interface BackfillForm {
  connectionId: string;
  resourceType: BackfillResourceType;
  triggerSource: BackfillTrigger;
  windowStart: string;
  windowEnd: string;
  paginationMode: PaginationMode;
  idempotencyKey: string;
  pageSize: number;
  maxPages: number;
}

/** 发起补拉的默认表单：窗口默认取最近两小时，补拉多数是「刚才那段时间漏了」。 */
export function emptyBackfillForm(): BackfillForm {
  const end = new Date(Date.now() - 5 * 60_000);
  const start = new Date(end.getTime() - 2 * 60 * 60_000);
  const local = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  return {
    connectionId: '',
    resourceType: 'ORDER',
    triggerSource: 'MANUAL',
    windowStart: local(start),
    windowEnd: local(end),
    paginationMode: 'TIME_INCREMENTAL',
    // 幂等键默认按「连接 + 资源 + 窗口」派生：同一段区间重复提交会拿回同一个任务，
    // 而不是悄悄产生第二次补拉（蓝图把 idempotency_key 列为输入契约的一部分）。
    idempotencyKey: '',
    pageSize: 100,
    maxPages: 20,
  };
}

/**
 * 幂等键：连接 + 资源类型 + 窗口起点。
 *
 * <p>同一段区间发两次（漏推检测与人工发起各来一次）会拿回**同一个**任务，
 * 而不会产生第二个补拉任务 —— 这正是后端唯一索引要挡住的事。
 */
export function derivedIdempotencyKey(form: BackfillForm): string {
  return [form.connectionId || 'no-connection', form.resourceType, form.windowStart].join(':');
}

/** 时间窗校验。返回错误文案，合法时返回 null。 */
export function validateWindow(form: BackfillForm): string | null {
  if (!form.windowStart || !form.windowEnd) return '时间窗两端都要填';
  const start = new Date(form.windowStart).getTime();
  const end = new Date(form.windowEnd).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) return '时间窗格式不正确';
  // 零长窗口捞不到任何东西，却会让人以为「该区间已确认无缺口」——比不发这个补拉更糟
  if (end <= start) return '时间窗右端必须晚于左端';
  if (end > Date.now() + 24 * 3600_000) return '时间窗不能延伸到未来';
  return null;
}

/** 组装发起补拉的入参。 */
export function buildTaskInput(form: BackfillForm): BackfillTaskInput {
  return {
    connectionId: form.connectionId,
    // 站点维度可选：不选即「该连接下所有站点的合并补拉」，与 sync_cursor 的同款语义。
    marketplaceId: null,
    resourceType: form.resourceType,
    triggerSource: form.triggerSource,
    windowStart: new Date(form.windowStart).toISOString(),
    windowEnd: new Date(form.windowEnd).toISOString(),
    idempotencyKey: form.idempotencyKey || derivedIdempotencyKey(form),
    // 关联的断点续传游标：不选则不推进任何游标（补拉只填补，不改常规增量位置）。
    cursorId: null,
    paginationMode: form.paginationMode,
    // 分段轮询（平台只给「最近 N 条」）必须承认自己可能延迟发现：
    // 后端 DB 层 CHECK 也会强制这条为 true，这里显式传，避免依赖服务端默认。
    delayedDiscovery: form.paginationMode === 'RECENT_N_SCAN',
    pageSize: form.pageSize,
    maxPages: form.maxPages,
    rateLimitPerMinute: 60,
  };
}

/** 四个数字的口径说明：合计会把「补到了东西」与「补到了没见过的东西」混为一谈。 */
export function countHint(task: BackfillTaskView): string {
  return `拉取 ${task.fetchedCount} · 新增 ${task.createdCount} · 更新 ${task.updatedCount} · 去重 ${task.deduplicatedCount} · 失败 ${task.failedCount}`;
}

/**
 * 「补拉可能延迟发现」的提示文案。
 *
 * <p>平台只提供「最近 N 条」时，轮询过程中平台仍在推新数据会把「最近 N 条」顶走，
 * 窗口早期的那部分只能靠下一轮才补到 —— 这不是缺陷，是接口形态的必然，
 * 所以必须**如实标出来**，而不是让人以为这是一次完整补拉。
 */
export function delayedDiscoveryHint(): string {
  return '该平台只提供「最近 N 条」，不能按时间直接定位。分段轮询期间平台若推送新数据，窗口早期的记录会被顶到下一轮才补到 —— 补拉可能延迟发现。';
}

export function formatInstant(value: string | null): string {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString();
}
