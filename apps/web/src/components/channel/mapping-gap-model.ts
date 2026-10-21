// 🔴 用**相对**路径而不是 `@/` 别名：根 vitest 配置没有配 alias，
//   用别名会让本模型层在 `pnpm test` 里直接 import 失败（与 listing-mapping-model 同款理由）。
import { api } from '../../lib/api';

/**
 * 2.4.2.3 映射缺失队列 的前端模型层。
 *
 * <p>蓝图：「**职责**：平台商品已同步但未映射到内部 SKU 的记录，等待人工建立映射或创建新 SKU。
 * **输入**：`listing_variant` 无映射的记录；`listing_mapping_version` 的错误状态。
 * **输出**：待映射清单（平台商品、变体、店铺、价格）；操作入口：映射到已有 SKU / 新建 SKU / 标记忽略。
 * **关系**：上游 = 04 刊登与内容、09 订单（缺映射订单也进此队列）；下游 = 04 映射管理。
 * <b>不按名称猜 SKU</b>；映射建立后回填受影响订单，历史订单冻结当时映射版本。」
 *
 * <p>🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>不按名称猜 SKU</b>：本模型**没有**任何「建议 SKU / 相似度 / 候选」的取值函数 ——
 *       那个形状一存在，赶订单的人就会去选它。4.1.2.1 的候选恒带「待人工确认」，
 *       本队列只摆缺口，决策由人给。</li>
 *   <li><b>价格位不画假数据</b>：价格归 05 定价，服务端 {@code priceAvailable=false} 时
 *       渲染「未接入（归 05 定价）」，**不是 0、不是 "--"、不是猜的价**。</li>
 *   <li><b>筛选与排序全在服务端</b>：前端只翻译 query string，不在浏览器里过滤已分页的数据
 *       （那样会得到「总数说 500、实际只筛得出当前页 50 条」）。</li>
 *   <li><b>未交付段如实显示</b>：09 订单来源未接线、价格未接入、03 建档未接线，
 *       三件事都由服务端的措辞原样透出，前端不自己编。</li>
 * </ol>
 *
 * <p>🔴 金额在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 服务端 `BigDecimal` 的事在前端算一遍就会引入二进制浮点误差。
 *
 * <p>🔴 本层的类型**自己声明**而不直接复用 `packages/api-contracts` 的同名类型：
 * codegen 把 Java record 的可空字段压成了非空（`price: number` 而不是 `number | null`），
 * 照抄契约会让「没有价格快照」在类型上变成"有价格" —— 那正是**画假数据**的类型级来源。
 * （与 listing-mapping-model 同一手法：它也自己声明 `MappingNumber = number | null`。）
 */

/** 与后端 {@code ListingMappingGapController} 同前缀。 */
export const base = 'channel/mapping-gaps';

export type GapStatus = 'PENDING' | 'MAPPED' | 'IGNORED';
export type GapDecision = 'NONE' | 'MAPPED' | 'CREATE_SKU' | 'IGNORED';
export type GapSource = 'LISTING_SYNC' | 'ORDER';
export type GapReason = 'VARIANT_UNMAPPED' | 'MAPPING_RELEASED' | 'ORDER_UNMAPPED';
export type GapRelationType = 'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_ONE';
export type GapSort = 'RECENT' | 'OLDEST' | 'PRICE' | 'SHOP';

/** 🔴 null = 服务端没给这个值，与 `0` 含义相反。 */
export type GapNumber = number | null;

export interface MappingGapCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface MappingGapCapabilities {
  dimensions: MappingGapCapabilityDimension[];
  statuses: string[];
  decisions: string[];
  gapReasons: string[];
  sources: string[];
  /** 🔴 平台侧售价是否可用（false = 归 05 定价） */
  priceAvailable: boolean;
  priceNote: string;
  priceOwnerModule: string;
  /** 🔴 09 订单来源是否已接线 */
  orderSourceWired: boolean;
  gapNote: string;
  dependencyNote: string;
}

