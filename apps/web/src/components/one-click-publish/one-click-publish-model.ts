import { api } from '../inventory/api';

/**
 * 4.3.1.1 一键刊登 前端模型。
 *
 * 蓝图：「单个 SKU 一次性发布到多个目标店铺/站点：自动套模板、填充内容、建立映射、上传媒体、设置价格库存、提交平台审核。输入：源 SKU；目标店铺与站点集合；模板选择；价格库存来源策略；`idempotency_key`。输出：N 个发布任务（每个目标一个）+ 各任务的进度与结果（草稿/审核中/成功/驳回+原因）。关系：上游 = 4.2.1.1、5 定价（取价）、7 库存（取可售量）、02 平台能力；下游 = 2.5.1.1 回写任务。**跨平台发布不是一个事务**：某个平台失败不影响其他平台，各自独立重试。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **进度与结论不重算**。`ListingPublishJobRow.progress` 的五个计数、百分比与总体结论
 *    **全部由服务端算好**（它是目标行的聚合，落库就会漂）。前端原样显示，一个数都不自己算。
 *
 * 2. **「审核中」不是「已发布」**。`SUBMITTED` 的徽章是「审核中（等平台回执）」，
 *    与「已成功」分开 —— 说成「已发布」就是撒谎。
 *
 * 3. **取价不可用就显示不可用**。5.1.1.1 未交付时价格状态是 `UNAVAILABLE`，
 *    界面原样显示「取价未接入（归 5.1.1.1）」，**不编一个价**、也不显示成一个看起来正常的 0。
 *
 * 4. **重试只重试失败的那一个**。界面上没有「全部重试」入口；被驳回的目标**不能**重试
 *    （内容没改，重发只会再被驳回一次），按钮根本不出现。
 *
 * 5. **跨平台不是一个事务**。结果清单逐目标列，某个目标失败时其余目标照旧显示为
 *    「审核中」；汇总文案说「部分目标还没出结果」，**不**写成「整批失败」。
 */

export interface ListingPublishCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingPublishCapabilities {
  dimensions: ListingPublishCapabilityDimension[];
  contentSources: string[];
  priceSources: string[];
  stockSources: string[];
  jobStates: string[];
  targetStates: string[];
  stepStates: string[];
  stepKeys: string[];
  maxTargets: number;
  nonTransactionalNote: string;
  idempotencyNote: string;
  snapshotNote: string;
  relationNote: string;
}

export interface ListingPublishTargetOption {
  shopId: string;
  shopCode: string;
  platformId: string;
  platformCode: string;
  platformName: string;
  supportStatus: string;
  marketplaceId: string;
  marketplaceCode: string;
  marketplaceName: string;
  countryCode: string;
  defaultLocale: string;
  capabilities: Record<string, boolean>;
}

export interface ListingPublishEditorSubmissionOption {
  submissionId: string;
  platformCode: string;
  shopId: string;
  shopCode: string;
  marketplaceId: string;
  marketplaceCode: string;
  locale: string;
  state: string;
  title: string;
  imageCount: number;
  queuedAt: string;
}

export interface ListingPublishTemplateOption {
  templateId: string;
  code: string;
  name: string;
  scope: string;
  scopeTarget: string;
  platformId: string;
  platformCode: string;
  marketplaceId: string | null;
  marketplaceCode: string | null;
  language: string;
  status: string;
  fieldCount: number;
  assetGroupCount: number;
}

export interface ListingPublishContentSources {
  editorSubmissions: ListingPublishEditorSubmissionOption[];
  templates: ListingPublishTemplateOption[];
}

export interface ListingPublishTargetInput {
  shopId: string;
  marketplaceId: string;
  locale: string;
  categoryPath: string;
}

export interface CreateListingPublishJobRequest {
  sourceSkuId: string;
  targets: ListingPublishTargetInput[];
  contentSource: string;
  contentSourceIds: string[];
  priceSource: string;
  priceAmount?: number;
  priceCurrency?: string;
  stockSource: string;
  warehouseId?: string;
  idempotencyKey: string;
  note: string;
}

export interface ListingPublishProgress {
  total: number;
  draft: number;
  inReview: number;
  succeeded: number;
  rejected: number;
  failed: number;
  percent: number;
  outcome: string;
}

