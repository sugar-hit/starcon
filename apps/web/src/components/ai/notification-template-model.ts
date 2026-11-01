import type {
  NotifyTemplateCapabilities,
  NotifyTemplateContentAssetView,
  NotifyTemplateDetail,
  NotifyTemplateInput,
  NotifyTemplateMetadataInput,
  NotifyTemplateOverview,
  NotifyTemplatePage,
  NotifyTemplateRenderInput,
  NotifyTemplateRenderResult,
  NotifyTemplateResolved,
  NotifyTemplateRow,
  NotifyTemplateVariableDefinition,
  NotifyTemplateVersionRow,
  NotifyTemplateVersionDetail,
  NotifyTemplateVersionInput,
  NotifyTemplateVersionPage,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 18.5.1.1「模板管理」前端模型。
 *
 * 蓝图：「管理通知与消息模板：模板内容、变量、适用场景、语言版本、启用状态；修改后新产生的通知使用新版本，
 * 历史通知保留当时内容。输入：模板编辑；变量定义。输出：模板记录 + 渲染预览。关系：上游 = 3.4.2 文案资产；
 * 下游 = 1.3.1.1、15.1.1.2、18.5.2。**模板必须版本化**，已发出的通知不能被新模板"篡改"。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「编辑」不是「改内容」**：编辑 = **新增一个版本**。页面上没有「保存并覆盖」这条路 ——
 *    覆盖了就没法解释「那条旧通知当时写的是什么」。
 * 2. **渲染失败要说清缺哪个变量**，不能只显示一段空白。预览接口返回 400 时**带结构化清单**，
 *    这里原样透出来（`errors[]` 里每条都带变量名），而不是把错误吞成一句「预览失败」。
 * 3. **历史通知保留当时内容**：版本历史里能翻到每一版的内容与生效区间，
 *    「按时刻解析」能取到**那一刻生效的那一版**。
 * 4. **上游读不到 ≠ 没有**：3.4.2 文案资产读不到时如实显示「读不到」，不显示成「暂无」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），前端**不手抄**字符串联合。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/ai/templates';

/** 模板列表的筛选条件。 */
export interface TemplateListParams {
  keyword?: string | undefined;
  scene?: string | undefined;
  language?: string | undefined;
  enabled?: boolean | undefined;
  /** 归档状态：ACTIVE（未归档，默认）/ ARCHIVED / ALL。 */
  status?: string | undefined;
}

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 *
 * <p>布尔值要显式转成 {@code 'true' / 'false'} —— {@code undefined} 会被拼成字面量
 * {@code "undefined"}，上游就会当成「筛选了这个值」。
 */
function query(params: Record<string, string | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, typeof value === 'boolean' ? String(value) : value);
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：场景词表、语言写法、变量约定、边界。 */
export function fetchCapabilities(): Promise<NotifyTemplateCapabilities> {
  return api<NotifyTemplateCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<NotifyTemplateOverview> {
  return api<NotifyTemplateOverview>(`${base}/overview`);
}

/** 模板列表（真分页 + 真筛选）。 */
export function fetchTemplates(
  page: number,
  size: number,
  params: TemplateListParams,
): Promise<NotifyTemplatePage> {
  return api<NotifyTemplatePage>(
    `${base}?${query({ ...params, page: String(page), size: String(size) })}`,
  );
}

/** 模板详情（主记录 + 版本列表 + 当前生效版本）。 */
export function fetchTemplateDetail(templateId: string): Promise<NotifyTemplateDetail> {
  return api<NotifyTemplateDetail>(`${base}/${templateId}`);
}

/** 版本列表（真分页）。 */
export function fetchVersions(
  templateId: string,
  page: number,
  size: number,
): Promise<NotifyTemplateVersionPage> {
  return api<NotifyTemplateVersionPage>(
    `${base}/${templateId}/versions?${query({ page: String(page), size: String(size) })}`,
  );
}

/** 版本详情。 */
export function fetchVersionDetail(
  templateId: string,
  versionId: string,
): Promise<NotifyTemplateVersionDetail> {
  return api<NotifyTemplateVersionDetail>(`${base}/${templateId}/versions/${versionId}`);
}

/**
 * **按时刻**解析当时生效的那一版。
 *
 * <p>🔴 下游要引用的是「版本 id + 内容快照」，不是「当前版本」。
 */
export function resolveTemplate(
  templateKey: string,
  scene: string,
  language: string,
  at: string,
): Promise<NotifyTemplateResolved> {
  return api<NotifyTemplateResolved>(
    `${base}/resolve?${query({ templateKey, scene, language, at })}`,
  );
}

/** 上游文案资产（3.4.2 标题库 / 描述与卖点库）。 */
export function fetchContentAssets(): Promise<NotifyTemplateContentAssetView> {
  return api<NotifyTemplateContentAssetView>(`${base}/upstream/content-assets`);
}

// ================================================================ 写入

/** 新建模板（同时落下第 1 版内容；不自动启用）。 */
export function createTemplate(input: NotifyTemplateInput): Promise<NotifyTemplateRow> {
  return api<NotifyTemplateRow>(`${base}`, 'POST', input);
}

/**
 * 新增版本（= 编辑）。
 *
 * <p>🔴 **只追加**：老版本一个字节都不动。
 */
export function appendVersion(
  templateId: string,
  input: NotifyTemplateVersionInput,
): Promise<NotifyTemplateVersionDetail> {
  return api<NotifyTemplateVersionDetail>(`${base}/${templateId}/versions`, 'POST', input);
}

/** 改名称与说明（内容改不了 —— 内容只能新增版本）。 */
export function updateMetadata(
  templateId: string,
  input: NotifyTemplateMetadataInput,
): Promise<NotifyTemplateRow> {
  return api<NotifyTemplateRow>(`${base}/${templateId}/metadata`, 'POST', input);
}

/** 启用某个版本。 */
export function enableVersion(
  templateId: string,
  versionId: string,
): Promise<NotifyTemplateDetail> {
  return api<NotifyTemplateDetail>(`${base}/${templateId}/versions/${versionId}/enable`, 'POST');
}

/** 停用某个版本（只收口生效区间，版本行不动）。 */
export function disableVersion(
  templateId: string,
  versionId: string,
): Promise<NotifyTemplateDetail> {
  return api<NotifyTemplateDetail>(`${base}/${templateId}/versions/${versionId}/disable`, 'POST');
}

/**
 * 删除模板 = **停用 + 归档**（**不物理删**）。
 *
 * <p>🔴 版本行与启用历史原样留着：已经发出的通知要能按「当时的内容」解释清楚。
 */
export function archiveTemplate(templateId: string): Promise<NotifyTemplateRow> {
  return api<NotifyTemplateRow>(`${base}/${templateId}`, 'DELETE');
}

/**
 * 渲染预览（**不落库**）。
 *
 * <p>🔴 后端渲染失败时返回 **400 + 结构化清单**（每条错误带变量名）。
 * 这里把它**原样转成一次正常的返回**（`ok=false` + `errors[]`），
 * 免得页面只显示一句「预览失败」—— 那正是这个模块最不该丢的信息。
 */
export async function renderPreview(
  input: NotifyTemplateRenderInput,
): Promise<NotifyTemplateRenderResult> {
  try {
    return await api<NotifyTemplateRenderResult>(`${base}/render-preview`, 'POST', input);
  } catch (error) {
    const result = readRenderResult(error);
    if (result) return result;
    throw error;
  }
}

/** 从错误里把渲染结果捞出来（后端 400 的响应体就是渲染结果本身）。 */
export function readRenderResult(error: unknown): NotifyTemplateRenderResult | null {
  if (!(error instanceof ApiError)) return null;
  const body = error.body as Partial<NotifyTemplateRenderResult> | null;
  if (!body || !Array.isArray(body.errors)) return null;
  return {
    ok: false,
    message: body.message ?? error.message,
    renderedSubject: null,
    renderedBody: null,
    variables: body.variables ?? [],
    errors: body.errors,
    templateId: body.templateId ?? null,
    versionId: body.versionId ?? null,
    templateKey: body.templateKey ?? null,
    language: body.language ?? null,
    versionNo: body.versionNo ?? null,
  };
}

// ================================================================ 展示口径

/** 语气档位。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/**
 * 当前生效的版本（**可能没有**）。
 *
 * <p>🔴 后端在「模板当前没有生效版本」时返回 {@code null}；而 codegen 生成的扁平契约里
 * {@code $ref} 属性的可空性**表达不出来**（{@code tools/codegen/catalog-contracts.py} 的
 * {@code ts()} 遇到 {@code $ref} 直接返回类型名，丢掉 {@code nullable}）——
 * 所以这里显式收一次窄，**不**去改 codegen 产物。
 */
export function enabledVersionOf(
  detail: NotifyTemplateDetail | null,
): NotifyTemplateVersionRow | null {
  if (!detail) return null;
  const candidate = detail.enabledVersion as NotifyTemplateVersionRow | null | undefined;
  return candidate ?? null;
}

/** 场景码 → 人话（词表来自后端的能力声明，前端不自己写一份）。 */
export function sceneLabel(capabilities: NotifyTemplateCapabilities | null, code: string): string {
  const found = capabilities?.conventions?.scenes?.find((s) => s.code === code);
  return found?.label ?? code;
}

/** 变量类型码 → 人话。 */
export function variableTypeLabel(
  capabilities: NotifyTemplateCapabilities | null,
  code: string,
): string {
  const found = capabilities?.conventions?.variableTypes?.find((t) => t.code === code);
  return found?.label ?? code;
}

/** 渲染错误码 → 人话。 */
export function renderErrorLabel(code: string): string {
  if (code === 'SYNTAX_ERROR') return '模板语法错误';
  if (code === 'MISSING_VARIABLE') return '缺少变量取值';
  if (code === 'UNKNOWN_VARIABLE') return '未声明的变量';
  if (code === 'EXTRA_VARIABLE') return '多余的取值';
  if (code === 'INVALID_VALUE') return '取值与类型不符';
  if (code === 'INVALID_DECLARATION') return '变量定义有误';
  return code;
}

/** 渲染错误的语气：语法错误与未声明的变量是硬伤。 */
export function renderErrorTone(code: string): Tone {
  if (code === 'SYNTAX_ERROR' || code === 'UNKNOWN_VARIABLE' || code === 'INVALID_DECLARATION') {
    return 'danger';
  }
  if (code === 'MISSING_VARIABLE' || code === 'INVALID_VALUE' || code === 'EXTRA_VARIABLE') {
    return 'warn';
  }
  return 'muted';
}

/** 模板启用状态的语气。 */
export function enabledTone(enabled: boolean): Tone {
  return enabled ? 'ok' : 'muted';
}

/** 一个版本的状态说明（生效中 / 已失效 / 未启用过）。 */
export function versionStateLabel(version: {
  active: boolean;
  activatedAt: string | null;
  deactivatedAt: string | null;
}): string {
  if (version.active) return '生效中';
  if (version.deactivatedAt) return '已失效';
  if (version.activatedAt) return '生效中';
  return '未启用';
}

/** 模板 key 的规范写法（小写机器名）。 */
export function normalizeTemplateKey(raw: string): string {
  return raw.trim().toLowerCase();
}

/** 语言规范化（语种小写、地区大写：zh-cn → zh-CN）。 */
export function normalizeLanguage(raw: string): string {
  const value = raw.trim();
  const parts = value.split('-');
  const head = parts[0]?.toLowerCase() ?? '';
  const tail = parts.slice(1).map((part) => {
    if (part.length === 2) return part.toUpperCase();
    if (part.length === 4) {
      return part.charAt(0).toUpperCase() + part.slice(1).toLowerCase();
    }
    return part.toLowerCase();
  });
  return [head, ...tail].filter(Boolean).join('-');
}

/**
 * 正文里用了哪些变量（页面据此提示「你还没声明这个变量」）。
 *
 * <p>🔴 与后端的解析口径一致：{@code {{变量名}}}，变量名只认字母 / 数字 / 下划线。
 */
export function placeholderNames(content: string): string[] {
  const names: string[] = [];
  const pattern = /\{\{([A-Za-z0-9_]+)\}\}/g;
  let match = pattern.exec(content);
  while (match !== null) {
    const name = match[1];
    if (name && !names.includes(name)) names.push(name);
    match = pattern.exec(content);
  }
  return names;
}

/**
 * 变量定义里没声明、正文里却用了的变量（**前端先说一遍**，后端也会拒收）。
 */
export function undeclaredVariables(
  content: string,
  definitions: NotifyTemplateVariableDefinition[],
): string[] {
  const declared = definitions.map((d) => d.name);
  return placeholderNames(content).filter((name) => !declared.includes(name));
}

/**
 * 已声明、但正文里没用的变量（**允许**，但值得提醒）。
 */
export function unusedVariables(
  content: string,
  definitions: NotifyTemplateVariableDefinition[],
): string[] {
  const used = placeholderNames(content);
  return definitions.map((d) => d.name).filter((name) => !used.includes(name));
}

/**
 * 渲染预览的取值表（按变量定义逐条列出，缺的那些留空给运营填）。
 */
export function valueInputs(
  definitions: NotifyTemplateVariableDefinition[],
  values: Record<string, string>,
): { name: string; type: string; value: string }[] {
  return definitions.map((definition) => ({
    name: definition.name,
    type: definition.type,
    value: values[definition.name] ?? '',
  }));
}

/** 时刻的展示（后端返回的是 ISO-8601 带偏移）。 */
export function formatMoment(iso: string | null | undefined): string {
  if (!iso) return '—';
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return iso;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}
