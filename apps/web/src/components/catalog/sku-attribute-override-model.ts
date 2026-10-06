import type {
  SkuOverrideAttribute,
  SkuOverrideView,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.2.2.2 SKU 属性覆写前端模型。
 *
 * <p>界面纪律（与后端 {@code SkuAttributeOverrideService} 同源）：
 *
 * <ul>
 *   <li>**四种来源各有各的说法**，不能一律写成"未填写"。`SKU_CLEARED` 是运营主动声明
 *       "这个 SKU 没有这条属性"，`UNSET` 是"还没人填过"——两者的下一步动作不一样。
 *   <li>**有效值与来源分开显示**。有效值是空数组时显示来源标签，不显示空白单元格，
 *       否则用户会以为页面没加载出来。
 *   <li>**必填缺口在读的时候就标出来**，不用等点了保存才吃 400。后端在面板里已经算好
 *       `missingRequired`，照着渲染即可。
 *   <li>**不可改的属性在读的时候就禁用**，并把 `blockReason` 显示出来。让人点了之后
 *       再看报错是最差的一种交互。
 * </ul>
 */

/**
 * 覆写面板路径。
 *
 * <p>按 SKU 寻址，与后端 {@code SkuAttributeOverrideController} 的
 * {@code /catalog/sku-attribute-override/skus/{id}} 一致。刻意不要求前端先查商品详情
 * 拿 SPU id 再拼路径：多一次请求、多一个可能与后端不一致的入参，而 SKU 的归属本来就唯一。
 */
export function overridePath(skuId: string) {
  return `catalog/sku-attribute-override/skus/${encodeURIComponent(skuId)}`;
}

export function fetchOverridePanel(skuId: string) {
  return api<SkuOverrideView>(overridePath(skuId));
}

/**
 * 设置覆写值。
 *
 * <p>值列表**不允许为空**：清空是独立端点（`clearOverride`），传空数组会让
 * "取消覆写"与"显式清空"两个方向相反的动作共用一个调用点。
 */
export function setOverride(skuId: string, attributeId: string, values: string[]) {
  return api<SkuOverrideView>(
    `${overridePath(skuId)}/attribute-overrides/${encodeURIComponent(attributeId)}`,
    'PUT',
    { values },
  );
}

/** 显式清空：有效值变空，且不退回 SPU 公共值。必填属性会被后端拒绝。 */
export function clearOverride(skuId: string, attributeId: string) {
  return api<SkuOverrideView>(
    `${overridePath(skuId)}/attribute-overrides/${encodeURIComponent(attributeId)}/clear`,
    'PUT',
  );
}

/** 取消覆写：移除 SKU 侧那一行，落回 SPU 公共值。没有覆写时也是幂等成功。 */
export function removeOverride(skuId: string, attributeId: string) {
  return api<SkuOverrideView>(
    `${overridePath(skuId)}/attribute-overrides/${encodeURIComponent(attributeId)}`,
    'DELETE',
  );
}

// ------------------------------------------------------------ 展示语义

/** 来源的中文说法与语气。键与后端的 {@code EffectiveValues.Source.code()} 一一对应。 */
export const SOURCE_LABELS = {
  SKU_OVERRIDE: { label: 'SKU 覆写', tone: 'info' },
  SKU_CLEARED: { label: 'SKU 已清空', tone: 'warning' },
  SPU_COMMON: { label: 'SPU 公共值', tone: 'neutral' },
  UNSET: { label: '未设置', tone: 'warning' },
} as const;

export type SourceCode = keyof typeof SOURCE_LABELS;

export function isSourceCode(value: string): value is SourceCode {
  return value in SOURCE_LABELS;
}

export function sourceLabel(attribute: SkuOverrideAttribute): string {
  return isSourceCode(attribute.source) ? SOURCE_LABELS[attribute.source].label : attribute.source;
}

export function sourceTone(attribute: SkuOverrideAttribute) {
  return isSourceCode(attribute.source) ? SOURCE_LABELS[attribute.source].tone : 'neutral';
}

/**
 * 有效值的显示文本。
 *
 * <p>空数组**不能**返回空串——空串会被渲染成一个看不出所以然的空格。返回 null 让调用方
 * 按来源分别给"已显式清空"或"未设置"，那才是用户真正需要的信息。
 */
export function effectiveText(attribute: SkuOverrideAttribute): string | null {
  return attribute.effectiveValues.length === 0 ? null : attribute.effectiveValues.join(' / ');
}

/** 必填缺口文案：区分"从未设置"与"被显式清空"，两者下一步动作不同。 */
export function missingHint(attribute: SkuOverrideAttribute): string {
  return attribute.source === 'SKU_CLEARED'
    ? '已显式清空。必填属性不能处于这个状态，请填入取值或先在类目规则里取消必填。'
    : '还没有生效值：既没有 SKU 覆写，SPU 公共侧也没有值。';
}

/** 只读角色：写操作按权限收敛，而不是等点了才 403。 */
export function writable(permissions: readonly string[]): boolean {
  return permissions.includes('catalog.attribute.update');
}

/** 只看需要操作的属性（改过 / 没值的），默认视图用。 */
export function actionable(attribute: SkuOverrideAttribute): boolean {
  return attribute.source !== 'SPU_COMMON' || attribute.missingRequired;
}

// ------------------------------------------------------------ 错误处理

export function errorText(error: unknown, fallback = '操作失败'): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 缺失清单的一行。字段与后端 {@code SkuOverrideMissing} 逐字对齐。 */
export type OverrideMissing = {
  attributeId: string;
  code: string;
  name: string;
  source: string;
  reason: string;
};

/**
 * 400 带结构化缺失清单时取出它，供界面按条渲染。
 *
 * <p>后端把清单挂在 ProblemDetail 的 `missing` 属性上（见 ApiErrorHandler）。
 * 取不到就返回空数组，调用方据此区分"有清单要逐条显示"与"只是一句通用提示"。
 */
export function missingFromError(error: unknown): OverrideMissing[] {
  const body = (error as { body?: { missing?: unknown } } | null)?.body;
  return Array.isArray(body?.missing) ? (body.missing as OverrideMissing[]) : [];
}

/** 必填校验未通过（400 且带清单）：界面要按条显示缺什么、为什么算缺。 */
export function isRequiredMissing(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400 && missingFromError(error).length > 0;
}

/** 404：SPU / SKU / 属性不存在，或当前租户看不到它。RLS 已过滤，不区分两者。 */
export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}
