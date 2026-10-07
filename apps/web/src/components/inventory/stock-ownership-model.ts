import type {
  OwnershipDriftReport,
  OwnershipReferenceOptions,
  OwnershipReassignRequest,
  OwnershipReassignResult,
  OwnershipRuleInput,
  OwnershipRulePage,
  OwnershipRuleSaved,
  OwnerInventoryReport,
  StockOwnerInput,
  StockOwnerPage,
  StockOwnerSaved,
  StockOwnershipCapabilities,
  StockOwnershipResolved,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 所有权类型与指向类型 —— 取值与后端 `StockOwnershipModels` 的同名枚举一字不差。
 *
 * <p>刻意**不从契约导入**：后端把这两个字段暴露成普通字符串（spec 里就是
 * `{"type":"string"}`），枚举本体只存在于 Java 侧，所以 codegen 永远不会生成这两个类型 ——
 * 去契约里 import 只会得到「无此导出成员」的编译错误。
 */
export type StockOwnerKind = 'SELF' | 'SUPPLIER_CONSIGNMENT' | 'PLATFORM' | 'CONSIGNEE';
export type OwnerRefType = 'LEGAL_ENTITY' | 'SUPPLIER' | 'PLATFORM' | 'INTERNAL';

/**
 * 7.7.1.1 货主与所有权前端模型。
 *
 * <p>界面上最容易出错的几处，这个模块都显式处理了：
 *
 * <ul>
 *   <li><b>"没配过货权"不能画成"自有货"</b>。默认自有是**兜底**，不是判定结果 ——
 *       界面上必须标出这是回落档，否则运营会以为那条寄售规则还生效着。
 *   <li><b>"寄售不进自有资产"要看得见</b>。寄售与自有混在一张表里而没有标记，
 *       等于本模块没有做；所以类型标签与"是否计入自有资产"常驻显示，不藏在详情里。
 *   <li><b>货值口径不可用要说出来</b>。本页只出数量口径。用一个假金额把表填满，
 *       比留空危险得多 —— 界面必须显示"不可用 + 为什么"，而不是画一个 0。
 *   <li><b>存量不跟着新配置改</b>。配完"整仓寄售"之后，库里已有的货仍是原货主；
 *       这一条必须在页面上明写，否则用户会以为配完就生效了。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export interface OwnerListParams {
  ownerKind?: string | undefined;
  status?: string | undefined;
  ownerRefId?: string | undefined;
  ownedAssetOnly?: boolean;
  keyword?: string | undefined;
  page?: number;
  size?: number;
}

export function fetchOwners(params: OwnerListParams): Promise<StockOwnerPage> {
  return api<StockOwnerPage>(`warehouse/inventory/ownership/owners${query(params)}`);
}

export function fetchOwner(id: string) {
  return api<StockOwnerPage['owners'][number]>(`warehouse/inventory/ownership/owners/${id}`);
}

export function createOwner(input: StockOwnerInput): Promise<StockOwnerSaved> {
  return api<StockOwnerSaved>('warehouse/inventory/ownership/owners', 'POST', input);
}

export function updateOwner(id: string, input: StockOwnerInput): Promise<StockOwnerSaved> {
  return api<StockOwnerSaved>(`warehouse/inventory/ownership/owners/${id}`, 'PUT', input);
}

export function setOwnerStatus(id: string, status: string): Promise<StockOwnerSaved> {
  return api<StockOwnerSaved>(
    `warehouse/inventory/ownership/owners/${id}/status?status=${encodeURIComponent(status)}`,
    'POST',
    {},
  );
}

export interface RuleListParams {
  scopeType?: string | undefined;
  ownerId?: string | undefined;
  warehouseId?: string | undefined;
  legalEntityId?: string | undefined;
  skuId?: string | undefined;
  effectiveOnly?: boolean;
  keyword?: string | undefined;
  page?: number;
  size?: number;
}

export function fetchRules(params: RuleListParams): Promise<OwnershipRulePage> {
  return api<OwnershipRulePage>(`warehouse/inventory/ownership/rules${query(params)}`);
}

export function createRule(input: OwnershipRuleInput): Promise<OwnershipRuleSaved> {
  return api<OwnershipRuleSaved>('warehouse/inventory/ownership/rules', 'POST', input);
}

/** 换版 = 收口旧档 + 开新档。没有"就地改"的入口。 */
export function supersedeRule(id: string, input: OwnershipRuleInput): Promise<OwnershipRuleSaved> {
  return api<OwnershipRuleSaved>(`warehouse/inventory/ownership/rules/${id}/versions`, 'POST', input);
}

export function retireRule(id: string): Promise<OwnershipRuleSaved> {
  return api<OwnershipRuleSaved>(`warehouse/inventory/ownership/rules/${id}/retire`, 'POST', {});
}

export function fetchResolved(params: {
  warehouseId?: string | undefined;
  legalEntityId?: string | undefined;
  skuId?: string | undefined;
}): Promise<StockOwnershipResolved> {
  return api<StockOwnershipResolved>(`warehouse/inventory/ownership/resolved${query(params)}`);
}

export function fetchReport(params: {
  warehouseId?: string | undefined;
  legalEntityId?: string | undefined;
  skuId?: string | undefined;
}): Promise<OwnerInventoryReport> {
  return api<OwnerInventoryReport>(`warehouse/inventory/ownership/report${query(params)}`);
}

export function fetchDrift(params: {
  warehouseId?: string | undefined;
  legalEntityId?: string | undefined;
  skuId?: string | undefined;
}): Promise<OwnershipDriftReport> {
  return api<OwnershipDriftReport>(`warehouse/inventory/ownership/drift${query(params)}`);
}

export function fetchReferenceOptions(): Promise<OwnershipReferenceOptions> {
  return api<OwnershipReferenceOptions>('warehouse/inventory/ownership/reference-options');
}

export function reassign(input: OwnershipReassignRequest): Promise<OwnershipReassignResult> {
  return api<OwnershipReassignResult>('warehouse/inventory/ownership/reassign', 'POST', input);
}

export function fetchCapabilities(): Promise<StockOwnershipCapabilities> {
  return api<StockOwnershipCapabilities>('warehouse/inventory/ownership/capabilities');
}

function query(params: object): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 展示

/**
 * codegen 约定：只要 schema 里有任何一个可空字段，整个类型别名就会生成成 `T | null`。
 * 所以凡是遍历接口返回的数组或取子对象，都要先过这道守卫 ——
 * 它同时也是 `noUncheckedIndexedAccess` 下的必需步骤。
 */
export function notNull<T>(value: T | null): value is NonNullable<T> {
  return value !== null && value !== undefined;
}

export const ownerKindLabels: Record<string, string> = {
  SELF: '自有',
  SUPPLIER_CONSIGNMENT: '供应商寄售',
  PLATFORM: '平台所有',
  CONSIGNEE: '代销',
};

export const refTypeLabels: Record<string, string> = {
  LEGAL_ENTITY: '本租户法人',
  SUPPLIER: '供应商（catalog）',
  PLATFORM: '平台（channel）',
  INTERNAL: '本租户自身',
};

export const scopeTypeLabels: Record<string, string> = {
  DEFAULT: '全租户兜底',
  WAREHOUSE: '整仓',
  LEGAL_ENTITY: '按法人',
  SKU: '按 SKU',
};

/** 解析优先级：数字越小越优先（SKU 最具体，DEFAULT 兜底）。与后端保持同一套约定。 */
export const scopePriority = {
  SKU: 0,
  LEGAL_ENTITY: 1,
  WAREHOUSE: 2,
  DEFAULT: 3,
} as const;

/** 每种所有权类型允许的指向类型 —— 与后端同一套规则，用来在建档表单里就地拦下自相矛盾的组合。 */
export function allowedRefTypes(kind: string): OwnerRefType[] {
  switch (kind) {
    case 'SELF':
      return ['LEGAL_ENTITY', 'INTERNAL'];
    case 'SUPPLIER_CONSIGNMENT':
      return ['SUPPLIER'];
    case 'PLATFORM':
      return ['PLATFORM', 'INTERNAL'];
    case 'CONSIGNEE':
      return ['SUPPLIER', 'LEGAL_ENTITY'];
    default:
      return [];
  }
}

/**
 * 货权档的作用域文字（哪个仓 / 哪个法人 / 哪个 SKU）。
 *
 * <p>作用域是这张表的**主键**：两档若作用域不同，它们管的就是不同的货，
 * 把它们并排成"两条规则"会让人以为它们在打架，而实际上它们毫不相干。
 */
export function scopeTargetText(rule: {
  scopeType: string;
  warehouseCode?: string | null;
  legalEntityCode?: string | null;
  skuCode?: string | null;
}): string {
  switch (rule.scopeType) {
    case 'WAREHOUSE':
      return rule.warehouseCode ? `整仓 ${rule.warehouseCode}` : '整仓（未选仓库）';
    case 'LEGAL_ENTITY':
      return rule.legalEntityCode ? `法人 ${rule.legalEntityCode}` : '按法人（未选法人）';
    case 'SKU':
      return rule.skuCode ? `SKU ${rule.skuCode}` : '按 SKU（未选 SKU）';
    default:
      return '全租户兜底';
  }
}

/**
 * 货主标签的视觉档位。
 *
 * <p>只用一档中性 + 一档警示：自有与三类非自有。刻意不给寄售用"错误色" ——
 * 寄售是**正常业务形态**，不是配置错误；把它画成红色只会让人去"修"一个没问题的东西。
 */
export function ownerKindTone(kind: string): 'owned' | 'non-owned' {
  return kind === 'SELF' ? 'owned' : 'non-owned';
}

/** 「是否计入自有资产」的口径文字。财务读的就是这一句。 */
export function ownedAssetText(countsAsOwnedAsset: boolean): string {
  return countsAsOwnedAsset ? '计入自有资产' : '不计入自有资产';
}

export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 });
}

