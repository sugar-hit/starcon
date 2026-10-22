import { api } from '../../lib/api';

/**
 * 16.1.2.1 违规与处罚 前端模型。
 *
 * 蓝图：「记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施（警告/下架/限制销售/扣款/停号）、
 * 处理状态与整改要求。输入：平台违规通知同步；整改记录。输出：违规记录 + 整改跟踪 + 扣款金额（进 12 财务）。
 * 关系：上游 = 2 同步；下游 = 12 财务（罚扣款）、4 刊登（问题商品下架）、16.1.2.2 申诉。
 * **违规常涉及多个商品**，需批量定位受影响 listing 与库存。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **扣款金额永远带币种**，而且**没有跨币种的总数** —— 页面上把每个币种各占一行，
 *    并写明「不同币种未折算，未合计」。把 100 USD 与 100 EUR 加成「200」是最容易犯、
 *    也最没人能发现的错。
 * 2. **「尚无处罚措施」不等于「没问题」** —— 它有自己的色调与文案，
 *    不与「轻微（警告级）」共用一个绿。
 * 3. **「读不到库存」不等于「没有库存」** —— 上游不可达时显示「读不到」，
 *    绝不显示成「可售 0」。
 * 4. **状态机在服务端**：页面只显示后端给出的合法下一步，不自己拼。
 * 5. **未交付的下游灰掉**：12 财务（罚扣款）与 16.1.2.2 申诉管理当前**都未交付**，
 *    界面上如实标出，**不编**放款记录、**不编**申诉单。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/platform-violations';

/** 🔴 处罚措施值域（与后端 CHECK 逐字一致）：警告 / 下架 / 限制销售 / 扣款 / 停号。 */
export const MEASURES = [
  'WARNING',
  'DELIST',
  'RESTRICT_SALES',
  'DEDUCTION',
  'SUSPEND_ACCOUNT',
] as const;

/** 处理状态值域。 */
export const STATUSES = ['PENDING', 'IN_PROGRESS', 'RECTIFIED', 'APPEALING', 'CLOSED'] as const;

/** 整改状态值域。 */
export const REMEDIATION_STATUSES = [
  'OPEN',
  'IN_PROGRESS',
  'SUBMITTED',
  'VERIFIED',
  'REJECTED',
] as const;

/** 涉及主体类型值域。 */
export const SUBJECT_TYPES = ['SKU', 'LISTING', 'ORDER'] as const;

/** 🔴 来源值域：平台同步来的 / 人工登记的。 */
export const SOURCES = ['PLATFORM_SYNC', 'MANUAL'] as const;

/** 处理状态筛选的可选项。 */
export const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '未处理' },
  { value: 'IN_PROGRESS', label: '处理中' },
  { value: 'RECTIFIED', label: '已整改' },
  { value: 'APPEALING', label: '申诉中' },
  { value: 'CLOSED', label: '已关闭' },
] as const;

