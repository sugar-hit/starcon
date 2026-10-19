import { api } from '../inventory/api';
import type {
  BulkCollectCapabilityDimension,
  BulkCollectPage,
  BulkCollectPolicyRow,
  BulkCollectResultPage,
  BulkCollectResultRow,
  BulkCollectSourceRow,
  BulkCollectTaskRow,
  ListingBulkCollectCapabilities,
  PluginReportResult,
  StartBulkCollectRequest,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 4.4.1.2 批量/插件采集 前端模型。
 *
 * 蓝图：「支持按分类页、关键词搜索结果批量采集，以及浏览器插件在用户浏览时抓取当前页商品，解决大批量找货场景。输入：分类页 URL /
 * 关键词 + 页数范围；插件上报的页面数据。输出：批量采集任务 + 结果清单。关系：上游 = 4.4.1.1；下游 = 4.4.1.3。**抓取必须有频率限制与去重**
 * （同 URL 不重复抓、已存在的商品跳过），避免重复劳动与外部站点压力。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **限频要看得见**。服务端超限时返回 429 + **可读原因**（还要等多少秒、为什么被拒），
 *    页面必须把**原文**显示出来 —— 笼统的「操作失败」会让运营以为是系统坏了。
 * 2. **去重要看得见**。命中「已存在」时清单里那条要明说「已经采过了，没有再次请求来源站点」。
 * 3. **只产生草稿**。页面不出现「发布」「上架」「建档」按钮，也没有价格 / 库存输入框。
 * 4. **三态俱全**。加载态 / 空态 / 错误态都有，只写 happy path 的页面不算交付。
 */

/** 批量采集的三种入口模式（与后端 `ListingBulkCollectModels.MODES` 一致）。 */
export const BULK_MODES = ['CATEGORY_PAGE', 'KEYWORD', 'PLUGIN'] as const;

export type BulkMode = (typeof BULK_MODES)[number];

/** 任务状态（与后端 `ListingBulkCollectModels.STATES` 一致）。 */
export const BULK_STATES = [
  'PENDING',
  'RUNNING',
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'RATE_LIMITED',
] as const;

/** 结果状态（与后端 `ListingBulkCollectModels.RESULT_STATES` 一致）。 */
export const BULK_RESULT_STATES = [
  'FETCHED',
  'SKIPPED_DUPLICATE',
  'FAILED',
  'SKIPPED_RATE_LIMITED',
] as const;

export type {
  BulkCollectCapabilityDimension,
  BulkCollectPage,
  BulkCollectPolicyRow,
  BulkCollectResultPage,
  BulkCollectResultRow,
  BulkCollectSourceRow,
  BulkCollectTaskRow,
  ListingBulkCollectCapabilities,
  PluginReportResult,
  StartBulkCollectRequest,
};

// ================================================================ 取数

export async function fetchBulkCapabilities(): Promise<ListingBulkCollectCapabilities> {
  return api<ListingBulkCollectCapabilities>('channel/listing-bulk-collects/capabilities');
}

export async function fetchBulkTasks(
  mode?: string,
  state?: string,
  sourceCode?: string,
  page = 0,
  size = 50,
): Promise<BulkCollectPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (mode) query.set('mode', mode);
  if (state) query.set('state', state);
  if (sourceCode) query.set('sourceCode', sourceCode);
  return api<BulkCollectPage>(`channel/listing-bulk-collects?${query.toString()}`);
}

export async function fetchBulkTask(taskId: string): Promise<BulkCollectTaskRow> {
  return api<BulkCollectTaskRow>(`channel/listing-bulk-collects/${taskId}`);
}

export async function fetchBulkResults(
  taskId: string,
  state?: string,
  page = 0,
  size = 50,
): Promise<BulkCollectResultPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (state) query.set('state', state);
  return api<BulkCollectResultPage>(`channel/listing-bulk-collects/${taskId}/results?${query.toString()}`);
}

/** 发起一次批量采集（只建任务；执行是另一个动作）。 */
export async function startBulkCollect(payload: StartBulkCollectRequest): Promise<BulkCollectTaskRow> {
  return api<BulkCollectTaskRow>('channel/listing-bulk-collects', 'POST', payload);
}

