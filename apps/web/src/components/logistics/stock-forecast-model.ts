import { ApiError, api } from '../inventory/api';

/**
 * 10.4.2.2 备货预报前端模型。
 *
 * <p>蓝图关系段加粗句：「**平台仓备货有严格规格**（箱型、贴标、托盘、混装限制），
 * 规格不符会被拒收造成额外成本与延误。」
 *
 * <p>所以界面的重心不在「填一张表」，而在**让规格违规在提交前就看得见**：
 * 规格是四组可维护的限制，校验结果逐条点名违反哪一条，提交按钮被闸门挡住。
 * 界面上没有「先提交再说」的路径 —— 那条路径的终点就是仓端拒收。
 *
 * <p>另一条纪律：**读不到就说读不到**。库存与在途口径缺失时显示「不可用」，
 * 不用 0 顶 —— 0 会被读成「没有库存需求」，那正是会真的少备一批货的路径。
 */

// ---------------------------------------------------------------------------
// 契约类型
// ---------------------------------------------------------------------------

export interface ForecastCapabilityDimension {
  key: string;
  label: string;
  available: boolean;
  ownerModule: string;
  impact: string;
}

export interface ForecastCapabilities {
  dimensions: ForecastCapabilityDimension[];
  boxTypes: string[];
  labelFormats: string[];
  sources: string[];
  note: string;
}

export interface ForecastSpecRow {
  id: string;
  accountId: string;
  accountName: string;
  versionNo: number;
  status: string;
  allowedBoxTypes: string[];
  maxUnitsPerCarton?: number | null;
  maxWeightPerCartonKg?: number | null;
  maxSkusPerCarton?: number | null;
  requiresFnskuLabel: boolean;
  requiresCartonLabel: boolean;
  allowedLabelFormats: string[];
  palletRequired: boolean;
  maxCartonsPerPallet?: number | null;
  maxPalletWeightKg?: number | null;
  mixedLoadingAllowed: boolean;
  minArrivalLeadDays: number;
  note: string;
  createdAt: string;
  retiredAt?: string | null;
}

export interface ForecastRow {
  id: string;
  forecastNo: string;
  accountId: string;
  accountName: string;
  providerCode: string;
  source: string;
  status: string;
  expectedArrivalAt: string;
  totalQuantity: number;
  lineCount: number;
  platformShipmentId: string;
  submittedAt?: string | null;
  closedAt?: string | null;
  lastError: string;
  note: string;
  createdAt: string;
}

export interface ForecastSummary {
  total: number;
  draft: number;
  validated: number;
  submitted: number;
  shipmentCreated: number;
  accepted: number;
  rejected: number;
  cancelled: number;
  totalQuantity: number;
}

export interface ForecastPage {
  rows: ForecastRow[];
  page: number;
  size: number;
  total: number;
  pageCount: number;
  summary: ForecastSummary;
}

export interface ForecastLineRow {
  id: string;
  skuCode: string;
  quantity: number;
  unit: string;
  boxType: string;
  cartonCount?: number | null;
  palletCount?: number | null;
  labelFormat: string;
  cartonLabelRef: string;
  unitWeightKg?: number | null;
  note: string;
  position: number;
}

export interface ForecastLineInput {
  skuCode: string;
  quantity: number;
  unit?: string;
  boxType?: string;
  cartonCount?: number | null;
  palletCount?: number | null;
  labelFormat?: string;
  cartonLabelRef?: string;
  unitWeightKg?: number | null;
  note?: string;
}

export interface SpecViolation {
  rule: string;
  ruleLabel: string;
  skuCode?: string | null;
  message: string;
  expected?: string | null;
  actual?: string | null;
}

export interface SpecCheckResult {
  passed: boolean;
  violations: SpecViolation[];
  specId?: string | null;
  specVersion?: number | null;
  checkedAt: string;
  note: string;
}