/** 待映射清单的一行。🔴 没有任何「建议 SKU / 相似度」字段。 */
export interface MappingGapRow {
  id: string;
  listingId: string;
  listingVariantId: string;
  source: string;
  gapReason: string;
  status: string;
  decision: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  externalItemId: string;
  titleSnapshot: string;
  externalVariantId: string;
  attributesSnapshot: string;
  /** 🔴 平台侧售价快照；没有快照时是 null（**不是 0**） */
  price: GapNumber;
  currency: string | null;
  /** 🔴 内部 SKU 逻辑 id（03 拥有）；未决策时是 null */
  internalSkuId: string | null;
  mappingId: string | null;
  /** 🔴 回填到的那一版（历史事实，不可改写） */
  mappingVersionId: string | null;
  /** 🔴 解决这个缺口的那一版的生效时间点 */
  mappedEffectiveFrom: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string;
  ignoreReason: string;
  backfilledAt: string | null;
  orderRef: string;
  waitingDays: number;
  createdAt: string;
  updatedAt: string;
}

export interface MappingGapPage {
  items: MappingGapRow[];
  total: number;
  page: number;
  size: number;
  pendingInPage: number;
  priceSnapshotInPage: number;
}

export interface MappingGapCount {
  key: string;
  total: number;
}

export interface MappingGapShopCount {
  shopCode: string;
  shopName: string;
  platformCode: string;
  total: number;
}

export interface MappingGapShopOption {
  shopCode: string;
  shopName: string;
  platformCode: string;
}

export interface MappingGapFilterOptions {
  shops: MappingGapShopOption[];
  platformCodes: string[];
  statuses: string[];
  decisions: string[];
  gapReasons: string[];
  sources: string[];
  sorts: string[];
}

export interface MappingGapSkuRequestView {
  id: string;
  requestedCode: string;
  requestedName: string;
  requestedBarcode: string;
  requestedBaseUnit: string;
  status: string;
  dispatchedAt: string | null;
  /** 🔴 03 建出来的 SKU；未派发时是 null（**不是"已建好"**） */
  createdSkuId: string | null;
  lastError: string;
  createdAt: string;
}

export interface MappingGapDetail {
  gap: MappingGapRow;
  skuRequests: MappingGapSkuRequestView[];
  orderBackfillNote: string;
}

export interface MappingGapSummary {
  byStatus: MappingGapCount[];
  byReason: MappingGapCount[];
  byShop: MappingGapShopCount[];
  pendingTotal: number;
  oldestPendingAt: string | null;
  priceSnapshotCount: number;
  orderSourceWired: boolean;
}

export interface MappingGapActionResult {
  gap: MappingGapRow;
  message: string;
}

export interface MappingGapSkuRequestResult {
  request: MappingGapSkuRequestView;
  message: string;
}

export interface MappingGapOrderAccepted {
  gapId: string;
  message: string;
}

/** 「映射到已有 SKU」的入参。🔴 internalSkuId 必须是明确的 id，不接受名字。 */
export interface MappingGapMapInput {
  internalSkuId: string;
  relationType: string;
  componentQuantity: number | null;
  changeReason: string;
  effectiveFrom: string | null;
}

/** 「新建 SKU」的入参（**只登记意图**）。 */
export interface MappingGapCreateSkuInput {
  requestedCode: string;
  requestedName: string;
  requestedBarcode: string;
  requestedBaseUnit: string;
  note: string;
}

/** 「标记忽略」的入参。 */
export interface MappingGapIgnoreInput {
  reason: string;
  note: string;
}

/** 09 订单来源的事件载荷。 */
export interface MappingGapOrderInput {
  orderRef: string;
  listingVariantId: string;
  price: number | null;
  currency: string | null;
  note: string;
}

// ==================================================================== 读取

export interface GapFilters {
  // ⚠️ 显式写成 `| undefined`：AGENTS.md 开着 exactOptionalPropertyTypes，
  //   省略与「传了 undefined」在类型上是两回事 —— 这里要允许后者（表单清空时就是这么传的）。
  shopCode?: string | undefined;
  platformCode?: string | undefined;
  search?: string | undefined;
  status?: string | undefined;
  source?: string | undefined;
  gapReason?: string | undefined;
  minPrice?: string | undefined;
  maxPrice?: string | undefined;
  sort?: string | undefined;
}

/**
 * 把筛选条件翻译成 query string。
 *
 * <p>🔴 空值**一律不传**：传 `status=` 会被后端当成「筛选 status 为空串」而不是「不筛」。
 */
export function buildGapQuery(filters: GapFilters): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || value === null || value === '') continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return suffix ? `?${suffix}` : '';
}

export const fetchGaps = (filters: GapFilters = {}, page = 0, size = 50) =>
  api<MappingGapPage>(`${base}${buildGapQuery(filters)}&page=${page}&size=${size}`);

