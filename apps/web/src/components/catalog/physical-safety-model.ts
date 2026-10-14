import type {
  PhysicalSafetyProfile,
  PhysicalSafetyRow,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.7.2.1 物理与安全属性前端模型。
 *
 * <p>界面纪律（与后端 {@code PhysicalSafetyService} 同源，规则编号一致）：
 *
 * <ul>
 *   <li><b>R1 缺一维不给体积</b>：{@code volumeCm3} 为 null 时显示「缺长宽高」，
 *       绝不用 0 顶替 —— 0 体积会让渠道按「小件」给运费。
 *   <li><b>R2 未采集 ≠ 都不带</b>：界面上「未采集」是一个<b>要引导去补采集</b>的状态，
 *       用警示色；「已采集 · 无特殊属性」才是可以放行的状态。两者文案与语气都不能一样。
 *   <li><b>R3 除数是渠道口径</b>：除数由运营/调用方选，不写死成默认渠道；
 *       不选除数就不显示体积重，并把「为什么不显示」讲出来。
 * </ul>
 */

const ROOT = 'catalog/physical-safety';

export function profilePath(skuId: string) {
  return `${ROOT}/skus/${encodeURIComponent(skuId)}`;
}

/** 除数可空：不传就不给体积重（后端不替渠道选口径）。 */
export function fetchProfile(skuId: string, divisorCm3PerKg?: string) {
  const query = divisorCm3PerKg ? `?divisorCm3PerKg=${encodeURIComponent(divisorCm3PerKg)}` : '';
  return api<PhysicalSafetyProfile>(`${profilePath(skuId)}${query}`);
}

export function saveProfile(skuId: string, body: unknown) {
  return api<{ profile: PhysicalSafetyProfile; packagingChanged: boolean }>(
    profilePath(skuId),
    'PUT',
    body,
  );
}

/**
 * 按安全属性筛选。
 *
 * <p>三个标记都传 `true` / `false` / 不传（undefined → 不筛）——运营会问
 * 「哪些货**不是**危险品」，只支持筛 true 就答不了。
 */
export function searchProfiles(params: {
  keyword?: string;
  containsBattery?: boolean;
  containsLiquid?: boolean;
  dangerousGoods?: boolean;
  isMagnetic?: boolean;
  safetyCollected?: boolean;
  requiresDeclaration?: boolean;
  packagingType?: string;
  page?: number;
  size?: number;
}) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return api<{
    items: PhysicalSafetyRow[];
    page: number;
    size: number;
    total: number;
    totalPages: number;
  }>(`${ROOT}/profiles${suffix ? `?${suffix}` : ''}`);
}

/** 渠道可用性判定：渠道限制由调用方给，本模块不建渠道表。 */
export function verifyChannel(skuId: string, body: unknown) {
  return api<{
    shippable: boolean;
    blockers: string[];
    blockerLabels: string[];
    declarations: string[];
    declarationLabels: string[];
    note: string;
  }>(`${profilePath(skuId)}/channel-verdict`, 'POST', body);
}

// ------------------------------------------------------------ 展示语义

/** 四个安全标记的键，与后端 {@code PhysicalSafetyRules.SAFETY_FLAGS} 一一对应。 */
export const SAFETY_FLAGS = [
  { key: 'containsBattery', code: 'contains_battery', label: '带电（含电池）' },
  { key: 'containsLiquid', code: 'contains_liquid', label: '液体' },
  { key: 'dangerousGoods', code: 'dangerous_goods', label: '危险品' },
  { key: 'isMagnetic', code: 'is_magnetic', label: '磁性' },
] as const;

export type SafetyFlagKey = (typeof SAFETY_FLAGS)[number]['key'];

export function flagLabel(code: string): string {
  return SAFETY_FLAGS.find((flag) => flag.code === code)?.label ?? code;
}

/**
 * 数字展示。
 *
 * <p>null 显示「—」而不是 0：后端对「算不出来」一律返回 null，界面把它渲染成 0
 * 就等于凭空造了一个数字（规则 R1）。
 */
export function decimal(value: number | null | undefined, unit = ''): string {
  if (value === null || value === undefined) return '—';
  const text = String(value);
  const trimmed = text.includes('.') ? text.replace(/0+$/, '').replace(/\.$/, '') : text;
  return unit ? `${trimmed} ${unit}` : trimmed;
}

