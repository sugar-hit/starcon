import type {
  ReservationReleaseAllocationInput,
  ReservationReleaseCandidatePage,
  ReservationReleaseExpiryInput,
  ReservationReleaseExpiryOutcome,
  ReservationReleaseInput,
  ReservationReleaseOption,
  ReservationReleaseOptions,
  ReservationReleaseOutcome,
  ReservationReleaseRecordPage,
  ReservationReleaseRunDetail,
  ReservationReleaseRunPage,
  ReservationReleaseRunRequest,
  ReservationReleaseRunRow,
  ReservationCapability,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 7.2.1.3 释放与过期前端模型。
 *
 * <p>蓝图：「处理预占的释放（订单取消、支付超时、拆单减少）与过期回收，提供手动释放入口与
 * 批量过期清理，释放时同步恢复可用量。」输出「预占终态记录 + 余额恢复分录」。
 *
 * <p>这个页面上最容易把事实读错的三处，都在这里显式处理：
 *
 * <ul>
 *   <li><b>「还回可用池」与「需求释放」是两个数，不合并</b>。需求 12、只圈到 10 的货整笔释放时，
 *       前者是 10（真的占过库存的那部分）、后者是 12（需求侧放弃的总量）。
 *       合成一个数字，可用量就会凭空多出 2，而可用量只准有一个来源。
 *   <li><b>超时时长为 null 显示「—」而不是 0</b>。null 是「没超时 / 不参与超时」，
 *       0 是「超时 0 秒」，把两者都渲染成 0 会让「没到期」看起来像「刚到期」。
 *   <li><b>判定时刻常驻显示</b>。候选清单是「按某个时刻算出来的」，不显示那个时刻，
 *       用户会把一次预演当成实时状态。
 * </ul>
 */

/** 集合级基址与 7.2.1.1 / 7.2.1.2 共用：释放的对象就是预占，挂在同一条路径之下。 */
export const RESERVATION_RELEASE_BASE = 'warehouse/inventory/reservations';

/** 过期候选查询条件。与后端 `ReservationReleaseQuery` 一一对应。 */
export interface ReservationReleaseParams {
  sourceType?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
  /** 规则判定时刻（带时区偏移的 ISO 串）。不传则用服务端当前时刻。 */
  evaluatedAt?: string;
}

function queryString(parts: string[]): string {
  return parts.length ? `?${parts.join('&')}` : '';
}

/**
 * 过期候选清单 + 汇总。
 *
 * <p>「可还回量」这一列是货物的量，「需求量 − 已消耗」是需求的量，页面上分两列显示。
 */
export function fetchReservationReleaseCandidates(params: ReservationReleaseParams = {}) {
  const parts: string[] = [];
  if (params.sourceType) parts.push(`sourceType=${encodeURIComponent(params.sourceType)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.evaluatedAt) parts.push(`evaluatedAt=${encodeURIComponent(params.evaluatedAt)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<ReservationReleaseCandidatePage>(
    `${RESERVATION_RELEASE_BASE}/release-candidates${queryString(parts)}`,
  );
}

/** 批量过期清理的运行历史。 */
export function fetchReservationReleaseRuns(page = 0, size = 20) {
  return api<ReservationReleaseRunPage>(
    `${RESERVATION_RELEASE_BASE}/expiry-runs?page=${page}&size=${size}`,
  );
}

/** 单次运行详情 = 运行凭据 + 它产生的释放凭据。 */
export function fetchReservationReleaseRun(runId: string) {
  return api<ReservationReleaseRunDetail>(`${RESERVATION_RELEASE_BASE}/expiry-runs/${runId}`);
}

/** 释放凭据（可按预占过滤）。 */
export function fetchReservationReleaseRecords(
  params: { reservationId?: string; page?: number; size?: number } = {},
) {
  const parts: string[] = [];
  if (params.reservationId) parts.push(`reservationId=${encodeURIComponent(params.reservationId)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<ReservationReleaseRecordPage>(
    `${RESERVATION_RELEASE_BASE}/release-records${queryString(parts)}`,
  );
}

/** 下拉项：释放方式、释放来源、排序键，以及过期规则说明。 */
export function fetchReservationReleaseOptions() {
  return api<ReservationReleaseOptions>(`${RESERVATION_RELEASE_BASE}/release-options`);
}

/** 链路各环节的接入情况（含「可用量口径归 7.1.1.3」这一位的如实声明）。 */
export function fetchReservationReleaseCapabilities() {
  return api<ReservationCapability[]>(`${RESERVATION_RELEASE_BASE}/release-capabilities`);
}

/**
 * codegen 把标了 `@Schema(nullable = true)` 的字段生成为 `T | null` 而不是可选字段，
 * 所以直接传对象字面量必须把每个可空字段都写成 `null`（漏一个就报 TS2345）。
 * 这里收 `Partial` 并统一补 null，让调用方写 `{ triggerSource: 'MANUAL' }` 就够了。
 */
export type ReservationReleaseRequest = Partial<ReservationReleaseInput>;
export type ReservationReleaseRunBody = Partial<ReservationReleaseRunRequest>;
export type ReservationReleaseExpiryBody = Partial<ReservationReleaseExpiryInput>;

function fill<T extends object>(input: T): T {
  const out = { ...input } as Record<string, unknown>;
  for (const [key, value] of Object.entries(out)) {
    if (value === undefined) out[key] = null;
  }
  return out as T;
}

/**
 * 手动释放：订单取消 / 支付超时 / 拆单减少 / 人工。
 *
 * <p>不传 `allocations` 与 `quantity` = 整笔释放；只传 `quantity` = 按圈定时间 FIFO 释放到该量；
 * 传 `allocations` = 逐条按分配释放。
 *
 * <p>`idempotencyKey` 建议由人工动作带上：上游事件是至少投递一次，重投必须拿到同一个回执，
 * 而不是把货还回两次。
 */
export function releaseReservation(
  reservationId: string,
  input: ReservationReleaseRequest = {},
) {
  return api<ReservationReleaseOutcome>(
    `${RESERVATION_RELEASE_BASE}/${reservationId}/release`,
    'POST',
    fill(input),
  );
}

/**
 * 批量过期清理。
 *
 * <p>`dryRun` 先看不动；`evaluatedAt` 让调度器能对同一批作业用同一个判定时刻重投。
 */
export function expireOverdueReservations(input: ReservationReleaseRunBody = {}) {
  return api<ReservationReleaseRunRow>(
    `${RESERVATION_RELEASE_BASE}/expire-overdue`,
    'POST',
    fill(input),
  );
}

/** 补写 / 清空过期时刻。7.2.1.1 登记时没传 expiresAt 的那些靠它补。 */
export function setReservationExpiry(
  reservationId: string,
  input: ReservationReleaseExpiryBody = {},
) {
  return api<ReservationReleaseExpiryOutcome>(
    `${RESERVATION_RELEASE_BASE}/${reservationId}/expiry`,
    'POST',
    fill(input),
  );
}

// ================================================================ 展示口径

/** 释放方式标签。 */
export function releaseModeLabel(mode: string | undefined, label?: string | null): string {
  if (label) return label;
  switch (mode) {
    case 'RELEASE':
      return '释放';
    case 'EXPIRE':
      return '超时回收';
    default:
      return mode ?? '未知方式';
  }
}

/** 释放来源标签。 */
export function releaseTriggerLabel(
  trigger: string | undefined,
  label?: string | null,
): string {
  if (label) return label;
  switch (trigger) {
    case 'ORDER_CANCELLED':
      return '订单取消';
    case 'PAYMENT_TIMEOUT':
      return '支付超时';
    case 'SPLIT_REDUCTION':
      return '拆单减少';
    case 'MANUAL':
      return '人工释放';
    case 'EXPIRY_SWEEP':
      return '超时回收';
    default:
      return trigger ?? '未知来源';
  }
}

/**
 * 超时时长文案。
 *
 * <p>null → 「—」。给 0 会把「没超时」「没设过期时刻」「超时 0 秒」三件事画成一个数。
 */
export function overdueText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '—';
  if (seconds <= 0) return '0 秒';
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days} 天 ${hours} 小时`;
  if (hours > 0) return `${hours} 小时 ${minutes} 分`;
  return `${minutes} 分 ${seconds % 60} 秒`;
}

/**
 * 释放结果的量文案。
 *
 * <p>两个数都出现且不相等时**必须都显示**：那正是「需求放弃 12、可用量只还回 10」的场合，
 * 只显示一个会把差额变成一个说不清的数。
 */
export function restoredText(
  restoredQuantity: number | null | undefined,
  releasedDemandQuantity: number | null | undefined,
): string {
  const restored = restoredQuantity ?? 0;
  const released = releasedDemandQuantity ?? 0;
  if (Math.abs(restored - released) < 1e-9) return `${restored}`;
  return `${restored}（需求侧放弃 ${released}）`;
}

/** 运行凭据的一行摘要。干跑与真跑必须一眼可分，否则「干跑了但没执行」会被当成已清理。 */
export function runSummaryText(run: ReservationReleaseRunRow): string {
  if (run.dryRun) {
    return `干跑：判定 ${run.candidateCount} 条候选，未做任何变更`;
  }
  const skipped = run.skippedCount > 0 ? `，跳过 ${run.skippedCount} 条` : '';
  return `回收 ${run.processedCount} 条${skipped}，还回可用量 ${run.restoredQuantity}`;
}

/** 下拉候选（静态兜底；接口不可用时页面仍能给出正确的取值）。 */
export const RESERVATION_RELEASE_MODE_OPTIONS: ReservationReleaseOption[] = [
  { code: 'RELEASE', label: '释放', sweepOnly: false, description: '订单取消 / 拆单减少 / 人工' },
  { code: 'EXPIRE', label: '超时回收', sweepOnly: false, description: '只回收仍在 HELD 的预占' },
];

export const RESERVATION_RELEASE_TRIGGER_OPTIONS: ReservationReleaseOption[] = [
  { code: 'ORDER_CANCELLED', label: '订单取消', sweepOnly: false, description: null },
  { code: 'PAYMENT_TIMEOUT', label: '支付超时', sweepOnly: false, description: null },
  { code: 'SPLIT_REDUCTION', label: '拆单减少', sweepOnly: false, description: null },
  { code: 'MANUAL', label: '人工释放', sweepOnly: false, description: null },
  { code: 'EXPIRY_SWEEP', label: '超时回收', sweepOnly: true, description: '仅批量过期清理可用' },
];

export const RESERVATION_RELEASE_SORT_OPTIONS = [
  { code: 'EXPIRES_AT', label: '按过期时刻' },
  { code: 'OVERDUE', label: '按超时时长' },
  { code: 'RESTORABLE', label: '按可还回量' },
  { code: 'RESERVATION_NO', label: '按预占单号' },
];

/** 「EXPIRY_SWEEP 不能用于手动入口」的提示。常驻显示，免得用户选了才被拒。 */
export const SWEEP_ONLY_TRIGGER_NOTE = '「超时回收」只由批量过期清理产生，手动释放请选订单取消 / 拆单减少 / 人工。';

/** 恢复量与释放量的口径说明。常驻显示，否则「还回 10 / 释放 12」会被当成算错。 */
export const RESTORE_QUANTITY_NOTE =
  '还回可用池 = 各分配本次释放量之和（货物的量）；需求释放 = 预占口径上放弃的量。'
  + '需求没被全部分配时两者不相等：没圈到 bucket 的部分从未占过可用量，不该算进恢复量。';

/** 过期规则说明（接口回传的 ruleNote 为准，这份是接口不可用时的兜底）。 */
export const EXPIRY_RULE_NOTE =
  '过期判据 = 预占中（HELD）且已过 expires_at；已承诺（COMMITTED）永不自动释放，'
  + '名下还有承诺的预占整笔跳过批量回收。';

/** 可用量口径归属说明。 */
export const AVAILABLE_OWNER_NOTE =
  '本模块把分配移出占额集合并记录恢复量；可用量（= 在手 − 预占）的口径与重算由 7.1.1.3 可用量计算裁决，本模块不重复实现。';