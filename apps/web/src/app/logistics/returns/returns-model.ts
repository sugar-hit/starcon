import { api, ApiError } from '../../../lib/api';

/**
 * 10.7.1.1 退货接收与处置决策前端模型。
 *
 * <p>蓝图：「管理买家退回商品的接收与处置决策：本地仓/海外仓/平台仓接收登记，判定
 * <b>转销售（可再售）/退运回国/弃置销毁</b>，记录处置成本（退货运费、销毁费、平台处理费）
 * 与残值。」输入：退货实收记录（11.2.2.1）；处置决策与成本参数；商品再销售限制
 * （品牌方要求/平台政策）。输出：处置单；库存状态变更指令（→ 7）；处置成本（→ 12 费用）。
 *
 * <p>🔴 这个界面上**最容易做成假活**的一处，是把处置方式做成一个带默认值的下拉框，
 * 或者给「转销售」一个高亮。蓝图加粗句写得很直白：「跨境退货三大去向（转卖/退运/弃置）
 * 对利润影响巨大，默认按『可再售』处理会高估回收价值，转卖/弃置决策必须显式。」
 *
 * <p>所以本模型的三个约定：
 *
 * <ol>
 *   <li><b>决策表单的处置方式初值是空串</b>（{@link EMPTY_DISPOSITION_METHOD}），
 *       不是 'RESALE'。{@link validateDecisionForm} 在它为空时直接拒，
 *       页面不给「默认高亮可再售」这回事。</li>
 *   <li><b>「能不能转销售」由服务端判</b>（GET /eligibility）：质检结论 + 再销售限制。
 *       页面只把服务端给的 {@code allowed} / {@code blockingReasons} 显示出来，
 *       **不在浏览器里自己比对**—— 那条规则一旦散落到前端就一定会漏一处。</li>
 *   <li><b>成本与残值是两种口径</b>：成本明细行与成本币种逐字相同，残值币种可以不同；
 *       页面**不做任何汇率换算**，合计也按币种分行显示。</li>
 * </ol>
 */

// ================================================================ 枚举词表

/** 处置去向：与服务端白名单逐字一致。 */
export type DispositionMethod = 'RESALE' | 'RETURN_TO_COUNTRY' | 'DISCARD';

/** 处置状态的初值 —— **空串**，代表「还没显式选」。 */
export const EMPTY_DISPOSITION_METHOD = '' as const;

/** 三个去向的展示顺序：先转销售（最容易被默认的那个），再退运、弃置。 */
export const DISPOSITION_METHODS: readonly DispositionMethod[] = [
  'RESALE',
  'RETURN_TO_COUNTRY',
  'DISCARD',
];

/** 接收仓类型。 */
export type WarehouseType = 'LOCAL' | 'OVERSEAS' | 'PLATFORM';

/** 处置状态（本服务的判定进度，**不是**处置方式）。 */
export type DispositionState = 'PENDING_DECISION' | 'DECIDED' | 'ORDERED';

export const DISPOSITION_STATES: readonly DispositionState[] = [
  'PENDING_DECISION',
  'DECIDED',
  'ORDERED',
];

/** 再销售限制的对象类型。 */
export type RestrictionSubjectType = 'BRAND' | 'PLATFORM' | 'SKU';

/** 限制依据。 */
export type RestrictionBasis = 'BRAND_REQUIREMENT' | 'PLATFORM_POLICY' | 'REGULATION' | 'CONTRACT';

/** 处置成本类型。 */
export type CostType = 'RETURN_FREIGHT' | 'DESTROY_FEE' | 'PLATFORM_HANDLING_FEE' | 'OTHER';

// ================================================================ 形状

