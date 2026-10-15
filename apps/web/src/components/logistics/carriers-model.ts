import { api } from '../inventory/api';

/**
 * 10.1.1.1 承运商列表前端模型。
 *
 * <p>蓝图加粗句：**承运商是往来单位的一种角色，不在此处重复建档**。
 *
 * <p>这个约束在界面上的直接后果是：主体身份（名称 / 编码 / 接口类型 / 账号）
 * 在这一页**只读**，页面上没有任何编辑它们的入口 —— 它们要回 /partners 改。
 * 本页能写的只有物流侧那几项：承运商类别、时效等级、覆盖国家、支持业务类型、
 * 凭据引用。
 *
 * <p>另一条同样重要：**凭据只存密钥管理器引用**。所以表单里那个字段叫
 * 「凭据引用」而不是「密钥 / 密码」，并且要求同时填掩码 —— 真实凭据落库
 * 会在写库那一刻被 DB 的 CHECK 拒掉（见 V134）。
 */

export interface CarrierCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface CarrierCapabilities {
  dimensions: CarrierCapabilityDimension[];
  carrierCategories: string[];
  serviceTiers: string[];
  businessTypes: string[];
  coverageCountryCodes: string[];
  readinessCodes: string[];
  availabilityCodes: string[];
  upstreamNote: string;
  boundaryNote: string;
}

export interface CarrierCategoryCount {
  category: string;
  label: string;
  count: number;
}

export interface CarrierSummary {
  total: number;
  available: number;
  manualOnly: number;
  partyBlocked: number;
  notRegistered: number;
  noCredential: number;
  noCoverage: number;
  noBusinessScope: number;
}

export interface CarrierOverview {
  summary: CarrierSummary;
  byCategory: CarrierCategoryCount[];
  byTier: CarrierCategoryCount[];
  note: string;
}

export interface CarrierRow {
  id: string;
  roleId: string;
  partyId: string;
  partyCode: string;
  partyName: string;
  partyStatus: string;
  partyUsable: boolean;
  roleStatus: string;
  interfaceType: string;
  accountRef: string | null;
  serviceCapabilities: string[];
  carrierCategory: string | null;
  serviceTier: string | null;
  secretRef: string | null;
  secretMasked: string | null;
  coverageCountries: string[];
  coverageCount: number;
  businessTypes: string[];
  businessCount: number;
  readiness: string;
  availability: string;
  availabilityMessage: string;
  note: string;
  version: number;
  syncedAt: string | null;
  updatedAt: string;
}

