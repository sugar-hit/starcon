import { api } from '../inventory/api';

/**
 * 10.3.1.1 轨迹查询前端模型。
 *
 * <p>蓝图：「查询并展示包裹物流轨迹：节点时间线（揽收-运输-清关-转运-派送-签收）、
 * 预计时效对比、异常节点标记，支持按承运商批量拉取」，输出「轨迹时间线 + 预计到达时间
 * + 异常标记」，并加粗「<b>按承运商事件 ID 去重</b>，同一节点重复推送不产生重复记录」。
 *
 * <p>🔴 页面最容易做成假活的两件事，这里都堵掉了：
 *
 * <ol>
 *   <li><b>去重不靠页面</b>：本页**从不**自己判断「这条事件是不是重复的」——
 *       重复与否由后端在 DB 唯一约束上判定，页面拿到的就是最终账本。
 *       页面上如果自己「按事件号去个重」，就等于把去重规则复制了一份到前端，
 *       而前端这份在绕过页面直接写库时**不生效**，正是蓝图加粗句要防的事。</li>
 *   <li><b>时效对比不靠页面算</b>：ON_TIME / AT_RISK / DELAYED / NO_PROMISE 由
 *       **后端读时**判定（是否延误取决于现在几点），页面只展示后端给的结论与两个来源
 *       （我方承诺 vs 承运商预计）。页面自己比一次就多出一套口径。</li>
 * </ol>
 *
 * <p>⚠️ 本页**不涉及任何金额**：运费成本归集与结算归 12 财务（未交付）。
 * 这一页自造一个运费列，就会造出第二套成本口径。
 *
 * <p>⚠️ 异常**只标记不处置**：联系承运商 / 理赔 / 补发 / 改址归 10.3.1.2（未交付），
 * 本页不提供任何处置入口。
 */

// ====================================================================
// 传输类型（与后端 TrackingQueryModels 一一对应）
// ====================================================================