export interface ReturnCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ReturnDispositionCapabilities {
  dimensions: ReturnCapabilityDimension[];
  dispositionMethods: string[];
  warehouseTypes: string[];
  costTypes: string[];
  restrictionSubjectTypes: string[];
  restrictionBases: string[];
  decisionNote: string;
  currencyNote: string;
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

export interface ReturnDispositionOverview {
  registrationCount: number;
  pendingDecisionCount: number;
  issuedOrderCount: number;
  cancelledOrderCount: number;
  resaleCount: number;
  repatriationCount: number;
  discardCount: number;
  restrictedRegistrationCount: number;
  costTotals: CurrencyTotal[];
  salvageTotals: CurrencyTotal[];
  evaluatedAt: string;
  note: string;
  currencyNote: string;
}

export interface ReturnReceivingRow {
  id: string;
  registrationNo: string;
  receiptId: string;
  receiptLineId: string;
  receiptNo: string;
  skuCode: string;
  skuName: string;
  batchNo: string;
  brandCode: string;
  platformCode: string;
  warehouseType: string;
  warehouseTypeLabel: string;
  warehouseRef: string;
  warehouseCountry: string;
  receivedQty: number;
  receivedAt: string;
  registeredBy: string;
  note: string;
  dispositionState: string;
  dispositionMethod: string | null;
  dispositionMethodLabel: string | null;
  decisionId: string | null;
  orderId: string | null;
  activeRestrictionCount: number;
  version: number;
}

export interface ReturnReceivingPage {
  rows: ReturnReceivingRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
  dispositionNote: string;
}

export interface ReturnResaleRestrictionRow {
  id: string;
  subjectType: string;
  subjectTypeLabel: string;
  subjectRef: string;
  restrictionBasis: string;
  restrictionBasisLabel: string;
  reason: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: string;
  revokedAt: string | null;
  revokeReason: string;
  version: number;
}

export interface ReturnResaleRestrictionPage {
  rows: ReturnResaleRestrictionRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
}

export interface ReturnMethodEligibility {
  method: string;
  methodLabel: string;
  allowed: boolean;
  blockingReasons: string[];
  note: string;
}

export interface ReturnDispositionEligibility {
  registrationId: string;
  skuCode: string;
  skuName: string;
  receivedQty: number;
  qualityVerdict: string | null;
  qualityVerdictLabel: string | null;
  qualityVerdictAt: string | null;
  qualityNote: string | null;
  resaleRestricted: boolean;
  resaleRestrictionNote: string;
  methods: ReturnMethodEligibility[];
  evaluatedAt: string;
  upstreamNote: string;
}

export interface ReturnDispositionDecisionRow {
  id: string;
  decisionNo: string;
  registrationId: string;
  receiptId: string;
  receiptLineId: string;
  receiptNo: string;
  skuCode: string;
  skuName: string;
  batchNo: string;
  brandCode: string;
  platformCode: string;
  dispositionMethod: string;
  dispositionMethodLabel: string;
  decidedQty: number;
  decisionBasis: string;
  decidedBy: string;
  decidedAt: string;
  qualityVerdict: string | null;
  qualityVerdictLabel: string | null;
  qualityVerdictAt: string | null;
  qualitySalvageEstimate: number | null;
  qualitySalvageCurrency: string | null;
  resaleRestricted: boolean;
  resaleRestrictionNote: string;
  status: string;
  statusLabel: string;
  consumedByOrderId: string | null;
  version: number;
}

export interface ReturnDispositionDecisionPage {
  rows: ReturnDispositionDecisionRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
  decisionNote: string;
}

export interface ReturnDispositionCostRow {
  id: string;
  orderId: string;
  costType: string;
  costTypeLabel: string;
  amount: number;
  currency: string;
  vendorRef: string;
  incurredAt: string;
  note: string;
}

export interface ReturnInventoryInstructionRow {
  id: string;
  orderId: string;
  dispositionMethod: string;
  instructionType: string;
  instructionTypeLabel: string;
  targetInventoryState: string;
  targetInventoryStateLabel: string;
  skuCode: string;
  skuName: string;
  batchNo: string;
  qty: number;
  instructionStatus: string;
  consumerModule: string;
  issuedAt: string;
  issuedBy: string;
}

export interface ReturnDispositionOrderRow {
  id: string;
  dispositionNo: string;
  decisionId: string;
  registrationId: string;
  receiptId: string;
  receiptLineId: string;
  receiptNo: string;
  skuCode: string;
  skuName: string;
  batchNo: string;
  brandCode: string;
  platformCode: string;
  dispositionMethod: string;
  dispositionMethodLabel: string;
  disposedQty: number;
  costCurrency: string;
  salvageCurrency: string;
  salvageAmount: number;
  totalCostAmount: number;
  costItemCount: number;
  status: string;
  statusLabel: string;
  issuedBy: string;
  issuedAt: string;
  note: string;
  version: number;
}

export interface ReturnDispositionOrderDetail {
  head: ReturnDispositionOrderRow;
  costs: ReturnDispositionCostRow[];
  instruction: ReturnInventoryInstructionRow | null;
  events: ReturnDispositionEventRow[];
}

export interface ReturnDispositionOrderPage {
  rows: ReturnDispositionOrderRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
  currencyNote: string;
}

export interface ReturnDispositionEventRow {
  id: string;
  eventType: string;
  eventTypeLabel: string;
  registrationId: string | null;
  decisionId: string | null;
  orderId: string | null;
  actorId: string;
  occurredAt: string;
  detail: string;
}

export interface ReturnDispositionEventPage {
  rows: ReturnDispositionEventRow[];
  page: number;
  size: number;
  total: number;
}

// ================================================================ 取数

const BASE = 'channel/return-dispositions';

export function fetchCapabilities(): Promise<ReturnDispositionCapabilities> {
  return api<ReturnDispositionCapabilities>(`${BASE}/capabilities`);
}

export function fetchOverview(): Promise<ReturnDispositionOverview> {
  return api<ReturnDispositionOverview>(`${BASE}/overview`);
}

export interface RegistrationQuery {
  keyword?: string;
  warehouseType?: string;
  dispositionState?: string;
  page?: number;
  size?: number;
}

export function fetchRegistrations(query: RegistrationQuery = {}): Promise<ReturnReceivingPage> {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.warehouseType) params.set('warehouseType', query.warehouseType);
  if (query.dispositionState) params.set('dispositionState', query.dispositionState);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ReturnReceivingPage>(`${BASE}/registrations?${params.toString()}`);
}

