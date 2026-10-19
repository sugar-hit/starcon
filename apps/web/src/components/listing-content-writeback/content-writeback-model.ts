import { api } from '../inventory/api';

/**
 * 4.3.2.1 内容回写 前端模型。
 *
 * 蓝图：「把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、改 A+），支持按变更类型
 * 触发或批量触发，并记录回写前后快照。输入：内容变更事件；用户批量回写操作；目标 listing 集合。
 * 输出：回写任务（经 02 队列）+ 变更历史。关系：上游 = 3 商品主数据变更、4.2.1.2 批量编辑；
 * 下游 = 2.5.1.1。**内容回写不触碰价格与库存**（那是 05/07 的事），三条回写通道独立失败独立重试。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **页面不给「改价 / 改库存」入口**。`CHANGE_TYPE_OPTIONS` 只有五个内容字段，
 *    `REJECTED_CHANGE_TYPES` 里那几项只出现在「归属说明」里、不出现在可选列表里。页面上放一个
 *    必然被服务端 422 的按钮，比不放更糟 —— 用户会以为功能已交付。
 *
 * 2. **「跳过」不并进「成功」**。`SKIPPED_NO_CHANGE`（改后与线上逐字相同，未派发）有自己的徽章与
 *    计数。把它算成成功 = 谎报「已推送」；把它算成失败 = 让运营以为出了问题。两种都比真实更糟。
 *
 * 3. **改前值缺失就说缺失**。`beforeSource === 'MISSING'` 时显示「平台侧无基线」，
 *    **不显示**改后值假装那就是线上现状（那会让「平台上现在是什么样」永远答不上来）。
 *
 * 4. **在途 ≠ 成功**。`DISPATCHED`（在途，等 4.3.2.2 回报）与 `SUCCEEDED` 分开，
 *    页面绝不在在途时就显示「已回写」。
 *
 * 5. **重试只重试失败项**。界面上没有「全部重试」入口；`retryFailed()` 只带失败项 id，
 *    被服务端拒绝的项原样回显在 `rejected` 里。
 */

export interface ContentWritebackCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ContentWritebackChannel {
  channel: string;
  label: string;
  ownerModule: string;
  handledHere: boolean;
  note: string;
}

export interface ContentWritebackCapabilities {
  dimensions: ContentWritebackCapabilityDimension[];
  changeTypes: string[];
  rejectedChangeTypes: string[];
  channels: ContentWritebackChannel[];
  triggerStates: string[];
  taskStates: string[];
  snapshotNote: string;
  relationNote: string;
}

/**
 * 🔴 可选的变更类型 —— **只有内容通道的五个字段**。
 *
 * 与后端 `ContentWritebackModels.CHANGE_TYPES` 逐字一致。价格 / 库存不在这里，
 * 也不在 `REJECTED_CHANGE_TYPES` 的可选项里：它们只在归属说明里被点名。
 */
export const CHANGE_TYPE_OPTIONS = [
  { value: 'TITLE', label: '标题', hint: 'listing 标题 / 平台商品标题' },
  { value: 'BULLETS', label: '卖点', hint: 'bullet points（描述的卖点短语段）' },
  { value: 'IMAGES', label: '图片', hint: '主图与附图（整组替换语义）' },
  { value: 'DESCRIPTION', label: '描述', hint: '详情长文本；需显式给值，见能力表' },
  { value: 'A_PLUS', label: 'A+', hint: 'A+ 模块；需显式给值，见能力表' },
] as const;

export type ChangeType = (typeof CHANGE_TYPE_OPTIONS)[number]['value'];

export interface CreateContentWritebackRequest {
  changeTypes: string[];
  listingIds?: string[];
  shopCode?: string;
  status?: string;
  overrides?: Record<string, string>;
  language?: string;
  limit?: number;
  note?: string;
}

