import { api, ApiError } from '../../../lib/api';

/**
 * 10.7.1.2 退运回国前端模型。
 *
 * <p>蓝图：「管理退运回国：<b>批量退运单（凑单/合箱/报关）</b>、国内收货仓、关税与申报、
 * 回国后的重新入库或报废；<b>支持海运拼箱的成本分摊</b>。」输入：待退运清单；
 * 报关资料（3.7）；运输方式与成本。输出：退运单 + 报关资料；回国收货记录 + 重新入库分录；
 * 退运成本。
 *
 * <p>🔴 这个界面上**最容易做成假活**的两处：
 *
 * <ol>
 *   <li><b>「合单 / 凑单」不能只是页面上的一句标题</b>：{@link buildConsignmentPayload}
 *       一次提交**多条**处置单（lines），页面必须能勾选多行一起成单。</li>
 *   <li><b>「成本分摊」不能只是留一个空字段</b>：页面把服务端的逐行分摊
 *       （权重 / 权重合计 / 比例 / 分摊金额 / 尾差）原样摊开，
 *       并用 {@link allocationSum} 与 {@link allocationBalanced} 做**自检**：
 *       合计必须等于该笔成本金额，尾差不许丢。</li>
 * </ol>
 *
 * <p>🔴 币种纪律：成本币种、报关币种、残值币种是**三个独立口径**，
 * 页面**不做任何汇率换算**，三者**永远不相加**（{@link moneyText} 只做展示）。
 */

// ================================================================ 枚举词表

/** 退运单状态（展示名以服务端 capabilities 为准，这里只做兜底）。 */
export type ShipbackStatus =
  | 'DRAFT'
  | 'CONFIRMED'
  | 'DISPATCHED'
  | 'ARRIVED'
  | 'CLOSED'
  | 'CANCELLED';

/** 运输方式。 */
export type TransportMode =
  | 'SEA_CONSOLIDATION'
  | 'SEA_FULL_CONTAINER'
  | 'AIR'
  | 'EXPRESS'
  | 'TRUCK'
  | 'OTHER';

/** 成本分摊口径。 */
export type AllocationBasis = 'WEIGHT' | 'QUANTITY' | 'DECLARED_VALUE' | 'EQUAL';

/** 入库 / 报废去向。 */
export type EntryType = 'RESTOCK' | 'SCRAP';

// ================================================================ 形状

export interface ReturnShipbackCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string | null;
  impact: string;
}

