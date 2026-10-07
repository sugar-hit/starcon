import type {
  LabelPrintCapabilities,
  LabelPrintDevice,
  LabelPrintExecution,
  LabelPrintJob,
  LabelPrintJobDetail,
  LabelPrintJobPage,
  LabelPrintLog,
  LabelPrintLogPage,
  LabelPrintTarget,
  LabelPrintTargetPage,
  LabelPrintTemplate,
  LabelPrintTemplatePage,
  LabelReprintRule,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 8.3.4.2 面单与标签打印前端模型。
 *
 * <p>界面上最容易出错、本模块显式处理了几处：
 *
 * <ul>
 *   <li>**「打印」不是一个无副作用的动作**。点一次就是这张面单的第 N 次打印，N 会进库、
 *       会被平台判违规。所以界面必须把「第几次 / 有没有告警 / 要不要强制重打」说出来，
 *       而不是点完弹一句「成功」。
 *   <li>**超限默认是拦下来的**，不是打个警告让人点「忽略」绕过去。真要打，
 *       必须勾强制重打并写理由，理由会跟着任务留痕。
 *   <li>**次数为 0 与不限次是两件事**：`maxCount` 为 null 是「这个平台不限次」，
 *       画成「上限 0 次」会让操作人以为一张都打不了。
 *   <li>**内容是真渲染的预览**，不是图片占位：ZPL 与 HTML 都由服务端按模板与快照现算。
 * </ul>
 */

export interface TargetParams {
  objectType?: string;
  externalRef?: string;
  keyword?: string;
  minPrintCount?: number;
  page?: number;
  size?: number;
}

export interface JobParams {
  status?: string;
  objectType?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface LogParams {
  jobId?: string;
  targetId?: string;
  warningOnly?: boolean;
  page?: number;
  size?: number;
}

export interface TemplateParams {
  objectType?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'warehouse/inventory/printing';

export function fetchPrintCapabilities(): Promise<LabelPrintCapabilities> {
  return api<LabelPrintCapabilities>(`${base}/capabilities`);
}

export function fetchTargets(params: TargetParams): Promise<LabelPrintTargetPage> {
  return api<LabelPrintTargetPage>(`${base}/targets${query(params)}`);
}

export function fetchTarget(id: string): Promise<LabelPrintTarget> {
  return api<LabelPrintTarget>(`${base}/targets/${id}`);
}

export function fetchTargetHistory(id: string): Promise<LabelPrintLog[]> {
  return api<LabelPrintLog[]>(`${base}/targets/${id}/history`);
}

export function registerTarget(body: Record<string, unknown>): Promise<LabelPrintTarget> {
  return api<LabelPrintTarget>(`${base}/targets`, 'POST', body);
}

export function fetchTemplates(params: TemplateParams): Promise<LabelPrintTemplatePage> {
  return api<LabelPrintTemplatePage>(`${base}/templates${query(params)}`);
}

export function createTemplate(body: Record<string, unknown>): Promise<LabelPrintTemplate> {
  return api<LabelPrintTemplate>(`${base}/templates`, 'POST', body);
}

export function fetchDevices(activeOnly = true): Promise<LabelPrintDevice[]> {
  return api<LabelPrintDevice[]>(`${base}/devices${query({ activeOnly })}`);
}

export function createDevice(body: Record<string, unknown>): Promise<LabelPrintDevice> {
  return api<LabelPrintDevice>(`${base}/devices`, 'POST', body);
}

export function fetchReprintRules(): Promise<LabelReprintRule[]> {
  return api<LabelReprintRule[]>(`${base}/reprint-rules`);
}

export function upsertReprintRule(body: Record<string, unknown>): Promise<LabelReprintRule> {
  return api<LabelReprintRule>(`${base}/reprint-rules`, 'POST', body);
}

export function fetchJobs(params: JobParams): Promise<LabelPrintJobPage> {
  return api<LabelPrintJobPage>(`${base}/jobs${query(params)}`);
}

export function fetchJob(id: string): Promise<LabelPrintJobDetail> {
  return api<LabelPrintJobDetail>(`${base}/jobs/${id}`);
}

export function createJob(body: Record<string, unknown>): Promise<LabelPrintJob> {
  return api<LabelPrintJob>(`${base}/jobs`, 'POST', body);
}

export function executeJob(id: string): Promise<LabelPrintExecution> {
  return api<LabelPrintExecution>(`${base}/jobs/${id}/execute`, 'POST');
}

export function cancelJob(id: string): Promise<LabelPrintJob> {
  return api<LabelPrintJob>(`${base}/jobs/${id}/cancel`, 'POST');
}

export function fetchLogs(params: LogParams): Promise<LabelPrintLogPage> {
  return api<LabelPrintLogPage>(`${base}/logs${query(params)}`);
}

/** 打印内容下载端点：返回的是打印那一刻落库并校验过的那一份，不是现算的。 */
export function contentUrl(jobId: string, itemId: string): string {
  return `/api/warehouse/${base}/jobs/${jobId}/items/${itemId}/content`;
}

// ------------------------------------------------------------ 展示

export const objectTypeLabels: Record<string, string> = {
  SHIPPING_LABEL: '面单',
  PACKING_SLIP: '装箱单',
  SKU_BARCODE: 'SKU 条码',
  LOCATION_LABEL: '库位标签',
  CARTON_MARK: '箱唛',
};

export const objectTypeOptions = Object.entries(objectTypeLabels).map(([value, label]) => ({
  value,
  label,
}));

export const jobStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  QUEUED: '已排队',
  PRINTING: '打印中',
  COMPLETED: '已完成',
  FAILED: '已失败',
  CANCELLED: '已取消',
};

export const jobStatusOptions = Object.entries(jobStatusLabels).map(([value, label]) => ({
  value,
  label,
}));

export const verdictLabels: Record<string, string> = {
  NORMAL: '正常',
  WARNING: '重复打印告警',
  BLOCKED: '超出平台上限',
};

export const formatLabels: Record<string, string> = {
  ZPL: 'ZPL 指令流',
  HTML: 'HTML 打印视图',
};

export const deviceKindLabels: Record<string, string> = {
  THERMAL_LABEL: '热敏标签机',
  LASER_DOCUMENT: '激光打印机',
};

export function objectTypeLabel(code: string | undefined): string {
  return code ? (objectTypeLabels[code] ?? code) : '—';
}

export function jobStatusLabel(status: string | undefined): string {
  return status ? (jobStatusLabels[status] ?? status) : '—';
}

export function jobStatusTone(status: string | undefined): 'ok' | 'warn' | 'bad' | 'neutral' {
  switch (status) {
    case 'COMPLETED':
      return 'ok';
    case 'PRINTING':
    case 'QUEUED':
      return 'warn';
    case 'FAILED':
      return 'bad';
    default:
      return 'neutral';
  }
}

export function verdictLabel(verdict: string | undefined): string {
  return verdict ? (verdictLabels[verdict] ?? verdict) : '—';
}

export function verdictTone(verdict: string | undefined): 'ok' | 'warn' | 'bad' | 'neutral' {
  switch (verdict) {
    case 'NORMAL':
      return 'ok';
    case 'WARNING':
      return 'warn';
    case 'BLOCKED':
      return 'bad';
    default:
      return 'neutral';
  }
}

/**
 * 上限文案。
 *
 * <p>`maxCount` 为 null 是**不限次**，不是上限 0 —— 画成「上限 0 次」会让操作人以为打不了。
 */
export function limitText(maxCount: number | null | undefined): string {
  if (maxCount === null || maxCount === undefined) return '不限次';
  return `上限 ${maxCount} 次`;
}

export function warningText(warningCount: number | null | undefined): string {
  if (warningCount === null || warningCount === undefined) return '无告警线';
  return `第 ${warningCount} 次起告警`;
}

/** 「还能印几次」：超限显示「已达上限」，不限次显示「不限」。 */
export function remainingText(target: {
  printCount: number;
  nextVerdict?: string | null;
  maxCount?: number | null;
}): string {
  if (target.maxCount === null || target.maxCount === undefined) return '不限';
  const left = target.maxCount - target.printCount;
  if (left <= 0) return '已达上限';
  return `还剩 ${left} 次`;
}

/**
 * 重量文案。
 *
 * <p>后端按 NUMERIC(19,6) 存重量，读回来常见是 `1.250000`。直接拼接会显示成「1.250000 kg」，
 * 界面上三个一样的零纯属噪声；这里去掉多余的尾零，但 **0 公斤是有效重量**，不能当空值。
 */
export function weightText(weight: number | string | null | undefined): string {
  if (weight === null || weight === undefined) return '—';
  const value = typeof weight === 'number' ? String(weight) : weight.trim();
  if (value === '') return '—';
  const numeric = Number(value);
  const normalized = Number.isFinite(numeric)
    ? String(numeric)
    : value.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
  return `${normalized} kg`;
}

export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/** 错误文案：把后端讲清的理由原样带出来，不二次概括。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) return e.message || '操作失败，请稍后重试';
  if (e instanceof Error) return e.message;
  return '操作失败，请稍后重试';
}

/**
 * 执行打印后的结果文案。
 *
 * <p>只回「成功」是不够的：操作人要看到这次一共打了几张、其中第几次、有没有落到告警区间。
 */
export function executionText(result: LabelPrintExecution): string {
  const labels = result.logs.length;
  const objects = result.counts.length;
  const warned = result.warnedTargets;
  if (labels === 0) return '本次没有产生打印凭据';
  const base = `已打印 ${labels} 张（${objects} 个对象）`;
  return warned > 0 ? `${base}，其中 ${warned} 个对象落在重复打印告警区间` : base;
}