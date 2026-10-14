import type {
  CountryOption,
  FacilityOptions,
  WarehouseCreateInput,
  WarehouseCreated,
  WarehouseFacilityPage,
  WarehouseRow,
  WorkVolumeCapability,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 8.1.1.1 仓库列表前端模型。
 *
 * <p>蓝图：「展示租户下所有仓库：类型（自营/三方/海外仓/平台仓/监管仓）、所属法人、国家地区、
 * 时区、启用状态、库存量与作业量，支持筛选与新增。」
 *
 * <p>界面上最容易出错、本模块显式处理了几处：
 *
 * <ul>
 *   <li>**平台仓与自有仓是两个数，不是一个数。**FBA 库存是平台观测值（V55 的纪律），
 *       与自有账本在任何地方都不相加。显示成一个「总库存」列会让人以为这是账实相符的数字。
 *   <li>**未接入的作业维度要显示成「未接入」而不是 0。**收货（8.2）/ 拣货出库（8.3）/
 *       组装（8.4）尚未交付，显示 0 会被读成「这个仓不作业」。
 *   <li>**当地时间是「现在几点」，不是时区标签。**IANA 时区 ID 与当地时间是两个字段：
 *       裸偏移量不表达夏令时规则，「+08:00」回答不了「这周该不该切夏令时」。
 *   <li>**未绑定法人要说未绑定。**货权绑定是财务前提，0 绑定时不能画成「法人数 0」了事。
 * </ul>
 */

/**
 * 仓库类型。
 *
 * <p>契约里它是 {@code WarehouseRow.kind} 的内联联合（codegen 不为它单列一个 type），
 * 所以这里从行类型反推，保证前后端取值集合不会各写一份而漂移。
 */
export type WarehouseKind = WarehouseRow['kind'];

/** 仓库类型的中文名。与后端 {@code WarehouseKind.label()} 逐条一致，这里独立维护一份是为了离线渲染。 */
export const kindLabels: Record<WarehouseKind, string> = {
  LOCAL: '自营仓',
  OVERSEAS: '海外仓',
  PLATFORM_FBA: '平台仓',
  THIRD_PARTY: '三方仓',
  BONDED: '监管仓',
};

/** 只有平台仓的数量来自观测值，不进自有账本。 */
export const platformObservedKinds: readonly WarehouseKind[] = ['PLATFORM_FBA'];

export function kindLabel(kind: WarehouseKind): string {
  return kindLabels[kind] ?? kind;
}

export function isPlatformObserved(kind: WarehouseKind): boolean {
  return platformObservedKinds.includes(kind);
}

export interface FacilityParams {
  kind?: string;
  legalEntityId?: string;
  countryCode?: string;
  status?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
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

// ------------------------------------------------------------ 接口

const base = 'warehouse/facilities';

export function fetchWarehouses(params: FacilityParams): Promise<WarehouseFacilityPage> {
  return api<WarehouseFacilityPage>(`${base}${query(params)}`);
}

export function fetchFacilityOptions(): Promise<FacilityOptions> {
  return api<FacilityOptions>(`${base}/options`);
}

export function fetchWorkCapabilities(): Promise<WorkVolumeCapability[]> {
  return api<WorkVolumeCapability[]>(`${base}/capabilities`);
}

export interface CreateWarehouseDraft {
  code: string;
  name: string;
  kind: WarehouseKind | '';
  countryCode: string;
  timezone: string;
  defaultLegalEntityId: string;
  status: string;
  isDefault: boolean;
}

export function createWarehouse(draft: CreateWarehouseDraft): Promise<WarehouseCreated> {
  const input: WarehouseCreateInput = {
    code: draft.code.trim(),
    name: draft.name.trim(),
    kind: draft.kind,
    countryCode: draft.countryCode.trim().toUpperCase(),
    timezone: draft.timezone.trim(),
    defaultLegalEntityId: draft.defaultLegalEntityId,
    status: draft.status || null,
    isDefault: draft.isDefault,
  };
  return api<WarehouseCreated>(`${base}`, 'POST', input);
}

// ------------------------------------------------------------ 展示

/**
 * 数量格式化。数量一律是后端返回的 number（BigDecimal 序列化的结果），
 * 这里只做显示；**不做单位换算** —— 仓库列表的单位就是件/个。
 */
export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 4 });
}

/**
 * 时刻文本：**按该时刻自带的偏移量**渲染，不做任何时区换算。
 *
 * <p>后端返回的是 {@code OffsetDateTime}，偏移量是采集器/写入方**实际记录**的偏移，
 * 不是需要前端去猜的东西 —— 所以这里只把 ISO 串压成可读形状，偏移原样留着。
 * 换算成「本地时间」才是猜测：那会让一个在 {@code +08:00} 记录的时刻被显示成 UTC 的墙上值。
 *
 * <p>形状不符合预期时返回原串：宁可显示一个能被用户原样报给后端的串，
 * 也不要显示一个错位的「看起来对」的时间。
 */
