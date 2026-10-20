import { api } from '../inventory/api';
import type { SiteOption } from './profit-calculator-model';

/** 目标店铺（站点）选项：与 5.3.1.1 / 5.3.1.2 共用同一份形状（同一个平台目录口径）。 */
export type { SiteOption };

/**
 * 5.4.1.2 批量调价前端模型。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：对选中的一批 listing 统一改价（固定金额/百分比/按模板），支持预览影响范围（涉及多少店铺、多少商品、预计利润变化）。
 *
 * **输入**：目标集合；调价参数；预览确认。
 *
 * **输出**：批量调价单 + 影响预览报告。
 *
 * **关系**：上游 = 5.3.1.2、4.1.1.1；下游 = 5.4.2.1。**必须先看预览再执行**，预览要包含"改完后预计亏损的商品数"这类反向指标。
 *
 * </blockquote>
 *
 * <h2>🔴 页面必须把「先预览再执行」做成一道真的闸</h2>
 *
 * <p>后端执行时**重算**预览并逐字比对预览令牌 —— 对不上就 409 拒绝。
 * 前端如果允许「跳过预览直接执行」，用户看到的就只是一个没有意义的 409，
 * 而不是「你得先看一眼影响范围」。所以这里的 {@link executeBatchAdjustment}
 * 永远要求带上 {@link BatchPriceAdjustmentPreview.previewToken}。
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>三种「没有」在这一页同时出现，**它们是三件事**：
 *
 * <ol>
 *   <li><b>跳过</b>：这个 listing 调不了（没有价格观测 / 币种不同 / 调完不是正价 /
 *       模板拒绝）⇒ 新价与前后利润都是 null。</li>
 *   <li><b>成本不完整</b>：改了，但缺项 ⇒ 毛利额与毛利率是 null，亏损标记也是 null。</li>
 *   <li><b>真的是 0</b>：毛利额恰好是 0 ⇒ 正常的边界值。</li>
 * </ol>
 *
 * ⇒ {@link moneyText} / {@link percentText} / {@link lossText} 把它们渲染成**看得见**的三种样子。
 */

/** 调价方式。与后端 {@code AdjustMode} 逐字对应。 */
export type AdjustMode = 'FIXED_AMOUNT' | 'PERCENT' | 'TEMPLATE';

/** 明细行状态。与后端 {@code ItemStatus} 逐字对应。 */
export type ItemStatus = 'WILL_ADJUST' | 'SKIPPED';

/** 明细排序。与后端 {@code Sort} 逐字对应。 */
export type AdjustmentSort =
  | 'DELTA_ASC'
  | 'DELTA_DESC'
  | 'OLD_PRICE_ASC'
  | 'OLD_PRICE_DESC'
  | 'MARGIN_ASC'
  | 'MARGIN_DESC';

/** 亏损筛选。 */
export type LossFilter = '' | 'LOSS_AFTER' | 'LOSS_BEFORE';

/** 目标店铺（平台 + 站点）。 */
export interface ShopRef {
  platformCode: string;
  siteCode: string;
}

/** 批量调价参数。预览与执行共用同一份。 */
export interface BatchPriceAdjustmentRequest {
  adjustMode: AdjustMode;
  adjustAmount: number | null;
  adjustRate: number | null;
  templateId: string | null;
  currency: string;
  costCurrency: string;
  fxRate: number | null;
  shops: ShopRef[];
  skuIds: string[];
  category: string;
  brand: string;
  quantity: number | null;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  monthlyFixedCost: number | null;
  costWindowFrom: string | null;
  costWindowTo: string | null;
  daysOfCover: number | null;
  competitorReferencePrice: number | null;
  competitorCurrency: string | null;
  note: string;
}

