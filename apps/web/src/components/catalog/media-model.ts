import type {
  MediaDetail,
  MediaIntegrityCheck,
  MediaIntegrityResult,
  MediaIntegritySweep,
  MediaOverview,
  MediaPage,
  MediaReference,
  MediaReferenceSaved,
  MediaRow,
  MediaSaved,
  MediaTagInput,
  MediaTagRow,
  MediaTagSaved,
  MediaUpdate,
  MediaUpload,
  MediaVersion,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.4.1.1 媒体库前端模型。
 *
 * <p>这个页面上最容易出错的三处：
 *
 * <ul>
 *   <li>**元数据不能在前端算**。格式、宽高、字节数、哈希一律来自后端
 *       （`MediaRules.probe` 从字节探测）。前端要做的只有一件：把后端给的值显示出来。
 *       在浏览器里「读一下图片宽高再提交」看着更省事，但那等于让调用方能声称
 *       「一张 1KB 的图有 4000×3000」，而刊登侧要拿这个尺寸去校验平台规格。
 *   <li>**失效状态不能在前端推断**。`integrityStatus` 来自「真的把对象读回来算 SHA-256」
 *       的探测结果。前端只看状态码决定显示什么颜色，不自己判断「这个文件应该还在吧」。
 *   <li>**删除保护要让人先看见**。被引用的素材在列表行上就带引用计数，
 *       不点开详情也能知道它删不掉——否则用户会先点删除、再吃一个 400。
 * </ul>
 */

/** 媒体类型词表：与 V18 的 CHECK 和 `MediaRules.MEDIA_TYPES` 同一口径。 */
export const MEDIA_TYPES = [
  { value: 'IMAGE', label: '图片', hint: '支持 PNG / JPEG / GIF / BMP' },
  { value: 'VIDEO', label: '视频', hint: '支持 MP4 / MOV / WEBM / AVI' },
] as const;

/** 用途词表。刊登按用途取图，主图位不能塞详情图。 */
export const PURPOSES = [
  { value: 'PRIMARY', label: '主图', hint: '商品主图位，多数平台限 1 张' },
  { value: 'GALLERY', label: '附图', hint: '轮播 / 画廊图' },
  { value: 'DETAIL', label: '详情图', hint: '详情页长图' },
  { value: 'VIDEO', label: '视频', hint: '商品视频' },
  { value: 'OTHER', label: '其他', hint: '暂不确定用途的素材' },
] as const;

/** 完整性状态词表：与 V18 的 CHECK 和 `MediaRules.INTEGRITY_STATUSES` 同一口径。 */
export const INTEGRITY_STATUSES = [
  'UNKNOWN',
  'VERIFIED',
  'MISSING',
  'SIZE_MISMATCH',
  'CONTENT_MISMATCH',
] as const;

/**
 * 完整性状态 → 中文标签与「该做什么」。
 *
 * <p>四种坏法的处理动作不同，所以文案也不同：文件缺失要重传，大小/内容不符
 * 说明存储被改过，两者的处置完全不同。都写成「素材异常」的话运维看完不知道该做什么。
 */
export const INTEGRITY_LABELS: Record<string, string> = {
  UNKNOWN: '未校验',
  VERIFIED: '已校验',
  MISSING: '文件缺失',
  SIZE_MISMATCH: '大小不符',
  CONTENT_MISMATCH: '内容不符',
};

export const INTEGRITY_ACTIONS: Record<string, string> = {
  MISSING: '存储里已找不到这个文件，请重新上传一个新版本',
  SIZE_MISMATCH: '文件字节数与登记时不一致，存储可能被改写；请重新上传并核对',
  CONTENT_MISMATCH: '同尺寸但内容变了，存储可能被覆盖；请核对后重新上传',
};

export function mediaTypeLabel(value: string | null | undefined): string {
  return MEDIA_TYPES.find((item) => item.value === value)?.label ?? value ?? '未知';
}

export function purposeLabel(value: string | null | undefined): string {
  return PURPOSES.find((item) => item.value === value)?.label ?? value ?? '未知';
}

export function integrityLabel(status: string | null | undefined): string {
  if (!status) return '未校验';
  return INTEGRITY_LABELS[status] ?? status;
}

export function integrityAction(status: string | null | undefined): string | null {
  if (!status) return null;
  return INTEGRITY_ACTIONS[status] ?? null;
}

/** 完整性状态是否属于「有问题」的一类（与后端 `MediaRules.integrityFailing` 同口径）。 */
export function integrityFailing(status: string): boolean {
  return status === 'MISSING' || status === 'SIZE_MISMATCH' || status === 'CONTENT_MISMATCH';
}

/**
 * 完整性状态 → Badge 色调。
 *
 * <p>返回值直接用 `@starcon/ui` 的 Badge tone 词表（neutral / success / danger），
 * 不自造一套：自造的名字在这个组件上编译不过，而且改 UI 包时只会多一处要同步的地方。
 */
export function integrityTone(status: string): 'neutral' | 'success' | 'danger' | 'warning' {
  if (status === 'VERIFIED') return 'success';
  if (status === 'UNKNOWN') return 'neutral';
  return 'danger';
}

/** 列表页筛选项：引用状态三态。 */
export const REFERENCED_FILTERS = [
  { value: '', label: '引用状态：全部' },
  { value: 'referenced', label: '只看待清理（无引用）' },
  { value: 'used', label: '只看被引用的' },
] as const;

export type ReferencedFilter = '' | 'referenced' | 'used';

// ------------------------------------------------------------ 接口

export type MediaListQuery = {
  keyword?: string;
  mediaType?: string;
  purpose?: string;
  tagCode?: string;
  integrityStatus?: string;
  referenced: boolean | null;
  enabled: boolean | null;
  page: number;
  size: number;
};

export function fetchOverview() {
  return api<MediaOverview>('catalog/media/overview');
}

export function fetchMedia(query: MediaListQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.mediaType) params.set('mediaType', query.mediaType);
  if (query.purpose) params.set('purpose', query.purpose);
  if (query.tagCode) params.set('tagCode', query.tagCode);
  if (query.integrityStatus) params.set('integrityStatus', query.integrityStatus);
  if (query.referenced !== null) params.set('referenced', String(query.referenced));
  if (query.enabled !== null) params.set('enabled', String(query.enabled));
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<MediaPage>(`catalog/media?${params.toString()}`);
}