export interface ListingPublishJobRow {
  jobId: string;
  sourceSkuId: string;
  contentSource: string;
  contentSourceIds: string[];
  priceSource: string;
  priceAmount: number | null;
  priceCurrency: string | null;
  priceState: string;
  priceStateReason: string;
  stockSource: string;
  stockQuantity: number | null;
  stockState: string;
  stockStateReason: string;
  state: string;
  primaryShopId: string;
  idempotencyKey: string;
  note: string;
  progress: ListingPublishProgress;
  createdAt: string;
  updatedAt: string;
  dispatchedAt: string | null;
  completedAt: string | null;
}

export interface ListingPublishContentField {
  field: string;
  sourceKind: string;
  status: string;
  ownerModule: string;
  note: string;
}

export interface ListingPublishContentSnapshot {
  title: string | null;
  bullets: string[] | null;
  description: string | null;
  images: string[] | null;
  categoryPath: string | null;
  platformCategoryId: string | null;
  sourceKind: string;
  sourceId: string;
  sourceNote: string;
  fields: ListingPublishContentField[] | null;
}

export interface ListingPublishTargetRow {
  targetId: string;
  jobId: string;
  position: number;
  platformId: string;
  platformCode: string;
  shopId: string;
  shopCode: string;
  marketplaceId: string;
  marketplaceCode: string;
  locale: string;
  categoryPath: string;
  state: string;
  contentSource: string;
  contentSourceId: string;
  contentSnapshot: ListingPublishContentSnapshot | null;
  externalItemId: string | null;
  platformRequestId: string;
  failureReason: string;
  attempts: number;
  platformOperationId: string | null;
  dispatchedAt: string | null;
  completedAt: string | null;
}

