import type {
  DetailEntryPoint,
  LocationPage,
  WarehouseDetail,
  WarehouseUpdated,
  WarehouseUpdateInput,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from './api';
import { errorText, kindLabel, momentText, qty, statusLabel } from './warehouse-facility-model';

/**
 * 8.1.1.2 仓库详情前端模型。
 *
 * <p>蓝图：「单仓库全景：基础信息与地址、库位结构、库存分布、作业统计、关联的
 * 自发货/中转/FBA 备货策略、作业人员与设备。」「详情页是仓库的控制塔，
 * 作业入口与库存入口都在这里。」
 *
 * <p>控制塔是最容易被「顺手合并」伤害的一页，本模型显式处理了五处：
 *
 * <ul>
 *   <li>**平台仓观测值与自有账本是两个数。**FBA 库存是平台观测值（V55 的纪律），
 *       控制塔上把它们合成一个「总库存」，等于宣告全公司账实相符。
 *   <li>**未接入的维度显示成「未接入」而不是 0。**库位树、上架任务、拣货出库、组装、
 *       备货策略、人员花名册、设备都没有数据表；显示 0 会被读成「这个仓没有」。
 *   <li>**地址缺失要说缺哪几个**，不能画成一行空白。
 *   <li>**归档是终态**：归档后编辑入口整个关掉，而不是提交了再报一个错。
 *   <li>**当地时间是「现在几点」**：IANA ID 与当地时间是两个字段，偏移量不表达夏令时规则。
 * </ul>
 *
 * <p>数量、时刻、状态标签、错误文案都复用 8.1.1.1 的实现而不是各写一份 ——
 * 同一份事实有两套格式化函数，迟早会在某一个模块里漂移。
 */

/**
 * 库位清单的查询条件。
 *
 * <p>本地定义而不是从契约导入：查询条件由 controller 的 {@code @RequestParam} 逐个接收，
 * springdoc 不会为它建 schema，所以契约里没有 {@code LocationQuery}。
 * 8.1.1.1 的 {@code FacilityParams} 是同一个处理。字段名与后端逐条对应。
 */
export interface LocationParams {
  type?: string;
  status?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

const base = 'warehouse/facilities';

export function fetchWarehouseDetail(id: string): Promise<WarehouseDetail> {
  return api<WarehouseDetail>(`${base}/${id}`);
}

export function fetchWarehouseLocations(
  id: string,
  params: LocationParams,
): Promise<LocationPage> {
  return api<LocationPage>(`${base}/${id}/locations${query(params)}`);
}

export function fetchWarehouseEntrypoints(id: string): Promise<DetailEntryPoint[]> {
  return api<DetailEntryPoint[]>(`${base}/${id}/entrypoints`);
}

export interface UpdateWarehouseDraft {
  name: string;
  countryCode: string;
  timezone: string;
  addressLine1: string;
  addressLine2: string;
  city: string;
  region: string;
  postalCode: string;
  status: string;
}

export function draftOf(detail: WarehouseDetail): UpdateWarehouseDraft {
  return {
    name: detail.base.name,
    countryCode: detail.base.countryCode,
    timezone: detail.base.timezone,
    addressLine1: detail.address.line1 ?? '',
    addressLine2: detail.address.line2 ?? '',
    city: detail.address.city ?? '',
    region: detail.address.region ?? '',
    postalCode: detail.address.postalCode ?? '',
    status: detail.base.status,
  };
}

export function updateWarehouse(id: string, draft: UpdateWarehouseDraft): Promise<WarehouseUpdated> {
  const input: WarehouseUpdateInput = {
    name: draft.name.trim(),
    countryCode: draft.countryCode.trim().toUpperCase(),
    timezone: draft.timezone.trim(),
    addressLine1: blank(draft.addressLine1),
    addressLine2: blank(draft.addressLine2),
    city: blank(draft.city),
    region: blank(draft.region),
    postalCode: blank(draft.postalCode),
    status: draft.status || null,
  };
  return api<WarehouseUpdated>(`${base}/${id}`, 'PUT', input);
}

function blank(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 展示

export { errorText, kindLabel, momentText, qty, statusLabel };

/** 当地时间。后端已按仓库时区算好，无法识别时如实说而不是退化成 UTC。 */
export function localTimeText(detail: WarehouseDetail): string {
  if (!detail.base.localTime) return `时区 ${detail.base.timezone} 无法识别`;
  return `${detail.base.localTime}（${detail.base.timezone}）`;
}

/**
 * 地址的展示。
 *
 * <p>缺失字段单独一行说清缺什么：`text` 只有国家码时不能画成「地址：CN」了事 ——
 * 「境外仓不填邮编」与「这家仓压根没填地址」必须能一眼分开。
 */
export function addressText(detail: WarehouseDetail): { value: string; missing: string } {
  const missing = detail.address.missingFields;
  if (missing.length === 0) return { value: detail.address.text ?? '—', missing: '' };
  if (detail.address.text === detail.address.countryCode) {
    // 只有国家码，说明地址整体没填
    return { value: '—', missing: `地址未填写（缺 ${missing.join('、')}）` };
  }
  return { value: detail.address.text ?? '—', missing: `缺 ${missing.join('、')}` };
}

/**
 * 库存量的展示。
 *
 * <p>平台仓返回观测值、自有仓返回 null —— **两者不相加**，所以给两种形状而不是一个总和。
 */
export function stockSourceText(detail: WarehouseDetail): { source: string; note: string } {
  if (detail.stock.platform) {
    return {
      source: `平台观测 ${qty(detail.stock.platform.observedOnHand)}`,
      note: `观测值不计入自有账本 · ${qty(detail.stock.platform.sourceCount)} 个来源 · 采集于 ${momentText(
        detail.stock.platform.latestCollectedAt,
      )}`,
    };
  }
  return { source: '自有账本', note: '权威可用量来源' };
}

/** 质量状态角色的中文名。与后端 inventory_quality_status.role 的三值 CHECK 逐条对应。 */
export function qualityRoleLabel(role: string): string {
  if (role === 'SELLABLE') return '可售';
  if (role === 'PENDING_INSPECTION') return '待检';
  if (role === 'DEFECTIVE') return '残次';
  return role;
}

/** 库位是否可拣：不可拣库位上的货发不出去，这个属性要显示出来而不是只留在库位表里。 */
export function pickableText(pickable: boolean): string {
  return pickable ? '可拣' : '不可拣';
}

/** 空库位与占用库位分开说：「没有库位」与「库位是空的」是两件事。 */
export function locationFillText(total: number, occupied: number): string {
  if (total === 0) return '还没有建库位';
  return `${occupied} / ${total} 个库位有货`;
}

/** 作业人员的三个子维度分别表态：任务是真数，花名册与设备未接入。 */
export function laborTone(available: boolean): 'positive' | 'neutral' {
  return available ? 'positive' : 'neutral';
}

export function laborText(
  taskTotal: number,
  unassigned: number,
  running: number,
  completed: number,
  exception: number,
  operators: number,
): string {
  return [
    `任务 ${qty(taskTotal)}`,
    `在跑 ${qty(running)}`,
    `已完成 ${qty(completed)}`,
    `异常 ${qty(exception)}`,
    `未派工 ${qty(unassigned)}`,
    `在岗 ${qty(operators)} 人`,
  ].join(' · ');
}

/** 入口按可用与否分组：未接入的一组显示成「未接入」并写明归属模块。 */
export function splitEntrypoints(points: DetailEntryPoint[]): {
  available: DetailEntryPoint[];
  unavailable: DetailEntryPoint[];
} {
  return {
    available: points.filter((p) => p.available),
    unavailable: points.filter((p) => !p.available),
  };
}

/**
 * 维护表单的前端校验。
 *
 * <p>界面上先拦住，用户就不用等一个 400。后端仍然独立校验一遍 —— 前端校验是体验，
 * 不是安全边界（后端确实独立拒了偏移量时区：ZoneId.of 接受 "+08:00"，不能只靠它）。
 */
export function updateProblem(draft: UpdateWarehouseDraft): string {
  if (!draft.name.trim()) return '请填写仓库名称';
  if (draft.name.trim().length > 200) return '仓库名称不能超过 200 个字符';
  const country = draft.countryCode.trim().toUpperCase();
  if (!country) return '请填写国家地区';
  if (!/^[A-Z]{2}$/.test(country)) return '国家地区必须是两位字母代码（如 CN、US）';
  if (!draft.timezone.trim()) return '请填写时区';
  if (/^[+-]\d{1,2}(:\d{2})?$/.test(draft.timezone.trim()))
    return '时区要填 IANA ID（如 Asia/Shanghai），偏移量不表达夏令时规则';
  if (draft.status && !['ACTIVE', 'INACTIVE', 'ARCHIVED'].includes(draft.status))
    return '不支持的仓库状态';
  return '';
}

/** 归档是终态：归档后不能再改任何字段，所以编辑入口整个关掉而不是提交了再报错。 */
export function isArchived(status: string): boolean {
  return status === 'ARCHIVED';
}

export const ALL = '';