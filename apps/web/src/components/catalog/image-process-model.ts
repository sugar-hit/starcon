import type {
  MediaProcessJob,
  MediaProcessJobDetail,
  MediaProcessJobPage,
  MediaProcessJobRow,
  MediaProcessOverview,
  MediaProcessRecipeInput,
  MediaProcessRecipeRow,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.4.1.2 图片处理前端模型。
 *
 * <p>这个页面最容易出错的三处：
 *
 * <ul>
 *   <li><b>提交是「已受理」而不是「已完成」</b>。蓝图写死了「处理是异步任务，长批量不阻塞页面」，
 *       所以 POST 返回的只是「排队中」。前端**绝不能**在提交后显示「处理完成」——
 *       进度只能来自轮询任务详情，而详情里的状态是库里可查的事实。
 *   <li><b>像素指标不能在前端算</b>。产物尺寸、字节数、哈希一律来自后端
 *       （产物是 media_asset 的新版本，元数据由 V19/V18 的触发器与探测得出）。
 *       前端只负责把给到的值显示出来，不该「预览一下猜个尺寸」。
 *   <li><b>「原图始终保留」要在界面上说清</b>。处理会推进素材的当前版本，
 *       如果用户以为原图被覆盖掉了就再传一次，那素材库里就会多出一份重复内容。
 *       界面上要明确「原图版本仍在版本历史里」。
 * </ul>
 */

/** 目标格式词表：与 V19 的 CHECK 和 `ImageProcessRules.TARGET_FORMATS` 同一口径。 */
export const TARGET_FORMATS = [
  { value: 'png', label: 'PNG', hint: '支持透明，刊登主图最稳妥' },
  { value: 'jpeg', label: 'JPEG', hint: '体积小；不接受透明，去背景时必须开白底' },
  { value: 'gif', label: 'GIF', hint: '仅 1 位透明，去背景边缘会出锯齿' },
  { value: 'bmp', label: 'BMP', hint: '无压缩，文件很大' },
] as const;

/** 适配方式：FIT 不裁掉画面，CROP_FILL 填满并裁掉溢出。 */
export const CROP_MODES = [
  { value: 'FIT', label: 'FIT 等比补白边', hint: '整张图都保留，多的地方补白边' },
  { value: 'CROP_FILL', label: 'CROP_FILL 填满裁切', hint: '填满目标框，超出部分居中裁掉' },
] as const;

export const WATERMARK_POSITIONS = [
  { value: 'TOP_LEFT', label: '左上' },
  { value: 'TOP_RIGHT', label: '右上' },
  { value: 'BOTTOM_LEFT', label: '左下' },
  { value: 'BOTTOM_RIGHT', label: '右下' },
  { value: 'CENTER', label: '居中' },
  { value: 'TILED', label: '平铺', hint: '防「裁掉角就绕过水印」' },
] as const;

export const JOB_STATUSES = [
  { value: 'PENDING', label: '排队中' },
  { value: 'RUNNING', label: '处理中' },
  { value: 'SUCCEEDED', label: '全部成功' },
  { value: 'PARTIAL', label: '部分失败' },
  { value: 'FAILED', label: '全部失败' },
  { value: 'CANCELLED', label: '已取消' },
] as const;

export const ITEM_STATUS_LABELS: Record<string, string> = {
  PENDING: '待处理',
  RUNNING: '处理中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  SKIPPED: '已跳过',
  CANCELLED: '已取消',
};

/** 终态：到达这些状态后不再轮询（还在跑才轮询，否则会把页面变成一个永不停止的请求循环）。 */
const TERMINAL_JOB_STATUSES = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED']);

export function jobStatusLabel(value: string | null | undefined): string {
  return JOB_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function itemStatusLabel(value: string | null | undefined): string {
  return ITEM_STATUS_LABELS[value ?? ''] ?? value ?? '未知';
}

export function terminal(status: string | null | undefined): boolean {
  return TERMINAL_JOB_STATUSES.has(status ?? '');
}

/** 只有还在跑的任务才需要轮询。与后端 `ImageProcessRules.terminal` 同一口径。 */
export function pollable(status: string | null | undefined): boolean {
  return status === 'PENDING' || status === 'RUNNING';
}

/** Badge 色调，直接用 `@starcon/ui` 的 tone 词表，不自造一套。 */
export function jobStatusTone(
  status: string | null | undefined,
): 'neutral' | 'success' | 'danger' | 'warning' {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'PARTIAL') return 'warning';
  if (status === 'FAILED') return 'danger';
  return 'neutral';
}

// ------------------------------------------------------------ 接口

export function fetchProcessingOverview() {
  return api<MediaProcessOverview>('catalog/media/processing/overview');
}

export function fetchRecipes() {
  return api<MediaProcessRecipeRow[]>('catalog/media/processing/recipes');
}

export function fetchRecipe(id: string) {
  return api<MediaProcessRecipeRow>(`catalog/media/processing/recipes/${id}`);
}

export function saveRecipe(id: string | null, input: MediaProcessRecipeInput) {
  return id
    ? api<{ id: string; version: number }>(`catalog/media/processing/recipes/${id}`, 'PUT', input)
    : api<{ id: string; version: number }>('catalog/media/processing/recipes', 'POST', input);
}

export function setRecipeEnabled(id: string, enabled: boolean) {
  return api<{ id: string; version: number }>(
    `catalog/media/processing/recipes/${id}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

export function deleteRecipe(id: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/media/processing/recipes/${id}?version=${version}`,
    'DELETE',
  );
}

export function fetchJobs(params: { keyword?: string; status?: string; page: number; size: number }) {
  const query = new URLSearchParams();
  if (params.keyword?.trim()) query.set('keyword', params.keyword.trim());
  if (params.status) query.set('status', params.status);
  query.set('page', String(params.page));
  query.set('size', String(params.size));
  return api<MediaProcessJobPage>(`catalog/media/processing/jobs?${query.toString()}`);
}

/** 提交处理任务。返回的是「已受理」——处理在线程池里跑，进度要靠 fetchJobDetail 轮询。 */
export function submitJob(recipeId: string, assetIds: string[]) {
  return api<MediaProcessJob>('catalog/media/processing/jobs', 'POST', { recipeId, assetIds });
}

export function fetchJobDetail(id: string) {
  return api<MediaProcessJobDetail>(`catalog/media/processing/jobs/${id}`);
}

export function cancelJob(id: string) {
  return api<MediaProcessJob>(`catalog/media/processing/jobs/${id}/cancel`, 'POST');
}

export function fetchAssetHistory(assetId: string) {
  return api<MediaProcessJobRow[]>(`catalog/media/processing/assets/${assetId}/history`);
}

// ------------------------------------------------------------ 校验（与服务层同口径）

export function recipeCodeProblem(value: string): string | null {
  const code = value.trim().toUpperCase();
  if (!code) return '配方编码不能为空';
  if (code.length > 32) return '配方编码最多 32 个字符';
  if (!/^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(code))
    return '配方编码请使用英文字母、数字、下划线或短横线，长度 1~32 位';
  return null;
}

export function recipeNameProblem(value: string): string | null {
  const name = value.trim();
  if (!name) return '配方名称不能为空';
  if (name.length > 100) return '配方名称最多 100 个字符';
  return null;
}

export function targetFormatProblem(value: string): string | null {
  const format = value.trim().toLowerCase();
  if (!format) return '目标格式不能为空';
  if (!TARGET_FORMATS.some((f) => f.value === format))
    return `目标格式只能是 ${TARGET_FORMATS.map((f) => f.value).join(' / ')}（服务端用 JDK ImageIO 写出，WEBP 没有内置写入器）`;
  return null;
}

export function toleranceProblem(value: number | null): string | null {
  if (value === null) return '去背景容差不能为空';
  if (value < 0 || value > 255) return '去背景容差必须在 0~255 之间';
  return null;
}

export function opacityProblem(value: number | null): string | null {
  if (value === null) return '水印不透明度不能为空';
  if (value < 0 || value > 100) return '水印不透明度必须在 0~100 之间';
  return null;
}

/** 目标尺寸要么两列都空（不缩放），要么两列都有值。 */
export function targetSizeProblem(width: number | null, height: number | null): string | null {
  const noWidth = width === null;
  const noHeight = height === null;
  if (noWidth && noHeight) return null;
  if (noWidth) return '目标尺寸要同时给宽和高：只给一个尺寸时裁剪结果不唯一';
  if (noHeight) return '目标尺寸要同时给宽和高：只给一个尺寸时裁剪结果不唯一';
  if (width <= 0 || height <= 0) return '目标尺寸必须大于 0';
  if (width > 12000 || height > 12000) return '目标边长最多 12000 px（再大就该用原图而不是放大）';
  return null;
}

/**
 * 去背景 + 目标格式的组合。
 *
 * <p>只有 png 有真正的 alpha 通道，gif 只有 1 位透明，bmp / jpeg 没有 alpha。
 * 这条校验在提交前就给出来，而不是等后端 400——用户填完 200 张图才被拒太晚了。
 */
export function backgroundFormatProblem(
  removeBackground: boolean,
  format: string,
): string | null {
  if (!removeBackground) return null;
  if (format.trim().toLowerCase() === 'png') return null;
  return `去背景会把背景抠成透明，而目标格式 ${format.trim().toLowerCase()} 不支持透明（jpeg/bmp 没有 alpha 通道，gif 只有 1 位）。请改用 png 目标格式，或勾选白底（先抠背景再铺白底）`;
}

export function ratioLabelProblem(value: string): string | null {
  const label = value.trim();
  if (!label) return '比例档标签不能为空';
  if (label.length > 32) return '比例档标签最多 32 个字符';
  return null;
}

export function ratiosProblem(
  ratios: ({ label: string; widthPx: number; heightPx: number } | null)[] | null,
): string | null {
  // 契约里 ratios 可空（Jackson 没标 @NotNull），所以这里先滤掉 null 再判：
  // 让 null 参与后面的 .label 会在运行时炸，而不是给出一句人话。
  const list = (ratios ?? []).filter((r): r is { label: string; widthPx: number; heightPx: number } =>
    r !== null,
  );
  if (list.length > 8) return '一个配方最多 8 个比例档';
  const seen = new Set<string>();
  for (const ratio of list) {
    const problem = ratioLabelProblem(ratio.label);
    if (problem) return problem;
    if (ratio.widthPx <= 0 || ratio.heightPx <= 0) return '比例档的宽高必须大于 0';
    if (ratio.widthPx > 12000 || ratio.heightPx > 12000) return '比例档边长最多 12000 px';
    const key = ratio.label.trim().toUpperCase();
    if (seen.has(key)) return `比例档标签重复：${key}（同一配方内标签必须唯一，否则产物无法区分）`;
    seen.add(key);
  }
  return null;
}

export function recipeProblem(input: MediaProcessRecipeInput): string | null {
  return (
    recipeCodeProblem(input.code) ??
    recipeNameProblem(input.name) ??
    targetFormatProblem(input.targetFormat) ??
    toleranceProblem(input.backgroundTolerance ?? null) ??
    opacityProblem(input.watermarkOpacity ?? null) ??
    targetSizeProblem(input.targetWidthPx ?? null, input.targetHeightPx ?? null) ??
    backgroundFormatProblem(input.removeBackground, input.targetFormat) ??
    ratiosProblem(input.ratios ?? [])
  );
}

/** 单次批量上限。与后端 `ImageProcessRules.MAX_ASSETS_PER_JOB` 同一口径。 */
export const MAX_ASSETS_PER_JOB = 500;

export function assetsSizeProblem(count: number): string | null {
  if (count === 0) return '请至少选择一个要处理的素材';
  if (count > MAX_ASSETS_PER_JOB) return `单次最多处理 ${MAX_ASSETS_PER_JOB} 个素材`;
  return null;
}

// ------------------------------------------------------------ 展示格式化

/**
 * 进度百分比。分母为 0 时返回 0 而不是 NaN——
 * NaN 渲染到进度条上会变成空白，看起来像「进度丢了」。
 */
export function progressPercent(job: {
  assetCount: number;
  succeededCount: number;
  failedCount: number;
  skippedCount: number;
}): number {
  const total = job.assetCount;
  if (!total) return 0;
  const done = job.succeededCount + job.failedCount + job.skippedCount;
  return Math.round((done / total) * 100);
}

/** 「3 / 10 · 成功 2 · 失败 1」：把计数讲成一句人话。 */
export function progressText(job: {
  assetCount: number;
  succeededCount: number;
  failedCount: number;
  skippedCount: number;
}): string {
  const done = job.succeededCount + job.failedCount + job.skippedCount;
  const parts = [`${done} / ${job.assetCount}`];
  if (job.succeededCount) parts.push(`成功 ${job.succeededCount}`);
  if (job.failedCount) parts.push(`失败 ${job.failedCount}`);
  if (job.skippedCount) parts.push(`跳过 ${job.skippedCount}`);
  return parts.join(' · ');
}

/**
 * 产物尺寸。空值显示「未探测」而不是 0——与 media-model 的口径一致。
 */
export function outputDimensionText(
  width: number | null | undefined,
  height: number | null | undefined,
): string {
  if (width === null || width === undefined || height === null || height === undefined)
    return '未探测';
  return `${width} × ${height}`;
}

/**
 * 版本关系文案：把「原图始终保留」讲清楚。
 *
 * <p>产物落在新版本上（v1 → v2），原始的 v1 仍在版本历史里。
 * 不写清楚的话，运营看到 current_version 前进会以为原图被覆盖了。
 */
export function versionRelationText(fromVersion: number, toVersion: number): string {
  return `基于 v${fromVersion} 生成 → v${toVersion}（原图 v${fromVersion} 保留在版本历史里）`;
}

/** 配方的一句话摘要，让列表页不用展开就知道这套规格做了什么。 */
export function recipeSummary(recipe: {
  targetFormat: string;
  removeBackground: boolean;
  whiteBackground: boolean;
  watermarkText: string | null;
  ratios: { label: string }[] | null;
}): string {
  const parts: string[] = [];
  if (recipe.removeBackground) parts.push('去背景');
  if (recipe.whiteBackground) parts.push('白底');
  const text = (recipe.watermarkText ?? '').trim();
  if (text) parts.push(`水印「${text}」`);
  parts.push(`输出 ${recipe.targetFormat.toUpperCase()}`);
  const labels = (recipe.ratios ?? []).map((r) => r.label).join(' + ');
  if (labels) parts.push(`比例 ${labels}`);
  return parts.join(' · ');
}

// ------------------------------------------------------------ 空表单与文案

export function emptyRecipeInput(): MediaProcessRecipeInput {
  return {
    code: '',
    name: '',
    note: null,
    targetPlatform: 'AMAZON',
    targetFormat: 'png',
    removeBackground: false,
    backgroundTolerance: 12,
    whiteBackground: false,
    watermarkText: '',
    watermarkPosition: 'BOTTOM_RIGHT',
    watermarkOpacity: 30,
    targetWidthPx: null,
    targetHeightPx: null,
    cropMode: 'FIT',
    ratios: [],
    enabled: true,
    version: 0,
  };
}

/** 常见比例档的起始值：主图 1:1 + 缩略图（蓝图点名的那两个）。 */
export function defaultRatios() {
  return [
    { label: 'MAIN', widthPx: 1600, heightPx: 1600, cropMode: 'CROP_FILL' as const },
    { label: 'THUMBNAIL', widthPx: 200, heightPx: 200, cropMode: 'FIT' as const },
  ];
}

export function jobEmptyHint(filtered: boolean): string {
  return filtered
    ? '当前筛选条件下没有处理任务。先清掉筛选条件再看看。'
    : '还没有图片处理任务。先建一个处理配方（目标平台规格），再勾选素材提交处理。';
}

export function recipeEmptyHint(): string {
  return '还没有处理配方。配方是一套可复用的加工规格：去背景、白底、水印、裁剪尺寸、输出格式，以及要生成哪些比例档。';
}

/**
 * 提交后的提示。**必须**说明这是异步受理且原图保留——
 * 用户提交后如果不知道要等，也不知道原图没丢，行为会退化成「提交完就再传一遍」。
 */
export function submittedHint(job: MediaProcessJob): string {
  return `处理已受理，正在排队（${job.assetCount} 个素材）。处理在线程池里执行，进度会自动刷新；原图版本始终保留，产物作为新版本写入素材库。`;
}

/** 失败原因：后端给的是可执行的中文原因，这里只在没有原因时补一句兜底。 */
export function itemErrorText(item: { status: string; error: string | null }): string | null {
  if (item.error && item.error.trim()) return item.error;
  if (item.status === 'FAILED') return '处理失败，但后端没有给出原因。请重新提交一次，若仍失败请联系管理员。';
  if (item.status === 'CANCELLED') return '任务已取消，这个素材没有处理。';
  return null;
}

export function errorText(error: unknown, fallback = '图片处理数据加载失败') {
  if (error instanceof ApiError && error.message) return error.message;
  return fallback;
}

export type {
  MediaProcessJob,
  MediaProcessJobDetail,
  MediaProcessJobRow,
  MediaProcessRecipeRow,
};