/** 执行请求：参数 + 预览确认（蓝图的输入句：「目标集合；调价参数；**预览确认**」）。 */
export interface BatchPriceAdjustmentExecuteRequest {
  request: BatchPriceAdjustmentRequest;
  previewToken: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface BatchPriceAdjustmentCapabilities {
  sites: SiteOption[];
  listingDefinitionNote: string;
  priceSourceNote: string;
  upstreamNote: string;
  downstreamNote: string;
  previewGateNote: string;
  reverseMetricNote: string;
  roundingNote: string;
  skipNote: string;
  limitNote: string;
  permissionNote: string;
}

/** 调价单明细的一行（= 目标集合里的一个 listing）。 */
export interface BatchPriceAdjustmentItem {
  id: string | null;
  adjustmentId: string | null;
  skuId: string;
  skuCode: string;
  skuName: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  status: ItemStatus;
  skipReason: string;
  oldPrice: number | null;
  newPrice: number | null;
  priceDelta: number | null;
  priceSource: string | null;
  priceObservedAt: string | null;
  grossProfitBefore: number | null;
  grossMarginBefore: number | null;
  grossProfitAfter: number | null;
  grossMarginAfter: number | null;
  decisionReady: boolean;
  lossBefore: boolean | null;
  lossAfter: boolean | null;
}

/**
 * 影响预览报告。
 *
 * 🔴 {@code profitDeltaTotal} 是 **null** 时表示「有一批 listing 没测准」——
 * 合计会把没测准的行当成 0，那是一个系统性虚高的数字。
 */
export interface BatchPriceAdjustmentPreview {
  previewToken: string;
  adjustMode: AdjustMode;
  adjustModeLabel: string;
  adjustAmount: number | null;
  adjustRate: number | null;
  templateId: string | null;
  templateCode: string | null;
  templateName: string | null;
  currency: string;
  costCurrency: string;
  fxRate: number | null;
  shopCount: number;
  shops: string[];
  skuCount: number;
  listingCount: number;
  willAdjustCount: number;
  skippedCount: number;
  lossBeforeCount: number;
  lossAfterCount: number;
  lossAfterSkuCount: number;
  profitBeforeTotal: number | null;
  profitAfterTotal: number | null;
  profitDeltaTotal: number | null;
  avgMarginBefore: number | null;
  avgMarginAfter: number | null;
  items: BatchPriceAdjustmentItem[];
  note: string;
}

/** 一次批量调价（调价单 + 影响预览报告快照）。 */
export interface BatchPriceAdjustmentDetail {
  id: string;
  adjustMode: AdjustMode;
  adjustModeLabel: string;
  adjustAmount: number | null;
  adjustRate: number | null;
  templateId: string | null;
  templateCode: string | null;
  templateName: string | null;
  currency: string;
  costCurrency: string;
  fxRate: number | null;
  scopeCategory: string;
  scopeBrand: string;
  scopeSkuIds: string;
  scopeShops: string;
  quantity: number;
  platformFeeRate: number | null;
  platformFeeFixed: number | null;
  logisticsCostPerUnit: number | null;
  monthlyFixedCost: number | null;
  costWindowFrom: string | null;
  costWindowTo: string | null;
  shopCount: number;
  skuCount: number;
  listingCount: number;
  willAdjustCount: number;
  skippedCount: number;
  lossBeforeCount: number;
  lossAfterCount: number;
  lossAfterSkuCount: number;
  profitBeforeTotal: number | null;
  profitAfterTotal: number | null;
  profitDeltaTotal: number | null;
  avgMarginBefore: number | null;
  avgMarginAfter: number | null;
  previewToken: string;
  note: string;
  createdBy: string | null;
  createdAt: string;
}

/** 调价单列表（分页）。 */
export interface BatchPriceAdjustmentPage {
  items: BatchPriceAdjustmentDetail[];
  page: number;
  size: number;
  total: number;
}

/** 明细列表（分页）。 */
export interface BatchPriceAdjustmentItemPage {
  items: BatchPriceAdjustmentItem[];
  page: number;
  size: number;
  total: number;
}

/** 明细筛选。 */
export interface AdjustmentItemFilters {
  status: ItemStatus | '';
  loss: LossFilter;
  keyword: string;
  sort: AdjustmentSort;
}

// ================================================================ 分区描述符

/** 分区里的一个表单项。 */
export interface AdjustmentSectionField {
  id: string;
  label: string;
  hint: string;
  kind: 'select' | 'text' | 'number' | 'textarea';
  required?: boolean;
}

/**
 * 批量调价分区的描述符 —— **组件从它渲染，测试也从它断言**。
 *
 * <p>🔴 断言写在字面量上，兄弟模块一旦交付就会红。
 * 断言「本分区渲染出了它自己的标题与字段」这件事本身是稳的 ——
 * 它只依赖本模块自己的描述符。
 */
export interface AdjustmentSectionDescriptor {
  key: string;
  title: string;
  description: string;
  fields: AdjustmentSectionField[];
}

export const ADJUSTMENT_SECTION: AdjustmentSectionDescriptor = {
  key: 'batch-price-adjustment',
  title: '批量调价',
  description:
    '对选中的一批 listing 统一改价（固定金额 / 百分比 / 按模板）：先预览影响范围，再确认执行。预览会告诉你涉及多少店铺、多少商品、预计利润变化，以及改完后预计亏损的商品数。',
  fields: [
    { id: 'adjustMode', label: '调价方式', hint: '固定金额 / 百分比 / 按模板', kind: 'select', required: true },
    { id: 'shops', label: '目标店铺', hint: '可以多选 —— 预览里的「涉及多少店铺」', kind: 'select', required: true },
    { id: 'category', label: '分类', hint: 'spu 分类精确匹配，空 = 不限定', kind: 'text' },
    { id: 'brand', label: '品牌', hint: 'spu 品牌精确匹配，空 = 不限定', kind: 'text' },
    { id: 'adjustAmount', label: '固定金额', hint: '可负 = 降价', kind: 'number' },
    { id: 'adjustRate', label: '涨跌幅', hint: '0.1 = +10%，小数口径', kind: 'number' },
    { id: 'platformFeeRate', label: '平台费率（手工假设）', hint: '0.15 = 15%', kind: 'number' },
    { id: 'logisticsCostPerUnit', label: '物流每单位运费（手工假设）', hint: '每单位尾程运费', kind: 'number' },
  ],
};

// ================================================================ 纯函数：口径渲染

/**
 * 金额渲染。
 *
 * <p>🔴 {@code null} ⇒ 「未计入」，**不是 0.00**。
 * 「取不到」与「确实是 0」是两件事。
 */
export function moneyText(value: number | null | undefined, fallback = '未计入'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return trimZeros(value.toFixed(4));
}

/** 金额 + 币种。取不到时**不**补币种。 */
export function moneyWithCurrency(
  value: number | null | undefined,
  currency: string | null | undefined,
  fallback = '未计入',
): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return currency ? `${moneyText(value)} ${currency}` : moneyText(value);
}

