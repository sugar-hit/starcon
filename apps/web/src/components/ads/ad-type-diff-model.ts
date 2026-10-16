import { api } from '../inventory/api';

/**
 * 14.1.2.2 广告类型差异 前端模型。
 *
 * 蓝图：「适配不同平台与广告类型的字段差异（自动广告、SB/SD、品牌广告、DSP、大促广告），把差异字段归一到内部
 * 模型展示。输入：各平台广告类型定义；平台 API 返回。输出：归一后的广告类型视图 + 特有字段补充展示。
 * 关系：上游 = 2.1.2.1 平台映射；下游 = 14.1.1.1。**归一化让跨平台广告可比**，但**必须保留原始类型标记，不能丢
 * 信息**。」
 *
 * 🔴 这个页面上最容易做丢的两件事，这里都挡住了：
 *
 * 1. **归一不在浏览器里做**。页面拿的是服务端算好的「归一后内部类型 + 可比汇总 + 保真状态」，
 *    浏览器里没有任何一处把平台原始类型映射到内部类型 —— 前端映射一遍就意味着
 *    「界面上的归一」与「落库与上报的归一」会有两个算法，而出问题时没人知道该信哪个。
 *    同样，特有字段的**原始值与归一后的内部落点由服务端拼成同一条**返回（`ExtraFieldView`），
 *    前端只渲染，不自己去找 `rawPayload[fieldKey]` 再猜它归到了哪一列。
 *
 * 2. **「不能丢信息」必须看得见**。`platformAdType`（原始标记）、`rawPayload`（原文）与
 *    `rawOnlyFieldKeys`（只留在原文里、没有内部落点的字段）在每一行、每一处详情里都成对出现。
 *    把它们藏起来等于把蓝图那句加粗话变成口号。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 归一与派生比率是服务端 `BigDecimal` 的事，前端算一遍就会引入二进制浮点误差，
 * 而且会让「界面上的 ACOS」和「报表里的 ACOS」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（分母为 0），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdTypeDiffCapabilities {
  dimensions: AdCapabilityDimension[];
  internalAdTypes: string[];
  mappingSources: string[];
  auctionModels: string[];
  objectives: string[];
  fieldDataTypes: string[];
  mappingStatuses: string[];
  normalizationNote: string;
  fidelityNote: string;
  unmappedNote: string;
  upstreamNote: string;
}

export interface AdTypeProfileRow {
  id: string;
  platformId: string;
  platformCode: string;
  platformName: string;
  /** 🔴 原始平台类型标记。 */
  platformAdType: string;
  /** 🔴 归一后的内部类型；UNMAPPED = 上游 2.1.2.1 没给归一口径。 */
  internalAdType: string;
  displayName: string;
  auctionModel: string;
  objective: string;
  supportsTargeting: boolean;
  supportsBidSchedule: boolean;
  supportsNegativeKw: boolean;
  supportsCreative: boolean;
  supportsAsciiAd: boolean;
  mappingSource: string;
  enumKind: string;
  fieldSpecCount: number;
  payloadCount: number;
  note: string;
  status: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdTypeProfilePage {
  items: AdTypeProfileRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdTypeFieldSpecRow {
  id: string;
  adTypeProfileId: string;
  /** 🔴 原始字段键。 */
  fieldKey: string;
  fieldLabel: string;
  dataType: string;
  unit: string;
  enumValues: string;
  /** 🔴 是否参与跨平台可比。 */
  comparable: boolean;
  /** 🔴 归一后的内部落点；为空表示该字段只能原样留在原始报文里。 */
  internalRepresentation: string;
  requiredOnInput: boolean;
  position: number;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdTypeProfileDetail {
  profile: AdTypeProfileRow;
  fieldSpecs: AdTypeFieldSpecRow[];
  /** 🔴 原始报文里出现、却没有任何字段定义覆盖的键。 */
  undocumentedKeys: string[];
  note: string;
}

export interface AdTypePayloadRow {
  id: string;
  adTypeProfileId: string;
  /** 🔴 原始平台类型标记。 */
  platformAdType: string;
  /** 🔴 归一后的内部类型。 */
  internalAdType: string;
  platformCode: string;
  displayName: string;
  auctionModel: string;
  objective: string;
  shopCode: string;
  externalAdId: string;
  externalCampaignId: string;
  adName: string;
  adStatus: string;
  currency: string;
  dailyBudget: AdMoney;
  defaultBid: AdMoney;
  bidFloor: AdMoney;
  startAt: string | null;
  endAt: string | null;
  impressions: AdMoney;
  clicks: AdMoney;
  conversions: AdMoney;
  spend: AdMoney;
  attributedRevenue: AdMoney;
  /** 🔴 派生比率：null = 不可比，与 0 含义相反。 */
  ctr: AdRatio;
  cpc: AdMoney;
  cvr: AdRatio;
  acos: AdRatio;
  /** 🔴 已归一的键。 */
  normalizedFieldKeys: string[];
  /** 🔴 只留在原始报文里、没有内部落点的键（DB 派生）。 */
  rawOnlyFieldKeys: string[];
  mappingStatus: string;
  rawFetchedAt: string;
  ingestedAt: string;
  sourceRef: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** 特有字段补充展示的一行：**原始值与归一后的内部落点在服务端就拼好了**，前端只渲染。 */
export interface ExtraFieldView {
  fieldKey: string;
  fieldLabel: string;
  dataType: string;
  unit: string;
  /** 🔴 原始报文里的原值（平台原样，未加工）。 */
  rawValue: string;
  normalized: boolean;
  internalRepresentation: string;
  comparabilityNote: string;
}

export interface AdTypePayloadDetail {
  payload: AdTypePayloadRow;
  /** 🔴 原始差异字段原文，一个字都没改。 */
  rawPayload: Record<string, unknown>;
  extraFields: ExtraFieldView[];
  /** 🔴 没有任何字段定义覆盖的原始键。 */
  undocumentedKeys: string[];
  fidelityNote: string;
}

export interface AdTypePayloadPage {
  items: AdTypePayloadRow[];
  total: number;
  page: number;
  size: number;
  unmappedCount: number;
  partialCount: number;
}

/** 平台 + 原始类型标记（归一视图里必须成对出现，否则「原始标记」这一环就丢了）。 */
export interface PlatformTypeMarker {
  platformId: string;
  platformCode: string;
  platformAdType: string;
  displayName: string;
  adCount: number;
}

export interface ComparableTypeSummary {
  internalAdType: string;
  adCount: number;
  platformCount: number;
  markers: PlatformTypeMarker[];
  auctionModels: string[];
  objectives: string[];
  impressions: AdMoney;
  clicks: AdMoney;
  conversions: AdMoney;
  spend: AdMoney;
  attributedRevenue: AdMoney;
  comparabilityNote: string;
}

export interface UnmappedTypeSummary {
  platformId: string;
  platformCode: string;
  platformAdType: string;
  displayName: string;
  adCount: number;
  reason: string;
}

export interface ComparableView {
  types: ComparableTypeSummary[];
  unmappedTypes: UnmappedTypeSummary[];
  note: string;
}

export interface RawOnlyFieldCount {
  fieldKey: string;
  occurrences: number;
}

export interface FidelityOverview {
  totalAds: number;
  normalizedAds: number;
  partialAds: number;
  unmappedAds: number;
  /** 🔴 「系统没有归一哪些字段」的明账。 */
  rawOnlyFields: RawOnlyFieldCount[];
  note: string;
}

/** 走 2.1.2.1 两层解析的结果；`mapped=false` + `UNMAPPED` 是合法答案。 */
export interface MappingResolution {
  platformId: string;
  platformCode: string;
  platformAdType: string;
  internalAdType: string;
  mapped: boolean;
  mappingSource: string;
  enumKind: string;
  precedenceNote: string;
  unmappedReason: string;
}

// ==================================================================
// 表单
// ==================================================================

export interface ProfileInput {
  id: string;
  platformId: string;
  platformAdType: string;
  /** 归一后的内部类型；默认由「解析」按钮带出来，不让运营手填。 */
  internalAdType: string;
  displayName: string;
  auctionModel: string;
  objective: string;
  supportsTargeting: boolean;
  supportsBidSchedule: boolean;
  supportsNegativeKw: boolean;
  supportsCreative: boolean;
  supportsAsciiAd: boolean;
  note: string;
}

export const emptyProfileInput: ProfileInput = {
  id: '',
  platformId: '',
  platformAdType: '',
  internalAdType: '',
  displayName: '',
  auctionModel: 'CPC',
  objective: 'SALES',
  supportsTargeting: true,
  supportsBidSchedule: true,
  supportsNegativeKw: true,
  supportsCreative: true,
  supportsAsciiAd: false,
  note: '',
};

export interface FieldSpecInput {
  id: string;
  fieldKey: string;
  fieldLabel: string;
  dataType: string;
  unit: string;
  enumValues: string;
  comparable: boolean;
  internalRepresentation: string;
  requiredOnInput: boolean;
  position: string;
  note: string;
}

export const emptyFieldSpecInput: FieldSpecInput = {
  id: '',
  fieldKey: '',
  fieldLabel: '',
  dataType: 'STRING',
  unit: '',
  enumValues: '',
  comparable: false,
  internalRepresentation: '',
  requiredOnInput: false,
  position: '10',
  note: '',
};

export interface PayloadInput {
  id: string;
  adTypeProfileId: string;
  shopCode: string;
  externalAdId: string;
  externalCampaignId: string;
  adName: string;
  adStatus: string;
  currency: string;
  dailyBudget: string;
  defaultBid: string;
  bidFloor: string;
  startAt: string;
  endAt: string;
  impressions: string;
  clicks: string;
  conversions: string;
  spend: string;
  attributedRevenue: string;
  /** 🔴 平台 API 返回的差异字段原文（JSON 对象文本，一个字不改）。 */
  rawPayload: string;
  rawFetchedAt: string;
  normalizedFieldKeys: string;
  sourceRef: string;
  note: string;
}

export const emptyPayloadInput: PayloadInput = {
  id: '',
  adTypeProfileId: '',
  shopCode: '',
  externalAdId: '',
  externalCampaignId: '',
  adName: '',
  adStatus: 'ENABLED',
  currency: 'USD',
  dailyBudget: '',
  defaultBid: '',
  bidFloor: '',
  startAt: '',
  endAt: '',
  impressions: '0',
  clicks: '0',
  conversions: '0',
  spend: '0',
  attributedRevenue: '',
  rawPayload: '{\n  \n}',
  rawFetchedAt: '',
  normalizedFieldKeys: '',
  sourceRef: '',
  note: '',
};

export interface AdValidation {
  ok: boolean;
  errors: string[];
}

// ==================================================================
// 接口调用
// ==================================================================

export function fetchAdTypeCapabilities(): Promise<AdTypeDiffCapabilities> {
  return api<AdTypeDiffCapabilities>('channel/ad-type-diffs/capabilities');
}

/** 走 2.1.2.1 两层解析原始类型的归一口径（无副作用，供建档前自查）。 */
export function resolveAdType(platformId: string, platformAdType: string): Promise<MappingResolution> {
  const query = new URLSearchParams({ platformId, platformAdType });
  return api<MappingResolution>(`channel/ad-type-diffs/resolve?${query.toString()}`);
}

export function fetchProfiles(params: {
  platformId?: string;
  internalAdType?: string;
  status?: string;
  sort?: string;
  page?: number;
  size?: number;
}): Promise<AdTypeProfilePage> {
  const query = new URLSearchParams();
  if (params.platformId) query.set('platformId', params.platformId);
  if (params.internalAdType) query.set('internalAdType', params.internalAdType);
  if (params.status) query.set('status', params.status);
  query.set('sort', params.sort ?? 'PLATFORM');
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<AdTypeProfilePage>(`channel/ad-type-diffs/profiles?${query.toString()}`);
}

export function fetchProfile(id: string): Promise<AdTypeProfileDetail> {
  return api<AdTypeProfileDetail>(`channel/ad-type-diffs/profiles/${id}`);
}

export function fetchFieldSpecs(id: string): Promise<AdTypeFieldSpecRow[]> {
  return api<AdTypeFieldSpecRow[]>(`channel/ad-type-diffs/profiles/${id}/field-specs`);
}

/**
 * 登记 / 改档案。
 *
 * 🔴 `mappingSource` **不由页面编**：它跟着解析结果一起送 —— 归一口径来自 2.1.2.1 的哪一层，
 * 是解析出来的事实，不是页面的选择。
 */
export function saveProfile(input: ProfileInput, mappingSource: string): Promise<AdTypeProfileRow> {
  return api<AdTypeProfileRow>('channel/ad-type-diffs/profiles', 'POST', {
    id: input.id || undefined,
    platformId: input.platformId,
    platformAdType: input.platformAdType.trim(),
    internalAdType: input.internalAdType,
    displayName: input.displayName.trim(),
    auctionModel: input.auctionModel,
    objective: input.objective,
    supportsTargeting: input.supportsTargeting,
    supportsBidSchedule: input.supportsBidSchedule,
    supportsNegativeKw: input.supportsNegativeKw,
    supportsCreative: input.supportsCreative,
    supportsAsciiAd: input.supportsAsciiAd,
    mappingSource,
    note: input.note.trim(),
  });
}

export function saveFieldSpec(
  profileId: string,
  input: FieldSpecInput,
): Promise<AdTypeFieldSpecRow> {
  return api<AdTypeFieldSpecRow>(`channel/ad-type-diffs/profiles/${profileId}/field-specs`, 'POST', {
    id: input.id || undefined,
    fieldKey: input.fieldKey.trim(),
    fieldLabel: input.fieldLabel.trim(),
    dataType: input.dataType,
    unit: input.unit.trim(),
    enumValues: input.enumValues.trim(),
    comparable: input.comparable,
    internalRepresentation: input.internalRepresentation.trim(),
    requiredOnInput: input.requiredOnInput,
    position: Number(input.position || 0),
    note: input.note.trim(),
  });
}

export function fetchPayloads(params: {
  platformId?: string;
  internalAdType?: string;
  mappingStatus?: string;
  shopCode?: string;
  sort?: string;
  page?: number;
  size?: number;
}): Promise<AdTypePayloadPage> {
  const query = new URLSearchParams();
  if (params.platformId) query.set('platformId', params.platformId);
  if (params.internalAdType) query.set('internalAdType', params.internalAdType);
  if (params.mappingStatus) query.set('mappingStatus', params.mappingStatus);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  query.set('sort', params.sort ?? 'INGESTED_DESC');
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<AdTypePayloadPage>(`channel/ad-type-diffs/payloads?${query.toString()}`);
}

export function fetchPayload(id: string): Promise<AdTypePayloadDetail> {
  return api<AdTypePayloadDetail>(`channel/ad-type-diffs/payloads/${id}`);
}

/** 登记归一后的广告数据。**保真相关字段不在入参里** —— 它们由服务端 DB 触发器派生。 */
export function savePayload(input: PayloadInput): Promise<SavePayloadResponse> {
  const raw: unknown = JSON.parse(input.rawPayload);
  const optional = (value: string): number | undefined => {
    const trimmed = value.trim();
    return trimmed === '' ? undefined : Number(trimmed);
  };
  return api<SavePayloadResponse>('channel/ad-type-diffs/payloads', 'POST', {
    id: input.id || undefined,
    adTypeProfileId: input.adTypeProfileId,
    shopCode: input.shopCode.trim(),
    externalAdId: input.externalAdId.trim(),
    externalCampaignId: input.externalCampaignId.trim(),
    adName: input.adName.trim(),
    adStatus: input.adStatus,
    currency: input.currency.trim().toUpperCase(),
    dailyBudget: optional(input.dailyBudget),
    defaultBid: optional(input.defaultBid),
    bidFloor: optional(input.bidFloor),
    startAt: input.startAt ? new Date(input.startAt).toISOString() : undefined,
    endAt: input.endAt ? new Date(input.endAt).toISOString() : undefined,
    impressions: optional(input.impressions),
    clicks: optional(input.clicks),
    conversions: optional(input.conversions),
    spend: optional(input.spend),
    attributedRevenue: optional(input.attributedRevenue),
    rawPayload: raw,
    rawFetchedAt: new Date(input.rawFetchedAt).toISOString(),
    normalizedFieldKeys: input.normalizedFieldKeys
      .split(/[,\s]+/)
      .map((key) => key.trim())
      .filter(Boolean),
    sourceRef: input.sourceRef.trim(),
    note: input.note.trim(),
  });
}

export interface SavePayloadResponse {
  row: AdTypePayloadRow;
  /** 🔴 DB 派生出来的保真状态（服务层声称什么都不作数）。 */
  mappingStatus: string;
  /** 🔴 DB 派生出来的「只留在原始报文里的键」。 */
  rawOnlyFieldKeys: string[];
}

export function fetchComparable(): Promise<ComparableView> {
  return api<ComparableView>('channel/ad-type-diffs/comparable');
}

export function fetchFidelity(): Promise<FidelityOverview> {
  return api<FidelityOverview>('channel/ad-type-diffs/fidelity');
}

// ==================================================================
// 校验
// ==================================================================

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(value: string): boolean {
  return UUID_RE.test(value.trim());
}

/** 登记档案的校验：与服务层的拒收条件一一对应。 */
export function validateProfile(input: ProfileInput, mappingSource: string): AdValidation {
  const errors: string[] = [];
  if (!isUuid(input.platformId)) errors.push('请选择平台');
  if (!input.platformAdType.trim()) {
    errors.push('🔴 原始平台类型标记必填 —— 它是「平台 API 原文是什么」的唯一依据');
  }
  if (!input.internalAdType) {
    errors.push('🔴 归一后的内部类型必填：请先点「按 2.1.2.1 解析」，让解析结果带出来');
  }
  if (!input.displayName.trim()) errors.push('展示名必填');
  if (mappingSource === 'UNMAPPED' && input.internalAdType !== 'UNMAPPED') {
    errors.push('上游 2.1.2.1 没给归一口径，归一类型只能是 UNMAPPED —— 不能落到兜底类型');
  }
  if (mappingSource !== 'UNMAPPED' && input.internalAdType === 'UNMAPPED') {
    errors.push('内部类型是 UNMAPPED，但声明的来源不是 UNMAPPED —— 两者必须自洽');
  }
  return { ok: errors.length === 0, errors };
}

/**
 * 特有字段定义的校验。
 *
 * 🔴 「声称可比却没有内部落点」是最容易犯的一条：服务端是 DB 的 CHECK 直接拒，
 * 这里先给��话错误 —— 运营看到「约束名」是没法行动的。
 */
export function validateFieldSpec(input: FieldSpecInput): AdValidation {
  const errors: string[] = [];
  if (!input.fieldKey.trim()) errors.push('🔴 原始字段键必填（要与原始报文里的顶层键同名）');
  if (!input.fieldLabel.trim()) errors.push('界面标签必填');
  if (input.comparable && !input.internalRepresentation.trim()) {
    errors.push('🔴 声称参与跨平台可比就必须给出归一后的内部落点，否则这一栏没法横向比');
  }
  // 🔴 反过来（不可比 + 没有内部落点）**不是错误**：那正是「只原样留在原始报文里」的字段，
  //   它会在保真概览的 rawOnlyFields 里被显式列出。这里刻意只提示、不拦。
  return { ok: errors.length === 0, errors };
}

/** 登记归一后广告数据的校验。 */
export function validatePayload(input: PayloadInput): AdValidation {
  const errors: string[] = [];
  if (!isUuid(input.adTypeProfileId)) errors.push('请选择广告类型档案');
  if (!input.shopCode.trim()) errors.push('店铺编码必填');
  if (!input.externalAdId.trim()) errors.push('🔴 平台侧广告 id 必填 —— 没有它就认不出「这一条」');

  if (!input.rawPayload.trim()) {
    errors.push('🔴 原始报文必填：它就是「不能丢信息」的兜底');
  } else {
    let parsed: unknown;
    try {
      parsed = JSON.parse(input.rawPayload);
    } catch {
      errors.push('原始报文不是合法 JSON');
      parsed = undefined;
    }
    if (parsed !== undefined && (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed))) {
      errors.push('原始报文必须是 JSON **对象**（按顶层键算差集是保真审计的前提）');
    }
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const payloadKeys = Object.keys(parsed as Record<string, unknown>);
      // 🔴 声称已归一的键必须真的存在于原文里 —— 否则保真率能被虚报成 100%
      const claimed = input.normalizedFieldKeys
        .split(/[,\s]+/)
        .map((key) => key.trim())
        .filter(Boolean);
      const missing = claimed.filter((key) => !payloadKeys.includes(key));
      if (missing.length > 0) {
        errors.push(`🔴 声称已归一的字段不在原始报文里：${missing.join('、')}`);
      }
    }
  }

  if (!input.rawFetchedAt.trim()) {
    errors.push('原始报文抓取时刻必填');
  } else if (Number.isNaN(new Date(input.rawFetchedAt).getTime())) {
    errors.push('原始报文抓取时刻不是合法时间');
  }
  for (const [label, value] of [
    ['日预算', input.dailyBudget],
    ['默认出价', input.defaultBid],
    ['出价下限', input.bidFloor],
    ['花费', input.spend],
    ['归因销售额', input.attributedRevenue],
  ] as const) {
    if (value.trim() === '') continue;
    const parsed = Number(value);
    if (Number.isNaN(parsed) || parsed < 0) errors.push(`${label}必须是不小于 0 的数字`);
  }
  for (const [label, value] of [
    ['展示量', input.impressions],
    ['点击量', input.clicks],
    ['转化量', input.conversions],
  ] as const) {
    const parsed = Number(value.trim() === '' ? '0' : value);
    if (Number.isNaN(parsed) || parsed < 0) errors.push(`${label}必须是不小于 0 的数字`);
  }
  return { ok: errors.length === 0, errors };
}

// ==================================================================
// 展示辅助
// ==================================================================

/** 内部类型标签（蓝图点名的六类 + 显式的未映射）。 */
export function internalTypeLabel(internalAdType: string): string {
  if (internalAdType === 'AUTO_DISCOVERY') return '自动广告';
  if (internalAdType === 'SPONSORED_BRAND') return 'SB 品牌广告';
  if (internalAdType === 'SPONSORED_DISPLAY') return 'SD 展示型品牌广告';
  if (internalAdType === 'BRAND_AD') return '品牌广告';
  if (internalAdType === 'DSP_PROGRAMMATIC') return 'DSP 程序化';
  if (internalAdType === 'PROMOTION_EVENT') return '大促广告';
  if (internalAdType === 'UNMAPPED') return '未映射（无归一口径）';
  return internalAdType;
}

/** 归一口径来源：这条归一是走 2.1.2.1 的哪一层解析出来的。 */
export function mappingSourceLabel(source: string): string {
  if (source === 'TENANT_OVERRIDE') return '租户覆盖';
  if (source === 'PLATFORM_DEFAULT') return '平台默认';
  if (source === 'UNMAPPED') return '未映射';
  return source;
}

/**
 * 保真状态标签。
 *
 * 🔴 PARTIAL_FIELD_COVERAGE 不是「归一失败」：类型归一好了，只是有字段没有内部落点。
 * 把它渲染成红色报错会让人以为整条数据不可信 —— 实际上它是**可用的、只是不可横比**。
 */
export function mappingStatusLabel(status: string): string {
  if (status === 'NORMALIZED') return '完全归一';
  if (status === 'PARTIAL_FIELD_COVERAGE') return '部分覆盖（有字段只留在原文里）';
  if (status === 'UNMAPPED_TYPE') return '未映射（类型就没归一）';
  return status;
}

export function mappingStatusTone(status: string): string {
  if (status === 'NORMALIZED') return 'text-emerald-600';
  if (status === 'PARTIAL_FIELD_COVERAGE') return 'text-amber-600';
  return 'text-muted-foreground';
}

export function auctionLabel(model: string): string {
  if (model === 'CPC') return '按点击计费 CPC';
  if (model === 'CPM') return '按千次展示 CPM';
  if (model === 'CPA') return '按行动计费 CPA';
  if (model === 'CPM_GOAL') return 'CPM 目标型';
  if (model === 'CPC_GOAL') return 'CPC 目标型';
  if (model === 'HYBRID') return '混合竞价';
  return '未知拍卖方式';
}

export function objectiveLabel(objective: string): string {
  if (objective === 'TRAFFIC') return '引流';
  if (objective === 'SALES') return '拉销量';
  if (objective === 'BRAND_AWARE') return '品牌认知';
  if (objective === 'BACKGROUND_REACH') return '后台触达';
  if (objective === 'DEAL') return '大促转化';
  if (objective === 'APP_PROMOTION') return '应用推广';
  return '目标未知';
}

export function adStatusLabel(status: string): string {
  if (status === 'ENABLED') return '投放中';
  if (status === 'PAUSED') return '已暂停';
  if (status === 'ENDED') return '已结束';
  if (status === 'ARCHIVED') return '已归档';
  if (status === 'PENDING_REVIEW') return '审核中';
  return '状态未知';
}

export function money(value: AdMoney, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

export function quantity(value: AdMoney): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/**
 * 派生比率展示：**不可比显示「不可比」，不是 0.00%**。
 *
 * 🔴 0 是「可比，但真的是零」；null 是「压根算不出来」（分母为 0）。
 * 渲染成 0% 会让人以为「这条广告一次点击都没有」，而真相是「没有展示量，比率无从计算」。
 */
export function ratioText(value: AdRatio, asPercent = true): string {
  if (value === null || value === undefined) return '不可比';
  return asPercent ? `${(value * 100).toFixed(2)}%` : value.toFixed(4);
}

export function ratioTone(value: AdRatio): string {
  return value === null || value === undefined ? 'text-muted-foreground' : 'text-foreground';
}

export function timestampText(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 一行广告的完整保真提示（原始标记 + 归一类型 + 只留在原文里的字段）。 */
export function fidelityLine(payload: AdTypePayloadRow): string {
  const parts = [
    `原始标记 ${payload.platformAdType}`,
    `归一 ${internalTypeLabel(payload.internalAdType)}`,
  ];
  if (payload.rawOnlyFieldKeys.length > 0) {
    parts.push(`只留在原文里的字段 ${payload.rawOnlyFieldKeys.join('、')}`);
  } else {
    parts.push('原始字段全部有归一落点');
  }
  return parts.join(' · ');
}

/** 结果为空时的说明：区分「一条广告都没归一」与「有广告但没有可比口径」。 */
export function emptyComparableText(view: ComparableView | null): string {
  if (!view) return '';
  if (view.types.length === 0) {
    if (view.unmappedTypes.length === 0) {
      return (
        '本租户还没有归一后的广告数据。先在上方登记广告类型档案，再录入平台 API 返回的广告。' +
        '空结果不代表「系统认为这些广告不存在」，它代表「还没有可归一的输入」。'
      );
    }
    return (
      `当前只有 ${view.unmappedTypes.length} 个原始类型未映射，因此可比表是空的。` +
      '它们**存在且带完整原始标记**，只是 2.1.2.1 没有给归一口径 —— ' +
      '在上方「按 2.1.2.1 解析」处补一条映射（或租户覆盖），它们就会进可比表。'
    );
  }
  return '';
}