export function momentText(iso: string | null | undefined): string {
  if (!iso) return '—';
  const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})(?::\d{2})?(Z|[+-]\d{2}:\d{2})?$/.exec(iso);
  if (!m) return iso;
  const [, day, hm, offset] = m;
  return `${day} ${hm}${offset ? ` ${offset}` : ''}`;
}

/** 当地时间。后端已经按仓库时区算好，缺值时如实说「时区无法识别」而不是退化成 UTC。 */
export function localTimeText(row: WarehouseRow): string {
  if (!row.localTime) return `时区 ${row.timezone} 无法识别`;
  return `${row.localTime}（${row.timezone}）`;
}

/**
 * 货权法人的展示文案。
 *
 * <p>未绑定时显示「未绑定法人」而不是空白：`legalEntityCount` 为 0 时是「这个仓没有任何货」，
 * 与「这个仓有货但没绑定法人」是两件完全不同的事，必须分开说。
 */
export function legalEntityText(row: WarehouseRow): string {
  if (row.legalEntityCode) return `${row.legalEntityCode} · ${row.legalEntityName ?? ''}`.trim();
  return row.legalEntityCount > 0 ? `未绑定法人（已持有 ${row.legalEntityCount} 个法人的货）` : '未绑定法人';
}

/** 作业量的一句话摘要。已接入的维度给数，未接入的在标题里由调用方单独说明。 */
export function workText(row: WarehouseRow): string {
  const parts = [
    `盘点 ${qty(row.work.stocktakeTaskCount)}`,
    `调入 ${qty(row.work.transferInCount)}`,
    `调出 ${qty(row.work.transferOutCount)}`,
    `库存变动 ${qty(row.work.inventoryMovementCount)}`,
  ];
  return parts.join(' · ');
}

/**
 * 库存量的展示。
 *
 * <p>平台仓返回的是观测值（{@code platformStock}），自有仓返回 null ——
 * **两者不相加**。这里给两种形状而不是给一个总和。
 */
export interface StockDisplay {
  onHand: string;
  available: string;
  source: 'ledger' | 'observed';
  note: string;
}

export function stockDisplay(row: WarehouseRow): StockDisplay {
  if (row.platformStock) {
    return {
      onHand: qty(row.platformStock.observedOnHand),
      available: qty(row.platformStock.observedAvailable),
      source: 'observed',
      note: `平台仓观测值 · ${qty(row.platformStock.sourceCount)} 个来源 · 采集于 ${momentText(
        row.platformStock.latestCollectedAt,
      )}（不计入自有账本）`,
    };
  }
  return {
    onHand: qty(row.stock.onHand),
    available: qty(row.stock.available),
    source: 'ledger',
    note: '自有账本',
  };
}

export const statusLabels: Record<string, string> = {
  ACTIVE: '启用中',
  INACTIVE: '已停用',
  ARCHIVED: '已归档',
};

export function statusLabel(status: string): string {
  return statusLabels[status] ?? status;
}

/** 归档仓不该再被当成可用仓推给用户。 */
export function statusTone(status: string): 'positive' | 'negative' | 'neutral' {
  if (status === 'ACTIVE') return 'positive';
  if (status === 'ARCHIVED') return 'negative';
  return 'neutral';
}

/** 国家下拉只列本租户真实在用的国家；没有数据时如实说「先建一个仓库」。 */
export function countryOptions(countries: CountryOption[]): Array<{ value: string; label: string }> {
  return countries.map((c) => ({ value: c.code, label: c.code }));
}

/**
 * 新增表单的前端校验。
 *
 * <p>界面上先拦住，用户就不用等一个 400。后端仍然独立校验一遍 —— 前端校验是体验，
 * 不是安全边界。
 */
export function createProblem(draft: CreateWarehouseDraft): string {
  if (!draft.code.trim()) return '请填写仓库编码';
  if (draft.code.trim().length > 64) return '仓库编码不能超过 64 个字符';
  if (!draft.name.trim()) return '请填写仓库名称';
  if (!draft.kind) return '请选择仓库类型';
  const country = draft.countryCode.trim().toUpperCase();
  if (!country) return '请填写国家地区';
  if (!/^[A-Z]{2}$/.test(country)) return '国家地区必须是两位字母代码（如 CN、US）';
  if (!draft.timezone.trim()) return '请填写时区';
  // 常见写法的友好提示：填了偏移量而不是 IANA ID 的用户很多。
  if (/^[+-]\d{1,2}(:\d{2})?$/.test(draft.timezone.trim()))
    return '时区要填 IANA ID（如 Asia/Shanghai），偏移量不表达夏令时规则';
  if (!draft.defaultLegalEntityId) return '请选择所属法人：仓库绑定法人是货权与收入归属的前提';
  return '';
}

/** 把后端返回的错误翻译成能直接显示的话。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 0) return '网络不可用，请检查连接后重试';
    if (error.status === 403) return '当前角色没有仓库档案的权限';
    return error.message || '操作失败，请稍后重试';
  }
  if (error instanceof Error) return error.message;
  return '未知错误，请稍后重试';
}

/** 筛选下拉的「全部」选项。 */
export const ALL = '';