export const fetchSummary = () => api<MappingGapSummary>(`${base}/summary`);

export const fetchFilterOptions = () => api<MappingGapFilterOptions>(`${base}/filter-options`);

export const fetchCapabilities = () => api<MappingGapCapabilities>(`${base}/capabilities`);

export const fetchGap = (id: string) => api<MappingGapDetail>(`${base}/${id}`);

// ==================================================================== 三个操作入口

/**
 * 操作一：映射到已有 SKU。
 *
 * <p>🔴 `internalSkuId` 必须由人明确给出 —— 本函数**不做任何自动匹配**。
 */
export const mapGap = (id: string, input: MappingGapMapInput) =>
  api<MappingGapActionResult>(`${base}/${id}/map`, 'POST', input);

/**
 * 操作二：新建 SKU。
 *
 * <p>🔴 只**登记意图**：03 侧建档尚无接线 ⇒ 请求停在 PENDING，**不假装 SKU 已创建**。
 */
export const createSkuForGap = (id: string, input: MappingGapCreateSkuInput) =>
  api<MappingGapSkuRequestResult>(`${base}/${id}/create-sku`, 'POST', input);

/** 操作三：标记忽略（原因必填）。 */
export const ignoreGap = (id: string, input: MappingGapIgnoreInput) =>
  api<MappingGapActionResult>(`${base}/${id}/ignore`, 'POST', input);

/** 09 订单来源的事件入口（**当前 trade 侧没有推这条事件**）。 */
export const acceptOrderGap = (input: MappingGapOrderInput) =>
  api<MappingGapOrderAccepted>(`${base}/orders`, 'POST', input);

// ==================================================================== 展示口径

export const STATUS_LABELS: Record<GapStatus, string> = {
  PENDING: '待映射',
  MAPPED: '已建立映射',
  IGNORED: '已忽略',
};

export const DECISION_LABELS: Record<GapDecision, string> = {
  NONE: '未决策',
  MAPPED: '映射到已有 SKU',
  CREATE_SKU: '登记新建 SKU',
  IGNORED: '标记忽略',
};

export const SOURCE_LABELS: Record<GapSource, string> = {
  LISTING_SYNC: '平台商品同步',
  ORDER: '订单缺映射',
};

export const REASON_LABELS: Record<GapReason, string> = {
  VARIANT_UNMAPPED: '从未建立映射',
  MAPPING_RELEASED: '映射曾建立后被解除',
  ORDER_UNMAPPED: '订单缺映射',
};

export const RELATION_LABELS: Record<GapRelationType, string> = {
  ONE_TO_ONE: '一对一',
  ONE_TO_MANY: '一对多（套装）',
  MANY_TO_ONE: '多对一',
};

export const SORT_LABELS: Record<GapSort, string> = {
  RECENT: '最近入队',
  OLDEST: '最久未处理',
  PRICE: '高价优先',
  SHOP: '按店铺',
};

/**
 * 队列状态色。
 *
 * <p>PENDING 用提醒色（MAPPED / IGNORED 用中性色）：PENDING 是**等人处理**，
 * 不是出了错 —— 用危险色会让整个待办列表看起来像事故现场。
 */
export function statusTone(status: string): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'PENDING':
      return 'warning';
    case 'MAPPED':
      return 'success';
    case 'IGNORED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

export interface PriceCell {
  /** 该行是否带平台侧售价快照。 */
  available: boolean;
  /** 真实价格；没有快照时为 null（**不是 0**）。 */
  value: number | null;
  /** 币种。 */
  currency: string | null;
  /** 未接入时的措辞（服务端原样透出）；available 时为 null。 */
  note: string | null;
  /** 未接入的责任模块。 */
  ownerModule: string | null;
}

/**
 * 🔴 价格位。
 *
 * <p>本批次 channel 侧没有任何「平台 listing 售价」事实表（05 定价整卷未交付）⇒
 * **平台商品来源的队列行没有价格快照**。此时渲染「未接入（归 05 定价）」，
 * **刻意不提供「返回 0」的分支** —— 0 会被读成「这个商品卖 0 块」。
 *
 * <p>订单来源的行可能带平台价快照（事件载荷携带），那一行显示**真实数字**。
 */
