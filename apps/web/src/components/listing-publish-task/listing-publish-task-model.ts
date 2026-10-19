import { api } from '../inventory/api';

/**
 * 4.3.1.3 发布任务 前端模型。
 *
 * 蓝图：「单次发布任务的进度视图：各步骤状态（内容校验 → 平台创建 → 变体创建 → 媒体上传 → 价格库存设置 → 提交审核）、失败步骤与原因、可重试的步骤。输入：任务 ID；平台返回结果。输出：步骤时间线 + 错误明细 + 重试操作。关系：上游 = 4.3.1.1/4.3.1.2；下游 = 2.5.1.1。**平台提交审核是异步的**，任务在「审核中」结束，实际结果由 4.3.2.2 回流。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「审核中」不是「已发布」**。`stage=IN_REVIEW` 的徽章是「审核中（等平台回执）」，
 *    与「已成功」分开 —— 说成「已发布」就是撒谎。蓝图那句「平台提交审核是异步的」是
 *    这个页面最核心的一句文案。
 * 2. **重试成功 ≠ 平台审核通过**。重试返回的 `outcome=SUCCEEDED` 只表示
 *    「已重新提交平台」，界面上照原文说，**不写成「已发布」**。
 * 3. **两条上游的状态不揉**。4.3.1.1 的发布任务与 4.3.1.2 的队列任务都叫「发布任务」，
 *    但它们是两套独立的状态机 —— 列表里用来源标签分开，原始状态（`state`）原样显示，
 *    归一化阶段（`stage`）只用于筛选。
 * 4. **不可重试的步骤也给理由**。`retryable=false` 时 `retryBlockedReason` 必填，
 *    界面上原样显示「为什么这一步不能重试」，不给一个点了没反应的按钮。
 * 5. **批量发布的队列任务没有步骤行**。它显示「本任务没有步骤时间线」+ 原因，
 *    **不拿空数组冒充「已完成」**。
 *
 * 🔴 本模块**不自己算**任何进度：阶段、步骤状态、可重试与否全部来自后端
 * （后端是从 4.3.1.1 的步骤行聚合出来的，落库就会漂）。前端一个数都不自己算。
 */

// ================================================================ 类型

export interface ListingPublishTaskCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingPublishTaskPhase {
  phase: string;
  description: string;
  /** 落到哪些步骤键；空数组 = 上游没有为这一段单独记步骤行。 */
  stepKeys: string[];
  note: string;
}

export interface ListingPublishTaskCapabilities {
  dimensions: ListingPublishTaskCapabilityDimension[];
  phases: ListingPublishTaskPhase[];
  taskKinds: string[];
  stages: string[];
  stepStates: string[];
  stepKeys: string[];
  errorScopes: string[];
  maxPageSize: number;
  asyncReviewNote: string;
  nonTransactionalNote: string;
  retryNote: string;
  relationNote: string;
}