/** 执行一次批量采集（真发 HTTP 请求）。 */
export async function runBulkCollect(taskId: string): Promise<BulkCollectTaskRow> {
  return api<BulkCollectTaskRow>(`channel/listing-bulk-collects/${taskId}/run`, 'POST');
}

// ================================================================ 纯函数（可单测）

/**
 * 🔴 客户端侧的输入预检：只挡**明显不合法**的输入，不代替服务端的判定。
 *
 * <p>白名单与限频是**服务端的权威**（租户与运维都可能改），前端预检只是为了少发一次无谓请求。
 */
export function precheckCategoryUrl(raw: string): string | null {
  const value = raw.trim();
  if (!value) return '请填写分类页 URL';
  if (!/^https?:\/\//i.test(value)) return '分类页 URL 必须以 http:// 或 https:// 开头';
  try {
    const url = new URL(value);
    if (!url.hostname) return '分类页 URL 缺少主机名';
  } catch {
    return '分类页 URL 格式不正确';
  }
  return null;
}

/** 关键词预检。 */
export function precheckKeyword(raw: string): string | null {
  const value = raw.trim();
  if (!value) return '请填写关键词';
  if (value.length > 200) return '关键词太长了（最多 200 个字符）';
  return null;
}

/** 页码范围预检。 */
export function precheckPageRange(from: number, to: number): string | null {
  if (!Number.isInteger(from) || !Number.isInteger(to)) return '页码必须是整数';
  if (from < 1) return '起始页从 1 起';
  if (to < from) return '结束页不能小于起始页';
  return null;
}

/** 入口模式文案。 */
export function bulkModeLabel(mode: string): string {
  switch (mode) {
    case 'CATEGORY_PAGE':
      return '分类页批量';
    case 'KEYWORD':
      return '关键词批量';
    case 'PLUGIN':
      return '浏览器插件上报';
    default:
      return mode;
  }
}

/** 任务状态文案。 */
export function bulkStateLabel(state: string): string {
  switch (state) {
    case 'PENDING':
      return '待运行';
    case 'RUNNING':
      return '采集中';
    case 'SUCCEEDED':
      return '已采完';
    case 'PARTIAL':
      return '部分采完';
    case 'FAILED':
      return '失败';
    case 'RATE_LIMITED':
      return '被限频打断';
    default:
      return state;
  }
}

export function bulkStateTone(state: string): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (state) {
    case 'SUCCEEDED':
      return 'success';
    case 'PARTIAL':
      return 'warning';
    case 'FAILED':
      return 'danger';
    case 'PENDING':
    case 'RUNNING':
      return 'info';
    case 'RATE_LIMITED':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 结果状态文案。 */
export function resultStateLabel(state: string): string {
  switch (state) {
    case 'FETCHED':
      return '已落成草稿';
    case 'SKIPPED_DUPLICATE':
      return '已存在，跳过';
    case 'FAILED':
      return '抓取失败';
    case 'SKIPPED_RATE_LIMITED':
      return '被限频跳过';
    default:
      return state;
  }
}

export function resultStateTone(
  state: string,
): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (state) {
    case 'FETCHED':
      return 'success';
    case 'SKIPPED_DUPLICATE':
    case 'SKIPPED_RATE_LIMITED':
      return 'warning';
    case 'FAILED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 结果来源文案。 */
export function originLabel(origin: string): string {
  switch (origin) {
    case 'BULK':
      return '批量链路';
    case 'PLUGIN':
      return '插件上报';
    default:
      return origin;
  }
}

/**
 * 🔴 限频提示：把服务端给的**原文**显示出来。
 *
 * <p>服务端已经写清了是并发超限、发起太密、还是同 host 抓取太快，以及**还要等多少秒**。
 * 前端不加工、不吞掉 —— 运营要按这个去安排下一次采集的时间。
 */
export function rateLimitNote(task: BulkCollectTaskRow | null): string {
  if (!task) return '';
  if (task.state === 'RATE_LIMITED') {
    return task.errorMessage || '🔴 被限频打断了，已采到的结果全部保留，可以点「继续采集」接着采。';
  }
  return '';
}

/**
 * 🔴 这个任务能不能「继续采集」。
 *
 * <p>被限频打断（{@code RATE_LIMITED}）或只采了一部分（{@code PARTIAL}）都能接着采 ——
 * 服务端保留了断点。已采完（{@code SUCCEEDED}）的不行。
 */
export function canResume(task: BulkCollectTaskRow | null): boolean {
  if (!task) return false;
  return task.state === 'RATE_LIMITED' || task.state === 'PARTIAL' || task.state === 'FAILED';
}

/** 采集统计的界面文案。 */
export function taskSummaryLabel(task: BulkCollectTaskRow | null): string {
  if (!task) return '';
  return [
    `发现 ${task.discoveredCount} 条`,
    `落成草稿 ${task.fetchedCount} 条`,
    `跳过 ${task.skippedDuplicateCount} 条`,
    `失败 ${task.failedCount} 条`,
  ].join(' · ');
}

/** 任务失败 / 中断的原因（服务端原文）。 */
export function taskReason(task: BulkCollectTaskRow | null): string {
  if (!task) return '';
  if (task.state === 'SUCCEEDED') return '';
  return task.errorMessage || '';
}

/**
 * 🔴 外部页面原文标价的界面措辞。
 *
 * <p>**不是我们的售价** —— 售价归 05 定价（整卷未交付）。页面必须写清这一点，
 * 否则运营会把来源站的报价当成自己的定价直接用。
 */
export function priceLabel(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未抓到';
  const value = String(amount);
  return currency ? `${value} ${currency}` : value;
}

/** 抓取时刻的界面文案。 */
export function capturedAtLabel(capturedAt: string | null | undefined): string {
  if (!capturedAt) return '—';
  const date = new Date(capturedAt);
  if (Number.isNaN(date.getTime())) return capturedAt;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 平台白名单行的展示文案。 */
export function sourceLabel(source: BulkCollectSourceRow | null | undefined): string {
  if (!source) return '';
  return `${source.name}（${source.host}）`;
}

/** 🔴 限频策略的界面文案（运营据此知道为什么被拒）。 */
export function policySummary(policy: BulkCollectPolicyRow | null): string {
  if (!policy) return '';
  return [
    `同时最多 ${policy.maxConcurrentTasks} 个批量任务`,
    `两次发起至少间隔 ${policy.minTaskStartIntervalSeconds} 秒`,
    `单任务最多 ${policy.maxPagesPerTask} 页 / ${policy.maxItemsPerTask} 条`,
    `单次运行预算 ${policy.maxTaskSeconds} 秒`,
  ].join(' · ');
}

/** 未交付的维度（采集箱 / 浏览器扩展本体 / 认领 / 媒体库 / 售价 / 库存）。 */
export function unavailableDimensions(
  caps: ListingBulkCollectCapabilities | null,
): BulkCollectCapabilityDimension[] {
  if (!caps) return [];
  return caps.dimensions.filter((d) => !d.available);
}

/** 空态文案：还没有批量采集任务时显示什么。 */
export function emptyTasksMessage(): string {
  return '还没有批量采集任务。选一个平台，填分类页 URL 或关键词 + 页数范围，'
    + '点「开始采集」就会真的去翻那些页面、把商品抓成采集草稿。';
}

/** 空态文案：白名单为空时显示什么（运维没配白名单也是一种状态）。 */
export function emptyAllowlistMessage(caps: ListingBulkCollectCapabilities | null): string {
  if (caps && caps.sources.length === 0) {
    return '🔴 当前平台白名单是空的，一个来源都不可采。请运维在平台白名单（listing_collect_source）中登记来源域名。';
  }
  return '';
}

/** 空态文案：选中的任务还没有结果时显示什么。 */
export function emptyResultsMessage(task: BulkCollectTaskRow | null): string {
  if (!task) return '';
  if (task.state === 'PENDING') return '这个任务还没跑 —— 点「运行」开始采集。';
  if (task.discoveredCount === 0) return '这个任务没有发现任何商品链接。';
  return '这个任务还没有结果。';
}
