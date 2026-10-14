import type {
  UnitGroupConversionInput,
  UnitGroupConversionSaved,
  UnitGroupConvertInput,
  UnitGroupConvertResult,
  UnitGroupDetail,
  UnitGroupInput,
  UnitGroupMemberInput,
  UnitGroupOverview,
  UnitGroupPage,
  UnitGroupSaved,
  UnitOfMeasure,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.3.3.1 计量单位（字典视角）前端模型。
 *
 * <p>这个页面上最容易出错的三处：
 *
 * <ul>
 *   <li>**换算判定不能在前端重算**。`denied` / `denyCode` 一律来自后端
 *       （`UnitGroupService.convert` 是唯一实现，它读的是数据库里当天的生效版本）。
 *       在浏览器里再写一份算法，症状是「页面说能换算、接口说不能」，
 *       而换算比例错一次就是一批按错比例记完的库存账。
 *   <li>**原因码与中文提示要一起看**。七条原因码（未登记 / 停用 / 组停用 / 量纲不匹配 /
 *       不同组 / 无生效版本 / 无需换算）对应的处理动作完全不同，
 *       界面上不能都写成「换算失败」——运营看完不知道该做什么。
 *   <li>**单位字典与单位组是两套写入口**。单位字典行的增删改走
 *       `unit-conversion/units`（3.1.2.4 的写入所有权），单位组走 `unit-groups`（本模块）。
 *       这个文件里两组接口并列，但注释说明了各自归属，改的时候别把它们混成一个端点。
 * </ul>
 */

// ------------------------------------------------------------ 接口（单位组，本模块）

export function fetchOverview() {
  return api<UnitGroupOverview>('catalog/unit-groups/overview');
}

export function fetchGroups(query: {
  keyword?: string;
  dimension?: string;
  enabled: boolean | null;
  page: number;
  size: number;
}) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.dimension) params.set('dimension', query.dimension);
  if (query.enabled !== null) params.set('enabled', String(query.enabled));
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<UnitGroupPage>(`catalog/unit-groups?${params.toString()}`);
}

export function fetchGroup(groupId: string, asOf: string) {
  const query = asOf ? `?asOf=${encodeURIComponent(asOf)}` : '';
  return api<UnitGroupDetail>(`catalog/unit-groups/${groupId}${query}`);
}

export function createGroup(input: UnitGroupInput) {
  return api<UnitGroupSaved>('catalog/unit-groups', 'POST', input);
}

export function saveGroup(groupId: string, input: UnitGroupInput) {
  return api<UnitGroupSaved>(`catalog/unit-groups/${groupId}`, 'PUT', input);
}

export function setGroupEnabled(groupId: string, enabled: boolean, version: number) {
  return api<{ enabled: boolean }>(
    `catalog/unit-groups/${groupId}/enabled?enabled=${enabled}&version=${version}`,
    'PUT',
  );
}

