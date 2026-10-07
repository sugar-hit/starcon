import type {
  ExportAppliedFilter,
  ExportFieldInfo,
  ExportFilterInfo,
  ExportJobInput,
  ExportJobPage,
  ExportJobRow,
  ExportSortInfo,
  ExportSourceInfo,
  ExportSourcePage,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

export type {
  ExportAppliedFilter,
  ExportFieldInfo,
  ExportFilterInfo,
  ExportJobRow,
  ExportSortInfo,
  ExportSourceInfo,
};

/** 报价（3.6.2.1）由另一条车道开发、尚未并入本分支，因此不在可导出数据源里。 */
export const QUOTE_BOUNDARY_HINT =
  '报价列表（3.6.2.1）尚未交付，因此本页没有「报价」数据源。' +
  '导出是注册式的：报价上线后由后端新增一个数据源即可，字段选择器与本页无需改动。';

/** 敏感字段必须显式授权并写明用途，这是本模块内自行落地的合规要求（16.6.1 未开发）。 */
export const PII_HINT =
  '含邮箱 / 电话 / 手机号的导出属于敏感数据外发，必须填写导出用途；' +
  '用途会与操作人、时间、条件、字段清单、行数一并留档，供合规回溯。';

/** 下载有效期由后端配置，前端只回显，不自己算 —— 前后端各算一次必然漂移。 */
export const TTL_HINT = '导出文件带下载有效期，过期后由后端清理且不可再下载；作废可立即中断下载。';

export type { ExportJobInput };

// ================================================================ 纯逻辑（可单测）

export type BadgeTone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/** 状态 → Badge 语气。返回类型收窄到 Badge 真实支持的 tone 联合，改了 UI 就会在这里报错。 */
export function statusTone(status: string): BadgeTone {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'CANCELLED':
    case 'EXPIRED':
      return 'neutral';
    default:
      return 'warning';
  }
}

export function statusLabel(status: string): string {
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

export function isRunning(row: ExportJobRow): boolean {
  return row.status === 'PENDING' || row.status === 'RUNNING';
}

/** 0 行是合法结果（条件确实没命中），但必须让人一眼看出「不是失败了」。 */
export function rowCountText(row: ExportJobRow): string {
  if (isRunning(row)) return '生成中…';
  return `${row.rowCount} 行`;
}

export function byteText(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '';
  const value = Number(bytes);
  if (!Number.isFinite(value)) return '';
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

/** 本地时间显示：导出记录里的时间是 UTC ISO 串，直接展示会让运营对不上自己的时区。 */
export function localTime(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function piiFieldsText(row: ExportJobRow): string {
  return row.piiFields.join(' / ');
}

export function conditionText(filters: ExportAppliedFilter[]): string {
  if (filters.length === 0) return '无条件（导出当前可见的全部数据）';
  return filters.map((f) => `${f.label}=${f.value}`).join('；');
}

/**
 * 提交前的本地校验。
 *
 * <p>**不代替后端校验**，只是把最常见的两个错误在点提交那一刻就说清楚：
 * 一个字段都没勾、勾了敏感字段却没写用途。后端仍然会再拒一道（那是权威）。
 */
export function requestProblem(
  fields: string[],
  piiPurpose: string,
  sources: ExportSourceInfo[],
  sourceKey: string | null,
): string | null {
  if (!sourceKey) return '请先选择要导出的数据源';
  const source = sources.find((s) => s.key === sourceKey);
  if (!source) return '数据源不存在，请重新选择';
  if (!source.available) return source.note || '该数据源尚未交付';
  if (fields.length === 0) return '请至少选择一个导出字段';
  const pii = source.fields.filter((f) => f.pii && fields.includes(f.key));
  if (pii.length > 0 && !piiPurpose.trim())
    return `本次导出包含敏感字段（${pii.map((f) => f.label).join('、')}），必须填写导出用途`;
  return null;
}

/** 默认勾选哪些字段：不带 PII 的前几个，避免默认就触发授权流程。 */
export function defaultFields(source: ExportSourceInfo | null): string[] {
  if (!source) return [];
  return source.fields.filter((f) => !f.pii).slice(0, 3).map((f) => f.key);
}

export function availableFilters(source: ExportSourceInfo | null): ExportFilterInfo[] {
  if (!source) return [];
  return source.filters.filter((f) => f.available);
}

export function unavailableFilters(source: ExportSourceInfo | null): ExportFilterInfo[] {
  if (!source) return [];
  return source.filters.filter((f) => !f.available);
}

export function sortOptions(source: ExportSourceInfo | null): ExportSortInfo[] {
  return source?.sorts ?? [];
}

// ================================================================ 接口

export async function fetchSources(): Promise<ExportSourcePage> {
  return api<ExportSourcePage>('catalog/export-sources');
}

export async function fetchJobs(filter: {
  sourceKey: string | null;
  status: string | null;
  page: number;
  size: number;
}): Promise<ExportJobPage> {
  const query = new URLSearchParams();
  if (filter.sourceKey) query.set('sourceKey', filter.sourceKey);
  if (filter.status) query.set('status', filter.status);
  query.set('page', String(filter.page));
  query.set('size', String(filter.size));
  return api<ExportJobPage>(`catalog/export-jobs?${query.toString()}`);
}

export async function createJob(input: ExportJobInput): Promise<ExportJobRow> {
  return api<ExportJobRow>('catalog/export-jobs', 'POST', input);
}

export async function cancelJob(id: string): Promise<ExportJobRow> {
  return api<ExportJobRow>(`catalog/export-jobs/${id}/cancel`, 'POST');
}

/** 导出文件下载的完整地址。用普通链接而不是 api()：它是 CSV 而不是 JSON。 */
export function jobFileUrl(id: string): string {
  return `/api/catalog/catalog/export-jobs/${id}/file`;
}

/** 把后端的错误收成一句能直接显示的话（含字段清单与授权理由这两类长提示）。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '操作失败，请重试';
}