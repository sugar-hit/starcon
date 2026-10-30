/**
 * 13.6.2.1 导出与订阅 前端模型（卷 13「利润与经营分析」L3 13.6.2 的第二格）。
 *
 * <p>蓝图原文：「报表导出（Excel/CSV/PDF）与定时订阅推送（邮件/企业 IM），支持大文件异步导出。」
 * 输出「文件下载链接；订阅投递记录。」
 * 关系：上游 = 13.6.1.1；下游 = 1.3.2.2 推送。
 * <strong>大导出走异步任务并给出有效期</strong>，同步导出大文件会拖垮服务。
 *
 * <p><b>🔴 这个页面上最容易做丢的四件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「受理」不是「完成」</b>。受理返回的是「排队中」，文件与行数要轮询任务详情
 *       才拿得到 ⇒ 页面**绝不**在受理后显示「导出完成」。</li>
 *   <li><b>「已投递」不是「排上队了」</b>。投递记录的状态是**真的投递结果**：
 *       没配置 SMTP / Webhook 时它会是 <code>FAILED</code> 并写明原因，
 *       页面照着显示，<strong>不把 FAILED 说成「已发送」</strong>。</li>
 *   <li><b>过期就是过期</b>。下载链接过了有效期会回 410 ⇒ 页面显示明确的过期态，
 *       而不是给一个点了就报错的按钮。</li>
 *   <li><b>前端不做业务算术</b>。金额、比率、行数、字节数全部来自服务端。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  ExportSubscriptionDeliveryView,
  ExportSubscriptionDispatchResult,
  ExportSubscriptionColumnInfo,
  ExportSubscriptionFilterOperatorInfo,
  ExportSubscriptionFormatInfo,
  ExportSubscriptionJobPage,
  ExportSubscriptionJobView,
  ExportSubscriptionSortInfo,
  ExportSubscriptionSourcesResponse,
} from '../../../../../packages/api-contracts/src/index';

export type {
  ExportSubscriptionDeliveryView,
  ExportSubscriptionDispatchResult,
  ExportSubscriptionColumnInfo,
  ExportSubscriptionFilterOperatorInfo,
  ExportSubscriptionFormatInfo,
  ExportSubscriptionJobPage,
  ExportSubscriptionJobView,
  ExportSubscriptionSortInfo,
  ExportSubscriptionSourcesResponse,
};

// ================================================================ 格式

/** 格式的中文标签（认不出来就原样显示，不编一个）。 */
export function exportFormatLabel(format: string): string {
  switch (format) {
    case 'CSV':
      return 'CSV';
    case 'XLSX':
      return 'Excel（.xlsx）';
    case 'PDF':
      return 'PDF';
    default:
      return format;
  }
}

/** 格式的下载 content-type（页面提示用）。 */
export function exportFormatContentType(format: string): string {
  switch (format) {
    case 'CSV':
      return 'text/csv';
    case 'XLSX':
      return 'Excel 工作簿（.xlsx）';
    case 'PDF':
      return 'PDF 文档（.pdf）';
    default:
      return format;
  }
}

/** 格式说明（能力声明里的那句）。 */
export function exportFormatNote(format: ExportSubscriptionFormatInfo): string {
  return format.note || format.label;
}

// ================================================================ 任务状态

/** 任务状态的中文标签。 */
export function exportStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '排队中';
    case 'RUNNING':
      return '生成中';
    case 'SUCCEEDED':
      return '已完成';
    case 'FAILED':
      return '失败';
    case 'CANCELLED':
      return '已作废';
    case 'EXPIRED':
      return '已过期';
    default:
      return status;
  }
}

/** 任务是不是已经到终态了（终态不再轮询）。 */
export function isTerminalStatus(status: string): boolean {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(status);
}

/** 任务是不是还在跑（还在跑就要继续轮询）。 */
export function isRunningStatus(status: string): boolean {
  return status === 'PENDING' || status === 'RUNNING';
}

/** 下载状态的四种形态。 */
export type DownloadState = 'ready' | 'pending' | 'expired' | 'failed' | 'cancelled';

/**
 * 下载状态判定。
 *
 * <p>🔴 **「能不能下载」由服务端算**（{@code downloadable} + {@code expiresAt}），
 * 前端不自己推断 —— 自己推断就会出现「页面说能下，点下去 410」。
 */
export function downloadState(job: ExportSubscriptionJobView): DownloadState {
  if (job.status === 'SUCCEEDED') {
    if (job.downloadable) return 'ready';
    return 'expired';
  }
  if (job.status === 'EXPIRED') return 'expired';
  if (job.status === 'CANCELLED') return 'cancelled';
  if (job.status === 'FAILED') return 'failed';
  return 'pending';
}