/** 处罚措施筛选的可选项。 */
export const MEASURE_OPTIONS = [
  { value: '', label: '全部措施' },
  { value: 'WARNING', label: '警告' },
  { value: 'DELIST', label: '下架' },
  { value: 'RESTRICT_SALES', label: '限制销售' },
  { value: 'DEDUCTION', label: '扣款' },
  { value: 'SUSPEND_ACCOUNT', label: '停号' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个下游的交付状态。 */
export interface PlatformViolationCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface PlatformViolationCapabilities {
  gaps: PlatformViolationCapabilityGap[];
  violationTypes: string[];
  violationTypeLabels: Record<string, string>;
  measures: string[];
  measureLabels: Record<string, string>;
  statuses: string[];
  statusLabels: Record<string, string>;
  statusTransitions: Record<string, string[]>;
  remediationStatuses: string[];
  remediationStatusLabels: Record<string, string>;
  remediationStatusTransitions: Record<string, string[]>;
  subjectTypes: string[];
  sources: string[];
  impactLevels: string[];
  typeDomainNote: string;
  moneySemantics: string;
  impactSemantics: string;
  stateMachineSemantics: string;
  idempotencySemantics: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 店铺在本模块里的覆盖情况。 */
export interface PlatformViolationShop {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  platformName: string;
  violationCount: number;
  openCount: number;
  pendingDeductionCount: number;
}

/** 店铺范围清单。 */
export interface PlatformViolationShopPage {
  items: PlatformViolationShop[];
  total: number;
  page: number;
  size: number;
}

/** 一条违规记录。 */
export interface PlatformViolation {
  violationId: string;
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  /** 🔴 平台侧通知 id（幂等锚点） */
  externalNoticeId: string;
  source: string;
  sourceLabel: string;
  violationType: string;
  violationTypeLabel: string;
  title: string;
  description: string;
  occurredAt: string;
  notifiedAt: string | null;
  status: string;
  statusLabel: string;
  /** 服务端算出的合法下一步（页面不自己拼） */
  nextStatuses: string[];
  deadline: string | null;
  /** 🔴 派生的影响档位 */
  impactLevel: string;
  impactLabel: string;
  measures: string[];
  measureLabels: string[];
  subjectCount: number;
  penaltyCount: number;
  remediationCount: number;
  openRemediationCount: number;
  createdAt: string;
}

/** 违规记录清单。 */
export interface PlatformViolationPage {
  items: PlatformViolation[];
  total: number;
  page: number;
  size: number;
  pendingCount: number;
  inProgressCount: number;
  rectifiedCount: number;
  appealingCount: number;
  closedCount: number;
  note: string;
}

/** 一条处罚措施。 */
export interface PlatformPenalty {
  penaltyId: string;
  violationId: string;
  measure: string;
  measureLabel: string;
  /** 🔴 null = 这条措施**不带金额**（不是「金额是 0」） */
  penaltyAmount: number | null;
  /** 🔴 与金额成对出现 */
  penaltyCurrency: string | null;
  moneyText: string;
  effectiveAt: string;
  endsAt: string | null;
  status: string;
  note: string;
}

/** 某个币种的扣款合计。 */
export interface PlatformPenaltyTotal {
  currency: string;
  amount: number;
  penaltyCount: number;
}

/** 处罚清单 + **按币种分组的合计**。 */
export interface PlatformPenaltyPage {
  items: PlatformPenalty[];
  total: number;
  page: number;
  size: number;
  /** 🔴 按币种分组，**没有**跨币种的总数 */
  totalsByCurrency: PlatformPenaltyTotal[];
  totalNote: string;
  note: string;
}

/** 违规涉及的一个主体。 */
export interface PlatformViolationSubject {
  subjectId: string;
  violationId: string;
  subjectType: string;
  subjectTypeLabel: string;
  subjectRef: string;
  skuId: string | null;
  listingId: string | null;
  orderRef: string | null;
  affectedQuantity: number | null;
  note: string;
}

/** 批量挂涉及主体的结果。 */
export interface PlatformViolationSubjectBatchResult {
  violationId: string;
  requested: number;
  inserted: number;
  skipped: number;
  skippedRefs: string[];
  note: string;
}

/** 一条整改要求。 */
export interface PlatformViolationRemediation {
  remediationId: string;
  violationId: string;
  requirement: string;
  dueAt: string | null;
  /** 🔴 派生的「已逾期」 */
  overdue: boolean;
  status: string;
  statusLabel: string;
  nextStatuses: string[];
  ownerRef: string;
  completedAt: string | null;
  verifiedAt: string | null;
  evidenceCount: number;
  createdAt: string;
}

/** 整改要求清单。 */
export interface PlatformViolationRemediationPage {
  items: PlatformViolationRemediation[];
  total: number;
  page: number;
  size: number;
  overdueCount: number;
  note: string;
}

/** 一条整改凭证（追加式）。 */
export interface PlatformViolationEvidence {
  evidenceId: string;
  remediationId: string;
  violationId: string;
  evidenceType: string;
  evidenceRef: string;
  summary: string;
  submittedAt: string;
}

/** 受影响的一个平台商品。 */
export interface PlatformViolationAffectedListing {
  listingId: string;
  externalItemId: string;
  title: string;
  status: string;
  statusLabel: string;
  platformStockState: string;
  sourceSubjectType: string;
  sourceSubjectRef: string;
}

/** 受影响商品的可售量。 */
export interface PlatformViolationAffectedInventory {
  skuId: string;
  skuCode: string | null;
  /** 🔴 null = **读不到**或**没有库存行**，**不是 0** */
  sellable: number | null;
  found: boolean;
  note: string;
}

/** 批量定位的结果。 */
export interface PlatformViolationAffected {
  violationId: string;
  subjectCount: number;
  listingCount: number;
  listings: PlatformViolationAffectedListing[];
  /** 🔴 false ⇒ 库存那一段是「读不到」，**不是「没有库存」** */
  inventoryAvailable: boolean;
  inventory: PlatformViolationAffectedInventory[];
  inventoryNote: string;
  note: string;
}

/** 批量下架的结果。 */
export interface PlatformViolationDelistResult {
  violationId: string;
  candidateCount: number;
  affectedCount: number;
  skipCount: number;
  skippedListingIds: string[];
  outcome: string;
  reason: string;
  ownerModule: string;
  taskId: string;
}

/** 违规详情。 */
export interface PlatformViolationDetail {
  violation: PlatformViolation;
  penalties: PlatformPenalty[];
  subjects: PlatformViolationSubject[];
  remediations: PlatformViolationRemediation[];
  evidence: PlatformViolationEvidence[];
  note: string;
}

/** 一次同步的结果。 */
export interface PlatformViolationSyncResult {
  runId: string;
  shopId: string;
  fetched: number;
  written: number;
  /** 🔴 幂等跳过的条数 */
  skipped: number;
  failed: number;
  failures: string[];
  note: string;
}

/** 同步运行记录。 */
export interface PlatformViolationSyncRun {
  runId: string;
  shopId: string;
  shopCode: string;
  status: string;
  startedAt: string;
  finishedAt: string | null;
  fetchedCount: number;
  writtenCount: number;
  skippedCount: number;
  failedCount: number;
  reason: string;
}

/** 同步运行记录清单。 */
export interface PlatformViolationSyncRunPage {
  items: PlatformViolationSyncRun[];
  total: number;
  page: number;
  size: number;
  note: string;
}

// ==================================================================
// API
// ==================================================================

export async function fetchCapabilities(): Promise<PlatformViolationCapabilities> {
  return api<PlatformViolationCapabilities>(`${BASE}/capabilities`);
}

export async function fetchShops(
  page = 0,
  size = 50,
  filters: { platformCode?: string; keyword?: string } = {},
): Promise<PlatformViolationShopPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  return api<PlatformViolationShopPage>(`${BASE}/shops?${query.toString()}`);
}

export interface ViolationFilters {
  shopId?: string;
  status?: string;
  violationType?: string;
  measure?: string;
  source?: string;
  keyword?: string;
}

export async function fetchViolations(
  page = 0,
  size = 20,
  filters: ViolationFilters = {},
): Promise<PlatformViolationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.status) query.set('status', filters.status);
  if (filters.violationType) query.set('violationType', filters.violationType);
  if (filters.measure) query.set('measure', filters.measure);
  if (filters.source) query.set('source', filters.source);
  if (filters.keyword) query.set('keyword', filters.keyword);
  return api<PlatformViolationPage>(`${BASE}/violations?${query.toString()}`);
}