/**
 * 售价渲染（2 位小数 —— 对外标价就是两位小数的钱）。
 *
 * <p>🔴 {@code null} ⇒ 「未计入」，不是 0.00。
 */
export function priceText(value: number | null | undefined, fallback = '未计入'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  return trimZeros(value.toFixed(2));
}

/**
 * 毛利率渲染。
 *
 * <p>🔴 {@code null} ⇒ 「不可信（成本不完整）」，**不是 0%**。
 */
export function percentText(
  rate: number | null | undefined,
  fallback = '不可信（成本不完整）',
): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return fallback;
  return `${trimZeros((rate * 100).toFixed(2))}%`;
}

/** 价差渲染：正 = 涨价，负 = 降价。 */
export function deltaText(value: number | null | undefined, fallback = '—'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return fallback;
  const sign = value > 0 ? '+' : '';
  return `${sign}${trimZeros(value.toFixed(2))}`;
}

function trimZeros(text: string): string {
  if (!text.includes('.')) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/** 调价方式的中文名。 */
export function adjustModeLabel(mode: AdjustMode | null | undefined): string {
  switch (mode) {
    case 'FIXED_AMOUNT':
      return '固定金额';
    case 'PERCENT':
      return '百分比';
    case 'TEMPLATE':
      return '按模板';
    default:
      return '—';
  }
}

/** 明细状态的中文名。 */
export function itemStatusLabel(status: ItemStatus | null | undefined): string {
  switch (status) {
    case 'WILL_ADJUST':
      return '将调价';
    case 'SKIPPED':
      return '已跳过';
    default:
      return '—';
  }
}

/**
 * 亏损标记的中文名。
 *
 * <p>🔴 {@code null} ⇒ 「不可信」，**不是「不亏」**：成本不完整时算不出毛利率，
 * 那种 listing 是「没测准」而不是「没亏」。
 */
export function lossText(loss: boolean | null | undefined): string {
  if (loss === null || loss === undefined) return '不可信';
  return loss ? '亏损' : '不亏';
}

/** 售价来源的中文名。 */
export function priceSourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'INTERNAL_PRICE':
      return '我方价';
    case 'PLATFORM_PRICE':
      return '平台在售价';
    default:
      return '—';
  }
}

