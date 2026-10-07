import type {
  AttributeValidationInput,
  AttributeValidationReport,
  CategoryAttributeCondition,
  CategoryAttributeConditionValue,
  CategoryAttributeRequirement,
  CategoryAttributeRequirementInput,
  CategoryAttributeRequirementSaved,
  CategoryNode,
  CategoryRequirementsOverview,
  CategoryRequirementsView,
  CategoryTree,
  EntryFormSchema,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.3.1.2 类目属性要求前端模型。
 *
 * <p>这个页面上最容易出错的三处：
 *
 * <ul>
 *   <li>**继承必须显式可见**。生效规则集里的每一条都可能来自祖先类目，
 *       界面上不标「来自哪一层」的话，运营改不动也查不了——
 *       「为什么 T恤 要填保修期」这种问题的答案就藏在继承里。
 *   <li>**停用不是删除**。停用 = 本节点不再覆盖祖先（该属性从这个类目豁免）；
 *       删除 = 本层这条覆盖彻底不存在。两者语义不同，按钮不能混成一个。
 *   <li>**条件必填要能当场看到触发**。配置页在填「条件取值」时就要显示
 *       「当【颜色】等于 红色 时必填」这句话，而不是让人提交后再去录入页试错。
 * </ul>
 *
 * <p>表单 schema 与保存前校验的求值结果都来自后端（条件求值只有一处实现，
 * 见 CategoryAttributeRequirementRules），前端**不复制**第二套求值逻辑——
 * 复制一份就意味着迟早会对不上，而对不上的症状是「表单亮必填、提交却放行」。
 */

// ------------------------------------------------------------ 接口

export function fetchOverview() {
  return api<CategoryRequirementsOverview>('catalog/categories/attribute-requirements/overview');
}

export function fetchRequirements(categoryId: string) {
  return api<CategoryRequirementsView>(`catalog/categories/attribute-requirements/${categoryId}`);
}

export function fetchEffective(categoryId: string) {
  return api<CategoryAttributeRequirement[]>(
    `catalog/categories/attribute-requirements/${categoryId}/effective`,
  );
}

export function saveRequirement(categoryId: string, input: CategoryAttributeRequirementInput) {
  return api<CategoryAttributeRequirementSaved>(
    `catalog/categories/attribute-requirements/${categoryId}`,
    'POST',
    input,
  );
}

export function setRequirementEnabled(categoryId: string, attributeId: string, enabled: boolean) {
  return api<{ enabled: boolean }>(
    `catalog/categories/attribute-requirements/${categoryId}/${attributeId}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

export function deleteRequirement(categoryId: string, attributeId: string) {
  return api<{ deleted: boolean }>(
    `catalog/categories/attribute-requirements/${categoryId}/${attributeId}`,
    'DELETE',
  );
}

/**
 * 生成录入表单 schema。
 *
 * <p>{@code values} 是当前已填写的值（key = 属性编码），用于让条件必填立刻反映出来：
 * 用户把颜色改成红色，保修期应当马上变必填。
 */
export function fetchEntryFormSchema(
  categoryId: string,
  values: Record<string, string>,
  includeDisabled = false,
) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (key && value) query.append('values', `${key}:${value}`);
  }
  if (includeDisabled) query.append('includeDisabled', 'true');
  const suffix = query.toString();
  return api<EntryFormSchema>(
    `catalog/categories/attribute-requirements/${categoryId}/entry-form-schema${suffix ? `?${suffix}` : ''}`,
    'POST',
  );
}

/** 保存前校验：逐字段返回结果（不合法也返回 200，整体结论在 valid 上）。 */
export function validateAttributes(categoryId: string, input: AttributeValidationInput) {
  return api<AttributeValidationReport>(
    `catalog/categories/attribute-requirements/${categoryId}/validate`,
    'POST',
    input,
  );
}

// ------------------------------------------------------------ 词表与文案

/** 要求形态词表。与后端 CategoryAttributeRequirementRules.REQUIREMENTS 逐字一致。 */
export const REQUIREMENTS = ['REQUIRED', 'OPTIONAL', 'CONDITIONAL_REQUIRED'] as const;

/** 条件算子词表。与 V15 的 CHECK 约束逐字一致。 */
export const CONDITION_OPERATORS = ['IN', 'NOT_IN', 'EQ', 'NEQ', 'GT', 'GTE', 'LT', 'LTE'] as const;

/**
 * 排序类算子：对没有大小关系的数据类型不可用。
 *
 * <p>与后端 operatorDataTypeProblem 同一口径。允许在界面上选出一个不可求值的条件，
 * 用户要等到录入时才发现「字段永远不校验」——那是最难解释的一类配置。
 */
const ORDERING = ['GT', 'GTE', 'LT', 'LTE'];

export function requirementLabel(requirement: string): string {
  switch (requirement) {
    case 'REQUIRED':
      return '必填';
    case 'OPTIONAL':
      return '可选';
    case 'CONDITIONAL_REQUIRED':
      return '条件必填';
    default:
      return requirement;
  }
}

export function operatorLabel(operator: string): string {
  switch (operator) {
    case 'IN':
      return '属于';
    case 'NOT_IN':
      return '不属于';
    case 'EQ':
      return '等于';
    case 'NEQ':
      return '不等于';
    case 'GT':
      return '大于';
    case 'GTE':
      return '大于等于';
    case 'LT':
      return '小于';
    case 'LTE':
      return '小于等于';
    default:
      return operator;
  }
}

/** 某个数据类型下可选的算子列表（界面上直接渲染成一排按钮）。 */
export function operatorsFor(dataType: string): string[] {
  return CONDITION_OPERATORS.filter(
    (op) => !(ORDERING.includes(op) && ['TEXT', 'OPTION', 'BOOLEAN'].includes(dataType)),
  );
}

// ------------------------------------------------------------ 表单校验（与服务层同口径）

export function requirementProblem(value: string): string | null {
  if (!value) return '要求形态不能为空';
  if (!REQUIREMENTS.includes(value as (typeof REQUIREMENTS)[number]))
    return '要求形态只能是 REQUIRED（必填）、OPTIONAL（可选）或 CONDITIONAL_REQUIRED（条件必填）';
  return null;
}

export function operatorProblem(operator: string, dataType: string): string | null {
  if (!operator) return '条件算子不能为空';
  if (!CONDITION_OPERATORS.includes(operator as (typeof CONDITION_OPERATORS)[number]))
    return '条件算子只能是 IN / NOT_IN / EQ / NEQ / GT / GTE / LT / LTE';
  if (ORDERING.includes(operator) && ['TEXT', 'OPTION', 'BOOLEAN'].includes(dataType))
    return `算子 ${operator} 只能用于数值或日期属性：${dataType} 的取值没有大小关系`;
  return null;
}

/**
 * 取值约束的表单校验。
 *
 * <p>与服务层 rangeProblem 同一套判定，理由相同：能在保存前挡住的，
 * 不要留到录入时才炸。这里多一条「条件取值必须非空」——
 * 空集合的条件恒不成立，配置的人看不出任何异常。
 */
export function rangeProblem(input: {
  dataType: string;
  minValue: string;
  maxValue: string;
  maxLength: string;
  allowFreeText: boolean;
  allowedOptions: string[];
  requirement: string;
  conditionValues: string[];
}): string | null {
  const { dataType, minValue, maxValue, maxLength, allowFreeText, allowedOptions, requirement } =
    input;
  const numeric = dataType === 'DECIMAL';
  const text = dataType === 'TEXT';
  const option = dataType === 'OPTION';

  const min = minValue.trim() ? Number(minValue) : null;
  const max = maxValue.trim() ? Number(maxValue) : null;
  if (min !== null && max !== null && min > max) return '取值下限不能大于上限';
  if (minValue.trim() && !numeric) return '取值上下限只对数值属性（DECIMAL）有意义';
  if (maxValue.trim() && !numeric) return '取值上下限只对数值属性（DECIMAL）有意义';
  if (allowedOptions.length > 0 && !option) return '允许选项只对枚举属性（OPTION）有意义';
  if (maxLength.trim() && !text) return '文本长度上限只对文本属性（TEXT）有意义';
  if (maxLength.trim() && Number(maxLength) <= 0) return '文本长度上限必须大于 0';
  if (allowFreeText && !option) return '「允许自由填写」只对枚举属性（OPTION）有意义';
  if (allowFreeText && allowedOptions.length > 0)
    return '「允许自由填写」与「限定允许选项」互相矛盾：允许自由填写时，所有选项都可选';

  if (requirement === 'CONDITIONAL_REQUIRED' && input.conditionValues.length === 0)
    return '条件必填必须给出至少一个条件取值：取值集合为空时条件恒不成立，这条要求等于没配';
  if (requirement !== 'CONDITIONAL_REQUIRED' && input.conditionValues.length > 0)
    return '只有条件必填（CONDITIONAL_REQUIRED）的规则才能配置条件取值';
  return null;
}

/** 编辑表单的整体校验。返回 null 表示可提交。 */
export function requirementInputProblem(
  input: CategoryAttributeRequirementInput,
  dataType: string,
): string | null {
  const shape = requirementProblem(input.requirement);
  if (shape) return shape;
  if (!input.attributeId) return '请选择属性';
  if (input.condition) {
    // 算子相容性看的是**前置属性**的数据类型，不是被编辑属性的：
    // 「保修期在颜色=…时必填」里 GT 能不能用，取决于颜色是枚举还是数值。
    // 用错类型就等于放行了一个永远求不出值的条件。
    const dependencyType = input.condition.dataType || dataType;
    const operator = operatorProblem(input.condition.operator, dependencyType);
    if (operator) return operator;
  }
  return rangeProblem({
    dataType,
    minValue: input.minValue === null || input.minValue === undefined ? '' : String(input.minValue),
    maxValue: input.maxValue === null || input.maxValue === undefined ? '' : String(input.maxValue),
    maxLength:
      input.maxLength === null || input.maxLength === undefined ? '' : String(input.maxLength),
    allowFreeText: input.allowFreeText,
    allowedOptions: input.allowedOptions ?? [],
    requirement: input.requirement,
    conditionValues: (input.conditionValues ?? []).map((v) => v?.value ?? ''),
  });
}

// ------------------------------------------------------------ 展示辅助

/**
 * 「当【颜色】属于 红色 / 蓝色 时必填」——配置页与规则列表都用这一句。
 *
 * <p>前置属性的名字从两个形状里取：规则 DTO 里的 {@code attributeName}，
 * 表单 schema 的 dependency 用的却是 {@code label}（录入表单里显示的是标签不是字典名）。
 * 两边不统一的话，同一条规则在配置页和表单预览里会显示成两个不同的属性名。
 */
export function conditionText(
  condition:
    | (CategoryAttributeCondition | { attributeName?: string | null; label?: string | null })
    | null
    | undefined,
  values: string[],
): string {
  if (!condition) return '';
  const named = condition as {
    attributeName?: string | null;
    label?: string | null;
    attributeCode?: string | null;
    operator: string;
  };
  const label = named.attributeName || named.label || named.attributeCode || '前置属性';
  return `当【${label}】${operatorLabel(named.operator)} ${values.join(' / ')} 时必填`;
}

/** 树列表：类目下拉的树来源。与 3.3.1.1 同一个端点，不重复实现。 */
export function fetchTrees() {
  return api<CategoryTree[]>('catalog/categories/trees');
}

/** 某一棵树的全部节点（一次取回整棵树）。 */
export function fetchNodes(treeId: string) {
  return api<CategoryNode[]>(`catalog/categories/trees/${treeId}/nodes`);
}

/** 规则的来源说明。继承来的必须说清来自哪一层，否则运营无法自查。 */
export function sourceText(requirement: CategoryAttributeRequirement): string {
  if (!requirement.inherited) return '本类目配置';
  const layers = requirement.depth === 0 ? '根类目' : `第 ${requirement.depth} 层祖先`;
  return `继承自【${requirement.sourceCategoryName}】（${layers}）`;
}

/** 覆盖率概览的一句话。 */
export function overviewHint(overview: CategoryRequirementsOverview): string {
  if (overview.categoriesWithoutRequirement === 0)
    return `${overview.categoryCount} 个内部类目都配了属性要求`;
  return (
    `${overview.categoriesWithRequirement} / ${overview.categoryCount} 个内部类目配了属性要求，` +
    `${overview.categoriesWithoutRequirement} 个还没有——这些类目的录入表单不做必填校验`
  );
}

/** 只有一个类目配了要求时的空态提示：告诉用户去哪儿配，而不是显示空白表格。 */
export function emptyHint(view: CategoryRequirementsView | null): string {
  if (!view) return '';
  if (view.own.length > 0) return '这个类目还没有自己的规则，当前看到的全部来自祖先类目';
  return '还没有为这个类目配置属性要求。配置后，商品录入时会在这个类目下校验必填与取值范围';
}

// ------------------------------------------------------------ 类型辅助

export function conditionInput(
  condition: CategoryAttributeCondition | null | undefined,
): CategoryAttributeCondition | null {
  return condition
    ? {
        attributeId: condition.attributeId,
        attributeCode: condition.attributeCode,
        attributeName: condition.attributeName,
        dataType: condition.dataType,
        operator: condition.operator,
      }
    : null;
}

export function conditionValueInputs(values: string[]): CategoryAttributeConditionValue[] {
  return values.map((value, index) => ({ value, position: index }));
}

export function conditionValueTexts(
  values: CategoryAttributeConditionValue[] | null | undefined,
): string[] {
  return (values ?? []).map((entry) => entry?.value ?? '').filter(Boolean);
}

/** 空表单：新建规则时的初值。 */
export function emptyRequirementInput(
  attributeId: string,
  dataType: string,
): CategoryAttributeRequirementInput {
  return {
    attributeId,
    requirement: 'OPTIONAL',
    minValue: null,
    maxValue: null,
    maxLength: null,
    allowFreeText: false,
    position: 0,
    allowedOptions: [],
    condition:
      dataType === 'OPTION'
        ? null
        : { attributeId: '', attributeCode: '', attributeName: '', dataType, operator: 'EQ' },
    conditionValues: [],
    enabled: true,
    version: 0,
  };
}

/** 规则 → 编辑表单初值。 */
export function toRequirementInput(
  requirement: CategoryAttributeRequirement,
): CategoryAttributeRequirementInput {
  return {
    attributeId: requirement.attributeId,
    requirement: requirement.requirement,
    minValue: requirement.minValue,
    maxValue: requirement.maxValue,
    maxLength: requirement.maxLength,
    allowFreeText: requirement.allowFreeText,
    position: requirement.position,
    allowedOptions: [...requirement.allowedOptions],
    condition: conditionInput(requirement.condition),
    conditionValues: [],
    enabled: requirement.enabled,
    version: requirement.version,
  };
}

/**
 * 类目下拉的可选项：只取内部管理分类树的启用节点。
 *
 * <p>平台类目映射树的节点归 04 刊登维护（蓝图明确「不同平台的类目要求可能不同」），
 * 让它出现在这里只会诱导运营配出用不上的规则。
 */
export function internalCategories(trees: CategoryTree[], nodes: CategoryNode[]): CategoryNode[] {
  const internalTreeIds = new Set(
    trees.filter((tree) => tree.treeKind === 'INTERNAL').map((tree) => tree.id),
  );
  return nodes.filter((node) => node.enabled && internalTreeIds.has(node.treeId));
}

/** 把接口错误转成可显示的中文文案，沿用 3.3.1.1 的口径。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}
