import { api } from '../inventory/api';

/**
 * 4.3.1.2 批量发布 前端模型。
 *
 * 蓝图：「批量刊登队列：按规则筛选待发布商品（如'SPU 状态=在售 且 未在任何店铺发布'），生成任务
 * 批次，支持暂停、调整顺序、批量重试。输入：筛选规则与数量上限；发布策略；用户确认。输出：批次
 * 任务列表 + 总体进度 + 失败明细（可跳转 4.3.1.3）。关系：上游 = 4.1.1.1、4.2.1.2；下游 =
 * 4.3.1.3。**批量任务必须限流**：按平台各自的 API 配额排队。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **限流是数据不是装饰**。`PublishRateLimitRow` 是服务端返回的真实配置，页面上逐平台列出
 *    「在途 / 窗口 / 上限」，派发结果按**限流行**（`DispatchLaneReport[]`）展示 —— 不是把
 *    Amazon 与 TikTok 合并成一个「共派发 N 条」的假账。没配限流的平台显示「未配置限流，本轮不派」，
 *    **不显示成一个看起来正常的数字**。
 *
 * 2. **在途不是成功**。`DISPATCHED`（在途）与 `SUCCEEDED`（成功）是两个状态，徽章文案刻意不同。
 *    本模块只负责队列编排，实际平台调用由 4.3.1.3 回报 —— 页面绝不在途时就显示「已发布」。
 *
 * 3. **重排真的改次序**。`reorderTasks()` 提交的是**完整的新次序**（taskIds），不是让用户拖拽
 *    后前端自己猜位置。重排后必须重新拉取任务列表，让用户看见次序真的变了。
 *
 * 4. **重试只重试失败项**。界面上没有「全部重试」的按钮 —— 只有 `retryFailed()`，它**不接受**
 *    已成功的 id（传了会被服务端逐条拒绝并回显原因）。成功的项在界面上也不提供任何重试入口。
 */

export interface PublishCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface PublishCapabilities {
  dimensions: PublishCapabilityDimension[];
  batchStates: string[];
  taskStates: string[];
  rateLimitNote: string;
  downstreamNote: string;
  relationNote: string;
}

export interface PublishRateLimitRow {
  id: string;
  platformId: string;
  platformCode: string;
  platformName: string;
  shopId: string | null;
  shopCode: string | null;
  maxInFlight: number;
  requestsPerWindow: number;
  windowSeconds: number;
  enabled: boolean;
  currentInFlight: number;
  currentWindowCount: number;
  updatedAt: string;
}

/** 建批次的筛选条件（形状照抄 4.1.1.1 的筛选参数）。 */
export interface PublishFilters {
  shopCode: string;
  status: string;
  category: string;
  search: string;
  hasPendingDraft: boolean;
  excludeAlreadyPublished: boolean;
}

export interface CreatePublishBatchRequest {
  shopCode?: string;
  status?: string;
  category?: string;
  search?: string;
  hasPendingDraft?: boolean;
  excludeAlreadyPublished?: boolean;
  limit?: number;
}