/**
 * 一行的色调。
 *
 * <p>🔴 跳过的行**不是** danger —— 它不是「有问题」，它是「调不了」。
 */
export function itemTone(
  item: BatchPriceAdjustmentItem,
): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  if (item.status === 'SKIPPED') return 'neutral';
  if (item.lossAfter === true) return 'danger';
  if (item.lossAfter === null) return 'info';
  return 'success';
}

// ================================================================ 纯函数：预览报告

/** 顶部统计卡。 */
export interface AdjustmentStat {
  label: string;
  value: string;
  hint: string;
  tone: 'success' | 'warning' | 'danger' | 'info';
}

/**
 * 预览报告的统计卡。
 *
 * <p>🔴 **反向指标必须单独成卡**：「改完后预计亏损的商品数」是蓝图加粗句点名要的，
 * 它不能被塞进「预计利润变化」里 —— 一批 listing 整体利润上升，
 * 也可能是因为其中几个从「小赚」变成「巨亏」。
 */
export function previewStats(preview: BatchPriceAdjustmentPreview | null): AdjustmentStat[] {
  if (!preview) return [];
  return [
    {
      label: '涉及店铺',
      value: String(preview.shopCount),
      hint: preview.shops.join(' · ') || '—',
      tone: 'info',
    },
    {
      label: '涉及商品',
      value: String(preview.skuCount),
      hint: `目标 listing ${preview.listingCount} 个（商品 × 店铺）`,
      tone: 'info',
    },
    {
      label: '将调价',
      value: String(preview.willAdjustCount),
      hint: `跳过 ${preview.skippedCount} 个`,
      tone: preview.willAdjustCount > 0 ? 'success' : 'warning',
    },
    {
      label: '改价后亏损 listing',
      value: String(preview.lossAfterCount),
      hint: `改价前 ${preview.lossBeforeCount} 个`,
      tone: preview.lossAfterCount > 0 ? 'danger' : 'success',
    },
    {
      label: '改价后预计亏损商品数',
      value: String(preview.lossAfterSkuCount),
      hint: '至少一个 listing 亏损的去重商品数（反向指标）',
      tone: preview.lossAfterSkuCount > 0 ? 'danger' : 'success',
    },
    {
      label: '预计利润变化',
      value:
        preview.profitDeltaTotal === null
          ? '不可信'
          : `${deltaText(preview.profitDeltaTotal)} ${preview.currency}`,
      hint:
        preview.profitDeltaTotal === null
          ? '有一批 listing 成本不完整，没测准 —— 合计不给（不是 0）'
          : `改价前 ${moneyText(preview.profitBeforeTotal)} → 改价后 ${moneyText(
              preview.profitAfterTotal,
            )} ${preview.currency}`,
      tone:
        preview.profitDeltaTotal === null
          ? 'warning'
          : preview.profitDeltaTotal < 0
            ? 'danger'
            : 'success',
    },
    {
      label: '平均毛利率',
      value: percentText(preview.avgMarginAfter, '不可信'),
      hint: `改价前 ${percentText(preview.avgMarginBefore, '不可信')}`,
      tone: 'info',
    },
  ];
}