export async function fetchViolation(violationId: string): Promise<PlatformViolationDetail> {
  return api<PlatformViolationDetail>(`${BASE}/violations/${violationId}`);
}

export async function registerViolation(input: {
  shopId: string;
  externalNoticeId: string;
  violationType: string;
  title: string;
  description: string;
  occurredAt: string;
  notifiedAt?: string | null;
  deadline?: string | null;
}): Promise<{ created: boolean; note: string }> {
  return api(`${BASE}/violations`, 'POST', input);
}

export async function transitionViolation(
  violationId: string,
  status: string,
): Promise<PlatformViolation> {
  return api<PlatformViolation>(`${BASE}/violations/${violationId}/status`, 'POST', { status, note: '' });
}

export async function addPenalty(
  violationId: string,
  input: {
    measure: string;
    penaltyAmount?: number | null;
    penaltyCurrency?: string | null;
    effectiveAt?: string | null;
    endsAt?: string | null;
    note: string;
  },
): Promise<PlatformPenalty> {
  return api<PlatformPenalty>(`${BASE}/violations/${violationId}/penalties`, 'POST', input);
}

export async function attachSubjects(
  violationId: string,
  subjects: {
    subjectType: string;
    subjectRef: string;
    skuId?: string | null;
    listingId?: string | null;
    orderRef?: string | null;
    affectedQuantity?: number | null;
    note: string;
  }[],
): Promise<PlatformViolationSubjectBatchResult> {
  return api<PlatformViolationSubjectBatchResult>(
    `${BASE}/violations/${violationId}/subjects`,
    'POST',
    subjects,
  );
}