export interface ListingPublishTaskRow {
  taskId: string;
  taskKind: string;
  stage: string;
  state: string;
  jobId: string | null;
  batchId: string | null;
  platformId: string;
  platformCode: string;
  shopId: string;
  shopCode: string;
  marketplaceId: string | null;
  marketplaceCode: string | null;
  locale: string | null;
  position: number;
  attempts: number;
  failureReason: string;
  stepCount: number;
  failedStepCount: number;
  stepTimelineAvailable: boolean;
  dispatchedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ListingPublishTaskPage {
  rows: ListingPublishTaskRow[];
  page: number;
  size: number;
  total: number;
}

export interface ListingPublishTaskAttemptRow {
  attemptId: string;
  attemptNo: number;
  /** 任务级重试没有步骤键。 */
  stepKey: string | null;
  scope: string;
  outcome: string;
  reason: string;
  platformOperationId: string | null;
  requestedAt: string;
  finishedAt: string | null;
}

export interface ListingPublishTaskStepRow {
  stepId: string;
  taskId: string;
  jobId: string;
  targetId: string | null;
  stepKey: string;
  phase: string;
  scope: string;
  position: number;
  state: string;
  detail: string;
  retryable: boolean;
  retryBlockedReason: string;
  startedAt: string | null;
  finishedAt: string | null;
  durationMillis: number | null;
  attemptCount: number;
  attempts: ListingPublishTaskAttemptRow[];
}

export interface ListingPublishTaskError {
  scope: string;
  stepKey: string | null;
  phase: string | null;
  reason: string;
  occurredAt: string | null;
  retryable: boolean;
}

export interface ListingPublishTaskPublishRecord {
  recordId: string;
  action: string;
  outcome: string;
  reason: string;
  platformRequestId: string;
  occurredAt: string;
}

export interface ListingPublishTaskDetail {
  task: ListingPublishTaskRow;
  timeline: ListingPublishTaskStepRow[];
  errors: ListingPublishTaskError[];
  attempts: ListingPublishTaskAttemptRow[];
  stepTimelineAvailable: boolean;
  stepTimelineNote: string;
  publishRecords: ListingPublishTaskPublishRecord[];
  contentSnapshotTitle: string | null;
  externalItemId: string | null;
  asyncReviewNote: string;
}

export interface ListingPublishTaskRetryResult {
  taskId: string;
  stepKey: string | null;
  attemptNo: number;
  outcome: string;
  reason: string;
  platformOperationId: string | null;
  timeline: ListingPublishTaskStepRow[];
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<ListingPublishTaskCapabilities> {
  return api<ListingPublishTaskCapabilities>('channel/listing-publish-tasks/capabilities');
}

export async function fetchTasks(params: {
  kind?: string;
  stage?: string;
  platformId?: string;
  shopId?: string;
  page?: number;
  size?: number;
}): Promise<ListingPublishTaskPage> {
  const query = new URLSearchParams();
  if (params.kind) query.set('kind', params.kind);
  if (params.stage) query.set('stage', params.stage);
  if (params.platformId) query.set('platformId', params.platformId);
  if (params.shopId) query.set('shopId', params.shopId);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingPublishTaskPage>(`channel/listing-publish-tasks/tasks?${query.toString()}`);
}

export async function fetchTask(taskId: string): Promise<ListingPublishTaskDetail> {
  return api<ListingPublishTaskDetail>(`channel/listing-publish-tasks/tasks/${taskId}`);
}

export async function fetchSteps(taskId: string): Promise<ListingPublishTaskStepRow[]> {
  return api<ListingPublishTaskStepRow[]>(`channel/listing-publish-tasks/tasks/${taskId}/steps`);
}

/** 🔴 步骤级重试：重试时间线上的某一步。 */
export async function retryStep(
  taskId: string,
  stepKey: string,
): Promise<ListingPublishTaskRetryResult> {
  return api<ListingPublishTaskRetryResult>(
    `channel/listing-publish-tasks/tasks/${taskId}/steps/${stepKey}/retry`,
    'POST',
  );
}

/** 🔴 任务级重试：整条任务重来（派发就失败时的唯一出路）。 */
export async function retryTask(taskId: string): Promise<ListingPublishTaskRetryResult> {
  return api<ListingPublishTaskRetryResult>(
    `channel/listing-publish-tasks/tasks/${taskId}/retry`,
    'POST',
  );
}

// ================================================================ 纯函数（可单测）

/**
 * 🔴 阶段文案 —— **「审核中」与「已成功」刻意不同**。
 *
 * `IN_REVIEW` 的语义是"已提交平台、等回执"，把它显示成「已发布」会让运营以为商品已经上架。
 */
export function stageLabel(stage: string): string {
  switch (stage) {
    case 'PENDING':
      return '待提交';
    case 'IN_REVIEW':
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
      return stage;
  }
}

/** 阶段徽章色调。 */
export function stageTone(
  stage: string,
): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (stage) {
    case 'SUCCEEDED':
      return 'success';
    case 'IN_REVIEW':
      return 'info';
    case 'REJECTED':
      return 'warning';
    case 'FAILED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 步骤状态文案。 */
export function stepStateLabel(state: string): string {
  switch (state) {
    case 'PENDING':
      return '未开始';
    case 'RUNNING':
      return '进行中';
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'SKIPPED':
      return '已跳过';
    case 'BLOCKED':
      return '被挡住';
    default:
      return state;
  }
}

/** 步骤状态徽章色调。 */
export function stepStateTone(
  state: string,
): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (state) {
    case 'SUCCEEDED':
      return 'success';
    case 'RUNNING':
      return 'info';
    case 'FAILED':
      return 'danger';
    case 'BLOCKED':
      return 'warning';
    case 'SKIPPED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 任务来源标签：两条上游各有各的状态机，来源必须一眼分得开。 */
export function taskKindLabel(kind: string): string {
  if (kind === 'ONECLICK') return '一键刊登（4.3.1.1）';
  if (kind === 'BATCH') return '批量发布（4.3.1.2）';
  return kind;
}

/** 任务来源的短标签（徽章里用）。 */
export function taskKindBadge(kind: string): string {
  if (kind === 'ONECLICK') return '一键刊登';
  if (kind === 'BATCH') return '批量';
  return kind;
}

/**
 * 🔴 步骤归属标签。
 *
 * `JOB` = 作业级步骤（价格 / 库存：一次刊登给所有目标用同一个价与同一份可售量），
 * `TARGET` = 目标级步骤。
 */
export function stepScopeLabel(scope: string): string {
  if (scope === 'JOB') return '作业级';
  if (scope === 'TARGET') return '目标级';
  if (scope === 'TASK') return '任务级';
  return scope;
}

/** 一个任务的展示名：平台 / 店铺 / 站点（+ 语言）。 */
export function taskLabel(task: {
  platformCode: string;
  shopCode: string;
  marketplaceCode: string | null;
  locale: string | null;
}): string {
  const scope = [task.platformCode, task.shopCode, task.marketplaceCode].filter(Boolean).join(' / ');
  return task.locale ? `${scope}（${task.locale}）` : scope;
}

/** 错误明细的来源标签。 */
export function errorScopeLabel(scope: string): string {
  if (scope === 'TASK') return '任务';
  if (scope === 'STEP') return '步骤';
  if (scope === 'ATTEMPT') return '重试';
  return scope;
}

/** 错误明细的一行文案。 */
export function errorLine(error: ListingPublishTaskError): string {
  const where =
    error.scope === 'STEP' && error.stepKey
      ? `步骤 ${error.stepKey}`
      : error.scope === 'ATTEMPT' && error.stepKey
        ? `步骤 ${error.stepKey} 的重试`
        : '任务';
  return `${where}：${error.reason}`;
}

/**
 * 🔴 重试结果的一句话。
 *
 * `SUCCEEDED` = **已重新提交平台**，不是「已发布」—— 平台提交审核是异步的。
 */
export function retryOutcomeLine(result: {
  outcome: string;
  reason: string;
  attemptNo: number;
}): string {
  if (result.outcome === 'SUCCEEDED') {
    return `第 ${result.attemptNo} 次重试已提交平台（${result.reason}）`;
  }
  return `第 ${result.attemptNo} 次重试没成：${result.reason}`;
}

/** 尝试结果文案。 */
export function attemptOutcomeLabel(outcome: string): string {
  return outcome === 'SUCCEEDED' ? '已重新提交平台' : '重试没成';
}

/** 耗时文案（毫秒 → 人能读的短串）。 */
export function durationLabel(millis: number | null): string {
  if (millis === null || millis < 0) return '';
  if (millis < 1000) return `${millis} 毫秒`;
  const seconds = Math.round(millis / 1000);
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return rest === 0 ? `${minutes} 分钟` : `${minutes} 分 ${rest} 秒`;
}

/** 时间戳文案（后端给的是 ISO 串；这里只做展示格式化，不做时区换算）。 */
export function timeLabel(value: string | null): string {
  if (!value) return '';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
}

/**
 * 🔴 时间线按蓝图阶段分组。
 *
 * 阶段序来自后端的 `phases`（蓝图原文措辞）；**没有独立步骤行的阶段**（平台创建 / 变体创建）
 * 也会出现，标注「本模块没有独立步骤行」—— 这是真话，不是空步骤。
 */
export function groupTimelineByPhase(
  timeline: ListingPublishTaskStepRow[],
  phases: ListingPublishTaskPhase[],
): { phase: string; note: string; steps: ListingPublishTaskStepRow[] }[] {
  const groups: { phase: string; note: string; steps: ListingPublishTaskStepRow[] }[] = [];
  for (const phase of phases) {
    const steps = timeline.filter((step) => step.phase === phase.phase);
    // 🔴 一个阶段只有在「有步骤」或「有 note（上游没为这一段单独记步骤行）」时才显示 ——
    //    空步骤 + 无 note 的阶段是纯噪音；有 note 的那两段（平台创建 / 变体创建）
    //    恰恰是**要显示的**：它们标出蓝图与上游记录之间的真实缺口。
    if (steps.length === 0 && !phase.note) continue;
    groups.push({ phase: phase.phase, note: phase.note, steps });
  }
  // 兜底：后端出现了未登记的阶段时也显示出来，不静默丢步骤
  for (const step of timeline) {
    if (!groups.some((group) => group.phase === step.phase)) {
      groups.push({ phase: step.phase, note: '', steps: [step] });
    }
  }
  return groups;
}

/** 可重试的步骤（界面上给它们放重试按钮）。 */
export function retryableSteps(timeline: ListingPublishTaskStepRow[]): ListingPublishTaskStepRow[] {
  return timeline.filter((step) => step.retryable);
}

/** 🔴 任务级重试是否可用：只有失败的任务能重来（被平台驳回的不行）。 */
export function canRetryTask(task: { state: string }): boolean {
  return task.state === 'FAILED';
}

/** 任务级重试被拒时的原因（与后端口径一致）。 */
export function taskRetryHint(task: { state: string }): string {
  if (task.state === 'REJECTED') {
    return '平台已驳回：内容需要修改后重新发起，重发同一份内容没有意义。';
  }
  if (task.state === 'FAILED') {
    return '重试会把**同一份**冻结内容重新提交一次。';
  }
  return '只有失败的任务能重试。';
}

/** 空态文案：三种「空」必须分得开。 */
export function emptyStateMessage(kind: 'tasks' | 'timeline' | 'errors'): string {
  switch (kind) {
    case 'tasks':
      return '还没有发布任务。先在一键刊登（4.3.1.1）里建一次发布，或在批量发布（4.3.1.2）里入队。';
    case 'timeline':
      return '这个任务还没有步骤时间线。';
    case 'errors':
      return '这个任务目前没有错误。';
  }
}

/** 能力不可用时的空态文案（与「没有数据」不同）。 */
export function capabilityUnavailableMessage(dimension: string): string {
  if (dimension === 'platform_create_and_variant_phases') {
    return '「平台创建」与「变体创建」这两段在 4.3.1.1 没有单独的步骤记录，本模块不凭空造步骤。';
  }
  if (dimension === 'price_source_retry') {
    return '价格步骤重试未交付（归 05 定价 / 5.1.1.1）：价格快照已冻结，要改价请重新发起一次发布。';
  }
  if (dimension === 'batch_task_retry') {
    return '批量发布（4.3.1.2）的队列任务重试归它自己的批次重试入口。';
  }
  return '该能力尚未交付。';
}

/** 能力声明里未交付的维度。 */
export function unavailableDimensions(
  caps: ListingPublishTaskCapabilities,
): ListingPublishTaskCapabilityDimension[] {
  return caps.dimensions.filter((dimension) => !dimension.available);
}