// ================================================================ 纯函数：输入与校验

/** 校验失败。 */
export interface AdjustmentValidationError {
  field: string;
  message: string;
}

/** 空表单。 */
export function emptyRequest(): BatchPriceAdjustmentRequest {
  return {
    adjustMode: 'PERCENT',
    adjustAmount: null,
    adjustRate: null,
    templateId: null,
    currency: 'USD',
    costCurrency: 'USD',
    fxRate: null,
    shops: [],
    skuIds: [],
    category: '',
    brand: '',
    quantity: 1,
    platformFeeRate: null,
    platformFeeFixed: null,
    logisticsCostPerUnit: null,
    monthlyFixedCost: null,
    costWindowFrom: null,
    costWindowTo: null,
    daysOfCover: null,
    competitorReferencePrice: null,
    competitorCurrency: null,
    note: '',
  };
}

/** 空筛选。 */
export function emptyFilters(): AdjustmentItemFilters {
  return { status: '', loss: '', keyword: '', sort: 'DELTA_ASC' };
}

/**
 * 提交前的本地校验（后端还会再校验一遍，这里是为了少一次往返）。
 *
 * <p>🔴 跨币种没给汇率 ⇒ 拒绝提交。绝不按 1:1 折算。
 */
export function validateRequest(
  request: BatchPriceAdjustmentRequest,
): AdjustmentValidationError | null {
  if (!request.shops.length) {
    return { field: 'shops', message: '必须指定至少一个目标店铺' };
  }
  if (!/^[A-Za-z]{3}$/.test(request.currency)) {
    return { field: 'currency', message: '币种必须是三位大写字母' };
  }
  if (request.costCurrency && !/^[A-Za-z]{3}$/.test(request.costCurrency)) {
    return { field: 'costCurrency', message: '币种必须是三位大写字母' };
  }
  if (request.adjustMode === 'FIXED_AMOUNT') {
    if (request.adjustAmount === null) {
      return { field: 'adjustAmount', message: '固定金额方式必须给出调价金额（可负 = 降价）' };
    }
    if (request.adjustAmount === 0) {
      return { field: 'adjustAmount', message: '调价金额不能是 0 —— 那什么都不会变' };
    }
  }
  if (request.adjustMode === 'PERCENT') {
    if (request.adjustRate === null) {
      return { field: 'adjustRate', message: '百分比方式必须给出涨跌幅（小数口径，0.1 = +10%）' };
    }
    if (request.adjustRate <= -1) {
      return { field: 'adjustRate', message: '涨跌幅不能小于 -100%（那会把价格变成负数）' };
    }
    if (request.adjustRate === 0) {
      return { field: 'adjustRate', message: '涨跌幅不能是 0 —— 那什么都不会变' };
    }
  }
  if (request.adjustMode === 'TEMPLATE' && !request.templateId) {
    return { field: 'templateId', message: '按模板方式必须选择一条定价模板' };
  }
  if (request.quantity !== null && request.quantity <= 0) {
    return { field: 'quantity', message: '数量必须大于 0' };
  }
  if (
    request.platformFeeRate !== null &&
    (request.platformFeeRate < 0 || request.platformFeeRate >= 1)
  ) {
    return { field: 'platformFeeRate', message: '平台费率是小数口径（0.15 表示 15%）' };
  }
  if (request.logisticsCostPerUnit !== null && request.logisticsCostPerUnit < 0) {
    return { field: 'logisticsCostPerUnit', message: '物流每单位运费不能为负' };
  }
  if (
    request.currency.toUpperCase() !== request.costCurrency.toUpperCase() &&
    (request.fxRate === null || request.fxRate <= 0)
  ) {
    return {
      field: 'fxRate',
      message: '售价币种与成本币种不同，必须给汇率（1 成本币种 = X 售价币种）—— 绝不按 1:1 折算',
    };
  }
  return null;
}

