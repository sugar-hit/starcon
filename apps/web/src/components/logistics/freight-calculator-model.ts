import { api } from '../inventory/api';

/**
 * 10.1.2.1 运费计算器前端模型。
 *
 * 蓝图：「输入收货国家、重量、体积、件数、申报价值、是否带电/液体等，输出各可用渠道的
 * 运费与预计时效，供人工决策或规则匹配使用。输入：包裹参数；渠道规则集；可能的附加费。
 * 输出：渠道 × (运费、时效、可承运性) 对照表。」
 *
 * 🔴 这个页面上最容易做成假活的两件事，这里都挡住了：
 *
 * 1. **运费不在浏览器里算**。页面把包裹参数发给 `/channel/freight/estimate`，
 *    拿回服务端算好的对照表。这里没有任何一处拿 `rateCard.bands` 自己乘一遍 ——
 *    前端算一遍就意味着「界面上的钱」和「落库的钱」会有两个算法。
 * 2. **算不出来不是 0**。`rateSource === 'NO_RULE'` 的行运费各列是 `null`，
 *    页面显示「规则不全，无法试算」而不是 0.00 —— 一条 0 元运费会被当成
 *    「这家最便宜」选进分仓与费用测算。
 *
 * 🔴 金额在 JS 侧只用 `number` 承载与展示，**不做任何前端算术**（不求和、不比较、
 * 不换算）—— 运算是服务端 BigDecimal 的事，前端算一遍就会引入二进制浮点误差。
 */

/** 金额一律 number（JSON number），只原样展示。 */
export type FreightMoney = number | null;

/** 数量（重量 / 体积 / 计费量），语义由所在字段决定。 */
export type FreightQty = number | null;

export interface FreightCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface FreightCapabilities {
  dimensions: FreightCapabilityDimension[];
  chargeModels: string[];
  bases: string[];
  roundingModes: string[];
  rateSources: string[];
  eligibilityCodes: string[];
  estimationNote: string;
  dataNote: string;
}

export interface RateBandItem {
  bandNo: number;
  fromQty: number;
  toQty: number | null;
  toQtyInclusive: boolean;
  rateAmount: number;
  note: string;
}