export interface ReturnShipbackCapabilities {
  dimensions: ReturnShipbackCapabilityDimension[];
  statuses: string[];
  transportModes: string[];
  costTypes: string[];
  allocationBases: string[];
  declarationTypes: string[];
  entryTypes: string[];
  consolidationNote: string;
  allocationNote: string;
  currencyNote: string;
  customsNote: string;
  financeNote: string;
  inventoryNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface CurrencyTotal {
  currency: string;
  amount: number;
  itemCount: number;
}

export interface ReturnShipbackOverview {
  consignmentCount: number;
  draftCount: number;
  confirmedCount: number;
  dispatchedCount: number;
  arrivedCount: number;
  closedCount: number;
  cancelledCount: number;
  candidateCount: number;
  candidateQty: number;
  lineCount: number;
  costTotals: CurrencyTotal[];
  declaredValueTotals: CurrencyTotal[];
  restockEntryCount: number;
  scrapEntryCount: number;
  evaluatedAt: string;
  note: string;
  currencyNote: string;
}

export interface ShipbackCandidateRow {
  dispositionOrderId: string;
  dispositionNo: string;
  decisionId: string;
  receiptNo: string;
  skuCode: string;
  skuName: string;
  batchNo: string;
  brandCode: string;
  platformCode: string;
  disposedQty: number;
  salvageAmount: number;
  salvageCurrency: string;
  dispositionMethod: string;
  dispositionMethodLabel: string;
  issuedAt: string;
  note: string;
}

export interface ShipbackCandidatePage {
  rows: ShipbackCandidateRow[];
  page: number;
  size: number;
  total: number;
  totalQty: number;
  evaluatedAt: string;
  note: string;
}

export interface ReturnShipbackRow {
  id: string;
  shipbackNo: string;
  status: string;
  statusLabel: string;
  transportMode: string;
  transportModeLabel: string;
  originCountry: string;
  destinationCountry: string;
  originWarehouseRef: string;
  domesticWarehouseRef: string;
  carrierRef: string;
  trackingNo: string;
  totalQty: number;
  totalWeightKg: number;
  totalVolumeM3: number;
  costCurrency: string;
  customsStatus: string;
  customsStatusLabel: string;
  customsDeclarationNo: string;
  lineCount: number;
  totalCost: number;
  plannedDepartureAt: string | null;
  plannedArrivalAt: string | null;
  actualDepartureAt: string | null;
  actualArrivalAt: string | null;
  createdAt: string;
  consolidationNote: string;
  note: string;
  version: number;
}

export interface ReturnShipbackPage {
  rows: ReturnShipbackRow[];
  page: number;
  size: number;
  total: number;
  totalCost: number;
  evaluatedAt: string;
  note: string;
}

export interface ReturnShipbackLineRow {
  id: string;
  consignmentId: string;
  dispositionOrderId: string;
  decisionId: string;
  dispositionNo: string;
  receiptNo: string;
  skuCode: string;
  skuName: string;
  batchNo: string;
  brandCode: string;
  platformCode: string;
  qty: number;
  weightKg: number;
  volumeM3: number;
  hsCode: string;
  declaredItemName: string;
  declaredValue: number;
  declaredCurrency: string;
  salvageAmount: number;
  salvageCurrency: string;
  allocatedCost: number;
  status: string;
  statusLabel: string;
  note: string;
}

export interface ReturnShipbackDeclarationRow {
  id: string;
  consignmentId: string;
  lineId: string | null;
  sourceModule: string;
  sourceReference: string;
  declarationNo: string;
  declarationType: string;
  declarationTypeLabel: string;
  hsCode: string;
  declaredItemName: string;
  declaredQty: number;
  declaredValue: number;
  declaredCurrency: string;
  tariffRate: number;
  tariffAmount: number;
  taxAmount: number;
  totalAmount: number;
  status: string;
  statusLabel: string;
  submittedAt: string | null;
  clearedAt: string | null;
  note: string;
}

export interface ReturnShipbackCostRow {
  id: string;
  consignmentId: string;
  costType: string;
  costTypeLabel: string;
  amount: number;
  currency: string;
  allocationBasis: string;
  allocationBasisLabel: string;
  vendorRef: string;
  incurredAt: string;
  allocatedTotal: number;
  note: string;
}

/**
 * 🔴 成本分摊明细。
 *
 * <p>{@code allocationRatio} 是 scale 4 的比例；{@code residualAmount} 是**尾差调整额**
 * （把「总额 − 逐笔四舍五入之和」补到某一行上），于是
 * {@code SUM(allocatedAmount) 恒等于该笔成本金额}。
 */
export interface ReturnShipbackAllocationRow {
  id: string;
  consignmentId: string;
  lineId: string;
  costId: string;
  requestedBasis: string;
  requestedBasisLabel: string;
  appliedBasis: string;
  appliedBasisLabel: string;
  basisValue: number;
  basisTotal: number;
  allocationRatio: number;
  allocatedAmount: number;
  currency: string;
  residualAmount: number;
  skuCode: string;
  skuName: string;
}

export interface ShipbackAllocationResult {
  costId: string;
  costType: string;
  amount: number;
  currency: string;
  requestedBasis: string;
  appliedBasis: string;
  basisFallback: boolean;
  totalAllocated: number;
  allocations: ReturnShipbackAllocationRow[];
  note: string;
}

export interface ReturnShipbackArrivalRow {
  id: string;
  consignmentId: string;
  domesticWarehouseRef: string;
  arrivedAt: string;
  receivedQty: number;
  damagedQty: number;
  shortageQty: number;
  receivedBy: string;
  note: string;
}

export interface ReturnShipbackEntryRow {
  id: string;
  consignmentId: string;
  lineId: string;
  entryType: string;
  entryTypeLabel: string;
  qty: number;
  skuCode: string;
  skuName: string;
  batchNo: string;
  domesticWarehouseRef: string;
  entryNo: string;
  postingStatus: string;
  consumerModule: string;
  issuedAt: string;
  issuedBy: string;
  note: string;
}

export interface ReturnShipbackEventRow {
  id: string;
  eventType: string;
  eventTypeLabel: string;
  consignmentId: string | null;
  lineId: string | null;
  costId: string | null;
  declarationId: string | null;
  entryId: string | null;
  actorId: string;
  occurredAt: string;
  detail: string;
}

export interface ReturnShipbackEventPage {
  rows: ReturnShipbackEventRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
}

export interface ReturnShipbackDetail {
  head: ReturnShipbackRow;
  lines: ReturnShipbackLineRow[];
  declarations: ReturnShipbackDeclarationRow[];
  costs: ReturnShipbackCostRow[];
  allocations: ReturnShipbackAllocationRow[];
  arrivals: ReturnShipbackArrivalRow[];
  entries: ReturnShipbackEntryRow[];
  events: ReturnShipbackEventRow[];
  nextActions: string[];
  note: string;
}

// ================================================================ 取数

const BASE = 'channel/return-shipbacks';

export function fetchCapabilities(): Promise<ReturnShipbackCapabilities> {
  return api<ReturnShipbackCapabilities>(`${BASE}/capabilities`);
}

export function fetchOverview(): Promise<ReturnShipbackOverview> {
  return api<ReturnShipbackOverview>(`${BASE}/overview`);
}

export interface CandidateQuery {
  keyword?: string;
  page?: number;
  size?: number;
}

export function fetchCandidates(query: CandidateQuery = {}): Promise<ShipbackCandidatePage> {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ShipbackCandidatePage>(`${BASE}/candidates?${params.toString()}`);
}

export interface ConsignmentQuery {
  keyword?: string;
  status?: string;
  transportMode?: string;
  page?: number;
  size?: number;
}

export function fetchConsignments(query: ConsignmentQuery = {}): Promise<ReturnShipbackPage> {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.status) params.set('status', query.status);
  if (query.transportMode) params.set('transportMode', query.transportMode);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ReturnShipbackPage>(`${BASE}/consignments?${params.toString()}`);
}

