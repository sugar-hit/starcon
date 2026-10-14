import type {
  ImportFieldMappingInput,
  ImportFieldMappingPage,
  ImportFieldMappingRow,
  ImportJobPage,
  ImportJobRow,
  ImportJobRowLine,
  ImportJobRowPage,
  ImportMappingInput,
  ImportRowErrorLine,
  ImportRowErrorPage,
  ImportTemplateColumn,
  ImportTemplateDetail,
  ImportTemplateInput,
  ImportTemplatePage,
  ImportTemplateRow,
  ImportUploadSummary,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.8.1.1 导入向导前端模型。
 *
 * <p>这一页最容易出错的三处，都在前端就注定：
 *
 * <ul>
 *   <li><b>「提交」不等于「导入完成」。</b>蓝图写死「导入是异步任务，大文件不阻塞页面」，
 *       所以 {@code prevalidate} / {@code execute} 返回的是**已受理**，不是结果。
 *       拿到 200 就显示「导入完成」是最严重的错：它让用户关掉页面，
 *       而后台一个字节都还没写。本模块用 {@link isSettled} + {@link progressText}
 *       把「还在跑」与「跑完了」在界面上分开。
 *   <li><b>「有错误的行不落库」必须在界面上说清。</b>3.8.1.2 蓝图：不允许「尽力导入」
 *       产生半截数据。所以 {@link executeHint} 明确告诉用户：执行只写预校验判为可用的行，
 *       有错误的行不会进库，需要改对后重新上传 —— 而不是「尽力导入了大部分」。
 *   <li><b>「同一租户同时只有一个同类任务」要由后端裁决。</b>前端禁用按钮只是体面，
 *       真正拦住第二个任务的是 DB 的部分唯一索引（V29）。所以前端**必须**把后端 409
 *       的原文显示出来（它点名了当前在跑的文件名），而不是吞掉换成一句「导入失败」。
 * </ul>
 */

/** 导入模式：与 V29 的 {@code import_job.import_mode} CHECK 和后端 {@code ImportRules.Mode} 同一口径。 */
export const IMPORT_MODES = [
  {
    value: 'CREATE',
    label: '新增',
    hint: '只新建。编码已存在就跳过这一行，不会覆盖它 —— 想改已有数据请用更新或按编码匹配',
  },
  {
    value: 'UPDATE',
    label: '更新',
    hint: '只更新。编码不存在就报错（那一行不落库）—— 适合「先建后补资料」的批量回填',
  },
  {
    value: 'MATCH_BY_CODE',
    label: '按编码匹配',
    hint: '按匹配键找：找到了就更新，找不到就新建。多数批量导入用这个',
  },
] as const;

/** 目标对象：与 V29 的 {@code import_job.target_entity} CHECK 同一口径。故意不含 QUOTE，见迁移文件头。 */
export const TARGET_ENTITIES = [
  { value: 'SPU', label: '商品 SPU', hint: '编码、名称、描述、品牌、类目、状态' },
  { value: 'SKU', label: 'SKU', hint: '编码、所属商品、名称、基础单位、状态' },
  {
    value: 'ATTRIBUTE',
    label: '属性值',
    hint: '商品 + 属性编码 + 属性值（只写值，不新建属性定义）',
  },
  { value: 'PACKAGE', label: '包装档案', hint: '长宽高、净重、毛重（毛重不能小于净重）' },
  { value: 'BARCODE', label: '条码', hint: 'SKU + 条码体系 + 条码值（一个条码只能属于一个 SKU）' },
] as const;

/** 任务状态：与 V29 的 {@code import_job.status} CHECK 同一口径。 */
export const JOB_STATUSES = [
  { value: 'PENDING', label: '已上传' },
  { value: 'PREVALIDATING', label: '预校验中' },
  { value: 'PREVALIDATED', label: '预校验完成' },
  { value: 'RUNNING', label: '导入中' },
  { value: 'SUCCEEDED', label: '全部成功' },
  { value: 'PARTIAL', label: '部分成功' },
  { value: 'FAILED', label: '导入失败' },
  { value: 'CANCELLED', label: '已取消' },
] as const;

/**
 * **仍在跑**的状态。用来决定要不要继续轮询。
 *
 * <p>与后端部分唯一索引的活跃态完全一致（V29
 * {@code import_job_one_active_per_entity}）：这四个状态占用互斥槽位，
 * 其余都是终态。两者必须一致，否则页面会在任务已经结束后继续转圈。
 */
export const ACTIVE_STATUSES = ['PENDING', 'PREVALIDATING', 'PREVALIDATED', 'RUNNING'] as const;

/** 逐行结论：与 V29 的 {@code import_job_row.outcome} CHECK 同一口径。 */
export const ROW_OUTCOMES = [
  { value: 'PENDING', label: '待预校验' },
  { value: 'VALID', label: '校验通过' },
  { value: 'INVALID', label: '校验不通过' },
  { value: 'IMPORTED', label: '已导入' },
  { value: 'SKIPPED', label: '已跳过' },
  { value: 'FAILED', label: '写入失败' },
] as const;

export type ImportMode = (typeof IMPORT_MODES)[number]['value'];
export type TargetEntity = (typeof TARGET_ENTITIES)[number]['value'];

export function modeLabel(mode: string): string {
  return IMPORT_MODES.find((m) => m.value === mode)?.label ?? mode;
}

export function modeHint(mode: string): string {
  return IMPORT_MODES.find((m) => m.value === mode)?.hint ?? '';
}

export function entityLabel(entity: string): string {
  return TARGET_ENTITIES.find((e) => e.value === entity)?.label ?? entity;
}

export function statusLabel(status: string): string {
  return JOB_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/** 与 {@code @starcon/ui} 的 Badge tone 词表对齐（success / warning / neutral），不自造。 */
export function statusTone(status: string): 'success' | 'warning' | 'neutral' {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'PARTIAL':
    case 'FAILED':
      return 'warning';
    default:
      return 'neutral';
  }
}

export function outcomeLabel(outcome: string): string {
  return ROW_OUTCOMES.find((o) => o.value === outcome)?.label ?? outcome;
}

/** 逐行结论的色调：成功/跳过是绿的，失败与不通过必须显眼（那是「这行没进库」）。 */
export function outcomeTone(outcome: string): 'success' | 'warning' | 'neutral' {
  switch (outcome) {
    case 'IMPORTED':
      return 'success';
    case 'INVALID':
    case 'FAILED':
      return 'warning';
    case 'SKIPPED':
    case 'VALID':
      return 'neutral';
    default:
      return 'neutral';
  }
}

export function severityLabel(severity: string): string {
  return severity === 'ERROR' ? '必须修正' : '可跳过';
}

/** 任务是否已到终态（不再需要轮询）。 */
export function isSettled(status: string): boolean {
  return !ACTIVE_STATUSES.includes(status as (typeof ACTIVE_STATUSES)[number]);
}

/** 是否仍在后台跑 —— 页面据此决定要不要继续轮询。 */
export function isRunning(status: string): boolean {
  return status === 'PREVALIDATING' || status === 'RUNNING';
}

/**
 * 进度文案。
 *
 * <p>「已校验 12 / 40 行」这类数字**只在真的跑完之后才是结论**；
 * 还在跑时它是「已推进的进度」。所以措辞上区分开，
 * 免得用户把进行中的数字当成最终结果。
 */
export function progressText(job: ImportJobRow): string {
  const prevalidating = job.status === 'PREVALIDATING';
  if (prevalidating) {
    return job.rowCount > 0
      ? `正在校验，已完成 ${job.validCount + job.invalidCount} / ${job.rowCount} 行`
      : '正在校验…';
  }
  if (job.status === 'RUNNING') {
    return job.rowCount > 0
      ? `正在导入，已完成 ${job.appliedCount + job.failedCount + job.skippedCount} / ${job.validCount} 行`
      : '正在导入…';
  }
  return `共 ${job.rowCount} 行`;
}

/** 进度百分比（0–100）。分母为 0 时返回 0，避免 NaN 画出一个空的进度条。 */
export function progressPercent(job: ImportJobRow): number {
  const done =
    job.status === 'PREVALIDATING'
      ? job.validCount + job.invalidCount
      : job.status === 'RUNNING'
        ? job.appliedCount + job.failedCount + job.skippedCount
        : job.rowCount;
  const total = job.status === 'PREVALIDATING' ? job.rowCount : job.validCount;
  if (total <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((done / total) * 100)));
}

