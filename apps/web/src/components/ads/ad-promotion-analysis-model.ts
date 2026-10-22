import { api } from '../inventory/api';

/**
 * 14.4.2.1 促销效果分析 前端模型。
 *
 * <p>蓝图：「促销效果分析：促销期间销量/销售额/毛利变化、增量判断、补贴成本、ROI，与活动前的基期对比。
 * 输入：促销数据；历史基线；利润数据。输出：效果报表 + 增量估算 + ROI。
 * 关系：上游 = 14.4.1；下游 = 13.4 利润、14.2.2.1 预算。
 * <strong>促销期间毛利可能大幅下滑</strong>，评估必须看毛利而不是销售额。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **销售额与毛利必须同屏**。毛利不是附注 —— 判定看的是毛利，
 *    两列分开在不同区块就等于把「促销期间毛利大幅下滑」藏起来了。
 * 2. **不可比不是 0**。`null` 渲染成「不可比」：没登记成本 ⇒ 毛利不可比；
 *    促销类型没有可折算价格系数 ⇒ 补贴不可比。0 会被读成「毛利是 0」/「这次没花钱」。
 * 3. **判定看毛利**。`incrementVerdict` 的判据是**增量毛利**，不是增量销售额；
 *    界面要把「放量不赚」单独标出来。
 * 4. **口径写在页面里**。基期怎么取、补贴怎么算、ROI 怎么来，都随响应返回并显示，
 *    不让人靠猜。
 * 5. **跑一次分析的留痕看得见**。跳过的促销（没起止时间 / 没有适用商品）必须显示出来 ——
 *    静默跳过等于让人以为「全都算了」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 毛利、增量、ROI 都是服务端 `BigDecimal` 的事，前端算一遍就会引入浮点误差，
 * 而且会让「界面上的毛利」和「账上的毛利」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdAnalysisMoney = number | null;

/** 🔴 null = 不可比（没成本 / 没基准价 / 类型不可折算），与 `0`（可比但真的是零）含义相反。 */
export type AdAnalysisRatio = number | null;