export function deleteGroup(groupId: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/unit-groups/${groupId}?version=${version}`, 'DELETE');
}

export function addConversion(groupId: string, input: UnitGroupConversionInput) {
  return api<UnitGroupConversionSaved>(`catalog/unit-groups/${groupId}/conversions`, 'POST', input);
}

/**
 * 删除一条已闭合的组内换算版本。
 *
 * <p>与 3.1.2.4 的单据快照口径相反：组内换算是字典配置，写错了就该删；
 * 单据快照是已发生事实，DB 层禁止删。当前仍开放生效的那一版也删不掉。
 */
export function deleteConversion(groupId: string, conversionId: string) {
  return api<{ deleted: boolean }>(
    `catalog/unit-groups/${groupId}/conversions/${conversionId}`,
    'DELETE',
  );
}

/** 换算查询：蓝图的输出「换算查询能力」。换不动时返回 200 + denied=true + 原因码。 */
export function convertGroup(input: UnitGroupConvertInput) {
  return api<UnitGroupConvertResult>('catalog/unit-groups/convert', 'POST', input);
}

// ------------------------------------------------------------ 接口（单位字典，3.1.2.4 的写入所有权）

/**
 * 单位字典列表。
 *
 * <p>刻意复用 `catalog/unit-conversion/units` 而不是新开一个字典端点：
 * 单位字典的写入所有者是 3.1.2.4 的 `UnitConversionService`，
 * 本模块只读它（量纲 / 名称 / 数量精度）。字典视角页面同时调两个服务是对的，
 * 把字典再写一遍才是错。
 */
export function fetchUnits() {
  return api<UnitOfMeasure[]>('catalog/unit-conversion/units');
}

export function createUnit(input: {
  code: string;
  name: string;
  dimension: string;
  scale: number;
  allowPurchase: boolean;
  allowSale: boolean;
  allowStorage: boolean;
  enabled: boolean;
  version: number;
}) {
  return api<{ id: string; version: number }>('catalog/unit-conversion/units', 'POST', input);
}

export function updateUnit(
  unitId: string,
  input: {
    code: string;
    name: string;
    dimension: string;
    scale: number;
    allowPurchase: boolean;
    allowSale: boolean;
    allowStorage: boolean;
    enabled: boolean;
    version: number;
  },
) {
  return api<{ id: string; version: number }>(
    `catalog/unit-conversion/units/${unitId}`,
    'PUT',
    input,
  );
}

export function deleteUnit(unitId: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/unit-conversion/units/${unitId}?version=${version}`,
    'DELETE',
  );
}

// ------------------------------------------------------------ 原因码

/** 量纲词表。与 V8 的 unit_of_measure CHECK 和 UnitGroupRules.DIMENSIONS 同一口径。 */
export const DIMENSION_LABELS: Record<string, string> = {
  WEIGHT: '重量',
  LENGTH: '长度',
  VOLUME: '体积',
  COUNT: '数量',
  PACKAGE: '包装',
};

export const DIMENSIONS = ['WEIGHT', 'LENGTH', 'VOLUME', 'COUNT', 'PACKAGE'] as const;

/** Set 形态，供运行时判存在用（`DIMENSIONS.includes` 在 `noUncheckedIndexedAccess` 下要收窄类型）。 */
export const DIMENSION_SET: ReadonlySet<string> = new Set(DIMENSIONS);

export function dimensionLabel(dimension: string | null | undefined): string {
  if (!dimension) return '未知';
  return DIMENSION_LABELS[dimension] ?? dimension;
}

/**
 * 拒绝原因码 → 中文标签。与 `UnitGroupRules.DENY_LABELS` 逐字同口径。
 *
 * <p>这七条是后端 `UnitGroupService.convert` 的全部输出。前端只负责把码翻成话，
 * 不新增也不合并——合并会让界面上「量纲不匹配」和「不在同一个组」都变成同一句
 * "换不了"，运营去错地方找。
 */
export const DENY_LABELS: Record<string, string> = {
  UNIT_UNREGISTERED: '单位未登记',
  UNIT_DISABLED: '单位已停用',
  GROUP_DISABLED: '单位组已停用',
  DIMENSION_MISMATCH: '量纲不匹配',
  NOT_SAME_GROUP: '不在同一个单位组',
  NO_EFFECTIVE_VERSION: '没有生效的换算版本',
  SELF_CONVERSION: '无需换算',
};

/**
 * 拒绝原因码 → 运营该做什么。
 *
 * <p>四种原因的处理动作不同，所以文案也不同：
 * 补登记 / 启用 / 改量纲或换单位 / 配换算版本 / 把两个单位放进同一个组。
 */
export const DENY_ACTIONS: Record<string, string> = {
  UNIT_UNREGISTERED: '先在单位字典里登记这个单位',
  UNIT_DISABLED: '先启用这个单位，或改用其它启用的单位',
  GROUP_DISABLED: '先启用包含这两个单位的单位组',
  DIMENSION_MISMATCH: '量纲不同的单位之间不允许换算，请换一个同量纲的目标单位',
  NOT_SAME_GROUP: '把这两个单位放进同一个同量纲的单位组',
  NO_EFFECTIVE_VERSION: '给该单位配置组内换算版本，并让生效区间覆盖这一天',
  SELF_CONVERSION: '',
};

