import { api } from '../inventory/api';

/**
 * 10.3.2.1 交运批次前端模型。
 *
 * <p>蓝图加粗句：**批次交接是责任分界点——批次内包裹的丢失责任在承运商，
 * 需要交接清单留证**。
 *
 * <p>这个约束在界面上的直接后果是：交接清单**只读**。本页**不提供**任何
 * 「编辑清单」「删除一批」入口 —— 一旦界面上出现编辑框，就等于在产品层面承认
 * 交接凭据可以改写，而凭据一旦可改写，「责任在承运商」这句话就没有依据了。
 *
 * <p>能写的只有三件事，都是「登记事实」而不是「修数据」：组批（写清单）、
 * 登记交接、带回回执。
 */

export interface HandoverCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface HandoverCapabilities {
  dimensions: HandoverCapabilityDimension[];
  batchStatuses: string[];
  carrierChannels: string[];
  waybillGapCodes: string[];
  receiptStatuses: string[];
  manifestActions: string[];
  carrierSourceNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface HandoverGroupBucket {
  carrierCode: string;
  carrierChannel: string;
  handoverBucket: string;
  handoverBucketLabel: string;
  unitCount: number;
  parcelCount: number;
  totalWeightKg: number;
  missingWaybillCount: number;
}

export interface HandoverCandidateSummary {
  pendingCount: number;
  batchedCount: number;
  pendingParcels: number;
  pendingWeightKg: number;
  missingWaybillCount: number;
  byCarrier: HandoverGroupBucket[];
  handoverDateOptions: string[];
}

export interface HandoverCandidateRow {
  id: string;
  sourceUnitId: string;
  sourcePackingOrderId: string;
  warehouseRef: string;
  outboundOrderNo: string;
  salesOrderNo: string;
  destinationCountry: string;
  carrierCode: string;
  carrierChannel: string;
  waybillNo: string;
  waybillGapReason: string;
  waybillGapCode: string;
  parcelCount: number;
  totalWeightKg: number;
  status: string;
  batchId?: string | null;
  packedAt: string;
  handedOverAt: string;
  syncedAt: string;
}

export interface HandoverCandidatePage {
  items: HandoverCandidateRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  summary: HandoverCandidateSummary;
}

export interface HandoverBatchRow {
  id: string;
  batchNo: string;
  carrierCode: string;
  carrierChannel: string;
  handoverAt: string;
  handoverBucket: string;
  unitCount: number;
  parcelCount: number;
  totalWeightKg: number;
  status: string;
  statusDetail: string;
  statusLabel: string;
  dispatchedByMembershipId?: string | null;
  dispatchedAt?: string | null;
  receiptRef: string;
  receiptStatus: string;
  receiptStatusLabel: string;
  receiptDetail: string;
  receiptRecordedAt?: string | null;
  manifestCount: number;
  version: number;
  createdAt: string;
}

export interface HandoverStatusBucket {
  status: string;
  statusLabel: string;
  batchCount: number;
  parcelCount: number;
  totalWeightKg: number;
}

export interface HandoverBatchSummary {
  draftCount: number;
  dispatchPendingCount: number;
  handedOverCount: number;
  cancelledCount: number;
  handedOverParcels: number;
  handedOverWeightKg: number;
  receiptMismatchCount: number;
  byStatus: HandoverStatusBucket[];
  byCarrier: HandoverGroupBucket[];
}

export interface HandoverBatchPage {
  items: HandoverBatchRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  summary: HandoverBatchSummary;
}

export interface HandoverManifestItemRow {
  id: string;
  batchId: string;
  candidateId: string;
  parcelNo: number;
  parcelRef: string;
  warehouseRef: string;
  outboundOrderNo: string;
  carrierCode: string;
  waybillNo: string;
  waybillGapReason: string;
  waybillGapCode: string;
  grossWeightKg: number;
  lengthMm: number;
  widthMm: number;
  heightMm: number;
  remark: string;
  handoverAt: string;
  handedByMembershipId: string;
  createdAt: string;
}

export interface HandoverBatchEventRow {
  id: string;
  batchId: string;
  candidateId?: string | null;
  eventKind: string;
  fromStatus: string;
  toStatus: string;
  detail: string;
  actorMembershipId?: string | null;
  occurredAt: string;
}

export interface HandoverBatchDetail {
  batch: HandoverBatchRow;
  manifest: HandoverManifestItemRow[];
  timeline: HandoverBatchEventRow[];
  completionCriteria: string[];
  boundaryNotes: string[];
}

export interface HandoverManifestExport {
  batchId: string;
  batchNo: string;
  carrierCode: string;
  carrierChannel: string;
  handoverAt: string;
  status: string;
  statusLabel: string;
  itemCount: number;
  totalWeightKg: number;
  items: HandoverManifestItemRow[];
  boundaryNotes: string[];
}

export interface SyncCandidatesResult {
  fetched: number;
  created: number;
  updated: number;
  carrierCode: string;
  syncedAt: string;
  note: string;
}

export interface ManifestItemInput {
  candidateId: string;
  parcelNo: number;
  parcelRef: string;
  waybillNo: string;
  waybillGapCode: string;
  waybillGapReason: string;
  grossWeightKg: number;
  lengthMm: number;
  widthMm: number;
  heightMm: number;
  remark: string;
}

// ================================================================== 端点

export function fetchCapabilities(): Promise<HandoverCapabilities> {
  return api<HandoverCapabilities>('channel/handover-batches/capabilities');
}

export function fetchCandidates(
  carrierCode?: string,
  warehouseRef?: string,
): Promise<HandoverCandidatePage> {
  const query = new URLSearchParams();
  if (carrierCode) query.set('carrierCode', carrierCode);
  if (warehouseRef) query.set('warehouseRef', warehouseRef);
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return api<HandoverCandidatePage>(`channel/handover-batches/candidates${suffix}`);
}

export function syncCandidates(carrierCode?: string): Promise<SyncCandidatesResult> {
  return api<SyncCandidatesResult>('channel/handover-batches/candidates/sync', 'POST', {
    carrierCode: carrierCode ?? '',
    page: 0,
    size: 200,
  });
}

export function fetchGroupPreview(carrierCode?: string): Promise<HandoverGroupBucket[]> {
  const query = carrierCode ? `?carrierCode=${encodeURIComponent(carrierCode)}` : '';
  return api<HandoverGroupBucket[]>(`channel/handover-batches/group-preview${query}`);
}

export function fetchBatches(status?: string): Promise<HandoverBatchPage> {
  const query = status ? `?status=${encodeURIComponent(status)}` : '';
  return api<HandoverBatchPage>(`channel/handover-batches${query}`);
}

export function fetchBatchDetail(id: string): Promise<HandoverBatchDetail> {
  return api<HandoverBatchDetail>(`channel/handover-batches/${id}`);
}

export function fetchManifest(id: string): Promise<HandoverManifestExport> {
  return api<HandoverManifestExport>(`channel/handover-batches/${id}/manifest`);
}

export function createBatch(
  carrierCode: string,
  handoverAt: string,
  items: ManifestItemInput[],
  note?: string,
): Promise<HandoverBatchDetail> {
  return api<HandoverBatchDetail>('channel/handover-batches', 'POST', {
    carrierCode,
    handoverAt,
    warehouseRef: '',
    items,
    note: note ?? '',
  });
}

export function dispatchBatch(
  id: string,
  handoverAt: string,
  note: string,
): Promise<HandoverBatchDetail> {
  return api<HandoverBatchDetail>(`channel/handover-batches/${id}/dispatch`, 'POST', {
    handoverAt,
    note,
  });
}

export function recordReceipt(
  id: string,
  receiptRef: string,
  receiptStatus: string,
  receiptDetail: string,
): Promise<HandoverBatchDetail> {
  return api<HandoverBatchDetail>(`channel/handover-batches/${id}/receipt`, 'POST', {
    receiptRef,
    receiptStatus,
    receiptDetail,
    receiptRecordedAt: new Date().toISOString(),
  });
}

export function cancelBatch(id: string, note: string): Promise<HandoverBatchDetail> {
  return api<HandoverBatchDetail>(
    `channel/handover-batches/${id}/cancel?note=${encodeURIComponent(note)}`,
    'POST',
  );
}

// ================================================================== 展示与校验

export function batchStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '已组批待交接';
    case 'DISPATCH_PENDING':
      return '已登记交接 · 待承运商接通';
    case 'HANDED_OVER':
      return '已交接 · 责任在承运商';
    case 'CANCELLED':
      return '已作废';
    default:
      // 未知枚举值回落到原值，不静默吞掉：界面必须看得出这是一个没见过的状态
      return status || '（空）';
  }
}

