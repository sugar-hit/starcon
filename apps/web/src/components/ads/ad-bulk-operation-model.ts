import { api } from '../inventory/api';

/**
 * 14.1.1.3 批量操作 前端模型。
 *
 * 蓝图：「跨活动批量调整：预算增减、竞价调整、状态切换、结束时间设置，支持按筛选结果全选并预览变更。
 * 输入：选中活动集合；调整参数；预览确认。输出：批量调整任务（异步）+ 每条执行结果。
 * 关系：上游 = 14.1.1.1；下游 = 平台广告 API（经 2 队列）。
 * **批量调价要有单次上限与总消耗上限保护**，误操作一次可能烧掉整月预算。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「按筛选结果全选」是服务端能力**。前端**只**把筛选条件递过去（`fetchBulkSelection`），
 *    拿回一页 id 与一个计数 —— **不**把 id 全拉回浏览器再循环。那等于全表扫。
 * 2. **钱的前端不做任何算术**。总预算增量、绝对敞口、护栏裁决全部由服务端 `BigDecimal` 算好返回
 *    （`totalBudgetDelta` / `totalBudgetDeltaAbs` / `allowed` / `rejectionReason`）。
 *    浏览器里算一遍就会引入二进制浮点误差，界面上的数也会和实际执行的不一样。
 * 3. **净增量与绝对敞口是两个数**。「+1000 与 −1000 净变化是 0，但动的是 2000 的钱」——
 *    界面必须两个都显示，只显示净变化会让人以为这批「没动多少钱」。
 * 4. **「已生效」= 本地已生效**。平台回写队列未交付 ⇒ 每条结果都带 `pendingPlatformWriteback`，
 *    界面必须显式写「本地已改、还没回写平台」。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdBulkMoney = number | null;

export interface AdBulkCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

/**
 * 护栏上限。
 *
 * 🔴 界面要能显示「本次最多 N 个」—— 所以这些上限随响应返回，不是散落在代码里的常量。
 */
export interface AdBulkGuardrails {
  maxSingleBudgetDelta: number;
  maxTotalBudgetDelta: number;
  maxCampaignBudget: number;
  maxBid: number;
  maxSingleBidDelta: number;
  maxTotalBidDelta: number;
  maxCampaignsPerTask: number;
  maxGroupsPerTask: number;
  maxSelectablePerFilter: number;
  maxPreviewItems: number;
  note: string;
}

