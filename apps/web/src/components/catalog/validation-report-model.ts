import type {
  ImportValidationCategoryStat,
  ImportValidationColumnStat,
  ImportValidationExportInput,
  ImportValidationExportPage,
  ImportValidationExportRow,
  ImportValidationIssueLine,
  ImportValidationIssuePage,
  ImportValidationReportRow,
  ImportValidationSummary,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 3.8.1.2 校验报告前端模型。
 *
 * <p>蓝图说这一页要做三件事，而它们在界面上都对应一条硬纪律：
 *
 * <ul>
 *   <li><b>逐行逐列展示</b>：运营的操作是「改第几行的哪一格」，所以明细按行分组、
 *       每条带<b>文件里的原始值</b>（没有它就得回原文件里逐格比对）。
 *   <li><b>区分「可跳过」与「必须修正」</b>：这不只是两种颜色，
 *       它决定这一行到底落不落库。所以页面上必须有一个地方把
 *       「有 N 行永远不会进库」直接说出来，而不是让人自己数红色。
 *   <li><b>导出带标注的错误文件</b>：导出分成两种，含义不同 —— 带标注的错误文件给
 *       <b>全部</b>数据行（看得到好行/坏行比例），修正版模板只给<b>有问题的</b>行。
 * </ul>
 *
 * <p><b>报告可能已过期（{@code stale}）</b>：报告是某一轮预校验结论的快照，
 * 而上游预校验可重跑。所以页面必须在过期时显式提示并引导「重新生成」，
 * 绝不能把上一轮的数字当成当前结论继续用。
 */

/** 严重级别筛选：与后端 {@code ImportValidationRules.SEVERITY_FILTERS} 同一词表。 */
export const SEVERITY_FILTERS = [
  { value: 'ALL', label: '全部问题', hint: '必须修正 + 可跳过' },
  { value: 'ERROR', label: '必须修正', hint: '这些行不会落库' },
  { value: 'WARNING', label: '可跳过', hint: '这些行照常落库' },
] as const;

/**
 * 错误类别：与后端 {@code ImportValidationRules.Category} 同一词表（六类，无兜底桶）。
 *
 * <p>前端只读后端给的 {@code label}，这里的列表只用于筛选下拉 ——
 * 所以后端加一类时这里不会显示错标签（旧标签仍来自后端返回值）。
 */
export const CATEGORIES = [
  { value: 'REQUIRED_MISSING', label: '必填缺失' },
  { value: 'FORMAT_INVALID', label: '格式错误' },
  { value: 'DUPLICATE_CODE', label: '重复编码' },
  { value: 'REFERENCE_MISSING', label: '引用不存在' },
  { value: 'UNIQUE_CONFLICT', label: '唯一冲突' },
  { value: 'WRITE_FAILED', label: '写入失败' },
] as const;

/** 导出种类：与后端 {@code ImportValidationRules.KIND_*} 同一词表。 */
export const EXPORT_KINDS = [
  {
    value: 'ANNOTATED_ERRORS',
    label: '带标注的错误文件',
    hint: '全部数据行 + 标注列。改完可以原样重新上传（标注列会被忽略）',
  },
  {
    value: 'FIXED_TEMPLATE',
    label: '修正版模板',
    hint: '只含有问题的行 + 标注列。改完这一份就能重传',
  },
] as const;

export type SeverityFilter = (typeof SEVERITY_FILTERS)[number]['value'];
export type ExportKind = (typeof EXPORT_KINDS)[number]['value'];

/** 与 `@starcon/ui` 的 Badge tone 词表对齐，不自造。 */
export function severityTone(severity: string): 'danger' | 'warning' {
  return severity === 'ERROR' ? 'danger' : 'warning';
}

export function severityLabel(severity: string): string {
  return severity === 'ERROR' ? '必须修正' : '可跳过';
}

export function filterLabel(filter: string | null): string {
  if (!filter) return '全部问题';
  return SEVERITY_FILTERS.find((f) => f.value === filter)?.label ?? filter;
}

export function kindLabel(kind: string): string {
  return EXPORT_KINDS.find((k) => k.value === kind)?.label ?? kind;
}

export function kindHint(kind: string): string {
  return EXPORT_KINDS.find((k) => k.value === kind)?.hint ?? '';
}

// ================================================================ 归并与文案

/** 按行分组的一条明细。 */
export type IssueRowGroup = {
  rowNo: number;
  /** 这一行是否永不落库（行级事实：只要有 ERROR 就是） */
  blocking: boolean;
  /** 这一行的全部问题（同一行可能 2 条 WARNING + 1 条 ERROR） */
  items: ImportValidationIssueLine[];
};

/**
 * 把逐条明细按行分组。
 *
 * <p>直接平铺 N 条会让用户读不下去 —— 运营要改的是「第 47 行的那一格」，
 * 不是一个孤立的错误码。按行分组之后，一屏里能直接回答「这一行要改几处」。
 */
export function groupIssuesByRow(lines: ImportValidationIssueLine[]): IssueRowGroup[] {
  const byRow = new Map<number, ImportValidationIssueLine[]>();
  for (const line of lines) {
    const bucket = byRow.get(line.rowNo);
    if (bucket) bucket.push(line);
    else byRow.set(line.rowNo, [line]);
  }
  return [...byRow.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([rowNo, items]) => ({ rowNo, blocking: items.some((i) => i.rowBlocking), items }));
}

/** 这一行要改几处（去重后的字段数：同一字段两条错误只算一处）。 */
export function distinctFieldCount(items: ImportValidationIssueLine[]): number {
  const labels = new Set<string>();
  for (const item of items) {
    const key = item.fieldKey.trim();
    if (key) labels.add(key);
  }
  // 整行级问题（fieldKey 空串）不占「某一格」，但仍是一次要改的地方
  return labels.size + (items.some((i) => !i.fieldKey.trim()) ? 1 : 0);
}

/**
 * 这一批「有多少行真能落库」的百分比。
 *
 * <p>进度条的口径是<b>行</b>不是条：一条 ERROR 落在 1000 行的文件里，
 * 对「这批能不能导进去」的影响就是一行，不是三分之一。
 */
export function importablePercent(report: ImportValidationReportRow): number {
  if (report.totalRowCount <= 0) return 0;
  const importable = report.totalRowCount - report.blockingRowCount;
  return Math.round((importable / report.totalRowCount) * 100);
}

/**
 * 报告顶部那句话：有多少行会落库、多少行永远不会进库。
 *
 * <p>「必须修正」必须被说成「不会落库」而不是「有问题」——
 * 蓝图那半句是「不允许尽力导入产生半截数据」，所以这些行不是「先导进去再改」。
 */
export function blockingNotice(report: ImportValidationReportRow): string {
  if (report.blockingRowCount === 0)
    return `全部 ${report.totalRowCount} 行都可落库${report.skippableRowCount > 0 ? `（其中 ${report.skippableRowCount} 行有可跳过的提示）` : ''}。`;
  return (
    `${report.blockingRowCount} 行必须修正，这些行不会落库；` +
    `${report.totalRowCount - report.blockingRowCount} 行可落库。` +
    `导出带标注的文件改完重新上传，或在导入向导里改用「按编码匹配」只更新已存在的记录。`
  );
}

/** 报告过期时的那句话。 */
export function staleNotice(report: ImportValidationReportRow): string {
  return (
    `这份报告生成于 ${formatTime(report.generatedAt)}，之后任务又有过变动，` +
    `它描述的可能已经不是当前结论。重新生成后再按它改文件。`
  );
}

export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
}

