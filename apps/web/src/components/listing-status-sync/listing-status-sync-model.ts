import { api } from '../inventory/api';

/**
 * 4.3.2.2 平台状态回流 前端模型。
 *
 * 蓝图：「接收并展示平台侧的商品状态变化：审核通过/驳回、下架、限流、被抑制（suppressed）、
 * 类目审核、价格异常通知，并支持一键处理（如按驳回原因批量修正重发）。输出：状态变更记录 +
 * 需处理队列。**回流是平台状态的权威来源**，内部状态与之冲突时以平台为准并告警。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **前端不重算冲突判定**。`conflictDetected` / `internalStatus` / `platformStatus` /
 *    `resolution` 都是服务端算好的结论，页面**原样显示**。判定口径是业务规则
 *    （双方对「是否在售」的主张是否相反），前端再实现一遍必然随版本漂移，
 *    而「哪一边说了算」错一次就是运营误操作。
 *
 * 2. **冲突不并进普通事件**。冲突有**自己的区**，并且明确写出「**以平台为准**」——
 *    「平台说已下架、内部还在售」是本模块最要紧的一条信息，混在事件列表里会被划过去。
 *
 * 3. **在途 ≠ 成功**。一键处理发起的修正重发落在 02 队列里，状态可能是
 *    `PENDING` / `SENDING` / `SUCCEEDED` / `FAILED` / **`UNKNOWN`**。
 *    `UNKNOWN`（结果未知，需 2.5.1.2 核验）**单独显示**，绝不并进「已处理」。
 *
 * 4. **已派发过的「一键处理」不静默消失**。回执里逐条写明「状态为 DISPATCHED，
 *    不再重复发起修正重发」，页面原样展示 —— 静默丢弃会让运营以为全发出去了。
 *
 * 5. **三态文案各不相同**：加载中 / 出错了 / 还没有数据。
 *    「还没有平台状态回流记录」与「事件源尚未接入」是**两回事**，不能共用一句话。
 */

export interface ListingStatusCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingStatusCapabilities {
  dimensions: ListingStatusCapabilityDimension[];
  eventTypes: string[];
  ingestChannels: string[];
  queueKinds: string[];
  queueStates: string[];
  conflictRule: string;
  authorityNote: string;
  relationNote: string;
}

/** 一条状态变更记录（= 平台状态回流的记录）。 */
export interface ListingStatusEventRow {
  eventId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  eventType: string;
  source: string;
  ingestChannel: string;
  fromStatus: string;
  toStatus: string;
  platformStatus: string;
  internalStatus: string;
  conflictDetected: boolean;
  conflictNote: string;
  resolution: string;
  rejectionReason: string;
  detail: string;
  note: string;
  occurredAt: string;
  receivedAt: string;
}

