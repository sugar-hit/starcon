import type {
  BundleComponentInput,
  BundleComponentRow,
  BundleComponentsInput,
  BundleDetail,
  BundleEffectiveView,
  BundleInput,
  BundleOverview,
  BundlePage,
  BundleRow,
  BundleVersionDetail,
  BundleVersionInput,
  BundleVersionPage,
  BundleVersionRow,
  BundleVersionSummary,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/** 套装列表筛选条件。字段与后端 `BundleQuery` 逐字对应。 */
export type BundleFilter = {
  keyword: string | null;
  status: string | null;
  page: number;
  size: number;
};

/** 版本列表筛选条件。字段与后端 `BundleVersionQuery` 逐字对应。 */
export type BundleVersionFilter = {
  keyword: string | null;
  status: string | null;
  bundleId: string | null;
  page: number;
  size: number;
};

/**
 * 3.5.1.1 套装版本管理前端模型。
 *
 * <p>这个页面最容易出错的三处：
 *
 * <ul>
 *   <li><b>「已发布不可改」要在界面上就讲清楚，而不是让人试了再报错。</b>
 *       已发布的版本整版冻结（改名、改版本号、改生效起点、改组件一律拒绝），
 *       唯一还能做的是「把生效终点收紧到未来」—— 提前停用本版。
 *       所以 {@link canEditVersion} 与 {@link closeHint} 把这条讲成人话。
 *   <li><b>「此刻生效的是哪一版」不是一个可以前端重算的东西。</b>
 *       生效区间是左闭右开的 [起点, 终点)，重叠区间被后端拒绝。
 *       前端**只显示**后端给的 {@code effectiveVersionNo}，不拿版本号大小去猜
 *       ——「v10 比 v9 新」这种推断在区间语义下是不成立的。
 *   <li><b>「此刻没有生效版本」是真状态，不是「查不到」。</b>
 *       只有草稿、或已发布版本区间都还没到时就是没有。
 *       画成空列表会被读成「这个套装不存在内容」，而不是「这一刻不能卖它」。
 * </ul>
 */

/** 版本状态：与 V23 的 `bundle_version.status` CHECK 和后端 `BundleRules.STATUSES` 同一口径。 */
export const VERSION_STATUSES = [
  { value: 'DRAFT', label: '草稿', hint: '还没发布：可以随便改，改完发布后整版冻结' },
  { value: 'PUBLISHED', label: '已发布', hint: '发布后不可修改，历史订单冻结的就是这一版' },
] as const;

/** 套装状态：与 V23 的 `bundle.status` CHECK 同一口径。 */
export const BUNDLE_STATUSES = [
  { value: 'ACTIVE', label: '启用', hint: '可以继续新建版本' },
  { value: 'ARCHIVED', label: '已归档', hint: '不再新建版本，历史版本照常可读（订单要读）' },
] as const;

/**
 * 单个版本最多多少个组件。
 *
 * <p>必须与后端 `BundleRules.MAX_COMPONENTS_PER_VERSION` 一致：这是**可用性**上限
 * （再多订单展开出来的子行没人核得对），不是数据库限制。前端提前拦是为了省一次往返，
 * 但权威判定在后端。
 */
export const MAX_COMPONENTS_PER_VERSION = 50;

/** 组件数量的小数位数上限，与 `numeric(19,6)` 一致。 */
export const QUANTITY_SCALE = 6;

/** 发布后不可变的字段清单（界面上要逐条说清，而不是笼统一句「不能改」）。 */
export const FROZEN_FIELDS = [
  '套装名称',
  '版本号',
  '生效起点',
  '组件清单',
  '备注',
] as const;

export function statusLabel(status: string): string {
  return VERSION_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export function statusHint(status: string): string {
  return VERSION_STATUSES.find((s) => s.value === status)?.hint ?? '';
}

/**
 * 版本状态的视觉调子。与 {@code @starcon/ui} 的 Badge tone 词表对齐
 * （success / warning / neutral / info …），不要自造 'muted' / 'default'。
 */
export function statusTone(status: string): 'success' | 'warning' | 'neutral' {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'DRAFT') return 'warning';
  return 'neutral';
}

/** 套装状态的视觉调子。 */
export function bundleStatusTone(status: string): 'success' | 'neutral' {
  return status === 'ACTIVE' ? 'success' : 'neutral';
}

export function bundleStatusLabel(status: string): string {
  return BUNDLE_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/** 版本现在能不能改内容。已发布的一律 false —— 整版冻结，不是「部分字段」。 */
export function canEditVersion(row: Pick<BundleVersionRow, 'status'>): boolean {
  return row.status === 'DRAFT';
}

/** 已发布版本上唯一还能做的事：把生效终点收紧到未来（提前停用本版）。 */
export function canCloseVersion(row: Pick<BundleVersionRow, 'status'>): boolean {
  return row.status === 'PUBLISHED';
}

/**
 * 「提前停用本版」这一步要把话说全。
 *
 * <p>它是**唯一**合法的例外，而且只影响未来：终点必须落在当前时刻之后，
 * 且不能比原来的终点更晚。所以「已发布不可改」并不是死路。
 */
export function closeHint(row: Pick<BundleVersionRow, 'versionNo' | 'effectiveTo'>): string {
  return (
    `提前停用「${row.versionNo}」：把生效终点收紧到当前时刻之后的一个时间点，` +
    '之后由下一个版本接替。收紧只能往前——落进过去等于追溯改写「那时生效的是哪一版」，' +
    '而历史订单冻结的正是当时的版本。'
  );
}

/** 版本区间的可读写法。半开区间：[起点, 终点)，终点空 = 一直有效。 */
export function rangeText(from: string | null, to: string | null): string {
  const start = formatMoment(from);
  if (!to) return `${start} 起一直有效（[起点, +∞)）`;
  return `${start} → ${formatMoment(to)}（[起点, 终点)，终点那一瞬间归下一版）`;
}

function formatMoment(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ` +
    `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
  );
}

/**
 * 把 datetime-local 的值转成带时区的 ISO 串。
 *
 * <p>不转的话后端会拒（「不是合法的带时区时间」）；而如果按 UTC 直接拼，
 * 一个在东八区选了 18:00 的运营会得到 18:00Z = 次日 02:00 的生效起点。
 */
export function localInputToInstant(localValue: string): string {
  if (!localValue) return '';
  const parsed = new Date(localValue);
  if (Number.isNaN(parsed.getTime())) return '';
  return parsed.toISOString();
}

/** ISO 串转回 datetime-local 能显示的本地写法（用于回填编辑表单）。 */
export function instantToLocalInput(value: string | null): string {
  if (!value) return '';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}` +
    `T${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
  );
}