export interface RegisterReceivingPayload {
  receiptId: string;
  receiptLineId: string;
  warehouseType: string;
  warehouseRef: string;
  warehouseCountry?: string | undefined;
  receivedQty: number;
  receivedAt?: string | undefined;
  brandCode?: string | undefined;
  platformCode?: string | undefined;
  note?: string | undefined;
}

export function registerReceiving(payload: RegisterReceivingPayload): Promise<ReturnReceivingRow> {
  return api<ReturnReceivingRow>(`${BASE}/registrations`, 'POST', payload);
}

export function fetchEligibility(registrationId: string): Promise<ReturnDispositionEligibility> {
  const params = new URLSearchParams({ registrationId });
  return api<ReturnDispositionEligibility>(`${BASE}/eligibility?${params.toString()}`);
}

export interface RestrictionQuery {
  subjectType?: string;
  status?: string;
  page?: number;
  size?: number;
}

export function fetchRestrictions(query: RestrictionQuery = {}): Promise<ReturnResaleRestrictionPage> {
  const params = new URLSearchParams();
  if (query.subjectType) params.set('subjectType', query.subjectType);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ReturnResaleRestrictionPage>(`${BASE}/restrictions?${params.toString()}`);
}

export interface SaveRestrictionPayload {
  subjectType: string;
  subjectRef: string;
  restrictionBasis: string;
  reason: string;
  effectiveFrom?: string | undefined;
  effectiveTo?: string | undefined;
}

export function saveRestriction(
  payload: SaveRestrictionPayload,
): Promise<ReturnResaleRestrictionRow> {
  return api<ReturnResaleRestrictionRow>(`${BASE}/restrictions`, 'POST', payload);
}

export function revokeRestriction(
  id: string,
  revokeReason: string,
): Promise<ReturnResaleRestrictionRow> {
  return api<ReturnResaleRestrictionRow>(`${BASE}/restrictions/${id}/revoke`, 'POST', { revokeReason });
}

export interface DecisionQuery {
  dispositionMethod?: string;
  status?: string;
  page?: number;
  size?: number;
}

export function fetchDecisions(query: DecisionQuery = {}): Promise<ReturnDispositionDecisionPage> {
  const params = new URLSearchParams();
  if (query.dispositionMethod) params.set('dispositionMethod', query.dispositionMethod);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ReturnDispositionDecisionPage>(`${BASE}/decisions?${params.toString()}`);
}

export interface DecidePayload {
  registrationId: string;
  dispositionMethod: string;
  decidedQty: number;
  decisionBasis: string;
  decidedAt?: string | undefined;
}

export function decideDisposition(payload: DecidePayload): Promise<ReturnDispositionDecisionRow> {
  return api<ReturnDispositionDecisionRow>(`${BASE}/decisions`, 'POST', payload);
}

export interface OrderQuery {
  dispositionMethod?: string;
  status?: string;
  page?: number;
  size?: number;
}

export function fetchOrders(query: OrderQuery = {}): Promise<ReturnDispositionOrderPage> {
  const params = new URLSearchParams();
  if (query.dispositionMethod) params.set('dispositionMethod', query.dispositionMethod);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ReturnDispositionOrderPage>(`${BASE}/orders?${params.toString()}`);
}

export interface CostDraft {
  costType: string;
  amount: number;
  currency?: string | undefined;
  vendorRef?: string | undefined;
  incurredAt?: string | undefined;
  note?: string | undefined;
}

