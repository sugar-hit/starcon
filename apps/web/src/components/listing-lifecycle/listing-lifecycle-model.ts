import { api } from '../inventory/api';

/**
 * 4.1.1.3 生命周期与下架 前端模型。
 *
 * 蓝图：「管理 listing 状态：在售 / 草稿 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 /
 * 被封禁。支持平台侧状态变更被动同步，以及主动下架时选择是否回写平台。输出：状态变更记录
 * （来源：平台 or 人工）+ 可选的平台操作任务。**平台下架与内部下架是两个事实**，
 * 分别记录并展示差异 —— 平台把商品下架了而内部不知情，是最常见的漏单原因。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **每条事件都显式标注来源**（平台 / 人工）。`source` 是本模块的核心价值：
 *    少了它，「平台把商品下架了」和「我们主动下架了」在界面上长得一模一样 ——
 *    而这两件事的处置方式完全相反。
 *
 * 2. **前端不重算差异判定**。`divergence` / `divergenceKind` / `divergenceNote` 是
 *    服务端算好的结论，页面**原样显示**。判定口径（双方对「是否在售」的主张是否相反）
 *    是一套业务规则，前端再实现一遍必然随版本漂移，而「哪一边说了算」错一次就是运营误操作。
 *
 * 3. **「是否回写平台」默认是「否」**。回写是一个**有资损风险**的动作（把平台上正在卖的
 *    商品下架），漏发的后果只是内部与平台暂时不一致 —— 保守默认永远是对的那一侧。
 *
 * 4. **「回写了」必须有凭据**。回执里的 `writebackBatchId` / `operationId` 才是
 *    「真的产生了平台任务」的证据；没有它们就显示「只改了内部状态，平台侧不知情」，
 *    **不显示**一个必然失败的「已下发平台」。
 *
 * 5. **三态文案各不相同**：加载中 / 出错了 / 还没有数据。
 *    「还没有商品」与「筛选条件太窄」是**两回事**，不能共用一句话。
 */

// ================================================================ 类型

export interface ListingLifecycleCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingLifecycleCapabilities {
  dimensions: ListingLifecycleCapabilityDimension[];
  lifecycleStatuses: string[];
  actionSources: string[];
  actions: string[];
  platformEventTypes: string[];
  ingestChannels: string[];
  stateMachineRule: string;
  divergenceRule: string;
  relationNote: string;
}

export interface ListingLifecycleFilterOptions {
  lifecycleStatuses: string[];
  actionSources: string[];
  actions: string[];
  platformEventTypes: string[];
  ingestChannels: string[];
  divergenceKinds: string[];
}

/** 生命周期列表的一行（= 一个商品）。 */
export interface ListingLifecycleRow {
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  status: string;
  lastChangeSource: string;
  lastChangeAt: string | null;
  lastChangeFrom: string;
  lastChangeTo: string;
  platformStatus: string;
  platformEventType: string;
  platformEventAt: string | null;
  divergence: boolean;
  divergenceKind: string;
  divergenceNote: string;
  allowedActions: string[];
  eventCount: number;
  updatedAt: string | null;
}

/** 一条状态变更事件。 */
export interface ListingLifecycleEventRow {
  eventId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  fromStatus: string;
  toStatus: string;
  source: string;
  eventType: string;
  platformStatus: string;
  internalStatus: string;
  conflictDetected: boolean;
  conflictNote: string;
  resolution: string;
  rejectionReason: string;
  note: string;
  internalStateChange: boolean;
  occurredAt: string | null;
  receivedAt: string | null;
}

/** 一次人工动作（或一次平台联动）的留痕。 */
export interface ListingLifecycleActionRow {
  actionId: string;
  listingId: string;
  action: string;
  fromStatus: string;
  toStatus: string;
  source: string;
  platformEventType: string;
  platformStatus: string;
  rejectionReason: string;
  reason: string;
  writebackRequested: boolean;
  writebackBatchId: string | null;
  writebackBatchNo: string;
  writebackItemId: string | null;
  operationId: string | null;
  platformEventId: string | null;
  actorId: string | null;
  createdAt: string | null;
}

/** 一条差异（平台侧与内部侧说法相反）。 */
export interface ListingLifecycleDivergenceRow {
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  internalStatus: string;
  platformStatus: string;
  platformEventType: string;
  divergenceKind: string;
  divergenceNote: string;
  severity: string;
  internalChangedAt: string | null;
  platformChangedAt: string | null;
}

export interface ListingLifecycleDetail {
  listing: ListingLifecycleRow;
  timeline: ListingLifecycleEventRow[];
  actions: ListingLifecycleActionRow[];
  allowedActions: string[];
  stateMachineRule: string;
}

export interface ListingLifecyclePage {
  items: ListingLifecycleRow[];
  page: number;
  size: number;
  total: number;
}