// ================================================================ 输入校验
//
// 这里的校验只为了「少一次往返」，权威判定在后端。逐条与后端 `BundleRules` 同口径，
// 避免出现「前端放行、后端拒绝」或者反过来。

export function codeProblem(code: string): string | null {
  const value = code.trim();
  if (!value) return '套装编码不能为空';
  if (value.length > 64) return '套装编码最多 64 个字符';
  return null;
}

export function versionNoProblem(versionNo: string): string | null {
  const value = versionNo.trim();
  if (!value) return '版本号不能为空';
  if (value.length > 32) return '版本号最多 32 个字符';
  return null;
}

export function versionNameProblem(name: string): string | null {
  const value = name.trim();
  if (!value) return '套装名称不能为空';
  if (value.length > 200) return '套装名称最多 200 个字符';
  return null;
}

export function noteProblem(note: string): string | null {
  return note.length > 500 ? '备注最多 500 个字符' : null;
}

/** 组件数量：必须大于 0，小数位不超过 6（与 `numeric(19,6)` 一致）。 */
export function quantityProblem(raw: string): string | null {
  const value = raw.trim();
  if (!value) return '组件数量不能为空';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return '组件数量不是数字';
  if (parsed <= 0)
    return '组件数量必须大于 0：数量为 0 的组件会让订单展开出一条 0 子行、库存扣出一条 0 扣减，两边都答不出「这个组件到底要不要」';
  const [, fraction = ''] = value.split('.');
  if (fraction.length > QUANTITY_SCALE) return `组件数量最多 ${QUANTITY_SCALE} 位小数`;
  return null;
}