export interface ShipbackLineDraft {
  dispositionOrderId: string;
  qty: number;
  weightKg?: number | undefined;
  volumeM3?: number | undefined;
  hsCode?: string | undefined;
  declaredItemName?: string | undefined;
  declaredValue?: number | undefined;
  declaredCurrency?: string | undefined;
  salvageAmount?: number | undefined;
  salvageCurrency?: string | undefined;
  note?: string | undefined;
}

export interface CreateConsignmentPayload {
  transportMode: string;
  originCountry: string;
  destinationCountry: string;
  originWarehouseRef: string;
  domesticWarehouseRef: string;
  costCurrency: string;
  departurePort?: string | undefined;
  arrivalPort?: string | undefined;
  carrierRef?: string | undefined;
  trackingNo?: string | undefined;
  plannedDepartureAt?: string | undefined;
  plannedArrivalAt?: string | undefined;
  consolidationNote?: string | undefined;
  note?: string | undefined;
  lines: ShipbackLineDraft[];
}

export function createConsignment(
  payload: CreateConsignmentPayload,
): Promise<ReturnShipbackDetail> {
  return api<ReturnShipbackDetail>(`${BASE}/consignments`, 'POST', payload);
}

export function fetchConsignment(id: string): Promise<ReturnShipbackDetail> {
  return api<ReturnShipbackDetail>(`${BASE}/consignments/${id}`);
}

export function addLine(id: string, line: ShipbackLineDraft): Promise<ReturnShipbackDetail> {
  return api<ReturnShipbackDetail>(`${BASE}/consignments/${id}/lines`, 'POST', { line });
}

export interface RecordDeclarationPayload {
  lineId?: string | undefined;
  declarationType: string;
  hsCode: string;
  declaredItemName: string;
  declaredQty: number;
  declaredValue: number;
  declaredCurrency: string;
  tariffRate: number;
  taxAmount?: number | undefined;
  sourceReference?: string | undefined;
  declarationNo?: string | undefined;
  status?: string | undefined;
  submittedAt?: string | undefined;
  clearedAt?: string | undefined;
  note?: string | undefined;
}

export function recordDeclaration(
  id: string,
  payload: RecordDeclarationPayload,
): Promise<ReturnShipbackDeclarationRow> {
  return api<ReturnShipbackDeclarationRow>(`${BASE}/consignments/${id}/customs`, 'POST', payload);
}