export interface ListingPublishStepRow {
  stepId: string;
  jobId: string;
  targetId: string | null;
  stepKey: string;
  position: number;
  state: string;
  detail: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface ListingPublishDispatchEntry {
  targetId: string;
  shopCode: string;
  marketplaceCode: string;
  platformCode: string;
  result: string;
  note: string;
}

export interface ListingPublishDispatchResponse {
  jobId: string;
  jobState: string;
  entries: ListingPublishDispatchEntry[];
  progress: ListingPublishProgress;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<ListingPublishCapabilities> {
  return api<ListingPublishCapabilities>('channel/one-click-publish/capabilities');
}

export async function fetchTargetOptions(): Promise<ListingPublishTargetOption[]> {
  return api<ListingPublishTargetOption[]>('channel/one-click-publish/target-options');
}

export async function fetchContentSources(): Promise<ListingPublishContentSources> {
  return api<ListingPublishContentSources>('channel/one-click-publish/content-sources');
}

export async function fetchJobs(state?: string): Promise<ListingPublishJobRow[]> {
  const query = state ? `?state=${encodeURIComponent(state)}` : '';
  return api<ListingPublishJobRow[]>(`channel/one-click-publish/jobs${query}`);
}

export async function fetchJob(jobId: string): Promise<ListingPublishJobRow> {
  return api<ListingPublishJobRow>(`channel/one-click-publish/jobs/${jobId}`);
}

export async function fetchTargets(jobId: string): Promise<ListingPublishTargetRow[]> {
  return api<ListingPublishTargetRow[]>(`channel/one-click-publish/jobs/${jobId}/targets`);
}

export async function fetchSteps(jobId: string): Promise<ListingPublishStepRow[]> {
  return api<ListingPublishStepRow[]>(`channel/one-click-publish/jobs/${jobId}/steps`);
}

export async function createJob(
  body: CreateListingPublishJobRequest,
): Promise<ListingPublishJobRow> {
  return api<ListingPublishJobRow>('channel/one-click-publish/jobs', 'POST', body);
}

export async function dispatchJob(jobId: string): Promise<ListingPublishDispatchResponse> {
  return api<ListingPublishDispatchResponse>(
    `channel/one-click-publish/jobs/${jobId}/dispatch`,
    'POST',
  );
}

export async function retryTarget(targetId: string): Promise<ListingPublishTargetRow> {
  return api<ListingPublishTargetRow>(
    `channel/one-click-publish/targets/${targetId}/retry`,
    'POST',
  );
}

// ================================================================ 纯函数（可单测）

/**
 * 目标状态文案 —— **「审核中」与「已成功」刻意不同**。
 *
 * `SUBMITTED` 的语义是"已提交平台、等回执"，把它显示成「已发布」会让运营以为商品已经上架。
 */
export function targetStateLabel(state: string): string {
  switch (state) {
    case 'DRAFT':
      return '草稿（已冻结内容，未提交）';
    case 'SUBMITTED':
      return '审核中（等平台回执）';
    case 'SUCCEEDED':
      return '已成功';
    case 'REJECTED':
      return '平台驳回';
    case 'FAILED':
      return '失败';
    case 'CANCELLED':
      return '已撤销';
    default:
      return state;
  }
}

/** 目标状态对应的徽章色调。 */
export function targetStateTone(
  state: string,
): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (state) {
    case 'SUCCEEDED':
      return 'success';
    case 'SUBMITTED':
      return 'info';
    case 'REJECTED':
      return 'warning';
    case 'FAILED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 总体结论文案（服务端算好的结论，前端不重算）。 */
export function outcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'COMPLETED':
      return '全部成功';
    case 'PARTIAL':
      return '部分成功';
    case 'FAILED':
      return '全部失败';
    case 'NOT_DISPATCHED':
      return '还有目标没出结果';
    default:
      return outcome;
  }
}

/** 进度摘要：五个计数 + 百分比，一句话（数字全部来自服务端）。 */
export function progressSummary(progress: ListingPublishProgress): string {
  return `共 ${progress.total} 个目标 · 草稿 ${progress.draft} · 审核中 ${progress.inReview} · 成功 ${progress.succeeded} · 驳回 ${progress.rejected} · 失败 ${progress.failed}（${progress.percent}%，${outcomeLabel(progress.outcome)}）`;
}

/**
 * 🔴 价格状态说明。
 *
 * `UNAVAILABLE` 时**如实说不可用并给出来源模块**，不假装有价、也不显示成 0。
 * `SET` 时显示服务端冻结下来的金额与币种（前端不自己换算、不自己格式化金额）。
 */
export function priceStateNotice(job: ListingPublishJobRow): string {
  if (job.priceState === 'SET') {
    return `价格已冻结：${job.priceAmount} ${job.priceCurrency ?? ''}`.trim();
  }
  if (job.priceState === 'UNAVAILABLE') {
    return job.priceStateReason || '取价来源未交付，本次发布不带平台售价。';
  }
  return '本次发布未设置价格。';
}

/** 库存状态说明。 */
export function stockStateNotice(job: ListingPublishJobRow): string {
  if (job.stockState === 'SET') {
    return `可售量已冻结：${job.stockQuantity}`;
  }
  if (job.stockState === 'UNAVAILABLE') {
    return job.stockStateReason || '可售量读不到，本次发布不带库存。';
  }
  return job.stockStateReason || '本次发布未设置库存。';
}

/**
 * 目标选项的稳定键（店铺 + 站点 + 站点默认语言）。
 *
 * 🔴 键里带语言：内容按「平台 + 站点 + 语言」分别保存，同一个店铺的同一个站点
 * 在两种语言下是**两份**内容，选中态不能只按店铺+站点记。
 */
export function targetOptionKey(option: {
  shopId: string;
  marketplaceId: string;
  defaultLocale: string;
}): string {
  return `${option.shopId}|${option.marketplaceId}|${option.defaultLocale}`;
}

/** 一个目标输入的稳定键（店铺 + 站点 + 语言）。 */
export function targetInputKey(target: {
  shopId: string;
  marketplaceId: string;
  locale: string;
}): string {
  return `${target.shopId}|${target.marketplaceId}|${target.locale}`;
}

/** 目标选项的展示名。 */
export function targetOptionLabel(option: ListingPublishTargetOption): string {
  return `${option.platformCode} / ${option.shopCode} / ${option.marketplaceCode}（${option.defaultLocale}）`;
}

/**
 * 🔴 按内容来源过滤候选。
 *
 * 内容来源是"编辑器草稿"时只列编辑器入队记录，是"刊登模板"时只列模板 ——
 * 两类候选混在一起会让运营以为自己选错了。
 */
export function contentSourceCandidates(
  sources: ListingPublishContentSources,
  contentSource: string,
): {
  submissions: ListingPublishEditorSubmissionOption[];
  templates: ListingPublishTemplateOption[];
} {
  if (contentSource === 'TEMPLATE') {
    return { submissions: [], templates: sources.templates };
  }
  return { submissions: sources.editorSubmissions, templates: [] };
}

/**
 * 🔴 建作业请求体。
 *
 * 空值**不下发**（交给服务端走默认）；`priceSource=MANUAL` 时金额与币种**必须**带上，
 * 否则服务端会拒（价格不能是"手填但没填"）。
 */
export function buildCreateRequest(input: {
  sourceSkuId: string;
  targets: ListingPublishTargetInput[];
  contentSource: string;
  contentSourceIds: string[];
  priceSource: string;
  priceAmount: string;
  priceCurrency: string;
  stockSource: string;
  idempotencyKey: string;
  note: string;
}): CreateListingPublishJobRequest {
  const body: CreateListingPublishJobRequest = {
    sourceSkuId: input.sourceSkuId,
    targets: input.targets,
    contentSource: input.contentSource,
    contentSourceIds: input.contentSourceIds,
    priceSource: input.priceSource,
    stockSource: input.stockSource,
    idempotencyKey: input.idempotencyKey,
    note: input.note,
  };
  if (input.priceSource === 'MANUAL') {
    body.priceAmount = Number(input.priceAmount);
    body.priceCurrency = input.priceCurrency.trim().toUpperCase();
  }
  return body;
}

/** 提交前的本地校验（**不替代**服务端校验，只是不让 obviously 不完整的请求发出去）。 */
export function validateCreateInput(input: {
  sourceSkuId: string;
  targets: ListingPublishTargetInput[];
  contentSourceIds: string[];
  priceSource: string;
  priceAmount: string;
  priceCurrency: string;
  idempotencyKey: string;
}): string | null {
  if (!input.sourceSkuId.trim()) return '请先填源 SKU';
  if (input.targets.length === 0) return '请至少选一个目标店铺与站点';
  if (input.contentSourceIds.length === 0) return '请至少选一个内容来源';
  if (input.priceSource === 'MANUAL') {
    if (!input.priceAmount.trim()) return '价格来源是「运营手填」时必须填金额';
    if (!input.priceCurrency.trim()) return '价格来源是「运营手填」时必须填币种';
  }
  if (!input.idempotencyKey.trim()) return '请填幂等键（重复提交同一个键只会产生一条作业）';
  return null;
}

/** 🔴 只有失败的目标能重试；被驳回的目标不能（内容没改，重发只会再被驳回一次）。 */
export function canRetry(target: ListingPublishTargetRow): boolean {
  return target.state === 'FAILED';
}

/** 重试按钮的提示文案。 */
export function retryHint(target: ListingPublishTargetRow): string {
  if (target.state === 'REJECTED') {
    return '平台已驳回：内容需要修改后重新发起，重发同一份内容没有意义。';
  }
  if (target.state === 'FAILED') {
    return `重试会重发**同一份**冻结内容（当前尝试次数 ${target.attempts}）。`;
  }
  return '只有失败的目标能重试。';
}

/** 派发结果摘要：逐目标列出，**不合并成一个总数**。 */
export function dispatchSummary(dispatch: ListingPublishDispatchResponse): string[] {
  if (dispatch.entries.length === 0) {
    return ['本轮没有可派发的目标（都已经派发过了）'];
  }
  return dispatch.entries.map((entry) => {
    const scope = `${entry.platformCode} / ${entry.shopCode} / ${entry.marketplaceCode}`;
    return `${scope}：${entry.result === 'DISPATCHED' ? '已提交平台审核' : '派发失败'} —— ${entry.note}`;
  });
}

/** 未交付维度的说明（取价、合规预检、发布任务时间线页、下架联动）。 */
export function unavailableDimensions(
  caps: ListingPublishCapabilities,
): ListingPublishCapabilityDimension[] {
  return caps.dimensions.filter((dimension) => !dimension.available);
}

/** 空态文案：三种「空」必须分得开。 */
export function emptyStateMessage(kind: 'jobs' | 'targets' | 'sources'): string {
  switch (kind) {
    case 'jobs':
      return '还没有一键刊登作业。填好源 SKU 与目标店铺，点「创建并派发」开始。';
    case 'targets':
      return '还没有发布任务。先创建一键刊登作业，每个目标会在这里列出。';
    case 'sources':
      return '还没有可选的内容来源：编辑器草稿要先在刊登编辑器里提交审核，刊登模板要先在模板页启用。';
  }
}

/** 能力不可用时的空态文案（与「没有数据」不同）。 */
export function capabilityUnavailableMessage(dimension: string): string {
  if (dimension === 'price_source') {
    return '取价来源（05 定价 / 5.1.1.1）尚未交付：本次发布不带平台售价，界面不会编一个价。';
  }
  if (dimension === 'compliance_precheck') {
    return '合规预检（4.2.3.2）尚未交付：本次只做字段级校验，不做违禁词 / 侵权扫描。';
  }
  return '该能力尚未交付。';
}