/** 体积展示：缺一维时明确说「缺长宽高」，不给一个 0。 */
export function volumeText(profile: {
  volumeCm3: number | null;
  lengthMm: number | null;
  widthMm: number | null;
  heightMm: number | null;
}): string {
  if (profile.volumeCm3 !== null) return `${decimal(profile.volumeCm3)} cm³`;
  const missing = [
    ['长', profile.lengthMm],
    ['宽', profile.widthMm],
    ['高', profile.heightMm],
  ]
    .filter(([, value]) => value === null || value === undefined)
    .map(([label]) => label);
  return missing.length > 0 ? `缺${missing.join('、')}，算不出体积` : '—';
}

/** 体积重展示：不选除数时说明「除数是渠道口径」，不默默套一个默认值（规则 R3）。 */
export function volumetricText(volumetricWeightKg: number | null, divisor: string): string {
  if (volumetricWeightKg !== null && divisor.trim() !== '') {
    return `${decimal(volumetricWeightKg, 'kg')}（除数 ${divisor.trim()}）`;
  }
  if (volumetricWeightKg !== null) return decimal(volumetricWeightKg, 'kg');
  return divisor.trim() === ''
    ? '未选除数，不给体积重（除数是渠道口径）'
    : '缺长宽高，算不出体积重';
}

/**
 * 安全属性的状态标签（规则 R2）。
 *
 * <p>三种状态三种说法与语气：`未采集` 是 warning（要去补采集），
 * `已采集 · 无特殊属性` 是 success-ish neutral（确认过、可放行），
 * 有标记时逐个列出来。
 */
export function safetyState(profile: {
  safetyCollected: boolean;
  containsBattery: boolean;
  containsLiquid: boolean;
  dangerousGoods: boolean;
  isMagnetic: boolean;
}): { label: string; tone: 'warning' | 'neutral' | 'danger'; active: string[] } {
  if (!profile.safetyCollected) return { label: '未采集', tone: 'warning', active: [] };
  const active = SAFETY_FLAGS.filter((flag) => profile[flag.key]).map((flag) => flag.code);
  if (active.length === 0) return { label: '已采集 · 无特殊属性', tone: 'neutral', active: [] };
  return { label: active.map(flagLabel).join(' / '), tone: 'danger', active };
}

/** 只读角色：写操作按权限收敛，而不是等点了才 403。 */
export function writable(permissions: readonly string[]): boolean {
  return permissions.includes('catalog.compliance.update');
}

// ------------------------------------------------------------ 草稿与校验

export type SafetyDraft = {
  lengthMm: string;
  widthMm: string;
  heightMm: string;
  netWeightG: string;
  grossWeightG: string;
  packagingType: string;
  safetyCollected: boolean;
  containsBattery: boolean;
  containsLiquid: boolean;
  dangerousGoods: boolean;
  isMagnetic: boolean;
  dangerousSubstanceClass: string;
  safetyNote: string;
  safetyVersion: number;
  packagingVersion: number;
};

export function draftFrom(profile: PhysicalSafetyProfile): SafetyDraft {
  return {
    lengthMm: profile.lengthMm === null ? '' : String(profile.lengthMm),
    widthMm: profile.widthMm === null ? '' : String(profile.widthMm),
    heightMm: profile.heightMm === null ? '' : String(profile.heightMm),
    netWeightG: profile.netWeightG === null ? '' : String(profile.netWeightG),
    grossWeightG: profile.grossWeightG === null ? '' : String(profile.grossWeightG),
    packagingType: profile.packagingType,
    safetyCollected: profile.safetyCollected,
    containsBattery: profile.containsBattery,
    containsLiquid: profile.containsLiquid,
    dangerousGoods: profile.dangerousGoods,
    isMagnetic: profile.isMagnetic,
    // 备注与包装版本号都必须**从服务端回填**：
    //   * 备注写成 '' 会让「打开页面看一眼就点保存」把已填的备注静默清空；
    //   * packagingVersion 恒为 0 会让第二次保存永远撞不上乐观锁 ——
    //     第一次写完 sku_package.version 就变成 1，此后每次提交都拿 0 去 CAS，必然 409。
    dangerousSubstanceClass: profile.dangerousSubstanceClass,
    safetyNote: profile.safetyNote,
    safetyVersion: profile.safetyVersion ?? 0,
    packagingVersion: profile.packagingVersion ?? 0,
  };
}