export interface TrackingCapabilityDimension {
  dimension: string;
  description: string;
  /** false 时必须同时给出 ownerModule 与 impact —— 缺口要说清归属与影响。 */
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface TrackingCapabilities {
  dimensions: TrackingCapabilityDimension[];
  nodeTypes: string[];
  etaStatuses: string[];
  subjectStatuses: string[];
  eventSources: string[];
  pullTaskStatuses: string[];
  pullItemStatuses: string[];
  serviceLevels: string[];
  boundaryNote: string;
  highlight: string;
}

/** 轨迹主体行 = 一票已发货的轨迹汇总。 */
export interface TrackingSubjectRow {
  id: string;
  /** 上游 9.4.1.2 trade 的 shipment_record id（本服务不编造跨服务 uuid）。 */
  sourceShipmentRecordId: string;
  sourceRecordNo: string;
  salesOrderNo: string;
  outboundOrderNo: string;
  warehouseRef: string;
  carrierCode: string;
  masterWaybillNo: string;
  destinationCountry: string;
  channelId: string | null;
  channelCode: string | null;
  channelName: string | null;
  /** 公斤。重量单位换算在服务层做（禁浮点），页面只展示。 */
  grossWeightKg: number;
  serviceLevel: string;
  parcelCount: number;
  shippedAt: string | null;
  latestNode: string;
  latestNodeLabel: string;
  latestEventCode: string;
  latestDescription: string;
  latestOccurredAt: string | null;
  latestReceivedAt: string | null;
  eventCount: number;
  exceptionFlag: boolean;
  exceptionCount: number;
  lastExceptionCode: string;
  lastExceptionNote: string;
  lastExceptionAt: string | null;
  status: string;
  statusLabel: string;
  /** 后端**读时**算出的时效状态，不是存下来的静态结论。 */
  etaStatus: string;
  etaStatusLabel: string;
  /** 我方承诺（10.1.1.3 时效规则在发货当时的快照）。 */
  promisedEtaEarliestAt: string | null;
  promisedEtaAt: string | null;
  promisedTransitDaysMin: number | null;
  promisedTransitDaysMax: number | null;
  /** 承运商给的预计到达（承运商说的，不是我方承诺）。 */
  carrierEtaAt: string | null;
  /** 命中了几条时效承诺；>1 表示同一承运商有多个渠道都承诺了这个口径。 */
  matchedPromiseCount: number;
  syncedAt: string;
}

export interface TrackingCarrierBucket {
  carrierCode: string;
  subjectCount: number;
  exceptionCount: number;
}

export interface TrackingNodeBucket {
  nodeType: string;
  subjectCount: number;
}

export interface TrackingEtaBucket {
  etaStatus: string;
  subjectCount: number;
}

/** 汇总：全量口径（不受筛选与分页影响）。 */
export interface TrackingSubjectSummary {
  totalCount: number;
  exceptionCount: number;
  delayedCount: number;
  atRiskCount: number;
  noEtaCount: number;
  noPromiseCount: number;
  deliveredCount: number;
  inTransitCount: number;
  eventTotal: number;
  byCarrier: TrackingCarrierBucket[];
  byNode: TrackingNodeBucket[];
  byEtaStatus: TrackingEtaBucket[];
}

export interface TrackingSubjectPage {
  items: TrackingSubjectRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  summary: TrackingSubjectSummary;
}

export interface CarrierTrackingEventRow {
  id: string;
  subjectId: string;
  carrierCode: string;
  /** 🔴 去重键的一半：承运商自己的事件号。 */
  carrierEventId: string;
  nodeType: string;
  nodeLabel: string;
  eventCode: string;
  description: string;
  location: string;
  /** 承运商给的**发生时刻**，不是写入时刻。 */
  occurredAt: string;
  receivedAt: string;
  /** 承运商在**这一条**事件里给的预计到达（null = 这条没带）。 */
  estimatedDeliveryAt: string | null;
  source: string;
  exceptionCode: string;
  exceptionNote: string;
  exception: boolean;
  registeredByMembershipId: string | null;
  createdAt: string;
}

export interface TrackingEventPage {
  items: CarrierTrackingEventRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 预计时效对比：承诺（我方规则）与预计（承运商说的）是**两个来源**，不合并。 */
export interface TrackingEtaComparison {
  etaStatus: string;
  etaStatusLabel: string;
  promisedTransitDaysMin: number | null;
  promisedTransitDaysMax: number | null;
  promisedEtaEarliestAt: string | null;
  promisedEtaAt: string | null;
  carrierEtaAt: string | null;
  latestOccurredAt: string | null;
  note: string;
  matchedPromiseCount: number;
}

export interface TrackingTimelineDetail {
  subject: TrackingSubjectRow;
  timeline: CarrierTrackingEventRow[];
  eta: TrackingEtaComparison;
  boundaryNotes: string[];
}

export interface TrackingSyncResult {
  scanned: number;
  created: number;
  updated: number;
  skipped: number;
  totalElements: number;
  notes: string[];
}

export interface TrackingPullTaskRow {
  id: string;
  taskNo: string;
  carrierCode: string;
  status: string;
  statusLabel: string;
  triggerSource: string;
  requestedCount: number;
  succeededCount: number;
  duplicateCount: number;
  noEventCount: number;
  failedCount: number;
  insertedEventCount: number;
  startedAt: string | null;
  finishedAt: string | null;
  failureReason: string;
  createdByMembershipId: string | null;
  createdAt: string;
}

export interface TrackingPullTaskItemRow {
  id: string;
  subjectId: string | null;
  waybillNo: string;
  status: string;
  statusLabel: string;
  insertedEventCount: number;
  duplicateEventCount: number;
  detail: string;
}

export interface TrackingPullTaskDetail {
  task: TrackingPullTaskRow;
  items: TrackingPullTaskItemRow[];
}

export interface TrackingPullTaskPage {
  items: TrackingPullTaskRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ====================================================================
// 筛选
// ====================================================================

export interface TrackingFilters {
  carrierCode: string;
  destinationCountry: string;
  nodeType: string;
  etaStatus: string;
  status: string;
  keyword: string;
  exceptionOnly: boolean;
  sort: string;
  page: number;
  size: number;
}

export const EMPTY_TRACKING_FILTERS: TrackingFilters = {
  carrierCode: '',
  destinationCountry: '',
  nodeType: '',
  etaStatus: '',
  status: '',
  keyword: '',
  exceptionOnly: false,
  sort: 'LATEST_DESC',
  page: 0,
  size: 20,
};

/** 拉取任务的筛选（与主体清单分开：任务按承运商与时间看，主体按票看）。 */
export interface TrackingPullFilters {
  carrierCode: string;
  status: string;
  page: number;
  size: number;
}

export const EMPTY_TRACKING_PULL_FILTERS: TrackingPullFilters = {
  carrierCode: '',
  status: '',
  page: 0,
  size: 20,
};

/** 建一轮批量拉取的入参。 */
export interface TrackingPullTaskInput {
  carrierCode: string;
  waybillNos: string[];
  triggerSource: 'MANUAL' | 'SCHEDULED';
}

export const EMPTY_TRACKING_PULL_TASK: TrackingPullTaskInput = {
  carrierCode: '',
  waybillNos: [],
  triggerSource: 'MANUAL',
};

// ====================================================================
// 标签与语义色
// ====================================================================

/** 节点类型（与后端 V212 的 CHECK 同集合）。 */
export function nodeTypeLabel(code: string): string {
  switch (code) {
    case 'NO_TRACE':
      return '无轨迹';
    case 'PICKED_UP':
      return '已揽收';
    case 'IN_TRANSIT':
      return '运输中';
    case 'CUSTOMS':
      return '清关中';
    case 'TRANSFER':
      return '转运中';
    case 'OUT_FOR_DELIVERY':
      return '派送中';
    case 'DELIVERED':
      return '已签收';
    case 'EXCEPTION':
      return '异常';
    case 'RETURNED':
      return '已退回';
    default:
      return code || '未知';
  }
}

/** 时效状态：语义色跟着**严重度**走，不跟着颜色走。 */
export function etaStatusLabel(code: string): string {
  switch (code) {
    case 'ON_TIME':
      return '按时';
    case 'AT_RISK':
      return '有风险（承运商预计晚于承诺）';
    case 'DELAYED':
      return '已延误';
    case 'NO_PROMISE':
      return '无时效承诺（判不了延误）';
    case 'NO_ETA':
      return '承运商未给预计';
    default:
      return code || '未知';
  }
}

export function etaStatusTone(code: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (code) {
    case 'ON_TIME':
      return 'success';
    case 'AT_RISK':
      return 'warning';
    case 'DELAYED':
      return 'danger';
    default:
      // NO_PROMISE / NO_ETA 不是「准点」，是「判不了」—— 用中性色，不冒充好消息。
      return 'neutral';
  }
}

export function subjectStatusLabel(code: string): string {
  switch (code) {
    case 'ACTIVE':
      return '在途';
    case 'DELIVERED':
      return '已签收';
    case 'RETURNED':
      return '已退回';
    case 'VOID':
      return '已作废';
    default:
      return code || '未知';
  }
}

export function pullTaskStatusLabel(code: string): string {
  switch (code) {
    case 'PENDING':
      return '待执行';
    case 'RUNNING':
      return '执行中';
    case 'SUCCEEDED':
      return '全部取回';
    case 'PARTIAL':
      return '部分失败';
    case 'FAILED':
      return '整体失败';
    case 'NO_CHANNEL':
      return '承运商轨迹通道未接通';
    default:
      return code || '未知';
  }
}

export function pullTaskStatusTone(code: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (code) {
    case 'SUCCEEDED':
      return 'success';
    case 'PARTIAL':
      return 'warning';
    case 'FAILED':
      return 'danger';
    default:
      // NO_CHANNEL 是**能力缺口**，不是业务失败 —— 中性色，如实说「没接通」而不是「出错」。
      return 'neutral';
  }
}

export function pullItemStatusLabel(code: string): string {
  switch (code) {
    case 'PULLED':
      return '已取回';
    case 'DUPLICATE':
      return '全部重复';
    case 'NO_EVENT':
      return '承运商回复无轨迹';
    case 'FAILED':
      return '拉取失败';
    case 'SKIPPED':
      return '已跳过';
    default:
      return code || '未知';
  }
}

export function serviceLevelLabel(code: string): string {
  switch (code) {
    case 'ECONOMY':
      return '经济';
    case 'STANDARD':
      return '标准';
    case 'EXPRESS':
      return '特快';
    default:
      return code || '未知';
  }
}

export function eventSourceLabel(code: string): string {
  switch (code) {
    case 'CARRIER_PUSH':
      return '承运商推送';
    case 'CARRIER_PULL':
      return '批量拉取';
    case 'MANUAL_ATTESTATION':
      return '人工登记';
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
 * 「这个节点发生在几点」在跨时区团队里就各说各的。
 */
export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  return value.replace('T', ' ').replace(/\.\d+/, '').replace(/([+-]\d{2}:\d{2}|Z)$/, ' $1');
}

/** 重量展示：去掉无意义的尾随零，但**不做单位换算**（后端已换算成公斤）。 */
export function formatWeight(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${String(Number(value))} kg`;
}

/** 我方承诺（发货当时的时效口径快照）。 */
export function promiseText(row: TrackingSubjectRow): string {
  if (!row.promisedEtaAt) return '无时效承诺';
  const days =
    row.promisedTransitDaysMin !== null && row.promisedTransitDaysMax !== null
      ? ` ${row.promisedTransitDaysMin}~${row.promisedTransitDaysMax} 天`
      : '';
  return `${formatMoment(row.promisedEtaEarliestAt)} ~ ${formatMoment(row.promisedEtaAt)}${days}`;
}

/** 承运商预计（承运商说的，不是我方承诺 —— 两个来源不合并）。 */
export function carrierEtaText(row: TrackingSubjectRow): string {
  if (!row.carrierEtaAt) return '承运商未给预计';
  return formatMoment(row.carrierEtaAt);
}

/** 清单里那一行的时间线摘要。 */
export function latestText(row: TrackingSubjectRow): string {
  if (!row.latestOccurredAt) return '尚无轨迹';
  return `${row.latestNodeLabel || nodeTypeLabel(row.latestNode)} · ${formatMoment(row.latestOccurredAt)}`;
}

/** 异常摘要：没有异常码就说「未给异常码」，不装作说清了什么。 */
export function exceptionText(row: TrackingSubjectRow): string {
  if (!row.exceptionFlag) return '无异常节点';
  const code = row.lastExceptionCode || '未给异常码';
  const note = row.lastExceptionNote ? `：${row.lastExceptionNote}` : '';
  return `${code}${note}`;
}

// ====================================================================
// 查询串
// ====================================================================

/** 主体清单的查询串：只放非空筛选，页码与页长总是带上。 */
export function buildSubjectQuery(filters: TrackingFilters): string {
  const query = new URLSearchParams();
  if (filters.carrierCode) query.set('carrierCode', filters.carrierCode);
  if (filters.destinationCountry) query.set('destinationCountry', filters.destinationCountry);
  if (filters.nodeType) query.set('nodeType', filters.nodeType);
  if (filters.etaStatus) query.set('etaStatus', filters.etaStatus);
  if (filters.status) query.set('status', filters.status);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.exceptionOnly) query.set('exceptionOnly', 'true');
  query.set('sort', filters.sort);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return query.toString();
}

// ====================================================================
// 表单校验
// ====================================================================

/** 运单号列表：按行 / 逗号 / 空格分隔，去空去重。 */
export function parseWaybillList(raw: string): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const piece of raw.split(/[\s,;]+/)) {
    const value = piece.trim();
    if (!value || seen.has(value)) continue;
    seen.add(value);
    out.push(value);
  }
  return out;
}

/** 建轮批量拉取前的校验。运单号**可空**（空 = 拉该承运商下全部在跟踪的运单）。 */
export function validatePullTaskInput(input: TrackingPullTaskInput): string[] {
  const problems: string[] = [];
  if (!input.carrierCode.trim()) problems.push('承运商代码不能为空');
  if (input.waybillNos.length > 500) problems.push('一次最多 500 票');
  for (const waybill of input.waybillNos) {
    if (waybill.length > 64) problems.push(`运单号 ${waybill} 超过 64 个字符`);
  }
  if (input.triggerSource !== 'MANUAL' && input.triggerSource !== 'SCHEDULED')
    problems.push('触发来源只能是 MANUAL 或 SCHEDULED');
  return problems;
}

// ====================================================================
// 取数
// ====================================================================

export function fetchTrackingCapabilities(): Promise<TrackingCapabilities> {
  return api<TrackingCapabilities>('channel/logistics-tracking/capabilities');
}

/** 概览：全量口径，不受列表筛选与分页影响。 */
export function fetchTrackingOverview(): Promise<TrackingSubjectSummary> {
  return api<TrackingSubjectSummary>('channel/logistics-tracking/overview');
}

export function fetchTrackingSubjects(filters: TrackingFilters): Promise<TrackingSubjectPage> {
  return api<TrackingSubjectPage>(
    `channel/logistics-tracking/subjects?${buildSubjectQuery(filters)}`,
  );
}

export function fetchTrackingSubject(id: string): Promise<TrackingTimelineDetail> {
  return api<TrackingTimelineDetail>(`channel/logistics-tracking/subjects/${id}`);
}

export function fetchTrackingEvents(
  filters: TrackingFilters,
): Promise<TrackingEventPage> {
  const query = new URLSearchParams();
  if (filters.carrierCode) query.set('carrierCode', filters.carrierCode);
  if (filters.nodeType) query.set('nodeType', filters.nodeType);
  if (filters.exceptionOnly) query.set('exceptionOnly', 'true');
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<TrackingEventPage>(`channel/logistics-tracking/events?${query.toString()}`);
}

export function fetchTrackingPullTasks(
  filters: TrackingPullFilters,
): Promise<TrackingPullTaskPage> {
  const query = new URLSearchParams();
  if (filters.carrierCode) query.set('carrierCode', filters.carrierCode);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<TrackingPullTaskPage>(`channel/logistics-tracking/pull-tasks?${query.toString()}`);
}

export function fetchTrackingPullTask(id: string): Promise<TrackingPullTaskDetail> {
  return api<TrackingPullTaskDetail>(`channel/logistics-tracking/pull-tasks/${id}`);
}

/** 从上游 9.4.1.2 同步运单集合（幂等投影）。 */
export function syncTrackingSubjects(input: {
  carrierCode: string;
  serviceLevel: string;
  page: number;
  size: number;
}): Promise<TrackingSyncResult> {
  return api<TrackingSyncResult>('channel/logistics-tracking/subjects/sync', 'POST', input);
}

/** 建一轮「按承运商批量拉取」并执行。 */
export function createTrackingPullTask(
  input: TrackingPullTaskInput,
): Promise<TrackingPullTaskDetail> {
  return api<TrackingPullTaskDetail>('channel/logistics-tracking/pull-tasks', 'POST', input);
}