export interface RecordCostPayload {
  costType: string;
  amount: number;
  currency: string;
  allocationBasis: string;
  vendorRef?: string | undefined;
  incurredAt?: string | undefined;
  note?: string | undefined;
}

export function recordCost(
  id: string,
  payload: RecordCostPayload,
): Promise<ShipbackAllocationResult> {
  return api<ShipbackAllocationResult>(`${BASE}/consignments/${id}/costs`, 'POST', payload);
}

export function fetchAllocations(id: string): Promise<ReturnShipbackAllocationRow[]> {
  return api<ReturnShipbackAllocationRow[]>(`${BASE}/consignments/${id}/allocations`);
}

export function confirmConsignment(id: string): Promise<ReturnShipbackDetail> {
  return api<ReturnShipbackDetail>(`${BASE}/consignments/${id}/confirm`, 'POST', {});
}

export function dispatchConsignment(
  id: string,
  trackingNo?: string,
): Promise<ReturnShipbackDetail> {
  return api<ReturnShipbackDetail>(`${BASE}/consignments/${id}/dispatch`, 'POST', {
    trackingNo: trackingNo?.trim() || undefined,
  });
}

export interface RecordArrivalPayload {
  domesticWarehouseRef: string;
  arrivedAt?: string | undefined;
  receivedQty: number;
  damagedQty?: number | undefined;
  shortageQty?: number | undefined;
  note?: string | undefined;
}

export function recordArrival(
  id: string,
  payload: RecordArrivalPayload,
): Promise<ReturnShipbackDetail> {
  return api<ReturnShipbackDetail>(`${BASE}/consignments/${id}/arrival`, 'POST', payload);
}

export interface RecordEntryPayload {
  lineId: string;
  entryType: string;
  qty: number;
  domesticWarehouseRef?: string | undefined;
  note?: string | undefined;
}

export function recordEntry(
  id: string,
  payload: RecordEntryPayload,
): Promise<ReturnShipbackDetail> {
  return api<ReturnShipbackDetail>(`${BASE}/consignments/${id}/entries`, 'POST', payload);
}

export function closeConsignment(id: string): Promise<ReturnShipbackDetail> {
  return api<ReturnShipbackDetail>(`${BASE}/consignments/${id}/close`, 'POST', {});
}

export function cancelConsignment(id: string, cancelReason: string): Promise<ReturnShipbackDetail> {
  return api<ReturnShipbackDetail>(`${BASE}/consignments/${id}/cancel`, 'POST', { cancelReason });
}

export interface EventQuery {
  consignmentId?: string;
  page?: number;
  size?: number;
}

export function fetchEvents(query: EventQuery = {}): Promise<ReturnShipbackEventPage> {
  const params = new URLSearchParams();
  if (query.consignmentId) params.set('consignmentId', query.consignmentId);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ReturnShipbackEventPage>(`${BASE}/events?${params.toString()}`);
}

// ================================================================ 纯函数（可测）

export function shipbackStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'CONFIRMED':
      return '已确认';
    case 'DISPATCHED':
      return '已发出';
    case 'ARRIVED':
      return '已到货';
    case 'CLOSED':
      return '已关闭';
    case 'CANCELLED':
      return '已取消';
    default:
      return '未知状态';
  }
}

export function transportModeLabel(mode: string | null | undefined): string {
  switch (mode) {
    case 'SEA_CONSOLIDATION':
      return '海运拼箱（LCL）';
    case 'SEA_FULL_CONTAINER':
      return '海运整柜（FCL）';
    case 'AIR':
      return '空运';
    case 'EXPRESS':
      return '国际快递';
    case 'TRUCK':
      return '陆运';
    case 'OTHER':
      return '其他';
    default:
      return '未知运输方式';
  }
}

export function allocationBasisLabel(basis: string | null | undefined): string {
  switch (basis) {
    case 'WEIGHT':
      return '按重量';
    case 'QUANTITY':
      return '按数量（件）';
    case 'DECLARED_VALUE':
      return '按申报货值';
    case 'EQUAL':
      return '平均分摊';
    default:
      return '未知口径';
  }
}

