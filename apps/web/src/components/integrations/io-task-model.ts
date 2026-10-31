import type {
  ImportExportCapabilities,
  ImportExportCapability,
  ImportExportDomainRow,
  ImportExportOverview,
  ImportExportRowErrorPage,
  ImportExportTaskPage,
  ImportExportTaskRow,
  ImportExportTemplateDetail,
  ImportExportTemplatePage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.1.3.1「导入导出任务」前端模型。
 *
 * 蓝图：「<strong>职责</strong>：统一的数据导入导出通道：任务创建（选择数据域与范围）、异步执行、
 * 进度跟踪、结果下载、失败行报告、模板下载。<strong>输入</strong>：导出/导入请求；文件上传。
 * <strong>输出</strong>：任务记录 + 文件（带有效期）+ 错误报告。<strong>关系</strong>：上游 = 全部业务域；
 * 下游 = 用户本地文件。<strong>大文件一律异步</strong>，并在下载链接上设置有效期，避免存储无限增长。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「已受理」不是「已完成」**。发起导出后服务端返回的是「任务已创建」，
 *    文件还在后台生成 —— 页面**绝不**在提交后显示「导出完成」，
 *    必须轮询任务详情拿真实的行数与状态。
 * 2. **没接通的数据域要显式摆出来**。客户域没有面向平台的授权端点，
 *    订单 / 库存 / 凭证只能导出不能导入 —— 页面要写明「缺什么、归谁」，
 *    把它混进空态等于告诉运营「这些域已经能导了」。
 * 3. **读不到 ≠ 没有**。端点读不到时显示「读不到」并写明原因，不渲染绿色空态。
 * 4. **失败行要看到行号与原因**，不是一句「导入失败」。
 * 5. **下载有有效期**。过期后按钮要消失并说明「重新发起一次」，
 *    而不是让用户点一下拿到一个 410。
 *
 * <p><b>类型全部从契约派生</b>（{@code packages/api-contracts} 的 codegen 产物），前端不手抄字符串联合。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/io';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<ImportExportCapabilities> {
  return api<ImportExportCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<ImportExportOverview> {
  return api<ImportExportOverview>(`${base}/overview`);
}

/** 数据域目录（**两个方向各一行**）。 */
export function fetchDomains(): Promise<ImportExportDomainRow[]> {
  return api<ImportExportDomainRow[]>(`${base}/domains`);
}

/** 任务列表（真分页 + 真筛选）。 */
export function fetchTasks(params: {
  direction?: string | undefined;
  domainCode?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<ImportExportTaskPage> {
  return api<ImportExportTaskPage>(`${base}/tasks?${query(params)}`);
}

/** 任务详情（**进度跟踪**：行数、状态、失败行数都在这里）。 */
export function fetchTask(id: string): Promise<ImportExportTaskRow> {
  return api<ImportExportTaskRow>(`${base}/tasks/${id}`);
}

/** 失败行明细（逐行逐列）。 */
export function fetchRowErrors(
  id: string,
  params: { severity?: string | undefined; page?: number | undefined; size?: number | undefined },
): Promise<ImportExportRowErrorPage> {
  return api<ImportExportRowErrorPage>(`${base}/tasks/${id}/errors?${query(params)}`);
}

/** 模板列表。 */
export function fetchTemplates(params: {
  domainCode?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<ImportExportTemplatePage> {
  return api<ImportExportTemplatePage>(`${base}/templates?${query(params)}`);
}

/** 模板详情（列定义 + 示例行）。 */
export function fetchTemplate(id: string): Promise<ImportExportTemplateDetail> {
  return api<ImportExportTemplateDetail>(`${base}/templates/${id}`);
}

// ================================================================ 动作

/** 发起导出任务（**已受理**：文件在后台生成，要轮询任务详情）。 */
export function createExport(
  domainCode: string,
  scope: Record<string, string>,
): Promise<ImportExportTaskRow> {
  return api<ImportExportTaskRow>(`${base}/tasks`, 'POST', { domainCode, scope });
}

/**
 * 发起导入任务（**base64 JSON**：浏览器的同源代理只转发 JSON，multipart 过不去）。
 */
export function createImport(
  domainCode: string,
  filename: string,
  content: ArrayBuffer,
  scope: Record<string, string>,
): Promise<ImportExportTaskRow> {
  const bytes = new Uint8Array(content);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return api<ImportExportTaskRow>(`${base}/tasks/import`, 'POST', {
    domainCode,
    filename,
    contentBase64: btoa(binary),
    scope,
  });
}

/** 作废一个任务。 */
export function cancelTask(id: string): Promise<ImportExportTaskRow> {
  return api<ImportExportTaskRow>(`${base}/tasks/${id}/cancel`, 'POST');
}

/** 清理过期任务与文件。 */
export function expireTasks(): Promise<{ expiredTasks: number }> {
  return api<{ expiredTasks: number }>(`${base}/expire`, 'POST');
}

// ================================================================ 下载

/**
 * 下载链接（**普通链接**，不是 `api()`）。
 *
 * 🔴 同源代理对 `text/csv` 是**原样透传**的（走 JSON.parse 会把 CSV 解析失败变成 503），
 * 且代理会用会话 cookie 补上 `Authorization` 头 —— 所以浏览器直接开这个链接就能拿到文件。
 */
export function resultFileUrl(id: string): string {
  return `/api/${base}/tasks/${id}/file`;
}

/** 失败行报告下载链接。 */
export function errorReportUrl(id: string): string {
  return `/api/${base}/tasks/${id}/error-report`;
}

/** 模板下载链接。 */
export function templateFileUrl(id: string): string {
  return `/api/${base}/templates/${id}/file`;
}

// ================================================================ 词表

/** 语气 → Badge 的 tone。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 任务状态的显示口径。 */
export function taskStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待执行';
    case 'RUNNING':
      return '执行中';
    case 'SUCCEEDED':
      return '已完成';
    case 'FAILED':
      return '已失败';
    case 'CANCELLED':
      return '已作废';
    case 'EXPIRED':
      return '已过期';
    default:
      return status || '—';
  }
}

/**
 * 任务状态的语气。
 *
 * 🔴 「已过期」是**中性**而不是错误：文件到期的任务本身是正常完成的，
 * 标成红色会让人以为出了故障。
 */
export function taskStatusTone(status: string): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'ok';
    case 'RUNNING':
      return 'warn';
    case 'FAILED':
      return 'danger';
    case 'CANCELLED':
    case 'EXPIRED':
      return 'muted';
    default:
      return 'muted';
  }
}

/** 方向的显示口径。 */
export function directionLabel(direction: string): string {
  return direction === 'EXPORT' ? '导出' : direction === 'IMPORT' ? '导入' : direction || '—';
}

/** 失败级别的语气。 */
export function severityTone(severity: string): Tone {
  return severity === 'ERROR' ? 'danger' : 'warn';
}

/**
 * 能力声明里**不可用**的那几格。
 *
 * 🔴 这些必须显式摆出来 —— 混进空态等于告诉运营「订单也已经能导了」。
 */
export function unavailableCapabilities(
  capabilities: ImportExportCapabilities | null | undefined,
): ImportExportCapability[] {
  return (capabilities?.capabilities ?? []).filter((c) => !c.available);
}

/**
 * 某个方向上**可用**的数据域。
 *
 * 🔴 只列可用的：发起任务的下拉里不该出现一个点下去必然 409 的选项。
 * 不可用的那些在「数据域目录」与「能力声明」里如实摆着。
 */
export function availableDomains(
  domains: ImportExportDomainRow[] | null | undefined,
  direction: 'EXPORT' | 'IMPORT',
): ImportExportDomainRow[] {
  return (domains ?? []).filter((d) => d.direction === direction && d.available);
}

/** 某个方向上**不可用**的数据域（页面要写明缺什么、归谁）。 */
export function unavailableDomains(
  domains: ImportExportDomainRow[] | null | undefined,
  direction: 'EXPORT' | 'IMPORT',
): ImportExportDomainRow[] {
  return (domains ?? []).filter((d) => d.direction === direction && !d.available);
}

/**
 * 任务的进度百分比。
 *
 * 🔴 没有总行数时**不编一个百分比出来** —— 返回 null 让页面显示「进行中」而不是「0%」。
 */
export function progressPercent(task: ImportExportTaskRow | null | undefined): number | null {
  if (!task) return null;
  if (task.totalRows <= 0) return null;
  if (task.status === 'SUCCEEDED') return 100;
  return Math.min(100, Math.round((task.processedRows / task.totalRows) * 100));
}

/**
 * 文件还能不能下载。
 *
 * 🔴 过期后按钮要**消失**并说明「重新发起一次」——
 * 让用户点一下拿到一个 410 是把后端的判断甩给用户。
 */
export function downloadable(
  task: ImportExportTaskRow | null | undefined,
  now: Date = new Date(),
): boolean {
  if (!task) return false;
  if (task.status !== 'SUCCEEDED') return false;
  if (!task.resultFileId) return false;
  if (!task.expiresAt) return true;
  return new Date(task.expiresAt).getTime() > now.getTime();
}

/** 剩余有效期的文案（已过期 / 剩余不到一小时 / 剩余 N 小时）。 */
export function expiryText(task: ImportExportTaskRow | null | undefined): string {
  if (!task || !task.expiresAt) return '';
  const remaining = new Date(task.expiresAt).getTime() - Date.now();
  if (remaining <= 0) return '已过下载有效期';
  const hours = Math.floor(remaining / 3_600_000);
  if (hours < 1) return '不到 1 小时后过期';
  if (hours < 48) return `还剩 ${hours} 小时过期`;
  return `还剩 ${Math.floor(hours / 24)} 天过期`;
}

/** 失败原因码的显示口径（后端给的是稳定码，页面据此显示人话）。 */
export function errorCodeLabel(code: string): string {
  switch (code) {
    case '':
      return '—';
    case 'MISSING_NAME':
      return '缺少订阅名称';
    case 'MISSING_TARGET_URL':
      return '缺少订阅端点地址';
    case 'INVALID_TARGET_URL':
      return '端点地址格式不对';
    case 'MISSING_APP_CODE':
      return '缺少所属应用';
    case 'APP_NOT_FOUND':
      return '应用不存在';
    case 'MISSING_EVENT_TYPES':
      return '缺少订阅事件类型';
    case 'EVENT_TYPE_UNKNOWN':
      return '事件类型未登记';
    case 'DUPLICATE_NAME':
      return '同名订阅重复';
    case 'SUBSCRIPTION_EXISTS':
      return '订阅已存在';
    case 'UPSTREAM_ERROR':
      return '上游导入作业报告了失败';
    default:
      return code;
  }
}

/** 失败原因码 → 页面口径（把后端的码换成中文短语）。 */
export function errorCodeText(code: string): string {
  const label = errorCodeLabel(code);
  return label === code ? code : label;
}
