import type {
  BomComponentInput,
  BomComponentRow,
  BomComponentsInput,
  BomDetail,
  BomEffectiveView,
  BomInput,
  BomOverview,
  BomPage,
  BomRequirement,
  BomRow,
  BomUsagePage,
  BomVersionDetail,
  BomVersionInput,
  BomVersionPage,
  BomVersionRow,
  BomVersionSummary,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/** BOM 列表筛选条件。字段与后端 `BomQuery` 逐字对应。 */
export type BomFilter = {
  keyword: string | null;
  status: string | null;
  page: number;
  size: number;
};

/** BOM 版本列表筛选条件。字段与后端 `BomVersionQuery` 逐字对应。 */
export type BomVersionFilter = {
  keyword: string | null;
  status: string | null;
  bomId: string | null;
  page: number;
  size: number;
};

/**
 * 3.5.2.1 BOM 版本管理前端模型。
 *
 * <p>这个页面最容易出错的三处：
 *
 * <ul>
 *   <li><b>损耗率是百分数，填 5 就是 5%。</b>界面上必须写「%」并且明确「5 = 5%，不是 500%」，
 *       因为存成比率的话一个填错的 5 会让采购量翻 6 倍而且不报错。
 *   <li><b>需求量是精确值，不是采购量。</b>后端给的是 6 位小数的需求量，<b>不取整</b>。
 *       页面上不要自作聪明四舍五入成整数 —— 取整是 3.5.2.2 与 06 的决定，
 *       而且结果区的「单层」提示必须留在屏幕上（子 BOM 里的料不在这里）。
 *   <li><b>「此刻生效的是哪一版」不是前端能重算的东西。</b>前端只显示后端给的
 *       {@code effectiveVersionNo}，不拿版本号大小去猜 ——「v10 比 v9 新」在区间语义下不成立。
 * </ul>
 */

/** 版本状态：与 V25 的 `bom_version.status` CHECK 和后端 `BomRules.STATUSES` 同一口径。 */
export const VERSION_STATUSES = [
  { value: 'DRAFT', label: '草稿', hint: '还没发布：可以随便改，改完发布后整版冻结' },
  { value: 'PUBLISHED', label: '已发布', hint: '发布后不可修改；08 加工单已按它领过料' },
] as const;

/** BOM 状态：与 V25 的 `bom.status` CHECK 同一口径。 */
export const BOM_STATUSES = [
  { value: 'ACTIVE', label: '启用', hint: '可以继续新建版本' },
  { value: 'ARCHIVED', label: '已归档', hint: '不再新建版本，历史版本照常可读（08 加工单要读）' },
] as const;

/**
 * 单个版本最多多少个组件。
 *
 * <p>必须与后端 `BomRules.MAX_COMPONENTS_PER_VERSION` 一致：这是**可用性**上限
 * （再多组装单领出来的料就没人核得对了），不是数据库限制。权威判定在后端。
 */
export const MAX_COMPONENTS_PER_VERSION = 50;

/** 单位用量与需求量的共同小数位数，与 `numeric(19,6)` 一致。 */
export const QUANTITY_SCALE = 6;

/** 损耗率的小数位数上限，与 `numeric(7,4)` 一致。 */
export const SCRAP_SCALE = 4;

/** 损耗率上界（百分数）。与后端 `BomRules.SCRAP_MAX` 及 V25 的 CHECK 同一口径。 */
export const SCRAP_MAX = 100;

/**
 * 损耗率单位的说明文案。
 *
 * <p>放在表单 label 上而不是 tooltip：这个字段填错是静默的（采购量翻 6 倍且不报错），
 * 藏在提示里的规则等于没有规则。
 */
export const SCRAP_UNIT_HINT =
  `单位是百分数：填 5 就是损耗 5%，不是 500%。上界 ${SCRAP_MAX}% —— ` +
  '100% 损耗等于这个组件一丁点都用不上、只需要补损耗，那不是一条有效的 BOM 行' +
  '（要表达废料请在成品上记，不该让组装单出现「领料 0 件」）。无损耗填 0。';

export function statusLabel(status: string): string {
  return VERSION_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export function statusHint(status: string): string {
  return VERSION_STATUSES.find((s) => s.value === status)?.hint ?? '';
}

/**
 * 版本状态的视觉调子。与 {@code @starcon/ui} 的 Badge tone 词表对齐
 * （success / warning / neutral），不要自造 'muted' / 'default'。
 */
export function statusTone(status: string): 'success' | 'warning' | 'neutral' {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'DRAFT') return 'warning';
  return 'neutral';
}

export function bomStatusTone(status: string): 'success' | 'neutral' {
  return status === 'ACTIVE' ? 'success' : 'neutral';
}

export function bomStatusLabel(status: string): string {
  return BOM_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/** 版本现在能不能改内容。已发布的一律 false —— 整版冻结，不是「部分字段」。 */
export function canEditVersion(row: Pick<BomVersionRow, 'status'>): boolean {
  return row.status === 'DRAFT';
}

/** 已发布版本上唯一还能做的事：把生效终点收紧到未来（提前停用本版）。 */
export function canCloseVersion(row: Pick<BomVersionRow, 'status'>): boolean {
  return row.status === 'PUBLISHED';
}

/**
 * 「提前停用本版」这一步要把话说全。
 *
 * <p>它是**唯一**合法的例外，而且只影响未来：终点必须落在当前时刻之后，
 * 且不能比原来的终点更晚。所以「已发布不可改」并不是死路。
 */
export function closeHint(row: Pick<BomVersionRow, 'versionNo' | 'effectiveTo'>): string {
  return (
    `提前停用「${row.versionNo}」：把生效终点收紧到当前时刻之后的一个时间点，` +
    '之后由下一个版本接替。收紧只能往前——落进过去等于追溯改写「那时该按哪一版算料」，' +
    '而 08 加工单领的料正是按当时的版本算的。'
  );
}

/** 发布后不可变的字段清单（界面上要逐条说清，而不是笼统一句「不能改」）。 */
export const FROZEN_FIELDS = ['BOM 名称', '版本号', '生效起点', '组件清单（用量与损耗率）', '备注'] as const;

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
// 这里的校验只为了「少一次往返」，权威判定在后端。逐条与后端 `BomRules` 同口径，
// 避免出现「前端放行、后端拒绝」或者反过来。

export function codeProblem(code: string): string | null {
  const value = code.trim();
  if (!value) return 'BOM 编码不能为空';
  if (value.length > 64) return 'BOM 编码最多 64 个字符';
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
  if (!value) return 'BOM 名称不能为空';
  if (value.length > 200) return 'BOM 名称最多 200 个字符';
  return null;
}

export function noteProblem(note: string): string | null {
  return note.length > 500 ? '备注最多 500 个字符' : null;
}

/** 单位用量：必须大于 0，小数位不超过 6（与 `numeric(19,6)` 一致）。 */
export function quantityProblem(raw: string): string | null {
  const value = raw.trim();
  if (!value) return '单位用量不能为空';
  const parsed = Number(value);
  if (!Number.isFinite(parsed))
    return '单位用量不是数字：「每 1 个成品要几件/几米」是生产用量，填错这一项会按假数领料';
  if (parsed <= 0)
    return '单位用量必须大于 0：用量为 0 的组件是「配了但不领」——组装单会领出一条 0 件，两边都答不出「这个组件到底要不要」';
  const [, fraction = ''] = value.split('.');
  if (fraction.length > QUANTITY_SCALE) return `单位用量最多 ${QUANTITY_SCALE} 位小数`;
  return null;
}

/**
 * 损耗率：百分数，0–100，小数位 ≤ 4（与 `numeric(7,4)` + CHECK 同口径）。
 *
 * <p>空串合法（提交时按 0 处理）：委外加工里「这个组件不损耗」是绝大多数情况，
 * 必填只会让人填一堆 0。
 */
export function scrapRateProblem(raw: string): string | null {
  const value = raw.trim();
  if (!value) return null;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return `损耗率不是数字：请填百分数（5 = 5%），不要填 ${value}`;
  if (parsed < 0) return '损耗率不能是负数';
  if (parsed > SCRAP_MAX)
    return `损耗率不能超过 ${SCRAP_MAX}%：100% 损耗等于这个组件一丁点都用不上、只需要补损耗，那不是一条有效的 BOM 行`;
  const [, fraction = ''] = value.split('.');
  if (fraction.length > SCRAP_SCALE) return `损耗率最多 ${SCRAP_SCALE} 位小数`;
  return null;
}

/** 组件清单规模。至少 1 个（发布的前提），至多 50 个（可用性上限）。 */
export function componentCountProblem(count: number): string | null {
  if (count === 0)
    return '一个 BOM 至少要有一个组件：空组件的 BOM 算不出任何需求量，08 组装单按它领不出任何料';
  if (count > MAX_COMPONENTS_PER_VERSION)
    return `一个 BOM 最多 ${MAX_COMPONENTS_PER_VERSION} 个组件（再多组装单领出来的料就没人核得对了）`;
  return null;
}

/** 提交前一次性校验组件清单，返回第一条问题（没有则 null）。 */
export function componentsProblem(
  components: { skuId: string; unitQuantity: string; scrapRate: string }[],
): string | null {
  const count = componentCountProblem(components.length);
  if (count) return count;
  for (const c of components) {
    const problem = quantityProblem(c.unitQuantity) ?? scrapRateProblem(c.scrapRate);
    if (problem) return problem;
  }
  const seen = new Set<string>();
  for (const c of components) {
    if (seen.has(c.skuId))
      return '同一个 SKU 在这份组件清单里出现了两次：每个组件「每 1 个成品要多少」只能有一个答案';
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

/** 要生产的成品数量：正整数（生产计划按件下达，0.5 个成品在仓库里不存在）。 */
export function outputQuantityProblem(raw: string): string | null {
  const value = raw.trim();
  if (!value) return '请填写要生产的成品数量';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return '成品数量不是数字';
  if (!Number.isInteger(parsed))
    return '成品数量必须是整数：生产计划按件下达，0.5 个成品在仓库里不存在';
  if (parsed <= 0)
    return '成品数量必须大于 0：产 0 个时每个组件的需求量都是 0，清单会被误读成「这个 BOM 什么料都不用」';
  return null;
}

// ================================================================ 读模型

export async function fetchBomOverview(): Promise<BomOverview> {
  return api<BomOverview>('catalog/boms/overview');
}

export async function fetchBoms(filter: BomFilter): Promise<BomPage> {
  const query = new URLSearchParams();
  if (filter.keyword) query.set('keyword', filter.keyword);
  if (filter.status) query.set('status', filter.status);
  query.set('page', String(filter.page));
  query.set('size', String(filter.size));
  return api<BomPage>(`catalog/boms?${query.toString()}`);
}

export async function fetchBom(id: string): Promise<BomDetail> {
  return api<BomDetail>(`catalog/boms/${id}`);
}

/** 当前生效版本：06 采购建议与 08 加工单取版本的形状。不传 at 就是查「此刻」。 */
export async function fetchEffectiveVersion(bomId: string, at?: string): Promise<BomEffectiveView> {
  const query = at ? `?at=${encodeURIComponent(at)}` : '';
  return api<BomEffectiveView>(`catalog/boms/${bomId}/effective${query}`);
}

export async function fetchVersions(filter: BomVersionFilter): Promise<BomVersionPage> {
  const query = new URLSearchParams();
  if (filter.keyword) query.set('keyword', filter.keyword);
  if (filter.status) query.set('status', filter.status);
  if (filter.bomId) query.set('bomId', filter.bomId);
  query.set('page', String(filter.page));
  query.set('size', String(filter.size));
  return api<BomVersionPage>(`catalog/bom-versions?${query.toString()}`);
}

export async function fetchVersion(id: string): Promise<BomVersionDetail> {
  return api<BomVersionDetail>(`catalog/bom-versions/${id}`);
}

/** 单层需求量。结果是精确值不取整，`singleLevel` 决定要不要显示「不含子 BOM 里的料」。 */
export async function fetchRequirement(versionId: string, outputQuantity: string): Promise<BomRequirement> {
  return api<BomRequirement>(
    `catalog/bom-versions/${versionId}/requirement?outputQuantity=${encodeURIComponent(outputQuantity)}`,
  );
}

/** 倒排：某个 SKU 被哪些 BOM 版本当组件用着。 */
export async function fetchUsage(
  skuId: string,
  options: { keyword?: string | null; onlyEffective?: boolean; page?: number; size?: number } = {},
): Promise<BomUsagePage> {
  const query = new URLSearchParams();
  query.set('skuId', skuId);
  if (options.keyword) query.set('keyword', options.keyword);
  if (options.onlyEffective) query.set('onlyEffective', 'true');
  query.set('page', String(options.page ?? 0));
  query.set('size', String(options.size ?? 20));
  return api<BomUsagePage>(`catalog/bom-components/usage?${query.toString()}`);
}

// ================================================================ 写模型

export async function saveBom(id: string | null, input: BomInput): Promise<BomRow> {
  return api<BomRow>(id ? `catalog/boms/${id}` : 'catalog/boms', id ? 'PUT' : 'POST', input);
}

export async function setBomStatus(id: string, status: string): Promise<BomRow> {
  return api<BomRow>(`catalog/boms/${id}/status?status=${encodeURIComponent(status)}`, 'PUT');
}

export async function deleteBom(id: string, version: number): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`catalog/boms/${id}?version=${version}`, 'DELETE');
}

export async function createVersion(bomId: string, input: BomVersionInput): Promise<BomVersionRow> {
  return api<BomVersionRow>(`catalog/boms/${bomId}/versions`, 'POST', input);
}

export async function updateVersion(id: string, input: BomVersionInput): Promise<BomVersionRow> {
  return api<BomVersionRow>(`catalog/bom-versions/${id}`, 'PUT', input);
}

export async function publishVersion(id: string): Promise<BomVersionRow> {
  return api<BomVersionRow>(`catalog/bom-versions/${id}/publish`, 'POST');
}

export async function closeVersion(id: string, effectiveTo: string): Promise<BomVersionRow> {
  return api<BomVersionRow>(
    `catalog/bom-versions/${id}/close?effectiveTo=${encodeURIComponent(effectiveTo)}`,
    'PUT',
  );
}

/** 版本复制：整份复制成新草稿（组件、用量、损耗率都带走）。 */
export async function copyVersion(
  id: string,
  versionNo: string,
  effectiveFrom?: string,
): Promise<BomVersionRow> {
  const query = new URLSearchParams();
  query.set('versionNo', versionNo);
  if (effectiveFrom) query.set('effectiveFrom', encodeURIComponent(effectiveFrom));
  return api<BomVersionRow>(`catalog/bom-versions/${id}/copy?${query.toString()}`, 'POST');
}

export async function deleteVersion(id: string, version: number): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`catalog/bom-versions/${id}?version=${version}`, 'DELETE');
}