export function costTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'OCEAN_FREIGHT':
      return '海运费';
    case 'AIR_FREIGHT':
      return '空运费';
    case 'TARIFF':
      return '关税';
    case 'CUSTOMS_FEE':
      return '报关费';
    case 'INLAND_FREIGHT':
      return '内陆运费';
    case 'HANDLING_FEE':
      return '操作 / 装卸费';
    case 'REPACK_FEE':
      return '换包装费';
    case 'OTHER':
      return '其他费用';
    default:
      return '其他费用';
  }
}

export function declarationTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'RETURN_IMPORT':
      return '退运进口';
    case 'EXPORT':
      return '出口';
    case 'TRANSIT':
      return '过境';
    default:
      return '未知报关类型';
  }
}

export function entryTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'RESTOCK':
      return '重新入库（→ 7 库存）';
    case 'SCRAP':
      return '报废（→ 10.7.1.3）';
    default:
      return '未知去向';
  }
}

export function customsStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'NOT_PREPARED':
      return '未备报关';
    case 'PREPARED':
      return '已备报关';
    case 'SUBMITTED':
      return '已报关';
    case 'CLEARED':
      return '已放行';
    case 'REJECTED':
      return '已退单';
    default:
      return '未备报关';
  }
}

/** 金额展示：**原样**显示币种与数额，不做任何换算。 */
export function moneyText(amount: number | null | undefined, currency?: string | null): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return '—';
  const fixed = amount.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 数量展示（数量是 NUMERIC(19,6)）。 */
export function quantityText(qty: number | null | undefined): string {
  if (qty === null || qty === undefined || Number.isNaN(qty)) return '—';
  return Number.isInteger(qty) ? String(qty) : qty.toFixed(6);
}

/** 比例展示：scale 4（与后端一致）。 */
export function ratioText(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return '—';
  return ratio.toFixed(4);
}

/**
 * 🔴 分摊合计自检：逐行分摊金额之和。
 *
 * <p>页面上直接用它跟成本金额对比 —— 合计不等于成本金额就说明分摊坏了。
 */
export function allocationSum(rows: ReturnShipbackAllocationRow[]): number {
  return rows.reduce((sum, row) => sum + (row.allocatedAmount ?? 0), 0);
}

/** 🔴 分摊是否守恒（合计 == 成本金额，容差 1e-6）。 */
export function allocationBalanced(
  rows: ReturnShipbackAllocationRow[],
  costAmount: number,
): boolean {
  if (!Number.isFinite(costAmount)) return false;
  return Math.abs(allocationSum(rows) - costAmount) < 1e-6;
}

/** 🔴 分摊明细里是否带了尾差调整（除不尽时必有）。 */
export function hasResidual(rows: ReturnShipbackAllocationRow[]): boolean {
  return rows.some((row) => Math.abs(row.residualAmount ?? 0) > 1e-9);
}

/** 尾差合计（应等于「总额 − 逐笔四舍五入之和」）。 */
export function residualSum(rows: ReturnShipbackAllocationRow[]): number {
  return rows.reduce((sum, row) => sum + (row.residualAmount ?? 0), 0);
}

// ------------------------------------------------------------ 合单表单

export interface ConsignmentForm {
  transportMode: string;
  originCountry: string;
  destinationCountry: string;
  originWarehouseRef: string;
  domesticWarehouseRef: string;
  costCurrency: string;
  departurePort: string;
  arrivalPort: string;
  carrierRef: string;
  trackingNo: string;
  consolidationNote: string;
  note: string;
}

/** 勾选中的候选行（凑单的最小单位：一条处置单一行）。 */
export interface SelectedCandidate {
  dispositionOrderId: string;
  skuCode: string;
  skuName: string;
  disposedQty: number;
  qty: string;
  weightKg: string;
  declaredValue: string;
  declaredCurrency: string;
  hsCode: string;
  declaredItemName: string;
}

export function emptyConsignmentForm(): ConsignmentForm {
  return {
    transportMode: 'SEA_CONSOLIDATION',
    originCountry: '',
    destinationCountry: 'CN',
    originWarehouseRef: '',
    domesticWarehouseRef: '',
    costCurrency: 'USD',
    departurePort: '',
    arrivalPort: '',
    carrierRef: '',
    trackingNo: '',
    consolidationNote: '',
    note: '',
  };
}

