import type {
  SkuDetail,
  SkuDetailInput,
  SkuDetailSaved,
  SkuEffectiveAttribute,
  SkuPackaging,
  SkuUnitConversion,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.1.2.2 SKU 详情前端模型。
 *
 * <p>页面上最重要的一条纪律：**没接入的数据不显示成 0**。库存、成本、售价、平台 listing
 * 各自归 07/06/05/04 所有，这些服务尚未开发，后端会标 `available=false`，界面必须显示「未接入」
 * 并把归属服务写出来。把"数据还没接"画成 0，运营会真的以为这个 SKU 断货了或没有定价。
 *
 * <p>属性区显示的是**有效值**（SPU 公共值经 SKU 覆写后的解析结果），来源分四档：
 * SKU 覆写 / SKU 已清空 / SPU 公共值 / 未赋值。只有把来源一起显示出来，运营才能判断
 * "这个值为什么和我填的不一样"——是没填、被清空了，还是继承了公共值。
 */

/** 属性有效值的来源，与后端 EffectiveValues.Source 一一对应。 */
export const attributeSources: Record<string, string> = {
  SKU_OVERRIDE: 'SKU 覆写值',
  SKU_CLEARED: 'SKU 已清空',
  SPU_COMMON: '继承 SPU 公共值',
  UNSET: '未赋值',
};

/** SKU 状态沿用商品档案页的四值域（SPU 才是五态生命周期）。 */
export const skuStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '启用',
  INACTIVE: '停用',
  ARCHIVED: '已归档',
};

export const spuStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_LISTING: '待上架',
  ON_SALE: '在售',
  SUSPENDED: '停售',
  DISCONTINUED: '停产',
};

/** 单位角色标签，与后端 SkuUnitConversion.roleLabel 一致（前端也要能离线读懂历史数据）。 */
export const unitRoleLabels: Record<string, string> = {
  PURCHASE: '采购单位',
  SALE: '销售单位',
  PACKAGING: '包装单位',
};

export function fetchSkuDetail(id: string) {
  return api<SkuDetail>(`catalog/skus/${id}`);
}

export function saveSkuDetail(id: string, input: SkuDetailInput) {
  return api<SkuDetailSaved>(`catalog/skus/${id}`, 'PUT', input);
}

/** 404 说明 SKU 不存在或当前角色没有访问权限——RLS 已经过滤过跨租户的行，不区分两者。 */
export function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

/** 409：乐观锁冲突。界面必须给出"刷新后重试"，而不是让用户以为保存成功了。 */
export function isConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

/**
 * 数量的展示文本。
 *
 * <p>后端给的是 NUMERIC，序列化后是 `250.000000`。直接渲染会让界面上出现一串无意义的零，
 * 所以去掉无意义的末尾小数；但**不四舍五入**——重量与体积重不能被悄悄改掉。
 */
export function decimal(value: number | null | undefined) {
  if (value === null || value === undefined) return '—';
  const text = String(value);
  if (!text.includes('.')) return text;
  return text.replace(/0+$/, '').replace(/\.$/, '');
}

/** 有效值的展示文本。布尔按「是/否」翻译，其余原样。 */
export function effectiveText(attribute: SkuEffectiveAttribute) {
  if (attribute.effectiveValues.length === 0) return '—';
  return attribute.effectiveValues
    .map((value) => (attribute.dataType === 'BOOLEAN' ? (value === 'true' ? '是' : '否') : value))
    .join(' / ');
}

/** 属性按 groupName 分组，保持后端给的 position 顺序。 */
export function groupAttributes(attributes: SkuEffectiveAttribute[]) {
  const groups = new Map<string, SkuEffectiveAttribute[]>();
  for (const attribute of attributes) {
    const key = attribute.groupName || '基本参数';
    const bucket = groups.get(key);
    if (bucket) bucket.push(attribute);
    else groups.set(key, [attribute]);
  }
  return [...groups.entries()].map(([name, items]) => ({ name, items }));
}

/** 包装尺寸：缺一维就不拼"×"，宁可少写也不能拿 0 凑出一个看起来完整的尺寸。 */
export function dimensionText(packaging: SkuPackaging | null) {
  if (!packaging) return '—';
  const sides = [packaging.lengthMm, packaging.widthMm, packaging.heightMm];
  const known = sides.filter((v) => v !== null && v !== undefined);
  if (known.length === 0) return '—';
  return `${known.map((v) => decimal(v)).join(' × ')} mm${known.length < 3 ? '（未采齐）' : ''}`;
}

export function weightText(packaging: SkuPackaging | null) {
  if (!packaging) return '—';
  if (packaging.netWeightG === null && packaging.grossWeightG === null) return '—';
  return `净重 ${decimal(packaging.netWeightG)} g · 毛重 ${decimal(packaging.grossWeightG)} g`;
}