/** 一条需处理队列项（也承载冲突告警行）。 */
export interface ListingStatusQueueRow {
  queueId: string;
  eventId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  eventType: string;
  kind: string;
  state: string;
  rejectionReason: string;
  operationId: string | null;
  idempotencyKey: string;
  operationStatus: string;
  operationVerification: string;
  note: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface RepublishReceiptItem {
  queueId: string;
  eventId: string;
  listingId: string;
  externalItemId: string;
  shopCode: string;
  eventType: string;
  rejectionReason: string;
  operationId: string;
  idempotencyKey: string;
  operationStatus: string;
  operationVerification: string;
}

export interface RepublishReceipt {
  matchedCount: number;
  dispatchedCount: number;
  skippedCount: number;
  dispatched: RepublishReceiptItem[];
  rejected: string[];
  note: string;
}

export interface ListingStatusEventPage {
  items: ListingStatusEventRow[];
  page: number;
  size: number;
  total: number;
}

export interface ListingStatusQueuePage {
  items: ListingStatusQueueRow[];
  page: number;
  size: number;
  total: number;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<ListingStatusCapabilities> {
  return api<ListingStatusCapabilities>('channel/listing-status-events/capabilities');
}

export async function fetchEvents(params: {
  eventType?: string;
  shopCode?: string;
  platformCode?: string;
  conflict?: boolean;
  page?: number;
  size?: number;
}): Promise<ListingStatusEventPage> {
  const query = new URLSearchParams();
  if (params.eventType) query.set('eventType', params.eventType);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  if (params.platformCode) query.set('platformCode', params.platformCode);
  if (params.conflict !== undefined) query.set('conflict', String(params.conflict));
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingStatusEventPage>(`channel/listing-status-events/events?${query.toString()}`);
}

export async function fetchQueue(params: {
  kind?: string;
  state?: string;
  eventType?: string;
  rejectionReason?: string;
  shopCode?: string;
  page?: number;
  size?: number;
}): Promise<ListingStatusQueuePage> {
  const query = new URLSearchParams();
  if (params.kind) query.set('kind', params.kind);
  if (params.state) query.set('state', params.state);
  if (params.eventType) query.set('eventType', params.eventType);
  if (params.rejectionReason) query.set('rejectionReason', params.rejectionReason);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingStatusQueuePage>(`channel/listing-status-events/queue?${query.toString()}`);
}

/** 🔴 冲突与告警区：只取 CONFLICT_ALERT 行。 */
export async function fetchConflicts(page = 0, size = 20): Promise<ListingStatusQueuePage> {
  return api<ListingStatusQueuePage>(
    `channel/listing-status-events/conflicts?page=${page}&size=${size}`,
  );
}

/** 🔴 一键处理：按驳回原因（可再按事件类型/店铺/显式点名）批量发起修正重发。 */
export async function republish(body: {
  rejectionReason?: string;
  eventTypes?: string[];
  queueItemIds?: string[];
  shopCode?: string;
  limit?: number;
  note?: string;
}): Promise<RepublishReceipt> {
  return api<RepublishReceipt>('channel/listing-status-events/actions/republish', 'POST', body);
}

export async function acknowledgeQueueItem(queueId: string, note?: string): Promise<ListingStatusQueueRow> {
  return api<ListingStatusQueueRow>(
    `channel/listing-status-events/queue/${queueId}/acknowledge`,
    'POST',
    { note: note ?? '' },
  );
}

export async function resolveQueueItem(queueId: string, note?: string): Promise<ListingStatusQueueRow> {
  return api<ListingStatusQueueRow>(`channel/listing-status-events/queue/${queueId}/resolve`, 'POST', {
    note: note ?? '',
  });
}

// ================================================================ 纯函数（可单测）

/**
 * 🔴 事件类型的中文名 —— 职责句逐词对应。
 *
 * 顺序与后端 `EVENT_TYPES` 一致：审核通过 / 驳回 / 下架 / 限流 / 被抑制 / 类目审核 / 价格异常。
 */
export const EVENT_TYPE_OPTIONS = [
  { value: 'APPROVED', label: '审核通过', hint: '平台已接受该商品' },
  { value: 'REJECTED', label: '审核驳回', hint: '平台驳回了该商品；按驳回原因批量修正重发' },
  { value: 'DELISTED', label: '下架', hint: '平台已把该商品下架' },
  { value: 'THROTTLED', label: '限流', hint: '平台限制该商品的曝光；商品仍在售' },
  { value: 'SUPPRESSED', label: '被抑制', hint: '平台抑制该商品（suppressed）' },
  { value: 'CATEGORY_REVIEW', label: '类目审核', hint: '平台在审类目归属' },
  { value: 'PRICE_ALERT', label: '价格异常', hint: '平台报了价格异常；判定归 05 定价' },
] as const;

export function eventTypeLabel(eventType: string): string {
  return EVENT_TYPE_OPTIONS.find((o) => o.value === eventType)?.label ?? eventType;
}

/** 队列项类型文案。三种待办是**不同的东西**，不能都叫「待办」。 */
export function queueKindLabel(kind: string): string {
  switch (kind) {
    case 'REPUBLISH':
      return '可修正重发';
    case 'REVIEW':
      return '需人工处理';
    case 'CONFLICT_ALERT':
      return '冲突告警';
    default:
      return kind;
  }
}

/** 队列项状态文案。 */
export function queueStateLabel(state: string): string {
  switch (state) {
    case 'OPEN':
      return '待处理';
    case 'ACKNOWLEDGED':
      return '已确认';
    case 'DISPATCHED':
      return '已发起修正重发（在途）';
    case 'RESOLVED':
      return '已处理完';
    case 'CANCELLED':
      return '已作废';
    default:
      return state;
  }
}

/** 队列项状态的徽章色调。 */
export function queueStateTone(
  state: string,
): 'neutral' | 'info' | 'success' | 'danger' | 'warning' {
  switch (state) {
    case 'RESOLVED':
      return 'success';
    case 'DISPATCHED':
      return 'info';
    case 'CANCELLED':
      return 'danger';
    case 'ACKNOWLEDGED':
      return 'warning';
    default:
      return 'neutral';
  }
}

/**
 * 🔴 02 队列任务状态的文案 —— **在途、结果未知、失败三态刻意不同**。
 *
 * `UNKNOWN` = 提交平台后拿不到确定结果（2.5.1.2 的核验通道管这个）。
 * 把它显示成「已处理」是谎报；显示成「失败」会让运营以为要重来一遍。
 */
export function operationStatusLabel(status: string, verification: string): string {
  if (status === '') return '尚未发起';
  switch (status) {
    case 'PENDING':
      return '已入队待平台执行';
    case 'SENDING':
      return '平台执行中';
    case 'SUCCEEDED':
      return '平台已接受';
    case 'FAILED':
      return '平台执行失败';
    case 'UNKNOWN':
      return verification === 'STILL_UNKNOWN'
        ? '结果未知（需 2.5.1.2 核验）'
        : '结果未知';
    default:
      return status;
  }
}

/** 02 队列任务状态的徽章色调。结果未知用 warning（既不是成功也不是失败）。 */
export function operationStatusTone(
  status: string,
): 'neutral' | 'info' | 'success' | 'danger' | 'warning' {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'PENDING':
    case 'SENDING':
      return 'info';
    case 'UNKNOWN':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 事件来源的通道文案。 */
export function ingestChannelLabel(channel: string): string {
  switch (channel) {
    case 'WEBHOOK':
      return '平台推送';
    case 'POLL':
      return '轮询补充';
    case 'API':
      return '接口录入';
    case 'MANUAL':
      return '内部人工改状态';
    default:
      return channel;
  }
}

/**
 * 🔴 冲突区的一行摘要。
 *
 * 🔴 前端**不重算**冲突判定：这里只是把服务端算好的三个字段排成一句人话。
 * 「以平台为准」四个字必须出现在摘要里 —— 那是蓝图的加粗句，也是这条告警的行动指令。
 */
export function conflictSummary(event: ListingStatusEventRow): string {
  if (!event.conflictDetected) return '';
  return (
    `内部状态 ${event.internalStatus || '（未记录）'}，平台状态 ${event.platformStatus || event.eventType}` +
    ` —— 以平台为准`
  );
}

/** 一键处理的回执摘要：逐条列出真的发起了什么、什么没发。 */
export function republishSummary(receipt: RepublishReceipt): string[] {
  const lines: string[] = [];
  lines.push(
    `命中 ${receipt.matchedCount} 条 · 发起修正重发 ${receipt.dispatchedCount} 条 · 跳过 ${receipt.skippedCount} 条`,
  );
  for (const item of receipt.dispatched) {
    lines.push(
      `已发起：${item.externalItemId || item.listingId}（${eventTypeLabel(item.eventType)}` +
        `${item.rejectionReason ? ` · ${item.rejectionReason}` : ''}）→ 02 队列任务 ${item.operationId}` +
        ` · ${operationStatusLabel(item.operationStatus, item.operationVerification)}`,
    );
  }
  for (const reason of receipt.rejected) {
    lines.push(`未发起：${reason}`);
  }
  if (receipt.note) lines.push(receipt.note);
  return lines;
}

/** 真分页的页码摘要（total 来自服务端 count，不是前端算的）。 */
export function pageSummary(page: { page: number; size: number; total: number }): string {
  const from = page.total === 0 ? 0 : page.page * page.size + 1;
  const to = Math.min(page.total, (page.page + 1) * page.size);
  return `第 ${from}-${to} 条 / 共 ${page.total} 条`;
}

/** 同步延迟：平台事件发生时刻 → 入库时刻。两个都是真实时刻，差值才有意义。 */
export function syncDelayLabel(occurredAt: string, receivedAt: string): string {
  const occurred = new Date(occurredAt).getTime();
  const received = new Date(receivedAt).getTime();
  if (Number.isNaN(occurred) || Number.isNaN(received)) return '—';
  const seconds = Math.round((received - occurred) / 1000);
  if (seconds < 0) return '—';
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} 分钟`;
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`;
}

/** 空态文案：区分「还没有记录」与「事件源尚未接入」—— 两回事，不能共用一句话。 */
export function emptyEventsMessage(sourceIngested: boolean): string {
  return sourceIngested
    ? '还没有平台状态回流记录。平台侧的商品状态变化（审核通过/驳回、下架、限流、被抑制、类目审核、价格异常）到达后会出现在这里。'
    : '还没有平台状态回流记录。平台侧的真实推送通道尚未接入（归 02 平台适配器），本模块目前只提供回流入库与呈现这一段 —— 记录会随事件到达而出现。';
}

/** 需处理队列的空态文案。 */
export function emptyQueueMessage(): string {
  return '没有待处理的事项。平台驳回 / 下架 / 被抑制的商品会进这里（可一键修正重发），限流 / 类目审核 / 价格异常会进「需人工处理」。';
}

/** 冲突区的空态文案。 */
export function emptyConflictsMessage(): string {
  return '当前没有内部状态与平台状态冲突的告警。回流是平台状态的权威来源 —— 一旦两边对「是否在售」的主张相反，这里会立刻出现一条「以平台为准」的告警。';
}

/** 未交付维度的说明（真实 webhook / 轮询调度器 / 生命周期联动 / 发布任务视图 / 合规拦截）。 */
export function unavailableDimensions(
  caps: ListingStatusCapabilities,
): ListingStatusCapabilityDimension[] {
  return caps.dimensions.filter((d) => !d.available);
}