export function emptySelectedCandidate(
  candidate: ShipbackCandidateRow,
): SelectedCandidate {
  return {
    dispositionOrderId: candidate.dispositionOrderId,
    skuCode: candidate.skuCode,
    skuName: candidate.skuName,
    disposedQty: candidate.disposedQty,
    qty: String(candidate.disposedQty),
    weightKg: '',
    declaredValue: '',
    declaredCurrency: '',
    hsCode: '',
    declaredItemName: '',
  };
}

/**
 * 🔴 合单表单校验。
 *
 * <p>「凑单 / 合箱」至少要一行货 —— 一张空退运单既摊不了成本也报不了关。
 */
export function validateConsignmentForm(
  form: ConsignmentForm,
  selected: SelectedCandidate[],
): string {
  if (!form.transportMode) return '必须选择运输方式';
  if (!/^[A-Za-z]{2}$/.test(form.originCountry.trim())) return '起运国家必须是两位国家代码';
  if (!/^[A-Za-z]{2}$/.test(form.destinationCountry.trim())) return '目的国家必须是两位国家代码';
  if (!form.originWarehouseRef.trim()) return '起运仓不能为空';
  if (!form.domesticWarehouseRef.trim()) return '国内收货仓不能为空';
  if (!/^[A-Za-z]{3}$/.test(form.costCurrency.trim())) {
    return '成本币种必须是三个大写字母（例如 USD）';
  }
  if (!selected.length) return '至少要选一条待退运的货：退运是合单操作，空单没有意义';
  for (const row of selected) {
    const qty = Number(row.qty);
    if (!row.qty.trim() || !Number.isFinite(qty) || qty <= 0) {
      return `退运数量必须大于 0（${row.skuCode}）`;
    }
    if (qty > row.disposedQty) {
      return `退运数量 ${qty} 超过处置单的处置量 ${row.disposedQty}（${row.skuCode}）`;
    }
    const weight = row.weightKg.trim();
    if (weight && (!Number.isFinite(Number(weight)) || Number(weight) < 0)) {
      return `重量必须是不小于 0 的数字（${row.skuCode}）`;
    }
    const value = row.declaredValue.trim();
    if (value && (!Number.isFinite(Number(value)) || Number(value) < 0)) {
      return `申报货值必须是不小于 0 的数字（${row.skuCode}）`;
    }
  }
  return '';
}

/** 校验通过后构造请求体（**只在这里**把空串变成拒绝）。 */
export function buildConsignmentPayload(
  form: ConsignmentForm,
  selected: SelectedCandidate[],
): CreateConsignmentPayload | { error: string } {
  const error = validateConsignmentForm(form, selected);
  if (error) return { error };
  const lines: ShipbackLineDraft[] = selected.map((row) => {
    const weight = row.weightKg.trim();
    const value = row.declaredValue.trim();
    const currency = row.declaredCurrency.trim().toUpperCase();
    return {
      dispositionOrderId: row.dispositionOrderId,
      qty: Number(row.qty),
      weightKg: weight ? Number(weight) : undefined,
      declaredValue: value ? Number(value) : undefined,
      declaredCurrency: currency || undefined,
      hsCode: row.hsCode.trim() || undefined,
      declaredItemName: row.declaredItemName.trim() || undefined,
    };
  });
  return {
    transportMode: form.transportMode,
    originCountry: form.originCountry.trim().toUpperCase(),
    destinationCountry: form.destinationCountry.trim().toUpperCase(),
    originWarehouseRef: form.originWarehouseRef.trim(),
    domesticWarehouseRef: form.domesticWarehouseRef.trim(),
    costCurrency: form.costCurrency.trim().toUpperCase(),
    departurePort: form.departurePort.trim() || undefined,
    arrivalPort: form.arrivalPort.trim() || undefined,
    carrierRef: form.carrierRef.trim() || undefined,
    trackingNo: form.trackingNo.trim() || undefined,
    consolidationNote: form.consolidationNote.trim() || undefined,
    note: form.note.trim() || undefined,
    lines,
  };
}

// ------------------------------------------------------------ 成本表单

export interface CostForm {
  costType: string;
  amount: string;
  currency: string;
  allocationBasis: string;
  vendorRef: string;
  note: string;
}

export function emptyCostForm(currency: string): CostForm {
  return {
    costType: 'OCEAN_FREIGHT',
    amount: '',
    currency,
    allocationBasis: 'WEIGHT',
    vendorRef: '',
    note: '',
  };
}