/**
 * 草稿校验。
 *
 * <p>「勾了标记却没确认已采集」在这里就拦下：那是自相矛盾的提交，
 * 放行会让渠道看到「带电」而运营以为自己没填。
 */
export function validateDraft(draft: SafetyDraft): string[] {
  const errors: string[] = [];
  const net = quantity(draft.netWeightG, '净重', errors);
  const gross = quantity(draft.grossWeightG, '毛重', errors);
  if (net !== null && gross !== null && gross < net) errors.push('毛重不能小于净重');
  quantity(draft.lengthMm, '长', errors);
  quantity(draft.widthMm, '宽', errors);
  quantity(draft.heightMm, '高', errors);
  if (draft.packagingType.trim().length > 64) errors.push('包装方式最多 64 个字符');
  if (draft.safetyNote.trim().length > 1000) errors.push('安全属性备注最多 1000 个字符');
  const anyFlag =
    draft.containsBattery || draft.containsLiquid || draft.dangerousGoods || draft.isMagnetic;
  if (anyFlag && !draft.safetyCollected)
    errors.push('勾选了安全标记就必须同时确认「已采集安全属性」');
  // 规则 B3：与后端 PhysicalSafetyService.validate 同一套判定，两个方向都拦。
  const substance = draft.dangerousSubstanceClass.trim();
  if (substance.length > 64) errors.push('危险品类别最多 64 个字符');
  if (draft.dangerousGoods && substance === '')
    errors.push('勾了危险品就必须填写危险品类别：16 合规要按类别决定申报路径与所需证书');
  if (!draft.dangerousGoods && substance !== '') errors.push('没有勾危险品就不该填危险品类别');
  return errors;
}

/** 空串 → null（未填）；非数字 → 记一条错误并返回 null，不让 NaN 提交上去。 */
function quantity(text: string, label: string, errors: string[]): number | null {
  const trimmed = text.trim();
  if (trimmed === '') return null;
  const value = Number(trimmed);
  if (!Number.isFinite(value)) {
    errors.push(`${label}必须是数字`);
    return null;
  }
  if (value < 0) {
    errors.push(`${label}不能为负数`);
    return null;
  }
  if (!/^\d+(\.\d{1,6})?$/.test(trimmed)) {
    errors.push(`${label}最多 6 位小数`);
    return null;
  }
  return value;
}

export type SafetyPayload = {
  lengthMm: number | null;
  widthMm: number | null;
  heightMm: number | null;
  netWeightG: number | null;
  grossWeightG: number | null;
  packagingType: string;
  safetyCollected: boolean;
  containsBattery: boolean;
  containsLiquid: boolean;
  dangerousGoods: boolean;
  isMagnetic: boolean;
  dangerousSubstanceClass: string;
  safetyNote: string;
  safetyVersion: number;
  packagingVersion: number;
};

export function draftToPayload(draft: SafetyDraft): SafetyPayload {
  return {
    lengthMm: nullableNumber(draft.lengthMm),
    widthMm: nullableNumber(draft.widthMm),
    heightMm: nullableNumber(draft.heightMm),
    netWeightG: nullableNumber(draft.netWeightG),
    grossWeightG: nullableNumber(draft.grossWeightG),
    packagingType: draft.packagingType.trim(),
    safetyCollected: draft.safetyCollected,
    containsBattery: draft.containsBattery,
    containsLiquid: draft.containsLiquid,
    dangerousGoods: draft.dangerousGoods,
    isMagnetic: draft.isMagnetic,
    dangerousSubstanceClass: draft.dangerousSubstanceClass.trim(),
    safetyNote: draft.safetyNote.trim(),
    safetyVersion: draft.safetyVersion,
    packagingVersion: draft.packagingVersion,
  };
}

function nullableNumber(text: string): number | null {
  const trimmed = text.trim();
  return trimmed === '' ? null : Number(trimmed);
}

// ------------------------------------------------------------ 错误处理

export function errorText(error: unknown, fallback = '操作失败'): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 404：SKU 不存在，或当前租户看不到它。RLS 已过滤，不区分两者。 */
export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/** 409：乐观锁撞了（被他人修改）。刷新后重试。 */
export function isConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}