export function denyLabel(code: string | null | undefined): string {
  if (!code) return '';
  return DENY_LABELS[code] ?? '换算失败';
}

export function denyAction(code: string | null | undefined): string {
  if (!code) return '';
  return DENY_ACTIONS[code] ?? '';
}

// ------------------------------------------------------------ 校验（与服务层同口径）

const GROUP_CODE_PATTERN = /^[\p{Script=Han}A-Za-z0-9][\p{Script=Han}A-Za-z0-9_-]{0,31}$/u;

/** 单位组编码校验。与 `UnitGroupRules.codeProblem` 同一套规则（允许中文）。 */
export function groupCodeProblem(value: string): string | null {
  const code = value.trim();
  if (!code) return '单位组编码不能为空';
  if (!GROUP_CODE_PATTERN.test(code))
    return '单位组编码只能使用中文、英文字母、数字、下划线或短横线，长度 1~32 位';
  return null;
}

export function groupNameProblem(value: string): string | null {
  const name = value.trim();
  if (!name) return '单位组名称不能为空';
  if (name.length > 100) return '单位组名称最多 100 个字符';
  return null;
}

/**
 * 组内换算比例校验。与 `UnitGroupRules.ratioProblem` 同一口径。
 *
 * <p>只接受十进制字符串：换算比例是 `numeric(19,6)`，用 Number 解析会先丢精度，
 * 用户填 `0.000001` 变成 `1e-6` 再发出去，服务层会当成一个可疑的比例。
 */
export function ratioProblem(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return '换算比例不能为空';
  if (!/^\d+(\.\d+)?$/.test(trimmed)) return '换算比例请填十进制数字，不支持科学计数法';
  if (/^0*(\.0+)?$/.test(trimmed)) return '换算比例必须大于 0';
  const parts = trimmed.split('.');
  const fraction = parts[1] ?? '';
  if (fraction.length > 6) return '换算比例最多 6 位小数';
  const integer = (parts[0] ?? '').replace(/^0+/, '');
  if (integer.length > 13) return '换算比例的整数部分最多 13 位';
  return null;
}

export function quantityProblem(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return '数量不能为空';
  if (!/^\d+(\.\d+)?$/.test(trimmed)) return '数量请填十进制数字，不支持科学计数法';
  if (/^0*(\.0+)?$/.test(trimmed)) return '数量必须大于 0';
  const fraction = trimmed.split('.')[1] ?? '';
  if (fraction.length > 6) return '数量最多 6 位小数';
  return null;
}

export function windowProblem(from: string, to: string): string | null {
  if (!from.trim()) return '生效起始日不能为空';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from.trim())) return '生效起始日格式需为 yyyy-MM-dd';
  if (to.trim() && !/^\d{4}-\d{2}-\d{2}$/.test(to.trim())) return '生效截止日格式需为 yyyy-MM-dd';
  if (to.trim() && to.trim() < from.trim()) return '生效截止日不能早于生效起始日';
  return null;
}

// ------------------------------------------------------------ 展示

/** 数量按目标单位的 scale 显示；缺失画成「—」而不是 0。 */
export function quantityText(value: number | string | null | undefined, scale = 6): string {
  if (value === null || value === undefined || value === '') return '—';
  const numeric = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(numeric)) return String(value);
  return numeric.toFixed(Math.max(0, Math.min(6, scale)));
}

export function windowText(from: string | null, to: string | null | undefined): string {
  if (!from) return '—';
  return to ? `${from} ~ ${to}` : `${from} ~ 至今`;
}

export function timestamp(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toISOString().slice(0, 16).replace('T', ' ') + ' UTC';
}

/** 换算结果的一句话摘要。成功与被拒都能读，不把原因码原样丢给用户。 */
export function convertSummary(result: UnitGroupConvertResult | null): string {
  if (!result) return '';
  if (result.denied) return `${denyLabel(result.denyCode)}：${result.denyMessage ?? ''}`;
  const from = quantityText(result.convertedQuantity);
  return `${result.quantity} ${result.fromUnitCode} = ${from} ${result.toUnitCode}`;
}