export function validateCostForm(form: CostForm): string {
  if (!form.costType) return '必须选择成本类型';
  const amount = Number(form.amount);
  if (!form.amount.trim() || !Number.isFinite(amount) || amount < 0) {
    return '成本金额必须是不小于 0 的数字';
  }
  if (!/^[A-Za-z]{3}$/.test(form.currency.trim())) return '币种必须是三个大写字母（例如 USD）';
  if (!form.allocationBasis) return '必须选择分摊口径（按重量 / 按数量 / 按申报货值 / 平均）';
  return '';
}

export function buildCostPayload(form: CostForm): RecordCostPayload | { error: string } {
  const error = validateCostForm(form);
  if (error) return { error };
  return {
    costType: form.costType,
    amount: Number(form.amount),
    currency: form.currency.trim().toUpperCase(),
    allocationBasis: form.allocationBasis,
    vendorRef: form.vendorRef.trim() || undefined,
    note: form.note.trim() || undefined,
  };
}

// ------------------------------------------------------------ 报关表单

export interface DeclarationForm {
  declarationType: string;
  hsCode: string;
  declaredItemName: string;
  declaredQty: string;
  declaredValue: string;
  declaredCurrency: string;
  tariffRate: string;
  taxAmount: string;
  declarationNo: string;
  sourceReference: string;
  note: string;
}

export function emptyDeclarationForm(): DeclarationForm {
  return {
    declarationType: 'RETURN_IMPORT',
    hsCode: '',
    declaredItemName: '',
    declaredQty: '',
    declaredValue: '',
    declaredCurrency: 'CNY',
    tariffRate: '',
    taxAmount: '',
    declarationNo: '',
    sourceReference: '',
    note: '',
  };
}

/**
 * 🔴 报关表单校验。
 *
 * <p>税率必须是<b>小数</b>（0.15 = 15%），填 15 会被拒 —— 那是 1500%。
 */
export function validateDeclarationForm(form: DeclarationForm): string {
  if (!form.declarationType) return '必须选择报关单类型';
  if (!form.hsCode.trim()) return 'HS 编码不能为空';
  if (!form.declaredItemName.trim()) {
    return '申报品名不能为空：申报品名不照搬销售标题，但它必须有';
  }
  const qty = Number(form.declaredQty);
  if (!form.declaredQty.trim() || !Number.isFinite(qty) || qty <= 0) {
    return '申报数量必须大于 0';
  }
  const value = Number(form.declaredValue);
  if (!form.declaredValue.trim() || !Number.isFinite(value) || value < 0) {
    return '申报货值必须是不小于 0 的数字';
  }
  if (!/^[A-Za-z]{3}$/.test(form.declaredCurrency.trim())) {
    return '申报币种必须是三个大写字母（例如 CNY）';
  }
  if (!form.tariffRate.trim()) return '关税税率不能为空（填 0 表示无关税）';
  const rate = Number(form.tariffRate);
  if (!Number.isFinite(rate) || rate < 0 || rate > 1) {
    return '关税税率必须是不小于 0 且不大于 1 的小数（0.15 表示 15%）';
  }
  const tax = form.taxAmount.trim();
  if (tax && (!Number.isFinite(Number(tax)) || Number(tax) < 0)) {
    return '税费必须是不小于 0 的数字';
  }
  return '';
}

/**
 * 🔴 关税金额的**页面预览**（服务端仍是唯一真相）。
 *
 * <p>关税 = 申报货值 × 税率，HALF_UP 到 4 位 —— 与服务端算法一致，
 * 页面先把结果给运营看，但落库时服务端会自己重算一遍。
 */
export function previewTariffAmount(declaredValue: number, tariffRate: number): number {
  if (!Number.isFinite(declaredValue) || !Number.isFinite(tariffRate)) return 0;
  return Math.round(declaredValue * tariffRate * 10000) / 10000;
}