export async function fetchAffected(
  violationId: string,
  warehouseId?: string,
): Promise<PlatformViolationAffected> {
  const query = new URLSearchParams();
  if (warehouseId) query.set('warehouseId', warehouseId);
  const suffix = query.toString();
  return api<PlatformViolationAffected>(
    suffix ? `${BASE}/violations/${violationId}/affected?${suffix}` : `${BASE}/violations/${violationId}/affected`,
  );
}

export async function delistAffected(violationId: string): Promise<PlatformViolationDelistResult> {
  return api<PlatformViolationDelistResult>(`${BASE}/violations/${violationId}/delist`, 'POST', {});
}

export async function addRemediation(
  violationId: string,
  input: { requirement: string; dueAt?: string | null; ownerRef: string },
): Promise<PlatformViolationRemediation> {
  return api<PlatformViolationRemediation>(
    `${BASE}/violations/${violationId}/remediations`,
    'POST',
    input,
  );
}

export async function fetchRemediations(
  page = 0,
  size = 20,
  filters: { shopId?: string; violationId?: string; status?: string; overdueOnly?: string } = {},
): Promise<PlatformViolationRemediationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.violationId) query.set('violationId', filters.violationId);
  if (filters.status) query.set('status', filters.status);
  if (filters.overdueOnly) query.set('overdueOnly', filters.overdueOnly);
  return api<PlatformViolationRemediationPage>(`${BASE}/remediations?${query.toString()}`);
}

export async function transitionRemediation(
  remediationId: string,
  status: string,
): Promise<PlatformViolationRemediation> {
  return api<PlatformViolationRemediation>(`${BASE}/remediations/${remediationId}`, 'PUT', {
    status,
    note: '',
  });
}

export async function addEvidence(
  remediationId: string,
  input: { evidenceType: string; evidenceRef: string; summary: string },
): Promise<PlatformViolationEvidence> {
  return api<PlatformViolationEvidence>(
    `${BASE}/remediations/${remediationId}/evidence`,
    'POST',
    input,
  );
}

export async function fetchPenalties(
  page = 0,
  size = 20,
  filters: { shopId?: string; violationId?: string; measure?: string } = {},
): Promise<PlatformPenaltyPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.violationId) query.set('violationId', filters.violationId);
  if (filters.measure) query.set('measure', filters.measure);
  return api<PlatformPenaltyPage>(`${BASE}/penalties?${query.toString()}`);
}

