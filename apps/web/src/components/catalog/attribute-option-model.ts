import type {
  AttributeOptionOrderSaved,
  AttributeOptionOverviewRow,
  AttributeOptionPage,
  AttributeOptionToggled,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.2.1.2 属性选项管理前端模型。
 *
 * <p>界面纪律（与后端 {@code AttributeOptionService} 同源）：
 *
 * <ul>
 *   <li>**引用计数如实呈现，不编故事**。`productValueCount` 目前恒为 0，因为字典条目
 *       暂时拿不到商品属性值（`attribute_value` 的复合外键仍锁在 SPU 作用域）。
 *       界面上把它写成「商品取用 0」并配一句说明，而不是写成「已被 0 个商品使用」——
 *       后者会让人以为这是一个真实测量出来的数，点了没反应才回来查。
 *   <li>**停用与删除分开呈现**。`deletable` ���后端算好的合成字段：有引用只能停用。
 *   <li>**「没有选项」和「选项全被停用」不是一回事**，聚合页上要分开说，
 *       否则运营会去改一个已经改过的地方。
 * </ul>
 */

/** 排序档位。白名单与后端 ORDER_BY 一一对应，未命中的值不会发出去。 */
export const OPTION_SORTS = [
  { value: 'POSITION', label: '按属性与位置' },
  { value: 'ATTRIBUTE', label: '按属性分组' },
  { value: 'VALUE', label: '按取值' },
  { value: 'CREATED_AT', label: '按登记时间' },
  { value: 'REFERENCES', label: '按引用次数' },
] as const;

export type OptionSort = (typeof OPTION_SORTS)[number]['value'];

export type OptionListQuery = {
  keyword?: string;
  attributeId?: string | null;
  enabled: boolean | null;
  referenced: boolean | null;
  sort: OptionSort;
  page: number;
  size: number;
};

export function fetchOptionOverview(query: OptionListQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('q', query.keyword.trim());
  if (query.attributeId) params.set('attributeId', query.attributeId);
  if (query.enabled !== null && query.enabled !== undefined)
    params.set('enabled', String(query.enabled));
  if (query.referenced !== null && query.referenced !== undefined)
    params.set('referenced', String(query.referenced));
  if (query.sort) params.set('sort', query.sort);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<AttributeOptionPage>(`catalog/attributes/options?${params.toString()}`);
}

export function toggleOption(attributeId: string, value: string, enabled: boolean) {
  return api<AttributeOptionToggled>(
    `catalog/attributes/options/lifecycle?attributeId=${encodeURIComponent(attributeId)}&value=${encodeURIComponent(value)}`,
    'PUT',
    { enabled },
  );
}

/**
 * 批量重排：一次提交某个属性的全部（或部分）选项及其新位置。
 *
 * <p>用 `param` 而不是拼在 query 里：选项值是中文（深空灰 / XL），
 * 手写 encodeURIComponent 容易在别处漏掉，后端就会收到一个不存在的值。
 */
export function reorderOptions(
  attributeId: string,
  items: { value: string; position: number }[],
) {
  return api<AttributeOptionOrderSaved>('catalog/attributes/options/order', 'PUT', {
    attributeId,
    items,
  });
}

// ------------------------------------------------------------ 展示语义

/**
 * 引用计数怎么念。
 *
 * <p>`productValueCount` 恒为 0 时**不能**念成「已被 0 个商品使用」——那是伪造了一个测量结果。
 * 这里直接说明它尚未接入赋值链路，让运营知道「0」是现状而不是结论。
 */
export function referenceText(row: AttributeOptionOverviewRow): string {
  if (row.productValueCount > 0) return `商品取用 ${row.productValueCount}`;
  if (row.categoryRuleCount > 0) return `类目规则 ${row.categoryRuleCount} 条`;
  return '暂无引用';
}

/** 有引用时给出「只能停用」的原因，界面上用它替代「删除」。 */
export function retainHint(row: AttributeOptionOverviewRow): string | null {
  if (!row.inUse) return null;
  if (row.categoryRuleCount > 0)
    return `已被 ${row.categoryRuleCount} 条类目规则列为可选值，删除会连带改掉那些规则；请改为停用`;
  if (row.productValueCount > 0)
    return `已被 ${row.productValueCount} 处商品属性值取用，历史单据还要靠它解析；请改为停用`;
  return null;
}

/**
 * 空态文案：区分「这个筛选下没有选项」与「租户里一个选项都没有」。
 *
 * <p>两者的下一步完全不同：前者是筛选条件太窄，后者是属性字典还没配取值。
 *
 * <p>判断只看**有没有在筛**：只要用户设了筛选条件而结果为空，下一步就一定是放宽条件——
 * 这时即便 total 为 0（筛的就是 0 条）也一样，否则会把人引去一个跟当前问题无关的地方。
 */
export function emptyHint(total: number, filtered: boolean): string {
  if (filtered) return '当前筛选条件下没有选项，放宽条件试试。';
  return total > 0
    ? '当前没有可展示的选项，试试重置筛选。'
    : '还没有任何属性选项。先在属性字典里把枚举属性的取值登记进来。';
}

/** 聚合页按属性分组时用的标题：`CODE · 名称`。 */
export function attributeLabel(row: AttributeOptionOverviewRow): string {
  return `${row.attributeCode} · ${row.attributeName}`;
}

/** 引用状态的三态筛选标签。 */
export const REFERENCED_FILTERS = [
  { value: null as boolean | null, label: '全部引用状态' },
  { value: true, label: '仅被引用的' },
  { value: false, label: '仅未被引用的' },
] as const;

/** 启用状态的三态筛选标签。 */
export const ENABLED_FILTERS = [
  { value: null as boolean | null, label: '全部状态' },
  { value: true, label: '仅启用的' },
  { value: false, label: '仅停用的' },
] as const;

export function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

/** 400：校验失败（值不属于该属性 / 批次内重复 / 父属性已停用）。界面必须说明发生了什么。 */
export function isBadRequest(error: unknown) {
  return error instanceof ApiError && error.status === 400;
}

export function isForbidden(error: unknown) {
  return error instanceof ApiError && error.status === 403;
}

export function errorText(error: unknown, fallback = '操作失败') {
  return error instanceof Error && error.message ? error.message : fallback;
}

/**
 * 把当前页按属性分组渲染。
 *
 * <p>排序交给后端（跨属性顺序由 position 决定），这里只把**相邻同属性**的行并成一组，
 * 不重新排序——前端再排一次会让用户以为服务端返回的顺序不可信。
 */
export function groupByAttribute(rows: AttributeOptionOverviewRow[]) {
  const groups: { attributeId: string; label: string; rows: AttributeOptionOverviewRow[] }[] = [];
  for (const row of rows) {
    const last = groups[groups.length - 1];
    if (last && last.attributeId === row.attributeId) last.rows.push(row);
    else
      groups.push({
        attributeId: row.attributeId,
        label: attributeLabel(row),
        rows: [row],
      });
  }
  return groups;
}

/**
 * 生成一份「按当前顺序下移一位」的提交内容。
 *
 * <p>批量重排用的是覆盖式提交，所以这里必须给出**完整的**目标顺序：
 * 只提交被拖动的那一项，其余选项会保留旧 position，新旧位置可能撞在一起。
 */
export function moveWithin(rows: AttributeOptionOverviewRow[], from: number, to: number) {
  const next = rows.map((row, index) => ({ value: row.value, position: index }));
  const moved = next[from];
  if (!moved) return null;
  next.splice(from, 1);
  next.splice(to, 0, moved);
  return next.map((item, index) => ({ value: item.value, position: index }));
}