/** 生效区间。左闭右开；未收口就是「至今」。 */
export function intervalText(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  if (!from) return '—';
  const start = formatMoment(from);
  return to ? `${start} ~ ${formatMoment(to)}（已收口）` : `${start} ~ 至今在生效`;
}

export function formatMoment(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * 解析结论的一句话。
 *
 * <p>「回落」与「命中真实配置」必须能被一眼分开：前者意味着这批货的货权是默认值，
 * 用户配的规则可能没生效 —— 这正是最需要被看见的一种状态。
 */
export function resolvedHeadline(resolved: StockOwnershipResolved): string {
  if (!resolved.owner) return '未配置：这批货目前没有货主';
  const who = `${resolved.owner.name}（${ownerKindLabels[resolved.owner.ownerKind] ?? resolved.owner.ownerKind}）`;
  return resolved.usedFallback
    ? `回落默认货主：${who}`
    : `命中${scopeTypeLabels[resolved.matchedScope ?? 'DEFAULT']}档：${who}`;
}

/**
 * 自有与非自有的差额文字。
 *
 * <p>寄售量必须单独成列而不是混在总量里：12 财务只把自有那一列算进资产表，
 * 把两者合成一个"库存总量"显示，等于把本模块最关键的一个区分又抹掉了。
 */
export function nonOwnedText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return qty(value);
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前角色没有货主与货权的维护权限';
    if (error.status === 0) return '正在连接仓库服务，请稍后重试';
    if (error.status === 409) return '与既有货权记录冲突，请刷新后检查输入';
    return error.message;
  }
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}