export interface ShipmentTaskRow {
  id: string;
  forecastId: string;
  taskNo: string;
  status: string;
  attempts: number;
  platformShipmentId: string;
  lastError: string;
  acknowledgedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ShipmentReportRow {
  id: string;
  externalShipmentRef: string;
  source: string;
  reportedAt: string;
  createdAt: string;
}

export interface ForecastReceiptRow {
  id: string;
  receiptType: string;
  receiptRef: string;
  detail: string;
  rejectedReason: string;
  receivedAt: string;
  source: string;
  createdAt: string;
}

export interface ForecastDetail {
  forecast: ForecastRow;
  lines: ForecastLineRow[];
  spec?: ForecastSpecRow | null;
  shipmentTask?: ShipmentTaskRow | null;
  receipts: ForecastReceiptRow[];
  specCheck: SpecCheckResult;
}

export interface ForecastAvailabilityLine {
  skuCode: string;
  requestedQuantity: number;
  unit: string;
  sellable?: number | null;
  sellableKnown: boolean;
  inTransitQuantity?: number | null;
  inTransitKnown: boolean;
  coverable?: boolean | null;
  note: string;
}

export interface ForecastAvailabilityPage {
  rows: ForecastAvailabilityLine[];
  page: number;
  size: number;
  total: number;
  note: string;
}

/** 目的仓下拉项（10.4.2.1 的仓账户）。 */
export interface DestinationAccount {
  id: string;
  name: string;
  externalAccountCode: string;
  countryCode: string;
  warehouseKind: string;
  providerCode: string;
  connectionName: string;
}

// ---------------------------------------------------------------------------
// 读取出口
// ---------------------------------------------------------------------------

export interface ForecastListQuery {
  status?: string;
  accountId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function fetchCapabilities(): Promise<ForecastCapabilities> {
  return api<ForecastCapabilities>('channel/stock-forecasts/capabilities');
}

export function fetchDestinationAccounts(): Promise<DestinationAccount[]> {
  return api<DestinationAccount[]>('channel/stock-forecasts/accounts');
}

export function fetchSpecs(accountId?: string): Promise<ForecastSpecRow[]> {
  const query = accountId ? `?accountId=${encodeURIComponent(accountId)}` : '';
  return api<ForecastSpecRow[]>(`channel/stock-forecasts/specs${query}`);
}

export function fetchForecasts(query: ForecastListQuery = {}): Promise<ForecastPage> {
  const params = new URLSearchParams();
  if (query.status) params.set('status', query.status);
  if (query.accountId) params.set('accountId', query.accountId);
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.sort) params.set('sort', query.sort);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ForecastPage>(`channel/stock-forecasts?${params.toString()}`);
}

export function fetchForecast(id: string): Promise<ForecastDetail> {
  return api<ForecastDetail>(`channel/stock-forecasts/${id}`);
}

export function fetchSpecCheck(id: string): Promise<SpecCheckResult> {
  return api<SpecCheckResult>(`channel/stock-forecasts/${id}/spec-check`);
}

export function fetchAvailability(id: string): Promise<ForecastAvailabilityPage> {
  return api<ForecastAvailabilityPage>(`channel/stock-forecasts/${id}/availability`);
}

export function fetchShipmentReports(id: string): Promise<ShipmentReportRow[]> {
  return api<ShipmentReportRow[]>(`channel/stock-forecasts/${id}/shipment-reports`);
}

export function fetchReceipts(id: string): Promise<ForecastReceiptRow[]> {
  return api<ForecastReceiptRow[]>(`channel/stock-forecasts/${id}/receipts`);
}

// ---------------------------------------------------------------------------
// 写出口
// ---------------------------------------------------------------------------

export interface CreateSpecInput {
  accountId: string;
  allowedBoxTypes: string[];
  maxUnitsPerCarton?: number | null;
  maxWeightPerCartonKg?: number | null;
  maxSkusPerCarton?: number | null;
  requiresFnskuLabel: boolean;
  requiresCartonLabel: boolean;
  allowedLabelFormats: string[];
  palletRequired: boolean;
  maxCartonsPerPallet?: number | null;
  maxPalletWeightKg?: number | null;
  mixedLoadingAllowed: boolean;
  minArrivalLeadDays?: number | null;
  note?: string;
}

export function createSpec(input: CreateSpecInput): Promise<string> {
  return api<string>('channel/stock-forecasts/specs', 'POST', input);
}

export function retireSpec(id: string): Promise<void> {
  return api<void>(`channel/stock-forecasts/specs/${id}/retire`, 'POST');
}

export interface CreateForecastInput {
  accountId: string;
  source?: string;
  expectedArrivalAt: string;
  note?: string;
  lines: ForecastLineInput[];
}

export function createForecast(input: CreateForecastInput): Promise<string> {
  return api<string>('channel/stock-forecasts', 'POST', input);
}

export function replaceLines(id: string, lines: ForecastLineInput[]): Promise<void> {
  return api<void>(`channel/stock-forecasts/${id}/lines`, 'PUT', { lines });
}

/**
 * 过规格闸。
 *
 * <p>🔴 规格不符时后端返回 **422 + violations 数组**。这里把结构化违规**取出来抛**，
 * 让界面能逐条显示「违反了哪一条」，而不是只显示一句「规格不符」。
 */
export function validateForecast(id: string): Promise<SpecCheckResult> {
  return api<SpecCheckResult>(`channel/stock-forecasts/${id}/validate`, 'POST');
}

export function submitForecast(id: string): Promise<string> {
  return api<string>(`channel/stock-forecasts/${id}/submit`, 'POST');
}

export function cancelForecast(id: string, reason?: string): Promise<void> {
  const query = reason ? `?reason=${encodeURIComponent(reason)}` : '';
  return api<void>(`channel/stock-forecasts/${id}/cancel${query}`, 'POST');
}

/**
 * 推进平台货件创建任务。
 *
 * <p>🔴 服务商侧失败时后端返回 **502 + 任务体**（任务状态 FAILED + 失败原因）。
 * 那是**真实失败**，界面必须显示失败原因并允许重试 —— 不能当成成功。
 */
export function dispatchShipmentTask(id: string): Promise<ShipmentTaskRow> {
  return api<ShipmentTaskRow>(`channel/stock-forecasts/${id}/shipment-task/dispatch`, 'POST');
}

export function retryShipmentTask(id: string): Promise<ShipmentTaskRow> {
  return api<ShipmentTaskRow>(`channel/stock-forecasts/${id}/shipment-task/retry`, 'POST');
}

export interface CreateShipmentReportInput {
  externalShipmentRef: string;
  source?: string;
  reportedAt: string;
}

export function createShipmentReport(
  id: string,
  input: CreateShipmentReportInput,
): Promise<string> {
  return api<string>(`channel/stock-forecasts/${id}/shipment-reports`, 'POST', input);
}

export interface CreateReceiptInput {
  receiptType: string;
  receiptRef?: string;
  detail?: string;
  rejectedReason?: string;
  receivedAt: string;
  source?: string;
}

export function createReceipt(id: string, input: CreateReceiptInput): Promise<string> {
  return api<string>(`channel/stock-forecasts/${id}/receipts`, 'POST', input);
}

// ---------------------------------------------------------------------------
// 展示派生
// ---------------------------------------------------------------------------

/** 预报单状态中文名。 */
export function forecastStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'VALIDATED':
      return '已过规格校验';
    case 'SUBMITTED':
      return '已提交';
    case 'SHIPMENT_CREATED':
      return '货件已创建';
    case 'ACCEPTED':
      return '仓端已接收';
    case 'REJECTED':
      return '仓端已拒收';
    case 'CANCELLED':
      return '已作废';
    default:
      return status;
  }
}