/** 下载状态的说明文案。 */
export function downloadStateText(job: ExportSubscriptionJobView): string {
  switch (downloadState(job)) {
    case 'ready':
      return `在有效期内可下载（到期时刻 ${job.expiresAt ?? '未算出'}）`;
    case 'pending':
      return job.downloadNote || '尚未生成完成，请稍候刷新';
    case 'expired':
      return job.downloadNote || '已过下载有效期，请重新发起一次导出';
    case 'failed':
      return job.error || job.downloadNote || '导出失败';
    case 'cancelled':
      return job.downloadNote || '这份导出已被作废';
    default:
      return job.downloadNote;
  }
}

/** 任务一行摘要。 */
export function exportJobSummary(job: ExportSubscriptionJobView): string {
  const parts = [exportFormatLabel(job.format), exportStatusLabel(job.status)];
  if (job.rowCount > 0) parts.push(`${job.rowCount} 行`);
  if (job.fileName) parts.push(job.fileName);
  return parts.join(' · ');
}

/** 字节数的展示（服务端给的是精确十进制，这里只做单位换算）。 */
export function formatByteSize(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—';
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

/** 文件大小文案（DB 里的 byte_size 是 NUMERIC(19,6)，契约里是 number）。 */
export function exportByteSizeText(job: ExportSubscriptionJobView): string {
  return formatByteSize(job.byteSize === null ? null : Number(job.byteSize));
}

// ================================================================ 可导出列

/** 一列的说明（页面上照着显示，别自己编一句）。 */
export function exportColumnNote(column: ExportSubscriptionColumnInfo): string {
  return column.note;
}

/** 维度列 / 度量列的中文分类。 */
export function exportColumnKindLabel(kind: string): string {
  return kind === 'DIMENSION' ? '维度' : '度量';
}

/** 选中的列的摘要。 */
export function exportSelectionText(columns: ExportSubscriptionColumnInfo[]): string {
  if (columns.length === 0) return '未选列';
  return columns.map((column) => column.label).join('、');
}

// ================================================================ 投递

/**
 * 投递状态的中文标签。
 *
 * <p>🔴 **「待投递」不是「已发送」**。
 */
export function deliveryStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待投递';
    case 'DELIVERED':
      return '已投递';
    case 'FAILED':
      return '投递失败';
    case 'CANCELLED':
      return '已取消';
    default:
      return status;
  }
}

/** 投递状态是不是「真的送到了」。 */
export function deliverySucceeded(delivery: ExportSubscriptionDeliveryView): boolean {
  return delivery.status === 'DELIVERED';
}

/** 投递记录的一行摘要。 */
export function deliverySummary(delivery: ExportSubscriptionDeliveryView): string {
  const target = `${delivery.reportCode} · ${delivery.channel} → ${delivery.target}`;
  return `${target} · ${deliveryStatusLabel(delivery.status)}`;
}

/** 投递的说明（**失败必须把原因摆出来**）。 */
export function deliveryNote(delivery: ExportSubscriptionDeliveryView): string {
  if (delivery.status === 'FAILED') {
    return delivery.lastError || '投递失败（原因未记录）';
  }
  return delivery.note;
}

/** 一轮投递的结果文案。 */
export function dispatchResultText(result: ExportSubscriptionDispatchResult | null): string {
  if (!result) return '';
  return `尝试 ${result.attempted} 条 · 成功 ${result.delivered} 条 · 失败 ${result.failed} 条`;
}

// ================================================================ 下载链接

/**
 * 导出文件的下载地址（**浏览器直接下载**，不是 JSON 包装）。
 *
 * <p>⚠️ 路径前缀与 {@code lib/api.ts} 的后端路由表一致：
 * {@code /api/<route>/<path>} ⇒ reporting 服务是 {@code /api/reporting/reporting/...}。
 */
export function exportDownloadUrl(jobId: string): string {
  return `/api/reporting/reporting/custom-report/export/jobs/${jobId}/file`;
}

/** 同步导出的下载地址（**小结果集**才走这条路）。 */
export function exportSyncUrl(): string {
  return '/api/reporting/reporting/custom-report/export/sync';
}

// ================================================================ 页面状态

/** 页面的三种状态分开说。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 任务清单的状态判定。
 *
 * <p>🔴 **「读不到」与「没有数据」是两件事**：读不到（服务不可达 / 无权限）⇒ 错误态；
 * 读到了但一条任务都没有 ⇒ 空态。
 */
export function jobListState(page: ExportSubscriptionJobPage | null, error: string): PageState {
  if (error) return 'error';
  if (!page) return 'loading';
  if (page.rows.length === 0) return 'empty';
  return 'ready';
}

/** 任务清单的空态文案（区分「筛选无结果」与「还没导出过」）。 */
export function jobListEmptyText(page: ExportSubscriptionJobPage | null): string {
  if (!page) return '';
  if (page.keyword || page.statusFilter || page.formatFilter) {
    return '没有导出任务匹配当前筛选条件。';
  }
  return '还没有发起过导出。先选一张报表 → 选格式 → 发起导出。';
}

/** 投递台账的空态文案。 */
export function deliveryEmptyText(): string {
  return '还没有投递记录。订阅计划到点后会由外部调度落成待投递记录。';
}