export async function syncNotices(
  shopId: string,
  notices: {
    externalNoticeId: string;
    violationType: string;
    title: string;
    description: string;
    occurredAt: string;
    notifiedAt?: string | null;
    deadline?: string | null;
    subjects?: {
      subjectType: string;
      subjectRef: string;
      skuId?: string | null;
      listingId?: string | null;
      orderRef?: string | null;
      affectedQuantity?: number | null;
      note: string;
    }[];
    penalties?: {
      measure: string;
      penaltyAmount?: number | null;
      penaltyCurrency?: string | null;
      effectiveAt?: string | null;
      endsAt?: string | null;
      note: string;
    }[];
  }[],
): Promise<PlatformViolationSyncResult> {
  return api<PlatformViolationSyncResult>(`${BASE}/shops/${shopId}/sync`, 'POST', { notices });
}

export async function fetchSyncRuns(
  page = 0,
  size = 20,
  filters: { shopId?: string } = {},
): Promise<PlatformViolationSyncRunPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.shopId) query.set('shopId', filters.shopId);
  return api<PlatformViolationSyncRunPage>(`${BASE}/sync-runs?${query.toString()}`);
}

// ==================================================================
// 展示口径（**不重算后端的结论**）
// ==================================================================

/**
 * 🔴 扣款金额的展示串。
 *
 * <p>没有金额时返回**空串**，**不是 "0"** —— 「这条措施没有金额」与「这条措施金额是 0」
 * 是两件不同的事。
 */
export function moneyText(amount: number | null | undefined, currency: string | null | undefined) {
  if (amount === null || amount === undefined) return '';
  return `${amount.toFixed(2)} ${currency ?? ''}`.trim();
}

/**
 * 🔴 扣款合计的展示：**按币种各占一行**，**没有**一个跨币种的总数。
 *
 * <p>多个币种时明确写「不同币种未折算，未合计」—— 让看的人知道这里**没有**一个总数，
 * 而不是以为页面漏算了。
 */
export function totalsText(totals: PlatformPenaltyTotal[] | undefined | null): string {
  if (!totals || totals.length === 0) return '暂无扣款';
  const lines = totals.map((t) => `${t.currency} ${t.amount.toFixed(2)}`);
  if (totals.length === 1) return lines[0] ?? '';
  return `${lines.join(' / ')}（不同币种未折算，未合计）`;
}

/** 影响档位的人话。 */
export function impactLabel(level: string | null | undefined): string {
  switch (level) {
    case 'CRITICAL':
      return '致命（停号级）';
    case 'HIGH':
      return '严重（下架级）';
    case 'MEDIUM':
      return '中等（限制销售 / 扣款级）';
    case 'LOW':
      return '轻微（警告级）';
    case 'NONE':
      return '尚无处罚措施';
    default:
      return level ?? '未声明';
  }
}

/**
 * 🔴 影响档位的色调。
 *
 * <p>「尚无处罚措施」用 **neutral** 而不是 success —— 一条没有任何处罚的违规
 * **仍然需要处理**，把它显示成绿色是最容易让人漏掉的一种撒谎。
 */
export function impactTone(level: string | null | undefined): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (level) {
    case 'CRITICAL':
      return 'danger';
    case 'HIGH':
      return 'danger';
    case 'MEDIUM':
      return 'warning';
    case 'LOW':
      return 'success';
    case 'NONE':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 处理状态的人话。 */
export function statusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'PENDING':
      return '未处理';
    case 'IN_PROGRESS':
      return '处理中';
    case 'RECTIFIED':
      return '已整改';
    case 'APPEALING':
      return '申诉中';
    case 'CLOSED':
      return '已关闭';
    default:
      return status ?? '未声明';
  }
}