export interface ContentWritebackRow {
  writebackId: string;
  triggerKind: string;
  changeTypes: string[];
  listingIds: string[];
  overrides: string;
  language: string;
  state: string;
  totalCount: number;
  pendingCount: number;
  skippedCount: number;
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

export interface ContentWritebackTaskRow {
  taskId: string;
  writebackId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  language: string;
  changeTypes: string[];
  draftId: string | null;
  snapshotId: string | null;
  state: string;
  attempts: number;
  operationId: string | null;
  failureReason: string;
  dispatchedAt: string | null;
  completedAt: string | null;
}

export interface ContentWritebackChangeRow {
  changeId: string;
  taskId: string;
  listingId: string;
  field: string;
  beforeValue: string;
  afterValue: string;
  changed: boolean;
  outcome: string;
  beforeSource: string;
  afterSource: string;
  reason: string;
}

export interface ContentWritebackLaneReport {
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

export interface ContentWritebackDispatchResponse {
  writebackId: string;
  triggerState: string;
  totalDispatched: number;
  totalSkippedByRateLimit: number;
  queuedOperationIds: string[];
  lanes: ContentWritebackLaneReport[];
  writeback: ContentWritebackRow;
}

export interface ContentWritebackActionResult {
  writebackId: string;
  state: string;
  changedTaskIds: string[];
  rejected: string[];
  writeback: ContentWritebackRow;
}

export interface ListingContentHistoryEntry {
  writebackId: string;
  triggerState: string;
  createdAt: string;
  taskState: string;
  operationId: string | null;
  changes: ContentWritebackChangeRow[];
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<ContentWritebackCapabilities> {
  return api<ContentWritebackCapabilities>('channel/listing-content-writeback/capabilities');
}

export async function fetchWritebacks(): Promise<ContentWritebackRow[]> {
  return api<ContentWritebackRow[]>('channel/listing-content-writeback/writebacks');
}

export async function createWriteback(
  body: CreateContentWritebackRequest,
): Promise<ContentWritebackRow> {
  return api<ContentWritebackRow>('channel/listing-content-writeback/writebacks', 'POST', body);
}

export async function fetchWriteback(writebackId: string): Promise<ContentWritebackRow> {
  return api<ContentWritebackRow>(`channel/listing-content-writeback/writebacks/${writebackId}`);
}

export async function fetchTasks(
  writebackId: string,
  state?: string,
): Promise<ContentWritebackTaskRow[]> {
  const query = state ? `?state=${encodeURIComponent(state)}` : '';
  return api<ContentWritebackTaskRow[]>(
    `channel/listing-content-writeback/writebacks/${writebackId}/tasks${query}`,
  );
}

/** 🔴 一轮派发：服务端按**各平台自己的配额**排队并写 02 队列，这里只负责把结果拿回来。 */
export async function dispatchWriteback(
  writebackId: string,
): Promise<ContentWritebackDispatchResponse> {
  return api<ContentWritebackDispatchResponse>(
    `channel/listing-content-writeback/writebacks/${writebackId}/dispatch`,
    'POST',
  );
}

/** 🔴 只重试**失败**项：形参只接受 FAILED 任务 id（不给 = 本次全部失败项）。 */
export async function retryFailed(
  writebackId: string,
  taskIds?: string[],
  note?: string,
): Promise<ContentWritebackActionResult> {
  return api<ContentWritebackActionResult>(
    `channel/listing-content-writeback/writebacks/${writebackId}/retry`,
    'POST',
    { taskIds: taskIds ?? null, note: note ?? '' },
  );
}

export async function cancelWriteback(
  writebackId: string,
  note?: string,
): Promise<ContentWritebackRow> {
  return api<ContentWritebackRow>(
    `channel/listing-content-writeback/writebacks/${writebackId}/cancel`,
    'POST',
    { note: note ?? '' },
  );
}

export async function fetchChanges(taskId: string): Promise<ContentWritebackChangeRow[]> {
  return api<ContentWritebackChangeRow[]>(
    `channel/listing-content-writeback/tasks/${taskId}/changes`,
  );
}

export async function fetchListingHistory(
  listingId: string,
): Promise<ListingContentHistoryEntry[]> {
  return api<ListingContentHistoryEntry[]>(
    `channel/listing-content-writeback/listings/${listingId}/content-history`,
  );
}

// ================================================================ 纯函数（可单测）

/**
 * 🔴 请求体 → 服务端载荷。
 *
 * 三件事在这里被挡住：
 *  * 空值不下发（交给服务端默认），而不是下发空串去覆盖；
 *  * 没有选任何变更类型时返回 `null`（调用方据此禁用提交按钮）——
 *    一次「不改任何字段」的回写请求不是回写请求；
 *  * `overrides` 只保留**内容通道的键**：即便将来有别的调用方塞了 price 进来，
 *    前端这一层也不会把它传上去（服务端还会再拒一道）。
 */
export function buildCreateRequest(draft: {
  changeTypes: string[];
  listingIds: string[];
  shopCode: string;
  status: string;
  overrides: Record<string, string>;
  limit: number;
  note: string;
}): CreateContentWritebackRequest | null {
  const types = draft.changeTypes.map((t) => t.trim().toUpperCase()).filter(Boolean);
  const allowed = new Set(CHANGE_TYPE_OPTIONS.map((o) => o.value));
  const valid = types.filter((t) => allowed.has(t as ChangeType));
  if (valid.length === 0) return null;

  const body: CreateContentWritebackRequest = { changeTypes: valid, limit: draft.limit };
  if (draft.listingIds.length > 0) body.listingIds = draft.listingIds;
  if (draft.shopCode) body.shopCode = draft.shopCode;
  if (draft.status) body.status = draft.status;
  const overrides: Record<string, string> = {};
  for (const [key, value] of Object.entries(draft.overrides)) {
    const upper = key.trim().toUpperCase();
    if (allowed.has(upper as ChangeType) && value.trim() !== '') overrides[upper] = value;
  }
  if (Object.keys(overrides).length > 0) body.overrides = overrides;
  if (draft.note) body.note = draft.note;
  return body;
}

/** 触发状态文案。RUNNING / COMPLETED / CANCELLED。 */
export function triggerStateLabel(state: string): string {
  switch (state) {
    case 'RUNNING':
      return '进行中';
    case 'COMPLETED':
      return '已完成';
    case 'CANCELLED':
      return '已中止';
    default:
      return state;
  }
}

/**
 * 🔴 任务状态文案 —— **在途、跳过与成功三态刻意不同**。
 *
 * `SKIPPED_NO_CHANGE` 是「内容与线上逐字相同，因此从未派发」。把它显示成「已回写」是谎报，
 * 显示成「失败」会让运营以为出了问题 —— 它两个都不是。
 */
export function taskStateLabel(state: string): string {
  switch (state) {
    case 'PENDING':
      return '待派发';
    case 'SKIPPED_NO_CHANGE':
      return '已跳过（无变更）';
    case 'DISPATCHED':
      return '在途（等平台回报）';
    case 'SUCCEEDED':
      return '已回写';
    case 'FAILED':
      return '失败';
    default:
      return state;
  }
}

/** 任务状态对应的徽章色调。跳过用 warning（既不是成功也不是失败）。 */
export function taskStateTone(
  state: string,
): 'neutral' | 'info' | 'success' | 'danger' | 'warning' {
  switch (state) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'DISPATCHED':
      return 'info';
    case 'SKIPPED_NO_CHANGE':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 进度摘要：六个计数 + 百分比。跳过单独成句，不并进成功。 */
export function progressSummary(writeback: ContentWritebackRow): string {
  return (
    `共 ${writeback.totalCount} 条 · 待派发 ${writeback.pendingCount} · ` +
    `在途 ${writeback.dispatchedCount} · 已回写 ${writeback.succeededCount} · ` +
    `失败 ${writeback.failedCount} · 已跳过 ${writeback.skippedCount}（${writeback.progressPercent}%）`
  );
}

/**
 * 🔴 派发结果摘要：**逐限流行**列出，不合并成一个总数。
 *
 * 另附队列凭据：`queuedOperationIds` 是本轮写进 02 队列的 platform_operation id ——
 * 「经队列」是可查事实，不是一句描述。
 */
export function dispatchSummary(dispatch: ContentWritebackDispatchResponse): string[] {
  const queued = dispatch.queuedOperationIds.length;
  const lines: string[] = [];
  if (dispatch.lanes.length === 0) {
    lines.push(
      dispatch.triggerState === 'CANCELLED'
        ? '本次回写已中止，本轮没有派发任何任务（已在途的不受影响）'
        : '本轮没有可派发的任务',
    );
  }
  for (const lane of dispatch.lanes) {
    const scope = lane.shopCode
      ? `${lane.platformCode} / ${lane.shopCode}`
      : `${lane.platformCode}（平台级）`;
    if (!lane.rateLimitId) {
      lines.push(`${scope}：未配置限流，本轮不派发（${lane.skippedByRateLimit} 条待派）`);
      continue;
    }
    const parts = [
      `${scope}：本轮派发 ${lane.dispatched} 条`,
      `在途 ${lane.inFlightBefore}/${lane.maxInFlight}`,
      `窗口 ${lane.windowCountBefore}/${lane.requestsPerWindow}（${lane.windowSeconds}s）`,
    ];
    if (lane.skippedByRateLimit > 0) parts.push(`因限流跳过 ${lane.skippedByRateLimit} 条`);
    if (lane.note) parts.push(lane.note);
    lines.push(parts.join(' · '));
  }
  if (queued > 0) {
    lines.push(`已写入 02 队列 ${queued} 条（operationId 可在回写任务列表核验）`);
  }
  return lines;
}

/** 动作结果摘要：哪些真被改了、哪些被拒（被拒原因原样展示，不吞掉）。 */
export function actionSummary(result: ContentWritebackActionResult, verb: string): string {
  const parts = [`${verb}了 ${result.changedTaskIds.length} 条`];
  if (result.rejected.length > 0) {
    parts.push(`${result.rejected.length} 条被拒绝：${result.rejected.join('；')}`);
  }
  return parts.join('；');
}

/** 空态文案：区分「还没有回写」与「筛选没命中」。 */
export function emptyWritebacksMessage(): string {
  return '还没有内容回写记录。选好变更类型与目标 listing，点「创建内容回写」开始。';
}

/** 未交付维度的说明（平台实际调用 / 上游变更事件 / 描述与 A+ 内部基线 / 类目回写）。 */
export function unavailableDimensions(
  caps: ContentWritebackCapabilities,
): ContentWritebackCapabilityDimension[] {
  return caps.dimensions.filter((d) => !d.available);
}

/** 🔴 三条回写通道的归属：不属于本模块的通道返回 ownerModule，供页面显示「改价请去 05 定价」。 */
export function foreignChannels(caps: ContentWritebackCapabilities): ContentWritebackChannel[] {
  return caps.channels.filter((c) => !c.handledHere);
}

/**
 * 🔴 一个字段的改前值怎么显示。
 *
 * `MISSING` 时**不显示改后值顶替** —— 那会让「平台上现在是什么样」永远答不上来。
 * 返回 `{ known: false, text: '平台侧无基线（改前值不可知）' }` 而不是把 after 当 before 显示。
 */
export function beforeDisplay(change: ContentWritebackChangeRow): {
  known: boolean;
  text: string;
} {
  if (change.beforeSource === 'MISSING') {
    return { known: false, text: '平台侧无基线（改前值不可知）' };
  }
  return { known: true, text: change.beforeValue === '' ? '（空）' : change.beforeValue };
}

/** 改后值的来源文案：内部草稿 / 显式覆盖值 / 无来源。 */
export function afterSourceLabel(afterSource: string): string {
  switch (afterSource) {
    case 'INTERNAL_DRAFT':
      return '内部草稿';
    case 'OVERRIDE':
      return '本次显式给值';
    case 'MISSING':
      return '无来源';
    default:
      return afterSource;
  }
}

/** 单个字段变更的结局文案。APPLIED / NO_CHANGE / SKIPPED_NO_SOURCE。 */
export function outcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'APPLIED':
      return '有变更';
    case 'NO_CHANGE':
      return '与线上相同（不派发）';
    case 'SKIPPED_NO_SOURCE':
      return '内部无可推送内容';
    default:
      return outcome;
  }
}

/** 字段中文名。 */
export function fieldLabel(field: string): string {
  return CHANGE_TYPE_OPTIONS.find((o) => o.value === field)?.label ?? field;
}
