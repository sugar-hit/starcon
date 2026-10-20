import { api } from '../inventory/api';

/**
 * 10.3.2.2 揽收与签收确认前端模型。
 *
 * <p>蓝图：「确认承运商揽收（避免超时未揽收被平台判未发货）与签收（触发售后窗口关闭
 * 与评价邀请时机）」，输入「承运商揽收/签收事件；SLA 时限」，输出「状态更新 + 提醒任务」，
 * 并加粗「<b>揽收状态直接影响平台履约指标，需主动监控而非等轨迹同步</b>」。
 *
 * <p>🔴 页面最容易做成假活的两件事，这里都堵掉了：
 *
 * <ol>
 *   <li><b>超时不靠页面算</b>：{@code pickupState='OVERDUE'} 由**后端巡检**按
 *       {@code now()} 与时限快照判定（是否超时取决于现在几点），页面只展示后端给的结论。
 *       页面自己比一次时刻就多了一套口径，而且会在后端没巡检时说「没超时」。</li>
 *   <li><b>「有没有确认」不靠页面推</b>：页面上「已揽收 / 未揽收」直接取后端的
 *       {@code pickupState}，不从轨迹事件里自己数 —— 证据归 10.3.1.1 判定，
 *       本页只呈现。</li>
 * </ol>
 *
 * <p>⚠️ 本页**不涉及任何金额**：运费成本归集与结算归 12 财务（未开发）。
 * 这一页自造一个运费列，就会造出第二套成本口径。
 */

// ====================================================================
// 传输类型（与后端 LogisticsPickupSignoffModels 一一对应）
// ====================================================================

