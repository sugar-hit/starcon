import type {
  CategorySpecAxisInput,
  CategorySpecAxisRule,
  SpecAxis,
  SpecAxisBindingInput,
  SpecAxisBindingSaved,
  SpecAxisInput,
  SpecAxisOption,
  SpecAxisOptionInput,
  SpecAxisOptionSaved,
  SpecAxisOverview,
  SpecAxisPanel,
  SpecAxisSaved,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.1.3.1 规格轴定义前端模型。
 *
 * <p>页面上最容易出错的两处：
 *
 * <ul>
 *   <li>**「没绑」不能画成「矩阵是 0」**。矩阵行数是各轴可用选项的笛卡尔积；
 *       一条轴还没配选项时它是 0，而"一条轴都没绑"也是 0——两者的下一步动作完全不同
 *       （前者去配选项，后者去绑轴）。界面上必须分开写。
 *   <li>**规格选项要显式讲清它同时是属性选项**。值源只有一处，但用户看到的是两个入口，
 *       不点破就会以为颜色值在规格轴和属性各存了一份——那正是蓝图要避免的。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export function fetchOverview() {
  return api<SpecAxisOverview>('catalog/spec-axis/overview');
}

export function fetchAxes() {
  return api<SpecAxis[]>('catalog/spec-axis/axes');
}

export function createAxis(input: SpecAxisInput) {
  return api<SpecAxisSaved>('catalog/spec-axis/axes', 'POST', input);
}

export function updateAxis(id: string, input: SpecAxisInput) {
  return api<SpecAxisSaved>(`catalog/spec-axis/axes/${id}`, 'PUT', input);
}

export function deleteAxis(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/spec-axis/axes/${id}?version=${version}`, 'DELETE');
}

export function fetchOptions(axisId: string) {
  return api<SpecAxisOption[]>(`catalog/spec-axis/axes/${axisId}/options`);
}

export function addOption(axisId: string, input: SpecAxisOptionInput) {
  return api<SpecAxisOptionSaved>(`catalog/spec-axis/axes/${axisId}/options`, 'POST', input);
}

export function deleteOption(axisId: string, value: string) {
  return api<{ deleted: boolean }>(
    `catalog/spec-axis/axes/${axisId}/options?value=${encodeURIComponent(value)}`,
    'DELETE',
  );
}

export function fetchPanel(spuId: string) {
  return api<SpecAxisPanel>(`catalog/spec-axis/spus/${spuId}`);
}

export function bindAxis(spuId: string, input: SpecAxisBindingInput) {
  return api<SpecAxisBindingSaved>(`catalog/spec-axis/spus/${spuId}/axes`, 'POST', input);
}

export function unbindAxis(spuId: string, axisId: string) {
  return api<{ deleted: boolean }>(`catalog/spec-axis/spus/${spuId}/axes/${axisId}`, 'DELETE');
}

export function fetchCategoryRules(category: string | null) {
  const query = category ? `?category=${encodeURIComponent(category)}` : '';
  return api<CategorySpecAxisRule[]>(`catalog/spec-axis/category-rules${query}`);
}

export function saveCategoryRule(specAxisId: string, input: CategorySpecAxisInput) {
  return api<{ category: string; specAxisId: string }>(
    `catalog/spec-axis/category-rules?specAxisId=${specAxisId}`,
    'POST',
    input,
  );
}

export function deleteCategoryRule(category: string, specAxisId: string) {
  return api<{ deleted: boolean }>(
    `catalog/spec-axis/category-rules?category=${encodeURIComponent(category)}&specAxisId=${specAxisId}`,
    'DELETE',
  );
}

// ------------------------------------------------------------ 错误语义

/** 404：记录不存在或当前角色没有访问权限——RLS 已过滤过跨租户的行，不区分两者。 */
export function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

/** 409：乐观锁冲突、编码重复、轴被引用不能删、类目规则不允许该轴。界面必须说明发生了什么。 */
export function isConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

export function errorText(error: unknown, fallback = '操作失败') {
  return error instanceof Error && error.message ? error.message : fallback;
}

// ------------------------------------------------------------ 词表与校验（与后端 SpecAxisRules 同口径）

/**
 * 轴编码：ASCII 大写字母数字与下划线短横线，1~64 位。
 *
 * <p>与选项值**故意不同**：轴码是机器标识（COLOR / SIZE），且必须与承载它的
 * variant_axis 属性定义同 code；选项值是人话（深空灰 / XL），允许中文与空格。
 */
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/;

export function codeProblem(value: string): string | null {
  const code = value.trim();
  if (code === '') return '规格轴编码不能为空';
  if (!CODE_PATTERN.test(code))
    return '规格轴编码请使用英文字母、数字、下划线或短横线，长度 1~64 位';
  return null;
}

/** 规范化为后端期望的大写形态，避免提交后被判非法。 */
export function normalizeCode(value: string) {
  return value.trim().toUpperCase();
}

const VALUE_PATTERN = /^[\p{Script=Han}A-Za-z0-9][\p{Script=Han}A-Za-z0-9 _\-./]{0,99}$/u;

export function valueProblem(value: string): string | null {
  const normalized = value.trim();
  if (normalized === '') return '规格选项值不能为空';
  // 与后端 attribute_option.value varchar(100) 同长度：两处存同一个值，长度必须一致。
  if (normalized.length > 100) return '规格选项值最多 100 个字符（与属性选项的值源同长度）';
  if (!VALUE_PATTERN.test(normalized))
    return '规格选项值只能使用中文、英文字母、数字、空格、下划线、短横线、点或斜杠';
  return null;
}

// ------------------------------------------------------------ 展示

/**
 * 矩阵行数的展示文案。
 *
 * <p>把 0 分成两种含义：一条轴都没绑 → 去绑轴；绑了但没选项 → 去配选项。
 * 两者都画成"0 个组合"会让人以为配置已经生效了。
 */
export function matrixHint(panel: SpecAxisPanel): string {
  const { boundAxisCount, matrixRowCount } = panel;
  if (boundAxisCount === 0)
    return '当前 SPU 还没有绑定任何规格轴，矩阵为空。先在上面挑一条轴绑定，再为它登记选项。';
  if (matrixRowCount === 0)
    return '已绑定规格轴，但其中至少一条轴还没有可用选项，矩阵为空。先为每条轴登记至少一个选项。';
  return `按当前配置展开可生成 ${matrixRowCount} 个 SKU（${panel.axes
    .map((axis) => `${axis.specAxisName} ${axis.enabledOptionCount}`)
    .join(' × ')}）。这个数字随选项增删变化，生成 SKU 前请以最新值为准。`;
}

/**
 * 选项的来源说明。
 *
 * <p>蓝图硬约束是「规格选项不得独立于属性选项存在」，界面上必须把这一侧点破——
 * 否则用户会以为同一个颜色值在两个地方各存了一份。
 */
export function optionSourceText(option: SpecAxisOption): string {
  if (!option.sourceAttributeId) return '⚠️ 找不到承载属性：该值不在属性选项值源中，请检查绑定关系';
  return `值源：属性「${option.sourceAttributeName || option.sourceAttributeCode}」的选项`;
}

/** 类目必绑缺口的提示。0 与「没查过」不是一回事，所以文案要显式区分。 */
export function requiredAxisHint(panel: SpecAxisPanel): string {
  if (panel.requiredAxisNotes.length === 0) return '该类目没有配置必绑规格轴规则。';
  if (panel.missingRequiredAxisCount === 0)
    return `该类目要求的必绑规格轴已全部绑定（${panel.requiredAxisNotes.length} 条）。`;
  return `该类目有 ${panel.missingRequiredAxisCount} 条必绑规格轴尚未绑定，矩阵可能不完整。`;
}

/** 时间戳展示。后端统一给 UTC ISO 串，这里只做本地化，不改值。 */
export function timestamp(value: string | null | undefined) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN');
}

/** 轴字典行摘要：删除前必须看得见的两个计数。 */
export function axisUsageText(axis: SpecAxis) {
  return `${axis.optionCount} 个选项 · ${axis.boundSpuCount} 个 SPU 在用`;
}

// ------------------------------------------------------------ 表单草稿

export type AxisDraft = {
  code: string;
  name: string;
  enabled: boolean;
  version: number;
};

export function emptyAxisDraft(): AxisDraft {
  return { code: '', name: '', enabled: true, version: 0 };
}

export function axisDraft(axis: SpecAxis): AxisDraft {
  return { code: axis.code, name: axis.name, enabled: axis.enabled, version: axis.version };
}

/**
 * 轴表单校验。
 *
 * <p>编辑时**不校验 code**：后端已规定轴编码建后不可改（已绑定的 SPU 按同 code 的
 * variant_axis 属性承载选项），前端在编辑态把它锁成只读并说明原因，比提交后再报 400 更早。
 */
export function validateAxis(draft: AxisDraft, isNew: boolean): string[] {
  const errors: string[] = [];
  if (isNew) {
    const problem = codeProblem(draft.code);
    if (problem) errors.push(problem);
  }
  if (draft.name.trim() === '') errors.push('规格轴名称不能为空');
  else if (draft.name.length > 100) errors.push('规格轴名称最多 100 个字符');
  return errors;
}

export function draftToAxisInput(draft: AxisDraft): SpecAxisInput {
  return {
    code: normalizeCode(draft.code),
    name: draft.name.trim(),
    enabled: draft.enabled,
    version: draft.version,
  };
}

export type OptionDraft = {
  value: string;
  position: string;
  enabled: boolean;
};

export function emptyOptionDraft(): OptionDraft {
  return { value: '', position: '0', enabled: true };
}

export function validateOption(draft: OptionDraft, existing: SpecAxisOption[]): string[] {
  const errors: string[] = [];
  const problem = valueProblem(draft.value);
  if (problem) errors.push(problem);
  if (draft.value.trim() !== '') {
    const same = existing.some((option) => option.value === draft.value.trim());
    // 重复登记是幂等的（会更新位置），但界面上要说清楚它不会新增一行。
    if (same) errors.push(`「${draft.value.trim()}」已存在，重复提交只会更新它的位置与启用状态`);
  }
  if (!/^\d+$/.test(draft.position.trim())) errors.push('排序位置需填 0 起的整数');
  return errors;
}

export function draftToOptionInput(draft: OptionDraft): SpecAxisOptionInput {
  return {
    value: draft.value.trim(),
    position: Number(draft.position.trim()),
    enabled: draft.enabled,
  };
}

export type CategoryRuleDraft = {
  category: string;
  required: boolean;
  position: string;
};

export function emptyCategoryRuleDraft(category = ''): CategoryRuleDraft {
  return { category, required: false, position: '0' };
}

export function validateCategoryRule(draft: CategoryRuleDraft): string[] {
  const errors: string[] = [];
  const category = draft.category.trim();
  if (category === '') errors.push('类目不能为空');
  else if (category.length > 100) errors.push('类目名称最多 100 个字符');
  if (!/^\d+$/.test(draft.position.trim())) errors.push('排序位置需填 0 起的整数');
  return errors;
}

export function draftToCategoryRuleInput(draft: CategoryRuleDraft): CategorySpecAxisInput {
  return {
    category: draft.category.trim(),
    required: draft.required,
    position: Number(draft.position.trim()),
  };
}