/** 店铺是否已在目标集合里。 */
export function hasShop(shops: ShopRef[], platformCode: string, siteCode: string): boolean {
  return shops.some((s) => s.platformCode === platformCode && s.siteCode === siteCode);
}

/** 增删一个目标店铺。 */
export function toggleShop(
  shops: ShopRef[],
  platformCode: string,
  siteCode: string,
): ShopRef[] {
  if (hasShop(shops, platformCode, siteCode)) {
    return shops.filter((s) => !(s.platformCode === platformCode && s.siteCode === siteCode));
  }
  return [...shops, { platformCode, siteCode }];
}

/** 目标集合的人话摘要。 */
export function scopeSummary(request: BatchPriceAdjustmentRequest): string {
  const parts: string[] = [];
  parts.push(
    request.shops.length
      ? `店铺 ${request.shops.map((s) => `${s.platformCode}/${s.siteCode}`).join('、')}`
      : '未选店铺',
  );
  parts.push(`分类 ${request.category || '不限'}`);
  parts.push(`品牌 ${request.brand || '不限'}`);
  if (request.adjustMode === 'FIXED_AMOUNT') {
    parts.push(
      `固定金额 ${deltaText(request.adjustAmount)} ${request.currency}`,
    );
  } else if (request.adjustMode === 'PERCENT') {
    parts.push(`涨跌幅 ${percentText(request.adjustRate, '—')}`);
  } else {
    parts.push('按定价模板');
  }
  return parts.join(' · ');
}

// ================================================================ 端点

export function fetchAdjustmentCapabilities(): Promise<BatchPriceAdjustmentCapabilities> {
  return api<BatchPriceAdjustmentCapabilities>('catalog/batch-price-adjustments/capabilities');
}

export function fetchAdjustmentSites(): Promise<SiteOption[]> {
  return api<SiteOption[]>('catalog/batch-price-adjustments/sites');
}

/** 预览影响范围（**无副作用**：后端在一个注定回滚的事务里跑它）。 */
export function previewBatchAdjustment(
  request: BatchPriceAdjustmentRequest,
): Promise<BatchPriceAdjustmentPreview> {
  return api<BatchPriceAdjustmentPreview>(
    'catalog/batch-price-adjustments/preview',
    'POST',
    request,
  );
}

/** 执行批量调价（必须带上预览确认令牌）。 */
export function executeBatchAdjustment(
  request: BatchPriceAdjustmentRequest,
  previewToken: string,
): Promise<BatchPriceAdjustmentDetail> {
  const payload: BatchPriceAdjustmentExecuteRequest = { request, previewToken };
  return api<BatchPriceAdjustmentDetail>(
    'catalog/batch-price-adjustments',
    'POST',
    payload,
  );
}

export function fetchAdjustments(page = 0, size = 20): Promise<BatchPriceAdjustmentPage> {
  return api<BatchPriceAdjustmentPage>(`catalog/batch-price-adjustments?page=${page}&size=${size}`);
}

export function fetchAdjustmentItems(
  adjustmentId: string,
  filters: AdjustmentItemFilters,
  page = 0,
  size = 20,
): Promise<BatchPriceAdjustmentItemPage> {
  const params = new URLSearchParams();
  if (filters.status) params.set('status', filters.status);
  if (filters.loss) params.set('loss', filters.loss);
  if (filters.keyword.trim()) params.set('keyword', filters.keyword.trim());
  if (filters.sort) params.set('sort', filters.sort);
  params.set('page', String(page));
  params.set('size', String(size));
  return api<BatchPriceAdjustmentItemPage>(
    `catalog/batch-price-adjustments/${adjustmentId}/items?${params.toString()}`,
  );
}
