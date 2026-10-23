import { api } from './api';
import type { UserPreferenceView } from '../../../../packages/api-contracts/src/index';

/**
 * 1.4.1.2 列表与表格偏好 —— 前端模型（纯函数 + 接口调用）。
 *
 * <p>蓝图：「记住各列表页的列显示、排序、分页大小、筛选条件，降低重复操作成本。
 * 输出：下次进入该页时恢复上次的视图状态。」以及硬约束「**偏好按页面 key 存储**，
 * 新增字段不影响旧偏好」。
 *
 * <p>本文件钉住四件最容易做错、而做错会**静默**的事：
 *
 * <ol>
 *   <li><b>缺键套默认值，而不是报错。</b>老偏好里没有新加的字段是常态
 *       （蓝图的硬约束）。缺 {@code columns} 用页面默认列，缺 {@code sortKey} 用默认排序，
 *       缺 {@code pageSize} 用默认条数 —— 任何一项都不能让页面崩掉。</li>
 *   <li><b>存回来的列必须是这一页认识的列。</b>否则旧版本留下的、或别处的 key
 *       会被当成可见列渲染，表格直接错位。</li>
 *   <li><b>「用户隐藏了某列」与「这一列当时还不存在」必须分得开。</b>
 *       所以载荷里除了 {@code columns}（可见列）还存 {@code columnSet}
 *       （保存那一刻这一页已知的全部列）：差集才是用户**显式隐藏**的，
 *       新加的列默认可见。</li>
 *   <li><b>自由文本不落库。</b>筛选条件里的自由文本（关键字、姓名…）可能含 PII，
 *       所以只有页面**显式声明**可持久化的键、且值是安全 token（枚举 / 编号 / 日期）时才会存。
 *       其余一律不存 —— 宁可少记，也不能把 PII 写进个人偏好表。</li>
 * </ol>
 */

/** 一列（key 稳定，label 只是界面上的标题）。 */
export interface ListColumn {
  key: string;
  label: string;
}

/** 排序方向。 */
export type SortDirection = 'ASC' | 'DESC';

/** 一页的偏好选项（页面自己声明「我有哪些列、哪些排序、哪些筛选可以记」）。 */
export interface ListPreferenceOptions {
  /** 页面 key：稳定的页面标识（小写字母 / 数字 / 点 / 下划线 / 连字符）。 */
  pageKey: string;
  /** 这一页的列定义。 */
  columns: ListColumn[];
  /**
   * 这一页支持的排序。
   *
   * <p>排序键是**页面自己定义的不透明 token**（如 {@code PACKED_AT_DESC}），
   * 方向已经编在 token 里 —— 因为后端的排序白名单本来就是这种形状
   * （有的维度只有降序，没有升序），拆成「字段 + 方向」反而会出现拼不出后端 token 的组合。
   */
  sortOptions?: ListColumn[];
  /** 默认每页条数。 */
  pageSize: number;
  /** 可选的每页条数；默认 {@link PAGE_SIZE_OPTIONS}。 */
  pageSizeOptions?: number[];
  /** 默认筛选条件。 */
  filters?: Record<string, string>;
  /**
   * 允许被持久化的筛选条件键（**白名单**）。
   *
   * <p>没在白名单里的键一律不落库 —— 自由文本筛选（关键字、姓名、备注）默认就在名单外。
   */
  persistableFilterKeys?: readonly string[];
}

/** 恢复出来的视图状态（页面直接用它渲染）。 */
export interface ListPreferenceState {
  /** 可见列（按显示顺序）。 */
  columns: string[];
  /** 排序键（页面定义的 token）；空串 = 不排序。 */
  sortKey: string;
  pageSize: number;
  /** 筛选条件（只含白名单里的键）。 */
  filters: Record<string, string>;
}

/** 可选的每页条数。 */
export const PAGE_SIZE_OPTIONS: readonly number[] = [10, 20, 50, 100];

/** 每页条数的取值范围（与服务端的 1..200 对齐）。 */
export const MIN_PAGE_SIZE = 5;
export const MAX_PAGE_SIZE = 200;