export function fetchDetail(id: string) {
  return api<MediaDetail>(`catalog/media/${id}`);
}

export function fetchVersions(id: string) {
  return api<MediaVersion[]>(`catalog/media/${id}/versions`);
}

export function fetchReferences(id: string) {
  return api<MediaReference[]>(`catalog/media/${id}/references`);
}

export function fetchIntegrityChecks(id: string) {
  return api<MediaIntegrityCheck[]>(`catalog/media/${id}/integrity-checks`);
}

export function fetchTags() {
  return api<MediaTagRow[]>('catalog/media/tags');
}

/**
 * 上传媒体。
 *
 * <p>走 FormData 而不是 JSON：文件本体是**字节**，base64 会让请求体膨胀 33%、
 * 在内存里多留一份副本，而素材库处理的恰恰是大文件。
 *
 * <p>刻意不走 `api()` 那个 helper：它无条件设 `Content-Type: application/json`，
 * 而 multipart 必须让浏览器自己带 boundary（手动设会把 boundary 丢掉，
 * 服务端解析不出文件部分）。所以这里直接 fetch 同源代理路径。
 */
export async function uploadMedia(file: File, input: MediaUpload): Promise<MediaSaved> {
  const form = new FormData();
  form.append('file', file);
  form.append('meta', new Blob([JSON.stringify(input)], { type: 'application/json' }));
  return multipart<MediaSaved>('catalog/media', form);
}

/** 追加一个新版本（旧版本永远保留：3.4.1.2 要求原图不被覆盖）。 */
export async function addVersion(id: string, file: File, note: string | null): Promise<MediaSaved> {
  const form = new FormData();
  form.append('file', file);
  form.append('meta', new Blob([JSON.stringify({ note })], { type: 'application/json' }));
  return multipart<MediaSaved>(`catalog/media/${id}/versions`, form);
}

/** POST 一个空体（校验类端点）。同样不能用 api()：它要求 JSON 头与 body 配对。 */
async function multipart<T>(path: string, form: FormData): Promise<T> {
  const response = await fetch(`/api/catalog/${path}`, { method: 'POST', body: form });
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
      body?.detail ?? body?.message ?? '操作失败，请检查输入后重试',
      result,
    );
  }
  return result as T;
}

/** 校验单个素材的存储完整性（后端真的把对象读回来算哈希）。 */
export function verifyAsset(id: string) {
  return api<MediaIntegrityResult>(`catalog/media/${id}/verify`, 'POST');
}

/** 批量巡检：单次最多 200 个。 */
export function verifySweep() {
  return api<MediaIntegritySweep>('catalog/media/verify-sweep', 'POST');
}

export function saveMedia(id: string, input: MediaUpdate) {
  return api<MediaSaved>(`catalog/media/${id}`, 'PUT', input);
}

export function setMediaEnabled(id: string, enabled: boolean) {
  return api<{ enabled: boolean }>(`catalog/media/${id}/enabled?enabled=${enabled}`, 'PUT');
}

export function deleteMedia(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/media/${id}?version=${version}`, 'DELETE');
}

export function addReference(
  id: string,
  input: { ownerType: string; spuId: string | null; skuId: string | null; note: string | null },
) {
  return api<MediaReferenceSaved>(`catalog/media/${id}/references`, 'POST', input);
}

export function removeReference(id: string, referenceId: string) {
  return api<MediaReferenceSaved>(`catalog/media/${id}/references/${referenceId}`, 'DELETE');
}

export function saveTag(tagId: string | null, input: MediaTagInput) {
  return tagId
    ? api<MediaTagSaved>(`catalog/media/tags/${tagId}`, 'PUT', input)
    : api<MediaTagSaved>('catalog/media/tags', 'POST', input);
}

export function deleteTag(tagId: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/media/tags/${tagId}?version=${version}`, 'DELETE');
}