export function buildDeclarationPayload(
  form: DeclarationForm,
): RecordDeclarationPayload | { error: string } {
  const error = validateDeclarationForm(form);
  if (error) return { error };
  const tax = form.taxAmount.trim();
  return {
    declarationType: form.declarationType,
    hsCode: form.hsCode.trim(),
    declaredItemName: form.declaredItemName.trim(),
    declaredQty: Number(form.declaredQty),
    declaredValue: Number(form.declaredValue),
    declaredCurrency: form.declaredCurrency.trim().toUpperCase(),
    tariffRate: Number(form.tariffRate),
    taxAmount: tax ? Number(tax) : undefined,
    declarationNo: form.declarationNo.trim() || undefined,
    sourceReference: form.sourceReference.trim() || undefined,
    note: form.note.trim() || undefined,
  };
}

// ------------------------------------------------------------ 收货 / 分录表单

export interface ArrivalForm {
  domesticWarehouseRef: string;
  receivedQty: string;
  damagedQty: string;
  shortageQty: string;
  note: string;
}

export function emptyArrivalForm(warehouseRef: string): ArrivalForm {
  return {
    domesticWarehouseRef: warehouseRef,
    receivedQty: '',
    damagedQty: '',
    shortageQty: '',
    note: '',
  };
}

export function validateArrivalForm(form: ArrivalForm, plannedQty: number): string {
  if (!form.domesticWarehouseRef.trim()) return '国内收货仓不能为空';
  const received = Number(form.receivedQty);
  if (!form.receivedQty.trim() || !Number.isFinite(received) || received < 0) {
    return '收货数量必须是不小于 0 的数字';
  }
  if (received > plannedQty) return `收货数量 ${received} 超过计划发运数量 ${plannedQty}`;
  for (const [label, raw] of [
    ['破损数量', form.damagedQty],
    ['短少数量', form.shortageQty],
  ] as const) {
    if (raw.trim() && (!Number.isFinite(Number(raw)) || Number(raw) < 0)) {
      return `${label}必须是不小于 0 的数字`;
    }
  }
  return '';
}

export function buildArrivalPayload(
  form: ArrivalForm,
  plannedQty: number,
): RecordArrivalPayload | { error: string } {
  const error = validateArrivalForm(form, plannedQty);
  if (error) return { error };
  const damaged = form.damagedQty.trim();
  const shortage = form.shortageQty.trim();
  return {
    domesticWarehouseRef: form.domesticWarehouseRef.trim(),
    receivedQty: Number(form.receivedQty),
    damagedQty: damaged ? Number(damaged) : undefined,
    shortageQty: shortage ? Number(shortage) : undefined,
    note: form.note.trim() || undefined,
  };
}

export interface EntryForm {
  lineId: string;
  entryType: string;
  qty: string;
  note: string;
}

export function emptyEntryForm(lineId = '', entryType = 'RESTOCK'): EntryForm {
  return { lineId, entryType, qty: '', note: '' };
}

export function validateEntryForm(form: EntryForm, remainingQty: number): string {
  if (!form.lineId) return '必须选择一条明细行';
  if (!form.entryType) return '必须选择去向（重新入库 / 报废）';
  const qty = Number(form.qty);
  if (!form.qty.trim() || !Number.isFinite(qty) || qty <= 0) {
    return '数量必须大于 0';
  }
  if (qty > remainingQty) {
    return `数量 ${qty} 超过该行尚未登记去向的数量 ${remainingQty}`;
  }
  return '';
}

export function buildEntryPayload(
  form: EntryForm,
  remainingQty: number,
): RecordEntryPayload | { error: string } {
  const error = validateEntryForm(form, remainingQty);
  if (error) return { error };
  return {
    lineId: form.lineId,
    entryType: form.entryType,
    qty: Number(form.qty),
    note: form.note.trim() || undefined,
  };
}

/** 一行还没登记去向的数量（行数量 − 已登记的入库 / 报废分录）。 */
export function remainingEntryQty(
  line: ReturnShipbackLineRow,
  entries: ReturnShipbackEntryRow[],
): number {
  const used = entries
    .filter((entry) => entry.lineId === line.id)
    .reduce((sum, entry) => sum + (entry.qty ?? 0), 0);
  return (line.qty ?? 0) - used;
}

/** 错误文案：422 / 409 的理由原样透出（服务端写清了「凭什么挡住」）。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 422 || error.status === 409) return error.message;
    if (error.status === 403) return '当前角色没有退运回国的操作权限';
    if (error.status === 0) return '正在连接渠道服务，请稍后重试';
    return error.message;
  }
  return '加载失败，请稍后重试';
}