/** 组内成员按 position 排序后的可选项（下拉用）。 */
export function memberOptions(detail: UnitGroupDetail | null) {
  if (!detail) return [];
  return detail.members.map((member) => ({
    value: member.unitCode,
    label: `${member.unitCode}${member.unitName ? `（${member.unitName}）` : ''}`,
    base: member.base,
    enabled: member.unitEnabled,
  }));
}

export function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

export function isConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

/** 错误文案。403/404 要分开说，否则运营会以为是自己的数据有问题。 */
export function errorText(error: unknown, fallback = '操作失败') {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前角色没有此操作权限';
    if (error.status === 404) return '记录不存在或已删除，请刷新后重试';
    return error.message || fallback;
  }
  return error instanceof Error ? error.message : fallback;
}

// ------------------------------------------------------------ 表单草稿

export type GroupDraft = {
  code: string;
  name: string;
  dimension: string;
  baseUnitCode: string;
  note: string;
  enabled: boolean;
  /**
   * 成员。契约里数组元素带 `| null`（Jackson 允许显式 null），
   * 但草稿里我们从不塞 null，所以用 NonNullable 收窄——
   * 否则整条链路上每个访问点都要判一遍 null，而那永远不成立。
   */
  members: NonNullable<UnitGroupMemberInput>[];
  version: number;
};

export function emptyGroupDraft(): GroupDraft {
  return {
    code: '',
    name: '',
    dimension: 'WEIGHT',
    baseUnitCode: '',
    note: '',
    enabled: true,
    members: [],
    version: 0,
  };
}

/**
 * 从详情回填编辑草稿。
 *
 * <p>成员要带上 position：整体替换语义下，前端必须先取回当前全集再算差集，
 * 少发一条就是静默的成员丢失。
 */
export function groupDraft(detail: UnitGroupDetail): GroupDraft {
  return {
    code: detail.group.code,
    name: detail.group.name,
    dimension: detail.group.dimension,
    baseUnitCode: detail.group.baseUnitCode,
    note: detail.group.note,
    enabled: detail.group.enabled,
    members: detail.members.map((member) => ({
      unitCode: member.unitCode,
      position: member.position,
    })),
    version: detail.group.version,
  };
}

/**
 * 单位组表单校验。返回全部问题（不是第一条），一次改完比逐条试省事。
 *
 * <p>编辑时**不校验 code**：后端已规定编码建后不可改（下游按编码引用换算关系），
 * 前端在编辑态把它锁成只读并说明原因，比提交后再报 400 更早。
 */
export function validateGroup(draft: GroupDraft, isNew: boolean): string[] {
  const errors: string[] = [];
  if (isNew) {
    const codeProblem = groupCodeProblem(draft.code);
    if (codeProblem) errors.push(codeProblem);
  }
  const nameProblem = groupNameProblem(draft.name);
  if (nameProblem) errors.push(nameProblem);
  if (!DIMENSION_SET.has(draft.dimension)) errors.push('请选择一个量纲');
  if (draft.note.trim().length > 500) errors.push('备注最多 500 个字符');
  if (draft.members.length === 0) errors.push('至少要有一个成员单位');
  if (draft.members.length > 50) errors.push('一个单位组最多 50 个成员');
  if (!draft.baseUnitCode.trim()) errors.push('必须选择一个组基础单位');
  else if (!draft.members.some((member) => member.unitCode === draft.baseUnitCode.trim()))
    // 基准单位必须在本组成员里：它不占换算行（比例恒为 1），但组内比例都是相对它定义的。
    errors.push('组基础单位必须在本组成员里');
  const seen = new Set<string>();
  for (const member of draft.members) {
    const unitCode = member.unitCode.trim();
    if (!unitCode) errors.push('成员单位编码不能为空');
    else if (seen.has(unitCode)) errors.push(`成员单位重复：${unitCode}`);
    seen.add(unitCode);
  }
  return errors;
}