/**
 * 执行前的提示。
 *
 * <p>存在的理由是 3.8.1.2 蓝图那句红线：「**校验失败的行不落库**，不允许『尽力导入』产生半截数据」。
 * 用户按下执行之前必须知道将要发生什么：只写校验通过的行，有错的行留在错误清单里。
 */
export function executeHint(job: ImportJobRow | null): string {
  if (!job) return '';
  if (job.status !== 'PREVALIDATED') return '';
  if (job.invalidCount === 0) {
    return `${job.validCount} 行全部通过校验，可以执行。执行是异步任务，大文件不会卡住页面。`;
  }
  return (
    `将导入 ${job.validCount} 行；**${job.invalidCount} 行有必须修正的问题，不会落库**` +
    `（不允许「尽力导入」产生半截数据）。改对后可以重新上传这个文件再导一次。`
  );
}

/** 上传文件格式说明。Excel 未支持这件事必须写在界面上，不能只写在文档里。 */
export const FORMAT_HINT =
  '当前**只支持 CSV**（UTF-8 或 GBK，自动识别；带 BOM 的 Excel 另存文件也能直接读）。' +
  '**不支持 .xlsx / .xls** —— 仓库里没有 Excel 解析依赖，要支持需先引入 Apache POI。' +
  '请在 Excel 里「另存为 CSV UTF-8」再上传。';

