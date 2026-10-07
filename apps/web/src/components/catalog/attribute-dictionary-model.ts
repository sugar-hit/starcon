import type {
  AttributeDefinitionInput,
  AttributeDefinitionPage,
  AttributeDefinitionRow,
  AttributeDefinitionSaved,
  AttributeDictionaryOverview,
  AttributeOptionInput,
  AttributeOptionRow,
  AttributeOptionSaved,
  CategoryAttributeRule,
  CategoryAttributeRuleInput,
  CategoryAttributeRuleSaved,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.2.1.1 属性定义列表前端模型。
 *
 * <p>页面上最容易出错的三处，这里逐个钉住：
 *
 * <ul>
 *   <li>**「没有选项」和「有 0 个可用选项」不是一回事**。前者是枚举属性还没配取值（下一步去配），
 *       后者是取值都停用了（下一步去启用）。都画成「0 个选项」会让人以为配置已经生效。
 *   <li>**停用与删除必须分开呈现**。有引用的选项只能停用：历史商品还要靠它解析那个值。
 *       界面上在用户点删除之前就把「被引用」标出来，比点了才被拒绝要好。
 *   <li>**类目规则的可选范围要显示出来**。一条「类目服饰只允许红色/蓝色」的规则，
 *       如果界面上只显示「必填」，运营根本不知道录入时能选什么。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export type AttributeListQuery = {
  keyword?: string;
  dataType?: string;
  usage?: string;
  filterable?: boolean | null;
  enabled?: boolean | null;
  page: number;
  size: number;
};

export function fetchAttributes(query: AttributeListQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('q', query.keyword.trim());
  if (query.dataType) params.set('dataType', query.dataType);
  if (query.usage) params.set('usage', query.usage);
  if (query.filterable !== null && query.filterable !== undefined)
    params.set('filterable', String(query.filterable));
  if (query.enabled !== null && query.enabled !== undefined)
    params.set('enabled', String(query.enabled));
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<AttributeDefinitionPage>(`catalog/attributes?${params.toString()}`);
}

export function fetchAttributeOverview() {
  return api<AttributeDictionaryOverview>('catalog/attributes/overview');
}

export function createAttribute(input: AttributeDefinitionInput) {
  return api<AttributeDefinitionSaved>('catalog/attributes', 'POST', input);
}

export function updateAttribute(id: string, input: AttributeDefinitionInput) {
  return api<AttributeDefinitionSaved>(`catalog/attributes/${id}`, 'PUT', input);
}

export function deleteAttribute(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/attributes/${id}?version=${version}`, 'DELETE');
}

export function fetchOptions(id: string) {
  return api<AttributeOptionRow[]>(`catalog/attributes/${id}/options`);
}

export function addOption(id: string, input: AttributeOptionInput) {
  return api<AttributeOptionSaved>(`catalog/attributes/${id}/options`, 'POST', input);
}

export function updateOption(id: string, value: string, input: AttributeOptionInput) {
  return api<AttributeOptionSaved>(
    `catalog/attributes/${id}/options?value=${encodeURIComponent(value)}`,
    'PUT',
    input,
  );
}

export function deleteOption(id: string, value: string) {
  return api<{ deleted: boolean }>(
    `catalog/attributes/${id}/options?value=${encodeURIComponent(value)}`,
    'DELETE',
  );
}

export function fetchCategoryRules(category: string | null, attributeId: string | null) {
  const params = new URLSearchParams();
  if (category?.trim()) params.set('category', category.trim());
  if (attributeId) params.set('attributeId', attributeId);
  const suffix = params.toString();
  return api<CategoryAttributeRule[]>(`catalog/attributes/category-rules${suffix ? `?${suffix}` : ''}`);
}

export function saveCategoryRule(id: string, input: CategoryAttributeRuleInput) {
  return api<CategoryAttributeRuleSaved>(`catalog/attributes/${id}/category-rules`, 'POST', input);
}

export function deleteCategoryRule(id: string, category: string) {
  return api<{ deleted: boolean }>(
    `catalog/attributes/${id}/category-rules?category=${encodeURIComponent(category)}`,
    'DELETE',
  );
}

// ------------------------------------------------------------ 错误语义

/** 404：记录不存在或当前角色没有访问权限——RLS 已过滤过跨租户的行，不区分两者。 */
export function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

/** 409：乐观锁冲突、编码重复、被引用不能删。界面必须说明发生了什么。 */
export function isConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

export function errorText(error: unknown, fallback = '操作失败') {
  return error instanceof Error && error.message ? error.message : fallback;
}

// ------------------------------------------------------------ 词表与校验（与后端 AttributeDictionaryRules 同口径）

export const DATA_TYPES = [
  { value: 'TEXT', label: '文本' },
  { value: 'DECIMAL', label: '数字' },
  { value: 'BOOLEAN', label: '布尔' },
  { value: 'DATE', label: '日期' },
  { value: 'OPTION', label: '枚举' },
] as const;

export const USAGES = [
  { value: 'DESCRIPTION', label: '描述' },
  { value: 'SPECIFICATION', label: '规格' },
  { value: 'LOGISTICS', label: '物流' },
  { value: 'COMPLIANCE', label: '合规' },
] as const;

export const VALUE_MODES = [
  { value: 'COMMON_DEFAULT', label: 'SPU 公共值（SKU 可覆写）' },
  { value: 'COMMON_LOCKED', label: 'SPU 公共值锁定（SKU 不可覆写）' },
  { value: 'SKU_ONLY', label: '仅 SKU 填写' },
] as const;

/**
 * 属性编码：ASCII 字母数字与下划线短横线，1~64 位。
 *
 * <p>与选项值**故意不同**：编码是机器标识（COLOR / NET_WEIGHT），且必须与 SPU 级属性定义、
 * 规格轴按同 code 对齐；选项值是人话（深空灰 / XL），允许中文。
 */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/;

export function normalizeCode(value: string) {
  return value.trim().toUpperCase();
}

export function codeProblem(value: string): string | null {
  const code = value.trim();
  if (code === '') return '属性编码不能为空';
  if (!CODE_PATTERN.test(code))
    return '属性编码请使用英文字母、数字、下划线或短横线，长度 1~64 位';
  return null;
}

export function valueProblem(value: string): string | null {
  const normalized = value.trim();
  if (normalized === '') return '选项值不能为空';
  // 与后端 attribute_option.value varchar(100) 同长度。
  if (normalized.length > 100) return '选项值最多 100 个字符';
  return null;
}

export function labelOf(list: readonly { value: string; label: string }[], value: string) {
  return list.find((item) => item.value === value)?.label ?? value;
}

/**
 * 规格轴的固定形态提示。
 *
 * <p>与后端 `variantAxisProblem` 与 V1 的 CHECK 同口径：规格轴必须登记为
 * 「按 SKU 填写的必填单选枚举属性」，且用途必须是「规格」。
 */
export function variantAxisHint(draft: AttributeDraft): string | null {
  if (!draft.variantAxis) return null;
  if (
    draft.dataType !== 'OPTION' ||
    draft.valueMode !== 'SKU_ONLY' ||
    draft.multiple ||
    !draft.required
  )
    return '规格轴必须登记为「按 SKU 填写的必填单选枚举属性」，否则规格矩阵无法生成。';
  if (draft.usage !== 'SPECIFICATION')
    return '规格轴属性的用途必须是「规格」：规格轴的输入就是用途=规格的属性定义。';
  return null;
}

// ------------------------------------------------------------ 展示

/** 列表行摘要：删除前必须看得见的三个计数。 */
export function usageText(row: AttributeDefinitionRow) {
  return `${row.optionCount} 个选项 · ${row.categoryRuleCount} 条类目规则 · ${row.spuUsageCount} 个商品在用`;
}

/**
 * 选项摘要：把「0 个可用选项」拆成两种含义。
 *
 * <p>枚举属性一条取值都没有 → 还没配；配了但全部停用 → 该启用哪一个。
 * 两者都画成「0 个选项」会让人以为配置已生效。
 */
export function optionCountHint(row: AttributeDefinitionRow): string | null {
  if (row.dataType !== 'OPTION') return null;
  if (row.optionCount === 0) return '枚举属性还没有任何取值，请先在下方登记至少一个选项。';
  if (row.enabledOptionCount === 0)
    return `全部 ${row.optionCount} 个选项都已停用，新录入时无法选择，请至少启用一个。`;
  return null;
}

/** 类目规则的取值范围文案。0 条与「没配过」不是一回事，所以分开写。 */
export function ruleRangeText(rule: CategoryAttributeRule): string {
  if (rule.dataType === 'DECIMAL') {
    if (rule.minValue === null && rule.maxValue === null) return '不限取值范围';
    const min = rule.minValue ?? '不限';
    const max = rule.maxValue ?? '不限';
    return `取值范围 ${min} ~ ${max}`;
  }
  if (rule.dataType === 'OPTION') {
    if (rule.allowedOptions.length === 0) return '允许全部选项';
    return `只允许：${rule.allowedOptions.join('、')}`;
  }
  return '不限取值范围';
}

/** 时间戳展示。后端统一给 UTC ISO 串，这里只做本地化，不改值。 */
export function timestamp(value: string | null | undefined) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN');
}

// ------------------------------------------------------------ 表单草稿

export type AttributeDraft = {
  code: string;
  name: string;
  dataType: string;
  valueMode: string;
  usage: string;
  multiple: boolean;
  required: boolean;
  filterable: boolean;
  variantAxis: boolean;
  unit: string;
  groupName: string;
  position: string;
  enabled: boolean;
  options: string;
  version: number;
};

export function emptyAttributeDraft(): AttributeDraft {
  return {
    code: '',
    name: '',
    dataType: 'TEXT',
    valueMode: 'COMMON_DEFAULT',
    usage: 'DESCRIPTION',
    multiple: false,
    required: false,
    filterable: false,
    variantAxis: false,
    unit: '',
    groupName: '',
    position: '0',
    enabled: true,
    options: '',
    version: 0,
  };
}

export function attributeDraft(row: AttributeDefinitionRow): AttributeDraft {
  return {
    code: row.code,
    name: row.name,
    dataType: row.dataType,
    valueMode: row.valueMode,
    usage: row.usage,
    multiple: row.multiple,
    required: row.required,
    filterable: row.filterable,
    variantAxis: row.variantAxis,
    unit: row.unit,
    groupName: row.groupName,
    position: String(row.position),
    enabled: row.enabled,
    options: '',
    version: row.version,
  };
}

/** 把选项文本按行拆成数组；空行忽略，去空白、去空项。 */
export function parseOptions(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '');
}

export function validateAttribute(draft: AttributeDraft, isNew: boolean): string[] {
  const errors: string[] = [];
  if (isNew) {
    const problem = codeProblem(draft.code);
    if (problem) errors.push(problem);
  }
  if (draft.name.trim() === '') errors.push('属性名称不能为空');
  else if (draft.name.trim().length > 100) errors.push('属性名称最多 100 个字符');
  if (draft.position.trim() !== '' && !/^\d+$/.test(draft.position.trim()))
    errors.push('排序位置需填 0 起的整数');

  const options = parseOptions(draft.options);
  if (draft.dataType === 'OPTION' && isNew && options.length === 0)
    errors.push('枚举属性请至少提供一个选项（每行一个）');
  if (draft.dataType !== 'OPTION' && options.length > 0)
    errors.push('只有枚举属性（OPTION）可以配置选项集合');
  if (new Set(options).size !== options.length) errors.push('选项不可重复');
  for (const option of options) {
    const problem = valueProblem(option);
    if (problem) errors.push(problem);
  }
  const axisHint = variantAxisHint(draft);
  if (axisHint) errors.push(axisHint);
  return errors;
}

export function draftToAttributeInput(draft: AttributeDraft): AttributeDefinitionInput {
  return {
    code: normalizeCode(draft.code),
    name: draft.name.trim(),
    dataType: draft.dataType,
    valueMode: draft.valueMode,
    usage: draft.usage,
    multiple: draft.multiple,
    required: draft.required,
    filterable: draft.filterable,
    variantAxis: draft.variantAxis,
    unit: draft.unit.trim(),
    groupName: draft.groupName.trim(),
    position: Number(draft.position.trim() || '0'),
    enabled: draft.enabled,
    options: parseOptions(draft.options),
    version: draft.version,
  };
}

export type RuleDraft = {
  category: string;
  required: boolean;
  minValue: string;
  maxValue: string;
  position: string;
  allowedOptions: string[];
};

export function emptyRuleDraft(category = ''): RuleDraft {
  return { category, required: false, minValue: '', maxValue: '', position: '0', allowedOptions: [] };
}

export function validateRule(
  draft: RuleDraft,
  dataType: string,
  options: AttributeOptionRow[],
): string[] {
  const errors: string[] = [];
  const category = draft.category.trim();
  if (category === '') errors.push('类目不能为空');
  else if (category.length > 100) errors.push('类目名称最多 100 个字符');
  if (!/^\d+$/.test(draft.position.trim())) errors.push('排序位置需填 0 起的整数');

  const minText = draft.minValue.trim();
  const maxText = draft.maxValue.trim();
  const hasRange = minText !== '' || maxText !== '';

  const numeric = (text: string, label: string): number | null => {
    if (text === '') return null;
    if (!/^-?\d+(\.\d+)?$/.test(text)) {
      errors.push(`取值${label}请填数字`);
      return null;
    }
    return Number(text);
  };
  const min = numeric(minText, '下限');
  const max = numeric(maxText, '上限');

  if (hasRange && dataType !== 'DECIMAL')
    errors.push('取值上下限只对数字属性有意义；枚举属性请改用可选值子集');
  // 两个槽位都解析成功才比大小：任一填错时报的应该是「请填数字」，不是「下限大于上限」。
  if (min !== null && max !== null && min > max) errors.push('取值范围的下限不能大于上限');

  if (dataType === 'OPTION' && hasRange)
    errors.push('枚举属性的可选值范围请用可选值子集表达，不要同时设置取值上下限');

  const known = new Set(options.filter((option) => option.enabled).map((option) => option.value));
  for (const value of draft.allowedOptions) {
    if (!known.has(value)) errors.push(`可选值「${value}」不在该属性的可用选项里`);
  }
  return errors;
}

/** 空串表示「不限」，否则交给后端按字符串解析，避免这里先转 JS number 丢掉精度。 */
function decimalOrNull(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === '') return null;
  // 用字符串交给后端解析，避免这里先转成 JS number 丢掉精度。
  return Number(trimmed);
}

export function draftToRuleInput(draft: RuleDraft): CategoryAttributeRuleInput {
  return {
    category: draft.category.trim(),
    required: draft.required,
    minValue: decimalOrNull(draft.minValue),
    maxValue: decimalOrNull(draft.maxValue),
    position: Number(draft.position.trim() || '0'),
    allowedOptions: draft.allowedOptions,
  };
}