/**
 * 整体替换组件清单。
 *
 * <p>整体替换而不是增删式：保存后清单就是提交的那份，不会剩一条「幽灵组件」。
 * 这是后端 {@code BomComponentsInput} 的语义，页面不要偷偷改成增删式。
 */
export async function replaceComponents(
  versionId: string,
  input: BomComponentsInput,
): Promise<BomVersionDetail> {
  return api<BomVersionDetail>(`catalog/bom-versions/${versionId}/components`, 'PUT', input);
}

// ================================================================ 表单与展示辅助

export function emptyBomInput(outputSkuId = '', version = 0): BomInput {
  return { code: '', outputSkuId, note: '', version };
}

export function emptyVersionInput(version = 0): BomVersionInput {
  return { versionNo: '', name: '', effectiveFrom: '', effectiveTo: null, note: '', version };
}

export function toVersionInput(row: BomVersionRow): BomVersionInput {
  return {
    versionNo: row.versionNo,
    name: row.name,
    effectiveFrom: instantToLocalInput(row.effectiveFrom),
    effectiveTo: row.effectiveTo ? instantToLocalInput(row.effectiveTo) : null,
    note: row.note,
    version: row.version,
  };
}

/** 编辑期的一条组件草稿。数量与损耗率用 string 持有用户输入（`0.`、`1e` 不是合法 number）。 */
export type ComponentDraft = { skuId: string; unitQuantity: string; scrapRate: string };

