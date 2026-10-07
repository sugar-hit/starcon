import type {
  UnitConversionGroup,
  UnitConversionInput,
  UnitConvertResult,
  UnitOfMeasure,
  UnitOfMeasureInput,
  UnitOverview,
  UnitPanel,
  UnitSnapshot,
  UnitSnapshotLine,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.1.2.4 单位与换算前端模型。
 *
 * <p>页面上最容易出错的两处：
 *
 * <ul>
 *   <li>**「没有」不能画成「是 0」**。未登记基础单位的 SKU 数、开放生效的换算版本数，
 *       都必须和自己的含义一起出现；把「字典没覆盖到」画成 0，运营会去改本来正确的比例。
 *   <li>**快照比例要连同区间一起显示**。单据行上的比例是冻结那一刻的值，
 *       只显示基础数量看不出这个数是怎么换出来的，事后对账无从下手。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export function fetchOverview() {
  return api<UnitOverview>('catalog/unit-conversion/overview');
}

export function fetchUnits() {
  return api<UnitOfMeasure[]>('catalog/unit-conversion/units');
}

export function createUnit(input: UnitOfMeasureInput) {
  return api<{ id: string; version: number }>('catalog/unit-conversion/units', 'POST', input);
}

export function updateUnit(id: string, input: UnitOfMeasureInput) {
  return api<{ id: string; version: number }>(`catalog/unit-conversion/units/${id}`, 'PUT', input);
}

export function deleteUnit(id: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/unit-conversion/units/${id}?version=${version}`,
    'DELETE',
  );
}

export function fetchPanel(skuId: string) {
  return api<UnitPanel>(`catalog/unit-conversion/skus/${skuId}`);
}

export function addConversion(skuId: string, input: UnitConversionInput) {
  return api<{ id: string }>(
    `catalog/unit-conversion/skus/${skuId}/conversions`,
    'POST',
    input,
  );
}

/** 基础数量换算是纯计算（不写库），所以用只读权限即可调用。 */
export function convert(input: {
  skuId: string;
  quantity: number;
  unitCode: string;
  role: string | null;
  asOf: string | null;
}) {
  return api<UnitConvertResult>('catalog/unit-conversion/convert', 'POST', input);
}

export function freezeSnapshot(input: {
  documentType: string;
  documentRef: string;
  occurredAt: string | null;
  lines: { lineNo: number; skuId: string; unitCode: string; role: string; quantity: number }[];
}) {
  return api<UnitSnapshot>('catalog/unit-conversion/snapshots', 'POST', input);
}

export function fetchSnapshot(documentRef: string, documentType: string | null) {
  const type = documentType ? `&documentType=${encodeURIComponent(documentType)}` : '';
  return api<UnitSnapshot>(
    `catalog/unit-conversion/snapshots?documentRef=${encodeURIComponent(documentRef)}${type}`,
  );
}

// ------------------------------------------------------------ 错误语义

/** 404：记录不存在或当前角色没有访问权限——RLS 已过滤过跨租户的行，不区分两者。 */
export function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

/** 409：乐观锁冲突，或换算区间重叠、单位被引用不能删。界面必须说明发生了什么。 */
export function isConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

export function errorText(error: unknown, fallback = '操作失败') {
  return error instanceof Error && error.message ? error.message : fallback;
}

// ------------------------------------------------------------ 词表（与后端 UnitRules 同口径）

export const dimensionLabels: Record<string, string> = {
  WEIGHT: '重量',
  LENGTH: '长度',
  VOLUME: '体积',
  COUNT: '数量',
  PACKAGE: '包装',
};

export const dimensions = ['WEIGHT', 'LENGTH', 'VOLUME', 'COUNT', 'PACKAGE'] as const;

export const roleLabels: Record<string, string> = {
  PURCHASE: '采购单位',
  SALE: '销售单位',
  PACKAGING: '包装单位',
  BASE: '基础单位',
};

/** 可写换算表的角色。BASE 是 SKU 自身的属性，比例恒为 1，不占换算行。 */
export const conversionRoles = ['PURCHASE', 'SALE', 'PACKAGING'] as const;

export const documentTypeLabels: Record<string, string> = {
  PURCHASE: '06 采购',
  INBOUND: '07 入库',
  OUTBOUND: '07 出库',
  PICKING: '08 拣货',
  TRANSFER: '07 调拨',
  COUNT: '07 盘点',
};

export const documentTypes = [
  'PURCHASE',
  'INBOUND',
  'OUTBOUND',
  'PICKING',
  'TRANSFER',
  'COUNT',
] as const;

/**
 * 单位码字符集：中文、拉丁字母、数字、下划线、短横线，1~32 位。
 *
 * <p>与内部编码的 [A-Z0-9] 词表**故意不同**——单位码不是内部编码，真实数据里就是
 * '件' / '箱' / '千克'。前端挡错要在提交前，不能等一趟往返。
 */
const UNIT_CODE_PATTERN = /^[\p{Script=Han}A-Za-z0-9][\p{Script=Han}A-Za-z0-9_-]{0,31}$/u;

export function unitCodeProblem(value: string): string | null {
  const code = value.trim();
  if (code === '') return '单位编码不能为空';
  if (!UNIT_CODE_PATTERN.test(code))
    return '单位编码只能使用中文、英文字母、数字、下划线或短横线，长度 1~32 位';
  return null;
}

/** 数量精度 0~6 位；与 numeric(19,6) 的存储精度一致。 */
export function scaleProblem(value: string): string | null {
  if (!/^\d+$/.test(value.trim())) return '数量精度需填 0~6 的整数';
  const scale = Number(value.trim());
  if (scale > 6) return '数量精度最多 6 位小数';
  return null;
}

/**
 * 换算比例的校验。
 *
 * <p>比例必须严格大于 0：0 会让基础数量恒为 0（入库写成 0 件），负数会让出库变成入库。
 * 这两种都是会真实打错账的错误，所以提交前就挡。
 */
export function ratioProblem(value: string): string | null {
  const raw = value.trim();
  if (raw === '') return '换算比例不能为空';
  const ratio = Number(raw);
  if (!Number.isFinite(ratio)) return '换算比例必须是数字';
  if (ratio <= 0) return '换算比例必须大于 0';
  if (/^-?\d*\.\d{7,}$/.test(raw)) return '换算比例最多 6 位小数';
  const [int = '0'] = raw.split('.');
  if (int.replace('-', '').length > 13) return '换算比例的整数部分最多 13 位';
  return null;
}

/** 数量必须为正：0 或负数在单据上没有业务含义。 */
export function quantityProblem(value: string): string | null {
  const raw = value.trim();
  if (raw === '') return '数量不能为空';
  const quantity = Number(raw);
  if (!Number.isFinite(quantity)) return '数量必须是数字';
  if (quantity <= 0) return '数量必须大于 0';
  if (/^-?\d*\.\d{7,}$/.test(raw)) return '数量最多 6 位小数';
  return null;
}

/** 生效区间：截止日不能早于起始日；留空表示"至今仍生效"。 */
export function windowProblem(from: string, to: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from.trim())) return '生效起始日需为 YYYY-MM-DD';
  if (to.trim() === '') return null;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(to.trim())) return '生效截止日需为 YYYY-MM-DD';
  return to.trim() < from.trim() ? '生效截止日不能早于生效起始日' : null;
}

// ------------------------------------------------------------ 展示

/** 数量展示：按单位精度四舍五入。空值画成「—」而不是 0。 */
export function quantityText(value: number | null | undefined, scale = 6): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(Math.max(0, Math.min(6, scale)));
}

/** 生效区间文案。开放区间显式写「至今」，不画成空。 */
export function windowText(from: string | null, to: string | null) {
  if (!from) return '—';
  return `${from} ~ ${to ?? '至今'}`;
}

/** 时间戳展示。后端统一给 UTC ISO 串，这里只做本地化，不改值。 */
export function timestamp(value: string | null | undefined) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN');
}

/**
 * 换算比例的展示文案。
 *
 * <p>比例是「1 个该单位 = 多少个基础单位」，方向搞反会让运营照着填反比例，
 * 所以界面必须把「1 箱 = 12 件」这句话写全，而不是只给一个裸数字。
 */
export function ratioSummary(group: UnitConversionGroup) {
  const unit = group.unitCode;
  if (!group.currentQuantityPerBase)
    return `${group.roleLabel}：当前没有生效版本，换算时会直接报错而不是回退到旧比例`;
  return `1 ${unit} = ${group.currentQuantityPerBase} ${unit} 对应的基础单位数量`;
}

/** 换算计算结果的展示句。命中基础单位时要说清"比例恒为 1"，否则像没换算。 */
export function convertSummary(result: UnitConvertResult) {
  const base = `${result.quantity} ${result.unitCode} = ${result.baseQuantity} ${result.baseUnit}`;
  return result.role === 'BASE' ? `${base}（直接用基础单位，比例恒为 1）` : base;
}

/** 概览里未登记基础单位的提示语；0 与「没查过」不是一回事。 */
export function unregisteredHint(count: number) {
  if (count === 0) return '所有 SKU 的基础单位都已登记到单位字典，量纲校验全量生效。';
  return `${count} 个 SKU 的基础单位不在单位字典里：这些 SKU 无法配置换算（量纲无从校验），请先补登记基础单位。`;
}

// ------------------------------------------------------------ 表单草稿

export type UnitDraft = {
  code: string;
  name: string;
  dimension: string;
  scale: string;
  allowPurchase: boolean;
  allowSale: boolean;
  allowStorage: boolean;
  enabled: boolean;
  version: number;
};

export function emptyUnitDraft(): UnitDraft {
  return {
    code: '',
    name: '',
    dimension: 'COUNT',
    scale: '0',
    allowPurchase: true,
    allowSale: true,
    allowStorage: true,
    enabled: true,
    version: 0,
  };
}

export function unitDraft(unit: UnitOfMeasure): UnitDraft {
  return {
    code: unit.code,
    name: unit.name,
    dimension: unit.dimension,
    scale: String(unit.scale),
    allowPurchase: unit.allowPurchase,
    allowSale: unit.allowSale,
    allowStorage: unit.allowStorage,
    enabled: unit.enabled,
    version: unit.version,
  };
}

/**
 * 单位表单校验。
 *
 * <p>编辑时**不校验 code**：后端已规定单位编码建后不可改（换算行与快照按编码引用它），
 * 前端在编辑态把它锁成只读并说明原因，比提交后再报 400 更早。
 */
export function validateUnit(draft: UnitDraft, isNew: boolean): string[] {
  const errors: string[] = [];
  if (isNew) {
    const codeProblem = unitCodeProblem(draft.code);
    if (codeProblem) errors.push(codeProblem);
  }
  if (draft.name.trim() === '') errors.push('单位名称不能为空');
  else if (draft.name.length > 100) errors.push('单位名称最多 100 个字符');
  if (!dimensions.includes(draft.dimension as (typeof dimensions)[number]))
    errors.push('请选择一个量纲');
  const scaleProblemText = scaleProblem(draft.scale);
  if (scaleProblemText) errors.push(scaleProblemText);
  return errors;
}

export function draftToUnitInput(draft: UnitDraft): UnitOfMeasureInput {
  return {
    code: draft.code.trim(),
    name: draft.name.trim(),
    dimension: draft.dimension,
    scale: Number(draft.scale),
    allowPurchase: draft.allowPurchase,
    allowSale: draft.allowSale,
    allowStorage: draft.allowStorage,
    enabled: draft.enabled,
    version: draft.version,
  };
}

export type ConversionDraft = {
  unitCode: string;
  role: string;
  quantityPerBase: string;
  effectiveFrom: string;
  effectiveTo: string;
};

export function emptyConversionDraft(baseUnit: string): ConversionDraft {
  return {
    unitCode: '',
    role: 'PURCHASE',
    quantityPerBase: '',
    // 默认今天：新建版本最常见的场景就是"从今天起改比例"。
    effectiveFrom: new Date().toISOString().slice(0, 10),
    effectiveTo: '',
  };
}

/**
 * 换算版本表单校验。
 *
 * <p>{@code baseUnit} 参与校验：给基础单位建换算行一定是错的（比例恒为 1），
 * 这里提前拦下并说清楚该改哪里。
 */
export function validateConversion(
  draft: ConversionDraft,
  baseUnit: string,
  registeredUnits: UnitOfMeasure[],
): string[] {
  const errors: string[] = [];
  const unitCode = draft.unitCode.trim();
  if (unitCode === '') errors.push('请选择单位');
  else if (unitCode === baseUnit.trim())
    errors.push(`「${unitCode}」就是该 SKU 的基础单位，比例恒为 1，不需要换算行`);
  else {
    const unit = registeredUnits.find((u) => u.code === unitCode);
    if (!unit) errors.push(`单位「${unitCode}」尚未登记到单位字典，请先在单位字典中新建`);
    else if (!unit.enabled) errors.push(`单位「${unitCode}」已停用，不能再新建换算版本`);
  }
  if (!conversionRoles.includes(draft.role as (typeof conversionRoles)[number]))
    errors.push('请选择换算角色');
  const ratioProblemText = ratioProblem(draft.quantityPerBase);
  if (ratioProblemText) errors.push(ratioProblemText);
  const windowProblemText = windowProblem(draft.effectiveFrom, draft.effectiveTo);
  if (windowProblemText) errors.push(windowProblemText);
  return errors;
}

export function draftToConversionInput(draft: ConversionDraft): UnitConversionInput {
  return {
    unitCode: draft.unitCode.trim(),
    role: draft.role,
    // 字符串先转数字再提交：后端收 BigDecimal，前端不能把 "12" 当字符串发过去。
    quantityPerBase: Number(draft.quantityPerBase),
    effectiveFrom: draft.effectiveFrom.trim(),
    effectiveTo: draft.effectiveTo.trim() === '' ? null : draft.effectiveTo.trim(),
  };
}

/** 快照行的展示：比例与区间必须一起出现，否则事后对账看不出这个数怎么来的。 */
export function snapshotLineText(line: UnitSnapshotLine) {
  return `${line.quantity} ${line.unitCode} × ${line.quantityPerBase} = ${line.baseQuantity} ${line.baseUnit}`;
}