/** 报价（3.6.2.1）尚未交付这件事必须在界面上说清，而不是默默少一类对象。 */
export const QUOTE_BOUNDARY_HINT =
  '**报价导入暂不可用**：供应商报价模块（3.6.2.1）尚未交付，价格的唯一写入所有者是定价服务，' +
  'catalog 不建报价表 —— 否则会出现两个价格事实来源。本页可导入 SPU / SKU / 属性 / 包装 / 条码五类。';

/** 校验报告（3.8.1.2）尚未交付：错误明细本模块已落库并可查，但报告页归 3.8.1.2。 */
export const REPORT_BOUNDARY_HINT =
  '逐行错误明细（必须修正 / 可跳过）由本模块**落库并提供查询**，' +
  '完整的校验报告页面与「带标注错误文件导出」属于 3.8.1.2，尚未交付。';

// ================================================================ 接口

export type TemplateFilter = {
  targetEntity: string | null;
  keyword: string | null;
  page: number;
  size: number;
};

export async function fetchTemplates(filter: TemplateFilter): Promise<ImportTemplatePage> {
  const query = new URLSearchParams();
  if (filter.targetEntity) query.set('targetEntity', filter.targetEntity);
  if (filter.keyword) query.set('q', filter.keyword);
  query.set('page', String(filter.page));
  query.set('size', String(filter.size));
  return api<ImportTemplatePage>(`catalog/import-templates?${query.toString()}`);
}

export async function fetchTemplate(id: string): Promise<ImportTemplateDetail> {
  return api<ImportTemplateDetail>(`catalog/import-templates/${id}`);
}

export async function createTemplate(input: ImportTemplateInput): Promise<ImportTemplateDetail> {
  return api<ImportTemplateDetail>('catalog/import-templates', 'POST', input);
}

export async function updateTemplate(
  id: string,
  input: ImportTemplateInput,
): Promise<ImportTemplateDetail> {
  return api<ImportTemplateDetail>(`catalog/import-templates/${id}`, 'PUT', input);
}

export async function deleteTemplate(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`catalog/import-templates/${id}`, 'DELETE');
}

/** 模板 CSV 下载的完整地址。用普通链接而不是 api()：它是 CSV 而不是 JSON。 */
export function templateFileUrl(id: string): string {
  return `/api/catalog/catalog/import-templates/${id}/file`;
}

export async function fetchMappings(templateId: string): Promise<ImportFieldMappingPage> {
  return api<ImportFieldMappingPage>(
    `catalog/import-field-mappings?templateId=${encodeURIComponent(templateId)}`,
  );
}

export async function saveMapping(input: ImportFieldMappingInput): Promise<ImportFieldMappingRow> {
  return api<ImportFieldMappingRow>('catalog/import-field-mappings', 'POST', input);
}

export async function deleteMapping(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`catalog/import-field-mappings/${id}`, 'DELETE');
}

/**
 * 上传文件（multipart）。
 *
 * <p>**不能走 {@link api}**：那个助手只会发 JSON，而这里是真正的文件上传
 * （运营上传的就是文件，走文件通道才带得上真实文件名与大小，
 * 而这两样是「导进去的到底是哪份文件」的凭据）。
 *
 * <p>401 仍然派发 {@code identity-expired}：登录过期在这一步也必须回到登录页，
 * 否则用户只会看到一句无来由的上传失败。
 */