export function priceCell(
  row: Pick<MappingGapRow, 'price' | 'currency'>,
  capabilities: MappingGapCapabilities | null,
): PriceCell {
  if (row.price !== null && row.price !== undefined) {
    return {
      available: true,
      value: row.price,
      currency: row.currency ?? null,
      note: null,
      ownerModule: null,
    };
  }
  return {
    available: false,
    value: null,
    currency: null,
    note: capabilities?.priceNote ?? '价格能力未接入（归 05 定价与价目系统）',
    ownerModule: capabilities?.priceOwnerModule ?? '05 定价',
  };
}

/**
 * 价格区间筛选的适用范围说明。
 *
 * <p>🔴 价格区间是**真 SQL**，但它精确地筛「有平台价快照且落在区间内」的行。
 * 平台商品来源当前没有快照 ⇒ 这个筛选当前只覆盖带快照的行。
 * **不写清楚的话**，运营会以为「筛完没结果 = 没有缺口」。
 */
export function priceFilterScopeHint(
  capabilities: MappingGapCapabilities | null,
  snapshotCount: number,
): string {
  const owner = capabilities?.priceOwnerModule ?? '05 定价';
  if (snapshotCount > 0) {
    return `价格区间只覆盖**带平台价快照**的行（本页 ${snapshotCount} 条有快照）。平台商品来源当前没有价格快照（${owner} 未交付）。`;
  }
  return `价格区间只覆盖**带平台价快照**的行，当前一条都没有 —— 平台商品来源没有价格快照（${owner} 未交付）。`;
}

/** 未接入的维度清单（用于页面顶部的「本模块未接入什么」明账）。 */
export function unavailableDimensions(
  capabilities: MappingGapCapabilities | null,
): MappingGapCapabilityDimension[] {
  if (!capabilities) return [];
  return capabilities.dimensions.filter((d) => !d.available);
}

// ==================================================================== 表单校验

export interface MapForm {
  internalSkuId: string;
  relationType: GapRelationType;
  componentQuantity: string;
  changeReason: string;
}

export interface IgnoreForm {
  reason: string;
  note: string;
}

export interface CreateSkuForm {
  requestedCode: string;
  requestedName: string;
  requestedBarcode: string;
  requestedBaseUnit: string;
  note: string;
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * 「映射到已有 SKU」的表单校验。
 *
 * <p>🔴 内部 SKU 必须是一个**明确的** id：这里不查库、不做模糊匹配 ——
 * 蓝图加粗句「不按名称猜 SKU」在表单层就是「必须填 id，不接受名字」。
 */
export function validateMapForm(form: MapForm): string | null {
  const sku = form.internalSkuId.trim();
  if (!sku) return '请填写要映射到的内部 SKU id（03 商品主数据的逻辑 id）';
  if (!UUID_PATTERN.test(sku)) return '内部 SKU id 必须是完整 UUID';
  if (!form.relationType) return '请选择映射基数';
  if (form.relationType === 'ONE_TO_MANY') {
    const qty = Number(form.componentQuantity);
    if (!form.componentQuantity.trim()) return '一对多（套装）必须给出组件数量';
    if (!Number.isFinite(qty) || qty <= 0) return '组件数量必须大于 0';
  } else if (form.componentQuantity.trim()) {
    return '组件数量只对一对多（套装）有意义';
  }
  if (!form.changeReason.trim()) return '变更原因必填：没有原因的版本历史无法回溯「当时为什么这么建」';
  return null;
}

/** 组装「映射到已有 SKU」的入参。 */
export function buildMapInput(form: MapForm): MappingGapMapInput {
  return {
    internalSkuId: form.internalSkuId.trim(),
    relationType: form.relationType,
    componentQuantity:
      form.relationType === 'ONE_TO_MANY' ? Number(form.componentQuantity) : null,
    changeReason: form.changeReason.trim(),
    effectiveFrom: null,
  };
}

/** 「标记忽略」的表单校验。 */
export function validateIgnoreForm(form: IgnoreForm): string | null {
  if (!form.reason.trim()) return '忽略原因必填：不写原因就没人知道这条为什么不用映射';
  return null;
}

/** 「新建 SKU」的表单校验。 */
export function validateCreateSkuForm(form: CreateSkuForm): string | null {
  if (!form.requestedCode.trim() && !form.requestedName.trim()) {
    return '至少给出期望的 SKU 编码或名称，否则 03 侧无从建档';
  }
  return null;
}

export function formatInstant(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString();
}