export function setTagEnabled(tagId: string, enabled: boolean) {
  return api<{ enabled: boolean }>(`catalog/media/tags/${tagId}/enabled?enabled=${enabled}`, 'PUT');
}

// ------------------------------------------------------------ 校验（与服务层同口径）

export function nameProblem(value: string): string | null {
  const name = value.trim();
  if (!name) return '素材名称不能为空';
  if (name.length > 200) return '素材名称最多 200 个字符';
  return null;
}

export function mediaTypeProblem(value: string): string | null {
  if (!value.trim()) return '媒体类型不能为空';
  if (!MEDIA_TYPES.some((item) => item.value === value))
    return '媒体类型只能是 IMAGE（图片）或 VIDEO（视频）';
  return null;
}

export function purposeProblem(value: string): string | null {
  if (!value.trim()) return '用途不能为空';
  if (!PURPOSES.some((item) => item.value === value))
    return `用途只能是 ${PURPOSES.map((item) => item.value).join(' / ')}`;
  return null;
}

/** 标签编码校验。与 `MediaRules.tagCodeProblem` 同一套规则。 */
export function tagCodeProblem(value: string): string | null {
  const code = value.trim().toUpperCase();
  if (!code) return '标签编码不能为空';
  if (code.length > 32) return '标签编码最多 32 个字符';
  if (!/^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(code))
    return '标签编码请使用英文字母、数字、下划线或短横线，长度 1~32 位';
  return null;
}

/** 上传前的本地检查：只挡**必然**失败的，不重复后端已经挡了的事。 */
export function fileProblem(file: File | null): string | null {
  if (!file) return '请选择一个要上传的文件';
  if (file.size === 0) return '文件是空的，请重新选择';
  if (file.size > 50 * 1024 * 1024) return '文件超过 50 MiB 上限，请压缩后再上传';
  // 后端会按字节签名判定真实类型并拒绝谎报，这里提前给出可读提示，
  // 但**不**拿扩展名当判据——那正是后端要避免的错误。
  if (/\.webp$/i.test(file.name))
    return '服务端无法可靠读出 WEBP 的宽高，请先转成 PNG 或 JPEG 再上传';
  return null;
}

export function uploadProblem(input: MediaUpload): string | null {
  return (
    nameProblem(input.name) ??
    mediaTypeProblem(input.mediaType) ??
    purposeProblem(input.purpose) ??
    tagsProblem(input.tagCodes ?? [])
  );
}

export function tagsProblem(codes: (string | null)[]): string | null {
  if (codes.length > 50) return '单个素材最多挂 50 个标签';
  const seen = new Set<string>();
  for (const raw of codes) {
    if (raw === null) continue;
    const problem = tagCodeProblem(raw);
    if (problem) return problem;
    const code = raw.trim().toUpperCase();
    if (seen.has(code)) return `同一个标签不能重复挂载：${code}`;
    seen.add(code);
  }
  return null;
}

// ------------------------------------------------------------ 展示格式化

/** 字节数的人类可读形式。库里是 NUMERIC(19,6)，展示时才折算成 KB/MB/GB。 */
export function byteSizeText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

/**
 * 尺寸 / 时长的显示。
 *
 * <p>空值显示「未探测」而不是 0 或破折号当默认：视频宽高与时长是**没有探测**，
 * 显示成 0 会让运营以为「这个视频时长 0 秒」而做出错误判断。
 */
export function dimensionText(width: number | null | undefined, height: number | null | undefined) {
  if (width === null || width === undefined || height === null || height === undefined)
    return '未探测';
  return `${width} × ${height}`;
}

export function durationText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '未探测';
  return `${seconds} 秒`;
}

/** 空表单初值。 */
export function emptyUploadInput(): MediaUpload {
  return {
    name: '',
    mediaType: 'IMAGE',
    purpose: 'GALLERY',
    description: null,
    altText: null,
    tagCodes: [],
    enabled: true,
  };
}

/** 详情 → 编辑表单初值。 */
export function toMediaUpdate(detail: MediaDetail, tagCodes: string[]): MediaUpdate {
  return {
    name: detail.asset.name,
    purpose: detail.asset.purpose,
    description: detail.asset.description || null,
    altText: detail.asset.altText,
    tagCodes,
    enabled: detail.asset.enabled,
    version: detail.asset.version,
  };
}

export function emptyTagInput(): MediaTagInput {
  return { code: '', name: '', note: null, enabled: true, version: 0 };
}

/** 空态与筛选态的提示文案：两种空态文案不同，别混。 */
export function emptyHint(filtered: boolean) {
  return filtered
    ? '当前筛选条件下没有素材。先清掉筛选条件再看看——素材库是租户级的公共资产库。'
    : '这个租户还没有素材。上传第一张图片或视频后，刊登与批量上传就能复用它。';
}

/** 错误文案：后端给的是可执行的中文原因，这里只补上下文。 */
export function errorText(error: unknown, fallback = '素材数据加载失败') {
  if (error instanceof ApiError && error.message) return error.message;
  return fallback;
}

export type { MediaDetail, MediaIntegrityCheck, MediaRow, MediaTagRow, MediaVersion };
