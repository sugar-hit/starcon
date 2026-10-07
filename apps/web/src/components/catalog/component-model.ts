import type {
  BundleComponentCheck,
  BundleComponentEditInput,
  BundleComponentInput,
  BundleComponentItem,
  BundleComponentOverview,
  BundleComponentPage,
  BundleComponentProblem,
  BundleSkuInput,
  BundleSkuPage,
  BundleSkuRow,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/** 组件清单筛选条件。字段与后端 `BundleComponentQuery` 逐字对应。 */
export type ComponentFilter = {
  keyword: string | null;
  versionId: string | null;
  bundleId: string | null;
  nested: string | null;
  page: number;
  size: number;
};

/** 指向列表筛选条件。字段与后端 `BundleSkuQuery` 逐字对应。 */
export type LinkFilter = {
  keyword: string | null;
  page: number;
  size: number;
};

/**
 * 3.5.1.2 组件维护前端模型。
 *
 * <p>这个工作台最容易出错的三处：
 *
 * <ul>
 *   <li><b>「已发布版本的组件不可改」要在界面上就讲清楚，而不是让人试了再报错。</b>
 *       蓝图原话：「已发布版本的组件不可改，只能新建版本 —— 历史订单冻结的是当时版本。」
 *       所以 {@link canEditComponent} 与 {@link frozenHint} 把这条讲成人话。
 *   <li><b>校验结果要一次看全，而不是让人反复提交试错。</b>
 *       蓝图的输出写的是「校验结果（循环、数量非法、组件不存在均拒绝保存）」，
 *       运营的下一个动作是「一次改完」。所以问题按 {@link PROBLEM_CODES} 分组显示，
 *       而不是只显示后端返回的第一条。
 *   <li><b>嵌套组件必须一眼看出来。</b>
 *       组件 SKU 本身代表另一个套装时（{@code BundleComponentItem.nested}），
 *       展开会继续往下走一层 —— 那是循环检测存在的理由，不标出来就没人知道为什么要防它。
 * </ul>
 */

/** 单个版本最多多少个组件，与后端 `BundleRules.MAX_COMPONENTS_PER_VERSION` 一致。 */
export const MAX_COMPONENTS_PER_VERSION = 50;

/** 组件数量的小数位数上限，与 `numeric(19,6)` 一致。 */
export const QUANTITY_SCALE = 6;

/**
 * 校验问题码 → 展示口径。
 *
 * <p>顺序就是展示顺序：先说「整版不能动」这种结构性问题，再说具体某一行。
 * code 由后端给（稳定机器可读），文案在前端排版时用。
 */
export const PROBLEM_CODES = [
  {
    code: 'VERSION_FROZEN',
    label: '整版已冻结',
    hint: '已发布版本的组件不可增删改，只能新建一个版本并让它接替本版',
  },
  {
    code: 'CIRCULAR_REFERENCE',
    label: '循环引用',
    hint: '套装之间会闭合成环，09 订单展开时会在环上无限递归',
  },
  {
    code: 'QUANTITY_INVALID',
    label: '数量非法',
    hint: '数量必须大于 0，且小数位不超过 6（numeric(19,6)）',
  },
  {
    code: 'COMPONENT_SKU_MISSING',
    label: '组件不存在',
    hint: '组件必须指向本租户真实存在的 SKU',
  },
  {
    code: 'DUPLICATE_SKU',
    label: '组件重复',
    hint: '同一个 SKU 在一个版本里只能有一行，否则「每件要几件」无解',
  },
  {
    code: 'EMPTY_COMPONENT_LIST',
    label: '没有组件',
    hint: '一个套装至少要有一个组件，空清单展开成订单行什么都不是',
  },
] as const;

export function problemLabel(code: string): string {
  // PROBLEM_CODES 是 as const，code 的类型是字面量联合；这里收 string 是因为
  // 后端可能返回词表外的新码，所以显式按 string 查而不是让类型系统断言「一定在表内」。
  return PROBLEM_CODES.find((p) => p.code === code)?.label ?? code;
}

export function problemHint(code: string): string {
  return PROBLEM_CODES.find((p) => p.code === code)?.hint ?? '';
}

/** 问题的视觉调子：结构性/成环类是红，个别行的错是黄。 */
export function problemTone(code: string): 'danger' | 'warning' {
  if (code === 'VERSION_FROZEN' || code === 'CIRCULAR_REFERENCE' || code === 'EMPTY_COMPONENT_LIST') {
    return 'danger';
  }
  return 'warning';
}

// ================================================================ 可写性

/** 这一行现在能不能改。已发布版本的一律 false —— 整版冻结，不是「部分字段」。 */
export function canEditComponent(row: Pick<BundleComponentItem, 'editable'>): boolean {
  return row.editable;
}

/** 已发布版本上唯一能做的事：新建一个版本并让它接替本版。 */
export function frozenHint(row: Pick<BundleComponentItem, 'bundleCode' | 'versionNo'>): string {
  return (
    `套装「${row.bundleCode}」的版本 ${row.versionNo} 已发布，这一行不可改：` +
    '历史订单冻结的是当时展开的组件清单，改了会让「这单当时包含哪些组件」无从查证。' +
    '要改组件请新建一个版本并让它接替本版。'
  );
}

/** 嵌套组件的说明：这行不是终点，展开会继续往下走一层。 */
export function nestedHint(row: Pick<BundleComponentItem, 'nested' | 'linkedBundleCode'>): string {
  if (!row.nested) return '';
  return (
    `这个组件 SKU 代表套装「${row.linkedBundleCode}」，所以这行不是终点：` +
    '09 订单展开时会继续按那个套装的生效版本往下展开。' +
    '循环检测守的就是这一类边 —— 套装 A 含 B 且 B 含 A 会让展开在环上无限递归。'
  );
}

// ================================================================ 展示辅助

/** 组件行的版本归属标签。 */
export function componentOrigin(row: Pick<BundleComponentItem, 'bundleCode' | 'versionNo'>): string {
  return `${row.bundleCode} · ${row.versionNo}`;
}

/**
 * 组件数量的显示。
 *
 * <p>不在 JS 里对数量做加减：合计由后端用 BigDecimal 算好（{@code totalQuantity}），
 * 前端再算一遍小数会引入与后端不一致的舍入。
 */
export function quantityText(value: number | null): string {
  if (value === null || value === undefined) return '—';
  // 去掉尾随 0，但保留至少一位小数之外的原值（0.5 要显示成 0.5 而不是 0.5）。
  return String(value);
}

/** 清单区的一句总结。空清单要说清是「还没填」而不是「这个套装没有组件」。 */
export function componentSummary(
  rows: BundleComponentItem[],
  total: number | null,
  nestedCount: number,
): string {
  if (!rows.length) return '还没有任何组件：先从 SKU 里挑一个加进来';
  const parts = [`${rows.length} 个组件`, `每件合计 ${quantityText(total)}`];
  if (nestedCount > 0) parts.push(`其中 ${nestedCount} 个是嵌套套装`);
  return parts.join(' · ');
}

// ================================================================ 输入校验
//
// 这里的校验只为了「少一次往返」，权威判定在后端。逐条与后端 BundleComponentRules 同口径。

/** 组件数量：必须大于 0，小数位不超过 6（与 numeric(19,6) 一致）。 */
export function quantityProblem(raw: string): string | null {
  const value = raw.trim();
  if (!value) return '组件数量不能为空';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return '组件数量不是数字';
  if (parsed <= 0) return '组件数量必须大于 0：数量为 0 的组件会让订单展开出一条 0 子行、库存扣出一条 0 扣减';
  const [, fraction = ''] = value.split('.');
  if (fraction.length > QUANTITY_SCALE) return `组件数量最多 ${QUANTITY_SCALE} 位小数`;
  return null;
}

export function roleProblem(role: string): string | null {
  return role.trim().length > 32 ? '组件角色最多 32 个字符' : null;
}

export function noteProblem(note: string): string | null {
  return note.trim().length > 200 ? '组件备注最多 200 个字符' : null;
}

/** 加一行之前的本地预判。 */
export function addComponentProblem(skuId: string, quantity: string): string | null {
  if (!skuId) return '每个组件都要选一个 SKU';
  return quantityProblem(quantity);
}

// ================================================================ 校验结果

/** 校验通过的提示文案；不通过时给一句「还剩几条」。 */
export function checkSummary(check: BundleComponentCheck): string {
  if (check.ok) return '校验通过：没有发现循环、数量非法或组件不存在的问题';
  return `还有 ${check.problems.length} 条问题要处理`;
}

/** 按 PROBLEM_CODES 的顺序分组，界面上逐组显示而不是一堆平铺的条目。 */
export function groupProblems(problems: BundleComponentProblem[]): {
  code: string;
  label: string;
  hint: string;
  items: BundleComponentProblem[];
}[] {
  const groups: {
    code: string;
    label: string;
    hint: string;
    items: BundleComponentProblem[];
  }[] = [];
  for (const { code } of PROBLEM_CODES) {
    const items = problems.filter((p) => p.code === code);
    if (items.length) {
      groups.push({ code, label: problemLabel(code), hint: problemHint(code), items });
    }
  }
  // 后端若返回了词表外的问题码，也不要静默丢掉 —— 归到最后一组照样显示。
  const known = new Set<string>(PROBLEM_CODES.map((p) => p.code));
  const unknown = problems.filter((p) => !known.has(p.code));
  if (unknown.length) {
    groups.push({ code: 'OTHER', label: '其他问题', hint: '', items: unknown });
  }
  return groups;
}

// ================================================================ 读模型

export async function fetchComponentOverview(): Promise<BundleComponentOverview> {
  return api<BundleComponentOverview>('catalog/components/overview');
}

export async function fetchComponents(filter: ComponentFilter): Promise<BundleComponentPage> {
  const query = new URLSearchParams();
  if (filter.keyword) query.set('keyword', filter.keyword);
  if (filter.versionId) query.set('versionId', filter.versionId);
  if (filter.bundleId) query.set('bundleId', filter.bundleId);
  if (filter.nested) query.set('nested', filter.nested);
  query.set('page', String(filter.page));
  query.set('size', String(filter.size));
  return api<BundleComponentPage>(`catalog/components?${query.toString()}`);
}

export async function fetchComponent(id: string): Promise<BundleComponentItem> {
  return api<BundleComponentItem>(`catalog/components/${id}`);
}

/** 校验结果：一次拿全一个版本上的所有问题。 */
export async function fetchComponentCheck(versionId: string): Promise<BundleComponentCheck> {
  return api<BundleComponentCheck>(`catalog/versions/${versionId}/component-check`);
}

export async function fetchLinks(filter: LinkFilter): Promise<BundleSkuPage> {
  const query = new URLSearchParams();
  if (filter.keyword) query.set('keyword', filter.keyword);
  query.set('page', String(filter.page));
  query.set('size', String(filter.size));
  return api<BundleSkuPage>(`catalog/bundle-skus?${query.toString()}`);
}

// ================================================================ 写模型

export async function addComponent(
  versionId: string,
  input: BundleComponentInput,
): Promise<BundleComponentItem> {
  return api<BundleComponentItem>(`catalog/versions/${versionId}/components`, 'POST', input);
}

export async function editComponent(
  id: string,
  input: BundleComponentEditInput,
): Promise<BundleComponentItem> {
  return api<BundleComponentItem>(`catalog/components/${id}`, 'PUT', input);
}

export async function deleteComponent(id: string, rowVersion: number): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`catalog/components/${id}?rowVersion=${rowVersion}`, 'DELETE');
}

export async function createLink(input: BundleSkuInput): Promise<BundleSkuRow> {
  return api<BundleSkuRow>('catalog/bundle-skus', 'POST', input);
}

export async function updateLink(id: string, input: BundleSkuInput): Promise<BundleSkuRow> {
  return api<BundleSkuRow>(`catalog/bundle-skus/${id}`, 'PUT', input);
}

export async function deleteLink(id: string, rowVersion: number): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`catalog/bundle-skus/${id}?rowVersion=${rowVersion}`, 'DELETE');
}

// ================================================================ 表单辅助

export function emptyComponentInput(skuId = '', quantity = '1'): {
  skuId: string;
  quantity: string;
} {
  return { skuId, quantity };
}

export function emptyLinkInput(): { bundleId: string; skuId: string; note: string } {
  return { bundleId: '', skuId: '', note: '' };
}

/** 契约里的数量是 number（后端 BigDecimal + numeric(19,6)），编辑期用 string 持有用户输入。 */
export function toQuantityNumber(raw: string): number {
  return Number(raw);
}

/** 错误文案：后端返回的是可执行的中文原因（含完整环路），直接显示，不要再包一层「操作失败」。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}