/** 一类问题的总数（条）。 */
export function categoryIssueCount(stat: ImportValidationCategoryStat): number {
  return stat.errorCount + stat.warningCount;
}

/** 「这一列错得最多」的列表：只给真的有问题的列。 */
export function problemColumns(
  columns: ImportValidationColumnStat[],
): ImportValidationColumnStat[] {
  return columns.filter((c) => c.errorCount + c.warningCount > 0);
}

// ================================================================ 取数

export async function fetchReport(jobId: string): Promise<ImportValidationSummary> {
  return api<ImportValidationSummary>(`catalog/import-jobs/${jobId}/report`);
}

export async function regenerateReport(jobId: string): Promise<ImportValidationReportRow> {
  return api<ImportValidationReportRow>(`catalog/import-jobs/${jobId}/report`, 'POST');
}

export async function fetchIssues(
  jobId: string,
  severity: string | null,
  category: string | null,
  rowNo: number | null,
  page: number,
  size: number,
): Promise<ImportValidationIssuePage> {
  const query = new URLSearchParams();
  if (severity && severity !== 'ALL') query.set('severity', severity);
  if (category) query.set('category', category);
  if (rowNo) query.set('rowNo', String(rowNo));
  query.set('page', String(page));
  query.set('size', String(size));
  return api<ImportValidationIssuePage>(
    `catalog/import-jobs/${jobId}/report/issues?${query.toString()}`,
  );
}

export async function fetchExports(jobId: string): Promise<ImportValidationExportPage> {
  return api<ImportValidationExportPage>(`catalog/import-jobs/${jobId}/report/exports`);
}

export async function createExport(
  jobId: string,
  input: ImportValidationExportInput,
): Promise<ImportValidationExportRow> {
  return api<ImportValidationExportRow>(
    `catalog/import-jobs/${jobId}/report/exports`,
    'POST',
    input,
  );
}

/**
 * 下载导出文件的链接。
 *
 * <p>走**普通链接下载**而不是 {@code api()}：它是 CSV 而非 JSON，
 * 与 3.8.1.1 的模板下载同一处理方式。租户上下文由 Authorization 头（服务端 cookie 转换而来）解析，
 * 链接本身不带也不接受任何租户参数。
 */
export function exportFileUrl(jobId: string, exportId: string): string {
  return `/api/catalog/catalog/import-jobs/${jobId}/report/exports/${exportId}/file`;
}

/** 报告查询参数（从 URL 读，带上 jobId 直接进报告）。 */
export function reportJobId(search: string): string | null {
  return new URLSearchParams(search).get('jobId');
}

export { errorText } from './import-model';

/** 重导出用到的契约类型（工作区与单测都从这里取，避免两处各引一次）。 */
export type {
  ImportValidationCategoryStat,
  ImportValidationColumnStat,
  ImportValidationExportPage,
  ImportValidationExportRow,
  ImportValidationIssueLine,
  ImportValidationIssuePage,
  ImportValidationReportRow,
  ImportValidationSummary,
};