export interface ListingLifecycleEventPage {
  items: ListingLifecycleEventRow[];
  page: number;
  size: number;
  total: number;
}

export interface ListingLifecycleDivergencePage {
  items: ListingLifecycleDivergenceRow[];
  page: number;
  size: number;
  total: number;
}

export interface LifecycleActionReceipt {
  actionId: string;
  listingId: string;
  action: string;
  fromStatus: string;
  toStatus: string;
  source: string;
  writebackRequested: boolean;
  writebackBatchId: string | null;
  writebackBatchNo: string;
  operationId: string | null;
  statusEventId: string | null;
  note: string;
}

export interface LifecycleSyncReceipt {
  appliedCount: number;
  skippedCount: number;
  applied: string[];
  skipped: string[];
  note: string;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<ListingLifecycleCapabilities> {
  return api<ListingLifecycleCapabilities>('channel/listing-lifecycles/capabilities');
}

export async function fetchFilterOptions(): Promise<ListingLifecycleFilterOptions> {
  return api<ListingLifecycleFilterOptions>('channel/listing-lifecycles/filter-options');
}

export async function fetchLifecycles(params: {
  status?: string;
  source?: string;
  shopCode?: string;
  platformCode?: string;
  keyword?: string;
  onlyDivergence?: boolean;
  page?: number;
  size?: number;
}): Promise<ListingLifecyclePage> {
  const query = new URLSearchParams();
  if (params.status) query.set('status', params.status);
  if (params.source) query.set('source', params.source);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  if (params.platformCode) query.set('platformCode', params.platformCode);
  if (params.keyword) query.set('keyword', params.keyword);
  if (params.onlyDivergence) query.set('onlyDivergence', 'true');
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingLifecyclePage>(`channel/listing-lifecycles?${query.toString()}`);
}

export async function fetchDivergences(
  page = 0,
  size = 20,
): Promise<ListingLifecycleDivergencePage> {
  return api<ListingLifecycleDivergencePage>(
    `channel/listing-lifecycles/diff?page=${page}&size=${size}`,
  );
}

export async function fetchListingDetail(listingId: string): Promise<ListingLifecycleDetail> {
  return api<ListingLifecycleDetail>(`channel/listing-lifecycles/${listingId}`);
}

export async function fetchTimeline(
  listingId: string,
  source?: string,
  page = 0,
  size = 50,
): Promise<ListingLifecycleEventPage> {
  const query = new URLSearchParams();
  if (source) query.set('source', source);
  query.set('page', String(page));
  query.set('size', String(size));
  return api<ListingLifecycleEventPage>(
    `channel/listing-lifecycles/${listingId}/timeline?${query.toString()}`,
  );
}

/** 人工动作：主动下架 / 重新上架 / 提交平台审核 / 标记被封禁。 */
export async function actLifecycle(
  listingId: string,
  body: { action: string; writebackToPlatform?: boolean; reason?: string },
): Promise<LifecycleActionReceipt> {
  return api<LifecycleActionReceipt>(
    `channel/listing-lifecycles/${listingId}/actions`,
    'POST',
    body,
  );
}

/** 平台状态被动同步。 */
export async function applyPlatformStatus(
  listingId: string,
  body: {
    platformEventId?: string | null;
    eventType?: string;
    platformStatus?: string;
    rejectionReason?: string;
    ingestChannel?: string;
    note?: string;
  },
): Promise<LifecycleActionReceipt> {
  return api<LifecycleActionReceipt>(
    `channel/listing-lifecycles/${listingId}/platform-status`,
    'POST',
    body,
  );
}

/** 批量同步：把还没联动过的平台事件应用到内部状态。 */
export async function syncPlatformStatus(): Promise<LifecycleSyncReceipt> {
  return api<LifecycleSyncReceipt>('channel/listing-lifecycles/sync', 'POST');
}

// ================================================================ 纯函数（可单测）

/**
 * 🔴 七态的中文名 —— 与后端的七态取值域逐字对应。
 *
 * 顺序就是蓝图的经营顺序：草稿 → 在售 → 平台审核中 → 平台驳回 → 主动下架 / 平台下架 / 被封禁。
 */
export const STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿', hint: '已登记但还没提交平台' },
  { value: 'ON_SALE', label: '在售', hint: '商品正在平台上有序' },
  { value: 'UNDER_REVIEW', label: '平台审核中', hint: '已提交，等平台裁决' },
  { value: 'REJECTED', label: '平台驳回', hint: '平台驳回了，需要修正' },
  { value: 'DELISTED_BY_US', label: '主动下架', hint: '我们主动下的架' },
  { value: 'DELISTED_BY_PLATFORM', label: '平台下架', hint: '平台下的架 —— 与内部下架是两个事实' },
  { value: 'BLOCKED', label: '被封禁', hint: '平台封了这个商品' },
] as const;