export function batchStatusTone(status: string): 'success' | 'warning' | 'neutral' | 'info' {
  switch (status) {
    case 'HANDED_OVER':
      return 'success';
    case 'DISPATCH_PENDING':
      return 'warning';
    case 'DRAFT':
      return 'info';
    default:
      return 'neutral';
  }
}

export function carrierChannelLabel(channel: string): string {
  switch (channel) {
    case 'PLATFORM_INTEGRATED':
      return '平台面单';
    case 'THIRD_PARTY':
      return '第三方物流';
    case 'SELF_PICKUP':
      return '自提';
    case 'POSTAL':
      return '邮政';
    case 'FREIGHT_FORWARDER':
      return '货代';
    default:
      return channel || '（未指定）';
  }
}

export function waybillGapLabel(code: string): string {
  switch (code) {
    case 'UPSTREAM_NOT_ISSUED':
      return '上游尚未签发';
    case 'UPSTREAM_UNREADABLE':
      return '上游通道不可读';
    case 'MANUAL_OVERRIDE':
      return '人工指定';
    default:
      return code || '未说明';
  }
}

export function receiptStatusLabel(status: string): string {
  switch (status) {
    case 'ACCEPTED':
      return '全部接收';
    case 'ACCEPTED_WITH_DISCREPANCY':
      return '有差异接收';
    case 'REJECTED':
      return '拒收';
    default:
      return '';
  }
}