export function draftToGroupInput(draft: GroupDraft): UnitGroupInput {
  return {
    code: draft.code.trim(),
    name: draft.name.trim(),
    dimension: draft.dimension,
    baseUnitCode: draft.baseUnitCode.trim(),
    note: draft.note.trim(),
    enabled: draft.enabled,
    members: draft.members.map((member, index) => ({
      unitCode: member.unitCode.trim(),
      position: member.position ?? index,
    })),
    version: draft.version,
  };
}

export type ConvertDraft = {
  fromUnitCode: string;
  toUnitCode: string;
  quantity: string;
  asOf: string;
};

export function emptyConvertDraft(): ConvertDraft {
  return { fromUnitCode: '', toUnitCode: '', quantity: '1', asOf: '' };
}

export function validateConvert(draft: ConvertDraft): string[] {
  const errors: string[] = [];
  if (!draft.fromUnitCode.trim()) errors.push('请选择源单位');
  if (!draft.toUnitCode.trim()) errors.push('请选择目标单位');
  const quantity = quantityProblem(draft.quantity);
  if (quantity) errors.push(quantity);
  if (draft.asOf.trim() && !/^\d{4}-\d{2}-\d{2}$/.test(draft.asOf.trim()))
    errors.push('基准日格式需为 yyyy-MM-dd');
  return errors;
}

export function draftToConvertInput(draft: ConvertDraft): UnitGroupConvertInput {
  return {
    fromUnitCode: draft.fromUnitCode.trim(),
    toUnitCode: draft.toUnitCode.trim(),
    quantity: Number(draft.quantity.trim()),
    asOf: draft.asOf.trim() ? draft.asOf.trim() : null,
  };
}

export type GroupConversionDraft = {
  unitCode: string;
  quantityPerBase: string;
  effectiveFrom: string;
  effectiveTo: string;
  note: string;
};

export function emptyGroupConversionDraft(baseUnitCode: string): GroupConversionDraft {
  return {
    unitCode: '',
    quantityPerBase: '',
    effectiveFrom: new Date().toISOString().slice(0, 10),
    effectiveTo: '',
    note: '',
  };
}

export function validateGroupConversion(
  draft: GroupConversionDraft,
  baseUnitCode: string,
): string[] {
  const errors: string[] = [];
  if (!draft.unitCode.trim()) errors.push('请选择要配比例的成员单位');
  else if (draft.unitCode.trim() === baseUnitCode)
    // 基准单位比例恒为 1，不占换算行；多存一行会让同一个事实有两个来源。
    errors.push('组基础单位的比例恒为 1，不需要换算版本');
  const ratio = ratioProblem(draft.quantityPerBase);
  if (ratio) errors.push(ratio);
  const window = windowProblem(draft.effectiveFrom, draft.effectiveTo);
  if (window) errors.push(window);
  if (draft.note.trim().length > 500) errors.push('备注最多 500 个字符');
  return errors;
}

export function draftToGroupConversionInput(draft: GroupConversionDraft): UnitGroupConversionInput {
  return {
    unitCode: draft.unitCode.trim(),
    // 按字符串直接转，避免 Number 丢掉 6 位小数以下的精度。
    quantityPerBase: Number(draft.quantityPerBase.trim()),
    effectiveFrom: draft.effectiveFrom.trim(),
    effectiveTo: draft.effectiveTo.trim() ? draft.effectiveTo.trim() : null,
    note: draft.note.trim(),
  };
}

/**
 * 候选成员单位：字典里启用、且与组同量纲的单位。
 *
 * <p>量纲过滤放在候选里而不是提交时才报错：运营在多选框里就能看出
 * 「件」不能进重量组，不用提交后读一段错误消息再回头找。
 */
export function linkableUnits(units: UnitOfMeasure[], dimension: string): UnitOfMeasure[] {
  return units.filter((unit) => unit.dimension === dimension && unit.enabled);
}
