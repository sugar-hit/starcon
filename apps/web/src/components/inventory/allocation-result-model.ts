import type {
  AllocationReconciliationReport,
  AllocationResultCapabilities,
  AllocationResultPage,
  AllocationResultRunPage,
  AllocationWritebackInput,
  AllocationWritebackPage,
  AllocationWritebackSaved,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.8.2.1 分摊结果查看前端模型。
 *
 * <p>蓝图：「展示某 SKU/仓在各店铺的分摊量、已用、剩余、回写平台的数值，以及回写失败或延迟导致的实际风险敞口。输入：分摊结果；平台回写状态（4.3.2.1）。输出：分摊明细表 + 平台回写一致性核对。」
 *
 * <p>界面上最容易出错的几处，这个模块都显式处理了：
 *
 * <ul>
 *   <li><b>「尚未回填」必须长得和「0」不一样</b>。平台回写状态在 channel 服务，
 *       本地没收到时后端给的是 {@code UNKNOWN} + 一堆 null。界面把这些渲染成
 *       「尚未回填」并单独标色，**绝不**渲染成 0 —— 0 意味着「平台上一件都卖不出去」，
 *       而 UNKNOWN 意味着「我们不知道，该去查同步」，两者的处置完全相反。
 *   <li><b>敞口不是一个提示文案，是一个数</b>。缓冲不足时显示的是
 *       「超出 N 件，缓冲只有 M 件」，而不只是一句「有风险」。
 *   <li><b>已用/剩余/敞口的可信度要一起显示</b>。汇总里同时给「已知的行数」与
 *       「总行数」，让人知道这一列有多少是猜的。
 *   <li><b>跨服务边界写在页面上</b>：回写状态经事件 / API 进入本地投影，
 *       本服务不读 channel 的表。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export interface ResultListParams {
  runId?: string | undefined;
  skuId?: string | undefined;
  warehouseId?: string | undefined;
  referenceId?: string | undefined;
  method?: string | undefined;
  allocatedFrom?: string | undefined;
  allocatedTo?: string | undefined;
  scope?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface RunListParams {
  skuId?: string | undefined;
  method?: string | undefined;
  allocatedFrom?: string | undefined;
  allocatedTo?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface WritebackListParams {
  runId?: string | undefined;
  referenceId?: string | undefined;
  warehouseId?: string | undefined;
  state?: string | undefined;
  sourceKind?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 能力清单：跨服务边界、UNKNOWN 与 0 的区别、三个缓冲的区分、敞口算式、仓维度来源。 */
export function fetchResultCapabilities(): Promise<AllocationResultCapabilities> {
  return api<AllocationResultCapabilities>('warehouse/inventory/allocation-results/capabilities');
}

/** 分摊明细表（真分页）。 */
export function fetchResults(params: ResultListParams): Promise<AllocationResultPage> {
  return api<AllocationResultPage>(
    `warehouse/inventory/allocation-results/results${query(params)}`,
  );
}

/** 分摊轮次列表（真分页）。 */
export function fetchRuns(params: RunListParams): Promise<AllocationResultRunPage> {
  return api<AllocationResultRunPage>(
    `warehouse/inventory/allocation-results/runs${query(params)}`,
  );
}

/**
 * 平台回写一致性核对：总量口径 + 逐轮结论。
 *
 * <p>与分摊明细表读的是**同一份**判定，所以两处结论永远一致 ——
 * 不存在「表里说一致、核对说超卖」这种事。
 */
export function fetchReconciliation(
  params: ResultListParams,
): Promise<AllocationReconciliationReport> {
  return api<AllocationReconciliationReport>(
    `warehouse/inventory/allocation-results/reconciliation${query(params)}`,
  );
}

/** 回写状态观测列表（真分页，append-only 事实的时间线）。 */
export function fetchWritebacks(params: WritebackListParams): Promise<AllocationWritebackPage> {
  return api<AllocationWritebackPage>(
    `warehouse/inventory/allocation-results/writebacks${query(params)}`,
  );
}

/**
 * 登记一次平台回写状态的观测。
 *
 * <p>只有 update 权限才能登记：它会直接改变「平台挂着的数」这个事实，
 * 进而改变风险敞口的结论。
 */
export function recordWriteback(
  input: AllocationWritebackInput,
): Promise<AllocationWritebackSaved> {
  return api<AllocationWritebackSaved>(
    'warehouse/inventory/allocation-results/writebacks',
    'POST',
    input,
  );
}

function query(params: object): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 展示

/**
 * codegen 约定：只要 schema 里有任何一个可空字段，整个类型别名就会生成成 `T | null`，
 * 所以列表元素也要先过这道关。
 *
 * <p>刻意写成**类型谓词**：写成 `<T>(v: T | null) => T | null` 时
 * `Array.filter(notNull)` 不会收窄元素类型。
 */
export function notNull<T>(v: T | null | undefined): v is T {
  return v !== null && v !== undefined;
}

/**
 * 数量口径：DB 是 numeric(19,6)，而**件数必须是整件**，所以这里不补小数位。
 *
 * <p>null 显示成「—」而不是 0：null 的含义是「本地没有这个数」，
 * 渲染成 0 就是把「不知道」说成了「一件都没有」。
 */
export function qty(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (Number.isNaN(n)) return '—';
  return n.toLocaleString('zh-CN');
}

/** 比例：库里存 0.2，界面显示 20%。 */
export function percent(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (Number.isNaN(n)) return '—';
  return `${(n * 100).toLocaleString('zh-CN', { maximumFractionDigits: 4 })}%`;
}

/** 平台回写状态。UNKNOWN 是本模块最该被看见的一个取值。 */
export const writebackStateLabels: Record<string, string> = {
  UNKNOWN: '尚未回填',
  SUCCEEDED: '已回写成功',
  FAILED: '回写失败',
  PENDING: '已发出未确认',
};

/** 一致性判定。 */
export const consistencyStateLabels: Record<string, string> = {
  UNKNOWN: '无法核对',
  CONSISTENT: '一致',
  OVER_ADVERTISED: '平台挂多了',
  UNDER_ADVERTISED: '平台挂少了',
};

/** 敞口判定。BEYOND_BUFFER 是蓝图那句「缓冲不足会超卖」的落点。 */
export const exposureStateLabels: Record<string, string> = {
  UNKNOWN: '无法计算',
  NO_EXPOSURE: '无敞口',
  WITHIN_BUFFER: '敞口在缓冲内',
  BEYOND_BUFFER: '缓冲不足，会超卖',
};

/** 观测来源。 */
export const sourceKindLabels: Record<string, string> = {
  EVENT: '事件推送',
  API: '授权 API 推送',
  MANUAL: '显式录入',
};

/** 筛选口径。 */
export const scopeLabels: Record<string, string> = {
  ALL: '全部',
  EXPOSURE: '只看有敞口的',
  BEYOND_BUFFER: '只看缓冲不足的',
  INCONSISTENT: '只看回写不一致的',
  UNKNOWN: '只看回写状态未知的',
};

/** 分摊方法。 */
export const methodLabels: Record<string, string> = {
  PROPORTIONAL_SALES: '按销量占比比例分配',
  FIXED_PRIORITY: '按固定优先级先到先得',
  SHOP_TYPE_PRIORITY: '按店铺类型优先级',
};

/** 店铺经营模式。 */
export const operatingModeLabels: Record<string, string> = {
  SELF_FULFILLED: '自营',
  FULL_CONSIGNMENT: '全托管',
  SEMI_CONSIGNMENT: '半托管',
  OVERSEAS_WAREHOUSE: '海外仓',
  LOCAL: '本地',
};

/** 7.8.1.1 的分摊结论。 */
export const outcomeLabels: Record<string, string> = {
  OK: '正常',
  CAP_REACHED: '触到份额上限',
  DEMAND_EXHAUSTED: '货被前面的店分完',
  NO_SALES: '销量为 0',
  DISABLED: '成员已停用',
};

/**
 * 回写状态的色调。
 *
 * <p>UNKNOWN 用中性色而不是红色：它不是「出错了」，是「我们还不知道」——
 * 把它渲染成红色会让人以为系统坏了，而它其实是在提示「该去接同步」。
 */
export function writebackTone(state: string | null | undefined): string {
  switch (state) {
    case 'SUCCEEDED':
      return 'text-emerald-600 dark:text-emerald-400';
    case 'FAILED':
      return 'text-rose-600 dark:text-rose-400';
    case 'PENDING':
      return 'text-amber-600 dark:text-amber-400';
    default:
      return 'text-muted-foreground';
  }
}

/** 一致性的色调。 */
export function consistencyTone(state: string | null | undefined): string {
  switch (state) {
    case 'CONSISTENT':
      return 'text-emerald-600 dark:text-emerald-400';
    case 'OVER_ADVERTISED':
      return 'text-rose-600 dark:text-rose-400';
    case 'UNDER_ADVERTISED':
      return 'text-amber-600 dark:text-amber-400';
    default:
      return 'text-muted-foreground';
  }
}

/** 敞口的色调。只有「缓冲不足」才需要被看见。 */
export function exposureTone(state: string | null | undefined): string {
  switch (state) {
    case 'BEYOND_BUFFER':
      return 'text-rose-600 dark:text-rose-400';
    case 'WITHIN_BUFFER':
      return 'text-amber-600 dark:text-amber-400';
    case 'NO_EXPOSURE':
      return 'text-emerald-600 dark:text-emerald-400';
    default:
      return 'text-muted-foreground';
  }
}

/** 一行是不是「回写状态未知」。 */
export function isUnknown(row: { writebackState: string | null }): boolean {
  return row.writebackState === 'UNKNOWN';
}

/**
 * 一行敞口的一句话说明。
 *
 * <p>刻意把「超了多少」与「缓冲有多少」放在一起说：光说「有敞口」没法处置，
 * 而「超出 25 件、缓冲只有 6 件」直接给出了「缓冲不够，要调缓冲或减分摊量」。
 */
export function exposureText(row: {
  exposureQuantity?: number | string | null;
  bufferQuantity?: number | string | null;
  exposureState?: string | null;
}): string {
  if (row.exposureState === 'UNKNOWN') {
    return '无法计算：本地没有回写状态或没有可售量口径。';
  }
  if (row.exposureState === 'NO_EXPOSURE') {
    return '平台挂着的数没有超过我方剩余，同步窗口内不会因此超卖。';
  }
  const exposure = qty(row.exposureQuantity);
  if (row.bufferQuantity === null || row.bufferQuantity === undefined) {
    return `敞口 ${exposure} 件，且该店没有配置平台回写缓冲（缓冲按 0 计）。`;
  }
  const buffer = qty(row.bufferQuantity);
  if (row.exposureState === 'BEYOND_BUFFER') {
    return `敞口 ${exposure} 件 > 缓冲 ${buffer} 件：缓冲不足，同步窗口内会超卖。`;
  }
  return `敞口 ${exposure} 件 ≤ 缓冲 ${buffer} 件：缓冲兜得住。`;
}

/** 时刻文案。null 显示成「—」。 */
export function timeText(v: string | null | undefined): string {
  if (!v) return '—';
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('zh-CN');
}

/**
 * 汇总文案。
 *
 * <p>刻意把「已知的行数」和「总行数」放在一起：只给一个「已用合计」，
 * 看的人没法知道这个数覆盖了多少家店 —— 而没覆盖的那些家店恰恰是最需要看的。
 */
export function summaryText(summary: {
  rowCount: number;
  consumedKnownCount: number;
  platformKnownCount: number;
  unknownStateCount: number;
  exposureComputableCount: number;
  exposureTotal: number | string | null;
  beyondBufferCount: number;
}): string {
  return [
    `共 ${summary.rowCount} 行分摊`,
    `已用已知 ${summary.consumedKnownCount}/${summary.rowCount} 行`,
    `回写数已知 ${summary.platformKnownCount}/${summary.rowCount} 行`,
    `尚未回填 ${summary.unknownStateCount} 行`,
    `可算敞口 ${summary.exposureComputableCount} 行，合计 ${qty(summary.exposureTotal)} 件`,
    `缓冲不足 ${summary.beyondBufferCount} 行`,
  ].join('；');
}

/** 统一的错误文案：把 ApiError 的状态码与后端给的原因都带出来。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    return e.status ? `请求失败（${e.status}）：${e.message}` : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}