/** 把已保存的组件行转成编辑期草稿。 */
export function toComponentDraft(row: BomComponentRow): ComponentDraft {
  return {
    skuId: row.skuId,
    unitQuantity: String(row.unitQuantity),
    // 契约里 scrapRate 可空（后端缺省按 0 处理），空串与 0 提交效果相同。
    scrapRate: row.scrapRate == null ? '' : String(row.scrapRate),
  };
}

/** 把编辑期草稿转成契约要求的输入。空损耗率按 null 提交，后端存 0。 */
export function toComponentInput(draft: ComponentDraft): BomComponentInput {
  return {
    skuId: draft.skuId,
    unitQuantity: Number(draft.unitQuantity),
    scrapRate: draft.scrapRate.trim() === '' ? null : Number(draft.scrapRate),
    role: '',
    note: '',
  };
}

/** 空组件草稿：默认用量 1、损耗率留空（大多数组件不损耗，不必逼用户填 0）。 */
export function emptyComponentDraft(skuId = ''): ComponentDraft {
  return { skuId, unitQuantity: '1', scrapRate: '' };
}

/** 组件清单摘要。空清单要说出「发布时会被拒」，而不是画成「0 个组件」。 */
export function componentSummary(rows: BomComponentRow[], total: number | null): string {
  if (!rows.length)
    return '尚未填组件（发布时会被拒绝：空组件的 BOM 算不出任何需求量，08 组装单按它领不出任何料）';
  const maxScrap = rows.reduce((max, r) => Math.max(max, r.scrapRate ?? 0), 0);
  const scrap = maxScrap > 0 ? ` · 最高损耗 ${maxScrap}%` : ' · 无损耗';
  return `${rows.length} 个组件 · 每件合计 ${total ?? 0}${scrap}`;
}

/** 需求量的展示。后端给的是精确值，这里只补单位说明，不做取整。 */
export function requiredText(value: number): string {
  return `${value}`;
}

/** BOM 列表行上「此刻生效」的展示。没生效时要说清是「没生效」而不是「没有版本」。 */
export function effectiveLabel(row: Pick<BomRow, 'effectiveVersionNo' | 'versionCount'>): string {
  if (row.effectiveVersionNo) return `此刻生效：${row.effectiveVersionNo}`;
  if (row.versionCount > 0)
    return '此刻没有生效版本（只有草稿，或已发布版本的区间都还没到）—— 这一刻按它领不出料';
  return '还没有任何版本';
}

/** 倒排行上「这一行现在能不能改」的一句话。 */
export function usageEditableLabel(row: { versionNo: string; editable: boolean }): string {
  return row.editable ? `${row.versionNo} 草稿：可改` : `${row.versionNo} 已发布：已冻结，改不了`;
}

/** 错误文案：后端返回的是可执行的中文原因，直接显示，不要再包一层「操作失败」。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}

/** 版本摘要的兼容导出：类型别名让下拉复用时不必再从契约里引一次。 */
export type { BomVersionSummary };