export interface AdBulkCapabilities {
  dimensions: AdBulkCapabilityDimension[];
  operations: string[];
  targetTypes: string[];
  statuses: string[];
  guardrails: AdBulkGuardrails;
  spendImpactNote: string;
  writebackNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdBulkOption {
  value: string;
  label: string;
}

export interface AdBulkOptions {
  platforms: AdBulkOption[];
  shops: AdBulkOption[];
  statuses: AdBulkOption[];
  internalAdTypes: AdBulkOption[];
  operations: AdBulkOption[];
  guardrails: AdBulkGuardrails;
  spendImpactNote: string;
  note: string;
}

/** 服务端按筛选条件解析出的一个目标（活动或广告组）。 */
export interface AdBulkSelectedRow {
  targetType: string;
  campaignId: string;
  campaignName: string;
  shopCode: string;
  platformCode: string;
  status: string;
  dailyBudget: AdBulkMoney;
  currency: string;
  endAt: string | null;
  groupId: string | null;
  groupName: string;
  defaultBid: AdBulkMoney;
  bidCurrency: string;
}

export interface AdBulkSelection {
  items: AdBulkSelectedRow[];
  total: number;
  nextCursor: string | null;
  /** 🔴 命中数超过单次上限 —— 批量任务会**整批拒绝**，必须先缩小筛选范围。 */
  truncated: boolean;
  limit: number;
  note: string;
}

/** 预览与提交共用的请求形状（服务端两个端点吃同一份载荷）。 */
export interface AdBulkOperationRequest {
  platformId?: string | undefined;
  shopCode?: string | undefined;
  status?: string | undefined;
  internalAdType?: string | undefined;
  platformAdType?: string | undefined;
  keyword?: string | undefined;
  campaignIds?: string[] | undefined;
  targetType?: string | undefined;
  operation: string;
  budget?: number | undefined;
  delta?: number | undefined;
  bid?: number | undefined;
  bidDelta?: number | undefined;
  endAt?: string | undefined;
  clearEndAt?: boolean | undefined;
  reason?: string | undefined;
}

export interface AdBulkPreviewItem {
  targetType: string;
  campaignId: string;
  campaignName: string;
  groupId: string | null;
  groupName: string;
  beforeStatus: string;
  afterStatus: string;
  beforeBudget: AdBulkMoney;
  afterBudget: AdBulkMoney;
  budgetDelta: AdBulkMoney;
  beforeBid: AdBulkMoney;
  afterBid: AdBulkMoney;
  bidDelta: AdBulkMoney;
  beforeEndAt: string | null;
  afterEndAt: string | null;
  /** APPLY / SKIP / REJECT / REJECT_LIMIT */
  verdict: string;
  message: string;
}

export interface AdBulkPreview {
  /** 🔴 超上限 ⇒ false ⇒ 整批拒绝（不做部分执行）。 */
  allowed: boolean;
  rejectionReason: string;
  operation: string;
  targetType: string;
  targetCount: number;
  matchedCount: number;
  previewItemCount: number;
  previewTruncated: boolean;
  totalBudgetDelta: number;
  totalBudgetDeltaAbs: number;
  totalBidDelta: number;
  totalBidDeltaAbs: number;
  /** 🔴 恒为 false：没有消耗预估模型，不编一个假的数字出来。 */
  spendImpactEstimated: boolean;
  spendImpactNote: string;
  guardrails: AdBulkGuardrails;
  items: AdBulkPreviewItem[];
  note: string;
}

export interface AdBulkTaskRow {
  id: string;
  operation: string;
  targetType: string;
  /** PENDING / RUNNING / DONE / PARTIAL / FAILED / REJECTED */
  status: string;
  targetCount: number;
  appliedCount: number;
  rejectedCount: number;
  skippedCount: number;
  totalBudgetDelta: number;
  totalBudgetDeltaAbs: number;
  totalBidDelta: number;
  totalBidDeltaAbs: number;
  selectionMode: string;
  selectionSummary: string;
  paramBudget: AdBulkMoney;
  paramDelta: AdBulkMoney;
  paramBid: AdBulkMoney;
  paramBidDelta: AdBulkMoney;
  paramEndAt: string | null;
  paramClearEndAt: boolean;
  reason: string;
  requestedBy: string;
  message: string;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface AdBulkTaskItemRow {
  id: string;
  targetType: string;
  campaignId: string;
  campaignName: string;
  groupId: string | null;
  groupName: string;
  beforeStatus: string;
  afterStatus: string;
  beforeBudget: AdBulkMoney;
  afterBudget: AdBulkMoney;
  beforeBid: AdBulkMoney;
  afterBid: AdBulkMoney;
  beforeEndAt: string | null;
  afterEndAt: string | null;
  /** PENDING / APPLIED / REJECTED / SKIPPED */
  result: string;
  message: string;
  /** 🔴 本地已改、尚未回写平台。 */
  pendingPlatformWriteback: boolean;
  executedAt: string | null;
}

export interface AdBulkTaskDetail {
  task: AdBulkTaskRow;
  items: AdBulkTaskItemRow[];
  itemTotal: number;
  page: number;
  size: number;
  note: string;
}

export interface AdBulkTaskPage {
  items: AdBulkTaskRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdBulkTaskSubmission {
  task: AdBulkTaskRow;
  claimed: boolean;
  note: string;
}

// ==================================================================
// 请求
// ==================================================================

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

export function fetchBulkCapabilities(): Promise<AdBulkCapabilities> {
  return api<AdBulkCapabilities>('channel/ad-bulk-operations/capabilities');
}

export function fetchBulkOptions(): Promise<AdBulkOptions> {
  return api<AdBulkOptions>('channel/ad-bulk-operations/options');
}

/**
 * 按筛选条件解析出目标 id 集合的一页。
 *
 * 🔴 **服务端解析**：前端只递筛选条件，拿回一页 id 与一个命中计数。
 * 绝不能把整张表拉回浏览器再循环 —— 那等于全表扫。
 */
export function fetchBulkSelection(query: {
  platformId?: string | undefined;
  shopCode?: string | undefined;
  status?: string | undefined;
  internalAdType?: string | undefined;
  platformAdType?: string | undefined;
  keyword?: string | undefined;
  targetType?: string | undefined;
  cursor?: string | undefined;
  size?: number | undefined;
}): Promise<AdBulkSelection> {
  return api<AdBulkSelection>(`channel/ad-bulk-operations/selection${queryString(query)}`);
}

/** 预览变更（**不落库**）。 */
export function previewBulkOperation(request: AdBulkOperationRequest): Promise<AdBulkPreview> {
  return api<AdBulkPreview>('channel/ad-bulk-operations/preview', 'POST', request);
}

/** 提交批量任务（异步）：受理即返回任务，逐条结果事后查。 */
export function submitBulkTask(request: AdBulkOperationRequest): Promise<AdBulkTaskSubmission> {
  return api<AdBulkTaskSubmission>('channel/ad-bulk-operations/tasks', 'POST', request);
}

export function fetchBulkTasks(page = 0, size = 20): Promise<AdBulkTaskPage> {
  return api<AdBulkTaskPage>(`channel/ad-bulk-operations/tasks${queryString({ page, size })}`);
}

export function fetchBulkTask(id: string, page = 0, size = 200): Promise<AdBulkTaskDetail> {
  return api<AdBulkTaskDetail>(
    `channel/ad-bulk-operations/tasks/${id}${queryString({ page, size })}`,
  );
}

// ==================================================================
// 展示辅助
// ==================================================================

export function bulkOperationLabel(operation: string): string {
  if (operation === 'ADJUST_BUDGET') return '预算增减';
  if (operation === 'SET_BUDGET') return '设为预算';
  if (operation === 'PAUSE') return '暂停';
  if (operation === 'ENABLE') return '启用';
  if (operation === 'SET_END_AT') return '设置结束时间';
  if (operation === 'ADJUST_BID') return '调整竞价';
  return operation;
}

/** 🔴 竞价落在**广告组**上 —— 界面上要把落点写出来，别让人以为是改活动。 */
export function bulkTargetLabel(targetType: string): string {
  if (targetType === 'AD_GROUP') return '广告组';
  if (targetType === 'CAMPAIGN') return '活动';
  return targetType;
}

export function bulkTaskStatusLabel(status: string): string {
  if (status === 'PENDING') return '已受理，等待执行';
  if (status === 'RUNNING') return '执行中';
  if (status === 'DONE') return '全部完成';
  if (status === 'PARTIAL') return '部分完成（有条被拒）';
  if (status === 'FAILED') return '执行失败';
  if (status === 'REJECTED') return '整批被拒（一条都没执行）';
  return status;
}

export function bulkTaskStatusTone(status: string): string {
  if (status === 'REJECTED' || status === 'FAILED') return 'text-destructive';
  if (status === 'PARTIAL') return 'text-amber-600';
  if (status === 'DONE') return 'text-emerald-600';
  return 'text-muted-foreground';
}

export function bulkItemResultLabel(result: string): string {
  if (result === 'PENDING') return '等待执行';
  if (result === 'APPLIED') return '已生效';
  if (result === 'REJECTED') return '被拒';
  if (result === 'SKIPPED') return '跳过';
  return result;
}

export function bulkItemResultTone(result: string): string {
  if (result === 'REJECTED') return 'text-destructive';
  if (result === 'SKIPPED' || result === 'PENDING') return 'text-muted-foreground';
  return 'text-emerald-600';
}

export function bulkVerdictLabel(verdict: string): string {
  if (verdict === 'APPLY') return '会执行';
  if (verdict === 'SKIP') return '无需执行';
  if (verdict === 'REJECT') return '这一条会被拒';
  if (verdict === 'REJECT_LIMIT') return '🔴 越上限（整批拒绝）';
  return verdict;
}

export function bulkVerdictTone(verdict: string): string {
  if (verdict === 'REJECT_LIMIT' || verdict === 'REJECT') return 'text-destructive';
  if (verdict === 'SKIP') return 'text-muted-foreground';
  return 'text-emerald-600';
}

export function bulkMoney(value: AdBulkMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

export function bulkTimestamp(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 护栏上限的一行说明（界面上要把天花板写出来，而不是让人撞墙才知道）。 */
export function bulkGuardrailText(guardrails: AdBulkGuardrails | null): string {
  if (!guardrails) return '';
  return (
    `单次单活动预算调整上限 ${guardrails.maxSingleBudgetDelta}，一次批量总额上限 ${guardrails.maxTotalBudgetDelta}，` +
    `单活动预算上限 ${guardrails.maxCampaignBudget}；单组竞价上限 ${guardrails.maxBid}，` +
    `单次竞价调整上限 ${guardrails.maxSingleBidDelta}，竞价总额上限 ${guardrails.maxTotalBidDelta}；` +
    `一个任务最多 ${guardrails.maxCampaignsPerTask} 个活动 / ${guardrails.maxGroupsPerTask} 个广告组，` +
    `一次筛选最多解析 ${guardrails.maxSelectablePerFilter} 条。`
  );
}

/** 目标名：广告组落点时带上组名，别让人只看到活动名。 */
export function bulkTargetName(item: {
  targetType: string;
  campaignName: string;
  groupName?: string;
}): string {
  if (item.targetType === 'AD_GROUP' && item.groupName) {
    return `${item.campaignName} / ${item.groupName}`;
  }
  return item.campaignName;
}

// ==================================================================
// 表单校验
// ==================================================================

export interface AdBulkFormInput {
  operation: string;
  targetType: string;
  budget: string;
  delta: string;
  bid: string;
  bidDelta: string;
  endAt: string;
  clearEndAt: boolean;
  reason: string;
}

export const emptyAdBulkFormInput: AdBulkFormInput = {
  operation: 'ADJUST_BUDGET',
  targetType: 'CAMPAIGN',
  budget: '',
  delta: '',
  bid: '',
  bidDelta: '',
  endAt: '',
  clearEndAt: false,
  reason: '',
};

/**
 * 表单校验（护栏的数值由服务端最终裁决，这里只挡住明显错的）。
 *
 * 🔴 「竞价必须是广告组落点」这条在**前端**也挡一次 ——
 * 界面若让人选一个不存在的组合，点了提交只会拿到一句 400，体验很差。
 */
export function validateAdBulkForm(input: AdBulkFormInput): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  const operations = [
    'ADJUST_BUDGET',
    'SET_BUDGET',
    'PAUSE',
    'ENABLE',
    'SET_END_AT',
    'ADJUST_BID',
  ];
  if (!operations.includes(input.operation)) errors.push('操作类型不合法');
  if (input.targetType === 'AD_GROUP' && input.operation !== 'ADJUST_BID') {
    errors.push('广告组落点只用于竞价调整');
  }
  if (input.operation === 'ADJUST_BID' && input.targetType !== 'AD_GROUP') {
    errors.push('竞价调整的落点必须是广告组');
  }
  if (input.operation === 'SET_BUDGET') {
    const parsed = Number(input.budget);
    if (input.budget.trim() === '' || Number.isNaN(parsed) || parsed < 0) {
      errors.push('目标预算必须是不小于 0 的数字');
    }
  }
  if (input.operation === 'ADJUST_BUDGET') {
    const parsed = Number(input.delta);
    if (input.delta.trim() === '' || Number.isNaN(parsed) || parsed === 0) {
      errors.push('预算增减额必须是非 0 的数字（可以是负数）');
    }
  }
  if (input.operation === 'ADJUST_BID') {
    const hasBid = input.bid.trim() !== '';
    const hasDelta = input.bidDelta.trim() !== '';
    if (hasBid === hasDelta) {
      errors.push('竞价调整必须给出目标竞价或竞价增减额，二选一');
    }
    if (hasBid) {
      const parsed = Number(input.bid);
      if (Number.isNaN(parsed) || parsed < 0) errors.push('目标竞价必须是不小于 0 的数字');
    }
    if (hasDelta) {
      const parsed = Number(input.bidDelta);
      if (Number.isNaN(parsed) || parsed === 0) errors.push('竞价增减额必须是非 0 的数字');
    }
  }
  if (input.operation === 'SET_END_AT') {
    if (!input.clearEndAt) {
      if (input.endAt.trim() === '') {
        errors.push('必须给出目标结束时刻，或勾选「清空结束时间」');
      } else if (Number.isNaN(new Date(input.endAt).getTime())) {
        errors.push('结束时间不是合法时间');
      }
    }
  }
  return { ok: errors.length === 0, errors };
}