export async function uploadFile(
  templateId: string,
  importMode: string,
  file: File,
  mapping?: Record<string, string>,
): Promise<ImportUploadSummary> {
  const form = new FormData();
  form.append('templateId', templateId);
  form.append('importMode', importMode);
  form.append('file', file);
  if (mapping && Object.keys(mapping).length > 0) {
    form.append(
      'mapping',
      Object.entries(mapping)
        .map(([k, v]) => `${k}:${v}`)
        .join(','),
    );
  }
  const response = await fetch('/api/catalog/catalog/import-jobs', {
    method: 'POST',
    body: form,
    cache: 'no-store',
  });
  if (response.status === 401 && typeof window !== 'undefined')
    window.dispatchEvent(new Event('identity-expired'));
  const text = await response.text();
  let result: unknown = null;
  try {
    result = text ? JSON.parse(text) : null;
  } catch {
    throw new ApiError(response.ok ? 0 : response.status, '服务响应无法解析，请重试');
  }
  if (!response.ok) {
    const body = result as { detail?: string; message?: string } | null;
    throw new ApiError(
      response.status,
      body?.detail ?? body?.message ?? '上传失败，请检查文件后重试',
      result,
    );
  }
  return result as ImportUploadSummary;
}

export async function fetchJobs(
  targetEntity: string | null,
  status: string | null,
  page: number,
  size: number,
): Promise<ImportJobPage> {
  const query = new URLSearchParams();
  if (targetEntity) query.set('targetEntity', targetEntity);
  if (status) query.set('status', status);
  query.set('page', String(page));
  query.set('size', String(size));
  return api<ImportJobPage>(`catalog/import-jobs?${query.toString()}`);
}

export async function fetchJob(id: string): Promise<ImportJobRow> {
  return api<ImportJobRow>(`catalog/import-jobs/${id}`);
}

/**
 * 改本次任务的字段映射。
 *
 * <p>{@code saveAs} 非空时顺带存成可复用配置：不同供应商列名不同
 * （Amazon 的 {@code item_sku}、1688 的「商家编码」），映射一次之后每批文件都能直接套。
 */
export async function setJobMapping(
  id: string,
  mapping: Record<string, string>,
  saveAs?: string,
): Promise<ImportJobRow> {
  // 契约里 saveAs 是必填的 string，所以「不保存」传空串而不是省略字段
  // （省略会得到一条缺字段的请求体，后端 @NotNull 之外还会在反序列化时报 400）。
  return api<ImportJobRow>(`catalog/import-jobs/${id}/mapping`, 'PUT', {
    mapping,
    saveAs: saveAs ?? '',
  } satisfies ImportMappingInput);
}

/** 请求预校验。返回的是**已受理**，不是结论 —— 结果要轮询任务详情。 */
export async function requestPrevalidate(id: string): Promise<ImportJobRow> {
  return api<ImportJobRow>(`catalog/import-jobs/${id}/prevalidate`, 'POST');
}

/** 请求执行。同样是**已受理**。 */
export async function requestExecute(id: string): Promise<ImportJobRow> {
  return api<ImportJobRow>(`catalog/import-jobs/${id}/execute`, 'POST');
}

export async function cancelJob(id: string): Promise<ImportJobRow> {
  return api<ImportJobRow>(`catalog/import-jobs/${id}/cancel`, 'POST');
}

export async function fetchRows(
  id: string,
  outcome: string | null,
  page: number,
  size: number,
): Promise<ImportJobRowPage> {
  const query = new URLSearchParams();
  if (outcome) query.set('outcome', outcome);
  query.set('page', String(page));
  query.set('size', String(size));
  return api<ImportJobRowPage>(`catalog/import-jobs/${id}/rows?${query.toString()}`);
}

export async function fetchErrors(
  id: string,
  severity: string | null,
  page: number,
  size: number,
): Promise<ImportRowErrorPage> {
  const query = new URLSearchParams();
  if (severity) query.set('severity', severity);
  query.set('page', String(page));
  query.set('size', String(size));
  return api<ImportRowErrorPage>(`catalog/import-jobs/${id}/errors?${query.toString()}`);
}

// ================================================================ 纯逻辑

export type TemplateInput = {
  code: string;
  name: string;
  targetEntity: string;
  note: string;
  enabled: boolean;
};