/** 处理状态的色调。 */
export function statusTone(status: string | null | undefined): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (status) {
    case 'PENDING':
      return 'danger';
    case 'IN_PROGRESS':
      return 'warning';
    case 'RECTIFIED':
      return 'success';
    case 'APPEALING':
      return 'warning';
    case 'CLOSED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 处罚措施的人话。 */
export function measureLabel(measure: string | null | undefined): string {
  switch (measure) {
    case 'WARNING':
      return '警告';
    case 'DELIST':
      return '下架';
    case 'RESTRICT_SALES':
      return '限制销售';
    case 'DEDUCTION':
      return '扣款';
    case 'SUSPEND_ACCOUNT':
      return '停号';
    default:
      return measure ?? '未声明';
  }
}

/** 整改状态的人话。 */
export function remediationStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'OPEN':
      return '待整改';
    case 'IN_PROGRESS':
      return '整改中';
    case 'SUBMITTED':
      return '已提交（待验收）';
    case 'VERIFIED':
      return '已验收';
    case 'REJECTED':
      return '已驳回（需重新整改）';
    default:
      return status ?? '未声明';
  }
}

/** 来源的人话。 */
export function sourceLabel(source: string | null | undefined): string {
  if (source === 'PLATFORM_SYNC') return '平台同步';
  if (source === 'MANUAL') return '人工登记';
  return source ?? '未声明';
}

/**
 * 🔴 库存可售量的展示：**读不到 / 没有库存行**显示成「未知」，
 * **绝不显示成 0** —— 「没查到」和「确实是 0」是两件事。
 */
export function sellableText(row: PlatformViolationAffectedInventory): string {
  if (!row.found || row.sellable === null || row.sellable === undefined) return '未知';
  return row.sellable.toFixed(2);
}

/** 违规类型的人话。 */
export function violationTypeLabel(code: string | null | undefined): string {
  switch (code) {
    case 'PRODUCT_IP_INFRINGEMENT':
      return '知识产权侵权';
    case 'PRODUCT_SAFETY':
      return '产品安全（不合规 / 危险品）';
    case 'PRODUCT_MISREPRESENTATION':
      return '商品信息不实';
    case 'PROHIBITED_ITEM':
      return '违禁品';
    case 'RESTRICTED_ITEM':
      return '禁限售';
    case 'ACCOUNT_HEALTH':
      return '账号健康类违规';
    case 'FULFILLMENT':
      return '履约违规';
    case 'CONTENT':
      return '详情页内容违规';
    case 'CUSTOMER_SERVICE':
      return '客诉与客服违规';
    case 'OTHER':
      return '其他';
    default:
      return code ?? '未声明';
  }
}

// ==================================================================
// 空态（三态）
// ==================================================================

/** 空态的种类。 */
export type EmptyKind = 'NO_SHOP' | 'NO_DATA' | 'NO_MATCH';

/**
 * 🔴 空态三态：**没有店铺** / **还没登记过违规** / **筛选无结果** —— 三种情况分开说。
 *
 * <p>把它们混成一句「暂无数据」是最省事也最没用的做法：运营看到「暂无数据」
 * 不知道该去登记一条、还是该换个筛选条件。
 */
export function emptyStateKind(
  shopTotal: number,
  violationTotal: number,
  filtered: boolean,
): EmptyKind {
  if (shopTotal === 0) return 'NO_SHOP';
  if (violationTotal === 0 && !filtered) return 'NO_DATA';
  return 'NO_MATCH';
}

/** 空态的标题与说明。 */
export function emptyState(kind: EmptyKind): { title: string; description: string } {
  switch (kind) {
    case 'NO_SHOP':
      return {
        title: '还没有店铺',
        description: '违规是**店铺维度**的事实：先在 02 渠道与店铺里登记店铺，再回来登记违规。',
      };
    case 'NO_DATA':
      return {
        title: '还没有登记过任何违规',
        description:
          '本模块的违规来自**平台违规通知同步**或**人工登记**（带平台通知 id，幂等）。现在一条都没有。',
      };
    default:
      return {
        title: '筛选条件下没有违规',
        description: '当前筛选（状态 / 措施 / 店铺 / 关键词）没有命中任何一条违规，换个条件再试。',
      };
  }
}