/** 预报来源中文名。 */
export function forecastSourceLabel(source: string): string {
  switch (source) {
    case 'REPLENISHMENT_SUGGESTION':
      return '补货建议';
    case 'FBA_SHIPMENT_PLAN':
      return 'FBA 发货计划';
    case 'MANUAL':
      return '人工登记';
    default:
      return source;
  }
}

/** 货件任务状态中文名。 */
export function shipmentTaskStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待推进';
    case 'SUBMITTED':
      return '已提交服务商';
    case 'SUCCEEDED':
      return '已受理';
    case 'FAILED':
      return '失败（可重试）';
    case 'CANCELLED':
      return '已作废';
    default:
      return status;
  }
}

/**
 * 规格违规的一句话摘要。
 *
 * <p>🔴 界面上**必须**说清「违反了哪一条 + 期望什么 + 实际什么」——
 * 蓝图明说规格不符会被拒收造成额外成本与延误，运营要据此改到不会再被拒收。
 */
export function violationText(violation: SpecViolation): string {
  const scope = violation.skuCode ? `[${violation.skuCode}] ` : '';
  const detail =
    violation.expected || violation.actual
      ? `（期望 ${violation.expected ?? '—'}，实际 ${violation.actual ?? '—'}）`
      : '';
  return `${scope}${violation.message}${detail}`;
}