export function statusLabel(status: string): string {
  return STATUS_OPTIONS.find((o) => o.value === status)?.label ?? status;
}

/** 状态的徽章色调。 */
export function statusTone(
  status: string,
): 'neutral' | 'info' | 'success' | 'danger' | 'warning' {
  switch (status) {
    case 'ON_SALE':
      return 'success';
    case 'UNDER_REVIEW':
      return 'info';
    case 'REJECTED':
      return 'danger';
    case 'DELISTED_BY_US':
    case 'DELISTED_BY_PLATFORM':
      return 'warning';
    case 'BLOCKED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/**
 * 🔴 来源的文案 —— **平台 / 人工**。
 *
 * 这是本模块的核心价值：少了它，「平台把商品下架了」和「我们主动下架了」在界面上
 * 长得一模一样，而这两件事的处置方式完全相反。
 */
export function sourceLabel(source: string): string {
  switch (source) {
    case 'MANUAL':
      return '人工';
    case 'PLATFORM':
      return '平台';
    default:
      return source;
  }
}

/** 来源的徽章色调：平台来源用 info，人工用 neutral —— 两者必须一眼能分开。 */
export function sourceTone(source: string): 'neutral' | 'info' | 'success' | 'danger' | 'warning' {
  switch (source) {
    case 'PLATFORM':
      return 'info';
    case 'MANUAL':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 人工动作的中文名。 */
export function actionLabel(action: string): string {
  switch (action) {
    case 'DELIST':
      return '主动下架';
    case 'REPUBLISH':
      return '重新上架';
    case 'SUBMIT_REVIEW':
      return '提交平台审核';
    case 'MARK_BLOCKED':
      return '标记被封禁';
    case 'APPLY_PLATFORM_STATUS':
      return '平台状态联动';
    default:
      return action;
  }
}

/** 人工动作的说明（按钮上的一句话）。 */
export function actionHint(action: string): string {
  switch (action) {
    case 'DELIST':
      return '把商品从「在售」改成「主动下架」，可选择是否回写平台';
    case 'REPUBLISH':
      return '把商品改回「在售」；平台侧的上架动作归 4.3.1.2 / 4.3.2.2';
    case 'SUBMIT_REVIEW':
      return '把商品改成「平台审核中」，等平台裁决';
    case 'MARK_BLOCKED':
      return '把商品标成「被封禁」';
    default:
      return '';
  }
}

/** 平台事件类型的中文名。 */
export function platformEventLabel(eventType: string): string {
  switch (eventType) {
    case 'APPROVED':
      return '平台审核通过';
    case 'REJECTED':
      return '平台驳回';
    case 'DELISTED':
      return '平台下架';
    case 'THROTTLED':
      return '平台限流';
    case 'SUPPRESSED':
      return '平台抑制';
    case 'CATEGORY_REVIEW':
      return '平台类目审核';
    case 'PRICE_ALERT':
      return '平台价格异常';
    case 'STATUS_CHANGE':
      return '内部状态变更';
    default:
      return eventType;
  }
}

/**
 * 🔴 差异类型的中文名 —— 四类差异是**不同的东西**，不能都叫「有差异」。
 */
export function divergenceLabel(kind: string): string {
  switch (kind) {
    case 'PLATFORM_DELISTED_INTERNAL_ON_SALE':
      return '平台已下架，内部还在售';
    case 'INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE':
      return '内部不在售，平台还在卖';
    case 'INTERNAL_DELISTED_NOT_WRITEBACK':
      return '内部下架但没回写平台';
    case 'INTERNAL_DELISTED_NO_WRITEBACK_RECORD':
      return '内部下架但没有动作留痕';
    default:
      return kind;
  }
}

/** 差异的徽章色调：漏单风险是 danger，其余是 warning。 */
export function divergenceTone(
  kind: string,
): 'neutral' | 'info' | 'success' | 'danger' | 'warning' {
  switch (kind) {
    case 'PLATFORM_DELISTED_INTERNAL_ON_SALE':
      return 'danger';
    case 'INTERNAL_NOT_SELLABLE_PLATFORM_ON_SALE':
    case 'INTERNAL_DELISTED_NOT_WRITEBACK':
    case 'INTERNAL_DELISTED_NO_WRITEBACK_RECORD':
      return 'warning';
    default:
      return 'neutral';
  }
}

/**
 * 🔴 时间线上一条事件的标题。
 *
 * `internalStateChange` 是**服务端算好的**（该事件是内部状态变更还是平台事件原文），
 * 前端不自己判断「这个 token 算不算七态」。
 */
export function eventHeadline(event: ListingLifecycleEventRow): string {
  if (event.internalStateChange) {
    return `内部状态 ${event.fromStatus || '（无）'} → ${event.toStatus}`;
  }
  return `平台事件：${platformEventLabel(event.eventType)}${
    event.platformStatus ? `（${event.platformStatus}）` : ''
  }`;
}

/** 时间线上一条事件的副标题（来源 + 动因 + 驳回原因）。 */
export function eventSubline(event: ListingLifecycleEventRow): string {
  const parts: string[] = [`来源：${sourceLabel(event.source)}`];
  if (event.internalStateChange && event.eventType && event.eventType !== 'STATUS_CHANGE') {
    parts.push(platformEventLabel(event.eventType));
  }
  if (event.rejectionReason) parts.push(`驳回原因：${event.rejectionReason}`);
  if (event.note) parts.push(event.note);
  return parts.join(' · ');
}

/** 动作留痕的一行摘要。 */
export function actionSummary(action: ListingLifecycleActionRow): string {
  const parts: string[] = [
    `${actionLabel(action.action)}：${action.fromStatus} → ${action.toStatus}`,
    `来源：${sourceLabel(action.source)}`,
  ];
  if (action.writebackRequested) {
    parts.push(`已回写平台（批次 ${action.writebackBatchNo || action.writebackBatchId}）`);
  } else if (action.action === 'DELIST') {
    parts.push('未回写平台');
  }
  if (action.reason) parts.push(action.reason);
  return parts.join(' · ');
}

/**
 * 🔴 一次动作的回执摘要。
 *
 * 「有没有回写平台」必须说清楚 —— 回写与不回写的后果完全不同：
 * 回写了 = 平台侧也会下架；没回写 = 只改内部状态，平台还在卖。
 */
export function receiptSummary(receipt: LifecycleActionReceipt): string[] {
  const lines: string[] = [];
  lines.push(
    `${actionLabel(receipt.action)}：${receipt.fromStatus} → ${receipt.toStatus}` +
      `（来源：${sourceLabel(receipt.source)}）`,
  );
  if (receipt.writebackRequested) {
    lines.push(
      `已生成平台操作任务：批次 ${receipt.writebackBatchNo || receipt.writebackBatchId}` +
        ` · 02 队列任务 ${receipt.operationId}`,
    );
  } else {
    lines.push('未回写平台 —— 只改了内部状态，平台侧不知情（平台可能还在卖）');
  }
  if (receipt.statusEventId) {
    lines.push(`状态变更事件 ${receipt.statusEventId}（由数据库触发器记录）`);
  }
  if (receipt.note) lines.push(receipt.note);
  return lines;
}

/** 批量同步的回执摘要。 */
export function syncSummary(receipt: LifecycleSyncReceipt): string[] {
  const lines: string[] = [];
  lines.push(`联动 ${receipt.appliedCount} 条 · 未联动 ${receipt.skippedCount} 条`);
  for (const line of receipt.applied) lines.push(`已联动：${line}`);
  for (const line of receipt.skipped) lines.push(`未联动：${line}`);
  if (receipt.note) lines.push(receipt.note);
  return lines;
}

/** 真分页的页码摘要（total 来自服务端 count，不是前端算的）。 */
export function pageSummary(page: { page: number; size: number; total: number }): string {
  const from = page.total === 0 ? 0 : page.page * page.size + 1;
  const to = Math.min(page.total, (page.page + 1) * page.size);
  return `第 ${from}-${to} 条 / 共 ${page.total} 条`;
}

/** 列表的空态文案。 */
export function emptyListMessage(filtered: boolean): string {
  return filtered
    ? '当前筛选条件下没有商品。换个状态、来源或店铺再试 —— 或者清空筛选看全量。'
    : '还没有登记任何商品。listing 由 02 同步从平台拉取后登记进来，登记后就能在这里管理它的生命周期。';
}

/** 差异区的空态文案。 */
export function emptyDivergenceMessage(): string {
  return '当前没有平台侧与内部侧的差异。回流是平台状态的权威来源 —— 一旦两边对「当前是否在售」的主张相反，这里会立刻出现一条差异。';
}

/** 时间线的空态文案。 */
export function emptyTimelineMessage(source: string): string {
  return source === 'PLATFORM'
    ? '这个商品还没有平台来源的状态变更记录。'
    : source === 'MANUAL'
      ? '这个商品还没有人工来源的状态变更记录。'
      : '这个商品还没有任何状态变更记录。';
}

/** 未交付维度的说明（平台任务派发 / 重新上架回写 / 轮询调度器 / 批量动作 / 申诉工单）。 */
export function unavailableDimensions(
  caps: ListingLifecycleCapabilities,
): ListingLifecycleCapabilityDimension[] {
  return caps.dimensions.filter((d) => !d.available);
}

/** 时刻格式化（空值给「—」）。 */
export function formatTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('zh-CN');
}