export interface LogisticsPickupSignoffCapabilityDimension {
  dimension: string;
  description: string;
  /** false 时必须同时给出 ownerModule 与 impact —— 缺口要说清归属与影响。 */
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface LogisticsPickupSignoffCapabilities {
  dimensions: LogisticsPickupSignoffCapabilityDimension[];
  caseStatuses: string[];
  pickupStates: string[];
  deliveryStates: string[];
  reminderKinds: string[];
  reminderStatuses: string[];
  notificationKinds: string[];
  notificationStatuses: string[];
  gapCodes: string[];
  boundaryNote: string;
  highlight: string;
}

/** 确认单行 = 一个**交运件**（10.3.2.1 交接清单的一行）。 */
export interface PickupSignoffCaseRow {
  id: string;
  caseNo: string;
  sourceManifestItemId: string;
  sourceBatchId: string;
  sourceBatchNo: string;
  sourceBatchStatus: string;
  sourceSubjectId: string | null;
  /** 命中了几个轨迹主体：>1 = 运单号不唯一，不自动确认。 */
  subjectMatchCount: number;
  masterWaybillNo: string;
  parcelNo: number;
  parcelRef: string;
  carrierCode: string;
  channelId: string | null;
  salesOrderNo: string;
  destinationCountry: string;
  grossWeightKg: number;
  handoverAt: string;
  dispatchedAt: string | null;
  slaRuleId: string | null;
  /** 小时。后端是 numeric(19,6)，JSON 里是 number（禁浮点的保证在 DB 与服务层）。 */
  pickupSlaHours: number | null;
  reminderLeadHours: number | null;
  pickupDeadlineAt: string | null;
  pickupState: string;
  pickupStateLabel: string;
  pickupConfirmedAt: string | null;
  pickupEvidenceSource: string;
  pickupEvidenceRef: string;
  deliveryState: string;
  deliveryStateLabel: string;
  deliveryConfirmedAt: string | null;
  deliveryEvidenceSource: string;
  deliveryEvidenceRef: string;
  exceptionFlag: boolean;
  exceptionCode: string;
  exceptionNote: string;
  exceptionAt: string | null;
  /** 超时小时数（巡检那一刻的读数，会随巡检继续增长）。 */
  overdueHours: number | null;
  status: string;
  statusLabel: string;
  gapCode: string;
  gapLabel: string;
  gapReason: string;
  lastReconciledAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PickupSignoffCarrierBucket {
  carrierCode: string;
  caseCount: number;
  overdueCount: number;
}

export interface PickupSignoffStatusBucket {
  status: string;
  caseCount: number;
}

export interface PickupSignoffSummary {
  totalCount: number;
  pendingPickupCount: number;
  pickedUpCount: number;
  overdueCount: number;
  deliveredCount: number;
  voidCount: number;
  exceptionCount: number;
  noWaybillCount: number;
  subjectMissingCount: number;
  subjectAmbiguousCount: number;
  slaUnconfiguredCount: number;
  reminderPendingCount: number;
  notificationReadyCount: number;
  notificationPendingCount: number;
  byCarrier: PickupSignoffCarrierBucket[];
  byStatus: PickupSignoffStatusBucket[];
}

export interface PickupSignoffCasePage {
  items: PickupSignoffCaseRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  summary: PickupSignoffSummary;
}

export interface PickupSignoffEventRow {
  id: string;
  caseId: string;
  eventKind: string;
  eventKindLabel: string;
  fromStatus: string;
  toStatus: string;
  detail: string;
  carrierEventId: string;
  actorMembershipId: string | null;
  occurredAt: string;
}

export interface PickupSignoffEventPage {
  items: PickupSignoffEventRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PickupSignoffReminderRow {
  id: string;
  caseId: string;
  caseNo: string;
  masterWaybillNo: string;
  carrierCode: string;
  reminderKind: string;
  reminderKindLabel: string;
  status: string;
  statusLabel: string;
  title: string;
  detail: string;
  dueAt: string | null;
  assignedToMembershipId: string | null;
  acknowledgedAt: string | null;
  acknowledgedByMembershipId: string | null;
  resolvedAt: string | null;
  resolutionNote: string;
  createdAt: string;
}

export interface PickupSignoffReminderPage {
  items: PickupSignoffReminderRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PickupSignoffNotificationRow {
  id: string;
  caseId: string;
  caseNo: string;
  masterWaybillNo: string;
  carrierCode: string;
  notificationKind: string;
  notificationKindLabel: string;
  downstreamModule: string;
  downstreamStatus: string;
  downstreamStatusLabel: string;
  dueAt: string | null;
  /** JSON 字符串（后端是 jsonb，契约里是 string）—— 要结构化内容就自己 JSON.parse。 */
  payload: string;
  attemptCount: number;
  lastError: string;
  consumedAt: string | null;
  consumedByMembershipId: string | null;
  createdAt: string;
}

export interface PickupSignoffNotificationPage {
  items: PickupSignoffNotificationRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PickupSignoffCaseDetail {
  signoffCase: PickupSignoffCaseRow;
  timeline: PickupSignoffEventRow[];
  reminders: PickupSignoffReminderRow[];
  notifications: PickupSignoffNotificationRow[];
  boundaryNotes: string[];
}

export interface PickupSignoffSlaRuleRow {
  id: string;
  carrierCode: string;
  channelId: string | null;
  channelCode: string | null;
  channelName: string | null;
  pickupSlaHours: number;
  reminderLeadHours: number;
  reviewInviteDelayHours: number;
  afterSalesWindowHours: number;
  status: string;
  statusLabel: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface PickupSignoffSlaRulePage {
  items: PickupSignoffSlaRuleRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PickupSignoffSyncResult {
  scanned: number;
  created: number;
  updated: number;
  skipped: number;
  totalElements: number;
  notes: string[];
}

export interface PickupSignoffReconcileResult {
  scanned: number;
  created: number;
  pickupConfirmed: number;
  deliveryConfirmed: number;
  overdueDetected: number;
  dueSoonDetected: number;
  remindersCreated: number;
  notificationsCreated: number;
  exceptionsMarked: number;
  voided: number;
  notes: string[];
}

// ====================================================================
// 筛选
// ====================================================================

export interface PickupSignoffFilters {
  carrierCode: string;
  status: string;
  pickupState: string;
  deliveryState: string;
  batchNo: string;
  gapCode: string;
  keyword: string;
  overdueOnly: boolean;
  exceptionOnly: boolean;
  sort: string;
  page: number;
  size: number;
}

export const EMPTY_PICKUPSIGNOFF_FILTERS: PickupSignoffFilters = {
  carrierCode: '',
  status: '',
  pickupState: '',
  deliveryState: '',
  batchNo: '',
  gapCode: '',
  keyword: '',
  overdueOnly: false,
  exceptionOnly: false,
  sort: 'HANDOVER_AT_DESC',
  page: 0,
  size: 20,
};

/** 提醒任务的筛选（与确认单分开：提醒按「要处理什么」看）。 */
export interface PickupSignoffReminderFilters {
  carrierCode: string;
  reminderKind: string;
  status: string;
  page: number;
  size: number;
}

export const EMPTY_PICKUPSIGNOFF_REMINDER_FILTERS: PickupSignoffReminderFilters = {
  carrierCode: '',
  reminderKind: '',
  status: '',
  page: 0,
  size: 20,
};

/** 下游待通知的筛选。 */
export interface PickupSignoffNotificationFilters {
  carrierCode: string;
  notificationKind: string;
  downstreamStatus: string;
  page: number;
  size: number;
}

export const EMPTY_PICKUPSIGNOFF_NOTIFICATION_FILTERS: PickupSignoffNotificationFilters = {
  carrierCode: '',
  notificationKind: '',
  downstreamStatus: '',
  page: 0,
  size: 20,
};

/** 登记时限规则的入参。 */
export interface PickupSignoffSlaRuleInput {
  carrierCode: string;
  channelId: string | null;
  pickupSlaHours: number | null;
  reminderLeadHours: number | null;
  reviewInviteDelayHours: number | null;
  afterSalesWindowHours: number | null;
  status: string;
  note: string;
}

export const EMPTY_PICKUPSIGNOFF_SLA_RULE: PickupSignoffSlaRuleInput = {
  carrierCode: '',
  channelId: null,
  pickupSlaHours: null,
  reminderLeadHours: 4,
  reviewInviteDelayHours: 0,
  afterSalesWindowHours: 168,
  status: 'ACTIVE',
  note: '',
};

// ====================================================================
// 标签与语义色
// ====================================================================

/** 确认单状态：语义色跟着**严重度**走。 */
export function signoffStatusLabel(code: string): string {
  switch (code) {
    case 'PENDING_PICKUP':
      return '已交运待揽收';
    case 'PICKED_UP':
      return '已揽收 · 在途';
    case 'PICKUP_OVERDUE':
      return '超时未揽收';
    case 'DELIVERED':
      return '已签收';
    case 'VOID':
      return '已作废';
    default:
      return code || '未知';
  }
}

export function signoffStatusTone(code: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (code) {
    case 'PICKUP_OVERDUE':
      return 'danger';
    case 'PENDING_PICKUP':
      return 'warning';
    case 'PICKED_UP':
    case 'DELIVERED':
      return 'success';
    default:
      return 'neutral';
  }
}

export function pickupStateLabel(code: string): string {
  switch (code) {
    case 'PENDING':
      return '未确认揽收';
    case 'CONFIRMED':
      return '已确认揽收';
    case 'OVERDUE':
      return '已超时未揽收';
    default:
      return code || '未知';
  }
}

export function pickupStateTone(code: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (code) {
    case 'CONFIRMED':
      return 'success';
    case 'OVERDUE':
      return 'danger';
    case 'PENDING':
      return 'warning';
    default:
      return 'neutral';
  }
}

export function deliveryStateLabel(code: string): string {
  switch (code) {
    case 'PENDING':
      return '未确认签收';
    case 'CONFIRMED':
      return '已确认签收';
    default:
      return code || '未知';
  }
}

export function deliveryStateTone(code: string): 'success' | 'neutral' {
  return code === 'CONFIRMED' ? 'success' : 'neutral';
}

export function reminderKindLabel(code: string): string {
  switch (code) {
    case 'PICKUP_DUE_SOON':
      return '即将超时未揽收';
    case 'PICKUP_OVERDUE':
      return '超时未揽收';
    case 'DELIVERY_CONFIRMED':
      return '已签收待跟进';
    default:
      return code || '未知';
  }
}

export function reminderStatusLabel(code: string): string {
  switch (code) {
    case 'PENDING':
      return '待处理';
    case 'ACKNOWLEDGED':
      return '已认领';
    case 'RESOLVED':
      return '已处置';
    case 'CANCELLED':
      return '已失效';
    default:
      return code || '未知';
  }
}

export function notificationKindLabel(code: string): string {
  switch (code) {
    case 'PLATFORM_FULFILLMENT_WRITEBACK':
      return '平台履约回传';
    case 'REVIEW_INVITATION':
      return '评价邀请';
    case 'AFTER_SALES_WINDOW_CLOSE':
      return '售后窗口关闭';
    default:
      return code || '未知';
  }
}

export function notificationStatusLabel(code: string): string {
  switch (code) {
    case 'PENDING':
      return '未到时机';
    case 'READY':
      return '到时机 · 待下游消费';
    case 'CONSUMED':
      return '下游已消费';
    case 'FAILED':
      return '消费失败';
    case 'CANCELLED':
      return '已失效';
    default:
      return code || '未知';
  }
}

export function notificationStatusTone(code: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (code) {
    case 'CONSUMED':
      return 'success';
    case 'READY':
      return 'warning';
    case 'FAILED':
      return 'danger';
    default:
      return 'neutral';
  }
}

export function gapLabel(code: string): string {
  switch (code) {
    case '':
      return '';
    case 'NO_WAYBILL':
      return '缺运单号';
    case 'TRACKING_SUBJECT_MISSING':
      return '缺轨迹主体';
    case 'SUBJECT_AMBIGUOUS':
      return '运单号不唯一';
    case 'BATCH_VOID':
      return '批次已作废';
    case 'SLA_UNCONFIGURED':
      return '未配时限规则';
    default:
      return code;
  }
}

export function evidenceSourceLabel(code: string): string {
  switch (code) {
    case '':
      return '';
    case 'CARRIER_EVENT':
      return '承运商事件';
    case 'MANUAL_ATTESTATION':
      return '人工登记';
    default:
      return code;
  }
}

export function signoffEventKindLabel(code: string): string {
  switch (code) {
    case 'CASE_CREATED':
      return '建单';
    case 'SLA_APPLIED':
      return '补上时限';
    case 'PICKUP_CONFIRMED':
      return '确认揽收';
    case 'PICKUP_DUE_SOON':
      return '即将超时';
    case 'PICKUP_OVERDUE':
      return '超时未揽收';
    case 'DELIVERY_CONFIRMED':
      return '确认签收';
    case 'EXCEPTION_MARKED':
      return '标记异常';
    case 'VOIDED':
      return '作废';
    case 'ATTESTED':
      return '人工确认';
    default:
      return code || '未知';
  }
}

// ====================================================================
// 展示
// ====================================================================

/**
 * 时刻展示：只做格式化，**不做时区换算**。
 *
 * <p>刻意不用 `toLocaleString()`：那会把服务端给的偏移量换算成浏览器本地时区，
 * 「这一件几点被揽收」在跨时区团队里就各说各的。
 */
export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  return value.replace('T', ' ').replace(/\.\d+/, '').replace(/([+-]\d{2}:\d{2}|Z)$/, ' $1');
}

/** 小时展示：保留后端给的精度（numeric(19,6)），不做单位换算。 */
export function formatHours(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${String(value)} 小时`;
}

/** 时限口径一句话：快照语义要说出来。 */
export function deadlineText(row: PickupSignoffCaseRow): string {
  if (!row.pickupDeadlineAt) return '未配时限规则（判不了超时）';
  return `${formatMoment(row.pickupDeadlineAt)}（建单时快照 ${formatHours(row.pickupSlaHours)}）`;
}

/** 揽收口径一句话：证据来源要写出来（承运商事件 vs 人工登记）。 */
export function pickupText(row: PickupSignoffCaseRow): string {
  if (row.pickupState === 'CONFIRMED') {
    const source = evidenceSourceLabel(row.pickupEvidenceSource);
    const ref = row.pickupEvidenceRef ? ` · ${row.pickupEvidenceRef}` : '';
    return `${formatMoment(row.pickupConfirmedAt)} · ${source}${ref}`;
  }
  if (row.pickupState === 'OVERDUE') {
    return `已超时 ${formatHours(row.overdueHours)}（截止 ${formatMoment(row.pickupDeadlineAt)}）`;
  }
  return '未确认揽收';
}

/** 签收口径一句话。 */
export function deliveryText(row: PickupSignoffCaseRow): string {
  if (row.deliveryState === 'CONFIRMED') {
    const source = evidenceSourceLabel(row.deliveryEvidenceSource);
    const ref = row.deliveryEvidenceRef ? ` · ${row.deliveryEvidenceRef}` : '';
    return `${formatMoment(row.deliveryConfirmedAt)} · ${source}${ref}`;
  }
  return '未确认签收';
}

/** 缺口说明：有缺口就把原因写出来，不装作「正常」。 */
export function gapText(row: PickupSignoffCaseRow): string {
  if (!row.gapCode) return '';
  return row.gapReason || gapLabel(row.gapCode);
}

// ====================================================================
// 查询串
// ====================================================================

/** 确认单清单的查询串：只放非空筛选，页码与页长总是带上。 */
export function buildCaseQuery(filters: PickupSignoffFilters): string {
  const query = new URLSearchParams();
  if (filters.carrierCode) query.set('carrierCode', filters.carrierCode);
  if (filters.status) query.set('status', filters.status);
  if (filters.pickupState) query.set('pickupState', filters.pickupState);
  if (filters.deliveryState) query.set('deliveryState', filters.deliveryState);
  if (filters.batchNo) query.set('batchNo', filters.batchNo);
  if (filters.gapCode) query.set('gapCode', filters.gapCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.overdueOnly) query.set('overdueOnly', 'true');
  if (filters.exceptionOnly) query.set('exceptionOnly', 'true');
  query.set('sort', filters.sort);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return query.toString();
}

// ====================================================================
// 表单校验
// ====================================================================

/** 登记时限规则前的校验。小时一律**正数**，且必须给揽收时限。 */
export function validateSlaRuleInput(input: PickupSignoffSlaRuleInput): string[] {
  const problems: string[] = [];
  if (!input.carrierCode.trim()) problems.push('承运商代码不能为空');
  if (input.pickupSlaHours === null || input.pickupSlaHours <= 0)
    problems.push('揽收时限必须大于 0 小时');
  if (input.reminderLeadHours !== null && input.reminderLeadHours < 0)
    problems.push('提醒提前量不能为负');
  if (input.reviewInviteDelayHours !== null && input.reviewInviteDelayHours < 0)
    problems.push('评价邀请延迟不能为负');
  if (input.afterSalesWindowHours !== null && input.afterSalesWindowHours <= 0)
    problems.push('售后窗口必须大于 0 小时');
  if (input.status !== 'ACTIVE' && input.status !== 'DISABLED')
    problems.push('规则状态只能是 ACTIVE 或 DISABLED');
  return problems;
}

/** 人工确认前的校验。凭据号**必填**（没有凭据号的确认无法用于事后追责）。 */
export function validateAttestInput(input: {
  kind: string;
  evidenceRef: string;
}): string[] {
  const problems: string[] = [];
  if (input.kind !== 'PICKUP' && input.kind !== 'DELIVERY')
    problems.push('确认类型只能是 PICKUP（揽收）或 DELIVERY（签收）');
  if (!input.evidenceRef.trim()) problems.push('凭据号不能为空');
  else if (input.evidenceRef.length > 128) problems.push('凭据号最多 128 个字符');
  return problems;
}

// ====================================================================
// 取数
// ====================================================================

export function fetchPickupSignoffCapabilities(): Promise<LogisticsPickupSignoffCapabilities> {
  return api<LogisticsPickupSignoffCapabilities>('channel/logistics-pickup-signoff/capabilities');
}

/** 概览：全量口径，不受列表筛选与分页影响。 */
export function fetchPickupSignoffOverview(): Promise<PickupSignoffSummary> {
  return api<PickupSignoffSummary>('channel/logistics-pickup-signoff/overview');
}

export function fetchPickupSignoffCases(
  filters: PickupSignoffFilters,
): Promise<PickupSignoffCasePage> {
  return api<PickupSignoffCasePage>(
    `channel/logistics-pickup-signoff/cases?${buildCaseQuery(filters)}`,
  );
}

export function fetchPickupSignoffCase(id: string): Promise<PickupSignoffCaseDetail> {
  return api<PickupSignoffCaseDetail>(`channel/logistics-pickup-signoff/cases/${id}`);
}

export function fetchPickupSignoffReminders(
  filters: PickupSignoffReminderFilters,
): Promise<PickupSignoffReminderPage> {
  const query = new URLSearchParams();
  if (filters.carrierCode) query.set('carrierCode', filters.carrierCode);
  if (filters.reminderKind) query.set('reminderKind', filters.reminderKind);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<PickupSignoffReminderPage>(
    `channel/logistics-pickup-signoff/reminders?${query.toString()}`,
  );
}

export function fetchPickupSignoffNotifications(
  filters: PickupSignoffNotificationFilters,
): Promise<PickupSignoffNotificationPage> {
  const query = new URLSearchParams();
  if (filters.carrierCode) query.set('carrierCode', filters.carrierCode);
  if (filters.notificationKind) query.set('notificationKind', filters.notificationKind);
  if (filters.downstreamStatus) query.set('downstreamStatus', filters.downstreamStatus);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<PickupSignoffNotificationPage>(
    `channel/logistics-pickup-signoff/notifications?${query.toString()}`,
  );
}

export function fetchPickupSignoffSlaRules(
  filters: PickupSignoffReminderFilters,
): Promise<PickupSignoffSlaRulePage> {
  const query = new URLSearchParams();
  if (filters.carrierCode) query.set('carrierCode', filters.carrierCode);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<PickupSignoffSlaRulePage>(
    `channel/logistics-pickup-signoff/sla-rules?${query.toString()}`,
  );
}

/** 从 10.3.2.1 交运批次同步确认单（幂等投影）。 */
export function syncPickupSignoffCases(input: {
  carrierCode: string;
  batchNo: string;
}): Promise<PickupSignoffSyncResult> {
  return api<PickupSignoffSyncResult>('channel/logistics-pickup-signoff/cases/sync', 'POST', {
    carrierCode: input.carrierCode,
    batchNo: input.batchNo,
    page: 0,
    size: 200,
  });
}

/** 巡检：推进揽收/签收状态、判定超时、生成提醒与下游待通知。 */
export function reconcilePickupSignoff(input: {
  carrierCode: string;
  batchNo: string;
}): Promise<PickupSignoffReconcileResult> {
  return api<PickupSignoffReconcileResult>('channel/logistics-pickup-signoff/reconcile', 'POST', {
    carrierCode: input.carrierCode,
    batchNo: input.batchNo,
    page: 0,
    size: 200,
  });
}

/** 人工确认揽收 / 签收。 */
export function attestPickupSignoff(
  id: string,
  input: { kind: string; occurredAt: string; evidenceRef: string; note: string },
): Promise<PickupSignoffCaseDetail> {
  return api<PickupSignoffCaseDetail>(
    `channel/logistics-pickup-signoff/cases/${id}/attest`,
    'POST',
    input,
  );
}

/** 认领提醒。 */
export function acknowledgePickupSignoffReminder(
  id: string,
  note: string,
): Promise<PickupSignoffReminderRow> {
  return api<PickupSignoffReminderRow>(
    `channel/logistics-pickup-signoff/reminders/${id}/acknowledge`,
    'POST',
    { note },
  );
}

/** 处置提醒。 */
export function resolvePickupSignoffReminder(
  id: string,
  note: string,
): Promise<PickupSignoffReminderRow> {
  return api<PickupSignoffReminderRow>(
    `channel/logistics-pickup-signoff/reminders/${id}/resolve`,
    'POST',
    { note },
  );
}

/** 登记下游消费结果。 */
export function consumePickupSignoffNotification(
  id: string,
  result: 'CONSUMED' | 'FAILED',
  note: string,
): Promise<PickupSignoffNotificationRow> {
  return api<PickupSignoffNotificationRow>(
    `channel/logistics-pickup-signoff/notifications/${id}/consume`,
    'POST',
    { result, note },
  );
}

/** 登记 / 改时限规则。 */
export function registerPickupSignoffSlaRule(
  input: PickupSignoffSlaRuleInput,
): Promise<PickupSignoffSlaRuleRow> {
  return api<PickupSignoffSlaRuleRow>('channel/logistics-pickup-signoff/sla-rules', 'POST', input);
}