export interface CarrierPage {
  items: CarrierRow[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  summary: CarrierSummary;
}

export interface SaveCarrierInput {
  carrierCategory: string;
  serviceTier: string;
  secretRef: string;
  secretMasked: string;
  coverageCountries: string[];
  businessTypes: string[];
  note: string;
  version: number;
}

export interface SyncCarriersResult {
  seen: number;
  created: number;
  refreshed: number;
  skippedNoCarrierRole: number;
  syncedAt: string;
  note: string;
}

/** 列表筛选条件（全部可选，空串按不过滤处理）。 */
export interface CarrierFilters {
  keyword: string;
  carrierCategory: string;
  serviceTier: string;
  availability: string;
  countryCode: string;
  businessType: string;
  sort: string;
  page: number;
  size: number;
}

export const EMPTY_CARRIER_FILTERS: CarrierFilters = {
  keyword: '',
  carrierCategory: '',
  serviceTier: '',
  availability: '',
  countryCode: '',
  businessType: '',
  sort: 'NAME_ASC',
  page: 0,
  size: 20,
};

// ================================================================== 文案

/** 承运商类别（蓝图职责句括号里的五种）。 */
export function carrierCategoryLabel(code: string | null): string {
  switch (code) {
    case 'EXPRESS_COURIER':
      return '快递';
    case 'LINE_HAUL':
      return '专线';
    case 'POSTAL':
      return '邮政';
    case 'FBA_CARRIER':
      return 'FBA 承运';
    case 'LAST_MILE':
      return '末端派送';
    default:
      return '未登记';
  }
}

/** 时效等级。 */
export function serviceTierLabel(code: string | null): string {
  switch (code) {
    case 'STANDARD':
      return '标准';
    case 'EXPRESS':
      return '加急';
    case 'PRIORITY':
      return '优先';
    default:
      return '未登记';
  }
}

/** 接口类型（**上游 catalog 的只读快照**，不要在本页编辑）。 */
export function interfaceTypeLabel(code: string): string {
  switch (code) {
    case 'API':
      return '系统对接';
    case 'SFTP_FILE':
      return '文件交换';
    case 'WEB_PORTAL':
      return '平台下单';
    case 'EMAIL':
      return '邮件往来';
    default:
      return code;
  }
}

/** 服务能力（上游快照）。 */
export function serviceCapabilityLabel(code: string): string {
  const labels: Record<string, string> = {
    AIR: '空运',
    SEA: '海运',
    RAIL: '铁路',
    TRUCK: '陆运',
    EXPRESS: '国际快递',
    DOOR_TO_DOOR: '门到门',
    CUSTOMS: '代理清关',
    INSURED: '可保价',
    URGENT: '支持加急',
  };
  return labels[code] ?? code;
}

/** 支持的业务类型。 */
export function businessTypeLabel(code: string): string {
  switch (code) {
    case 'OWN_FULFILLMENT':
      return '自发货';
    case 'RETURN':
      return '退货';
    case 'FBA_INBOUND':
      return 'FBA 入仓';
    default:
      return code;
  }
}

/**
 * 可用性状态的展示元数据。
 *
 * <p>tone 决定徽章配色：绿=可用、蓝=只能人工、灰=还没配、红=上游说不能用。
 * 刻意把「还没配」与「不能用」分成两种 —— 前者是本模块待补的活，
 * 后者是上游已把它拉黑/停用，运营看到红色不该再去改物流配置。
 */
export function availabilityMeta(availability: string): {
  label: string;
  tone: 'success' | 'info' | 'neutral' | 'danger';
} {
  switch (availability) {
    case 'AVAILABLE':
      return { label: '可用', tone: 'success' };
    case 'MANUAL_ONLY':
      return { label: '仅人工', tone: 'info' };
    case 'PARTY_BLOCKED':
      return { label: '主体不可用', tone: 'danger' };
    case 'NOT_REGISTERED':
      return { label: '未登记物流档案', tone: 'neutral' };
    case 'NO_CREDENTIAL':
      return { label: '缺凭据引用', tone: 'neutral' };
    case 'NO_COVERAGE':
      return { label: '缺覆盖国家', tone: 'neutral' };
    case 'NO_BUSINESS_SCOPE':
      return { label: '缺业务类型', tone: 'neutral' };
    default:
      return { label: availability, tone: 'neutral' };
  }
}

/** 可用性是否已经到「可以被 10.1.1.2 挑走」的程度。 */
export function isSelectable(row: CarrierRow): boolean {
  return row.availability === 'AVAILABLE' || row.availability === 'MANUAL_ONLY';
}

/**
 * 凭据引用的本地校验。
 *
 * <p>前端这道校验是为了**早点告诉人**，不是安全边界 —— 后端有 DB 层 CHECK 兜底。
 * 但它能把「粘了一个真实 token 进来」这件事在提交前就标红，
 * 而不是等一次 500。
 */
const REF_PREFIXES = ['secret://', 'kv://', 'vault://', 'external-secrets://'];

export function validateCredential(secretRef: string, secretMasked: string): string[] {
  const problems: string[] = [];
  const ref = secretRef.trim();
  const masked = secretMasked.trim();
  if (!ref) {
    if (masked) {
      problems.push('只填了掩码但没有凭据引用：掩码是用来遮住引用的，不能单独存在');
    }
    return problems;
  }
  if (!REF_PREFIXES.some((prefix) => ref.startsWith(prefix))) {
    problems.push(
      '凭据只能填密钥管理器引用（secret:// / kv:// / vault:// / external-secrets:// 开头），真实密钥不入库',
    );
  }
  if (!masked) {
    problems.push('填了凭据引用就必须同时填掩码：界面上要显示「已配置（xxx****）」');
  } else if (!masked.includes('*')) {
    problems.push('掩码必须真的打码（至少含一个 *）');
  }
  return problems;
}

/** 整张物流侧档案的本地校验，逐条返回理由。 */
export function validateCarrierForm(input: SaveCarrierInput): string[] {
  const problems: string[] = [];
  if (!input.carrierCategory) problems.push('承运商类别必填：它是承运商物流侧的分类');
  if (!input.serviceTier) problems.push('时效等级必填：渠道配置要靠它选线路');
  const countries = input.coverageCountries.filter((c) => c.trim());
  if (countries.length === 0) {
    problems.push('至少登记一个覆盖国家：一个国家都送不到的承运商不该被选中');
  }
  countries.forEach((c) => {
    if (!/^[A-Za-z]{2}$/.test(c.trim())) problems.push(`国家代码「${c}」必须是两位字母（如 DE / US）`);
  });
  if (input.businessTypes.filter((b) => b.trim()).length === 0) {
    problems.push('至少声明一种业务类型（自发货 / 退货 / FBA 入仓）');
  }
  problems.push(...validateCredential(input.secretRef, input.secretMasked));
  return problems;
}

/** 从一行承运商铺出表单初值（编辑时用）。 */
export function toForm(row: CarrierRow): SaveCarrierInput {
  return {
    carrierCategory: row.carrierCategory ?? '',
    serviceTier: row.serviceTier ?? '',
    secretRef: row.secretRef ?? '',
    secretMasked: row.secretMasked ?? '',
    coverageCountries: [...row.coverageCountries],
    businessTypes: [...row.businessTypes],
    note: row.note ?? '',
    version: row.version,
  };
}

/** 把逗号 / 空格 / 分号分隔的输入拆成去重的大写列表。 */
export function parseList(raw: string): string[] {
  return Array.from(
    new Set(
      raw
        .split(/[,;\s]+/)
        .map((v) => v.trim().toUpperCase())
        .filter(Boolean),
    ),
  );
}

/** 把代码列表翻成中文串；空列表显示「未登记」而不是空白。 */
export function listText(values: string[], label: (code: string) => string): string {
  if (!values.length) return '未登记';
  return values.map(label).join('、');
}

// ================================================================== 端点

export function fetchCarrierCapabilities(): Promise<CarrierCapabilities> {
  return api<CarrierCapabilities>('channel/carriers/capabilities');
}

export function fetchCarrierOverview(): Promise<CarrierOverview> {
  return api<CarrierOverview>('channel/carriers/overview');
}

export function fetchCarriers(filters: CarrierFilters): Promise<CarrierPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.carrierCategory) query.set('carrierCategory', filters.carrierCategory);
  if (filters.serviceTier) query.set('serviceTier', filters.serviceTier);
  if (filters.availability) query.set('availability', filters.availability);
  if (filters.countryCode) query.set('countryCode', filters.countryCode);
  if (filters.businessType) query.set('businessType', filters.businessType);
  if (filters.sort) query.set('sort', filters.sort);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<CarrierPage>(`channel/carriers?${query.toString()}`);
}

export function syncCarriers(): Promise<SyncCarriersResult> {
  return api<SyncCarriersResult>('channel/carriers/sync', 'POST');
}

export function fetchCarrier(id: string): Promise<CarrierRow> {
  return api<CarrierRow>(`channel/carriers/${id}`);
}

export function saveCarrier(id: string, input: SaveCarrierInput): Promise<CarrierRow> {
  return api<CarrierRow>(`channel/carriers/${id}`, 'PUT', input);
}

/** 撤下物流侧档案（**不是**删除承运商：主体记录在 catalog，本服务无权删）。 */
export function removeCarrierLogisticsProfile(id: string): Promise<void> {
  return api<void>(`channel/carriers/${id}`, 'DELETE');
}