/**
 * 模板表单校验。
 *
 * <p>与后端 {@code ImportTemplateInput} 的约束对齐，但**前端也必须自己判一遍**：
 * 浏览器端校验不是为了替后端兜底，而是为了在提交前就指出「匹配键只能有一个」
 * 这类需要理解规则的问题，而不是让用户提交完再读一段 400。
 */
export function templateProblem(input: TemplateInput): string | null {
  if (!input.code.trim()) return '模板编码不能为空';
  if (input.code.trim().length > 64) return '模板编码最长 64 个字符';
  if (!input.name.trim()) return '模板名称不能为空';
  if (!TARGET_ENTITIES.some((e) => e.value === input.targetEntity))
    return '目标对象不合法（可选：SPU / SKU / ATTRIBUTE / PACKAGE / BARCODE）';
  return null;
}

export function toTemplateInput(input: TemplateInput): ImportTemplateInput {
  return {
    code: input.code.trim(),
    name: input.name.trim(),
    note: input.note.trim(),
    targetEntity: input.targetEntity,
    enabled: input.enabled,
    // 不传列时后端套该目标对象的标准列（ImportRules.defaultColumns），
    // 所以「先建一个能用的模板」只需要这三项。
    columns: [],
  };
}

export function emptyTemplateInput(): TemplateInput {
  return { code: '', name: '', targetEntity: 'SPU', note: '', enabled: true };
}

/**
 * 字段映射的缺列检查。
 *
 * <p>「模板列在文件里没有对应列」是预校验会报的 {@code UNMAPPED_COLUMN}，
 * 但在**映射这一步**就提示出来更好：那时用户正看着文件列头，
 * 让他来回改比提交一次再看错误清单快得多。
 */
export function unmappedRequiredKeys(
  template: ImportTemplateDetail | null,
  mapping: Record<string, string>,
): string[] {
  if (!template) return [];
  return template.columns
    .filter((c) => c.required && !mapping[c.fieldKey])
    .map((c) => `${c.header}（${c.fieldKey}）`);
}

export function mappingProblem(
  template: ImportTemplateDetail | null,
  mapping: Record<string, string>,
): string | null {
  if (!template) return null;
  const missing = unmappedRequiredKeys(template, mapping);
  if (missing.length > 0)
    return `还有必填列没有对应到文件里的列：${missing.join('、')}。请在下面为它们各选一列`;
  return null;
}

/** 文件里尚未被任何模板字段用到的列 —— 提示「这些列会被忽略」，而不是让人以为它们也导入了。 */
export function ignoredHeaders(
  template: ImportTemplateDetail | null,
  headers: string[],
  mapping: Record<string, string>,
): string[] {
  if (!template) return [];
  const used = new Set(Object.values(mapping).filter(Boolean));
  return headers.filter((h) => !used.has(h));
}

/**
 * 逐行错误按「行号 + 字段」聚合的展示模型。
 *
 * <p>直接平铺 400 条错误会让用户读不下去，而 3.8.1.2 尚未交付报告页，
 * 所以本页至少要按行分组、每行一眼看清「哪几列错了、分别是什么级别」。
 */
export type RowErrorGroup = {
  rowNo: number;
  errorCount: number;
  items: ImportRowErrorLine[];
};

export function groupErrors(lines: ImportRowErrorLine[]): RowErrorGroup[] {
  const byRow = new Map<number, ImportRowErrorLine[]>();
  for (const line of lines) {
    const bucket = byRow.get(line.rowNo);
    if (bucket) bucket.push(line);
    else byRow.set(line.rowNo, [line]);
  }
  return [...byRow.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([rowNo, items]) => ({ rowNo, errorCount: items.length, items }));
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}

/**
 * 重导出用到的契约类型。
 *
 * <p>工作区与单测都从这里取类型，而不是各自从 `api-contracts` 再引一遍 ——
 * 两处各引一次时，改契约的人只会改掉其中一处，然后在另一处看到一片红。
 */
export type {
  ImportFieldMappingInput,
  ImportFieldMappingPage,
  ImportFieldMappingRow,
  ImportJobPage,
  ImportJobRow,
  ImportJobRowLine,
  ImportJobRowPage,
  ImportMappingInput,
  ImportRowErrorLine,
  ImportRowErrorPage,
  ImportTemplateColumn,
  ImportTemplateDetail,
  ImportTemplateInput,
  ImportTemplatePage,
  ImportTemplateRow,
  ImportUploadSummary,
};