/**
 * 可用性结论。
 *
 * <p>🔴 口径缺失（上游没给这一行）时返回 {@link AvailabilityState.UNAVAILABLE}，
 * **不**返回「不足」—— 「查不到」与「确实不够」是两件事，
 * 把前者渲染成后者会让运营以为真的缺货。
 */
export enum AvailabilityState {
  /** 可售量足够覆盖本次预报量。 */
  ENOUGH = 'ENOUGH',
  /** 可售量不足以覆盖本次预报量。 */
  SHORT = 'SHORT',
  /** 上游没给这个 SKU 的口径，结论未知。 */
  UNAVAILABLE = 'UNAVAILABLE',
}

export function availabilityState(line: ForecastAvailabilityLine): AvailabilityState {
  if (!line.sellableKnown || line.sellable === null || line.sellable === undefined)
    return AvailabilityState.UNAVAILABLE;
  return line.sellable >= line.requestedQuantity ? AvailabilityState.ENOUGH : AvailabilityState.SHORT;
}

/** 可用性口径的中文说明；口径缺失时明说「不可用」，不用 0 顶。 */
export function availabilityText(line: ForecastAvailabilityLine): string {
  const state = availabilityState(line);
  if (state === AvailabilityState.UNAVAILABLE) return '不可用（上游没有该 SKU 的口径）';
  const sellable = line.sellable ?? 0;
  const transitQty = line.inTransitQuantity;
  const transit =
    line.inTransitKnown && transitQty !== null && transitQty !== undefined
      ? ` · 头程在途 ${quantityText(transitQty, line.unit)}`
      : ' · 头程在途不可用';
  return `可售 ${quantityText(sellable, line.unit)}${transit}`;
}

/** 数量文本：去掉多余的 0，但小数精度不丢。 */
export function quantityText(quantity: number, unit: string): string {
  const text = quantity.toFixed(6).replace(/\.?0+$/, '');
  return `${text} ${unit}`;
}

/**
 * 从 422 的响应体里取出规格违规清单。
 *
 * <p>后端把违规挂在 ProblemDetail 的 {@code violations} 属性上（并附
 * {@code error: 'SPEC_VALIDATION_FAILED'}）。取不到就返回空数组，
 * 界面退回显示 `detail` 文案。
 */
export function violationsFromError(error: unknown): SpecViolation[] {
  if (!(error instanceof ApiError)) return [];
  const body = error.body as { violations?: unknown } | null;
  if (!body || !Array.isArray(body.violations)) return [];
  return body.violations as SpecViolation[];
}