export interface AdAnalysisCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdAnalysisCapabilities {
  dimensions: AdAnalysisCapabilityDimension[];
  incrementVerdicts: string[];
  reportDimensions: string[];
  sorts: string[];
  salesNote: string;
  marginNote: string;
  baselineNote: string;
  incrementNote: string;
  subsidyNote: string;
  roiNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdAnalysisOption {
  code: string;
  label: string;
}

/** 促销下拉项（带 id —— 分析与筛选要的是促销 id，不只是编码）。 */
export interface AdAnalysisPromotionOption {
  id: string;
  promotionCode: string;
  promotionName: string;
  promotionType: string;
  currency: string;
  startAt: string | null;
  endAt: string | null;
  status: string;
}

export interface AdAnalysisPlatformOption {
  id: string;
  code: string;
  name: string;
}

export interface AdAnalysisShopOption {
  code: string;
  name: string;
}

export interface AdAnalysisOptions {
  incrementVerdicts: AdAnalysisOption[];
  dimensions: AdAnalysisOption[];
  sorts: AdAnalysisOption[];
  promotions: AdAnalysisPromotionOption[];
  platforms: AdAnalysisPlatformOption[];
  shops: AdAnalysisShopOption[];
  currencies: string[];
  salesNote: string;
  marginNote: string;
  baselineNote: string;
}

/**
 * 效果报表行。
 *
 * 🔴 每一行**同时**给出销售额与毛利（`promoSales` 与 `promoGrossProfit`），
 * 基期对照与增量也在同一行 —— 「促销期毛利下滑」这个判断要能在同一行里做出来。
 */
export interface AdAnalysisRow {
  promotionId: string;
  promotionCode: string;
  promotionName: string;
  promotionType: string;
  platformCode: string;
  shopCode: string;
  listingId: string | null;
  listingTitle: string | null;
  externalItemId: string | null;
  currency: string;
  periodFromAt: string | null;
  periodToAt: string | null;
  periodDays: number;
  periodCoveredDays: number;
  promoUnits: AdAnalysisMoney;
  promoSales: AdAnalysisMoney;
  promoCost: AdAnalysisMoney;
  promoGrossProfit: AdAnalysisMoney;
  promoGrossMarginRate: AdAnalysisRatio;
  baselineFromAt: string | null;
  baselineToAt: string | null;
  baselineDays: number;
  baselineCoveredDays: number;
  baselineUnits: AdAnalysisMoney;
  baselineSales: AdAnalysisMoney;
  baselineCost: AdAnalysisMoney;
  baselineGrossProfit: AdAnalysisMoney;
  baselineGrossMarginRate: AdAnalysisRatio;
  incrementUnits: AdAnalysisMoney;
  incrementSales: AdAnalysisMoney;
  incrementGrossProfit: AdAnalysisMoney;
  subsidyUnit: AdAnalysisMoney;
  subsidyCost: AdAnalysisMoney;
  roiNumerator: AdAnalysisMoney;
  roiDenominator: AdAnalysisMoney;
  roiValue: AdAnalysisRatio;
  incrementVerdict: string;
  marginAlert: boolean;
  productCount: number;
  grossProfitProductCount: number;
  comparableProductCount: number;
  dataAsOf: string | null;
  metricDefinitionNote: string;
}

export interface AdAnalysisPage {
  items: AdAnalysisRow[];
  total: number;
  page: number;
  size: number;
  metricDefinitionNote: string;
}

/** 成本与售价快照（catalog 的成本被复制进 channel 自己的表）。 */
export interface AdAnalysisCostSnapshotRow {
  id: string;
  promotionId: string;
  promotionCode: string;
  promotionName: string;
  listingId: string;
  listingTitle: string;
  externalItemId: string;
  currency: string;
  unitCost: AdAnalysisMoney;
  basePrice: AdAnalysisMoney;
  sourceRef: string;
  capturedAt: string | null;
  note: string;
  version: number;
  updatedAt: string | null;
}

export interface AdAnalysisCostSnapshotPage {
  items: AdAnalysisCostSnapshotRow[];
  total: number;
  page: number;
  size: number;
}

/** 分析运行留痕：算了哪些、跳过了哪些、为什么跳过。 */
export interface AdAnalysisRunRow {
  id: string;
  status: string;
  promotionId: string | null;
  analyzedCount: number;
  skippedCount: number;
  skipped: string;
  dataAsOf: string | null;
  metricDefinitionNote: string;
  note: string;
  createdAt: string | null;
}

/** 导出留痕：谁、什么时候、什么筛选条件、导出了多少行。 */
export interface AdAnalysisExportRow {
  id: string;
  requestedBy: string;
  dimension: string;
  promotionId: string | null;
  platformId: string | null;
  shopCode: string | null;
  currency: string | null;
  incrementVerdict: string | null;
  dateFrom: string | null;
  dateTo: string | null;
  rowCount: number;
  dataAsOf: string | null;
  metricDefinitionNote: string;
  createdAt: string | null;
}

export interface AdAnalysisExportPage {
  items: AdAnalysisExportRow[];
  total: number;
  page: number;
  size: number;
}

// ==================================================================
// 请求
// ==================================================================

export interface AdAnalysisQuery {
  dimension?: string | undefined;
  sort?: string | undefined;
  promotionId?: string | undefined;
  platformId?: string | undefined;
  shopCode?: string | undefined;
  currency?: string | undefined;
  incrementVerdict?: string | undefined;
  marginAlertOnly?: boolean | undefined;
  keyword?: string | undefined;
  dateFrom?: string | undefined;
  dateTo?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 登记成本与售价快照（成本进入本服务的唯一通道）。 */
export interface SaveAdAnalysisCostSnapshotInput {
  promotionId: string;
  listingId: string;
  unitCost: number;
  basePrice?: number | undefined;
  currency?: string | undefined;
  sourceRef?: string | undefined;
  capturedAt?: string | undefined;
  note?: string | undefined;
}

/** 导出留痕请求：只带筛选条件（服务端按同样的条件重算，不存数据快照）。 */
export interface AdAnalysisExportRequest {
  dimension: string;
  promotionId?: string | undefined;
  platformId?: string | undefined;
  shopCode?: string | undefined;
  currency?: string | undefined;
  incrementVerdict?: string | undefined;
  dateFrom?: string | undefined;
  dateTo?: string | undefined;
}

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

export function fetchAdPromotionAnalysisCapabilities(): Promise<AdAnalysisCapabilities> {
  return api<AdAnalysisCapabilities>('channel/ad-promotion-analysis/capabilities');
}

export function fetchAdPromotionAnalysisOptions(): Promise<AdAnalysisOptions> {
  return api<AdAnalysisOptions>('channel/ad-promotion-analysis/options');
}

/** 效果报表：真聚合 / 真分页 / 真筛选 / 真排序。 */
export function fetchAdPromotionAnalysisReport(query: AdAnalysisQuery = {}): Promise<AdAnalysisPage> {
  return api<AdAnalysisPage>(`channel/ad-promotion-analysis/report${queryString(query)}`);
}

/** 跑一次分析（写动作 ⇒ POST）。 */
export function runAdPromotionAnalysis(promotionId?: string): Promise<AdAnalysisRunRow> {
  return api<AdAnalysisRunRow>('channel/ad-promotion-analysis/runs', 'POST', {
    promotionId: promotionId ?? null,
    note: '页面发起',
  });
}

export function fetchAdPromotionAnalysisRuns(page = 0, size = 20): Promise<AdAnalysisRunRow[]> {
  return api<AdAnalysisRunRow[]>(
    `channel/ad-promotion-analysis/runs${queryString({ page, size })}`,
  );
}

export function fetchAdPromotionAnalysisCostSnapshots(
  query: { promotionId?: string | undefined; listingId?: string | undefined; page?: number | undefined; size?: number | undefined } = {},
): Promise<AdAnalysisCostSnapshotPage> {
  return api<AdAnalysisCostSnapshotPage>(
    `channel/ad-promotion-analysis/cost-snapshots${queryString(query)}`,
  );
}

export function saveAdPromotionAnalysisCostSnapshot(
  input: SaveAdAnalysisCostSnapshotInput,
): Promise<AdAnalysisCostSnapshotRow> {
  return api<AdAnalysisCostSnapshotRow>('channel/ad-promotion-analysis/cost-snapshots', 'POST', input);
}

/** 登记一次导出（留痕：谁、什么时候、什么筛选条件、导出了多少行）。 */
export function createAdPromotionAnalysisExport(
  request: AdAnalysisExportRequest,
): Promise<AdAnalysisExportRow> {
  return api<AdAnalysisExportRow>('channel/ad-promotion-analysis/exports', 'POST', request);
}

export function fetchAdPromotionAnalysisExports(page = 0, size = 20): Promise<AdAnalysisExportPage> {
  return api<AdAnalysisExportPage>(
    `channel/ad-promotion-analysis/exports${queryString({ page, size })}`,
  );
}

/** 导出 CSV 的下载链接（按导出留痕 id 下载，与当时的筛选条件完全一致）。 */
export function adPromotionAnalysisExportCsvUrl(exportId: string): string {
  return `/api/channel/ad-promotion-analysis/exports/${exportId}/csv`;
}

// ==================================================================
// 展示辅助
// ==================================================================

/**
 * 金额展示：**不可比显示「不可比」，不是 0.00**。
 *
 * 🔴 0 是「可比，但真的是零」；null 是「压根算不出来」（没登记成本快照）。
 */
export function analysisMoney(value: AdAnalysisMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '不可比';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 数量展示（销量 / 件数）：null 同样是「不可比」。 */
export function analysisQuantity(value: AdAnalysisMoney | undefined): string {
  if (value === null || value === undefined) return '不可比';
  return value.toFixed(2);
}

/**
 * 比率展示：毛利率按百分比，ROI 按倍数。
 *
 * 🔴 两者都**不做任何前端算术** —— 服务端给什么就显示什么；
 * null 一律是「不可比」，不是 0.00% / 0.00。
 */
export function analysisPercent(value: AdAnalysisRatio | undefined): string {
  if (value === null || value === undefined) return '不可比';
  return `${(value * 100).toFixed(2)}%`;
}

export function analysisRatio(value: AdAnalysisRatio | undefined): string {
  if (value === null || value === undefined) return '不可比';
  return value.toFixed(4);
}

/**
 * 🔴 增量判定显示名。
 *
 * <p>判据是**增量毛利**，文案里必须写清楚 —— 界面上不能让人以为
 * 「销售额涨了所以是增量」。
 */
export function analysisVerdictLabel(verdict: string): string {
  switch (verdict) {
    case 'INCREMENTAL':
      return '增量（毛利增加）';
    case 'FLAT':
      return '持平（毛利不变）';
    case 'DECREMENTAL':
      return '下滑（毛利减少）';
    case 'INSUFFICIENT_DATA':
      return '数据不足（不可判定）';
    default:
      return verdict;
  }
}

export function analysisVerdictTone(verdict: string): string {
  switch (verdict) {
    case 'INCREMENTAL':
      return 'text-emerald-600';
    case 'FLAT':
      return 'text-muted-foreground';
    case 'DECREMENTAL':
      return 'text-destructive';
    default:
      return 'text-amber-600';
  }
}

/** 毛利为负时的色调：负毛利是本模块最该被看见的读数。 */
export function analysisGrossProfitTone(value: AdAnalysisMoney | undefined): string {
  if (value === null || value === undefined) return 'text-muted-foreground';
  if (value < 0) return 'text-destructive';
  return 'text-foreground';
}

/** 「放量不赚」告警的显示：单独一列，不与判定混在一起。 */
export function analysisMarginAlertText(alert: boolean): string {
  return alert ? '是（销售额涨了但毛利率跌了）' : '否';
}

export function analysisMarginAlertTone(alert: boolean): string {
  return alert ? 'text-destructive' : 'text-muted-foreground';
}

/** 覆盖度：「有平台快照的天数 / 促销期天数」 —— 没有数据与销量是 0 是两件事。 */
export function analysisCoverageText(covered: number, days: number): string {
  if (days <= 0) return '不可比';
  return `${covered}/${days} 天`;
}

export function analysisCoverageTone(covered: number, days: number): string {
  if (days <= 0) return 'text-muted-foreground';
  if (covered === 0) return 'text-amber-600';
  if (covered < days) return 'text-amber-600';
  return 'text-muted-foreground';
}

export function analysisTimestampText(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 促销期 / 基期窗口的展示（按站点当地自然日对齐）。 */
export function analysisWindowText(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  if (!from || !to) return '—';
  const start = new Date(from);
  const end = new Date(to);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return `${from} ~ ${to}`;
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  return `${fmt(start)} ~ ${fmt(end)}（不含）`;
}

/** 促销类型显示名（照抄 14.4.1.1 的口径，跨模块同一个词）。 */
export function analysisPromotionTypeLabel(type: string): string {
  switch (type) {
    case 'PERCENT_OFF':
      return '百分比折扣';
    case 'FIXED_AMOUNT_OFF':
      return '固定额减免';
    case 'BOGO':
      return '买 N 送 M';
    case 'BUNDLE':
      return '组合优惠';
    case 'FREE_SHIPPING':
      return '免运费';
    default:
      return type;
  }
}

/** 运行状态：PARTIAL 意味着**有促销被跳过了**。 */
export function analysisRunStatusLabel(status: string): string {
  switch (status) {
    case 'SUCCESS':
      return '全部算完';
    case 'PARTIAL':
      return '部分跳过';
    case 'FAILED':
      return '失败';
    default:
      return status;
  }
}

export function analysisRunStatusTone(status: string): string {
  if (status === 'SUCCESS') return 'text-emerald-600';
  if (status === 'PARTIAL') return 'text-amber-600';
  return 'text-destructive';
}

/** 跳过明细（jsonb 数组）渲染成人能读的一行行。 */
export function analysisSkippedEntries(skipped: string): { promotionCode: string; reason: string }[] {
  if (!skipped) return [];
  try {
    const parsed: unknown = JSON.parse(skipped);
    if (!Array.isArray(parsed)) return [];
    return parsed.map((item) => {
      const record = (item ?? {}) as { promotionCode?: unknown; reason?: unknown };
      return {
        promotionCode: typeof record.promotionCode === 'string' ? record.promotionCode : '',
        reason: typeof record.reason === 'string' ? record.reason : '',
      };
    });
  } catch {
    return [];
  }
}

// ==================================================================
// 表单校验（与服务端同口径，错了也别让用户白跑一趟）
// ==================================================================

export interface AdAnalysisCostInputState {
  promotionId: string;
  listingId: string;
  unitCost: string;
  basePrice: string;
  currency: string;
  sourceRef: string;
  note: string;
}

export function emptyAdAnalysisCostInput(): AdAnalysisCostInputState {
  return {
    promotionId: '',
    listingId: '',
    unitCost: '',
    basePrice: '',
    currency: '',
    sourceRef: '',
    note: '',
  };
}

function isNonNegativeAmount(raw: string): boolean {
  if (raw.trim() === '') return false;
  const value = Number(raw);
  return !Number.isNaN(value) && value >= 0;
}

/**
 * 成本快照表单校验。
 *
 * <p>🔴 **基准价可以留空**（留空 = 没登记 ⇒ 百分比促销的补贴不可比），
 * 但**单位成本不能留空** —— 单位成本缺了，毛利就不可比，登记这条快照没有意义。
 */
export function validateAdAnalysisCostInput(input: AdAnalysisCostInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.promotionId.trim()) errors.push('促销必填');
  if (!input.listingId.trim()) errors.push('商品 id 必填');
  if (!isNonNegativeAmount(input.unitCost)) errors.push('单位成本必须是不小于 0 的数字');
  if (input.basePrice.trim() !== '' && !isNonNegativeAmount(input.basePrice)) {
    errors.push('基准价必须是不小于 0 的数字（留空表示没登记）');
  }
  if (input.currency.trim() && input.currency.trim().length !== 3) {
    errors.push('币种必须是 3 位字母代码');
  }
  return { ok: errors.length === 0, errors };
}

/** 报表筛选的日期区间校验。 */
export function validateAdAnalysisRange(dateFrom: string, dateTo: string): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (dateFrom.trim() && dateTo.trim() && dateFrom.trim() > dateTo.trim()) {
    errors.push('起始日不能晚于截止日');
  }
  return { ok: errors.length === 0, errors };
}