export interface RateCardRow {
  id: string;
  channelId: string;
  channelCode: string;
  channelName: string;
  carrierName: string;
  channelBillingMode: string;
  currency: string;
  status: string;
  chargeModel: string;
  basis: string;
  roundingMode: string;
  roundingScale: number;
  volumetricDivisor: number | null;
  firstQty: number | null;
  firstAmount: number | null;
  additionalQty: number | null;
  additionalAmount: number | null;
  bands: RateBandItem[];
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelCountryRule {
  channelId: string;
  channelCode: string;
  countryCode: string;
  remoteArea: boolean;
  transitMinDays: number | null;
  transitMaxDays: number | null;
  note: string;
}

export interface RateCardOverview {
  cards: RateCardRow[];
  countryRules: ChannelCountryRule[];
  note: string;
}

export interface FreightEstimateLine {
  channelId: string;
  channelCode: string;
  channelName: string;
  carrierName: string;
  /** PASS 可承运 · REVIEW 需人工确认 · BLOCK 硬拦截（承运商会拒收）。 */
  eligibility: string;
  eligibilityReasons: string[];
  /** RATE_CARD 按费率卡 · CHANNEL_FLAT 回退渠道单值 · NO_RULE 规则不全。 */
  rateSource: string;
  basis: string;
  chargeableQty: FreightQty;
  volumetricQty: FreightQty;
  baseCharge: FreightMoney;
  fuelFee: FreightMoney;
  remoteFee: FreightMoney;
  peakFee: FreightMoney;
  insuranceFee: FreightMoney;
  totalCharge: FreightMoney;
  currency: string;
  transitMinDays: number | null;
  transitMaxDays: number | null;
  quoteable: boolean;
  trace: string[];
  notes: string[];
}

export interface FreightEstimateResult {
  quotedAt: string;
  destCountry: string;
  shopCode: string;
  weightKg: number;
  volumeCm3: number | null;
  pieces: number;
  channelCount: number;
  quoteableCount: number;
  passCount: number;
  reviewCount: number;
  blockCount: number;
  currency: string;
  mixedCurrency: boolean;
  cheapestTotal: FreightMoney;
  dearestTotal: FreightMoney;
  /** 恒为 RULE_ESTIMATE —— 试算口径，不是实际结算成本。 */
  costBasis: string;
  savedQuoteId: string | null;
  lines: FreightEstimateLine[];
  note: string;
}

export interface FreightQuoteRow {
  id: string;
  costBasis: string;
  destCountry: string;
  shopCode: string;
  weightKg: number;
  lengthCm: number | null;
  widthCm: number | null;
  heightCm: number | null;
  pieces: number;
  declaredValue: number | null;
  declaredCurrency: string | null;
  containsBattery: boolean;
  containsLiquid: boolean;
  containsMagnet: boolean;
  categoryCodes: string[];
  quotedAt: string;
  currency: string;
  mixedCurrency: boolean;
  channelCount: number;
  passCount: number;
  reviewCount: number;
  blockCount: number;
  cheapestTotal: FreightMoney;
  dearestTotal: FreightMoney;
  note: string;
  createdAt: string;
}

export interface FreightQuoteLineRow {
  channelId: string;
  channelCode: string;
  channelName: string;
  carrierName: string;
  eligibility: string;
  eligibilityReason: string;
  rateSource: string;
  basis: string;
  chargeableQty: FreightQty;
  volumetricQty: FreightQty;
  baseCharge: FreightMoney;
  fuelFee: FreightMoney;
  remoteFee: FreightMoney;
  peakFee: FreightMoney;
  insuranceFee: FreightMoney;
  totalCharge: FreightMoney;
  currency: string;
  transitMinDays: number | null;
  transitMaxDays: number | null;
  quoteable: boolean;
  calcTrace: string;
}

export interface FreightQuoteDetail {
  quote: FreightQuoteRow;
  lines: FreightQuoteLineRow[];
}

export interface FreightQuoteSummary {
  total: number;
  withBlock: number;
  withReview: number;
  mixedCurrency: number;
  quoteable: number;
}

export interface FreightQuotePage {
  items: FreightQuoteRow[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  summary: FreightQuoteSummary;
}

/** 试算输入（页面上表单的形状；数字字段是字符串，避免输入过程中的类型魔法）。 */
export interface FreightEstimateInput {
  destCountry: string;
  shopCode: string;
  weightKg: string;
  lengthCm: string;
  widthCm: string;
  heightCm: string;
  pieces: string;
  declaredValue: string;
  declaredCurrency: string;
  categoryCodes: string;
  containsBattery: boolean;
  containsLiquid: boolean;
  containsMagnet: boolean;
  note: string;
}

export function emptyEstimateInput(): FreightEstimateInput {
  return {
    destCountry: 'DE',
    shopCode: '',
    weightKg: '',
    lengthCm: '',
    widthCm: '',
    heightCm: '',
    pieces: '1',
    declaredValue: '',
    declaredCurrency: '',
    categoryCodes: '',
    containsBattery: false,
    containsLiquid: false,
    containsMagnet: false,
    note: '',
  };
}

// ==================================================================
// 接口调用
// ==================================================================

export function fetchFreightCapabilities(): Promise<FreightCapabilities> {
  return api<FreightCapabilities>('channel/freight/capabilities');
}

export function fetchRateCards(countryCode?: string): Promise<RateCardOverview> {
  const query = countryCode ? `?countryCode=${encodeURIComponent(countryCode)}` : '';
  return api<RateCardOverview>(`channel/freight/rate-cards${query}`);
}

/** 试算（无副作用）。金额全部来自服务端，页面不做运算。 */
export function estimateFreight(input: FreightEstimateInput): Promise<FreightEstimateResult> {
  return api<FreightEstimateResult>(
    'channel/freight/estimate',
    'POST',
    toEstimateRequest(input),
  );
}

/** 试算并落库（下游 9.3.2.1 分仓、5.2.3.2 费用测算引用的是它）。 */
export function saveFreightQuote(input: FreightEstimateInput): Promise<FreightEstimateResult> {
  return api<FreightEstimateResult>('channel/freight/quotes', 'POST', {
    request: toEstimateRequest(input),
  });
}

export function fetchFreightQuotes(params: {
  destCountry?: string;
  eligibility?: string;
  sort?: string;
  page?: number;
  size?: number;
}): Promise<FreightQuotePage> {
  const query = new URLSearchParams();
  if (params.destCountry) query.set('destCountry', params.destCountry);
  if (params.eligibility) query.set('eligibility', params.eligibility);
  query.set('sort', params.sort ?? 'TIME_DESC');
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<FreightQuotePage>(`channel/freight/quotes?${query.toString()}`);
}

export function fetchFreightQuote(id: string): Promise<FreightQuoteDetail> {
  return api<FreightQuoteDetail>(`channel/freight/quotes/${id}`);
}

/**
 * 表单 → 请求体。
 *
 * 尺寸**要么三个都给要么都不给**：只给两个会被服务端 400 拒（半套尺寸会算出一个
 * 看起来正常的体积重），所以这里在提交前就把「只填了一部分」的情况变成校验错误。
 */
export function toEstimateRequest(input: FreightEstimateInput) {
  return {
    destCountry: input.destCountry.trim().toUpperCase(),
    shopCode: input.shopCode.trim(),
    weightKg: Number(input.weightKg),
    lengthCm: input.lengthCm.trim() ? Number(input.lengthCm) : null,
    widthCm: input.widthCm.trim() ? Number(input.widthCm) : null,
    heightCm: input.heightCm.trim() ? Number(input.heightCm) : null,
    pieces: input.pieces.trim() ? Number(input.pieces) : 1,
    declaredValue: input.declaredValue.trim() ? Number(input.declaredValue) : null,
    declaredCurrency: input.declaredCurrency.trim().toUpperCase() || null,
    categoryCodes: splitCodes(input.categoryCodes),
    containsBattery: input.containsBattery,
    containsLiquid: input.containsLiquid,
    containsMagnet: input.containsMagnet,
    note: input.note.trim(),
  };
}

export function splitCodes(raw: string): string[] {
  return raw
    .split(/[\s,;、，；]+/)
    .map((code) => code.trim().toUpperCase())
    .filter((code) => code.length > 0);
}

// ==================================================================
// 校验
// ==================================================================

export interface EstimateValidation {
  ok: boolean;
  /** 阻断提交的问题（逐条给出，不合并成一句话）。 */
  errors: string[];
}

/**
 * 提交前校验。
 *
 * 这里的每一条都对应服务端会拒的场景；前端提前说清楚，是为了让人在表单旁边就知道
 * 哪里不对，而不是提交后收到一个 400。
 */
export function validateEstimate(input: FreightEstimateInput): EstimateValidation {
  const errors: string[] = [];
  if (!/^[A-Za-z]{2}$/.test(input.destCountry.trim())) {
    errors.push('收货国家是两位国家代码（如 DE / US）');
  }
  const weight = Number(input.weightKg);
  if (!input.weightKg.trim() || Number.isNaN(weight) || weight < 0) {
    errors.push('计费重量必须是不小于 0 的数字（单位 kg）');
  }
  const dims = [input.lengthCm, input.widthCm, input.heightCm].map((v) => v.trim());
  const given = dims.filter((v) => v.length > 0).length;
  if (given !== 0 && given !== 3) {
    errors.push('长宽高要么三个都填，要么都不填（只填一部分会算出错误的体积重）');
  }
  dims.forEach((value) => {
    if (value && (Number.isNaN(Number(value)) || Number(value) <= 0)) {
      errors.push('长宽高必须是大于 0 的数字（单位 cm）');
    }
  });
  if (input.pieces.trim() && Number(input.pieces) < 1) {
    errors.push('件数必须 ≥ 1');
  }
  if (input.declaredValue.trim() && Number(input.declaredValue) < 0) {
    errors.push('申报价值不能是负数');
  }
  if (input.declaredCurrency.trim() && !/^[A-Za-z]{3}$/.test(input.declaredCurrency.trim())) {
    errors.push('申报币种是三位币种代码（如 USD / EUR）');
  }
  const badCode = splitCodes(input.categoryCodes).find((code) => !/^[A-Z0-9_]{1,64}$/.test(code));
  if (badCode) {
    errors.push(`品类编码只接受字母数字下划线：${badCode}`);
  }
  return { ok: errors.length === 0, errors };
}

// ==================================================================
// 展示辅助
// ==================================================================

export function moneyText(value: FreightMoney, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 🔴 算不出钱的行显示「规则不全」，**不显示 0.00**。 */
export function totalText(line: FreightEstimateLine | FreightQuoteLineRow): string {
  if (!line.quoteable || line.totalCharge === null) return '规则不全，无法试算';
  return moneyText(line.totalCharge, line.currency);
}

export function eligibilityLabel(eligibility: string): string {
  switch (eligibility) {
    case 'PASS':
      return '可承运';
    case 'REVIEW':
      return '需人工确认';
    case 'BLOCK':
      return '硬拦截';
    default:
      return eligibility;
  }
}

export function eligibilityTone(eligibility: string): 'success' | 'warning' | 'danger' {
  switch (eligibility) {
    case 'PASS':
      return 'success';
    case 'REVIEW':
      return 'warning';
    default:
      return 'danger';
  }
}

export function rateSourceLabel(rateSource: string): string {
  switch (rateSource) {
    case 'RATE_CARD':
      return '按费率卡';
    case 'CHANNEL_FLAT':
      return '渠道单值回退';
    case 'NO_RULE':
      return '规则不全';
    default:
      return rateSource;
  }
}

export function basisLabel(basis: string): string {
  switch (basis) {
    case 'WEIGHT':
      return '按重量';
    case 'VOLUME':
      return '按体积折重';
    case 'PIECE':
      return '按件';
    default:
      return basis;
  }
}

export function chargeModelLabel(model: string): string {
  switch (model) {
    case 'FLAT_RATE':
      return '计分档位';
    case 'FIRST_PLUS_ADDITIONAL':
      return '首重 + 续重';
    default:
      return model;
  }
}

export function transitText(min: number | null, max: number | null): string {
  if (min === null || max === null) return '未登记';
  return min === max ? `${min} 天` : `${min}–${max} 天`;
}

/** 计费量说明：把「按哪个量算的」讲清楚（含体积重时的取大说明）。 */
export function chargeableText(line: FreightEstimateLine): string {
  if (line.chargeableQty === null) return '—';
  const unit = line.basis === 'PIECE' ? ' 件' : ' kg';
  const base = `${line.chargeableQty}${unit}`;
  if (line.volumetricQty !== null && line.basis === 'WEIGHT') {
    return `${base}（体积重 ${line.volumetricQty}kg）`;
  }
  return base;
}

/** 档位区间的可读写法：下界恒闭，上界闭开由 toQtyInclusive 决定。 */
export function bandRangeText(band: RateBandItem): string {
  const end = band.toQty === null ? '∞' : String(band.toQty);
  // 无上界 = +∞，那个端点永远不闭合 ⇒ 与 DB 的 band_range 保持同口径
  return `[${band.fromQty}, ${end}${band.toQty !== null && band.toQtyInclusive ? ']' : ')'}`;
}

/** 费率卡摘要：界面上要能看出这条渠道是按什么口径取价的。 */
export function rateCardSummary(card: RateCardRow): string {
  const parts = [chargeModelLabel(card.chargeModel), basisLabel(card.basis)];
  if (card.volumetricDivisor !== null) parts.push(`体积重除数 ${card.volumetricDivisor}`);
  parts.push(`舍入 ${card.roundingMode}@${card.roundingScale}`);
  if (card.chargeModel === 'FIRST_PLUS_ADDITIONAL') {
    parts.push(`首重 ${card.firstQty ?? '—'}kg / ${card.firstAmount ?? '—'}`);
    parts.push(`续重 ${card.additionalQty ?? '—'}kg 每段 ${card.additionalAmount ?? '—'}`);
  } else {
    parts.push(`${card.bands.length} 段`);
  }
  return parts.join(' · ');
}

/**
 * 对照表的空态文案。
 *
 * 🔴 「没有候选渠道」与「有渠道但都算不出钱」是**两件事**：前者是渠道没配适用范围，
 * 后者是规则没配全。把两者混成一句「暂无数据」，运维会去看错的地方。
 */
export function emptyResultText(result: FreightEstimateResult | null): string {
  if (!result) return '';
  if (result.channelCount === 0) {
    return '没有候选渠道：渠道要么没启用、要么适用范围不含这个目的国 / 店铺。这不等于「可以随便选一条」。';
  }
  if (result.quoteableCount === 0) {
    return '有候选渠道，但一条都算不出运费：规则不全（缺尺寸、缺费率卡或缺档位）。请先补规则，别用 0 元当参考。';
  }
  return '';
}

/** 试算单列表里「这次为什么一个都没选上」的摘要。 */
export function quoteOutcomeText(row: FreightQuoteRow): string {
  if (row.channelCount === 0) return '无候选渠道';
  if (row.cheapestTotal === null) return '规则不全';
  if (row.blockCount > 0) return `${row.blockCount} 条硬拦截`;
  if (row.reviewCount > 0) return `${row.reviewCount} 条需确认`;
  return '可直接下单';
}