/** 安全 token：字母 / 数字 / 点 / 下划线 / 连字符 / 冒号 / 斜杠 / @（枚举、编号、日期、UUID）。 */
const SAFE_TOKEN = /^[A-Za-z0-9._:/@-]{0,64}$/;

/** 看起来像凭据的值（与服务端同一套口径，前端先拦一道，服务端再兜一道）。 */
const CREDENTIAL_VALUE =
  /-----BEGIN [A-Z ]*PRIVATE KEY-----|^bearer\s+\S+|^basic\s+\S+|^eyJ[A-Za-z0-9_-]{16,}\.\S*$|(password|passwd|pwd|token|secret|apikey)\s*=/i;

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

/**
 * 筛选条件的值是否**可以落库**。
 *
 * <p>只放行「安全 token」：枚举值、编号、日期、UUID。
 * 含空格、含中文、长文本一律不通过 —— 那些是自由文本，可能含 PII。
 */
export function isSafeFilterValue(value: unknown): boolean {
  if (typeof value === 'number') return Number.isFinite(value);
  if (typeof value === 'boolean') return true;
  if (!isString(value)) return false;
  const trimmed = value.trim();
  if (trimmed === '') return false;
  if (!SAFE_TOKEN.test(trimmed)) return false;
  return !CREDENTIAL_VALUE.test(trimmed);
}

/**
 * 从筛选条件里挑出**允许持久化**的部分。
 *
 * @param filters 当前筛选条件
 * @param allowedKeys 白名单（页面显式声明）
 */
export function pickPersistableFilters(
  filters: Record<string, string>,
  allowedKeys: readonly string[] = [],
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const key of allowedKeys) {
    const value = filters[key];
    if (value === undefined) continue;
    if (!isSafeFilterValue(value)) continue;
    out[key] = value.trim();
  }
  return out;
}

/**
 * 可见列的归一化。
 *
 * @param stored 存下来的可见列
 * @param storedSet 保存那一刻这一页已知的全部列（可能是旧载荷，没有这个键）
 * @param known 这一页**现在**知道的全部列
 */
export function normalizeColumns(
  stored: unknown,
  storedSet: unknown,
  known: readonly string[],
): string[] {
  // 根本没存过列 → 这一页的列全部可见
  if (!Array.isArray(stored)) return [...known];
  const visible = stored.filter(isString).filter((c) => known.includes(c));
  if (visible.length === 0) return [...known];
  const set = Array.isArray(storedSet) ? storedSet.filter(isString) : known;
  // 当时存在、现在也认识、却不在可见列表里 → 用户**显式隐藏**了它。
  // 不在 set 里的（当时还没有这一列）→ 新加的列，默认可见。
  const hidden = new Set(set.filter((c) => known.includes(c) && !visible.includes(c)));
  const ordered = [...visible];
  for (const column of known) {
    if (!hidden.has(column) && !ordered.includes(column)) ordered.push(column);
  }
  return ordered;
}

/**
 * 把存下来的载荷与页面默认值合并成一份可渲染的视图状态。
 *
 * <p><b>缺键不报错</b>：这是蓝图「新增字段不影响旧偏好」的落点。
 */
export function resolveListPreferences(
  stored: unknown,
  options: ListPreferenceOptions,
): ListPreferenceState {
  const payload = isPlainObject(stored) ? stored : {};
  const knownColumns = options.columns.map((c) => c.key);
  const knownSorts = (options.sortOptions ?? []).map((c) => c.key);
  const sizeOptions = options.pageSizeOptions ?? PAGE_SIZE_OPTIONS;

  const columns = normalizeColumns(payload.columns, payload.columnSet, knownColumns);

  const storedSortKey = isString(payload.sortKey) ? payload.sortKey : '';
  const sortKey =
    storedSortKey !== '' && knownSorts.includes(storedSortKey)
      ? storedSortKey
      : knownSorts[0] ?? '';

  const storedSize = payload.pageSize;
  const pageSize =
    typeof storedSize === 'number' && sizeOptions.includes(storedSize)
      ? storedSize
      : options.pageSize;

  const allowed = options.persistableFilterKeys ?? [];
  const storedFilters = isPlainObject(payload.filters) ? payload.filters : {};
  const filters: Record<string, string> = { ...(options.filters ?? {}) };
  for (const key of allowed) {
    const value = storedFilters[key];
    if (isString(value) && isSafeFilterValue(value)) filters[key] = value.trim();
  }

  return { columns, sortKey, pageSize, filters };
}