export interface IssueOrderPayload {
  decisionId: string;
  costCurrency: string;
  salvageCurrency: string;
  salvageAmount: number;
  issuedAt?: string | undefined;
  note?: string | undefined;
  costs?: CostDraft[] | undefined;
}

export function issueDispositionOrder(payload: IssueOrderPayload): Promise<ReturnDispositionOrderRow> {
  return api<ReturnDispositionOrderRow>(`${BASE}/orders`, 'POST', payload);
}

export function fetchOrder(id: string): Promise<ReturnDispositionOrderDetail> {
  return api<ReturnDispositionOrderDetail>(`${BASE}/orders/${id}`);
}

export function recordOrderCost(
  id: string,
  payload: {
    costType: string;
    amount: number;
    currency?: string | undefined;
    vendorRef?: string | undefined;
    incurredAt?: string | undefined;
    note?: string | undefined;
  },
): Promise<ReturnDispositionCostRow> {
  return api<ReturnDispositionCostRow>(`${BASE}/orders/${id}/costs`, 'POST', payload);
}

export function cancelDispositionOrder(
  id: string,
  cancelReason: string,
  expectedVersion?: number,
): Promise<ReturnDispositionOrderRow> {
  const payload: { cancelReason: string; expectedVersion?: number | undefined } = { cancelReason };
  if (expectedVersion !== undefined) payload.expectedVersion = expectedVersion;
  return api<ReturnDispositionOrderRow>(`${BASE}/orders/${id}/cancel`, 'POST', payload);
}

export interface EventQuery {
  registrationId?: string;
  decisionId?: string;
  orderId?: string;
  page?: number;
  size?: number;
}

export function fetchEvents(query: EventQuery = {}): Promise<ReturnDispositionEventPage> {
  const params = new URLSearchParams();
  if (query.registrationId) params.set('registrationId', query.registrationId);
  if (query.decisionId) params.set('decisionId', query.decisionId);
  if (query.orderId) params.set('orderId', query.orderId);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ReturnDispositionEventPage>(`${BASE}/events?${params.toString()}`);
}

// ================================================================ 纯函数（可测）

/** 处置方式的展示名。服务端已给 label，这里兜底未知值。 */
export function dispositionMethodLabel(method: string | null | undefined): string {
  switch (method) {
    case 'RESALE':
      return '转销售（可再售）';
    case 'RETURN_TO_COUNTRY':
      return '退运回国';
    case 'DISCARD':
      return '弃置销毁';
    default:
      return '未决定';
  }
}

/** 处置状态（判定进度）的展示名。 */
export function dispositionStateLabel(state: string | null | undefined): string {
  switch (state) {
    case 'PENDING_DECISION':
      return '待人工决策';
    case 'DECIDED':
      return '已决策待下发';
    case 'ORDERED':
      return '已下发处置单';
    default:
      return '待人工决策';
  }
}

export function warehouseTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'LOCAL':
      return '本地仓';
    case 'OVERSEAS':
      return '海外仓';
    case 'PLATFORM':
      return '平台仓';
    default:
      return '未知仓型';
  }
}

export function costTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'RETURN_FREIGHT':
      return '退货运费';
    case 'DESTROY_FEE':
      return '销毁费';
    case 'PLATFORM_HANDLING_FEE':
      return '平台处理费';
    case 'OTHER':
      return '其他费用';
    default:
      return '其他费用';
  }
}

export function restrictionSubjectLabel(type: string | null | undefined): string {
  switch (type) {
    case 'BRAND':
      return '品牌方要求';
    case 'PLATFORM':
      return '平台政策';
    case 'SKU':
      return '单品限制';
    default:
      return '未知对象';
  }
}

export function restrictionBasisLabel(basis: string | null | undefined): string {
  switch (basis) {
    case 'BRAND_REQUIREMENT':
      return '品牌方要求';
    case 'PLATFORM_POLICY':
      return '平台政策';
    case 'REGULATION':
      return '法规不允许';
    case 'CONTRACT':
      return '合同条款';
    default:
      return '未知依据';
  }
}

/** 金额展示：**原样**显示币种与数额，不做任何换算。 */
export function moneyText(amount: number | null | undefined, currency?: string | null): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return '—';
  const fixed = amount.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 数量展示（数量是 NUMERIC(19,6)，但登记/处置数量都是整数件）。 */
export function quantityText(qty: number | null | undefined): string {
  if (qty === null || qty === undefined || Number.isNaN(qty)) return '—';
  return Number.isInteger(qty) ? String(qty) : qty.toFixed(6);
}