/**
 * 运单号展示文案。
 *
 * <p>🔴 关键：**没有运单号时不能显示空白，也不能拿别的号顶上**。
 * 10.2.1.2 运单申请未交付时运单号可能真的还没有来源，此时必须显示
 * 「缺口 + 原因」—— 空白在界面上与「这一件压根没有运单号」无法区分，
 * 而 9.4.1.1 已经踩过一次并明确禁止拿出库单号冒充运单号。
 */
export function waybillText(
  waybillNo: string,
  gapCode: string,
  gapReason: string,
): { text: string; missing: boolean } {
  if (waybillNo.trim()) return { text: waybillNo.trim(), missing: false };
  const label = waybillGapLabel(gapCode);
  return { text: gapReason.trim() ? `${label}：${gapReason.trim()}` : label, missing: true };
}

/** 重量按公斤显示，保留三位；禁浮点记账在前端同样不做二次累加。 */
export function weightText(kg: number): string {
  if (!Number.isFinite(kg)) return '—';
  return `${kg.toFixed(3)} kg`;
}

/** 尺寸按毫米显示成「长×宽×高」。 */
export function dimensionText(lengthMm: number, widthMm: number, heightMm: number): string {
  if (!lengthMm || !widthMm || !heightMm) return '—';
  return `${lengthMm}×${widthMm}×${heightMm} mm`;
}