/** 组件清单规模。至少 1 个（发布的前提），至多 50 个（可用性上限）。 */
export function componentCountProblem(count: number): string | null {
  if (count === 0)
    return '一个套装至少要有一个组件：空组件的套装在刊登上显示成能买的商品，展开到订单里却什么都不是';
  if (count > MAX_COMPONENTS_PER_VERSION)
    return `一个套装最多 ${MAX_COMPONENTS_PER_VERSION} 个组件（再多订单展开出来的子行就没人核得对了）`;
  return null;
}

/** 提交前一次性校验组件清单，返回第一条问题（没有则 null）。 */
export function componentsProblem(
  components: { skuId: string; quantity: string }[],
): string | null {
  const count = componentCountProblem(components.length);
  if (count) return count;
  for (const c of components) {
    const problem = quantityProblem(c.quantity);
    if (problem) return problem;
  }
  const seen = new Set<string>();
  for (const c of components) {
    if (seen.has(c.skuId))
      return '同一个 SKU 在这份组件清单里出现了两次：每个组件「每件套装要几件」只能有一个答案';
    seen.add(c.skuId);
  }
  if (components.some((c) => !c.skuId)) return '每个组件都要选一个 SKU';
  return null;
}

/** 生效区间顺序（半开区间：终点必须严格晚于起点）。 */
export function rangeOrderProblem(from: string, to: string): string | null {
  if (!from) return '生效起点不能为空';
  if (!to) return null;
  const start = new Date(from).getTime();
  const end = new Date(to).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) return '生效区间不是合法的时间';
  if (end <= start)
    return '生效终点必须晚于生效起点：区间是左闭右开的 [起点, 终点)，终点等于起点等于「从不生效」';
  return null;
}

/** 「提前停用」的本地预判，终点为空表示「一直有效」，不合法。 */
export function closeToProblem(localValue: string, currentTo: string | null): string | null {
  if (!localValue) return '关闭生效区间要指定一个具体时刻：留空等于重新变成「一直有效」';
  const end = new Date(localValue).getTime();
  if (Number.isNaN(end)) return '生效终点不是合法的时间';
  if (end <= Date.now()) return '生效终点必须落在当前时刻之后：那会追溯改写历史上生效的是哪一版';
  if (currentTo) {
    const current = new Date(currentTo).getTime();
    if (end > current) return '生效终点只能收紧不能往后延：往后延会与已经接替它的版本重叠';
  }
  return null;
}

// ================================================================ 读模型

export async function fetchBundleOverview(): Promise<BundleOverview> {
  return api<BundleOverview>('catalog/bundles/overview');
}

export async function fetchBundles(filter: BundleFilter): Promise<BundlePage> {
  const query = new URLSearchParams();
  if (filter.keyword) query.set('keyword', filter.keyword);
  if (filter.status) query.set('status', filter.status);
  query.set('page', String(filter.page));
  query.set('size', String(filter.size));
  return api<BundlePage>(`catalog/bundles?${query.toString()}`);
}

export async function fetchBundle(id: string): Promise<BundleDetail> {
  return api<BundleDetail>(`catalog/bundles/${id}`);
}

/** 当前生效版本：这是 04 刊登与 09 订单取版本的形状。不传 at 就是查「此刻」。 */
export async function fetchEffectiveVersion(bundleId: string, at?: string): Promise<BundleEffectiveView> {
  const query = at ? `?at=${encodeURIComponent(at)}` : '';
  return api<BundleEffectiveView>(`catalog/bundles/${bundleId}/effective${query}`);
}

export async function fetchVersions(filter: BundleVersionFilter): Promise<BundleVersionPage> {
  const query = new URLSearchParams();
  if (filter.keyword) query.set('keyword', filter.keyword);
  if (filter.status) query.set('status', filter.status);
  if (filter.bundleId) query.set('bundleId', filter.bundleId);
  query.set('page', String(filter.page));
  query.set('size', String(filter.size));
  return api<BundleVersionPage>(`catalog/versions?${query.toString()}`);
}

export async function fetchVersion(id: string): Promise<BundleVersionDetail> {
  return api<BundleVersionDetail>(`catalog/versions/${id}`);
}

export async function saveBundle(
  id: string | null,
  input: BundleInput,
): Promise<BundleRow> {
  return api<BundleRow>(id ? `catalog/bundles/${id}` : 'catalog/bundles', id ? 'PUT' : 'POST', input);
}