/** 「1 箱 = 12 件」；历史版本额外标出生效区间，单据冻结的是当时的比例。 */
export function unitConversionText(conversion: SkuUnitConversion, baseUnit: string) {
  const window =
    conversion.effectiveTo === null
      ? `${conversion.effectiveFrom} 起`
      : `${conversion.effectiveFrom} ~ ${conversion.effectiveTo}`;
  return `1 ${conversion.unitCode} = ${decimal(conversion.quantityPerBase)} ${baseUnit} · ${window}${
    conversion.current ? '' : '（历史版本）'
  }`;
}

/** 档案是否完全空白——用来区分"加载完成但没内容"与"加载中/出错"。 */
export function isEmptyProfile(detail: SkuDetail) {
  return (
    detail.attributes.length === 0 &&
    detail.specs.length === 0 &&
    detail.unitConversions.length === 0 &&
    detail.packaging === null &&
    detail.media.length === 0 &&
    detail.statusHistory.length === 0
  );
}

/** 编辑草稿：把详情摊平成表单可用的字符串/数字。 */
export type SkuDetailDraft = {
  name: string;
  barcode: string;
  baseUnit: string;
  packagingType: string;
  lengthMm: string;
  widthMm: string;
  heightMm: string;
  netWeightG: string;
  grossWeightG: string;
  version: number;
};

export function detailDraft(detail: SkuDetail): SkuDetailDraft {
  const packaging = detail.packaging;
  return {
    name: detail.name,
    barcode: detail.barcode.primary,
    baseUnit: detail.baseUnit,
    packagingType: packaging?.packagingType ?? '',
    lengthMm:
      packaging?.lengthMm === null || packaging?.lengthMm === undefined
        ? ''
        : decimal(packaging.lengthMm),
    widthMm:
      packaging?.widthMm === null || packaging?.widthMm === undefined
        ? ''
        : decimal(packaging.widthMm),
    heightMm:
      packaging?.heightMm === null || packaging?.heightMm === undefined
        ? ''
        : decimal(packaging.heightMm),
    netWeightG:
      packaging?.netWeightG === null || packaging?.netWeightG === undefined
        ? ''
        : decimal(packaging.netWeightG),
    grossWeightG:
      packaging?.grossWeightG === null || packaging?.grossWeightG === undefined
        ? ''
        : decimal(packaging.grossWeightG),
    version: detail.version,
  };
}

/** 空串 → null（表示"没维护这一项"），其余走数字校验。空串不能变成 0。 */
function quantity(text: string, label: string, errors: string[]): number | null {
  const trimmed = text.trim();
  if (trimmed === '') return null;
  if (!/^\d+(\.\d+)?$/.test(trimmed)) {
    errors.push(`${label}只能是非负数字`);
    return null;
  }
  if (/\.\d{7,}$/.test(trimmed)) {
    errors.push(`${label}最多 6 位小数`);
    return null;
  }
  return Number(trimmed);
}

/** 表单校验。与后端的校验同口径，但前端先挡一次，省掉一次必然失败的往返。 */
export function validateDraft(draft: SkuDetailDraft) {
  const errors: string[] = [];
  if (draft.name.trim() === '') errors.push('SKU 名称不能为空');
  if (draft.name.length > 200) errors.push('SKU 名称最多 200 个字符');
  if (draft.barcode.length > 128) errors.push('条码最多 128 个字符');
  if (draft.baseUnit.trim() === '') errors.push('基础单位不能为空');
  if (draft.packagingType.length > 64) errors.push('包装方式最多 64 个字符');
  const net = quantity(draft.netWeightG, '净重', errors);
  const gross = quantity(draft.grossWeightG, '毛重', errors);
  if (net !== null && gross !== null && gross < net) errors.push('毛重不能小于净重');
  quantity(draft.lengthMm, '长', errors);
  quantity(draft.widthMm, '宽', errors);
  quantity(draft.heightMm, '高', errors);
  return errors;
}

/** 草稿 → 请求体。version 原样带上：这是乐观锁凭据，不能由前端自己算。 */
export function draftToInput(draft: SkuDetailDraft): SkuDetailInput {
  return {
    name: draft.name.trim(),
    barcode: draft.barcode.trim(),
    baseUnit: draft.baseUnit.trim(),
    packagingType: draft.packagingType.trim() === '' ? null : draft.packagingType.trim(),
    lengthMm: nullable(draft.lengthMm),
    widthMm: nullable(draft.widthMm),
    heightMm: nullable(draft.heightMm),
    netWeightG: nullable(draft.netWeightG),
    grossWeightG: nullable(draft.grossWeightG),
    version: draft.version,
  };
}

function nullable(text: string): number | null {
  const trimmed = text.trim();
  return trimmed === '' ? null : Number(trimmed);
}

/** 时间戳展示。后端统一给 UTC ISO 串，这里只做本地化，不改值。 */
export function timestamp(value: string | null | undefined) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN');
}