/**
 * 组批前的本地校验。
 *
 * <p>返回**逐条**原因而不是一条笼统的「请检查输入」—— 现场一次要交几十件，
 * 只回一句「有误」等于让人自己一行行找。
 */
export function validateBatch(
  carrierCode: string,
  handoverAt: string,
  items: ManifestItemInput[],
): string[] {
  const problems: string[] = [];
  if (!carrierCode.trim()) problems.push('承运商必填：一条交运批次不许跨承运商');
  if (!handoverAt.trim()) problems.push('交运时间必填');
  if (items.length === 0) {
    problems.push('交接清单不能为空：没有逐件清单就没有留证');
    return problems;
  }
  const seen = new Set<string>();
  items.forEach((item, index) => {
    const tag = `第 ${index + 1} 件`;
    if (!item.candidateId) problems.push(`${tag}：缺少候选包裹标识`);
    if (item.parcelNo <= 0) problems.push(`${tag}：包裹序号必须为正整数`);
    const key = `${item.candidateId}#${item.parcelNo}`;
    if (seen.has(key)) problems.push(`${tag}：同一件包裹在清单里出现了两次`);
    seen.add(key);
    if (!item.waybillNo.trim() && !item.waybillGapReason.trim()) {
      problems.push(`${tag}：运单号为空时必须写明缺口原因（绝不出库单号冒充运单号）`);
    }
    if (item.grossWeightKg < 0) problems.push(`${tag}：重量不能为负`);
  });
  return problems;
}

/**
 * 从候选行派生逐件清单输入。
 *
 * <p>运单号直接取候选读数，**没有就留空并带上缺口原因**，不在前端编一个。
 */
export function manifestInputFromCandidate(
  candidate: HandoverCandidateRow,
  parcelNo: number,
  weightKg: number,
  remark = '',
): ManifestItemInput {
  const missing = !candidate.waybillNo.trim();
  return {
    candidateId: candidate.id,
    parcelNo,
    parcelRef: `${candidate.outboundOrderNo}-${parcelNo}`,
    waybillNo: candidate.waybillNo,
    waybillGapCode: missing ? candidate.waybillGapCode || 'UPSTREAM_NOT_ISSUED' : '',
    waybillGapReason: missing
      ? candidate.waybillGapReason || '上游尚未签发运单号（10.2.1.2 运单申请未交付）'
      : '',
    grossWeightKg: weightKg,
    lengthMm: 0,
    widthMm: 0,
    heightMm: 0,
    remark,
  };
}

/** 未交付能力维度清单，用于页面上的灰块（不假装有数据）。 */
export function unavailableDimensions(
  capabilities: HandoverCapabilities | null,
): HandoverCapabilityDimension[] {
  if (!capabilities) return [];
  return capabilities.dimensions.filter((d) => !d.available);
}

/**
 * 批次是否可登记交接。
 *
 * <p>清单为空时**不允许**交接：蓝图那句「需要交接清单留证」意味着没有清单的
 * 交接在事后无从追责，宁可现在拦住。
 */
export function canDispatch(batch: HandoverBatchRow): boolean {
  return batch.status === 'DRAFT' && batch.manifestCount > 0;
}

/** 批次是否可带回回执（已登记交接或已交接都可以）。 */
export function canRecordReceipt(batch: HandoverBatchRow): boolean {
  return batch.status === 'DISPATCH_PENDING' || batch.status === 'HANDED_OVER';
}

/** 批次是否可作废。已交接是责任分界终态，不可作废。 */
export function canCancel(batch: HandoverBatchRow): boolean {
  return batch.status === 'DRAFT' || batch.status === 'DISPATCH_PENDING';
}

/** 回执状态是否必须写明差异原因（有差异 / 拒收都要，否则无法发起理赔）。 */
export function receiptNeedsDetail(status: string): boolean {
  return status === 'ACCEPTED_WITH_DISCREPANCY' || status === 'REJECTED';
}