export async function setBundleStatus(id: string, status: string): Promise<BundleRow> {
  return api<BundleRow>(`catalog/bundles/${id}/status?status=${encodeURIComponent(status)}`, 'PUT');
}

export async function deleteBundle(id: string, version: number): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`catalog/bundles/${id}?version=${version}`, 'DELETE');
}

export async function createVersion(
  bundleId: string,
  input: BundleVersionInput,
): Promise<BundleVersionRow> {
  return api<BundleVersionRow>(`catalog/bundles/${bundleId}/versions`, 'POST', input);
}

export async function updateVersion(id: string, input: BundleVersionInput): Promise<BundleVersionRow> {
  return api<BundleVersionRow>(`catalog/versions/${id}`, 'PUT', input);
}

export async function publishVersion(id: string): Promise<BundleVersionRow> {
  return api<BundleVersionRow>(`catalog/versions/${id}/publish`, 'POST');
}

export async function closeVersion(id: string, effectiveTo: string): Promise<BundleVersionRow> {
  return api<BundleVersionRow>(
    `catalog/versions/${id}/close?effectiveTo=${encodeURIComponent(effectiveTo)}`,
    'PUT',
  );
}

export async function deleteVersion(id: string, version: number): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`catalog/versions/${id}?version=${version}`, 'DELETE');
}

/**
 * 整体替换组件清单。
 *
 * <p>整体替换而不是增删式：保存后清单就是提交的那份，不会剩一条「幽灵组件」。
 * 这是后端 {@code BundleComponentsInput} 的语义，页面不要偷偷改成增删式。
 */
export async function replaceComponents(
  versionId: string,
  input: BundleComponentsInput,
): Promise<BundleVersionDetail> {
  return api<BundleVersionDetail>(`catalog/versions/${versionId}/components`, 'PUT', input);
}

// ================================================================ 表单与展示辅助

export function emptyBundleInput(version = 0): BundleInput {
  return { code: '', note: '', version };
}

export function emptyVersionInput(version = 0): BundleVersionInput {
  return { versionNo: '', name: '', effectiveFrom: '', effectiveTo: null, note: '', version };
}

export function toVersionInput(row: BundleVersionRow): BundleVersionInput {
  return {
    versionNo: row.versionNo,
    name: row.name,
    effectiveFrom: instantToLocalInput(row.effectiveFrom),
    effectiveTo: row.effectiveTo ? instantToLocalInput(row.effectiveTo) : null,
    note: row.note,
    version: row.version,
  };
}

/**
 * 组件数量在契约里是 number（后端 BigDecimal + numeric(19,6)）。
 *
 * <p>编辑期用 string 持有用户输入（`0.`、`1e` 这类中间态不是合法 number），
 * 提交前才转成 number —— 所以这个函数只用于回填「已保存的值」，
 * 正在编辑的输入不走它。
 */
export function toComponentInput(row: BundleComponentRow): BundleComponentInput {
  return { skuId: row.skuId, quantity: Number(row.quantity), role: row.role, note: row.note };
}

/** 把编辑期的字符串数量转成契约要求的 number。 */
export function toQuantityNumber(raw: string): number {
  return Number(raw);
}

/** 组件数量的合计，按后端返回的 totalQuantity 显示，不在 JS 里加一遍小数。 */
export function componentSummary(rows: BundleComponentRow[], total: number | null): string {
  const kinds = `${rows.length} 个组件`;
  if (!rows.length) return '尚未填组件（发布时会被拒绝：空组件的套装展开成订单行什么都不是）';
  return `${kinds} · 每件合计 ${total ?? 0}`;
}

/** 套装列表行上「此刻生效」的展示。没生效时要说清是「没生效」而不是「没有版本」。 */
export function effectiveLabel(row: Pick<BundleRow, 'effectiveVersionNo' | 'versionCount'>): string {
  if (row.effectiveVersionNo) return `此刻生效：${row.effectiveVersionNo}`;
  if (row.versionCount > 0)
    return '此刻没有生效版本（只有草稿，或已发布版本的区间都还没到）—— 这一刻不能卖它';
  return '还没有任何版本';
}

/** 错误文案：后端返回的是可执行的中文原因，直接显示，不要再包一层「操作失败」。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}