/**
 * 生成要落库的载荷。
 *
 * <p>只写**页面声明过**的东西：列（含保存时的已知集合）、排序、每页条数、白名单筛选条件。
 */
export function buildPreferencePayload(
  state: ListPreferenceState,
  options: ListPreferenceOptions,
): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    columns: [...state.columns],
    columnSet: options.columns.map((c) => c.key),
  };
  // 这一页没有可选的每页条数（不分页的清单）时**不写** pageSize：
  // 存一个这一页根本不认的值，下次读回来还是会被丢掉 —— 只是白占一行。
  if ((options.pageSizeOptions ?? PAGE_SIZE_OPTIONS).length > 0) {
    payload.pageSize = state.pageSize;
  }
  if (state.sortKey !== '') payload.sortKey = state.sortKey;
  const filters = pickPersistableFilters(state.filters, options.persistableFilterKeys ?? []);
  if (Object.keys(filters).length > 0) payload.filters = filters;
  return payload;
}

/**
 * 把载荷渲染成人能读的摘要（设置页用）。
 *
 * <p>刻意**不**把载荷原样 JSON.stringify 出来：设置页是给人看的，
 * 「列显示：出库单、仓库、时限」比一坨 JSON 有用得多。
 */
export function describePreference(preference: unknown): string[] {
  if (!isPlainObject(preference)) return [];
  const lines: string[] = [];
  if (Array.isArray(preference.columns)) {
    const columns = preference.columns.filter(isString);
    lines.push(`列显示：${columns.length > 0 ? columns.join('、') : '（全部隐藏）'}`);
  }
  if (isString(preference.sortKey) && preference.sortKey !== '') {
    lines.push(`排序：${preference.sortKey}`);
  }
  if (typeof preference.pageSize === 'number') {
    lines.push(`每页：${preference.pageSize} 条`);
  }
  if (isPlainObject(preference.filters)) {
    const entries = Object.entries(preference.filters);
    if (entries.length > 0) {
      lines.push(`筛选：${entries.map(([key, value]) => `${key}=${String(value)}`).join('、')}`);
    }
  }
  return lines;
}

// ==========================================================================
// 接口调用
// ==========================================================================
/** 偏好读取的返回形状（与后端 `UserPreferenceView` 一致）。 */
export type { UserPreferenceView };

function path(pageKey: string): string {
  return `identity/user-preferences/${encodeURIComponent(pageKey)}`;
}

/**
 * 读一个页面 key 的偏好。
 *
 * <p>后端在**没存过**时返回 200 + {@code stored=false}（不是 404）——
 * 「还没保存过偏好」是列表页的常态，不该被当成异常。
 */
export function fetchListPreference(pageKey: string): Promise<UserPreferenceView> {
  return api<UserPreferenceView>(path(pageKey));
}

/** 列出我全部页面的偏好（设置页用）。 */
export function fetchListPreferences(): Promise<{ entries: UserPreferenceView[] }> {
  return api<{ entries: UserPreferenceView[] }>('identity/user-preferences');
}

/** 保存（upsert）一个页面 key 的偏好。 */
export function saveListPreference(
  pageKey: string,
  preference: Record<string, unknown>,
): Promise<UserPreferenceView> {
  return api<UserPreferenceView>(path(pageKey), 'PUT', { preference });
}

/** 重置一个页面 key 的偏好（删掉那一行 = 回到默认值）。 */
export function resetListPreference(pageKey: string): Promise<unknown> {
  return api<unknown>(path(pageKey), 'DELETE');
}

/** 重置我全部页面的偏好。 */
export function resetAllListPreferences(): Promise<unknown> {
  return api<unknown>('identity/user-preferences', 'DELETE');
}