export interface PublishBatchRow {
  batchId: string;
  state: string;
  filterRule: string;
  strategy: string;
  limitCount: number;
  totalCount: number;
  pendingCount: number;
  dispatchedCount: number;
  succeededCount: number;
  failedCount: number;
  progressPercent: number;
  actorId: string | null;
  note: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface PublishTaskRow {
  taskId: string;
  batchId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  rateLimitId: string | null;
  position: number;
  state: string;
  attempts: number;
  failureReason: string | null;
  dispatchedAt: string | null;
  completedAt: string | null;
}

export interface DispatchLaneReport {
  platformCode: string;
  shopCode: string | null;
  rateLimitId: string | null;
  maxInFlight: number;
  inFlightBefore: number;
  requestsPerWindow: number;
  windowSeconds: number;
  windowCountBefore: number;
  dispatched: number;
  skippedByRateLimit: number;
  note: string;
}

export interface DispatchResponse {
  batchId: string;
  batchState: string;
  totalDispatched: number;
  totalSkipped: number;
  lanes: DispatchLaneReport[];
  batch: PublishBatchRow;
}

export interface BatchActionResult {
  batchId: string;
  state: string;
  changedTaskIds: string[];
  rejected: string[];
  batch: PublishBatchRow;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<PublishCapabilities> {
  return api<PublishCapabilities>('channel/listing-publish/capabilities');
}

export async function fetchRateLimits(): Promise<PublishRateLimitRow[]> {
  return api<PublishRateLimitRow[]>('channel/listing-publish/rate-limits');
}

export async function saveRateLimit(
  body: {
    platformId: string;
    shopId?: string | null;
    maxInFlight?: number;
    requestsPerWindow?: number;
    windowSeconds?: number;
    enabled?: boolean;
  },
): Promise<PublishRateLimitRow> {
  return api<PublishRateLimitRow>('channel/listing-publish/rate-limits', 'POST', body);
}

export async function fetchBatches(): Promise<PublishBatchRow[]> {
  return api<PublishBatchRow[]>('channel/listing-publish/batches');
}

export async function createBatch(
  body: CreatePublishBatchRequest,
): Promise<PublishBatchRow> {
  return api<PublishBatchRow>('channel/listing-publish/batches', 'POST', body);
}

export async function fetchBatch(batchId: string): Promise<PublishBatchRow> {
  return api<PublishBatchRow>(`channel/listing-publish/batches/${batchId}`);
}

export async function fetchTasks(
  batchId: string,
  state?: string,
): Promise<PublishTaskRow[]> {
  const query = state ? `?state=${encodeURIComponent(state)}` : '';
  return api<PublishTaskRow[]>(`channel/listing-publish/batches/${batchId}/tasks${query}`);
}

/** 🔴 一轮派发：服务端按**各平台自己的配额**排队，这里只负责把逐限流行的结果拿回来。 */
export async function dispatchBatch(batchId: string): Promise<DispatchResponse> {
  return api<DispatchResponse>(`channel/listing-publish/batches/${batchId}/dispatch`, 'POST');
}

export async function pauseBatch(batchId: string, note?: string): Promise<PublishBatchRow> {
  return api<PublishBatchRow>(`channel/listing-publish/batches/${batchId}/pause`, 'POST', {
    note: note ?? '',
  });
}

export async function resumeBatch(batchId: string): Promise<PublishBatchRow> {
  return api<PublishBatchRow>(`channel/listing-publish/batches/${batchId}/resume`, 'POST');
}

export async function cancelBatch(batchId: string, note?: string): Promise<PublishBatchRow> {
  return api<PublishBatchRow>(`channel/listing-publish/batches/${batchId}/cancel`, 'POST', {
    note: note ?? '',
  });
}

/**
 * 🔴 重排：提交**完整的新执行次序**。
 *
 * 顺序即执行次序 —— 服务端按这个次序重写 position，派发取任务就是按 position 升序，
 * 所以次序真的变。前端不自己算位置，也不让用户填「新的 position 数值」。
 */
export async function reorderTasks(
  batchId: string,
  taskIds: string[],
  note?: string,
): Promise<BatchActionResult> {
  return api<BatchActionResult>(`channel/listing-publish/batches/${batchId}/reorder`, 'POST', {
    taskIds,
    note: note ?? '',
  });
}

/**
 * 🔴 批量重试：只重试**失败**项。
 *
 * 形参刻意**不接受**任意 id 列表 —— 页面上没有「全部重试」入口。传了已成功的 id 会被服务端
 * 逐条拒绝并回显原因（`rejected`），前端原样展示，不静默吞掉。
 */
export async function retryFailed(batchId: string, note?: string): Promise<BatchActionResult> {
  return api<BatchActionResult>(`channel/listing-publish/batches/${batchId}/retry`, 'POST', {
    note: note ?? '',
  });
}

// ================================================================ 纯函数（可单测）

export function emptyFilters(): PublishFilters {
  return {
    shopCode: '',
    status: '',
    category: '',
    search: '',
    hasPendingDraft: false,
    excludeAlreadyPublished: false,
  };
}

export function activeFilterCount(filters: PublishFilters): number {
  let count = 0;
  if (filters.shopCode) count += 1;
  if (filters.status) count += 1;
  if (filters.category) count += 1;
  if (filters.search) count += 1;
  if (filters.hasPendingDraft) count += 1;
  if (filters.excludeAlreadyPublished) count += 1;
  return count;
}

/**
 * 筛选条件 → 建批次请求体。空值**不下发**（交给服务端走默认），
 * 两个布尔开关是「显式语义」：只有勾了才下发 true，关着就不传（而不是下发 false 去覆盖服务端默认）。
 */
export function buildCreateRequest(
  filters: PublishFilters,
  limit: number,
): CreatePublishBatchRequest {
  const body: CreatePublishBatchRequest = { limit };
  if (filters.shopCode) body.shopCode = filters.shopCode;
  if (filters.status) body.status = filters.status;
  if (filters.category) body.category = filters.category;
  if (filters.search) body.search = filters.search;
  if (filters.hasPendingDraft) body.hasPendingDraft = true;
  if (filters.excludeAlreadyPublished) body.excludeAlreadyPublished = true;
  return body;
}

/** 批次状态文案。RUNNING/PAUSED/COMPLETED/CANCELLED。 */
export function batchStateLabel(state: string): string {
  switch (state) {
    case 'RUNNING':
      return '进行中';
    case 'PAUSED':
      return '已暂停';
    case 'COMPLETED':
      return '已完成';
    case 'CANCELLED':
      return '已中止';
    default:
      return state;
  }
}

/**
 * 🔴 任务状态文案 —— **在途与成功刻意不同**。
 *
 * 「在途」是"已发出、等平台回报"，不是"已发布"。把它显示成「成功」会让运营以为商品已经上架，
 * 而真实情况是这一步由 4.3.1.3 回报。
 */
export function taskStateLabel(state: string): string {
  switch (state) {
    case 'PENDING':
      return '待派发';
    case 'DISPATCHED':
      return '在途（等平台回报）';
    case 'SUCCEEDED':
      return '已成功';
    case 'FAILED':
      return '失败';
    default:
      return state;
  }
}

/** 任务状态对应的徽章色调。 */
export function taskStateTone(state: string): 'neutral' | 'info' | 'success' | 'danger' {
  switch (state) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'DISPATCHED':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 进度摘要：五个计数 + 百分比，一句话。 */
export function progressSummary(batch: PublishBatchRow): string {
  return `共 ${batch.totalCount} 条 · 待派发 ${batch.pendingCount} · 在途 ${batch.dispatchedCount} · 成功 ${batch.succeededCount} · 失败 ${batch.failedCount}（${batch.progressPercent}%）`;
}

/**
 * 🔴 派发结果摘要：**逐限流行**列出，不合并成一个总数。
 *
 * 返回空数组（没派发任何东西）时给出明确说法，而不是空字符串 —— 「没派」和「不知道」必须分开。
 */
export function dispatchSummary(dispatch: DispatchResponse): string[] {
  if (dispatch.lanes.length === 0) {
    return dispatch.batchState === 'PAUSED'
      ? ['批次已暂停，本轮没有派发任何任务（已在途的任务不受影响）']
      : ['本轮没有可派发的任务'];
  }
  return dispatch.lanes.map((lane) => {
    const scope = lane.shopCode ? `${lane.platformCode} / ${lane.shopCode}` : `${lane.platformCode}（平台级）`;
    if (!lane.rateLimitId) {
      return `${scope}：未配置限流，本轮不派发（${lane.skippedByRateLimit} 条待派）`;
    }
    const parts = [
      `${scope}：本轮派发 ${lane.dispatched} 条`,
      `在途 ${lane.inFlightBefore}/${lane.maxInFlight}`,
      `窗口 ${lane.windowCountBefore}/${lane.requestsPerWindow}（${lane.windowSeconds}s）`,
    ];
    if (lane.skippedByRateLimit > 0) {
      parts.push(`因限流跳过 ${lane.skippedByRateLimit} 条`);
    }
    if (lane.note) parts.push(lane.note);
    return parts.join(' · ');
  });
}

/** 批量动作结果摘要：哪些真被改了、哪些被拒（被拒原因原样展示，不吞掉）。 */
export function actionSummary(result: BatchActionResult, verb: string): string {
  const changed = result.changedTaskIds.length;
  const lines = [`${verb}了 ${changed} 条`];
  if (result.rejected.length > 0) {
    lines.push(`${result.rejected.length} 条被拒绝：${result.rejected.join('；')}`);
  }
  return lines.join('；');
}

/** 空态文案：区分「还没有批次」与「筛选没命中」，后者告诉用户先建批次。 */
export function emptyBatchesMessage(): string {
  return '还没有批量发布批次。用上方筛选条件选出待发布商品，点「生成批次」开始。';
}

/** 失败明细文案：可跳转 4.3.1.3。taskId 是跳转参数。 */
export function failureHint(): string {
  return '失败明细可跳转 4.3.1.3（发布任务）查看单条任务的步骤与错误；该模块尚未交付，此处先展示后端记录的原因。';
}

/** 未交付维度的说明（SPU 状态筛选、平台实际调用）。前端据此显示「未接入」，不假装有。 */
export function unavailableDimensions(caps: PublishCapabilities): PublishCapabilityDimension[] {
  return caps.dimensions.filter((d) => !d.available);
}