/** 决策表单：处置方式的初值 —— **空串**，代表「还没显式选」。 */
export interface DecisionForm {
  registrationId: string;
  /** 处置方式；空串 = 还没选。**绝不允许**默认成 'RESALE'。 */
  dispositionMethod: string;
  decidedQty: string;
  decisionBasis: string;
}

export function emptyDecisionForm(registrationId = ''): DecisionForm {
  return {
    registrationId,
    dispositionMethod: EMPTY_DISPOSITION_METHOD,
    decidedQty: '',
    decisionBasis: '',
  };
}

/**
 * 决策表单校验。
 *
 * <p>🔴 处置方式为空 ⇒ 直接拒，**不替人选一个**。这是蓝图加粗句在界面上的落点。
 */
export function validateDecisionForm(form: DecisionForm): string {
  if (!form.registrationId) return '必须先选一条接收登记';
  if (!form.dispositionMethod) {
    return '必须显式选择处置方式（转销售 / 退运回国 / 弃置销毁）—— 本模块不替你默认';
  }
  if (!DISPOSITION_METHODS.includes(form.dispositionMethod as DispositionMethod)) {
    return '处置方式必须是转销售 / 退运回国 / 弃置销毁之一';
  }
  const qty = Number(form.decidedQty);
  if (!form.decidedQty.trim() || !Number.isFinite(qty) || qty <= 0) {
    return '处置数量必须大于 0';
  }
  if (!form.decisionBasis.trim()) {
    return '必须写清判定依据：转卖 / 退运 / 弃置对利润影响巨大';
  }
  return '';
}

/** 校验通过后构造请求体（**只在这里**把空串变成拒绝，不在别处兜底）。 */
export function buildDecisionPayload(
  form: DecisionForm,
): DecidePayload | { error: string } {
  const error = validateDecisionForm(form);
  if (error) return { error };
  return {
    registrationId: form.registrationId,
    dispositionMethod: form.dispositionMethod,
    decidedQty: Number(form.decidedQty),
    decisionBasis: form.decisionBasis.trim(),
  };
}

/** 可行性：某个去向是否被服务端允许。 */
export function methodAllowed(
  eligibility: ReturnDispositionEligibility | null,
  method: string,
): boolean {
  if (!eligibility) return false;
  return eligibility.methods.some((m) => m.method === method && m.allowed);
}

/** 可行性：某个去向被挡住的理由（没有理由 = 没被挡住）。 */
export function blockingReasons(
  eligibility: ReturnDispositionEligibility | null,
  method: string,
): string[] {
  if (!eligibility) return [];
  return eligibility.methods.find((m) => m.method === method)?.blockingReasons ?? [];
}

/**
 * 页面上「能不能选」某个去向。
 *
 * <p>🔴 判据是**服务端结论**（{@link methodAllowed}），不是页面自己比对质检与限制。
 * 服务端还没给出结论时一律**不许选**（拿不准 ⇒ 交给人，不是默认转销售）。
 */
export function canSelectMethod(
  eligibility: ReturnDispositionEligibility | null,
  method: string,
): boolean {
  return methodAllowed(eligibility, method);
}

/** 概览里三个去向的分布（按未给定的顺序返回，缺项补 0）。 */
export function dispositionDistribution(overview: ReturnDispositionOverview | null): {
  method: DispositionMethod;
  label: string;
  count: number;
}[] {
  if (!overview) return [];
  return [
    { method: 'RESALE', label: dispositionMethodLabel('RESALE'), count: overview.resaleCount },
    {
      method: 'RETURN_TO_COUNTRY',
      label: dispositionMethodLabel('RETURN_TO_COUNTRY'),
      count: overview.repatriationCount,
    },
    { method: 'DISCARD', label: dispositionMethodLabel('DISCARD'), count: overview.discardCount },
  ];
}

/** 概览里「待人工决策」的接收登记数。 */
export function pendingDecisionCount(overview: ReturnDispositionOverview | null): number {
  return overview?.pendingDecisionCount ?? 0;
}

/** 错误文案：422 / 409 的理由原样透出（服务端写清了「凭什么挡住」）。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 422 || error.status === 409) return error.message;
    if (error.status === 503) return '退货实收 / 质检结论暂时读不到，请稍后重试（不是「没有数据」）';
    if (error.status === 403) return '当前角色没有退货处置的操作权限';
    if (error.status === 0) return '正在连接渠道服务，请稍后重试';
    return error.message;
  }
  return '加载失败，请稍后重试';
}
